#!/usr/bin/env bash
# 폴백·장애 시험. 시험 서버(~/ws-poc)에서 실행한다. 컨테이너를 멈추고 되살리므로 다른 시험과 동시에 돌리지 말 것.
# 결과는 한 줄씩 "[케이스] 기대=... 결과=..." 로 harness/work/fallback.log 에 남는다.
set -uo pipefail
cd "$(dirname "$0")"
DC="docker compose --env-file .env"
PF="$DC exec -T harness python poc_fallback.py"
LOG=harness/work/fallback.log
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

SID="$(python3 -c 'import json;print(json.load(open("harness/work/state.json"))["sessionId"])')"
setenv() { # 이름 값 - .env 의 값을 바꾼다
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}
wait_be() { for _ in $(seq 1 60); do curl -sf http://127.0.0.1:6591/actuator/health >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
wait_ai() { for _ in $(seq 1 40); do curl -sf http://127.0.0.1:6592/health >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
restore() {
  setenv POC_TICKET_ENABLED true
  setenv POC_INGEST_ENABLED true
  setenv POC_WS_H264_SESSION_IDS "$SID"
  $DC up -d ai be >/dev/null 2>&1
  $DC start redis >/dev/null 2>&1 || true
  wait_ai; wait_be
}

say "== 기준선: 티켓 발급 + 10초 H.264 송출이 정상"
restore
say "[C0 ticket] $($PF ticket)"
say "[C0 hold10] $($PF hold --seconds 10)"

say "== C1 킬 스위치(camera.upload.ticket-enabled=false): 티켓 503, JPEG는 그대로"
setenv POC_TICKET_ENABLED false; $DC up -d be >/dev/null 2>&1; wait_be
say "[C1 ticket] 기대=503 CAMERA_UPLOAD_UNAVAILABLE 결과=$($PF ticket)"
say "[C1 jpeg] 기대=프레임 수신 결과=$($PF probe-jpeg --seconds 6)"
restore

say "== C2 플래그 제거(CAMERA_WS_H264_SESSION_IDS 비움): 티켓 409, JPEG는 그대로"
setenv POC_WS_H264_SESSION_IDS ""; $DC up -d be >/dev/null 2>&1; wait_be
say "[C2 ticket] 기대=409 CAMERA_UPLOAD_NOT_ENABLED 결과=$($PF ticket)"
say "[C2 jpeg] 기대=프레임 수신 결과=$($PF probe-jpeg --seconds 6)"
restore

say "== C3 AI 수신 비활성(INGEST_WS_ENABLED=false): 연결은 AI 사유로 즉시 닫히고, JPEG는 그대로"
setenv POC_INGEST_ENABLED false; $DC up -d ai >/dev/null 2>&1; wait_ai
say "[C3 ws] 기대=1008 INGEST_DISABLED(또는 핸드셰이크 503) 결과=$($PF ws-try)"
say "[C3 jpeg] 기대=프레임 수신 결과=$($PF probe-jpeg --seconds 6)"
restore

say "== C4 AI 서버 중지: 핸드셰이크 503이 빠르게 돌아온다(FE는 곧바로 JPEG 폴백)"
$DC stop ai >/dev/null 2>&1
say "[C4 ws] 기대=503 3~5초 안 결과=$($PF ws-try)"
$DC start ai >/dev/null 2>&1; wait_ai

say "== C5 Redis 장애: 새 티켓은 503, 이미 수립된 연결은 이어 간다"
rm -f harness/work/c5.json
$DC exec -T harness python poc_fallback.py hold --seconds 40 --out /poc/work/c5.json > /dev/null 2>&1 &
HOLD_PID=$!
sleep 12
$DC stop redis >/dev/null 2>&1
sleep 3
say "[C5 ticket during outage] 기대=503(필터 fail-closed) 결과=$($PF ticket)"
sleep 10
$DC start redis >/dev/null 2>&1
wait "$HOLD_PID"
say "[C5 established] 기대=종료 코드 없음·프레임 계속·간격 정상 결과=$(cat harness/work/c5.json)"

say "== C6 토큰 무효화(비밀번호 변경 등): 60초 재확인으로 1008 AUTH_INVALIDATED 종료"
rm -f harness/work/c6.json
$DC exec -T harness python poc_fallback.py hold --seconds 100 --out /poc/work/c6.json > /dev/null 2>&1 &
HOLD_PID=$!
sleep 10
NOW="$(date +%s)"
$DC exec -T redis redis-cli SET "password:invalidate:PW0001" "$((NOW + 1))" >/dev/null
sleep 2
$DC exec -T redis redis-cli EXPIRE "password:invalidate:PW0001" 300 >/dev/null
wait "$HOLD_PID"
say "[C6 invalidated] 기대=close 1008 AUTH_INVALIDATED, 60초 안(재확인 주기) 결과=$(cat harness/work/c6.json)"
say "[C6 ticket after] 기대=200(무효화 시각 이후에 발급한 새 토큰은 통과 - 재로그인 상황) 결과=$($PF ticket)"
$DC exec -T redis redis-cli DEL "password:invalidate:PW0001" >/dev/null

say "== C7 정리 후 기준선 복귀"
restore
say "[C7 ticket] $($PF ticket)"
say "[C7 hold10] $($PF hold --seconds 10)"
say FALLBACK_DONE
