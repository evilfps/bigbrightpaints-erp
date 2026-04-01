#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

ALLOWLIST="ci/architecture/module-import-allowlist.txt"

resolve_diff_base() {
  if [[ -n "${ARCH_DIFF_BASE:-}" ]] && git rev-parse --verify --quiet "$ARCH_DIFF_BASE" >/dev/null; then
    echo "$ARCH_DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]] && git rev-parse --verify --quiet "$GITHUB_BASE_SHA" >/dev/null; then
    echo "$GITHUB_BASE_SHA"
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
  changed_files="$(git diff --name-only "$BASE"...HEAD || true)"
  mode="range"
else
  changed_files="$(git diff --name-only || true)"
  mode="working_tree"
fi

if ! printf '%s\n' "$changed_files" | grep -Fxq "$ALLOWLIST"; then
  echo "[allowlist-evidence] OK: allowlist not changed ($mode mode)"
  exit 0
fi

# Require docs/ARCHITECTURE.md change alongside allowlist update.
# Match case-insensitively because git may track the file as architecture.md or ARCHITECTURE.md
# depending on platform history.
if ! printf '%s\n' "$changed_files" | grep -Fixq "docs/ARCHITECTURE.md"; then
  echo "[allowlist-evidence] FAIL: allowlist changed without docs/ARCHITECTURE.md update." >&2
  echo "[allowlist-evidence] remediation: update docs/ARCHITECTURE.md with the new boundary decision." >&2
  exit 1
fi

adr_candidates="$(printf '%s\n' "$changed_files" | grep -E '^docs/adr[s]?/ADR-.*allowlist.*\.md$' || true)"
if [[ -z "$adr_candidates" ]]; then
  echo "[allowlist-evidence] FAIL: allowlist changed without ADR evidence file." >&2
  echo "[allowlist-evidence] remediation: add docs/adr/ADR-*-allowlist-*.md (or docs/adrs/ADR-*-allowlist-*.md) including Why Needed / Alternatives Rejected / Boundary Preserved." >&2
  exit 1
fi

valid=0
while IFS= read -r adr_file; do
  [[ -z "$adr_file" ]] && continue
  if [[ ! -f "$adr_file" ]]; then
    continue
  fi
  if grep -Fq '## Why Needed' "$adr_file" \
    && grep -Fq '## Alternatives Rejected' "$adr_file" \
    && grep -Fq '## Boundary Preserved' "$adr_file"; then
    valid=1
    break
  fi
done <<< "$adr_candidates"

if [[ "$valid" -ne 1 ]]; then
  echo "[allowlist-evidence] FAIL: ADR evidence file is missing required sections." >&2
  echo "[allowlist-evidence] required headings: ## Why Needed, ## Alternatives Rejected, ## Boundary Preserved" >&2
  exit 1
fi

echo "[allowlist-evidence] OK"
