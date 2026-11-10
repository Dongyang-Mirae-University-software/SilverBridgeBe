# 기능 점검 - 안부 말벗 테스트 계정 (PR #461, 템플릿 B)

> 2026-11-10 · 대상 `b235e496`(diff `7bcf9514..b235e496 -- src/`) · 점검 Sonnet 하위 에이전트(격리 worktree) + 주 세션이 M-1·M-3 근거를 코드로 다시 확인
> 기능 설명 `docs/(2026-11-10) feature-companion-test-mode.md`

## 판정: 조건부 PASS · 🔴 0 · 🟠 0 · 🟡 3 · 🟢 5

모두 테스트 계정에만 해당하고 일반 계정에는 영향이 없다. 말벗 단위 테스트 91건 기준선 통과.

## 발견 사항

| 등급 | ID | 위치 | 문제 | 제안 |
|---|---|---|---|---|
| 🟡 | M-1 | `CompanionService.openTest`, `CompanionSessionWriter.claimTest` | 같은 시간대 `openTest` 요청 2개가 동시에 오면(더블탭) 둘 다 "진행 중 테스트 대화 없음"으로 판단한다. 그래서 테스트 행 2개와 AI 대화 2개가 생긴다. 쓰지 않은 쪽은 10분 뒤 마감 스케줄러가 NO_RESPONSE로 닫는다. 그 행이 시작 시각에서 이기면 화면·분포가 그 고아 대화로 바뀔 수 있다(추정). 이중 위험 알림이나 UNIQUE 위반은 없다 | 부분 UNIQUE `(ward_id, session_date, slot) WHERE is_test AND status='OPEN'`을 두고 위반 시 기존 행을 다시 조회(일반 `open`과 같은 형태, 새 마이그레이션) / 또는 수용 |
| 🟡 | M-2 | `CompanionFullIntegrationTest` | 같은 시간대에 테스트 행이 있을 때 일반 `open()`이 맞게 도는지 실 DB로 보는 테스트가 없다. 결함 주입 a)는 목 메서드 이름이 달라서 잡혔을 뿐이다. 실제로 조회가 테스트 행을 보게 되면 `IncorrectResultSize`가 난다 | 통합 시나리오 추가: 테스트 행이 있는 상태에서 일반 `open()` 성공 + 일반 `SLOT_DONE` 유지 |
| 🟡 | M-3 | `CompanionPromptScheduler.sendOne:93` | 정각 안내가 `promptContent(slot, false)`를 부르는지 고정하는 테스트가 없다(결함 주입 c2 미검출). 정각 푸시에 `test=true`가 실리면 기기가 테스트 시작으로 잇는다 | `sendPrompts` 경로에서 디스패처 data에 `test`가 없음을 단언하는 테스트 |
| 🟢 | L-1 | `CompanionService.sendTestPrompt` | 대상이 아니거나(정지·피보호자 아님) 알림 설정을 껐거나 FCM 토큰이 없어도 204를 준다. 그래서 QA가 "왜 안 왔는지" 알 수 없다 | 문서에 명시하거나, 대상이 아니면 409 |
| 🟢 | L-2 | `CompanionGuardianService.today` | 같은 시간대에서 일반 대화(대답 있음) 뒤에 대답 없는 테스트 대화가 오면, 보호자 `answeredCount`·`current`가 최신 테스트 행 기준이 된다. 하루 기분·연속 일수·주의 알림은 모든 행을 보므로 영향이 없다 | 수용(테스트 전용) |
| 🟢 | L-3 | `requireTestWard` | 역할은 보지 않는다. 보호자로 바뀐 뒤에는 컨트롤러 `hasRole('WARD')`·토큰 무효화·기록 삭제가 막는다 | 현상 유지 |
| 🟢 | L-4 | 운영 | ID 기준 목록이라, 탈퇴 뒤 같은 ID로 재가입하면 테스트 권한을 물려받는다(gosky dev 한정이라 영향이 작다) | 정책 문서에 한 줄 |
| 🟢 | L-5 | `WardCompanionController.sendTestPrompt` Swagger | 공통 오류 설명(`COMMON_ERRORS`) 누락 | `+ COMMON_ERRORS` |

## 결함 주입

| 주입 | 결과 |
|---|---|
| a) 일반 `open()`이 테스트 행도 보는 조회 | 검출(단위 1건 - 목 이름 차이, M-2) |
| b) V68 부분 인덱스 조건 제거 | 검출(논리 판단 - `일반_대화는_시간대당_한_번_그대로`의 테스트 행 2개 INSERT가 실패, Docker가 없어 실행은 안 함) |
| c1) `promptContent` 일반 분기에 test 키 | 검출(`promptContentData`) |
| c2) `sendOne`이 `promptContent(slot, true)` | **미검출**(M-3) |
| d) analysis를 일반 계정에도 | 검출(`normalWardTurnHasNoAnalysis`) |

## 정상으로 확인한 항목

- 단일 결과 조회: `Optional` 단건 조회는 일반 행 전용(`...AndTestFalse`)과 테스트 `findFirst`뿐이다. `toMap`은 모두 merge 함수가 있다. `CompanionSession` 사용처는 companion 패키지 안에만 있다.
- 집계: 관리자 응답률·연속·마지막 응답·알림 수, `CompanionMoodDays`·주의 알림, 마감 스케줄러, 탈퇴·역할 변경 정리는 여러 행에서도 안전하다.
- 접근 차단: 말벗 꺼짐 503, 목록 밖 403, analysis도 `isTestWard`가 막는다.
- analysis 유출 없음: DB 칸 없음, 로그는 id·slot·turnNo만, 알림 data 없음, 통합 테스트가 로그 원문 미노출을 단언한다. 응답에는 일반 Map만 싣는다(Jackson 2 타입 없음).
- 테스트 안내: 정각 안내와 같은 `SETTINGS_ONLY`·FCM·정지 수신자 차단 경로다. 선점 기록이 없다. 속도 제한은 Redis 장애 시 fail-open(기존 정책)이다.
- V68: 같은 이름으로 부분 인덱스를 다시 만든다(가역).
