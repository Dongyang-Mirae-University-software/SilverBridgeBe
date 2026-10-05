# 카메라 방 선택(8개 고정·방마다 1대) + 피보호자 내 카메라 연결 상태 (2026-10-05)

> 브랜치 `feature/camera-label-unique-ward-status` · 마이그레이션 **V58** · 근거: 2026-10-05 사용자 결정(피보호자 "내 카메라" 프로토타입 수정안)

## 1. 정책

- **방은 정해진 8개 중에서만 고른다**: 거실·침실·주방·화장실·현관·베란다·작은방·작은방2(`CameraRoom`, 화면 순서).
  목록 밖이면 등록·수정 모두 400 `CAMERA_ROOM_INVALID`. FE 버튼만 믿으면 API를 직접 부르는 요청이 임의 이름을 넣을 수 있어 서버가 막는다.
  - DB에는 enum 이름이 아니라 한글 방 이름을 그대로 저장한다(알림 문구 "…님 댁 **거실**에서", 이상감지 이력·관리자 검색이 이 문자열을 쓴다). 방 추가는 `CameraRoom`에 값만 더하면 된다(DB CHECK 없음).
- **한 피보호자의 같은 방에는 카메라 1대**: 다른 카메라가 쓰는 방이면 409 `CAMERA_LABEL_DUPLICATED`("같은 방에 이미 등록된 카메라가 있습니다."). 다른 피보호자와는 무관하다.
  - 같은 기기가 같은 방으로 다시 등록하거나, 수정에서 지금 방을 그대로 보내면 성공(자기 자신은 중복이 아니다 - 멱등).
  - 같은 기기라도 다른 카메라가 쓰는 방으로 옮기면 409.
  - 동시에 두 기기가 같은 방을 등록하면 DB 제약(`uq_camera_ward_label`, V58)이 막고, 서비스가 잡아 같은 409로 바꾼다(전역 핸들러의 일반 `DUPLICATE_VALUE`로 새지 않게). 새 등록은 id가 IDENTITY라 `save()`에서, 방 변경은 `flush()`에서 위반이 나므로 둘 다 변환 범위다(기능 점검 H-1). 판정은 Hibernate 제약 이름 우선, DB 메시지 보조. 다른 제약 위반은 그대로 올린다.
  - 사용 중지(`isActive=false`) 카메라도 방을 차지한다(DB 제약과 같은 기준). 방을 비우려면 카메라를 삭제한다.
- **피보호자 "내 카메라"에 연결 상태**: 보호자 실시간 목록과 같은 기준이다. AI 목록을 1번만 부르고, AI 장애면 목록은 주고 상태만 `null`(확인 불가) - "모르는 값을 0/꺼짐으로 채우지 않는다".
  - 기존 `GET /api/ward/camera`에는 붙이지 않았다 - AI 호출 제한이 8초라 AI가 느리면 송출 기기가 `sessionId`를 받는 경로까지 늦어진다. 상태는 별도 API로 받는다.

## 2. API (FE 계약)

| Method / Path | 설명 |
|---|---|
| `GET /api/ward/camera/rooms` | 방 선택지 8개(화면 순서) `[{label, registered}]`. `registered=true`면 "· 등록됨"(선택 불가). 방 이름 변경 화면에서는 지금 카메라의 방도 `true`로 오니 현재 방은 선택된 상태로 표시 |
| `GET /api/ward/camera/live` | 내 카메라 + 연결 상태 `[{id, sessionId, deviceId, label, status, lastFrameAt, createdAt}]` (최신 등록순). 분 30회·시간 600회 초과 시 429 |
| `POST /api/ward/camera` | (기존) `label`이 8개 방 중 하나여야 함. 400 `CAMERA_ROOM_INVALID` / 409 `CAMERA_LABEL_DUPLICATED` 추가 |
| `PATCH /api/ward/camera/{id}` | (기존) `label`을 보내면 위와 같은 규칙. 400·409 추가 |
| `GET /api/ward/camera` | (기존, 변경 없음) 송출 시작용 `sessionId`는 여기서 받는다(AI 호출 없음) |

`status` 표시: `running` = **연결됨** / `disconnected`(10초 이상 프레임 없음)·`offline`(송출 안 함) = **연결 안 됨** / `null` = **확인 중**(AI 서버 장애, "연결 안 됨"과 구분).
`deviceId`가 그 기기의 localStorage 값과 같으면 "이 기기" 카메라다.

## 3. 변경 파일

- 신규: `domain/camera/entity/CameraRoom.java` · `dto/CameraRoomOption.java` · `dto/WardLiveCameraView.java` · `db/migration/V58__camera_unique_room_per_ward.sql`
- 변경: `CameraService`(방 검증·중복 검사·flush 변환·`getRoomOptions`) · `CameraStreamService`(`getWardLiveCameras`) · `CameraStreamRateLimit`(`WARD_LIVE`) · `WardCameraController`(엔드포인트 2개·Swagger 400/409) · `CameraRepository`(`findByWardIdAndLabel`) · `Camera`(`@Table` 제약 표기) · `CameraRegisterRequest`·`CameraUpdateRequest`(Swagger 설명) · `ErrorCode`(2개)
- 바뀌지 않은 것: 보호자 카메라 API·실시간 중계, 이상감지 구독·클립, AI 서버, 관리자 집계, 인가 규칙(`CAMERA_NOT_AUTHORIZED` 403 + `[IDOR-ATTEMPT]`)

## 4. V58 (비가역 아님 - 되돌리기: 제약 DROP)

- `UNIQUE (ward_id, label)` 추가. 기존 중복이 있으면 데이터를 바꾸지 않고 명확한 메시지로 중단한다(V55 방식).
- 배포 전 점검(2026-10-05, 읽기 전용): gosky 카메라 1대·중복 0·8개 방 밖 0 / vkcs 0대. 그대로 적용된다.
- 이미 있는 카메라의 방 이름이 8개 밖이면 그 카메라는 그대로 동작하지만, 방 이름을 바꿀 때는 8개 중에서 골라야 한다(현재 해당 0건).

## 5. 검증

- 단위(카메라 도메인 114개 통과, 신규 17): 목록 밖 방 400(등록·수정) / 8개 방 허용 / 새 등록 같은 방 409·저장 없음 / 같은 기기 같은 방 재등록 성공 / 같은 기기 다른 카메라 방 409 / 수정 중복 409·같은 방 그대로 성공 / 다른 피보호자 무관 / DB 제약 → 409 변환 / 다른 제약은 그대로 / 방 선택지 순서·등록 여부 / 내 카메라 상태 매핑(running·disconnected·offline)·AI 장애 null·카메라 없으면 AI 미호출·속도 제한 / 역할 인가(WARD만).
- 기존 테스트 수정: 등록·수정 입력에 쓰던 "안방"·"방1"·"방2"를 8개 방 이름으로 교체(규칙 변경에 따른 것, 검증 의도는 그대로).
- 통합(vkcs Testcontainers, `V58CameraRoomUniqueIntegrationTest`): 다른 피보호자·다른 방 허용 / 같은 방 두 번째 저장을 제약이 막고 오류에 제약 이름이 실림(서비스 판정 근거) / 기존 중복 시 중단 / 중복 없으면 제약 생성.
  - 결과: 통합 **60개 통과**(V58 4개 포함). push 없이 작업 트리를 vkcs 임시 폴더에 풀어 `tools/integration-test.sh`와 같은 docker 명령으로 실행했다(배포 폴더 무관, 끝난 뒤 삭제).
  - 첫 실행에서 V58 테스트 4개가 첫 저장부터 실패 - `camera.ward_id`는 `users` FK(V29 `fk_cameras_ward`)라 피보호자 회원을 먼저 저장하도록 고쳤다. `Camera` 엔티티 주석의 "FK 대신 String wardId로만 저장"은 실제와 다른 옛 설명이다(이번 범위에서 고치지 않음).
- 전체 단위 테스트 **1295개 통과**, `./gradlew build` 통과.

## 6. FE 반영 메모

- 등록 모달: `GET /api/ward/camera/rooms`로 버튼을 그리고 `registered=true`는 비활성 + "· 등록됨". 409를 받으면(다른 기기가 먼저 등록) 선택지를 다시 불러온다.
- 내 카메라: `GET /api/ward/camera/live`(15초 이상 간격 폴링 권장). `null` 상태는 "확인 중"으로.
- 이 화면들이 동작하려면 송출이 백엔드 등록(`POST /api/ward/camera` → 받은 `sessionId`로 송출)을 거쳐야 한다 - `(2026-09-30) issue-anomaly-camera-integration-gap.md`.
