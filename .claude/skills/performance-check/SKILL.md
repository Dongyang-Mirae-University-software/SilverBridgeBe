---
name: performance-check
description: 앱 레이어 성능 점검이 필요할 때 사용. "느려", "응답 시간", "성능 점검", "스레드", "캐시 누락", "비동기", "WebSocket 지연" 같은 요청에서 발동. 동기 블로킹 / 캐시 / 커넥션 풀 / 비동기 / WebSocket fan-out 등을 본다. DB·SQL 쿼리 자체 최적화는 db-improve 영역.
---

## 목적
앱 레이어(스레드·메모리·네트워크·캐시·비동기) 측면의 성능 병목을 찾아 개선안을 제시한다.

## 입력/스코프
- 기본: 현재 브랜치 변경분 + 응답 시간이 중요한 경로 (Controller → Service)
- 사용자 지정 시 특정 도메인
- 분석 대상: `*Controller`, `*Service`, `*Listener`, `*Config`, `application*.yaml` (스레드 풀·커넥션 풀·캐시 설정)

## 절차
1. **핫 패스 식별** — 자주 호출되는 endpoint, WebSocket 메시지 핸들러, 이벤트 리스너
2. **블로킹 I/O 탐지** — 외부 호출(FCM, Solapi, 카카오) 동기/비동기 여부
3. **N+1 외 쿼리 패턴** — 루프 안 Repository 호출은 `db-improve`로 위임, 여기선 콜 횟수만 보고
4. **캐시 적용 가능성** — Redis 사용 가능한데 매번 DB 조회하는 경로
5. **비동기 처리** — Spring Event 리스너의 `@Async`, FCM 발송 비동기화 여부
6. **설정값 점검** — 스레드 풀, 커넥션 풀, WebSocket 메시지 버퍼
7. **수정안 적용** — 비동기/캐시 도입은 정합성 영향 있으므로 사용자 확인
8. **검증** — `./gradlew build -x test --no-daemon`, 가능하면 단순 부하 테스트
9. **커밋** — `perf: <도메인> <개선 요약>`

## 검출 기준 (이 프로젝트 특화)

### 블로킹 I/O
- **FCM 발송이 요청 스레드에서 동기 실행** → `@Async` 또는 별도 워커 (특히 다수 토큰 fan-out)
- **Solapi SMS 발송 동기 호출** → 사용자 응답 시간에 SMS API 지연 포함되면 안 됨
- **카카오 OAuth 토큰 교환** 동기 (불가피 — 유의해서 timeout 설정 확인)
- **외부 호출 timeout 미설정** → 무한 대기 위험. RestTemplate/WebClient 모두 connect/read timeout 명시
- **Spring Event 리스너가 동기**(`@EventListener` 만 있고 `@Async` 없음) → 발행자 스레드 차단. 비동기 필요 여부 판단 (`agent_docs/spring-event.md`)

### 캐시 (Redis)
- **자주 조회 + 거의 안 변하는 데이터**가 매 요청 DB 조회 (예: 공지사항 목록, 사용자 프로필) → Redis 캐시
- **Spring `@Cacheable` 미사용** : 캐시 가치 있는 메서드 식별
- **캐시 키 충돌** : `RedisKeys` 상수 사용 여부 (CLAUDE.md 규칙 3)
- **TTL 미설정** : 메모리 누수
- **캐시 무효화 누락** : 데이터 변경 후 캐시 evict 안 함 → 정합성 깨짐

### 콜렉션 / 메모리
- 큰 콜렉션 전체 메모리 적재 후 처리 → 스트림 / 페이지 처리
- `findAll()` 후 메모리에서 필터 → DB where로 이동
- ByteArray 응답 (이미지) 큰 사이즈 메모리 보관 → 스트리밍

### 스레드 풀 / 커넥션 풀
- `application.yaml` 의 HikariCP 설정 누락 / 기본값
  - `spring.datasource.hikari.maximum-pool-size`
  - `spring.datasource.hikari.connection-timeout`
- `@Async` 사용 시 `TaskExecutor` Bean 정의 없음 → 기본 SimpleAsyncTaskExecutor 무한 스레드 생성 위험
- WebSocket 메시지 처리 스레드 풀(`MessageBrokerConfigurer.configureClientInboundChannel`) 기본값 — 동시 접속 많으면 조정

### WebSocket / STOMP
- 토픽 fan-out: 한 이벤트가 다수 사용자에게 broadcast 시 스레드 점유 패턴
- 큰 페이로드 broadcast → 압축 / 페이로드 슬림화
- SockJS fallback 지연

### 알고리즘
- O(n²) 중첩 루프 (특히 콜렉션 큰 도메인 — `anomaly`, `ai` 표정 이력)
- `String` `+` 반복 누적 → `StringBuilder`
- 정규식 매번 컴파일 → `Pattern.compile()` 정적 상수

### 트랜잭션 길이
- `@Transactional` 메서드 안에 외부 HTTP 호출 → DB 커넥션 점유 시간 길어짐, 풀 고갈 위험. 트랜잭션 밖으로 분리
- 조회 메서드에 `readOnly = true` 누락 (db-improve 와 겹치지만 풀 점유 관점)

### 모니터링 / 측정
- p95, p99 측정 가능한 지표 (Micrometer / Actuator metrics) 노출 여부
- 응답 시간 로그 측정용 인터셉터 / `@Timed` 사용

## Non-goals
- SQL/JPA 쿼리 자체 최적화 → `db-improve`
- 보안 결함 → `security-scan`
- 가독성 → `refactor`
- 라이브러리 버전 → `dependency-check`

## 출력 포맷

### 1) 요약 표
| # | 위치 | 종류 | 예상 효과 | 심각도 | 위험도 |
|---|---|---|---|---|---|

종류: `blocking-io` / `cache-missing` / `pool-config` / `async-missing` / `algo` / `transaction-length` / `payload-size`

심각도:
- **Critical**: 운영 장애 가능 (스레드 풀 고갈, OOM, timeout 누락)
- **High**: 자주 호출되는 핫 패스에서 명확한 병목
- **Medium**: 비핫 패스 개선
- **Low**: 미세 튜닝

위험도(개선 시 부작용):
- **safe**: 동작 변화 없음 (캐시 추가 + 적절한 evict, timeout 추가)
- **needs-validation**: 비동기 전환 → 정합성·순서 보장 검토 필요
- **breaking**: 응답 형태/시점 변경

### 2) 항목별 상세
- **위치**: 파일:라인 + 호출 빈도 추정
- **현재 동작**: 동기/블로킹/풀스캔 등 1~2줄
- **개선안**: 코드 + 설정
- **예상 효과**: "응답 시간 X ms → Y ms (추정)" 또는 "스레드 점유 N% 감소"
- **부작용**: 정합성·순서·재시도 처리 등

### 3) 측정 권장
어떤 지표(p95, 처리량, 풀 사용률)로 효과 검증할지 1~2줄 가이드

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과
- 비동기/캐시 도입 시 PR 본문에 정합성 영향 명시
- 커밋: `perf: <도메인> <개선 요약>` (예: `perf: FCM 발송 비동기화`)
