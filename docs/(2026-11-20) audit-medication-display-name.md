# 기능 점검(B) - 약 표시 이름 `displayName` (BE #503)

> 2026-11-20. 대상 `09fb5fee`(PR #503) - `MedicationDisplayName.of`, `MedicationItem.displayName`, 대시보드 `UncheckedMedication.displayName`, WS `medicationDisplayName`. 마이그레이션 없음. 점검만 했고 코드는 고치지 않았다.

## 판정: PASS - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 3

## 점검 파일

`medication/entity/MedicationDisplayName` · `medication/dto/MedicationItem` · `medication/listener/MedicationIntakeNotificationListener` · `medication/controller/WardMedicationController`(Swagger) · `dashboard/dto/GuardianDashboardResponse` · `dashboard/service/GuardianDashboardService` + 테스트 4개.

## A. 보안·인가 - PASS

| 항목 | 결과 |
|---|---|
| 엔드포인트·역할 | 새 엔드포인트 없음. 기존 응답에 필드만 더했고 인가 경로(`isActiveConnection`·`getActiveWardIds`·WARD 본인)는 그대로 |
| IDOR | 해당 없음 - 표시 이름은 이미 볼 수 있는 `name`에서 계산한 값 |
| PII·로그 | 새 로그 없음. 약 이름을 로그에 쓰는 곳 없음(OCR 로그 규칙 유지) |
| 입력·처리 시간 | `name`은 저장 때 100자 상한. 100자 최악 입력 한 건 약 1ms(아래 L-1) |

## B. 기능 정합성 - PASS

실제 클래스로 경계 입력을 돌렸다(일회성 확인, 저장하지 않음).

| 입력 | 결과 | 판단 |
|---|---|---|
| `타이레놀정500MG` · `다이아벡스정 500 밀리그램` · `아스피린 정 100mg` | `타이레놀정` · `다이아벡스정` · `아스피린 정` | 의도대로 |
| `오메가3 1000mg` · `비타민B12 500mcg` | 그대로 | 앞이 숫자라 떼지 않음 - 원본 그대로 두는 안전한 쪽 |
| `타이레놀정500밀리그램 아침` · `철분제 (빈혈)` · `혈압약 아침 1알` | 그대로 | 끝이 용량·괄호 규칙에 맞지 않음 |
| `정(abc` · `정)` · `(정)` | 그대로 | 짝 없는 괄호·빈 결과는 원본 |
| `마그네슘정(자기 전)` · `루테인 20mg (눈 영양제)` · `비타민D 1000IU (아침)` | `마그네슘정` · `루테인` · `비타민D` | **직접 입력한 괄호 메모가 화면에서 빠진다** - 사용자 결정 ③에서 `타이레놀정(두통)`과 같은 경우로 받아들인 동작(저장 값은 그대로). FE에는 메모를 `memo` 칸에 쓰도록 안내 |

- 저장·검증·OCR·알림은 바뀌지 않았다. 일괄 등록의 중복 검사는 원본 이름 기준이라 같은 약의 다른 용량을 함께 등록할 수 있다(결정 ①: 화면에서는 같은 이름).
- gosky 실데이터(보호자 시험 계정): 약 10종 모두 `displayName` 있음, 대시보드 `mostUrgent`도 같음.
- 다른 도메인: 챗·말벗·알림은 약 이름을 쓰지 않는다(PHASE 0 확인 그대로).

## C. 구조·계약 - PASS (L-2)

- 필드 추가만이라 하위 호환(`name` 유지). 레코드 직렬화는 실서버 api-docs·응답에서 확인했다.
- WS 리스너는 `@Async` 그대로이고, 표시 이름 계산은 쓰기가 없는 순수 함수다.
- 정규식은 미리 컴파일한 상수이고 요청마다 약 개수만큼 호출된다(스케줄러 경로에서는 쓰지 않음).

## D. 테스트 - PASS (L-3)

표시 이름 40건 + DTO·대시보드·WS 각 1건. 인가가 바뀌지 않아 인가 테스트는 기존 것으로 충분하다.

## 이슈

| ID | 등급 | 내용 | 제안 |
|---|---|---|---|
| L-1 | 🟢 | `TRAILING_DOSE`의 게으른 앞부분 `(.*?)`와 숫자 `[0-9][0-9.,]*`가 되돌림을 겹쳐서, 100자 최악 입력(`가1,1,1,…x`)이 약 1ms 걸린다. 이름은 보호자가 넣은 값이라 영향은 자기 화면뿐이지만, 약이 아주 많으면 조회가 느려질 수 있다 | 숫자 부분을 소유 수량자 `[0-9][0-9.,]*+`로 바꾸고 최악 입력 시간 테스트를 둔다 |
| L-2 | 🟢 | 보호자 `GET /api/guardian/medication` Swagger 설명에 `displayName` 안내가 없다(피보호자 쪽에는 있음). 스키마에는 있다 | 설명 `medications[]` 줄에 "화면 이름은 displayName, name은 원본(수정 창 기본값)"을 더한다 |
| L-3 | 🟢 | 받아들인 동작과 안전장치(괄호 메모 제거, `오메가3 1000mg` 유지, `500MG`·띄어 쓴 단위)가 테스트로 고정돼 있지 않다 | 위 B 표의 입력을 표 테스트에 더한다 |

## 수정용 커밋 메시지 초안

```
fix: 약 표시 이름 정규식 되돌림 줄이기·Swagger 안내·경계 테스트 (#503 점검 L-1~L-3)
```
