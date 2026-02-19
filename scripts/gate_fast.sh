#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-fast"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
REQUIRE_DIFF_BASE="${GATE_FAST_REQUIRE_DIFF_BASE:-false}"
RELEASE_VALIDATION_MODE="${GATE_FAST_RELEASE_VALIDATION_MODE:-false}"
SYNC_PR_MODE="${GATE_FAST_SYNC_PR_MODE:-false}"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

resolve_diff_base() {
  if [[ -n "${DIFF_BASE:-}" ]]; then
    echo "$DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]]; then
    echo "$GITHUB_BASE_SHA"
    return 0
  fi

  if git rev-parse --verify --quiet origin/main >/dev/null; then
    git merge-base origin/main HEAD
    return 0
  fi

  if git rev-parse --verify --quiet main >/dev/null; then
    git merge-base main HEAD
    return 0
  fi

  if git rev-parse --verify --quiet origin/master >/dev/null; then
    git merge-base origin/master HEAD
    return 0
  fi

  echo "HEAD~1"
}

if [[ "$RELEASE_VALIDATION_MODE" == "true" ]]; then
  REQUIRE_DIFF_BASE="true"
fi

if [[ "$REQUIRE_DIFF_BASE" == "true" ]]; then
  if [[ -z "${DIFF_BASE:-}" ]]; then
    echo "[gate-fast] FAIL: explicit DIFF_BASE is required when GATE_FAST_REQUIRE_DIFF_BASE=true"
    exit 2
  fi
  if [[ "$DIFF_BASE" =~ ^HEAD~ ]]; then
    echo "[gate-fast] FAIL: HEAD~N is not allowed in release validation mode; use a fixed RELEASE_ANCHOR_SHA"
    exit 2
  fi
fi

echo "[gate-fast] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-fast \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-fast] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-fast \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-fast] orchestrator correlation contract guard"
CORRELATION_GUARD_LOG="$ARTIFACT_DIR/orchestrator-correlation-guard.txt"
if bash "$ROOT_DIR/scripts/guard_orchestrator_correlation_contract.sh" >"$CORRELATION_GUARD_LOG" 2>&1; then
  cat "$CORRELATION_GUARD_LOG"
else
  cat "$CORRELATION_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-fast] integration-failure metadata schema guard"
INTEGRATION_FAILURE_SCHEMA_GUARD_LOG="$ARTIFACT_DIR/integration-failure-metadata-schema-guard.txt"
if bash "$ROOT_DIR/scripts/guard_integration_failure_metadata_schema.sh" >"$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" 2>&1; then
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG"
else
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-fast] openapi contract drift guard"
OPENAPI_CONTRACT_DRIFT_GUARD_LOG="$ARTIFACT_DIR/openapi-contract-drift-guard.txt"
if bash "$ROOT_DIR/scripts/guard_openapi_contract_drift.sh" >"$OPENAPI_CONTRACT_DRIFT_GUARD_LOG" 2>&1; then
  cat "$OPENAPI_CONTRACT_DRIFT_GUARD_LOG"
else
  cat "$OPENAPI_CONTRACT_DRIFT_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-fast] accounting portal scope contract guard"
PORTAL_SCOPE_GUARD_LOG="$ARTIFACT_DIR/accounting-portal-scope-guard.txt"
if bash "$ROOT_DIR/scripts/guard_accounting_portal_scope_contract.sh" >"$PORTAL_SCOPE_GUARD_LOG" 2>&1; then
  cat "$PORTAL_SCOPE_GUARD_LOG"
else
  cat "$PORTAL_SCOPE_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-fast] audit trail ownership contract guard"
AUDIT_TRAIL_OWNERSHIP_GUARD_LOG="$ARTIFACT_DIR/audit-trail-ownership-guard.txt"
if bash "$ROOT_DIR/scripts/guard_audit_trail_ownership_contract.sh" >"$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG" 2>&1; then
  cat "$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG"
else
  cat "$AUDIT_TRAIL_OWNERSHIP_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-fast] run critical truth tests"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-fast test
)

if [[ "$SYNC_PR_MODE" == "true" ]]; then
  echo "[gate-fast] sync-pr mode enabled: skipping changed-files coverage enforcement for long-lived branch convergence PR"
  DIFF_BASE="$(resolve_diff_base)"
  python3 - "$ARTIFACT_DIR/changed-coverage.json" "$DIFF_BASE" <<'PY'
import json
import sys

out_path = sys.argv[1]
diff_base = sys.argv[2]
summary = {
    "diff_base": diff_base,
    "sync_pr_mode": True,
    "skipped": True,
    "reason": "long_lived_branch_convergence_pr",
    "passes": True,
}
with open(out_path, "w", encoding="utf-8") as fh:
  json.dump(summary, fh, indent=2)
print("[gate-fast] changed-files coverage summary:")
print(json.dumps(summary, indent=2))
PY
  echo "[gate-fast] OK"
  exit 0
fi

DIFF_BASE="$(resolve_diff_base)"
echo "[gate-fast] changed-files coverage against base=$DIFF_BASE"
coverage_args=(
  --jacoco "$ROOT_DIR/erp-domain/target/site/jacoco/jacoco.xml"
  --diff-base "$DIFF_BASE"
  --src-root erp-domain/src/main/java
  --threshold-line 0.95
  --threshold-branch 0.90
  --output "$ARTIFACT_DIR/changed-coverage.json"
)

if [[ "$RELEASE_VALIDATION_MODE" == "true" ]]; then
  coverage_args+=(--fail-on-vacuous)
fi

python3 "$ROOT_DIR/scripts/changed_files_coverage.py" "${coverage_args[@]}"

python3 - "$ARTIFACT_DIR/changed-coverage.json" "$RELEASE_VALIDATION_MODE" <<'PY'
import json
import sys

summary_path = sys.argv[1]
release_mode = sys.argv[2].lower() == "true"
with open(summary_path, "r", encoding="utf-8") as fh:
  summary = json.load(fh)

structural_only = summary.get("structural_only", False)
structural_files = summary.get("structural_files") or []
if structural_only:
  print(f"[gate-fast] structural-only diff detected ({len(structural_files)} files)")
  for path in structural_files:
    print(f"  - structural file: {path}")

warnings: list[tuple[str, list[str]]] = []
blocking_findings: list[tuple[str, list[str]]] = []
coverage_skipped = summary.get("coverage_skipped_files") or []
files_with_unmapped = summary.get("files_with_unmapped_lines") or []
if coverage_skipped:
  blocking_findings.append(("coverage_skipped_files", coverage_skipped))
if files_with_unmapped:
  warnings.append(("files_with_unmapped_lines", files_with_unmapped))

for label, items in blocking_findings:
  print(f"[gate-fast] FAIL: {label}:")
  for item in items:
    print(f"  - {item}")

for label, items in warnings:
  print(f"[gate-fast] WARN: {label}:")
  for item in items:
    print(f"  - {item}")

if release_mode and blocking_findings:
  print("[gate-fast] FAIL: release validation mode requires coverage for all changed files/lines.", file=sys.stderr)
  sys.exit(1)
PY

echo "[gate-fast] OK"
