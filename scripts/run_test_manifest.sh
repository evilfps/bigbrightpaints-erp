#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="pr-fast"
LABEL=""
MAVEN_ARGS=()
MANIFESTS=()

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
    --maven-arg)
      MAVEN_ARGS+=("$2")
      shift 2
      ;;
    --manifest)
      MANIFESTS+=("$2")
      shift 2
      ;;
    *)
      echo "[run_test_manifest] unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${#MANIFESTS[@]}" -eq 0 ]]; then
  echo "[run_test_manifest] at least one --manifest is required" >&2
  exit 2
fi

SELECTOR="$(python3 "$ROOT_DIR/scripts/manifest_to_dtest.py" "${MANIFESTS[@]}")"
DISPLAY_LABEL="${LABEL:-$PROFILE}"

echo "[run_test_manifest] profile=$PROFILE label=$DISPLAY_LABEL"
echo "[run_test_manifest] selector=$SELECTOR"

(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  cmd=(mvn -B -ntp -P"$PROFILE" test -Dtest="$SELECTOR")
  if [[ "${#MAVEN_ARGS[@]}" -gt 0 ]]; then
    cmd+=("${MAVEN_ARGS[@]}")
  fi
  "${cmd[@]}"
)
