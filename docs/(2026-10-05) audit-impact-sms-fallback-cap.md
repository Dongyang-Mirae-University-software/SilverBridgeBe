# 영향 범위 점검 - SOS 문자 폴백 시간당 상한 (2026-10-05)

- 대상: PR #305 (머지 `cbdea14`, 브랜치 커밋 `15dd184`) · 템플릿 C · 점검만(문서 정정 제외), 코드 수정 없음
- 기준 커밋: dev `7753f5a` · 서버 두 곳 `e80f673` 배포 · 마이그레이션 변경 없음
- 근거 문서: `docs/(2026-10-05) feature-sos-sms-fallback-cap.md`

## 종합 판정: ✅ PASS (🔴 0 · 🟠 0 · 🟡 1 · 🟢 4)

공용 코드를 건드린 곳은 `NotificationDispatcher.dispatchMandatory` 한 곳이고, 새 `ChannelFailureReason.RATE_LIMITED`를 받는 소비처는 모두 값을 문자열·라벨로 다뤄 깨지지 않았다. 🟡 1건은 **계약·문서가 새 값을 몰랐던 것**이며 이번에 정정했다.

## PHASE 0 변경점 정의

| 바뀐 것 | 내용 |
|---|---|
| 정책 | `FORCED_PUSH_WITH_SMS_FALLBACK`(= `WARD_SOS`)의 문자 폴백 직전에 수신자별 시간당 30건 상한 |
| 새 enum 값 | `ChannelFailureReason.RATE_LIMITED` (이력 `channel_results` JSONB에 문자열로 저장) |
| 새 컴포넌트 | `SmsFallbackLimiter`, `SmsFallbackProperties`, Redis 키 `notify:sms-fallback:{type}:{recipientId}` |
| 시그니처 | `NotificationDispatcher` 생성자 인자 추가 |

**불변식**
1. 상한은 `WARD_SOS` 문자 폴백에만 걸린다. 푸시·WebSocket·SOS 이력·쿨다운·다른 종류의 문자에는 걸리지 않는다.
2. "SOS 알림은 항상 발송"의 의미(푸시 강제, 이력 무조건)는 유지된다. 상한은 문자만 생략한다.
3. 정지·탈퇴 진행 수신자는 한도를 소비하지 않는다(`dispatch()` 차단이 앞선다).
4. `ChannelFailureReason`의 새 값을 받는 모든 곳이 모르는 값에서 깨지지 않는다.
5. 인프라(Redis) 장애는 SOS 문자를 막지 않는다.

## PHASE A·B 사용처 전수와 판정

| 영역 | 위치 | 불변식 | 판정 | 근거 |
|---|---|---|---|---|
| 정책 적용 범위 | `NotificationType.java:72` (`WARD_SOS`만 `FORCED_PUSH_WITH_SMS_FALLBACK`) | 1 | PASS | 사용처가 이 한 곳. `NotificationDispatcherTest`의 정책 고정 테스트("SOS만 SMS 폴백")가 새 종류의 무심코 합류를 막는다. 합류하더라도 키에 종류가 있어 한도가 분리된다 |
| 호출 위치 | `NotificationDispatcher.java` 문자 발송 직전(푸시 전달 시 앞선 return) | 1·2 | PASS | 푸시 전달이면 한도를 세지 않는 단위 테스트로 고정 |
| 설정 기반 문자 | `dispatchSettingsBased` 경로(복약 요약 `MEDICATION_MISSED` 등) | 1 | PASS | 상한 미호출. `설정기반_문자는_상한미적용` 테스트 |
| 이상감지 | `FORCED_PUSH_PLUS_SETTINGS` | 1 | PASS | 문자 폴백이 원래 없음 |
| 정지·탈퇴 수신자 | `withReceivableRecipient` (`dispatch()` 앞단) | 3 | PASS | 차단이 폴백 로직보다 먼저 |
| SOS 리스너 | `SosNotificationListener.java:103-136` | 2 | PASS(수용) | 반환값 `isDelivered()`로만 판정. 상한 초과(`FAILED`)는 미전달 → 전원 초과 시 쿨다운 해제. 이미 수용 기록(M-2). `[SOS-NO-DELIVERY]` 문구가 이때 약간 부정확(L-2) |
| 다른 `dispatch()` 반환값 사용처 | 전수 grep | - | PASS | 반환값을 쓰는 곳은 SOS 리스너 하나 |
| 이력 저장 | `ChannelAttempt`(JSON), `NotificationLog.channelResults` | 4 | PASS | JSONB·CHECK 없음, 마이그레이션 불요. 모르는 값을 읽는 롤백 위험만 문서화 |
| 관리자 조회 DTO | `AdminNotificationItem.ChannelResultItem` | 4 | PASS | `reason`과 `reason.label()` 그대로 노출 → 새 값도 라벨이 나간다 |
| 관리자 집계 | `NotificationLogRepository` | 4 | PASS | 결과 집계는 `result` 컬럼 기준, `reason` 미사용 |
| 요약 카드 | `failed` | - | PASS(수용) | 푸시 실패 + 문자 생략은 `FAILED`로 집계. 푸시가 실제로 안 간 건이라 거짓은 아님 |
| 다른 `ChannelFailureReason` 소비처 | `FcmService`·`AlimtalkSender`·`SmsNotificationChannel`·`KakaoAlimtalkNotificationChannel`·`NotificationChannel` Javadoc | 4 | PASS | 생산자뿐이고 switch로 전수 분기하는 곳 없음 |
| 생성자 변경 | 직접 생성은 `NotificationDispatcherTest` 한 곳 | - | PASS | 반영됨. 나머지는 `@Mock` |
| Redis 키 충돌 | 기존 접두사 `sos:notify:cooldown:`, `anomaly:...`, `connection:request:...` | 5 | PASS | `notify:sms-fallback:`은 겹치지 않음. 모든 쓰기에 TTL(1시간), `noeviction` 용량 영향 미미 |
| Redis 장애 | `SmsFallbackLimiter` catch 후 true | 5 | PASS | #306~#309의 "Redis 명령 2초 제한"과도 맞물려 시간 초과도 fail-open |
| QA | `tests/sos` 등 | - | PASS | 푸시 토큰 없는 `guardian1`의 폴백이 실행당 1건 수준이라 30건에 못 미침. 번호 없는 임시 보호자(G09·G11)는 `NO_PHONE` 환불로 한도를 소비하지 않음 |
| 서버 반영 | vkcs·gosky `e80f673` | - | PASS | healthy, 기동 후 `SMS-FALLBACK-CAP`·`BindException` 0건, 서버 `.env`에 덮어쓰기 키 없음 → 기본 30 |

## PHASE C 테스트로 고정할 수 있는가

| 대상 | 현황 | 제안 |
|---|---|---|
| 정책 종류 한정 | 기존 `NotificationDispatcherTest`가 `FORCED_PUSH_WITH_SMS_FALLBACK` = `WARD_SOS`만임을 고정 | 충분 |
| 상한 경계·환불·fail-open | `SmsFallbackLimiterTest`, 디스패처 테스트 | 충분 |
| 구조 고정("limiter는 디스패처만 쓴다") | 없음 | 선택 - 쿨다운의 "사용처 구조 테스트"와 같은 형태 |
| Lua 실행(증가·환불) | 단위 테스트에서 실행되지 않음. `RedisCounter`는 기존 운영 검증 유틸, 환불은 인증번호 한도와 같은 스크립트 | 선택 - 통합 테스트에 Redis 컨테이너를 두는 일이라 비용이 큼. 운영에서 `[SMS-FALLBACK-CAP]` 로그와 이력으로 확인 |
| 사유 enum ↔ 문서·FE 계약 | 동기화 테스트 없음(이번 drift 원인) | 선택 - 문서를 읽는 테스트는 관례에 없어 제안만. 대신 사유를 추가할 때 이 점검의 체크리스트(아래)를 쓴다 |

## 이슈

### 🟡 M-1 · 새 사유가 문서·FE 계약에 반영돼 있지 않았다 (정정 완료)
- 사실: `RATE_LIMITED`가 추가됐지만 관리자 알림 이력 계약에는 사유 7종만 있었다. FE 타입 `ChannelFailureReason` 유니온에도 없었다. `reasonLabel`을 그대로 쓰는 화면은 문제가 없으나, `Record<ChannelFailureReason, …>`처럼 값을 전수 매핑하는 코드는 새 값에서 컴파일/표시가 어긋날 수 있다.
- 같은 맥락의 어긋남: `프로젝트_설명.txt`의 Redis 키 목록과 SOS 설명, `CLAUDE.md` §1 알림 채널 줄, SOS 연동 안내(FE 전달) 문구에 문자 폴백 상한이 없었다.
- 조치(이 커밋 + Notion): 
  - `docs/(2026-09-22) feature-admin-notification-history.md` 사유 표에 `RATE_LIMITED` 추가
  - Notion "관리자 API - 알림 이력" 사유 표와 TypeScript 타입에 `RATE_LIMITED` 추가
  - Notion "SOS 연동 안내"의 문자 대체 발송 행에 상한 설명 추가
  - `프로젝트_설명.txt` SOS 설명과 Redis 키 목록, `CLAUDE.md` §1 줄에 상한 추가

### 🟢 L-1 · Swagger "항상 발송" 설명에 문자 상한이 없다 (코드 변경이라 미적용)
- `WardSosController`의 `@Operation` 설명은 "긴급 알림은 필수 알림으로 항상 발송됩니다"라고만 한다. 푸시는 항상이지만 문자는 시간당 30건 상한이 있다. 설명 한 줄 추가를 PR로 제안한다.

### 🟢 L-2 · `[SOS-NO-DELIVERY]` 문구가 상한 초과 때 사실과 약간 다르다 (수용 유지)
- 전원이 상한을 넘어 전달 0명이 되면 "어느 보호자에게도 전달되지 않아"라고 찍히는데, 그 보호자들은 이미 그 시간에 문자 30건을 받았다. 로그 문구뿐이며 동작은 이전 검토에서 수용한 그대로다.

### 🟢 L-3 · 한도는 보호자 단위(수용 유지)
- 한 보호자가 피보호자 여러 명을 보면 한도를 공유한다. 결정 기록 있음(사용자 확인 "추천으로 진행").

### 🟢 L-4 · Lua는 운영에서만 검증된다
- 증가는 기존 운영 유틸이고 환불은 인증번호 한도 환불과 같은 스크립트다. 새 로직은 키 접두사와 호출 시점뿐이라 위험이 작다고 보고 통합 테스트는 두지 않았다.

## 사유(`ChannelFailureReason`)를 추가할 때 체크리스트
1. `ChannelFailureReason`(라벨 포함) 2. DB: JSONB라 마이그레이션 불요, 단 **이력에 쓰기 시작한 뒤 롤백하면 이전 코드가 모르는 값을 읽는다** 3. `docs/(2026-09-22) feature-admin-notification-history.md` 사유 표 4. FE 계약(Notion "관리자 API - 알림 이력")의 표와 TypeScript 유니온 5. 관리자 Swagger 설명 6. 이 값을 실패율에서 어떻게 다룰지(`result` 영향)

## 후속
| 항목 | 담당 | 비고 |
|---|---|---|
| M-1 문서·계약 정정 | 완료 | repo + Notion |
| L-1 Swagger 한 줄 | 선택 | 코드 변경이라 PR |
| 구조 테스트 / Redis 통합 테스트 | 선택 | 위 표 |
| FE에 `RATE_LIMITED` 전달 | FE | Notion 반영 완료, 전수 매핑 코드가 있으면 확인 |
