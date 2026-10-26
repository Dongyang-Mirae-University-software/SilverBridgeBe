# 영향 범위 점검 - 이상감지 삭제된 카메라를 기본 집계·재촉에서 제외 (2026-10-25)

> 템플릿 C(영향 범위). 대상: BE #379(`248817b`)·FE #142(`9e5e3ff`). 점검만 했다(코드 수정 없음, 정책 문서 1줄만 보강).

## PHASE -1. 환경

- 2026-10-25, BE dev `248817b` 이후(문서 커밋만 추가), FE develop `9e5e3ff`
- BE `./gradlew build` 통과(#379 작업 중), FE `npm test` 313·`test:browser` 207·build 통과(#142 작업 중)

## PHASE 0. 변경점과 불변식

- **변경**: 보호자 요약(`deletedCameraCount` 외 값)·보호자 대시보드 "확인이 필요한 일"(건수·피보호자 칩·최신 1건)·재촉 3종(건별·하루 요약·동수 안내) 후보가 **지금 등록된 카메라(카메라 행 있음, 사용 중지 포함)** 의 상황만 본다. FE 이력 전체·유형 칩은 `camera=EXISTING`.
- **불변식**: "보호자에게 *확인이 필요한 상황*으로 세거나 알리는 것은 카메라 행이 있는 세션의 상황뿐이다. 삭제된 카메라 상황의 이력·판정은 남고 판정 API는 그대로 동작한다."

## PHASE A·B. 사용처와 판정

| # | 도메인 | 위치 | 불변식 | 근거 |
|---|---|---|---|---|
| 1 | anomaly | `GuardianAnomalyService.getHistorySummary` | PASS | `countForGuardianSummary(wardIds, liveSessionIds)`, 같은 목록으로 `countDeletedCameraForGuardian` - 카메라 조회 1회, 합 = 전체(gosky 3 + 42 = 45) |
| 2 | anomaly | `getHistory` (camera 생략) | PASS(의도적 미적용) | 하위 호환. FE가 `EXISTING`을 보낸다(#142) |
| 3 | dashboard | `GuardianDashboardService` → `getNeedsReviewCount`(피보호자별)·`getLatestNeedsReview` | PASS | 둘 다 `liveSessionIds(wardIds)`. gosky 대시보드 2 = 요약 `needsReviewCount` 2 |
| 4 | anomaly | `AnomalyReviewReminderPlanner.claimReminders`·`claimSummaries`·`claimConflicts` | PASS | 세 후보 조회 직후 `retainRegisteredCameras`. 선점·UNIQUE·야간·설정 존중은 그 뒤 그대로 |
| 5 | anomaly | `submitFeedback`·`skipConflictNoticeFor` | PASS(카메라 무관) | 카메라를 조회하지 않음(`verifyNoInteractions(cameraService)` 고정). 삭제 카메라 상황이 동수가 되면 다른 응답자 안내는 #4에서 빠진다 - 기본 화면에 없는 상황이라 의도와 같다 |
| 6 | anomaly | `AnomalyIncidentService`(상황 생성·승계) | 해당 없음 | 감지는 등록 카메라(`findOwnerBySessionId`)에서만 생기므로 새 상황은 항상 등록 카메라 |
| 7 | camera | 등록 `CameraService.register` | PASS | `(wardId, deviceId)` 행이 없으면 새 세션 발급, `uq_cameras_session` - 삭제 뒤 재등록해도 옛 세션은 되살아나지 않는다(옛 상황이 "등록 카메라"로 돌아오지 않음) |
| 8 | admin·camera | 역할 변경 `deleteAllByWard` | PASS | 그 피보호자 상황이 전부 "삭제 카메라"가 되지만 연결도 함께 정리돼 보호자 화면·재촉 대상이 원래 없다 |
| 9 | schema | `anomaly_incident.session_id` | PASS | `NOT NULL`(V44) - `IN`/`NOT IN`이 빠짐없이 둘로 나뉜다(NULL이면 양쪽에서 빠질 수 있었다) |
| 10 | anomaly | 판정 기준 일치 | PASS | 보호자 서비스는 피보호자 범위 목록(`findSessionIdsByWardIds`), 플래너는 세션 조회(`findLabelsBySessionIds`). 세션은 피보호자를 옮기지 않아(카메라 `ward_id` 변경 경로 없음, 역할 변경은 삭제) 같은 결과 |
| 11 | anomaly | 클립 열람(보호자·피보호자) | 해당 없음 | 카메라 삭제 시 클립도 삭제(`CameraDeletedEvent`) |
| 12 | admin | 이상감지 로그·요약·관리자 대시보드 | PASS(의도적 미적용) | 오탐률 통계는 전체 기록 기준 유지 - 사용자 결정 |
| 13 | FE | 이력 화면 칩 | PASS | 전체·유형 `EXISTING`, 삭제 칩 `DELETED`. gosky 반영·`/guardian/detection` 200 |
| 14 | FE | 대시보드·푸시 라우팅 | PASS | 대시보드는 BE 값 그대로. 재촉 푸시 3종은 incidentId 없이 `/guardian/detection`으로만 이동 |
| 15 | ward | 피보호자 이상감지 API | 해당 없음 | 피보호자 쪽은 클립 API뿐(이력·요약 없음) |

## PHASE C. 테스트로 고정되는가 - 변이 시험

임시 worktree에서 한 곳씩 조건을 빼고 관련 단위 테스트 3클래스를 돌렸다(원본은 통과 확인).

| 변이 | 결과 |
|---|---|
| 건별 재촉 후보 필터 제거 | 검출 |
| 하루 요약 후보 필터 제거 | 검출 |
| 동수 안내 후보 필터 제거 | 검출 |
| 요약 집계에 등록 카메라 대신 빈 목록 | 검출 |
| 대시보드 건수에 빈 목록 | 검출 |
| 대시보드 최신 1건에 빈 목록 | 검출 |
| FE 칩 `EXISTING` → 생략 | 검출(브라우저 테스트 3건 실패, #142 작업 중) |

실 DB 쿼리는 vkcs 통합 테스트(`summaryExcludesDeletedCamera`·`deletedCameraExcluded`, `4f255eef`)가 고정한다.

## 이슈

🔴 0 · 🟠 0 · 🟡 0 · 🟢 4

- **L-1 Swagger 스키마 문구**: `GuardianDashboardResponse`의 `needsReviewCount`·`pendingActions` 스키마 설명에 "지금 등록된 카메라 기준"이 없다(컨트롤러 설명에는 있음). 코드 변경이라 별도 PR 필요.
- **L-2 정책 문서**: `.claude/rules/domain-security-policy.md` "이상감지 판정" 절 규칙 ⑤(재촉 제외)에 삭제 카메라가 없고 "보호자 이력 유형 필터·요약" 절에만 있었다 → **조치**(이 커밋, 규칙 ⑤에 한 줄).
- **L-3 FE 빈 목록 문구**: 등록 카메라 상황 0건·삭제 카메라 N건이면 "전체" 탭이 "감지 기록이 없어요"라고 쓴다. "삭제된 카메라 N" 칩이 함께 보여 오해는 작다. 문구를 "지금 카메라의 감지 기록이 없어요" 정도로 바꾸는 FE 개선 제안.
- **L-4 배포 전환기(수용)**: 배포 전에 나간 재촉·요약을 눌러 들어오면 그 상황이 삭제 카메라면 "전체"에 없다(삭제 카메라 칩에서 보임). 같은 이유로 하루 요약의 보호자 단위 미루기(`recentlyReminded`)는 삭제 카메라 상황의 옛 재촉 기록도 본다 - 배포 뒤 삭제 카메라 재촉이 새로 생기지 않으므로 2시간 안에 사라진다.

수용(성능): 대시보드는 피보호자 칩마다 카메라 조회가 1회 늘었다(카메라 서비스 전체 3대 상한, PK·ward 조회).

## 종합 판정

**PASS** - 새 불변식을 깨는 사용처 없음. 조치 대상은 🟢 L-1(Swagger)·L-3(FE 문구) 두 건이며 둘 다 표시 문구다.

## 후속 조치 (2026-10-26)

- **L-1 조치** - BE #380(`c91c615`): `GuardianDashboardResponse`의 `PendingActions`·`anomaly`·`needsReviewCount`·`latest` 스키마 설명에 "지금 등록된 카메라 기준"을 적었다(동작 변경 없음). `./gradlew build` 통과, vkcs CD 성공, gosky `b5a8eec`(#380 포함) 기동·AI WS 연결, gosky `/v3/api-docs`에서 새 문구 확인.
- **L-3 조치** - FE #144(`e910ced`): "전체"가 비었는데 `deletedCameraCount > 0`이면 "지금 카메라의 감지 기록이 없어요. 삭제된 카메라의 기록은 "삭제된 카메라"에서 볼 수 있어요". 브라우저 테스트 1건 추가(조건을 빼면 실패 확인), 단위 313·브라우저 208·build 통과, gosky FE 반영·`/guardian/detection` 200.
- 남은 것: L-4(배포 전환기)는 수용.
- 별건: FE `AuthShell.browser.test.tsx`가 `process is not defined`로 import 실패 - 이번 변경을 빼도 develop에서 같은 실패(로그인 화면 디자인 커밋 `daf8aad`·`7d8e4ce` 이후). → **해결**(FE #146 `c335ef1`, 2026-10-26): `next/image`·`Icon`을 테스트에서 대체하고, v11 레이아웃(안쪽 스크롤 없음)에 맞게 안전망 검사를 "틀 밖으로 밀려난 absolute 요소가 문서를 늘리지 않는다"로 재설계. 틀 `overflow:hidden` 제거·기준 부모 4곳 제거 변이 모두 검출, 브라우저 테스트 22파일 212건 전부 통과.
