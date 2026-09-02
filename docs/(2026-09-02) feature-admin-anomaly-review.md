# 관리자 이상감지 로그 + 판정 정정 (PR ④)

> 2026-09-02 · 근거 문서: `docs/(2026-08-31) api-contract-anomaly-dashboard.md` §6
> 선행: PR ①(#236, V44 판정 스키마) · PR ②(#237, V45 보호자 응답·재촉) · PR ③(#239, 대시보드 집계)
> 마이그레이션: **V46** (이유는 §3 - 예상과 다릅니다)

---

## 1. 무엇을 만들었나

보호자 응답이 엇갈린 건(`CONFLICTED`)을 관리자가 확인해 확정하는 자리입니다.

| 엔드포인트 | 용도 |
|---|---|
| `GET /api/admin/anomaly` | 이상감지 기록 목록 (상태·피보호자 필터, 페이징, **보호자 응답 내역 포함**) |
| `PATCH /api/admin/anomaly/{incidentId}/review` | 판정 정정 |

이 PR로 **`resolvedBy`를 채우는 경로가 처음 생겼습니다.** 그전까지는 아무도 채울 수 없어 보호자 화면의 `resolvedByAdmin`이 항상 `false`였고, 엇갈린 판정이 영원히 `CONFLICTED`로 남았습니다.

---

## 2. 이 PR이 지키는 정책

### 관리자는 1차 판정을 하지 않는다

여기 있는 것은 **보호자 응답을 보고 뒤집는 2차 정정**뿐입니다. 관리자용 "오탐이다/아니다" 최초 판정 API를 추가하면 안 됩니다 - 현장을 아는 사람은 보호자이고, 관리자는 엇갈린 것을 정리하는 역할입니다.

### 정정은 상태만 바꾸고 응답 원본은 지우지 않는다

누가 무엇이라고 답했는지는 정정 후에도 그대로 남습니다. 지우면 **관리자가 무엇을 근거로 뒤집었는지 확인할 방법이 사라져** 정정 자체를 검증할 수 없습니다.

### 되돌리기는 막는다

지정할 수 있는 값은 `REAL`·`FALSE_ALARM` **둘뿐**입니다. `PENDING`·`CONFLICTED`로 되돌리면 400(`ANOMALY_INVALID_REVIEW_STATUS`)입니다. 확인을 마친 뒤에 "아직 아무도 답하지 않음"으로 되돌리면 그 상태가 무엇을 뜻하는지 알 수 없게 됩니다. 판단이 서지 않으면 그냥 두면 됩니다.

### 재정정은 허용한다

이미 정정한 건을 다시 정정할 수 있습니다. **관리자도 잘못 누를 수 있는데 막아 두면 되돌릴 방법이 없습니다.** 대신 정정할 때마다 감사 로그가 쌓여 "누가 언제 무엇에서 무엇으로 바꿨는지"가 추적됩니다.

(보호자 응답은 그대로 409로 막힙니다 - 그건 정책 변경이 아닙니다.)

### 정정 알림을 보내지 않는다

오탐으로 확정해도 "아까 그건 아니었습니다"를 다시 푸시하지 않습니다. 알림이 두 배가 되고 다음 진짜 경보의 신뢰만 깎입니다.

### 감사 로그를 남긴다

개인 이력을 뒤집는 조작이라 반드시 남깁니다. **집계 숫자만 보는 대시보드(PR ③)가 감사 로그를 남기지 않는 것과 대비되는 지점**이며, 그때 "개인 이력을 열람하는 관리자 API는 반드시 남긴다"고 예외를 한정해 둔 것이 여기 적용됩니다.

### 연결 여부로 좁히지 않는다

보호자 조회와 달리 관리자는 **전체**를 봅니다. 연결된 피보호자만 보면 엇갈린 판정을 찾아낼 수 없기 때문입니다. 그 대가로 감사 로그를 남깁니다.

---

## 3. 착수 전 확인한 것 (PHASE 0) - V46이 필요한 이유가 예상과 달랐다

계약 문서와 메모리는 "PR ④ = V46"이라고만 적혀 있었고, 정정용 컬럼을 만들기 위한 것으로 보였습니다. **실제로는 아니었습니다.**

| 항목 | 실제 | 판정 |
|---|---|---|
| `resolved_by`·`resolved_at`·`review_note` | **V44가 이미 만들어 뒀고** 엔티티 매핑까지 완료 | 이미 있음 |
| 관리자 확정이 보호자 응답으로 안 뒤집히는 것 | `applyReviewStatus()`가 `isAdminResolved()`면 무시 - **이미 구현돼 있음** | 테스트로 고정만 |
| 정정을 반영할 엔티티 메서드 | `resolvedBy`를 채울 경로 없음 | 신규 |
| 감사 로그 동작 값 | `AdminAuditAction`에 이상감지 관련 값 없음 | 신규 |
| **DB CHECK 제약** | `chk_admin_audit_action` 존재 | 🔴 **V46 필수** |

🔴 **V46이 필요한 진짜 이유는 감사 로그입니다.** `admin_audit_log.action`에 CHECK 제약이 걸려 있어, enum에 값을 더하는 것만으로는 insert가 실패하고 **같은 트랜잭션의 본 작업(정정)까지 롤백돼 500**이 납니다.

이 함정은 이미 한 번 터졌습니다 - V1의 CHECK가 V14에서 추가된 `ANNOUNCEMENT_DRAFT_*` 4종을 허용하지 않아 **공지 임시저장 기능 전체가 죽었고**(C-S3-1), V27에서 고쳤습니다. 그 재발을 막으려고 `AdminAuditActionCheckSyncTest`가 enum 전수와 CHECK를 대조합니다. **enum만 늘리고 마이그레이션을 빼먹으면 테스트가 먼저 실패합니다.**

> V46의 허용 목록은 V1이 아니라 **V27**(enum 전수와 맞춘 판)을 기준으로 삼았습니다. V1에 있던 `ANNOUNCEMENT_PUBLISH`는 enum에 없어 V27이 이미 뺐으므로 되살리지 않았습니다.

그 밖에 확인한 것:
- 감사 로그 기록은 `AdminAuditLogService.log(adminId, action, targetId, detail)` 재사용
- 페이징은 `PageResponse`, 동적 필터는 `InquiryRepository.searchForAdmin` 패턴 재사용
- 응답 일괄 조회 `findByIncidentIdIn`, 이름 조회 `findAllById` 이미 있음

---

## 4. 변경 파일

**신규**

| 파일 | 역할 |
|---|---|
| `anomaly/controller/AdminAnomalyController.java` | 엔드포인트 2개 |
| `anomaly/service/AdminAnomalyService.java` | 목록 조립 + 정정 |
| `anomaly/dto/AdminAnomalyIncidentItem.java` | 관리자용 항목 (응답 내역 포함) |
| `anomaly/dto/AdminAnomalyFeedbackItem.java` | 보호자 응답 한 건 |
| `anomaly/dto/AdminAnomalyReviewRequest.java` | 정정 요청 |
| `db/migration/V46__add_anomaly_review_audit_action.sql` | CHECK 재정의 |

**수정**

| 파일 | 변경 |
|---|---|
| `global/enums/AdminAuditAction.java` | `ANOMALY_REVIEW_RESOLVE` 추가 |
| `global/exception/ErrorCode.java` | `ANOMALY_INVALID_REVIEW_STATUS`(400) 추가 |
| `anomaly/entity/AnomalyIncident.java` | `resolveByAdmin(...)` 추가 |
| `anomaly/repository/AnomalyIncidentRepository.java` | `searchForAdmin(...)` 추가 |

**건드리지 않은 것**: 보호자 응답 API, 재촉 스케줄러, 대시보드 집계, 알림 발송 경로.

### 설계 판단 2가지

**`respondedAt`은 마지막으로 답을 바꾼 시각입니다.** 보호자는 응답을 번복할 수 있으므로 관리자에게 필요한 것은 "지금 이 사람의 의견이 언제 것인가"입니다. 처음 답한 시각을 주면 번복 뒤에도 옛 시각이 남아 판단을 그르칩니다.

**빈 페이지에서 일찍 반환하지 않습니다.** 마지막 페이지 이후를 요청하면 항목은 비지만 전체 건수는 남아야 하므로, 조회만 건너뛰고 페이징 정보는 그대로 살립니다.

---

## 5. 테스트

`./gradlew build` **전체 504건 통과 / 실패 0** (기존 488 + 신규 16)

| 테스트 | 고정하는 정책 |
|---|---|
| `AdminAnomalyServiceTest$Resolve` (7) | 정정 반영 / **응답 원본 보존** / **정정 후 뒤집힘 차단** / 재정정 허용 / 감사 로그 기록 / 되돌리기 거부 / 404 |
| `AdminAnomalyServiceTest$Search` (5) | 연결 여부로 안 좁힘 / 응답 없으면 빈 배열 / 응답 내역 포함 / 빈 페이지에서도 전체 건수 유지 / 페이지 크기 상한 50 |
| `AdminAnomalyControllerSecurityTest` (4) | ADMIN 허용, **GUARDIAN·WARD 403** |
| `AdminAuditActionCheckSyncTest` (기존) | enum ↔ CHECK 동기화 - V46을 빼먹으면 여기서 먼저 실패 |

되돌리기 거부 테스트는 **상태를 건드리지도, 감사 로그를 남기지도 않는 것**까지 확인합니다.

---

## 6. 검증 가이드

```bash
# 엇갈린 건만 보기
curl -H "Authorization: Bearer <ADMIN_TOKEN>" \
  "https://api.devdmu.gosky.kr/api/admin/anomaly?status=CONFLICTED"

# 정정
curl -X PATCH -H "Authorization: Bearer <ADMIN_TOKEN>" -H "Content-Type: application/json" \
  -d '{"reviewStatus":"FALSE_ALARM","note":"보호자 통화 확인 - 요리 연기"}' \
  "https://api.devdmu.gosky.kr/api/admin/anomaly/37/review"
```

**확인 포인트**
- 정정 후 `feedbacks`가 그대로 남아 있는지
- 같은 건에 보호자가 응답하면 409인지
- `{"reviewStatus":"PENDING"}`으로 정정하면 400인지
- `admin_audit_log`에 `ANOMALY_REVIEW_RESOLVE` 행이 쌓이는지
- 관리자 아닌 토큰이면 403

---

## 7. 남은 것

- **프론트 화면** - 관리자 콘솔의 이상감지 로그 목록과 정정 UI. 이 PR 범위 밖이며 FE 담당자가 진행합니다.
- 보호자 대시보드 통계(계약 §7)는 계약만 확정된 상태로 여전히 미착수입니다.
