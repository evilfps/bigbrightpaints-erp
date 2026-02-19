#!/usr/bin/env bash
# Chain caller-provided BASH_ENV (if any) and repository compatibility shims.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOOTSTRAP_PATH="$SCRIPT_DIR/bash_env_bootstrap.sh"
COMPAT_FILE="$SCRIPT_DIR/bash_compat.sh"
CURRENT_BASH_ENV="${BASH_ENV:-}"
CHAINED_BASH_ENV="${BBP_CHAINED_BASH_ENV:-}"
CHAINED_BASH_ENV_PARENT_PID="${BBP_CHAINED_BASH_ENV_PARENT_PID:-}"

if [[ -n "$CHAINED_BASH_ENV" \
  && -n "$CHAINED_BASH_ENV_PARENT_PID" \
  && "$CURRENT_BASH_ENV" == "$BOOTSTRAP_PATH" \
  && "$CHAINED_BASH_ENV_PARENT_PID" == "$PPID" \
  && "$CHAINED_BASH_ENV" != "$BOOTSTRAP_PATH" \
  && -f "$CHAINED_BASH_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$CHAINED_BASH_ENV"
fi

unset BBP_CHAINED_BASH_ENV
unset BBP_CHAINED_BASH_ENV_PARENT_PID

if [[ -f "$COMPAT_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$COMPAT_FILE"
fi
