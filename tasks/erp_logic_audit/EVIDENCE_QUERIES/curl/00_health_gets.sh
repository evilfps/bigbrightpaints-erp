#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL (e.g., http://localhost:8080)}"

echo "== /actuator/health"
curl -sS "${BASE_URL}/actuator/health" | jq .

echo "== /api/integration/health"
curl -sS "${BASE_URL}/api/integration/health" | jq .

