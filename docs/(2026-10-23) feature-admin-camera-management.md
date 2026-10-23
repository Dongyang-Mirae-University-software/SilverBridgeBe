# 관리자 디바이스(카메라) 관리 조회 API (2026-10-23)

## 배경
- 관리자 웹 "디바이스 관리" 화면(Claude Design `SilverBridge-app.html`의 `AdminDevices`)이 쓸 API가 없었다. 대시보드에는 대수만 있고 카메라 한 대씩 볼 방법이 없었다.
- 이번 작업은 BE만 한다. 관리자 웹은 `(2026-10-23) api-contract-admin-camera.md`만 보고 붙인다.

## API (ADMIN 전용, 조회 전용, 마이그레이션 없음)
| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/api/admin/camera?status=ALL\|LIVE\|OFFLINE&q=&page=&size=` | 카메라 목록(등록 최신순, `PageResponse`) |
| GET | `/api/admin/camera/summary` | 상단 카드 숫자 |

- 항목: `cameraId, sessionId, room, wardId, wardName, wardAddress(address만), isActive, registeredAt(KST), liveStatus`
- 요약: `aiConnected, registered, maxTotal, live, offline, totalWards, wardsWithoutCamera`

## 상태 판정
- 기준은 대시보드와 같은 **AI 구독 집합**(`AiLiveStreamSubscriber`)이다. 구독 = AI가 라이브로 보고했고 백엔드가 감시(subscribe) 중.
  - 구독 중 → `LIVE`, 그 밖의 등록 카메라 → `OFFLINE`
  - AI 연결이 끊겼거나 **재접속 뒤 첫 세션 목록을 아직 받지 못했으면** → `null`(확인 불가). 재접속 직후에는 구독 기록이 비어 있어 목록이 오기 전 몇 초간 모든 카메라가 끊김으로 보이던 틈을 막으려고 `liveSessionSnapshot()`(연결 + 첫 목록 처리 뒤에만 값)을 새로 뒀다. 대시보드 숫자는 이번에 바꾸지 않았다(같은 틈이 대시보드에는 남아 있다).
- 상태를 모를 때 `status=LIVE`·`OFFLINE` 필터는 빈 목록이 아니라 **503 `CAMERA_LIVE_STATUS_UNKNOWN`**이다(사용자 결정 2026-10-23). 빈 목록은 "끊긴 카메라 0대"로 정반대로 읽힌다. `status=ALL`은 항목마다 `liveStatus=null`로 정상 응답한다.
- **꺼 둔 카메라(`isActive=false`)도 개수에 넣는다**(사용자 결정 2026-10-23). 꺼도 감지·알림은 계속되므로 실제 송출 여부를 보여 준다. 그래서 `live + offline = registered`이고, 꺼 둔 카메라를 빼는 대시보드 "끊김"(ADMIN-G20)과 숫자가 다를 수 있다.

## 모집단·검색·페이지
- 탈퇴 처리 중(INACTIVE)인 피보호자의 카메라는 뺀다(회원 관리 목록과 같은 모집단, 곧 CASCADE로 지워진다). 등록 상한 검사(`CameraService`)는 전체 행을 세므로 이 짧은 기간(최대 약 20분)에는 `registered`보다 1대 많게 셀 수 있다(수용).
- 검색 `q`: 공백을 모두 지우고 소문자로 바꿔 주소·방·이름·세션 ID에 부분 일치.
- 상태가 메모리 값이라 **목록을 메모리에서 거르고 자른다**(DB 페이지를 먼저 자르면 필터 뒤 개수가 어긋난다). 서비스 전체 등록 상한 `camera.max-total`이 3대라 비용이 없다 - 상한을 수십 대 이상으로 올리면 이 방식부터 다시 본다(서비스 주석에 근거).
- 페이지는 `AdminPaging`(page 0~, size 기본 20·최대 50). 시안은 10행이라 FE가 `size=10`을 보낸다.

## 만들지 않은 것
- **[점검 요청] 버튼**: 받는 사람·절차가 없다. 연결 끊김은 이미 보호자에게 `CAMERA_LINK_LOST`로 자동 안내된다. 보낼 곳 없는 버튼은 거짓 UI.
- **"24시간 넘게 꺼짐"**(시안 주석): 끊긴 시각을 저장하지 않는다. 지금 상태만.
- **영상·정지 화면·클립**: 관리자 열람 금지 그대로. 응답에 스트림·프레임·클립 주소를 싣지 않는다.
- **`deviceId`**(송출 기기 토큰): 재등록 키라 싣지 않는다. 상세 주소도 싣지 않는다.
- **관리자 카메라 삭제**: 넣지 않았다(사용자 결정). "한 계정의 슬롯 독점은 수용"(2026-10-14) 그대로.
- 시안의 "설치 위치"는 호실(101호)이지만 백엔드는 방 이름(`CameraRoom` 8종)이다 - 값만 다르고 칸은 같다.

## 인가·로그
- `SecurityConfig` `/api/admin/**` 규칙 + 클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")`. 조회라 감사 로그 없음.
- 로그 `[ADMIN-CAMERA]`: adminId·결과 건수·소요 시간만(주소·이름·검색어 금지).

## 바뀐 파일
- `domain/admin/controller/AdminCameraController`, `service/AdminCameraService`, `dto/AdminCamera{Item,LiveStatus,StatusFilter,SummaryResponse}`
- `domain/anomaly/client/AiLiveStreamSubscriber`: `sessionListSynced` 플래그 + `liveSessionSnapshot()` (구독·재접속 동작 변경 없음)
- `global/exception/ErrorCode`: `CAMERA_LIVE_STATUS_UNKNOWN`(503)

## 테스트
- `AdminCameraServiceTest`(11): LIVE/OFFLINE/모름 null, 모름 + 필터 503, 탈퇴 처리 중 제외, 필터 뒤 개수·페이지, 범위 밖 페이지, 검색 4종, KST, 요약 합계·null·상한 0 → null
- `AiLiveStreamSubscriberTest`(+4): 첫 목록 전 빈 값, 목록 뒤 집합(0대면 빈 집합), 끊김·재접속 뒤 빈 값, 소켓 닫힘
- `AdminCameraControllerSecurityTest`: ADMIN 허용, GUARDIAN·WARD 403, 쓰기 매핑 없음
- `AdminCameraControllerHttpTest`: 실제 직렬화(칸·KST 문자열·`deviceId` 없음), null 칸이 빠지지 않음, 503 코드, 잘못된 status 400
- `AdminCameraItemExposureTest`: 항목 칸 허용 목록 고정
- `LogRawExceptionGuardTest` 대상에 `AdminCameraService` 추가
