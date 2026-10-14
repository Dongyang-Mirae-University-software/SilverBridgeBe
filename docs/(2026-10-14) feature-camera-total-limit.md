# 서비스 전체 카메라 등록 상한 3대 (2026-10-14)

## 배경
- 15fps 송출 기준 AI 분석은 1장 19.2ms(스레드 1개)라 동시 송출 3대에서 사용률 약 86%, 4대부터 초과다(`(2026-10-13) design-video-transport-be.md` 1-1절).
- 학생 프로젝트 규모라 "모든 사용자 합산 3대"로 등록 자체를 막기로 했다(사용자 결정 2026-10-14). 등록 수가 곧 동시 송출 상한이 된다.

## 변경
- `camera.max-total`(env `CAMERA_MAX_TOTAL`, 기본 3, 0 이하면 끔). `CameraService.register`의 **새 카메라 분기**에서만 검사한다.
- 초과 시 409 `CAMERA_LIMIT_REACHED`("현재 서비스에서 등록할 수 있는 카메라를 모두 사용했습니다. 등록된 카메라를 정리한 뒤 다시 시도해주세요."). 문구에 숫자를 넣지 않아 설정값이 바뀌어도 어긋나지 않는다.
- 동시 등록: `CameraRepository.lockTotalLimit`(`pg_advisory_xact_lock`)으로 한 줄로 세운 뒤 `count()` 확인과 INSERT. 마이그레이션 없음.
- 검사 순서: 방 이름(400) → 같은 방 중복(409) → 전체 상한(409).

## 바뀌지 않는 것
같은 기기 재등록(멱등)·방 변경·삭제, 방당 1대·방 8개 규칙, sessionId 발급, 시청 티켓·중계, 클립 저장, 이상감지·알림, `recommended-fps`.

## 테스트
- 단위 `CameraTotalLimitTest`: 상한 도달 거절·미만 등록·재등록 통과(락·카운트 미호출)·0이면 끔·방 검증 우선
- 통합 `CameraTotalLimitConcurrencyIntegrationTest`(실 PostgreSQL, vkcs에서 `tools/integration-test.sh`): 8건 동시 × 5회에서 매 회 정확히 3건 성공, 상한에서 재등록 통과·삭제 후 신규 등록

## 운영
- gosky 기존 카메라 3대·클립은 배포 뒤 초기화한다(이상감지 이력은 유지). 절차와 결과는 아래 "초기화 기록"에 적는다.
- FE: 409 `CAMERA_LIMIT_REACHED`일 때 `error.message`를 그대로 보여 주면 된다(코드로 분기 가능).

## 초기화 기록
(적용 후 기입)
