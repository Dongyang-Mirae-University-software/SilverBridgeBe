-- 안부 말벗 종료 이유에 AVOIDED 추가 (2026-11-12 사용자 결정)
--
-- AI #272: 한 대화에서 어르신이 두 번 말을 피하면("말하기 싫어", "할 말 없어") 말벗이 마무리 문장으로 끝낸다.
-- 지금까지는 USER_STOP(끝내기 직접 누름)으로 저장돼 둘이 섞였다. 기록을 나눠 감지 규칙 조정·보호자 신호의 근거로 쓴다.
-- 되돌리기: CHECK를 V65 값으로 다시 정의하면 된다(AVOIDED 행이 있으면 먼저 USER_STOP으로 바꿔야 함) - 비가역 아님.

ALTER TABLE companion_session DROP CONSTRAINT chk_companion_session_end_reason;
ALTER TABLE companion_session ADD CONSTRAINT chk_companion_session_end_reason
    CHECK (end_reason IN ('MAX_TURNS', 'NO_RESPONSE', 'USER_STOP', 'AVOIDED'));
