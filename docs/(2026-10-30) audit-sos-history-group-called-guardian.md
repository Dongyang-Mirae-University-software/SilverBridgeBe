# 기능 점검 - SOS 이력 연타 묶음(#401)·전화받은 보호자(#402) (2026-10-30)

템플릿 B. 대상 커밋 dev `054994a`(#402 머지, #401 `e6f3aa1` 포함). 점검만 했고 코드는 바꾸지 않았다.
방법: 메인 직접 점검 + 독립 검토(Sonnet 에이전트, 결론을 주지 않고 코드만 전달) → 에이전트 지적을 코드로 재확인 + gosky 실서버 읽기 전용 확인.

## 종합 판정

**조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 6

## 엔드포인트 × 역할

| 엔드포인트 | 허용 | 인가 | IDOR |
|---|---|---|---|
| `GET /api/guardian/sos/history` (`group` 포함) | GUARDIAN | `getActiveWardIds`/`isActiveConnection`, 위반 403 `SOS_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` (묶음 모드도 같은 `resolveVisibleWardIds`) | PASS |
| `POST /api/ward/sos` (`calledGuardianId`) | WARD | `wardId`는 토큰, `calledGuardianId`는 `isActiveConnection(보호자, 피보호자)` 통과 값만 저장 - 남의 보호자 ID 저장 불가 | PASS |

## 실서버 확인 (gosky, 읽기 전용)

- 비로그인 `GET .../sos/history?group=true` 401, `POST /api/ward/sos` 401.
- `/v3/api-docs`: 이력 파라미터 `group`, 요청 `calledGuardianId`, 응답 `pressCount`·`firstTriggeredAt`·`lastTriggeredAt`·`calledGuardianId`·`calledGuardianName` 반영.
- DB: V64 컬럼, `fk_sos_event_called_guardian ... ON DELETE SET NULL`, 부분 인덱스 확인. `sos_event` 179행(피보호자 5명, 최대 123행).
- 묶음 쿼리 `EXPLAIN ANALYZE`(최다 피보호자 123행): **0.54ms**, 123 누름 → 66 묶음. 같은 피보호자 동시각 행 0건.
- 로그인한 실제 호출 비교는 하지 못했다(보호자 테스트 계정 로그인 실패, 반복하지 않음).

## 이슈

### 🟡 M-1 다른 보호자의 사용자 ID가 응답에 나간다

- 근거: `SosHistoryItem.calledGuardianId`, `GuardianSosService.resolveCalledGuardianNames`.
- 같은 피보호자의 ACTIVE 보호자끼리 서로의 **사용자 ID**를 보게 된다. 보호자가 다른 보호자의 정보를 보는 기존 경로는 없었다(`getActiveGuardians`는 피보호자 화면 전용).
- 이름 표시는 Q4에서 승인한 결정이지만, 칩에는 이름만 필요하다. 사용자 ID는 연결 요청 대상 지정 등에 쓰이는 값이라 필요 없이 넓히지 않는 편이 맞다.
- 수정안: 응답에서 `calledGuardianId`를 빼고 `calledGuardianName`만 둔다(저장은 그대로). FE 전달 페이지도 함께 고친다. FE가 아직 붙이지 않아 지금 빼는 비용이 가장 작다.

### 🟢 L-1 이력 조회 `[IDOR-ATTEMPT]` 로그에 `wardId` 원문

- `GuardianSosService.resolveVisibleWardIds` - 쿼리 파라미터를 형식 검사 없이 로그에 남긴다(줄바꿈·과대 입력). 기존 코드이며 묶음 경로도 같은 메서드를 지난다.
- 수정안: 복약 `MedicationWardIds`·챗 대리 상담과 같이 형식(영숫자 1~6자)이 틀리면 원문 대신 길이만 남긴다.

### 🟢 L-2 묶음 모드는 요청당 윈도 쿼리 3회 + 보관 정리 없음

- `countPressGroups`·`findPressGroups`·`countPressGroupsByTriggerType`가 매번 인가 범위 전체를 정렬한다. `sos_event`에는 보관 기간 정리가 없다.
- 실측 0.5ms(123행)로 지금은 문제가 아니다. 필터가 없을 때는 total = Σcounts라 합칠 수 있다. 행이 수만 건이 되면 다시 본다(기능 문서에 이미 적음).

### 🟢 L-3 이력 Swagger `[응답]` 절에 `calledGuardianId`·`calledGuardianName` 설명 없음

- `GuardianSosController` 설명은 `group` 절에 묶음 칸만 적었다. 스키마(@Schema)에는 있다. `[응답]` 절에 한 줄 추가.

### 🟢 L-4 묶음 매핑의 null 방어 없음

- `getGroupedHistory`가 `events.get(id)`를 그대로 쓴다. 지금은 `sos_event` 삭제 경로가 없어(탈퇴는 SET NULL) 발생하지 않지만, 보관 정리를 만들면 두 쿼리 사이 삭제로 500이 날 수 있다. 그때 함께 방어한다.

### 🟢 L-5 연결을 끊은 보호자에게 건 전화 묶음은 칩 없이 따로 보인다

- 묶음 키에는 `called_guardian_id`가 있고 이름은 지금 연결된 보호자만 내리므로, 이웃 줄과 구분 표시 없이 갈라져 보일 수 있다. 보호자 탈퇴(SET NULL) 뒤에는 미기록 줄과 합쳐진다. 표시 전용이라 수용.

### 🟢 L-6 테스트 빈틈

- 같은 `created_at` 연타의 묶음(지금은 `id` 정렬이 보장)과, 전화받은 보호자가 섞인 상태의 total = Σcounts 일관성을 고정하는 통합 테스트가 없다.

## 독립 검토 지적 중 받아들이지 않은 것

- "컨트롤러 호출 순서 테스트 없음" - `WardSosControllerHttpTest`가 `InOrder`로 확인 → 저장을 고정한다.
- "`SosTriggeredEvent` 구조 가드 없음" - `SosServiceTest`가 `new SosTriggeredEvent(WARD_ID, 42L, WARD_NAME)` 정확 일치를 검증해 필드가 늘면 컴파일이 깨진다(약한 가드지만 존재).
- "대시보드 최신 1건에 이름 없음" - Q4 결정(대시보드 불변).

## 확인된 것 (PASS)

- SOS 요청은 어떤 `calledGuardianId`에도 201 - 확인기는 트랜잭션 밖(`open-in-view: false`, 컨트롤러·확인기 `@Transactional` 없음), 예외는 null로 삼킨다. 로그는 wardId·사유 코드·길이·예외 클래스명만.
- 알림 라우팅 무관 - 이벤트에 값 없음, 전 ACTIVE 보호자 발송 그대로.
- 묶음 SQL: 60초 경계(`>`), `created_at, id` 정렬, NULL을 `''`로 한 파티션, 경로 필터 시 묶음 동일, total·목록 같은 CTE, 페이지 보정 기존과 같음, 바인딩 정상. KST 자정을 넘어 연쇄되는 것은 "연쇄" 정의대로(의도).
- 응답 하위호환 - 새 칸 NON_NULL(HTTP 직렬화 테스트로 고정), `group` 생략 시 기존 응답, 대시보드 불변.
- N+1 없음 - 피보호자별 ACTIVE 보호자 1회, 이름 일괄 조회.

## 수정 커밋 초안 (진행 시)

- `fix: SOS 이력 응답에서 전화받은 보호자 사용자 ID 제외` (M-1)
- `fix: SOS 이력 IDOR 로그에 wardId 원문 대신 형식 검사·길이만` (L-1)
- `docs: SOS 이력 Swagger 응답 설명에 전화받은 보호자 칸 추가` (L-3)
- `test: SOS 묶음 동시각·보호자 혼합 total 일관성 통합 테스트` (L-6)

## 반영 결과 (2026-10-30, `fix/sos-history-audit-followup`)

| 이슈 | 조치 |
|---|---|
| M-1 | 이력 응답에서 `calledGuardianId` 제거, `calledGuardianName`만(`SosHistoryItem.withCalledGuardianName`). 저장·묶음 기준은 그대로. Notion FE 페이지 수정 |
| L-1 | `resolveVisibleWardIds`가 `wardId` 형식(영숫자 1~6자)을 먼저 확인 - 틀리면 DB 조회 없이 403, 로그는 길이만 |
| L-2 | 경로 필터가 없으면 전체 묶음 수 = 경로별 묶음 수의 합으로 계산 - 요청당 윈도 쿼리 3회 → 2회(필터가 있을 때만 3회) |
| L-3 | 이력 Swagger `[응답]` 절에 `calledGuardianName`·묶음 칸 설명 추가 |
| L-4 | 묶음 매핑에서 사라진 최신 건은 칸을 건너뛰고, 첫 건만 사라지면 최신 건으로 대신 |
| L-5 | 수용(표시 전용) - 코드 변경 없음 |
| L-6 | 통합 테스트 2건: 같은 시각 연타 한 묶음·최신 건 id, 보호자 혼합 시 전체 = 경로별 합·필터 건수 일치 |

테스트: 로컬 `./gradlew build` 단위 2115건 실패 0 / vkcs `integration-test.sh fix/sos-history-audit-followup` @ `cd7f3922` 통과 - 전체 115건 실패 0(`SosCalledGuardianIntegrationTest` 5건 · `SosHistoryPressGroupIntegrationTest` 8건).
