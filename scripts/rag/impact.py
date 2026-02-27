#!/usr/bin/env python3
"""Cross-module impact traversal using indexed graph facts."""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from collections import deque
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

from core import utc_now_iso


TRAVERSABLE = {
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
    "CANONICAL_ROUTE",
    "ALTERNATE_ROUTE",
    "MODULE_DEPENDS_ON",
    "WORKFLOW_CHAIN",
    "WORKFLOW_OWNER",
    "WORKFLOW_DECISION",
    "BUSINESS_EVENT",
    "BELONGS_TO",
    "DEFINED_IN",
    "USES_CONFIG",
    "USES_ENV",
    "ACCEPTS_TYPE",
    "ACCEPTS_CONTAINS_TYPE",
    "RETURNS_TYPE",
    "RETURNS_CONTAINS_TYPE",
    "READS_TABLE",
    "WRITES_TABLE",
    "RETRY_POLICY",
    "TRANSACTIONAL",
    "SCHEDULE_TRIGGER",
    "SECURED_BY",
    "LISTENS_EVENT",
    "EMITS_EVENT",
    "TEST_COVERS",
}

STRUCTURED_SYMBOL_PREFIXES = (
    "route:",
    "file:",
    "module:",
    "table:",
    "entity:",
    "event:",
    "config:",
    "workflow:",
    "test:",
    "gate:",
    "finding:",
    "role:",
    "package:",
    "type:",
)


def keep_symbol(symbol: str) -> bool:
    if not symbol:
        return False
    if symbol.startswith(STRUCTURED_SYMBOL_PREFIXES):
        return True
    if "#" in symbol or "." in symbol:
        return True
    if symbol.isupper() and len(symbol) <= 8:
        return False
    if len(symbol) <= 3:
        return False
    return False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze cross-module impact for symbol or file")
    parser.add_argument("--db-path", default=".tmp/rag/context_index.sqlite")
    parser.add_argument("--symbol", default="")
    parser.add_argument("--file-path", default="")
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--top-k", type=int, default=25)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--output", default="")
    return parser.parse_args()


def connect(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    return conn


def seed_symbols(conn: sqlite3.Connection, symbol: str, file_path: str) -> List[str]:
    seeds: List[str] = []
    if symbol:
        seeds.append(symbol)

    if file_path:
        rows = conn.execute(
            "SELECT DISTINCT symbol FROM chunks WHERE file_path = ? AND symbol IS NOT NULL AND symbol != ''",
            (file_path,),
        ).fetchall()
        seeds.extend(str(row["symbol"]) for row in rows)

    deduped = []
    seen = set()
    for seed in seeds:
        if seed in seen:
            continue
        seen.add(seed)
        deduped.append(seed)
    return deduped


def traverse_graph(
    conn: sqlite3.Connection,
    seeds: List[str],
    depth: int,
    top_k: int,
) -> Tuple[List[Dict[str, Any]], Dict[str, int], Dict[str, int]]:
    queue: deque[Tuple[str, int]] = deque((seed, 0) for seed in seeds)
    seen_symbols: Set[str] = set(seeds)
    symbol_hops: Dict[str, int] = {seed: 0 for seed in seeds}
    file_hops: Dict[str, int] = {}
    edges: List[Dict[str, Any]] = []

    while queue and len(edges) < max(300, top_k * 20):
        current, hop = queue.popleft()
        if hop >= depth:
            continue

        rows = conn.execute(
            """
            SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
            FROM facts
            WHERE subject = ? OR object = ?
            LIMIT 300
            """,
            (current, current),
        ).fetchall()

        for row in rows:
            predicate = str(row["predicate"])
            if predicate not in TRAVERSABLE:
                continue
            confidence = float(row["confidence"] or 0.0)
            if predicate == "CALLS" and confidence < 0.58:
                continue

            edge = {
                "subject": str(row["subject"]),
                "predicate": predicate,
                "object": str(row["object"]),
                "file_path": str(row["file_path"] or ""),
                "line_start": int(row["line_start"] or 0),
                "line_end": int(row["line_end"] or 0),
                "confidence": round(confidence, 3),
                "evidence": str(row["evidence"] or ""),
                "hop": hop + 1,
            }
            edges.append(edge)

            if edge["file_path"]:
                file_hops[edge["file_path"]] = min(file_hops.get(edge["file_path"], hop + 1), hop + 1)

            for neighbor in (edge["subject"], edge["object"]):
                if neighbor not in seen_symbols:
                    seen_symbols.add(neighbor)
                    symbol_hops[neighbor] = hop + 1
                    queue.append((neighbor, hop + 1))

            if len(edges) >= max(300, top_k * 20):
                break

    return edges, symbol_hops, file_hops


def impacted_modules(conn: sqlite3.Connection, symbols: List[str]) -> List[str]:
    if not symbols:
        return []
    placeholders = ",".join("?" for _ in symbols[:300])
    rows = conn.execute(
        f"SELECT DISTINCT module FROM chunks WHERE symbol IN ({placeholders}) AND module IS NOT NULL",
        symbols[:300],
    ).fetchall()
    modules = sorted({str(row["module"]) for row in rows if row["module"]})
    return modules


def recommended_tests(conn: sqlite3.Connection, impacted_symbols: List[str], top_k: int) -> List[str]:
    if not impacted_symbols:
        return []
    placeholders = ",".join("?" for _ in impacted_symbols[:300])
    rows = conn.execute(
        f"""
        SELECT subject, object, confidence
        FROM facts
        WHERE predicate = 'TEST_COVERS' AND object IN ({placeholders})
        ORDER BY confidence DESC
        LIMIT ?
        """,
        [*impacted_symbols[:300], top_k],
    ).fetchall()
    return [str(row["subject"]) for row in rows]


def guard_recommendations(modules: List[str]) -> List[str]:
    checks = ["bash ci/check-architecture.sh", "bash ci/check-enterprise-policy.sh"]
    if any(module in {"auth", "rbac", "company"} for module in modules):
        checks.append("bash ci/check-orchestrator-layer.sh")
    if any(module in {"accounting", "inventory", "invoice", "purchasing"} for module in modules):
        checks.append("bash scripts/gate_reconciliation.sh")
    if any(module in {"orchestrator", "production", "factory"} for module in modules):
        checks.append("bash scripts/gate_core.sh")
    checks.append("bash scripts/gate_fast.sh")

    deduped = []
    seen = set()
    for check in checks:
        if check in seen:
            continue
        seen.add(check)
        deduped.append(check)
    return deduped


def render_human(report: Dict[str, Any]) -> None:
    print("[rag-impact] analysis:")
    print(f"- seeds: {', '.join(report['seeds']) if report['seeds'] else '(none)'}")
    print(f"- impacted modules: {', '.join(report['impacted_modules']) if report['impacted_modules'] else '(none)'}")
    print(f"- impacted files: {len(report['impacted_files'])}")
    print(f"- traversed edges: {report['edge_count']}")
    if report["top_edges"]:
        print("- top edges:")
        for edge in report["top_edges"]:
            citation = ""
            if edge["file_path"]:
                citation = f" ({edge['file_path']}:{edge['line_start']}-{edge['line_end']})"
            print(f"  - [{edge['hop']}] {edge['subject']} --{edge['predicate']}--> {edge['object']}{citation}")
    if report["recommended_tests"]:
        print("- recommended tests:")
        for test in report["recommended_tests"]:
            print(f"  - {test}")
    if report["recommended_checks"]:
        print("- recommended checks:")
        for check in report["recommended_checks"]:
            print(f"  - {check}")


def main() -> int:
    args = parse_args()
    if not args.symbol and not args.file_path:
        print("[rag-impact] provide --symbol or --file-path", file=sys.stderr)
        return 2

    db_path = Path(args.db_path).resolve()
    if not db_path.exists():
        print(f"[rag-impact] index not found: {db_path}", file=sys.stderr)
        return 2

    conn = connect(db_path)
    try:
        seeds = seed_symbols(conn, args.symbol.strip(), args.file_path.strip())
        if not seeds:
            print("[rag-impact] no seed symbols found from input", file=sys.stderr)
            return 3

        edges, symbol_hops, file_hops = traverse_graph(conn, seeds, max(0, args.depth), args.top_k)
        impacted_symbols = sorted(
            [symbol for symbol in symbol_hops.keys() if keep_symbol(symbol)],
            key=lambda sym: (symbol_hops[sym], sym),
        )
        impacted_files = sorted(file_hops.keys(), key=lambda fp: (file_hops[fp], fp))
        modules = impacted_modules(conn, impacted_symbols)
        tests = recommended_tests(conn, impacted_symbols, args.top_k)

        top_edges = sorted(edges, key=lambda item: (item["hop"], -item["confidence"]))[: args.top_k]

        report = {
            "generated_at": utc_now_iso(),
            "seeds": seeds,
            "depth": args.depth,
            "edge_count": len(edges),
            "impacted_symbol_count": len(impacted_symbols),
            "impacted_file_count": len(impacted_files),
            "impacted_modules": modules,
            "impacted_symbols": impacted_symbols[: max(args.top_k * 3, 40)],
            "impacted_files": [
                {"file_path": path, "hop": file_hops[path]} for path in impacted_files[: max(args.top_k * 2, 30)]
            ],
            "top_edges": top_edges,
            "recommended_tests": tests,
            "recommended_checks": guard_recommendations(modules),
        }
    finally:
        conn.close()

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        render_human(report)

    return 0


if __name__ == "__main__":
    sys.exit(main())
