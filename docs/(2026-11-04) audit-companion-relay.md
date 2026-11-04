# 안부 말벗 PR-A 기능 점검 + 알림 종류 영향 범위 (2026-11-04)

> 템플릿 B + C(알림 종류 2개 추가). 대상: dev `4815c36`(#437), AI 계약 `feat/companion-ai-part` `ae621f3`(변경 없음 확인).
> 점검만 했다 - 코드 수정 없음. 근거 문서(`feature-companion-relay.md`·`policy-companion.md`)는 코드로 다시 확인했다.

## 조치 결과 (2026-11-04, `fix/companion-audit`)

| 항목 | 조치 |
|---|---|
| M-1 | 역할 변경 BEFORE_COMMIT에 말벗 기록·질문 안내 기록 삭제 + AI 삭제 대기열, AFTER_COMMIT 비동기 1회 시도. 탈퇴 대기열 조건을 "피보호자이거나 말벗 기록이 있으면"으로 넓힘 |
| M-2 | `CompanionSessionWriterTest`(4) + `CompanionFullIntegrationTest`(4: 위험 1회·마감 URGENT·역할 변경·탈퇴). 결함 주입 3종(턴 선점·마감 선점·끝난 대화 보호 제거) 모두 검출 |
| M-3 | writer는 OPEN만 고침, 서비스는 자리를 잡은 뒤 다시 읽음(턴·마치기·멈춘 대화 마감) |
| L-1 | 합계 초과 413 `COMPANION_UPLOAD_TOO_LARGE` "음성과 사진을 합쳐 10MB까지" |
| L-2 | 재시도 순서 `last_attempt_at NULLS FIRST, requested_at`, 24회마다 `[COMPANION-PURGE-STUCK]` ERROR |
| L-3 | 보호자 알림 종류 설정 응답에 `companionRisk` `{enabled:true, required:true}` |
| L-4 | AI 쪽(대화 TTL) - 재희 전달 목록 |

## 종합 판정

**조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 3 · 🟢 4 → 위 조치 후 🟡·🟢(L-4 제외) 모두 해소

인가·원문 미저장·AI 키·알림 공용 규칙은 통과. 위험 알림 1회 보장 로직은 맞지만 **테스트가 지키지 않고**(변이 2종 미검출), 역할 변경 경로의 데이터 정리와 낡은 스냅샷으로 결과를 덮어쓰는 경로가 남았다.

## 1. 엔드포인트 × 역할

| 경로 | WARD | GUARDIAN·ADMIN | 비로그인 | 근거 |
|---|---|---|---|---|
| `GET /api/ward/companion/today` | 본인 것만 | 403 | 401 | 클래스 `@PreAuthorize("hasRole('WARD')")` |
| `POST /sessions` | 본인 것만(서버가 시간대 결정) | 403 | 401 | 같음 |
| `POST /sessions/{id}/reply`·`/finish` | 남의 대화 403 `COMPANION_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]` | 403 | 401 | `CompanionService.requireOwned` |
| `POST /sessions/{id}/reply/audio` | 같음 | **본문 파싱 전** 403 | 401 | `SecurityConfig` 경로 규칙 + 메서드 보안 |

gosky 실측(비로그인): 세 경로 401, 1MB 본문 음성 요청도 401(본문 읽기 전 차단).

## 2. PHASE A 보안·인가 - PASS

| 항목 | 결과 |
|---|---|
| IDOR | PASS - 남의 sessionId는 403 + WARN(wardId·sessionId만). 존재 여부만 드러남(2026-07-14 수용 범위) |
| 원문 미저장(결정 B) | PASS - 엔티티에 원문 칼럼 없음, 로그는 ID·턴 번호·코드만, `ApiLoggingAspect`는 메서드·경로만(본문·인자 미기록), 요청 본문 캐싱 필터 없음, 위험 알림 문구에 원문·질병명 없음 |
| AI 키 | PASS - 헤더로만, 리다이렉트 안 따라감, AI 오류 문구 미노출(허용 목록 코드만 로그) |
| 업로드 | PASS - 매직 바이트 판별, 크기·장수 상한, 원래 파일 이름 미전달. (문구 문제는 L-1) |
| 경로 주입 | PASS - dialogueId `[A-Za-z0-9_-]{1,64}`, 탈퇴 wardId 영숫자 검사 |

## 3. PHASE B 기능 정합성

- 시간대 창 경계(시작 포함·+60분 미포함), 동시 열기 UNIQUE + 선점 해제, AI 열기 실패 시 다시 열기, 끝난 턴 → 즉시 결과 저장, AI 대화 없음 → LOST: 코드·테스트로 확인.
- AI 계약 대조(`ae621f3` 코드 기준): 요청 필드·턴 응답·finish 결과 필드·오류 코드 모두 일치. 계약 문서 D:90(턴 원문 BE 저장)은 코드·결정 B와 다름 - AI 쪽 문서 정정 대상.

### 🟡 M-1 역할 변경(WARD → GUARDIAN) 시 말벗 데이터가 정리되지 않는다

- `CompanionWithdrawalListener.enqueue`는 **탈퇴 시점 역할이 WARD일 때만** 삭제 대기열에 넣는다. 관리자가 피보호자를 보호자로 바꾼 뒤 그 사람이 탈퇴하면 대기열에 들어가지 않아 **AI 쪽 말벗 데이터(메모리 대화·검수 목록)가 영구히 남는다**. BE `companion_session`·`companion_prompt_log`도 보호자 계정에 남는다(탈퇴 CASCADE로는 지워짐).
- 역할 변경 = "고아가 되는 데이터를 함께 정리"(카메라 삭제와 같은 원칙, CLAUDE.md §8)에 어긋난다.
- 수정안: `UserRoleChangedEvent` 리스너에서 현재 역할이 WARD가 아니면 그 사용자의 `companion_session`·`companion_prompt_log`를 지우고 삭제 대기열에 넣는다(AFTER_COMMIT 동기면 REQUIRES_NEW - `SyncAfterCommitListenerPropagationTest` 표 갱신). 탈퇴 대기열 조건도 "WARD이거나 말벗 기록이 있으면"으로 넓힌다.

### 🟡 M-3 낡은 스냅샷으로 끝난 대화 결과를 덮어쓸 수 있다

- `CompanionService`는 대화 행을 **자리를 잡기 전에** 읽고(`requireOpenOwned`·`findStale`), `CompanionSessionWriter.finish`는 상태가 OPEN인지 다시 보지 않는다.
- 경로: ① 마감 스케줄러가 멈춘 대화 목록을 읽은 직후 어르신이 "마치기"를 눌러 USER_STOP으로 끝남 → 스케줄러가 같은 대화를 다시 finish → 종료 이유가 NO_RESPONSE로, 끝난 시각이 덮인다. ② 앞 턴이 대화를 끝낸 직후 들어온 턴 → AI `DIALOGUE_ENDED` → 결과를 다시 저장.
- 지금은 기분·턴 수가 AI 결과와 같아 피해가 작지만, PR-B의 주의(NOTICE) 계산이 종료 이유·날짜를 쓰면 틀어진다.
- 수정안: `writer.finish`·`recordTurn`은 OPEN이 아니면 아무것도 바꾸지 않고 현재 행을 돌려준다. 서비스는 자리를 잡은 뒤 행을 다시 읽어 상태를 본다.

## 4. PHASE C 알림 공용 규칙 영향 범위 - PASS

불변식: "강제 FCM은 어떤 설정으로도 줄지 않고, 정지 수신자는 전 채널 차단".

| 사용처 | 판정 | 근거 |
|---|---|---|
| `NotificationDispatcher` 정책 분기 | PASS | `COMPANION_RISK`는 기존 `FORCED_PUSH_PLUS_SETTINGS` 경로 그대로(강제 FCM + 설정 ∩ {FCM,SMS} + 미전달 시 문자 대체), 정지 수신자 차단·설정 조회 실패 격리(#425) 그대로 |
| `SmsFallbackLimiter` | PASS | 키 = 종류 + 수신자 → 말벗 위험 문자는 이상감지와 따로 시간당 30건 |
| 알림톡 매핑 | PASS | `templates`는 `ANOMALY_DETECTED` 하나(`AlimtalkTemplateMappingGuardTest` 1번이 고정) |
| 이메일 | PASS | 두 종류 모두 `Email.ALLOWED` 없음(`NotificationTypeEmailPolicyTest`) |
| 관리자 이력 탭·라벨 | PASS | 기타 탭, "안부 말벗"(switch 전수) |
| FCM 긴급도 | PASS | `COMPANION_RISK`만 high(코드·yaml 기본값 같음) |
| `notification_log.type` | PASS | VARCHAR(40), CHECK 없음(V54) |
| 보호자 알림 종류 설정 | 🟢 L-3 | 디스패처는 설정을 모름(정책대로). 응답에 말벗 위험 알림이 `required`로 안 보임 |
| executor | PASS | 위험 = `urgentNotificationExecutor`, 탈퇴 삭제 = `aiPurgeExecutor`(`NotificationExecutorAssignmentTest`) |

## 5. PHASE D 구조·계약

- AI 호출은 트랜잭션 밖, 쓰기는 짧은 트랜잭션(PASS). 동시 상한 자리는 모든 경로가 try-with-resources(PASS). 인덱스: `findStale`(status,last_activity_at), 날짜 조회(UNIQUE 앞부분) - PASS.
- 응답 `Cache-Control: no-store`, Swagger 오류 목록이 코드와 일치(PASS).

## 6. PHASE E 테스트 - 결함 주입

| 변이 | 결과 |
|---|---|
| 소유 확인 제거 | 검출 |
| 멈춘 대화 NO_RESPONSE 변환 제거 | 검출 |
| AI 오류 코드 변환 제거 | 검출 |
| **턴 위험 신호 선점 제거(writer)** | **미검출** |
| **마감 URGENT 선점 제거(writer)** | **미검출** |

### 🟡 M-2 위험 알림 1회 보장을 지키는 테스트가 없다

- 서비스 테스트는 `writer`를 목으로 두고, 통합 테스트는 저장소 선점 쿼리만 본다. writer가 위험 신호에서 선점·이벤트 발행을 빼먹어도 전 테스트가 통과한다 - 말벗에서 가장 중요한 안전 경로다.
- 수정안: `CompanionSessionWriter` 단위 테스트(턴 위험 → 이벤트 1번, 같은 대화 두 번째는 0번, 마감 URGENT 미발송 → 1번, 이미 보냈으면 0번) + `CompanionRiskFullIntegrationTest`(턴 → 커밋 → 긴급 리스너 → 보호자 `COMPANION_RISK` 디스패치 1건).

## 7. 🟢 Low

- **L-1 업로드 합계 초과 문구**: 음성 5MB + 사진 5장×1MB는 전역 요청 상한 10MB를 넘는다. 이때 "음성은 5MB를 넘을 수 없습니다."가 나가 원인과 다르다 → 합계 전용 문구(`COMPANION_UPLOAD_TOO_LARGE` 413 "음성과 사진을 합쳐 10MB까지") 또는 사진 상한 조정.
- **L-2 탈퇴 삭제 재시도 막힘**: 오래된 순 + 첫 실패 중단이라 영구히 실패하는 1건이 대기열 전체를 막을 수 있다(지금 AI는 모든 ID에 200이라 현실 위험 낮음). 시도 횟수가 늘어도 경보가 없다 → `last_attempt_at` 오래된 순으로 돌리고, 시도 N회 넘으면 ERROR 로그.
- **L-3 보호자 알림 종류 설정에 말벗 위험 알림이 안 보임**: 끌 수 없는 필수 알림인데 `required` 목록에 없다 → PR-B 설정 작업 때 `companionRisk: {required: true}` 추가.
- **L-4 AI 열기 시간 초과 뒤 고아 대화**: BE는 선점을 풀지만 AI는 이미 대화를 만들었을 수 있다(AI 대화 TTL 없음) → AI 쪽 항목(재희 전달 목록의 TTL과 같은 건).

## 8. 실서버(gosky dev, 읽기만)

비로그인 401(3경로) · V65 적용(flyway 65) · 활성 질문 19건(09·12·15·21시 4건, 18시 3건) · `companion_session`·`prompt_log`·`purge_request` 0건 · `.env.dev`에 `COMPANION_` 키 0개(스위치 꺼짐 = 503 예정). 시험 피보호자 로그인 확인은 하지 않았다(새 계정을 만들지 않음).

## 9. 재희(AI)에게 넘길 것

계약 문서 D:90 정정, main 서버 등록 시 `register_companion_error_handler` 연결(없으면 오류가 `{detail}`로 나가 BE가 전부 503 처리), 대화 TTL(L-4), 한 턴 60초.

## 10. 수정 커밋 메시지 초안

```
fix: 안부 말벗 점검 반영 - 역할 변경 정리·끝난 대화 덮어쓰기 방지·위험 알림 테스트
```
