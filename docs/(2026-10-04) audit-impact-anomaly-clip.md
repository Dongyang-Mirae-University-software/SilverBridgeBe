# 영향 범위 점검 (템플릿 C) - 이상감지 5초 클립 (2026-10-04)

> 대상: PR #294 `feature/anomaly-clip` @ `df64f23` (V57) · 기준 dev `0503f0c`
> 모델 배치: PHASE A 사용처 수집 = Sonnet 하위 에이전트, PHASE B 판정·잠금 순서 분석 = Opus(메인)
> 규칙: 점검만 - 코드 수정 없음

## PHASE -1. 환경
- 2026-10-04, 빌드 통과(단위 1276 / 0 실패), vkcs 통합 테스트 55 / 0 실패(`fb6efa3`, 이후 커밋은 클라이언트 분류·기본값만 변경).
- AI 클립 API 배포 확인(gosky AI `87accbb`·`5046436`, 서버 키로 422 `CLIP_INVALID_PARAMS` JSON 응답).
- 두 서버 모두 `./.data/clips` 마운트 **없음**(compose·디렉터리 확인), 디스크 여유 gosky 203G / vkcs 73G.

## PHASE 0. 변경점과 불변식

- 새 테이블 `anomaly_clip`(FK `incident_id`·`ward_id` CASCADE) + 디스크 파일, 새 이벤트 `CameraDeletedEvent`, 판정 훅(`submitFeedback` → `applyReviewStatus`), `UserWithdrawnEvent` 새 리스너, 보호자 이력 DTO 필드 추가, 새 executor·스케줄러.
- **불변식**: "클립은 열람 자격(보호자 ACTIVE 연결 / 피보호자 본인 + ACTIVE 연결 1건 이상)이 있을 때만 보이고, 삭제 사유(탈퇴·카메라 삭제·오탐 24h·보관 만료)가 생기면 행과 파일이 함께 사라진다. 클립은 이력·알림 경로에 영향을 주지 않는다."

## PHASE A·B. 사용처별 판정

| 영역 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 탈퇴(본인) | `UserController:214` → `UserService:202` 이벤트 → purge `:231` | PASS | `AnomalyClipCleanupListener.handleWithdrawn` 동기 AFTER_COMMIT + `REQUIRES_NEW`(`AnomalyClipService.deleteAllOfWard`) - purge CASCADE 전에 행·파일 삭제. 통합 테스트로 커밋 확인 |
| 탈퇴(관리자 강제) | `AdminUserService:185` `forceWithdraw` → 같은 이벤트 | PASS | 같은 파이프라인 |
| 탈퇴(스윕 purge) | `WithdrawnUserPurgeScheduler:38` → `purgeWithdrawnUser` | ⚠️ L-3 | 이벤트 없음 → 행은 CASCADE, 파일은 다음 05:00 고아 청소까지 디스크에 남는다(열람 불가) |
| 카메라 삭제(본인) | `CameraService:136-137` | PASS | `CameraDeletedEvent` → 동기 AFTER_COMMIT 정리. 통합 테스트 |
| 카메라 삭제(역할 변경) | `CameraService:159-164` ← `AdminUserService:244` | PASS | 삭제 전 세션 수집 후 발행. 통합 테스트 |
| 카메라 sessionId·wardId 변경 | `Camera` 변경 메서드 `rename`·`activate`·`deactivate`뿐 | PASS | 재지정 경로 없음 → 클립의 `(ward_id, session_id)` 키가 어긋나지 않는다 |
| `anomaly_incident` 삭제 | Java 경로 0건, users CASCADE뿐 | PASS | 클립 행도 CASCADE(V57 FK) |
| 판정 쓰기 | `GuardianAnomalyService:134` 1곳(V52 이후 SQL 쓰기 없음) | PASS | 같은 잠금 트랜잭션에서 `:136` 클립 공개 상태 반영. 다른 판정 경로가 생기면 훅 누락 위험 - 아래 C-1 |
| 상황 승계 | `AnomalyIncidentService.resolveIncident:44` (`addDetection`은 판정 유지) | ⚠️ L-2 | 오탐 확정 상황에 10분 안에 이어진 감지의 클립은 HIDDEN으로 태어난다 |
| 이력 소비자 | `GuardianAnomalyService.getHistory` ← 컨트롤러 1곳 | PASS | 인가 통과한 상황만 요약 |
| 관리자 | `AdminAnomalyService`·`AdminDashboardService`·admin DTO | PASS | clip 참조 0건(`AdminAnomalyClipExposureTest` 고정) |
| 인가 | `AnomalyClipAccessService:73`(`isActiveConnection`)·`:86`(`getActiveGuardianIds`) | PASS | `getMyWards` 0건. 연결 종료 5경로(보호자·피보호자 해제·탈퇴·역할 변경·강제 해제)는 모두 상태 전이라 열람 시점 검사로 즉시 비공개 |
| 계정 상태 | `JwtAuthenticationFilter` - 요청별 status 조회 없음, 무효화 키로 강제 | PASS(기존 동작) | 정지·탈퇴·역할 변경 시 토큰 무효화. 클립 경로도 같은 필터 |
| 보안 경로 | `SecurityConfig:94-117` | PASS | `/api/*/anomaly/**`에 permitAll 없음, 역할은 클래스 레벨 `@PreAuthorize` |
| executor | `AsyncConfig` 4종, 클립은 `clipExecutor`만 | PASS | 긴급 알림 풀과 분리(`NotificationExecutorAssignmentTest`) |
| 스케줄러 | 일일 03:00·04:00·04:30·05:00, 풀 3 | PASS | 시각 충돌 없음 |
| 탈퇴 리스너 전파 | `UserWithdrawnEvent` 리스너 5종 모두 동기 AFTER_COMMIT, 쓰기 REQUIRES_NEW | PASS | H-1 규칙 준수 |

### 잠금 순서 (Opus 분석)

| 경합 | 결과 |
|---|---|
| 클립 기록 ↔ 보호자 판정 | 같은 상황 행 `FOR UPDATE`로 직렬화 - PASS(통합 테스트 20회) |
| 클립 기록 ↔ 카메라 삭제 정리 | 기록이 카메라 존재를 확인한 뒤 삭제가 끼면 행이 남을 수 있음 → 05:00 "카메라 사라짐" 단계가 회수(문서화된 한계) |
| 판정(+비공개) ↔ 피보호자 purge | 판정 트랜잭션의 쓰기는 상황 행·보호자 FK·`anomaly_clip` UPDATE뿐이라 피보호자 `users` 행을 기다리지 않음 - 교착 없음 |
| **클립 기록 ↔ 피보호자 purge** | ⚠️ **L-1** 아래 |

## 이슈

🔴 0 · 🟠 0 · 🟡 0 · 🟢 3 · ℹ️ 3

### 🟢 L-1 클립 기록과 피보호자 purge의 교착 가능성
- T1(클립 기록): 상황 행 `FOR UPDATE` → `anomaly_clip` INSERT 시 FK 검사가 피보호자 `users` 행에 `KEY SHARE`.
- T2(purge): 피보호자 `users` 행 DELETE(행 잠금) → CASCADE로 상황 행 삭제 시도 → T1 대기. T1은 `KEY SHARE`에서 T2 대기 → **교착**. PostgreSQL이 한쪽을 중단한다.
- 영향: T1 중단이면 파일 삭제 + 쿨다운 해제(정상 정리), T2 중단이면 컨트롤러 1회 재시도 → 스윕(10분)이 회수. 피보호자 탈퇴와 그 집 클립 저장이 같은 순간 겹칠 때만 생기고 스스로 복구된다.
- 수정안(선택): `record()`에서 상황 행보다 **먼저 피보호자 `users` 행을 `FOR KEY SHARE`로 잠근다**(잠금 순서를 purge와 같게 users → incident). 그러면 purge가 먼저면 기록이 기다렸다가 "상황 없음"으로 빠지고, 기록이 먼저면 purge가 기다린다. 실 DB 경합 테스트로 고정 가능.

### 🟢 L-2 오탐 확정 상황에 이어진 감지의 클립이 비공개로 태어난다(정책 판단 필요)
- 상황은 10분 이내 연속 감지를 묶고 `addDetection`은 판정을 유지한다(기존 규칙). 그래서 보호자가 "오탐"이라 답한 직후 같은 카메라에서 다시 감지되면, 새 클립도 그 상황의 판정을 따라 HIDDEN → 24시간 뒤 삭제된다.
- 그 사이 진짜 화재였다면 보호자가 판정을 번복해야 클립이 돌아온다. 알림은 그대로 나간다(판정은 알림을 막지 않음).
- 선택지: ① 현행 유지(판정 단위 = 상황, 일관됨) ② 판정 **이후** 저장된 클립은 VISIBLE로 태어나게 한다(`hidden_at`보다 늦게 만든 클립은 숨기지 않음). ②는 "상황 단위 판정"과 어긋나지만 증거 보존에 유리하다. **결정 요청.**

### 🟢 L-3 스윕 purge 경로의 파일 잔존
- 리스너가 실패해 스윕이 purge하면 클립 파일은 다음 05:00 고아 청소까지(최대 약 24시간) 디스크에 남는다. 행이 없어 열람은 불가하다.
- 수정안(선택): `WithdrawnUserPurgeScheduler`가 purge 전에 클립 정리를 부르거나, 고아 청소를 하루 1회 → 1시간 1회로. 현행도 "탈퇴자 데이터를 붙들지 않는다"는 원칙 안에서 하루 이내라 수용 가능.

### ℹ️ I-1 AI 404가 HTML로 바뀐다(AI·인프라)
- `testai.gosky.kr` 앞단이 404 응답 본문을 HTML 페이지로 바꾼다(422는 JSON 그대로). 백엔드는 상태 코드로 분류해 동작 영향이 없고 로그 `errorCode`만 null. AI 쪽에 전달.

### ℹ️ I-2 종료 대기(20초) < 클립 호출 제한(25초)
- 배포 시 진행 중인 클립은 최대 5초 모자라 끊길 수 있다 - 그 클립만 빠지고 임시 파일은 고아 청소가 회수. 알림 풀과 같은 종료 대기를 쓰는 것이 맞아 수용.

### ℹ️ I-3 피보호자 "연결 1건 이상"은 연결 상태 기준
- 보호자가 전원 이용 제한(RESTRICTED)이어도 연결이 ACTIVE면 피보호자는 클립을 본다. 결정 문구("ACTIVE 연결이 1건 이상")와 일치하며 정지 사유를 노출하지 않는 쪽이라 그대로 둔다.

## PHASE C. 테스트로 고정할 수 있는 것

| 제안 | 형태 |
|---|---|
| C-1 판정 쓰기 지점이 늘 때 클립 훅 누락 방지 | `AnomalyIncident.applyReviewStatus` 호출자가 `GuardianAnomalyService` 하나뿐임을 고정하는 아키텍처 테스트(새 판정 경로가 생기면 실패 → 클립 훅 확인 유도). 또는 훅을 `applyReviewStatus` 옆 한 메서드로 묶기 |
| C-2 L-1 수정 시 | 실 DB 경합 테스트(기록 ↔ purge 동시 20회, 교착 예외 0) |
| C-3 탈퇴 경로가 늘 때 | `UserWithdrawnEvent`를 발행하지 않는 users 삭제 경로가 `purgeWithdrawnUser`(스윕) 하나뿐임을 고정 |

## 반영 (2026-10-04, 사용자 "추천안대로 진행")

- L-1 ✅ `AnomalyClipService.record`가 `AnomalyClipRepository.lockWardForKeyShare`로 피보호자 행을 먼저 잠근다 - 통합 테스트 `클립기록과_탈퇴purge_동시`(20회).
- L-2 ✅ 판정 뒤 저장된 클립은 공개로 저장(정책 파일 규칙 ⑤ 하위 항목) - 통합 테스트 `오탐판정_이후_클립은_공개`, 동시 테스트 불변식을 "판정보다 이른 공개 클립 없음"으로 변경.
- L-3 ✅ 고아 파일 청소를 매시 15분(`cleanupOrphanFiles`)으로 분리.
- 인프라: `docker-compose.dev.yml` api에 `./.data/clips:/data/clips` 추가.
- C-1·C-3(아키텍처 테스트)은 제안으로 남긴다.

## 종합 판정

⚠️ **점검됨 - 머지 가능, 잔여 Low 3건**. 불변식 위반(열람 범위 이탈·삭제 누락·알림 영향)은 없다. L-1은 자동 복구되는 드문 경합, L-2는 정책 결정 사항, L-3은 수용 가능한 지연이다.

머지 전 남은 것: 두 서버 `./.data/clips:/data/clips` 마운트(인프라). 마운트 없이 배포해도 기능은 동작하지만 컨테이너 재생성 때 파일이 사라진다.
