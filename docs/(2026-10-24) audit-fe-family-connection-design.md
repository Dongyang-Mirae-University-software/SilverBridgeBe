# (2026-10-24) 점검 - FE 가족 화면(내 가족·연결된 가족·가족 등록) 디자인 적용

- 대상: SilverBridgeFe PR #134 (`5081a32`, 머지 `301bf6f`), 점검 기준 develop `da48514`
- 템플릿: B(기능 점검) - 이 기능 + 건드린 공용 코드 사용처
- 방법: 독립 에이전트(sonnet)가 변경분을 따로 리뷰 → 메인이 주장마다 코드로 재확인, 공용 코드 사용처는 메인이 직접 조사. 실서버 흐름은 구현 PHASE 3에서 gosky 테스트 계정으로 확인함(아래 "실서버 근거")
- 결론: **조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 2 · 🟢 7 (코드 수정 없음)

## 점검 범위

| 구분 | 파일 |
|---|---|
| 피보호자 내 가족 | `src/app/(ward)/ward/guardians/**` (`WardGuardiansPanel`·`WardGuardianPendingSection`·`WardGuardianActiveSection`) |
| 보호자 연결된 가족·가족 등록 | `src/app/(guardian)/guardian/wards/**` (`GuardianWardsListPanel`·`GuardianConnectionRequestForm`·`GuardianConnectionRequestHistory`) |
| 공용 | `src/utils/connection/connectionView.ts`, `src/utils/format/korean.ts`(`getDirectionParticle`), `src/service/query/guardian/connection.ts`, `src/lib/realtime/connectionSocket.ts`, `src/app/(ward)/ward/_components/WardLayout.tsx`, `src/constants/dashboard.ts` |

## 화면 × 역할 × BE 엔드포인트

| 동작 | 역할 | 엔드포인트 | 확인 |
|---|---|---|---|
| 요청 | 보호자 | `POST /api/guardian/connection/request` | 계약 일치, 오류 `code` 분기 |
| 요청 취소 | 보호자 | `DELETE /api/guardian/connection/cancel/{id}` | 계약 일치(이번에 화면 추가) |
| 목록·대기 | 보호자 | `GET .../select`, `GET .../requests` | ACTIVE만 카드, PENDING만 대기 목록 |
| 해제 | 보호자 | `DELETE .../disconnection/{id}` | 확인 창 |
| 받은 요청 | 피보호자 | `GET /api/ward/connection/pending` | 가린 번호가 와도 화면에 안 씀 |
| 수락·거절 | 피보호자 | `POST .../{id}/accept`, `DELETE .../request/{id}/refusal` | 확인 창 없음(디자인) |
| 해제 | 피보호자 | `DELETE /api/ward/connection/disconnection/{id}` | 확인 창 |
| 취소 신호 | 피보호자 | WS `/topic/{userId}/connection-request-cancelled` | 토스트 없이 목록 무효화 |

인가 판정은 BE 몫이고 FE는 역할 경로(`(ward)`·`(guardian)`)와 토큰 헤더만 쓴다 - 이 PR이 인가 경로를 바꾸지 않았다.

## PASS 항목

- **수락 전 연락처 비노출**: 피보호자 대기 카드는 이름·관계만, 보호자 대기 목록은 회원 ID·관계·요청일만, 보호자 카드는 `status === 'ACTIVE'`만. 테스트가 "010" 미표시를 고정.
- **빈 값**: "정보 없음"으로 채우지 않고 줄을 뺀다(`getGuardianRows`·`getWardRows`).
- **오류 분기**: `response.data.code`로만(`getConnectionErrorCode`), 429는 `data.retryAfterSeconds` 우선. `apiClient`가 AxiosError의 `response`를 유지함을 확인.
- **회원 ID**: BE `UserIdGenerator.ID_LENGTH = 6`·`@Size(min=6,max=6)`과 FE 6자리 검사 일치, 대소문자 변환 없음, `maxLength` 대신 정리 함수가 잘라 붙여 넣기 보존.
- **relation 방향**: 보호자 화면 "김영희의 아들"(보호자 기준 문장), 피보호자 화면 "박민수 (아들)"(그대로 맞음), 대기 목록 "아들로 요청". 조사 `getDirectionParticle`은 ㄹ 받침을 "로"로 처리(테스트 고정).
- **취소 무알림**: `connection-request-cancelled`는 WARD만 구독하고 `WardLayout`이 `careai:push`를 보내지 않고 `wardConnectionsQueryKey`만 무효화. 보호자는 구독하지 않음(테스트 고정).
- **무효화 범위**: 보호자 요청(onSuccess)·취소(onSettled)·해제가 `guardianConnectionsQueryKey` 접두로 `/select`·`/requests`를 함께 갱신. 같은 키를 쓰는 보호자 대시보드·실시간 화면도 갱신된다. 피보호자 수락·거절·해제는 `wardConnectionsQueryKey` 접두로 active·pending·합본을 갱신.
- **중복 클릭**: 제출(`!isPending`)·취소(전 행 비활성)·수락/거절(서로 합쳐 비활성)·해제 확인(비활성) 모두 막힘.
- **레이아웃**: 375px 가로 넘침 없음(브라우저 테스트), `position: fixed`는 오버레이뿐(absolute 없음), 피보호자 버튼 52~68px·글씨 17px 이상.
- **BE 엔드포인트 계약**: 8개 모두 `GuardianConnectionController`·`WardConnectionController` 매핑과 일치.

## 이슈

### 🟡 M-1 요청 취소가 실패하면 빈 "수락을 기다리는 중" 칸과 오류 문구가 남는다
- 위치: `GuardianConnectionRequestHistory.tsx` - `if (pendingConnections.length === 0 && !errorMessage) return null;`
- 재현: 보호자가 "요청 취소"를 누르는 사이 피보호자가 수락·거절 → BE 409(`CONNECTION_NOT_PENDING`) → `errorMessage` 설정 → `onSettled` 재조회로 PENDING 0건 → 오류 때문에 칸이 남아 제목 + 서버 문구만 보인다. 다른 행 취소가 성공해도 이전 오류는 다음 취소를 누를 때까지 남는다.
- 제안: 오류가 칸을 붙들지 않게(0건이면 칸 대신 한 줄 안내 후 자동으로 지움), 성공·재조회 때 오류를 지운다. 409면 "이미 수락되었거나 거절된 요청이에요"처럼 `code`로 안내.

### 🟡 M-2 확인 창이 열린 채 목록이 바뀌면 낡은 대상을 들고 있다
- 위치: `WardGuardianActiveSection.tsx`(`confirmTarget` + 빈 상태 early return), `GuardianWardsListPanel.tsx`(`confirmTarget`)
- 재현(피보호자): 연결 끊기 창이 열린 동안 마지막 보호자가 먼저 해제 → 빈 상태 early return으로 창은 안 보이지만 `confirmTarget`은 남음 → 나중에 새 보호자가 연결되면 누르지도 않은 이전 사람의 확인 창이 뜬다.
- 재현(공통): 상대가 먼저 해제한 뒤 "끊기/연결 해제"를 누르면 BE 409(`CONNECTION_NOT_ACTIVE`) 서버 문구가 그대로 나온다.
- 제안: 목록에서 `confirmTarget.id`가 사라지면 창을 닫는다(id로 다시 찾아 파생). 409는 "이미 연결이 해제됐어요"로 안내.

### 🟢 L-1 확인 창 포커스 트랩·포커스 복귀 없음
- `role="dialog"`·`aria-modal`·Escape·안전한 버튼 `autoFocus`는 있으나 Tab이 뒤 화면으로 새고 닫힌 뒤 트리거로 돌아가지 않는다. 공용 `CommonModal`도 같은 수준이라 프로젝트 공통 과제로 둔다.

### 🟢 L-2 보호자 연결 해제 뒤 그 피보호자의 다른 데이터 쿼리는 무효화되지 않는다 (원래부터)
- 해제는 연결 목록 키만 무효화한다. 대시보드 통합 API·복약·이상감지 쿼리는 다음 재조회까지 남고, 그때 BE가 403을 준다. 옛 화면도 같은 범위였다.

### 🟢 L-3 429인데 남은 시간을 모르면 "내일"이라고 단정
- `getConnectionRequestErrorMessage`: `retryAfterSeconds`·`Retry-After`가 없으면 "내일 다시 요청할 수 있어요". BE는 24시간 고정 창의 남은 TTL이라 몇 분 뒤일 수도 있다 → "잠시 뒤에"가 정확하다. BE가 항상 값을 주므로 실제로는 거의 안 탄다.

### 🟢 L-4 모바일에서 회원 ID 찾는 길 안내가 한 단계 빠짐
- 도움말 "어르신 화면에서 이름(프로필)을 누르면 보이는 회원 ID예요"는 맞지만(`ProfileModal` "회원 ID · …"), 모바일은 메뉴 버튼으로 사이드바를 먼저 열어야 이름 카드가 보인다. "메뉴를 열고 이름을 누르면"이 정확하다.

### 🟢 L-5 이번 재작성으로 쓰는 곳이 없어진 코드
- `components/connections/ConnectionCard.tsx`(+ CSS 454줄)의 `ConnectionCard`·`ConnectionList`, `ConnectionShared.tsx`의 `ConnectionStats`·`getActivePartnerValue`·`getPartnerPhoneValue`·`getConnectionStatusClass` - develop 전체에서 사용처 0건.
- 원래부터 쓰는 곳이 없던 것: `constants/dashboard.ts`의 `PAGE_TITLES`(이번에 이름만 맞춤), `pendingConnectionRequests.ts`의 `getPendingConnectionRequestItems`(세션 저장 대기 요청을 쓰기만 하고 읽는 화면 없음).
- 제거는 별도 정리 PR로(동작 영향 없음).

### 🟢 L-6 카드 부제 "김영희의 아들"이 제목 바로 아래라 김영희를 가리키는 것처럼 읽힐 여지
- 방향은 BE 규칙과 맞고 사용자 승인 사항이다. 더 분명하게 하려면 "나: 김영희의 아들" 또는 "내 관계 · 아들"처럼 주어를 드러낸다(취향 수준).

### 🟢 L-7 빠진 테스트
- 취소 실패(409) 시 표시(M-1), 확인 창이 열린 채 목록이 바뀌는 경우(M-2), 목록 조회 오류 상태 3곳, `WardLayout`의 `CONNECTION_REQUEST_CANCELLED` 무알림·무효화, `connectionId` 없는 취소 신호(현재 `removePendingConnectionRequest(NaN)`은 아무것도 지우지 않아 무해 - 가드가 있으면 더 명확).

## 실서버 근거 (구현 PHASE 3, gosky, 테스트 계정)

요청 200 → 중복 요청 409 `CONNECTION_ALREADY_EXISTS` → 피보호자 대기 목록 1건(관계 "아들", 번호 가림) → 보호자 쪽 대기 건 연락처·주소·이메일 null → 요청 취소 200 → 피보호자 대기 0건 → 다시 요청 → 수락 200(ACTIVE, 연락처 공개) → 피보호자 쪽 해제 200. 시험 전후 상태 동일(연결 없음). 화면 클릭 확인은 하지 않았고 브라우저 동작은 컴포넌트 테스트(12건)로 확인.

## 수정 프롬프트 초안 (승인 시)

```
[FE] 가족 화면 점검 후속 - M-1·M-2·L-3·L-4·L-7 (+ 원하면 L-5 정리)
보고 언어: 모든 PHASE 보고와 최종 보고는 한국어로
PHASE 0. 위 이슈를 develop 코드로 재확인
PHASE 1. 계획 승인
PHASE 2. [모델: sonnet]
  - M-1 취소 실패: 409 CONNECTION_NOT_PENDING → "이미 수락되었거나 거절된 요청이에요", 재조회·성공 시 오류 지움, 0건이면 칸 대신 한 줄
  - M-2 확인 창: confirmTarget을 id로 목록에서 다시 찾고 없으면 닫기, 해제 409 CONNECTION_NOT_ACTIVE → "이미 연결이 해제됐어요"
  - L-3 남은 시간 모르면 "잠시 뒤에", L-4 도움말 "메뉴를 열고 이름을 누르면"
  - (선택) L-5 쓰지 않는 ConnectionCard·ConnectionStats 등 삭제
PHASE 3. 테스트: M-1·M-2 재현 테스트(수정 전 실패 확인), 목록 오류 상태, WardLayout 취소 신호 무알림
PHASE 5. 마무리: 브랜치 → PR → 머지 → gosky 반영(pull) → 정리. 묻지 않고 진행
```
