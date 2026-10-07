# 카메라 권장 송출 프레임 5 → 10 상향 (2026-10-07)

## 변경
- `camera.recommended-fps` 기본값 5 → **10** (`application.yaml`, `CameraService`의 `@Value` 폴백). 환경변수 이름 `CAMERA_RECOMMENDED_FPS` 유지.
- FE는 `recommendedFps` 간격으로 캡처한다(`CameraRegisterModal.tsx startCaptureLoop(camera.recommendedFps)`). 마이그레이션 없음.
- 2026-07-03 `design-camera-domain.md`의 "5fps 고정"은 이 문서로 정정한다(그 문서는 설계 당시 스냅샷이라 고치지 않았다).

## 근거와 전제
- 사용 환경: 시연·테스트용, **동시 송출 카메라 1대(많아야 2~3대)**, 시연 장소 LG U+ 와이파이 6.
- gosky AI 실측: 1장 19.2ms(스레드 1개, 초당 최대 약 52장) → 1대 × 10장 = 사용률 약 19%, 2대 약 38%.
- 10/6 시연 기록: 한 장 왕복 약 0.12초 → FE가 2장 동시 업로드하면 네트워크로 초당 약 15장까지 가능.
- 클립 버퍼(10초·최대 150장)에 낙상 클립 9초 = 90장. 10장을 넘기면 효과 대비 휴대폰 발열·배터리 부담이 크다.
- **동시 4대 이상이면 낮추거나 AI 분석 스레드를 늘릴 것.**
- FE 송출 개선(동시 업로드 2장·720p·배포용 빌드)은 FE 팀이 별도 진행.

## 백엔드 메시지 처리 부담 (PHASE 0 확인)
`AiLiveStreamSubscriber` → `AnomalyDetectionService.handle`(`@Transactional`) → `AnomalyJudge`.
- 정상 프레임(danger=false·normal·unknown): DB 조회 0, Redis 0(트랜잭션만 열림).
- 위험 프레임 이력 쿨다운(1분) 안: Redis `tryAcquire` 1회.
- 쿨다운 통과(분당 최대 1건): 소유자·상황·사용자 조회 + 이력 저장. fps와 무관.
- `camera-analysis` 브로드캐스트는 메모리 갱신 + 상태 변화 시만·2초 간격이라 fps에 비례하지 않는다.
- 초당 10~30건에서도 부담 없음. 정상 프레임이 트랜잭션을 여는 것은 기존 동작이며 이번에 바꾸지 않았다.

## 바뀌지 않는 것
API 응답 필드·형식(값만 10), 판정·쿨다운·클립 구간, `camera-analysis` 2초 간격. 두 서버 `.env.dev`에는 `CAMERA_RECOMMENDED_FPS`가 없어(2026-10-07 확인) 기본값 변경만으로 반영된다.

## 검증
- 단위: `CameraServiceTest`(응답이 설정값을 따름), `CameraRecommendedFpsDefaultTest`(yaml·`@Value` 폴백 = 10).
- 배포 후: 두 서버에서 `GET /api/ward/camera`의 `recommendedFps`가 10인지 확인.
- ⚠️ **송출 중인 카메라는 페이지를 새로고침(재등록)해야 새 값을 받는다.**
