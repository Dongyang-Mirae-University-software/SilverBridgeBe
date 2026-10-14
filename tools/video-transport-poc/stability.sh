#!/usr/bin/env bash
# 30분 안정 시험: H.264 송출 30분 + 재연결 5회. 종료 뒤 ffmpeg 프로세스 누수와 클립 생성을 확인한다.
# 사용(시험 서버에서): ./stability.sh [seconds=1800] [reconnects=5]
set -uo pipefail
cd "$(dirname "$0")"
SECS="${1:-1800}"; RECON="${2:-5}"
DC="docker compose --env-file .env"
LOG=harness/work/stability.log
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

count_ffmpeg() { # AI 컨테이너 안의 ffmpeg 프로세스 수(좀비 포함)
  $DC exec -T ai python - <<'PY'
import os
n = 0
for pid in filter(str.isdigit, os.listdir("/proc")):
    try:
        cmd = open(f"/proc/{pid}/cmdline", "rb").read().replace(b"\0", b" ").decode(errors="ignore")
        state = open(f"/proc/{pid}/stat").read().split(") ")[-1].split()[0]
    except Exception:
        continue
    if "ffmpeg" in cmd or "ffmpeg" in open(f"/proc/{pid}/comm").read():
        n += 1
print(n)
PY
}

say "시작 $(date '+%F %T') ffmpeg_before=$(count_ffmpeg)"
BE_MEM_BEFORE="$(docker stats --no-stream --format '{{.MemUsage}}' wspoc-be-1)"
./measure.sh "stab_h264_${SECS}s" $((SECS + 120)) > harness/work/measure_stab.txt &
MPID=$!
sleep 2
$DC exec -T harness python poc_client.py run --mode h264 --scene busy --seconds "$SECS" --reconnects "$RECON" \
  --name "stab_h264_${SECS}s" > harness/work/run_stab.log 2>&1
say "송출 종료 rc=$?"
sleep 8
say "ffmpeg_after=$(count_ffmpeg)  (0이어야 한다)"
say "BE 메모리 전=${BE_MEM_BEFORE} 후=$(docker stats --no-stream --format '{{.MemUsage}}' wspoc-be-1)"
# 재연결 5회 뒤에도 클립이 만들어지는가: 짧게 다시 송출해 버퍼를 채운 뒤 클립을 요청한다
$DC exec -T harness python - <<'PY' | tee -a "$LOG"
import json, time, asyncio
import requests
import poc_client as c
import poc_fallback as f
state = c.load_state()
result = asyncio.run(f._hold(12))
sid = state["sessionId"]
# 감지 직후 시점을 가정한 클립(앞 3초 + 뒤 0.5초)
r = requests.post(f"{c.AI}/api/v1/live-streams/{sid}/clips", headers=c.ai_headers(), timeout=30,
                  json={"preSeconds": 3, "postSeconds": 0.5})
print("clip status=%s type=%s bytes=%d frames=%s size=%sx%s" % (
    r.status_code, r.headers.get("content-type"), len(r.content), r.headers.get("X-Clip-Frames"),
    r.headers.get("X-Clip-Width"), r.headers.get("X-Clip-Height")))
print("post-hold:", json.dumps(result, ensure_ascii=False))
PY
say STABILITY_DONE
