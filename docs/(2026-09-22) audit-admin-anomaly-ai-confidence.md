# 기능 점검 - 관리자 이상감지 AI 신뢰도(aiConfidence)

> 2026-09-22 · 템플릿 B(기능 점검) · 대상 PR #255(`7c7a031`) + 문서 `7273e71` · 코드 수정 없음

## 종합 판정: ✅ PASS - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 4

## PHASE -1. 환경

- dev `7273e71`, 작업 트리 깨끗, `./gradlew test` 611 / 0 실패(skip 1), 마이그레이션 V53 그대로.
- 배포: vkcs(CD success)·skyserver(수동) 모두 `7c7a031`, Flyway v53 validate, health 200.

## PHASE 0. 대상

| 파일 | 변경 |
|---|---|
| `AnomalyIncidentRepository.countForAdminSummary` | GROUP BY에 `SUM(maxConfidence)` · `TypeStatusCount.getConfidenceSum()` |
| `AdminAnomalySummaryResponse` | `Accuracy` → `AiConfidence(average, real, falseAlarm, basis)` |
| `AdminAnomalyService.getSummary` | 판정별 confidence 합계 누적 → 가중평균 |
| `AdminAnomalyController` | Swagger 설명 |

- `countForAdminSummary`·`TypeStatusCount` 호출부는 **`AdminAnomalyService` 한 곳뿐**(메인 코드). 관리자 대시보드(`todayAnomaly`)는 별도 쿼리라 영향 없음.
- 메인 코드에 `accuracy` 잔존 없음.

| 엔드포인트 | ADMIN | GUARDIAN | WARD |
|---|---|---|---|
| `GET /api/admin/anomaly` | 200 | 403 | 403 |
| `GET /api/admin/anomaly/summary` | 200 | 403 | 403 |

## PHASE A. 보안·인가 - PASS

- 이중 게이트 유지: `SecurityConfig` `/api/admin/**` → `hasRole("ADMIN")` + 클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")`(두 메서드 모두 적용).
- 쓰기 매핑 없음(`AdminAnomalyControllerSecurityTest.쓰기_매핑_없음`) 유지.
- 새 응답 필드는 집계 숫자뿐 - 개인 식별 정보 추가 없음. keyword 이스케이프·IN 절 경로 무변경.
- IDOR: 해당 없음(관리자 전체 조회가 의도, 연결로 좁히지 않음 - 기존 정책).

## PHASE B. 기능 정합성

- **가중평균 PASS**: DB는 (유형, 판정)별 `SUM`·`COUNT`만 주고 서비스가 합계 ÷ 건수로 계산 - 유형이 여럿이어도 평균의 평균이 되지 않는다(`유형_합산_가중평균` 테스트).
- **null 규칙 PASS**: basis 0 → average null, 위험 0건 → real null, 오탐 0건 → falseAlarm null. 0으로 채우지 않는다.
- **최종 시안 매핑 PASS**: 하위 칸 = `review.real / basis`·`review.falseAlarm / basis` → basis = real + falseAlarm이라 합이 항상 100%. 큰 숫자 = average(판정 난 건만)로 하위 칸과 분모가 같다.
- **유형 필터 PASS**: aiConfidence·review는 고른 유형으로, byType만 유형 무시(D-7 유지).

### 🟡 M-1 동수가 있으면 응답률 바의 문구와 숫자가 어긋난다 (FE 문구 / Notion)

Notion 매핑은 "N건 중 판정 완료 M건 · 미판정 K건"을 M = `real + falseAlarm`, K = `pending`으로 안내했다. 그러나 응답률은 `(total - pending) / total`이라 동수(`conflicted`)를 **응답한 것**으로 센다.

- 예: total 52 · pending 12 · real+falseAlarm 38 · conflicted 2 → 바는 **77%**, 문구는 "52건 중 판정 완료 **38**건 · 미판정 12건"(= 73%). M + K = 50 ≠ 52.
- 동수 칩을 없앴기 때문에(D-3) 사라진 2건을 화면 어디서도 설명할 수 없다.
- **권고**: 문구의 M을 `total - pending`으로 바꾼다(= 응답률의 분자). 동수도 "보호자가 응답한 건"이므로 뜻이 맞고 M + K = N이 된다. 백엔드 변경 불필요, Notion 매핑·코드 예시만 수정.

### 🟢 L-4 "평균 AI 신뢰도"는 상황 **최고값**의 평균이다 (정보)

`max_confidence`는 상황에 묶인 감지 중 최댓값이라, 오래 이어진 상황일수록 값이 올라간다(평균보다 위쪽으로 치우침). 라벨 오해 여지는 Notion에 "얼마나 확신했는가, 맞았는가가 아니다"로 이미 안내. 조치 불요.

## PHASE C. 구조·계약

- **하위호환**: `accuracy` 제거는 breaking이지만 9-21 배포 필드로 FE 연동 전(로컬 FE 리포 사용처 0, Notion에 제거 고지). PASS.
- **Swagger**: `aiConfidence` 설명이 필드 의미(confidence 평균)와 일치. 최종 카드의 하위 칸 계산법은 Notion에 있다 - API 문서로는 충분.
- **JPQL**: `SUM(double)` → Hibernate `Double`, 프로젝션 `double getConfidenceSum()`. 그룹은 1행 이상이고 `max_confidence`가 NOT NULL이라 null이 오지 않는다. vkcs 통합 테스트(`집계_GROUP_BY`)로 실 PostgreSQL 확인. PASS.
- **Notion ↔ 응답**: "관리자 API - 이상감지 로그" 페이지의 필드·타입·예시가 실제 응답과 1:1 일치(예시 숫자 0.88 = (2×0.91 + 5×0.868) / 7, 응답률 7/9 확인).

### 🟢 L-2 `aiConfidence.real`·`falseAlarm`은 최종 카드에서 쓰지 않는다

중간 시안(83%/79%)용으로 만든 칸별 confidence 평균이다. 응답에 남아 있어도 해는 없고 Notion에 "미사용"으로 표시했다. FE 연동이 끝난 뒤 계속 안 쓰이면 제거(또는 AI 품질 분석용으로 유지)를 판단한다.

### 🟢 L-3 confidence 범위 검증이 없다 (기존 동작)

`AnomalySignalParser`가 AI의 `confidence`를 `asDouble(0.0)`으로 받을 뿐 0~1 검사를 하지 않는다. AI가 형식을 바꿔 87(퍼센트)을 보내면 평균이 "8700%"가 된다. 이번 변경 전부터 목록의 `maxConfidence`에 같은 전제가 있었고 AI 계약이 0~1이라 **수용**. 형식이 바뀌면 파서에서 막는다.

## PHASE D. 테스트

| 케이스 | 테스트 |
|---|---|
| 시안 숫자 재현·미판정/동수 미혼입 | `AdminAnomalyServiceTest.Summary.비율_계산` |
| 분모 0 null | `분모_0이면_null` |
| 한쪽 판정만 | `한쪽_판정만` |
| 유형 합산 가중평균 | `유형_합산_가중평균` |
| 유형 필터 적용 | `유형_탭` |
| SUM 프로젝션(실 DB) | `AdminAnomalyQueryIntegrationTest.집계_GROUP_BY` |

### 🟢 L-1 `/summary`의 역할별 보안 테스트가 없다

`AdminAnomalyControllerSecurityTest`는 목록(`getIncidents`)만 GUARDIAN·WARD 403을 확인한다. `/summary`도 같은 클래스 레벨 `@PreAuthorize`라 실제 구멍은 아니지만, 메서드 단위로 권한을 바꾸는 실수를 잡지 못한다. 다음 이상감지 관리자 변경 때 한 줄 추가를 권고.

## 조치 제안

| # | 조치 | 위치 | 코드 |
|---|---|---|---|
| M-1 | 응답률 바 문구 M = `total - pending` | Notion 이상감지 로그 페이지 | 없음 |
| L-1 | `/summary` GUARDIAN·WARD 403 테스트 | `AdminAnomalyControllerSecurityTest` | 테스트만 |
| L-2 | 미사용 필드 유지/제거 판단 | FE 연동 후 | 보류 |
| L-3·L-4 | 수용 | - | - |

L-1 수정 시 커밋 초안: `test: 관리자 이상감지 집계 API 역할별 403 테스트 추가`
