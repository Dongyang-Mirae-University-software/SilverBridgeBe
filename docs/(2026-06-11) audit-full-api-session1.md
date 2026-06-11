# 전체 API 종합 점검 — 세션 1 (auth + user)

- **점검 일자**: 2026-06-11
- **세션**: 1/3 — 대상 도메인 `domain/auth`(18 엔드포인트) + `domain/user`(6 엔드포인트), 총 24개
- **점검 성격**: 두 도메인 모두 풀 점검 완료 이력 있음 → **기준선 이후 변경분 + 회귀 확인 위주(경량)**
- **기준선**: `(2026-05-23) audit-report-auth-password-reset.md` / `(2026-05-24) audit-report-user.md` + 스팟 체크 3건(05-23·05-25·06-06)
- **환경**: dev 브랜치 a956dba, `./gradlew build -x test` 통과(exit 0)
- **기준선 이후 변경 커밋**: 14건 (탈퇴 hard delete, 카카오 client secret/nonce 보존/FK 수정/@Async 분리, 인증코드 소비 순서, 프로필 이미지 4건, @ValidPassword 통합 등)

---

## 1. 인가 매트릭스 ★

auth/user에는 WARD/GUARDIAN 역할 분기가 없다(전부 공개 또는 본인-스코프). 핵심 검증 축은
① `/api/auth/**` permitAll 와일드카드 아래 보호 누락, ② `/api/user/**` 본인-스코프(IDOR), ③ rate limit 커버리지.

| # | 엔드포인트 | 접근 | 본인확인 | IP RateLimit | 판정 |
|---|---|---|---|---|---|
| 1 | POST `/api/auth/signup/email/check` | 공개 | — | ✅ `email-check` | ✅ |
| 2 | POST `/api/auth/signup` | 공개 | SMS nonce | ❌ (L-S1-2) | ⚠️ Low |
| 3 | POST `/api/auth/signin` | 공개 | — | ✅ `signin` + 계정잠금 | ✅ |
| 4 | POST `/api/auth/refresh` | 공개 | refresh 토큰 | ✅ `token-refresh` | ✅ |
| 5 | POST `/api/auth/logout` | **인증** (permitAll에서 명시 분리, L-3) | JWT | — | ✅ |
| 6 | POST `/api/auth/find-email` | 공개 | 이름+전화 | ✅ `find-email` | ✅ |
| 7 | POST `/api/auth/signin/kakao` | 공개 | 카카오 인가코드+secret | ✅ `kakao-login` | ✅ |
| 8 | POST `/api/auth/signup/kakao` | 공개 | pending 세션+SMS nonce | ❌ (L-S1-2) | ⚠️ Low |
| 9–11 | POST `/api/auth/signup/sms/{send,verify,resend}` | 공개 | — | ✅ `signup-sms` + per-phone 10/h | ✅ |
| 12–14 | POST `/api/auth/find-password/email/{send,verify,resend}` | 공개 | 가입 이메일 | ✅ 이중윈도우 10/분·30/h + per-email 10/h | ✅ |
| 15–17 | POST `/api/auth/find-password/sms/{send,verify,resend}` | 공개 | 이름+전화 | ✅ 이중윈도우 + per-phone 10/h | ✅ |
| 18 | POST `/api/auth/password/reset` | 공개 | 6자리 코드 선검증(A-M1) | ✅ `pw-reset-confirm` | ✅ |
| 19 | GET `/api/user/me` | 인증 | `@AuthenticationPrincipal`만 | — | ✅ |
| 20 | PUT `/api/user/me` | 인증 | 동상 + 전화변경 시 SMS nonce | — | ✅ |
| 21 | PATCH `/api/user/me/image` | 인증 | 동상 | — | ✅ |
| 22 | DELETE `/api/user/me/image` | 인증 | 동상 | — | ✅ |
| 23 | PUT `/api/user/me/password` | 인증 | 동상 + 현재 비밀번호 | — | ✅ |
| 24 | DELETE `/api/user/me` | 인증 | 동상 + 비밀번호/"탈퇴" 문구(H-6) | — | ✅ |

- **IDOR 없음 재확인**: user 6개 전부 `@AuthenticationPrincipal`(JWT 파생 userId)만 사용, path/body로 타 사용자 id 미수용 — 기준선과 동일.
- `@PreAuthorize` 미사용은 설계대로(SecurityConfig 중앙 관리 + admin만 `hasRole`).
- `anyRequest().authenticated()` 디폴트-거부 구조 유지 ✅.

## 2. PHASE A — 보안·인가

### 회귀 확인 (기준선 수정 사항 14건 → 전부 유지 ✅, 회귀 없음)

| 기준선 이슈 | 현재 상태 |
|---|---|
| A-USER-1 탈퇴 후 access token 30분 유효 | ✅ 해소 유지 — `handleWithdrawn`→`invalidatePreviousAccessTokens`, 필터가 iat ≤ 무효화시각 비교 |
| A-USER-2 Content-Type만 검증 | ✅ Magic Number(JPEG/PNG/GIF/WebP 12바이트) 병행 검증 |
| D-USER-1 업로드가 트랜잭션 내부 | ✅ `ProfileImagePersister` 분리 — 업로드는 무트랜잭션, 영속화만 @Transactional |
| D-USER-2 커밋 전 파일 삭제 | ✅ `deleteStoredFileAfterCommit`(TransactionSynchronization) |
| D-USER-3 탈퇴 시 connections/fcm 잔존 | ✅ hard delete CASCADE + 탈퇴 리스너(connection/fcm)로 해소 |
| B-USER-1 비밀번호 정규식 3중복 | ✅ `@ValidPassword` 3곳(Register/PasswordResetConfirm/PasswordChange) 일관 |
| B-USER-2 findById 6회 반복 | ✅ `getUserOrThrow` 통일 |
| E-USER-1 비번변경 감사 부재 | ✅ `[PASSWORD-CHANGE]` INFO(userId만) |
| 인증코드 소비 순서(06-06 규칙) | ✅ `confirmReset`: `verifyWithoutConsume` 선두 → 모든 검증·변경 후 `consume` 마지막. 카카오 nonce도 마지막 소비 + "미소비 보존" 테스트 4종 존재 |
| 카카오 가입 FK(PR #185) | ✅ `KakaoRegisteredEvent` AFTER_COMMIT + @Async(notificationExecutor) — 커밋 후 기록, CallerRunsPolicy로 유실 없음 |
| 카카오 client secret | ✅ 토큰 교환에 포함, `.env` 주입 + fail-fast 검증(스팟 05-25에서 확인) |
| 로그인 잠금 user.id 기반 + Lua 원자화 | ✅ 유지 |
| H-1/A-M1 enumeration 차단(로그인 통합 401, reset 코드 선검증) | ✅ 유지 |
| 상수시간 코드 비교(A-L1) | ✅ `MessageDigest.isEqual` 유지 |

### 신규 발견

#### 🟡 M-S1-1 · 탈퇴 2단계(withdraw→purge) 사이 실패 시 "영구 잠금 좀비 계정"
- `DELETE /api/user/me`는 ① `withdraw()`(deactivate+이벤트, 커밋) → ② AFTER_COMMIT 리스너 3종(auth 토큰정리·connection 해제·fcm 정리, **모두 동기 — purge 순서 전제는 유효함을 확인**) → ③ `purgeWithdrawnUser()`(행 삭제) 순.
- ②의 리스너 중 하나가 던지거나(예: Redis 장애 — 이때 **나머지 리스너도 스킵됨**, `@Order` 미지정으로 순서·생존 불확정) ③이 실패하면: 사용자는 **INACTIVE + access token 무효화 + refresh 삭제** 상태로 남는다 → 재로그인 불가(INACTIVE), 탈퇴 재시도 불가(토큰 401), 재가입 불가(이메일/전화 잔존). **hard delete가 고치려던 바로 그 증상**이 복구 경로 없이 재현되며, 수동 DB 개입 필요.
- 확률 낮음(리스너/purge 실패 = 인프라 장애급)·영향 큼. **권장**: ⓐ 컨트롤러에서 ②③ 실패를 잡아 purge 재시도 1회 + 실패 시 ERROR 로깅(운영 알림 기준), 또는 ⓑ INACTIVE(관리자 제한)와 구분되는 탈퇴-중간 상태 식별자(예: 별도 플래그/스케줄 스윕)로 자동 회복. 설계 결정 필요 → 수정은 별도 승인.

#### 🟢 L-S1-2 · `signup`·`signup/kakao`만 IP RateLimit 부재
- 공개 auth 엔드포인트 중 이 2곳만 `rateLimitService.check` 미적용. 실효 위험은 낮음(무nonce 요청은 exists 쿼리 2회/Redis get에서 즉시 차단, bcrypt는 nonce 통과 후) — 방어 일관성 차원의 보완 후보.

#### 🟢 L-S1-3 · Swagger 429 미문서화 (B4와 중복 — §3 참조)

#### 🟢 L-S1-4 · `FindEmailRequest.phone`에 `@Pattern` 누락
- 타 DTO 6곳은 `^\d{10,11}$` 적용. 미검증 임의 문자열로 DB 조회 발생(파라미터 바인딩이라 인젝션은 불가) — 형식 통일 권장.

#### 🟢 L-S1-5 · `updateProfileImage` 영속화 실패 시 신규 업로드 파일 고아화
- 업로드(트랜잭션 밖) 성공 → `persister.replace` 실패(USER_NOT_FOUND/DB 오류) 시 새 파일이 파일서버에 잔존, 정리 경로 없음. D-USER-1 구조 분리로 생긴 새 경로. catch 후 fire-and-forget 삭제 한 줄로 보완 가능.

### PII / 입력 검증 / 동시성

- **PII**: `UserProfileResponse`·`LoginResponse`에 password/providerId/내부토큰 없음 ✅. `KakaoLoginResponse.kakaoId`는 신규가입 왕복용 **의도된 노출**(악용엔 pending 세션+SMS nonce 필요 → 단독 무가치). 로그는 `MaskingUtil` 일관 ✅. (기존 Low A-USER-4 `FileServerClient.delete` WARN의 fileUrl 노출은 미해결 — 기존 이슈)
- **입력 검증**: DTO 검증 촘촘(L-S1-4 한 건 제외). 네이티브 쿼리 없음, 전부 파라미터 바인딩 ✅.
- **동시성**:
  - 탈퇴 리스너 3종 모두 동기 AFTER_COMMIT → purge 선행 전제 **충족 확인** (단 M-S1-1의 실패 전파 리스크).
  - 동시 중복 탈퇴(더블클릭): 두 번째 purge가 0행 delete → `StaleStateException` 계열 500 가능성(첫 요청 성공엔 무영향) — Low, 기존 A-USER-3(전화 TOCTOU 500)과 같은 계열.
  - 가입 email/phone exists 후 save race → DB 유니크가 최종 방어, 23505→409 매핑(PR #185) ✅.

## 3. PHASE B — API 계약

- **B1 응답 포맷**: 24개 전부 `ApiResponse<T>` (success/data/message) ✅.
- **B2 상태코드·메서드**: signup 2종 201 ✅, 인증류 401/잠금 429/중복 409 의미 정확 ✅. `INVALID_PASSWORD`=401(의미상 400/422 논쟁은 기존 C-USER-2 — 전역 일관 유지 결정 존중).
- **B3 URL 패턴**: `signup/sms`·`find-password/{채널}/{동작}` 계층 일관, kebab-case 일관 ✅.
- **B4 Swagger ↔ 구현** (🟢 L-S1-3):
  - `email/check`·`signin/kakao`·`refresh`·`find-email`·`password/reset`은 rate limit이 있는데 **429가 @ApiResponses에 없음**(FindPassword·Sms 컨트롤러는 문서화함 — 컨트롤러 간 불일치).
  - `find-email`의 "둘 다 있는 경우(maskedEmail+hasKakaoAccount)"는 **도달 불가능 사례**: `uq_users_phone`(V2)이 전역 부분 유니크(WHERE phone IS NOT NULL)라 name+phone 매칭은 최대 1명. Swagger 설명·스트림 dual 분기가 죽은 케이스를 기술(무해, 문서 정리 권장).
- **B5 에러 메시지 톤**: 시니어 친화 존댓말 일관 ✅. (기존 Low C-USER-1: `SOCIAL_USER_NO_PASSWORD` 문구가 "재설정" 한정인데 변경·탈퇴 흐름에서도 사용 — 미해결 기존 이슈)

## 4. PHASE C — 구조·품질

- **@Transactional 경계**: 외부 I/O(메일·SMS·파일업로드)는 전부 트랜잭션 밖(M-5/D-USER-1 일관) ✅. REQUIRES_NEW 분리(폐기·접속로그)는 롤백-생존 의도가 주석으로 명시 ✅.
- **이벤트**: 4종(PasswordChanged/UserWithdrawn/KakaoRegistered + 발행부) 전부 AFTER_COMMIT ✅. KakaoRegistered만 @Async(접속로그 — 응답시간 분리 목적 명시) — 의도 문서화됨.
- **JPA**: `User` 연관관계 없음 → N+1 원천 부재, 응답 DTO 스칼라만 ✅.
- 🟢 L-S1-6 (clean-code): `SmsVerificationService.generateCode()`가 호출마다 `new SecureRandom()` 생성 — `PasswordResetService`는 static 재사용. 미세 비효율+비일관, static 통일 권장.

## 5. PHASE D — 테스트 갭 (단위 테스트만, 통합 테스트는 의도적 제외)

| 갭 | 내용 |
|---|---|
| 🟡 `SmsService` 테스트 클래스 부재 | `sendVerificationCode`(가입번호 409 분기)·`verifyCode`(nonce 발급)·`consumeVerification`(H-5 보안핵심: null/불일치/소비) 직접 검증 없음. 공통로직(`SmsVerificationServiceTest`)만 존재 |
| 🟢 `AuthService` 성공 경로 일부 | login/register/refresh **성공** 경로, `findEmail`·`logout`·`checkEmail` 테스트 없음(실패 경로·재사용 감지·잠금은 충실) |
| 🟢 `UserService.purgeWithdrawnUser` | 멱등(이미 삭제)·이미지 afterCommit 삭제 경로 검증 없음 (UserServiceTest 25건은 기준선 갭 F-USER-1~5 해소 확인) |

## 6. 이슈 요약

| ID | 심각도 | 도메인 | 내용 | 권장 |
|---|---|---|---|---|
| M-S1-1 | 🟡 Medium | user | 탈퇴 2단계 사이 실패 시 좀비 계정(재로그인·재시도·재가입 모두 불가, 수동 복구) | purge 재시도+ERROR 알림 로깅 또는 탈퇴-중간 상태 식별 |
| L-S1-2 | 🟢 Low | auth | signup 2종만 IP RateLimit 부재 | `rateLimitService.check` 추가(일관성) |
| L-S1-3 | 🟢 Low | auth | 429 Swagger 미문서화 5곳 + find-email 죽은 dual 케이스 문서 | @ApiResponses 보완 |
| L-S1-4 | 🟢 Low | auth | FindEmailRequest.phone @Pattern 누락 | `^\d{10,11}$` 추가 |
| L-S1-5 | 🟢 Low | user | 이미지 영속화 실패 시 신규 업로드 고아 파일 | 실패 시 fire-and-forget 삭제 |
| L-S1-6 | 🟢 Low | auth | SecureRandom 매 호출 생성 | static 통일 |
| (D) | 🟡/🟢 | auth/user | 테스트 갭 3건 (§5) | SmsServiceTest 우선 |

**Critical/High: 없음.** 기존 미해결(중복 보고 제외): A-USER-3·A-USER-4·C-USER-1(Low 백로그), find-password 프론트 협의·모니터링 TODO.

## 7. 도메인 간 일관성 (세션 간 비교용)

- 응답 포맷·에러 톤·kebab-case URL은 auth/user 내부 일관 — 세션 2·3에서 동일 기준으로 대조할 것.
- 429 문서화 관행이 컨트롤러마다 갈림(FindPassword/Sms는 O, 나머지 X) — 전 도메인 공통 규칙 수립 후보.

## 8. 다음 세션 인계

- **세션 2 (connection + notification + SOS)**:
  - 탈퇴 리스너 `UserWithdrawalConnectionListener`/`UserWithdrawalFcmListener`는 **동기 AFTER_COMMIT임을 이번 세션에서 확인**(purge 순서 전제) — 세션 2에서 내부 로직(상대 알림·정리 범위)을 점검하되 @Async로 바꾸면 M-S1-1 전제가 깨짐을 유의.
  - M-S1-1 논의 시 connection 정리 실패 시나리오 포함할 것.
- **세션 3 (global)**: `RateLimitService`·`VerificationCodeValidator`·`JwtAuthenticationFilter`·`GlobalExceptionHandler`는 이번에 동작 확인만 했고 풀 점검은 세션 3 몫.