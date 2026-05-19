# auth / user 도메인 — 프론트 프로토타입 갭 분석

> 분석 전용 문서. 코드·마이그레이션 변경 없음. 수정 여부는 검토 후 결정.
> 작성일: 2026-05-19 · 대상 브랜치: `dev`

---

## 1. 요약

| 항목 | 값 |
|------|-----|
| 점검한 화면 수 | **9** (Image 1 ~ Image 9) |
| 점검한 백엔드 파일 | Java 55개 (auth 45 + user 10) + Flyway 17개(V1~V17) |
| 발견된 갭 총 개수 | **8** |
| 사용자 결정으로 제외 | 2 (로그인 유지 / 약관 동의) |

### 심각도별 갭 개수

| 심각도 | 개수 | 항목 |
|--------|------|------|
| 🔴 Critical | 2 | 성별(gender), 생년월일(birth_date) — 필수 입력인데 저장 경로 전무 |
| 🟠 High | 2 | 우편번호(postcode) 저장 불가, 아이디 찾기 결과 가입일 미반환 |
| 🟡 Medium | 2 | 비밀번호 규칙 안내 불일치, 인증코드 TTL/쿨다운 미응답 |
| 🟢 Low | 2 | 이메일 마스킹 형식 차이, SMS 코드 형식검증 일관성 |

### 핵심 결론 (3줄)

1. **회원가입(Image 8)** — 프로토타입 필수 입력인 **성별·생년월일·우편번호** 3개가 `RegisterRequest`/`KakaoRegisterRequest`/`User` 엔티티/`users` 테이블 어디에도 없다. DTO + 스키마(V18) 동시 변경 필요.
2. **아이디 찾기 결과(Image 3)** — 화면은 "가입일 2024-08-12"를 표시하지만 `FindEmailResponse`에 가입일 필드가 없다. `created_at`은 이미 DB에 존재하므로 **DTO/서비스만 수정**하면 됨(스키마 변경 불필요).
3. 그 외 로그인/아이디찾기 입력/비밀번호 찾기(이메일·SMS) 흐름·엔드포인트·필드는 **대부분 정합**. 비밀번호 정책 안내 문구와 인증코드 잔여시간 응답 계약만 합의 필요.

> ⚠️ **마이그레이션 버전 주의**: 요청서에는 "V14까지 / V15 초안"으로 적혀 있으나 실제 마이그레이션은 **V17까지 존재**(V15=인덱스, V16=토큰 재사용 로그, V17=미사용 테이블 제거). 따라서 신규 마이그레이션 번호는 **V18**이어야 한다.

---

## 2. 백엔드 엔드포인트 인벤토리 (분석 기준)

| 화면 연관 | 메서드 · 경로 | Request | Response |
|-----------|---------------|---------|----------|
| Img1 로그인 | `POST /api/auth/signin` | `LoginRequest{email, password}` | `LoginResponse{accessToken, refreshToken, userId, email, name, role}` |
| Img1 카카오 | `POST /api/auth/signin/kakao` | `KakaoLoginRequest{code}` | `KakaoLoginResponse{isNewUser, kakaoId, email, name, profileImageUrl, accessToken, ...}` |
| Img1 이메일중복 | `POST /api/auth/signup/email/check` | `EmailCheckRequest{email}` | `Void` |
| Img2·3 아이디찾기 | `POST /api/auth/find-email` | `FindEmailRequest{name, phone}` | `FindEmailResponse{maskedEmail, hasKakaoAccount}` |
| Img4·5 비번찾기(이메일) | `POST /api/auth/find-password/email/send` `/verify` `/resend` | `PasswordResetRequest{email}` / `PasswordResetEmailVerifyRequest{email, code}` | `Void` / `PasswordResetTokenResponse{token}` |
| Img7·5 비번찾기(SMS) | `POST /api/auth/find-password/sms/send` `/verify` `/resend` | `PasswordResetSmsSendRequest{name, phone}` / `PasswordResetSmsVerifyRequest{phone, code}` | `Void` / `PasswordResetTokenResponse{token}` |
| Img6 새 비번 설정 | `POST /api/auth/password/reset` | `PasswordResetConfirmRequest{token, newPassword}` | `Void` |
| Img9 가입 SMS | `POST /api/auth/signup/sms/send` `/verify` `/resend` | `SmsSendRequest{phone}` / `SmsVerifyRequest{phone, code}` | `Void` / `SmsVerifyResponse{verificationNonce}` |
| Img8·9 회원가입 | `POST /api/auth/signup` | `RegisterRequest{email, password, name, phone, verificationNonce, role, address, addressDetail}` | `Void` |
| Img8·9 카카오 가입 | `POST /api/auth/signup/kakao` | `KakaoRegisterRequest{kakaoId, name, phone, verificationNonce, role, profileImageUrl, address, addressDetail}` | `LoginResponse` |

### `users` 테이블 최종 컬럼 (V1→V17 반영)

`id, email, password(NULL), name, phone(NULL·UNIQUE), role, status(ACTIVE|INACTIVE), provider(LOCAL|KAKAO), provider_id, profile_image, address(V10·NOT NULL DEFAULT ''), address_detail(V10·NOT NULL DEFAULT ''), last_login_at, created_at, updated_at`

- 제거됨: `email_verified`(V3), `prev_password1/2`(V5)
- **부재**: `gender`, `birth_date`, `postcode`/`zip_code`, `terms_agreed_at`

### 정책 상수 (코드 확정값)

| 정책 | 값 | 출처 |
|------|-----|------|
| Access Token 만료 | 30분 (1,800,000ms) | `application.yaml` `jwt.access-token-expiration` |
| Refresh Token 만료 | 7일 (604,800,000ms) | `application.yaml` `jwt.refresh-token-expiration` |
| 인증코드 TTL | **5분** | `SmsVerificationService.CODE_TTL_MINUTES` |
| 재발송 쿨다운 | **1분** | `SmsVerificationService.COOLDOWN_TTL_MINUTES` |
| 인증코드 최대 오류 | 5회 | `SmsVerificationService.MAX_ATTEMPTS` |
| 비번 재설정 토큰 TTL | 30분 | `PasswordResetService.RESET_TOKEN_TTL_MINUTES` |
| SMS 인증 nonce TTL | 10분 | `SmsService.VERIFIED_TTL_MINUTES` |
| 로그인 잠금 | 5회 실패 → 30분 잠금 | `AuthLoginProperties` |
| 비밀번호 규칙 | 영문+숫자+특수문자, 공백없이 **8자 이상** (`@Size`+`@Pattern` 인라인) | `RegisterRequest` / `PasswordResetConfirmRequest` / `PasswordChangeRequest` |

> 참고: 요청서가 언급한 `@ValidPassword` 커스텀 애너테이션은 **존재하지 않음**. 비밀번호 정책은 각 DTO에 동일 `@Size(min=8)` + `@Pattern` 정규식으로 인라인 중복 정의되어 있다.

---

## 3. 화면별 갭 분석

### Image 1 — 로그인 화면

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 이메일/비밀번호 입력 | `POST /api/auth/signin` · `LoginRequest{email,password}` | ✅ 정합 |
| 아이디 찾기 링크 | `POST /api/auth/find-email` | ✅ 정합 |
| 비밀번호 찾기 링크 | `/api/auth/find-password/...` (이메일·SMS) | ✅ 정합 |
| 카카오로 로그인 | `POST /api/auth/signin/kakao` | ✅ 정합 |
| 회원가입 링크 | 가입 흐름 존재 | ✅ 정합 |
| **로그인 유지 체크박스** | 토큰 만료 고정(access 30분/refresh 7일), 차등 없음 | ⛔ **제외(사용자 결정)** — 프론트 처리 |

**갭**: 없음 (엔드포인트 경로가 `/login`이 아닌 `/signin`인 점은 명세 차이일 뿐 기능 갭 아님).
**참고**: `LoginResponse`는 로그인 직후 화면 전환에 충분한 `userId/email/name/role` 제공.

---

### Image 2 — 아이디 찾기 (입력)

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 이름 + 전화번호 입력 | `FindEmailRequest{name, phone}` | ✅ 정합 |
| 아이디 찾기 버튼 | `POST /api/auth/find-email` | ✅ 정합 |

**갭**: 없음.

---

### Image 3 — 아이디 찾기 (결과)  🟠 High

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 마스킹된 이메일 (`yo****ee@naver.com`) | `FindEmailResponse.maskedEmail`, `MaskingUtil.maskEmail` | 🟢 형식 경미 불일치 |
| **가입일 표시 (`가입일 2024-08-12`)** | `FindEmailResponse`에 가입일 필드 **없음**. `AuthService.findEmail`은 `new FindEmailResponse(maskedEmail, hasKakaoAccount)`만 생성 | 🟠 **High — 누락** |
| 로그인하기 버튼 | 화면 전환만, API 불필요 | ✅ |

**갭 상세**
- **G-1 (🟠 High)** 가입일 미반환. 화면이 가입일을 노출하나 응답 DTO에 대응 필드 없음. `created_at` 컬럼은 `users`에 이미 존재 → **스키마 변경 불필요**, `FindEmailResponse`에 `joinedAt`(LOCAL 계정 기준) 추가 + `AuthService.findEmail`에서 채워 반환하면 해결.
- **G-7 (🟢 Low)** 마스킹 형식 차이. 백엔드는 고정 `***`(3개) 사용 → `younghee@naver.com` → `yo***ee@naver.com`. 프로토타입은 `yo****ee@naver.com`(`****` 4개). 자릿수가 원문 길이를 반영하지 않으며 별표 개수가 화면과 다름. 기능 문제 아님, 표기 합의 필요.

**권장 조치**: `FindEmailResponse`에 가입일 필드 추가(서비스/DTO만). 마스킹 별표 개수는 프론트와 합의 후 한쪽 통일.

---

### Image 4 — 비밀번호 찾기 (방법 선택 · 이메일)

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 이메일/SMS 인증 방식 선택 | 양 경로 모두 백엔드 존재 (`find-password/email/*`, `find-password/sms/*`) | ✅ 정합 |
| 이메일 입력 → 인증코드 받기 | `POST /api/auth/find-password/email/send` · `PasswordResetRequest{email}` | ✅ 정합 |

**갭**: 없음.
**참고**: 보안상 미존재 이메일·카카오 계정도 200 반환(가입 여부 노출 방지) — 프론트는 항상 "발송됨" 처리 필요(의도된 설계).

---

### Image 5 — 비밀번호 찾기 (인증코드 입력)  🟡 Medium

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 6자리 인증코드 입력 | 이메일: `PasswordResetEmailVerifyRequest{email, code}` `code @Pattern ^\d{6}$` ✅ / SMS: `PasswordResetSmsVerifyRequest{phone, code}` `code @NotBlank만` | 🟢 일관성 부족 |
| 남은 시간 표시 (`02:43`) | TTL=5분 확정. 단, send/verify 응답이 **잔여 시간을 내려주지 않음** | 🟡 Medium |
| 다시 받기 링크 | `/email/resend` `/sms/resend` 존재, 쿨다운 1분. 응답에 쿨다운 잔여 미포함 | 🟡 Medium (확인 필요) |
| 이전 / 확인 버튼 | "확인" = `.../verify` | ✅ 정합 |

**갭 상세**
- **G-6 (🟡 Medium)** 인증코드 잔여시간·쿨다운이 응답에 없음. 화면의 `02:43` 카운트다운과 "다시 받기" 활성화 타이밍을 프론트가 **하드코딩(TTL 300초 / 쿨다운 60초)** 가정해 클라이언트 카운트다운해야 함. 백엔드 TTL(5분)·쿨다운(1분)과 값 자체는 일치하나, 계약이 문서로만 존재. → 응답에 `expiresInSeconds`/`resendAvailableInSeconds` 추가하거나 API 계약 문서로 고정 권장.
- **G-8 (🟢 Low)** SMS 비번재설정 코드 검증 일관성. 이메일 경로(`PasswordResetEmailVerifyRequest`)는 `code`에 `@Pattern("^\\d{6}$")`가 있으나 SMS 경로(`PasswordResetSmsVerifyRequest`)와 가입 SMS(`SmsVerifyRequest`)는 `@NotBlank`만. 6자리 숫자 형식 검증을 동일하게 맞추는 것이 안전.

**확인 필요**: 프로토타입 카운트다운 시작값. 화면의 `02:43`은 중간 스냅샷 — 시작이 `5:00`인지(=TTL 5분과 일치) 확인 필요.

---

### Image 6 — 비밀번호 찾기 (새 비밀번호 설정)  🟡 Medium

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 새 비밀번호 (**8자 이상**) | `PasswordResetConfirmRequest.newPassword` — `@Size(min=8)` + **영문+숫자+특수문자 필수** `@Pattern` | 🟡 Medium 불일치 |
| 비밀번호 확인 (재입력) | 프론트 전용 일치 검증, 백엔드 필드 불필요 | ✅ (의도된 설계) |
| 비밀번호 변경 버튼 | `POST /api/auth/password/reset{token, newPassword}` | ✅ 정합 |

**갭 상세**
- **G-5 (🟡 Medium)** 비밀번호 규칙 안내 불일치. 화면 플레이스홀더는 "**8자 이상**"만 안내하나 백엔드는 영문·숫자·특수문자를 **모두** 포함해야 통과(`^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[특수문자])...`). 사용자가 "12345678" 입력 시 화면 안내상 통과 기대 → 백엔드 400. UX 혼선·전환 실패 유발. → 프론트 안내 문구를 백엔드 정책에 맞추거나, 정책을 "8자 이상"으로 완화하는 합의 필요(현 정책 유지 권장, 문구만 정정).
- **참고**: `confirmReset`은 현재 비밀번호와 동일 시 `SAME_AS_CURRENT_PASSWORD`(400) 차단 — 화면에 해당 에러 안내 UI 없음(🟢 경미, 프론트 메시지 보강 권장).

---

### Image 7 — 비밀번호 찾기 (SMS 인증 선택)

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 이름 + 휴대폰 번호 입력 | `PasswordResetSmsSendRequest{name, phone}` | ✅ 정합 |
| 인증코드 받기 버튼 | `POST /api/auth/find-password/sms/send` | ✅ 정합 |
| (이후) 코드 입력 → 새 비번 | `/sms/verify` → `token` → `/password/reset` | ✅ 흐름 정합 |

**갭**: 없음 (코드 입력 화면은 Image 5와 공유, G-8 동일 적용).

---

### Image 8 — 회원가입 (1단계, 기본 정보)  🔴 Critical

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 가입 유형 피보호자/보호자 | `RegisterRequest.role` (WARD/GUARDIAN) | ✅ 정합 |
| 이름 / 이메일 | `name` / `email` (+ `email/check` 중복확인) | ✅ 정합 |
| 비밀번호 / 비밀번호 확인 | `password` (확인은 프론트 전용) | 🟡 G-5 정책 불일치 동일 적용 |
| **성별 (여성/남성)** | `RegisterRequest`·`KakaoRegisterRequest`·`User`·`users` **전무** | 🔴 **Critical — 누락** |
| **생년월일** | 동일하게 **전무** | 🔴 **Critical — 누락** |
| **주소: 우편번호 + 도로명 + 상세** | 백엔드 `address`(도로명)+`addressDetail`(상세)만. **우편번호(postcode) 없음** | 🟠 **High — 부분 누락** |
| 다음 버튼 | 백엔드에 1단계 전용 엔드포인트 없음 (단일 `/signup`) | ✅ 흐름상 정상(아래 흐름분석) |

**갭 상세**
- **G-2 (🔴 Critical)** 성별 저장 경로 전무. 화면 필수 입력. `users.gender` 컬럼 + `RegisterRequest`/`KakaoRegisterRequest`에 `gender` 필드 + `Gender` enum + `User.builder().gender(...)` 매핑 필요.
- **G-3 (🔴 Critical)** 생년월일 저장 경로 전무. 화면 필수 입력. `users.birth_date DATE` + DTO `birthDate` 필드 필요.
- **G-4 (🟠 High)** 우편번호 저장 불가. 화면은 `우편번호 / 도로명주소 / 상세주소` 3분할(카카오 주소 API 연동)인데 백엔드는 2분할만 수용 → 우편번호 유실. 도로명·상세는 보존되므로 Critical 대비 한 단계 낮게 분류하나, 화면 필수 입력이므로 V18에 포함 권장.

**모든 가입 경로 영향**: 일반(`RegisterRequest`)뿐 아니라 **카카오(`KakaoRegisterRequest`)** 도 동일하게 3필드 부재. V18 + DTO 수정 시 양쪽 모두 반영해야 함. (`UserProfileResponse`/`UserUpdateRequest`도 동 필드 부재 — 프로필 화면 도입 시 후속 영향, 이번 범위 외 참고만)

---

### Image 9 — 회원가입 (2단계, 전화번호 인증)  🟡 Medium

| 화면 요구 | 백엔드 상태 | 판정 |
|-----------|-------------|------|
| 전화번호 + 인증요청 | `POST /api/auth/signup/sms/send` · `SmsSendRequest{phone}` (가입된 번호면 409) | ✅ 정합 |
| 6자리 인증번호 입력 | `POST /api/auth/signup/sms/verify` · `SmsVerifyRequest{phone, code}` → `SmsVerifyResponse{verificationNonce}` | ✅ 정합 |
| 남은 시간 표시 (`02:43`) | TTL 5분, 응답에 잔여시간 미포함 | 🟡 G-6 동일 적용 |
| **이용약관·개인정보 동의 체크박스** | 저장 경로 전무 | ⛔ **제외(사용자 결정)** |
| 이전 / 가입 완료 | "가입 완료" = `POST /api/auth/signup` (verify 응답 `verificationNonce` 동봉) | ✅ 정합 |

**갭 상세**
- **G-6 (🟡 Medium)** Image 5와 동일 — 인증코드 잔여시간 응답 미제공.

---

## 4. 흐름(Flow) 일치 분석

| 흐름 | 프로토타입 | 백엔드 | 판정 |
|------|-----------|--------|------|
| 비번찾기(이메일) | 방식선택→이메일입력→코드입력→새비번 | `email/send`→`email/verify`(token)→`password/reset` | ✅ 일치 |
| 비번찾기(SMS) | 이름+전화→코드입력→새비번 | `sms/send`→`sms/verify`(token)→`password/reset` | ✅ 일치 |
| 회원가입 | 1단계 기본정보 → 2단계 전화인증 → 가입완료 | `email/check`→`sms/send`→`sms/verify`(nonce)→`signup`(전체 데이터 일괄 전송) | ✅ 일치(주의 1) |
| 카카오 가입 | (프로토타입 화면 없음) | `signin/kakao`(isNewUser)→`sms/send`→`sms/verify`→`signup/kakao` | ✅ 백엔드 정상, 화면 미제공 |

**주의 1**: 백엔드 `/signup`은 단일 호출로 기본정보+전화인증(nonce)을 한 번에 받는다. 프로토타입의 "1단계 다음"은 백엔드 호출이 아닌 **프론트 화면 전환**이며, 1단계 입력값은 프론트가 보관했다가 2단계 SMS 인증 완료 후 `verificationNonce`와 함께 `/signup`으로 일괄 전송해야 한다(설계상 정상, 프론트 구현 가이드 필요).

---

## 5. DB 스키마 변경 제안

### 추가 필요 컬럼

| 컬럼 | 타입 | NULL | 비고 |
|------|------|------|------|
| `gender` | `VARCHAR(10)` | NULL 허용 | `CHECK IN ('MALE','FEMALE')`. 신규가입 필수화는 앱 레이어에서 강제 |
| `birth_date` | `DATE` | NULL 허용 | 미래일·최소연령 검증은 DTO에서 |
| `postcode` | `VARCHAR(10)` | NULL 허용 | 카카오 주소 API 우편번호(5자리, 여유 10) |

> 약관 동의(`terms_agreed_at`)는 **사용자 결정으로 이번 제안에서 제외**.

### Flyway 마이그레이션 초안 (V18 — **초안만, 파일 생성 금지**)

```sql
-- V18__add_user_profile_fields.sql  (초안)
-- 회원가입 프로토타입(Image 8) 필수 입력 대응: 성별·생년월일·우편번호
-- 가역적 추가(컬럼 ADD, NULL 허용) — 비가역 DDL 아님

ALTER TABLE users
    ADD COLUMN gender     VARCHAR(10) NULL,
    ADD COLUMN birth_date DATE        NULL,
    ADD COLUMN postcode   VARCHAR(10) NULL;

ALTER TABLE users ADD CONSTRAINT chk_users_gender
    CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE'));
```

### 기존 데이터 영향 분석

- **데이터 손실 없음**: 3개 컬럼 모두 NULL 허용 ADD → 기존 행은 자동 NULL. 비가역 DDL 아님(롤백 시 `DROP COLUMN` 가능).
- **신규 가입 필수화 방식**: DB `NOT NULL`을 걸면 기존 행이 깨지므로, **애플리케이션 레이어**(`RegisterRequest`/`KakaoRegisterRequest`에 `@NotNull`/`@NotBlank` + `Gender` enum 매핑)에서 신규 가입만 필수로 강제하는 것을 권장.
- **선례 비교**: 주소(V10)는 `NOT NULL DEFAULT ''`로 추가되어 기존 사용자에게 빈 문자열이 들어갔음. 성별/생년월일은 빈 문자열·기본값이 의미가 없으므로 **NULL 권장**(기존 사용자=미입력 상태로 구분 가능).
- **기존 사용자 보완 입력**(프로필 수정 시 성별/생년월일/우편번호 입력 유도) 여부는 정책 결정 필요 — 아래 "확인 필요" 참조.

---

## 6. 정책 불일치 항목

| 정책 | 프로토타입 | 백엔드 | 결론 |
|------|-----------|--------|------|
| **로그인 유지** | 체크박스 존재 | 토큰 만료 고정(access 30분/refresh 7일) | ⛔ **제외(사용자 결정)** — 프론트가 토큰 저장 위치(localStorage/sessionStorage)로 처리. 백엔드 변경 없음. 단, refreshToken은 서버에서 7일 후 무효화되므로 "로그인 유지"여도 7일 초과 시 재로그인 필요(의도 확인됨) |
| **약관 동의 저장** | 동의 체크박스 | 저장 경로 전무(DTO/엔티티/스키마 모두 없음) | ⛔ **제외(사용자 결정)** — 이번 분석/스키마 제안 범위 외 |
| **비밀번호 규칙** | "8자 이상"만 표기 | 영문+숫자+특수문자, 공백없이 8자+ | 🟡 G-5 — 프론트 안내 문구를 실제 정책에 맞춰 정정 권장(정책 유지) |
| **인증코드 TTL** | `02:43` 카운트다운 | 5분(300초) 고정 | 🟡 G-6 — 값 일치하나 응답 미전달, 계약 문서화 또는 응답 필드화 |
| **재발송 쿨다운** | "다시 받기" 링크 | 1분(60초) | 🟡 G-6 — 동일, 쿨다운 잔여 응답 미전달 |

---

## 7. 확인 필요 (가정하지 않음)

1. **우편번호 저장 방식** — 별도 `postcode` 컬럼 vs 도로명주소 문자열에 포함? (프론트 카카오 주소 API 결과를 우편번호/도로명/상세로 분해 전송하는지 확인)
2. **성별 허용값** — `MALE`/`FEMALE`만? `OTHER`/`미지정` 포함 여부 (CHECK 제약·enum 정의에 영향)
3. **생년월일 검증 정책** — 타입 `DATE` 가정. 미래일 차단·최소 연령(예: 만 14세) 제한 필요 여부
4. **기존 사용자 보완** — 신규 가입만 3필드 필수 vs 기존 사용자에게도 프로필 수정 시 입력 유도?
5. **카카오 가입 폼** — 프로토타입은 일반 가입 화면(Image 8)만 제공. 카카오 신규 가입도 동일하게 성별/생년월일/우편번호를 입력받는지(=`KakaoRegisterRequest`도 동일 변경 필요한지)
6. **인증코드 카운트다운 시작값** — 화면 `02:43`은 중간 스냅샷. 시작이 `5:00`(=백엔드 TTL 5분)과 일치하는지
7. **마스킹 별표 개수** — 백엔드 고정 `***`(3개) vs 프로토타입 `****`(4개) 중 어느 쪽으로 통일할지

---

## 8. 우선순위 처리 권장 순서

### 🔴 Critical / 🟠 High — 선처리 (DTO + 스키마 동반 변경)

1. **G-2 성별 / G-3 생년월일 / G-4 우편번호** (Image 8) — V18 마이그레이션 + `RegisterRequest`·`KakaoRegisterRequest` 필드 추가 + `Gender` enum + `User` 엔티티/빌더 매핑. **한 PR로 묶어 처리** 권장(동일 가입 흐름·동일 마이그레이션). 진행 전 "확인 필요" 1·2·3·5 결정 선행.
2. **G-1 아이디 찾기 가입일** (Image 3) — 스키마 변경 불필요(`created_at` 존재). `FindEmailResponse` + `AuthService.findEmail`만 수정. 독립적·저위험 → 빠르게 처리 가능.

### 🟡 Medium — 후속 (정책/계약 합의 후)

3. **G-5 비밀번호 규칙 안내** — 프론트 안내 문구 정정(백엔드 무변경 가능) 또는 정책 완화 합의.
4. **G-6 인증코드 잔여시간/쿨다운 계약** — API 계약 문서화로 단기 해소, 중기적으로 응답 필드(`expiresInSeconds` 등) 추가 검토.

### 🟢 Low — 백로그

5. **G-7 이메일 마스킹 별표 개수** — 프론트/백 합의 후 한쪽 통일.
6. **G-8 SMS 코드 6자리 형식검증 일관성** — `PasswordResetSmsVerifyRequest`/`SmsVerifyRequest`에 `@Pattern("^\\d{6}$")` 추가로 이메일 경로와 일관화.

### 제외 (사용자 결정 — 조치 안 함)

- 로그인 유지(프론트 처리), 약관 동의(범위 외).

---

## 부록: 갭 ID 인덱스

| ID | 화면 | 영역 | 심각도 | 한 줄 요약 |
|----|------|------|--------|-----------|
| G-1 | Img3 | DTO/API | 🟠 High | 아이디찾기 결과에 가입일 미반환(스키마 무변경) |
| G-2 | Img8 | 스키마/DTO | 🔴 Critical | 성별 저장 경로 전무 |
| G-3 | Img8 | 스키마/DTO | 🔴 Critical | 생년월일 저장 경로 전무 |
| G-4 | Img8 | 스키마/DTO | 🟠 High | 우편번호 저장 불가(주소 2분할만) |
| G-5 | Img6/8 | 정책 | 🟡 Medium | 비밀번호 규칙 안내 불일치 |
| G-6 | Img5/9 | 정책/API | 🟡 Medium | 인증코드 TTL·쿨다운 응답 미전달 |
| G-7 | Img3 | API | 🟢 Low | 이메일 마스킹 별표 개수 차이 |
| G-8 | Img5/7 | DTO | 🟢 Low | SMS 코드 6자리 형식검증 일관성 부족 |
| (제외) | Img1 | 정책 | — | 로그인 유지 — 프론트 처리(사용자 결정) |
| (제외) | Img9 | 스키마/정책 | — | 약관 동의 — 범위 외(사용자 결정) |
