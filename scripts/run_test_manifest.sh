#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="pr-fast"
LABEL=""
BATCH_SIZE=25
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
    --batch-size)
      BATCH_SIZE="$2"
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

if ! [[ "$BATCH_SIZE" =~ ^[0-9]+$ ]] || [[ "$BATCH_SIZE" -le 0 ]]; then
  echo "[run_test_manifest] --batch-size must be a positive integer" >&2
  exit 2
fi

SELECTORS=()
while IFS= read -r selector; do
  SELECTORS+=("$selector")
done < <(python3 "$ROOT_DIR/scripts/manifest_to_dtest.py" --format lines "${MANIFESTS[@]}")
if [[ "${#SELECTORS[@]}" -eq 0 ]]; then
  echo "[run_test_manifest] manifest resolved to an empty selector set" >&2
  exit 2
fi

DISPLAY_LABEL="${LABEL:-$PROFILE}"

echo "[run_test_manifest] profile=$PROFILE label=$DISPLAY_LABEL"
echo "[run_test_manifest] selectors=${#SELECTORS[@]} batch_size=$BATCH_SIZE"

(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec target/manifest-jacoco-batches
  mkdir -p target/manifest-jacoco-batches

  batch_start=0
  batch_number=0
  report_count=0
  while [[ "$batch_start" -lt "${#SELECTORS[@]}" ]]; do
    batch_number=$((batch_number + 1))
    batch_selectors=("${SELECTORS[@]:batch_start:BATCH_SIZE}")
    batch_selector="$(IFS=,; printf '%s' "${batch_selectors[*]}")"
    batch_start=$((batch_start + ${#batch_selectors[@]}))

    echo "[run_test_manifest] batch=$batch_number size=${#batch_selectors[@]}"
    echo "[run_test_manifest] batch_selector=$batch_selector"

    rm -rf target/site/jacoco target/jacoco.exec

    cmd=(mvn -B -ntp -P"$PROFILE" test -Dtest="$batch_selector")
    if [[ "${#MAVEN_ARGS[@]}" -gt 0 ]]; then
      cmd+=("${MAVEN_ARGS[@]}")
    fi
    "${cmd[@]}"

    batch_report="target/site/jacoco/jacoco.xml"
    if [[ -f "$batch_report" ]]; then
      cp "$batch_report" "target/manifest-jacoco-batches/jacoco-$batch_number.xml"
      report_count=$((report_count + 1))
    else
      echo "[run_test_manifest] batch=$batch_number produced no JaCoCo report; continuing" >&2
    fi
  done

  if [[ "$report_count" -gt 0 ]]; then
    python3 "$ROOT_DIR/scripts/merge_jacoco_xml.py" \
      --output target/site/jacoco/jacoco.xml \
      target/manifest-jacoco-batches/jacoco-*.xml
  else
    echo "[run_test_manifest] no JaCoCo reports generated for label=$DISPLAY_LABEL"
  fi
)
