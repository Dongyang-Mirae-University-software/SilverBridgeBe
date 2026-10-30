-- SOS 이력에 "보호자에게 직접 전화"(GUARDIAN_CALL)를 받은 보호자를 기록한다(2026-10-30).
-- 보호자 이력 화면의 보호자 연락 줄에 이름 칩을 보여 주기 위한 표시 전용 값이다 - 알림 대상·발송에는 쓰지 않는다.
-- 서버가 그 피보호자의 ACTIVE 보호자인지 확인한 값만 저장하고, 아니면 NULL로 둔다(SOS 요청은 거절하지 않는다).
-- 보호자가 탈퇴(hard delete)하면 칩만 사라지고 이력은 남도록 ON DELETE SET NULL - ward_id(V26)와 같은 정책.
-- 기존 행은 누구에게 전화했는지 알 수 없어 NULL 그대로 둔다(백필 없음). 가역 변경(DROP COLUMN으로 되돌릴 수 있음).
ALTER TABLE sos_event ADD COLUMN called_guardian_id VARCHAR(6) NULL;

ALTER TABLE sos_event
    ADD CONSTRAINT fk_sos_event_called_guardian
        FOREIGN KEY (called_guardian_id) REFERENCES users (id) ON DELETE SET NULL;

-- 회원 삭제 시 FK 확인이 sos_event 전체를 훑지 않게 한다. 값이 있는 행(보호자 연락)만 담는다.
CREATE INDEX idx_sos_event_called_guardian ON sos_event (called_guardian_id)
    WHERE called_guardian_id IS NOT NULL;
