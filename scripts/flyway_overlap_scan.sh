#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v1}"
FAIL_ON_FINDINGS="${FAIL_ON_FINDINGS:-false}"

usage() {
  cat <<USAGE
Usage: bash scripts/flyway_overlap_scan.sh [--migration-set <v1|v2>]
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migration-set)
      MIGRATION_SET="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[flyway_overlap_scan] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$MIGRATION_SET" in
  v1)
    MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration"
    ALLOWLIST_FILE="$ROOT_DIR/scripts/flyway_overlap_allowlist.txt"
    ;;
  v2)
    MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration_v2"
    ALLOWLIST_FILE="$ROOT_DIR/scripts/flyway_overlap_allowlist_v2.txt"
    ;;
  *)
    echo "[flyway_overlap_scan] invalid migration set: $MIGRATION_SET (expected v1 or v2)" >&2
    exit 2
    ;;
esac

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
  echo "[flyway_overlap_scan] missing migrations dir: $MIGRATIONS_DIR"
  exit 2
fi

export MIGRATIONS_DIR FAIL_ON_FINDINGS ALLOWLIST_FILE

python3 - <<'PY'
import collections
import glob
import os
import re
import sys

migrations_dir = os.environ["MIGRATIONS_DIR"]
fail_on_findings = os.environ.get("FAIL_ON_FINDINGS", "false").lower() == "true"
allowlist_file = os.environ.get("ALLOWLIST_FILE", "")

allowlist: set[tuple[str, str]] = set()
if allowlist_file and os.path.exists(allowlist_file):
    with open(allowlist_file, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            if ":" not in line:
                continue
            kind, name = line.split(":", 1)
            allowlist.add((kind.strip().lower(), name.strip().lower()))

files = sorted(glob.glob(os.path.join(migrations_dir, "V*__*.sql")))
if not files:
    print(f"[flyway_overlap_scan] no migrations found under: {migrations_dir}")
    sys.exit(2)

patterns = {
    "table": re.compile(
        r'\bCREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+("?)([A-Za-z0-9_.]+)\1',
        re.IGNORECASE,
    ),
    "index": re.compile(
        r'\bCREATE\s+(?:UNIQUE\s+)?INDEX(?:\s+IF\s+NOT\s+EXISTS)?\s+("?)([A-Za-z0-9_.]+)\1',
        re.IGNORECASE,
    ),
    "constraint": re.compile(r'\bCONSTRAINT\s+("?)(?!IF\b)([A-Za-z0-9_.]+)\1', re.IGNORECASE),
}

found: dict[str, dict[str, set[str]]] = {
    kind: collections.defaultdict(set) for kind in patterns.keys()
}

for path in files:
    base = os.path.basename(path)
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                stripped = line.strip()
                if stripped.startswith("--"):
                    continue
                for kind, pat in patterns.items():
                    m = pat.search(line)
                    if not m:
                        continue
                    name = m.group(2).lower()
                    found[kind][name].add(base)
    except OSError as e:
        print(f"[flyway_overlap_scan] failed to read {path}: {e}")
        sys.exit(2)

findings = 0
print(f"[flyway_overlap_scan] scanning: {migrations_dir}")
if allowlist:
    print(f"[flyway_overlap_scan] allowlist entries: {len(allowlist)}")

for kind in ("table", "constraint", "index"):
    print()
    print(f"[flyway_overlap_scan] duplicate {kind} definitions (heuristic)")
    dups = {
        name: sorted(list(fs))
        for name, fs in found[kind].items()
        if len(fs) > 1 and (kind, name) not in allowlist
    }
    if not dups:
        print("  (none)")
        continue
    findings = 1
    for name, fs in sorted(dups.items()):
        print(f"  {name}: {', '.join(fs)}")

print()
print(f"[flyway_overlap_scan] scanned {len(files)} migrations (findings={findings})")

if fail_on_findings and findings:
    sys.exit(1)
sys.exit(0)
PY
