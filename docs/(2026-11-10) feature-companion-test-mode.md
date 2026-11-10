# 안부 말벗 테스트 계정 (최종 QA 지원, 2026-11-10)

> 브랜치 `feature/companion-test-mode`. 실제 서비스 흐름을 테스트 계정이 원하는 때에 그대로 돌려 본다. 일반 계정 동작은 바뀌지 않는다.

## 켜는 법

- `COMPANION_TEST_WARD_IDS`(쉼표 목록, 기본 빈 값 = 꺼짐). 모든 계정을 뜻하는 `*`는 받지 않는다(기동 실패).
- `COMPANION_ENABLED` 등 말벗 기본 설정이 켜져 있어야 한다. **운영 서버에는 넣지 않는다** - gosky dev `.env.dev`에만.

## API (FE와 맞춘 형식)

| API | 내용 |
|---|---|
| `GET /api/ward/companion/today` | `testMode`(boolean, 항상 있음) 추가 |
| `POST /api/ward/companion/test/sessions` `{"slot":"15:00"}` | 지금 시각과 상관없이 고른 시간대의 오늘 질문으로 대화를 연다. 응답은 기존 대화 시작과 같다. 같은 시간대에 진행 중인 테스트 대화가 있으면 그 대화를 이어 간다(`resumed=true`, 사용자 결정). 끝났거나 끊겼으면 새로 연다(몇 번이든) |
| `POST /api/ward/companion/test/prompt` `{"slot":"15:00"}` | 204. 자기 기기로 `COMPANION_PROMPT`(정각 안내와 같은 문구·FCM만·설정 기반). data는 `{type, slot, test:"true"}`, 정각 안내 data는 그대로 `{type, slot}`. 분당 3회·시간당 30회 |
| 턴(`reply`·`reply/audio`) | 테스트 계정이면 `analysis` (일반 계정은 필드 자체가 없음) |
| 마치기(`finish`) | 테스트 계정이면 `analysis` |

오류: 테스트 계정이 아니면 403 `COMPANION_TEST_NOT_ALLOWED`("테스트 계정만 쓸 수 있습니다."), 시간대 형식 오류는 400 `COMPANION_SLOT_INVALID`, 말벗이 꺼져 있으면 503 `COMPANION_DISABLED`.

### 턴 analysis - AI 값 그대로 (AI `companion_router.py _turn_response`, gosky `38ab09c`가 들어 있는 main 기준)

| 필드 | AI 값 |
|---|---|
| `turnLabel` | `turnResult.label` (한글: 기쁨·평온·걱정·우울·판단 보류 등, 무응답이면 null) |
| `turnSource` | `turnResult.source` (TEXT·FACE·NO_RESPONSE 등) |
| `face` | `face` 그대로: `label`·`confidence`·`frameCount`·`usable`·`totalFrames`·`facesFound` (사진이 없거나 대답이 비면 AI가 `usable=false`·0을 준다) |
| `riskFlag` | 최상위 `riskFlag` 또는 `signals.riskFlag` (BE의 턴 위험 규칙과 같음) |
| `signals` | `signals` 그대로: `emotion`·`activityMotivation`·`socialContact`·`physicalCondition`·`futureOrientation`·`responseEvasion`·`riskFlag`. 무응답·되묻기 턴은 null |

**AI가 주지 않아 싣지 못한 값**: `textMood`(글 기분 코드). AI는 글 분류 결과를 `turnResult`에 합쳐서만 주고 따로 내보내지 않는다. 필요하면 AI `_turn_response`에 필드를 더해야 한다(AI 범위).

### 마치기 analysis - 저장된 결과 값

`mood`(영문 코드, 판단 불가 null) · `turnCount` · `answeredCount` · `noResponseCount` · `riskFlag` · `alertLevel`(URGENT·INFO) · `test`(테스트 대화 여부). 저장된 값이라 이미 마친 대화를 다시 불러도 같다.

## 저장·집계

- **V68**(가역): `companion_session.is_test BOOLEAN NOT NULL DEFAULT false`, 시간대당 1회 UNIQUE `uq_companion_session_slot`를 `WHERE is_test = false` 부분 유니크 인덱스로 바꿨다. 되돌리기: 테스트 행을 지운 뒤 원래 제약을 다시 만든다.
  - 대안 비교: B. 테스트 시작 때 옛 테스트 행 삭제(마이그레이션 없음) → 집계 기록이 사라지고 같은 시간대의 일반 대화와 충돌 / C. UNIQUE에 is_test 포함 → 테스트 반복이 여전히 막힘.
- **테스트 대화는 일반 대화와 똑같이 저장·집계한다**: 보호자 today·하루 기분·연속 일수·주의 알림·관리자 집계·위험 알림(대화당 1번) 모두 포함. `is_test`는 표시만 해 두었다(나중에 걸러 볼 수 있게).
- 같은 시간대에 대화가 여러 행이면 피보호자·보호자 today는 **가장 최근에 시작한 대화**를 보여 준다(`CompanionService.latestBySlot`). 관리자 집계는 원래 (피보호자, 시간대) 묶음·피보호자별 마지막 대화 기준이라 그대로 맞다.
- 일반 열기는 `findByWardIdAndSessionDateAndSlotAndTestFalse`로 일반 대화만 본다(테스트 행이 섞이면 Optional 조회가 터지는 것 방지).
- 테스트 안내는 **`companion_prompt_log` 선점 기록을 남기지 않는다**(사용자 결정): 응답률은 정각 안내 기준 그대로이고, 같은 시간대를 반복해 보낼 수 있다.
- `analysis`는 응답 DTO에만 있다. DB 칸·로그·알림 어디에도 남기지 않는다(원문 미저장 결정 B와 같은 기준).

## 바뀌지 않는 것

일반 계정의 60분 창·시간대당 1회·정각 안내 스케줄과 data·원문 미저장·위험 알림 1회·삭제 대기열·저장하는 AI 값(riskFlag·alertLevel). 테스트 계정도 일반 `POST /sessions`는 창 규칙을 따른다.

## 테스트

- 단위 `CompanionTestModeTest`(창 밖 시작·반복·이어 가기·403·400·창 규칙·testMode·시간대별 최근 대화·사진 장수 전달·analysis·일반 계정 analysis 없음·Jackson 3 직렬화·`*` 거부·안내 문구 data), `CompanionPromptSchedulerTest`(디스패처 1건·선점 기록 없음·정지 계정 제외).
- 통합 `CompanionFullIntegrationTest`: 같은 시간대 테스트 대화 2번 저장·위험 알림 1번·보호자 화면·관리자 분포·알림 수·테스트 안내 2번 = 디스패처 2건·prompt_log 0·analysis가 DB 칸·로그에 없음, V68 부분 UNIQUE(일반 2번째 행 거부·테스트 행 여러 개 허용).

## 기능 점검 제안 (템플릿 B 초안 - "시작해" 후 실행)

- 범위: 이 브랜치 diff + 건드린 공용 코드의 사용처(`CompanionService.open`·`today`, `CompanionGuardianService.today`, `CompanionStatsService`, `CompanionPromptScheduler`, `CompanionSessionRepository`, V68).
- 볼 것
  1. 같은 시간대 여러 행일 때 `findByWardIdAndSessionDate`를 쓰는 곳이 모두 맞는지(보호자 `answeredCount`·`current`, 관리자 `noDialogueWards`·`alertSentCount`·응답률, 주의 알림 `CompanionMoodDays`, 마감 스케줄러).
  2. 테스트 계정이 같은 시간대에 일반 대화와 테스트 대화를 함께 가질 때 피보호자 today·보호자 today 표시와 일반 열기의 `SLOT_DONE` 판정.
  3. `isTestWard`가 꺼짐·목록 밖·역할 변경·탈퇴 뒤에 analysis·테스트 API를 막는지(목록에 남은 ID가 보호자가 되면 컨트롤러 WARD 제한만 남음).
  4. analysis가 응답 밖(로그·예외 메시지·알림 data)으로 새지 않는지 - `LogRawExceptionGuardTest` 관점.
  5. 테스트 안내가 정지 계정·FCM 토큰 없음·알림 설정 끔에서 일반 안내와 같게 동작하는지, 속도 제한 Redis 장애 시 동작.
  6. 결함 주입: 일반 열기를 `TestFalse` 없는 조회로 되돌림 / 부분 인덱스 조건 제거 / 정각 안내 data에 test 추가 / analysis를 일반 계정에도 실음 → 테스트가 잡는지.
- [모델: sonnet] (판정·알림 경로를 고치는 조치가 나오면 fable)
