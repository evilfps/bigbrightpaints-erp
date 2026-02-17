#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-core"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
GATE_START_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RELEASE_SHA="${RELEASE_SHA:-}"
if [[ -z "$RELEASE_SHA" ]] && resolved_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  RELEASE_SHA="$resolved_sha"
fi
if [[ -z "$RELEASE_SHA" || "$RELEASE_SHA" == "unknown" ]]; then
  echo "[gate-core] ERROR: unable to resolve release SHA; set RELEASE_SHA explicitly or run within a git checkout with HEAD available" >&2
  exit 1
fi
if resolved_head_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  if [[ "$RELEASE_SHA" != "$resolved_head_sha" ]]; then
    echo "[gate-core] ERROR: RELEASE_SHA mismatch; expected checkout HEAD $resolved_head_sha but got $RELEASE_SHA" >&2
    exit 1
  fi
fi
TRACEABILITY_FILE="$ARTIFACT_DIR/gate-core-traceability.json"

echo "[gate-core] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-core \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-core] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-core \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-core] orchestrator correlation contract guard"
CORRELATION_GUARD_LOG="$ARTIFACT_DIR/orchestrator-correlation-guard.txt"
if bash "$ROOT_DIR/scripts/guard_orchestrator_correlation_contract.sh" >"$CORRELATION_GUARD_LOG" 2>&1; then
  cat "$CORRELATION_GUARD_LOG"
else
  cat "$CORRELATION_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-core] integration-failure metadata schema guard"
INTEGRATION_FAILURE_SCHEMA_GUARD_LOG="$ARTIFACT_DIR/integration-failure-metadata-schema-guard.txt"
if bash "$ROOT_DIR/scripts/guard_integration_failure_metadata_schema.sh" >"$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" 2>&1; then
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG"
else
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-core] integration-failure schema fixture matrix"
INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG="$ARTIFACT_DIR/integration-failure-metadata-schema-fixture-matrix.txt"
if bash "$ROOT_DIR/scripts/guard_integration_failure_metadata_schema_fixture_matrix.sh" >"$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG" 2>&1; then
  cat "$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG"
else
  cat "$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG" >&2
  exit 1
fi

echo "[gate-core] openapi contract drift guard"
OPENAPI_CONTRACT_DRIFT_GUARD_LOG="$ARTIFACT_DIR/openapi-contract-drift-guard.txt"
if bash "$ROOT_DIR/scripts/guard_openapi_contract_drift.sh" >"$OPENAPI_CONTRACT_DRIFT_GUARD_LOG" 2>&1; then
  cat "$OPENAPI_CONTRACT_DRIFT_GUARD_LOG"
else
  cat "$OPENAPI_CONTRACT_DRIFT_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-core] accounting portal scope contract guard"
PORTAL_SCOPE_GUARD_LOG="$ARTIFACT_DIR/accounting-portal-scope-guard.txt"
if bash "$ROOT_DIR/scripts/guard_accounting_portal_scope_contract.sh" >"$PORTAL_SCOPE_GUARD_LOG" 2>&1; then
  cat "$PORTAL_SCOPE_GUARD_LOG"
else
  cat "$PORTAL_SCOPE_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-core] audit trail ownership contract guard"
AUDIT_TRAIL_OWNERSHIP_GUARD_LOG="$ARTIFACT_DIR/audit-trail-ownership-guard.txt"
if bash "$ROOT_DIR/scripts/guard_audit_trail_ownership_contract.sh" >"$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG" 2>&1; then
  cat "$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG"
else
  cat "$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-core] run critical+concurrency+reconciliation truth tests"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Dsurefire.runOrder=alphabetical -Pgate-core test
)

echo "[gate-core] module coverage gate"
python3 "$ROOT_DIR/scripts/module_coverage_gate.py" \
  --jacoco "$ROOT_DIR/erp-domain/target/site/jacoco/jacoco.xml" \
  --packages com.bigbrightpaints.erp.modules.accounting,com.bigbrightpaints.erp.modules.inventory,com.bigbrightpaints.erp.modules.invoice,com.bigbrightpaints.erp.orchestrator.policy,com.bigbrightpaints.erp.orchestrator.service,com.bigbrightpaints.erp.orchestrator.workflow \
  --classes com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService,com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService,com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService,com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy,com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer,com.bigbrightpaints.erp.orchestrator.service.TraceService,com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService \
  --line-threshold 0.92 \
  --branch-threshold 0.85 \
  --active-classes-only \
  --min-active-classes 7 \
  --min-active-packages 4 \
  --output "$ARTIFACT_DIR/module-coverage.json"

echo "[gate-core] build traceability manifest"
python3 - "$ARTIFACT_DIR" "$GATE_START_UTC" "$RELEASE_SHA" <<'PY'
import hashlib
import json
import os
import sys
import time

artifact_dir, started_at_utc, release_sha = sys.argv[1:4]
manifest_path = os.path.join(artifact_dir, "gate-core-traceability.json")
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
            "path": f"artifacts/gate-core/{name}",
            "sha256": digest.hexdigest(),
            "bytes": stat_result.st_size,
        }
    )

payload = {
    "gate": "gate-core",
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

echo "[gate-core] OK"
