# (2026-10-26) 관리자 제휴 병원 관리 - 예약 서비스 중계 API

> 브랜치 `feature/admin-partner-hospital` · 마이그레이션 V63(감사 로그 CHECK 재정의만) · 관리자 웹 FE는 이번 범위 아님
> 관리자 웹 전달용 계약: `docs/(2026-10-26) api-contract-admin-hospital.md`

## 1. 무엇을 만들었나

제휴 병원 데이터(46곳·진료과 118개)와 관리 API는 예약 서비스(SilverBridgeReservation, NestJS + SQLite)에 이미 있다.
BE는 데이터를 저장하지 않고, 관리자 토큰으로 예약 서비스 관리자 API를 **중계**한다.

| BE (ADMIN 전용) | 예약 서비스 |
|---|---|
| `GET /api/admin/hospital?q=&region=&department=&page=&size=` | `GET /api/v1/admin/hospitals` (전체, 메모리 필터·페이지) |
| `GET /api/admin/hospital/summary` | 같은 목록에서 계산 |
| `POST /api/admin/hospital` | `POST /api/v1/admin/hospitals` |
| `PUT /api/admin/hospital/{id}` | `PUT /api/v1/admin/hospitals/{id}` |
| `DELETE /api/admin/hospital/{id}` | `DELETE /api/v1/admin/hospitals/{id}` (`deleted=true`) |
| `PUT /api/admin/hospital/{id}/status` | `PUT /api/v1/admin/hospitals/{id}/status` |
| `POST /api/admin/hospital/{id}/department` | `POST /api/v1/admin/hospitals/{id}/departments` |
| `PUT /api/admin/hospital/{id}/department/{deptId}` | `PUT /api/v1/admin/departments/{deptId}` (이름만) |
| `DELETE /api/admin/hospital/{id}/department/{deptId}` | `DELETE /api/v1/admin/departments/{deptId}` (`active=false`) |

코드: `domain/hospital/client/ReservationAdminClient`·`domain/hospital/config/ReservationAdminProperties`,
`domain/admin/controller/AdminHospitalController`·`domain/admin/service/AdminHospitalService`·`domain/admin/dto/AdminHospital*`.

## 2. 전제와 실제가 달랐던 점 (PHASE 0)

- 예약 서비스 목록은 **검색·페이지가 없다** → BE가 전체를 받아 거르고 자른다(관리자 카메라 목록과 같은 방식, 근거 = 50곳 내외).
- 운영 상태(`operationStatus` 등)는 병원이 아니라 별도 `HospitalStatus` 행이다 → 응답 `status` 객체.
- 예약 서비스는 **삭제된 병원에도 상태·진료과 변경을 받아 주고**, 진료과 **중복도 막지 않는다** → BE가 쓰기 전에 목록으로 확인(404·409).
- 시각은 `^\d{2}:\d{2}$` 모양만(99:99 통과), 병원 전화는 검증 없음 → BE가 실제 시각·순서·전화 형식을 검증.
- `admin-login`(ADMIN_PANEL_PASSWORD)은 id가 가장 작은 ADMIN(seed 계정) 토큰을 준다 → BE는 **전용 ADMIN 계정 + `/auth/login`**.
- "우리 사용자 예약 수"는 바로 주는 API가 없고 `/admin/reservations` 전체(환자 이름·전화·증상 포함)를 받아야 셀 수 있다 → **싣지 않음**(사용자 결정 D2).
- 병원 이메일은 예약 서비스에 칸이 없다 → 만들지 않음(D3).
- `sb-internal` 네트워크는 gosky에만 있다 → 기본 compose에 넣으면 vkcs CD가 깨진다 → **`docker-compose.gosky.yml` override**(D4).

## 3. 사용자 결정 (2026-10-26, "권장안대로 시작해")

| | 결정 |
|---|---|
| D1 자격 | BE 전용 예약 서비스 ADMIN 계정(공용 `ADMIN_PANEL_PASSWORD` 재사용 안 함) |
| D2 우리 사용자 예약 수 | 생략. 필요해지면 예약 서비스에 숫자만 주는 API를 따로 만든다 |
| D3 병원 이메일 | 제외 |
| D4 compose | gosky 전용 override 파일 |

## 4. 중계 규칙

1. **설정**: `RESERVATION_ADMIN_BASE_URL`·`_EMAIL`·`_PASSWORD` - 코드 기본값 없음. 하나라도 비면 이 API들만 503 `HOSPITAL_SERVICE_UNAVAILABLE`(기동은 막지 않음, vkcs는 비어 있는 것이 정상).
2. **토큰**: 로그인해 받은 JWT를 메모리에 두고 `exp` 10분 전에 다시 받는다(읽지 못하면 1시간). 관리자 경로가 401이면 한 번만 재로그인해 재시도, 또 401이면 503. 토큰·비밀번호는 로그·응답에 없다.
3. **허용 목록**: 클라이언트 메서드는 로그인 + 위 8개 경로뿐이고 경로는 상수 + 숫자 ID로만 만든다(`admin/users`·`reservations`·`api-keys`·`admin-login` 미호출을 테스트로 고정).
4. **시간 제한**: 연결 3초, 한 요청 전체(로그인·재시도 포함) 8초 - 마감에 연결을 끊는다(전용 데몬 스레드 2, 빈 아님). 리다이렉트 미추종.
5. **오류**: 예약 서비스 오류 본문은 읽지 않는다. 404 → `HOSPITAL_NOT_FOUND`/`HOSPITAL_DEPARTMENT_NOT_FOUND`, 400·422 → `HOSPITAL_REQUEST_REJECTED`, 401·403(재시도 후)·5xx·3xx·통신 오류 → 503, 시간 초과 → 504 `HOSPITAL_SERVICE_TIMEOUT`.
6. **응답 허용 목록**: 예약 서비스 행을 그대로 넘기지 않고 `AdminHospitalItem` 칸만 옮긴다. 사용 중지된 진료과·`deleted=true` 병원은 뺀다. 시각은 KST, 읽지 못하면 null.
7. **감사 로그**: 등록·수정·삭제·상태·진료과 추가/변경/삭제 = `HOSPITAL_*` 7종(V63). **예약 서비스 호출보다 먼저** 남긴다 - 기록이 실패하면 변경하지 않고, 호출이 실패해도 "시도" 기록은 남는다(감사 로그 = 시도 기록). 접속 정보가 없거나 쓰기 전 확인(404·409·400)에서 걸리면 남기지 않는다. 조회는 남기지 않는다. detail은 500자로 자른다.
8. **로그**: `[ADMIN-HOSPITAL]` 동작·adminId·hospitalId·결과 코드·소요 시간, `[RESERVATION-ADMIN]` 상태 코드·예외 클래스명만. `LogRawExceptionGuardTest` 대상.

## 5. 입력 검증

- 이름 100·지역 30·주소 200자: `@NotBlank @VisibleText @NoControlChars` + `TextSanitizer.sanitize`
- 소개 1000자(줄바꿈 허용, 생략 시 빈 문자열), 공지 200자
- 전화 `0\d{1,2}-?\d{3,4}-?\d{4}` 또는 `1\d{3}-?\d{4}`
- 시각 `00:00~23:59`, 시작 < 종료(수정에서 한쪽만 바꾸면 기존 값과 합쳐 확인 → 400 `HOSPITAL_INVALID_HOURS`). 자정을 넘기는 진료 시간은 받지 않는다(지금 46곳 모두 09:00~18:00)
- 진료과 1~20개·각 30자, 같은 요청 안 중복 400, 같은 병원 사용 중인 이름과 같으면 409(공백·대소문자 무시)
- 상태 숫자 0~9999(평균 대기 0~1440분), enum은 예약 서비스와 같은 값만

## 6. 인프라

- `docker-compose.gosky.yml`(신규): api를 `sb-internal`에 붙인다. **gosky 수동 배포 명령이 바뀐다**: `docker compose -f docker-compose.dev.yml -f docker-compose.gosky.yml up -d --build api`. 옛 명령으로 올리면 이 API만 503이 된다(다른 기능 무영향).
- gosky `.env.dev`: `RESERVATION_ADMIN_BASE_URL=http://reservation-api:6015`, `_EMAIL`, `_PASSWORD`(백업 후 추가).
- 예약 서비스 BE 전용 ADMIN 계정: 예약 서비스 API `POST /api/v1/admin/users`(role ADMIN)로 만든다(DB 직접 수정·`prisma db seed` 금지).
- vkcs-linux: 변경 없음(값 비어 503 정상).

### 반영 결과 (2026-10-26)

- PR #382 머지 `98d1f10`. 머지 전 vkcs 통합 테스트 통과, CD 성공(V63 적용, 비로그인 401).
- gosky(사용자 확인 후 스크립트 1회 실행):
  - 예약 서비스 BE 전용 ADMIN 계정 `be-hospital-relay@silverbridge.internal`(id 44)을 예약 서비스 API(`admin-login` → `POST /admin/users`)로 생성했다. 연락처는 더미 `010-0000-0000`이고 비밀번호는 서버에서 만든 32자다(어디에도 출력하지 않음).
  - `.env.dev` 백업 `.env.dev.bak-20261026-101919` 후 `RESERVATION_ADMIN_*` 3줄을 추가했다.
  - `git pull` → `-f docker-compose.gosky.yml` 포함 `up -d --build api`: `98d1f10`, 네트워크 `dmu-dev-net`·`sb-internal`, healthy, 재시작 0, AI WS 연결.
- gosky 확인:
  - BE 컨테이너 안에서 같은 설정값으로 예약 서비스 로그인(role ADMIN) → `GET /admin/hospitals` 200, 46곳.
  - 비로그인 401, 보호자 토큰 목록·요약·등록 403, api-docs에 6개 경로 노출.
- ~~미확인~~ → 아래 "BE 관리자 끝까지 시험"에서 확인했다.

### BE 관리자 끝까지 시험 (2026-10-26, gosky `431db61`)

- 시험용 BE 관리자 계정 `sbtadm`(ADMIN·ACTIVE·LOCAL)을 사용자 승인 후 gosky `users`에 직접 넣었다. 관리자는 가입·역할 변경으로 만들 수 없다. 비밀번호는 무작위 26자이고 DB에는 bcrypt(12) 해시만 들어갔다. 자격은 작업 PC `~/.silverbridge-test.env`의 `TEST_ADMIN_*`에만 있다(출력 안 함). 관리자 계정은 API로 지울 수 없다(`CANNOT_MODIFY_ADMIN`) - 필요 없어지면 DB에서 지운다.
- gosky에는 이미 관리자 3명이 있었다(자격 미상, 건드리지 않음). 공개 비밀번호 seed 계정(`admin@test.com`)은 없었다.
- BE를 거친 15단계가 전부 기대대로였다.
  - 목록 200(46곳), 요약 200(46·44), 검색 `q=정형` 5건
  - 등록 201(id 57)
  - 같은 병원 재등록 409 `HOSPITAL_DUPLICATED`
  - 수정 200, 시작 > 종료 수정 400 `HOSPITAL_INVALID_HOURS`
  - 운영 상태 200
  - 진료과 추가 201, 같은 진료과(공백 섞음) 409, 진료과 이름 변경 200, 진료과 삭제 200
  - 병원 삭제 200, 삭제 후 검색 0건, 삭제된 병원 상태 변경 404 `HOSPITAL_NOT_FOUND`
- 감사 로그 `admin_audit_log`(admin `sbtadm`)에는 정확히 7행이 남았다: CREATE(`hospital:new`, 값 포함) → UPDATE → STATUS_CHANGE → DEPARTMENT_ADD → DEPARTMENT_UPDATE → DEPARTMENT_DELETE → DELETE. 400·409로 막힌 요청은 행이 없다. `[ADMIN-HOSPITAL] 등록 완료: hospitalId=57`가 만든 병원을 잇는다.
- 예약 서비스에는 병원 57이 `deleted=true`로 남았다(소프트 삭제, 되살리는 API 없음 - 수용).


## 7. 수용한 한계

- 쓰기 전 확인과 실제 쓰기 사이의 경합(동시에 같은 진료과 추가 등)은 막지 않는다 - 관리자 한두 명 규모.
- 감사 로그는 시도 기록이라 실패한 변경도 남는다(결과는 `[ADMIN-HOSPITAL]` 로그의 result).
- 예약 서비스는 삭제된 병원을 되살리는 API가 없다 - 잘못 지우면 예약 서비스 쪽 작업이 필요하다.
- 토큰 캐시는 인스턴스 메모리(단일 인스턴스 전제).
- 삭제된 병원의 예약은 예약 서비스의 예약 목록·내 예약에 그대로 남는다(의도).

## 8. 테스트

- `ReservationAdminClientTest`(실제 로컬 HTTP 10건): 토큰 재사용·401 재로그인 1회·두 번째 401 503·로그인 거절·미설정 무호출·404 매핑·400/5xx·시간 초과 504·리다이렉트 미추종·허용 목록 경로
- `AdminHospitalServiceTest`(19건): 필터·페이지·요약·변환, 감사 로그 → 호출 순서, 감사 실패 시 미호출, 미설정 시 감사 없음, 시각·중복·보이지 않는 글자, 삭제된 병원 상태 404, 진료과 소유·중복
- `AdminHospitalControllerSecurityTest`: 9개 경로 ADMIN 허용·GUARDIAN·WARD 403
- `AdminHospitalRequestValidationTest`(20건), `AdminAuditActionCheckSyncTest`(V63), `LogRawExceptionGuardTest`

## 9. 점검 후속 (2026-10-26, `docs/(2026-10-26) audit-admin-partner-hospital.md`)

- **M-1 결과 불명·중복**: 쓰기 요청이 예약 서비스에 **연결된 뒤** 시간 초과·끊김·2xx 본문 이상이 나면 504 `HOSPITAL_RESULT_UNKNOWN`("이미 반영됐을 수 있으니 새로고침해 확인")이다. 연결 전 실패(연결 거부·연결 시간 초과)와 5xx는 그대로 503/504 `HOSPITAL_SERVICE_TIMEOUT`이다(반영되지 않음). 등록과 이름·주소 수정은 같은 이름 + 주소(공백·대소문자 무시) 병원이 있으면 409 `HOSPITAL_DUPLICATED`이다 - 같은 이름의 다른 지점은 주소가 달라 막지 않는다.
- **M-2 감사 로그 값**: detail에 보낸 칸과 값을 남긴다(`name=…, phone=…, departments=내과/외과`, 줄바꿈은 펴고 500자로 자름). 등록 성공 뒤 `[ADMIN-HOSPITAL] 등록 완료: hospitalId=`로 새 id를 잇는다. 감사 로그 = 시도 기록 원칙은 그대로다.
- **L-1** 로그인이 400·401·403으로 거절되면 30초 동안 다시 로그인하지 않는다(네트워크 오류는 기억하지 않음).
- **L-2** 서비스 메서드가 요청 하나를 `ReservationAdminClient.withinCall`로 감싸 목록 확인·로그인·재시도·쓰기가 마감 하나(8초)를 함께 쓴다 - "한 요청 전체 8초"가 사실이 됐다.
- **L-3** 읽기 시간 제한을 마감보다 1초 길게 두고 `SocketTimeoutException`도 시간 초과로 본다(504가 503으로 나오던 경합).
- **L-4** 쓰기 7개 경로에 Swagger 응답(400·401·403·404·409·503·504) 명시.
- **L-5** 예약 서비스 ID(Int) 범위를 넘는 ID는 감사 로그·호출 전에 404.
- **L-6** 테스트 보강: 동시 요청 로그인 1회, 쓰기 시간 초과·2xx 본문 이상 = 결과 불명, 연결 거부는 503, 로그인 거절 기억, accessToken 누락, 응답 크기 상한, 마감 공유, 중복 409, 감사 detail 값·500자, ID 범위.
