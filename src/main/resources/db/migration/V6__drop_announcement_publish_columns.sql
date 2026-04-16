-- 공지 임시저장/게시 구분 제거 (등록 즉시 게시로 단순화)
ALTER TABLE announcements DROP COLUMN IF EXISTS is_published;
ALTER TABLE announcements DROP COLUMN IF EXISTS published_at;
