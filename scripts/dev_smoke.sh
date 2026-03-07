#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" && -n "${BASH_ENV:-}" ]]; then
  export BBP_CHAINED_BASH_ENV="${BASH_ENV:-}"
  export BBP_CHAINED_BASH_ENV_PARENT_PID="$$"
else
  unset BBP_CHAINED_BASH_ENV
  unset BBP_CHAINED_BASH_ENV_PARENT_PID
fi
export BASH_ENV="$COMPAT_BASH_ENV_BOOTSTRAP"

ARTIFACT_DIR="$ROOT_DIR/artifacts/dev-smoke"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
DEV_SMOKE_MANIFEST="${DEV_SMOKE_MANIFEST:-$ROOT_DIR/testing/local/manifests/dev-smoke.txt}"
LOCAL_GUARD_MANIFEST="${LOCAL_GUARD_MANIFEST:-$ROOT_DIR/testing/local/manifests/local-guard.txt}"

rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

echo "[dev-smoke] validate local manifests"
python3 "$ROOT_DIR/scripts/validate_local_test_manifests.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --dev-smoke-manifest "$DEV_SMOKE_MANIFEST" \
  --local-guard-manifest "$LOCAL_GUARD_MANIFEST" \
  --output "$ARTIFACT_DIR/local-manifest-validation.json"

bash "$ROOT_DIR/scripts/run_local_test_manifest.sh" \
  --profile dev-smoke \
  --label dev-smoke \
  --manifest "$DEV_SMOKE_MANIFEST"

echo "[dev-smoke] OK"
