---
name: log-review
description: 로그 품질 점검이 필요할 때 사용. "로그 리뷰", "로그 품질", "민감정보 로그", "로그 레벨", "logger" 같은 요청에서 발동. 민감정보 누출·로그 레벨 적절성·구조화·노이즈 제거 관점에서 본다. 보안 결함 자체는 security-scan, 성능 영향은 performance-check 영역.
---

## 목적
로깅이 (1) 민감정보를 노출하지 않고 (2) 적절한 레벨로 (3) 운영 디버깅에 도움이 되며 (4) 과도하지 않은지 점검한다.

## 입력/스코프
- 기본: 현재 브랜치 변경분 + `*Service`, `*Controller`, `*Listener`, `*Filter`, `*Interceptor`
- 사용자 지정 시 특정 도메인
- 분석 대상: `log.*()`, `logger.*()`, `System.out.*`, `e.printStackTrace()` 호출
- 설정: `logback-spring.xml`, `application*.yaml` 의 `logging.*`

## 절차
1. **로그 호출 전수 검색** — `log\.(trace|debug|info|warn|error)`, `System\.(out|err)`, `printStackTrace`
2. **민감정보 누출 검사** — 토큰·비밀번호·전화번호·주민번호·이메일·주소 패턴 매칭
3. **레벨 적절성 검토** — 정상 흐름이 ERROR/WARN 인지, 운영에 무용한 INFO 가 폭증하는지
4. **포맷·구조화 점검** — 메시지 + 컨텍스트(userId, requestId), parameterized logging 사용 여부
5. **노이즈 측정** — 동일 메시지 반복, 핫 패스에서 INFO 다발
6. **수정안 적용** — 마스킹/레벨 조정/제거
7. **검증** — `./gradlew build -x test --no-daemon`
8. **커밋** — `chore: <도메인> 로그 정리` 또는 `fix: <도메인> 민감정보 로그 제거`

## 검출 기준 (이 프로젝트 특화)

### 민감정보 누출 (가장 우선)
- **JWT / Refresh token**: `log.info("token={}", token)` 같은 호출 → 즉시 제거 또는 hash/마지막 N자리만
- **비밀번호 (해시 전후 모두)**: 로그 절대 금지
- **전화번호** (010-XXXX-XXXX): 마스킹 (`010-****-1234`)
- **이메일**: 부분 마스킹 (`s***@gmail.com`) — 단순 운영 디버그 용도면 운영에서 INFO 이상 출력 금지
- **주민번호 / 생년월일** : 절대 금지
- **userId (6자리)** : 식별자이므로 운영 디버그 목적이면 OK, 단 다른 PII와 함께 출력 시 주의
- **카카오 OAuth code/state** : 인증 흐름 로그에 그대로 찍히는지 확인
- **FCM device token** : 전체 출력 금지, 마지막 N자리만
- **Solapi API key** : 절대 로그 금지
- **DB 접속 정보** : Hikari config 로그 확인
- **Redis 값** : 비밀번호 reset 토큰, refresh token 등 그대로 로그하지 말 것
- **요청 본문 전체 덤프** : 회원가입·비밀번호 변경 endpoint 의 request body 전체 로그 금지

### 로그 레벨 적절성
- **ERROR**: 시스템 장애·예외 처리 실패·외부 시스템 에러. 정상 비즈니스 분기(잘못된 비밀번호 입력 등)는 WARN 이하
- **WARN**: 정상 동작이지만 비정상 상황 (Rate Limit 초과, 인증 실패, 잠재적 문제)
- **INFO**: 운영자가 알아야 하는 비즈니스 이벤트 (서비스 시작/종료, 중요 상태 전이) — 핫 패스에서 매 요청 INFO 금지
- **DEBUG**: 개발 디버깅용, 운영 기본 비활성
- **TRACE**: 매우 상세, 거의 사용 안 함

자주 보이는 안티패턴:
- 정상 응답마다 INFO 로그 → 응답 시간/볼륨 점검 후 DEBUG 또는 제거
- catch 블록에서 `log.error("...", e)` 후 다시 throw → 스택트레이스 두 번 찍힘
- 비즈니스 예외(`CustomException`) 까지 ERROR 로 처리 → 사용자 입력 오류는 WARN
- `e.printStackTrace()` 사용 → SLF4J `log.error(msg, e)` 로 교체

### 구조화·포맷
- **String concatenation** : `log.info("user " + userId + " logged in")` → `log.info("user {} logged in", userId)` (parameterized — 디스에이블 시 cost 0)
- **컨텍스트 누락** : 어떤 사용자/요청인지 식별 불가 → MDC 또는 인자 추가 (userId, requestId)
- **MDC**: `MDC.put("userId", ...)` 후 `MDC.clear()` 누락 → 스레드 재사용 시 잘못된 값
- **Correlation ID** : 분산 추적 가능하도록 요청 진입 시 `requestId` MDC 주입 권장
- **타임스탬프 포맷 / TZ** : `logback-spring.xml` 에서 일관 (KST/UTC 명시)

### 노이즈 / 비용
- **동일 메시지 반복** : 외부 호출 실패 시 재시도마다 ERROR → 한 번 모아서 출력
- **핫 패스 INFO 다발** : WebSocket heartbeat, ping/pong 같은 곳
- **Logger 인스턴스** : 클래스마다 `private static final Logger log = ...` 또는 Lombok `@Slf4j` 일관
- **logback async appender** 검토 — 동기 logger 가 핫 패스에서 disk I/O 차단 유발

### 예외 처리와의 관계
- `try { ... } catch (Exception e) { log.error("실패"); }` → 스택트레이스 누락. `log.error("실패: {}", message, e)` 형태로 throwable 마지막 인자
- 비즈니스 예외 (`CustomException`)는 글로벌 핸들러에서 일괄 로깅, Service 안에서 중복 ERROR 로그 금지

### 환경별 설정
- **운영(prod)**: 기본 `INFO`, 패키지별 필요시 조정. DEBUG/TRACE 활성 금지
- **개발(dev)**: `DEBUG` 허용, 단 외부 라이브러리(`org.hibernate.SQL` 등) 무분별 활성 시 노이즈
- `logging.level.*` 가 코드/yaml 어디에 정의되어 있는지 일관성 점검

## Non-goals
- 로그 자체로 인한 보안 결함 (인증 우회 우회 시나리오) → `security-scan`
- 로그 디스크 I/O 가 응답 시간 좌우하는 케이스 → `performance-check`
- 메서드명·변수명 → `refactor`

## 출력 포맷

### 1) 요약 표
| # | 파일:라인 | 종류 | 심각도 |
|---|---|---|---|

종류: `pii-leak` / `level-wrong` / `non-parameterized` / `noise` / `missing-context` / `mdc-leak` / `printStackTrace` / `duplicate-error`

심각도:
- **Critical**: PII/시크릿/토큰 평문 노출 (즉시 패치, 기존 로그 백엔드 정리도 고려)
- **High**: 빈번한 핫 패스에서 PII 노출 가능, 또는 ERROR 폭증으로 알람 무력화
- **Medium**: 레벨 부적절, 컨텍스트 누락
- **Low**: 가독성·일관성 (parameterized logging, logger 선언 통일)

### 2) 항목별 상세
- **위치**: 파일:라인
- **현재 코드**: 발췌
- **문제**: 어떤 정보가 어떻게 노출/오염되는지
- **수정안**: 코드 + 마스킹 유틸 사용 여부
- **회귀 영향**: 기존에 이 로그를 모니터링/알람에 쓰고 있는지 사용자 확인 필요한 케이스 표시

### 3) 운영 정리 권고
- 이미 디스크/외부 로그 백엔드에 적재된 민감정보 처리 가이드 (보존 정책에 따라 삭제 또는 마스킹 작업 별도 필요)

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과
- Critical(PII 노출)은 별도 commit, PR 본문에 영향 범위·기존 로그 정리 필요 여부 명시
- 커밋: `fix: <도메인> 민감정보 로그 제거` / `chore: <도메인> 로그 레벨 정리`
