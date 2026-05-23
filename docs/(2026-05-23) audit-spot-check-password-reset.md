# 비밀번호 재설정 정책 변경 — 스팟 점검 보고서

- **점검 일자**: 2026-05-23 (토)
- **점검자**: Claude Code
- **점검 유형**: 스팟 점검 (Spot Check) — 변경 부분 한정
- **대상 커밋**: `3a0fbea` (feat), `2b0e828` (docs) — PR #166으로 `dev` 머지 완료
- **관련 정책 문서**: `docs/(2026-05-23) policy-change-password-reset.md`, `docs/(2026-05-23) audit-report-auth-password-reset.md`

> ⚠️ auth 도메인 전체 재점검이 아니라 **이번 정책 변경(always-200 → 404/400/429 분기)으로 영향받은 코드만** 점검한다. 이전 auth audit 결과는 그대로 유효하다.

---

## 1. 점검한 파일 (변경 부분만)

### 코드 (9)
| # | 파일 | 변경 요지 |
|---|------|----------|
| 1 | `auth/controller/FindPasswordController.java` | send/resend 4개 → 이중 윈도우 RateLimit(`check` 4-arg) + IP 전달 + Swagger 404/400/429 |
| 2 | `auth/service/PasswordResetService.java` | 미가입 404 / 카카오 400 / per-email 상한 429 + WARN 로깅, `ipAddress` 파라미터 추가 |
| 3 | `auth/dto/PasswordResetRequest.java` | `@Email` 메시지·`@Schema` 설명 갱신 |
| 4 | `auth/dto/PasswordResetSmsSendRequest.java` | `@Pattern` 메시지·`@Schema` 설명 갱신 |
| 5 | `global/exception/ErrorCode.java` | `EMAIL_ACCOUNT_NOT_FOUND(404)` 추가 |
| 6 | `global/security/RateLimitService.java` | 분+시간 이중 윈도우 `check(endpoint, id, maxPerMinute, maxPerHour)` 오버로드 |
| 7 | `global/util/RedisKeys.java` | `PW_EMAIL_SEND_COUNT` 키 추가 |
| 8 | `auth/service/PasswordResetServiceTest.java` | 404/400·SMS·per-email cap 테스트 재작성 (+97) |
| 9 | `global/security/RateLimitServiceTest.java` | 이중 윈도우 테스트 추가 (+48) |

### 문서 (5)
`CLAUDE.md`(§9), `프로젝트_설명.txt`, `docs/progress.md`, `docs/(2026-05-23) policy-change-password-reset.md`, `docs/(2026-05-23) audit-report-auth-password-reset.md`

---

## 2. PHASE -1 — 환경 확인 결과

| 항목 | 결과 |
|------|------|
| 작업 트리 | Clean (커밋되지 않은 변경 없음) |
| 현재 브랜치 | `dev` (변경은 PR #166으로 머지 완료) |
| 빌드 `./gradlew build -x test --no-daemon` | ✅ BUILD SUCCESSFUL (up-to-date) |
| CLAUDE.md / 프로젝트_설명.txt / progress.md | ✅ 모두 갱신됨 |

---

## 3. PHASE A — 보안 점검 결과

### A1. User Enumeration 방어 강도
- 404(`EMAIL_ACCOUNT_NOT_FOUND`)·400(`SOCIAL_USER_NO_PASSWORD`)·200 분기는 **정책상 의도된 노출**이며 코드로 명확히 분기됨. ✅
- RateLimit 코드 레벨 동작: 컨트롤러가 `check("pw-reset-email"/"pw-reset-sms", ip, 10, 30)` 호출 → `RateLimitService`가 1분/1시간 카운터를 각각 증가 후 한쪽이라도 초과 시 429. 키·TTL은 테스트로 검증됨. ✅
- **🟠 (조건부) IP 우회 — X-Forwarded-For 신뢰 정책**: `application.yaml`의 `server.forward-headers-strategy: framework`로 인해 `httpRequest.getRemoteAddr()`는 `X-Forwarded-For`/`Forwarded` 헤더에서 추출한 클라이언트 IP를 반환한다. always-200이 폐지되면서 **IP RateLimit이 enumeration의 1차 방어**가 됐다. 만약 상단 nginx가 `X-Forwarded-For`를 실 client IP로 **덮어쓰지(realip/overwrite) 않고 append만** 한다면, 클라이언트가 헤더 선두값을 스푸핑해 매 요청 IP를 회전 → IP RateLimit을 무력화할 수 있다. 그 경우 **무제한 계정/Provider enumeration(404/400/200 구분)** 과 등록 사용자 대상 reset 메일 트리거가 가능해진다.
  - 추가 위험 증폭: **미존재 이메일**은 per-email 상한(`password:email:sendcount`)이 가입 확인 *후*에만 증가하므로, 미존재 이메일 enumeration은 **IP RateLimit이 유일한 throttle**이다.
  - 코드 자체 결함은 아니며 모든 RateLimit 엔드포인트에 공통인 기존 사항이나, 이번 변경이 위험도를 끌어올렸다. → **nginx X-Forwarded-For 처리(realip 모듈/overwrite) 확인 필요.**

### A2. Timing Attack 가능성
- 200 경로는 `sendResetEmail`(SMTP) + Redis 저장으로 404 경로보다 느려 **타이밍 차이가 존재**한다.
- 그러나 **상태코드(404 vs 200/400)가 이미 가입 여부를 의도적으로 노출**하므로 타이밍 정보는 추가 가치가 없다. 정책 문서가 "응답 시간 정규화"를 *모순*으로 명시 거부한 결정과 일치. → **운영상 위협 아님, 조치 불필요.**

### A3. SMS 비용 보호
- `requestResetBySms`: 미일치(`matches.isEmpty()`) → `USER_NOT_FOUND`(404), 카카오만 매칭 → 400 — **둘 다 `smsVerificationService.sendCode` 호출 전에 차단**된다. ✅
- 실사용 번호 매칭 시에도 `sendCode` 내부 per-phone 상한(`SMS_SEND_COUNT`, 1시간 10회, A-M3)이 SMS 폭탄을 차단. ✅ → **PASS** (미가입자에게 SMS 미발송, 테스트로 검증).

### A4. 에러 메시지 / 로그 PII 노출
- 에러 메시지("해당 이메일로 가입된 계정이 없습니다" 등)에 **입력값 echo 없음**. ✅
- 미가입 WARN 로깅은 `MaskingUtil.maskEmail`/`maskPhone` 적용, IP는 식별 목적 전체 기록. ✅
- `GlobalExceptionHandler`는 정적 `errorCode.getMessage()`만 로깅. ✅ → **PASS**.

### B1. Rate Limit 동시성 (concurrency-review)
- `RedisCounter.incrementWithTtl`가 **Lua 스크립트로 INCR + (최초 1회)EXPIRE를 원자 처리** → INCR/EXPIRE 사이 TTL 누락 틈 제거. ✅
- 패턴이 **increment-then-check**(check-then-increment 아님)라 TOCTOU 우회 없음. ✅
- 이중 윈도우는 분·시간 카운터를 각각 원자 증가 — 두 카운터 간 교차 원자성은 없으나 각 카운터가 독립적으로 정확하므로 undercount 없음. ✅
- 고정 윈도우 경계 버스트(윈도우 경계에서 최대 2배 허용)는 알려진 한계이나 본 용도에 허용 가능. → **PASS** (정보성).

---

## 4. PHASE B — 구조·계약 점검 결과

- **예외 처리**: 신규 분기는 `CustomException(ErrorCode.*)`로 throw → `GlobalExceptionHandler`가 `errorCode.getStatus()`로 매핑. `EMAIL_ACCOUNT_NOT_FOUND`/`USER_NOT_FOUND`=404, `SOCIAL_USER_NO_PASSWORD`=400, `TOO_MANY_REQUESTS`=429 정확. ✅
- **@Transactional 경계**: `requestReset`/`requestResetBySms`는 의도적으로 비트랜잭션(M-5: SMTP/SMS 호출이 DB 커넥션 미점유). 신규 코드는 Redis+외부발송만 추가, DB 쓰기 없음 → 트랜잭션 경계 변경 불필요. ✅
- **HTTP 의미(api-contract-review)**: 404(미존재)·429(과다요청) 의미 정확. 400(카카오 계정)은 의미상 409/422가 더 정밀하나 **프로젝트 기존 컨벤션과 일관**, 결함 아님. ✅
- **Swagger 일치**: 4개 엔드포인트 `@ApiResponses`가 404/400/429를 정확히 반영, send·resend 응답 일관. **계약-구현 일치**. ✅
- **키 충돌**: 신규 키는 `rate:<endpoint>:1m:<ip>` / `:1h:<ip>` 접미 — 기존 단일 윈도우 `check`(2-arg) 키와 충돌 없음(테스트로 포맷 검증). ✅

---

## 5. PHASE C — 테스트 점검 결과

| 체크리스트 케이스 | 커버 | 위치 |
|------|------|------|
| 미가입 이메일 → 404 | ✅ | `requestReset_미가입_404_이메일미발송` |
| 미가입 사용자(SMS) → 404 | ✅ | `requestResetBySms_미일치_404_SMS미발송` |
| 카카오 계정 → 400 (이메일/SMS) | ✅ | `*_카카오*_400_*` |
| per-email 상한 초과 → 429 | ✅ | `requestReset_perEmail상한초과_429` |
| 정상 흐름 (이메일/SMS) | ✅ | `requestReset_정상_*`, `requestResetBySms_정상_SMS발송` |
| IP RateLimit 초과 → 429 (분/시간) | ✅ | `dualWindow_minuteExceeded_throws`, `dualWindow_hourExceeded_throws` |
| RateLimit 키·TTL 포맷 | ✅ | `dualWindow_keyFormatAndTtls` |
| A-M1 코드 선검증 회귀 | ✅ | `confirmReset_코드검증_사용자조회보다_선행` |
| **이메일 형식 오류 → 400** | ❌ | 미작성 (선언적 `@Email`) |
| **전화번호 형식 오류 → 400** | ❌ | 미작성 (선언적 `@Pattern`) |
| **RateLimit 실제 동시성** | ⚠️ | mock 기반 로직 검증만, 실 동시 INCR 통합 테스트 없음 |

→ 핵심 비즈니스 분기(404/400/429/정상)는 충실히 커버됨. validation 400·실동시성은 보강 여지.

---

## 6. PHASE D — 문서 일관성 점검 결과

- `CLAUDE.md` §9: 404/400/429·per-email 상한·이중 윈도우·WARN 로깅·거부안 모두 반영. ✅
- `프로젝트_설명.txt`: 3-4 비밀번호 재설정 섹션 2026-05-23 갱신 반영. ✅
- `docs/progress.md`: 2026-05-23 항목 + Breaking change + 변경 파일/테스트 목록 명시. ✅
- **모순 잔재 없음**: "항상 200/always-200" 문자열은 모두 *"…였으나 변경"* 형태의 변경 서술이며, 유효한 정책으로 잘못 남은 곳 없음. ✅
- 변경 날짜(2026-05-23) 전 문서 명시. ✅ → **PASS**.

---

## 7. 발견 이슈 (심각도별)

### 🔴 Critical — 없음
### 🟠 High
- **[SPOT-H1] (조건부·인프라) X-Forwarded-For 스푸핑 시 IP RateLimit 우회 → enumeration 무력화 가능.** always-200 폐지로 IP RateLimit이 1차 방어가 됐으나, `getRemoteAddr()`가 forward 헤더에 의존(`forward-headers-strategy: framework`). nginx가 `X-Forwarded-For`를 실 client IP로 덮어쓰지 않으면 헤더 회전으로 우회 가능. 미존재 이메일 enumeration은 IP RateLimit이 유일 throttle이라 영향 큼.

### 🟡 Medium
- **[SPOT-M1] validation 400 케이스 테스트 부재.** 이메일 형식 오류·전화번호 형식 오류(`@Email`/`@Pattern` → 400)가 자동화 검증으로 보장되지 않음. `@WebMvcTest` 컨트롤러 슬라이스 테스트 권장.

### 🟢 Low
- **[SPOT-L1]** RateLimit 실제 동시성 통합 테스트 부재 — Lua 원자성에 위임(단위 테스트는 mock 로직만). embedded Redis 통합 테스트 있으면 견고(필수 아님).
- **[SPOT-L2]** 카카오 계정 400 — 의미상 409/422가 더 정밀하나 기존 컨벤션과 일관(결함 아님, 정보성).
- **[SPOT-L3]** 서비스 테스트가 JUnit `assertThrows`와 AssertJ 혼용(스킬은 `assertThatThrownBy` 권장). 기존 클래스 패턴 따른 것이라 경미.
- **[SPOT-L4]** `PasswordResetServiceTest.java` 파일 끝 newline 누락(`\ No newline at end of file`).

---

## 8. 통과 여부 및 후속 조치

### 판정: **일부 수정 필요 (조건부 PASS)**
코드 레벨 실결함(Critical/실코드 High)은 **0건**. 비즈니스 로직·동시성·SMS 비용 보호·PII 마스킹·문서 일관성 모두 양호. 단 아래 후속 확인·보강이 필요하다.

### 추천 후속 조치 (우선순위 순)
1. **[SPOT-H1] nginx `X-Forwarded-For` 처리 확인 (인프라, 우선)** — realip 모듈 또는 `proxy_set_header X-Forwarded-For $remote_addr`로 실 client IP 강제 여부 검증. 미보장 시 RateLimit 우회로 enumeration 방어가 무력화됨. 코드 변경 불필요할 수 있으나 *반드시* 확인.
2. **[SPOT-M1] validation 400 컨트롤러 테스트 추가** — `@WebMvcTest(FindPasswordController)`로 잘못된 이메일/전화번호 → 400 검증.
3. **[SPOT-L1] (선택) RedisCounter 동시성 통합 테스트** — embedded Redis로 동시 INCR 정합성·TTL 검증.
4. **[SPOT-L4] 파일 끝 newline 보강** (사소).

> 위 항목은 모두 **별도 작업 브랜치(`docs/...`, `test/...`, `infra/...`) + PR**로 처리 — 대상 변경이 이미 `dev`에 머지되어 있으므로 `dev` 직접 커밋 금지 원칙 준수.
