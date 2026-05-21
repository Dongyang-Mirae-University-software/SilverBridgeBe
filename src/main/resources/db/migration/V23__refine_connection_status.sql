-- =============================================
-- V23: 연결 상태(ConnectionStatus) 세분화 — REFUSED, DISCONNECTED 추가
--
--      기존엔 거절/취소/해제가 모두 CANCELLED로 평탄화되어 "요청 내역"에서 구분 불가했다.
--      - CANCELLED   : 보호자가 PENDING 요청을 스스로 취소
--      - REFUSED     : 피보호자가 PENDING 요청을 거절 (신규)
--      - DISCONNECTED: ACTIVE 연결을 해제 (신규)
--
--      ⚠️ 중복방지 부분 유니크 인덱스의 "live" 정의 변경:
--      기존 uq_connections_active 는 WHERE status != 'CANCELLED' 라, REFUSED/DISCONNECTED를
--      "활성"으로 오인해 거절·해제 후 재연결을 막아버린다. live = (PENDING, ACTIVE)만으로 재정의한다.
--
--      기존 CANCELLED 행은 그대로 둔다(과거 거절/취소/해제를 사후 구분 불가). 신규 전이부터 세분화 적용.
-- =============================================

-- 1. status CHECK 제약 확장
ALTER TABLE connections DROP CONSTRAINT chk_connections_status;
ALTER TABLE connections ADD CONSTRAINT chk_connections_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED', 'REFUSED', 'DISCONNECTED'));

-- 2. 중복방지 부분 유니크 인덱스를 live(PENDING/ACTIVE) 기준으로 재정의
DROP INDEX IF EXISTS uq_connections_active;
CREATE UNIQUE INDEX uq_connections_live
    ON connections (guardian_id, ward_id)
    WHERE status IN ('PENDING', 'ACTIVE');
