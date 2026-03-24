#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEGACY_DIR="erp-domain/src/main/resources/db/migration"
HEAD_REF="${HEAD_REF:-HEAD}"
BASE_REF="${BASE_REF:-}"
CHECK_UNCOMMITTED="true"
if [[ -n "${CI:-}" || -n "${GITHUB_ACTIONS:-}" ]]; then
  CHECK_RANGE="true"
else
  CHECK_RANGE="false"
fi

usage() {
  cat <<USAGE
Usage: bash scripts/guard_legacy_migration_freeze.sh [--base <ref>] [--head <ref>] [--legacy-dir <path>] [--no-uncommitted] [--no-range]

Fails when legacy Flyway v1 migration files under db/migration are changed.
By default, range checks run in CI and working-tree checks run locally.

Options:
  --base <ref>         Compare against this base ref (default: auto-resolve)
  --head <ref>         Head ref for diff comparison (default: HEAD)
  --legacy-dir <path>  Legacy migration directory (default: erp-domain/src/main/resources/db/migration)
  --no-uncommitted     Skip working tree/index check
  --no-range           Skip git range diff check
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      BASE_REF="${2:-}"
      shift 2
      ;;
    --head)
      HEAD_REF="${2:-}"
      shift 2
      ;;
    --legacy-dir)
      LEGACY_DIR="${2:-}"
      shift 2
      ;;
    --no-uncommitted)
      CHECK_UNCOMMITTED="false"
      shift
      ;;
    --no-range)
      CHECK_RANGE="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[guard_legacy_migration_freeze] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "[guard_legacy_migration_freeze] not a git repository: $ROOT_DIR" >&2
  exit 2
fi

if [[ ! -d "$ROOT_DIR/$LEGACY_DIR" ]]; then
  echo "[guard_legacy_migration_freeze] legacy dir not found: $ROOT_DIR/$LEGACY_DIR" >&2
  exit 2
fi

resolve_base_ref() {
  if [[ -n "$BASE_REF" ]]; then
    if [[ "$BASE_REF" =~ ^0+$ ]]; then
      echo "[guard_legacy_migration_freeze] warning: provided base ref is zero SHA; falling back"
    elif git -C "$ROOT_DIR" rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
      printf '%s\n' "$BASE_REF"
      return 0
    else
      echo "[guard_legacy_migration_freeze] warning: provided base ref not found: $BASE_REF"
    fi
  fi
  if git -C "$ROOT_DIR" rev-parse --verify --quiet "${HEAD_REF}^" >/dev/null; then
    printf '%s\n' "${HEAD_REF}^"
    return 0
  fi
  printf '\n'
}

collect_worktree_violations() {
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    violations+=("WORKTREE ${line}")
  done < <(git -C "$ROOT_DIR" diff --name-status --diff-filter=ACMRTUXB -- "$LEGACY_DIR")

  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    violations+=("WORKTREE ${line}")
  done < <(git -C "$ROOT_DIR" diff --cached --name-status --diff-filter=ACMRTUXB -- "$LEGACY_DIR")

  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    violations+=($'WORKTREE ??\t'"${path}")
  done < <(git -C "$ROOT_DIR" ls-files --others --exclude-standard -- "$LEGACY_DIR")
}

violations=()

if [[ "$CHECK_UNCOMMITTED" == "true" ]]; then
  # Deletion-only cleanup is allowed; new or edited legacy migrations are not.
  collect_worktree_violations
fi

if [[ "$CHECK_RANGE" == "true" ]]; then
  resolved_base="$(resolve_base_ref)"
  if [[ -n "$resolved_base" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      violations+=("RANGE ${line}")
    done < <(git -C "$ROOT_DIR" diff --name-status --diff-filter=ACMRTUXB "${resolved_base}..${HEAD_REF}" -- "$LEGACY_DIR")
  else
    echo "[guard_legacy_migration_freeze] warning: unable to resolve base ref; skipping range diff"
  fi
fi

if (( ${#violations[@]} > 0 )); then
  echo "[guard_legacy_migration_freeze] FAIL: legacy Flyway v1 chain is frozen" >&2
  echo "[guard_legacy_migration_freeze] Detected changes under $LEGACY_DIR:" >&2
  for line in "${violations[@]}"; do
    echo "  - $line" >&2
  done
  echo "[guard_legacy_migration_freeze] Add new schema work only under erp-domain/src/main/resources/db/migration_v2" >&2
  exit 1
fi

echo "[guard_legacy_migration_freeze] OK: no legacy v1 migration changes detected"
