# 문의하기(고객센터) 기능 통합 점검 — audit spot-check

- **점검 일자**: 2026-07-03
- **점검 대상 커밋**: `2500532 feat(inquiry): 문의하기 기능 추가 (보호자 작성 + 관리자 답변)` (PR #209), 후속 `8e19f21 docs: 문의 API Swagger 태그 등록` (PR #211)
- **마이그레이션**: `V28__add_inquiries.sql`
- **범위**: `domain/inquiry` 전체 + 마이그레이션 + 알림 이벤트/리스너 + 관리자 목록(탭/필터/검색) — **문의 도메인만** (다른 도메인 미변경)
- **방식**: 정적 점검(코드 수정·DB 조작·git 쓰기 없음)
- **환경 검증**: `./gradlew build -x test` ✅ (exit 0) / 문의 도메인 테스트 3종 ✅ (exit 0)

---

## 1. 점검한 파일 (프로덕션 15 + 테스트 3 + 마이그레이션 1)

```
domain/inquiry/
├─ controller/GuardianInquiryController.java   보호자: 작성/내목록/내상세
├─ controller/AdminInquiryController.java      관리자: 목록/상세/답변
├─ service/InquiryService.java                 보호자 로직 (IDOR 소유권 검증)
├─ service/AdminInquiryService.java            관리자 로직 (탭카운트·검색·답변·이벤트)
├─ repository/InquiryRepository.java           본인목록/상태카운트/동적검색
├─ entity/Inquiry.java                         엔티티 + answer() 상태전환
├─ event/InquiryAnsweredEvent.java             답변완료 이벤트
├─ listener/InquiryNotificationListener.java   AFTER_COMMIT @Async 알림
└─ dto/  InquiryCreateRequest, InquiryAnswerRequest,
         InquiryResponse, AdminInquiryResponse,
         AdminInquiryDetailResponse, AdminInquiryListResponse
global/enums/  InquiryCategory, InquiryStatus
global/exception/ErrorCode.java  (INQUIRY_NOT_FOUND / _NOT_AUTHORIZED / _ALREADY_ANSWERED)
src/main/resources/db/migration/V28__add_inquiries.sql
test/.../inquiry/  InquiryServiceTest, AdminInquiryServiceTest, InquiryNotificationListenerTest
```

---

## 2. 엔드포인트 × 역할 인가 표

| 메서드·경로 | 역할 강제 | 강제 수단 | 반환 |
|---|---|---|---|
| `POST /api/guardian/inquiry` | GUARDIAN | 클래스 `@PreAuthorize("hasRole('GUARDIAN')")` + `@EnableMethodSecurity` | 200 ApiResponse |
| `GET /api/guardian/inquiry` | GUARDIAN | 〃 | 200 (본인 목록) |
| `GET /api/guardian/inquiry/{id}` | GUARDIAN + **소유자** | 〃 + 서비스 소유권 검증 | 200 / 404 |
| `GET /api/admin/inquiry` | ADMIN | `SecurityConfig` `/api/admin/**` → `hasRole("ADMIN")` | 200 |
| `GET /api/admin/inquiry/{id}` | ADMIN | 〃 | 200 / 404 |
| `POST /api/admin/inquiry/{id}/answer` | ADMIN | 〃 | 200 / 404 / 409 |

- WARD/ADMIN → 보호자 API 접근 시 **403** (method security).
- 미인증 → **401**. 엔드포인트 모두 **단수형(`inquiry`)** 확인 ✅.

---

## 3. IDOR / 보안 검증 결과 ★

| 항목 | 결과 | 근거 |
|---|---|---|
| **A1. 본인 문의만 조회 (IDOR)** | ✅ **PASS** | `InquiryService.getOwnedInquiry()`가 `inquiry.getUserId().equals(userId)` 불일치 시 `INQUIRY_NOT_AUTHORIZED` throw. 목록은 `findByUserIdOrderByCreatedAtDesc(userId)`로 본인 것만. 타인 문의는 존재조차 숨기려 **404 위장**(`INQUIRY_NOT_AUTHORIZED`=HttpStatus.NOT_FOUND). 전용 테스트(`getMyInquiry_타인것_차단`) 존재. |
| **A2. 역할 인가** | ✅ PASS | 보호자 API 클래스 `@PreAuthorize`, 관리자 API 경로 매칭. `@EnableMethodSecurity` 활성 확인. |
| **A3. 관리자 답변 권한·기록** | ✅ PASS | `/api/admin/**` ADMIN 강제. `answeredBy`에 `@AuthenticationPrincipal adminId` 기록(위조 불가). |
| **A4. 입력 검증** | ✅ PASS | `@NotNull category` / `@NotBlank title @Size(max=100)` / `@NotBlank content @Size(max=2000)` / 답변 `@NotBlank @Size(max=2000)`. 잘못된 category 문자열 → enum 바인딩 실패 400. DTO 길이 ≤ DB 컬럼(title 100, content/answer TEXT) 정합. |
| **A5. PII 노출** | ✅ PASS | 관리자 응답은 `authorId`(6자리)+`authorName`만, 전화/이메일 미포함. 보호자 응답엔 작성자 정보 없음(본인). 리스너·서비스 로그에 본문/원문 노출 없음. |

**IDOR 종합: PASS** — 소유권 검증·404 위장·본인 스코프 조회가 모두 갖춰짐.

---

## 4. 기능 정합성

| 항목 | 결과 | 비고 |
|---|---|---|
| **B1. 상태 전환** | ✅ | `answer()`가 WAITING→ANSWERED + `answeredBy`·`answeredAt(now)` 기록. 재답변은 `isAnswered()` 가드로 **409 거부**(덮어쓰기 아님). 테스트 존재. |
| **B2. 탭 카운트** | ✅ | `count()`/`countByStatus()` — **필터·검색과 무관한 전역 카운트**(탭 배지용, 문서·구현·DTO 주석 일치). |
| **B3. 필터·검색** | ✅ | 동적 JPQL(각 조건 null 무시). keyword는 제목·내용·**작성자명(User.name)** 부분일치(대소문자 무시). `normalize()`가 공백→null 처리(테스트 존재). |
| **B4. 답변 알림** | ✅ | `InquiryAnsweredEvent` → `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")`. `NotificationType.INQUIRY_ANSWERED(mandatory=false)` = **선택 알림, 사용자 설정 따름**. 답변 롤백 시 미발송, 알림 실패가 답변 저장 롤백 안 함. connection/sos 리스너와 동일 패턴. |

---

## 5. 구조 / 계약 / 성능

| 항목 | 결과 | 비고 |
|---|---|---|
| C1 트랜잭션 경계 | ✅ | 쓰기 `@Transactional`, 조회 `@Transactional(readOnly=true)`. |
| C2 이벤트 AFTER_COMMIT | ✅ | 커밋 후 발송, 지연이 HTTP 응답에 미포함. |
| C6 엔드포인트 단수형 | ✅ | 모두 `inquiry`. |
| C7 Swagger | ✅ | Tag `보호자 - 문의`/`관리자 - 문의`, 경로 정렬(SwaggerConfig, 표시순 전용·보안 무관). |
| C8 페이징 | ✅ | `PageResponse<AdminInquiryResponse>`. |
| **C9 N+1** | ✅ | 목록: 검색 1쿼리 + 작성자명 `findAllById` **배치 1쿼리** + 카운트 3쿼리. 행별 조회 없음. |
| **C10 인덱스** | ✅ | `(user_id, created_at DESC)` `(status, created_at DESC)` `(category)` — 조회 패턴과 정합. keyword LIKE는 인덱스 미적용이나 현 규모 허용. |

---

## 6. 발견 이슈

> 🔴 Critical / 🟠 High: **없음**.

### 🟡 Medium

**M-1. 저장형 XSS 잠재 — 보호자 입력이 관리자 화면에 원문 전달 (프론트 렌더링 의존)**
- 보호자가 넣은 `title`/`content`가 관리자 목록·상세 응답에 **원문 그대로** 내려간다. 관리자 웹이 이를 `innerHTML` 등으로 이스케이프 없이 렌더링하면, 악성 보호자가 넣은 `<script>`가 **관리자 세션**에서 실행(권한 경계 교차 = 낮은 권한→높은 권한 공격 벡터).
- 백엔드는 JSON(`application/json`) 반환이라 브라우저 직접 해석은 안 되며, **1차 방어는 프론트 출력 이스케이프**가 정석. 다만 대상이 관리자라 defense-in-depth 가치가 있음.
- **권고**: (1차) FE에서 문의 본문 렌더 시 이스케이프 확인. (선택적 서버측) 저장 전 HTML 새니타이즈 또는 태그 제거. **FE 구현에 따라 실제 위험도 확정 필요** → SilverBridgeFe 점검 항목으로 이관 권장.

### 🟢 Low

- **L-1. POST 생성 응답 201 아님(200)** — `create`/`answer` 모두 200. REST 관례상 생성은 201이나, Swagger 문서·프로젝트 기존 관례와 일관되므로 기능 문제 아님(컨벤션 선택).
- **L-2. 컨트롤러 반환 타입 불일치(외형)** — 보호자는 `ResponseEntity<ApiResponse<..>>`, 관리자는 `ApiResponse<..>` 직접 반환. 둘 다 200+ApiResponse 바디로 동일하나 스타일 상이. 정리 시 한쪽으로 통일 권장.
- **L-3. 문의 작성 rate limit 없음** — 인증된 GUARDIAN이 무제한 작성 가능(DB 적재 남용 여지). 인증 필요·저권한이라 위험 낮음. 남용 관측 시 IP/계정 상한 검토(비번재설정 흐름 패턴 참고).

### ℹ️ 참고 (이슈 아님 — 설계 의도 확인됨)

- **탈퇴자 문의 CASCADE 삭제**: FK `user_id ON DELETE CASCADE` — 보호자 hard-delete 탈퇴 시 본인 문의가 함께 삭제(마이그레이션 주석에 명시된 의도). 결과적으로 관리자 목록의 검색 쿼리 `Inquiry i, User u WHERE u.id=i.userId`(내부조인)에 **고아 문의가 존재하지 않아** 누락/탭카운트 불일치 없음. DTO의 "탈퇴 시 authorName null" 방어 코드는 사실상 미발동이나 무해.
  - 단, 이는 **탈퇴 시 고객지원 이력도 소실**됨을 의미 — 감사/이력 보존이 필요하면 별도 정책 논의 필요(현재는 의도된 삭제).
- **관리자 엔드포인트 스타일**: 기존 admin 컨트롤러(공지)는 `/select` `/create` 등 동사-경로형인데 문의는 RESTful(`/{id}/answer`). 문의 쪽이 더 정석 — 향후 신규는 문의 스타일로 통일 권장.

---

## 7. 종합 판정

**✅ PASS** — 핵심 보안(IDOR·역할 인가·답변자 기록)·기능 정합성(상태전환·탭카운트·선택 알림 AFTER_COMMIT)·구조(트랜잭션·N+1·인덱스)·테스트가 모두 견고. 빌드·문의 테스트 통과.

- Critical/High **없음**.
- Medium 1건(저장형 XSS)은 **프론트 렌더링 의존** — SilverBridgeFe 점검으로 확정 권장.
- Low 3건은 컨벤션/하드닝 수준으로 배포 차단 사유 아님.

> 조치 없이 현행 배포 가능. M-1은 FE 이스케이프 확인을 후속 과제로 트래킹.
