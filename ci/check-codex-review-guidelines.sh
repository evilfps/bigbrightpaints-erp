#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

errors=0
fail() {
  echo "[codex-review-guidelines] FAIL: $1" >&2
  errors=$((errors + 1))
}

if [[ ! -f AGENTS.md ]]; then
  fail "AGENTS.md missing"
else
  if ! grep -Fq '## Review Guidelines (Required)' AGENTS.md; then
    fail "AGENTS.md missing required review-guidelines section"
  fi
  if ! grep -Fq 'R2' AGENTS.md; then
    fail "AGENTS.md missing explicit R2 escalation checkpoint"
  fi
fi

for f in docs/SECURITY.md docs/agents/PERMISSIONS.md docs/agents/CATALOG.md; do
  [[ -f "$f" ]] || fail "missing required policy doc: $f"
done

bash ci/lint-knowledgebase.sh
bash ci/check-architecture.sh
bash ci/check-enterprise-policy.sh
bash ci/check-orchestrator-layer.sh

if [[ "$errors" -gt 0 ]]; then
  echo "[codex-review-guidelines] $errors issue(s) found." >&2
  exit 1
fi

echo "[codex-review-guidelines] OK"
