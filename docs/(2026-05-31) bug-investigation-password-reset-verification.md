# 비밀번호 초기화 인증 재시도 버그 진단

- **발견 일자**: 2026-05-31
- **상태**: ✅ **수정 완료** (가설 A — 검증/소비 분리, "검증 후 마지막 소비"). 브랜치 `fix/password-reset-code-consume-order`
- **영향 엔드포인트**: `POST /api/auth/password/reset` (`PasswordResetService.confirmReset`)
- **빌드**: `./gradlew build --no-daemon` (테스트 포함) 통과 (exit 0)

> **수정 요약** (§6 가설 A 방향대로 적용):
> 1. `confirmReset` 맨 앞 `verify()`(검증+소비) → **`verifyWithoutConsume()`**(비소비)로 교체 — enumeration 차단(A-M1)은 유지.
> 2. 모든 검증·비밀번호 변경·로그 성공 후 **마지막에** `verificationCodeValidator.consume()`로 코드 소비.
> 3. `VerificationCodeValidator`에 `consume(verifyKey, attemptKey)` 헬퍼 신설.
> 4. 회귀 테스트: `SAME_AS_CURRENT_PASSWORD`/`SOCIAL_USER_NO_PASSWORD` 실패 시 `consume` **미호출**(코드 보존→재시도 가능), 정상 시 `consume` 호출 + `verify` 미사용. `verifyWithoutConsume`/`consume` 단위 테스트 추가.
> 5. `.claude/rules/domain-security-policy.md`에 "검증 후 마지막 소비" 불변 규칙 명문화.

---

## 1. 재현 시나리오

1. 비밀번호 초기화 진행 (인증코드 발송 → 사전 확인까지 정상)
2. `/reset`을 **올바른 식별자(email/phone)·올바른 6자리 코드**로 호출하되, **새 비밀번호가 현재 비밀번호와 동일**(또는 다른 다운스트림 검증 실패) → **1차 400 실패**
3. 같은 코드로 새 비밀번호만 바꿔 `/reset` **재호출(2차)**
4. **`EXPIRED_SMS_CODE`("인증번호가 만료되었습니다")** 반환 → 재시도 불가

> ⚠️ 사용자 체감: "방금 받은 인증번호인데 만료됐다고 나온다." 실제로는 **만료가 아니라 1차에서 코드가 소비(삭제)된 것** — 클라이언트는 둘을 구분할 수 없음(동일 `EXPIRED_SMS_CODE`).

---

## 2. PHASE 0 — `/reset` 전체 흐름

`PasswordResetService.confirmReset()` (src/.../auth/service/PasswordResetService.java:173-224) 실행 순서:

| # | 단계 | 실패 시 에러 | 코드 소비 시점 |
|---|------|-------------|---------------|
| 1 | `@Valid` (DTO: 6자리/비번 형식) | 400 (형식) | **소비 전** — 코드 안전 |
| 2 | email XOR phone 검증 (L179) | `INVALID_INPUT` | **소비 전** — 코드 안전 |
| 3 | **`verificationCodeValidator.verify(...)`** (L192) | `EXPIRED`/`INVALID`/`TOO_MANY` | ★ **여기서 코드 즉시 삭제(소비)** |
| 4 | 사용자 조회 (L201) | `USER_NOT_FOUND` | 소비 **후** |
| 5 | 카카오 계정 차단 (L207) | `SOCIAL_USER_NO_PASSWORD` | 소비 **후** |
| 6 | **현재 비밀번호와 동일 차단 (L212)** | `SAME_AS_CURRENT_PASSWORD` | 소비 **후** ★ |
| 7 | 비밀번호 변경 + 이벤트 + 로그 | — | — |

핵심: **3단계 `verify()`가 코드를 먼저 소비**하고, **4~6단계 비즈니스 검증이 그 뒤**에 온다.

`verify()`의 실제 동작 (`VerificationCodeValidator.verify`, L32-58):
- 성공 시 → `redisTemplate.delete(verifyKey)` + `delete(attemptKey)` (L56-57) — **코드 영구 삭제**
- `@Transactional`은 **DB만 롤백**한다. Redis `delete`는 트랜잭션 대상이 아니므로, 이후 4~6단계가 throw해 트랜잭션이 롤백돼도 **삭제된 코드는 복구되지 않는다.**

### 1차 "잘못된 정보"별 코드 상태 (보고 항목 5)

| 1차 잘못된 정보 | verify 결과 | 코드 소비? | 2차 재시도 |
|----------------|------------|-----------|-----------|
| **새 비밀번호 = 현재 비밀번호** (식별자·코드 정상) | 성공 | ✅ **삭제됨** | ❌ EXPIRED ← **본 버그** |
| 비밀번호 형식 오류(8자 미만 등) | (verify 도달 전 @Valid 차단) | ❌ 보존 | ✅ 정상 |
| 인증코드 1회 오입력 | 실패(attempt++) | ❌ 보존 | ✅ 정상 |
| 인증코드 5회 오입력 | 5회째 무효화 | ✅ 삭제됨 | ❌ EXPIRED (가설 B, 의도된 잠금) |
| 식별자(email/phone) 틀림 | 다른 키 조회→null→EXPIRED | ❌ 실코드 보존 | ✅ 정상 |

→ **단 1회 실패로 재시도가 막히는 경로는 "코드·식별자는 맞고 다운스트림 검증(주로 `SAME_AS_CURRENT_PASSWORD`)이 실패"한 경우뿐**이며, 이것이 재현 시나리오와 정확히 일치.

---

## 3. PHASE 1 — 근본 원인 확정

### ✅ 가설 A: 검증 시점 즉시 소비 — **확정 (PRIMARY)**

- `confirmReset`은 인증 **검증과 소비가 결합된** `verify()`를 **비즈니스 검증보다 먼저** 호출(L192).
- Redis 삭제는 `@Transactional` 롤백 대상이 아님 → 4~6단계 실패 시 **코드가 비가역적으로 소모**, 2차 재시도 불가.
- 근거: PasswordResetService.java:192 (verify 선행) + VerificationCodeValidator.java:56-57 (성공 시 delete) + L173 `@Transactional`.

### ⚠️ 가설 B: 시도 카운터/잠금 — **부분 사실 (SECONDARY, 의도된 동작)**

- 코드 5회 오입력 시 `attempts >= MAX_ATTEMPTS(5)`로 코드 즉시 무효화(VerificationCodeValidator.java:46-49) → 그 후 정답 입력해도 EXPIRED.
- 단 **5회 누적**이 필요 → "1차 1회 실패" 시나리오와는 불일치. 본 버그의 주원인은 아니나, 인접 UX 이슈로 인지.
- 오입력 카운터(`attempt`)와 다운스트림 검증 실패는 무관(다운스트림 실패는 카운터를 올리지 않음).

### ❌ 가설 C: 1차 호출이 코드를 재생성/무효화 — **기각**

- `/reset`은 `requestReset`(발송)을 호출하지 않음 → 코드 재생성 없음.
- 식별자 오기입은 **다른 Redis 키**를 조회할 뿐 실제 코드 키를 건드리지 않음.

### ❌ 가설 D: TTL/시간 만료 — **기각 (주원인 아님)**

- 코드 TTL 5분. 즉시 재호출하는 재현 시나리오에서 시간 만료는 발생하지 않음.
- 단, 에러 코드가 가설 A·B·D 모두 동일 `EXPIRED_SMS_CODE`라 **표면상 만료처럼 보이는 것**이 혼동의 핵심.

---

## 4. 카카오 가입 버그와의 연관성 — **같은 뿌리 (root cause class 동일)**

직전 카카오/일반 가입 버그도 **"인증 소비가 검증·처리보다 먼저 일어나 실패 시 재시도 불가"**였고, **이미 수정**되어 있음. 그 수정 주석이 본 버그를 그대로 설명한다:

```java
// KakaoAuthService.java:141-146
// SMS 인증 nonce 일치 확인 + 키 소비 (H-5)
// consumeVerification은 Redis 키를 즉시 삭제하는데 이 삭제는 @Transactional 롤백 대상이 아니므로,
// 검증보다 먼저 소비하면 검증 실패 시 nonce가 비가역적으로 소모돼 재시도가 막힌다.
// LOCAL AuthService.register와 동일하게 "검증 후 마지막 소비" 순서를 맞춘다.
smsService.consumeVerification(request.getPhone(), request.getVerificationNonce());
```

| 흐름 | 인증 매개체 | 소비 순서 | 상태 |
|------|-----------|----------|------|
| 일반 가입 `AuthService.register` | nonce (`consumeVerification`) | 비즈니스 검증 **후** 마지막 소비 | ✅ 안전 |
| 카카오 가입 `KakaoAuthService` | nonce | 비즈니스 검증 **후** 마지막 소비 | ✅ 안전(수정 완료) |
| **비번재설정 `confirmReset`** | 6자리 코드 (`verify`) | **검증을 맨 먼저 소비** | ❌ **버그 (미수정)** |

- **같은 원리**: 인증 "검증"과 "소비"의 미분리 + Redis 삭제가 트랜잭션 롤백 비대상.
- **다른 점**: 가입은 nonce 간접 토큰(`SmsService.consumeVerification`)을, 비번재설정은 6자리 코드를 직접(`VerificationCodeValidator.verify`) 사용 → **단일 공유 라인 한 줄 수정으론 양쪽이 동시에 고쳐지지 않음.** 가입은 이미 올바른 순서로 고쳐졌고, 비번재설정만 같은 원칙을 적용하면 됨.
- **공유 컴포넌트**: `VerificationCodeValidator`는 가입/비번재설정이 공유하며, 이미 **소비형 `verify()`** 와 **비소비형 `verifyWithoutConsume()`** 를 둘 다 제공한다(L67-85). 즉 분리 수단은 이미 존재 → 호출 순서만 바꾸면 됨(컴포넌트 시그니처 변경 불필요).

---

## 5. PHASE 2 — 운영 로그/Redis 진단 가이드 (실행은 사용자)

> 코드 근거로 원인이 확정되었으므로 아래는 **현장 재확인용**(선택). 조회 전용 · PII 마스킹 유지.

**(1) `/reset` 1차+2차 직후 로그**
```bash
docker compose -f docker-compose.dev.yml logs api --since 3m | grep -iE "PW-RESET|비밀번호 재설정|EXPIRED_SMS|SAME_AS_CURRENT"
```
- 기대: 1차에 `SAME_AS_CURRENT_PASSWORD`(400) → 2차에 `EXPIRED_SMS_CODE`(400). 발송 로그("재설정 ... 발송 완료")는 1·2차 사이에 없어야 함(재발송 안 했으므로).

**(2) 인증 상태 키워드 추적**
```bash
docker compose -f docker-compose.dev.yml logs api --since 3m | grep -iE "verify|만료|인증번호"
```

**(3) Redis 키 소멸 확인 (조회만, 변경 금지)** — phone 방식 예시, 실제 번호는 마스킹해 기록
```bash
# 1차 호출 "직전": 코드 키 존재 확인
redis-cli --scan --pattern 'password:sms:verify:*'
# 1차(SAME_AS_CURRENT 실패) "직후": 위 키가 사라졌는지 확인  ← 사라지면 가설 A 입증
redis-cli --scan --pattern 'password:sms:verify:*'
```
- 이메일 방식 키: `password:email:verify:*` / 오류횟수: `password:sms:attempt:*`, `password:email:attempt:*`
- ⚠️ `KEYS`/`--scan` 외 `GET`으로 코드값 출력 시 PII이므로 로그·문서에 남기지 말 것.

---

## 6. PHASE 3 — 수정 방향 (구현 별도 · 본 문서는 제안만)

### 가설 A 수정 (권장): "검증 후 마지막 소비" 순서로 정렬 — 가입 흐름과 동일 원칙

`confirmReset`에서:
1. 맨 앞 `verify()`(소비형) → **`verifyWithoutConsume()`(비소비형)** 으로 교체
   - enumeration 차단(A-M1) **유지**: 미발급 식별자는 여전히 `EXPIRED`로 먼저 막혀 가입 여부/provider 미노출.
   - 오입력 카운터·5회 무효화 동작도 동일 유지.
2. 사용자 조회 → 카카오 차단 → 현재 비번 동일 차단 → 비밀번호 변경까지 **모두 통과한 뒤**
3. **마지막에 코드 소비**(verifyKey/attemptKey 삭제). `VerificationCodeValidator`에 `consume(verifyKey, attemptKey)` 헬퍼 추가가 깔끔(아니면 `requestReset`이 이미 쓰는 `redisTemplate.delete` 인라인).

**효과**: `SAME_AS_CURRENT_PASSWORD` 등 다운스트림 실패 시 코드가 보존 → 같은 코드로 즉시 재시도 가능. 최종 성공 시에만 1회용 소비.

**잔여 노출 평가**: 다운스트림 실패 시 코드가 자연 TTL(5분)·5회 한도까지 유효하게 남음 — 이는 발송~소비 사이 정상 유효창과 동일 수준으로 수용 가능. replay는 성공 시 즉시 삭제로 차단.

### 가설 B 관련 (선택): 다운스트림 실패와 코드 오입력 구분
- 이미 구조상 구분됨(다운스트림 실패는 attempt 카운터 미증가). 추가 조치 불필요. 5회 잠금은 의도된 정책으로 유지.

### 영향 범위
- **변경 파일**: `PasswordResetService.confirmReset` 1곳. (+ 선택적으로 `VerificationCodeValidator`에 `consume()` 헬퍼 추가)
- **가입(LOCAL/KAKAO)**: 영향 없음 — 이미 올바른 순서.
- **`/find-password/*/verify`**: 영향 없음 — 이미 `verifyWithoutConsume` 사용.
- **DB 마이그레이션**: 불필요.

### 통합 수정 가능 여부
- **단일 공유 한 줄로 양쪽 동시 수정은 불가**(가입=nonce 경로, 비번재설정=코드 경로로 분기). 가입은 이미 수정 완료 → **비번재설정에만 동일 "검증 후 마지막 소비" 원칙 적용**이 정확한 통합점.
- 재발 방지: 회귀 테스트(`SAME_AS_CURRENT_PASSWORD` 실패 후 동일 코드 재시도 성공) 추가 + `domain-security-policy.md`에 **"인증 소비는 모든 비즈니스 검증·처리 성공 후 마지막에"** 불변 규칙 명문화 권장.

---

## 7. 다음 액션

1. (사용자 결정) 가설 A 수정 적용 여부 — `confirmReset` 순서 재정렬 + (선택) `consume()` 헬퍼.
2. 회귀 테스트 추가: 동일 코드로 `SAME_AS_CURRENT_PASSWORD` 1차 실패 → 2차 정상 변경.
3. `domain-security-policy.md`에 "검증 후 마지막 소비" 불변 규칙 추가.
4. (선택) 운영 로그/Redis로 §5 현장 재확인.
