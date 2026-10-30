# 보호자 SOS 이력 - 연타 묶음 조회 (2026-10-30)

## 1. 배경

보호자 이력 화면이 같은 피보호자의 연타를 한 칸("10월 5일 오후 3:21 · 2번 연달아 누름")으로 보이려 한다.
지금 이력 API는 누를 때마다 한 줄이라 FE가 묶으면 페이지 경계에서 묶음이 잘린다(1페이지 끝 2번 + 2페이지 첫 1번이 두 칸으로 갈림). 그래서 BE가 DB 안에서 묶어서 준다.

## 2. 결정 (사용자 승인 2026-10-30)

| | 결정 |
|---|---|
| D1 | 같은 피보호자·같은 발생 경로(`triggerType`)의 다음 누름이 **앞 누름과 60초 이내**면 같은 묶음. 시계 기준 칸이 아니라 연쇄다(30초 간격으로 5분 누르면 한 묶음). 정확히 60초도 같은 묶음. 간격은 설정 `sos.history-group-gap-seconds`(env `SOS_HISTORY_GROUP_GAP_SECONDS`, 기본 60, 1~3600 밖이면 60) |
| D2 | 푸시 문구의 "최근 10분 내 N번째"(`SosNotificationListener.REPEAT_WINDOW`)는 그대로 - 목적이 다르다 |
| D3 | 기존 `GET /api/guardian/sos/history`는 그대로. `group=true`일 때만 묶음 단위. 페이지·`totalElements`는 묶음 단위 |
| D4 | 묶음 모드의 `counts`(all·sosButton·guardianCall)는 묶음 수. 묶지 않는 모드는 지금처럼 누름 수 |
| D5 | 탈퇴로 `ward_id`가 NULL인 익명 이력은 묶지 않는다 - 실제로는 보호자 이력 쿼리가 `ward_id IN (인가 목록)`이라 익명 이력은 애초에 조회되지 않는다(아래 4절) |

## 3. API

`GET /api/guardian/sos/history?group=true[&wardId=&triggerType=&page=&size=]` (GUARDIAN, 인가는 기존과 같음 - ACTIVE 연결만, 위반 403 `SOS_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`)

묶음 항목 = 기존 필드(**최신 건** 값) + 3개:

```json
{
  "sosEventId": 42,                       // 묶음의 최신 건
  "wardId": "A1B2C3",
  "wardName": "김영희",
  "triggeredAt": "2026-10-05T15:21:40+09:00",   // = lastTriggeredAt
  "location": "자택 거실",                  // 최신 건 위치(묶음 안 위치가 달라도)
  "triggerType": "SOS_BUTTON",
  "pressCount": 2,
  "firstTriggeredAt": "2026-10-05T15:21:00+09:00",
  "lastTriggeredAt": "2026-10-05T15:21:40+09:00"
}
```

- `group` 생략·false: 응답이 바이트 단위로 기존과 같다 - 세 칸은 null이면 JSON에 나오지 않는다(`@JsonInclude(NON_NULL)` 칸 단위). 보호자 대시보드의 `latest`(같은 `SosHistoryItem`)도 그대로.
- `triggerType` 필터와 함께: 묶음이 경로별로 나뉘므로 필터를 걸어도 그 경로의 묶음은 전체 조회 때와 같다.
- 순서: 묶음의 마지막 누름 최신순(같으면 최신 건 id 내림차순).

## 4. 쿼리

`SosEventRepository.PRESS_GROUP_CTE` (PostgreSQL 네이티브):

1. `marked`: 인가 목록·경로 조건으로 거른 행에 `LAG(created_at) OVER (PARTITION BY ward_id, trigger_type ORDER BY created_at, id)`로 앞 누름과의 간격을 보고, 첫 행이거나 간격 > gap이면 `is_start = 1`.
2. `numbered`: `is_start` 누적합 = 묶음 번호 `grp`.
3. 목록: `GROUP BY ward_id, trigger_type, grp` → `array_agg(id ORDER BY …)[1]`로 최신·최초 id, `COUNT(*)` 누름 수, `ORDER BY MAX(created_at) DESC` + `LIMIT/OFFSET`.
4. 건수: `SUM(is_start)`(전체) / `SUM(is_start) GROUP BY trigger_type`(counts).

- 시각·위치는 프로젝션으로 받지 않는다. 묶음 행은 id만 주고 서비스가 `findAllById`로 엔티티를 읽어 기존 항목과 같은 매핑을 쓴다(네이티브 timestamptz 프로젝션 변환 선례가 없어 위험을 피함).
- 경로 조건은 빈 문자열 = 전체(null 바인딩 타입 추론 회피).
- **익명 이력**: `WHERE ward_id IN (:wardIds)`라 NULL은 대상이 아니다. 기존 서비스 주석·단위 테스트의 "익명 이력도 목록에 실린다"는 목(mock)에서만 성립했다 - 주석을 바로잡았다(동작 변경 없음).
- **인덱스**: 마이그레이션 없음. WHERE는 `idx_sos_events_ward_created (ward_id, created_at DESC)`를 타고, 윈도 정렬 대상은 ACTIVE 연결 피보호자들의 SOS 행 전체(사람이 누르는 이벤트라 피보호자당 수백 건 규모). 요청마다 범위 전체를 묶으므로 수만 건 단위가 되면 다시 본다.

## 5. 바뀌지 않는 것

SOS 발생(`POST /api/ward/sos`)·이력 저장·알림·쿨다운(10초)·`repeatCount`(10분 창)·문자 폴백 상한·보호자 대시보드(`getRecent`)·인가 경로(새 인가 코드 없음, `getMyWards` 미사용)·`group` 없는 요청의 응답.

## 6. 변경 파일

- `sos/repository/SosEventRepository.java` - `PRESS_GROUP_CTE`, `findPressGroups`·`countPressGroups`·`countPressGroupsByTriggerType`, 프로젝션 2종
- `sos/service/GuardianSosService.java` - `getGroupedHistory`, 묶음 counts, 익명 주석 정정
- `sos/controller/GuardianSosController.java` - `group` 파라미터·Swagger 설명
- `sos/dto/SosHistoryItem.java` - `pressCount`·`firstTriggeredAt`·`lastTriggeredAt`(NON_NULL), `ofGroup`
- `sos/config/SosProperties.java`, `application.yaml` - `history-group-gap-seconds`
- 테스트: `GuardianSosServiceTest`(+5), `GuardianSosControllerHttpTest`(신규, 직렬화 회귀), `SosPropertiesTest`(+2), `GuardianSosControllerSecurityTest`(시그니처), `SosHistoryPressGroupIntegrationTest`(신규, 실 DB 8건)

## 7. 검증 가이드

- 통합 테스트(vkcs): `~/SilverBridgeBe/tools/integration-test.sh feature/sos-history-press-group`
  - 59초 연쇄 1묶음 / 60초 같은 묶음·61초 두 묶음 / 30초 간격 5분 1묶음 / 경로·피보호자 다르면 따로 / 익명 미포함 / 페이지 경계 미절단(크기 1로 3페이지) / counts = 묶음 수
- 실서버(gosky, 보호자 토큰): `GET /api/guardian/sos/history` 와 `?group=true`를 비교 - 전자는 세 칸이 없고, 후자의 `counts.all` ≤ 전자의 `counts.all`.

## 8. 테스트 결과

- 로컬 `./gradlew build`(단위 테스트 포함): **BUILD SUCCESSFUL** - 2093건, 실패 0·오류 0·건너뜀 1(기존).
  - 신규: `GuardianSosServiceTest` 묶음 매핑·경로 필터·403·연결 0명·기존 조회 회귀 5건, `GuardianSosControllerHttpTest` 2건(생략 시 세 칸 JSON 없음 / `group=true` 분기), `SosPropertiesTest` 2건.
- vkcs `tools/integration-test.sh feature/sos-history-press-group` @ `449a6eb6`: **통과** - 전체 110건 실패 0, `SosHistoryPressGroupIntegrationTest` 8건 전부 통과(캐시 아님, 5 tasks executed).
