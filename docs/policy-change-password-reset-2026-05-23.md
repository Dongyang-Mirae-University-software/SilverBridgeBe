# 정책 변경: 비밀번호 재설정 — 가입 여부 명시 응답

- **변경 일자**: 2026-05-23
- **결정자**: 프로젝트 오너 (Claude Code 분석·구현)
- **적용 브랜치**: `feature/pw-reset-user-friendly-policy`

---

## 1. 변경 의도 (시니어 친화)

> **시니어/4050 타겟 UX 우선.** 기존 always-200 정책은 User Enumeration을 차단했으나,
> 잘못된 이메일/전화번호를 입력한 시니어 사용자가 "메일이 안 온다"며 원인을 모른 채 이탈하는
> 문제가 컸다. 가입 여부를 **명확히 안내(404/400)**하여 이탈을 줄이고, 그로 인해 노출되는
> enumeration은 **Rate Limit + per-email 상한 + 비정상 패턴 로깅**으로 방어한다.

---

## 2. 변경 전/후 비교

### 영향 엔드포인트
- `POST /api/auth/find-password/email/send`
- `POST /api/auth/find-password/email/resend`
- `POST /api/auth/find-password/sms/send`
- `POST /api/auth/find-password/sms/resend`

### 응답 비교

| 케이스 | 변경 전 | 변경 후 |
|--------|---------|---------|
| 이메일 형식 오류 | 400 | 400 "올바른 이메일 형식이 아닙니다." (동일) |
| **미가입 이메일** | **200** (조용히 미발송) | **404 "해당 이메일로 가입된 계정이 없습니다."** |
| 카카오 가입 이메일 | 200 (조용히 미발송) | **400 "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다."** |
| 정상 이메일 | 200 발송 | 200 발송 (동일) |
| 전화번호 형식 오류 | 400 | 400 "올바른 전화번호 형식이 아닙니다." (동일) |
| **이름+전화번호 미일치** | **200** (조용히 미발송) | **404 "사용자를 찾을 수 없습니다."** |
| 카카오만 매칭 | 200 (조용히 미발송) | **400 `SOCIAL_USER_NO_PASSWORD`** |
| 정상 SMS | 200 발송 | 200 발송 (동일) |
| 상한 초과 | (단일 1분10회) | **429** — IP 1분10회/1시간30회 또는 per-email·per-phone 1시간10회 |

> `POST /api/auth/password/reset`(3단계), `email/verify`·`sms/verify`(2단계)는 **변경 없음**.
> 이미 노출 정책인 `signup/email/check`, `find-email`도 변경 없음.

---

## 3. 적용한 보안 보완 장치

| 장치 | 내용 | 키/위치 |
|------|------|---------|
| **IP 이중 윈도우 Rate Limit** | pw-reset send/resend에 1분 10회 + 1시간 30회. 기타 엔드포인트는 1분 10회 유지 | `RateLimitService.check(endpoint,id,maxPerMinute,maxPerHour)` / `rate:{ep}:1m:{ip}`, `rate:{ep}:1h:{ip}` |
| **per-email 발송 상한** | 가입 확인된 이메일 한정 1시간 10회. IP 회전 우회 메일 폭탄·비용 남용 차단(SMS A-M3 대칭) | `password:email:sendcount:{email}` |
| **per-phone 발송 상한**(기존 A-M3) | SMS 1시간 10회 — 특정 번호 SMS 폭탄·비용 남용 차단 | `sms:sendcount:{phone}` |
| **비정상 패턴 WARN 로깅** | 미가입 404 시 마스킹 식별자 + IP를 WARN 기록 → enumeration 스윕 사후 탐지 | `PasswordResetService` `[PW-RESET]` 로그 |
| **SMS 비용 보호** | 미가입 name+phone은 `SmsSender` 호출 전 404 선차단 → 미가입자에게 SMS 미발송 | `requestResetBySms` |
| **코드 선검증(기존 A-M1)** | confirmReset은 코드 검증을 사용자 조회보다 먼저 — 3단계 누출 방지 유지 | `confirmReset` |

---

## 4. 거부한 보안 제안 + 사유

| 제안 | 사유 |
|------|------|
| 응답 시간 정규화 (Timing Attack 방어) | 새 정책이 **404/200으로 존재 여부를 의도적으로 노출**하므로 타이밍 방어는 모순·무의미 |
| 의심 IP 자동 블랙리스트 | 공용 NAT(기관/가정)에서 **시니어 다수 오차단** 위험. 429 Rate Limit으로 충분 |
| CAPTCHA | 4050/시니어 사용자 부담 (오버엔지니어링 금지선) |
| 이메일 도메인 실재 확인 / ML 행동 분석 / 외부 서비스 연동 | 현 단계 불필요 (오버엔지니어링 금지선) |

---

## 5. 향후 모니터링 권장 항목

1. `[PW-RESET] 미가입 … 차단` WARN 로그 빈도 — 동일 IP/단시간 급증 시 enumeration 스윕 의심.
2. 429 발생률 — 정상 시니어 사용자의 오차단(false positive)이 잦으면 1분 한도 상향 검토.
3. per-email / per-phone 1시간 10회 상한 도달 빈도 — 실제 메일/SMS 비용 추이와 함께 재평가.
4. 미가입 404 비율 — 비정상적으로 높으면 프론트 입력 검증/안내 개선 필요 신호.
