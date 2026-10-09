# 탈퇴 시 AI 상담 기록 삭제 기능 점검 (템플릿 B, 2026-10-09)

- 대상: 백엔드 PR #340(`385d29f`) `AiMemberDataPurgeListener`·`AiChatClient.deleteLogs`·`AsyncConfig.aiPurgeExecutor`, AI 서버 PR #10(`9c301b1`) `DELETE /api/v1/chat/logs`. 점검 기준 dev `385d29f`, AI main `9c301b1`.
- 방식: 코드 정독 + 실서버(gosky) 임시 ID로 AI 구간 end-to-end 실행 + 두 서버 반영·기동 확인. 코드 수정 없음.
- 한계: **실제 회원 탈퇴를 시작부터 끝까지 돌리지는 못했다.** 일반 가입은 SMS 인증이 필요하고, QA의 계정 생성은 dev DB 직접 INSERT라 점검 규칙(DB 직접 조작 금지)에 어긋나 임시 계정을 만들지 않았다. 대신 구간별로 검증했다: ① 탈퇴 이벤트 발행(코드), ② 이벤트 → 리스너 배선(코드·단위 테스트, 실 컨텍스트 테스트 없음 = M-1), ③ 리스너 → AI 호출(HTTP 테스트), ④ AI 삭제(실서버 실측).

## 종합 판정: PASS (🔴 0 · 🟠 0 · 🟡 2 · 🟢 3)

## PHASE 0 - 대상

| 구성 | 위치 | 비고 |
|---|---|---|
| 탈퇴 이벤트 발행 | `UserService.withdraw`(203행)·`forceWithdraw`(215행) | 둘 다 `@Transactional` 안 - AFTER_COMMIT이 유효 |
| 리스너 | `domain/chat/listener/AiMemberDataPurgeListener` | `@Async("aiPurgeExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)` |
| 클라이언트 | `AiChatClient.deleteLogs` | 200만 성공, 호출 제한 15초, 키는 헤더만 |
| 실행 풀 | `AsyncConfig.aiPurgeExecutor` | 1/2/100, 포화 시 폐기 + `[AI-PURGE-REJECTED]` WARN, 종료 시 대기 |
| AI 엔드포인트 | `chat_router.delete_logs` | 챗 라우터 전체에 API 키 검사, `userId` 필수 |

이 기능은 외부에 노출되는 엔드포인트가 아니라 내부 이벤트 처리다(백엔드에 새 경로 없음). AI의 새 엔드포인트만 키로 보호된다.

## PHASE A - 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 삭제 대상 정확성 | PASS | 삭제할 ID는 탈퇴 이벤트의 `userId`(본인 탈퇴 또는 관리자 강제 탈퇴 대상)뿐이다. 요청 값에서 오지 않는다 |
| AI 엔드포인트 인가 | PASS | gosky 실측: 키 없음 401. 키는 서버 간 통신 전용(브라우저 노출 없음) |
| 전체 삭제 사고 방지 | PASS | `userId` 없음·빈 값·공백은 422이고 아무것도 지우지 않는다(실측 + AI 테스트). ORM 파라미터 바인딩이라 주입 불가 |
| 다른 회원 기록 보존 | PASS | 해당 `user_id` 행만 삭제(AI 테스트가 다른 회원 행 유지 확인) |
| URL 인코딩 | PASS | `AiChatClientDeleteLogsTest`가 `&userId=` 주입 시도가 새 쿼리가 되지 않음을 고정 |
| 로그 | PASS | 성공 `[CHAT-PURGE]`·실패 `[AI-PURGE-FAILED]` 모두 userId와 예외 클래스명만. `LogRawExceptionGuardTest`·`ChatRelayLogGuardTest` 대상 |
| 정지/삭제 후 접근 | 해당 없음 | 삭제 대상이라 이후 조회 경로 자체가 없다 |

## PHASE B - 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 모든 탈퇴 경로 포함 | PASS | 본인 탈퇴·관리자 강제 탈퇴 모두 같은 이벤트 발행 지점(`withdraw`/`forceWithdraw`) |
| AI 구간 end-to-end(실서버) | PASS | 임시 ID `zzAUDIT01`: 생성 후 1건 -> `DELETE` 응답 `deleted:1` -> 0건. 다른 ID는 영향 없음 |
| 멱등 | PASS | 기록 없는 ID 삭제는 200 + `deleted:0` |
| 실패 격리 | PASS | 리스너는 예외를 삼키고 탈퇴·다른 리스너·purge를 막지 않는다(단위 테스트) |
| 미반영 AI 방어 | PASS | 옛 AI(404/405)를 성공으로 보지 않고 WARN으로 남긴다(테스트). 배포는 AI -> 백엔드 순서로 했다 |
| 두 서버 반영 | PASS | vkcs·gosky 백엔드 `385d29f`, 기동 ERROR 0. gosky AI 컨테이너 새 코드로 재생성, 두 백엔드 AI WS 재접속 확인 |

## PHASE C - 구조·계약

- 트랜잭션 경계: 리스너는 백엔드 DB를 쓰지 않으므로 `REQUIRES_NEW`가 필요 없다(비동기 + 이벤트에 userId 포함). 클립 삭제처럼 동기일 필요가 없는 이유가 문서·주석에 있다.
- executor: 알림 풀과 분리했고 `NotificationExecutorAssignmentTest`가 이름을 고정한다. 폐기는 `[NOTIFY-REJECTED]` ERROR와 섞이지 않는다.
- 계약: AI 응답 `{success, message, data:{deleted:n}}`은 사용하지 않고 상태 코드만 본다. 응답 형식이 바뀌어도 영향이 없다.

## PHASE D - 테스트

- 백엔드: 클라이언트 4건(메서드·경로·키·인코딩·200만 성공·키 미설정), 리스너 3건(요청·실패 삼킴·AFTER_COMMIT+전용 executor), executor 배정·로그 가드 확장. 전체 `./gradlew test` 통과.
- AI: 삭제 4건(해당 회원만, userId 누락 422, 멱등, 키 검사 등록 확인).
- 공백: 실제 Spring 컨텍스트에서 "탈퇴 트랜잭션 커밋 -> 비동기 리스너 호출"을 검증하는 테스트가 없다(M-1).

## 이슈

### 🟡 M-1. 탈퇴 이벤트 -> 리스너 배선을 실 컨텍스트로 검증하지 못했다
- **조치(2026-10-09)**: `WithdrawalAiPurgeIntegrationTest` 추가(커밋 시 전용 스레드에서 호출, 롤백 시 미호출).
- 근거: 리스너의 애너테이션 조합은 단위 테스트로 고정했지만, 실제 커밋 후 비동기 호출은 확인하지 못했다. 이 저장소는 같은 종류의 결함(동기 AFTER_COMMIT 안 쓰기가 조용히 사라짐, 2026-09-30 H-1)을 실 DB 통합 테스트로 잡은 이력이 있다. 이번 리스너는 DB를 쓰지 않아 그 결함에는 해당하지 않지만, 이벤트가 실제로 도달하는지는 코드 정독에 의존한다.
- 조치안: `WithdrawalListenerCommitIntegrationTest` 형태로 `UserService.withdraw`를 실 컨텍스트에서 돌려 `AiChatClient`(목)의 `deleteLogs(userId)` 호출을 확인하는 통합 테스트를 추가한다(Docker가 필요해 vkcs의 `tools/integration-test.sh`에서 실행). 또는 다음 정기 점검에서 테스트 계정을 만들어 실제 탈퇴를 한 번 돌린다.

### 🟡 M-2. AI 서버의 예약 자격 증명·카메라 행도 탈퇴 정리가 없다 (이번 기능의 범위 밖, 같은 종류의 문제)
- **조치(2026-10-09)**: 예약 API 키는 AI 삭제 API(PR #11) + 같은 리스너로 해소. `cameras`는 실서버 1행·회원 ID 컬럼 비어 있음을 확인해 제외.
- 근거: AI 서버 `reservation_credentials`(`user_id`, 예약 서비스 API 키 보관)와 `camera`(`target_user_id`·`guardian_user_id`)가 회원 ID를 보관한다. 백엔드에는 예약 자격 증명 연동이 없고 AI 서버에 삭제 경로도 없다. 탈퇴해도 남는다.
- 영향: 특히 `reservation_credentials`는 비밀 값이라 상담 기록보다 민감할 수 있다. 다만 백엔드 경유로는 읽을 수 없고(조회 경로가 백엔드에 없음), 카메라 행은 백엔드 카메라 삭제 흐름과 별개다.
- 조치안: 별도 점검 항목으로 올려 정책 결정(탈퇴 시 삭제 vs 보관) 후 AI 삭제 API를 같은 방식으로 확장한다.

### 🟢 L-1. 탈퇴 직전 진행 중인 대화가 삭제 뒤에 기록을 남길 수 있다
- 근거: AI는 답을 만든 뒤 마지막에 기록을 저장한다(`chat_service.process_message` 223~234행). 대화가 최대 약 120초 걸리므로, 그 사이 탈퇴가 커밋되어 삭제가 먼저 끝나면 이후 저장된 기록 1건이 남는다. 토큰 무효화는 이미 시작된 요청을 취소하지 않는다.
- 영향: 탈퇴 직전 2분 안에 질문을 보낸 경우 1건. 확률이 낮고 노출은 아니다(조회 경로 없음). 수용 가능. 해결하려면 AI에서 삭제 후 일정 시간 같은 ID의 저장을 막거나 백엔드가 지연 재삭제를 해야 해서 비용이 크다.

### 🟢 L-2. 재시도 없음 (스윕 purge·AI 장애·executor 포화 시 기록이 남음)
- 문서화된 수용 사항이다(rules 불변 규칙 ⑥). `[AI-PURGE-FAILED]`·`[AI-PURGE-REJECTED]` WARN이 추적 단서다.

### 🟢 L-3. 기능 배포 전에 이미 탈퇴한 회원의 기록은 남아 있다
- 이번 변경은 이후 탈퇴만 처리한다. 필요하면 AI 서버 `chat_logs`에서 백엔드에 없는 `user_id`를 찾아 일회성 정리를 한다(대상 판단은 사용자 결정).

## 종합
기능 자체(이벤트 발행 -> 비동기 호출 -> AI 삭제)는 구간별로 모두 확인됐고 안전 장치(422, 200만 성공, 실패 격리, 로그 최소화)도 정상이다. 남은 것은 실 컨텍스트 통합 테스트(M-1)와 같은 종류의 다른 AI 데이터(M-2)다.

## 수정용 커밋 메시지 초안
- `test: 회원 탈퇴 시 챗 기록 삭제 리스너 호출 통합 테스트 추가` (M-1)
- M-2는 정책 결정 후 `feat: 회원 탈퇴 시 AI 예약 자격 증명 삭제 요청` 형태
