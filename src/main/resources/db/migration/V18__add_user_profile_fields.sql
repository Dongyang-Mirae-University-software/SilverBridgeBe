-- =============================================
-- V18: 회원가입 프로토타입(Image 8) 필수 입력 대응
--      성별 / 생년월일 / 우편번호 컬럼 추가
--
-- 가역적 변경: 컬럼 ADD(모두 NULL 허용) — 비가역 DDL 아님.
-- 기존 행은 자동 NULL(미입력). 신규 가입 및 프로필 수정 시의 필수화는
-- 애플리케이션 레이어(DTO @NotNull/@NotBlank)에서 강제한다.
-- =============================================

ALTER TABLE users
    ADD COLUMN gender     VARCHAR(10) NULL,
    ADD COLUMN birth_date DATE        NULL,
    ADD COLUMN postcode   VARCHAR(10) NULL;

-- 성별 허용값 제약 (여성/남성만). NULL 허용(기존 사용자 미입력).
ALTER TABLE users ADD CONSTRAINT chk_users_gender
    CHECK (gender IS NULL OR gender IN ('FEMALE', 'MALE'));

-- ⚠️ 비가역 위험 DDL: name 컬럼 VARCHAR(50) → VARCHAR(20) 축소.
--    기존 행에 20자 초과 이름이 있으면 이 문장에서 ERROR로 마이그레이션이 중단됩니다
--    (데이터 잘림 없이 안전 실패). 운영 적용 전 SELECT max(length(name)) FROM users; 로
--    20 초과 데이터가 없는지 반드시 확인하세요.
ALTER TABLE users
    ALTER COLUMN name TYPE VARCHAR(20);
