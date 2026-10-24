# (2026-10-24) 보호자 이상감지 이력 - 삭제된 카메라 필터·건수

- 요청: 사용자(2026-10-24) "삭제된 카메라 필터 있어야겠는데?" - Claude Design 이상감지 화면의 "삭제된 카메라 N" 칩
- 범위: BE(필터·건수) + FE(칩). 마이그레이션 없음

## 왜 서버에서 하는가

FE #123 전에는 불러온 한 페이지 안에서만 삭제된 카메라 기록을 숨기거나 셌다. 그러면 칩 숫자와 목록이 페이지마다 달라진다. 유형 칩과 같이 **서버가 거르고 서버가 센다**.

## API

| 경로 | 변경 |
|---|---|
| `GET /api/guardian/anomaly/history` | `camera=DELETED`(삭제된 카메라의 이력만) · `EXISTING`(지금 등록된 카메라의 이력만), 생략 = 전체. 그 밖의 값 400. `type`과 함께 쓸 수 있고 `totalElements`도 필터 기준 |
| `GET /api/guardian/anomaly/history/summary` | `deletedCameraCount` 추가(= `camera=DELETED`의 `totalElements`, 유형 무관). 기존 필드 무변경 |

## 정책

- **삭제 기준 = 이력의 `cameraLabel = null`과 같다**: 상황의 `session_id`에 해당하는 카메라 행이 없으면 삭제된 카메라다. 사용 중지(`is_active=false`)는 삭제가 아니다.
- 삭제 뒤 같은 기기로 다시 등록하면 새 세션이 발급되므로(`CameraService.register` - 재사용은 카메라 행이 있을 때만) 옛 이력이 다시 "있는 카메라"가 되지 않는다.
- 필터는 전용 enum `AnomalyCameraFilter`로 받는다 - 잘못된 값은 빈 목록이 아니라 400(`AnomalyTypeFilter`·`WardListFilter`와 같은 판단).
- 인가는 그대로(`resolveVisibleWardIds`, ACTIVE 연결만). 지금 등록된 카메라 세션은 그 피보호자 범위로만 조회한다.
- 상황 쿼리는 `Camera`를 직접 참조하지 않고, 서비스가 세션 목록을 넘긴다(관리자 로그 검색과 같은 방식). 목록이 비면 매칭 불가 값 `""` 하나(빈 IN 절 회피).
- 카메라 필터가 없으면 카메라를 조회하지 않는다.

## 변경 파일

- BE: `AnomalyCameraFilter`(신규) · `AnomalyIncidentRepository.findHistory`(카메라 조건) · `countDeletedCameraForGuardian`(신규) · `CameraRepository.findSessionIdsByWardIdIn`·`CameraService.findSessionIdsByWardIds`(신규) · `GuardianAnomalyService` · `GuardianAnomalyHistorySummary.deletedCameraCount` · `GuardianAnomalyController`(파라미터·Swagger)
- 테스트: `GuardianAnomalyHistoryFilterTest`(+5) · `GuardianAnomalyHistoryHttpTest`(+2) · `GuardianAnomalyHistoryQueryIntegrationTest`(+1, 실제 PostgreSQL) · 시그니처 변경에 따른 기존 테스트 수정
- FE: 별도 PR - 유형 칩 줄에 "삭제된 카메라 N"(1건 이상일 때만), 누르면 유형은 "전체"로(하나만 고름)

## 테스트 결과

- `./gradlew build` 통과 - 단위 테스트 1871개, 실패 0 (건너뜀 1)
- 통합 테스트(vkcs, 브랜치): PR 본문에 결과
