# 미점검 API 전수 점검 - PR #230~#245

> 대상: 2026-08-06 ~ 2026-09-10 머지분 (커밋 `d872654..3de239c`, 49 커밋 / 16 PR, 마이그레이션 V38~V49)
> 기준 대장: `docs/audit-index.md` - 마지막 점검이 #227(2026-08-05)이라 그 이후 16개 PR이 점검 없이 머지돼 있었다.
> 점검일: 2026-09-10 · 점검자: Claude Code (점검만, 코드 미수정)
> 후속: 역할 영역별 횡단 점검은 `(2026-09-10) audit-role-boundary-guardian-ward-admin.md`

---

## PHASE -1. 사전 환경 확인

| 항목 | 결과 |
|---|---|
| HEAD | `3de239c` (docs, #245 강제 연결 직후), 워킹트리 클린, 브랜치 `dev` |
| 빌드 | `./gradlew clean build --no-daemon` **통과**(exit 0) |
| 테스트 | 107개 클래스 / **555 tests / 0 failures / 1 skipped**(`BackendApplicationTests` - 로컬 DB·Redis 필요로 `@Disabled`) |
| 마이그레이션 | V49가 최신 (V38~V49가 이번 범위, V38은 #229 점검 수정분) |

## PHASE 0. 점검 대상

### 대상 PR

| PR | 머지 | 내용 | 마이그레이션 |
|---|---|---|---|
| #230 | 08-07 | Swagger 태그 "<역할> - <도메인>" 재편 | - |
| #231 | 08-27 | SOS ACK 철회 + 발생 경로(`trigger_type`) | V39 |
| #232 | 08-27 | 미복용 요약 발송 시각 선택 | V40 |
| #233 | 08-27 | 미복용 요약 설정을 (보호자, 피보호자) 축으로 | V41 |
| #234·#235·#236 | 08-31~09-01 | 이상감지 상황(incident) 스키마 도입 → 롤백 → 복원 | V42·V43·V44 |
| #237 | 09-01 | 보호자 판정 API + 미응답 재촉 | V45 |
| #238 | 09-01 | WebSocket Origin 403 (yaml만) | - |
| #239 | 09-02 | 관리자 대시보드 집계 | - |
| #240 | 09-02 | FCM 토큰 누적 방지(상한·유휴 정리) | - |
| #241 | 09-02 | 관리자 이상감지 로그·정정 | V46 |
| #242 | 09-07 | 피보호자 목록 status 필터 | - |
| #243 | 09-09 | 관리자 회원관리 (RESTRICTED 상태) | V47·V48 |
| #244 | 09-09 | 정지 계정 알림 차단·정지 사유 | V49 |
| #245 | 09-10 | 관리자 강제 연결·해제 | - |

### 정책 문서 ↔ 코드 대조 (drift)

| 문서가 말하는 것 | 코드 | 판정 |
|---|---|---|
| 판정은 보호자만, 관리자는 2차 정정만 | `GuardianAnomalyService.submitFeedback` / `AdminAnomalyService.resolve`(REAL·FALSE_ALARM만) | 일치 |
| CONFLICTED는 다수결 없음, 관리자 확정 후 재계산 안 함 | `calculateStatus`가 distinct>1이면 CONFLICTED / `applyReviewStatus`가 `isAdminResolved()`면 무시 | 일치 |
| 재촉: 닫힌 뒤 1시간 → 건별 1회 → 일 1회 요약 20:00, 마감 3일, 야간 22~08 미루기, FCM만 | `AnomalyReviewReminderPlanner` + `NotificationType.ANOMALY_REVIEW_REQUIRED(SETTINGS_ONLY, FCM)` | 일치 |
| 대시보드: AI 끊기면 `streamingCameras`·`disconnectedCameras` null, `longestWaitingHours` null, byType 0건 항목 없음 | `AdminDashboardService` | 일치 |
| 정지=RESTRICTED, 로그인 검사 `!= ACTIVE`, 정지 시 토큰 무효화 | `AuthService` 2곳·`KakaoAuthService` 1곳 부등호 / `UserRestrictedEvent` → `handleRestricted` | 일치 |
| 정지 계정 수신 차단은 `dispatch()` 한 곳, 상태 불명은 통과 | `NotificationDispatcher.resolveIfAllowed` / `NotificationRecipient.canReceive` | 일치 |
| 강제 탈퇴는 `forceWithdraw` 파이프라인, 감사 로그가 삭제보다 먼저 | `AdminUserService.forceDelete` | 일치 |
| 강제 연결은 양쪽 알림 + 감사 로그, PENDING 승격, 전용 문구 | `ConnectionService.forceConnect` + `handleForced` + `CONNECTION_FORCED`·`DisconnectedBy.ADMIN` | 일치 |
| **"개인 이력을 열람하는 관리자 API(이상감지 로그 등)는 감사 로그를 반드시 남긴다"** | `GET /api/admin/anomaly`·`GET /api/admin/user/{id}`는 **남기지 않음**(정정·수정만 기록) | **불일치 → M-3** |
| progress.md: "WebSocket - 정지되면 로그인 자체가 안 돼 세션 없음" | 핸드셰이크 시점만 검사, **이미 열린 세션은 유지** | **불일치 → M-4** |

### 신규·변경 엔드포인트 × 역할 인가 (22개)

| 엔드포인트 | 역할 게이트 | 자원 인가 | 결과 |
|---|---|---|---|
| `GET /api/guardian/anomaly/history` | GUARDIAN(클래스) | wardId 지정=`isActiveConnection` / 생략=`getActiveWardIds` | PASS |
| `POST /api/guardian/anomaly/{incidentId}/feedback` | GUARDIAN | 상황→ward → `isActiveConnection`, 관리자 확정 건 409 | PASS |
| `GET·PUT /api/guardian/anomaly/reminder-setting` | GUARDIAN | 본인(축 없음) | PASS |
| `GET /api/admin/anomaly` | ADMIN(경로+클래스) | 전체(정책상 연결로 좁히지 않음) | PASS (M-3 참조) |
| `PATCH /api/admin/anomaly/{incidentId}/review` | ADMIN | 감사 로그 `ANOMALY_REVIEW_RESOLVE` | PASS |
| `GET /api/admin/dashboard/safety`·`/operation` | ADMIN(경로+클래스) | 집계만, 감사 미기록(정책) | PASS |
| `GET /api/admin/user`·`/counts` | ADMIN(경로+클래스) | INACTIVE 제외 | PASS |
| `GET /api/admin/user/{userId}` | ADMIN | INACTIVE **미제외** | PASS (M-1 참조) |
| `PATCH /api/admin/user/{userId}` | ADMIN | ADMIN 대상 403, ADMIN 승격 400, INACTIVE 지정 400, WARD+RESTRICTED 400 | PASS (M-1 참조) |
| `DELETE /api/admin/user/{userId}` | ADMIN | ADMIN 대상 403, `forceWithdraw` 경유 | PASS (M-1 참조) |
| `POST /api/admin/connection` | ADMIN(경로+클래스) | 역할·상태 검증, ACTIVE 중복 409 | PASS |
| `DELETE /api/admin/connection/{connectionId}` | ADMIN | ACTIVE만, 양쪽 알림 | PASS |
| `GET /api/guardian/connection/select?status=` | GUARDIAN | 본인 연결, `WardListFilter` 전용 enum | PASS |
| `GET·PUT /api/guardian/ward/{wardId}/medication-alert-setting` | GUARDIAN | `isActiveConnection` (경로가 `/medication-alert-setting`에서 피보호자별로 이동) | PASS |
| `POST /api/ward/sos` (`triggerType` 추가) | WARD | 본인 | PASS |
| `PATCH /api/guardian/sos/{id}/ack` | **제거됨** (V39) | - | 확인 |

### 비-HTTP 실행 경로

- `AnomalyReviewReminderScheduler`(5분) → `AnomalyReviewReminderPlanner.claimReminders/claimSummaries`(@Transactional 선점) → `AnomalyReviewReminderService`(발송)
- `FcmTokenCleanupScheduler`(매일 04:00 KST) → `FcmService.cleanupStaleTokens`
- `MedicationReminderScheduler`(1분) → `MedicationMissedAlertPlanner`(보호자·피보호자별 시각, MIN/MAX 게이트)
- `ConnectionNotificationListener.handleForced`(AFTER_COMMIT + @Async) → 양쪽 WS + `CONNECTION_FORCED`
- `UserAccountEventListener.handleRestricted`(AFTER_COMMIT, REQUIRES_NEW) → refresh 삭제 + Redis 무효화 키
- `AnomalyDetectionService`(@Transactional) → `AnomalyIncidentService.resolveIncident`(승계/신규) → `anomaly_event.incident_id`

---

## PHASE A. 보안·인가

### IDOR - 전부 PASS

- 보호자 경로 4개 도메인(SOS·복약·복약설정·이상감지)이 모두 `getActiveWardIds`·`isActiveConnection`만 쓴다. `getMyWards` 호출자는 `GuardianConnectionController` 하나뿐(화면 조회용).
- 위반 시 403 + `[IDOR-ATTEMPT]` WARN, 응답에 소유자·내용 없음. 없는 자원은 404.
- 관리자 대상 조작 차단: `validateNotAdmin` → 403 `CANNOT_MODIFY_ADMIN` + `[ADMIN-MODIFY-BLOCKED]`. 본인(관리자) 계정도 같은 경로로 막힌다.
- `AdminUserService.getUsers` 키워드는 LIKE 메타문자(`\`·`%`·`_`) 이스케이프 + JPQL `escape '\'` - 인젝션·와일드카드 우회 없음.

### 감사 로그·CHECK 동기화 - PASS

- 회원 이름/역할/상태 변경·강제 탈퇴·강제 연결/해제·판정 정정 모두 `admin_audit_log` 기록. `AdminAuditLogService.log`는 REQUIRED라 호출 트랜잭션과 함께 커밋/롤백된다(CHECK 위반 시 본 작업까지 롤백되는 구조 - V46·V48이 그래서 필요했다).
- `UserStatusCheckSyncTest`·`AdminAuditActionCheckSyncTest`가 `V*.sql` 전체에서 **마지막 CHECK 정의**를 골라 enum 전수와 대조한다. V47·V48이 최신이고 통과.

### 정지 계정 - PASS + 한계 1건

- 로그인·재발급 3곳 모두 `!= ACTIVE`. `RESTRICTED` 즉시 토큰 무효화(`handleRestricted`). 알림은 `dispatch()` 한 곳에서 수신자 상태로 차단(테스트 5건이 강제 FCM·SMS 폴백·이상감지·INACTIVE·상태 불명 통과까지 고정).
- **한계**: WebSocket은 핸드셰이크 때만 무효화 키를 본다 → **M-4**.

### 입력 검증 - PASS

- `AdminUserUpdateRequest` `@Size(20/200)`, `AdminForceConnectRequest` `@NotBlank @Size(6)`, `AdminAnomalyReviewRequest` `@NotNull @Size(200)`, `AnomalyFeedbackRequest` `@NotNull`, `SosTriggerRequest` `@Size(100)`.
- 필터는 전용 enum(`AdminUserStatusFilter`·`AdminUserConnectionFilter`·`WardListFilter`)이고 잘못된 값은 `MethodArgumentTypeMismatchException` 핸들러가 400으로 응답한다.
- 페이지 크기 상한 50(관리자 3종·보호자 2종).

### PII·로그 - PASS

- 감사 로그 detail에 이름·이메일이 들어간다(강제 탈퇴·강제 연결). 관리자 전용 테이블이고 조회 API가 없어 노출면은 DB 직접 접근뿐 - 수용.
- `[NOTIFY-BLOCKED]`·`[IDOR-ATTEMPT]`·`[ADMIN-MODIFY-BLOCKED]` WARN은 userId만 남긴다.

---

## PHASE B. 기능 정합성

| 항목 | 확인 | 결과 |
|---|---|---|
| 상황 승계 | 같은 (ward, session, type) + 직전 감지 10분 이내 + KST 같은 날 + 시각 역행 아님 | PASS |
| 1인 1표·번복 | `upsertMyFeedback` UPDATE, `uq_anomaly_feedback` | PASS |
| 다수결 금지 | distinct verdict >1 → CONFLICTED | PASS |
| 관리자 정정 | REAL·FALSE_ALARM만(400), 재정정 허용, 응답 원본 보존, 알림 없음 | PASS (L-1 참조) |
| 재촉 시작 시점 | `lastDetectedAt <= now - (merge 10 + delay 60)` | PASS |
| 한 명 응답 시 중단 | 후보를 `reviewStatus = PENDING`으로만 조회 → 응답 즉시 후보에서 빠짐, 응답 API는 열림 | PASS |
| 야간 억제 = 미루기 | `isQuietHours`면 선점 없이 빈 목록 반환 | PASS |
| 선점 후 발송 | Planner가 @Transactional로 로그 커밋 → Service가 트랜잭션 밖에서 dispatch | PASS |
| 재촉 채널 FCM만 | `allowedChannels = {FCM}`, 강제 FCM은 줄이지 않음(테스트 고정) | PASS |
| 알림톡 매핑 금지 종류 | yaml `templates`는 `ANOMALY_DETECTED`뿐 | PASS |
| 대시보드 null 정책 | AI 끊김 → `streamingCameras`·`disconnectedCameras` null / 대기 문의 0건 → `longestWaitingHours` null / `byType` 집계된 유형만 / review 4값 | PASS |
| 복약 시각 (보호자, 피보호자) 축 | `uq_guardian_medication_setting(guardian_id, ward_id)`, 요청 null=미변경, 분 단위 절삭 | PASS |
| 집계 상한 = 발송 시각 | `count(..., setting.alertTime())`가 `doseTime > cutoff`를 제외, 문구에 시각 포함 | PASS |
| Planner MIN/MAX 게이트 | `findEarliestAlertTime`/`findLatestAlertTime`(enabled=true만) + 기본값 | PASS |
| SOS ACK 철회 | `SosAckStatus`·리스너·DTO·컬럼 모두 제거, `trigger_type`은 이력 표시 전용(리스너·디스패처 미참조) | PASS |
| 연결 필터 | 생략=ACTIVE+PENDING, 마스킹은 `status == ACTIVE`에서만 | PASS |
| 역할 변경 연결 정리 | ACTIVE→`disconnect`+알림 / PENDING→`cancel` 무알림 | PASS (M-2 참조) |
| 피보호자 정지 금지 | `validateResultingCombination`(바뀐 뒤 조합) | PASS |
| 강제 연결 | PENDING 승격 / 없으면 생성 후 `activate` / RESTRICTED·INACTIVE 400 / ACTIVE 중복 409 | PASS |
| FCM 상한·유휴 정리 | 신규 등록 후 상한 5 초과 시 오래된 순 삭제 / 60일 미갱신 삭제 / 같은 사용자 재등록은 `touch` | PASS (L-2 참조) |

---

## PHASE C. 구조·계약

- **@Transactional 경계**: `AdminUserService.forceDelete`는 의도적으로 무트랜잭션(2단계). `updateUser`는 한 트랜잭션에서 감사 로그까지 묶인다. Planner 2종은 선점 트랜잭션, 발송은 밖. `AnomalyIncidentService.resolveIncident`는 `@Transactional`이 없지만 호출자 `AnomalyDetectionService.handle`이 트랜잭션이라 문제없음(단독 호출 시 주의).
- **AFTER_COMMIT**: `handleRestricted`는 REQUIRES_NEW + try/catch best-effort. `handleForced`는 @Async. 이전 점검 H-1(탈퇴 리스너의 REQUIRED 전파)은 이번 범위에 변경 없음 - **여전히 미실측**.
- **N+1**: 관리자 회원 목록(연결 일괄 조회 후 배분), 이상감지 목록(카메라 라벨·응답·이름 IN 조회), 재촉 후보(이름·라벨·설정 일괄) 모두 배치 조회. 상대 이름 키워드 검색은 서브쿼리라 인덱스를 못 타지만 회원 규모에서 수용(주석에 명시).
- **인덱스**: V44 3종(ward+started / status+started / merge 키), V45 reminder(guardian, incident), V41 ward_id. 대시보드 `findByStartedAtGreaterThanEqual`은 started_at 단독 인덱스가 없지만 하루치 범위라 수용.
- **마이그레이션 순차 적용**: V42(생성)→V43(전부 DROP)→V44(재생성)→V45·V46은 빈 DB에서 순서대로 문제없음. V41의 `DELETE FROM guardian_medication_setting`은 비가역이며 배포 2곳 0건 확인 후 넣었다고 문서에 기록됨. V39 컬럼 DROP도 비가역(ack 전건 NULL 확인 기록 있음).
- **계약**: 새 응답 DTO는 record + `@Schema`. 관리자 목록은 `PageResponse`, 필터 enum은 Swagger `allowableValues`와 일치. `ANOMALY_ALREADY_RESOLVED` 409·`ANOMALY_INVALID_REVIEW_STATUS` 400·`WARD_CANNOT_BE_RESTRICTED` 400·`CONNECTION_TARGET_NOT_ACTIVE` 400 - 의미와 상태코드 일치. 강제 연결의 WS 이벤트명은 L-5 참조.

---

## PHASE D. 테스트

- 신규 테스트 12개 클래스 + 수정 21개. 핵심 정책이 테스트로 고정돼 있다: 다수결 금지·관리자 확정 후 409·재정정·응답 원본 보존 / 재촉 6조건(응답자·기재촉·설정 OFF·연결 해제·야간·후보 조건) / 대시보드 null 3종 / 정지 계정 알림 차단 5종 / 회원 수정 조합 검사·INACTIVE 거부·ADMIN 승격 거부 / 강제 탈퇴 순서·purge 재시도 / CHECK 동기화 2종.
- **미커버**: `AdminUserController`·`AdminConnectionController`·`GuardianAnomalyController`의 역할 게이트(MockMvc 보안 테스트 없음) → M-5. 역할 변경 시 토큰 무효화 부재(M-1)와 INACTIVE 대상 수정(M-1)은 테스트가 없어서 통과한 것.
- 목 기반 한계(이전 점검 M-1 계승): V39~V49 DDL, `uq_anomaly_feedback`·`uq_anomaly_review_reminder`·`uq_guardian_medication_setting` UNIQUE 경합, 재촉 선점 트랜잭션의 실제 커밋 순서는 실 DB에서 검증된 적 없다.

---

## 이슈

### 🔴 Critical - 없음

### 🟠 High - 없음

### 🟡 Medium

**M-1. 역할 변경이 기존 access token을 끊지 않는다** - `AdminUserService.applyRole`
- `JwtAuthenticationFilter`는 토큰의 `role` 클레임으로 `ROLE_*` 권한을 만들고 DB를 읽지 않는다. 정지는 `UserRestrictedEvent`로 무효화 키를 세우지만 **역할 변경은 이벤트가 없어** 옛 역할의 access token이 만료(30분)까지 `@PreAuthorize`를 통과한다. refresh는 DB 역할로 재발급하므로 그 뒤에는 바로잡힌다.
- 실효 피해는 제한적이다: 연결이 이미 정리돼 보호자 데이터 API는 빈 결과·403이고 연결 요청은 DB 역할 검사로 400이다. 다만 옛 WARD는 30분간 SOS 발신·카메라 등록·복용 체크(WARD API)를 계속 할 수 있고, "역할 게이트는 @PreAuthorize"라는 전제가 그 시간 동안 거짓이 된다. 2026-09-09 규칙("정지는 토큰까지 끊어야 즉시 듣는다")이 역할 변경에도 똑같이 적용돼야 한다.
- 제안: `UserRoleChangedEvent` 발행 → `UserAccountEventListener`가 `handleRestricted`와 같은 무효화 경로를 태운다. 테스트 1건 추가.

**M-2. 관리자 수정·상세·강제 탈퇴가 INACTIVE(탈퇴 진행·좀비) 계정을 대상으로 허용된다** - `AdminUserService.getUserOrThrow`
- 목록·탭 건수는 INACTIVE를 제외하는데(`searchForAdmin`, "관리자가 할 일이 없다"), `getUser`·`updateUser`·`forceDelete`는 `findById`만 한다. 목록에는 없지만 ID를 알면 조작된다.
- `PATCH {status: ACTIVE}` → `User.activate()`가 INACTIVE를 ACTIVE로 되돌려 **탈퇴 진행 중이던 계정이 되살아난다**. 이미 리스너가 refresh 토큰·연결(DISCONNECTED)·FCM을 정리한 뒤라 반쪽 계정이고, 스윕(`INACTIVE`만 회수)도 더는 지우지 않는다. `{status: RESTRICTED}`도 같은 효과(INACTIVE → RESTRICTED). "탈퇴는 상태 변경이 아니라 삭제"(2026-09-09 규칙 ①)에 정면으로 어긋난다.
- `DELETE` on INACTIVE → `forceWithdraw`가 `UserWithdrawnEvent`를 다시 발행해 WITHDRAW 접속로그가 중복 기록된다(해는 작음).
- 제안: `getUserOrThrow`에서 `status == INACTIVE`면 404(`USER_NOT_FOUND`) - 목록과 같은 모집단. 테스트 2건(수정·삭제).

**M-3. 역할 변경이 연결만 정리하고 역할 귀속 데이터는 남긴다** - `AdminUserService.applyRole`
- WARD → GUARDIAN: `camera.ward_id`가 그대로라 **AI 구독이 계속되고** 감지되면 `ANOMALY_DETECTED_SELF`가 본인에게 나간다. 본인은 이제 GUARDIAN이라 카메라 API(WARD 전용)를 못 써 **카메라를 지울 수 없는 고아**가 되고, 대시보드 `countDistinctWards`·`wardsWithoutCamera`가 어긋난다. `sos_setting`·`medication_setting`도 남는다(무해).
- GUARDIAN → WARD: `guardian_medication_setting`·`guardian_anomaly_setting`이 남고(무해), 그가 등록한 약(`created_by`)은 피보호자 자산이라 남은 ACTIVE 보호자가 관리할 수 있어 문제없다. 단 탈퇴와 달리 `MEDICATION_STOPPED` 안내는 없다(약이 삭제되지 않으니 맞다).
- 제안(결정 필요): ① 역할 변경 시 카메라를 함께 삭제하고 감사 로그 detail에 건수 기록, 또는 ② 카메라가 있으면 400으로 막고 "카메라를 먼저 정리하라"고 안내. 시니어 계정을 잘못 만든 것을 바로잡는 용도라면 ①이 실무적이다.

**M-4. 정책 문서와 구현이 어긋남 - 관리자 "열람" 감사 로그**
- `.claude/rules/domain-security-policy.md`(2026-09-01·02)는 "개인 이력을 열람하는 관리자 API(PR ④ 이상감지 로그 등)는 반드시 남긴다"고 적었으나 `GET /api/admin/anomaly`·`GET /api/admin/user/{id}`·`GET /api/admin/user`는 남기지 않는다. `AdminUserService` Javadoc은 "조회는 남기지 않는다"고 반대로 적혀 있다. 코드 의도는 일관되게 "쓰기만 기록"이고 PR ④ 문서도 정정에 대해서만 말한다.
- 페이징·폴링 조회를 전부 남기면 공지 수정 같은 실제 조작 이력이 묻히므로(대시보드 결정과 같은 이유) **문서 쪽을 "개인 이력을 변경하는 관리자 API는 반드시 남긴다"로 고치는 것**을 권한다. 열람 감사를 원하면 별도 결정.

**M-5. 정지된 계정의 열린 WebSocket 세션이 유지된다** - `JwtHandshakeInterceptor`
- 무효화 키(`PASSWORD_INVALIDATE`)는 핸드셰이크에서만 검사한다. 정지 시점에 이미 연결된 STOMP 세션은 끊기지 않아 `anomaly-detected`·`medication-taken`·`connection-*` 실시간 이벤트가 그 브라우저에 계속 도착한다. HTTP는 즉시 401이므로 프론트가 로그아웃 처리하며 소켓을 닫을 가능성이 높지만 백엔드가 보장하지 않는다.
- progress.md(2026-09-09)는 "정지되면 로그인 자체가 안 돼 세션 없음"으로 적어 **이미 열린 세션**을 놓쳤다. 정책은 "WebSocket은 추상화 밖"이라 정책 위반은 아니고 문서화된 한계도 아닌 상태.
- 제안: `handleRestricted`에서 `SimpUserRegistry`로 해당 userId 세션을 닫거나, 최소한 정책 문서에 "정지 시 열린 WS 세션은 만료·재접속 전까지 유지" 한계를 명시.

**M-6. 새 컨트롤러 3종의 역할 게이트 테스트 부재**
- `AdminUserController`·`AdminConnectionController`·`GuardianAnomalyController`에 MockMvc 보안 테스트가 없다(7개 보안 테스트 중 미포함). 관리자 2종은 `/api/admin/**` 경로 규칙이 겹쳐 실질 위험은 낮지만 CLAUDE 원칙("경로 규칙만 두면 테스트로 고정할 수 없다")과 어긋난다. `GuardianAnomalyController`는 경로 규칙 없이 클래스 `@PreAuthorize`만이 게이트라 **테스트가 유일한 회귀 방어**인데 없다.
- 제안: `AdminAnomalyControllerSecurityTest`를 본떠 3건 추가(ADMIN/GUARDIAN/WARD × 허용·403).

### 🟢 Low

**L-1.** `AdminAnomalyService.resolve` 재정정 시 `note`를 생략하면 이전 메모가 null로 덮인다. 감사 로그에는 남으니 추적은 되지만 화면에서 사라진다. 재정정 시 null이면 기존 메모 유지가 자연스럽다.

**L-2.** `FcmService.registerToken` 공유 디바이스 경로(`reassignTo`)는 `enforceMaxPerUser`를 호출하지 않는다. 새 소유자 입장에서는 토큰이 1개 늘어나므로 상한(5)을 넘을 수 있다. 문서의 "재등록은 개수를 늘리지 않는다"는 같은 사용자 재등록에만 참이다.

**L-3.** `InquiryService` 클래스 Javadoc이 "404 위장"이라 적혀 있으나 2026-07-14에 403으로 바뀌었다. 주석만 오래됐다.

**L-4.** `anomaly_incident.resolved_by`가 `ON DELETE SET NULL`이라 정정한 관리자 행이 삭제되면 `isAdminResolved()`가 false로 돌아가 보호자 응답으로 상태가 다시 바뀔 수 있다. 관리자 삭제 경로가 API에 없어 현재는 이론상.

**L-5.** `handleForced`가 피보호자에게도 WS 이벤트명 `connection-accepted`를 재사용한다. 피보호자 화면이 그 이벤트를 구독하지 않으면 실시간 갱신이 빠진다(FCM은 감). FE 계약 확인 항목.

**L-6.** `AdminUserService.forceDelete`에서 `getUserOrThrow`가 트랜잭션 밖이고 `forceWithdraw`가 다시 읽는다. 사이에 상태가 바뀌어도 `forceWithdraw`는 검사 없이 `deactivate()`한다(M-2와 같은 뿌리). M-2를 고치면 함께 해결.

---

## 종합 판정

**⚠️ 점검됨(잔여 이슈 있음)** - Critical·High 없음. IDOR·역할 인가·정지 차단·감사 로그·CHECK 동기화·재촉 절제·대시보드 null 정책은 모두 코드와 테스트로 확인됐다. Medium 6건은 ① 역할 변경의 토큰·데이터 정리 누락(M-1·M-3), ② INACTIVE 대상 조작(M-2), ③ 문서 drift 2건(M-4·M-5), ④ 테스트 공백(M-6)이다. M-1·M-2·M-6은 작은 수정으로 닫히고, M-3·M-4는 결정이 필요하다.

### 이전 잔여 이슈 상태

| 이슈 | 상태 |
|---|---|
| H-1 탈퇴 리스너 REQUIRED 전파 (2026-08-05) | **변경 없음, 미실측** - 이번 범위에 해당 리스너 수정 없음 |
| M-1 실 DB 통합 테스트 부재 (2026-08-05) | **변경 없음** - `BackendApplicationTests`는 여전히 `@Disabled`, Testcontainers 미도입. V39~V49도 목으로만 검증 |

---

## 수정용 커밋 메시지 초안 (승인 후)

```
fix: 역할 변경 시 토큰 무효화 + INACTIVE 계정 관리자 조작 차단 (M-1·M-2)

- UserRoleChangedEvent 신설, UserAccountEventListener가 정지와 같은 무효화 경로를 탄다
- AdminUserService.getUserOrThrow가 INACTIVE면 404 (목록과 같은 모집단)
- 관련 테스트 3건 추가

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```

```
test: 관리자 회원관리·강제 연결·보호자 이상감지 컨트롤러 역할 게이트 테스트 (M-6)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```

```
docs: 관리자 감사 로그 정책을 "변경 시 기록"으로 정정, 정지 시 WS 세션 한계 명시 (M-4·M-5)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```
