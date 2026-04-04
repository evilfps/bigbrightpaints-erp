#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

resolve_diff_base() {
  if [[ -n "${REVIEW_POLICY_DIFF_BASE:-}" ]]; then
    echo "$REVIEW_POLICY_DIFF_BASE"
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

  if git rev-parse --verify --quiet HEAD~1 >/dev/null; then
    echo "HEAD~1"
    return 0
  fi

  echo "HEAD"
}

is_docs_only_path() {
  local path="$1"
  case "$path" in
    README.md|AGENTS.md|ARCHITECTURE.md|CHANGELOG.md|docs/INDEX.md|docs/ARCHITECTURE.md|docs/CONVENTIONS.md|docs/SECURITY.md|docs/RELIABILITY.md|docs/BACKEND-FEATURE-CATALOG.md|docs/RECOMMENDATIONS.md|docs/adrs/*|docs/agents/*|docs/approvals/*|docs/deprecated/*|docs/modules/*|docs/flows/*|docs/frontend-api/*|docs/frontend-portals/*|.factory/library/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

diff_base="$(resolve_diff_base)"
if ! git rev-parse --verify --quiet "${diff_base}^{commit}" >/dev/null; then
  echo "[codex-review-policy] unable to resolve diff base '$diff_base'; running strict review checks"
  bash ci/check-codex-review-guidelines.sh
  exit 0
fi

compare_range="${diff_base}...HEAD"
changed_files=()
while IFS= read -r line; do
  [[ -n "$line" ]] && changed_files+=("$line")
done < <(git diff --name-only "$compare_range")

if [[ "${#changed_files[@]}" -eq 0 ]]; then
  echo "[codex-review-policy] no changed files in $compare_range; running strict review checks"
  bash ci/check-codex-review-guidelines.sh
  exit 0
fi

docs_only=true
non_docs_paths=()
for path in "${changed_files[@]}"; do
  if ! is_docs_only_path "$path"; then
    docs_only=false
    non_docs_paths+=("$path")
  fi
done

if [[ "$docs_only" == "true" ]]; then
  echo "[codex-review-policy] docs-only change-set detected in $compare_range"
  echo "[codex-review-policy] policy: run knowledgebase lint only and skip Codex review/subagent/runtime validators"
  echo "[codex-review-policy] lane: canonical docs/governance and internal .factory/library guidance packets only"
  bash ci/lint-knowledgebase.sh
  exit 0
fi

echo "[codex-review-policy] runtime/config/schema/test impact detected; running strict review checks"
printf '[codex-review-policy] non-doc path: %s\n' "${non_docs_paths[@]}"
bash ci/check-codex-review-guidelines.sh
