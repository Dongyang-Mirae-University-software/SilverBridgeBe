# 카카오 OAuth Client Secret — 스팟 점검 (시크릿 노출 + 작동 검증)

- **점검 일자**: 2026-05-25
- **점검 대상 작업**: `feature/kakao-client-secret` (PR #179, 커밋 `5adff48` → `dev` 머지)
- **점검 성격**: 미니 스팟 점검 (시크릿 노출 여부 + 최소 작동 검증)
- **관련 산출물**: `docs/(2026-05-25) feature-kakao-client-secret.md`

> ⚠️ 본 문서에는 실제 시크릿 값을 포함하지 않는다. 환경변수명·검증 결과만 기록한다.

---

## 0. 환경 확인 (PHASE -1)

| 항목 | 결과 |
|------|------|
| 오늘 날짜 | 2026-05-25 |
| Git 상태 | clean (현재 브랜치 `dev`) |
| 대상 커밋 | `5adff48 feat(auth): 카카오 OAuth Client Secret 적용` (PR #179 머지 완료) |
| 빌드 | ✅ `./gradlew build -x test --no-daemon` **EXIT 0** |

점검 대상 파일(11): `KakaoOAuthClient`, `RequiredPropertiesValidator`, `SecurityConfigValidator`,
`application.yaml`, `SecurityConfigValidatorTest`, `KakaoAuthService`, `.gitignore`,
`CLAUDE.md`, `프로젝트_설명.txt`, `docs/(2026-05-25) feature-kakao-client-secret.md`, `docs/progress.md`
+ Git 히스토리 스캔.

---

## 1. 시크릿 노출 점검 (PHASE A) — **PASS**

| 항목 | 점검 내용 | 결과 |
|------|-----------|------|
| **A1** 코드 평문 노출 | 전체 `.java`에서 32자+ 영숫자 리터럴 스캔 → 매칭 2건 모두 비(非)시크릿: `UserIdGenerator.CHARS`(Base62 ID 문자집합), `SecurityConfigValidatorTest.VALID_JWT_SECRET`(테스트용 placeholder). 실제 시크릿 하드코딩 없음 | ✅ |
| **A2** 설정 파일 노출 | `application.yaml` → `client-secret: ${KAKAO_CLIENT_SECRET:}` (환경변수 참조 + 빈 기본값). 평문 값 없음 | ✅ |
| **A3** `.gitignore` | `.env.*` 포함(line 2). `.env.dev` Git 미추적 확인(`git ls-files` 매칭 0). 추적되는 `.env`류 파일 없음 | ✅ |
| **A4** 로깅 안전성 | `KakaoOAuthClient.getToken`은 요청 파라미터를 로깅하지 않고 `status`+`errorCode`만 기록. `getUserInfo`도 `status`+`code`만. WebClient/RestClient 요청 바디 로깅 설정 없음. `client_secret`/`clientSecret`을 찍는 로그 코드 0건. `SecurityConfigValidator` 통과 로그는 **값이 아닌 라벨 텍스트**("JWT secret + Kakao client secret 정상")만 출력 | ✅ |
| **A5** Git 히스토리 | 커밋 `5adff48` diff 확인 → 코드는 변수 참조(`params.add("client_secret", clientSecret)`), yaml은 `${KAKAO_CLIENT_SECRET:}`. **실제 시크릿 값이 커밋된 흔적 없음** → 히스토리 정리 불필요 | ✅ |

추가 안전장치: `RequiredPropertiesValidator` fingerprint는 SHA-256 해시 **12자 prefix만** 노출(원문 복원 불가).

---

## 2. 작동 검증 (PHASE B) — **PASS**

| 항목 | 점검 내용 | 결과 |
|------|-----------|------|
| **B1** 검증기 동작 | `RequiredPropertiesValidator`: `KAKAO_CLIENT_SECRET` 검증 맵에 포함(11개 키), blank 시 다른 누락 키와 함께 `IllegalStateException`으로 시작 중단. `SecurityConfigValidator`: `MIN_KAKAO_CLIENT_SECRET_LENGTH = 32`, 길이<32·placeholder/약한 값 거부. 테스트 5케이스(정상/blank/짧음/약한값/대소문자) 커버 | ✅ |
| **B2** 토큰 교환 정합성 | `getToken`이 `grant_type`·`client_id`(=REST API Key)·`client_secret`·`redirect_uri`·`code`를 함께 전송. 파라미터명 `client_secret` 정확, 카카오 OAuth 토큰 엔드포인트 스펙 준수 | ✅ |
| **B3** 기존 흐름 회귀 | `getToken(code, redirectUri)` **시그니처 불변**(secret은 `@Value` 필드 주입) → `KakaoAuthService.kakaoLogin` 호출부 무영향. 기존/신규 사용자 분기(existing→로그인, new→Redis pending 후 `ofNewUser`) 로직 변경 없음 | ✅ |

> **fail-fast 보장**: `application.yaml`이 `kakao.client-secret`에 빈 기본값을 제공하므로
> `KakaoOAuthClient`의 `@Value("${kakao.client-secret}")`(기본값 없음)도 빈 값으로 해석되어
> 시작 실패가 아닌 `RequiredPropertiesValidator`의 명시적 중단으로 이어진다. 즉 시크릿이 비면
> **토큰 교환 이전에** 애플리케이션 시작이 차단된다.

---

## 3. 문서·운영 일관성 (PHASE C) — **PASS**

| 항목 | 점검 내용 | 결과 |
|------|-----------|------|
| **C1** 사용자 작업 안내 | feature 문서에 `.env.dev` 추가 라인(`KAKAO_CLIENT_SECRET=<발급값>`), 카카오 콘솔 [보안 > Client Secret] 코드 발급 절차, **"사용 함" 활성화** 경고, 컨테이너 재시작·검증 방법 명시 | ✅ |
| **C2** CLAUDE.md / 프로젝트_설명.txt | CLAUDE.md §9에 정책 메모(2026-05-25). `프로젝트_설명.txt`에 `KAKAO_CLIENT_SECRET` 5회 반영 — 환경변수 목록(11번 항목)·검증 규칙(최소 32자+약한 값 거부)·변경 일자(2026-05-25) 모두 명시 | ✅ |

---

## 4. 발견 이슈

**없음.** Critical/High/Medium/Low 모두 0건.

(참고·비차단) `KakaoOAuthClient`는 `${kakao.client-secret}`(기본값 없음), 두 Validator는
`${kakao.client-secret:}`(빈 기본값)으로 placeholder 표기가 다르다. application.yaml이
키를 빈 기본값으로 정의하므로 **동작상 무해**(전자도 빈 값으로 해석). 일관성 차원의 표기 통일은
선택 사항이며 수정 불필요.

---

## 5. 종합 판정

| 영역 | 판정 |
|------|------|
| 시크릿 노출 (PHASE A) | **PASS** |
| 작동 검증 (PHASE B) | **PASS** |
| 문서 일관성 (PHASE C) | **PASS** |
| 빌드 | **PASS** (EXIT 0) |

> ✅ **최종: PASS** — 시크릿 평문 노출 없음, 토큰 교환·검증기 정합, 기존 흐름 회귀 없음, 문서 일관.
> 운영 적용 전제는 카카오 콘솔에서 Client Secret **"사용 함" 활성화** + 프론트와 동시 배포(별도 정책 메모 참조).
