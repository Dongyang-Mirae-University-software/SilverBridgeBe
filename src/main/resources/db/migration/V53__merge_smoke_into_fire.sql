-- 연기(SMOKE)를 화재(FIRE)로 통합 (2026-09-21)
--
-- 백엔드는 연기를 따로 구분하지 않는다 - AI가 smoke를 보내도 DetectedType.fromAi가 FIRE로 받는다.
-- 과거 이력도 같은 기준으로 맞춰야 목록·집계·보호자 이력이 "화재" 하나로 보인다.
-- DetectedType enum에서 SMOKE를 지웠으므로 이 UPDATE가 없으면 SMOKE 행을 읽는 순간 매핑 오류가 난다.
--
-- ⚠️ 비가역: 원래 연기였는지는 남지 않는다.
-- 상황 병합은 되돌리지 않는다 - 같은 시각에 따로 열려 있던 FIRE·SMOKE 상황은 둘 다 FIRE로 남는다(과거 기록이라 수용).
-- detected_type에는 CHECK 제약이 없어 제약 재정의는 필요 없다.

UPDATE anomaly_event    SET detected_type = 'FIRE' WHERE detected_type = 'SMOKE';
UPDATE anomaly_incident SET detected_type = 'FIRE' WHERE detected_type = 'SMOKE';
