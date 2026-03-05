#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_files=(
  "AGENTS.md"
  "ARCHITECTURE.md"
  "docs/INDEX.md"
  "docs/ARCHITECTURE.md"
  "docs/SECURITY.md"
  "docs/RELIABILITY.md"
  "docs/agents/CATALOG.md"
  "docs/agents/PERMISSIONS.md"
  "docs/agents/WORKFLOW.md"
  "docs/agents/ENTERPRISE_MODE.md"
  "docs/agents/ORCHESTRATION_LAYER.md"
  "docs/approvals/R2-CHECKPOINT.md"
  "docs/approvals/R2-CHECKPOINT-TEMPLATE.md"
  "docs/runbooks/rollback.md"
  "docs/runbooks/migrations.md"
  "agents/catalog.yaml"
  "agents/orchestrator-layer.yaml"
  "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md"
  "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/AGENTS.md"
  "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/AGENTS.md"
  "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/AGENTS.md"
)

errors=0

fail() {
  printf '[knowledgebase-lint] FAIL: %s\n' "$1" >&2
  errors=$((errors + 1))
}

compatibility_mode=false
legacy_contract_markers=(
  "AGENTS.md"
  "docs/INDEX.md"
  "agents/catalog.yaml"
)
for marker in "${legacy_contract_markers[@]}"; do
  if [[ ! -f "$marker" ]]; then
    compatibility_mode=true
    break
  fi
done

if [[ "$compatibility_mode" == "true" ]]; then
  compatibility_required=(
    "README.md"
    "docs/architecture.md"
    "docs/developer-guide.md"
  )
  for f in "${compatibility_required[@]}"; do
    if [[ ! -f "$f" ]]; then
      fail "missing compatibility doc: $f"
    fi
  done

  if [[ "$errors" -gt 0 ]]; then
    printf '[knowledgebase-lint] %d issue(s) found.\n' "$errors" >&2
    exit 1
  fi

  printf '[knowledgebase-lint] WARN: legacy knowledgebase contract files not present; running compatibility mode checks.\n'
  printf '[knowledgebase-lint] OK\n'
  exit 0
fi

is_valid_iso_date() {
  local candidate="$1"
  python3 - "$candidate" <<'PY'
import datetime
import sys

value = sys.argv[1]
try:
    datetime.date.fromisoformat(value)
except ValueError:
    sys.exit(1)
PY
}

normalize_path() {
  local target="$1"
  python3 - "$target" <<'PY'
import os
import sys

print(os.path.normpath(os.path.abspath(sys.argv[1])))
PY
}

is_path_within_root() {
  local candidate="$1"
  case "$candidate" in
    "$ROOT_DIR" | "$ROOT_DIR"/*) return 0 ;;
    *) return 1 ;;
  esac
}

for f in "${required_files[@]}"; do
  if [[ ! -f "$f" ]]; then
    fail "missing required file: $f (remediation: create and link it from docs/INDEX.md)"
  fi
done

# Freshness markers for markdown files.
for f in "${required_files[@]}"; do
  if [[ "$f" == *.md ]]; then
    if ! grep -Eq '^Last reviewed: [0-9]{4}-[0-9]{2}-[0-9]{2}$' "$f"; then
      fail "$f is missing a valid 'Last reviewed: YYYY-MM-DD' marker (remediation: add near file top)"
      continue
    fi

    reviewed_date="$(grep -E '^Last reviewed: [0-9]{4}-[0-9]{2}-[0-9]{2}$' "$f" | head -n1 | sed 's/^Last reviewed: //')"
    if ! is_valid_iso_date "$reviewed_date"; then
      fail "$f has invalid Last reviewed date '$reviewed_date' (remediation: use real calendar date)"
      continue
    fi

    today="$(date +%F)"
    if [[ "$reviewed_date" > "$today" ]]; then
      fail "$f has future Last reviewed date '$reviewed_date' (remediation: set to <= $today)"
    fi
  fi
done

# docs/INDEX.md must link to canonical docs.
canonical_links=(
  "docs/ARCHITECTURE.md"
  "docs/SECURITY.md"
  "docs/RELIABILITY.md"
  "docs/agents/CATALOG.md"
  "docs/agents/PERMISSIONS.md"
  "docs/agents/WORKFLOW.md"
  "docs/agents/ENTERPRISE_MODE.md"
  "docs/agents/ORCHESTRATION_LAYER.md"
  "docs/approvals/R2-CHECKPOINT.md"
  "docs/approvals/R2-CHECKPOINT-TEMPLATE.md"
  "docs/runbooks/rollback.md"
  "docs/runbooks/migrations.md"
)
for link in "${canonical_links[@]}"; do
  if ! grep -Fq "$link" docs/INDEX.md; then
    fail "docs/INDEX.md does not reference $link (remediation: add canonical cross-link)"
  fi
done

check_ref() {
  local src_file="$1"
  local raw_ref="$2"
  local ref="$raw_ref"

  # ignore external/anchors/templates/wildcards/commands
  [[ "$ref" =~ ^https?:// ]] && return 0
  [[ "$ref" =~ ^# ]] && return 0
  [[ "$ref" == *"*"* ]] && return 0
  [[ "$ref" == *'${'* ]] && return 0
  [[ "$ref" == *"<"* || "$ref" == *">"* ]] && return 0
  [[ "$ref" == *" "* ]] && return 0

  # trim trailing punctuation
  ref="${ref%,}"
  ref="${ref%.}"

  # keep only likely path references
  if [[ ! "$ref" =~ / ]] && [[ ! "$ref" =~ \.(md|sh|ya?ml|json)$ ]] && [[ "$ref" != "AGENTS.md" ]] && [[ "$ref" != "ARCHITECTURE.md" ]]; then
    return 0
  fi

  local candidate_rel candidate_root
  if [[ "$ref" == /* ]]; then
    candidate_rel="$(normalize_path "$ROOT_DIR/${ref#/}")"
  else
    candidate_rel="$(normalize_path "$(dirname "$src_file")/$ref")"
  fi
  candidate_root="$(normalize_path "$ROOT_DIR/${ref#/}")"

  if ! is_path_within_root "$candidate_rel" && ! is_path_within_root "$candidate_root"; then
    return 0
  fi

  if [[ ! -e "$candidate_rel" && ! -e "$candidate_root" ]]; then
    fail "$src_file references missing path '$raw_ref' (remediation: fix link or create target)"
  fi
}

files_to_scan=(
  "AGENTS.md"
  "ARCHITECTURE.md"
  "docs/INDEX.md"
  "docs/ARCHITECTURE.md"
  "docs/SECURITY.md"
  "docs/RELIABILITY.md"
  "docs/agents/CATALOG.md"
  "docs/agents/PERMISSIONS.md"
  "docs/agents/WORKFLOW.md"
  "docs/agents/ENTERPRISE_MODE.md"
  "docs/agents/ORCHESTRATION_LAYER.md"
  "docs/approvals/R2-CHECKPOINT.md"
  "docs/approvals/R2-CHECKPOINT-TEMPLATE.md"
  "docs/runbooks/rollback.md"
  "docs/runbooks/migrations.md"
)

for file in "${files_to_scan[@]}"; do
  # Markdown links [text](path)
  while IFS= read -r ref; do
    check_ref "$file" "$ref"
  done < <(sed -n 's/.*](\([^)]*\)).*/\1/p' "$file")

  # Backtick path references.
  while IFS= read -r token; do
    check_ref "$file" "$token"
  done < <(awk -F'`' '{for(i=2;i<=NF;i+=2) print $i}' "$file")
done

if ! python3 scripts/check_ticket_status_parity.py; then
  fail "ticket status parity guard failed (remediation: reconcile ticket.yaml, SUMMARY.md, and TIMELINE.md statuses)"
fi

if [[ "$errors" -gt 0 ]]; then
  printf '[knowledgebase-lint] %d issue(s) found.\n' "$errors" >&2
  exit 1
fi

printf '[knowledgebase-lint] OK\n'
