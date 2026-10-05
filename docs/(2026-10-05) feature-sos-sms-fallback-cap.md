# SOS 문자 폴백 시간당 상한 (2026-10-05)

branch `feature/sos-sms-fallback-cap` · 마이그레이션 없음 · 근거: 쿨다운 10초 점검 M-2 (`(2026-10-05) audit-sos-notify-cooldown.md`)

## 1. 왜

- 쿨다운을 10초로 줄이면서 SOS 입력 자체에는 속도 제한이 없다(429 미도입 유지). 푸시를 못 받는 보호자는 연타나 호출 루프 시 문자를 **분당 6건, 시간당 360건**까지 받을 수 있었다.
- 사용자 결정: 문자도 푸시와 같은 **10초 단일 쿨다운**은 그대로 두고, 학생 프로젝트라 비용·피로에 **적당한 상한**을 둔다.

## 2. 규칙

| 항목 | 값 |
|---|---|
| 대상 | 필수 알림(`WARD_SOS`)의 **문자 폴백**(푸시 전달 실패 시)만 |
| 상한 | 수신자당 **1시간에 30건** (고정 1시간 창) |
| 설정 | `notification.sms-fallback.max-per-hour` (환경변수 `SMS_FALLBACK_MAX_PER_HOUR`, 기본 30). 0 = 끔. 음수는 기본값 + WARN. 비숫자·빈 값은 기동 실패(fail-fast) |
| 초과 시 | 그 문자만 생략. 푸시·WebSocket·SOS 이력·쿨다운은 그대로. 알림 이력에는 문자 시도가 `RATE_LIMITED`("문자 발송 한도 초과로 생략됨")로 남고 WARN `[SMS-FALLBACK-CAP]` |
| Redis 장애 | fail-open(막지 않고 발송), WARN `[SMS-FALLBACK-CAP-REDIS-DOWN]` |
| 센 시점 | 문자 발송 **직전**(선점 후 발송). 푸시가 전달되면 세지 않는다. 문자가 **접수되지 않으면 환불**(번호 없음·발송사 장애 - 장애 중 연타가 한도를 다 써서 복구 뒤에도 막히는 것을 방지). 상한 초과로 생략한 건은 센 적이 없으니 환불하지 않는다 |

30건은 10초 간격으로 쉬지 않고 눌러도 **5분**이면 닿는 값이다. 5분 연속 알림을 받은 보호자는 상황을 안다고 보고, 정상적인 몇 번 입력은 걸리지 않는다. 최악의 비용은 보호자 1명당 시간당 30건(상한 없을 때의 1/12).

## 3. 초안에서 달라진 점

- 키는 (피보호자, 보호자) 쌍이 아니라 **(알림 종류, 수신자)**다 - 디스패처의 `wardId`는 이력 표시 전용이라 라우팅·카운트 근거로 쓰지 않는다. 한 보호자가 여러 피보호자를 보면 그 보호자의 문자 폴백 한도가 합산된다(수용).
- 설정은 `sos.*`가 아니라 `notification.sms-fallback.*`다 - 디스패처(notification 도메인)가 sos 설정을 import하지 않게 하기 위함. 현재 문자 폴백 정책을 쓰는 종류가 `WARD_SOS` 하나라 효과는 같다.
- 로그 태그는 `[SOS-SMS-CAP]`가 아니라 `[SMS-FALLBACK-CAP]`(디스패처 일반 경로).

## 4. 변경 파일

| 파일 | 내용 |
|---|---|
| `notification/config/SmsFallbackProperties.java` (신규) | 설정, 기본 30 |
| `notification/dispatch/SmsFallbackLimiter.java` (신규) | Redis 카운터. 증가+최초 TTL은 기존 `RedisCounter.incrementWithTtl`(Lua 원자), 환불은 "키가 있고 0보다 클 때만 DECR" Lua(인증번호 발송 한도 환불과 같은 패턴) |
| `notification/dispatch/NotificationDispatcher.java` | `dispatchMandatory`에서 문자 발송 직전 상한 확인. 생성자 인자 추가 |
| `notification/channel/ChannelFailureReason.java` | `RATE_LIMITED` 추가 (이력 reason은 JSONB 문자열이라 DB 제약 없음 - 마이그레이션 불요) |
| `application.yaml` | `notification.sms-fallback.max-per-hour` |

## 5. 알려진 한계 / 의도

> Opus 적대적 검토(2026-10-05) 반영: **M-1 TTL 없는 키 경합**(setIfAbsent+increment 분리 → 원자 Lua로 교체)과 **M-3 실패 발송의 한도 소진**(환불 추가, 번호 없는 보호자가 한도를 쓰던 L-1도 해소)을 고쳤다. 아래 둘은 수용했다.

- **(수용) M-2 전원이 상한을 넘으면 SOS 쿨다운이 풀린다**: 대상 보호자 전원이 푸시 불가이면서 상한을 넘으면 전달 0명이라 `SosNotificationListener`가 쿨다운을 해제한다(SOS-G09). 이후 연타마다 WS·이력 행·WARN 로그는 쿨다운 없이 반복된다(문자는 계속 생략). 구분 신호를 주려면 `notification_log.result` CHECK 마이그레이션이 필요하고, 푸시 불가 보호자 전원에게 시간당 30건 넘게 문자가 나간 지속 연타 상황이라 수용했다. `[SOS-NO-DELIVERY]` 문구가 이때 사실과 약간 어긋난다.
- **(수용·사용자 확인 필요) M-4 한도는 보호자 단위**: 한 보호자가 피보호자 여러 명을 보면 한도를 공유한다. 피보호자 A의 호출 루프가 한도를 다 쓰면 같은 시간 피보호자 B의 문자도 생략된다. 키를 (보호자, 피보호자)로 바꾸려면 `dispatchMandatory`에 wardId를 넘겨야 하고 "wardId는 표시 전용" 규칙과 부딪쳐 이번에는 두지 않았다.
- **롤백 주의**: `RATE_LIMITED` 이력이 생긴 뒤 이전 코드로 되돌리면 모르는 enum 값 때문에 관리자 알림 이력 조회가 실패할 수 있다(Jackson 설정에 따라 다름). 롤백이 필요하면 해당 행을 먼저 확인할 것.

- 고정 창이라 경계에서 최대 2배(시간당 60건)까지 나갈 수 있다. 수용.
- 상한을 넘은 보호자가 푸시도 못 받는 상태라면 그 사이 SOS는 이력과 WebSocket(접속 중일 때)에만 닿는다. 5분 연속 알림 뒤라 수용하고, `[SMS-FALLBACK-CAP]` 로그와 알림 이력(RATE_LIMITED)이 흔적이다.
- 모든 보호자가 상한에 걸리면 전달 0명이라 SOS 쿨다운이 풀린다(SOS-G09). 이때도 문자는 생략된다.
- 알림 이력의 전체 결과는 `FAILED`로 남는다(푸시 실패 + 문자 생략). 관리자 실패율에는 포함된다.

## 6. 테스트

- `SmsFallbackLimiterTest`(신규): 30번째 허용·31번째 생략, 원자 증가+TTL 1시간, 수신자별 키, 0=끔(Redis 미접근), Redis 장애 fail-open, 증가값 0, 환불 실행·장애 삼킴, 음수 대체, 바인딩·비숫자 기동 실패.
- `NotificationDispatcherTest`: 상한 초과 시 문자 생략 + 이력 RATE_LIMITED(환불 없음), 푸시 성공 시 상한 미사용, 설정 기반 문자(복약 요약)는 상한 미적용, 문자 미접수 시 환불·접수 시 환불 없음.
- Lua 스크립트(증가·환불)는 단위 테스트에서는 실행되지 않아 후속으로 `SmsFallbackLimiterRedisIntegrationTest`(실제 `redis:7.2`, 통합 테스트 스위트)를 추가했다. 구조는 `SmsFallbackLimiterUsageTest`가 고정한다.
- 전체 단위 테스트 통과(1359), `build -x test` 통과.

## 7. 배포 결과

- PR #305 머지 커밋 `cbdea14` (브랜치 커밋 `15dd184`). CD run 37323774364 성공.
- vkcs-linux: `cbdea14` 배포 후 healthy. 이어서 다른 PR(#306~#309)이 머지되어 최종 `e80f673`(CD run 37324392404 성공), healthy, 오류 0건.
- gosky: `55d39cb` → `e80f673`(수동 배포 시점의 최신 dev - 내 변경 외에 #306~#309 QA 후속 수정이 함께 반영됨), healthy, Flyway "No migration necessary", `AI WS 연결됨`, 오류·BindException 0건.
- 두 서버 같은 커밋(`e80f673`). 설정 키 `SMS_FALLBACK_MAX_PER_HOUR`는 서버 `.env.dev`에 없어 기본 30건으로 동작한다.
- 상한 초과를 실제로 일으켜 보는 검증은 하지 않았다(실제 문자·보호자 알림이 발생한다). 동작은 단위 테스트로 갈음했고, 운영에서는 `[SMS-FALLBACK-CAP]`·`[SMS-FALLBACK-CAP-REDIS-DOWN]` 로그와 알림 이력 `RATE_LIMITED`로 확인한다.
- 점검(템플릿 C)은 미실시 - `docs/audit-index.md`에 ❌로 등록.
