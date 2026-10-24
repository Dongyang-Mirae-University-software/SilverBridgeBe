# 이상감지 - 삭제된 카메라 이력을 기본 집계·재촉에서 빼기 (2026-10-25)

> 브랜치 `fix/anomaly-deleted-camera-archive` · 마이그레이션 없음 · 사용자 결정 2026-10-25

## 1. 배경

삭제된 카메라(지금 등록되어 있지 않은 `session_id` - 이력 응답 `cameraLabel = null`과 같은 기준)의 이상감지 상황은
영상이 없고(카메라 삭제 시 클립도 삭제) 다시 확인할 방법이 없다. 그런데 보호자 화면의 "전체"·"확인 필요" 건수에 섞여
혼란을 주고, 재촉 알림까지 나갔다.

결정: **이력과 판정은 지우지 않고**(안전 기록·판정 통계 보존) 보호자 기본 집계·재촉에서만 빼고,
"삭제된 카메라" 필터(`camera=DELETED`, 2026-10-24)에서만 보이게 한다.

## 2. 바뀐 것

| 대상 | 전 | 후 |
|---|---|---|
| `GET /api/guardian/anomaly/history/summary` | 조회 범위의 상황 전부를 셈 | `total`·`pendingCount`·`conflictedCount`·`needsReviewCount`·`byType` = **지금 등록된 카메라** 상황만(= `history?camera=EXISTING`의 totalElements). `deletedCameraCount`는 그대로 |
| 보호자 대시보드 `GET /api/guardian/dashboard` 이상감지 확인 필요 건수(합계·피보호자별 칩) | 전부 | 지금 등록된 카메라 상황만 |
| 대시보드 "최신 확인 필요 1건" | 전부 중 최신 | 같은 기준(건수와 1건이 어긋나지 않게) |
| 미응답 건별 재촉 / 하루 1회 요약 / 동수 재확인 안내 | 판정 상태·기간 조건 | 후보 조회 직후 **지금 등록된 카메라** 상황만 남김 |
| Swagger | - | summary·history `camera`·대시보드 설명에 의미 변경 명시 |

관계식: `summary.total + summary.deletedCameraCount = history(camera 생략).totalElements`.

## 3. 바꾸지 않은 것

- `history`의 `camera` 생략 = 전체(하위 호환). FE가 "전체" 탭에서 `camera=EXISTING`을 보낸다.
- 판정 API `POST .../anomaly/{incidentId}/feedback` - 카메라 등록 여부를 보지 않아 삭제된 카메라 상황에도 그대로 응답할 수 있다.
  동수를 만든 보호자의 `sent=false` 기록(`skipConflictNoticeFor`)도 그대로.
- 이미 남은 재촉·요약·동수 안내 발송 기록은 건드리지 않는다(선점 후 발송·UNIQUE·야간 미루기·설정 존중 그대로).
- 관리자 이상감지 로그·요약·대시보드 - 전체 기록 기준 유지(오탐률 통계).
- 이상감지 발생 알림 본체 - 카메라가 등록돼 있어야 감지되므로 해당 없음.
- 이력 행·응답 데이터 삭제 없음.

## 4. 구현

- `AnomalyIncidentRepository.countForGuardianSummary(wardIds, liveSessionIds)`·`findLatestByStatuses(wardIds, liveSessionIds, statuses, pageable)`에 `i.sessionId IN :liveSessionIds` 추가.
- `GuardianAnomalyService`: 요약·대시보드 두 메서드가 `liveSessionIds(wardIds)`(= `CameraService.findSessionIdsByWardIds`, 사용 중지 포함, 비면 매칭 불가 값 `""`)를 넘긴다. 요약은 카메라 조회 1회로 기본 집계·삭제 건수를 함께 계산.
- `AnomalyReviewReminderPlanner.retainRegisteredCameras`: 세 후보 조회 직후 `CameraService.findLabelsBySessionIds(후보 세션)`의 키(= 카메라 행이 있는 세션)로 거른다.
  - JPQL에서 `Camera`를 직접 조인하지 않은 이유: anomaly가 카메라 엔티티를 쿼리로 참조하지 않고 `CameraService`를 거치는 기존 경계(이력 필터와 같은 방식). 후보는 마감 3일 안의 PENDING·CONFLICTED뿐이라 메모리 필터 비용이 작다.

## 5. 성능

- 보호자 쿼리는 기존 `idx_anomaly_incident_ward_started(ward_id, started_at)`를 그대로 타고, 세션 IN 목록이 붙는다. 등록 카메라는 서비스 전체 상한 3대(`camera.max-total`)라 목록이 최대 3개다.
- 카메라 존재 확인(`findBySessionIdIn`)은 `uq_cameras_session(session_id)` 유니크 인덱스.
- 대시보드는 피보호자별 건수마다 카메라 조회가 1회 늘었다(피보호자 수만큼, 작은 테이블 PK·ward 조회).

## 6. 테스트

- 단위
  - `GuardianAnomalyHistoryFilterTest`: 요약이 등록 카메라 목록을 기본 집계·삭제 건수 모두에 넘김(카메라 조회 1회), 카메라 0대면 매칭 불가 값, 대시보드 건수도 같은 목록
  - `GuardianAnomalyServiceTest`: 최신 1건이 등록 카메라 목록을 넘김, 삭제된 카메라 상황에도 판정이 동작하고 카메라를 조회하지 않음
  - `AnomalyReviewReminderPlannerTest.DeletedCameraExcluded`: 재촉(혼합·전부 삭제)·요약(삭제분 미집계·삭제분뿐이면 미발송)·동수 안내 제외, 사용 중지 카메라는 계속 재촉
- 통합(실 PostgreSQL)
  - `GuardianAnomalyHistoryQueryIntegrationTest.summaryExcludesDeletedCamera`: 등록·삭제·사용 중지 카메라가 섞였을 때 유형×판정 집계는 등록분(사용 중지 포함)만, 삭제분은 `countDeletedCameraForGuardian`, 합이 전체 이력과 같음
  - `GuardianDashboardQueryIntegrationTest.deletedCameraExcluded`: 확인 필요 건수·최신 1건이 같은 기준으로 삭제분 제외

## 7. FE 전달

- 이력 화면 "전체" 탭은 `GET /api/guardian/anomaly/history?camera=EXISTING`을 보낸다(요약 숫자와 맞추려면 필수).
- "삭제된 카메라" 탭은 `camera=DELETED`, 건수는 `summary.deletedCameraCount`.
- 요약 `total`·탭 건수·"확인 필요"는 배포 즉시 등록 카메라 기준으로 바뀐다. FE 반영 전에는 "전체" 목록 건수와 헤더 숫자가 다를 수 있다.
- 삭제된 카메라 상황에는 재촉·요약·동수 안내 푸시가 가지 않지만, 화면에서 직접 판정은 할 수 있다.
