#!/usr/bin/env bash
# Realtime Chat — e2e orchestrator
#
# 동작:
#   1. (옵션) docker compose 재빌드/재기동
#   2. mongodb/redis healthy 대기
#   3. api-server/chat-server-1·2 부팅 대기
#   4. npm install (한 번만)
#   5. scenario.js 실행 → PASS/FAIL 출력
#
# 사용:
#   ./e2e/run.sh            # 컨테이너가 이미 떠 있다고 가정, 시나리오만
#   ./e2e/run.sh --rebuild  # docker compose down -v + up --build (깨끗한 상태)
#   ./e2e/run.sh --restart  # down -v + up (rebuild 없이 데이터만 청소)

set -euo pipefail

cd "$(dirname "$0")/.."   # 프로젝트 루트로

REBUILD=0
RESTART=0
for arg in "$@"; do
  case "$arg" in
    --rebuild) REBUILD=1 ;;
    --restart) RESTART=1 ;;
    *) echo "usage: $0 [--rebuild|--restart]" >&2; exit 1 ;;
  esac
done

echo "━━━ 1. docker compose 상태 확인 ━━━"
if [ "$REBUILD" -eq 1 ]; then
  echo "  → down -v + up -d --build"
  docker compose down -v
  docker compose up -d --build
elif [ "$RESTART" -eq 1 ]; then
  echo "  → down -v + up -d"
  docker compose down -v
  docker compose up -d
else
  docker compose up -d >/dev/null
fi

echo "━━━ 2. mongodb/redis healthy 대기 ━━━"
for i in $(seq 1 30); do
  ok=$(docker compose ps --format json mongodb redis 2>/dev/null | python3 -c '
import sys, json
ok = 0
for line in sys.stdin:
  line = line.strip()
  if not line: continue
  try: d = json.loads(line)
  except: continue
  if d.get("Health","") == "healthy": ok += 1
print(ok)
' || echo 0)
  if [ "$ok" = "2" ]; then echo "  mongodb + redis healthy"; break; fi
  sleep 1
done

echo "━━━ 3. api-server / chat-server-1·2 부팅 대기 (actuator health) ━━━"
wait_health() {
  local port="$1"; local name="$2"
  local code=""
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:${port}/actuator/health" 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then echo "  $name (:$port) HEALTHY"; return 0; fi
    sleep 1
  done
  echo "  $name (:$port) TIMEOUT (last http=$code)"; return 1
}
wait_health 8080 'api-server'
wait_health 8081 'chat-server-1'
wait_health 8082 'chat-server-2'

echo "━━━ 4. npm install ━━━"
cd e2e
if [ ! -d node_modules ]; then
  npm install --no-audit --no-fund --silent
else
  echo "  node_modules 이미 존재 — skip"
fi

echo "━━━ 5. 시나리오 실행 ━━━"
echo
exec node scenario.js
