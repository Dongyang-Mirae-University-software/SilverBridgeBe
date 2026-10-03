# 영향 범위 점검 (템플릿 C) - 보호자 실시간 카메라 보기 PR #290 (2026-10-03)

> 대상 `d5b05c0..ea3d7b8` (PR #290 머지). 읽기 전용 점검 + gosky 읽기(ssh). 구현 문서 `(2026-10-03) feature-ai-stream-relay.md`.
> 배포: vkcs CD success, gosky 수동 배포 `ea3d7b8` healthy·AI WS 재연결. 실서버 스모크 - 보호자 API 무인증 401, 티켓 없는 영상 401 `CAMERA_STREAM_TICKET_INVALID`, 다른 `/api/camera/stream/**` 경로 401, 컨테이너 → AI REST 키 있음 200·없음 401.

## 결론

🔴 0 · 🟠 1 · 🟡 4 · 🟢 4. 열람 범위를 넓히는 누락 경로 없음.

| # | 심각도 | 항목 | 근거 | 실패 시나리오 | 권장 |
|---|---|---|---|---|---|
| M-1 | 🟠 | AI WS가 끊겨도 보호자 화면에 "확인 불가"를 알리지 않는다 | `LiveAnalysisBroadcaster.clear()`는 맵만 비우고 STOMP 미발송(호출: `AiLiveStreamSubscriber` 연결/종료) | 수신기 재접속 대기(최대 60초 반복) 동안 화면에 마지막 "송출 중·정상"이 남는다 - "모르는 값을 아는 값처럼 보이지 않는다" 원칙 위반(REST 스냅샷만 비워짐) | `clear()` 전에 보낸 적 있는 세션에 `status=null`(확인 불가) 발송 + FE 계약 추가 |
| L-1 | 🟡 | 꺼진 카메라(`is_active=false`)도 `camera-analysis`가 나간다 | `fanOut`이 `findOwnerBySessionId`(활성 필터 없음) / 영상은 `getViewableCamera`가 404 | `/live` 목록에 없는 세션 이벤트가 FE에 도착(영상 404, 분석은 흐름) | `fanOut`에 활성 조건 추가 **또는** "감지는 계속(ANOM-G08)이니 표시도 유지"로 정책 명시 - 결정 필요 |
| L-2 | 🟡 | AI 호출이 리다이렉트를 따라간다 | `SimpleClientHttpRequestFactory`·MJPEG `HttpURLConnection` 기본 follow | AI(또는 경로상 장비)가 30x를 주면 다른 호스트로 요청(키 헤더 동반 여부는 미확인) | `setInstanceFollowRedirects(false)` |
| L-3 | 🟡 | 목록·상태·스냅샷 응답 제한이 읽기 1회 단위 | `setReadTimeout` = SO_TIMEOUT. 크기 초과 시에도 `close()`가 drain | AI가 조금씩 흘리면 요청 스레드가 5초 넘게 묶임 | 후속 - 필요 시 전체 시간 제한 래핑 |
| L-4 | 🟡 | 티켓·상태·스냅샷 API에 속도 제한 없음 | 호출마다 DB 2~3회 + AI 호출, 스냅샷 최대 5MB | 인증된 보호자의 반복 호출이 AI 부하로 증폭(티켓의 Redis 영향은 수만 rps라야 의미) | 후속 - `RateLimitService` 검토 |
| I-1 | 🟢 | 분석 executor 포화 로그가 `[NOTIFY-REJECTED]` ERROR | `AsyncConfig` 공용 거부 핸들러 | 화면용 폐기가 알림 장애처럼 보임 | 선택 - 이름·레벨 분리 |
| I-2 | 🟢 | nginx 접속 로그에 티켓 쿼리 | gosky `dmu_access.log` | 1회용·60초라 소비 뒤 무의미 | 수용(설계) |
| I-3 | 🟢 | 시각 오프셋 혼재 | `lastFrameAt` UTC / `analysis.analyzedAt` KST | FE 표시 혼동 | FE 안내 또는 KST 통일 |
| I-4 | 🟢 | 로그아웃은 열린 영상에 반영되지 않음 | 재확인은 상태·연결·카메라·무효화 키 | 본인 로그아웃 뒤 최대 30분 | 수용 |

## 연결·계정 상태 변화 반영

| 경로 | 열린 MJPEG | 티켓 발급/소비 | camera-analysis |
|---|---|---|---|
| 보호자·피보호자·관리자 해제 | 60초 안 REVOKED | 403 | 다음 발송부터 제외(매번 조회) |
| 탈퇴(보호자) | 60초 안 종료 | 401 / 403·401 | 세션 closer + WS 게이트 + 연결 없음 |
| 탈퇴(피보호자) | 60초 안 종료 | 403 → 404 | 미발송 |
| 역할 변경 | 60초 안 종료(무효화·카메라 삭제) | 401 / 403·404 | 미발송 |
| 정지 | 60초 안 종료 | 401 / 403 `INACTIVE_USER` | WS 게이트 차단 |
| 카메라 삭제 | 60초 안 종료 | 404 | 미발송 |
| 카메라 비활성화 | 60초 안 종료 | 404 | **계속 발송(L-1)** |
| 비밀번호 변경 | 무효화 키로 60초 안 종료(Redis 장애 시 미반영 - 문서화) | 401 | 세션 closer |
| 로그아웃 | 미반영(I-4) | 발급 티켓 60초 유효 | 기존 동작 |

## PASS

- STOMP 구독은 범용 userId 인가로 보호, 클라이언트 SEND `/app/`만, 페이로드는 String 필드 record.
- 수신기: 판정이 먼저, 예외 격리, `session_status` 처리는 DB 없음, `ended`는 `retainAll` 직전 계산, 대시보드 지표 의미 불변, 발송 순서 사실상 FIFO.
- 보안 매처: `/api/camera/stream/*/mjpeg` GET 하나(다른 메서드·경로 인증 필요), 헤더가 있으면 JWT 일반 검증.
- 티켓: 32바이트 SecureRandom·형식 선검증·GETDEL·불일치 `[IDOR-ATTEMPT]`·Redis 장애 503.
- 노출: AI 키 헤더 전용·로그는 클래스명/status, 403·404에 소유자 정보 없음, base-url 고정, 세션 ID `/`까지 인코딩.
- 자원: Slots compute·semaphore 정합, 이중 close 안전, attach·stop 경합 처리, pump 예외 경로 반납 보장. 종료 순서 Boot 4.0.8 `GRACEFUL_SHUTDOWN_PHASE = MAX - 1024` → Slots 먼저.
- OSIV off, 응답 버퍼링 필터 없음, Tomcat 200 스레드 vs 상한 20.
- 공용: global→domain import 0건, 이름 없는 `@Async` 없음, ErrorCode 추가만, `DetectedTypeLabel.of()` 불변.
- gosky nginx: `X-Accel-Buffering: no` 존중(`proxy_ignore_headers` 없음), `proxy_read_timeout` 1200초, `proxy_intercept_errors` off. 운영 DB 활성 카메라 1대 + ACTIVE 연결 1건 → 보호자 계정으로 E2E 가능.

## 미확인

- AI `session_status` 실제 페이로드와 파서 대조 / JDK 리다이렉트 시 사용자 헤더 재전송 여부 / Tomcat 쓰기 타임아웃 실값 / 브라우저 `<img>` → nginx → 백엔드 → AI E2E.

## 분류

- 즉시 수정 권장: M-1
- 결정 필요: L-1(필터 추가 vs 정책 명시)
- 한 줄 하드닝: L-2
- 후속·수용: L-3·L-4·I-1~I-4

## 반영 (2026-10-04, branch `fix/camera-stream-audit`)

사용자 결정: M-1·L-2 즉시 수정, L-1은 "분석 상태도 막기"(추천안).

| # | 반영 |
|---|---|
| M-1 | `LiveAnalysisBroadcaster.clear()`가 비우기 전에 **상태를 보낸 적 있는 세션**에 `status=null`(분석 필드도 null)을 보낸다. 분석이 송출 상태보다 먼저 오면 `running`으로 채워, 메시지의 `status=null`은 "AI 연결이 끊겨 확인 불가" 한 가지 뜻만 갖는다. FE 노션에 한 줄 추가 |
| L-1 | 팬아웃이 `CameraService.findActiveOwnerBySessionId`(활성 카메라만)를 쓴다. 감지·화재 알림 경로는 `findOwnerBySessionId` 그대로(ANOM-G08 유지) |
| L-2 | 목록·상태·스냅샷 팩토리(`prepareConnection`)와 MJPEG 연결 모두 `setInstanceFollowRedirects(false)` - 30x는 장애(503) |

- 테스트 +5: 끊김 시 확인 불가 발송·미발송 세션 무시·분석 먼저 running(broadcaster), 활성 주인 조회(CameraService), 리다이렉트 미추종(실 HTTP). `./gradlew clean build` 단위 **1173 / 실패 0**.
- 후속으로 남김: L-3(전체 시간 제한)·L-4(속도 제한)·I-1(거부 로그 분리)·I-3(시각 오프셋 - FE 안내).

## 후속 반영 - L-3·L-4 (2026-10-04, branch `fix/camera-stream-limits`)

| # | 반영 |
|---|---|
| L-3 | 목록·상태·스냅샷 호출에 **전체 제한 `callTimeout`(기본 8초)**. 헤더 대기는 마감 시 스케줄러가 연결을 끊고, 본문은 덩어리 단위로 읽으며 읽을 때마다 마감을 확인한다. 이 호출들도 Spring 응답 래퍼 대신 `HttpURLConnection` 직접 사용(래퍼 `close()`는 drain해 중단 의미가 없어짐). JSON 1MB·스냅샷 5MB 상한 |
| L-4 | 보호자 ID 기준 분·시간 이중 윈도우 속도 제한(`RateLimitService`, Redis 장애 시 fail-open): 목록 30/600, 상태 30/600, 스냅샷 60/1200, 티켓 20/300. 인가·AI 호출보다 먼저 검사, 초과 429 `TOO_MANY_REQUESTS` + `Retry-After` |

- **확인한 JDK 동작**: 본문을 읽는 중에는 다른 스레드의 `disconnect()`가 읽기를 깨우지 못하고(조금씩 흘리는 서버로 10초 블록 재현), 마감에 끊긴 스트림은 예외 대신 EOF로 끝나기도 한다. 그래서 본문 단계는 읽기 사이 마감 확인 + 마감 경과 시 잘린 본문을 실패 처리. **최악 = callTimeout + readTimeout(5초)** - 이전에는 상한이 없었다.
- MJPEG 중계 루프는 AI가 0.2초마다 프레임을 보내 매 반복에서 종료·재확인 조건을 보므로 같은 JDK 동작의 영향이 없다.
- 테스트 +4: 헤더 지연·본문 흘림이 전체 제한(1초)에서 끊김(실 HTTP, 1.0초·1.35초), API별 한도 검사, 한도 초과 시 인가·AI·티켓 전 차단. `./gradlew clean build` 단위 **1177 / 실패 0**.
- 남은 정보 항목: I-1(거부 로그 분리)·I-3(시각 오프셋 - FE 안내).

## 후속 반영 - I-1·I-3 (2026-10-04, branch `fix/camera-stream-info`)

| # | 반영 |
|---|---|
| I-1 | `liveAnalysisExecutor` 폐기 로그를 `[LIVE-ANALYSIS-REJECTED]` **WARN**으로 분리(알림 풀은 `[NOTIFY-REJECTED]` ERROR 그대로). 화면 상태 1건 폐기가 SOS·화재 알림 유실처럼 보이지 않게 |
| I-3 | AI 시각(`lastFrameAt`)을 읽을 때 KST로 바꿔 싣는다 - `/live`·`/status`의 `lastFrameAt`과 `analysis.analyzedAt`·STOMP `analyzedAt`이 모두 `+09:00` |

- 테스트 +2: 실시간 분석 풀 포화 정책(호출 스레드 실행·예외 없음), 폐기 로그 태그·레벨(로그 캡처). AI 시각 KST 비교로 갱신. `./gradlew clean build` 단위 **1179 / 실패 0**.
- 이로써 이 점검의 항목은 모두 반영됐다(M-1·L-1~L-4·I-1·I-3, I-2·I-4는 수용).
