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
- 2026-10-14 10:45 gosky, 사용자 확인 후 실행. 백업: gosky 로그인 홈의 `camera-reset-backup-20261014-1044.sql`(camera·anomaly_clip·anomaly_incident 데이터, `pg_dump --data-only`).
- `delete from camera`를 한 트랜잭션으로 실행: **카메라 3행 삭제**. 직후 건수: camera 0 / anomaly_clip 13 / anomaly_incident 47 / anomaly_event 97.
- 이상감지 이력(event·incident)은 유지했다. `anomaly_clip` 행·파일 13건도 남아 있다(`camera` FK로 지워지지 않는 구조) - 삭제 이벤트를 거치지 않았으므로 보관 만료(최대 30일)·오탐 24시간 등 기존 삭제 경로로 정리된다. 이력에서의 위치(방 이름) 표시는 카메라 행이 없어 빈칸일 수 있다.
- vkcs-linux는 카메라 0건이라 초기화 대상이 없었다.
- 직접 SQL 삭제라 `CameraDeletedEvent`가 발행되지 않았다(클립 즉시 정리 없음). 다음 초기화는 가능하면 피보호자 본인 삭제 경로를 쓴다.
