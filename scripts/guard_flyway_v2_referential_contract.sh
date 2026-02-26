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
  echo "[guard_flyway_v2_referential_contract] FAIL: $1" >&2
  exit 1
}

[[ -d "$MIGRATIONS_DIR" ]] || fail "missing migrations dir: $MIGRATIONS_DIR"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"

mapfile -t migration_files < <(find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name 'V*__*.sql' | sort)
[[ ${#migration_files[@]} -gt 0 ]] || fail "no migration_v2 files found"

python3 - "$MIGRATIONS_DIR" "${migration_files[@]}" <<'PY'
import pathlib
import re
import sys
from collections import defaultdict

IDENT = r'(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)'
QUAL_IDENT = rf'{IDENT}(?:\s*\.\s*{IDENT})?'


def normalize_ident(raw):
    token = raw.strip()
    if not token:
        return None
    if token[0] == '"' and token[-1] == '"':
        return token[1:-1].replace('""', '"')
    return token.lower()


def normalize_table(raw):
    token = raw.strip().rstrip(",;")
    token = re.sub(r"(?is)^only\s+", "", token).strip()
    parts = [part.strip() for part in token.split(".") if part.strip()]
    if not parts:
        return None
    normalized = [normalize_ident(part) for part in parts]
    if any(part is None for part in normalized):
        return None
    if len(normalized) == 1:
        return f"public.{normalized[0]}"
    return ".".join(normalized[-2:])


def normalize_column_token(raw):
    token = raw.strip().rstrip(",;")
    if "." in token:
        token = token.split(".")[-1].strip()
    return normalize_ident(token)


def split_top_level(raw, delimiter=","):
    out = []
    buf = []
    depth = 0
    in_single = False
    in_double = False
    i = 0
    while i < len(raw):
        ch = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if in_single:
            buf.append(ch)
            if ch == "'" and nxt == "'":
                buf.append(nxt)
                i += 2
                continue
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            buf.append(ch)
            if ch == '"' and nxt == '"':
                buf.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "'":
            in_single = True
            buf.append(ch)
            i += 1
            continue
        if ch == '"':
            in_double = True
            buf.append(ch)
            i += 1
            continue
        if ch == "(":
            depth += 1
            buf.append(ch)
            i += 1
            continue
        if ch == ")":
            if depth > 0:
                depth -= 1
            buf.append(ch)
            i += 1
            continue
        if ch == delimiter and depth == 0:
            out.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    out.append("".join(buf))
    return out


def normalize_column_list(raw):
    cols = []
    for part in split_top_level(raw):
        token = part.strip()
        if not token:
            continue
        match = re.match(rf"(?is)^(?P<ident>{IDENT}(?:\s*\.\s*{IDENT})?)", token)
        if not match:
            return None
        ident = match.group("ident")
        remainder = token[match.end():].lstrip()
        if remainder.startswith("("):
            return None
        col = normalize_column_token(ident)
        if not col:
            return None
        cols.append(col)
    return tuple(cols) if cols else None


def line_number(raw, offset):
    return raw.count("\n", 0, offset) + 1


def strip_sql_comments(raw):
    buf = []
    in_single = False
    in_double = False
    in_line_comment = False
    block_comment_depth = 0
    dollar_tag = None
    i = 0
    while i < len(raw):
        ch = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
                buf.append("\n")
            i += 1
            continue
        if block_comment_depth > 0:
            if ch == "/" and nxt == "*":
                block_comment_depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                block_comment_depth -= 1
                i += 2
                if block_comment_depth == 0 and i < len(raw):
                    upcoming = raw[i]
                    if not upcoming.isspace() and (not buf or not buf[-1].isspace()):
                        buf.append(" ")
                continue
            if ch == "\n":
                buf.append("\n")
            i += 1
            continue
        if dollar_tag is not None:
            if raw.startswith(dollar_tag, i):
                buf.extend(list(dollar_tag))
                i += len(dollar_tag)
                dollar_tag = None
                continue
            buf.append(ch)
            i += 1
            continue
        if in_single:
            buf.append(ch)
            if ch == "'" and nxt == "'":
                buf.append(nxt)
                i += 2
                continue
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            buf.append(ch)
            if ch == '"' and nxt == '"':
                buf.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "-" and nxt == "-":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            if buf and not buf[-1].isspace():
                buf.append(" ")
            block_comment_depth = 1
            i += 2
            continue
        if ch == "'":
            in_single = True
            buf.append(ch)
            i += 1
            continue
        if ch == '"':
            in_double = True
            buf.append(ch)
            i += 1
            continue
        if ch == "$":
            tag_match = re.match(r"\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$", raw[i:])
            if tag_match:
                dollar_tag = tag_match.group(0)
                buf.extend(list(dollar_tag))
                i += len(dollar_tag)
                continue
        buf.append(ch)
        i += 1
    return "".join(buf)


def iter_statements(raw):
    buf = []
    in_single = False
    in_double = False
    in_line_comment = False
    in_block_comment = False
    dollar_tag = None
    i = 0
    start = 0
    while i < len(raw):
        ch = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if in_line_comment:
            buf.append(ch)
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            buf.append(ch)
            if ch == "*" and nxt == "/":
                buf.append(nxt)
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if dollar_tag is not None:
            buf.append(ch)
            if raw.startswith(dollar_tag, i):
                tail = dollar_tag[1:]
                if tail:
                    buf.extend(list(tail))
                i += len(dollar_tag)
                dollar_tag = None
                continue
            i += 1
            continue
        if in_single:
            buf.append(ch)
            if ch == "'" and nxt == "'":
                buf.append(nxt)
                i += 2
                continue
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            buf.append(ch)
            if ch == '"' and nxt == '"':
                buf.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "-" and nxt == "-":
            in_line_comment = True
            buf.append(ch)
            buf.append(nxt)
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            buf.append(ch)
            buf.append(nxt)
            i += 2
            continue
        if ch == "'":
            in_single = True
            buf.append(ch)
            i += 1
            continue
        if ch == '"':
            in_double = True
            buf.append(ch)
            i += 1
            continue
        if ch == "$":
            tag_match = re.match(r"\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$", raw[i:])
            if tag_match:
                dollar_tag = tag_match.group(0)
                buf.extend(list(dollar_tag))
                i += len(dollar_tag)
                continue
        if ch == ";":
            statement = "".join(buf).strip()
            if statement:
                yield statement, line_number(raw, start)
            buf = []
            i += 1
            start = i
            continue
        buf.append(ch)
        i += 1
    statement = "".join(buf).strip()
    if statement:
        yield statement, line_number(raw, start)


def find_matching_paren(raw, open_index):
    depth = 0
    in_single = False
    in_double = False
    i = open_index
    while i < len(raw):
        ch = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if in_single:
            if ch == "'" and nxt == "'":
                i += 2
                continue
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            if ch == '"' and nxt == '"':
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "'":
            in_single = True
            i += 1
            continue
        if ch == '"':
            in_double = True
            i += 1
            continue
        if ch == "(":
            depth += 1
            i += 1
            continue
        if ch == ")":
            depth -= 1
            if depth == 0:
                return i
            i += 1
            continue
        i += 1
    return -1


def add_contract(contracts, contract_counts, table, cols):
    if table and cols and all(cols):
        key = tuple(cols)
        contracts[table].add(key)
        contract_counts[table][key] += 1


def remove_contract(contracts, contract_counts, table, cols):
    if not table or not cols:
        return
    key = tuple(cols)
    current = contract_counts[table].get(key, 0)
    if current <= 1:
        contract_counts[table].pop(key, None)
        contracts[table].discard(key)
        return
    contract_counts[table][key] = current - 1


def add_primary_key(primary_keys, table, cols):
    if table and cols and all(cols):
        primary_keys[table].add(tuple(cols))


def add_fk(foreign_keys, src_table, src_cols, ref_table, ref_cols, source):
    if src_table and src_cols and ref_table:
        foreign_keys.append({
            "src_table": src_table,
            "src_cols": tuple(src_cols),
            "ref_table": ref_table,
            "ref_cols": tuple(ref_cols) if ref_cols else None,
            "source": source,
        })


def remember_constraint(constraint_defs, table, constraint_name, kind, cols):
    if not table or not constraint_name or not cols:
        return
    constraint_defs[table][constraint_name] = {
        "kind": kind,
        "cols": tuple(cols),
    }


def parse_create_table_statement(statement, path, line, contracts, contract_counts, primary_keys, foreign_keys, constraint_defs):
    create_match = re.match(
        rf"(?is)^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?P<table>{QUAL_IDENT})\s*\(",
        statement,
    )
    if not create_match:
        return
    table = normalize_table(create_match.group("table"))
    open_index = statement.find("(", create_match.end() - 1)
    if open_index < 0:
        return
    close_index = find_matching_paren(statement, open_index)
    if close_index < 0:
        return
    body = statement[open_index + 1:close_index]
    source = f"{path.name}:{line}"
    for raw_entry in split_top_level(body):
        entry = raw_entry.strip()
        if not entry:
            continue
        constraint_name = None
        constraint_match = re.match(rf"(?is)^CONSTRAINT\s+(?P<cname>{IDENT})\s+(?P<body>.*)$", entry)
        if constraint_match:
            constraint_name = normalize_ident(constraint_match.group("cname"))
            entry_no_constraint = constraint_match.group("body").strip()
        else:
            entry_no_constraint = entry
        key_match = re.match(r"(?is)^(?P<kind>PRIMARY\s+KEY|UNIQUE)\s*\((?P<cols>.*)\)$", entry_no_constraint)
        if key_match:
            cols = normalize_column_list(key_match.group("cols"))
            add_contract(contracts, contract_counts, table, cols)
            if key_match.group("kind").upper().startswith("PRIMARY"):
                add_primary_key(primary_keys, table, cols)
                if not constraint_name and table:
                    inferred_name = f"{table.split('.')[-1]}_pkey"
                    remember_constraint(constraint_defs, table, inferred_name, "PRIMARY", cols)
                else:
                    remember_constraint(constraint_defs, table, constraint_name, "PRIMARY", cols)
            elif constraint_name:
                remember_constraint(constraint_defs, table, constraint_name, "UNIQUE", cols)
            continue
        fk_match = re.match(
            rf"(?is)^FOREIGN\s+KEY\s*\((?P<src>.*)\)\s+REFERENCES\s+(?P<ref_table>{QUAL_IDENT})(?:\s*\((?P<ref_cols>.*)\))?",
            entry_no_constraint,
        )
        if fk_match:
            src_cols = normalize_column_list(fk_match.group("src"))
            ref_table = normalize_table(fk_match.group("ref_table"))
            ref_cols_raw = fk_match.group("ref_cols")
            ref_cols = normalize_column_list(ref_cols_raw) if ref_cols_raw else None
            add_fk(foreign_keys, table, src_cols, ref_table, ref_cols, source)
            continue
        col_match = re.match(rf"(?is)^(?P<col>{IDENT})\s+", entry)
        if not col_match:
            continue
        col = normalize_column_token(col_match.group("col"))
        tail = entry[col_match.end():]
        if re.search(r"(?is)\bPRIMARY\s+KEY\b", tail):
            add_contract(contracts, contract_counts, table, (col,))
            add_primary_key(primary_keys, table, (col,))
            if table:
                pk_constraint_match = re.search(
                    rf"(?is)\bCONSTRAINT\s+(?P<cname>{IDENT})\s+PRIMARY\s+KEY\b",
                    tail,
                )
                if pk_constraint_match:
                    constraint_name = normalize_ident(pk_constraint_match.group("cname"))
                else:
                    constraint_name = f"{table.split('.')[-1]}_pkey"
                remember_constraint(constraint_defs, table, constraint_name, "PRIMARY", (col,))
        elif re.search(r"(?is)\bUNIQUE\b", tail):
            add_contract(contracts, contract_counts, table, (col,))
        col_fk = re.search(
            rf"(?is)\bREFERENCES\s+(?P<ref_table>{QUAL_IDENT})(?:\s*\((?P<ref_cols>[^)]*)\))?",
            tail,
        )
        if col_fk:
            ref_table = normalize_table(col_fk.group("ref_table"))
            ref_cols_raw = col_fk.group("ref_cols")
            ref_cols = normalize_column_list(ref_cols_raw) if ref_cols_raw else None
            add_fk(foreign_keys, table, (col,), ref_table, ref_cols, source)


def parse_alter_contracts(sql, path, contracts, contract_counts, primary_keys, foreign_keys, constraint_defs):
    alter_pattern = re.compile(
        rf"(?is)ALTER\s+TABLE(?:\s+ONLY)?\s+(?:IF\s+EXISTS\s+)?(?P<table>{QUAL_IDENT})(?P<body>.*?);"
    )
    for alter in alter_pattern.finditer(sql):
        table = normalize_table(alter.group("table"))
        body = alter.group("body")
        source = f"{path.name}:{line_number(sql, alter.start())}"
        for key in re.finditer(
            rf"(?is)\bADD\s+(?:CONSTRAINT\s+(?P<constraint>{IDENT})\s+)?(?P<kind>PRIMARY\s+KEY|UNIQUE)\s*\((?P<cols>[^)]*)\)",
            body,
        ):
            cols = normalize_column_list(key.group("cols"))
            add_contract(contracts, contract_counts, table, cols)
            constraint_name = normalize_ident(key.group("constraint")) if key.group("constraint") else None
            if key.group("kind").upper().startswith("PRIMARY"):
                add_primary_key(primary_keys, table, cols)
                if not constraint_name and table:
                    constraint_name = f"{table.split('.')[-1]}_pkey"
                remember_constraint(constraint_defs, table, constraint_name, "PRIMARY", cols)
            elif constraint_name:
                remember_constraint(constraint_defs, table, constraint_name, "UNIQUE", cols)

        for dropped in re.finditer(
            rf"(?is)\bDROP\s+CONSTRAINT\s+(?P<if_exists>IF\s+EXISTS\s+)?(?P<constraint>{IDENT})",
            body,
        ):
            dropped_name = normalize_ident(dropped.group("constraint"))
            dropped_def = constraint_defs[table].pop(dropped_name, None)
            if dropped_def:
                dropped_cols = dropped_def["cols"]
                remove_contract(contracts, contract_counts, table, dropped_cols)
                if dropped_def["kind"] == "PRIMARY":
                    primary_keys[table].discard(dropped_cols)

        for fk in re.finditer(
            rf"(?is)\bADD\s+(?:CONSTRAINT\s+{IDENT}\s+)?FOREIGN\s+KEY\s*\((?P<src>[^)]*)\)\s+REFERENCES\s+(?P<ref_table>{QUAL_IDENT})(?:\s*\((?P<ref_cols>[^)]*)\))?",
            body,
        ):
            src_cols = normalize_column_list(fk.group("src"))
            ref_table = normalize_table(fk.group("ref_table"))
            ref_cols_raw = fk.group("ref_cols")
            ref_cols = normalize_column_list(ref_cols_raw) if ref_cols_raw else None
            add_fk(foreign_keys, table, src_cols, ref_table, ref_cols, source)


def parse_unique_indexes(sql, path, contracts, contract_counts):
    index_pattern = re.compile(
        rf"(?is)CREATE\s+UNIQUE\s+INDEX(?:\s+IF\s+NOT\s+EXISTS)?\s+{IDENT}\s+ON\s+(?P<table>{QUAL_IDENT})\s+(?:USING\s+\w+\s*)?\((?P<cols>[^)]*)\)(?P<tail>.*?);"
    )
    for index in index_pattern.finditer(sql):
        tail = index.group("tail")
        if re.search(r"(?is)\bWHERE\b", tail):
            continue
        table = normalize_table(index.group("table"))
        cols = normalize_column_list(index.group("cols"))
        add_contract(contracts, contract_counts, table, cols)


def main():
    if len(sys.argv) < 3:
        print("usage error", file=sys.stderr)
        sys.exit(2)

    files = [pathlib.Path(arg) for arg in sys.argv[2:]]
    contracts = defaultdict(set)
    contract_counts = defaultdict(lambda: defaultdict(int))
    primary_keys = defaultdict(set)
    constraint_defs = defaultdict(dict)
    foreign_keys = []

    for path in files:
        raw_sql = path.read_text(encoding="utf-8")
        sql = strip_sql_comments(raw_sql)
        for statement, line in iter_statements(sql):
            parse_create_table_statement(statement, path, line, contracts, contract_counts, primary_keys, foreign_keys, constraint_defs)
        parse_alter_contracts(sql, path, contracts, contract_counts, primary_keys, foreign_keys, constraint_defs)
        parse_unique_indexes(sql, path, contracts, contract_counts)

    if not foreign_keys:
        print("[guard_flyway_v2_referential_contract] FAIL: no FK references parsed from migration_v2", file=sys.stderr)
        sys.exit(1)

    violations = []
    for fk in foreign_keys:
        if fk["ref_cols"] is None:
            pk_candidates = primary_keys.get(fk["ref_table"], set())
            match = any(len(cols) == len(fk["src_cols"]) for cols in pk_candidates)
            if not match:
                known_pk = ", ".join(f"({', '.join(cols)})" for cols in sorted(pk_candidates)) if pk_candidates else "none"
                violations.append(
                    f"{fk['source']}: {fk['src_table']}({', '.join(fk['src_cols'])}) -> "
                    f"{fk['ref_table']}(PRIMARY KEY shorthand) lacks compatible PK target (known PKs: {known_pk})"
                )
            continue

        candidates = contracts.get(fk["ref_table"], set())
        if fk["ref_cols"] not in candidates:
            known = ", ".join(f"({', '.join(cols)})" for cols in sorted(candidates)) if candidates else "none"
            violations.append(
                f"{fk['source']}: {fk['src_table']}({', '.join(fk['src_cols'])}) -> "
                f"{fk['ref_table']}({', '.join(fk['ref_cols'])}) lacks PK/UNIQUE target (known: {known})"
            )

    if violations:
        print("[guard_flyway_v2_referential_contract] Referential target violations:", file=sys.stderr)
        for violation in sorted(violations):
            print(f"  - {violation}", file=sys.stderr)
        sys.exit(1)

    print(
        f"[guard_flyway_v2_referential_contract] OK: checked {len(foreign_keys)} FK references "
        f"against {sum(len(v) for v in contracts.values())} PK/UNIQUE targets"
    )


if __name__ == "__main__":
    main()
PY
