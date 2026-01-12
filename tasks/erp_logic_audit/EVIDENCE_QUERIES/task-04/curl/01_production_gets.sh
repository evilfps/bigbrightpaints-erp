#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL (e.g., http://localhost:8081)}"
: "${TOKEN:?Set TOKEN (JWT bearer)}"
: "${COMPANY_CODE:?Set COMPANY_CODE (for X-Company-Id)}"

H_AUTH="Authorization: Bearer ${TOKEN}"
H_COMP="X-Company-Id: ${COMPANY_CODE}"

get() {
  local path="$1"
  echo "== GET ${path}"
  curl -sS -H "${H_AUTH}" -H "${H_COMP}" "${BASE_URL}${path}" | jq .
}

logs=$(curl -sS -H "${H_AUTH}" -H "${H_COMP}" "${BASE_URL}/api/v1/factory/production/logs")

echo "== GET /api/v1/factory/production/logs"
echo "${logs}" | jq .

log_id=$(echo "${logs}" | jq -r '.data[0].id // empty')

if [[ -n "${log_id}" ]]; then
  get "/api/v1/factory/production/logs/${log_id}"
  get "/api/v1/factory/production-logs/${log_id}/packing-history"
fi

get "/api/v1/factory/unpacked-batches"
