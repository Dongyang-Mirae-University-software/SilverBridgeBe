# 점검 잔여 이슈 처리 - 영향 범위 점검 (템플릿 C + B)

> 점검일 2026-09-30 · 기준 커밋 `8800166`(dev) · **점검만 수행, 코드 수정 없음**
> 대상: #259 `fix/remaining-audit-items`(H-1·E-2·SOS 경계값), #260 `refactor/package-boundaries`(F-1~F-3)
> 배포 상태: vkcs-linux(CD) `8800166` / gosky(수동, 2026-09-30 11:16) `8800166` - 둘 다 스키마 V54, healthy, AI WS 연결

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| dev 최신 | `8800166` (#260) ← `e2a579b` (#259) |
| `./gradlew test` | 683 / 0 실패(스킵 1) - 머지 전 브랜치 기준, dev와 내용 동일 |
| vkcs 통합 테스트 | 26 / 0 실패. **CD 두 번은 모두 `FROM-CACHE`** - 머지 전에 같은 입력으로 vkcs에서 실제 실행한 결과(수정본 B·refactor C)를 재사용했다(L-1) |
| vkcs 기동 | `Current version of schema "public": 54`, `Started` 17초 |
| gosky 기동 | 수동 배포(`reset --hard origin/dev` → build → up). 추적 파일 수정 없음 확인 후 진행, untracked CSV·SQL 보존. healthy, AI WS 연결됨, 공개 `/actuator/health` 200 |

## PHASE 0. 변경점과 불변식

| # | 변경 | 불변식 |
|---|---|---|
| ① | 탈퇴 정리 두 메서드 REQUIRED → REQUIRES_NEW | 동기 AFTER_COMMIT 리스너가 부르는 쓰기·이벤트 발행은 새 트랜잭션에서 일어난다 |
| ② | 이상감지 응답이 상황 행 쓰기 잠금 | 같은 상황의 판정 재계산은 앞선 응답의 커밋을 본 뒤 일어난다 |
| ③ | `AnomalyIncident` `@DynamicUpdate` | 감지 승계는 `review_status`를 쓰지 않고, 응답은 감지 컬럼을 쓰지 않는다 |
| ④ | 패키지 이동 3건 | 동작 불변, `global → domain` import 0 |

## PHASE A·B. 사용처 전수와 판정

### ① 동기 AFTER_COMMIT 리스너 전수 (H-1과 같은 결함이 또 있는가)

리포 전체 `@TransactionalEventListener` 17개 중 `@Async`가 없는 것(실제 어노테이션 기준 - javadoc의 `@Async` 문구 제외):

| 리스너 | 부르는 쓰기 | 전파 | 판정 |
|---|---|---|---|
| `UserAccountEventListener` 4종(탈퇴·정지·역할 변경·비밀번호 변경) | refresh 토큰 삭제·접속로그 | 메서드 자체가 REQUIRES_NEW | PASS |
| `UserWithdrawalConnectionListener` | `tearDownConnectionsOnWithdrawal` | REQUIRES_NEW (#259) | PASS |
| `MedicationWithdrawalListener` | `removeMedicationsRegisteredBy` | REQUIRES_NEW (#259) | PASS |
| **`UserWithdrawalFcmListener`** | **`FcmService.deleteAllTokens` → `deleteByUserId`(파생 삭제)** | **REQUIRED** | **FAIL → M-1** |

비동기(`@Async`) 리스너 10개는 트랜잭션 자원이 없는 별도 스레드에서 돌아 해당 없음.

- `tearDownConnectionsOnRoleChange`는 REQUIRED 유지가 맞다 - `AdminUserService.updateUser`(`@Transactional`) 안에서 불려 그 트랜잭션과 함께 커밋돼야 한다. PASS.
- 커넥션: 탈퇴 afterCommit 구간에서 바깥(커밋 끝난) 커넥션 + REQUIRES_NEW 1개 = 동시 최대 2개, 리스너가 순차라 늘지 않는다. Hikari 기본 10. PASS.

### ①-2 탈퇴 시 새로 나가기 시작한 해제 알림

#259 이전에는 해제 이벤트가 AFTER_COMMIT을 맞지 못해 **한 번도 발송되지 않았다.** 이제 나간다.

| 확인 | 판정 |
|---|---|
| 수신자 = 남은 상대(ACTIVE) - 디스패처 상태 검사 통과 | PASS |
| WS `connection-cancelled` - FE가 보호자·피보호자 모두 구독 중(목록 갱신) | PASS |
| 이력 `ward_id` - 피보호자 탈퇴 시 purge가 먼저 끝나면 FK로 그 이력만 누락(발송 무영향, 2026-09-22 수용) | 수용 |
| PENDING 연결은 `cancel()` 무알림 - 연결 알림 비대칭 정책 유지 | PASS |
| 보호자 탈퇴 시 남은 보호자가 받는 `MEDICATION_STOPPED`와 피보호자가 받는 해제 알림은 수신자가 달라 중복·모순 없음 | PASS |
| **문구** - 보호자 탈퇴면 피보호자에게 "보호자가 연결을 해제했습니다.", 피보호자 탈퇴면 보호자에게 "피보호자가 연결을 해제했습니다." | **M-2 (결정 필요)** |

### ② 판정 잠금

| 경로 | 상황 행 접근 | 판정 |
|---|---|---|
| 보호자 응답 `submitFeedback` | `findByIdForUpdate`(쓰기 잠금) → 응답 upsert → 판정 갱신 → 동수 기록 `ON CONFLICT` | PASS - 다른 행을 먼저 잠그지 않는다 |
| AI 감지 승계 `AnomalyIncidentService.resolveIncident` | 잠금 없이 읽고 UPDATE, 이력 INSERT의 FK 검사 | PASS - 응답 트랜잭션이 끝날 때까지 **기다릴 뿐** 역순 잠금이 없어 교착 없음 |
| 재촉·동수 안내 스케줄러, 관리자 조회 | 읽기만 | 해당 없음 |

- 대기 상한(lock timeout)은 두지 않았다. 응답 트랜잭션은 조회 몇 건이라 ms 단위이고, AI 수신 스레드가 그만큼 기다릴 수 있다(L-2).

### ③ `@DynamicUpdate`

- `AnomalyIncident`를 고치는 경로는 `addDetection`(감지)·`applyReviewStatus`(응답) 둘뿐이다. 신규 생성은 INSERT라 무관. PASS.
- 두 경로 모두 `updated_at`(BaseTimeEntity)을 쓴다 - 나중 커밋이 이긴다. 표시·정렬에 쓰지 않아 무해. PASS.
- 감지끼리의 `event_count++` 경합은 AI WS 수신이 단일 스레드라 해당 없음.

### ④ 패키지 이동

| 확인 | 판정 |
|---|---|
| 옮긴 3개 클래스의 문자열·리플렉션 참조(yaml·로그 설정·Swagger) | 없음, PASS |
| `AdminAuditLog` 엔티티 스캔 - 루트 패키지 하위라 자동 스캔. 두 서버 기동·통합 테스트 스키마 validate 통과 | PASS |
| 로그 테스트(`AdminAuditLogServiceTest`)는 클래스 기준 로거 | PASS |
| 의존 방향 재계산: 도메인 간 순환 0, `global → domain` 0 | PASS |

## PHASE C. 구조·계약

- 트랜잭션 경계: 탈퇴 = `withdraw`(커밋) → 동기 리스너 4개(각자 새 트랜잭션, FCM만 예외 M-1) → `purgeWithdrawnUser`(새 트랜잭션). 이벤트 발행 위치는 연결 정리 트랜잭션 안 → 그 커밋 후 비동기 알림.
- 잠금 모드: `@Lock(PESSIMISTIC_WRITE)` + JPQL 단건. PostgreSQL에서 행 잠금이며 테이블 잠금 아님.
- API 계약 변경 없음(응답·상태코드·경로 동일).

## PHASE D. 테스트

- 새 통합 테스트 3종의 대조 기록: 수정 전 코드로 **4건 실패**(연결 `ACTIVE` 잔존·약 잔존·동시 응답 `REAL`·감지 후 `PENDING`) → 수정 후 통과. `(2026-09-30) fix-remaining-audit-items.md`에 기록. 유효.
- "동기 AFTER_COMMIT 안의 REQUIRED 쓰기"를 기계적으로 막는 가드는 정적 분석(호출 그래프)이 필요해 단위 테스트로는 어렵다. 대신 **실 DB 통합 테스트 패턴**(`WithdrawalListenerCommitIntegrationTest`)을 새 리스너마다 붙이는 것을 정책 파일 규칙으로 두었다(L-3). M-1을 고칠 때 같은 클래스에 FCM 토큰 케이스를 추가하면 된다.

## 이슈

### 🟡 M-1. 탈퇴 FCM 토큰 삭제도 커밋되지 않는다 (H-1과 같은 결함)

- `UserWithdrawalFcmListener`(동기 AFTER_COMMIT) → `FcmService.deleteAllTokens`(`@Transactional` REQUIRED) → 이미 커밋된 탈퇴 트랜잭션에 합류해 삭제가 커밋되지 않는다. 로그 `FCM 토큰 일괄 삭제(탈퇴)`는 찍혀 **실제와 다른 기록**이 남는다.
- **현재 영향은 없다**: 정상 경로는 바로 뒤 purge의 `fcm_tokens` FK CASCADE가 지운다. purge가 실패해 좀비가 남아도(최대 ~20분) 디스패처가 INACTIVE 수신자를 차단해 푸시가 나가지 않는다.
- 고치지 않으면 이 리스너에 쓰기를 더하는 순간 H-1이 재발한다. **제안**: `deleteAllTokens`를 `REQUIRES_NEW`로(사용처는 이 리스너 하나), `WithdrawalListenerCommitIntegrationTest`에 토큰 케이스 추가.

### 🟡 M-2. 탈퇴를 "상대가 연결을 해제했습니다"로 알린다 (결정 필요)

- #259로 처음 발송되기 시작한 문구다. 탈퇴 경로는 `DisconnectedBy.GUARDIAN`/`WARD`를 재사용해 "보호자가 연결을 해제했습니다."가 나간다.
- 사실과 완전히 어긋나지는 않지만(탈퇴가 연결을 끝냈다), 받는 사람은 "나를 끊었다"로 읽는다. 관리자 강제 해제 때 `DisconnectedBy.ADMIN`을 따로 둔 판단(정책 파일 "문구를 재사용하지 말 것")과 같은 종류의 문제다.
- **선택지**: (a) 그대로 둔다 / (b) `DisconnectedBy.WITHDRAWN` 추가 → "보호자가 탈퇴해 연결이 종료되었습니다." (enum 값 추가지만 DB CHECK 대상 아님 - 이벤트 전용). 권장은 (b).

### 🟢 L-1. CD의 배포 전 통합 테스트가 캐시 결과를 재사용했다

- #259·#260 CD 모두 `integrationTest FROM-CACHE`. 머지 전 vkcs에서 같은 입력으로 **실제 실행한** 결과라 유효하다(2026-09-22 캐시 허용 유지 결정대로). 기록만 남긴다.

### 🟢 L-2. 판정 잠금 대기 상한 없음

- 응답 트랜잭션이 짧아 AI 수신 스레드 대기는 ms 단위다. 응답 트랜잭션에 외부 호출이 들어가면 대기가 길어지므로 `submitFeedback`에 외부 I/O를 넣지 말 것. 코드 변경 불요.

### 🟢 L-3. 동기 AFTER_COMMIT 쓰기 가드는 통합 테스트 패턴으로

- 기계적 가드(정적 분석) 대신 정책 규칙 + 실 DB 테스트 패턴. M-1 수정 때 적용.

## 종합 판정

**PASS (🟡 2건, 🟢 3건)** - 🔴·🟠 없음. #259의 세 가지 수정은 불변식을 지키고 다른 경로를 깨지 않았으며, #260은 동작 불변이 확인됐다. M-1은 H-1과 같은 결함의 마지막 한 곳(현재 무해), M-2는 이번에 처음 사용자에게 보이게 된 문구의 결정 사항이다.
