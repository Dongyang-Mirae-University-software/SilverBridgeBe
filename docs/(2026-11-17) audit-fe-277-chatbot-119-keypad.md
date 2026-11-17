# 기능 점검(B) - FE #277·#278 챗봇 119 키패드 (2026-11-17)

- 대상: FE `a438a9f`(#277 챗봇 119 버튼 → 앱 안 키패드, 통화 버튼 = SOS `CHATBOT_119`) · `470f6e4`(#278 이력 모르는 경로 값 대체 색)
- 기준: FE `origin/develop` `470f6e4` / BE `#498` / AI `origin/main` 코드 읽기. 실서버 SOS 발생은 하지 않았다(실제 푸시가 시험 보호자에게 나감 - 아래 L-3).
- 템플릿 B, 코드 수정 없음.

## 종합 판정

**조건부 PASS - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 3.** 발신 차단(목표 1)은 확인됐다: 챗봇·말풍선·키패드 소스에 `tel:`·`sms:`·`callto:`·intent 스킴 0, 가드 테스트 2곳. 통화 버튼의 SOS 전송(잠금·재시도·성공 뒤 재전송 없음)·보호자 이력 표시도 맞다.
그러나 **위기 판정이 실제 AI 응답 모양과 달라 자해 답에 109 안내가 안 뜬다(H-1)**, 보호자가 없는 피보호자에게 "보호자에게 알렸어요"라고 안내한다(M-1).

## A. 보안·인가

| 확인 | 결과 |
|---|---|
| 실제 발신 경로 | PASS - `<a href="tel:119">` → `<button>`, 키패드는 기존 가드(`tel:`·링크·`window.location` 금지) 유지, `ChatbotContent.guard.test.ts` 신설 |
| SOS 요청 주체 | PASS - `isWardChat`(variant `ward`) 패널에서만 버튼. 보호자 챗봇은 버튼 없음(브라우저 테스트로 요청 0·`tel:` 0 고정). 보호자가 호출해도 BE `/api/ward/sos`는 WARD 전용 |
| 요청 본문 | PASS - `{triggerType:'CHATBOT_119'}`만. `calledGuardianId`·위치 없음 |
| 로그 | PASS - 실패는 기존 `useWardSosMutation` `reportNonApiError`(오류 객체, 사용자 입력 없음) |

## B. 기능 정합성

| 확인 | 결과 |
|---|---|
| 표시 조건 | PASS - 기존 `wardUrgent`(예약 패널 우선) 그대로 |
| 열기 ≠ 전송 | PASS - 키패드 여는 것만으로는 요청 없음, 통화 버튼에서만(BE 문서 `c922cd3a`로 계약 정정) |
| 연타·재시도 | PASS - ref 잠금 1건, 실패 후 재시도, 성공 뒤 같은 키패드 재전송 없음, 다시 열면 다시 보냄(이력 행은 매번 저장, 푸시는 BE 쿨다운 10초) |
| 오프라인 | PASS - `networkMode:'always'`로 바로 실패, 오프라인 문구 |
| 109 안내(위기) | **FAIL - H-1** |
| 보호자 없음 | **FAIL - M-1** |
| 글자 배율·색 | PASS - `--ward-font-scale`은 문서 루트, `ModalHost`는 앱 루트라 키패드 안 109 줄도 배율 적용, 흰 글자 상속 |
| 보호자 이력 | PASS - "챗봇 119" 필터·건수·항목 배지, 전체 = 세 탭 합(BE 영향 점검 M-1 해소), 모르는 값 대체 색(H-1 해소, 수정 전 코드에서 실패하는 테스트) |
| SOS 화면 회귀 | PASS - `onCall` 없으면 옛 동작(`DEMO_CALL_NOTICE`), WardSosContent 테스트 무변경 통과 |

## 이슈

- 🟠 **H-1 자해 고정 답에 109 안내가 뜨지 않는다 (판정 전제가 틀림)**
  - `isCrisisAnswer` = 응급 판정 && 경고 문장 없음. 근거로 삼은 `chatUrgency.ts` 주석("자해 고정 응답은 경고 없이 high 만 온다")이 낡았다 - AI `medical_llm_service.self_harm_response`는 2026-10-24(`f2c5117`)부터 `emergencyWarning: [_SELF_HARM_WARNING]`("…119에 전화하세요. 자살예방 상담전화 109는 24시간 받습니다.")을 싣는다. 그래서 109 줄은 위기 뒤 해제 턴(`layout.urgent`만)에서만 켜지고, 정작 첫 자해 답에서는 꺼진다.
  - 반대 방향: 규칙 폴백에서 `riskLevel high`인데 `intent`가 `emergency_guidance`가 아니면 경고가 비어(`chat_service.py` 폴백 `emergencyWarning … if intent == "emergency_guidance" else []`) 의료 답에 109 줄이 붙을 수 있다(드묾).
  - 테스트가 틀린 전제를 고정한다: `chatUrgency.test.ts`(`{riskLevel:'high'}` → 위기), 브라우저 "자해·위기 답(경고 없음, riskLevel high)".
  - FE에는 AI `meta`(`selfHarm`·`crisisFollowup`)가 오지 않아 칸으로 가를 수 없다. 문구 비교는 규칙상 금지.
  - **수정안 (결정 필요)**: ① **AI가 `layout.crisis: true`를 싣는다**(`meta.selfHarm` 또는 kind `crisis`일 때, 표시 전용 - `layout.urgent`와 같은 방식). BE는 실시간 답(`ChatRelayService` `aiChatClient.send` → `plain`)은 그대로 넘기지만 기록 다시 열기(`ChatLogProjection.projectLayout`)는 `urgent` 외 하위 칸을 버리므로, `urgent`와 같은 방식으로 `crisis`(불리언 true만) 한 칸을 통과시키는 BE 변경 + `ChatLogProjection` 테스트가 함께 필요하다. FE `isCrisisAnswer` = `layout.crisis === true`, 테스트를 AI 실제 모양(경고 있는 자해 답)으로 교체. AI 배포는 서버 재시작이 따르는 확인 지점. ② (임시·FE만) 모든 응급 답에 109 줄을 붙인다 - 놓치지 않지만 가슴 통증 답에도 자살예방 안내가 붙는다. **추천 ①**, ① 배포 전까지 ②를 쓸지는 사용자 결정.
- 🟡 **M-1 보호자가 없는 피보호자에게 "보호자에게 알렸어요"**
  - BE는 ACTIVE 보호자가 0명이면 이력만 저장하고 201을 준다(응답에 알린 수 없음). 챗봇 패널 안내 "통화 버튼을 누르면 보호자에게 알림이 가요"와 성공 문구 "보호자에게 알렸어요"가 이때 거짓이 된다. SOS 화면은 이 경우를 따로 다룬다(`WardSosContent` - `useWardActiveGuardians`로 보호자 없음이면 결과 문구 없이 키패드만).
  - 수정안: 챗봇도 `useWardActiveGuardians`를 써서 보호자 없음이면 안내 "연결된 보호자가 없어 알림은 가지 않아요"·성공 문구 "연결된 보호자가 없어 알림은 가지 않았어요. 시연 화면이라 119에도 걸리지 않아요"로 바꾸고 SOS(이력)는 그대로 보낸다. 목록을 못 받았을 때(로딩·오류)는 지금 문구 유지. 브라우저 테스트 추가.
- 🟢 **L-1 보내는 중 닫으면 결과를 모른다**: Esc·닫기로 키패드를 닫아도 요청은 끝까지 가지만 성공·실패 안내가 없다(실패해도 모름). SOS 화면은 실패 창을 띄운다. 수용 가능 - 고치려면 닫힌 뒤 실패면 챗봇 쪽 오류 창(SOS 화면 `openErrorModal`과 같은 문구).
- 🟢 **L-2 `chatUrgency.ts` 주석 낡음**: H-1과 함께 "자해 고정 응답은 경고와 high가 함께 온다"로 고친다.
- 🟢 **L-3 실기기 종단 확인 남음**: 시험 피보호자로 통화 버튼 1회 → 보호자 푸시·WS·이력 "챗봇 119". BE는 `SosChatbot119FullIntegrationTest`로 서비스~발송 이력까지 확인됨. 실제 푸시가 나가므로 사용자 폰 확인 때 함께.

## D. 테스트

- 있음: 가드(챗봇·말풍선·키패드), 키패드 열림·`CHATBOT_119` 1건·연타 1건·실패 후 재시도·성공 뒤 재전송 없음, 의료 응급 109 없음, 보호자 화면 요청 0·`tel:` 0, 이력 필터·배지·모르는 값.
- 없음/틀림: 자해 답(경고 있음)의 109(H-1, 지금 테스트는 틀린 모양), 보호자 없음 문구(M-1), 보내는 중 닫기(L-1).

## 수정 커밋 메시지 초안

- AI: `feat: 자해·위기 답에 layout.crisis 표시 칸 (FE 109 안내 근거, 표시 전용)`
- BE: `feat: 챗봇 기록 layout.crisis 통과 (urgent와 같이 불리언 true만, FE #277 점검 H-1)`
- FE: `fix: 챗봇 109 안내를 layout.crisis로 판정 + 보호자 없는 피보호자 안내 문구 (FE #277 점검 H-1·M-1·L-2)`

## 반영 (2026-11-17, 사용자 "전부 반영")

| 이슈 | 반영 | 배포 |
|---|---|---|
| H-1 | AI #317 `layout.crisis`(자해 `meta.selfHarm`·위기 해제 턴에만, 의료 응급 없음) → BE #500 기록 다시 열기 `ChatLogProjection`이 `crisis`를 `urgent`와 같은 기준(불리언 true만)으로 통과 → FE #279 `isCrisisAnswer` = `layout.crisis === true`, 테스트를 AI 실제 모양(경고 박스 + high + crisis)으로 교체 | AI gosky `b9cb172`(롤백 `sb-ai-rollback:20261117-layout-crisis`, 같은 pull에 다른 세션의 #318 챗 기록 90일 정리 포함) · BE `2bac5b0e` vkcs CD 성공·gosky 수동 · FE `050d6829` gosky dev |
| M-1 | 연결된 보호자가 없으면 패널 "연결된 보호자가 없어 알림은 가지 않아요"·통화 뒤 "…알림은 가지 않았어요. 시연 화면이라 119에도 걸리지 않아요"(SOS 이력은 그대로 보냄, 목록 로딩·실패면 옛 문구). 키패드 `successNotice` prop, `useWardActiveGuardians(enabled)` | FE #279 |
| L-1 | 보내는 중 키패드를 닫은 뒤 실패하면 "SOS 전송 실패" 창 + [119 화면 열기](열린 채 실패는 키패드 안 안내만) | FE #279 |
| L-2 | `chatUrgency` 주석 "자해 고정 응답은 경고와 high가 함께 온다"로 정정 | FE #279 |
| L-3 | **남음** - 실기기에서 통화 버튼 1회 → 보호자 푸시·이력 "챗봇 119"(실제 푸시가 나가 사용자 확인 때) | - |

- 확인: gosky 운영 AI 컨테이너 모듈 재생(API 호출 없음)으로 자해 고정 답 `layout.urgent=True·crisis=True`·경고 박스 있음. AI 로컬 venv 레이아웃 테스트 111건·관련 2093건 통과(torch 없는 환경 1건 제외), BE chat 테스트 실패 0, FE tsc 0·eslint 오류 0·vitest 714·브라우저 전체 732 통과.
- 수정 중 발견: `ChatbotReservation`·`ChatbotVoiceInput` 브라우저 테스트가 `useActiveConnections`를 목으로 바꾸고 있어 새 훅 호출로 32건이 깨졌다 - 목에 `useWardActiveGuardians`(보호자 1명)를 더했다.

