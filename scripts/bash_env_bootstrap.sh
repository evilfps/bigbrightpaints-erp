#!/usr/bin/env bash
# Chain caller-provided BASH_ENV (if any) and repository compatibility shims.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPAT_FILE="$SCRIPT_DIR/bash_compat.sh"
CURRENT_BASH_ENV="${BASH_ENV:-}"
ORIGINAL_BASH_ENV="${BBP_ORIGINAL_BASH_ENV:-}"

if [[ -n "$ORIGINAL_BASH_ENV" && "$ORIGINAL_BASH_ENV" != "$CURRENT_BASH_ENV" && -f "$ORIGINAL_BASH_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$ORIGINAL_BASH_ENV"
fi

if [[ -f "$COMPAT_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$COMPAT_FILE"
fi
