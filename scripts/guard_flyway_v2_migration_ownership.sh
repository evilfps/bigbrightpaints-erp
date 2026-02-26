#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPAT_FILE="$ROOT_DIR/scripts/bash_compat.sh"
if [[ -f "$COMPAT_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$COMPAT_FILE"
fi
MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration_v2"

fail() {
  echo "[guard_flyway_v2_migration_ownership] FAIL: $1" >&2
  exit 1
}

[[ -d "$MIGRATIONS_DIR" ]] || fail "missing migrations dir: $MIGRATIONS_DIR"
git -C "$ROOT_DIR" rev-parse --git-dir >/dev/null 2>&1 || fail "repository metadata unavailable"

mapfile -t migration_files < <(find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name 'V*__*.sql' | sort)
[[ ${#migration_files[@]} -gt 0 ]] || fail "no migration_v2 files found"

untracked=()
for abs_path in "${migration_files[@]}"; do
  rel_path="${abs_path#"$ROOT_DIR"/}"
  if ! git -C "$ROOT_DIR" ls-files --error-unmatch "$rel_path" >/dev/null 2>&1; then
    untracked+=("$rel_path")
  fi
done

if [[ ${#untracked[@]} -gt 0 ]]; then
  printf '[guard_flyway_v2_migration_ownership] Untracked migration_v2 files:\n' >&2
  printf '  - %s\n' "${untracked[@]}" >&2
  fail "all migration_v2 files must be tracked"
fi

# Detect CREATE TABLE blocks that define `id bigint NOT NULL` but never establish
# PRIMARY KEY/UNIQUE(id) on that table (inline or via ALTER TABLE).
mapfile -t missing_pk_tables < <(
  awk '
    function normalize_table(raw, cleaned) {
      cleaned = raw
      gsub(/"/, "", cleaned)
      sub(/\(.*/, "", cleaned)
      gsub(/,/, "", cleaned)
      sub(/^ONLY[[:space:]]+/, "", cleaned)
      sub(/^public\./, "", cleaned)
      sub(/;$/, "", cleaned)
      return cleaned
    }

    function reset_unique_state(table_name) {
      unique_open[table_name] = 0
      unique_pending_open[table_name] = 0
      unique_buffer[table_name] = ""
    }

    function unique_columns_exact_id(raw, normalized) {
      normalized = tolower(raw)
      gsub(/"/, "", normalized)
      gsub(/[[:space:]]+/, "", normalized)
      return normalized == "id"
    }

    function strip_string_literals(raw, cleaned, i, ch, next_ch, prev_ch, sq, in_quote, in_dquote, escape_quote) {
      cleaned = ""
      sq = sprintf("%c", 39)
      in_quote = 0
      in_dquote = 0
      escape_quote = 0

      for (i = 1; i <= length(raw); i++) {
        ch = substr(raw, i, 1)
        if (in_quote) {
          if (escape_quote && ch == "\\") {
            i++
            continue
          }

          if (ch == sq) {
            next_ch = substr(raw, i + 1, 1)
            if (next_ch == sq) {
              i++
              continue
            }
            in_quote = 0
            escape_quote = 0
          }
          continue
        }

        if (in_dquote) {
          cleaned = cleaned ch
          if (ch == "\"") {
            next_ch = substr(raw, i + 1, 1)
            if (next_ch == "\"") {
              cleaned = cleaned next_ch
              i++
              continue
            }
            in_dquote = 0
          }
          continue
        }

        if (ch == "\"") {
          in_dquote = 1
          cleaned = cleaned ch
          continue
        }

        if (!in_quote && !in_dquote) {
          if (ch == sq) {
            prev_ch = (i > 1) ? substr(raw, i - 1, 1) : ""
            escape_quote = (prev_ch == "E" || prev_ch == "e") ? 1 : 0
            in_quote = 1
            cleaned = cleaned " "
            continue
          }
          cleaned = cleaned ch
          continue
        }
      }

      return cleaned
    }

    function paren_delta(raw, opens, closes, tmp) {
      tmp = raw
      opens = gsub(/\(/, "(", tmp)
      tmp = raw
      closes = gsub(/\)/, ")", tmp)
      return opens - closes
    }

    function id_bigint_not_null_on_line(raw, normalized, segment, comma_pos, probe, notnull_pos, before) {
      normalized = tolower(raw)
      gsub(/"/, "", normalized)
      if (match(normalized, /(^|[(,])[[:space:]]*id[[:space:]]+bigint([^[:alnum:]_]|$)/) == 0) {
        return 0
      }

      segment = substr(normalized, RSTART)
      sub(/^[[:space:],(]+/, "", segment)
      comma_pos = index(segment, ",")
      if (comma_pos > 0) {
        segment = substr(segment, 1, comma_pos - 1)
      }

      probe = segment
      while (match(probe, /not[[:space:]]+null([^[:alnum:]_]|$)/) > 0) {
        before = substr(probe, 1, RSTART - 1)
        if (before !~ /(^|[^[:alnum:]_])is[[:space:]]*$/) {
          return 1
        }
        probe = substr(probe, RSTART + RLENGTH)
      }
      return 0
    }

    function mark_pk_contract(table_name, line_raw, line_upper, line_no_quotes, segment, open_pos, close_pos, unique_body) {
      if (table_name == "") {
        return
      }

      line_upper = toupper(line_raw)
      line_no_quotes = line_raw
      gsub(/"/, "", line_no_quotes)

      if (line_upper ~ /PRIMARY[[:space:]]+KEY/) {
        has_pk[table_name] = 1
        reset_unique_state(table_name)
        return
      }

      segment = line_no_quotes
      while (1) {
        if (unique_open[table_name]) {
          close_pos = index(segment, ")")
          if (close_pos == 0) {
            if (unique_buffer[table_name] != "") {
              unique_buffer[table_name] = unique_buffer[table_name] " " segment
            } else {
              unique_buffer[table_name] = segment
            }
            return
          }

          unique_body = substr(segment, 1, close_pos - 1)
          if (unique_buffer[table_name] != "") {
            unique_body = unique_buffer[table_name] " " unique_body
          }
          if (unique_columns_exact_id(unique_body)) {
            has_pk[table_name] = 1
          }
          reset_unique_state(table_name)
          segment = substr(segment, close_pos + 1)
          continue
        }

        if (unique_pending_open[table_name]) {
          pending_segment = segment
          sub(/^[[:space:]]+/, "", pending_segment)
          pending_upper = toupper(pending_segment)
          if (pending_segment == "") {
            return
          }

          if (substr(pending_segment, 1, 1) == "(") {
            open_pos = index(segment, "(")
            unique_open[table_name] = 1
            unique_pending_open[table_name] = 0
            segment = substr(segment, open_pos + 1)
            continue
          }

          if (pending_upper ~ /^(NULLS|NOT|DISTINCT)([^[:alnum:]_]|$)/) {
            open_pos = index(segment, "(")
            if (open_pos == 0) {
              return
            }
            unique_open[table_name] = 1
            unique_pending_open[table_name] = 0
            segment = substr(segment, open_pos + 1)
            continue
          }

          unique_pending_open[table_name] = 0
          continue
        }

        if (match(toupper(segment), /UNIQUE[[:space:]]*\(/) == 0) {
          if (match(toupper(segment), /UNIQUE([^[:alnum:]_]|$)/) > 0) {
            trailing = substr(segment, RSTART + RLENGTH)
            unique_pending_open[table_name] = 1
            if (trailing ~ /^[[:space:]]*$/) {
              return
            }
            segment = trailing
            continue
          }
          return
        }
        open_pos = RSTART + RLENGTH - 1
        segment = substr(segment, open_pos + 1)
        unique_open[table_name] = 1
        unique_buffer[table_name] = ""
      }
    }

    function extract_create_table(raw, i, token_count, token, idx, table_name) {
      token_count = split(raw, token, /[[:space:]]+/)
      table_name = ""
      for (i = 1; i <= token_count; i++) {
        if (toupper(token[i]) == "TABLE") {
          idx = i + 1
          if (toupper(token[idx]) == "IF" && toupper(token[idx + 1]) == "NOT" && toupper(token[idx + 2]) == "EXISTS") {
            idx += 3
          }
          table_name = token[idx]
          break
        }
      }
      return normalize_table(table_name)
    }

    function extract_alter_table(raw, i, token_count, token, idx, table_name) {
      token_count = split(raw, token, /[[:space:]]+/)
      table_name = ""
      for (i = 1; i <= token_count; i++) {
        if (toupper(token[i]) == "TABLE") {
          idx = i + 1
          if (toupper(token[idx]) == "ONLY") {
            idx++
          }
          table_name = token[idx]
          break
        }
      }
      return normalize_table(table_name)
    }

    {
      line = $0
      sub(/--.*/, "", line)
      if (line ~ /^[[:space:]]*$/) {
        next
      }

      line_clean = strip_string_literals(line)
      if (line_clean ~ /^[[:space:]]*$/) {
        next
      }

      upper = toupper(line_clean)

      if (upper ~ /^[[:space:]]*CREATE[[:space:]]+TABLE/) {
        in_create = 1
        in_alter = 0
        current_table = extract_create_table(line_clean)
        create_has_id = 0
        create_depth = 0
        reset_unique_state(current_table)
      }

      if (in_create) {
        if (id_bigint_not_null_on_line(line_clean)) {
          create_has_id = 1
        }

        mark_pk_contract(current_table, line_clean)
        create_depth += paren_delta(line_clean)

        if (create_depth <= 0 && line_clean ~ /;/) {
          if (create_has_id && !has_pk[current_table]) {
            needs_pk[current_table] = 1
          }
          in_create = 0
          reset_unique_state(current_table)
          current_table = ""
        }
        next
      }

      if (upper ~ /^[[:space:]]*ALTER[[:space:]]+TABLE/) {
        in_alter = 1
        alter_table = extract_alter_table(line_clean)
        reset_unique_state(alter_table)
      }

      if (in_alter) {
        mark_pk_contract(alter_table, line_clean)
        if (line_clean ~ /;/) {
          in_alter = 0
          reset_unique_state(alter_table)
          alter_table = ""
        }
        next
      }
    }

    END {
      for (table in needs_pk) {
        if (!has_pk[table]) {
          print table
        }
      }
    }
  ' "${migration_files[@]}" | sort -u
)

if [[ ${#missing_pk_tables[@]} -gt 0 ]]; then
  printf '[guard_flyway_v2_migration_ownership] Tables missing PK/UNIQUE(id) contract:\n' >&2
  printf '  - %s\n' "${missing_pk_tables[@]}" >&2
  fail "migration_v2 id-column ownership contract violated"
fi

echo "[guard_flyway_v2_migration_ownership] OK"
