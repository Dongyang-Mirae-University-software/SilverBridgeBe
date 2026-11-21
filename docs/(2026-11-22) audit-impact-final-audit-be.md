# 영향 범위 점검(C) - 최종 점검 BE 조치 #515

- 일자: 2026-11-22 · 템플릿 C(공용 규칙 변경) · 점검만(코드 변경 없음)
- 대상: BE #515 (`298c532f`) + #514 (`c999c0e9`)
- 기준 코드: `origin/dev` `02c1c70e`(#515 포함, 별도 worktree에서 읽음)
- 조사: PHASE A sonnet(사용처 수집) · PHASE B fable xhigh(판정, Spring 7.0.9·SDR 4.0.7 소스 대조) · 핵심 판정(풀 종료 순차)은 메인이 `ExecutorConfigurationSupport` 소스로 다시 확인
- 선행 문서: `(2026-11-21) audit-full-2026-11-final.md` · `(2026-11-21) fix-audit-full-2026-11-final.md`

## 결론

- **🔴 0 · 🟠 0 · 🟡 4 · 🟢 6** - 조건부 PASS.
- 네 가지 규칙 중 R2(쿨다운 전 수신자 거르기)·R3(역할 변경 정리)·R4(AI 중계 자리)는 사실상 지켜진다.
- **R1(종료 예산)은 식 자체가 실제와 다르다(I-1).** 테스트는 "단계 15초 + 가장 긴 풀 20초 < 40초"를 보지만, Spring 7은 풀을 `destroy()`에서 **하나씩 차례로** 기다린다. 긴급 알림 큐는 이 때문에 줄지 않지만(풀은 자기 차례 전까지 계속 돈다), 클립·AI 삭제·접속 로그가 배포 때 잘릴 수 있다.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 오늘 | 2026-11-22 |
| 기준 | `origin/dev` `02c1c70e`, `298c532f` 포함 확인 |
| 빌드 | `./gradlew build -x test --no-daemon` 통과 |
| compose | `stop_grace_period: 40s`(dev·prod) |

## PHASE 0. 바뀐 공용 규칙과 불변식

| # | 바뀐 것 | 불변식 |
|---|---|---|
| R1 | `timeout-per-shutdown-phase: 15s` + 풀 종료 대기 | 종료 대기를 모두 더해도 40초 안에 끝나고, 긴급 알림 큐가 확실하게 비워진다 |
| R2 | `CameraLinkLostNotifier.receivers` ACTIVE 필터 | 쿨다운·선점을 잡는 알림은 ACTIVE 수신자만 세고, 0명이면 쿨다운을 잡지 않는다 |
| R3 | `GameRecordPurgeListener.handleRoleChanged` | 역할이 바뀌면 옛 역할로만 쓰이던 데이터는 탈퇴 정리와 같은 범위로 지운다 |
| R4 | `GameRelaySlots` | AI를 부르는 사용자 경로는 서버·사용자 상한이 있고, 인가 뒤에 자리를 잡고, 반드시 돌려준다 |

## PHASE A·B. 사용처 표와 판정

### R1 종료 예산

Spring 7 동작(소스 확인): `waitForTasksToCompleteOnShutdown=true`면 `ContextClosedEvent`에서 `lateShutdown`만 세우고 lifecycle `stop()`은 아무것도 하지 않는다. 실제 대기는 `destroy()` → `shutdown()` → `awaitTermination`이고 빈 파괴는 한 스레드에서 순차다.

| 위치 | 판정 | 근거 |
|---|---|---|
| `AsyncConfig.java:120-121`, Y:102-107, `ShutdownGraceBudgetTest` | **FAIL** | 풀 5개 × 20초가 합산된다. 단계 대기도 웹 graceful·스케줄러 두 번 가능, `@PreDestroy` 5초 3개(Solapi·Email·AccessLog) - 이론 최악 145초 (I-1) |
| 긴급 풀 "가장 먼저" | 문자상 FAIL, 실효 PASS | 파괴 순서는 aiPurge·clip 뒤지만, 그 전까지 긴급 풀은 계속 돌아 drain 시간이 항상 20초 이상 |
| 종료 시작 뒤 긴급 작업 수락 | PASS | `lateShutdown`이라 자기 destroy 전까지 받는다. 단 그 뒤에도 AI WS 수신이 열려 있다 (I-2) |
| 긴급 작업 1건 최악(`SosNotificationListener:99-130`, `AnomalyNotificationListener:86-128`) | FAIL(드묾) | 수신자 순차 × (FCM 10초 + 문자 10초) (I-3) |
| drain 중 Redis(`SosNotificationCooldown`·`AnomalyNotificationCooldown`·`SmsFallbackLimiter`) | PASS(fail-open) | Lettuce는 0 단계에서 먼저 멈추지만 세 곳 모두 예외를 잡고 발송 (I-4) |
| aiPurge 유실(`AiMemberDataPurgeListener:36-38`) | 의도 | 챗·예약·게임 삭제는 재시도 없음 수용(policy-chat·policy-game), 말벗만 매시 재시도 |
| Boot 스케줄러(Y:87-100) | PASS(주의) | 대기 설정 없음 - 단계에서 최대 15초 뒤 `shutdownNow` |

### R2 쿨다운 전 수신자 거르기

| 경로 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 카메라 끊김 | `CameraLinkLostNotifier.java:135-141, 178-195` | PASS | 기준 구현 |
| 이상감지 재촉·요약·동수 | `AnomalyReviewReminderPlanner.java:387-398` | PASS | 선점 전 `findIdsByIdInAndStatus(ACTIVE)` |
| 복약 미복용 요약 | `MedicationMissedAlertPlanner.java:149` | PASS | 같음 |
| 복약 첫·재알림 | `MedicationReminderPlanner.java:153-183` | 해당없음 | 수신자는 피보호자 본인. 피보호자는 이용 제한 불가, 탈퇴 진행이면 곧 삭제 |
| 말벗 누적·주의·질문 안내 | `CompanionRiskPatternListener:76-97`, `CompanionNoticeListener:82`, `CompanionPromptScheduler:74-104` | PASS | 선점 전 ACTIVE 필터 |
| SOS | `SosNotificationListener.java:76-88, 131-141` | PASS(동등) | 필터는 없지만 전달 0이면 쿨다운 해제(:137), TTL 10초 |
| 이상감지 발생 | `AnomalyNotificationListener.java:89, 109-119` | 의도 | 키가 수신자별이라 정지 수신자의 키가 다른 사람에게 영향 없음. NOT_SENT 유지는 주석에 명시 |
| 말벗 위험 알림 | `CompanionSessionWriter.java:164-175` | 의도 | 대화 1건당 1회 선점(policy-companion L-3). 부작용은 I-9 |

### R3 역할 변경 정리 (탈퇴와 대조)

| 데이터 | 탈퇴 | 역할 변경 | 판정 |
|---|---|---|---|
| 연결·카메라(+클립)·복약 중지·토큰·WS | 정리 | 정리(`AdminUserService.java:236-258`) | PASS |
| 말벗 BE·AI | 삭제 | 삭제(`CompanionWithdrawalListener:72, 105`) | PASS |
| AI 게임 기록 | 삭제 | 삭제(#515, `GameRecordPurgeListener:51-61`) | PASS (I-10) |
| AI 대리 상담 기록 | 연결 해제 이벤트 | 연결 정리 이벤트(`AiProxyChatLogPurgeListener:32-40`) | PASS |
| AI 본인 상담 기록 | 삭제 | 유지 | 유지 타당 - 두 역할 모두 쓰는 본인 대화, 90일 정리 |
| FCM 토큰 | 삭제 | 유지 | 유지 타당 - 기기에 묶인 값 |
| 알림 이력·설정, 문의, 등록한 약 | CASCADE | 유지 | 의도(policy-notification L-5·L-7·L-8) |
| **AI 예약 계정·키** | 삭제 | 유지 | **결정 없음** (I-8) |
| **SOS 이력·설정, 이상감지 이력** | CASCADE/SET NULL | 유지 | **결정 없음** (I-8) |

동기 AFTER_COMMIT 리스너 6개는 모두 `SyncAfterCommitListenerPropagationTest` 규칙을 지킨다. 새 리스너는 `@Async`라 대상 밖(정상).

### R4 AI 중계 자리

| 경로 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 챗 전송 | `ChatRelayService.java:114-151` | PASS | 속도 제한 → 인가 → `try(ChatSlots)` 10·1 |
| STT | `ChatSttService.java:49-76` | PASS | 사용자·전체 자리, finally |
| 복약 OCR | `MedicationOcrService.java:45-55` | PASS | 5·1 |
| 말벗 턴·마치기 | `CompanionService.java:211-301` | PASS | 소유 확인 뒤 자리, 서버 8 + 대화 1 (I-7) |
| 말벗 목소리 | `CompanionAudioService.java:50-71` | PASS | 피보호자 2·전체 8 |
| 게임 중계 | `GuardianGameService.java:42-88`, `GameRelaySlots.java:36-84` | PASS | 인가 뒤 자리, 모든 예외 경로 반납, 사용자 키 0이면 제거(누수 없음) (I-6) |
| 영상 MJPEG | `CameraStreamService.java:191-203` | PASS | 20·2 |
| **영상 목록·상태·프레임, 챗 기록, 말벗 이어 가기** | `CameraStreamService.java:68-170`, `ChatRelayService.java:153-165`, `CompanionService.java:542` | **FAIL(부분)** | 속도 제한 + 짧은 시간 제한(8·15·3초)만, 동시 상한 없음 (I-5) |

## 발견 사항

| ID | 등급 | 내용 | 위치 | 재현 조건 | 제안 |
|---|---|---|---|---|---|
| I-1 | 🟡 | 종료 예산 식이 실제 순서와 다르다 | `AsyncConfig.java:36,120-121`, Y:102-107, `ShutdownGraceBudgetTest` | 배포 순간 챗·말벗 턴 진행(웹 단계 15초) + 클립 생성(clip 20초) + 탈퇴 AI 삭제(aiPurge 20초) = 55초 > 40초 → SIGKILL. 클립·AI 삭제·접속 로그 큐·일반 알림 잔여가 잘린다 | 비긴급 풀(aiPurge·clip·liveAnalysis) 대기를 0~5초로 줄이고, 테스트를 `단계×2 + Σ풀 + Σ@PreDestroy < grace`로 바꾼다. Y 주석 정정 |
| I-2 | 🟡 | 긴급 풀을 닫은 뒤에도 AI WS 수신이 열려 있다 | `AiLiveStreamSubscriber.java:208-215` | 긴급 풀 destroy 시작 뒤 ~ 구독기 파괴 전(최대 20초)에 화재 신호 → 이력·이력 쿨다운은 남고 알림은 `[NOTIFY-REJECTED]`로 폐기. 순서는 추론(실행 미확인) | 구독기를 SmartLifecycle 높은 phase로 먼저 닫거나 `@DependsOn("urgentNotificationExecutor")`로 "수신 중단 → 긴급 drain" 순서 고정 |
| I-3 | 🟡 | 긴급 작업 1건이 20초를 넘을 수 있다 | `SosNotificationListener.java:99-130`, `AnomalyNotificationListener.java:86-128` | FCM 지연 장애 중 배포 + 수신자 3명 → 3×(10초 + 폴백) > 20초 → 뒤 수신자 실패 | 긴급 풀만 대기를 늘리고(I-1에서 줄인 몫) 수신자 발송 병렬화 검토 |
| I-5 | 🟡 | 자리 없는 짧은 AI 조회가 요청 스레드를 붙든다 | `CameraStreamService.java:68-170`, `ChatRelayService.java:153-165` | AI 무응답 시 보호자 1명이 최대 20개 남짓 스레드 점유(Tomcat 기본 200). 보호자 10여 명이 대시보드를 연 채 AI가 멈추면 SOS·로그인까지 대기. 현 규모(활성 19명)에선 닿기 어렵다 | "짧은 AI 조회" 공용 자리(전체 N·1인 2~3)를 인가 뒤에 둔다(#515 M-4와 같은 논리) |
| I-8 | 🟡 | 역할 변경 뒤 남는 옛 역할 데이터 중 결정 기록이 없는 것 | `AdminUserService.java:246-251` | 피보호자 → 보호자 변경 시 `sos_event`(위치 문구)·SOS 설정, 이상감지 이력, AI 예약 계정·평문 키가 탈퇴까지 남는다. 다른 사용자에게는 안 보임(D-1), 관리자 화면·통계엔 잡힘 | 수용이면 policy-notification L-7 목록에 적고, 아니면 `UserRoleChangedEvent`에서 지운다. **예약 계정 삭제는 실제 예약까지 지우므로 사용자 결정 필요** |
| I-4 | 🟢 | 풀 drain은 Redis가 멈춘 뒤 돈다 | SDR `LettuceConnectionFactory`, `SmsFallbackLimiter.java:83` | 종료 중 발송은 fail-open으로 나가지만 쿨다운이 기록되지 않아 재기동 직후 한 번 더 갈 수 있다. `release`는 `DataAccessException`만 잡아 STOPPED의 `IllegalStateException`은 리스너 catch까지 올라간다 | 문서화, 또는 `release` catch 범위 확대 |
| I-6 | 🟢 | 게임 중계에 전용 속도 제한 없음 | `GuardianGameService.java:41-88` | 전역 필터(분 600)만 걸린다. 인가 실패(DB 1회 + `[IDOR-ATTEMPT]` WARN)를 분 600까지 반복 가능. `GameRelayProperties`에 값 검증이 없어 0이면 전부 503 | `requireConnected` 앞에 분 30 수준 제한 + 설정값 검증 |
| I-7 | 🟢 | 말벗 테스트 계정은 대화 5개를 동시에 열 수 있다 | `CompanionService.java:156-182` | 시간대별 OPEN 1개(V69)라 전체 8자리 중 5자리까지. 허용 목록 계정 한정 | 문서화 |
| I-9 | 🟢 | 말벗 위험 알림 선점의 부작용 | `CompanionSessionWriter.java:164-175` | 받을 사람 0명이어도 `risk_notified_at`이 선다 → 누적 알림 조건에서 그날이 "이미 알린 날"로 빠지고, 보호자 화면·관리자 통계가 "알림 보냄"으로 표시 | 전달 0이면 로그, 통계는 `notification_log` 기준 또는 문서에 명시 |
| I-10 | 🟢 | 게임 삭제 뒤 재생성·늦은 삭제 | `GameRecordPurgeListener.java:51-61` | 역할 변경 때 열린 게임 화면(AI 직접)이 계속 쓰면 기록이 다시 생긴다. 보호자 → 피보호자는 aiPurge가 밀리면 첫 기록이 늦은 삭제에 지워질 수 있다(수십 초 지연이 겹쳐야 함) | 수용(탈퇴 때 다시 지워짐), 문서화 |
| I-11 | 🟢 | #515 이어 가기 경합 | `CompanionService.java:517-524, 296-322` | `isBusy` 확인 직후 마감 스케줄러가 자리를 잡으면 이어 가기 화면 뒤 다음 대답이 끝난 대화 응답을 받는다(ms 창 × 5분 주기) | 이미 수용 기록(policy-companion:78). 조치 불필요 |

## PHASE C. 테스트로 고정할 수 있는 것 (제안)

| 대상 | 제안 테스트 | 막는 것 |
|---|---|---|
| I-1 | `ShutdownGraceBudgetTest`를 `timeout-per-shutdown-phase × 2 + Σ(AsyncConfig 풀 await) + Σ(@PreDestroy 대기 상수) < stop_grace_period`로 바꾼다. 수동 풀 대기 상수는 리플렉션으로 읽는다 | 풀 추가·대기 증가로 예산을 넘기는 것 |
| I-2 | `AiLiveStreamSubscriber`가 긴급 풀보다 먼저 멈추는지 - 컨텍스트 종료 순서를 기록하는 슬라이스 테스트 또는 `@DependsOn` 존재 검사 | 수신 중단 전 긴급 풀이 닫히는 순서 |
| R3 | `RoleChangeCleanupCoverageTest`: `UserWithdrawnEvent` 리스너 목록 ⊆ (`UserRoleChangedEvent` 리스너 + 명시적 "유지" 표). 표에는 유지 이유를 적는다(`SyncAfterCommitListenerPropagationTest`와 같은 방식) | 탈퇴 정리만 추가하고 역할 변경을 빠뜨리는 것 |
| R4 | `AiRelaySlotCoverageTest`: `Ai*Client`의 사용자 대면 호출 메서드 ↔ 자리 클래스 대응 표(자리 없음은 이유와 함께) | 새 AI 중계 경로를 자리 없이 추가하는 것 |
| R2 | (기계적 고정 어려움) 쿨다운·선점 클래스 목록과 수신자 필터 위치를 정책 문서 표로 남긴다 | - |

## 사용자 결정이 필요한 것

1. **I-1·I-2·I-3 (종료 순서)** - 비긴급 풀 대기 축소 + 구독기 선종료 + 테스트 식 변경을 한 PR로 할지.
2. **I-8 (역할 변경 뒤 SOS·이상감지 이력, AI 예약 계정)** - 수용으로 문서에 적을지, 지울지. 예약 계정은 실제 예약까지 지워진다.
3. **I-5 (짧은 AI 조회 자리)** - 지금 할지, 사용자 수가 늘 때 할지.
4. 🟢 항목(I-4·I-6·I-7·I-9·I-10)은 묶어서 문서화·소규모 수정 후보.

## 미확인

- 빈 파괴 순서는 등록 규칙으로 추론했고 실행 로그로 확인하지 않았다.
- AI 서버 쪽 동작(게임 조회가 행을 만드는지, 말벗 삭제 직후 진행 중 턴 재기록).
- FE BFF가 보호자 역할에서 본인 예약 계정을 쓰는지(I-8 판단의 전제).
- 종료 중 STOMP 브로커 정지로 WS 화면 이벤트가 유실되는지.
