# 이상감지 동시 위험 처리 · AI 연결 상태 감지 (2026-10-16)

> 마이그레이션 없음. 새 `NotificationType` 1종(`CAMERA_LINK_LOST`) - `notification_log.type`은 CHECK가 없어 재정의 불필요(V54).

## 배경

AI 실시간 분석은 종류별 결과 `results[]`(화재·흉기·낙상 각각 `detectedType`·`confidence`·`danger`)를 보내고, 최상위
`detectedType/confidence/danger`는 그중 대표 1건(화재 > 흉기 > 낙상, AI `live_detection_merge.py` `KIND_PRIORITY`)이다.
백엔드는 대표만 읽어 흉기와 낙상이 동시에 위험이면 낙상이 빠졌다. 이 밖에 AI 재시작·반쯤 열린 연결에서 감시가 조용히 끊기는 경로가 있었다.

## 변경

| # | 내용 | 위치 |
|---|---|---|
| A | `results[]` 중 `danger=true`·감지 대상 종류를 **각각** 신호로 만들어 기존 판정·쿨다운·이력·알림·클립 경로에 태운다. 같은 종류(연기→화재)는 최고 신뢰도 1건, 대표는 위험 목록에 없을 때만 더한다(CONFIDENCE 폴백·불일치 경고 보존). `results[]`가 없으면 기존 대표 1건. 화면용 `camera-analysis`는 대표 그대로. 종류별 처리 실패는 격리 | `AnomalySignalParser.parseAll/expand`, `AiLiveStreamSubscriber.handleAnalysis` |
| B | 끊길 때 구독 세션 스냅샷(`lastKnownSessions`) 보관 → 재접속 뒤 **첫 목록**에서 빠진 등록 카메라를 `OFFLINE`으로 알린다(`onSessionsOffline`). 돌아오지 않으면 유예 2분 뒤 보호자 ACTIVE 전원에게 FCM 1회, 카메라당 6시간 쿨다운 | `AiLiveStreamSubscriber`, `LiveAnalysisBroadcaster`, `CameraLinkLostNotifier` |
| C | 수신 시각(`lastMessageAt`)을 기록하고 resync 타이머에서 `resync-seconds × stale-resync-multiplier`(기본 60×3=180초) 동안 아무 수신이 없으면 연결을 닫아 기존 백오프 재접속으로 보낸다 | `AiLiveStreamSubscriber.closeIfStale` |
| D | `anomaly.reconnect-max-seconds` 60 → 20, 지연에 0~25% 지터(상한 초과 없음) | `AnomalyProperties`, `application.yaml` |
| E | `[ANOMALY-UNKNOWN-TYPE-DANGER]`·`[ANOMALY-DANGER-FIELD-MISSING]` WARN, 세션·종류별 1분 1회. 원본 종류 문자열은 영숫자·`_-` 32자로 정제. **판정은 바꾸지 않음** | `AnomalySignalParser` |
| F | 낙상 클립 기본값 3/2 → 8/1(운영 env와 일치, AI 앞 상한 8·합계 10 반영 완료) | `AnomalyProperties`, `application.yaml` |

## 설정

| 키 | 기본 | 설명 |
|---|---|---|
| `anomaly.reconnect-max-seconds` (`ANOMALY_RECONNECT_MAX_SECONDS`) | 20 | 재접속 백오프 상한 |
| `anomaly.stale-resync-multiplier` | 3 | 0이면 반쯤 열린 연결 감지 끔 |
| `anomaly.link-lost-notify.enabled` | true | 킬 스위치(알림만 멈춤, 화면 OFFLINE 표시는 그대로) |
| `anomaly.link-lost-notify.grace-seconds` | 120 | 이 안에 다시 구독되면 보내지 않음 |
| `anomaly.link-lost-notify.cooldown-hours` | 6 | 카메라당 재알림 간격(Redis, 확인 실패 시 보내지 않음) |

## 동작 보존

알림 정책(`FORCED_PUSH_PLUS_SETTINGS`)·강제 FCM·정지 계정 차단·이력 실패 시 쿨다운 해제·전달 0건 쿨다운 해제·문자 대체·알림톡 보호자 전용·판정 모드·클립 5경로·킬 스위치는 그대로다.
쿨다운(이력·알림·클립)·incident 키가 모두 `(session, type)` 단위라 동시 위험을 각각 처리해도 폭주하지 않는다.

## 알림 `CAMERA_LINK_LOST` 규칙

- `SETTINGS_ONLY`, FCM만(`ANOMALY_REVIEW_REQUIRED`와 같은 허용 채널), 이메일 제외, **알림톡 매핑 금지**(다발성), 강제 채널로 승격하지 말 것.
- 문구는 원인을 단정하지 않는다("영상이 확인되지 않아 감시가 멈춰 있을 수 있습니다").
- 수신자는 ACTIVE 보호자뿐(`getActiveGuardianIds`). 보호자가 없으면 쿨다운도 쓰지 않는다.
- AI 연결이 다시 끊기면 유예 중인 알림을 모두 취소한다(상태를 모르는 동안은 보내지 않고, 재접속 뒤 첫 목록으로 다시 판단).
- 관리자 알림 이력 화면에서는 "기타" 탭에 잡힌다(`NotificationTypeLabel` = "카메라").

## 알려진 한계 (수용)

- 정상 종료(사용자가 카메라를 끔)는 알리지 않는다 - AI 연결 끊김 뒤 돌아오지 않은 카메라만 대상이다.
- AI 재시작 직후 다수 카메라가 동시에 복귀하지 못하면 보호자별로 카메라 수만큼 알림이 간다(카메라당 6시간 쿨다운이 상한).
- 구독 세션이 하나도 없는 동안 AI가 아무 메시지도 안 보내도 resync 목록 응답이 수신으로 잡힌다. 응답마저 없을 때만 닫는다.
- 단일 인스턴스 메모리 상태다(유예 대기·스냅샷).

## 테스트

`AnomalySignalParserMultiDangerTest`(화재+흉기·흉기+낙상·중복·옛 형식) / `AiLiveStreamSubscriberTest`(종류별 판정·격리·재접속 뒤 사라진/돌아온 세션·수신 없음 닫기) / `CameraLinkLostNotifierTest`(유예·복구·쿨다운·Redis 장애·킬 스위치) / `AiClipClientTest`(낙상 8/1).

## 후속 (2026-10-16) - 보호자 알림 종류 설정에 `cameraLinkLost` 추가 (V61)

- `GET·PUT /api/guardian/notification-type-setting`에 `cameraLinkLost`(끌 수 있음, 기본 ON, `required=false`) 추가. 요청의 `null`은 변경 없음.
- `guardian_notification_preference.camera_link_lost_enabled BOOLEAN NOT NULL DEFAULT TRUE`(V61, 컬럼 추가만 - 비가역 DDL 없음, 백필 불필요).
- `CameraLinkLostNotifier`가 ACTIVE 보호자에서 끈 사람을 빼고 보낸다. 전원이 껐으면 쿨다운도 쓰지 않는다. 설정 조회가 실패하면 전원에게 보낸다(끈 사람에게 한 번 더 가는 쪽이 낫다).
- **게이트는 이 알림 하나뿐이다** - 이상감지 발생·SOS 필수 알림 경로와 디스패처는 이 설정을 읽지 않는다(`GuardianNotificationPreferenceUsageGuardTest`에 `CameraLinkLostNotifier`만 추가).
