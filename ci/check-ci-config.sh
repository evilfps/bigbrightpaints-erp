#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

require_tool() {
  local tool="$1"
  local install_hint="$2"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "[ci-config-check] FAIL: missing required tool: $tool" >&2
    echo "[ci-config-check] remediation: $install_hint" >&2
    exit 1
  fi
}

resolve_diff_base() {
  if [[ -n "${CI_CONFIG_DIFF_BASE:-}" ]] && git rev-parse --verify --quiet "$CI_CONFIG_DIFF_BASE" >/dev/null; then
    echo "$CI_CONFIG_DIFF_BASE"
    return 0
  fi

  if [[ -n "${GITHUB_BASE_SHA:-}" ]] && git rev-parse --verify --quiet "$GITHUB_BASE_SHA" >/dev/null; then
    echo "$GITHUB_BASE_SHA"
    return 0
  fi

  if git rev-parse --verify --quiet origin/main >/dev/null; then
    git merge-base origin/main HEAD
    return 0
  fi

  echo ""
}

require_tool actionlint "install actionlint or use the CI job, which installs it before running this check"
require_tool shellcheck "install shellcheck or use the CI job, which installs it before running this check"

echo "[ci-config-check] workflow syntax"
shellcheck_wrapper="$(mktemp -t ci-shellcheck.XXXXXX)"
trap 'rm -f "$shellcheck_wrapper"' EXIT
cat >"$shellcheck_wrapper" <<'EOF'
#!/usr/bin/env bash
exec shellcheck --severity=error "$@"
EOF
chmod +x "$shellcheck_wrapper"
actionlint -shellcheck "$shellcheck_wrapper"

BASE="$(resolve_diff_base)"
if [[ -n "$BASE" ]]; then
  changed_shell_files="$(
    {
      git diff --name-only "$BASE"...HEAD || true
      git diff --name-only || true
      git ls-files --others --exclude-standard || true
    } |
      sed '/^$/d' |
      sort -u |
      grep -E '(^ci/|^scripts/|^\.github/).*\.(sh|bash)$' |
      while IFS= read -r path; do
        [[ -f "$path" ]] && printf '%s\n' "$path"
      done || true
  )"
else
  changed_shell_files="$(git ls-files 'ci/*.sh' 'scripts/*.sh' '.github/**/*.sh' | sort -u)"
fi

if [[ -z "$changed_shell_files" ]]; then
  echo "[ci-config-check] shellcheck skipped: no changed shell scripts"
  echo "[ci-config-check] OK"
  exit 0
fi

echo "[ci-config-check] shellcheck changed shell scripts"
while IFS= read -r shell_file; do
  shellcheck --severity=error -- "$shell_file"
done <<<"$changed_shell_files"

echo "[ci-config-check] OK"
