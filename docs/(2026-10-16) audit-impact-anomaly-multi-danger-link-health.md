# 영향 범위 점검 - 이상감지 동시 위험 처리 · AI 연결 상태 감지 (2026-10-16, 템플릿 C)

> 대상: PR #353(`0c19a2f`). 변경한 공용 규칙은 `NotificationType`(+`CAMERA_LINK_LOST`)과 `AnomalySignalParser`·`AiLiveStreamSubscriber`의 신호 계약이다.
> 방법: `NotificationType` 전 사용처·switch·`values()`/`allOf` 순회·테스트 가드 확인, 신호 경로(판정 → 이력 → 알림 → 클립 → 화면 상태)를 코드로 따라감, AI 서버 재배포(PR #19)에 따른 실서버 재접속 로그 확인.

## 사용처 대조

| 영역 | 확인 | 결과 |
|---|---|---|
| `NotificationTypeLabel` exhaustive switch | 컴파일로 강제 | ✅ "카메라" 라벨 추가 |
| `AdminNotificationCategory.OTHER` (`allOf` 빼기 방식) | 새 종류가 자동으로 "기타"에 들어감 | ✅ 의도(판정 요청·연결·문의와 같은 안내성). 테스트 기대값 갱신 |
| `AdminNotificationService` TypeScope `allOf` | 전체 조회에 자동 포함 | ✅ |
| 알림톡 매핑 | `AlimtalkTemplateMappingGuardTest`에 `CAMERA_LINK_LOST` 추가, 매핑 없음 | ✅ 스킵 |
| 이메일 | `allowedChannels = {FCM}`, `NotificationTypeEmailPolicyTest`에 추가 | ✅ |
| 정지·탈퇴 진행 계정 차단 | `dispatch()` 한 곳(수신자 기준) - 신규 경로도 동일 | ✅ |
| 사용자 설정 | `SETTINGS_ONLY`라 FCM을 끈 보호자는 `NOT_SENT / NO_ENABLED_CHANNEL`(실패 아님) | ✅ |
| 보호자 알림 종류 설정(`notification-type-setting`) | 이 종류는 목록에 없음 | 🟢 L-1 |
| `notification_log.type` CHECK | V54에서 CHECK 없음 | ✅ 마이그레이션 불필요 |
| 문자 대체·SOS/이상감지 쿨다운 해제 로직 | `FORCED_PUSH_*` 정책만 대상 - 신규 종류는 영향 없음 | ✅ |

## 신호 경로 (동시 위험)

- 쿨다운 키(이력·알림·클립)·incident 모두 `(session, type)` 단위 → 흉기+낙상이 서로 쿨다운을 먹지 않고, 같은 종류 반복은 기존대로 억제 ✅
- 같은 메시지의 종류별 처리는 순차이며 신호마다 격리 → 한 종류 DB 오류가 다른 종류를 막지 않음(`종류_하나_실패해도_나머지_처리`) ✅
- `results[]`가 없거나 대표만 위험이면 결과가 이전과 동일(대표 1건) → 기존 FIRE 단독 흐름 회귀 없음 ✅
- CONFIDENCE 폴백·`[ANOMALY-DANGER-MISMATCH]`는 대표를 계속 본다(대표가 위험 목록에 없을 때만 목록에 더함) ✅
- 화면용 `camera-analysis`·`findLatest`는 대표 1건 그대로 - FE 계약 불변 ✅
- 통계·관리자 로그: 흉기+낙상 동시 시 상황이 종류별로 2건 생기는 것이 정상(상황 단위는 `(ward, session, type)`) 🟢 L-2

## 연결 상태 경로

- 끊김 → `lastKnownSessions` 보관 → 재접속 후 첫 `live_streams`에서 한 번만 평가. 정상 종료(연결 유지 중 목록에서 사라짐)는 기존 `onSessionsEnded` 경로 그대로 - 알림 대상 아님 ✅
- 유예 중 연결이 다시 끊기면 `cancelAll()`; 스냅샷은 합쳐서 이어가므로 재접속 뒤 다시 평가됨 ✅
- 그 사이 삭제된 카메라는 스냅샷 평가와 발송 시점 두 번 걸러짐 ✅
- 쿨다운(Redis) 확인 실패 시 보내지 않음(fail-closed) - 이력·알림(fail-open)과 반대이며 의도 ✅
- `anomaly.resync-seconds=0`이면 반쯤 열린 연결 감지가 꺼짐 🟢 L-3

## 실서버 관찰 (gosky, AI 서버 PR #19 재배포 중 - 2026-10-16 10:59~11:00 KST)

- AI WS 종료(code 1006) → 재접속 시도가 2초·4초·7초·15초 간격으로 이어지고(AI가 502로 올라오는 동안) 11:00:02 연결됨. **상한 20초 + 지터가 실제로 적용됨**(이전 설정이면 32초 대기 구간).
- 재접속 후 세션 목록 요청까지 정상. 단, 이 시점에 구독 중이던 세션이 없어(등록 카메라 1대, 송출 중 아님) **사라진 세션 OFFLINE·`CAMERA_LINK_LOST` 경로는 실서버에서 실행되지 않았다**(단위 테스트로만 검증).
- 24시간 이력: FIRE 7 / WEAPON 1(모두 danger=true). 낙상 이력 없음 → **동시 위험(흉기+낙상) E2E는 여전히 미확인**.
- 참고(범위 밖): 만료된 JWT로 WebSocket 핸드셰이크를 6초마다 재시도하는 클라이언트가 있다(`JwtHandshakeInterceptor` WARN). FE의 재연결 정책 문제로 보이며 BE 변경 대상 아님.

## 발견 사항

| ID | 등급 | 내용 | 조치 |
|---|---|---|---|
| L-1 | 🟢 | 보호자 알림 종류 설정에 `CAMERA_LINK_LOST` 끄기가 없다(계정 FCM을 끄는 방법뿐). 카메라당 6시간 쿨다운으로 절제되나 AI 재시작 때 카메라 수만큼 한꺼번에 도착할 수 있음 | 수용. 반복 불만이 생기면 `guardian_notification_preference`에 항목 추가(마이그레이션 필요) |
| L-2 | 🟢 | 동시 위험이면 관리자 상황 목록에 같은 시각 2건(종류별) | 의도. FE/운영 안내만 |
| L-3 | 🟢 | `anomaly.resync-seconds=0`이면 반쯤 열린 연결 감지가 꺼짐 | 수용(기본 60초) |
| L-4 | 🟢 | stale 닫기에서 `close()` 예외 시 `afterConnectionClosed`를 직접 호출하는데 프레임워크 콜백과 겹치면 재접속이 두 번 예약될 수 있음(백오프라 해가 작음) | 수용. 관찰 후 필요하면 세션 일치 검사 추가 |
| I-1 | ℹ️ | 사라진 세션 OFFLINE·끊김 알림, 동시 위험 이력의 실서버 E2E 미확인 | 카메라 송출 중 AI 재시작 시 `[ANOMALY-SESSION-LOST-AFTER-RECONNECT]`·`[CAMERA-LINK-LOST]` 로그, 동시 감지 시 같은 시각 다른 종류 2건 확인 |

🔴 High·🟠 Medium 없음. 코드 수정 없음(모두 수용 또는 관찰).
