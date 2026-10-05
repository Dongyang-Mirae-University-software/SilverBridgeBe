-- 한 피보호자의 같은 방(label)에는 카메라 1대만 (2026-10-05 사용자 결정).
--
-- 서비스(CameraService)가 등록·수정 전에 먼저 검사해 409 CAMERA_LABEL_DUPLICATED로 답하고,
-- 이 제약은 같은 순간 두 기기가 같은 방을 등록하는 경합의 최종 방어선이다.
-- label은 저장 전에 TextSanitizer로 정리되므로 그대로 비교한다(대소문자 구분 - 방 목록이 한글 고정값).
--
-- 배포 전 점검(읽기 전용): 아래가 0행이어야 한다. 2026-10-05 gosky 카메라 1대·중복 0, vkcs 0대 확인.
--   SELECT ward_id, label, count(*) FROM camera GROUP BY ward_id, label HAVING count(*) > 1;
-- 중복이 있으면 데이터를 바꾸지 않고 명확한 메시지로 중단한다(V55와 같은 방식). 되돌리기: 제약만 DROP.
DO $$
DECLARE
    duplicates text;
BEGIN
    SELECT string_agg(ward_id || ':' || label, ',' ORDER BY ward_id, label)
      INTO duplicates
      FROM (SELECT ward_id, label FROM camera GROUP BY ward_id, label HAVING count(*) > 1) d;
    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION '같은 방에 카메라가 2대 이상이라 마이그레이션을 중단합니다(피보호자:방): %', duplicates;
    END IF;
END $$;

ALTER TABLE camera
    ADD CONSTRAINT uq_camera_ward_label UNIQUE (ward_id, label);
