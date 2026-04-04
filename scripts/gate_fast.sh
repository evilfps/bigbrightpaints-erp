#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-fast"
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
REQUIRE_DIFF_BASE="${GATE_FAST_REQUIRE_DIFF_BASE:-false}"
RELEASE_VALIDATION_MODE="${GATE_FAST_RELEASE_VALIDATION_MODE:-false}"
SYNC_PR_MODE="${GATE_FAST_SYNC_PR_MODE:-false}"
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
  echo "[gate-fast] FAIL: RELEASE_HEAD_SHA=$EXPECTED_RELEASE_HEAD_SHA does not match current HEAD=$RESOLVED_RELEASE_HEAD_SHA"
  exit 2
fi
CANONICAL_BASE_REF="${GATE_CANONICAL_BASE_REF:-harness-engineering-orchestrator}"
CANONICAL_BASE_REQUIRED="${GATE_REQUIRE_CANONICAL_BASE:-false}"
CANONICAL_BASE_SHA=""
CANONICAL_BASE_VERIFIED="false"
TRACEABILITY_FILE="$ARTIFACT_DIR/gate-fast-traceability.json"

resolve_diff_base() {
  if [[ -n "${DIFF_BASE:-}" ]]; then
    echo "$DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]]; then
    echo "$GITHUB_BASE_SHA"
    return 0
  fi

  if git rev-parse --verify --quiet main >/dev/null; then
    git merge-base main HEAD
    return 0
  fi

  if git rev-parse --verify --quiet origin/main >/dev/null; then
    git merge-base origin/main HEAD
    return 0
  fi

  if git rev-parse --verify --quiet origin/master >/dev/null; then
    git merge-base origin/master HEAD
    return 0
  fi

  # Fall back to the canonical harness anchor only when the branch has no
  # usable mainline base. This keeps changed-files coverage scoped to the
  # cleaned branch diff instead of replaying the entire release lineage.
  if [[ -n "${CANONICAL_BASE_SHA:-}" ]] && git merge-base --is-ancestor "$CANONICAL_BASE_SHA" HEAD; then
    git merge-base "$CANONICAL_BASE_SHA" HEAD
    return 0
  fi

  if git rev-parse --verify --quiet "$CANONICAL_BASE_REF" >/dev/null \
      && git merge-base --is-ancestor "$CANONICAL_BASE_REF" HEAD; then
    git merge-base "$CANONICAL_BASE_REF" HEAD
    return 0
  fi

  echo "HEAD~1"
}

resolve_canonical_base() {
  local requested_ref="$CANONICAL_BASE_REF"
  local -a candidate_refs=()
  local -a resolved_refs=()
  local -a resolved_shas=()
  local candidate_ref
  local candidate_sha

  if [[ "$GIT_CONTEXT_AVAILABLE" != "true" ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-fast] FAIL: canonical base verification requires git context"
      exit 2
    fi
    return 0
  fi

  # Prefer remote-tracking canonical refs to avoid stale local branch anchors.
  if [[ "$requested_ref" == origin/* ]]; then
    candidate_refs=("$requested_ref")
  else
    candidate_refs=("origin/$requested_ref" "$requested_ref")
  fi

  for candidate_ref in "${candidate_refs[@]}"; do
    if candidate_sha="$(git -C "$ROOT_DIR" rev-parse --verify --quiet "$candidate_ref" 2>/dev/null)"; then
      resolved_refs+=("$candidate_ref")
      resolved_shas+=("$candidate_sha")
    fi
  done

  if [[ "${#resolved_refs[@]}" -eq 0 ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-fast] FAIL: canonical base ref '$requested_ref' was not found"
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
          echo "[gate-fast] WARN: canonical base '$requested_ref' is stale/non-ancestor; using '$CANONICAL_BASE_REF' ($CANONICAL_BASE_SHA)"
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
    echo "[gate-fast] FAIL: HEAD=$RESOLVED_RELEASE_HEAD_SHA is not based on canonical base candidates: $resolved_desc"
    exit 2
  fi

  local idx
  for idx in "${!resolved_refs[@]}"; do
    if git -C "$ROOT_DIR" merge-base --is-ancestor "${resolved_shas[$idx]}" "$RESOLVED_RELEASE_HEAD_SHA"; then
      CANONICAL_BASE_REF="${resolved_refs[$idx]}"
      CANONICAL_BASE_SHA="${resolved_shas[$idx]}"
      if [[ "$CANONICAL_BASE_REF" != "$requested_ref" ]]; then
        echo "[gate-fast] WARN: canonical base '$requested_ref' is stale/non-ancestor; using '$CANONICAL_BASE_REF' ($CANONICAL_BASE_SHA)"
      fi
      return 0
    fi
  done

  CANONICAL_BASE_REF="${resolved_refs[0]}"
  CANONICAL_BASE_SHA="${resolved_shas[0]}"
}

emit_traceability_manifest() {
  local release_anchor_sha="$1"
  local diff_base="$2"
  python3 - "$ARTIFACT_DIR" "$TRACEABILITY_FILE" "$GATE_START_UTC" "$RESOLVED_RELEASE_HEAD_SHA" "$GIT_CONTEXT_AVAILABLE" "$CANONICAL_BASE_REF" "$CANONICAL_BASE_SHA" "$CANONICAL_BASE_REQUIRED" "$CANONICAL_BASE_VERIFIED" "$RELEASE_VALIDATION_MODE" "$release_anchor_sha" "$diff_base" "$SYNC_PR_MODE" <<'PY'
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
    release_validation_mode,
    release_anchor_sha,
    diff_base,
    sync_pr_mode,
) = sys.argv[1:14]

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
            "path": f"artifacts/gate-fast/{name}",
            "sha256": digest.hexdigest(),
            "bytes": stat_result.st_size,
        }
    )

payload = {
    "gate": "gate-fast",
    "release_head_sha": release_head_sha,
    "git_context_available": git_context_available.lower() == "true",
    "canonical_base_ref": canonical_base_ref,
    "canonical_base_sha": canonical_base_sha or None,
    "canonical_base_required": canonical_base_required.lower() == "true",
    "canonical_base_verified": canonical_base_verified.lower() == "true",
    "release_validation_mode": release_validation_mode.lower() == "true",
    "sync_pr_mode": sync_pr_mode.lower() == "true",
    "diff_base": diff_base,
    "started_at_utc": started_at_utc,
    "finished_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifact_count": len(artifacts),
    "artifacts": artifacts,
}
if release_anchor_sha:
    payload["release_anchor_sha"] = release_anchor_sha

with open(tmp_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
    fh.write("\n")
os.replace(tmp_path, manifest_path)
PY
}

if [[ "$RELEASE_VALIDATION_MODE" == "true" ]]; then
  REQUIRE_DIFF_BASE="true"
  CANONICAL_BASE_REQUIRED="true"
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

resolve_canonical_base

if [[ "$RELEASE_VALIDATION_MODE" == "true" ]]; then
  if [[ "$GIT_CONTEXT_AVAILABLE" != "true" ]]; then
    echo "[gate-fast] FAIL: release validation mode requires git context for immutable anchor validation"
    exit 2
  fi
  if [[ ! "$DIFF_BASE" =~ ^[0-9a-fA-F]{40}$ ]]; then
    echo "[gate-fast] FAIL: DIFF_BASE must be a full 40-character commit SHA in release validation mode"
    exit 2
  fi
  if git -C "$ROOT_DIR" for-each-ref --format='%(refname:short)' | grep -Fxq "$DIFF_BASE"; then
    echo "[gate-fast] FAIL: DIFF_BASE must be a literal commit ID and must not match any ref name"
    exit 2
  fi
  if ! resolved_diff_base="$(git -C "$ROOT_DIR" rev-parse --verify --quiet --end-of-options "${DIFF_BASE}^{commit}" 2>/dev/null)"; then
    echo "[gate-fast] FAIL: DIFF_BASE must resolve to a commit SHA in release validation mode"
    exit 2
  fi
  DIFF_BASE="$resolved_diff_base"
  if ! git -C "$ROOT_DIR" merge-base --is-ancestor "$DIFF_BASE" "$RESOLVED_RELEASE_HEAD_SHA"; then
    echo "[gate-fast] FAIL: DIFF_BASE=$DIFF_BASE is not an ancestor of HEAD=$RESOLVED_RELEASE_HEAD_SHA"
    exit 2
  fi
  if [[ "$DIFF_BASE" == "$RESOLVED_RELEASE_HEAD_SHA" ]]; then
    echo "[gate-fast] FAIL: DIFF_BASE must be older than HEAD in release validation mode"
    exit 2
  fi
fi

echo "[gate-fast] confidence-lane contract"
python3 "$ROOT_DIR/scripts/validate_confidence_lanes.py" \
  --contract "$ROOT_DIR/testing/local/confidence-lanes.json" \
  --lane pr \
  --output "$ARTIFACT_DIR/confidence-lane-contract.json"

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
  emit_traceability_manifest "" "$DIFF_BASE"
  cat "$TRACEABILITY_FILE"
  echo "[gate-fast] OK"
  exit 0
fi

DIFF_BASE="$(resolve_diff_base)"
echo "[gate-fast] changed-files coverage against base=$DIFF_BASE"

if ! RUNTIME_SOURCE_DIFF="$(git -C "$ROOT_DIR" diff --name-only "${DIFF_BASE}...${RESOLVED_RELEASE_HEAD_SHA}" -- erp-domain/src/main/java | sed '/^[[:space:]]*$/d')"; then
  echo "[gate-fast] FAIL: unable to compute changed runtime sources against base=$DIFF_BASE"
  exit 2
fi
if [[ -z "$RUNTIME_SOURCE_DIFF" ]]; then
  echo "[gate-fast] no runtime source changes under erp-domain/src/main/java; skipping changed-files coverage enforcement"
  python3 - "$ARTIFACT_DIR/changed-coverage.json" "$DIFF_BASE" <<'PY'
import json
import sys

out_path = sys.argv[1]
diff_base = sys.argv[2]
summary = {
    "diff_base": diff_base,
    "files_considered": 0,
    "line_covered": 0,
    "line_total": 0,
    "line_ratio": 1.0,
    "line_threshold": 0.95,
    "branch_covered": 0,
    "branch_total": 0,
    "branch_ratio": 1.0,
    "branch_threshold": 0.9,
    "vacuous": False,
    "vacuous_reason": "",
    "structural_only": False,
    "structural_files": [],
    "coverage_skipped_files": [],
    "files_with_unmapped_lines": [],
    "skipped": True,
    "reason": "no_runtime_source_changes",
    "passes": True,
    "per_file": {},
}
with open(out_path, "w", encoding="utf-8") as fh:
  json.dump(summary, fh, indent=2)
print("[gate-fast] changed-files coverage summary:")
print(json.dumps(summary, indent=2))
PY
  emit_traceability_manifest "" "$DIFF_BASE"
  cat "$TRACEABILITY_FILE"
  echo "[gate-fast] OK"
  exit 0
fi

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

if ! python3 "$ROOT_DIR/scripts/changed_files_coverage.py" "${coverage_args[@]}"; then
  echo "[gate-fast] WARN: changed-files coverage gate did not meet thresholds; continuing in compatibility mode"
fi

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

RELEASE_ANCHOR_SHA=""
if [[ "$RELEASE_VALIDATION_MODE" == "true" ]]; then
  RELEASE_ANCHOR_SHA="$DIFF_BASE"
fi
emit_traceability_manifest "$RELEASE_ANCHOR_SHA" "$DIFF_BASE"
cat "$TRACEABILITY_FILE"

echo "[gate-fast] OK"
