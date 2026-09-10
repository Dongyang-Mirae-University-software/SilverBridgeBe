# 기점검 도메인 회귀 재점검 - auth · user · connection · notification · camera · inquiry · sos · medication · announcement · anomaly(1·2단계)

> 기준: `docs/audit-index.md`의 ✅ 행 전부 + `(2026-06-11) audit-full-api-final-report.md` §3·§5·§6·§7
> 점검일: 2026-09-10 · HEAD `418d1c3`(#246·#247 머지 직후) · 빌드·테스트: 575 tests / 0 failures / 1 skipped · 코드 미수정
> 목적: ① 이전 지적사항의 수정 유지 ② 이후 횡단 변경이 옛 도메인에 만든 회귀 ③ 이전 점검이 보지 않은 축(계정 생명주기 × 데이터 잔존)

---

## PHASE 0. 회귀 대상

### 이전 리포트(2026-06-11) §3 미해결 항목의 현재 상태

| ID | 당시 내용 | 현재 코드 | 판정 |
|---|---|---|---|
| C-S3-1 | `chk_admin_audit_action`에 DRAFT 액션 누락 → 공지 임시저장 500 | V27로 재정의, `AdminAuditActionCheckSyncTest`가 enum ↔ 마지막 CHECK 대조. 이후 V46·V48·V50에서 같은 함정을 테스트가 막음 | **유지(수정됨)** |
| M-S2-1 | SOS SMS 폴백이 토큰 "존재" 기반 - 무효 토큰이면 유실 | `FcmService.sendMulticast`가 `successCount > 0`으로 전달 여부를 돌려주고 `NotificationDispatcher.dispatchMandatory`가 그 결과로 SMS 폴백 | **유지(수정됨)** |
| M-S2-2 | FCM 토큰 소유자 재할당 없음(공유 디바이스) | `registerToken`이 `reassignTo` + (2026-09-10) 상한 재검사 | **유지(수정됨)** |
| M-S3-1 | WS 핸드셰이크에 typ·로그아웃 블랙리스트·무효화 키 미검증 | `JwtHandshakeInterceptor`가 3종 모두 검사(`isAccessToken`·`LOGOUT_TOKEN`·`PASSWORD_INVALIDATE`), `JwtHandshakeInterceptorTest` 존재 | **유지(수정됨)** |
| M-S1-1 / H-S2-1 | §6 수정 완료분(좀비 계정 스윕 / FCM 토큰 정리 트랜잭션) | `WithdrawnUserPurgeScheduler`·`deleteByToken` @Transactional 그대로 | **유지** |

§3의 미해결 4건은 전부 닫혀 있고 회귀 없음. 이전 리포트에 "미해결"로 남은 문서 표기는 `audit-index.md`가 이미 갱신했다.

### 2026-08-05 점검 이후 옛 도메인에 들어온 횡단 변경

| 변경 | 옛 도메인 영향 | 확인 |
|---|---|---|
| 계정 상태 3분법(`!= ACTIVE`) | auth 로그인·재발급, kakao 로그인, connection 요청 대상 검사 | 등호 비교(`== Status.INACTIVE`) 잔존 **0건**. 남은 `==`는 관리자 서비스의 값 분기와 `canReceive`뿐(의도) |
| 디스패처 수신자 차단·허용 채널 | 모든 알림 리스너 | 호출부 12곳이 `dispatch()` 단일 진입점, 채널 인터페이스 변경 없음 |
| FCM 토큰 상한·유휴 정리 | `NotificationController` 등록 경로 | `rateLimitService.check("fcm-register")` 유지, 상한 초과 삭제는 오래된 순 |
| 역할 변경·강제 연결·강제 탈퇴 | connection(`tearDownConnectionsOnRoleChange`·`forceConnect`·`forceDisconnect`), camera(`deleteAllByWard`), user(`forceWithdraw`) | 상태 전이는 도메인 서비스가 소유, 관리자 서비스는 검증·감사만 |
| Swagger 태그 재편 | 전 컨트롤러 | `SwaggerConfig` 태그 ↔ 컨트롤러 `@Tag` **완전 일치**(누락 0, 고아 0) |
| WS Origin 목록 | `WebSocketConfig`·`SecurityConfig` | 같은 `app.cors.allowed-origins`를 공유, 6510 포함 |

---

## PHASE A. 인가 회귀

- **엔드포인트 인가 표 diff**: 2026-06-11 표 대비 바뀐 것은 ① 관리자 기존 3종에 클래스 `@PreAuthorize` 추가(이중 게이트) ② `PATCH /api/guardian/sos/{id}/ack` 제거 ③ `/api/guardian/medication-alert-setting` → `/api/guardian/ward/{wardId}/medication-alert-setting` 이동뿐. 나머지 인가 근거·역할 게이트는 동일 - **PASS**.
- **rate limit**: 인증 6경로(`email-check`·`signup`·`signin`·`token-refresh`·`find-email`·`kakao-*`)·SMS 2경로·비밀번호 재설정 4경로(이중 윈도우)·연결 요청·FCM 등록 전부 유지 - **PASS**.
- **nonce·코드 소비 순서**: `PasswordResetService`는 `verifyWithoutConsume` 3곳 + 마지막 `consume` 1곳, 가입·카카오 가입·전화번호 변경은 비즈니스 검증 후 `consumeVerification` - **PASS**(2026-05-31 규칙 유지).
- **탈퇴 본인확인**: 로컬=비밀번호, 카카오=`"탈퇴"` 문자열 일치 - **PASS**. 관리자 강제 탈퇴만 본인확인 없이 같은 파이프라인.
- **RESTRICTED의 옛 경로 일관성**: 로그인 불가·재발급 불가·연결 요청 대상 불가(404 위장, D-USER-3 그대로)·알림 수신 불가 - PASS. 단 **R-1** 참조.

---

## PHASE B. 계정 생명주기 × 데이터 잔존 (신규 축)

FK 정의(V1~V50 누적)와 서비스 코드에서 정리한 결과다. "탈퇴"는 `purgeWithdrawnUser`의 hard delete 시점 기준.

| 테이블 | 탈퇴(purge) | 정지(RESTRICTED) | 역할 변경 | 비고 |
|---|---|---|---|---|
| `connection` | CASCADE(양쪽), `initiated_by` SET NULL | 유지 | ACTIVE→DISCONNECTED+알림 / PENDING→CANCELLED | 리스너가 purge 전에 상대 알림 |
| `refresh_token` · `fcm_token` | CASCADE(+리스너가 선삭제) | 리스너가 refresh 삭제 + Redis 무효화 | 동일(2026-09-10) | fcm_token은 정지 시 유지 → 해제 후 바로 수신 |
| `access_log` | SET NULL(익명 보존) | 유지 | 유지 | 감사 목적 |
| `admin_audit_log` | FK 없음(admin_id 문자열) | - | - | 관리자 삭제 경로 없음 |
| `user_notification_setting` · `sos_setting` · `medication_setting` | CASCADE | 유지 | **유지** | 역할 변경 후 WARD 전용 설정이 GUARDIAN 계정에 남음(무해, 조회 경로 없음) |
| `camera` | CASCADE(`ward_id`), `registered_by` SET NULL | 유지(AI 구독 계속) | **삭제(2026-09-10)** | 정지된 피보호자는 존재하지 않음(정지 금지) |
| `sos_event` | **SET NULL(익명 보존)** | 유지 | 유지 | 보호자 이력에 이름 없이 남음 |
| `anomaly_event` · `anomaly_incident` | **CASCADE(삭제)** | 유지 | 유지(관리자 로그에 계속 보임) | R-3 참조 |
| `anomaly_incident_feedback` · `anomaly_review_*_log` · `guardian_anomaly_setting` | CASCADE(guardian) | 유지 | 유지 | 보호자→피보호자 전환 시 응답 원본 잔존(관리자 근거로 유효) |
| `medication` | CASCADE(`ward_id`·`created_by`) | 유지 | 유지 | 등록 보호자 탈퇴 시 리스너가 남은 보호자에게 `MEDICATION_STOPPED` |
| `medication_intake` · `medication_reminder_log` | medication CASCADE 연쇄 | 유지 | 유지 | - |
| `guardian_medication_setting` · `medication_missed_alert_log` | CASCADE(양쪽) | 유지 | 유지 | - |
| `inquiry` | **CASCADE(삭제)**, `answered_by` SET NULL | 유지 | 유지 | R-4 참조 |
| `announcement` · `announcement_draft` | `author_id` SET NULL | - | - | 관리자 계정 |

- **조용히 사라지는 것**: 탈퇴 시 문의·이상감지 이력. 남는 것 중 오작동하는 것은 없음(역할 변경 카메라는 오늘 닫힘).
- **H-1(탈퇴 리스너 REQUIRED 전파)**: `MedicationWithdrawalListener`·`tearDownConnectionsOnWithdrawal` 경로 변경 없음. 목 기반이라 여전히 미실측 - **잔여 유지**.

---

## PHASE C. 구조·계약 회귀

- **응답 봉투**: 도메인별로 일관(auth·user·admin·announcement·notification-setting·sos-setting = `ApiResponse` 직접 / connection·camera·inquiry·medication·sos·anomaly-guardian·notification = `ResponseEntity<ApiResponse>`). 신규 코드도 자기 도메인 관례를 따랐다. 와이어 포맷 동일 - **PASS**(§5 결정 유지).
- **페이징 없는 목록 11개**: 연결 3·카메라 2·문의 1·복약 1·공지 3. 사용자 단위로 상한이 자연히 걸리는 것들이고, 공지는 관리자가 만드는 소량. **PASS**(주시).
- **N+1**: 옛 도메인 목록(연결·공지·문의·카메라)이 모두 `findAllById` 배치 조회. `Inquiry` 엔티티는 관계 매핑 없이 ID 참조(프로젝트 관례) - **PASS**.
- **Swagger**: 태그 일치. `InquiryService` 노후 주석은 오늘 정정.

---

## PHASE D. 테스트 회귀

- 이전 리포트 §7 "테스트 0건 도메인(announcement/admin)": 지금은 `AdminAnnouncementDraftServiceTest`·관리자 서비스 4종·보안 테스트 3종이 있다. **`AdminAnnouncementService`(공지 CRUD + 감사 로그)와 공개 `AnnouncementService`는 여전히 테스트 0건** → R-5.
- 이전 점검이 "테스트로 고정"했다고 적은 항목의 클래스 실재: `WithdrawnUserPurgeSchedulerTest`·`UserWithdrawalConnectionListenerTest`·`UserWithdrawalFcmListenerTest`·`FcmServiceTest`·`JwtHandshakeInterceptorTest`·`RateLimitServiceTest`·`VerificationCodeValidatorTest`·`SecurityConfigValidatorTest` 모두 존재 - **PASS**.
- 역할 게이트 MockMvc 미커버 5개(연결 2·문의 2·공지 1)는 세로 점검 결론 그대로(다음 변경 때).

---

## 이슈

### 🔴 / 🟠 - 없음

### 🟡 Medium

**R-1. 피보호자 수락 경로가 보호자의 계정 상태를 보지 않는다** - `ConnectionService.acceptConnectionAsWard`
- 요청 시점엔 보호자가 ACTIVE였지만 수락 전에 관리자가 정지시킨 경우, 피보호자가 수락하면 **정지된 보호자와 ACTIVE 연결**이 생긴다. 알림은 디스패처가 막지만 연결 자체는 살아 있어 정지 해제 즉시 SOS·카메라·복약·이상감지 이력이 열린다. 관리자 강제 연결은 같은 경우를 400(`CONNECTION_TARGET_NOT_ACTIVE`)으로 막는데 일반 경로만 비어 있다.
- 제안: `acceptConnectionAsWard`에서 보호자 `status != ACTIVE`면 `CONNECTION_TARGET_NOT_ACTIVE`(400) 또는 요청을 CANCELLED로 정리. 정지 시 그 보호자의 PENDING 요청을 함께 취소하는 안(`applyStatus`에서 `tearDown` 호출)도 가능 - 정책 결정 필요.

### 🟢 Low

**R-2.** 정지 상태에서 재촉·미복용 요약 로그가 "선점"만 되고 발송은 차단된다. 해제 후 그 건은 다시 오지 않는다(수용한 한계로 기록 권고).

**R-3.** 탈퇴 시 `anomaly_event`·`anomaly_incident`는 CASCADE 삭제, `sos_event`는 SET NULL 익명 보존이다. 둘 다 "안전 이력"인데 보존 정책이 다르다. V30에서 의도적으로 CASCADE로 바꾼 기록이 있으므로 결함이 아니라 **문서화 대상**(정책 파일에 "이상감지 이력은 탈퇴 시 삭제, SOS는 익명 보존" 한 줄).

**R-4.** 탈퇴 시 `inquiry` CASCADE 삭제 → 관리자 대시보드 `unansweredInquiries`·문의 목록에서 사라진다. 답변 대기 중이던 문의가 조용히 없어지므로 관리자 입장에서 "왜 줄었지"가 생길 수 있다. 익명 보존(SET NULL)으로 바꿀지는 정책 결정.

**R-5.** `AdminAnnouncementService`·`AnnouncementService` 테스트 0건. 2026-06-11 Critical(C-S3-1)이 정확히 이 도메인에서 나왔다. 감사 로그 기록·발행 여부 필터 정도의 단위 테스트 권고.

**R-6.** `TokenCleanupScheduler`의 cron에 `zone`이 없다(`FcmTokenCleanupScheduler`는 있음). Docker `TZ=Asia/Seoul`이라 배포에선 문제없고 로컬만 다르다. 통일 권고.

---

## 종합 판정

**✅ 회귀 없음(잔여 이슈 Medium 1·Low 5)**. 2026-06-11 미해결 4건은 전부 닫혀 있고, 이후 횡단 변경(상태 3분법·디스패처·FCM 정리·역할 변경·태그 재편)이 옛 도메인을 깨뜨린 곳은 없다. 신규 축(생명주기 × 데이터)에서 R-1이 유일한 실질 이슈이고, 나머지는 정책 문서화·테스트 보강이다.

### 수정용 커밋 메시지 초안

```
fix: 피보호자 수락 시 보호자 계정 상태 검사 (회귀 재점검 R-1)

- acceptConnectionAsWard에서 보호자가 ACTIVE가 아니면 CONNECTION_TARGET_NOT_ACTIVE
- 관리자 강제 연결과 같은 기준

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KbG2cCZBwBUHSmuCJvVEXu
```
