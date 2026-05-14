-- =============================================
-- 테스트 계정 시드 데이터
-- 실행 (로컬): psql -U {username} -d {dbname} -f db/seed.dev.sql
-- 실행 (dev 서버): cat db/seed.dev.sql | docker exec -i dmu-dev-db sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB'
-- =============================================

-- users.id 는 VARCHAR(6) 영숫자 (UserIdGenerator 와 동일 포맷).
-- 시드는 사람이 알아보기 쉬운 고정 ID 사용 — 실제 사용자 ID 와 충돌 가능성은 무시할 수준.
-- 비밀번호 해시:
--   admin12#  → $2b$10$t./xanLm8U/U2eI.Ak1PhOALrZBoyTe/kzTLONObPqKKRMsQLqm9W
--   test1234! → $2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm

-- =============================================
-- 관리자 계정 (1개)
-- =============================================
INSERT INTO users (id, email, password, name, phone, role, status, provider)
VALUES (
    'admin1',
    'admin@test.com',
    '$2b$10$t./xanLm8U/U2eI.Ak1PhOALrZBoyTe/kzTLONObPqKKRMsQLqm9W',
    '관리자',
    '01000000000',
    'ADMIN',
    'ACTIVE',
    'LOCAL'
)
ON CONFLICT (email) DO NOTHING;

-- =============================================
-- 보호자 계정 (5개) — 비밀번호: test1234!
-- =============================================
INSERT INTO users (id, email, password, name, phone, role, status, provider) VALUES
    ('gd0001', 'guardian01@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '보호자01', '01011110001', 'GUARDIAN', 'ACTIVE', 'LOCAL'),
    ('gd0002', 'guardian02@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '보호자02', '01011110002', 'GUARDIAN', 'ACTIVE', 'LOCAL'),
    ('gd0003', 'guardian03@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '보호자03', '01011110003', 'GUARDIAN', 'ACTIVE', 'LOCAL'),
    ('gd0004', 'guardian04@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '보호자04', '01011110004', 'GUARDIAN', 'ACTIVE', 'LOCAL'),
    ('gd0005', 'guardian05@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '보호자05', '01011110005', 'GUARDIAN', 'ACTIVE', 'LOCAL')
ON CONFLICT (email) DO NOTHING;

-- =============================================
-- 피보호자 계정 (10개) — 비밀번호: test1234!
-- =============================================
INSERT INTO users (id, email, password, name, phone, role, status, provider) VALUES
    ('ward01', 'ward01@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자01', '01022220001', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward02', 'ward02@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자02', '01022220002', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward03', 'ward03@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자03', '01022220003', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward04', 'ward04@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자04', '01022220004', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward05', 'ward05@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자05', '01022220005', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward06', 'ward06@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자06', '01022220006', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward07', 'ward07@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자07', '01022220007', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward08', 'ward08@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자08', '01022220008', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward09', 'ward09@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자09', '01022220009', 'WARD', 'ACTIVE', 'LOCAL'),
    ('ward10', 'ward10@test.com', '$2b$10$HIoVP5gEsKlbfOy4Motcn.oBJB76jKQw6R0u6/Zdlxx8scy6rVBxm', '피보호자10', '01022220010', 'WARD', 'ACTIVE', 'LOCAL')
ON CONFLICT (email) DO NOTHING;
