# (2026-10-24) 보호자 이상감지 이력 삭제된 카메라 필터 기능 점검

- 대상: BE #376 (머지 `cc96cef`, vkcs CD·gosky 수동 배포) + FE #130 (머지 `865f5a4`, gosky 반영)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 범위: 새 필터·건수 + 바뀐 공용 코드(`getHistory`·`getHistorySummary`·`GuardianAnomalyHistorySummary`)의 사용처(보호자 대시보드)

## 결론

**PASS** - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 2

인가·입력 검증·건수 정합성은 코드·테스트·gosky 실측에서 모두 맞다. 남은 것은 다음 두 가지다.

- L-1: 보호자 대시보드가 쓰지 않는 삭제 건수 때문에 쿼리가 늘고, 실패 지점도 하나 생겼다.
- L-2: 피보호자를 바꿀 때 필터가 초기화되는지 지키는 테스트가 없다.

## PHASE 0. 대상

| 엔드포인트 | 역할 | 변경 |
|---|---|---|
| `GET /api/guardian/anomaly/history` | GUARDIAN | `camera=DELETED|EXISTING` (전용 `AnomalyCameraFilter`) |
| `GET /api/guardian/anomaly/history/summary` | GUARDIAN | `deletedCameraCount` 추가 |
| `GET /api/guardian/dashboard` | GUARDIAN | 무변경 - 단, 내부에서 `getHistorySummary`를 피보호자마다 부름 |

## PHASE A. 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 역할 | ✅ | 기존 컨트롤러 `@PreAuthorize` 그대로(`GuardianAnomalyControllerSecurityTest`) |
| IDOR(연결 안 된 피보호자) | ✅ PASS | `resolveVisibleWardIds`가 카메라 조회·이력 조회보다 먼저(단위 테스트 - 403이면 카메라도 조회 안 함) |
| 카메라 조회 범위 | ✅ | 지금 등록된 세션은 인가된 피보호자 범위로만(`findSessionIdsByWardIdIn`) |
| 입력 검증 | ✅ | 잘못된 `camera`(소문자·ACTIVE·ALL·임의 값) 400 - 단위 테스트 + gosky 실측 `camera=deleted` 400 |
| 노출 | ✅ | 새로 드러나는 것은 건수뿐. 세션 ID·기기 ID 미노출, 새 로그 없음 |

## PHASE B. 기능 정합성

- gosky 실측(테스트 보호자): `deletedCameraCount` 42 = `camera=DELETED` 42건(장소 모두 null) / `EXISTING` 3건(거실·침실) / 합계 45 = 전체 / `DELETED`+낙상 5건.
- 기준은 이력 `cameraLabel = null`과 같다. 사용 중지 카메라는 "있음"이다.
- 삭제 뒤 다시 등록하면 새 세션이라, 옛 이력이 되살아나지 않는다(`CameraService.register` - 같은 피보호자·같은 기기·행 존재일 때만 세션 재사용).
- 수용: 요약과 목록 조회 사이에 카메라가 삭제되면 잠깐 숫자가 어긋날 수 있다(다음 조회에서 맞춰짐).

## PHASE C. 구조·계약

- 응답 계약: 필드·파라미터만 더했다(하위 호환). Swagger에 `camera`·`deletedCameraCount` 설명 있음.
- 상황 쿼리는 `Camera`를 참조하지 않고 세션 목록을 받는다(관리자 로그 검색과 같은 방식). 빈 목록은 `NO_MATCH`로 빈 IN 절을 피한다. 카메라는 서비스 전체 상한 3대라 목록이 커지지 않는다.
- **L-1**: `GuardianDashboardService`가 피보호자마다 `getHistorySummary`를 불러 `needsReviewCount`만 쓴다. 이번 변경으로 피보호자 1명당 쿼리 2개(카메라 세션 목록 + 삭제 건수)가 더 돈다. 이 쿼리가 실패하면 대시보드 이상감지 칸이 `unavailable`이 된다(쓰지도 않는 값 때문에 생긴 새 실패 지점). 피보호자 수가 적어 비용은 작다.

## PHASE D. 테스트 (변이 시험)

| 변이 | 결과 |
|---|---|
| BE B1 DELETED/EXISTING 플래그 뒤바꿈 · B2 요약 삭제 건수 0 · B3 빈 세션 목록을 그대로 넘김 · B4 필터 없어도 카메라 조회 · B5 컨트롤러가 camera 미전달 | 5종 모두 검출 |
| FE F1 camera 미전달 · F2 0건에도 칩 표시 · F3 삭제 칩 선택 시 type에 값 섞임 | 3종 검출 |
| FE F4 피보호자를 바꿔도 필터 유지 | **놓침**(L-2) - 유형 필터 때부터 없던 테스트 |

- JPQL `NOT IN`↔`IN`은 단위 테스트로 잡을 수 없다. 실제 PostgreSQL 통합 테스트 `GuardianAnomalyHistoryQueryIntegrationTest.deletedCameraFilter`가 맡는다(vkcs 브랜치 실행 통과).
- 참고: vkcs `tools/integration-test.sh`는 실행 뒤 임시 폴더를 지워서, 테스트 이름별 결과를 나중에 볼 수 없다.

## 이슈

| ID | 단계 | 내용 | 제안 |
|---|---|---|---|
| L-1 | 🟢 | 대시보드가 쓰지 않는 삭제 건수 때문에 피보호자당 쿼리 2개 추가 + 새 실패 지점 | 대시보드는 확인 필요 건수만 세는 메서드를 쓰게 하거나, 요약 계산에서 삭제 건수를 선택으로 |
| L-2 | 🟢 | 피보호자 전환 시 필터 초기화 테스트 없음 | `AnomalyHistoryContent` 브라우저 테스트 1개 |

## 수정 프롬프트 초안 (승인 시)

BE `fix/dashboard-anomaly-summary-light` - L-1 대시보드용 확인 필요 건수 메서드 분리(삭제 건수·카메라 조회 없음, 기존 인가 그대로, 단위 테스트) / FE - L-2 피보호자 전환 시 필터·페이지 초기화 브라우저 테스트. 마이그레이션 없음.

## 후속 조치 (2026-10-24) - L-1·L-2 전부 조치

| ID | 조치 | 검증 |
|---|---|---|
| L-1 | BE #378 (머지 `ed3a801`, vkcs CD·gosky 수동 배포): `GuardianAnomalyService.getNeedsReviewCount` 신설(같은 인가, PENDING+CONFLICTED만, 카메라·삭제 건수 미조회) → 대시보드가 이것을 씀. 응답 무변경 | 단위 테스트 2개(카메라 미조회·403·연결 0명 = 0), 빌드 통과(1870, 실패 0). gosky 실측 대시보드 `anomaly 45` = 요약 `needsReviewCount 45`, `unavailable` 없음 |
| L-2 | FE #132 (머지 `bbbd863`, 테스트만): 상태를 가진 틀로 피보호자 전환 시 필터(삭제된 카메라 포함)·페이지 초기화 브라우저 테스트 | 필터 초기화·페이지 초기화를 각각 지우면 실패(2/2), browser 147 통과 |

