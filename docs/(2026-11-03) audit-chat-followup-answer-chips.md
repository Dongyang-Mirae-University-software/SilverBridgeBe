# 기능 점검 - AI 챗봇 되묻기 답 칩 (2026-11-03, 템플릿 B)

- 대상: AI #160 `4c1a89e` · BE #435 `50253c4` · FE #210 `772fdd3` (기능 설명은 분석 4-47, `policy-chat.md` "되묻기 답 칩")
- 범위: 칩 흐름(AI 생성 → BE 정리 → FE 표시 → 다음 턴 이어 붙이기·판정) + 건드린 공용 코드 사용처(AI `should_suggest`·`suggested_questions` 호출부, BE `SuggestedQuestions.sanitize` 호출부, FE `latestSuggestions`·`wardPrompts`·`guardianPrompts`)
- 환경(점검 시작 시): AI·BE·FE 배포 코드 = gosky. pytest 1377 / BE 단위 2209 / FE 단위 454·브라우저 369 통과
- **종합: 🔴 0 · 🟠 0 · 🟡 2 · 🟢 3 → 전부 수정·배포** (AI #163 `e52310f` · BE #436 `b072a24` · FE #211 `53c3bfc`)

## 점검 결과

| 항목 | 판정 | 근거 |
|---|---|---|
| A. 칩이 기록·로그에 남는가 | PASS | AI 챗 기록 미저장(테스트 고정), AI 로그 `[CHAT-SUGGEST] source=answer count=N`만, BE `ALLOWED_FIELDS` 밖, `ChatRelayLogGuardTest` 통과 |
| A. 모르는 `suggestedKind` 값 | PASS | BE가 `answer`·칩 있음일 때만 내림, FE는 코드 값만 비교·텍스트로만 그림 |
| A. 엔드포인트 × 역할 | PASS(변경 없음) | `POST /api/guardian/chat`(GUARDIAN)·`/api/ward/chat`(WARD), 같은 `ChatRelayService.relay` |
| B. 칩이 나가면 안 되는 턴 | PASS | `followupAsked`를 세우는 곳은 되묻기 답 하나. 위험 신호 되묻기·답에 119·응급·경고 박스 답은 막힘(두 겹: 표시 + 119 문장) |
| B. 대리 말투 | PASS | 칩과 되묻기 질문이 같은 `followupProxy` 값 |
| B. 묶음 분류(짧은 증상 61문장) | 🟡·🟢 | M-2·L-1 |
| B. FE 보호자 화면 | 🟡 | M-1 |
| C. 계약·단일 출처·문구 비교 | PASS(문서 1건) | 칩 문장은 AI 고정 표 한 곳, FE·BE 문구 비교 없음 / L-2 |
| D. 테스트 | 보강 | L-3 |

## 이슈와 조치

| 번호 | 내용 | 조치 |
|---|---|---|
| 🟡 M-1 (FE) | 보호자 화면이 피보호자 목록을 받기 전(`selectedWard` null)에 칩을 누르면 `wardId` 없이 본인 상담으로 가고, 다음 턴(`wardId` 있음)에는 AI가 다른 피보호자 대화로 보고 되묻기 이어 붙이기를 하지 않아 칩 문장만 상담됐다(gosky 화면 확인 중 1회 실제 발생) | FE #211: 목록을 받는 동안 칩·예약 칩·입력·보내기 잠금 + "피보호자 정보를 불러오는 중이에요". 브라우저 테스트 2건, 결함 주입 2종 검출 |
| 🟡 M-2 (AI) | 증상이 아닌 말("속상해요"·"열받아요"·"배고파요"·"배불러요")이 낱말 "속·열·배"로 되묻기 대상이 되어 "먹고 나면 더 아파요" 같은 칩이 붙었다(되묻기 조건은 기존, 칩으로 더 드러남) | AI #163: 감정 표현은 `_FOLLOWUP_DISTRESS`, 배고픔·배부름은 `_FOLLOWUP_NOT_SYMPTOM`으로 되묻지 않음(일반 상담 경로). 옛/새 재생: 시험지 209문장 판정·칩 차이 0, 바뀐 것은 이 표현뿐 |
| 🟢 L-1 (AI) | "손이 아파요"·"발이 아파요"가 기본 묶음, "목이 뻐근해요"가 기침 묶음("가래도 나와요") | AI #163: 손·발 통증·손발가락·목 뻐근함·결림은 관절 묶음(칩 표만, 판정 무관) |
| 🟢 L-2 (BE 문서) | `(2026-10-30) feature-chat-suggested-questions.md`가 "0~3개·되묻기는 빈 목록" | BE #436: 후속 메모 |
| 🟢 L-3 (테스트) | BE 중계 경로의 `suggestedKind` 통과·삭제, AI 응급 다음 턴 칩 없음이 직접 고정돼 있지 않았다 | BE #436 `ChatRelayServiceTest` 1건(결함 주입 검출), AI #163 1건 |

기록만(수정 안 함): 칩 다음 턴에 라우터(LLM)가 응급으로 보면 이어 붙이지 않는다(타이핑한 답과 같음, 실측 0) / 종료어 `됐어요`는 별도 작업(4-47) / FE `LoginPage.browser.test.tsx`는 전체 실행 때만 간헐 실패(단독 10/10, 이번 범위 밖).

## 수정 검증

- AI: pytest 1398 통과(새 21), 결함 주입 4종 검출(응급 다음 턴 주입은 기존 되묻기 테스트가 검출).
- BE: `ChatRelayServiceTest` 19 통과, `./gradlew build` 통과, CD `b072a24`.
- FE: 챗봇 브라우저 44·단위 454 통과, tsc·build 통과, lint 오류 0.
- gosky: AI 재시작 2026-11-03 15:58(사용자 실행, `e52310f`), FE dev `53c3bfc`(`/login` 200). BE는 운영 코드 무변경이라 gosky 재빌드 없음.
- gosky v7 142 × 1(`--replay-history`): **132/132**, 응급 37·37·37, 일반 증상 high 0, 보호자 대리 3/3, 되묻기 대상·이어 답하기·반례 10/10, 일반 답 119 2/77(기록용). AI 결과 `docs/audits/2026-11-03-followup-answer-chips-audit.md`(#166).
