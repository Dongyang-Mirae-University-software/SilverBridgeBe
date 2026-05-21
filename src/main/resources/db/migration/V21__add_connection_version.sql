-- =============================================
-- V21: 연결(connections)에 낙관적 락용 version 컬럼 추가
--
--      수락/거절/취소/해제 등 상태 전이가 "조회 → status 검사 → UPDATE" 구조라
--      동시 요청 시 두 트랜잭션이 가드를 모두 통과해 lost update가 발생할 수 있었다.
--      (예: accept ∥ cancel → 최종 상태 비결정 + 수락 알림과 실제 상태 불일치)
--      JPA @Version(낙관적 락)으로 두 번째 커밋을 충돌로 차단하고 409로 응답한다.
--
-- 가역적 변경: NOT NULL DEFAULT 0 ADD COLUMN — 기존 행은 0으로 초기화.
-- =============================================

ALTER TABLE connections
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN connections.version IS 'JPA 낙관적 락 버전 — 동시 상태 전이 lost update 방지.';
