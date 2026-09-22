# 관리자 이상감지 로그 - AI 신뢰도 카드를 confidence 평균으로 교체

> 2026-09-22 · 기능 · 마이그레이션 없음 · ⚠️ `/summary` 응답 필드 교체(`accuracy` → `aiConfidence`)

## 왜

관리자 콘솔 시안(이상감지 로그, 2026-09-22 제공)을 v2 API(PR #253)와 대조한 결과, **AI 신뢰도 카드 하나만 정의가 달랐다.**

- 시안: 평균 AI 신뢰도 **81%**, 하위 칸 위험 판정 **83%** / 오탐 **79%** - 두 칸 합이 100%가 아니다.
- v2: `accuracy.rate` = 위험 ÷ (위험 + 오탐) - 하위 두 칸 합이 항상 100%. 시안 건수(위험 16·오탐 24)로는 40%.
- 시안 숫자는 **판정 난 상황들의 AI confidence 평균**과 정확히 맞는다: (16 × 0.83 + 24 × 0.79) ÷ 40 = 0.806 ≈ 81%.

## 시안 × API 대조 (PHASE 0)

| 시안 요소 | API | 판정 |
|---|---|---|
| 기간 탭 · 유형 탭+건수 · 이름·장소 검색 | `period` · `summary.byType` · `keyword` | ✅ (v2) |
| 응답률 · "N건 중 판정 완료 M건·미판정 K건" | `responseRate` · `total` · `review` | ✅ |
| 판정 칩 · 표 컬럼(시각·유형·사용자·장소·감지 지속·판정) · 페이징 | `status` · 목록 항목 · `PageResponse` | ✅ |
| 유형별·판정별 현황 막대 | `byType` · `review` | ✅ |
| **평균 AI 신뢰도 / 위험 판정 / 오탐** | `accuracy`(비율) | ⚠️ → **이번 변경** |

## 결정 (2026-09-22, 사용자)

| # | 결정 |
|---|---|
| D-1 | AI 신뢰도 = **판정 난 상황(위험+오탐)의 `max_confidence` 평균**. 기존 `accuracy`(위험 비율)는 **제거·교체** |
| D-2 | 분모는 **판정 난 건만** - 미판정·동수 제외. 그래야 평균이 위험 평균·오탐 평균의 가중평균이 되어 카드가 자기모순이 없다 |
| D-3 | **동수(CONFLICTED)는 관리자 화면에 따로 표시하지 않는다**. 백엔드는 그대로(판정 4값·재확인 안내 유지), FE는 칩을 시안대로 4개(전체·위험·오탐·미판정)만 둔다 |

## API 변경 - `GET /api/admin/anomaly/summary`

```json
"aiConfidence": { "average": 0.806, "real": 0.83, "falseAlarm": 0.79, "basis": 40 }
```

| 필드 | 뜻 | null 조건 |
|---|---|---|
| `average` | 위험 + 오탐 상황의 confidence 평균 (0.0~1.0, 소수 넷째 자리) | basis 0 |
| `real` | 위험 판정 상황의 평균 | 위험 0건 |
| `falseAlarm` | 오탐 판정 상황의 평균 | 오탐 0건 |
| `basis` | 위험 + 오탐 건수 | - |

- **제거**: `accuracy { rate, falseAlarmRate, basis }`. v2는 2026-09-21 배포라 FE 연동 전이었다(로컬 FE 리포에 사용처 없음).
- 유형 필터를 따른다(D-7 그대로 - 탭 건수만 유형 무시). 기간·검색어도 같다.
- 뜻 주의: "AI가 얼마나 **확신**했는가"이지 "맞았는가"가 아니다. 오탐 평균이 높으면 확신했는데 틀린 경보가 많다는 뜻이다.
- confidence는 상황의 **최고값**(`max_confidence`, 묶인 감지 중 최댓값)이다.

## 구현

- `AnomalyIncidentRepository.countForAdminSummary`: 같은 `GROUP BY`에 `SUM(i.maxConfidence) AS confidenceSum` 추가, 프로젝션 `getConfidenceSum()`.
  평균이 아니라 **합계**로 받는 이유: 서비스가 여러 유형을 합칠 때 평균의 평균이 되지 않게(가중평균 = 합계 ÷ 건수). 원본 행을 읽지 않는 방식 유지.
- `AdminAnomalySummaryResponse`: `Accuracy` → `AiConfidence`.
- `AdminAnomalyService.getSummary`: 판정별 confidence 합계 누적 → `average(sum, count)`(건수 0이면 null).
- `AdminAnomalyController`: Swagger 설명 교체.

## FE 요청

- AI 신뢰도 카드: 메인 = `aiConfidence.average`, 하위 = `real`(위험 판정)·`falseAlarm`(오탐). null이면 "-"(0% 아님). `basis`(판정 N건 기준)를 함께 보여 주면 좋다.
- 판정 칩은 시안대로 4개. 동수는 따로 그리지 않는다. 표의 판정 배지가 드물게 `CONFLICTED`로 오면 "확인 중" 같은 중립 표기로.
- "판정 완료 N건"은 `review.real + review.falseAlarm`으로 계산한다(칩 숫자와 맞도록).

## 검증

- `./gradlew test` **611 tests / 0 failures / 0 errors** (skipped 1). 추가·수정: 시안 숫자 재현(0.806·0.83·0.79, 미판정·동수 미혼입) / 분모 0 null / 한쪽 판정만 있으면 그쪽만 값 / 유형 합산은 가중평균(평균의 평균 아님) / 유형 필터 적용.
- 통합 테스트 `AdminAnomalyQueryIntegrationTest.집계_GROUP_BY`에 SUM 프로젝션 단언 추가 - **vkcs `tools/integration-test.sh` 통과**(2026-09-22, `781b48a`, 41초, 캐시 아닌 실제 실행).
- 운영 DB `anomaly_incident` 0행(2026-09-22 확인) - 실데이터 검증은 AI 경보가 쌓인 뒤.
