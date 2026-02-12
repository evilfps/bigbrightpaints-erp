#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
MGMT_URL="${MGMT_URL:-http://localhost:9090}"

if [[ -z "${ERP_SMOKE_EMAIL:-}" || -z "${ERP_SMOKE_PASSWORD:-}" || -z "${ERP_SMOKE_COMPANY:-}" ]]; then
  echo "ERP_SMOKE_EMAIL, ERP_SMOKE_PASSWORD, and ERP_SMOKE_COMPANY must be set for auth checks." >&2
  exit 1
fi

if ! command -v python3 >/dev/null; then
  echo "python3 is required to parse the login response JSON." >&2
  exit 1
fi

echo "Checking health..."
curl -fsS "${MGMT_URL}/actuator/health" >/dev/null

echo "Checking authenticated profile..."
login_payload=$(printf '{"email":"%s","password":"%s","companyCode":"%s"}' \
  "${ERP_SMOKE_EMAIL}" "${ERP_SMOKE_PASSWORD}" "${ERP_SMOKE_COMPANY}")
token=$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "${login_payload}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))'
)

if [[ -z "${token}" ]]; then
  echo "Login did not return accessToken." >&2
  exit 1
fi

echo "Checking API docs are private by default..."
anonymous_docs_status=$(curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}/v3/api-docs")
case "${anonymous_docs_status}" in
  401|403|404)
    ;;
  *)
    echo "Anonymous API docs unexpectedly accessible (status ${anonymous_docs_status})." >&2
    exit 1
    ;;
esac

echo "Checking API docs (authenticated)..."
authenticated_docs_status=$(curl -sS -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${token}" \
  "${BASE_URL}/v3/api-docs")
if [[ "${authenticated_docs_status}" != "200" ]]; then
  echo "Authenticated API docs check failed (status ${authenticated_docs_status})." >&2
  exit 1
fi

curl -fsS \
  -H "Authorization: Bearer ${token}" \
  -H "X-Company-Id: ${ERP_SMOKE_COMPANY}" \
  "${BASE_URL}/api/v1/auth/profile" >/dev/null

echo "Smoke checks OK."
