# 점검 이슈 반영 — PR #218~#227 점검 후속

> 대상 점검: `docs/(2026-08-05) audit-medication-sos-notification.md` (이슈 6건)
> 작업일: 2026-08-06 · 기준 브랜치: `dev` @ `f604953`

## 처리 결과 요약

| 이슈 | 등급 | 처리 | 비고 |
|---|---|---|---|
| M-3 피보호자 인가 문구 | 🟡 | ✅ 수정 | `MEDICATION_NOT_OWNED` 신설 |
| M-2 자정 분기 테스트 무력 | 🟡 | ✅ 수정 | 경계 규칙을 시각과 분리, 뮤테이션으로 실효성 검증 |
| L-1 `dose_time` 인덱스 없음 | 🟢 | ✅ 수정 | V38 |
| L-2 Planner 조회 반복 | 🟢 | ✅ 수정(부분) | 동작 불변 범위만 |
| L-3 PATCH 역할 테스트 누락 | 🟢 | ❌ **오탐 정정** | 이미 커버돼 있었음 |
| M-1 실 DB 통합 테스트 부재 | 🟡 | ⏸ 보류 | Docker 미가용 |
| H-1 AFTER_COMMIT 트랜잭션 전파 | 🟠 | ⏸ 보류 | M-1이 전제(실측 수단) |

검증: `./gradlew build` **407 tests / 0 failures** (기존 405 + 신규 2).

---

## ✅ M-3. 피보호자 인가 오류 문구 분리

**문제**: `WardMedicationService.findOwnMedication`이 보호자용 `MEDICATION_NOT_AUTHORIZED`
("연결된 피보호자의 복약 정보만 볼 수 있습니다")를 던졌다. 피보호자에게는 "연결된 피보호자"라는 말이
성립하지 않아 왜 막혔는지 알 수 없고, Swagger가 같은 403을 "본인의 약이 아님"이라 적어 둔 것과도 어긋났다.

**수정**:
- `ErrorCode.MEDICATION_NOT_OWNED(403, "본인의 약만 체크할 수 있습니다.")` 신설.
- 피보호자 경로에서만 이 코드를 던진다. 보호자 경로(`MEDICATION_NOT_AUTHORIZED`)는 그대로.
- 상태코드(403)와 `[IDOR-ATTEMPT]` WARN 형태는 **변경 없음** — 2026-07-14 정책 그대로다.
  바뀐 것은 문구뿐이며, 응답에 소유자·내용 정보를 싣지 않는다는 불변 규칙도 유지된다.

**회귀 방어**: `WardMedicationServiceTest`가 문구를 고정한다 —
`isEqualTo("본인의 약만 체크할 수 있습니다.")` + `doesNotContain("연결된 피보호자")`.

## ✅ M-2. 자정 유예 창(불변 규칙 ⑥) 테스트가 회귀를 잡도록 수정

**문제**: 기존 테스트는 ① 기대값을 프로덕션과 **같은 삼항식으로 재계산**하고 ② **실 시각에 의존**해,
자정 분기가 "CI가 00:00~00:30 KST에 돌 때만" 실행됐다. 즉 되감기 회귀를 통과시켰다.

**수정**:
- `graceWindowStart`를 `private LocalTime graceWindowStart(LocalTime now)` →
  **`static LocalTime graceWindowStart(LocalTime now, long graceMinutes)`** 로 변경.
  경계 규칙을 현재 시각과 분리해 경계값을 리터럴로 직접 검증할 수 있게 했다. 호출부는
  `properties.getGraceMinutes()`를 넘기며 **동작은 완전히 동일**하다.
- 신규 테스트 2개 — 실 시각과 무관하게 결정적으로 돈다.
  - `[규칙⑥] 자정 절단`: 00:00 / 00:10 / **00:30(경계, 유예와 동값)** → 전부 `LocalTime.MIN`
  - `[규칙⑥] 일반`: 00:31→00:01 / 12:00→11:30 / 23:59→23:29
- 기존 테스트는 **배선 검증**으로 축소 — 상한이 현재 시각인지, 하한이 그 상한으로 계산한 값인지만 본다
  (삼항식 복제 제거).

**실효성 검증(뮤테이션)**: 자정 절단을 제거한 변형(`return now.minusMinutes(graceMinutes);`)을 넣고
테스트를 돌려 **`[규칙⑥] 자정 절단`만 정확히 FAILED**(13건 중 1건)를 확인한 뒤 원복했다.
수정 전 테스트였다면 이 변형은 통과했을 것이다.

## ✅ L-1. 복약 스케줄러 조회용 인덱스 (V38)

스케줄러가 1분마다 `findByDeletedAtIsNullAndDoseTimeBetween`(복용 알림)과
`findByDeletedAtIsNullAndDoseTimeLessThanEqual`(미복용 요약, 21:00~23:00 매 분)을 던지는데
V35의 인덱스는 `(ward_id)`·`(created_by)`뿐이라 두 쿼리 모두 `medication` 풀스캔이었다.

```sql
CREATE INDEX idx_medication_dose_time ON medication (dose_time) WHERE deleted_at IS NULL;
```

부분 인덱스인 이유는 두 쿼리가 `deleted_at IS NULL`을 함께 걸고, soft delete된 약은 영원히 대상이
아니기 때문이다(V35의 두 인덱스와 같은 방식). **기존 마이그레이션 수정 없음**, V38 신규.

## ✅ L-2. 미복용 Planner의 반복 조회 축소 (동작 불변)

**문제**: `alreadySent` 필터가 ward 루프 **안쪽**이라, 미체크 약이 남아 있는 피보호자는 발송 창
(기본 120분) 내내 목록에 있어 이미 보낸 뒤에도 매 분 `findMissedAlertEnabled`가 헛돌았다.

**수정**: 보호자 목록을 받은 직후 `alreadySent`로 선필터해, 남은 보호자가 없으면 설정 조회 없이 건너뛴다.

**의도적으로 남긴 것**: `getActiveGuardianIds`는 매 분 그대로 호출한다. "로그가 있으면 이 피보호자는
끝났다"고 단정해 ward 단위로 건너뛰면 **21시 이후 새로 연결된 보호자가 그날 요약을 못 받는다**.
조회 1건보다 정확성이 우선이라 이 부분은 최적화하지 않았다. 기존 테스트(하루 한 번 / 연결 없으면
기록 없음 / 설정 OFF 제외 / 기본값 ON)가 그대로 통과한다.

## ❌ L-3. 점검 오탐 — 정정

점검 보고서는 `MedicationControllerSecurityTest`에 PATCH 역할 게이트 케이스가 없다고 적었으나
**사실이 아니다**. 이미 커버돼 있다:

- `MedicationControllerSecurityTest:76, 81-82` — GUARDIAN이 `update` 호출 시 예외 없음
- `MedicationControllerSecurityTest:96-99` — WARD가 `update` 호출 시 `AccessDeniedException`

점검 당시 grep 패턴이 HTTP 동사 문자열만 훑어 `guardianController.update(...)` 호출을 잡지 못한 것이
원인이다. **코드 변경 없음**, 대장의 잔여 이슈에서 제거한다.

---

## ⏸ 보류: M-1 · H-1

**사유**: 작업 환경에 Docker가 없다. `/usr/bin/docker`가 Docker Desktop의 WSL 마운트
(`/mnt/wsl/docker-desktop/cli-tools/usr/bin/docker`)를 가리키는데 대상이 존재하지 않는다
(= Docker Desktop 미실행). 승인된 작업 프롬프트 PHASE 0에 "docker 접근 불가면 M-1 스킵, H-1 보류"로
미리 정해 둔 분기를 따랐다.

**왜 그냥 작성만 하지 않았는가**: Testcontainers 테스트를 실행해 보지 않고 커밋하면
① H-1 실측이라는 본래 목적을 달성하지 못하고 ② Docker 없는 환경에서 `./gradlew test`가 깨져
빌드 회귀를 만든다. 검증하지 못한 테스트를 넣는 것은 이득보다 손해다.

**재개 조건과 순서**:

```
1) Docker Desktop 실행 후 확인:  docker info
2) build.gradle 에 testImplementation 'org.testcontainers:postgresql' 추가
3) 통합 테스트 3개
   - Flyway V1~V38 순차 적용
   - uq_medication_reminder 중복 저장 차단 (선점 후 발송의 최종 방어선 — 최초 실검증)
   - 탈퇴 리스너 후 medication 행 상태  ← H-1 실측
4) H-1 판정
   - 쓰기 유실 확인 → MedicationWithdrawalService + ConnectionService
     .tearDownConnectionsOnWithdrawal 에 @Transactional(REQUIRES_NEW)
     ⚠️ connection 쪽은 2026-05-26부터 운영 중인 경로라 별도 승인 필요
   - 유실 없음 → 코드 무변경, 대장의 ⚠️ 해제
```

**H-1은 여전히 미검증 추정**이며, 현재는 purge FK CASCADE가 같은 결과를 만들어 가려져 있다.
`medication.created_by`를 `SET NULL`로 바꾸는 변경이 생기면 그 전에 반드시 판정할 것.

## V38 적용 안내

인덱스 1개 추가라 비가역 DDL이 아니고 데이터 변경도 없다. 다만 `CREATE INDEX`는 기본적으로
테이블 쓰기를 잠그므로, 운영 데이터가 커진 뒤 적용한다면 `CONCURRENTLY`를 검토할 것
(현재 `medication` 행 수가 사실상 0이라 이번에는 불필요).
