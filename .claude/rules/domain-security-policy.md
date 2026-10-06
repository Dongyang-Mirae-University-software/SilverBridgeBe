# 도메인 보안 정책 메모

> 코드만으로는 드러나지 않는 보안 정책 결정의 의도·이력을 기록한다.
> (이 파일은 `paths:` 스코프 없이 매 세션 로드된다. CLAUDE.md §8에서 요약을 참조한다.)

## 비밀번호 재설정 — 가입 여부 명시 응답 (2026-05-23 갱신)

- **의도**: 시니어/4050 타겟 UX 우선 — "메일이 안 와요" 이탈 감소.
- **User Enumeration 정책 변경**: 기존 `find-password` send/resend는 미가입에도 **항상 200**(enumeration 차단)이었으나, **미가입 404 / 카카오 가입 계정 400**으로 명시 안내하도록 변경.
  - 이메일: 미가입 404("해당 이메일로 가입된 계정이 없습니다"), 카카오 400.
  - SMS: 이름+전화번호 미일치 404("사용자를 찾을 수 없습니다"), 카카오 400.
- **노출 보완 (Rate Limit 등)**:
  - `pw-reset-email`/`pw-reset-sms` send·resend: IP **이중 윈도우 1분 10회 / 1시간 30회**(초과 429).
  - per-email 발송 상한 `password:email:sendcount` **1시간 10회**(SMS `sms:sendcount` A-M3와 대칭).
  - 미가입 시 `[PW-RESET]` **WARN 로깅**(마스킹 식별자+IP), 미가입자 SMS/메일 **발송 전 차단**.
  - `/password/reset`(3단계)는 코드 선검증(A-M1) 유지 — **변경 없음**.
- **거부안**: 응답 시간 정규화(존재 여부를 의도적으로 노출 → 모순), 의심 IP 블랙리스트(공용 NAT 시니어 오차단), CAPTCHA(시니어 부담).
- 상세: `docs/(2026-05-23) policy-change-password-reset.md`, `docs/(2026-05-23) audit-report-auth-password-reset.md`.

## 카카오 OAuth — Client Secret 적용 (2026-05-25)

- **의도**: 인가코드 탈취 시 토큰 발급을 차단(REST API Key 단독 대비 보안 강화).
- **변경**: 백엔드 토큰 교환(`POST kauth.kakao.com/oauth/token`) 요청에 `client_secret` 추가.
- **환경변수**: `KAKAO_CLIENT_SECRET` — `.env.dev`로만 주입(코드/Git **평문 비노출**). `application.yaml`은 `${KAKAO_CLIENT_SECRET:}`로 매핑.
- **시작 시 검증**: 존재 여부 → `RequiredPropertiesValidator`(11개 키), 길이(≥32)·placeholder/약한 값 → `SecurityConfigValidator`. 미설정·약한 값이면 시작 중단(fail-fast).
- **운영 적용 전제**: 카카오 콘솔 [보안 > Client Secret] 코드 발급 + **"사용 함" 활성화** 필요. 활성화 없이 secret만 보내면 무시되고, 활성화 후 secret 누락 시 토큰 발급 실패.
- 상세: `docs/(2026-05-25) feature-kakao-client-secret.md`.

## 회원 탈퇴 — 영구 삭제(hard delete) 전환 (2026-05-26)

- **의도**: 기존 비활성화(soft delete)는 user 행이 남아 탈퇴 후 같은 이메일/전화번호로 **재가입이 막히던** 문제가 있었음(`existsByPhone`/`existsByEmail`가 INACTIVE 행도 포함). 탈퇴 시 계정·관련 데이터를 **영구 삭제**하여 재가입을 허용.
- **변경**: `withdraw()`(본인확인+`deactivate()`+`UserWithdrawnEvent`) 커밋 **후**, 컨트롤러가 `purgeWithdrawnUser()`로 user 행을 **hard delete** (이전엔 `deactivate()`로 INACTIVE 전환만).
  - **단계 분리 이유**: 정리 로직(연결 해제+상대 알림·FCM/refresh 토큰 정리·WITHDRAW 접속로그)이 `UserWithdrawnEvent`의 **AFTER_COMMIT 리스너**로 동작하며 "user 행이 살아있음"을 전제로 함 → 정리 완료 후 별도 트랜잭션에서 삭제.
- **연관 데이터**: users 참조 FK가 이미 `CASCADE`/`SET NULL`이라 **DB 마이그레이션 불필요**.
  - CASCADE 삭제: `connections`·`fcm_tokens`·`refresh_tokens`.
  - `SET NULL`: `access_logs`·`announcements`·`announcement_drafts` — **접속로그는 보안 감사용으로 익명 보존**(완전 삭제 아님). 업로드 프로필 이미지 파일은 커밋 후 제거(카카오 CDN 등 외부 URL이면 파일서버가 무시).
  - **안전 이력의 보존 정책은 둘로 갈린다 (2026-09-11 회귀 재점검 R-3, 의도된 차이)**: `sos_event`는 SET NULL로 **익명 보존**(보호자 이력에 이름 없이 남음), `anomaly_event`·`anomaly_incident`(+응답·재촉 로그)는 V30·V44에서 **CASCADE 삭제**로 확정했다. 문의(`inquiry`)도 CASCADE다. "탈퇴자가 남긴 데이터를 붙들지 않는다"가 기본이고 SOS만 감사 목적 예외다 - 한쪽에 맞추자고 FK를 바꾸지 말 것. 전체 표는 `docs/(2026-09-10) audit-regression-pre-230.md` PHASE B.
- **본인 확인 유지(H-6)**: 일반=비밀번호, 카카오=confirmation "탈퇴" 일치 확인. access token 단독 탈취로 인한 임의 삭제를 차단(soft→hard 전환에도 동일 적용).
- **비가역**: 복구 불가 — 기존 soft delete의 복구·전수 감사 이점은 포기(접속로그 익명 기록만 잔존).
- 상세: PR #181.

## 연결 거절 — 보호자 실시간 알림 추가 (2026-05-28)

- **의도**: 피보호자가 연결 요청을 거절해도 보호자에게 이벤트가 안 가 보호자 웹이 새로고침 전까지 "요청중"에 멈춰 있던 문제. 수락(`ConnectionAcceptedEvent`)·해제(`ConnectionDisconnectedEvent`)와 **비대칭**이던 거절을 동일 패턴으로 정렬.
- **변경**: `refuseConnectionAsWard()`가 `refuse()` 커밋 후 `ConnectionRefusedEvent(connectionId, guardianId)` 발행 → `ConnectionNotificationListener.handleRefused()`(AFTER_COMMIT, `@Async`)가 보호자에게 WebSocket(`connection-refused`) + FCM(`"연결 요청이 거절되었습니다."`) 발송.
- **FCM 문구**: 시니어/4050 타겟 직관성 우선 — "거절되었습니다"로 명확히. 모호한 "종료/해제" 표현은 회피.
- **알림 비대칭 정책(의도된 것)**: 같은 PENDING 종료라도 알림 대상이 다름 — ① **피보호자 거절 → 보호자 알림O**(본인의 명시적 액션), ② **보호자 요청 취소(`cancel`) → 무알림**, ③ **회원 탈퇴 시 PENDING → CANCELLED 무알림**(`tearDownConnectionsOnWithdrawal`). 향후 "일관성" 명목으로 ②③에 알림을 추가하지 말 것 — 거절만 상대에게 통지가 필요한 명시적 거부 행위.
- **인가**: `connection-refused` 토픽은 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 없음) — 별도 등록 불필요.
- **DB 영향 없음**: 상태 전이(`refuse()`)는 기존과 동일, 이벤트 발행만 추가. 마이그레이션 불필요.
- 상세: `docs/(2026-05-28) feature-connection-refused-notification.md`.

## AI 세션 목록 재동기화 - 방송 누락 안전망 (2026-10-01 QA BE-1)

- AI는 세션 **생성·종료 때만** 목록을 broadcast한다. 방송이 한 번 빠지면 등록된 카메라가 에러 없이 감시에서 빠지는데(조용한 침묵), 미등록 세션 로그가 DEBUG라 운영에서도 보이지 않았다.
- `AiLiveStreamSubscriber`가 `anomaly.resync-seconds`(기본 60, 0이면 끔)마다 `list`를 다시 요청한다(연결이 없으면 건너뜀). 놓친 세션은 이때 구독된다. **등록 카메라가 라이브인데 subscribe 전송이 실패하면 WARN**을 남긴다. 미등록 세션은 로그 폭주를 막으려고 DEBUG 그대로다.
- 이 주기 요청을 지우거나 길게 늘리면 방송 누락이 다시 침묵으로 돌아간다. 재접속 백오프·카메라 등록 시 `list` 요청과는 별개 경로다.

## 보호자 실시간 카메라 보기 - 영상은 백엔드가 인가 후 중계한다 (2026-10-03)

- **경위**: FE 무인증 프록시(`/api/streams/**`)가 서버 AI 키를 붙여 AI 전 경로로 넘기고, 보호자 화면이 AI 전체 세션을 그대로 보여 **로그인 없이 누구나 모든 집 영상을 볼 수 있었다**. AI 키도 브라우저 WS URL(`?apiKey=`)에 노출됐다. 2026-07-03 설계의 "백엔드는 영상을 프록시하지 않는다"를 뒤집어 **FE → 백엔드 → AI 완전 중계**로 바꿨다. 상세 `docs/(2026-10-03) feature-ai-stream-relay.md`.
- **불변 규칙 ①(열람 범위)**: 시청·상태·스냅샷·티켓은 **보호자 전용**이고 **ACTIVE 연결된 피보호자의 활성 등록 카메라**만이다. 모든 경로가 `CameraService.getViewableCamera` 한 곳을 지난다(`isActiveConnection`만, `getMyWards` 금지). 위반은 403 `CAMERA_NOT_CONNECTED` + `[IDOR-ATTEMPT]`, 없는·꺼진·백엔드 미등록 세션은 404(미등록은 AI에 송출 중이어도 주인을 모른다). 피보호자용 시청 API를 만들려면 이 정책부터 바꿀 것.
- **불변 규칙 ②(`<img>`는 1회용 티켓)**: 영상 경로(`GET /api/camera/stream/*/mjpeg`)만 permitAll이고 인증은 **한 사용자·한 세션·60초·1회** 티켓(`GETDEL`)이다. **access token을 쿼리로 받지 말 것** - 프록시·접속 로그에 30분짜리 전권 토큰이 남는다. 소비 시점에 계정 상태(`!= ACTIVE` → 403)·토큰 무효화(발급 뒤 무효화 → 401)·연결을 다시 확인한다.
- **불변 규칙 ③(열린 영상도 끊는다)**: 시청 중 60초마다 계정·연결·카메라·무효화를 재확인해 끊고, 영상 1건은 최대 30분이다(AI MJPEG는 세션이 끝나도 스스로 끝나지 않는다). DB 확인이 실패하면 끊고, 무효화 키(Redis) 조회만 실패하면 이어 간다.
- **불변 규칙 ④(AI 키는 서버 안에서만)**: `X-API-Key` 헤더로만 보내고 로그·응답에 남기지 않는다. FE에 AI 주소·키를 다시 내리지 말 것.
- **불변 규칙 ⑤(분석 상태는 WS 전용)**: STOMP `camera-analysis`는 디스패처를 거치지 않아 **푸시·문자·알림 이력이 없다**. 화면 표시용이라 상태가 바뀔 때만·같은 카메라 최소 2초 간격으로 ACTIVE 보호자에게 `sendToUser`로만 보낸다(정지 계정 게이트 적용). `anomaly-detected`(판정을 거친 화재 알림)와 섞지 말고, 이 경로에 푸시를 붙이지 말 것.
- **`camera-analysis`의 `status=null`은 "확인 불가"다 (2026-10-04 점검 M-1)**: AI 연결이 끊기면 상태를 보낸 적 있는 세션에 `status=null`을 보낸다(비우기만 하면 마지막 "정상"이 화면에 남는다 - "모르는 값을 아는 값처럼 보이지 않는다"). 분석이 송출 상태보다 먼저 오면 `running`으로 채워 `null`의 뜻을 하나로 지킨다. **꺼진 카메라에는 보내지 않는다**(L-1, `findActiveOwnerBySessionId`) - 감지·화재 알림은 `is_active`와 무관하게 계속된다(ANOM-G08). AI 호출은 리다이렉트를 따라가지 않는다(L-2, 키 헤더 유출 방지).
- **불변 규칙 ⑥(MJPEG는 Spring 응답 래퍼로 닫지 말 것)**: `SimpleClientHttpResponse.close()`는 본문을 끝까지 읽어(drain) 끝없는 MJPEG에서 영원히 멈춘다 - 자리·요청 스레드 누수. `AiMjpegStream`이 `HttpURLConnection.disconnect()`로 소켓을 바로 끊는다(`AiStreamClientTest`가 고정).
- **호출 전체 시간 제한·속도 제한 (2026-10-04 점검 L-3·L-4)**: AI 목록·상태·스냅샷은 `callTimeout`(8초) 안에 끝낸다 - 헤더 대기는 연결을 끊고, 본문은 읽기 사이에 마감을 확인한다(본문을 읽는 중에는 `disconnect()`가 읽기를 깨우지 못하는 JDK 동작 때문, 최악 = 8초 + 읽기 5초). 마감이 지나면 EOF로 끝났어도 잘린 본문으로 보고 실패시킨다 - 이 확인을 빼지 말 것. 보호자 카메라 API 4종은 보호자 ID 기준 분·시간 속도 제한(목록·상태 30/600, 스냅샷 60/1200, 티켓 20/300)을 인가보다 먼저 건다(Redis 장애 시 fail-open - 시청은 보조 방어로 막지 않는다).
- **실시간 분석 풀의 폐기는 알림 장애가 아니다 (2026-10-04 점검 I-1)**: `liveAnalysisExecutor`는 포화 시 `[LIVE-ANALYSIS-REJECTED]` WARN으로 폐기한다(다음 상태 변화 때 최신 상태가 다시 나간다). 알림 풀의 `[NOTIFY-REJECTED]` ERROR와 섞지 말 것 - 섞으면 진짜 알림 유실이 묻힌다. AI 시각은 응답에 KST로 싣는다(I-3).
- **동시 시청 상한**: 전체 20 / 1인 2(초과 429). 영상 1건이 요청 스레드 1개를 붙들므로 상한을 없애면 SOS·로그인 같은 일반 API까지 멈춘다. 상한·티켓은 단일 인스턴스 기준이다.
- **수용한 한계**: 연결 해제·정지 반영은 최대 60초 지연 / FE가 프록시를 줄이고 `NEXT_PUBLIC_STREAM_WS_URL`을 지우기 전까지 무인증 구멍은 남는다(송출 업로드 경로는 범위 밖이라 프록시에 남음).

## 연결 요청 취소 - 알림은 없고 화면 갱신 신호만 간다 (2026-10-01 QA BE-3)

- 위 비대칭 정책(취소 → 무알림)은 **푸시·문자·알림 이력**에 대한 결정이다. 그런데 신호가 아예 없으면 피보호자가 열어 둔 "요청 온 목록"에 취소된 요청이 남아, 누르면 `CONNECTION_NOT_PENDING` 오류가 났다.
- `cancelPendingAsGuardian`이 `ConnectionRequestCancelledEvent`를 발행하고, 리스너가 피보호자에게 WebSocket **`connection-request-cancelled`(payload `{connectionId}`)만** 보낸다. **디스패처를 거치지 않으므로 FCM·SMS·알림 이력이 생기지 않는다** - 이 경계를 넘어 푸시를 붙이지 말 것.
- 기존 `connection-cancelled`를 재사용하지 말 것 - FE가 그 이벤트를 "연결이 해제되었습니다" 토스트로 보여 줘서 수락 전 요청 취소에는 문구가 거짓이 된다.
- 탈퇴·역할 변경으로 PENDING이 조용히 취소되는 경로는 같은 증상이 있으나 이번에 다루지 않았다(필요해지면 같은 이벤트를 재사용).

## 연결 요청 반복 제한 - 같은 쌍 24시간 쿨다운 (2026-10-02 QA CONN-G04/XCUT-G29)

- **규칙**: 같은 (보호자, 피보호자) 쌍의 요청은 24시간에 **5건까지** 허용하고 **6번째부터 429 `CONNECTION_REQUEST_COOLDOWN`**(`retryAfterSeconds` = 남은 TTL)이다(`ConnectionRequestLimiter.MAX_REQUESTS_PER_PAIR = 5`, 사용자 지시 "반복 5회 이상이면 적용" 후 4→5로 조정). Redis `connection:request:count:{guardianId}:{wardId}`, 24시간 고정 윈도우, Flyway 없음.
- **세는 시점**: 중복(PENDING·ACTIVE) 검사 **뒤**에 센다(409는 세지 않음). 조회→생성→증가 순서라 동시 요청이 한두 건 더 통과할 수 있음은 수용한다.
- **초기화**: 피보호자가 **수락하면 키를 지운다.** 거절·취소에서는 지우지 말 것 - 요청→거절 반복으로 제한이 풀린다.
- **Redis 장애 시 fail-open**(`[CONN-REQUEST-LIMIT-REDIS-DOWN]` WARN). 관리자 강제 연결에는 적용하지 않는다.
- **알림 비대칭 정책 그대로**: 429는 요청을 만들지 않으므로 알림·WS가 없다. 취소·PENDING 종료에 알림을 추가하지 말 것. 피보호자 차단 기능은 만들지 않았다(별도 결정).

## 카카오 가입 access_logs FK 위반 수정 (2026-05-30)

- **의도**: 카카오 가입의 `KAKAO_LOGIN` 접속로그를 가입 트랜잭션 안에서 REQUIRES_NEW로 남기면, 미커밋 user 행을 별도 트랜잭션이 못 봐 `fk_access_logs_user` 위반(SQLState 23503)이 발생. 이를 `DataIntegrityViolationException` 핸들러가 "중복"으로 오표시했음.
- **변경**: `KakaoRegisteredEvent` + `@TransactionalEventListener(AFTER_COMMIT)` 리스너로 접속로그를 **커밋 후** 기록(user 커밋 후라 FK 보장). 공유 `AccessLogService`는 미변경. 예외 핸들러는 SQLState 구분 — 23505(unique)만 409 "중복", FK 등은 500 + 원인 로깅.
- **불변 규칙**: 가입 트랜잭션 내부에서 `accessLogService.log()`(REQUIRES_NEW)를 **직접 호출하지 말 것** — 미커밋 user를 참조해 FK 위반. 접속로그는 AFTER_COMMIT 이벤트로.
- 상세: `docs/(2026-05-30) bugfix-kakao-signup-access-log-fk.md`, PR #185.

## 인증코드/nonce 소비 순서 — "검증 후 마지막 소비" (2026-05-31)

- **의도**: 인증 매개체(SMS 6자리 코드·가입 nonce)의 **검증과 소비(Redis 삭제)를 분리**한다. Redis 삭제는 `@Transactional` 롤백 대상이 아니므로, 검증·비즈니스 처리보다 **먼저 소비하면 이후 단계가 실패해도 인증이 비가역적으로 소모**돼 같은 코드/nonce로 재시도가 막힌다.
- **불변 규칙**: 인증코드/nonce 소비는 **모든 비즈니스 검증·처리가 성공한 "마지막"에** 수행한다.
  - 비번재설정 `confirmReset`: 맨 앞은 비소비형 `verificationCodeValidator.verifyWithoutConsume()`(enumeration 차단 A-M1 유지), 모든 검증·변경 성공 후 마지막에 `verificationCodeValidator.consume()`. 소비형 `verify()`(검증+삭제 결합)를 다운스트림 검증 앞에서 호출하지 말 것.
  - 가입 `AuthService.register`·`KakaoAuthService`: nonce는 비즈니스 검증 후 `smsService.consumeVerification()`으로 마지막 소비(이미 적용됨).
- **버그 이력**: `confirmReset`이 소비형 `verify()`를 맨 앞에서 호출 → 새 비밀번호=현재 비밀번호(`SAME_AS_CURRENT_PASSWORD`) 등 1차 실패 시 코드가 소모돼, 같은 코드 2차 재시도가 `EXPIRED_SMS_CODE`로 막힘. 카카오 가입 nonce 버그와 동일 뿌리(검증/소비 미분리 + Redis 비롤백).
- **공유 컴포넌트**: `VerificationCodeValidator`는 `verify`(소비형)·`verifyWithoutConsume`(비소비형)·`consume`(소비 전용)를 제공. 흐름별로 적절히 조합한다.
- **L-1 경계 (2026-06-06 점검, 의도적 미수정)**: 가입 nonce 소비는 "비즈니스 검증 후"지만 `userRepository.save()` *앞*이다. 이를 `save()` 뒤로 옮기는 단순 변경은 **무효** — User는 assigned-ID라 Hibernate가 INSERT를 커밋(flush)까지 지연시켜, 유니크 위반(`DataIntegrityViolationException`)이 consume *뒤*인 커밋 시점에 터진다. 게다가 가입 `save` 실패는 항상 ① 영구 중복(이메일/전화 — 같은 값 재시도 자체 불가) 또는 ② 서버측 결함(FK·NOT NULL — 재시도 무의미)뿐이라, nonce 보존 실익이 사실상 0(#184/#188이 막은 "1차 실패 후 정상 재입력" 케이스는 이 경로에 없음). → **"일관성" 명목으로 `saveAndFlush`나 nonce 검증/소비 분리를 추가하지 말 것**(복잡도·회귀만 증가).
- 상세: `docs/(2026-05-31) bug-investigation-password-reset-verification.md`, `docs/(2026-06-06) audit-spot-check-kakao-password-bugfix.md`(L-1 분석).

## 회원 탈퇴 — 2단계 사이 실패 복원력 + INACTIVE 불변식 (2026-06-11)

- **의도**: 탈퇴는 `withdraw()`(INACTIVE 전환, 커밋) → AFTER_COMMIT 리스너 3종 → `purgeWithdrawnUser()`(영구 삭제)의 2단계라, 사이에 배포 재시작·인프라 순단이 끼면 INACTIVE 행이 잔존(M-S1-1). 이 상태는 재로그인(INACTIVE)·탈퇴 재시도(토큰 무효화로 401)·재가입(이메일/전화 잔존) 모두 불가한 자가 복구 불능 좀비.
- **변경(3중 방어)**: ① 탈퇴 리스너 3종(auth 토큰정리·connection 해제·notification FCM)을 try/catch로 best-effort화 — 한 리스너 실패가 나머지·purge를 막지 않음(행 정리는 purge FK CASCADE가 최종 담당, 유실 가능한 건 상대 알림·WITHDRAW 접속로그뿐). ② 컨트롤러 purge 실패 시 1회 재시도 + `[WITHDRAW-PURGE-FAILED]` ERROR. ③ `WithdrawnUserPurgeScheduler`(10분 주기)가 `INACTIVE && updated_at < now-10분` 행을 스윕 purge — 좀비는 최대 ~20분 내 자동 회수.
- **불변 규칙 (INACTIVE 불변식)**: `Status.INACTIVE`를 만드는 경로는 **탈퇴(`User.deactivate()`) 단 하나**여야 한다. 스윕이 "오래된 INACTIVE = 좀비"로 판정해 **영구 삭제**하므로, 관리자 계정 제한·휴면 등 다른 용도로 INACTIVE를 재사용하면 **해당 계정이 스윕에 삭제된다**. 그런 기능 도입 시 반드시 별도 상태값(예: RESTRICTED)을 추가할 것. (`user.activate()`는 현재 미사용 — 복구 기능 추가 시에도 동일 주의)
- **수용한 한계**: 스윕 경유 purge는 리스너를 거치지 않아 실패 경로에 한해 상대방 알림·WITHDRAW 감사로그가 유실될 수 있음(`[WITHDRAW-SWEEP]` WARN으로 흔적 보존). 리스너 내부 REQUIRES_NEW 커밋 실패는 try/catch 밖이라 전파되지만 이 경우도 스윕이 회수.
- 상세: `docs/(2026-06-11) audit-full-api-session1.md` M-S1-1.

## IDOR 응답 — 404 위장 → 403 명시 안내 전환 (2026-07-14)

- **의도**: 타인 자원(카메라·문의) 접근 시 기존에는 **404로 위장**해 존재 자체를 숨겼다(enumeration 차단). 그러나 "왜 안 보이지"로 이탈하는 시니어/4050 UX를 우선해, **무슨 일이 일어났는지 그대로 안내**한다. 비밀번호 재설정(2026-05-23)과 **같은 판단**이다.
- **변경**: `CAMERA_NOT_AUTHORIZED`(403, "본인이 등록한 카메라만 사용할 수 있습니다.") 신설, `INQUIRY_NOT_AUTHORIZED`를 404→**403**("본인이 작성한 문의만 볼 수 있습니다.")로 변경. 없는 자원은 **그대로 404**(`*_NOT_FOUND`).
- **수용한 노출**: "그 id의 자원이 존재한다"는 사실만 드러난다. **내용은 주지 않는다**(방 이름·세션ID·문의 본문 미노출).
- **보완**: 타인 자원 접근 시도는 `[IDOR-ATTEMPT]` **WARN 로깅**(userId + 대상 id). 반복 시도 탐지의 근거를 남긴다.
- **불변 규칙**: 403은 "본인 것이 아님"만 알린다 — 응답에 **소유자·내용 정보를 절대 싣지 말 것**. 새 도메인도 같은 형태(`<도메인>_NOT_AUTHORIZED` 403 + `[IDOR-ATTEMPT]` WARN)를 따른다.
- **문구는 수신자 기준으로 (2026-08-06 추가)**: 같은 도메인이라도 **보호자 경로와 피보호자 경로는 ErrorCode를 나눈다**. 복약은 `MEDICATION_NOT_AUTHORIZED`("연결된 피보호자의 복약 정보만…", 보호자용)와 `MEDICATION_NOT_OWNED`("본인의 약만 체크할 수 있습니다.", 피보호자용)로 분리했다 — 피보호자에게 "연결된 피보호자"라고 안내하면 뜻이 통하지 않아, **404 위장을 버린 이유(무슨 일이 일어났는지 그대로 안내) 자체가 무의미해진다**. 상태코드(403)·`[IDOR-ATTEMPT]` WARN·소유자 정보 미포함은 그대로다. 코드를 아끼려고 한 도메인에 하나만 두지 말 것.
- 상세: `docs/(2026-07-14) fix-audit-findings.md`, `docs/(2026-08-06) fix-audit-findings.md`(문구 분리).

## 카카오 알림톡 — 승인 템플릿 없이 발송 금지 (2026-07-14)

- **경위**: "카카오톡 채팅으로 알림"을 원해 검토 — **카카오 푸시**(kapi `/v2/push/*`)는 카카오톡이 아니라 우리 앱 푸시(FCM 대행)라 기존 FCM과 도착지가 같아 폐기, **카카오톡 메시지 API**는 친구 관계·동의가 필요해 부적합. → **알림톡**(Solapi, 전화번호 수신)으로 확정. 이미 쓰는 Solapi 계정·SDK 재사용.
- **불변 규칙**: 알림톡은 **사전 심사에서 승인된 템플릿 문구**만 발송할 수 있다(변수만 치환, 자유 문구·전체 변수 불가). 용도가 다른 템플릿(예: 인증번호)으로 다른 알림을 보내면 문구가 어긋나 **카카오 채널 제재 대상**이 된다. 종류별 승인 템플릿이 없으면 **발송하지 말 것**(`AlimtalkProperties.templateFor()`가 null → 채널 스킵).
- **SMS 대체발송 금지**: Solapi "알림톡 실패 시 SMS 대체발송"은 콘솔·코드 모두 OFF(`KakaoOption.disableSms=true`). 켜면 문자 미선택자에게 과금·발송이 나가 "문자는 사용자 선택"(이상감지 D-2)을 뒤집는다.
- **템플릿 작성 규칙 (2026-07-20, 1차 반려로 확인)**: 알림톡은 "**수신자의 액션에 기반한** 정보성 메시지"만 허용된다. 사실 전달만으로는 부족하고, **수신자가 무슨 행동을 했기에 이 메시지를 받는지**가 본문에 있어야 한다("등록하신·신청하신·가입하신"). 변수 비중이 높아 고정 문구만으로 용도를 알 수 없으면 "변수만으로 이루어진 내용"으로도 걸린다. 새 템플릿을 만들 때 반드시 지킬 것:
  - 본문에 ① 수신자 액션·관계("보호자로 등록하시고 신청하신") ② 서비스명 ③ 발송 트리거("등록하신 카메라에서 감지되어") ④ 수신 설정 변경 안내를 포함한다.
  - **변수 예시값은 검수자 참고 의견에 글로 적는다** — Solapi 등록 화면에는 변수별 예시값 입력란이 없다(카카오 공식 콘솔에는 있음). "사용 변수 목록"의 "내용"은 입력란이 아니라 *변수가 쓰인 위치* 라벨이다.
  - 채널명(`@gosky`)과 본문 서비스명(CareAI)이 달라 브랜드 불일치로 보일 수 있으므로 검수 의견에 관계를 명시한다.
  - ⚠️ **승인 후 문구 수정은 재검수 대상** — 변수는 반드시 `#{}` 형태로 **검수 시점부터** 넣는다. 예시값을 본문에 박아 승인받고 나중에 변수로 바꾸는 순서는 불가능하다.
- **다발성 메시지 규칙 (2026-07-23, 2차 반려로 확인)**: 이상감지처럼 **같은 수신자에게 반복 발송될 수 있는** 알림(=다발성)은, 수신자가 그 반복 수신에 **동의했거나 직접 요청했음**을 본문에 **고정값으로 고지**해야만 승인된다. 발송 사유(1차 반려 보완)만으로는 부족하다.
  - 검수자 제시 예시: *"해당 메시지는 고객님께서 요청하신 이상 감지 알림으로, 설정하신 내용과 다른 상황이 생길 경우 지정하신 보호자 및 피보호자에게 발송됩니다."*
  - **반드시 고정 문구** — `#{}` 변수로 넣으면 검수 시점에 내용을 확인할 수 없어 인정되지 않는다.
  - 문구가 "요청·설정하셨다"고 말하는 이상 **앱에 실제로 그 동의·설정 UI가 있어야** 한다(알림 설정 API `/api/user/me/notification-settings`의 알림톡 ON/OFF가 근거 — 알림톡은 사용자 선택 채널이므로 전제 성립).
  - 이 고지 문구는 **본인용 템플릿에도 동일하게** 필요하다(수신 대상만 바꿔 표현).
- **현황(2026-07-27)**: 이상감지 **보호자용** 템플릿 `KA01TP260715015020754dXeU0ww3my9` — 1·2차 반려 후 **3차 승인 완료**(2026-07-27). 카테고리 `서비스이용 > 이용안내/공지(004001)`, 기본형, 대체발송 OFF. 발신 프로필 `KA01PF240930145539248iUN6bVyplGB`. `pfId`·`templateId`·Solapi 키는 `.env.dev` 주입(평문 커밋 금지).
  - **불변 규칙 — 이상감지 알림톡은 보호자에게만 (2026-07-27 결정·구현)**: 승인 문구가 보호자용이라 피보호자 본인에게 보내면 사실과 어긋난다. **본인용 템플릿은 등록하지 않는다.** 리스너가 본인 수신분을 `ANOMALY_DETECTED_SELF`로 dispatch하고 이 타입에 **알림톡 템플릿 매핑을 두지 않아** 알림톡만 스킵된다(FCM·WS·SMS는 그대로 — 본인 대피 안내 D-1 유지). `application.yaml`의 `templates`에 `ANOMALY_DETECTED_SELF`를 추가하지 말 것.
    - 이 구조의 전제: **채널은 `data["type"]`이 아니라 dispatch된 `NotificationType`으로 템플릿을 고른다**(`NotificationChannel.send(type, ...)`). `data["type"]`은 FE 계약 값이라 라우팅 근거로 쓰지 말 것.
    - 디스패처에 "제외 채널" 파라미터를 추가하는 방식은 거부안 — 호출자가 FCM 강제 발송을 우회할 수 있게 된다.
  - **승인 후 운영 책임**: 카카오 안내대로 발송 책임은 전송자에게 있고, 어뷰징·다수 신고 시 **발신 프로필이 차단**된다. 본문이 "회원님께서 직접 신청하신"이라고 말하는 이상, 알림톡은 **사용자 설정 ON일 때만** 나가야 한다(현재 `FORCED_PUSH_PLUS_SETTINGS`가 이를 보장 — 알림톡을 강제 채널로 승격시키지 말 것).
  - **반려 이력**: 1차(2026-07-20) = 수신 대상·발송 사유 불명확("수신자 액션 기반 정보성 메시지"가 아님). 2차(2026-07-23) = **다발성 메시지 수신 동의 고지 누락**. → 3차는 검수자 제시 예시 문장을 거의 그대로 고정 문구로 넣고, 비어 있던 **검수자 참고 의견**(변수 예시값·발송 대상·동의 획득 경로)을 채워 제출 → 승인.
  - 📄 **제출 문구 원문·차수별 diff는 `docs/(2026-07-23) alimtalk-template-review-history.md`** — 재검수 요청할 때마다 제출 본문 전문을 그 문서에 추가할 것(승인 후 문구 수정은 재검수 대상이라 원문이 없으면 대응 불가).
  - **`#{detectedAt}` 코드 반영 완료(2026-07-23)**: `AnomalyDetectedEvent.detectedAt`(AI `analyzedAt`, nullable) → 리스너가 KST `yyyy-MM-dd HH:mm`로 포맷해 `data["detectedAt"]`에 담고 `application.yaml` `variables`에 등록. null(AI fallback 페이로드)이면 **발송 시각으로 대체 표시**하되 이력 `anomaly_event.detected_at`은 NULL 그대로 둔다(이력에서만 "AI 시각 vs 수신 시각" 구분 유지).
  - **활성화 완료(2026-07-31 확인)** — gosky·vkcs-linux 두 서버의 `.env.dev` 모두 `ALIMTALK_ENABLED=true`이고 `pfId`·`templateId`도 승인 값과 동일하다. 즉 **이상감지 알림톡은 실제로 발송 중**이다(로컬은 코드 기본값 `false`라 발송되지 않는다 — 서버와 별개).

## SOS 동작 설정 — 알림 억제 용도로 쓰지 말 것 (2026-07-23)

- **경위**: 피보호자 환경설정의 "SOS 동작 설정"(3개 옵션)이 UI만 배포되고 동작이 없던 것을 발견 → 설정을 계정 단위로 영속화(`sos_setting`, V32)하면서 의미를 확정했다. 원래 라벨 "119에 바로 연결"은 *보호자 알림 없이* 를 뜻하는 것처럼 읽혔다.
- **불변 규칙**: `SosAction`은 **프론트의 119 연결·안내 흐름**만 정한다. **어떤 값에서도 보호자 알림은 항상 발송된다** — SOS는 `NotificationType.WARD_SOS`(`FORCED_PUSH_WITH_SMS_FALLBACK`)로 사용자 설정을 무시하고 강제 발송하는 필수 알림이고, 이 설정으로 끌 수 없다. `SosNotificationListener`·`NotificationDispatcher`가 `sosAction`을 읽게 만들지 말 것.
  - 값 이름 `CALL_119`는 "알림 없이"가 아니라 "119 즉시 연결"이다. 이름만 보고 알림 분기를 넣지 말 것.
- **거부안**: "119만 걸고 알림은 생략" 옵션 — ① API를 호출하지 않으면 **SOS 이력(`sos_event`)도 안 남아** "알림이 실패해도 이력은 무조건 남는다"는 원칙이 깨지고, ② 호출하면 알림이 나가 라벨이 거짓이 된다. 긴급 SOS에서 보호자 알림을 끄는 선택지 자체가 서비스 취지에 반한다고 판단해 채택하지 않았다.
- **기본값**: 행이 없으면 `CALL_119_AND_NOTIFY`(FE 기존 기본값과 동일) — 백필 마이그레이션 불요.
- 상세: `docs/(2026-07-23) feature-sos-action-setting.md`.

## SOS 이력 - ACTIVE 연결이 유일한 열람 근거 (2026-07-30 / ACK 철회 2026-08-26)

- **경위**: 보호자용 SOS 이력 조회 + 처리 결과(ACK) 추가(`sos_event` 컬럼 확장, V33). SOS는 피보호자의 위치·건강 위기 시점이 드러나는 민감 이력이라 "누가 볼 수 있는지"를 코드 밖에 고정해 둔다.
- **불변 규칙 ①(열람 범위)**: 보호자는 **요청 시점에 ACTIVE 연결인 피보호자**의 이력만 조회할 수 있다. 연결이 해제·거절되면 **과거 이력도 즉시 비공개**가 된다(연결 종료 후 개인정보 잔존 방지). 인가 목록은 `ConnectionService.getActiveWardIds()`·`isActiveConnection()`만 사용한다 - `getMyWards()`는 **PENDING이 섞여 있어 인가 목록으로 쓰면 수락 전 피보호자의 이력이 노출된다**.
  - 연결 없는 대상 접근은 404 위장 대신 **403 `SOS_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN**(2026-07-14 정책과 동일 형태).
  - 피보호자 탈퇴로 `ward_id`가 NULL이 된 익명 이력은 이름 없이 목록에만 실린다(익명 보존은 감사 목적). ⚠️ 익명 이력만 조회될 때 이름 맵으로 `Map.of()`를 반환하면 `get(null)`이 **NPE(500)**를 던진다 - `Collections.emptyMap()`을 쓸 것(2026-08-26 수정).
- **불변 규칙 ②(발생 경로는 알림을 가르지 않는다)**: `sos_event.trigger_type`(`SOS_BUTTON`/`GUARDIAN_CALL`, V39)은 **이력 표시 전용**이다. 보호자에게 직접 전화한 경우에도 **ACTIVE 보호자 전원에게 동일하게 알림이 나간다** - 전화받은 보호자 외 나머지도 상황을 알아야 하기 때문이다. `SosNotificationListener`·`NotificationDispatcher`가 이 값을 읽게 만들지 말 것("직접 전화했으니 알림 생략" 같은 최적화 금지 - 필수 알림 보장에 구멍). 2026-07-23 규칙([SOS 동작 설정])의 연장이다.
- **처리 결과(ACK) 철회 (2026-08-26, V39)**: 보호자가 "안전 확인 / 응급 출동"을 남기던 기능을 **제거했다**. `PATCH /api/guardian/sos/{id}/ack`, WebSocket `sos-acknowledged`, `ack_status`·`ack_by`·`ack_at`·`ack_note` 컬럼, `SosAckStatus`가 모두 사라졌다. SOS 이력은 **"언제·어떤 경로로 발생했는가"까지만** 답한다.
  - 이유: 보호자 화면이 붙은 적이 없어 `ack_status`가 전건 NULL이었다. 그 상태로 관리자 화면에 "미처리 SOS" 지표를 만들면 발생한 SOS 100%를 "보호자 전원 무응답"으로 표시하는 **거짓 경보**가 된다.
  - 되살리려면 **보호자 앱의 처리 결과 입력 화면 연동이 선행**되어야 한다. 화면 없이 컬럼만 다시 넣지 말 것.
  - 삭제 근거: 배포 서버 두 곳 모두 `ack_status`가 채워진 행 0건임을 확인하고 DROP했다(비가역).
- 상세: `docs/(2026-07-30) feature-sos-history-ack.md`(도입), `docs/(2026-08-26) refactor-sos-flow.md`(철회·발생 경로).

## SOS 알림 쿨다운 - 폭주 방지용이며 10초다 (2026-10-05, 30초에서 단축)

- **목적**: 보호자 폰에 같은 알림이 쏟아지는 것(alarm fatigue)을 막는 것이다. **서버 부하 방어가 아니다.** SOS에서는 반복 입력이 위급 신호라 길게 잡지 않는다.
- **값**: `sos.notify-cooldown-seconds`(기본 **10**, 정수가 1~300 밖이면 기본값으로 대체. 빈 값·비숫자는 대체되지 않고 기동 실패 - 다른 설정 클래스와 같은 fail-fast). 코드 상수가 아니라 설정이다(`SosProperties`).
- **불변 규칙**: 쿨다운은 **알림 발송에만** 적용된다 - 이력(`sos_event`)은 쿨다운과 무관하게 전부 저장한다. **푸시·WebSocket·문자 폴백은 단일 쿨다운**을 쓴다(문자만 별도 간격으로 나누지 않았다). 키 구조(피보호자 단위 SET NX EX)·전달 0명이면 해제(SOS-G09)·Redis 장애 시 fail-open은 그대로다. 쿨다운을 이유로 SOS 요청을 429로 막지 말 것.
- **FE와 역할 분담**: 의도치 않은 중복 탭은 FE(전송 중 버튼 비활성화)가, 알림 간격은 서버 쿨다운이 맡는다.
- **대가**: 문자 폴백 대상 보호자는 연타 시 문자가 최대 3배 빈도로 나갈 수 있다. 이상감지 쿨다운(이력 1분·보호자 5분·본인 3분)과는 별개 값이다.
- **문자 폴백 시간당 상한 (2026-10-05, 점검 M-2)**: 대가로 늘어난 문자를 막기 위해 `WARD_SOS`의 **문자 폴백에만** 수신자별 시간당 30건 상한을 둔다(`notification.sms-fallback.max-per-hour`, 0=끔). 쿨다운은 푸시·문자 단일 10초 그대로다. **푸시·WebSocket·SOS 이력은 이 상한과 무관하다** - 초과하면 문자만 생략하고 알림 이력에 `RATE_LIMITED`로 남긴다. SOS 알림 자체를 막는 429가 아니므로 "SOS는 항상 발송" 규칙은 그대로다. 복약·이상감지 등 설정 기반 문자에는 걸지 말 것. Redis 장애 시 fail-open. 문자가 접수되지 않으면(번호 없음·발송사 장애) 센 한 건을 환불한다. 키는 (알림 종류, 수신자)이며 디스패처의 `wardId`(표시 전용)를 쓰지 않는다 - 그래서 한 보호자의 여러 피보호자가 한도를 공유한다(수용). 전원이 상한을 넘으면 SOS 쿨다운이 풀린다(수용, SOS-G09). 증가는 반드시 원자 유틸(`RedisCounter.incrementWithTtl`)로 - `setIfAbsent`+`increment` 분리는 창 만료 경합으로 TTL 없는 영구 키를 만든다. 상세 `docs/(2026-10-05) feature-sos-sms-fallback-cap.md`.
- 상세: `docs/(2026-10-05) fix-sos-notify-cooldown.md`.

## SOS 119 연결 - 화면만 띄우고 발신하지 않는다 (2026-08-26)

- **경위**: 피보호자가 SOS를 누르면 119로 연결되는 흐름을 설계했으나, **학생 프로젝트라 실제 신고가 나가면 안 된다**. 프론트는 **119가 입력된 키패드 화면만** 보여주고 발신하지 않는 것으로 확정했다.
- **불변 규칙**: 백엔드는 119 연결에 관여하지 않는다(종전과 동일). `SosAction`의 세 값(`CALL_119`·`CALL_119_AND_NOTIFY`·`NOTIFY_GUARDIAN_FIRST`)은 **"119 안내 화면을 언제 어떻게 보여줄지"**만 정한다. 값 이름의 `CALL_119`는 "전화를 건다"가 아니라 **"119 화면을 띄운다"**는 뜻이며, 이름만 보고 발신 로직·알림 분기를 넣지 말 것.
  - `tel:119` 링크를 쓰면 다이얼러가 119가 입력된 채 열려 사용자가 통화 버튼을 누르면 **실제로 걸린다**. 그래서 `tel:`이 아니라 **자체 키패드 UI**로 그린다(2026-08-26 결정).
- **어떤 값에서도 보호자 알림은 항상 발송된다** - 2026-07-23 규칙 그대로다. 이 설정으로 SOS 알림을 끌 수 없다.
- **보호자 직접 전화는 이력에 남긴다**: 피보호자가 SOS 화면에서 보호자 카드를 눌러 전화할 때, 프론트가 `POST /api/ward/sos`를 `triggerType: GUARDIAN_CALL`로 함께 호출한다. 호출하지 않으면 그 전화는 이력에 남지 않는다(백엔드가 감지할 방법이 없다).

## 복약 알림 — 역할 분리가 곧 기능 (2026-08-04)

- **경위**: 보호자/피보호자 복약 화면에 대응하는 API 신설(`medication`, V35). 시니어가 약 이름·용량을 입력하는 부담을 덜기 위해 **등록은 보호자**가 하고, "실제로 드셨는지"의 진실원본은 **피보호자의 체크**로 두었다.
- **불변 규칙 ①(역할 분리)**: **약 등록·삭제는 보호자만**, **복용 체크·해제는 피보호자만**이다. 이 분리는 편의 기능이 아니라 요구사항 그 자체다 — 보호자가 대신 체크할 수 있으면 "피보호자가 체크해야 보호자에게 보인다"가 무너져 복용 기록이 추측이 된다. **보호자용 체크 API를 추가하지 말 것**(편의·관리 목적이라도). 컨트롤러가 역할별로 분리되어 있고 클래스 레벨 `@PreAuthorize`가 게이트다.
- **불변 규칙 ②(열람 범위)**: 보호자는 **요청 시점에 ACTIVE 연결**인 피보호자의 복약 정보만 조회·관리할 수 있다. 연결이 해제되면 과거 복약 정보도 즉시 비공개다. 인가 목록은 `ConnectionService.getActiveWardIds()`·`isActiveConnection()`만 쓴다 — `getMyWards()`는 **PENDING이 섞여 있어 수락 전 피보호자의 복약 정보가 노출된다**(SOS 이력 2026-07-30과 동일). 위반은 **403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN**, 없는·삭제된 약은 404.
- **불변 규칙 ③(날짜는 KST)**: "오늘"은 `MedicationClock`(Asia/Seoul)로만 판정한다. 서버·DB 타임존을 따르면 09:00(KST) 이전 체크가 전날로 기록돼 카운트가 되돌아가고, 22:00 취침 전 약이 자정 이후 "오늘 것"으로 남는다.
- **등록 보호자 탈퇴 = 그 보호자가 등록한 약도 중지**(`created_by` FK CASCADE). 초안은 `SET NULL`(약은 피보호자 자산이므로 유지, `connections.initiated_by`의 V2 전례)이었으나 "탈퇴자가 남긴 데이터를 붙들지 않는다"를 우선해 삭제로 확정했다.
  - **단, 조용히 지우지 않는다** — 피보호자는 스스로 약을 등록할 수 없어(규칙①) 남은 보호자가 모르면 복구되지 않는다. `MedicationWithdrawalListener`가 삭제 **전에 건수를 집계**해 **남은 ACTIVE 보호자에게만** 중지 안내를 보낸다(`NotificationType.MEDICATION_STOPPED`). **피보호자 본인에게는 보내지 않는다** — 조치할 수단이 없는 알림은 불안만 준다.
  - 이 리스너는 **동기 AFTER_COMMIT**이어야 한다(`@Async` 금지) — 탈퇴는 커밋 직후 purge가 회원 행을 지우므로, 비동기로 미루면 FK CASCADE가 약을 먼저 삭제해 건수를 셀 수 없다. 예외는 밖으로 내보내지 않는다(best-effort — 전파되면 나머지 리스너·purge까지 막혀 좀비 계정 M-S1-1).
  - **동기 AFTER_COMMIT 리스너가 부르는 쓰기는 `REQUIRES_NEW`여야 한다 (H-1, 2026-09-30 수정)**: 커밋 직후라 탈퇴 트랜잭션 자원이 아직 스레드에 묶여 있어, 기본 전파(REQUIRED)면 이미 커밋된 트랜잭션에 합류해 **쓰기가 커밋되지 않는다**. `MedicationWithdrawalService.removeMedicationsRegisteredBy`·`ConnectionService.tearDownConnectionsOnWithdrawal`이 그 상태였다 - 행은 purge CASCADE가 지워 겉으로 무해했지만, **연결 정리 안에서 발행한 해제 이벤트가 AFTER_COMMIT을 맞지 못해 탈퇴 시 상대방 해제 알림이 한 번도 나가지 않고 있었다**. 실 DB로 재현·고정(`WithdrawalListenerCommitIntegrationTest`). 같은 결함이 `FcmService.deleteAllTokens`(`UserWithdrawalFcmListener`)에도 있어 2026-09-30 영향 범위 점검 M-1로 함께 고쳤다 - 현재 동기 AFTER_COMMIT 리스너 7개가 모두 이 규칙을 따른다. 새 탈퇴 리스너를 만들 때도 같은 규칙이며, `UserAccountEventListener`·`NotificationLogService.record()`가 이미 따르는 형태다.
  - **수용한 한계**: 스윕 purge(`WithdrawnUserPurgeScheduler`) 경로는 리스너를 거치지 않아 안내가 유실되고 약만 삭제된다(WITHDRAW 감사로그·연결 해제 알림과 동일).
- **복용 체크 알림은 WebSocket만**(`medication-taken`). FCM·SMS·알림톡은 보내지 않는다 — 하루 여러 번 일어나는 일상 동작이라 푸시는 소음이다(SOS ACK 2026-07-30과 같은 판단). 새 채널을 붙이자는 요구가 오면 이 판단을 먼저 확인할 것.
- **`medication_setting.alarm_enabled`는 복약 알림 전용**이다. 현재는 보관·조회만 하며 2차(복용 시각 스케줄러 발송)에서 발송 게이트가 된다. **SOS 등 필수 알림을 이 값으로 억제하지 말 것**(SOS 동작 설정 2026-07-23 규칙의 연장).
- 상세: `docs/(2026-08-04) feature-medication-reminder.md`.

### 복약 알림 발송 (2차, 2026-08-05)

- **불변 규칙 ④(선점 후 발송)**: 복약 알림은 `medication_reminder_log`에 발송 기록을 **먼저 커밋한 뒤** 발송한다. 순서를 뒤집어 "보내고 기록"으로 만들면 발송 직후 앱이 죽었을 때 다음 주기에 또 보낸다. 스케줄러가 1분마다 돌기 때문에 기록이 없으면 유예 창(30분) 내내 같은 알림이 30번 나간다. `UNIQUE (medication_id, dose_date, attempt)`가 최종 방어선이며, 이 제약을 완화하지 말 것.
  - 대가로 **발송 실패 시 그 회차는 유실**된다(재시도하지 않는다). 알림이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고, 재알림이 두 번째 기회다.
- **불변 규칙 ⑤(복약 알림톡 금지)**: `MEDICATION_REMINDER`·`MEDICATION_STOPPED`에 **알림톡 템플릿을 매핑하지 말 것**. 복약 알림은 매일 반복되는 전형적인 **다발성 메시지**라, 보내려면 "반복 수신에 동의했음"을 고정 문구로 고지한 별도 템플릿 승인이 필요하다(이상감지 2차 반려 사유, 2026-07-23). 승인 없이 매핑하면 승인 문구와 다른 발송이 되어 발신 프로필 차단 대상이 된다. 현재는 매핑이 없어 조용히 스킵된다(`ANOMALY_DETECTED_SELF`와 같은 구조).
- **불변 규칙 ⑥(유예 창은 자정을 넘지 않는다)**: `graceWindowStart()`는 00:00에서 자른다. 하루를 되감으면 `dose_date`가 달라져 **어제 약을 오늘 날짜로** 보내게 되고, 발송 기록·복용 체크의 날짜 기준이 어긋난다. "23:50 약이 자정 넘겨 안 나간다"를 고치겠다고 되감기를 넣지 말 것 — 그 건은 건너뛰는 것이 의도된 동작이다.
- **재알림은 사용자 선택**(`medication_setting.remind_again_enabled`, 기본 ON). 문자를 켜둔 사용자에게는 한 번 복용에 문자가 2건까지 나가므로 끌 수 있어야 한다. **강제 채널로 승격시키지 말 것** — 복약은 SOS·화재 같은 즉시 대응이 아니라 매일 반복되는 일상이라 사용자의 선택을 뒤집을 근거가 없다.
- **설정 API 하위호환**: `PUT .../medication-setting`의 필드는 모두 **선택**이며 `null`은 "변경하지 않음"이다. 필수로 되돌리면 기존 프론트의 `{alarmEnabled}` 요청이 400으로 깨지거나 재알림 설정이 초기화된다.
- **킬 스위치** `medication.reminder.enabled=false`는 **발송만** 멈춘다 — 등록·체크·조회는 계속 동작해야 한다.
- 상세: `docs/(2026-08-05) feature-medication-reminder-scheduler.md`.

### 미복용 보호자 알림 (3차, 2026-08-05)

- **불변 규칙 ⑦(단정 금지)**: 미복용 알림 문구는 **"체크되지 않았습니다"**여야 하며 **"약을 안 드셨습니다"로 쓰지 말 것**. 체크 누락과 실제 미복용을 서버는 구분할 수 없고, 제3자(보호자)에게 사실이 아닌 통보를 하면 불필요한 걱정과 전화를 만든다. `MedicationMissedAlertServiceTest`가 이 문구를 테스트로 고정한다.
- **불변 규칙 ⑧(집계 상한 = 발송 시각)**: 요약은 **발송 시각까지 복용 시각이 지난 약만** 센다. 취침 전 22:00 약을 21:00 요약에 포함하면 "아직 먹을 때가 아닌 약"이 매일 미복용으로 잡혀 거짓 알림이 나간다. 그 약이 요약에서 빠지는 것은 **의도된 동작**이다. **발송 시각과 집계 상한을 분리하지 말 것** — "시각만 앞당기고 집계는 21:00 기준" 같은 변형은 정확히 이 거짓 경보를 만든다.
  - **발송 시각은 (보호자, 피보호자)별 설정이다 (2026-08-27, V40→V41)**: `guardian_medication_setting.missed_alert_time`(분 단위 자유, `NULL`=미설정 → `medication.reminder.missed-alert.alert-time` 기본값 21:00). 시각을 이르게 고른 보호자는 그날 요약에 담기는 약이 줄어드는데, **그 사실을 알림 본문에 시각으로 밝힌다**("19:30까지 예정된 복약 N건 중"). 시각을 문구에서 빼면 이후에 먹을 약까지 확인된 것처럼 읽히므로 빼지 말 것.
  - **축을 다시 보호자 단위로 되돌리지 말 것 (2026-08-27, V41)**: 발송 시각이 집계 상한을 겸하므로, 피보호자마다 마지막 복약 시각이 다르면 값 하나로는 반드시 누군가 손해를 본다(늦게 드시는 분의 약이 매일 빠지거나, 일찍 끝나는 분의 요약이 밤늦게 도착). UNIQUE는 `(guardian_id, ward_id)`이며 같은 피보호자를 보는 보호자끼리 시각이 다를 수 있다 - "설정이 중복된다"는 이유로 합치지 말 것.
    - 축이 나뉘면서 **남의 피보호자 설정을 건드릴 수 있는 경로**가 되었다. `GET·PUT /api/guardian/ward/{wardId}/medication-alert-setting`은 `isActiveConnection`으로 막고 위반 시 403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN이다(`getMyWards`는 PENDING 혼입이라 금지). 발송용 벌크 조회는 스케줄러 경로라 검증하지 않는다 - 호출자가 이미 `getActiveGuardianIds`로 좁힌 뒤 부른다.
  - `NULL`을 `21:00` 기본값으로 백필하거나 컬럼을 `NOT NULL DEFAULT`로 바꾸지 말 것 — 그러면 서버 기본값을 바꿔도 기존 보호자가 따라오지 않고, "정하지 않았다"와 "21:00을 골랐다"를 구분할 수 없게 된다.
  - 설정 API의 `missedAlertTime`은 `null`=변경 안 함이다(공통 규약). 기본값 복귀는 21:00을 직접 지정하는 것으로 갈음하며, `null`을 초기화 신호로 재해석하지 말 것 — 기존 프론트의 `{missedAlertEnabled}` 단독 요청이 시각을 초기화해 버린다.
  - 발송 창(`deadline-minutes`)은 **보호자 시각 기준**으로 움직이며 자정에서 끊는다. 보호자마다 시각이 달라 "지금이 발송 시각인가"를 값 하나로 판단할 수 없으므로, Planner는 설정 테이블의 `MIN`/`MAX(missed_alert_time)`과 기본값으로 전체 구간을 먼저 잡아 복약 테이블 스캔을 막는다 — 이 게이트를 지우면 스케줄러가 하루 종일 매 분 복약 전체를 훑는다.
- **불변 규칙 ⑨(요약 축)**: 미복용 알림은 **(보호자, 피보호자, 날짜)당 하루 한 건**이다(`uq_medication_missed_alert`). 약 단위로 쪼개 건별 발송으로 바꾸지 말 것 — 알림 피로로 보호자가 앱 알림을 통째로 끄면 **SOS·이상감지 같은 필수 알림까지 함께 죽는다**. 같은 이유로 보호자가 이 알림만 끌 수 있는 설정(`guardian_medication_setting.missed_alert_enabled`)을 제거하거나 강제 채널로 승격시키지 말 것.
- **킬 스위치 독립**: `medication.reminder.missed-alert.enabled`는 **보호자 요약만** 멈춘다. 피보호자의 복용 알림(2차)과 같은 스위치로 묶지 말 것 — 보호자 쪽 문제로 피보호자 복약 알림까지 멈추면 안 된다.
- **설정 축 구분**: `medication_setting`(피보호자 단위 = 무엇을 **보낼지**)과 `guardian_medication_setting`(보호자 단위 = 무엇을 **받을지**)은 축이 다르다. 하나로 합치지 말 것.
- 상세: `docs/(2026-08-05) feature-medication-missed-alert.md`, `docs/(2026-08-27) feature-medication-missed-alert-time.md`(발송 시각 선택), `docs/(2026-08-27) refactor-medication-missed-alert-per-ward.md`(피보호자별 축).

## 이상감지 판정 - 보호자만 판정하고, 재촉은 절제한다 (2026-09-01 / 다수결 전환·관리자 정정 폐지 2026-09-21)

- **경위**: AI가 `danger=true`로 올린 감지가 실제 위험이었는지 서버는 알 수 없다(`confidence`는 "얼마나 불꽃처럼 보이는가"이지 "실제로 불이 났는가"가 아니다). 현장을 아는 보호자가 사후에 판정하게 하고, 관리자는 엇갈린 건만 정정한다. 스키마는 V44(PR #236), API·재촉은 V45(PR ②).
- **불변 규칙 ①(판정 주체)**: 판정은 **보호자만** 한다. 피보호자 본인·관리자용 **판정·정정 API를 만들지 말 것**(관리자 2차 정정은 2026-09-21 폐지 - 아래 "관리자 정정 폐지"). 보호자 1인 1표이며 재호출은 번복(UPDATE)이다.
- **불변 규칙 ②(열람 범위)**: 보호자는 **요청 시점 ACTIVE 연결**인 피보호자의 이상감지 이력만 조회·응답할 수 있다. 연결이 해제되면 과거 이력도 즉시 비공개다. 인가 목록은 `getActiveWardIds`·`isActiveConnection`만 쓴다 - `getMyWards`는 PENDING이 섞여 **수락 전 피보호자의 이력이 노출된다**(SOS·복약과 동일). 위반은 403 `ANOMALY_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN, 없는 상황은 404, 관리자 확정 건은 409 `ANOMALY_ALREADY_RESOLVED`.
- **불변 규칙 ③(응답자 다수결, 동수만 CONFLICTED - 2026-09-21 변경)**: 판정은 **응답한 보호자의 다수결**이다. 미응답은 표에 넣지 않는다. 2:1이면 다수 쪽, **동수(1:1, 2:2)만 `CONFLICTED`**이며 동수는 **보호자들이 다시 응답해 합의하는 것으로만** 풀린다 - 관리자가 대신 정하지 않는다. 번복할 때마다 재계산한다. 보호자 응답 원본은 지우지 않는다.
  - **폐지한 옛 규칙**: 2026-09-01~09-21에는 "다수결 금지 - 답이 하나라도 갈리면 CONFLICTED, 관리자가 정정"이었다. 불일치 자체를 관리자 정보로 보존하려던 것인데, 운영자가 현장을 모르는 채 가족의 판정을 확정하는 구조가 맞지 않다고 판단해 **가족 합의**로 바꿨다(사용자 결정).
  - **수용한 대가**: ① 2:1은 소수 의견이 상태값에서 사라진다 - 관리자 목록의 `feedbacks`(응답 원본)로만 보인다. ② 합의되지 않은 동수는 마감 후에도 영구 `CONFLICTED`로 남는다.
  - "소수 의견이 사라진다"는 이유로 다수결 금지로 되돌리거나, "동수가 안 풀린다"는 이유로 관리자 정정을 되살리지 말 것 - 둘 다 이번에 명시적으로 버린 선택이다.
- **동수 재확인 안내 (2026-09-21, V52)**: 상황이 동수가 되면 **그 상황에 응답한 보호자**에게 `ANOMALY_REVIEW_CONFLICTED`(FCM, "다른 보호자와 판정이 다릅니다. 다시 확인해 주세요.")를 **보호자당 한 번** 보낸다.
  - **방금 답해 동수를 만든 보호자는 받지 않는다** - 응답 API가 이미 `CONFLICTED`를 돌려줬다. 응답 트랜잭션이 `anomaly_review_conflict_log`에 `sent=false` 행을 남겨 스케줄러가 건너뛴다.
  - **미응답 보호자는 대상이 아니다** - 의도된 동작(ANOM-G06, 2026-10-02 명시): 건별 재촉은 `PENDING`일 때만 가고 동수는 한 명 이상이 답한 뒤의 상태라, **동수가 된 상황의 미응답 보호자에게는 동수 안내도 재촉도 가지 않는다**(규칙 ⑤ "한 명이 답하면 나머지 재촉 중단"의 연장). 그 보호자가 앱에서 직접 열어 답하는 경로는 열려 있다. 안내 대상을 미응답자까지 넓히지 말 것.
  - 재촉과 같은 절제 원칙: `SETTINGS_ONLY`·FCM만·재촉 끄기 설정 존중·ACTIVE 연결만·야간 미루기·선점 후 발송·`UNIQUE (incident_id, guardian_id)`·알림톡 매핑 금지·마감 3일. 동수가 안 풀려도 **추가 재촉은 없다.**
  - **누가 무엇이라 답했는지 문구에 싣지 말 것** - 가족 사이의 의견 차이를 푸시로 노출하지 않는다.
  - 규칙 변경 시점의 기존 동수 건은 V52가 응답자 전원을 `sent=false`로 기록해 **안내하지 않는다**(배포 직후 과거 건으로 푸시가 몰리지 않게).
- **불변 규칙 ④(판정은 알림을 되돌리지 않는다)**: 오탐으로 표시해도 **정정 알림을 발송하지 않는다.** "아까 그건 아니었습니다"를 다시 푸시하면 알림이 두 배가 되고 다음 진짜 경보의 신뢰만 깎인다.
- **불변 규칙 ⑤(재촉은 절제한다)**: 미응답 재촉은 **상황이 닫힌 뒤 1시간 → 건별 FCM 1회 → 이후 하루 1회 요약(20:00 KST)**이고 마감은 상황 시작 + 3일이다. 답할 때까지 계속 보내면 보호자가 앱 알림을 통째로 꺼버리고, **그때 그 집의 SOS·화재 알림까지 함께 죽는다**(복약 미복용 요약 불변 규칙 ⑨와 같은 판단).
  - **강제 채널로 승격시키지 말 것.** `ANOMALY_REVIEW_REQUIRED`는 `SETTINGS_ONLY`이고 보호자별 끄기(`guardian_anomaly_setting.review_reminder_enabled`, 기본 ON)가 있어야 한다. 재촉은 생명이 걸린 즉시 대응이 아니라 사후 확인 요청이다.
  - **시작 시점을 상황이 닫히기 전으로 당기지 말 것** - 대응 중인 사람에게 판정을 묻는 꼴이 된다. "닫혔다"는 마지막 감지 + `incident-merge-minutes`다.
  - **한 명이 응답해 PENDING을 벗어나면 나머지 재촉은 중단**하되 **응답 API는 계속 열어둔다**. "전원 응답을 받아야 엇갈림을 안다"는 이유로 뒤집지 말 것 - 나중에 다른 보호자가 눌러 다수결이 바뀌거나 동수가 되는 경로는 그대로 살아 있고, 알림량만 1/3이 된다.
  - **야간(22:00~08:00 KST) 억제는 버리기가 아니라 미루기**다. 후보 조건이 남아 아침 첫 주기에 다시 잡힌다. ⚠️ **이상감지 발생 알림 본체는 억제 대상이 아니다** - 화재는 밤에도 즉시 알려야 한다.
  - **선점 후 발송**(기록 먼저 커밋 → 발송). 뒤집으면 5분 주기 스케줄러가 마감 내내 같은 재촉을 반복한다. `UNIQUE (incident_id, guardian_id)`·`UNIQUE (guardian_id, summary_date)`를 완화하지 말 것.
  - **알림톡 매핑을 두지 말 것** - 재촉은 전형적인 다발성 메시지라 "반복 수신에 동의했음"을 고정 문구로 고지한 별도 템플릿 승인이 필요하다(2026-07-23 2차 반려 사유). 현재는 매핑이 없어 조용히 스킵된다.
- **불변 규칙 ⑥(문구는 단정하지 않는다)**: 재촉 문구는 **"화재 감지가 있었습니다. 실제 상황이었는지 확인해 주세요"**여야 하며 "화재가 발생했습니다"로 쓰지 말 것. 실제 위험이었는지는 아직 아무도 모르고, 그걸 묻는 것이 이 알림의 목적이다. 단정하면 지난 일로 놀라게 만든다(복약 "체크되지 않았습니다"와 같은 판단).
- **종류별 허용 채널 (공용 알림 인프라, 2026-09-01 신설)**: `NotificationType`이 *그 종류가 설정 기반 발송에 쓸 수 있는 채널*을 선언하고, 디스패처가 **사용자가 켠 채널 ∩ 허용 채널**로 발송한다. 기본값은 전 채널이라 기존 종류는 무변경이다.
  - ⚠️ **강제 채널(FCM)은 이 값으로 줄어들지 않는다.** 교집합은 *설정에서 나온 집합*에만 적용한다 - 강제 경로에 끼워 넣으면 SOS·화재의 푸시 보장에 구멍이 생긴다.
  - 이것은 **타입에 박힌 선언**이지 호출자가 넘기는 파라미터가 아니다. 2026-07-27에 거부한 "디스패처 제외 채널 파라미터"(호출자가 강제 발송을 우회할 수 있다)와 혼동하지 말 것.
- **관리자 지표 (PR ③ 구현 완료 2026-09-02)**: 오탐률은 **응답률과 함께** 표시한다. 재촉해도 응답률은 100%가 되지 않아, 오탐 건수만 띄우면 분모가 거짓이 된다. SOS 처리결과(ACK)가 전건 NULL인 채 "무응답 100%"로 읽힐 뻔한 함정과 같다. `GET /api/admin/dashboard/safety`의 `todayAnomaly.review`가 pending·real·falseAlarm·conflicted **네 값을 모두** 내리는 이유가 이것이다 - 응답 수는 `total - pending`이다. **오탐 건수만 단독으로 노출하는 응답을 만들지 말 것.**
  - **모르는 값을 0으로 채우지 말 것 (2026-09-02)**: 대시보드의 `streamingCameras`·`disconnectedCameras`는 AI WS가 끊기면 **`null`**(알 수 없음)이다. `Camera`에는 스트리밍 상태 컬럼이 없어 이 값은 AI 구독 집합에서 파생되는데, 끊긴 상태에서 0을 내려보내면 **우리 수신기의 장애가 현장 카메라 전멸로** 표시된다. 같은 이유로 미답변 문의가 0건일 때 `longestWaitingHours`도 0이 아닌 `null`이다. 편의를 이유로 `null`을 0으로 바꾸지 말 것.
  - **0건인 유형은 항목을 만들지 말 것**: `byType`에는 실제로 집계된 유형만 담는다. "낙상 0건"은 안전하다는 뜻이 아니라 **AI 모델이 없다**는 뜻이라, 숫자로 보여주면 정확히 반대로 읽힌다.
  - **대시보드 조회는 감사 로그(`admin_audit_log`)에 남기지 않는다**: 집계 숫자만 반환해 개인 식별 정보가 없고, 30초 폴링 화면이라 기록하면 공지 수정 같은 실제 조작 이력이 묻힌다. 다만 **개인 이력을 변경하는 관리자 API(회원 수정·강제 연결 등)는 반드시 남긴다** - 이 예외를 확대 적용하지 말 것. 조회(목록·상세·이상감지 로그)는 남기지 않는다 - 페이징·폴링 조회까지 기록하면 실제 조작 이력이 묻힌다(2026-09-10 점검 M-4에서 문구 정정. 옛 문구 "열람하는"은 구현과 달랐다).
- **관리자 정정 폐지 (2026-09-21, V52)**: 2026-09-02(PR #241, V46)에 만든 `PATCH /api/admin/anomaly/{incidentId}/review`를 **제거했다.** 관리자 이상감지 화면은 **조회 전용**이다(`GET /api/admin/anomaly`, 연결 여부로 좁히지 않음, 조회라 감사 로그 없음).
  - 함께 사라진 것: `AdminAnomalyReviewRequest`, `AnomalyIncident.resolveByAdmin`·`isAdminResolved`와 재계산 잠금, `ANOMALY_ALREADY_RESOLVED`(409)·`ANOMALY_INVALID_REVIEW_STATUS`(400), 응답 필드 `resolvedByAdmin`(보호자)·`resolvedBy`·`resolvedAt`·`reviewNote`(관리자).
  - **남겨 둔 것**: DB 컬럼 `resolved_by`·`resolved_at`·`review_note`(매핑만 해제, DROP은 비가역이라 별도 후속) / `AdminAuditAction.ANOMALY_REVIEW_RESOLVE`와 CHECK(폐지 전 감사 로그 행 보존 - 빼면 CHECK 재정의가 기존 행 때문에 실패한다. 새로 기록되지는 않는다).
  - V52가 기존 판정을 **관리자 정정 건까지 포함해** 보호자 응답만으로 재계산했다(비가역).
  - `AdminAnomalyControllerSecurityTest`가 이 컨트롤러에 쓰기 매핑이 없음을 고정한다 - 관리자 판정 API를 되살리려면 이 정책부터 바꿀 것.
- **알려진 한계 - 판정 집계 (2026-09-21 보류 결정 / E-2 해결 2026-09-30)**: 다수결 전환 영향 범위 점검의 E-2·E-3이다. 둘 다 다수결 전환 **이전부터 있던** 드문 경우다. E-2는 2026-09-30에 고쳤고, **E-3은 여전히 보류·수용**이다. 실사용 데이터에서 문제가 보이면 다시 판단한다.
  - **E-2 동시 응답 덮어쓰기 - 해결 (2026-09-30)**: 두 보호자가 같은 순간 반대로 응답하면 각자 자기 표만 보고 판정을 확정해 동수가 `REAL`·`FALSE_ALARM`으로 남던 문제. 응답은 이제 상황 행을 **쓰기 잠금**(`AnomalyIncidentRepository.findByIdForUpdate`, `SELECT … FOR UPDATE`)으로 읽어 같은 상황의 응답이 한 줄로 선다. **낙관적 잠금(`@Version`)으로 바꾸지 말 것** - 진 쪽 보호자의 응답이 실패해 재시도를 요구한다. 실 DB 동시 응답 20회 반복으로 고정(`AnomalyReviewConcurrencyIntegrationTest`, 잠금 제거 시 첫 회에 실패함을 확인).
  - **감지 승계가 판정을 되돌리던 문제 - 해결 (2026-09-30, E-2 작업 중 발견)**: `AnomalyIncident`는 AI 감지 승계(`addDetection`)와 보호자 응답(`applyReviewStatus`)이 따로 고치는데, 전체 컬럼 UPDATE라 감지 쪽이 읽어 둔 옛 `review_status`를 다시 써서 **화재가 이어지는 동안 보호자가 답한 판정이 `PENDING`으로 돌아갔다**(E-2보다 흔하다 - 보호자는 알림을 받자마자 답한다). `@DynamicUpdate`로 바뀐 컬럼만 쓰게 했다. **떼지 말 것** - 감지 쪽에 잠금을 거는 대안은 AI 수신 스레드를 보호자 응답 트랜잭션에 묶어 거부했다.
  - **E-3 연결이 끝난 보호자의 표**: 연결을 해제한 보호자의 응답은 지워지지 않아 계속 표에 들어가고, 탈퇴로 응답이 CASCADE 삭제돼도 판정은 다시 계산되지 않는다(예: 1:1 동수에서 한 명이 끊어도 `CONFLICTED`가 그대로 남는다).
    - 고친다면 정해 둔 방향: "지금 ACTIVE인 보호자 표만 센다", **이미 `REAL`·`FALSE_ALARM`으로 확정된 판정은 연결이 바뀌어도 유지**하고 `PENDING`·`CONFLICTED`만 다시 계산한다(사용자 결정 2026-09-21). 연결 종료 경로는 다섯(보호자 해제·피보호자 해제·탈퇴 정리·역할 변경 정리·관리자 강제 해제)이며 모두 `ConnectionDisconnectedEvent`를 발행한다. 이 이벤트에 보호자·피보호자 ID를 싣는 선행 작업은 **2026-09-22에 끝났다**(`guardianId`·`wardId`, 알림 이력 점검 L-3) - 탈퇴는 연결 행이 곧 purge되므로 소비자는 연결을 다시 조회하지 말고 이 필드를 쓸 것.
  - 이 한계를 이유로 다수결을 폐기하거나 관리자 정정을 되살리지 말 것 - 둘 다 이번에 명시적으로 버린 선택이다.
- 상세: `docs/(2026-09-01) feature-anomaly-guardian-review.md`, `docs/(2026-09-02) feature-admin-anomaly-review.md`, `docs/(2026-08-31) api-contract-anomaly-dashboard.md` §4·§6, `docs/(2026-09-21) policy-change-anomaly-majority-review.md`(다수결 전환·정정 폐지), `docs/(2026-09-21) audit-impact-anomaly-majority-review.md`(E-2·E-3).

## 피보호자 목록 status 필터 - 좁혀도 인가 목록이 아니다 (2026-09-07)

- **경위**: 프론트의 상태 탭이 클라이언트 필터링 대신 서버 기준으로 목록을 받도록 `GET /api/guardian/connection/select`에 `status`(ACTIVE·PENDING·ALL)를 추가했다(PR #242, 마이그레이션 없음).
- **불변 규칙 ①(인가 목록 아님)**: `getMyWards(guardianId, ACTIVE)`가 결과적으로 ACTIVE 연결만 돌려주지만 **이것을 타 도메인의 IDOR 인가 근거로 쓰지 말 것**. 인가는 계속 `getActiveWardIds()`·`isActiveConnection()`만 쓴다. 이 메서드는 화면 조회용이라 표시 요건(정렬·상태 표기)에 따라 언제든 PENDING을 다시 포함하도록 바뀔 수 있고, 그때 인가가 조용히 넓어진다. SOS·복약·이상감지가 `getMyWards`를 금지해 온 이유가 "지금 PENDING이 섞여서"가 아니라 "이건 인가용 메서드가 아니라서"임을 잊지 말 것.
- **불변 규칙 ②(전용 필터 enum 유지)**: 파라미터는 `ConnectionStatus`가 아니라 전용 `WardListFilter`(ACTIVE·PENDING·ALL)로 받는다. 전체 상태 enum을 받으면 `status=REFUSED`가 유효한 값이라 400이 아니라 **빈 배열**로 응답되고, 호출자는 "거절된 연결이 0건"으로 **정반대 해석**을 한다("0건인 유형은 항목을 만들지 말 것"·"모르는 값을 0으로 채우지 말 것"과 같은 판단이다). 종료된 이력(CANCELLED·REFUSED·DISCONNECTED)은 `/api/guardian/connection/requests`가 담당하며, 편의를 이유로 `/select`에 종료 상태를 열지 말 것.
- **불변 규칙 ③(마스킹은 필터와 무관)**: 연락처·주소·이메일은 `ConnectionResponse`가 `status == ACTIVE`일 때만 채운다. `status=PENDING`으로 좁혀 조회해도 null이며, "이미 PENDING만 골라 왔으니 보여줘도 된다"는 식으로 필터를 근거 삼아 마스킹을 풀지 말 것 - 수락 전 피보호자의 연락처는 보호자에게 노출 대상이 아니다.
- **하위호환**: 파라미터 생략 = 기존 동작(ACTIVE + PENDING). 기본값을 단일 상태로 바꾸면 파라미터 없이 호출하는 기존 프론트 화면이 조용히 비어 보인다.
- 상세: `docs/(2026-09-07) feature-connection-select-status-filter.md`.

## 관리자 회원관리 - 정지는 별도 상태, 삭제는 탈퇴 경로 (2026-09-09)

- **경위**: 2026-05-15에 만들었다가 2026-06-11에 제거된 회원관리 API를 관리자 콘솔 프로토타입에 맞춰 복원했다(V47·V48). 옛 구현을 그대로 되살리면 안 되는 지점이 여섯 개 있었고, 그중 셋은 계정을 지우거나 정지를 무력화하는 것이었다.
- **불변 규칙 ①(정지는 RESTRICTED, INACTIVE 재사용 금지)**: 계정 상태는 **이용 중(`ACTIVE`) / 이용 제한(`RESTRICTED`) / 삭제(강제 탈퇴)** 세 가지다. 정지를 `INACTIVE`로 표현하지 말 것 - `WithdrawnUserPurgeScheduler`가 `updated_at`이 10분 지난 INACTIVE 행을 좀비로 보고 **영구 삭제**하므로 정지시킨 계정이 20분 안에 사라진다(2026-06-11 INACTIVE 불변식이 예고한 바로 그 경우다). 수정 API가 `INACTIVE`를 받으면 400(`INVALID_STATUS`)이다 - 탈퇴는 상태 변경이 아니라 삭제다.
  - "비활성화" 상태를 다시 만들지 말 것. 이미 삭제가 있어 의미가 없다고 판단해 넣지 않았다.
  - ⚠️ **`Status` enum에 값을 더할 때는 반드시 `chk_users_status` 재정의 마이그레이션을 함께** 넣는다. V4가 허용 목록을 `ACTIVE`·`INACTIVE`로 좁혀 놔서 enum만 늘리면 UPDATE가 CHECK 위반(23514)으로 실패하고 본 작업까지 롤백돼 500이 난다(`admin_audit_log.action`의 C-S3-1·V46과 같은 함정). `UserStatusCheckSyncTest`가 enum 전수와 CHECK를 대조해 막는다.
- **불변 규칙 ②(로그인 차단은 부등호로)**: 계정 상태 검사는 `status != Status.ACTIVE`로 한다. `== INACTIVE` 등호 비교로 되돌리지 말 것 - 새 상태값이 늘 때마다 조용히 로그인이 뚫린다(실제로 RESTRICTED 도입 시점에 `AuthService` 2곳·`KakaoAuthService` 1곳이 그 상태였다). 응답은 `INACTIVE_USER`(403)를 공용으로 쓴다.
  - **정지는 토큰까지 끊어야 즉시 듣는다**: `JwtAuthenticationFilter`가 요청마다 DB를 읽지 않아 상태만 바꾸면 기존 access token이 만료(30분)까지 유효하다. `UserRestrictedEvent` → `UserAccountEventListener.handleRestricted`가 refresh 삭제 + Redis 무효화 키를 세운다(탈퇴·비밀번호 변경과 같은 경로). 이 이벤트를 떼지 말 것.
- **불변 규칙 ③(강제 탈퇴도 탈퇴 파이프라인을 탄다)**: 관리자 삭제는 `UserService.forceWithdraw()`(본인 확인만 없는 일반 탈퇴) → AFTER_COMMIT 리스너 3종 → `purgeWithdrawnUser()` 2단계다. **`userRepository.delete()`를 직접 부르지 말 것** - 리스너를 건너뛰어 연결 상대 알림·FCM 토큰 정리·WITHDRAW 접속로그가 유실되는데, 행 정리는 FK CASCADE가 해버려 겉보기엔 성공한 것처럼 보인다.
  - **감사 로그는 삭제보다 먼저 남긴다.** 뒤에 남기면 기록이 실패했을 때 계정만 사라지고 누가 지웠는지가 남지 않는다.
- **불변 규칙 ④(관리자 계정은 조회만)**: 대상이 ADMIN이면 수정·삭제 모두 403(`CANNOT_MODIFY_ADMIN`)이고 `[ADMIN-MODIFY-BLOCKED]` WARN을 남긴다. 관리자가 관리자를 지울 수 있으면 서로를 지워 운영 주체가 사라진다. **역할 변경으로 ADMIN을 만들 수도 없다**(400 `INVALID_ROLE`) - 회원관리 화면 하나로 권한을 만들어낼 수 있게 된다.
- **불변 규칙 ⑤(역할 변경은 연결을 정리하고 ACTIVE만 알린다)**: 역할이 뒤집히면 보호자-피보호자 방향이 어긋나 관계가 뜻을 잃으므로 기존 연결을 정리한다. **ACTIVE는 `disconnect()` + `ConnectionDisconnectedEvent`(상대 알림), PENDING은 `cancel()`(무알림)** - 탈퇴 정리와 같은 규칙이며 PENDING 무알림은 2026-05-28 알림 비대칭 정책 그대로다. ACTIVE를 `cancel()`로 끝내지 말 것(CANCELLED는 "수락 전 요청을 스스로 취소"라는 뜻이라 이력이 사실과 달라진다).
  - **카메라도 함께 삭제한다 (2026-09-10 점검 M-3, 결정)**: 카메라는 피보호자 자산이라 보호자가 된 뒤에는 카메라 API(WARD 전용)를 쓸 수 없어 **아무도 지울 수 없는 고아**가 되고, AI 구독과 본인 화재 알림은 계속된다. 역할 변경 시 `CameraService.deleteAllByWard`로 전부 지우고 감사 로그 detail에 건수를 남긴다. 다시 쓰려면 피보호자 계정으로 재등록해야 한다(장치의 sessionId 재설정 포함). "카메라가 있으면 400으로 막자"는 안은 실사용 기준으로 검토했으나, 역할 변경이 필요한 경우가 대개 "가족이 실수로 피보호자로 가입"이라 초기화 후 재등록으로 확정했다.
  - **피보호자로서 갖고 있던 약도 중지한다 (2026-09-30 전체 점검 M-1)**: 카메라와 같은 이유다. 남겨 두면 알림 스케줄러가 계속 "약 드실 시간"을 보내는데 본인은 체크(피보호자 전용)도 삭제(연결된 보호자 전용)도 할 수 없다. `MedicationRoleChangeService.stopAllOwnedByWard`가 soft delete하고 감사 로그 detail에 건수를 남긴다. **그 사람이 보호자로서 남에게 등록해 준 약은 건드리지 않는다** - 일반 연결 해제 때도 남는 것과 같은 기준이다. 남는 설정 행(`medication_setting` 등)은 동작에 영향이 없어 두었다.
  - **토큰도 끊는다 (2026-09-10 점검 M-1)**: access token은 발급 시점의 role 클레임으로 권한을 만들어, 역할만 바꾸면 옛 역할의 토큰이 만료(30분)까지 `@PreAuthorize`를 통과한다. `UserRoleChangedEvent` → `UserAccountEventListener.handleRoleChanged`가 정지와 같은 무효화 경로를 탄다. 이 이벤트를 떼지 말 것.
  - **탈퇴 진행 중(INACTIVE) 계정은 상세·수정·삭제에서도 404다 (2026-09-10 점검 M-2)**: 목록에서만 빼면 ID를 아는 관리자가 `status=ACTIVE`로 되돌려 이미 토큰·연결·FCM이 정리된 반쪽 계정을 되살릴 수 있고, 스윕(INACTIVE만 회수)도 더는 지우지 않는다. `AdminUserService.getUserOrThrow`가 INACTIVE를 걸러낸다 - 목록과 같은 모집단.
- **불변 규칙 ⑥(관리자도 이메일·전화번호는 못 바꾼다)**: 수정 대상은 **이름·역할·계정 상태 3가지뿐**이다.
  - **전화번호** - 본인 경로는 SMS 인증 nonce 소비가 필수라(H-5) 관리자 경로를 열면 그 인증을 통째로 우회한다. 게다가 전화번호는 SOS·복약 문자가 실제로 도착하는 곳이라 오입력이 긴급 알림을 남에게 보낸다.
  - **이메일** - 본인조차 바꿀 수 없는 로그인 ID다. 관리자에게 열면 시스템에서 유일한 이메일 변경 경로가 된다.
  - 이름 수정은 감사 로그(`USER_NAME_CHANGE`)를 남긴다. **`AdminAuditAction`에 값을 더할 때는 CHECK 재정의 마이그레이션을 함께** 넣는다(V48).
- **관리자 계정의 연결 상태는 `null`이다**: 연결이 0건인 것이 아니라 **연결이라는 축 자체가 없는** 계정이라, NONE("미연결")으로 표시하면 "연결이 끊긴 회원"으로 정확히 반대로 읽힌다. 대시보드의 "모르는 값을 0으로 채우지 않는다"(2026-09-02)와 같은 판단이다.
- **필터 enum과 요청 enum을 같게 만들지 말 것**: 목록 필터는 전용 `AdminUserStatusFilter`(ALL·ACTIVE·RESTRICTED)로 받는다 - `Status`를 그대로 열면 `status=INACTIVE`가 400이 아니라 **빈 배열**로 응답돼 "탈퇴 회원 0명"으로 정반대 해석을 낳는다(`WardListFilter` 2026-09-07과 같은 판단). 반면 수정 요청은 `Status`를 받아 `INACTIVE`를 분명히 400으로 거절한다 - 쓰기에서는 조용한 빈 결과가 아니라 명시적 거절이 필요하고, "탈퇴는 상태가 아니라 삭제"를 전달해야 하기 때문이다.
- **정지 계정은 대시보드 "총 회원 수"에서 빠진다(알려진 한계)**: `AdminDashboardService`가 `Status.ACTIVE`만 센다. 대시보드는 계약이 따로 잡힌 화면이라 회원관리 PR에서 슬쩍 바꾸지 않았다. 정지 계정이 늘어 지표가 흔들리면 대시보드 계약으로 다룰 것.
- **`connections.relation`은 한 방향뿐이다**: 값은 **보호자가 피보호자에게 어떤 사람인지**를 가리킨다("아들"). 반대 라벨(피보호자가 보호자에게 무엇인지)은 저장되지 않으며, 뒤집는 매핑은 성별·다의성 때문에 안전하지 않다. 화면에서 "{상대이름} ({relation})"으로 붙이면 relation이 상대를 가리키는 것처럼 읽히므로 문장으로 풀어 쓴다("이 회원은 박민수님의 아들"). 양방향 라벨이 필요하면 스키마 변경과 연결 요청 화면 수정이 선행되어야 한다.
- **불변 규칙 ⑦(정지 계정에는 알림을 보내지 않는다, 2026-09-09 추가)**: 이용 제한·탈퇴 진행 계정이 **수신자**면 어떤 채널로도 발송하지 않는다 - **강제 FCM도 예외가 아니다**(`NotificationDispatcher.resolveIfAllowed`). 정지는 탈취 의심 시의 임시 조치라, 알림을 계속 보내면 그 계정을 쥔 사람에게 피보호자의 SOS 발생·화재 감지 같은 생활 상황을 실시간으로 통보하게 된다. "필수 알림이니 예외" 논리로 되돌리지 말 것.
  - **막는 기준은 수신자다.** 정지된 피보호자가 **원인**인 알림(그 집의 화재·SOS)은 보호자들에게 그대로 나간다. 계정 정지는 이용 제한이지 안전망 해제가 아니다.
  - **차단은 `dispatch()` 한 곳에서만** 한다. 정책 분기마다 흩어 놓으면 하나를 고칠 때 나머지가 어긋난다. 단 `SETTINGS_ONLY`의 "활성 채널 0건이면 수신자 조회조차 안 한다" 최적화는 유지한다(채널 계산 → 조회·차단 순서).
  - **상태를 모르면(사용자 행 없음) 막지 않는다** - "정지"가 아니라 "알 수 없음"이다.
  - **수용한 위험**: 보호자가 한 명뿐인데 정지 상태면 그 피보호자의 SOS가 아무에게도 안 간다. `[NOTIFY-BLOCKED]` WARN이 유일한 흔적이다.
  - **WebSocket도 같은 기준이다 (2026-10-02, ANOM-G10·ADMIN-G28)**: `WebSocketEventPublisher.sendToUser`가 발송 직전에 수신자 상태를 PK로 조회해 ACTIVE가 아니면 보내지 않는다(`[WS-BLOCKED]` INFO, userId만). 사용자 행이 없거나 조회가 실패하면 상태를 모르는 것이라 막지 않는다. 정지 즉시성이 목적이므로 **상태 캐시를 두지 말 것**. 상태 조회는 `global`의 `WebSocketRecipientStatusPort`를 user 도메인(`UserWebSocketRecipientStatusAdapter`)이 구현한다(`global`→`domain` import 금지). 이 확인을 지우면 정지 전에 열린 소켓으로 SOS·화재가 계속 흘러간다.
  - **열린 WebSocket 세션은 토큰 무효화 직후 서버가 닫는다 - 해소 (2026-10-02 P15, 2026-09-10 점검 M-5의 수용한 한계를 대체)**: 정지·비밀번호 변경/재설정·역할 변경·탈퇴 시 `UserAccountEventListener`가 토큰 무효화 직후 `WebSocketSessionCloser.closeAll(userId)`로 그 사용자의 열린 세션을 닫는다(close `1008 POLICY_VIOLATION`, reason은 고정 `AUTH_INVALIDATED` - 사유 상세·개인정보 금지). 세션 목록은 Principal이 아니라 자체 `WebSocketSessionRegistry`(핸드셰이크 attributes의 userId 기준, `UserSessionTrackingHandlerDecorator`가 등록·제거)다 - Principal을 세우면 구독 인가·`convertAndSendToUser` 경로가 바뀔 수 있어 택하지 않았다.
    - ⚠️ **단일 인스턴스 메모리 목록**이라 API 서버를 여러 대로 늘리면 다른 인스턴스에 붙은 세션은 닫히지 않는다 - 그때 다시 설계한다.
    - **순서(무효화 -> 닫기)를 뒤집지 말 것**: 닫힌 클라이언트가 옛 토큰으로 즉시 재연결하면 핸드셰이크를 통과한다.
    - 위 WS 발송 차단(`[WS-BLOCKED]`)과는 **별개의 이중 방어**다(발송 차단은 상태 조회, 닫기는 이벤트 시점 1회). 정지 **해제는 닫지 않는다**.
    - FE는 `1008`/`AUTH_INVALIDATED`를 받으면 재연결하지 말고 로그아웃하거나 토큰 재발급 후 재연결한다.
  - **"유일한 보호자 경고"는 2026-09-09엔 만들지 않기로 했으나, 사용자가 2026-10-02에 추천안으로 변경해 대시보드 지표로 구현했다 (ADMIN-G27/CONN-G08)**: `safetyEvents.wardsWithoutReachableGuardian` = ACTIVE 연결 보호자가 없거나, 있어도 전원이 이용 중(ACTIVE)이 아닌 피보호자 수. 기존 `wardsWithoutGuardian`은 그대로 둔다(하위호환). **개수만 내리고 대상·정지 사유는 싣지 않는다. 피보호자 화면에는 표시하지 않는다**(정지 사유 비노출). 정지 결정 자체를 막거나 관리자 화면에 경고 팝업을 만들지는 않는다.
    - 옛 판단(2026-09-09): 정지 대상을 보안 이상·법적 문제 계정으로 한정해 경고가 불필요하다고 봤다. 정지를 실제로 쓰며 사각지대가 지표에 안 잡히는 점이 문제로 확인되어 뒤집혔다 - 위 지표를 지우고 "만들지 않기로 했다"로 되돌리지 말 것.
    - ⚠️ **정지가 만드는 사각지대**: 강제 탈퇴·역할 변경은 연결이 사라져 `wardsWithoutGuardian`에 잡히지만 정지는 연결이 남아 거기엔 안 잡힌다. 정지까지 포함한 지표는 `wardsWithoutReachableGuardian`이다.
- **불변 규칙 ⑧(피보호자는 이용 제한 금지, 2026-09-09 추가)**: 피보호자를 정지하면 로그인이 막혀 **SOS를 보낼 수 없다.** 피보호자 계정은 편의 기능이 아니라 안전망 그 자체라 400(`WARD_CANNOT_BE_RESTRICTED`)으로 막는다. 탈취가 의심되면 정지 대신 **비밀번호 재설정**을 쓴다(토큰만 끊기고 본인은 계속 쓸 수 있다).
  - 검사는 **바뀐 뒤의 조합**으로 한다(`validateResultingCombination`). 축별로 따로 검사하면 "보호자를 정지 → 역할을 피보호자로" 두 단계 우회가 열리고, 순서를 정해 검사하면 "제한을 풀면서 역할을 바꾸는" 정상 요청이 잘못 거부된다.
- **정지 사유는 컬럼에 남긴다(V49)**: 감사 로그에만 남기면 **아무도 볼 수 없다** - 관리자 감사 로그 조회 API가 2026-06-11에 제거돼 쓰기 경로만 있다. `users.status_reason`은 "지금 왜 잠겨 있는가"만 답하며 해제 시 지운다(`User.activate()`). 변경 이력은 계속 `admin_audit_log`가 담당한다. 이 컬럼을 이력 저장소로 쓰지 말 것.
- 상세: `docs/(2026-09-09) feature-admin-user-management.md`, `docs/(2026-09-09) fix-notification-restricted-user.md`.

## 관리자 강제 연결 - 동의 없이 만드는 관계 (2026-09-10)

- **경위**: 고객센터 문의를 받아 관리자가 대신 연결해 주는 경로를 열었다(`POST /api/admin/connection`, 마이그레이션 없음). 시니어가 수락 버튼을 누르지 못해 가족이 연결하지 못하는 경우가 실제로 있다.
- **불변 규칙 ①(동의의 대체물이 필요하다)**: 일반 연결은 **피보호자의 수락이 곧 동의**인데 강제 연결에는 그것이 없다. 그 대가로 ① **양쪽 모두에게 알리고** ② **감사 로그를 반드시 남긴다**. 둘 중 하나라도 빼면 관리자가 조용히 남의 집을 열어 볼 수 있는 경로가 된다.
  - 특히 **피보호자 알림을 빼지 말 것**. 원래 피보호자에게는 "연결됨" 알림 경로가 없었는데(늘 수락하는 쪽이라 본인이 안다) 강제 연결은 모르는 채로 생기므로 새로 만든 것이다.
- **불변 규칙 ②(문구를 재사용하지 말 것)**: `CONNECTION_ACCEPTED`("피보호자가 수락했습니다")·`DisconnectedBy.GUARDIAN`("보호자가 해제했습니다")를 강제 조작에 쓰면 **알림이 거짓말을 한다.** 그래서 `CONNECTION_FORCED` 종류와 `DisconnectedBy.ADMIN` 값을 따로 두었다. 복약 "체크되지 않았습니다"·이상감지 "화재 감지가 있었습니다"와 같은 판단이다.
  - **탈퇴·역할 변경으로 끊긴 연결도 같은 규칙이다 (2026-09-30, 점검 M-2)**: 탈퇴 정리는 `DisconnectedBy.WITHDRAWN`("보호자가 탈퇴해 연결이 종료되었습니다." / "피보호자가 탈퇴해 연결이 종료되었습니다." - 받는 쪽이 피보호자면 떠난 쪽은 보호자), 관리자 역할 변경 정리는 `DisconnectedBy.ADMIN`("관리자가 연결을 해제했습니다.")으로 발행한다. 예전에는 둘 다 `GUARDIAN`/`WARD`를 재사용해 남은 쪽에게 "상대가 연결을 해제했습니다"가 나갔다 - 탈퇴 쪽은 H-1 때문에 실제로는 발송되지 않다가 2026-09-30 #259로 처음 보이게 됐고, 역할 변경 쪽은 그전부터 발송되고 있었다. `DisconnectedBy`는 이벤트 전용 enum이라 DB CHECK 대상이 아니다(마이그레이션 불요). 새 연결 종료 경로를 만들면 **누가 끊었는지**에 맞는 값을 고를 것.
- **불변 규칙 ③(강제 해제는 양쪽에)**: 당사자가 끊을 때는 반대편에게만 알리지만, 관리자가 끊으면 **둘 다 자기가 끊지 않았다**. 한쪽만 알리면 나머지는 연결이 사라진 것을 모른다. "기존 해제와 같으니 한쪽만"으로 되돌리지 말 것.
- **수락 대기 요청은 승격시킨다**: 같은 쌍의 PENDING이 있으면 새로 만들지 않고 `activate()`한다. 실제 시나리오가 "요청은 갔는데 수락을 못 누른 상태"라 관리자가 대신 수락해 주는 셈이고, 새로 만들면 같은 쌍의 연결이 둘이 된다.
- **이용 제한·탈퇴 진행 계정은 연결 대상이 아니다**(400 `CONNECTION_TARGET_NOT_ACTIVE`) - 로그인도 알림도 되지 않는 계정이라 연결해 두어도 아무것도 동작하지 않는다.
  - **피보호자의 일반 수락 경로도 같은 기준이다 (2026-09-11 회귀 재점검 R-1)**: 요청 뒤 보호자가 정지·탈퇴 진행 상태가 되면 `acceptConnectionAsWard`가 같은 400으로 막는다. 그대로 수락하면 알림은 디스패처가 막지만 연결은 살아 있어 **정지 해제 즉시 SOS·카메라·복약 이력이 열린다.** 요청 시점 검사(`validateConnectionRequest`)만으로는 요청과 수락 사이의 상태 변화를 못 본다.
- **연결 조회 API를 따로 만들지 않았다** - 회원 상세(`GET /api/admin/user/{userId}`)가 이미 그 회원의 연결 전체를 준다. 별도 "연결 관리" 화면이 생기면 그때 판단한다.

## 운영 설정 - 수용한 한계와 보강 (2026-09-11)

- **실사용 도메인(`api.devdmu.gosky.kr`)의 Swagger·api-docs 무인증 공개는 수용한 한계다** (기술 점검 E-1): 그 서버는 우리가 관리하는 인프라가 아니라 `SWAGGER_ENABLED`를 끌 권한이 없다. 전 엔드포인트·DTO·에러 문구가 보이는 것을 알고 둔다. 관리 권한이 생기면 `SWAGGER_ENABLED=false`로 끄고 vkcs(도메인 없음)에서만 켠다. Swagger 설명문에 **시크릿·내부 호스트·계정 정보를 적지 말 것**(정책 근거 설명은 이미 공개돼 있다).
- **알림 executor는 포화 시 폐기한다** (B-2): `CallerRunsPolicy`를 버렸다 - 포화 시 AI WS 수신 스레드·HTTP 요청 스레드가 FCM·SMS 응답을 기다리게 되어 `@Async`를 둔 이유가 사라진다. 큐 500, 넘치면 `[NOTIFY-REJECTED]` ERROR 로그 후 폐기. "알림을 버리면 안 되니 CallerRuns로"로 되돌리지 말 것 - 화재 다발 시점에 정확히 AI 수신이 멈춘다.
- **우아한 종료** (B-3): `server.shutdown=graceful` + executor 종료 대기 20초. 배포마다 컨테이너가 교체되므로 이것이 없으면 그 순간 큐에 있던 SOS 알림이 사라진다.
- **스케줄러 풀 3스레드** (B-1): 기본 1스레드에 스케줄러와 AI WS 재접속 예약이 함께 줄을 선다. 복약 Planner가 느려지면 AI 재접속이 밀려 그 사이 화재 신호를 놓친다. 스케줄러를 추가하면 이 값을 다시 본다.
  - **재검토 (2026-09-22, 알림 이력 점검 L-2)**: 6종이 됐다 - 주기형 3종(복약 1분·판정 재촉 5분·탈퇴 스윕 10분) + 일일 정리 3종(토큰 03:00·FCM 토큰 04:00·알림 이력 04:30). 일일 정리는 시각을 비껴 두어 주기형과만 겹칠 수 있고, 3스레드로 감당된다고 보고 **3을 유지**했다. 일일 작업을 같은 시각에 몰아 넣지 말 것.
- **외부 연동 타임아웃**: 카카오(기존)·SMTP(C-1, 각 10초)·파일서버(C-2, connect 3초·read 10초)·Solapi(P16, 호출 10초 - 아래). 새 HTTP 클라이언트를 추가할 때 타임아웃 없이 두지 말 것 - 요청 스레드를 붙든다.
  - **FCM에도 시간 제한을 걸었다 (2026-09-30 전체 점검 M-2)**: `FcmConfig`에 연결 3초·응답 10초. Solapi는 SDK 제한이 50초 고정이라 별도로 감쌌다(아래 P16). FCM이 느려지면 `notificationExecutor`가 묶여 뒤따르는 SOS·화재 알림이 밀린다.
- **긴급 알림 전용 executor (2026-10-02 QA SOS-G13·XCUT-G11)**: SOS·이상감지 발생 알림(`SosNotificationListener`·`AnomalyNotificationListener`)은 `urgentNotificationExecutor`(core 4 / max 8 / queue 200, 스레드명 `notify-urgent-`)를 쓰고, 연결·문의·복약 체크·카카오 가입 접속로그는 `notificationExecutor`에 남는다. 포화·종료 정책은 일반 풀과 같다(폐기 + `[NOTIFY-REJECTED]` ERROR, CallerRuns 금지, 종료 대기 20초). **긴급 리스너를 일반 풀로 되돌리거나 일반 알림을 긴급 풀에 태우지 말 것**(`NotificationExecutorAssignmentTest`가 고정). 새 `@Async` 리스너는 executor 이름을 명시한다.
  - **Solapi 호출 시간 제한 (2026-10-02 P16, 위 50초 한계를 해소)**: SDK 1.0.3은 연결·읽기·쓰기 50초 고정(설정 불가)이라 `SolapiCallExecutor`(전용 데몬 풀 10 + 큐 20, 포화 시 즉시 실패, CallerRuns 금지)에서 호출하고 `solapi.call-timeout-seconds`(기본 10초, `SOLAPI_CALL_TIMEOUT_SECONDS`)만 기다린다. 초과·포화는 기존 통신 오류와 같게 처리된다: 알림 이력 `PROVIDER_ERROR`, 인증번호는 `SMS_SEND_FAILED` + 발송 한도 환불. **수용한 한계**: SDK가 인터럽트를 무시하면 풀 스레드는 50초까지 남지만 긴급 알림 스레드는 풀려난다 / 늦게 접수된 건도 실패로 기록될 수 있다. **SDK 호출을 호출 스레드로 되돌리거나 CallerRuns로 바꾸지 말 것.**
  - **해소 (2026-10-05, XCUT-G11)**: `AlimtalkSender`·`SmsSender`의 실패 로그는 예외 클래스명과 발송사 상태 코드(영숫자 16자 이하)만 남긴다(`SolapiFailureCodes`). `getFailedMessageList()`에는 수신·발신 번호가 들어 있으니 로그에 찍지 말 것. **전수 점검 완료 (2026-10-06)**: 요청 값·이메일·기기 토큰·payload 가 섞일 수 있는 예외 메시지·스택을 로그에서 걷어냈다 - `GlobalExceptionHandler`(요청 바디 파싱 실패·타입 불일치·DB 무결성 위반의 `Key (email)=(값)`), `PasswordResetService`(MailException 수신자 주소), `NotificationDispatcher`(채널 예외 객체째 로깅하던 것), `FcmService`(오류 코드 열거형만), `WebSocketEventPublisher`, `FileServerClient`, `KakaoOAuthClient`, `JwtTokenProvider`(파싱 실패), `SosNotificationCooldown`·`SosNotificationListener`, `ApiLoggingAspect`(CustomException 외에는 클래스명). **새 로그에 `e.getMessage()`나 예외 객체를 넘기지 말 것** - 클래스명·고정 코드만(`LogRawExceptionGuardTest`가 점검한 파일들을 고정한다). 의도적으로 남긴 곳: 스케줄러·리스너의 `log.error("...", e)`(내부 DB·로직 오류 진단용, 알림 본문·수신처를 다루지 않음)·`UnhandledException`(미처리 오류 스택)·`AiLiveStreamSubscriber`(AI WS, 개인 정보 없음).
- **DB·Redis 포트는 로컬 전용이다 (2026-09-30 전체 점검 C-1)**: `docker-compose.dev.yml`의 db·redis는 `127.0.0.1:`에만 묶는다. 전 인터페이스(`"6514:6379"`)로 되돌리지 말 것 - gosky는 호스트 방화벽이 없어 그대로 인터넷에 노출되고, Redis는 비밀번호가 없는 채로 비밀번호 재설정 코드·본인확인 통과 표시·토큰 무효화 키를 담는다(읽히면 임의 계정 탈취). 2026-09-30까지 실제로 열려 있었고 스캐너 접근 흔적이 있었다(파괴 흔적은 없음, 읽기 여부는 확인 불가). 외부에서 DB를 볼 때는 SSH 터널. CD는 api만 재기동하므로 compose의 db·redis 설정을 바꾸면 두 서버에서 `up -d`를 직접 돌려야 반영된다.
- **Redis 퇴출 정책 `noeviction` (2026-10-02 AUTH-G30, P10)**: `docker-compose.dev.yml`의 Redis는 `maxmemory 256mb`, `maxmemory-policy noeviction`이다. 로그인 잠금·로그아웃 블랙리스트·토큰 무효화·인증코드 키가 메모리 압박으로 임의 삭제되면 잠금 해제·로그아웃 토큰 부활이 생긴다. 모든 쓰기에 TTL이 있음을 확인했다(키 수는 활성 사용자·요청량에 비례). 대신 가득 차면 쓰기가 OOM 오류가 되므로 `INFO memory`·`evicted_keys`·`DBSIZE`를 점검하고 사용량이 70~80%를 넘으면 원인 키를 확인한다. **`allkeys-lru`로 되돌리지 말 것.** CD의 `up -d api`는 compose 변경이 있으면 redis도 재생성한다(`appendonly yes`라 키는 대부분 유지, 최대 약 1초분 쓰기 유실 가능). 상세 `docs/(2026-10-02) infra-redis-eviction-policy.md`.
- **감사 로그 detail은 DB에만** (E-2): `AdminAuditLogService`의 SLF4J 출력에는 adminId·action·targetId까지만 적는다. detail에는 이름·이메일이 들어가고 컨테이너 stdout은 로그 수집 경로다.
- 상세: `docs/(2026-09-10) feature-admin-force-connection.md`.

## 관리자 알림 이력 - 사실만 남기고, 붙들지 않는다 (2026-09-22)

- **경위**: 관리자 "알림 이력" 화면을 위해 발송 결과를 `notification_log`(V54)에 남기기 시작했다. 프로토타입에는 "기기 응답 없음" 같은 가짜 사유와 "정서 상태 분석" 탭이 있었으나, 서버가 실제로 아는 것만 담기로 했다.
- **불변 규칙 ①(아는 것만 기록한다)**: 실패 사유는 `ChannelFailureReason` 고정 코드만이다. FCM·Solapi는 **접수했는지**까지만 알려준다 - 기기 표시·통신사 도달·"응답 없음"은 서버가 알 수 없으므로 사유로 만들지 말 것. "전송 완료"는 **발송 서버 접수 기준**이며 화면에도 그렇게 밝힌다.
- **불변 규칙 ②(예외 원문 저장 금지)**: 채널 예외 메시지·Solapi 실패 목록에는 토큰·전화번호가 섞일 수 있다. 이력에는 코드만, 로그(`[NOTIFY-LOG-FAILED]`)에는 userId·type·결과·예외 클래스명만 남긴다. 본문(이름·장소)은 DB에만 있고 stdout에 찍지 않는다.
- **불변 규칙 ③(보내지 않음은 실패가 아니다)**: 정지·탈퇴 진행 계정 차단, 사용자가 채널을 꺼 둔 것, 알림톡 템플릿이 없는 종류는 의도대로 동작한 것이다. `NOT_SENT` 또는 "대상 아님"(`NOT_APPLICABLE`, 기록 안 함)으로 두고 실패율에 섞지 말 것 - 실패율이 부풀면 진짜 장애가 묻힌다(대시보드 "모르는 값을 0으로 채우지 않는다"와 같은 판단).
  - **채널을 꺼 둔 경우도 `NOT_SENT`로 기록한다 (2026-09-22 점검 L-1, 수용)**: 이 때문에 "켠 채널이 없으면 DB를 건드리지 않는다"던 경로에 INSERT가 1건 생겼다(수신자 조회는 여전히 생략). 관리자 화면의 "발송 안 함 · 수신자가 알림을 꺼 둠"이 이 행이다. 양은 보관 90일로 다스린다 - "쓸데없는 행"이라며 기록을 빼면 관리자가 "왜 안 갔지"에 답할 수 없게 된다.
- **불변 규칙 ④(기록이 발송을 막지 않는다)**: 기록은 발송 **후**, `REQUIRES_NEW`로 한 번만 하고 실패는 삼킨다. 발송 전에 기록하거나 기록 실패를 전파하면 SOS·화재 알림이 이력 테이블 장애에 묶인다. `REQUIRES_NEW`를 기본 전파로 바꾸지 말 것 - AFTER_COMMIT 리스너 안에서 이미 커밋된 트랜잭션에 합류해 **기록이 조용히 사라진다**.
- **불변 규칙 ⑤(붙들지 않는다)**: 수신자·피보호자 탈퇴 시 CASCADE 삭제, 보관 기본 90일(`notification.log.retention-days`). SOS 이력(`sos_event`)의 익명 보존은 감사 목적의 예외이고 알림 이력에는 적용하지 않는다.
- **조회 전용·감사 로그 없음**: 관리자 조회 공통 규칙대로다. `AdminNotificationControllerSecurityTest`가 쓰기 매핑이 없음을 고정한다.
- **테스트로 고정한 것 (2026-09-22 점검 반영)**: `NotificationLogAfterCommitIntegrationTest`(AFTER_COMMIT 안의 기록은 REQUIRES_NEW라야 남는다 - 대조군 포함) · `NotificationDispatcherTest` 가드(모든 알림 종류 × 수신자 상태에서 이력이 정확히 1건).
- **연결 이벤트는 당사자를 싣는다 (2026-09-22 점검 L-3)**: `ConnectionAcceptedEvent`·`ConnectionRefusedEvent`에 `wardId`, `ConnectionDisconnectedEvent`에 `guardianId`·`wardId`를 더했다. 알림 대상(`notifyTargetId`)과 다른 값이며 **알림 대상·문구를 바꾸는 데 쓰지 말 것**(연결 알림 비대칭 정책 그대로). 해제 이벤트의 당사자 필드는 이상감지 E-3(해제·탈퇴 보호자 표 재계산)을 고칠 때 쓰려고 미리 실어 둔 것이다.
  - 피보호자 탈퇴 경로에서는 비동기 해제 알림보다 purge가 먼저 끝나 FK 때문에 그 이력만 남지 않을 수 있다(`[NOTIFY-LOG-FAILED]` WARN, 발송 무영향). 어차피 CASCADE로 지워질 행이라 수용했다.
- 상세: `docs/(2026-09-22) feature-admin-notification-history.md`, `docs/(2026-09-22) fix-admin-notification-audit-findings.md`.

## 2026-10-02 QA(BE) 반영 - 인증 저장소 장애·토큰·입력 정규화·알림 결과 (P0~P9)

> 전 묶음(PR #267~#283)이 dev에 머지·배포된 뒤 현재 dev 코드로 확인했다(2026-10-02). 상세 표: `docs/(2026-10-02) fix-qa-be-issues.md`.

### 인증 필터·토큰 (D1~D5)

- **인증 저장소(Redis) 장애 (D1)**: 일반 경로는 무효화·블랙리스트 조회가 실패하면 **503**(`SERVICE_UNAVAILABLE`, `[AUTH-STORE-UNAVAILABLE]`)이다. **`POST /api/ward/sos`만 fail-open**(검사 생략 후 통과, WARN `[AUTH-STORE-FAIL-OPEN]`) - 서명·만료·typ 검증은 그대로 한다. WebSocket 핸드셰이크는 fail-open이 없고 503으로 거부한다.
  - **왜**: Redis 장애 중 SOS가 막히면 안전망이 무너진다. 그 외 경로는 정지·로그아웃된 토큰을 통과시키면 안 된다.
  - **수용한 한계**: Redis가 죽은 동안 **이미 로그아웃·정지·비밀번호 변경된 토큰이 SOS 경로에서만 통과**할 수 있다("정지는 즉시 차단" 정책의 유일한 예외). SOS는 어차피 보호자에게 가는 필수 알림이라 위험이 작다고 보고 둔다. fail-open을 다른 경로로 넓히지 말 것.
- **무효화 시각은 초 단위로 비교 (D2)**: `iat(초) < 무효화 시각(초)`일 때만 거부한다. 같은 초에 발급된 토큰은 허용(최대 1초 수용 - 밀리초 비교는 변경 직후 재로그인한 새 토큰을 거부했다). 옛 ms 값(1e11 이상)은 /1000으로 호환한다. 비교 단위를 다시 ms로 되돌리지 말 것.
- **refresh 토큰 (D3)**: jti 고유값 / `typ` 검사(access 토큰으로 refresh를 시도해도 **정상 세션을 폐기하지 않는다**) / 다른 기기 로그인으로 밀려난 refresh는 재사용 감지가 아니라 단순 `INVALID_TOKEN`(사용자별 마지막 로그인 시각 마커 `LoginSupersedeMarker`) / 회전 재사용 감지(H-3)는 유지. 밀려난 토큰을 `TOKEN_REUSE_DETECTED`로 처리하면 정상 사용자의 세션이 통째로 끊긴다.
- **관리자 본인 탈퇴 403 (D4)**: `ADMIN_CANNOT_WITHDRAW` + `[WITHDRAW-ADMIN-BLOCKED]` WARN. 마지막 관리자가 스스로 지우면 운영 주체가 사라진다(`CANNOT_MODIFY_ADMIN`과 같은 취지).
- **클라이언트 IP (D5)**: `app.client-ip.trusted-proxies`(`CLIENT_IP_TRUSTED_PROXIES`)에 든 피어일 때만 `X-Forwarded-For`를 **오른쪽부터** 해석하고, 비신뢰 피어가 보낸 헤더는 무시한다(스푸핑으로 rate limit·접속로그 IP를 속이는 것 방지). **기본 신뢰 대역에 사설망(`192.168.0.0/16` 등)이 들어 있어, 피어(nginx가 넘긴 X-Real-IP)가 사설 주소면 서버 `.env.dev` 설정 없이도 동작한다**(2026-10-05 gosky·vkcs-linux 모두 `CLIENT_IP_TRUSTED_PROXIES` 미설정으로 QA 통과). FE 서버가 공인 IP로 접속하는 구조가 되면 그 IP를 넣되, 환경변수는 기본값을 대체하므로 기본 대역도 함께 적을 것. `getRemoteAddr()` 직접 사용 금지 규칙은 그대로다.
  - **XFF는 래퍼를 벗긴 원래 요청에서 읽는다 (2026-10-05, AUTH-G28 재발)**: `server.forward-headers-strategy: framework`의 `ForwardedHeaderFilter`가 감싼 요청은 `X-Forwarded-*`를 숨긴다. 피어 주소만 래퍼를 벗기고 XFF는 감싼 요청에서 읽던 탓에 신뢰 피어여도 XFF가 항상 비어 FE 경유 사용자가 한 IP(192.168.0.1)로 합산됐다(PR #301). 벗기는 로직은 `ClientIpResolver.unwrap()` 한 곳이며 **피어와 XFF가 같은 원래 요청을 읽어야 한다** - 둘을 다른 요청 객체에서 읽게 되돌리지 말 것. 신뢰 판정은 피어로 먼저 하므로 원본 헤더를 읽어도 위조 방어는 그대로다. 테스트는 필터를 거치지 않은 `MockHttpServletRequest`만으로는 이 결함을 못 잡는다 - 실제 `ForwardedHeaderFilter`를 거친 요청으로 검증한다(`ClientIpResolverTest`).
- **속도 제한 장애 정책**: `RateLimitService`는 Redis 장애 시 **fail-open**(WARN) - 로그인·가입이 통째로 막히는 것보다 낫다. 반면 **로그인 잠금·인증번호 시도·발송 상한 카운터는 fail-closed**(보안 한도라 장애 중에 풀어주지 않는다). 두 부류를 같은 정책으로 통일하지 말 것.
- **STOMP 클라이언트 SEND**: `/app/` prefix만 허용하고 `/topic/...`은 거부한다. SUBSCRIBE만 검사하면 인증된 사용자가 `SEND /topic/{상대 userId}/sos-triggered`로 **가짜 SOS·화재 알림을 상대 화면에 띄울 수 있었다**. 서버 발행(`SimpMessagingTemplate`)은 이 인터셉터를 거치지 않아 영향이 없다.

### 입력 정규화·계정 식별 (이메일·텍스트)

- **이메일은 소문자로 정규화**: 입력은 소문자로 저장·조회하고, **V55(비가역)**가 기존 값을 소문자로 바꾸고 `lower(email)` 유니크 인덱스(`uq_users_email_lower`)를 만든다. ⚠️ **배포 전에 대소문자만 다른 중복이 없는지 점검**한다(마이그레이션 파일 머리의 `GROUP BY lower(email) HAVING count(*) > 1` 쿼리). 중복이 있으면 마이그레이션이 실패한다. 기존 `V*.sql`은 수정하지 않았다.
- **이름·텍스트 정규화**: 공용 유틸은 `global/validation`(`TextSanitizer`·`@VisibleText`·`@NoControlChars`)이다. 사용자가 입력하는 이름·표시 텍스트는 이것을 쓰고(보이지 않는 문자·제어문자·한글 채움 문자만 있는 값 차단), 도메인별로 따로 정규식을 만들지 말 것. `global`이 `domain`을 import하지 않는 규칙을 지킨다.
- **오류 응답에 `code` 필드**: FE가 문구가 아니라 `code`로 분기하도록 오류 응답에 코드를 싣는다. 문구는 바뀔 수 있으니 FE에 문구 파싱을 요구하지 말 것.

### 로그인 잠금·인증번호 시도 (기존 "검증 후 마지막 소비" 규칙 유지)

- **로그인 잠금**: 비밀번호 **비교 전에 시도를 예약**해 동시 요청에서도 5회를 넘지 못하게 한다. 미가입 이메일도 같은 방식으로 세어(enumeration과 별개로 잠금 동작을 동일하게) 가입 여부를 시간·응답으로 구분하지 못하게 한다. **비밀번호 변경·탈퇴 본인확인은 userId별 별도 잠금**이고, 비밀번호 재설정·변경에 성공하면 잠금을 푼다.
- **인증번호 시도**: 시도 횟수는 비교 전에 예약하고 **정답이면 환불**한다. 확인 API는 IP 속도 제한이 있다. nonce·코드 소비는 여전히 **모든 검증 뒤 마지막**이며 `consume`은 원자적이다(2026-05-31 규칙 그대로). 소비를 앞당기거나 예약을 비교 뒤로 미루지 말 것.

### 카카오·파일서버

- **가입 `pendingToken`**: 카카오 로그인 응답으로 일회용 토큰을 내리고(서버에는 SHA-256 해시만 저장, TTL 30분), 가입 완료는 **그 토큰을 가진 본인만** 할 수 있다. 다시 로그인하면 새 토큰으로 교체된다. kakaoId만 알면 남의 가입 대기를 가로챌 수 있었던 문제를 막는다.
- **프로필 이미지 URL**: 카카오 CDN(https, `kakaocdn.net`)만 받는다. 임의 URL은 파일서버 삭제·표시 경로에서 SSRF·추적 URL이 된다.
- **일반 가입에서 `kakao_숫자@kakao.com` 거절**: 카카오 이메일 없는 계정에 서버가 부여하는 가상 이메일과 충돌해 남의 카카오 계정을 선점할 수 있다.
- **파일서버 삭제는 자기 baseUrl만**: 외부 URL(카카오 CDN 등)에는 삭제 요청을 보내지 않는다.

### SOS·알림 결과·설정 최초 저장 (h)

- **디스패처 결과 반환**: `NotificationDispatcher`가 수신자별 `NotificationLogResult`를 돌려주고, SOS 리스너는 **전달된 보호자가 0명이면 쿨다운을 해제**해 재시도가 막히지 않게 한다. ⚠️ **SOS 알림은 항상 발송**한다는 정책은 그대로다 - 이 결과는 쿨다운 해제용일 뿐 발송 여부를 가르지 않는다.
- **설정 최초 저장**: 알림 설정·SOS 설정은 행이 없을 때 동시에 저장하면 UNIQUE 위반으로 500이 났다. `ON CONFLICT DO NOTHING` 후 재조회한다.
- **보호자 SOS 이력**: `triggerType` 필터와 `counts`(경로별 건수)를 제공한다. 열람 범위(ACTIVE 연결만)는 그대로다.

### 연결·복약·이상감지·관리자 (i~l)

- **연결**: 강제 연결·해제 WS 페이로드에 `type`/`title`/`body`를 싣는다. 수락 시 **양쪽 역할을 다시 검증**(`INVALID_CONNECTION_ROLE` - 역할 변경 뒤 낡은 요청 수락 방지)하고, 동시에 같은 연결을 만들면 `CONNECTION_ALREADY_EXISTS`로 응답한다.
- **복약**: 보호자가 없는 피보호자의 약은 알림 대상에서 제외하되 **약은 보존**한다(보호자 재연결 시 복구). 복용 시각을 **과거 시각으로 수정하면 당일 발송 기록을 유지**한다(지난 시각으로 알림이 다시 나가면 거짓 알림). **오늘 늦게 등록한 약은 그날 미복용 요약 집계에서 제외**한다(등록 전 시각의 약이 "체크되지 않음"으로 잡히는 것 방지). 문구 "체크되지 않았습니다"·집계 상한 = 발송 시각 규칙은 그대로다.
- **이상감지**: 하루 요약에서 **건별 재촉이 요약 시각 2시간 이내에 이미 나간 상황은 제외**한다(같은 상황 이중 알림 방지). 이력 쿨다운은 **저장에 실패하면 해제**한다(이력이 없는데 쿨다운만 남아 다음 감지가 버려지는 것 방지).
- **관리자**: 공지 조회수는 원자 증가이고 `updated_at`은 내용이 바뀔 때만 갱신한다(**V56 트리거**). 목록 파라미터는 `page`(0 이상, 기본 0)/`size`(기본 20, 최대 50)로 통일하고, 공지·문의 목록 본문은 100자로 축약한다. 문의 작성은 분당 5회로 제한하고, 연결 목록 필터에서 관리자 계정은 제외한다.

### 의도 명시 (ANOM-G06)

- 동수(`CONFLICTED`) 상황의 **미응답 보호자에게는 동수 안내도 재촉도 가지 않는다 - 의도된 동작**이다(위 "이상감지 판정" 절 규칙 ⑤의 연장, 코드 변경 없음). "미응답자도 알려야 한다"며 안내 대상을 넓히지 말 것.

### 카메라 등록·중지 카메라 (P14, ANOM-G08)

- **카메라 등록 DTO**: 방 이름은 수정 경로와 같은 검증(`@NotBlank`·`@VisibleText`·`@NoControlChars`·최대 30자), 기기 토큰은 최대 64자다. 등록과 수정의 검증을 서로 다르게 두지 말 것.
- **`camera.is_active`는 사용자 표시용 플래그이며 감지·알림을 끄지 않는다**(2026-10-02 결정, ANOM-G08 - 문서화로 종결, 코드 변경 없음). 안전 알림을 줄이는 방향은 금지이고 FE에 "중지" UI도 없다. 알림을 끄는 의미로 쓰려면 정책부터 바꿀 것.
- **방은 정해진 8개, 피보호자당 방마다 1대 (2026-10-05, V58)**: `CameraRoom`(거실·침실·주방·화장실·현관·베란다·작은방·작은방2) 밖이면 400 `CAMERA_ROOM_INVALID`, 같은 피보호자의 다른 카메라가 쓰는 방이면 409 `CAMERA_LABEL_DUPLICATED`(같은 기기·같은 방 재등록은 멱등). 서버가 막는 이유는 FE 버튼만 믿으면 API 직접 호출로 임의 이름·중복이 들어오기 때문이다. DB에는 한글 방 이름을 그대로 저장한다(알림 문구·이력·검색이 그 문자열을 쓴다) - enum 이름으로 바꿔 저장하지 말 것. 동시 등록은 `uq_camera_ward_label`이 막고 서비스가 같은 409로 바꾼다 - 새 등록은 id가 IDENTITY라 `save()`에서, 방 변경은 `flush()`에서 위반이 나므로 **둘 다 변환 범위에 둘 것**(기능 점검 H-1: `flush()`만 감싸 새 등록 경합이 일반 `DUPLICATE_VALUE`로 샜다). 제약 이름을 바꾸면 `CameraService.ROOM_UNIQUE_CONSTRAINT`도 함께 바꿀 것. 사용 중지 카메라도 방을 차지한다(삭제해야 빈다).
- **피보호자 연결 상태는 별도 API (2026-10-05)**: `GET /api/ward/camera/live`가 보호자 `/live`와 같은 기준으로 상태를 붙인다(AI 장애 시 `null` = 확인 중, "연결 안 됨"으로 채우지 않는다). 기존 `GET /api/ward/camera`에 AI 호출을 붙이지 말 것 - 송출 기기가 `sessionId`를 받는 경로가 AI 호출 제한(8초)만큼 늦어진다.

### 구현으로 확정된 항목 (이전 보류 목록에서 이동)

- **ADMIN-G27/CONN-G08, ADMIN-G28/ANOM-G10, SOS-G13/XCUT-G11, CONN-G04/XCUT-G29**는 모두 구현·배포됐다. 정책은 각각 "관리자 회원관리" 규칙 ⑦(WS 수신자 차단·대시보드 지표), "운영 설정"(긴급 executor), "연결 요청 반복 제한" 절에 있다.

### 남은 보류 (의도적으로 만들지 않았다)

- **CONN-G02**: 강제 연결 알림의 FCM 승격 - **현행 유지로 확정**(`CONNECTION_FORCED`는 설정 기반 발송). 강제 채널로 올리지 말 것.
- **XCUT-G31**: refresh 토큰 HttpOnly 쿠키 전환 - 설계안만 작성(`docs/(2026-10-02) design-refresh-token-httponly-cookie.md`), 구현은 별도 세션에서 진행한다(A안 추천, 결정 질문 9개).
- **세션 강제 종료**: **구현됨**(2026-10-02 P15, #287) - 위 "관리자 회원관리" 규칙 ⑦의 "열린 WebSocket 세션" 항목 참조. 단일 인스턴스 한정.
- **CONN-G15 경합 - 해소 (2026-10-05, PR #309)**: 연결을 새로 만드는 두 경로(`requestConnectionAsGuardian`·`AdminConnectionService.forceConnect`)는 역할을 읽기 전에 두 사람의 users 행을 `FOR SHARE`(id 순)로, 관리자 회원 수정(`AdminUserService.updateUser`)은 연결 정리 전에 대상 users 행을 `FOR NO KEY UPDATE`로 잠근다(순서 users → connection, 탈퇴 purge·클립 기록과 같아 교착 없음). 요청 생성은 INSERT라 `@Version`으로 못 막고, 역할 변경 쪽 조회가 미커밋 요청을 못 봐 보호자가 된 사용자 앞으로 PENDING이 남던 문제였다. **잠금을 빼거나 검증 뒤로 옮기지 말 것. 역할 변경 쪽을 `FOR UPDATE`로 바꾸지 말 것**(클립의 `FOR KEY SHARE`와 충돌). 수락 경로는 기존 행 UPDATE라 `@Version`으로 충분해 바꾸지 않았다. 실 DB 검증: `ConnectionRoleChangeRaceIntegrationTest`.
- (ANOM-G08은 위 "카메라 등록·중지 카메라"로 종결.)

## 카메라 세션 ID - 사용자 식별자를 싣지 않는다 (2026-10-05 QA 종합 점검)

- **경위**: 세션 ID 형식이 `ward_{wardId}_{6자}`였다. 세션 ID는 AI 서버·영상 경로·FE 프록시로 바깥에 보이는 값이라, FE 프록시 무인증(Critical, FE 별도 작업)과 엮이면 비로그인으로 얻은 세션 ID에서 **피보호자 ID**를 꺼내 AI 서버의 다른 기록(챗 기록 등)을 조회하는 연쇄가 열렸다.
- **불변 규칙**: `CameraIdentifierFactory.newSessionId()`는 **`ward_` + 영숫자 16자**(약 95비트)이고 인자를 받지 않는다. 세션 ID에 사용자 ID·이름·전화번호 등 **어떤 식별자도 싣지 말 것** - "가독성"을 이유로 되살리지 말 것(`CameraIdentifierFactoryTest`가 형식을 고정). 인가는 언제나 DB 행으로 판정한다.
  - 영숫자만 쓴다 - FE·AI가 세션 ID를 URL에 인코딩 없이 넣는다(2026-10-05 전수 확인). 길이는 `VARCHAR(64)` 안.
- **기존 카메라는 바꾸지 않는다(수용)**: 같은 기기(`deviceId`, FE `localStorage`)로 재등록하면 기존 세션 ID를 재사용하므로 옛 형식은 **카메라를 삭제하고 다시 등록할 때까지 남는다**. 일괄 재발급·재등록 시 교체는 송출 설정이 깨질 수 있어 하지 않았다. 옛 형식을 없애려면 해당 카메라를 삭제 후 재등록한다.
- 백엔드·FE·AI·QA 어디도 세션 ID를 파싱하지 않는다(불투명 문자열). 새 코드도 세션 ID에서 정보를 꺼내지 말 것.

## 이상감지 영상 클립 - 보는 사람은 좁게, 지우는 길은 넓게 (2026-10-04)

- **경위**: 위험 감지 시점의 5초 영상(감지 앞 3초 + 뒤 2초, VP8 WebM)을 백엔드가 AI 서버에서 받아 디스크에 저장한다(V57 `anomaly_clip`, AI 계약서 2026-10-04 최종본). 집 안 영상이라 이 서비스에서 가장 민감한 데이터다. 상세 `docs/(2026-10-04) feature-anomaly-clip.md`.
- **불변 규칙 ①(열람 범위)**: 보호자는 **요청 시점 ACTIVE 연결** 피보호자의 클립만(`isActiveConnection`, `getMyWards` 금지 - 위반 403 `ANOMALY_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`). 피보호자 본인은 본인 집 클립이되 **ACTIVE 연결이 1건 이상일 때만**(0건이면 404, 재연결하면 다시 보인다 - 연결 해제로 파일을 지우지 않는다). 남의 클립은 403 `ANOMALY_CLIP_NOT_OWNED` + `[IDOR-ATTEMPT]`. 비공개·만료·없음은 모두 404로 사유를 구분하지 않는다.
- **불변 규칙 ②(관리자 불허)**: 관리자 API·관리자 응답 DTO에 클립 정보·URL을 싣지 말 것(`AdminAnomalyClipExposureTest`가 고정). 운영 확인이 필요해도 관리자 열람 경로를 만들려면 이 정책부터 바꿀 것.
- **불변 규칙 ③(재생은 인증 헤더로)**: 파일 API는 일반 인증 경로다(FE가 fetch → blob URL). 영상 중계처럼 permitAll + 티켓 경로를 만들지 말 것 - 1.9MB 파일은 헤더 인증으로 충분하고, 공개 경로는 그 자체로 위험을 늘린다. access token을 쿼리로 받지 말 것. 응답은 `Cache-Control: private, no-store`(비공개 전환 뒤 캐시로 다시 보이면 안 된다).
- **불변 규칙 ④(삭제 5경로)**: 보관 만료(최대 30일 - 설정으로 늘려도 30으로 자른다) / 오탐 24시간 / 피보호자 탈퇴 / 카메라 삭제(`delete`·`deleteAllByWard` → `CameraDeletedEvent`) / 고아 청소(행 없는 파일은 **매시 15분**, 그 밖은 05:00 KST). 새 삭제 경로(예: 회원 데이터 일괄 정리)를 만들면 클립 파일도 함께 지우는지 확인할 것 - 행은 FK CASCADE로 지워져도 **파일은 남는다**(그때는 매시 고아 청소가 회수 - 점검 L-3, 하루 1회로 되돌리지 말 것).
  - 탈퇴·카메라 삭제 리스너는 **동기 AFTER_COMMIT + `REQUIRES_NEW`**(H-1 규칙). 비동기로 바꾸면 탈퇴 purge의 CASCADE가 먼저 행을 지워 지울 파일을 알 수 없다. 예외는 삼킨다(탈퇴 파이프라인을 막지 않는다).
  - 순서는 **행 삭제 커밋 → 파일 삭제**다. 뒤집으면 롤백 시 파일 없는 행이 열람 404를 만든다.
- **불변 규칙 ⑤(오탐 24시간 유예)**: 판정이 `FALSE_ALARM`이 되면 **판정 쓰기 잠금과 같은 트랜잭션**에서 **그 시점까지 저장된** 클립을 즉시 `HIDDEN`, 그 밖(REAL·CONFLICTED·PENDING)이면 복구. 동수·판정 대기는 숨기지도 지우지도 않는다. 물리 삭제는 청소가 `hidden_at + 24h` 뒤 **여전히 HIDDEN일 때만**(조건부 DELETE) 한다.
  - **판정 뒤 저장된 클립은 공개로 태어난다 (2026-10-04 점검 L-2, 사용자 결정)**: 오탐 확정 상황에 10분 안에 이어진 감지는 진짜 화재일 수 있어, 그 영상을 숨기면 24시간 뒤 증거가 사라진다. 판정은 판정 시점의 클립에만 적용되고, 보호자가 다시 오탐으로 답하면 그때까지의 클립이 숨겨진다. "상황 단위 판정과 어긋난다"는 이유로 새 클립을 오탐 상황에 맞춰 숨기도록 되돌리지 말 것.
  - 클립 기록은 **피보호자 `users` 행(`FOR KEY SHARE`) → 상황 행(`FOR UPDATE`)** 순서로 잠근다(점검 L-1) - 탈퇴 purge(users → CASCADE 상황)와 같은 순서라 교착이 없다. 상황 행 잠금으로 보호자 응답과도 한 줄로 서서 "판정보다 먼저 저장된 공개 클립"이 남지 않는다(`AnomalyClipIntegrationTest` 동시 20회 × 2).
- **불변 규칙 ⑥(알림과 분리)**: 클립은 이력 커밋 뒤 **별도 리스너·전용 `clipExecutor`**(2/2/20, 포화 시 폐기 + `[ANOMALY-CLIP-REJECTED]` WARN, CallerRuns 금지 - 알림 유실이 아니므로 `[NOTIFY-REJECTED]` ERROR로 남기지 않는다)에서 만든다. 긴급 알림 풀에 태우지 말 것(AI 응답 최대 25초 동안 화재 알림이 줄을 선다). 클립 실패가 이력·알림을 막거나 되돌리게 만들지 말 것. 완성 알림(WS·푸시)도 만들지 않았다 - 필요하면 `WebSocketEventPublisher`만, 디스패처 금지.
- **불변 규칙 ⑦(킬 스위치 범위)**: `anomaly.clip.enabled=false`는 **생성만** 멈춘다. 열람·삭제·청소는 계속 동작해야 한다(끄면 만료 클립이 영원히 남는다).
- **생성 조건과 쿨다운**: `danger=true` 이력만(CONFIDENCE 폴백의 `danger=false` 이력은 클립 없음). 클립 쿨다운 `anomaly:clip:{session}:{type}`(기본 5분)은 이력·알림 쿨다운과 **별개 키**이며 Redis 장애 시 **fail-closed**(클립 생략) - 이력·알림의 fail-open과 반대인 이유는 클립이 부가 기능이고 장애 중 AI 인코딩 요청 폭주를 막기 위해서다. 재시도 가능한 실패는 쿨다운을 풀고, 401·422·키 미설정·503 `CLIP_DISABLED`(AI 킬 스위치)는 풀지 않는다(같은 실패로 AI를 두드리지 않게). 상황당 12개, 디스크 여유 1GB 미만이면 생략.
- **파일 경로**: DB에는 서버가 만든 `UUID.webm` 이름만 저장하고, 경로로 바꿀 때마다 형식 검사 + normalize + 저장 루트 바로 아래인지 확인한다. 사용자 입력·AI 응답 값을 경로에 쓰지 말 것. 저장은 임시 파일 → 원자적 이동, 응답은 EBML 시그니처·10MB 상한 검증 후에만 저장한다.
- **포화 폐기는 쿨다운을 남기지 않는다 (2026-10-05 QA 종합 점검 확인)**: 클립 쿨다운은 `clipExecutor` 작업 **안**(`AnomalyClipCaptureService.capture`)에서 잡으므로, 포화로 폐기된 작업은 쿨다운을 잡지 않고 다음 위험 감지(이력 쿨다운 1분 뒤)가 다시 시도한다(`AnomalyClipExecutorSaturationTest`가 CallerRuns 금지와 폐기된 작업이 Redis를 건드리지 않음을, 같은 클래스의 구조 테스트가 "쿨다운은 `AnomalyClipCaptureService`만 쓴다"를 고정한다). 쿨다운 확인을 이벤트 발행 쪽(작업 밖)으로 옮기지 말 것 - 폐기된 건이 쿨다운만 남겨 5분간 클립이 생기지 않는다. (2026-10-04 판의 "포화로 폐기된 클립은 쿨다운 동안 재생성 안 함"은 코드와 달라 정정했다.)
- **AI 호출**: 접속 정보는 영상 중계와 같은 `camera.stream.*`(키 `X-API-Key` 헤더만, 리다이렉트 미추종). 호출 **전체** 제한 25초(마감 시 연결을 끊는다 - 영상 중계 L-3과 같은 방식)는 일반 10초 규칙의 예외다(AI가 뒤 구간 2초를 기다린 뒤 인코딩, 계약상 처리 상한이 요청 수신부터 20초 + 전송 여유). 읽기 1회 제한만으로 되돌리지 말 것. 로그에는 sessionId·clipId·결과 코드만.
  - **AI 계약 v2 보충 (2026-10-05 QA 종합 점검)**: AI 킬 스위치 응답 503 `CLIP_DISABLED`는 계약서 v2에 있으며 재시도·쿨다운 해제 대상이 아니다. AI 인코딩은 ffmpeg에 `-an`(음성 없음)·짝수 해상도 보정·`nice`(낮은 우선순위)를 더 붙인다 - AI 내부 사항이라 백엔드는 EBML 시그니처·크기만 검증하며, 음성 트랙·해상도를 가정한 처리를 추가하지 말 것.
- **수용한 한계**: 탈퇴·카메라 삭제 리스너의 **파일 삭제가 실패하면** 그 파일은 매시 고아 청소가 회수한다 - 청소는 수정 후 1시간이 지난 파일만 지우므로 **최대 약 2시간** 남을 수 있다(2026-10-05 QA 종합 점검, 수용) / 오탐 유예가 지나 삭제된 뒤 번복되면 클립은 돌아오지 않음 / 저장소는 단일 서버 로컬 디스크(`./.data/clips` 마운트 필수 - 없으면 컨테이너 재생성 때 사라짐).

## 2026-10-05 QA(BE) 잔여 25건 처리 (PR #306·#307·#308·#309)

> 항목별 결과·FE 전달은 Notion "QA(BE) → FE 전달". 아래는 코드만으로 드러나지 않는 결정만 적는다. Notion QA(BE)의 9/30 기준 설명 중 이미 고쳐진 항목이 섞여 있었다(MED-G16·ADMIN-G24·SOS-G12·MED-G05·XCUT-G19·AUTH-G08·XCUT-G16·FEUX-G11·FEUX-G12·FEUX-G34·XCUT-G30는 코드 변경 없이 종결, AUTH-G16은 동시 제출 테스트만 추가).

- **토큰 무효화 값이 손상되면 조회 오류와 똑같이 처리한다 (XCUT-G03)**: 숫자 아님·음수·현재 시각 +300초 초과면 일반 경로 503, `POST /api/ward/sos`만 통과, 로그만 `[AUTH-STORE-CORRUPT]` ERROR로 나눈다(`TokenInvalidation.parseEpochSecond`). **손상 값을 "무효화 없음"으로 통과시키지 말 것**(그 키가 정지·비밀번호 변경의 흔적일 수 있다). **401로 답하지도 말 것**(재로그인한 새 토큰까지 같은 키에 걸려 로그인 반복). 로그인 잠금 카운터의 Redis 오류는 fail-closed이며 응답이 503이 아니라 500이다(통일 여부는 미결).
- **Redis 명령·연결 시간 제한 2초 (XCUT-G11)**: `REDIS_TIMEOUT`·`REDIS_CONNECT_TIMEOUT`. 인증 필터와 SOS 쿨다운이 Redis를 거치므로 제한이 없으면 Lettuce 기본 60초로 요청·긴급 알림 스레드가 묶인다. 블로킹 명령을 도입하면 다시 볼 것.
- **미응답 요약은 보호자 단위로도 미룬다 (ANOM-G09)**: 상황 단위 제외(재촉이 요약 시각 -2시간 이후에 나간 상황 제외)에 더해, 어떤 상황이든 건별 재촉을 받은 지 2시간이 안 된 보호자는 그 주기에 요약을 선점하지 않고 다음 주기에 다시 판단한다(재촉 기록 전부 기준, 응답 여부 무관). 같은 보호자에게 오래된 다른 미응답 상황이 남아 있으면 같은 실행에서 재촉과 요약이 함께 나가던 문제였다. 저녁마다 새 재촉을 받는 보호자는 요약이 계속 밀릴 수 있으나 수용한다(건별 재촉으로 이미 알렸다). 선점 후 발송·UNIQUE는 그대로.
- **ANOM-G14**: 이력 쿨다운은 저장 전 선점, 메서드 안 예외는 즉시, 커밋 실패·결과 불명(`STATUS_UNKNOWN`)은 `afterCompletion`에서 해제한다(이미 구현돼 있었고 테스트로 고정). **ANOM-G06**: 동수 상황의 미응답 보호자는 재촉도 동수 안내도 받지 않는다 - 의도된 동작으로 확정(`unansweredGuardianOfTieGetsNothing`).
- **ANOM-G08**: `camera.is_active`는 표시용이며 Swagger(`PATCH /api/ward/camera/{id}`·`isActive` 필드)에 "사용 중지해도 화재 감지와 알림은 계속된다(보호자 화면에서만 숨겨짐)"를 명시했다(`CameraIsActiveApiDocTest`). 감지·이력·클립은 `findOwnerBySessionId`(is_active 무시), 보호자 목록·시청·분석 상태만 꺼진 카메라를 뺀다.
- **연결 요청 전 상대 확인 (CONN-G06)**: 사용자 ID는 대소문자를 구분하고 서버는 정확히 일치하는 ID만 찾는다(대소문자 무시 조회는 엉뚱한 사람에게 요청이 간다). `GET /api/guardian/connection/preview?targetId=`는 `{targetId, maskedName}`만 돌려주고 판정은 실제 요청과 같은 `validateConnectionRequest`를 쓴다(요청·알림·카운트 없음, 보호자 기준 속도 제한). **수락 전 연락처·주소를 이 응답에 추가하지 말 것.** 2026-10-02의 "열거 위험으로 조회 API를 만들지 않는다"는 판단은 이 최소 안(가린 이름 + 기존 오류 코드가 이미 드러내는 정보)으로 갱신한다.
- **강제 연결 WS는 알림보다 먼저 (CONN-G02)**: 양쪽 `connection-accepted` WS를 `dispatch`보다 먼저 보낸다(설정 DB 오류가 피보호자 화면 갱신을 막지 않게). 알림은 `SETTINGS_ONLY` 그대로.
- **세션 만료 뒤 FCM 토큰 해제 (XAREA-G01)**: `POST /api/notifications/fcm-token/release`(본문 `{accessToken, token}`)만 만료된 access token(서명·`typ=access` 검사, 만료 후 refresh 수명 이내)을 받는다. 할 수 있는 일은 **본인 소유 FCM 토큰 삭제뿐**이다. 이 만료 허용 파싱(`getAccessTokenSubjectAllowingExpired`)을 인증 수단이나 다른 경로로 넓히지 말 것. 이 경로는 로그아웃 블랙리스트·무효화를 보지 않는다(잃는 것이 없는 조작). IP 기준 속도 제한 1분 10회. permitAll은 이 경로 하나뿐.
- **보류 유지**: XCUT-G31(HttpOnly 쿠키)은 별도 세션에서 진행한다. **XCUT-G06(탭 경합) 직전 refresh 토큰 유예는 구현하지 않았다** - QA 재현 경로(옛 토큰을 약 30분 뒤 다시 냄)를 10초 유예가 해결하지 못하고, 유예 안에 새 쌍을 발급하면 계보가 갈라져 단일 기기 정책·회전 재사용 감지(H-3)가 약해지며 설계안 결정 질문 4번이 미결이다. 탭 간 갱신 직렬화는 FE 몫.
