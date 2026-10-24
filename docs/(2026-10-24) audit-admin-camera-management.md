# 관리자 디바이스(카메라) 관리 조회 API 기능 점검 (템플릿 B, 2026-10-24)

- 대상: PR #373 (머지 `f7ad44b`) - `GET /api/admin/camera`·`/summary`, `AiLiveStreamSubscriber.liveSessionSnapshot()`, `CAMERA_LIVE_STATUS_UNKNOWN`
- 방법: 독립 에이전트(Sonnet) 코드 점검(작성자 판단을 주지 않고 코드만 전달) + 메인 재확인 + 변이 2종 + gosky 실서버 호출
- 코드 수정 없음

## 종합 판정: 조건부 PASS
🔴 0 · 🟠 0 · 🟡 1 · 🟢 4. 인가·PII 노출 문제 없음.

## 인가 (gosky 실서버 2026-10-24, dev `54af682` - #373 포함)
| 엔드포인트 | ADMIN | GUARDIAN | WARD | 비인증 |
|---|---|---|---|---|
| GET /api/admin/camera | 200 (코드·테스트) | **403 실측** | 403 (메서드 보안 테스트) | **401 실측** |
| GET /api/admin/camera/summary | 200 (코드·테스트) | **403 실측** | 403 (메서드 보안 테스트) | **401 실측** |

- `SecurityConfig` `/api/admin/**` → ADMIN 규칙 + 클래스 레벨 `@PreAuthorize` 이중. 쓰기 매핑 없음(테스트 고정). `?status=OFFLINE`도 보호자 토큰 403.
- 관리자 토큰 200 실측은 시험 관리자 계정이 없어 미실시.
- 응답에 `deviceId`·영상·정지 화면·클립 주소·상세 주소 없음(`AdminCameraItemExposureTest`), 로그는 adminId·건수·소요 시간만.

## 기능 정합성 (PASS)
- `sessionListSynced`는 목록 처리 끝에서만 true, 연결·종료 시 false. `streams`가 배열이 아니거나 처리 중 예외가 나면 true가 되지 않아 "알 수 없음"으로 남는다(안전한 쪽). stale 닫기도 `afterConnectionClosed`를 거친다.
- 필터 → 정렬 → 페이지 순서, `totalElements`는 필터 결과 기준, 범위 밖 페이지 안전, 잘못된 status 400, 모를 때 LIVE·OFFLINE 503 / ALL은 null로 200.
- N+1 없음(`findAll` 1회 + `findAllById` 1회), `readOnly` 트랜잭션, 패키지 경계(camera → anomaly import 없음) 준수.

## 변이 확인 (메인, 되돌린 뒤 작업 사본 깨끗함 확인)
| 변이 | 결과 |
|---|---|
| 모를 때 503 검사 제거(`if (false)`) | `AdminCameraServiceTest` 실패 - 잡힘 |
| 스냅샷의 첫 목록 수신 여부 검사 제거 | `AiLiveStreamSubscriberTest` 실패 - 잡힘 |

## 이슈
### 🟡 M-1 대시보드는 재접속 직후 첫 목록 전에 "끊김"을 부풀린다 (기존 동작, 이번 PR 회귀 아님) - ✅ 조치 2026-10-24 `fix/dashboard-camera-snapshot`
- `AdminDashboardService.getSafetyDashboard`는 `isConnected()`만 보고 `subscribedSessionCount()`를 쓴다. 재접속 직후 첫 세션 목록 전에는 구독 기록이 비어 `streamingCameras=0`, `disconnectedCameras=활성 카메라 전부`가 된다(약 수 초). 디바이스 관리 화면은 같은 구간에 null이다.
- 숨긴 카메라 포함 여부로 두 화면의 끊김 숫자가 다른 것은 이번 기능에서 **수용으로 결정·문서화**했다(사용자 결정 2026-10-23). 남는 것은 위 구간뿐이다.
- 제안: 대시보드도 `liveSessionSnapshot()`으로 바꿔 첫 목록 전에는 null을 내린다(대시보드 계약상 null은 이미 "알 수 없음"이라 FE 변경 없음). 대시보드 숫자를 바꾸는 일이라 사용자 결정 후 진행.

### 🟢 L-1 RESTRICTED 피보호자의 카메라는 `registered`에 들어가지만 `totalWards`에는 안 들어간다
- 피보호자는 이용 제한이 금지(400 `WARD_CANNOT_BE_RESTRICTED`)라 정상 경로로는 생기지 않는다(독립 점검은 🟡로 냈으나 이 근거로 낮춤). 수용.

### 🟢 L-2 검색어 `q` 길이 상한 없음
- 쿼리스트링이고 메모리 비교라 실질 위험은 낮다. 필요하면 100자 상한.

### 🟢 L-3 옛 연결의 목록 처리와 재연결이 겹치면 첫 목록 전에 true가 될 수 있음 (추측)
- 같은 경합이 기존 구독 집합에도 있고, 영향은 다음 재동기화(최대 60초)까지. 수용.

### 🟢 L-4 테스트 공백
- `streams`가 배열이 아닌 목록·목록 처리 중 예외에서 스냅샷이 "알 수 없음"으로 남는지 테스트 없음(코드 읽기로 확인).
- 비인증 401은 컨트롤러 테스트 범위 밖(URL 규칙) - 이번에 실서버로 확인.

## 수정용 커밋 메시지 초안 (M-1을 고치기로 하면)
`fix: 대시보드 카메라 송출 수를 AI 첫 세션 목록 전에는 null로` - `AdminDashboardService`가 `liveSessionSnapshot()`을 쓰고, 재접속 직후 0·전부 끊김을 내지 않음을 테스트로 고정.

## 후속 조치 (2026-10-24, `fix/dashboard-camera-snapshot`)
- M-1: `AdminDashboardService`가 `liveSessionSnapshot()`으로 `streamingCameras`·`disconnectedCameras`·`subscribedSessions`를 계산한다. 재접속 직후 첫 목록 전에는 `aiConnected=true`인 채 두 값이 null, `subscribedSessions`는 0. 분모(활성 카메라만, ADMIN-G20)와 `aiConnected`는 그대로.
- 테스트 +2(`첫목록_전에는_null`·`목록_받은뒤_0대는_0`). 옛 계산으로 되돌리면 실패함을 확인. `./gradlew build` 통과(1863건, 실패 0).
- Swagger(대시보드 컨트롤러·응답 DTO) 설명에 "연결 직후 첫 목록 전에도 null"을 더했다. FE는 이미 null을 "알 수 없음"으로 다루므로 변경 없음.

