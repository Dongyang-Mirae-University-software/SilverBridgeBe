# 점검: 보호자 대시보드 통합 API (템플릿 B, 2026-10-08)

대상: PR #334 (`GET /api/guardian/dashboard`) + 건드린 공용 코드(`GuardianAnomalyService`·`GuardianSosService` 및 두 저장소에 **추가한** 메서드). 기존 시그니처 변경이 없어 영향 범위 점검(C)은 하지 않았다.

## 결론

High/Medium 없음. 정책 위반 없음. Low 이슈 D-1(null 의미)·D-2(테스트 공백)와 데이터 칸 실서버 미검증은 **같은 날 후속 PR 로 모두 해소**했다(아래 "후속 반영"). 남은 것은 수용한 Low(D-3~D-5)뿐.

## 실서버 확인 (gosky, 읽기 전용, 테스트 보호자 계정)

| 항목 | 결과 |
|---|---|
| 토큰 없음 | 401 |
| `wardId` 없음 | 200, 최상위 키 = `pendingActions·wards·anomalyDetection·sos·medication·unavailable` (정서·활동·예약 필드 없음) |
| 없는 `wardId` | 403 `DASHBOARD_NOT_AUTHORIZED` |
| `guardianId=OTHER` 쿼리 | 무시되고 본인 기준 200 |
| 연결 0명 | `wards: []`, 세 칸 `null`, `unavailable: []`, `pendingActions` 모두 0 |
| JSON 직렬화 | 정상(10/8 챗 응답의 Jackson 2 타입 사고와 같은 문제 없음 - 이 DTO 는 record·표준 타입만) |

- vkcs-linux 는 이 계정이 로그인 401(해당 서버에 계정 없음 추정) -> 정책대로 반복하지 않고 멈췄다. 같은 코드가 CD 로 배포돼 기동 로그에 ERROR 없음만 확인.
- 1차 확인 때는 계정에 ACTIVE 연결 피보호자가 0명이라 데이터 칸을 못 봤다 -> 아래 "실서버 재확인"에서 해소.

## 정책 대조 (rules 파일)

| 정책 | 결과 |
|---|---|
| 열람 범위 = ACTIVE 연결만, `getMyWards` 금지 | OK - 대시보드 `isActiveConnection`/`getActiveWardIds` + 하위 서비스 재인가 |
| 위반 403 + `[IDOR-ATTEMPT]` | OK (실서버 403 확인) |
| 모르는 값을 0 으로 채우지 말 것 | OK - 칸 실패 null + `unavailable`, `total`·칩 `pendingCount` 도 null |
| 복약 "체크되지 않았습니다"(단정 금지) | OK - 문구를 만들지 않고 Swagger 로 FE 에 권장 |
| 오탐률 단독 노출 금지 | 해당 없음(오탐 건수 미노출) |
| 판정 알림을 되돌리지 않음·재촉 규칙 | 영향 없음(조회 전용) |
| SOS ACK 철회 | OK - "해결됨" 필드 없음 |
| 로그 원문 금지 | OK - 칸 이름 + 예외 클래스명만 |
| 동기 AFTER_COMMIT/잠금 규칙 | 해당 없음(읽기 전용) |

## 이슈

| ID | 심각도 | 내용 | 권장 |
|---|---|---|---|
| D-1 | Low | **`null` 의 뜻이 둘이다.** 연결 0명일 때와 칸 조회 실패 때 모두 칸이 `null`이고, 구분은 `unavailable` 이 비어 있는지뿐이다. FE 가 칸 `null` 만 보고 "확인 중"으로 그리면 연결 없는 보호자에게도 '확인 중'이 보인다. 연결 0명의 0건은 실제로 센 값이라 "모르는 값"이 아니다. | 둘 중 택1: (a) 현행 유지 + FE 에 "`wards: []` 면 빈 화면, `unavailable` 에 있을 때만 확인 중" 전달(Swagger 에는 이미 적음) / (b) 연결 0명이면 세 칸을 0 값 객체(`needsReviewCount 0`, `thisMonthCount 0 · latest null`, `uncheckedCount 0`)로 내림. SOS 이력 API 가 연결 0명에 `counts 0`을 주는 것과 일치하는 (b) 를 권장하나 사용자 결정 사항 |
| D-2 | Low | **새 서비스 메서드의 단위 테스트 공백.** `getLatestNeedsReview`·`getRecent`는 대시보드 테스트에서 목으로만 호출된다. 직접 단위 테스트가 없다: 연결 0명 -> null/(0,null), 연결 없는 `wardId` -> 403(`ANOMALY_NOT_AUTHORIZED`/`SOS_NOT_AUTHORIZED`) + 저장소 미호출, 탈퇴로 `wardId` null 인 SOS 최근 1건에서 이름 맵 NPE 없음(`Collections.emptyMap` 경로). 쿼리 자체는 통합 테스트로 검증됨. | `GuardianAnomalyServiceTest`/`GuardianSosServiceTest`에 위 3개 케이스 추가 |
| D-3 | Low | 복약 미체크에 **오늘 늦게 등록한 약**(복용 시각이 등록 이전)이 포함된다. `MedicationItem` 에 등록 시각이 없다. 미복용 보호자 요약은 이를 제외하지만 대시보드는 포함. | 문서에 기록됨(수용). 거슬리면 `MedicationItem` 에 `createdAt`을 더하는 별도 작업 |
| D-4 | Info | 피보호자 수 N 에 대해 `getHistorySummary` 를 N 번 호출(+연결 확인 쿼리). 보호자당 연결 수 상한이 코드에 없다. 현실 규모(2~3명)에서는 무시 가능, 수십 명이면 대시보드 1회가 수십~백여 쿼리. | 연결 수가 커지면 summary 를 wardId 별 GROUP BY 한 번으로 묶는 저장소 메서드 추가 |
| D-5 | Info | `getRecent` 의 `Page` 반환 때문에 최근 1건을 얻는 데 COUNT 쿼리가 한 번 더 나간다(인덱스 `ward_id, created_at DESC`로 가벼움). | 수용 |

## 확인한 것(문제 없음)

- 대시보드 서비스에 `@Transactional` 이 없다(칸 실패 시 rollback-only 전파 방지). 하위 서비스가 각자 readOnly.
- 한 칸 실패 -> 나머지 칸 유지: 단위 테스트 고정. 이상감지는 건수 합계와 `latest` 가 따로 실패할 수 있어도 `anomalyOk` 로 함께 null 처리.
- KST 월 경계: 단위(`monthStart`) + 실 DB(말일 23:59:59 / 1일 00:00:00, UTC 표기 동일 순간).
- 인가 밖 피보호자 데이터 혼입 없음: 복약은 전원 조회 결과를 인가된 범위로 필터, 이상감지·SOS 는 하위 서비스가 같은 `wardIds` 로 조회(통합 테스트에서 다른 피보호자 제외 확인).
- 속도 제한: `UserRateLimitFilter` 600/분이 경로 무관 적용(신규 경로 자동 포함).

## 후속

- D-2 는 작은 테스트 PR 로 처리 권장. D-1 은 사용자 결정(FE 와 협의) 후.
- 연결된 테스트 데이터로 데이터 칸 실서버 확인 1회.

## 실서버 재확인 (gosky, 연결된 피보호자 1명, 읽기 전용)

대시보드 값을 개별 API 와 대조했다. 전부 일치.

| 항목 | 대시보드 | 개별 API |
|---|---|---|
| 이상감지 확인 필요 건수 | 15 | 15 (`/history/summary`) |
| 이상감지 최신 확인 필요 1건 | 상황 #95 (흉기, PENDING) | 이력 목록의 같은 #95 |
| SOS 이번 달(KST) | 3 | 3 (이력 목록에서 KST 월 시작 이후로 직접 센 값, 전체 16건 중) |
| SOS 최근 1건 | 같은 건 | 이력 첫 항목 |
| 복약 미체크 | 1 (점심 15:02) | 1 (`/api/guardian/medication`에서 현재 KST 시각 기준으로 직접 센 값) |
| `pendingActions.total` / 칩 `pendingCount` | 16 (= 15 + 1) / 16 | - |
| `wardId` 지정 / 없는 `wardId` | 200, 합계 동일 / 403 | - |

`unavailable` 은 비어 있었다. 최신 상황의 `cameraLabel` 이 null 인 것은 카메라가 삭제된 상황이라 이력 API 와 같은 의도된 동작.

## 후속 반영 (PR: `feature/dashboard-empty-and-tests`)

- **D-1 해소(권장안 b)**: 연결 0명이면 `anomalyDetection{needsReviewCount:0, latest:null}`·`sos{thisMonthCount:0, latest:null}`·`medication{uncheckedCount:0, mostUrgent:null}`·`pendingActions` 0·`wards: []`·`unavailable: []`. 연결 없음의 0 은 실제로 센 값이고 SOS 이력 API 의 `counts 0` 과 같은 기준이다. **이제 칸 `null` 은 조회 실패에만 쓴다.** FE 는 칸이 null 일 때만 "확인 중"으로 그리면 된다. Swagger·기능 문서·테스트 갱신.
- **D-2 해소**: `GuardianAnomalyServiceTest.LatestNeedsReview`(연결 0명·403 시 저장소 미호출·없으면 null·PENDING+CONFLICTED 1건 조회) 4건, `GuardianSosServiceTest.getRecent_*`(연결 0명·403·기간 0건이어도 최근 1건·탈퇴 익명 NPE 없음) 4건 추가.
- D-3~D-5 는 수용(변경 없음).
