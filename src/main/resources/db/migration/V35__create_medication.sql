-- 복약 알림 (2026-08-04)
--
-- 보호자가 피보호자의 약을 등록하고, 피보호자가 실제 복용을 체크한다.
-- 체크는 피보호자만 할 수 있고(보호자 화면의 체크 표시는 읽기 전용), 그 결과가 보호자에게 보인다.
-- 조회·등록·삭제는 모두 "요청 시점에 ACTIVE 연결"인 관계에서만 허용된다(SOS 이력과 동일한 인가 원칙).

-- 약 마스터. 하루 1회 복용 기준의 반복 일정이며, 특정 날짜의 복용 여부는 medication_intake가 담는다.
--
-- ward_id  : 소유자. 피보호자 탈퇴 시 복약 일정도 존재 이유가 없으므로 CASCADE.
-- created_by: 등록한 보호자. 탈퇴 시 그 보호자가 등록한 약도 함께 사라진다(CASCADE).
--   ※ 실제 삭제·안내는 MedicationWithdrawalListener가 명시적으로 수행한다 — CASCADE가 먼저 지우면
--     "몇 건이 중지됐는지" 셀 수 없어 남은 보호자에게 안내를 보낼 수 없다. 여기 CASCADE는 리스너가
--     실패했거나 스윕 purge로 리스너를 건너뛴 경우를 회수하는 안전망이다.
CREATE TABLE medication (
    id          BIGSERIAL    PRIMARY KEY,
    ward_id     VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by  VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    time_slot   VARCHAR(20)  NOT NULL,           -- MORNING / LUNCH / DINNER / BEDTIME
    dose_time   TIME         NOT NULL,           -- 실제 복용 시각 (미지정 시 time_slot 기본값)
    dose_amount INT          NOT NULL DEFAULT 1, -- 복용량(정). 엔티티가 int라 INT로 맞춘다(ddl-auto=validate)
    memo        VARCHAR(100),                    -- "식사와 함께" 등 (선택)
    deleted_at  TIMESTAMPTZ,                     -- soft delete — 과거 복용 이력을 보존하기 위함
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 목록 조회는 항상 "삭제되지 않은 약"만 대상으로 한다 → 부분 인덱스로 삭제된 행을 색인에서 제외.
CREATE INDEX idx_medication_ward ON medication (ward_id) WHERE deleted_at IS NULL;
-- 보호자 탈퇴 정리(등록자 기준 조회)용.
CREATE INDEX idx_medication_created_by ON medication (created_by) WHERE deleted_at IS NULL;

-- 날짜별 복용 체크. 행이 존재하면 그날 복용한 것이고, 체크 해제는 행 삭제다.
-- UNIQUE (medication_id, dose_date)로 같은 약을 같은 날 두 번 체크할 수 없다(중복 요청 멱등).
CREATE TABLE medication_intake (
    id            BIGSERIAL   PRIMARY KEY,
    medication_id BIGINT      NOT NULL REFERENCES medication(id) ON DELETE CASCADE,
    dose_date     DATE        NOT NULL,   -- KST 기준 복용 예정일
    taken_at      TIMESTAMPTZ NOT NULL,   -- 실제 체크한 시각
    CONSTRAINT uq_medication_intake UNIQUE (medication_id, dose_date)
);

-- 피보호자별 복약 알림 ON/OFF (보호자 화면의 "알림 켜짐/꺼짐" 토글).
-- 행이 없으면 애플리케이션 기본값(ON)을 따른다 → 기존 사용자 백필 불필요(sos_setting과 동일한 방식).
CREATE TABLE medication_setting (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    alarm_enabled BOOLEAN     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_medication_setting_user UNIQUE (user_id)
);
