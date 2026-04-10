# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Info
- 메인 페이지: dmu.gosky.kr
- 백엔드 API: api.dmu.gosky.kr : port 6511
- PostgreSQL 17: port 6513
- Redis 7.2: port 6514

## Stack
- Java 21, Spring Boot 4.0.5, Gradle
- Spring Security + OAuth2 Client
- Spring Data JPA + PostgreSQL
- Spring Data Redis
- Spring Mail
- JJWT 0.12.6
- Lombok

## Docker
- 파일: `docker-compose.dev.yml`
- container_name: `dmusso-{env}-{service}` (예: dmusso-dev-db)
- 볼륨명: `dmusso-{env}-{service}-data`
- 네트워크명: `dmu-{env}-net`
- 민감 정보는 `.env.dev` 파일로 분리
- `.env.*` 파일은 git에 올리지 않음

## Package Structure (도메인형)
```
kr.silverbridge.main
├── domain
│   ├── auth
│   │   ├── controller      # AuthController, KakaoAuthController, SmsController, PasswordResetController
│   │   ├── service         # AuthService, KakaoAuthService, SmsService, PasswordResetService
│   │   ├── dto             # LoginRequest/Response, RegisterRequest, KakaoLoginResponse, KakaoRegisterRequest 등
│   │   ├── entity          # RefreshToken, AccessLog
│   │   ├── repository
│   │   └── oauth           # 카카오 OAuth 클라이언트
│   ├── user
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity          # User
│   │   └── dto
│   └── admin
│       ├── controller
│       ├── service
│       └── dto
└── global
    ├── config              # Redis, Mail, Web 설정
    ├── security            # Security 필터/설정
    ├── jwt                 # JWT 발급/검증
    ├── enums               # Role, Status, Provider
    ├── entity              # BaseTimeEntity
    ├── response            # ApiResponse 공통 포맷
    ├── exception           # GlobalExceptionHandler, ErrorCode, CustomException
    └── aop                 # 공통 로그
```

### 주요 Enum 값
| Enum | 값 |
|------|-----|
| `Role` | `WARD`(피보호자), `GUARDIAN`(보호자), `ADMIN`(관리자) |
| `Status` | `ACTIVE`(정상), `INACTIVE`(탈퇴) |
| `Provider` | `LOCAL`, `KAKAO` |

## Coding Rules
- Lombok 사용
- DTO 요청/응답 분리 (`XxxRequest` / `XxxResponse`)
- 한국어 주석
- `BaseTimeEntity` 공통 부모 클래스 사용

## Swagger 작성 규칙

Swagger UI는 **프론트엔드 개발자**가 보는 문서다. 프론트가 별도로 묻지 않고 Swagger만 보고 API를 연동할 수 있도록 작성한다.

### DTO — @Schema 필수
모든 Request/Response DTO의 클래스와 필드에 `@Schema`를 추가한다.

```java
@Schema(description = "로그인 요청")
public class LoginRequest {

    @Schema(description = "가입한 이메일 주소", example = "user@example.com")
    private String email;

    @Schema(description = "비밀번호", example = "Password1!")
    private String password;
}
```

- `description`: 필드가 무엇인지, 어디서 온 값인지, 어떻게 쓰는지
- `example`: 실제 사용할 수 있는 예시 값
- 응답 필드는 다음 API 호출에 어떻게 쓰는지 명시 (예: "POST /api/auth/refresh 의 refreshToken 필드에 전달")
- 선택 필드는 `nullable = true` 추가
- Enum 필드는 `allowableValues` 추가

### Controller — @Operation 필수
`@Operation`의 `description`에 아래 내용을 포함한다.

**다단계 API (SMS 인증, 카카오 회원가입 등)**
```java
@Operation(
    summary = "회원가입",
    description = """
        [일반 회원가입 전체 흐름]
        1. POST /api/auth/email/check   → 이메일 중복 확인
        2. POST /api/auth/sms/send      → SMS 인증코드 발송
        3. POST /api/auth/sms/verify    → SMS 인증코드 확인
        4. POST /api/auth/register      → 회원가입 완료 (현재 API)
        """
)
```

**인증이 필요한 API**
```java
description = """
    [요청 헤더]
    Authorization: Bearer {accessToken}
    """
```

**응답 분기가 있는 API (예: 카카오 로그인)**
```java
description = """
    isNewUser 값에 따라 응답 구조가 달라집니다.
    - isNewUser=false (기존 회원): accessToken, refreshToken 사용
    - isNewUser=true  (신규 회원): kakaoId, email, name 사용 → 회원가입 흐름 진행
    """
```

**제한사항이 있는 API (SMS, 토큰 등)**
```java
description = """
    [제한사항]
    - 인증코드 유효 시간: 5분
    - 재발송 가능 시간: 1분 후
    - 5회 이상 오류 시 인증코드 초기화 → 재발송 필요
    """
```

### @ApiResponse 작성 기준
- 200: 성공 시 어떤 값이 반환되는지 명시
- 400: 어떤 입력이 잘못됐을 때 발생하는지 구체적으로 명시
- 보안상 항상 200을 반환하는 경우 그 이유 명시

```java
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "200",
    description = "요청 처리 완료 (이메일 미존재 시에도 동일하게 200 반환 — 이메일 존재 여부 노출 방지)"
)
```

## Git Branch Strategy

### 브랜치 구조
- `prod`: 배포의 기준 브랜치
- `dev`: 개발의 기준 브랜치 — Merge Request를 통해서만 feature 브랜치로부터 반영
- `feature/*`: 개발 작업 브랜치 — dev에서 분기, 작업 후 MR → dev, 브랜치 삭제

### 브랜치 네이밍
형식: `type/short-description` 또는 `type/short-description-author`

| type | 용도 |
|------|------|
| `feature` | 기능 개발 |
| `fix` | 버그 수정 |
| `hotfix` | 운영 긴급 수정 |
| `refactor` | 리팩토링 |
| `design` | UI/디자인 작업 |
| `docs` | 문서 작업 |
| `test` | 테스트 코드 |
| `chore` | 설정/패키지/잡일 |
| `infra` | 서버/Docker/Nginx/CI-CD |
| `release` | 배포 준비 |

예시: `feature/login-api`, `fix/signup-validation`, `infra/docker-nginx-setting`
이슈 번호 포함: `feature/123-login-api`, `fix/87-token-refresh`

### 브랜치 작업 순서
1. `git pull origin dev` — 최신 코드 동기화
2. `git checkout -b type/short-description` — 브랜치 분기
3. 작업 및 커밋
4. push 전 `git merge dev` → 충돌 해결
5. `git push origin type/short-description`
6. MR(PR) 생성 → dev로 머지 후 **브랜치 삭제** (브랜치 삭제를 전제로 MR 진행)

### 브랜치 작성 규칙
- 전부 소문자, 띄어쓰기 금지, 단어 구분은 `-`
- 너무 길지 않게, 기능 단위가 보이게 작성

### 커밋 메시지 형식
```
type: 무슨 작업을 했는지 한국어로
- 상세 내용 (선택)
- 상세 내용 (선택)
```

| type | 용도 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 리팩토링 |
| `design` | UI/디자인 변경 |
| `style` | 코드 포맷 (비즈니스 로직 변경 없음) |
| `docs` | 문서 수정 |
| `test` | 테스트 코드 |
| `chore` | 기타 변경사항 |
| `init` | 초기 생성 |
| `rename` | 파일/폴더 이동 및 이름 변경 |
| `remove` | 파일 삭제 |

### 커밋 작성 규칙
- 첫 글자는 소문자
- 한 커밋 = 한 작업
- 무슨 작업인지 바로 알 수 있게 작성

## DB
- schema.sql: db/schema.sql 참고
- 모든 날짜/시간 컬럼은 `TIMESTAMPTZ` 사용 (Java: `OffsetDateTime`)

### Redis (임시 저장)
| 키 패턴 | 용도 | TTL |
|---------|------|-----|
| `sms:verify:{phone}` | 회원가입 SMS 인증코드 | 5분 |
| `sms:verified:{phone}` | 회원가입 SMS 인증 완료 상태 | 10분 |
| `sms:cooldown:{phone}` | 회원가입 SMS 재발송 쿨다운 | 1분 |
| `sms:attempt:{phone}` | 회원가입 SMS 오류 횟수 | 5분 |
| `password:reset:{token}` | 비밀번호 재설정 토큰 | 30분 |
| `password:sms:verify:{phone}` | 비밀번호 재설정 SMS 인증코드 | 5분 |
| `password:sms:cooldown:{phone}` | 비밀번호 재설정 SMS 재발송 쿨다운 | 1분 |
| `password:sms:attempt:{phone}` | 비밀번호 재설정 SMS 오류 횟수 | 5분 |
| `kakao:pending:{kakaoId}` | 카카오 신규 가입 임시 이메일 | 10분 |
| `logout:{accessToken}` | 로그아웃된 토큰 블랙리스트 | 토큰 남은 만료시간 |
| `login:fail:{email}` | 로그인 실패 횟수 | 30분 |
| `login:lock:{email}` | 로그인 잠금 상태 (5회 실패 시 설정) | 30분 |

## 카카오 OAuth 플로우

### 신규 가입 (4단계)
```
1. POST /api/auth/kakao
   body: { "code": "카카오_인가_코드" }
   → isNewUser=true, kakaoId + email + name + profileImageUrl 반환 (토큰 없음)
   → 프론트에서 회원가입 폼으로 이동, kakaoId는 다음 단계에서 그대로 사용

2. POST /api/auth/sms/send
   body: { "phone": "01012345678" }
   → SMS 인증코드 발송

3. POST /api/auth/sms/verify
   body: { "phone": "01012345678", "code": "123456" }
   → 인증 완료 (10분 유효)

4. POST /api/auth/kakao/register
   body: { "kakaoId": "...", "name": "...", "phone": "...", "role": "WARD|GUARDIAN", "profileImageUrl": "..." }
   → 회원가입 완료 + accessToken / refreshToken 발급
```

### 기존 사용자 로그인
```
POST /api/auth/kakao
→ isNewUser=false, accessToken + refreshToken 발급
```

## 일반 회원가입 플로우
```
1. POST /api/auth/email/check    → 이메일 중복 확인
2. POST /api/auth/sms/send       → SMS 인증코드 발송
3. POST /api/auth/sms/verify     → SMS 인증코드 확인 (10분 유효)
4. POST /api/auth/register       → 회원가입 완료
```

## 비밀번호 재설정 플로우

### 이메일 방식
```
1. POST /api/auth/password/reset-request   → 재설정 이메일 발송 (token 포함, 30분 유효)
2. POST /api/auth/password/reset           → token + 새 비밀번호로 변경
```

### SMS 방식
```
1. POST /api/auth/password/sms/send      → 인증코드 SMS 발송
2. POST /api/auth/password/sms/verify    → 인증코드 확인 → token 반환 (30분 유효)
3. POST /api/auth/password/reset         → token + 새 비밀번호로 변경
```

- 카카오 계정은 두 방식 모두 사용 불가
- 보안상 계정 미존재 시에도 200 반환 (가입 여부 노출 방지)

## 보안 정책

### 로그인 브루트포스 차단
- 동일 이메일로 5회 연속 실패 → 30분 잠금
- 잠금 중 시도 시 `LOGIN_LOCKED (429)` 반환
- 로그인 성공 시 실패 횟수 초기화

### ADMIN 계정 보호
- 관리자 API에서 ADMIN 역할 계정은 상태 변경/강제 삭제 불가
- 시도 시 `CANNOT_MODIFY_ADMIN (403)` 반환

### 전화번호 변경 시 SMS 인증 필수
- `PUT /api/users/me` 에서 phone 값이 현재와 다를 경우 `sms:verified:{phone}` 키 확인
- SMS 인증 미완료 시 `SMS_NOT_VERIFIED (400)` 반환
- 인증 완료 후 10분 이내에 변경 요청해야 함
