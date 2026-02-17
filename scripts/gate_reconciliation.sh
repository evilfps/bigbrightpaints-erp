#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-reconciliation"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
GATE_START_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RELEASE_SHA="${RELEASE_SHA:-}"
if [[ -z "$RELEASE_SHA" ]] && resolved_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  RELEASE_SHA="$resolved_sha"
fi
if [[ -z "$RELEASE_SHA" || "$RELEASE_SHA" == "unknown" ]]; then
  echo "[gate-reconciliation] ERROR: unable to resolve release SHA; set RELEASE_SHA explicitly or run within a git checkout with HEAD available" >&2
  exit 1
fi
if resolved_head_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  if [[ "$RELEASE_SHA" != "$resolved_head_sha" ]]; then
    echo "[gate-reconciliation] ERROR: RELEASE_SHA mismatch; expected checkout HEAD $resolved_head_sha but got $RELEASE_SHA" >&2
    exit 1
  fi
fi
TRACEABILITY_FILE="$ARTIFACT_DIR/reconciliation-gate-traceability.json"

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
  mvn -B -ntp -Dsurefire.runOrder=alphabetical -Pgate-reconciliation test
)

echo "[gate-reconciliation] build evidence artifacts"
python3 "$ROOT_DIR/scripts/surefire_report_summary.py" \
  --reports-dir "$ROOT_DIR/erp-domain/target/surefire-reports" \
  --summary-out "$ARTIFACT_DIR/reconciliation-summary.json" \
  --mismatch-out "$ARTIFACT_DIR/mismatch-report.txt" \
  --gate gate-reconciliation

echo "[gate-reconciliation] verify reconciliation evidence artifacts"
required_reconciliation_artifacts=(
  "$ARTIFACT_DIR/reconciliation-summary.json"
  "$ARTIFACT_DIR/mismatch-report.txt"
  "$ARTIFACT_DIR/catalog-validation.json"
  "$ARTIFACT_DIR/flake-guard.json"
)
for required_artifact in "${required_reconciliation_artifacts[@]}"; do
  if [[ ! -f "$required_artifact" ]]; then
    echo "[gate-reconciliation] missing required artifact: $required_artifact" >&2
    exit 1
  fi
done

if [[ -s "$ARTIFACT_DIR/mismatch-report.txt" ]]; then
  echo "[gate-reconciliation] mismatch-report.txt must be empty on pass" >&2
  exit 1
fi

echo "[gate-reconciliation] build traceability manifest"
python3 - "$ARTIFACT_DIR" "$GATE_START_UTC" "$RELEASE_SHA" <<'PY'
import hashlib
import json
import os
import sys
import time

artifact_dir, started_at_utc, release_sha = sys.argv[1:4]
manifest_path = os.path.join(artifact_dir, "reconciliation-gate-traceability.json")
tmp_path = manifest_path + ".tmp"
artifacts = []

for name in sorted(os.listdir(artifact_dir)):
    path = os.path.join(artifact_dir, name)
    if not os.path.isfile(path):
        continue
    if path == manifest_path:
        continue
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
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
    "release_sha": release_sha,
    "started_at_utc": started_at_utc,
    "finished_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifact_count": len(artifacts),
    "artifacts": artifacts,
}

with open(tmp_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
os.replace(tmp_path, manifest_path)
PY
cat "$TRACEABILITY_FILE"

echo "[gate-reconciliation] OK"
