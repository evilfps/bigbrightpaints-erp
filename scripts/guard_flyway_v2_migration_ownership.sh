#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
      unique_buffer[table_name] = ""
    }

    function unique_columns_exact_id(raw, normalized) {
      normalized = tolower(raw)
      gsub(/"/, "", normalized)
      gsub(/[[:space:]]+/, "", normalized)
      return normalized == "id"
    }

    function paren_delta(raw, opens, closes, tmp) {
      tmp = raw
      opens = gsub(/\(/, "(", tmp)
      tmp = raw
      closes = gsub(/\)/, ")", tmp)
      return opens - closes
    }

    function mark_pk_contract(table_name, line_raw, line_upper, line_no_quotes, open_pos, fragment, close_pos, unique_body) {
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

      if (!unique_open[table_name]) {
        if (match(line_upper, /UNIQUE[[:space:]]*\(/) == 0) {
          return
        }
        open_pos = RSTART + RLENGTH - 1
        fragment = substr(line_no_quotes, open_pos + 1)
        unique_open[table_name] = 1
        unique_buffer[table_name] = ""
      } else {
        fragment = line_no_quotes
      }

      close_pos = index(fragment, ")")
      if (close_pos > 0) {
        unique_body = substr(fragment, 1, close_pos - 1)
        if (unique_buffer[table_name] != "") {
          unique_body = unique_buffer[table_name] " " unique_body
        }
        if (unique_columns_exact_id(unique_body)) {
          has_pk[table_name] = 1
        }
        reset_unique_state(table_name)
        return
      }

      if (unique_buffer[table_name] != "") {
        unique_buffer[table_name] = unique_buffer[table_name] " " fragment
      } else {
        unique_buffer[table_name] = fragment
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

      upper = toupper(line)

      if (upper ~ /^[[:space:]]*CREATE[[:space:]]+TABLE/) {
        in_create = 1
        in_alter = 0
        current_table = extract_create_table(line)
        create_has_id = 0
        create_depth = 0
        reset_unique_state(current_table)
      }

      if (in_create) {
        lower_no_quotes = tolower(line)
        gsub(/"/, "", lower_no_quotes)
        if (lower_no_quotes ~ /(^|[(,])[[:space:]]*id[[:space:]]+bigint[[:space:]]+not[[:space:]]+null([[:space:]]*[,)]|[[:space:]]*$)/) {
          create_has_id = 1
        }

        mark_pk_contract(current_table, line)
        create_depth += paren_delta(line)

        if (create_depth <= 0 && line ~ /;/) {
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
        alter_table = extract_alter_table(line)
        reset_unique_state(alter_table)
      }

      if (in_alter) {
        mark_pk_contract(alter_table, line)
        if (line ~ /;/) {
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
