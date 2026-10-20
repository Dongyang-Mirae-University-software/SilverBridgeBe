# 챗봇 wardId 검증 · 예약 계정 비밀값 · 예약 API 노출 차단 (2026-10-20)

> 상태: **5개 repo 머지·배포 완료(2026-10-20)**. BE는 vkcs·gosky 모두 `2b779ed`. 점검 결과는 `(2026-10-20) audit-chat-ward-id-validation.md` (§10 참고).
> 근거: `(2026-10-20) analysis-ai-chat-reservation.md`의 보안 항목. 분석은 그 시점 기록이라 작업 시작 때 코드·설정으로 다시 확인했다(아래 §2).

## 1. 한 줄 요약

챗 요청의 `context.wardId`를 아무도 검증하지 않아 **보호자가 남의 피보호자 ID를 넣으면 그 사람 이름으로 예약을 조회·생성**할 수 있었고, 예약 계정 비밀번호는 **저장소에 공개된 코드 기본값**으로 계산되었으며, 예약 API(6015)는 **외부에 열려 있었다**. 세 가지를 각 저장소에서 막는다.

## 2. 의심 vs 확인 결과 (2026-10-20, 읽기 전용 확인. 공격 시도 없음)

| 의심 | 확인 결과 | 근거 |
|---|---|---|
| S-1 wardId 위조 | **사실** | BE `ChatRelayService.buildBody`가 `userId`만 덮어쓰고 `context`를 그대로 전달. AI `chat_service.py`가 `context.wardId`로 `ensure_credential(user_id=…)`을 호출하고 `wardName`·`wardPhone`도 클라이언트 값을 그대로 사용. 반면 FE 예약 화면 BFF는 `x-sb-ward-id`를 BE의 ACTIVE 연결 목록과 대조하고 있었다 - **챗 경로에만 검증이 없었다** |
| S-2 비밀번호 추측 | **사실** | 비밀번호 = `HMAC(RESERVATION_ACCOUNT_SECRET, userId)`, 이메일 = `sb-<userId>@silverbridge.local`. AI `.env`·FE `.env*`·두 컨테이너 환경 어디에도 `RESERVATION_ACCOUNT_SECRET`이 없어 **양쪽이 코드 기본값**으로 동작. 공용 계정(`RESERVATION_SERVICE_EMAIL`/`PASSWORD`)도 `.env`에 없어 코드 기본값이 남아 있었다 |
| S-3 예약 API 노출 | **부분 사실** | 6015가 `0.0.0.0`/`[::]`에 열려 있었음(사실). AI는 `127.0.0.1:6015`로 붙지만 **FE BFF는 `RESERVATION_API_URL` 미설정 → 공개 도메인**으로 붙고 있었다. 운영 `.env`의 `JWT_SECRET`·`ADMIN_PANEL_PASSWORD`는 compose 기본값과 **달랐다**(운영 값은 별도). 다만 코드·compose에 폴백 기본값이 남아 있었다 |
| (부가) 운영 DB 계정 | 확인 | 예약 DB 계정 28개 = `sb-*` 17 + seed(`user*@example.com`·`admin@example.com`) 9 + 기타 2. 개수만 확인 |

## 3. 변경 (저장소별 draft PR)

| 저장소 | PR | 브랜치 | 내용 |
|---|---|---|---|
| BE | #356 | `fix/chat-ward-id-validation` | 보호자 경로 `sendAsGuardian`: `context.wardId`가 있으면 `isActiveConnection`(ACTIVE만, `getMyWards` 금지)으로 확인, 아니면 403 `CHAT_WARD_NOT_CONNECTED` + `[IDOR-ATTEMPT]` WARN, AI 호출 없음. 연결이 확인되면 `wardName`·`wardPhone`을 **DB 값으로 덮어씀**. 피보호자 경로는 세 키를 지움. 테스트 6건 |
| FE | #81 | `fix/reservation-bff-require-secret` | 예약 BFF의 비밀값·공개 주소 **기본값 제거**(하나라도 없으면 예약 요청만 503), compose가 `sb-internal` 네트워크에 참여. 테스트 3건 |
| 예약 | #1 | `fix/require-secrets-bind-localhost` | `JWT_SECRET`·`ADMIN_PANEL_PASSWORD` 코드·compose 기본값 제거(비면 기동 실패), 포트 `127.0.0.1:6015`, `sb-internal`에 `reservation-api` 별칭 |
| AI | #23 | `feature/chat-emergency-signals` | **이 작업과 별개**: gosky 컨테이너에서 이미 돌던 미커밋 4파일(응급 신호 감지)을 저장소에 반영. 같은 `chat_service.py`를 건드려 먼저 분리 |
| AI | #24 | `fix/reservation-secret-no-default` | `RESERVATION_ACCOUNT_SECRET`·공용 계정 기본값 제거, 비밀값이 비면 `ensure_credential`이 None, 예약 흐름만 안내 문구로 응답(기동·상담·이상감지 영향 없음). 테스트 4건. #23 위에 쌓음 |

### 바뀌지 않는 것
피보호자 챗, 보호자 본인 상담, 예약 목록 화면, 챗 기록 API, 상담 본문 로그 금지(`ChatRelayLogGuardTest`), AI 같은 프로세스의 이상감지·클립·라이브 분석, 챗 응답 품질·예약 흐름(최종 확인·취소·변경).

## 4. 결정 사항 (Q1~Q4, 사용자 "기본 제안대로")

| | 결정 | 상태 |
|---|---|---|
| Q1 기존 `sb-*` 계정 17개 | 새 키 적용 시 비밀번호가 달라져 로그인 불가 → (b) **시연 데이터면 삭제 후 재생성** 권장. 저장된 API 키는 유효하면 계속 동작 | **미실행** (시연 데이터 여부 확인 필요, 비가역) |
| Q2 공개 도메인 | `reservation.dmu.gosky.kr`가 **예약 API(6015)와 관리자 화면(6017)을 함께 서빙**해 도메인 통째 차단은 관리자 화면까지 막는다 → FE가 내부 주소로 붙는 것을 확인한 뒤 **`/api/` 경로만 외부 차단** 권장 | **미실행**, 배포 단계에서 다시 확인 |
| Q3 seed 계정 9개 | 운영 DB에 남아 있으면 삭제 | **비밀번호 무효화로 해소(2026-10-20)**: 처음엔 사용자 결정으로 유지했으나 seed 9개(`admin@example.com` + `user*@example.com` 8개)가 **예약 163건 전부를 소유**하고 `admin@example.com`이 **예약 서비스의 유일한 ADMIN 계정**(관리자 패널 로그인이 첫 ADMIN 사용자를 씀)이라 삭제 대신 비밀번호만 무효화했다. 변경 전 **9개 모두 저장소 seed 파일의 공개 비밀번호가 그대로 통하는 상태**였고(해시 비교로 확인, 로그인 시도 없음), 변경 후 0개. 계정마다 서로 다른 무작위 값으로 바꿨고 값은 어디에도 남기지 않았다. 계정 12개·예약 163건·ADMIN 1개 그대로, 관리자 패널은 `ADMIN_PANEL_PASSWORD`로 계속 로그인된다 |
| Q4 오류 코드 | `CHAT_WARD_NOT_CONNECTED` 403 "연결된 피보호자만 대신 상담·예약할 수 있습니다." | 구현됨 (#356) |

## 5. 배포 순서 (어긋나면 예약 로그인이 실패한다)

1. **호스트에서 `docker network create sb-internal`** (예약·FE compose가 외부 네트워크로 참조 - 없으면 compose 기동 실패)
2. **예약**: PR #1 반영 → `docker compose up -d --build reservation-api` (6015가 로컬 전용이 되므로 FE는 3번 전까지 공개 도메인 경유가 계속 동작해야 한다 - 즉 2·3번은 연달아)
3. **AI·FE**: 같은 새 `RESERVATION_ACCOUNT_SECRET`을 AI `.env`·FE `.env.dev`에 넣고, FE에는 `RESERVATION_API_URL=http://reservation-api:6015` 추가. AI는 #23 → #24 순 머지 후 `docker compose up -d --no-deps --build ai-server`(**송출이 끊김**), gosky 작업 트리의 같은 4파일 수정은 pull 전에 정리(stash/reset)
4. **Q1·Q3 계정 처리** (+ 필요 시 Q2 nginx)
5. **BE**: PR #356 머지 → vkcs CD 자동 → gosky 수동 배포

운영 `.env` 수정 전에는 `cp .env .env.bak-YYYYMMDD-HHMM`로 백업한다. 비밀값은 `.env`로만 넣고 출력·커밋하지 않는다. 새 비밀값을 만들면 전환 순간에 기존 `sb-*` 계정 로그인이 막히므로 3·4번은 가까운 시간에 한다.

## 6. 동작 영향 (정상 사용자)

- 연결된 피보호자를 대신하는 상담·예약은 그대로다. **화면에 보이는 피보호자 이름과 DB 이름이 다르면** 예약 초안의 환자명이 DB 값으로 나간다.
- 연결이 아닌(PENDING 포함) 피보호자 ID로 보낸 챗은 403이다.
- 비밀값 없이 AI를 배포하면 병원 예약 흐름만 안내 문구로 바뀐다(챗 상담·이상감지는 정상).
- FE에 비밀값·주소 환경변수가 없으면 병원 예약 화면이 503이다.

## 7. 검증

| 대상 | 결과 |
|---|---|
| BE | `./gradlew build` 전체 통과 (`ChatRelayWardContextTest` 6건 신규, 채팅 도메인 테스트 통과) |
| FE | vitest 107건 통과(신규 3건) · `tsc --noEmit` 오류 없음 · eslint 지적 없음 |
| 예약 | `nest build` 통과, 컴파일 산출물에서 비밀값 누락 시 실패/정상 시 통과 확인. **서버를 실제로 띄워 보지는 못함**(로컬 bcrypt 네이티브 빌드 실패) |
| AI | 신규 4건 통과(가벼운 의존성 환경). 변경 파일 컴파일 확인. **전체 pytest는 torch 등 의존성 때문에 못 돌림**, `chat_service.py`의 예약 비활성 분기는 단위 테스트가 없다 |

## 8. 남은 위험·수용

- (해소) 예약 서비스 seed 계정 9개의 비밀번호가 저장소 seed 파일에 고정돼 있었고 운영 DB에서도 그대로 통하는 상태였다. 2026-10-20에 비밀번호를 무작위 값으로 무효화했다(§4 Q3). `prisma db seed`를 다시 실행해도 `upsert`의 `update: {}`라 기존 계정의 비밀번호는 덮어쓰지 않는다. 다만 seed 스크립트의 `resetHospitalData`는 예약 전체를 지우니 운영 DB에서 `db seed`를 다시 돌리지 말 것
- **AI 서버는 여전히 `context.wardId`를 신뢰한다.** 검증은 BE 중계에서만 한다. AI를 BE 우회로 직접 부를 수 있는 키 보유자는 임의 wardId를 보낼 수 있다(AI API 키는 서버 안에서만 쓴다는 기존 정책, 키 교체는 하지 않기로 한 기존 결정). AI 쪽에서 wardId 신뢰 범위를 줄이는 방안은 별도 검토.
- 공개 도메인(`/api/`)은 배포 단계 Q2 전까지 열려 있다. 관리자 화면(6017)은 이 작업의 범위가 아니다.
- FE BFF는 사용자 토큰으로 예약 서비스의 **임의 경로**를 중계한다(예: `/auth/*`). 인증된 사용자 본인 계정 범위라 이번에는 두었다(🟢).
- 예약 서비스의 관리자 패널은 비밀번호 하나로 로그인한다(운영 값은 별도 설정, 이번 변경은 코드 기본값 제거까지).
- FE 예약 토큰 캐시(6시간)와 AI 저장 키는 새 비밀값으로 바꾸는 순간 일부 계정에서 어긋날 수 있다(Q1).
- AI #24는 `chat_service.py`의 비활성 분기 단위 테스트가 없다. gosky에서 전체 테스트 1회 필요.

## 9. 점검 (2026-10-20 실행 완료: `(2026-10-20) audit-chat-ward-id-validation.md`, PASS · 🟡1 🟢5)

아래는 실행한 프롬프트 초안이다(기록용).

인가 규칙(챗의 wardId)을 바꿨으므로 챗 중계 기능 점검(템플릿 B, `security-audit` 중심)을 제안한다. 프롬프트 초안은 `docs/audit-index.md` 행의 "점검 문서" 열과 아래를 따른다.

```
AI 챗봇 중계(보호자·피보호자) 점검 - wardId 검증 변경 반영.
변경 PR: BE #356 (+ AI #23·#24, FE #81, 예약 #1 - 배포 후)

PHASE -1. 사전 환경 확인  [모델: sonnet]
  - 오늘 날짜 / 대상 커밋(머지된 #356) / ./gradlew build / 마이그레이션 최신 버전
  - 두 서버에 #356이 반영됐는지(git log -1, docker ps)

PHASE 0. 점검 대상 식별  [모델: sonnet]
  - domain/chat 파일 트리, 엔드포인트 × 역할 표(/api/guardian/chat*, /api/ward/chat*)
  - context 키가 AI까지 가는 경로 전수(ChatRelayService.buildBody, AI chat_service·schema)

PHASE A. 보안·인가 ★  [스킬: security-audit]  [모델: opus - 인가 우회 판단]
  - IDOR: 보호자 → 남의 wardId(연결 없음/PENDING/REFUSED/DISCONNECTED/탈퇴 직후) 모두 403 + AI 미호출인가
  - wardName·wardPhone 위조 무시 / context 안 userId·role 등 다른 키가 AI에 미치는 영향
  - 피보호자 경로의 wardId 제거 / 관리자 토큰 403
  - 대리 상담 중 연결이 해제되는 경합(검증 시점 vs AI 호출 시점)
  - 로그·예외·알림 이력에 상담 본문/전화번호 노출 없음(ChatRelayLogGuardTest 범위 확인)
  - 예약 계정 파생(HMAC) 입력이 wardId뿐인지, 새 비밀값 적용 뒤 FE·AI 규칙 일치

PHASE B. 기능 정합성  [모델: sonnet]
  - 연결된 피보호자 대신 예약 시 환자명·전화가 DB 값으로 들어가는지(실서버 gosky, 시험 계정 사용·값 출력 금지)
  - wardPhone이 null인 피보호자, wardId 공백/숫자/배열 타입

PHASE C. 구조·계약  [스킬: spring-boot-patterns, api-contract-review]  [모델: sonnet]
  - Swagger 오류 코드(CHAT_WARD_NOT_CONNECTED 403) 반영 / FE 전달 필요 여부

PHASE D. 테스트  [스킬: test-quality]  [모델: sonnet]
  - ChatRelayWardContextTest 변이 테스트(검증 제거 시 실패하는가) / 실제 HTTP 직렬화 테스트에 403 케이스

보고: 파일 목록 · 엔드포인트×역할 인가 표 · IDOR PASS/FAIL · 이슈 🔴🟠🟡🟢 · 종합 판정
규칙: 점검만, 코드 수정 금지 / DB 직접 조작·실제 공격 금지 / 자동 git commit·push 금지
산출물: docs/(날짜) audit-chat-ward-id-validation.md + progress.md + audit-index.md 갱신
```

## 10. 배포 결과 (2026-10-20, gosky)

| 순서 | 대상 | 결과 |
|---|---|---|
| 0 | `docker network create sb-internal` | 완료 |
| 1 | 예약 #1 머지(`331ab72`) → pull → `up -d --build reservation-api` | 완료. 포트 `127.0.0.1:6015`만 열림, `sb-internal`에 `reservation-api` 별칭 참여, 로컬·공개 도메인 모두 200(nginx는 변경 없음) |
| 2 | AI #23(`2629b7e`)·#24(`cff3c4e`) 머지 | 완료 |
| 3 | 새 `RESERVATION_ACCOUNT_SECRET` 생성 → AI `.env`·FE `.env.dev`에 같은 값, FE에 `RESERVATION_API_URL=http://reservation-api:6015` | 완료. 수정 전 `.env.bak-20261020-1104` 백업, 값은 출력·기록하지 않음 |
| 4 | 예약 DB 백업(`reservation-dev.db.bak-20261020-1105`) | 완료 |
| 5 | AI 서버: 작업 트리 4파일(#23과 동일 확인) 정리 → pull → 재빌드·재시작 | 완료. 비밀값 적용(`True`), 옛 공용 계정 꺼짐. BE의 AI 이상감지 구독은 502 몇 회 뒤 재연결됨 |
| 6 | FE #81 머지(`41a9af4`) → 백업 브랜치·수정 stash(작업 중에만 사용, 이후 삭제) → `origin/develop` 병합(`2d885da`) → 수정 복원 → `docker compose -f docker-compose.dev.yml up -d` | 완료. healthy, `sb-internal` 참여, 환경변수 반영, `reservation-api:6015` 접속 OK |
| 7 | Q1: 시연 데이터인 `sb-*` 계정 17개와 그 예약 21건·API 키 4개 삭제 | 완료. 남은 계정 11개(seed 포함, **Q3 seed는 사용자 결정으로 유지**) |
| 8 | Q2: nginx | **생략**(6015가 로컬 전용이 되어 필요성이 낮고, 공유 nginx 수정 위험 대비 이득이 작음) |
| 9 | BE #356 머지(`2b779ed`) → vkcs CD 성공 → gosky 수동 배포(`up -d --build api`) | 완료. 두 서버 healthy, AI 구독 연결됨. 실서버 확인: 연결 없는 `wardId` 403 `CHAT_WARD_NOT_CONNECTED`(+`[IDOR-ATTEMPT]`), 숫자 `wardId` 400. 문서 #357 머지(`5743e6f`) |

### 확인한 것
- 시험 계정(보호자, ACTIVE 피보호자 1명)으로 FE BFF `/api/reservation/reservations/my` 대리 조회: 200, 연결 없는 `wardId`: 403
- FE BFF가 새 비밀값으로 만든 계정에 **AI `ensure_credential`이 로그인해 키를 발급**함 -> AI·FE 비밀값 일치 확인
- 재생성된 `sb-*` 계정 1개(시험 피보호자). 나머지 사용자는 예약 화면을 다시 열 때 자동 생성된다

### 아직 확인하지 못한 것
- (해소) BE `wardId` 검증은 배포 후 실서버에서 확인했다(위 9행, 점검 보고서에 ACTIVE 정상 경로까지 기록)
- 챗봇 예약 흐름 전체(병원 검색~예약 생성)의 E2E는 시험하지 않았다(실제 사용자 예약 데이터를 시험에 쓰지 않는 원칙)
- AI 전체 pytest는 컨테이너에 pytest가 없어 못 돌림

### 운영 메모
- gosky FE는 로컬 병합 커밋이 `origin/develop`과 갈라져 있었다(앞 43). 이번에 `origin/develop`을 병합해 해소했다. 커밋 안 된 `GuardianHospitalContent.tsx` 수정은 FE PR #82(`996ac32`)로 저장소에 반영했고 서버 작업 트리는 깨끗하다
- 작업 중 만든 `.env` 백업 2개, 예약 DB 백업, gosky FE의 백업 브랜치·stash는 2026-10-20 사용자 요청으로 모두 삭제했다 (삭제한 `sb-*` 계정 17개·예약 21건은 복구 불가, 사용자가 화면을 다시 열면 계정만 자동 재생성)
