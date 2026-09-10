# 점검 이슈 반영 - 2026-09-10 세로·횡단 점검

> 근거: `(2026-09-10) audit-unaudited-prs-230-245.md`(M-1~M-6, L-1~L-4) · `(2026-09-10) audit-role-boundary-guardian-ward-admin.md`(A-1·A-2·G-1)
> PR ① `fix/audit-findings-2026-09`(마이그레이션 없음) / PR ② `feature/inquiry-answer-audit`(V50)
> 결정(사용자): M-3 = 역할 변경 시 카메라 삭제·재등록 / M-4 = 문서 정정 / L-4 = 코드 수정(V50 아님)

---

## PHASE 0에서 드러난 전제 차이

| 프롬프트의 전제 | 실제 | 대응 |
|---|---|---|
| 카메라 삭제가 AI 구독을 해제한다 | 해제 경로가 없다. `AiLiveStreamSubscriber`에 unsubscribe 액션이 없고, 삭제된 세션은 AI 목록 갱신 때 정리되며 그 사이 신호는 "등록된 카메라 없음"으로 스킵된다 | M-3도 기존 삭제와 같은 방식(행 삭제)으로 맞춤. AI 프로토콜에 unsubscribe가 있는지 모르는 채로 만들지 않았다 |
| 정지 시 WS 세션을 서버가 끊을 수 있다 | 핸드셰이크가 Principal을 세우지 않아 `SimpUserRegistry`로 세션을 찾을 수 없다 | M-5는 정책 문서에 수용한 한계로 기록 |
| L-4는 V50에서 FK를 바꾼다 | `resolved_at`은 SET NULL 대상이 아니다. FK를 RESTRICT로 바꾸면 정정한 관리자의 탈퇴 purge가 FK 위반으로 실패해 좀비가 된다 | `isAdminResolved()`를 `resolvedAt != null`로 - 코드만으로 닫음 |

## PR ① 변경 파일

| 이슈 | 파일 | 변경 |
|---|---|---|
| M-1 | `user/event/UserRoleChangedEvent` (신규) · `AdminUserService.applyRole` · `UserAccountEventListener.handleRoleChanged` (신규) | 역할 변경 시 refresh 삭제 + access 무효화(정지와 같은 경로, best-effort) |
| M-2 | `AdminUserService.getUserOrThrow` | INACTIVE면 404 - 상세·수정·강제탈퇴 공통 |
| M-3 | `CameraRepository.deleteByWardId` · `CameraService.deleteAllByWard` (신규) · `AdminUserService.applyRole` · `AdminUserController` 설명 | 역할 변경 시 카메라 전부 삭제, 감사 로그 `"(연결 N건 해제, 카메라 M대 삭제)"`(0대면 기존 문구 유지) |
| M-4·M-5 | `.claude/rules/domain-security-policy.md` · `CLAUDE.md` · `docs/progress.md` | "열람"→"변경" 정정 / 열린 WS 세션 한계 / 역할 변경 규칙 3줄 |
| M-6 | `AdminUserControllerSecurityTest` · `AdminConnectionControllerSecurityTest` · `GuardianAnomalyControllerSecurityTest` (신규) | ADMIN·GUARDIAN·WARD × 허용·403 |
| A-1 | `AdminAnnouncementController` · `AdminAnnouncementDraftController` · `AdminInquiryController` | 클래스 `@PreAuthorize("hasRole('ADMIN')")` - 동작 변화 없음 |
| G-1 | `CameraService.getConnectedWardCameras` · `CameraServiceTest` | `connectionService.getActiveWardIds`로 교체, `ConnectionRepository` 의존 제거 |
| L-1 | `AnomalyIncident.resolveByAdmin` | note null이면 기존 메모 유지 |
| L-2 | `FcmService.registerToken` | 소유자 이전 경로에도 `enforceMaxPerUser` |
| L-3 | `InquiryService` Javadoc | 404 위장 → 403 |
| L-4 | `AnomalyIncident.isAdminResolved` | `resolvedAt != null` |

### 역할 변경의 순서 (`applyRole`)

1. `user.updateRole(role)`
2. `connectionService.tearDownConnectionsOnRoleChange` (ACTIVE 해제·알림, PENDING 취소)
3. `cameraService.deleteAllByWard` (같은 트랜잭션)
4. `UserRoleChangedEvent` 발행 (AFTER_COMMIT에서 토큰 무효화)
5. 감사 로그 (같은 트랜잭션 - 실패하면 전부 롤백)

## PR ② 변경 파일 (V50)

| 파일 | 변경 |
|---|---|
| `AdminAuditAction.INQUIRY_ANSWER` | enum 추가 |
| `V50__add_inquiry_answer_audit_action.sql` | `chk_admin_audit_action` 재정의(V48 목록 + `INQUIRY_ANSWER`) |
| `AdminInquiryService.answer` | 답변 후 `auditLogService.log(adminId, INQUIRY_ANSWER, inquiryId, ...)` |
| `AdminInquiryServiceTest` | 감사 로그 기록 / 409·404에서는 미기록 |

## 테스트

| 클래스 | 추가·수정 |
|---|---|
| `AdminUserServiceTest` | 역할 변경 토큰 이벤트 / 카메라 삭제·감사 문구 / 역할 유지 시 부수효과 없음 / INACTIVE 상세·수정·삭제 404 (6) |
| `UserAccountEventListenerTest` | `handleRoleChanged` 무효화 (1) |
| `CameraServiceTest` | 목을 `ConnectionService`로 교체 (2 수정) |
| `FcmServiceTest` | 소유자 이전 상한 검사 (1) |
| `AdminAnomalyServiceTest` | 재정정 메모 유지 / 관리자 행 삭제 후 확정 유지 (2) |
| `GuardianAnomalyServiceTest` | 확정 상태를 `resolveByAdmin` 실제 경로로 (1 수정) |
| 신규 보안 테스트 3 클래스 | 9 |
| `AdminInquiryServiceTest` (PR ②) | 감사 로그 (2) |

빌드 결과는 아래 "검증"에 기록.

## 검증

| 항목 | 결과 |
|---|---|
| PR ① `./gradlew clean build --no-daemon` | **통과**(exit 0) |
| 테스트 | 110개 클래스 / **574 tests / 0 failures / 1 skipped**(기존 555 + 신규 19) |
| 회귀 | `GuardianAnomalyServiceTest`의 "관리자 확정 건 409" 테스트가 `resolvedBy`만 리플렉션으로 세워 L-4 변경 직후 실패 → 실제 경로 `resolveByAdmin`으로 바꿔 통과 |
| PR ② | 아래 별도 기록 |
