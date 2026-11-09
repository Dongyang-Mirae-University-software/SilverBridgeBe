# 안부 말벗 PR-B 점검 - 기능(B) + 영향 범위(C, 알림 종류 추가)

- 점검일: 2026-11-09 / 기준: dev `49c141c`
- 대상: PR-B `1cd3075`(머지 `33f6b47`, V66). 그 뒤 반영분 `9002b55`(기분 영문 코드)·`2644fde`(#454 확인 턴 위험)·`cd03d84`(#455 판단 불가만 있는 날 끊김 테스트) 포함
- 방법: 코드 직접 확인 + 결함 주입 2종(임시 worktree, 원복). 코드 수정 없음
- 종합: **조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 2 → **전부 조치**(PR `fix/companion-notice-connected-since`, 아래 8절)

## 1. 엔드포인트 × 역할

| 엔드포인트 | 허용 | 인가 위치 | 테스트 |
|---|---|---|---|
| `GET /api/guardian/ward/{wardId}/companion/today` | GUARDIAN | 클래스 `@PreAuthorize("hasRole('GUARDIAN')")` (URL 규칙은 `authenticated`) | 서비스 단위·통합(남의 피보호자 403). **웹 계층 역할 테스트 없음 → L-2** |
| `GET /api/admin/ai-stats/companion` | ADMIN | `/api/admin/**` URL 규칙 + 클래스 `@PreAuthorize` | `AdminAiStatsControllerSecurityTest`·`HttpTest` |
| `GET·PUT /api/guardian/notification-type-setting` (`companionNotice`·`companionRisk` 추가) | GUARDIAN | 기존 | `GuardianNotificationTypeSettingControllerSecurityTest` |

## 2. 보안·인가 (PHASE A)

| 항목 | 결과 | 근거 |
|---|---|---|
| IDOR (남의 피보호자) | PASS | `CompanionGuardianService.java:107-116` 403 `COMPANION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, 형식 오류 ID는 DB 조회 없이 길이만 로그 |
| D-1 보호자 화면 | PASS | 오늘 대화·주의 칸 모두 `startedAt >= connectedAt`(`:59`·`:84`), 기준 시각은 SOS·이상감지와 같은 `ConnectionService`(null = 옛 연결 전체 허용, 같은 처리) |
| D-1 주의 알림 | **FAIL → M-1** | `CompanionNoticeListener`가 연결 시점과 무관하게 연속 일수를 센다 |
| 원문 비저장 | PASS | 응답 DTO·`companion_notice_log`(ID·날짜·기분 코드·일수)·알림 문구·관리자 집계에 원문·요약 없음, `current.summary`는 항상 null |
| 알림 문구 | PASS | "걱정스럽거나 가라앉은 기분이 N일째 … 안부 전화를 드려 보세요" - 병명·원문 없음, data는 `type`·`wardId`·`days` |
| 캐시 | PASS | 보호자 응답 `Cache-Control: no-store` |

## 3. 기능 정합성 (PHASE B)

| 항목 | 결과 |
|---|---|
| 하루 기분·연속 일수 한 곳 계산 | PASS - 보호자 주의 칸·주의 알림·관리자 "이어지는 어르신"이 모두 `CompanionMoodDays` |
| 지금 기분·관리자 기분 분포 | 의도된 차이 - "판단 불가 포함 마지막 마친 대화" 기준(기능 문서 29행, 2026-11-09 결정). 이슈 아님 |
| 모두 판단 불가인 날 | 연속 끊김(2026-11-09 확정, `CompanionMoodDaysTest`) |
| 주의 알림 하루 1번 | PASS - `(ward_id, notice_date)` UNIQUE 선점 후 발송, 받을 보호자 없으면 선점 안 함 |
| 끄기 게이트 | PASS - 리스너에서만(`GuardianNotificationPreferenceUsageGuardTest`), 결함 주입 M2 검출 |
| 관리자 응답률 | PASS - 분모 = 보낸 안내, 0이면 null / 알림 보낸 수 = 주의 선점 수 + `risk_notified_at` 수(#454 뒤에도 대화당 1번이라 의미 같음) |
| 질문 안내 `*` | PASS - `Role.WARD` + `Status.ACTIVE`만. 기본값 빈 값, gosky `.env.dev`에도 대상 줄 없음(값 미출력, 줄 수만 확인) |

## 4. 구조·계약 + 영향 범위 (PHASE C)

| 사용처 | 판정 | 근거 |
|---|---|---|
| `NotificationType.COMPANION_NOTICE` | PASS | `SETTINGS_ONLY` + FCM만 (`NotificationType.java:151`), 이메일 허용 없음 |
| `COMPANION_RISK` | PASS | `FORCED_PUSH_PLUS_SETTINGS`, 웹푸시 high urgency, 사용자 알림 묶음 URGENT |
| `UserNotificationCategory` | PASS | 세 종류 모두 묶음 있음(default 없는 switch) |
| `NotificationTypeLabel` | PASS | "안부 말벗" |
| executor | PASS | 주의 리스너 `notificationExecutor` (`NotificationExecutorAssignmentTest:58`), 위험 리스너 긴급 풀 |
| DB CHECK | 해당 없음 | 알림 종류 CHECK 없음. `chk_companion_notice_mood`는 `CompanionCheckSyncTest` |
| 정지·탈퇴 진행 수신자 | PASS | 디스패처 한 곳에서 차단(기존) |
| 탈퇴 | PASS | `companion_notice_log.ward_id` FK `ON DELETE CASCADE` |
| 역할 변경(피보호자 → 보호자) | PASS | `CompanionWithdrawalListener.java:77` BEFORE_COMMIT 삭제 |
| 설정 저장 | PASS | `insertCompanionNoticeIfAbsent` ON CONFLICT DO NOTHING 뒤 수정 - 다른 칸 덮지 않음 |
| N+1 | 수용 | 보호자 화면 질문 조회 최대 5건, 관리자 집계는 30일 완료 대화 전체를 읽음(학생 규모) |

## 5. 이슈

### 🟡 M-1 주의 알림이 D-1(지금 연결 이후 기록만)을 지키지 않는다

- 위치: `CompanionNoticeListener.handleMoodRecorded` - `findFinishedSince(wardId, …)` 전체로 연속 일수를 계산하고, 그 값을 ACTIVE 보호자 전원에게 보낸다.
- 재현: 피보호자가 11/07·11/08 걱정으로 끝남 → 보호자 B가 11/09 10:00 새로 연결(또는 해제 뒤 재연결·관리자 강제 연결) → 11/09 12:00 대화가 걱정으로 끝남 → B에게 "걱정스럽거나 가라앉은 기분이 3일째" 푸시. 같은 시각 B의 화면 `attention`은 연결 이후 1일만 세어 **null**이다.
- 영향: 연결 전 기분 이력(일수)이 알림 문구로 드러난다(2026-10-31 사용자 결정 D-1과 어긋남). 푸시와 화면이 서로 다른 말을 한다.
- 수정 방향: 보호자마다 `findActiveConnectedAt`으로 그 보호자 연결 이후 대화만 남겨 연속 일수를 계산하고, 3일 이상인 보호자에게만 그 일수로 보낸다. 선점은 지금처럼 피보호자·날짜 1번(받을 보호자가 1명 이상일 때만). `companion_notice_log.days`는 보낸 일수 중 최댓값.
- 고정 테스트: 리스너 단위(연결 이후 2일인 보호자 제외·다른 보호자는 발송) + `CompanionFullIntegrationTest`(재연결 보호자에게 미발송).

### 🟢 L-1 보호자 화면 주의 칸의 D-1 필터가 테스트로 고정되지 않았다

- 결함 주입 M1: `CompanionGuardianService.java:84`의 연결 이후 필터를 지워도 `CompanionGuardianServiceTest` 통과(미검출). 지금 기분(`current`)의 필터만 검증한다.
- 수정: 연결 전 걱정 2일 + 연결 후 1일이면 `attention`이 null인 케이스 추가.

### 🟢 L-2 보호자 말벗 API에 웹 계층 역할 테스트가 없다

- 클래스 `@PreAuthorize`만 있고 WARD·ADMIN·익명 요청의 403/401을 보는 `*ControllerSecurityTest`가 없다(관리자 집계에는 있음). 애노테이션이 빠져도 서비스의 연결 검사가 막지만, 다른 도메인과 같은 형태로 고정해 둔다.

## 6. 참고 (이슈 아님)

- 주의 알림은 3일째의 **첫** 걱정·우울 대화에서 나간다. 그날 마지막 대화가 평온으로 끝나면 화면 주의 칸은 사라지지만 알림은 이미 갔다 - 실시간 알림의 성격이라 수용 권장.
- `COMPANION_PROMPT`(질문 안내)는 웹푸시 high urgency 목록에 없다. 절전 중 지연(복약 사례 16분)이 60분 창 안에서 생길 수 있다 - PR-A 범위, 질문 안내를 켤 때 다시 본다(지금 gosky는 안내 대상 없음).

## 7. 결함 주입

| 번호 | 바꾼 것 | 결과 |
|---|---|---|
| M1 | 보호자 화면 주의 칸 연결 이후 필터 제거 | 미검출 → L-1 |
| M2 | 주의 알림 끄기 게이트 제거 | 검출(`CompanionNoticeListenerTest` 실패) |

## 8. 조치 (2026-11-09)

| 항목 | 조치 | 검증 |
|---|---|---|
| M-1 | `CompanionNoticeListener`가 보호자마다 `findActiveConnectedAt` 이후 대화로 연속 일수를 세어 3일 이상인 보호자에게만 자기 일수로 보낸다. 피보호자 단위로 3일이 안 되면 바로 끝낸다(보호자별 일수는 그보다 클 수 없다). 선점은 피보호자·날짜 1번, 기록 일수는 보낸 일수 중 최댓값. 받을 보호자가 없으면 선점하지 않는다 | 리스너 단위 4건 추가(연결 이후 2일 보호자 제외·아무도 3일 안 되면 선점 없음·보호자별 일수·연결 끝난 보호자 제외) + 통합 `주의_알림은_연결_이후_대화로_센다` |
| (공통) | 연결 이후 필터를 `CompanionMoodDays.startedSince` 한 곳으로 모아 화면 주의 칸과 알림이 같이 쓴다 | 결함 주입 M3(도우미 필터 무력화) → 새 테스트 4건 검출, M4(리스너 옛 동작) → 3건 검출 |
| L-1 | `CompanionGuardianServiceTest.attentionCountsOnlySinceConnection` | M3에서 검출 |
| L-2 | `GuardianCompanionControllerSecurityTest`(GUARDIAN 허용, WARD·ADMIN 403, 쓰기 매핑 없음) | 단위 4건 |

- 바뀌지 않은 것: 문구 형식·FCM만·일반 풀·끄기 게이트 위치·하루 1번 UNIQUE·관리자 집계(D-1 대상 아님)·마이그레이션.
- 테스트: `./gradlew build` 통과, vkcs 통합 155건 실패 0(`d086785e`).

