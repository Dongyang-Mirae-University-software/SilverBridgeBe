# 영향 범위 점검 - #425 알림 설정 조회 실패 격리 (2026-11-02)

> 템플릿 C. 대상 PR #425(머지 `ff2583a`, 2026-11-01). 점검은 PHASE A~D(읽기만)이고, 수정은 승인 후 PHASE E에서 한다.
> 기준 커밋: dev `96574ea`. 두 서버 모두 #425 반영됨(vkcs-linux `1837b39`, gosky dev `1a0103e`).

## 1. 변경점 (#425 diff 확인 결과)

| 위치 | 바뀐 것 |
|---|---|
| `NotificationDispatcher.dispatchBySettings` | `settingsChannels()` 예외를 잡는다. `[NOTIFY-SETTINGS-UNAVAILABLE]` WARN(클래스명만) 후 `Outcome.settingsUnavailable()`를 돌려준다. 결과는 `FAILED`, 시도 채널은 0건이다. 예전에는 예외가 호출자로 올라갔다 |
| `NotificationDispatcher.dispatchForcedPushPlusSettings` | 같은 예외를 잡는다. 강제 FCM만 남기고 그대로 발송한다. 선택 채널은 빠지고, 문자 대체는 "켠 문자를 시도 안 함"으로 보고 기존 규칙을 따른다 |
| `ConnectionNotificationListener.handleForced` | 보호자·피보호자 발송을 `dispatchForced()`로 각각 try/catch한다 |
| 그대로인 것 | `withReceivableRecipient`(정지·탈퇴 차단)는 강제 정책에서 설정 조회보다 **먼저** 돈다. `recipientResolver.resolve()`(users 조회) 예외는 격리하지 않는다. `dispatchMandatory`(SOS)는 설정을 읽지 않는다 |

설명대로임을 코드로 확인했다. 차이가 하나 있다: 격리 대상은 **설정 조회만**이고, 수신자 조회 실패는 여전히 예외로 올라간다(L-1).

### 불변식
1. 강제 채널(SOS = 푸시+문자, 이상감지·안부 위험 = 푸시)은 설정 조회 실패로 줄지 않는다.
2. 정지·탈퇴 진행 수신자 차단은 설정 조회 실패와 무관하게 유지된다.
3. 한 수신자의 실패가 같은 이벤트의 다른 수신자 발송을 막지 않는다.
4. 설정 조회 실패가 호출자 트랜잭션·리스너를 깨뜨리지 않고, 이력에 남는다.
5. 문자 대체·시간당 상한·SOS 쿨다운의 기존 규칙은 그대로다.

## 2. 사용처 (호출처 17곳, global 0곳, 공지·웹푸시 data-only는 디스패처를 거치지 않음)

| 호출처 | 종류(정책) | 호출 위치 | 수신자 루프·격리 | 결과값 사용 |
|---|---|---|---|---|
| `sos/listener/SosNotificationListener:105` | WARD_SOS (강제+문자) | @Async urgent, AFTER_COMMIT | 보호자 루프, 회차별 try | `isDelivered` 0건이면 쿨다운 해제 |
| `anomaly/listener/AnomalyNotificationListener:106` | ANOMALY_DETECTED(_SELF) (강제+설정) | @Async urgent, AFTER_COMMIT | 수신자 루프, 회차별 try | FAILED·예외면 수신자 쿨다운 해제, NOT_SENT면 유지 |
| `anomaly/service/AnomalyReviewReminderService:42·62·78` | REVIEW_REQUIRED·CONFLICTED (설정) | @Scheduled 5분 | 선점 목록 루프, 회차별 try | 무시(선점 후 재시도 없음) |
| `anomaly/service/CameraLinkLostNotifier:142` | CAMERA_LINK_LOST (설정) | notificationExecutor | 보호자 루프, 회차별 try | 무시(6시간 쿨다운 선점, 미해제) |
| `checkin/service/CheckinPromptPlanner:96` | CHECKIN_PROMPT (설정) | @Scheduled 1분 | 피보호자 루프, 회차별 try | 무시(선점) |
| `checkin/service/CheckinGuardianAlertService:106` | CHECKIN_RISK (강제+설정) / ATTENTION·MISSED (설정) | @Async listener·스케줄러 | 보호자 루프, 회차별 try | 무시(선점) |
| `connection/listener/ConnectionNotificationListener:52·83·156·187` | 요청·수락·거절·해제 (설정) | @Async notificationExecutor | 단일 수신자 | 무시 |
| `connection/listener/ConnectionNotificationListener:126` | CONNECTION_FORCED (설정) | @Async | 2명, 수신자별 try(#425) | 무시 |
| `inquiry/listener/InquiryNotificationListener:37` | INQUIRY_ANSWERED (설정) | @Async | 단일 | 무시 |
| `medication/listener/MedicationWithdrawalListener:88` | MEDICATION_STOPPED (설정) | **동기** AFTER_COMMIT | 보호자 `forEach`, **회차별 try 없음** | 무시 |
| `medication/service/MedicationReminderService:60` | MEDICATION_REMINDER (설정) | @Scheduled 1분 | 루프, 회차별 try | 무시(선점) |
| `medication/service/MedicationMissedAlertService:39` | MEDICATION_MISSED (설정) | @Scheduled | 루프, 회차별 try | 무시(선점) |

## 3. 사용처 × 불변식 판정

| 도메인 / 위치 | ① | ② | ③ | ④ | ⑤ | 근거 |
|---|---|---|---|---|---|---|
| SOS | PASS | PASS | PASS | PASS | PASS | `dispatchMandatory`는 설정을 읽지 않는다(`NotificationDispatcherTest:189` `verifyNoInteractions(settingService)`). 쿨다운 해제 판단은 결과값 기준이라 변화가 없다 |
| 이상감지 발생 | PASS | PASS | PASS | PASS | PASS | 설정 실패 시 강제 푸시가 나간다. 푸시 실패면 문자 대체로 간다. 결과 FAILED면 리스너가 쿨다운을 푼다(예전에는 예외 → 해제로 같은 효과였고, 지금은 푸시까지 나간다) |
| 안부 위험 CHECKIN_RISK | PASS | PASS | PASS | PASS | PASS | 같은 강제+설정 정책이라 #425 효과를 그대로 받는다(가드 `NotificationDispatcherTest:566`) |
| 재촉·요약·동수 안내 | 해당없음 | PASS | PASS | PASS | PASS | 예전 예외 → catch 로그였던 것이 이제 FAILED 이력으로 남는다. 선점 후 재시도가 없다는 점은 전후가 같다 |
| 카메라 연결 끊김 | 해당없음 | PASS | PASS | PASS | 해당없음 | 6시간 쿨다운 미해제는 전후가 같다(L-4) |
| 안부 안내·확인 필요·미응답 | 해당없음 | PASS | PASS | PASS | 해당없음 | 선점 후 무시라 전후가 같다 |
| 연결 4종·문의 | 해당없음 | PASS | 해당없음 | PASS | 해당없음 | 단일 수신자다. 예전에는 @Async 예외로 끝나 이력이 없었고, 이제 FAILED로 남는다(개선) |
| 강제 연결 | 해당없음 | PASS | PASS | PASS | 해당없음 | #425가 고쳤다(`ConnectionNotificationListenerTest`, `AdminForceConnectFullIntegrationTest`) |
| 복약 중지 안내 | 해당없음 | PASS | **부분** | PASS | 해당없음 | 설정 실패는 이제 격리된다. 다만 수신자 조회·WS 예외는 남은 보호자와 다른 피보호자 안내까지 끊는다(M-1) |
| 복약 시각·미복용 요약 | 해당없음 | PASS | PASS | PASS | 해당없음 | 선점 후 무시 |

**트랜잭션(④)**: `enabledChannels`는 `@Transactional(readOnly)` REQUIRED다. 활성 트랜잭션 안에서 예외가 나면 그 트랜잭션이 rollback-only로 표시된다. 그런데 호출처 중 진행 중인 트랜잭션 안에서 dispatch를 부르는 곳은 없다. 동기 AFTER_COMMIT(복약 중지)은 이미 커밋된 트랜잭션이라 표시가 나도 영향이 없다. 이력 기록은 `REQUIRES_NEW`다. → PASS

**웹푸시 data-only·high urgency**: `FcmService`의 메시지 생성 단계에서 정해지며 설정 조회와 겹치지 않는다. 설정 실패 시 강제 푸시도 같은 빌더를 탄다. → 영향 없음

### "예외가 사라져 생긴 변화"
- 결과값으로 쿨다운을 판단하는 곳은 SOS와 이상감지뿐이다. 둘 다 FAILED를 "미전달"로 보므로 **실패 뒤 쿨다운이 남아 다음 알림이 버려지는 경우는 없다.**
- 나머지는 모두 발송 전에 선점하고 결과를 무시한다. #425 이전에도 예외를 잡아 로그만 남겼으므로 동작은 같다. 바뀐 것은 로그의 `sent` 집계뿐이다(L-3).

## 4. 이슈

| 등급 | ID | 내용 | 근거 | 재현 조건 | 고치는 방법 | 고정 테스트 |
|---|---|---|---|---|---|---|
| 🟡 | M-1 | 복약 중지 안내에 수신자별 격리가 없다(③) | `MedicationWithdrawalListener.java:84-88` `recipients.forEach`, 바깥 `catch`는 `:60` | 보호자 탈퇴 시 남은 보호자 A의 users 조회(디스패처 `resolve`)나 WS 발송이 예외를 던지면 → 보호자 B와 다음 피보호자 안내가 모두 빠진다 | 회차마다 try/catch(클래스명만 로그). 강제 연결 `dispatchForced`와 같은 형태 | `MedicationWithdrawalListenerTest`: 첫 보호자 dispatch 예외 → 두 번째 보호자 발송·다음 피보호자 처리 |
| 🟡 | M-2 | 설정 조회 실패 FAILED는 시도 채널이 0건이라 관리자 이력의 실패 사유 팝업이 비고, Swagger 설명("시도한 채널이 모두 실패")과 맞지 않는다 | `AdminNotificationController.java:47`, `AdminNotificationItem.java:20`(팝업은 `channelResults`로 그린다) | 설정 DB 오류 중 연결·문의 알림 1건 → 이력에 FAILED + 빈 채널 결과 | Swagger 설명에 "채널 결과가 빈 FAILED = 알림 설정 조회 실패로 시도하지 않음"을 추가하고 FE 전달 메모를 남긴다(관리자 FE는 FE 담당). 사유 컬럼 신설은 CHECK 마이그레이션이 필요해 제안하지 않는다 | Swagger 문구 테스트(선택) |
| 🟢 | L-1 | 수신자 조회(users) 실패는 #425 범위 밖이다. 예외가 호출자로 올라가고 이력이 남지 않는다 | `NotificationDispatcher.withReceivableRecipient` | DB 장애 | **수용**: 호출처 모두 수신자별 try가 있고(M-1 제외) SOS·이상감지는 예외 시 쿨다운을 푼다. users 조회가 실패하는 장애면 FCM 토큰 조회·이력 기록도 실패해 격리해도 얻는 것이 적다 | - |
| 🟢 | L-2 | 설정 기반 경로는 설정 조회가 상태 확인보다 먼저라, 정지 계정 + 설정 실패면 NOT_SENT(RESTRICTED) 대신 FAILED로 남는다 | `dispatchBySettings` | 정지 계정 + 설정 DB 오류 | **수용**: 발송은 0건이라 ②는 지켜진다. 사유 표시만 다르다 | T-2로 "아무 채널도 호출 안 함"을 고정 |
| 🟢 | L-3 | 스케줄러 경로의 `sent++`가 결과와 무관해서, 설정 실패(이제 FAILED)도 "발송 n건" 로그에 들어간다. 예전에는 예외라 세지 않았다 | `AnomalyReviewReminderService`·`MedicationReminderService`·`CheckinPromptPlanner` | 설정 DB 오류 중 스케줄러 실행 | 선택: `isDelivered()`일 때만 센다. 기능 영향은 없다 | - |
| 🟢 | L-4 | 카메라 연결 끊김 6시간 쿨다운은 발송 전에 선점하고 실패해도 풀지 않는다 | `CameraLinkLostNotifier:131-146` | 끊김 알림 발송 실패 후 6시간 안의 재끊김 | **기록만**: #425 전후 변화가 없는 기존 설계다 | - |

🔴·🟠 0건.

## 5. 테스트로 고정할 것 (PHASE C)

#425가 이미 고정한 것: 이상감지 설정 실패 → 강제 푸시, 설정 실패 + 푸시 미전달 → 문자 대체, 설정 기반 → FAILED, 강제 연결 양방향 격리.

| ID | 종류 | 막는 불변식 | 내용 |
|---|---|---|---|
| T-1 | 단위(`NotificationDispatcherTest`) | ① | `FORCED_PUSH_PLUS_SETTINGS` 전 종류(`@EnumSource` + 정책 필터: 이상감지 2종·CHECKIN_RISK) × 설정 조회 실패 → 강제 푸시 발송. 새 강제 종류가 생겨도 자동 포함된다 |
| T-2 | 단위(같은 파일) | ② | 정지·탈퇴 진행 수신자 + 설정 조회 실패 → 강제 정책은 NOT_SENT(설정 조회 자체를 안 함), 설정 기반은 어떤 채널도 호출하지 않음 |
| T-3 | 단위(`MedicationWithdrawalListenerTest`) | ③ | M-1 수정 고정 |
| (안 함) | 통합(Full) | - | 설정 저장소 실패를 주입하려면 기반 클래스에 목을 더해야 해서 모든 `*FullIntegrationTest`의 컨텍스트가 바뀐다. 단위로 충분하다 |

## 6. 종합 판정

- #425는 설명대로 동작한다. 불변식 ①②④⑤는 전 사용처에서 PASS다.
- ③은 복약 중지 안내 한 곳만 부분 PASS다(M-1). 다만 설정 조회 실패 자체는 그곳에서도 이미 격리된다.
- 고칠 후보: M-1(코드+테스트), M-2(Swagger 문구+FE 전달 메모), T-1·T-2(테스트만). L-1~L-4는 수용·기록이다.
- 정책 변경은 없어 `policy-notification.md`는 그대로 둔다(수정 시 M-1 결과만 반영 여부 판단).

## 7. PHASE E 반영 (2026-11-02, 사용자 승인: 권장안 + 이슈 전부 + 테스트 제안)

브랜치 `fix/impact-dispatcher-isolation`. 🟢 L-1~L-4도 "수용" 대신 코드로 반영했다.

| ID | 반영 | 고정 테스트 |
|---|---|---|
| M-1 | `MedicationWithdrawalListener` 보호자별 try/catch(클래스명만 ERROR) | `MedicationWithdrawalListenerTest.한보호자_실패해도_나머지_안내` |
| M-2 | Swagger `GET /api/admin/notification` 결과 설명에 "채널 결과가 빈 FAILED = 설정·수신자 조회 실패" 추가. 관리자 FE는 FE 담당이라 FE 전달 메모로 남김 | - |
| L-1 | 디스패처 `withReceivableRecipient`가 수신자 조회 예외를 잡아 `FAILED`(시도 0건) 기록 - 상태를 모르면 강제 정책이라도 보내지 않는다(정지 차단 우선, 실제로는 같은 DB 장애로 토큰·번호도 못 읽는다) | `NotificationDispatcherTest.수신자조회실패_실패로기록`(SOS·이상감지·설정 기반), `설정과수신자_모두조회실패_실패로기록` |
| L-2 | 설정 조회 실패 시 수신자 상태를 확인해 정지·탈퇴 진행이면 `NOT_SENT`(차단 사유) | `설정기반_설정조회실패_정지수신자는_차단사유` |
| L-3 | 스케줄러 6곳(재촉·요약·동수·복약 시각·미복용·말벗 안내·말벗 보호자 알림)의 `sent`가 `FAILED`를 세지 않음 | `AnomalyReviewReminderServiceTest.failedResultIsNotCounted` |
| L-4 | `CameraLinkLostNotifier` - 전원 미전달 + 실패(FAILED·예외)면 6시간 쿨다운 해제, `NOT_SENT`뿐이면 유지 | `CameraLinkLostNotifierTest` 3건 |
| T-1 | `FORCED_PUSH_PLUS_SETTINGS` 전 종류 × 설정 조회 실패 → 강제 푸시(목록 전제 테스트 포함) | `강제정책_전종류_설정조회실패_강제푸시` |
| T-2 | 정지 수신자 + 강제 정책 → 설정 조회 없이 차단 | `강제정책_정지수신자_설정조회없이_차단` |

마이그레이션·통합 테스트 변경 없음(vkcs 브랜치 통합 테스트 불요, CD가 배포 전에 돌린다). 정책 갱신: `policy-notification.md` #425 절, `policy-anomaly.md` 카메라 연결 끊김.
