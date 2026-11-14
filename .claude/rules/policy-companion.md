---
paths:
  - "**/domain/companion/**"
  - "**/*Companion*"
  - "**/V65__add_companion.sql"
---

# 도메인 정책 - 안부 말벗

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (2026-11-04, PR-A)

- 피보호자가 하루 5번(09·12·15·18·21시 KST, 각 60분 창) AI 말벗과 최대 8턴 대화한다. 기기 → BE `/api/ward/companion/*` → AI `/companion/*`(재희 AI 계약, gosky 메인 AI 서버에 들어감 - 2026-11-04 결정).
- 질문은 BE가 고른다: `companion_question`(V65 초기 19문장, 근거 `docs/(2026-11-04) companion-question-research.md`)에서 시간대 활성 질문 중 `epochDay mod 개수`.
- 대화 결과(종료 이유·기분·턴 수·위험 여부)는 `companion_session`에 저장한다. 위험 신호는 보호자에게 `COMPANION_RISK`.
- 스위치 `COMPANION_ENABLED`(기본 꺼짐) + `COMPANION_AI_BASE_URL`·`COMPANION_AI_API_KEY`(기본값 없음). 상세 `docs/(2026-11-04) feature-companion-relay.md`.
- **말벗 목소리 중계(2026-11-13)**: `GET /api/ward/companion/sessions/{sessionId}/audio/{index}`(WARD·본인 대화만·끝난 대화 포함) → AI TTS(#281). 대화 열기·턴 응답 `audio {index}|null`(고정 문장 턴·이어 가기는 null). 형식 mpeg·wav·ogg·1MB(넘으면 502)·AI 404 → 404·그 밖 503(`COMPANION_AUDIO_*`), 8초·동시 8(피보호자당 2, 점검 M-1)·리다이렉트 끔, AI에 대화 주인 `X-Companion-Ward-Id` 헤더(점검 L-2), 오류는 `Accept`와 무관하게 JSON(점검 L-1 - 엔드포인트 안에서만 처리, 컨트롤러 전체 `@ExceptionHandler`로 옮기지 말 것: 429 `Retry-After`를 가로챈다), **소리는 저장하지 않는다**. 상세 `docs/(2026-11-13) feature-companion-tts-relay.md`. **말벗 AI 하나를 BE 두 곳(vkcs·gosky)이 같이 쓰지 말 것** - AI가 소리를 BE 대화 번호만으로 찾아 같은 번호의 남의 소리가 나간다(점검 L-2, 지금 vkcs 말벗 꺼짐). 점검 `docs/(2026-11-13) audit-companion-tts-relay.md`.
- **말벗 목소리 구글 엔진(2026-11-14, AI #294)**: gosky AI는 `COMPANION_TTS_ENGINE=google`(Gemini 3.8 Flash TTS·Gacrux - 11/14 Kore에서 변경(60~80대 고음 청력 저하 고려, 여성 후보 중 가장 낮은 음높이 143Hz, 속도 초당 약 3.6음절 유지), Agent Platform·서비스 계정에 묶인 API 키 - 일반 키는 401)이다. **말벗 문장(어르신 말을 되짚는 모델 대답 포함)이 구글로 전송된다**(사용자 결정 2026-11-14: 화면 안내 문구는 팀이 정한다, 구글 쪽 보관·학습 정책은 미확인). 실패(시간 초과 8초·4xx·5xx·429·하루 5만 자)하면 그 문장부터 F1, 전환된 대화는 끝까지 F1, 429·상한은 10분 멈춤. BE 계약·형식(mp3 24kHz mono) 무변경. 실측 p50 2.65초·p95 3.66초(F1 0.85·1.69초) - 구글 시간 초과 뒤 F1이면 BE 8초 대기를 넘어 그 문장은 503 → FE 읽기. 되돌리기는 AI `.env` ENGINE=supertonic 한 줄 + 재시작. 결과 AI `docs/eval-results/2026-11-14-companion-google-tts.md`.
- **PR-B(2026-11-04)**: 보호자 `GET /api/guardian/ward/{wardId}/companion/today`(지금 기분·시간대별 질문·상태·기분·확인할 일), 주의 알림 `COMPANION_NOTICE`(걱정·우울 3일 이상, 하루 1번, V66), 관리자 `GET /api/admin/ai-stats/companion`(오늘 집계). 상세 `docs/(2026-11-04) feature-companion-screens.md`.

## 하루 기분·연속 일수·주의 알림 (2026-11-04 사용자 결정, PR-B)

- **하루 기분** = 그날 마지막으로 판정된 대화의 기분(판단 불가 건너뜀). **연속 일수** = 기준일(오늘 판정이 있으면 오늘, 없으면 어제)부터 거꾸로 걱정·우울인 날 수, **판정된 대화가 없는 날은 끊는다** - 대화가 없는 날뿐 아니라 대화했지만 모두 판단 불가(`UNKNOWN`)인 날도 끊는다(2026-11-09 사용자 확정, 건너뛰지 않음 - `CompanionMoodDaysTest.unknownOnlyDayBreaks`). 규칙은 `CompanionMoodDays` 한 곳 - 보호자 화면·주의 알림·관리자 집계가 같이 쓴다. 따로 계산하지 말 것.
- **지금 기분·관리자 기분 분포는 하루 기분과 다르다**(의도, 2026-11-09 점검): 보호자 `current`와 관리자 `moodDistribution`은 판단 불가를 **포함한** 마지막 마친 대화 기준이다("지금 기분" = 가장 최근 대화, 판단 불가면 "확인할 수 없어요"·`unknown`). 그래서 같은 어르신이 분포에서는 `unknown`이면서 이어지는 어르신(하루 기분·연속 일수 기준)에 오를 수 있다.
- **주의 알림**: 3일째부터 날마다 하루 1번(`companion_notice_log` (ward, date) UNIQUE 선점). FCM만·설정 기반·일반 풀. 보호자가 `companionNotice`로 끌 수 있고 게이트는 `CompanionNoticeListener`가 건다(디스패처는 모른다). 받을 보호자가 없으면 선점하지 않는다. 문구에 병명·원문 금지("걱정스럽거나 가라앉은 기분이 N일째").
  - **연속 일수는 보호자마다 그 보호자의 지금 연결 이후 대화로 센다**(D-1, 2026-11-09 PR-B 점검 M-1): 피보호자 전체 기록으로 세면 새로·다시 연결한 보호자에게 연결 전 기분 일수가 푸시로 드러난다(화면 주의 칸은 이미 연결 이후만). 필터는 `CompanionMoodDays.startedSince` 한 곳 - 화면·알림이 같이 쓴다. 보호자마다 일수가 다를 수 있고(각자 화면과 같은 숫자), 선점은 피보호자·날짜 1번·기록 일수는 보낸 일수 중 최댓값. 관리자 "이어지는 어르신"은 D-1 대상이 아니다(피보호자 전체 기록).
- **보호자 조회 권한**: ACTIVE 연결 + **지금 연결 이후 대화만**(D-1, `ConnectionService.findActiveConnectedAt`). 남의 피보호자 403 `COMPANION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`. 대화 글은 `sessions/{id}`에서만, 동의 시에만(아래 "대화 내용 저장" 절).
- **관리자 집계**: BE DB로 계산(AI 호출 없음). 응답률 = 보낸 질문 안내 중 그 (피보호자, 시간대) 대화에 대답이 있는 비율, 안내가 없으면 null. 어르신 이름은 BE가 붙인다. 원문·요약 금지.
- **질문 안내 대상**: `COMPANION_PROMPT_WARD_IDS`에 `*`면 이용 중인 모든 피보호자. 피보호자 끄기 설정이 생기기 전에는 기본으로 켜지 말 것.

## 여러 날 위험 누적 알림 (2026-11-11 사용자 결정)

- **규칙**: 최근 7일(오늘 포함, `session_date`) 중 `companion_session.max_risk_prob`(턴 `riskProbability` 최댓값, V70) ≥ 0.97인 **서로 다른 날이 2일 이상**이고, 그중 **위험 알림(`risk_notified_at`)이 나가지 않은 날이 1일 이상**이면 `COMPANION_RISK_PATTERN`. 판정은 `CompanionRiskPattern` 한 곳. 대화 상태·테스트 대화를 가리지 않는다. 보호자마다 지금 연결 이후 대화만(D-1, `startedSince`).
- **시점**: 턴 기록 트랜잭션에서 그 턴 확률이 기준 이상이면 이벤트 → AFTER_COMMIT `@Async("urgentNotificationExecutor")`. 위험 알림 선점을 먼저 해 같은 커밋에 들어간다. `riskProbability`는 v9 척도만 나오고(AI가 v4·실패·무응답 턴은 null), 마치기 응답에는 없다.
- **7일에 1번**: `companion_risk_pattern_notice`(피보호자당 1행) 조건부 UPSERT 한 문장(`last_notified_date <= 오늘-7`일 때만 이김). 받을 보호자가 없으면 선점하지 않는다. (ward, date) UNIQUE + 조회로 바꾸지 말 것 - 자정 경계 경합이 생긴다. 역할 변경 정리에 포함, 탈퇴는 FK CASCADE.
- **채널**: `COMPANION_RISK`와 같은 `FORCED_PUSH_PLUS_SETTINGS`·FCM·문자, 웹푸시 high, 사용자 묶음 URGENT. 보호자 설정 항목은 따로 없고 `companionRisk`(필수)에 포함. 문구·로그·data에 확률·원문 금지(일수만). 관리자 "알림 보냄" 수에 포함.
- **영향 범위 점검 반영 (2026-11-11, `docs/(2026-11-11) audit-impact-companion-risk-pattern.md`)**:
  - **M-1 계정 이용 중인 보호자만 대상**: 누적·주의 알림 리스너는 선점 전에 `findIdsByIdInAndStatus(..., ACTIVE)`로 거른다(이상감지 재촉 L-12와 같음). 이용 제한·탈퇴 진행 보호자만 남으면 선점하지 않는다. 디스패처 차단은 그대로 둔다(2중 방어).
  - **L-1 같은 순간 두 건 방지**: 판정을 부른 턴이 방금 위험 알림 선점에 이겼으면 누적 판정 이벤트를 내지 않는다(다음 높은 턴에서 다시 판정). 같은 대화에서 이미 위험 알림을 보낸 뒤의 높은 턴은 판정한다.
  - **L-3 수용**: 선점 후 발송이라 발송이 모두 실패해도 7일간 다시 보내지 않는다(위험 알림·주의 알림과 같은 방식, 이력에 `FAILED`).
  - **L-5 켤 때 주의**: 테스트 대화도 집계되므로 시험 피보호자가 높은 대답을 반복하면 연결된 시험 보호자에게 대체 문자(과금)가 갈 수 있다.
  - 고정: `CompanionRiskTypeParityTest`(위험 알림과 정책·채널·묶음·탭·이름·high 같음), `NotificationExecutorAssignmentTest`(긴급 풀).
- **스위치** `COMPANION_RISK_PATTERN_ENABLED`(기본 false) + `_THRESHOLD`·`_MIN_DAYS`·`_WINDOW_DAYS`(0.97·2·7). **수용한 한계**: 개발용 자료에서 안전한 주 알림 +3.6%p(목표 +2%p 미달), 실제 어르신 대화로는 검증 전(11/08 DGX 연구). 상세 `docs/(2026-11-11) feature-companion-risk-pattern.md`.

## 대화 내용 저장·보호자 대화 보기 (2026-11-11 사용자 결정)

- **근거**: 개인정보 보호법 제23조(건강 등 민감정보 별도 동의)·제21조(목적 달성·보유기간 경과 시 지체 없이 파기).
- **턴 저장(V71 `companion_turn`)**: 턴마다 1행 - 기분(`turnResult.moodCode` 또는 한글 `label`, 판단 보류 = null)은 **항상**, `bot_text`(그 대답 뒤 말벗이 한 말, 기기에 나간 문장 그대로)·`answer_text`(글 대답, 음성이면 AI 받아 적은 글)는 **동의 시에만**. 화면 표시용 문자열 그대로(정규화 금지). 같은 턴 번호는 건너뛴다(UNIQUE 위반으로 위험 알림 선점이 롤백되지 않게). 테스트 대화도 같은 규칙. 저장은 `CompanionTurnRecorder` 한 곳(턴 기록 트랜잭션에 합류, MANDATORY).
- **동의(V71 `companion_transcript_consent`, 피보호자당 1행)**: `GET/PUT /api/ward/companion/transcript-consent`(WARD, 말벗 스위치와 무관하게 동작 - 철회는 언제나 가능해야 한다). 기본 동의 없음. 동의 뒤 턴부터 저장(지난 턴 채우지 않음), 처음 동의 시각 유지(`ON CONFLICT DO NOTHING`).
- **철회 = 즉시 삭제**: 같은 트랜잭션에서 동의 행 DELETE → 그 피보호자 턴 글 UPDATE NULL(기분은 남김). **경합**: 턴 저장이 동의 행을 `FOR SHARE`로 읽고, 철회의 DELETE가 그 커밋을 기다린 뒤 비우므로 철회 직전 턴의 글도 남지 않는다(`CompanionTranscriptFullIntegrationTest`). 순서(DELETE 먼저)·잠금을 바꾸지 말 것.
- **30일 보관**: `CompanionTranscriptRetentionScheduler` 매일 **04:55**(`COMPANION_TRANSCRIPT_RETENTION_DAYS`, 기본 30)가 `created_at` 30일 지난 턴의 글만 비운다. 기분·대화 기록은 남는다. 스케줄러는 15종, 풀 3 유지(일일 작업 시각 겹침 없음).
- **정리**: 탈퇴 = FK CASCADE(users → 동의 행, users → session → turn). 역할 변경(피보호자 → 보호자) = `clearOnRoleChange`가 동의 행 삭제 + 대화 삭제(턴 CASCADE).
- **보호자 API**(모두 ACTIVE 연결·D-1·`[IDOR-ATTEMPT]`): `week`(오늘 포함 7일, 오래된 날부터, 하루 기분 = `CompanionMoodDays`) / `days/{date}`(today와 같은 모양, 7일 밖·형식 오류 400 `COMPANION_DATE_OUT_OF_RANGE`, 지난 날은 그날 끝 시각 기준) / `sessions/{id}`(7일 안, 다른 피보호자·연결 전·없음 = 404 `COMPANION_SESSION_NOT_FOUND` - 다른 피보호자는 `[IDOR-ATTEMPT]` WARN). `transcript`: 동의 없음 → `NO_CONSENT`(남은 글이 있어도 싣지 않음), 글 있음 → `AVAILABLE`, 30일 지남 → `EXPIRED`, 동의 전 시작 → `NO_CONSENT`. today·days 칸에 `sessionId`(nullable)만 더했다(사용자 결정 - 기존 필드 불변).
- **영향 범위 점검 반영 (2026-11-12, `docs/(2026-11-12) audit-impact-companion-transcript.md`)**:
  - **M-1 고지 문구**: Swagger·API 설명·DTO·주석에 "대답·응답 문장·받아 적은 글은 저장하지 않는다"를 쓰지 말 것 - "동의한 경우에만 저장, 30일 뒤 삭제, 철회 시 즉시 삭제"(음성·사진은 저장하지 않음). `CompanionTranscriptApiDocTest`가 고정.
  - **L-1 잠금 순서 = 동의 행 → 대화 행**: `recordTurn`은 대화 행을 바꾸기 전에 `CompanionTurnRecorder.lockConsent`를 부른다. native 쿼리는 실행 전에 전체 flush하므로(Hibernate 7, JPA 부트스트랩) 대화 행을 먼저 바꾸면 역할 변경 정리·탈퇴 purge와 교착이 생긴다. `CompanionSessionWriterTest.consentLockedBeforeSessionChange`가 고정. 탈퇴 purge의 CASCADE 순서는 DB가 정해 완전히 맞추지는 못한다(수용).
  - **L-2 테스트 대화 위험 알림 = 피보호자당 하루 1번**: 테스트 대화는 `claimTestRiskNotification`(같은 날 테스트 대화 중 이미 보낸 것이 없을 때만 이김), 일반 대화는 대화당 1번 그대로. 동시에 진행 중인 서로 다른 시간대 테스트 대화는 드물게 2건(수용). 숫자 1은 점검 조치 때 정한 값이다.
  - **L-3 고지 4항목**: 동의 GET/PUT 응답 `notice`(목적·저장 항목·보관 기간·동의하지 않을 권리, 보관 일수는 설정값). FE는 이 문구를 그대로 보여 준다 - 항목을 줄이지 말 것.
- 상세 `docs/(2026-11-11) feature-companion-transcript.md`.

## 불변 규칙

- **원문은 피보호자 동의 시에만 저장한다**(결정 B 2026-11-02를 2026-11-11 사용자 결정으로 변경 - 위 "대화 내용 저장" 절): 말벗 문장·대답 글·받아 적은 글은 `companion_turn.bot_text`·`answer_text`에만, 동의했을 때만 담는다. 음성·사진은 여전히 저장하지 않는다. **로그·알림 문구·감사 로그에는 동의와 무관하게 절대 넣지 말 것**(`CompanionTranscriptLogGuardTest`가 말벗 도메인 로그 인자를 본다).
- **질문 문장은 행을 고치지 않는다**: 바꿀 때는 `active=false` + 새 행(같은 문장 = 같은 번호, 응답률 집계 기준). 새 마이그레이션으로.
- **기분 판단 불가 = null**: `UNKNOWN`·0·"정상"으로 바꾸지 말 것(화면 "확인할 수 없어요"). AI 한글 값은 영문 코드로만 저장(결정 D). AI는 2026-11-09부터 `mood`·`moodCode`를 영문 코드(`JOY`·`CALM`·`WORRY`·`DEPRESSED`·`UNKNOWN`)로 준다 - `CompanionMood.fromAi`는 영문(대문자)·한글을 모두 받고 `UNKNOWN`·모르는 값은 null, `mood`가 비면 `moodCode`를 본다(`moodKo`는 읽지 않음).
- **종료 이유 `AVOIDED`**(2026-11-12 사용자 결정, V72): 어르신이 한 대화에서 두 번 말을 피해(AI #272 `COMPANION_AVOID_END_COUNT`) 말벗이 마무리한 대화. 끝내기를 누른 `USER_STOP`과 나눠 저장한다. 마감 스케줄러 변환(`asAbandoned`)은 `USER_STOP`만 바꾼다 - `AVOIDED`를 넣지 말 것. AI보다 BE를 먼저 배포한다(모르는 값은 `USER_STOP`으로 저장됨). 지금은 저장·응답에만 쓰고 화면·집계·알림에는 쓰지 않는다.
- **AI 판단 중 믿는 것은 `riskFlag`·`alertLevel`뿐**(결정 E). 주의(NOTICE, 걱정·우울 3일)는 PR-B에서 BE가 계산한다.
- **위험 알림은 대화당 1번**: `risk_notified_at` 조건부 UPDATE에 이긴 쪽만 이벤트를 낸다(턴에서 처음 본 때, 또는 마감 때 URGENT인데 아직 안 보낸 때). 문구에 원문·질병명·구체 내용 금지. `COMPANION_RISK`는 강제 푸시 + 설정, FCM·문자만(알림톡·이메일 금지), 긴급 풀(`urgentNotificationExecutor`).
  - **턴 위험 = 최상위 `riskFlag` 또는 `signals.riskFlag`**(2026-11-09): 확인(되묻기) 턴(`replySource="CONFIRM"`)은 `signals`가 null이고 낱말 규칙 결과를 최상위에만 싣는다. `signals`만 읽지 말 것 - 그 위험 알림이 마감 때로 늦어진다. AI 새 오류 코드 `ANSWER_TOO_LONG`·`FRAME_FORMAT_UNSUPPORTED`는 `COMPANION_ANSWER_TOO_LONG`·`COMPANION_FRAME_INVALID`(400)로 바꾼다.
- **질문 안내 `COMPANION_PROMPT`는 FCM만·설정 기반**: 하루 5건 다발성이라 문자·알림톡·강제 승격 금지. PR-B 전까지 `COMPANION_PROMPT_WARD_IDS` 허용 목록에만.
- **이어 가는 대화의 턴 수**(2026-11-12): 대화 열기 응답 `SessionResponse.turnCount` = `companion_session.turn_count`(기록된 마지막 턴 번호). 새 대화 0, 이어 가기(일반·테스트 모두 `resumeOrReject`)는 저장값. 기기는 다음 대답을 `turnCount + 1`번째로 보여 준다. 응답에 필드만 더했다(기존 필드 불변).
- **이어 가는 대화의 질문**(2026-11-14, AI 점검 M-1): 이어 가기 응답 `question`은 턴이 있으면 AI `GET /companion/dialogues/{id}`의 `lastQuestion`(마지막 말벗 문장 - AI가 다음 대답을 이 문장의 대답으로 해석한다), 턴 없음·AI 실패(3초 `state-call-timeout`)·빈 값·300자 초과면 여는 질문. 여는 질문으로 되돌리지 말 것(어르신 답과 AI 해석이 어긋난다). 로그에는 오류 코드 이름만.
- **AI 호출은 트랜잭션 밖**(최대 90초): 쓰기는 `CompanionSessionWriter`의 짧은 트랜잭션. 동시 상한 서버 8(503 `COMPANION_BUSY`)·대화 1(409 `COMPANION_TURN_IN_PROGRESS`), 단일 인스턴스 전제.
- **중계 제한 90초**(사용자 결정 2026-11-04): AI에 한 턴 60초를 요청했다. 300초(AI 단계별 제한 합)로 늘리지 말 것 - 요청 스레드를 5분 붙든다.
- **탈퇴 삭제는 대기열**: 탈퇴 트랜잭션 BEFORE_COMMIT에 `companion_purge_request`(FK 없음, ID만), 직후 1회 + 매시 35분 재시도(시도 안 한 것·오래 전 시도한 것부터, 첫 실패에서 중단, 24회마다 `[COMPANION-PURGE-STUCK]` ERROR). 스윕 purge·AI 장애에도 빠지지 않게 하려는 것이라 AFTER_COMMIT으로 옮기지 말 것. 스위치와 무관하게 주소·키만 있으면 지운다.
- **역할 변경(피보호자 → 보호자) = 말벗 기록 삭제 + AI 삭제 대기열**(2026-11-04 점검 M-1): `UserRoleChangedEvent` BEFORE_COMMIT에 `companion_session`·`companion_prompt_log`를 지우고 대기열에 넣는다(카메라·복약과 같은 역할 변경 정리 원칙). 탈퇴 대기열 조건은 "피보호자이거나 말벗 기록이 있으면" - 역할만 보고 거르지 말 것(옛 피보호자의 AI 데이터가 영구히 남는다).
- **진행 중인 대화만 고친다**(2026-11-04 점검 M-3): `CompanionSessionWriter.recordTurn`·`finish`는 OPEN이 아니면 아무것도 바꾸지 않는다. 서비스는 처리 자리를 잡은 **뒤** 대화를 다시 읽는다 - 자리 전에 읽은 스냅샷으로 결과를 쓰면 마감 스케줄러와 "마치기"가 겹칠 때 종료 이유가 덮인다.
- **위험 알림 1회 보장은 테스트가 지킨다**: `CompanionSessionWriterTest`(선점 이김·짐·끝난 대화) + `CompanionFullIntegrationTest`(턴 → 커밋 → 긴급 리스너 → 이력 1건, 마감 URGENT, 역할 변경·탈퇴 삭제). writer를 고치면 둘 다 돌릴 것.
- **보호자 알림 종류 설정**: `companionRisk`는 `required: true`로만 내린다(끌 수 없다, 2026-11-04 점검 L-3).
- **남의 대화는 403 `COMPANION_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]`**(2026-07-14 정책).
- **업로드**: 음성 WAV·WebM·MP3 5MB, 사진 JPEG·PNG 장당 1MB·5장, 앞부분 바이트로만 판별, 원래 파일 이름은 AI로 넘기지 않는다. 오디오 경로는 SecurityConfig에서 본문 파싱 전에 WARD만 통과. 합계가 서블릿 상한(10MB)을 넘으면 `COMPANION_UPLOAD_TOO_LARGE`(413, 2026-11-04 점검 L-1).

## 테스트 계정 (2026-11-10, 최종 QA 지원)

- **전용 목록 `COMPANION_TEST_WARD_IDS`**(기본 빈 값 = 꺼짐, `*` 불가 - 기동 실패). **운영 서버 `.env`에 넣지 말 것** - gosky dev `.env.dev`에만. 판정은 `CompanionProperties.isTestWard`(말벗 켜짐 + 목록 포함) 한 곳.
- 테스트 계정만: `POST /api/ward/companion/test/sessions {slot}`(창 없이 고른 시간대의 오늘 질문, 반복 가능, 같은 시간대에 진행 중인 테스트 대화는 이어 감) / `POST .../test/prompt {slot}`(204) / `today.testMode` / 턴·마치기 `analysis`. 나머지는 403 `COMPANION_TEST_NOT_ALLOWED`. 테스트 계정도 일반 `POST /sessions`는 창 규칙을 따른다.
- **테스트 대화는 일반 대화와 똑같이 저장·집계한다**(보호자 화면·하루 기분·연속 일수·주의 알림·관리자 집계·위험 알림). 단 위험 알림 발송은 피보호자당 하루 1번(2026-11-12 점검 L-2 - 위 "대화 내용 저장" 절). `companion_session.is_test`는 표시만 한다 - 집계에서 빼는 필터를 함부로 넣지 말 것(사용자 결정, 실제 흐름 확인이 목적). 시간대당 1회 UNIQUE는 일반 대화 전용 부분 인덱스(V68)라, 일반 대화 조회는 `...AndTestFalse`로 해야 한다. **진행 중(OPEN) 테스트 대화는 시간대당 1개**(V69 부분 UNIQUE, 점검 M-1) - 동시 시작(더블탭)에 진 쪽은 위반을 받아 이긴 쪽 대화를 이어 간다. 시간대 칸은 시간대별 가장 최근에 시작한 대화(`CompanionService.latestBySlot`)이고, **보호자 지금 기분·대답한 시간대 수는 그 날 모든 대화로 본다**(점검 L-2 - 대답한 일반 대화 뒤에 대답 없는 테스트 대화가 와도 대답한 시간대로 센다).
- **테스트 안내 푸시**: 정각 안내와 같은 문구·`COMPANION_PROMPT`(FCM만·설정 기반)이고 data에 `test=true`만 더한다. **`companion_prompt_log`를 남기지 않는다**(응답률은 정각 안내 기준, 사용자 결정). 정각 안내 data에 `test`를 넣지 말 것(`CompanionPromptSchedulerTest.regularPromptHasNoTestKey`가 고정). 분당 3·시간당 30. **204는 발송 요청 뜻이다**(점검 L-1) - 정지 계정·알림 설정 끔·FCM 토큰 없음이면 정각 안내처럼 보내지 않고 응답은 그대로 204다(결과는 알림 이력).
- **`analysis`는 응답에만**: AI 턴 값(`turnResult`·`face`·`signals`·위험) 그대로, 만들어 내지 않는다(AI가 안 주는 글 기분 코드는 없음). DB 칸·로그·알림에 남기지 말 것 - 원문 미저장(결정 B)과 같은 기준. 일반 계정 응답에는 필드 자체가 없다(`@JsonInclude(NON_NULL)`). 상세 `docs/(2026-11-10) feature-companion-test-mode.md`.
- **역할은 `isTestWard`가 아니라 컨트롤러가 막는다**(점검 L-3, 현상 유지): 목록에 남은 ID가 보호자로 바뀌어도 `WardCompanionController`의 `hasRole('WARD')`·역할 변경 토큰 무효화·말벗 기록 삭제가 막는다. 테스트 API를 다른 컨트롤러로 옮기면 역할 검사를 함께 옮길 것.
- **테스트 전체 공개 `COMPANION_TEST_ALL_WARDS`**(2026-11-11, 기본 false): 켜면 모든 피보호자가 테스트 계정(`isTestWard` 한 곳). 목록 방식은 그대로. 보호자·관리자는 해당 없음(컨트롤러 `hasRole('WARD')`). **gosky dev `.env.dev`에만 - 운영 서버 `.env`에 넣지 말 것.** 켜진 동안 모든 피보호자 응답에 `analysis`가 실리고, 테스트 대화도 위험·누적 알림 집계에 들어가 연결된 보호자 대체 문자(과금)가 늘 수 있다(L-5와 같음). QA가 끝나면 끄고 `up -d api`.
- **목록은 ID 기준이다**(점검 L-4): 탈퇴(hard delete) 뒤 같은 ID로 다시 가입하면 테스트 권한을 물려받는다. QA가 끝나면 `.env.dev`에서 ID를 빼고 `up -d api`. 점검 `docs/(2026-11-10) audit-companion-test-mode.md`.

## 수용한 한계 (2026-11-04)

- AI 대화는 메모리에만 있어 AI 재시작 시 진행 중 대화는 LOST(결과 없음).
- 멈춘 대화(10분 무활동)는 5분 주기 스케줄러가 NO_RESPONSE로 닫는다 - AI가 죽어 있으면 첫 실패에서 그 주기를 멈춘다.
- 질문은 날짜로만 돌아가 같은 날 모든 피보호자가 같은 문장을 받는다.
- 질문 → AI 신호 매핑은 영역 근거에서 유추한 가설이다(대화 로그로 검증 필요).
