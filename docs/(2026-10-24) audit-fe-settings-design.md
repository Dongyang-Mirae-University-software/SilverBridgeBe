# (2026-10-24) FE 환경설정 보호자·피보호자 디자인 반영(FE #133) 기능 점검

- 대상: FE PR #133 `design/settings` (커밋 `becec53`, 머지 `9424e32`, gosky 반영 2026-10-24)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 범위: PR 변경분 + 건드린 공용 코드의 사용처(알림 종류 query·mutation, 채널 설정 훅, SOS 설정 훅)
- 기준: FE develop `9424e32`, gosky FE `9424e32`, BE dev(설정 API 변경 없음)

## 결론

**PASS** - 🔴 0 · 🟠 0 · 🟡 2 · 🟢 4

필수 알림(SOS·이상 감지)은 화면에 끄는 수단이 없고, BE도 `sos:false` 요청을 무시한다(gosky 실측). 토글 저장, 저장 중 막기, 실패 시 되돌림, 카카오 계정 비밀번호 변경 숨김, 탈퇴 2단계가 동작한다. 실서버에서 저장 후 다시 조회해도 값이 유지된다. 변이 8종은 모두 테스트가 잡았다. 남은 것은 다음과 같다.

- 🟡 M-1(이번 PR): 이메일 스위치를 열면서 카카오 가상 이메일 계정도 켤 수 있게 됐다. 이 계정은 켜도 메일이 오지 않는다(거짓 설정).
- 🟡 M-2(원래부터): 설정을 불러오지 못하면 기본값을 실제 값처럼 보여 주고 그 상태로 저장할 수 있다.
- 🟢 L-1~L-4: 모두 원래부터 있던 표시·경합·접근성 문제다.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-10-24 |
| develop에 `9424e32` 포함 / gosky FE | 예 / `9424e32` (`/guardian/settings`·`/ward/settings` 200) |
| `npm test` / `npm run test:browser` | 289 통과 / 162 통과 |
| `tsc --noEmit` / `npm run build` | 통과 / 통과 |
| `npm run lint` | 오류 1건 - 범위 밖 `BirthDateSelects.tsx`(develop에 원래 있음) |

## PHASE 0. 대상

| 파일 | 역할 |
|---|---|
| `components/settings/AlertTypeSettingsSection.tsx` | 보호자 알림 종류 - SOS·이상 감지 "항상 받아요", 재촉·카메라 연결 끊김·복약 토글 |
| `service/query/guardian/notification.ts` | 알림 종류 저장 훅 - 화면 먼저 반영(onMutate)·실패 시 되돌림·성공 응답으로 캐시 갱신 |
| `components/settings/NotificationSettingsSection.tsx` | 알림 방법 4채널(알림톡·푸시·문자·이메일), 실패 토스트 |
| `components/settings/PasswordChangeSection.tsx` | 비밀번호 변경, 카카오 계정은 버튼 없음, 피보호자 해요체 |
| `components/settings/AccountDeleteSection.tsx` | 탈퇴 확인 → 본인 확인(비밀번호 / 카카오 "탈퇴"·"회원탈퇴") |
| `ward/settings/_components/WardBasicSettingsSection.tsx` | 글자 크기·진한 글자(기기 저장), SOS 동작 두 값 |

**공용 코드 사용처** (`grep`, 테스트 제외)

| 공용 코드 | 사용처 | 판정 |
|---|---|---|
| `guardianNotificationTypeSettingQueryOptions` | 설정 화면, **보호자 복약 카드**(`WardMedicationCard` - `medication.enabled === false`면 "복약 알림 꺼짐") | PASS - 화면 먼저 반영·되돌림이 같은 캐시라 복약 카드도 함께 바뀌고 함께 되돌아간다. 캐시 모양(`{enabled, required}`)은 그대로다 |
| `useUpdateGuardianNotificationTypeSettingMutation` | 설정 화면만 | PASS |
| `userNotificationSettingsQueryOptions`·`useNotificationSettingsMutation` | 설정 화면만(훅은 이번 PR에서 바꾸지 않음) | PASS |
| `wardSosSettingQueryOptions`·`useUpdateWardSosSettingMutation` | 피보호자 레이아웃 1회 동기화(`WardLayout`), 설정 화면 | PASS(경합은 L-2) |
| `PasswordChangeSection`·`AccountDeleteSection` | 보호자·피보호자 설정 화면만 | PASS |

## PHASE A. 보안·인가

화면이 부르는 API와 BE 인가는 다음과 같다(이번 PR로 BE 변경 없음).

| 호출 | 역할 | BE 인가·규칙 |
|---|---|---|
| `GET·PUT /api/guardian/notification-type-setting` | GUARDIAN | 본인 토큰 기준. `sos`·`anomalyDetection` 변경은 무시 |
| `GET·PUT /api/user/me/notification-settings` | 로그인 사용자 | 본인 토큰 기준 |
| `PUT /api/ward/sos-setting` | WARD | 본인 토큰 기준 |
| `PATCH` 비밀번호 변경 / `DELETE` 탈퇴 | 로그인 사용자 | 현재 비밀번호 / 카카오는 확인 문구. 사용자별 시도 잠금 |

- **IDOR**: 해당 없음 - 모든 설정 API가 경로·본문에 대상 ID 없이 토큰 주인만 다룬다. PASS
- **필수 알림 잠금**: 화면에 SOS·이상 감지 스위치가 없다(테스트·변이 M5로 고정). gosky에서 `PUT {sos:false}` → 200이지만 `sos`는 `{enabled:true, required:true}` 그대로였다. PASS
- **거짓 설정**: 정서 변화·병원 예약 토글이 없다(테스트로 고정). 단 이메일은 M-1
- **PII·로그**: 새 로그 없음. 탈퇴·비밀번호 실패는 서버 문구만 보여 준다. 채널 실패는 고정 문구 토스트다. 훅의 `reportNonApiError`는 원래부터 있었고 비 API 오류만 콘솔에 남긴다. PASS
- **탈퇴 본인 확인**: 확인 → 입력 2단계. 카카오는 앞뒤 공백을 지운 "탈퇴"·"회원탈퇴"만 보낸다(BE 허용값과 같음, `(2026-10-01)` 결정). 변이 M2·M8로 고정. PASS

## PHASE B. 기능 정합성

- **저장 동작**
  - 토글은 누르는 즉시 바뀐다.
  - 저장 중에는 같은 섹션의 다른 토글이 비활성화된다(변이 M4).
  - 5xx면 이전 값으로 돌아가고 토스트로 안내한다(변이 M1·M7). 피보호자 SOS도 저장 중에는 라디오가 비활성화된다.
- **실서버 저장 유지(gosky, 테스트 보호자 계정, 끝나고 원래 값으로 되돌림)**:
  - `cameraLinkLost` true → false: PUT 200, 다시 조회해도 false
  - `EMAIL` false → true: PUT 200, 다시 조회해도 true
- **채널 문구와 BE 동작 대조**

| 채널 | 화면 문구 | BE 동작 | 판정 |
|---|---|---|---|
| 알림톡 | 이상 감지 알림을 카카오톡으로 | 승인 템플릿이 보호자 이상감지뿐 | 맞음 |
| 푸시 | SOS·이상 감지는 꺼 두어도 항상 푸시 | `FORCED_PUSH_*` | 맞음 |
| 문자 | 켜면 문자로도 받고, SOS·이상 감지는 푸시 미전달 시 꺼 두어도 대체 | SOS 폴백·이상감지 대체(2026-10-06) | 맞음 |
| 이메일 | 연결·문의 답변·복약 확인 안내, SOS·이상 감지는 안 보냄 | `Email.ALLOWED` | 맞음. 단 카카오 가상 이메일 계정은 M-1 |

- **복약 문구**: "약 복용 체크가 안 된 날"이다("안 드시면" 아님, 미복용 단정 금지 규칙).
- **SOS 동작**: `CALL_119_AND_NOTIFY`·`NOTIFY_GUARDIAN_FIRST` 두 값만 보여 준다. "알림 없이" 표현이 없다(테스트로 고정).

## PHASE C. 구조·계약

- 알림 종류 훅의 onSuccess가 무효화(invalidate) 대신 **응답으로 캐시를 덮어쓴다**. 응답이 비면(`getResponseData` null) 화면에 먼저 반영한 값이 남는다. 저장은 성공한 상태라 맞는 값이다. PASS
- 응답에 `cameraLinkLost`가 없는 옛 서버에서도 화면 먼저 반영이 `{required:false, ...}`로 채워져 깨지지 않는다. PASS
- **#114**: 숨김 input은 positioned 부모(`.toggle`·`.contrastRow`) 안에 겹쳐 있다. 375px에서 `scrollHeight == innerHeight`, 가로 넘침 없음(변이 M6).

## PHASE D. 테스트

새 테스트는 15건이다(`settings.browser.test.tsx` 11 · `WardBasicSettingsSection.browser.test.tsx` 4). 변이 검사 결과는 아래와 같다. 원본은 15/15 통과였고, 각 변이는 테스트 1건씩이 실패해 모두 검출됐다.

| # | 변이 | 결과 |
|---|---|---|
| M1 | 알림 종류 실패 시 되돌림 제거 | 검출 |
| M2 | 카카오 "탈퇴" 입력 거부 | 검출 |
| M3 | 카카오 계정에 비밀번호 변경 버튼 노출 | 검출 |
| M4 | 채널 저장 중 막기 제거 | 검출 |
| M5 | SOS 끄는 스위치 부활 | 검출 |
| M6 | 숨김 input을 토글 밖(아래 900px)으로 | 검출 |
| M7 | SOS 저장 실패 시 되돌림 제거 | 검출 |
| M8 | 카카오 확인 문구 공백 제거 삭제 | 검출 |

## 이슈

### 🟡 M-1 이메일 스위치가 카카오 가상 이메일 계정에도 열려 있다 (이번 PR)

- **현상**: 이메일이 없는 카카오 가입자는 서버가 `kakao_숫자@kakao.com`을 부여한다. 이 주소는 BE `EmailNotificationChannel`이 보내지 않는다(대상 아님, 이력 없음). 이번 PR이 "준비 중"을 풀면서 이 계정도 스위치를 켤 수 있고, "가입한 이메일로 보내 드려요"라는 안내만 보고 켜게 된다. 켜도 아무것도 오지 않는다(거짓 설정).
- **조치 제안**: 프로필 이메일이 `^kakao_\d+@kakao\.com$`(대소문자 무시, BE와 같은 패턴)이면 이메일 스위치를 비활성화하고 "카카오로 가입해 받을 이메일 주소가 없어요"를 표시한다. 테스트는 가상 이메일 프로필에서 비활성·문구, 일반 이메일에서 활성.

### 🟡 M-2 설정을 불러오지 못하면 기본값을 실제 값처럼 보여 준다 (원래부터)

- **현상**:
  - 알림 종류는 조회가 실패하면 `DEFAULT_SETTING`(전부 켜짐)을 그린다.
  - 채널은 빈 목록을 "푸시만 켜짐"으로 그린다.
  - 둘 다 토글을 누를 수 있다.
  - 실제로 꺼 둔 재촉이 켜진 것처럼 보이고, 누르면 의도와 반대로 저장될 수 있다("모르는 값을 아는 값처럼 보이지 않는다" 원칙 위반).
- **조치 제안**: `isError`면 토글 대신 "설정을 불러오지 못했어요" + 다시 시도 버튼을 보여 준다(필수 알림 "항상 받아요"는 서버 값과 무관해 그대로 둬도 된다).

### 🟢 L-1 프로필을 불러오는 동안·실패하면 카카오 계정이 일반 계정 흐름으로 보인다 (원래부터)

`isKakaoUser = profile?.provider === 'KAKAO'`라서 프로필이 없으면 false다. 그 사이 카카오 사용자에게 비밀번호 변경 버튼과 탈퇴 비밀번호 입력이 보이고, 진행하면 BE가 거절한다. 프로필이 올 때까지 계정·보안 탭의 버튼을 비활성화하면 된다.

### 🟢 L-2 SOS 설정 첫 조회가 저장 중에 도착하면 화면이 옛 값으로 덮일 수 있다 (원래부터, 드묾)

`WardLayout`은 첫 조회 응답으로 한 번만 동기화한다. 사용자가 그 전에 값을 바꾸면 늦게 온 옛 응답이 화면 값을 덮을 수 있다. 서버에는 새 값이 저장되고 새로고침하면 맞아진다. 동기화 전에 사용자가 고친 값이 있으면 건너뛰면 된다.

### 🟢 L-3 비밀번호 입력에 `autoComplete`가 없다 (원래부터)

변경 폼의 세 칸에 `current-password`/`new-password`가 없어 브라우저 비밀번호 관리자가 새 비밀번호를 저장하지 못한다.

### 🟢 L-4 탈퇴 모달에 포커스 가둠·Esc 닫기가 없다 (원래부터)

`alertdialog`/`dialog` 역할과 제목 연결은 이번에 붙였다. 포커스 이동 제한과 Esc는 없다(문의 상세 모달 L-4와 같은 종류).

## 수정용 커밋 메시지 초안 (FE)

```
fix: 카카오 가상 이메일 계정은 이메일 알림을 켤 수 없게 막음 (M-1)
fix: 알림 설정을 불러오지 못하면 기본값 대신 다시 시도 안내 (M-2)
fix: 계정·보안 탭은 프로필 확인 뒤에 버튼 활성화 (L-1)
```

## 조치 결과 (2026-10-25)

6건 모두 FE #141(`1a895ce`, 머지 `6d7a35f`)로 반영했다. gosky FE `6d7a35f`, `/guardian/settings`·`/ward/settings` 200.

| # | 조치 |
|---|---|
| M-1 | 프로필 이메일이 `^kakao_\d+@kakao\.com$`이면 이메일 스위치를 꺼진 채로 막고 이유 표시 |
| M-2 | 조회 실패(`isError && !data`)면 공용 `SettingsLoadError`(다시 시도)를 토글 대신 표시 - 알림 종류·알림 방법 |
| L-1 | `isAccountReady = Boolean(profile)` 전에는 비밀번호 변경·탈퇴 버튼 비활성 |
| L-2 | `hasUserSavedWardSosSetting` - 진행 중·성공한 저장이 있으면 첫 동기화를 건너뜀(실패한 저장은 세지 않음) |
| L-3 | `autoComplete` current-password / new-password |
| L-4 | 공용 `useModalDialog`로 포커스 가두기·Esc·바깥 클릭(처리 중엔 무시), 첫 포커스 "취소", 닫으면 탈퇴 버튼으로 복귀 |

테스트: 브라우저 +8·단위 +4. 각 수정을 되돌리면 해당 테스트가 실패(7/7). `npm test` 307 · `test:browser` 186 · `tsc` · `build` 통과.
