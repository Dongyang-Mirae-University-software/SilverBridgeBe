# 영향 범위 점검(C) - SosTriggerType.CHATBOT_119 추가 (BE #498) (2026-11-17)

대상: BE #498(`04b49fb7`) - SOS 발생 경로 `CHATBOT_119`, 보호자 이력 `counts.chatbot119`. 기준: BE dev `04b49fb7`, FE `origin/develop` `6c3f1a7`.

## 결론
조건부 PASS - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 2. **BE는 결함 없음.** FE가 값을 모르는 상태라 `CHATBOT_119` 이력이 생기면 보호자 SOS 이력 화면이 깨질 수 있다(H-1) - FE ②(챗봇 119 키패드)가 값을 보내기 **전에** 같은 PR이나 앞선 PR에서 고쳐야 한다.

## 사용처 전수
| 위치 | 판정 |
|---|---|
| BE `SosEvent.triggerType` | `@Enumerated(STRING)`, VARCHAR(20), CHECK 없음 - 값 끝에 추가해 ordinal 무관(숫자 enum은 #490에서 이미 거부) ✅ |
| BE `SosTriggeredEvent`·`SosNotificationListener`·`NotificationDispatcher`·WS `sos-triggered` | 경로 필드 없음(`wardId`·`sosEventId`·`wardName`) - 알림은 경로와 무관하게 `WARD_SOS` ✅ (규칙 ②) |
| BE `SosCalledGuardianResolver` | `!= GUARDIAN_CALL`이면 버림 - `CHATBOT_119`도 버림(테스트 고정) ✅ |
| BE 이력 JPQL 필터·건수 / 네이티브 묶음 쿼리 | 경로 일반 처리(파티션·`:triggerType` 문자열) - 통합 테스트로 실 DB 확인 ✅ |
| BE `Counts`·`toCounts` | `chatbot119` 포함, default 없는 switch ✅ |
| BE 대시보드 `sos.latest` | `SosHistoryItem` 그대로 - 값만 새로 올 수 있음 ✅ |
| BE 그 밖(관리자·보관 정리·탈퇴·속도 제한) | 경로를 읽는 곳 없음 ✅ |
| AI 서버 | 참조 없음(FE가 BE를 직접 호출) ✅ |
| FE `GuardianSosHistoryContent.tsx` | ❌ H-1·M-1 |
| FE 대시보드 | `sos.thisMonthCount`만 사용 ✅ |
| FE `service/interface` 타입 | `SosTriggerType`·`WardSosTriggerType`·`counts` 타입에 새 값 없음(H-1과 함께) |

## 실측 (gosky, 읽기 전용)
시험 보호자로 `GET /api/guardian/sos/history` 4가지(기본·`group=true`·`triggerType=CHATBOT_119`·둘 다): 모두 `success`, `counts = {all 3, sosButton 1, guardianCall 2, chatbot119 0}`, 필터 결과 0건. Swagger api-docs에 `CHATBOT_119` 반영.
**`CHATBOT_119` 발생 요청은 보내지 않았다** - 저장되면 H-1로 시험 보호자 화면이 깨지고(이력은 지울 수 없음) 강제 푸시·문자가 실제로 나간다(L-1).

## 발견
- 🟠 **H-1 (FE) 모르는 경로 값에 이력 화면이 깨진다**: `TRIGGER_TYPE_META[item.triggerType].tone`(목록 렌더)에서 `CHATBOT_119`면 `undefined.tone` TypeError. 해당 피보호자의 '전체' 탭에 한 건만 있어도 화면이 그려지지 않는다. 지금은 아무도 이 값을 보내지 않아 잠재 결함. 수정: 맵에 `CHATBOT_119`(예: 라벨 "챗봇 119") 추가 + 모르는 값의 대체 표시(`?.tone ?? 'sos'`), 타입 3곳 갱신. **FE ②가 보내기 전에 반영할 것.**
- 🟡 **M-1 (FE) 탭 숫자가 맞지 않는다**: '전체' = `counts.all`(챗봇 119 포함)인데 탭은 SOS 버튼·보호자 연락 둘뿐 - 전체 ≠ 두 탭의 합. `chatbot119` 탭 추가(H-1과 함께).
- 🟢 **L-1 실제 발생 → 푸시·WS·이력 실측 보류**: H-1 반영 뒤 FE ② 검증 때 시험 피보호자로 1건 확인(쿨다운 10초·문자 상한 주의).
- 🟢 **L-2 정책 문서**: "경로 추가 시 FE 표시 맵이 먼저"를 policy-sos에 적었다(이번 반영).

## 조치
- 문서: policy-sos "챗봇 119" 절에 FE 선반영 규칙 추가, audit-index ⚠️, progress.
- H-1·M-1은 FE 몫 - FE ② 세션에 넘긴다(아래 보고).
