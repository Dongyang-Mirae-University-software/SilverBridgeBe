---
paths:
  - "**/domain/checkin/**"
  - "**/*Checkin*"
---

# 도메인 정책 - 안부 말벗 (checkin)

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (2026-11-01 1차)

- **안부 말벗**(checkin, V65): 하루 다섯 번(09·12·15·18·21시 KST) 피보호자와 2분·8턴 이내 음성 대화 → AI(DGX, SenseVoice + EXAONE)가 정서 5가지(기쁨·편안·외로움·기운 없음·걱정, 판단 보류)·생활 신호 5칸(잠·식사·통증·기분·외로움)·위험을 정리 → BE가 `checkin_result`에 저장. AI 계약은 AiServer PR #152 `docs/checkin-companion.md`(v0).
  - 보호자 `GET·PUT /api/guardian/ward/{wardId}/checkin/questions`(시각별 질문 200자, ACTIVE 연결만, 피보호자 단위 공유).
  - 피보호자(WARD) `POST /api/ward/checkin/sessions` → `.../{sessionId}/turns`(multipart `audio` + 선택 `frames`) → `.../{sessionId}/finish`(`selfReport`), `GET·PUT /api/ward/checkin/setting`(`faceConsent`, 기본 false).
  - 2차(예정): 5시각 피보호자 푸시·평소 대비 "확인 필요"·보호자 조회·보호자 알림. 상세 `docs/(2026-11-01) feature-checkin-companion.md`.

## 불변 규칙

- **① 기본 꺼짐 · vkcs에서만 켠다 (2026-11-01 사용자 규칙)**: `checkin.enabled`(기본 false) AND `CHECKIN_AI_BASE_URL` AND `CHECKIN_AI_API_KEY`가 모두 있어야 동작하고, 아니면 **모든 말벗 API가 503 `CHECKIN_DISABLED`**다. 주소·키에 **코드·compose 기본값을 두지 말 것** - dev가 gosky에 pull돼도 아무 일도 없어야 한다. 말벗 AI 키는 챗봇·이상감지의 `AI_HTTP_BASE_URL`·`AI_API_KEY`와 **분리**한다(다른 서버). gosky에는 배포·설정하지 않는다(비교 측정만 예외).
- **② 대화 원문은 BE에 없다**: 받아쓴 글자·AI 답은 피보호자 본인 화면용으로 턴 응답에만 싣고 저장하지 않는다. `checkin_result`에는 정서·신호(영문 코드)·위험·AI의 고정 요약 문장·근거(신호 + 턴 번호)·목소리 평균만 둔다. **보호자 화면에 받아쓴 원문을 보여 주지 않는다**(2026-11-01 결정 Q3 - 요약·근거만). 원문은 AI 서버에 30일(탈퇴 시 삭제).
- **③ 로그에 대화 내용 금지**: 받아쓴 글자·답·요약·근거·보호자 질문·음성·사진·키를 로그·예외 메시지에 남기지 않는다(사용자 ID·세션 ID·턴 번호·개수·상태 코드·고정 오류 코드·예외 클래스명까지). `CheckinLogGuardTest`(패키지 전체)·`LogRawExceptionGuardTest`가 고정. 응답은 `Cache-Control: no-store`.
- **④ AI 오류는 고정 코드만 읽는다**: 챗 중계는 오류 본문을 읽지 않지만, 말벗은 같은 422·413 안에서 "못 알아들음/파일 못 읽음", "너무 큼/너무 김"을 다르게 안내해야 해 본문의 `errorCode`만 **허용 목록**(`KNOWN_ERROR_CODES`)으로 읽는다. `message`는 읽지 않는다. 키 불일치·BE와 어긋난 409·모르는 코드는 503.
- **⑤ 세션 ID는 무작위**: `ck_` + 영숫자 24자(`CheckinIds`). AI·로그로 나가는 값이라 사용자 식별자를 넣지 말 것(카메라 세션 ID 규칙과 같다). 경로 값이 형식이 아니면 DB를 보지 않고 404.
- **⑥ 인가**: 피보호자 경로는 토큰 ID만(경로·본문으로 사용자 ID를 받지 않음), 남의 세션은 **403 `CHECKIN_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]`**(세션이 BE DB에 있어 구분할 수 있으므로 챗 기록처럼 404로 숨기지 않는다). 보호자 질문은 `isActiveConnection`만(위반 403 `CHECKIN_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, 형식이 틀린 wardId는 원문 로그 없이 403). turns 경로는 `SecurityConfig` URL 규칙으로 multipart 파싱 전에 WARD만 통과(약 봉투 OCR과 같은 이유).
- **⑦ 턴·시간은 BE가 센다**: 턴 번호는 클라이언트가 보내지 않는다(`turnCount + 1`). 8턴·AI `done`·시작 + 2분 + 여유 60초(마지막 녹음 도착분)가 지나면 409 `CHECKIN_FINISH_REQUIRED`. 끝난 이유(`TURN_LIMIT`·`AI_END`·`TIME_LIMIT`·`USER_END`)도 서버가 정한다. 같은 시각 진행 중 대화는 부분 UNIQUE로 하나뿐이고, 시작을 다시 부르면 이어받는다(나눈 말이 있으면 정리만 남은 대화로 돌려주고, 시간이 지났고 말이 없으면 `EXPIRED`로 닫고 새로).
- **⑧ 표정 동의가 꺼져 있으면 사진은 읽지도 않는다**: 기본 꺼짐, 본인만 바꾼다(보호자 경로 없음). 켜져 있어도 JPEG·장당 300KB·**최대 8장**(multipart 전역 10MB 안에 음성 5MB와 함께 들어가게 - 결정 Q4, AI 상한 120장보다 작다). AI는 아직 표정 결과를 내지 않는다(`face: null`).
- **⑨ 동시 상한·시간 제한**: AI 호출은 전체 6 / 1인 1(`CheckinSlots` - 1인 1건이 턴 순서를 지키는 장치이기도 하다), 시작 10초·턴 20초·정리 30초·삭제 15초, 마감은 전용 데몬 스레드(빈 등록 금지). 속도 제한: 시작 분 10/시간 60, 턴·정리 분 20/시간 200.
- **⑩ 탈퇴 정리**: BE 테이블은 회원 CASCADE. AI 기록은 `AiMemberDataPurgeListener`가 `checkin-logs` 항목으로 `DELETE /api/v1/checkin/logs?userId=`를 부른다(항목별 독립·best-effort·200만 성공). **킬 스위치와 무관하게 주소·키가 있으면 지운다**(AI도 꺼져 있어도 삭제는 동작). 주소·키가 없는 서버(gosky)는 건너뛴다. 정리 대상 질문(`updated_by`)은 SET NULL - 질문은 피보호자 것이다.

## 수용한 한계 (1차)

- **AI가 턴을 저장한 뒤 BE가 시간 초과로 끊으면** BE 턴 수가 늘지 않는다. 사용자가 다시 말하면 BE는 같은 턴 번호로 보내고 AI는 저장된 결과(앞의 말)를 돌려준다(AI 계약의 재전송 멱등). 새 말이 그 턴에서 버려질 수 있다.
- **시작 경합**에서 진 쪽의 AI 세션은 빈 채로 AI에 남는다(30일 자동 삭제).
- 60초 길이 검사는 AI(`AUDIO_TOO_LONG` 413)에 맡긴다 - BE는 크기만 본다.
- 1차에는 응답 창이 없어 그 시각부터 다음 시각 전까지 언제든 시작할 수 있다(2차에서 알림과 함께 1시간 창 - 결정 Q2).

## 2차에서 정할 것 (결정 반영)

- Q1: AI 위험(자해·응급)이 오르면 보호자 알림 = **강제 FCM + 설정 채널**(`FORCED_PUSH_PLUS_SETTINGS`, 이상감지와 같은 정책). SOS의 문자 폴백까지는 올리지 않는다(키워드 판정).
- Q2: 응답 창 1시간 · 평소 대비 기준 14일(설정값).
- 새 `NotificationType`을 더하면 `NotificationTypeLabel`·`FcmMessageProperties`·yaml·`NotificationTypeEmailPolicyTest`(이메일 없음)·`AlimtalkTemplateMappingGuardTest`(알림톡 없음 - 다발성)를 함께 맞추고, 영향 범위 점검(템플릿 C)을 제안한다.
