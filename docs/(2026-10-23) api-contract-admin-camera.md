# 관리자 웹 전달 - 디바이스 관리 API (2026-10-23)

관리자 웹 "디바이스 관리" 화면용입니다. 두 API 모두 **ADMIN 토큰**(`Authorization: Bearer ...`)이 필요하고 조회만 합니다.

## 1. 카메라 목록

`GET /api/admin/camera`

| 파라미터 | 필수 | 값 | 설명 |
|---|---|---|---|
| `status` | 아니오 | `ALL`(기본)·`LIVE`·`OFFLINE` | 탭. 전체·정상·오프라인 |
| `q` | 아니오 | 문자열 | 주소·설치 위치·피보호자 이름·세션 ID 부분 일치(공백·대소문자 무시) |
| `page` | 아니오 | 0부터(기본 0) | |
| `size` | 아니오 | 기본 20, 최대 50 | 시안은 10 |

응답 예시(200):
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "cameraId": 12,
        "sessionId": "ward_a1B2c3D4e5F6g7H8",
        "room": "거실",
        "wardId": "W00012",
        "wardName": "김영자",
        "wardAddress": "서울 강남구 역삼로 123",
        "isActive": true,
        "registeredAt": "2026-10-14T13:05:00+09:00",
        "liveStatus": "LIVE"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  }
}
```

- 정렬: 등록 최신순.
- `room`(설치 위치)은 방 이름(거실·침실·주방·화장실·현관·베란다·작은방·작은방2)입니다. 시안의 "101호"가 아닙니다.
- `wardAddress`는 기본 주소만입니다(상세 주소 없음). `wardName`·`wardAddress`는 회원 정보를 못 찾으면 `null`입니다(드묾).
- `isActive=false`는 피보호자가 화면에서 숨긴 카메라입니다. **감지·알림은 계속됩니다.** 필요하면 "숨김" 정도로만 표시해 주세요.
- 탈퇴 처리 중인 피보호자의 카메라는 나오지 않습니다.

### `liveStatus` 뜻
| 값 | 화면 | 뜻 |
|---|---|---|
| `LIVE` | 정상 | AI 서버가 영상을 받고 있고 서버가 감시 중 |
| `OFFLINE` | 오프라인 | AI 서버에 영상이 들어오지 않음(송출 꺼짐·끊김) |
| `null` | 확인 중 / 확인 불가 | 서버와 AI 서버의 연결이 끊겨 지금 알 수 없음. **오프라인으로 표시하지 마세요** |

### 상태를 알 수 없을 때
- `status=ALL`(또는 생략): 200, 모든 항목의 `liveStatus`가 `null`.
- `status=LIVE`·`OFFLINE`: **503**, `code: "CAMERA_LIVE_STATUS_UNKNOWN"`. 빈 목록이 아닙니다.
- 권장: 요약 API의 `aiConnected=false`면 정상·오프라인 탭을 잠시 비활성화하고 "카메라 상태를 확인할 수 없습니다" 안내.

## 2. 요약(상단 카드)

`GET /api/admin/camera/summary`

응답 예시(200):
```json
{
  "success": true,
  "data": {
    "aiConnected": true,
    "registered": 3,
    "maxTotal": 3,
    "live": 2,
    "offline": 1,
    "totalWards": 34,
    "wardsWithoutCamera": 31
  }
}
```

| 칸 | 카드 | 설명 |
|---|---|---|
| `registered` / `totalWards` | 등록 카메라 "3 / 34" | 등록 카메라 수 / 이용 중인 피보호자 수 |
| `live` | 정상 작동 | `null`이면 확인 불가 |
| `offline` | 연결이 끊긴 카메라 | `null`이면 확인 불가. 1 이상이면 경고색, 누르면 오프라인 탭 |
| `maxTotal` | (선택) "등록 3 / 상한 3" | 서비스 전체 등록 상한. 상한이 꺼져 있으면 `null` |
| `wardsWithoutCamera` | (선택) | 카메라를 한 대도 등록하지 않은 피보호자 수 |
| `aiConnected` | | `false`면 `live`·`offline`이 `null` |

- 값이 있으면 `live + offline = registered`이고, 목록의 탭 건수와 같습니다. 대시보드의 "끊김" 숫자는 숨긴 카메라를 빼고 세므로 다를 수 있습니다.
- **모르는 값은 0이 아니라 `null`입니다.** `null`을 0으로 바꿔 표시하지 마세요("-" 또는 "확인 중").

## 3. 오류
| 상태 | `code` | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT` | `status`가 세 값 밖(예: `ISSUE`) |
| 401 | | 토큰 없음·만료 |
| 403 | | 관리자가 아님 |
| 503 | `CAMERA_LIVE_STATUS_UNKNOWN` | 상태를 알 수 없는데 `LIVE`·`OFFLINE` 필터 |

## 4. 없는 것(시안과 다른 점)
- **[점검 요청] 버튼**: 만들지 않았습니다. 받는 곳·절차가 없고, 연결 끊김은 보호자에게 자동 안내됩니다. 버튼을 빼 주세요.
- **"24시간 넘게 신호 없음"**: 끊긴 시각을 저장하지 않아 지금 상태만 있습니다.
- **카메라 삭제·영상 보기·클립**: 관리자에게 열지 않습니다.
- 마지막 연결 시각 칸은 없습니다.
