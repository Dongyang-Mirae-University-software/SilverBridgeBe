-- 이메일 소문자 통일 + 이름 앞뒤 공백 정리 (2026-10-02, QA AUTH-G09·XCUT-G16·FEUX-G12·AUTH-G13)
--
-- 왜 필요한가: 가입·로그인·비밀번호 찾기가 이메일을 입력 그대로 저장·정확 일치로 조회해, User@x.com 과 user@x.com 이
-- 별개 계정으로 가입되고 가입 때와 다른 대소문자로는 로그인·복구가 실패했다. 이제 서비스 입구에서 trim+소문자로
-- 정규화한다(AuthInputNormalizer). 그 조회가 기존 계정을 찾으려면 저장된 이메일도 소문자여야 하므로 여기서 맞춘다.
--
-- ⚠️ 비가역: 원래 대소문자 표기는 보존하지 않는다(로그인 ID로서 의미가 없는 차이다).
-- ⚠️ 대소문자만 다른 중복 계정이 이미 있으면 어느 쪽을 남길지 사람이 정해야 하므로, 데이터를 바꾸기 전에 중단한다.
--    배포 전 점검 쿼리: SELECT lower(email), array_agg(id) FROM users GROUP BY lower(email) HAVING count(*) > 1;

-- ① 대소문자만 다른 중복 이메일 검사 - 있으면 아무것도 바꾸지 않고 중단
--    메시지에는 이메일 대신 사용자 id 묶음만 싣는다(배포 로그에 개인정보를 남기지 않게).
DO $$
DECLARE
    dup TEXT;
BEGIN
    SELECT string_agg('[' || ids || ']', ', ')
      INTO dup
      FROM (SELECT string_agg(id, ',' ORDER BY id) AS ids
              FROM users
             GROUP BY lower(email)
            HAVING count(*) > 1) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION '대소문자만 다른 중복 이메일이 있어 마이그레이션을 중단합니다: %', dup;
    END IF;
END $$;

-- ② 이메일 소문자화 (바뀌는 행만)
UPDATE users SET email = lower(email) WHERE email <> lower(email);

-- ③ 대소문자 무시 유일성 - 서비스가 정규화를 빠뜨려도 DB가 두 번째 계정을 막는다.
--    기존 uq_users_email(V1)은 그대로 둔다(정확 일치 조회의 인덱스이기도 하다).
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- ④ 이름 앞뒤 일반 공백 정리 - 가입·찾기가 이름을 정리해 조회하므로 "홍길동 "으로 저장된 행이 "홍길동"으로 찾아지게 한다.
--    공백만 있는 이름은 빈 문자열이 되므로 건드리지 않는다. 제로폭·전각 공백 등은 SQL로 안전하게 다루기 어려워 범위 밖이다.
UPDATE users SET name = btrim(name) WHERE name <> btrim(name) AND btrim(name) <> '';
