# 기능 점검 - PR #249 SOS 연타 재발송 알림 반복 횟수

> 대상: PR #249 (머지 `b7d2d13` 2026-09-21, 구현 커밋 `1cda6b4` 2026-09-11) · 마이그레이션 없음
> 근거 문서: `docs/(2026-09-11) feature-sos-repeat-count-notification.md` (사실로 믿지 않고 코드로 대조)
> 점검일: 2026-09-21 · 템플릿: work-prompt B(기능 점검) · 점검자: Claude Code (점검만, 코드 미수정)

---

## PHASE -1. 사전 환경 확인

| 항목 | 결과 |
|---|---|
| HEAD | `930ce14` (dev, 2026-09-21) |
| 대상 커밋 | `b7d2d13`의 1부모 대비 diff = 5파일(리스너·리포지토리·테스트·문서 2). 브랜치 쪽 `aae1f0b`는 dev를 끌어온 병합이라 22파일이 보이지만 #249 변경분이 아니다 |
| 대상 테스트 | `./gradlew test --tests "*SosNotificationListenerTest*"` - **10 tests / 0 failures / 0 errors / 0 skipped** (exit 0) |
| 빌드 | `./gradlew build -x test --no-daemon` - **통과** (exit 0) |
| 마이그레이션 | 최신 V51. #249는 마이그레이션 없음 - 문서 주장과 일치 |

## PHASE 0. 점검 대상

| 파일 | 변경 |
|---|---|
| `domain/sos/listener/SosNotificationListener.java` | 집계(`countRecentOccurrences`)·문구(`buildBody`)·`repeatCount` 페이로드·발송 로그 |
| `domain/sos/repository/SosEventRepository.java` | `countByWardIdAndCreatedAtGreaterThanEqual(String, OffsetDateTime)` |
| `test/.../SosNotificationListenerTest.java` | 5건 추가 |

**공용 규칙 미변경 확인 → 템플릿 C 전환 불필요**: `NotificationDispatcher`·`NotificationType`(`WARD_SOS` 정책)·`SosTriggeredEvent` 계약·`ConnectionService`·enum·DB CHECK 모두 diff에 없다. 발송은 기존대로 `notificationDispatcher.dispatch()`를 거친다.

### 알림 경로

```
SosService.trigger (@Transactional, 이력 save → SosTriggeredEvent 발행)
  → [커밋] → SosNotificationListener.handleSosTriggered (@Async notificationExecutor, AFTER_COMMIT)
      1) getActiveGuardianIds(wardId)          - 0명이면 종료
      2) cooldown.tryAcquire(wardId)           - 30초 SET NX, Redis 장애 시 fail-open
      3) countRecentOccurrences(wardId)  ← 신규 - 실패 시 1로 폴백
      4) 보호자별: WS sos-triggered + dispatcher.dispatch(WARD_SOS)  (repeatCount 추가)
```

## PHASE A. 보안·인가

| 항목 | 결과 | 근거 |
|---|---|---|
| 집계 범위가 그 피보호자로 한정 | ✅ PASS | `SosNotificationListener.java:126` - `wardId` 등치 조건. 다른 피보호자 이력이 섞일 경로 없음 |
| WS 토픽 인가 | ✅ PASS | 기존 `/topic/{userId}/sos-triggered`(`WebSocketEventPublisher.java:23`)에 키만 추가 - 수신자·토픽 불변 |
| 로그 PII | ✅ PASS | 발송 로그(`:114`)는 sosEventId·인원·횟수만, 집계 실패 WARN(`:130`)은 wardId·예외 메시지만. 이름·전화번호 신규 노출 없음 |
| 정지 수신자 차단 | ✅ PASS | 발송이 여전히 `dispatch()` 단일 경로 - 리스너가 채널을 직접 부르지 않는다 |

## PHASE B. 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 필수 알림 보장(fail-open) | ✅ PASS | `:124-132` 예외를 삼키고 1 반환 → 기본 문구로 발송. 테스트로 고정됨 |
| 순서: 쿨다운 → 집계 | ✅ PASS | `:81` tryAcquire 후 `:88` 집계. 쿨다운 fail-open(true 반환) 시에도 같은 순서 |
| 현재 건 포함 여부 | ✅ PASS | AFTER_COMMIT이라 방금 저장된 행이 보인다. `Math.max(count, 1)`로 0도 1로 보정(`:128`) |
| 시간대 | ✅ PASS | `created_at TIMESTAMPTZ`(V26) + `OffsetDateTime` 파라미터 → 순간(instant) 비교. 값은 앱 쪽 `offsetDateTimeProvider`(JpaAuditingConfig)가 채워 비교 기준과 같은 JVM 시계 |
| 경계 | ✅ PASS | `GreaterThanEqual`로 정확히 10분 전 포함. 흐르는 창이라 자정 무관. 탈퇴로 `ward_id` NULL인 행은 등치 조건에서 자연 제외 |
| 발생 경로(GUARDIAN_CALL) | ✅ PASS | trigger_type 필터 없이 함께 센다 - "발생 경로로 알림을 가르지 않는다"(정책 2026-07-30 ②)와 일치 |
| 1회 문구 불변 | ✅ PASS | `:144` 기존 문구 그대로, 테스트로 고정 |
| 단정 표현 | ✅ PASS | "계속 도움을 요청하고 있습니다" - 횟수(사실)만, 위급도·원인 없음 |
| 알림톡 | ✅ PASS | `application.yaml` `templates`에 `WARD_SOS` 없음 - 계속 스킵 |
| SMS 90바이트 | ⚠️ L-1 | 아래 |

## PHASE C. 구조·계약

| 항목 | 결과 | 근거 |
|---|---|---|
| 트랜잭션 경계 | ✅ PASS | 리스너에 `@Transactional` 없음 - 카운트 쿼리 1회가 짧게 커넥션을 쓰고 반납. 쓰기 없음 |
| 인덱스 | ✅ PASS | `idx_sos_events_ward_created (ward_id, created_at DESC)`(V26)가 등치+범위 카운트를 커버. V31 테이블 개명 후에도 인덱스는 유지(이름만 옛 복수형) |
| FE 계약 | ✅ PASS | `repeatCount`는 1일 때도 항상 포함, `String.valueOf`로 문자열 - FCM data(문자열만 허용) 규약 충족. 기존 키 불변 |
| 동시성 | ✅ PASS | 쿨다운 SET NX가 원자적이라 같은 창에서 알림 두 건이 같은 횟수를 보내는 경합 없음 |
| 횟수의 의미 | 🟢 L-2 | 아래 |

## PHASE D. 테스트

추가 5건은 목이 결과를 미리 정하는 형태지만, 각자 **리스너의 분기**(문구 선택·폴백·순서·창 계산)를 검증하므로 의미가 있다. "쿨다운 시 집계 미조회"는 `verifyNoInteractions`로 순서 불변식을 정확히 고정한다.

빠진 케이스는 🟢 L-3으로 묶었다.

---

## 이슈

### 🟢 L-1. 긴 이름에서 문자가 90바이트를 넘는다 - 문서의 "단문 한도 안" 주장은 짧은 이름에서만 참

- 근거: 문자 본문은 `"[" + title + "] " + body`(`SmsNotificationChannel.java:48`), 반복 문구는 `SosNotificationListener.java:146`. 이름은 최대 20자(`RegisterRequest.java:36` `@Size(max = 20)`, `UserUpdateRequest.java:22`).
- EUC-KR 기준 실측: 이름 3자 73바이트(문서 값과 일치) / **11자 89~91바이트 / 12자 이상 91바이트 초과 / 20자 107~109바이트**(횟수 자릿수에 따라). 기존 1회 문구는 20자 이름에서도 81바이트라 이번 변경으로 처음 한도를 넘게 된다.
- 영향: `SmsSender`가 메시지 타입을 지정하지 않아 Solapi가 길이로 SMS/LMS를 판별하므로 **발송은 되고 LMS 요금이 붙는 것**으로 본다(실발송으로 확인하지는 않음). 실사용 이름 대부분은 11자 이하라 발생 빈도는 낮다.
- 제안: 코드 수정 불요. 기능 문서의 "약 73바이트라 단문 한도 안" 문장을 "이름 11자 이하일 때"로 한정하는 문서 정정만.

### 🟢 L-2. "N번째"는 이 SOS의 순번이 아니라 집계 시점까지의 누적 건수다 (정보)

- 근거: 리스너가 `@Async`라 실행 전에 뒤이은 연타가 커밋되면 그 행까지 센다(`:126`). 또 10분 창 안에 별개의 두 상황이 있어도 두 번째를 "계속 요청하고 있습니다"로 표현한다.
- 판단: 보호자에게 필요한 정보("지금까지 몇 번 눌렀나")로는 오히려 정확하고, 창 10분을 한 상황으로 보는 것은 문서에 적힌 설계 결정이다. **수정 불요** - 문구를 바꿀 일이 생기면 이 성질을 전제로 할 것.

### 🟢 L-3. 테스트 공백 3건

1. **문구 전환 경계값 2** 미검증 - 테스트는 1과 3만 쓴다. `repeatCount < 2` 조건이 `< 3`으로 바뀌어도 통과한다.
2. **카운트 0 보정**(`Math.max`)이 명시 테스트 없이 "집계 창" 테스트의 목 기본값(0)에 우연히 기대고 있다.
3. **파생 쿼리가 실제 DB에서 한 번도 실행되지 않았다** - 리포 전반의 Testcontainers 부재(audit-index M-1)와 같은 공백. 메서드 이름 파싱·TIMESTAMPTZ 바인딩은 목 테스트로 검증되지 않는다.

- 제안: 1·2는 기존 테스트 클래스에 케이스 2개 추가로 막을 수 있다(다음 SOS 변경 때). 3은 Testcontainers 도입 시 함께.

## 종합 판정

**PASS (Low 3건, 코드 수정 불요)**. 필수 알림 보장(fail-open)·쿨다운 순서·인가·PII·정지 수신자 차단·알림톡 미매핑이 모두 유지되고, 공용 규칙을 건드리지 않아 **템플릿 C(영향 범위 점검)로 전환할 필요 없다.** L-1은 기능 문서 문구 정정, L-3의 1·2는 다음 SOS 변경 때 테스트 보강 대상이다.
