# 진행 기록

프로젝트 작업 중 누적되는 점검·리뷰 결과를 영역별로 정리한다.

---

## 2026-05-16 — auth / user 점검 결함 조치 완료

audit-report-auth.md 발견 28건에 대한 조치를 결함 단위 PR로 분리·머지 완료. 상세 상태는 audit-report-auth.md "적용 현황" 절 참조.

### Phase A (Critical 1 + High 7) — PR #124~#132

- 빨강: 비밀번호 변경·재설정 후 access token 즉시 무효화 (Redis sliding invalidation)
- High 7건: 로그인 enumeration 통합, 잠금 키 user.id 기반, refresh 재사용 감지, INACTIVE refresh 정리, SMS nonce 결합, 카카오 탈퇴 confirmation, dead code 정리
- 프론트 후속 작업 3건(H-1 로그인 응답 / H-5 SMS nonce / H-6 카카오 탈퇴)은 각 PR 본문에 마이그레이션 가이드 명시

### Medium/Low — PR #133~#137 (성격별 5묶음)

- #133 문서/Swagger 정합 (M-2)
- #134 보안 강화 (M-6 토큰 해시, M-7 헤더 검증, M-8 rate limit 확대, M-11 BCrypt 12, L-3 permitAll 분리 + 401 entry point)
- #135 Redis 카운터 원자화 (M-4, L-2 — RedisCounter Lua)
- #136 코드 품질·네이밍 (M-9 VerificationKeyConfig 리네임, M-12 AuthLoginProperties 외부화, M-13, L-1/L-4/L-5/L-7)
- #137 비밀번호 재설정 트랜잭션·로깅 (M-3 IP/UA 기록, M-5 readOnly 트랜잭션 분리)

### 제외/보류 (근거는 audit-report-auth.md 기록)

- 제외: L-6(ID 재시도가 단일 호출보다 안전), M-10(2곳 중복 추출은 발송 추상화로 더 복잡)
- 보류: M-1(프론트 state 검증 선확인 필요 — 협의 사항 문서화), KAKAO/SOLAPI 키 회전(운영 결정)

### 호환성

- Medium/Low 전 항목 응답 포맷 변경 없음
- application.yaml에 auth.login.* 추가 (기본값이 기존 동작과 동일)

---

## 2026-05-15 — auth / user 도메인 점검 (보안 정밀)

스킬 기반 7 PHASE 점검(security-audit / concurrency-review / architecture-review / spring-boot-patterns / clean-code / api-contract-review / jpa-patterns / performance-smell-detection / logging-patterns / test-quality) 완료. 보안 핵심 도메인 정밀 모드.

### 점검 범위 (총 49개 파일)
- domain/auth (43) + domain/user (10) + global/jwt (3) + global/security (4) + global/util (3, MaskingUtil/RedisKeys/VerificationCodeValidator+UserIdGenerator) + global/config (2, SecurityConfigValidator/RequiredPropertiesValidator) + global/exception (3, ErrorCode/CustomException/GlobalExceptionHandler) + global/aop/ApiLoggingAspect + db/migration(users/refresh_tokens/access_logs 관련 11개) + application.yaml + .env.dev + build.gradle + .gitignore

### 발견 요약
- 🔴 Critical 1건 — 비밀번호 변경/재설정 후 access token 30분 유효 결함
- 🟠 High 7건 — 계정 enumeration, 이메일 기반 로그인 잠금 DoS, refresh rotation 재사용 탐지 부재, INACTIVE 시 refresh 삭제 누락, SMS_VERIFIED 키 재사용, 카카오 탈퇴 본인 확인 약함, AccessLogRepository 타입 버그(dead code)
- 🟡 Medium 13건 / 🟢 Low 7건

### 산출물
- `docs/audit-report-auth.md` — 전체 분석·수정 제안·프론트 마이그레이션·커밋 메시지 초안 일괄 정리
- 본 progress.md 업데이트

### 다음 단계 (미적용 — 사용자 승인 후 PR 분리 진행)
- Critical 1건 우선 적용. PR 단위 분리 권장. 자동 git 작업 금지(CLAUDE.md §2).
- Phase F의 테스트 갭(KakaoAuthService, PasswordResetService, refresh rotation 동시성)은 수정 PR과 함께 진행.

---

## 2026-05-15 — 관리자 대시보드 / 회원관리 API 사후 점검

스킬 기반 6 PHASE 점검(architecture / spring-boot / clean-code / jpa / security / test / logging) 결과 요약. 점검 대상은 PR #121(대시보드)·#122(회원관리 검색) 및 기존 회원관리 엔드포인트.

### PHASE 1. 구조 점검

적용
- 어드민 전용 사용자 집계 쿼리(countUserStats / countByRole)를 `admin/repository/AdminUserStatsRepository` 로 이관. UserRepository 슬림화.
- AdminDashboardService.getSummary() 메서드 레벨 @Transactional 제거. 캐시 hit 경로에서 빈 트랜잭션 미개시.
- AdminDashboardController / AdminUserController 클래스 레벨 @PreAuthorize("hasRole('ADMIN')") 추가. SecurityConfig URL 차단과 이중 방어.

### PHASE 2. 코드 품질

적용
- AdminDashboardService 캐시 쓰기 실패 시 500 응답 대신 log.warn 후 fresh 반환 (CustomException/ErrorCode 의존 제거).
- 캐시 역직렬화 실패에 log.warn 추가.
- AdminDashboardService.getRecentUsers 의 PageRequest 에서 불필요한 Sort 인자 제거 (메서드명 OrderByCreatedAtDesc 가 정렬을 결정).
- 정적 팩토리 메서드 네이밍을 from 으로 통일 (AdminRecentUserResponse.of → from).

보류
- DTO 의 enum vs String 타입 일관성 — 호환성 영향 검토 후 별도 PR.
- `/user/status-change`, `/user/delete` 등 URL 컨벤션 정비 — 프론트 호환성 영향, 별도 마이그레이션.

### PHASE 3. 데이터 계층

적용
- Flyway V15: users 테이블에 (role, status, created_at DESC) 복합 인덱스 추가. 회원관리 검색/목록/최근 가입자 쿼리 전부 커버.
- 회원관리 검색에서 이메일 LIKE 조건과 LOWER 함수 호출 제거 — name/phone 부분일치만 유지. 검색 정책: 관리자는 이름·전화번호로 회원 식별.
- 결과적으로 auth 도메인의 이메일 정규화 / DB 데이터 마이그레이션 작업은 회피.

보류
- `/user/select` 의 페이지네이션은 PHASE 4 S2 로 이월하여 적용 (아래 참조).

### PHASE 4. 보안·동시성

적용
- LIKE 메타문자(%, _, \\) 이스케이프. 서비스 레이어에서 keyword 정규화 후 JPQL 의 `ESCAPE '\\'` 와 짝.
- `/api/admin/user/select` 페이지네이션 적용. 응답이 List 에서 페이지 객체(content/page/size/totalElements/totalPages)로 변경 — 프론트 호환성 영향 있음, 회원관리 화면 작업 중이라 동시 적용.

보류
- ApiLoggingAspect 가 파라미터를 안 찍어 keyword 마스킹 무용. 향후 파라미터 로깅 추가 시 마스킹 필요 (PHASE 6 L3 와 동일 트래킹).
- 캐시 stampede 방어 — 트래픽 증가 시 재검토.

### PHASE 5. 테스트

적용
- AdminDashboardServiceTest 7건 (캐시 hit/miss/손상/쓰기실패, baseline 0 → 증감률 null, 최근 가입자, 처리 대기 합산).
- AdminUserServiceTest 13건 (목록 페이징, 검색 빈/이스케이프/정상, 카운트 매핑, 상태/역할 변경 ADMIN 차단·정상·연결 cancel, 강제 탈퇴, 조회 not found).
- 총 20건 모두 통과 (JUnit5 + Mockito + AssertJ + 한글 @DisplayName, 기존 AuthServiceTest 컨벤션 준수).

### PHASE 6. 로깅·문서

적용
- L1 AdminAuditLogService.log() 가 DB 저장과 함께 SLF4J log.info 동시 출력 — 관리자 status/role/forceDelete 가 로그 파일에도 자동 기록.
- L2 ApiLoggingAspect 가 SecurityContext 의 userId 를 MDC 에 put/remove. application.yaml 의 로그 패턴에 `%X{userId:-anonymous}` 포함되어 모든 로그에 자동 표시.
- L4 Spring Boot 4 structured logging 활성화 토글 — `LOGGING_STRUCTURED_FORMAT` 환경변수에 `ecs`/`gelf`/`logstash` 입력 시 JSON 출력. 기본은 평문. MDC 도 자동 JSON 필드로 포함.

보류 / TODO
- L3 raw keyword 마스킹 — 현재 ApiLoggingAspect 가 파라미터 미로깅이라 노출 없음. 향후 파라미터 로깅 도입 시 keyword / name / phone 마스킹 필수.
- 운영 환경 structured logging 활성화는 로그 수집기·파서 호환성 확인 후 적용.

### 변경 파일 (총 14건)

신규
- `src/main/java/.../domain/admin/repository/AdminUserStatsRepository.java`
- `src/main/java/.../domain/admin/dto/AdminUserListPageResponse.java`
- `src/main/resources/db/migration/V15__add_users_role_status_created_at_index.sql`
- `src/test/java/.../domain/admin/service/AdminDashboardServiceTest.java`
- `src/test/java/.../domain/admin/service/AdminUserServiceTest.java`
- `docs/progress.md` (본 문서)

수정
- `src/main/java/.../domain/admin/controller/AdminDashboardController.java`
- `src/main/java/.../domain/admin/controller/AdminUserController.java`
- `src/main/java/.../domain/admin/service/AdminDashboardService.java`
- `src/main/java/.../domain/admin/service/AdminUserService.java`
- `src/main/java/.../domain/admin/service/AdminAuditLogService.java`
- `src/main/java/.../domain/admin/dto/AdminRecentUserResponse.java`
- `src/main/java/.../domain/user/repository/UserRepository.java`
- `src/main/java/.../global/aop/ApiLoggingAspect.java`
- `src/main/resources/application.yaml`
