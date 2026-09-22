# 관리자 이상감지 로그 v2 - 기간·유형·검색 필터 + 집계 API + 연기=화재 통합

> 2026-09-21 · 기능 · 마이그레이션 V53(⚠️ SMOKE → FIRE 데이터 변환, 비가역)

## 왜

관리자 콘솔 시안 v2(이상감지 로그)는 기간 탭(오늘·이번 주·이번 달·전체), 유형 탭(건수 포함), 이름·장소 검색,
사용자 응답률, 판정별 현황, AI 신뢰도, 감지 지속을 보여 준다. 기존 `GET /api/admin/anomaly`는 판정 상태·피보호자
필터와 페이징뿐이라 이 화면을 채울 수 없었다.

함께 **연기를 화재로 통합**했다(사용자 결정) - 백엔드에서 연기는 화재로 구분한다.

## 결정 (2026-09-21)

| # | 결정 |
|---|---|
| D-1 | **연기 = 화재, 백엔드 전체 적용**. AI가 `smoke`를 보내도 `DetectedType.fromAi`가 `FIRE`로 받는다. 상황 병합·쿨다운·알림·이력·통계 모두 "화재". 과거 SMOKE 행은 V53이 FIRE로 옮기고 enum에서 `SMOKE`를 지웠다 |
| D-2 | 흉기는 기존 enum 이름 `WEAPON` 유지(DB에 `KNIFE` 행 없음 확인). AI 클래스명 `knife`를 `WEAPON`으로 받는다. 라이브 탑재 전이라 `isDetectable`에는 넣지 않았다 |
| D-3 | 유형은 집계된 것만(0건 항목 없음). 라벨 FIRE=화재 · FALL=낙상 · WEAPON=흉기 |
| D-4 | 판정 4값 필터·건수 - 미판정(PENDING)·위험(REAL)·오탐(FALSE_ALARM)·동수(CONFLICTED). 다수결(V52) 그대로 |
| D-5 | 응답률 = (total - pending) / total, total 0이면 null |
| D-6 | ~~**AI 신뢰도 = 위험 / (위험 + 오탐)**~~ → **2026-09-22 confidence 평균으로 교체** (`(2026-09-22) feature-admin-anomaly-ai-confidence.md`). 미판정·동수는 분모 제외, 분모 0이면 null. 정의상 **AI 신뢰도 = 위험 비율**이고 오탐 비율 = 1 - 신뢰도다 |
| D-7 | 유형 탭을 고르면 오른쪽 집계(판정별 현황·응답률·AI 신뢰도)도 그 유형으로 좁힌다. **탭 옆 건수만 유형 필터를 무시**한다 |
| D-8 | 감지 지속 = 마지막 감지 - 첫 감지(분). 한 번만 잡힌 상황은 0 → 화면 "순간" |
| D-9 | 조회 전용, 감사 로그 없음, 서버 캐시 없음, 날짜 KST |

## API

### `GET /api/admin/anomaly` (확장 - 파라미터 모두 선택, 생략 시 기존과 동일)

| 파라미터 | 값 | 설명 |
|---|---|---|
| `status` | PENDING · REAL · FALSE_ALARM · CONFLICTED | 기존 |
| `wardId` | 피보호자 ID | 기존 |
| `period` | TODAY · THIS_WEEK · THIS_MONTH · ALL | 첫 감지 시각 기준 KST, 주는 월요일 시작. 생략 = ALL |
| `type` | FIRE · FALL · WEAPON | 전용 필터 enum - `SMOKE`·`NORMAL` 등은 400 |
| `keyword` | 문자열 | 피보호자 이름 또는 카메라 위치 부분일치(대소문자 무시, `%`·`_`는 글자 그대로). 탈퇴한 피보호자·삭제된 카메라는 검색되지 않는다 |
| `page`·`size` | | 기존(최대 50) |

항목에 **`durationMinutes`** 추가. `detectedType`의 가능 값은 이제 FIRE · FALL · WEAPON(SMOKE 없음).

### `GET /api/admin/anomaly/summary` (신설 - `period`·`type`·`keyword`)

```json
{
  "period": "THIS_WEEK",
  "total": 54,
  "byType": [ { "type": "FIRE", "label": "화재", "count": 54 } ],
  "review": { "pending": 12, "real": 16, "falseAlarm": 24, "conflicted": 2 },
  "responseRate": 0.7778,
  "accuracy": { "rate": 0.4, "falseAlarmRate": 0.6, "basis": 40 }
}
```

- `byType`: **유형 필터 무시**, 집계된 유형만, 건수 내림차순. 나머지는 고른 유형으로 좁힌다.
- 비율은 0.0~1.0(소수 넷째 자리). 분모 0이면 null - 0%로 표시하지 말 것.
- DB에서는 (유형, 판정 상태)별 건수만 `GROUP BY`로 받는다 - "전체" 기간도 원본 행을 읽지 않는다.

## 구현 메모

- 검색어는 먼저 피보호자 ID(`users.name`)·카메라 sessionId(`camera.label`) 목록으로 바꾼 뒤 상황을 거른다(상황 행에 이름·위치가 없다).
  한쪽 결과가 비면 매칭 불가능한 값 하나를 넣어 빈 IN 절을 피한다.
- 기간 "전체"는 null 대신 2000-01-01 하한을 넘긴다(null 시각 파라미터의 PostgreSQL 타입 추론 문제 회피).
- 알림 리스너가 따로 들고 있던 유형 라벨 매핑을 `DetectedTypeLabel`로 일원화했다.
- 마이그레이션: V53 = `anomaly_event`·`anomaly_incident`의 `SMOKE` → `FIRE`. `detected_type`에 CHECK 제약은 없다.
  운영 DB(api.devdmu)는 두 테이블 모두 0행(2026-09-21 확인).

## 영향 (보호자·알림 쪽 - 공용 변경)

- 연기 감지가 **화재로 알림**된다: 보호자·본인 FCM/WS/SMS 문구, 알림톡 `#{detectedTypeLabel}` 변수 값(템플릿 고정 문구는 그대로라 재검수 불요),
  재촉·동수 안내 문구, 보호자 이력의 `detectedType`·`detectedTypeLabel`.
- 같은 카메라에서 화재·연기가 번갈아 잡혀도 **한 상황**으로 묶이고 쿨다운도 공유한다(전에는 따로 열렸다).
- FE: `detectedType`에 `SMOKE`가 더 이상 오지 않는다. 유형 탭은 `summary.byType`으로 동적으로 그린다.

## FE 요청 (Notion 관리자 콘솔 페이지 갱신 대상)

- 기간·유형·검색은 목록과 `summary`에 같은 값으로 보낸다. 기본 기간(시안 "이번 주")은 FE가 `period=THIS_WEEK`로 보낸다.
- 유형 탭은 `byType`으로 그린다 - 낙상·흉기 탭을 고정으로 두지 말 것(0건은 항목이 없다).
- 판정 칩은 4값 - "동수"는 0건이면 숨겨도 된다. 미판정에 합치지 말 것.
- AI 신뢰도 카드: 메인 = `accuracy.rate`, 하위 두 칸 = 위험 비율(`rate`)·오탐 비율(`falseAlarmRate`)로 합 100%. 분모(`basis`)를 함께 보여 주면 좋다.
- 감지 지속: `durationMinutes` 0 → "순간", 그 외 "N분".

## 검증

- `./gradlew test` 609 tests / 0 failures / 0 errors (skipped 1).
- 추가 테스트: 기간 경계(KST 자정·월요일·월초·UTC 날짜 어긋남), 유형 필터 변환, 검색어 이스케이프·공백 무시·빈 결과,
  감지 지속, 집계 비율·분모 0 null·탭 건수는 유형 무시·0건 유형 없음, `smoke`→FIRE·`knife`→WEAPON 파싱.
- ⚠️ 새 JPQL 2개(`searchForAdmin`·`countForAdminSummary`)는 목 기반으로만 검증했다(실 DB 테스트 부재). Spring Data가 기동 시
  JPQL을 검사하므로 배포 기동 로그로 확인한다.
