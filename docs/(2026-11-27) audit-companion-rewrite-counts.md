# 기능 점검(B) - AI 말벗 꼬리질문 고쳐 쓰기 날짜별 횟수 저장·조회 (AI #364)

- 날짜: 2026-11-27 / 대상: AI `main` 47e6f52 (gosky 같은 커밋) / DGX 사본 md5 일치
- 변경: `app/services/companion/rewrite_counts.py`(`companion_rewrite_daily`), `reply_service._apply_followup_fixes`의 `rewrite_counts.record`, `GET /companion/ops/rewrite-counts`
- 종합: **PASS (조건부)** - 🔴·🟠 없음, 🟡 1·🟢 4. 🟡 W-1은 지금 운영 조건(BE 동시 턴 8건)에서는 나지 않지만, #364가 새로 만든 실패 가능성이라 고치기를 권한다.

## PHASE 0 `record`가 불리는 경로

- 턴 엔드포인트(`reply_text` 등)는 일반 `def`라 FastAPI 작업 스레드에서 돌고, DB 쓰기도 그 스레드에서 일어난다(응답을 만드는 경로 안의 동기 쓰기).
- `generate_reply`의 시도 반복문 안에서 **규칙 검사를 통과하고 마지막 턴이 아닌 후보**마다 `_apply_followup_fixes` → `record`가 불린다(`reply_service.py:934`).
- 세지 않는 턴: 마지막 인사, 규칙에 걸린 후보, LLM 실패·대체 문장, 위험·되묻기·무응답(`generate_reply`를 거치지 않음).
- **정의**: `followup_checked`는 "최종 대답 수"가 아니라 "고쳐 쓰기를 판단한 꼬리질문 후보 수"다. 기본값(`COMPANION_LLM_RETRY=0`)에서는 한 턴에 많아야 한 번이다. 재시도를 켜거나, 판단 뒤 "지난 문장과 비슷함"으로 다시 만들어진 경우에는 최종 대답과 어긋날 수 있다(W-2).

## PHASE A 보안·개인정보 - PASS

- 표 열은 날짜·종류·결과·개수뿐이다. 응답·로그에 원문·피보호자·대화 식별자가 없다(기록 실패 로그는 예외 클래스명만).
- 엔드포인트는 말벗 라우터 전체에 걸린 `require_companion_api_key` 아래에 있다. `from`·`to`는 `date.fromisoformat`으로 검사하고 범위는 90일 이하다.
- 탈퇴 삭제(`DELETE /wards/{id}/data`): 피보호자 정보가 없는 개수라 지울 대상이 아니다 - 판단 맞음.

## PHASE B 운영 동작

### B-1·B-2 sqlite 동시 쓰기 (DGX 사본, 임시 sqlite, 스레드마다 30번)

| 조건 | 8 스레드 | 12 스레드 | 16 스레드 |
|---|---|---|---|
| 검수 쓰기만 (#364 전과 같은 쓰기) | 실패 0 (3회) | - | 실패 0 (3회) |
| 검수 + 횟수 (#364 뒤) - 검수 쓰기 실패 | 0 (3회) | 3회 중 1회에서 1건 | 3회 모두 1~3건 |
| 횟수 유실 | 0 | - | 0 (480/480) |
| 지연 (8 / 16 스레드) | 검수 p50 7.0ms·p95 11.0ms, 횟수 p50 6.8ms·p95 9.1ms·최대 438ms | - | 검수 p95 13.3ms, 횟수 p95 9.5ms·**최대 3,257ms** |

- 실패는 모두 `sqlite3.OperationalError: database is locked`이고, 실패한 쪽은 **검수 쓰기(`review_store.add`)**다. 횟수 기록은 예외를 삼켜 대답을 막지 않지만, 검수 쓰기는 예외를 잡지 않는다(`companion_router.py:169` `_queue_for_review`). 그래서 실패하면 **그 턴 요청이 오류로 끝난다**(W-1).
- BE가 말벗 턴을 서버 전체 8건으로 제한하고, 모델 생성이 한 번에 하나씩이라 쓰기 시점도 흩어진다. 그래서 지금 운영 조건에서는 나지 않는다. 동시 사용이 늘거나 BE 상한을 올리면 나타난다.

### B-3 PostgreSQL UPSERT

DGX에 docker 권한과 `psycopg` 드라이버가 없어 재지 못했다. gosky는 sqlite라 운영 영향은 없다(W-4).

### B-4 날짜 경계

`today_kst()`는 `datetime.now(ZoneInfo("Asia/Seoul"))`라 서버 시간대와 상관없다. `TZ=America/Los_Angeles`에서 서버 날짜는 11/26이어도 기록은 11/27이다. 보관(오늘 포함 90일)과 조회 최대 범위(90일)가 같다.

### B-5 gosky 실제 표 (조회 API, 읽기 전용)

11/27 행 하나(`followup_checked` 2, 배포 확인용 시험분)뿐이다. 실사용 턴은 아직 없다.

## PHASE C 구조·계약

- `create_all(tables=[RewriteDaily.__table__])`는 그 표만 만들어 검수 표에 영향이 없다. 엔진이 둘(검수·횟수)로 늘어 같은 sqlite 파일에 연결 풀이 둘이다 - W-1의 원인이다.
- 테스트는 `reset_engine`으로 임시 DB를 쓰고, 다른 말벗 테스트가 `_apply_followup_fixes`를 부를 때는 공용 테스트 DB에 개수가 쌓인다. 개수만이라 다른 테스트 결과에는 영향이 없다.
- 팀 문서·운영 점검 문서·Notion이 #364를 맞게 적었다.

## PHASE D 테스트

| 변이 (DGX 사본, 끝난 뒤 md5로 원복 확인) | 결과 |
|---|---|
| UPSERT → 평범한 INSERT | 잡힘 (3 실패) |
| 보관 경계 +1 / -1 | 잡힘 / 잡힘 |
| 조회 범위 검사 빼기 | 잡힘 |
| KST → UTC | **살아남음** (W-3) |

gosky 전체 pytest는 배포 직후 결과(5,103 통과, 실패 1건은 `pgrep` 환경 문제)를 그대로 쓴다.

## 이슈

| 등급 | ID | 내용 | 위치 | 제안 |
|---|---|---|---|---|
| 🟡 | W-1 | 같은 sqlite 파일에 엔진 둘이 턴마다 쓰면서, 동시 12건 이상에서 검수 쓰기가 `database is locked`로 실패하고 그 턴 요청이 오류로 끝난다. #364 전에는 16건에서도 0건 | `rewrite_counts.py` `_set_engine`, `review_store.py` `add`, `companion_router.py:169` | ① sqlite면 두 엔진에 `PRAGMA journal_mode=WAL`·`busy_timeout`(예: 5초) ② 검수 쓰기도 실패하면 경고 로그만 남기고 대답은 내보내기 ③ (선택) 횟수 기록을 검수 저장소와 같은 엔진으로 묶기. 고친 뒤 같은 16스레드 측정으로 실패 0 확인 |
| 🟢 | W-2 | `followup_checked`는 고쳐 쓰기를 판단한 후보 수라, 재시도를 켜거나 다시 만들어진 대답에서는 최종 대답 수와 어긋난다 | `reply_service.py:934` | 엔드포인트 설명·운영 점검 문서에 정의를 적는다(기본값에서는 턴 수와 같음) |
| 🟢 | W-3 | 날짜를 KST로 정하는 것을 지키는 테스트가 없다 | 테스트 | `today_kst`가 UTC 자정 직전·직후에 KST 날짜를 내는 테스트 |
| 🟢 | W-4 | PostgreSQL에서 UPSERT 문을 확인하지 못했다 (운영은 sqlite) | `_UPSERT` | 검수 DB를 PostgreSQL로 옮길 때 일회용 컨테이너로 확인 |
| 🟢 | W-5 | 응답 경로 안의 동기 쓰기라, 경합하면 횟수 기록 하나가 대답을 최대 3초 늦출 수 있다 (16스레드) | `_apply_followup_fixes` | W-1의 WAL로 함께 줄어드는지 측정, 남으면 기록을 대답 뒤로 미룬다 |

## 수정 커밋 초안 (실행은 따로 "시작해")

```
fix: 말벗 검수 DB 동시 쓰기 잠금으로 턴이 실패하지 않게 (#364 점검 W-1·W-2·W-3)

- sqlite 검수 DB 엔진에 WAL·busy_timeout, 검수 쓰기 실패는 경고 로그만 남기고 대답은 그대로
- 고쳐 쓰기 횟수의 뜻(판단한 후보 수)을 엔드포인트 설명에 적고, KST 날짜 테스트 추가
```

## 조치 (11/27, AI #365 `38361c3`, gosky 반영)

| ID | 조치 |
|---|---|
| W-1 | `app/database/companion_base.create_review_engine`: sqlite 면 연결마다 `journal_mode=WAL`·`busy_timeout=5000`·`synchronous=NORMAL`. 검수 목록·고쳐 쓰기 횟수 두 엔진이 같이 쓴다. `_queue_for_review`는 검수 쓰기가 실패해도 경고 로그(클래스명)만 남기고 턴 응답을 내보낸다 |
| W-2 | 조회 엔드포인트 설명에 `followup_checked` = 고쳐 쓰기를 판단한 후보 수(기본값에서는 규칙을 통과한 꼬리질문 턴 수와 같음) |
| W-3 | KST 날짜 테스트(UTC 11/26 15:30 → 11/27) |
| W-4 | 그대로 (운영은 sqlite) - PostgreSQL 로 옮기면 WAL 설정은 적용되지 않고 UPSERT 를 따로 확인한다고 AI 팀 문서에 적음 |
| W-5 | W-1과 함께 줄었다 (아래 표) |

| 동시 쓰기 (DGX, 3회) | 검수 실패 전 → 후 | 한 턴 쓰기 p95 | 최대 |
|---|---|---|---|
| 8 | 0 → 0 | 9~20ms | 74ms 이하 |
| 16 | 1~3건 → 0 | 19~23ms | 334ms 이하 (전 3.3초) |
| 24 | - → 0 | 54~57ms | 433ms 이하 |

- WAL 만 켰을 때는 쓰기마다 디스크 동기화를 기다려 p95가 65~436ms로 늘었다. 그래서 `synchronous=NORMAL`을 더했다(전원이 끊기는 순간 마지막 몇 건을 잃을 수 있으나 DB는 깨지지 않는다). 횟수 유실은 모든 경우 0이다.
- 테스트: WAL·잠금 대기·동기화 설정, 검수 쓰기 실패에도 턴 200, KST 날짜. DGX 말벗 테스트 1,204 통과(2 `av` 환경), gosky 전체 pytest 5,106 통과(1 `pgrep` 환경).
- gosky 재시작(사용자 확인): 재시작 전 `data/companion_review.db`를 sqlite backup API로 `companion_review.db.bak-20261127-wal`에 백업(**12/04 이후 삭제 가능**), 롤백 `sb-ai-rollback:20261127-review-wal`(이전 태그 삭제). health 200·어댑터 4·5xx 0, `journal_mode=wal`·`-wal`·`-shm` 생성 확인. 시험 턴 200, 횟수가 재시작 전 2에서 3으로 이어서 오름(재시작 뒤에도 남음 확인, 11/27 행의 `followup_checked` 3·`vague_ok` 1은 모두 시험분). 디스크 84%.
