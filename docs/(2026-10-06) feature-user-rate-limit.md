# 로그인 사용자 기준 요청 횟수 제한 (2026-10-06)

## 경위
- 10/5 부하 측정에서 로그인한 토큰 하나(계정 하나)로 읽기 API를 초당 약 1,000건 보낼 수 있었다.
- IP 기준 제한(`RateLimitService`)은 로그인·인증·문의 작성 등 일부 경로에만 있고, 로그인한 사용자 기준 제한은 없었다.

## 동작
- `UserRateLimitFilter`가 `JwtAuthenticationFilter` 바로 뒤에서 인증된 사용자 ID 기준으로 센다.
- **기준값: 사용자당 1분 고정 윈도우 600회** (`app.user-rate-limit.max-per-minute`, 환경변수 `USER_RATE_LIMIT_PER_MINUTE`).
  - 화면 새로고침 한 번에 수십 건, 실시간 알림 뒤 재조회, 여러 탭을 써도 닿지 않는 값이다.
  - 초당 한도는 두지 않았다. 새로고침 순간의 동시 호출이 막히면 정상 사용이 깨지기 때문이다.
  - 부하 측정처럼 초당 1,000건을 보내면 첫 600건 뒤부터 그 분이 끝날 때까지 429다.
- 킬 스위치: `app.user-rate-limit.enabled` (`USER_RATE_LIMIT_ENABLED=false`)
- Redis 키: `rate:user-api:{userId}` (60초 TTL, 원자 증가 `RedisCounter.incrementWithTtl`).

## 제외 경로 (검사 자체를 하지 않는다)
| 경로 | 이유 |
|---|---|
| `/api/ward/sos`, `/api/ward/sos/**`, `/api/ward/sos-setting`, `/api/guardian/sos/**` 등 구간 이름이 `sos`로 시작하는 `/api/{역할}/sos*` | SOS는 어떤 제한에도 막히면 안 되는 필수 경로 |
| `/api/auth/**` | 로그인·갱신·로그아웃은 기존 IP 기준 제한을 그대로 둔다 |
| `/ws/**` | WebSocket 핸드셰이크 |
| `OPTIONS` 요청, 미인증 요청 | 사용자 ID 없음 / preflight |

## 초과 응답
기존 `GlobalExceptionHandler`와 같은 형식: HTTP 429, `code: TOO_MANY_REQUESTS`, `Retry-After` 헤더, `data.retryAfterSeconds`.
필터 안이라 핸들러를 못 타서 직접 쓴다.

## Redis 장애
`RateLimitService`의 기존 정책(fail-open, `[RATE-LIMIT-REDIS-DOWN]` WARN)을 그대로 따른다. 그 밖의 예기치 않은 오류도 통과시킨다. SOS는 애초에 검사하지 않는다.

## 이번에 하지 않은 것
- 경로별(읽기/쓰기, 무거운 API) 차등 한도: 기준을 잡을 사용량 데이터가 없다.
- nginx 쪽 `limit_req`: IP 기준이라 사용자별 제한을 대체하지 못한다. 앞단 방어를 원하면 별도 인프라 작업이다.
- 카메라 영상·스냅샷·티켓은 기존 전용 제한(보호자 ID 기준 분·시간)이 이미 있고 이 제한과 함께 적용된다.

## 테스트
`UserRateLimitFilterTest`(제외 경로·429 형식·킬 스위치·fail-open), `RateLimitServiceTest`(상한 경계·Redis 장애), `SecurityConfigAccessDeniedTest`(보안 체인 연결).
