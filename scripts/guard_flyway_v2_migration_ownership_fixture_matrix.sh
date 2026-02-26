#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_GUARD="$ROOT_DIR/scripts/guard_flyway_v2_migration_ownership.sh"
SOURCE_COMPAT="$ROOT_DIR/scripts/bash_compat.sh"

fail() {
  echo "[guard_flyway_v2_migration_ownership_fixture_matrix] FAIL: $1" >&2
  exit 1
}

[[ -f "$SOURCE_GUARD" ]] || fail "missing source guard script: $SOURCE_GUARD"
[[ -f "$SOURCE_COMPAT" ]] || fail "missing bash compatibility helper: $SOURCE_COMPAT"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

run_case() {
  local name="$1"
  local expected_exit="$2"
  local sql="$3"
  local case_root="$TMP_ROOT/$name"
  local migration_file="$case_root/erp-domain/src/main/resources/db/migration_v2/V1__fixture.sql"
  local out_file="$case_root/output.log"
  local status=0

  mkdir -p \
    "$case_root/scripts" \
    "$case_root/erp-domain/src/main/resources/db/migration_v2"

  cp "$SOURCE_GUARD" "$case_root/scripts/guard_flyway_v2_migration_ownership.sh"
  cp "$SOURCE_COMPAT" "$case_root/scripts/bash_compat.sh"
  chmod +x "$case_root/scripts/guard_flyway_v2_migration_ownership.sh"

  cat > "$migration_file" <<SQL
$sql
SQL

  git -C "$case_root" init -q
  git -C "$case_root" add "${migration_file#"$case_root/"}"

  set +e
  (cd "$case_root" && bash scripts/guard_flyway_v2_migration_ownership.sh) >"$out_file" 2>&1
  status=$?
  set -e

  if [[ "$status" -ne "$expected_exit" ]]; then
    echo "[guard_flyway_v2_migration_ownership_fixture_matrix] FAIL: case '$name' expected exit $expected_exit, got $status" >&2
    echo "--- $name output ---" >&2
    cat "$out_file" >&2
    exit 1
  fi
}

run_case "quoted_identifier_apostrophe_and_estring_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_quote_apostrophe (
    id bigint NOT NULL,
    label text DEFAULT E'owner''s;memo',
    "owner""note" text
);

ALTER TABLE ONLY public.fixture_quote_apostrophe
    ADD CONSTRAINT fixture_quote_apostrophe_pkey PRIMARY KEY (id);
SQL
)"

run_case "check_is_not_null_ordering_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_check_order (
    id bigint CHECK ((id IS NOT NULL)) NOT NULL,
    analysis text
);

ALTER TABLE ONLY public.fixture_check_order
    ADD CONSTRAINT fixture_check_order_pkey PRIMARY KEY (id);
SQL
)"

run_case "dollar_suffix_identifier_boundary_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_dollar_boundary (
    id bigint NOT NULL,
    foo$is text,
    analysis text
);

ALTER TABLE ONLY public.fixture_dollar_boundary
    ADD CONSTRAINT fixture_dollar_boundary_id_key UNIQUE (id);
SQL
)"

run_case "multiline_unique_with_estring_semicolon_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_multiline_unique (
    id bigint NOT NULL,
    notes text DEFAULT E'alpha;beta'
);

ALTER TABLE ONLY public.fixture_multiline_unique
    ADD CONSTRAINT fixture_multiline_unique_id_key UNIQUE
    (
        id
    );
SQL
)"

run_case "multiline_unique_nulls_not_distinct_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_unique_nulls_not_distinct (
    id bigint NOT NULL,
    token bigint
);

ALTER TABLE ONLY public.fixture_unique_nulls_not_distinct
    ADD CONSTRAINT fixture_unique_nulls_not_distinct_id_key UNIQUE NULLS NOT DISTINCT
    (
        id
    );
SQL
)"

run_case "multiline_unique_nulls_not_distinct_lowercase_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.fixture_unique_nulls_not_distinct_lower (
    id bigint NOT NULL,
    token bigint
);

ALTER TABLE ONLY public.fixture_unique_nulls_not_distinct_lower
    ADD CONSTRAINT fixture_unique_nulls_not_distinct_lower_id_key UNIQUE nulls not distinct
    (
        id
    );
SQL
)"

run_case "missing_pk_contract_fails" 1 "$(cat <<'SQL'
CREATE TABLE public.fixture_missing_pk (
    id bigint CHECK ((id IS NOT NULL)) NOT NULL,
    description text DEFAULT E'no;pk'
);
SQL
)"

run_case "unique_using_index_then_fk_does_not_satisfy_id_contract" 1 "$(cat <<'SQL'
CREATE TABLE public.fixture_unique_using_index (
    id bigint NOT NULL,
    token bigint NOT NULL
);

CREATE UNIQUE INDEX fixture_unique_using_index_token_idx
    ON public.fixture_unique_using_index USING btree (token);

ALTER TABLE ONLY public.fixture_unique_using_index
    ADD CONSTRAINT fixture_unique_using_index_token_key UNIQUE USING INDEX fixture_unique_using_index_token_idx,
    ADD CONSTRAINT fixture_unique_using_index_token_fk FOREIGN KEY (id) REFERENCES public.fixture_unique_using_index(token);
SQL
)"

run_case "multiline_unique_using_index_then_fk_does_not_satisfy_id_contract" 1 "$(cat <<'SQL'
CREATE TABLE public.fixture_unique_using_index_multiline (
    id bigint NOT NULL,
    token bigint NOT NULL
);

CREATE UNIQUE INDEX fixture_unique_using_index_multiline_token_idx
    ON public.fixture_unique_using_index_multiline USING btree (token);

ALTER TABLE ONLY public.fixture_unique_using_index_multiline
    ADD CONSTRAINT fixture_unique_using_index_multiline_token_key UNIQUE
    USING INDEX fixture_unique_using_index_multiline_token_idx,
    ADD CONSTRAINT fixture_unique_using_index_multiline_token_fk FOREIGN KEY (id) REFERENCES public.fixture_unique_using_index_multiline(token);
SQL
)"

echo "[guard_flyway_v2_migration_ownership_fixture_matrix] OK"
