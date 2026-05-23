# 백엔드 비밀번호 재설정 정책 변경 - 프론트 영향 분석

- **작성일:** 2026-05-22
- **작성자:** Claude Code 분석
- **대상 브랜치:** develop
- **성격:** 분석 + 인계 문서 (코드 미수정)

> 이 문서는 실제 프론트 코드를 정독한 결과를 기반으로 합니다. 추측한 부분은 "확인 필요"로 명시했습니다.

---

## 1. 변경 요약 (한눈에 보기)

| 항목 | 내용 |
|------|------|
| 변경 일자 | (백엔드 배포일 — **확인 필요**) |
| 영향 받는 엔드포인트 수 | **4개** (email send/resend, sms send/resend) |
| 영향 받는 프론트 파일 (직접) | **2개** (`FindPasswordContent.tsx`, `useFindPasswordFlow.ts`) |
| 영향 받는 프론트 파일 (선택/방어) | **3개** (`errorHandler.ts`, `FindPasswordEmailStep.tsx`, `FindPasswordSmsStep.tsx`) |
| 프론트 작업 예상 시간 | **2~4시간** (UX 추가 제외 시 1~1.5시간) |
| 디자이너 컨펌 필요 항목 수 | **2개** (404 가입유도 UI, 429 대기 안내 UI) |
| 핵심 리스크 | 전역 에러 핸들러(`errorHandler.ts`) 수정 시 모든 API 영향 |

**한 줄 결론:**
현재 아키텍처는 백엔드 메시지를 인라인으로 표시하는 구조라 **에러 메시지 출력 자체는 거의 자동으로 동작**한다. 다만 `FindPasswordContent.tsx`가 발송 결과와 무관하게 다음 스텝으로 넘어가는 흐름이라, **"에러 시 스텝 진행을 막는" 분기 처리가 핵심 작업**이다. 나머지(429 메시지, 404 가입 유도)는 UX 보강 성격.

---

## 2. 백엔드 변경 사항

### 2.1 영향 받는 엔드포인트

| Method | Endpoint | 프론트 호출 함수 (`src/service/api/auth.ts`) |
|--------|----------|-----------------------------------------------|
| POST | `/api/auth/find-password/email/send` | `findPasswordEmailSend` (L74) |
| POST | `/api/auth/find-password/email/resend` | `findPasswordEmailResend` (L82) |
| POST | `/api/auth/find-password/sms/send` | `findPasswordSmsSend` (L87) |
| POST | `/api/auth/find-password/sms/resend` | `findPasswordSmsResend` (L95) |

> ⚠️ `find-password/email/verify`, `find-password/sms/verify`, `password/reset`는 **이번 변경 범위 밖**이다. 인증코드 검증/최종 재설정 로직은 건드리지 않는다.

### 2.2 응답 정책 변경 (기존 → 변경)

| 상태 | 기존 | 변경 후 | 백엔드 메시지 |
|------|------|---------|--------------|
| 정상 | 200 OK (항상) | 200 OK | `인증코드가 발송되었습니다` |
| 형식 오류 | (없음, 200으로 흡수) | **400 Bad Request** | 이메일: `올바른 이메일 형식이 아닙니다` / 전화번호: `올바른 전화번호 형식이 아닙니다` |
| 미가입 | (없음, 200으로 숨김) | **404 Not Found** | 이메일: `해당 이메일로 가입된 계정이 없습니다` / SMS: `사용자를 찾을 수 없습니다` |
| 횟수 초과 | (없음) | **429 Too Many Requests** | `잠시 후 다시 시도해주세요` |

### 2.3 변경 배경

- 서비스 타겟이 **시니어/4050** → UX 우선
- 기존 "항상 200" 정책이 *"메일이 안 오는데?"* 이탈을 유발
- User Enumeration(가입 여부 노출) 리스크는 **Rate Limit(429)으로 보완**

---

## 3. 현재 프론트 상태 (PHASE 0 결과)

### 3.1 기술 스택

| 구분 | 기술 |
|------|------|
| 프레임워크 | Next.js 16.2.2 (App Router, `(auth)` route group) |
| UI | React 19.2.4 / TypeScript 5 |
| 데이터 페칭 | @tanstack/react-query 5.96.2 (`useMutation`) |
| HTTP 클라이언트 | axios 1.14.0 (`src/lib/api/apiClient.ts` 단일 인스턴스 + interceptor) |
| 폼 | react-hook-form 7.72.1 (단, 비번찾기 화면은 로컬 state + `useMutation` 사용) |
| 스타일링 | CSS Modules (`*.module.css`) + classnames/bind |

### 3.2 관련 파일 목록

**비밀번호 찾기 전용 (9개)**
```
src/app/(auth)/find-password/
├── page.tsx                                  # 페이지 진입점 (단순 래퍼)
├── _hooks/
│   └── useFindPasswordFlow.ts                # ★ 상태·mutation·에러 관리 (핵심)
└── _components/
    ├── FindPasswordContent.tsx               # ★ 스텝 오케스트레이션 (핵심)
    ├── FindPasswordContent.module.css        # .errorMessage 스타일 정의(L138)
    ├── FindPasswordMethodStep.tsx            # 1단계: 이메일/SMS 방식 선택
    ├── FindPasswordEmailStep.tsx             # 2단계(email): 이메일 입력·발송
    ├── FindPasswordSmsStep.tsx               # 2단계(sms): 이름·전화 입력·발송
    ├── FindPasswordVerifyStep.tsx            # 3단계: 인증코드 입력(공유 폼 래핑)
    └── FindPasswordResetStep.tsx             # 4단계: 새 비밀번호 설정
```

**공유 / 공통 레이어**
```
src/service/api/auth.ts                       # API 호출 함수 (순수, 분기 없음)
src/service/interface/auth.ts                 # 요청/응답 타입
src/service/interface/common.ts               # CommonResponse<T>
src/lib/api/apiClient.ts                      # ★ axios 인스턴스 + interceptor (전역)
src/lib/api/errorHandler.ts                   # ★ resolveError + ERROR_MESSAGES (전역)
src/app/(auth)/_components/VerificationCodeForm.tsx  # 인증코드 입력 폼 (find-password 전용 사용)
src/app/(auth)/_components/AuthTimer.tsx      # 인증 타이머 (180초)
src/app/_components/common/TextInput.tsx      # 공통 입력 컴포넌트
src/app/constant/pattern.ts                   # EMAIL/PHONE/PASSWORD 정규식
```

> 토스트/스낵바/공통 모달 컴포넌트는 **존재하지 않음**. (검색 결과 `toast` 키워드는 `apiClient.ts` 주석과 FCM `PushNotificationListener`뿐.)

### 3.3 현재 API 호출 방식

**호출 흐름:** `컴포넌트 → useFindPasswordFlow(useMutation) → service/api/auth.ts → apiClient(axios) → interceptor`

1. **`service/api/auth.ts`** — 응답을 가공하지 않는 **순수 호출 레이어**.
   ```ts
   export async function findPasswordEmailSend(body: IFindPasswordEmailSendReq) {
     return apiClient.post<CommonResponse<null>>(`${BASE}/find-password/email/send`, body);
   }
   // resend/sms/send/sms/resend 모두 동일 패턴 — 분기 없음
   ```
   → **이 파일은 수정 불필요.** (상태코드 분기를 여기에 넣지 않는 것이 기존 구조에 맞음)

2. **`apiClient.ts` response interceptor** — 성공 시 `response.data`만 반환, 실패 시 `resolveError`로 메시지를 만들어 `error.message`에 주입 후 `Promise.reject`.
   ```ts
   async (error: AxiosError) => {
     // ...401 refresh 로직...
     const apiError = resolveError(error as AxiosError<ServerErrorBody>);
     error.message = apiError.message;   // ← 백엔드 메시지가 여기로 들어옴
     return Promise.reject(error);
   }
   ```

3. **`useFindPasswordFlow.ts`** — 엔드포인트별 `useMutation`. `onError`에서 `error.message`를 `errorMessage` state로 옮김.
   ```ts
   const sendEmailMutation = useMutation({
     mutationFn: findPasswordEmailSend,
     onSuccess: () => setErrorMessage(''),
     onError: (error: Error) => setErrorMessage(error.message || '이메일 발송에 실패했습니다.'),
   });
   // sendEmail = (body) => sendEmailMutation.mutateAsync(body)  ← 실패 시 throw
   ```

### 3.4 현재 에러 처리 방식

| 질문 | 현재 상태 |
|------|-----------|
| 표시 방식 | **인라인 텍스트** — `{errorMessage && <p className={cx('errorMessage')}>{errorMessage}</p>}` (Email/Sms/Reset Step, VerificationCodeForm 모두 동일) |
| 공통 컴포넌트 | 토스트/모달 없음. 각 Step이 `errorMessage` prop을 받아 직접 렌더 |
| 메시지 결정 위치 | **백엔드 메시지 우선** → 없으면 status별 프론트 fallback(`ERROR_MESSAGES`) → 그래도 없으면 `'알 수 없는 오류가 발생했어요.'` |

`errorHandler.ts`의 메시지 결정 로직:
```ts
const ERROR_MESSAGES: Record<number, string> = {
  400: '요청이 올바르지 않아요. 입력값을 확인해주세요.',
  401: '로그인이 필요해요.',
  403: '접근 권한이 없어요.',
  404: '요청한 정보를 찾을 수 없어요.',
  409: '이미 처리된 요청입니다.',
  500: '서버에 문제가 생겼어요. 잠시 후 다시 시도해주세요.',
  // ⚠️ 429 항목 없음
};

message: error.response?.data?.message ?? ERROR_MESSAGES[status] ?? '알 수 없는 오류가 발생했어요.'
```
→ **백엔드가 보내는 새 메시지가 `error.response.data.message`에 담겨 오면 그대로 화면에 출력된다.** (단, 에러 응답 body 형태가 `{ message }` 필드를 갖는지 **확인 필요** — 3.5 / 7장 참조)

### 3.5 현재 사용 중인 메시지 문구

| 위치 | 문구 |
|------|------|
| 발송 실패 fallback (email) | `이메일 발송에 실패했습니다.` |
| 재발송 실패 fallback (email) | `이메일 재발송에 실패했습니다.` |
| 발송 실패 fallback (sms) | `SMS 발송에 실패했습니다.` |
| 재발송 실패 fallback (sms) | `SMS 재발송에 실패했습니다.` |
| 인증 실패 | `인증번호가 올바르지 않습니다.` |
| 새 비밀번호 규칙 | `8자 이상, 숫자와 특수문자를 포함해야 합니다.` |
| 비밀번호 불일치 | `비밀번호가 일치하지 않습니다.` |
| 400 전역 fallback | `요청이 올바르지 않아요. 입력값을 확인해주세요.` |
| 404 전역 fallback | `요청한 정보를 찾을 수 없어요.` |

> 이 fallback 문구들은 **백엔드 메시지가 안 올 때만** 노출된다. 백엔드가 메시지를 보내면 백엔드 문구가 우선.

### 3.6 Rate Limit(429) 처리 코드 존재 여부

- **없음.** `ERROR_MESSAGES`에 429 키가 없고, 429 전용 분기/대기시간 표시 로직도 전무.
- 현재로선 429 발생 시: 백엔드 메시지(`잠시 후 다시 시도해주세요`)가 오면 그대로 표시되고, 안 오면 `알 수 없는 오류가 발생했어요.`로 떨어진다.

---

## 4. 영향 분석 (PHASE 1 결과)

### 4.0 결론 먼저: 변경의 성격

기존 정책이 "항상 200"이었기 때문에, 현재 `FindPasswordContent.tsx`는 **발송 성공을 전제로** 다음 스텝(인증코드 입력)으로 무조건 넘어간다:
```ts
const handleEmailSubmit = async (event) => {
  event.preventDefault();
  await flow.sendEmail({ email: flow.email });
  flow.setStep(3);   // ← 발송 결과와 무관하게 항상 실행되던 흐름
};
```
- `mutateAsync`는 실패 시 **reject(throw)** 하므로, 현재도 에러가 나면 `setStep(3)`은 *건너뛰어진다*. 즉 "스텝이 잘못 진행되는" 치명적 버그는 아니다.
- 그러나 ① `await`가 try/catch 없이 throw → **unhandled promise rejection** 발생, ② 기존엔 거의 안 나던 에러(404/400/429)가 **정상 플로우의 일부**가 되므로, 명시적 try/catch 분기로 정리하는 것이 안전하다.

### 4.1 수정 필요한 파일 목록

---

#### ① `src/app/(auth)/find-password/_components/FindPasswordContent.tsx` — **난이도: 중간** (핵심)

**현재 코드 (해당 부분):**
```ts
const handleEmailSubmit = async (event: FormEvent<HTMLFormElement>) => {
  event.preventDefault();
  await flow.sendEmail({ email: flow.email });
  flow.setStep(3);
};

const handleSmsSubmit = async (event: FormEvent<HTMLFormElement>) => {
  event.preventDefault();
  await flow.sendSms({ name: flow.name, phone: flow.phone });
  flow.setStep(3);
};
```

**수정 방향:**
- `await` 호출을 `try/catch`로 감싸 **성공(200)일 때만 `setStep(3)`** 실행, 실패 시 스텝 유지(에러는 `onError`가 이미 `errorMessage`에 세팅).
- 이렇게 하면 unhandled rejection이 사라지고, 400/404/429 시 사용자는 입력 화면(step 2)에 머무르며 인라인 에러를 본다.
- (개념 예시 — 실제 구현은 개발자 재량)
  ```ts
  const handleEmailSubmit = async (event) => {
    event.preventDefault();
    try {
      await flow.sendEmail({ email: flow.email });
      flow.setStep(3);
    } catch {
      /* errorMessage는 mutation onError가 이미 세팅 → 스텝 유지 */
    }
  };
  ```
- (선택) 404 시 "회원가입 유도" CTA를 띄우려면, 어떤 status였는지 알 수 있도록 훅에서 status를 노출해야 함 → 4.5 참조.

---

#### ② `src/app/(auth)/find-password/_hooks/useFindPasswordFlow.ts` — **난이도: 낮음~중간**

**현재 코드 (해당 부분):**
```ts
const sendEmailMutation = useMutation({
  mutationFn: findPasswordEmailSend,
  onSuccess: () => setErrorMessage(''),
  onError: (error: Error) => setErrorMessage(error.message || '이메일 발송에 실패했습니다.'),
});
// sms/resend 동일
```

**수정 방향:**
- **기본 동작은 수정 없이도 OK** — `error.message`에 백엔드 메시지가 들어오므로 인라인 표시는 그대로 작동.
- (선택, 404 가입유도/429 분류가 필요할 때만) `onError`에서 status를 함께 보관:
  ```ts
  onError: (error) => {
    const status = (error as AxiosError).response?.status;
    setErrorMessage(error.message || '...');
    setErrorStatus(status ?? null);   // 신규 state: 404 → 가입 CTA, 429 → 대기 안내 분기용
  }
  ```
- 이 변경은 ①의 "404 시 회원가입 버튼" / 4.5 UX를 구현할 때만 필요. **메시지 표시만 목표라면 불필요.**

---

#### ③ `src/lib/api/errorHandler.ts` — **난이도: 낮음 / ⚠️ 전역 영향**

**현재 코드:**
```ts
const ERROR_MESSAGES: Record<number, string> = {
  400: '요청이 올바르지 않아요. 입력값을 확인해주세요.',
  // ... 429 없음
};
```

**수정 방향 (방어용, 권장):**
- 429 fallback 추가:
  ```ts
  429: '요청이 많아요. 잠시 후 다시 시도해주세요.',
  ```
- **목적:** 백엔드가 어떤 이유로 메시지를 누락해도 `알 수 없는 오류가 발생했어요.` 대신 의미 있는 문구가 나가게 하는 안전장치.
- **⚠️ 회귀 주의:** 이 파일은 **모든 API**(notification, connect/ward, connect/guardian, user, announcement, auth)가 공유한다. 429 키 *추가*는 안전(기존 동작 미변경, 비어있던 케이스만 채움)하지만, 기존 키(400/404 등) 문구를 바꾸면 전 화면 에러 문구가 동시에 바뀐다 → **기존 키는 건드리지 말 것.**

---

#### ④⑤ `FindPasswordEmailStep.tsx` / `FindPasswordSmsStep.tsx` — **난이도: 낮음 (UI 보강 시에만)**

**현재 코드 (공통):**
```tsx
{errorMessage && <p className={cx('errorMessage')}>{errorMessage}</p>}
```

**수정 방향:**
- 메시지 표시만 목표라면 **수정 불필요** (이미 인라인 렌더 중).
- 404 가입유도 CTA / 429 대기 안내 UI를 넣을 경우에만 마크업 추가 (디자이너 컨펌 후).

---

### 4.2 새로 추가해야 할 코드

| 케이스 | 필요한 처리 | 추가 위치 | 필수도 |
|--------|-------------|-----------|--------|
| **404 (이메일 미가입)** | 메시지 표시는 자동. (선택) "회원가입 하러 가기" CTA | `FindPasswordEmailStep` + 훅 status | 메시지=자동 / CTA=선택 |
| **404 (SMS 사용자 못 찾음)** | 동일. 메시지 `사용자를 찾을 수 없습니다` 표시 | `FindPasswordSmsStep` | 메시지=자동 / CTA=선택 |
| **400 (형식 오류)** | 메시지 표시 자동. (선택) 프론트 정규식(`EMAIL_PATTRERN`/`PHONE_PATTRERN`) 선검증으로 사전 차단 | Step 컴포넌트 `isValid` 강화 | 선택 |
| **429 (Rate Limit)** | 메시지 표시 자동(또는 ③ fallback). (선택) 남은 대기시간 카운트다운 | `errorHandler`(③) + UI | 메시지=자동 / 카운트다운=선택 |

> "자동"이라고 표기한 항목들은 **백엔드 메시지가 `error.response.data.message`로 내려온다는 전제** 하에 현재 코드로 표시된다. 이 전제는 7장에서 백엔드 확인이 필요하다.

### 4.3 메시지 문구 매핑

| 응답 코드 | 백엔드 메시지 | 프론트 표시 (제안) | 표시 방식 |
|-----------|--------------|---------------------|-----------|
| 200 | `인증코드가 발송되었습니다` | (메시지 불필요 — step 3로 진행) | 화면 전환 |
| 400 (email) | `올바른 이메일 형식이 아닙니다` | 동일 (백엔드 그대로) | 인라인(이메일 입력 아래) |
| 400 (phone) | `올바른 전화번호 형식이 아닙니다` | 동일 | 인라인(SMS 입력 아래) |
| 404 (email) | `해당 이메일로 가입된 계정이 없습니다` | 동일 + (선택) "회원가입 하러 가기" 버튼 | 인라인 + CTA |
| 404 (sms) | `사용자를 찾을 수 없습니다` | (제안) `입력하신 정보로 가입된 계정이 없습니다` 로 톤 통일 검토 | 인라인 + CTA |
| 429 | `잠시 후 다시 시도해주세요` | 동일 / fallback `요청이 많아요. 잠시 후 다시 시도해주세요.` | 인라인 |

**제안: 메시지는 "백엔드 메시지 그대로 표시"를 유지한다.**
- 근거: 현재 `resolveError`가 이미 백엔드 메시지를 1순위로 사용 → 패턴 일관.
- 근거: 백엔드 메시지가 시니어 타겟 UX를 고려해 작성됨(변경 배경).
- 예외 검토: SMS 404 `사용자를 찾을 수 없습니다`는 시니어에게 다소 기계적 → **문구 통일을 기획/백엔드와 협의**(7장).

### 4.4 UI 디자인 필요 항목 (디자이너 컨펌)

1. **404 에러 화면** — "가입된 계정 없음" 안내 + (제안) 회원가입 유도 CTA의 위치/문구/스타일.
2. **429 에러 화면** — 단순 메시지로 충분한지, 아니면 **남은 대기시간 카운트다운**(예: "30초 후 재시도 가능")까지 보여줄지. 카운트다운을 하려면 백엔드가 `Retry-After` 헤더 또는 대기초를 내려줘야 함(7장).

> 둘 다 기존 `.errorMessage` 인라인 스타일을 재사용하면 디자인 변경 없이도 동작은 가능. CTA/카운트다운을 "추가"할 때만 디자인 컨펌이 필요.

### 4.5 추가 UX 제안

- **404 → 회원가입 유도:** 시니어 타겟 특성상, "계정이 없습니다"만 보여주면 막다른 길이 된다. `회원가입 하러 가기` 버튼(`router.push('/signup')`)을 함께 노출 권장. 구현하려면 ②(훅에서 status 노출) 필요.
- **404 (email) → 이메일 찾기 유도:** 사용자가 이메일을 잘못 기억했을 수 있으므로 `이메일(아이디) 찾기` 링크 병행 검토 (find-email 플로우가 이미 존재).
- **429 → 재발송 버튼 비활성화 + 카운트다운:** 현재 `VerificationCodeForm`/Step의 재발송 버튼이 즉시 다시 눌리면 429를 반복 유발. 429 수신 시 일정 시간 버튼 disable 권장.
- **400 사전 차단:** `pattern.ts`의 `EMAIL_PATTRERN`/`PHONE_PATTRERN`로 제출 전 검증하면 굳이 서버 400을 받지 않아도 됨(왕복 절감 + 즉각 피드백). 현재 Email/Sms Step의 `isValid`는 `.trim().length > 0`만 검사한다.

---

## 5. 작업 체크리스트 (프론트 개발자용)

**필수 (메시지/플로우 정상화)**
- [ ] `FindPasswordContent.tsx` — `handleEmailSubmit` / `handleSmsSubmit`를 try/catch로 감싸 **성공 시에만 `setStep(3)`** (①)
- [ ] (선택·권장) `errorHandler.ts` — `ERROR_MESSAGES`에 `429` fallback 추가, 기존 키는 그대로 (③)
- [ ] 백엔드 에러 응답 body가 `{ message }` 필드를 포함하는지 실측 확인 (→ 안 그러면 메시지가 fallback으로 떨어짐)

**선택 (UX 보강 — 디자이너 컨펌 후)**
- [ ] `useFindPasswordFlow.ts` — `onError`에서 status 보관(`errorStatus`) (②)
- [ ] `FindPasswordEmailStep` / `FindPasswordSmsStep` — 404 시 "회원가입 하러 가기" CTA
- [ ] 429 시 재발송 버튼 disable / 카운트다운
- [ ] Email/Sms Step `isValid`에 정규식 선검증 추가 (400 사전 차단)

**테스트 케이스**
- [ ] 정상 (200) → step 3(인증코드)로 진행, 에러 메시지 없음
- [ ] 이메일 형식 오류 (400) → step 2 유지, `올바른 이메일 형식이 아닙니다` 인라인
- [ ] 전화번호 형식 오류 (400) → step 2 유지, `올바른 전화번호 형식이 아닙니다` 인라인
- [ ] 미가입 이메일 (404) → step 2 유지, `해당 이메일로 가입된 계정이 없습니다` (+CTA 구현 시 버튼)
- [ ] 미가입 사용자 (404, SMS) → step 2 유지, `사용자를 찾을 수 없습니다`
- [ ] Rate Limit (429) → step 2 유지, `잠시 후 다시 시도해주세요`
- [ ] **재발송 경로 회귀** — step 3에서 재발송 시 429/404가 와도 인라인 표시되고 화면 안 깨지는지
- [ ] **unhandled rejection 없음** — 콘솔에 Uncaught (in promise) 경고가 안 뜨는지

---

## 6. 회귀 위험 (PHASE 1 E 결과)

| 위험 | 영향 범위 | 평가 |
|------|-----------|------|
| **`errorHandler.ts` 수정** | **전역** — `notification`, `connect/ward`, `connect/guardian`, `user`, `announcement`, `auth` 모든 API가 `resolveError` 공유 | 429 키 *추가*는 안전(빈 케이스만 채움). **기존 키 문구 변경은 전 화면 영향 → 금지** |
| **`apiClient.ts` interceptor** | 전역 + 401 refresh 로직 포함 | 이번 작업에서 **수정 불필요**. 손대지 말 것 |
| **`VerificationCodeForm.tsx`** | find-password verify 스텝에서만 사용 (signup은 별도 `SignupPhoneVerificationStep` 사용) | 회귀 범위 좁음. 단 재발송이 send/resend 엔드포인트를 호출하므로 429/404 표시 동작은 확인 필요 |
| **`service/api/auth.ts`** | 순수 호출 레이어, 응답 가공 없음 | **수정 불필요** → 회귀 위험 없음 |
| **find-email 플로우** | 별도 엔드포인트(`/auth/find-email`), 이번 변경 무관 | 영향 없음 (단 동일 인터셉터 공유) |

**핵심 가드레일:** 이번 작업의 코드 변경은 가급적 `FindPasswordContent.tsx` + (선택) `useFindPasswordFlow.ts`로 **국소화**한다. 전역 파일(`errorHandler.ts`)은 429 추가만, `apiClient.ts`는 미수정.

---

## 7. 질문 / 협의 필요 사항

### 백엔드 팀
1. **에러 응답 body 형태** — 400/404/429 응답 body가 `{ "message": "...", "code": ... }`(=`ServerErrorBody`) 형태인가, 아니면 `CommonResponse`(`{ code, message, data }`) 형태인가? `resolveError`는 `error.response.data.message`를 읽으므로, 메시지가 다른 필드에 있으면 fallback 문구로 떨어진다. **(메시지 자동 표시의 핵심 전제)**
2. **429 대기시간 제공 여부** — `Retry-After` 헤더 또는 body에 남은 초를 주는가? (카운트다운 UX 가능 여부 결정)
3. **Rate Limit 기준** — 이메일/전화 단위인가 IP 단위인가? 재발송 버튼 disable 시간을 맞추기 위해 필요.
4. **400 트리거 조건** — 형식 검증만인가, 빈 값/누락도 400인가? 프론트 선검증 정규식과 정합 맞추기 위함.
5. **배포 일자** — 1장 "변경 일자" 확정 + 프론트 배포와의 순서(백엔드 먼저 나가면 그 사이 프론트는 fallback 문구로 동작).

### 디자인 팀
6. **404 화면** — "계정 없음" 메시지 + 회원가입/이메일찾기 CTA의 노출 여부·위치·문구.
7. **429 화면** — 단순 메시지 vs 카운트다운 + 재발송 버튼 비활성 상태 디자인.

### 기획 팀
8. **SMS 404 문구** — 백엔드 `사용자를 찾을 수 없습니다`를 그대로 쓸지, 이메일 케이스(`해당 이메일로 가입된 계정이 없습니다`)와 톤을 통일할지.
9. **User Enumeration 정책 합의 확인** — 가입 여부가 404로 노출되는 것이 보안 검토를 거친 의사결정인지(변경 배경상 "Rate Limit으로 보완" 합의됨으로 이해) 최종 확인.

---

## 부록 A. 데이터 흐름 요약

```
[사용자 입력]
   │  (FindPasswordEmailStep / FindPasswordSmsStep)
   ▼
handleEmailSubmit / handleSmsSubmit   ← ★수정 지점(try/catch + setStep)
   │  FindPasswordContent.tsx
   ▼
flow.sendEmail / flow.sendSms (mutateAsync)
   │  useFindPasswordFlow.ts (onSuccess/onError → errorMessage state)
   ▼
findPasswordEmailSend / ...Send / ...Resend
   │  service/api/auth.ts (순수 호출)
   ▼
apiClient(axios) → response interceptor
   │  성공: response.data 반환 / 실패: resolveError로 error.message 주입 후 reject
   ▼
errorHandler.resolveError   ← ★(선택) 429 fallback 추가 지점
   │  message = data.message ?? ERROR_MESSAGES[status] ?? 기본문구
   ▼
[인라인 <p className="errorMessage"> 출력]
```

## 부록 B. 참고 파일·라인

- 엔드포인트 호출: `src/service/api/auth.ts:74,82,87,95`
- 스텝 진행 핸들러(수정 지점): `src/app/(auth)/find-password/_components/FindPasswordContent.tsx:29-39`
- mutation/에러 state: `src/app/(auth)/find-password/_hooks/useFindPasswordFlow.ts:58-96`
- 전역 에러 메시지 맵: `src/lib/api/errorHandler.ts:28-35,48`
- interceptor 에러 주입: `src/lib/api/apiClient.ts:142-144`
- 인라인 에러 렌더: `FindPasswordEmailStep.tsx:33`, `FindPasswordSmsStep.tsx:42`, `VerificationCodeForm.tsx:54`
- 정규식: `src/app/constant/pattern.ts:1,3`
