# 기능 점검(B) - 종료 순서 보강 BE #519

- 일자: 2026-11-22 · 템플릿 B(짧게) · 점검만(코드 변경 없음)
- 대상: BE #519 (머지 `9906f152`) - `AiLiveStreamSubscriber` 처리 중 프레임 대기 3초·로그 4곳·종료 중 정상 종료 INFO, 테스트 5종, 문서
- 기준 코드: `origin/dev` `dfcdfed4`(별도 worktree)
- 조사: PHASE A fable xhigh(정적 분석 + Spring 7.0.9·Tomcat 11.0.26 바이트코드) · PHASE B~E sonnet(예산·훅 목록·로그·테스트 3회·변이 2종·서버 읽기)
- 근거: `(2026-11-22) fix-shutdown-order-hardening.md` · `(2026-11-22) audit-shutdown-order.md`

## 결론

- **PASS · 🔴 0 · 🟠 0 · 🟡 4 · 🟢 9**
- 동시성·순서 결함은 없다. 아래는 모두 확인했다.
  - "증가 → 종료 확인" 순서는 메모리 모델상 빈틈이 없다.
  - 첫 호출만 대기하고, 교착이 없다.
  - 프레임의 긴급 알림 제출이 카운터 감소보다 먼저 끝난다.
  - 3초 대기 뒤에 25초 비우기가 차례로 이어진다.
- 남은 것은 문서 정밀도 2건(H-1·U-1)과 테스트 보강 2건(U-2·U-3)이다.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 기준 | `origin/dev` `dfcdfed4`, `9906f152` 포함 |
| 빌드 | `./gradlew build --no-daemon` 통과 - 2,945건, 실패 0, 건너뜀 2. OCR 시간 제한 테스트도 이번엔 흔들리지 않음 |
| 관련 5종 | `cleanTest --no-build-cache` 3회 - 55건 × 3 모두 통과(22·28·23초) |

## PHASE A. 동시성·순서

| 항목 | 판정 | 근거 |
|---|---|---|
| 증가 → 종료 확인, finally 감소 | PASS | `AiLiveStreamSubscriber:412` 증가(try 밖) → `:414` 확인 → `:431-433` finally. 조기 return·Exception·Error 모두 감소 |
| stop이 0을 보고 프레임이 처리되는 경우 | PASS(없음) | volatile 쓰기(`:269`)와 atomic 증가(`:412`)가 모두 동기화 동작이라 전체 순서가 있다 - 한쪽은 반드시 상대를 본다 |
| 같은 세션 동시 호출 | PASS | Tomcat 클라이언트는 세션당 직렬. 옛·새 세션이 겹쳐도 합이라 의미 유지 |
| 첫 호출만 대기·인터럽트 | PASS | `:274` compareAndSet, `:293-296` 플래그 복원 |
| 교착 | PASS(없음) | 대기 중 stop 스레드는 `startupShutdownLock`만 쥐고 프레임 경로는 그 잠금을 쓰지 않는다. 닫기는 대기 뒤. `WsSession.doClose`는 CAS(모니터 없음) |
| 빈 생성 금지 예외 | PASS·의도 | 3초 대기는 lifecycle 단계(싱글턴 파괴 전)라 나지 않는다. 3초를 넘겨 파괴까지 간 프레임만 그 메시지를 버린다 |
| `handshakeStatus`·`rootCauseName` | PASS | 반환은 3자리 숫자 또는 `-`뿐, 깊이 10 + 자기 원인 차단 |
| 종료 로그 레벨 | PASS | INFO는 `shuttingDown && 1000`만, 그 외 WARN. 재접속 가드 그대로 |
| 3초 → 25초 순차 | PASS | 동기 `stop()`, 단계 +1024 > +512 |
| 알림 제출이 감소보다 먼저 | PASS | 프레임 스레드에서 커밋 → AFTER_COMMIT `@Async` 제출 → 반환 → 감소. 풀은 `lateShutdown`이라 종료 단계에도 받는다 |

## PHASE B. 예산·훅 목록

| 항목 | 판정 | 근거 |
|---|---|---|
| 91 = 15×2 + 7 + 3 + 25 + 11 + 15 | PASS | `application.yaml:110` · `ShutdownGraceBudgetTest:67` · `AiLiveStreamSubscriber:106` · `UrgentNotificationDrainLifecycle:43` · `AsyncConfig:42-49` · 수동 풀 3종 · compose dev:12·prod:15 |
| 수신기 대기를 리플렉션으로 읽음 | PASS | `ShutdownGraceBudgetTest:73-78, 239`, 0 이하면 실패 |
| 종료 훅 18개 목록 | PASS(누락·초과 0) | `@PreDestroy` 16 + Lifecycle 3(고유 18). `DisposableBean`·`destroyMethod`·`ContextClosedEvent` 0건, `AutoCloseable` 구현은 모두 빈이 아닌 안쪽 클래스 |
| "대기 없음" 금지 호출 목록 | 대체로 적절 | U-7 |
| 문서 숫자 | 대부분 일치 | U-1 |

## PHASE C. 보안·로그

| 항목 | 판정 | 근거 |
|---|---|---|
| 접속 실패 `error=원인 클래스명, status=3자리/-` | PASS | `:340, 351-385` |
| 전송 오류·전송 실패·닫기 오류 | PASS | `:560`, `:606`(payload·세션 ID 없음), `:394·:620` |
| 종료 중 code=1000 INFO | PASS | gosky 실로그로 확인 |
| 새로 등록한 3개 파일 검사 | PASS | `LogRawExceptionGuardTest:89-91`, 오타·삭제면 `NoSuchFileException`으로 실패 |
| "스택 로그 1곳만 의도적 유지" 정책 문구 | PASS | 예외 객체를 넘기는 곳은 `:429` 한 곳뿐 |

## PHASE D. 테스트·변이

| 변이 | 결과 |
|---|---|
| 증가를 종료 확인 뒤로 이동 | **생존** → U-2 |
| finally 감소 제거 | 사망 - `처리 중인 프레임이 없으면 stop은 기다리지 않는다` 1건에만 의존 → U-3 |

- 대기 테스트는 "300ms 동안 미복귀 + 닫지 않음 + 닫을 때 처리 완료"를 확인해 의미 있고 느려져도 거짓 실패가 없다.
- 마감 테스트의 상한 3초만 극단적 부하에서 이론상 흔들릴 수 있다.

## PHASE E. 운영 (읽기 전용)

| 서버 | 결과 |
|---|---|
| gosky | `9906f152`, healthy. 24시간 로그에 `AI WS 연결 종료: code=1000` INFO 1줄, `[SHUTDOWN-DRAIN] waitedMs=50`. `[SHUTDOWN-AI-STREAM]`·접속 실패·전송 로그 0건(처리 중 프레임 없음 - 정상) |
| vkcs | `9906f152`, healthy |

## 발견 사항

| ID | 등급 | 내용 | 위치 | 재현 조건 | 제안 |
|---|---|---|---|---|---|
| H-1 | 🟡 | 3초를 넘긴 프레임의 결말이 주석·문서와 다르다 | `AiLiveStreamSubscriber:281-282`, `fix-shutdown-order-hardening.md:159` | DB 커넥션 고갈 3초 이상(또는 위험 종류 여러 개 + Redis 지연) 중 종료. 풀 잔여 대기는 풀이 닫히기 **전에** 제출된 작업만 덮고, 그 뒤 커밋한 프레임은 `[NOTIFY-REJECTED]`로 폐기되며 이력·쿨다운만 남는다 | 주석·문서를 "풀 종료 전에 제출된 경우만 잔여 대기, 그 뒤는 폐기(ERROR 로그)"로 정정. 코드 변경 불필요 |
| U-1 | 🟡 | 기록 문서 §6 테스트 표가 #518 시점 값 그대로 | `fix-shutdown-order.md:83-89` | 같은 문서 §4 예산 표는 갱신돼 문서 안에서 어긋남 | 표 갱신 또는 "#518 시점" 표기 |
| U-2 | 🟡 | "증가 → 확인" 순서를 테스트가 지키지 못한다 | `AiLiveStreamSubscriberTest` | 증가를 확인 뒤로 옮기면 종료 뒤 도착 프레임이 감소만 해 카운터가 -1이 되는데 테스트가 통과 | 종료 뒤 프레임 1건 처리 후 카운터가 0인지 단언(결정적 재현 가능) |
| U-3 | 🟡 | finally 감소를 지키는 테스트가 1건뿐 | `AiLiveStreamSubscriberTest:482` | - | U-2의 카운터 0 단언으로 이중화 |
| H-2 | 🟢 | 3초 근거 주석이 "프레임 1건 = Redis 1회"로 적혀 있다(실제는 위험 종류 수만큼) | `AiLiveStreamSubscriber:101-104` | 설계 문서는 이미 수용으로 적음 | 주석 한 줄 보정 |
| H-3 | 🟢 | (기존) `send()`에 동시 전송 보호가 없다 | `AiLiveStreamSubscriber:596-610` | 재동기화·카메라 등록의 목록 요청과 구독 전송이 겹치면 `IllegalStateException`으로 그 `live_streams` 1건 폐기(다음 재동기화에서 복구). 알림 경로는 무관 | `catch (IllegalStateException)` 또는 `ConcurrentWebSocketSessionDecorator` |
| H-4 | 🟢 | 대기가 프레임 종류를 가리지 않는다 | `:412`, `:301` | 알림과 무관한 프레임에도 최대 3초 대기·INFO | 그대로 둠(상한 있음) |
| H-5 | 🟢 | (기존) 접속 성립과 stop 사이 틈 | `:389`, `:398` | 첫 닫기가 세션을 못 보면 `@PreDestroy`까지 열려 있음(프레임은 버려짐) | 그대로 둠 |
| U-4 | 🟢 | 로그 가드가 `getMessage()`만 잡는다 | `LogRawExceptionGuardTest:26` | `log.x("..", e)`·`toString()`은 못 잡음 | 필요 시 보조 검사 |
| U-5 | 🟢 | 가드 목록에 `CompanionGuardianService.java` 중복 | `LogRawExceptionGuardTest:84-85` | - | 한 줄 삭제 |
| U-6 | 🟢 | 남겨 둔 스택 로그에 AI 프레임 조각·세션 ID가 실릴 수 있다(기존) | `AiLiveStreamSubscriber:429, :554` | 파싱·DB 예외 메시지 | 정책 문구에 명시 |
| U-7 | 🟢 | "대기 없음" 금지 호출 목록의 오탐(`.get(`)·미탐(`wait(`·`.take()`·`.acquire(`·`.lock()`·메서드 참조) | `ShutdownGraceBudgetTest:115` | 문서에 한계 기재됨 | 필요 시 보강 |
| U-8 | 🟢 | 익명 클래스 예외면 원인 클래스명이 빈 문자열 | `AiLiveStreamSubscriber:360` | - | 빈 값이면 `getName()` |

## 수정용 커밋 메시지 초안

```
test: 수신기 처리 중 프레임 카운터 단언 보강, 종료 문서·주석 정정

- 종료 뒤 도착 프레임 처리 후 카운터 0 단언 (U-2·U-3)
- 3초 초과 프레임의 결말·3초 근거 주석 정정, fix-shutdown-order §6 표 갱신 (H-1·H-2·U-1)
- LogRawExceptionGuardTest 중복 제거, 원인 클래스명 빈 값 대체 (U-5·U-8)
```

## 미확인

- 실행 재현 없이 코드와 바이트코드로 판정했다(worktree 공유로 Gradle은 B~E 쪽만 실행).
- Tomcat 클라이언트의 세션당 직렬 호출은 지식 근거다.
- 처리 중 프레임이 있는 상태의 실제 종료는 아직 관측된 적이 없다.
- 웹 종료 구간에 AI WS가 끊기면 재접속 예약이 거절될 수 있다는 관찰(보강 문서 §9)은 실행 확인하지 않았다.
