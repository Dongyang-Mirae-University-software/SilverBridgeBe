# 받은 연결 요청에 보호자 프로필 사진 추가 (2026-10-28)

## 무엇을
- `GET /api/ward/connection/pending`(피보호자 "받은 요청" 카드) 응답 `PendingConnectionResponse`에 `guardianProfileImage`(String, nullable)를 더했다.
- 값은 보호자 `users.profile_image` 그대로다(카카오 CDN 같은 외부 URL도 저장값 그대로 - 저장 시점에 이미 검증됨). 없으면 `null`.

## 왜
- 프로토타입 v13 피보호자 "내 가족 > 받은 요청" 카드는 보호자의 이름·사진·관계·요청일을 보여 준다. 사진만 응답에 없었다.

## 지킨 것
- 가산 변경: 기존 필드·전화 마스킹·정렬(요청일 최신순)은 그대로, 마이그레이션 없음.
- 노출 수준: 보호자용 연결 목록 `ConnectionResponse.partnerProfileImage`가 PENDING에도 사진을 주는 것과 같다. 수락 전이라 전화(마스킹 외)·주소·이메일·성별·생년월일은 여전히 주지 않는다.
- 추가 조회 없음: `getPendingRequests`가 이미 한 번에 읽은 `guardianMap`의 User에서 꺼낸다(N+1 없음, 테스트로 고정).
- 보호자 행이 없을 때: 기존 `from()`은 보호자 객체를 바로 써서 NPE가 난다. `connection.guardian_id`가 FK CASCADE라 실제로는 생기지 않는 경우여서 이번 가산 변경에서는 바꾸지 않았다.

## 테스트
- `ConnectionServiceTest`: 사진 저장값 그대로 + 보호자 조회 1회 + 마스킹 유지 / 사진 없으면 null.

## FE 전달
- 피보호자 받은 요청 응답에 `guardianProfileImage`(nullable) 추가 - null이면 이니셜·기본 아이콘.

## 실서버 확인 (gosky, 2026-10-28)
- 두 서버 반영 커밋 `358ad92`(PR #393). 테스트 보호자가 테스트 피보호자에게 요청 → 받은 요청 응답에 `guardianProfileImage` 키 있음(테스트 보호자는 사진이 없어 null), 전화 마스킹 유지 → 요청 취소(200)로 원상 복구.
