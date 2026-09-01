# 피보호자에게 실시간 알림이 안 오던 문제 - 원인과 조치

> 2026-09-01 · 제보: 프론트(이윤아) "보호자는 소켓 알림이 다 오는데 피보호자는 아무것도 안 나온다"
> 조사 대상: gosky 서버 `dmu-dev-api` (api.devdmu.gosky.kr → 127.0.0.1:6511)

---

## 한 줄 요약

**WebSocket이 아예 연결된 적이 없었다.** 프론트 로컬 dev 서버 오리진(`http://localhost:6510`)이 서버의 허용 오리진 목록에 없어서 `/ws` 핸드셰이크가 **403으로 거부**되고 있었다. 보호자·피보호자 **둘 다** 막혀 있었고, 보호자 화면만 멀쩡해 보인 것은 소켓이 아니라 **화면이 API를 반복 조회(폴링)** 하고 있었기 때문이다.

즉 **프론트 버그도 백엔드 버그도 아니고, 허용 오리진 설정 누락**이다.

---

## 1. 증상

| | 보이는 현상 | 실제 |
|---|---|---|
| 보호자 | 실시간 알림이 잘 나옴 | 소켓은 죽어 있음. 화면이 API를 계속 다시 부르고 있었을 뿐 |
| 피보호자 | 아무 알림도 안 나옴 | 소켓이 죽어 있고, 폴링도 없어서 아무 일도 안 일어남 |

---

## 2. 진짜 원인

### 왜 403이 났나

백엔드는 `/ws` 접속을 받을 때 **"어느 웹페이지에서 접속했는지"(Origin)** 를 확인하고, 목록에 없으면 거부한다.

- 허용 목록 (`application.yaml`의 `app.cors.allowed-origins` 기본값):
  `https://devdmu.gosky.kr`, `http://localhost:3000`, `http://localhost:5173`, `http://localhost:8080`
- 프론트가 실제로 띄운 주소: **`http://localhost:6510`** ← 목록에 없음

서버 `.env.dev`에 `APP_CORS_ORIGINS`가 설정돼 있지 않아 위 기본값이 그대로 쓰이고 있었다.

### 왜 하필 소켓만 막혔나 (API는 멀쩡한데)

```
[브라우저]  --- /api/... --->  [프론트 dev 서버 localhost:6510]  --- 프록시 --->  [백엔드]
                                            ↑ 서버끼리의 통신이라 Origin 검사 대상이 아님 → 정상 200

[브라우저]  --- /ws (직접 연결, Origin: http://localhost:6510) ------------------>  [백엔드]
                                                                                     ↑ Origin 검사 → 403 거부
```

API 요청은 프론트 dev 서버를 거쳐 나가서 Origin 검사를 피해 가지만, WebSocket은 브라우저가 백엔드에 **직접** 붙기 때문에 Origin이 그대로 실려 간다. 그래서 **"API는 다 되는데 소켓만 안 되는"** 모양이 됐다.

### 왜 아무도 눈치채지 못했나

거부 로그가 **서버 로그에 안 남는다.** Origin 거부는 스프링 내부에서 DEBUG 레벨로만 기록돼서, 애플리케이션 로그만 봐서는 "조용히 아무 일도 안 일어난 것"처럼 보인다. nginx 접근 로그를 봐야 403이 드러난다.

---

## 3. 어떻게 확인했나 (근거)

### ① nginx 접근 로그 - 오늘 `/ws` 요청 1373건 중 성공은 0건

`/ws?token=...`의 JWT를 디코드해 계정별로 집계한 결과:

| 계정 | 역할 | 요청 수 | 결과 |
|---|---|---|---|
| `aB3x9A` | GUARDIAN | 1058건 | **전부 403** |
| `wDLVW1` | WARD | 294건 | **전부 403** |
| `gosky` | GUARDIAN | 3건 | 101(성공) 2건 - 다른 오리진에서 접속 |

보호자도 6초마다 재접속을 반복하며 계속 실패하고 있었다.

### ② 프론트 오리진 확인 - nginx 로그의 Referer

```
"GET /api/guardian/connection/requests" 200 ... "http://localhost:6510/guardian"
"GET /api/ward/connection/active"       200 ... "http://localhost:6510/ward/sos"
```

보호자·피보호자 화면 모두 `localhost:6510`에서 떠 있었다.

### ③ 직접 재현 - 서버에서 오리진만 바꿔 핸드셰이크 시도

```bash
curl -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: http://localhost:6510" \
  "http://127.0.0.1:6511/ws?token=x"
```

| Origin | 결과 |
|---|---|
| `http://localhost:6510` | **403** (오리진 거부) |
| `http://localhost:3000` | 200 (오리진 통과 - 그 뒤 토큰 검증에서 걸림) |

> 참고: 토큰이 틀렸을 때는 403이 아니라 **200 + 빈 응답**이 된다(핸드셰이크 인터셉터가 false를 반환하면 상태코드를 따로 세팅하지 않기 때문). **403이면 토큰 문제가 아니라 오리진 문제**라고 구분하면 된다.

### ④ "보호자는 정상"의 정체 - 폴링

오늘 하루 요청 수:

| 엔드포인트 | 호출 수 |
|---|---|
| `GET /api/guardian/connection/select` | 100 |
| `GET /api/guardian/connection/requests` | 91 |
| `GET /api/ward/connection/active` | 31 |

보호자 화면은 같은 API를 계속 다시 부르고 있어서 실시간처럼 보였고, 피보호자 화면은 그런 재조회가 없어 화면이 멈춰 있었다.

---

## 4. 같이 발견된 문제 - 연결돼도 60초 만에 끊김

오리진을 풀어도 그대로 남는 별개 문제였다.

- 성공했던 소켓 세션들의 수명이 **정확히 60초**였다 (예: 06:00:23 연결 → 06:01:23 해제)
- 브로커 통계: 누적 58세션, **정상 종료(DISCONNECT) 0건, 전송 오류 58건** - 클라이언트가 스스로 끊은 적이 한 번도 없음
- 원인: nginx `proxy_read_timeout` 기본값 60초. 백엔드가 60초간 아무것도 안 보내면 nginx가 연결을 끊는다.

**조치 완료**: `api.devdmu.gosky.kr` nginx 설정에 타임아웃 1200초가 추가됐다(2026-09-01 17:12).

```nginx
proxy_connect_timeout 1200s;
proxy_send_timeout    1200s;
proxy_read_timeout    1200s;
```

> ⚠️ 설정 파일만 고쳐서는 적용되지 않는다. `sudo nginx -t && sudo systemctl reload nginx` 로 **리로드까지** 해야 한다.

---

## 5. 조치

### (1) 코드 - 허용 오리진 기본값에 `localhost:6510` 추가 ✅

`src/main/resources/application.yaml`

```yaml
app:
  cors:
    allowed-origins: ${APP_CORS_ORIGINS:https://devdmu.gosky.kr,http://localhost:6510,http://localhost:3000,...}
```

앞으로 새로 배포되는 환경은 별도 설정 없이 동작한다. 같은 목록이 HTTP CORS와 WebSocket Origin 검증에 **함께** 쓰인다는 주석도 같이 남겼다.

### (2) gosky 서버 즉시 적용 (둘 중 하나)

gosky는 수동 배포 서버이고 현재 체크아웃이 8/31(#235)에 멈춰 있다. **지금 재배포하면 V44·V45 마이그레이션까지 함께 나가므로**, 이 버그만 급히 풀려면 env를 얹는 쪽이 안전하다.

**A. env만 추가 (재배포 없음, 권장)**

```bash
ssh gosky
cd /home/apps/SilverBridgeSky/SilverBridgeBe
sudo cp -a .env.dev .env.dev.bak-cors-20260901          # 백업 (이미 만들어 둠)
echo 'APP_CORS_ORIGINS=https://devdmu.gosky.kr,http://localhost:6510,http://localhost:3000,http://localhost:5173,http://localhost:8080' | sudo tee -a .env.dev
docker compose -f docker-compose.dev.yml up -d api      # restart 아님 - env는 재생성해야 반영됨
```

**B. dev를 정식 배포** (V44·V45 마이그레이션 포함, 별도 판단 필요)

```bash
cd /home/apps/SilverBridgeSky/SilverBridgeBe
sudo git pull origin dev
docker compose -f docker-compose.dev.yml up -d --build api
```

### (3) nginx 리로드 (타임아웃 적용)

```bash
ssh gosky
sudo nginx -t && sudo systemctl reload nginx
```

### 적용 후 확인 방법

```bash
# 403이 아니라 200이 나오면 오리진 통과
ssh gosky 'curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: http://localhost:6510" "http://127.0.0.1:6511/ws?token=x"'

# 실제 접속되면 이 로그가 뜬다
ssh gosky 'docker logs dmu-dev-api --since 5m | grep "WebSocket 연결"'
```

---

## 6. 오리진을 풀어도 피보호자에게 안 오는 알림이 있다 (설계상)

프론트가 아래 4개 외의 이벤트를 기다리고 있다면, 오리진을 고쳐도 안 나온다.

**피보호자에게 실제로 가는 WebSocket 이벤트 - 전부**

| 이벤트명 | 언제 |
|---|---|
| `connection-request` | 보호자가 연결을 요청했을 때 |
| `connection-cancelled` | 연결이 해제됐을 때 |
| `medication-taken` | 복약 체크/해제가 반영됐을 때 |
| `anomaly-detected` | 이상감지(화재 등)가 발생했을 때 |

**피보호자에게 소켓으로 가지 않는 것 (의도된 설계)**

- `sos-triggered`, `connection-accepted`, `connection-refused`, `medication-stopped` - **보호자 전용**
- 복약 알림(`MEDICATION_REMINDER`) - **FCM·문자 전용**, 소켓으로 안 보냄
- `anomaly-detected` - AI가 아직 `danger=true`를 올리지 않는 단계라 **현재 발생 건수 0**

구독 주소는 `/topic/{userId}/{이벤트명}` 이고, `{userId}`가 본인 것이 아니면 구독이 거부된다.

---

## 7. 재발 방지

- **오리진 목록은 HTTP CORS와 WebSocket이 공유한다.** 프론트 dev 포트가 바뀌면 이 목록도 같이 봐야 한다. API가 잘 되는 것은 소켓이 된다는 근거가 못 된다.
- **소켓이 안 될 때 첫 확인처는 nginx 접근 로그다.** 애플리케이션 로그에는 Origin 거부가 안 남는다.
  ```bash
  ssh gosky 'grep " /ws" /var/log/nginx/dmu_access.log | awk "{print \$9}" | sort | uniq -c'
  ```
  `403`이면 오리진, `200`이면 토큰, `101`이면 정상 접속이다.
- **"실시간으로 보인다"가 소켓이 붙었다는 뜻은 아니다.** 이번처럼 폴링이 증상을 가릴 수 있다.
- (선택) Origin 거부를 WARN으로 남기면 다음부터는 애플리케이션 로그만으로 진단된다. 로그 노이즈와 맞바꾸는 부분이라 이번에는 넣지 않았다.

---

## 관련 파일

- `src/main/resources/application.yaml` - `app.cors.allowed-origins`
- `src/main/java/kr/silverbridge/main/global/config/WebSocketConfig.java` - `/ws` 엔드포인트, 오리진 검증
- `src/main/java/kr/silverbridge/main/global/websocket/JwtHandshakeInterceptor.java` - 토큰 검증 (오리진 검사보다 **먼저** 실행)
- `src/main/java/kr/silverbridge/main/global/websocket/StompSubscriptionAuthorizationInterceptor.java` - 구독 권한 검증
- 서버: `/etc/nginx/sites-enabled/api.devdmu.gosky.kr`, `/home/apps/SilverBridgeSky/SilverBridgeBe/.env.dev`
