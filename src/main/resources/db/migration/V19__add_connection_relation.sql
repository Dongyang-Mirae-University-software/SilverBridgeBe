-- =============================================
-- V19: 연결(connections)에 보호자-피보호자 관계(relation) 컬럼 추가
--      프로토타입 "피보호자 등록" 화면에서 보호자가 피보호자와의 관계를
--      직접 입력(아들/딸/며느리/사위/손자/손녀/기타)하도록 변경.
--
-- 가역적 변경: NULL 허용 ADD COLUMN — 기존 PENDING/ACTIVE 행은 NULL 유지.
--             신규 요청에서의 필수화는 DTO(@NotBlank)로 애플리케이션 레이어가 강제.
-- =============================================

ALTER TABLE connections
    ADD COLUMN relation VARCHAR(10) NULL;

COMMENT ON COLUMN connections.relation IS '피보호자와의 관계 (예: 아들, 딸, 며느리, 사위, 손자, 손녀, 기타). 신규 요청은 필수, 기존 행은 NULL 허용.';
