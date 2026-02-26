#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_GUARD="$ROOT_DIR/scripts/guard_flyway_v2_referential_contract.sh"
SOURCE_COMPAT="$ROOT_DIR/scripts/bash_compat.sh"

fail() {
  echo "[guard_flyway_v2_referential_contract_fixture_matrix] FAIL: $1" >&2
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

  cp "$SOURCE_GUARD" "$case_root/scripts/guard_flyway_v2_referential_contract.sh"
  cp "$SOURCE_COMPAT" "$case_root/scripts/bash_compat.sh"
  chmod +x "$case_root/scripts/guard_flyway_v2_referential_contract.sh"

  cat > "$migration_file" <<SQL
$sql
SQL

  set +e
  (cd "$case_root" && bash scripts/guard_flyway_v2_referential_contract.sh) >"$out_file" 2>&1
  status=$?
  set -e

  if [[ "$status" -ne "$expected_exit" ]]; then
    echo "[guard_flyway_v2_referential_contract_fixture_matrix] FAIL: case '$name' expected exit $expected_exit, got $status" >&2
    echo "--- $name output ---" >&2
    cat "$out_file" >&2
    exit 1
  fi
}

run_case "shorthand_single_pk_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.parent_single (
    id bigint NOT NULL
);
ALTER TABLE ONLY public.parent_single
    ADD CONSTRAINT parent_single_pkey PRIMARY KEY (id);

CREATE TABLE public.child_single (
    id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_single
    ADD CONSTRAINT child_single_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_single
    ADD CONSTRAINT child_single_parent_fk FOREIGN KEY (parent_id) REFERENCES public.parent_single;
SQL
)"

run_case "shorthand_requires_primary_key_fail" 1 "$(cat <<'SQL'
CREATE TABLE public.parent_unique_only (
    id bigint NOT NULL
);
ALTER TABLE ONLY public.parent_unique_only
    ADD CONSTRAINT parent_unique_only_id_key UNIQUE (id);

CREATE TABLE public.child_unique_only (
    id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_unique_only
    ADD CONSTRAINT child_unique_only_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_unique_only
    ADD CONSTRAINT child_unique_only_parent_fk FOREIGN KEY (parent_id) REFERENCES public.parent_unique_only;
SQL
)"

run_case "shorthand_composite_pk_pass" 0 "$(cat <<'SQL'
CREATE TABLE public.parent_composite (
    company_id bigint NOT NULL,
    id bigint NOT NULL
);
ALTER TABLE ONLY public.parent_composite
    ADD CONSTRAINT parent_composite_pkey PRIMARY KEY (company_id, id);

CREATE TABLE public.child_composite (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_composite
    ADD CONSTRAINT child_composite_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_composite
    ADD CONSTRAINT child_composite_parent_fk FOREIGN KEY (company_id, parent_id) REFERENCES public.parent_composite;
SQL
)"

run_case "shorthand_composite_count_mismatch_fail" 1 "$(cat <<'SQL'
CREATE TABLE public.parent_composite_mismatch (
    company_id bigint NOT NULL,
    id bigint NOT NULL
);
ALTER TABLE ONLY public.parent_composite_mismatch
    ADD CONSTRAINT parent_composite_mismatch_pkey PRIMARY KEY (company_id, id);

CREATE TABLE public.child_composite_mismatch (
    id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_composite_mismatch
    ADD CONSTRAINT child_composite_mismatch_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_composite_mismatch
    ADD CONSTRAINT child_composite_mismatch_parent_fk FOREIGN KEY (parent_id) REFERENCES public.parent_composite_mismatch;
SQL
)"

run_case "shorthand_uses_latest_primary_key_shape_fail" 1 "$(cat <<'SQL'
CREATE TABLE public.parent_redefined_pk (
    company_id bigint NOT NULL,
    id bigint NOT NULL
);
ALTER TABLE ONLY public.parent_redefined_pk
    ADD CONSTRAINT parent_redefined_pk_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.parent_redefined_pk
    DROP CONSTRAINT parent_redefined_pk_pkey;
ALTER TABLE ONLY public.parent_redefined_pk
    ADD CONSTRAINT parent_redefined_pk_pkey PRIMARY KEY (company_id, id);

CREATE TABLE public.child_redefined_pk (
    id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_redefined_pk
    ADD CONSTRAINT child_redefined_pk_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_redefined_pk
    ADD CONSTRAINT child_redefined_pk_fk FOREIGN KEY (parent_id) REFERENCES public.parent_redefined_pk;
SQL
)"

run_case "column_level_named_pk_survives_default_pkey_drop_if_exists" 0 "$(cat <<'SQL'
CREATE TABLE public.parent_named_colpk (
    id bigint CONSTRAINT parent_named_colpk_custom PRIMARY KEY
);
ALTER TABLE ONLY public.parent_named_colpk
    DROP CONSTRAINT IF EXISTS parent_named_colpk_pkey;

CREATE TABLE public.child_named_colpk (
    id bigint NOT NULL,
    parent_id bigint NOT NULL
);
ALTER TABLE ONLY public.child_named_colpk
    ADD CONSTRAINT child_named_colpk_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.child_named_colpk
    ADD CONSTRAINT child_named_colpk_fk FOREIGN KEY (parent_id) REFERENCES public.parent_named_colpk;
SQL
)"

echo "[guard_flyway_v2_referential_contract_fixture_matrix] OK"
