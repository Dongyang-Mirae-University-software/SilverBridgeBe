# 카카오 회원가입 access_logs FK 위반 버그 수정

- **작업 일자**: 2026-05-30
- **브랜치**: `fix/kakao-signup-access-log-fk`
- **유형**: 버그 수정 (트랜잭션 경계 + 예외 매핑)
- **선행 진단**: `docs/(2026-05-29) bug-investigation-kakao-signup-duplicate.md` (원인 C·E는 커밋 798fea2·9e762ab로 적용 완료). 본 문서는 그때 "프론트의 세션만료 오표기"로 분류했던 **진짜 409 "중복" 응답의 실제 발생원**을 운영 로그로 확정해 수정한다.

---

## 1. 근본 원인

### 1-1. 1차 원인 — access_logs FK 위반 (SQLState 23503)

`KakaoAuthService.kakaoRegister()`는 `@Transactional`(외부 TX)이며, 그 안에서 접속로그를 다음 순서로 기록했다.

```java
userRepository.save(user);                                   // (A) 외부 TX — 아직 COMMIT 안 됨
...
accessLogService.log(user.getId(), KAKAO_LOGIN, ip, ua);     // (B) REQUIRES_NEW — 별도 TX/커넥션
```

`AccessLogService.log()`는 `@Transactional(propagation = REQUIRES_NEW)`다. (B)는 외부 트랜잭션을 **일시 중단(suspend)** 하고 **새 트랜잭션(다른 DB 커넥션)** 을 열어 `INSERT INTO access_logs(user_id=…)`를 실행한다. 그런데 (A)의 `users` 행은 외부 트랜잭션에서 **아직 커밋되지 않았다**. READ COMMITTED 격리에서 다른 커넥션은 미커밋 행을 볼 수 없으므로, FK 제약 `fk_access_logs_user`가 위반된다.

→ **순서 문제가 아니라 트랜잭션 격리 문제.** (B)를 (A) 뒤로 옮기거나 flush해도, REQUIRES_NEW는 별도 커넥션이라 미커밋 user를 볼 수 없어 해결되지 않는다.

### 1-2. 2차 원인 — DataIntegrityViolationException → "중복" 오매핑

FK 위반은 Spring `DataIntegrityViolationException`으로 래핑되어 전파된다. `GlobalExceptionHandler`는 위반 종류를 구분하지 않고 **무조건 409 "이미 사용 중이거나 중복된 값입니다"** 로 변환했다. → 실제로는 중복이 아닌데 사용자/프론트에 "중복"으로 오표시. (unique 위반과 FK 위반이 같은 메시지로 뭉개져 진단도 어려웠다.)

### 1-3. 부차 증상 — SMS nonce 소비

`smsService.consumeVerification()`은 Redis 키를 즉시 삭제(트랜잭션 롤백 대상 아님)하며 save·access_log보다 먼저 실행된다. FK 위반으로 외부 TX가 롤백되면 user 행은 사라지지만 nonce는 이미 소비돼, 재시도 시 `SMS_NOT_VERIFIED`("전화번호 인증을 먼저 완료해주세요")가 발생한다. **본 수정으로 정상 흐름의 FK 롤백이 제거되어 nonce 증발도 함께 사라진다.**

---

## 2. 운영 로그 증거

```
POST /api/auth/signup/kakao | KakaoAuthController.kakaoRegister()
SQLState: 23503 (FK 위반)
ERROR: insert or update on table "access_logs"
  violates foreign key constraint "fk_access_logs_user"
Detail: Key (user_id)=(7LFwHd) is not present in table "users".
```

- `user_id=7LFwHd`는 "전혀 없는" 게 아니라 **외부 트랜잭션에서 미커밋이라 별도 트랜잭션에 안 보이는** 상태.
- DB 실측상 email/phone/kakaoId 중복 0건(선행 진단) → 진짜 중복이 아님이 교차 확인됨.

---

## 3. 일반 가입(LOCAL)과의 차이 — 왜 LOCAL은 멀쩡한가

| 구분 | 가입 시 접속로그 | 결과 |
|------|------------------|------|
| **LOCAL** `AuthService.register()` | **기록 안 함** (user save만). 접속로그는 `login()` 시점에 기록 — 그땐 user가 이미 커밋됨 | FK 문제 없음 |
| **KAKAO** `kakaoRegister()` | 가입=자동로그인이라 **가입 트랜잭션 안에서 `KAKAO_LOGIN` 로그를 REQUIRES_NEW로 기록** | 미커밋 user 참조 → FK 위반 |

→ 카카오 가입만 유일하게 "가입 트랜잭션 내부에서 미커밋 user를 참조하는 접속로그"를 남겨 이 버그가 발생.

---

## 4. 수정 내용

### 방향 C (주) — 접속로그를 AFTER_COMMIT으로 이동

기존 `UserWithdrawnEvent`·`ConnectionRefusedEvent`의 `@TransactionalEventListener(AFTER_COMMIT)` 관례를 그대로 따른다.

| 파일 | 변경 |
|------|------|
| `domain/auth/event/KakaoRegisteredEvent.java` (신규) | 가입 완료 이벤트 레코드 `(userId, ipAddress, userAgent)` |
| `domain/auth/listener/KakaoRegisterEventListener.java` (신규) | `AFTER_COMMIT` + `REQUIRES_NEW`에서 `accessLogService.log(KAKAO_LOGIN)` 수행 — user 커밋 후이므로 FK 보장 |
| `domain/auth/service/KakaoAuthService.java` | `accessLogService.log(...)` 직접 호출 제거 → `eventPublisher.publishEvent(new KakaoRegisteredEvent(...))`로 교체. `ApplicationEventPublisher` 주입 |

- 가입 롤백 시 이벤트 미발화 → 실패한 가입의 로그도 남지 않음(정상).
- **공유 `AccessLogService.log()`는 미변경** → login/logout/refresh 등 다른 경로 무영향.

### 방향 D (병행) — 예외 매핑 정밀화

| 파일 | 변경 |
|------|------|
| `global/exception/GlobalExceptionHandler.java` | `DataIntegrityViolationException`에서 SQLState 추출. **23505(unique)만 409 "중복"**, 그 외(23503 FK·23502 NOT NULL·23514 CHECK 등)는 실제 원인을 ERROR 로그로 남기고 500 일반 오류로 응답 |

- unique 위반의 기존 동작(409 "중복")은 유지 → 다른 경로 안전.
- PII 주의: unique 위반은 메시지 본문에 중복 값(이메일/전화번호)이 포함될 수 있어 **SQLState만 로깅**. FK 등 비-unique는 진단을 위해 `getMostSpecificCause().getMessage()`(내부 식별자·제약명 위주)를 ERROR로 기록.

### nonce 소비 (작업 3) — 의도적 미변경

1회용 보안 의도 유지. FK 위반(정상 흐름의 롤백 원인)이 제거되어 stranding이 사라지므로 소비 시점은 그대로 둔다(검증 후 마지막 소비, 커밋 798fea2). 진짜 unique 중복으로 롤백되는 경우의 nonce 소비는 "재시도해도 중복이라 무의미"하므로 허용 가능.

---

## 5. 테스트 결과

| 테스트 | 내용 |
|--------|------|
| `KakaoAuthServiceTest#kakaoRegister_성공_토큰발급` | 이벤트 발행 검증 + **회귀 가드**: 가입 트랜잭션 안에서 `accessLogService.log()` 직접 호출 안 함 |
| `KakaoRegisterEventListenerTest` (신규) | `KakaoRegisteredEvent` 수신 시 `KAKAO_LOGIN` 접속로그 위임 |
| `GlobalExceptionHandlerTest` (신규) | 23505 → 409 "중복" / 23503(FK) → 500, "중복" 미포함 |
| 기존 가입 실패 케이스(세션만료·중복·ADMIN) | nonce 미소비·user 미저장 회귀 가드 유지 |

- `./gradlew build --no-daemon` (테스트 포함) **BUILD SUCCESSFUL (EXIT 0)**

---

## 6. DB 영향

없음. 상태 전이·스키마 변경 없이 접속로그 기록 시점만 트랜잭션 경계 밖으로 이동. 마이그레이션 불요.