#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCAN_FILE="$ROOT_DIR/scripts/db_predeploy_scans.sql"
DB_URL=""
OUTPUT_FILE=""

usage() {
  cat <<USAGE
Usage: bash scripts/run_db_predeploy_scans.sh --db-url <postgres-url> [--output <path>] [--scan-file <path>]

Options:
  --db-url     Postgres connection URL accepted by psql.
  --output     Optional output file to persist scan findings.
  --scan-file  Optional SQL file (default: scripts/db_predeploy_scans.sql)
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --db-url)
      DB_URL="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_FILE="${2:-}"
      shift 2
      ;;
    --scan-file)
      SCAN_FILE="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[run_db_predeploy_scans] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$DB_URL" ]]; then
  echo "[run_db_predeploy_scans] --db-url is required" >&2
  exit 2
fi

if [[ ! -f "$SCAN_FILE" ]]; then
  echo "[run_db_predeploy_scans] scan file not found: $SCAN_FILE" >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "[run_db_predeploy_scans] psql is required" >&2
  exit 2
fi

raw_output="$(psql "$DB_URL" -v ON_ERROR_STOP=1 -qAt -f "$SCAN_FILE")"
findings="$(printf '%s\n' "$raw_output" | sed '/^$/d')"

if [[ -n "$OUTPUT_FILE" ]]; then
  mkdir -p "$(dirname "$OUTPUT_FILE")"
  printf '%s\n' "$findings" > "$OUTPUT_FILE"
fi

if [[ -n "$findings" ]]; then
  echo "[run_db_predeploy_scans] FAIL: findings detected"
  printf '%s\n' "$findings"
  exit 1
fi

echo "[run_db_predeploy_scans] OK: zero findings"
