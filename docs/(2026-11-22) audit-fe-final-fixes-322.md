# 기능 점검(B) - FE 최종 점검 조치 #322

- 일자: 2026-11-22 · 템플릿 B · 점검만(코드 변경 없음)
- 대상: FE #322 (`799e734`) - 말벗 안내 문구(AI 서버로 보냄) · 말벗 푸시 4종 표시 · `textMood` 타입 · 주석
- 기준 코드: FE `origin/develop` `10bb337`(별도 worktree, `npm ci`)
- 조사: sonnet(문구와 BE·AI 저장 범위 대조, 푸시 매핑, 테스트·변이, 서버 읽기)
- 근거: `(2026-11-21) fix-audit-full-2026-11-final.md`

## 결론

- **PASS · 🔴 0 · 🟠 0 · 🟡 2 · 🟢 3**
- 안내 문구는 BE·AI의 실제 저장 범위와 맞다. 미동의면 대답을 저장하지 않고, 동의하면 따로 "N일 보관"을 안내한다.
- 푸시 4종의 이름과 표시도 BE와 맞다.
- 남은 것 두 가지
  - 동의 상태를 불러오는 짧은 순간에 잘못된 문구가 보인다(F-1).
  - 말벗 위험 알림을 SOS 아이콘으로 보여 주는 것에 결정 기록이 없다(F-2).

## 테스트

| 항목 | 결과 |
|---|---|
| vitest 단위 | 726/726 통과(81 파일) |
| 브라우저 테스트 | 850/850 통과(73 파일, Chromium) |
| 변이 5종 | 4종 검출, 1종 생존(F-3) |
| `next build` | 실행하지 않음 |
| 로그인 화면 실측 | 미확인(테스트 계정 없음) |

## 항목별 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| 푸시 종류 이름 = BE enum | PASS | `COMPANION_RISK`·`COMPANION_RISK_PATTERN`·`COMPANION_NOTICE`·`COMPANION_PROMPT`, 그 밖의 `COMPANION_*` 없음 |
| 앱 리스너 = 서비스 워커 매핑 | PASS | `PushNotificationListener.kind.ts:25-28` = `push-sw-logic.js:27-30`, 모르는 종류는 default. 테스트가 두 쪽을 대조 |
| 위험 2종 → sos | PASS | BE 강제 푸시 + 웹푸시 `Urgency: high` 목록(FcmMessageProperties.java:48-49)과 일치 |
| `textMood` 타입 | PASS | BE는 AI `textMood`를 Map 그대로 중계(CompanionDtos.java:99-101) - `scores`·`adjusted` 추가 필드도 지나감 |
| 주석 3곳 | PASS | 정책 문구와 일치 |
| gosky 반영 | PASS | FE dev 체크아웃 `10bb3378`, `silver-bridge-fe-dev-web` healthy, 컨테이너 dev 번들에 "AI 서버로 보내요" 문자열 확인 |

## 문구와 실제 동작

| 문구 | 실제 | 판정 |
|---|---|---|
| PRIVACY_TEXT "대답과 얼굴 사진을 AI 서버로 보내요. 대답한 말과 목소리, 얼굴 사진은 저장하지 않아요" (미동의 때) | 대답 글은 **동의한 경우에만** BE `companion_turn.answer_text`에 저장(30일, 철회 즉시 삭제). 음성·사진은 저장 안 함. AI는 원문·음성·사진을 DB에 저장하지 않음 | 미동의면 참 |
| `privacySharedText` "가족이 볼 수 있게 N일 보관 / 목소리·얼굴 사진은 AI 서버로 보내고 저장하지 않아요" (동의 때) | N = 서버 `retentionDays`(기본 30) | 참 |
| MEDIA_PRIVACY_TEXT (테스트 모드) | 음성·사진은 동의와 무관하게 비저장 | 참 |
| 구글 TTS 전송 미표기 | 정책상 사용자 결정으로 화면에서 생략, 문서에만 기록 | 의도 |

## 푸시 표시

- 위험 2종(sos 종류): "긴급" 라벨, `icon-sos`, 같은 계열 재알림. `requireInteraction`은 `WARD_SOS`만 true라 말벗 위험 알림은 자동으로 사라질 수 있고 액션 버튼도 없다.
- 안내 2종(default): "알림" 라벨. 화재·낙상으로 오해하지 않게 anomaly로 두지 않은 근거가 주석과 맞다.

## 발견 사항

| ID | 등급 | 내용 | 위치 | 재현 조건 | 제안 |
|---|---|---|---|---|---|
| F-1 | 🟡 | 동의 상태를 불러오는 중이거나 실패하면, 동의한 사용자에게도 "대답한 말은 저장하지 않아요"가 보인다(짧은 거짓 안내) | `WardCompanionContent.tsx:678` (`consent.data?.consented ? ... : PRIVACY_TEXT`) | 동의 완료 계정 + `GET /api/ward/companion/transcript-consent` 지연·실패 | `consent.data`가 없으면 문구를 숨기거나 "확인하는 중" 문구로 |
| F-2 | 🟡 | 말벗 위험 알림이 SOS 아이콘·"긴급" 라벨로 보이고 `requireInteraction`이 없다. 이 표시 방식의 결정 기록이 정책 문서에 없다 | `PushNotificationListener.kind.ts:25-26`, `push-sw-logic.js:27-28` | - | 사용자 결정 후 policy-companion에 한 줄 기록(필요하면 전용 `companion` 종류 검토) |
| F-3 | 🟢 | 테스트 모드 문구(MEDIA_PRIVACY_TEXT)를 바꿔도 테스트가 통과한다 | `WardCompanionContent.tsx:57` | 변이로 확인 | 브라우저 테스트에 문구 단언 1건 |
| F-4 | 🟢 | 넘침 테스트에 폰 폭(375px)이 없다 | `WardCompanionContent.browser.test.tsx` "안내 문구 배치" | - | 375px + 글자 1.47배 조합 추가 |
| F-5 | 🟢 | 안내 2종이 같은 "알림" 라벨 | - | - | 조치 없음 |

## 변이

| 변이 | 결과 |
|---|---|
| 서비스 워커 `COMPANION_RISK_PATTERN: 'default'` | 실패 2건 |
| 앱 `COMPANION_NOTICE: 'anomaly'` | 실패 2건 |
| PRIVACY_TEXT "목소리" → "음성" | 실패 1건 |
| MEDIA_PRIVACY_TEXT에서 "AI 서버로 보내고" 삭제 | **통과** (F-3) |
| `privacySharedText`에서 "AI 서버로 보내고" 삭제 | 실패 3건 |

## 수정용 커밋 메시지 초안 (FE 저장소)

```
fix: 말벗 동의 상태 확인 중에는 저장 안내 문구를 숨김, 테스트 모드 문구·폰 폭 테스트 추가

- 동의 상태 미확인(로딩·실패) 때 "저장하지 않아요" 문구 대신 확인 중 표시 (F-1)
- MEDIA_PRIVACY_TEXT 단언, 375px 넘침 테스트 (F-3·F-4)
```

## 미확인

- 로그인 상태의 실제 화면(문구 위치·줄바꿈)과 실제 푸시 수신 표시.
- F-1은 코드 읽기 근거이고 실제로 구동해 보지 않았다.
