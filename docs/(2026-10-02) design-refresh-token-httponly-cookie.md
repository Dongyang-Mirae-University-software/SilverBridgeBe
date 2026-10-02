# refresh 토큰 HttpOnly 쿠키 전환 설계안 (XCUT-G31)

> 작성 2026-10-02 / 상태: **설계안(구현 전 결정용)** / 코드 변경 없음
> 근거: QA `XCUT-G31`(low)·`AUTH-G03`·`XCUT-G06` (`/home/mjng/varqa/handoff/QA_BE.md`). 인증 계약 전체가 바뀌므로 구현하지 않고 별도 설계로 두기로 했다. 10/22 전까지는 버그·점검만 하는 기간이라 **구현 착수는 그 이후**를 전제한다.

## 1. 현재 흐름 (코드 확인)

| 구간 | 현재 동작 | 위치 |
|---|---|---|
| 로그인 | 비밀번호 검증 후 access·refresh 생성, 기존 refresh 삭제(단일 기기 정책) 뒤 저장, `LoginResponse`(body)로 **두 토큰을 내린다** | `AuthService.login` (186~198행) |
| 카카오 로그인·가입 | 기존 회원이면 `KakaoLoginResponse` body에 두 토큰 | `KakaoAuthController` `/api/auth/signin/kakao` 등 |
| 갱신 | `POST /api/auth/refresh` body `{refreshToken}` → DB 조회, 없으면 `detectAndHandleReuse`(다른 토큰이 있으면 **전체 폐기**), 있으면 회전(삭제 후 신규 저장). IP rate limit | `AuthController` 161행, `AuthService.refresh` 234행 |
| 로그아웃 | `Authorization: Bearer access` 필수, userId의 refresh 전체 삭제 | `AuthController` 184행 |
| 인증 필터 | `Authorization: Bearer` 헤더만 읽는다. 쿠키는 읽지 않는다 | `JwtAuthenticationFilter` 118~122행 |
| CORS/CSRF | `allowCredentials(true)` + `app.cors.allowed-origins`(`APP_CORS_ORIGINS`, **WS 핸드셰이크 Origin 검증과 공용**). CSRF disable, STATELESS | `SecurityConfig` 52~55, 140~153행 |
| WebSocket | 핸드셰이크 쿼리 `?token=<access>` | `JwtHandshakeInterceptor` |
| 수명 | access 30분 / refresh 7일 | `application.yaml` 94~95행 |
| BE 쿠키 코드 | **없음** (`Set-Cookie`·`HttpOnly` grep 0건) | - |

**FE 저장 방식(QA 인용)**: FE `tokenStore.ts`가 `document.cookie`로 access·refresh 둘 다 `path=/; max-age=604800; SameSite=Lax`(HttpOnly·Secure 없음)로 저장한다. 서버 컴포넌트 `app/page.tsx`와 Next 라우트 `api/[...path]/route.ts`가 그 쿠키를 읽는다. 즉 **FE에는 이미 Next 쪽 프록시 라우트가 있다**(선택지 B의 전제). 또 탭마다 모듈 변수에 refresh를 캐시해 쿠키보다 우선 사용하는데(XCUT-G06·AUTH-G03), 이것이 회전 시 "재사용 감지 → 전체 폐기"를 정상 사용자에게 일으킨다.

**위협의 실제 범위(QA 검증 의견)**: XSS가 나면 access는 메모리에서도 탈취되므로 HttpOnly로 막히는 것은 **"refresh의 7일 지속 탈취"**다. 그래서 심각도 low다.

## 2. 선택지 비교

도메인: FE `devdmu.gosky.kr`, API `api.devdmu.gosky.kr`는 등록 도메인(`gosky.kr`)이 같아 **same-site**다(cross-origin이지만 same-site). `SameSite=Lax/Strict` 쿠키가 API 요청에 실린다. 단 `gosky.kr`는 우리가 소유한 도메인이 아니므로 **쿠키 `Domain` 속성은 지정하지 않고(host-only)** `Path=/api/auth`로 좁힌다. 상위 도메인 공유 쿠키는 다른 서브도메인 앱에 노출되므로 금지.

| 항목 | A) BE가 Set-Cookie | B) FE BFF가 쿠키 | C) 현행 + 완화 |
|---|---|---|---|
| 방식 | 로그인·refresh 응답에 `Set-Cookie: rt=...; HttpOnly; Secure; SameSite=Lax; Path=/api/auth; Max-Age=7d`. `/refresh`·`/logout`이 쿠키를 읽음 | Next 라우트가 BE 응답의 refresh를 받아 FE 도메인에 HttpOnly 쿠키로 심고, 갱신 때 BE에 body로 전달. **BE 계약 불변** | 쿠키 그대로, 수명 단축·재사용 감지 강화 |
| XSS 탈취 방어 | refresh는 JS 접근 불가(7일 지속 탈취 차단) | 동일 | 없음(피해 시간만 줄임) |
| CSRF | 쿠키 자동 전송 경로가 생겨 **새 위험**. 대책: SameSite=Lax + `/refresh`·`/logout`에 Origin/Referer 검증(허용 목록 = `APP_CORS_ORIGINS` 재사용) + `Content-Type: application/json` 강제(단순 폼 차단). 더블 서브밋은 추가 방어선(선택) | BFF가 같은 출처이므로 SameSite=Strict 가능. Origin 검증은 BFF에서 | 해당 없음 |
| CORS | 쿠키를 받으려면 FE가 `credentials: 'include'`, BE는 `allowCredentials(true)`(이미 켜짐)·**와일드카드 금지**(현재 명시 목록이라 충족) | 브라우저↔BFF는 same-origin이라 BE CORS 무관(서버 간 호출) | 변경 없음 |
| BE 변경량 | 큼(컨트롤러·DTO·로그아웃 계약·필터 아님) | **없음~소** | 소 |
| FE 변경량 | 중(저장 코드 제거, `credentials`) | 중~대(BFF 라우트가 토큰 보유 책임) | 소 |
| 카카오 로그인 | 카카오 로그인·가입 응답에도 쿠키 발급 필요(경로 3곳: 일반·카카오·카카오 가입) | BFF가 같은 처리 | 변경 없음 |
| 다중 탭 | **쿠키가 탭 간 단일 진실원본**이 되어 FE 모듈 변수 캐시 제거 시 XCUT-G06이 구조적으로 해소. 단 동시 갱신 경합(두 탭이 같은 쿠키로 동시 refresh)은 남음 → BE grace 필요 | BFF에서 직렬화 가능하나 서버리스 다중 인스턴스면 한계 | 별도 수정 필요 |
| WebSocket | access는 그대로 `?token=`(쿠키 영향 없음). Path=/api/auth라 `/ws`에는 쿠키가 안 간다 | 동일 | 동일 |
| 운영 의존 | HTTPS 필수(Secure). 두 서버의 nginx가 `Set-Cookie`를 버리지 않는지 확인 | FE 서버 런타임에 의존, BE 두 서버 영향 없음 | 없음 |

### 선택지별 한 줄 평가
- **A**: 표준적이고 FE가 토큰을 아예 못 만진다. 대신 BE 계약(`/refresh` body → 쿠키, `/logout`)이 바뀌고 CSRF 대책이 필수다.
- **B**: BE를 안 건드리지만 BFF가 신뢰 경계가 되어 FE 팀 부담이 크고, 이미 있는 `api/[...path]/route.ts` 프록시와 책임이 겹친다. 두 BE 서버(gosky·vkcs) 중 FE가 붙는 쪽만 이득.
- **C**: 위협을 줄이지 못한다. 단, XCUT-G06·AUTH-G03(재사용 감지 grace)은 **A·B와 무관하게 별도로 필요**하다.

## 3. 추천

**A로 가되, 병행 기간을 둔 단계 이행**을 추천한다. 이유: (1) 같은 site라 쿠키 도달이 보장되고, (2) 이미 `allowCredentials`·명시적 Origin 목록이 있어 CORS 추가 작업이 거의 없으며, (3) FE 다중 탭 결함을 쿠키 단일 원본으로 함께 줄일 수 있다. 득실은 "7일 지속 탈취 차단" 대 "CSRF 대책·계약 변경"이며 심각도가 low라 **10/22 이후 일정에 여유가 있을 때** 진행해도 늦지 않다. C의 grace(직전 회전 토큰 N초 허용)는 AUTH-G03과 묶어 **먼저 독립 PR**로 하는 것을 권한다(쿠키 전환 시 동시 갱신 경합이 더 자주 보일 수 있어 선행이 안전).

### 이행 계획

| 단계 | 담당 | 내용 | 롤백 |
|---|---|---|---|
| 0 | BE | 재사용 감지 grace(직전 토큰 수초 허용) — AUTH-G03·XCUT-G06 선행 | 설정값 0 |
| 1 | BE | 로그인·카카오·`/refresh`가 **body 유지 + Set-Cookie 동시 발급**. `/refresh`는 body 없으면 쿠키 사용, 둘 다 있으면 body 우선. `/logout`은 쿠키 삭제(`Max-Age=0`) 추가. Origin 검증. 쿠키 속성은 설정값(`auth.refresh-cookie.enabled/secure/same-site`)으로 두어 끌 수 있게 | 플래그 off로 기존 동작 |
| 2 | 서버 | 두 서버 `.env.dev`에 플래그·`APP_CORS_ORIGINS` 확인, nginx가 `Set-Cookie`·`Origin`을 통과시키는지 점검. 로컬(http)은 Secure 예외 필요 | `up -d`로 재생성(env는 restart로 반영 안 됨) |
| 3 | FE | refresh 저장·전송 코드 제거, `credentials: 'include'`, 서버 컴포넌트가 refresh 쿠키를 읽는 부분(`page.tsx`·route.ts)을 access 기준으로 변경 | FE 배포 롤백(BE는 병행이라 영향 없음) |
| 4 | BE | 응답 body의 `refreshToken` 제거(폐기 공지 후). Swagger·계약 문서 갱신 | 필드 복원 |

단계 1~3 사이에는 body와 쿠키가 모두 유효하므로 FE 먼저 배포되어도 깨지지 않는다(BE→FE 순서).

### 테스트 방법
- 단위: 쿠키 속성(HttpOnly·Secure·SameSite·Path)·로그아웃 삭제·body/쿠키 우선순위·Origin 불일치 403.
- MockMvc: 허용 Origin과 비허용 Origin, `Content-Type` 위반, 쿠키 없이 body만(병행기).
- 수동: 두 탭 동시 refresh(QA 재현 스크립트 `crosscut-b-low.spec.ts` 확장), 카카오 로그인, 로그아웃 후 쿠키 소멸, WS 연결 유지.

## 4. 결정이 필요한 질문

1. 선택지 A·B 중 어느 것인가? (B는 FE 팀 부담과 BFF 책임 명확화가 선행)
2. 쿠키 이름·`SameSite` 값: Lax(기본) vs Strict. Strict는 외부 링크 진입 시 첫 요청에 쿠키가 빠진다.
3. 병행 기간 길이와 body `refreshToken` 제거 시점(구버전 앱·캐시된 FE 존재 여부).
4. 재사용 감지 grace 허용 시간(예: 10초)과 "동일 토큰 동시 요청"의 응답 정책(같은 새 토큰 재발급 vs 409).
5. 쿠키 `Max-Age`: 7일 고정 vs 슬라이딩. 수명 단축(예: 3일)을 함께 할지.
6. access 토큰 저장(메모리 vs 단기 쿠키): 서버 컴포넌트가 access를 읽어야 하는 구조를 유지할지.
7. 로컬 개발(http://localhost)에서 Secure 해제 방식과 환경별 플래그 관리.
8. 모바일 앱·비브라우저 클라이언트가 있다면 body 방식을 계속 지원할지(있다면 4단계 보류).
9. 10/22 이후 일정 편성과, AUTH-G03 grace를 이 작업보다 먼저 할지.
