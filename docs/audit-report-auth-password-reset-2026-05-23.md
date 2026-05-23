# 비밀번호 재설정 정책 변경 — 점검·구현 보고서

- **점검 일자**: 2026-05-23
- **점검자**: Claude Code
- **대상 도메인**: `domain/auth` (비밀번호 재설정), `global/security`, `global/exception`
- **브랜치**: `feature/pw-reset-user-friendly-policy`

---

## 0. 정책 변경 결정 배경

- 서비스 타겟이 **시니어 + 4050세대 보호자**.
- 기존 비밀번호 재설정 API는 User Enumeration 방어를 위해 **가입 여부와 무관하게 항상 200**을 반환.
- 그 결과 잘못된 이메일/전화번호 입력 시 사용자가 원인을 알 수 없어 **"메일이 안 오네?" 하며 이탈**하는 UX 문제 발생.
- **결정**: 보안과 UX의 균형점으로 **가입 여부를 명시적으로 안내(404/400)**하되, enumeration 노출분은 **Rate Limit + per-email 상한 + 로깅**으로 방어.

---

## 1. PHASE 0 — 현재 코드 파악 결과

### 1-1. 관련 파일
- Controller: `FindPasswordController`(send/verify/resend ×2채널), `PasswordResetController`(`/password/reset`)
- Service: `PasswordResetService`(핵심), `SmsVerificationService`(per-phone 상한 A-M3 보유)
- Util: `VerificationCodeValidator`(상수시간 비교 A-L1), `VerificationKeyConfig`, `RedisKeys`, `RedisCounter`
- 예외: `ErrorCode`, `GlobalExceptionHandler`
- 보안: `RateLimitService`(단일 1분 윈도우, 전역 상수 10회), `SecurityConfig`
- 테스트: `PasswordResetServiceTest`, `RateLimitServiceTest`

### 1-2. 가입 여부 숨김 처리 위치 (변경 전)
| 위치 | 코드 | 동작 |
|------|------|------|
| `PasswordResetService:65-68` | `if (user == null \|\| user.isSocialProvider()) return;` | 이메일 — 미가입/카카오 조용히 종료(200) |
| `PasswordResetService:110-113` | `if (user == null) return;` | SMS — 미일치 조용히 종료(200) |
| `PasswordResetService:150-160` | 코드 검증 선행(A-M1) | confirmReset 누출 차단(유지) |

### 1-3. Rate Limit 현황
- `RateLimitService`: `WINDOW_SECONDS=60, MAX_REQUESTS=10` — **단일 1분 윈도우, 전 엔드포인트 공통 상수**(1시간 윈도우 없음).
- pw-reset 4개 send/resend는 `pw-reset-email`/`pw-reset-sms` 키로 적용 중(send·resend 키 공유).
- per-phone SMS 발송 상한(A-M3, 1시간 10회)이 `SmsVerificationService.sendCode` 내부에 존재 → **SMS 비용 보호**. 이메일에는 동등 장치 없음.

### 1-4. ErrorCode
- **재사용**: `USER_NOT_FOUND`(404, SMS 미일치), `SOCIAL_USER_NO_PASSWORD`(400, 카카오), `TOO_MANY_REQUESTS`(429).
- **신규**: `EMAIL_ACCOUNT_NOT_FOUND`(404, "해당 이메일로 가입된 계정이 없습니다.").

### 1-5. 형식 검증 위치
- 이메일: `@Email` + `@Size(max=50)` (이미 400 반환).
- 전화번호: `@Pattern("^\\d{10,11}$")` (이미 400 반환).
- → 형식 400은 이미 동작. 변경은 **silent-200 → 404/400** 부분.

### 1-6. 사용자 조회 (auth → user)
- `findByEmail`, `findAllByNameAndPhone`, `findByPhone` 직접 의존(읽기). `PhoneVerificationPort`는 반대 방향 전용 → 신규 port 불필요.

### 1-7. 기존 보안 정책
- 로그인 enumeration 통합(H-1/H-2), confirmReset 코드 선검증(A-M1), 상수시간 비교(A-L1), per-phone SMS 상한(A-M3), BCrypt 12, JWT typ 구분, 보안 헤더(A-L2).

---

## 2. PHASE 0.5 — 보안 보완 제안 + 사용자 선택 결과

| # | 제안 | 권장 | **사용자 선택** |
|---|------|------|----------------|
| 1 | IP Rate Limit 다중 윈도우(1분+1시간, 엔드포인트별) | 필수 | ✅ **채택** |
| 2 | per-email 발송 상한 (`password:email:sendcount`, SMS A-M3 대칭) | 권장 | ✅ **채택** |
| 3 | 비정상 패턴 WARN 로깅 (404 시 마스킹 식별자+IP) | 권장 | ✅ **채택** |
| 4 | SMS 비용 보호 (미가입 즉시 404 선차단) | 필수 | ✅ **채택** |
| 5 | 응답 시간 정규화 (Timing Attack) | 비권장 | ❌ 거부 |
| — | 의심 IP 자동 블랙리스트 | 비권장 | ❌ 거부 |

### Rate Limit 수치 — 적정성 평가 및 결정
- 기본안 1분 5회는 send·resend 키 공유 환경에서 **불안한 시니어의 반복 재발송 시 오차단 위험**.
- **결정**: **1분 10회 / 1시간 30회** (사용자 승인). 1분 한도는 시니어 여유, 시간 한도가 분산 저빈도 스윕 방어. enumeration 실질 방어는 #2(per-email) + #3(로깅)이 분담.

### 카카오 계정 처리 결정
- email/SMS 재설정에 카카오 계정이 매칭되면 → **400 `SOCIAL_USER_NO_PASSWORD`**("카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다."). (사용자 승인) find-email이 이미 카카오 존재를 노출하므로 정책 일관.

---

## 3. PHASE 1 — 변경 영향 분석

| 항목 | 영향 |
|------|------|
| A. 응답(프론트) | **Breaking** — send/resend 4개가 미가입 시 200→404, 카카오 200→400. 프론트 "항상 발송됨" 로직을 에러 분기로 수정 필요. |
| B. Rate Limit 충돌 | 전역 상수 하향은 타 엔드포인트 동반 영향 → **신규 오버로드 메서드**로 pw-reset 계열만 1분10회/1시간30회 적용, 기타 1분10회 유지. |
| C. 메시지 일관성 | 차분한 안내체 유지. `EMAIL_ACCOUNT_NOT_FOUND` 신규 메시지도 동일 톤. SMS 404 Swagger 설명은 실제 메시지("사용자를 찾을 수 없습니다")와 일치. |
| D. 도메인 경계 | 영향 없음(기존 직접 읽기 의존 유지). |
| E. 문서 | 프로젝트_설명.txt §3-4·§6·§7, CLAUDE.md, progress.md + 신규 산출물 2종. |
| F. 테스트 | `PasswordResetServiceTest`의 always-200 가정 테스트 깨짐 → 404/400 검증으로 재작성 + SMS·per-email cap 신규. |

---

## 4. PHASE 2 — 구현 결과

| 작업 | 파일 | 내용 |
|------|------|------|
| 1. ErrorCode | `ErrorCode.java` | `EMAIL_ACCOUNT_NOT_FOUND(404)` 추가. SMS=`USER_NOT_FOUND`, 카카오=`SOCIAL_USER_NO_PASSWORD` 재사용 |
| 2. Service | `PasswordResetService.java` | `requestReset`/`requestResetBySms` silent-return 제거 → 404/400 throw. `ipAddress` 파라미터 추가. per-email 상한(#2), 미가입 WARN 로깅(#3) |
| 3. DTO | `PasswordResetRequest`, `PasswordResetSmsSendRequest` | `@Email`/`@Pattern` 메시지를 정책 문구로, `@Schema`에서 "항상 200" 제거 |
| 4. Rate Limit | `RateLimitService.java`, `RedisKeys.java`, `FindPasswordController.java` | `check(endpoint,id,maxPerMinute,maxPerHour)` 이중 윈도우 오버로드. pw-reset 4개 엔드포인트 1분10/1시간30 적용 + IP 전달. `PW_EMAIL_SEND_COUNT` 키 추가 |
| 5. 보안 보완 | `PasswordResetService.java` | #2 per-email 상한(1시간 10회), #3 미가입 WARN 로깅, #4 미가입 SMS 선차단(정책 내재) |
| 6. Swagger | `FindPasswordController.java` | 4개 엔드포인트 @Operation/@ApiResponses에 404/400/429(이중윈도우+상한) 반영, "항상 200" 문구 제거 |

- `confirmReset`(3단계)은 코드 선검증(A-M1) 유지 — **변경 없음**(방어 심층).
- **빌드/테스트**: `./gradlew test`(영향 2개 클래스) + `./gradlew build -x test` 모두 BUILD SUCCESSFUL.

### 응답 매트릭스 (변경 후)
| 케이스 | 이메일 | SMS |
|--------|--------|-----|
| 형식 오류 | 400 "올바른 이메일 형식이 아닙니다." | 400 "올바른 전화번호 형식이 아닙니다." |
| 미가입 | **404 "해당 이메일로 가입된 계정이 없습니다."** | **404 "사용자를 찾을 수 없습니다."** |
| 카카오 계정 | 400 `SOCIAL_USER_NO_PASSWORD` | 400 `SOCIAL_USER_NO_PASSWORD` |
| 상한 초과 | 429(IP 1분10/1시간30 또는 per-email 1시간10) | 429(IP 1분10/1시간30 또는 per-phone 1시간10) |
| 정상 | 200 발송 | 200 발송 |

---

## 5. PHASE 3 — 문서 반영 결과

- `프로젝트_설명.txt` §3-4(가입 여부 노출→명시 안내), §6(Redis 키 `password:email:sendcount`, rate 이중 윈도우), §7(보안 정책 요약) 갱신 — 모두 "※ 2026-05-23 갱신" 표기.
- `CLAUDE.md` — 신규 "비밀번호 재설정 정책" 메모 + 푸터 날짜 갱신.
- `docs/progress.md` — `[2026-05-23]` 항목 추가(변경 엔드포인트·프론트 전달사항).
- 신규 산출물: 본 보고서 + `policy-change-password-reset-2026-05-23.md`.

---

## 6. 미해결 TODO / 후속

- [ ] **프론트 협의**: send/resend 4개 응답 200→404/400 Breaking. 에러 분기 UI(미가입 안내, 카카오 안내) 반영 필요.
- [ ] (선택) 형식 검증 메시지를 verify/confirm DTO까지 통일할지 검토(현재 send DTO만 정책 문구 적용).
- [ ] (모니터링) `[PW-RESET]` WARN 로그 기반 enumeration 스윕 알림 임계치 설정.
- [ ] per-email/per-phone 상한(1시간 10회) 운영 트래픽 관찰 후 수치 재평가.
- [ ] 자동 git commit/push 금지(CLAUDE.md §2) — PR은 사용자 승인 후 생성.
