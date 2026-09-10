# 점검 이슈 반영 ② - 회귀 재점검 + 기술 횡단 점검

> 근거: `(2026-09-10) audit-regression-pre-230.md`(R-*) · `(2026-09-11) audit-technical-cross-cutting.md`(B-*·C-*·D-*·E-*)
> 브랜치 `fix/audit-findings-2026-09-11`, 마이그레이션 **V51**(인덱스 2종, 비가역 아님)
> 결정(사용자): E-1 = 관리 밖 인프라라 수용 / R-1 = 400 / 전부 한 PR. 제외: F-1~F-3(별도 refactor PR), R-4(문의 CASCADE 정책 유지), B-2의 AI 전용 executor 분리

---

## PHASE 0 전제 차이

| 전제 | 실제 | 대응 |
|---|---|---|
| `CONNECTION_TARGET_NOT_ACTIVE` 문구가 보호자용 | "이용 중인 회원만 연결할 수 있습니다…" - 수신자 중립 | 재사용, ErrorCode 신설 없음 |
| Abort로 바꾸면 예외가 호출 스레드로 간다 | `@Async` 프록시가 `TaskRejectedException`을 던짐 | 거부 핸들러를 직접 구현해 로그 후 폐기(예외 없음) |
| 설정 키 유효성 | `spring.task.scheduling.pool.size`·`server.shutdown`·`mail.smtp.*timeout` 모두 Boot 4.0.5 유효 | 그대로 |
| 감사 로그 SLF4J 출력을 전제한 테스트 | 없음 | detail 제거 |
| `FileServerClient` 직접 생성 테스트 | 없음 | requestFactory 추가 |
| 인덱스 관례 | 엔티티 `@Table(indexes)` + 마이그레이션 양쪽 | 양쪽 추가 |

## 변경 파일

| 이슈 | 파일 | 변경 |
|---|---|---|
| R-1 | `ConnectionService.acceptConnectionAsWard` | 보호자 `status != ACTIVE` → 400 `CONNECTION_TARGET_NOT_ACTIVE`, 상태 불변·이벤트 없음 |
| B-1 | `application.yaml` | `spring.task.scheduling.pool.size: 3` |
| B-2 | `AsyncConfig` | 큐 100→500, `CallerRunsPolicy` → 거부 시 `[NOTIFY-REJECTED]` ERROR 로그 후 폐기 |
| B-3 | `AsyncConfig`·`application.yaml` | `waitForTasksToCompleteOnShutdown(true)`·`awaitTerminationSeconds(20)` / `server.shutdown: graceful` |
| C-1 | `application.yaml` | `spring.mail.properties.mail.smtp.{connectiontimeout,timeout,writetimeout}` 10초 |
| C-2 | `FileServerClient` | `SimpleClientHttpRequestFactory` connect 3초·read 10초 |
| E-2 | `AdminAuditLogService` | SLF4J에는 adminId·action·targetId만 (detail은 DB에만) |
| E-4 | `RedisKeys` | `CHARACTER_EXPRESSION`·`ADMIN_DASHBOARD_SUMMARY` 삭제(사용처 0) |
| R-6 | `TokenCleanupScheduler` | cron `zone = "Asia/Seoul"` |
| D-1·D-2 | `V51__add_cleanup_and_dashboard_indexes.sql` · `FcmToken`·`AnomalyIncident` `@Table` | `idx_fcm_token_updated_at`, `idx_anomaly_incident_started_at` |
| R-5 | `AdminAnnouncementServiceTest`(신규 5) · `AnnouncementServiceTest`(신규 4) | 감사 로그·404·배치 조회·조회수 |
| R-3·E-1·운영 | `.claude/rules/domain-security-policy.md` · `CLAUDE.md` | 안전 이력 보존 정책 차이 / Swagger 공개 수용 / 운영 설정 절 |

## 테스트

| 클래스 | 추가 |
|---|---|
| `ConnectionServiceTest` | 정지·탈퇴 진행 보호자 수락 400 + 상태 불변 (2), 기존 정상 수락 테스트에 보호자 스텁 추가 |
| `AsyncConfigTest` (신규) | 포화 시 호출 스레드 실행 없음·예외 없음 / 종료 대기 설정 (2) |
| `AdminAuditLogServiceTest` (신규) | detail이 DB에는 있고 로그에는 없음 (1, Logback ListAppender) |
| `AdminAnnouncementServiceTest` (신규) | 5 |
| `AnnouncementServiceTest` (신규) | 4 |

## 검증

| 항목 | 결과 |
|---|---|
| 대상 테스트 7클래스(연결·공지 2·AsyncConfig·감사 로그·강제 연결·FCM) | **통과** |
| `./gradlew clean build --no-daemon` (00:1x KST) | 114 클래스 / **589 tests / 1 failure / 1 skipped**(기존 575 + 신규 14) |
| 실패 1건 | `MedicationMissedAlertPlannerTest.집계상한_보호자별` - **이 PR과 무관한 기존 테스트**(파일·Planner 모두 미변경). `MedicationClock.now()` 실시간에 상대 시각(`now - 60분`)을 쓰는 구조라 **00:00~01:30 KST 사이에는 자정을 넘어 되감겨** 실패한다. 같은 날 낮에 돌린 이전 빌드(575/0)에서는 통과. 별도 처리 제안: 테스트를 고정 시각 기준으로 바꾸거나 자정 근처 `assumeTrue`로 건너뛰기 |
