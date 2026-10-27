# 관리자 대시보드 보강 기능 점검 (2026-10-27)

> 템플릿 B(기능 점검) · 대상 BE #387(`4c451a6`)·#388(`2fc430a`)·#389(`f5cc261`) · 기준 커밋 `f5cc261` · 점검만 했고 코드는 고치지 않음
> 구현 문서: `docs/(2026-10-27) feature-admin-dashboard-enrich.md`

## 종합 판정

**조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 5

인가·키 취급·로그·"모르는 값은 null" 규칙은 모두 지켜졌다. 남은 결함은 하나다. 챗봇 전송을 킬 스위치로 꺼 둬도 대시보드에 "정상"이 뜬다(M-1).

## PHASE 0. 대상

| 파일 | 역할 |
|---|---|
| `admin/controller/AdminDashboardController.java` | `/safety`·`/operation`·`/ai-status`(신규) |
| `admin/service/AdminDashboardService.java` | 알림 이력 요약·이상감지 요약 재사용, `maxTotal`, `generatedAt` |
| `admin/service/AdminAiStatusService.java` | 이상감지 구독 상태 + 챗봇 확인(트랜잭션 없음) |
| `admin/client/AiServerHealthClient.java` | AI `GET /api/v1/live-streams` 확인(연결 3초·전체 5초) |
| `admin/dto/AdminSafetyDashboardResponse`·`AdminOperationDashboardResponse`·`AdminAiStatusResponse`·`AiServiceState` | 응답 |

| 엔드포인트 | 비로그인 | GUARDIAN | WARD | ADMIN |
|---|---|---|---|---|
| `GET /api/admin/dashboard/safety` | 401 | 403 | 403 | 200 |
| `GET /api/admin/dashboard/operation` | 401 | 403 | 403 | 200 |
| `GET /api/admin/dashboard/ai-status` | 401 (gosky 실측) | 403 | 403 | 200 (gosky 실측) |

경로 규칙(`/api/admin/**` → ADMIN)과 클래스 레벨 `@PreAuthorize`가 이중으로 막는다. 메서드 보안 쪽은 `AdminDashboardControllerSecurityTest` 6건이 고정한다(익명 포함).

## PHASE A. 보안·인가 - PASS

- **IDOR**: 해당 없음. 개인 자원이 없고 집계 숫자만 내린다. 응답에 이름·ID·연락처가 없다.
- **역할 인가**: 새 경로도 클래스 레벨 `@PreAuthorize`의 적용을 받는다. 테스트로 고정돼 있다.
- **AI 키**: `X-API-Key` 헤더로만 보내고, 리다이렉트는 따라가지 않는다(테스트에서 대상 경로 0회 호출 확인). 로그와 응답에도 남지 않는다.
- **로그**: `[ADMIN-AI-STATUS]`에는 결과 코드·상태 코드·예외 클래스명만 남긴다. `LogRawExceptionGuardTest` 대상이다.
- **입력**: 받는 파라미터가 없다.

## PHASE B. 기능 정합성

- **전달 실패 알림**: `AdminNotificationService.getSummary(null, TODAY, null)`을 재사용한다.
  - 유형이 null이면 전 종류(`EnumSet.allOf`), 검색어가 null이면 미적용이다. 그래서 알림 이력 화면의 "오늘·전체" 탭 숫자와 같다.
  - `failed`만 쓰므로 NOT_SENT·SMS_FALLBACK은 세지 않는다(테스트에서 notSent 7건을 넣어도 2건).
  - 쿼리는 `created_at >= KST 자정`이고, 인덱스 `idx_notification_log_created_at`을 탄다.
- **평균 AI 신뢰도**: `AdminAnomalyService.getSummary(TODAY, null, null)`의 `countForAdminSummary`를 재사용한다.
  - 조건은 `startedAt >= KST 자정`뿐이라 `todayAnomaly`(`findByStartedAtGreaterThanEqual`)와 모집단이 같다. 둘 다 삭제된 카메라를 포함하는 관리자 전체 기준이다.
  - 판정 난 상황(위험+오탐)만 평균하고, 0건이면 null이다.
- **카메라 상한**: `camera.max-total`이 0 이하이면 null이다. gosky 실측은 `registered 2 / wards 2 / maxTotal 3`이었다.
- **AI 상태**: 이상감지는 `isConnected()`로 `OK`/`DISCONNECTED`를 낸다. 챗봇은 성공하면 `OK`, 실패는 모두 null이다. gosky 실측은 둘 다 `OK`(0.14초)였다.
- **M-1 (아래)**: 챗봇 킬 스위치가 반영되지 않는다.

## PHASE C. 구조·계약

- **트랜잭션**
  - `AdminDashboardService`는 클래스 레벨 `@Transactional(readOnly = true)`다. 재사용한 두 서비스의 readOnly 트랜잭션에 합류하므로 문제없다.
  - 어느 하나가 실패하면 그 탭 전체가 500이다. 원래 동작이며, 보호자 대시보드처럼 칸별로 격리하는 계약은 이 화면에 없다.
  - AI 호출은 트랜잭션이 없는 `AdminAiStatusService`에서 하므로 DB 연결을 붙들지 않는다.
- **추가 쿼리**: `/safety` 한 번에 GROUP BY 쿼리 2개가 늘었다. 결과 행은 (결과 4값) / (유형 × 판정) 수준이다. N+1은 없다.
- **하위 호환**: 기존 칸 이름·뜻은 그대로다. `AdminDashboardControllerHttpTest`가 실제 직렬화로 고정한다.
- **Swagger**: 두 서버 api-docs에서 새 경로·칸 6개를 확인했다.
- **#388 재발 여부**: `src/main`의 Spring 빈 가운데 생성자가 둘 이상인데 `@Autowired`가 없는 클래스는 0개다(스캔).

## PHASE D. 테스트

모두 통과했다. 서비스 6건, 클라이언트 8건(로컬 HTTP 서버), 빈 생성 1건, 상태 매핑 3건, HTTP 직렬화 3건, 권한 6건이다.

변이 확인:
- `failed` 대신 `notSent`를 쓰면 실패한다(2 ≠ 7).
- 기간을 바꾸면 `verify(... TODAY ...)`에서 실패한다.
- `@Autowired`를 빼면 빈 생성 테스트가 실패한다(#388에서 확인).

공백은 M-1(킬 스위치)과 L-4(가드)다.

## 이슈

### 🟡 M-1 챗봇 킬 스위치를 꺼도 "정상"으로 표시됨

- **현상**: `chat.relay.enabled=false`면 `ChatRelayService`가 모든 전송을 503으로 막는다. 그런데 `AiServerHealthClient`는 이 값을 보지 않아 AI가 응답만 하면 `chatbot.status=OK`를 낸다.
- **영향**: 운영자가 장애 대응으로 챗봇을 꺼 둔 동안 대시보드는 "AI 의료 챗봇 정상"을 보인다. 보호자·피보호자는 "잠시 사용할 수 없습니다"를 받는다. "확인하지 않은 것을 정상으로 보이지 않는다"는 이번 작업의 원칙과 어긋난다.
- **수정안(추천)**: `AiServiceState`에 `DISABLED`("꺼 둠" - 우리가 아는 사실)를 **추가**한다. 킬 스위치가 꺼져 있으면 AI를 부르지 않고 `DISABLED`를 내린다. 응답 값이 하나 늘어나는 것이라 관리자 웹 계약에 한 줄을 더해야 한다.
- **대안**: null(확인 불가)로 내리는 방법도 있다. 그러면 "꺼 둠"과 "모름"이 섞인다.
- **고정**: `AdminAiStatusServiceTest`에 "킬 스위치 꺼짐 → DISABLED, AI 미호출" 테스트를 추가한다.

### 🟢 L-1 이상감지 `OK`는 연결 여부만 본다 (수용 제안)

AI와 재접속한 직후 첫 세션 목록을 받기 전 몇 초 동안은 `/safety`의 `streamingCameras`가 null인데 `/ai-status`는 `OK`다. 연결 자체는 맞고 곧 목록이 와서 정상화된다. 이 칸은 "구독 연결 상태"로 정의돼 있어 정의와도 맞다.

### 🟢 L-2 한 응답 안의 시각 기준이 셋 (수용 제안)

`/safety` 한 번에 "오늘"을 대시보드 `now`, 알림 요약의 `OffsetDateTime.now(KST)`, 이상감지 요약의 `AnomalyReviewClock.now()`로 따로 구한다. KST 자정을 걸치는 요청(밀리초 단위)에서만 칸끼리 날짜가 다를 수 있다. 다음 폴링에서 맞춰진다.

### 🟢 L-3 `/ai-status` 동시 호출 상한 없음 (수용 제안)

AI가 응답하지 않으면 요청 하나가 요청 스레드를 최대 5초 붙든다. 관리자 전용이고 폴링 60초 권장이라 위험이 작다. 챗봇 중계(130초)·OCR(40초)처럼 길게 붙드는 경로가 아니다.

### 🟢 L-4 "생성자 여러 개 빈" 가드 테스트 없음 (선택)

#388과 같은 기동 실패는 지금 0건이지만, 다시 생기지 않게 막는 장치가 없다. `src/main`을 스캔해 "Spring 빈인데 생성자가 둘 이상이면 `@Autowired`가 있어야 한다"를 고정하는 소스 스캔 테스트(`PageRequestClampGuardTest`와 같은 방식)를 제안한다. 이번 결함은 배포 직후 두 서버가 재시작을 반복한 사례다.

### 🟢 L-5 AI 장애 동안 폴링마다 WARN (수용 제안)

AI가 내려가 있으면 관리자 화면이 열려 있는 동안 60초(권장)마다 `[ADMIN-AI-STATUS]` WARN이 남는다. 장애 신호로 쓸모가 있고, 양이 관리자 탭 수에 비례해 작다.

## 수정용 커밋 메시지 초안

```
fix: 관리자 AI 상태에서 챗봇 킬 스위치를 DISABLED로 표시

chat.relay.enabled=false면 챗 전송이 모두 503인데 대시보드는 AI 응답만 보고 "정상"을 냈다(점검 M-1).
꺼져 있으면 AI를 부르지 않고 AiServiceState.DISABLED("꺼 둠")를 내린다(값 추가만).
```

L-4를 함께 하면 `test: 생성자가 여러 개인 Spring 빈의 @Autowired 누락을 막는 가드 테스트 추가`로 커밋을 나눈다.

## 후속 조치 (2026-10-27, 6건 전부 반영)

사용자 지시("전부 반영")로 수용 제안 항목까지 모두 고쳤다. 마이그레이션 없음, 기존 응답 칸 그대로(상태 값 `DISABLED`만 추가).

| 항목 | 조치 | 고정한 테스트 |
|---|---|---|
| M-1 | 킬 스위치가 꺼져 있으면 AI를 부르지 않고 `AiServiceState.DISABLED` | `AdminAiStatusServiceTest.chatbotDisabled` |
| L-1 | 연결됐지만 `liveSessionSnapshot()`이 비어 있으면(첫 목록 전) null. 목록을 받았으면 송출 0대여도 OK | `anomalyBeforeFirstList`·`anomalyEmptyListIsOk` |
| L-2 | `AdminNotificationService`·`AdminAnomalyService`에 기준 시각을 받는 `getSummary(..., now)` 오버로드를 추가했다(기존 메서드는 위임). 대시보드는 한 `now`를 두 요약과 오늘 이상감지에 넘긴다 | `AdminDashboardServiceTest.오늘_기준시각_하나` + 두 서비스의 자정 직전 경계 테스트 |
| L-3 | 진행 중인 확인을 함께 기다린다(`compareAndExchange`, 기다림 상한 = 호출 제한 + 1초). 끝나면 다음 요청은 새로 확인한다 | `concurrentChecksShareOneCall`(4건 동시 → AI 1회) |
| L-4 | 생성자가 둘 이상인 Spring 빈에 `@Autowired`가 없으면 실패하는 소스 스캔 | `BeanConstructorGuardTest` |
| L-5 | 실패 시작은 WARN, 같은 실패의 반복은 DEBUG, 회복은 INFO. 결과 코드는 `HTTP_STATUS_503`·`TIMEOUT_…`처럼 고정 코드다 | `logsOnlyOnTransition` |

변이 6종(각 결함을 다시 넣기)은 모두 해당 테스트가 잡았다.

