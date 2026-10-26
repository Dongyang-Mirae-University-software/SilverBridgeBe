# 관리자 공지 목록 건수·페이지·제목 검색 기능 점검 (2026-10-26)

- 대상: BE #381 (`6c0b86c`). 관리자 공지·임시저장 목록 `PageResponse` 전환, `keyword`, `GET /api/admin/announcement/summary`
- 템플릿: B(기능 점검). 코드 정독, 변이 시험 3종, gosky api-docs·비로그인 실측, 두 서버 DB 읽기 전용 조회
- 코드 수정 없음

## 종합 판정: ✅ PASS
🔴 0 · 🟠 0 · 🟡 0 · 🟢 4. 🟢 L-1은 이번 PR이 만든 문제가 아니라 관리자 목록 공통으로 원래 있던 것이다.

## 점검 파일
- `admin/controller/AdminAnnouncementController.java`, `AdminAnnouncementDraftController.java`
- `admin/service/AdminAnnouncementService.java`, `AdminAnnouncementDraftService.java`
- `admin/support/AnnouncementTitleKeyword.java`
- `admin/dto/AdminAnnouncementSummaryResponse.java`
- `announcement/repository/AnnouncementRepository.java`, `AnnouncementDraftRepository.java`
- 공용 코드: `global/response/PageResponse`, `admin/support/AdminPaging`, `global/validation/TextSanitizer`, `global/aop/ApiLoggingAspect`, `GlobalExceptionHandler`, `SecurityConfig`

## PHASE A. 보안·인가 - PASS

| 엔드포인트 | 비로그인 | GUARDIAN | WARD | ADMIN | 근거 |
|---|---|---|---|---|---|
| `GET /api/admin/announcement/select` | 401 | 403 | 403 | 200 | URL 규칙 `/api/admin/**` `hasRole("ADMIN")`과 클래스 레벨 `@PreAuthorize`로 이중 차단. 보안 테스트로 고정 |
| `GET /api/admin/announcement/draft/select` | 401 | 403 | 403 | 200 | 위와 같음 |
| `GET /api/admin/announcement/summary` (새 API) | 401 (gosky 실측) | 403 | 403 | 200 | 같은 컨트롤러라 클래스 레벨 게이트가 적용됨. 보안 테스트에 추가함 |

- **IDOR**: 해당 없음. 관리자 전역 조회라 본인 자원 개념이 없다.
- **입력 검증**: 정리 후 50자 초과는 400이다. 검색어는 바인딩 파라미터로 넘겨 인젝션이 막히고, LIKE 메타문자는 이스케이프한다. 원문 길이는 Tomcat 요청 줄 상한(8KB)이 막는다.
- **로그·PII**: `ApiLoggingAspect`는 `getRequestURI()`만 남겨 쿼리 문자열(`keyword`)이 로그에 남지 않는다. 새 로그는 없다. 조회라 감사 로그도 없다(관리자 공통 규칙).

## PHASE B. 기능 정합성 - PASS
- **건수**:
  - 목록 `totalElements`·`totalPages`는 `countQuery` 기준이다. 실제 PostgreSQL에서 검색 결과 기준 건수와 페이지 수를 확인했다(`AnnouncementTitleSearchIntegrationTest`, vkcs).
  - `/summary`는 `count()` 2회다. 운영 대시보드 `announcementDrafts`도 `announcementDraftRepository.count()`라 `draftCount`와 기준이 같다.
- **검색**:
  - 대소문자를 무시한다. 두 서버 DB가 `en_US.utf8`이라 `lower('ÉÄ')` = `éä`로 Java `toLowerCase()`와 같다.
  - `%`·`_`·`\`는 글자 그대로 찾는다(통합 테스트).
  - 제목이 빈 임시저장은 검색에서 빠진다.
- **저장값과 검색어 정리 기준**: 검색어는 저장할 때와 같은 `TextSanitizer.sanitize`를 거친다. 저장된 제목 중 정리 전 형태(NBSP·제로폭·전각 공백·연속 공백·앞뒤 공백·비 NFC)로 남은 것은 **0건**이다(gosky 공지 4건·임시저장 0건, vkcs 0건, 읽기 전용 조회).
- **정렬·축약·페이지 규칙**: 바뀌지 않았다(`createdAt DESC, id DESC`, 100자, `AdminPaging`). 단위 테스트가 검색 경로의 `Pageable` 정렬까지 확인한다.

## PHASE C. 구조·계약 - PASS
- 트랜잭션: 조회 메서드는 모두 `@Transactional(readOnly = true)`다. 이벤트·알림은 없다.
- N+1: 작성자를 `findAllById`로 한 번에 조회한다(기존 그대로).
- 계약: gosky `/v3/api-docs`가 계약 문서와 같다.
  - 두 목록: 파라미터 `page`·`size`·`keyword`, 응답 `PageResponse...`(content·page·size·totalElements·totalPages·last), 400 문서화
  - `/summary`: `publishedCount`·`draftCount`
- 하위 호환:
  - 배열에서 `PageResponse`로 바뀐 것은 깨는 변경이지만 소비처가 없다(FE 호출 0건, QA ADMIN-G24는 두 형태 모두 처리).
  - 공개 공지 API는 그대로다.
- 인덱스: 제목 `%..%` LIKE는 인덱스를 못 쓰고 전체를 훑는다. 규모상 문제없다(L-3).

## PHASE D. 테스트 - PASS
- 단위·보안·HTTP 직렬화·실 DB 통합 테스트가 있다. 관련 단위 테스트는 33건이다.
- 변이 시험(관련 단위 테스트로 판정):

| 변이 | 결과 |
|---|---|
| V1 `%` 이스케이프 제거 | 검출 |
| V2 길이 상한 50 → 51 | 검출 |
| V3 검색어를 무시하고 항상 전체 조회 | 검출 |

- JPQL의 `escape '\'` 절 제거는 실 DB 통합 테스트(vkcs, CD 배포 전 단계)만 잡는다. 단위 테스트로는 못 잡는 구조다.

## 이슈

### 🟢 L-1 아주 큰 `page`는 500 (원래부터, 관리자 목록 공통)
- 원인: `page × size`가 `Integer.MAX_VALUE`를 넘으면 Spring Data `PageableUtils`가 `InvalidDataAccessApiUsageException`("Page offset exceeds Integer.MAX_VALUE")을 던진다.
- 결과: `GlobalExceptionHandler`에 맞는 핸들러가 없어 `Exception` 핸들러로 가서 500이 된다. 예: `page=100000000&size=50`.
- 범위: `AdminPaging.of`를 쓰는 9곳이 모두 같다. 이번 PR 전부터 있던 동작이다.
  - (정정) 9곳 중 2곳은 관리자 밖이다: 공개 공지 목록(`AnnouncementService`)·보호자 내 문의(`InquiryService`). 보호자 SOS·이상감지 이력(`PageRequest` 직접 생성)도 같은 결함이 있었다. 즉 일반 사용자도 500을 낼 수 있었다.
- 영향: 데이터 영향은 없다. 오류 로그 한 줄이 남는다.
- 제안(별도 작업): `AdminPaging.page()`에 상한(예: offset이 int 범위 안이 되도록)을 두거나, 이 예외를 400으로 바꾼다.

### 🟢 L-2 터키어 `İ` 소문자 차이 (수용)
- Java `"İ".toLowerCase()`는 `i` + 결합 점 두 글자가 되고, PostgreSQL `lower('İ')`는 `i` 한 글자라 이 글자가 든 검색어는 맞지 않는다.
- 회원·문의·이상감지 관리자 검색도 같은 방식이고, 실사용(한글·영문 제목)과 무관해 수용한다.

### 🟢 L-3 제목 LIKE 전체 훑기 (수용)
- 공지는 gosky 4건 규모다. 수천 건 이상이 되면 `pg_trgm` 인덱스를 검토한다.

### 🟢 L-4 테스트 공백 (수용)
- 임시저장 서비스 테스트는 검색 경로의 정렬을 직접 확인하지 않는다(공지 쪽만 확인). 코드는 공지와 같은 형태다.
- 분해형 한글 입력 매칭은 서비스 단위 테스트가 아니라 `TextSanitizerTest`의 NFC 검증에 맡겨져 있다.

## 수정용 커밋 메시지 초안 (L-1을 고칠 경우)
`fix: 관리자 목록 페이지 번호 상한 - 큰 page 값의 500을 막는다`

## 후속 조치 (2026-10-26, `fix/admin-announcement-list-audit-followup`)

| 이슈 | 조치 |
|---|---|
| L-1 | 공용 `global/response/PageOffsets.clampPage`를 만들었다. offset이 int를 넘는 page는 범위 안의 마지막 페이지로 **보정**한다(사용자 결정, 400 아님). `AdminPaging.of`가 이 유틸을 써서 9곳에 한 번에 적용되고, 보호자 SOS·이상감지 이력 2곳도 같은 유틸을 쓴다. 결과는 빈 `content`와 정확한 `totalElements`다. 예외를 전역 400으로 바꾸는 방식은 다른 결함까지 가려서 택하지 않았다 |
| L-2 | 검색어의 Java `toLowerCase()`를 뺐다. JPQL을 `lower(title) like lower(concat(...)) escape '\'`로 바꿔 양쪽 모두 DB가 소문자로 만든다. 실 DB에서 `İSTANBUL`·`istanbul` → `İstanbul…`, `école` → `École…` 일치를 확인했다. 다른 관리자 검색(회원·문의·이상감지·알림)은 같은 Java 소문자화를 쓰지만 이번 범위 밖이다(같은 한계, 실사용 무관) |
| L-3 | 인덱스는 넣지 않았다(사용자 결정). 두 리포지토리 주석에 "수천 건 이상이면 `pg_trgm` GIN 인덱스 검토" 기준을 남겼다 |
| L-4 | 임시저장 검색 경로의 정렬 검증, 분해형(NFD) 한글 검색어가 NFC로 전달되는지 검증을 추가했다 |

- 테스트: `PageOffsetsTest`(size 1·20·50 경계), `AdminSupportTest`, `GuardianSosServiceTest`, `GuardianAnomalyHistoryFilterTest`, 공지 서비스 2종, 실 DB `AnnouncementTitleSearchIntegrationTest`(İ·É, 큰 page → 빈 페이지 + 전체 건수 1). `./gradlew build` BUILD SUCCESSFUL(단위 1897건 실패 0).
- 변이 시험: V4 `AdminPaging` 보정 제거 / V5 보호자 SOS 보정 제거 / V6 Java 소문자화 복귀 - 모두 검출.
