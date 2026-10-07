# AI 챗봇 백엔드 중계 (2026-10-07)

## 배경

2026-10-07 FE 보안 수정(ad44cb6)이 `/api/streams` 프록시를 송출 3개 경로로 줄이면서 챗봇이 막혔다
(`POST /api/streams/v1/chat` 404, `GET .../v1/chat/logs` 405). 챗봇 서버·AI 서버는 정상이었다.
영상 중계(2026-10-03)와 같은 방식으로 **FE → 백엔드 → AI** 중계 API를 만들었다. FE 프록시는 건드리지 않는다.

## PHASE 0에서 확인한 drift

| 전제 | 실제 |
|---|---|
| FE가 보내는 userId | 전송은 `userId: 1` **고정**, 목록 조회만 `profile.id` |
| AI의 기존 기록 userId | 실서버 `chat_logs` = `"1"` 112건 + 테스트 `990001~4`. 실제 `users.id`로 저장된 기록 0건 |
| 챗 사용 역할 | 보호자만(피보호자 화면은 빈 껍데기) |
| AI 대기 한도 | 실서버 `CHAT_UPSTREAM_TIMEOUT_SEC=120`(코드 기본값은 1200) |
| AI 오류 본문 | `{"detail":{success,message,errorCode,data}}` (FastAPI HTTPException) |

## 범위·정책

- **경로**(보호자 전용, `@PreAuthorize("hasRole('GUARDIAN')")`): `POST /api/guardian/chat`, `GET /api/guardian/chat/logs`,
  `GET /api/guardian/chat/logs/{chatId}`. 새 경로는 인증 필수(permitAll 추가 없음).
- **사용자 ID는 토큰에서만**: 요청 DTO에 `userId` 필드가 없고(`@JsonIgnoreProperties(ignoreUnknown)`) AI 본문의 `userId`는
  마지막에 토큰 ID로 덮어쓴다. 기록 목록·상세도 토큰 ID로만 AI에 묻는다. 기존 `"1"` 기록은 이전하지 않는다.
- **남의 기록 상세**: AI가 없는 기록과 남의 기록을 구분하지 않고 404를 주므로 `404 CHAT_LOG_NOT_FOUND`로 같게 답하고
  `[CHAT-LOG-NOT-FOUND]` INFO(userId·chatId만)를 남긴다. `[IDOR-ATTEMPT]` WARN은 구분이 불가능해 쓰지 않았다.
- **응답**: 공용 `ApiResponse.ok(data)`, 전송은 AI 응답의 `data` 그대로(언래핑 1회), **기록 목록·상세는 허용 목록 필드만**(`ChatLogProjection`, 2026-10-07 점검 M-2 - `contextJson`·`upstreamMeta`·`decisionTrace`·`userId` 제외).
- **호출 제한**: 전송 전체 130초(AI 120초 + 여유, 마감 시 연결을 끊는다), 기록 조회 15초. 일반 외부 호출 규칙(8~10초)의 예외.
- **상한**: 동시 전송 전체 10 / 1인 1(인스턴스 메모리, 초과 429 `CHAT_LIMIT_EXCEEDED`), 속도 제한 전송 분 10·시간 120,
  기록 조회 분 30·시간 600(Redis 장애 시 fail-open). 메시지 2000자, history 24개, context 8KB.
- **킬 스위치** `chat.relay.enabled`(`CHAT_RELAY_ENABLED`): 꺼도 **전송만** 503, 기록 조회는 계속(조회는 위험이 아님).
- **민감 정보**: 메시지·응답·기록 본문은 로그·예외 메시지에 남기지 않는다(userId·소요 시간·상태 코드·예외 클래스명까지).
  AI 오류 문구는 읽지 않고 상태 코드로만 판단해 고정 문구로 답한다. 응답은 `Cache-Control: no-store`.
  `ChatRelayLogGuardTest`가 chat 패키지 로그 인자에 본문·키가 들어가면 실패시킨다.
- **AI 키**: `X-API-Key` 헤더만, 리다이렉트 미추종. 이상감지·영상과 같은 `AI_API_KEY`·`AI_HTTP_BASE_URL`을 쓴다(신규 env 불필요).

## 오류 매핑

| AI | 백엔드 |
|---|---|
| 연결 실패·5xx·401/403·키 미설정·응답 깨짐 | 503 `CHAT_UNAVAILABLE` |
| 호출 마감 초과 | 504 `CHAT_TIMEOUT` |
| 400·422(전송) | 400 `CHAT_INVALID_REQUEST` |
| 404(기록 상세) | 404 `CHAT_LOG_NOT_FOUND` |
| (백엔드) 동시 상한 | 429 `CHAT_LIMIT_EXCEEDED` |
| (백엔드) 속도 제한 | 429 `TOO_MANY_REQUESTS` + `Retry-After` |

## 변경 파일

- 신규 `domain/chat/{client/AiChatClient, config/ChatRelayProperties, controller/GuardianChatController, dto/ChatRelayRequest, service/ChatRelayService, service/ChatSlots}`
- 수정 `ErrorCode`(CHAT_* 5종), `application.yaml`(`chat.relay.*`)
- 테스트: `AiChatClientTest`·`ChatRelayServiceTest`·`GuardianChatControllerSecurityTest`·`ChatRelayLogGuardTest`, `LogRawExceptionGuardTest` 목록 추가
- 마이그레이션 없음, 기존 camera 코드·SecurityConfig·STOMP 무변경

## 검증 가이드

1. 비로그인 → 401, 피보호자·관리자 토큰 → 403.
2. 보호자 토큰으로 `POST /api/guardian/chat` `{"message":"두통이 있어요","userId":"다른ID"}` → 200, AI `chat_logs.user_id`가 **토큰 ID**로 저장(다른 ID 아님).
3. `GET /api/guardian/chat/logs` → 본인 기록만. 남의 `chatId` 상세 → 404 `CHAT_LOG_NOT_FOUND`.
4. 응답 시간은 배포 후 1회 호출로 확인(예상: 모델 생성 수 초~수십 초).

## 테스트 결과

(PHASE 3 결과는 PR 본문에 원문으로 기록)
