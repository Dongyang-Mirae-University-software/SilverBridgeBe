---
paths:
  - "**/domain/game/**"
  - "**/*Game*"
---

# 도메인 정책 - 게임 (보호자 게임 기록 중계)

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (2026-11-19)

- 치매 예방 게임 자체는 AI 서버(`/api/v1/games/**`)에 있다. 피보호자는 FE에서 AI `/games/embed` iframe을 직접 열어 플레이한다(키 없는 경로, BE 무관).
- 보호자 게임 관리 화면은 **FE → BE → AI 중계**로 기록을 읽는다: `GET /api/guardian/game/progress?wardId=`·`GET /api/guardian/game/activity?wardId=&days=`(GUARDIAN 전용, `no-store`). 상세 `docs/(2026-11-19) feature-guardian-game-relay.md`.

## 보호자 게임 기록 중계 (2026-11-19)

- **경위**: FE `/api/streams` 프록시가 2026-10-06 보안 수정(FE ad44cb6)으로 피보호자 송출 POST 3개 경로만 받게 되어, 보호자 게임 화면의 AI GET 호출이 405로 막혔다("게임 기록을 불러오지 못했습니다"). 또 FE가 게임 사용자 번호를 클라이언트에서 만들어 넘기는 구조라, 프록시가 열려 있었다면 남의 기록을 볼 수 있었다. 영상(2026-10-03)·챗(2026-10-07) 중계와 같은 방식으로 바꿨다.
- **불변 규칙 ①(FE 프록시를 다시 열지 말 것)**: 게임 경로를 `/api/streams` 프록시 허용 목록에 넣지 말 것 - 프록시는 AI 키를 붙여 넘기므로 인증 없이 AI 서버가 다시 뚫린다. 보호자 조회는 이 중계만 쓴다.
- **불변 규칙 ②(인가)**: 형식이 틀린 wardId(`UserIdFormat`)는 DB 조회 없이 403(로그엔 길이만), ACTIVE 연결이 아니면 403 `GAME_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`. 두 경우 AI를 부르지 않는다. 인가는 `isActiveConnection`만(`getMyWards` 금지 - policy-connection ①).
- **불변 규칙 ③(게임 번호는 서버가 계산)**: AI가 `userId`를 정수로만 받아 `GameUserIds.of(wardId)`(= FE `toGameUserId` = `String.hashCode()`의 절댓값을 `long`으로, 0이면 1)로 바꾼다. **클라이언트에서 게임 번호를 받지 말 것.** FE 함수와 식이 어긋나면 보호자 화면이 0점으로 보인다(`GameUserIdsTest`가 FE 값으로 고정). AI user_id가 문자열이 되면 FE 함수와 함께 지운다.
- **불변 규칙 ④(AI 호출)**: `AI_HTTP_BASE_URL`·`AI_API_KEY`를 영상·챗 중계와 공유(새 env 없음). `X-API-Key` 헤더만, 리다이렉트 미추종, 연결 3초·호출 전체 8초, 응답 256KB 상한. AI 실패(401 포함)는 전부 503 `GAME_UNAVAILABLE` - 보호자 401로 내보내지 않는다. 로그엔 호출 이름·상태·예외 클래스명만.
- **응답**: 허용 목록 record. AI 게임 내부 상태(`state`)와 게임 번호(`userId`)는 싣지 않고 `wardId`를 싣는다. 시각 문자열은 **변환하지 않는다**(오프셋 없는 UTC - FE #259가 UTC로 읽는다. 영상 중계처럼 KST로 바꾸면 9시간 어긋난다).
- **D-1 미적용 (2026-11-19 사용자 결정)**: 게임 기록은 사건 이력이 아니라 누적 진행 상황이고 AI에 날짜 하한 조회가 없다. 다시 연결한 보호자는 연결 이전 누적 점수·활동도 본다(수용).
- **탈퇴 시 AI 게임 기록 삭제 (2026-11-20, 점검 M-1)**: `GameRecordPurgeListener`(비동기 AFTER_COMMIT, `aiPurgeExecutor`)가 AI `DELETE /api/v1/games/records?userId={게임 번호}`(AI #323, `GAME_REQUIRE_API_KEY`와 무관하게 항상 키 필요)를 부른다. 상담 기록 삭제와 같은 best-effort - 실패·옛 AI(404)는 `[AI-PURGE-FAILED]` WARN만, 탈퇴는 막지 않는다. 이벤트에 역할이 없고 회원 행이 곧 지워져 **역할을 가리지 않고 모든 탈퇴에 요청**한다(보호자는 보통 0건). 게임 번호가 우연히 같은 다른 회원 기록도 지워질 수 있다(31비트 충돌 - 아래 수용 한계와 같음). 보관 기간 정리는 두지 않는다(이용 중엔 계속 쓰는 누적 기록, D-2).
- **수용 한계 - 게임 번호 충돌**: 31비트 번호라 서로 다른 회원이 같은 번호가 될 수 있다(1000명일 때 약 0.02%) - 조회·삭제가 섞인다. AI `user_id`를 문자열로 바꾸면 해소.
- **범위 밖**: 피보호자 본인 게임 화면(iframe 직접, 정상 동작), AI 게임 무인증 경로(`/embed`·`/state`·`/answer`·`/reset` - AI 감사 M-7, 담당 밖 제외).
