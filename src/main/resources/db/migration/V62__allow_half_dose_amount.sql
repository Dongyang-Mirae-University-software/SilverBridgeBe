-- 복용량 반 알(0.5 단위) 지원 (2026-10-22, "약 추가" 시안의 "반알").
--
-- dose_amount: INT -> NUMERIC(3,1). 기존 정수 값은 그대로 옮겨진다(1 -> 1.0, 손실 없음).
-- CHECK: 0.5 이상 99 이하, 0.5의 배수(엔티티·DTO 규칙 DoseAmounts 와 같은 범위 - API를 거치지 않은 쓰기도 막는다).
--   기존 DTO가 1~99 정수만 받아 왔으므로 기존 행은 모두 이 조건을 만족한다(머지 전 두 서버에서 확인).
--
-- 되돌리기(비가역 여부): 적용 자체는 데이터를 잃지 않는다. 다만 0.5 단위 값이 한 건이라도 저장된 뒤에는
--   INT 로 되돌리면 그 값이 반올림·손실되므로, 그때부터는 사실상 되돌릴 수 없다(되돌리려면 반 알 행을 먼저 정리해야 한다).
--   ALTER TYPE 은 테이블을 다시 쓰지만 medication 은 작은 테이블이라 잠금 시간은 짧다.
ALTER TABLE medication
    ALTER COLUMN dose_amount TYPE NUMERIC(3, 1) USING dose_amount::NUMERIC(3, 1);

ALTER TABLE medication
    ADD CONSTRAINT chk_medication_dose_amount
        CHECK (dose_amount >= 0.5 AND dose_amount <= 99 AND dose_amount * 2 = trunc(dose_amount * 2));
