# 영향 범위 점검 - 보호자 챗봇 예약 대상 선택 (`guardianWards`·`reservation_patient`) (2026-11-11)

> 템플릿 C · 점검만(코드 수정 없음) · 기준 BE `aee1e4d9`(#472) / AI `4445c00`(#255·#256) · 대상 문서 `(2026-11-11) feature-guardian-chat-patient-choice.md`

## PHASE 0. 변경점과 불변식

- 바뀐 것: 보호자 챗 context에 BE가 만든 `guardianWards` 추가, `uiSelection` `reservation_patient` 값을 ACTIVE 연결로 검증, 새 인가용 메서드 `ConnectionService.getActiveWardSummaries`.
- 불변식
  - **I1**: 보호자 챗에서 AI가 환자로 묶는 피보호자는 AI 호출 시점과 응답 시점에 그 보호자의 ACTIVE 연결이다.
  - **I2**: 어느 챗 경로에서도 클라이언트 값이 `guardianWards`·대리 상담 키로 쓰이지 않는다.
  - **I3**: `guardianWards`(이름·관계)는 FE 응답·로그·저장 기록에 남지 않고, 피보호자가 떠나면 보호자 쪽 기록에서도 사라진다.

## PHASE A·B. 사용처와 판정

| # | 위치 | 불변식 | 판정 | 근거 |
|---|---|---|---|---|
| 1 | BE `ChatRelayService.sendAsGuardian` (`context.wardId`) | I1 | PASS | 형식 → `isActiveConnection` → AI 호출 → 응답 뒤 재확인. #472에서 바뀌지 않음 |
| 2 | BE `verifyPatientChoice` | I1 | PASS (심층 방어) | 남의 ID 403·형식 오류 길이만 로그·`context.wardId`와 다르면 400 |
| 3 | AI `chat_service` 선택 턴 (`selection_outcome`) | I1 | PASS | 직전 서버 기록의 `wardIds`(BE가 준 목록) 안에 있고 **이번 턴 `wardId`(BE 검증) == 고른 값**일 때만 `go`. 아니면 `reask`·`stale`이고 예약 서비스를 부르지 않는다. AI가 wardId를 스스로 정하지 않는다 |
| 4 | AI 칸 이름 비교 vs BE 비교 | I1 | ⚠️ L-1 | AI는 `field.strip()` 뒤 비교, BE는 그대로 `equals`. `"reservation_patient "`면 BE 검증을 건너뛰고 AI는 선택 턴으로 처리한다. #3 때문에 남의 피보호자로 묶이지는 않는다(심층 방어 한 겹만 빠짐) |
| 5 | BE `sanitizedContext` (두 경로) | I2 | PASS | `guardianWards` 키를 항상 지우고, 보호자 경로만 DB 값으로 채운다 |
| 6 | AI `guardian_wards()` | I2 | PASS | `role == GUARDIAN`일 때만 읽는다. `role`은 BE가 경로로 덮어쓴 값이다 |
| 7 | BE `getActiveWardSummaries` | I1·I3 | PASS | ACTIVE만, 이름·관계만, 사용처는 챗 한 곳. `getMyWards`를 쓰지 않음 |
| 8 | 탈퇴 진행 피보호자 | I1 | PASS | `UserWithdrawnEvent`가 탈퇴 요청 때 나가고, AFTER_COMMIT `tearDownConnectionsOnWithdrawal`이 연결을 끊는다 → 목록·검증 모두 제외 |
| 9 | 이용 제한 피보호자 | I1 | 해당 없음 | 피보호자는 이용 제한 금지(400) |
| 10 | 다른 AI 챗 호출 경로 | I2 | 해당 없음 | `aiChatClient.send`는 `ChatRelayService.relay` 한 곳. STT·말벗은 context를 넘기지 않는다 |
| 11 | AI `_build_user_context` (`userContext` 저장) | I3 | PASS | 허용 목록 방식이라 `guardianWards`가 없다(사용자 결정 B 충족) |
| 12 | AI 대상 질문 답 저장 (`ui`·`upstreamMeta.reservationTargetAsk.wardIds`) | I3 | ⚠️ **M-1** | 아래 |
| 13 | BE 기록 응답 `ChatLogProjection` | I3 | ⚠️ (M-1과 묶음) | `ALLOWED_FIELDS`에 `ui`가 있어 대상 질문 기록의 선택지 표시 글자("이름(관계)")가 FE 기록 화면에 내려간다. `guardianWards` 칸 자체는 없음(PASS) |
| 14 | BE 로그 | I3 | PASS | 새 WARN은 형식이 맞는 ID만, 형식 오류·피보호자 경로는 길이만. `ChatRelayLogGuardTest` 통과 |
| 15 | 계약 문구 `__self__`·"빈 목록이면 본인 예약" | - | ⚠️ L-2 | 사용자 결정(AI #255 기록)으로 **보호자 챗봇 예약은 피보호자만 대상**이다. AI는 `__self__` 선택지를 만들지 않고, 목록이 비면 안내 문장을 준다. BE가 `__self__`를 받아 주는 것은 해가 없다(AI가 `stale`로 처리). 그러나 #472 Swagger·`policy-chat.md`·기능 문서는 옛 계약 문구다 |

## 이슈

### 🟡 M-1 - 대상 질문 기록에 떠난 피보호자의 이름·ID가 남는다 (AI)

- **현상**: "누구의 병원 예약인가요?" 답은 보호자 **본인 상담 기록**(`wardId` 없음)으로 저장된다. 그 행의 `contextJson.ui.options`에 피보호자별 `value = wardId`·`label = "이름(관계)"`가, `upstreamMeta.reservationTargetAsk.wardIds`에 ID 목록이 들어간다.
- **정리에 안 걸림**: 피보호자 탈퇴 정리(#129)와 연결 해제 정리(L-13)는 `router.wardId`·`userContext.wardId`·`flowState`/`pendingAction.wardId`로만 행을 찾는다. 그래서 이 행은 남고, BE 기록 응답은 `ui`를 허용하므로 보호자 기록 화면에도 계속 보인다.
- **영향**: 탈퇴하거나 연결을 끊은 피보호자의 이름·관계·ID가 기간 제한 없이 남는다. 사용자 결정 B("떠난 피보호자의 이름이 남지 않게")의 목적이 이 경로로 빠진다. 보호자가 원래 알던 정보이고 제3자 노출은 아니라 High는 아니다.
- **제안 (AI)**: 아래 둘 중 하나.
  - (a) 정리 대상 ID 모음(`_proxy_ward_ids`)에 `reservationTargetAsk.wardIds`를 더해, 그 피보호자의 탈퇴·연결 해제 때 이 행도 지운다. 다만 그 행에 다른 피보호자도 함께 있으면 함께 사라진다.
  - (b) 저장할 때 이 답의 `ui.options` label을 비운다. 선택 턴은 `wardIds`만 쓰므로 동작은 같다. 이 경우 ID만 남으므로 (a)와 함께 한다.
  - 테스트: AI 정리 테스트에 "대상 질문 행 → 해당 피보호자 탈퇴 시 삭제" 케이스를 더한다.

### 🟢 L-1 - 칸 이름 비교 방식이 BE·AI가 다르다 (BE)

- BE `patientChoice`가 `selection.field()`를 그대로 비교한다. AI처럼 `strip()` 뒤 비교해야 두 쪽이 같은 턴을 "대상 선택"으로 본다.
- 테스트: `ChatRelayGuardianWardsTest`에 `"reservation_patient "` + 남의 ID → 403 케이스를 더한다.

### 🟢 L-2 - 문서·Swagger가 옛 계약(`__self__`·본인 예약) 문구다 (BE 문서)

- `GuardianChatController` Swagger, `policy-chat.md` 불변 규칙 ⑦ 아래 항목, `(2026-11-11) feature-guardian-chat-patient-choice.md`를 "선택지는 연결된 피보호자만(AI는 `__self__`를 주지 않음), 목록이 비면 AI가 안내 문장"으로 고친다.
- 코드의 `SELF_CHOICE` 통과는 그대로 둔다(옛 FE·해 없음). 지우면 400을 내야 하는지 같은 결정이 하나 더 생긴다.

## PHASE C. 테스트로 고정할 것

| 대상 | 방법 |
|---|---|
| L-1 | 위 BE 테스트 1건 |
| M-1 | AI 정리 테스트 1건 (대상 질문 행 삭제) |
| 칸 이름 동기화 | BE `PATIENT_CHOICE_FIELD`·AI `TARGET_FIELD`는 저장소가 달라 기계적으로 묶을 수 없다. 양쪽 주석에 서로를 적어 둔 상태를 유지한다(AI 주석에 #472 언급 있음) |

## 종합 판정

**조건부 PASS** - I1·I2는 지켜진다(BE 검증 + AI가 BE 검증 값으로만 환자를 묶음). I3는 M-1(AI 대상 질문 기록)로 일부 어긋난다. 🔴 0 · 🟠 0 · 🟡 1 · 🟢 2.

## 조치 결과 (2026-11-11, 사용자 "전부 반영해줘")

| 이슈 | 조치 | 반영 |
|---|---|---|
| 🟡 M-1 | AI #259(`ba9b4eb`): 기록에 저장하는 대상 질문 `ui`의 선택지 표시 글자를 "피보호자"로(응답 ui는 그대로), `_proxy_ward_ids`가 `reservationTargetAsk.wardIds`도 정리 대상으로 본다. 테스트 4건(저장 ui 이름 없음·다른 칸 그대로·피보호자 탈퇴 시 질문 행 삭제·연결 해제 시 삭제). 로컬 전체 2799 passed, 실패 17·수집 오류 34는 수정 전 main과 같음(무거운 패키지 없는 환경) | gosky AI 18:18 재시작, `/health` 200·warm-up 완료·GPU 9,563MiB·BE AI WS 18:18:54 재연결. 롤백 태그 `sb-ai-rollback:20261111-pre-target-ask-purge` 1개 |
| 🟢 L-1 | BE #474(`e5aab03d`): 칸 이름에 `reservation_patient`가 들어 있으면 검증한다. Java `strip()`은 NBSP를 지우지 않아 AI(Python `strip()`)와 같은 다듬기로는 맞출 수 없었다. 테스트 5건(공백·탭·NBSP·전각 공백) | vkcs CD 성공 · gosky dev 수동 배포 |
| 🟢 L-2 | BE #474: Swagger·`policy-chat.md`·기능 문서의 `__self__`·"목록이 비면 본인 예약" 문구 정정(사용자 결정 - 예약은 피보호자만). 코드의 `__self__` 통과는 유지 | 위와 같음 |

**최종 판정: PASS** - 남은 이슈 없음. 이미 저장된 옛 대상 질문 기록(#255 배포 17:46 ~ #259 배포 18:18 사이)은 표시 글자가 이름 그대로지만, 정리 대상 ID 판정이 바뀌어 그 피보호자가 떠날 때 함께 지워진다.
