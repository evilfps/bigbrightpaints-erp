#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration"

FAIL_ON_FINDINGS="${FAIL_ON_FINDINGS:-false}"

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
  echo "[schema_drift_scan] missing migrations dir: $MIGRATIONS_DIR"
  exit 2
fi

if command -v rg >/dev/null 2>&1; then
  SEARCH_TOOL="rg"
else
  SEARCH_TOOL="grep"
fi

echo "[schema_drift_scan] scanning: $MIGRATIONS_DIR"
echo "[schema_drift_scan] using: $SEARCH_TOOL"

findings=0

scan_pattern() {
  local label="$1"
  local pattern="$2"
  echo
  echo "[schema_drift_scan] $label"
  if [[ "$SEARCH_TOOL" == "rg" ]]; then
    rg -n --glob '*.sql' "$pattern" "$MIGRATIONS_DIR" >/tmp/schema_drift_scan.tmp 2>/dev/null || true
  else
    grep -RIn --include='*.sql' -e "$pattern" "$MIGRATIONS_DIR" >/tmp/schema_drift_scan.tmp 2>/dev/null || true
  fi
  if [[ -s /tmp/schema_drift_scan.tmp ]]; then
    cat /tmp/schema_drift_scan.tmp
    findings=1
  else
    echo "  (none)"
  fi
}

# Drift generators
scan_pattern "CREATE TABLE IF NOT EXISTS (drift generator)" "CREATE TABLE IF NOT EXISTS"
scan_pattern "CREATE INDEX IF NOT EXISTS (drift generator)" "CREATE INDEX IF NOT EXISTS"
scan_pattern "ALTER TABLE IF EXISTS (drift generator)" "ALTER TABLE IF EXISTS"

# Ambiguous backfills heuristic: files containing both UPDATE and FROM (review determinism)
echo
echo "[schema_drift_scan] UPDATE + FROM backfills (review determinism)"
update_files=()
while IFS= read -r f; do
  update_files+=("$f")
done < <(
  if [[ "$SEARCH_TOOL" == "rg" ]]; then
    rg -l --glob '*.sql' -i "\\bUPDATE\\b" "$MIGRATIONS_DIR" || true
  else
    grep -RIl --include='*.sql' -i -e "UPDATE" "$MIGRATIONS_DIR" || true
  fi
)

if [[ "${#update_files[@]}" -eq 0 ]]; then
  echo "  (none)"
else
  flagged=false
  for f in "${update_files[@]}"; do
    has_from=false
    if [[ "$SEARCH_TOOL" == "rg" ]]; then
      if rg -q -i "\\bFROM\\b" "$f"; then
        has_from=true
      fi
    else
      if grep -qi -e "FROM" "$f"; then
        has_from=true
      fi
    fi
    if [[ "$has_from" == "true" ]]; then
      flagged=true
      findings=1
      echo "  $f"
      # Show a small excerpt around UPDATE statements.
      if [[ "$SEARCH_TOOL" == "rg" ]]; then
        rg -n --glob '*.sql' -i "\\bUPDATE\\b|\\bFROM\\b" "$f" | head -n 30 || true
      else
        grep -n -i -E "UPDATE|FROM" "$f" | head -n 30 || true
      fi
      echo "  ---"
    fi
  done
  if [[ "$flagged" == "false" ]]; then
    echo "  (none)"
  fi
fi

# Duplicate version intent: same description used across multiple versions.
echo
echo "[schema_drift_scan] duplicate description intents"
declare -A seen
dups=0
shopt -s nullglob
for f in "$MIGRATIONS_DIR"/V*__*.sql; do
  base="$(basename "$f")"
  desc="${base#*__}"
  desc="${desc%.sql}"
  key="$(printf '%s' "$desc" | tr '[:upper:]' '[:lower:]')"
  if [[ -n "${seen[$key]:-}" ]]; then
    echo "  DUP: $desc -> $base and ${seen[$key]}"
    dups=1
  else
    seen[$key]="$base"
  fi
done
shopt -u nullglob
if [[ "$dups" -eq 0 ]]; then
  echo "  (none)"
else
  findings=1
fi

if [[ "$FAIL_ON_FINDINGS" == "true" && "$findings" -ne 0 ]]; then
  echo
  echo "[schema_drift_scan] FAIL_ON_FINDINGS=true and findings detected"
  exit 1
fi

echo
echo "[schema_drift_scan] done (findings=$findings)"
