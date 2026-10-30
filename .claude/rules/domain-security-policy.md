# 도메인 보안 정책 메모

> 코드만으로는 드러나지 않는 보안 정책 결정의 의도·이력을 기록한다.
> (이 파일은 `paths:` 스코프 없이 매 세션 로드된다. CLAUDE.md §8에서 요약을 참조한다.)


## 도메인별 정책 파일 (2026-10-30 분리 - 토큰 절감)

도메인 규칙은 아래 파일로 나눴고, 각 파일은 해당 코드 경로를 다룰 때만 자동 로드된다(`paths:`).
**코드를 열지 않고** 그 도메인 작업을 설계(work-prompt)·점검·답변할 때는 해당 파일을 먼저 Read 한다 - 이 파일만 보고 "규칙 없음"으로 판단하지 말 것.

- `policy-auth.md` - 계정·인증 (auth·user·토큰·가입·탈퇴)
- `policy-connection.md` - 보호자-피보호자 연결
- `policy-sos.md` - SOS
- `policy-anomaly.md` - 이상감지 (감지·판정·재촉·클립·관리자 이상감지 화면)
- `policy-camera.md` - 카메라·실시간 영상 중계
- `policy-medication.md` - 복약
- `policy-notification.md` - 알림 채널·발송 이력
- `policy-chat.md` - AI 챗봇 중계·관리자 AI 통계
- `policy-admin.md` - 관리자 회원·병원·대시보드
- `policy-dashboard.md` - 보호자 대시보드 통합 API

## IDOR 응답 — 404 위장 → 403 명시 안내 전환 (2026-07-14)

- **의도**: 타인 자원(카메라·문의) 접근 시 기존에는 **404로 위장**해 존재 자체를 숨겼다(enumeration 차단). 그러나 "왜 안 보이지"로 이탈하는 시니어/4050 UX를 우선해, **무슨 일이 일어났는지 그대로 안내**한다. 비밀번호 재설정(2026-05-23)과 **같은 판단**이다.
- **변경**: `CAMERA_NOT_AUTHORIZED`(403, "본인이 등록한 카메라만 사용할 수 있습니다.") 신설, `INQUIRY_NOT_AUTHORIZED`를 404→**403**("본인이 작성한 문의만 볼 수 있습니다.")로 변경. 없는 자원은 **그대로 404**(`*_NOT_FOUND`).
- **수용한 노출**: "그 id의 자원이 존재한다"는 사실만 드러난다. **내용은 주지 않는다**(방 이름·세션ID·문의 본문 미노출).
- **보완**: 타인 자원 접근 시도는 `[IDOR-ATTEMPT]` **WARN 로깅**(userId + 대상 id). 반복 시도 탐지의 근거를 남긴다.
- **불변 규칙**: 403은 "본인 것이 아님"만 알린다 — 응답에 **소유자·내용 정보를 절대 싣지 말 것**. 새 도메인도 같은 형태(`<도메인>_NOT_AUTHORIZED` 403 + `[IDOR-ATTEMPT]` WARN)를 따른다.
- **문구는 수신자 기준으로 (2026-08-06 추가)**: 같은 도메인이라도 **보호자 경로와 피보호자 경로는 ErrorCode를 나눈다**. 복약은 `MEDICATION_NOT_AUTHORIZED`("연결된 피보호자의 복약 정보만…", 보호자용)와 `MEDICATION_NOT_OWNED`("본인의 약만 체크할 수 있습니다.", 피보호자용)로 분리했다 — 피보호자에게 "연결된 피보호자"라고 안내하면 뜻이 통하지 않아, **404 위장을 버린 이유(무슨 일이 일어났는지 그대로 안내) 자체가 무의미해진다**. 상태코드(403)·`[IDOR-ATTEMPT]` WARN·소유자 정보 미포함은 그대로다. 코드를 아끼려고 한 도메인에 하나만 두지 말 것.
- 상세: `docs/(2026-07-14) fix-audit-findings.md`, `docs/(2026-08-06) fix-audit-findings.md`(문구 분리).


## 운영 설정 - 수용한 한계와 보강 (2026-09-11)

- **실사용 도메인(`api.devdmu.gosky.kr`)의 Swagger·api-docs 무인증 공개는 수용한 한계다** (기술 점검 E-1): 그 서버는 우리가 관리하는 인프라가 아니라 `SWAGGER_ENABLED`를 끌 권한이 없다. 전 엔드포인트·DTO·에러 문구가 보이는 것을 알고 둔다. 관리 권한이 생기면 `SWAGGER_ENABLED=false`로 끄고 vkcs(도메인 없음)에서만 켠다. Swagger 설명문에 **시크릿·내부 호스트·계정 정보를 적지 말 것**(정책 근거 설명은 이미 공개돼 있다).
- **알림 executor는 포화 시 폐기한다** (B-2): `CallerRunsPolicy`를 버렸다 - 포화 시 AI WS 수신 스레드·HTTP 요청 스레드가 FCM·SMS 응답을 기다리게 되어 `@Async`를 둔 이유가 사라진다. 큐 500, 넘치면 `[NOTIFY-REJECTED]` ERROR 로그 후 폐기. "알림을 버리면 안 되니 CallerRuns로"로 되돌리지 말 것 - 화재 다발 시점에 정확히 AI 수신이 멈춘다.
- **우아한 종료** (B-3): `server.shutdown=graceful` + executor 종료 대기 20초. 배포마다 컨테이너가 교체되므로 이것이 없으면 그 순간 큐에 있던 SOS 알림이 사라진다.
- **스케줄러 풀 3스레드** (B-1): 기본 1스레드에 스케줄러와 AI WS 재접속 예약이 함께 줄을 선다. 복약 Planner가 느려지면 AI 재접속이 밀려 그 사이 화재 신호를 놓친다. 스케줄러를 추가하면 이 값을 다시 본다.
  - **재검토 (2026-09-22, 알림 이력 점검 L-2)**: 6종이 됐다 - 주기형 3종(복약 1분·판정 재촉 5분·탈퇴 스윕 10분) + 일일 정리 3종(토큰 03:00·FCM 토큰 04:00·알림 이력 04:30). 일일 정리는 시각을 비껴 두어 주기형과만 겹칠 수 있고, 3스레드로 감당된다고 보고 **3을 유지**했다. 일일 작업을 같은 시각에 몰아 넣지 말 것.
  - **재검토 (2026-10-31 전체 점검)**: 8종이다 - 위 6종 + 클립 청소 일일 05:00·고아 파일 매시 :15(2026-10-04). 일일 03:00·04:00·04:30·05:00과 매시 :15는 겹치지 않고 주기형 3종과만 겹칠 수 있어 **3을 유지**했다.
- **외부 연동 타임아웃**: 카카오(기존)·SMTP(C-1, 각 10초)·파일서버(C-2, connect 3초·read 10초)·Solapi(P16, 호출 10초 - 아래). 새 HTTP 클라이언트를 추가할 때 타임아웃 없이 두지 말 것 - 요청 스레드를 붙든다.
  - **FCM에도 시간 제한을 걸었다 (2026-09-30 전체 점검 M-2)**: `FcmConfig`에 연결 3초·응답 10초. Solapi는 SDK 제한이 50초 고정이라 별도로 감쌌다(아래 P16). FCM이 느려지면 `notificationExecutor`가 묶여 뒤따르는 SOS·화재 알림이 밀린다.
- **긴급 알림 전용 executor (2026-10-02 QA SOS-G13·XCUT-G11)**: SOS·이상감지 발생 알림(`SosNotificationListener`·`AnomalyNotificationListener`)은 `urgentNotificationExecutor`(core 4 / max 8 / queue 200, 스레드명 `notify-urgent-`)를 쓰고, 연결·문의·복약 체크·카카오 가입 접속로그는 `notificationExecutor`에 남는다. 포화·종료 정책은 일반 풀과 같다(폐기 + `[NOTIFY-REJECTED]` ERROR, CallerRuns 금지, 종료 대기 20초). **긴급 리스너를 일반 풀로 되돌리거나 일반 알림을 긴급 풀에 태우지 말 것**(`NotificationExecutorAssignmentTest`가 고정). 새 `@Async` 리스너는 executor 이름을 명시한다.
  - **Solapi 호출 시간 제한 (2026-10-02 P16, 위 50초 한계를 해소)**: SDK 1.0.3은 연결·읽기·쓰기 50초 고정(설정 불가)이라 `SolapiCallExecutor`(전용 데몬 풀 10 + 큐 20, 포화 시 즉시 실패, CallerRuns 금지)에서 호출하고 `solapi.call-timeout-seconds`(기본 10초, `SOLAPI_CALL_TIMEOUT_SECONDS`)만 기다린다. 초과·포화는 기존 통신 오류와 같게 처리된다: 알림 이력 `PROVIDER_ERROR`, 인증번호는 `SMS_SEND_FAILED` + 발송 한도 환불. **수용한 한계**: SDK가 인터럽트를 무시하면 풀 스레드는 50초까지 남지만 긴급 알림 스레드는 풀려난다 / 늦게 접수된 건도 실패로 기록될 수 있다. **SDK 호출을 호출 스레드로 되돌리거나 CallerRuns로 바꾸지 말 것.**
  - **해소 (2026-10-05, XCUT-G11)**: `AlimtalkSender`·`SmsSender`의 실패 로그는 예외 클래스명과 발송사 상태 코드(영숫자 16자 이하)만 남긴다(`SolapiFailureCodes`). `getFailedMessageList()`에는 수신·발신 번호가 들어 있으니 로그에 찍지 말 것. **전수 점검 완료 (2026-10-06)**: 요청 값·이메일·기기 토큰·payload 가 섞일 수 있는 예외 메시지·스택을 로그에서 걷어냈다 - `GlobalExceptionHandler`(요청 바디 파싱 실패·타입 불일치·DB 무결성 위반의 `Key (email)=(값)`), `PasswordResetService`(MailException 수신자 주소), `NotificationDispatcher`(채널 예외 객체째 로깅하던 것), `FcmService`(오류 코드 열거형만), `WebSocketEventPublisher`, `FileServerClient`, `KakaoOAuthClient`, `JwtTokenProvider`(파싱 실패), `SosNotificationCooldown`·`SosNotificationListener`(`SosNotificationCooldownTest`도 원문 미노출을 고정), `ApiLoggingAspect`(CustomException 외에는 클래스명). **새 로그에 `e.getMessage()`나 예외 객체를 넘기지 말 것** - 클래스명·고정 코드만(`LogRawExceptionGuardTest`가 점검한 파일들을 고정한다). 의도적으로 남긴 곳: 스케줄러·리스너의 `log.error("...", e)`(내부 DB·로직 오류 진단용, 알림 본문·수신처를 다루지 않음)·`UnhandledException`(미처리 오류 스택)·`AiLiveStreamSubscriber`(AI WS, 개인 정보 없음). **공용 예외 처리기도 전부 걷어냈다 (2026-10-28, AI 챗 집계 점검 L-1 후속)**: `GlobalExceptionHandler`의 남은 원문 로그 10곳을 이름·클래스명·발생 위치로 바꿨다 - IAE는 `type`·`at`(우리 패키지 첫 프레임 파일:줄)만(JDK 헤더 값 오류는 AI 키 원문을, `NumberFormatException`은 사용자 입력을 메시지에 담는다), 지원하지 않는 Content-Type은 값 없이(요청자가 줄바꿈으로 가짜 로그 줄을 만들 수 있다), `CustomException`·429는 오류 코드 이름, 낙관적 잠금은 엔티티 클래스명만(ID 없음). 이 파일도 `LogRawExceptionGuardTest` 목록에 들어갔고 `GlobalExceptionHandlerLogTest`가 로그 내용을 고정한다. 응답은 바뀌지 않았다.
- **DB·Redis 포트는 로컬 전용이다 (2026-09-30 전체 점검 C-1)**: `docker-compose.dev.yml`의 db·redis는 `127.0.0.1:`에만 묶는다. 전 인터페이스(`"6514:6379"`)로 되돌리지 말 것 - gosky는 호스트 방화벽이 없어 그대로 인터넷에 노출되고, Redis는 비밀번호가 없는 채로 비밀번호 재설정 코드·본인확인 통과 표시·토큰 무효화 키를 담는다(읽히면 임의 계정 탈취). 2026-09-30까지 실제로 열려 있었고 스캐너 접근 흔적이 있었다(파괴 흔적은 없음, 읽기 여부는 확인 불가). 외부에서 DB를 볼 때는 SSH 터널. CD는 api만 재기동하므로 compose의 db·redis 설정을 바꾸면 두 서버에서 `up -d`를 직접 돌려야 반영된다.
- **Redis 퇴출 정책 `noeviction` (2026-10-02 AUTH-G30, P10)**: `docker-compose.dev.yml`의 Redis는 `maxmemory 256mb`, `maxmemory-policy noeviction`이다. 로그인 잠금·로그아웃 블랙리스트·토큰 무효화·인증코드 키가 메모리 압박으로 임의 삭제되면 잠금 해제·로그아웃 토큰 부활이 생긴다. 모든 쓰기에 TTL이 있음을 확인했다(키 수는 활성 사용자·요청량에 비례). 대신 가득 차면 쓰기가 OOM 오류가 되므로 `INFO memory`·`evicted_keys`·`DBSIZE`를 점검하고 사용량이 70~80%를 넘으면 원인 키를 확인한다. **`allkeys-lru`로 되돌리지 말 것.** CD의 `up -d api`는 compose 변경이 있으면 redis도 재생성한다(`appendonly yes`라 키는 대부분 유지, 최대 약 1초분 쓰기 유실 가능). 상세 `docs/(2026-10-02) infra-redis-eviction-policy.md`.
- **감사 로그 detail은 DB에만** (E-2): `AdminAuditLogService`의 SLF4J 출력에는 adminId·action·targetId까지만 적는다. detail에는 이름·이메일이 들어가고 컨테이너 stdout은 로그 수집 경로다.
- 상세: `docs/(2026-09-10) feature-admin-force-connection.md`.


## 2026-10-02 QA(BE) 반영 - 인증 저장소 장애·토큰·입력 정규화·알림 결과 (P0~P9)

> 전 묶음(PR #267~#283)이 dev에 머지·배포된 뒤 현재 dev 코드로 확인했다(2026-10-02). 상세 표: `docs/(2026-10-02) fix-qa-be-issues.md`.


### 연결·복약·이상감지·관리자 (i~l)

- **연결**: 강제 연결·해제 WS 페이로드에 `type`/`title`/`body`를 싣는다. 수락 시 **양쪽 역할을 다시 검증**(`INVALID_CONNECTION_ROLE` - 역할 변경 뒤 낡은 요청 수락 방지)하고, 동시에 같은 연결을 만들면 `CONNECTION_ALREADY_EXISTS`로 응답한다.
- **복약**: 보호자가 없는 피보호자의 약은 알림 대상에서 제외하되 **약은 보존**한다(보호자 재연결 시 복구). 복용 시각을 **과거 시각으로 수정하면 당일 발송 기록을 유지**한다(지난 시각으로 알림이 다시 나가면 거짓 알림). **오늘 늦게 등록한 약은 그날 미복용 요약 집계에서 제외**한다(등록 전 시각의 약이 "체크되지 않음"으로 잡히는 것 방지). 문구 "체크되지 않았습니다"·집계 상한 = 발송 시각 규칙은 그대로다.
- **이상감지**: 하루 요약에서 **건별 재촉이 요약 시각 2시간 이내에 이미 나간 상황은 제외**한다(같은 상황 이중 알림 방지). 이력 쿨다운은 **저장에 실패하면 해제**한다(이력이 없는데 쿨다운만 남아 다음 감지가 버려지는 것 방지).
- **관리자**: 공지 조회수는 원자 증가이고 `updated_at`은 내용이 바뀔 때만 갱신한다(**V56 트리거**). 목록 파라미터는 `page`(0 이상, 기본 0)/`size`(기본 20, 최대 50)로 통일하고, 공지·문의 목록 본문은 100자로 축약한다. 문의 작성은 분당 5회로 제한하고, 연결 목록 필터에서 관리자 계정은 제외한다.


### 구현으로 확정된 항목 (이전 보류 목록에서 이동)

- **ADMIN-G27/CONN-G08, ADMIN-G28/ANOM-G10, SOS-G13/XCUT-G11, CONN-G04/XCUT-G29**는 모두 구현·배포됐다. 정책은 각각 "관리자 회원관리" 규칙 ⑦(WS 수신자 차단·대시보드 지표), "운영 설정"(긴급 executor), "연결 요청 반복 제한" 절에 있다.


### 남은 보류 (의도적으로 만들지 않았다)

- **CONN-G02**: 강제 연결 알림의 FCM 승격 - **현행 유지로 확정**(`CONNECTION_FORCED`는 설정 기반 발송). 강제 채널로 올리지 말 것.
- **XCUT-G31**: refresh 토큰 HttpOnly 쿠키 전환 - 설계안만 작성(`docs/(2026-10-02) design-refresh-token-httponly-cookie.md`), 구현은 별도 세션에서 진행한다(A안 추천, 결정 질문 9개).
- **세션 강제 종료**: **구현됨**(2026-10-02 P15, #287) - 위 "관리자 회원관리" 규칙 ⑦의 "열린 WebSocket 세션" 항목 참조. 단일 인스턴스 한정.
- **CONN-G15 경합 - 해소 (2026-10-05, PR #309)**: 연결을 새로 만드는 두 경로(`requestConnectionAsGuardian`·`AdminConnectionService.forceConnect`)는 역할을 읽기 전에 두 사람의 users 행을 `FOR SHARE`(id 순)로, 관리자 회원 수정(`AdminUserService.updateUser`)은 연결 정리 전에 대상 users 행을 `FOR NO KEY UPDATE`로 잠근다(순서 users → connection, 탈퇴 purge·클립 기록과 같아 교착 없음). 요청 생성은 INSERT라 `@Version`으로 못 막고, 역할 변경 쪽 조회가 미커밋 요청을 못 봐 보호자가 된 사용자 앞으로 PENDING이 남던 문제였다. **잠금을 빼거나 검증 뒤로 옮기지 말 것. 역할 변경 쪽을 `FOR UPDATE`로 바꾸지 말 것**(클립의 `FOR KEY SHARE`와 충돌). 수락 경로는 기존 행 UPDATE라 `@Version`으로 충분해 바꾸지 않았다. 실 DB 검증: `ConnectionRoleChangeRaceIntegrationTest`.
- (ANOM-G08은 위 "카메라 등록·중지 카메라"로 종결.)


## 2026-10-05 QA(BE) 잔여 25건 처리 (PR #306·#307·#308·#309)

> 항목별 결과·FE 전달은 Notion "QA(BE) → FE 전달". 아래는 코드만으로 드러나지 않는 결정만 적는다. Notion QA(BE)의 9/30 기준 설명 중 이미 고쳐진 항목이 섞여 있었다(MED-G16·SOS-G12·MED-G05·XCUT-G19·AUTH-G08·XCUT-G16·FEUX-G11·FEUX-G12·FEUX-G34·XCUT-G30는 코드 변경 없이 종결, AUTH-G16은 동시 제출 테스트만 추가).

- **토큰 무효화 값이 손상되면 조회 오류와 똑같이 처리한다 (XCUT-G03)**: 숫자 아님·음수·현재 시각 +300초 초과면 일반 경로 503, `POST /api/ward/sos`만 통과, 로그만 `[AUTH-STORE-CORRUPT]` ERROR로 나눈다(`TokenInvalidation.parseEpochSecond`). **손상 값을 "무효화 없음"으로 통과시키지 말 것**(그 키가 정지·비밀번호 변경의 흔적일 수 있다). **401로 답하지도 말 것**(재로그인한 새 토큰까지 같은 키에 걸려 로그인 반복). 로그인·비밀번호 확인 잠금, 인증번호 시도·발송 상한 카운터의 Redis 오류는 **fail-closed(장애 중에는 풀어주지 않음)이되 응답은 503 `SERVICE_UNAVAILABLE`이다**(2026-10-06 결정: 서버 코드 결함이 아니라 의존 서비스의 일시 장애라 500이 아니다). `GlobalExceptionHandler.handleStoreUnavailable`이 `RedisConnectionFailureException`·`RedisSystemException`·`QueryTimeoutException`을 한 곳에서 503으로 바꾼다 - 잡히지 않고 올라온 예외만 대상이라 fail-open으로 삼키는 곳(속도 제한·SOS 쿨다운 등)은 그대로다. 카운터마다 따로 503을 만들지 말고, 이 핸들러에서 Redis 예외를 500으로 되돌리지 말 것. 로그는 `[STORE-UNAVAILABLE]`에 예외 클래스명만 남긴다.
- **Redis 명령·연결 시간 제한 2초 (XCUT-G11)**: `REDIS_TIMEOUT`·`REDIS_CONNECT_TIMEOUT`. 인증 필터와 SOS 쿨다운이 Redis를 거치므로 제한이 없으면 Lettuce 기본 60초로 요청·긴급 알림 스레드가 묶인다. 블로킹 명령을 도입하면 다시 볼 것.
- **미응답 요약은 보호자 단위로도 미룬다 (ANOM-G09)**: 상황 단위 제외(재촉이 요약 시각 -2시간 이후에 나간 상황 제외)에 더해, 어떤 상황이든 건별 재촉을 받은 지 2시간이 안 된 보호자는 그 주기에 요약을 선점하지 않고 다음 주기에 다시 판단한다(재촉 기록 전부 기준, 응답 여부 무관). 같은 보호자에게 오래된 다른 미응답 상황이 남아 있으면 같은 실행에서 재촉과 요약이 함께 나가던 문제였다. 저녁마다 새 재촉을 받는 보호자는 요약이 계속 밀릴 수 있으나 수용한다(건별 재촉으로 이미 알렸다). 선점 후 발송·UNIQUE는 그대로.
- **공개 공지 목록도 나눠 받고 본문을 줄인다 (ADMIN-G24, 2026-10-06 정정)**: 위 "코드 변경 없이 종결" 목록에 ADMIN-G24를 넣은 것은 사실과 달랐다. 10/5(#306~#309)에는 관리자 공지·임시저장·내 문의 목록만 고쳐졌고 공개 `GET /api/commonness/announcement/select`는 전체·전문 반환이 남아 있었다. 10/6에 같은 공통 코드(`AdminPaging`·`TextSummary`)로 맞췄다: 선택 `page`(기본 0)/`size`(기본 20, 최대 50), 본문 앞 100자 축약, 최신순(+id 내림차순), 응답은 배열 그대로. 전체 본문은 상세 `GET .../select/detail/{id}`만 준다. 마이그레이션 없음.
- **ANOM-G14**: 이력 쿨다운은 저장 전 선점, 메서드 안 예외는 즉시, 커밋 실패·결과 불명(`STATUS_UNKNOWN`)은 `afterCompletion`에서 해제한다(이미 구현돼 있었고 테스트로 고정). **ANOM-G06**: 동수 상황의 미응답 보호자는 재촉도 동수 안내도 받지 않는다 - 의도된 동작으로 확정(`unansweredGuardianOfTieGetsNothing`).
- **ANOM-G08**: `camera.is_active`는 표시용이며 Swagger(`PATCH /api/ward/camera/{id}`·`isActive` 필드)에 "사용 중지해도 화재 감지와 알림은 계속된다(보호자 화면에서만 숨겨짐)"를 명시했다(`CameraIsActiveApiDocTest`). 감지·이력·클립은 `findOwnerBySessionId`(is_active 무시), 보호자 목록·시청·분석 상태만 꺼진 카메라를 뺀다.
- **연결 요청 전 상대 확인 (CONN-G06)**: 사용자 ID는 대소문자를 구분하고 서버는 정확히 일치하는 ID만 찾는다(대소문자 무시 조회는 엉뚱한 사람에게 요청이 간다). `GET /api/guardian/connection/preview?targetId=`는 `{targetId, maskedName}`만 돌려주고 판정은 실제 요청과 같은 `validateConnectionRequest`를 쓴다(요청·알림·카운트 없음, 보호자 기준 속도 제한). **수락 전 연락처·주소를 이 응답에 추가하지 말 것.** 2026-10-02의 "열거 위험으로 조회 API를 만들지 않는다"는 판단은 이 최소 안(가린 이름 + 기존 오류 코드가 이미 드러내는 정보)으로 갱신한다.
- **강제 연결 WS는 알림보다 먼저 (CONN-G02)**: 양쪽 `connection-accepted` WS를 `dispatch`보다 먼저 보낸다(설정 DB 오류가 피보호자 화면 갱신을 막지 않게). 알림은 `SETTINGS_ONLY` 그대로.
- **세션 만료 뒤 FCM 토큰 해제 (XAREA-G01)**: `POST /api/notifications/fcm-token/release`(본문 `{accessToken, token}`)만 만료된 access token(서명·`typ=access` 검사, 만료 후 refresh 수명 이내)을 받는다. 할 수 있는 일은 **본인 소유 FCM 토큰 삭제뿐**이다. 이 만료 허용 파싱(`getAccessTokenSubjectAllowingExpired`)을 인증 수단이나 다른 경로로 넓히지 말 것. 이 경로는 로그아웃 블랙리스트·무효화를 보지 않는다(잃는 것이 없는 조작). IP 기준 속도 제한 1분 10회. permitAll은 이 경로 하나뿐.
- **보류 유지**: XCUT-G31(HttpOnly 쿠키)은 별도 세션에서 진행한다. **XCUT-G06(탭 경합) 직전 refresh 토큰 유예는 구현하지 않았다** - QA 재현 경로(옛 토큰을 약 30분 뒤 다시 냄)를 10초 유예가 해결하지 못하고, 유예 안에 새 쌍을 발급하면 계보가 갈라져 단일 기기 정책·회전 재사용 감지(H-3)가 약해지며 설계안 결정 질문 4번이 미결이다. 탭 간 갱신 직렬화는 FE 몫.


## 웹 응답에 Jackson 2 타입을 싣지 말 것 (2026-10-08)

- Spring Boot 4 웹 응답 변환기는 Jackson 3(`tools.jackson`)이다. `com.fasterxml.jackson.databind` 의 `JsonNode`·`ObjectNode` 를 컨트롤러가 반환하면 모르는 타입이라 bean getter(array·nodeType…)가 직렬화된다(챗 중계가 이 때문에 한때 사용 불가).
- 응답 경계(서비스)에서 일반 객체·DTO 로 바꾼 뒤 반환한다. 컨트롤러 테스트는 실제 HTTP 직렬화를 거친다(`GuardianChatControllerHttpTest`).

## 2026-10-31 전체 점검 반영 - 공통

- **원문 로그 금지 대상 추가**: `JwtTokenProvider`(만료 메시지)·`JwtAuthenticationFilter`·`JwtHandshakeInterceptor`(토큰 손상 사유는 고정 코드 `TokenInvalidation.CorruptValueException.reason()`)·`AnomalyEventCooldown`·`AnomalyReviewReminderService`(dispatch 경로라 스택째 남기지 않음)를 `LogRawExceptionGuardTest`에 넣었다. 대시보드 IDOR 로그는 SOS 이력처럼 형식 오류 wardId를 길이만 남긴다.
- **동기 AFTER_COMMIT 리스너 표 (`SyncAfterCommitListenerPropagationTest`)**: `@Async` 없는 AFTER_COMMIT 리스너는 스스로 REQUIRES_NEW이거나, 표에 적은 위임 대상이 모두 REQUIRES_NEW이거나, 쓰기가 없다는 이유를 표에 적어야 한다. 새 동기 리스너를 만들면 이 테스트가 실패한다 - 표에 넣어 정하고, 리스너에 저장소 쓰기를 직접 넣지 말 것.
- **챗 전송 본문 상한**: `ChatRequestSizeFilter`(512KB, 413 `CHAT_REQUEST_TOO_LARGE`) - policy-chat.
- **결정 대기(2026-10-31)**: D-1 재연결 시 공백 기간 이력(SOS·이상감지는 지금 "요청 시점 ACTIVE"만 본다 - 재연결·강제 연결 시 과거 전체가 보인다) / D-2 익명 보존 데이터(`access_logs` IP·UA, `admin_audit_log.detail`, `sos_event.location`) 보관 기간. 상세 `docs/(2026-10-31) audit-full-2026-10.md`.
