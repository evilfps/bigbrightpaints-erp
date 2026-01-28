#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

REPORT_DIRS=(
  "$ROOT_DIR/erp-domain/target/surefire-reports"
  "$ROOT_DIR/erp-domain/target/failsafe-reports"
)

found_any=false
if command -v rg >/dev/null 2>&1; then
  SEARCH_TOOL="rg"
else
  SEARCH_TOOL="grep"
fi

for dir in "${REPORT_DIRS[@]}"; do
  if [[ ! -d "$dir" ]]; then
    continue
  fi

  found_any=true
  echo
  echo "[triage] reports: $dir"

  xmls=("$dir"/TEST-*.xml)
  if [[ -e "${xmls[0]}" ]]; then
    # Print failing/error suites first.
    while IFS= read -r line; do
      echo "$line"
    done < <(
      if [[ "$SEARCH_TOOL" == "rg" ]]; then
        rg -n "<testsuite " "${xmls[@]}" \
          | rg -n "failures=\\\"[1-9]|errors=\\\"[1-9]" \
          || true
      else
        grep -n -E "<testsuite " "${xmls[@]}" \
          | grep -E "failures=\\\"[1-9]|errors=\\\"[1-9]" \
          || true
      fi
    )
  fi

  # Surface the human-readable failure logs if present.
  txts=("$dir"/*.txt)
  if [[ -e "${txts[0]}" ]]; then
    for f in "${txts[@]}"; do
      has_failure=false
      if [[ "$SEARCH_TOOL" == "rg" ]]; then
        if rg -q "<<< FAILURE!|<<< ERROR!" "$f"; then
          has_failure=true
        fi
      else
        if grep -q -E "<<< FAILURE!|<<< ERROR!" "$f"; then
          has_failure=true
        fi
      fi
      if [[ "$has_failure" == "true" ]]; then
        echo
        echo "[triage] $f (last 120 lines)"
        tail -n 120 "$f"
      fi
    done
  fi
done

if [[ "$found_any" != "true" ]]; then
  echo "[triage] no surefire/failsafe reports found (run tests first)"
fi
