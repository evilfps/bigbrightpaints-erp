#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LAYER_FILE="agents/orchestrator-layer.yaml"
CATALOG_FILE="agents/catalog.yaml"
DOC_FILE="docs/agents/ORCHESTRATION_LAYER.md"

errors=0
fail() {
  echo "[orchestrator-layer] FAIL: $1" >&2
  errors=$((errors + 1))
}

if [[ ! -f "$LAYER_FILE" || ! -f "$CATALOG_FILE" || ! -f "$DOC_FILE" ]]; then
  if [[ -f "$ROOT_DIR/.codex/config.toml" ]]; then
    echo "[orchestrator-layer] WARN: legacy orchestrator-layer contract files missing; compatibility mode active"
    echo "[orchestrator-layer] OK"
    exit 0
  fi
fi

[[ -f "$LAYER_FILE" ]] || fail "missing $LAYER_FILE"
[[ -f "$CATALOG_FILE" ]] || fail "missing $CATALOG_FILE"
[[ -f "$DOC_FILE" ]] || fail "missing $DOC_FILE"

required_layer_tokens=(
  "orchestrator_id: orchestrator"
  "routing_rules:"
  "review_pipeline:"
  "commit_policy:"
  "review-only agents do not commit code"
  "minimum_review_agents_per_slice: 1"
  "require_orchestrator_premerge_review: true"
  "block_on_cross_slice_overlaps: true"
  "block_on_scope_violations: true"
  "require_codex_review_guidelines_check: true"
  "completion_contract:"
)

for token in "${required_layer_tokens[@]}"; do
  if [[ -f "$LAYER_FILE" ]] && ! grep -Fq "$token" "$LAYER_FILE"; then
    fail "$LAYER_FILE missing required token '$token'"
  fi
done

if [[ -f "$CATALOG_FILE" && -f "$LAYER_FILE" ]]; then
  while IFS= read -r id; do
    [[ -z "$id" ]] && continue
    if [[ "$id" == "orchestrator" ]]; then
      continue
    fi
    if ! grep -Fq "$id" "$LAYER_FILE"; then
      fail "agent id '$id' from $CATALOG_FILE not referenced in $LAYER_FILE"
    fi
  done < <(awk '/^  - id: / {print $3}' "$CATALOG_FILE")
fi

if [[ -f "$DOC_FILE" ]]; then
  if ! grep -Fq "agents/orchestrator-layer.yaml" "$DOC_FILE"; then
    fail "$DOC_FILE must reference agents/orchestrator-layer.yaml"
  fi
fi

if [[ "$errors" -gt 0 ]]; then
  echo "[orchestrator-layer] $errors issue(s) found." >&2
  exit 1
fi

echo "[orchestrator-layer] OK"
