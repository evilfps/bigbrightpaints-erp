#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

R2_FILE="docs/approvals/R2-CHECKPOINT.md"

echo "[enterprise-policy] canonical workflow decision contract guard"
bash "$ROOT_DIR/scripts/guard_workflow_canonical_paths.sh"

resolve_diff_base() {
  if [[ -n "${ENTERPRISE_DIFF_BASE:-}" ]] && git rev-parse --verify --quiet "$ENTERPRISE_DIFF_BASE" >/dev/null; then
    echo "$ENTERPRISE_DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]] && git rev-parse --verify --quiet "$GITHUB_BASE_SHA" >/dev/null; then
    echo "$GITHUB_BASE_SHA"
    return 0
  fi

  if [[ -n "${ARCH_DIFF_BASE:-}" ]] && git rev-parse --verify --quiet "$ARCH_DIFF_BASE" >/dev/null; then
    echo "$ARCH_DIFF_BASE"
    return 0
  fi

  if git rev-parse --verify --quiet origin/main >/dev/null; then
    git merge-base origin/main HEAD
    return 0
  fi

  echo ""
}

BASE="$(resolve_diff_base)"
if [[ -n "$BASE" ]]; then
  range_changes="$(git diff --name-only "$BASE"...HEAD || true)"
  worktree_changes="$(git diff --name-only || true)"
  untracked_changes="$(git ls-files --others --exclude-standard || true)"
  changed_files="$(printf '%s\n%s\n%s\n' "$range_changes" "$worktree_changes" "$untracked_changes" | sed '/^$/d' | sort -u)"
  mode="range"
else
  worktree_changes="$(git diff --name-only || true)"
  untracked_changes="$(git ls-files --others --exclude-standard || true)"
  changed_files="$(printf '%s\n%s\n' "$worktree_changes" "$untracked_changes" | sed '/^$/d' | sort -u)"
  mode="working_tree"
fi

if [[ -z "$changed_files" ]]; then
  echo "[enterprise-policy] OK: no changed files detected ($mode mode)"
  exit 0
fi

high_risk_regex='^(erp-domain/src/main/resources/db/migration_v2/|erp-domain/src/main/java/com/bigbrightpaints/erp/modules/(auth|rbac|company|hr|accounting)/|erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/)'

if ! grep -Eq "$high_risk_regex" <<< "$changed_files"; then
  echo "[enterprise-policy] OK: no high-risk paths changed ($mode mode)"
  exit 0
fi

echo "[enterprise-policy] high-risk paths changed; enforcing R2 enterprise controls"

if [[ ! -f "$R2_FILE" ]]; then
  echo "[enterprise-policy] FAIL: missing $R2_FILE for high-risk change set." >&2
  echo "[enterprise-policy] remediation: create from docs/approvals/R2-CHECKPOINT-TEMPLATE.md and fill all required sections." >&2
  exit 1
fi

if [[ -n "$BASE" ]] && ! grep -Fxq "$R2_FILE" <<< "$changed_files"; then
  echo "[enterprise-policy] FAIL: high-risk change set without updated $R2_FILE." >&2
  echo "[enterprise-policy] remediation: include scope-specific R2 approval evidence in the same change." >&2
  exit 1
fi

required_headers=(
  "## Scope"
  "## Risk Trigger"
  "## Approval Authority"
  "## Escalation Decision"
  "## Rollback Owner"
  "## Expiry"
  "## Verification Evidence"
)

for h in "${required_headers[@]}"; do
  if ! grep -Fq "$h" "$R2_FILE"; then
    echo "[enterprise-policy] FAIL: $R2_FILE missing required section '$h'." >&2
    echo "[enterprise-policy] remediation: follow docs/approvals/R2-CHECKPOINT-TEMPLATE.md exactly." >&2
    exit 1
  fi
done

if grep -Fxq -- '- Why this is R2: unspecified' "$R2_FILE"; then
  echo "[enterprise-policy] FAIL: $R2_FILE has assumption placeholder for R2 rationale." >&2
  echo "[enterprise-policy] remediation: replace placeholder with evidence-backed rationale." >&2
  exit 1
fi

if ! grep -Eq -- '^- Mode: (orchestrator|human)$' "$R2_FILE"; then
  echo "[enterprise-policy] FAIL: $R2_FILE must declare approval mode as orchestrator or human." >&2
  echo "[enterprise-policy] remediation: set '- Mode: orchestrator' or '- Mode: human' in Approval Authority." >&2
  exit 1
fi

if ! grep -Eq -- '^- Human escalation required: (yes|no)$' "$R2_FILE"; then
  echo "[enterprise-policy] FAIL: $R2_FILE must declare escalation decision (yes|no)." >&2
  echo "[enterprise-policy] remediation: set '- Human escalation required: yes|no' with reason." >&2
  exit 1
fi

if grep -Fxq -- '- Commands run: unspecified' "$R2_FILE" \
  || grep -Fxq -- '- Result summary: unspecified' "$R2_FILE" \
  || grep -Fxq -- '- Artifacts/links: unspecified' "$R2_FILE"; then
  echo "[enterprise-policy] FAIL: verification evidence is incomplete (unspecified)." >&2
  echo "[enterprise-policy] remediation: add concrete proof artifacts (commands, outcomes, and links/paths)." >&2
  exit 1
fi

if grep -Eq '^erp-domain/src/main/resources/db/migration_v2/' <<< "$changed_files"; then
  if ! grep -Fxq 'docs/runbooks/migrations.md' <<< "$changed_files"; then
    echo "[enterprise-policy] FAIL: migration_v2 changed without docs/runbooks/migrations.md update." >&2
    echo "[enterprise-policy] remediation: document forward plan, dry-run commands, and rollback strategy." >&2
    exit 1
  fi
  if ! grep -Fxq 'docs/runbooks/rollback.md' <<< "$changed_files"; then
    echo "[enterprise-policy] FAIL: migration_v2 changed without docs/runbooks/rollback.md update." >&2
    echo "[enterprise-policy] remediation: document tested rollback path for this migration set." >&2
    exit 1
  fi
fi

main_high_risk_changed=0
if grep -Eq '^erp-domain/src/main/java/com/bigbrightpaints/erp/(modules/(auth|rbac|company|hr|accounting)|orchestrator)/' <<< "$changed_files"; then
  main_high_risk_changed=1
fi

if [[ "$main_high_risk_changed" -eq 1 ]]; then
  if ! grep -Eq '^erp-domain/src/test/java/' <<< "$changed_files"; then
    if ! grep -Fq '## Test Waiver (Only if no tests changed)' "$R2_FILE"; then
      echo "[enterprise-policy] FAIL: high-risk logic changed without tests or explicit waiver." >&2
      echo "[enterprise-policy] remediation: add/modify tests under erp-domain/src/test/java or include justified waiver section in $R2_FILE." >&2
      exit 1
    fi
  fi
fi

echo "[enterprise-policy] OK"
