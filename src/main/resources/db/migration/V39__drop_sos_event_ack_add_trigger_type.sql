-- SOS 이력에서 처리 결과(ACK)를 제거하고, 발생 경로(trigger_type)를 추가한다.
--
-- [1) ACK 제거 - ⚠️ 비가역]
-- 보호자가 SOS 처리 결과("안전 확인"/"응급 출동")를 남기는 기능(V33)을 철회한다.
-- SOS 이력은 "언제 발생했는지"만 남기고 처리 결과·메모는 기록하지 않는다(2026-08-26 결정).
-- 프론트에 ACK 화면이 붙은 적이 없어 배포 서버 두 곳 모두 ack_status 가 채워진 행이 0건임을
-- 확인한 뒤 삭제한다. 컬럼 삭제는 되돌릴 수 없다 - 되살리려면 V33 을 다시 적용해야 한다.
-- FK(fk_sos_event_ack_by)는 컬럼과 함께 정리한다.
--
-- [2) 발생 경로 추가]
-- 피보호자 SOS 화면에는 두 가지 경로가 있다 - "긴급 SOS 버튼"과 "보호자에게 직접 전화".
-- 둘 다 이력을 남기고 보호자 알림도 나가지만, 보호자 이력 화면에서 구분해 보여줘야 하므로
-- 발생 경로를 이력에 기록한다. 기존 행은 전부 버튼 경로이므로 DEFAULT 로 백필한다.
-- DEFAULT 를 남겨 두는 이유: 이 컬럼을 지정하지 않는 과거 방식의 INSERT 가 실패하지 않게 한다.

ALTER TABLE sos_event
    DROP CONSTRAINT IF EXISTS fk_sos_event_ack_by,
    DROP COLUMN IF EXISTS ack_status,
    DROP COLUMN IF EXISTS ack_by,
    DROP COLUMN IF EXISTS ack_at,
    DROP COLUMN IF EXISTS ack_note;

ALTER TABLE sos_event
    ADD COLUMN trigger_type VARCHAR(20) NOT NULL DEFAULT 'SOS_BUTTON';   -- SOS_BUTTON / GUARDIAN_CALL
