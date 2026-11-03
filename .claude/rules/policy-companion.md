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

## 불변 규칙

- **원문을 저장하지 않는다**(결정 B, 2026-11-02): 대답 글·음성·사진·받아 적은 글·말벗 응답 문장은 기기로 넘기기만 한다. DB 컬럼·로그·알림 문구·감사 로그 어디에도 넣지 말 것. AI 계약 문서(D:90 "턴 기록은 백엔드 DB")는 이 결정과 다르다 - 계약 문서가 틀렸다.
- **질문 문장은 행을 고치지 않는다**: 바꿀 때는 `active=false` + 새 행(같은 문장 = 같은 번호, 응답률 집계 기준). 새 마이그레이션으로.
- **기분 판단 불가 = null**: `UNKNOWN`·0·"정상"으로 바꾸지 말 것(화면 "확인할 수 없어요"). AI 한글 값은 영문 코드로만 저장(결정 D).
- **AI 판단 중 믿는 것은 `riskFlag`·`alertLevel`뿐**(결정 E). 주의(NOTICE, 걱정·우울 3일)는 PR-B에서 BE가 계산한다.
- **위험 알림은 대화당 1번**: `risk_notified_at` 조건부 UPDATE에 이긴 쪽만 이벤트를 낸다(턴에서 처음 본 때, 또는 마감 때 URGENT인데 아직 안 보낸 때). 문구에 원문·질병명·구체 내용 금지. `COMPANION_RISK`는 강제 푸시 + 설정, FCM·문자만(알림톡·이메일 금지), 긴급 풀(`urgentNotificationExecutor`).
- **질문 안내 `COMPANION_PROMPT`는 FCM만·설정 기반**: 하루 5건 다발성이라 문자·알림톡·강제 승격 금지. PR-B 전까지 `COMPANION_PROMPT_WARD_IDS` 허용 목록에만.
- **AI 호출은 트랜잭션 밖**(최대 90초): 쓰기는 `CompanionSessionWriter`의 짧은 트랜잭션. 동시 상한 서버 8(503 `COMPANION_BUSY`)·대화 1(409 `COMPANION_TURN_IN_PROGRESS`), 단일 인스턴스 전제.
- **중계 제한 90초**(사용자 결정 2026-11-04): AI에 한 턴 60초를 요청했다. 300초(AI 단계별 제한 합)로 늘리지 말 것 - 요청 스레드를 5분 붙든다.
- **탈퇴 삭제는 대기열**: 탈퇴 트랜잭션 BEFORE_COMMIT에 `companion_purge_request`(FK 없음, ID만), 직후 1회 + 매시 35분 재시도(첫 실패에서 중단). 스윕 purge·AI 장애에도 빠지지 않게 하려는 것이라 AFTER_COMMIT으로 옮기지 말 것. 스위치와 무관하게 주소·키만 있으면 지운다.
- **남의 대화는 403 `COMPANION_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]`**(2026-07-14 정책).
- **업로드**: 음성 WAV·WebM·MP3 5MB, 사진 JPEG·PNG 장당 1MB·5장, 앞부분 바이트로만 판별, 원래 파일 이름은 AI로 넘기지 않는다. 오디오 경로는 SecurityConfig에서 본문 파싱 전에 WARD만 통과.

## 수용한 한계 (2026-11-04)

- AI 대화는 메모리에만 있어 AI 재시작 시 진행 중 대화는 LOST(결과 없음).
- 멈춘 대화(10분 무활동)는 5분 주기 스케줄러가 NO_RESPONSE로 닫는다 - AI가 죽어 있으면 첫 실패에서 그 주기를 멈춘다.
- 질문은 날짜로만 돌아가 같은 날 모든 피보호자가 같은 문장을 받는다.
- 질문 → AI 신호 매핑은 영역 근거에서 유추한 가설이다(대화 로그로 검증 필요).
