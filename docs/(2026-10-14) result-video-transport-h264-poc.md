# 영상 전송 H.264 PoC 결과와 판정 - 현행 JPEG 유지(C) 확정 (2026-10-14)

> 상태: **종결. 판정 = C(현행 유지: 송출 JPEG 업로드 -> AI -> BE 중계 MJPEG).** H.264(안 B-lite)는 구현·검증을 마쳤으나 **머지·배포하지 않고 보류**한다.
> 근거: 이 문서의 시험 결과, `(2026-10-13) design-video-transport-be.md`, `(2026-10-14) design-video-transport-be-ticket.md`(대체됨).
> 운영 영향: **없음.** 서버·FE·AI 코드와 `.env`는 바뀌지 않았다(문서만 `dev`에 반영).

## 1. 한 줄 결론

감지 결과·프레임 간격·안정성은 JPEG와 H.264가 **같고**, 차이는 **필요 대역폭(H.264가 78~93% 적음)**과 **서버 부담(H.264가 AI CPU·복잡도를 더함)**뿐이다. 우리 사용 환경(시연·시험용, 노트북 유선 1Gbps / 태블릿 Wi-Fi 100Mbps 또는 5G 테더링 / 5G 휴대폰 무제한)에서는 대역폭이 병목이 아니므로 **H.264의 이점이 없고 비용만 생겨 JPEG를 유지한다.**

## 2. 무엇을 만들고 검증했나

안 B-lite: 송출만 H.264, 시청(BE의 MJPEG 중계)은 변경 없음.

```
FE ─wss(1회용 ut_ 티켓)→ BE /ws/camera-upload ─내부 WS + X-API-Key→ AI ingest-ws → ffmpeg 디코드 → JPEG → 기존 분석·방송·클립
```

| 저장소 | 브랜치(보존, 머지 안 함) | 내용 |
|---|---|---|
| BE | `feature/video-transport-ws-upload` | 업로드 티켓 발급(`POST /api/ward/camera/{id}/upload-ticket`), `/ws/camera-upload` 중계, `transport` 필드, 시험 하니스(`tools/video-transport-poc`), 통합 계약 문서 |
| AI | `feature/ingest-ws-h264` | `/api/v1/stream-sessions/{id}/ingest-ws`(기본 꺼짐), ffmpeg 디코더, 수신 처리 공유 함수 추출, PoC 시험 앱(`tools/ingest_poc`) |
| FE | `feature/h264-sender` | `h264Sender`(WebCodecs), `selectTransport`, 캡처 틱 분기(PoC 플래그 꺼짐 시 기존 JPEG 경로 그대로) |

통합 계약(메시지 형식·티켓·수명·폴백·Redis 장애 정책)은 BE 브랜치의 `docs/(2026-10-14) design-video-transport-contract.md`에 있다.

## 3. 시험 결과

시험 환경: vkcs-linux 별도 compose(전용 Postgres·Redis, 운영 스택과 분리), gosky 별도 컨테이너(운영 모델·임계값 읽기 전용 재사용). 영상은 모두 **가상 장면**(실제 가정 영상 미사용).

### 3-1. JPEG vs H.264 (1280x720, 15fps, 각 120초, vkcs)

| 장면·경로 | 전송량 | 수신 | 간격 max / p99 | PSNR | BE CPU | AI CPU |
|---|---|---|---|---|---|---|
| busy · JPEG | 1.52MB/s | 1800/1800 | 69 / 68ms | 31.4dB | 0% | 4% |
| busy · H.264 | 0.19MB/s | 1798/1800 | 284 / 71ms | 27.0dB | 1% | 20% |
| calm · JPEG | 0.84MB/s | 1800/1800 | 69 / 68ms | 30.3dB | 0% | 4% |
| calm · H.264 | 0.19MB/s | 1798/1800 | 71 / 70ms | 29.5dB | 1% | 16% |

gosky에서 AI CPU는 JPEG 37.8% -> H.264 42.3%(+4.5%p, 감지 추론 포함). vkcs(구형 Xeon)는 +12~16%p.

### 3-2. 감지 일치 (gosky, 운영 모델·임계값, 장면당 60초, 합성 불꽃)

| 장면 | H.264 1.5Mbps | H.264 2.5Mbps |
|---|---|---|
| fire(전체) | danger 일치 100%, 종류 100%, 신뢰도 차 중앙 0.011, 누락 0, 첫 danger 차 0초 | 동일(0.010) |
| smallfire(작은 대상) | 100%, 누락 0, 차 0초(신뢰도 차 중앙 0.013) | 동일(0.017) |
| calm(정상) | 오탐 없음 | 오탐 없음 |

AUD 적용 후 3회 반복에서도 100%·차 0초. 한계: 합성 장면, libx264 인코더(브라우저 하드웨어 인코더 아님), 낙상·흉기는 합성 곤란으로 미비교.

### 3-3. 변환 지연 (수신 -> JPEG)

최초 p50 71.5ms·창별 max 157ms(ffmpeg raw H.264 파서가 다음 유닛의 시작을 봐야 프레임을 내보내 출력이 1프레임 늦음). **유닛 뒤에 AUD(NAL 9)를 붙여 해소**: p50 4.7ms, 창별 max 10.4ms. 같은 원인으로 `-fflags nobuffer`는 프레임을 잃으므로 쓰지 않는다.

### 3-4. 30분 안정 (vkcs, busy, 재연결 5회)

송출 27,000장 -> 수신 26,988장(0.04%, 연결당 디코더 버퍼 2장) / 간격 max 212ms(p99 71ms) / 종료 코드 전부 1000 / ffmpeg 프로세스 0 -> 0 / BE 메모리 +7MiB / 5회 재연결 뒤 클립 정상(200, WebM, 1280x720) / CPU BE 1%·AI 20% 안정.

### 3-5. 폴백·장애 (서버 측)

| 케이스 | 결과 |
|---|---|
| 킬 스위치 `camera.upload.ticket-enabled=false` | 티켓 503 `CAMERA_UPLOAD_UNAVAILABLE`, JPEG 정상 |
| 플래그 제거(`CAMERA_WS_H264_SESSION_IDS` 비움) | 티켓 409 `CAMERA_UPLOAD_NOT_ENABLED`, JPEG 정상 |
| AI `INGEST_WS_ENABLED=false` | 연결 0.43초에 1008 `INGEST_DISABLED` 전달, JPEG 정상 |
| AI 서버 중지 | 핸드셰이크 503이 3.1초에 반환 |
| Redis 장애 | 새 티켓 503, 수립된 연결은 유지(600장 송신·종료 없음, 간격 max 317ms) |
| 토큰 무효화 | 62.2초에 1008 `AUTH_INVALIDATED` 종료, 새 토큰은 티켓 200 |

브라우저 측 폴백(`selectTransport`·재연결 1회·JPEG 이어 가기)은 FE 단위 테스트(vitest)로만 고정했고 **실기기에서는 확인하지 못했다.**

### 3-6. 대역폭 제약 (gosky 시험 컨테이너, 단일 업링크 FIFO 모델, 60초)

| 업링크 | JPEG(fire) 실효 fps / 지연 p50 | H.264 실효 fps / 지연 p50 |
|---|---|---|
| 8Mbps | 15.0 / 80ms | 15.0 / 15ms |
| 5Mbps | 11.4(손실 24%) / 144ms | 15.0 / 21ms |
| 3Mbps | 7.1(손실 53%) / 265ms | 15.0 / 34ms |
| 2Mbps | 4.8(손실 68%) / 415ms | 15.0 / 50ms(p95 101ms) |
| busy 8 / 5Mbps | 10.1 / 6.3fps | 15.0 / 15.0fps |

### 3-7. 소스별 대역폭 (합성 장면, JPEG 품질 0.8 vs H.264 1.5Mbps)

| 소스 | JPEG | H.264 | PSNR JPEG / H.264 |
|---|---|---|---|
| 화면 공유 · 정지 | 13.4Mbps (6.0GB/시간) | 1.2Mbps | 38.9 / 38.7dB |
| 화면 공유 · 스크롤 | 11.9Mbps | 1.3Mbps | 39.8 / 38.8dB |
| 후면 카메라 · 실내 | 8.2Mbps (3.7GB/시간) | 1.5Mbps | 35.0 / 34.1dB |
| 후면 카메라 · 저조도(잡음 많음) | 22.9Mbps (10.3GB/시간) | 1.5Mbps | 26.7 / 26.2dB |

JPEG 대역폭은 장면에 따라 8~23Mbps로 변하고 H.264는 1.5Mbps로 고정된다. 저조도 값은 센서 잡음 단순 모델이라 실제 폰에서는 더 낮을 가능성이 크다.

## 4. 판정과 이유

**C(현행 유지)로 확정한다.**

| 우리 환경 | 업링크 | JPEG 필요량 | 판단 |
|---|---|---|---|
| 노트북 화면 공유(유선) | 1Gbps | 6~13Mbps | 병목 없음 |
| 태블릿 | Wi-Fi 100Mbps 또는 5G 테더링 | 8~23Mbps | 병목 없음 |
| 5G 휴대폰(후면 카메라, 시연·시험용, 무제한) | 보통 10~50Mbps 이상 | 8~23Mbps | 데이터·발열 문제 없음 |

- H.264의 이점(대역폭·데이터 절감)이 필요한 상황이 없다.
- H.264는 AI CPU(+4.5%p gosky)와 구성 복잡도(티켓·WS 중계·디코더·AUD·폴백 상태기계)를 더하고, 화질이 약간 낮으며, 브라우저 하드웨어 인코더 의존이라 기기 호환 위험이 있다.
- 운영 AI 컨테이너 재시작(송출 세션 끊김)·BE 수동 배포 위험을 감수할 이득이 없다.

## 5. 이 판정이 의미하는 것 (현재와의 차이)

**현재와 달라지는 것은 없다.** `dev`에는 이 문서만 추가된다. 운영 코드·설정·배포 상태 변화 없음, `transport` 필드·업로드 티켓·`/ws/camera-upload`는 머지되지 않았다(브랜치 보존).

## 6. 현장 점검(PoC 없이 대비)

시연 장소의 5G 신호가 약하면 업링크가 10Mbps 아래로 내려가 JPEG가 프레임을 잃을 수 있다(3-6 참고).

1. 시연 전 휴대폰에서 업로드 속도를 측정한다. 안정적으로 **15Mbps 이상**이면 그대로 진행한다(밝은 곳 권장 - 저조도는 JPEG가 커진다).
2. 15Mbps 미만이면 FE의 `MAX_CAPTURE_WIDTH`(1280 -> 960)와 JPEG 품질(0.8 -> 0.6)을 낮춘다(대역폭 대략 절반 이하, 서버 변경 불필요). 감지 정확도 영향은 별도로 확인한다.
3. 그래도 안 되면 아래 재개 조건에 따라 H.264를 해당 카메라에만 켠다.

## 7. 재개 조건 (이 중 하나라도 생기면 다시 검토)

1. 현장 업링크가 15Mbps 미만이거나 JPEG 프레임 손실이 **실측으로** 확인됨
2. 휴대폰·태블릿을 상시(24시간) 카메라로 전환해 데이터·발열이 문제가 됨
3. 카메라 대수가 늘어 서버 수신 대역폭이 부담이 됨(현재 등록 상한 3대)

재개 시 순서: 브랜치 3개 리베이스 -> PR(AI -> BE -> FE, 모두 플래그 off) -> 운영 AI 재시작 시각 합의 -> `.env.dev`(`CAMERA_UPLOAD_WS_URL`·`CAMERA_WS_H264_SESSION_IDS`, 백업 후) -> 실기기 1대 측정 -> 해당 카메라만 `CAMERA_WS_H264_SESSION_IDS`에 지정. nginx(`api.devdmu.gosky.kr`는 `location /` 하나로 Upgrade 통과, 타임아웃 1200초)는 변경이 필요 없음을 확인했다.

## 8. 미확인으로 남은 것

- 브라우저 `VideoEncoder`(하드웨어 H.264) 지원·프레임당 슬라이스 수·탭 숨김 시 동작·폰 발열·배터리(실기기 시험 안 함)
- 실제 거실 영상·낙상·흉기에 대한 감지 일치
- gosky 운영 서버의 장시간 시험(안정 시험은 vkcs에서 수행)

## 9. 시험 환경 처리

시험 인스턴스는 정지했고 운영 서비스(`dmu-dev-*`, `silverbridge-ai-server`)는 건드리지 않았다. 재개를 대비해 보존한다.

| 위치 | 내용 | 시작 방법 |
|---|---|---|
| vkcs-linux `~/ws-poc` | compose(`wspoc`), `.env`(무작위 비밀, 실제 서비스 키 아님), 하니스 | `docker compose --env-file .env up -d db redis ai be` |
| gosky `/home/apps/ws-poc-gosky` | 시험 AI 컨테이너(`wspoc-ai-gosky`, 127.0.0.1:6592), 하니스 | `./h.sh` 래퍼, `docker start wspoc-ai-gosky` |

폐기하려면 두 디렉터리와 컨테이너·이미지(`wspoc-*`)를 삭제하면 된다(운영과 공유하는 볼륨 없음).
