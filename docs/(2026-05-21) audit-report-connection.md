# Connection 도메인 종합 점검 보고서 (2026-05-21)

| 항목 | 내용 |
|------|------|
| 점검 일자 | 2026-05-21 |
| 점검자 | Claude Code (Opus 4.7) |
| 대상 도메인 | `domain/connection/**` (+ 알림 연동·스키마·정책) |
| 기준 브랜치 | `dev` (점검 시작 `c1d9334`) |
| 적용 스킬 | architecture-review · spring-boot-patterns · jpa-patterns · concurrency-review · security-audit · api-contract-review · performance-smell-detection · logging-patterns · test-quality · clean-code · solid-principles |
| 처리 결과 | 식별 이슈 대부분 본 사이클에서 수정 완료 (PR #152·#153·#155·#156·#157·#158). Group 4(리팩터) 의도적 보류 |

## 점검 범위

- **1차(직접)**: `domain/connection/**` — controller 2, dto 3, entity 1, event 3, listener 1, repository 1, service 1
- **2차(알림 연동)**: `domain/notification/FcmService`(호출 지점), `global/websocket/{WebSocketEventPublisher, StompSubscriptionAuthorizationInterceptor, JwtHandshakeInterceptor}`, `global/config/WebSocketConfig`
- **3차(스키마·정책)**: Flyway `V1/V2/V8/V11/V19/V20/V21/V22/V23`, `global/exception/{ErrorCode, GlobalExceptionHandler}`, `global/security/{RateLimitService, SecurityConfig}`, `global/util/RedisKeys`, `global/aop/ApiLoggingAspect`

> 참고: 의뢰 시 "V15 relation 컬럼"은 실제 **V19**, "ward `/select` deprecated"는 이미 **완전 제거 종결**(guardian `/select`는 현역 별개 엔드포인트)로 확인됨.

## 처리 PR 목록

| PR | 성격 | 내용 | 상태 |
|----|------|------|------|
| #152 | 사전 정리 | priority 컬럼·응답 필드·정렬 제거 (V20) | merged |
| #153 | 🔴 Critical | A2 상태 전이 lost update — `@Version` 낙관락 (V21) + 409 핸들러 + accept ErrorCode 교정 | merged |
| #154 | 테스트 | ConnectionService·리스너 단위 테스트 26건 (G-1) | merged |
| #155 | 정리·관측성 | dead code 5개 제거(C-DEAD1/A5) + 상태 전이 로그(F-1/F-2) + 정렬 인덱스(E-3, V22) | merged |
| #156 | 인프라 | 알림 `@Async` 비동기화 (B3/E-4) | merged |
| #157 | API 계약 | 재처리 400→409(D-1) + disconnection 경로 일관화(D-2) | merged (프론트 통보 필요) |
| #158 | API 계약 | ConnectionStatus 세분화 REFUSED/DISCONNECTED(D-6, V23) | merged (프론트 통보 필요) |

(점검 산출물 docs는 `5969451`로 dev 직접 커밋)

---

## Phase별 발견 이슈 및 처리

### PHASE A — 동시성·트랜잭션

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| A2 | 🔴 Critical | 상태 전이 check-then-act + 락 부재 → 동시 요청 lost update, accept∥cancel 시 상태·알림 불일치 | ✅ 수정완료 #153 (@Version) |
| A3 | 🟠 High | 동시 연결 해제 시 DisconnectedEvent 중복 발행 | ✅ 수정완료 #153 (동일 근원) |
| A4 | ⚪ N/A | 우선순위 변경 동시성 — 경로 제거됨 | ✅ #152 (필드 제거) |
| A5 | 🟡 Medium | 역할 변경 자동 CANCELLED 미배선 + dead query | ✅ 수정완료 #155 (dead query 제거; wiring은 트리거 부재로 N/A) |
| B1 | ✅ | 트랜잭션 경계 일관, 이벤트 발행 tx 내부 | 양호 유지 |
| B2 | ✅ | 리스너 AFTER_COMMIT, 이벤트 불변 record | 양호 유지 |
| B3 | 🟡 Medium | 리스너 동기 실행 → FCM 지연이 응답에 포함 | ✅ 수정완료 #156 (@Async) |
| A1 | 🟢 Low | 동시 중복요청 409 안전, 충돌 메시지 범용 | 무해 — 현행 유지 |

### PHASE B — 보안 (Critical/High 없음 ✅)

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| C1 인가 / C2 IDOR / C3 어뷰징 / C4 WebSocket / C5 PII | ✅ | 클래스 레벨 @PreAuthorize, 소유권 검증, Rate Limit, 구독 인가, PII 노출 정책 모두 견고 | 양호 유지 |
| B-C3a | 🟢 Low | 실패 응답 404/400/409 구분 → 이론적 ID 열거 (ID 공간+rate limit로 실질 불가) | 현행 유지 가능 |
| B-C4a | 🟢 Low | WS 핸드셰이크 토큰 쿼리 파라미터 (WS 인프라 레벨) | 백로그(짧은 TTL·로그 스크러빙) |

### PHASE C — 구조·품질

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| C-DEAD1 | 🟡 Medium | dead code 5개 | ✅ 수정완료 #155 |
| C-ARCH1 | 🟡 Medium | ConnectionService → UserRepository 직접 의존 | ⏸ 보류 (모놀리식 실용 패턴, 가치 대비 churn) |
| C-SOLID1 | 🟢 Low | ConnectionService 명령+조회 혼재 | ⏸ 보류 (규모상 분리 불필요) |

### PHASE D — API 계약

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| D-1 | 🟡 Medium | 이미 처리된 요청 재처리 400 (관례상 409) | ✅ 수정완료 #157 |
| D-2 | 🟢 Low | disconnection 경로가 /connection prefix 밖 | ✅ 수정완료 #157 |
| D-6 | 🟡 Medium | 거절/취소/해제 평탄화(CANCELLED) | ✅ 수정완료 #158 (REFUSED/DISCONNECTED) |
| accept ErrorCode | 🟢 Low | not-PENDING 시 NOT_ACTIVE 오인 | ✅ 수정완료 #153 |

### PHASE E — 데이터 계층

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| E-4 | 🟡 Medium | FCM 동기 발송 → 응답 지연 | ✅ 수정완료 #156 (=B3) |
| E-3 | 🟢 Low | guardian 정렬 인메모리 | ✅ 수정완료 #155 (V22 인덱스) |
| N+1·연관관계 | ✅ | 연관관계 없음(String FK), 목록 배치, ward 쿼리 인덱스 커버 | 양호 유지 |

### PHASE F — 로깅

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| F-1 | 🟡 Medium | 도메인 lifecycle 로그 부재 | ✅ 수정완료 #155 |
| F-2 | 🟢 Low | MDC connectionId 미포함 | ✅ 수정완료 #155 (로그에 connectionId 포함) |
| FCM/WS 실패 로깅·PII | ✅ | 실패 로깅, raw PII 미로깅 | 양호 유지 |

### PHASE G — 테스트

| ID | 심각도 | 발견 | 수정 여부 |
|----|--------|------|-----------|
| G-1 | 🟠 High | connection 테스트 0건 | ✅ 수정완료 #154 (26건, 이후 #158에서 갱신) |

---

## 미해결 / 의도적 보류 (다음 사이클)

- **⏸ C-ARCH1**: user 조회 의존 분리 — 모놀리식 실용 패턴이라 보류(필요 시 user 도메인 published port 도입).
- **⏸ C-SOLID1**: ConnectionService 분리 — 규모상 불필요, 보류.
- **🟢 B-C4a**: WS 핸드셰이크 토큰 쿼리 파라미터 — 짧은 TTL·액세스 로그 스크러빙(WS 인프라 공통 과제).
- **🟢 B-C3a / A1-L1**: 정보성 — 현행 유지.

## 프론트 호환성 변경 사항

| 변경 | PR | 영향 | 머지 |
|------|----|------|------|
| 응답 `priority` 필드 제거 | #152 | 프론트 조율 필요 | merged |
| 동시 전이 시 409 신규 케이스 | #153 | 응답 필드 무변경 | merged |
| 재처리 400→409 | #157 | 에러 상태코드 분기 시 확인 | merged |
| 해제 URL `/connection/disconnection` | #157 | **하위호환 불가 — URL 수정 필수** | merged |
| 응답 `status`에 REFUSED/DISCONNECTED | #158 | status 분기 시 신규 값 처리 | merged |

> ⚠️ #157·#158은 dev 머지·배포 완료. 프론트엔드가 개발 중이라 선반영했으며, 위 3가지(해제 URL 변경·status 신규 값·재처리 409)를 **프론트 팀에 통보** 필요.

## 종합 평가

connection 도메인은 보안(인가·IDOR·WebSocket·PII)이 견고하고 트랜잭션·이벤트 분리·N+1 회피가 잘 되어 있었다. 최대 위험이던 **A2(상태 전이 lost update, Critical)** 를 낙관적 락으로 해소했고, 테스트 부재(G-1)·동기 알림(B3)·관측성(F-1)·dead code(C-DEAD1) 등 이월 항목을 본 사이클에서 모두 처리했다. API 계약 변경(#157·#158)도 프론트엔드 개발 중 시점에 맞춰 선반영(머지)했으며 프론트 통보만 남았다. 남은 것은 의도적으로 보류한 리팩터(Group 4)뿐이다.
