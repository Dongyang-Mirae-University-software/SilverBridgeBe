# AI 챗 전체 집계 API 기능 점검 (2026-10-28)

- 대상: AI 서버 PR #84 `GET /api/v1/chat/stats` (구현 커밋 `47472a9`, 머지 `c147414`, gosky 배포 2026-10-28 10:25)
- 템플릿: 기능 점검(B). 점검만 했고 코드는 고치지 않았다.
- 방법: 코드 직접 확인 + 독립 검토(결론을 주지 않고 코드만 넘김) + gosky 실측(키 없는 호출, 운영 DB는 개수·인덱스만 조회, 원문 출력 없음)

## 점검 범위

| 파일 | 역할 |
|---|---|
| `app/services/chat_stats_service.py` | 기간 검증, KST 경계, 집계 |
| `app/routers/chat_router.py` (`/stats`) | 400 변환, 응답 |
| `app/main.py:139` | chat 라우터 전체에 `require_api_key` |
| `tests/test_chat_stats.py` | 7건 |
| 쓰기 경로 대조 | `chat_service.py`(저장 `context_payload`, `_fallback_reply`), `medical_llm_service.py`(`infer_risk`·`self_harm_response`), `reservation_confirm_flow.py:428`(`make_appointment`) |

## 엔드포인트 × 인가

| 경로 | 인가 | 결과 |
|---|---|---|
| `GET /api/v1/chat/stats` | `X-API-Key` (서버 간 호출 전용, BE가 관리자에게 중계) | PASS - 외부 도메인 `testai.gosky.kr`에서 키 없음·틀린 키 모두 401 실측 |

IDOR: 해당 없음(사용자별 자원이 아니라 전체 개수만). 응답·로그에 원문·이름·userId 없음 - PASS(테스트로 고정, `[CHAT-STATS]` 로그는 기간·행 수·ms만).

## 정합성 (저장값 ↔ 집계 정의)

| 항목 | 판정 | 근거 |
|---|---|---|
| KST 경계 | PASS | `created_at` = naive UTC(`utcnow`, 컨테이너 TZ=UTC), `[from 00:00 KST, to+1 00:00 KST)`를 UTC로 바꿔 자름. 경계 행 테스트 있음 |
| 31일 상한 | PASS | 31일 200 / 32일 400 |
| emergencyGuidance (`risk_level=high`) | PASS | high가 나오는 세 곳(MedGemma `infer_risk`·`_fallback_reply`·자해 고정 응답) 모두 응급(119) 안내를 함께 낸다. 자해 위기 응답도 여기 포함된다 |
| reservationCompleted | PASS | `tool_result/make_appointment`는 예약 서비스 2xx 생성 때 한 곳(`_after_created`)에서만 나온다. 실패는 `tool_error` |
| byRoute | PASS | 라우터·규칙·UI·키워드 경로 모두 세 값 중 하나, 바깥 예외만 `unknown` |
| byRole | 조건부 | 아래 L-2 |
| fallbackReplies | 조건부 | 아래 L-3 |
| 실측 대조 | PASS | 10월 전체 API 값이 DB를 직접 센 값과 모두 같다(577 / 7 / 300·39·238 / 302·234·41·0 / 36 / 42 / 0) |

## 이슈

### 🟡 M-1 기간 행 전체를 메모리로 읽고 `created_at` 인덱스가 없다
- 위치: `chat_stats_service.py` 조회부(`select(...).all()`), `app/models/chat_log.py`(`created_at` index 없음)
- 기간 안의 행을 `context_json`(예약 턴은 병원 목록 등으로 수 KB~수십 KB)까지 한 번에 읽고, 호출마다 `chat_logs` 전체를 순차 스캔한다. 속도 제한도 없다.
- 실패 시나리오: BE가 관리자 화면에서 이 API를 주기적으로 폴링하거나 상담이 월 1만 건 이상으로 늘면, 요청 하나가 수백 MB를 읽고 파싱하며 같은 프로세스의 챗·이상감지와 메모리를 다툰다.
- 지금 규모: 운영 577행·2MB(평균 2.2KB, 최대 22KB)라 영향 없음.
- 수정안: `created_at` 인덱스(시작 시 `CREATE INDEX IF NOT EXISTS`, `schema_upgrades.py` 방식) + 필요한 키만 읽기(`yield_per` 또는 Postgres JSON 연산자). BE 중계는 폴링하지 말고 화면 진입·기간 변경 때만 부르고, 짧게 캐시하는 것을 권한다.

### 🟢 L-1 극단 날짜에서 500
- `from=0001-01-01`은 KST→UTC 변환에서, `to=9999-12-31`은 `+1일`에서 `OverflowError` → 처리되지 않은 500(본문 노출은 없음). 재현 확인.
- 수정안: `parse_range`에서 `OverflowError`를 잡아 400, 또는 연도 범위 제한.

### 🟢 L-2 byRole은 클라이언트 값
- `userContext.role`은 FE가 보낸 값을 BE가 그대로 넘긴 것이다(BE `ChatRelayService`는 ward 키만 덮어씀). 위조·누락이 집계에 그대로 들어간다.
- 실측: `unknown` 238건 중 237건은 10/08~10/12 기록(대부분 `userContext`가 통째로 빔). 10/13 이후 FE는 `GUARDIAN`/`WARD`를 보낸다 - 옛 기록 때문이다.
- 수정안(BE 쪽): 보호자·피보호자 챗 컨트롤러가 이미 나뉘어 있으니 BE 중계가 `context.role`을 토큰 역할로 덮어쓴다. BE 집계 중계 작업에 함께 넣는다.

### 🟢 L-3 생성 실패 후 자해 고정 응답은 fallbackReplies에서 빠진다
- MedGemma 생성 실패(`chat_service.py:320`) 또는 바깥 예외(`:326`)에서 `_fallback_reply`가 자해 고정 응답을 내면 `engine=rule`이라 세지 않는다(응급 안내로는 센다).
- 정의 선택 문제다. "모델이 답을 못 만든 턴"을 정확히 세려면 저장 시 실패 표시(예: `upstreamMeta`에 생성 실패 플래그)가 필요하다 - 저장 형식 변경이라 별도 결정.

### 🟢 L-4 테스트가 운영 연결을 거치지 않는다
- 테스트는 자체 FastAPI 앱을 써서, 400 오류 위치를 `detail.errorCode`로 검사한다. 운영은 `main.py:125` 핸들러가 최상위 `errorCode`로 펼친다(BE 계약은 최상위 `errorCode` - 실측과 같음). 401도 테스트가 직접 붙인 키 검사 기준이라 `main.py:139` 연결이 빠져도 못 잡는다.
- 수정안: `app.main.app` 기준 테스트 1건(키 없음 401·400 최상위 `errorCode`).

### 🟢 L-5 경계·형식 케이스 미고정
- 연도 overflow, `context_json`이 dict가 아닌 JSON(`[]`), `userContext`가 dict가 아님, role 앞뒤 공백, 자해 응답(high + `engine=rule`)의 분류를 시험하지 않는다. 코드는 dict·공백을 처리하지만 테스트로 고정돼 있지 않다.

### 참고 (이슈 아님)
- 시험 호출과 실사용을 구분하지 못한다(10/09·10/12·10/13 하루 160~196건은 시험으로 보임). 기록에 구분 값이 없다. 관리자 화면 설명에 "시험 계정 상담 포함"을 적거나, 필요하면 BE가 시험 계정을 표시하는 방식을 따로 정한다.

## 종합 판정

**조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 5. 인가·원문 미노출·주요 카운트 정의는 맞고, 운영 숫자도 DB 직접 집계와 일치한다. M-1은 지금 규모에선 드러나지 않지만 BE 중계가 폴링 구조가 되기 전에 고치는 것을 권한다.

## 수정용 커밋 메시지 초안 (AI 저장소)

```
fix: 챗 집계 API의 created_at 인덱스·극단 날짜 400·운영 연결 테스트 보강

- chat_logs.created_at 인덱스를 시작 시 멱등 생성(기간 조회 순차 스캔 방지)
- 기간 행은 필요한 컬럼만 나눠 읽는다(yield_per)
- 연도 경계에서 OverflowError가 500이 되던 것을 400 CHAT_STATS_INVALID_RANGE로
- app.main 기준 테스트로 401·최상위 errorCode를 고정, dict 아닌 JSON·role 공백·자해 응답 분류 케이스 추가
```

## 후속 반영 (2026-10-28, 사용자 "전부 반영")

| 이슈 | 반영 | 위치 |
|---|---|---|
| 🟡 M-1 | `chat_logs.created_at` 인덱스(모델 `index=True` + 기동 시 `ensure_indexes` 멱등 `CREATE INDEX IF NOT EXISTS`), 기간 행은 500행씩 나눠 읽기(`yield_per`) | AI #87 |
| 🟢 L-1 | 0001/9999 날짜 `OverflowError` → 400 `CHAT_STATS_INVALID_RANGE` | AI #87 |
| 🟢 L-2 | BE 챗 중계가 `context.role`을 경로의 역할로 덮어씀(보호자 `GUARDIAN`·피보호자 `WARD`), 정책 문서 갱신 | BE `fix/chat-relay-role` |
| 🟢 L-3 | 생성 실패 두 경로가 기록 `upstreamMeta.generationFailed=true`를 남기고 집계가 센다(배포 이후 기록부터, `/chat` 응답 무변경) | AI #87 |
| 🟢 L-4 | 운영 예외 핸들러 규칙으로 최상위 `errorCode` 검사 + `main.py` 키 검사 연결·`ensure_indexes` 호출 고정 | AI #87 |
| 🟢 L-5 | dict 아닌 JSON·role 공백·모르는 경로·자해 응답 분류 테스트 | AI #87 |

- 검증: AI chat 관련 279건 + 운영 이미지 임시 컨테이너 전체 829건 중 828건 통과(실패 1건은 이미지에 `pgrep`이 없는 기존 환경 문제), 변이 6종 검출. BE chat 81건 통과, 역할 덮어쓰기 제거 변이 검출(4건 실패).
- 남는 한계: 2026-10-28 이전 기록의 `byRole.unknown`(옛 기록 237건)과 생성 실패 뒤 자해 응답(표시 없음)은 고칠 수 없다 - 새 기록부터 정확하다.

