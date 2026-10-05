# QA(종합 점검) 후속 - 백엔드 (2026-10-05)

> 브랜치 `fix/qa-comprehensive-be`(dev `e472fc8` 기준) · 마이그레이션 **없음** · 근거: Notion "DMU / SilverBridgeQA / QA(종합 점검)"(2026-10-05) 클립 QA·보안 QA

## 1. 범위

| # | 항목 | 결과 |
|---|---|---|
| ① | 카메라 세션 ID에서 피보호자 ID 제거 | 신규 발급분부터 `ward_` + 영숫자 16자 |
| ② | 클립 테스트 공백(백엔드 해당 5건) | 5건 모두 보강(단위 4클래스 + 통합 1건) |
| ③ | 낮은 위험 정리 | 포화 폐기·쿨다운은 **문제 없음 확인**(테스트로 고정, 로그·정책 문구 정정) / 정책 문서 보충 |

범위 밖: FE 프록시 인증·게임 화면, AI 서버(챗 로그 소유자 확인·게임 API 키·링버퍼 메모리), nginx 80포트, `CLIENT_IP_TRUSTED_PROXIES`(QA에서 정정 - 문제 아님).

## 2. PHASE 0 - 전제와 실제 코드의 차이

| # | 전제 | 실제 | 처리 |
|---|---|---|---|
| D1 | 기존 카메라는 재등록 때 새 ID로 바뀐다 | 같은 기기(`deviceId`, FE `localStorage`)로 재등록하면 **기존 세션 ID를 재사용**한다(`CameraService.register`) | 기존 카메라 유지(수용). 삭제 후 재등록해야 새 형식 |
| D2 | 포화로 폐기된 클립은 쿨다운이 남아 5분간 클립이 안 생길 수 있다 | 쿨다운은 `clipExecutor` 작업 **안**에서 잡으므로 폐기된 작업은 쿨다운을 잡지 않는다 | 코드 로직 무변경. 테스트로 고정, `AsyncConfig` 폐기 로그·Javadoc과 정책 "수용한 한계"의 틀린 문구 정정 |
| D3 | 정책에 L-2(판정 뒤 저장 클립은 공개) 명시 필요 | 2026-10-04에 이미 명시돼 있음 | 확인만 |
| D4 | 503 `CLIP_DISABLED`·ffmpeg 옵션 반영 필요 | 503은 이미 코드·문서에 있음. ffmpeg 옵션(`-an`·짝수 해상도·`nice`)만 빠져 있음 | 기능 문서 §10·정책에 보충 |
| D5 | 탈퇴 파일 삭제 실패 시 최대 약 2시간 잔존 | 맞음. 기능 문서는 "최대 약 1시간"으로 잘못 적혀 있었음 | 정정 + 정책에 수용 명시 |
| D6 | 테스트 공백 6건 | 백엔드 해당 5건("AI 프레임 100장 메모리"는 AI 서버) | 5건 보강 |
| D7 | 형식 변경이 FE·AI·QA에 영향 | 4개 저장소 전수: 세션 ID 파싱·형식 정규식 **0건**(불투명 문자열). 컬럼 BE `VARCHAR(64)`, AI `String(128)`. FE·AI는 URL에 인코딩 없이 넣음 | 영숫자만 사용. 영향 없음 |

## 3. 변경 내용

### ① 세션 ID 형식

- `CameraIdentifierFactory.newSessionId()` - 인자 제거, `ward_` + 영숫자 16자(약 95비트, 21자). 중복 시 다시 뽑기는 그대로.
- `CameraService.register` 호출 1줄, Swagger 예시 3곳(`CameraResponse`·`GuardianCameraView`·`GuardianLiveCameraView`).
- 기존 카메라: 그대로(일괄 재발급·재등록 시 교체는 송출 설정을 깰 수 있어 하지 않음).

### ② 테스트 보강

| 공백 | 테스트 | 내용 |
|---|---|---|
| 세션 ID 형식 | `CameraIdentifierFactoryTest`(신규 4) | `^ward_[A-Za-z0-9]{16}$`, 매번 다름, 중복 시 재추첨, DeviceID 형식 |
| 클립 쿨다운 전용 | `AnomalyClipCooldownTest`(신규 7) | 5분 TTL·키 형식, 이미 잡힘, null, **Redis 장애 fail-closed**, 해제, 해제 실패 삼킴, 유형별 키 |
| 보호자 클립 보안 + 파일 헤더 | `GuardianAnomalyClipHttpTest`(신규 9) | 실제 인가 서비스·클립 서비스·파일 + MockMvc. 비연결·PENDING·다른 집 클립 403(본문에 소유자·파일 정보 없음), 숨김·만료·없음·파일 없음 404, 200 `video/webm`·`private, no-store`·`clip-{id}.webm`, **Range 206 + `Content-Range`** |
| 포화 폐기와 쿨다운 | `AnomalyClipExecutorSaturationTest`(신규 2) | 실제 `clipExecutor`(2/2/20)를 채운 뒤 폐기: 호출 스레드에서 실행 안 함·예외 없음 / 폐기된 작업은 Redis를 건드리지 않고, 풀린 뒤 다음 감지가 쿨다운을 잡음 |
| 고아 청소 통합 | `AnomalyClipIntegrationTest#고아_파일_1시간_경계`(추가 1) | 실제 DB 파일 목록 + 실제 파일 수정 시각, 매시 진입점 `cleanupOrphanFiles()`: 행 없는 61분 파일 삭제 / 59분 파일·행 있는 3시간 파일 유지 |

`CameraServiceTest`는 시그니처 변경에 맞추고, 재등록 테스트 이름에 "옛 형식도 재사용"을 명시했다.

### ③ 문구·문서

- `AsyncConfig`: `[ANOMALY-CLIP-REJECTED]` 로그와 Javadoc을 실제 동작("쿨다운을 잡기 전이라 다음 위험 감지에서 다시 시도")으로 정정.
- 정책 파일: "카메라 세션 ID - 사용자 식별자를 싣지 않는다" 절 신설 / 클립 절에 포화 폐기·쿨다운 정정, 파일 잔존 최대 약 2시간 수용, AI 계약 v2 보충(503·ffmpeg 옵션).
- CLAUDE.md §8 한 줄, 기능 문서(2026-10-04) §10·81행, 설계 문서(2026-07-03) 형식 표기.

## 4. 검증

- `./gradlew build` 통과 - 단위 테스트 **1300건 / 실패 0**.
- 통합 테스트: vkcs `integration-test.sh fix/qa-comprehensive-be` **통과**(`db572bd`, 캐시 아님). 명령:
  ```bash
  ~/SilverBridgeBe/tools/integration-test.sh fix/qa-comprehensive-be
  ```

## 5. FE 전달 - 카메라 세션 ID 형식 변경 안내

> 아래를 FE 전달 페이지에 그대로 붙여 쓴다.

**카메라 세션 ID 형식이 바뀝니다 (백엔드 배포 후 새로 등록하는 카메라부터)**

- 예전: `ward_a9cC5f_k3m9Q2` (가운데가 피보호자 ID) → 이제: `ward_k3m9Q2aZ7pLx01Bc` (`ward_` + 영문·숫자 16자)
- 이유: 세션 ID에서 피보호자 ID를 알아낼 수 없게 하기 위해서입니다(QA 종합 점검 보안 항목).
- **FE 코드 수정은 필요 없습니다.** 세션 ID를 쪼개거나 형식을 검사하는 곳이 없음을 확인했습니다. 앞으로도 세션 ID에서 피보호자 ID 등을 꺼내 쓰지 말아 주세요(값 그대로 사용).
- 이미 등록된 카메라는 **기존 ID를 그대로 씁니다**(같은 기기로 다시 등록해도 유지). 옛 형식을 없애려면 그 카메라를 삭제하고 다시 등록하면 됩니다.
- 길이는 최대 64자, 영문·숫자·`_`만 쓰므로 URL에 그대로 넣어도 됩니다.

## 6. 점검 제안 (CLAUDE.md §2-6)

- 건드린 공용 코드: `CameraService.register`(호출 1줄)·`AsyncConfig`(로그 문구). 이벤트 계약·인가 메서드·`NotificationType`은 변경 없음.
- 사용처 전수(세션 ID 4개 저장소)는 PHASE 0에서 이미 수행했다. 그래서 **템플릿 C 대신 범위를 좁힌 기능 점검(B)** 을 제안한다. 초안:
  - [모델: sonnet] 세션 ID 발급 경로 1곳·재등록 재사용 분기·Swagger 예시 일치 / 새 테스트 5종이 동작을 실제로 고정하는지(잠금·구현을 바꾸면 깨지는지) / 정책 문서와 코드 대조
  - 통합 테스트 vkcs 통과 확인 후 `docs/audit-index.md` 상태 갱신

## 7. 배포 (2026-10-05)

- PR #297 머지 `17da651` → vkcs CD 성공(배포 전 통합 테스트 포함), api healthy.
- gosky 수동 배포: `git pull --ff-only origin dev` → `docker compose -f docker-compose.dev.yml up -d --build api`(추적되지 않는 파일 보존, db·redis 무변경). Flyway 57건 검증·기동·AI WS 재연결·healthy 확인.
- 새 세션 ID 형식은 **배포 후 새로 등록하는 카메라부터** 적용된다. FE 전달 문구는 §5.
