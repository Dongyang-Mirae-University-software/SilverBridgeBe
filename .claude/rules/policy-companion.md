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
- **PR-B(2026-11-04)**: 보호자 `GET /api/guardian/ward/{wardId}/companion/today`(지금 기분·시간대별 질문·상태·기분·확인할 일), 주의 알림 `COMPANION_NOTICE`(걱정·우울 3일 이상, 하루 1번, V66), 관리자 `GET /api/admin/ai-stats/companion`(오늘 집계). 상세 `docs/(2026-11-04) feature-companion-screens.md`.

## 하루 기분·연속 일수·주의 알림 (2026-11-04 사용자 결정, PR-B)

- **하루 기분** = 그날 마지막으로 판정된 대화의 기분(판단 불가 건너뜀). **연속 일수** = 기준일(오늘 판정이 있으면 오늘, 없으면 어제)부터 거꾸로 걱정·우울인 날 수, **판정된 대화가 없는 날은 끊는다** - 대화가 없는 날뿐 아니라 대화했지만 모두 판단 불가(`UNKNOWN`)인 날도 끊는다(2026-11-09 사용자 확정, 건너뛰지 않음 - `CompanionMoodDaysTest.unknownOnlyDayBreaks`). 규칙은 `CompanionMoodDays` 한 곳 - 보호자 화면·주의 알림·관리자 집계가 같이 쓴다. 따로 계산하지 말 것.
- **지금 기분·관리자 기분 분포는 하루 기분과 다르다**(의도, 2026-11-09 점검): 보호자 `current`와 관리자 `moodDistribution`은 판단 불가를 **포함한** 마지막 마친 대화 기준이다("지금 기분" = 가장 최근 대화, 판단 불가면 "확인할 수 없어요"·`unknown`). 그래서 같은 어르신이 분포에서는 `unknown`이면서 이어지는 어르신(하루 기분·연속 일수 기준)에 오를 수 있다.
- **주의 알림**: 3일째부터 날마다 하루 1번(`companion_notice_log` (ward, date) UNIQUE 선점). FCM만·설정 기반·일반 풀. 보호자가 `companionNotice`로 끌 수 있고 게이트는 `CompanionNoticeListener`가 건다(디스패처는 모른다). 받을 보호자가 없으면 선점하지 않는다. 문구에 병명·원문 금지("걱정스럽거나 가라앉은 기분이 N일째").
  - **연속 일수는 보호자마다 그 보호자의 지금 연결 이후 대화로 센다**(D-1, 2026-11-09 PR-B 점검 M-1): 피보호자 전체 기록으로 세면 새로·다시 연결한 보호자에게 연결 전 기분 일수가 푸시로 드러난다(화면 주의 칸은 이미 연결 이후만). 필터는 `CompanionMoodDays.startedSince` 한 곳 - 화면·알림이 같이 쓴다. 보호자마다 일수가 다를 수 있고(각자 화면과 같은 숫자), 선점은 피보호자·날짜 1번·기록 일수는 보낸 일수 중 최댓값. 관리자 "이어지는 어르신"은 D-1 대상이 아니다(피보호자 전체 기록).
- **보호자 조회 권한**: ACTIVE 연결 + **지금 연결 이후 대화만**(D-1, `ConnectionService.findActiveConnectedAt`). 남의 피보호자 403 `COMPANION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`. 대답 원문 칸을 만들지 말 것(디자인 시안에 있었지만 결정 B).
- **관리자 집계**: BE DB로 계산(AI 호출 없음). 응답률 = 보낸 질문 안내 중 그 (피보호자, 시간대) 대화에 대답이 있는 비율, 안내가 없으면 null. 어르신 이름은 BE가 붙인다. 원문·요약 금지.
- **질문 안내 대상**: `COMPANION_PROMPT_WARD_IDS`에 `*`면 이용 중인 모든 피보호자. 피보호자 끄기 설정이 생기기 전에는 기본으로 켜지 말 것.

## 불변 규칙

- **원문을 저장하지 않는다**(결정 B, 2026-11-02): 대답 글·음성·사진·받아 적은 글·말벗 응답 문장은 기기로 넘기기만 한다. DB 컬럼·로그·알림 문구·감사 로그 어디에도 넣지 말 것. AI 계약 문서(D:90 "턴 기록은 백엔드 DB")는 이 결정과 다르다 - 계약 문서가 틀렸다.
- **질문 문장은 행을 고치지 않는다**: 바꿀 때는 `active=false` + 새 행(같은 문장 = 같은 번호, 응답률 집계 기준). 새 마이그레이션으로.
- **기분 판단 불가 = null**: `UNKNOWN`·0·"정상"으로 바꾸지 말 것(화면 "확인할 수 없어요"). AI 한글 값은 영문 코드로만 저장(결정 D). AI는 2026-11-09부터 `mood`·`moodCode`를 영문 코드(`JOY`·`CALM`·`WORRY`·`DEPRESSED`·`UNKNOWN`)로 준다 - `CompanionMood.fromAi`는 영문(대문자)·한글을 모두 받고 `UNKNOWN`·모르는 값은 null, `mood`가 비면 `moodCode`를 본다(`moodKo`는 읽지 않음).
- **AI 판단 중 믿는 것은 `riskFlag`·`alertLevel`뿐**(결정 E). 주의(NOTICE, 걱정·우울 3일)는 PR-B에서 BE가 계산한다.
- **위험 알림은 대화당 1번**: `risk_notified_at` 조건부 UPDATE에 이긴 쪽만 이벤트를 낸다(턴에서 처음 본 때, 또는 마감 때 URGENT인데 아직 안 보낸 때). 문구에 원문·질병명·구체 내용 금지. `COMPANION_RISK`는 강제 푸시 + 설정, FCM·문자만(알림톡·이메일 금지), 긴급 풀(`urgentNotificationExecutor`).
  - **턴 위험 = 최상위 `riskFlag` 또는 `signals.riskFlag`**(2026-11-09): 확인(되묻기) 턴(`replySource="CONFIRM"`)은 `signals`가 null이고 낱말 규칙 결과를 최상위에만 싣는다. `signals`만 읽지 말 것 - 그 위험 알림이 마감 때로 늦어진다. AI 새 오류 코드 `ANSWER_TOO_LONG`·`FRAME_FORMAT_UNSUPPORTED`는 `COMPANION_ANSWER_TOO_LONG`·`COMPANION_FRAME_INVALID`(400)로 바꾼다.
- **질문 안내 `COMPANION_PROMPT`는 FCM만·설정 기반**: 하루 5건 다발성이라 문자·알림톡·강제 승격 금지. PR-B 전까지 `COMPANION_PROMPT_WARD_IDS` 허용 목록에만.
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
- **테스트 대화는 일반 대화와 똑같이 저장·집계한다**(보호자 화면·하루 기분·연속 일수·주의 알림·관리자 집계·위험 알림). `companion_session.is_test`는 표시만 한다 - 집계에서 빼는 필터를 함부로 넣지 말 것(사용자 결정, 실제 흐름 확인이 목적). 시간대당 1회 UNIQUE는 일반 대화 전용 부분 인덱스(V68)라, 일반 대화 조회는 `...AndTestFalse`로 해야 한다. 화면은 시간대별 가장 최근 대화(`CompanionService.latestBySlot`).
- **테스트 안내 푸시**: 정각 안내와 같은 문구·`COMPANION_PROMPT`(FCM만·설정 기반)이고 data에 `test=true`만 더한다. **`companion_prompt_log`를 남기지 않는다**(응답률은 정각 안내 기준, 사용자 결정). 정각 안내 data에 `test`를 넣지 말 것. 분당 3·시간당 30.
- **`analysis`는 응답에만**: AI 턴 값(`turnResult`·`face`·`signals`·위험) 그대로, 만들어 내지 않는다(AI가 안 주는 글 기분 코드는 없음). DB 칸·로그·알림에 남기지 말 것 - 원문 미저장(결정 B)과 같은 기준. 일반 계정 응답에는 필드 자체가 없다(`@JsonInclude(NON_NULL)`). 상세 `docs/(2026-11-10) feature-companion-test-mode.md`.

## 수용한 한계 (2026-11-04)

- AI 대화는 메모리에만 있어 AI 재시작 시 진행 중 대화는 LOST(결과 없음).
- 멈춘 대화(10분 무활동)는 5분 주기 스케줄러가 NO_RESPONSE로 닫는다 - AI가 죽어 있으면 첫 실패에서 그 주기를 멈춘다.
- 질문은 날짜로만 돌아가 같은 날 모든 피보호자가 같은 문장을 받는다.
- 질문 → AI 신호 매핑은 영역 근거에서 유추한 가설이다(대화 로그로 검증 필요).
