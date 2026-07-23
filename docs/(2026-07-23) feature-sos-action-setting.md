# 피보호자 SOS 동작 설정 — 계정 단위 영속화 (2026-07-23)

## 배경

FE 점검에서 **환경설정 > SOS 동작 설정**(3개 라디오)이 실제로는 아무 동작도 하지 않는 것이 확인됐다.

- FE 배포본(`develop` / `e160256`, 로컬과 동일 커밋)에서 `sosAction` 참조 6곳이 **전부 저장·표시용**이고, SOS 실행 화면(`WardSosContent.tsx`)에는 참조가 **0건**이다.
- `tel:119` 로 119에 거는 코드가 **존재하지 않는다**. 소스의 `119`는 전부 설정 화면의 라벨·설명 문자열이다.
- 설정값은 **localStorage 전용**이라 기기·브라우저를 바꾸면 초기화된다.

즉 "잘못 구현"이 아니라 **UI만 먼저 나가고 동작이 없는 상태**였다. 생명과 직결된 기능이라 "119에 바로 연결"을 선택해 둔 사용자가 실제로는 119에 연결되지 않는 **오해 유발**이 가장 큰 문제였다.

## 결정 사항 (2026-07-23)

| # | 결정 | 영향 |
|---|---|---|
| 1 | **보호자 알림은 끌 수 없다** | 백엔드 `WARD_SOS` 강제 발송 유지 — **코드 변경 없음** |
| 2 | **SOS 설정을 계정 단위로 동기화한다** | 이 PR의 범위 — 저장·조회 API 신설 |
| 3 | **SOS 동작 설정 UI는 기능 완성까지 숨긴다** | FE 조치 |

①에 따라 "119 바로 연결(보호자 알림 없이)"은 채택하지 않고, 세 옵션의 차이를 **"119를 어떻게 연결·안내할지"** 로 재정의했다. 긴급 SOS에서 보호자 알림을 끄는 선택지는 서비스 취지에 맞지 않고, 백엔드에 "알림 억제" 경로를 새로 만들면 "SOS는 끌 수 없는 필수 알림" 정책과 정면 충돌한다.

## 범위

**이번 PR = 설정값 보관·조회만.** 알림 발송 경로는 일절 손대지 않았다.

- 무변경 확인: `SosService` · `SosNotificationListener` · `NotificationDispatcher` · `NotificationType(WARD_SOS)` · `WardSosController`
- 119 전화 연결은 **프론트의 `tel:` 링크** 담당이며 백엔드는 관여하지 않는다(기존 `WardSosController` 안내와 동일).

## 구현

### 데이터

`V32__add_sos_setting.sql` — 신규 테이블 `sos_setting`(단수형, V31 규칙).

```sql
CREATE TABLE sos_setting (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sos_action VARCHAR(30)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sos_setting_user UNIQUE (user_id)
);
```

- **행이 없으면 기본값**(`CALL_119_AND_NOTIFY`) → 기존 사용자 **백필 불필요**. `user_notification_setting`(V25)과 동일한 방식.
- 기본값은 FE의 기존 `DEFAULT_WARD_SETTINGS.sosAction`(`call119AndNotify`)과 같은 값이라 계정 동기화로 전환해도 동작이 바뀌지 않는다.
- users FK `ON DELETE CASCADE` — 회원 탈퇴(hard delete) 시 자동 정리.
- ※ V32 번호 안전성 확인: 2026-07-15 카카오 푸시 검토 때 쓰였다가 revert된 V32가 있으나 **git 전체 브랜치에 흔적 0건**(커밋된 적 없음) → CD(`dev` push) 경로로 배포된 적이 없어 번호 재사용에 문제 없다.

### enum `SosAction`

FE가 쓰던 키와 1:1 대응시켜 화면 라벨만 교체하면 되도록 했다(A안).

| 값 | FE 키 | 의미 |
|---|---|---|
| `CALL_119` | `call119` | 119 즉시 연결 |
| `CALL_119_AND_NOTIFY` | `call119AndNotify` | 119 연결 + 보호자 알림 안내 **(기본값)** |
| `NOTIFY_GUARDIAN_FIRST` | `notifyGuardianFirst` | 보호자에게 먼저 알린 뒤 119 안내 |

> ⚠️ **`CALL_119`는 "보호자 알림 없이"라는 뜻이 아니다.** 세 값 모두 보호자 알림이 발송되며, 차이는 119 연결 시점·안내 방식뿐이다. 이 주의사항은 enum·서비스·컨트롤러 Swagger 설명에 모두 명시했다.

### API

| Method | Path | 권한 |
|---|---|---|
| GET | `/api/ward/sos-setting` | `hasRole('WARD')` |
| PUT | `/api/ward/sos-setting` | `hasRole('WARD')` |

- SOS는 피보호자 전용 기능이라 WARD만 접근 가능(GUARDIAN/ADMIN 403).
- 요청/응답 모두 `{ "sosAction": "CALL_119_AND_NOTIFY" }` 단일 필드.
- 정의되지 않은 값은 400.

### 변경 파일

**신규 9개 / 기존 수정 0개**

```
src/main/resources/db/migration/V32__add_sos_setting.sql
domain/sos/entity/SosAction.java
domain/sos/entity/SosSetting.java
domain/sos/repository/SosSettingRepository.java
domain/sos/service/SosSettingService.java
domain/sos/dto/SosSettingResponse.java
domain/sos/dto/SosSettingUpdateRequest.java
domain/sos/controller/WardSosSettingController.java
test/domain/sos/service/SosSettingServiceTest.java
test/domain/sos/controller/WardSosSettingControllerSecurityTest.java
```

## 테스트 결과

`./gradlew build` **BUILD SUCCESSFUL** — 전체 통과, 회귀 없음.

신규 8건:

- `SosSettingServiceTest` (4) — 설정 없음 → 기본값 / 저장값 우선 / 기존 행 갱신 시 `save` 미호출 / 신규 행 upsert
- `WardSosSettingControllerSecurityTest` (4) — WARD 조회·변경 허용 / GUARDIAN 조회·변경 403

## 검증 가이드

```bash
# 조회 (설정한 적 없으면 기본값)
curl -H "Authorization: Bearer {wardAccessToken}" \
     https://api.devdmu.gosky.kr/api/ward/sos-setting
# → { "data": { "sosAction": "CALL_119_AND_NOTIFY" } }

# 변경
curl -X PUT -H "Authorization: Bearer {wardAccessToken}" \
     -H "Content-Type: application/json" \
     -d '{"sosAction":"NOTIFY_GUARDIAN_FIRST"}' \
     https://api.devdmu.gosky.kr/api/ward/sos-setting

# 보호자 토큰으로 호출 시 403 확인
```

## 남은 작업 (FE)

1. **SOS 동작 설정 UI 숨김** — 기능 완성 전까지(우선순위 높음, 이미 노출 중)
2. `WardSosContent.tsx`가 설정값을 읽도록 연결 + 옵션별 `tel:119` 분기
3. localStorage → 이 API로 전환
4. 죽은 상수 `WARD_SOS_OPTIONS` 정리(화면은 `WardSettingsContent`의 자체 `SOS_OPTIONS`를 쓰고 문구도 다름)
5. **실기기 검증** — `tel:`은 PC에서 동작하지 않고, API 응답을 `await`한 뒤 이동하면 클릭 제스처 소모로 차단될 수 있음

FE 전달 문서: 노션 DMU > 프론트엔드 전달 내용 > "SOS 동작 설정 — 현재 아무 동작도 하지 않습니다"
