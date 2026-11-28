# gosky 메모리 부족 - dev API 멈춤 대응 (2026-11-28, 전체 점검 H-2)

> 근거 점검: `(2026-11-28) audit-full-2026-11-end.md` H-2. 조치 PR: BE #529 (`d4ba35ca`).

## 1. 측정 결과 - 원인 가설 수정

점검 문서는 "직렬 GC가 스왑으로 밀려난 힙을 다시 읽느라 멈춤"으로 추정했다. 다시 재 보니 **힙 스왑이 아니다**.

| 항목 (변경 전, 11-28 11:46) | 값 |
|---|---|
| 호스트 | 31.8GB, free 962MB / available 8.8GB, **스왑 8191/8191MB 가득**, swappiness 60, 커널 5.15(MGLRU 없음), cgroup v2(`memory_recursiveprot`), Docker 29.5.1, 디스크 84% |
| 호스트 PSI | memory full 누적 45,461초(가동 56일) / io some avg300 4.0% |
| dev API 48시간 (11-26 17:13 ~ 11-28 11:42) | Hikari starvation **25회**(45.7초~**3분 3초**), `Connection is not available` 58, `Failed to validate connection` 47 |
| 스케줄러 발송 실패 ERROR | 10 (ANOMALY-REVIEW 4 · MEDICATION-REMINDER 3 · MEDICATION-MISSED 2 · ANOMALY-CLIP 1) |
| AI WS 경고 | 52 (DeploymentException 32 · SSLEngineResult 9 · code=null 4 · IOException 3) |
| dev API cgroup | anon 779M · **file 13M** · swap 3M · **refault_anon 1** · **refault_file 24,857,206** · pgmajfault 1,006,902 · 메모리 PSI full 1,466초(≈ 멈춤 합계) / io full 297초 |
| FE dev / AI / prod API | 1.95GB(anon 1.89G) / 2.4GB 제한 없음 / 0.72GB - 셋 다 refault_file 1,600만~4,400만 (같은 증상) |

- **메커니즘**: 호스트 스왑이 가득 차 익명 메모리를 내보낼 수 없으니 커널 회수가 **파일 페이지에만** 몰린다. 그래서 JVM의 libjvm·JDK 모듈(`lib/modules`)·jar 페이지가 계속 쫓겨났다가 다시 읽힌다. GC·safepoint 중에 이 페이지를 다시 읽으면 프로세스 전체가 멈춘다. 컨테이너 메모리 PSI full 합계가 멈춤 합계와 맞는다.
- **스왑 금지(memswap_limit)는 효과가 거의 없다**(익명 refault ≈ 0, 스왑 3MB). 채택하지 않았다.
- **`mem_reservation`(memory.low)로 보호하는 방법은 먹지 않는다**: 커널 5.15에서 컨테이너 scope의 유효 보호는 부모 `system.slice`의 보호를 넘지 못하는데, `system.slice`의 memory.low가 0이다. 이 값을 바꾸는 것은 호스트 systemd 설정이라 관리자 영역이다(아래 4절).
- **페이지 캐시를 휘젓는 후보**: gitlab 컨테이너 안 `tail --follow=name --retry`의 read_bytes 누적이 약 70TB(56일, 평균 약 14MB/s)다.

## 2. 바꾼 설정 (dev 전용)

`docker-compose.dev.yml` api에 다음을 넣었다. Dockerfile `JAVA_OPTS`에 더해지는 옵션이고, Dockerfile·prod는 바꾸지 않았다. gosky·vkcs `.env.dev`에 같은 변수가 없음을 확인했다.

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-XX:+PerfDisableSharedMem -Xlog:gc:stdout:time"
```

- `PerfDisableSharedMem`: `/tmp/hsperfdata_*` mmap 파일 쓰기를 없앤다. 디스크 대기가 잦은 호스트에서 safepoint 멈춤을 늘리는 알려진 요인이다. 이 옵션을 켜면 jstat은 못 쓰고 jcmd는 쓸 수 있다.
- GC 로그: 다음 멈춤이 GC 안에서(긴 `Pause`) 일어나는지 밖에서 일어나는지 가르는 근거로 쓴다.
- 검토하고 뺀 옵션: `-Xms=-Xmx`·`AlwaysPreTouch`(익명 메모리를 미리 잡아 호스트 압박만 늘리고, 원인인 파일 페이지와는 무관하다) / GC 교체(SerialGC는 1.5G 컨테이너에 맞다. 멈춤은 GC 알고리즘이 아니라 페이지 회수에서 온다) / FE dev 빌드본 전환(약 1.7GB 절감이지만 배포 절차가 바뀌어 이번에는 보류 - 사용자 결정).

**적용 확인 (11-28 11:55 재생성)**: gosky·vkcs 두 서버 모두 `d4ba35ca`. `printenv JAVA_TOOL_OPTIONS` 일치, `/tmp/hsperfdata_root` 비어 있음, 기동 로그 `Picked up JAVA_TOOL_OPTIONS` 1회, `AiLiveStreamSubscriber` 연결됨, health 200, ERROR 0. vkcs CD success.

**되돌리기**: compose에서 `environment.JAVA_TOOL_OPTIONS` 두 줄을 지우고 PR을 올린 뒤 각 서버에서 `up -d api`.

## 3. 전후 비교

| 항목 | 변경 전 48시간 | 변경 후 24시간 (11-29 12:00 이후 측정 예정) |
|---|---|---|
| Hikari starvation | 25회 (24시간 환산 약 12.5) · 최대 3m3s | |
| Connection is not available / validate | 58 / 47 | |
| 스케줄러 발송 실패 ERROR | 10 | |
| AI WS 경고 (DeploymentException) | 52 (32) | |
| 컨테이너 메모리 PSI full | 1,466초 / 2일 | |
| 멈춤 시각 GC 로그 | 없음 | (긴 Pause가 있으면 GC 안에서의 멈춤) |

측정 명령: `docker logs --since 24h dmu-dev-api` 에서 위 문자열의 건수, cgroup `memory.pressure`·`memory.stat`.

## 4. 서버 관리자 요청문 (초안 - 보내지 않음)

> gosky 공용 서버의 메모리 상태 때문에 저희 컨테이너(dmu-dev-api 등)가 하루 십수 번 1~3분씩 멈추고 있습니다. 확인 부탁드립니다.
> 1. **스왑 8GB가 가득 찼습니다**(11-28 기준 8191/8191MB). 스왑을 많이 쓰는 쪽은 sqlservr(약 1.5GB)·immich(약 1.6GB)·mysqld·gitlab(bundle) 순입니다. 스왑을 늘리거나, 한가한 시간에 `swapoff -a && swapon -a`로 비워 주실 수 있을지 여쭙니다(재수용할 메모리 여유는 available 약 8.8GB).
> 2. **gitlab 컨테이너가 약 6.3GB**로 가장 큽니다. puma·sidekiq 워커 수를 줄이거나 메모리 제한을 거는 것을 검토해 주실 수 있을까요?
> 3. gitlab 컨테이너 안의 로그 `tail --follow=name --retry` 프로세스가 가동 56일간 디스크에서 약 70TB(평균 약 14MB/s)를 읽고 있습니다. 페이지 캐시를 계속 밀어내는 원인으로 보입니다.
> 4. (선택) `systemctl set-property system.slice MemoryLow=...`처럼 컨테이너가 속한 slice에 보호값을 주면, 컨테이너별 `mem_reservation`이 실제로 먹게 됩니다(지금은 0이라 무효).

## 5. 남은 위험

- AI 서버(`silverbridge-ai-server`)도 같은 증상이다(제한 없음, refault_file 2,400만). AI WS가 끊기는 원인이 AI 쪽 멈춤일 수도 있다 - 재시작이 필요해 이번 범위 밖이다.
- 긴급 알림(SOS·이상감지)은 1회만 시도한다 / 어르신 SOS 화면은 10초 뒤 "전송 실패"를 보인다 / BE 끊김 감지 180초 > AI 약 40초 - 점검 제안으로 따로 다룬다.
