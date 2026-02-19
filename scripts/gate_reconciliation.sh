#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-reconciliation"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" ]]; then
  export BBP_ORIGINAL_BASH_ENV="${BASH_ENV:-}"
fi
export BASH_ENV="$COMPAT_BASH_ENV_BOOTSTRAP"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
GATE_START_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
EXPECTED_RELEASE_HEAD_SHA="${RELEASE_HEAD_SHA:-}"
RESOLVED_RELEASE_HEAD_SHA="unknown"
GIT_CONTEXT_AVAILABLE="false"
if resolved_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  RESOLVED_RELEASE_HEAD_SHA="$resolved_sha"
  GIT_CONTEXT_AVAILABLE="true"
elif [[ -n "$EXPECTED_RELEASE_HEAD_SHA" ]]; then
  RESOLVED_RELEASE_HEAD_SHA="$EXPECTED_RELEASE_HEAD_SHA"
fi
if [[ -n "$EXPECTED_RELEASE_HEAD_SHA" && "$GIT_CONTEXT_AVAILABLE" == "true" && "$EXPECTED_RELEASE_HEAD_SHA" != "$RESOLVED_RELEASE_HEAD_SHA" ]]; then
  echo "[gate-reconciliation] FAIL: RELEASE_HEAD_SHA=$EXPECTED_RELEASE_HEAD_SHA does not match current HEAD=$RESOLVED_RELEASE_HEAD_SHA"
  exit 2
fi
CANONICAL_BASE_REF="${GATE_CANONICAL_BASE_REF:-harness-engineering-orchestrator}"
CANONICAL_BASE_REQUIRED="${GATE_REQUIRE_CANONICAL_BASE:-true}"
CANONICAL_BASE_SHA=""
CANONICAL_BASE_VERIFIED="false"
if [[ "$GIT_CONTEXT_AVAILABLE" == "true" ]]; then
  if resolved_base_sha="$(git -C "$ROOT_DIR" rev-parse --verify --quiet "$CANONICAL_BASE_REF" 2>/dev/null)"; then
    CANONICAL_BASE_SHA="$resolved_base_sha"
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      if ! git -C "$ROOT_DIR" merge-base --is-ancestor "$CANONICAL_BASE_SHA" "$RESOLVED_RELEASE_HEAD_SHA"; then
        echo "[gate-reconciliation] FAIL: HEAD=$RESOLVED_RELEASE_HEAD_SHA is not based on canonical base '$CANONICAL_BASE_REF' ($CANONICAL_BASE_SHA)"
        exit 2
      fi
      CANONICAL_BASE_VERIFIED="true"
    fi
  elif [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
    echo "[gate-reconciliation] FAIL: canonical base ref '$CANONICAL_BASE_REF' was not found"
    exit 2
  fi
elif [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
  echo "[gate-reconciliation] FAIL: canonical base verification requires git context"
  exit 2
fi
TRACEABILITY_FILE="$ARTIFACT_DIR/gate-reconciliation-traceability.json"

echo "[gate-reconciliation] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-reconciliation \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-reconciliation] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-reconciliation \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-reconciliation] run reconciliation truth tests"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-reconciliation test
)

echo "[gate-reconciliation] build evidence artifacts"
python3 "$ROOT_DIR/scripts/surefire_report_summary.py" \
  --reports-dir "$ROOT_DIR/erp-domain/target/surefire-reports" \
  --summary-out "$ARTIFACT_DIR/reconciliation-summary.json" \
  --mismatch-out "$ARTIFACT_DIR/mismatch-report.txt" \
  --gate gate-reconciliation

echo "[gate-reconciliation] build traceability manifest"
python3 - "$ARTIFACT_DIR" "$TRACEABILITY_FILE" "$GATE_START_UTC" "$RESOLVED_RELEASE_HEAD_SHA" "$GIT_CONTEXT_AVAILABLE" "$CANONICAL_BASE_REF" "$CANONICAL_BASE_SHA" "$CANONICAL_BASE_REQUIRED" "$CANONICAL_BASE_VERIFIED" <<'PY'
import hashlib
import json
import os
import sys
import time

(
    artifact_dir,
    manifest_path,
    started_at_utc,
    release_head_sha,
    git_context_available,
    canonical_base_ref,
    canonical_base_sha,
    canonical_base_required,
    canonical_base_verified,
) = sys.argv[1:10]

tmp_path = manifest_path + ".tmp"
artifacts = []

for name in sorted(os.listdir(artifact_dir)):
    path = os.path.join(artifact_dir, name)
    if not os.path.isfile(path):
        continue
    if path == manifest_path:
        continue
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    stat_result = os.stat(path)
    artifacts.append(
        {
            "path": f"artifacts/gate-reconciliation/{name}",
            "sha256": digest.hexdigest(),
            "bytes": stat_result.st_size,
        }
    )

payload = {
    "gate": "gate-reconciliation",
    "release_head_sha": release_head_sha,
    "git_context_available": git_context_available.lower() == "true",
    "canonical_base_ref": canonical_base_ref,
    "canonical_base_sha": canonical_base_sha or None,
    "canonical_base_required": canonical_base_required.lower() == "true",
    "canonical_base_verified": canonical_base_verified.lower() == "true",
    "started_at_utc": started_at_utc,
    "finished_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifact_count": len(artifacts),
    "artifacts": artifacts,
}

with open(tmp_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
    fh.write("\n")
os.replace(tmp_path, manifest_path)
PY
cat "$TRACEABILITY_FILE"

echo "[gate-reconciliation] OK"
