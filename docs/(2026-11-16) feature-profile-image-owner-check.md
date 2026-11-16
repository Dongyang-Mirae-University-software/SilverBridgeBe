# (2026-11-16) 프로필 사진 삭제 전 "다른 회원이 쓰는 파일" 확인 (AUTH-A44)

- 브랜치: `feature/profile-image-owner-check` (기준 `dev` `d8fc29fa`)
- 근거: 최종 QA AUTH-A44 (노션 최종 QA - BE+AI), `audit-index.md` 백로그
- 설계 결정(2026-11-16): (가) 마이그레이션 없이 "지우기 직전에 다른 회원 프로필이 아직 같은 파일을 가리키면 지우지 않는다"

## PHASE 0 - 전제 vs 실제

| 전제 | 실제 |
|---|---|
| 파일 서버는 프로필 사진에만 쓴다 | 맞음 - `FileServerClient` 사용처는 `UserService` 하나 |
| 지우는 곳 | 사진 교체 후 옛 파일(`updateProfileImage`), 사진 삭제(`deleteProfileImage`, 커밋 뒤), 탈퇴 purge(`purgeWithdrawnUser`, 커밋 뒤), 업로드 뒤 영속화 실패 되돌리기 - 4곳 |
| 파일명에 업로더 정보가 있는가 | 없음 - 파일 서버가 준 주소의 마지막 조각만 있음 → (나) 주인 표 방식은 마이그레이션·기존 데이터 채우기 필요 |
| `ownedFilename` | 우리 파일 서버 호스트일 때 파일명, 아니면 null (package-private 이었음) |

## 변경

| 파일 | 내용 |
|---|---|
| `domain/user/service/UserService.java` | 새 `deleteIfUnreferenced(url)` - 우리 파일 서버 주소면 `existsByProfileImageEndingWith("/" + 파일명)` 로 남은 회원 확인, 있으면 WARN(파일명 끝 6자만) 후 건너뜀, 조회 실패도 건너뜀. 위 4곳이 모두 이 메서드를 거친다 |
| `domain/user/repository/UserRepository.java` | `existsByProfileImageEndingWith(String)` (LIKE 이스케이프는 Spring Data 기본) |
| `global/client/FileServerClient.java` | `ownedFilename` public 으로 (동작 그대로) |
| `global/exception/ErrorCode.java`, `GlobalExceptionHandler.java`, `companion/controller/WardCompanionController.java` | 말벗 업로드의 "서블릿 상한 10MB" 코드 주석·Swagger 를 12MB 로 정정(사용자 문구는 그대로). #494 점검 L-1 은 정책 문서 2개만 고쳐서 남은 코드 쪽 |
| `프로젝트_설명.txt` | 사진 삭제 문단을 새 동작으로 |
| 테스트 | `UserServiceTest` 6개 추가 - 삭제/교체/purge 에서 다른 회원이 쓰면 건너뜀, 아무도 안 쓰면 삭제, 외부 주소는 조회 없음, 조회 실패면 건너뜀 |

## 바뀌지 않는 것

- 사용자 응답(200·메시지)과 DB 처리(사진 칸 NULL, 탈퇴 행 삭제) - 파일 서버 삭제만 건너뛸 수 있다
- 외부 주소(카카오 CDN 등)는 원래대로 지우지 않음
- 업로드·크기 검사·속도 제한

## 한계

- 같은 파일을 가리키던 회원이 없어진 뒤에는 지울 사람이 없어 고아 파일이 남을 수 있다(남의 파일을 지우는 것보다 안전한 쪽). 파일명은 업로드마다 새로 받으므로 정상 흐름에서는 생기지 않는다.
- `profile_image` 는 색인이 없어 LIKE 조회가 전체 훑기다. 회원 수가 적고 사진 삭제·교체·탈퇴 때만 1번이라 지금은 문제 없음.

## 검증

- `./gradlew build` 통과
- QA `auth-p1b-profile.spec.ts` 의 `OWNER_CHECK_IMPLEMENTED` 를 켜고 AUTH-A44 재실행 (결과는 PR·노션에 기록)
