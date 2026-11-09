# 관리자 AI 통계 - 라우터 실패·상태 칸 추가 (2026-11-09)

> 근거: AI #188(`8cb86c9`, gosky AI `eed8971` 2026-11-09 00:22 반영) `GET /api/v1/chat/stats` 새 칸.
> 이전 기능 문서: `docs/(2026-10-28) feature-admin-ai-chat-stats.md`. 분석 4-55. 정책: `.claude/rules/policy-admin.md` "관리자 AI 통계".

## 범위

- `GET /api/admin/ai-stats/chat` 응답(`AdminAiChatStatsResponse`)에 칸 3개를 더한다. 관리자 FE 화면은 FE 담당자 몫이다(이 작업에서 만들지 않음).
- 목적: Anthropic 키가 마지막 예산이라 크레딧 소진·라우터 장애를 관리자 화면에서 바로 알 수 있게 한다.
- 마이그레이션 없음. AI·FE 저장소는 바꾸지 않았다.

## 새 칸

| 칸 | 형태 | 뜻 |
|---|---|---|
| `routerFailures` | `Long` 또는 null | 기간 안 라우터 실패 턴 수(키워드 경로로 답함) |
| `routerFailuresByKind` | `{credit, auth, timeout, other}` 또는 null | 실패 종류별 수 |
| `routerStatus` | `{state, recentFailures, windowSec}` 또는 null | **지금** 라우터 상태(기간과 무관, AI 서버 프로세스 기준, AI 재시작 시 초기화) |

`state` 값: `ok` / `failing`(최근 창 안에 실패 있음) / `credit_exhausted`(크레딧 소진, 라우터가 다시 성공하면 풀림) / `off`(키 없음, `recentFailures` 0).

```json
"routerFailures": 3,
"routerFailuresByKind": {"credit": 2, "auth": 0, "timeout": 1, "other": 0},
"routerStatus": {"state": "credit_exhausted", "recentFailures": 2, "windowSec": 600}
```

## 결정 (2026-11-09 사용자 승인, 모두 권장안)

1. **옛 AI(칸 없음)**: 그 칸만 null, 나머지 집계는 그대로 낸다(응답 전체 503 아님). 정책 ② "모르면 0이 아니다".
2. **형식 이상**: `routerStatus`는 state가 4값 밖이거나 개수가 0 이상 정수가 아니거나 `windowSec < 1`이면 `routerStatus`만 null. `routerFailures`도 0 이상 정수가 아니면 null. `routerFailuresByKind`는 4칸 중 하나라도 어긋나면 묶음째 null(일부만 0으로 메우지 않음). 형식 이상일 때 `[ADMIN-AI-STATS] 선택 칸 형식 이상` WARN에 **칸 이름만** 남긴다(값·원문 없음). 기존 칸의 검사와 503은 그대로다.
3. **캐시**: `routerStatus`도 같은 기간 60초 캐시에 함께 둔다 - 최대 60초 늦을 수 있다(AI 보호 규칙 ③).
4. **`routerFailuresByKind`는 4칸 고정 record**(허용 목록 유지, Map 아님). AI가 종류를 더해도 자동으로 나가지 않는다.
5. **#188 배포 전 기간**: AI 집계 근거(`upstreamMeta.routerFailed`)는 #188 배포 뒤 기록에만 있어 그 전 기간은 0으로 온다. BE는 값을 바꾸지 않고(날짜를 코드에 박지 않음) Swagger와 FE 전달 사항에 "2026-11-09 AI 배포 뒤 기록부터 집계"로 명시한다.

## 바뀌지 않는 것

- 기존 칸·검사(`daily` 날짜 순서·`generatedAt` +09:00·필수 개수 칸)·503/504 규칙·속도 제한(분 30·시간 600)·캐시 TTL 60초.
- AI 호출 경로(`AiChatClient.stats`), 다른 관리자 API.
- 원인 문구·사용자 정보 칸은 추가하지 않았다(개수·상태 코드만). JSON 통째 전달 없음.

## 변경 파일

- `domain/admin/dto/AdminAiChatStatsResponse.java` - 칸 3개 + `RouterFailuresByKind`·`RouterStatus` record, Swagger 설명
- `domain/admin/service/AdminAiChatStatsService.java` - `optionalCount`·`routerFailuresByKind`·`routerStatus`(선택 칸 매핑), `ROUTER_STATES`
- `AdminAiChatStatsServiceTest` - 정상 매핑, state 4값, 옛 AI(칸 없음), routerStatus 형식 이상 8종, byKind 일부 누락·이상 5종, routerFailures 이상 4종, 기존 칸 503 유지, 캐시 적중 때 같은 상태, 허용 목록 밖 칸 미노출
- `AdminAiStatsControllerHttpTest` - 새 칸 직렬화, 모를 때 null 칸

## 검증

- 관련 테스트: `AdminAiChatStatsServiceTest` 58건 · `AdminAiStatsControllerHttpTest` 3건 통과.
- `./gradlew build`: 단위 테스트 2401건 실패 0(건너뜀 19).
- 실서버 확인 결과는 아래 "배포·실서버 확인"에 적는다.

## 배포·실서버 확인

- PR #450 머지 `8c68bd0` (2026-11-09).
- vkcs: CD 성공(통합 테스트 단계 실행·통과), `dmu-dev-api` healthy, AI WS 연결, 기동 뒤 ERROR 0.
- gosky(dev만): 수동 배포, HEAD `1504101`(`8c68bd0` 포함), `dmu-dev-api` healthy, AI WS 연결, ERROR 0. prod·AI 서버는 건드리지 않음.
- 실서버 1회 확인(관리자 시험 계정, 2026-11-09 하루): 200, `routerFailures`·`routerFailuresByKind`·`routerStatus` 모두 있음(null 아님), `routerStatus` 하위 칸은 `state`·`recentFailures`·`windowSec`만. 값은 기록하지 않음.

## 기능 점검 (템플릿 B, 2026-11-09) - 조건부 PASS → 🟢4건 전부 조치

| ID | 내용 | 조치 |
|---|---|---|
| L-1 | `AdminAiChatStatsService`가 `LogRawExceptionGuardTest` 목록에 없었다 | 목록에 추가 |
| L-2 | OpenAPI 3.1에서 null 가능한 객체 칸이 `type: "null"` + `$ref`("null이면서 객체" 모순)로 나갔다 - 이 칸 2개와 기존 6곳(`AdminHospitalItem.status`·`AnomalyDetection.latest`·`Medication.mostUrgent`·`Sos.latest`·`CameraLiveStatusResponse.analysis`·`AdminUserListItem.firstConnection`) | `global/config/NullableRefSchemaCustomizer`가 컴포넌트 속성 중 `$ref` + null 표시를 `anyOf: [{$ref}, {type: null}]`로 바꾼다(설명 유지, 응답 JSON 무변경 - 문서만) |
| L-3 | 값끼리 맞는지 검사하지 않았다 | 종류별 합 ≠ `routerFailures`면 둘 다 null. `state`-`recentFailures` 모순(ok·off인데 1 이상, failing인데 0)이면 `routerStatus` null. `credit_exhausted`는 0이어도 맞다(창 밖). 모두 칸 이름만 WARN |
| L-4 | 새 WARN 로그 내용을 고정하는 테스트가 없었다 | 로그를 붙잡아 칸 이름만 있고 AI가 준 값이 없음을 확인하는 테스트 추가 |

- 테스트: `AdminAiChatStatsServiceTest` 68 · `NullableRefSchemaCustomizerTest` 2(실제 DTO 주석을 springdoc 3.1 모델로 변환해 확인) · `LogRawExceptionGuardTest` 4 · 전체 단위 2413건 실패 0.
