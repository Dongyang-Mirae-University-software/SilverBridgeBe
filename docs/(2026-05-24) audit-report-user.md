# user 도메인 종합 점검 보고서 + 프로필 이미지 삭제 API 통합 점검

- **점검 일자**: 2026-05-24
- **점검자**: Claude Code (스킬 기반 다단계 점검)
- **대상 브랜치**: `dev` (점검) → 수정은 `fix/user-audit-2026-05-24`
- **점검 방식**: PHASE -1(환경) → 0(범위/컨벤션) → A(보안·PII·동시성) → B(구조·품질) → C(API 계약) → D(데이터) → E(로깅) → F(테스트) → G(문서)
- **사용 스킬**: security-audit, architecture-review, spring-boot-patterns, jpa-patterns, api-contract-review, test-quality (+ concurrency/clean/solid/performance/logging는 분석 적용)

> user 도메인은 **스킬 기반 종합 점검 최초**. 신규 `프로필 이미지 삭제 API`(PR #174, 2026-05-24)를 함께 점검.

---

## 1. 점검 범위

### 1차 (직접 대상) — `domain/user/**`
controller/UserController · service/UserService · entity/User · dto(PasswordChangeRequest·UserUpdateRequest·WithdrawRequest·UserProfileResponse) · event(PasswordChangedEvent·UserWithdrawnEvent) · port/PhoneVerificationPort · repository/UserRepository · (test)UserServiceTest

### 2차 (의존)
auth/listener/UserAccountEventListener · global/client/FileServerClient · global/validation/BirthDateValidator·ValidBirthDate · global/jwt/JwtAuthenticationFilter · global/security/SecurityConfig

### 3차 (스키마·정책)
db/migration V1·V2·V7·V10·V11·V15·V18 (users) · global/exception/ErrorCode · global/util/MaskingUtil · application.yaml

### 최근 변경분 (PR #174, 커밋 42b6a8c)
UserController(+30) · UserService(+30, `deleteProfileImage`) · UserServiceTest(+60) · 문서 3종

---

## 2. PHASE -1 환경 확인 결과

| 항목 | 결과 |
|------|------|
| 날짜 | 2026-05-24 (일) |
| Git | `dev` clean, origin/dev 동기화 |
| 빌드 | ✅ `./gradlew build -x test --no-daemon` exit 0 |
| 신규 커밋 | ✅ `42b6a8c feat(user): 프로필 이미지 삭제 API 구현` dev 머지(`29f5e29`) |
| 이전 산출물 | connection(05-21), auth password-reset(05-23). **user 풀 점검 없음(최초)** |

---

## 3. URL 패턴 일관성 — ✅ 불일치 없음

컨트롤러·`프로젝트_설명.txt`(3-5)·Swagger 3자 일치. 프롬프트 전제(`/api/users/me/profile-image`)는 **이미 정정 완료**된 상태(`프로젝트_설명.txt` L278-279, "과거 `/api/users/*`는 오기"). **정정 작업 불필요.**

| 엔드포인트 | 매핑 | 메서드 의미 |
|---|---|---|
| 내 정보 조회 | `GET /api/user/me` | 안전·멱등 ✓ |
| 내 정보 수정 | `PUT /api/user/me` | 전체 교체(부분 수정 불가) ✓ |
| 비밀번호 변경 | `PUT /api/user/me/password` | (관찰: 비멱등 — C-USER-2) |
| 프로필 이미지 변경 | `PATCH /api/user/me/image` | 부분 변경 ✓ |
| 프로필 이미지 삭제 | `DELETE /api/user/me/image` | 멱등(200+message) ✓ |
| 회원 탈퇴 | `DELETE /api/user/me` | INACTIVE soft-delete |

---

## 4. 발견 이슈 (심각도별)

### 🟠 High

#### A-USER-1 · 회원 탈퇴 후 기존 access token이 무효화되지 않음
- `JwtAuthenticationFilter`는 DB 조회 없이 토큰 클레임만으로 인증(성능 최적화). 매 요청 `status(INACTIVE)`를 재검증하지 않음.
- 탈퇴 리스너 `handleWithdrawn`은 refresh token 삭제 + `access_logs` 기록만 하고, **access token 무효화 키를 설정하지 않음.**
- 결과: 탈퇴(`INACTIVE`) 후에도 이미 발급된 access token이 **최대 30분(TTL)** 동안 모든 인증 API에서 유효.
- 비밀번호 변경은 동일 위험을 `PASSWORD_INVALIDATE`로 차단(필터 L62)하나 **탈퇴는 비대칭.**
- 노출 한계: refresh 삭제 + 재로그인 INACTIVE 차단으로 창은 ≤30분 한정.
- **조치(수정)**: `handleWithdrawn`에서도 `invalidatePreviousAccessTokens(userId)` 호출 → 탈퇴 이전 발급 토큰 즉시 401.

#### F-USER-1 · `changePassword` 성공 경로 테스트 부재
- 실패 경로 3종만 존재. 비밀번호 인코딩 교체 + `PasswordChangedEvent` 발행(보안 핵심) 검증 없음.
- **조치(수정)**: 성공 경로 테스트 추가(`updatePassword`/이벤트 발행 검증).

#### F-USER-2 · `updateProfile` 테스트 전무
- 분기 최다(전화번호 변경 → `consumeVerification` → `existsByPhone`)인데 0건.
- **조치(수정)**: 미변경/변경 성공/`PHONE_ALREADY_EXISTS`/`USER_NOT_FOUND` 테스트 추가.

### 🟡 Medium

#### A-USER-2 · 프로필 이미지 형식 검증이 클라이언트 Content-Type에만 의존
- `file.getContentType()`(스푸핑 가능)만 화이트리스트 대조. Magic Number 미검증.
- **조치(수정)**: 파일 시그니처(JPEG/PNG/GIF/WebP) 검증 추가(Content-Type 검증과 병행, 방어심층).

#### D-USER-2 · 커밋 전 파일 삭제 → 롤백 시 이미지 깨짐 (정합성)
- `updateProfileImage`/`deleteProfileImage`가 트랜잭션 커밋 **이전에** 파일 서버 `delete(oldUrl)` 호출. 커밋 실패 시 DB는 옛 URL을 가리키는데 실제 파일은 삭제 → 깨진 이미지.
- **조치(수정)**: 파일 삭제를 `TransactionSynchronization.afterCommit`으로 이전(트랜잭션 밖/단위 테스트에서는 즉시 위임 → 기존 테스트 호환).

#### D-USER-1 · 외부 파일 I/O가 트랜잭션 내부 (성능) — **부분 수정**
- 업로드/삭제 HTTP 호출이 `@Transactional` 내부 → DB 커넥션 점유.
- **조치**: 삭제는 D-USER-2 수정으로 커밋 후 실행되어 트랜잭션 작업에서 분리됨. **업로드의 트랜잭션 밖 이전은 트랜잭션 경계 재설계(self-injection/TransactionTemplate)가 필요해 배포 플로우 회귀 위험 → 통합테스트 동반 별도 follow-up 권장.**

#### D-USER-3 · 탈퇴 시 connections/fcm_tokens 미정리 (교차도메인) — **follow-up**
- soft delete(INACTIVE)는 연결/FCM 토큰을 정리하지 않음. INACTIVE 사용자가 푸시를 계속 받거나 연결에 잔존 가능.
- **조치**: notification/connection 측 INACTIVE 필터링 여부 확인 필요. **user 도메인 단독 수정 대상 아님 → 교차도메인 follow-up 이슈로 분리.**

#### E-USER-1 · 비밀번호 변경 감사추적 부재
- `changePassword`는 애플리케이션 로그·`access_logs` 모두 없음(redis 무효화 키만, 자동만료).
- **조치(수정)**: INFO 감사 로그 추가(userId만). `access_logs` PASSWORD_CHANGE 액션 추가는 cross-cutting → 선택 follow-up.

#### B-USER-1 · 비밀번호 정규식 3곳 복제 (교차도메인) — **follow-up**
- `PasswordChangeRequest`(user) = `RegisterRequest`(auth) = `PasswordResetConfirmRequest`(auth). 값 동일·정책 일관.
- **조치**: 공유 `@ValidPassword`(global/validation) 추출이 정답이나, **auth DTO 2건 수정이 수반되어 본 점검의 "타 도메인 작업 금지" 범위를 벗어남 → user+auth 통합 follow-up으로 분리.** (user 단독 부분 적용은 오히려 중복처 증가라 비권장.)

#### B-USER-2 · `findById().orElseThrow()` 6회 반복
- **조치(수정)**: private `getUserOrThrow(userId)` 헬퍼 추출.

#### F-USER-3 / F-USER-4 · `updateProfileImage`/`withdraw`(LOCAL 성공) 테스트 갭
- **조치(수정)**: 크기/타입/시그니처/성공, LOCAL 탈퇴 성공 테스트 추가.

### 🟢 Low

| ID | 항목 | 조치 |
|----|------|------|
| A-USER-3 | 전화번호 변경 TOCTOU(`uq_users_phone`이 최종 방어, race 시 500) | 백로그(409 매핑 검토) |
| A-USER-4 | `FileServerClient.delete` WARN 로그에 fileUrl 노출 | 백로그 |
| C-USER-1 | `SOCIAL_USER_NO_PASSWORD` 메시지 "재설정"인데 변경에도 사용 | 백로그(공유코드 중립화) |
| C-USER-2 | current-password 불일치 401(400/422가 의미상 더 맞음) | 변경 안 함(auth 전역 일관) |
| E-USER-2 | `updateProfileImage` 로그 부재(삭제는 3개) | 수정(INFO 추가) |
| B-USER-3 | `KAKAO_WITHDRAW_CONFIRMATION` 상수 위치 | 수정(상단 이동) |
| F-USER-5 | `getMyProfile` 테스트 없음 | 수정(추가) |

---

## 5. ✅ 확인된 안전 사항

- **IDOR 없음**: 전 엔드포인트 `@AuthenticationPrincipal`(JWT 파생 userId)만 사용. path/body의 타 사용자 id 미수용.
- **비밀번호 변경 견고**: 현재 비번 검증 → 새 비번 정규식 → 동일 비번 차단 → 소셜 차단 → 이벤트(AFTER_COMMIT)로 refresh 삭제 + 전 기기 무효화.
- **탈퇴 본인확인**: LOCAL=비밀번호, KAKAO=`"탈퇴"` confirmation(H-6).
- **PII**: 응답은 본인 데이터만(me), password/token 미포함. 로그엔 userId만.
- **N+1 없음**: `User`에 연관관계 매핑 없음 → `getMyProfile` 단일 쿼리, `UserProfileResponse`는 스칼라만 → LazyInit 위험 없음.
- **업로드 제한**: multipart 5MB + 서비스 5MB 이중, `MaxUploadSizeExceededException → 400`.
- **SQL**: 전부 파라미터 바인딩, 관리자 검색 LIKE 메타문자 `ESCAPE`.
- **의존 방향**: auth → user 단방향(PhoneVerificationPort/이벤트). user는 auth 미참조.
- **phone 유니크**: `uq_users_phone` 부분 유니크 인덱스(V2)로 DB 보장.
- **신규 `deleteProfileImage`**: 멱등·DB 우선·파일서버 실패 비전파 설계 정확, 테스트 4종 양호.

---

## 6. 동시성

| 시나리오 | 평가 |
|---|---|
| 동시 탈퇴 | `deactivate()` 멱등(INACTIVE). `WITHDRAW` access_log 중복 행 가능(Low) |
| 동시 이미지 업로드 | `@Version` 없음 → last-writer-wins, 고아 파일 가능(Low) |
| 동시 이미지 삭제 | null 멱등 + 파일서버 2차 404 WARN → 안전 |
| 동시 비번 변경 | `PASSWORD_INVALIDATE` 설정 직후 eventual, 무시 가능 |

---

## 7. 프론트 호환성 영향

- **응답 필드 삭제·이름 변경 없음.** 모든 수정은 서버 내부 동작.
- **A-USER-1**: 탈퇴 후 기존 access token이 즉시 401 → 프론트는 이미 "탈퇴=로그아웃" 처리하므로 영향 없음(오히려 기대 부합).
- **A-USER-2**: 시그니처 위반(스푸핑) 파일만 400. 정상 이미지 영향 없음.
- **D-USER-2**: 정상 케이스 동작 불변(삭제 시점만 커밋 후로 이동).
- **E-USER-1/2**: 로그만 추가.

---

## 8. 미해결 TODO (follow-up)

1. **D-USER-1(잔여)** — `updateProfileImage` 업로드의 트랜잭션 밖 이전(통합테스트 동반).
2. **D-USER-3** — 탈퇴 시 connections/fcm_tokens 생명주기 정책 결정(notification/connection 도메인 협의).
3. **B-USER-1** — 공유 `@ValidPassword` 추출(user+auth 통합 PR).
4. **Low 백로그** — A-USER-3(409 매핑), A-USER-4(로그 마스킹), C-USER-1(메시지 중립화).
5. (선택) `access_logs` PASSWORD_CHANGE 액션 추가.

---

## 9. 다음 점검 권장 시점

- D-USER-1/D-USER-3/B-USER-1 follow-up 머지 후 재확인.
- connection/notification 도메인과의 교차 점검(탈퇴 생명주기) 시 user 재점검 동반.
- 프로필/계정 기능 추가(예: 이메일 변경) 시 본 보고서 기준 회귀 점검.
