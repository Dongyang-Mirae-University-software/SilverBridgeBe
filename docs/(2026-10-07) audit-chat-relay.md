# AI 챗봇 중계 기능 점검 (템플릿 B, 2026-10-07)

- 대상: PR #327(`37a5e0c`) `domain/chat/**` + `ErrorCode` CHAT_* + `application.yaml` `chat.relay.*`. 점검 기준 커밋 `7532efb`(dev).
- 방식: 코드 정독 + 실서버 AI 직접 호출(vkcs 서버 안에서 키 사용·출력 없음, 테스트 대화 1건 `AUDIT-CHAT-TEST`) + 두 서버 api-docs·비로그인 응답 확인. **코드 수정 없음.**
- 한계: 보호자 토큰으로 `백엔드 → AI` 전체 경로를 실제로 호출한 확인은 **못 했다**(테스트 계정 자격 증명이 없음). 이 구간은 AI 직접 호출 + `AiChatClientTest`(실제 HTTP 임시 서버) + 두 서버의 경로 등록으로 갈음했다. FE 교체 후 첫 호출 때 확인할 것.

## 종합 판정: PASS (🔴 0 · 🟠 0 · 🟡 2 · 🟢 5)

## PHASE 0 - 대상·엔드포인트 × 역할

| 엔드포인트 | GUARDIAN | WARD | ADMIN | 비로그인 |
|---|---|---|---|---|
| `POST /api/guardian/chat` | 허용 | 403 | 403 | 401(두 서버 실측) |
| `GET /api/guardian/chat/logs` | 허용 | 403 | 403 | 401(두 서버 실측) |
| `GET /api/guardian/chat/logs/{chatId}` | 허용 | 403 | 403 | 401 |

- 두 서버(vkcs·gosky) api-docs에 3개 경로 등록 확인. `SecurityConfig` `anyRequest().authenticated()`가 받치고, permitAll 추가 없음.
- 전역 `UserRateLimitFilter`(사용자별 분당 제한)가 추가로 걸린다.

## PHASE A - 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 역할 인가 | PASS | 클래스 레벨 `@PreAuthorize("hasRole('GUARDIAN')")`, `GuardianChatControllerSecurityTest`가 WARD·ADMIN 403 고정 |
| IDOR(남의 기록 조회) | **PASS** | DTO에 `userId` 없음 + `@JsonIgnoreProperties`, AI 본문 `userId`는 마지막에 토큰 ID로 덮어씀, 기록 조회는 토큰 ID만 사용. 테스트: 본문·context 안의 userId 모두 무시 |
| AI 소유자 검증(중계가 의존) | PASS | 실서버 실측: 본인 목록 1건 / 다른 userId 목록 0건 / 상세 본인 200 · 타인 404 / 목록 userId 없음 422 |
| 키 노출 | PASS | `X-API-Key` 헤더만, 로그·응답·URI에 없음(테스트로 URI 검증), 리다이렉트 미추종 |
| 본문 로그 | PASS | chat 로그 문장 전수 확인(userId·소요 시간·상태 코드·예외 클래스명·chatId만). `ChatRelayLogGuardTest`·`LogRawExceptionGuardTest` 대상. 검증 실패 로그(`GlobalExceptionHandler`)는 필드 기본 메시지만 남기고 입력 값은 안 남김 |
| 오류 응답 | PASS | AI 오류 문구를 읽지 않고 상태 코드로만 판단, 고정 문구 응답 |
| 인증 저장소 장애 | 해당 없음 | 일반 경로 503 정책 그대로(SOS 외 fail-open 없음) |

### 🟡 M-1 AI 상세 조회는 `userId`가 선택이다 (AI 서버, 범위 밖 - 권고만)
- 실측: `GET /api/v1/chat/logs/{id}`를 `userId` 없이 부르면 **200**(`CHAT_REQUIRE_USER_ID=false`). `chat_id`는 자동 증가 번호라 추측 가능하다.
- 중계 경로는 항상 `userId`를 붙이므로 **백엔드 경유로는 노출되지 않는다.** 위험은 AI 서버 키를 가진 직접 호출뿐이다(FE 프록시에서 챗 경로가 사라져 FE 쪽 경로는 없다).
- 권고: AI 서버 `CHAT_REQUIRE_USER_ID=true`. 켜면 `userId` 없는 호출이 422가 되므로 다른 호출자(게임·QA 스크립트 등)가 없는지 확인 후 적용. 이번 범위 밖이라 변경하지 않았다.

### 🟡 M-2 기록 응답이 AI 내부 정보를 그대로 싣는다 (데이터 최소화)
- 실측 목록·상세 응답 키: `contextJson`(안에 `userContext` = 요청 때 보낸 이름·연락처 등 프로필, `upstreamMeta`·`decisionTrace` 포함), `decisionTrace`, `upstreamMeta`. FE는 이 중 `message·reply·engine·intent·tool·toolData·type·ui·createdAt`만 쓴다(`GuardianChatContent.tsx`).
- 본인 데이터가 본인 브라우저로 가는 것이라 직접 피해는 작지만, AI 내부 구조(`router` 등)와 프로필 사본이 불필요하게 나간다. 이전 프록시 경로도 같았다.
- 제안: 중계에서 기록 응답을 **허용 목록(allowlist)** 으로 걸러 위 9개 필드(+`id`·`sessionId`·`chatNo`·`riskLevel`·`recommendedAction`·`reservationRequired`)만 내린다. 전송 응답은 이미 필요한 필드뿐이라 해당 없음.

## PHASE B - 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 전송 응답 모양 | PASS | 실측 200, 12.7초, 키 `data.{reply,riskLevel,intent,engine,modelName,type,ui,tool,toolData,summary,...}` - FE `ChatResponse`와 일치(`toolData` 포함, 매핑 변경 불요) |
| 응답 시간 | 12.7초(`medgemma`) | 130초 제한 안. 첫 호출·부하 시 길어질 수 있어 FE 140초 권고 유지 |
| 입력 한도 | PASS | message 2000(경계 2001 거절 테스트)·history 24·context 20키/8KB·sessionId 64 |
| `uiSelection`만 있는 전송 | PASS | 단위 테스트(빈 message로 AI에 전달). AI가 빈 message를 받는지는 AI 스키마 기본값 `""`로 확인했으나 실호출은 안 해 봄 |
| 동시 상한 | PASS | 1인 1 / 전체 10, 실패·예외에도 자리 반환 테스트 |
| 킬 스위치 | PASS | 전송만 503, 기록 조회 유지(의도) |
| 키 미설정 | PASS | 503 `CHAT_UNAVAILABLE`, AI를 호출하지 않음 |

## PHASE C - 구조·계약

| 항목 | 결과 | 비고 |
|---|---|---|
| 응답 포맷·code | PASS | 공용 `ApiResponse`, 실패는 `code`. 상태코드 매핑은 계약 문서와 일치 |
| 트랜잭션·이벤트 | 해당 없음 | DB·이벤트 없음 |
| 호출 시간 제한 | PASS | 마감 시 연결을 끊음(영상 중계와 같은 방식), 크기 상한(응답 2MB·목록 10MB) |
| Swagger | PASS | 역할·오류 code·타임아웃 명시, 시크릿·내부 주소 없음 |
| 마이그레이션 | 없음 | - |

### 🟢 참고(수정 불요 또는 수용)
- **L-1 배포 중 진행 중인 챗은 끊긴다**: 우아한 종료 대기(기본 30초)가 챗 최대 130초보다 짧다. 배포 순간 응답을 기다리던 사용자는 오류를 본다. 영상처럼 종료 처리를 두지 않았고, 재시도하면 되므로 수용.
- **L-2 기록 목록은 페이지가 없다**: AI가 전체를 돌려주고 중계는 10MB에서 503으로 자른다. 현재 실사용 기록은 0건이라 무해. 사용자별 기록이 수천 건이 되면 AI 쪽 페이지네이션이 필요하다.
- **L-3 JSON 본문 크기는 파싱 뒤에 검사한다**: `context`의 값이 매우 크면 검증 전에 파싱된다(Jackson 문자열 기본 상한 약 20MB). 인증된 보호자만 도달하고 분당 10회로 제한돼 수용. 필요하면 요청 본문 크기 제한을 둔다.
- **L-4 속도 제한은 동시 상한 거절에도 소모된다**: 응답을 기다리는 중 재전송하면 429를 받고 분 한도 1회가 쓰인다. 영향 미미.
- **L-5 Swagger 경로 정렬 목록에 없다**: `SwaggerConfig` 정렬 목록에 chat 경로가 없다(camera·클립도 같음). 표시 순서만 영향.

## PHASE D - 테스트 공백(🟢)
현재 23개(클라이언트 8·서비스 10·보안 4·로그 가드 1). 보강 제안(모두 단위 테스트, 우선순위 낮음):
- `ChatRelayRequest` 검증 테스트: `history[].role` 잘못된 값·`content` 4000자 초과·`uiSelection` 빈 값·`context` 21키·`sessionId` 65자.
- `context` 8KB 경계(8192/8193바이트).
- `AiChatClient`: 기록 목록 빈 배열(`[]`) 정상 반환, 기록 상세 5xx → 503, 응답 크기 상한 초과 → 503.
- `ChatSlots` 병렬 획득(스레드 여러 개가 동시에 `acquire`해도 1인 상한을 넘지 않음).

## 실측 부작용 기록
- 점검 중 AI `chat_logs`에 테스트 행 1건(`userId=AUDIT-CHAT-TEST`, 두통 문의 1턴)이 남았다. 사용자 허용 범위(대화 1건)이며 삭제 API가 없어 그대로 둔다. 실사용자 기록과 섞이지 않는다.

## 수정 제안(합의 후 진행)
1. M-2 기록 응답 허용 목록(`ChatLogView` 변환) + 테스트 - 코드 변경, 소규모.
2. M-1 AI `CHAT_REQUIRE_USER_ID=true` - AI 서버 설정(재시작 필요하므로 별도 협의, 스트림 세션 영향 확인).
3. PHASE D 테스트 보강 - 후순위.

## 후속 (2026-10-07, 사용자 지시 "전부 반영")

| 항목 | 상태 | 내용 |
|---|---|---|
| M-2 기록 응답 허용 목록 | **반영**(PR) | `ChatLogProjection` 허용 목록 16개 필드, 형식 이상이면 503. `ChatLogProjectionTest`·`ChatRelayServiceTest`로 고정 |
| 테스트 보강(PHASE D) | **반영**(PR) | 요청 검증 6·context 8KB 경계·message 2000자 경계·클라이언트(빈 배열·5xx·크기 상한)·`ChatSlots` 병렬 2. chat 테스트 23 → 43개 |
| M-1 AI `CHAT_REQUIRE_USER_ID=true` | **적용 완료**(2026-10-07 17:32, gosky AI 서버, 사용자 실행) | `.env` 추가 + 컨테이너 재생성, 백업 `.env.bak--chat-require-user`(날짜 누락 이름). 실측: `userId` 없는 상세 조회 200 → **422**, 본인 상세 200·타인 상세 404·본인 목록 200 그대로. 백엔드 AI WS는 재시작 중 접속 시도 실패 후 자동 재연결 |

### M-1 적용 기록
- 자동 권한 판정이 에이전트의 `.env` 수정·재시작을 거부해 **사용자가 직접 실행**했다(명령 입력 중 `date`와 `+%Y…` 사이 공백이 빠져 백업 이름이 `.env.bak--chat-require-user`가 됐다 - 내용은 이전 `.env` 그대로, 필요하면 이름만 바꾼다).
- `.env`는 `env_file`이라 재생성(`up -d`)으로 반영했다. 재시작으로 스트림 세션은 사라졌고(사용자 확인: AI 서버 사용처 없음) 백엔드 AI 구독은 자동 재연결됐다.
- 롤백: `.env`에서 `CHAT_REQUIRE_USER_ID=true` 줄을 지우고 `docker compose up -d ai-server`.
