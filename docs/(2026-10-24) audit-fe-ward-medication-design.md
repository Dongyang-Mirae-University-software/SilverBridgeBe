# 피보호자 복약 화면 디자인 적용(FE #135) 기능 점검

- 날짜: 2026-10-24 / 템플릿 B(기능 점검) / 점검만, 코드 수정 없음
- 대상: FE `develop` `da48514`(PR #135 머지 `1f0cb12` + 보호자 복약 디자인 #136 포함)
- 범위: `src/app/(ward)/ward/medication/**`, 사용처 - `utils/format/medication.ts`(`formatDoseTimeKorean`·`groupMedicationsByTime`·`isRegisteredAfterDose`), `service/query/ward/medication.ts`(묶음 체크·WS 캐시 갱신), `WardLayout.tsx`(WS `medication-taken`), `PushNotificationListener.tsx`(복약 푸시 이동·재조회), 피보호자 홈 복약 칸(`WardHomeContent.tsx`)
- BE 대조: `WardMedicationController`(`/today`·`/intake/batch`), `MedicationItem.createdAt`, `MedicationIntakeNotificationListener`(수신자 = ACTIVE 보호자 + 피보호자 본인)

## 종합 판정: PASS (🔴 0 · 🟠 0 · 🟡 2 · 🟢 4)

보안·역할 분리·묶음 체크·되돌리기·오늘 등록 전 약 제외는 정상이다. 🟡 2건은 화면 동작(펼침 고정)과 글자 대비이며 데이터·알림에는 영향이 없다.

## PHASE A. 보안·역할

| 항목 | 결과 | 근거 |
|---|---|---|
| 피보호자는 체크·해제만 | PASS | 화면에 추가·수정·삭제 버튼 없음(테스트 고정), 호출은 `/api/ward/medication/today`·`/intake/batch`뿐 |
| 남의 약 체크 | PASS(BE) | 화면은 `medicationIds`만 보내고 소유 판정은 BE(`MEDICATION_NOT_OWNED` 403 + `[IDOR-ATTEMPT]`). 실패 시 안내 + 재조회 |
| 화면 접근 역할 | PASS | `(ward)` 레이아웃 아래 - 기존 역할 가드 그대로 |
| 로그·PII | PASS | 새 로그 없음, 실패는 기존 `reportNonApiError` |

## PHASE B. 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 묶음 체크는 남은 약만 | PASS | 일부 체크 시 "남은 약 N개 먹었어요", 보내는 ID = 미체크·오늘 대상 약 |
| 되돌리기 = 그 시간대 묶음 해제 | PASS | 체크된 약 ID를 `taken:false`로 묶음 전송, 단건 API 미사용 |
| 연속 탭 | PASS | ref 즉시 잠금 + 처리 중 버튼 비활성 - 둘 다 테스트로 검출 확인 |
| 오늘 등록 전 약 | PASS | `isRegisteredAfterDose`(= BE `MedicationClock.registeredAfterDose`), 체크·완료 판정 제외, 시간대 전체면 "오늘은 등록 전" |
| KST 오늘 | PASS | BE `doseDate` + `useKstMidnightRefetch` |
| 반 알·시각 | PASS | "반 알"(BE 알림과 같은 표기), "오전 8시" |
| WS `medication-taken` | PASS(+L-1) | 본인 체크의 되울림·다른 기기 체크 모두 캐시에 반영, 멱등 |
| 피보호자 홈 | 해당 없음 | `/ward/medication` 링크 카드만, 데이터 미사용 |
| 375·768·1280px·고대비 | PASS | 가로 넘침 없음, gosky 실화면 바깥 스크롤 없음(scrollHeight = innerHeight) |

## 이슈

### 🟡 M-1 체크한 시간대가 계속 펼쳐져 다음 시간대로 넘어가지 않는다 (#135에서 생김)

- 체크하면 되돌리기를 위해 그 시간대를 펼쳐 둔다(`focusKey`). 이 값이 화면을 떠나기 전까지 남아, 아침을 체크하고 화면을 켜 둔 채 점심이 되면 "지금 점심 약" 대신 "아침 약 다 먹었어요"가 펼쳐져 있다.
- 자정 재조회로 날이 바뀌어도 같은 키가 남는다 - 재현: 취침 전 약 체크 → 다음 날 데이터로 재조회 → "지금 아침 약"이 아니라 "취침 전 약"이 펼쳐짐(임시 브라우저 테스트로 확인).
- 시니어가 화면을 켜 둔 채 쓰는 경우 지금 먹을 약이 한 줄로 접혀 보인다. 데이터·알림 영향 없음.
- 제안: `doseDate`가 바뀌면 `focusKey`를 비우고, 화면이 다시 보일 때(visibilitychange) 펼친 시간대가 "다 먹음"이면 비운다. 테스트로 고정.

### 🟡 M-2 "오늘은 등록 전이었어요" 글자 대비 3.66:1 (일반 모드)

- `WardMedicationCard.module.css` `.state` 17px·700, `--sb-ink-mute`(#8c8576) on #fff = 3.66:1. 이 크기는 큰 글자(18.66px 굵게) 기준에 못 미쳐 4.5:1이 필요하다. 고대비 모드는 9.01:1로 통과.
- 왜 이 약을 체크할 수 없는지 알려 주는 유일한 문구라 시니어가 읽을 수 있어야 한다.
- 제안: `.state`(미체크)는 `--sb-ink-soft`(9.51:1)로. 참고로 기존 `.intakeError`(18px·800, 4.23:1)도 경계값이라 함께 19px 이상으로 올리면 깔끔하다.

### 🟢 L-1 WS 캐시 갱신이 `doseDate`를 보지 않는다 (#135 이전부터)

- `applyMedicationTakenToWardCache`가 payload의 `doseDate`를 무시해 자정 직전 체크의 늦은 이벤트가 다음 날 같은 약을 체크된 것처럼 바꾼다(재현 확인). 화면 표시만이고 다음 재조회에서 바로잡힌다.
- 제안: payload `doseDate`가 캐시 `doseDate`와 다르면 무시. 이 함수의 테스트도 없다 - 함께 추가.

### 🟢 L-2 되돌리기 실패에도 "체크하지 못했어요"

- 해제 실패에도 같은 문구다. 제안: 해제면 "되돌리지 못했어요…".

### 🟢 L-3 페이지 설명 "보호자에게도 알려 드려요"

- 연결된 보호자가 0명이면 사실이 아니고, 실제로는 보호자 화면 실시간 반영(WS)뿐이며 푸시는 없다(정책: 복용 체크 알림은 WS만). 제안: "연결된 보호자 화면에도 바로 보여요".

### 🟢 L-4 테스트 공백

- 변이 7종 중 6종 검출(오늘 등록 전 제외 제거·되돌리기를 체크로·남은 약 대신 전체·처리 중 잠금 제거·ref 잠금 제거·반 알 표기). **미검출 1종**: "지금"을 모든 시간대에 붙여도 통과 - 나중 시간대를 펼쳤을 때 "지금"이 없음을 단언 추가.
- WS 이벤트로 펼친 카드가 바뀌는 경우, 시간대 전체가 오늘 등록 전인 날도 테스트가 없다.

## 참고(이슈 아님)

- 단건 체크 API·`useMedicationIntakeMutation`은 화면에서 더 쓰지 않는다(A안 - 시간대 단위만, PR #135에 기록). 지울지는 후속 정리 때.
- BE 묶음 체크 상한 50개 - 한 시간대에 약 50개를 넘는 경우는 현실적으로 없어 다루지 않았다.

## 실서버

- gosky FE `1f0cb12` 반영, `/ward/medication` 200, 테스트 피보호자 로그인 실화면 3개 크기에서 바깥 스크롤·가로 넘침 없음.
- 체크 → 보호자 화면 반영 → 해제는 **미확인**: 테스트 피보호자에게 ACTIVE 보호자·오늘 약이 없다(테스트 보호자는 다른 피보호자와 연결). 확인하려면 테스트 계정끼리 연결 + 약 등록 후 정리가 필요해 사용자 결정 대기.

## 수정 커밋 메시지 초안

- `fix: 피보호자 복약 화면 펼침 고정·안내 글자 대비 - 날이 바뀌거나 다시 볼 때 지금 시간대로(점검 M-1·M-2)`
- `fix: 피보호자 복약 WS 갱신은 같은 날짜만 반영·되돌리기 실패 문구·설명 문구(점검 L-1~L-4)`

## 후속 조치 (2026-10-25) - 🟡 2 · 🟢 4 전부 반영

FE #140(머지 `346f1ea`, gosky FE 반영·`/ward/medication` 200·실화면 375/768/1280 바깥 스크롤 없음).

| 이슈 | 조치 |
|---|---|
| M-1 | 펼친 시간대는 `{key, doseDate, stale}` - 다른 날이면 무시, `visibilitychange`로 다시 볼 때 그 시간대가 다 먹은 상태면 지금 시간대로. 누른 직후 [되돌리기]는 유지 |
| M-2 | `.state` → `--sb-ink-soft`(9.51:1), `.intakeError` 19px |
| L-1 | `applyMedicationTakenToWardCache`가 payload `doseDate` ≠ 캐시 `doseDate`면 무시(날짜 없는 옛 형식은 반영) + 단위 테스트 3 |
| L-2 | 마지막 요청 `taken=false` 실패면 "되돌리지 못했어요" |
| L-3 | 설명 "연결된 보호자 화면에도 바로 보여요" |
| L-4 | 브라우저 테스트 6 추가(나중 시간대 "지금" 없음·날 바뀜·다시 보기·되돌리기 실패·WS 반영·전부 등록 전) |

검증: `npm test` 309 · `npm run test:browser` 199 통과(develop #138·#139 합친 상태), 변이 5종(날짜 확인·다시 보기·되돌리기 문구·"지금"·WS 날짜) 모두 해당 테스트에서 검출. 실서버 체크 → 보호자 반영은 여전히 미확인(테스트 계정 연결 없음).

