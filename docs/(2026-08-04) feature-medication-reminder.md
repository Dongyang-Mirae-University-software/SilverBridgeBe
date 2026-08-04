# 복약 알림 API (2026-08-04)

> 보호자가 피보호자의 약을 등록하고, **피보호자가 직접 복용을 체크**하면 그 결과가 보호자에게 보이는 기능.
> 근거: 첨부 화면 4종(보호자 복약알림 목록 / 약 추가 모달 2탭 / 피보호자 오늘의 일정).

---

## 1. 확정한 요구사항

| # | 규칙 | 이유 |
|---|---|---|
| R1 | 보호자–피보호자가 **ACTIVE 연결**이어야 조회·등록·삭제 가능 | 연결이 유일한 열람 근거(SOS 이력 2026-07-30과 동일) |
| R2 | **약 등록·삭제는 보호자만** | 시니어가 약 이름·용량을 직접 입력하는 부담을 덜기 위함 |
| R3 | **복용 체크는 피보호자만** | "피보호자가 체크해야 보호자에게 보인다"가 이 기능의 목적 |

R2·R3는 문서가 아니라 **엔드포인트 구조**로 지킨다 — 보호자 컨트롤러에 체크 API가 없고, 피보호자 컨트롤러에 등록 API가 없다. 클래스 레벨 `@PreAuthorize`가 역할을 가른다.

---

## 2. 데이터 모델 (V35)

```
medication           약 마스터 (반복 일정)
  ward_id     → users(id) CASCADE      소유자. 피보호자 탈퇴 시 함께 삭제
  created_by  → users(id) CASCADE      등록 보호자. 탈퇴 시 그 보호자가 등록한 약도 중지
  time_slot / dose_time / dose_amount / memo
  deleted_at                           soft delete — 지난 복용 이력 보존

medication_intake    날짜별 복용 체크 (행 존재 = 복용)
  UNIQUE (medication_id, dose_date)    중복 체크 방지 → 재시도·더블탭 멱등
  dose_date                            KST 기준 날짜

medication_setting   피보호자별 복약 알림 ON/OFF
  UNIQUE (user_id)                     행이 없으면 기본값 ON(백필 불요)
```

**`created_by`를 CASCADE로 둔 결정**: 초안은 `SET NULL`(약은 피보호자 자산이므로 유지)이었으나, "탈퇴한 보호자가 남긴 데이터를 붙들지 않는다"를 우선해 **삭제**로 확정했다. 다만 조용히 사라지면 피보호자가 실제로 복용 중인 약이 본인 화면에서도 없어지고 **본인은 재등록할 수 없으므로**(R2), 남은 보호자에게 중지 안내를 보내는 것을 함께 넣었다(§4).

**"오늘" 판정은 항상 KST** (`MedicationClock`). 서버가 UTC로 돌면 09:00(KST) 이전 체크가 전날로 기록돼 카운트가 되돌아간다.

---

## 3. API

경로는 **단수형**을 쓴다(기존 `/api/admin/announcement`·`/api/ward/sos-setting` 관례).

| 역할 | 메서드 · 경로 | 화면 |
|---|---|---|
| 보호자 | `GET /api/guardian/medication` | 피보호자 카드 목록 + "오늘 2/3회" |
| 보호자 | `POST /api/guardian/ward/{wardId}/medication` | 약 추가 |
| 보호자 | `DELETE /api/guardian/medication/{medicationId}` | 약 삭제(x) — soft |
| 보호자 | `GET·PUT /api/guardian/ward/{wardId}/medication-setting` | 알림 토글 |
| 피보호자 | `GET /api/ward/medication/today` | 오늘의 복약 일정 + "0/3회 완료" |
| 피보호자 | `POST /api/ward/medication/{medicationId}/intake` | 복용 체크 |
| 피보호자 | `DELETE /api/ward/medication/{medicationId}/intake` | 체크 해제(오늘만) |

- 복용 시간은 `MedicationTimeSlot`(MORNING/LUNCH/DINNER/BEDTIME) + `doseTime`(생략 시 슬롯 기본값 08:00/13:00/18:00/22:00). 화면2의 select와 화면1·4의 "아침 08:00 / 취침 전 22:00" 표기를 모두 커버한다.
- 나이는 **서버가 계산한 만 나이**만 응답한다(생년월일 원본 미노출). 생년월일이 없으면 `null` → 프론트가 표기 생략.
- 표시 문구("아침 08:00 · 1정 · 식후 30분")는 프론트가 조립한다 — 서버는 원자값만 준다.
- 약 **수정(PUT)은 만들지 않았다** — 화면에 수정 UI가 없다.

### 인가 매트릭스

| 경로 | 역할 게이트 | 추가 검증 | 위반 |
|---|---|---|---|
| `/api/guardian/**` | `hasRole('GUARDIAN')` | `isActiveConnection(guardianId, wardId)` | 403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN |
| `/api/ward/**` | `hasRole('WARD')` | `medication.wardId == 본인` | 동일 |

- 인가 목록은 `getActiveWardIds`·`isActiveConnection`만 사용한다. **`getMyWards`는 PENDING이 섞여 있어 인가에 쓰면 수락 전 피보호자의 복약 정보가 노출된다.**
- 없는 자원·삭제된 약은 404(`MEDICATION_NOT_FOUND`), 남의 자원은 403. 403 응답에 소유자·약 내용은 싣지 않는다(2026-07-14 정책).

---

## 4. 알림

| 상황 | 채널 | 수신자 |
|---|---|---|
| 복용 체크·해제 | **WebSocket만** (`medication-taken`) | ACTIVE 보호자 전원 + 피보호자 본인(기기 동기화) |
| 등록 보호자 탈퇴로 약 중지 | WS(`medication-stopped`) + `NotificationType.MEDICATION_STOPPED`(SETTINGS_ONLY) | **남은 ACTIVE 보호자만** |

- 복용 체크에 FCM·SMS·알림톡을 붙이지 않은 이유: 하루 여러 번 일어나는 일상 동작이라 푸시로 알리면 소음이다(SOS ACK 2026-07-30과 동일한 판단).
- 중복 체크·미체크 해제는 **이벤트를 발행하지 않는다** — 상태가 안 바뀌었는데 알림만 반복되는 걸 막는다.
- 중지 안내를 **피보호자 본인에게는 보내지 않는다** — 본인은 재등록할 수단이 없어 조치 불가능한 알림이 되고 불안만 준다.
- WS 페이로드에 카운트("2/3")를 넣지 않는다 — 프론트가 목록을 갖고 있어 해당 항목만 갱신하면 카운트는 스스로 다시 계산된다.
- 토픽 `/topic/{userId}/...`은 STOMP 인터셉터의 범용 `{userId}==세션` 검증으로 자동 보호된다(이벤트 화이트리스트 없음).

### 탈퇴 정리 경로

`UserWithdrawnEvent` → `MedicationWithdrawalListener`(**동기** AFTER_COMMIT, best-effort try/catch)
→ `MedicationWithdrawalService`가 건수 집계 후 삭제 → 남은 보호자에게 안내.

- **동기인 이유**: 탈퇴는 커밋 직후 컨트롤러가 회원 행을 purge하므로, 비동기로 미루면 FK CASCADE가 약을 먼저 지워 "몇 건인지" 셀 수 없다. `UserWithdrawalConnectionListener`와 같은 형태다.
- **DB CASCADE는 안전망**이다 — 리스너가 실패했거나 스윕 purge로 리스너를 건너뛴 경우를 회수한다.
- **수용한 한계**: 스윕 purge(`WithdrawnUserPurgeScheduler`) 경로에서는 안내가 나가지 않고 약만 조용히 삭제된다. WITHDRAW 감사로그·연결 해제 알림이 이미 감수한 것과 같은 한계다.
- 이미 삭제(soft delete)된 약은 **안내 건수에서 제외**하되 **삭제 대상에는 포함**한다(문구의 숫자가 사실과 달라지지 않게 + 등록자 없는 잔여 행을 남기지 않게).

---

## 5. 변경 파일

**신규** — `V35__create_medication.sql`, `domain/medication/` 15개
(entity 4 · repository 3 · dto 6 · service 5 · controller 2 · event 1 · listener 2)

**변경 2개(추가만)**
- `ErrorCode`: `MEDICATION_NOT_FOUND`(404) · `MEDICATION_NOT_AUTHORIZED`(403)
- `NotificationType`: `MEDICATION_STOPPED(SETTINGS_ONLY)`

**무변경 확인**: connection·sos·anomaly·notification 디스패처·채널 구현체·SecurityConfig(신규 경로는 `anyRequest().authenticated()` + 클래스 레벨 `@PreAuthorize`로 커버)·STOMP 인터셉터.

---

## 6. 검증

- `./gradlew test` **전체 361건 / 실패 0**(신규 32건). 신규 테스트 축:
  - 인가 우회: 비ACTIVE 보호자의 조회·등록·삭제·설정 변경, 타인 약 체크·해제
  - 역할 분리: WARD의 등록·삭제 거부 / **GUARDIAN의 복용 체크 거부**(R3를 구조로 검증)
  - 멱등: 중복 체크·미체크 해제가 오류가 아니며 알림도 반복되지 않음
  - 카운트: "2/3", 약 없는 피보호자 카드(0/0), soft delete 제외
  - 탈퇴: 건수 집계·삭제, 탈퇴자 제외 수신자, 남은 보호자 없으면 미발송, 예외 미전파
- `dose_amount`는 **INT**로 정의했다 — `ddl-auto: validate`에서 엔티티 `int`와 `SMALLINT`가 어긋나면 기동이 실패한다(기존 마이그레이션도 전부 `INT`).
- ⚠️ **Flyway 실적용 미검증** — 이 환경에 Docker·로컬 PostgreSQL이 없다. 배포 전 V33 때처럼 dev 스키마 복제본에 V35를 적용해 확인하거나, 기동 로그에서 `version "35"`와 `validate` 통과를 확인할 것.

---

## 7. 이번 범위 밖 (후속)

1. **복용 시각 알림 발송** — `MEDICATION_REMINDER` 타입 + 스케줄러. `medication_setting.alarm_enabled`가 그때 발송 게이트가 된다(지금은 보관·조회만).
2. **미복용 시 보호자 알림** — 예: 취침 전까지 체크되지 않으면 통지.
3. **약봉투 카메라 인식(OCR)** — 화면2의 두 번째 탭. 백엔드에 OCR·이미지 업로드 인프라가 없어 제외했다.
4. **약 수정 API** — 화면에 수정 UI가 생기면.
