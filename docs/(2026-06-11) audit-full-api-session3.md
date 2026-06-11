# 전체 API 종합 점검 — 세션 3 (announcement + admin + global 공통 인프라)

- **점검 일자**: 2026-06-11 · **세션**: 3/3 · **대상**: announcement 2 + admin 11 = 13 엔드포인트 + global 12패키지(jwt·security·websocket·exception·aop·config·util)
- **점검 성격**: announcement·admin **정밀(점검 이력 전무)** / global 풀 점검(과거 스팟들의 단편 확인을 통합 실증)
- **환경**: dev 3e20d97 (H-S2-1 수정 머지 직후), 전체 빌드 통과본

---

## 1. 인가 매트릭스 ★

| # | 엔드포인트 | 접근 | 보호 방식 | 판정 |
|---|---|---|---|---|
| 1–2 | GET `/api/commonness/announcement/{select, select/detail/{id}}` | 인증(전 역할) | `anyRequest().authenticated()` | ✅ |
| 3–7 | `/api/admin/announcement/{select, select/detail/{id}, create, update/{id}, delete/{id}}` | ADMIN | SecurityConfig `hasRole("ADMIN")` 경로 기반 | ✅ |
| 8–13 | `/api/admin/announcement/draft/{select, select/detail/{id}, create, update/{id}, delete/{id}, publish/{id}}` | ADMIN | 동상 | ⚠️ **전 기능 동작 불능 (C-S3-1)** |

- `/api/admin/**` 경로 기반 보호가 두 admin 컨트롤러 매핑을 모두 커버 ✓. ADMIN 역할은 가입 경로에서 차단(INVALID_ROLE)되어 위조 불가 ✓.
- 공지 응답 PII: `authorName`만 노출(작성자 email/phone/id 없음, 탈퇴 시 null) ✓.

## 2. 발견 이슈

### 🔴 C-S3-1 · `chk_admin_audit_action` CHECK에 DRAFT 액션 4종 누락 — **공지 임시저장 기능 전체 500**

- `admin_audit_logs`의 CHECK(V1)는 `ANNOUNCEMENT_CREATE/UPDATE/PUBLISH/DELETE`만 허용하는데, 코드(`AdminAuditAction`)는 draft 작업에 `ANNOUNCEMENT_DRAFT_CREATE/UPDATE/DELETE/PUBLISH`를 기록한다. **V1 이후 어떤 마이그레이션도 이 CHECK를 갱신하지 않았다** (전 `V*.sql` 확인).
- `AdminAuditLogService.log`는 REQUIRED(호출 트랜잭션 합류)이므로: draft create/update/delete/publish → 감사 insert가 **CHECK 위반(23514)** → `DataIntegrityViolationException` → 핸들러가 non-unique로 **500** → **본 작업까지 전체 롤백**. 즉 임시저장 4개 엔드포인트는 호출 시 항상 실패한다.
- enum과 CHECK의 추가 비정합: CHECK의 `ANNOUNCEMENT_PUBLISH`는 enum에 없음(draft 게시는 `ANNOUNCEMENT_DRAFT_PUBLISH`).
- **미발견 경위**: announcement/admin 테스트 0건 + 단위 테스트로는 DB CHECK를 못 잡음 + admin 화면 미사용 추정.
- **권장 수정**: 새 마이그레이션(V27)으로 CHECK를 enum 전수와 일치하게 재정의(기존 V1 수정 금지 원칙 준수). 장기적으로 enum↔CHECK 동기화 검증 테스트(또는 CHECK 제거 후 애플리케이션 enum 검증 일원화) 검토.

### 🟡 M-S3-1 · WebSocket 핸드셰이크 토큰 검증이 HTTP 필터 대비 약함

- `JwtHandshakeInterceptor`는 `validateToken`+`getUserId`만 수행. HTTP의 `JwtAuthenticationFilter`가 하는 3가지를 안 한다:
  ① **typ 검증 없음** → **refresh token으로 WS 연결 가능**(A-H1 우회 — refresh 탈취 시 rotation 재사용 감지에 안 걸리고 7일간 조용히 WS 인증 가능)
  ② 로그아웃 블랙리스트 미확인 ③ `PASSWORD_INVALIDATE` 미확인 → 로그아웃·비번변경·탈퇴 직후에도 옛 access token으로 **새 WS 연결** 가능(토큰 만료까지 ≤30분).
- 구독은 본인 토픽으로 제한(인터셉터)되므로 타인 데이터 접근은 불가 — 영향은 "무효화된 본인 세션의 잔존"에 한정.
- **권장**: 핸드셰이크에 `isAccessToken` + 블랙리스트 + invalidate 검사 추가(필터 로직 재사용).

### 🟢 Low

| ID | 내용 | 권장 |
|---|---|---|
| L-S3-2 | draft DTO는 title/content null 허용인데 DB는 NOT NULL → null 전송 시 23502 → 500 (빈 문자열은 통과 — FE가 "" 보내는 동안만 무사) | null→"" 정규화 또는 `@NotNull` |
| L-S3-3 | WS `setAllowedOriginPatterns("*")` vs HTTP CORS 설정 기반(`app.cors.allowed-origins`) 비대칭 — 토큰 필수라 실위험 낮음 | 동일 설정 재사용 |
| L-S3-4 | JWT iat 초 단위 절사 vs 무효화 키 ms 비교 — 무효화와 같은 초 안에 재발급된 새 토큰 오차단(≤1초 창) | iat 비교 시 1초 마진 또는 수용(문서화) |
| L-S3-5 | `UserRepository` 관리자 검색 쿼리 3종(search/findByRoleIn/findByRoleNot) 호출처 없음 — 회원관리 화면 미구현 dead query | 기능 구현 전까지 제거 또는 유지 결정 |
| L-S3-6 | 공지 목록 `findAll` 무페이징 + `increaseViewCount` 동시 증가 lost update(통계라 무해) | 규모상 수용, 페이징은 백로그 |
| L-S3-7 | `AnnouncementController`만 인증 필요인데 Swagger에 명시 없음 + `/api/commonness/` 네이밍(어색한 영어) | 문서 보완 / 네이밍은 FE breaking이라 유지 |

## 3. ✅ 확인된 안전 사항 (global 인프라 실증)

- **JwtAuthenticationFilter**: 로그아웃 블랙리스트(SHA-256 해시 키) → typ 검증(A-H1) → PASSWORD_INVALIDATE iat 비교 순서 정확, 필터 내 JSON 401 포맷 일관.
- **STOMP 구독 인가**: `/topic/{userId}/**` 전체에 세션 userId 일치 강제(이벤트명 화이트리스트 불필요 — sos-triggered·connection-* 자동 보호) **실증**. IDOR 시도 WARN 로깅.
- **GlobalExceptionHandler**: 23505만 409 "중복", 그 외 무결성 위반은 원인 ERROR + 일반 500(PR #185 정책 유지). 낙관락 → 409 메시지 친화적. PII 비로깅(중복 값 미출력). 미지원 메서드/미디어/경로 전부 정규화.
  - ※ 이 덕분에 **세션 1 보고의 "동시 중복 탈퇴 → 500 가능" 추정은 409로 정정**(`StaleStateException`→`ObjectOptimisticLockingFailureException` 매핑).
- **RateLimitService**: 단일·이중 윈도우 모두 Lua 원자적 INCR+TTL(M-4).
- **AsyncConfig**: `notificationExecutor` CallerRunsPolicy — SOS 리스너 주석의 "큐 포화 시 유실 없음" 전제 **실증**. (core 2/max 10/queue 100)
- **ApiLoggingAspect**: method/URI/컨트롤러/IP만 — 바디·파라미터 비로깅(PII 안전).
- **시크릿 fail-fast**: RequiredPropertiesValidator(11키) + SecurityConfigValidator(길이·placeholder) 동작 유지. JWT SecretKey 1회 캐싱.
- **admin 감사**: `admin_audit_logs`는 FK 없음(문자열 adminId) → 관리자 탈퇴 차단 없음 + 감사 영구 보존. 감사가 본 작업과 같은 트랜잭션(행위 실패 시 감사도 미기록 — 정합적 선택, AccessLogService의 REQUIRES_NEW와 다른 의도 구분 확인).

## 4. PHASE B/C — 계약·구조

- admin 응답도 `ApiResponse` 일관. URL 동사형(`/select`, `/create`, `/update/{id}`)은 connection과 같은 프로젝트 컨벤션(자원형인 auth/user와 분기) — FE breaking이라 변경 비권장, 컨벤션 문서화만 권장.
- `@Transactional` 경계·읽기 전용 분리 정확, 작성자 배치 조회(findAllById)로 N+1 없음, draft publish의 "삭제+생성" 단일 트랜잭션 원자성 ✓.

## 5. PHASE D — 테스트 갭

| 갭 | 내용 |
|---|---|
| 🟡 announcement/admin 테스트 **0건** | C-S3-1이 회귀망에 없던 직접 원인. 서비스 단위 테스트 + (가능하면) enum↔CHECK 정합 검증 추가 권장 |
| 🟢 JwtHandshakeInterceptor 테스트 부재 | M-S3-1 수정 시 동반 |

## 6. 다음 단계

- C-S3-1: V27 마이그레이션 + (선택) enum 정합 테스트 — **즉시 수정 권장** (admin 기능 복구).
- M-S3-1·Low들: 최종 통합 리포트의 우선순위 일괄 처리 대상.
