#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" && -n "${BASH_ENV:-}" ]]; then
  export BBP_CHAINED_BASH_ENV="${BASH_ENV:-}"
  export BBP_CHAINED_BASH_ENV_PARENT_PID="$$"
else
  unset BBP_CHAINED_BASH_ENV
  unset BBP_CHAINED_BASH_ENV_PARENT_PID
fi
export BASH_ENV="$COMPAT_BASH_ENV_BOOTSTRAP"

TESTS_JAVA_ROOT="$ROOT_DIR/erp-domain/src/test/java"
TRUTH_TEST_ROOT="$TESTS_JAVA_ROOT/com/bigbrightpaints/erp/truthsuite"

PROFILE=""
LABEL="local-tests"
MANIFEST=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --label)
      LABEL="$2"
      shift 2
      ;;
    --manifest)
      MANIFEST="$2"
      shift 2
      ;;
    *)
      echo "[$LABEL] unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$PROFILE" || -z "$MANIFEST" ]]; then
  echo "[$LABEL] usage: $0 --profile <maven-profile> --manifest <path> [--label <name>]" >&2
  exit 2
fi

SELECTORS="$(python3 - "$TESTS_JAVA_ROOT" "$TRUTH_TEST_ROOT" "$MANIFEST" <<'PY'
import sys
from pathlib import Path

tests_java_root = Path(sys.argv[1]).resolve()
truth_root = Path(sys.argv[2]).resolve()
manifest = Path(sys.argv[3]).resolve()

if not manifest.exists():
    print(f"manifest missing: {manifest}", file=sys.stderr)
    raise SystemExit(1)

entries = []
seen = set()
errors = []
for line_no, raw in enumerate(manifest.read_text(encoding="utf-8").splitlines(), start=1):
    content = raw.split("#", 1)[0].strip()
    if not content:
        continue
    rel = content.replace("\\", "/").removeprefix("./")
    if rel in seen:
        errors.append(f"duplicate manifest entry at line {line_no}: {rel}")
        continue
    seen.add(rel)
    file_path = (truth_root / rel).resolve()
    if not file_path.exists():
        errors.append(f"missing test file at line {line_no}: {rel}")
        continue
    try:
        relative_to_java_root = file_path.relative_to(tests_java_root)
    except ValueError:
        errors.append(f"manifest entry escapes src/test/java at line {line_no}: {rel}")
        continue
    entries.append(".".join(relative_to_java_root.with_suffix("").parts))

if not entries:
    errors.append(f"manifest is empty: {manifest}")

if errors:
    for error in errors:
        print(error, file=sys.stderr)
    raise SystemExit(1)

print(",".join(entries), end="")
PY
)"

if [[ -z "$SELECTORS" ]]; then
  echo "[$LABEL] resolved selector list is empty" >&2
  exit 2
fi

SELECTOR_COUNT="$(python3 - "$SELECTORS" <<'PY'
import sys
value = sys.argv[1].strip()
print(0 if not value else len([part for part in value.split(',') if part]))
PY
)"

echo "[$LABEL] profile=$PROFILE selectors=$SELECTOR_COUNT manifest=$MANIFEST"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Dsurefire.runOrder=alphabetical -Djacoco.skip=true -P"$PROFILE" -Dtest="$SELECTORS" test
)
