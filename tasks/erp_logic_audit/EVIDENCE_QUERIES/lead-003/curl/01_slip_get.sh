#!/usr/bin/env bash
set -euo pipefail
BASE_URL=${BASE_URL:-http://localhost:8081}
COMPANY_CODE=${COMPANY_CODE:-BBP}
TOKEN=${TOKEN:?TOKEN required}
SLIP_ID=${SLIP_ID:?SLIP_ID required}

curl -sS -w "\nHTTP_STATUS:%{http_code}\n" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Company-Id: ${COMPANY_CODE}" \
  "${BASE_URL}/api/v1/dispatch/slip/${SLIP_ID}"
