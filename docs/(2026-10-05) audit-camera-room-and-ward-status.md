# 기능 점검 - 카메라 방 8개 고정·방마다 1대 + 피보호자 내 카메라 연결 상태 (2026-10-05)

> 템플릿 B · 대상 PR #299 (`bff53c8`, base dev `2025c44`) · 조사 Sonnet → 판정·반영 Opus · 상세 구현 문서 `(2026-10-05) feature-camera-room-and-ward-status.md`

## 엔드포인트 × 역할

| 엔드포인트 | WARD | GUARDIAN·ADMIN | 비고 |
|---|---|---|---|
| `POST /api/ward/camera` | 본인 | 403 | 클래스 `@PreAuthorize("hasRole('WARD')")`, wardId는 `@AuthenticationPrincipal`만 |
| `GET /api/ward/camera` | 본인 | 403 | 기존 |
| `GET /api/ward/camera/rooms` | 본인 | 403 | 신규 |
| `GET /api/ward/camera/live` | 본인(429 있음) | 403 | 신규, `camera-ward-live` 30/분·600/시 |
| `PATCH /api/ward/camera/{id}` | 본인 것만(타인 403) | 403 | `getOwnedCamera` |
| `DELETE /api/ward/camera/{id}` | 본인 것만(타인 403) | 403 | `getOwnedCamera` |

IDOR: 신규 엔드포인트는 body·path의 사용자 ID를 쓰지 않는다 - **PASS**.

## 발견 사항과 처리

| ID | 심각도 | 내용 | 판정 | 처리 |
|---|---|---|---|---|
| H-1 | 🟠 | 새 등록 동시 경합이 `CAMERA_LABEL_DUPLICATED`가 아니라 일반 `DUPLICATE_VALUE`(409)로 나간다. `Camera` id가 IDENTITY라 `save()`가 INSERT를 즉시 실행하는데, 변환 try가 `flush()`만 감쌌다. 단위 테스트는 mock이 `flush()`에서만 예외를 던져 놓쳤다 | **확인** | 반영 - `save()`도 변환 범위(`translateRoomConflict`)에 넣음. 테스트를 실제 동작대로 분리: 새 등록 = `save()`에서 위반, 방 변경 = `flush()`에서 위반 |
| M-1 | 🟡 | 8개 밖 방 이름을 가진 기존 카메라는 재등록하면 8개 중 하나로 바뀌고, 선택지(`rooms`)에 반영되지 않는다 | 해당 없음 | 배포 전 점검(PHASE 0)에서 운영 카메라 1대 모두 8개 안, 목록 밖 0건 확인 |
| M-2 | 🟡 | 사용 중지(`isActive=false`) 카메라도 방을 차지해 `registered=true` | 의도대로 | DB 제약과 같은 기준. FE 안내에 "방을 비우려면 카메라 삭제"를 넣는다 |
| L-1 | 🟢 | 제약 판정이 DB 메시지 문자열 포함 여부에 기댄다 | 확인 | 반영 - Hibernate `ConstraintViolationException.getConstraintName()`을 먼저 보고 메시지는 보조로 |
| L-2 | 🟢 | 보호자·피보호자 `/live`의 AI 호출·`null` 처리 코드 중복 | 수용 | 리팩터 후보, 필수 아님 |

문제없음 확인(ℹ️): 입력 우회(NFC·서식문자·공백 정리 후 정확 일치 - "작은방 2"·전각·유사문자 400) / 로그·응답에 타인 정보 없음 / V58 DO 블록은 중복 시 데이터 무변경 중단, 제약 추가 잠금은 행 수가 작아 영향 없음 / 속도 제한 키 분리 / N+1 없음(쿼리 1회 + AI 1회) / 같은 방 재등록·수정 멱등 / `deleteAllByWard`·역할 변경 경로 무변경 / AI 호출은 트랜잭션 밖(DB 커넥션을 잡지 않음).

## 테스트

- 반영 후 추가·수정: 새 등록 경합(`save()` 위반 → 409) / Hibernate 제약 이름 판정 / 방 변경 경합(`flush()` 위반 → 409) / 다른 제약은 그대로.
- 남은 약점(수용): 실 DB 두 스레드 동시 등록 테스트는 없다. 서비스 슬라이스를 실 DB로 띄우려면 연결·이벤트 협력자까지 구성해야 해 범위가 커진다. 실제 예외 종류·제약 이름은 V58 통합 테스트(`같은방_두번째_거절`)가 실 PostgreSQL로 확인한다.

## 종합 판정

✅ **PASS** (H-1·L-1 반영 후). 인가·입력 검증·마이그레이션·응답 형식 이상 없음.
