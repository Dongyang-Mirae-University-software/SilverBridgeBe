# 보호자 피보호자 목록 - status 쿼리 파라미터 지원 (2026-09-07)

> `GET /api/guardian/connection/select`에 상태 필터를 추가한다. 프론트의 상태 탭이 클라이언트 필터링
> 대신 서버 기준으로 목록을 받도록 하기 위한 변경이다. **스키마 변경 없음.**

## 1. 발단 - FE 제안

프론트에서 "탭은 UI 상태지만 데이터 기준은 백엔드가 가져야 한다"는 취지로 `status` 쿼리 파라미터를
요청했다. 근거로 든 문제는 페이지네이션 도입 시 개수 불일치, 탭별 count와 목록 불일치,
React Query 캐시가 "전체 데이터" 기준으로 묶여 탭 전환 시 stale data가 보이는 것이었다.

### 착수 전 확인한 사실 (FE 리포 `SilverBridgeFe`, gosky `e160256` / 로컬 동일 커밋)

- **FE 프록시가 쿼리스트링을 그대로 넘긴다**: `src/app/api/[...path]/route.ts`가 `request.nextUrl.search`를
  백엔드 URL에 붙여 전달한다. 백엔드 파라미터 추가만으로 끝나며 프록시 수정이 필요 없다.
- **제안서가 말한 "보호자 SOS 페이지"와 "상태 탭"은 아직 FE에 없다**: `src/app/(guardian)/guardian/` 아래에
  `sos` 디렉터리가 없고, `GuardianWardsPanel`의 현재 탭은 `list | register`(목록/등록)다. 즉 지금 깨지고
  있는 화면은 없고 **앞으로 만들 화면을 위한 선반영**이다.
- **캐시 지적은 사실이다**: `guardianConnectionsQueryKey = ['guardian-connections']` 단일 키를 세 컴포넌트가
  공유하고 각자 클라이언트에서 필터링한다(`GuardianWardsPanel`·`GuardianWardRegisterPanel`·`GuardianDashboardContent`).
- **상태별 노출 제어는 이미 백엔드가 한다**: `ConnectionResponse.fromGuardianView`가 `status == ACTIVE`일 때만
  전화번호·주소를 채운다. FE 필터링과 무관하게 안전하다.
- **페이지네이션은 FE 어디에도 없다**: 제안서도 "들어가면"이라는 가정이었다.

## 2. 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 파라미터명 | `status` | `AdminInquiryController`의 `@RequestParam(required=false) InquiryStatus status` 선례 |
| 기본값 | 생략 = `ALL`(ACTIVE + PENDING) | 기존 FE 3개 컴포넌트가 파라미터 없이 호출 중 - 하위호환 |
| 허용 값 | 전용 enum `WardListFilter` (ACTIVE·PENDING·ALL) | 아래 참조 |
| `ALL` 정의 | ACTIVE + PENDING (종료 이력 제외) | `/requests`와 역할 중복 방지 |
| 잘못된 값 | 400 (`handleTypeMismatch` 재사용) | 기존 핸들러가 이미 처리 |
| 탭 count | 범위 밖 | 아래 참조 |
| 페이지네이션 | 범위 밖 | FE 미사용 |

### `ConnectionStatus`를 그대로 받지 않은 이유

이 엔드포인트는 진행 중인 연결(ACTIVE·PENDING)만 다룬다. 전체 상태 enum을 파라미터로 받으면
`status=REFUSED`가 **유효한 enum이라 400이 아니라 빈 배열**로 응답된다. 호출자는 이를 "거절된 연결이 0건"
으로 읽지만 실제 뜻은 "이 API가 그 상태를 다루지 않는다"라서 **정확히 반대 해석**이 된다.

실제로 FE의 `sortGuardianConnections`에는 이미 `REFUSED`·`CANCELLED`·`DISCONNECTED` 정렬 순서가 들어 있다
(현재는 `/select`가 내려주지 않아 도달 불가한 분기). 이 오해가 일어날 소지가 실재한다.

전용 enum이면 그 값들이 **구조적으로 400**이 되고, 종료된 이력은 `/api/guardian/connection/requests`가
담당한다는 경계가 명확해진다. `WardListFilter`가 스스로 `ConnectionStatus` 목록을 알고 있어
상태 결정이 connection 도메인 안에 남는다.

### 탭 count를 범위에서 뺀 이유

`GuardianWardsPanel` 헤더의 "연결됨 N명 · 대기 N건"은 현재 한 응답에서 둘 다 계산한다.
탭별로 호출을 쪼개면 헤더가 두 번 호출해야 하므로 **status 파라미터만으로는 count 문제가 해결되지 않고
오히려 나빠진다**. 카운트 전용 응답은 별건으로 다룬다. 당장은 헤더가 `ALL`(또는 생략) 호출을 유지하면 된다.

## 3. 변경 파일

| 파일 | 변경 |
|---|---|
| `domain/connection/dto/WardListFilter.java` | **신규** - 필터 enum(ACTIVE·PENDING·ALL) + `orDefault(null)=ALL` + `toStatuses()` |
| `domain/connection/service/ConnectionService.java` | `getMyWards(guardianId, filter)`로 시그니처 확장. null = ALL |
| `domain/connection/controller/GuardianConnectionController.java` | `@RequestParam(required=false) WardListFilter status` + Swagger 설명·400 응답 추가 |
| `test/.../ConnectionServiceTest.java` | 신규 4건 + 기존 1건 의미 갱신 |

**리포지토리 변경 없음** - `findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId, List<ConnectionStatus>)`를
그대로 재사용한다. **마이그레이션 없음**, DTO 변경 없음.

## 4. 지켜진 불변 규칙

- **인가 경계 유지**: `getMyWards`는 화면 조회용이며 `status=ACTIVE`로 좁혀도 **인가 목록이 아니다.**
  타 도메인의 IDOR 판정은 그대로 `getActiveWardIds()`·`isActiveConnection()`만 쓴다(주석으로 명시).
  착수 전 확인 결과 `getMyWards`의 운영 호출부는 컨트롤러 1곳뿐이었다.
- **상태별 마스킹 유지**: 필터로 좁혀도 전화번호·주소는 ACTIVE에서만 채워진다. `PENDING` 단독 조회에서
  연락처가 노출되지 않는 것을 테스트로 고정했다.
- **`/requests` 역할 불변**: 종료된 이력(CANCELLED·REFUSED·DISCONNECTED)은 여전히 `/requests`만 다룬다.

## 5. FE 연동 가이드

```
GET /api/guardian/connection/select            → ACTIVE + PENDING (기존과 동일)
GET /api/guardian/connection/select?status=ALL     → 위와 동일
GET /api/guardian/connection/select?status=ACTIVE  → 연결됨만
GET /api/guardian/connection/select?status=PENDING → 수락 대기만
GET /api/guardian/connection/select?status=REFUSED → 400 "'status' 값의 형식이 올바르지 않습니다."
```

- 응답 형태는 기존과 동일한 `ApiResponse<List<ConnectionResponse>>`다. 필드 추가·삭제 없음.
- **기존 호출은 그대로 두어도 동작한다.** 탭을 만들 때만 파라미터를 붙이면 된다.
- React Query key에 status를 포함할 때 **mutation 후 invalidate가 모든 status 키를 덮는지** 확인할 것.
  현재는 `['guardian-connections']` 단일 키라 취소·해제 후 자동 갱신되지만, 키를 쪼개면
  `invalidateQueries({ queryKey: ['guardian','wards'] })`처럼 접두 무효화가 필요하다.
- 거절·취소·해제 이력 탭이 필요하면 `/api/guardian/connection/requests`를 쓴다. FE에 이미
  `getGuardianConnectionRequests()`가 구현돼 있으나 현재 아무 화면에서도 호출하지 않는다.

## 6. 검증

- `./gradlew build` **508건 / 실패 0**(기존 504 + 신규 4).
- 신규 테스트: `ALL`이 생략과 같은 상태 집합을 조회 / `ACTIVE`만 조회 / `PENDING`만 조회 시 연락처 null 유지 /
  모든 필터 값이 종료 상태를 포함하지 않음. 기존 `getMyWards` 테스트는 "파라미터 생략 = 하위호환"으로 의미를 갱신.
