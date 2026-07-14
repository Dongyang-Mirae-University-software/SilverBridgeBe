# 진행 기록

프로젝트 작업 중 누적되는 점검·리뷰 결과를 영역별로 정리한다.

---

### [2026-06-09] 피보호자 SOS 긴급 알림 기능 스팟 점검 — PASS

신규 SOS 기능(`POST /api/ward/sos`, PR #199 / `d66343f`)을 생명 관련 긴급 기능 기준으로 점검 — **"알림이 확실히 가는가"** 최우선. **코드 변경 없음(점검만).** 빌드·SOS 단위 테스트 3클래스 모두 성공.

- **PHASE A 알림 확실성 — 전 항목 PASS**: ① 필수 알림 `WARD_SOS(mandatory=true)`→dispatcher가 사용자 설정 무시·`MANDATORY_CHANNELS{FCM}` 강제 발송 ② `getActiveGuardianIds`(ACTIVE만) for 루프 전원 발송, PENDING/CANCELLED 제외 ③ 실패 격리: 보호자별 try/catch + `WebSocketEventPublisher.sendToUser` 자체 예외 삼킴(WS 실패가 FCM 미차단) + dispatcher 채널별 격리 ④ `AFTER_COMMIT`으로 이력 커밋 후 발송→발송 전량 실패해도 `sos_events` 보존 ⑤ 본문에 `wardName` 포함. 추가: `AsyncConfig` `CallerRunsPolicy`로 큐 포화 시에도 알림 드롭 없음.
- **PHASE B 인가 PASS**: `@PreAuthorize("hasRole('WARD')")`(GUARDIAN/ADMIN 403), `wardId`는 `@AuthenticationPrincipal`만(사칭 불가), `/topic/{guardianId}/sos-triggered`는 STOMP 범용 `{userId}==세션` 인가로 보호.
- **PHASE C/D PASS**: 트랜잭션 경계·AFTER_COMMIT·기존 connection 패턴 일관, N+1 없음, V26 최신·`ON DELETE SET NULL`. 핵심 신뢰성 케이스(필수/전원/격리/이력/403/보호자0) 단위 테스트 고정.
- **이슈**: 🔴/🟠 **0건**. 🟡 M-1(SOS 필수 채널 FCM 단독 — 오프라인 보호자 미수신 가능, SMS 폴백 검토 권고) · M-2(중복 SOS 쿨다운 부재 — 이력 보존하며 알림만 쿨다운/프론트 디바운싱 권고, 차단형 지양). 🟢 L-1(201 vs 200) · L-2(wardName null 가드). 전부 선택적 강화이며 현재 구현 결함 아님.
- **종합 판정**: ✅ PASS — 머지 상태 양호, 추가 조치 없이 운영 가능.
- 산출물: `docs/(2026-06-09) audit-spot-check-ward-sos.md`.

---

### [2026-06-09] SOS 스팟 점검 이슈 4건 반영 (branch `feature/ward-sos-audit-fixes`)

위 점검에서 나온 M-1·M-2·L-1·L-2를 모두 코드에 반영. "이력은 무조건 남는다"·"긴급 재요청 차단 안 함"·"인프라 장애 fail-open" 원칙 유지.

- **M-1 조건부 SMS 폴백**: 필수 알림 기본 FCM, 보호자에게 FCM 토큰이 없을 때만 SMS 추가(`NotificationDispatcher.mandatoryTargets`, `FcmService.hasToken`/`FcmTokenRepository.existsByUserId` 신규). 정상 보호자는 SMS 비용 0, 푸시 미도달 보호자만 보강. (초안의 '항상 FCM+SMS' → 사용자 결정으로 '토큰 없을 때만'으로 전환.)
- **M-2 알림 쿨다운**: `SosNotificationCooldown`(Redis SET NX EX 30초). 알림만 생략하고 `sos_events` 이력은 보존, 429 차단 없음, Redis 장애 시 fail-open.
- **L-1**: `WardSosController` → `201 Created`.
- **L-2**: `SosService` wardName 공백 시 `"보호 대상자"` 폴백.
- **테스트**: 신규 `SosNotificationCooldownTest` + 리스너 쿨다운/서비스 폴백/디스패처 토큰분기 테스트. SOS·디스패처 테스트 및 `build -x test` BUILD SUCCESSFUL.
- 산출물: `docs/(2026-06-09) feature-ward-sos.md` §8, `docs/(2026-06-09) audit-spot-check-ward-sos.md` §9 갱신.

---

## [2026-06-09] — sos: 피보호자 긴급 SOS 기능 구현 (PR `feature/ward-sos`)

피보호자(WARD)가 긴급 SOS 버튼을 누르면 발생 이력을 남기고 연결된 ACTIVE 보호자 전원에게 긴급 알림(FCM + WebSocket)을 보내는 기능.

- **핵심 정책**: SOS 알림은 **필수 알림** — 보호자 알림 설정(ON/OFF)과 무관하게 무조건 발송. 연결된 ACTIVE 보호자 **전원** 발송(한 명 실패가 다른 보호자 막지 않음). 피보호자(WARD 역할)만 발생 가능.
- **엔드포인트**: `POST /api/ward/sos` (WARD 전용, `@PreAuthorize`). 요청 바디 없음, 응답 `{ sosEventId, triggeredAt }`.
- **신규 도메인 `domain/sos/`**: 엔티티/리포지토리/DTO/이벤트(`SosTriggeredEvent`)/서비스/컨트롤러/리스너. **신규 패턴 없이** 기존 connection 알림 흐름을 그대로 미러링.
- **이력 테이블 V26 `sos_events`**(id/ward_id/created_at): 발송 보호자 목록은 미저장(알림은 커밋 후 AFTER_COMMIT 발송이라 저장 시점 미확정 + 정규화). `ward_id`는 탈퇴 hard delete 시 `ON DELETE SET NULL`로 익명 보존(access_logs와 동일).
- **필수 알림 분류**: `NotificationType.WARD_SOS(true)` 추가 — 디스패처가 `isMandatory()`면 사용자 설정을 무시하고 `MANDATORY_CHANNELS`(FCM)로 강제 발송. enum javadoc이 예고했던 "긴급 알림용 확장 지점"의 **첫 사용처**(AI 이상감지도 향후 동일 메커니즘 사용 예정 — 2026-05-31 설계 참조).
- **흐름**: `SosService.trigger`(@Transactional) 이력 저장 + `SosTriggeredEvent` 발행 → `SosNotificationListener`(@Async, AFTER_COMMIT)가 `ConnectionService.getActiveGuardianIds`로 보호자 전원 조회 → WS(`sos-triggered`) + 디스패처(WARD_SOS) 발송. **알림 실패가 이력 저장을 롤백시키지 않음**(이력은 무조건 보존).
- **범위 밖(프론트)**: 119 통화 화면=순수 프론트 연출(백엔드 무관), 보호자 직접 전화=기존 `/api/ward/connection/active`의 partnerPhone + `tel:` 링크.
- **rate limit 미적용(의도)**: 긴급 버튼 특성상 과도한 제한이 실제 위급을 차단할 위험.
- **테스트**: SosService 2 / SosNotificationListener 4(전원발송·필수알림 설정무시·보호자0명·실패격리) / WardSosController 권한 2(WARD 허용·GUARDIAN 403, 메서드 시큐리티 AOP). `./gradlew build` BUILD SUCCESSFUL.
- **산출물**: `docs/(2026-06-09) feature-ward-sos.md`.

---

## [2026-05-31] — AI 이상감지 WebSocket 연동 분석/설계 (구현 전, 분석+설계 전용)

AI 서버의 실시간 이상감지 신호(`latest_analysis`)를 백엔드가 WebSocket 클라이언트로 구독해 보호자에게 긴급 알림을 보내는 기능의 **연동 방법 확정 + 설계**. 구현은 다음 단계.

- **⚠️ 분석 중 저장소 오인 → 정정**: 처음엔 로컬 클론 `/home/skarndaudwls/SilverBridgeAiServer`(remote=`gosky2/SilverBridgeAiServer` **fork**, `62ddc9a` 2026-05-22)를 분석해 "WS 코드 없음"·"danger=threshold 미달"이라 **잘못 결론**. 사용자 지적으로 **정본 = `Dongyang-Mirae-University-software/SilverBridgeAiServer`**(main, 2026-05-30 push) 확인, `gh api`로 정본 코드 직접 판독해 전면 교정. **향후 AI 분석 기준은 `Dongyang-Mirae` org 저장소.**
- **실제 아키텍처(정본 ✅)**: iPad **프레임 인제스트 + WS 구독 broadcast**. iPad가 `POST /stream-sessions`(sessionId+cameraIdentifier 제공) → `POST /stream-sessions/{id}/frame`(JPEG) 송출 → AI가 `fire_smoke.pt`(YOLO)로 **15프레임마다** 분석(in-memory, **DB 미저장**) → 매 프레임 `session_status`+`latest_analysis`를 **해당 세션 구독자에게** broadcast. WS(`/api/v1/ws/live`)는 `action` 기반(connect→list→subscribe(sessionId)→푸시 수신, ping/pong).
- **`danger` 정정(코드 ✅)**: 라이브 경로에서 **`danger`는 항상 `False` 하드코딩**(`fire_smoke_detection_service` "표시 전용", `analyze_stream_frame` payload 고정). "fire인데 false"는 임계값 문제가 **아니라** danger가 판정필드가 아니기 때문 → **백엔드는 danger 무시, `detectedType`+`confidence`로 판단**.
- **검증된 코드 사실**: 라이브 detectedType ∈ `{normal, fire, smoke, unknown}`(TARGET_CLASSES={fire,smoke}, **fall/weapon 없음**), `FIRE_SMOKE_CONF_THRESHOLD` 기본 **0.35**(낮음→알림용 별도 임계 권장), `STREAM_SAMPLE_EVERY_N_FRAMES` 15, 끊김 타임아웃 10초, WS 인증=`x-api-key` 헤더 또는 `apiKey` 쿼리(단일 정적 키), 불일치 시 close 1008. `sessionId↔cameraIdentifier`는 세션 생성 시 확립+`live_streams` 목록에 둘 다 포함.
- **설계 제안(잠정)**: ① 백엔드가 WS 클라이언트로 구독(헤더 인증·지수백오프 재연결·동적 subscribe, 다중 인스턴스 중복 주의), ② 판단=`detectedType∈{fire,smoke}` + 백엔드 알림 임계값(기본 0.6~0.7), danger 무시, ③ **중복방지 3단**((sessionId,analyzedAt) dedup → 지속성 승격 → Redis 쿨다운 `anomaly:cooldown:{ward}:{type}` TTL N분), ④ `sessionId→cameraIdentifier→wardId` 매핑(live_streams로 맵 유지; wardId는 우리 자체 매핑표 권장), ⑤ `AnomalyDetectedEvent`(AFTER_COMMIT/@Async)→`anomaly_events` 저장→ACTIVE 연결로 보호자 조회→**긴급=필수 알림(설정 우회**, SMS 인증번호 선례), ⑥ `anomaly_events` 재생성(V17서 DROP)+`session_id`/`bbox`, event_type CHECK는 `FIRE/SMOKE`.
- **선행 차단(이하늘 확인)**: cameraIdentifier→wardId 매핑 합의·iPad 세션 등록 흐름·백엔드 구독 합의(#3·4·8), 운영 임계/빈도값(#2), fall/weapon 계획(#5) 등 9건 문서 §7.
- **testai.gosky.kr 시스템 구성 파악**: AI 서버(`SilverBridgeAiServer`, FastAPI/GPU :6017) + 테스트 FE(`SilverBridgeStreamTestFe`, React/Vite :6018)를 리버스 프록시로 한 도메인에 합친 데모 환경. 부속: MedGemma LLM(:6012), 예약 API(`SilverBridgeReservation`, NestJS/Prisma :6015, `reservation.dmu.gosky.kr` — 우리 BE는 병원예약 V24로 제거돼 분리된 별도 시스템), PostgreSQL(:6019). 운영 `.env`로 `STREAM_SAMPLE_EVERY_N_FRAMES=5`(분석 5프레임마다)·`REQUIRE_GPU=true`·`STREAM_STATE_BACKEND=memory`·`MEDIAMTX_ENABLED=false` 확인.
- **🔒 보안 경고**: 운영 `.env` 시크릿(`API_KEY`·OpenAI `GPT_API_KEY`·`HF_TOKEN`·DB 비번)이 채팅으로 공유됨 → **전부 회전 권장**(특히 OpenAI). 또한 `VITE_API_KEY`가 FE 정적 번들에 인라인돼 브라우저로 공개(=`API_KEY`와 동일 값, 테스트 FE에 하드코딩). 문서엔 값 마스킹.
- **문서 눈높이 정책**: 사용자가 Java/Spring 비전문가 → 산출물 상단에 **🟢 쉬운 설명** 블록 추가, 전문용어는 한 줄 풀이. (기억: [산출물은 비전문가 눈높이로])
- **규칙 준수**: AI/FE/예약 저장소 읽기 전용(수정/커밋 0), `.env` 미열람(값은 사용자 제공분만), 시크릿 평문 미기재.
- **산출물**: (2026-07-03 통합) 위 두 분석 문서 `ai-anomaly-websocket-integration-spec.md`·`testai-gosky-kr-system-overview.md`는 skyserver 배포본 SSH 실측으로 확증 후, AI 서버 자체 설명은 `docs/프로젝트_설명_AI서버.txt`로, 백엔드 연동 요청은 노션(DMU/AI서버팀)으로 각각 통합·이관하고 두 .md는 삭제.

---

## [2026-05-30] — hospital: 병원 예약 기능 완전 제거 (코드 + DB 테이블)

병원 예약 기능을 프로젝트에서 제외하기로 결정하여 관련 자산을 정리.

- **핵심**: 백엔드 구현은 **시작된 적이 없었음** — `hospital_reservations`는 `V1__init.sql`의 DB 테이블만 존재하고 Entity/Repository/Service/Controller/DTO/테스트가 전무한 미사용 자산. 대시보드 통계에도 참조 없음(컴파일 의존성 0). `V17`의 미사용 테이블 정리와 동일한 후속 작업.
- **코드 제거**: 없음(대상 Java 파일 0개).
- **DB**: 신규 `V24__drop_hospital_reservations.sql` — `DROP TABLE IF EXISTS hospital_reservations;`. leaf 테이블(inbound FK 없음)이라 CASCADE 불필요, 트리거·인덱스 동반 제거. 기존 `V1`~`V23` 미수정.
- **문서**: `프로젝트_설명.txt`(테이블 섹션 제거), `.claude/rules/domain-security-policy.md`(탈퇴 CASCADE 목록의 `hospital_reservations` 언급 제거). CLAUDE.md는 언급 없어 변경 없음.
- **마이그레이션 적용은 수동** — 비가역 DDL, dev 선검증 후 운영 적용. 본 작업은 파일 추가까지만.
- 검증: `./gradlew build -x test --no-daemon` BUILD SUCCESSFUL (코드 변경 없음).
- **산출물**: `docs/(2026-05-30) feature-remove-hospital-reservation.md`.

---

## [2026-05-28] — connection: 연결 거절 시 보호자 실시간 알림 추가 (PR `feature/connection-refused-notification`)

피보호자가 연결 요청을 거절해도 보호자에게 이벤트가 안 가, 보호자 웹이 새로고침 전까지 "요청중"에 멈춰 있던 문제. 수락·해제는 이벤트를 발행하는데 거절만 누락된 **비대칭** 해소.

- **원인**: `ConnectionService.refuseConnectionAsWard()`가 `connection.refuse()`만 호출하고 이벤트 미발행 (수락=`ConnectionAcceptedEvent`, 해제=`ConnectionDisconnectedEvent` 발행과 대조).
- **구현(수락 흐름 100% 미러링)**:
  - `ConnectionRefusedEvent(connectionId, guardianId)` 신규 — `ConnectionAcceptedEvent`와 동일 구조.
  - `refuseConnectionAsWard()` — `refuse()` 후 이벤트 발행 추가.
  - `ConnectionNotificationListener.handleRefused()` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`, WebSocket `connection-refused` + FCM `"연결 요청이 거절되었습니다."`(type `CONNECTION_REFUSED`).
- **FCM 문구 결정**: 시니어/4050 직관성 우선 — "거절되었습니다"로 명확히(모호한 "종료/해제" 회피).
- **인가**: `connection-refused`는 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호 → 코드 변경 없음(확인만).
- **알림 비대칭(의도)**: 피보호자 거절→보호자 알림O / 보호자 취소·탈퇴 PENDING→CANCELLED 무알림 유지(거절만 명시적 거부라 통지 필요).
- **테스트**: `ConnectionServiceTest` 기존 "거절 시 이벤트 없음" 테스트를 **의도 반전**(이벤트 발행 검증)·비PENDING `never publish` 추가, `ConnectionNotificationListenerTest`에 `handleRefused` WS+FCM 발송·문구 검증 추가.
- **프론트 인계**: 구독 `/topic/{guardianId}/connection-refused`, payload `{ connectionId }` → 수신 시 요청 목록에서 제거 + "거절됨" 표시. FCM `"연결 요청이 거절되었습니다."` 수신 처리.
- **DB 영향 없음**: 상태 전이 동일, 이벤트 발행만 추가 → 마이그레이션 불필요.
- **산출물**: `docs/(2026-05-28) feature-connection-refused-notification.md`, CLAUDE.md §9 메모, 프로젝트_설명.txt(3-6·4) 갱신.
- 검증: `./gradlew build --no-daemon` BUILD SUCCESSFUL (전체 테스트 포함).

---

## [2026-05-28] — auth: 카카오 로그인 409 "이미 사용 중인 이메일" 원인 진단 (백엔드 정상, 프론트 버그)

`POST /api/auth/signin/kakao` → 409 `EMAIL_ALREADY_EXISTS` 신고 조사 (진단 전용, 코드 수정 없음).

- **판정**: **케이스 D (프론트엔드 흐름 버그)** — 백엔드 카카오 OAuth 로직은 정상. Client Secret 회귀(C)·백엔드 신규가입 버그(B) 배제.
- **근본 원인**: 프론트 `SignupContent.tsx:30 const isKakao = Boolean(kakaoId && email && name)`. 카카오 신규 가입자는 **name이 항상 null**(백엔드 설계, `KakaoAuthService.java:103 ofNewUser(...,null,...)`)이라 `isKakao`가 늘 false → 카카오 가입자가 **일반 가입 폼으로 빠져 LOCAL 계정(+비밀번호) 생성** → 이후 같은 이메일 카카오 로그인이 `existsByEmail` 충돌로 409. (FE 버그 2026-04-25부터, Client Secret 무관)
- **증거**: DB `provider=LOCAL, has_password=t, KAKAO_LOGIN 로그 0건` → 일반 가입·일반 로그인한 계정임이 "카카오로 가입했다"는 인지와 모순. 사용자 생성 경로는 `register`(LOCAL)·`kakaoRegister`(KAKAO) 2개뿐이라 LOCAL+provider_id=null은 `/api/auth/signup`에서만 생성 가능.
- **심각도**: 높음 — `isKakao`가 모든 신규 카카오 가입자에게 false라 **카카오 회원가입 기능 전체가 사실상 작동 불능**.
- **수정 방향**: 1차(FE 필수) `SignupContent.tsx:30`에서 `name` 조건 제거 → KakaoSignupForm 정상 렌더 → `signupKakao`로 KAKAO 계정 생성. 2차(BE 선택) 콜백 409 안내 개선·login provider 격리 명시화(부작용: 카카오 계정 일반 로그인 5회 시 본인 계정 30분 잠금).
- **상세**: `docs/(2026-05-28) bug-investigation-kakao-409.md`. 코드 수정은 별도 세션(FE 저장소 `../SilverBridgeFe`).

---

## [2026-05-25] — global: 공유 @ValidPassword 추출 (B-USER-1 follow-up, user+auth 교차)

user 도메인 점검 follow-up **B-USER-1** 해소. 비밀번호 정규식이 user `PasswordChangeRequest` + auth `RegisterRequest`/`PasswordResetConfirmRequest` **3곳에 복제**되던 문제(+테스트 fixture까지 4곳).

- **방식**: `global/validation`에 공유 `@ValidPassword`(+`PasswordValidator`) 추출 — 기존 `@ValidBirthDate`/`BirthDateValidator` 패턴과 동일. 3개 DTO의 `@Pattern(정규식)`을 `@ValidPassword`로 치환.
- **범위 한정**: **정규식만** 공유 제약으로 이전. `@NotBlank`(필수)·`@Size(8~64)`(길이)는 각 DTO에 유지 → 길이/형식 메시지 분리 보존(동작 불변). `@ValidPassword` 기본 메시지 = 기존 `@Pattern` 메시지 동일.
- **null 통과**: 필수 여부는 `@NotBlank`가 담당(메시지 분리, BirthDateValidator와 동일).
- **테스트**: `PasswordFieldValidationTest`를 fixture 인라인 정규식 → `@ValidPassword` 검증으로 재구성(마지막 중복처 제거, 문자정책·null·공백·이모지·한글). 정규식 단일 출처화.
- **교차도메인**: auth DTO 2건 수정 포함(사용자 승인하 진행). **브랜치**: `refactor/valid-password-2026-05-25`.

---

## [2026-05-24] — user: 프로필 이미지 업로드 트랜잭션 경계 분리 (D-USER-1 follow-up)

user 도메인 점검 follow-up **D-USER-1** 해소. `updateProfileImage`가 파일 서버 업로드(외부 HTTP, ≤5MB)를 `@Transactional` 내부에서 수행해 업로드 동안 DB 커넥션을 점유하던 문제.

- **방식**: 트랜잭션 경계를 **구조적으로 분리**. `updateProfileImage`에서 `@Transactional` 제거 → 검증·업로드는 트랜잭션 밖, URL 영속화만 신규 협력 빈 `ProfileImagePersister.replace`(@Transactional, 프록시 경유)에 위임. 커밋 후 기존 파일 삭제(D-USER-2 유지).
- **대안 비교**: bulk `@Modifying`(❌ `@LastModifiedDate updated_at` 갱신 누락) / `TransactionTemplate`(코드베이스 미사용 신규 패턴·단위테스트 난해) / 연결 지연획득 reorder(타이밍 의존·취약) → **dirty checking 유지 + 경계가 코드로 드러나는** 협력 빈 채택.
- **주의**: 사용자 미존재 시 업로드 후 영속화 단계에서 `USER_NOT_FOUND`(고아 파일 가능) — JWT principal 기반 + A-USER-1로 탈퇴자 토큰 무효화라 실현 가능성 극히 낮음.
- **테스트**: `updateProfileImage` 4건을 협력 빈 위임 구조로 재작성(검증 실패 시 업로드·영속화 미호출 확인), `ProfileImagePersisterTest` 2건 신규.
- **브랜치**: `fix/profile-image-upload-tx-2026-05-24`.

---

## [2026-05-24] — withdraw: 탈퇴 시 연결·FCM 토큰 정리 (D-USER-3 follow-up)

user 도메인 점검(`audit-report-user.md`)의 교차도메인 follow-up **D-USER-3** 해소. 탈퇴(soft delete INACTIVE)가 connections·fcm_tokens를 정리하지 않아 ① 탈퇴자가 상대에게 정상 연결(+ACTIVE면 연락처 PII)로 노출 ② 탈퇴자에게 푸시 지속 ③ 탈퇴자 6자리 ID로 신규 연결 요청 가능하던 문제.

- **정책 결정**: 탈퇴 시 **정리(teardown)** 방식 채택(읽기 필터 대비 단일 시점·누락 위험 적음, 재활성화 플로우 없어 손실 우려 없음). ACTIVE→DISCONNECTED + **상대에게 해제 알림 발송**(기존 `ConnectionDisconnectedEvent` 재사용) / PENDING→CANCELLED(무알림, 기존 cancel·refuse와 동일).
- **아키텍처**: 기존 `UserWithdrawnEvent`를 각 도메인이 `AFTER_COMMIT`으로 수신해 자기 데이터 정리(도메인 소유권 유지, auth 리스너와 동일 패턴). user 도메인 변경 없음.
  - notification: `UserWithdrawalFcmListener` → `FcmService.deleteAllTokens`(`deleteByUserId`). soft delete라 FK CASCADE 미발동 → 명시적 삭제.
  - connection: `UserWithdrawalConnectionListener` → `ConnectionService.tearDownConnectionsOnWithdrawal`. 신규 `ConnectionRepository.findByParticipantAndStatusIn`(보호자/피보호자 양측).
- **요청 차단**: `validateConnectionRequest`에서 대상 `Status != ACTIVE` → `USER_NOT_FOUND`(존재 비노출).
- **테스트**: `ConnectionServiceTest`에 정리 4건 + 대상 INACTIVE 차단 1건, 리스너 위임 테스트 2건 추가.
- **브랜치**: `fix/withdraw-cleanup-2026-05-24`. (PR #175와 본 파일 상단 동시 삽입 → 머지 시 충돌 해소함)

---

## [2026-05-24] — user: 도메인 풀 점검 + 프로필 이미지 삭제 통합 점검

user 도메인 **스킬 기반 종합 점검 최초**(PHASE A~G). 신규 `DELETE /api/user/me/image`(PR #174) 통합 점검. URL 패턴 3자(코드·`프로젝트_설명.txt`·Swagger) 일치 확인 — 정정 불필요. (산출물: `docs/(2026-05-24) audit-report-user.md`, `audit-summary-user.csv`)

- **🟠 High**: ① **A-USER-1** 탈퇴 후 access token 미무효화(≤30분, 필터 status 미재검증·무효화 키 미설정 — 비번 변경과 비대칭) → `handleWithdrawn`에서 `invalidatePreviousAccessTokens` 호출로 해결. ② **F-USER-1/2** `changePassword` 성공 경로·`updateProfile` 테스트 부재 → 추가.
- **🟡 Medium 해결**: A-USER-2(이미지 Magic Number 검증 추가) / D-USER-2(파일 삭제 `afterCommit` 이전 — 롤백 시 이미지 깨짐 방지) / E-USER-1(비번 변경 INFO 감사 로그) / B-USER-2(`getUserOrThrow` 헬퍼) / F-USER-3·4(이미지·LOCAL탈퇴 테스트).
- **🟡 Medium follow-up(분리)**: D-USER-1(업로드 트랜잭션 밖 이전 — 경계 재설계 회귀 위험) / D-USER-3(탈퇴 시 connections·fcm 생명주기 — 교차도메인) / B-USER-1(공유 `@ValidPassword` — user+auth 통합).
- **✅ 안전 확인**: IDOR 없음(me 전용·principal 파생) / 비번 변경 전 기기 무효화 견고 / N+1 없음(연관관계 미매핑) / SQL 파라미터 바인딩·LIKE ESCAPE / auth→user 단방향 / phone `uq_users_phone` 보장 / 신규 삭제 API 멱등·테스트 양호.
- **브랜치**: `fix/user-audit-2026-05-24` (수정 Medium까지, follow-up·Low 백로그).

---

## [2026-05-24] — user: 프로필 이미지 삭제 API 신규 구현

`DELETE /api/user/me/image` 신규 추가. **시나리오: 작업 요청 배경에는 "문서엔 명시되어 있으나 미구현"이라 되어 있었으나, 실제 `프로젝트_설명.txt` 3-5는 "별도 삭제 엔드포인트 없음(교체만 지원)"으로 정반대 기재** + `/api/users/*`(복수) 경로는 오기로 명시. 따라서 *문서 누락 보완*이 아니라 **신규 기능 추가 결정**으로 진행하고 문서 3-5도 갱신함.

- **구현**: 기존 이미지 교체(`updateProfileImage`)의 자동 삭제 패턴(`fileServerClient.delete(oldUrl)`, fire-and-forget) 재사용. 별도 헬퍼 추출 없이 동일 호출 패턴 유지.
- **정책**: 멱등(이미 없어도 200) / DB가 진실의 원천(파일 서버 삭제 실패해도 `profile_image=NULL` 커밋) / 응답 메시지형 `ApiResponse<Void>` (형제 `withdraw`와 일관) / 로그 INFO·userId만(PII·파일 경로 미로깅).
- **안내 메시지 분기(UX)**: 서비스가 실제 삭제 여부를 `boolean` 반환 → 실제 삭제 "프로필 이미지가 삭제되었습니다.", 이미 없던 경우 "설정된 프로필 이미지가 없어 기본 이미지를 사용 중입니다." (둘 다 200). 시니어 타겟상 *현재 기본 이미지 상태*임을 명시.
- **FileServerClient·User 엔티티 무변경** — `delete(String)`은 이미 존재(null-safe·예외 삼킴·WARN), `updateProfileImage(null)` 그대로 사용.
- **테스트**: `UserServiceTest`에 4건 추가(이미지 있음 삭제 / 이미지 없음 멱등 / 파일 서버 결과 무관 NULL / 사용자 없음 404). `./gradlew test --tests UserServiceTest` 11건 전부 통과, `compileJava/compileTestJava` 성공.
- **산출물**: `docs/(2026-05-24) feature-profile-image-delete.md`, `프로젝트_설명.txt` 3-5 갱신. (브랜치 `feature/profile-image-delete`)

---

## [2026-05-23] — auth: 비밀번호 재설정 정책 변경 스팟 점검 완료

정책 변경(`3a0fbea`/PR #166, always-200 → 404/400/429)의 변경 부분만 스팟 점검(보안·동시성·계약·테스트·문서). 코드 레벨 실결함 0건 — **조건부 PASS**. RedisCounter Lua 원자성·SMS 비용 보호(미가입 SMS 미발송)·PII 마스킹·문서 일관성 양호. 후속: ① 🟠 nginx `X-Forwarded-For` 처리 확인(always-200 폐지로 IP RateLimit이 enumeration 1차 방어가 됨 — 헤더 스푸핑 시 우회 가능), ② 🟡 validation 400(`@Email`/`@Pattern`) 컨트롤러 테스트 보강. (산출물: `docs/(2026-05-23) audit-spot-check-password-reset.md`)

**후속 처리(2026-05-24)**:
- ① SPOT-H1 **취약 확정 후 해결** → nginx 확인 결과 XFF append(`$proxy_add_x_forwarded_for`)+`realip` 미설정으로 스푸핑 우회 가능. `ClientIpResolver`(X-Real-IP 우선) 도입 + `getRemoteAddr()` 보안 사용처 19곳 일괄 교체 (**PR #173**).
- ② SPOT-M1 **해결** → `PasswordResetDtoValidationTest`(Bean Validation 단위 15건). 프로젝트 관행상 `@WebMvcTest` 미도입 (본 PR #172).

---

## [2026-05-23] — auth: 비밀번호 재설정 정책 변경 (가입 여부 명시 응답 + Rate Limit 강화)

시니어/4050 타겟 UX 우선. 비밀번호 재설정 send/resend가 미가입에도 always-200을 반환하던 정책을, 가입 여부를 명시(404/400)하도록 변경. 노출되는 enumeration은 IP 이중 윈도우 RateLimit + per-email 상한 + WARN 로깅으로 방어. (상세: `docs/(2026-05-23) policy-change-password-reset.md`, `docs/(2026-05-23) audit-report-auth-password-reset.md`)

### 변경된 엔드포인트 (Breaking)

- `POST /api/auth/find-password/email/send` · `/email/resend`
  - 미가입 이메일: 200 → **404** "해당 이메일로 가입된 계정이 없습니다"
  - 카카오 가입 계정: 200 → **400** "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다"
- `POST /api/auth/find-password/sms/send` · `/sms/resend`
  - 이름+전화번호 미일치: 200 → **404** "사용자를 찾을 수 없습니다"
  - 카카오만 매칭: 200 → **400** `SOCIAL_USER_NO_PASSWORD`
- 4개 모두 IP RateLimit이 **1분 10회 / 1시간 30회 이중 윈도우**로 강화 (초과 시 429)
- `/password/reset`(3단계), `/email/verify`·`/sms/verify`(2단계)는 **변경 없음**

### 보안 보완 (PHASE 0.5 채택분)

- per-email 발송 상한 `password:email:sendcount:{email}` 1시간 10회 (SMS A-M3 대칭)
- 미가입 404 시 `[PW-RESET]` WARN 로깅(마스킹 식별자+IP) — enumeration 스윕 탐지
- SMS 비용 보호: 미가입자에게는 SMS 발송 전 404 선차단
- 거부: 응답 시간 정규화(정책상 모순), 의심 IP 블랙리스트(공용 NAT 오차단 위험)

### 변경 파일

- `ErrorCode`(EMAIL_ACCOUNT_NOT_FOUND 추가), `PasswordResetService`(404/400+ip+상한+로깅), `RateLimitService`(이중 윈도우 오버로드), `RedisKeys`(PW_EMAIL_SEND_COUNT), `FindPasswordController`(dual-window+Swagger), `PasswordResetRequest`·`PasswordResetSmsSendRequest`(메시지·Schema)
- 테스트: `PasswordResetServiceTest`(404/400·SMS·per-email cap 재작성), `RateLimitServiceTest`(이중 윈도우)

### 프론트 전달 사항 ⚠️

- **Breaking**: send/resend 4개가 더 이상 "항상 200"이 아님. 미가입(404)·카카오(400)·형식오류(400)·상한초과(429)를 **에러 분기로 처리**해야 함.
- 권장 UX: 404 → "가입된 계정이 없습니다" 안내 + 회원가입 유도, 카카오 400 → "카카오 로그인을 이용해주세요" 안내, 429 → "잠시 후 다시 시도" 안내.
- 형식 검증 메시지: 이메일 "올바른 이메일 형식이 아닙니다.", 전화번호 "올바른 전화번호 형식이 아닙니다. (숫자 10~11자리, 하이픈 없이)".

---

## 2026-05-21 — connection 도메인: 피보호자 측 조회 API 분리 (active / pending)

피보호자웹이 "내 보호자 리스트"(ACTIVE) + "요청온 목록"(PENDING) 두 카드로 분리됨에 따라, 단일 `/api/ward/connection/select`(ACTIVE only)를 UI 구조에 맞춰 두 엔드포인트로 분리. (점검은 다음 세션 예정 — 이번엔 구현만)

### PHASE 0 — 현재 상태 분석

- `GET /api/ward/connection/select` → `getMyGuardians` → `findByWardIdAndStatusOrderByPriorityAsc(wardId, ACTIVE)`. **ACTIVE only, priority 오름차순**, 응답 `ConnectionResponse`(피보호자 관점 `fromWardView`).
- 피보호자 측 **PENDING 조회 API 없음** → 신규 필요.
- Repository: ACTIVE용 `findByWardIdAndStatusOrderByPriorityAsc` 존재(재사용), PENDING 최신순 메서드 부재 → 추가.
- `ConnectionResponse`는 이미 `status == ACTIVE`일 때만 phone/address를 채우고 PENDING은 자동 null 마스킹 — 정책 A·B의 기본값이 이미 "PENDING 미노출"이었음.
- DTO 필드명이 보호자/피보호자 공용이라 `partner*`(요구사항의 `guardian*`과 상이).

### PHASE 1 — 결정 사항

| 항목 | 결정 | 근거 |
|------|------|------|
| 기존 `/api/ward/connection/select` | **즉시 제거** → `/active`로 대체 | 옵션 B, 사용자 결정 |
| PENDING 응답 DTO | **신규 `PendingConnectionResponse`** | "요청온 목록" 카드 전용 슬림 계약 |
| PENDING 전화번호 | **백엔드 마스킹** (`010****5678`) | 프론트 마스킹 시 전체 번호가 응답 본문에 실려 노출 → 서버에서 차단. 기존 `MaskingUtil.maskPhone` 재사용(형식 일관성) |
| PENDING 주소 | **미노출** (DTO에 미포함) | 수락 후 ACTIVE 응답에서만 노출 (기존 정책과 일관) |

### PHASE 2 — 변경 사항

- **WardConnectionController**
  - `GET /api/ward/connection/select` **제거**
  - `GET /api/ward/connection/active` 신규 — ACTIVE 보호자, priority 오름차순, `ConnectionResponse` 재사용
  - `GET /api/ward/connection/pending` 신규 — PENDING 요청, createdAt 내림차순, `PendingConnectionResponse`
  - 수락/거절 API는 변경 없음 (`/{id}/accept`, `/request/{id}/refusal` 그대로)
- **ConnectionService**
  - `getMyGuardians` → `getActiveGuardians`로 **개명** (제거된 `/select` 전용이라 죽은 코드/중복 방지)
  - `getPendingRequests(wardId)` 신규 — PENDING 최신순 + 보호자 User 일괄 조회 후 `PendingConnectionResponse` 매핑
- **ConnectionRepository** — `findByWardIdAndStatusOrderByCreatedAtDesc(wardId, status)` 추가
- **PendingConnectionResponse** 신규 DTO — `connectionId, guardianId, guardianName, guardianPhone(마스킹), relation, requestedAt(createdAt)`
- 가정: 현재 ward-개시 요청 흐름이 없어 ward의 PENDING은 전부 보호자-개시 → "요청온 목록"과 일치. 향후 ward-개시 추가 시 `initiatedBy` 필터 필요.

### 프론트 영향 (Breaking)

- `GET /api/ward/connection/select` **제거됨** → `/active`로 교체 필요.
- `/pending` 응답은 `partner*`가 아닌 `guardian*` 네이밍의 신규 스키마.
- ⚠️ `프로젝트_설명.txt:301`의 ward `/select` 기술이 stale — 별도 후속 정리 필요.

---

## 2026-05-20 — connection 도메인 프로토타입 정렬 + WebSocket SockJS 제거

보호자웹 신규 프로토타입(피보호자 등록·요청 내역·내 보호자 카드)에 맞춰 connection 도메인을 확장. 동시에 웹 전용 운영 결정에 따라 WebSocket SockJS를 제거해 네이티브 WebSocket(wss) 단순화.

### connection 도메인 — PHASE 0 점검

엔드포인트는 보호자 4 + 피보호자 4 = 8개 모두 정상 동작. 본인-본인 차단, 중복 ACTIVE/PENDING 차단(애플리케이션 + DB 유니크 인덱스 `uq_connections_active`), 존재하지 않는 ID에 대한 404는 이미 구현됨.

### connection 도메인 — 갭

- **관계(relation) 전면 부재**: DB 컬럼·엔티티 필드·요청 DTO·응답 DTO 어디에도 없음
- **피보호자 측 "내 보호자" 카드에 phone/address/relation 누락**: User 엔티티엔 존재하나 `ConnectionResponse`에 미노출
- **보호자 측 "요청 내역" 테이블에 CANCELLED 이력 표시 불가**: `/select`가 ACTIVE+PENDING만 반환

### connection 도메인 — 결정 사항

| 정책 | 결정 |
|------|------|
| `ConnectionRequestDto.relation` | 필수(`@NotBlank` + `@Size(max=10)`) — 한국어 관계 호칭 최대 4~5음절 |
| 거절·취소된 요청 이력 노출 | 별도 엔드포인트로 CANCELLED 포함 전체 반환 |
| PENDING 상태에서 상대 phone/address | 비공개(null), ACTIVE에서만 노출 — 어뷰징 방지 |

### connection 도메인 — 변경 사항

- **V19__add_connection_relation.sql** 신규 — `connections.relation VARCHAR(10) NULL` 추가 (기존 행 NULL 호환, 신규 요청은 DTO에서 필수화)
- **Connection 엔티티** — `relation` 필드 (`@Column(length = 10)`) + Builder 포함
- **ConnectionRequestDto** — `relation` 필드 추가 (`@NotBlank` + `@Size(max=10)`)
- **ConnectionResponse** — `partnerPhone`, `partnerAddress`, `partnerAddressDetail`, `relation` 추가. `status == ACTIVE`일 때만 phone/address 채움, 그 외 null
- **ConnectionService**
  - `requestConnectionAsGuardian`: `relation`을 엔티티에 저장 + 이벤트 페이로드에 포함
  - `getMyConnectionRequests(guardianId)` 신규 — PENDING+ACTIVE+CANCELLED 전체 최신순 반환 (기존 `findByGuardianIdOrderByCreatedAtDesc` 재사용)
- **GuardianConnectionController**
  - `GET /api/guardian/connection/requests` 신규 — "요청 내역" 테이블용
  - 기존 `/api/guardian/connection/select`는 ACTIVE+PENDING만 유지 ("피보호자 리스트" 사이드바용)
- **ConnectionRequestedEvent** — `relation` 필드 추가 (호환을 위해 null fallback 유지)
- **ConnectionNotificationListener.handleRequested** — FCM 본문을 `"{relation} {guardianName}님이 연결을 요청했어요."`로 변경, relation null이면 기존 fallback 문구 유지

### WebSocket SockJS 제거

운영 환경이 모바일 앱 없이 **웹 전용**으로 확정됨에 따라 SockJS 폴백 계층을 제거. 모던 브라우저 네이티브 WebSocket(wss://) 단독으로 단순화.

- **변경**: `WebSocketConfig.registerStompEndpoints` 의 `.withSockJS()` 호출 제거
- **유지**: STOMP 메시지 브로커(`/topic`, `/app`), JWT 핸드셰이크 인터셉터, 구독 권한 인터셉터, `setAllowedOriginPatterns("*")` 모두 그대로
- **프론트 영향**: 연결 URL을 `https://api.devdmu.gosky.kr/ws` (SockJS) → `wss://api.devdmu.gosky.kr/ws` (native WS)로 변경. `@stomp/stompjs`만 쓰고 `sockjs-client` 의존성 제거 가능
- **별개 인프라 작업**: nginx의 WebSocket Upgrade proxy 설정(`proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_http_version 1.1; proxy_read_timeout 3600s`) 미적용 시 504 Gateway Timeout 지속 — 백엔드 코드 변경과 독립 이슈

### 프론트 영향 (응답 구조 변경)

`ConnectionResponse`에 4개 필드가 추가됨. 기존 필드 삭제·이름 변경 없음 — 기존 클라이언트는 그대로 동작.

| 추가 필드 | 타입 | 노출 조건 |
|-----------|------|-----------|
| `partnerPhone` | String? | ACTIVE에서만 |
| `partnerAddress` | String? | ACTIVE에서만 |
| `partnerAddressDetail` | String? | ACTIVE에서만 |
| `relation` | String? | 항상 (기존 행은 null) |

### 검증

- `./gradlew compileJava --no-daemon -q` 통과
- `./gradlew compileTestJava --no-daemon -q` 통과 — 기존 테스트 영향 없음
- 자동 git commit/push 없음(사용자 승인 후 PR 생성)

### 후속

- 스킬 기반 점검(spring-boot-patterns, api-contract-review, jpa-patterns, security-audit 등)은 다음 세션에서 별도 진행
- 프론트에서 `relation` 누락 시 400 에러 메시지 처리 필요 (`"피보호자와의 관계를 선택해주세요."`)
- nginx WebSocket proxy 설정 확인 (504 Gateway Timeout 원인)
- 프론트는 `sockjs-client` 의존성 제거 및 `brokerURL: 'wss://api.devdmu.gosky.kr/ws'`로 직접 STOMP 연결

---

## 2026-05-19 — 미검증 API 정리 (call/game/ai/anomaly 제거)

무계획적으로 추가됐던 미검증 엔드포인트를 제거하고 검증된 부분만 남기는 정리 작업. 사전 점검 후 사용자 승인 받아 도메인 단위로 끊어 진행, 각 단계 빌드 통과 확인.

### 제거 분류

완전 제거 도메인 (디렉터리 통째)
- domain/call (4) — Ward/GuardianCallController, CallService, WebRtcSignalRequest. SOS(POST /api/ward/sos)·WebRTC 시그널·통화 종료 포함
- domain/game (8) — Ward/GuardianGameController, GameService, GameResult(엔티티/리포), Game DTO 3종. game 결과 저장·조회·랭킹
- domain/ai (7) — AiEventController(AI 적재 엔드포인트 포함), Ward/GuardianCharacterController, AiEventService, CharacterExpressionRecord(엔티티/리포/요청 DTO). 캐릭터 표정 전체
- domain/anomaly (3) — AnomalyEvent(엔티티/리포), AnomalyEventQueryService

admin 부분 제거 (29) — 컨트롤러 7(User/Connection/Dashboard/AccessLog/AnomalyEvent/GameResult/AuditLog)·DTO 17·서비스 4(User/Connection/Dashboard/Admin)·리포 1(AdminUserStats)

enum 제거 (2) — GameType, AnomalyEventType

테스트 제거 (2) — AdminUserServiceTest, AdminDashboardServiceTest

부분 정리 (수정)
- connection: priority 변경 경로만 제거(WardConnectionController.updatePriority, ConnectionService.updatePriority, Connection.updatePriority setter, ConnectionPriorityUpdateRequest). priority 필드·기본값·정렬 조회·ConnectionResponse 매핑은 유지
- announcement: AnnouncementController @Tag name을 "피보호자/보호자 - 공지사항"으로 변경(경로·@Operation·description 유지)

### 공유 의존으로 살린 것

- AdminAuditLogService / AdminAuditLogRepository / AdminAuditLog 엔티티 / AdminAuditAction enum — AdminAnnouncementService·AdminAnnouncementDraftService가 감사로그 기록에 사용 중. 단 조회용 getLogs()와 AdminAuditLogController는 제거(쓰기 경로만 잔존)
- domain/notification 전체(FCM) 무수정 — call/game/ai의 FCM 호출은 파일 삭제로 자연 소멸
- ConnectionNotificationListener 무수정 — SOS/call 로직 없이 연결 요청/수락/해제 알림만(FCM 검증 경로)
- AccessAction enum 유지 — auth 도메인 사용

### 결과

- 삭제 56파일 + 수정 5파일(소스). 3869줄 삭제
- 단계별 `./gradlew build -x test --no-daemon` 전부 통과
- 최종 `./gradlew build`(테스트 포함) BUILD SUCCESSFUL — 잔존 테스트 전부 통과
- 자동 git commit/push 없음. 작업 브랜치 chore/remove-unverified-apis

### 후속 / 주의

- DB DROP TABLE은 별도 제안서 `docs/V17_drop_unused_tables_proposal.md` 로 분리 (마이그레이션 V16까지 존재 → 신규 번호는 V17). 사용자 승인 후 별도 PR
- 2026-05-15 항목의 admin 대시보드/회원관리(AdminUserService·AdminDashboardService·AdminUserStatsRepository·V15 인덱스)는 이번 정리로 대부분 제거됨. V15 인덱스(users role/status/created_at)는 당장 사용처 없으나 V1~V16 수정 금지 원칙상 그대로 둠 — 향후 DROP INDEX는 V17 제안서에서 함께 검토
- 기존 V1~V16 마이그레이션 파일은 미수정 (제거 도메인 테이블은 DB에 잔존)

---

## 2026-05-18 — M-1 카카오 OAuth state 방향 확정

프론트 확인 결과 카카오 로그인 미구현 상태. 모델 A(프론트 검증)로 결정.
- 프론트가 카카오 로그인 신규 구현 시 state 생성·sessionStorage 보관·콜백 자체 대조 포함
- 백엔드에는 기존대로 `code`만 전달 — 백엔드 코드·API 계약 변경 없음
- 프론트에 신규 구현 명세 전달 완료, 프론트 배포 확인 시 audit-report-auth.md M-1을 "해소 완료"로 갱신 예정 *(audit-report-auth.md는 2026-05-23 삭제됨)*
- 상세는 audit-report-auth.md "M-1 협의 사항 > 결정(2026-05-18)" 참조 *(audit-report-auth.md는 2026-05-23 삭제됨)*

---

## 2026-05-16 — auth / user 점검 결함 조치 완료

audit-report-auth.md 발견 28건에 대한 조치를 결함 단위 PR로 분리·머지 완료. 상세 상태는 audit-report-auth.md "적용 현황" 절 참조. *(audit-report-auth.md는 2026-05-23 삭제됨)*

### Phase A (Critical 1 + High 7) — PR #124~#132

- 빨강: 비밀번호 변경·재설정 후 access token 즉시 무효화 (Redis sliding invalidation)
- High 7건: 로그인 enumeration 통합, 잠금 키 user.id 기반, refresh 재사용 감지, INACTIVE refresh 정리, SMS nonce 결합, 카카오 탈퇴 confirmation, dead code 정리
- 프론트 후속 작업 3건(H-1 로그인 응답 / H-5 SMS nonce / H-6 카카오 탈퇴)은 각 PR 본문에 마이그레이션 가이드 명시

### Medium/Low — PR #133~#137 (성격별 5묶음)

- #133 문서/Swagger 정합 (M-2)
- #134 보안 강화 (M-6 토큰 해시, M-7 헤더 검증, M-8 rate limit 확대, M-11 BCrypt 12, L-3 permitAll 분리 + 401 entry point)
- #135 Redis 카운터 원자화 (M-4, L-2 — RedisCounter Lua)
- #136 코드 품질·네이밍 (M-9 VerificationKeyConfig 리네임, M-12 AuthLoginProperties 외부화, M-13, L-1/L-4/L-5/L-7)
- #137 비밀번호 재설정 트랜잭션·로깅 (M-3 IP/UA 기록, M-5 readOnly 트랜잭션 분리)

### 제외/보류 (근거는 audit-report-auth.md 기록 — 2026-05-23 삭제됨)

- 제외: L-6(ID 재시도가 단일 호출보다 안전), M-10(2곳 중복 추출은 발송 추상화로 더 복잡)
- 보류: M-1(프론트 state 검증 선확인 필요 — 협의 사항 문서화), KAKAO/SOLAPI 키 회전(운영 결정)

### 호환성

- Medium/Low 전 항목 응답 포맷 변경 없음
- application.yaml에 auth.login.* 추가 (기본값이 기존 동작과 동일)

---

## 2026-05-15 — auth / user 도메인 점검 (보안 정밀)

스킬 기반 7 PHASE 점검(security-audit / concurrency-review / architecture-review / spring-boot-patterns / clean-code / api-contract-review / jpa-patterns / performance-smell-detection / logging-patterns / test-quality) 완료. 보안 핵심 도메인 정밀 모드.

### 점검 범위 (총 49개 파일)
- domain/auth (43) + domain/user (10) + global/jwt (3) + global/security (4) + global/util (3, MaskingUtil/RedisKeys/VerificationCodeValidator+UserIdGenerator) + global/config (2, SecurityConfigValidator/RequiredPropertiesValidator) + global/exception (3, ErrorCode/CustomException/GlobalExceptionHandler) + global/aop/ApiLoggingAspect + db/migration(users/refresh_tokens/access_logs 관련 11개) + application.yaml + .env.dev + build.gradle + .gitignore

### 발견 요약
- 🔴 Critical 1건 — 비밀번호 변경/재설정 후 access token 30분 유효 결함
- 🟠 High 7건 — 계정 enumeration, 이메일 기반 로그인 잠금 DoS, refresh rotation 재사용 탐지 부재, INACTIVE 시 refresh 삭제 누락, SMS_VERIFIED 키 재사용, 카카오 탈퇴 본인 확인 약함, AccessLogRepository 타입 버그(dead code)
- 🟡 Medium 13건 / 🟢 Low 7건

### 산출물
- `docs/audit-report-auth.md` — 전체 분석·수정 제안·프론트 마이그레이션·커밋 메시지 초안 일괄 정리 *(2026-05-23 삭제됨)*
- 본 progress.md 업데이트

### 다음 단계 (미적용 — 사용자 승인 후 PR 분리 진행)
- Critical 1건 우선 적용. PR 단위 분리 권장. 자동 git 작업 금지(CLAUDE.md §2).
- Phase F의 테스트 갭(KakaoAuthService, PasswordResetService, refresh rotation 동시성)은 수정 PR과 함께 진행.

---

## 2026-05-15 — 관리자 대시보드 / 회원관리 API 사후 점검

스킬 기반 6 PHASE 점검(architecture / spring-boot / clean-code / jpa / security / test / logging) 결과 요약. 점검 대상은 PR #121(대시보드)·#122(회원관리 검색) 및 기존 회원관리 엔드포인트.

### PHASE 1. 구조 점검

적용
- 어드민 전용 사용자 집계 쿼리(countUserStats / countByRole)를 `admin/repository/AdminUserStatsRepository` 로 이관. UserRepository 슬림화.
- AdminDashboardService.getSummary() 메서드 레벨 @Transactional 제거. 캐시 hit 경로에서 빈 트랜잭션 미개시.
- AdminDashboardController / AdminUserController 클래스 레벨 @PreAuthorize("hasRole('ADMIN')") 추가. SecurityConfig URL 차단과 이중 방어.

### PHASE 2. 코드 품질

적용
- AdminDashboardService 캐시 쓰기 실패 시 500 응답 대신 log.warn 후 fresh 반환 (CustomException/ErrorCode 의존 제거).
- 캐시 역직렬화 실패에 log.warn 추가.
- AdminDashboardService.getRecentUsers 의 PageRequest 에서 불필요한 Sort 인자 제거 (메서드명 OrderByCreatedAtDesc 가 정렬을 결정).
- 정적 팩토리 메서드 네이밍을 from 으로 통일 (AdminRecentUserResponse.of → from).

보류
- DTO 의 enum vs String 타입 일관성 — 호환성 영향 검토 후 별도 PR.
- `/user/status-change`, `/user/delete` 등 URL 컨벤션 정비 — 프론트 호환성 영향, 별도 마이그레이션.

### PHASE 3. 데이터 계층

적용
- Flyway V15: users 테이블에 (role, status, created_at DESC) 복합 인덱스 추가. 회원관리 검색/목록/최근 가입자 쿼리 전부 커버.
- 회원관리 검색에서 이메일 LIKE 조건과 LOWER 함수 호출 제거 — name/phone 부분일치만 유지. 검색 정책: 관리자는 이름·전화번호로 회원 식별.
- 결과적으로 auth 도메인의 이메일 정규화 / DB 데이터 마이그레이션 작업은 회피.

보류
- `/user/select` 의 페이지네이션은 PHASE 4 S2 로 이월하여 적용 (아래 참조).

### PHASE 4. 보안·동시성

적용
- LIKE 메타문자(%, _, \\) 이스케이프. 서비스 레이어에서 keyword 정규화 후 JPQL 의 `ESCAPE '\\'` 와 짝.
- `/api/admin/user/select` 페이지네이션 적용. 응답이 List 에서 페이지 객체(content/page/size/totalElements/totalPages)로 변경 — 프론트 호환성 영향 있음, 회원관리 화면 작업 중이라 동시 적용.

보류
- ApiLoggingAspect 가 파라미터를 안 찍어 keyword 마스킹 무용. 향후 파라미터 로깅 추가 시 마스킹 필요 (PHASE 6 L3 와 동일 트래킹).
- 캐시 stampede 방어 — 트래픽 증가 시 재검토.

### PHASE 5. 테스트

적용
- AdminDashboardServiceTest 7건 (캐시 hit/miss/손상/쓰기실패, baseline 0 → 증감률 null, 최근 가입자, 처리 대기 합산).
- AdminUserServiceTest 13건 (목록 페이징, 검색 빈/이스케이프/정상, 카운트 매핑, 상태/역할 변경 ADMIN 차단·정상·연결 cancel, 강제 탈퇴, 조회 not found).
- 총 20건 모두 통과 (JUnit5 + Mockito + AssertJ + 한글 @DisplayName, 기존 AuthServiceTest 컨벤션 준수).

### PHASE 6. 로깅·문서

적용
- L1 AdminAuditLogService.log() 가 DB 저장과 함께 SLF4J log.info 동시 출력 — 관리자 status/role/forceDelete 가 로그 파일에도 자동 기록.
- L2 ApiLoggingAspect 가 SecurityContext 의 userId 를 MDC 에 put/remove. application.yaml 의 로그 패턴에 `%X{userId:-anonymous}` 포함되어 모든 로그에 자동 표시.
- L4 Spring Boot 4 structured logging 활성화 토글 — `LOGGING_STRUCTURED_FORMAT` 환경변수에 `ecs`/`gelf`/`logstash` 입력 시 JSON 출력. 기본은 평문. MDC 도 자동 JSON 필드로 포함.

보류 / TODO
- L3 raw keyword 마스킹 — 현재 ApiLoggingAspect 가 파라미터 미로깅이라 노출 없음. 향후 파라미터 로깅 도입 시 keyword / name / phone 마스킹 필수.
- 운영 환경 structured logging 활성화는 로그 수집기·파서 호환성 확인 후 적용.

### 변경 파일 (총 14건)

신규
- `src/main/java/.../domain/admin/repository/AdminUserStatsRepository.java`
- `src/main/java/.../domain/admin/dto/AdminUserListPageResponse.java`
- `src/main/resources/db/migration/V15__add_users_role_status_created_at_index.sql`
- `src/test/java/.../domain/admin/service/AdminDashboardServiceTest.java`
- `src/test/java/.../domain/admin/service/AdminUserServiceTest.java`
- `docs/progress.md` (본 문서)

수정
- `src/main/java/.../domain/admin/controller/AdminDashboardController.java`
- `src/main/java/.../domain/admin/controller/AdminUserController.java`
- `src/main/java/.../domain/admin/service/AdminDashboardService.java`
- `src/main/java/.../domain/admin/service/AdminUserService.java`
- `src/main/java/.../domain/admin/service/AdminAuditLogService.java`
- `src/main/java/.../domain/admin/dto/AdminRecentUserResponse.java`
- `src/main/java/.../domain/user/repository/UserRepository.java`
- `src/main/java/.../global/aop/ApiLoggingAspect.java`
- `src/main/resources/application.yaml`

---

## 2026-05-20 — auth/user 도메인 2차 보안·구조 종합 점검

2026-05-20 프로토타입 정합(`cb6c211` — 성별/생년월일/우편번호 필드 추가, 비번 재설정 6자리 통일) 이후 스킬 기반 점검 미수행 상태였음. 보안 핵심 도메인이라 강도 높여 PHASE 0~G 진행.

### 점검 범위
- 1차(52): `domain/auth/**`, `domain/user/**`
- 2차(10): `global/jwt/**`, `global/security/**`, `global/util/VerificationCodeValidator·MaskingUtil·RedisKeys·RedisCounter`, `global/config/SecurityConfigValidator·RequiredPropertiesValidator`
- 3차: Flyway V16/V18, `ErrorCode` 인증 관련

### 적용 스킬
architecture-review / spring-boot-patterns / jpa-patterns / security-audit (+ concurrency-review / api-contract-review / performance-smell-detection / logging-patterns / test-quality 발견 항목 매핑)

### 핵심 발견 — Critical 5건 (트랜잭션 롤백으로 refresh token 폐기 무효화)

`AuthService` 4개소 + `KakaoAuthService` 1개소에서 `refreshTokenRepository.delete*` 직후 `throw CustomException` 시 본 트랜잭션 롤백으로 폐기가 실제 DB에 적용되지 않던 결함. 특히 **H-3(도난 감지) / H-4(INACTIVE 차단 시 token 즉시 삭제) 보안 fix가 사실상 무효**였음. access_logs는 `AccessLogService.REQUIRES_NEW`로 살아남아 도난 흔적은 남지만 사용자 token은 회수되지 않는 상태.

**근거**: `CustomException extends RuntimeException` + `@Transactional` default rollback rule + `rollbackFor`/`noRollbackFor` 미지정.

### 1차 커밋 — `2e91381 fix(auth): refresh token 폐기를 별도 트랜잭션으로 분리`
- 신규 `RefreshTokenRevocationService` (`@Transactional(REQUIRES_NEW)`) — `AccessLogService` 패턴과 동일
- `AuthService` 4개소 + `KakaoAuthService` 1개소가 `revokeAll/revokeOne` 경유
- `AuthServiceTest` verify 3건 정정
- 정상 흐름(로그인 단일 디바이스 정책, refresh rotation)은 throw가 없어 원본 호출 유지
- 응답 포맷·API 시그니처 변경 없음 → 프론트 호환성 영향 0

### 2차 누적 커밋 — High 2 + Medium 5 일괄 반영
- **H-A1** `AuthService.login` fail counter `RedisCounter.incrementWithTtl` 통일 (M-4 라운드 누락분, fixed window)
- **H-A8** `KakaoOAuthClient` connect 3s / read 5s 타임아웃 명시 (Tomcat thread 행 방지)
- **M-M1** `ObjectMapper` 빈 주입 — `KakaoOAuthClient` 생성자, `JwtAuthenticationFilter` `@RequiredArgsConstructor`, `SecurityConfig` 에서 필터 생성 시 전달
- **M-M2** 입력 길이 정책 정비 — 10개 DTO에 email max=50 / password max=64 / verificationNonce max=36 / kakaoId max=20 일관 적용 (BCrypt 72byte cutoff, RFC 5321, UUID 표준 반영)
- **M-M3** `KakaoOAuthClient` 응답 body 로깅 마스킹 — `extractTokenErrorCode/extractApiErrorCode` 헬퍼로 errorCode만 출력
- 전체 `./gradlew test` BUILD SUCCESSFUL
- 응답 포맷 변경 없음 / DB 스키마 변경 없음 / 프론트 호환성 영향 0

### 이월 (다음 스프린트)
- **M-F1~F5**: 테스트 갭 5건 (`KakaoAuthServiceTest`, `PasswordResetServiceTest`, `BirthDateValidatorTest`, `RefreshTokenRevocationServiceTest`, `JwtAuthenticationFilterTest`)
- **L-A5**: `SecurityConfig` 보안 헤더 (HSTS/CSP/X-Frame-Options 등)
- **L-G1**: `application.yaml` DB credential placeholder
- **L-C1**: `UserController` RESTful 경로 정리 — 프론트 마이그레이션 동반 협의
- **M-B1**: `UserService → SmsService` 역방향 의존 — 별도 협의

### 산출물
- `docs/audit-report-auth-2026-05-20.md` — 본 점검 종합 보고서 (신규) *(2026-05-23 삭제됨)*
- `docs/audit-report-auth.md` — 2026-05-15 1차 점검 보고서 (그대로 보존) *(2026-05-23 삭제됨)*

---

## 2026-05-21 — connection 도메인 종합 점검 (스킬 기반)

connection 도메인은 구현 완료·기능 테스트 통과 상태였으나 스킬 기반 정적 점검은 미수행. 보호자-피보호자 양측 동시 액션 + FCM/WebSocket 이벤트가 트랜잭션과 얽혀 동시성·이벤트 일관성 위험이 높아 PHASE -1~G 진행.

### 점검 범위
- 1차: `domain/connection/**` (controller 2, dto 3, entity 1, event 3, listener 1, repository 1, service 1)
- 2차: `domain/notification/FcmService`(호출 지점), `global/websocket/**`, `global/config/WebSocketConfig`
- 3차: Flyway V1/V2/V8/V11/V19/V20/V21, `ErrorCode`/`GlobalExceptionHandler`, `RateLimitService`/`SecurityConfig`, `RedisKeys`, `ApiLoggingAspect`

### 적용 스킬
architecture-review / spring-boot-patterns / jpa-patterns / concurrency-review / security-audit / api-contract-review / performance-smell-detection / logging-patterns / test-quality / clean-code / solid-principles

### 사전 정리 — `#152 refactor/remove-connection-priority`
- priority(통화 우선순위) 변경 경로가 2026-05-19 제거되어 `1`로 고정된 죽은 필드/컬럼이 됨 → DB 컬럼·체크제약·정렬 인덱스(V20 DROP COLUMN, 자동 cascade), `ConnectionResponse` 응답 필드, 빌더 고정값 제거
- ward ACTIVE 조회 정렬을 priority → `createdAt` 오름차순으로 대체, 대체 인덱스 `(ward_id, status, created_at)` 추가
- 프론트 호환성: 응답에서 `priority` 필드 제거(계약 변경 — 프론트 조율 필요)

### 핵심 발견 — Critical 1건 (A2 상태 전이 lost update)
연결 상태 전이(수락/거절/취소/해제)가 `findById → status 검사 → dirty-checking UPDATE`(WHERE는 id만) 구조 + 락 부재로, 동시 요청(예: accept∥cancel) 시 두 트랜잭션이 가드를 모두 통과해 **lost update** 발생. 게다가 `ConnectionAcceptedEvent`는 발행됐는데 행은 CANCELLED로 끝날 수 있어 **상태와 알림이 불일치**.

### Critical 수정 — `#153 fix/connection-optimistic-lock`
- `Connection`에 `@Version` 추가(낙관적 락), V21 `version BIGINT NOT NULL DEFAULT 0`(가역적)
- `GlobalExceptionHandler`: `ObjectOptimisticLockingFailureException` → 409 매핑
- `acceptConnectionAsWard`: not-PENDING 시 ErrorCode 오인(`CONNECTION_NOT_ACTIVE` → `CONNECTION_NOT_PENDING`) 교정
- A3(동시 해제 중복 이벤트)도 동일 근원으로 해소
- 프론트 호환성: `version`은 내부 컬럼(응답 무변경), 동시 전이 시 409 신규 케이스만 추가

### 양호 확인
- 보안(인가·IDOR·WebSocket 구독 인가·PII 정책) 견고 — Critical/High 없음
- 트랜잭션 경계 일관, 이벤트 AFTER_COMMIT 분리, 연관관계 없음(String FK)으로 N+1/lazy 원천 없음, 목록 `findAllById` 배치

### 이월 항목 후속 처리 (전부 본 사이클 반영)
- **G-1 (High)**: ✅ `#154` — ConnectionService·리스너 테스트 26건
- **C-DEAD1 / A5 (Medium)**: ✅ `#155` — dead code 5개 제거 (역할변경 연동은 트리거 부재로 N/A)
- **F-1/F-2 (Medium/Low)**: ✅ `#155` — 상태 전이 INFO 로그(connectionId 포함)
- **E-3 (Low)**: ✅ `#155` — `(guardian_id, status, created_at)` 인덱스 교체(V22)
- **B3/E-4 (Medium)**: ✅ `#156` — 알림 `@Async` 비동기화
- **D-1 (Medium) / D-2 (Low)**: ✅ `#157` — 재처리 400→409 + disconnection 경로 일관화 (프론트 통보 필요)
- **D-6 (Medium)**: ✅ `#158` — ConnectionStatus 세분화 REFUSED/DISCONNECTED + 유니크 인덱스 의미 조정(V23) (프론트 통보 필요)

### 의도적 보류 (다음 사이클)
- **C-ARCH1 (Medium)**: `ConnectionService → UserRepository` 직접 의존 — 모놀리식 실용 패턴이라 보류
- **C-SOLID1 (Low)**: ConnectionService 분리 — 규모상 불필요, 보류
- **Low**: B-C3a(열거 oracle, 실질 무해), B-C4a(WS 토큰 쿼리, 인프라 공통 과제)

### 프론트 통보 필요 (#157·#158 — 프론트 개발 중 시점에 선반영)
- 해제 URL: `/api/{role}/disconnection/{id}` → `/api/{role}/connection/disconnection/{id}` (하위호환 불가)
- 응답 `status`에 `REFUSED`/`DISCONNECTED` 신규 값 등장
- 이미 처리된 요청 재처리 응답 400 → 409

### 산출물
- `docs/(2026-05-21) audit-report-connection.md` — 종합 보고서 (신규, 수정완료 반영)

---

## 2026-05-22 — auth/user 도메인 3차 보안·구조 종합 점검 (스킬 기반)

1·2차(2026-05-15/20) 종료 후 보안 핵심 도메인 재점검. PHASE -1~G 진행, 발견 즉시 수정(High/Medium/Low 전부 + 테스트 갭 전부 작성).

### PHASE -1 흔적 처리
- 점검 작업 자체는 정상(1·2차 완료·머지·문서화). 유일한 흔적: `.claude/settings.json`(커밋 안 됨)에서 git push/commit deny 안전장치가 allow로 이동된 상태.
- 사용자 결정: **그대로 두고 진행**. 본 라운드 변경에 settings.json 미포함, 자동 git 미수행.

### 핵심 발견 — Critical 0 / High 2
- **A-H1 (High)**: JWT 토큰 타입 미구분 — refresh token을 Bearer로 제시 시 authenticated 엔드포인트 통과·로그아웃 우회. → `typ` 클레임 + 필터 access-only.
- **A-H2 (High)**: `/signin` IP RateLimit 부재 — 계정 분산 credential stuffing 무제한. → `RateLimitService.check(signin)`.

### 수정 완료 (본 라운드)
- High 2 (A-H1, A-H2)
- Medium 2: A-M1(비번재설정 confirm enumeration — 코드검증 선행), C-1(`isNewUser` @JsonProperty 고정)
- Low 6: C-2(카카오 가입 201), A-L1(constant-time 비교), A-L2(보안 헤더, CSP 제외 부분), A-L4(데드코드 AuthenticationManager/CustomUserDetailsService 제거), A-L5(AI·game 잔재+미사용 ErrorCode 5개 제거), A-L6(생년월일 만120세 상한), D-3(SecretKey 캐싱), B-2(매직 문자열 상수화), E-2(traceId MDC), E-3(잠금 WARN 로그)

### 이월 (다음 사이클)
- A-M2(XFF/프록시 trusted-proxy — 인프라 토폴로지 확인), A-L3/G-2(DB credential 기본값 dev — .env/CD 확인), A-L2 CSP(Swagger 호환 정책), B-1(auth↔user 결합 — 모놀리식 보류), L-C1(UserController RESTful 경로 — 프론트 마이그레이션, 2차 이월), E-4(약관 동의 기록 — 약관 백엔드 미구현 시 N/A)

### 검토 후 미구현 확인 (결정)
- 로그인 유지(프론트 책임), 약관 동의(백엔드 미구현) — 발견사항으로만 기록.

### 양호 재검증
- 1·2차 보안 fix 전부 현행 코드에 살아있음(access token 무효화·refresh 폐기 REQUIRES_NEW·rotation+재사용감지·로그인 응답 통합·SMS nonce·BCrypt12·JWT secret 검증·IDOR 없음·동시가입 409).

### 테스트 (PHASE F — 갭 전부 작성)
- 신규 5: JwtAuthenticationFilterTest(A-H1 회귀), KakaoAuthServiceTest, PasswordResetServiceTest(A-M1 회귀), RefreshTokenRevocationServiceTest, BirthDateValidatorTest
- 보강 2: JwtTokenProviderTest(typ), SmsVerificationServiceTest(per-phone 캡)
- `./gradlew test` 대상 74건 통과 + `./gradlew build -x test` BUILD SUCCESSFUL

### 프론트 호환성
- 응답 필드 삭제·이름변경 없음. A-H1 배포 시 기존 access token 1회 401→refresh 자동복구. C-1/C-2는 카카오 프론트 미구현이라 영향 없음.

### 산출물
- `docs/audit-report-auth-2026-05-22.md` — 3차 종합 보고서 (신규) *(2026-05-23 삭제됨)*
- `fix/auth-audit-2026-05-22` → PR #159로 dev 머지 완료 (커밋 9개)

### 후속 처리 (follow-up, PR 별도) — 이월 항목 4건 추가 해소
2026-05-22 점검의 이월 항목 중 4건을 후속 브랜치에서 처리:
- **A-M2** ✅ `docker-compose.dev.yml` api publish `6511:6511` → `127.0.0.1:6511:6511` (외부 직접 접근 차단, nginx 도메인 경유 유지)
- **A-L3/G-2** ✅ DB 자격증명 약한 기본값(`dev`) 제거 + `RequiredPropertiesValidator`에 `DB_USERNAME`/`DB_PASSWORD` 편입(fail-fast) — `.env.dev` 명시 확인
- **A-L2 CSP** ✅ Swagger 호환 CSP 추가
- **B-1** ✅ `PhoneVerificationPort`(user) 추출 — user→auth 직접 의존 제거, auth→user 단방향 정렬
- 잔여 이월: L-C1(RESTful 경로·프론트 조율), E-4(약관·미구현) → **아래 추가 PR에서 해소(2026-05-22)**
- 검증: 대상 74건 통과 + `./gradlew build -x test` BUILD SUCCESSFUL

### 잔여 이월 해소 (follow-up 2 / PR `refactor/user-restful-paths`, 2026-05-22)
- **L-C1** ✅ `UserController` RESTful 경로 **하드 전환** — `GET /me/select`→`GET /me`, `PUT /me/update`→`PUT /me`, `PATCH /me/update/image-change`→`PATCH /me/image`, `PUT /me/update/password-change`→`PUT /me/password`, `DELETE /me/delete`→`DELETE /me`
  - 동기화: `SwaggerConfig`(표시 순서) · `SmsController`/`SmsVerifyResponse`(nonce 안내) · `프로젝트_설명.txt`(API 목록·SMS 절차)
  - `SecurityConfig`는 `/api/user/**` authenticated → 수정 불필요, 컨트롤러 MockMvc 테스트 없음(회귀 영향 없음)
  - ⚠️ 데드 경로 없음(하드 전환) → 배포 시 옛 경로 즉시 404. **프론트 새 경로 동시 교체 후 머지·배포 필수**
- **E-4** ⛔ 약관 동의 시점 기록 — **결정상 구현 안 함(Won't-do)**. 프로토타입 프론트에서도 약관 동의 UI 제거. 향후 약관 기능 도입 시 재검토
- 검증: `./gradlew build -x test --no-daemon` BUILD SUCCESSFUL

---

## [2026-05-25] 카카오 OAuth Client Secret 적용 (PR `feature/kakao-client-secret`)

REST API Key 단독 대비 보안 강화 — 인가코드 탈취 시 토큰 발급 차단을 위해 토큰 교환 요청에 `client_secret` 추가.

- **코드**:
  - `KakaoOAuthClient` — `@Value("${kakao.client-secret}")` 필드 + `getToken` 파라미터에 `client_secret` 추가 (시그니처 불변 → 호출처·기존 테스트 무영향)
  - `application.yaml` — `kakao.client-secret: ${KAKAO_CLIENT_SECRET:}`
  - `RequiredPropertiesValidator` — `KAKAO_CLIENT_SECRET` 존재 검증 추가 (10 → 11개)
  - `SecurityConfigValidator` — `KAKAO_CLIENT_SECRET` 길이(≥32)·placeholder/약한 값 거부 (JWT secret과 동일 패턴, 책임 분리 유지)
- **테스트**: `SecurityConfigValidatorTest`(신규, 순수 단위) — 정상/blank/짧음/약한값/대소문자 5케이스
- **시크릿 관리**: `KAKAO_CLIENT_SECRET`는 `.env.dev`로만 주입(코드/Git 평문 비노출), 로그·예외 미노출 확인
- **사용자 작업(PHASE 2)**: 카카오 콘솔 [보안 > Client Secret] 코드 발급 + "사용 함" 활성화 → `.env.dev` 추가 → `api` 컨테이너 재시작
- **산출물**: `docs/(2026-05-25) feature-kakao-client-secret.md`, CLAUDE.md §9 메모, 프로젝트_설명.txt(3-3·7·11) 갱신
- 검증: `./gradlew build --no-daemon` BUILD SUCCESSFUL (테스트 포함)

### 스팟 점검 (2026-05-25)
- 시크릿 노출(A1~A5)·작동(B1~B3)·문서(C1~C2) 미니 점검 → **전부 PASS, 이슈 0건**
- 시크릿 평문 노출 없음(코드/yaml/로그/Git 히스토리), `.env.dev` 미추적, 토큰 교환·검증기 정합, 기존 카카오 흐름 회귀 없음
- 빌드 `./gradlew build -x test --no-daemon` EXIT 0
- 산출물: `docs/(2026-05-25) audit-spot-check-kakao-client-secret.md`

---

## [2026-05-29] 카카오 가입 중복/인증소비/세션만료 버그 진단 (수정 미적용)

재현: LOCAL 가입 → 탈퇴 → 같은 카카오 계정 가입 → 폰 인증 완료 → "가입완료"에서 "입력값이 중복됐습니다"(409) → 재인증 후 "전화번호 인증을 먼저 완료해주세요"(SMS_NOT_VERIFIED).

- **원인 C (확정·root, 카카오 한정)**: `KakaoAuthService.kakaoRegister`가 `consumeVerification`(nonce 삭제, L114)을 **중복검사·세션확인보다 먼저** 호출. 이후 단계 실패 시 `@Transactional`은 DB만 롤백, **Redis nonce 삭제는 비가역** → 가입 실패가 인증을 태워 재시도 불가. LOCAL `register`는 검증 후 마지막에 소비(L83)라 무영향 → **순서 비대칭 회귀**.
- **원인 E (확정)**: 사용자가 본 "카카오 세션 만료"는 access token(30분)이 아니라 **`kakao:pending` 10분**(`KakaoAuthService.java:48`). 단계3에서 타이머 시작, 시니어 4단계 가입이 10분 초과 → `KAKAO_SESSION_EXPIRED`. 게다가 세션확인이 nonce소비보다 뒤라 E도 C를 유발.
- **원인 B (기각)**: PHASE2 DB 실측 — email/phone/kakao 행 **전부 0건**. hard delete 완전 동작 → 잔존 INACTIVE 없음. **단계5 "입력값이 중복됐습니다"는 DB 진짜 중복 아님.** `existsBy*` status 필터 부재는 원인 아닌 latent 갭으로만 잔존.
- **단계5 최종 판정 = E (소거법)**: nonce 소비(L114) 이후 실패 분기 3개 중 ①consume실패(메시지 불일치)·②중복(DB 0행) 제거 → **③ KAKAO_SESSION_EXPIRED만 남음.** 프론트가 이 400을 "중복"으로 오표기(FE 정정 필요). Redis도 pending/verified 전부 만료(TTL -2)로 사후 스냅샷 일치.
- **연쇄**: 단계5 세션만료(E, pending 10분) → nonce 선소비(C, L114가 세션확인 L119보다 앞) → 단계6 SMS_NOT_VERIFIED 재시도 차단.
- **수정 방향(미적용)**: C-1 소비 순서 교정(최우선, 카카오 메서드 한정·무영향) → E-1 pending TTL 상향(예 30분) + FE 에러 메시지 정정. B는 수정 불요(잠재 갭만 기록).
- **다음**: 근본원인 확정(C+E) → 사용자 수정 방향 승인 시 `fix/kakao-register-...` 브랜치 착수.
- 빌드: `./gradlew build -x test --no-daemon` EXIT 0.
- 산출물: `docs/(2026-05-29) bug-investigation-kakao-signup-duplicate.md`

---

## [2026-05-30] 카카오 가입 access_logs FK 위반 버그 수정 (PR `fix/kakao-signup-access-log-fk`)

운영 로그(SQLState 23503)로 진짜 409 "중복" 응답의 실제 발생원을 확정·수정. 2026-05-29 진단이 "프론트 세션만료 오표기"로 봤던 부분의 **실제 원인**.

- **1차 원인(확정)**: `KakaoAuthService.kakaoRegister`(`@Transactional`)가 `accessLogService.log`(REQUIRES_NEW)를 가입 트랜잭션 안에서 호출 → 별도 커넥션이 **미커밋 user 행**을 못 봐 `fk_access_logs_user` 위반(23503). **순서 아닌 트랜잭션 격리 문제** — 재정렬·flush로 안 풀림.
- **2차 원인(확정)**: `GlobalExceptionHandler`가 `DataIntegrityViolationException`을 종류 불문 409 "중복"으로 변환 → FK 위반이 "중복"으로 오표시.
- **LOCAL 대조**: `AuthService.register`는 가입 시 접속로그 미기록(login 때만, 그땐 user 커밋됨) → 카카오만 발생.
- **수정 C(주)**: `KakaoRegisteredEvent` + `KakaoRegisterEventListener`(`@TransactionalEventListener(AFTER_COMMIT)`+REQUIRES_NEW)로 `KAKAO_LOGIN` 로그를 커밋 후 기록. 공유 `AccessLogService` 미변경 → login/logout/refresh 무영향.
- **수정 D(병행)**: 예외 핸들러 SQLState 구분 — 23505(unique)만 409 "중복", 23503 FK 등은 ERROR 로깅 + 500. unique PII는 SQLState만 로깅.
- **nonce**: 1회용 의도 유지·미변경(FK 롤백 제거로 stranding 자연 해소).
- 회귀 가드 테스트: 가입 성공 시 `accessLogService.log` 직접 호출 안 함 / 리스너 위임 / FK≠unique 매핑. 신규 테스트 2종.
- 빌드: `./gradlew build --no-daemon`(테스트 포함) BUILD SUCCESSFUL (EXIT 0).
- DB 영향 없음(스키마·상태전이 불변, 로그 기록 시점만 이동). 마이그레이션 불요.
- 산출물: `docs/(2026-05-30) bugfix-kakao-signup-access-log-fk.md`

---

## [2026-05-31] 비밀번호 초기화 인증 재시도 버그 진단 (진단 전용, 미수정)

`POST /api/auth/password/reset`에서 1차 실패 후 같은 코드로 재시도가 막히는 버그를 코드 근거로 원인 확정.

- **근본 원인 확정(가설 A)**: `PasswordResetService.confirmReset`이 **소비형 `verify()`(성공 시 Redis 코드 삭제)를 비즈니스 검증보다 먼저** 호출(L192). 이후 `SAME_AS_CURRENT_PASSWORD`(L212) 등 다운스트림 검증이 실패하면, `@Transactional`이 DB만 롤백하고 **Redis 삭제는 롤백 안 됨** → 코드 비가역 소모 → 2차 재시도 시 `EXPIRED_SMS_CODE`.
- **재현 트리거**: 식별자·6자리 코드는 정상이고 **새 비밀번호 = 현재 비밀번호**로 1차 실패 → 같은 코드로 2차 → "만료" 오류. (1회 실패로 막히는 유일 경로)
- **가설 검증**: A 확정 / B(5회 오입력 잠금)는 의도된 동작·5회 누적 필요라 본 시나리오와 불일치 / C·D 기각. 표면 에러가 모두 `EXPIRED_SMS_CODE`라 만료로 오인되는 게 혼동의 핵심.
- **카카오 가입 버그와 같은 뿌리**: "인증 소비가 검증·처리보다 먼저 → 실패 시 재시도 불가". 가입(LOCAL/KAKAO)은 이미 **"검증 후 마지막 소비"**로 수정 완료(`KakaoAuthService` L141-146 주석에 동일 원리 명시). 비번재설정만 미적용. 단 가입=nonce 경로/비번재설정=코드 경로라 **한 줄 공유 수정은 불가**, 같은 원칙 적용이 통합점.
- **수정 방향(미적용)**: 맨 앞 `verify()`→`verifyWithoutConsume()`로 교체(enumeration 차단 A-M1 유지), 모든 검증·변경 성공 후 **마지막에 코드 소비**. 변경은 `confirmReset` 1곳, DB 마이그레이션 불요. 회귀 테스트 + `domain-security-policy.md`에 "소비는 마지막에" 불변 규칙 명문화 권장.
- 빌드: `./gradlew build -x test --no-daemon` EXIT 0.
- 산출물: `docs/(2026-05-31) bug-investigation-password-reset-verification.md`

### [2026-05-31] (수정 적용) 비밀번호 초기화 인증 재시도 버그 — 가설 A 수정 완료

브랜치 `fix/password-reset-code-consume-order` (dev 분기). 진단(위 항목)의 가설 A 방향대로 적용.

- **수정**: `PasswordResetService.confirmReset` 맨 앞 `verify()`(검증+소비) → `verifyWithoutConsume()`(비소비)로 교체(enumeration A-M1 유지), 모든 검증·변경·로그 성공 후 **마지막에** `verificationCodeValidator.consume()` 호출. `VerificationCodeValidator`에 `consume(verifyKey, attemptKey)` 헬퍼 신설.
- **효과**: `SAME_AS_CURRENT_PASSWORD` 등 다운스트림 1차 실패 시 코드가 보존돼 같은 코드로 즉시 재시도 가능. 최종 성공 시에만 1회용 소비.
- **테스트**: `PasswordResetServiceTest` — 정상 시 consume 호출+verify 미사용, SAME_AS_CURRENT/SOCIAL 실패 시 consume 미호출(★회귀). `VerificationCodeValidatorTest` — verifyWithoutConsume 키 보존·consume 삭제 단위 테스트 추가.
- **정책 문서**: `domain-security-policy.md`에 "인증코드/nonce 소비는 검증 후 마지막에" 불변 규칙 추가.
- **영향 범위**: `confirmReset` 1곳 + 공유 validator에 헬퍼 1개. 가입(LOCAL/KAKAO)·`/verify`·DB 무영향, 마이그레이션 불요.
- 빌드: `./gradlew build --no-daemon`(테스트 포함) BUILD SUCCESSFUL (EXIT 0).

---

## [2026-05-31] 알림 채널 추상화 인프라 구축 (1단계)

브랜치 `feature/notification-channel-infra` (dev 분기). 알림을 "이벤트 → 채널 라우터 → 사용자 설정 활성 채널 발송"으로 일반화. 전체 3단계 중 1단계(인프라 + FCM/SMS 추상화)만 구현, 카카오 알림톡·이메일은 enum만 정의.

- **채널 추상화**: `NotificationChannelType`(FCM/SMS/KAKAO_ALIMTALK/EMAIL) + `NotificationChannel` 전략 인터페이스. 구현체 = `FcmNotificationChannel`(FcmService 위임), `SmsNotificationChannel`(SmsSender 위임). `NotificationDispatcher`가 `List<NotificationChannel>`로 구현체 자동 수집 → **새 채널 = 빈 추가만**. KAKAO/EMAIL은 구현체 없어 디스패처가 skip.
- **현재 구조 정정**: 기존 알림은 FCM+WebSocket이었고 **SMS는 알림 미사용(인증 전용)**. SMS 알림은 신규 추가(회귀 아님). WebSocket은 추상화 밖·항상 발송(협의).
- **사용자 설정**: 신규 테이블 `user_notification_settings`(V25, user×channel 1행, FK ON DELETE CASCADE). 기본값 = **FCM ON·그 외 OFF**, 행 없으면 기본값 → **백필 불요**. 조회/변경 API `GET·PUT /api/user/me/notification-settings`.
- **필수/선택**: SMS 인증번호는 디스패처 미경유 동기 발송이라 **설정 무시(필수)가 구조적 보장**. 연결 알림 4종은 선택(설정 따름). 긴급 알림용 mandatory 강제발송 메커니즘은 마련했으나 현재 등록 타입 없음(휴면).
- **라우팅 안전성**: 채널별 발송 try/catch **격리**(한 채널 실패가 다른 채널 안 막음), 미구현 채널 skip, 활성 채널 0이면 조기 종료.
- **변경**: `ConnectionNotificationListener` 4곳을 `fcmService.sendToUser` → `dispatcher.dispatch`로 전환(WebSocket 유지). `FcmService`·`SmsSender`·인증 흐름 **미변경**(회귀 방지). 기본값 FCM ON이라 연결 푸시 동작 동일.
- **테스트**: 채널 위임/디스패처 라우팅·격리/설정 서비스 upsert·기본값 신규 테스트 + `ConnectionNotificationListenerTest` 갱신. `./gradlew test`·`build -x test` 모두 EXIT 0(기존 회귀 없음).
- **프론트엔드 영향 없음**(additive). 카카오 알림톡(PFID/템플릿ID/SDK 예제)·이메일은 2·3단계 인계 — 산출물 문서 §8.
- 산출물: `docs/(2026-05-31) feature-notification-channel-infra.md`

---

### [2026-05-31] 연결 해제 알림이 "거절" 문구로 보이는 문제 — 조사 결과 백엔드 무결

연결 해제 시 "피보호자가 거절하였습니다"가 표시된다는 제보 점검. 사용자 가설(직전 거절 알림 추가가 해제 문구 오염)은 **기각**.

- **백엔드 무결 확정**: "피보호자가 거절하였습니다" 문자열은 `src/main` 어디에도 없음. 해제 본문은 정상("...연결을 해제했습니다"), 이벤트 매핑(`ConnectionDisconnectedEvent`→`handleDisconnected`) 정상, 액터 분기(GUARDIAN/WARD) 정상.
- **거절 커밋 영향 없음**: `2a69188`은 `handleRefused`를 추가만 함. 해제 본문은 `fa5f86f`→`2a69188`→`ee997f9` 세 시점 모두 동일.
- **진짜 원인**: FCM은 `notification`+`data` 둘 다 발송 → 포그라운드 앱이 `data.type`으로 자체 문구 렌더. 해제의 와이어 식별자가 `CONNECTION_CANCELLED`/`connection-cancelled`(내부 enum `CONNECTION_DISCONNECTED`·status `DISCONNECTED`와 불일치)라, 거절 분리 전 FE가 이를 "해제 겸 거절"로 취급한 잔재로 추정. → FE 렌더링 + 네이밍 이슈.
- **처리(옵션 B)**: 와이어 호환 유지(breaking 회피) — 백엔드 코드 무변경. `ConnectionNotificationListenerTest`에 `data.type` 단언(`CONNECTION_CANCELLED`/`CONNECTION_REFUSED`) 보강해 4종 비혼동을 고정. FE에 "cancelled=해제 전용, refused=거절 전용, 서버 body 그대로 표시 권장" 계약 인계.
- 검증: 리스너 테스트 EXIT 0, `build -x test` EXIT 0.
- 산출물: `docs/(2026-05-31) bugfix-connection-disconnect-message.md`.

---

### [2026-06-02] 연결 조회 partner 프로필 전체 필드 추가

연결 조회 응답의 상대방(partner) 정보에 누락돼 있던 **성별·생년월일·이메일·우편번호**를 보강. 케어 서비스 특성상 연결된 양쪽(보호자/피보호자)이 상대 프로필 전체를 상호 열람.

- **추가 필드**(`ConnectionResponse`): `partnerPostcode`·`partnerGender`(FEMALE/MALE)·`partnerBirthDate`(ISO)·`partnerEmail`.
- **노출 규칙**: 기존 `partnerPhone/Address`와 동일한 **ACTIVE 게이팅** — PENDING/취소/거절은 모두 `null`("연결 성립 전 비노출" 정책 확장). 성별/생년월일/우편번호 미입력 계정은 ACTIVE라도 `null`(null-safe 매핑).
- **민감/시스템 필드 미노출**: password·provider_id·provider·role·계정status·lastLoginAt — DTO에 필드 자체 부재(리플렉션 테스트로 고정).
- **영향 엔드포인트**: 공유 DTO 1곳 수정 → `/guardian/connection/select`·`/guardian/connection/requests`·`/ward/connection/active` 3개 자동 반영. 수락 전 카드(`PendingConnectionResponse`, `/ward/connection/pending`)는 최소정보+전화 마스킹 정책 유지로 **무변경**.
- **DB 영향 없음**(읽기 응답 매핑만 추가). 프론트 호환(additive — 기존 필드 무변경).
- **테스트**: `ConnectionResponseTest` 신규(ACTIVE 노출/비-ACTIVE null/null 프로필 안전/민감필드 미존재) EXIT 0, `connection.*` 회귀 EXIT 0, `build -x test` EXIT 0.
- 산출물: `docs/(2026-06-02) feature-connection-partner-full-profile.md`.

---

### [2026-06-06] 카카오 가입 / 비밀번호 재설정 버그 수정 검증 — PASS

직전 머지된 3건 수정(① 카카오 가입 `access_logs` FK 위반+예외 오매핑 #185, ② 카카오 가입 실패 시 nonce 소비 #184, ③ 비번 재설정 인증코드 소비 순서 #188)의 정확성·회귀 스팟 점검. **코드 변경 없음(점검만).**

- **수정 정확성 PASS**: ① 로그를 `KakaoRegisteredEvent`(AFTER_COMMIT, REQUIRES_NEW)로 미뤄 user 커밋 후 기록 → FK 안전, 가입 내부 `accessLogService.log()` 직접호출 없음. 예외는 `extractSqlState()`로 23505(unique)→409"중복"/그 외(FK 23503 등)→500+원인 ERROR 로깅으로 분리. ② nonce 소비(`consumeVerification`)가 세션·역할·중복 검증 뒤. ③ `confirmReset`이 맨앞 `verifyWithoutConsume`, 모든 검증·변경 성공 후 마지막 `consume` — Redis 비롤백 대비 "검증 후 마지막 소비".
- **회귀 PASS**: 일반 가입(`AuthService.register`) 무변경, 가입 시 접속로그 미기록이라 FK 소지 없음(카카오만 KAKAO_LOGIN — 의도된 비대칭). 공유 컴포넌트(`VerificationCodeValidator`·`GlobalExceptionHandler`·`SmsService`) 시그니처·기존 동작 유지. 기존 카카오 로그인 인라인 로그(커밋된 user라 FK 안전) 정상.
- **테스트 PASS**: 수정별 단위 테스트 존재 — 가입 실패 3종(세션만료/이메일중복/ADMIN) `save·consume never`, FK(23503)→500·"중복"불포함, AFTER_COMMIT 로그, `SAME_AS_CURRENT_PASSWORD`/카카오분기 `consume never`(★회귀), `verifyWithoutConsume`는 키 유지. `build -x test` EXIT 0, 관련 6 클래스 테스트 EXIT 0.
- **이슈**: Critical/High/Medium 없음. Low 2건(소비가 `save()` 직전이라 좁은 race 시 nonce 소모 / AFTER_COMMIT 리스너 비동기 아님) — 모두 의도된 설계, 수정 불요.
- **종합 판정**: ✅ PASS — 머지 상태 양호, 추가 조치 불요.
- 산출물: `docs/(2026-06-06) audit-spot-check-kakao-password-bugfix.md`.

---

### [2026-06-06] connection 누적 변경 스팟 점검 (지난 풀 점검 이후) — PASS

지난 풀 점검(`docs/(2026-05-21) audit-report-connection.md`) 이후 쌓인 connection 변경 5건을 스팟 점검. 빌드 EXIT 0, **신규 결함 0건**.

- **점검 대상**: ① active/pending 조회 분리 + `relation` 컬럼(`V19`, 배경의 "V15"는 착오) ② 거절 실시간 알림(`ConnectionRefusedEvent`+`handleRefused`, #183) ③ 해제 "거절" 오표시 조사(#190, 백엔드 무변경) ④ partner 프로필 전체 필드(#191, 성별/생년월일/이메일/우편번호) ⑤ 알림 채널 추상화(FCM→dispatcher, #189).
- **PHASE A (알림 4종 정합)**: 요청/수락/거절/해제 이벤트→리스너→문구→`data.type`까지 1:1 비혼동 확인. 해제 WS명 `connection-cancelled`(내부 enum과 불일치)는 PR #190의 의도된 와이어 호환 보존 — 결함 아님. 거절 커밋이 해제 본문 미변경 재확인.
- **PHASE B (partner 노출)**: 신규 4필드 전부 `revealContact=ACTIVE` 게이팅·양방향(Guardian/Ward) 대칭. `password`·`provider_id`·토큰류 **응답 매핑에 부재**(🔴 Critical 없음). `genderName(null)` null-safe. 수락 전 카드(`PendingConnectionResponse`)는 전체필드 미추가 유지.
- **PHASE C (동시성)**: 리스너 4종 `AFTER_COMMIT`+`@Async` 일관, `@Version`(V21) 낙관적 락, 알림 실패 @Async 격리. 거절(PENDING)·해제(ACTIVE) 전제 상태 배타.
- **PHASE D (계약)**: partner 필드는 비파괴적 append, `@Schema` 문서화, `relation` DTO `@Size(max=10)`↔DB `VARCHAR(10)` 정합.
- **이슈**: Critical/High/Medium/Low 모두 없음. 정보성 노트 2건(해제 와이어 네이밍=의도, partnerEmail=로그인 식별자지만 ACTIVE·가족 한정 의도된 노출).
- **종합 판정**: ✅ PASS — 수정 사항 없음(커밋 대상 없음).
- 산출물: `docs/(2026-06-06) audit-spot-check-connection-changes.md`.

## [2026-06-11] 전체 API 점검 세션 1 완료 (auth + user, 회귀 위주)

- **범위**: 3세션 분할 점검의 1차 — auth 18 + user 6 = 24 엔드포인트. 기준선(05-23 auth / 05-24 user) 이후 변경 커밋 14건 중심 회귀 점검.
- **인가 매트릭스**: IDOR 없음(user 전부 `@AuthenticationPrincipal`만), `/api/auth/**` permitAll 아래 보호 누락 없음(logout만 명시 인증), deny-by-default 유지. **Critical/High 없음.**
- **회귀**: 기준선 수정 14건(A-USER-1·2, D-USER-1·2·3, B-USER-1·2, E-USER-1, F-USER-1~5, 인증코드 소비순서, 카카오 FK/secret) **전부 해소 유지 — 회귀 0건.** 탈퇴 리스너 3종이 동기 AFTER_COMMIT임을 확인해 purge 순서 전제 유효성 검증.
- **신규 이슈**: 🟡 M-S1-1(탈퇴 2단계 사이 실패 시 좀비 계정 — 재로그인·재시도·재가입 불가, 복구 경로 없음) 1건 + 🟢 Low 5건(signup 2종 RateLimit 부재, Swagger 429 미문서화, find-email 죽은 dual 분기+@Pattern 누락, 이미지 고아파일 경로, SecureRandom 비일관) + 테스트 갭 3건(SmsServiceTest 부재 우선).
- 산출물: `docs/(2026-06-11) audit-full-api-session1.md`, `docs/(2026-06-11) audit-summary-session1.csv`.
- 다음: 세션 2(connection+notification+SOS 정밀 — 탈퇴 리스너 @Async 전환 금지 유의), 세션 3(announcement+admin+global).

## [2026-06-11] 전체 API 점검 세션 2 완료 (connection + notification + SOS)

- **범위**: connection 10 + notification 4 + SOS 1 = 15 엔드포인트 + 디스패치 인프라. connection=회귀 위주, **notification=정밀(첫 풀 점검)**, SOS=스팟(06-09) 반영 확인.
- **인가**: `@EnableMethodSecurity`+ROLE_ 프리픽스로 클래스 `@PreAuthorize` 동작 실증. connection 소유 헬퍼 2종으로 IDOR 없음. **Critical 없음.**
- **🟠 H-S2-1 (핵심 발견)**: 만료 FCM 토큰 정리(`cleanupInvalidTokens`→`deleteByToken`)가 무트랜잭션 경로(리스너 @Async→디스패처→채널)에서 호출돼 derived delete가 구조적으로 실패 → 만료 토큰 영구 잔존 → `hasToken()` 항상 true → **앱 삭제 보호자에게 SOS 푸시·SMS 폴백 모두 영구 유실**. 정적 분석 결론(런타임 재현 미수행), FcmService 테스트 부재로 회귀망 미포착.
- **🟡 Medium 2**: M-S2-1(presence 기반 폴백 — 무효 토큰 첫 SOS 유실), M-S2-2(토큰 소유자 재할당 없음 — 공유 디바이스 알림 오수신). 🟢 Low 4(토큰 삭제 소유 검증, ResponseEntity 스타일 분기, SOS Swagger 블록 부재, 채널 실패 로그 스택 누락) + 테스트 갭 3.
- **회귀 0건**: 연결 알림 4종 디스패처 전환 후 정합 유지, 알림 비대칭 정책·SMS 인증 미경유 규칙·SOS 수정 4건(201·이름 폴백·쿨다운 fail-open)·sos_events/settings FK(탈퇴 차단 없음) 전부 확인.
- 산출물: `docs/(2026-06-11) audit-full-api-session2.md` (CSV는 세션 1 제거 결정에 따라 미생성).
- 다음: 세션 3(announcement+admin+global 정밀) — WS 토픽 인가 실증·H-S2-1 수정 후 재확인 인계.

## [2026-06-11] 전체 API 점검 세션 3 완료 + 3세션 최종 통합 리포트 (점검 전체 종료)

- **범위**: announcement 2 + admin 11 = 13 엔드포인트(점검 이력 전무 → 정밀) + global 12패키지(jwt/security/websocket/exception/aop/config) 풀 점검.
- **🔴 C-S3-1 (Critical, 미수정)**: `chk_admin_audit_action` CHECK(V1)가 enum의 `ANNOUNCEMENT_DRAFT_*` 4종을 허용하지 않아, 감사 로그(REQUIRED 합류)가 23514로 터지며 **공지 임시저장 4개 엔드포인트가 항상 500(전체 롤백) — 기능 동작 불능**. announcement/admin 테스트 0건이라 미발견. 수정=V27 마이그레이션(CHECK 재정의).
- **🟡 M-S3-1**: WS 핸드셰이크가 HTTP 필터 대비 약함 — typ 미검증(refresh로 연결 가능, A-H1 우회)·로그아웃 블랙리스트·PASSWORD_INVALIDATE 미확인. 🟢 Low 6건(draft DTO null vs NOT NULL 500 경로, WS Origin `*` 비대칭, iat 초 절사 1초 오차단 창, 관리자 검색 dead query 3종, 공지 무페이징, commonness 네이밍).
- **global 실증(안전 확인)**: STOMP 구독 `{userId}` 일치 강제로 sos/connection 토픽 자동 보호, 핸들러 23505/낙관락 분기(세션 1 "동시 탈퇴 500" 추정은 **409로 정정**), RateLimit Lua 원자성, notificationExecutor CallerRunsPolicy, AOP 로깅 PII 안전, 시크릿 fail-fast.
- **최종 통합**: 52개 엔드포인트 전수 — IDOR/PII 노출/SQLi 없음, 회귀 0건. 점검 중 수정 완료 2건(M-S1-1 PR #202, H-S2-1 PR #203). 미해결 Critical 1·Medium 3 + Low 17. 발표 전 우선순위는 최종 리포트 §4.
- 산출물: `docs/(2026-06-11) audit-full-api-session3.md`, `docs/(2026-06-11) audit-full-api-final-report.md`.

## [2026-06-11] API 점검 잔여 이슈 일괄 반영 완료 (PR #205) — 점검 발견 이슈 전체 처리 종료

- **M-S2-1** (설계 결정: FCM 실패 시에만 SMS): `NotificationChannel.send`가 실제 전달 성공 여부를 반환, 필수 알림은 토큰 없음·전 토큰 만료·발송 예외를 모두 "전달 실패"로 수렴해 SMS 폴백. 동시 발송 아님(성공 시 SMS 비용 절약).
- **M-S2-2**: FCM 토큰 등록 시 타 사용자 소유면 소유자 갱신(공유 디바이스 알림 오수신 차단). **M-S3-1**: WS 핸드셰이크에 typ·로그아웃 블랙리스트·무효화(iat) 검증 추가(HTTP 필터와 동일 수준) + Origin을 `app.cors.allowed-origins`로 통일.
- **Low 7건**: signup 2곳 RateLimit, Swagger 429·SOS 문서, FindEmailRequest @Pattern, 이미지 고아 파일 방지, SecureRandom 재사용, draft null 정규화, dead query 3종 제거.
- 신규 테스트: JwtHandshakeInterceptorTest 6 + FcmServiceTest 4 + 디스패처 폴백 3케이스. 전체 239 tests 통과.
- **점검 사이클 총결산**: 발견 Critical 1·High 1·Medium 4 **전부 수정 머지**(PR #202~#205). 잔여는 의도적 수용(Low: iat 1초 창, 공지 무페이징, URL 동사형/래퍼 스타일 컨벤션)뿐 — 최종 리포트 §4 참조.

## [2026-06-20] 피보호자 FCM 미수신 버그 진단 (수정 미적용)

- **증상**: 같은 프론트 로직인데 보호자는 FCM 정상 수신, 피보호자만 미수신. 프론트 로그 "이미 등록된 토큰 사용".
- **근본 원인(2층위 수렴, 둘 다 "같은 브라우저 멀티계정"에서만 발현)**:
  - **3-A 구조적**: Firebase `getToken()`은 브라우저당 동일 토큰 1개 + 백엔드 `uq_fcm_tokens_token` UNIQUE → 토큰은 **마지막 등록자 1명만** 소유. 다른 쪽은 `findByUserId` 0건 → 미수신.
  - **3-B 프론트 가중**: `fcm.ts` `registerFcmTokenForCurrentDevice` 조기 반환 가드가 **토큰 값만 보고 userId를 안 봄**. 로그아웃 미경유 계정 전환 시 sessionStorage 잔존 → 피보호자 등록 POST 자체를 건너뜀 → 백엔드 `reassignTo` 미발동 → 토큰 보호자에 하드 고정.
- **검증 결과**: 백엔드 발송 경로 정상(역할 필터 없음·기본 FCM ON·wardId 정확). 가설 2/3/4 배제. 가설 1이 원인이나 "이전 누락"이 아니라 "단일 토큰 독점 + 프론트 가드 생략".
- **결론**: 같은 브라우저 멀티계정 **테스트 아티팩트**. 운영(기기 분리) 환경에선 양쪽 정상일 가능성 높음.
- **수정 방향(미적용, 결정 대기)**: A 무수정(테스트 분리) / **B 프론트 보강(로그인 시 항상 재등록, userId 포함 가드) — 권장, 백엔드 회귀 없음** / C 백엔드 `(user_id,token)` 복합키(회귀 큼, 비권장).
- **잔여 확인(보류)**: 테스트 계정 ID 확보 후 `fcm_tokens`/`user_notification_settings` DB 조회로 최종 확정.
- 산출물: `docs/(2026-06-20) bug-investigation-ward-fcm-not-received.md`.

## [2026-07-01] 문의하기(고객센터) 기능 구현 (보호자 작성 + 관리자 답변)

- **신규 도메인 `inquiry`**: 보호자가 문의를 작성하고 관리자가 답변하는 고객센터 기능. 완전 신규(기존 코드 없음).
- **테이블**(Flyway V28 `inquiries`): user_id(작성자, FK→users **CASCADE** — 탈퇴 시 함께 삭제) / category / title / content / status / answer / answered_by(FK→users **SET NULL**) / answered_at. 인덱스 3종(본인 목록·상태 탭·카테고리).
- **enum**(global/enums, 순수 enum): `InquiryCategory`(ANOMALY/HOSPITAL/ACCOUNT/SERVICE/ETC) · `InquiryStatus`(WAITING/ANSWERED). 한글 라벨은 프론트 매핑.
- **엔드포인트**: 보호자 `POST/GET /api/guardian/inquiry`·`GET .../{id}`(본인만, IDOR 차단) / 관리자 `GET /api/admin/inquiry`(탭 카운트+카테고리·상태 필터+제목·내용·작성자명 검색+**페이징**)·`GET .../{id}`·`POST .../{id}/answer`(WAITING→ANSWERED).
- **답변 알림**(선택): `InquiryAnsweredEvent`(AFTER_COMMIT `@Async`) → `NotificationDispatcher.dispatch(작성자, INQUIRY_ANSWERED, ...)`. `NotificationType.INQUIRY_ANSWERED(false)` = 사용자 설정 따름(기본 FCM ON). WebSocket 미발송(실시간 화면 이벤트 아님).
- **재사용**: ApiResponse·NotificationDispatcher·BaseTimeEntity·String userId(FK 미매핑)·IDOR 검증 패턴. **신규 공통** `PageResponse<T>`(코드베이스 첫 페이징 래퍼 — 관리자 목록에 도입, 이후 재사용).
- **범위 외(열어둠)**: 관리자 대시보드 "미처리 문의" 통계는 대시보드 API 자체가 미구현이라 제외 — `countByStatus(WAITING)` 제공으로 향후 연결 가능. 답변 감사 로그는 CHECK(V27) 동기화 필요해 제외.
- 테스트 3종(보호자 서비스·관리자 서비스·알림 리스너) 통과, 전체 build 회귀 0건.
- 산출물: `docs/(2026-07-01) feature-inquiry.md`.

## [2026-07-02] CD 배포 서버 divergent 복구 + 재발 방지 패치 (PR #210)

- **증상**: PR #209(문의 기능) 머지 후 CD 배포가 14초 만에 실패(exit 128). 직전 PR #208(6/15)도 동일 실패 — 마지막 정상 배포는 6/11 PR #205.
- **원인**: 배포 서버(self-hosted `[self-hosted, dev]`, `~/SilverBridgeBe`) 로컬 `dev`가 `origin/dev`와 **divergent**(로컬 578 vs 원격 588 커밋). 과거 `dev` 히스토리가 rewrite/force-push되며 해시가 바뀌었는데(로컬 `e305384` Merge #205 vs 원격 `cbbb366` 같은 내용·다른 해시), 배포 서버는 옛 해시 체인에 멈춘 채 `git pull`(merge)을 반복해 분기 누적 → `git pull origin dev`가 "divergent branches"로 중단. 로컬-온리 578커밋은 전부 원격 중복(고유 작업 없음), working tree clean.
- **복구**: 서버 SSH 접속 → `git fetch origin` + `git reset --hard origin/dev`로 `37e4b5e`(PR #209 포함)에 정확히 정렬. 실패한 CD 워크플로우 재실행 → **배포 성공(8m35s)**. 문의 API 정상 반영.
- **재발 방지(PR #210)**: `cd.yml` 배포 스텝을 `git pull origin dev` → `git fetch origin dev` + `git reset --hard origin/dev`로 전환. 배포 서버는 origin/dev의 순수 미러(진실의 원천 아님)이므로 merge 대신 강제 정렬 → dev가 또 rewrite돼도 divergent 원천 차단. 머지 후 새 스텝으로 CD 재실행 성공(로그에서 fetch+reset→api Recreated 확인).
- **후속 권장**: 배포 서버 SSH 자격증명이 세션에 노출됨 → 비밀번호 교체 권장.

## [2026-07-03] 문의하기(고객센터) 기능 통합 점검 (PASS)

- **대상**: PR #209(`feat(inquiry)`) + #211(Swagger 태그) / `V28__add_inquiries.sql` — 문의 도메인 전용 정적 점검(코드·DB·git 미변경). build -x test ✅, 문의 테스트 3종 ✅.
- **IDOR ★ PASS**: `getOwnedInquiry()` 소유권 검증 + `INQUIRY_NOT_AUTHORIZED`=404 위장, 목록은 본인 스코프(`findByUserId...`). 전용 테스트 존재.
- **인가 PASS**: 보호자 API 클래스 `@PreAuthorize("hasRole('GUARDIAN')")`(`@EnableMethodSecurity` 확인), 관리자 API `/api/admin/**`→`hasRole("ADMIN")`. answeredBy=`@AuthenticationPrincipal`(위조 불가).
- **기능 PASS**: 상태전환 WAITING→ANSWERED + 재답변 409, 탭카운트=전역(필터 무관), 검색=제목·내용·작성자명 동적필터, 답변알림=`INQUIRY_ANSWERED(mandatory=false)` 선택·AFTER_COMMIT `@Async`.
- **성능 PASS**: 작성자명 `findAllById` 배치(N+1 없음), 인덱스 3종 조회패턴 정합.
- **이슈**: 🔴/🟠 없음. 🟡 M-1 저장형 XSS 잠재(보호자 입력→관리자 화면, **FE 렌더링 의존** → SilverBridgeFe 점검 이관 권장). 🟢 생성 응답 200(201 아님)·반환타입 외형 불일치·작성 rate limit 없음(저권한). ℹ️ 탈퇴자 문의 CASCADE 삭제=의도(고객지원 이력 소실 유의).
- **판정: PASS** — 조치 없이 배포 가능, M-1만 FE 후속 트래킹. 산출물 `docs/(2026-07-03) audit-spot-check-inquiry.md`.

## [2026-07-13] AI 이상감지 WebSocket 수신 + 이력 (1단계, danger 기반)

- **범위**: 수신·판정·이력까지. **보호자 알림 발송은 2단계**(별도) — 설계는 `docs/(2026-07-13) design-anomaly-notification.md`.
- **수신**: AI 서버는 웹훅이 없고 자체 WS로 broadcast만 하므로, 백엔드가 **클라이언트로 구독**(`AiLiveStreamSubscriber`, AI 서버 무변경). `x-api-key` 헤더 인증, `ApplicationReadyEvent` 이후 접속 + 지수 백오프 재접속 — **연결 실패·키 미설정이 기동을 막지 않는다**(구독만 비활성).
- **구독 범위**: 연결 직후 `{"action":"list"}` → **우리 `cameras`에 등록된 세션만** subscribe(미등록 세션은 구독조차 안 함 → 로그 폭주 차단). 세션 생성/종료 broadcast(`live_streams`)로 신규 카메라 자동 구독.
- **판정**: `anomaly.trigger-mode` — **`DANGER`(기본)** = `danger==true`만 인정(위험 판정 책임=AI, AI 담당 협의). **`CONFIDENCE`(폴백)** = 신뢰도 임계(기본 0.6) — AI의 danger 정식화 배포 지연으로 이력 0건일 때 임시 전환용. `normal`·`unknown` 및 미탑재 종류(fall·weapon)는 무시.
- **조용한 침묵 방지**: 라이브 경로의 AI `danger`는 현재 항상 false 하드코딩 → DANGER 모드에서 이력 0건이 정상이다. "고신뢰 감지인데 danger=false"가 지속되면 `[ANOMALY-DANGER-MISMATCH]` WARN(세션당 1분 스로틀)로 미배포를 드러낸다.
- **중복 방지**: AI는 매 프레임(초당 여러 번) broadcast → Redis 쿨다운 `anomaly:cooldown:{sessionId}:{detectedType}` TTL 5분(SET NX EX). Redis 장애 시 fail-open(중복 이력 < 이력 유실).
- **이력**: `V30__add_anomaly_events.sql` — `ward_id`(users FK CASCADE)·`session_id`·`detected_type`·`confidence`·`danger`·`detected_at`(**NULL 허용** — AI fallback 페이로드엔 `analyzedAt`이 없다. NULL = 분석 시각 불명, 수신 시각은 `created_at`)·`created_at`, 인덱스 `(ward_id, created_at DESC)`. `session_id`→`ward_id` 매핑은 camera 도메인 재사용(`CameraService.findWardIdBySessionId`), 미등록 세션은 스킵+WARN.
- 테스트 3종(판정·이력 흐름·페이로드 파싱) 통과, 전체 build 회귀 0건. 산출물: `docs/(2026-07-13) feature-anomaly-detection-receiver.md`.

## [2026-07-14] 테이블 명명 단수형 통일 (V31)

- **결정**: 테이블명을 복수형 → **단수형**으로 통일. `connections→connection`, `cameras→camera`, `anomaly_events→anomaly_event`, `sos_events→sos_event`, `inquiries→inquiry`, `announcements→announcement`, `announcement_drafts→announcement_draft`, `admin_audit_logs→admin_audit_log`, `access_logs→access_log`, `fcm_tokens→fcm_token`, `refresh_tokens→refresh_token`, `user_notification_settings→user_notification_setting` (12개).
- ⚠️ **`users`만 복수형 유지(의도적 예외)**: `user`는 PostgreSQL 예약어 — 테이블명으로 쓰면 모든 참조를 `"user"`로 인용해야 하고, 인용을 빠뜨린 `SELECT * FROM user`는 테이블이 아닌 세션 사용자를 뜻해 **조용히 오동작**한다. 그 함정을 감수할 이득이 없어 예외로 남김.
- **변경 범위**: `V31__rename_tables_to_singular.sql`(ALTER TABLE RENAME 12건) + 엔티티 12개의 `@Table(name=...)`. **네이티브 SQL 0건**이라 그 외 코드 변경 없음(JPQL은 엔티티명 사용). FK·인덱스는 rename을 따라가므로 재생성 불필요 — 다만 제약·인덱스 **이름에는 복수형이 남는다**(예: `fk_cameras_ward`). 이름은 식별 라벨일 뿐 동작 무관이라 변경 폭을 줄이기 위해 그대로 둠.
- ⚠️ **마이그레이션과 엔티티는 반드시 같이 배포**: `ddl-auto: validate`라 한쪽만 반영되면 앱이 기동하지 않는다.
- **사전 검증**: 실제 dev DB에서 `BEGIN; ALTER TABLE … RENAME …; ROLLBACK;` 리허설로 12건 전부 성공 확인(데이터 무변경). 로컬 `./gradlew build` 회귀 0건.

## [2026-07-14] 배포 환경은 2개 — CD(vkcs-linux) / 수동(skyserver). 혼동 주의

- **구성(의도된 것)**:
  - **vkcs-linux** — **CD 자동 배포** 대상. 러너 `silverbridge-dev`(labels: self-hosted,dev)가 여기 있고 `cd.yml`이 `~/SilverBridgeBe`로 배포한다. 공개 도메인 없음(SSH 터널로 확인).
  - **skyserver(gosky)** — `api.devdmu.gosky.kr`이 물린 **실사용 환경. CD 미적용 = 수동 배포**. 저장소 `/home/apps/SilverBridgeSky/SilverBridgeBe`, 컨테이너 `dmu-dev-api/db/redis`.
- ⚠️ **혼동 포인트**: `dev` 머지 → CD success 는 **vkcs-linux 반영**일 뿐이다. **skyserver는 수동 배포하지 않으면 그대로다** — 실제로 PR #209(문의)·#213(카메라)·#214(이상감지) 머지 후에도 skyserver는 4주째 옛 컨테이너(Flyway V27)였다. "머지했는데 왜 안 보이지?"의 원인은 대개 이것.
- **skyserver 수동 배포 절차** (CD 스크립트와 동일):
  ```
  cd /home/apps/SilverBridgeSky/SilverBridgeBe
  git fetch origin dev && git reset --hard origin/dev   # pull(merge) 금지 — divergent 재발(2026-07-02)
  docker compose -f docker-compose.dev.yml build api
  docker compose -f docker-compose.dev.yml up -d api
  docker image prune -f
  ```
  ※ `reset --hard`는 추적 파일만 되돌린다(서버의 untracked CSV/SQL 보존). `.env.dev`는 서버 로컬 파일이라 git에 없다 — `AI_API_KEY` 등은 서버에서 직접 관리.
- **2026-07-14 skyserver 반영 결과**: V28·V29·V30·**V31**(단수형 rename) 전부 success, 기동 에러 0건, `[ANOMALY] AI WS 연결됨` 확인, 외부 `api.devdmu.gosky.kr/actuator/health` = 200 UP.
- **접속**: skyserver = `ssh gosky`(ed25519 키). vkcs-linux = `ssh vkcs-linux`(키 인증 미설정 — 필요 시 `ssh-copy-id`).

## [2026-07-14] 이상감지 알림 2단계 — 보호자·본인 발송 (FCM 고정 + SMS 선택)

- **범위**: 1단계(수신·판정·이력) 위에 **알림 발송**을 얹음. 이력 적재 → `AnomalyDetectedEvent` → AFTER_COMMIT 리스너가 **ACTIVE 보호자 전원 + 피보호자 본인**에게 WebSocket(`anomaly-detected`) + FCM/SMS 발송. 마이그레이션 없음(V31 최신, 스키마 그대로).
- **채널 정책 확장**: `NotificationType`에 `Policy` 도입 — `SETTINGS_ONLY`(연결·문의) / `FORCED_PUSH_WITH_SMS_FALLBACK`(WARD_SOS, **기존 동작 그대로**) / `FORCED_PUSH_PLUS_SETTINGS`(신규 `ANOMALY_DETECTED` — FCM 고정 + SMS·알림톡은 사용자 설정대로). 디스패처 3분기. `isMandatory()` → `policy()`.
- **결정**: ① 본인에게도 발송(화재는 당사자 대피 최우선, 본인 문구엔 대피 안내) ② **FCM 미전달 시 SMS 강제 폴백 안 함** — 문자는 사용자 선택(과금 동의)이라 폴백이 그 선택을 뒤집음. `[NOTIFY-UNDELIVERED]` WARN만. ※ SOS 폴백은 유지 ③ 알림톡은 2차 PR(템플릿 심사 리드타임).
- **쿨다운 2층**: 이력(`AnomalyEventCooldown`, `(sessionId,type)` 5분) + 알림(`AnomalyNotificationCooldown`, `(userId,sessionId,type)` — **보호자 5분 / 본인 1분**). 둘 다 Redis fail-open(긴급 우선).
- **AI 팀 합의(2026-07-14)**: 라이브 경로에서 **`confidence >= 0.6` 이면 `danger=true`** 로 채우기로 함 → 배포되면 현행 `DANGER` 모드가 곧바로 실동작(백엔드 코드 변경 불필요). 그 전까지 이력 0건이 정상이며 `[ANOMALY-DANGER-MISMATCH]` WARN으로 감지된다.
- **테스트**: `./gradlew test` 278건 통과(실패 0). 디스패처 정책·리스너(수신자/쿨다운/격리)·이벤트 발행 검증 추가.
- 상세: `docs/(2026-07-14) feature-anomaly-notification-phase2.md`
