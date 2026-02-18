#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_ROOT="$ROOT_DIR/erp-domain/src/main/java"
REMEDIATION_COMMAND="bash scripts/guard_integration_failure_metadata_schema.sh"
LOG_FAILURE_PATTERN='logFailure\('
SCHEMA_PATTERN='IntegrationFailureMetadataSchema\.applyRequiredFields\('
MANUAL_REQUIRED_KEY_PATTERN='put\("failureCode"|put\("errorCategory"|put\("alertRoutingVersion"|put\("alertRoute"'

fail() {
  echo "[guard_integration_failure_metadata_schema] ERROR: $1" >&2
  echo "[guard_integration_failure_metadata_schema] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

for required_command in awk; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "required command not found: $required_command"
done

search_matching_files() {
  local pattern="$1"
  local root="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -l "$pattern" "$root" || true
    return
  fi
  grep -R -l -E -- "$pattern" "$root" || true
}

search_line_numbers() {
  local pattern="$1"
  local file="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -n "$pattern" "$file" | cut -d: -f1 || true
    return
  fi
  grep -n -E -- "$pattern" "$file" | cut -d: -f1 || true
}

has_pattern() {
  local pattern="$1"
  local file="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -q "$pattern" "$file"
    return
  fi
  grep -Eq -- "$pattern" "$file"
}

[[ -d "$SOURCE_ROOT" ]] || fail "missing source root: $SOURCE_ROOT"

mapfile -t producer_files < <(search_matching_files "$LOG_FAILURE_PATTERN" "$SOURCE_ROOT")

[[ "${#producer_files[@]}" -gt 0 ]] || fail "no files with logFailure calls found under $SOURCE_ROOT"

processed_producers=0
for file in "${producer_files[@]}"; do
  mapfile -t log_lines < <(
    awk '
      function sanitize_line(raw, cleaned, block_start, block_tail, block_end) {
        cleaned = raw
        gsub(/"([^"\\]|\\.)*"/, "", cleaned)

        if (in_block_comment) {
          block_end = index(cleaned, "*/")
          if (block_end == 0) {
            return ""
          }
          cleaned = substr(cleaned, block_end + 2)
          in_block_comment = 0
        }

        block_start = index(cleaned, "/*")
        while (block_start > 0) {
          block_tail = substr(cleaned, block_start + 2)
          block_end = index(block_tail, "*/")
          if (block_end == 0) {
            cleaned = substr(cleaned, 1, block_start - 1)
            in_block_comment = 1
            break
          }
          cleaned = substr(cleaned, 1, block_start - 1) substr(block_tail, block_end + 2)
          block_start = index(cleaned, "/*")
        }

        sub(/\/\/.*/, "", cleaned)
        return cleaned
      }

      function paren_delta(cleaned, opens, closes, tmp) {
        tmp = cleaned
        opens = gsub(/\(/, "", tmp)
        tmp = cleaned
        closes = gsub(/\)/, "", tmp)
        return opens - closes
      }

      function has_integration_failure_token(text, pattern) {
        pattern = "logFailure[[:space:]]*\\([[:space:]]*(AuditEvent[[:space:]]*\\.[[:space:]]*)?INTEGRATION_FAILURE([[:space:]]*[,)]|$)"
        return text ~ pattern
      }

      BEGIN { in_call = 0; in_block_comment = 0; start = 0; depth = 0; buffer = "" }
      {
        cleaned = sanitize_line($0)
        if (!in_call && cleaned ~ /logFailure[[:space:]]*\(/) {
          in_call = 1
          start = NR
          depth = paren_delta(cleaned)
          buffer = cleaned "\n"
          if (depth <= 0) {
            if (has_integration_failure_token(buffer)) {
              print start
            }
            in_call = 0
            depth = 0
            buffer = ""
          }
          next
        }
        if (in_call) {
          depth += paren_delta(cleaned)
          buffer = buffer cleaned "\n"
          if (depth <= 0) {
            if (has_integration_failure_token(buffer)) {
              print start
            }
            in_call = 0
            depth = 0
            buffer = ""
          }
        }
      }
    ' "$file"
  )

  [[ "${#log_lines[@]}" -gt 0 ]] || continue
  processed_producers=$((processed_producers + 1))

  if has_pattern "$MANUAL_REQUIRED_KEY_PATTERN" "$file"; then
    fail "producer $file still writes required metadata keys manually; use IntegrationFailureMetadataSchema only"
  fi

  mapfile -t helper_lines < <(search_line_numbers "$SCHEMA_PATTERN" "$file")
  [[ "${#helper_lines[@]}" -gt 0 ]] || fail "producer $file does not call IntegrationFailureMetadataSchema.applyRequiredFields"

  for log_line in "${log_lines[@]}"; do
    method_start_line="$(
      awk -v target="$log_line" '
        NR <= target && $0 ~ /^[[:space:]]*(public|private|protected)[^;]*\([^\)]*\)/ { line=NR }
        END { if (line) print line; else print 1 }
      ' "$file"
    )"

    has_schema_in_method=false
    for helper_line in "${helper_lines[@]}"; do
      if (( helper_line >= method_start_line && helper_line <= log_line )); then
        has_schema_in_method=true
      else
        if (( helper_line > log_line )); then
          break
        fi
      fi
    done

    if [[ "$has_schema_in_method" != true ]]; then
      fail "producer $file has INTEGRATION_FAILURE log at line $log_line without schema helper in method scope"
    fi
  done
done

[[ "$processed_producers" -gt 0 ]] || fail "no INTEGRATION_FAILURE log producers found under $SOURCE_ROOT"

echo "[guard_integration_failure_metadata_schema] OK"
