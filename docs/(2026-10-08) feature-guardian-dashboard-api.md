# 보호자 대시보드 통합 API (2026-10-08)

`GET /api/guardian/dashboard?wardId=` - FE 가 대시보드 진입 시 여러 API 를 동시에 부르던 것을 한 번에 받는다.
**백엔드가 데이터를 가진 칸만** 담는다. 마이그레이션 없음.

## 범위

| 칸 | 포함 | 비고 |
|---|---|---|
| 이상감지(확인 필요) | O | 건수 + 가장 최근 확인 필요 1건 |
| SOS | O | 이번 달(KST) 건수 + 가장 최근 1건. **"해결됨"은 없다**(ACK 2026-08-26 철회) |
| 복약 | O | 오늘 복용 시각이 지났는데 체크되지 않은 약 |
| 확인이 필요한 일 N건 | O | 이상감지 확인 필요 + 복약 미체크. SOS 는 세지 않는다 |
| 오늘의 정서 / 게임·활동 / 병원 예약 | X | BE 에 데이터 없음 -> **필드를 만들지 않았다**(0 이나 빈 값으로 채우지 않음) |

## 계약

- 경로: `GET /api/guardian/dashboard?wardId=` (GUARDIAN 전용). 요청서의 `guardianId` 는 받지 않는다 - 보호자는 토큰에서만. 요청서의 값은 화면상 피보호자 칩이라 `wardId` 로 해석했다.
- `wardId` 지정: ACTIVE 연결 확인(아니면 403 `DASHBOARD_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`). 생략: ACTIVE 연결 전원 합산. 연결 0명: `wards: []`, 건수 전부 0, `latest`/`mostUrgent` null (실제로 센 0 - 칸 null 은 조회 실패 전용, 2026-10-08 점검 D-1 반영).
- 응답 최상위: `pendingActions{total,anomaly,medication}` · `wards[{wardId,wardName,pendingCount}]` · `anomalyDetection{needsReviewCount,latest}` · `sos{thisMonthCount,latest}` · `medication{uncheckedCount,mostUrgent}` · `unavailable[]`.
- `anomalyDetection.latest` = 기존 `AnomalyIncidentItem`(이력 API 와 같은 형식, `clip`/`clipCount` 포함). 영상 재생은 기존 클립 API.
- `sos.latest` = 기존 `SosHistoryItem`. 이번 달이 아니어도 가장 최근 1건.
- `medication.mostUrgent` = 지난 시각 + 미체크 중 복용 시각이 가장 이른 1건(`wardId, wardName, medicationId, name, timeSlot, doseTime`).

## 정책

- **칸별 실패 격리**: 한 칸이 실패해도 전체 500 이 아니다. 실패한 칸은 **0 이 아니라 null**, 칸 이름이 `unavailable` 에 담긴다. `pendingActions.total` 은 구성 칸이 하나라도 실패하면 null(합계를 추정하지 않는다). 칩의 `pendingCount` 도 같다. 인가 실패(403)만 전체 실패.
- **"확인이 필요한 일"의 정의**: 이상감지 `PENDING+CONFLICTED`(기존 `needsReviewCount` 와 같음) + 복약 미체크. SOS 는 보호자가 처리할 일이 없어 세지 않는다.
- **복약 미체크 판정**: `!taken && doseTime <= 현재 KST 시각`. 서버는 체크 누락과 실제 미복용을 구분하지 못한다 -> FE 문구는 **"체크되지 않았습니다"** 권장("안 드셨습니다" 단정 금지).
- **날짜는 KST**: 이번 달 = KST 1일 00:00 부터(`monthStart`), 복약 "오늘"/현재 시각은 `MedicationClock`.
- **로그**: 칸 이름과 예외 클래스명만(`[DASHBOARD-SECTION-FAILED]`). 예외 원문·본문 없음.
- **인가는 두 겹**: 대시보드가 한 번(`isActiveConnection`/`getActiveWardIds`), 하위 서비스가 각자 한 번 더. `getMyWards` 는 쓰지 않는다.

## 변경 파일

신규(`domain/dashboard`): `controller/GuardianDashboardController`, `service/GuardianDashboardService`, `dto/GuardianDashboardResponse`.
기존에 **메서드만 추가**(기존 시그니처·응답·인가·알림 불변):
- `AnomalyIncidentRepository.findLatestByStatuses`, `GuardianAnomalyService.getLatestNeedsReview`
- `SosEventRepository.countByWardIdInAndCreatedAtGreaterThanEqual`, `GuardianSosService.getRecent` (+`RecentSos`)
- `ErrorCode.DASHBOARD_NOT_AUTHORIZED`
복약 서비스는 변경 없음(`getWardMedications` 결과를 인가된 범위로 좁혀 사용).

## 설계 메모

- 대시보드 서비스에 `@Transactional` 을 걸지 않았다. 하위 readOnly 트랜잭션이 예외로 rollback-only 가 되면 바깥 트랜잭션이 커밋 때 `UnexpectedRollbackException` 을 던져 칸별 격리가 깨진다. 하위 서비스가 각자 읽기 전용 트랜잭션을 연다.
- 피보호자 이름은 `UserRepository.findAllById` 한 번(칩용). 서비스 메서드 재사용 원칙의 유일한 예외 - 이상감지 쪽 서비스도 같은 방식으로 이름을 조회한다.
- 피보호자 수만큼 `getHistorySummary` 를 부른다(칩 배지용). 보호자당 피보호자는 소수라 수용. 속도 제한(`UserRateLimitFilter` 600/분)은 경로 무관 전역이라 자동 적용.
- 알려진 한계: 오늘 늦게 등록된 약(복용 시각이 등록 이전)도 미체크로 센다 - `MedicationItem` 에 등록 시각이 없어 구분 불가(미복용 보호자 요약은 이를 제외하지만 대시보드는 화면 표시용).

## 검증

- 단위: `GuardianDashboardServiceTest`(연결 0명·403 시 하위 미호출·wardId 지정/생략 범위·칸 실패 null·미체크 판정·KST 월 경계·제외 필드 부재), `GuardianDashboardControllerSecurityTest`(GUARDIAN 허용·WARD/ADMIN 거부·guardianId 파라미터 없음).
- 통합(실 PostgreSQL): `GuardianDashboardQueryIntegrationTest`(확인 필요 최신 1건·인가 밖 제외·SOS KST 월 경계 말일 23:59:59 / 1일 00:00:00). **vkcs 에서 `tools/integration-test.sh` 로 실행** - 결과는 PR 본문.
- `./gradlew test build` 통과(로컬).
