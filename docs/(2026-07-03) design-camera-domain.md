# 설계안 — 백엔드 `camera` 도메인 (Option A)

> 작성 2026-07-03 · 대상 저장소 SilverBridgeBe · 상태 **제안(미구현)**
> 목적: 이상감지 카메라(세션)를 실제 사용자(피보호자↔보호자)에 귀속시켜, **보호자가 자신과 연결된 피보호자의 세션만** 보고, **피보호자는 버튼 하나로 카메라를 등록**하도록 한다.
> 갱신 이력: 초안(카메라 단일 식별자) → **본판**(배포 실측 + 요구 구체화: "피보호자 고유 SessionID" + "기기 하드웨어 CameraID" + 등록 UX 단순화 반영).

---

## 0. 배포 실측으로 확인된 현재 상태 (2026-07-03)

- ✅ AI 파이프라인은 실제로 동작 — 화재 신뢰도 84% 감지, `status running / FPS 1.82 / isAnalyzing true` 확인.
- 🔴 **보안 결함 실증(IDOR)**: 보호자 계정(`테스트/GRD001`)이 **연결 여부와 무관하게** 임의 피보호자 세션(`stream_001_mjng`)을 그대로 열람. FE(`useGuardianMonitor`)가 AI `GET /live-streams` 전체를 사용자 필터 없이 노출하기 때문. → 누구든 세션을 열면 아무 보호자나 볼 수 있는 구조.
- 🟠 **피보호자 등록 UX 과다**: "송출 설정 및 시작"에서 Session ID(`stream_001_mjng`)·Camera ID(`ipad-room-001_test`)를 **손으로 입력**, FPS도 슬라이더 수동. 추가로 "카메라 등록(고급)"에 피보호자 ID/보호자 ID/스트림 URL 등 자유 입력 필드가 노출 — **시니어 타깃에 부적합**하고, 손입력 ID는 스푸핑 가능.

이 문서는 위 두 문제를 백엔드 소유권 모델 + 등록 흐름 단순화로 해소한다.

---

## 1. 목표 · 책임 분리

- **백엔드(SilverBridgeBe)** = 세션 소유권의 **진실의 원천**. users·connections·JWT를 이미 소유하므로 인가를 여기서 판정.
- **AI 서버** = 지금처럼 "멍청한 내부 서비스"로 **무변경 유지**(단일 API Key, 세션 메모리·익명). 프로젝트_설명_AI서버.txt §1의 책임 분리와 일치.
- 백엔드는 **영상 프레임을 프록시하지 않는다.** ① 피보호자에게 **고유 SessionID를 발급**하고 ② 보호자에게 **볼 수 있는 SessionID allowlist**를 내려줄 뿐. 영상 경로(WS/MJPEG)는 기존대로 FE↔AI(Next 프록시).

### 두 개의 식별자 (요구사항 핵심)
스크린샷의 "Session ID / Camera ID" 두 필드를 각각 **다른 의미**로 정식화한다.

| 개념 | = 화면의 필드 | 무엇을 식별 | 발급 주체 | 예 |
|---|---|---|---|---|
| **SessionID** | Session ID | **누구(피보호자)의 세션인가** | **백엔드 발급, 피보호자 고유** | `ward_a9cC5f_k3m` |
| **DeviceID(CameraID)** | Camera ID | **어느 기기인가** | **기기 하드웨어 유래(FE 수집)** | 디바이스 지문 해시 |

- AI 스트림 세션 생성 시 → AI `sessionId` ← 우리 **SessionID**, AI `cameraIdentifier` ← 우리 **DeviceID**.
- **보호자는 SessionID만** 본다. 필터·구독 키가 SessionID이므로, "피보호자 고유 SessionID"가 곧 접근 단위.
- 손입력 완전 제거 — 두 값 모두 자동 산출.

### 소유권 모델
- **세션(카메라)의 소유자 = 피보호자(WARD).** 감시되는 장소·기기의 주인.
- **보호자(GUARDIAN)는 ACTIVE 연결을 통해 전이적으로 접근** — 별도 카메라-보호자 매핑 없이 **기존 `connections`(§3-6) 재사용**. 연결이 끊기면 접근도 자동 소멸.

```
users(WARD) 1 ──< cameras            (소유: ward_id, 각 행 = 고유 session_id)
users(WARD) 1 ──< connections(ACTIVE) >── 1 users(GUARDIAN)
  → 보호자가 볼 수 있는 SessionID = (보호자의 ACTIVE 연결 피보호자들)의 cameras.session_id
```

---

## 2. 피보호자 등록 UX (요구 반영 — FE 별도 구현, 계약만 정의)

> 백엔드 설계 범위 밖이지만, API 형태가 여기서 결정되므로 흐름을 못박는다.

### 변경점
- 메뉴 라벨 **"화면 송출" → "카메라 등록"**.
- STEP 3 "송출 설정 및 시작"(Session ID/Camera ID/FPS 수동) **삭제** → **"카메라 등록" 버튼 1개**.
- "카메라 등록(고급)" 폼(피보호자 ID/보호자 ID/스트림 URL 자유 입력) **삭제**.
- **FPS 고정** — 사용자 슬라이더 제거, 서버 권장 기본값(예 5fps) 하드코딩. (AI측 `STREAM_SAMPLE_EVERY_N_FRAMES=5`와 정합)

### 흐름
```
[피보호자] "카메라 등록" 탭
  1. 카메라/화면 선택 + 미리보기            (기존 유지)
  2. [ 카메라 등록 ] 버튼 클릭
       → FE가 기기 지문(DeviceID) 산출 (§3)
       → POST /api/ward/camera { deviceId, deviceLabel, label? }
       → 백엔드가 (해당 피보호자의) 고유 SessionID 발급·반환   ← "임의로 작성"
  3. 반환된 SessionID/DeviceID로 AI 송출 자동 시작
       (createStreamSession sessionId=SessionID, cameraIdentifier=DeviceID)
```
- 재등록(같은 기기) 시 **동일 SessionID 재사용**(멱등) — 보호자 화면 목록이 흔들리지 않음.
- 피보호자는 ID를 **한 글자도 입력하지 않음**.

### ⚠️ "기기 하드웨어 정보" 웹 현실 제약 (중요)
브라우저는 프라이버시상 **진짜 하드웨어 시리얼을 제공하지 않는다.** 웹(Next.js)에서 최선의 근사:
- `navigator.mediaDevices.enumerateDevices()` → 선택한 카메라의 `deviceId`(권한 허용 후 origin 내 안정) + `label`("FaceTime HD Camera" 등).
- `localStorage`에 최초 1회 생성·영속하는 **디바이스 UUID**(브라우저 프로필 단위 앵커).
- 권장 산식: `deviceId = SHA-256(localStorageUuid + cameraDeviceId)` → 안정적·프라이버시 존중 지문. **FE가 계산**, 백엔드는 불투명 문자열로 저장.
- 한계 정직 고지: 브라우저 프로필 단위이지 물리 기기 단위가 아님. 진짜 기기 단위가 필요하면 **네이티브 iPad 앱의 `identifierForVendor`**가 정답(웹 범위 밖). → §9 미결정.

---

## 3. 엔티티 · 마이그레이션

### 3-1. 엔티티 `Camera`
`domain/camera/entity/Camera.java` — `Connection`/`Inquiry` 컨벤션(FK 대신 `String`(6) userId, `BaseTimeEntity` 상속, IDENTITY, 보호 생성자 + Builder, setter 대신 의미 메서드).

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | Long (IDENTITY) | PK |
| `wardId` | String(6), NOT NULL | 소유 피보호자. FK users ON DELETE CASCADE |
| `sessionId` | String(64), UNIQUE, NOT NULL | **피보호자 고유 SessionID**. 백엔드 발급, AI sessionId·보호자 노출 키 |
| `deviceId` | String(128), NOT NULL | **CameraID** — 기기 하드웨어 유래 지문(FE 수집, 불투명). 재등록 dedup 키 |
| `deviceLabel` | String(50), NULL | 기기 표시명(예 "FaceTime HD Camera") |
| `label` | String(30), NULL | 사용자 표시명(예 "거실") |
| `registeredBy` | String(6), NULL | 등록 주체. FK users ON DELETE SET NULL |
| `isActive` | boolean, NOT NULL | 사용/중지 |
| `createdAt`/`updatedAt` | — | `BaseTimeEntity` |

- 메서드: `rename(label)`, `activate()`/`deactivate()`.
- **SessionID 발급**: `UserIdGenerator`와 동일한 SecureRandom + 중복 재시도 방식의 신규 `SessionIdGenerator`(global/util). 형식 `ward_{wardId}_{4~5랜덤}` — 가독성만 부여, 인가는 **문자열 파싱이 아니라 DB 행**으로 판정.
- **멱등 키**: `(ward_id, device_id)` 유니크 → 같은 피보호자·같은 기기 재등록 시 기존 행(=기존 SessionID) 반환.

### 3-2. 마이그레이션 `V29__add_cameras.sql`
V28 스타일(IDENTITY, TIMESTAMPTZ, updated_at는 DB 트리거 없이 `BaseTimeEntity`가 갱신).

```sql
-- 이상감지 카메라 소유권. 소유자 = 피보호자(ward_id). 보호자는 connections(ACTIVE)로 전이 접근.
-- session_id = 피보호자 고유 세션 식별자(백엔드 발급, AI sessionId). device_id = 기기 하드웨어 유래 지문(FE 수집).
-- 회원 탈퇴(hard delete) 시 본인 카메라도 제거되도록 ward_id ON DELETE CASCADE (connections/inquiries 동일 정책).
-- registered_by 는 등록 주체 — 등록자 계정 삭제 시에도 카메라는 소유자 기준 유지되도록 SET NULL.
CREATE TABLE cameras (
    id            BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ward_id       VARCHAR(6)   NOT NULL,
    session_id    VARCHAR(64)  NOT NULL,
    device_id     VARCHAR(128) NOT NULL,
    device_label  VARCHAR(50)  NULL,
    label         VARCHAR(30)  NULL,
    registered_by VARCHAR(6)   NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cameras_session       UNIQUE (session_id),
    CONSTRAINT uq_cameras_ward_device   UNIQUE (ward_id, device_id),
    CONSTRAINT fk_cameras_ward          FOREIGN KEY (ward_id)       REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cameras_registered_by FOREIGN KEY (registered_by) REFERENCES users (id) ON DELETE SET NULL
);

-- 피보호자 본인 목록 / 보호자 allowlist(ward_id IN ...) 양쪽에 사용
CREATE INDEX idx_cameras_ward ON cameras (ward_id);
```

> 참고: AI 서버에도 `cameras` 테이블이 있으나 **별도 PostgreSQL(silverbridge_ai)** 이라 충돌 없음. 목적이 다르다(AI측 메타·자유문자열 vs 우리측 소유권 원천).

---

## 4. Repository

`domain/camera/repository/CameraRepository.java`

```java
public interface CameraRepository extends JpaRepository<Camera, Long> {
    // 피보호자 본인 카메라 목록 (최신순)
    List<Camera> findByWardIdOrderByCreatedAtDesc(String wardId);

    // 재등록 멱등 — 같은 피보호자·같은 기기의 기존 카메라
    Optional<Camera> findByWardIdAndDeviceId(String wardId, String deviceId);

    // 보호자 allowlist — 연결된 피보호자들의 활성 카메라 일괄 조회
    List<Camera> findByWardIdInAndIsActiveTrue(Collection<String> wardIds);

    // SessionID 단건 (개별 인가·스트림 토큰 확장용)
    Optional<Camera> findBySessionId(String sessionId);

    boolean existsBySessionId(String sessionId); // 발급기 중복 검사
}
```

---

## 5. API 명세

### 5-1. 피보호자 (`WardCameraController`, `@PreAuthorize("hasRole('WARD')")`, `@Tag("피보호자 - 카메라")`)
`wardId`는 항상 `@AuthenticationPrincipal String wardId` — **body의 사용자 ID는 신뢰하지 않는다.**

| Method · Path | 설명 | Body | 응답 |
|---|---|---|---|
| `POST /api/ward/camera` | **카메라 등록**. SessionID 서버 발급, (ward,device) 멱등 | `{ deviceId, deviceLabel?, label? }` | `CameraResponse` (201/200) |
| `GET /api/ward/camera` | 내 카메라 목록 | — | `List<CameraResponse>` |
| `PATCH /api/ward/camera/{id}` | 이름 변경 / 활성 토글 | `{ label?, isActive? }` | `CameraResponse` |
| `DELETE /api/ward/camera/{id}` | 카메라 삭제 | — | `ApiResponse<Void>` |

- `{id}` 접근 시 **소유권 검증**: `camera.wardId == wardId` 아니면 `CAMERA_NOT_FOUND(404)` (IDOR을 404로 은닉 — inquiry 상세 동일 정책).
- `POST`는 **멱등**: `(wardId, deviceId)` 기존 행 있으면 그 행(기존 SessionID) 반환(200), 없으면 신규 발급(201).

### 5-2. 보호자 (`GuardianCameraController`, `@PreAuthorize("hasRole('GUARDIAN')")`, `@Tag("보호자 - 카메라")`)

| Method · Path | 설명 | 응답 |
|---|---|---|
| `GET /api/guardian/cameras` | **allowlist** — 내 ACTIVE 연결 피보호자들의 활성 카메라 SessionID | `List<GuardianCameraView>` |

`GuardianCameraView`: `{ sessionId, wardId, wardName, label, isActive }` — FE가 이 SessionID 집합으로 AI `live-streams`를 필터/구독. **다른 피보호자 세션은 애초에 목록에 없음** → 스크린샷의 IDOR 해소.

### 5-3. DTO
- `CameraRegisterRequest { @NotBlank @Size(max=128) String deviceId; @Size(max=50) String deviceLabel; @Size(max=30) String label; }`
- `CameraUpdateRequest { @Size(max=30) String label; Boolean isActive; }` (부분 수정 — null 필드 미변경)
- `CameraResponse { Long id; String sessionId; String deviceId; String label; boolean isActive; OffsetDateTime createdAt; }`
- `GuardianCameraView { String sessionId; String wardId; String wardName; String label; boolean isActive; }`

---

## 6. Service · 인가 로직

`domain/camera/service/CameraService.java` (`@Transactional`, `CameraRepository` + `ConnectionRepository` + `UserRepository` + `SessionIdGenerator`)

- **등록(멱등)**:
  ```
  findByWardIdAndDeviceId(wardId, deviceId)
    .map(existing -> existing)                                   // 200
    .orElseGet(() -> save(Camera.builder()
        .wardId(wardId).registeredBy(wardId)
        .deviceId(deviceId).deviceLabel(deviceLabel).label(label)
        .sessionId(sessionIdGenerator.generate())               // 피보호자 고유 발급
        .isActive(true).build()));                              // 201
  ```
- **본인 목록**: `findByWardIdOrderByCreatedAtDesc(wardId)`.
- **수정/삭제**: `findById` → 소유권 검증(`wardId` 불일치 시 `CAMERA_NOT_FOUND`) → 의미 메서드/`delete`.
- **보호자 allowlist**:
  ```
  wardIds = connectionRepository
      .findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId, [ACTIVE])
      .map(Connection::getWardId)
  cameras   = cameraRepository.findByWardIdInAndIsActiveTrue(wardIds)
  wardNames = userRepository.findAllById(wardIds)   // 배치 조회 — ConnectionService.getMyWards 동일 패턴
  → GuardianCameraView 조립
  ```
- **개별 인가 헬퍼**(스트림 토큰 등 확장 대비):
  ```java
  void assertGuardianCanView(String guardianId, String sessionId) {
      Camera cam = cameraRepository.findBySessionId(sessionId)
          .orElseThrow(() -> new CustomException(CAMERA_NOT_FOUND));
      boolean ok = connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
          guardianId, cam.getWardId(), List.of(ConnectionStatus.ACTIVE));
      if (!ok) throw new CustomException(CAMERA_ACCESS_DENIED); // 403
  }
  ```

### ErrorCode 추가 (`global/exception/ErrorCode.java`)
```java
// 카메라
CAMERA_NOT_FOUND(HttpStatus.NOT_FOUND, "카메라를 찾을 수 없습니다."),
CAMERA_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 카메라에 대한 권한이 없습니다."),
```

---

## 7. AI 서버 · FE 연동 계약

**연동 스타일 = SessionID allowlist + FE 구독 (AI 무변경).**

1. **발급**: 피보호자가 "카메라 등록" → 백엔드가 `sessionId`(+저장된 `deviceId`) 반환.
2. **송출**: 피보호자 기기가 AI `createStreamSession` 호출 시 `sessionId ← 우리 SessionID`, `cameraIdentifier ← 우리 DeviceID`.
   - 계약: **AI sessionId ≡ 우리 session_id**. AI `live-streams` 목록·WS `subscribe`가 sessionId 키이므로 이렇게 맞춰야 필터 성립.
3. **수신**: 보호자 FE가 `/api/guardian/cameras`로 allowlist를 받아, AI `live-streams` 응답·WS `live_streams` 브로드캐스트를 **allowlist ∩** 로 필터해 표시/구독.

> 이 단계에서 AI 서버·Next 프록시는 손대지 않는다.
> ⚠️ **FE 필터는 UX 경계이지 하드 보안 경계가 아니다.** AI WS는 여전히 전체 세션을 브로드캐스트하고 `apiKey`가 클라이언트에 노출되어 있어(배포 실측), 결심한 공격자는 우회 가능. 하드 경계는 **Phase C**(백엔드 발급 스코프 스트림 토큰 + 엣지에서 sessionId 검증 프록시)에서 닫는다 — 본 설계는 그 seam(`assertGuardianCanView`)만 심어둔다.

---

## 8. 생명주기 · 탈퇴

- **피보호자 탈퇴(hard delete)**: `ward_id ON DELETE CASCADE`로 카메라 행 자동 삭제 → **별도 리스너 불필요**. AI측 라이브 세션은 메모리·무프레임 10초 후 자동 disconnected이므로 통지 불요.
- **연결 해제**: 카메라 행은 유지되나 보호자 allowlist에서 자동 제외(ACTIVE 아님) → 접근 자동 소멸.
- **카메라 CRUD는 WebSocket/FCM 알림 대상 아님**(사용자 대상 실시간 통지가 아님) — NotificationDispatcher 미경유.

---

## 9. 변경 파일 목록

**신규 (backend)**
```
domain/camera/entity/Camera.java
domain/camera/repository/CameraRepository.java
domain/camera/service/CameraService.java
domain/camera/controller/WardCameraController.java
domain/camera/controller/GuardianCameraController.java
domain/camera/dto/{CameraRegisterRequest,CameraUpdateRequest,CameraResponse,GuardianCameraView}.java
global/util/SessionIdGenerator.java
src/main/resources/db/migration/V29__add_cameras.sql
src/test/.../CameraServiceTest.java   (소유권·멱등·allowlist 인가 검증 — 핵심 로직 필수)
```
**수정 (backend)**: `global/exception/ErrorCode.java` (에러코드 2종)
- SecurityConfig 변경 **불요** — `/api/ward/**`·`/api/guardian/**`는 `anyRequest().authenticated()` + 메서드 레벨 `@PreAuthorize`로 커버.

**FE (별도 제안 — 이번 백엔드 범위 밖)**
- 메뉴 "화면 송출" → "카메라 등록" 라벨 변경.
- `WardStreamContent`: STEP3 수동 입력·FPS 슬라이더·고급 폼 제거 → "카메라 등록" 버튼 1개 + 기기 지문 산출(§2) + `/api/ward/camera` 연동, FPS 고정.
- `useGuardianMonitor`: `/api/guardian/cameras` allowlist로 세션 필터/구독(전체 노출 제거).

---

## 10. 미결정 사항 (검토 요청)

1. **DeviceID 정확도**: 웹 지문(브라우저 프로필 단위)로 충분한가, 아니면 네이티브 iPad 앱(`identifierForVendor`, 진짜 기기 단위)을 전제로 갈 것인가? (§2 제약)
2. **멀티 기기**: 한 피보호자가 여러 방에 카메라를 둘 수 있게 할지(현재 설계는 기기별 1행·SessionID 다수 지원). "고유 SessionID"를 피보호자당 **정확히 1개**로 강제할지 여부.
3. **등록 주체**: 피보호자 본인만 vs 보호자 대행 등록 허용(후자면 `POST /api/guardian/cameras` + ACTIVE 연결 검증 추가).
4. **카메라 개수 상한**: 피보호자당 N대 제한(어뷰징 방지).
5. **FPS 고정값**: 서버 권장 기본값을 얼마로(예 5fps)? 백엔드 config로 내려줄지 FE 상수로 둘지.
6. **Phase C 착수 시점**: 스코프 스트림 토큰(하드 보안 경계)을 이번에 함께 갈지, seam만 심고 다음 단계로 미룰지.

---

## 11. 다음 단계
승인 시: ① `feature/camera-domain` 브랜치 분기 → ② 엔티티·마이그레이션·리포지토리 → ③ 서비스·인가 + 테스트 → ④ 컨트롤러·DTO·Swagger → ⑤ `./gradlew build -x test` 검증 → PR(`dev`). (코드 변경이므로 §3 PR 게이트 적용, 문서 예외 아님.) FE는 별도 저장소(../SilverBridgeFe) PR로 분리.
