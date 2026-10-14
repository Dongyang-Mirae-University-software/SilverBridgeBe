#!/usr/bin/env bash
# 시험 인스턴스 준비: 무작위 비밀(.env)과 가짜 Firebase 서비스 계정을 만든다. 실제 서비스 키는 쓰지 않는다.
# 사용: ./setup.sh   (이 디렉터리에서, app.jar·ai-src·harness 를 먼저 복사해 둘 것)
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  rand() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
  tmp="$(mktemp -d)"
  # 가짜 서비스 계정 - 키는 이 서버에서 만든 임시 RSA 키라 FCM에 인증할 수 없다(알림이 실제로 나갈 수 없다)
  openssl genrsa -out "$tmp/key.pem" 2048 2>/dev/null
  openssl pkcs8 -topk8 -nocrypt -in "$tmp/key.pem" -out "$tmp/key8.pem"
  python3 - "$tmp" > "$tmp/sa.json" <<'PY'
import json, sys
key = open(sys.argv[1] + "/key8.pem").read()
print(json.dumps({
    "type": "service_account", "project_id": "poc-not-real", "private_key_id": "0" * 40,
    "private_key": key, "client_email": "poc@poc-not-real.iam.gserviceaccount.com",
    "client_id": "0", "token_uri": "https://oauth2.googleapis.com/token",
}))
PY
  {
    echo "POC_DB_PASSWORD=$(rand 24)"
    echo "POC_JWT_SECRET=$(rand 40)"      # 40자 = HS256(jjwt가 키 길이로 알고리즘을 고른다 - 48자 이상이면 HS384)
    echo "POC_KAKAO_SECRET=$(rand 40)"
    echo "POC_AI_KEY=$(rand 40)"
    echo "POC_FIREBASE_B64=$(base64 -w0 "$tmp/sa.json")"
    echo "POC_WS_H264_SESSION_IDS="
  } > .env
  chmod 600 .env
  rm -rf "$tmp"
  echo ".env 생성"
else
  echo ".env 유지"
fi
mkdir -p frames harness
