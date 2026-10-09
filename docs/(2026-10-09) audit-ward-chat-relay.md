# 피보호자 AI 챗봇 중계 기능 점검 (템플릿 B, 2026-10-09)

- 대상: PR #339(머지 `f233c6e`) `WardChatController` + `ChatRelayService`·`ChatSlots` 공유부. 점검 기준 커밋 `f233c6e`(dev).
- 방식: 코드 정독 + 두 서버 비로그인 응답·api-docs 확인 + AI 서버 소스 대조. 코드 수정 없음.
- 한계: **피보호자 계정으로 하는 실서버 호출은 하지 못했다.** gosky 테스트 변수 `TEST_WARD_*`·`TEST_GUARDIAN_*` 두 계정의 실제 역할이 모두 GUARDIAN이다(`/api/user/me`로 확인). 새 경로가 배포된 것은 보호자 토큰의 403(404 아님)과 api-docs 등록으로 확인했다. vkcs-linux는 도메인이 없어 api-docs·실호출은 하지 않았고 `dmu-dev-api` healthy와 커밋 반영만 확인했다.

## 종합 판정: PASS (🔴 0 · 🟠 0 · 🟡 1 · 🟢 4)

## PHASE 0 - 엔드포인트 × 역할

| 엔드포인트 | WARD | GUARDIAN | ADMIN | 비로그인 |
|---|---|---|---|---|
| `POST /api/ward/chat` | 허용 | 403(gosky 실측) | 403(테스트) | 401(gosky 실측) |
| `GET /api/ward/chat/logs` | 허용 | 403(테스트) | 403(테스트) | 401(gosky 실측) |
| `GET /api/ward/chat/logs/{chatId}` | 허용 | 403(테스트) | 403(테스트) | 401(gosky 실측) |
| `/api/guardian/chat*` 3개 | 403(테스트) | 허용 | 403(테스트) | 401 |

- gosky api-docs에 `/api/ward/chat` 3개 경로 등록(태그 `피보호자 - AI 챗봇`, 응답 코드는 보호자와 동일). 보호자 경로 3개는 그대로.
- `SecurityConfig`의 `anyRequest().authenticated()`가 받치고 permitAll 추가는 없다. 전역 `UserRateLimitFilter`는 챗 경로를 제외하지 않아 그대로 적용된다.

## PHASE A - 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 역할 인가 | PASS | 클래스 레벨 `@PreAuthorize("hasRole('WARD')")`, `WardChatControllerSecurityTest`가 GUARDIAN·ADMIN 403과 WARD 허용 고정 |
| IDOR(남의 기록) | PASS | 컨트롤러는 `@AuthenticationPrincipal`의 사용자 ID만 서비스에 넘긴다. DTO에 userId 없음, AI 본문 userId는 토큰 ID로 덮어씀, 기록 조회도 토큰 ID만 사용. 남의 chatId는 AI가 404(2026-10-07 실측) |
| 보호자·관리자의 피보호자 기록 열람 경로 | PASS | `domain/chat` 밖에서 챗을 참조하는 코드가 없고(`ErrorCode` 제외), 관리자 컨트롤러·서비스에 챗 연동이 없다. AI에서 소유자 없이 조회하는 경로는 `CHAT_REQUIRE_USER_ID=true`로 막혀 있다(gosky) |
| ID 공간 충돌 | PASS | `users.id`가 한 테이블의 PK라 보호자·피보호자 ID가 겹치지 않는다. 한도·동시 자리는 사용자 ID 키 |
| 본문 로그 | PASS | 컨트롤러에 로그 없음. 서비스 로그는 userId·소요 시간·chatId만. `ChatRelayLogGuardTest`가 chat 패키지 전체를 검사하므로 새 컨트롤러도 포함 |
| 응답 필터 | PASS | 기록 응답은 `ChatLogProjection` 허용 목록으로 걸러 AI 내부 정보(contextJson·userContext 등)를 내리지 않는다. 두 역할 공통 |
| `context` 전달 | PASS(수용) | `role`·`guardianId`는 클라이언트 값 그대로 AI에 간다. AI는 `ctx`에 담아 모델 입력으로만 쓰고 분기에 쓰지 않는다(`chat_service.py` 297~302). 권한 판단 근거가 아니다 |

## PHASE B - 기능 정합성

- 보호자·피보호자가 같은 서비스를 쓰므로 응답 스펙·오류 code·한도(분 10/시간 120/동시 1)가 같다. `ChatRelayServiceTest`의 역할 간 격리 테스트가 한도 키와 자리가 사용자별로 따로 세어지고 AI에 각자 토큰 ID만 간다는 것을 고정한다.
- AI의 `recommendedAction=guardian_contact`는 메시지 키워드로 정해지는 기계용 코드이고 역할과 무관하다(FE는 저장만 함). 사용자에게 보이는 "보호자" 문구는 폴백 응답의 위험도 중간 문구뿐이다(L-4, 조치됨).

## PHASE C - 구조·계약

- 컨트롤러는 서비스 호출과 `Cache-Control: no-store`만 하는 얇은 구조이고 `@Transactional`·이벤트·DB가 없다. 로직 복제 없음(PASS).
- 반환 타입이 `ApiResponse<Object>`이고 `GuardianChatControllerHttpTest`의 Jackson 2 가드에 `WardChatController`가 포함돼 있다. `WardChatControllerHttpTest`가 실제 HTTP 직렬화(문자열·배열·객체)를 고정한다.
- Swagger: 태그·`@Operation`·`@ApiResponses`가 보호자와 같은 형태이고 gosky api-docs 응답 코드가 일치한다.

## PHASE D - 테스트

- 새 컨트롤러의 권한·직렬화·반환 타입 가드, 서비스의 역할 간 격리가 있다. 남은 공백은 L-1(실서버 피보호자 호출)뿐이다.

## 이슈

### 🟡 M-1. 탈퇴해도 AI 서버의 상담 기록이 남는다 (보호자 경로부터 존재, 피보호자로 확대)
- **조치(2026-10-09)**: AI 서버 `DELETE /api/v1/chat/logs`(PR #10) + 백엔드 `ChatLogPurgeListener`로 해소. 상세 `docs/(2026-10-09) feature-chat-log-purge-on-withdraw.md`. 이미 탈퇴해 남은 기존 기록은 지우지 않는다.
- 근거: 탈퇴 리스너(`UserWithdrawnEvent` 소비자)에 챗 정리가 없고, AI 챗 라우터(`chat_router.py`)에도 삭제 엔드포인트가 없다(POST·GET 목록·GET 상세뿐).
- 영향: "탈퇴 = hard delete, 탈퇴자가 남긴 데이터를 붙들지 않는다"와 어긋난다. 피보호자 경로가 열리면서 고령 사용자의 건강 상담이 대상에 들어가 민감도가 커졌다. 사용자 ID는 6자 영숫자라 같은 ID로 재가입할 확률은 사실상 없고, 백엔드 경유로는 남의 기록을 볼 수 없다(노출이 아니라 잔존 문제).
- 조치안: AI 팀에 `DELETE /api/v1/chat/logs?userId=` 요청 -> 백엔드에서 탈퇴 AFTER_COMMIT 리스너(`REQUIRES_NEW`, 실패는 삼킴)로 호출. 또는 보관 기간 정책을 정해 AI에서 만료 삭제. 정책 결정이 먼저 필요하다(사용자 결정 사항).

### 🟢 L-1. 실서버에서 피보호자 계정 호출을 확인하지 못했다
- gosky 테스트 변수가 모두 GUARDIAN 계정이다. WARD 역할 테스트 계정이 `~/.silverbridge-test.env`에 있어야 전송(`data.reply` 타입)·기록 목록·격리를 확인할 수 있다. 코드·단위 테스트로는 모두 고정돼 있다.

### 🟢 L-2. 서버 전체 동시 상한 10건을 두 역할이 나눠 쓴다
- 전송 1건이 요청 스레드를 최대 130초 붙들어 상한을 둔 것이고 인스턴스 메모리 기준이다. 피보호자 이용이 늘면 보호자가 `CHAT_LIMIT_EXCEEDED`(429)를 더 자주 볼 수 있다. 현재 사용량에서는 문제 아님. `[CHAT-LIMIT] 서버 전체` WARN이 늘면 `CHAT_RELAY_MAX_CONCURRENT`를 본다(톰캣 스레드 여유 확인 후).

### 🟢 L-3. 역할이 바뀌면 같은 사용자 ID의 옛 기록이 새 경로에서 보인다
- 관리자 역할 변경(보호자 <-> 피보호자)은 사용자 ID를 유지하므로 옛 상담 기록이 새 역할의 경로에서 조회된다. 본인 기록이라 타인 노출은 아니다. 조치 불요.

### 🟢 L-4. 폴백 응답의 "보호자와 상태를 공유" 문구가 보호자 본인·보호자 없는 피보호자에게 맞지 않는다
- **정정(2026-10-09)**: 처음 "보호자에게 연락 안내가 나갈 수 있다"고 쓴 것은 범위를 넓게 본 것이었다. 확인 결과 사용자에게 보이는 "보호자" 문구는 AI 서버 키워드 폴백 응답(모델이 답을 못 만들 때)의 위험도 중간 문구 한 곳뿐이다(`chat_service.py`). `recommendedAction=guardian_contact`는 화면 문구가 아니라 기계용 코드이고 FE는 저장만 한다. AI 모델 프롬프트에는 "보호자"가 없다.
- **조치**: 문구를 "가족이나 가까운 분과 상태를 공유하고"로 변경(AI 서버 PR #12 머지, `a0c0f20`). `recommendedAction` 값은 API 계약이라 유지. **서버 반영은 AI 서버 재시작 시점에 진행 예정**(성능 테스트 종료 후, 사용자 결정).
- FE 안내: 나중에 `recommendedAction`으로 "보호자에게 연락" 버튼을 만들면 피보호자 화면에서는 연결된 보호자가 있을 때만 보여 줄 것(Notion 전달 페이지에 기재).

## 수정용 커밋 메시지 초안 (M-1을 진행하기로 결정하면)
- `feat: 회원 탈퇴 시 AI 챗 상담 기록 삭제 요청` (AI 삭제 엔드포인트 선행 필요)
