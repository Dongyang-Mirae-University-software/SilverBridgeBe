-- ADMIN-G05: 공지 상세를 조회하면 view_count UPDATE 때문에 updated_at(수정 일시)이 함께 바뀌었다.
-- V1의 trg_announcements_updated_at(V31 테이블 개명 후에도 announcement에 그대로 붙어 있다)은 모든 UPDATE에서 발동하므로,
-- 제목·내용이 실제로 바뀔 때만 발동하도록 WHEN 조건을 건다. 같은 함수(update_updated_at)를 재사용한다.
-- view_count만 바뀐 UPDATE(조회수 원자 증가)와 작성자 탈퇴(author_id SET NULL)는 수정 일시를 건드리지 않는다.
DROP TRIGGER IF EXISTS trg_announcements_updated_at ON announcement;

CREATE TRIGGER trg_announcements_updated_at
    BEFORE UPDATE ON announcement
    FOR EACH ROW
    WHEN (OLD.title IS DISTINCT FROM NEW.title OR OLD.content IS DISTINCT FROM NEW.content)
    EXECUTE FUNCTION update_updated_at();
