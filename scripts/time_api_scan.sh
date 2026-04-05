#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp"
TARGET_DIR_REL="${TARGET_DIR#"$ROOT_DIR"/}"
DIFF_BASE="${TIME_API_DIFF_BASE:-}"

PATTERN='LocalDate\.now\(|Instant\.now\(|ZoneId\.systemDefault\(|new Date\(|Clock\.systemDefaultZone\('

echo "[time_api_scan] scanning ${TARGET_DIR}"

resolve_diff_base() {
  if [[ -n "$DIFF_BASE" ]] && git -C "$ROOT_DIR" rev-parse --verify --quiet "$DIFF_BASE" >/dev/null; then
    echo "$DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]] && git -C "$ROOT_DIR" rev-parse --verify --quiet "$GITHUB_BASE_SHA" >/dev/null; then
    echo "$GITHUB_BASE_SHA"
    return 0
  fi

  if git -C "$ROOT_DIR" rev-parse --verify --quiet main >/dev/null; then
    git -C "$ROOT_DIR" merge-base main HEAD
    return 0
  fi

  if git -C "$ROOT_DIR" rev-parse --verify --quiet origin/main >/dev/null; then
    git -C "$ROOT_DIR" merge-base origin/main HEAD
    return 0
  fi

  echo ""
}

target_files=()

is_excluded_target() {
  local path="$1"
  case "$path" in
    */modules/auth/*|*/core/security/*|*/core/util/CompanyClock.java|*/core/util/CompanyTime.java)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

if git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  resolved_diff_base="$(resolve_diff_base)"
  if [[ -n "$resolved_diff_base" ]]; then
    while IFS= read -r changed; do
      [[ -z "$changed" ]] && continue
      candidate="$ROOT_DIR/$changed"
      if ! is_excluded_target "$candidate"; then
        target_files+=("$candidate")
      fi
    done < <(
      git -C "$ROOT_DIR" diff --name-only "$resolved_diff_base"...HEAD -- "$TARGET_DIR_REL" \
        | grep -E '\.java$' || true
    )
    if [[ "${#target_files[@]}" -eq 0 ]]; then
      echo "[time_api_scan] diff base: $resolved_diff_base"
      echo "[time_api_scan] no changed Java sources; skipping branch-scoped scan"
      exit 0
    fi
    echo "[time_api_scan] diff base: $resolved_diff_base"
    echo "[time_api_scan] changed Java sources: ${#target_files[@]}"
  fi
fi

if command -v rg >/dev/null 2>&1; then
  if [[ "${#target_files[@]}" -gt 0 ]]; then
    matches=$(rg -n "$PATTERN" "${target_files[@]}" || true)
  else
    matches=$(rg -n "$PATTERN" "$TARGET_DIR" \
      --glob '!**/modules/auth/**' \
      --glob '!**/core/security/**' \
      --glob '!**/core/util/CompanyClock.java' \
      --glob '!**/core/util/CompanyTime.java' \
      || true)
  fi
else
  if [[ "${#target_files[@]}" -gt 0 ]]; then
    matches=$(grep -nHE "$PATTERN" "${target_files[@]}" || true)
  else
    matches=$(grep -RInE "$PATTERN" "$TARGET_DIR" \
      --exclude-dir=auth \
      --exclude-dir=security \
      --exclude=CompanyClock.java \
      --exclude=CompanyTime.java \
      || true)
  fi
fi

if [[ -n "$matches" ]]; then
  echo "[time_api_scan] forbidden time API usage detected:" >&2
  echo "$matches" >&2
  exit 1
fi

echo "[time_api_scan] OK"
