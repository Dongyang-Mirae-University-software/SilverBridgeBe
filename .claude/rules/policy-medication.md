---
paths:
  - "**/domain/medication/**"
---

# 도메인 정책 - 복약

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **약 봉투 OCR**(medication, 2026-10-22, 마이그레이션 없음): `POST /api/guardian/ward/{wardId}/medication/ocr`(GUARDIAN, multipart `image`, ACTIVE 연결만) → AI `/api/v1/medication/ocr` 중계, **등록 초안만**(사진·결과 저장 없음, 자동 등록 금지, `reason`·성분 미노출, 반 알은 `perDose` 0.5 → FE 메모, 약 이름 대조 `inList`(모르면 null)·`suggestions`(`inList=false`일 때만 최대 3, 100자 초과는 뺌, 참고용·자동 교체 금지) 전달 2026-10-23). 8MB·jpeg/png/webp·40초·동시 5/1인 1·킬 스위치 `medication.ocr.enabled`. multipart 전역 상한 10MB(경로별 상한은 서비스 검사). 상세 rules 파일.
- **복약 알림**(medication, 2026-08-04 1차 / 발송 2026-08-05, **1·2차 완료**): 보호자가 약을 등록(`POST /api/guardian/ward/{wardId}/medication`)하고 **피보호자만 복용을 체크**(`POST /api/ward/medication/{id}/intake`)한다 — 이 역할 분리가 기능의 목적이라 **보호자용 체크 API·피보호자용 등록 API를 만들지 말 것**. 인가는 ACTIVE 연결만(`getActiveWardIds`·`isActiveConnection`; `getMyWards`는 PENDING 혼입이라 금지), 위반 시 403 + `[IDOR-ATTEMPT]`. 날짜는 항상 **KST**(`MedicationClock`), 체크는 `UNIQUE(medication_id, dose_date)`로 멱등, 약 삭제는 soft delete(복용 이력 보존). 체크 알림은 **WebSocket만**(`medication-taken`). 등록 보호자 탈퇴 시 그 약도 삭제하고 **남은 보호자에게만** 중지 안내(`MEDICATION_STOPPED`).
  - **수정**(`PATCH /api/guardian/medication/{id}`, 2026-08-05): 부분 수정(null=미변경). `timeSlot`만 바꾸고 `doseTime`을 생략하면 **새 슬롯 기본 시각으로 갱신**("저녁 08:00" 방지), 메모는 **빈 문자열로 삭제**. **복용 시각이 바뀌면 당일 발송 기록(attempt 1·2)을 삭제**해 새 시각으로 다시 알림이 나가게 하되 **복용 체크는 유지**한다. `wardId`·`createdBy`는 불변.
    - **시간대는 시각으로 (2026-10-30)**: 수정에서 `timeSlot`이나 `doseTime`을 보내면, 단건 등록에서 `doseTime`을 보내면 저장 시간대는 **최종 시각으로 다시 정한다**(`MedicationTimeSlot.fromDoseTime`, 일괄 등록과 같은 규칙). 어긋나게 와도 400이 아니라 **시각 기준**(예전 화면 호환). 이름·복용량·메모만 고치면 시간대를 건드리지 않는다. "점심 01:40" 같은 약이 보호자 화면 시간대 칸 순서를 뒤집었다(알림 묶음 키는 같은 날 시간대를 빼 영향 없음 - 아래 "시간대 묶음"). 이미 어긋난 옛 약은 마이그레이션 없이 **다음 수정 때 바로잡힌다**(사용자 결정). FE 수정 창도 시각↔시간대를 맞춰 움직인다. 상세 `docs/(2026-10-30) fix-medication-slot-from-time.md`.
  - **미복용 요약**(3차, V37 / 시각 선택 V40 / 피보호자별 V41): **보호자가 그 피보호자에 대해 지정한 시각**(미설정 시 21:00 KST)에 **ACTIVE 보호자 중 수신 설정 ON**인 사람에게 `MEDICATION_MISSED`(FCM·문자) 발송. **지정 시각 = 집계 상한** — 그 시각까지 복용 시각이 지난 약만 집계한다(21:00이면 취침 전 22:00 약이 빠짐 — 의도된 동작이라 시각을 그 피보호자의 마지막 복약 시각에 맞춰 잡는 것이 이 설정의 요점). 하루 1건(`UNIQUE(guardian_id, ward_id, dose_date)`), 미체크 0건이면 미발송. ⚠️ 문구는 **"체크되지 않았습니다"** — "안 드셨습니다"로 쓰지 말 것(체크 누락과 실제 미복용을 구분할 수 없다). 설정 API `GET·PUT /api/guardian/ward/{wardId}/medication-alert-setting`(**(보호자, 피보호자) 축** — 같은 피보호자를 보는 보호자끼리 시각이 다를 수 있다. 두 필드 모두 선택=미변경, 위반 시 403+`[IDOR-ATTEMPT]`). 카드 조회(`GET /api/guardian/medication`) 응답에 설정 3종이 동봉된다(`alarmEnabled`는 2026-10-24 제거). 킬 스위치 `medication.reminder.missed-alert.enabled`는 **2차와 독립**.
  - **오늘 늦게 등록한 약**(2026-10-22, 마이그레이션 없음): 그날(KST) 등록됐고 등록 시각 > 복용 시각인 약은 그날 미체크로 세지 않는다 - 기준은 `MedicationClock.registeredAfterDose` **한 곳**(미복용 요약·보호자 대시보드 "확인이 필요한 일"), 카드 응답 `MedicationItem.createdAt`(KST)으로 FE 카드도 같은 판정(회색 "오늘은 등록 전이었어요"). 상세 `docs/(2026-10-22) feature-medication-card-registered-today.md`.
  - **시간대 묶음**(2026-10-23, 마이그레이션 없음): 피보호자 묶음 체크 `POST /api/ward/medication/intake/batch` `{medicationIds, taken}`(WARD 전용, 1~50개, **전부 성공/전부 실패**, 약마다는 단건과 같은 메서드·잠금 ID 오름차순 - 보호자용 체크는 여전히 없음). 복약 알림은 **선점은 약 단위 그대로, 발송만** (피보호자, 날짜, 시각, 회차)로 묶어 1건(문구는 2026-10-27부터 한 건과 같은 단문 "약 드실 시간이에요." - 약 이름·개수·시각 없음, data `medicationIds` 추가). **묶음 키에 시간대는 넣지 않는다**(2026-10-30 영향 범위 점검 L-1) - 문구에 시간대가 없으니 같은 시각이면 한 건으로 충분하고, 넣으면 시간대와 시각이 어긋난 옛 약이 같은 분에 알림을 두 건으로 가른다. FE 보호자 카드·피보호자 화면도 시간대 묶음("3번 중"). 상세 `docs/(2026-10-23) design-medication-time-group.md`. 보호자 여러 개 삭제 `POST /api/guardian/ward/{wardId}/medication/delete-batch` `{medicationIds}`(GUARDIAN·ACTIVE 연결, 1~100개, 전부/전무, 다른 피보호자 약 섞임 403, **이미 삭제된 약은 건너뜀**, soft delete - FE "아침 약 모두 삭제"·"약 전부 삭제", 화면의 ID만 보냄). `Medication`은 **`@DynamicUpdate` - 떼지 말 것**(2026-10-23 #372: 전체 컬럼 UPDATE면 수정과 삭제가 겹칠 때 삭제가 취소돼 알림이 계속 나갔다, `MedicationDeletedRaceIntegrationTest`).
  - **일괄 등록·반 알**(2026-10-22, V62): `POST /api/guardian/ward/{wardId}/medication/batch` = drugs(1~20) × times(1~6) **한 트랜잭션**(전부 성공/전부 실패), 시간대는 시각으로(04~10 아침/11~15 점심/16~20 저녁/그 외 취침 전), 같은 요청 안 중복 400. `doseAmount`는 **0.5 단위**(0.5~99, `NUMERIC(3,1)`+CHECK, 정수 하위 호환, 응답 `1`·`0.5`), 알림 문구 "반 알". 상세 `docs/(2026-10-22) feature-medication-batch-half-dose.md`.
  - **발송**(2차, V36): 스케줄러 1분 주기 → 복용 시각 도래 + 미체크면 **피보호자 본인에게**(알림 ON/OFF 스위치·`medication-setting` API는 2026-10-24 제거 - 늘 보낸다, `alarm_enabled` 컬럼은 읽지 않음) `MEDICATION_REMINDER`(SETTINGS_ONLY) 발송. **채널은 FCM·문자뿐** — 알림톡은 `templates` 매핑을 두지 않아 스킵(다발성 메시지라 별도 템플릿 승인 필요), WS도 안 보냄. 유예 **30분**(자정은 안 넘김), **재알림은 사용자 선택**(`remind_again_enabled`, 기본 ON, 15분 뒤 1회, 마감 60분). 중복 발송은 **"선점 후 발송"**(`medication_reminder_log` + `UNIQUE(medication_id, dose_date, attempt)`)으로 막는다 — 순서를 뒤집지 말 것. 킬 스위치 `medication.reminder.enabled`.


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
- **복용 시각 알림은 늘 보낸다 (2026-10-24 사용자 결정)**: 보호자 화면의 "○○에게 약 드실 시간 알려 드리기" 스위치와 설정 API(`GET·PUT /api/guardian/ward/{wardId}/medication-setting`)를 없앴다. `medication_setting.alarm_enabled` 컬럼은 비가역 DROP을 피하려고 남겼지만 **읽지 않는다** - 예전에 꺼 둔 피보호자도 다시 받는다. 재알림(`remind_again_enabled`)은 저장값(기본 켜짐)대로 따르되 바꾸는 API는 없다. 발송을 다시 끌 수 있게 하려면 스위치·API를 되살리는 정책 결정이 먼저다. **SOS 등 필수 알림은 복약 설정과 무관**하다(SOS 동작 설정 2026-07-23 규칙의 연장).
- 상세: `docs/(2026-08-04) feature-medication-reminder.md`.
- **일괄 등록·반 알 (2026-10-22, V62)**: 일괄 등록(`/medication/batch`)도 **보호자만**·`isActiveConnection`만이며 약 한 건의 생성 규칙은 단건 등록과 같은 `newMedication`을 쓴다(복제 금지). **한 트랜잭션 - 전부 성공 또는 전부 실패**를 부분 성공으로 바꾸지 말 것(어느 약이 빠졌는지 보호자가 모른 채 알림이 비게 된다). 복용량은 0.5 단위(`DoseAmounts`, DB CHECK 동일) - 사용자에게 보이는 서버 문구는 "0.5정"이 아니라 "반 알". 상세 `docs/(2026-10-22) feature-medication-batch-half-dose.md`.

### 복약 알림 발송 (2차, 2026-08-05)

- **불변 규칙 ④(선점 후 발송)**: 복약 알림은 `medication_reminder_log`에 발송 기록을 **먼저 커밋한 뒤** 발송한다. 순서를 뒤집어 "보내고 기록"으로 만들면 발송 직후 앱이 죽었을 때 다음 주기에 또 보낸다. 스케줄러가 1분마다 돌기 때문에 기록이 없으면 유예 창(30분) 내내 같은 알림이 30번 나간다. `UNIQUE (medication_id, dose_date, attempt)`가 최종 방어선이며, 이 제약을 완화하지 말 것.
  - 대가로 **발송 실패 시 그 회차는 유실**된다(재시도하지 않는다). 알림이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고, 재알림이 두 번째 기회다.
- **불변 규칙 ⑤(복약 알림톡 금지)**: `MEDICATION_REMINDER`·`MEDICATION_STOPPED`에 **알림톡 템플릿을 매핑하지 말 것**. 복약 알림은 매일 반복되는 전형적인 **다발성 메시지**라, 보내려면 "반복 수신에 동의했음"을 고정 문구로 고지한 별도 템플릿 승인이 필요하다(이상감지 2차 반려 사유, 2026-07-23). 승인 없이 매핑하면 승인 문구와 다른 발송이 되어 발신 프로필 차단 대상이 된다. 현재는 매핑이 없어 조용히 스킵된다(`ANOMALY_DETECTED_SELF`와 같은 구조).
- **불변 규칙 ⑥(유예 창은 자정을 넘지 않는다)**: `graceWindowStart()`는 00:00에서 자른다. 하루를 되감으면 `dose_date`가 달라져 **어제 약을 오늘 날짜로** 보내게 되고, 발송 기록·복용 체크의 날짜 기준이 어긋난다. "23:50 약이 자정 넘겨 안 나간다"를 고치겠다고 되감기를 넣지 말 것 — 그 건은 건너뛰는 것이 의도된 동작이다.
- **재알림은 사용자 선택**(`medication_setting.remind_again_enabled`, 기본 ON). 문자를 켜둔 사용자에게는 한 번 복용에 문자가 2건까지 나가므로 끌 수 있어야 한다. **강제 채널로 승격시키지 말 것** — 복약은 SOS·화재 같은 즉시 대응이 아니라 매일 반복되는 일상이라 사용자의 선택을 뒤집을 근거가 없다.
- ~~설정 API 하위호환~~: `PUT .../medication-setting`은 2026-10-24에 없앴다(위 항목).
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


## 약 봉투 OCR - 사진은 저장하지 않고 초안만 (2026-10-22)

- **경위**: 시안의 "약 봉투를 찍으면 자동 등록"을 측정한 결과(sonnet 5.5, 바로 세운 사진 9/10, 1글자 오독 잔존) 자동 등록은 하지 않고 초안만 주기로 했다. 상세 `docs/(2026-10-22) feature-medication-ocr.md`, 측정 `docs/(2026-10-22) result-medication-ocr-measure.md`.
- **불변 규칙 ①(저장 금지)**: 봉투에는 환자 이름·주민번호 앞자리·병원·약국이 찍혀 있다. 사진·AI 결과를 DB·디스크·감사 로그·알림 이력에 남기지 말 것. 등록은 보호자가 확인·수정한 뒤 기존 약 추가 API로만 한다 - OCR 결과로 약을 **자동 등록하는 경로를 만들지 말 것**.
- **불변 규칙 ②(인가·역할)**: GUARDIAN 전용이고 `isActiveConnection`만(`getMyWards` 금지), 위반 403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`. 인가는 사진 검사보다 **앞**이다. 피보호자용 OCR은 만들지 않는다(약 등록이 보호자 몫 - 복약 불변 규칙 ①).
- **불변 규칙 ③(허용 목록)**: 응답은 `MedicationOcrProjection`이 읽는 칸만 담는다. AI `reason`(자유 문장)과 성분은 내리지 말 것 - 자유 문장에는 사진 속 개인정보가 섞일 수 있고, 성분은 확신 high로 틀렸다. 환자·병원·약국 칸을 더하지 말 것.
- **불변 규칙 ④(로그)**: 보호자 ID·wardId·소요 시간·약 개수·결과 코드·상태 코드·예외 클래스명만. 사진·약 이름·AI 응답·업로드 파일 이름 금지(`MedicationOcrLogGuardTest`). 업로드 파일 이름은 AI에도 넘기지 않는다(형식별 고정 이름).
- **40초·동시 상한**: 모델 호출이라 일반 8~10초 규칙의 예외다. 요청 스레드를 붙들므로 동시 전체 5 / 1인 1(인스턴스 메모리)을 없애지 말 것. AI 키는 `X-API-Key` 헤더만, 리다이렉트 미추종, AI 오류 본문은 읽지 않는다(챗 중계와 같음).
- **multipart 전역 상한 10MB**: 경로별 상한은 서비스가 검사한다(프로필 5MB `UserService`, OCR 8MB). 전역 값을 올릴 때 서비스 검사가 없는 업로드 경로가 생기지 않았는지 확인할 것.
  - gosky 앞단 nginx(`devdmu.gosky.kr`·`api.devdmu.gosky.kr`)는 기본 1MB였다 → 2026-10-22 `client_max_body_size 10m` 적용(백업 `/root/nginx-backup-20261022-ocr/`). 업로드 상한을 바꿀 때는 nginx 값도 함께 볼 것.
- **점검 후속 (2026-10-22, L-1·L-2·L-6)**: ① OCR 경로는 `SecurityConfig` URL 규칙으로도 GUARDIAN만 - 메서드 보안은 multipart 파싱 뒤라 WARD·ADMIN이 10MB를 읽게 한 뒤 403이었다. 이 URL 규칙을 지우지 말 것(`@PreAuthorize`와 이중). ② 업로드 전역 상한 10MB 때문에 프로필 사진 업로드에 사용자 기준 속도 제한(분 5·시간 30)을 뒀다 - 새 업로드 경로를 만들면 서비스 크기 검사 + 속도 제한을 함께 둘 것. ③ 복약 IDOR 로그는 경로 `wardId` 형식(영숫자 1~6자)을 먼저 확인하고 틀리면 원문 대신 길이만 남긴다(`MedicationWardIds`, 챗 대리 상담과 같음).
- **반 알**: OCR 응답은 `perDose` 원값(0.5)을 준다. `doseAmount` 0.5 단위 저장은 후속 PR #362(V62)에서 지원한다.
