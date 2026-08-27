-- 미복용 요약 설정의 축을 (보호자) → (보호자, 피보호자)로 변경 (2026-08-27)
--
-- V40에서 발송 시각을 보호자가 고를 수 있게 했지만 값이 보호자당 하나뿐이었다.
-- 그런데 이 시각은 집계 상한을 겸하므로, 피보호자마다 마지막 복약 시각이 다르면
-- 하나의 값으로는 반드시 누군가가 손해를 본다.
--
--   김영희: 수면 보조제 22:00  → 22:30으로 잡아야 요약에 들어감
--   이순자: 마지막 약  12:00  → 20:00이면 충분
--
-- 공통 21:00을 쓰면 김영희의 22:00 약이 매일 요약에서 빠지고, 그걸 살리려고 22:30으로
-- 올리면 이순자 요약까지 밤 10시 반에 도착한다. 그래서 피보호자별로 나눈다.

-- 축이 바뀌어 기존 행은 의미를 잃는다(어느 피보호자 것인지 알 수 없다).
-- 배포 2곳(gosky·vkcs-linux) 모두 0건임을 확인하고 지운다. 행이 있더라도 설정은
-- 기본값(ON · 21:00)으로 되돌아갈 뿐이라 사용자가 잃는 것은 직접 고른 시각 하나뿐이다.
DELETE FROM guardian_medication_setting;

-- 어느 피보호자 건 요약인지. 피보호자가 탈퇴하면 이 설정도 함께 사라진다.
ALTER TABLE guardian_medication_setting
    ADD COLUMN ward_id VARCHAR(6) NOT NULL REFERENCES users(id) ON DELETE CASCADE;

-- 보호자당 1행 → (보호자, 피보호자)당 1행.
ALTER TABLE guardian_medication_setting
    DROP CONSTRAINT uq_guardian_medication_setting;
ALTER TABLE guardian_medication_setting
    ADD CONSTRAINT uq_guardian_medication_setting UNIQUE (guardian_id, ward_id);

-- 발송 판정은 "이 피보호자의 보호자들 설정"을 한 번에 읽는다(Planner).
-- UNIQUE 인덱스는 guardian_id 선행이라 ward_id 단독 조회에 쓰이지 않으므로 따로 둔다.
CREATE INDEX idx_guardian_medication_setting_ward ON guardian_medication_setting (ward_id);
