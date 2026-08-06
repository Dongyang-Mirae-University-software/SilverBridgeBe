# 미점검 API 전수 점검 — PR #218~#227

> 대상: 2026-07-15 ~ 2026-08-05 머지분 (커밋 `8ac668f..99769bb`, 29 커밋)
> 기준 대장: `docs/audit-index.md` — 마지막 점검이 #217(2026-07-14)이라 그 이후 10개 PR이 점검 없이 머지돼 있었다.
> 점검일: 2026-08-05 · 점검자: Claude Code (점검만, 코드 미수정)

---

## PHASE -1. 사전 환경 확인

| 항목 | 결과 |
|---|---|
| HEAD | `99769bb` (#227 medication-update), 워킹트리 클린 |
| 빌드 | `./gradlew build -x test --no-daemon` **통과**(exit 0) |
| 테스트 | `./gradlew test --no-daemon` **통과** — 79개 클래스 / **405 tests / 0 failures** |
| 마이그레이션 | V37이 최신 (V32~V37이 이번 범위) |

## PHASE 0. 점검 대상

### 대상 PR

| PR | 머지 | 내용 | 마이그레이션 |
|---|---|---|---|
| #218 | 07-15 | 감사 지적 수정 + 알림톡 채널 구현 | — |
| #220 | 07-23 | 알림톡 `#{detectedAt}` 변수 | — |
| #219 | 07-23 | SOS 동작 설정 | V32 |
| #221 | 07-27 | 알림톡 보호자 전용(`ANOMALY_DETECTED_SELF`) | — |
| #222 | 07-30 | 이상감지 쿨다운 기본값(이력 5→1분 / 본인 1→3분) | — |
| #223 | 07-31 | SOS 이력 조회 + ACK + 위치 | V33·V34 |
| #224 | 08-04 | 복약 1차 (등록·체크) | V35 |
| #225 | 08-05 | 복약 2차 (스케줄러 발송) | V36 |
| #226 | 08-05 | 복약 3차 (미복용 보호자 요약) | V37 |
| #227 | 08-05 | 복약 4차 (약 수정 PATCH) | — |

### 신규 엔드포인트 × 역할 인가 (15개)

| 엔드포인트 | 역할 게이트 | 자원 인가 | 결과 |
|---|---|---|---|
| `GET /api/guardian/medication` | GUARDIAN(클래스) | `getActiveWardIds` | PASS |
| `POST /api/guardian/ward/{wardId}/medication` | GUARDIAN | `isActiveConnection` | PASS |
| `PATCH /api/guardian/medication/{id}` | GUARDIAN | 약→ward → `isActiveConnection` | PASS |
| `DELETE /api/guardian/medication/{id}` | GUARDIAN | 〃 | PASS |
| `GET /api/guardian/ward/{wardId}/medication-setting` | GUARDIAN | `isActiveConnection` | PASS |
| `PUT /api/guardian/ward/{wardId}/medication-setting` | GUARDIAN | 〃 | PASS |
| `GET /api/guardian/medication-alert-setting` | GUARDIAN | 본인(축 없음) | PASS |
| `PUT /api/guardian/medication-alert-setting` | GUARDIAN | 〃 | PASS |
| `GET /api/ward/medication/today` | WARD(클래스) | 본인 wardId | PASS |
| `POST /api/ward/medication/{id}/intake` | WARD | `medication.wardId == 요청자` | PASS |
| `DELETE /api/ward/medication/{id}/intake` | WARD | 〃 | PASS |
| `GET /api/guardian/sos/history` | GUARDIAN | wardId 지정=`isActiveConnection` / 생략=`getActiveWardIds` | PASS |
| `PATCH /api/guardian/sos/{id}/ack` | GUARDIAN | 이력→ward → `isActiveConnection` | PASS |
| `GET /api/ward/sos-setting` | WARD(클래스) | 본인 | PASS |
| `PUT /api/ward/sos-setting` | WARD | 본인 | PASS |

### 비-HTTP 실행 경로

- `MedicationReminderScheduler`(1분 주기) → 복용 알림 / 재알림 / 미복용 요약
- `MedicationIntakeNotificationListener`(AFTER_COMMIT + `@Async`) → WS `medication-taken`
- `MedicationWithdrawalListener`(AFTER_COMMIT **동기**) → 약 정리 + `MEDICATION_STOPPED`
- `SosAckNotificationListener`(AFTER_COMMIT + `@Async`) → WS `sos-acknowledged`
- `AnomalyNotificationListener` → 본인분 `ANOMALY_DETECTED_SELF` 분기

### 문서-코드 drift

**drift 없음.** `docs/(2026-08-0*) feature-*.md`·CLAUDE.md·`domain-security-policy.md`에 기재된 내용이 코드와 일치한다. 특히 아래 불변 규칙은 코드에서 직접 확인했다.

---

## PHASE A. 보안·인가 — PASS

- **IDOR**: 신규 엔드포인트 15개 전건이 `getActiveWardIds()`·`isActiveConnection()`만 사용한다.
  **`getMyWards()` 사용처 0건** (PENDING 혼입 경로 없음).
- **403 + `[IDOR-ATTEMPT]` WARN** 형태를 4곳 모두 준수: `GuardianMedicationService.requireActiveConnection`,
  `WardMedicationService.findOwnMedication`, `GuardianSosService.resolveVisibleWardIds`·`acknowledge`.
- **탈퇴 익명 이력 차단**: `GuardianSosService.acknowledge`가 `wardId == null`을 명시적으로 403 처리
  (`GuardianSosService.java:96`). 익명 이력은 `getActiveWardIds` 결과에 절대 포함되지 않으므로 조회에서도 빠진다.
- **역할 분리(요구사항 R3)**: 보호자 컨트롤러에 체크 API 없음 / 피보호자 컨트롤러에 등록 API 없음.
  클래스 레벨 `@PreAuthorize`가 게이트이며 `MedicationControllerSecurityTest`가 GUARDIAN의 체크 시도 거부를 고정한다.
- **입력 검증**: `@Size`·`@Min`/`@Max`·`@NotBlank` 적용. `MedicationUpdateRequest`는 전 필드 선택(null=미변경),
  `SosTriggerRequest`는 바디 자체가 선택(`required = false`)이라 기존 무바디 호출과 호환된다.
- **PII·로그**: 403 응답에 소유자·내용 정보 없음. 로그는 ID만 남기고 약 이름·메모·SOS 위치를 찍지 않는다.
- **WS 토픽**: `medication-taken`·`medication-stopped`·`sos-acknowledged` 모두
  `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 없음).

## PHASE B. 기능 정합성 — PASS

| 불변 규칙 | 확인 위치 | 결과 |
|---|---|---|
| ③ 날짜는 KST | `MedicationClock`(`ZoneId.of("Asia/Seoul")`) — 조회·체크·스케줄러가 모두 경유 | PASS |
| ④ 선점 후 발송 | `Planner.claim*` (`@Transactional`, `saveAll` 후 반환) → `Service.send`가 커밋 뒤 dispatch | PASS |
| ④ UNIQUE 최종 방어선 | `uq_medication_reminder (medication_id, dose_date, attempt)` (V36) 존재·미완화 | PASS |
| ⑤ 복약 알림톡 금지 | `application.yaml` `templates`에 `ANOMALY_DETECTED`만. `MEDICATION_*` 매핑 없음 | PASS |
| ⑥ 유예 창 자정 미되감기 | `graceWindowStart()`가 `LocalTime.MIN`으로 절단 | PASS(단 M-2 참조) |
| ⑦ 단정 금지 | 문구 = "…아직 체크되지 않았습니다" / 재알림 = "…복용 체크되지 않았어요" | PASS |
| ⑧ 집계 상한 = 판정 시각 | `findByDeletedAtIsNullAndDoseTimeLessThanEqual(alertTime)` | PASS |
| ⑨ 요약 축 하루 1건 | `uq_medication_missed_alert (guardian_id, ward_id, dose_date)` (V37) | PASS |
| 킬 스위치 독립 | `sendWardReminders()`와 `sendGuardianMissedAlerts()`가 각각 별도 플래그 확인 | PASS |
| 설정 API 하위호환 | `updatePreference`·`updateMissedAlertEnabled` 모두 `null`=미변경 | PASS |
| PATCH 부분 수정 | timeSlot 단독 변경 시 새 슬롯 기본 시각 / memo 빈 문자열 삭제 / 시각 변경 시 발송기록만 삭제(체크 보존) | PASS |
| ANOMALY_DETECTED_SELF | 리스너가 본인만 SELF 타입으로 dispatch, `data["type"]`은 `ANOMALY_DETECTED` 유지(FE 계약) | PASS |
| ACK 알림 미개입 | `SosNotificationListener`·`NotificationDispatcher`가 `ack_status`·`sosAction`을 읽지 않음 | PASS |

## PHASE C. 구조·계약 — 이슈 1건 (H-1)

- `@Transactional` 경계·`readOnly` 구분 적절, 이벤트 전부 AFTER_COMMIT.
- `MedicationWithdrawalListener`는 **동기**(`@Async` 없음) — 규칙대로다(purge CASCADE보다 먼저 세어야 함).
  예외를 try/catch로 삼켜 좀비 계정(M-S1-1)을 만들지 않는다.
- N+1 회피 일관: `findPreferences`·`findByMedicationIdInAndDoseDate`·`findAllById`로 일괄 조회.
- 응답 포맷·상태코드·Swagger 상세하며 실제 동작과 일치(SOS 발생만 201, 나머지 200).
- V32~V37 제약·인덱스가 코드 가정과 일치.

## PHASE D. 테스트 — 이슈 2건 (M-1, M-2)

- 신규 도메인 테스트가 두텁다: 인가 우회(`MedicationControllerSecurityTest`·`GuardianSosControllerSecurityTest`·
  `WardSosSettingControllerSecurityTest`), 경계값(재알림 구간·마감 역전·판정 시각 상한), 정책 문구 고정.
- 405개 전부 통과.

---

## 이슈 목록

### 🟠 H-1. AFTER_COMMIT 리스너에서 `@Transactional`(REQUIRED)로 쓰기 — 커밋 여부 미검증

**위치**: `MedicationWithdrawalService.removeMedicationsRegisteredBy` (`@Transactional`),
`MedicationWithdrawalListener.handleWithdrawn`(AFTER_COMMIT 동기)에서 호출.
동일 패턴: `ConnectionService.tearDownConnectionsOnWithdrawal` (2026-05-26부터 존재).

**내용**: `@TransactionalEventListener(AFTER_COMMIT)` 시점에는 원본 트랜잭션이 이미 커밋됐지만 스레드에
바인딩된 채로 남아 있다. 이때 전파 속성이 `REQUIRED`인 메서드를 호출하면 **새 트랜잭션이 아니라 완료된
트랜잭션에 참여**하게 되어, 그 안의 쓰기가 커밋되지 않고 정리 시점에 버려질 수 있다(Spring의 알려진 함정 —
그래서 공식 권장이 `REQUIRES_NEW`다).

**리포 내 불일치**: 같은 AFTER_COMMIT 경로인 `UserAccountEventListener`·`KakaoRegisterEventListener`·
`AccessLogService`·`RefreshTokenRevocationService`는 **모두 `REQUIRES_NEW`**를 명시한다. 탈퇴 정리 2곳만 `REQUIRED`다.

**현재 영향 = 없음(가려져 있음)**: 탈퇴는 커밋 직후 purge가 회원 행을 hard delete하고,
`medication.created_by`·`connection` FK가 `ON DELETE CASCADE`라 행은 어차피 사라진다. 그래서 쓰기가
유실돼도 결과가 같아 아무도 눈치채지 못한다. 알림·건수 집계는 조회라 영향이 없다.

**언제 터지는가**: `created_by`를 `SET NULL`로 바꾸면(설계 초안이 실제로 그랬다) 즉시 드러난다 —
약이 지워지지 않은 채 "중지 안내"만 나간다.

⚠️ **이 항목은 코드 리딩 기반 추정이며 실측하지 않았다**(실 DB 통합 테스트가 없어 — M-1 참조).
단정하지 말고 아래로 확인할 것:

```
1) 로컬 dev DB 기동 → 보호자 계정으로 약 등록 → 그 보호자 탈퇴
2) purge 직전에 중단(또는 created_by FK를 임시로 SET NULL로 바꾼 사본 DB)하고
   medication 행이 실제로 사라졌는지 확인
3) 유실이 확인되면 두 메서드에 @Transactional(propagation = REQUIRES_NEW) 적용
```

### 🟡 M-1. 실 DB 통합 테스트가 전무

**내용**: 테스트 405개가 **전부 목(mock) 기반**이다. H2·Testcontainers 의존성이 없고
`@DataJpaTest`도 없다(`@SpringBootTest`는 컨텍스트 로드용 `BackendApplicationTests` 뿐).

**놓치는 것**:
- Flyway 마이그레이션 ↔ 엔티티 매핑 정합성(V32~V37 6개가 한 번도 실행 검증되지 않음)
- **`uq_medication_reminder` UNIQUE 제약** — 불변 규칙 ④가 "최종 방어선"이라 부르는 바로 그 제약이
  테스트에서 한 번도 걸려본 적이 없다. 목은 제약을 흉내내지 않는다.
- 트랜잭션 전파·AFTER_COMMIT 실제 커밋 여부(H-1이 미검증인 이유)
- soft delete 부분 인덱스(`WHERE deleted_at IS NULL`) 동작

**권고**: Testcontainers PostgreSQL로 최소 3개만 — ① Flyway V1~V37 순차 적용,
② 같은 `(medication_id, dose_date, attempt)` 2회 저장 시 제약 위반, ③ 탈퇴 리스너 후 medication 행 상태.

### 🟡 M-2. 자정 유예 창(불변 규칙 ⑥) 테스트가 회귀를 잡지 못한다

**위치**: `MedicationReminderPlannerTest.claimFirst_유예창_조회구간` (`:129-147`)

**내용**: 두 가지 이유로 이 테스트는 규칙 ⑥의 회귀를 통과시킨다.

1. **기대값을 프로덕션과 같은 삼항식으로 재계산**한다(`:142-144`가 `graceWindowStart`의 로직을 그대로 복제).
   구현이 반대로 바뀌면 기대값도 같이 바뀌어 여전히 통과한다.
2. **실 시각(`MedicationClock.now()`)에 의존**한다. `MedicationClock`이 정적 유틸이라 시간을 주입할 수 없어,
   자정 분기(`LocalTime.MIN`)는 **CI가 00:00~00:30 KST에 돌 때만** 실행된다.

**대비되는 좋은 예**: `MedicationMissedAlertPlannerTest`는 `properties.getMissedAlert().setAlertTime(...)`으로
현재 시각 기준 상대값을 넣어 분기를 **결정적으로** 검증한다(`:118`, `:127`).

**권고**: 같은 방식으로 `properties.setGraceMinutes(현재 분 + 1)`을 넣어 자정 분기를 강제하고,
기대값은 삼항식이 아니라 `LocalTime.MIN` 상수로 못 박는다. CLAUDE.md가 "되감기를 넣지 말 것"이라고
명시한 규칙이라 테스트가 지켜줄 가치가 있다.

### 🟡 M-3. `MEDICATION_NOT_AUTHORIZED` 문구가 피보호자에게는 뜻이 통하지 않는다

**위치**: `ErrorCode.MEDICATION_NOT_AUTHORIZED` = "연결된 피보호자의 복약 정보만 볼 수 있습니다."
피보호자 경로 `WardMedicationService.findOwnMedication`(`:128`)이 같은 코드를 던진다.

**내용**: 피보호자가 남의 약 ID로 체크를 시도하면 위 문구가 그대로 나간다. 피보호자에게는 "연결된
피보호자"라는 말이 성립하지 않아, 왜 막혔는지 알 수 없다. Swagger 문서는 같은 403을
**"본인의 약이 아님"**이라고 적어 두어 **문서와 실제 응답이 다르다**(`WardMedicationController:88`, `:115`).

**왜 지적하는가**: 2026-07-14에 404 위장을 버리고 403 명시 안내로 바꾼 이유가 "무슨 일이 일어났는지
그대로 안내"(시니어 UX)였다. 뜻이 통하지 않는 문구는 그 결정의 효과를 되돌린다.

**권고**: `MEDICATION_NOT_OWNED`(403, "본인의 약만 체크할 수 있습니다.")를 신설해 피보호자 경로에서 던진다.
`[IDOR-ATTEMPT]` WARN 형태와 상태코드는 그대로 둔다.

### 🟢 L-1. `medication(dose_time)` 인덱스 없음 — 스케줄러가 1분마다 풀스캔

`findByDeletedAtIsNullAndDoseTimeBetween`·`…LessThanEqual`이 매 분 실행되는데 V35의 인덱스는
`(ward_id)`·`(created_by)` 부분 인덱스뿐이라 `dose_time` 조건에 쓰이지 않는다. 현재 데이터량에서는
문제가 없으나, 약이 늘면 가장 먼저 드러날 지점이다. 필요 시
`CREATE INDEX … ON medication (dose_time) WHERE deleted_at IS NULL`.

### 🟢 L-2. 미복용 Planner가 발송 창 내내 ward별 조회를 반복한다

`MedicationMissedAlertPlanner.claimMissedAlerts`에서 `alreadySent` 필터가 **ward 루프 안쪽**(`:117`)이라,
이미 보낸 뒤에도 발송 창(기본 21:00~23:00, 120분) 동안 매 분 ward마다
`getActiveGuardianIds` + `findMissedAlertEnabled`가 호출된다. 미체크 약은 그대로 남아 ward가 목록에서
빠지지 않기 때문이다. 정확성 문제는 없다(중복 발송은 막힌다). ward 단위 `alreadySent` 선필터를 루프
앞으로 올리면 사라진다.

### 🟢 L-3. PATCH 엔드포인트의 역할 게이트 테스트 없음

`MedicationControllerSecurityTest`에 `update` 케이스가 없다(#227이 나중에 추가돼 누락).
클래스 레벨 `@PreAuthorize`가 같은 클래스의 다른 메서드로 이미 검증돼 있어 실질 위험은 낮고,
IDOR 경로는 `GuardianMedicationUpdateTest`가 커버한다. 한 줄 추가로 정리 가능.

---

## 종합 판정

| 도메인 | 판정 | 비고 |
|---|---|---|
| medication (#224~#227) | ✅ **PASS** | 인가·정책 불변 규칙 전건 준수. M-2·M-3·L-1~L-3 개선 여지 |
| sos 확장 (#219·#223) | ✅ **PASS** | ACTIVE 연결 단일 인가축, 익명 이력 차단, ACK 미개입 확인 |
| notification·anomaly (#218·#220·#221·#222) | ✅ **PASS** | 알림톡 보호자 전용 구조가 타입 분리로 정확히 구현됨 |
| 공통(트랜잭션·테스트 기반) | ⚠️ **조건부** | H-1(미검증) · M-1(실 DB 테스트 부재) |

**Critical(🔴) 0건.** 인가 우회·PII 노출·필수 알림 소실은 발견되지 않았다.

가장 값어치 있는 후속은 **M-1(Testcontainers 3개)** 이다 — H-1을 실측으로 판정할 수단이 생기고,
"선점 후 발송"의 최종 방어선인 UNIQUE 제약이 그때 처음으로 실제 검증된다.

---

## 수정용 커밋 메시지 초안

```
fix: 피보호자 복약 인가 오류 문구 분리 (M-3)

피보호자가 타인의 약을 체크할 때도 보호자용 문구("연결된 피보호자의 복약
정보만 볼 수 있습니다")가 나가 뜻이 통하지 않았다. Swagger 문서와도 어긋났다.
MEDICATION_NOT_OWNED(403)를 신설해 피보호자 경로에서 던진다.
```

```
test: 복약 유예 창 자정 분기를 결정적으로 검증 (M-2)

기대값을 프로덕션과 같은 삼항식으로 재계산하고 실 시각에 의존해,
graceWindowStart가 자정을 되감도록 바뀌어도 테스트가 통과했다.
graceMinutes를 조작해 분기를 강제하고 기대값을 LocalTime.MIN으로 고정한다.
```

```
test: Testcontainers 기반 DB 통합 테스트 도입 (M-1)

테스트가 전부 목 기반이라 Flyway 마이그레이션·UNIQUE 제약·트랜잭션 전파가
한 번도 실제로 실행되지 않았다. V1~V37 적용, medication_reminder_log 중복
저장 차단, 탈퇴 리스너 커밋 여부(H-1)를 검증한다.
```
