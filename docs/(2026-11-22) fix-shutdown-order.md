# 종료 순서 수정 - AI 수신 중단 → 알림 풀 비우기 → 나머지 (2026-11-22)

> 근거: `(2026-11-22) audit-impact-final-audit-be.md` R1 · I-1(종료 예산 식이 실제와 다름) · I-2(긴급 풀을 닫은 뒤에도 AI 수신이 열려 있음) · I-3(긴급 작업 1건이 20초를 넘을 수 있음).
> 절차: PHASE 0 전제 검증(실측) → PHASE 1 계획 승인 → 구현. 브랜치 `fix/shutdown-order`.
> 사용자 결정: grace 100초(단계 대기 15초 유지, 비우기 마감 25초) / 수신자 병렬 발송 안 함 / 수신기는 웹 정지 뒤·비우기 직전에 멈춤 / 스케줄러 빈 문제는 이 PR 밖.

## 1. 무엇이 문제였나 (PHASE 0 실측)

앱 전체 컨텍스트를 DB·Redis 없이 띄운 임시 테스트로 종료 단계와 빈 파괴 순서를 기록했다(임시 테스트는 삭제).

| 전제(문서·주석) | 실제 |
|---|---|
| grace 40초 = 단계 대기 15 + 풀 대기 20 + 여유 | 풀 5개는 빈 파괴 때 **하나씩 차례로** 기다린다(20초 × 5 = 100초). 단계 대기도 단계마다 따로 걸린다. 이론 최악 약 150초 |
| 긴급 풀이 닫히기 전에 수신이 멈춘다 | 파괴 순서가 `aiPurge → clip → urgent → AccessLog → AI 수신기 → notification → …`라 **수신기가 긴급 풀보다 늦게** 닫혔다. 그 사이 화재 신호는 이력·쿨다운만 남고 알림은 `[NOTIFY-REJECTED]`로 폐기 |
| 풀을 비우는 동안 발송 수단이 살아 있다 | Solapi·Email·DB는 살아 있었지만 **Redis는 0단계에서 먼저 멈춘다** → 쿨다운·문자 상한이 fail-open(중복 발송) |
| 스케줄러는 Boot `taskScheduler`(풀 3) | Boot 스케줄러 빈이 없다. `messageBrokerTaskScheduler` 하나뿐(풀 = CPU 수, 0단계) - 아래 "열린 항목" |

## 2. 종료 순서 (변경 후)

괄호는 그 자리에서 기다릴 수 있는 최대 시간이다. 단계 값은 `ShutdownPhases`에 있다(큰 값이 먼저).

```
SIGTERM → ContextClosedEvent        스케줄러는 새 예약 호출을 거절한다(이미 걸려 있던 cron 1회분은 0단계까지 시각이 되면 시작한다 - §7).
                                    AsyncConfig 풀 5개는 계속 받는다.
 [MAX]          영상 중계 닫기(CameraStreamSlots)
 [MAX - 1024]   웹 우아한 종료 - 진행 중 요청 대기                 (단계 대기 15초)
 [MAX - 2048]   웹 서버 정지                                       (Tomcat 5초 + 뒤에 unload 2초)
 [MAX/2 + 1024] AI 화재 수신 중단 - AiLiveStreamSubscriber.stop()   (처리 중이던 프레임 대기 3초 - 보강)  ← 신규
 [MAX/2 + 512]  긴급·일반 알림 풀 비우기                            (공유 마감 25초)  ← 신규
 [MAX/2]        AsyncConfig 풀 5개 - 아무것도 하지 않는다
 [0]            WebSocket 브로커 · 스케줄러(진행 중 예약 작업) · Redis 정지   (단계 대기 15초)
 빈 파괴(한 스레드, 차례로)
   aiPurge(2) → clip(2) → urgent(3) → AccessLog(5) → AI 수신기(이미 멈춤) → notification(3)
   → liveAnalysis(1) → WebSocket 실행기·스케줄러 → Solapi(5) → Email(5) → Redis → JPA → DataSource
```

- **수신 중단을 웹 정지 뒤에 둔 이유**: 웹 단계(최대 22초) 동안 들어온 화재 신호는 그대로 받아 처리한다. 가장 먼저 멈추면 그 구간의 신호는 새 컨테이너가 다시 감지할 때까지 늦어진다.
- **비우기를 0단계 앞에 둔 이유**: Redis·DB·FCM·Solapi·메일이 모두 살아 있는 동안 발송을 끝낸다.
- **비우기는 풀을 닫지 않는다**: 실행 중·대기 중 작업이 0이 되기를(연속 2회 확인) 기다릴 뿐이다. 그 뒤에 들어온 작업도 빈 파괴 전까지 받고, 파괴 때 잔여 대기(긴급 3초)가 맡는다.
- **수신 중단은 처리 중이던 프레임을 기다린다**(같은 날 보강, 점검 S-1): 멈출 때 이미 처리에 들어간 프레임이 끝나기를 3초까지 기다린 뒤 연결을 닫는다. 프레임 처리가 끝나면 이력 커밋과 긴급 알림 제출도 끝나 있어 비우기가 그 알림을 기다린다. 처리 중인 프레임이 없으면 기다리지 않는다. 상세 `(2026-11-22) fix-shutdown-order-hardening.md`.
- **정정**(점검 S-2): 처음에는 위 그림에 "스케줄러는 새 예약을 받지 않는다"고만 적었다. 새 예약 호출이 거절되는 것은 맞지만, 종료 전에 이미 걸려 있던 cron 작업은 0단계에서 스케줄러가 멈출 때까지 시각이 되면 그대로 시작한다(§7).

## 3. 변경 파일

| 파일 | 내용 |
|---|---|
| `global/config/ShutdownPhases.java` (신규) | 수신 중단·비우기 단계 값과 전체 순서 설명 |
| `global/config/UrgentNotificationDrainLifecycle.java` (신규) | 종료 단계에서 긴급·일반 풀이 빌 때까지 대기(공유 마감 25초). 실행기는 이름으로 조회(의존 등록 금지), 일시정지 대상 아님 |
| `domain/anomaly/client/AiLiveStreamSubscriber.java` | `SmartLifecycle` 구현. 접속 리스너 `start()` → `connectOnReady()`로 이름 변경, lifecycle `start()`는 표시만. `stop()` 뒤 메시지·뒤늦은 연결을 받지 않음. `@PreDestroy`는 보험으로 유지 |
| `global/config/AsyncConfig.java` | 공용 20초 → 풀별 잔여 대기(긴급 3 · 일반 3 · 클립 2 · AI 삭제 2 · 실시간 분석 1) |
| `application.yaml` | 종료 예산 주석 정정(값은 15s 그대로) |
| `docker-compose.dev.yml` · `docker-compose.prod.yml` | `stop_grace_period` 40s → 100s (prod는 파일만, 재기동 없음) |
| `.claude/rules/domain-security-policy.md` | B-3 갱신, B-1에 열린 항목 기록 |
| 테스트 | `ShutdownGraceBudgetTest`(식 변경) · `ShutdownOrderTest`(신규) · `UrgentNotificationDrainLifecycleTest`(신규) · `AiLiveStreamSubscriberTest`(+3) · `AsyncConfigTest`(대기 값 + 풀별 1건) |

## 4. 종료 시간 예산

| 항목 | 변경 전(이론 최악) | 변경 후 |
|---|---|---|
| 웹 우아한 종료 단계 | 15 | 15 |
| Tomcat 정지(요청 스레드 종료 대기 5 + unload 2) | 7 (미계상) | 7 |
| AI 수신 중단(처리 중이던 프레임 대기 - 같은 날 보강) | - | 3 |
| 알림 풀 비우기 | - | 25 |
| 0단계(진행 중 예약 작업·WebSocket 실행기) | 15 | 15 |
| 풀 파괴 대기 합 | 100 | 11 |
| 수동 풀 `@PreDestroy` 합(Solapi·Email·AccessLog) | 15 | 15 |
| **합** | **152** | **91** (PR #518 시점 88 + 보강 3) |
| `stop_grace_period` | 40 | **100** |

- grace는 상한이다. 평소 종료 시간은 그대로다(부하 없을 때 단계 전체 약 0.1초 실측).
- 단계 대기만 내려서는 40초에 맞출 수 없다(단계 대기 0이어도 58초). 내리면 10~15초 걸리는 요청이 배포마다 추가로 잘린다.
- Tomcat 7초는 소스로 확인한 기본값이다(`executorTerminationTimeoutMillis` 5000, `unloadDelay` 2000). 단계 대기를 넘긴 요청이 있을 때만 걸린다.

## 5. 바뀌지 않는 것

알림 정책(종류별 발송 정책·정지 계정 차단), 포화 시 폐기 + 로그(CallerRuns 금지), 풀 크기·큐, 리스너-풀 배정(`NotificationExecutorAssignmentTest`), 쿨다운 fail-open, 수신자 순차 발송, 수동 풀 5초 상수, 스케줄러 빈 구성, `timeout-per-shutdown-phase` 15초.

## 6. 테스트

`./gradlew build --no-daemon` → **BUILD SUCCESSFUL**: 373개 클래스 · 2,923건 · 실패 0 · 오류 0 · 건너뜀 2(기존 `@Disabled` - `BackendApplicationTests`·`EmailNotificationHtmlTest`).
통합 테스트(`integrationTest`, Docker 필요)는 로컬에서 돌리지 못했다 - 머지 전 vkcs에서 `tools/integration-test.sh fix/shutdown-order`.

| 테스트 | 고정하는 것 |
|---|---|
| `ShutdownOrderTest` (5) | 단계 값의 순서 / 두 빈의 단계·자동 시작·일시정지 제외 / 컨텍스트 종료 순서(웹 → 수신 중단 → 비우기 → 0단계) / 비우기가 0단계 전에 긴급 작업을 끝내고 그 뒤에도 풀이 받는 것 / 비우기 빈이 실행기에 의존하지 않는 것 |
| `UrgentNotificationDrainLifecycleTest` (5) | 빈 풀은 바로 끝남 / 실행 중·큐 작업을 기다림 / 마감을 넘기면 돌아오되 풀은 열어 둠 / 풀이 없어도 종료를 막지 않음 / 비울 풀 이름 |
| `ShutdownGraceBudgetTest` (2) | 대기 합 < grace(dev·prod) / `@PreDestroy`에서 풀을 기다리는 클래스가 예산 목록과 같음 |
| `AiLiveStreamSubscriberTest` (+3) | stop 뒤 메시지 미처리·재접속 미예약 / 뒤늦게 성립한 연결 닫기 / lifecycle `start()`는 접속하지 않고 접속 리스너는 `connectOnReady` |
| `AsyncConfigTest` (+1, 2건 수정) | 풀별 잔여 대기 값 |

**변이 확인**(구현을 일부러 되돌려 새 테스트가 실패하는지 본 뒤 원복, 원복 뒤 변경 내용 해시 일치 확인):

| 변이 | 실패한 테스트 |
|---|---|
| 수신기 단계를 비우기보다 낮춤 | `ShutdownOrderTest` 3건(단계 값·종료 순서·0단계 전 완료) |
| 풀 대기를 공용 20초로 되돌림 | `ShutdownGraceBudgetTest` 합 · `AsyncConfigTest` 3건 |
| stop 뒤 메시지 차단 제거 | `AiLiveStreamSubscriberTest` 1건 |
| `@PreDestroy` + `awaitTermination` 클래스가 하나 더 생김(흉내) | `ShutdownGraceBudgetTest` 목록 검사 |
| 비우기에서 대기를 뺌 | `UrgentNotificationDrainLifecycleTest` 2건 · `ShutdownOrderTest` 1건 |
| dev `stop_grace_period`를 40초로 | `ShutdownGraceBudgetTest` 합 |
| stop이 수신을 막지 않음(`shuttingDown` 미설정) | `AiLiveStreamSubscriberTest` 2건 |
| 수신기를 일시정지 대상으로 | `ShutdownOrderTest` 1건 |
| 단계 대기 30초 / 비우기 마감 60초 / Solapi 대기 30초(각각 따로) | `ShutdownGraceBudgetTest` 합(세 번 모두) |

## 7. 수용한 한계 · 열린 항목

- **긴급 작업 1건이 25초를 넘는 경우**: 수신자 1명에 FCM 13초 + 문자 폴백 10초(=23초)까지는 덮는다. 수신자가 여럿이고 발송사가 모두 느리면 넘는다(수신자 순차 발송 유지). 넘은 작업은 컨텍스트가 닫힌 뒤에도 끝날 때까지 돌지만(풀 스레드는 데몬이 아니다) Redis·DB·Solapi 풀이 닫힌 뒤라 대부분 실패로 끝난다. 최종 상한은 grace(100초)다.
- **진행 중이던 예약 작업이 낸 알림**: 말벗 멈춘 대화 마감(5분 주기, AI 호출 최대 90초)이 비우기 뒤에 위험 알림을 내면 잔여 3초 안에서만 처리된다. 스케줄러가 0단계라 비우기보다 늦게 멈추기 때문이다.
- **종료 구간에 시각이 된 cron 작업 (점검 S-2, PR 이전부터)**: 종료가 시작되면 스케줄러는 새 예약 호출을 거절하지만, 이미 걸려 있던 cron 1회분은 0단계에서 스케줄러가 멈출 때까지 시각이 되면 시작한다(`ThreadPoolTaskScheduler`의 `executeExistingDelayedTasksAfterShutdownPolicy` 기본값 true, 프로브로 실행 확인). 웹 종료·수신 중단·비우기 구간(최대 약 50초)에 정각이나 5분 주기가 걸리면 말벗 마감·질문 안내 같은 작업이 그때 시작된다. Redis도 0단계에서 멈추므로 그 작업은 도중에 Redis 없이 돌 수 있고(쿨다운·문자 상한 fail-open), 비우기 뒤에 낸 알림은 잔여 대기에만 기댄다. 0단계 안에서 Redis가 스케줄러 작업보다 먼저 멈춘다는 부분은 소스 추론이다. 스케줄러 빈을 따로 두는 작업(아래 열린 항목)에서 함께 다룬다.
- **처리 중이던 프레임이 3초를 넘는 경우 (점검 S-1 보강 뒤)**: 수신 중단은 3초까지만 기다린다. Redis 명령 제한 2초 뒤 fail-open으로 저장하는 경우까지는 덮고, 커넥션 풀 고갈(획득 제한 10초)이나 위험 종류 여러 개가 모두 느린 경우는 덮지 못한다. 넘긴 프레임의 알림은 보강 전처럼 풀 잔여 대기(긴급 3초)에 기댄다.
- **열린 항목 - 스케줄러 풀 설정 미적용**: `spring.task.scheduling.pool.size: 3`(정책 B-1)이 실제로는 적용되지 않는다. `@EnableWebSocketMessageBroker`의 `messageBrokerTaskScheduler`가 유일한 `TaskScheduler` 빈이라 Boot 기본 스케줄러가 물러난다. `@Scheduled` 전체와 AI WS 재접속이 그 풀(크기 = CPU 수, 스레드명 `MessageBroker-`)에서 돈다. 따로 다룬다 - 스케줄러를 따로 두면 비우기 단계를 그보다 낮출 것(`ShutdownPhases` 주석).
- **열린 항목 - `SmsFallbackLimiter.release`**: `DataAccessException`만 잡는다. Redis가 멈춘 뒤에는 `IllegalStateException`이 `dispatch()` 밖으로 나가 그 수신자의 알림 이력 기록이 빠진다(소스 추론). 비우기를 0단계 앞으로 옮겨 대부분 사라졌고, 잔여 대기 구간에만 남는다.
- **열린 항목 - 통합 테스트의 스케줄러 끄기**: `FullContextIntegrationTest.SchedulingOff`가 지우는 빈 이름이 Spring 7의 실제 이름(`org.springframework.scheduling.config.internalScheduledAnnotationProcessor`)과 다르다. 동작하지 않을 가능성이 있다(실행 미확인).
- **미확인**: compose가 옛 컨테이너를 내릴 때 새 grace를 쓰는지(두 번째 배포부터는 확실하다).

## 8. gosky 실측

2026-11-22, PR #518 머지 `b228093d`. vkcs CD success(통합 테스트 포함), gosky `up -d --build api` 후 새 코드 컨테이너를 `docker stop`으로 한 번 더 멈춰 종료 로그를 봤다(부하 없음, AI WS 연결 상태).

| 항목 | 결과 |
|---|---|
| 컨테이너 `StopTimeout` | 100 (새 `stop_grace_period` 적용) |
| `docker stop` 소요 | 1.9초 |
| 종료 순서(로그 시각) | `Commencing graceful shutdown` 45.350 → `Graceful shutdown complete` 45.354 → AI WS 연결 종료(code=1000) 45.357 → `[SHUTDOWN-DRAIN] 알림 풀 비움: waitedMs=50` 45.408 → `HikariPool-1 - Shutdown` 45.432 |
| 재기동 | `Started BackendApplication` 8.8초, `AI WS 연결됨` |
| vkcs | `b228093d`, `dmu-dev-api` healthy |

계획한 순서 "웹 정지 → 화재 수신 중단 → 알림 풀 비우기 → DB 종료"가 실제 로그와 같다. 진행 중 요청·알림이 있을 때의 최악 시간은 실측하지 않았다(예산 테스트로 상한만 고정).
