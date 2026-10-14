#!/usr/bin/env bash
# 비교 시험 매트릭스: 같은 가상 장면(busy·calm)을 JPEG 경로·H.264 경로로 각 120초 송출하며 BE·AI CPU 를 잰다.
# 사용(시험 서버에서): ./run_matrix.sh [seconds]
set -uo pipefail
cd "$(dirname "$0")"
SECONDS_RUN="${1:-120}"
RUN="docker compose --env-file .env exec -T harness python poc_client.py"
mkdir -p harness/work
./measure.sh idle 30 | sed 's/^/idle: /'
for scene in busy calm; do
  for mode in jpeg h264; do
    name="${mode}_${scene}_${SECONDS_RUN}s"
    ./measure.sh "$name" $((SECONDS_RUN + 80)) > "harness/work/measure_${name}.txt" &
    mpid=$!
    sleep 2
    $RUN run --mode "$mode" --scene "$scene" --seconds "$SECONDS_RUN" --name "$name" > "harness/work/run_${name}.log" 2>&1
    echo "done $name rc=$?"
    wait "$mpid"
    sleep 5
  done
done
echo MATRIX_DONE
