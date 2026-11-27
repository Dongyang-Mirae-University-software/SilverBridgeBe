# 기능 점검(B) - AI 말벗 검수 DB 동시 쓰기 잠금 수정 (AI #365)

- 날짜: 2026-11-27 / 대상: AI `main` 38361c3 (gosky 같은 커밋, 11/27 07:16 UTC 기동) / DGX 사본 md5 일치
- 변경: `app/database/companion_base.create_review_engine`(sqlite면 WAL·`busy_timeout` 5000·`synchronous=NORMAL`), `companion_router._queue_for_review`의 검수 쓰기 예외 삼킴
- 종합: **PASS** - 🔴·🟠 없음, 🟡 1·🟢 3. 데이터 안전에는 문제가 없다. 다만 쓰기 실패가 이제 로그에만 남아 드러나지 않는 점(X-1)은 손보기를 권한다.

## PHASE -1·0

| 항목 | 결과 |
|---|---|
| gosky 검수 DB 파일 | `companion_review.db` 180KB, `-wal` 48KB, `-shm` 32KB, 백업 `.bak-20261127-wal` 180KB |
| `/app/data` 볼륨 | ext4 위 bind mount - WAL을 쓰기에 안전(네트워크 파일 시스템 아님) |
| 프로세스 | uvicorn 작업자 1개(`--workers` 없음) - 같은 파일을 여는 프로세스는 하나 |
| 파일을 여는 곳 | 앱 코드 `ReviewStore`·`RewriteCountStore` 둘뿐(모두 `create_review_engine`). scripts·tools·백업 스크립트·cron에서 여는 곳 없음 |

## PHASE A 데이터 안전

- **A-1 백업**: AI 문서·스크립트에 검수 DB를 파일째 복사하는 백업 안내는 없다. 11/27 백업은 sqlite backup API로 받아 `-wal`에 있던 최근 쓰기까지 들어 있다(완전함). 앞으로 `data/` 폴더를 파일째 복사하면 `-wal`을 함께 복사해야 한다(X-2).
- **A-2 `-wal` 크기** (DGX 사본):

  | 상황 | `-wal` |
  |---|---|
  | 쓰기 1만 건 뒤 | 4,055KB |
  | 긴 읽기를 연 채 5천 건 | 4,059KB |
  | 읽기를 닫은 뒤 1,500건 | 4,059KB |

  자동 체크포인트(1,000쪽)가 비운 뒤 같은 자리에 다시 써서 약 4MB에서 멈춘다. gosky 디스크(84%)에 주는 영향은 미미하다.
  - 긴 읽기가 체크포인트를 막는지는 제대로 재지 못했다. 이 드라이버(pysqlite)는 SELECT만으로 트랜잭션을 시작하지 않아 읽기 스냅샷이 붙잡히지 않았다. 실제 코드의 목록 조회는 짧은 읽기라 운영 위험은 낮다(X-4).
- **A-3 `synchronous=NORMAL` 손실 범위**: WAL에서 `NORMAL`은 프로세스가 죽거나 컨테이너가 강제로 꺼져도 커밋된 쓰기를 잃지 않는다(쓰기는 OS 캐시에 넘어가 있다). 잃을 수 있는 것은 OS가 멈추거나 전원이 끊기는 순간의 마지막 몇 건이고, DB는 깨지지 않는다. 잃는 것이 검수 항목·횟수(운영용 자료)라 받아들일 수 있다.
- **A-4 PostgreSQL**: `engine.dialect.name == "sqlite"`일 때만 PRAGMA를 보낸다. PostgreSQL로 바꾸면 설정이 빠진다 - 코드로 확인.

## PHASE B 운영 동작

- **B-1 예외 삼킴**: 검수·횟수 쓰기 실패는 `WARNING` 로그(`[COMPANION-REVIEW] add failed`·`[COMPANION-REWRITE-COUNT] record failed`)로만 남는다. 상태 API(`/companion/status`)·헬스에는 드러나지 않는다. 디스크가 가득 차거나 파일 권한이 바뀌면 턴마다 경고만 쌓이고 검수 목록·횟수가 조용히 빠진다. #365 전에는 검수 실패가 턴 오류로 드러났다(X-1).
- **B-2 gosky 로그** (기동 이후): 검수 쓰기 실패 0, 횟수 기록 실패 0.
- **B-3 쓰는 중 조회** (DGX): 쓰기 8스레드가 도는 5초 동안 검수 목록 조회·승인 17번, 오류 0.
- **B-4 디스크**: `-wal`·`-shm` 합계는 운영에서 수십 KB이고, 많이 써도 약 4MB에서 멈춘다.

## PHASE C 테스트

| 변이 (DGX 사본, 끝난 뒤 md5로 원복 확인) | 결과 |
|---|---|
| `synchronous` 줄 빼기 | 잡힘 |
| 검수 실패 삼킴 빼기 | 잡힘 |
| dialect 분기 빼기(PostgreSQL에도 PRAGMA를 보냄) | **살아남음** - PostgreSQL 테스트가 없다 (X-3) |

gosky 전체 pytest는 배포 직후 결과(5,106 통과, 실패 1건은 `pgrep` 환경 문제)를 그대로 쓴다.

## 이슈

| 등급 | ID | 내용 | 위치 | 제안 |
|---|---|---|---|---|
| 🟡 | X-1 | 검수·횟수 쓰기 실패가 경고 로그로만 남아, 계속되는 장애(디스크 가득·권한)에서 운영자 자료가 조용히 빠진다. 사용자 대답에는 영향이 없어 🟠로 보지 않았다 | `companion_router.py` `_queue_for_review`, `rewrite_counts.py` `record` | 두 저장소에 기동 이후 실패 횟수를 세어 `/companion/status`에 `reviewWriteFailures`·`countWriteFailures`로 싣는다. 연속 10회 실패면 `ERROR` 한 줄 |
| 🟢 | X-2 | WAL이라 DB 파일만 복사하면 최근 쓰기가 빠진다 | 운영 절차 | AI 팀 문서에 "백업은 sqlite backup API·`VACUUM INTO`, 파일 복사면 `-wal`·`-shm`까지" 한 줄 |
| 🟢 | X-3 | dialect 분기를 지키는 테스트가 없다 | 테스트 | sqlite가 아닌 URL(가짜 dialect)에서 PRAGMA를 보내지 않는지 보는 테스트 |
| 🟢 | X-4 | 긴 읽기가 체크포인트를 막는지 재지 못했다 | - | 수용 (조회는 짧은 읽기) |

## 수정 커밋 초안 (실행은 따로 "시작해")

```
fix: 말벗 검수 DB 쓰기 실패를 상태 API 에 드러냄 (#365 점검 X-1·X-2·X-3)

- 검수·고쳐 쓰기 횟수 쓰기 실패 수를 /companion/status 에 싣고, 연속 10회 실패면 ERROR 한 줄
- WAL 백업 주의(팀 문서), sqlite 가 아닌 엔진에 PRAGMA 를 보내지 않는 테스트
```
