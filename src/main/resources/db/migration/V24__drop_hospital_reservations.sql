-- =============================================
-- V24: hospital_reservations(병원 예약) 테이블 제거
-- =============================================
-- 2026-05-30 병원 예약 기능을 프로젝트에서 제외하기로 결정.
-- 백엔드 구현(Entity/Repository/Service/Controller/DTO)은 시작된 적이 없고
-- DB 테이블만 V1__init.sql에서 생성되어 미사용 상태로 잔존했음.
-- (V17__drop_unused_feature_tables.sql의 미사용 테이블 정리와 동일한 후속 정리)
--
-- 비가역 DDL. 적용 전 운영 DB 백업/스냅샷 확인 필수.
--
-- hospital_reservations는 leaf 테이블(FK는 users(id)를 향하는 방향이며,
-- 이 테이블을 부모로 참조하는 inbound FK 없음)이므로 DROP 순서 제약 없고
-- CASCADE 불필요. 트리거(trg_hospital_reservations_updated_at)와
-- 인덱스(idx_hospital_reservations_*)는 테이블과 함께 제거됨.

DROP TABLE IF EXISTS hospital_reservations;
