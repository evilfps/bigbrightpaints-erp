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

# Detect CREATE/ALTER statements that define `id bigint NOT NULL` but never
# establish PRIMARY KEY/UNIQUE(id) on that table.
mapfile -t missing_pk_tables < <(
  awk '
    BEGIN {
      IGNORECASE = 1
      RS = ";"
    }

    function normalize_table(raw, cleaned) {
      cleaned = raw
      gsub(/"/, "", cleaned)
      sub(/\(.*/, "", cleaned)
      gsub(/,/, "", cleaned)
      sub(/^ONLY[[:space:]]+/, "", cleaned)
      sub(/^public\./, "", cleaned)
      return cleaned
    }

    function collapse_spaces(raw, cleaned) {
      cleaned = raw
      gsub(/--[^\n\r]*/, "", cleaned)
      gsub(/[\r\n\t]+/, " ", cleaned)
      gsub(/[[:space:]]+/, " ", cleaned)
      sub(/^[[:space:]]+/, "", cleaned)
      sub(/[[:space:]]+$/, "", cleaned)
      return cleaned
    }

    function has_id_bigint_not_null(stmt, normalized) {
      normalized = stmt
      gsub(/"/, "", normalized)
      return normalized ~ /(^|[^[:alnum:]_])id[[:space:]]+bigint[[:space:]]+NOT[[:space:]]+NULL([^[:alnum:]_]|$)/
    }

    function has_pk_contract(stmt, normalized) {
      normalized = stmt
      gsub(/"/, "", normalized)
      return normalized ~ /PRIMARY[[:space:]]+KEY/ || normalized ~ /UNIQUE[[:space:]]*\([[:space:]]*id[[:space:]]*\)/
    }

    {
      stmt = collapse_spaces($0)
      if (stmt == "") next

      if (stmt ~ /CREATE[[:space:]]+TABLE[[:space:]]+/) {
        create_stmt = stmt
        sub(/.*CREATE[[:space:]]+TABLE[[:space:]]+/, "", create_stmt)
        sub(/^IF[[:space:]]+NOT[[:space:]]+EXISTS[[:space:]]+/, "", create_stmt)
        split(create_stmt, create_token, /[[:space:]]+/)
        current_table = normalize_table(create_token[1])

        if (current_table != "" && has_id_bigint_not_null(stmt)) {
          needs_pk[current_table] = 1
        }
        if (current_table != "" && has_pk_contract(stmt)) {
          has_pk[current_table] = 1
        }
        next
      }

      if (stmt ~ /ALTER[[:space:]]+TABLE[[:space:]]+/) {
        alter_stmt = stmt
        sub(/.*ALTER[[:space:]]+TABLE[[:space:]]+/, "", alter_stmt)
        sub(/^ONLY[[:space:]]+/, "", alter_stmt)
        split(alter_stmt, alter_token, /[[:space:]]+/)
        alter_table = normalize_table(alter_token[1])
        if (alter_table != "" && has_pk_contract(stmt)) {
          has_pk[alter_table] = 1
        }
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
