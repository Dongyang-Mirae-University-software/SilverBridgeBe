# 보호자 이상감지 이력 - 유형 필터 + 건수 요약 (2026-10-07)

> 요청: FE(이윤아) "이력에 낙상·화재·흉기를 구분하는 쿼리를 넣어 달라. 페이지네이션이라 필요하다. 없으면 전체."
> 마이그레이션 없음. 브랜치 `feature/anomaly-history-type-filter`.

## 배경
- FE는 불러온 페이지만으로 탭 건수·"확인 필요"를 계산했다. 서버 페이지네이션에서는 이 값이 틀리므로 서버가 필터와 건수를 함께 준다.
- 프로토타입의 "연기" 탭은 BE에 없다(연기는 `FIRE`로 합쳐 저장). FE 탭은 화재(연기 포함)·낙상·흉기.

## 범위
1. `GET /api/guardian/anomaly/history` 에 `type`(FIRE | FALL | WEAPON, 생략 시 전체) 추가. 필터는 쿼리 안에서 건다(`findHistory`) - `totalElements`·`totalPages`도 필터 기준.
2. `GET /api/guardian/anomaly/history/summary?wardId=` 신설 - `{ total, pendingCount, conflictedCount, byType{fire,fall,weapon} }`.

## 정책 결정
- **필터 enum은 보호자용 `AnomalyTypeFilter`를 새로 둔다.** `DetectedType`을 그대로 받으면 NORMAL·UNKNOWN이 400이 아니라 빈 목록이 된다(관리자 `AdminAnomalyTypeFilter`와 같은 판단). 관리자 enum 이름을 바꾸지 않아 관리자 API 영향 없음.
- 잘못된 값(소문자·NORMAL·UNKNOWN·SMOKE)은 400(`MethodArgumentTypeMismatchException` 기존 처리).
- 요약의 유형 건수는 type 필터와 무관하게 **조회 범위(wardId) 전체** 기준 - 탭을 골라도 숫자가 변하지 않는다.
- 인가는 이력과 같은 `resolveVisibleWardIds`(ACTIVE 연결만, 위반 403 + `[IDOR-ATTEMPT]`)를 먼저 거친다. 연결이 없으면 전부 0인 빈 요약.
- **`byType`은 세 유형을 항상 담는다.** "0건인 유형은 항목을 만들지 말 것"은 AI 모델이 없던 유형이 0건으로 보여 "안전"으로 읽히는 것을 막으려던 규칙(관리자 `byType`)이다. 낙상·흉기는 10/6부터 라이브라 0이 실제로 센 값이며, 항목이 빠지면 FE가 탭 건수를 undefined로 처리해야 한다. 관리자 `byType`은 그대로.
- **"확인 필요"는 서버가 한 값으로 정하지 않는다.** `pendingCount`(PENDING)와 `conflictedCount`(CONFLICTED)를 따로 내리고 화면이 합산 여부를 정한다. (FE 코드가 로컬에 없어 정의를 확인하지 못했다.)
- 정렬: `startedAt DESC, id DESC` (동률 시 페이지 경계 안정).
- 시그니처: `getHistory(guardianId, wardId, type, page, size)` 5인자로 교체(호출부 컨트롤러 1곳·테스트만).

## 변경 파일
- `AnomalyIncidentRepository` (`findHistory`·`countForGuardianSummary` 추가, `findByWardIdInOrderByStartedAtDesc` 제거)
- `GuardianAnomalyService` (`getHistory` type, `getHistorySummary`)
- `GuardianAnomalyController` (type 파라미터·summary 엔드포인트·Swagger)
- 신규 DTO `AnomalyTypeFilter`·`GuardianAnomalyHistorySummary`
- 테스트: `GuardianAnomalyHistoryFilterTest`·`GuardianAnomalyHistoryHttpTest`(신규), `GuardianAnomalyServiceTest`·`GuardianAnomalyControllerSecurityTest`(시그니처·요약 권한), 통합 `GuardianAnomalyHistoryQueryIntegrationTest`(신규)

## 바뀌지 않는 것
- 이력 응답 형태(`PageResponse<AnomalyIncidentItem>`), 인가 로직, `type` 없을 때의 결과(순서만 id 보조 정렬 추가).

## 검증
- `./gradlew build`: 1520건 / 실패 0 / 스킵 1 (로컬).
- 통합 테스트(실제 PostgreSQL): vkcs에서 실행 - 결과는 PR 본문 참조.
