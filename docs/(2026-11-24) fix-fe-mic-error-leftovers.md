# (2026-11-24) FE #338 - 마이크 열기 실패 안내 마무리 (#337 점검 K-1~K-3)

- 대상: FE `develop` `026f303` (PR #338) - `chatSttPolicy.ts`(`micOpenErrorMessage`·`micOpenFailed`), `companionVoiceNotice.ts`, `useVoiceInput.ts`(주석)
- 근거: `(2026-11-23) audit-fe-mic-error-kinds-337.md`

## 바꾼 것

| 번호 | 내용 | 결정 |
|---|---|---|
| K-1 | 옛 Chrome·웹뷰 이름 `DevicesNotFoundError` → 장치 없음, `TrackStartError` → 사용 중. `PermissionDeniedError`는 그대로 권한 | - |
| K-2 | `AbortError`(원인을 알 수 없는 장치 시작 실패)를 "다른 앱이 마이크를 쓰고 있어요" 대신 새 문구로. 챗봇 "마이크를 켜지 못했어요. 글로 입력해 주세요.", 말벗 "마이크를 켜지 못했어요." + [홈으로], 말하기 버튼 유지(device) | 사용자 결정(새 문구) |
| K-3 | 코드 변경 없음 - 도달 불가. 말벗 대화가 닫히는 길은 마침 카드의 [홈으로]와 대답 오류의 `leaveTalk`뿐이고 둘 다 대답을 한 번 보낸 뒤다. 말하기 버튼을 누를 때 `voice.clearNotice()`가 먼저 돌아 권한·장치 안내가 남은 채 닫히는 순서가 없다. 실패하는 테스트를 만들 수 없어 넣지 않았다. 대화를 닫는 길이 새로 생기면(대답 없이 닫기 등) 그때 대화를 열 때 지운다(미지원은 남긴다) | 사용자 결정(코드 빼고 기록) |

- 주의: 말벗 매핑은 "글로"가 든 공용 문구를 "지금은 말벗과 이야기할 수 없어요"(block 없음)로 바꾼다. 공용 문구를 새로 만들면 말벗 매핑도 함께 더해야 [홈으로]가 사라지지 않는다(`micOpenFailed`도 그래서 매핑을 더했다).

## 확인

- tsc·eslint 통과, 단위 739 / 브라우저 850 통과(WSL Node 22 + Chrome, 사용 후 삭제).
- 변이 5종 모두 잡힘: 옛 이름 2종 제거, AbortError를 사용 중으로 되돌림·분기 제거, 말벗 켜기 실패 매핑 제거.
- gosky dev 실측 못 함: dev FE 폴더(`/home/apps/SilverBridgeSky/SilverBridgeFe`)가 팀원 브랜치 `feat/companion-prompt-voice`(원격과 같음)에 있었다. `git pull origin develop`이 그 브랜치 위에 merge 커밋을 만들어, 올리지 않은 채 `git reset --keep`으로 팀원이 올린 커밋 `17bbe415`로 되돌렸다(작업 트리 변경 없음). #338은 그 폴더가 develop으로 돌아오거나 그 브랜치가 develop을 받으면 반영된다.

## 정리

- worktree·로컬·원격 `fix/fe-mic-error-leftovers` 브랜치, `~/.local/node22`, 임시 스크립트를 지웠다. 시험 컨테이너 없음. 유료 API 사용 없음.
