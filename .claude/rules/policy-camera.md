---
paths:
  - "**/domain/camera/**"
  - "**/*AiLiveStream*"
  - "**/*AiStream*"
  - "**/*Mjpeg*"
  - "**/domain/admin/**/*Camera*"
---

# 도메인 정책 - 카메라·실시간 영상 중계

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **보호자 실시간 카메라 보기**(camera, 2026-10-03): 영상·상태는 **FE → 백엔드 → AI 중계**(브라우저는 AI에 직접 붙지 않는다, AI 키는 서버 헤더로만). `GET /api/guardian/camera/live`·`/{sessionId}/status`·`/latest-frame`·`POST .../stream-ticket`(GUARDIAN 전용, ACTIVE 연결 + 활성 등록 카메라만 - `getViewableCamera` 한 곳), `<img>`는 `GET /api/camera/stream/{id}/mjpeg?ticket=`(1회용 60초 티켓, 이 경로만 permitAll). 시청 중 60초 재확인·최대 30분·동시 20/1인 2. 분석 상태는 STOMP `camera-analysis`(**WS 전용**, 상태 변화 시만 2초 간격, `anomaly-detected`와 별개). **권장 송출 10fps**(`camera.recommended-fps`, 2026-10-07 5에서 상향, 동시 1~3대 기준 - 4대 이상이면 낮추거나 AI 분석 스레드 증설). **서비스 전체 카메라 등록 상한 3대**(`camera.max-total`, env `CAMERA_MAX_TOTAL`, 2026-10-14, 0=끔): 새 카메라 등록만 막고(409 `CAMERA_LIMIT_REACHED`) 같은 기기 재등록·삭제는 무관, 동시 등록은 어드바이저리 락으로 한 줄로 세운다. gosky는 15fps(`CAMERA_RECOMMENDED_FPS=15`, 2026-10-14 시험 유지). MJPEG는 `HttpURLConnection.disconnect()`로 닫는다(Spring 응답 `close()`는 drain해 멈춤). 상세 rules 파일.
  - **관리자 디바이스 관리**(2026-10-23, 마이그레이션 없음): `GET /api/admin/camera?status=ALL|LIVE|OFFLINE&q=`·`/summary`(ADMIN, **조회 전용** - 삭제·점검 요청 없음). `liveStatus`는 대시보드와 같은 AI 구독 집합 기준이고 AI가 끊겼거나 재접속 뒤 첫 목록 전이면 **null**(`liveSessionSnapshot()`) - 그때 LIVE·OFFLINE 필터는 503 `CAMERA_LIVE_STATUS_UNKNOWN`. 꺼 둔 카메라도 개수에 포함(`live+offline=registered`), 탈퇴 처리 중 피보호자 제외, `deviceId`·영상·클립 미노출, 메모리 필터(근거 = 상한 3대). 상세 rules 파일·`docs/(2026-10-23) feature-admin-camera-management.md`.

## AI 세션 목록 재동기화 - 방송 누락 안전망 (2026-10-01 QA BE-1)

- AI는 세션 **생성·종료 때만** 목록을 broadcast한다. 방송이 한 번 빠지면 등록된 카메라가 에러 없이 감시에서 빠지는데(조용한 침묵), 미등록 세션 로그가 DEBUG라 운영에서도 보이지 않았다.
- `AiLiveStreamSubscriber`가 `anomaly.resync-seconds`(기본 60, 0이면 끔)마다 `list`를 다시 요청한다(연결이 없으면 건너뜀). 놓친 세션은 이때 구독된다. **등록 카메라가 라이브인데 subscribe 전송이 실패하면 WARN**을 남긴다. 미등록 세션은 로그 폭주를 막으려고 DEBUG 그대로다.
- 이 주기 요청을 지우거나 길게 늘리면 방송 누락이 다시 침묵으로 돌아간다. 재접속 백오프·카메라 등록 시 `list` 요청과는 별개 경로다.

## 보호자 실시간 카메라 보기 - 영상은 백엔드가 인가 후 중계한다 (2026-10-03)

- **경위**: FE 무인증 프록시(`/api/streams/**`)가 서버 AI 키를 붙여 AI 전 경로로 넘기고, 보호자 화면이 AI 전체 세션을 그대로 보여 **로그인 없이 누구나 모든 집 영상을 볼 수 있었다**. AI 키도 브라우저 WS URL(`?apiKey=`)에 노출됐다. 2026-07-03 설계의 "백엔드는 영상을 프록시하지 않는다"를 뒤집어 **FE → 백엔드 → AI 완전 중계**로 바꿨다. 상세 `docs/(2026-10-03) feature-ai-stream-relay.md`.
- **불변 규칙 ①(열람 범위)**: 시청·상태·스냅샷·티켓은 **보호자 전용**이고 **ACTIVE 연결된 피보호자의 활성 등록 카메라**만이다. 모든 경로가 `CameraService.getViewableCamera` 한 곳을 지난다(`isActiveConnection`만, `getMyWards` 금지). 위반은 403 `CAMERA_NOT_CONNECTED` + `[IDOR-ATTEMPT]`, 없는·꺼진·백엔드 미등록 세션은 404(미등록은 AI에 송출 중이어도 주인을 모른다). 피보호자용 시청 API를 만들려면 이 정책부터 바꿀 것.
  - **예외 - 피보호자는 본인 카메라의 정지 화면만 본다 (2026-10-15, 사용자 결정)**: `GET /api/ward/camera/{id}/latest-frame`(WARD 전용). 다른 기기로 등록한 카메라가 어느 방을 비추는지 알아보려고 열었다(모르면 등록 해제 후 재등록해야 했다). 본인 카메라만(`CameraService.getOwnedSessionId` = `getOwnedCamera`, 남의 것은 403 `CAMERA_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`), 그 기기가 이미 보낸 마지막 프레임만 돌려주며 **카메라를 원격으로 켜거나 끄지 않는다**. 피보호자 ID 기준 분 30·시간 600. **실시간 영상(MJPEG)·티켓은 그대로 보호자 전용** - 피보호자에게 영상 중계를 열려면 동시 시청 자리·티켓 정책부터 다시 정할 것.
- **불변 규칙 ②(`<img>`는 1회용 티켓)**: 영상 경로(`GET /api/camera/stream/*/mjpeg`)만 permitAll이고 인증은 **한 사용자·한 세션·60초·1회** 티켓(`GETDEL`)이다. **access token을 쿼리로 받지 말 것** - 프록시·접속 로그에 30분짜리 전권 토큰이 남는다. 소비 시점에 계정 상태(`!= ACTIVE` → 403)·토큰 무효화(발급 뒤 무효화 → 401)·연결을 다시 확인한다.
- **불변 규칙 ③(열린 영상도 끊는다)**: 시청 중 60초마다 계정·연결·카메라·무효화를 재확인해 끊고, 영상 1건은 최대 30분이다(AI MJPEG는 세션이 끝나도 스스로 끝나지 않는다). DB 확인이 실패하면 끊고, 무효화 키(Redis) 조회만 실패하면 이어 간다.
- **불변 규칙 ④(AI 키는 서버 안에서만)**: `X-API-Key` 헤더로만 보내고 로그·응답에 남기지 않는다. FE에 AI 주소·키를 다시 내리지 말 것.
- **불변 규칙 ⑤(분석 상태는 WS 전용)**: STOMP `camera-analysis`는 디스패처를 거치지 않아 **푸시·문자·알림 이력이 없다**. 화면 표시용이라 상태가 바뀔 때만·같은 카메라 최소 2초 간격으로 ACTIVE 보호자에게 `sendToUser`로만 보낸다(정지 계정 게이트 적용). `anomaly-detected`(판정을 거친 화재 알림)와 섞지 말고, 이 경로에 푸시를 붙이지 말 것.
- **`camera-analysis`의 `status=null`은 "확인 불가"다 (2026-10-04 점검 M-1)**: AI 연결이 끊기면 상태를 보낸 적 있는 세션에 `status=null`을 보낸다(비우기만 하면 마지막 "정상"이 화면에 남는다 - "모르는 값을 아는 값처럼 보이지 않는다"). 분석이 송출 상태보다 먼저 오면 `running`으로 채워 `null`의 뜻을 하나로 지킨다. **꺼진 카메라에는 보내지 않는다**(L-1, `findActiveOwnerBySessionId`) - 감지·화재 알림은 `is_active`와 무관하게 계속된다(ANOM-G08). AI 호출은 리다이렉트를 따라가지 않는다(L-2, 키 헤더 유출 방지).
- **불변 규칙 ⑥(MJPEG는 Spring 응답 래퍼로 닫지 말 것)**: `SimpleClientHttpResponse.close()`는 본문을 끝까지 읽어(drain) 끝없는 MJPEG에서 영원히 멈춘다 - 자리·요청 스레드 누수. `AiMjpegStream`이 `HttpURLConnection.disconnect()`로 소켓을 바로 끊는다(`AiStreamClientTest`가 고정).
- **호출 전체 시간 제한·속도 제한 (2026-10-04 점검 L-3·L-4)**: AI 목록·상태·스냅샷은 `callTimeout`(8초) 안에 끝낸다 - 헤더 대기는 연결을 끊고, 본문은 읽기 사이에 마감을 확인한다(본문을 읽는 중에는 `disconnect()`가 읽기를 깨우지 못하는 JDK 동작 때문, 최악 = 8초 + 읽기 5초). 마감이 지나면 EOF로 끝났어도 잘린 본문으로 보고 실패시킨다 - 이 확인을 빼지 말 것. 보호자 카메라 API 4종은 보호자 ID 기준 분·시간 속도 제한(목록·상태 30/600, 스냅샷 60/1200, 티켓 20/300)을 인가보다 먼저 건다(Redis 장애 시 fail-open - 시청은 보조 방어로 막지 않는다).
- **실시간 분석 풀의 폐기는 알림 장애가 아니다 (2026-10-04 점검 I-1)**: `liveAnalysisExecutor`는 포화 시 `[LIVE-ANALYSIS-REJECTED]` WARN으로 폐기한다(다음 상태 변화 때 최신 상태가 다시 나간다). 알림 풀의 `[NOTIFY-REJECTED]` ERROR와 섞지 말 것 - 섞으면 진짜 알림 유실이 묻힌다. AI 시각은 응답에 KST로 싣는다(I-3).
- **동시 시청 상한**: 전체 20 / 1인 2(초과 429). 영상 1건이 요청 스레드 1개를 붙들므로 상한을 없애면 SOS·로그인 같은 일반 API까지 멈춘다. 상한·티켓은 단일 인스턴스 기준이다.
  - **같은 보호자가 같은 카메라를 다시 열면 이전 중계를 닫고 새로 연다 (2026-10-15)**: 화면을 다시 열거나 송출이 껐다 켜지면 이전 연결이 재확인(60초)·30분 상한까지 자리를 쥐어, 상한은 지키면서 정작 보려는 영상이 429로 거절됐다(`CameraStreamSlots.acquire(userId, sessionId)`). **다른 카메라의 중계는 닫지 않는다** - "가장 오래된 영상 자동 종료"는 상한 정책 변경이라 하지 않았다. FE도 `<img>`를 뺄 때 `src`를 지워 연결을 끊는다(브라우저는 DOM에서 빼기만 하면 MJPEG 연결을 유지했다).
- **수용한 한계**: 연결 해제·정지 반영은 최대 60초 지연 / FE가 프록시를 줄이고 `NEXT_PUBLIC_STREAM_WS_URL`을 지우기 전까지 무인증 구멍은 남는다(송출 업로드 경로는 범위 밖이라 프록시에 남음).

## 2026-10-02 QA(BE) 반영 · 
### 카메라 등록·중지 카메라 (P14, ANOM-G08)

- **카메라 등록 DTO**: 방 이름은 수정 경로와 같은 검증(`@NotBlank`·`@VisibleText`·`@NoControlChars`·최대 30자), 기기 토큰은 최대 64자다. 등록과 수정의 검증을 서로 다르게 두지 말 것.
- **`camera.is_active`는 사용자 표시용 플래그이며 감지·알림을 끄지 않는다**(2026-10-02 결정, ANOM-G08 - 문서화로 종결, 코드 변경 없음). 안전 알림을 줄이는 방향은 금지이고 FE에 "중지" UI도 없다. 알림을 끄는 의미로 쓰려면 정책부터 바꿀 것.
- **방은 정해진 8개, 피보호자당 방마다 1대 (2026-10-05, V58)**: `CameraRoom`(거실·침실·주방·화장실·현관·베란다·작은방·작은방2) 밖이면 400 `CAMERA_ROOM_INVALID`, 같은 피보호자의 다른 카메라가 쓰는 방이면 409 `CAMERA_LABEL_DUPLICATED`(같은 기기·같은 방 재등록은 멱등). 서버가 막는 이유는 FE 버튼만 믿으면 API 직접 호출로 임의 이름·중복이 들어오기 때문이다. DB에는 한글 방 이름을 그대로 저장한다(알림 문구·이력·검색이 그 문자열을 쓴다) - enum 이름으로 바꿔 저장하지 말 것. 동시 등록은 `uq_camera_ward_label`이 막고 서비스가 같은 409로 바꾼다 - 새 등록은 id가 IDENTITY라 `save()`에서, 방 변경은 `flush()`에서 위반이 나므로 **둘 다 변환 범위에 둘 것**(기능 점검 H-1: `flush()`만 감싸 새 등록 경합이 일반 `DUPLICATE_VALUE`로 샜다). 제약 이름을 바꾸면 `CameraService.ROOM_UNIQUE_CONSTRAINT`도 함께 바꿀 것. 사용 중지 카메라도 방을 차지한다(삭제해야 빈다).
- **피보호자 연결 상태는 별도 API (2026-10-05)**: `GET /api/ward/camera/live`가 보호자 `/live`와 같은 기준으로 상태를 붙인다(AI 장애 시 `null` = 확인 중, "연결 안 됨"으로 채우지 않는다). 기존 `GET /api/ward/camera`에 AI 호출을 붙이지 말 것 - 송출 기기가 `sessionId`를 받는 경로가 AI 호출 제한(8초)만큼 늦어진다.

- **서비스 전체 등록 상한 (2026-10-14)**: `camera.max-total`(env `CAMERA_MAX_TOTAL`, 기본 3, 0 이하면 끔)이 **전 사용자 합산** 카메라 수를 막는다. 근거는 15fps에서 AI 동시 분석이 3대(사용률 약 86%)까지라는 실측 환산이고, 학생 프로젝트 규모의 결정이다. 새 카메라를 만드는 `register` 분기에서만 검사하며 초과 시 409 `CAMERA_LIMIT_REACHED`다. 방 검증(400)·방 중복(409)이 먼저다.
  - **같은 기기 재등록·방 변경·삭제는 상한과 무관**하다(재등록이 막히면 이미 등록한 사용자의 송출 설정이 깨진다).
  - **동시 등록 경합은 `pg_advisory_xact_lock`으로 막는다**(트랜잭션 수준, 마이그레이션 없음). 카운트 → INSERT 사이를 한 줄로 세우는 락이라 **빼거나 카운트를 락 앞으로 옮기지 말 것**(`CameraTotalLimitConcurrencyIntegrationTest`가 8건 동시 × 5회로 고정, vkcs에서 실행).
  - 상한은 **등록 수**라 동시 송출 상한도 함께 보장한다(송출은 등록된 카메라만). AI 분석 스레드를 늘리면 `CAMERA_MAX_TOTAL`로 올린다. 사용 중지(`is_active=false`) 카메라도 등록 수에 센다.
  - **한 계정의 슬롯 독점은 수용한다(2026-10-14 점검 M-1)**: 피보호자 한 명이 방 3개를 등록하면 다른 사용자가 등록할 수 없고, 관리자 카메라 삭제 API가 없어 풀려면 DB 수동 삭제(또는 역할 변경·강제 탈퇴)뿐이다. 피보호자당 상한·관리자 삭제 API는 필요해지면 그때 설계한다.
  - 상한을 넘는 기존 데이터는 자동으로 지우지 않는다(신규 등록만 막는다). 상세 `docs/(2026-10-14) feature-camera-total-limit.md`.

- **송출 끊김 후속 (2026-10-14)**: gosky 권장 fps는 **15 유지**(`CAMERA_RECOMMENDED_FPS=15`, `max-total` 3과 짝, FE-1·FE-2 배포 후). 사용자 요청 상한은 분당 600 그대로다. `[USER-RATE-LIMIT]` 로그에 `userId`·`method`·경로 템플릿(숫자·긴 식별자는 `{id}`, 쿼리스트링 제외)을 남긴다 - 토큰·티켓·IP 원문은 금지. 피보호자 `GET /api/ward/camera/live`에도 `recommendedFps`가 내려간다(가산). **FE가 로그인 확인 값을 30초까지 캐시하는 것은 수용**한다(경량 로그인 확인 엔드포인트는 만들지 않는다). STOMP CONNECT 헤더 토큰은 핸드셰이크를 익명으로 열어야 해 보안이 낮아지므로 이번에 만들지 않았다(쿼리 `?token=` 유지).


## 카메라 세션 ID - 사용자 식별자를 싣지 않는다 (2026-10-05 QA 종합 점검)

- **경위**: 세션 ID 형식이 `ward_{wardId}_{6자}`였다. 세션 ID는 AI 서버·영상 경로·FE 프록시로 바깥에 보이는 값이라, FE 프록시 무인증(Critical, FE 별도 작업)과 엮이면 비로그인으로 얻은 세션 ID에서 **피보호자 ID**를 꺼내 AI 서버의 다른 기록(챗 기록 등)을 조회하는 연쇄가 열렸다.
- **불변 규칙**: `CameraIdentifierFactory.newSessionId()`는 **`ward_` + 영숫자 16자**(약 95비트)이고 인자를 받지 않는다. 세션 ID에 사용자 ID·이름·전화번호 등 **어떤 식별자도 싣지 말 것** - "가독성"을 이유로 되살리지 말 것(`CameraIdentifierFactoryTest`가 형식을 고정). 인가는 언제나 DB 행으로 판정한다.
  - 영숫자만 쓴다 - FE·AI가 세션 ID를 URL에 인코딩 없이 넣는다(2026-10-05 전수 확인). 길이는 `VARCHAR(64)` 안.
- **기존 카메라는 바꾸지 않는다(수용)**: 같은 기기(`deviceId`, FE `localStorage`)로 재등록하면 기존 세션 ID를 재사용하므로 옛 형식은 **카메라를 삭제하고 다시 등록할 때까지 남는다**. 일괄 재발급·재등록 시 교체는 송출 설정이 깨질 수 있어 하지 않았다. 옛 형식을 없애려면 해당 카메라를 삭제 후 재등록한다.
- 백엔드·FE·AI·QA 어디도 세션 ID를 파싱하지 않는다(불투명 문자열). 새 코드도 세션 ID에서 정보를 꺼내지 말 것.


## 관리자 디바이스 관리 - 상태만 보고, 기기·영상은 보지 않는다 (2026-10-23)

- **경위**: 관리자 웹 "디바이스 관리" 화면용 조회 API(`GET /api/admin/camera`·`/summary`, 마이그레이션 없음). 상세 `docs/(2026-10-23) feature-admin-camera-management.md`.
- **불변 규칙 ①(모르는 상태는 null)**: `liveStatus`는 AI 구독 집합 기준 LIVE/OFFLINE이고, AI 연결이 끊겼거나 재접속 뒤 첫 세션 목록을 받기 전이면 `null`이다(`AiLiveStreamSubscriber.liveSessionSnapshot()`). 이때 LIVE·OFFLINE 필터는 빈 목록이 아니라 503 `CAMERA_LIVE_STATUS_UNKNOWN`이다. null을 OFFLINE·0으로 채우지 말 것(대시보드 2026-09-02와 같은 판단).
- **불변 규칙 ②(싣지 않는 칸)**: `deviceId`(재등록 키)·영상·정지 화면·클립 주소·상세 주소를 응답에 싣지 말 것(`AdminCameraItemExposureTest`). 관리자 영상 열람 금지(`AdminAnomalyClipExposureTest`)와 같은 취지.
- **불변 규칙 ③(조회 전용)**: 카메라 삭제·점검 요청을 만들지 않았다(사용자 결정 - 한 계정 슬롯 독점은 2026-10-14대로 수용). 쓰기 매핑을 더하려면 `AdminCameraControllerSecurityTest`의 고정과 이 정책부터 바꿀 것. 삭제를 넣게 되면 `CameraService.delete`(→ `CameraDeletedEvent` 클립 정리)를 타고, `AdminAuditAction` 새 값 + CHECK 재정의 마이그레이션, 감사 로그는 삭제보다 먼저.
- **모집단**: 탈퇴 처리 중(INACTIVE) 피보호자의 카메라는 뺀다(회원 관리와 같다). 꺼 둔 카메라(`is_active=false`)는 넣고 개수에도 센다 - 꺼도 감지는 계속되므로(대시보드 "끊김"은 빼고 센다, ADMIN-G20 - 두 숫자가 다를 수 있음은 수용).
- **메모리 필터·페이지**: 상태가 메모리 값이라 전체를 읽어 거른 뒤 자른다. 근거는 등록 상한 `camera.max-total` 3대다 - 상한을 크게 올리면 이 방식부터 다시 볼 것.
- 로그 `[ADMIN-CAMERA]`는 adminId·건수·소요 시간만(주소·이름·검색어 금지), 조회라 감사 로그 없음.

## 2026-10-31 전체 점검 반영 (H-1)

- **대체된 중계는 연결을 붙이는 순간 닫는다**: AI 연결을 기다리는 사이 같은 카메라를 다시 열면 이전 슬롯은 연결 없이 자리만 반납된다. `CameraStreamSlots.Slot.attach`가 반납 여부(`released`)를 보고 즉시 닫고, `CameraStreamService`는 대체된 중계를 시작하지 않는다(`CameraStreamSlotsTest.대체후_attach`). 이 확인을 빼면 상한(전체 20/1인 2) 밖의 중계가 최대 30분 돈다. `connection`·`released`는 둘 다 volatile이어야 한다(close와 엇갈려도 한쪽이 상대 값을 본다).
