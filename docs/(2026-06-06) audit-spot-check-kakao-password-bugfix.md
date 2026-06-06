# 스팟 점검 — 카카오 가입 / 비밀번호 재설정 버그 수정 검증

> **점검 일자**: 2026-06-06
> **점검자**: Claude Code (AI 보조, 인간 검토 전제)
> **성격**: 직전 머지된 3건의 버그 수정이 (1) 올바른지 + (2) 회귀가 없는지 검증하는 스팟 점검. 코드 변경 없음(점검만).
> **종합 판정**: ✅ **PASS** — 3건 모두 수정 정확, 회귀 없음, 발견된 Critical/High/Medium 이슈 없음.

---

## 0. 점검 대상 (수정 커밋)

| # | 버그 | 수정 커밋 | PR |
|---|------|----------|-----|
| 1 | 카카오 가입 `access_logs` FK 위반 + 예외 오매핑("중복"으로 뭉갬) | `9f4b283` | #185 |
| 2 | 카카오 가입 실패 시 SMS nonce 소비(1차 실패 후 재시도 불가) | `798fea2` | #184 |
| 3 | 비밀번호 재설정 인증코드 소비 순서(1차 실패 후 코드 소모) | PR #188 | #188 |

## 1. 점검한 파일 목록 (총 13)

**소스 (8)**
- `domain/auth/service/KakaoAuthService.java` — 카카오 로그인/가입
- `domain/auth/event/KakaoRegisteredEvent.java` — 가입 완료 이벤트
- `domain/auth/listener/KakaoRegisterEventListener.java` — AFTER_COMMIT 접속로그
- `global/exception/GlobalExceptionHandler.java` — `DataIntegrityViolationException` 처리
- `domain/auth/service/PasswordResetService.java` — 비번 재설정 confirm
- `global/util/VerificationCodeValidator.java` — verify/verifyWithoutConsume/consume
- `domain/auth/service/AuthService.java` — 일반 가입(회귀 대상)
- `domain/auth/service/SmsService.java` · `AccessLogService.java` · `KakaoAuthController.java` (소비/로그/IP 경로)

**테스트 (5)**
- `KakaoAuthServiceTest` · `KakaoRegisterEventListenerTest` · `GlobalExceptionHandlerTest` · `PasswordResetServiceTest` · `VerificationCodeValidatorTest`

## PHASE -1. 사전 환경
- 빌드: `./gradlew build -x test --no-daemon` → **BUILD SUCCESSFUL** (40s)
- 테스트: 관련 6개 클래스 `--tests` 실행 → **BUILD SUCCESSFUL** (34s)
- Git: `dev` 클린, 3건 수정 커밋 머지 확인.

---

## 2. 수정 정확성 검증 (PHASE A)

### ① 카카오 가입 access_log 순서 — **PASS**

| 항목 | 결과 | 근거 |
|------|------|------|
| A1. users가 access_log보다 먼저 커밋 | ✅ | `kakaoRegister`는 `userRepository.save()`(L167) 후 **`publishEvent(KakaoRegisteredEvent)`**(L185)만 호출. 로그는 `@TransactionalEventListener(AFTER_COMMIT)`에서 user 커밋 뒤 기록 → `fk_access_logs_user` 안전. |
| A2. 가입 실패 시 롤백 | ✅ | 실패 시 트랜잭션 롤백 → AFTER_COMMIT 미발화 → 실패한 가입의 로그도 안 남음(정상). |
| A3. 트랜잭션 경계 | ✅ | 리스너는 `REQUIRES_NEW` 독립 트랜잭션 — 로그 실패가 가입 응답에 영향 없음. `kakaoRegister` 내부에서 `accessLogService.log()` **직접 호출 없음**(불변 규칙 준수). |

### ② 예외 매핑 — **PASS**

| 항목 | 결과 | 근거 |
|------|------|------|
| A4. `DataIntegrityViolationException` 세분화 | ✅ | `extractSqlState()`로 원인 체인의 `SQLException` SQLState 추출 후 분기. |
| A5. FK ≠ unique 메시지 | ✅ | 23505(unique)만 409 "이미 사용 중이거나 중복된 값" / 그 외(FK 23503·NOT NULL·CHECK)는 500 일반 오류. |
| A6. 실제 원인 로깅 | ✅ | non-unique는 `log.error(... getMostSpecificCause().getMessage())`. unique는 PII 노출 방지 위해 SQLState만 WARN. |
| A7. 진짜 중복만 "중복" | ✅ | 23505에서만 "중복" 메시지. (테스트로 23505→409·중복포함 / 23503→500·중복불포함 고정) |

### ③ 인증 소비 — **PASS**

| 항목 | 결과 | 근거 |
|------|------|------|
| A8. 검증/소비 분리 | ✅ | `VerificationCodeValidator`가 `verify`(소비형)·`verifyWithoutConsume`(비소비)·`consume`(소비전용) 3분리. SMS nonce도 `verifyCode`(발급) ↔ `consumeVerification`(소비) 분리. |
| A9. 1차 실패 후 재시도 가능 | ✅ | 카카오: nonce 소비(`consumeVerification` L146)가 세션·역할·중복 검증 **뒤**. 비번: `confirmReset`이 맨 앞 `verifyWithoutConsume`(L196), `consume`은 모든 검증·변경 성공 후 **마지막**(L231). Redis 삭제는 `@Transactional` 롤백 비대상이므로 "마지막 소비"가 핵심. |
| A10. 성공 시에만 소비 | ✅ | 카카오/일반 가입 모두 비즈니스 검증 통과 후에만 `consumeVerification`. 비번은 비밀번호 변경·이벤트·로그 성공 후 `consume`. |

---

## 3. 회귀 검증 (PHASE B) — **PASS**

| 항목 | 결과 | 근거 |
|------|------|------|
| B1. 일반 가입 정상 | ✅ | `AuthService.register` 무변경(이번 수정은 카카오 흐름 한정). |
| B2. 일반 가입 access_log 순서 | ✅ | 일반 가입은 **가입 시 접속로그를 남기지 않음**(SIGNUP 액션 없음) → 애초에 FK 위반 소지 없음. 카카오만 KAKAO_LOGIN 기록(의도된 비대칭). |
| B3. 카카오 수정의 일반 가입 영향 | ✅ | 공유 컴포넌트(`SmsService.consumeVerification`, `VerificationCodeValidator`, `GlobalExceptionHandler`)는 시그니처·기존 동작 유지. 일반 가입도 "검증 후 소비" 동일 패턴. |
| B4. 정상 재설정 흐름 | ✅ | send→verify(미소비)→confirm(재검증+변경+소비) 6자리 코드 단일 흐름 유지. |
| B5. 양쪽 일관성 | ✅ | 가입(nonce)·재설정(6자리 코드) 모두 "검증 후 마지막 소비"로 정렬. 정책문서(`.claude/rules`)와 일치. |
| B6. 기존 카카오 로그인 | ✅ | `kakaoLogin` 무변경 — 기존 사용자는 트랜잭션 내 `accessLogService.log(KAKAO_LOGIN)` 인라인(커밋된 user라 FK 안전), 토큰 발급 정상. INACTIVE는 `revokeAll` 후 차단. |

---

## 4. 테스트 검증 (PHASE C) — **PASS**

수정마다 단위 테스트가 추가됐고, 누락 케이스 없음. 핵심 단언:

- **카카오 가입 정상**: `verify(eventPublisher).publishEvent(KakaoRegisteredEvent)` + `verify(accessLogService, never()).log(...)` — 인라인 로그 미호출(AFTER_COMMIT 위임) 고정.
- **가입 실패 롤백/nonce 보존**: 세션만료·이메일중복·ADMIN역할 각각 `userRepository.save() never` + `consumeVerification() never` — 1차 실패 시 nonce 보존.
- **FK ≠ 중복**: `GlobalExceptionHandlerTest` — 23505→409·"중복" 포함 / 23503→500·"중복" 불포함.
- **AFTER_COMMIT 로그**: `KakaoRegisterEventListenerTest` — 이벤트 수신 시 KAKAO_LOGIN 기록.
- **1차 실패 후 코드 재사용**: `confirmReset_현재와동일_SAME_AS_CURRENT_PASSWORD` (★회귀) + 카카오계정 분기 — `consume() never`. `VerificationCodeValidatorTest` — `verifyWithoutConsume`는 키 유지, `consume`은 삭제.
- **A-M1 유지**: 코드 무효 시 사용자 조회 안 함(enumeration 차단).

> 통합 테스트 부재는 의도적 제외(이슈 아님).

---

## 5. 발견 이슈

🔴 Critical / 🟠 High / 🟡 Medium — **없음.**

🟢 **Low (정보성, 수정 불요)**
- **L-1. nonce/코드 소비가 `userRepository.save()` 직전**: 가입 흐름에서 소비는 모든 *비즈니스 검증* 뒤지만 DB `save()` *앞*이다. `existsBy` 통과 후의 동시성 race로 `save()`가 unique 위반을 던지면 nonce는 이미 소비돼 롤백 안 됨(Redis). 다만 ⓐ race 윈도우가 매우 좁고 ⓑ 정책문서가 "검증 후 마지막 소비 = 비즈니스 검증 후"로 명시한 **의도된 설계**이며 ⓒ 일반/카카오 가입 양쪽 동일하게 적용됨 → 회귀 아님. 기록만.
- **L-2. AFTER_COMMIT 리스너 비동기 아님**: `KakaoRegisterEventListener`는 `@Async` 미부착이라 접속로그 insert가 커밋 후 동일 스레드에서 동기 실행(응답 직전). 감사 로그라 best-effort로 충분하고 `REQUIRES_NEW`라 실패해도 가입 응답 무영향. 지연 민감도 낮아 현행 유지 무방.

---

## 6. 종합 판정

| 구분 | 판정 |
|------|------|
| 수정 정확성 (A1~A10) | ✅ PASS |
| 회귀 (B1~B6) | ✅ PASS |
| 테스트 (C) | ✅ PASS |
| **빌드/테스트 실행** | ✅ EXIT 0 |
| **종합** | ✅ **PASS — 머지 상태 양호, 추가 조치 불요** |

3건의 수정은 모두 근본 원인(트랜잭션 경계·SQLState 미구분·검증/소비 미분리)을 정확히 겨냥했고, 회귀 없이 기존 흐름과 일관된다. Low 2건은 의도된 설계로 수정 대상 아님.
