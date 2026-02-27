#!/usr/bin/env python3
"""Detect high-risk silent-failure patterns and persist retrievable findings."""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence, Set, Tuple

from core import FactRecord, FindingRecord, RagStore, utc_now_iso


FINDING_SOURCE = "silent_failures_v1"

SEVERITY_SCORE = {
    "critical": 0.98,
    "high": 0.9,
    "medium": 0.75,
    "low": 0.6,
}

SEVERITY_ORDER = ["low", "medium", "high", "critical"]

ERP_CRITICAL_HINTS: Dict[str, Tuple[str, ...]] = {
    "money": (
        "ledger",
        "journal",
        "posting",
        "debit",
        "credit",
        "gl",
        "invoice",
        "payment",
        "amount",
        "currency",
        "balance",
        "receivable",
        "payable",
    ),
    "inventory": (
        "inventory",
        "stock",
        "warehouse",
        "batch",
        "serial",
        "reservation",
        "material",
        "onhand",
    ),
    "tax": (
        "tax",
        "vat",
        "gst",
        "tds",
        "tcs",
        "withholding",
    ),
    "auth": (
        "super_admin",
        "superadmin",
        "tenant",
        "companycontext",
        "rbac",
        "authorize",
        "permission",
        "forbidden",
        "accessdenied",
    ),
}

WRITE_HINT_RE = re.compile(
    r"\b(save(?:All)?|insert|update|delete|flush|persist|merge|executeUpdate|batchUpdate|upsert)\s*\(",
    re.IGNORECASE,
)
SQL_WRITE_HINT_RE = re.compile(r"\b(insert\s+into|update\s+\w+|delete\s+from)\b", re.IGNORECASE)
TRANSACTION_HINT_RE = re.compile(
    r"@Transactional|TransactionTemplate|transactionTemplate|withTransaction|inTransaction|beginTransaction|EntityTransaction",
    re.IGNORECASE,
)
EVENT_EMIT_HINT_RE = re.compile(
    r"\b(publishEvent|emitEvent|eventPublisher|applicationEventPublisher|kafkaTemplate\.send|rabbitTemplate\.convertAndSend|jmsTemplate\.convertAndSend|outbound\.send|producer\.send)\b",
    re.IGNORECASE,
)
AFTER_COMMIT_HINT_RE = re.compile(
    r"TransactionalEventListener|afterCommit|after_commit|TransactionSynchronizationManager|registerSynchronization",
    re.IGNORECASE,
)
OUTBOX_HINT_RE = re.compile(r"\boutbox\b|publishOutbox|enqueueOutbox|Outbox", re.IGNORECASE)
BACKOFF_HINT_RE = re.compile(r"backoff|timeout|maxattemptsexpression|delay|multiplier", re.IGNORECASE)

MONEY_CONTEXT_HINT_RE = re.compile(
    r"\b(amount|total|subtotal|price|tax|rate|balance|currency|round|rounding|discount)\b",
    re.IGNORECASE,
)
DOUBLE_FLOAT_HINT_RE = re.compile(r"\b(double|float)\b")
DOUBLE_DECL_RE = re.compile(r"\b(?:double|float)\s+([A-Za-z_][A-Za-z0-9_]*)\b")
BIGDECIMAL_CTOR_RE = re.compile(r"new\s+BigDecimal\s*\(\s*([^)]+?)\s*\)", re.IGNORECASE)
SETSCALE_NO_ROUNDING_RE = re.compile(r"\.setScale\s*\(\s*[^,\)]+\s*\)")

IDEMPOTENCY_GUARD_RE = re.compile(
    r"\bidempot|alreadyProcessed|already_processed|duplicate|dedup|existsBy.*(request|event|idempot)|on\s+conflict|insert\s+ignore|unique\s+constraint|requestId|idempotencyKey\b",
    re.IGNORECASE,
)
RETRY_HINT_RE = re.compile(r"@Retryable|retry|attempt", re.IGNORECASE)

PATH_SCOPE_RE = re.compile(
    r"@PathVariable[^\n]*(company|tenant|id)[A-Za-z0-9_]*|\{(company|tenant)(Id)?\}|/(companies|tenants)/\{id\}",
    re.IGNORECASE,
)
CONTEXT_SCOPE_RE = re.compile(
    r"CompanyContext|TenantContext|getCurrentCompanyId|getCurrentTenantId|contextCompany|contextTenant|tokenCompany|tokenTenant|getCompanyId\(|getTenantId\(",
    re.IGNORECASE,
)
BINDING_SCOPE_RE = re.compile(
    r"Objects\.equals\s*\([^\)]*(companyId|tenantId|id)[^\)]*(context|current|token|claim|company|tenant)"
    r"|(?:companyId|tenantId|id)\s*\.equals\s*\([^\)]*(context|current|token|claim|company|tenant)"
    r"|if\s*\([^\)]*(companyId|tenantId|id)[^\)]*(==|!=|equals)[^\)]*(context|current|token|claim|company|tenant)"
    r"|set(?:Current)?(?:Company|Tenant)Id\s*\(\s*(companyId|tenantId|id)\s*\)"
    r"|validate[A-Za-z0-9_]*\s*\([^\)]*(companyId|tenantId|id)[^\)]*(context|current|token|claim|company|tenant)",
    re.IGNORECASE,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Detect retrievable silent-failure findings")
    parser.add_argument("--db-path", default=".tmp/rag/context_index.sqlite")
    parser.add_argument("--top-k", type=int, default=30)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--output", default="")
    return parser.parse_args()


def connect(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    return conn


def strip_inline_comments(text: str) -> str:
    text = re.sub(r"//.*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return text


def is_test_path(file_path: str) -> bool:
    normalized = file_path.replace("\\", "/").lower()
    return "/src/test/" in normalized


def bump_severity(base: str, bump: int) -> str:
    if base not in SEVERITY_ORDER:
        base = "medium"
    idx = SEVERITY_ORDER.index(base)
    idx = min(len(SEVERITY_ORDER) - 1, max(0, idx + bump))
    return SEVERITY_ORDER[idx]


def infer_erp_criticality(text: str, file_path: str = "") -> List[str]:
    lowered = f"{text}\n{file_path}".lower()
    tags: List[str] = []
    for tag, hints in ERP_CRITICAL_HINTS.items():
        if any(hint in lowered for hint in hints):
            tags.append(tag)
    return sorted(set(tags))


def metadata_with_guidance(
    *,
    pattern: str,
    why: str,
    suggested_fix_pattern: str,
    erp_criticality: Sequence[str],
    extra: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "pattern": pattern,
        "why": why,
        "suggested_fix_pattern": suggested_fix_pattern,
        "erp_criticality": sorted(set(str(tag) for tag in erp_criticality if str(tag).strip())),
    }
    if extra:
        payload.update(extra)
    return payload


def detect_write_signals(content: str, symbol_predicates: Dict[str, List[str]]) -> Tuple[bool, List[str], List[str]]:
    write_signals: List[str] = []
    if WRITE_HINT_RE.search(content):
        write_signals.append("code_write_call")
    if SQL_WRITE_HINT_RE.search(content):
        write_signals.append("sql_write_statement")
    write_tables = [item for item in symbol_predicates.get("WRITES_TABLE", []) if str(item).strip()]
    if write_tables:
        write_signals.append("facts_writes_table")
    has_writes = bool(write_signals)
    return has_writes, sorted(set(write_signals)), sorted(set(write_tables))


def load_symbol_predicates(conn: sqlite3.Connection) -> Dict[str, Dict[str, List[str]]]:
    predicates = {
        "TRANSACTIONAL",
        "RETRY_POLICY",
        "WRITES_TABLE",
        "READS_TABLE",
        "EMITS_EVENT",
        "LISTENS_EVENT",
        "CALLS",
        "HANDLER_CALLS",
        "ROUTE_CALLS",
        "IDEMPOTENCY_KEY_TYPE",
        "ROUTE_IDEMPOTENCY_KEY_TYPE",
    }
    placeholders = ",".join("?" for _ in predicates)
    rows = conn.execute(
        f"""
        SELECT subject, predicate, object
        FROM facts
        WHERE predicate IN ({placeholders})
        """,
        tuple(sorted(predicates)),
    ).fetchall()
    index: Dict[str, Dict[str, List[str]]] = {}
    for row in rows:
        subject = str(row["subject"] or "")
        predicate = str(row["predicate"] or "")
        obj = str(row["object"] or "")
        if not subject or not predicate:
            continue
        bucket = index.setdefault(subject, {})
        values = bucket.setdefault(predicate, [])
        if obj and obj not in values:
            values.append(obj)
    return index


def detect_swallowed_catches(rows: List[sqlite3.Row]) -> List[FindingRecord]:
    findings: List[FindingRecord] = []
    catch_re = re.compile(r"catch\s*\([^\)]*\)\s*\{(.*?)\}", re.DOTALL)

    for row in rows:
        content = str(row["content"])
        file_path = str(row["file_path"] or "")
        for match in catch_re.finditer(content):
            body = strip_inline_comments(match.group(1)).strip()
            body_lower = body.lower()
            is_empty = not body
            has_throw = "throw" in body_lower
            has_return = "return" in body_lower
            only_logging = False
            if body and not has_throw and not has_return:
                body_lines = [line.strip() for line in body.splitlines() if line.strip()]
                if body_lines and all(
                    line.startswith(("log.", "logger.", "LOGGER.", "System.out", "System.err"))
                    for line in body_lines
                ):
                    only_logging = True

            if is_empty or only_logging:
                criticality = infer_erp_criticality(content, file_path)
                base = "high" if is_empty else "medium"
                severity = bump_severity(base, 1 if criticality and not is_test_path(file_path) else 0)
                findings.append(
                    FindingRecord(
                        finding_type="SWALLOWED_EXCEPTION",
                        severity=severity,
                        title="Possible swallowed exception",
                        message=(
                            "Empty catch block detected." if is_empty else "Catch block logs but does not rethrow or return error state."
                        ),
                        file_path=file_path,
                        line_start=int(row["line_start"]),
                        line_end=int(row["line_end"]),
                        symbol=str(row["symbol"] or ""),
                        source=FINDING_SOURCE,
                        metadata=metadata_with_guidance(
                            pattern="catch_without_propagation",
                            why="Exceptions can be silently ignored, causing hidden workflow divergence.",
                            suggested_fix_pattern="Return explicit error state or rethrow domain exception with contextual metadata.",
                            erp_criticality=criticality,
                        ),
                    )
                )

    return findings


def likely_ignored_return(line: str) -> bool:
    stripped = line.strip()
    if not stripped or stripped.startswith(("//", "*", "@")):
        return False
    if "=" in stripped or stripped.startswith(("return ", "throw ", "if ", "for ", "while ", "switch ")):
        return False
    if not stripped.endswith(";"):
        return False

    call_match = re.match(r"^([A-Za-z_][A-Za-z0-9_$.]*)\s*\([^;]*\);$", stripped)
    if not call_match:
        return False

    call_name = call_match.group(1).split(".")[-1]
    if call_name.lower() in {
        "debug",
        "info",
        "warn",
        "error",
        "trace",
        "println",
        "printstacktrace",
        "add",
        "put",
        "set",
    }:
        return False

    risky_prefixes = ("is", "has", "can", "should", "validate", "check", "verify", "exists", "try")
    risky_names = {"save", "publish", "send", "flush", "update", "execute"}

    return call_name.startswith(risky_prefixes) or call_name in risky_names


def detect_ignored_returns(rows: List[sqlite3.Row]) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        content = str(row["content"])
        file_path = str(row["file_path"] or "")
        for line in content.splitlines():
            if likely_ignored_return(line):
                criticality = infer_erp_criticality(content, file_path)
                severity = bump_severity("medium", 1 if criticality and not is_test_path(file_path) else 0)
                findings.append(
                    FindingRecord(
                        finding_type="IGNORED_RETURN_VALUE",
                        severity=severity,
                        title="Potential ignored return value",
                        message=f"Line appears to call '{line.strip()}' without checking return value.",
                        file_path=file_path,
                        line_start=int(row["line_start"]),
                        line_end=int(row["line_end"]),
                        symbol=str(row["symbol"] or ""),
                        source=FINDING_SOURCE,
                        metadata=metadata_with_guidance(
                            pattern="call_without_assignment_or_branch",
                            why="Control flow may ignore failure or duplicate-handling signals.",
                            suggested_fix_pattern="Capture return value and branch on success/failure or idempotent duplicate result.",
                            erp_criticality=criticality,
                        ),
                    )
                )
                break

    return findings


def detect_retry_without_backoff(rows: List[sqlite3.Row], symbol_facts: Dict[str, Dict[str, List[str]]]) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        content = str(row["content"])
        symbol = str(row["symbol"] or "")
        file_path = str(row["file_path"] or "")
        predicates = symbol_facts.get(symbol, {})
        has_retry = "@Retryable" in content or bool(predicates.get("RETRY_POLICY"))
        if not has_retry:
            continue
        if BACKOFF_HINT_RE.search(content.lower()):
            continue
        criticality = infer_erp_criticality(content, file_path)
        severity = "high" if {"money", "inventory", "tax"}.intersection(criticality) else "medium"
        findings.append(
            FindingRecord(
                finding_type="RETRY_WITHOUT_BACKOFF",
                severity=severity,
                title="Retry policy missing explicit backoff/timeout hints",
                message="@Retryable detected without obvious backoff, timeout, or jitter controls.",
                file_path=file_path,
                line_start=int(row["line_start"]),
                line_end=int(row["line_end"]),
                symbol=symbol,
                source=FINDING_SOURCE,
                metadata=metadata_with_guidance(
                    pattern="retry_without_backoff",
                    why="Aggressive retries can amplify duplicate writes and production incidents.",
                    suggested_fix_pattern="Add bounded retries with explicit backoff + timeout and pair retryable mutations with idempotency guards.",
                    erp_criticality=criticality,
                ),
            )
        )

    return findings


def detect_transactional_gap(
    rows: List[sqlite3.Row],
    symbol_facts: Dict[str, Dict[str, List[str]]],
) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        file_path = str(row["file_path"] or "")
        if is_test_path(file_path):
            continue
        content = str(row["content"] or "")
        symbol = str(row["symbol"] or "")
        predicates = symbol_facts.get(symbol, {})
        has_writes, write_signals, write_tables = detect_write_signals(content, predicates)
        if not has_writes:
            continue

        has_transaction = bool(predicates.get("TRANSACTIONAL")) or bool(TRANSACTION_HINT_RE.search(content))
        if has_transaction:
            continue

        criticality = infer_erp_criticality(
            content + "\n" + "\n".join(write_tables),
            file_path,
        )
        sensitive = bool({"money", "inventory", "tax"}.intersection(criticality))
        severity = "critical" if sensitive else "high"
        findings.append(
            FindingRecord(
                finding_type="TRANSACTIONAL_GAP",
                severity=severity,
                title="Mutation path without clear transaction boundary",
                message="DB write signals found but no explicit transactional boundary detected.",
                file_path=file_path,
                line_start=int(row["line_start"]),
                line_end=int(row["line_end"]),
                symbol=symbol,
                source=FINDING_SOURCE,
                metadata=metadata_with_guidance(
                    pattern="writes_without_transaction_boundary",
                    why="Partial commits in ERP mutation flows can desync ledger, inventory, or tax state.",
                    suggested_fix_pattern="Wrap related writes in a single transaction boundary and fail atomically on downstream errors.",
                    erp_criticality=criticality,
                    extra={
                        "write_signals": write_signals,
                        "write_tables": write_tables,
                        "has_transaction_fact": bool(predicates.get("TRANSACTIONAL")),
                    },
                ),
            )
        )

    return findings


def detect_event_before_commit(
    rows: List[sqlite3.Row],
    symbol_facts: Dict[str, Dict[str, List[str]]],
) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        file_path = str(row["file_path"] or "")
        if is_test_path(file_path):
            continue
        content = str(row["content"] or "")
        symbol = str(row["symbol"] or "")
        predicates = symbol_facts.get(symbol, {})
        has_writes, write_signals, write_tables = detect_write_signals(content, predicates)
        if not has_writes:
            continue

        emits_event = bool(predicates.get("EMITS_EVENT")) or bool(EVENT_EMIT_HINT_RE.search(content))
        if not emits_event:
            continue

        has_after_commit = bool(AFTER_COMMIT_HINT_RE.search(content))
        has_outbox = bool(OUTBOX_HINT_RE.search(content))
        event_targets = predicates.get("EMITS_EVENT", [])

        criticality = infer_erp_criticality(
            content + "\n" + "\n".join(event_targets) + "\n" + "\n".join(write_tables),
            file_path,
        )
        sensitive = bool({"money", "inventory", "tax"}.intersection(criticality))

        if not has_after_commit:
            severity = "critical" if sensitive else "high"
            findings.append(
                FindingRecord(
                    finding_type="EVENT_BEFORE_COMMIT",
                    severity=severity,
                    title="Event emission appears before reliable commit guard",
                    message="Method writes data and emits events without clear after-commit/outbox signaling.",
                    file_path=file_path,
                    line_start=int(row["line_start"]),
                    line_end=int(row["line_end"]),
                    symbol=symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="event_emit_with_mutation_without_after_commit",
                        why="Downstream consumers may observe events for state that later rolls back.",
                        suggested_fix_pattern="Emit via outbox or after-commit hooks only after durable transaction success.",
                        erp_criticality=criticality,
                        extra={
                            "write_signals": write_signals,
                            "event_targets": event_targets,
                            "write_tables": write_tables,
                        },
                    ),
                )
            )

        if not has_outbox:
            severity = "high" if sensitive else "medium"
            findings.append(
                FindingRecord(
                    finding_type="NO_OUTBOX",
                    severity=severity,
                    title="Mutation+event flow without outbox evidence",
                    message="Method appears to mutate state and emit events without outbox evidence.",
                    file_path=file_path,
                    line_start=int(row["line_start"]),
                    line_end=int(row["line_end"]),
                    symbol=symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="mutation_event_without_outbox",
                        why="At-least-once delivery with retries can create phantom or duplicated downstream effects.",
                        suggested_fix_pattern="Adopt transactional outbox or equivalent durable publish mechanism tied to commit.",
                        erp_criticality=criticality,
                        extra={"event_targets": event_targets, "write_tables": write_tables},
                    ),
                )
            )

    return findings


def detect_money_rounding(rows: List[sqlite3.Row]) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        file_path = str(row["file_path"] or "")
        if is_test_path(file_path):
            continue
        content = str(row["content"] or "")
        symbol = str(row["symbol"] or "")
        criticality = infer_erp_criticality(content, file_path)
        money_context = bool({"money", "tax"}.intersection(criticality)) or bool(MONEY_CONTEXT_HINT_RE.search(content))
        if not money_context:
            continue

        has_money_rounding_issue = False
        for line in content.splitlines():
            line_lower = line.lower()
            if not MONEY_CONTEXT_HINT_RE.search(line):
                continue
            if DOUBLE_FLOAT_HINT_RE.search(line_lower):
                has_money_rounding_issue = True
                break
        if has_money_rounding_issue:
            severity = "critical" if "tax" in criticality else "high"
            findings.append(
                FindingRecord(
                    finding_type="MONEY_ROUNDING",
                    severity=severity,
                    title="Floating-point arithmetic in money/tax context",
                    message="Detected double/float usage in amount/tax/total calculation context.",
                    file_path=file_path,
                    line_start=int(row["line_start"]),
                    line_end=int(row["line_end"]),
                    symbol=symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="float_or_double_in_money_context",
                        why="Binary floating-point causes cumulative rounding drift in financial books.",
                        suggested_fix_pattern="Use BigDecimal with explicit scale and rounding mode for money/tax calculations.",
                        erp_criticality=criticality,
                    ),
                )
            )

        double_vars = set(DOUBLE_DECL_RE.findall(content))
        bad_ctor_args: List[str] = []
        for match in BIGDECIMAL_CTOR_RE.finditer(content):
            arg = match.group(1).strip()
            if not arg:
                continue
            if arg.startswith(("\"", "'", "new MathContext", "MathContext", "BigInteger", "BigDecimal")):
                continue
            if re.fullmatch(r"[0-9]+\.[0-9]+[dDfF]?", arg):
                bad_ctor_args.append(arg)
                continue
            if arg in double_vars:
                bad_ctor_args.append(arg)

        if bad_ctor_args:
            severity = "critical" if "tax" in criticality else "high"
            findings.append(
                FindingRecord(
                    finding_type="BIGDECIMAL_MISUSE",
                    severity=severity,
                    title="BigDecimal constructed from floating-point source",
                    message="BigDecimal constructor appears to consume double/float literal or variable.",
                    file_path=file_path,
                    line_start=int(row["line_start"]),
                    line_end=int(row["line_end"]),
                    symbol=symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="bigdecimal_from_double",
                        why="Constructing BigDecimal from floating values embeds binary precision error.",
                        suggested_fix_pattern="Use BigDecimal.valueOf(...) or string constructor from canonical decimal text.",
                        erp_criticality=criticality,
                        extra={"arguments": sorted(set(bad_ctor_args))},
                    ),
                )
            )

        if SETSCALE_NO_ROUNDING_RE.search(content):
            severity = "high" if {"money", "tax"}.intersection(criticality) else "medium"
            findings.append(
                FindingRecord(
                    finding_type="BIGDECIMAL_MISUSE",
                    severity=severity,
                    title="BigDecimal#setScale without explicit rounding mode",
                    message="setScale(value) detected without rounding mode in money/tax context.",
                    file_path=file_path,
                    line_start=int(row["line_start"]),
                    line_end=int(row["line_end"]),
                    symbol=symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="setscale_without_rounding_mode",
                        why="Implicit rounding assumptions create inconsistent totals across services.",
                        suggested_fix_pattern="Specify explicit rounding mode (e.g., HALF_UP/HALF_EVEN) with agreed scale policy.",
                        erp_criticality=criticality,
                    ),
                )
            )

    return findings


def detect_idempotency_risk(
    rows: List[sqlite3.Row],
    symbol_facts: Dict[str, Dict[str, List[str]]],
) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        file_path = str(row["file_path"] or "")
        if is_test_path(file_path):
            continue
        content = str(row["content"] or "")
        symbol = str(row["symbol"] or "")
        predicates = symbol_facts.get(symbol, {})
        has_writes, write_signals, write_tables = detect_write_signals(content, predicates)
        if not has_writes:
            continue

        has_retry = bool(predicates.get("RETRY_POLICY")) or bool(RETRY_HINT_RE.search(content))
        if not has_retry:
            continue
        if IDEMPOTENCY_GUARD_RE.search(content):
            continue

        criticality = infer_erp_criticality(
            content + "\n" + "\n".join(write_tables),
            file_path,
        )
        sensitive = bool({"money", "inventory", "tax"}.intersection(criticality))
        severity = "critical" if sensitive else "high"
        findings.append(
            FindingRecord(
                finding_type="IDEMPOTENCY_GAP",
                severity=severity,
                title="Retryable mutation without idempotency guard",
                message="Retry signals detected on mutation path without obvious idempotency/duplicate guard.",
                file_path=file_path,
                line_start=int(row["line_start"]),
                line_end=int(row["line_end"]),
                symbol=symbol,
                source=FINDING_SOURCE,
                metadata=metadata_with_guidance(
                    pattern="retryable_mutation_without_idempotency",
                    why="Retries can duplicate postings, payments, or stock movements.",
                    suggested_fix_pattern="Add idempotency key uniqueness checks and short-circuit already-processed operations.",
                    erp_criticality=criticality,
                    extra={"write_signals": write_signals, "write_tables": write_tables},
                ),
            )
        )

    return findings


def normalize_type_symbol(raw_type: str) -> str:
    token = str(raw_type or "").strip()
    if token.lower().startswith("type:"):
        token = token[len("type:") :]
    return token.strip()


def idempotency_key_bucket(type_symbol: str) -> str:
    normalized = normalize_type_symbol(type_symbol).lower()
    leaf = normalized.split(".")[-1]
    if "uuid" in normalized:
        return "uuid"
    if "ulid" in normalized:
        return "ulid"
    if "snowflake" in normalized:
        return "numeric"
    if leaf in {"long", "integer", "int", "short", "biginteger", "bigint", "byte"}:
        return "numeric"
    if leaf in {"string", "charsequence"}:
        return "string"
    return "other"


def idempotency_bucket_mismatch_severity(left: str, right: str) -> str:
    pair = {left, right}
    if len(pair) <= 1:
        return ""
    if pair in ({"numeric", "uuid"}, {"numeric", "ulid"}, {"uuid", "ulid"}):
        return "high"
    if "string" in pair and ({"uuid", "numeric", "ulid"} & pair):
        return "medium"
    if "other" in pair:
        return ""
    return "medium"


def detect_idempotency_type_mismatch(
    rows: List[sqlite3.Row],
    symbol_facts: Dict[str, Dict[str, List[str]]],
) -> List[FindingRecord]:
    findings: List[FindingRecord] = []
    rows_by_symbol: Dict[str, sqlite3.Row] = {
        str(row["symbol"] or ""): row
        for row in rows
        if str(row["symbol"] or "").strip()
    }

    seen: Set[Tuple[str, str, str, str]] = set()
    for caller_symbol, predicates in symbol_facts.items():
        caller_row = rows_by_symbol.get(caller_symbol)
        if not caller_row:
            continue
        caller_file_path = str(caller_row["file_path"] or "")
        if is_test_path(caller_file_path):
            continue
        caller_content = str(caller_row["content"] or "")
        caller_types_raw = [normalize_type_symbol(item) for item in predicates.get("IDEMPOTENCY_KEY_TYPE", [])]
        caller_types = sorted({item for item in caller_types_raw if item})
        if not caller_types:
            continue
        caller_buckets = sorted({idempotency_key_bucket(item) for item in caller_types if idempotency_key_bucket(item) != "other"})
        if len(caller_buckets) != 1:
            continue
        caller_bucket = caller_buckets[0]

        outbound_targets: List[str] = []
        for predicate in ("HANDLER_CALLS", "ROUTE_CALLS", "CALLS"):
            for raw_target in predicates.get(predicate, []):
                target = str(raw_target or "").strip()
                if not target or "#" not in target or target.startswith("route:"):
                    continue
                if target not in outbound_targets:
                    outbound_targets.append(target)

        for target_symbol in outbound_targets:
            callee_predicates = symbol_facts.get(target_symbol, {})
            callee_types_raw = [normalize_type_symbol(item) for item in callee_predicates.get("IDEMPOTENCY_KEY_TYPE", [])]
            callee_types = sorted({item for item in callee_types_raw if item})
            if not callee_types:
                continue
            callee_buckets = sorted({idempotency_key_bucket(item) for item in callee_types if idempotency_key_bucket(item) != "other"})
            if len(callee_buckets) != 1:
                continue
            callee_bucket = callee_buckets[0]
            if caller_bucket == callee_bucket:
                continue

            mismatch_severity = idempotency_bucket_mismatch_severity(caller_bucket, callee_bucket)
            if not mismatch_severity:
                continue

            key = (caller_symbol, target_symbol, caller_bucket, callee_bucket)
            if key in seen:
                continue
            seen.add(key)

            criticality = infer_erp_criticality(
                caller_content + "\n" + caller_symbol + "\n" + target_symbol,
                caller_file_path,
            )
            severity = mismatch_severity
            if mismatch_severity == "high" and {"money", "inventory", "tax", "auth"}.intersection(criticality):
                severity = "critical"

            findings.append(
                FindingRecord(
                    finding_type="IDEMPOTENCY_KEY_TYPE_MISMATCH",
                    severity=severity,
                    title="Cross-module idempotency key type mismatch",
                    message=(
                        "Caller and callee use incompatible idempotency key types "
                        f"({caller_bucket} -> {callee_bucket}) on the same call chain."
                    ),
                    file_path=caller_file_path,
                    line_start=int(caller_row["line_start"] or 0),
                    line_end=int(caller_row["line_end"] or 0),
                    symbol=caller_symbol,
                    source=FINDING_SOURCE,
                    metadata=metadata_with_guidance(
                        pattern="caller_callee_idempotency_type_mismatch",
                        why=(
                            "Different idempotency key representations across module boundaries can bypass dedupe checks "
                            "and create duplicate side effects."
                        ),
                        suggested_fix_pattern=(
                            "Standardize idempotency key contract on one canonical type (UUID/string/numeric) and normalize at boundary adapters."
                        ),
                        erp_criticality=criticality,
                        extra={
                            "caller_symbol": caller_symbol,
                            "callee_symbol": target_symbol,
                            "caller_bucket": caller_bucket,
                            "callee_bucket": callee_bucket,
                            "caller_types": caller_types,
                            "callee_types": callee_types,
                        },
                    ),
                )
            )

    return findings


def detect_cross_tenant_scope_split(rows: List[sqlite3.Row]) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    for row in rows:
        file_path = str(row["file_path"] or "")
        if is_test_path(file_path):
            continue
        kind = str(row["kind"] or "")
        if kind not in {"java_route_handler", "java_method"}:
            continue
        content = str(row["content"] or "")
        symbol = str(row["symbol"] or "")

        has_path_scope = bool(PATH_SCOPE_RE.search(content))
        has_context_scope = bool(CONTEXT_SCOPE_RE.search(content))
        if not (has_path_scope and has_context_scope):
            continue
        if BINDING_SCOPE_RE.search(content):
            continue

        criticality = sorted(set(infer_erp_criticality(content, file_path) + ["auth"]))
        findings.append(
            FindingRecord(
                finding_type="CROSS_TENANT_SCOPE_SPLIT",
                severity="critical",
                title="Path tenant/company scope and context scope are not explicitly bound",
                message="Route appears to use {companyId}/{tenantId} and context-company logic without explicit equality/binding step.",
                file_path=file_path,
                line_start=int(row["line_start"]),
                line_end=int(row["line_end"]),
                symbol=symbol,
                source=FINDING_SOURCE,
                metadata=metadata_with_guidance(
                    pattern="path_scope_plus_context_scope_without_binding",
                    why="Tenant boundary drift can authorize one tenant while mutating another tenant's scope.",
                    suggested_fix_pattern="Explicitly validate path company/tenant ID equals authenticated context before bypass/authorization decisions.",
                    erp_criticality=criticality,
                ),
            )
        )

    return findings


def detect_undefined_config(conn: sqlite3.Connection) -> List[FindingRecord]:
    findings: List[FindingRecord] = []

    def normalize_key(key: str) -> str:
        base = key.lower().strip()
        if base.startswith("config:"):
            base = base[len("config:") :]
        return base

    def canonical_key(key: str) -> str:
        base = normalize_key(key)
        base = base.replace("-", "").replace("_", "")
        return base

    defined_rows = conn.execute(
        "SELECT DISTINCT subject FROM facts WHERE predicate = 'CONFIG_DEFINED'"
    ).fetchall()
    defined_exact = {normalize_key(str(row["subject"])) for row in defined_rows}
    defined_canonical = {canonical_key(str(row["subject"])) for row in defined_rows}

    used_rows = conn.execute(
        """
        SELECT object, file_path, line_start, line_end, subject
        FROM facts
        WHERE predicate IN ('USES_CONFIG', 'USES_ENV')
        """
    ).fetchall()

    seen = set()
    for row in used_rows:
        key = str(row["object"])
        key_norm = normalize_key(key)
        if key_norm in defined_exact:
            continue
        key_canonical = canonical_key(key)
        if key_canonical in defined_canonical:
            continue
        if any(d.startswith(f"{key_norm}.") or key_norm.startswith(f"{d}.") for d in defined_exact):
            continue
        if key_norm in seen:
            continue
        seen.add(key_norm)

        findings.append(
            FindingRecord(
                finding_type="UNDEFINED_CONFIG_KEY",
                severity="high",
                title="Config key used but not defined",
                message=f"{key} is referenced but not found in indexed config definitions.",
                file_path=str(row["file_path"] or ""),
                line_start=int(row["line_start"] or 0),
                line_end=int(row["line_end"] or 0),
                symbol=str(row["subject"] or ""),
                source=FINDING_SOURCE,
                metadata=metadata_with_guidance(
                    pattern="config_use_without_definition",
                    why="Missing config contracts create environment-specific behavior drift.",
                    suggested_fix_pattern="Define the key in canonical config schema and enforce default/required validation.",
                    erp_criticality=["auth"] if "security" in str(row["file_path"] or "").lower() else [],
                    extra={"config_key": key},
                ),
            )
        )

    return findings


def finding_to_fact(idx: int, finding: FindingRecord) -> FactRecord:
    subject = f"finding:{FINDING_SOURCE}:{idx}"
    predicate = f"FINDING_{finding.finding_type}"
    obj = finding.symbol or finding.file_path or "unknown"
    confidence = SEVERITY_SCORE.get(finding.severity, 0.6)
    return FactRecord(
        subject=subject,
        predicate=predicate,
        object=obj,
        file_path=finding.file_path,
        line_start=finding.line_start,
        line_end=finding.line_end,
        confidence=confidence,
        evidence=finding.message,
        metadata={
            "severity": finding.severity,
            "title": finding.title,
            "source": FINDING_SOURCE,
            **finding.metadata,
        },
    )


def summarize(findings: List[FindingRecord], top_k: int) -> Dict[str, Any]:
    severity_counts: Dict[str, int] = {"critical": 0, "high": 0, "medium": 0, "low": 0}
    for finding in findings:
        severity_counts[finding.severity] = severity_counts.get(finding.severity, 0) + 1

    top = findings[:top_k]
    return {
        "generated_at": utc_now_iso(),
        "source": FINDING_SOURCE,
        "finding_count": len(findings),
        "severity_counts": severity_counts,
        "top_findings": [
            {
                "finding_type": finding.finding_type,
                "severity": finding.severity,
                "title": finding.title,
                "message": finding.message,
                "file_path": finding.file_path,
                "line_start": finding.line_start,
                "line_end": finding.line_end,
                "symbol": finding.symbol,
                "metadata": finding.metadata,
            }
            for finding in top
        ],
    }


def main() -> int:
    args = parse_args()
    db_path = Path(args.db_path).resolve()
    if not db_path.exists():
        print(f"[silent-failures] index not found: {db_path}", file=sys.stderr)
        return 2

    conn = connect(db_path)
    rows = conn.execute(
        """
        SELECT file_path, line_start, line_end, kind, symbol, content
        FROM chunks
        WHERE language = 'java' AND kind IN ('java_method', 'java_route_handler')
        """
    ).fetchall()
    symbol_facts = load_symbol_predicates(conn)

    findings: List[FindingRecord] = []
    findings.extend(detect_swallowed_catches(rows))
    findings.extend(detect_ignored_returns(rows))
    findings.extend(detect_retry_without_backoff(rows, symbol_facts))
    findings.extend(detect_transactional_gap(rows, symbol_facts))
    findings.extend(detect_event_before_commit(rows, symbol_facts))
    findings.extend(detect_money_rounding(rows))
    findings.extend(detect_idempotency_risk(rows, symbol_facts))
    findings.extend(detect_idempotency_type_mismatch(rows, symbol_facts))
    findings.extend(detect_cross_tenant_scope_split(rows))
    findings.extend(detect_undefined_config(conn))

    deduped: List[FindingRecord] = []
    seen = set()
    for finding in findings:
        key = (
            finding.finding_type,
            finding.severity,
            finding.file_path,
            finding.line_start,
            finding.line_end,
            finding.symbol,
            finding.message,
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(finding)
    findings = deduped

    # Deterministic ordering: high severity first, then file/symbol.
    severity_rank = {"critical": 0, "high": 1, "medium": 2, "low": 3}
    findings.sort(
        key=lambda item: (
            severity_rank.get(item.severity, 99),
            item.file_path,
            item.line_start,
            item.finding_type,
        )
    )

    conn.close()

    store = RagStore(db_path)
    store.init_schema()
    store.replace_findings(FINDING_SOURCE, findings)
    store.conn.execute("DELETE FROM facts WHERE subject LIKE ?", (f"finding:{FINDING_SOURCE}:%",))
    finding_facts = [finding_to_fact(idx, finding) for idx, finding in enumerate(findings, start=1)]
    inserted_facts = store.insert_facts(finding_facts)
    store.commit()
    store.close()

    report = summarize(findings, top_k=args.top_k)
    report["facts_inserted"] = inserted_facts

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("[silent-failures] summary:")
        print(json.dumps(report, indent=2))

    return 0


if __name__ == "__main__":
    sys.exit(main())
