---
paths:
  - "**/domain/sos/**"
---

# 도메인 정책 - SOS

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **보호자 SOS 이력 연타 묶음**(sos, 2026-10-30, 마이그레이션 없음): `GET /api/guardian/sos/history?group=true`만 묶음 단위(같은 피보호자·같은 경로에서 **앞 누름과 60초 이내** 연쇄, `sos.history-group-gap-seconds`, DB 윈도 함수로 묶어 페이지 경계에서 안 잘림 - 자바에서 묶지 말 것). 항목 = 최신 건 값 + `pressCount`·`firstTriggeredAt`·`lastTriggeredAt`(생략 모드엔 없음), `totalElements`·`counts`는 **묶음 수**. 표시 전용 - 푸시 "최근 10분 내 N번째"·쿨다운과 별개. 상세 `docs/(2026-10-30) feature-sos-history-press-group.md`. **전화받은 보호자**(2026-10-30, V64): `POST /api/ward/sos`의 선택 `calledGuardianId`(GUARDIAN_CALL·ACTIVE 보호자만 저장, 아니면 NULL - **SOS는 거절하지 않음**, 확인은 저장 트랜잭션 밖 `SosCalledGuardianResolver`) → 이력 `calledGuardianName`(지금 연결된 보호자만, **사용자 ID는 내리지 않음** - 2026-10-30 점검 M-1), 묶음 기준에도 포함. 알림에 쓰지 않음 - `docs/(2026-10-30) feature-sos-called-guardian.md`.

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
- **전화받은 보호자(2026-10-30, V64 `called_guardian_id`)는 표시 전용이다**: 보호자 연락 줄의 이름 칩용이며 **알림 대상·발송을 가르지 않는다**(`SosTriggeredEvent`에 싣지 않아 리스너가 읽을 수 없다 - 이벤트에 더하지 말 것, 규칙 ②). 요청 값은 **SOS를 거절하지 않고** 걸러 저장한다 - GUARDIAN_CALL이 아니거나 형식(영숫자 1~6자)이 틀리거나 그 피보호자의 ACTIVE 보호자가 아니거나 확인이 실패하면 NULL + `[SOS-CALLED-GUARDIAN-IGNORED]` WARN(원문 미기록). 확인은 `SosCalledGuardianResolver`가 **SOS 저장 트랜잭션 밖에서** 먼저 한다 - 트랜잭션 안에서 `isActiveConnection`이 예외를 내면 잡아도 rollback-only가 되어 SOS 이력까지 잃는다(확인기에 `@Transactional`을 걸지 말 것). 이름은 이력 조회 시 그 보호자가 **지금** ACTIVE 연결일 때만 내린다(해제·탈퇴면 생략). **응답에 전화받은 보호자의 사용자 ID를 싣지 말 것** - 보호자끼리 서로의 ID를 볼 경로가 원래 없었고 칩에는 이름만 필요하다(2026-10-30 점검 M-1). 이력 조회 `[IDOR-ATTEMPT]` 로그는 `wardId` 형식(영숫자 1~6자)이 틀리면 원문 대신 길이만 남긴다(L-1). 보호자 탈퇴는 FK SET NULL(이력 보존). 묶음(`group=true`)은 보호자 연락에서 전화받은 보호자도 기준이다. 상세 `docs/(2026-10-30) feature-sos-called-guardian.md`.
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

## 2026-10-02 QA(BE) 반영 · 
### SOS·알림 결과·설정 최초 저장 (h)

- **디스패처 결과 반환**: `NotificationDispatcher`가 수신자별 `NotificationLogResult`를 돌려주고, SOS 리스너는 **전달된 보호자가 0명이면 쿨다운을 해제**해 재시도가 막히지 않게 한다. ⚠️ **SOS 알림은 항상 발송**한다는 정책은 그대로다 - 이 결과는 쿨다운 해제용일 뿐 발송 여부를 가르지 않는다.
- **설정 최초 저장**: 알림 설정·SOS 설정은 행이 없을 때 동시에 저장하면 UNIQUE 위반으로 500이 났다. `ON CONFLICT DO NOTHING` 후 재조회한다.
- **보호자 SOS 이력**: `triggerType` 필터와 `counts`(경로별 건수)를 제공한다. 열람 범위(ACTIVE 연결만)는 그대로다.
