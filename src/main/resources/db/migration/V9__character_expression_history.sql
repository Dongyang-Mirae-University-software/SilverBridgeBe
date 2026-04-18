CREATE TABLE character_expressions (
    id          BIGSERIAL PRIMARY KEY,
    ward_id     VARCHAR(6)         NOT NULL REFERENCES users(id),
    expression  VARCHAR(50)        NOT NULL,
    confidence  DOUBLE PRECISION,
    created_at  TIMESTAMPTZ        NOT NULL DEFAULT now()
);

CREATE INDEX idx_character_expressions_ward_id_created_at
    ON character_expressions (ward_id, created_at DESC);
