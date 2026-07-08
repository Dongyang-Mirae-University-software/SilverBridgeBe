# 설계안 — 백엔드 `camera` 도메인 (Option A)

> 작성 2026-07-03 · 갱신 2026-07-08 · 대상 저장소 SilverBridgeBe · 상태 **제안(미구현)**
> 목적: 이상감지 카메라(세션)를 실제 사용자(피보호자↔보호자)에 귀속시켜, **보호자가 자신과 연결된 피보호자의 세션만** 보고, **피보호자는 방별로 카메라를 여러 대 간단히 등록**하도록 한다.
> 갱신 이력: 초안(단일 식별자) → 본판(SessionID+하드웨어 DeviceID) → **현판**(멀티 기기 확정 · DeviceID = 하드웨어 지문 폐기, **백엔드 발급 토큰**으로 전환 · **방별(거실/안방/방1~3) 구분**).

---

## 0. 배포 실측으로 확인된 현재 상태 (2026-07-03)

- ✅ AI 파이프라인 실제 동작 — 화재 신뢰도 84% 감지, `status running / FPS 1.82 / isAnalyzing true`.
- 🔴 **보안 결함 실증(IDOR)**: 보호자 계정(`테스트/GRD001`)이 **연결 여부와 무관하게** 임의 피보호자 세션(`stream_001_mjng`)을 열람. FE(`useGuardianMonitor`)가 AI `GET /live-streams` 전체를 사용자 필터 없이 노출.
- 🟠 **피보호자 등록 UX 과다**: Session ID·Camera ID 손입력, FPS 슬라이더 수동, 고급 폼에 피보호자/보호자 ID·스트림 URL 자유 입력 — 시니어 부적합 + 손입력 ID 스푸핑 가능.

이 문서는 두 문제를 백엔드 소유권 모델 + 등록 흐름 단순화로 해소한다.

---

## 1. 목표 · 책임 분리

- **백엔드(SilverBridgeBe)** = 세션 소유권의 **진실의 원천**. users·connections·JWT를 이미 소유하므로 인가를 여기서 판정.
- **AI 서버** = 지금처럼 "멍청한 내부 서비스"로 **무변경 유지**(단일 API Key, 세션 메모리·익명). 프로젝트_설명_AI서버.txt §1 책임 분리와 일치.
- 백엔드는 **영상 프레임을 프록시하지 않는다.** ① 피보호자에게 **SessionID·DeviceID를 발급**하고 ② 보호자에게 **볼 수 있는 SessionID allowlist**를 내려줄 뿐. 영상 경로(WS/MJPEG)는 기존대로 FE↔AI(Next 프록시).

### 두 개의 식별자 (모두 백엔드 발급 — 손입력·하드웨어 접근 없음)
스크린샷의 "Session ID / Camera ID" 두 필드를 각각 다른 의미로 정식화하되, **둘 다 백엔드가 발급**한다.

| 개념 | = 화면 필드 | 무엇을 식별 | 발급/저장 | 예 |
|---|---|---|---|---|
| **SessionID** | Session ID | **어느 카메라(방)의 세션인가** | 백엔드 발급, 카메라 행마다 고유 | `ward_a9cC5f_k3m` |
| **DeviceID** | Camera ID | **어느 기기인가**(재등록 dedup 키) | **백엔드 발급 토큰, FE가 `localStorage` 영속** | `dev_7Qs4Xu` |

- ⚠️ **DeviceID는 하드웨어 지문이 아니다.** 브라우저 지문(userAgent·화면·mediaDevices)은 시크릿모드·캐시삭제·브라우저 교체에 취약하고 프라이버시 이슈가 있어 폐기. 대신 **최초 등록 시 백엔드가 랜덤 토큰을 발급 → FE가 `localStorage`에 저장 → 재등록 때 되돌려 보냄**. 어떤 기기·OS·브라우저(아이패드/PC/폰)에서도 동일하게 동작(핸드폰 전용 아님).
- DeviceID는 **credential이 아니다.** 항상 JWT의 `wardId`로 스코프되는 **본인 카메라 dedup 키**일 뿐 — 최악의 오작동은 "같은 피보호자에게 중복 카메라 행 하나 더" 뿐이고 교차 사용자 위험 없음.
- AI 스트림 세션 생성 시 → AI `sessionId` ← 우리 **SessionID**, AI `cameraIdentifier` ← 우리 **DeviceID**.
- **보호자는 SessionID만** 본다(필터·구독 키). 손입력은 전면 제거.

### 멀티 기기 · 방 구분 (요구 확정)
- **피보호자당 카메라 여러 대** — 거실/안방/방1/방2/방3처럼 **방별로 각 1행**. 행마다 SessionID·DeviceID 고유.
- 구분 라벨 = `label`(설치 위치, 방 이름). FE가 프리셋 칩(거실·안방·방1·방2·방3) + 직접 입력 제공. DB는 자유 문자열(집집마다 방 구성이 달라 enum 부적합).
- 보호자 이상감지 목록에는 **연결된 피보호자들의 방별 카메라가 나열**되고, 카드에 `{피보호자명 · 방이름}` 표시.

### 소유권 모델
- **세션(카메라)의 소유자 = 피보호자(WARD).**
- **보호자는 ACTIVE 연결로 전이 접근** — 별도 매핑 없이 **기존 `connections`(§3-6) 재사용**. 연결 끊기면 접근 자동 소멸.

```
users(WARD) 1 ──< cameras (N)          방별 N행, 각 행 = 고유 session_id/device_id
users(WARD) 1 ──< connections(ACTIVE) >── 1 users(GUARDIAN)
  → 보호자가 볼 수 있는 SessionID = (ACTIVE 연결 피보호자들)의 cameras.session_id 전체
```

---

## 2. 피보호자 등록 UX (요구 반영 — FE 별도 구현, 계약만 정의)

### 변경점
- 메뉴 라벨 **"화면 송출" → "카메라 등록"**.
- STEP3 "송출 설정 및 시작"(Session/Camera ID·FPS 수동) **삭제**, "카메라 등록(고급)" 폼 **삭제**.
- **FPS 고정**(서버 권장 기본, 예 5fps — AI `STREAM_SAMPLE_EVERY_N_FRAMES=5`와 정합). 슬라이더 제거.
- 대신 **방 선택 + "카메라 등록" 버튼**만 노출.

### 흐름
```
[피보호자] "카메라 등록" 탭 — 이 기기를 특정 방의 카메라로 등록
  1. 카메라/화면 선택 + 미리보기                     (기존 유지)
  2. 방 선택: [거실][안방][방1][방2][방3][+직접입력]   (프리셋 칩)
  3. [ 카메라 등록 ] 클릭
       FE가 localStorage에서 deviceId 조회
       → POST /api/ward/camera { label:"거실", deviceId?:<있으면 전송> }
       → 백엔드: (신규면) SessionID+DeviceID 발급 / (기존 기기면) 기존 행 반환
       → FE가 응답의 deviceId를 localStorage에 저장(덮어씀)
  4. 반환된 SessionID/DeviceID로 AI 송출 자동 시작
       createStreamSession(sessionId=SessionID, cameraIdentifier=DeviceID)
```
- **같은 기기 재등록 = 멱등**: localStorage의 deviceId를 보내면 기존 SessionID 재사용 → 보호자 목록이 흔들리지 않음. 방 이름만 바꾸면 label 갱신.
- **다른 방에 새 기기 배치**: 그 기기의 브라우저엔 아직 deviceId가 없으니(또는 새 브라우저) 신규 발급 → 방별로 자연히 다른 카메라 행 생성.
- 피보호자는 **방 이름만 고르고 버튼 한 번** — ID 입력 없음.

> 참고(관리): 한 기기(브라우저)에서 방을 바꿔 여러 번 등록하면 같은 deviceId로 label만 계속 바뀐다. "거실 기기 = 거실 카메라"처럼 **기기 1대가 한 방을 담당**하는 사용을 전제로 안내(방마다 별도 기기/브라우저). 다중 방을 한 기기로 번갈아 쓰는 예외는 §10 미결정.

---

## 3. 엔티티 · 마이그레이션

### 3-1. 엔티티 `Camera`
`domain/camera/entity/Camera.java` — `Connection`/`Inquiry` 컨벤션(FK 대신 `String`(6) userId, `BaseTimeEntity` 상속, IDENTITY, 보호 생성자 + Builder, setter 대신 의미 메서드).

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | Long (IDENTITY) | PK |
| `wardId` | String(6), NOT NULL | 소유 피보호자. FK users ON DELETE CASCADE |
| `sessionId` | String(64), UNIQUE, NOT NULL | 카메라 고유 SessionID. 백엔드 발급, AI sessionId·보호자 노출 키 |
| `deviceId` | String(64), NOT NULL | **백엔드 발급 기기 토큰**(FE가 localStorage 영속). 재등록 dedup 키 |
| `label` | String(30), NOT NULL | **설치 위치(방 이름)** — 거실/안방/방1/방2/방3 등 |
| `registeredBy` | String(6), NULL | 등록 주체. FK users ON DELETE SET NULL |
| `isActive` | boolean, NOT NULL | 사용/중지 |
| `createdAt`/`updatedAt` | — | `BaseTimeEntity` |

- 메서드: `rename(label)`, `activate()`/`deactivate()`.
- **식별자 발급**: `UserIdGenerator`와 동일한 SecureRandom + 중복 재시도 방식의 신규 `CameraIdentifierFactory`(global/util)가 `sessionId`(`ward_{wardId}_{rand}`)와 `deviceId`(`dev_{rand}`)를 각각 유니크 보장 생성. 가독성만 부여, 인가는 **DB 행**으로 판정.
- **멱등 키**: `(ward_id, device_id)` 유니크 → 같은 피보호자·같은 기기 재등록 시 기존 행(=기존 SessionID) 반환.

### 3-2. 마이그레이션 `V29__add_cameras.sql`
V28 스타일(IDENTITY, TIMESTAMPTZ, updated_at는 DB 트리거 없이 `BaseTimeEntity`가 갱신).

```sql
-- 이상감지 카메라 소유권. 소유자 = 피보호자(ward_id). 보호자는 connections(ACTIVE)로 전이 접근.
-- 피보호자당 여러 대(방별). session_id = 카메라 고유 세션(AI sessionId). device_id = 백엔드 발급 기기 토큰(FE localStorage).
-- label = 설치 위치(방 이름). 회원 탈퇴(hard delete) 시 ward_id ON DELETE CASCADE (connections/inquiries 동일 정책).
-- registered_by 는 등록 주체 — 등록자 계정 삭제 시에도 카메라는 소유자 기준 유지되도록 SET NULL.
CREATE TABLE cameras (
    id            BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ward_id       VARCHAR(6)  NOT NULL,
    session_id    VARCHAR(64) NOT NULL,
    device_id     VARCHAR(64) NOT NULL,
    label         VARCHAR(30) NOT NULL,
    registered_by VARCHAR(6)  NULL,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cameras_session     UNIQUE (session_id),
    CONSTRAINT uq_cameras_ward_device UNIQUE (ward_id, device_id),
    CONSTRAINT fk_cameras_ward          FOREIGN KEY (ward_id)       REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cameras_registered_by FOREIGN KEY (registered_by) REFERENCES users (id) ON DELETE SET NULL
);

-- 피보호자 본인 목록 / 보호자 allowlist(ward_id IN ...) 양쪽에 사용
CREATE INDEX idx_cameras_ward ON cameras (ward_id);
```

> 참고: AI 서버에도 `cameras` 테이블이 있으나 **별도 PostgreSQL(silverbridge_ai)** 이라 충돌 없음.

---

## 4. Repository

`domain/camera/repository/CameraRepository.java`

```java
public interface CameraRepository extends JpaRepository<Camera, Long> {
    // 피보호자 본인 카메라 목록 (방별, 최신순)
    List<Camera> findByWardIdOrderByCreatedAtDesc(String wardId);

    // 재등록 멱등 — 같은 피보호자·같은 기기의 기존 카메라
    Optional<Camera> findByWardIdAndDeviceId(String wardId, String deviceId);

    // 보호자 allowlist — 연결된 피보호자들의 활성 카메라 일괄 조회
    List<Camera> findByWardIdInAndIsActiveTrue(Collection<String> wardIds);

    // SessionID 단건 (개별 인가·스트림 토큰 확장용)
    Optional<Camera> findBySessionId(String sessionId);

    boolean existsBySessionId(String sessionId);   // 발급기 중복 검사
    boolean existsByDeviceId(String deviceId);      // 발급기 중복 검사
}
```

---

## 5. API 명세

### 5-1. 피보호자 (`WardCameraController`, `@PreAuthorize("hasRole('WARD')")`, `@Tag("피보호자 - 카메라")`)
`wardId`는 항상 `@AuthenticationPrincipal String wardId` — **body의 사용자 ID는 신뢰하지 않는다.**

| Method · Path | 설명 | Body | 응답 |
|---|---|---|---|
| `POST /api/ward/camera` | **카메라 등록/재등록**. 방 이름 필수, deviceId는 있으면 전송 | `{ label, deviceId? }` | `CameraResponse` (201 신규 / 200 기존) |
| `GET /api/ward/camera` | 내 카메라(방별) 목록 | — | `List<CameraResponse>` |
| `PATCH /api/ward/camera/{id}` | 방 이름 변경 / 활성 토글 | `{ label?, isActive? }` | `CameraResponse` |
| `DELETE /api/ward/camera/{id}` | 카메라 삭제 | — | `ApiResponse<Void>` |

- `POST` 동작: `findByWardIdAndDeviceId(wardId, deviceId)` 존재 → 기존 반환(라벨 갱신, 200) / 없거나 deviceId 미전송 → **신규 SessionID+DeviceID 발급**(201). *(FE가 보낸 deviceId가 본인 소유가 아니면 무시하고 신규 발급 — sessionId/deviceId는 항상 새로 유니크 생성되므로 충돌·탈취 불가)*
- `{id}` 접근 시 **소유권 검증**: `camera.wardId == wardId` 아니면 `CAMERA_NOT_FOUND(404)`(IDOR 404 은닉 — inquiry 상세 동일).

### 5-2. 보호자 (`GuardianCameraController`, `@PreAuthorize("hasRole('GUARDIAN')")`, `@Tag("보호자 - 카메라")`)

| Method · Path | 설명 | 응답 |
|---|---|---|
| `GET /api/guardian/cameras` | **allowlist** — 내 ACTIVE 연결 피보호자들의 활성 카메라(방별) | `List<GuardianCameraView>` |

`GuardianCameraView`: `{ sessionId, wardId, wardName, label, isActive }` — FE가 이 SessionID 집합으로 AI `live-streams`를 필터/구독. 카드 표기 `{wardName · label}`(예 "남궁명진 · 거실"). **다른 피보호자 세션은 목록에 없음** → IDOR 해소.

### 5-3. DTO
- `CameraRegisterRequest { @NotBlank @Size(max=30) String label; @Size(max=64) String deviceId; }`
- `CameraUpdateRequest { @Size(max=30) String label; Boolean isActive; }` (부분 수정 — null 미변경)
- `CameraResponse { Long id; String sessionId; String deviceId; String label; boolean isActive; OffsetDateTime createdAt; }`
- `GuardianCameraView { String sessionId; String wardId; String wardName; String label; boolean isActive; }`

---

## 6. Service · 인가 로직

`domain/camera/service/CameraService.java` (`@Transactional`, `CameraRepository` + `ConnectionRepository` + `UserRepository` + `CameraIdentifierFactory`)

- **등록/재등록(멱등)**:
  ```
  if (deviceId != null && (existing = findByWardIdAndDeviceId(wardId, deviceId)).isPresent()) {
      existing.rename(label);          // 방 이름 갱신
      return CameraResponse(existing); // 200
  }
  Camera c = save(Camera.builder()
      .wardId(wardId).registeredBy(wardId).label(label)
      .sessionId(factory.newSessionId(wardId))   // ward_{wardId}_{rand}, 유니크
      .deviceId(factory.newDeviceId())           // dev_{rand}, 유니크
      .isActive(true).build());
  return CameraResponse(c);            // 201 (deviceId 포함 반환 → FE가 localStorage 저장)
  ```
- **본인 목록**: `findByWardIdOrderByCreatedAtDesc(wardId)`.
- **수정/삭제**: `findById` → 소유권 검증(`wardId` 불일치 시 `CAMERA_NOT_FOUND`) → 의미 메서드/`delete`.
- **보호자 allowlist**:
  ```
  wardIds   = connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId, [ACTIVE]).map(getWardId)
  cameras   = cameraRepository.findByWardIdInAndIsActiveTrue(wardIds)
  wardNames = userRepository.findAllById(wardIds)   // 배치 조회 — ConnectionService.getMyWards 동일 패턴
  → GuardianCameraView 조립 (방별로 여러 건 가능)
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

1. **발급**: 피보호자가 "카메라 등록" → 백엔드가 `sessionId`+`deviceId` 반환(FE는 deviceId를 localStorage 저장).
2. **송출**: 기기가 AI `createStreamSession` 호출 시 `sessionId ← 우리 SessionID`, `cameraIdentifier ← 우리 DeviceID`.
   - 계약: **AI sessionId ≡ 우리 session_id**. AI `live-streams`·WS `subscribe`가 sessionId 키이므로 정합.
3. **수신**: 보호자 FE가 `/api/guardian/cameras` allowlist를 받아, AI `live-streams`·WS `live_streams` 브로드캐스트를 **allowlist ∩** 로 필터해 방별 카드로 표시/구독.

> AI 서버·Next 프록시 무변경.
> ⚠️ **FE 필터는 UX 경계이지 하드 보안 경계가 아니다.** AI WS는 전체 세션을 브로드캐스트하고 `apiKey`가 클라이언트에 노출(배포 실측)되어 결심한 공격자는 우회 가능. 하드 경계는 **Phase C**(백엔드 발급 스코프 스트림 토큰 + 엣지 sessionId 검증 프록시)에서 닫는다 — 본 설계는 seam(`assertGuardianCanView`)만 심는다.

---

## 8. 생명주기 · 탈퇴

- **피보호자 탈퇴(hard delete)**: `ward_id ON DELETE CASCADE`로 카메라 행 자동 삭제 → 별도 리스너 불필요. AI측 라이브 세션은 메모리·무프레임 10초 후 자동 disconnected.
- **연결 해제**: 카메라 행 유지되나 보호자 allowlist에서 자동 제외(ACTIVE 아님) → 접근 자동 소멸.
- **카메라 CRUD는 WebSocket/FCM 알림 대상 아님** — NotificationDispatcher 미경유.

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
global/util/CameraIdentifierFactory.java     (sessionId·deviceId 유니크 발급)
src/main/resources/db/migration/V29__add_cameras.sql
src/test/.../CameraServiceTest.java          (소유권·멱등·allowlist·방별 다건 검증 — 핵심 로직 필수)
```
**수정 (backend)**: `global/exception/ErrorCode.java` (에러코드 2종)
- SecurityConfig 변경 **불요** — `/api/ward/**`·`/api/guardian/**`는 `anyRequest().authenticated()` + 메서드 레벨 `@PreAuthorize`로 커버.

**FE (별도 제안 — 이번 백엔드 범위 밖)**
- 메뉴 "화면 송출" → "카메라 등록".
- `WardStreamContent`: 수동 입력·FPS 슬라이더·고급 폼 제거 → 방 선택 칩 + "카메라 등록" 버튼 + localStorage deviceId 관리 + `/api/ward/camera` 연동, FPS 고정.
- `useGuardianMonitor`: `/api/guardian/cameras` allowlist로 세션 필터/구독(전체 노출 제거), 방별 카드(`wardName · label`).

---

## 10. 미결정 사항 (검토 요청)

1. **한 기기 다중 방**: 기본 전제는 "기기 1대 = 방 1개". 한 기기(브라우저)로 여러 방을 번갈아 등록해야 하는 경우가 있나? 있으면 deviceId를 방마다 분리 발급하는 옵션 필요.
2. **등록 주체**: 피보호자 본인만 vs 보호자 대행 등록 허용(후자면 `POST /api/guardian/cameras` + ACTIVE 연결 검증 추가).
3. **카메라 개수 상한**: 피보호자당 N대 제한(예 10대) — 어뷰징 방지. 방 5개 예시면 여유 상한이 적절.
4. **FPS 고정값**: 서버 권장 기본값(예 5fps)을 백엔드 config로 내릴지 FE 상수로 둘지.
5. **방 이름 중복**: 같은 피보호자가 "거실" 카메라 2개를 만드는 것을 막을지(라벨 유니크) 허용할지. (기본: 허용 — 재등록/기기 교체 유연성)
6. **Phase C 착수 시점**: 스코프 스트림 토큰(하드 보안 경계)을 이번에 함께 갈지, seam만 심고 다음 단계로 미룰지.

---

## 11. 다음 단계
승인 시: ① `feature/camera-domain` 분기 → ② 엔티티·마이그레이션·리포지토리 → ③ 서비스·인가 + 테스트 → ④ 컨트롤러·DTO·Swagger → ⑤ `./gradlew build -x test` 검증 → PR(`dev`). (코드 변경 = §3 PR 게이트.) FE는 별도 저장소(../SilverBridgeFe) PR로 분리.
