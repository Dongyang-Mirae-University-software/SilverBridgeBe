#!/usr/bin/env bash
# 통합 테스트(Testcontainers - 실제 PostgreSQL) 실행기. vkcs-linux(Docker 있는 서버)에서 돈다.
#
#   사용법 (vkcs에서):  ~/SilverBridgeBe/tools/integration-test.sh [대상]
#     대상 생략      → origin/dev 최신
#     브랜치 이름    → origin/<브랜치> (머지 전 PR 검증 - 예: feature/xxx)
#     HEAD          → 배포 폴더의 현재 커밋 (CD가 배포 직전에 쓴다)
#
# 왜 이렇게 도나
#   - 호스트에는 JDK 21이 없다(Java 1.8뿐, 서버에 설치하지 않는다) → gradle:9.4.1-jdk21 컨테이너 안에서 돌린다.
#   - Testcontainers가 PostgreSQL을 띄우려면 Docker가 필요하다 → 호스트 docker 소켓을 넘기고,
#     띄운 DB에 localhost로 붙도록 host 네트워크 + TESTCONTAINERS_HOST_OVERRIDE=localhost.
#   - 배포 폴더(~/SilverBridgeBe)는 건드리지 않는다 → 대상 커밋을 git archive로 임시 폴더에 풀어서 돌린다.
#     컨테이너는 root로 파일을 만들기 때문에 끝나기 전에 소유자를 되돌린다(안 그러면 임시 폴더를 못 지운다).
#   - Gradle 의존성은 이름 있는 볼륨(sb-gradle-it-cache)에 남겨 두 번째 실행부터 빠르다.
#
# 종료 코드: 테스트 성공 0 / 실패 1 이상. 리포트는 ~/sb-it-reports/latest/index.html 에 남는다.
set -euo pipefail

TARGET="${1:-dev}"
REPO_DIR="${REPO_DIR:-$HOME/SilverBridgeBe}"
IMAGE="gradle:9.4.1-jdk21"
REPORT_DIR="$HOME/sb-it-reports/latest"

WORK="$(mktemp -d /tmp/sb-it.XXXXXX)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

if [ "$TARGET" = "HEAD" ]; then
  REF="HEAD"
else
  git -C "$REPO_DIR" fetch -q origin "$TARGET"
  REF="FETCH_HEAD"
fi
COMMIT="$(git -C "$REPO_DIR" rev-parse --short "$REF")"
git -C "$REPO_DIR" archive "$REF" | tar -x -C "$WORK"
echo "[integration-test] 대상 ${TARGET} @ ${COMMIT} → ${WORK}"

set +e
docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v sb-gradle-it-cache:/home/gradle/.gradle \
  -v "$WORK":/workspace -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  "$IMAGE" \
  sh -c "gradle integrationTest --no-daemon --console=plain; rc=\$?; chown -R $(id -u):$(id -g) /workspace; exit \$rc"
RC=$?
set -e

rm -rf "$REPORT_DIR" && mkdir -p "$REPORT_DIR"
if [ -d "$WORK/build/reports/tests/integrationTest" ]; then
  cp -r "$WORK/build/reports/tests/integrationTest/." "$REPORT_DIR/"
fi

if [ "$RC" -eq 0 ]; then
  echo "[integration-test] 통과 (${COMMIT})"
else
  echo "[integration-test] 실패 (${COMMIT}, exit ${RC}) - 리포트: ${REPORT_DIR}/index.html"
fi
exit "$RC"
