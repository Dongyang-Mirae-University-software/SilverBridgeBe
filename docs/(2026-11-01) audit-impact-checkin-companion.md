# 안부 말벗 1·2차 점검 - 영향 범위(템플릿 C) + 기능(템플릿 B) (2026-11-01)

> 대상: #427(1차, V65)·#429(2차, V66), 기준 커밋 `origin/dev` `40c61a8`. 점검만 했고 코드는 바꾸지 않았다.
> 정책: `.claude/rules/policy-checkin.md`, 구현: `docs/(2026-11-01) feature-checkin-companion.md`.

## 0. 변경점과 불변식

| 바뀐 공용 규칙 | 지켜야 할 불변식 |
|---|---|
| `NotificationType` 4개 추가(`CHECKIN_RISK`는 강제 정책 `FORCED_PUSH_PLUS_SETTINGS`) | 종류를 전수로 다루는 곳(라벨·관리자 탭·긴급도·강제 정책 가드·알림톡·이메일)이 새 종류를 빠짐없이, 의도대로 다룬다. 강제 FCM은 설정으로 줄지 않는다. 정지 수신자는 차단된다 |
| 보호자 알림 종류 설정 `checkinMissed`(V66) | 설정은 "보내지 않는 쪽"으로만 작용하고 참조처가 고정돼 있다. 필수 알림(위험)은 설정이 없다 |
| `AiMemberDataPurgeListener`에 `checkin-logs` 항목 | 항목별 독립·best-effort·200만 성공. 말벗이 없는 서버는 건너뛴다 |
| `SecurityConfig` turns URL 규칙, `ErrorCode` `CHECKIN_*`, 스케줄러 1개 추가 | WARD 외 역할은 multipart 파싱 전 403. 스케줄러 풀 3으로 충분 |

## 1. 영향 범위 - 사용처 판정 (템플릿 C)

| 영역 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 디스패처 정책 분기 | `NotificationDispatcher.dispatch` switch | PASS | 정책 단위 분기라 종류별 분기가 없다. 정지·탈퇴 수신자 차단(`withReceivableRecipient`)이 모든 정책에 적용된다 |
| 문자 대체 상한 | `SmsFallbackLimiter.tryAcquire(type, user)` | PASS | 종류별로 세므로 `CHECKIN_RISK`는 이상감지와 별도로 시간당 30건 |
| 관리자 라벨 | `NotificationTypeLabel` switch | PASS | 전수 switch(컴파일 강제) → "안부" |
| 관리자 탭 | `AdminNotificationCategory.OTHER`(나머지 전부) | PASS | 4종 모두 "기타". `AdminNotificationFilterTest` 갱신 |
| 웹푸시 긴급도 | `FcmMessageProperties`·yaml 기본값 | PASS | `CHECKIN_PROMPT`·`CHECKIN_RISK` 추가, 둘이 같다(`FcmServiceMessageTest`) |
| 강제 정책 가드 | `NotificationDispatcherTest.정책_가드` | PASS | `CHECKIN_RISK` 추가를 의도로 기록 |
| 알림톡 | yaml `templates`(이상감지 보호자용만) | PASS | 매핑이 없어 4종 모두 건너뜀(다발성, 승인 없음) |
| 이메일 | `NotificationType.allowedChannels` 기본 | PASS | `Email.ALLOWED`를 적지 않아 제외(`NotificationTypeEmailPolicyTest`) |
| executor | `CheckinGuardianAlertListener` | PASS | 위험 = `urgentNotificationExecutor`, 확인 필요 = `notificationExecutor`(`NotificationExecutorAssignmentTest`) |
| 보호자 설정 참조처 | `GuardianNotificationPreferenceUsageGuardTest` | PASS | `CheckinGuardianAlertService`만 추가. 디스패처는 설정을 모른다 |
| 보호자 설정 DB | V66 `checkin_missed_enabled DEFAULT TRUE`, `insertCheckinMissedIfAbsent` | PASS | 기존 행은 기본 ON. 실 DB 왕복 통합 테스트 통과 |
| 탈퇴 정리 | `AiMemberDataPurgeListener` | PASS | 항목별 try. 통합 테스트로 커밋 시 호출·롤백 시 미호출 고정 |
| 계정 상태 | 스케줄러 `eligible`(ACTIVE·WARD), 디스패처 차단 | PASS | 정지 피보호자에게 안부 알림 없음. 정지 보호자는 디스패처가 막음 |
| 역할 변경 | 피보호자 → 보호자 | PASS | 스케줄러가 WARD만 보고, 피보호자 API는 `hasRole('WARD')`. 연결은 역할 변경 정리가 끊는다 |
| 연결 해제 | 조회·질문·설정 | PASS | 모두 `isActiveConnection`. 조회는 D-1(지금 연결 이후)만 보인다 |
| 연결 해제 | `checkin_setting.prompt_enabled` | 🟢 L-1 | 켠 보호자가 연결을 끊어도 다른 ACTIVE 보호자가 있으면 알림이 계속 간다 |
| 스케줄러 풀 | `domain-security-policy` 재검토 | PASS | 12종, 말벗이 꺼진 서버는 즉시 반환 |
| DB CHECK | V65·V66 ↔ enum·상수 | PASS | `CheckinCheckSyncTest`(상태·끝난 이유·정서·시각·알림 종류)·`CheckinAiProjectionTest`(신호 코드) |

## 2. 기능 점검 (템플릿 B)

### 엔드포인트 × 역할

| 엔드포인트 | 역할 | 인가 | 결과 |
|---|---|---|---|
| `GET·PUT /api/guardian/ward/{wardId}/checkin/questions` | GUARDIAN | ACTIVE 연결, 형식이 틀린 wardId는 원문 로그 없이 403 | PASS |
| `GET·PUT /api/guardian/ward/{wardId}/checkin/setting` | GUARDIAN | 같음, 표정 동의는 못 바꿈 | PASS |
| `GET /api/guardian/checkin`·`/weekly` | GUARDIAN | 같음 + D-1 | PASS(실서버 403 확인) |
| `POST /api/ward/checkin/sessions` | WARD | 토큰 ID만 | PASS |
| `POST .../{id}/turns` | WARD(URL 규칙 + 메서드) | 형식 아니면 404(DB 안 봄), 남의 세션 403 + `[IDOR-ATTEMPT]` | PASS(`SecurityConfigAccessDeniedTest`) |
| `POST .../{id}/finish` | WARD | 같음 | PASS |
| `GET·PUT /api/ward/checkin/setting` | WARD | 본인만 | PASS |

- **IDOR**: PASS(남의 세션·연결되지 않은 피보호자 모두 403, 단위 테스트·실서버 확인)
- **PII·로그**: PASS - `CheckinLogGuardTest`(패키지 전체)·`LogRawExceptionGuardTest`, 실서버 `[CHECKIN]` 로그에 대화 내용 없음. 보호자 응답에 받아쓴 원문 칸이 없음(BE에 저장하지 않음)
- **상태 전환**: 시작 → 턴 → 정리 / 만료, 진행 중 부분 UNIQUE, 재전송 멱등 - PASS(실 DB 통합 테스트)
- **선점 후 발송**: 피보호자·보호자 알림 모두 REQUIRES_NEW 선점 뒤 발송 - PASS(순서 단위 테스트, ON CONFLICT 실 DB 테스트, 실서버 18:01 선점·발송 확인)

### 이슈

| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟡 | M-1 | **위험 대체 문자가 단문 한도를 넘는다**: `CHECKIN_RISK` `smsFallbackText`가 EUC-KR 93~95바이트(이름 3~4자)라 90바이트를 넘는다. 이상감지 보호자 대체 문자는 `fitsSingleSms`로 단문을 지키는데 말벗만 장문(LMS) 과금·분할 위험이 있다 | 문구를 줄이고(예: "[실버브릿지] {이름}님 안부 대화에 도움이 필요할 수 있어요. 연락해 주세요.") 이름이 길면 이름을 빼는 대체 문구를 둔다. `fitsSingleSms` 단위 테스트로 고정 |
| 🟡 | M-2 | **보호자 알림 경로를 처음부터 끝까지 확인한 시험이 없다**: 이벤트 발행 → AFTER_COMMIT → `@TransactionalEventListener(condition = SpEL)` → executor → 선점 → 디스패치는 조각별 단위 테스트뿐이다. 실서버에서도 위험·확인 필요 알림은 나가지 않았다(응급 문장 녹음이 없었음). SpEL 조건(중첩 enum 참조)이 틀리면 알림이 조용히 사라진다 | `FullContextIntegrationTest`를 상속한 `CheckinGuardianAlertFullIntegrationTest`로 턴 위험 → 커밋 → 긴급 executor에서 `CHECKIN_RISK` 디스패치까지 고정한다(목은 기반 클래스 규칙대로) |
| 🟡 | M-3 | **질문 변경 요청의 시각(`"09:00"`) 역직렬화를 HTTP로 확인하지 않았다**: `CheckinSlot`의 `@JsonCreator`·`@JsonValue`가 웹 계층(Jackson 3)에서 동작하는지 실서버·테스트 모두 확인하지 않았다(응답 직렬화는 실서버에서 `"15:00"` 확인). 모르는 값이 400이 되는지도 같다 | `GuardianChatControllerHttpTest`처럼 실제 HTTP 직렬화를 거치는 `GuardianCheckinControllerHttpTest`(정상 `"09:00"`, `"10:00"` 400) 추가 |
| 🟡 | M-4 | **보관 기간이 없다**: `checkin_result`·`checkin_prompt_log`·`checkin_guardian_alert_log`는 탈퇴 전까지 계속 쌓인다(피보호자당 하루 최대 5 + 5 + 보호자 수 × 3행). D-2(2026-10-31)는 접속 로그 90일·감사 로그 1년을 정했고, AI 서버는 대화 원문을 30일 둔다 | 사용자 결정: 예) 발송 기록 90일·결과 1년(평소 대비 14일·조회 60일보다 길게). 일일 정리는 기존 시각(04:40~05:00)과 겹치지 않게 |
| 🟢 | L-1 | 켠 보호자가 연결을 끊어도 다른 ACTIVE 보호자가 있으면 안부 알림이 계속 간다(남은 보호자는 켠 적이 없을 수 있음) | 수용 또는 연결 해제 이벤트에서 `prompt_changed_by`가 그 보호자면 끄기. 피보호자 단위 설정이라 현행 유지 권장 |
| 🟢 | L-2 | 보호자 조회가 칸마다 `isVisibleSinceConnection`을 불러 하루 최대 5회·주간 최대 약 70회 같은 연결을 조회한다 | 연결 시각을 한 번 읽어 비교(성능 문제는 아직 없음) |
| 🟢 | L-3 | `policy-notification.md` 기능 요약의 보호자 알림 종류 설명이 낡았다("끌 수 있는 건 재촉·복약뿐, 정서는 BE 발송 없음") - `cameraLinkLost`·`checkinMissed`가 빠졌고 안부 확인 필요는 발송된다 | 문서 갱신(코드 무관) |
| 🟢 | L-4 | 두 보호자가 같은 시각 질문을 동시에 처음 저장하면 UNIQUE 위반이 공용 409 "중복"으로 나간다 | 수용(드묾) 또는 `ON CONFLICT` upsert |
| 🟢 | L-5 | 스윕 purge(리스너를 거치지 않는 탈퇴 경로)에서는 AI 말벗 기록이 남는다(30일 뒤 AI가 자동 삭제) | 챗과 같은 수용 한계 - 정책 문서에 이미 있음 |

## 3. 테스트 커버리지

- 있음: 대화 서비스 30여 건(킬 스위치·인가·동의·턴/시간·끝난 이유·응답 창·판정 저장), AI 클라이언트 실 HTTP, 판정기 8건, 스케줄러 9건, 보호자 알림 5건, 조회 5건, 설정 5건, 저장소 이벤트 4건, 실 DB 통합(선점·부분 UNIQUE·JSONB·CHECK·CASCADE·설정 왕복·탈퇴 정리).
- 빈 곳: M-2(알림 처음부터 끝까지), M-3(요청 역직렬화), 위험 알림 실서버 수신.

## 4. 종합 판정

**⚠️ 점검됨(잔여 이슈 있음)** - 🔴·🟠 없음. 인가·IDOR·로그·선점 후 발송·공용 규칙 반영은 PASS. 🟡 4건 중 M-1·M-2·M-3은 코드·테스트로 바로 고칠 수 있고, M-4는 보관 기간 결정이 필요하다.

### 수정용 커밋 초안
- `fix: 안부 위험 알림 대체 문자를 단문 한도 안으로` (M-1)
- `test: 안부 보호자 알림 전체 경로·질문 요청 HTTP 역직렬화 고정` (M-2·M-3)
- `feat: 안부 말벗 결과·발송 기록 보관 기간 정리` (M-4, 결정 후)
- `docs: 알림 정책 기능 요약 - 보호자 알림 종류 갱신` (L-3)
