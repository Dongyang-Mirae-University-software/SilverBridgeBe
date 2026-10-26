# 페이지 번호 보정(`PageOffsets`) 영향 범위 점검 (2026-10-26)

- 변경: BE #383 (`3e589e3`). `global/response/PageOffsets.clampPage`, `AdminPaging.of`, 보호자 SOS·이상감지 이력
- 템플릿: C(영향 범위). 사용처 전수 grep, 판정, FE 사용처 대조. #383 머지 직후 gosky 실측 결과를 함께 실었다
- 코드 수정 없음

## 종합 판정: ✅ PASS
🔴 0 · 🟠 0 · 🟡 0 · 🟢 2 (모두 수용)

## PHASE 0. 변경점과 불변식
- **바뀐 것**: 사용자가 보낸 `page`는 `PageOffsets.clampPage(page, size)`를 거친다. 0 미만은 0이 되고, `page > Integer.MAX_VALUE / size`면 `Integer.MAX_VALUE / size`가 된다.
- **불변식**:
  1. 사용자가 보낸 `page`로 offset이 int 범위를 넘는 `Pageable`이 만들어지지 않는다.
  2. `page ≤ Integer.MAX_VALUE / size`(정상 범위)에서는 동작이 이전과 같다.

## PHASE A. 사용처 전수
- 사용자 `page`를 받는 곳: **12곳**. 모두 보정을 거친다.
- `PageRequest.of(0, …)` 고정값 5곳: 해당 없음. 클립 청소 2곳, 최신 1건 조회 2곳, 리포지토리 주석 1곳이다.
- 컨트롤러가 `Pageable`(`@PageableDefault`)을 직접 받는 곳: 0곳.
- 네이티브 `OFFSET`·`LIMIT`를 직접 만드는 쿼리: 0곳.

## PHASE B. 사용처별 판정

| 도메인 | 위치 | 경로 | 불변식 ①② | 근거 |
|---|---|---|---|---|
| 알림(관리자) | `AdminNotificationService:67` | `AdminPaging.of` → JPQL `Page` | PASS | `PageResponse.of` |
| 이상감지(관리자) | `AdminAnomalyService:80` | `AdminPaging.of` → `Page` | PASS | 같음 |
| 이상감지(보호자) | `GuardianAnomalyService:88` | `PageOffsets` 직접 → `Page` | PASS | `normalizeSize` 최소 1이라 보정이 늘 작동. 연결 없음 분기 `Page.empty(pageable)`도 같은 `pageable` |
| SOS(보호자) | `GuardianSosService:73` | `PageOffsets` 직접 → `Page` | PASS | 위와 같음. `SosHistoryPage.of`가 `getNumber()`를 실음 |
| 공지(관리자) | `AdminAnnouncementService:51` | `AdminPaging.of` → `Page` | PASS | |
| 임시저장(관리자) | `AdminAnnouncementDraftService:52` | 같음 | PASS | |
| 공지(공개) | `AnnouncementService:37` | `AdminPaging.of` → `getContent()` 배열 | PASS | 응답에 page 칸 없음 |
| 문의(보호자 본인) | `InquiryService:49` | `AdminPaging.of` → `List` 반환 리포지토리 | PASS | `List` + `Pageable`도 Spring Data가 같은 offset 검사를 한다. 보정으로 막힘 |
| 문의(관리자) | `AdminInquiryService:57` | `AdminPaging.of` → `Page` | PASS | 건수 3종은 별도 count라 무관 |
| 회원(관리자) | `AdminUserService:92` | 같음 | PASS | |
| 카메라(관리자) | `AdminCameraService:83` | 메모리 `subList`, offset `Math.min` | PASS | 원래도 예외가 나지 않던 경로. 보정 후에도 빈 목록·정확한 전체 건수 |
| 제휴 병원(관리자, #382) | `AdminHospitalService:78` | 카메라와 같은 메모리 방식 | PASS | #382가 #383보다 먼저 머지돼 자동으로 보정이 적용됨 |

- 정상 범위 동작 동일(불변식 ②): `clampPage`는 `page ≤ MAX/size`면 입력을 그대로 돌려준다. 12곳 모두 정상 범위 요청의 결과가 바뀌지 않는다. 바뀌는 것은 원래 500이던 요청뿐이다.
- 응답의 `page` 칸: 보정 뒤 값이 내려간다(예: `page=100000000&size=50` → `42949672`). `totalElements`·`totalPages`는 정확하고 `last=true`다.
- FE 사용처(`SilverBridgeFe` c335ef1):
  - `Pagination`·`getPageWindow`는 화면 상태의 페이지 번호와 응답의 `totalPages`로 그린다.
  - 요청하는 페이지는 `totalPages` 범위 안이라 보정 구간에 들어가지 않는다.
  - 관리자 웹 FE는 아직 없다.
- 실측(gosky, #383 배포 직후, 테스트 보호자 계정, `page=100000000`):

  | 경로 | 결과 |
  |---|---|
  | 공개 공지 목록 | 200, 0건 |
  | 보호자 SOS 이력 | 200, `page=42949672`, `totalElements=28`, content 0 |
  | 보호자 이상감지 이력 | 200, `page=42949672`, `totalElements=45`, content 0 |
  | 관리자 공지 목록·`/summary` | 403 (보호자 토큰이라 정상) |

## PHASE C. 테스트로 고정되는가
- 공통 지점(`AdminPaging.of`)은 `AdminSupportTest`, 직접 호출 2곳은 `GuardianSosServiceTest`·`GuardianAnomalyHistoryFilterTest`가 고정한다.
- 경계값은 `PageOffsetsTest`(size 1·20·50)가 고정한다.
- 실 DB에서는 `AnnouncementTitleSearchIntegrationTest`(size 50, offset 2147483600)가 확인한다.
- 변이 시험(보정 제거, 2곳 각각)이 모두 검출됐다(#383).
- 새 페이지 목록이 `AdminPaging`·`PageOffsets`를 빼먹는 것을 기계적으로 막는 가드 테스트는 두지 않았다. 사용처가 서비스에 흩어져 있어 grep 기반 가드는 오탐이 많다. 새 목록을 만들 때 `AdminPaging.of`를 쓰는 것이 관례다(L-2).

## 이슈 (🟢, 모두 수용)

### 🟢 L-1 보정된 `page`가 응답에 내려간다
- 요청한 값이 아니라 보정된 값이 `page` 칸에 실린다.
- 범위를 한참 벗어난 요청만 해당하고, FE는 이 칸으로 다음 요청을 만들지 않는다.
- 수용한다. 요청값을 그대로 돌려주려면 `PageResponse`에 별도 칸이 필요하다.

### 🟢 L-2 새 페이지 목록의 보정 누락은 관례로만 막힌다
- `PageRequest.of(page, …)`를 직접 만드는 새 코드는 같은 500을 다시 낼 수 있다.
- 지금은 사용자 page를 받는 곳이 모두 보정을 거친다.
- 새 목록은 `AdminPaging.of`나 `PageOffsets.clampPage`를 쓴다(`AdminPaging`·`PageOffsets` Javadoc에 이유 기록).

## 참고
- size 1에서 offset이 정확히 `Integer.MAX_VALUE`인 경우는 실 DB로 확인하지 않았다.
- PostgreSQL에서 Hibernate는 offset과 fetch를 따로 바인딩해 두 값을 더하지 않으므로 넘침이 생기지 않는다.

## 후속 조치 (2026-10-26, `test/page-clamp-guard`)

| 이슈 | 조치 |
|---|---|
| L-1 | `PageResponse`·`SosHistoryPage`의 `page` Swagger 설명을 "실제 적용된 페이지 번호(0-based). offset이 int 범위를 넘는 요청은 마지막 가능 페이지로 보정된 값"으로 고쳤다(사용자 결정 A - 동작 그대로, 요청값을 되돌려주지 않음). 응답의 page는 실제 offset과 맞는 값이다 |
| L-2 | 소스 스캔 가드 `PageRequestClampGuardTest`. `src/main/java`에서 `PageRequest.of(`의 첫 인자가 `0`이나 `PageOffsets.clampPage(`가 아니면 실패한다(`AdminPaging.java` 제외). `.withPage(`·`PageRequest.ofSize(`·`Pageable.ofSize(`도 실패로 잡고, 실패 메시지에 `file:line`과 안내를 넣는다. 스캔이 사용처를 실제로 찾았는지(보정 2곳 이상·고정 0 4곳 이상)와 `AdminPaging.of` 2개 메서드의 보정도 고정한다 |

- 변이 시험 4종이 모두 검출됐다: 보호자 SOS 보정 제거, 새 목록이 `PageRequest.of(page, size)` 직접 생성, `AdminPaging` 보정 제거, `withPage`로 우회.
- `./gradlew build` 통과.
