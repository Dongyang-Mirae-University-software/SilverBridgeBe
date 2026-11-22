# 종료 순서 보강 - 처리 중 프레임 대기·테스트 고정 (2026-11-22)

> 근거: `(2026-11-22) audit-shutdown-order.md`의 S-1·S-6·T-1~T-6, S-2(문서 정정). 선행 작업: `(2026-11-22) fix-shutdown-order.md`(PR #518).
> 절차: PHASE 0 전제 검증 → PHASE 1 계획 승인 → 구현. 브랜치 `fix/shutdown-order-hardening`.
> 사용자 결정: 프레임 대기 3초 / "연속 2회 확인" 테스트는 대역 풀 방식 / 접속 실패 로그는 원인 클래스명 + 상태 코드 3자리.

## 1. 범위

| ID | 내용 | 처리 |
|---|---|---|
| S-1 | 수신 중단이 처리 중이던 화재 프레임을 기다리지 않음 | 3초까지 기다린 뒤 닫는다 |
| T-1 | 비우기 기본 마감을 고정하는 테스트 없음 | 기본 생성자 마감 = 상수 단언 |
| T-2 | "연속 2회 확인" 무테스트 | 대역 풀 각본 테스트 |
| T-3 | `@PreDestroy` 대기 검사가 좁음 | 종료 훅 클래스 전수(18개)를 대기 초와 함께 목록으로 고정 |
| T-4 | 새·변경 파일이 로그 가드에 없음 | 수신기의 예외 메시지 로그 4곳을 걷어내고 3개 파일 등록 |
| T-5 | 마감 초과 WARN·인터럽트 무테스트 | 테스트 2건 |
| T-6 | 정상 종료도 WARN | 종료 절차의 정상 종료(code=1000)만 INFO |
| S-6 | 수신기 의존 배선 미고정 | 생성자·필드 타입을 보는 테스트 |
| S-2 | 기록 문서의 "새 예약을 받지 않는다"가 실제와 다름 | 문서 정정(코드 변경 없음) |

범위 밖(그대로): S-2의 스케줄러 빈 분리, S-3·S-4·S-5, 수신자 병렬 발송.

## 2. 변경 파일

| 파일 | 내용 |
|---|---|
| `domain/anomaly/client/AiLiveStreamSubscriber.java` | 처리 중 프레임 수(`inFlightFrames`)와 `stop()`의 대기(상수 `IN_FLIGHT_WAIT_SECONDS = 3`) / 예외 메시지 로그 4곳 교체 / 종료 절차의 정상 종료 로그를 INFO로 |
| `global/config/ShutdownPhases.java` | 주석만 - 수신 중단의 대기와 "수신기에 먼저 멈추는 빈을 주입하지 말 것" |
| `AiLiveStreamSubscriberTest` (+7) | 프레임 대기 4건 · 연결 종료 로그 레벨 · 전송 실패 로그 · 접속 실패 요약 |
| `UrgentNotificationDrainLifecycleTest` (+4) | 기본 마감 · 연속 2회 확인 · 마감 초과 WARN · 인터럽트 |
| `ShutdownGraceBudgetTest` (2 → 3) | 예산 식에 수신 중단 대기 가산 / 종료 훅 전수 목록 / 0초 선언 훅의 본문 검사 |
| `ShutdownOrderTest` (+1) | 수신기가 Lifecycle 빈을 주입받지 않음 |
| `LogRawExceptionGuardTest` | `UrgentNotificationDrainLifecycle`·`AsyncConfig`·`AiLiveStreamSubscriber` 등록 |
| `.claude/rules/domain-security-policy.md` | B-3(대기 3초·예산 91초·종료 훅 목록 규칙), 로그 문단(수신기 예외 범위 축소) |
| `docs/(2026-11-22) fix-shutdown-order.md` | §2 정정·보강, §4 예산, §7 한계(S-2·3초 초과) |

운영 코드 변경은 `AiLiveStreamSubscriber` 한 파일이다(`ShutdownPhases`는 주석). `UrgentNotificationDrainLifecycle`·`AsyncConfig`는 바꾸지 않았다.

## 3. 수신 중단의 프레임 대기 (S-1)

**문제**: 위험 프레임이 종료 확인을 지난 직후 `stop()`이 불리면, 그 프레임의 이력 커밋이 다음 단계(알림 풀 비우기, 빠르면 50ms)보다 늦게 끝날 수 있다. 그러면 알림은 비우기가 끝난 뒤 풀에 들어가 잔여 대기(3초)에만 기댄다.

**변경 후 `stop()`**: 종료 표시 → 재동기화 취소 → 처리 중이던 프레임이 0이 될 때까지 대기(10ms 간격, 상한 3초) → 연결 닫기.

- 프레임 처리가 끝났다는 것은 이력 커밋과 AFTER_COMMIT 리스너의 긴급 알림 제출까지 끝났다는 뜻이다(같은 스레드). 그래서 그 알림은 비우기가 시작되기 전에 풀에 있다.
- 처리 중인 프레임이 없으면 기다리지 않는다(평소 배포 +0ms, 로그 없음).
- `stop()`은 종료 단계와 `@PreDestroy`에서 두 번 불린다. 대기는 첫 호출에서만 한다(두 번 기다리면 예산이 두 배).
- 상한을 넘기면 `[SHUTDOWN-AI-STREAM]` WARN(대기 ms·상한·처리 중 개수만)을 남기고 닫는다. 기다려서 끝났으면 INFO.
- **순서 주의**: `handleTextMessage`는 프레임 수를 **먼저 올리고** 종료 표시를 확인한다. 반대로 하면 "확인 통과 → stop이 0을 보고 지나감 → 처리 시작"이 가능해 대기가 무의미해진다.

**3초의 근거**(코드 기준, 실측 아님):

| 구간 | 상한 | 근거 |
|---|---|---|
| 파싱 | CPU만 | 메시지 1MB 이하 |
| 이력 쿨다운(Redis) | 2초 뒤 fail-open | `spring.data.redis.timeout` 기본 2초, `AnomalyEventCooldown` |
| SQL 4~6문 + 커밋 | 평소 ms(제한 설정 없음) | `AnomalyDetectionService.record` |
| 커넥션 획득 | 10초(풀 고갈 시) | `hikari.connection-timeout` |
| 알림·클립 제출 | 비차단 | `@Async` 리스너 2개 |

프레임 경로에 AI·HTTP 호출은 없다. 3초는 "Redis가 느려 2초 뒤 fail-open으로 저장한 프레임"까지 덮고, 커넥션 풀 고갈이나 위험 종류 여러 개가 모두 느린 경우는 덮지 못한다(수용 - 그때는 보강 전과 같다).

## 4. 로그 변경 (T-4·T-6)

| 위치 | 전 | 후 |
|---|---|---|
| 접속 실패 (WARN) | `AI WS 접속 실패: {예외 메시지}` | `AI WS 접속 실패: error={가장 안쪽 원인 클래스명}, status={3자리 또는 -}` |
| 전송 오류 (WARN) | `AI WS 전송 오류: {예외 메시지}` | `AI WS 전송 오류: error={클래스명}` |
| 전송 실패 (WARN) | `AI WS 전송 실패: payload={보낸 내용}, error={예외 메시지}` | `AI WS 전송 실패: action={list 또는 subscribe}, error={클래스명}` |
| 종료 중 닫기 오류 (DEBUG) | `AI WS 종료 중 오류(무시): {예외 메시지}` | 같은 문구 + 클래스명 |
| 연결 종료 | 항상 WARN | 종료 절차에서 code=1000이면 INFO, 그 밖은 WARN(문구 동일) |
| 메시지 처리 실패 (ERROR, 스택) | - | 그대로(정책상 의도적 유지) |

- 문구 앞부분은 모두 그대로라 기존 검색어는 계속 맞는다. 저장소 안(`docs/`·`.claude/`·`tools/`·`.github/`·테스트)에서 바뀐 뒷부분을 검색에 쓰는 곳은 없었다.
- `status`는 핸드셰이크가 HTTP 응답으로 거절됐을 때 컨테이너 메시지의 `[401]` 같은 대괄호 안 3자리(100~599)만 꺼낸 값이다. 메시지의 나머지(주소 등)는 버린다. 서버가 죽은 것(`ConnectException`, `status=-`)과 API 키가 틀린 것(`DeploymentException`, `status=401`)을 가를 수 있다.
- 접속 실패 예외는 `CompletableFuture` 래퍼에 싸여 와서 겉 클래스명은 늘 같다. 그래서 가장 안쪽 원인을 남긴다.
- `ShutdownPhases`는 상수만 있고 로그가 없어 가드에 등록하지 않았다.

## 5. 종료 훅 목록 (T-3)

`ShutdownGraceBudgetTest`가 `src/main`을 훑어 종료 훅(`@PreDestroy`·`Lifecycle`·`DisposableBean`·`destroyMethod`·최상위 `AutoCloseable`·`ContextClosedEvent`)을 가진 클래스를 모두 찾고, 아래 목록과 같은지 본다. 새 훅은 목록에 넣기 전까지 실패한다.

| 대기 | 클래스 |
|---|---|
| 25초 | `UrgentNotificationDrainLifecycle` |
| 3초 | `AiLiveStreamSubscriber` |
| 5초씩 | `SolapiCallExecutor` · `EmailSendExecutor` · `AccessLogService` |
| 0초 (13개) | `CameraStreamSlots` · `LiveAnalysisBroadcaster` · `AiClipClient` · `AiStreamClient` · `AiChatClient` · `AiSttClient` · `AiCompanionClient` · `CompanionAiStatusClient` · `AiGameClient` · `AiMedicationOcrClient` · `ReservationAdminClient` · `AiServerHealthClient` · `AdminAiStatusService` |

- 대기 값은 각 클래스의 상수에서 읽어 예산에 더한다.
- 0초 목록은 훅 메서드 본문에 `awaitTermination`·`.close()`·`.join(`·`.get(`·`sleep(`·`.await(`·`invokeAll`·`awaitQuiescence`가 없는지도 본다.
- helper 메서드를 거친 대기는 문자열로 잡지 못한다. 그 경우의 방어선은 "새 훅은 사람이 초를 적어야 통과한다"는 절차다.
- `@Bean`으로 만드는 `ThreadPoolTaskExecutor`는 지금처럼 `AsyncConfig` 풀 합으로 따로 센다. 다른 설정 클래스에서 실행기·스케줄러 빈을 새로 만들면 이 스캔에는 걸리지 않으므로 예산에 직접 넣어야 한다.

## 6. 종료 시간 예산

| 항목 | PR #518 | 보강 후 |
|---|---|---|
| 단계 대기 15 × 2 | 30 | 30 |
| Tomcat 정지 | 7 | 7 |
| AI 수신 중단(처리 중 프레임 대기) | 0 | **3** |
| 알림 풀 비우기 | 25 | 25 |
| 풀 잔여 대기 합 | 11 | 11 |
| 수동 풀 합(Solapi·Email·AccessLog) | 15 | 15 |
| **합** | 88 | **91** |
| `stop_grace_period` | 100 | 100 |

## 7. 바뀌지 않는 것

알림 정책·채널(종류별 발송, 정지 계정 차단), 포화 시 폐기 + 로그(CallerRuns 금지), 리스너-풀 배정, 풀 크기·큐·풀별 잔여 대기, 쿨다운 fail-open, 수신자 순차 발송, `ShutdownPhases` 값, 비우기 마감 25초와 그 구현, `timeout-per-shutdown-phase` 15초, `stop_grace_period` 100초, 스케줄러 빈 구성, compose·`application.yaml`, 마이그레이션.

## 8. 테스트

- `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**: 375개 클래스 · 2,945건 · 실패 0 · 오류 0 · 건너뜀 2(PR #518 때와 같은 수).
- 관련 6개 클래스(65건: `AiLiveStreamSubscriberTest` 33 · `UrgentNotificationDrainLifecycleTest` 9 · `ShutdownOrderTest` 6 · `ShutdownGraceBudgetTest` 3 · `LogRawExceptionGuardTest` 4 · `AsyncConfigTest` 10)는 `cleanTest --no-build-cache`로 3회 연속 통과.
- 전체 빌드는 두 번 돌렸다. 첫 번째에서 이번 변경과 무관한 `AiMedicationOcrClientTest` "호출 전체 시간 제한을 넘기면 504"(300ms 제한을 재는 타이밍 테스트) 1건이 실패했다(기대 `MEDICATION_OCR_TIMEOUT`, 실제 `MEDICATION_OCR_UNAVAILABLE`). 그 클래스만 3회 다시 돌려 모두 통과했고, 두 번째 전체 빌드는 실패 0이다. 부하에 민감한 기존 테스트로 보이며 이번 범위에서 고치지 않았다.
- 통합 테스트(`integrationTest`)는 돌리지 않았다(로컬에 Docker 없음, 마이그레이션·JPQL 변경 없음).

| 테스트 | 고정하는 것 |
|---|---|
| `AiLiveStreamSubscriberTest` 처리_중이던_프레임이_끝날_때까지_stop이_기다린다 | 프레임이 끝나기 전에는 stop이 돌아오지 않고, 닫는 시점에 처리가 끝나 있음 |
| 〃 마감을_넘기면_닫고_다시_기다리지_않는다 | 상한 뒤 닫음 · WARN 1건 · 두 번째 stop은 안 기다림 |
| 〃 처리_중_프레임이_없으면_기다리지_않는다 | 끝난 프레임(실패 포함)은 세지 않음 |
| 〃 처리_중_프레임_대기_기본값 | 기본 대기 = 상수, 상수 > 2초 |
| 〃 연결_종료_로그_레벨 | 종료 절차의 code=1000만 INFO |
| 〃 전송_실패_로그에_원문을_남기지_않는다 · 접속_실패_요약 | 로그에 메시지·보낸 내용 없음, 상태 코드 3자리만 |
| `UrgentNotificationDrainLifecycleTest` 기본_생성자의_마감은_상수와_같다 | 운영 생성자의 마감 |
| 〃 연속_두_번_비어야_끝난다 | 대역 풀이 0→1→0→1→0→0으로 답할 때 끝까지 확인 |
| 〃 마감_초과는_WARN으로_남는다 · 인터럽트가_걸리면_표시를_되살리고_돌아온다 | 마감 초과 로그 · 인터럽트 경로 |
| `ShutdownGraceBudgetTest` (3) | 합 < grace / 종료 훅 전수 = 목록 / 0초 선언 훅 본문 |
| `ShutdownOrderTest` 수신기는_먼저_멈추는_Lifecycle_빈에_의존하지_않는다 | 생성자·필드 타입에 Lifecycle 없음, 프레임워크 타입은 확인한 것만 |

**변이 확인**(구현을 일부러 되돌려 실패를 본 뒤 원복, 원복 뒤 파일 해시 일치 확인):

| 변이 | 실패한 테스트 |
|---|---|
| `REQUIRED_IDLE_CHECKS` 2 → 1 | `UrgentNotificationDrainLifecycleTest` 연속_두_번_비어야_끝난다 |
| 바쁨을 보고도 확인 횟수를 지우지 않음 | 〃 연속_두_번_비어야_끝난다 |
| 비우기 기본 생성자 마감 0초 | 〃 기본_생성자의_마감은_상수와_같다 (`ShutdownOrderTest`는 이 변이에서도 통과한다) |
| 비우기: 인터럽트 표시를 되살리지 않음 | 〃 인터럽트가_걸리면_표시를_되살리고_돌아온다 |
| 비우기: 마감 초과 로그를 INFO로 | 〃 마감_초과는_WARN으로_남는다 |
| 수신기: 프레임 대기 제거 | `AiLiveStreamSubscriberTest` 처리_중이던_프레임이_끝날_때까지_stop이_기다린다 · 마감을_넘기면_닫고_다시_기다리지_않는다 |
| 수신기: 두 번째 stop도 기다림 | 〃 마감을_넘기면_닫고_다시_기다리지_않는다 |
| 수신기: 끝난 프레임 수를 내리지 않음 | 〃 처리_중_프레임이_없으면_기다리지_않는다 |
| 수신기: 기본 대기 0 | 〃 처리_중_프레임_대기_기본값 · 처리_중이던_프레임이_끝날_때까지_stop이_기다린다 |
| `IN_FLIGHT_WAIT_SECONDS` 3 → 30 | `ShutdownGraceBudgetTest` 종료_대기_합이_컨테이너_grace보다_짧다 |
| 0초로 선언한 클래스(`AiGameClient`)의 훅에 `awaitTermination` 추가 | 〃 기다리지_않는다고_선언한_훅은_실제로_기다리지_않는다 |
| 새 `@PreDestroy` 클래스 추가 | 〃 종료_훅_클래스는_빠짐없이_선언돼_있다 |
| 전송 오류 로그에 `getMessage()` 재도입 | `LogRawExceptionGuardTest` guarded_files_do_not_log_exception_messages · `AiLiveStreamSubscriberTest` 전송_실패_로그에_원문을_남기지_않는다 |
| 종료 절차의 정상 종료 로그를 WARN으로 | `AiLiveStreamSubscriberTest` 연결_종료_로그_레벨 |
| 수신기 생성자에 `CameraStreamSlots` 추가(호출부 4곳도 맞춤) | `ShutdownOrderTest` 수신기는_먼저_멈추는_Lifecycle_빈에_의존하지_않는다 |
| 수신기에 `CameraStreamSlots` 필드 주입 | 〃 같은 테스트 (+ 컨텍스트를 띄우는 2건은 그 빈이 없어 함께 실패) |

16건 모두 의도한 테스트가 실패했다. 변이마다 원본을 바이트 그대로 되돌리고 SHA-256으로 확인했다(불일치 0건, 파일 모드 변화 없음).

## 9. 수용한 한계 · 열린 항목

- **증가/확인 순서**: `handleTextMessage`에서 프레임 수 증가를 종료 확인 뒤로 옮기는 변이는 단위 테스트로 결정적으로 재현되지 않는다. 주석과 정책 문서로 고정했다.
- **3초를 넘는 프레임**: 보강 전과 같은 동작이다(알림은 풀 잔여 대기 3초에 기댄다).
- **예산 밖의 기존 대기**: 수신기가 연결을 닫을 때의 차단 전송 제한(Tomcat 기본 20초, 점검 S-4)과 DB 풀 종료 때 활성 커넥션 정리(HikariCP 최대 10초)는 예산에 없다. 둘 다 상대가 멈춰 있을 때만 걸린다. 여유는 100 − 91 = 9초다.
- **범위 밖 관찰(실행 미확인)**: 종료가 시작되면 스케줄러가 새 예약을 거절하므로, 웹 종료 구간(최대 22초)에 AI WS가 끊기면 재접속 예약이 실패해 그 구간의 신호를 받지 못할 수 있다. PR 이전부터 그렇다. 스케줄러 빈 분리와 함께 볼 항목이다.
- **S-2**(종료 구간에 시각이 된 cron): 문서만 고쳤다. 조치는 스케줄러 빈 분리 작업에서 한다.

## 10. gosky 배포 확인

2026-11-22, PR #519 머지 `9906f152`. vkcs CD success(통합 테스트 포함). gosky는 `build api` 뒤 옛(#518) 컨테이너를 멈추고 `up -d api`. 그 뒤 새 코드 컨테이너를 한 번 더 `docker stop`해 새 종료 로그를 봤다(부하 없음).

| 항목 | 결과 |
|---|---|
| 배포 커밋 · 컨테이너 | gosky·vkcs 모두 `9906f152`, `dmu-dev-api` healthy |
| 종료 로그 순서(웹 종료 → AI WS 연결 종료 → `[SHUTDOWN-DRAIN]`) | 16:26:09.550 → .553 → .603(`waitedMs=50`) → .627 Hikari 종료. `docker stop` 1.7초 |
| `AI WS 연결 종료: code=1000` 레벨(INFO여야 함) | INFO (옛 컨테이너 종료 때는 WARN - #518 코드) |
| `[SHUTDOWN-AI-STREAM]` 로그(처리 중 프레임이 없으면 없음) | 없음(처리 중 프레임 없음) |
| 재기동 뒤 `AI WS 연결됨` | 16:26:21 기동 9초, 연결됨 |
