# 역할 영역별 횡단 점검 - 보호자 / 피보호자 / 관리자

> `(2026-09-10) audit-unaudited-prs-230-245.md`(세로 점검)의 후속. 도메인마다 따로 만든 인가 로직이 **역할 경계에서 서로 어긋나는 지점**을 찾기 위해 역할 하나의 눈으로 전체 API를 가로로 본다.
> 점검일: 2026-09-10 · HEAD `3de239c` · 빌드·테스트 결과는 세로 점검과 동일(555 tests / 0 failures) · 코드 미수정
> 세로 점검에서 이미 보고한 이슈(M-1~M-6)는 참조만 하고 재보고하지 않는다.

---

## PHASE 0. 역할별 API 지도

컨트롤러 27개를 게이트 기준으로 분류했다.

| 영역 | 게이트 | 컨트롤러 |
|---|---|---|
| **보호자** (9) | 클래스 `@PreAuthorize("hasRole('GUARDIAN')")` | GuardianConnection · GuardianCamera · GuardianInquiry · GuardianMedication · GuardianSos · GuardianAnomaly |
| **피보호자** (7) | 클래스 `@PreAuthorize("hasRole('WARD')")` | WardConnection · WardCamera · WardMedication · WardSos · WardSosSetting |
| **관리자** (8) | `/api/admin/**` 경로 규칙 (+ 신규 4종은 클래스 `@PreAuthorize` 이중) | AdminUser · AdminConnection · AdminDashboard · AdminAnomaly(이중) / AdminAnnouncement · AdminAnnouncementDraft · AdminInquiry(경로만) |
| **공용** | `authenticated()` / `permitAll` | User · NotificationSetting · Notification(FCM 토큰) · Announcement(`/api/commonness`) · Auth 5종 |

### 같은 자원을 두 역할이 다른 경로로 다루는 지점

| 자원 | 보호자 | 피보호자 | 관리자 |
|---|---|---|---|
| 연결 | 요청·취소·해제·목록(`/select`, `/requests`) | 수락·거절·해제·목록(`/active`, `/pending`) | 강제 연결·해제, 회원 상세에서 조회 |
| 카메라 | ACTIVE 피보호자 카메라 목록(읽기) | 본인 카메라 CRUD | 대시보드 집계만 |
| 복약 | 약 등록·수정·삭제, 피보호자 복약 알림 설정, 미복용 요약 설정 | 오늘 일정, 복용 체크·해제 | 없음 |
| SOS | 이력 조회 | 발생, 동작 설정 | 없음 |
| 이상감지 | 이력 조회, 판정(1차), 재촉 설정 | 없음(알림만 수신) | 전체 로그, 정정(2차) |
| 문의 | 작성·본인 목록·상세 | 없음 | 전체 목록·상세·답변 |

### 인가 근거 메서드 전수 (보호자 → 피보호자 자원)

| 경로 | 근거 |
|---|---|
| `GuardianSosService` | `getActiveWardIds` / `isActiveConnection` |
| `GuardianMedicationService`·`GuardianMedicationSettingService` | `getActiveWardIds` / `isActiveConnection` |
| `GuardianAnomalyService` | `getActiveWardIds` / `isActiveConnection` |
| `CameraService.getConnectedWardCameras` | **`connectionRepository.findByGuardianIdAndStatusIn(ACTIVE)` 직접 호출** (결과는 `getActiveWardIds`와 동일) |
| `ConnectionService.getMyWards` | 화면 조회 전용, 인가 호출자 0곳 |

---

## 1부. 보호자(GUARDIAN)

### A. 역할 경계 보안

| 항목 | 확인 | 결과 |
|---|---|---|
| 피보호자 자원 인가 통일 | 4개 도메인이 `getActiveWardIds`·`isActiveConnection`, 카메라만 리포지토리 직접 호출 | PASS (G-1 참조) |
| 연결 종료 후 과거 이력 즉시 비공개 | 모든 조회가 **요청 시점** ACTIVE 연결로 판정. 캐시·스냅샷 없음 | PASS |
| PENDING 피보호자 정보 노출 | `ConnectionResponse.fromGuardianView`가 `status == ACTIVE`일 때만 연락처·주소·이메일·성별·생년월일 채움. `status=PENDING` 필터로 좁혀도 동일 | PASS |
| 보호자에게 열리면 안 되는 것 | 복용 체크 API 없음(클래스 게이트 + `MedicationControllerSecurityTest`) / 피보호자 SOS 동작 설정 접근 없음 / 이상감지 정정 API 없음(403 테스트) / 타 보호자의 재촉·미복용 설정은 principal 축이라 접근 불가 | PASS |
| 문의 | 본인 문의만(`inquiry.userId == principal`), 위반 403 + `[IDOR-ATTEMPT]` | PASS |
| 역할 변경 후 옛 토큰 | 세로 점검 **M-1** | 참조 |

### B. 기능 정합성 - 보호자가 받는 알림

| 종류 | 정책 | 끄기 | 확인 |
|---|---|---|---|
| `WARD_SOS` | 강제 FCM + SMS 폴백 | 불가 | PASS |
| `ANOMALY_DETECTED` | 강제 FCM + 설정(SMS·알림톡) | FCM 불가 | PASS |
| `ANOMALY_REVIEW_REQUIRED` | 설정, FCM만 | `guardian_anomaly_setting.review_reminder_enabled` | PASS |
| `MEDICATION_MISSED` | 설정 | `guardian_medication_setting.missed_alert_enabled` (피보호자별) | PASS |
| `MEDICATION_STOPPED`·`CONNECTION_*`·`INQUIRY_ANSWERED` | 설정 | 채널 설정 | PASS |

정지된 보호자는 위 전부 차단(수신자 기준), 정지된 피보호자가 원인인 알림은 발송 - 세로 점검에서 확인.

### 보호자 이슈

**G-1 🟢** `CameraService.getConnectedWardCameras`만 연결 상태를 리포지토리에서 직접 판정한다. 지금은 `getActiveWardIds`와 결과가 같지만, "연결 판정 로직은 connection 도메인 안에 둔다"(2026-07-30 규칙)에서 벗어난 유일한 경로다. 인가 정책이 바뀔 때(예: RESTRICTED 상대 제외) 이 한 곳만 따라오지 않는다. `connectionService.getActiveWardIds(guardianId)`로 교체 권고(동작 변화 없음).

---

## 2부. 피보호자(WARD)

### A. 역할 경계 보안

| 항목 | 확인 | 결과 |
|---|---|---|
| 본인 자원만 | 카메라 `camera.wardId == principal`(403 `CAMERA_NOT_AUTHORIZED`) / 복약 `medication.wardId == principal`(403 `MEDICATION_NOT_OWNED`) / SOS 동작 설정·오늘 일정·연결은 principal 축 | PASS |
| 피보호자에게 없어야 하는 것 | 약 등록·수정·삭제(보호자 클래스 게이트) / 이상감지 1차 판정(GUARDIAN 게이트, 관리자 경로도 없음) / 타인 카메라 / 미복용 요약·재촉 설정 | PASS |
| 보호자 정보 역방향 노출 | `ConnectionResponse.fromWardView`가 ACTIVE에서만 보호자 연락처·주소·이메일 공개. PENDING 요청 목록(`PendingConnectionResponse`)은 이름·마스킹 전화·관계만 | PASS |
| 정지 금지 | `validateResultingCombination`이 "바뀐 뒤 조합"으로 WARD+RESTRICTED 차단, 두 단계 우회 테스트 존재 | PASS |
| 로그인·SOS 안전망 | WARD는 RESTRICTED가 될 수 없고, SOS는 `WARD_SOS` 강제 발송 + `sos_event` 무조건 기록. `SosAction`·`trigger_type`은 알림을 가르지 않음(리스너·디스패처 미참조) | PASS |
| 강제 연결 동의 대체 | 피보호자에게 `CONNECTION_FORCED`("관리자가 OOO님을 보호자로 연결했습니다") + WS. 강제 해제도 양쪽 | PASS |

### B. 기능 정합성 - 피보호자가 받는 알림

| 종류 | 채널 | 확인 |
|---|---|---|
| `MEDICATION_REMINDER` | FCM·문자(설정), 알림톡 매핑 없음, WS 없음 | PASS |
| `ANOMALY_DETECTED_SELF` | 강제 FCM + 설정, **알림톡 매핑 없음**(yaml `templates`에 `ANOMALY_DETECTED`만) | PASS |
| `CONNECTION_REQUEST`·`CONNECTION_FORCED`·`CONNECTION_DISCONNECTED` | 설정 | PASS |
| 야간 억제 | 재촉(보호자 대상)에만 적용. 피보호자 본인 화재 알림은 억제 없음 | PASS |

### 피보호자 이슈

**W-1 🟡 (세로 점검 M-3의 피보호자 관점)** WARD → GUARDIAN 역할 변경 뒤 본인 카메라를 더는 관리할 수 없다. 카메라 API가 WARD 전용이라 삭제 경로가 사라지고 AI 구독은 계속된다. 피보호자 계정을 잘못 만든 시니어 가족이 역할을 바꿔 달라고 문의하는 시나리오가 정확히 이 경로다.

**W-2 🟢** `GuardianCameraView`가 `sessionId`를 보호자에게 내려준다. sessionId는 AI 스트림 식별자라 보호자가 스트림을 보려면 필요하지만, 연결 해제 뒤에도 보호자 브라우저에 남은 값으로 AI 서버에 직접 붙을 수 있는지는 **AI 서버의 인증 방식에 달려 있다**(백엔드 밖). `AI_API_KEY`가 백엔드 전용이면 무해. 확인 항목으로만 남긴다.

---

## 3부. 관리자(ADMIN)

### A. 역할 경계 보안

| 항목 | 확인 | 결과 |
|---|---|---|
| ADMIN 게이트 전수 | `/api/admin/**` `hasRole("ADMIN")` 경로 규칙이 8개 컨트롤러 전부를 덮음. 신규 4종만 클래스 `@PreAuthorize` 추가 | PASS (A-1 참조) |
| 관리자 대상 조작 차단 | 회원 수정·삭제 403 `CANNOT_MODIFY_ADMIN` + `[ADMIN-MODIFY-BLOCKED]`. 강제 연결은 역할 검사(GUARDIAN·WARD만)로 ADMIN 배제 | PASS |
| ADMIN 생성 경로 | 역할 변경 `role=ADMIN` 400 `INVALID_ROLE`. 가입 API는 WARD·GUARDIAN만. 다른 경로 없음 | PASS |
| 관리자가 하면 안 되는 것 | 1차 판정 API 없음 / 대신 체크 API 없음 / 이메일·전화 변경 없음(`AdminUserUpdateRequest`에 필드 자체가 없음) | PASS |
| 강제 조작의 알림·문구 | 연결 `CONNECTION_FORCED` 양쪽 / 해제 `DisconnectedBy.ADMIN` 양쪽 / 강제 탈퇴는 일반 탈퇴 리스너(상대에게 "보호자/피보호자가 해제했습니다" - 탈퇴와 같은 문구, 수용) / 정지·역할 변경은 본인 알림 없음(정지는 로그인 시 403 문구로 인지) | PASS |
| 개인 이력 조작의 감사 로그 | 회원 이름/역할/상태·강제 탈퇴·강제 연결/해제·판정 정정·공지 CRUD·초안 CRUD/발행 = 기록 / **문의 답변 = 미기록** | A-2 참조 |
| 열람 감사 | 세로 점검 **M-4**(문서 drift) | 참조 |
| INACTIVE 대상 | 세로 점검 **M-2** | 참조 |

### B. 기능 정합성

| 항목 | 확인 | 결과 |
|---|---|---|
| 필터 enum 전용 타입 | 회원 `AdminUserStatusFilter`(ALL·ACTIVE·RESTRICTED)·`AdminUserConnectionFilter` / 이상감지 `AnomalyReviewStatus`(전체 값이 조회 대상이라 전용 타입 불요) / 문의 `InquiryStatus` | PASS |
| null vs 0 | 관리자 계정 `connectionState` null / 대시보드 null 3종 / 탭 건수는 0도 키 유지(사실 그대로) | PASS |
| 모집단 일치 | 목록·탭 건수 모두 INACTIVE 제외. 대시보드 `totalUsers`는 ACTIVE만(RESTRICTED 제외, 알려진 한계로 기록됨) | PASS |

### 관리자 이슈

**A-1 🟡** 관리자 컨트롤러 8개 중 **기존 3종(공지·공지 초안·문의)은 경로 규칙만** 게이트다. 신규 4종이 이중 게이트를 택한 이유("경로 규칙만 두면 테스트로 고정할 수 없고 경로가 바뀌면 조용히 열린다")가 기존 3종에는 소급되지 않았다. 지금 위험은 없지만 기준이 둘이면 다음 컨트롤러가 어느 쪽을 따를지 정해지지 않는다. 3종에 클래스 `@PreAuthorize("hasRole('ADMIN')")` 추가 권고(동작 변화 없음).

**A-2 🟡** `AdminInquiryService.answer`가 감사 로그를 남기지 않는다. 보호자 개인 문의를 열어 답변하는 **쓰기 조작**인데 `AdminAuditAction`에 값이 없다. 2026-07-03 점검 당시엔 기준이 없었고, 2026-09-02에 "개인 이력을 여는 관리자 조작은 남긴다"가 세워진 뒤 기존 경로가 따라오지 않은 것. 추가한다면 `INQUIRY_ANSWER` enum + **CHECK 재정의 마이그레이션(V50) 필수**(`AdminAuditActionCheckSyncTest`가 막는다).

**A-3 🟢** 강제 탈퇴 시 연결 상대는 "보호자가 연결을 해제했습니다"(탈퇴와 같은 문구)를 받는다. 관리자가 지운 것이지만 상대 입장에서 "그 사람 계정이 사라졌다"는 사실은 같아 거짓은 아니다. 강제 연결·해제처럼 전용 문구를 둘지는 선택.

---

## 4부. 횡단(두 영역 이상)

| 항목 | 확인 | 결과 |
|---|---|---|
| 토큰·세션 무효화 | 탈퇴·비밀번호 변경·정지 = refresh 삭제 + Redis 키. **역할 변경 = 없음**(M-1). WS는 핸드셰이크에서만 검사(M-5) | 참조 |
| STOMP 구독 인가 | `/topic/{userId}/**`를 세션 userId와 대조하는 범용 검사. 새 토픽(`anomaly-detected`·`medication-taken`·`connection-accepted` 재사용)도 자동 보호, 화이트리스트 없음 | PASS |
| 403 문구 수신자 기준 | `MEDICATION_NOT_AUTHORIZED`(보호자)·`MEDICATION_NOT_OWNED`(피보호자) 분리 / `SOS_NOT_AUTHORIZED`·`ANOMALY_NOT_AUTHORIZED`는 보호자 경로뿐이라 하나 / `CAMERA_NOT_AUTHORIZED`는 피보호자 경로뿐 / `INQUIRY_NOT_AUTHORIZED` 보호자 | PASS |
| URL prefix | `/api/guardian`·`/api/ward`·`/api/admin` 규칙 준수. 공용은 `/api/user`·`/api/notifications`·`/api/commonness`·`/api/auth` | PASS (X-1 참조) |
| 역할별 응답 DTO 노출 필드 | 보호자 뷰 = 피보호자 연락처·주소·이메일·성별·생년월일(ACTIVE) / 피보호자 뷰 = 보호자 연락처·주소·이메일(ACTIVE) / 관리자 = 이메일·전화·상태 사유. 이상감지·SOS 이력은 이름·시각·위치·카메라 라벨만 | PASS |
| 역할 변경의 데이터 잔존 | M-3 | 참조 |

**X-1 🟢** 공용 알림 API가 두 prefix에 흩어져 있다: FCM 토큰은 `/api/notifications/fcm-token`, 채널 설정은 `/api/user/me/notification-settings`. 기능상 문제는 없고 Swagger 태그는 "공통 - 알림 설정" 하나로 묶여 있다. 정리한다면 다음 계약 변경 때.

---

## PHASE D. 역할 경계 테스트 집계

| 영역 | MockMvc 역할 게이트 테스트 | 미커버 컨트롤러 |
|---|---|---|
| 보호자 | GuardianSos · Medication(보호자 부분) · Camera(보호자 부분) | GuardianConnection · GuardianInquiry · **GuardianAnomaly** |
| 피보호자 | WardSos · WardSosSetting · Medication(피보호자 부분) · Camera(피보호자 부분) | WardConnection |
| 관리자 | AdminDashboard · AdminAnomaly | **AdminUser · AdminConnection** · AdminAnnouncement · AdminAnnouncementDraft · AdminInquiry |
| 공용 | - | User · NotificationSetting · Notification |

- 서비스 단위 테스트는 `isActiveConnection`을 목으로 세워 403을 검증하므로, 누군가 `getMyWards`로 바꾸면 목이 호출되지 않아 실패한다 - 인가 헬퍼 교체는 간접적으로 잡힌다.
- 클래스 `@PreAuthorize`만이 게이트인 컨트롤러(보호자·피보호자 전부, 관리자 신규 4종)는 MockMvc 테스트가 유일한 회귀 방어다. 위 표의 미커버 8개 중 세로 점검 M-6(3개)을 우선하고, 나머지는 다음 변경 때 함께 추가.

---

## 영역별 종합 판정

| 영역 | 판정 | 요지 |
|---|---|---|
| 보호자 | **PASS** | 인가 근거·마스킹·열람 범위·알림 정책 모두 일관. G-1은 리팩터 권고 |
| 피보호자 | **PASS** | 본인 자원 한정·안전망 보장 확인. W-1은 M-3의 결정에 따름 |
| 관리자 | **⚠️ 잔여 이슈** | 게이트·감사 기준이 신규/기존 컨트롤러 사이에서 둘로 갈림(A-1·A-2). 위험보다 기준 통일 문제 |
| 횡단 | **PASS** | STOMP·403 문구·prefix·DTO 노출 범위 일관. 토큰·세션은 M-1·M-5 |

---

## 수정용 커밋 메시지 초안 (승인 후)

```
refactor: 관리자 기존 컨트롤러 3종에 클래스 @PreAuthorize 추가, 카메라 목록 인가를 ConnectionService로 (A-1·G-1)

- AdminAnnouncement·AdminAnnouncementDraft·AdminInquiry에 hasRole('ADMIN') 이중 게이트 (동작 변화 없음)
- CameraService.getConnectedWardCameras가 connectionService.getActiveWardIds를 쓴다

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```

```
feat: 문의 답변 감사 로그 (V50 - admin_audit_log CHECK에 INQUIRY_ANSWER 추가) (A-2)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```
