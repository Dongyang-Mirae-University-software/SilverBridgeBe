# 기능 점검(B) - FE #293 보호자 게임 관리 화면 BE 중계 전환 (2026-11-20)

- 대상: FE `709d29b`(머지 `7c47b84`) - 보호자 `/guardian/game`이 `/api/streams` 프록시(GET 405) 대신 BE `GET /api/guardian/game/progress·activity?wardId=`(BE #501)를 부른다. 보호자 쪽 `toGameUserId` 제거, 403·503 문구 분리.
- 기준: FE `origin/develop` `7c47b84` = gosky FE dev 반영본, BE `dev` `67ab069d`(게임 묶음 BE #501·#502, AI #323 gosky 반영 끝). 템플릿 B, 코드 수정 없음.
- 방법: 코드 읽기 + BE 계약 대조 + 바꿔 보기 7건(임시 worktree, 커밋 안 함) + gosky 실측(시험 보호자, 읽기만, 375px·1280px)
- 근거: `docs/(2026-11-19) feature-guardian-game-relay.md`, `.claude/rules/policy-game.md`

## 종합 판정

**PASS - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 3 (참고 1).** policy-game ①(프록시를 다시 열지 않음)·③(게임 번호는 서버가 계산)을 FE가 지키고, 새 테스트가 경로·파라미터·오류 분기·새로고침 키의 회귀를 모두 잡는다. 🟢는 모두 #293 이전부터 있던 동작이거나 #293과 무관하다.

## PHASE -1 환경

| 항목 | 결과 |
|---|---|
| FE develop · gosky FE dev | 둘 다 `7c47b84` |
| 단위 테스트(#293 PR 시점) | 80파일·731건 통과, tsc·eslint 0, `next build` 통과 |
| 전체 브라우저 테스트(develop `7c47b84`) | 768건 중 767 통과 - 실패 1건 `guardian/emotion/page.browser.test.tsx`는 실행 중 `@stomp/stompjs` 재묶음(reloading) 때문, 단독 재실행 통과(L-3) |
| BE 게임 ErrorCode | `GAME_NOT_AUTHORIZED` 403 · `GAME_INVALID_DAYS` 400 · `GAME_UNAVAILABLE` 503 |

## 호출 × 역할

| 화면 | FE 함수 | BE 경로 | 역할 | 인가 |
|---|---|---|---|---|
| `/guardian/game` | `getWardGameSummary(wardId)` | `GET /api/guardian/game/progress?wardId=` | GUARDIAN | ACTIVE 연결만, 아니면 403 |
| `/guardian/game` | `getWardGameActivity(wardId)` | `GET /api/guardian/game/activity?wardId=`(days 생략 = 182) | GUARDIAN | 같음 |
| `/ward/game`(범위 밖·무변경) | `toGameUserId` → AI `/games/embed` iframe | - | WARD | AI 직접(policy-game 범위 밖) |

## PHASE A 보안·인가

| 확인 | 결과 |
|---|---|
| policy-game ① 프록시 | PASS - `api/streams/[...path]/route.ts`는 `POST`만 export, 허용 경로는 `v1/stream-sessions`(+ frame·stop)뿐. 게임 경로·GET 없음 |
| policy-game ③ 게임 번호 | PASS - 보호자 코드에 `toGameUserId`·`userId` 파라미터 없음(`GuardianLayout`의 `userId`는 실시간 WS용, 무관). `streamClient` 사용처는 피보호자 송출 2곳뿐 |
| wardId 전달 | PASS - axios `params`로 인코딩, 경로 조립 없음 |
| 남의 wardId | PASS - gosky API 403 `GAME_NOT_AUTHORIZED`(본문에 소유자·내용 없음), 화면은 고정 문구만 |
| 로그·PII | PASS - 게임 화면·API 모듈에 console·reportError 없음, 문구에 wardId·응답 원문 없음 |
| 피보호자 iframe | PASS - `WardGameContent`·`utils/game/userId.ts` 무변경, 브라우저 테스트 2건 통과 |

## PHASE B 기능

| 확인 | 결과 |
|---|---|
| 403 / 503 / 기타 / 200인데 data null | PASS - 각각 연결 확인 안내 / 잠시 후 안내 / 기존 문구 / 기존 문구(오류 아님이라 `role=alert` 없음) |
| 피보호자 여러 명 중 일부 실패 | PASS - 카드마다 따로 조회·표시 |
| 요약 성공·활동 실패 | ⚠️ 활동 오류를 보지 않아 히트맵이 "0일·0문제"로 보인다 - L-2 |
| 새로고침 | PASS - 접두어 `['guardian-ward-game']` 하나로 요약·활동 모두 무효화(M7이 고정) |
| 연결 해제·재연결 | PASS - 카드는 ACTIVE 연결 목록 기준, 키에 wardId가 있어 다른 피보호자 캐시와 섞이지 않음, 요약은 `refetchOnMount: 'always'` |
| access 만료 | PASS - `apiClient` 401 → 재발급 → 재시도 경로를 그대로 탄다(전엔 `streamClient` 자체 재시도) |
| days·히트맵 | PASS - BE 기본 182일 = 히트맵 26주 |
| `lastPlayedAt` | PASS - `parseServerTime`(UTC) 유지, BE도 원문 그대로 |

## PHASE C 계약 (BE record ↔ FE `interface/game.ts`)

| 필드 | BE | FE | 판정 |
|---|---|---|---|
| `wardId` | String | string | 일치 |
| `totalScore`·`totalAttempts` | long(기본 0) | number | 일치 |
| `lastPlayedAt` | String(null 가능) | string \| null | 일치 |
| `games[].progress` | null 가능 | `IGameProgress \| null` | 일치 |
| `game.totalStages`·`title`·`themeColor` | Integer·String(AI가 빠뜨리면 null) | number·string(필수) | 🟢 L-1 |
| `progress.currentStageNo`·`score`·`attempts`·`cleared` | Integer·Long·Boolean(null 가능) | 필수 | 🟢 L-1 |
| `activity[]` | `{date, attempts, correct, score}` long | number | 일치 |
| `userId`·`state` | 없음 | 없음 | 일치 |

- 오류 코드 이름·상태코드 ↔ FE 분기: 일치. `GAME_INVALID_DAYS`는 FE가 days를 보내지 않아 분기 불요.
- `gameErrorMessage`는 `'use client'` 컴포넌트 밖 별도 파일, 쿼리 옵션(`retry: false` 등)은 전환 전과 같다.

## PHASE D 테스트 - 바꿔 보기

| # | 일부러 망가뜨린 것 | 잡은 테스트 |
|---|---|---|
| 기준 | 변경 없음 | 단위 7 · 브라우저 4 통과 |
| M1 | 요약 경로를 옛 AI 경로로 | 단위 1 실패 |
| M2 | `wardId` 대신 숫자 `userId` | 단위 1 실패 |
| M3 | 403 분기 삭제 | 단위 1 · 브라우저 1 실패 |
| M4 | 503 분기 삭제 | 단위 1 · 브라우저 1 실패 |
| M5 | 화면이 다른 ID(`ward.id`) 전달 | 브라우저 1 실패 |
| M6 | 카드에 오류 전달 끊기 | 브라우저 3 실패 |
| M7 | 활동 키를 새로고침 접두어 밖으로 | 단위 1 실패 |

7/7 검출. 빠진 케이스: 요약 성공·활동 실패 조합(L-2 동작 자체가 미정이라 테스트 없음).

## PHASE E 실서버 (gosky dev, 시험 보호자, 읽기만)

- 1280px·375px 모두 오류 문구 없음, 피보호자 2명 카드·게임 4종(진행 막대 8개), 가로 넘침 0, 페이지 오류 0.
- 네트워크: `/api/guardian/game/progress`·`activity` 200 4건만, `/api/streams` 호출 없음.
- API: 연결 없는 wardId 403 `GAME_NOT_AUTHORIZED`(화면용 연결 데이터는 바꾸지 않음). `role=alert` 1개는 Next `__next-route-announcer__`(빈 텍스트)로 카드와 무관.
- BE #501 점검 L-2(실서버 보호자 200 미확인)도 이 실측으로 확인됐다.

## 잔여 (🟢)

- **L-1 계약상 null 가능 필드를 FE가 필수로 둔다**: BE는 AI가 필드를 빠뜨리면 `totalStages`·`currentStageNo` 등을 null로 싣는다. 그러면 "NaN / 10 문제"·"/ 문제"처럼 보일 수 있다. 지금 AI 응답에는 다 있고 #293 전(AI 직접)도 같았다. 고치려면 FE 타입을 `| null`로 두고 `?? 0`, 또는 BE에서 기본값.
- **L-2 활동 조회 실패가 "활동 없음"처럼 보인다**: `activityResults[i].error`를 보지 않아, 요약은 되고 활동만 503이면 히트맵이 0일·0문제로 그려진다. 같은 AI를 부르므로 둘 중 하나만 실패하는 경우는 드물고 #293 전에도 같았다. 새로고침 버튼의 돌아감 표시도 요약만 본다. 고치려면 활동 오류 시 히트맵 자리에 짧은 안내.
- **L-3 브라우저 테스트 간헐 실패(#293 무관)**: `@stomp/stompjs`가 `vitest.browser.config.ts`의 `optimizeDeps.include`에 없어, 전체 실행 중 재묶음이 일어나면 그때 돌던 파일이 "Invalid hook call"로 실패한다. 한 줄 추가로 막힌다.

## 참고 (#293 무관, 기존 화면)

- **R-1 375px에서 히트맵 최근 주가 화면 밖**: `ActivityHeatmap`은 `overflow-x: auto`이고 처음 위치가 왼쪽(5월)이라, 휴대폰에서는 가장 최근인 10~11월을 보려면 옆으로 밀어야 한다. 최근 쪽으로 처음 스크롤해 두면 보호자가 바로 본다(최초 커밋 `0ca782b` 이후 그대로).

## 수정 제안 (실행은 승인 후)

L-1·L-2·L-3·R-1은 모두 FE 작은 수정이라 한 PR로 묶을 수 있다: `fix/guardian-game-audit-followup` - 게임 타입 null 허용 + 표시 기본값, 활동 오류 안내(브라우저 테스트 1건), `optimizeDeps.include`에 `@stomp/stompjs`, 히트맵 처음 위치를 오른쪽 끝으로. 커밋 초안 `fix: 보호자 게임 화면 점검 후속 - 활동 조회 실패 안내·null 필드 기본값·히트맵 최근 주 먼저`.

## 조치 (2026-11-20, 사용자 "전부 반영")

| 항목 | 조치 | PR |
|---|---|---|
| L-1 | 게임 타입의 null 가능 필드를 `\| null`로, 화면 기본값(문제 수 0·제목 '게임'·색은 CSS 기본 `--sb-brand`) | FE #295 `a088ad7` |
| L-2 | 요약은 되고 활동만 실패하면 히트맵 대신 "최근 활동 기록을 불러오지 못했습니다. 잠시 후 새로고침해 주세요." (`role=alert`), 새로고침 표시가 활동 조회도 본다 | FE #295 |
| L-3 | `vitest.browser.config.ts` `optimizeDeps.include`에 `@stomp/stompjs` - 전체 브라우저 71파일·773건 통과, 재묶음 0회 | FE #295 |
| R-1 | 좁은 화면에서 히트맵을 처음부터 오른쪽 끝(최근 주)으로 스크롤 | FE #295 |
| R-1 후속 | gosky 375px 실측에서 요일 열(월·수·금)이 함께 밀려 사라진 것을 찾아 요일 열을 `position: sticky`로 고정 | FE #296 `e483546` |

- 바꿔 보기: R-1·L-2·L-1(`totalStages`·`title` 기본값)·요일 sticky를 되돌리면 각각 테스트가 실패한다. 처음 쓴 L-1 테스트는 `Math.max(0, …)`가 null 계산을 0으로 막아 되돌림을 못 잡아서, 단계 수만 있는 게임(`0 / 10 문제`)과 기본 제목 확인을 더했다.
- 검증: tsc·eslint 0, 단위 80파일·712건(#293 때 731건과의 차이는 그 사이 머지된 FE #294의 `displayName.test.ts` 정리), 브라우저 71파일·773건, `next build` 통과.
- gosky FE dev `e4835463` 반영(소스 마운트 pull, 재시작 없음). 시험 보호자 실측: 375px에서 히트맵 2개 모두 오른쪽 끝(118/118px)·요일 열 왼쪽 고정, 1280px는 스크롤 없음, 오류 문구·NaN·경고·가로 넘침·페이지 오류 0, 요청은 `/api/guardian/game/*` 200만.

