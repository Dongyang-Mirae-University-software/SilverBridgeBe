# 관리자 알림 이력 점검 이슈 반영 (2026-09-22)

> 브랜치 `fix/admin-notification-audit-findings` · 마이그레이션 없음
> 근거: `docs/(2026-09-22) audit-impact-notification-channel-result.md` (템플릿 C, #257)

## 결정 (사용자 승인 2026-09-22)

| 이슈 | 결정 |
|---|---|
| M-1 실서버 기록 미확인 | 통합 테스트로 AFTER_COMMIT 경로 고정 + dev 실발송 1건 뒤 vkcs 조회(**사용자가 dev에서 연결 요청을 한 번 보냄**) |
| L-1 채널 꺼 둠도 INSERT | 코드 변경 없음(수용 유지), 정책 파일에 명시 |
| L-2 스케줄러 6 / 풀 3 | **풀 3 유지**, `application.yaml` 주석·정책 파일의 스케줄러 목록 정정 |
| L-3 연결 알림 피보호자 칸 공란 | 연결 이벤트 3종에 당사자 ID 추가, 리스너 4인자 `dispatch` |
| L-4 기록 누락 가드 없음 | 파라미터화 테스트 추가 |

## 변경

### L-3 연결 이벤트 계약

| 이벤트 | 추가 필드 | 발행처 |
|---|---|---|
| `ConnectionAcceptedEvent` | `wardId` | `acceptConnectionAsWard` |
| `ConnectionRefusedEvent` | `wardId` | `refuseConnectionAsWard` |
| `ConnectionDisconnectedEvent` | `guardianId`, `wardId` (해제된 연결의 당사자) | 보호자 해제 · 피보호자 해제 · 탈퇴 정리 · 역할 변경 정리 · 관리자 강제 해제(양쪽) |

- 소비자는 `ConnectionNotificationListener` 하나다. 수락·거절·해제 3곳이 4인자 `dispatch(수신자, wardId, …)`로 바뀌었다.
- **알림 대상·문구·WebSocket 이벤트는 그대로다.** 새 필드는 이력의 "피보호자" 칸과, 보류해 둔 이상감지 E-3(해제·탈퇴 보호자 표 재계산)을 고칠 때 쓸 값이다. E-3 로직은 만들지 않았다.
- 알려진 한계: 피보호자 탈퇴 경로에서는 비동기 해제 알림보다 purge가 먼저 끝나 FK 때문에 그 이력만 남지 않을 수 있다(`[NOTIFY-LOG-FAILED]` WARN, 발송 무영향). 어차피 CASCADE로 지워질 행이라 수용.

### M-1 `NotificationLogAfterCommitIntegrationTest` (신규)

- 테스트 트랜잭션을 끄고(`NOT_SUPPORTED`) 실제로 커밋한 뒤, `afterCommit` 콜백에서 기록한다 - 동기 AFTER_COMMIT 리스너(`MedicationWithdrawalListener`)와 같은 자리.
  - `NotificationLogService.record()`(REQUIRES_NEW) → 행이 남는다.
  - 대조군: 저장소에 바로 저장(기본 전파) → 이미 커밋된 트랜잭션에 합류해 **남지 않는다**. 이 대조군이 REQUIRES_NEW의 근거를 코드로 고정한다.
- 실제 커밋이라 전용 ID(`GAC001`·`WAC001`)를 쓰고 `@AfterEach`에서 사용자를 지워 이력도 CASCADE로 지운다.

### L-4 `NotificationDispatcherTest` 가드

- `NotificationType` 전 값(14) × 수신자 상태(ACTIVE·RESTRICTED) = 28건: `dispatch` 한 번에 `record()`가 정확히 한 번, 종류·wardId·결과(ACTIVE=DELIVERED / RESTRICTED=NOT_SENT+RESTRICTED_ACCOUNT)가 맞는지.

### L-1·L-2 문서

- `.claude/rules/domain-security-policy.md`: 알림 이력 절에 L-1(채널 꺼 둠도 기록하는 이유)·테스트 고정·연결 이벤트 당사자 필드, 운영 설정 절에 스케줄러 6종·풀 3 유지 근거, 이상감지 E-3 선행 작업 완료 표시.
- `application.yaml`: `spring.task.scheduling.pool` 주석의 "5종"을 실제 6종 목록으로.

## 변경 파일

- main: `connection/event/ConnectionAcceptedEvent` · `ConnectionRefusedEvent` · `ConnectionDisconnectedEvent` · `connection/service/ConnectionService` · `connection/listener/ConnectionNotificationListener` · `application.yaml`(주석)
- test: `ConnectionServiceTest`(7개 발행 경로의 당사자 필드 검증 추가) · `ConnectionNotificationListenerTest`(4인자, `eq(WARD_ID)`) · `NotificationDispatcherTest`(가드 28건)
- integrationTest: `NotificationLogAfterCommitIntegrationTest`
- docs: 이 문서 · `progress.md` · `audit-index.md` · `.claude/rules/domain-security-policy.md`

## 검증

- `./gradlew test`: **681건, 실패 0** (건너뜀 1은 기존) - 이전 653 + 가드 28
- `./gradlew build -x test`: 통과
- 통합 테스트: 컴파일 확인, 실행은 push 후 vkcs `tools/integration-test.sh fix/admin-notification-audit-findings`
- M-1 실서버: dev에서 연결 요청 1회 후 `notification_log` 조회 - 머지·배포 뒤 확인
