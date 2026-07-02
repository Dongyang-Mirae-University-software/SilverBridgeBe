# 문의하기(고객센터) 기능 구현 — 보호자 작성 + 관리자 답변

**작업 일자**: 2026-07-01
**관련 커밋(예정)**: `feat(inquiry): 문의하기 기능 추가 (보호자 작성 + 관리자 답변)`
**신규 도메인**: `domain/inquiry` (완전 신규)

---

## 1. 기능 정의

보호자가 고객센터에 문의를 작성하고, 관리자가 답변하는 기능.

- **작성 주체**: 보호자(GUARDIAN)만. WARD/ADMIN 은 작성 불가(403).
- **카테고리**(고정 enum 5종): 이상감지 관련 / 병원 관련 / 계정·회원 / 서비스 이용 / 기타
- **상태**: 답변 대기(WAITING) / 답변 완료(ANSWERED)
- **답변 알림**: 답변 완료 시 작성자에게 FCM 발송(**선택 알림** — 사용자 알림 설정에 따름, SOS 같은 필수 아님).

---

## 2. 설계

### 2-1. 테이블 `inquiries` (Flyway V28)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT IDENTITY PK | |
| user_id | VARCHAR(6) NOT NULL | 작성자(보호자), FK→users **ON DELETE CASCADE** |
| category | VARCHAR(20) NOT NULL | InquiryCategory |
| title | VARCHAR(100) NOT NULL | |
| content | TEXT NOT NULL | |
| status | VARCHAR(20) NOT NULL DEFAULT 'WAITING' | InquiryStatus |
| answer | TEXT NULL | 답변 전 null |
| answered_by | VARCHAR(6) NULL | 답변 관리자, FK→users **ON DELETE SET NULL** |
| answered_at | TIMESTAMPTZ NULL | |
| created_at / updated_at | TIMESTAMPTZ NOT NULL | BaseTimeEntity(Auditing) |

**인덱스**: `(user_id, created_at DESC)`(보호자 본인 목록) · `(status, created_at DESC)`(관리자 탭·상태 필터) · `(category)`(카테고리 필터).

**FK 정책 근거**:
- `user_id → CASCADE`: 보호자 탈퇴(hard delete) 시 본인 문의도 함께 삭제(connections 와 동일 정책). 별도 정리 리스너 불필요 — DB가 처리. 따라서 작성자 없는 orphan 문의는 존재 불가.
- `answered_by → SET NULL`: 관리자 계정 삭제 시에도 답변 내용은 보존.

### 2-2. enum (`global/enums`, 순수 enum + 라인 주석 — 프로젝트 관례)

- `InquiryCategory`: `ANOMALY`(이상감지) / `HOSPITAL`(병원) / `ACCOUNT`(계정·회원) / `SERVICE`(서비스 이용) / `ETC`(기타)
- `InquiryStatus`: `WAITING`(답변 대기) / `ANSWERED`(답변 완료)
- 한글 표시명은 서버가 내려주지 않음 — **프론트가 코드값→라벨 매핑**(§4 매핑표 참조).

### 2-3. 엔드포인트

| 주체 | 메서드·경로 | 설명 |
|---|---|---|
| 보호자 | `POST /api/guardian/inquiry` | 문의 작성(GUARDIAN만) |
| 보호자 | `GET /api/guardian/inquiry` | 내 문의 목록(본인만, 최신순) |
| 보호자 | `GET /api/guardian/inquiry/{id}` | 내 문의 상세(본인만, IDOR 차단) |
| 관리자 | `GET /api/admin/inquiry` | 전체 목록(탭 카운트 + 카테고리·상태 필터 + 검색 + 페이징) |
| 관리자 | `GET /api/admin/inquiry/{id}` | 문의 상세(답변 모달용) |
| 관리자 | `POST /api/admin/inquiry/{id}/answer` | 답변 작성(WAITING→ANSWERED) |

- 보호자: 클래스 레벨 `@PreAuthorize("hasRole('GUARDIAN')")`.
- 관리자: `/api/admin/**` → SecurityConfig 경로 매칭으로 ADMIN 강제(`@PreAuthorize` 불요).
- 인증 주체: `@AuthenticationPrincipal String {guardianId|adminId}`.

### 2-4. 답변 알림 (선택)

`AdminInquiryService.answer()` 커밋 후 → `InquiryAnsweredEvent(inquiryId, authorUserId)` 발행
→ `InquiryNotificationListener.handleAnswered()`(`@Async("notificationExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)`)
→ `NotificationDispatcher.dispatch(authorUserId, INQUIRY_ANSWERED, content)`.

- `NotificationType.INQUIRY_ANSWERED(false)` = 선택 알림 → 사용자 설정 활성 채널로 발송(기본 FCM ON).
- 문구: title "문의 답변 완료", body "문의하신 내용에 답변이 등록되었습니다.", data `{type: INQUIRY_ANSWERED, inquiryId}`.
- connection/sos 리스너와 동일 패턴. 단, **WebSocket 실시간 발송은 없음**(문의 답변은 실시간 동기화가 필요한 화면 이벤트가 아니라 푸시로 충분).

---

## 3. 구현 내용 + 변경 파일

### 신규 파일 (17개)

**마이그레이션**
- `src/main/resources/db/migration/V28__add_inquiries.sql`

**enum (global)**
- `global/enums/InquiryCategory.java`
- `global/enums/InquiryStatus.java`

**엔티티·리포지토리**
- `domain/inquiry/entity/Inquiry.java` (BaseTimeEntity 상속, setter 없이 `answer()` 도메인 메서드로 상태 전환)
- `domain/inquiry/repository/InquiryRepository.java` (본인 목록·`countByStatus`·`searchForAdmin` 동적 필터 JPQL)

**DTO**
- `domain/inquiry/dto/InquiryCreateRequest.java` (검증: category NotNull, title ≤100, content ≤2000)
- `domain/inquiry/dto/InquiryResponse.java` (보호자 목록·상세 공용)
- `domain/inquiry/dto/AdminInquiryResponse.java` (관리자 목록 행 — content 미포함)
- `domain/inquiry/dto/AdminInquiryDetailResponse.java` (관리자 상세 — 본문+답변)
- `domain/inquiry/dto/InquiryAnswerRequest.java` (검증: answer NotBlank ≤2000)
- `domain/inquiry/dto/AdminInquiryListResponse.java` (탭 카운트 + 페이지)

**서비스**
- `domain/inquiry/service/InquiryService.java` (보호자 — 작성·본인목록·본인상세+IDOR)
- `domain/inquiry/service/AdminInquiryService.java` (관리자 — 목록·상세·답변+이벤트)

**이벤트·리스너**
- `domain/inquiry/event/InquiryAnsweredEvent.java`
- `domain/inquiry/listener/InquiryNotificationListener.java`

**컨트롤러**
- `domain/inquiry/controller/GuardianInquiryController.java`
- `domain/inquiry/controller/AdminInquiryController.java`

**공통 신규**
- `global/response/PageResponse.java` (코드베이스 첫 페이징 응답 래퍼 — Spring Data Page를 평평하게 감쌈)

### 수정 파일 (2개)
- `global/exception/ErrorCode.java` — `INQUIRY_NOT_FOUND`(404) / `INQUIRY_NOT_AUTHORIZED`(404 위장) / `INQUIRY_ALREADY_ANSWERED`(409) 추가
- `notification/dispatch/NotificationType.java` — `INQUIRY_ANSWERED(false)` 추가

### 테스트 (3개)
- `InquiryServiceTest` — 작성 시 WAITING 초기화 / 본인 목록·상세 / 타인 접근 차단(IDOR) / 미존재 404
- `AdminInquiryServiceTest` — 탭 카운트·작성자명 매핑 / 공백 검색어 null 정규화 / 답변 상태전환+이벤트 발행 / 재답변 409(이벤트 미발행) / 미존재 404
- `InquiryNotificationListenerTest` — 답변 완료 → 작성자에게 INQUIRY_ANSWERED 선택 알림 문구·타입 검증

---

## 4. 프론트 인계

### 4-1. 카테고리 / 상태 코드 → 한글 라벨 매핑 (프론트에서 처리)

| category 코드 | 라벨 |
|---|---|
| `ANOMALY` | 이상감지 관련 |
| `HOSPITAL` | 병원 관련 |
| `ACCOUNT` | 계정·회원 |
| `SERVICE` | 서비스 이용 |
| `ETC` | 기타 |

| status 코드 | 라벨 |
|---|---|
| `WAITING` | 답변 대기 |
| `ANSWERED` | 답변 완료 |

### 4-2. 보호자 API

**문의 작성** — `POST /api/guardian/inquiry`
```json
// 요청
{ "category": "SERVICE", "title": "제목", "content": "내용" }
// 응답 data
{ "id": 1, "category": "SERVICE", "title": "제목", "content": "내용",
  "status": "WAITING", "answer": null, "answeredAt": null,
  "createdAt": "2026-07-01T10:00:00+09:00" }
```

**내 문의 목록** — `GET /api/guardian/inquiry` → `data`: `InquiryResponse[]` (최신순). 답변 완료 건은 `answer`/`answeredAt` 채워짐.

**내 문의 상세** — `GET /api/guardian/inquiry/{id}` → `data`: `InquiryResponse`. 타인 문의 ID는 404.

### 4-3. 관리자 API

**목록** — `GET /api/admin/inquiry?category=&status=&keyword=&page=0&size=20` (모든 쿼리 optional)
```json
// 응답 data
{
  "totalCount": 137, "waitingCount": 12, "answeredCount": 125,   // 탭 배지(전역 카운트, 필터 무관)
  "inquiries": {
    "content": [
      { "id": 1, "category": "SERVICE", "title": "제목",
        "authorId": "aB3x9Z", "authorName": "김보호",   // 탈퇴 시 authorName null
        "status": "WAITING", "createdAt": "2026-07-01T10:00:00+09:00" }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
  }
}
```
- **탭 카운트**(`totalCount`/`waitingCount`/`answeredCount`)는 필터·검색과 무관한 전역 카운트 → 탭 배지 `전체(N)/답변 대기(N)/답변 완료(N)`에 그대로.
- **필터·검색은 `inquiries`(목록)에만 적용**. `keyword`는 제목·내용·작성자명 부분 일치(대소문자 무시).

**상세** — `GET /api/admin/inquiry/{id}`
```json
// 응답 data (AdminInquiryDetailResponse)
{ "id": 1, "category": "SERVICE", "title": "제목", "content": "내용",
  "authorId": "aB3x9Z", "authorName": "김보호",
  "status": "ANSWERED", "answer": "답변 내용", "answeredByName": "관리자",
  "answeredAt": "2026-07-01T11:00:00+09:00", "createdAt": "2026-07-01T10:00:00+09:00" }
```

**답변 작성** — `POST /api/admin/inquiry/{id}/answer`
```json
// 요청
{ "answer": "확인 후 조치했습니다." }
// 응답 data: 답변 완료된 AdminInquiryDetailResponse (status=ANSWERED)
```
- 상태 WAITING→ANSWERED 전환, `answered_by`/`answered_at` 기록.
- **이미 답변된 문의 재답변 시 409**(`INQUIRY_ALREADY_ANSWERED`).
- 답변 완료 시 작성자에게 **FCM 알림 발송**(선택 — 사용자 알림 설정 OFF면 미발송). data: `{type: "INQUIRY_ANSWERED", inquiryId}`.

### 4-4. 공통 응답 래퍼
모든 응답은 `{ success, message, data }`(`ApiResponse`)로 감싸짐. 데이터는 `data` 필드.

---

## 5. 테스트 결과

- 신규 테스트 3개 클래스 전부 통과.
- `./gradlew build`(전체 테스트 포함) **통과** — 기존 회귀 0건.
- Flyway V28 신규 파일만 추가(기존 마이그레이션 수정 없음).

---

## 6. 향후 연결 지점 (범위 외, 열어둠)

- **관리자 대시보드 "미처리 문의" 통계**: 대시보드 통계 API 자체가 미구현(admin 도메인은 공지·감사로그만 존재, `RedisKeys.ADMIN_DASHBOARD_SUMMARY` 미사용 상수만 예약됨). 이번 범위 밖. 단, `InquiryRepository.countByStatus(WAITING)`를 제공하므로 대시보드 신설 시 즉시 연결 가능.
- **감사 로그**: 관리자 답변을 `admin_audit_logs`에 남기려면 `AdminAuditAction` enum + `chk_admin_audit_action` CHECK(V27) 동기화 마이그레이션이 필요해 이번엔 제외. 필요 시 별도 작업.
- **카테고리 `HOSPITAL`**: 문의 분류일 뿐, 병원 예약 기능(제거된 V24 hospital_reservations)과 무관.
