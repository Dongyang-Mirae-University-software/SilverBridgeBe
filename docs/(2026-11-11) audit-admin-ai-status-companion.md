# 기능 점검 - 관리자 대시보드 AI 서비스 상태 안부 말벗 칸 (2026-11-11)

- 템플릿 B(기능 점검), 대상 BE #473 머지 `69d86bcb` (기준 dev `4a06c6a2`)
- 범위: `CompanionAiStatusClient`, `AdminAiStatusService` 병렬 확인, `AdminAiStatusResponse.companion`, Swagger 설명, 건드린 공용 테스트 코드(`FullContextIntegrationTest` 목·`LogRawExceptionGuardTest` 목록)
- 점검만 했다(코드 수정 없음). 바꿔 보기 시험은 임시 worktree에서 하고 원본으로 되돌렸다.

## 종합 판정

**PASS** · 🔴 0 · 🟠 0 · 🟡 0 · 🟢 4

## 점검 파일

| 파일 | 역할 |
|---|---|
| `domain/companion/client/CompanionAiStatusClient.java` | AI `GET /companion/status` 확인(말벗 키) |
| `domain/admin/service/AdminAiStatusService.java` | 세 칸 판정, 말벗은 가상 스레드에서 병렬 확인 |
| `domain/admin/dto/AdminAiStatusResponse.java` | `companion` 칸 |
| `domain/admin/controller/AdminDashboardController.java` | Swagger 설명 |
| AI `app/routers/companion_router.py`·`app/core/companion_security.py` (읽기만) | 상태 경로 계약 |

## 엔드포인트 × 역할

| 경로 | 허용 | 근거 | 테스트 |
|---|---|---|---|
| `GET /api/admin/dashboard/ai-status` | ADMIN | `SecurityConfig` `/api/admin/**` hasRole ADMIN + 컨트롤러 클래스 `@PreAuthorize` | `AdminDashboardControllerSecurityTest`(ADMIN 허용·보호자·피보호자·비로그인 거절), gosky 비로그인 401 실측(vkcs) |

IDOR: 해당 없음(사용자 자원을 다루지 않음) - PASS.

## PHASE A. 보안·로그

| 항목 | 결과 |
|---|---|
| 키 전달 | `X-API-Key` 헤더만(쿼리 없음 - `ok` 테스트가 쿼리 null 확인) |
| 리다이렉트 | 따라가지 않음(3xx = 확인 불가) |
| 로그 | 결과 코드(`NOT_CONFIGURED`·`HTTP_STATUS_<n>`·`UNEXPECTED_BODY`·`AI_DISABLED`·`TIMEOUT_/IO_ERROR_<예외 클래스명>`)만. 키·주소·응답 본문·예외 객체 없음(테스트가 고정). `LogRawExceptionGuardTest` 목록에 포함 |
| 헤더 값 오류(키 제어 문자) | `setRequestProperty`의 `IllegalArgumentException`은 `RuntimeException` catch에서 클래스명 코드로만 바뀜 - 원문(키 포함)이 공용 예외 처리기로 올라가지 않는다 |
| 응답 크기 | 본문 16KB 상한 |
| 응답 노출 | 관리자 응답에는 상태값과 시각뿐. AI 응답의 `sessionLimitSec`·`maxTurns`·`fusionRule`은 읽지 않고 버린다 |

## PHASE B. 기능 정합성

| 경우 | 결과 | 근거 |
|---|---|---|
| 스위치 꺼짐 | DISABLED, AI 미호출 | `companionDisabled`, vkcs `.env.dev`에 켬 설정 없음 |
| 켜짐 + 주소·키 없음 | null, AI 미호출 | `companionNotConfigured` |
| 200 + `success` + `data.enabled` true | OK | gosky 실측 2회(배포 직후 17:40, AI 재배포 뒤 17:5x) |
| enabled false·형식 이상·HTML 200·401·5xx·시간 초과·접속 거부 | null | 클라이언트 테스트 |
| 병렬 확인 | 챗봇(요청 스레드)·말벗(가상 스레드) 동시 - 최대 약 6.5초(말벗 기다림 상한 = 호출 5초 + 공유 대기 1초 + 0.5초) | `chatbotAndCompanionRunInParallel`, `companionTimeout` |
| 종료 중 요청 | 실행기 거절(`RejectedExecutionException`) → null | 코드 확인(테스트 없음 - L-3) |
| 기다림 초과 | 가상 스레드는 클라이언트 마감(5초)으로 스스로 끝남, 응답은 null | 코드 확인 |
| 동시 요청 | 클라이언트가 진행 중 확인 하나를 공유 → 관리자 탭이 늘어도 AI 호출 1회 | `concurrentChecksShareOneCall` |
| 로그 전환 | 실패 시작 WARN 1회·회복 INFO 1회·같은 실패 DEBUG, 401↔5xx처럼 코드가 바뀌면 WARN | `logsOnlyOnTransitionWithoutSecrets` |
| 시각 | 세 칸 KST, `generatedAt` ≥ 모든 `checkedAt` | `allOk`·`generatedAtAfterAllChecks`, 실측 |
| 기존 칸 | `anomalyDetection`·`chatbot` 판정 코드 변경 없음 | diff |

AI가 실제로 멈춘 상황의 실측은 하지 않았다(AI 재시작 금지). 대신 시간 초과·접속 거부 단위 테스트로 확인했다.

## PHASE C. 구조·계약

- 트랜잭션 없음(DB 미사용), 서버 캐시 없음 - 대시보드 공통 규칙 그대로.
- 의존 방향 admin → companion(기존 `AdminAiStatsController` → `CompanionStatsService`와 같음), companion → admin 0건, global 미사용.
- 마감 스레드·가상 스레드 실행기 모두 빈이 아니다(`@Scheduled` 이동 위험 없음). 둘 다 `@PreDestroy`로 닫는다.
- 응답 형식: 다른 두 칸과 같은 `Item{status, checkedAt}`, status null이어도 칸이 빠지지 않음(`AdminDashboardControllerHttpTest`).
- Swagger: 값 의미·확인 범위·응답 시간을 적었고 내부 주소·키는 없음.

## PHASE D. 테스트 - 바꿔 보기 시험

| 바꾼 것 | 결과 |
|---|---|
| M1 `data.enabled` 검사 제거 | 검출 |
| M2 리다이렉트 추종 | 검출 |
| M3 동시 요청 공유 제거 | 검출 |
| M4 스위치 꺼짐 DISABLED 제거 | 검출 |
| M5 병렬 → 순차 | 검출 |
| M6 경고 로그에 키 | 검출 |
| M7 호출 마감 무시(60초) | 검출 |
| M8 미설정 null 제거 | 검출 |

8/8 검출. 전체 `./gradlew build` 통과(테스트 2538건, 실패 0, PR #473 기준).

## 이슈

- 🟢 **L-1 `companion.ai-base-url`이 `ExternalUrlSchemeChecker.KEYS`에 없다** (#473 이전부터, 말벗 중계 2026-11-04): 평문 http 외부 주소로 바뀌어도 기동 경고가 나지 않는다. 지금 gosky 값은 사설 대역(`10.*`)이라 넣어도 경고 대상은 아니다. 수정안: KEYS에 추가 + 기존 체커 테스트에 한 줄(말벗 키가 실리는 대화·상태·삭제 경로 모두 해당).
- 🟢 **L-2 OK는 AI 프로세스 응답 + 키 일치까지만 보장한다 (수용)**: AI `/companion/status`의 `enabled`는 `bool(companion_api_key)`라 키 검사를 통과하면 늘 true다. 음성 인식·언어 모델 준비 여부는 모른다. Swagger에 적었다. 실제 준비 상태가 필요하면 AI 쪽에 준비 상태 칸을 만드는 별도 작업이다.
- 🟢 **L-3 서비스의 예외 경로 테스트 없음**: `supplyAsync` 작업이 예외로 끝나는 경우(클라이언트는 예외를 던지지 않으므로 목에서만 가능)와 종료 뒤 `RejectedExecutionException` 경로. 둘 다 null로 가는 것을 코드로 확인했다. 수정안: 목이 `RuntimeException`을 던질 때 null, `shutdown()` 뒤 호출 시 null 테스트 2개.
- 🟢 **L-4 vkcs는 관리자 실측을 못 했다**: vkcs에 시험 관리자가 없어 비로그인 401·설정(켬 없음 → DISABLED 예상)만 확인했다. 판정은 단위 테스트(`companionDisabled`)가 고정한다.

## 수정 커밋 메시지 초안 (L-1·L-3을 고칠 때)

```
test: 관리자 AI 상태 말벗 확인 예외·종료 경로 테스트 추가
chore: 외부 주소 평문 경고 대상에 companion.ai-base-url 추가
```
