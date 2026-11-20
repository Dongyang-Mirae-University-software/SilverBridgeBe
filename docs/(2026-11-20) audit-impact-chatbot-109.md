# 영향 범위 점검(C) - 챗봇 109 SOS 발생 경로 `CHATBOT_109`

- 일자: 2026-11-20 · 템플릿 C(공용 enum 값 추가) · 점검만(코드 변경 없음)
- 대상: BE #507 (`3423880a`, `SosTriggerType.CHATBOT_109`) · FE #312 (`37486bf0`, 위기 답 109 버튼 → `POST /api/ward/sos` `triggerType: CHATBOT_109`)
- 기준 코드: `origin/dev` `3423880a`(BE) · FE `develop` `37486bf0`(gosky FE 체크아웃과 같음)
- 선례: `(2026-11-17) audit-impact-chatbot-119-sos.md`

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 오늘 | 2026-11-20 |
| dev | `origin/dev` = `3423880a`(#507 머지). 로컬 체크아웃은 2커밋 뒤(pull 안 함, `origin/dev`로 읽음) |
| gosky 반영 | BE 소스 `3423880a`, `dmu-dev-api` 18:34 KST 기동(머지 18:33 뒤) / FE `37486bf0`, `silver-bridge-fe-dev-web` 18:38 기동 - **BE가 FE보다 먼저 떴다**(정책의 배포 순서 충족) |
| 빌드·CI | #507 브랜치에 GitHub 체크 없음(이 저장소는 PR CI 없음). 이번 점검에서는 빌드를 돌리지 않았다(관리 세션 체크아웃이 dev 뒤라 그대로 빌드하면 머지 전 코드) |

## PHASE 0. 변경점과 불변식

- 바뀐 것: `SosTriggerType`에 `CHATBOT_109` / `SosTriggerRequest`·`SosHistoryItem` `allowableValues` / `SosHistoryPage.Counts.chatbot109`(+`all`) / `GuardianSosService.toCounts` case / 컨트롤러·`SwaggerConfig` 설명문. 마이그레이션 없음.
- 불변식: **CHATBOT_109는 이력 표시 전용 발생 경로이며, 알림·쿨다운·권한·집계는 다른 SOS 경로와 똑같이 동작하고 빠짐없이 센다.**

## PHASE A·B. 사용처 표와 판정

| # | 도메인 | 위치 | 판정 | 근거 |
|---|---|---|---|---|
| 1 | sos 저장 | `SosService.trigger` (`domain/sos/service/SosService.java:67`) | PASS | 경로를 그대로 저장만 하고 분기 없음. null이면 `SOS_BUTTON`(`SosEvent:71`) |
| 2 | sos 알림 | `SosTriggeredEvent(wardId, sosEventId, wardName)` | PASS | 이벤트에 경로가 **없다** - 리스너가 경로로 알림을 가를 수 없는 구조 |
| 3 | sos 알림 | `SosNotificationListener` (`@Async urgentNotificationExecutor`, AFTER_COMMIT) | PASS | WS `sos-triggered` + `dispatch(..., WARD_SOS)` 경로와 무관. 정지·탈퇴 수신자 차단은 `dispatch()` 한 곳 그대로 |
| 4 | 알림 정책 | `NotificationType.WARD_SOS` (`FORCED_PUSH_WITH_SMS_FALLBACK`) | PASS | 강제 FCM + SMS 폴백 그대로 |
| 5 | 쿨다운 | `SosNotificationCooldown` 키 `sos:notify:cooldown:{wardId}` | PASS | 피보호자 단위 - 경로와 무관하게 같은 쿨다운을 공유(아래 정책 질문 ②의 참고) |
| 6 | 속도 제한 | `UserRateLimitFilter` SOS 경로 제외(`^/api/ward/sos([/-].*)?$`) | PASS | 변경 없음 |
| 7 | 인증 장애 fail-open | `JwtAuthenticationFilter.SOS_PATH.equals(uri)` (`:146`) | PASS | 정확히 `/api/ward/sos`만. #507은 `global/`에서 `SwaggerConfig` 설명문만 바꿨다 |
| 8 | 입력 검증 | `SosTriggerRequest.triggerType` (Jackson enum) | PASS(값) · 🟡 M-1 | 새 값은 수용. 단 **모르는 값이면 400으로 SOS 전체가 버려진다**(아래 M-1) |
| 9 | 전화받은 보호자 | `SosCalledGuardianResolver.resolve` (`:40-46`) | PASS | `GUARDIAN_CALL`이 아니면 버림, WARN에 wardId·고정 사유만 |
| 10 | 집계 | `GuardianSosService.toCounts` (`:207-222`) | PASS | default 없는 switch에 `CHATBOT_109` case - 다음 값 추가도 컴파일로 막힘 |
| 11 | 집계 | `SosHistoryPage.Counts.of` | PASS | `all = sosButton + guardianCall + chatbot119 + chatbot109` |
| 12 | 집계 | `countByTriggerType`(EnumMap merge)·`countGroupsByTriggerType`(`values()` 이름 비교) | PASS | 새 값 자동 포함. 묶음 모드 전체 `totalElements = counts.all()`(`:127`) |
| 13 | 필터 | `GuardianSosController` `@RequestParam SosTriggerType triggerType` | PASS | enum 바인딩이라 `CHATBOT_109` 필터 수용, 모르는 값 400(조회라 무해) |
| 14 | 조회 쿼리 | `SosEventRepository` JPQL `e.triggerType = :triggerType`, 묶음 SQL `PARTITION BY ward_id, trigger_type, …` | PASS | 열람 범위 `VISIBLE_SINCE_CONNECTED`(지금 ACTIVE 연결 이후)는 경로와 무관하게 적용. 109·119를 같은 분에 눌러도 다른 묶음 |
| 15 | DB | `sos_event.trigger_type VARCHAR(20)` (V39), CHECK 없음 | PASS | `CHATBOT_109` 11자, 마이그레이션 불요 |
| 16 | 로그 | `SosService` `log.info(... triggerType={})` | PASS | 검증된 enum만 출력, 사용자 입력 원문 없음. 400은 `GlobalExceptionHandler`가 예외 클래스명만 |
| 17 | Swagger | `SosTriggerRequest:26`·`SosHistoryItem:55` `allowableValues`, `WardSosController`·`GuardianSosController`·`SwaggerConfig:114` 설명 | PASS · 🟢 L-1 | 네 곳 모두 `CHATBOT_109` 포함. 단 enum과 맞추는 테스트 없음(하드코딩 목록) |
| 18 | 보호자 대시보드 | `GuardianDashboardResponse` `SosHistoryItem latest` | PASS | 같은 DTO라 `CHATBOT_109` 그대로. FE 대시보드는 `triggerType`을 그리지 않음 |
| 19 | 알림 이력 | `AdminNotificationCategory.SOS = {WARD_SOS}`·`UserNotificationCategory` URGENT | PASS | 109 알림도 SOS·긴급으로 센다(경로 정보 없음) |
| 20 | 관리자 대시보드·통계 | (SOS 경로별 집계 없음) | 해당없음 | `sos` 밖에서 `SosEvent`·`trigger_type`을 읽는 곳 없음 |
| 21 | 탈퇴·보관 정리 | sos 정리 쿼리 | 해당없음 | 경로로 거르는 정리 없음 |
| 22 | FE 보호자 이력 | `GuardianSosHistoryContent.tsx:20-26` `TRIGGER_TYPE_META: Record<SosTriggerType, …>` | PASS | `CHATBOT_109: '챗봇 109'` - `Record`라 타입에 값이 있으면 누락이 컴파일 오류. 모르는 값 대체 색(`?? 'sos'`) 유지 |
| 23 | FE 보호자 이력 탭 | 같은 파일 `:118-121` | PASS | `counts?.chatbot109 ?? 0` |
| 24 | FE 배지 | 같은 파일 `:171` `=== 'CHATBOT_119' \|\| === 'CHATBOT_109'` | PASS · 🟢 L-2 | 등호 나열 - 다음 챗봇 경로가 생기면 배지만 빠진다(표시만) |
| 25 | FE 피보호자 챗봇 | `ChatbotContent.tsx:390` `number === '109' ? 'CHATBOT_109' : 'CHATBOT_119'` | PASS | 경로는 키패드를 연 버튼의 `number` prop(`EmergencyDialNumber`)으로 정해진다 |
| 26 | FE 타입 | `interface/guardian/sosHistory.ts:10`·`interface/ward/sos.ts:1` | PASS · 🟢 L-2 | 두 유니언 모두 포함. `chatbot109?` 선택 필드(BE는 항상 보냄, `?? 0` 처리) |
| 27 | FE 관리자 화면 | - | 해당없음 | SOS 발생 경로를 보여 주는 관리자 화면 없음 |

**FAIL 0건.** 불변식은 BE·FE 전 사용처에서 지켜진다.

## 이슈

| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟡 | M-1 | `POST /api/ward/sos`에 모르는 `triggerType`이 오면 Jackson 역직렬화 실패 → 400 → **SOS 이력도 보호자 알림도 없다**. 이번에는 BE가 FE보다 먼저 떠서 문제가 없었지만, 경로를 추가할 때마다 배포 순서(정책 문서 "배포 순서")에만 기대고 있다. 컨트롤러 주석의 "긴급 경로라 바디 유무로 실패하게 두지 않는다"와도 어긋난다 | 결정 필요: `SosTriggerRequest.triggerType`만 모르는 값을 `SOS_BUTTON`으로 받고 `[SOS-TRIGGER-UNKNOWN]` WARN(값 원문 없이 길이만) - 보호자 이력 필터(조회)는 400 유지. 새 경로가 이력에서 SOS 버튼으로 보이는 손실은 감수 |
| 🟢 | L-1 | `allowableValues` 2곳·설명문 3곳이 하드코딩. enum에 값을 더하고 빠뜨려도 테스트가 못 잡는다. `VARCHAR(20)` 길이도 테스트 없음 | `SosTriggerTypeSyncTest`(아래 PHASE C) |
| 🟢 | L-2 | FE 배지 등호 나열(`:171`)·FE 이력 테스트에 `CHATBOT_109` 탭 사례 없음(`GuardianSosHistoryContent.browser.test.tsx`는 `CHATBOT_119`만, 고정 데이터 `counts`에 `chatbot109` 없음) | FE 테스트 1건 + 배지를 메타의 `badge` 속성으로 |

## 정책 질문 (판정하지 않음 - 사용자 결정)

1. **109(마음 위기) 버튼 알림을 일반 SOS와 같은 문구(`WARD_SOS`)로 보내는 것이 보호자에게 맞는가.** 현재 `policy-sos.md`(2026-11-20)는 "푸시 문구는 경로와 무관하게 같다 - 바꾸려면 다시 결정"으로 적혀 있다. 보호자는 푸시만으로는 응급(119)인지 마음 위기(109)인지 알 수 없고 이력 화면에서만 "챗봇 109"를 본다. 그대로 둘지, 109만 문구(예: "상담 전화 버튼을 눌렀습니다")를 나눌지 확인이 필요하다. 나누면 `NotificationType` 추가(영향 범위 점검 C 재대상)·이벤트에 경로를 싣는 구조 변경이 따른다(지금은 이벤트에 경로가 없어 구조적으로 가를 수 없다).
2. (참고) 쿨다운이 피보호자 단위라, SOS 버튼을 누른 직후 쿨다운 안에서 109를 누르면 새 첫 알림이 아니라 쿨다운 규칙대로 처리된다. 경로를 가르지 않는다는 규칙 ②와 일치하지만 1번을 바꾸면 함께 봐야 한다.

## PHASE C. 테스트로 고정할 수 있는 것

| 장치 | 현재 | 제안 |
|---|---|---|
| Counts 칸 누락 | ✅ `toCounts` default 없는 switch(컴파일) + `GuardianSosServiceTest` 109 사례 2건 | 유지 |
| Swagger 허용 목록 | ❌ | BE `SosTriggerTypeSyncTest`: `SosTriggerRequest`·`SosHistoryItem`의 `@Schema(allowableValues)` 집합 == `SosTriggerType.values()` 이름 집합, 모든 이름 길이 ≤ 20(V39 `VARCHAR(20)`), `WardSosController` 요약 설명에 모든 이름 포함 |
| FE 표시 맵 | ✅ `Record<SosTriggerType, …>`(컴파일) | BE enum ↔ FE 유니언은 저장소가 달라 기계적 동기화 없음 - FE 테스트에 "BE가 보낸 모든 경로 이름에 라벨이 있다" 고정 목록 사례를 두는 정도 |
| 모르는 값에도 SOS 접수(M-1 채택 시) | ❌ | `WardSosControllerHttpTest`: `{"triggerType":"FUTURE"}` → 201, `SOS_BUTTON`으로 저장 |

## 종합 판정

**PASS (FAIL 0 · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 2).** `CHATBOT_109`는 알림·쿨다운·권한·집계에서 다른 경로와 똑같이 동작하고 빠짐없이 센다. M-1은 이번 변경의 결함이 아니라 경로 추가 때마다 되풀이되는 배포 순서 위험이며 수용 여부는 결정 사항이다.

## 수정 프롬프트 초안 (M-1·L-1 채택 시)

```
세션 제목: [BE] SOS 모르는 발생 경로도 접수 + SosTriggerType 동기화 테스트
병렬: 가능(sos dto·테스트만)
보고 언어: 모든 PHASE 보고와 최종 보고는 한국어로
PHASE 0: SosTriggerRequest·WardSosController·GlobalExceptionHandler 400 경로 확인
PHASE 1(계획 승인 후 코드): SosTriggerRequest.triggerType을 String으로 받거나 @JsonCreator로
  모르는 값 → SOS_BUTTON + [SOS-TRIGGER-UNKNOWN] WARN(wardId·길이만). 보호자 필터는 400 유지.
  SosTriggerTypeSyncTest(allowableValues == values(), 이름 ≤ 20자, 설명문 포함).
PHASE 3: ./gradlew test --tests '*Sos*' → PR → dev 머지 → gosky dev 수동 배포 확인
```

```
세션 제목: [FE] SOS 이력 챗봇 109 탭 테스트·배지 메타화
병렬: 가능
보고 언어: 모든 PHASE 보고와 최종 보고는 한국어로
GuardianSosHistoryContent: 배지 조건을 TRIGGER_TYPE_META.badge로, browser test에 CHATBOT_109 탭·건수·배지 사례 추가.
```

커밋 메시지 초안(M-1 수정 시): `fix: SOS 요청의 모르는 발생 경로를 SOS 버튼으로 접수`
