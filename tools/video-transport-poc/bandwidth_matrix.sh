#!/usr/bin/env bash
# 대역폭 제약 비교: 같은 장면을 업링크 한도(Mbps)별로 JPEG·H.264 로 보내 손실·지연 누적·감지 영향을 본다.
# 사용(gosky, /home/apps/ws-poc-gosky): ./bandwidth_matrix.sh [seconds=60]
set -uo pipefail
cd "$(dirname "$0")"
S="${1:-60}"
H=./h.sh
LOG=harness/work/bandwidth.log
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }
run() { # mode scene cap
  local mode="$1" scene="$2" cap="$3" label="$1"; [ "$1" = h264direct ] && label=h264
  $H poc_client.py run --mode "$mode" --scene "$scene" --seconds "$S" --cap-mbps "$cap" --direct \
    --session-id "ward_pocbw_${scene}" --name "bw_${label}_${scene}_cap${cap}" > /dev/null 2>&1
  say "done bw_${label}_${scene}_cap${cap}"
}
for cap in 8 5 3 2; do for mode in jpeg h264direct; do run "$mode" fire "$cap"; done; done
for cap in 8 5; do for mode in jpeg h264direct; do run "$mode" busy "$cap"; done; done
say BANDWIDTH_DONE
