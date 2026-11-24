# (2026-11-24) 어르신 호칭(wardRelation) 기능 점검 (템플릿 B)

- 대상: BE #524 `4b268c17`(V74) · FE #344 `64dbec7` (앞선 표시 정리 FE #342 포함)
- 기준: 구현 보고의 빌드·테스트 결과(BE 단위 2961·vkcs 통합 통과 / FE 단위 740·브라우저 856), 코드는 dev·develop 최신을 직접 읽음
- 결론: **PASS** (🔴 0 · 🟠 0 · 🟡 1 · 🟢 3) - 🟡 M-1은 이 문서와 함께 정책 문서로 조치

## 무엇이 바뀌었나

| 구분 | 내용 |
|---|---|
| 의미 | `relation` = 보호자가 어르신에게 누구인지(아들·딸…, 어르신 화면용) / `wardRelation` = 어르신이 보호자에게 누구인지(엄마·할머니…, 보호자 화면용) |
| BE | `connection.ward_relation VARCHAR(10)` NULL(V74), 요청 선택값(`@VisibleText`·10자, 서비스 `TextSanitizer` 후 빈 값 NULL), `ConnectionResponse.wardRelation`은 `fromGuardianView`만 |
| FE | 요청 화면 ③ "어르신은 나의…"(12개, 필수), 선택 칩 6곳·연결된 가족 카드에 `wardCallName()`(빈 값·"기타" → "어르신") |

## A. 보안·인가 - PASS

| 항목 | 결과 | 근거 |
|---|---|---|
| 입력 검증 | PASS | 보이지 않는 문자만·11자 → 400(`ConnectionRequestDtoValidationTest`), 정리 후 빈 값 NULL(`ConnectionServiceTest`). NFC 정규화·정리는 길이를 늘리지 않아 DB 10자를 넘지 않음 |
| 노출 범위 | PASS | `fromWardView`는 null 고정(`ConnectionResponseTest`). `ActiveWardSummary`(AI 챗 중계)·관리자 회원·알림 이력·연결 요청 알림 문구는 `wardRelation`을 읽지 않음(grep: 사용처 5파일뿐) |
| 로그 | PASS | relation·wardRelation을 남기는 로그 없음 |
| 연결 생성 경로 | PASS | 일반 요청만 값 저장. 관리자 강제 연결은 새 행이면 NULL(빌더에 값 없음), 대기 요청 승격이면 보호자가 고른 값 유지. 거절 뒤 재요청은 새 행이라 새 값 |

## B. 기능 정합성 - PASS

- 방향 일관성: BE 엔티티·DTO·Swagger 주석, FE `wardCallName`·`WardSelectorTabs` 주석, 요청 화면 문구("나는 어르신의…"/"어르신은 나의…")가 같은 방향으로 적혀 있음.
- 보호자 화면에서 `relation`을 어르신 호칭처럼 쓰는 곳: 없음. 남은 사용처는 "딸로 연결됨"(카드)·"아들로 요청"(요청 내역) - 둘 다 보호자 자신을 가리키는 올바른 방향.
- 피보호자 화면: `relation`을 "(딸)"로 그대로 씀(보호자 목록·대기 요청·SOS 전화) - 올바른 방향, 변경 없음.
- 대체 규칙: 빈 값·공백·"기타"·기존 연결 → "어르신"(`connectionView.test.ts`), 카드 "기타로 연결됨" 숨김.
- 요청 후 두 선택 모두 초기화(브라우저 테스트).

## C. 구조·계약 - PASS

- 응답은 필드 추가뿐(nullable) - 기존 클라이언트 영향 없음. 요청은 선택값이라 옛 화면 요청도 성공.
- V74는 가산형(되돌리기 = 칸 삭제), gosky·vkcs 적용 로그 확인. 엔티티 매핑은 vkcs 통합 테스트(실 PostgreSQL)로 확인.

## D. 테스트 - PASS (🟢 L-3)

BE: DTO 검증 3종, 서비스 저장(정리·빈 값), 응답(보호자 뷰만·기존 연결 null). FE: 두 그룹 필수·전송 값·초기화, 카드 호칭 3경우, `wardCallName`, 375·768·1280 렌더링 확인.

## 이슈

| ID | 등급 | 내용 | 조치 |
|---|---|---|---|
| M-1 | 🟡 | `policy-connection.md`에 두 관계의 방향 규칙이 없어, 다음 작업에서 `relation`을 보호자 화면 어르신 호칭으로 다시 쓸 수 있음(#342 이전 상태로 회귀) | 이 점검과 함께 정책 문서에 규칙 추가 |
| L-1 | 🟢 | 기존 연결은 호칭을 고를 방법이 없음(연결을 끊고 다시 요청해야 함) | 수용 - 사용자 결정 "기존 연결은 어르신으로 표시". 필요해지면 보호자용 호칭 수정 API를 별도 작업으로 |
| L-2 | 🟢 | prod는 BE에 V74가 없음. prod FE를 develop으로 먼저 다시 빌드하면 요청에 `wardRelation`이 실려 가지만 Boot 기본 설정이 모르는 필드를 무시해 요청은 성공하고, 화면은 "어르신"으로 보임(깨지지 않음) | prod 반영 시 BE 먼저 |
| L-3 | 🟢 | 관리자 강제 연결이 새 행을 만들 때 `wardRelation`이 NULL임을 고정하는 테스트가 없음(빌더 기본값으로 동작) | 선택 - 강제 연결 테스트에 한 줄 추가 가능 |
