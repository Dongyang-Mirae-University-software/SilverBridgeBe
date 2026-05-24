# 프로필 이미지 삭제 API 신규 구현

- **작업 일자**: 2026-05-24
- **작업자**: Claude Code
- **브랜치**: `feature/profile-image-delete`
- **엔드포인트**: `DELETE /api/user/me/image`

---

## 1. 배경

작업 요청 배경에는 *"`프로젝트_설명.txt`에 `DELETE /api/users/me/profile-image`가 명시되어 있으나 미구현 상태"* 라고 되어 있었다.

**그러나 실제 문서 내용은 정반대였다.** `프로젝트_설명.txt` 3-5 섹션(갱신 전)은:

```
프로필 이미지:
  PATCH /api/user/me/image    → 이미지 교체 (...). 교체 시 기존 파일 자동 삭제
  ※ 별도 이미지 삭제 엔드포인트는 없음 (교체만 지원)
```

즉 (1) **삭제 엔드포인트가 의도적으로 없다**고 적혀 있었고, (2) 경로 컨벤션 주석(line 276–279)에 `/api/users/*`(복수형)은 **오기였다**고 명시되어 있었다.

따라서 본 작업은 *"문서엔 있는데 빠진 API를 채우는"* 것이 아니라 **신규 기능 추가 결정**이며, 경로도 컨벤션(`/api/user`, 단수)에 맞춰 **`DELETE /api/user/me/image`** 로 구현했다. 문서 3-5도 "삭제 없음" → "삭제 추가"로 갱신했다.

> 이 전제 정정을 PHASE 0 보고에서 먼저 제기하고, 사용자 승인(① 신규 추가 + 문서 갱신 / ② 경로 `/api/user/me/image` / ③ 응답 `ApiResponse<Void>` / ④ `@Slf4j` 추가) 후 진행했다.

---

## 2. PHASE 0 파악 결과

| 항목 | 결과 |
|------|------|
| 업로드/교체 컨트롤러 | `UserController.java` — `@PatchMapping("/me/image")`, 클래스 베이스 `@RequestMapping("/api/user")`, `@AuthenticationPrincipal String userId` |
| 업로드/교체 서비스 | `UserService.updateProfileImage()` |
| **교체 시 자동 삭제 로직** | `oldUrl=getProfileImage()` → `upload()` → `updateProfileImage(newUrl)` → **`fileServerClient.delete(oldUrl)`** (성공 후 fire-and-forget) |
| **FileServerClient 삭제 메서드** | **이미 존재** — `void delete(String fileUrl)`. null/blank 早期 return, **모든 예외를 삼키고 WARN 로그만** 남김(throw 안 함). 신규 추가 불필요, 재사용 |
| 엔티티 처리 | `User.updateProfileImage(String)` — `null` 전달 시 `profile_image=NULL`. 그대로 사용 |
| 삭제 실패 처리 패턴 | `delete()` 내부에서 `log.warn("파일 서버 삭제 실패 (url=...)")` → **파일 서버 실패 WARN은 이미 클라이언트 레벨에서 처리됨** |
| 테스트 컨벤션 | `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks` + AssertJ + 한글 `@DisplayName`, `User.builder()` 헬퍼. 순수 단위 테스트(MockMvc 미사용) |
| 응답 컨벤션 | 형제 DELETE(`withdraw`)·`changePassword`는 `ApiResponse.ok("메시지")` → `ApiResponse<Void>` |

**재사용**: `fileServerClient.delete(oldUrl)` 한 줄이 곧 "교체 시 자동 삭제"의 핵심 primitive(이미 null-safe·non-throwing)이며, 삭제 API도 동일하게 호출. 1줄짜리 null-safe 호출이라 별도 private 헬퍼 추출은 이득이 적어 추출하지 않고 동일 패턴 유지.

---

## 3. PHASE 2 구현 내용

### 동작 흐름 (`UserService.deleteProfileImage`)

1. INFO 로그(삭제 요청 수신, userId)
2. 사용자 조회 — 없으면 `USER_NOT_FOUND`(404)
3. `oldImageUrl = user.getProfileImage()`
4. **NULL/blank → 멱등 no-op**: INFO 로그 후 `false` 반환(200, 삭제 대상 없음)
5. 값 있으면:
   - `user.updateProfileImage(null)` — **DB를 먼저 비움(진실의 원천)**
   - `fileServerClient.delete(oldImageUrl)` — fire-and-forget(실패해도 throw 안 함, WARN은 클라이언트가 기록)
   - INFO 로그(삭제 완료) 후 `true` 반환
6. 컨트롤러가 반환값(`boolean`)으로 안내 메시지 분기 → `ApiResponse<Void>` (둘 다 200):
   - `true`(실제 삭제): `"프로필 이미지가 삭제되었습니다."`
   - `false`(이미 없음): `"설정된 프로필 이미지가 없어 기본 이미지를 사용 중입니다."` — 사용자에게 *현재 기본 이미지 상태*임을 명시 (시니어 타겟 UX)

### 핵심 정책

- **멱등성**: 여러 번 호출해도 동일 결과(이미 없으면 no-op + 200).
- **DB가 진실의 원천**: 파일 서버 삭제 결과와 무관하게 `profile_image=NULL` 커밋. (`delete()`가 예외를 던지지 않으므로 트랜잭션은 항상 NULL로 커밋됨)
- **내부 정보 비노출**: 서비스 로그는 `userId`만 기록, 파일 경로/URL은 raw 로깅하지 않음. (파일 서버 통신 실패 시의 url 포함 WARN은 기존 `FileServerClient` 공유 코드의 동작으로, 본 작업 범위에서 변경하지 않음 — url은 사용자 PII 아님)

---

## 4. 추가/수정된 파일 목록

| 파일 | 변경 |
|------|------|
| `domain/user/controller/UserController.java` | `@DeleteMapping("/me/image") deleteProfileImage()` 추가 (Swagger `@Operation`/`@ApiResponses` 포함) |
| `domain/user/service/UserService.java` | `@Slf4j` + `deleteProfileImage(String userId)` 추가 |
| `domain/user/service/UserServiceTest.java` | 단위 테스트 4건 + static import 2건(`assertThatCode`, `verifyNoInteractions`) |
| `global/client/FileServerClient.java` | **무변경** (`delete(String)` 기존 재사용) |
| `domain/user/entity/User.java` | **무변경** (`updateProfileImage(null)` 기존 재사용) |
| `프로젝트_설명.txt` | 3-5 섹션 갱신: 삭제 엔드포인트 추가(2026-05-24 명시) |
| `docs/progress.md` | `[2026-05-24]` 항목 추가 |
| `docs/(2026-05-24) feature-profile-image-delete.md` | 본 문서(신규) |

> `CLAUDE.md`는 프로필 이미지 관련 정책 섹션이 없어(§9는 비밀번호 재설정 전용) 갱신하지 않음.

---

## 5. 단위 테스트 결과

`UserServiceTest`에 추가한 4건:

| 테스트 | 검증 |
|--------|------|
| 프로필 이미지가 있는 사용자 삭제 → **true** + DB NULL + 파일 서버 삭제 호출 | `deleted==true`, `profileImage==null`, `fileServerClient.delete(url)` 호출 |
| 프로필 이미지가 없는 사용자 삭제 → **false**(멱등 no-op), 파일 서버 미호출 | `deleted==false`, `profileImage==null` 유지, `verifyNoInteractions(fileServerClient)` |
| 파일 서버 삭제 결과와 무관하게 DB는 NULL 처리(예외 비전파) | `assertThatCode(...).doesNotThrowAnyException()`, `profileImage==null`, delete 호출 |
| 존재하지 않는 사용자 삭제 → USER_NOT_FOUND | `ErrorCode.USER_NOT_FOUND`, 파일 서버 미호출 |

> 컨트롤러가 서비스 반환값(`boolean`)으로 안내 메시지를 분기한다(실제 삭제 / 이미 기본 이미지). 둘 다 200.

```
./gradlew compileJava compileTestJava --no-daemon   → 성공
./gradlew test --tests "...UserServiceTest"         → BUILD SUCCESSFUL
  tests=11  skipped=0  failures=0  errors=0  (기존 7 + 신규 4)
```

---

## 6. 멱등성 검증 결과

- **이미지 없는 사용자**: `oldImageUrl == null` 분기 → DB·파일 서버 모두 변경 없이 200. (`deleteProfileImage_이미지없음_멱등` — `verifyNoInteractions(fileServerClient)`로 부작용 없음 확인)
- **반복 호출**: 1회차에 `profile_image=NULL` 처리 후, 2회차부터는 위 "이미지 없음" 분기로 진입 → 동일하게 200·no-op. 호출 횟수와 무관하게 최종 상태(NULL)·응답(200) 일치.
- **파일 서버 실패 시**: DB는 항상 NULL로 처리되어 재호출해도 멱등성 유지(파일 서버 잔여 파일이 있더라도 DB 기준 상태는 동일).