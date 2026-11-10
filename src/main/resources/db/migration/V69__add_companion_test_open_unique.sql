-- 안부 말벗 테스트 대화 점검 M-1 (2026-11-10)
-- 같은 시간대 테스트 시작이 동시에 오면(더블탭) 둘 다 "진행 중 없음"으로 보고 테스트 대화·AI 대화가 2개 생겼다.
-- 진행 중(OPEN)인 테스트 대화는 (피보호자, 날짜, 시간대)당 1개로 묶는다 - 끝나거나 끊긴 테스트 대화는 몇 개든 남는다.
-- 진 쪽 요청은 UNIQUE 위반을 받아 이긴 쪽 대화를 이어 간다(일반 대화 선점과 같은 형태).
-- 되돌리기: DROP INDEX uq_companion_session_test_open (가역).
-- 적용 전 위반 행이 있으면 실패한다 - 진행 중 테스트 대화가 같은 시간대에 2개 이상이면 먼저 하나를 닫는다.

CREATE UNIQUE INDEX uq_companion_session_test_open ON companion_session (ward_id, session_date, slot)
    WHERE is_test = true AND status = 'OPEN';
