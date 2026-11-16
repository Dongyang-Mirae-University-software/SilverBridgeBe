# 기능 점검 - FE 피보호자·예약 화면 정리 (#239·#241·#243·#244·#246·#247·#248) (2026-11-16)

대상: SilverBridgeFe develop `568078f`(7개 PR 모두 머지, 이후 #249~#271 포함한 지금 코드 기준), gosky FE dev `568078f4`. 관리자 화면은 FE 담당자 몫이라 제외.

## 점검 파일

`useVoiceInput.ts`·`VoiceInputButton.tsx`·`lib/audio/recordingLimiter.ts`(마이크·3초 무음) / `ChatbotContent.tsx`(녹음 중 칩·예약 선택지 잠금, 119 패널, 꼬리 질문) / `chatUrgency.ts`(응급 판정) / `utils/companion/companionOpen.ts`·`WardCompanionContent.tsx`·`WardHomeContent.tsx`(말벗 시간대) / `utils/medication/displayName.ts`(약 이름) / `WardSelectorTabs.tsx`·`GuardianHospitalContent.tsx`·`HospitalConfirmStep.tsx`(진료받으실 분·예약 확인).

## A. 보안·개인정보 - PASS

마이크는 누를 때만 권한을 묻고, 거부·장치 없음이면 서버를 부르지 않는다. 녹음은 메모리 WAV로 한 번 보내고 남기지 않으며 화면 이탈·대화 전환 때 트랙을 멈추고 요청을 취소한다. 119는 `tel:119` 링크뿐. 예약 피보호자 버튼은 ACTIVE 연결만, 진행 중에는 고른 사람만 보인다.

## B. 기능 정합성 - PASS

| 확인 | 결과 |
|---|---|
| 3초 무음 자동 종료 | 무음은 말소리를 들은 뒤에만 센다(`heardSpeech`, 최소 말소리 시간 이후) - 말을 시작하기 전 침묵으로는 끊기지 않음. 상한 30초(오디오 콜백이 멈춰도 타이머로 끝냄), 0.3초 미만은 "들리지 않음" |
| 중복 전송 | 권한 창 동안 두 번째 누름 차단(`busyRef`), 녹음·변환 중 칩(`disabled`)·예약 선택지(`active`)·처리 함수 모두 잠금(`wardVoiceBusy`) |
| 119 버튼 | 경고 문장 배열·`riskLevel === 'high'`·`layout.urgent === true`(불리언만) 중 하나면 응급 - 말풍선 긴급 상자와 같은 판정, 예약 진행 패널이 먼저, 다음 답이 응급이 아니면 빠른 질문으로 돌아온다 |
| 말벗 시간대 | `currentSlot`이고 그 칸이 `AVAILABLE`·`IN_PROGRESS`일 때만 - BE 상태값(`AVAILABLE`·`IN_PROGRESS`·`DONE`·`MISSED`·`UPCOMING`)과 일치, 마친 칸(DONE)은 BE도 다시 열지 않는다. 테스트 계정은 말벗 화면의 별도 경로 |
| 진료받으실 분 | 0명 = 안내 문구, 1명 = 버튼 숨김, 2명 이상 = 버튼, 예약 진행 중 = 고른 사람만·누를 수 없음 |
| 긴 약 이름 | 8자 넘는 이름만 끝의 용량(숫자 + 단위·복합·농도·괄호) 제거, 빼고 비면 원래 이름 |

## C. 화면 실측 (gosky devdmu.gosky.kr, Chromium 모바일, 2026-11-16 09:1x)

| 화면 | 360px | 390px |
|---|---|---|
| 피보호자 홈·챗봇·복약·말벗 | 200, 가로 넘침 0 | 로그인 기록 실패(같은 계정 연속 로그인 - 잠금 위험으로 재시도 안 함, 360px로 갈음) |
| 보호자 병원 예약·복약·챗봇 | 200, 가로 넘침 0 | 200, 가로 넘침 0 |

말벗 화면은 09시 창이라 "이야기 시작하기"가 보였고(창 규칙 일치) 테스트 표시가 나왔다. 스크린샷은 커밋하지 않았다(작업 뒤 삭제).

## D. 테스트

tsc 0 · eslint 0 · vitest 656 통과 · **브라우저 테스트(Chromium) 669 통과** - #246 때 서버에 실행 환경이 없어 남았던 "로컬 `npm run test:browser` 확인 필요"를 해소. 핵심 로직(`displayName`·`companionOpen`·`RecordingLimiter`)은 단위 테스트, 응급 119·녹음 중 잠금·진료받으실 분은 브라우저 테스트가 덮는다. WebKit(아이폰 Safari)은 WSL에서 실행 불가 - 실기기 몫.

## 이슈

🔴 0 · 🟠 0 · 🟡 0 · 🟢 2

| # | 내용 | 근거 | 제안 |
|---|---|---|---|
| L-1 | 용량만 다른 같은 약 두 개(예: "아스피린 100mg"·"아스피린 500mg")는 피보호자 복약 칸에 둘 다 "아스피린"으로 보인다 - 어느 것을 먹었는지 헷갈릴 수 있다 | `utils/medication/displayName.ts`(항목마다 따로 줄임, `WardMedicationContent.tsx:225`) | 목록 안에서 줄인 이름이 겹치면 그 둘은 원래 이름 유지 |
| L-2 | 마이크 시작 중 `createMediaStreamSource` 등이 드물게 실패하면 만든 `AudioContext`를 닫지 않고, 그 catch는 화면 이탈 여부를 보지 않고 안내를 띄운다 | `components/chatbot/useVoiceInput.ts:183` | catch에서 context 닫기 + `mountedRef` 확인 |

## 조치 (2026-11-16, 사용자 "전부 반영해줘")

FE #272 `603de3d`(gosky FE dev 반영, 받은 스크립트에서 새 코드 확인·재시작 불필요).

| # | 결과 |
|---|---|
| L-1 | `wardMedicationDisplayNames` - 같은 칸에서 줄인 이름이 겹치면 그 약들은 원래 이름(원래 이름까지 같으면 줄인 이름), 칸마다 한 번 계산. vitest 5건 추가 |
| L-2 | `useVoiceInput.start` 실패 시 만든 AudioContext 닫기 + 화면을 떠났으면 안내 생략 |

## 종합

**PASS** (Low 2건). FE 수정은 FE 저장소 PR로 진행한다.
