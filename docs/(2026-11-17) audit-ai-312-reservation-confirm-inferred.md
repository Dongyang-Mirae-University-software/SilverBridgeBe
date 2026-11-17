# AI #312 기능 점검 - 예약 시작 때 짐작한 진료과·하나뿐인 병원 확인 (2026-11-17)

- 대상: SilverBridgeAiServer PR #312, main `1d0da47` (gosky 반영 2026-11-17 16:45, 롤백 태그 `sb-ai-rollback:20261117-reservation-confirm`)
- 템플릿: B (기능 점검) · 점검만, 코드 수정 없음
- 검증 방법: 코드 읽기 + gosky `git archive` 사본에서 stub 시험(API 키 비움, 라우터·추출 stub). 상담 본문은 보지 않았다.

## 1. 점검 파일

| 파일 | 변경 |
|---|---|
| `app/services/reservation_intake.py` | `ReservationDraft.department_source`·`hospital_source` 추가 |
| `app/services/reservation_confirm_flow.py` | 저장 초안 키(`_DRAFT_KEYS`)에 두 출처 추가 |
| `app/services/reservation_orchestrator.py` | `_tag_sources`(출처 판정)·`_ask_department_confirm`·`_hospital_choice_reply`, `_advance`의 확인 분기, 후보 1개 자동 선택 제거, 조사 도우미 |
| `tests/test_reservation_confirm_inferred.py` | 신규 13개 |

## 2. 보안·인가 (PHASE A)

| 항목 | 결과 | 근거 |
|---|---|---|
| 새 엔드포인트·인가 경로 | 해당 없음 | 기존 예약 흐름 안의 분기만 추가 |
| 피보호자 고정·대상 선택 | PASS | `_pin_ward_patient`가 확인 분기보다 먼저, 대상 선택은 chat_service 그대로 |
| decisionTrace·로그 PII | PASS | 새 값은 `department_source`·`hospital_source`·`candidate_count`·`missing=department_confirm` 코드값뿐. `[예약트레이스]` 로그는 기존 가림 그대로 |
| 이력에서 나온 병원 카드 | PASS | 카드는 `hospital_id`가 있을 때만(예약 서비스에서 찾은 병원) - 이력의 임의 문구가 카드로 나가지 않음. 단 아래 L-2 |

## 3. 기능 정합성 (PHASE B)

| 경로 | 결과 |
|---|---|
| 이전 대화 진료과 → [네, ○○]/[다른 과 고르기] → 버튼 뒤 검색 | PASS (시험) |
| 이번 문장 진료과·증상 → 바로 진행 | PASS |
| [가까운 ○○ 찾기](저장 초안 없는 department 버튼) → 바로 진행 | PASS |
| 추출(stub)만으로 채운 진료과 → 확인 | PASS |
| 후보 1개 → 카드 1장 + [다른 지역으로 찾기], [예약하기] 뒤 날짜 | PASS |
| 후보 여러 개 → 문구·선택지 기존과 같음 | PASS |
| 이력에서만 나온 병원 → 진료과 확인 → 카드 1장 → [예약하기] 뒤 날짜 | PASS |
| [다른 과 고르기] + 병원이 정해진 경우 → 그 병원 진료과 목록 | ~~PASS~~ → **FAIL (조치 시 발견, 정정)** - 진료과를 비우면 `_clear_stale_links`가 '진료과 변경'으로 보고 병원까지 지워 전체 진료과 목록이 나왔다. 점검 때는 코드 읽기만으로 PASS 처리했다 → AI #315에서 수정 |
| 배포 전 저장 초안(출처 없음) | PASS - 바뀐 값이 없으면 출처를 정하지 않아 묻지 않음(30분 TTL) |
| 라우터 실패 턴의 글 답 | PASS - `flow-continues-fallback`이 `{직전 상태} / 답`으로 잇고, 판정은 원문(rawMessage)으로 |
| 라우터 정상 턴에 글로 "네"만 보냄 | 미확인 - 라우터(Claude)가 예약으로 분류해야 이어진다. API 키 시험 금지라 수용 |
| 확인 카드·취소/변경·중복 차단·시간 칸 | 변경 없음 (기존 4176개 통과) |

**의심 확인 시험 (stub, 확인 질문 뒤 글로 친 답)**

| 답 | 판정 | 결과 |
|---|---|---|
| "예약 말고 다른 과로 할래요" | message | 내과로 병원 검색 ❌ |
| "그래도 다른 과로 예약할래요" | message | 내과로 병원 검색 ❌ |
| "아니요 예약은 다른 과로" | inferred | 다시 확인 ✅ |

## 4. 이슈

| 등급 | ID | 내용 | 영향 | 제안 |
|---|---|---|---|---|
| 🟡 | M-1 | "네" 판정 `re.match(r"\s*(네\|예\|응\|좋아\|맞아\|그래)")`이 앞글자만 본다 - "예약…"·"그래도…"·"응급…"·"좋아하는…"도 '네'로 본다. 위 시험에서 "다른 과로" 답이 내과 진행으로 처리됐다 | 글로 답하는 보호자 화면. 마지막 확인 카드에 진료과가 보여 잘못 예약되지는 않지만, 원치 않는 과의 병원 목록이 뜬다 | 낱말 경계(`(네\|예\|응\|좋아요?\|맞아요?\|그래요?)(?=\s\|$\|[.,!~])`) + 부정·변경 낱말(`말고`·`아니`·`다른`) 있으면 제외. 위 3문장을 테스트로 고정 |
| 🟢 | L-1 | 이력 병원 카드에서 지역이 없으면(`draft.location` 없음) [다른 지역으로 찾기]가 붙지 않아, 버튼만 쓰는 화면에서는 거절할 길이 없다 | 프로필 주소가 없는 피보호자 + 이력 병원 이름일 때만 | 지역이 없으면 [다른 병원 찾기](`OTHER_HOSPITAL_OPTION` 계열)를 붙이거나 지역부터 묻기 |
| 🟢 | L-2 | 추출(LLM)이 `hospital_id`를 직접 주면 이름 조회(`resolve_hospital`)를 건너뛰어, 카드의 이름과 id가 예약 서비스 값과 다를 수 있다(배포 전에도 같은 경로로 바로 쓰였음 - 이번엔 확인을 거치므로 오히려 나아짐) | 낮음 | 이력 출처 병원은 id가 있어도 이름으로 다시 조회 |
| 🟢 | L-3 | 테스트 빈 곳: 보호자 대리(`wardId` 문맥) 경로, 출처 없는 옛 저장 초안, 병원이 정해진 상태의 [다른 과 고르기] | 회귀 감지 | 각 1개씩 추가 |

## 5. 종합 판정

**조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 3. 11/17 실측 문제(이전 대화 진료과 + 후보 1개 자동 선택)는 해소됐고 기존 예약 테스트 전부 통과. M-1은 글 입력 경로에만 있고 마지막 확인 카드가 막아 준다.

## 6. 수정용 커밋 메시지 초안 (AI 저장소)

```
fix: 짐작한 진료과 확인의 '네' 판정을 낱말 단위로 좁히기 (#312 점검 M-1·L-1·L-3)
```

## 7. 조치 (2026-11-17, AI #315 `0c0710c`, gosky 반영)

| ID | 조치 | 고정 테스트 |
|---|---|---|
| M-1 | `_is_plain_yes` - '네·예·응·좋아(요)·맞아(요)·그래(요)·그럼요'가 낱말로 끝날 때만, `말고·아니·다른·싫·바꿔·바꾸`가 있으면 수락 아님 | `test_typed_non_yes_is_not_taken_as_yes`(4문장)·`test_is_plain_yes`(14개) |
| L-1 | 지역을 모르면 병원 카드에 [다른 병원 찾기](`location_confirm __other__`) → 지역을 묻고 그 지역에서 다시 찾음 | `test_history_hospital_card_without_region_offers_other_hospital` |
| L-2 | 이력 출처 병원은 id가 있어도 이름으로 다시 조회, 없으면 버리고 검색 | `test_history_hospital_with_extracted_id_is_resolved_by_name`·`..._unknown_to_service_falls_back_to_search` |
| L-3 | 보호자 대리·옛 저장 초안·병원 고정 [다른 과] 테스트 추가 | `test_guardian_proxy_same_rules`·`test_legacy_stored_draft_without_source_is_not_asked`·`test_pick_other_department_with_hospital_fixed_lists_that_hospital` |
| (추가) | 위 정정 항목: `_clear_stale_links`가 진료과를 *비우기만* 한 경우는 변경으로 보지 않음(새 값으로 바뀔 때만 병원을 비움) | `test_pick_other_department_with_hospital_fixed_lists_that_hospital` |

테스트: gosky 사본 stub, 4218 passed · 16 skipped. 남은 것: 라우터 정상 턴의 글 '네'(API 키 시험 금지로 미확인, 수용).
