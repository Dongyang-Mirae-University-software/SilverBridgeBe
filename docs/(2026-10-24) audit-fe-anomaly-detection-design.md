# (2026-10-24) FE 보호자 이상감지 Claude Design 디자인 적용 기능 점검

- 대상: FE PR #123 `design/anomaly-detection` (머지 `765e7c9`, gosky 반영 2026-10-24)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 기준: FE develop `765e7c9`, gosky FE `765e7c9`
- 범위: 이상감지 화면 + 건드린 공용 코드(이상감지 API·쿼리·타입)의 사용처 + 실시간 감지·푸시 갱신 경로 + 판정에 따른 클립 숨김·복구 흐름

## 결론

**PASS** - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 4

정책(보호자만 판정·다수결·동수 안내·다른 보호자 선택 비노출·단정 금지 문구·모르는 값 비움·클립 blob 재생과 해제·실시간 src 제거·429 안내)은 코드·테스트·gosky 실측에서 모두 지켜졌다. 남은 것은 모두 낮은 단계다.

- L-1: 영상 화면에서 "괜찮아요"를 고르면 클립 목록은 비지만, 이미 받아 둔 영상은 화면을 닫을 때까지 계속 재생된다.
- L-2: 판정·실시간 감지 뒤 보호자 대시보드의 "확인이 필요한 일" 숫자가 최대 30초 늦게 바뀐다(원래부터).
- L-3: 푸시로 연 `?session=` 카메라가 이후 "실시간 카메라 보기"를 다시 눌러도 계속 먼저 열린다(원래부터).
- L-4: 요약 쿼리 키가 이력 키 아래에 있어야 실시간 갱신이 되는데, 이것을 지키는 테스트가 없다(변이 11종 중 유일하게 놓침).

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-10-24 |
| develop에 `765e7c9` 포함 / gosky FE | 예 / `765e7c9` |
| `npm test` / `npm run test:browser` | 286 통과 / 134 통과(빈 캐시) |
| `tsc --noEmit` / `npm run build` | 통과 / 통과 |
| 린트 | 기존 `BirthDateSelects.tsx` 오류 1 그대로(무관) |
| 송출 중 카메라 | 없음(gosky AI `/frame` 10초 0건) - 실시간 영상 재생은 실측 못 함 |

## PHASE 0. 바뀐 공용 코드와 사용처

| 바뀐 것 | 사용처(이상감지 화면 밖) | 영향 |
|---|---|---|
| `GetAnomalyHistoryParams.type` 추가 | 없음 | 선택 필드라 기존 호출 무변경 |
| `getGuardianAnomalyHistorySummary`·`guardianAnomalyHistorySummaryQueryOptions` 신설 | 없음 | - |
| 요약 쿼리 키 `['guardian-anomaly-history','summary',wardId]` | `GuardianLayout`(WS `ANOMALY_DETECTED`)·`PushNotificationListener`(푸시)가 `['guardian-anomaly-history']`로 무효화 | 앞부분 일치라 요약도 함께 갱신됨(의도) |
| `AnomalyIncidentCard`·`AnomalyClipModal` 재작성, `AnomalyVerdictPanel` 신설 | 없음(이상감지 화면 전용) | - |
| `page.tsx`가 `PageLayout`을 `GuardianMonitorContent`로 넘김 | 대시보드·메뉴는 `/guardian/detection` 링크만 | 경로 무변경 |
| `vitest.browser.config.ts` `next/link` 미리 묶기 | 전 브라우저 테스트 | 빈 캐시 실행 중 재로딩 0회 확인 |

공용 `WardSelectorTabs`는 수정하지 않았다(이 화면에서만 감싸는 요소로 버튼 44px).

## PHASE A. 정책·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 판정은 보호자 화면에만, REAL/FALSE_ALARM 두 값 | ✅ | `AnomalyVerdictPanel` - `useAnomalyFeedbackMutation`만 호출 |
| 판정 뒤에도 다시 고르기(덮어쓰기) | ✅ | "판정 바꾸기"(내 표 없으면 "나도 응답하기"), 테스트 + 변이 M3 검출 |
| 결과는 다수결(`reviewStatus`), 내 표는 다를 때만 "내 응답" | ✅ | 변이 M10 검출 |
| 동수만 재확인 안내, 다른 보호자 선택 비노출 | ✅ | 응답 DTO에 다른 보호자 표가 없고 화면도 쓰지 않음, 변이 M4 검출 |
| "알림 취소"·"화재가 발생했습니다" 같은 표현 | ✅ 없음 | 문구 전수 확인 |
| 모르는 값을 0으로 채우지 않음 | ✅ | 요약 실패 시 칩 숫자 비움(테스트), 실시간 `status=null` = "연결 상태를 확인하고 있어요"(변이 M9 검출) |
| 클립: 헤더 인증 fetch → blob, 닫으면 revoke, URL 토큰·다운로드 없음 | ✅ | 변이 M6 검출, gosky 실측 `blob:`·다운로드 링크 없음 |
| 클립 404 / 403 다른 문구 | ✅ | 404 = 볼 수 없는 영상 안내(변이 M5 검출), 403 = 서버 문구 |
| 실시간: 닫을 때 `<img>` src 제거, 429 서버 안내 표시 | ✅ | 변이 M7·M8 검출 |
| 유형 필터는 서버 쿼리, 건수는 요약 API | ✅ | 변이 M1·M2 검출, gosky 요청 `type=FALL` 실측 |

## PHASE B. 흐름 점검

1. **실시간 감지·푸시 → 건수 갱신**: WS `ANOMALY_DETECTED`와 푸시 수신이 모두 `guardianAnomalyHistoryQueryKey` 앞부분으로 무효화하고, 판정 mutation도 같은 키를 무효화한다. 요약 키가 그 아래라 목록과 "확인 필요"가 함께 갱신된다. 다만 이 배치를 지키는 테스트가 없다(L-4).
2. **판정 → 클립 숨김·복구**: "괜찮아요" 성공 → 이력·그 상황의 클립 목록 무효화 → BE가 클립을 숨겨 이력의 `clip`이 null(썸네일 "영상 없음"), 클립 목록은 빈 배열이 된다. 24시간 안에 "실제 위험이에요"로 바꾸면 BE가 복구하고, 썸네일은 `clipId` 캐시로 바로 다시 보인다. 열려 있던 영상 화면은 이미 받은 blob을 계속 재생한다(L-1).
3. **푸시 딥링크 `?session=`**: 첫 열기는 그 카메라를 연다(기존과 같음). 그 뒤 다른 피보호자를 골라 "실시간 카메라 보기"를 눌러도 `initialSessionId`가 계속 우선해 처음 카메라가 열린다(L-3, 이 PR 전에도 같은 동작).

## PHASE C. gosky 실측 (테스트 보호자, 데스크톱 1440·375)

| 항목 | 결과 |
|---|---|
| 요약 API ↔ 칩 | `total 45 / fire 29 / weapon 11 / fall 5 / needsReview 45` = "전체45·화재29·흉기11·낙상5·확인 필요 45" |
| 낙상 칩 | 요청 `history?page=0&size=10&wardId=…&type=FALL`, 5줄 |
| 페이지 이동 | 이전 1~5 다음(45건/10) |
| 클립 | `blob:` 재생, 다운로드 링크 없음, 클립 2개 상황에서 "영상 1·2" |
| 실시간 모달 | 송출 없음 → "영상이 오지 않아요", 카메라 칩 "꺼짐" |
| 바깥 문서 | 데스크톱 `scrollHeight 900 = innerHeight 900`, 375 `812 = 812`, 가로 넘침 버튼 0 |
| 판정 | **실서버에서 보내지 않음**(데이터 변경·동수 시 다른 보호자 푸시 우려) - 컴포넌트 테스트로 대신 |

## PHASE D. 테스트 품질 (변이 시험)

이상감지 브라우저 테스트 14개에 변이 11종을 하나씩 넣어 돌렸다. 고치지 않은 원본은 14개 통과.

| 변이 | 결과 |
|---|---|
| M1 `type` 쿼리 미전달 · M2 칩 건수를 목록에서 셈 · M3 판정 바꾸기 숨김 · M4 동수 안내 제거 · M5 클립 404 안내 제거 · M6 blob revoke 제거 · M7 MJPEG src 제거 안 함 · M8 429 안내 가림 · M9 `status=null`을 꺼짐으로 · M10 다수결 대신 내 표 표시 | 10종 검출 |
| M11 요약 키를 이력 키 밖으로 | **놓침**(L-4) |

## 이슈

| ID | 단계 | 내용 | 원인 | 제안 |
|---|---|---|---|---|
| L-1 | 🟢 | 영상 화면에서 "괜찮아요"를 고르면 클립 목록은 비는데 재생 중인 영상은 닫을 때까지 남는다 | `AnomalyClipModal`이 받은 blob을 목록과 대조하지 않음 | 목록 갱신 뒤 재생 중 클립이 없으면 blob 해제 + "볼 수 없는 영상" 안내. 본인이 고른 직후라 노출 범위는 같은 보호자 |
| L-2 | 🟢 | 판정·실시간 감지 뒤 대시보드 "확인이 필요한 일"이 최대 30초 늦다 | `['guardian-dashboard']`(staleTime 30초)를 무효화하지 않음. 이 PR 전부터 | 판정 성공·`ANOMALY_DETECTED` 수신 때 대시보드 키도 무효화 |
| L-3 | 🟢 | 푸시로 연 카메라(`?session=`)가 이후 실시간 열기에서도 계속 먼저 열린다 | `GuardianMonitorContent`가 매번 `initialSessionId`로 넘김. 이 PR 전부터 | 딥링크는 첫 열기에만 쓰기 |
| L-4 | 🟢 | 요약 키 배치(실시간 갱신의 전제)를 지키는 테스트 없음 | - | 요약 키가 이력 키로 시작하는지 단위 테스트 1개 |

## 수정 프롬프트 초안 (승인 시)

FE `fix/anomaly-audit-findings` - L-1 `AnomalyClipModal` 재생 중 클립이 목록에서 사라지면 해제·안내(+브라우저 테스트) / L-2 `useAnomalyFeedbackMutation` onSuccess와 `GuardianLayout` `ANOMALY_DETECTED`에서 `['guardian-dashboard']` 무효화 / L-3 딥링크 `session`을 첫 열기 뒤 버리기(+테스트) / L-4 쿼리 키 단위 테스트. BE·AI 변경 없음, gosky 반영 후 실측.
