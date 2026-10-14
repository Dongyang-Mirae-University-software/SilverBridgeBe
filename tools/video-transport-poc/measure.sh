#!/usr/bin/env bash
# 호스트에서 시험 구간의 BE·AI 컨테이너 CPU 를 잰다(docker stats, 2초 간격). 사용:
#   ./measure.sh <name> <seconds> [be_container ai_container]
# 결과: harness/work/cpu_<name>.csv 와 평균(코어 1개 = 100%) 한 줄 요약.
set -euo pipefail
name="$1"; seconds="$2"
be="${3:-wspoc-be-1}"; ai="${4:-wspoc-ai-1}"
out="$(dirname "$0")/harness/work/cpu_${name}.csv"
mkdir -p "$(dirname "$out")"
echo "t,be_cpu,ai_cpu,be_mem_mb,ai_mem_mb" > "$out"
end=$(( $(date +%s) + seconds ))
while [ "$(date +%s)" -lt "$end" ]; do
  row="$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' "$be" "$ai")"
  b="$(echo "$row" | awk -v n="$be" '$1==n {gsub("%","",$2); print $2}')"
  a="$(echo "$row" | awk -v n="$ai" '$1==n {gsub("%","",$2); print $2}')"
  bm="$(echo "$row" | awk -v n="$be" '$1==n {print $3}' | sed 's/MiB//;s/GiB/*1024/' | bc -l 2>/dev/null || echo 0)"
  am="$(echo "$row" | awk -v n="$ai" '$1==n {print $3}' | sed 's/MiB//;s/GiB/*1024/' | bc -l 2>/dev/null || echo 0)"
  echo "$(date +%s),${b:-0},${a:-0},${bm:-0},${am:-0}" >> "$out"
done
awk -F, 'NR>1 {b+=$2; a+=$3; n++} END {printf "samples=%d be_cpu_mean=%.1f%% ai_cpu_mean=%.1f%%\n", n, b/n, a/n}' "$out"
