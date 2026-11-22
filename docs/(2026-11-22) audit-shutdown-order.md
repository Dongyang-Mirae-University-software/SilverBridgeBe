# 기능 점검(B) - 배포 종료 순서 BE #518

- 일자: 2026-11-22 · 템플릿 B · 점검만(코드 변경 없음)
- 대상: BE #518 (머지 `b228093d`) - `ShutdownPhases` · `UrgentNotificationDrainLifecycle` · `AiLiveStreamSubscriber`(lifecycle) · `AsyncConfig` 풀별 대기 · `stop_grace_period` 100s
- 기준 코드: `origin/dev` `a0c17d13`(별도 worktree)
- 조사: PHASE A fable xhigh(Spring 7.0.9·spring-data-redis 4.0.7·Tomcat 11.0.26 소스 정독 + 독립 프로브 1회) · PHASE B~E sonnet(예산 대조·로그·테스트 3회 반복·변이 4종·서버 읽기)
- 근거 문서: `(2026-11-22) fix-shutdown-order.md` · `(2026-11-22) audit-impact-final-audit-be.md`

## 결론

- **PASS · 🔴 0 · 🟠 0 · 🟡 6 · 🟢 6**
- 종료 단계 순서, 단계 대기, 수신기 가드, 예산 숫자는 설계대로이고 문서와 코드가 일치한다.
- 남은 것은 좁은 창 2건(S-1 처리 중이던 화재 프레임, S-2 종료 구간에 시작하는 cron)과 테스트 보강 4건(T-1~T-4)이다.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 기준 | `origin/dev` `a0c17d13`, `b228093d` 포함 |
| 빌드 | `./gradlew build --no-daemon` 통과 - 2,932건, 실패 0, 건너뜀 2(기존 `@Disabled`) |
| 관련 테스트 5종 | `cleanTest --no-build-cache`로 3회 반복 - 51건 모두 통과 |

## 종료 단계 표

| phase | 빈 | 하는 일 |
|---|---|---|
| MAX | `cameraStreamSlots` 등 | 열린 영상 중계 닫기 |
| MAX-1024 | `webServerGracefulShutdown` | 진행 중 HTTP 요청 대기(최대 15초) |
| MAX-2048 | `webServerStartStop` | Tomcat 정지 |
| DEFAULT_PHASE+1024 | `AiLiveStreamSubscriber` | AI 화재 수신 중단(동기) |
| DEFAULT_PHASE+512 | `UrgentNotificationDrainLifecycle` | 긴급·일반 알림 풀 비우기(동기, 최대 25초) |
| MAX/2 | AsyncConfig 풀 5개 | no-op(`lateShutdown`) |
| 0 | WS 브로커·`messageBrokerTaskScheduler`·`redisConnectionFactory` | 진행 중 예약 작업 대기(최대 15초), Redis 정지 |
| 파괴 | 풀 잔여 대기 · 수동 풀 `@PreDestroy` · DB | 3+3+2+2+1초, 5×3초 |

- 단계 대기(15초)는 동기 `stop()`을 자르지 않는다(`DefaultLifecycleProcessor:480,653-654`, 프로브로 확인). 그래서 25초 비우기가 의미 있고, 예산식의 "15 × 2"(웹 단계 + 0단계)도 맞다.

## 예산 대조

| 항목 | 값 | 위치 | 판정 |
|---|---|---|---|
| 단계 대기 | 15s × 2 | `application.yaml:110` | PASS |
| Tomcat 정지·unload | 7 | 테스트 문서화 상수(소스 추론) | PASS |
| 비우기 마감 | 25 | `UrgentNotificationDrainLifecycle.java:43` | PASS |
| 풀 잔여 대기 | 3+3+1+2+2 = 11 | `AsyncConfig.java:42-49` | PASS |
| 수동 풀 | 5×3 = 15 | `SolapiCallExecutor:44`·`EmailSendExecutor:38`·`AccessLogService:32` | PASS |
| 합 | 88 < 100 | compose dev:12·prod:15, gosky override 없음, vkcs·gosky `StopTimeout=100` | PASS |

- `ShutdownGraceBudgetTest`는 yaml·빈·상수·compose를 실제로 읽는다(Tomcat 7·단계 수 2만 하드코딩).
- 정책 B-3·B-1, yaml 주석, `AsyncConfig` Javadoc, compose 주석, 기록 문서의 숫자가 모두 같다.

## PHASE A. 종료 동작

| 점검 | 판정 | 근거 |
|---|---|---|
| 단계 상수 순서 | PASS | `ShutdownPhases.java:31,34` |
| 의존 빈 때문에 다른 단계로 끌려가는가 | PASS | 수신기가 받는 Lifecycle 빈은 `TaskScheduler`(0단계)뿐. 비우기는 `BeanFactory`만 받는다 |
| 유휴 판정(연속 2회, 50ms) | PASS | 큐에서 꺼낸 뒤의 틈은 ns 단위. 풀 안 지연 예약 없음 |
| 25초 초과 | 의도 | WARN 뒤 풀은 열린 채 0단계로. Redis 정지 뒤 쿨다운·문자 상한은 fail-open |
| 인터럽트 | PASS | 플래그 복원 후 탈출 |
| 수신기 가드 2곳·재접속·재동기화 | PASS | `AiLiveStreamSubscriber:284-291, 305, 237-240, 261, 465` |
| `@PreDestroy` 중복·미접속 stop | PASS | `closeQuietly` 멱등, null 안전 |
| `isPauseable=false` | PASS(필수) | pauseable이면 캐시된 테스트 컨텍스트에서 수신기가 죽는다 |
| 기동 | PASS | `start()`는 표시만, 접속은 `connectOnReady` 한 곳 |

### 비우기 뒤 알림 풀에 들어올 수 있는 경로

| 경로 | 풀 사용 | 잃는 것 | 빈도 |
|---|---|---|---|
| 처리 중이던 AI 프레임 | 긴급 | S-1 | 극히 낮음 |
| 복약·이상감지 재촉 스케줄러 | 아니오(직접 발송) | 선점 뒤 미발송분(재알림이 두 번째 기회) | 실행 중 배포가 겹칠 때 |
| 말벗 질문 안내(cron 하루 5회) | 아니오 | 그 슬롯의 남은 대상 | 정각 ± 종료 구간 |
| 말벗 마감(cron 5분) | 긴급·일반 | 위험 알림 유실 가능(재시도 없음) | 배포당 약 0.7% + 실행 중 겹침 |
| 탈퇴 스윕 | 일반·aiPurge | 상대 해제 알림, AI 삭제(수용 기재) | 드묾 |
| 카메라 끊김 유예 타이머 | 일반 | 유예 중 안내는 배포마다 버려짐(PR 이전과 같음) | 유예 120초 안 배포 |

## PHASE C. 보안·로그

| 항목 | 판정 | 근거 |
|---|---|---|
| `[SHUTDOWN-DRAIN]` 로그 | PASS | 풀 이름·ms·개수만 (`UrgentNotificationDrainLifecycle:113,116,127`) |
| 예외 로그 | PASS | 클래스명만 (`:127-128`, `AiLiveStreamSubscriber:289`) |
| `LogRawExceptionGuardTest` 등록 | 미반영 | T-4 |

## PHASE E. 운영 (읽기 전용)

| 서버 | 결과 |
|---|---|
| gosky | `b228093d`, `StopTimeout=100`, healthy. 로그: 02:18:45.350 graceful → .357 AI WS 종료 → .408 `[SHUTDOWN-DRAIN] waitedMs=50` → 02:18:56 AI WS 연결됨 |
| vkcs | `b228093d`, `StopTimeout=100`, running |

## 발견 사항

| ID | 등급 | 내용 | 위치 | 재현 조건 | 제안 |
|---|---|---|---|---|---|
| S-1 | 🟡 | 처리 중이던 화재 프레임은 stop이 기다리지 않는다 | `AiLiveStreamSubscriber:305, 233-242` | 위험 프레임이 가드를 지난 직후 stop, 그리고 이력 커밋이 비우기 종료(최소 50ms)보다 늦을 때(Redis·DB가 느린 순간). 풀이 닫힌 뒤면 이력·이력 쿨다운만 남고 알림 폐기 | `handleTextMessage`를 in-flight 카운터로 감싸고 stop에서 0이 될 때까지 최대 1~2초 대기(예산에 가산) |
| S-2 | 🟡 | 종료 구간에 시각이 된 cron 작업이 시작되고, 0단계에서 Redis가 먼저 멈춘다(PR 이전부터) | `ThreadPoolTaskScheduler:87`, `ScheduledAnnotationBeanPostProcessor:648-652` | 말벗 마감(5분)·질문 안내 정각이 종료 구간에 걸릴 때. 프로브에서 실행 확인. 기록 문서 §2 "새 예약을 받지 않는다"와 어긋남 | 문서 §7에 명시. 후속으로 전용 스케줄러 빈(phase를 비우기보다 높게, `executeExistingDelayedTasksAfterShutdownPolicy=false`) - 스케줄러 `pool.size` 열린 항목과 함께 |
| T-1 | 🟡 | 기본 생성자의 비우기 마감을 고정하는 테스트 없음 | `UrgentNotificationDrainLifecycle.java:59` | 기본 마감을 0초로 바꾼 변이가 전부 통과 | 기본 생성자 timeout = 상수 단언 |
| T-2 | 🟡 | "연속 2회 확인" 방어가 무테스트 | `UrgentNotificationDrainLifecycle.java:51,100` | `REQUIRED_IDLE_CHECKS=1` 변이가 전부 통과 | 확인 횟수 단언 또는 큐→작업 전환 틈 재현 테스트 |
| T-3 | 🟡 | `@PreDestroy` 대기 클래스 검사가 좁다 | `ShutdownGraceBudgetTest.java:92-99` | helper 경유 대기·`ExecutorService.close()`(무기한)·`DisposableBean`을 못 잡는다 | 검사 대상을 넓히거나 `@PreDestroy` 클래스 전수를 허용 목록으로 |
| T-4 | 🟡 | 새·변경 파일이 `LogRawExceptionGuardTest`에 없다 | `LogRawExceptionGuardTest.java:29-86` | 이후 원문 예외 로그가 들어와도 막지 못함 | `UrgentNotificationDrainLifecycle`·`AsyncConfig` 등록(지금도 통과). 수신기는 기존 `getMessage` 3곳(`:276,:448,:488`)을 걷어낸 뒤 등록 |
| S-3 | 🟢 | 일시정지된 테스트 컨텍스트를 닫으면 비우기가 25초를 다 쓴다 | `UrgentNotificationDrainLifecycle:90-92` | 테스트 전용 | 일시정지된 풀은 건너뜀 |
| S-4 | 🟢 | 수신기 close가 예산 밖 동기 대기(Tomcat 기본 20초) | `AiLiveStreamSubscriber:499` | AI 쪽 전송이 막혀 있을 때만(PR 이전에도 있음) | `BLOCKING_SEND_TIMEOUT`을 2~3초로 줄이거나 예산 주석에 적기 |
| S-5 | 🟢 | 컨텍스트 `stop()` 뒤 `start()`하면 수신기가 영구 정지 | `AiLiveStreamSubscriber:224-226,236` | 호출하는 곳 없음 | 주석에 "context.stop() 금지" 명시 |
| S-6 | 🟢 | 수신기 의존 배선이 테스트로 고정돼 있지 않다 | `ShutdownOrderTest.java:97-98,151-158` | 나중에 더 높은 단계의 Lifecycle 빈을 주입하면 수신 중단이 웹 종료 앞으로 당겨진다 | 생성자 파라미터에 더 높은 단계 SmartLifecycle이 없음을 단언 |
| T-5 | 🟢 | 마감 초과 WARN·인터럽트 경로 무테스트 | `UrgentNotificationDrainLifecycleTest.java` | - | ListAppender로 WARN 단언, 인터럽트 후 플래그 복원 단언 |
| T-6 | 🟢 | 정상 종료도 `AI WS 연결 종료 code=1000`이 WARN | `AiLiveStreamSubscriber:459` | 배포마다 WARN 1줄 | 종료 중이면 INFO로 |

## 테스트·변이

| 변이 | 결과 |
|---|---|
| `REQUIRED_IDLE_CHECKS` 2→1 | 생존 → T-2 |
| 수신기 `handleTextMessage` 가드 제거 | 사망(`종료_뒤_수신_중단`) |
| 비우기 단계를 0단계 아래로 | 사망(`ShutdownOrderTest` 3건) |
| 기본 생성자 마감 0초 | 생존 → T-1 |

## 수정용 커밋 메시지 초안

```
test: 종료 비우기 기본 마감·연속 확인·로그 가드 고정, 수신 중 프레임 대기

- UrgentNotificationDrainLifecycle 기본 마감·확인 횟수 단언, 마감 초과 WARN·인터럽트 테스트 (T-1·T-2·T-5)
- ShutdownGraceBudgetTest @PreDestroy 대기 검사 확대 (T-3), LogRawExceptionGuardTest 등록 (T-4)
- AiLiveStreamSubscriber 처리 중 프레임 대기(최대 2초, 예산 가산)·정상 종료 INFO·의존 단계 테스트 (S-1·T-6·S-6)
```

## 미확인

- S-1은 코드 경로와 gosky 시각으로 추론했다(경합 재현 없음).
- S-2에서 0단계 안에 Lettuce가 스케줄러 콜백보다 먼저 멈춘다는 부분은 소스 추론이다(cron 실행 자체는 프로브로 확인).
- S-4의 20초는 Tomcat 소스 상수 기준이다.
- 15초 우아한 종료를 넘긴 HTTP 요청이 Tomcat 정지 뒤 커밋해 풀에 제출하는 경로는 분석하지 않았다.
- 진행 중 요청·알림이 있을 때의 실제 종료 시간은 실측하지 않았다(읽기 전용).
