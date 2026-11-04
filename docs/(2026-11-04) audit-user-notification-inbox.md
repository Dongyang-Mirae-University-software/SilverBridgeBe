# 내 알림 기록 API 기능 점검 (템플릿 B) - 2026-11-04

- 대상: PR #442 (`86c5a1a`), V67. 점검 기준 dev `356cc76`.
- 방법: 코드 확인 + gosky dev 실서버 확인(API 호출, 읽기 전용 `EXPLAIN`·인덱스 사용 통계). 코드 수정 없음.
- 판정: **조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 3 → **조치 완료(M-1·L-2·L-3, L-1은 조치 없음으로 종결)** - 아래 "조치 결과"

## 점검 파일

`UserNotificationController` · `UserNotificationService` · `UserNotificationCategory` · `UserNotificationItem` · `NotificationLogRepository`(사용자 쿼리 5개) · `NotificationLog.readAt` · `V67__add_notification_log_read_at.sql` · `ErrorCode`(NOTIFICATION_*) · `SwaggerConfig` · 테스트 4종

## 엔드포인트 × 역할

| 엔드포인트 | GUARDIAN | WARD | ADMIN | 미인증 |
|---|---|---|---|---|
| GET `/api/user/me/notifications` | 200 | 200 | 403 | 401 |
| GET `/unread-count` | 200 | 200 | 403 | 401 |
| POST `/{id}/read` | 200·403·404 | 200·403·404 | 403 | 401 |
| POST `/read-all` | 200 | 200 | 403 | 401 |

클래스 단위 `@PreAuthorize("hasAnyRole('GUARDIAN','WARD')")`, `@EnableMethodSecurity` 켜짐. 사용자 ID는 `@AuthenticationPrincipal`만(경로·쿼리 입력 없음 - 테스트로 고정). 사용자별 속도 제한(`UserRateLimitFilter`)이 이 경로를 제외하지 않는다.

## PHASE A 보안·인가 - PASS

| 항목 | 결과 | 근거 |
|---|---|---|
| IDOR(목록·개수) | PASS | 쿼리가 `recipient_id = 토큰 사용자`로만 조회. 통합 테스트에 다른 사용자 행 포함 |
| IDOR(읽음) | PASS | `UPDATE … WHERE id AND recipient_id`. 남의 것 403 `NOTIFICATION_NOT_OWNED` + `[IDOR-ATTEMPT]`(userId·notificationId만, 소유자 ID 없음 - 테스트 고정) |
| IDOR(모두 읽음) | PASS | 수신자 조건. 통합 테스트에서 다른 사용자 안 읽은 수 유지 확인 |
| 역할 | PASS | 위 표. 실서버 미인증 401 확인 |
| 입력 검증 | PASS | `category` 잘못된 값 400 `INVALID_INPUT`(실서버), size 최대 50·음수 page 보정(`AdminPaging`) |
| 응답·로그 PII | PASS | 응답 9필드 고정(관리자 칼럼 없음). 제목·본문은 휴대폰 알림과 같은 수준이고 수신자 본인에게만. 로그에 제목·본문 없음 |
| 존재 여부 노출 | 수용 | 403/404 구분으로 "그 id의 알림이 있다"만 드러난다 - 2026-07-14 IDOR 정책(내용·소유자 미노출)과 같다 |

## PHASE B 기능 정합성 - PASS (🟡 1)

- NOT_SENT 제외·90일 하한·FAILED 포함·최신순·묶음 필터·페이지 경계: 통합 테스트로 확인.
- 읽음 멱등: `COALESCE(read_at, :now)`로 처음 시각 유지, 동시 요청에도 UPDATE 한 번이라 경합 없음.
- V67 기존 행 처리: gosky 925행 전부 `read_at`이 채워짐(안 읽음 0) - 배포 직후 뱃지 0.
- 묶음 매핑: 19종 전부, 겹침 없음(`UserNotificationCategoryTest`, default 없는 switch).
- 🟡 **M-1 (FE 계약)**: 연결이 끝난 피보호자에 관한 알림도 목록에 남는데(Q3 결정), FE가 그 `wardId`로 이력 화면에 보내면 D-1·연결 인가 때문에 403(`ANOMALY_NOT_AUTHORIZED`·`SOS_NOT_AUTHORIZED` 등)이 난다. FE 전달 절의 "이동 화면"에 이 경우가 빠져 있다. → **조치안**: FE 전달 절에 "이동한 화면이 403이면 '연결이 끝난 피보호자의 기록은 볼 수 없습니다' 안내 후 목록에 머문다"를 적는다(문서만).

## PHASE C 구조·계약 - PASS (🟢 3)

- 트랜잭션: 조회 `readOnly`, 쓰기는 저장소 메서드의 `@Transactional @Modifying(clearAutomatically)` 한 번. 발송 경로·기록 시점·관리자 API 무변경(엔티티에 칼럼만 추가, 빌더 그대로).
- N+1 없음: 피보호자 이름 `findAllById` 한 번, 본인 알림이면 조회 생략(테스트 고정).
- 인덱스: Hibernate가 보낸 실제 SQL이 V67 부분 인덱스 조건과 맞는다 - 실서버 API 호출 뒤 `idx_notification_log_recipient_visible`·`_unread`의 `idx_scan`이 각 1. 안 읽은 수는 index-only scan.
- 🟢 **L-1**: 지금 데이터 규모(전체 925행, 수신자당 최대 161행)에서는 플래너가 목록 쿼리에 `created_at` 인덱스 + incremental sort를 고른다. 비용상 정상이며 규모가 커지면 다시 본다(조치 없음).
- 🟢 **L-2**: `read-all`·`read`는 90일 하한을 보지 않는다 - 보관 정리 전(최대 하루) 남은 기간 밖 행도 읽음 처리되고 `updated`에 셀 수 있다. 화면 영향 없음(그 행은 목록·개수에 없다). 조치 없음 또는 하한 추가.
- 🟢 **L-3**: Swagger `POST /{id}/read`에 숫자가 아닌 id의 400이 적혀 있지 않다. → 조치안: `@ApiResponse(400)` 한 줄.

## 정책 메모 (조치 불요, 문서화)

- 관리자가 역할을 바꾼 사용자(보호자 → 피보호자)도 예전에 받은 알림은 그대로 보인다. 역할 변경은 연결·카메라·말벗 기록을 지우지만, 이 목록은 "이미 받은 메시지의 사본"이라 Q3와 같은 판단이다. 탈퇴만 CASCADE로 지운다.

## PHASE D 테스트 - PASS

- 단위 31건 + 통합 5건. 조건을 하나씩 뺐다고 가정하면 각각 실패하는 테스트가 있다: 읽음의 NOT_SENT 조건(`읽음_멱등`), 목록 90일 하한(`보이는_행`), 모두 읽음의 수신자 조건(`모두_읽음`), 본인 알림 이름 생략(`목록_본인_알림`), IDOR 로그 소유자 미노출(`읽음_남의것`), 응답 필드(`응답_필드_고정`). (실제 변이 실행은 하지 않았다.)
- HTTP 경로 매핑·`category` 400·`read` 필드 직렬화는 MockMvc 테스트 대신 실서버 호출로 확인했다(필드 9개·400·404).

## 조치안 (승인 후)

| # | 내용 | 변경 | 커밋 초안 |
|---|---|---|---|
| M-1 | FE 전달 절에 연결 끝난 피보호자 이동 시 403 처리 안내 | 문서 | `docs: 내 알림 기록 FE 전달 - 연결 끝난 피보호자 이동 화면 안내` |
| L-3 | Swagger read 400 명시 | 코드(어노테이션) | `comment: 내 알림 읽음 API Swagger에 잘못된 id 400 명시` |
| L-2 | (선택) read·read-all에 90일 하한 | 코드 | `fix: 알림 읽음 처리도 보관 기간 안 알림만` |

## 조치 결과 (2026-11-04, 사용자 "전부 반영")

| # | 결과 |
|---|---|
| M-1 | FE 전달 절 "이동 화면"에 연결 끝난 피보호자 403 안내·목록 유지·D-1로 이력에 없을 수 있음을 추가. policy-notification에도 기록 |
| L-2 | `markRead`·`markAllRead`에 `created_at >= 하한` 추가 - 보관 기간 밖 알림 읽음은 404, 모두 읽음 `updated`는 안 읽은 수와 같은 범위. 단위 `읽음_보관기간_하한`·통합 `보관기간_밖_읽음_제외` 추가 |
| L-3 | Swagger 읽음 API에 400 `INVALID_INPUT`, 404 설명에 보관 기간·보내지 않은 알림 명시 |
| L-1 | 조치 없음(데이터 규모가 커지면 재확인) |
