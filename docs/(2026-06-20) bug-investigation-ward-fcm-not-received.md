# 피보호자 FCM 알림 미수신 버그 진단

- **발견 일자**: 2026-06-20
- **상태**: 진단 완료 / 수정 미적용 (수정 방향 결정 대기)
- **범위**: 백엔드(SilverBridgeBe) + 프론트엔드(SilverBridgeFe) 교차 진단
- **관련 코드**: `notification` 도메인(BE), `src/lib/fcm.ts`(FE)

---

## 0. 한눈에 보기 (비전문가용 요약)

> **백엔드 알림 발송 코드는 정상입니다.** 피보호자에게 알림을 보내는 길에 막힌 곳이 없습니다.
>
> 진짜 원인은 **"FCM 토큰은 브라우저(기기)당 딱 1개인데, 그 1개를 보호자와 피보호자가 동시에 가질 수 없다"**는 점입니다.
> - 한 브라우저에서 Firebase가 만들어 주는 알림 토큰은 **계정이 바뀌어도 항상 같은 값** 1개입니다.
> - DB는 그 토큰 1개를 **한 사람만** 소유할 수 있게 돼 있어(UNIQUE 제약), **마지막에 등록한 사람**이 가져갑니다.
> - 게다가 프론트엔드에 **"이미 등록한 토큰이면 다시 등록하지 않는다"**는 코드가 있는데, 이 판단이 **"누가 로그인했는지"는 보지 않고 토큰 값만** 봅니다. 그래서 같은 브라우저에서 보호자 → 피보호자로 (깔끔한 로그아웃 없이) 바꾸면 **피보호자 로그인 시 등록 요청 자체를 건너뜁니다.**
> - 결과: 토큰은 보호자 소유로 남고, 피보호자 명의 토큰은 DB에 0건 → 피보호자는 알림을 못 받습니다.
>
> **즉, 같은 브라우저에서 두 계정을 번갈아 테스트했기 때문에 생긴 현상**입니다. 실제 사용 환경(보호자=폰A, 피보호자=폰B처럼 기기가 분리)에서는 양쪽 모두 정상 수신됩니다.

---

## 1. 증상

- 보호자 계정: FCM 알림 **정상 수신**
- 피보호자 계정: FCM 알림 **미수신**
- 양쪽 동일한 프론트 FCM 초기화 로직
- 프론트 로그: **"이미 등록된 토큰 사용"** ← 핵심 단서

---

## 2. 프론트 6개 확인 항목별 답변

| # | 질문 | 답변 (코드 근거) |
|---|---|---|
| 1 | 발송 시 피보호자 userId가 잡히나 | **잡힘.** `ConnectionService.requestConnectionAsGuardian`이 `wardId`를 실어 `ConnectionRequestedEvent` 발행(`ConnectionService.java:75-77`) → `ConnectionNotificationListener.handleRequested`가 `dispatch(event.wardId(), …)`로 발송(`:45,53`). 역할 역전 없음. |
| 2 | fcm_tokens.token UNIQUE + 재등록 동작 | **token 전역 UNIQUE**(`uq_fcm_tokens_token`, `V8:27`; 엔티티 `FcmToken.java:24`). 같은 토큰 재등록 시 새 행 insert가 아니라 **기존 행의 user_id를 새 사용자로 갱신**(`FcmService.registerToken:31-41` → `FcmToken.reassignTo:34-37`). **소유권 이전 자체는 백엔드에 구현돼 있음**(M-S2-2). 단, "마지막 등록자"만 소유 가능. |
| 3 | 연결 이벤트에 피보호자 발송 로직 존재 | **존재.** 연결요청 → 피보호자(`handleRequested`), 수락/거절 → 보호자, 해제 → 상대방(`ConnectionNotificationListener:44-97`). 피보호자가 받는 알림은 "연결 요청". |
| 4 | 역할(보호자) 필터링 조건 | **없음.** `NotificationDispatcher.dispatch`는 `userId`만 받고 역할을 보지 않음. 피보호자를 거르는 코드 없음. |
| 5 | 피보호자 FCM이 OFF로 판단되나 | **아니오(기본 ON).** 설정 행을 만드는 곳은 사용자가 직접 토글하는 `updateSettings` 한 곳뿐. 가입 시 OFF 행 생성 없음 → 행이 없으면 `DEFAULT_ENABLED_CHANNELS = {FCM}` 적용 → **ON**(`NotificationSettingService:32-33,84-87`). (피보호자가 설정에서 직접 끈 경우만 예외 — DB로 최종 확인 가능) |
| 6 | 토큰이 보호자에 고정되는 메커니즘 ★ | **있음(핵심 원인).** §3 참조. 단일-토큰 모델 + 프론트의 토큰값 기준 "이미 등록" 가드. |

---

## 3. 근본 원인

원인은 **두 층위가 같은 결과로 수렴**한다. 둘 다 "같은 브라우저 멀티계정" 상황에서만 발현한다.

### 3-A. 구조적 원인 — 단일 토큰 모델 (브라우저당 1소유자)
- Firebase JS SDK의 `getToken()`은 **같은 브라우저 + 같은 VAPID 키**에 대해 **항상 동일한 토큰 1개**를 반환한다(`fcm.ts:62-65`). 한 브라우저의 두 계정은 서로 다른 토큰을 가질 수 없다.
- 백엔드 `uq_fcm_tokens_token` UNIQUE 제약상 그 토큰 1행의 `user_id`는 **한 명**만 가질 수 있다.
- `registerToken`은 같은 토큰을 **마지막에 등록한 사용자에게 소유권을 넘긴다**(`reassignTo`).
- ⟹ 같은 브라우저에서 보호자·피보호자를 번갈아 쓰면 **마지막에 로그인/등록한 계정이 토큰을 독점**한다. 증상상 그게 보호자.
- ⟹ 다른 한쪽(피보호자)은 `fcmTokenRepository.findByUserId(wardId)`가 **0건** → `sendToUser`가 토큰 없음으로 조용히 종료(`FcmService:62-66`) → 미수신.

### 3-B. 프론트 가중 원인 — "이미 등록" 가드가 user를 보지 않음 ★
`registerFcmTokenForCurrentDevice`(`fcm.ts:68-103`)의 조기 반환:

```js
const storedToken = storage?.getItem(FCM_TOKEN_KEY);              // careai_fcm_token
const registeredToken = storage?.getItem(FCM_REGISTERED_TOKEN_KEY); // careai_fcm_registered_token
if (storedToken && registeredToken === storedToken) return storedToken;  // ★ API 호출 생략
```

- 이 가드는 **토큰 값만** 비교하고 **현재 로그인 사용자(userId)는 고려하지 않는다.** 저장소는 `sessionStorage`(탭 단위, 탭 유지 시 잔존).
- 깔끔한 로그아웃(`useLogoutMutation` → `unregisterFcmTokenForCurrentDevice`, `mutations.ts:18`)은 두 키를 모두 지우므로 가드가 초기화된다. **그러나** 로그아웃을 거치지 않는 계정 전환(같은 탭에서 바로 재로그인, 세션 토큰 교체 등)에서는 `sessionStorage`가 잔존 → 가드가 그대로 살아 있어, **새 사용자(피보호자) 로그인 시 `registerNotificationFcmToken` POST를 아예 건너뛴다.**
- 등록 트리거는 `completeSigninSession`(`completeSignin.ts:8`)·`RoleRouteGuard`(`RoleRouteGuard.tsx:60`) 두 곳인데, 둘 다 같은 가드를 통과해야 실제 호출된다.
- ⟹ 백엔드 `reassignTo`가 발동할 기회 자체가 없어 토큰이 보호자 소유로 **하드 고정**된다. → 이것이 프론트 로그 **"이미 등록된 토큰 사용"**의 정체이며, 증상과 정확히 일치한다.

### 가설 검증 결과
| 가설 | 판정 | 근거 |
|---|---|---|
| **1. 토큰 소유권** | ✅ **원인** (단, "이전 누락"이 아니라 "단일 토큰 독점 + 프론트 가드의 API 생략") | §3-A, §3-B |
| 2. 설정 기본값 OFF | ❌ 코드상 기본 ON | `NotificationSettingService:32-33,84-87` |
| 3. 역할 필터 | ❌ 없음 | `NotificationDispatcher` |
| 4. userId 매핑 오류 | ❌ 없음 | 이벤트·디스패처 모두 `wardId` 일관 |

---

## 4. 토큰 소유권 메커니즘 (왜 "보호자 정상 / 피보호자 미수신"인가)

```
[같은 브라우저, 깔끔한 로그아웃 없이 계정 전환]
1. 보호자 로그인 → getToken()=T → POST /fcm-token → DB: T = 보호자
                    sessionStorage = { careai_fcm_token=T, careai_fcm_registered_token=T }
2. (로그아웃 버튼 미경유) 같은 탭에서 피보호자 로그인
   → registerFcmTokenForCurrentDevice(): storedToken=T, registeredToken=T → 일치 → 조기 반환 (POST 생략)
   → DB: T = 보호자 그대로 (reassign 미발동)
3. 보호자가 연결요청 → dispatch(wardId) → findByUserId(wardId) = 0건 → 발송 없음
   반면 보호자 대상 알림(수락/거절 등)은 T로 정상 발송 → 보호자만 수신
```

> 참고: **깔끔한 로그아웃을 거친 순차 테스트**라면, 마지막에 로그인한 계정이 토큰을 가져간다(로그아웃이 백엔드 토큰을 삭제 → 다음 로그인이 재등록). 이 경우 "피보호자가 마지막 로그인"이면 일시적으로 피보호자가 받고 보호자가 못 받는 **반대 증상**도 가능하다. 어느 쪽이든 **한 브라우저에서는 동시에 둘 다 받을 수 없다**는 게 본질이다.

---

## 5. 수정 방향 (제안 — 미적용, 결정 대기)

> 규칙상 코드 수정은 다음 단계. 각 방향의 영향 범위(보호자 알림 회귀 여부)를 함께 표기한다.

### A. "테스트 한계"로 결론 — 백엔드/프론트 무수정 (가장 보수적)
- 운영 환경은 보호자·피보호자가 **물리적으로 다른 기기**라 단일-토큰 충돌이 없다 → 현 코드로 양쪽 정상.
- 조치: 테스트를 **서로 다른 브라우저 프로필/시크릿창/기기**로 분리해 재현 확인. 코드 변경 없음.
- 회귀 위험: 없음. **단, 한 사용자가 보호자·피보호자 역할을 한 기기에서 겸하는 실제 시나리오가 있다면 부적합.**

### B. 프론트 보강 — 계정 전환 시 항상 재등록 (권장 후보)
- `registerFcmTokenForCurrentDevice`의 "이미 등록" 가드를 **userId까지 포함**해 판단(예: `careai_fcm_registered_token`을 `${userId}:${token}`로 저장)하거나, **로그인 시 가드를 무조건 1회 무효화**해 새 사용자 명의로 재등록(POST) → 백엔드 `reassignTo`가 소유권을 새 사용자로 이전.
- 효과: 같은 브라우저에서도 **현재 로그인 사용자가 토큰 소유** → 그 사용자가 알림 수신. (여전히 "마지막 로그인 1명"만 수신 — 단일 토큰 한계는 잔존하나, 의도와 일치)
- 회귀 위험: 낮음. 백엔드 무변경. 등록 API 호출 빈도만 소폭 증가(rate limit `fcm-register` 범위 내 확인 필요).

### C. 백엔드 모델 변경 — 한 토큰을 여러 user에 허용 (가장 큼, 비권장)
- `(user_id, token)` 복합 UNIQUE로 바꿔 한 기기 토큰을 여러 user가 공유 → 한 브라우저에서 두 계정 동시 수신.
- 회귀 위험: **높음.** `uq_fcm_tokens_token` 제거 → 멀티캐스트 중복 발송·만료 토큰 정리(`cleanupInvalidTokens`)·소유권 이전 로직 전면 재검토 필요. 마이그레이션 동반. 실효(브라우저 1개에 두 사람이 동시 로그인하는 운영 시나리오)가 크지 않으면 과投資.

---

## 6. 다음 액션

1. **(권장) DB 확인** — 테스트 계정 ID 확보 후 (현재 보류):
   - `SELECT user_id, left(token,12)||'…', platform, updated_at FROM fcm_tokens WHERE user_id IN ('<wardId>','<guardianId>') ORDER BY updated_at;` → 피보호자 행 0건이면 §3 확정.
   - `SELECT channel_type, enabled FROM user_notification_settings WHERE user_id='<wardId>';` → 가설 2 최종 배제(행 없거나 FCM=true면 정상).
2. **재현 분리 테스트** — 보호자/피보호자를 **다른 브라우저 프로필 또는 다른 기기**로 분리해 양쪽 수신되는지 확인(= 단일-토큰 원인 검증).
3. **수정 방향 결정** — A / B / C 중 선택. (현 분석상 **B**가 비용 대비 효과 균형이 좋고 백엔드 회귀 없음)

---

## 부록: 코드 근거 인덱스
- BE: `FcmService.java:30-70`, `FcmToken.java:24,34-37`, `FcmTokenRepository.java:15-18`, `NotificationDispatcher.java:61-89`, `NotificationSettingService.java:32-33,84-87`, `ConnectionNotificationListener.java:42-57`, `ConnectionService.java:60-80`, `NotificationType.java:16`, `V8__guardian_ward_features.sql:18-37`
- FE: `src/lib/fcm.ts:68-117`(가드·등록·해제), `src/lib/auth/completeSignin.ts:8`, `src/service/query/auth/mutations.ts:18`, `src/components/RoleRouteGuard.tsx:60`, `src/service/api/notification.ts:7-15`
