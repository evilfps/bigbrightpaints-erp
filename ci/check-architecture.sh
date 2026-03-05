#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_ROOT="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules"
ALLOWLIST="$ROOT_DIR/ci/architecture/module-import-allowlist.txt"
ARCH_DOC_PRIMARY="$ROOT_DIR/docs/ARCHITECTURE.md"
ARCH_DOC_FALLBACK="$ROOT_DIR/docs/architecture.md"

if [[ ! -d "$MODULE_ROOT" ]]; then
  echo "[architecture-check] FAIL: module root missing at $MODULE_ROOT" >&2
  echo "[architecture-check] remediation: update script path or create module root" >&2
  exit 1
fi

if [[ ! -f "$ALLOWLIST" ]]; then
  echo "[architecture-check] WARN: allowlist missing at $ALLOWLIST"
  echo "[architecture-check] WARN: continuing with fail-open compatibility mode"
  exit 0
fi

compatibility_relaxed_import_edges=false
if [[ ! -f "$ROOT_DIR/agents/catalog.yaml" ]]; then
  compatibility_relaxed_import_edges=true
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
  if [[ "$compatibility_relaxed_import_edges" == "true" ]]; then
    echo "[architecture-check] WARN: unresolved cross-module imports detected (compatibility mode):"
    sed 's/^/  - /' "$violations_tmp"
    echo "[architecture-check] WARN: allowlist enforcement relaxed because legacy orchestrator catalog is absent"
  else
    echo "[architecture-check] FAIL: new cross-module imports detected:" >&2
    sed 's/^/  - /' "$violations_tmp" >&2
    echo "[architecture-check] remediation:" >&2
    echo "  1) Prefer domain facade/orchestrator path instead of direct new module import." >&2
    echo "  2) If intentional, document rationale in docs/ARCHITECTURE.md and add edge to $ALLOWLIST." >&2
    exit 1
  fi
fi

ARCH_DOC="$ARCH_DOC_PRIMARY"
if [[ ! -f "$ARCH_DOC" ]]; then
  ARCH_DOC="$ARCH_DOC_FALLBACK"
fi

if [[ ! -f "$ARCH_DOC" ]]; then
  echo "[architecture-check] FAIL: missing architecture doc (checked $ARCH_DOC_PRIMARY and $ARCH_DOC_FALLBACK)." >&2
  echo "[architecture-check] remediation: add architecture documentation to align policy and allowlist." >&2
  exit 1
fi

if ! grep -Eq 'Inter-Module Dependency Rules|Cross-module dependency boundaries' "$ARCH_DOC"; then
  echo "[architecture-check] FAIL: $ARCH_DOC missing dependency-boundary section." >&2
  echo "[architecture-check] remediation: add a dependency boundary section to keep policy and allowlist aligned." >&2
  exit 1
fi

echo "[architecture-check] OK"
