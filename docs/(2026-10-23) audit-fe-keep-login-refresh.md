# (2026-10-23) FE 로그인 유지(재방문 자동 갱신·체크박스) 기능 점검

- 대상: FE PR #105 `fix/keep-login-refresh` (커밋 `07aec2d`, 머지 `1c19cd0`) + 바꾼 공용 코드의 사용처
- 템플릿: B(기능 점검), 점검만(코드 수정 없음). 토큰·동시성 판단은 Opus
- 기준: FE origin/develop `1c19cd0` (detached worktree). gosky FE는 점검 시점 `3235b49`(#106까지, `1c19cd0` 포함)

## 결론

**조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 5

세션 정리 조건(401·403·404만), 탭 간 갱신 직렬화, "로그인 유지" 쿠키 수명은 의도대로 동작하고 테스트로 고정돼 있다. 남은 것은 다음과 같다.

- 🟡 1건(M-1): 갱신이 일시 오류로 실패하면 실시간 알림 WS가 조용히 멈춘다. 이 PR로 새로 드러난 회귀다.
- 🟢 5건: 시간 초과 시 BE 재사용 감지 오탐, 테스트 공백, 복원 중 빈 화면, JWT 해석 중복, body-compat 전환 전제.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-10-23 |
| develop에 `1c19cd0` 포함 | 예. 이후 대상 파일을 바꾼 커밋 없음 |
| `npm test` | 236 통과 |
| `npm run test:browser` | 92 통과 |
| `tsc --noEmit` / `npm run build` | 통과 / 통과 |

## PHASE 0. 대상

**변경 파일 17개**: `page.tsx`, `RoleRouteGuard.tsx`, `components/auth/SessionRestore·SessionRetryNotice`, `lib/api/apiClient.ts`, `lib/auth/tokenStore·completeSignin·sessionEntry·sessionRestore`, `LoginContent.tsx`, 테스트 6개.

**공용 함수 사용처**

| 함수 | 사용처 |
|---|---|
| `refreshAccessToken` | apiClient 401 인터셉터, `streamClient.ts:24`, `connectionSocket.ts:194`, `sessionRestore.ts:11` (← SessionRestore·RoleRouteGuard) |
| `isSessionRejected` | `apiClient`, `sessionRestore` |
| `setAuthTokens` | `apiClient`(갱신, remember 생략), `completeSigninSession` (← LoginContent·KakaoCallbackContent·useSignupForm 카카오 가입) |
| `clearAuthTokens` | apiClient `clearSession`, `ReactQueryProvider`(`careai:auth-expired`), `RoleRouteGuard`, 로그아웃 `mutations.ts`, `AccountDeleteSection`, `PasswordChangeSection` |
| `getAccessToken()` 헤더 사용 | apiClient 요청 인터셉터, `connectionSocket`, `service/api/{ward,guardian}/camera.ts`, `guardian/anomaly.ts` |
| `document.cookie` 쓰기 | `tokenStore.ts`의 `setCookie`·`removeCookie` 두 곳뿐 |

**/login 진입 지점**

| 위치 | 조건 | 상태 |
|---|---|---|
| `app/page.tsx` | access 무효 + refresh 없음 | 바뀜 (refresh 있으면 `SessionRestore`) |
| `RoleRouteGuard` | access·refresh 모두 없음 / 복원 거절 / 프로필 오류(세션 지워짐·403·404) | 바뀜 |
| `SessionRestore` | 갱신 거절 | 신규 |
| `ReactQueryProvider` | `careai:auth-expired` (apiClient `clearSession`) | 그대로. 발생 조건이 줄었다(거절일 때만) |
| 로그아웃·탈퇴·비밀번호 변경·카카오 콜백·회원가입·인증 화면 버튼 | 사용자 동작 | 그대로 |

## PHASE A. 세션·토큰 안전성

**세션 정리 조건** - BE `ErrorCode`와 대조했다. 판정은 모두 PASS다.

| BE 응답 | FE 처리 |
|---|---|
| refresh 401 `INVALID_TOKEN`·`EXPIRED_TOKEN` (재사용 감지·다른 기기 로그인으로 밀려남 포함) | 정리 → /login |
| 403 `INACTIVE_USER` (정지·탈퇴 진행) | 정리 |
| 404 `USER_NOT_FOUND` | 정리 |
| 429 `TOO_MANY_REQUESTS` | 유지 + 다시 시도 |
| 503 `SERVICE_UNAVAILABLE` (Redis 장애) | 유지 + 다시 시도 |
| 5xx·시간 초과·오프라인 | 유지 |
| 200인데 토큰이 빠짐 | `SessionRejectedError` → 정리 (L-5 참고) |

- 갱신 5xx 뒤 원요청 401: 인터셉터 catch에서 reject만 하고 `clearSession` 분기로 가지 않는다(`_retry` 경로). **PASS**, 테스트로 고정.
- 갱신에는 성공했는데 재시도한 요청이 또 401이면 기존대로 `clearSession`이다. 정상 동작이다.

**탭 경합** (코드 경로로 추적)

| 시나리오 | 결과 | 판정 |
|---|---|---|
| 두 탭이 동시에 진입·갱신 | Web Locks로 한 줄로 선다. 뒤 탭은 락 안에서 쿠키를 다시 읽어 바뀐 refresh와 유효한 access를 그대로 쓴다. BE 호출 1회 | PASS (테스트) |
| 메모리 캐시가 오래된 탭 | 쿠키의 access도 만료됐으면 **최신** refresh로 갱신한다. 옛 토큰 재사용 0 | PASS (테스트) |
| 한 탭이 로그아웃하는 중에 다른 탭이 갱신 | 락 안에서 다시 읽으면 refresh가 없다 → 거절 → 정리 | PASS |
| 락 안에서 예외 | `locks.request`가 reject하고 락을 놓는다(Web Locks 명세) | PASS (테스트 없음 → L-2) |
| Web Locks 미지원 브라우저 | 탭 안 직렬화만 된다(PR에 한계로 명시) | 수용 |
| 다른 기기 로그인 | 밀려난 refresh → 401 `INVALID_TOKEN` → 정리 | PASS |

**쿠키 수명**
- 토큰 쿠키를 쓰는 곳은 모두 `setCookie` 한 곳을 거쳐 표시 쿠키를 따른다. 읽을 때 다시 쓰는 경우도 포함된다.
- 다음 경우에도 어긋나지 않는다: 유지 해제 후 유지 체크로 재로그인(표시 삭제), 해제 후 카카오 로그인(표시 삭제), 로그아웃(표시 포함 삭제), 해제 상태에서 표시만 남는 경우(브라우저 종료 시 함께 사라짐). **PASS**

**노출**: 새 코드에는 토큰·쿠키 값을 찍는 로그·console이 없다. WS debug는 기존 마스킹 그대로다. **PASS**

**streamClient**: 일시 오류이면 원요청을 reject하고 세션을 유지한다. 호출부가 다시 시도한다. **PASS**

### 🟡 M-1. 갱신이 일시 오류로 실패하면 실시간 알림 WS가 조용히 멈춘다

- **위치**: `connectionSocket.ts` `beforeConnect`. catch에서 실패 이유와 관계없이 `client.deactivate()`를 부른다.
- **회귀인 이유**:
  - PR 전: 같은 상황에서 세션이 지워져 로그인 화면으로 갔다. 사용자가 알아챘다.
  - PR 후: 로그인은 유지되고 화면도 정상처럼 보이지만, WS가 다시 붙지 않는다(페이지를 다시 열 때까지).
- **영향**: 보호자·피보호자 웹의 실시간 화면 갱신이 멈춘다(`anomaly-detected`·SOS·`medication-taken`·연결·`camera-analysis`). 강제 FCM 푸시는 그대로 도착하므로 안전 알림 자체가 사라지지는 않는다.
- **제안**:
  - `isSessionRejected(error)`일 때만 `deactivate()`한다.
  - 일시 오류이면 `mustRefreshToken = true`를 유지한 채 이번 연결 시도만 건너뛰고, STOMP 재연결 주기(`RECONNECT_DELAY_MS`)에 맞춰 다시 갱신한다.
  - 테스트: `connectionSocket` 단위 테스트에 "갱신 503 → deactivate 안 함 → 다음 주기 재시도", "갱신 401 → deactivate"를 추가한다.

## PHASE B. 기능 정합성

- **`resolveEntry`**: 판정 조합 전부와 역할 홈(WARD `/ward`, GUARDIAN `/guardian`, ADMIN `/guardian`)이 기존 `getRoleHomePath`와 같다. **PASS**
- **`SessionRestore`·`RoleRouteGuard`**:
  - 성공·거절·일시 장애 각각의 이동·안내가 의도와 같다.
  - 다시 시도는 버튼을 눌렀을 때만 일어나 무한 갱신 루프가 없다. 프로필은 `retry: false`다. **PASS**
- **프로필 오류 판정**:
  - 403(`INACTIVE_USER`)·404 → 정리.
  - 401이면서 refresh가 남아 있음(갱신 일시 장애) → 다시 시도. refresh가 지워짐(거절) → /login. **PASS**
- **StrictMode 이중 effect**: `refreshRequest` 공유로 BE 호출은 1회이고, 취소된 effect의 결과는 버린다. **PASS**
- **/login 직접 진입**: 바뀐 것 없음. 유효 세션이어도 자동 이동하지 않는 것은 기존과 같다.
- **gosky 실측** (`curl`, 가짜 쿠키 값):

| 쿠키 | 응답 |
|---|---|
| 없음 | 307 → `/login` |
| refresh만 | 200 (복원 화면) |
| 만료 access + refresh | 200 |

  브라우저 확인 3종(31분 뒤 재방문·유지 해제 후 종료·다른 기기)은 사용자 확인 대상이다.

## PHASE C. 구조·계약

- **서버 컴포넌트 경계**: `page.tsx`는 `sessionEntry`(타입·`routes`만 import)만 쓰고 axios·`document`를 끌어오지 않는다. **PASS**
- **하이드레이션**: `RoleRouteGuard`는 서버와 클라이언트 첫 렌더가 모두 `null`이라 불일치가 없다. **PASS**
- **안내 UI**: `role="alert"`, 버튼 높이 52px, 색은 디자인 토큰을 쓴다. 브랜드 배경에 흰 글씨는 기존 화면과 같은 관례다. **PASS**

### 🟢 L-3. 복원 중 빈 화면

`SessionRestore`와 `RoleRouteGuard` 복원 중에는 `null`을 렌더한다. 네트워크가 느리면 최대 10초(`refreshClient` timeout) 동안 빈 화면이 보인다. "로그인 상태를 확인하고 있어요" 같은 짧은 문구를 권장한다.

### 🟢 L-4. JWT payload 해석이 세 곳에 중복

`sessionEntry.getRoleFromAccessToken`, `tokenExpiry.getAccessTokenExpiryMs`, `tokenStore.getAccessTokenSubject`가 각자 해석한다. 공용 `decodeAccessTokenClaims` 하나로 모으기를 권장한다(동작 변화 없음).

## PHASE D. 테스트

**고정된 것**:
- 동시 401 → 갱신 1회
- 401·403·404 정리 / 429·5xx·네트워크 유지
- 갱신 5xx 뒤 원요청 401 → 로그아웃 없음
- 다른 탭 갱신 결과 그대로 사용, 만료 시 최신 토큰으로 갱신
- 유지 해제 → 세션 쿠키(갱신·재로드 후에도), 유지 체크 → 7일
- 카카오·가입 → 유지
- 첫 진입 판정, `SessionRestore` 3흐름, 가드 복원 성공·5xx 안내·403 정리

**변이 확인**: PR 작업 때 2종(갱신 실패 시 무조건 정리, 다른 탭 결과 사용 제거)에 더해, 이번 점검에서 2종을 확인했다. 4종 모두 테스트가 잡았다.
- `setCookie`가 표시를 무시하게 바꿈 → 단위 테스트 4건 실패
- 가드가 모든 프로필 오류에 세션을 정리하게 바꿈 → browser 테스트 1건 실패

### 🟢 L-2. 테스트 공백

- 가드의 복원 `rejected`(→ /login)·`unavailable`(→ 안내·다시 시도) 분기
- 토큰이 빠진 200 응답 → 정리
- 락 안 예외 / Web Locks 미지원 경로를 명시적으로 확인하는 테스트

## 그 밖의 발견

### 🟢 L-1. 갱신 응답을 잃은 시간 초과는 다음 갱신에서 BE 재사용 감지를 일으킨다 (수용 권장)

- **상황**: BE가 회전을 끝냈는데 응답이 시간 초과·게이트웨이 오류로 사라지면, FE는 옛 refresh를 쥔 채 세션을 유지한다. 다음 갱신 때 옛 토큰을 보내면 BE가 재사용으로 판정해 `revokeAll` + `TOKEN_REUSE_DETECTED` 접속 로그를 남기고 401로 응답한다 → 로그아웃.
- **비교**:
  - PR 전: 시간 초과 즉시 로그아웃. 재사용 감지는 일어나지 않았다.
  - PR 후: 사용자에게 보이는 결과(로그아웃)는 같고 조금 늦어진다. 접속 로그에 재사용 감지 오탐이 1건 남는다.
- **판단**: BE 갱신은 빠르고(10초 제한) 드문 경우다. 오프라인(`ERR_NETWORK`, 요청이 BE에 닿지 않음) 때 세션을 지키는 이득이 더 크다고 보고 수용을 권장한다.
- **대안**: 시간 초과(`ECONNABORTED`)만 "알 수 없음"으로 보고 이전처럼 정리하는 방법도 있지만 권장하지 않는다.

### 🟢 L-5. BE `auth.refresh-cookie.body-compat=false` 전환 전에 FE 쿠키 전환이 먼저여야 한다 (사전 조건 기록)

- body-compat을 끄면 갱신 응답의 `refreshToken`이 null이 된다. 이 PR에서 그 응답은 `SessionRejectedError` → 정리로 처리되므로, 전환 즉시 전 사용자가 첫 갱신 때 로그아웃된다.
- PR 전에도 같은 결과였으므로 회귀는 아니다. 다만 HttpOnly 쿠키(`careai_rt`) 전환 작업의 선행 조건으로 기록해 둔다.

## 수정 제안 (선택)

- **M-1**: `connectionSocket` 일시 오류 시 재시도 + 테스트
- **L-2**: 테스트 보강
- **L-3**: 확인 중 문구
- **L-4**: JWT 해석 통합

커밋 메시지 초안:
- `fix: 토큰 갱신이 일시 오류로 실패해도 실시간 알림 연결을 다시 시도`
- `test: 세션 복원 거절·일시 장애·토큰 누락 응답 테스트 보강`
- `refactor: access 토큰 클레임 해석을 한 곳으로 모으기`

L-1·L-5는 수용·기록만 하고 코드 변경은 없다.
