# 카카오 회원가입 복합 버그 정밀 진단 — 중복 에러 + SMS 인증 소비

> **발견 일자**: 2026-05-29
> **상태**: 진단 완료 / 수정 미적용 (수정은 다음 단계)
> **빌드 검증**: `./gradlew build -x test --no-daemon` → EXIT 0 (현재 dev 기준)
> **진단 대상 커밋**: `1e4f060`(dev HEAD). 직접 연관 변경 — `0faad8e` 탈퇴 hard delete(2026-05-26), `28fd164` SMS nonce 결합 H-5, `5adff48` 카카오 client_secret(2026-05-25)

---

## 1. 재현 시나리오 (사용자 보고)

| 단계 | 동작 | 결과 |
|------|------|------|
| 1 | `skarndaudwls@naver.com` 으로 일반(LOCAL) 회원가입 | 성공 |
| 2 | 해당 계정 회원 탈퇴 | 성공(표면상) |
| 3 | 같은 카카오 계정으로 카카오 회원가입 시도 | 신규 가입 진입 |
| 4 | 카카오 4단계 흐름 — 핸드폰 인증 완료 | **SMS 인증 성공** |
| 5 | "가입 완료" 클릭 | ❌ **"입력값이 중복됐습니다"** |
| 6 | 다시 전화번호 인증 → "가입 완료" | ❌ **"전화번호 인증을 먼저 완료해주세요"** (인증 초기화) |

> "입력값이 중복됐습니다" 문자열은 **백엔드 소스에 존재하지 않음**(grep 0건). 백엔드 메시지는 `이미 사용 중인 이메일입니다.`/`이미 사용 중인 전화번호입니다.`(409). → **프론트가 409(CONFLICT)를 자체 문구로 매핑**한 것으로 판단. 단계 6의 "전화번호 인증을 먼저 완료해주세요"는 백엔드 `ErrorCode.SMS_NOT_VERIFIED`(400)와 **문자열 정확히 일치**.

---

## 2. PHASE 0 — 관련 코드 파악 결과

### 2.1 회원 탈퇴 실제 동작 — **Hard Delete (확정)**

`UserController.withdraw` (`user/controller/UserController.java:210`) 는 **2단계**로 동작:

```
withdraw(...)            // 1단계: 본인확인 + user.deactivate()(INACTIVE) + UserWithdrawnEvent 발행 → tx1 커밋
  └ (AFTER_COMMIT 리스너 3종 동기 실행)
purgeWithdrawnUser(...)  // 2단계: userRepository.delete(user) → tx2 (행 영구 삭제)
```

- 1단계 `withdraw()`(`UserService.java:159`): `deactivate()`로 INACTIVE 전환 후 `UserWithdrawnEvent` 발행.
- 2단계 `purgeWithdrawnUser()`(`UserService.java:184`): `userRepository.delete(user)` — **행 자체를 삭제**.
- **AFTER_COMMIT 리스너 (모두 1단계 커밋 직후 동기 실행, `@Async` 아님 — 1건 제외)**:
  - `UserAccountEventListener.handleWithdrawn` (auth) — refresh token 삭제 + access token 무효화 키 + WITHDRAW 접속로그. `REQUIRES_NEW`.
  - `UserWithdrawalConnectionListener.handleWithdrawn` (connection) — `tearDownConnectionsOnWithdrawal`. 동기.
  - `UserWithdrawalFcmListener.handleWithdrawn` (notification) — FCM 토큰 정리. 동기.
  - (`ConnectionNotificationListener` 만 `@Async` — 상대방 알림 발송용, 탈퇴 핵심 경로 아님)

→ **정상 동작 시 행이 완전히 삭제되어 email/phone 모두 해제됨.** (의심 1 "soft delete 잔존"은 코드상 **기각** — 단, 2.4의 취약 지점 참조)

### 2.2 email/phone unique 제약 (확정)

- `User` 엔티티(`User.java:25`): `email` `@Column(nullable=false, unique=true)`.
- 마이그레이션: `V1__init.sql:33` `CONSTRAINT uq_users_email UNIQUE (email)`, `V2__db_improvements.sql:9` `CREATE UNIQUE INDEX uq_users_phone ON users(phone) WHERE phone IS NOT NULL` (partial unique).
- → email은 전역 unique, phone은 NOT NULL인 행끼리 unique.

### 2.3 카카오 가입 완료 중복 체크 — **status 조건 없음 (확정)**

`UserRepository` (`user/repository/UserRepository.java:22,25`):

```java
boolean existsByEmail(String email);   // status 조건 없음
boolean existsByPhone(String phone);   // status 조건 없음
```

→ **두 메서드 모두 status를 보지 않음.** 현재는 hard delete로 행이 사라지므로 무해하지만, **INACTIVE 잔존 행이 한 건이라도 있으면 영구히 재가입을 막는다**(2.4 참조).

### 2.4 ⚠️ 일반 가입 vs 카카오 가입 — nonce 소비 순서 **비대칭 (핵심 결함)**

| | LOCAL `AuthService.register` | KAKAO `KakaoAuthService.kakaoRegister` |
|---|---|---|
| 1 | `existsByEmail` 검사 (L68) | **`consumeVerification` — nonce 소비 (L114)** |
| 2 | `existsByPhone` 검사 (L73) | 카카오 세션 확인 (L118) |
| 3 | ADMIN 역할 차단 (L78) | ADMIN 역할 차단 (L124) |
| 4 | **`consumeVerification` — nonce 소비 (L83)** | `existsByEmail` 검사 (L129) |
| 5 | `save` (L101) | `existsByPhone` 검사 (L134) |
| 6 | — | `save` (L157) |

- **LOCAL**: 모든 검증을 **통과한 뒤 맨 마지막에** nonce를 소비 → 중복 실패 시 nonce **보존** → 재시도 가능.
- **KAKAO**: **맨 먼저** nonce를 소비 → 그 뒤 중복/세션/저장 단계 중 **어디서든 실패하면 nonce는 이미 소비됨**.

### 2.5 SMS 인증 키 생명주기 (확정)

| Redis 키 | 생성 시점 | 소비/삭제 시점 | TTL |
|---|---|---|---|
| `sms:verify:{phone}` (코드) | `sendVerificationCode`→`sendCode`(SmsVerificationService.java:66) | `verifyCode` 성공 시 (검증기 내부) | 5분 |
| `sms:verified:{phone}` (nonce) | `verifyCode` 성공 시(SmsService.java:63) | **`consumeVerification` 성공 시 즉시 `delete`(SmsService.java:84)** | 10분 |
| `kakao:pending:{kakaoId}` | `kakaoLogin` 신규분기(KakaoAuthService.java:100) | `kakaoRegister` 성공 시 `delete`(L160) | 10분 |

- `consumeVerification`(`SmsService.java:76`)은 **"검증 + 삭제"가 한 메서드에 결합**되어 있음. 성공 시 무조건 `redisTemplate.delete(sms:verified:{phone})` 실행.
- **Redis 작업은 `@Transactional`(JPA/DB) 트랜잭션에 포함되지 않음** → DB 롤백돼도 Redis 삭제는 **되돌려지지 않음**.

### 2.6 가입 실패 발생 코드 라인 + 트랜잭션 경계

- 단계 5 중복 실패 후보 라인: `KakaoAuthService.java:130`(EMAIL_ALREADY_EXISTS) 또는 `:135`(PHONE_ALREADY_EXISTS). 둘 다 **409 CONFLICT** → 프론트 "입력값이 중복됐습니다".
- `kakaoRegister`는 `@Transactional`(L109). 위 throw 시 **DB는 롤백**되지만, 그 이전 L114에서 실행된 **Redis nonce 삭제는 롤백 대상이 아님**.

---

## 3. PHASE 1 — 근본 원인 분석 (의심 A~D 검증)

### A. 탈퇴 데이터 잔존 — **기각 (PHASE 2 DB로 확정)**
- 코드상 탈퇴는 **hard delete**(2.1). **PHASE 2 실측: 해당 email/phone 행 0건**(4.3) → hard delete 정상 완료. 의심 1 "soft delete 잔존" **완전 기각**.
- (조건부 잔존 경로 — purge 미수행 가설 — 도 이번 데이터로는 발현되지 않음. 단, `purgeWithdrawnUser`가 `withdraw`와 별개 트랜잭션이고 1단계 동기 AFTER_COMMIT 리스너 예외 시 INACTIVE 잔존 **가능성**은 구조적으로 남아 있어, 방어적 보강 대상으로만 기록.)

### B. 중복 체크 로직 결함 — **단계 5 원인으로는 기각 / status 필터 부재는 latent로 잔존**
- **PHASE 2 DB 0행(4.3)으로, 단계 5의 "중복"은 DB 레벨의 진짜 중복이 아님이 확정.** 충돌할 행이 없으므로 `existsByEmail`/`existsByPhone`는 단계 5에서도 false. → **단계 5 실패는 중복 검사가 아닌 다른 분기**(1순위 E `KAKAO_SESSION_EXPIRED`, 4.3·4.4)에서 발생했고, 프론트가 이를 "입력값이 중복됐습니다"로 오표기한 것으로 판단. **단계 5의 실제 응답(4.4)으로 최종 확정 필요.**
- `existsByEmail`/`existsByPhone`의 **status 필터 부재**(2.3)는 **이번 버그의 원인은 아님**. 다만 hard delete가 어떤 이유로 미완료될 경우 INACTIVE 1행이 재가입을 영구 차단하는 **잠재 결함(defense-in-depth 갭)** 으로만 남김.
- 일반 vs 카카오 중복 체크 쿼리 차이 없음(같은 메서드). **실제 차이는 "nonce 소비 순서"(C)뿐.**

### C. SMS 인증 상태 소비 버그 — **확정 (root cause, 카카오 한정)**
- `kakaoRegister`는 **중복 검사보다 먼저** `consumeVerification`을 호출(2.4)하여 `sms:verified:{phone}` nonce를 **즉시 삭제**(2.5).
- 이후 중복 검사(L130/L135)에서 throw → `@Transactional` 롤백되지만 **Redis 삭제는 비가역**(2.6).
- → **가입 실패 시 nonce가 소비되어, 동일 nonce로 재시도하면 `SMS_NOT_VERIFIED`("전화번호 인증을 먼저 완료해주세요")**. 단계 6 증상과 **정확히 일치**.
- LOCAL 가입은 검증을 모두 통과한 뒤 소비하므로 동일 버그 없음 → **카카오 가입 경로 한정 회귀**.
- "인증 검증과 인증 삭제가 합쳐졌는가?" → **그렇다.** `consumeVerification`이 검증+삭제를 한 메서드에 결합, 그리고 그 호출이 **중복 검사 앞**에 위치한 것이 결합 결함.

### E. 카카오 세션 "너무 빨리 만료" — **access token(30분) 아님, `kakao:pending` 10분 (확정)**

사용자 보고: "가입 실패하면 카카오 세션이 만료됐다는데, access token은 30분인데 너무 빨리 만료된다." → **혼동이며, 실제 게이팅 키가 다름.**

- **두 값은 완전히 별개**:
  - **access token = 30분**(`application.yaml:67` `access-token-expiration: 1800000`) — **로그인 후 발급되는 인증 토큰**. 카카오 *가입* 흐름과 무관(가입 완료 시점에야 발급됨, `KakaoAuthService.java:163`).
  - **`kakao:pending:{kakaoId}` = 10분**(`KakaoAuthService.java:48` `KAKAO_PENDING_TTL = 10L` MINUTES) — **단계 3(`kakaoLogin`)에서 설정**되어 단계 5(`kakaoRegister`) 진입을 막는 **가입 세션 키**. 이게 "카카오 세션".
- **증상 원인**: 4단계 시니어/4050 가입 흐름(실명 입력 → 주소 검색 → 전화번호 입력 → SMS 발송·코드 수신·입력, SMS 코드 자체도 5분 TTL)은 **10분을 쉽게 초과**. 단계 3에서 타이머가 시작되므로, 단계 5 "가입 완료" 시점엔 `kakao:pending`이 이미 만료 → `KAKAO_SESSION_EXPIRED`(`KakaoAuthService.java:120`, 400). 사용자는 30분 기준으로 생각해 "너무 빠르다"고 느낌.
- **C와 결합(중요)**: `consumeVerification`(L114)이 **세션 확인(L118-120)보다 앞**에 있어, **세션 만료로 실패해도 nonce가 먼저 소비됨** → 단계 6 "전화번호 인증 먼저"로 또 이어짐. 즉 **E도 버그 C의 트리거**.
- **갱신 부재**: 단계 4 SMS 인증 성공이나 폼 진행으로 `kakao:pending` TTL을 **연장하는 로직 없음**(set은 단계 3 1회뿐). 10분 고정.

### D. 의심 포인트 간 연쇄 — **B → C 연쇄 (독립 아님)**

```
[단계 2] 탈퇴
   └─(가설 A: purge 미수행 시)─→ INACTIVE 행 잔존
                                      │
[단계 3~4] 카카오 가입 + SMS 인증 성공 (sms:verified nonce 발급)
                                      │
[단계 5] "가입 완료" → kakaoRegister
   ├ L114 consumeVerification ──→ sms:verified nonce 삭제 (Redis, 비가역)  ← 버그 C 발동 지점
   ├ L118 kakao:pending 확인 ─(E: 10분 초과 시)─→ KAKAO_SESSION_EXPIRED throw  ← C보다 뒤 = nonce 이미 소비됨
   ├ L130 existsByEmail / L135 existsByPhone
   │     └─(B: status 무시 + A의 잔존행)─→ 409 CONFLICT throw
   └ @Transactional 롤백 → DB는 원복, 그러나 nonce는 이미 삭제됨
                                      │
                                      ▼
[단계 6] 동일 nonce로 재시도 → consumeVerification 실패 → SMS_NOT_VERIFIED
        "전화번호 인증을 먼저 완료해주세요" (인증 초기화처럼 보임)
```

- **C는 B(또는 어떤 실패든)의 종속 증상**: 중복 외에도 `KAKAO_SESSION_EXPIRED`, ADMIN 차단, DB 제약 위반 등 **L114 이후 어떤 실패라도 nonce를 태운다**.
- **C는 단독으로도 버그**(순서 결함)이고, **B는 C의 트리거 중 하나**. 단계 5의 중복(B)이 없었다면 C도 발동하지 않았을 것.
- 단계 6에서 사용자가 "다시 전화번호 인증"을 했음에도 실패한다면, 프론트가 **재발급된 새 nonce를 register에 반영하지 못하고 소비된 옛 nonce를 재전송**했거나, 중복(B)이 잔존해 **재인증→재시도에서 nonce가 또 소비**되는 악순환 — 어느 쪽이든 근본은 C(소비 순서) + B(잔존 중복).

---

## 4. PHASE 2 — DB/Redis 상태 진단 가이드 (실행은 사용자)

> ⚠️ PII(이메일/전화번호)는 마스킹 권장. 아래 `:email`/`:phone` 자리표시자에 실제 값을 넣어 실행. 직접 조작 금지(SELECT/조회만).

### 4.1 users 테이블 — 잔존 행 / status / provider 확인

```sql
-- 해당 이메일로 행이 남아있는가? (hard delete 정상 시 0행이어야 정상)
SELECT id, provider, status, phone, created_at
FROM users
WHERE email = :email;          -- 예: 'skarndaudwls@naver.com'

-- 같은 전화번호로 잔존 행이 있는가? (단계 4가 성공했다면 0행이 정상)
SELECT id, provider, status, email, created_at
FROM users
WHERE phone = :phone;          -- 숫자만, 하이픈 없이

-- 카카오 계정의 providerId로 기존 가입 여부
SELECT id, status, email, phone
FROM users
WHERE provider = 'KAKAO' AND provider_id = :kakaoId;
```

**해석**:
- email 조회 결과 **1행 + status=INACTIVE** → 가설 A 적중(purge 미수행). hard delete가 1단계 리스너 예외로 중단됐을 가능성 → 애플리케이션 로그에서 `[WITHDRAW] 계정 영구 삭제 완료` 로그 **부재** 확인.
- email 조회 **0행**인데 단계 5에서 EMAIL 중복이 났다면 → 중복은 email이 아닌 다른 원인. phone 조회 결과 재확인.
- 단계 5의 409가 phone이었는지 email이었는지: 가입 실패 직후 **API 응답 본문의 message**(`이미 사용 중인 이메일입니다.` vs `이미 사용 중인 전화번호입니다.`)로 확정.

### 4.2 Redis 키 — nonce 소비 여부 확인

```bash
# 호스트에 redis-cli 미설치 → 컨테이너(dmu-dev-redis) 경유. 조회만(SET/DEL 금지).
# 현재 살아있는 가입 관련 키 일람 (dev 데이터셋 소규모라 KEYS 허용)
docker exec dmu-dev-redis redis-cli KEYS 'kakao:pending:*'
docker exec dmu-dev-redis redis-cli KEYS 'sms:verified:*'

# 전화번호 인증 nonce가 살아있는가? (단계 5 실패 후라면 '소비되어' 없을 것 — 버그 C 증거)
docker exec dmu-dev-redis redis-cli EXISTS "sms:verified:01030321634"
docker exec dmu-dev-redis redis-cli TTL    "sms:verified:01030321634"

# 카카오 세션(pending) 생존/잔여 TTL — E(10분 만료) 확인. <kakaoId>에 실제 값
docker exec dmu-dev-redis redis-cli EXISTS "kakao:pending:<kakaoId>"
docker exec dmu-dev-redis redis-cli TTL    "kakao:pending:<kakaoId>"

# 인증 코드 자체(verify)와 시도 카운터
docker exec dmu-dev-redis redis-cli EXISTS "sms:verify:01030321634"
docker exec dmu-dev-redis redis-cli GET    "sms:attempt:01030321634"
```

> 참고: redis가 `--maxmemory 256mb --maxmemory-policy allkeys-lru`(`docker-compose.dev.yml:85-88`)로 동작 → 메모리 압박 시 임의 키 퇴출 가능. dev 소규모에선 가능성 낮으나, 키가 TTL 전에 사라졌다면 LRU 퇴출도 후보(가능성 하).

**해석**:
- 단계 5 실패 직후 `sms:verified:{phone}` 가 **EXISTS 0** → **버그 C 실증**(검증 통과했는데 nonce가 사라짐 = 실패 경로에서 소비됨).
- `kakao:pending` 이 살아있고 `sms:verified` 만 사라졌다면 → 정확히 C 패턴.

---

## 4.3 PHASE 2 실측 결과 (2026-05-29)

### DB — **잔존 행 0건 (hard delete 완전 동작 확정)**

```
WHERE email = 'skarndaudwls@naver.com'  → (0 rows)
WHERE phone = '01030321634'             → (0 rows)
WHERE provider='KAKAO' AND provider_id=… → (0 rows)
```

→ **탈퇴 시 LOCAL 행이 email·phone 포함 완전히 삭제됨.** 잔존 INACTIVE 행 없음.

**이 결과의 함의 (진단 수정)**:
- **가설 A(purge 미수행·INACTIVE 잔존) 기각** — hard delete가 정상 완료됨.
- **원인 B(잔존행으로 인한 중복) 기각** — 충돌할 행 자체가 없으므로, 단계 5의 "입력값이 중복됐습니다"는 **DB 레벨의 진짜 중복이 아니다.** `existsByEmail`/`existsByPhone`는 단계 5 시점에도 false였을 수밖에 없음(이후 커밋된 행도 없음).
- → **단계 5 실패의 진짜 정체는 "중복"이 아니라 다른 에러를 프론트가 "입력값이 중복됐습니다"로 잘못 표기**했을 가능성이 큼. **1순위 후보: 원인 E `KAKAO_SESSION_EXPIRED`(400)** — 10분 pending 만료. (확정하려면 단계 5의 **실제 백엔드 응답 status+message** 필요 — 4.4 참조.)

### Redis 실측 (2026-05-29)

```
KEYS 'kakao:pending:*'         → (empty)
KEYS 'sms:verified:*'          → (empty)
EXISTS sms:verified:01030321634 → 0,  TTL → -2  (키 없음)
```

→ 현재 진행 중인 가입 세션·인증 nonce **모두 없음**(10분 TTL 자연 만료 후라 예상된 상태). 사후 스냅샷이라 단계 5 당시 상태를 역추적하진 못함. `docker logs --since 30m dmu-dev-api | grep …` 도 **0건**(컨테이너명 상이 또는 시간창 경과 추정).

### 단계 5 원인 — **소거법으로 E(`KAKAO_SESSION_EXPIRED`) 확정**

`kakaoRegister`에서 nonce 소비(L114) **이후** 실패할 수 있는 분기는 단 셋이며, 각각을 데이터로 제거:

1. `consumeVerification` 자체 실패(L114) → 메시지가 "전화번호 인증 먼저"여야 함. 단계 5 메시지는 "중복" → **아님**(단계 4에서 인증 성공, nonce 생존).
2. `existsByEmail`/`existsByPhone` 중복(L130/L135) → **DB 0행(4.3)으로 불가능.**
3. `KAKAO_SESSION_EXPIRED`(L120, pending 10분 만료) → **유일하게 남는 분기.**

→ **단계 5 = E.** 단계 3에서 시작된 `kakao:pending`(10분)이 시니어 4단계 가입 도중 만료. 프론트가 이 400을 "입력값이 중복됐습니다"로 **오표기**(별도 FE 정정 필요 — 백엔드 메시지는 "카카오 로그인 세션이 만료되었습니다…"). 그리고 nonce 소비(L114)가 세션 확인(L119)보다 앞이라 **이 실패가 nonce를 태워** 단계 6의 `SMS_NOT_VERIFIED`(C)로 연결.

> 100% 못박으려면: 카카오 로그인(단계 3) 후 **의도적으로 10분 이상 경과** 뒤 가입 완료 1회 → 응답 `400 "카카오 로그인 세션이 만료되었습니다…"` 확인(라이브 로그). 소거법 결론과 일치할 것.

## 4.4 ⚠️ 가장 결정적인 단일 증거 — 단계 5의 실제 백엔드 응답

프론트 문구("입력값이 중복됐습니다")는 **백엔드 문자열이 아니므로 신뢰 불가**. DB가 0행인 지금, 다음을 확보하면 단계 5 원인이 즉시 확정됨:

- **브라우저 Network 탭** 또는 **api 컨테이너 로그**에서 단계 5 `POST /api/auth/kakao/register` 의 **HTTP status + 응답 body message**:
  - `400` + `"카카오 로그인 세션이 만료되었습니다…"` → **원인 E 확정**(pending 10분 만료).
  - `409` + `"이미 사용 중인 이메일/전화번호입니다."` → 진짜 중복 — DB 0행과 모순되므로 **별도 동시성/재현 경로 재조사 필요**.
  - `400` + `"전화번호 인증을 먼저 완료해주세요"` → 이미 nonce 소비된 재시도(원인 C).

```bash
# api 컨테이너 로그에서 카카오 가입 관련 에러 추적 (시간대 맞춰)
docker logs --since 30m dmu-dev-api 2>&1 | grep -iE "kakao|register|SESSION_EXPIRED|ALREADY_EXISTS|SMS_NOT_VERIFIED"
```

## 5. PHASE 3 — 수정 방향 제안 (구현은 별도 단계)

### 5.1 원인 C (인증 소비 — 최우선, 카카오 한정 회귀)

**방향**: `kakaoRegister`에서 **모든 검증을 통과한 뒤 맨 마지막에** nonce를 소비하도록 순서 교정 — LOCAL `register`와 동일 패턴으로 정렬.

- 구체안 (택1, 구현 시 결정):
  - **(C-1) 순서 재배치**: `consumeVerification` 호출을 `existsByEmail`/`existsByPhone`/세션/ADMIN 검사 **뒤, save 직전**으로 이동. (최소 변경, LOCAL과 대칭) — **권장**.
  - **(C-2) 검증/소비 분리**: `consumeVerification`을 `peekVerification`(검증만) + `consume`(삭제만)으로 분리, 검증은 앞·삭제는 save 성공 후. (더 견고하나 변경 범위 큼, `updateProfile` 등 다른 호출처도 영향)
- **영향 범위**:
  - C-1: `KakaoAuthService.kakaoRegister` **단일 메서드** 내 라인 이동. SmsService·LOCAL 가입·프로필 수정 **무영향**. 가장 안전.
  - C-2: `SmsService` 시그니처/`PhoneVerificationPort` 변경 → LOCAL `register`, `UserService.updateProfile`도 동일 패턴 점검 필요(현재 LOCAL은 이미 순서가 옳아 동작엔 영향 없으나 API 변경 전파).
- **공통 주의**: Redis 삭제는 트랜잭션 밖이므로, save 성공 후 커밋 시점/예외 처리 정합을 함께 검토(이상적으로는 `@Transactional` 커밋 이후 소비 또는 save 직후 소비).

### 5.2 원인 B (중복 체크 status 무시) — **정책 결정 포인트**

단계 5 중복의 실데이터 원인(PHASE 2)에 따라 분기:

- **B가 "INACTIVE 잔존행"으로 확인된 경우** → 사실상 가설 A(purge 미수행)가 진짜 원인. 다음 중 정책 결정 필요:
  - **(정책 1) hard delete 신뢰성 보강** — `purgeWithdrawnUser`가 리스너 예외와 무관하게 반드시 실행되도록 보장(예: 리스너 예외를 삼키거나, purge를 동일 트랜잭션 흐름/스케줄 보정으로). 현 아키텍처(2단계 분리)의 예외 전파 경로 재검토. **재가입 허용 정책(2026-05-26)의 실효성 확보** 관점에서 정합적.
  - **(정책 2) `existsByEmail`/`existsByPhone`에 status 조건 추가** — `status != INACTIVE`(또는 ACTIVE만) 인 행만 중복 판정. 단, **INACTIVE 잔존행이 unique 인덱스 자체를 점유**하므로 exists를 통과시켜도 **DB INSERT 시 unique 위반 500**이 남음 → 이 정책은 **단독으로 불충분**, 정책 1과 병행해야 함.
  - **(정책 3) 탈퇴 시 email/phone 익명화** — soft delete를 유지하되 unique 컬럼을 토큰화. 현재의 hard delete 방향(2026-05-26 결정)과 상충하므로 **비권장**(되돌리기).
  - → **권장: 정책 1(hard delete 신뢰성 보강)**. 재가입 허용이라는 기존 정책 의도와 일치하고 unique 인덱스 점유 문제도 근본 해소.
- **B가 "email 진짜 충돌"(잔존행 없음)으로 확인된 경우** → 카카오 이메일이 다른 활성 계정과 충돌. 이는 정상 동작(다른 사람이 그 이메일 사용 중)일 수 있으므로 **버그가 아닐 수 있음** — UX 메시지 개선(어떤 값이 중복인지 명시) 정도로 분류.

### 5.4 원인 E (`kakao:pending` 10분 TTL 부족) — **정책 + 구현**

- **방향 (택1 또는 병행)**:
  - **(E-1) TTL 상향** — `KAKAO_PENDING_TTL`을 시니어 4단계 가입에 맞춰 상향(예: 30분). 가장 단순. 보안 영향 경미(pending 값은 검증된 카카오 이메일뿐, 토큰 아님). **권장 1순위**.
  - **(E-2) TTL 연장(슬라이딩)** — SMS 인증 성공 등 진행 단계에서 `kakao:pending` TTL을 재설정해 활성 사용자 세션 유지. 견고하나 추가 코드.
  - **(E-3) 만료 시 재로그인 유도 UX** — 현재 메시지("카카오 로그인을 다시 시도해주세요")는 정확하나, E-1과 병행해 빈도 자체를 낮추는 게 우선.
- **영향 범위**: `KakaoAuthService` 상수/흐름 한정. 다른 도메인 무영향.
- **C와의 관계**: C-1(소비 순서 교정)을 적용하면 **세션 만료로 실패해도 nonce는 보존**되므로, E로 인한 "재인증 강요"는 사라지고 "카카오 로그인만 다시"로 축소됨 → **C 수정이 E의 2차 피해(인증 초기화)를 제거**. E-1은 그 위에 만료 빈도 자체를 줄이는 보강.

### 5.3 일반 가입 영향 요약

| 수정 | LOCAL 가입 영향 | 비고 |
|---|---|---|
| C-1 (순서 재배치) | **없음** | 카카오 메서드 내부 한정 |
| C-2 (검증/소비 분리) | 점검 필요 | `register`·`updateProfile`가 같은 포트 사용 |
| B 정책 1 (purge 보강) | **없음** (탈퇴 경로) | 재가입 허용 실효성 ↑ |
| B 정책 2 (status 필터) | 가입 전반 | 단독 불충분(unique 위반 잔존) |

---

## 6. 다음 액션

1. **사용자**: PHASE 2의 SQL/redis-cli 실행 → 단계 5 직후 ① users 잔존행·status, ② `sms:verified` 소비 여부, ③ `kakao:pending` 생존/TTL(E 확인), ④ 단계 5 응답 message(이메일/전화번호/카카오 세션 만료 중 무엇인지) 회신.
2. 데이터 확인 후 **원인 B의 정체(잔존행 vs 진짜 충돌)** 와 **단계 5 실패가 B(중복)인지 E(세션 만료)인지** 확정.
3. **수정 우선순위**: **C-1(소비 순서 교정) 최우선** — 단독으로 "재시도 불가(인증 초기화)" 증상 제거(B·E 어느 트리거든 nonce 보존). 그다음 **E-1(pending TTL 상향)**, 이후 **B 정책 결정**(데이터 의존).
4. 수정은 별도 브랜치(`fix/kakao-register-...`) + PR. 본 진단은 코드 변경 0줄.

---

## 부록 — 근거 코드 위치

| 사실 | 위치 |
|------|------|
| 카카오 가입 nonce 선소비 | `KakaoAuthService.java:114` (검사 L130/L135보다 앞) |
| consumeVerification 검증+삭제 결합 | `SmsService.java:76-85` (L84 delete) |
| LOCAL 가입 올바른 순서(소비 마지막) | `AuthService.java:68-83` |
| 탈퇴 hard delete 2단계 | `UserController.java:213-222`, `UserService.java:159,184-196` |
| existsBy* status 조건 부재 | `UserRepository.java:22,25` |
| email/phone unique 제약 | `User.java:25`, `V1__init.sql:33`, `V2__db_improvements.sql:9` |
| 탈퇴 동기 AFTER_COMMIT 리스너 3종 | `UserAccountEventListener.java:36`, `UserWithdrawalConnectionListener.java:23`, `UserWithdrawalFcmListener.java:23` |
| SMS 키 정의 | `RedisKeys.java:13-14,30` |
| `kakao:pending` TTL 10분 | `KakaoAuthService.java:48,101` |
| access token 30분 (별개) | `application.yaml:67` |
| 세션 확인이 nonce 소비보다 뒤 | `KakaoAuthService.java:114`(소비) → `:118-120`(세션) |
| 중복 에러 메시지(409) | `ErrorCode.java:25-26`, SMS 미인증(400) `ErrorCode.java:43` |
