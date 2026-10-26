# (2026-10-26) API 계약 - 관리자 제휴 병원 관리

> 관리자 웹 전달용. 모든 경로 **ADMIN 전용**(`Authorization: Bearer <access>`), 비로그인 401·다른 역할 403.
> 응답은 공통 래퍼 `{ success, message, data, code }`. 오류는 `code`로 분기한다(문구는 바뀔 수 있다).
> 데이터는 예약 서비스에 있고 BE가 중계한다. 예약 서비스를 쓸 수 없는 서버에서는 모든 경로가 503 `HOSPITAL_SERVICE_UNAVAILABLE`.

## 공통 오류

| HTTP | code | 뜻 / 화면 |
|---|---|---|
| 400 | `INVALID_INPUT` | 입력 검증 실패(칸별 문구는 `message`), 수정 칸이 하나도 없음 |
| 400 | `HOSPITAL_INVALID_HOURS` | 진료 시작 ≥ 종료 |
| 400 | `HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST` | 등록 요청에 같은 진료과가 두 번 |
| 400 | `HOSPITAL_REQUEST_REJECTED` | 예약 서비스가 입력을 거절(드묾) |
| 404 | `HOSPITAL_NOT_FOUND` | 없거나 삭제된 병원 |
| 404 | `HOSPITAL_DEPARTMENT_NOT_FOUND` | 그 병원의 사용 중인 진료과가 아님 |
| 409 | `HOSPITAL_DEPARTMENT_DUPLICATED` | 같은 병원에 같은 이름의 진료과가 있음 |
| 503 | `HOSPITAL_SERVICE_UNAVAILABLE` | 예약 서비스 연결 불가·미설정 → "잠시 후 다시" |
| 504 | `HOSPITAL_SERVICE_TIMEOUT` | 예약 서비스 응답 지연(8초) → 쓰기였다면 목록을 새로고침해 반영 여부 확인 |

## 병원 항목 `AdminHospitalItem`

```json
{
  "id": 12,
  "name": "서울튼튼정형외과",
  "region": "서울 강남구",
  "address": "서울 강남구 테헤란로 123",
  "phone": "02-2001-0001",
  "description": "",
  "openTime": "09:00",
  "closeTime": "18:00",
  "bookingAvailable": true,
  "departments": [{ "id": 31, "name": "정형외과" }],
  "status": {
    "operationStatus": "OPEN",
    "congestionLevel": "NORMAL",
    "waitingCount": 3,
    "averageWaitingMinutes": 15,
    "todayReservationCount": 0,
    "acceptingPatients": true,
    "notice": null,
    "updatedAt": "2026-10-25T09:00:00+09:00"
  },
  "registeredAt": "2026-10-20T13:05:00+09:00",
  "updatedAt": "2026-10-25T09:00:00+09:00"
}
```

- `status`는 상태 행이 없으면 `null`. `operationStatus` = OPEN·CLOSED·BREAK_TIME·HOLIDAY·INSPECTION, `congestionLevel` = LOW·NORMAL·BUSY·VERY_BUSY.
- `status.todayReservationCount`는 **관리자가 입력하는 값**이며 실제 예약 집계가 아니다.
- `departments`는 사용 중인 진료과만. 시각은 KST, 알 수 없으면 `null`.
- 병원 이메일·우리 사용자 예약 수는 **없다**(예약 서비스에 칸이 없음 / 환자 정보 조회가 필요해 제외).

## 1. 목록 `GET /api/admin/hospital`

쿼리(모두 선택, 공백·대소문자 무시): `q`(이름·지역·주소 부분 일치), `region`(지역 부분 일치), `department`(진료과 이름 일치), `page`(0부터, 기본 0), `size`(기본 20, 최대 50).
정렬: 등록 최신순. 삭제된 병원은 빠진다.

```json
{ "success": true, "data": { "content": [AdminHospitalItem], "page": 0, "size": 20, "totalElements": 46, "totalPages": 3, "last": false } }
```

## 2. 요약 `GET /api/admin/hospital/summary`

```json
{ "success": true, "data": { "total": 46, "bookingAvailable": 44, "latestRegisteredAt": "2026-10-20T13:05:00+09:00" } }
```
`latestRegisteredAt`은 병원이 없으면 `null`.

## 3. 등록 `POST /api/admin/hospital` → 201

```json
{
  "name": "서울튼튼정형외과",          // 필수, 1~100자
  "region": "서울 강남구",             // 필수, 1~30자
  "address": "서울 강남구 테헤란로 123", // 필수, 1~200자
  "phone": "02-2001-0001",            // 필수, 02-123-4567 / 031-1234-5678 / 1588-1234 (하이픈 선택)
  "description": "관절·척추 진료",      // 선택, 최대 1000자, 줄바꿈 허용
  "openTime": "09:00",                // 필수, HH:mm(00:00~23:59), 종료보다 빨라야 함
  "closeTime": "18:00",               // 필수
  "bookingAvailable": true,           // 선택, 기본 true
  "departments": ["정형외과", "재활의학과"] // 필수, 1~20개, 각 1~30자, 중복 불가
}
```
응답 `data` = `AdminHospitalItem`. 모르는 칸을 보내면 무시된다.

## 4. 수정 `PUT /api/admin/hospital/{id}`

등록 칸 중 `departments`를 뺀 전부가 선택(null·생략 = 변경 안 함). 하나도 없으면 400. `description`은 빈 문자열로 지울 수 있다.
시각을 한쪽만 바꾸면 기존 값과 합쳐 순서를 확인한다. 응답 `data` = `AdminHospitalItem`.

## 5. 삭제 `DELETE /api/admin/hospital/{id}`

소프트 삭제 - 목록·앱 병원 찾기·새 예약에서 빠지고 이미 잡힌 예약 기록은 남는다. **되돌리는 API는 없다** → 확인 창 필수.
`{ "success": true, "message": "병원을 삭제했습니다.", "data": null }`

## 6. 운영 상태 `PUT /api/admin/hospital/{id}/status`

```json
{
  "operationStatus": "HOLIDAY",     // 선택
  "congestionLevel": "BUSY",        // 선택
  "waitingCount": 3,                // 선택, 0~9999
  "averageWaitingMinutes": 15,      // 선택, 0~1440
  "todayReservationCount": 0,       // 선택, 0~9999
  "acceptingPatients": false,       // 선택
  "notice": "오늘 오후 휴진"          // 선택, 최대 200자, 빈 문자열이면 지움
}
```
하나도 없으면 400. 삭제된 병원은 404. 응답 `data` = `status` 객체.

## 7. 진료과

- 추가 `POST /api/admin/hospital/{id}/department` `{ "name": "재활의학과" }` → 201, `data` = `{ id, name }`. 같은 이름 있으면 409.
- 이름 변경 `PUT /api/admin/hospital/{id}/department/{departmentId}` `{ "name": "..." }` → `data` = `{ id, name }`. 그 병원의 진료과가 아니면 404, 다른 진료과와 이름이 같으면 409.
- 삭제 `DELETE /api/admin/hospital/{id}/department/{departmentId}` → 사용 중지(기존 예약의 진료과 이름은 남는다). 다시 쓰려면 같은 이름으로 추가.

## 감사 로그

쓰기 7종은 BE 감사 로그에 남는다(조회는 남지 않음). 관리자 웹에서 감사 로그를 보는 API는 없다.
