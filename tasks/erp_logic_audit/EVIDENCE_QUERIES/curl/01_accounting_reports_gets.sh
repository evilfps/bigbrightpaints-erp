#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL (e.g., http://localhost:8080)}"
: "${TOKEN:?Set TOKEN (JWT bearer)}"
: "${COMPANY_CODE:?Set COMPANY_CODE (for X-Company-Id)}"

H_AUTH="Authorization: Bearer ${TOKEN}"
H_COMP="X-Company-Id: ${COMPANY_CODE}"

get() {
  local path="$1"
  echo "== GET ${path}"
  curl -sS -H "${H_AUTH}" -H "${H_COMP}" "${BASE_URL}${path}" | jq .
}

get "/api/v1/reports/trial-balance"
get "/api/v1/reports/inventory-valuation"
get "/api/v1/reports/inventory-reconciliation"
get "/api/v1/reports/balance-sheet"
get "/api/v1/reports/profit-loss"
get "/api/v1/accounting/month-end/checklist"
get "/api/v1/accounting/periods"

