# 기능 점검(템플릿 B) - QA 종합 점검 후속(BE), PR #297 (2026-10-05)

> 대상: PR #297 `17da651`(카메라 세션 ID 피보호자 ID 제거 · 클립 테스트 공백 5건 · 클립 정책 정정) · 점검 기준 dev `15fbec9` · 작업 기록 `docs/(2026-10-05) fix-qa-comprehensive-be.md`
> 점검만 수행(코드 수정 없음). 임시 결함 주입(변이 테스트)은 점검용으로 한 뒤 전부 원복했다(`git status` 깨끗).

## 종합 판정

**✅ PASS (🔴0 🟠0 🟡1 🟢3) → 후속 PR로 M-1·L-1·L-2 해소, L-3 수용** - 보안·인가·기능 정합성·계약은 이상 없음. 테스트 중 1건이 문서가 주장하는 것보다 약하다(M-1).

## PHASE -1 / 0

| 항목 | 결과 |
|---|---|
| 날짜 / 대상 커밋 | 2026-10-05 / `17da651`(dev에 포함, 이후 `15fbec9`까지 문서 PR #298) |
| 빌드 | `./gradlew build` 통과 |
| 서버 반영 | gosky·vkcs 모두 `17da651`(읽기 전용 확인) |
| 마이그레이션 | V57이 최신(이번 PR은 마이그레이션 없음) |
| 변경 파일 | 19개 - 프로덕션 코드 6(`CameraIdentifierFactory`·`CameraService`·DTO 3·`AsyncConfig`), 테스트 6, 문서 7 |

### 엔드포인트 × 역할 (세션 ID 발급·사용)

| 엔드포인트 | 역할 게이트 | 세션 ID와의 관계 |
|---|---|---|
| `POST/GET/PATCH/DELETE /api/ward/camera` | `@PreAuthorize WARD` | 등록 시 **발급**(유일한 발급 경로), 본인 카메라만 |
| `GET /api/guardian/camera`·`/live` | `GUARDIAN` | 응답에 실음, ACTIVE 연결 카메라만 |
| `GET·POST /api/guardian/camera/{sessionId}/*` | `GUARDIAN` | 경로 변수, `getViewableCamera`가 DB 행 + ACTIVE 연결로 판정 |
| `GET /api/camera/stream/{sessionId}/mjpeg` | 티켓 | 티켓 주인·세션 일치로 판정 |
| `GET /api/{guardian,ward}/anomaly/.../clips`·`clips/{id}/file` | `GUARDIAN`/`WARD` | 클립 ID로 접근, 소유·ACTIVE 연결 확인 |

## PHASE A - 보안·인가: PASS

- **발급 경로 단일**: `sessionId`를 만드는 곳은 `CameraIdentifierFactory.newSessionId()`와 호출부 `CameraService.register` 한 곳뿐(전수 grep). 인자로 사용자 식별자를 받지 않는다.
- **난수·길이**: `SecureRandom`, `ward_` + 영숫자 16자(21자) ≤ `VARCHAR(64)`.
- **`ward_` 접두사 판별·분해 코드 없음**: `startsWith("ward")`·`split("_")` 0건 - 세션 ID는 불투명 값.
- **IDOR**: 세션 ID를 아는 것만으로 통과되는 경로 없음. 카메라 열람은 `findBySessionId` 후 `isActiveConnection`, 위반 시 403 `CAMERA_NOT_CONNECTED` + `[IDOR-ATTEMPT]`. 클립은 보호자 403 `ANOMALY_NOT_AUTHORIZED`·피보호자 403 `ANOMALY_CLIP_NOT_OWNED`, 숨김·만료·없음은 404로 구분하지 않는다.
- **노출**: 응답·Swagger 예시는 새 형식으로 일치. 오류 본문에 소유자·파일 이름 없음(HTTP 테스트가 확인). 로그에 남는 세션 ID는 새 형식이면 사용자 정보가 없다(옛 형식 카메라는 서버 내부 로그에만 남는 값이라 수용).

## PHASE B - 기능 정합성: PASS

| 항목 | 결과 |
|---|---|
| 신규 등록 | 새 형식 발급 |
| 같은 `deviceId` 재등록 | 기존 세션 ID 재사용(옛 형식 포함) - 테스트 이름에 명시 |
| 남의 `deviceId` | 무시하고 신규 발급(`CameraServiceTest` 기존 테스트가 커버) |
| 중복 재추첨 | `existsBySessionId` 루프, 95비트라 사실상 1회. 테스트로 재추첨 고정 |
| 포화 폐기와 쿨다운 | 코드대로 쿨다운은 작업 안에서 잡는다 - 로그·정책 문구와 일치(단 M-1 참고) |
| 고아 청소 | 1시간 경계(`SETTLE`)와 "최대 약 2시간 잔존" 문구 일치 |

## PHASE C - 구조·계약: PASS

- `newSessionId` 호출부: main 1곳 + 테스트(`CameraServiceTest`·신규 테스트)뿐. 컴파일 통과.
- 응답 DTO의 `sessionId` 필드 이름·타입 무변경(예시 문자열만 바뀜) - FE 계약 유지.
- `AsyncConfig` 변경은 로그 문구·Javadoc뿐(풀 크기 2/2/20·폐기 정책 무변경).
- 정책 문서·CLAUDE.md의 새 규칙이 코드와 일치(코드로 대조).

## PHASE D - 테스트: 변이(결함 주입) 검증

신규 테스트가 실제로 동작을 고정하는지 결함을 임시로 넣어 확인했다.

| 주입한 결함 | 잡은 테스트 | 판정 |
|---|---|---|
| 쿨다운 Redis 장애를 fail-open으로(`return true`) | `AnomalyClipCooldownTest` fail-closed | ✅ 잡음 |
| 파일 응답에서 `Cache-Control` 제거 | `GuardianAnomalyClipHttpTest` 전체·Range | ✅ 잡음 |
| 보호자 ACTIVE 연결 검사 생략 | `GuardianAnomalyClipHttpTest` 403 3건 | ✅ 잡음 |
| 세션 ID를 6자로 축소 | `CameraIdentifierFactoryTest` 형식 | ✅ 잡음 |
| `clipExecutor` 포화 시 `CallerRunsPolicy` | `AnomalyClipExecutorSaturationTest` 2건 | ✅ 잡음 |
| 쿨다운 확인을 이벤트 발행 쪽(작업 밖)으로 이동 | (잡지 못함) | ⚠️ M-1 |

## 이슈

### 🟡 M-1 포화 테스트가 문서의 주장보다 약하다
- **무엇**: `AnomalyClipExecutorSaturationTest`의 Javadoc과 정책 문서는 "쿨다운 확인을 리스너 제출 전으로 옮기면 여기서 깨진다"고 쓰지만, 테스트는 제출 람다를 **스스로** 만든다(`executor.execute(() -> listener.handleAnomalyDetected(...))`). 운영에서 쿨다운을 작업 밖(예: `AnomalyDetectionService`가 이벤트를 발행하기 전)으로 옮겨도 테스트는 통과한다. 실제로 막는 것은 "실행기가 CallerRuns로 바뀌는 것"과 "`capture` 안에서 쿨다운을 잡는다"뿐이다.
- **영향**: 정책 규칙("쿨다운 확인을 제출 전으로 옮기지 말 것")이 테스트로 보호된다는 착각. 코드 동작 자체는 정상이다.
- **제안**: 문구를 사실대로 정정하는 것이 가장 싸다("실행기 설정·`capture` 안 쿨다운을 고정한다"). 진짜로 막으려면 `AnomalyClipListener`가 `@Async("clipExecutor")`임은 `NotificationExecutorAssignmentTest`가 이미 고정하므로, 이벤트 발행 서비스가 `AnomalyClipCooldown`에 의존하지 않는다는 ArchUnit/리플렉션 테스트를 더하는 방법이 있다(선택).

### 🟢 L-1 피보호자 경로 클립 파일의 HTTP 테스트 없음
- 보호자 경로만 HTTP 단위로 고정했다. 피보호자 경로(`WardAnomalyClipController`)는 응답 생성 헬퍼(`AnomalyClipFileResponse`)를 공유해 헤더는 같은 코드가 만들지만, `ANOMALY_CLIP_NOT_OWNED` 403이 HTTP 응답 코드로 나오는지는 서비스 단위 테스트까지만 본다. 같은 서비스·예외 핸들러를 쓰므로 위험은 작다.

### 🟢 L-2 설계 문서 의사 코드의 옛 시그니처
- `docs/(2026-07-03) design-camera-domain.md:204` 의 `factory.newSessionId(wardId)   // ward_{wardId}_{rand}`가 남아 있다(같은 문서의 30·101행은 정정됨). 설계 당시 스니펫이라 동작 영향은 없지만 현행과 어긋난다. 한 줄 정정 권장.

### 🟢 L-3 테스트 픽스처가 옛 형식 혼재
- `ward_a9cC5f_k3m` 형식 픽스처가 약 10개 테스트 클래스에 남아 있다. 세션 ID는 불투명 값이라 동작에는 영향이 없고, 기존 카메라(옛 형식) 호환을 보여주는 면도 있다. 새 형식으로 일괄 교체할지는 선택(권장하지 않음 - 변경량 대비 이득이 없다).

## 확인 사항(이슈 아님)

- 통합 테스트 `고아_파일_1시간_경계`: 61분/59분 경계에 1분 여유, 공유 저장소의 다른 테스트 파일은 모두 방금 만든 것이라 청소에 걸리지 않는다. vkcs에서 `db572bd` 기준 통과(캐시 아님).
- 변이 검증 중 `GuardianAnomalyClipHttpTest`의 "파일 없음 404" 테스트가 연결 검사 생략 결함에서 함께 실패한 것은 Mockito 엄격 스텁(안 쓰인 스텁) 때문이며 정상 동작이다.

## 후속 조치 (2026-10-05, `fix/qa-comprehensive-be-followup`)

- **M-1 해소**: 테스트 Javadoc·정책 문서(453행)를 실제 보장 범위로 정정하고, 쿨다운을 `AnomalyClipCaptureService`만 쓰도록 고정하는 구조 테스트(`cooldownIsOnlyUsedInsideCapture`)를 추가했다. 발행 서비스가 쿨다운을 참조하도록 임시 변경하면 실패함을 확인하고 원복했다.
- **L-1 해소**: `WardAnomalyClipHttpTest`(5건) 추가 - 남의 클립·상황 403 `ANOMALY_CLIP_NOT_OWNED`, 보호자 0명 404(파일 보존), 정상 200 헤더, Range 206.
- **L-2 해소**: 설계 문서 204행 정정.
- **L-3**: 수용(변경하지 않음).

## 수정 제안 (점검 시점, 코드는 건드리지 않았다)

| # | 변경 | 비고 |
|---|---|---|
| M-1 | `AnomalyClipExecutorSaturationTest` Javadoc과 정책 문서 453행 문구 정정(필요하면 구조 테스트 추가) | 문서·주석뿐이면 PR 불필요(문서 예외) - 단 테스트 Javadoc은 코드 파일이라 PR 경유 |
| L-2 | 설계 문서 204행 한 줄 정정 | 문서뿐(dev 직접 가능) |
| L-1 | 피보호자 클립 파일 HTTP 테스트 추가 | 선택 |

커밋 메시지 초안(M-1·L-2를 함께 정리할 때):
```
docs: 클립 포화 테스트 보장 범위 정정과 카메라 설계 문서 예시 갱신

- 포화 테스트가 고정하는 것은 실행기 설정과 capture 안 쿨다운이며, 쿨다운을 발행 쪽으로 옮기는 회귀는 막지 못함을 명시
- 카메라 설계 문서 의사 코드의 옛 세션 ID 시그니처 정정

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
```
