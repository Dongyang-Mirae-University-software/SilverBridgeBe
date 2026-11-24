# (2026-11-24) FE #338 기능 점검 (템플릿 B, 짧게) - 마이크 켜기 실패 안내·옛 오류 이름

- 대상: FE `develop` `026f303` (PR #338) - `chatSttPolicy.ts`(`micOpenErrorMessage`·`micOpenFailed`), `companionVoiceNotice.ts`, `useVoiceInput.ts`(주석). 점검 시점 develop `ebb744f`(이후 이 파일들을 바꾼 커밋 없음)
- 근거: `(2026-11-24) fix-fe-mic-error-leftovers.md`, `(2026-11-23) audit-fe-mic-error-kinds-337.md`
- 방식: 읽기 전용(사본). 테스트는 WSL Node 22 + Chrome(사용 후 삭제). 실측은 gosky dev(이번엔 dev FE 폴더가 develop `ebb744f`, #338 포함) - 마이크는 탭에서 `getUserMedia`만 가짜 오류로, 말벗은 오늘 현황·대화 열기 응답만 가짜로. 질문·대답을 보내지 않음(챗 요청 0건, 말벗 대답 요청 0건). AI·유료 호출 없음

## 판정: PASS (🟢 1)

| 항목 | 결과 | 근거 |
|---|---|---|
| 사용처 | PASS | `micOpenErrorMessage`는 `useVoiceInput` 한 곳, 그 훅은 보호자·피보호자 챗봇과 말벗 세 화면. 말벗은 `companionVoiceNotice`로 짧은 문구 + device |
| 의도 확인 | PASS | 장치 없음(`NotFoundError`·`OverconstrainedError`·`DevicesNotFoundError`) / 사용 중(`NotReadableError`·`TrackStartError`) / 켜기 실패(`AbortError`) / 그 밖(`PermissionDeniedError` 포함) = 권한. 말벗 device는 초점을 옮기지 않는다(미지원일 때만 [홈으로]로) |
| 공용 문구 규칙 | PASS(🟢 K-1) | "글로"가 든 공용 문구 6개(micNotFound·micInUse·micOpenFailed·unsupported·unavailable·disabled) 모두 말벗 매핑이 있다 |
| K-3 정정 재확인 | PASS | 대화가 닫히는 길은 여전히 마침 카드(`closeTalk`)와 대답 오류(`leaveTalk`)뿐, `handleVoice`가 `voice.clearNotice()` 뒤에 `toggle()` |
| 테스트 | PASS | tsc·eslint 통과, 단위 739 / 브라우저 853 통과 |
| 변이 | PASS | 6종 모두 잡힘: 옛 이름 2종 제거(단위), AbortError→사용 중, AbortError 분기 제거, 말벗 켜기 실패 매핑 제거, 말벗 block device→null(단위·브라우저) |
| 실측(gosky dev) | PASS | 아래 표 |

## 실측

| 화면 | 크기 | 가짜 오류 | 결과 |
|---|---|---|---|
| 보호자 챗봇 | 390x844 | `AbortError` | "마이크를 켜지 못했어요. 글로 입력해 주세요.", 글 칸 있음, 챗 요청 0, 넘침 0 |
| 보호자 챗봇 | 390x844 | `TrackStartError` | "다른 앱이 마이크를 쓰고 있어요. 글로 입력해 주세요." |
| 보호자 챗봇 | 390x844 | `DevicesNotFoundError` | "마이크를 찾지 못했어요. 글로 입력해 주세요.", 넘침 0 |
| 피보호자 챗봇 | 390x844 | `AbortError` | 같은 문구 + [글자로 쓰기], 챗 요청 0, 넘침 0 |
| 말벗 | 1180x820, 큰 글자 1.47배 | `AbortError` | "마이크를 켜지 못했어요." + [홈으로] + [말로 대답하기] 유지(아래 끝 526 < 820), 초점 그대로, 넘침 0, 대답 요청 0 |

- 처음 옛 이름 두 개를 같은 페이지에서 1초 간격으로 이어 눌렀을 때 안내가 비어 보였으나, 새로 불러 한 번씩 누르면 정상이었다(확인을 너무 일찍 한 것 - 결함 아님).

## 발견

| 번호 | 등급 | 위치 | 내용 | 제안 |
|---|---|---|---|---|
| K-1 | 🟢 | `companionVoiceNotice.test.ts` | 가드 테스트는 공용 문구가 말벗에서 "글로"를 내보내지 않는지만 본다. 장치 오류용 공용 문구가 새로 생기고 말벗 매핑을 빠뜨리면 일반 분기(`includes('글로')`)로 가 block이 null이 되어 [홈으로]가 조용히 사라진다(#338이 `micOpenFailed`에 매핑을 손으로 더한 이유) | `STT_MESSAGES` 키마다 기대 block을 적은 표(`Record<keyof typeof STT_MESSAGES, CompanionVoiceBlock>`)로 고정하는 테스트 - 새 키를 더하면 타입·테스트가 함께 실패한다 |

## 미확인

- 실제 옛 Chrome·웹뷰·Firefox 기기에서 나는 오류 이름(가짜 오류로 갈음).

## 정리

- 점검 사본·`~/.local/node22`·`/tmp/mj_a338_*`·임시 스크립트를 지웠다. gosky에 만든 것·시험 컨테이너 없음. 브라우저 탭의 가짜 응답·화면 크기·큰 글자 값은 새로 불러 원래대로.
- 디스크 `df -h /`: gosky 81% → 81%, 로컬 WSL 5%. 유료 API 사용 없음.
