-- 이상감지 판정 스키마(V42) 되돌리기 (2026-08-31)
--
-- 오탐 판정·관리자 대시보드 설계를 회의에서 다시 확정하기로 해 착수분을 되돌린다.
-- V42 파일 자체는 지우지 않는다 - 이미 적용된 서버가 있어 파일만 지우면 Flyway가
-- "적용됐지만 로컬에 없는 마이그레이션"으로 판단해 기동을 거부한다. 되돌리기도 앞으로 나아가는
-- 마이그레이션으로 한다.
--
-- 데이터 손실 없음: 판정 API가 머지된 적이 없어 세 객체 모두 사용된 적이 없다
-- (배포된 곳은 vkcs-linux 한 곳이고 anomaly_event도 0건이라 incident_id가 채워진 행이 없다).
--
-- 다시 만들 때는 이 파일을 되돌리지 말고 V44 이후로 새로 작성할 것.

-- 보호자 응답(참조하는 쪽)부터 지운다.
DROP TABLE IF EXISTS anomaly_incident_feedback;

-- 이력 → 상황 참조를 먼저 끊어야 상황 테이블을 지울 수 있다.
ALTER TABLE anomaly_event DROP COLUMN IF EXISTS incident_id;

DROP TABLE IF EXISTS anomaly_incident;
