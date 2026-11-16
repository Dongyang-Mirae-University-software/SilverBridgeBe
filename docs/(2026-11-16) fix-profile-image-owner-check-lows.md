# (2026-11-16) #495 점검 Low 3건 반영 - 프로필 사진 삭제 전 사용 확인 보강

- 브랜치: `fix/profile-image-owner-check-lows` (기준 `dev` `4f996cd5`)
- 근거: `(2026-11-16) audit-profile-image-owner-check.md` L-1~L-3

## PHASE 0 - 전제 vs 실제

| 전제 | 실제 |
|---|---|
| 확인은 `existsByProfileImageEndingWith("/" + 파일명)` 1번 | 맞음 (`UserService.deleteIfUnreferenced`, 사용처 1곳) |
| `ownedFilename` 은 디코딩된 경로 마지막 조각 | 맞음 (`URI.getPath`) |
| 같은 작업 브랜치·문서 | 없음 |

## 변경

| 파일 | 내용 |
|---|---|
| `domain/user/repository/UserRepository.java` | `existsByProfileImageEndingWith` → `existsByProfileImageContaining` (L-1) |
| `domain/user/service/UserService.java` | `isReferencedByAnyUser(파일명)` - `"/" + 파일명` 포함 확인, 경로용 인코딩(`UriUtils.encodePathSegment`)이 다르면 그 이름으로도 확인 (L-2) |
| `src/test/.../UserServiceTest.java` | 기존 6개 새 메서드로, 추가 3개 - 인코딩된 파일명이면 건너뜀, 인코딩이 같으면 조회 1번, 영속화 실패 시 새 파일 삭제 + 원래 예외 전파 (L-3) |
| `프로젝트_설명.txt` | 사진 삭제 문단에 "가리킨다"의 기준 한 줄 |

## 바뀌지 않는 것

사용자 응답·DB 처리, 외부 주소 건너뛰기, 삭제 4경로(교체·실패 되돌리기·삭제·탈퇴 purge), 조회 실패 시 안 지움.

## 판단

- 넓게 맞는 경우: `'/abc.png'` 가 `'/abc.png2'` 같은 다른 파일 주소에도 들어 있으면 그 파일은 지우지 않고 남는다(고아). 남의 파일을 지우는 것보다 안전한 쪽이고, 파일 서버 이름은 업로드마다 새로 받아 사실상 생기지 않는다.
- 같은 경로 보강이라 #495 점검(B) 범위 안 - 추가 점검 불요. QA AUTH-A44 재실행으로 확인.

## 검증

- `./gradlew build` 통과
- QA AUTH-A44 재실행 결과는 PR·노션에 기록
