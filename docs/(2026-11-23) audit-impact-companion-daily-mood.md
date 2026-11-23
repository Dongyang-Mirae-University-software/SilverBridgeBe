# 영향 범위 점검 (템플릿 C) - 말벗 하루 기분 = 그날 모든 턴의 다수결

- 날짜: 2026-11-23 / 기준 커밋: dev `d42d58f8` (BE #521) / 점검자: AI(Claude) - 점검만, 코드 수정 없음
- 변경점: 하루 기분이 "그날 마지막으로 판정된 대화의 기분" → "그날 마친 모든 대화의 판정된 턴 기분 중 가장 많이 나온 기분, 같으면 DEPRESSED > WORRY > CALM > JOY". 턴 기록이 없는 대화는 대화 기분 1표. 대화를 마칠 때마다 `CompanionMoodRecordedEvent` 발행(전에는 걱정·우울로 끝난 대화만).
- **불변식**: 하루 기분을 쓰는 모든 곳은 `CompanionTurnMoodReader` → `CompanionMoodDays.dailyMoods` 한 규칙을 쓰고, 대화 1건의 기분을 하루 기분처럼 쓰지 않는다. 이벤트가 늘어도 주의 알림은 피보호자·날짜당 1번이다. 사용자에게 보이는 설명은 새 규칙과 맞는다.

## PHASE A. 사용처 (domain/**·global/**·FE src)

| 구분 | 위치 | 쓰는 값 |
|---|---|---|
| 하루 기분 | `CompanionNoticeListener:75-95` | 피보호자·보호자별 연속 일수 → 주의 알림 |
| 하루 기분 | `CompanionGuardianService.week:86` | 보호자 최근 7일 |
| 하루 기분 | `CompanionGuardianService:196-199` | 보호자 today·days 주의 칸(attention) |
| 하루 기분 | `CompanionStatsService:89-99` | 관리자 "이어지는 어르신" |
| 대화 기분(의도) | `CompanionGuardianService:182-183`(current), `CompanionStatsService.distribution:129` | 지금 기분·관리자 기분 분포 = 마지막 마친 대화 |
| 대화 기분(대화 단위) | `CompanionGuardianService:119-126`, `CompanionService:667·681` | 대화 보기·시간대 칸 |
| 이벤트 | `CompanionSessionWriter.finish:127` → `CompanionNoticeListener` | 유일한 발행·구독 |
| 문서·문구 | `GuardianCompanionController:56`, `CompanionGuardianDtos:55`, `AdminAiStatsController:86`, `V66` 주석, FE `AdminAiStatsContent.tsx:125` | 규칙 설명 |

companion 도메인 밖(dashboard·admin 서비스·notification·global)에서 말벗 기분·턴 저장소를 쓰는 곳은 **0건**이다.

## PHASE B. 사용처별 판정

| 위치 | 판정 | 근거 |
|---|---|---|
| 주의 알림 `CompanionNoticeListener` | PASS | 턴 기분을 한 번 읽어 피보호자·보호자 계산에 같이 쓴다. 하루 1번은 `companion_notice_log` UNIQUE 선점이 그대로 막는다(이벤트가 대화마다 와도 두 번째부터 `DataIntegrityViolationException` → return). 기존 실 DB 테스트 `주의_알림은_하루_한_번` 통과(#521 통합). |
| D-1(연결 이후 대화만) | PASS | `startedSince`로 대화를 거른 뒤 그 대화들의 턴만 센다(맵은 피보호자 전체지만 조회 키가 걸러진 대화뿐). week·today·알림 모두 같은 순서. |
| 보호자 week·attention | PASS | `turnMoodReader.dailyMoods` 사용. 판단 불가만인 날은 키가 없어 연속을 끊는다(`unknownOnlyDayBreaks`). |
| 관리자 이어지는 어르신 | PASS | 같은 규칙, D-1 대상 아님(정책대로). |
| 지금 기분·기분 분포 | PASS(의도) | 정책 "지금 기분·관리자 기분 분포는 하루 기분과 다르다" 그대로 마지막 대화 기준. 규칙 변경 대상 아님. |
| 이벤트 발행 경로 | PASS | `finish`는 OPEN일 때만, 마감 스케줄러·"마치기" 모두 `CompanionService.finish` → `writer.finish`. LOST(`markLost`)는 이벤트 없음(전과 같음). |
| 조회 성능 | PASS(한계 기록) | `uq_companion_turn_session_turn (session_id, turn_no)`가 `session_id IN` 조회를 받친다. 관리자 집계는 30일 전체 대화 ID를 IN 하나로 넘긴다 - 아래 L-4. |
| **FE 관리자 AI 통계 안내** | **FAIL → M-1** | `AdminAiStatsContent.tsx:125` "하루 기분은 그날 마지막으로 판정된 대화의 기분입니다." - 화면에 보이는 문장이 새 규칙과 다르다. |
| **BE Swagger 설명** | **FAIL → L-1** | `GuardianCompanionController:56` week `mood: 그날 마지막으로 판정된 대화의 기분`, `CompanionGuardianDtos:55` `하루 기분(그날 마지막으로 판정된 대화)`. gosky Swagger는 공개라 FE가 이 설명을 보고 구현한다. |
| `AdminAiStatsController:86` moodDistribution "마지막 대화 기준" | PASS | 분포는 의도대로 마지막 대화 기준이라 맞는 설명. |
| `V66` 주석 | 해당없음 | 기존 마이그레이션 수정 금지(§6). 정책 문서가 기준. |

## 이슈

- 🟠 없음 / 🔴 없음
- 🟡 **M-1 FE 관리자 화면 안내 문장이 옛 규칙** - `AdminAiStatsContent.tsx:125`를 "하루 기분은 그날 모든 대화의 턴 기분 중 가장 많이 나온 기분입니다(같으면 걱정·우울 쪽)."으로 바꾼다. 같은 파일 브라우저 테스트에 이 문장 단언이 있으면 함께. (FE 저장소, 사용자에게 보이는 거짓 설명)
- 🟢 **L-1 BE Swagger 두 곳 옛 규칙** - `GuardianCompanionController:56`·`CompanionGuardianDtos:55` 설명 문구만 정정(응답 형태 불변).
- 🟢 **L-2 AI 재시작으로 LOST가 된 대화의 턴은 하루 기분에서 빠진다** - 턴 기분은 저장돼 있지만 `findFinishedSince(FINISHED)`만 읽는다. 정책 문구("마친 모든 대화")와 같고, 전 규칙에서도 LOST는 빠졌다. **수용 권고**(LOST 대화는 끝맺지 못해 대화 기분도 없다). 포함하려면 사용자 결정 필요.
- 🟢 **L-3 #335 전(2026-11-21 18:4x 전) 슬픔·화남 턴은 null이라 그 턴의 표가 없다** - 턴이 하나라도 있으면 대화 기분으로 대체하지 않으므로, 그 기간 대화는 걱정 표가 줄 수 있다. 30일 조회 창이라 2026-12-21경 자연 소멸, 옮기지 않음(정책과 같음). **수용**.
- 🟢 **L-4 관리자 집계 IN 목록 크기** - 30일 전체 대화 ID를 한 번에 넘긴다. PostgreSQL JDBC 바인드 상한(32,767)은 하루 5대화 기준 피보호자 약 218명에서 닿는다. 지금 규모(학생 프로젝트)에서는 문제없음 - 이용자가 늘면 1,000개씩 나눠 읽는다. **수용·기록**.
- 🟢 **L-5 새 다수결 경로의 실 DB 테스트 없음** - 통합 테스트는 새 JPQL을 실행하지만(턴 없는 대화 → 빈 결과), `companion_turn` 행으로 하루 기분이 바뀌는 경로(평온으로 끝난 대화인데 그날 턴 다수가 걱정 → 3일째 주의 알림)는 단위 테스트(목)로만 고정돼 있다.

## PHASE C. 테스트로 고정할 것 (제안)

1. `CompanionFullIntegrationTest.평온으로_끝나도_턴_다수가_걱정이면_주의_알림` - 지난 2일 걱정 대화 + 오늘 `mood=CALM`으로 끝나지만 `companion_turn` WORRY 3·CALM 1인 대화 → `COMPANION_NOTICE` 1건, `week()` 오늘 = WORRY (L-5).
2. `CompanionTurnRepositoryIntegrationTest.findMoodsBySessionIdIn_null_제외` - null 턴 제외·다른 대화 턴 미포함.
3. (선택) L-1을 고칠 때 `CompanionGuardianApiDocTest` 형태로 "마지막으로 판정된" 문구가 week 설명에 없음을 단언.

## 종합 판정

**조건부 PASS** - 규칙을 쓰는 BE 코드 경로(알림·보호자·관리자)는 모두 새 규칙 한 곳을 쓰고 D-1·하루 1번이 지켜진다. 사용자에게 보이는 설명 2곳(FE 관리자 안내 M-1, BE Swagger L-1)이 옛 규칙이라 정정이 필요하다.

### 수정용 커밋 메시지 초안
- BE: `docs: 말벗 하루 기분 Swagger 설명을 그날 턴 다수결로 정정` (L-1, 코드 파일이라 PR 경유) + `test: 말벗 하루 기분 턴 다수결 주의 알림 실 DB 검증` (L-5)
- FE: `fix: 관리자 AI 통계 하루 기분 안내를 그날 턴 다수결로` (M-1)
