# Redis 퇴출 정책 noeviction 전환 (QA AUTH-G30, 2026-10-02)

## 변경
`docker-compose.dev.yml` redis: `--maxmemory-policy allkeys-lru` -> `noeviction` (`--maxmemory 256mb` 유지).

## 이유
allkeys-lru 는 메모리가 차면 TTL이 남은 보안 키도 임의로 퇴출한다. 로그인 잠금(`login:lock:`)이 사라지면 잠금이 풀리고, 로그아웃 블랙리스트(`logout:`)·비밀번호/탈퇴/정지 토큰 무효화(`PASSWORD_INVALIDATE`)가 사라지면 끊은 토큰이 되살아난다. 인증코드·nonce 도 마찬가지다.

## 사용처 조사 (src/main grep)
Redis 는 `StringRedisTemplate` 만 쓴다(`@Cacheable`/`CacheManager`/Spring Session 없음). 모든 쓰기에 TTL이 있다.

| 키 | TTL |
|---|---|
| sms:verify / password:*:verify (인증코드) | CODE_TTL_MINUTES |
| sms:verified (nonce) | VERIFIED_TTL_MINUTES |
| kakao:pending | KAKAO_PENDING_TTL |
| login:lock | lockTtlMinutes |
| logout:<hash> | 토큰 잔여 시간 |
| PASSWORD_INVALIDATE | access token 만료시간 |
| presence (WebSocket) | 12시간 |
| 카운터(`RedisCounter` Lua: INCR 후 최초 1회 EXPIRE) - login:fail, sms:attempt/sendcount, password:*:attempt/sendcount, RateLimit | 윈도우 TTL |
| 쿨다운 setIfAbsent (SOS·이상감지 이력/알림) | 쿨다운 길이 |

결과: **TTL 없이 무한히 쌓이는 키는 발견되지 않았다.** 키 수는 활성 사용자·요청량에 비례하는 TTL 키뿐이라 256MB 에 도달할 가능성은 낮다. (참고: `RedisCounter`는 INCR 과 EXPIRE 가 한 Lua 스크립트라 TTL 누락 경로 없음.)

## 위험과 감시
noeviction 에서 maxmemory 도달 시 쓰기 명령이 OOM 오류가 된다(읽기는 정상). 로그인 실패 카운트·로그아웃 등록·인증코드 발급 같은 쓰기가 실패할 수 있으므로 메모리를 감시한다.
```
docker exec dmu-dev-redis redis-cli INFO memory | grep -E "used_memory_human|maxmemory_human"
docker exec dmu-dev-redis redis-cli INFO stats | grep -E "evicted_keys|rejected"
docker exec dmu-dev-redis redis-cli DBSIZE
```
`used_memory` 가 maxmemory 의 70~80% 를 넘으면 원인 키(`--scan --pattern`)를 확인한다. 포트는 127.0.0.1 전용(SSH 터널 사용).

## 배포 (수동 필요)
CD 는 api 컨테이너만 재기동하므로 이 변경은 자동 반영되지 않는다. vkcs-linux·gosky 각각에서:
```
docker compose -f docker-compose.dev.yml up -d redis
```
(command 변경으로 redis 컨테이너가 재생성된다.) 데이터는 `./.data/redis` bind mount 에 AOF(`appendonly yes`, everysec)로 저장돼 있어 재생성 후 AOF 를 다시 읽어 **키는 유지**된다. 단 최대 약 1초분 쓰기와 재시작 중 수 초간 Redis 불가(그 사이 로그인·인증 오류 가능, health check 로 api 대기)가 있다. 즉시 반영만 필요하면 `redis-cli CONFIG SET maxmemory-policy noeviction` 도 가능하나 재생성 시 compose 값이 우선이므로 compose 를 정본으로 한다.
