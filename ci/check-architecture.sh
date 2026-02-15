#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_ROOT="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules"
ALLOWLIST="$ROOT_DIR/ci/architecture/module-import-allowlist.txt"

if [[ ! -d "$MODULE_ROOT" ]]; then
  echo "[architecture-check] FAIL: module root missing at $MODULE_ROOT" >&2
  echo "[architecture-check] remediation: update script path or create module root" >&2
  exit 1
fi

if [[ ! -f "$ALLOWLIST" ]]; then
  echo "[architecture-check] FAIL: allowlist missing at $ALLOWLIST" >&2
  echo "[architecture-check] remediation: add allowlist before running boundary checks" >&2
  exit 1
fi

echo "[architecture-check] allowlist evidence contract"
bash "$ROOT_DIR/ci/architecture/check-allowlist-change-evidence.sh"

edges_tmp="$(mktemp)"
allow_tmp="$(mktemp)"
violations_tmp="$(mktemp)"
trap 'rm -f "$edges_tmp" "$allow_tmp" "$violations_tmp"' EXIT

while IFS= read -r java_file; do
  rel="${java_file#${MODULE_ROOT}/}"
  src_module="${rel%%/*}"

  while IFS= read -r import_line; do
    dst_module="$(echo "$import_line" | sed -E 's/^import com\.bigbrightpaints\.erp\.modules\.([a-zA-Z0-9_]+)\..*/\1/')"
    if [[ -n "$dst_module" && "$dst_module" != "$src_module" ]]; then
      printf '%s->%s\n' "$src_module" "$dst_module" >> "$edges_tmp"
    fi
  done < <(grep -E '^import com\.bigbrightpaints\.erp\.modules\.[a-zA-Z0-9_]+\.' "$java_file" || true)
done < <(find "$MODULE_ROOT" -type f -name '*.java' | sort)

sort -u "$edges_tmp" -o "$edges_tmp"
grep -Ev '^\s*#|^\s*$' "$ALLOWLIST" | sort -u > "$allow_tmp"

comm -23 "$edges_tmp" "$allow_tmp" > "$violations_tmp" || true

if [[ -s "$violations_tmp" ]]; then
  echo "[architecture-check] FAIL: new cross-module imports detected:" >&2
  sed 's/^/  - /' "$violations_tmp" >&2
  echo "[architecture-check] remediation:" >&2
  echo "  1) Prefer domain facade/orchestrator path instead of direct new module import." >&2
  echo "  2) If intentional, document rationale in docs/ARCHITECTURE.md and add edge to $ALLOWLIST." >&2
  exit 1
fi

if ! grep -Fq 'Inter-Module Dependency Rules' "$ROOT_DIR/docs/ARCHITECTURE.md"; then
  echo "[architecture-check] FAIL: docs/ARCHITECTURE.md missing 'Inter-Module Dependency Rules' section." >&2
  echo "[architecture-check] remediation: add section to keep policy and allowlist aligned." >&2
  exit 1
fi

echo "[architecture-check] OK"
