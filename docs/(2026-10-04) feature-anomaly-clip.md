# 이상감지 5초 영상 클립 - 저장·열람·삭제 (백엔드) (2026-10-04)

> 브랜치 `feature/anomaly-clip`(dev `0503f0c` 위로 rebase - #292 L-3 호출 전체 제한·#293 I-1 폐기 로그 분리 기준에 맞춤) · 마이그레이션 **V57** · AI 계약: "AI 서버 클립 엔드포인트 계약서(2026-10-04 최종본)"
> 근거: gosky 조사·측정(앞 3초 + 뒤 2초 / VP8 WebM FHD 3Mbps / 클립 약 1.9MB, 인코딩 0.5~0.6초)

## 1. 무엇을 하나

위험(`danger=true`)으로 이상감지 이력이 적재되면, 클립 쿨다운(카메라·유형당 5분)을 통과한 건마다 백엔드가 AI 서버에
`POST /api/v1/live-streams/{sessionId}/clips`로 **감지 앞 3초 + 뒤 2초** WebM을 요청해 **백엔드 디스크에 저장**한다.
보호자(ACTIVE 연결)와 피보호자 본인(ACTIVE 연결 1건 이상)이 상황 단위로 볼 수 있고, 관리자는 볼 수 없다.

```
AI WS 신호 → AnomalyDetectionService(이력 적재·커밋)
   ├─ AFTER_COMMIT @Async(urgentNotificationExecutor) → 알림 (기존, 무변경)
   └─ AFTER_COMMIT @Async(clipExecutor)               → AnomalyClipCaptureService (신규)
        danger 확인 → 클립 쿨다운(Redis) → 디스크 여유 → AI 요청(최대 25초)
        → EBML 시그니처·크기 검증 → 임시 파일 → 원자적 이동 → 행 기록(상황 행 잠금 안)
```

클립의 실패·지연은 이력·알림에 **영향이 없다**(다른 리스너·다른 스레드, 예외를 밖으로 내보내지 않음).

## 2. 결정 (PHASE 1, 2026-10-04 사용자 승인 "추천안대로")

| # | 항목 | 결정 |
|---|---|---|
| 1 | 재생 인증 | FE가 `Authorization` 헤더로 **fetch → blob URL 재생**. 티켓·permitAll 경로 없음 |
| 2 | 요청 실패 | 재시도 가능한 실패면 **클립 쿨다운 해제 → 다음 감지(이력 1분 간격)에서 재시도**. 401·422·키 미설정·503 `CLIP_DISABLED`(AI 킬 스위치, 계약 v2)는 해제하지 않음. Redis 장애 시 **클립 생략(fail-closed)** |
| 3 | 이력 응답 | 상황당 **대표 클립 1개(최신) + `clipCount`**, 전체는 목록 API. 상황당 상한 **12개** |
| 4 | 피보호자 오류 | 남의 클립 403 `ANOMALY_CLIP_NOT_OWNED` + `[IDOR-ATTEMPT]` / 연결 0건 404 / 보호자 미연결 403 `ANOMALY_NOT_AUTHORIZED` / 비공개·만료 404 `ANOMALY_CLIP_NOT_FOUND` |
| 5 | 청소 | **05:00 KST**. 고아 = 행 없는 파일·임시 파일 중 수정 후 1시간 경과. 스케줄러 풀 3 유지 |
| 6 | 타임아웃·크기 | 연결 3초(`camera.stream.connect-timeout` 공용), **호출 전체 25초**(AI 상한 20초 + 전송 여유 - 계약 v2 대조 권장, 일반 10초 규칙의 예외, 마감 시 연결을 끊는다 - 영상 중계 L-3과 같은 방식), 크기 **10MB** |
| 7 | 완성 WS 이벤트 | **없음** |
| 8 | QA 브랜치 순서 | 무관(코드 패치 전부 dev 반영, 남은 건 문서 커밋 1개) |
| 9 | AI 클라이언트 | `anomaly/client/AiClipClient` 신설, 접속 정보는 **기존 `camera.stream.*` 공용**(새 env 없음). `AiStreamClient`는 무변경 |
| 10 | 생성 조건 | **`danger=true` 이력만**(CONFIDENCE 폴백 모드에서는 클립 없음) |
| 11 | 디스크 보호 | 저장 루트 여유 **1GB 미만이면 생략 + ERROR** |
| 12 | 브랜치 | `feature/anomaly-clip` |

## 3. PHASE 0에서 확인한 사실 (프롬프트 전제와 다른 것)

- 영상 시청 중계는 "별도 작업"이 아니라 **이미 머지**(#290·#291) - AI HTTP 클라이언트·`camera.stream.*` 설정이 있었다(→ 결정 9).
- AI 서버 클립 API는 **아직 미배포**(키 없이 `/status` 401, `/clips` 404) → 계약서 기준 모의 서버(JDK `HttpServer`)로 개발·테스트.
- CONFIDENCE 폴백 모드에서는 `danger=false` 이력도 적재된다(→ 결정 10).
- 피보호자용 이상감지 API가 없었다 → 피보호자 컨트롤러 신설.
- 판정이 바뀌는 지점은 `GuardianAnomalyService.submitFeedback` **한 곳**(E-3 미구현이라 연결 해제로는 판정이 안 바뀜).
- 카메라 삭제 2경로 모두 이벤트가 없었고, `deleteAllByWard`는 벌크 삭제라 세션 목록을 삭제 전에 모아야 했다.

## 4. 데이터 (V57 `anomaly_clip`)

| 컬럼 | 설명 |
|---|---|
| `incident_id` | FK → `anomaly_incident` **CASCADE** |
| `ward_id` | FK → `users` **CASCADE** |
| `session_id` | 카메라 삭제 정리 키 |
| `file_name` | 서버가 만든 `UUID.webm`(UNIQUE). **경로 전체를 저장하지 않는다** - 루트는 `anomaly.clip.storage-dir` |
| `size_bytes` / `duration_ms`·`frame_count`·`width`·`height`·`clip_started_at` | AI 헤더 값. 없거나 형식이 틀리면 NULL |
| `detected_at` | AI에 요청한 감지 시각(`analyzedAt`, 없으면 요청 시각) |
| `status` | `VISIBLE`·`HIDDEN` CHECK + `(status='HIDDEN') = (hidden_at IS NOT NULL)` CHECK |
| `hidden_at` | 오탐 비공개 시각(24시간 유예 기준점) |

## 5. 정책

### 열람
- **보호자**: 요청 시점 ACTIVE 연결 피보호자의 클립만(`isActiveConnection`). 연결 해제 시 즉시 비공개.
- **피보호자 본인**: 본인 집 클립이되 **ACTIVE 연결이 1건 이상일 때만**(`getActiveGuardianIds`). 연결이 모두 끊기면 404, 재연결하면 다시 보인다. **연결 해제로 파일을 지우지 않는다.**
- **관리자**: 열람 불허. 관리자 API·DTO에 클립 정보가 없음을 `AdminAnomalyClipExposureTest`가 고정.
- 응답에는 파일 경로·세션·소유자 정보를 싣지 않는다. 파일 응답은 `Cache-Control: private, no-store`, `Content-Disposition: inline; filename="clip-{id}.webm"`.

### 오탐 비공개·복구
- 판정 쓰기 잠금(`findByIdForUpdate`) **같은 트랜잭션**에서 `FALSE_ALARM`이면 `HIDDEN`(+`hidden_at`), 그 밖(REAL·CONFLICTED·PENDING)이면 `VISIBLE` 복구.
- 이미 오탐인 상황에 도착한 클립은 `HIDDEN`으로 저장(기록도 같은 상황 행 잠금 안에서 판정을 읽는다 - 응답과 기록이 동시에 와도 오탐 상황의 클립이 공개로 남지 않는다).
- 물리 삭제는 청소 스케줄러가 `hidden_at` + 24시간 뒤, **여전히 HIDDEN일 때만**(조건부 DELETE) 한다. 유예 안에 번복되면 복구, 유예가 지나 삭제된 뒤에는 번복해도 돌아오지 않는다.
- 판정은 이미 나간 알림을 되돌리지 않는다(기존 규칙) - 클립 비공개는 알림이 아니다.

### 삭제 5경로
| 경로 | 방식 |
|---|---|
| 보관 만료(30일, 상한 30) | 청소(05:00) - 행 → 파일 |
| 오탐 24시간 | 청소 - 조건부 삭제 |
| 피보호자 탈퇴 | `UserWithdrawnEvent` **동기 AFTER_COMMIT** → `REQUIRES_NEW` 행 삭제 → 파일 삭제(purge CASCADE 전). 스윕 purge 경로는 행 CASCADE + 고아 파일 청소 |
| 카메라 삭제(`delete`·`deleteAllByWard`) | `CameraDeletedEvent`(camera → 이벤트, anomaly가 수신) 동기 AFTER_COMMIT → `REQUIRES_NEW` 행 삭제 → 파일. 상황 이력은 남는다 |
| 고아 | 청소 - 행 없는 파일·임시 파일(1시간 경과), 파일 없는 행, 카메라가 사라진 세션의 클립 |

### 생성
- 킬 스위치 `anomaly.clip.enabled=false`는 **생성만** 멈춘다(열람·삭제·청소는 계속).
- 쿨다운 키 `anomaly:clip:{sessionId}:{type}` - 이력(`anomaly:cooldown:`)·알림(`anomaly:notify:`)과 별개.
- `clipExecutor` core 2 / max 2 / queue 20, 포화 시 폐기(CallerRuns 금지) + **`[ANOMALY-CLIP-REJECTED]` WARN** - 알림 유실이 아니라 `[NOTIFY-REJECTED]` ERROR와 섞지 않는다(실시간 분석 풀 점검 I-1과 같은 기준). 폐기된 건은 쿨다운(5분)이 풀릴 때까지 다시 만들지 않는다(수용).
- 로그 태그 `[ANOMALY-CLIP]` - sessionId·clipId·incidentId·결과 코드·HTTP 상태만. AI 키·경로·예외 원문 금지.

## 6. 변경 파일

**신규**
- `db/migration/V57__create_anomaly_clip.sql`
- `domain/anomaly/entity/AnomalyClip`·`AnomalyClipStatus`, `repository/AnomalyClipRepository`
- `domain/anomaly/client/AiClipClient`
- `domain/anomaly/service/AnomalyClipCaptureService`·`AnomalyClipService`·`AnomalyClipStorage`·`AnomalyClipCooldown`·`AnomalyClipAccessService`·`AnomalyClipCleanupScheduler`
- `domain/anomaly/listener/AnomalyClipListener`·`AnomalyClipCleanupListener`
- `domain/anomaly/controller/WardAnomalyClipController`·`AnomalyClipFileResponse`, `dto/AnomalyClipItem`
- `domain/camera/event/CameraDeletedEvent`

**변경**
- `GuardianAnomalyService` - 판정 직후 `clipService.applyReviewStatus`(같은 트랜잭션), 이력에 클립 요약
- `GuardianAnomalyController` - 클립 목록·파일 2종
- `AnomalyIncidentItem` - `clip`·`clipCount` 추가(필드 추가, 하위호환)
- `CameraService.delete`·`deleteAllByWard` - 삭제 전 세션 수집 + `CameraDeletedEvent`
- `AnomalyProperties.Clip`, `application.yaml`(`anomaly.clip.*`, 스케줄러 7종 주석), `AsyncConfig.clipExecutor`, `ErrorCode` 2종

**무변경(확인)**: `AnomalyDetectionService`·`AnomalyNotificationListener`(이력·알림 경로), `AiStreamClient`·영상 중계, 관리자 이상감지 API·DTO.

## 7. FE 전달

### API
| 메서드·경로 | 역할 | 응답 |
|---|---|---|
| `GET /api/guardian/anomaly/history` | 보호자 | 항목에 `clip`(최신 1건 또는 null)·`clipCount` **추가** |
| `GET /api/guardian/anomaly/{incidentId}/clips` | 보호자 | `ApiResponse<AnomalyClipItem[]>` 최신순 |
| `GET /api/guardian/anomaly/clips/{clipId}/file` | 보호자 | `video/webm`(Range 206 지원) |
| `GET /api/ward/anomaly/{incidentId}/clips` | 피보호자 | 위와 같음 |
| `GET /api/ward/anomaly/clips/{clipId}/file` | 피보호자 | 위와 같음 |

`AnomalyClipItem`: `clipId`, `incidentId`, `detectedAt`, `durationMs`(null 가능), `width`·`height`(null 가능), `sizeBytes`, `createdAt`.

### 재생 (blob)
`<video src>`는 인증 헤더를 못 보내 401이 난다. 아래처럼 받는다(1.9MB 내외).
```js
const res = await fetch(`/api/guardian/anomaly/clips/${clipId}/file`, { headers: { Authorization: `Bearer ${token}` } });
if (!res.ok) { /* 오류는 JSON(ApiResponse) - code로 분기 */ }
const url = URL.createObjectURL(await res.blob());
video.src = url;            // 화면을 떠날 때 URL.revokeObjectURL(url)
```

### 오류 처리
| 상태·code | 의미 | 화면 |
|---|---|---|
| 404 `ANOMALY_CLIP_NOT_FOUND` | 없음·오탐 비공개·30일 경과·(피보호자) 연결된 보호자 없음 | "영상을 볼 수 없습니다" - 재시도 버튼 없이 |
| 404 `ANOMALY_INCIDENT_NOT_FOUND` | 상황 없음 | 목록 새로고침 |
| 403 `ANOMALY_NOT_AUTHORIZED` | (보호자) 연결이 끊긴 피보호자 | 목록에서 제거 |
| 403 `ANOMALY_CLIP_NOT_OWNED` | (피보호자) 다른 집 영상 | - |

- 클립은 감지 후 **약 3~10초 뒤** 생긴다(뒤 2초 대기 + 인코딩). 완성 알림(WS)은 없으니, 이력 화면을 열 때 조회하면 된다. 생성이 실패하면 그 상황에는 클립이 없을 수 있다(`clip=null`).
- 피보호자의 `incidentId`는 이상감지 알림(WS `anomaly-detected`·FCM data)에 들어 있다.
- **실사용 전제**: 카메라가 백엔드 `POST /api/ward/camera`로 등록돼 있어야 이력·클립이 생긴다(등록 일원화는 FE 몫, 2026-07-31 안내).

## 8. 인프라 전달 (사용자 진행)

1. **AI 서버 먼저 배포** - 계약서대로 `POST /api/v1/live-streams/{sessionId}/clips` + `imageio-ffmpeg==0.6.0`. 확인: 키를 넣은 요청이 404(세션 없음 `STREAM_SESSION_NOT_FOUND`)·200을 주면 라우트가 있는 것.
2. **볼륨 마운트** - `docker-compose.dev.yml` api 서비스:
   ```yaml
   volumes:
     - ./.data/clips:/data/clips
   ```
   두 서버(gosky·vkcs) 모두. 마운트가 없으면 컨테이너 재생성 때 클립이 사라진다(행은 남아 404 → 다음 청소가 행 정리).
3. **백엔드 배포**(이 PR 머지) - V57 적용.

환경변수(모두 선택, 기본값이 계약서 값): `ANOMALY_CLIP_ENABLED`(true) · `ANOMALY_CLIP_STORAGE_DIR`(/data/clips) · `ANOMALY_CLIP_PRE_SECONDS`(3) · `ANOMALY_CLIP_POST_SECONDS`(2) · `ANOMALY_CLIP_COOLDOWN_MINUTES`(5) · `ANOMALY_CLIP_REQUEST_TIMEOUT`(25s) · `ANOMALY_CLIP_MAX_BYTES`(10485760) · `ANOMALY_CLIP_RETENTION_DAYS`(30, 상한 30) · `ANOMALY_CLIP_HIDDEN_GRACE_HOURS`(24) · `ANOMALY_CLIP_MAX_PER_INCIDENT`(12) · `ANOMALY_CLIP_MIN_FREE_DISK_MB`(1024). AI 주소·키는 기존 `AI_HTTP_BASE_URL`·`AI_API_KEY`.

AI 서버보다 백엔드가 먼저 배포돼도 깨지지 않는다 - 클립 요청이 404(`SESSION_NOT_FOUND`로 분류)로 실패하고 다음 감지에서 다시 시도할 뿐, 이력·알림은 그대로다.

디스크 예상: 화재가 이어지면 카메라당 5분에 1개(약 1.9MB), 상황당 최대 12개(약 23MB), 30일 보관.

## 9. 검증

- `./gradlew build` 통과. **단위 1276 / 실패 0**(건너뜀 16은 기존) - 신규 97.
  - `AiClipClientTest`(모의 AI 서버): 계약 형식 요청(POST·키 헤더·`detectedAt` UTC Z), 헤더 메타·누락 null, EBML 불일치·크기 초과 거부, 응답 시간 초과, 본문을 조금씩 흘려도 호출 전체 제한에서 끊김, 리다이렉트 미추종, 오류 7종 매핑·재시도 여부, 키 미설정, 세션 ID 경로 인코딩
  - `AnomalyClipCaptureServiceTest`: 쿨다운 해제/유지 분기, 실패 시 파일 삭제, 킬 스위치, danger=false 생략, 디스크 부족
  - `AnomalyClipStorageTest`: 원자적 쓰기, 경로 이탈 8종 거부, 청소 목록
  - `AnomalyClipAccessServiceTest`: 보호자 미연결·PENDING 403 / 피보호자 남의 것 403·연결 0건 404 / 파일 없음 404
  - `AnomalyClipServiceTest`·`AnomalyClipCleanupSchedulerTest`·보안 테스트(보호자·피보호자 컨트롤러, 관리자 노출 없음)·`CameraServiceTest`(삭제 이벤트)·`NotificationExecutorAssignmentTest`(clipExecutor·동기 삭제 리스너)
- **통합 테스트(실 DB) - vkcs 통과**(2026-10-04, `fb6efa3`, 55건 / 실패 0):
  `AnomalyClipIntegrationTest` 9건(판정 비공개·복구, 오탐 상황 클립 비공개, **오탐 응답 ↔ 클립 기록 동시 20회**, 탈퇴·보호자 탈퇴·카메라 삭제·일괄 삭제 리스너 커밋 + 파일, 조건부 삭제, 카메라 없으면 기록 안 함) + `EnumCheckConstraintIntegrationTest`(`anomaly_clip.status`) + 기존 `AnomalyReviewConcurrencyIntegrationTest`(의존성 추가 반영)
  ```bash
  ~/SilverBridgeBe/tools/integration-test.sh feature/anomaly-clip
  ```
- 실서버 E2E(AI 클립 API 배포 후): 화재 감지 → 수 초 뒤 `anomaly_clip` 행·`/data/clips/*.webm` → 보호자 이력 `clip` → 파일 Chrome 재생 5.0초 / 오탐 응답 → 404 → 번복 → 재생.

## 10. AI 계약 v2 대조 (2026-10-04, AI 서버 `87accbb`·`5046436` 배포 후)

- AI 클립 API 배포 확인(gosky, 서버 키로 호출): 범위 밖 파라미터 422 `CLIP_INVALID_PARAMS`(JSON) - 계약대로.
- AI 쪽 대조 권장 2건 반영: ① 503 `CLIP_DISABLED` → `DISABLED`(재시도 안 함, 쿨다운 유지) ② 호출 제한 20 → **25초**.
- **drift 1건(동작 영향 없음)**: 없는 세션의 404가 `testai.gosky.kr` 앞단에서 **HTML 404 페이지로 바뀌어** 내려온다(계약은 JSON `STREAM_SESSION_NOT_FOUND`). 백엔드는 상태 코드로 `SESSION_NOT_FOUND`(재시도 가능)를 판정해 영향이 없고, 로그의 `errorCode`만 null이다. 프록시 설정은 AI·인프라 쪽 확인 사항.

## 11. 수용한 한계

- `clipExecutor` 포화로 폐기된 클립은 쿨다운(5분) 동안 다시 만들지 않는다.
- 클립 기록과 카메라 삭제가 아주 짧게 엇갈리면 삭제된 카메라의 클립 행이 남을 수 있다 - 다음 청소(카메라 사라짐 단계)가 회수한다.
- 오탐 유예(24시간)가 지나 삭제된 뒤 번복되면 클립은 돌아오지 않는다.
- 저장소는 단일 서버 로컬 디스크다(API 서버를 여러 대로 늘리면 공유 저장소로 다시 설계).
- AI 호출 전체 제한(25초)은 본문 수신 중에는 읽기 사이에서만 확인된다 - 최악은 마감 + 읽기 1회 제한(영상 중계 `AiStreamClient`와 같은 JDK 제약).
