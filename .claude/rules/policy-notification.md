---
paths:
  - "**/domain/notification/**"
  - "**/domain/admin/**/*Notification*"
---

# 도메인 정책 - 알림 채널·발송 이력

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **알림 채널 추상화**(notification, 2026-05-31 / 정책 확장·알림톡 2026-07-15): 이벤트 → `NotificationDispatcher` → `NotificationType.Policy`에 따라 채널 결정. **`SETTINGS_ONLY`**(연결·문의 = 사용자 설정대로) / **`FORCED_PUSH_WITH_SMS_FALLBACK`**(`WARD_SOS` = FCM 강제 + 미전달 시 SMS 폴백, 폴백 문자는 알림 종류·수신자별 시간당 30건 상한(SOS와 이상감지는 따로 센다) - 초과 시 문자만 생략, 2026-10-05) / **`FORCED_PUSH_PLUS_SETTINGS`**(`ANOMALY_DETECTED`·`_SELF` = FCM 고정 + 나머지는 설정대로, **어느 채널로도 전달되지 않았을 때만 문자 대체**(2026-10-06)·**전달 0건이면 그 수신자의 알림 쿨다운을 푼다**(2026-10-07) - 알림톡·켠 문자로 이미 전달됐거나 켠 문자를 이미 시도했으면 보태지 않음, 같은 시간당 30건 상한). 채널=`NotificationChannel` 전략(FCM·SMS·**KAKAO_ALIMTALK**·**EMAIL** 구현. **이메일은 안내성 종류만**(연결·문의 답변·복약 미복용 요약, `Email.ALLOWED` 명시), 긴급(SOS·이상감지)·반복 알림은 제외, 킬 스위치 `notification.email.enabled` 코드 기본 false·**두 서버 `.env.dev`는 true**(2026-10-13 활성화), V60). **종류별 허용 채널**(2026-09-01): `NotificationType`이 설정 기반 발송에 쓸 채널을 선언하면 디스패처가 *사용자 활성 채널 ∩ 허용 채널*로 좁힌다(기본=이메일을 뺀 전 채널, 이메일은 `Email.ALLOWED` 명시 종류만 - 2026-10-13). ⚠️ **강제 FCM은 이 값으로 줄어들지 않는다**. **새 채널 = 구현체 빈 추가**만(디스패처 자동 수집). WebSocket은 추상화 밖(항상 발송). **SMS 인증번호는 디스패처 미경유**(설정 무시). 기본값 FCM ON. 설정 API `/api/user/me/notification-settings`(GET·PUT). **보호자 알림 종류 설정**(2026-10-08, V59) `GET·PUT /api/guardian/notification-type-setting` - SOS·이상감지 발생은 `required` 잠금(줄이지 못함), 끌 수 있는 건 재촉(기존 설정 위임)·복약 미복용 요약(계정 토글 AND 피보호자별)뿐, 정서·병원 예약은 BE 발송이 없어 만들지 않음. 발송 스레드: SOS·이상감지 = `urgentNotificationExecutor`, 그 외 = `notificationExecutor`. `camera.is_active`는 표시용(감지·알림을 끄지 않음, 2026-10-02). 카메라 방은 `CameraRoom` 8개(거실·침실·주방·화장실·현관·베란다·작은방·작은방2) 중 선택, 피보호자당 방마다 1대(V58 `uq_camera_ward_label`, 409 `CAMERA_LABEL_DUPLICATED`), 피보호자 연결 상태는 `GET /api/ward/camera/live`(2026-10-05), 본인 카메라 정지 화면은 `GET /api/ward/camera/{id}/latest-frame`(2026-10-15, 실시간 영상은 보호자 전용 유지).
  - **발송 이력**(2026-09-22, V54): 채널 `send()`는 `ChannelResult(status, reason)`를 돌려주고 디스패처가 **수신자 1명당 1행**을 `notification_log`에 남긴다(발송 후, `REQUIRES_NEW`, 실패는 WARN만 - 발송 우선). 결과 `DELIVERED`·`SMS_FALLBACK`·`FAILED`·`NOT_SENT`(**실패 아님**). 사유는 **고정 코드만**(예외 원문 금지). 관련 피보호자는 4인자 `dispatch(userId, wardId, …)`(표시 전용 - `data["wardId"]`로 대신하지 말 것). 조회 `GET /api/admin/notification`·`/summary`(ADMIN, 조회 전용). 보관 90일. 상세 `docs/(2026-09-22) feature-admin-notification-history.md`.
  - **웹푸시 문구·data 전용**(2026-10-30, PR #405): 보호자 제목 = 누가·어디서 + 종류(`[긴급] {이름}님 SOS`·`[화재 감지] {이름}님 댁 {장소}`·`오늘 복약 확인 · {이름}님`), 두 문장 본문은 `\n`. FCM data에 `title`·`body`를 모든 종류에 싣고(FCM 계층에서만, 알림톡 변수 맵 아님), `notification.fcm.webpush-data-only`(기본 false)가 켜지면 웹 토큰은 notification 블록 없이 보낸다(알림 두 번 표시 해결 - **FE SW가 data로 그린 뒤에만 켤 것**). 전달 판정은 웹·비웹 합산으로 기존과 같다. 웹푸시 `Urgency: high`는 `notification.fcm.high-urgency-types`(사용자 선택 10종). SOS 대체 문자는 옛 문구로 고정, 문자 채널은 `[`로 시작하는 제목에 괄호를 겹치지 않는다. 피보호자 문구·알림톡은 무변경. 상세 `docs/(2026-10-30) feature-web-push-copy.md`.
  - **카카오 알림톡**(`KAKAO_ALIMTALK`): 카카오톡 채팅 도착(Solapi, 전화번호 수신 — 카카오 로그인·친구추가 불요). **승인된 템플릿 문구만**(자유 문구 불가, `#{변수}`만 치환) → 종류별 템플릿 없으면 스킵. `pfId`·`templateId`는 `.env.dev`, `disableSms=true`. ⚠️ "카카오 푸시"(kapi)는 카카오톡이 아니라 앱 푸시(FCM 대행)라 미채택.

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


## 보호자 알림 종류 설정 - 필수 알림은 줄이지 못하고, 못 보내는 알림은 설정도 없다 (2026-10-08)

- **경위**: 보호자 환경설정 "알림 종류" 탭용 `GET·PUT /api/guardian/notification-type-setting`(V59 `guardian_notification_preference`). 상세 `docs/(2026-10-08) feature-guardian-notification-preference.md`.
- **불변 규칙 ①(필수 알림은 이 설정으로 줄지 않는다)**: `sos`·`anomalyDetection`은 응답에 `required: true`·`enabled: true`로만 내리고 변경 경로가 없다(요청에 넣어도 무시). 이 설정은 **"보내지 않는 쪽으로만"** 작용하며 `WARD_SOS`·`ANOMALY_DETECTED*`의 푸시/문자 대체 경로를 건드리지 않는다. 게이트는 해당 알림의 플래너가 건다 - `NotificationDispatcher`는 이 설정을 모른다(`GuardianNotificationPreferenceUsageGuardTest`가 참조처를 고정). 디스패처에 제외 채널 파라미터를 넣지 말 것.
- **불변 규칙 ②(복약 = 계정 AND 피보호자별)**: 계정 토글이 OFF면 `MedicationMissedAlertPlanner`가 **선점 기록도 만들지 않는다**(선점 후 발송·UNIQUE 불변). `MEDICATION_STOPPED`(조치가 필요한 안내)는 게이트하지 않는다.
- **불변 규칙 ③(재촉은 저장소를 늘리지 않는다)**: `anomalyReviewReminder`는 기존 `guardian_anomaly_setting`에 위임한다. 같은 값을 새 테이블에 복제하지 말 것.
- **불변 규칙 ④(정서 변화·병원 예약 설정 금지)**: BE가 발송하지 않는 알림의 토글은 보내지 못하는 약속을 저장하는 거짓 UI다. 발송 기능이 생기기 전에는 필드·컬럼을 만들지 말 것.


## 이메일 알림 채널 - 안내성 알림 전용, 긴급 알림의 전달 수단이 아니다 (2026-10-13)

- **경위**: 알림 채널 추상화에 EMAIL enum만 있던 것을 구현했다(`EmailNotificationChannel`, 비밀번호 재설정과 같은 `JavaMailSender`·`spring.mail`). 상세 `docs/(2026-10-13) feature-email-notification.md`.
- **불변 규칙 ①(허용 종류는 명시한 것만)**: `NotificationType.allowedChannels()` 기본값이 **이메일을 뺀 전 채널**이다. 이메일은 `Email.ALLOWED`를 적은 안내성 종류(연결 5종·`INQUIRY_ANSWERED`·`MEDICATION_MISSED`)만 쓴다. 새 종류는 아무것도 안 적으면 메일로 나가지 않는다(`NotificationTypeEmailPolicyTest`가 허용 집합을 고정). 기본값을 다시 전 채널로 되돌리지 말 것 - 복약 알림처럼 하루 여러 번 나가는 종류가 전부 메일로 간다.
- **불변 규칙 ②(긴급 알림 제외)**: `WARD_SOS`·`ANOMALY_DETECTED`·`ANOMALY_DETECTED_SELF`는 이메일을 쓰지 않는다. 이메일은 접수만 돼도 "전달됨"으로 잡혀 **이상감지 문자 대체(어느 채널도 전달되지 않았을 때만)를 막고**, 즉시성이 보장되지 않아 푸시 미전달 시 알림이 늦어진다. 이메일을 긴급 알림의 전달 수단으로 승격하지 말 것(`NotificationDispatcherEmailTest`).
- **불변 규칙 ③(수신 주소)**: 계정 이메일만 쓴다. 카카오 가입 시 서버가 부여한 가상 이메일 `kakao_숫자@kakao.com`은 메일함이 없으므로 보내지 않는다. **실패가 아니라 "대상 아님"(`NOT_APPLICABLE`)**이라 이력에 남기지 않는다(2026-10-13 점검 L-1 - 실패로 세면 관리자 실패율에 섞여 진짜 장애가 묻힌다, 불변 규칙 "보내지 않음은 실패가 아니다"). 이메일만 켠 가상 이메일 계정은 `NOT_SENT / NO_ENABLED_CHANNEL`이 된다. 주소가 비어 있는 경우만 `NO_EMAIL` 실패로 기록한다.
- **불변 규칙 ④(로그·이력)**: 수신 주소·본문·예외 원문을 로그·이력에 남기지 않는다(`MailException`은 클래스명만, 사유는 고정 코드 `PROVIDER_ERROR`). `LogRawExceptionGuardTest` 대상에 채널 파일을 더했다. 이메일 본문은 push와 같은 제목·본문이라 피보호자 이름·장소가 메일에 남는다(관리자 알림 이력과 같은 수준, 수용).
- **불변 규칙 ⑤(기본 OFF·킬 스위치)**: 사용자 설정 기본값은 FCM만 ON이고, V60이 구현체 도입 전에 저장돼 있던 `EMAIL=true` 행을 모두 OFF로 되돌렸다(비가역, 명시적으로 다시 켠 사용자만 받는다). `notification.email.enabled`(기본 false)가 꺼져 있으면 빈이 없어 설정이 ON이어도 발송되지 않고 설정값은 보존된다(`NOT_SENT / NO_ENABLED_CHANNEL`).
- **운영 상태(2026-10-13)**: 두 서버 `.env.dev`에 `NOTIFICATION_EMAIL_ENABLED=true`로 활성화했다(코드 기본값은 false). 복약 미복용 요약은 스케줄러 스레드에서 동기 발송이라, SMTP 호출은 전용 `EmailSendExecutor`에서 **호출 전체 시간 제한**(`notification.email.call-timeout-seconds`, 기본 10초)을 걸어 실행한다(점검 M-1 조치, 상세 `docs/(2026-10-13) audit-impact-email-notification.md`). 이 래퍼를 빼거나 CallerRuns로 바꾸지 말 것.
- **수용한 한계**: 시간 초과·풀 포화 건은 실패(`PROVIDER_ERROR`)로 기록되며 실제로 늦게 접수됐을 수 있다(Solapi 래퍼와 같음). SDK가 인터럽트에 반응하지 않으면 풀 스레드(4개)는 SMTP 자체 제한까지 남지만 호출자는 제한 시간에 풀려난다. Gmail SMTP 일일 발송 한도에 걸리면 `PROVIDER_ERROR`로 기록된다. 정지·탈퇴 진행 계정 차단은 기존 `dispatch()` 한 곳 그대로다.

## 알림 설정 조회 실패 격리 (2026-11-01, #425)

- **경위**: `FORCED_PUSH_PLUS_SETTINGS`(이상감지)가 강제 FCM을 보내기 **전에** `settingService.enabledChannels`를 읽어, 설정 조회(DB)가 실패하면 그 수신자의 화재 강제 푸시·문자 대체·이력이 모두 빠졌다. `SETTINGS_ONLY`는 예외가 호출자로 올라가 같은 리스너의 다른 수신자(강제 연결의 피보호자) 발송까지 막았다.
- **규칙**: 디스패처가 설정 조회 예외를 격리한다(`[NOTIFY-SETTINGS-UNAVAILABLE]` WARN, 예외 클래스명만). 강제 정책은 선택 채널만 빼고 **강제 FCM을 그대로** 보내며, 푸시가 실패하면 문자 대체 규칙을 그대로 적용한다(켠 문자를 확인 못 해 "시도 안 함"으로 본다). `SETTINGS_ONLY`는 **기본 채널로 대신 보내지 않고**(꺼 둔 채널로 가면 안 된다) `FAILED`·시도 채널 0건으로 기록하고 반환한다 - 사용자 선택이 아니므로 `NOT_SENT`가 아니다.
- **되돌리지 말 것**: 강제 FCM 계산 앞에서 설정 조회 예외가 빠져나가게 두지 말 것, 디스패처가 예외를 호출자로 올리지 말 것. SOS(`FORCED_PUSH_WITH_SMS_FALLBACK`)는 설정을 읽지 않아 영향이 없었다.
- **수용**: 이 `FAILED` 행은 채널 결과가 비어 관리자 이력 화면의 실패 사유 칸이 비어 보인다(사유 코드를 늘리면 V54 CHECK 재정의가 필요해 미룸).

## 2026-10-31 전체 점검 반영 (M-2)

- **FCM 토큰 삭제 조건**: `UNREGISTERED`는 항상 삭제, `INVALID_ARGUMENT`는 **같은 묶음에서 성공한 토큰이 있을 때만** 그 토큰 탓으로 보고 삭제한다. 묶음 전체가 `INVALID_ARGUMENT`면 메시지 자체(페이로드 초과·잘못된 data) 문제일 수 있어 보존하고 `[FCM-INVALID-ARGUMENT]` WARN을 남긴다 - 지우면 재등록 전까지 SOS 푸시를 못 받는다. 남은 불량 토큰은 유휴 정리(04:00)가 회수한다. 만료 토큰 로그는 앞 6자만.
