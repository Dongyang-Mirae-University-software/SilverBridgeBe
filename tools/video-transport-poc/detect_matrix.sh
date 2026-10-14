#!/usr/bin/env bash
# gosky 감지 일치 시험: 같은 합성 장면을 JPEG·H.264(1.5/2.5Mbps) 경로로 각각 보내 감지 결과를 비교한다.
# 사용(gosky, /home/apps/ws-poc-gosky): ./detect_matrix.sh [seconds=60]
set -uo pipefail
cd "$(dirname "$0")"
S="${1:-60}"
H=./h.sh
: > harness/work/detect.log
say() { echo "$*" | tee -a harness/work/detect.log; }
for scene in fire smallfire calm; do
  # 같은 JPEG 경로를 두 번 - 분석 시점 차이로 생기는 자체 편차(노이즈 바닥)를 본다
  $H poc_client.py run --mode jpeg --scene "$scene" --seconds "$S" --direct --session-id "ward_pocdet_${scene}" --name "jpegA_${scene}" > /dev/null 2>&1; say "done jpegA_${scene}"
  $H poc_client.py run --mode jpeg --scene "$scene" --seconds "$S" --direct --session-id "ward_pocdet_${scene}" --name "jpegB_${scene}" > /dev/null 2>&1; say "done jpegB_${scene}"
  $H poc_client.py run --mode h264direct --scene "$scene" --seconds "$S" --bitrate 1500 --direct --session-id "ward_pocdet_${scene}" --name "h264_1500_${scene}" > /dev/null 2>&1; say "done h264_1500_${scene}"
  $H poc_client.py run --mode h264direct --scene "$scene" --seconds "$S" --bitrate 2500 --direct --session-id "ward_pocdet_${scene}" --name "h264_2500_${scene}" > /dev/null 2>&1; say "done h264_2500_${scene}"
done
say DETECT_DONE
