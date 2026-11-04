# 내 알림 기록 API (웹 상단 종 아이콘) - 2026-11-04

## 무엇을 했나

보호자·피보호자가 그동안 받은 알림을 웹에서 다시 보고, 안 읽은 수를 뱃지로 보도록 API 네 개를 만들었다.
새 테이블 없이 기존 발송 기록 `notification_log`(V54, 수신자 1명당 1행)에 읽은 시각 `read_at`(V67)만 더했다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/user/me/notifications?category=&page=&size=` | 내 알림 목록(최신순) |
| GET | `/api/user/me/notifications/unread-count` | 안 읽은 수 `{count}` |
| POST | `/api/user/me/notifications/{id}/read` | 한 건 읽음(멱등) |
| POST | `/api/user/me/notifications/read-all` | 모두 읽음 `{updated}` |

권한은 GUARDIAN·WARD이고 ADMIN은 403이다(관리자는 알림을 받지 않는다). 사용자 ID는 토큰에서만 받는다.

## 결정 (PHASE 1, 사용자 승인)

| # | 질문 | 결정 | 근거 |
|---|---|---|---|
| Q1 | 포함할 결과 | `DELIVERED`·`SMS_FALLBACK`·`FAILED` 포함, `NOT_SENT` 제외 | FAILED는 보냈지만 기기에 닿지 않았을 수 있는 알림(특히 SOS·화재)이라 앱 안에서라도 보여 준다. 설정·수신자 조회 실패로 시도 채널이 0인 FAILED도 포함 |
| Q2 | 읽음 저장 | `notification_log.read_at` + 부분 인덱스 2개 | 보관 정리·탈퇴 CASCADE와 함께 지워지고 행 수가 늘지 않는다. 칼럼 추가라 가역 |
| Q3 | 연결이 끝난 피보호자의 알림 | 그대로 보인다 | 아래 "D-1과의 관계" |
| Q4 | 정지·탈퇴 진행 계정 | 별도 처리 없음 | 토큰 무효화·로그인 차단으로 이미 막힌다 |
| Q5 | 안 읽은 수 갱신 | FE가 화면 진입·포커스·푸시 수신 때 다시 부른다 | WebSocket 이벤트는 만들지 않았다 |
| Q6 | 속도 제한 | 새로 만들지 않음 | `UserRateLimitFilter`(사용자당 분당 600)가 이미 적용된다. count는 부분 인덱스 |
| Q7 | `COMPANION_RISK` 묶음 | URGENT | 강제 푸시 + 문자 대체가 붙는 위험 신호 |
| Q8 | 배포 전 기존 행 | 읽음으로 채움(`read_at = created_at`) | 그대로면 배포 직후 최대 90일치가 안 읽음으로 잡혀 뱃지가 수백 건이 된다. 목록에는 보인다 |
| Q9 | ADMIN | 403 | 관리자에게는 알림 행이 생기지 않는다 |

### D-1과의 관계 (Q3)

D-1("보호자 이력은 지금 연결 이후 기록만", 2026-10-31)은 **피보호자의 기록(SOS·이상감지 이력)을 열람할 권한**에 관한 규칙이다.
이 목록은 **내 휴대폰에 이미 도착한 메시지의 사본**이다 - 연결을 끊어도 휴대폰 알림 센터의 알림이 지워지지 않는 것과 같다. 그래서 D-1을 적용하지 않는다.
피보호자가 탈퇴하면 `ward_id` FK가 `ON DELETE CASCADE`라 그 피보호자에 관한 보호자의 알림 행도 함께 지워진다(정책 ⑤ "붙들지 않는다"). 그래서 `wardName`이 탈퇴로 비는 경우는 없다.

## 구현 요점

- **보이는 행**: `recipient_id = 토큰 사용자 AND result <> 'NOT_SENT' AND created_at >= now - retention-days(90)`. 기간 조건은 보관 정리(매일 04:30)가 돌기 전 최대 하루치 남는 행을 빼려는 것이다.
- **부분 인덱스(V67)**: `(recipient_id, created_at DESC) WHERE result <> 'NOT_SENT'`(목록), `(recipient_id, created_at) WHERE read_at IS NULL AND result <> 'NOT_SENT'`(안 읽은 수). JPQL은 같은 조건을 enum 리터럴로 쓴다 - 바인딩 파라미터면 부분 인덱스를 고르지 못할 수 있다.
- **읽음**: `UPDATE ... SET read_at = COALESCE(read_at, :now) WHERE id AND recipient_id AND result <> NOT_SENT` 한 번. 이미 읽었으면 처음 시각을 유지하고 200(멱등). 0행이면 수신자 ID만 다시 읽어 남의 것 403 `NOTIFICATION_NOT_OWNED` + `[IDOR-ATTEMPT]` WARN(userId·notificationId만, 소유자 ID·제목·본문 없음), 없음·본인의 NOT_SENT 행은 404 `NOTIFICATION_NOT_FOUND`.
- **묶음(category)**: `UserNotificationCategory.of()`의 `switch`(default 없음) 한 곳. 새 `NotificationType`은 컴파일에서 막히고 `UserNotificationCategoryTest`가 전 종류·겹침 없음을 고정한다. 관리자 탭 `AdminNotificationCategory`와는 따로 둔다.
- **응답에 싣지 않는 칼럼**: `result`·`channel_results`·`not_sent_reason`·`recipient_id`(관리자용). `UserNotificationControllerSecurityTest`가 응답 필드를 고정한다.
- **wardName**: 페이지 안의 피보호자 ID를 모아 `findAllById` 한 번(N+1 없음). 본인에 관한 알림(`ward_id = recipient_id`)은 `wardId`·`wardName` 모두 null.
- **바뀌지 않은 것**: 발송 경로·기록 시점(발송 후 `REQUIRES_NEW`), 관리자 조회 API, 보관 90일, `NotificationLog` 빌더(디스패처 무변경).

## 알려진 한계

- **알림을 눌렀을 때 갈 화면을 정할 정보가 적다**: 발송 `data` 맵(`incidentId`·`sosEventId`·`medicationId` 등)은 `notification_log`에 저장하지 않는다. 목록에서 쓸 수 있는 건 `type`·`wardId`뿐이라, FE는 종류별 목록 화면(예: 그 피보호자의 이상감지 이력)으로 보내야 한다. 필요하면 별도 작업으로 다룬다.
- 이 테이블 이전(2026-09-22 V54 전)의 발송, WebSocket 전용 이벤트, 인증번호 문자는 기록이 없어 보이지 않는다.
- 피보호자 탈퇴 경로에서는 비동기 해제 알림 기록이 FK 때문에 남지 않을 수 있다(기존 수용 사항 그대로).

## 테스트

- 단위: `UserNotificationCategoryTest`(전 종류 매핑·분할·묶음 내용), `UserNotificationServiceTest`(조회 조건·이름 일괄·본인 알림, 읽음 성공/403 + 로그/404, 모두 읽음·개수), `UserNotificationControllerSecurityTest`(GUARDIAN·WARD 허용, ADMIN 403, 사용자 ID 입력 없음, 응답 필드 고정).
- 통합(실 PostgreSQL): `UserNotificationInboxIntegrationTest` - 본인만·NOT_SENT 제외·90일 밖 제외·3결과 포함·최신순, 묶음·페이지 경계, 읽음 멱등(처음 시각 유지)·남의 것/NOT_SENT 0, 모두 읽음 후 count 0, V67 부분 인덱스 조건.

## FE 전달 내용

### 경로·요청

모두 `Authorization: Bearer {accessToken}` 필요. 보호자·피보호자만(관리자 403).

```
GET  /api/user/me/notifications?category=URGENT&page=0&size=20
GET  /api/user/me/notifications/unread-count
POST /api/user/me/notifications/{id}/read
POST /api/user/me/notifications/read-all
```

- `category` 생략 = 전체. `page`는 0부터(음수는 0), `size` 기본 20·최대 50(넘으면 50).
- 최근 90일 동안 **실제로 보낸** 알림만 보인다(사용자가 채널을 꺼 둬서 안 간 알림은 없음).

### 응답 예시

목록:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1024,
        "type": "ANOMALY_DETECTED",
        "category": "URGENT",
        "title": "[화재 감지] 김영자님 댁 거실",
        "body": "거실 카메라에서 화재가 감지되었습니다.\n지금 확인해 주세요.",
        "createdAt": "2026-11-04T15:02:11.123+09:00",
        "read": false,
        "wardId": "WD0001",
        "wardName": "김영자"
      },
      {
        "id": 1019,
        "type": "INQUIRY_ANSWERED",
        "category": "OTHER",
        "title": "문의 답변 완료",
        "body": "문의하신 내용에 답변이 등록되었습니다.",
        "createdAt": "2026-11-04T10:00:00+09:00",
        "read": true,
        "wardId": null,
        "wardName": null
      }
    ],
    "page": 0, "size": 20, "totalElements": 2, "totalPages": 1, "last": true
  }
}
```
(페이지 필드 이름은 기존 `PageResponse` 그대로)

- `title`·`body`는 휴대폰에 뜬 문구와 같다. `body`에 줄바꿈 `\n`이 들어갈 수 있어 `white-space: pre-line` 등으로 표시한다.
- `wardId`·`wardName`은 **다른 사람(피보호자)에 관한 알림**일 때만 있다. 피보호자 본인에 관한 알림(복약 시각·본인 대피 안내·연결 요청·말벗 안내), 문의 답변, 판정 하루 요약은 null.
- 연결이 끝난 피보호자에 관한 알림도 이미 받은 알림이라 보인다(피보호자가 탈퇴하면 사라짐).

안 읽은 수: `{"success": true, "data": {"count": 3}}`
한 건 읽음: `{"success": true, "message": "알림을 읽음 처리했습니다."}` - 이미 읽었어도 200.
모두 읽음: `{"success": true, "data": {"updated": 3}}` - 이번에 바뀐 건수.

### category 표

| category | 화면 묶음 | 포함 type |
|---|---|---|
| `URGENT` | 긴급 | `WARD_SOS`, `ANOMALY_DETECTED`, `ANOMALY_DETECTED_SELF`, `COMPANION_RISK` |
| `MEDICATION` | 복약 | `MEDICATION_REMINDER`, `MEDICATION_MISSED`, `MEDICATION_STOPPED` |
| `CONNECTION` | 연결 | `CONNECTION_REQUEST`, `CONNECTION_ACCEPTED`, `CONNECTION_REFUSED`, `CONNECTION_DISCONNECTED`, `CONNECTION_FORCED` |
| `OTHER` | 그 밖 | `ANOMALY_REVIEW_REQUIRED`, `ANOMALY_REVIEW_CONFLICTED`, `CAMERA_LINK_LOST`, `INQUIRY_ANSWERED`, `COMPANION_PROMPT`, `COMPANION_NOTICE` |

화면 분기는 `category`로 하고, 아이콘·이동 화면 같은 세부는 `type`으로 한다(알림 종류가 늘면 `category`는 서버가 정해서 내려 준다).

### 오류 코드

| 상태 | code | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT` | `category`가 위 4개가 아님 |
| 401 | - | 토큰 없음·만료 |
| 403 | `FORBIDDEN` | 관리자 계정 |
| 403 | `NOTIFICATION_NOT_OWNED` | 다른 사용자의 알림 id로 읽음 요청 ("본인이 받은 알림만 읽음 처리할 수 있습니다.") |
| 404 | `NOTIFICATION_NOT_FOUND` | 없는 id(보관 기간 90일이 지나 지워진 알림 포함) |
| 429 | `TOO_MANY_REQUESTS` | 사용자 공통 제한(분당 600) |

### 안 읽은 수 갱신 시점

실시간 이벤트는 없다. 아래 때 `unread-count`를 다시 부른다.
- 앱(웹) 첫 진입·로그인 직후, 화면 이동
- 창 포커스(`visibilitychange`·`focus`)
- 포그라운드에서 푸시(FCM `onMessage`)를 받았을 때 - 기록은 발송 직후 남으므로 짧은 지연(1초 안팎) 뒤에 부르면 안전하다
- 종 목록을 열어 항목을 읽음 처리·모두 읽음 처리한 뒤(또는 응답으로 로컬 값 갱신)

### 이동 화면

알림의 상세 식별자(`incidentId` 등)는 내려가지 않는다. 항목을 누르면 `type`·`wardId` 기준으로 목록 화면으로 보낸다(예: `ANOMALY_*` → 그 피보호자의 이상감지 이력, `WARD_SOS` → SOS 이력, `MEDICATION_*` → 복약, `CONNECTION_*` → 연결 관리, `INQUIRY_ANSWERED` → 내 문의).
