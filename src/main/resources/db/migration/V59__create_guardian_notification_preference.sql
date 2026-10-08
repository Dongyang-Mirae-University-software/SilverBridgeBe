-- 보호자 계정 단위 알림 종류 수신 설정 (환경설정 > 알림 종류 탭)
--
-- 지금은 복약 미복용 요약(MEDICATION_MISSED)을 받을지 여부 하나뿐이다.
-- SOS·이상감지 발생 알림은 필수라 이 테이블에 값이 없다(끌 수 없음).
-- 판정 재촉은 기존 guardian_anomaly_setting을 그대로 쓴다.
-- 정서 변화·병원 예약은 BE가 발송하지 않으므로 컬럼을 만들지 않는다.
--
-- 행이 없으면 애플리케이션 기본값(ON)을 따른다 -> 백필 불필요. 추가만 하는 마이그레이션이라 비가역 DDL 없음.
-- users 참조는 ON DELETE CASCADE - 회원 탈퇴(hard delete) 시 자동 정리.
CREATE TABLE guardian_notification_preference (
    id                 BIGSERIAL    PRIMARY KEY,
    guardian_id        VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    medication_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_guardian_notification_preference UNIQUE (guardian_id)
);
