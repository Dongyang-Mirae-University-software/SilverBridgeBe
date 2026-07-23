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
- 상세: `docs/(2026-07-14) fix-audit-findings.md`.

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
- **현황(2026-07-23)**: 이상감지 **보호자용** 템플릿 `KA01TP260715015020754dXeU0ww3my9` — **2차 반려**. 카테고리 `서비스이용 > 이용안내/공지(004001)`, 기본형, 대체발송 OFF. 발신 프로필 `KA01PF240930145539248iUN6bVyplGB`. 승인 전까지 `ALIMTALK_ENABLED=false`(채널 스킵). `pfId`·`templateId`·Solapi 키는 `.env.dev` 주입(평문 커밋 금지).
  - **반려 이력**: 1차(2026-07-20) = 수신 대상·발송 사유 불명확("수신자 액션 기반 정보성 메시지"가 아님) → 수신자 액션·트리거를 명시해 재검수. 2차(2026-07-23) = **다발성 메시지 수신 동의 고지 누락** → 위 고정 문구 추가 필요.
  - ⚠️ **제출 문구 원문을 리포에 기록할 것** — 1·2차 제출 본문이 남아 있지 않아 반려 대응 시 "무엇을 고쳤는지" 재구성이 어렵다. 재검수 요청할 때마다 제출한 본문 전문을 이 파일 또는 `docs/`에 남긴다.
  - **승인 후 선행 코드 작업**: 새 문구가 `#{detectedAt}`(감지 시각)을 쓰는데 **아직 코드에 없다** — `AnomalyNotificationListener`의 `data` 맵과 `application.yaml`의 `variables` 목록에 추가하지 않으면 `bindVariables()`가 빈 문자열로 채워 "감지 시각: "만 발송된다.
  - **피보호자 본인용 템플릿은 미등록** — 현재 리스너는 보호자·본인 양쪽에 같은 알림을 보내는데, 본인에게 "OOO님 ~에서 감지"는 문구가 어긋난다. 별도 템플릿 등록 + `templateFor()`의 종류별 단일 매핑 구조 확장이 필요하다(리스너에 `self` 플래그는 이미 있음).
