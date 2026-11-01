# 안부 말벗(checkin) 연동 - 1차 (2026-11-01)

> 근거: AI 계약 v0 = SilverBridgeAiServer PR #152 `docs/checkin-companion.md`(미머지). 정책·불변 규칙은 `.claude/rules/policy-checkin.md`.
> 2차(알림·평소 대비·보호자 조회)는 이 PR 머지 뒤 별도 PR.

## 1. 무엇을 만들었나

| API | 역할 | 설명 |
|---|---|---|
| `GET /api/guardian/ward/{wardId}/checkin/questions` | GUARDIAN | 시각 5개의 질문(정하지 않은 시각은 기본 질문, `custom=false`) |
| `PUT /api/guardian/ward/{wardId}/checkin/questions` | GUARDIAN | 바꿀 시각만 `{questions:[{slot:"09:00", question}]}`. 1~200자, 빈 문자열 = 기본 질문으로 |
| `POST /api/ward/checkin/sessions` | WARD | 지금 시각(KST)의 대화 시작/이어받기 → `{sessionId, slot, question, turnCount, maxTurns, endsAt, resumed, finishRequired}` |
| `POST /api/ward/checkin/sessions/{sessionId}/turns` | WARD | multipart `audio`(webm·ogg·wav·m4a, 5MB·60초) + 선택 `frames`(JPEG 8장·300KB) → `{turnNo, transcript, reply, remainingTurns, finishRequired, emergency, selfHarm}` |
| `POST /api/ward/checkin/sessions/{sessionId}/finish` | WARD | `{selfReport}`(선택, `JOY`·`CALM`·`LONELY`·`LOW_ENERGY`·`WORRIED`) → `{slot, checkDate, emotion, selfReport, endReason}` |
| `GET·PUT /api/ward/checkin/setting` | WARD | `{faceConsent}` 기본 false |

모든 API는 꺼진 서버에서 503 `CHECKIN_DISABLED`. 오류 코드 전체는 Swagger 설명과 `ErrorCode`의 `CHECKIN_*`.

### 흐름 (FE)

1. 시작 → `question`을 읽어 드린다. `finishRequired=true`면 바로 4.
2. 녹음 → turns. `reply`를 읽어 드린다. `CHECKIN_SPEECH_NOT_RECOGNIZED`(422)면 "한 번 더 말씀해 주세요"(턴은 소모되지 않음).
3. `finishRequired=true` 또는 어르신이 "그만"하시면 4.
4. 기분 이모지(선택) → finish.

## 2. 데이터 (V65)

| 테이블 | 내용 | 비고 |
|---|---|---|
| `checkin_question` | 피보호자 × 시각 질문, `updated_by` | UNIQUE(ward, slot), 보호자 탈퇴 시 `updated_by` SET NULL |
| `checkin_setting` | `face_consent` | 피보호자당 1행 |
| `checkin_session` | 무작위 `session_id`, 날짜(KST)·시각·질문 사본·상태·턴 수·`ai_done`·턴 중 위험 | 진행 중은 (ward, date, slot) 부분 UNIQUE, `@Version` |
| `checkin_result` | 정서·본인 기분·신호 5칸(영문 코드)·위험·요약 고정 문장·근거(JSONB `[{signal, turnNo}]`)·목소리 평균 5개 | UNIQUE(ward, date, slot)·UNIQUE(session), 값은 CHECK로 고정 |

모두 피보호자 탈퇴 시 CASCADE. 받아쓴 글자·AI 답·음성·사진은 저장하지 않는다.

생활 신호 코드: sleep `GOOD/BAD` · meal `ATE/SKIPPED` · pain `YES/NO` · mood `GOOD/NORMAL/BAD` · lonely `YES/NO` (AI "모름"·모르는 값 = null).

## 3. AI 계약 대조 (PHASE 0 실측, 문서와 다른 점)

- `slot`은 `"09:00"` 형식 5종만(그 밖은 422).
- finish `reason`은 `TURN_LIMIT`·`TIME_LIMIT`·`USER_END`·`AI_END` 열거 - BE가 정해서 보낸다.
- finish 응답에 `turns`·`endReason`이 더 있고 목소리 키는 `avgAnswerSec`·`avgSpeechSec`·`avgPauseRatio`·`avgCharsPerSec`·`avgLoudnessDb`.
- 필드 검증 실패는 래퍼 없는 FastAPI `detail`(422) - BE는 상태 코드·`errorCode`만 보므로 영향 없음.
- 대답 없이 finish하면 200 `emotion: "판단 보류"`.
- multipart 사진 파트 이름은 `frames`(반복).

## 4. 설정 (`checkin.*`)

| 환경 변수 | 기본 | 설명 |
|---|---|---|
| `CHECKIN_ENABLED` | false | 킬 스위치 |
| `CHECKIN_AI_BASE_URL` | (없음) | 말벗 AI 주소 - vkcs: `http://172.18.0.1:16020`(호스트 전달기 → 사용자 PC 터널 → DGX) |
| `CHECKIN_AI_API_KEY` | (없음) | 말벗 AI 키(챗봇 `AI_API_KEY`와 별개) |
| `CHECKIN_TURN_TIMEOUT`·`_FINISH_TIMEOUT`·`_START_TIMEOUT` | 20s·30s·10s | |
| `CHECKIN_SESSION_DURATION`·`_TURN_GRACE`·`_MAX_TURNS` | 2m·60s·8 | |
| `CHECKIN_MAX_CONCURRENT`·`_MAX_PER_USER`·`_MAX_FRAMES` | 6·1·8 | |

**배포**: vkcs-linux `.env.dev`에만 위 세 키를 넣는다. gosky는 넣지 않는다(코드상 503·스케줄러 없음·탈퇴 정리도 건너뜀).
연결 경로는 사용자 PC(WSL) SSH 터널에 의존하므로 PC가 꺼지면 503 `CHECKIN_UNAVAILABLE`이 된다.

## 5. 테스트

- 단위: `CheckinConversationServiceTest`(킬 스위치 3·시작 6·턴 12·정리 6), `CheckinQuestionServiceTest`(9), `CheckinAiClientTest`(실제 HTTP: 키 헤더·사진 파트·오류 코드 매핑·리다이렉트 미추종·시간 초과·삭제 200만 성공·매직 바이트), `CheckinAiProjectionTest`(허용 목록·신호 코드 ↔ CHECK), `CheckinCheckSyncTest`(enum ↔ CHECK), `CheckinLogGuardTest`, `SecurityConfigAccessDeniedTest`(turns URL WARD 전용), `AiMemberDataPurgeListenerTest`(말벗 삭제 켜짐·건너뜀·항목별 독립).
- 결과·실제 AI 대화 시험은 아래 §6에 PR마다 적는다.

## 6. 결과 기록

### 1차 - PR #427 (머지 `827eddd`, 2026-11-01)

- 로컬 `./gradlew build`: 테스트 2,244개 통과. vkcs 통합 테스트(`integration-test.sh`): 첫 실행에서 `WithdrawalAiPurgeIntegrationTest` 실패(슬라이스 컨텍스트에 말벗 빈 없음) → 목 추가(`54951db`) 후 통과.
- vkcs CD 성공, V65 적용(0.13초). `.env.dev`에 `CHECKIN_ENABLED`·`CHECKIN_AI_BASE_URL`·`CHECKIN_AI_API_KEY` 추가(백업 `.env.dev.bak-20261101-1705`) 후 `up -d api`.
- gosky: 배포·설정하지 않음(`CHECKIN_` 키 0개, HEAD `8458e15`). pull해도 기본 꺼짐·주소 없음이라 503이고 탈퇴 정리도 건너뛴다.
- **실제 AI 대화 시험**(vkcs `dmu-dev-api` → 172.18.0.1:16020 → DGX, aihub WAV 3.3초, 시험 피보호자 `CKT001`):

| 단계 | 결과 |
|---|---|
| 설정 조회 | 200 `faceConsent:false` |
| 시작 | 200 시각 `15:00`, 세션 ID `ck_…` |
| 턴 1·2 | 200, 받아쓰기 11자·답 57/47자, BE 측정 974/624ms |
| 정리 | 200 정서 `CALM`·본인 `CALM`·`USER_END`, 결과 행 저장(요약 22자·목소리 평균 있음·신호 null) |
| 다시 정리 / 다시 시작 | 200 같은 결과(멱등) / 409 `CHECKIN_ALREADY_DONE` |
| 탈퇴(API) | 200 → BE 행 0건(CASCADE), `[AI-PURGE] item=checkin-logs` 완료, AI 잔여 0 |

- 로그 확인: `[CHECKIN]` 줄에는 wardId·세션 ID·턴 번호·소요 시간·정서 코드만 있고 대화 내용은 없다.

## 7. 2차 - 알림·평소 대비·보호자 조회 (V66)

| API | 역할 | 설명 |
|---|---|---|
| `GET·PUT /api/guardian/ward/{wardId}/checkin/setting` | GUARDIAN | `{promptEnabled}` 안부 알림 받기(기본 false) |
| `GET /api/guardian/checkin?wardId=&date=` | GUARDIAN | 시각별 상태(`DONE`·`IN_PROGRESS`·`OPEN`·`UPCOMING`·`MISSED`·`NO_RECORD`·`BEFORE_CONNECTION`) + 정서·본인 기분·신호·위험·확인 필요(근거 코드)·요약·근거 |
| `GET /api/guardian/checkin/weekly?wardId=&endDate=` | GUARDIAN | 7일 마친 수·미응답 수·확인 필요 수·위험·시각별 정서 |
| `GET·PUT /api/ward/checkin/setting` | WARD | 응답·요청에 `promptEnabled` 추가(본인이 끌 수 있음, 요청은 보낸 항목만) |
| `GET·PUT /api/guardian/notification-type-setting` | GUARDIAN | `checkinMissed` 추가(기본 true) |

- **피보호자 안부 알림** `CHECKIN_PROMPT`: `CheckinPromptPlanner` 1분 주기, 켠 피보호자 중 이용 중·ACTIVE 보호자 있음·결과 없음인 사람에게 시각 + 0~4분(피보호자별 고정)에 FCM. 선점 후 발송(`checkin_prompt_log`).
- **응답 창 1시간**: 창 밖 시작은 409 `CHECKIN_NOT_OPEN`(창 안에 말을 나눈 대화는 이어받아 정리 가능).
- **평소 대비 판정** `CheckinAttentionJudge`: 정리 때 그날 전 14일 결과로 계산해 `checkin_result.attention`·`attention_reasons`에 저장. 근거 둘 이상 + 점수 2.0 이상(생활 신호·본인 기분 1.0, AI 정서·목소리 0.5), 위험은 곧바로.
- **보호자 알림**: 위험 `CHECKIN_RISK`(턴에서 처음 올라갈 때 바로, 긴급 executor, 강제 FCM + 설정) / 확인 필요 `CHECKIN_ATTENTION`(위험이 아닐 때만) / 미응답 `CHECKIN_MISSED`(창 닫힌 뒤 1시간 안, 하루 한 번, 끌 수 있음). 보호자별 선점 후 발송(`checkin_guardian_alert_log`).
- 문구: 위험 "[긴급] {이름}님 안부 대화 / 안부 대화 중 도움이 필요할 수 있는 말씀이 있었어요." · 확인 필요 "안부 확인 · {이름}님 / 오늘 오후 3시 안부 대화가 평소와 조금 달랐어요." · 미응답 "… 안부 인사에 아직 답이 없으셨어요." · 피보호자 "안부 인사 드려요. 앱에서 잠깐 이야기 나눠요."(90바이트 안).
- **가정(사용자 확인 전 기본값)**: 안부 알림은 기본 꺼짐(보호자가 켬) / 피보호자 알림은 FCM만(문자 없음) / 관리자 이력은 "기타" 탭.

### 2차 결과

(PR·머지·vkcs 반영 결과는 마무리 단계에서 추가)
