#!/bin/bash
# 관리자 대시보드 데모 데이터 - gosky dev DB(dmu-dev-db) 전용. prod DB에 쓰지 말 것.
#   tools/demo-seed/run.sh         이전 데모 데이터를 지우고 다시 넣는다("오늘" 수치는 실행 시점 KST 기준 - 시연 당일 다시 실행)
#   tools/demo-seed/run.sh clean   데모 데이터만 지운다
# 데모 데이터 표식: 이메일 @demo.silverbridge.test, 세션 ward_DemoSeed*, 임시저장 제목 "[데모] ".
# 가짜 회원은 비밀번호·전화번호·FCM 토큰이 없고 판정 재촉을 꺼 둔다. 카메라는 만들지 않는다(서비스 상한 3대·AI 구독 대상).
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
HOST="${DEMO_SEED_HOST:-gosky}"

files=("$D/demo-cleanup.sql")
[ "${1:-}" = "clean" ] || files+=("$D/demo-seed.sql")

cat "${files[@]}" | ssh -o ConnectTimeout=10 "$HOST" \
  'docker exec -i dmu-dev-db psql -U dev -d dev -q -v ON_ERROR_STOP=1' >/dev/null
echo "done: ${1:-seed} on $HOST"
