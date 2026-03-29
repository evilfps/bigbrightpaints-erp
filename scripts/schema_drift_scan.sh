#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v2}"
MIGRATIONS_DIR="${MIGRATIONS_DIR:-}"
ALLOWLIST_FILE="${ALLOWLIST_FILE:-}"

FAIL_ON_FINDINGS="${FAIL_ON_FINDINGS:-false}"

usage() {
  cat <<USAGE
Usage: bash scripts/schema_drift_scan.sh [--migration-set <v2>] [--migrations-dir <dir>] [--allowlist <file>]
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migration-set)
      MIGRATION_SET="${2:-}"
      shift 2
      ;;
    --migrations-dir)
      MIGRATIONS_DIR="${2:-}"
      shift 2
      ;;
    --allowlist)
      ALLOWLIST_FILE="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[schema_drift_scan] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$MIGRATIONS_DIR" ]]; then
  if [[ "$MIGRATION_SET" != "v2" ]]; then
    echo "[schema_drift_scan] invalid --migration-set: $MIGRATION_SET (expected v2 only)" >&2
    exit 2
  fi
  MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration_v2"
fi

if [[ -z "$ALLOWLIST_FILE" ]]; then
  ALLOWLIST_FILE="$ROOT_DIR/scripts/schema_drift_scan_allowlist_v2.txt"
fi

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
echo "[schema_drift_scan] migration set: $MIGRATION_SET"
echo "[schema_drift_scan] using: $SEARCH_TOOL"

findings=0
allowlist=()

if [[ -f "$ALLOWLIST_FILE" ]]; then
  while IFS= read -r raw; do
    # Strip comments and trim whitespace.
    line="${raw%%#*}"
    line="$(echo "$line" | xargs)"
    if [[ -n "$line" ]]; then
      allowlist+=("$line")
    fi
  done < "$ALLOWLIST_FILE"
  echo "[schema_drift_scan] allowlist entries: ${#allowlist[@]}"
fi

is_allowlisted() {
  local file="$1"
  local relative_file
  relative_file="${file#"$ROOT_DIR"/}"
  relative_file="${relative_file#./}"
  if [[ "${#allowlist[@]}" -eq 0 ]]; then
    return 1
  fi
  for entry in "${allowlist[@]}"; do
    if [[ "$file" == "$entry" || "$relative_file" == "$entry" ]]; then
      return 0
    fi
  done
  return 1
}

scan_pattern() {
  local label="$1"
  local pattern="$2"
  local tmp_file
  echo
  echo "[schema_drift_scan] $label"
  tmp_file="$(mktemp "${TMPDIR:-/tmp}/schema_drift_scan.XXXXXX")"
  trap 'rm -f "$tmp_file"' RETURN
  if [[ "$SEARCH_TOOL" == "rg" ]]; then
    rg -n --glob '*.sql' "$pattern" "$MIGRATIONS_DIR" >"$tmp_file" 2>/dev/null || true
  else
    grep -RIn --include='*.sql' -e "$pattern" "$MIGRATIONS_DIR" >"$tmp_file" 2>/dev/null || true
  fi
  if [[ -s "$tmp_file" ]]; then
    local printed=false
    while IFS= read -r line; do
      local file="${line%%:*}"
      if is_allowlisted "$file"; then
        continue
      fi
      printed=true
      echo "$line"
    done < "$tmp_file"
    if [[ "$printed" == "true" ]]; then
      findings=1
    else
      echo "  (none)"
    fi
  else
    echo "  (none)"
  fi
  trap - RETURN
  rm -f "$tmp_file"
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
    rg -l --glob '*.sql' -i "^[[:space:]]*UPDATE[[:space:]]+" "$MIGRATIONS_DIR" || true
  else
    grep -RIl --include='*.sql' -i -E '^[[:space:]]*UPDATE[[:space:]]+' "$MIGRATIONS_DIR" || true
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
      if grep -qi -E '(^|[^[:alnum:]_])FROM([^[:alnum:]_]|$)' "$f"; then
        has_from=true
      fi
    fi
    if [[ "$has_from" == "true" ]]; then
      if is_allowlisted "$f"; then
        continue
      fi
      flagged=true
      findings=1
      echo "  $f"
      # Show a small excerpt around UPDATE statements.
      if [[ "$SEARCH_TOOL" == "rg" ]]; then
        rg -n --glob '*.sql' -i "^[[:space:]]*UPDATE[[:space:]]+|\\bFROM\\b" "$f" | head -n 30 || true
      else
        grep -n -i -E '^[[:space:]]*UPDATE[[:space:]]+|(^|[^[:alnum:]_])FROM([^[:alnum:]_]|$)' "$f" | head -n 30 || true
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
seen_keys=()
seen_values=()
dups=0
shopt -s nullglob
for f in "$MIGRATIONS_DIR"/V*__*.sql; do
  base="$(basename "$f")"
  desc="${base#*__}"
  desc="${desc%.sql}"
  key="$(printf '%s' "$desc" | tr '[:upper:]' '[:lower:]')"
  seen_base=""
  for i in "${!seen_keys[@]}"; do
    if [[ "${seen_keys[$i]}" == "$key" ]]; then
      seen_base="${seen_values[$i]}"
      break
    fi
  done
  if [[ -n "$seen_base" ]]; then
    echo "  DUP: $desc -> $base and $seen_base"
    dups=1
  else
    seen_keys+=("$key")
    seen_values+=("$base")
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
