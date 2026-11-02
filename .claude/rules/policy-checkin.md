---
paths:
  - "**/domain/checkin/**"
  - "**/*Checkin*"
---

# 도메인 정책 - 안부 말벗 (checkin)

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## ⚠️ 현재 상태 - 재희 말벗 기준으로 dev·gosky에 다시 만든다 (2026-11-02 사용자 결정)

- 말벗 AI는 **재희 구현**(`companion-emotion2` 저장소, `/companion/dialogues` 4개 경로, `X-API-Key`)을 쓴다. BE는 그 계약에 맞춰 **일반 흐름(feat 브랜치 → PR → dev → gosky)**으로 만든다. 기준 문서: 노션 "말벗(AI) 데이터 연동 제안 - 2026-11-02".
- 우리 말벗 서버(AI PR #152, DGX)와 vkcs 전용 시험 서버·터널은 **없앴다**. DGX는 모델 학습·평가에만 쓴다.
- dev·gosky에는 아직 말벗 코드·테이블이 없다(PR #432로 뺐다). 장기 브랜치 `feature/checkin`에 옛 v0(우리 서버 `/api/v1/checkin`용, 마이그레이션 V1065·V1066)가 남아 있다 - 재사용할 부분만 가져오고, 새 마이그레이션은 dev 번호 체계(V65~)를 따른다.
- **아래 내용은 옛 v0 기준 기록이다.** 새 설계가 확정되면 그 기준으로 고쳐 쓴다. dev의 다른 정책 파일에 남은 말벗 언급(알림 4종·스케줄러 수 등)도 v0 기준이다.

## 기능 요약 (2026-11-01 1차)

- **안부 말벗**(checkin, V65): 하루 다섯 번(09·12·15·18·21시 KST) 피보호자와 2분·8턴 이내 음성 대화 → AI(DGX, SenseVoice + EXAONE)가 정서 5가지(기쁨·편안·외로움·기운 없음·걱정, 판단 보류)·생활 신호 5칸(잠·식사·통증·기분·외로움)·위험을 정리 → BE가 `checkin_result`에 저장. AI 계약은 AiServer PR #152 `docs/checkin-companion.md`(v0).
  - 보호자 `GET·PUT /api/guardian/ward/{wardId}/checkin/questions`(시각별 질문 200자, ACTIVE 연결만, 피보호자 단위 공유).
  - 피보호자(WARD) `POST /api/ward/checkin/sessions` → `.../{sessionId}/turns`(multipart `audio` + 선택 `frames`) → `.../{sessionId}/finish`(`selfReport`), `GET·PUT /api/ward/checkin/setting`(`faceConsent`, 기본 false).
  - **2차**(V66): 보호자 `GET·PUT /api/guardian/ward/{wardId}/checkin/setting`(`promptEnabled`, 기본 false) → 5시각 피보호자 푸시 `CHECKIN_PROMPT`(0~4분 분산, 응답 창 1시간), 정리 때 평소 대비 판정(`attention`·`attentionReasons`), 보호자 알림 `CHECKIN_RISK`(강제 FCM + 설정)·`CHECKIN_ATTENTION`·`CHECKIN_MISSED`(끌 수 있음, 하루 한 번), 보호자 조회 `GET /api/guardian/checkin?wardId=&date=`·`/weekly?wardId=&endDate=`. 상세 `docs/(2026-11-01) feature-checkin-companion.md`.

## 불변 규칙

- **① 기본 꺼짐 (2026-11-01 사용자 규칙, v0 - "vkcs에서만 켠다·gosky 미배포"는 2026-11-02 폐기)**: `checkin.enabled`(기본 false) AND `CHECKIN_AI_BASE_URL` AND `CHECKIN_AI_API_KEY`가 모두 있어야 동작하고, 아니면 **모든 말벗 API가 503 `CHECKIN_DISABLED`**다. 주소·키에 **코드·compose 기본값을 두지 말 것** - dev가 gosky에 pull돼도 아무 일도 없어야 한다. 말벗 AI 키는 챗봇·이상감지의 `AI_HTTP_BASE_URL`·`AI_API_KEY`와 **분리**한다(다른 서버). gosky에는 배포·설정하지 않는다(비교 측정만 예외).
- **② 대화 원문은 BE에 없다**: 받아쓴 글자·AI 답은 피보호자 본인 화면용으로 턴 응답에만 싣고 저장하지 않는다. `checkin_result`에는 정서·신호(영문 코드)·위험·AI의 고정 요약 문장·근거(신호 + 턴 번호)·목소리 평균만 둔다. **보호자 화면에 받아쓴 원문을 보여 주지 않는다**(2026-11-01 결정 Q3 - 요약·근거만). 원문은 AI 서버에 30일(탈퇴 시 삭제).
- **③ 로그에 대화 내용 금지**: 받아쓴 글자·답·요약·근거·보호자 질문·음성·사진·키를 로그·예외 메시지에 남기지 않는다(사용자 ID·세션 ID·턴 번호·개수·상태 코드·고정 오류 코드·예외 클래스명까지). `CheckinLogGuardTest`(패키지 전체)·`LogRawExceptionGuardTest`가 고정. 응답은 `Cache-Control: no-store`.
- **④ AI 오류는 고정 코드만 읽는다**: 챗 중계는 오류 본문을 읽지 않지만, 말벗은 같은 422·413 안에서 "못 알아들음/파일 못 읽음", "너무 큼/너무 김"을 다르게 안내해야 해 본문의 `errorCode`만 **허용 목록**(`KNOWN_ERROR_CODES`)으로 읽는다. `message`는 읽지 않는다. 키 불일치·BE와 어긋난 409·모르는 코드는 503.
- **⑤ 세션 ID는 무작위**: `ck_` + 영숫자 24자(`CheckinIds`). AI·로그로 나가는 값이라 사용자 식별자를 넣지 말 것(카메라 세션 ID 규칙과 같다). 경로 값이 형식이 아니면 DB를 보지 않고 404.
- **⑥ 인가**: 피보호자 경로는 토큰 ID만(경로·본문으로 사용자 ID를 받지 않음), 남의 세션은 **403 `CHECKIN_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]`**(세션이 BE DB에 있어 구분할 수 있으므로 챗 기록처럼 404로 숨기지 않는다). 보호자 질문은 `isActiveConnection`만(위반 403 `CHECKIN_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, 형식이 틀린 wardId는 원문 로그 없이 403). turns 경로는 `SecurityConfig` URL 규칙으로 multipart 파싱 전에 WARD만 통과(약 봉투 OCR과 같은 이유).
- **⑦ 턴·시간은 BE가 센다**: 턴 번호는 클라이언트가 보내지 않는다(`turnCount + 1`). 8턴·AI `done`·시작 + 2분 + 여유 60초(마지막 녹음 도착분)가 지나면 409 `CHECKIN_FINISH_REQUIRED`. 끝난 이유(`TURN_LIMIT`·`AI_END`·`TIME_LIMIT`·`USER_END`)도 서버가 정한다. 같은 시각 진행 중 대화는 부분 UNIQUE로 하나뿐이고, 시작을 다시 부르면 이어받는다(나눈 말이 있으면 정리만 남은 대화로 돌려주고, 시간이 지났고 말이 없으면 `EXPIRED`로 닫고 새로).
- **⑧ 표정 동의가 꺼져 있으면 사진은 읽지도 않는다**: 기본 꺼짐, 본인만 바꾼다(보호자 경로 없음). 켜져 있어도 JPEG·장당 300KB·**최대 8장**(multipart 전역 10MB 안에 음성 5MB와 함께 들어가게 - 결정 Q4, AI 상한 120장보다 작다). AI는 아직 표정 결과를 내지 않는다(`face: null`).
- **⑨ 동시 상한·시간 제한**: AI 호출은 전체 6 / 1인 1(`CheckinSlots` - 1인 1건이 턴 순서를 지키는 장치이기도 하다), 시작 10초·턴 20초·정리 30초·삭제 15초, 마감은 전용 데몬 스레드(빈 등록 금지). 속도 제한: 시작 분 10/시간 60, 턴·정리 분 20/시간 200.
- **⑩ 탈퇴 정리**: BE 테이블은 회원 CASCADE. AI 기록은 `AiMemberDataPurgeListener`가 `checkin-logs` 항목으로 `DELETE /api/v1/checkin/logs?userId=`를 부른다(항목별 독립·best-effort·200만 성공). **킬 스위치와 무관하게 주소·키가 있으면 지운다**(AI도 꺼져 있어도 삭제는 동작). 주소·키가 없는 서버(gosky)는 건너뛴다. 정리 대상 질문(`updated_by`)은 SET NULL - 질문은 피보호자 것이다.

- **⑪ 안부 알림은 켠 피보호자에게만 (2026-11-01 2차, 가정으로 정함)**: 말벗을 켠 서버의 모든 피보호자에게 하루 다섯 번 푸시가 갑자기 가지 않도록 `checkin_setting.prompt_enabled` 기본 false다. 보호자(ACTIVE 연결)가 켜고, 본인도 끌 수 있다(하루 다섯 번 오는 알림을 본인이 멈출 수 있어야 한다). 표정 동의는 여전히 본인만. 기본값을 true로 바꾸지 말 것.
- **⑫ 선점 후 발송**: 피보호자 알림은 `checkin_prompt_log` UNIQUE(ward, date, slot), 보호자 알림은 `checkin_guardian_alert_log` UNIQUE(guardian, ward, date, slot, kind)를 **먼저 커밋(REQUIRES_NEW)한 뒤** 보낸다. 순서를 뒤집지 말 것(1분 주기라 창 내내 중복 발송). 실패 회차는 재시도하지 않는다(복약 규칙과 같다).
- **⑬ 응답 창 1시간 (결정 Q2)**: 시각부터 1시간 안에만 대화를 시작할 수 있다(409 `CHECKIN_NOT_OPEN`). 단 창 안에 말을 나눈 대화는 창이 닫혀도 정리할 수 있게 이어받는다. 미응답 알림은 창이 닫힌 뒤 1시간 안에만 본다(재시작 뒤 아침 미응답이 밤에 가지 않게). 알림을 받지 않은 시각은 미응답이 아니다(`NO_RECORD`).
- **⑭ 평소 대비 판정 (`CheckinAttentionJudge`)**: 기준 = 그날 전 14일 결과(결정 Q2). 생활 신호 평소와 다름 1.0·본인 부정 기분 1.0·AI 부정 정서 0.5·목소리(대답 시간·말 빠르기 2σ) 0.5, **근거 둘 이상이고 2.0 이상**이어야 확인 필요 - 신호 하나·보조 근거만으로는 붙지 않는다. 위험(응급·자해)만 곧바로. 평소를 모르면(기준 3건 미만) 부정 값을 다르다고 본다. 표정은 null이라 쓰지 않는다. 가중치를 바꿀 때 "신호 하나로 붙지 않음"을 지키는지 `CheckinAttentionJudgeTest`로 확인.
- **⑮ 보호자 알림**: `CHECKIN_RISK`는 위험이 **처음** 올라간 턴에 바로(정리를 기다리지 않음), 긴급 executor, `FORCED_PUSH_PLUS_SETTINGS`(결정 Q1 - SOS 문자 폴백까지는 아님), 끌 수 없다. `CHECKIN_ATTENTION`은 위험이 아닐 때만(겹쳐 보내지 않음), 일반 executor, FCM·설정 기반. `CHECKIN_MISSED`는 보호자 알림 종류 설정 `checkinMissed`(기본 ON)로 끌 수 있고, 설정 조회가 실패하면 그 회차는 보내지 않는다. 세 종류 모두 알림톡·이메일 없음, 문구는 단정하지 않고 대화 내용을 싣지 않는다.
- **⑯ 보호자 조회**: ACTIVE 연결만, **지금 연결 이후 기록만**(D-1 - 이전 시각은 `BEFORE_CONNECTION`으로 내용 없이). 받아쓴 원문은 BE에 없어 내려 줄 수 없다(요약·근거·정서·신호·확인 필요만). 오늘~60일 전.

- **⑰ 점검 반영 (2026-11-01, `docs/(2026-11-01) audit-impact-checkin-companion.md`)**:
  - 위험 대체 문자는 **단문(EUC-KR 90바이트) 안**이다 - 이름이 길어 넘치면 이름 없는 문구(`CheckinGuardianAlertService.riskSmsText`, M-1). 문구를 고칠 때 `fitsSingleSms` 테스트를 지킬 것.
  - 보호자 알림 전체 경로(쓰기 → 커밋 → SpEL 조건 리스너 → executor → 선점 → 디스패처)는 `CheckinGuardianAlertFullIntegrationTest`가 고정한다(M-2). 리스너 조건·executor를 바꾸면 이 테스트를 돌릴 것.
  - 웹 계층 시각 직렬화(`"09:00"` ↔ `CheckinSlot`)는 `GuardianCheckinControllerHttpTest`가 고정한다(M-3).
  - **보관 기간 (M-4, 사용자 결정)**: 발송 기록(`checkin_prompt_log`·`checkin_guardian_alert_log`) 90일, 대화·결과 365일(`retention.checkin-log-days`·`checkin-result-days`, 매일 05:10 KST `CheckinRetentionScheduler`). 결과 보관은 평소 대비 14일·조회 60일보다 길어야 한다.
  - **연결이 끝나면 그 보호자가 켠 안부 알림을 끈다 (L-1)**: `CheckinConnectionEndedListener`(동기 AFTER_COMMIT, 쓰기 REQUIRES_NEW). 다른 보호자·본인이 켠 설정은 그대로. 남은 보호자가 원하면 다시 켠다.
  - 보호자 조회는 "연결 이후" 판정을 조회 한 번 안에서 기억한다(L-2, 단조 판정). 질문 저장은 `ON CONFLICT` upsert(L-4). 스윕 purge의 AI 기록 잔존은 수용(L-5, AI 30일 자동 삭제).

## 수용한 한계 (1차)

- **AI가 턴을 저장한 뒤 BE가 시간 초과로 끊으면** BE 턴 수가 늘지 않는다. 사용자가 다시 말하면 BE는 같은 턴 번호로 보내고 AI는 저장된 결과(앞의 말)를 돌려준다(AI 계약의 재전송 멱등). 새 말이 그 턴에서 버려질 수 있다.
- **시작 경합**에서 진 쪽의 AI 세션은 빈 채로 AI에 남는다(30일 자동 삭제).
- 60초 길이 검사는 AI(`AUDIO_TOO_LONG` 413)에 맡긴다 - BE는 크기만 본다.
- (해소, 2차) 1차에는 응답 창이 없었다 - 2차에서 1시간 창 적용.
- **2차 한계**: 피보호자 안부 알림은 FCM만이라 앱 알림을 꺼 둔 어르신은 받지 못한다(하루 다섯 번 문자는 과금·소음). 스케줄러는 단일 인스턴스 기준(선점 UNIQUE가 다중 인스턴스 중복은 막는다). 관리자 알림 이력에서 안부 종류는 "기타" 탭에 섞인다(전용 탭은 관리자 FE 결정).
