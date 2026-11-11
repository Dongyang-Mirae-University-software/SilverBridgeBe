# 안부 말벗 여러 날 위험 누적 알림 (2026-11-11)

## 배경

- 한 번의 대답으로는 `riskFlag`(AI 위험 확률 0.99)에 못 미치지만 위험한 말이 며칠에 걸쳐 나오는 경우를 잡는다.
- 11/08 DGX 연구(실제 통화 7일 + 위험 신호 2~3개 삽입): 위험한 주를 찾는 비율 23% → 54%, 안전한 주에 알림이 가는 비율 4% → 6%.
- 한계: 개발용 자료에서는 안전한 주 알림이 +3.6%p로 목표(+2%p)를 못 맞췄다. 실제 어르신 대화로는 아직 검증하지 않았다. 그래서 기본은 꺼 둔다.
- AI 턴 응답의 `riskProbability`(float|null, v9 척도 - AI #234)를 쓴다.

## 결정 (사용자 결정 2026-11-11)

| 항목 | 결정 |
|---|---|
| 저장 | `companion_session.max_risk_prob NUMERIC(5,4)` null 허용, 턴마다 최댓값만 남김(V70). 소수 넷째 자리 내림, 0~1 밖·숫자가 아닌 값은 버림 |
| 규칙 | 최근 7일(오늘 포함, `session_date`) 중 0.97 이상인 서로 다른 날 2일 이상 + 그중 위험 알림이 안 나간 날 1일 이상 |
| 범위 | 대화 상태·테스트 대화 구분 없음. 보호자마다 지금 연결 이후 대화만(D-1) |
| 반복 억제 | 피보호자당 7일에 1번 - `companion_risk_pattern_notice` 1행 조건부 UPSERT |
| 알림 | `COMPANION_RISK_PATTERN` - 위험 알림과 같은 강제 채널(FCM 강제 + 설정, 대체 문자), 긴급 풀, 웹푸시 high, 사용자 묶음 URGENT, 관리자 탭 OTHER |
| 설정 항목 | 따로 만들지 않음. `companionRisk`(필수)에 포함 |
| 관리자 집계 | "알림 보냄" 수에 포함 |
| 스위치 | `COMPANION_RISK_PATTERN_ENABLED`(기본 false), `_THRESHOLD` 0.97, `_MIN_DAYS` 2, `_WINDOW_DAYS` 7 |

문구: 제목 "안부 말벗 확인 필요", 본문 "{이름}님과의 최근 며칠 안부 대화에서 걱정되는 말이 여러 번 있었어요. 안부를 확인해 주세요." data는 `type`·`wardId`·`days`뿐이다(확률·원문 없음).

## 흐름

1. `CompanionService.handleTurn`이 `riskProbability`를 읽어 `CompanionSessionWriter.recordTurn`에 넘긴다.
2. writer는 OPEN 대화만 최댓값을 갱신한다. 위험 알림 선점을 먼저 하고, 스위치가 켜져 있고 그 턴 확률이 기준 이상이면 `CompanionRiskPatternCheckEvent`를 낸다.
3. 커밋 뒤 `CompanionRiskPatternListener`(긴급 풀)가 7일 대화를 읽어 피보호자 단위로 판정한 뒤, 보호자별 D-1로 다시 판정한다.
4. 받을 보호자가 있으면 조건부 UPSERT로 선점하고, 이긴 경우에만 보호자별로 보낸다(수신자별 try/catch). 정지 수신자 차단은 디스패처가 한다.

## 바뀌지 않는 것

- riskFlag 즉시 위험 알림(`COMPANION_RISK`, 대화당 1번), 3일 주의 알림(`COMPANION_NOTICE`), 보호자 화면 응답.
- 보호자 알림 종류 설정 API 응답 모양(설명 문구만 갱신).

## 테스트

- 단위: `CompanionRiskPatternTest`(한 날 두 번·두 날·8일 전·0.97 미만·null·위험 알림 날만·OPEN·테스트 대화·확률 읽기), `CompanionRiskPatternListenerTest`(스위치·D-1·선점 짐·받을 사람 없음·실패 격리·문구), `CompanionSessionWriterTest`(최댓값·이벤트 조건·끝난 대화), 알림 가드 4종(강제 정책·사용자 묶음·관리자 탭·웹푸시 high). 정지 수신자 차단은 `NotificationDispatcherTest`의 전 종류 × 수신자 상태 가드가 새 종류도 포함한다.
- 통합: `CompanionRiskPatternFullIntegrationTest`(턴 → 커밋 → 리스너 → 이력 1건, 동시 판정 6번 → 1건, 7일 경계, 역할 변경 정리).
