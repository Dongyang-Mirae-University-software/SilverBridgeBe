# 이상감지 흉기·낙상 라이브 활성화 (2026-10-06)

## 배경
AI 서버가 `knife`(→WEAPON)·`fall`(→FALL) 신호를 보내기 시작한다(시연용). 지금까지 `isDetectable()`이 FIRE만 인정해 두 종류는 수신해도 버려졌다. AI 쪽 기준(백엔드 작업 아님): 흉기 = 7차수 모델·위험 기준 0.5 / 낙상 = 3차수 모델·위험 기준 0.35, 낙상은 1.5초 중 70% 이상 넘어진 상태일 때만 `danger=true`.

## 사전 확인(PHASE 0) - 문서와 실제의 차이
| 항목 | 결과 |
|---|---|
| `fromAi` 매핑 | `knife`→WEAPON, `fall`→FALL 이미 존재 |
| 판정 | `AnomalyJudge`가 `isDetectable()`로만 거름. 상황 승계·쿨다운·클립은 종류와 무관(FIRE 하드코딩 없음) |
| DB | `detected_type`에 CHECK 없음 → 마이그레이션 불요 |
| FE 계약 | DTO `allowableValues`·관리자 유형 필터·`DetectedTypeLabel`에 FALL·WEAPON 이미 있음 |
| 재촉·요약·동수 안내 | `DetectedTypeLabel` 사용, "화재" 고정 문구 없음 → 변경 불요 |
| 알림톡 | 승인 본문이 `#{detectedTypeLabel}` 변수 방식 → 흉기·낙상도 재심사 없이 발송. 계획 초안의 "스킵"은 불필요했음 |

## 변경
- `DetectedType.isDetectable()`: FIRE·FALL·WEAPON (normal·unknown은 계속 무시).
- `DetectedTypeLabel.withSubjectParticle()`: 받침으로 가/이 선택(화재가·흉기가·낙상이). 기존엔 "낙상가"가 나올 상태였다.
- `AnomalyNotificationListener`: 본인 안내를 종류별로.

| 종류 | 보호자 푸시 | 본인 푸시 | 본인 SMS 대체 |
|---|---|---|---|
| 화재 | `{이름}님 댁 {위치}에서 화재가 감지되었습니다.` | `… 안전한 곳으로 대피해 주세요.` (현행) | `… 안전한 곳으로 대피해 주세요.` (현행) |
| 흉기 | `… 흉기가 감지되었습니다. 바로 연락해 안전을 확인하고, 위험하면 112에 신고해 주세요.` | `… 안전한 곳으로 피하고 112에 연락해 주세요.` | 동일 |
| 낙상 | `… 낙상이 감지되었습니다. 바로 연락해 안전을 확인해 주세요.` | `… 괜찮으시면 보호자에게 연락해 주세요. 도움이 필요하면 SOS 버튼을 눌러 주세요.` | `… 도움이 필요하면 보호자에게 연락해 주세요.` |

- 112 안내는 문구일 뿐 서버가 발신하지 않는다(SOS 119 정책과 같은 결). 낙상 본인 알림은 유지(기존 수신자·본인 쿨다운 3분 그대로).
- 주석 정리: `AnomalyJudge`, `AdminSafetyDashboardResponse.TodayAnomaly`(낙상·흉기 미탑재 설명 → 집계된 유형만 담는 이유로 정정).

- **보호자 문구 보강(같은 날)**: 흉기·낙상 보호자 푸시에 행동 안내 추가(위 표). 흉기·낙상 보호자 SMS 대체는 푸시 본문과 같은 문구에 `[실버브릿지] `만 앞에 붙인다(화재는 `앱에서 확인해 주세요.` 유지). 알림톡은 승인 고정 문구라 안내를 더하려면 재검수가 필요해 이번엔 제외. 화재 보호자 문구와 본인 문구는 무변경.

## 바뀌지 않는 것
판정 정책(DANGER), 쿨다운 값, 수신자(ACTIVE 보호자 전원 + 본인), 강제 FCM, 알림톡 매핑(보호자 전용), 재촉·요약·동수 안내 문구.

## 테스트
- 신규: `DetectedTypeTest`(isDetectable·fromAi), `DetectedTypeLabelTest`(조사), `AnomalyJudgeTest`(낙상·흉기 danger 추종), `AnomalyNotificationListenerTest`(종류별 푸시·SMS 문구).
- 수정: `AnomalySignalParserTest`(WEAPON 비대상 단언 제거), `AnomalyJudgeTest`(미탑재 무시 테스트 제거).
- `./gradlew build` 통과.

## AI 팀에 넘길 사항
백엔드가 받는 `detectedType`은 `fire`/`smoke`(→FIRE), `fall`(→FALL), `knife`(→WEAPON)이며 대소문자는 무시한다. 라이브 경로에서 `danger=true`가 와야 이력·알림이 생긴다(백엔드는 종류로 거르지 않는다). 등록된 `camera.session_id`만 구독한다.

## 후속
시연 뒤 영향 범위 점검(템플릿 C) 제안: `DetectedType` 감지 대상 확대 = 공용 규칙 변경. `docs/audit-index.md`에 ❌ 행 추가함.
