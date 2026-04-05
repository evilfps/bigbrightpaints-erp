#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-core"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
MAVEN_MEMORY_DEFAULTS="$ROOT_DIR/scripts/maven_memory_defaults.sh"
if [[ -f "$MAVEN_MEMORY_DEFAULTS" ]]; then
  source "$MAVEN_MEMORY_DEFAULTS"
  bbp_ensure_maven_memory_defaults
elif [[ -z "${MAVEN_OPTS:-}" ]]; then
  export MAVEN_OPTS="-Xmx${BBP_MAVEN_XMX:-1536m} -XX:MaxMetaspaceSize=${BBP_MAVEN_MAX_METASPACE:-512m} -XX:+UseG1GC"
fi
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" && -n "${BASH_ENV:-}" ]]; then
  export BBP_CHAINED_BASH_ENV="${BASH_ENV:-}"
  export BBP_CHAINED_BASH_ENV_PARENT_PID="$$"
else
  unset BBP_CHAINED_BASH_ENV
  unset BBP_CHAINED_BASH_ENV_PARENT_PID
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
  echo "[gate-core] FAIL: RELEASE_HEAD_SHA=$EXPECTED_RELEASE_HEAD_SHA does not match current HEAD=$RESOLVED_RELEASE_HEAD_SHA"
  exit 2
fi
CANONICAL_BASE_REF="${GATE_CANONICAL_BASE_REF:-harness-engineering-orchestrator}"
CANONICAL_BASE_REQUIRED="${GATE_REQUIRE_CANONICAL_BASE:-true}"
CANONICAL_BASE_SHA=""
CANONICAL_BASE_VERIFIED="false"

resolve_canonical_base() {
  local requested_ref="$CANONICAL_BASE_REF"
  local -a candidate_refs=()
  local -a resolved_refs=()
  local -a resolved_shas=()
  local candidate_ref
  local candidate_sha

  if [[ "$GIT_CONTEXT_AVAILABLE" != "true" ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-core] FAIL: canonical base verification requires git context"
      exit 2
    fi
    return 0
  fi

  candidate_refs=("$requested_ref")
  if [[ "$requested_ref" != origin/* ]]; then
    candidate_refs+=("origin/$requested_ref")
  fi
  for fallback_ref in main origin/main; do
    if [[ "$fallback_ref" != "$requested_ref" ]]; then
      candidate_refs+=("$fallback_ref")
    fi
  done

  for candidate_ref in "${candidate_refs[@]}"; do
    if candidate_sha="$(git -C "$ROOT_DIR" rev-parse --verify --quiet "$candidate_ref" 2>/dev/null)"; then
      resolved_refs+=("$candidate_ref")
      resolved_shas+=("$candidate_sha")
    fi
  done

  if [[ "${#resolved_refs[@]}" -eq 0 ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-core] FAIL: canonical base ref '$requested_ref' was not found and no usable mainline fallback was available"
      exit 2
    fi
    return 0
  fi

  if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
    local idx
    for idx in "${!resolved_refs[@]}"; do
      if git -C "$ROOT_DIR" merge-base --is-ancestor "${resolved_shas[$idx]}" "$RESOLVED_RELEASE_HEAD_SHA"; then
        CANONICAL_BASE_REF="${resolved_refs[$idx]}"
        CANONICAL_BASE_SHA="${resolved_shas[$idx]}"
        CANONICAL_BASE_VERIFIED="true"
        if [[ "$CANONICAL_BASE_REF" != "$requested_ref" ]]; then
          echo "[gate-core] WARN: canonical base '$requested_ref' is unavailable/stale; using '$CANONICAL_BASE_REF' ($CANONICAL_BASE_SHA)"
        fi
        return 0
      fi
    done

    local resolved_desc=""
    for idx in "${!resolved_refs[@]}"; do
      if [[ -n "$resolved_desc" ]]; then
        resolved_desc+=", "
      fi
      resolved_desc+="${resolved_refs[$idx]} (${resolved_shas[$idx]})"
    done
    echo "[gate-core] FAIL: HEAD=$RESOLVED_RELEASE_HEAD_SHA is not based on canonical base candidates: $resolved_desc"
    exit 2
  fi

  CANONICAL_BASE_REF="${resolved_refs[0]}"
  CANONICAL_BASE_SHA="${resolved_shas[0]}"
}

resolve_canonical_base
TRACEABILITY_FILE="$ARTIFACT_DIR/gate-core-traceability.json"

echo "[gate-core] confidence-lane contract"
python3 "$ROOT_DIR/scripts/validate_confidence_lanes.py" \
  --contract "$ROOT_DIR/testing/local/confidence-lanes.json" \
  --lane main \
  --output "$ARTIFACT_DIR/confidence-lane-contract.json"

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
            "path": f"artifacts/gate-core/{name}",
            "sha256": digest.hexdigest(),
            "bytes": stat_result.st_size,
        }
    )

payload = {
    "gate": "gate-core",
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

echo "[gate-core] OK"
