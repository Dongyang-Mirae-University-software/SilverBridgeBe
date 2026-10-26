# (2026-10-26) 기능 점검 - 관리자 제휴 병원 관리(예약 서비스 중계)

> 템플릿 B · 대상 PR #382(`98d1f10`), 점검 기준 dev `3e589e3` · 점검만(코드 수정 없음)
> 방법: 독립 리뷰 에이전트(Sonnet, 코드만 제공) + 메인 재확인 · 변이 시험 14종 · gosky 실측(읽기 전용)

## 종합 판정: 조건부 PASS - 🔴 0 · 🟠 0 · 🟡 2 · 🟢 6

인가·비밀 노출·허용 목록에서는 결함이 없다. 남은 것은 쓰기 결과를 잘 알 수 없는 문제다. 시간 초과 뒤 다시 누르면 병원이 중복으로 생길 수 있고, 감사 로그에 결과·값이 남지 않는다.

## PHASE -1·0

- 2026-10-26, dev `3e589e3`(#382 `98d1f10` 포함), `./gradlew build` 통과, 최신 마이그레이션 V63.
- 점검 파일
  - `domain/hospital/client/ReservationAdminClient`, `domain/hospital/config/ReservationAdminProperties`
  - `domain/admin/controller/AdminHospitalController`, `domain/admin/service/AdminHospitalService`, `domain/admin/dto/AdminHospital*`(7)
  - `V63`, `application.yaml`(`hospital.reservation-admin`), `docker-compose.gosky.yml`, 테스트 4종

| 엔드포인트 | ADMIN | GUARDIAN | WARD | 비로그인 | 감사 로그 |
|---|---|---|---|---|---|
| `GET /api/admin/hospital`, `/summary` | 허용 | 403 | 403 | 401 | 없음(조회) |
| `POST /api/admin/hospital` | 허용 | 403 | 403 | 401 | `HOSPITAL_CREATE` |
| `PUT·DELETE /api/admin/hospital/{id}` | 허용 | 403 | 403 | 401 | `HOSPITAL_UPDATE`·`_DELETE` |
| `PUT /api/admin/hospital/{id}/status` | 허용 | 403 | 403 | 401 | `HOSPITAL_STATUS_CHANGE` |
| 진료과 `POST`·`PUT`·`DELETE` | 허용 | 403 | 403 | 401 | `HOSPITAL_DEPARTMENT_*` |

인가는 경로 규칙(`SecurityConfig` `/api/admin/**` hasRole ADMIN)과 클래스 `@PreAuthorize` 두 겹이다. gosky 실측 결과 비로그인 401, 보호자 토큰으로 목록·요약·등록 403이었다. 자원 소유 개념이 없는 관리자 전용 기능이라 IDOR(남의 자원 접근)는 해당 없다.

## PHASE A. 보안·인가 - PASS

- **허용 목록**: 클라이언트의 공개 메서드는 로그인과 8개 경로뿐이다. 경로는 상수 + `long` ID로만 만든다. 0 이하 ID는 서비스가 404로 막는다. 임의 경로를 받는 메서드는 없다. 변이 M1(로그인 경로를 `admin-login`으로 바꿈)을 테스트가 잡는다.
- **비밀 노출**: 로그 인자는 상태 코드·예외 클래스명·한도·adminId·hospitalId뿐이다. `CachedToken.toString`이 토큰을 가리고, 예약 서비스 오류 본문은 읽지 않는다. 감사 detail은 DB에만 남는다(SLF4J는 adminId·action·targetId만). `LogRawExceptionGuardTest` 대상이다.
- **리다이렉트**: 따라가지 않고 3xx는 503이다(변이 M3를 잡음).
- **base URL**: 설정값뿐이다. 끝에 `/`가 붙어도 Spring 경로 조립이 겹친 `/`를 정리한다(임시 테스트로 `http://reservation-api:6015/api/v1/admin/hospitals` 확인).
- **응답 허용 목록**: 예약 서비스 행을 그대로 넘기지 않는다. `deleted`·`active`·`hospitalId`·`passwordHash` 같은 내부 칸은 응답에 없다. 병원 이메일·예약 수도 없다.
- **입력**: DTO 검증 뒤 `TextSanitizer` 정리로 길이가 같거나 짧아져, 검증이 다시 깨지는 경로는 없다. 진료과 목록 원소는 서비스가 보이는 글자·제어문자를 검사한다.

## PHASE B. 기능 정합성

- 토큰 캐시: 토큰 값이 같을 때만 버리고(`invalidate`), 401은 한 번만 재시도한다. 401은 예약 서비스 가드에서 막힌 요청이라 POST 재시도가 중복을 만들지 않는다. 변이 M2·M5를 잡는다.
- 쓰기 전 확인: 삭제 병원 상태 변경, 진료과 소유, 이름 중복(자기 자신 제외). 변이 M7·M9·M10을 잡는다.
- 감사 로그 → 호출 순서: 변이 M8을 잡는다. 미설정이면 감사 로그를 남기지 않는다(변이 M14를 잡음).
- 필터·페이지·요약·삭제 병원 거르기·사용 중지 진료과 빼기·시작 < 종료: 변이 M11·M12·M13을 잡는다.
- 404 매핑은 경로별(병원 / 진료과)이고 변이 M6을 잡는다.

### 🟡 M-1. 시간 초과·응답 해석 실패 뒤 다시 누르면 병원·진료과가 중복으로 생긴다
- 근거: `ReservationAdminClient` `exchange`(시간 초과 504)·`parse`(형식 이상 503), `AdminHospitalService.create`(이름 중복 확인 없음)·`addDepartment`.
- 시나리오: 예약 서비스가 병원을 만든 뒤 응답이 8초를 넘기거나 본문이 비면 BE는 504·503을 돌려준다. 관리자가 다시 누르면 같은 병원이 하나 더 생긴다. 예약 서비스에는 이름 유일 제약도, 같은 요청 판별용 키도 없다. 계약 문서는 "504면 새로고침해 확인"이라고만 안내한다.
- 독립 리뷰는 🟠로 봤다. 🟡로 낮춘 이유는 세 가지다. 관리자 전용이고 드물다(SQLite 로컬, 평소 수십 ms). 중복은 목록에서 보이고 지울 수 있다. 안전 기능이 아니다.
- 제안:
  - 등록 전에 같은 이름 + 주소(공백·대소문자 무시)가 목록에 있으면 409 `HOSPITAL_DUPLICATED`.
  - 쓰기 호출 뒤의 시간 초과·형식 이상은 "반영됐을 수 있음" 문구로 구분한다(예: `HOSPITAL_RESULT_UNKNOWN` 504).
  - 계약 문서에 적는다.

### 🟡 M-2. 감사 로그가 만든 병원 id·바꾼 값·결과를 담지 않는다
- 근거: `AdminHospitalService.create` targetId `hospital:new`, `update`·`updateStatus` detail `fields=…`(이름만), `[ADMIN-HOSPITAL]` 로그의 등록 hospitalId는 null.
- 시나리오: "누가 이 병원 전화번호를 바꿨나"를 감사 로그로 답할 수 없다. 예약 서비스도 병원 수정·진료과 변경은 자체 로그(`AdminActionLog`)를 남기지 않는다(상태 변경만 남김). 등록한 병원이 어느 감사 행인지도 연결되지 않는다.
- 제안:
  - detail에 바뀐 값을 넣는다(병원 정보는 개인정보가 아니다, 500자 자르기 유지).
  - 등록 성공 뒤 `[ADMIN-HOSPITAL]` 로그에 새 id를 남긴다.
  - 감사 로그 = 시도 기록 원칙은 유지한다.

## PHASE C. 구조·계약

- 웹 응답은 record DTO뿐이다(Jackson 2 `JsonNode`는 서비스 경계에서 변환된다).
- 감사 로그는 자체 `@Transactional`이라 외부 호출과 따로 커밋된다. 서비스에 트랜잭션은 없다(의도).
- 상태 코드: 201(등록·진료과 추가), 200, 400, 404, 409, 503, 504. 계약 문서와 일치한다.
- "모르는 칸은 무시된다"(계약 문서): 명시 설정은 없다. 운영 근거로 판단했다 - 챗 DTO에 없는 `userId`를 옛 FE가 보내도 정상 처리됐다.
- compose override: gosky `config` 병합 결과 api 네트워크가 `app-net` + `sb-internal`이다. vkcs CD는 기본 파일만 쓰므로 무영향(CD 성공으로 확인).
- vkcs 기동: 설정이 비어 있어도 기동 성공(`@ConfigurationPropertiesScan`), V63 적용.

### 🟢 L-1. 자격이 틀리면 요청마다 로그인을 다시 시도한다
실패를 기억하지 않아 관리자 요청 하나당 로그인 1~2회가 나간다. 예약 서비스에는 로그인 제한·잠금이 없어(코드 검색 0건) 계정이 잠길 위험은 없고 부하만 는다. 제안: 실패를 30초 기억.

### 🟢 L-2. 시간 제한은 예약 서비스 호출 1회 단위다 - 확인 + 쓰기면 최대 약 16초
수정·상태·진료과 변경은 목록 확인과 쓰기로 두 번 부른다. 문서의 "한 요청 전체 8초"와 다르다. 제안: 서비스 호출 단위로 마감을 공유하거나 문서 문구를 고친다.

### 🟢 L-3. 시간 초과가 504 대신 503으로 나올 수 있다
읽기 시간 제한(`setReadTimeout`)과 마감 끊기가 같은 값이라, 읽기 시간 제한이 먼저 걸리면 `timedOut`이 아직 false라 503이 된다. 변이 M4(마감 끊기 제거)에서 실제로 그 경로가 503을 냈다. 제안: 읽기 시간 제한을 마감보다 1초 길게 두거나, `SocketTimeoutException`을 시간 초과로 처리한다.

### 🟢 L-4. 쓰기 경로의 Swagger 응답 설명이 description 글뿐이다
`@ApiResponses`는 목록에만 있다. 400·404·409·503·504를 쓰기 경로에도 붙인다.

### 🟢 L-5. `int` 범위를 넘는 id는 감사 로그를 남긴 뒤 503이 된다
예약 서비스 id는 Int인데 BE는 `long`을 받는다. 2147483647을 넘는 id로 삭제를 부르면 감사 행이 남고 예약 서비스 오류는 503이 된다. 제안: 그 범위면 404.

## PHASE D. 테스트

- 변이 14종 모두 잡힌다(M1~M14, 위 각 항목).
- gosky 실측: BE 컨테이너에서 같은 설정값으로 예약 서비스 로그인(ADMIN)·목록 200(46곳), 비로그인 401, 보호자 403.

### 🟢 L-6. 테스트 공백
- 동시 요청에서 로그인이 한 번만 나가는가
- 쓰기(POST) 시간 초과·응답 형식 이상이 어떻게 보이는가(M-1과 함께)
- 로그인 응답에 `accessToken`이 없거나, 2xx인데 본문이 비거나, 응답 크기 상한을 넘을 때

## 조치하지 않는 것 (확인 후 수용)

- 로그인 중 `synchronized`로 다른 요청이 최대 8초 기다림 - 관리자 전용·저빈도, 로그인이 겹치지 않는 이점.
- 토큰이 10분 이하로 남으면 매번 재로그인 - 예약 서비스 토큰은 7일.
- 시각 순서를 문자열로 비교 - 예약 서비스가 `\d{2}:\d{2}`만 받아 자리 수가 같다.
- `notice`를 지우면 `""` - 응답 스키마 설명에 "null 또는 빈 문자열"로 이미 적혀 있다.

## 미확인

- BE 관리자 토큰으로 처음부터 끝까지 하는 시험(목록, 시험용 병원 등록·수정·상태·진료과·삭제, 감사 로그 7종 기록). BE 관리자 테스트 계정이 없다.

## 수정 커밋 메시지 초안 (조치를 승인하면)

```
fix: 제휴 병원 중계 점검 후속 - 등록 중복·결과 불명 구분·감사 로그 값

- 등록 전 같은 이름+주소면 409 HOSPITAL_DUPLICATED (M-1)
- 쓰기 뒤 시간 초과·응답 형식 이상은 HOSPITAL_RESULT_UNKNOWN(504)으로 구분 (M-1)
- 감사 로그 detail에 바뀐 값, 등록 성공 시 새 병원 id 로그 (M-2)
- 로그인 실패 30초 기억, 읽기 시간 제한 > 마감, int 범위 밖 id 404, 쓰기 경로 Swagger 응답 (L-1·L-3·L-4·L-5)
- 마감은 서비스 호출 단위로 공유 (L-2), 테스트 공백 보강 (L-6)
```

## 후속 조치 (2026-10-26, BE #385)

M-1·M-2·L-1~L-6 **전부 조치**. 새 보호 장치 변이 7종 전부 검출(N1 등록 중복 확인 제거 · N2 쓰기 뒤 변환 실패를 결과 불명으로 안 바꿈 · N3 연결 뒤 쓰기 실패를 결과 불명으로 안 봄 · N4 로그인 거절 기억 제거 · N5 마감 공유 제거 · N6 Int 범위 확인 제거 · N7 감사 detail을 칸 이름만). `./gradlew build` 1965건 통과.

| 항목 | 조치 |
|---|---|
| M-1 | 연결 뒤 쓰기의 시간 초과·끊김·2xx 본문 이상 = 504 `HOSPITAL_RESULT_UNKNOWN`("새로고침해 확인"). 연결 전 실패·5xx는 그대로. 등록·이름/주소 수정은 같은 이름+주소면 409 `HOSPITAL_DUPLICATED` |
| M-2 | 감사 detail에 보낸 칸과 값(줄바꿈 펴기·500자), 등록 성공 시 `[ADMIN-HOSPITAL] 등록 완료: hospitalId=` |
| L-1 | 로그인 400·401·403이면 30초 재시도 안 함 |
| L-2 | `ReservationAdminClient.withinCall` - 관리자 요청 하나 전체가 마감 8초 공유 |
| L-3 | 읽기 시간 제한 = 마감 + 1초, `SocketTimeoutException`도 시간 초과 |
| L-4 | 쓰기 7개 경로 `@ApiResponses` |
| L-5 | ID > Int 범위면 감사·호출 전 404 |
| L-6 | 클라이언트 테스트 10 → 17건, 서비스 테스트 19 → 27건 |
