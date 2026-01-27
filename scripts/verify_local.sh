#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[verify_local] root=$ROOT_DIR"

echo "[verify_local] schema drift scan"
bash "$ROOT_DIR/scripts/schema_drift_scan.sh"

echo "[verify_local] mvn verify"
(cd "$ROOT_DIR/erp-domain" && mvn -B -ntp verify)

echo "[verify_local] OK"

