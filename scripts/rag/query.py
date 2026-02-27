#!/usr/bin/env python3
"""Hybrid graph + lexical retrieval and context-pack builder."""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from collections import defaultdict, deque
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from core import compact_excerpt, utc_now_iso, word_tokens


TRUTH_HIERARCHY = [
    "exact_code_excerpt",
    "generated_code_fact",
    "grounded_summary",
    "conjecture_disallowed",
]

TRAVERSABLE_PREDICATES = {
    "CALLS",
    "IMPORTS",
    "DEPENDS_ON",
    "EXTENDS",
    "IMPLEMENTS",
    "ROUTE_TO_HANDLER",
    "HANDLES_ROUTE",
    "ROUTE_CALLS",
    "HANDLER_CALLS",
    "ROUTE_ACCEPTS_TYPE",
    "ROUTE_ACCEPTS_CONTAINS_TYPE",
    "ROUTE_RETURNS_TYPE",
    "ROUTE_RETURNS_CONTAINS_TYPE",
    "ROUTE_CONTRACT",
    "ROUTE_IDEMPOTENCY_KEY_TYPE",
    "CANONICAL_ROUTE",
    "ALTERNATE_ROUTE",
    "WORKFLOW_CHAIN",
    "WORKFLOW_OWNER",
    "WORKFLOW_DECISION",
    "BUSINESS_EVENT",
    "MODULE_DEPENDS_ON",
    "USES_CONFIG",
    "USES_ENV",
    "READS_TABLE",
    "WRITES_TABLE",
    "ACCEPTS_TYPE",
    "ACCEPTS_CONTAINS_TYPE",
    "RETURNS_TYPE",
    "RETURNS_CONTAINS_TYPE",
    "LOGIC_FINGERPRINT",
    "IDEMPOTENCY_KEY_TYPE",
    "IDEMPOTENCY_KEY_PARAM",
    "TABLE_DEFINED",
    "RETRY_POLICY",
    "TRANSACTIONAL",
    "SCHEDULE_TRIGGER",
    "SECURED_BY",
    "LISTENS_EVENT",
    "EMITS_EVENT",
    "TEST_COVERS",
    "BELONGS_TO",
    "DEFINED_IN",
    "HAS_SLICE",
    "SLICE_ID",
    "SLICE_AGENT",
    "SLICE_STATUS",
    "SLICE_LANE",
    "SLICE_BRANCH",
    "SLICE_WORKTREE",
    "SLICE_SCOPE_PATH",
    "SLICE_REVIEWER",
    "SLICE_OBJECTIVE",
    "TICKET_STATUS",
    "TICKET_TITLE",
    "TICKET_PRIORITY",
    "TICKET_GOAL",
    "TICKET_BASE_BRANCH",
    "TICKET_UPDATED_AT",
    "TICKET_CREATED_AT",
    "TICKET_EVENT",
}

GROUNDING_KINDS = {
    "java_method",
    "java_method_decl",
    "java_repository_method_decl",
    "java_route_handler",
    "java_class",
    "sql_statement",
    "openapi_operation",
    "py_function",
    "py_class",
    "bash_function",
    "maven_dependency",
}

HIGH_SIGNAL_PREDICATES = {
    "ROUTE_TO_HANDLER",
    "HANDLES_ROUTE",
    "ROUTE_CALLS",
    "HANDLER_CALLS",
    "ROUTE_ACCEPTS_TYPE",
    "ROUTE_ACCEPTS_CONTAINS_TYPE",
    "ROUTE_RETURNS_TYPE",
    "ROUTE_RETURNS_CONTAINS_TYPE",
    "ROUTE_CONTRACT",
    "ROUTE_IDEMPOTENCY_KEY_TYPE",
    "CANONICAL_ROUTE",
    "ALTERNATE_ROUTE",
    "READS_TABLE",
    "WRITES_TABLE",
    "ACCEPTS_TYPE",
    "ACCEPTS_CONTAINS_TYPE",
    "RETURNS_TYPE",
    "RETURNS_CONTAINS_TYPE",
    "LOGIC_FINGERPRINT",
    "IDEMPOTENCY_KEY_TYPE",
    "IDEMPOTENCY_KEY_PARAM",
    "TABLE_DEFINED",
    "DEPENDS_ON",
    "EXTENDS",
    "IMPLEMENTS",
    "SECURED_BY",
    "LISTENS_EVENT",
    "EMITS_EVENT",
    "MODULE_DEPENDS_ON",
    "WORKFLOW_CHAIN",
    "DEFINED_IN",
    "HAS_SLICE",
    "SLICE_ID",
    "SLICE_AGENT",
    "SLICE_STATUS",
    "SLICE_LANE",
    "SLICE_BRANCH",
    "SLICE_WORKTREE",
    "SLICE_SCOPE_PATH",
    "SLICE_REVIEWER",
    "SLICE_OBJECTIVE",
    "TICKET_STATUS",
    "TICKET_TITLE",
    "TICKET_PRIORITY",
    "TICKET_GOAL",
    "TICKET_BASE_BRANCH",
    "TICKET_UPDATED_AT",
    "TICKET_CREATED_AT",
    "TICKET_EVENT",
}


def is_high_signal_predicate(predicate: str) -> bool:
    pred = str(predicate or "").upper()
    return pred in HIGH_SIGNAL_PREDICATES or pred.startswith("FINDING_")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Query hybrid RAG index and produce cite-or-refuse context packs",
    )
    parser.add_argument("--db-path", default=".tmp/rag/context_index.sqlite")
    parser.add_argument("--query", required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--candidate-limit", type=int, default=240)
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--include-docs", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print raw JSON output")
    parser.add_argument("--output", default="", help="Write context pack JSON to path")
    parser.add_argument("--min-confirm-score", type=float, default=0.45)
    parser.add_argument("--revision", default="", help="Requested git revision/commit for revision-locked retrieval.")
    parser.add_argument(
        "--allow-revision-mismatch",
        action="store_true",
        help="Allow retrieval even when index revision differs from requested revision.",
    )
    parser.add_argument("--max-per-file", type=int, default=2)
    parser.add_argument("--max-per-module", type=int, default=4)
    parser.add_argument("--min-module-diversity", type=int, default=2)
    parser.add_argument("--min-file-diversity", type=int, default=3)
    return parser.parse_args()


def sqlite_connect(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    return conn


def _meta_value(conn: sqlite3.Connection, key: str) -> str:
    row = conn.execute("SELECT value FROM repo_meta WHERE key = ?", (key,)).fetchone()
    return str(row["value"]) if row else ""


def _revision_matches(indexed_commit: str, requested_revision: str) -> bool:
    if not requested_revision:
        return True
    if not indexed_commit:
        return False
    left = indexed_commit.strip().lower()
    right = requested_revision.strip().lower()
    if not left or not right:
        return False
    return left == right or left.startswith(right) or right.startswith(left)


def build_fts_queries(tokens: Sequence[str], raw_query: str) -> Tuple[str, str]:
    cleaned = [token.replace('"', "") for token in tokens if token]
    if not cleaned:
        cleaned = [raw_query.replace('"', "").strip()]
    cleaned = [token for token in cleaned if token]
    if not cleaned:
        cleaned = ["erp"]

    weak_terms = {
        "code",
        "data",
        "service",
        "services",
        "test",
        "tests",
        "module",
        "modules",
        "api",
        "flow",
        "flows",
        "issue",
        "bug",
        "error",
    }
    prioritized = [token for token in cleaned if token not in weak_terms]
    if not prioritized:
        prioritized = cleaned

    strict_terms = prioritized[:3] if len(prioritized) >= 3 else prioritized[:2]
    if not strict_terms:
        strict_terms = [cleaned[0]]

    strict_query = " AND ".join(f'"{token}"' for token in strict_terms)
    relaxed_query = " OR ".join(f'"{token}"' for token in cleaned[:10])
    return strict_query, relaxed_query


def graph_seed_lookup(
    conn: sqlite3.Connection,
    tokens: Sequence[str],
    limit: int,
) -> Tuple[List[sqlite3.Row], Dict[str, float], Dict[str, float]]:
    if not tokens:
        return [], {}, {}

    facts: List[sqlite3.Row] = []

    for token in tokens[:12]:
        like = f"%{token}%"
        rows = conn.execute(
            """
            SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
            FROM facts
            WHERE lower(subject) LIKE ? OR lower(object) LIKE ? OR lower(predicate) LIKE ?
            LIMIT ?
            """,
            (like, like, like, max(20, limit // max(1, len(tokens)))),
        ).fetchall()
        facts.extend(rows)

    # Deduplicate rows while preserving order.
    seen = set()
    uniq_rows: List[sqlite3.Row] = []
    for row in facts:
        key = (
            row["subject"],
            row["predicate"],
            row["object"],
            row["file_path"],
            row["line_start"],
            row["line_end"],
        )
        if key in seen:
            continue
        seen.add(key)
        uniq_rows.append(row)
        if len(uniq_rows) >= limit:
            break

    file_weights: Dict[str, float] = defaultdict(float)
    symbol_weights: Dict[str, float] = defaultdict(float)
    for row in uniq_rows:
        confidence = float(row["confidence"] or 0.5)
        predicate = str(row["predicate"] or "")
        row_weight = 0.01 + 0.03 * confidence
        if is_high_signal_predicate(predicate):
            row_weight *= 1.8
        elif predicate == "CALLS" and confidence < 0.55:
            row_weight *= 0.35
        row_weight = max(0.002, min(0.09, row_weight))
        file_path = str(row["file_path"] or "")
        if file_path:
            file_weights[file_path] = min(0.20, file_weights[file_path] + row_weight)
        subject = str(row["subject"])
        obj = str(row["object"])
        symbol_weights[subject] = min(0.15, symbol_weights[subject] + row_weight)
        symbol_weights[obj] = min(0.15, symbol_weights[obj] + row_weight)

    return uniq_rows, dict(file_weights), dict(symbol_weights)


def lexical_search(
    conn: sqlite3.Connection,
    fts_query: str,
    limit: int,
    include_docs: bool,
    candidate_files: Optional[Sequence[str]] = None,
) -> List[sqlite3.Row]:
    sql = [
        "SELECT c.id, c.file_path, c.line_start, c.line_end, c.kind, c.symbol, c.module, c.language, c.content,",
        "bm25(chunks_fts, 1.0, 0.5, 0.2, 0.2, 0.1) AS bm25",
        "FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid",
        "WHERE chunks_fts MATCH ?",
    ]
    params: List[Any] = [fts_query]

    if not include_docs:
        sql.append("AND c.kind NOT LIKE 'doc_%'")

    if candidate_files:
        bounded = list(candidate_files)[:180]
        placeholders = ",".join("?" for _ in bounded)
        sql.append(f"AND c.file_path IN ({placeholders})")
        params.extend(bounded)

    sql.append("ORDER BY bm25 ASC LIMIT ?")
    params.append(limit)

    return conn.execute("\n".join(sql), params).fetchall()


def score_bm25(raw: Any) -> float:
    try:
        value = abs(float(raw))
    except (TypeError, ValueError):
        value = 999.0
    return 1.0 / (1.0 + value)


def kind_quality_boost(kind: str) -> float:
    if kind in GROUNDING_KINDS:
        return 0.24
    if kind.startswith("config_"):
        return -0.05
    if kind.startswith("doc_") or kind == "text_block":
        return -0.16
    return 0.03


def expand_neighbors(
    conn: sqlite3.Connection,
    seed_symbols: Sequence[str],
    depth: int,
    max_edges: int,
) -> Tuple[List[sqlite3.Row], Dict[str, int], Dict[str, int]]:
    edges: List[sqlite3.Row] = []
    symbol_hops: Dict[str, int] = {}
    file_hops: Dict[str, int] = {}

    queue: deque[Tuple[str, int]] = deque()
    seen_symbols: Set[str] = set()
    for symbol in seed_symbols:
        if symbol:
            queue.append((symbol, 0))
            seen_symbols.add(symbol)
            symbol_hops[symbol] = 0

    while queue and len(edges) < max_edges:
        symbol, hop = queue.popleft()
        if hop >= depth:
            continue

        rows = conn.execute(
            """
            SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
            FROM facts
            WHERE subject = ? OR object = ?
            LIMIT 200
            """,
            (symbol, symbol),
        ).fetchall()

        for row in rows:
            predicate = str(row["predicate"])
            if predicate not in TRAVERSABLE_PREDICATES:
                continue
            confidence = float(row["confidence"] or 0.0)
            if predicate == "CALLS" and confidence < 0.58:
                continue
            edges.append(row)
            file_path = str(row["file_path"] or "")
            if file_path:
                file_hops[file_path] = min(file_hops.get(file_path, hop + 1), hop + 1)

            for neighbor in (str(row["subject"]), str(row["object"])):
                if neighbor not in seen_symbols:
                    seen_symbols.add(neighbor)
                    symbol_hops[neighbor] = hop + 1
                    queue.append((neighbor, hop + 1))
            if len(edges) >= max_edges:
                break

    return edges, symbol_hops, file_hops


def enrich_candidates(
    conn: sqlite3.Connection,
    symbol_hops: Dict[str, int],
    file_hops: Dict[str, int],
    limit: int,
) -> List[sqlite3.Row]:
    rows: List[sqlite3.Row] = []

    symbols = [symbol for symbol in symbol_hops.keys() if symbol]
    if symbols:
        placeholders = ",".join("?" for _ in symbols[:120])
        rows.extend(
            conn.execute(
                f"""
                SELECT id, file_path, line_start, line_end, kind, symbol, module, language, content, NULL as bm25
                FROM chunks
                WHERE symbol IN ({placeholders})
                LIMIT ?
                """,
                [*symbols[:120], limit],
            ).fetchall()
        )

    files = [file_path for file_path in file_hops.keys() if file_path]
    if files:
        placeholders = ",".join("?" for _ in files[:120])
        rows.extend(
            conn.execute(
                f"""
                SELECT id, file_path, line_start, line_end, kind, symbol, module, language, content, NULL as bm25
                FROM chunks
                WHERE file_path IN ({placeholders})
                LIMIT ?
                """,
                [*files[:120], limit],
            ).fetchall()
        )

    # Deduplicate by id.
    deduped: List[sqlite3.Row] = []
    seen: Set[int] = set()
    for row in rows:
        row_id = int(row["id"])
        if row_id in seen:
            continue
        seen.add(row_id)
        deduped.append(row)
        if len(deduped) >= limit:
            break
    return deduped


def select_diverse_hits(
    reranked: Sequence[Dict[str, Any]],
    top_k: int,
    max_per_file: int,
    max_per_module: int,
    min_module_diversity: int,
    min_file_diversity: int,
) -> List[Dict[str, Any]]:
    if top_k <= 0:
        return []
    if not reranked:
        return []

    file_cap = max(1, max_per_file)
    module_cap = max(1, max_per_module)
    module_target = max(1, min(min_module_diversity, top_k))
    file_target = max(1, min(min_file_diversity, top_k))

    selected: List[Dict[str, Any]] = []
    selected_ids: Set[int] = set()
    file_counts: Dict[str, int] = defaultdict(int)
    module_counts: Dict[str, int] = defaultdict(int)

    def _pick(item: Dict[str, Any], enforce_module_cap: bool = True, enforce_file_cap: bool = True) -> bool:
        row_id = int(item["id"])
        if row_id in selected_ids:
            return False
        file_path = str(item.get("file_path") or "")
        module = str(item.get("module") or "root")
        if enforce_file_cap and file_counts[file_path] >= file_cap:
            return False
        if enforce_module_cap and module_counts[module] >= module_cap:
            return False
        selected.append(item)
        selected_ids.add(row_id)
        file_counts[file_path] += 1
        module_counts[module] += 1
        return True

    # Phase 1: prioritize distinct modules.
    for item in reranked:
        if len(selected) >= top_k:
            break
        module = str(item.get("module") or "root")
        if module_counts[module] > 0:
            continue
        if _pick(item):
            if len(module_counts) >= module_target:
                break

    # Phase 2: prioritize distinct files.
    for item in reranked:
        if len(selected) >= top_k:
            break
        file_path = str(item.get("file_path") or "")
        if file_counts[file_path] > 0:
            continue
        if _pick(item):
            if len(file_counts) >= file_target:
                break

    # Phase 3: fill with normal caps.
    for item in reranked:
        if len(selected) >= top_k:
            break
        _pick(item)

    # Phase 4: relax module cap if still short.
    if len(selected) < top_k:
        for item in reranked:
            if len(selected) >= top_k:
                break
            _pick(item, enforce_module_cap=False)

    # Phase 5: relax all caps to ensure enough evidence.
    if len(selected) < top_k:
        for item in reranked:
            if len(selected) >= top_k:
                break
            _pick(item, enforce_module_cap=False, enforce_file_cap=False)

    return selected[:top_k]


def build_context_pack(args: argparse.Namespace, conn: sqlite3.Connection) -> Dict[str, Any]:
    query = args.query.strip()
    indexed_commit = _meta_value(conn, "commit_sha")
    requested_revision = args.revision.strip()
    revision_match = _revision_matches(indexed_commit, requested_revision)
    revision_enforced = bool(requested_revision and not args.allow_revision_mismatch)

    tokens = word_tokens(query)
    strict_fts_query, relaxed_fts_query = build_fts_queries(tokens, query)
    token_set = set(tokens)
    precision_tokens = [token for token in tokens if len(token) >= 12 and "/" not in token and ":" not in token]

    security_focus = any(
        token in token_set for token in {"preauthorize", "protected", "authorize", "authorization", "rbac", "permission"}
    )
    endpoint_focus = any(token in token_set for token in {"endpoint", "endpoints", "route", "routes", "api"})
    chain_focus = any(token in token_set for token in {"chain", "pipeline", "trace", "flow", "workflow", "call", "calls", "called"})
    type_focus = any(token in token_set for token in {"dto", "schema", "payload", "request", "response", "contract", "type"}) or any(
        token.endswith(("dto", "request", "response", "schema", "payload")) for token in token_set
    )

    secured_symbols: Set[str] = set()
    route_handler_symbols: Set[str] = set()
    route_call_symbols: Set[str] = set()
    type_contract_symbols: Set[str] = set()
    if security_focus:
        rows = conn.execute(
            "SELECT DISTINCT subject FROM facts WHERE predicate = 'SECURED_BY' LIMIT 5000"
        ).fetchall()
        secured_symbols = {str(row["subject"]) for row in rows}
    if endpoint_focus:
        rows = conn.execute(
            "SELECT DISTINCT subject FROM facts WHERE predicate = 'HANDLES_ROUTE' LIMIT 5000"
        ).fetchall()
        route_handler_symbols = {str(row["subject"]) for row in rows}
        rows = conn.execute(
            "SELECT DISTINCT subject FROM facts WHERE predicate = 'ROUTE_CALLS' LIMIT 5000"
        ).fetchall()
        route_call_symbols = {str(row["subject"]) for row in rows}
    if type_focus:
        type_seed_tokens: List[str] = []
        for token in tokens[:12]:
            if token in precision_tokens:
                type_seed_tokens.append(token)
                continue
            if token in {"dto", "schema", "payload", "request", "response", "contract", "type"}:
                type_seed_tokens.append(token)
                continue
            if token.endswith(("dto", "request", "response", "schema", "payload")):
                type_seed_tokens.append(token)
        dedup_type_tokens = list(dict.fromkeys(type_seed_tokens))
        for token in dedup_type_tokens[:8]:
            if len(token) < 3:
                continue
            rows = conn.execute(
                """
                SELECT DISTINCT subject
                FROM facts
                WHERE predicate IN (
                    'ACCEPTS_TYPE',
                    'RETURNS_TYPE',
                    'ROUTE_ACCEPTS_TYPE',
                    'ROUTE_RETURNS_TYPE',
                    'ACCEPTS_CONTAINS_TYPE',
                    'RETURNS_CONTAINS_TYPE',
                    'ROUTE_ACCEPTS_CONTAINS_TYPE',
                    'ROUTE_RETURNS_CONTAINS_TYPE'
                )
                  AND (lower(object) LIKE lower(?) OR lower(object) LIKE lower(?))
                LIMIT 4000
                """,
                (f"%type:{token}%", f"%{token}%"),
            ).fetchall()
            type_contract_symbols.update(str(row["subject"]) for row in rows)

    graph_facts, graph_file_weights, graph_symbol_weights = graph_seed_lookup(
        conn,
        tokens,
        limit=max(args.candidate_limit, args.top_k * 12),
    )

    candidate_files = sorted(graph_file_weights.keys(), key=lambda p: graph_file_weights[p], reverse=True)

    narrow_rows = lexical_search(
        conn,
        strict_fts_query,
        limit=max(args.candidate_limit, args.top_k * 16),
        include_docs=args.include_docs,
        candidate_files=candidate_files,
    )
    global_rows = lexical_search(
        conn,
        strict_fts_query,
        limit=max(args.candidate_limit, args.top_k * 24),
        include_docs=args.include_docs,
        candidate_files=None,
    )

    if len(narrow_rows) + len(global_rows) < max(args.top_k * 2, 12):
        narrow_rows.extend(
            lexical_search(
                conn,
                relaxed_fts_query,
                limit=max(args.candidate_limit, args.top_k * 12),
                include_docs=args.include_docs,
                candidate_files=candidate_files,
            )
        )
        global_rows.extend(
            lexical_search(
                conn,
                relaxed_fts_query,
                limit=max(args.candidate_limit, args.top_k * 16),
                include_docs=args.include_docs,
                candidate_files=None,
            )
        )

    scored: Dict[int, Dict[str, Any]] = {}

    def ingest_row(row: sqlite3.Row, source: str) -> None:
        chunk_id = int(row["id"])
        lexical_score = score_bm25(row["bm25"])
        file_path = str(row["file_path"])
        symbol = str(row["symbol"] or "")
        kind = str(row["kind"])

        graph_boost = graph_file_weights.get(file_path, 0.0) + graph_symbol_weights.get(symbol, 0.0)
        code_boost = kind_quality_boost(kind)
        source_boost = 0.12 if source == "narrow" else 0.04
        topical_boost = 0.0
        lowered_path = file_path.lower()
        lowered_symbol = symbol.lower()
        lowered_content = str(row["content"]).lower()
        for token in tokens[:10]:
            if token in lowered_path or token in lowered_symbol:
                topical_boost += 0.03
        topical_boost = min(topical_boost, 0.18)

        precision_penalty = 0.0
        precision_match_boost = 0.0
        if precision_tokens:
            misses = 0
            hits = 0
            for token in precision_tokens[:3]:
                if token in lowered_symbol:
                    hits += 2
                    continue
                if token in lowered_path or token in lowered_content:
                    hits += 1
                    continue
                else:
                    misses += 1
            if hits > 0:
                precision_match_boost = min(0.40, 0.16 * hits)
            if misses > 0:
                precision_penalty = -min(0.30, 0.12 * misses)

        module_token_boost = 0.0
        module_tokens = {
            "auth",
            "accounting",
            "inventory",
            "sales",
            "hr",
            "company",
            "rbac",
            "invoice",
            "purchasing",
            "admin",
            "portal",
            "reports",
            "factory",
            "production",
            "orchestrator",
        }
        has_module_path = "/modules/" in lowered_path
        for token in tokens[:10]:
            if token == "auth":
                if (
                    "/modules/auth/" in lowered_path
                    or "/modules/rbac/" in lowered_path
                    or "/modules/company/" in lowered_path
                    or "/core/security/" in lowered_path
                ):
                    module_token_boost += 0.16
                elif has_module_path:
                    module_token_boost -= 0.12
                continue

            if token in module_tokens and f"/modules/{token}/" in lowered_path:
                module_token_boost += 0.16
            elif token in module_tokens and has_module_path:
                module_token_boost -= 0.03

            if token == "orchestrator" and "/orchestrator/" in lowered_path:
                module_token_boost += 0.16
        module_token_boost = max(-0.20, min(module_token_boost, 0.48))

        source_path_boost = 0.0
        if "/src/main/" in lowered_path:
            source_path_boost += 0.08
        elif "/src/test/" in lowered_path:
            source_path_boost -= 0.04

        intent_boost = 0.0
        if security_focus:
            if symbol in secured_symbols:
                intent_boost += 0.22
            if "/controller/" in lowered_path:
                intent_boost += 0.08
            elif "/src/main/" in lowered_path:
                intent_boost -= 0.04
        if endpoint_focus:
            if kind == "java_route_handler":
                intent_boost += 0.18
            if symbol in route_handler_symbols:
                intent_boost += 0.12
            if symbol in route_call_symbols:
                intent_boost += 0.10
            if "controller" in lowered_symbol:
                intent_boost += 0.04
        if chain_focus:
            if kind in {"java_route_handler", "workflow_chain", "workflow_registry_row"}:
                intent_boost += 0.14
            if symbol.startswith("route:"):
                intent_boost += 0.12
            if "/controller/" in lowered_path or "/service/" in lowered_path:
                intent_boost += 0.06
        if type_focus:
            if kind in {
                "openapi_operation",
                "java_route_handler",
                "java_method",
                "java_method_decl",
                "java_repository_method_decl",
            }:
                intent_boost += 0.14
            if symbol.startswith("openapi:"):
                intent_boost += 0.12
            if symbol in type_contract_symbols:
                intent_boost += 0.20
            if "/dto/" in lowered_path or "/model/" in lowered_path:
                intent_boost += 0.06
        if endpoint_focus and type_focus:
            if kind in {"java_route_handler", "endpoint_inventory_row"}:
                if symbol in type_contract_symbols or precision_match_boost > 0:
                    intent_boost += 0.18
                else:
                    intent_boost -= 0.10
            elif kind == "java_method":
                intent_boost -= 0.06

        graph_relevance = 1.0 if (topical_boost > 0 or module_token_boost > 0) else 0.2
        effective_graph_boost = graph_boost * graph_relevance

        total_score = (
            lexical_score
            + effective_graph_boost
            + code_boost
            + source_boost
            + topical_boost
            + module_token_boost
            + source_path_boost
            + intent_boost
            + precision_match_boost
            + precision_penalty
        )

        existing = scored.get(chunk_id)
        if existing and existing["score"] >= total_score:
            return
        scored[chunk_id] = {
            "id": chunk_id,
            "score": total_score,
            "lexical_score": lexical_score,
            "file_path": file_path,
            "line_start": int(row["line_start"]),
            "line_end": int(row["line_end"]),
            "kind": kind,
            "symbol": symbol,
            "module": str(row["module"] or "root"),
            "language": str(row["language"] or "text"),
            "content": str(row["content"]),
            "reasons": [
                f"lexical_match={lexical_score:.3f}",
                f"graph_boost={effective_graph_boost:.3f}",
                f"topical_boost={topical_boost:.3f}",
                f"module_token_boost={module_token_boost:.3f}",
                f"intent_boost={intent_boost:.3f}",
                f"precision_match_boost={precision_match_boost:.3f}",
                f"precision_penalty={precision_penalty:.3f}",
                f"source={source}",
            ],
        }

    for row in narrow_rows:
        ingest_row(row, "narrow")
    for row in global_rows:
        ingest_row(row, "global")

    top_initial = sorted(scored.values(), key=lambda item: item["score"], reverse=True)[: max(args.top_k * 2, 14)]

    seed_symbols = [item["symbol"] for item in top_initial if item["symbol"]]
    expanded_edges, symbol_hops, file_hops = expand_neighbors(
        conn,
        seed_symbols,
        depth=max(0, args.depth),
        max_edges=max(200, args.candidate_limit),
    )

    expansion_rows = enrich_candidates(
        conn,
        symbol_hops,
        file_hops,
        limit=max(args.candidate_limit, args.top_k * 12),
    )

    for row in expansion_rows:
        chunk_id = int(row["id"])
        existing = scored.get(chunk_id)
        base_score = 0.05
        symbol = str(row["symbol"] or "")
        file_path = str(row["file_path"])
        if symbol in symbol_hops:
            base_score += max(0.02, 0.22 - 0.06 * symbol_hops[symbol])
        if file_path in file_hops:
            base_score += max(0.02, 0.16 - 0.05 * file_hops[file_path])
        kind = str(row["kind"])
        if kind in GROUNDING_KINDS:
            base_score += 0.06

        if not existing:
            scored[chunk_id] = {
                "id": chunk_id,
                "score": base_score,
                "lexical_score": 0.0,
                "file_path": file_path,
                "line_start": int(row["line_start"]),
                "line_end": int(row["line_end"]),
                "kind": kind,
                "symbol": symbol,
                "module": str(row["module"] or "root"),
                "language": str(row["language"] or "text"),
                "content": str(row["content"]),
                "reasons": ["graph_expansion"],
            }
        else:
            existing["score"] += base_score
            existing["reasons"].append("graph_expansion")

    reranked = sorted(scored.values(), key=lambda item: item["score"], reverse=True)
    top_hits = select_diverse_hits(
        reranked,
        top_k=args.top_k,
        max_per_file=max(1, int(args.max_per_file)),
        max_per_module=max(1, int(args.max_per_module)),
        min_module_diversity=max(1, int(args.min_module_diversity)),
        min_file_diversity=max(1, int(args.min_file_diversity)),
    )

    code_hits = [hit for hit in top_hits if hit["kind"] in GROUNDING_KINDS]
    top_score = top_hits[0]["score"] if top_hits else 0.0
    can_confirm = bool(top_hits) and bool(code_hits) and top_score >= args.min_confirm_score
    if revision_enforced and not revision_match:
        can_confirm = False

    evidence = []
    for rank, hit in enumerate(top_hits, start=1):
        citation = f"{hit['file_path']}:{hit['line_start']}-{hit['line_end']}"
        evidence.append(
            {
                "rank": rank,
                "score": round(float(hit["score"]), 4),
                "file_path": hit["file_path"],
                "line_start": hit["line_start"],
                "line_end": hit["line_end"],
                "kind": hit["kind"],
                "symbol": hit["symbol"],
                "module": hit["module"],
                "language": hit["language"],
                "citation": citation,
                "why": hit["reasons"],
                "excerpt": compact_excerpt(hit["content"], max_lines=16, max_chars=1600),
            }
        )

    facts = []
    for row in graph_facts[: min(len(graph_facts), args.top_k * 6)]:
        facts.append(
            {
                "subject": str(row["subject"]),
                "predicate": str(row["predicate"]),
                "object": str(row["object"]),
                "file_path": str(row["file_path"] or ""),
                "line_start": int(row["line_start"] or 0),
                "line_end": int(row["line_end"] or 0),
                "confidence": round(float(row["confidence"] or 0.0), 3),
                "evidence": str(row["evidence"] or ""),
            }
        )

    unresolved: List[str] = []
    if not top_hits:
        unresolved.append("No matching indexed context found.")
    elif not code_hits:
        unresolved.append("Only documentation hits were retrieved; no direct code excerpt in top results.")
    if top_score < args.min_confirm_score:
        unresolved.append(
            f"Top evidence score {top_score:.3f} is below confirmation threshold {args.min_confirm_score:.3f}."
        )
    if requested_revision and not revision_match:
        unresolved.append(
            f"Index revision mismatch: indexed={indexed_commit or 'unknown'} requested={requested_revision}."
        )

    return {
        "query": query,
        "generated_at": utc_now_iso(),
        "policy": {
            "truth_hierarchy": TRUTH_HIERARCHY,
            "cite_or_refuse": True,
            "unknown_allowed": True,
        },
        "revision": {
            "requested": requested_revision or None,
            "indexed": indexed_commit or None,
            "matches_requested": revision_match if requested_revision else None,
            "enforced": revision_enforced,
        },
        "can_confirm": can_confirm,
        "confidence": round(top_score, 4),
        "message": (
            "Grounded context retrieved with citations."
            if can_confirm
            else "I can't confirm from indexed repo evidence."
        ),
        "retrieval": {
            "tokens": tokens,
            "fts_query": strict_fts_query,
            "fts_query_relaxed": relaxed_fts_query,
            "candidate_files": candidate_files[:40],
            "graph_fact_count": len(graph_facts),
            "expanded_edge_count": len(expanded_edges),
            "scored_chunk_count": len(reranked),
            "selected_evidence_count": len(top_hits),
            "module_diversity": len({str(hit.get("module") or "root") for hit in top_hits}),
            "file_diversity": len({str(hit.get("file_path") or "") for hit in top_hits}),
            "diversity_policy": {
                "max_per_file": max(1, int(args.max_per_file)),
                "max_per_module": max(1, int(args.max_per_module)),
                "min_module_diversity": max(1, int(args.min_module_diversity)),
                "min_file_diversity": max(1, int(args.min_file_diversity)),
            },
            "top_k": args.top_k,
        },
        "evidence": evidence,
        "facts": facts,
        "unresolved": unresolved,
    }


def print_human(context_pack: Dict[str, Any]) -> None:
    print("[rag-query] context-pack:")
    print(
        f"- can_confirm: {context_pack['can_confirm']}"
        f" | confidence: {context_pack['confidence']:.3f}"
        f" | message: {context_pack['message']}"
    )

    if context_pack["evidence"]:
        print("- evidence:")
    for item in context_pack["evidence"]:
        print(
            f"  {item['rank']}. {item['citation']} [{item['kind']}] score={item['score']:.3f}"
        )
        if item["symbol"]:
            print(f"     symbol: {item['symbol']}")
        print(f"     why: {', '.join(item['why'])}")

    if context_pack["unresolved"]:
        print("- unresolved:")
    for unresolved in context_pack["unresolved"]:
        print(f"  - {unresolved}")


def main() -> int:
    args = parse_args()
    db_path = Path(args.db_path).resolve()
    if not db_path.exists():
        print(f"[rag-query] index not found: {db_path}", file=sys.stderr)
        return 2

    conn = sqlite_connect(db_path)
    try:
        context_pack = build_context_pack(args, conn)
    finally:
        conn.close()

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(context_pack, indent=2), encoding="utf-8")

    if args.json:
        print(json.dumps(context_pack, indent=2))
    else:
        print_human(context_pack)

    return 0


if __name__ == "__main__":
    sys.exit(main())
