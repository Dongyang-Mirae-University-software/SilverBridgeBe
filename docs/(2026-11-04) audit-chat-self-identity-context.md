# (2026-11-04) 기능 점검 B - 챗봇 본인 상담 이름·전화 서버 값 (BE #440)

## 대상
- `ChatRelayService.sanitizedContext` / `send` / `sendAsGuardian` (머지 `011c0b3`, vkcs CD 성공·gosky dev 배포)
- 세 경로: 피보호자 본인 상담 · 보호자 본인 상담(wardId 없음, 결정 A) · 보호자 대리 상담

## 결과: PASS · 🔴 0 · 🟠 0 · 🟡 0 · 🟢 3

### 코드 확인
| 항목 | 결과 |
|---|---|
| 클라이언트 `name`·`phone` | 세 경로 모두 먼저 지움 (`IDENTITY_KEYS`) |
| 대리 상담 순서 | 킬 스위치 → 본문 → 속도 제한 → 형식 → ACTIVE 연결 그대로, 본인 조회는 wardId가 없을 때만 |
| 본인 조회 위치 | `admit`(속도 제한) 뒤 PK 1회 - 위조 요청으로 DB 조회를 늘릴 수 없음 |
| 로그 | 새 로그 없음, `ChatRelayLogGuardTest` 통과 |
| 기록 응답 | `contextJson`은 `ChatLogProjection` 허용 목록 밖이라 FE로 안 나감 |
| AI 모델 입력 | MedGemma는 나이·성별만(AI #107) - 이름·전화는 예약 초안·계정에만 쓰임 |
| 8KB 상한 | 최종 context 기준 그대로, 이름·전화 추가분은 수십 바이트 |

### gosky 실측 (시험 피보호자, 2026-11-04 14시대)
- `POST /api/ward/chat`에 위조 `name`·`phone`을 넣고 "내일 오전에 내과 진료 예약하고 싶어요" → 병원 선택 → 시간 선택 → `reservation_confirm`(확정은 누르지 않음, 예약 미생성).
- 3턴 동안 `patient_name`·`phone` 질문 없음, 확인 화면의 환자 = DB 이름, 연락처 끝 4자리 = DB 전화, 위조 이름·전화는 어느 응답에도 없음.
- 실측 스크립트가 초기에 선택지를 잘못 골라 같은 세션에서 지역 선택을 7번 반복하고 속도 제한(429)에 한 번 걸렸다 - 서버 결함 아님.

### 🟢 참고 → 2026-11-04 전부 반영 (사용자 결정 1A·2A)
- L-1: 수용한 한계로 확정, `policy-chat.md` 규칙 ⑦에 기록 (코드 변경 없음)
- L-2: FE가 context를 `role`·`wardId`로 줄임 (FE PR)
- L-3: BE `PROFILE_KEYS` - 생년월일·나이·성별·주소도 DB 값, 나머지 프로필 키는 제거 (BE PR)

원래 기록:
- **L-1** AI 예약 계정(`sb-{userId}`)이 이전에 클라이언트 이름으로 만들어졌다면 계정 프로필 이름은 그대로일 수 있다. 예약 건의 환자명은 매 턴 context 값이라 영향 없음.
- **L-2** FE가 `context.name`·`phone`을 계속 보내도 무시된다. 보내지 않게 정리하는 것은 FE 선택(동작 영향 없음).
- **L-3** `birthDate`·`email`·`address` 등 나머지 프로필 키는 아직 클라이언트 값 그대로 - 같은 방식 적용 후보.
