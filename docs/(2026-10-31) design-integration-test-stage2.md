# 통합 테스트 2단계 설계 - 앱 전체를 띄워 서비스·트랜잭션·리스너 검증 (2026-10-31)

> 상태: **구현 완료** (2026-10-31 사용자 "시작해" - Q1~Q5 모두 추천안). 결과는 맨 아래 "3. 결과" 절.
> 브랜치 `test/integration-stage2` · 운영 코드 변경 없음.

## 0. 현황 (2026-10-31, 코드로 확인)

### 0-1. 기준선

| 항목 | 값 |
|---|---|
| dev | `1591295` |
| `./gradlew build` | 통과, 80초(로컬) |
| 단위 테스트 | 2185건(실패 0·건너뜀 1) |
| vkcs `integration-test.sh dev` (캐시 적중) | 22초, `integrationTest FROM-CACHE` |
| vkcs 같은 대상 `--rerun`(실제 실행) | **122건 통과, 테스트 44.2초, 전체 70초** |
| CD 통합 테스트 단계(최근 4회) | 실제 실행 1분 20초~1분 25초 / 캐시 적중 23초 |

### 0-2. integrationTest 소스셋

- 테스트 클래스 40개 + 지원 2개(`PostgresIntegrationTest`·`TestData`). 기반은 `@DataJpaTest` + `@AutoConfigureTestDatabase(NONE)` + 정적 `postgres:17` 컨테이너 1개를 JVM 끝까지 공유(`max_connections=300`), 데이터는 테스트 트랜잭션 롤백으로 정리.
- Redis 검증(`SmsFallbackLimiterRedisIntegrationTest`)은 Spring 없이 `redis:7.2` `GenericContainer`를 직접 쓴다.
- `@SpringBootTest`는 0건(단위 쪽 `BackendApplicationTests`는 `@Disabled`). 커밋을 보는 테스트 3개(`WithdrawalListenerCommit`·`NotificationLogAfterCommit`·`WithdrawalAiPurge`)는 슬라이스에 리스너·서비스를 **손으로 `@Import`** 하고 `@Transactional(NOT_SUPPORTED)` + `TransactionTemplate`로 **이벤트를 직접 발행**한다.
- 의존성: Testcontainers 2.0.5, Awaitility 4.3.0(spring-boot-starter-test 경유)이 이미 경로에 있다. Gradle 빌드 캐시 켜짐.

**지금 방식의 한계** - (1) 실제 진입점(`UserService.withdraw`·`AdminUserService.forceDelete` 등)을 거치지 않아, 서비스가 이벤트를 안 내거나 다른 이벤트를 내면 못 잡는다. (2) 리스너 목록을 사람이 고르므로 새로 생긴 리스너·빠진 빈은 테스트 밖이다. (3) 디스패처·채널·설정·이력까지 이어지는 실제 순서(리스너 → `NotificationDispatcher` → 채널 → `notification_log`)를 보지 않는다. (4) Redis가 없어 Redis 쓰기가 섞인 리스너(H-2)는 검증 불가.

### 0-3. 앱 전체 기동 시 막아야 할 것

| 대상 | 기동 시 동작 | 끄는 방법(지금) | 테스트 처리 |
|---|---|---|---|
| 필수 설정 검증기 `RequiredPropertiesValidator`·`SecurityConfigValidator` | `@PostConstruct`에서 비면 실패(JWT 32자↑, 카카오 secret 32자↑·자리표시자 금지) | 없음 | 테스트용 가짜 값(무작위 32자↑) 주입 |
| Firebase `FcmConfig#firebaseMessaging` | base64 없으면 예외, 있으면 `FirebaseApp` 초기화 | 없음 | `@MockitoBean FirebaseMessaging`(팩토리 대체 → 초기화 안 함) |
| Solapi `SmsSender`·`AlimtalkSender` | 호출 때만 | 알림톡은 기본 꺼짐 | `@MockitoBean` |
| SMTP `JavaMailSender` | 호출 때만 | 이메일 채널은 기본 꺼짐, 재설정 메일은 없음 | `@MockitoBean JavaMailSender` |
| 카카오 `KakaoOAuthClient` | 호출 때만 | 없음 | `@MockitoBean` |
| 파일서버·AI(챗·클립·스트림·OCR·헬스)·예약 클라이언트 | 호출 때만 | 일부 플래그 | 모두 `@MockitoBean`, 플래그도 끔(`medication.ocr`·`anomaly.clip`·`chat.relay`) |
| AI WS `AiLiveStreamSubscriber` | `ApplicationReadyEvent`에 접속·재접속 예약 | `anomaly.enabled=false` | 플래그로 끔 |
| 스케줄러 11종 | `@EnableScheduling` 무조건(`BackendApplication`), fixedDelay 3종은 기동 직후 실행 | 개별 플래그 일부만, 탈퇴 스윕·클립 청소·익명화 등은 없음 | **Q2** - 테스트 전용 후처리기로 `internalScheduledAnnotationProcessor` 정의 제거 + 테스트용 `TaskScheduler` 제공(운영 코드 무변경) |
| WebSocket STOMP `SimpMessagingTemplate` | 메시지 브로커 기동(외부 없음) | - | 실제 빈 + `WebSocketEventPublisher` 호출을 스파이로 기록 |

### 0-4. AFTER_COMMIT 리스너 (시나리오 근거)

| 구분 | 리스너(이벤트) |
|---|---|
| 동기 + REQUIRES_NEW | `UserAccountEventListener` 4종(탈퇴·정지·역할 변경·비밀번호 변경) |
| 동기(위임 대상이 REQUIRES_NEW - `SyncAfterCommitListenerPropagationTest` 표) | `UserWithdrawalConnectionListener`·`MedicationWithdrawalListener`·`UserWithdrawalFcmListener`·`PasswordChangedFcmListener`·`AnomalyClipCleanupListener` 2종·`AiLiveStreamSubscriber.onCameraRegistered` |
| `@Async(notificationExecutor)` | `ConnectionNotificationListener` 6종, `InquiryNotificationListener`, `MedicationIntakeNotificationListener`, `KakaoRegisterEventListener`(+REQUIRES_NEW) |
| `@Async(urgentNotificationExecutor)` | `SosNotificationListener`, `AnomalyNotificationListener` |
| `@Async(aiPurgeExecutor)` | `AiMemberDataPurgeListener`(탈퇴), `AiProxyChatLogPurgeListener`(연결 해제) |
| `@Async(clipExecutor)` | `AnomalyClipListener` |

### 0-5. 전체를 띄우는 비용(추정)

- 컨텍스트 기동: 로컬 기준 10~20초, vkcs(HDD) 20~35초로 본다. **모든 2단계 테스트가 같은 설정(같은 목 목록·속성)을 쓰면 컨텍스트는 1번만 뜬다** - 클래스마다 `@MockitoBean`을 다르게 두면 클래스마다 새로 떠 비용이 배로 는다(설계의 핵심 제약).
- 컨테이너: Redis 1개 추가(약 2~3초). PostgreSQL은 기존 것 재사용(별도 DB).
- 시나리오 5개 × 수 초.
- **CD 증가분 예상 +40~70초**(1분 20초 → 2분~2분 30초). 캐시 적중이면 변화 없음.

## 1. 설계

### ① 공용 기반 `FullContextIntegrationTest` (support 패키지)

- `@SpringBootTest(webEnvironment = NONE)` - HTTP 계층은 범위 밖(서비스 진입점부터). 컨트롤러 경유가 필요한 시나리오가 생기면 `MOCK`으로 올린다.
- **PostgreSQL**: 기존 `PostgresIntegrationTest.POSTGRES` 컨테이너를 그대로 쓰되 **전용 DB `sb_full`** 을 만들어 연결한다(Flyway V1~ 새로 적용). 1단계 테스트와 데이터·커넥션이 섞이지 않는다.
- **Redis**: `redis:7.2` 컨테이너 1개(정적 공유). `spring.data.redis.timeout=500ms`(Redis 일시 중지 시나리오가 빨리 끝나게).
- **외부 연동**: 0-3 표의 대체 빈을 **베이스 클래스 한 곳에만** 둔다(컨텍스트 1회 기동). 시나리오는 목의 호출·인자를 검증한다. 실제 외부 호출은 0건.
- **스케줄러**: Q2 참조.
- **@Async 대기**: Awaitility `await().atMost(10s).untilAsserted(...)`, 부정(일어나면 안 됨) 검증은 `during(1s)`. `Thread.sleep` 금지.
- **데이터 정리**: 테스트 트랜잭션을 쓰지 않는다(롤백은 AFTER_COMMIT을 못 본다). 각 테스트 후 ① 실행 중 `@Async` 작업이 끝나기를 기다리고(executor 큐·활성 수 0까지 Awaitility) ② `sb_full`의 `flyway_schema_history` 외 전 테이블 `TRUNCATE ... RESTART IDENTITY CASCADE` ③ Redis `FLUSHDB` ④ 목 `reset`.

### ② 시나리오 우선순위

| 순위 | 시나리오 | 정책 근거 | 단위 테스트로 못 잡는 이유 | 실패 재현(수정 전으로 임시 되돌림) | 예상 |
|---|---|---|---|---|---|
| **1** | 일반 탈퇴: `UserService.withdraw` → 커밋 → 동기 리스너들 → `purgeWithdrawnUser` | §8 탈퇴 hard delete, H-1(2026-09-30) | REQUIRES_NEW 누락 시 쓰기·이벤트가 커밋된 트랜잭션에 합류해 사라지는 것은 실제 트랜잭션에서만 보인다 | H-1 수정 이전 전파(REQUIRED)로 되돌리면 상대 해제 알림 0건 | 1.5h |
|  | 검증: 상대 보호자에 `CONNECTION_DISCONNECTED`(WITHDRAWN) 발송·이력, refresh·FCM 토큰 삭제, Redis 무효화 키, AI 삭제 요청(비동기), 회원 행 삭제 | | | | |
| **2** | 관리자 강제 탈퇴 `AdminUserService.forceDelete` - 같은 파이프라인 | §8 "관리자 강제 탈퇴도 같은 파이프라인" | `delete()` 직접 호출로 바뀌어도 단위 테스트 목은 통과한다 | `forceWithdraw` 대신 `userRepository.delete` 로 바꾸면 알림·토큰 정리 실패 | 0.5h(1과 단언 공유) |
| **3** | 비밀번호 변경 + **Redis 일시 중지** → refresh 토큰 삭제는 커밋 | H-2(2026-10-31) | Redis 예외가 REQUIRES_NEW를 롤백시키는 것은 실 DB + 실 Redis 장애에서만 보인다 | `fa80217` 이전(한 덩어리 실행)으로 되돌리면 refresh 토큰 부활 | 1.5h |
|  | 방법: Testcontainers `dockerClient.pauseContainerCmd`로 Redis를 멈춘 채 `UserService.changePassword` → 커밋 후 DB에 refresh 0건, 재개 후 정상 | | | | |
| **4** | 관리자 역할 변경 `AdminUserService.updateUser`(피보호자→보호자) | §8 역할 변경 = 연결 정리 + 카메라 삭제 + 토큰 무효화 | 세 효과가 다른 서비스·리스너에 흩어져 있다 | (재현 대상 수정 커밋 없음 - 회귀 고정용) | 1h |
|  | 검증: 연결 DISCONNECTED(`ADMIN`), 상대에 해제 알림, 카메라 0대, refresh 삭제·무효화 키, 감사 로그 1건 | | | | |
| **5** | 정지 수신자 차단: 보호자 RESTRICTED + 피보호자 SOS → 정지 보호자는 전 채널 미발송(이력 `RESTRICTED_ACCOUNT`), 같은 피보호자의 ACTIVE 보호자는 FCM 발송 | §8 정지 계정 알림 미발송(`dispatch()` 한 곳) | 긴급 executor·설정·채널·이력을 실제로 거치는지 | (회귀 고정용) | 1h |

**후속(이번 범위 밖)**

| 시나리오 | 비고 |
|---|---|
| 관리자 강제 연결 → 양쪽 WS(알림보다 먼저)·`CONNECTION_FORCED`·감사 로그 | 순서 단언 필요(스파이 호출 순서) - ✅ 2026-11-01 완료(§3-6) |
| 연결 종료 4경로 → `AiProxyChatLogPurgeListener`(탈퇴 제외) | `aiPurgeExecutor` - ✅ 2026-11-01 완료(§3-6) |
| 정지 → 열린 WS 세션 종료(1008) | `webEnvironment=RANDOM_PORT` + STOMP 클라이언트 필요 |
| 동시성: 연결 요청 ↔ 역할 변경 잠금, 이상감지 판정 동시 쓰기 | 1단계 `ConnectionRoleChangeRaceIntegrationTest`가 저장소 수준은 이미 검증 - 서비스 수준으로 올릴지 따로 판단 |
| 카카오 가입 → 접속로그 AFTER_COMMIT(FK) | |

### ③ 1차 범위

공용 기반 + 시나리오 1~5. 시나리오마다 테스트 클래스 1개(베이스 공유), 테스트 수 8~12건 예상.

### ④ 실행 위치·시간

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 기존 `integrationTest`에 함께(추천)** | 스크립트·CD·캐시 그대로, build.gradle 변경 최소 | CD가 +40~70초(실제 실행일 때만) |
| B. 별도 태스크 `integrationTestFull`, CD에서 이어 실행 | 실패 원인이 나뉘어 보임, 따로 돌릴 수 있음 | 소스셋·스크립트 인자·CD 수정 필요, 결국 배포 시간은 같다 |
| C. 별도 태스크, CD에는 안 넣고 수동 | 배포 시간 그대로 | 회귀가 배포 전에 안 막힌다(2단계를 만드는 이유와 어긋남) |

추천 A: 배포 시간 증가가 1분 안쪽이고, 캐시 적중 배포(문서 외 변경 없는 재배포)는 영향이 없다. build.gradle 변경은 없을 것으로 본다(Redis는 Testcontainers 코어 `GenericContainer`, Awaitility 기존). 필요해지면 의존성 1줄만 추가한다.

### ⑤ 1단계에 영향이 없음을 보이는 방법

- 1단계 테스트 파일·`PostgresIntegrationTest`는 수정하지 않는다(컨테이너 참조만).
- 전용 DB `sb_full` + 전용 Redis라 데이터·커넥션 공유 없음. 정리(TRUNCATE)는 `sb_full`에만.
- 검증: 브랜치 실행 결과에서 1단계 122건이 그대로 통과하고 2단계 건수만 늘었는지, 1단계 클래스별 시간이 기준선과 비슷한지 비교.

## 2. 결정 질문

| # | 질문 | 추천 |
|---|---|---|
| Q1 | 1차 범위를 시나리오 1~5로 할까요? | 예 |
| Q2 | 스케줄러 끄기: (a) 테스트 전용 후처리기로 스케줄 처리기 빈 정의 제거 + 테스트용 `TaskScheduler`(운영 코드 무변경) / (b) `@EnableScheduling`을 `SchedulingConfig`로 옮기고 `@ConditionalOnProperty(app.scheduling.enabled, matchIfMissing=true)`(운영 코드 1파일 + 설정 키) | (a). 기동 검증에서 (a)가 막히면 (b)로 바꾸고 보고 |
| Q3 | 실행 위치 A(기존 `integrationTest`·CD 그대로)로 할까요? | A |
| Q4 | HTTP 계층은 제외(`webEnvironment=NONE`, 서비스 진입점부터)로 할까요? | 예 - 컨트롤러는 WebMvc 슬라이스가 이미 검증 |
| Q5 | 실패 재현은 H-1(시나리오 1)·`delete()` 직접 호출(2)·H-2(3)만 하고, 4·5는 수정 커밋이 없어 회귀 고정으로 둘까요? | 예 |

## 3. 결과 (2026-10-31)

### 3-1. 바뀐 파일

| 파일 | 내용 |
|---|---|
| `src/integrationTest/.../support/FullContextIntegrationTest.java` | 공용 기반(신규) |
| `.../user/WithdrawalPipelineFullIntegrationTest.java` | 시나리오 1·2 |
| `.../auth/PasswordChangeTokenRevocationFullIntegrationTest.java` | 시나리오 3 + 정상 경로 |
| `.../admin/AdminRoleChangeFullIntegrationTest.java` | 시나리오 4 |
| `.../sos/SosRestrictedRecipientFullIntegrationTest.java` | 시나리오 5 |
| `build.gradle` | 통합 테스트 스위트에 `spring-security-crypto` 1줄(운영 `PasswordEncoder` 빈으로 회원 비밀번호를 만든다 - 설계 ④의 "필요하면 1줄") |

운영 코드·1단계 테스트·`PostgresIntegrationTest`·스크립트·CD는 바꾸지 않았다.

### 3-2. 설계와 달라진 점

- `webEnvironment`를 `NONE`이 아니라 기본값(`MOCK`)으로 두었다. 보안·WebSocket 설정이 서블릿 컨텍스트를 전제해 `MOCK`이 안전하고, 포트는 열지 않는다. 테스트는 여전히 서비스 진입점부터 부른다(Q4 취지 그대로).
- 메일 헬스 체크가 목 `JavaMailSender`로는 뜨지 못해 `management.health.mail.enabled=false`를 기반 속성에 더했다.
- Q2 (a)는 그대로 동작했다: `@Scheduled` 처리기 정의를 지워도 자동 설정 `TaskScheduler`는 남는다. 실행 로그에 스케줄 작업 0건, AI WS는 `anomaly.enabled=false`로 미접속.
- 비밀번호 + Redis 일시 중지 시나리오에서 무효화 키 "없음"은 단언하지 않는다 - 멈춘 동안 보낸 SET이 재개 순간 늦게 실행된다(관찰). 고정하는 것은 "Redis 예외가 refresh 삭제를 되돌리지 않는다"뿐이다.

### 3-3. 시나리오별 결과·실패 재현

| 시나리오 | 결과 | 수정 전으로 임시로 되돌렸을 때 |
|---|---|---|
| 1 본인 탈퇴 | 통과 | H-1(`tearDownConnectionsOnWithdrawal` REQUIRES_NEW 제거) → **실패**: 연결이 `ACTIVE`로 남음 |
| 2 관리자 강제 탈퇴 | 통과 | `forceWithdraw` → `userRepository.delete` → **실패**: 무효화 키 없음. (H-1 되돌림에서도 상대 알림 0건으로 실패) |
| 3 비밀번호 변경 + Redis 일시 중지 | 통과 | `fa80217` 이전(한 덩어리) → **실패**: refresh 토큰 2개 부활 |
| 3' 정상 비밀번호 변경 | 통과 | - |
| 4 관리자 역할 변경 | 통과 | (회귀 고정) |
| 5 정지 보호자 SOS 차단 | 통과 | (회귀 고정) |

되돌린 코드는 커밋하지 않았고 `git checkout`으로 원복했다.

### 3-4. 소요 시간 (vkcs, 실제 실행 `--rerun`)

| | 기준선(dev `1591295`) | 이 브랜치 |
|---|---|---|
| 테스트 수 | 122 | 128 |
| 테스트 시간 | 44.2초 | 62.1초 (+18초) |
| 실행 전체 | 70초 | 85초 (+15초) |
| 2단계 컨텍스트 기동 | - | 17.1초(1회) |

흔들림: 새 시나리오 3회 반복(전체 1회 + 단독 2회) 모두 통과.

### 3-5. 발견한 운영 코드 문제 (보고만, 수정 안 함)

| 등급 | 내용 | 근거 |
|---|---|---|
| 🟢 | 피보호자 탈퇴 시 상대 보호자 해제 **푸시는 나가지만** 이력 기록이 `fk_notification_log_ward` 위반으로 실패한다(`[NOTIFY-LOG-FAILED]` WARN). 푸시는 purge 뒤 `@Async`로 나가는데 이력의 `ward_id`가 이미 지워진 피보호자다. FK가 `ON DELETE CASCADE`라 먼저 기록됐어도 purge 때 지워지므로 결과는 설계("탈퇴자 관련 이력은 남기지 않는다")와 같고, 영향은 WARN 한 줄뿐이다. 정리하려면 디스패처가 이력 저장 전에 `ward_id` 존재를 보고 NULL로 두거나 저장을 건너뛰는 방법이 있다 - 별도 판단. | `V54__create_notification_log.sql:29`, `NotificationDispatcher`의 `[NOTIFY-LOG-FAILED]` |

### 3-6. 후속 시나리오

§1 ②의 "후속" 표 그대로: 관리자 강제 연결(WS가 알림보다 먼저) / 연결 종료 4경로 → AI 대리 상담 기록 삭제 / 정지 → 열린 WS 세션 1008 종료(RANDOM_PORT + STOMP 클라이언트) / 서비스 수준 동시성(연결 요청 ↔ 역할 변경, 이상감지 판정) / 카카오 가입 접속로그 AFTER_COMMIT.

**진행 결과 (2026-11-01, PR #424 머지 `67d8fc3`, 운영 코드 변경 없음)**

| 후속 시나리오 | 상태 | 테스트 | 수정 전 재현 |
|---|---|---|---|
| 관리자 강제 연결 → 양쪽 WS(알림보다 먼저)·`CONNECTION_FORCED`·감사 로그 | ✅ 완료 | `AdminForceConnectFullIntegrationTest` 2건(순서 InOrder / 보호자 설정 조회 실패에도 양쪽 WS) | WS를 dispatch 뒤로 → 2건 모두 실패 |
| 연결 종료 4경로 → `AiProxyChatLogPurgeListener`(탈퇴 제외) | ✅ 완료 | `ConnectionEndProxyChatPurgeFullIntegrationTest` 6건 - 보호자 해제·피보호자 해제 1회 / 역할 변경 정리 쌍마다 1회 / **관리자 강제 해제는 같은 쌍 2회**(양쪽 알림용 이벤트 2번, 멱등 - 코드 주석과 같음) / 탈퇴 0회 / AI 실패에도 해제 커밋 | 리스너 제거 → 경로 4건 + AI 실패 건 실패, WITHDRAWN 건너뛰기 제거 → 탈퇴 건 실패, 동기 리스너+예외 전파 → AI 실패 건 실패 |
| 정지 → 열린 WS 세션 1008 종료 | 미진행 | | |
| 서비스 수준 동시성 | 미진행 | | |
| 카카오 가입 접속로그 AFTER_COMMIT | 미진행 | | |

- 기반 클래스 변경: `WebSocketEventPublisher`·`NotificationDispatcher`·`NotificationSettingService`를 `@MockitoSpyBean`으로(실제 동작 + 호출 기록, 실패 주입). 기반 클래스에만 둬서 컨텍스트 1회 기동은 그대로다.
- 정책 문서와의 차이: L-13은 "연결 종료 4경로"라 적었고 코드 주석은 탈퇴를 포함한 5경로다. 삭제 요청은 4경로이고, 관리자 강제 해제만 같은 쌍으로 2회 나간다.
- 소요 시간(vkcs `--rerun`): 128건 62.2초·전체 85초 → 136건 72.9초·전체 100초. 새 시나리오 3회 반복 모두 통과.
- 🟡 발견 → **해소(2026-11-01, PR #425 `ff2583a`)** - 같은 원인으로 이상감지 강제 푸시도 빠지던 구멍까지 디스패처에서 함께 고쳤고, 통합 테스트 A'의 단언(피보호자 이력 1건)을 채웠다. 원래 내용: 강제 연결 때 보호자 알림 설정 조회(DB)가 실패하면 예외가 `handleForced`를 빠져나가 피보호자 dispatch(푸시·이력)도 빠진다 - 임시 단언으로 확인. WS는 먼저 나가 CONN-G02 범위는 지켜지지만 강제 연결 정책 ①("피보호자 알림을 빼지 말 것")과 닿는다. 근거 `ConnectionNotificationListener.java:118-121`, `NotificationDispatcher.java:207-211`. 테스트는 이 동작을 고정하지 않았다(고칠 때 막지 않게).
