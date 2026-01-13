#!/usr/bin/env bash
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8081}
COMPANY_CODE=${COMPANY_CODE:-BBP}
TOKEN=${TOKEN:?TOKEN required}
REQUEST_FILE=${REQUEST_FILE:?REQUEST_FILE required}

curl -sS -w "\nHTTP_STATUS:%{http_code}\n" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Company-Id: ${COMPANY_CODE}" \
  -H 'Content-Type: application/json' \
  -d "@${REQUEST_FILE}" \
  "${BASE_URL}/api/v1/factory/production/logs"
