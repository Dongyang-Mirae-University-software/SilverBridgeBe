# 관리자 공지사항 목록 - 건수·페이지·제목 검색 (2026-10-26)

## 배경
- 관리자 공지사항 화면 시안에는 탭(전체·게시됨·임시저장)마다 건수, 제목 검색, 페이지 번호가 있다.
- 그런데 `GET /api/admin/announcement/select`·`/draft/select`는 배열만 돌려줘서 전체 건수와 마지막 페이지를 알 수 없었고, 검색 기능도 없었다.
- 관리자 웹 FE는 아직 없다. 착수 전에 소비처를 확인했다(2026-10-26).
  - FE 저장소에는 관리자 공지 호출이 없었다.
  - QA ADMIN-G24는 배열과 `PageResponse`를 둘 다 처리한다.
  - 그래서 새 경로를 만들지 않고 **기존 경로의 응답을 바꿨다**.
- 공개 공지 API(`/api/commonness/announcement/**`)는 바꾸지 않았다.

## API 계약 (관리자 웹 전달용, ADMIN 전용, 마이그레이션 없음)

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/api/admin/announcement/select?page=&size=&keyword=` | 게시된 공지 목록 (`PageResponse`) |
| GET | `/api/admin/announcement/draft/select?page=&size=&keyword=` | 임시저장 목록 (`PageResponse`) |
| GET | `/api/admin/announcement/summary` | 탭 건수 (**새 API**) |

### 목록 응답 (변경: 배열 → `PageResponse`)
```json
{
  "data": {
    "content": [
      { "id": 7, "authorId": "AD0001", "authorName": "관리자", "title": "서버 점검 안내",
        "content": "본문 앞 100자", "viewCount": 3,
        "createdAt": "2026-10-26T09:30:00+09:00", "updatedAt": "2026-10-26T09:30:00+09:00" }
    ],
    "page": 0, "size": 20, "totalElements": 11, "totalPages": 1, "last": true
  }
}
```
- 항목 칸은 이전과 같다. 임시저장 항목에는 `viewCount`가 없다.
- 탈퇴한 작성자는 `authorName`(과 `authorId`)이 `null`이다.
- 페이지 번호 UI는 `totalPages`로 그린다. 검색 결과 건수는 `totalElements`다.

### 요청 파라미터
| 이름 | 규칙 |
|---|---|
| `page` | 0부터 시작, 생략하면 0, 음수는 0으로 보정 |
| `size` | 생략하면 20, 0 이하는 20, 최대 50 |
| `keyword` | 선택. **제목만** 부분 일치로 찾는다. 대소문자를 무시하고 앞뒤 공백은 지운다. 공백만 보내면 검색하지 않는 것과 같다. **최대 50자(이모지 1개 = 1자), 넘으면 400 `INVALID_INPUT`**. `%`·`_`·`\`는 글자 그대로 찾는다. 제목이 빈 임시저장은 검색에 걸리지 않는다 |

- 정렬: 작성(생성) 일시 내림차순이고, 같은 시각이면 id 내림차순이다(기존과 같음).
- 본문: 목록의 `content`는 앞 100자 미리보기다(기존과 같음). 전체 본문은 상세 조회(`/select/detail/{id}`)로 받는다.

### 탭 건수
```json
{ "data": { "publishedCount": 12, "draftCount": 3 } }
```
- **검색어와 무관한 전체 건수**다. "전체" 탭 숫자는 `publishedCount + draftCount`로 계산한다.
- 검색 중의 결과 건수는 목록의 `totalElements`를 쓴다.
- "전체" 탭 목록 API는 따로 없다. 게시·임시저장이 다른 테이블이라 두 목록을 섞어 한 페이지로 자르면 페이지 번호가 맞지 않는다. 화면은 "전체" 탭에서 두 목록을 나눠 보여 주거나 게시 목록을 기본으로 둔다(FE 결정).

### 오류
| 상태 | code | 경우 |
|---|---|---|
| 400 | `INVALID_INPUT` | keyword 50자 초과 |
| 401 | - | 비로그인·토큰 만료 |
| 403 | - | ADMIN이 아님(보호자·피보호자) |

## 구현
- `admin/support/AnnouncementTitleKeyword`: 두 목록이 함께 쓰는 검색어 정규화를 한 곳에 뒀다.
  - 제목을 저장할 때와 같은 `TextSanitizer.sanitize`(NFC 정규화·보이지 않는 문자 제거·공백 정리)를 거친다. 그래서 분해형 한글로 입력해도 저장된 값과 맞는다.
  - 그 뒤 50자 검사, LIKE 메타문자 이스케이프, 소문자화를 한다. 회원·문의·이상감지 관리자 검색과 같은 규칙이다.
  - 50자 검사를 DB 조회보다 먼저 한다.
  - 잘라서 검색하지 않고 거절하는 이유: 잘라서 찾으면 관리자가 모르는 조건으로 결과가 나온다.
- 리포지토리 `findByTitleKeyword(keyword, Pageable)`: JPQL `lower(title) like concat('%', :keyword, '%') escape '\'`이고 `countQuery`가 있다. 검색어가 없으면 기존처럼 `findAll(Pageable)`을 쓴다.
- 탭 건수는 `count()` 2회다. 서버 캐시는 없다.
- 감사 로그는 남기지 않는다(조회는 기록하지 않는다는 관리자 공통 규칙).

## 바꾸지 않은 것 / 하지 않은 것
- 등록·수정·삭제·임시저장·게시 API, 감사 로그, 공개 공지 API, 조회수 증가 규칙은 바꾸지 않았다.
- 게시글을 임시저장으로 되돌리는 기능은 만들지 않았다. 화면에서 게시글을 수정할 때 [임시저장] 버튼을 숨기는 것으로 처리한다(FE).

## 테스트
- 단위: `AdminAnnouncementServiceTest`(건수·페이지 수, 검색어 정리·이스케이프·소문자, 공백만 있는 검색어, 50자 경계(이모지 50개 통과·51자 400·DB 미조회), 탭 건수·감사 로그 없음, 정렬·100자 축약·페이지 보정 유지)와 `AdminAnnouncementDraftServiceTest`(검색·건수·50자).
- 보안: `AdminAnnouncement(Draft)ControllerSecurityTest`. 비로그인이면 인증을 요구하고, GUARDIAN·WARD는 403이다. 탭 건수 API도 포함했다.
- HTTP 직렬화: `AdminAnnouncementControllerHttpTest`. `PageResponse` 칸, keyword 전달, keyword 생략 시 null, 400 `code`, 탭 건수 칸을 확인한다.
- 통합(실제 PostgreSQL): `AnnouncementTitleSearchIntegrationTest`. 대소문자 무시, `%`·`_`·`\`를 글자 그대로 찾는지, 검색 결과 기준 건수·페이지 수, 제목이 빈 임시저장 제외를 확인한다.
