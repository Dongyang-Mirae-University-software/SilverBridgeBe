# (2026-11-04) 기능 점검 B - 챗봇 환자 프로필 서버 값 (BE #444·#447, FE #213)

## 대상
- BE #444 `beb16f5`: `PROFILE_KEYS` 클라이언트 값 제거, 이름·전화·생년월일·나이·성별·주소를 환자 DB 값으로
- BE #447 `7104ff7`: 검색할 수 있는 주소만 보내기(`isSearchableAddress`) - 이 점검 중 찾은 퇴행 수정
- FE #213 `dee9f62`: 챗봇 context를 `{ role, wardId }`로 축소
- 반영: vkcs CD 성공(`7104ff7`), gosky dev BE `7104ff7`·FE dev `dee9f62`

## 결과: PASS · 🔴 0 · 🟠 0 · 🟡 1(점검 중 조치) · 🟢 1

### 🟡 M-1 자리표시·비정상 주소가 AI 병원 검색을 0건으로 만듦 → BE #447로 조치
- #444가 DB 주소를 그대로 넣었고, AI는 `location`이 없으면 주소 **원문**으로 검색한다(`reservation_orchestrator` 381·553행).
- gosky 시험 피보호자 주소가 문자열 `<주소>`라 검색 0건 → 지역 선택지 대신 "찾지 못했습니다"(전에는 지역 선택지). gosky 활성 회원 19명 중 3명이 시·도로 시작하지 않는 주소.
- 조치: "시·도 + 시·군·구"(세종은 시·도만)로 시작할 때만 `address`를 보낸다. 재실측: 같은 요청에 `location_sido` 선택지 6개, 위조 주소 없음.

### 확인한 것
| 항목 | 결과 |
|---|---|
| 위조 프로필 키(이름·나이·성별·생년월일·주소·patient*·relation) | gosky 실측·테스트 모두 무시됨 |
| AI 상담 기록 `userContext`(AI DB 읽기 전용, 키 이름만) | #447 뒤 `age,birthDate,gender,name,phone,role` - 자리표시 주소 없음. #444~#447 사이 3건에는 `<주소>`가 남음(무해) |
| MedGemma 나이·성별 | AI `_medgemma_context`가 `userContext`의 나이(없으면 생년월일)·성별로 만든다 - 피보호자 본인 상담에도 처음 들어감 |
| FE context | 브라우저 테스트가 대리 상담 `{ role: 'GUARDIAN', wardId }` 정확히 일치로 고정 |

### 🟢 L-1 보호자 화면 실측 → 2026-11-04 15:01 완료
- 새 시험 보호자(`pVFG3u`, env 15:00 갱신)로 devdmu 보호자 챗봇에서 1회 전송: 브라우저 요청 context 키 = `role`·`wardId`뿐, BE 연결 검증 통과(`[IDOR-ATTEMPT]` 없음, 7.9초), AI 기록 `userContext` 키 = `address,age,birthDate,gender,name,phone,role,wardId`(BE가 피보호자 DB 값으로 채움).

원래 기록 - 보호자 화면 실측 미실시
- 시험 보호자(`aB3x9A`) 로그인이 `INVALID_CREDENTIALS`(14:30, 1회, 재시도 안 함). env는 10/30 값이고 계정은 ACTIVE·11/04 10:59 로그인 기록 - 원래 주인이 비밀번호를 바꾼 것으로 보인다. 새 자격이 생기면 보호자 대리 상담 화면에서 요청 context 키만 다시 본다.
