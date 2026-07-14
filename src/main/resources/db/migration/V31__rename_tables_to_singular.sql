-- 테이블 명명 규칙을 복수형 → 단수형으로 통일한다 (팀 결정, 2026-07-13).
--
-- ⚠️ users 는 예외로 복수형을 유지한다 — "user" 는 PostgreSQL 예약어라 테이블명으로 쓰면
--    모든 참조를 "user" 로 인용해야 하고, 인용을 빠뜨린 쿼리(SELECT * FROM user)는 테이블이 아닌
--    현재 세션 사용자를 뜻하게 되어 조용히 오동작한다. 그 함정을 감수할 이득이 없어 users 만 남긴다.
--
-- RENAME 은 데이터를 보존하며, FK·인덱스·시퀀스는 테이블을 따라 자동으로 옮겨간다(재생성 불필요).
-- 다만 제약·인덱스 "이름"에는 복수형이 남는다(예: fk_cameras_ward) — 이름은 식별 라벨일 뿐
-- 동작에 영향이 없어, 불필요한 변경 폭을 줄이기 위해 그대로 둔다.
--
-- 엔티티 @Table(name=...) 도 같은 커밋에서 함께 바뀐다. ddl-auto=validate 이므로 둘 중 하나만
-- 배포되면 앱이 기동하지 않는다 — 반드시 함께 배포할 것.

ALTER TABLE connections                RENAME TO connection;
ALTER TABLE cameras                    RENAME TO camera;
ALTER TABLE anomaly_events             RENAME TO anomaly_event;
ALTER TABLE sos_events                 RENAME TO sos_event;
ALTER TABLE inquiries                  RENAME TO inquiry;
ALTER TABLE announcements              RENAME TO announcement;
ALTER TABLE announcement_drafts        RENAME TO announcement_draft;
ALTER TABLE admin_audit_logs           RENAME TO admin_audit_log;
ALTER TABLE access_logs                RENAME TO access_log;
ALTER TABLE fcm_tokens                 RENAME TO fcm_token;
ALTER TABLE refresh_tokens             RENAME TO refresh_token;
ALTER TABLE user_notification_settings RENAME TO user_notification_setting;
