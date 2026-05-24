# 카카오 OAuth Client Secret 적용

- **작업 일자**: 2026-05-25
- **브랜치**: `feature/kakao-client-secret`
- **분류**: feat(auth) — 보안 강화
- **마이그레이션**: 없음 (DDL 불필요)

> ⚠️ 본 문서에는 실제 시크릿 값을 절대 포함하지 않는다. 환경변수명·placeholder만 사용한다.

---

## 1. 작업 배경 (보안 강화)

기존 카카오 OAuth는 인가코드 → 토큰 교환 시 **REST API Key(`client_id`)만** 전송했다.
이 경우 인가코드가 탈취되면 공격자가 동일 `client_id`로 토큰을 발급받을 수 있다.

카카오가 제공하는 **Client Secret**을 토큰 교환 요청에 함께 전송하면, secret을 모르는
제3자의 토큰 발급을 차단해 인가코드 탈취 공격 면을 줄일 수 있다.

Client Secret 값은 `.env.dev`(또는 운영 환경변수)로만 주입하며 **코드·Git에 평문으로 두지 않는다.**

---

## 2. PHASE 0 — 현행 파악 결과

| 항목 | 내용 |
|------|------|
| 토큰 교환 위치 | `KakaoOAuthClient.getToken()` (`domain/auth/oauth/KakaoOAuthClient.java`) |
| 기존 전송 파라미터 | `grant_type`, `client_id`(=REST API Key), `redirect_uri`, `code` — **client_secret 없음** |
| 시크릿 주입 방식 | `@Value` 직접 주입 (`@ConfigurationProperties` 미사용) |
| application.yaml | `kakao.rest-api-key`, `kakao.redirect-uri` (둘 다 기본값 없음) |
| 필수 환경변수 검증 | `RequiredPropertiesValidator` — 존재(blank) 여부만, 기존 10개 키 |
| 약한 값/길이 검증 | `SecurityConfigValidator` — JWT secret 전담 (길이·약한 값) |
| `getToken` 호출처 | `KakaoAuthService.java` 1곳 (시그니처 불변 → 영향 없음) |
| 기존 테스트 | `KakaoAuthServiceTest` (KakaoOAuthClient를 `@Mock`) — 회귀 없음 |

### 설계 결정 — 검증 책임 분리 유지

`RequiredPropertiesValidator`는 **존재 여부**만, `SecurityConfigValidator`는 **길이·약한 값**만
검증하도록 코드베이스가 이미 책임을 분리해 둠(주석에 명시). 본 작업도 이 컨벤션을 따라
`KAKAO_CLIENT_SECRET`의 존재 검증과 강도(길이·placeholder) 검증을 두 클래스에 나눠 배치했다.

---

## 3. PHASE 1 — 구현 내용

### 3-1. `application.yaml`

```yaml
kakao:
  rest-api-key: ${KAKAO_REST_API_KEY}
  client-secret: ${KAKAO_CLIENT_SECRET:}   # 빈 기본값 — 존재/강도 검증은 Validator가 담당
  redirect-uri: ${KAKAO_REDIRECT_URI}
```

- 빈 기본값(`:`)을 둔 이유: 환경변수 미설정 시 placeholder 해석 실패로 모호하게 죽는 대신,
  `RequiredPropertiesValidator`가 누락 키를 모아 친화적 메시지로 안내하게 하기 위함.

### 3-2. `KakaoOAuthClient` — 토큰 교환에 client_secret 추가

```java
@Value("${kakao.client-secret}")
private String clientSecret;

// getToken(...) 내부
params.add("grant_type", "authorization_code");
params.add("client_id", restApiKey);
params.add("client_secret", clientSecret);   // ← 추가
params.add("redirect_uri", redirectUri);
params.add("code", code);
```

- `getToken(code, redirectUri)` **시그니처 불변** — secret은 `@Value` 필드로 주입하므로 호출처·테스트 영향 없음.

### 3-3. `RequiredPropertiesValidator` — 존재 검증 추가 (10 → 11개)

```java
@Value("${kakao.client-secret:}") String kakaoClientSecret,
...
map.put("KAKAO_CLIENT_SECRET", kakaoClientSecret);
```

- 미설정 시 다른 누락 키와 함께 한 번에 `IllegalStateException`으로 시작 중단.

### 3-4. `SecurityConfigValidator` — 강도 검증 추가

```java
@Value("${kakao.client-secret:}") String kakaoClientSecret
...
private void validateKakaoClientSecret() {
    if (kakaoClientSecret == null || kakaoClientSecret.isBlank()) {
        return; // 존재 검증은 RequiredPropertiesValidator 담당
    }
    // 길이 < 32 → 시작 중단
    // 알려진 placeholder/약한 값(소문자 비교) → 시작 중단
}
```

- 카카오 Client Secret은 32자 → `MIN_KAKAO_CLIENT_SECRET_LENGTH = 32`.
- placeholder 집합 예: `secret`, `changeme`, `placeholder`, `your-kakao-client-secret-here` 등.

### 3-5. 테스트 — `SecurityConfigValidatorTest` (신규, 순수 단위)

- 정상(32자 이상) 통과 / blank 시 건너뜀 / 32자 미만 중단 / placeholder 중단 / 대소문자 무시.
- Spring 컨텍스트·MockMvc 미사용(프로젝트 단위 테스트 관행 준수).

### 3-6. 로깅 안전성

- `getToken`은 요청 파라미터를 로그에 찍지 않고 `status`+`errorCode`만 기록 → **client_secret 로그 노출 없음**.
- 예외 메시지에도 시크릿 값 미포함.
- `RequiredPropertiesValidator` fingerprint는 SHA-256 12자 prefix만 노출(원문 복원 불가).
- 결론: **추가 마스킹 불필요** — 현 구조가 이미 안전.

---

## 4. PHASE 2 — 사용자 직접 작업 안내

### 4-1. 카카오 개발자 콘솔 설정

1. https://developers.kakao.com → 내 애플리케이션 선택
2. **보안 → Client Secret**
3. **코드 생성**(또는 재발급) → 생성된 코드 복사
4. **"사용 함"으로 활성화** (⚠️ 활성화하지 않으면 secret을 보내도 무시됨)

### 4-2. `.env.dev`에 추가 (프로젝트 루트, Git 미추적)

```
KAKAO_CLIENT_SECRET=<카카오 콘솔에서 발급받은 값>
```

- 따옴표 없이, 공백 없이, `=` 양옆 공백 없이.
- `.gitignore`에 `.env.*` 포함되어 추적되지 않음(확인 완료).

### 4-3. 컨테이너 재시작

```bash
docker compose -f docker-compose.dev.yml restart api
# 또는
docker compose -f docker-compose.dev.yml down && docker compose -f docker-compose.dev.yml up -d
```

### 4-4. 검증 방법

- **정상 케이스**: 카카오 로그인 시도 → 토큰 발급·로그인 정상.
- **fail-fast 케이스**: `KAKAO_CLIENT_SECRET` 미설정/짧은 값으로 재시작 → 시작 로그에
  `RequiredPropertiesValidator`(누락) 또는 `SecurityConfigValidator`(길이/약한 값) 에러로 즉시 중단되는지 확인.
- 시작 로그: `필수 환경변수 검증 통과: 11개 키 정상 (fingerprint=...)`, `보안 설정 검증 통과: JWT secret + Kakao client secret 정상`.

---

## 5. 보안 점검 결과 (자가 검증)

| 점검 | 결과 |
|------|------|
| 코드/문서/산출물에 실제 시크릿 값 없음 | ✅ |
| `.gitignore`에 `.env.*` 포함 | ✅ |
| `application.yaml`에 평문 값 없음 (환경변수 참조만) | ✅ |
| 로그·예외 메시지에 시크릿 노출 없음 | ✅ |
| 빌드 + 전체 테스트 통과 | ✅ (`./gradlew build` EXIT 0) |

---

## 6. 변경 파일 요약

| 파일 | 변경 |
|------|------|
| `src/main/resources/application.yaml` | `kakao.client-secret` 매핑 추가 |
| `domain/auth/oauth/KakaoOAuthClient.java` | `client_secret` 필드 + 토큰 교환 파라미터 |
| `global/config/RequiredPropertiesValidator.java` | `KAKAO_CLIENT_SECRET` 존재 검증 |
| `global/config/SecurityConfigValidator.java` | `KAKAO_CLIENT_SECRET` 길이·약한 값 검증 |
| `src/test/.../config/SecurityConfigValidatorTest.java` | 신규 단위 테스트 |
| `docs/(2026-05-25) feature-kakao-client-secret.md` | 본 문서 |
| `docs/progress.md`, `CLAUDE.md`, `프로젝트_설명.txt` | 기록·환경변수·정책 메모 갱신 |
