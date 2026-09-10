# 기술 횡단 점검 - 의존성 · 동시성 · 외부 연동 회복력 · 성능 · 운영 설정 · 아키텍처

> 지금까지의 점검(2026-05 ~ 2026-09-10)이 인가·기능·구조·테스트 4축뿐이었고 아래 6축은 처음이다.
> 점검일: 2026-09-11 · HEAD `418d1c3` · 코드 미수정 · 후속: `(2026-09-10) audit-regression-pre-230.md`(회귀 재점검)

---

## PHASE 0. 대상

| 축 | 대상 |
|---|---|
| 외부 연동 5종 | FCM(firebase-admin) · Solapi SMS/알림톡 · SMTP(Gmail) · 카카오 OAuth · AI WebSocket |
| 스케줄러 5종 | 복약 1분 · 재촉 5분 · 탈퇴 스윕 10분 · refresh 정리 03:00 · FCM 유휴 정리 04:00 KST |
| 비동기 | `notificationExecutor`(core 2 / max 10 / queue 100 / CallerRuns) + AFTER_COMMIT 리스너 9곳 |
| Redis 키 | 16종 prefix(`RedisKeys`) |
| 설정 | `.env` 키 55종 / `RequiredPropertiesValidator` 10종 / `SecurityConfigValidator`(JWT·카카오 secret) |

---

## PHASE A. 의존성 취약점  - ⏳ 실행 중

- `./gradlew dependencyCheckAnalyze`를 2026-09-10 23:5x에 시작했으나 **`NVD_API_KEY`가 없어 NVD 동기화가 수십 분~수 시간** 걸린다(플러그인 경고 그대로). 리포트가 만들어지면 이 절을 갱신한다.
- 플러그인 설정은 적절: CVSS 7.0+ 빌드 실패, 테스트 클래스패스 제외, HTML·JSON 출력, suppress 파일 분리.
- **A-1 🟡 스캔이 한 번도 완주된 적이 없다.** `build/reports/dependency-check-report.*`가 없고 docs에도 결과 기록이 없다. `NVD_API_KEY`(무료 발급)를 `.env.dev`가 아닌 개발자 셸 환경변수로 넣고 월 1회 돌리는 루틴이 필요하다.
- 버전 격차(스캔 결과와 함께 갱신 예정): Spring Boot 4.0.5 · firebase-admin 9.4.3 · solapi 1.0.3 · dependencycheck 플러그인 10.0.4.

---

## PHASE B. 동시성  [concurrency-review]

| 항목 | 확인 | 결과 |
|---|---|---|
| 스케줄러 스레드 | `spring.task.scheduling.pool.size` 미설정 → **기본 1스레드**. 5개 `@Scheduled` + `AiLiveStreamSubscriber`의 재접속 예약(`taskScheduler.schedule`)이 한 스레드를 공유 | **B-1 🟡** |
| 알림 executor 포화 정책 | `CallerRunsPolicy`: 큐 100·스레드 10을 넘으면 **호출 스레드**(HTTP 요청 스레드, AI WS 수신 스레드)가 발송을 동기 실행 | **B-2 🟡** |
| 종료 시 큐 처리 | `setWaitForTasksToCompleteOnShutdown` 미설정 → 재배포 순간 큐에 남은 알림(SOS 포함) 유실 | **B-3 🟡** |
| 선점 후 발송 UNIQUE 경합 | 환경당 인스턴스 1개, 두 환경은 DB가 달라 교차 중복 없음. FCM 프로젝트·Solapi 계정은 공유하지만 토큰·전화번호 집합이 DB별이라 중복 발송 경로 없음 | PASS |
| `AiLiveStreamSubscriber` | `volatile session`, `ConcurrentHashMap.newKeySet()`, 재접속 1회 예약(중복 없음), 백오프 2→60초 상한 | PASS |
| `@Async` 예외 | 리스너 대부분 try/catch, 나머지는 `SimpleAsyncUncaughtExceptionHandler`가 로그 | PASS |
| 선점 트랜잭션 경계 | Planner `@Transactional` 커밋 후 Service가 밖에서 dispatch(복약·재촉·미복용 3곳 동일) | PASS |

**B-1.** 복약 Planner가 느려지면(회원 증가) 재촉·스윕·AI 재접속이 뒤로 밀린다. 특히 AI WS 재접속이 밀리면 그 사이 화재 신호를 못 받는다. `spring.task.scheduling.pool.size: 3` 정도면 충분하다.

**B-2.** `@Async`를 둔 이유가 "발송 지연이 AI 신호 처리 스레드를 붙잡지 않도록"인데, 포화 시점(화재 다발 + 보호자 다수)에 정확히 그 스레드가 FCM·SMS 응답을 기다리게 된다. 큐를 늘리고(`500`) 거부 시 `AbortPolicy` + ERROR 로그로 바꾸면 최소한 어디서 유실됐는지 남는다. 알림을 버리는 정책이 싫다면 AI 경로만 별도 executor.

**B-3.** CD가 `docker compose up -d --build`로 컨테이너를 교체하므로 배포마다 큐가 비워진다. `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(20)`로 우아한 종료. `server.shutdown: graceful`도 함께.

---

## PHASE C. 외부 연동 회복력

| 연동 | 타임아웃 | 실패 시 | 킬 스위치 | 결과 |
|---|---|---|---|---|
| 카카오 OAuth (`KakaoOAuthClient`) | connect·read 명시 ✓ | 로그인 실패 응답 | - | PASS |
| FCM (`FcmService`) | SDK 기본값 | `false` 반환 → SOS는 SMS 폴백, 화재는 `[NOTIFY-UNDELIVERED]` WARN | - | PASS |
| Solapi SMS·알림톡 | SDK 기본값 | 예외 → 채널 격리 로그, `disableSms=true` 고정 | `ALIMTALK_ENABLED` | PASS |
| SMTP (`spring.mail`) | **미설정** | 비밀번호 재설정 메일 발송이 요청 스레드에서 동기 대기 | - | **C-1 🟡** |
| 파일 서버 (`FileServerClient`) | **미설정** (`RestClient.builder()` 기본) | 프로필 이미지 업로드·삭제가 요청 스레드에서 대기 | - | **C-2 🟡** |
| AI WS (`AiLiveStreamSubscriber`) | 핸드셰이크 SDK 기본값 | 백오프 재접속, 기동 미차단, 대시보드 `aiConnected=false` | `ANOMALY_ENABLED`·`AI_API_KEY` | PASS |

**C-1.** Gmail SMTP가 응답을 늦추면 `/find-password/email/send`가 Tomcat 스레드를 붙든다. rate limit이 있어 폭주는 막지만 정상 트래픽도 같이 느려진다. `spring.mail.properties.mail.smtp.connectiontimeout/timeout/writetimeout`(각 5~10초) 추가.

**C-2.** 카카오 클라이언트가 이미 같은 이유로 타임아웃을 둔 전례가 있다. `SimpleClientHttpRequestFactory`로 connect 3초 · read 10초.

**필수 알림 보장 한 줄 결론**: SOS는 FCM 실패 → SMS 폴백까지 있어 Solapi까지 동시에 죽어야 유실된다. 화재는 정책상 폴백이 없어 **FCM 장애 = 유실**이고 흔적은 `[NOTIFY-UNDELIVERED]` WARN뿐이다. 이건 결함이 아니라 2026-07-14 결정("문자는 사용자 선택")의 대가이며, 문서에 이미 있다.

**시크릿·설정 검증**: 필수 10키 fail-fast + JWT·카카오 secret 약한 값 거부 + `FcmConfig` 자체 fail-fast. `AI_API_KEY` 미설정은 의도적으로 기동을 막지 않는다. 나머지 45키는 기본값 보유 - PASS.

---

## PHASE D. 성능·JPA  [performance-smell-detection · jpa-patterns]

- `open-in-view: false` · `ddl-auto: validate` · `show-sql: false` · 엔티티 간 관계 매핑 0건(ID 참조 + 배치 조회 관례) - **PASS**. N+1이 구조적으로 나기 어렵다.
- 인덱스 커버리지(V1~V50 누적 47개): connection 4종 · users 3종+UNIQUE · refresh_token(user_id, expires_at) · access_log 3종 · inquiry 3종 · sos_event(ward, created) · camera(ward, session UNIQUE) · medication partial 3종 · anomaly_incident 3종 · reminder/summary 로그 UNIQUE. 주요 조회 경로 전부 커버.
- **D-1 🟢** `fcm_token.updated_at` 인덱스 없음 → 일 1회 `deleteStaleTokens` 풀스캔. 토큰 수백 건 규모라 무해.
- **D-2 🟢** `anomaly_incident.started_at` 단독 인덱스 없음 → 대시보드 오늘치 조회가 풀스캔. 하루치라 무해, 이력이 수만 건이 되면 추가.
- **D-3 🟢** 미복용 Planner가 발송 창(보호자 최소~최대 시각 + 마감) 동안 **매 분** `doseTime <= now`인 약 전량을 로드한다. MIN/MAX 게이트가 창 밖 스캔은 막지만 창 안에서는 회원 수에 비례. 수백 명 규모까지는 문제없고, 그 이상이면 "오늘 이미 보낸 (보호자, 피보호자)"를 쿼리 단계에서 제외하는 개선 여지.

---

## PHASE E. 운영 설정·로깅  [logging-patterns]

| 항목 | 확인 | 결과 |
|---|---|---|
| Swagger 노출 | `SWAGGER_ENABLED` 기본 `true`, `/v3/api-docs`·`/swagger-ui/**` permitAll. **실사용 도메인 `api.devdmu.gosky.kr`에서 무인증 200 확인** | **E-1 🟡** |
| actuator | `health,info`만, `show-details: never`, probe 활성 | PASS |
| 보안 헤더 | Spring Security 기본(nosniff·frame DENY·no-cache). HSTS는 프록시 담당, CSP는 API 서버라 불요 | PASS |
| CORS | 명시 오리진 + credentials, WS도 같은 목록 | PASS |
| 로그 PII | 애플리케이션 로그는 userId 위주. **단 `AdminAuditLogService.log`가 detail(이름·이메일 포함)을 INFO로 출력** | **E-2 🟡** |
| MDC | `trace`·`user` 패턴 적용, 구조화 포맷 스위치 존재 | PASS |
| Docker | temurin 21-jre, TZ, curl 헬스체크, 로그 로테이션 10MB×5. root 실행·JVM 옵션 없음(컨테이너 기본 25% heap) | E-3 🟢 |
| Redis 키 | 16종 중 `CHARACTER_EXPRESSION`·`ADMIN_DASHBOARD_SUMMARY` **미사용** | E-4 🟢 |
| cron 타임존 | `TokenCleanupScheduler` cron에 zone 없음(FCM 정리는 있음). 컨테이너 TZ로 커버 | E-5 🟢 |

**E-1.** 전 엔드포인트·DTO 스키마·에러 문구·정책 설명(설명문에 보안 결정 근거까지 적혀 있다)이 누구에게나 보인다. 졸업 프로젝트 시연 편의라면 수용 가능하나, 최소한 실사용 서버는 `SWAGGER_ENABLED=false`로 끄고 vkcs(도메인 없음)에서만 켜는 것이 맞다. **결정 필요.**

**E-2.** 감사 로그 DB 행은 관리자 전용이지만, 같은 내용이 컨테이너 stdout(json-file 50MB 보존)에도 남아 이메일·이름이 로그 수집 경로로 샌다. SLF4J 출력은 `action·targetId`까지만 남기고 detail은 DB에만.

---

## PHASE F. 아키텍처  [architecture-review]

의존 그래프(패키지 import 기준):

```
user ← auth, announcement, camera, connection, inquiry, medication, notification, sos, anomaly, admin
connection ← camera, medication, sos, anomaly, admin
notification ← connection, inquiry, medication, sos, anomaly
admin ↔ anomaly, admin ↔ inquiry        ← F-1
global/util/UserIdGenerator → domain.user   ← F-2
notification → auth (SmsSender)             ← F-3
```

**F-1 🟢** `admin ↔ anomaly`·`admin ↔ inquiry` **패키지 순환**: `AdminAnomalyService`·`AdminInquiryService`가 `admin.service.AdminAuditLogService`를 쓰고, `admin`은 anomaly·inquiry의 리포지토리·DTO를 쓴다. 빈 순환은 아니지만 "관리자 컨트롤러가 어느 패키지에 사나"가 도메인마다 달라졌다(회원관리는 `admin/`, 이상감지는 `anomaly/controller/Admin*`). `AdminAuditLogService`를 `global/audit`(도메인 로직 없음, 순수 기록)로 옮기면 순환이 풀린다.

**F-2 🟢** `global/util/UserIdGenerator`가 `UserRepository`에 의존 - CLAUDE.md "도메인 로직을 global에 두지 말 것"의 경계 사례. `domain/user`로 이동.

**F-3 🟢** 알림 도메인이 인증 도메인의 `SmsSender`를 재사용. SMS 발송기는 인프라 어댑터라 `global/client` 또는 `notification`이 소유하고 auth가 가져다 쓰는 방향이 맞다.

이벤트 vs 직접 호출 기준은 일관: 트랜잭션 밖 부수효과(알림·토큰 정리)는 이벤트, 상태 전이는 직접 호출.

---

## 이슈 요약

| ID | 등급 | 축 | 내용 | 시점 |
|---|---|---|---|---|
| A-1 | 🟡 | 의존성 | 취약점 스캔 미완주(NVD 키 없음) - 결과 대기 | 발표 전 |
| B-1 | 🟡 | 동시성 | 스케줄러 단일 스레드에 AI 재접속까지 공유 | 발표 전(설정 1줄) |
| B-2 | 🟡 | 동시성 | 알림 executor 포화 시 AI 수신 스레드가 발송을 동기 실행 | 이후 |
| B-3 | 🟡 | 동시성 | 종료 시 알림 큐 유실(우아한 종료 없음) | 발표 전(설정 3줄) |
| C-1 | 🟡 | 회복력 | SMTP 타임아웃 없음 | 발표 전(설정 3줄) |
| C-2 | 🟡 | 회복력 | 파일 서버 클라이언트 타임아웃 없음 | 이후 |
| E-1 | 🟡 | 운영 | 실사용 도메인 Swagger 무인증 공개 | **결정 필요** |
| E-2 | 🟡 | 로깅 | 감사 로그 detail(이름·이메일) INFO 출력 | 발표 전 |
| D-1~3 · E-3~5 · F-1~3 | 🟢 | - | 인덱스 2·전량 조회 1·Docker·미사용 키·cron zone·경계 3 | 이후 |

## 종합 판정

**⚠️ 잔여 이슈(Critical·High 없음)**. 코드 결함보다 **설정 부재**가 대부분이다(스케줄러 풀·우아한 종료·SMTP/파일 타임아웃은 합쳐서 설정 10줄). 결정이 필요한 것은 E-1(Swagger 공개) 하나, 결과 대기는 A-1 하나다.

### 수정용 커밋 메시지 초안

```
chore: 운영 설정 보강 - 스케줄러 풀·우아한 종료·SMTP/파일서버 타임아웃·감사 로그 PII 마스킹 (기술 점검 B-1·B-3·C-1·C-2·E-2)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```
