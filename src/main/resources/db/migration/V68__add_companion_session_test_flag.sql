-- 안부 말벗 최종 QA용 테스트 대화 (2026-11-10)
-- 테스트 계정(companion.test-ward-ids)은 시간대를 골라 몇 번이든 대화를 다시 할 수 있다. 테스트 대화도 일반 대화와
-- 똑같이 저장·집계하고(보호자 화면·주의 알림·관리자 집계·위험 알림 포함), is_test 는 나중에 걸러 볼 수 있게 표시만 한다.
-- 시간대당 1회 규칙(UNIQUE)은 일반 대화에만 건다 - 부분 유니크 인덱스.
-- 되돌리기: 테스트 행(is_test = true)을 지우면 원래 제약을 다시 만들 수 있다(가역).

ALTER TABLE companion_session ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE companion_session DROP CONSTRAINT uq_companion_session_slot;

CREATE UNIQUE INDEX uq_companion_session_slot ON companion_session (ward_id, session_date, slot)
    WHERE is_test = false;
