#!/usr/bin/env bash
set -euo pipefail

: "${APP_URL:?APP_URL required}"

curl -sS -w "\nHTTP_STATUS:%{http_code}\n" "${APP_URL}/actuator/health"
