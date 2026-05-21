# Connection 도메인 종합 점검 보고서

| 항목 | 내용 |
|------|------|
| 점검 일자 | 2026-05-21 |
| 점검자 | Claude Code (Opus 4.7) |
| 대상 도메인 | `domain/connection/**` (+ 알림 연동·스키마·정책) |
| 기준 브랜치 | `dev` (점검 시작 `c1d9334` → 종료 `fb4e2db`) |
| 적용 스킬 | architecture-review · spring-boot-patterns · jpa-patterns · concurrency-review · security-audit · api-contract-review · performance-smell-detection · logging-patterns · test-quality · clean-code · solid-principles |

## 점검 범위

- **1차(직접)**: `domain/connection/**` — controller 2, dto 3, entity 1, event 3, listener 1, repository 1, service 1
- **2차(알림 연동)**: `domain/notification/FcmService`(호출 지점), `global/websocket/{WebSocketEventPublisher, StompSubscriptionAuthorizationInterceptor, JwtHandshakeInterceptor}`, `global/config/WebSocketConfig`
- **3차(스키마·정책)**: Flyway `V1/V2/V8/V11/V19/V20/V21`, `global/exception/{ErrorCode, GlobalExceptionHandler}`, `global/security/{RateLimitService, SecurityConfig}`, `global/util/RedisKeys`, `global/aop/ApiLoggingAspect`

> 참고: 의뢰 시 "V15 relation 컬럼"은 실제 **V19**, "ward `/select` deprecated"는 이미 **완전 제거 종결**(guardian `/select`는 현역 별개 엔드포인트)로 확인됨.

## 점검 중 처리한 변경 (PR)

| PR | 성격 | 내용 |
|----|------|------|
| **#152** `refactor/remove-connection-priority` | 사전 정리 | priority(통화 우선순위) 컬럼·응답 필드·정렬 경로 제거 (2026-05-19 변경경로 제거로 죽은 필드화). V20 `DROP COLUMN`, ward ACTIVE 정렬 `createdAt` 대체 |
| **#153** `fix/connection-optimistic-lock` | Critical 수정 | A2 lost update 차단: `Connection.@Version` + V21 `version` 컬럼 + `ObjectOptimisticLockingFailureException`→409 + accept ErrorCode 교정 |

두 PR 모두 `dev` 머지 완료, 빌드 통과(`./gradlew build -x test`).

---

## Phase별 발견 이슈

### PHASE A — 동시성·트랜잭션 (최우선)

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| A2 | 🔴 Critical | 상태 전이(`accept/refuse/cancel/disconnect`)가 check-then-act + 락 부재 → 동시 요청 시 lost update. accept∥cancel 시 상태·알림(AcceptedEvent) 불일치 | ✅ #153 (@Version 낙관락) |
| A3 | 🟠 High | 동시 연결 해제 시 `ConnectionDisconnectedEvent` 중복 발행 (동일 근원) | ✅ #153 (@Version으로 해소) |
| A4 | ⚪ N/A | 우선순위 변경 동시성 — 변경 경로가 2026-05-19 제거됨, 점검 대상 없음 | — (#152로 필드까지 제거) |
| A5 | 🟡 Medium | 역할 변경→자동 CANCELLED **미배선**. `findActiveByUserId`·`User.updateRole`·`completeRole` 모두 호출 0건(dead). 향후 역할 변경 도입 시 연결 정합성 미처리 | OPEN (설계 결정) |
| B1 | ✅ | 트랜잭션 경계 일관(변경=@Transactional, 조회=readOnly), 이벤트 발행이 tx 내부 | — |
| B2 | ✅ | 리스너 3종 `@TransactionalEventListener(AFTER_COMMIT)` → 롤백 시 미발송. 이벤트 전부 불변 record(엔티티 참조 없음) → LazyInit 위험 없음 | — |
| B3 | 🟡 Medium | 리스너 **동기**(`@Async`/`@EnableAsync` 전무) → FCM 왕복이 응답 지연에 포함. `fcmTokenRepository.findByUserId`만 try/catch 밖(커밋 후 예외 전파 가능, 확률 낮음) | OPEN |
| A1 | 🟢 Low | 동시 중복요청은 `uq_connections_active` 부분 유니크 + `DataIntegrityViolationException`→**409**로 안전. 다만 충돌 메시지가 범용 | OPEN(무해) |

### PHASE B — 보안 (security-audit) — Critical/High 없음 ✅

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| C1 인가 | ✅ | 클래스 레벨 `@PreAuthorize`(GUARDIAN/WARD), `@EnableMethodSecurity`, deny-by-default. 목록 API는 `@AuthenticationPrincipal` 사용(파라미터 IDOR 불가) | — |
| C2 IDOR | ✅ | 단건 액션 전부 `getConnectionFor{Ward,Guardian}` 소유권 검증 → 403 | — |
| C3 어뷰징 | ✅ | 요청 endpoint Rate Limit 1분 10회(guardianId), 본인연결·중복 차단 | — |
| C4 WebSocket | ✅ | 핸드셰이크 JWT→세션 userId, SUBSCRIBE 시 `/topic/{userId}`==세션 검증(도청 IDOR 차단) | — |
| C5 PII | ✅ | ACTIVE만 전화·주소 노출, PENDING은 전화 마스킹+주소 미노출 (정책 명확) | — |
| B-C3a | 🟢 Low | 요청 실패 응답이 404/400/409 구분 → 이론적 ID 열거 oracle. 6자리 ID(~22억)+10회/분으로 실질 불가 | OPEN(low) |
| B-C4a | 🟢 Low | WS 핸드셰이크 토큰이 쿼리 파라미터 → 액세스 로그 유출 가능(WS 인프라 레벨) | OPEN(low) |

### PHASE C — 구조·품질

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| C-ARCH1 | 🟡 Medium | `ConnectionService`→`UserRepository` 직접 의존(도메인 간 결합) | OPEN |
| C-DEAD1 | 🟡 Medium | dead code 5개(`isConnected`·`getConnection`·`findByGuardianIdAndStatus`·`countByStatus`·`findActiveByUserId`) | OPEN |
| C-SOLID1 | 🟢 Low | `ConnectionService` 명령+조회 12개 — 경계상 SRP, 규모상 허용 | OPEN(low) |

### PHASE D — API 계약

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| D-1 | 🟡 Medium | 이미 처리된 요청 재처리 → **400**(관례상 409). Swagger·프론트 동반 변경 필요 | OPEN(협의) |
| D-6 | 🟡 Medium | `ConnectionStatus` 거절/취소/해제 → 모두 `CANCELLED` 평탄화, 이력 구분 불가 | OPEN(설계) |
| D-2 | 🟢 Low | REST 경로 비일관(`/{role}/disconnection/{id}`가 `/connection/` 밖) | OPEN(low) |
| accept ErrorCode | ✅ | not-PENDING 시 `CONNECTION_NOT_ACTIVE`→`CONNECTION_NOT_PENDING` 교정 | ✅ #153 |

### PHASE E — 데이터 계층

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| E-4 | 🟡 Medium | FCM 동기 발송 → 응답 지연(=B3) | OPEN |
| E-3 | 🟢 Low | guardian 조회 `createdAt` 정렬 인메모리(인덱스 없음), 영향 미미 | OPEN(low) |
| ✅ | — | 연관관계 없음(String FK) → N+1/lazy 원천 없음, 목록 `findAllById` 배치, ward 쿼리 V20 인덱스 커버, V19 relation 적정 | — |

### PHASE F — 로깅

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| F-1 | 🟡 Medium | `ConnectionService` 도메인 lifecycle 로그 0건 | OPEN |
| F-2 | 🟢 Low | MDC가 userId만, connectionId 미포함 | OPEN(low) |
| ✅ | — | FCM/WS 실패 로깅, raw PII 미로깅 | — |

### PHASE G — 테스트

| ID | 심각도 | 발견 | 수정 |
|----|--------|------|------|
| G-1 | 🟠 High | connection 테스트 0건 (CLAUDE.md §8.6 위반) | OPEN |

---

## 미해결 TODO (다음 점검 사이클 이월)

- **🟠 G-1**: `ConnectionServiceTest`(요청/수락/거절/해제/예외/동시성@Version/이벤트 verify) + `ConnectionNotificationListenerTest`(Mock FCM·WS). JUnit5+Mockito+AssertJ.
- **🟡 B3/E-4**: 알림 비동기화(`@EnableAsync` + 전용 executor + 리스너 `@Async`) — 신규 인프라라 신중.
- **🟡 A5 / C-DEAD1**: dead code 5개 제거 또는 역할 변경 연동 배선(설계 결정).
- **🟡 C-ARCH1**: user 조회용 포트/서비스 분리 검토.
- **🟡 D-1**: 이미 처리된 요청 재처리 시 409 정렬 — 프론트 협의.
- **🟡 D-6**: `ConnectionStatus` 세분화(REFUSED/DISCONNECTED 등) — 설계+프론트 협의.
- **🟡 F-1**: 상태 전이 INFO 로그 추가(PII 제외).
- **🟢 Low**: B-C3a, B-C4a, C-SOLID1, D-2, E-3, F-2.

## 프론트 호환성 변경 사항

| 변경 | PR | 영향 |
|------|----|------|
| 응답에서 `priority` 필드 제거 | #152 | guardian `/select`·`/requests`, ward `/active`, 단건 — **프론트 조율 필요** |
| 동시 상태 전이 시 409 신규 케이스 | #153 | 응답 필드 무변경. 클라이언트는 "이미 처리됨, 새로고침" 처리 권장 |
| (이월) D-1 적용 시 400→409, D-6 적용 시 status 값 추가 | — | 적용 시 별도 협의 |

## 종합 평가

connection 도메인은 **인가·IDOR·WebSocket·PII 보안이 견고**하고, 트랜잭션 경계·이벤트 AFTER_COMMIT 분리·N+1 회피가 잘 되어 있다. 최대 위험이던 **A2(상태 전이 lost update, Critical)** 는 본 점검에서 낙관적 락으로 해소했다. 남은 핵심 과제는 **테스트 부재(G-1)** 와 **알림 동기 실행(B3)** 이며, 그 외는 유지보수성·계약 일관성 수준의 이월 항목이다.
