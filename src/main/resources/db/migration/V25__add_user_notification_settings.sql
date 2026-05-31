-- 사용자별 알림 채널 ON/OFF 설정 테이블 (알림 채널 추상화 1단계)
-- 행이 없는 채널은 애플리케이션 기본값 정책을 따른다(기본 FCM ON, 그 외 OFF) → 백필 불필요.
-- users 참조는 ON DELETE CASCADE — 회원 탈퇴(hard delete) 시 자동 정리.
CREATE TABLE user_notification_settings (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_type VARCHAR(20)  NOT NULL,   -- FCM / SMS / KAKAO_ALIMTALK / EMAIL
    enabled      BOOLEAN      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_notif_channel UNIQUE (user_id, channel_type)
);

CREATE INDEX idx_user_notif_user ON user_notification_settings (user_id);
