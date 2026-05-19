-- 2026-05-19 미검증 API 정리(call/game/ai/anomaly 도메인 엔티티 제거)에 따른
-- 미사용 테이블 정리.
--
-- 비가역 DDL. 적용 전 운영 DB 백업/스냅샷 확인 필수.
-- 상세 배경: docs/V17_drop_unused_tables_proposal.md
--
-- 대상 3개 테이블은 모두 leaf 테이블(FK는 users 를 향하는 방향이며,
-- 이 테이블들을 부모로 참조하는 inbound FK 없음)이므로 DROP 순서 제약 없고
-- CASCADE 불필요. character_expressions 의 인덱스는 테이블과 함께 제거됨.
--
-- 유지: admin_audit_logs(공지사항 감사로그 사용), announcements,
--       announcement_drafts, fcm_tokens, connections, users 등.

DROP TABLE IF EXISTS game_results;
DROP TABLE IF EXISTS anomaly_events;
DROP TABLE IF EXISTS character_expressions;
