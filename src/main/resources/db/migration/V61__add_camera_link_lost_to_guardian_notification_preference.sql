-- 보호자 알림 종류 설정에 "카메라 연결 끊김 안내"(CAMERA_LINK_LOST) 수신 여부를 더한다.
--
-- AI 서버 재시작 등으로 감시가 끊긴 카메라를 유예 뒤 보호자에게 알리는 안내성 알림이라 끌 수 있다.
-- 행이 없거나 기존 행이면 기본값 ON(TRUE) -> 백필 불필요. 컬럼 추가만 하는 마이그레이션이라 비가역 DDL 없음.
ALTER TABLE guardian_notification_preference
    ADD COLUMN camera_link_lost_enabled BOOLEAN NOT NULL DEFAULT TRUE;
