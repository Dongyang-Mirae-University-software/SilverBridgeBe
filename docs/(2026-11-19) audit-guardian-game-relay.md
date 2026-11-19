# 기능 점검(B) - 보호자 게임 기록 중계 (BE #501) - 2026-11-19

- 대상: PR #501 (머지 `7ddfdddc`), `domain/game/**` + 함께 건드린 공용 코드(`ErrorCode` 3종·`ExternalUrlSchemeChecker.KEYS`·`SwaggerConfig` 태그·`application.yaml game.relay.*`)
- 근거 문서: `(2026-11-19) feature-guardian-game-relay.md`, `.claude/rules/policy-game.md`
- 범위 밖: AI 게임 경로 자체(AI 감사 M-7, 사용자 결정으로 제외), FE 전환(프롬프트 ②)
- **판정: PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 4 (🟡 M-1은 #501 이전부터 있던 문제, 해결은 AI 범위 필요)

## 1. 엔드포인트 × 역할

| 엔드포인트 | 비로그인 | WARD | ADMIN | GUARDIAN(연결 없음) | GUARDIAN(ACTIVE 연결) |
|---|---|---|---|---|---|
| `GET /api/guardian/game/progress?wardId=` | 401 | 403 | 403 | 403 `GAME_NOT_AUTHORIZED` | 200 |
| `GET /api/guardian/game/activity?wardId=&days=` | 401 | 403 | 403 | 403 `GAME_NOT_AUTHORIZED` | 200 |

- 근거: 클래스 `@PreAuthorize("hasRole('GUARDIAN')")` + `SecurityConfig anyRequest().authenticated()`, `GuardianGameControllerSecurityTest`.
- vkcs 실측(2026-11-19 23:2x): 관리자 403·없는 경로 404·토큰 없음 401. 보호자 200은 gosky 미배포로 못 함(L-2).

## 2. PHASE A - 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| IDOR | **PASS** | `requireConnected`: `UserIdFormat` 불일치 → DB 조회 없이 403(길이만 로깅), `isActiveConnection` false → 403 + `[IDOR-ATTEMPT]`. 두 경우 AI 미호출(`GuardianGameServiceTest`). `getMyWards` 미사용 |
| 게임 번호 위조 | **PASS** | 요청에 게임 번호 없음, `GameUserIds.of(wardId)`로 BE 계산 |
| 입력 검증 | PASS | `days` 1~366(밖이면 400, 연결·AI 조회 전), 숫자 아님·`wardId` 누락은 공용 처리기 400 |
| AI 키 | PASS | `X-API-Key` 헤더만, 리다이렉트 미추종(테스트), 키 미설정 시 AI 미호출 503, 로그·응답에 키 없음 |
| 외부 호출 시간 | PASS | 연결 3초·읽기 5초·호출 전체 8초(마감 시 끊음), 응답 256KB 상한 |
| 로그 원문 | PASS | 클래스명·상태·호출 이름만, `LogRawExceptionGuardTest`에 두 파일 등록 |
| 응답 노출 | PASS | 허용 목록 record - AI `state`(게임 내부 상태)·게임 번호 미포함 |
| 남용 | PASS | 사용자 속도 제한(분당 600) 적용 대상, 보호자 화면은 피보호자 1명당 2회 |
| 탈퇴 후 데이터 | 🟡 M-1 | 아래 |

## 3. PHASE B - 기능 정합성

- FE `toGameUserId`와 같은 번호: PASS (`GameUserIdsTest` - node로 얻은 FE 값 5개 + `-2^31`·0 경계).
- 응답 필드: FE `IWardGameSummary`·`IWardGameActivity`가 쓰는 필드 유지, `userId` → `wardId`로 바뀜(FE ② 반영 대상).
- 시각: AI 원문(오프셋 없는 UTC) 그대로 - FE #259 `parseServerTime`과 맞음. PASS.
- D-1 미적용은 사용자 결정(policy-game). 의도대로.

## 4. PHASE C - 구조·계약

- 트랜잭션: 서비스에 `@Transactional` 없음 - 연결 확인(readOnly)만 짧게 하고 AI 호출 중 DB 연결을 쥐지 않는다. PASS.
- 도메인 경계: `domain/game` → `connection` 서비스만 사용, `global`이 `game`을 import하지 않음. PASS.
- 오류 코드 형태: `<도메인>_NOT_AUTHORIZED` 403 + `[IDOR-ATTEMPT]`, 503 `<도메인>_UNAVAILABLE` - 기존 규칙과 같음. PASS.
- Swagger: 401·403·503(+activity 400) 명시, 태그 등록(`SwaggerTagSyncTest`). `wardId` 누락 400은 미기재(L-3).
- 설정: `game.relay.ai-base-url`이 `ExternalUrlSchemeChecker.KEYS`에 있음, vkcs 기동 때 평문 경고 없음.

## 5. PHASE D - 테스트

- 새 테스트 37건(서비스 15·클라이언트 9·번호 7·컨트롤러 6), 전체 2759건 통과.
- 빈 곳: L-3.

## 6. 이슈

### 🟡 M-1. 탈퇴한 피보호자의 게임 기록이 AI 서버에 영구히 남는다 (#501 이전부터)
- **현상**: 탈퇴 AFTER_COMMIT `AiMemberDataPurgeListener`는 AI 챗 기록만 삭제 요청한다. AI 서버에는 게임 기록(`game_progress`·`game_attempts`) 삭제 경로가 없고, 보관 기간 정리도 없다(D-2 보관 정책 목록에도 없음).
- **영향**: 탈퇴 뒤에도 플레이 활동(날짜·횟수·점수)이 남는다. 같은 회원 ID가 다시 발급되면(하드 삭제라 `existsById` 통과, 6자 영숫자라 확률은 매우 낮음) 새 사람의 보호자가 이전 사람의 기록을 보게 된다.
- **수정 방향**(이번 점검은 제안만): AI에 게임 번호 기준 삭제 API(키 필요) 추가 → BE `AiMemberDataPurgeListener`에 `games` 항목 추가(`GameUserIds.of(userId)`). AI 먼저 배포. 결정 필요: 보관 기간을 정할지(D-2에 추가).

### 🟢 L-1. 게임 번호 해시 충돌 (수용)
- 31비트로 접는 번호라 서로 다른 피보호자가 같은 번호가 될 수 있다(피보호자 1000명일 때 약 0.02%). 그러면 두 사람의 기록이 섞인다. FE 설계에서 온 한계로 policy-game ③에 적혀 있다 - AI `user_id`를 문자열로 바꾸면 해소.

### 🟢 L-2. 실서버 보호자 200 미확인
- gosky SSH 호스트 키 불일치(2026-11-19)로 gosky 배포·보호자 계정 확인을 못 했다. vkcs는 경로 배포만 확인. 해소 뒤 progress 200·미연결 403 실측.

### 🟢 L-3. 오류 응답 형태를 HTTP 테스트로 고정하지 않음
- `GuardianGameControllerHttpTest`는 standalone이라 공용 예외 처리기를 거치지 않는다 - 403·503·400 본문은 다른 도메인과 같은 공용 처리라 위험은 낮다. Swagger에 `wardId` 누락 400 미기재.

### 🟢 L-4. AI 게임 무인증 경로와 번호 공식 (참고)
- 피보호자 iframe 경로(`/embed`·`/state`·`/answer`·`/reset`)는 키 없이 번호만으로 동작한다(AI 감사 M-7, 담당 밖 제외). 번호 공식은 FE 코드에 이미 공개돼 있어 이번 중계로 새로 드러난 것은 없다. BE 중계는 키 경로(`/progress`·`/activity`)만 쓴다.

## 7. 수정용 커밋 메시지 초안 (M-1, AI 선행 후)

```
feat: 탈퇴 시 AI 게임 기록 삭제 요청 추가 (게임 중계 점검 M-1)
```
