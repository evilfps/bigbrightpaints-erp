#!/usr/bin/env python3
"""MCP server for embedded ERP RAG tooling.

Exposes hybrid retrieval and graph introspection as MCP tools over stdio.
"""

from __future__ import annotations

import argparse
from collections import deque
import json
import re
import sqlite3
import subprocess
import sys
from pathlib import Path
from typing import Any, Deque, Dict, List, Optional, Sequence, Set, Tuple

from core import should_index_path

HIGH_SIGNAL_FACT_PREDICATES: Set[str] = {
    "ROUTE_TO_HANDLER",
    "HANDLES_ROUTE",
    "ROUTE_CALLS",
    "HANDLER_CALLS",
    "ROUTE_CONTRACT",
    "ROUTE_ACCEPTS_TYPE",
    "ROUTE_ACCEPTS_CONTAINS_TYPE",
    "ROUTE_RETURNS_TYPE",
    "ROUTE_RETURNS_CONTAINS_TYPE",
    "ROUTE_IDEMPOTENCY_KEY_TYPE",
    "ACCEPTS_TYPE",
    "ACCEPTS_CONTAINS_TYPE",
    "RETURNS_TYPE",
    "RETURNS_CONTAINS_TYPE",
    "IDEMPOTENCY_KEY_TYPE",
    "IDEMPOTENCY_KEY_PARAM",
    "LOGIC_FINGERPRINT",
    "READS_TABLE",
    "WRITES_TABLE",
    "SECURED_BY",
    "LISTENS_EVENT",
    "EMITS_EVENT",
    "TEST_COVERS",
    "EXTENDS",
    "IMPLEMENTS",
    "DEPENDS_ON",
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

FINDING_SEVERITY_ORDER: Dict[str, int] = {
    "critical": 0,
    "high": 1,
    "medium": 2,
    "low": 3,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run ERP RAG MCP server over stdio")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--db-path", default=".tmp/rag/context_index.sqlite")
    parser.add_argument("--python", default=sys.executable)
    return parser.parse_args()


def _json_error(code: int, message: str, req_id: Any = None) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}


def _json_result(result: Any, req_id: Any) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def read_message() -> Optional[Dict[str, Any]]:
    headers: Dict[str, str] = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        if line in (b"\r\n", b"\n"):
            break
        decoded = line.decode("utf-8", errors="replace").strip()
        if ":" in decoded:
            key, value = decoded.split(":", 1)
            headers[key.strip().lower()] = value.strip()

    content_length = headers.get("content-length")
    if not content_length:
        return None
    length = int(content_length)
    payload = sys.stdin.buffer.read(length)
    if not payload:
        return None

    try:
        return json.loads(payload.decode("utf-8"))
    except json.JSONDecodeError:
        return None


def write_message(message: Dict[str, Any]) -> None:
    encoded = json.dumps(message, ensure_ascii=True).encode("utf-8")
    sys.stdout.buffer.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("utf-8"))
    sys.stdout.buffer.write(encoded)
    sys.stdout.buffer.flush()


def parse_json_from_output(raw: str) -> Dict[str, Any]:
    start = raw.find("{")
    end = raw.rfind("}")
    if start == -1 or end == -1 or end < start:
        return {"raw": raw.strip()}
    candidate = raw[start : end + 1]
    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        return {"raw": raw.strip()}


class RagMcpServer:
    def __init__(self, repo_root: Path, db_path: Path, python_bin: str) -> None:
        self.repo_root = repo_root
        self.db_path = db_path
        self.python_bin = python_bin
        self._shutdown_requested = False

    def _tool_result(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        text = json.dumps(payload, indent=2)
        return {"content": [{"type": "text", "text": text}], "structuredContent": payload, "isError": False}

    def _tool_error(self, message: str) -> Dict[str, Any]:
        payload = {"error": message}
        return {
            "content": [{"type": "text", "text": json.dumps(payload, indent=2)}],
            "structuredContent": payload,
            "isError": True,
        }

    def _run_script(self, script_name: str, args: List[str]) -> Dict[str, Any]:
        script_path = self.repo_root / "scripts" / "rag" / script_name
        cmd = [self.python_bin, str(script_path), "--db-path", str(self.db_path), *args]
        proc = subprocess.run(cmd, cwd=str(self.repo_root), capture_output=True, text=True)
        output = (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")
        payload = parse_json_from_output(output)
        payload["exit_code"] = proc.returncode
        payload["command"] = " ".join(cmd)
        return payload

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path))
        conn.row_factory = sqlite3.Row
        return conn

    def _query_sql(self, sql: str, params: Tuple[Any, ...] = ()) -> List[Dict[str, Any]]:
        conn = self._connect()
        try:
            rows = conn.execute(sql, params).fetchall()
            return [dict(row) for row in rows]
        finally:
            conn.close()

    def _head_commit(self) -> str:
        proc = subprocess.run(
            ["git", "-C", str(self.repo_root), "rev-parse", "HEAD"],
            check=False,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            return ""
        return (proc.stdout or "").strip()

    def _indexed_commit(self) -> str:
        conn = self._connect()
        try:
            row = conn.execute("SELECT value FROM repo_meta WHERE key = 'commit_sha'").fetchone()
            return str(row["value"]) if row else ""
        finally:
            conn.close()

    def _changed_files_since(self, diff_base: str) -> Tuple[List[str], List[str]]:
        if not diff_base:
            return [], []
        proc = subprocess.run(
            ["git", "-C", str(self.repo_root), "diff", "--name-only", f"{diff_base}...HEAD"],
            check=False,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            return [], []
        changed = [line.strip() for line in (proc.stdout or "").splitlines() if line.strip()]
        indexable = [path for path in changed if should_index_path(path)]
        return changed, indexable

    def _git_lines(self, *args: str) -> List[str]:
        proc = subprocess.run(
            ["git", "-C", str(self.repo_root), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            return []
        return [line.strip() for line in (proc.stdout or "").splitlines() if line.strip()]

    def _working_tree_changed_files(self, max_files: int = 200) -> List[str]:
        # Includes staged/unstaged tracked changes plus untracked files.
        tracked = self._git_lines("diff", "--name-only", "--relative", "HEAD")
        untracked = self._git_lines("ls-files", "--others", "--exclude-standard")
        combined = [*tracked, *untracked]
        selected: List[str] = []
        seen: Set[str] = set()
        for path in combined:
            normalized = path.strip()
            if not normalized or normalized in seen:
                continue
            if not should_index_path(normalized):
                continue
            seen.add(normalized)
            selected.append(normalized)
            if len(selected) >= max(1, max_files):
                break
        return selected

    def _git_file_last_modified_epoch(self, file_path: str) -> int:
        if not file_path:
            return 0
        proc = subprocess.run(
            ["git", "-C", str(self.repo_root), "log", "-1", "--format=%ct", "--", file_path],
            check=False,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            return 0
        raw = (proc.stdout or "").strip()
        try:
            return int(raw)
        except ValueError:
            return 0

    @staticmethod
    def _modules_from_paths(paths: Sequence[str]) -> List[str]:
        modules: List[str] = []
        seen: Set[str] = set()
        for raw in paths:
            path = str(raw or "")
            if not path:
                continue
            match = re.search(r"/modules/([^/]+)/", f"/{path}")
            if match:
                module = match.group(1).strip().lower()
            elif "/orchestrator/" in f"/{path}":
                module = "orchestrator"
            else:
                continue
            if not module or module in seen:
                continue
            seen.add(module)
            modules.append(module)
        return modules

    def _commit_exists(self, commit_ref: str) -> bool:
        if not commit_ref:
            return False
        proc = subprocess.run(
            ["git", "-C", str(self.repo_root), "rev-parse", "--verify", commit_ref],
            check=False,
            capture_output=True,
            text=True,
        )
        return proc.returncode == 0 and bool((proc.stdout or "").strip())

    @staticmethod
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

    @staticmethod
    def _normalize_revision(value: Any) -> str:
        if value is None:
            return ""
        text = str(value).strip()
        if text.lower() in {"none", "null"}:
            return ""
        return text

    @staticmethod
    def _normalize_route_input(route: str, method: str) -> str:
        clean = route.strip()
        inferred_method = ""
        inline = re.match(r"^\s*(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD|ANY)\s+(.+)$", clean, flags=re.IGNORECASE)
        if inline:
            inferred_method = inline.group(1).upper()
            clean = inline.group(2).strip()
        if not clean.startswith("/"):
            clean = "/" + clean
        clean = re.sub(r"//+", "/", clean)
        m = method.strip().upper() if method else inferred_method
        return f"{m} {clean}".strip()

    @classmethod
    def _route_search_patterns(cls, route: str, method: str = "") -> Tuple[str, str, str]:
        token = cls._normalize_route_input(route, method)
        path = token.split(" ", 1)[1].strip() if " " in token else token
        route_pattern = f"%route:{token}%" if " " in token else f"%route:% {path}%"
        path_pattern = f"%{path}%"
        return token, route_pattern, path_pattern

    @staticmethod
    def _default_graph_predicates() -> Set[str]:
        return {
            "CALLS",
            "HANDLER_CALLS",
            "ROUTE_CALLS",
            "ROUTE_TO_HANDLER",
            "HANDLES_ROUTE",
            "ROUTE_CONTRACT",
            "ROUTE_ACCEPTS_TYPE",
            "ROUTE_ACCEPTS_CONTAINS_TYPE",
            "ROUTE_RETURNS_TYPE",
            "ROUTE_RETURNS_CONTAINS_TYPE",
            "ROUTE_IDEMPOTENCY_KEY_TYPE",
            "DEPENDS_ON",
            "IMPORTS",
            "EXTENDS",
            "IMPLEMENTS",
            "USES_CONFIG",
            "USES_ENV",
            "READS_TABLE",
            "WRITES_TABLE",
            "TABLE_DEFINED",
            "EMITS_EVENT",
            "LISTENS_EVENT",
            "SECURED_BY",
            "TRANSACTIONAL",
            "RETRY_POLICY",
            "SCHEDULE_TRIGGER",
            "ACCEPTS_TYPE",
            "ACCEPTS_CONTAINS_TYPE",
            "RETURNS_TYPE",
            "RETURNS_CONTAINS_TYPE",
            "IDEMPOTENCY_KEY_TYPE",
            "IDEMPOTENCY_KEY_PARAM",
            "LOGIC_FINGERPRINT",
            "MODULE_DEPENDS_ON",
            "WORKFLOW_CHAIN",
            "WORKFLOW_OWNER",
            "DEFINED_IN",
            "BELONGS_TO",
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

    def _normalize_predicate_filter(self, raw_predicates: Any) -> Set[str]:
        allowed = self._default_graph_predicates()
        if not isinstance(raw_predicates, list):
            return allowed
        picked = {str(item).strip().upper() for item in raw_predicates if str(item).strip()}
        if not picked:
            return allowed
        constrained = picked.intersection(allowed)
        return constrained or allowed

    @staticmethod
    def _predicate_priority(predicate: str) -> int:
        priorities = {
            "ROUTE_TO_HANDLER": 120,
            "HANDLES_ROUTE": 120,
            "ROUTE_CALLS": 115,
            "HANDLER_CALLS": 112,
            "CALLS": 108,
            "READS_TABLE": 104,
            "WRITES_TABLE": 104,
            "TABLE_DEFINED": 102,
            "EMITS_EVENT": 102,
            "LISTENS_EVENT": 102,
            "ROUTE_CONTRACT": 100,
            "ROUTE_ACCEPTS_TYPE": 100,
            "ROUTE_ACCEPTS_CONTAINS_TYPE": 99,
            "ROUTE_RETURNS_TYPE": 100,
            "ROUTE_RETURNS_CONTAINS_TYPE": 99,
            "ROUTE_IDEMPOTENCY_KEY_TYPE": 101,
            "ACCEPTS_TYPE": 98,
            "ACCEPTS_CONTAINS_TYPE": 97,
            "RETURNS_TYPE": 98,
            "RETURNS_CONTAINS_TYPE": 97,
            "IDEMPOTENCY_KEY_TYPE": 99,
            "IDEMPOTENCY_KEY_PARAM": 93,
            "LOGIC_FINGERPRINT": 91,
            "EXTENDS": 96,
            "IMPLEMENTS": 96,
            "DEPENDS_ON": 92,
            "MODULE_DEPENDS_ON": 90,
            "WORKFLOW_CHAIN": 88,
            "WORKFLOW_OWNER": 86,
            "HAS_SLICE": 92,
            "SLICE_ID": 86,
            "SLICE_AGENT": 90,
            "SLICE_STATUS": 90,
            "SLICE_LANE": 80,
            "SLICE_BRANCH": 88,
            "SLICE_WORKTREE": 84,
            "SLICE_SCOPE_PATH": 86,
            "SLICE_REVIEWER": 80,
            "SLICE_OBJECTIVE": 78,
            "TICKET_STATUS": 84,
            "TICKET_TITLE": 84,
            "TICKET_PRIORITY": 82,
            "TICKET_GOAL": 82,
            "TICKET_BASE_BRANCH": 82,
            "TICKET_UPDATED_AT": 70,
            "TICKET_CREATED_AT": 70,
            "TICKET_EVENT": 84,
            "SECURED_BY": 84,
            "TRANSACTIONAL": 84,
            "RETRY_POLICY": 84,
            "SCHEDULE_TRIGGER": 84,
            "DEFINED_IN": 70,
            "BELONGS_TO": 70,
            "USES_CONFIG": 68,
            "USES_ENV": 68,
            "IMPORTS": 20,
        }
        return priorities.get(predicate.upper(), 50)

    @staticmethod
    def _is_high_signal_predicate(predicate: str) -> bool:
        pred = str(predicate or "").upper()
        return pred in HIGH_SIGNAL_FACT_PREDICATES or pred.startswith("FINDING_")

    @staticmethod
    def _parse_metadata_json(raw: Any) -> Dict[str, Any]:
        if isinstance(raw, dict):
            return raw
        text = str(raw or "").strip()
        if not text:
            return {}
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    @classmethod
    def _normalize_finding_row(cls, row: Dict[str, Any]) -> Dict[str, Any]:
        normalized = dict(row)
        metadata = cls._parse_metadata_json(row.get("metadata_json"))
        normalized["metadata"] = metadata
        erp_tags = metadata.get("erp_criticality")
        if isinstance(erp_tags, list):
            normalized["erp_criticality"] = [
                str(tag).strip().lower()
                for tag in erp_tags
                if str(tag).strip()
            ]
        else:
            normalized["erp_criticality"] = []
        severity = str(row.get("severity") or "").strip().lower()
        normalized["severity_rank"] = FINDING_SEVERITY_ORDER.get(severity, 99)
        return normalized

    @classmethod
    def _dedupe_findings(cls, findings: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
        deduped: List[Dict[str, Any]] = []
        seen = set()
        for finding in findings:
            key = (
                str(finding.get("finding_type") or ""),
                str(finding.get("file_path") or ""),
                int(finding.get("line_start") or 0),
                int(finding.get("line_end") or 0),
                str(finding.get("symbol") or ""),
                str(finding.get("message") or ""),
            )
            if key in seen:
                continue
            seen.add(key)
            deduped.append(finding)
        return deduped

    @staticmethod
    def _claim_focus_finding_types(claim: str) -> Set[str]:
        text = claim.lower()
        focus: Set[str] = set()
        if any(token in text for token in ("idempotent", "idempotency", "duplicate", "exactly-once")):
            focus.update({"IDEMPOTENCY_GAP", "IDEMPOTENCY_KEY_TYPE_MISMATCH"})
        if any(token in text for token in ("transaction", "atomic", "commit", "rollback")):
            focus.update({"TRANSACTIONAL_GAP", "EVENT_BEFORE_COMMIT", "NO_OUTBOX"})
        if any(token in text for token in ("outbox", "event before commit", "publish before commit")):
            focus.update({"EVENT_BEFORE_COMMIT", "NO_OUTBOX"})
        if any(token in text for token in ("money", "tax", "round", "rounding", "bigdecimal", "precision")):
            focus.update({"MONEY_ROUNDING", "BIGDECIMAL_MISUSE"})
        if any(token in text for token in ("tenant", "cross-tenant", "companyid", "boundary", "superadmin", "isolation")):
            focus.add("CROSS_TENANT_SCOPE_SPLIT")
        if any(token in text for token in ("safe", "secure", "correct", "no regression", "production-ready")):
            focus.add("*")
        return focus

    def _query_findings_for_claim_scope(
        self,
        symbols: Sequence[str],
        file_paths: Sequence[str],
        limit: int = 40,
    ) -> List[Dict[str, Any]]:
        filters: List[str] = []
        params: List[Any] = []

        normalized_symbols = [str(item).strip() for item in symbols if str(item).strip()]
        normalized_files = [str(item).strip() for item in file_paths if str(item).strip()]

        symbol_clauses: List[str] = []
        for symbol in normalized_symbols[:16]:
            symbol_clauses.append("(symbol = ? OR lower(symbol) LIKE lower(?))")
            params.extend([symbol, f"%{symbol}%"])
        if symbol_clauses:
            filters.append("(" + " OR ".join(symbol_clauses) + ")")

        if normalized_files:
            placeholders = ",".join("?" for _ in normalized_files[:24])
            filters.append(f"file_path IN ({placeholders})")
            params.extend(normalized_files[:24])

        where = " OR ".join(filters) if filters else "1=0"
        rows = self._query_sql(
            f"""
            SELECT finding_type, severity, title, message, file_path, line_start, line_end, symbol, source, metadata_json
            FROM findings
            WHERE {where}
            ORDER BY
              CASE severity
                WHEN 'critical' THEN 0
                WHEN 'high' THEN 1
                WHEN 'medium' THEN 2
                ELSE 3
              END ASC,
              file_path ASC,
              line_start ASC
            LIMIT ?
            """,
            (*params, max(1, min(120, limit))),
        )
        normalized = [self._normalize_finding_row(row) for row in rows]
        return self._dedupe_findings(normalized)

    def _find_dependency_path(
        self,
        from_symbol: str,
        to_symbol: str,
        max_depth: int,
        allowed_predicates: Set[str],
        max_expansions: int,
    ) -> Dict[str, Any]:
        start = from_symbol.strip()
        goal = to_symbol.strip()
        if not start or not goal:
            return {"found": False, "error": "from_symbol and to_symbol are required"}

        start_l = start.lower()
        goal_l = goal.lower()
        queue: Deque[Tuple[str, List[Dict[str, Any]]]] = deque()
        queue.append((start, []))
        seen_depth: Dict[str, int] = {start_l: 0}
        expansions = 0
        found_path: List[Dict[str, Any]] = []

        while queue and expansions < max_expansions:
            current, path = queue.popleft()
            current_l = current.lower()
            if current_l == goal_l:
                found_path = path
                break

            if len(path) >= max_depth:
                continue

            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE subject = ? OR object = ?
                LIMIT 260
                """,
                (current, current),
            )
            rows = sorted(
                rows,
                key=lambda row: (
                    -self._predicate_priority(str(row.get("predicate") or "")),
                    -float(row.get("confidence") or 0.0),
                ),
            )
            expansions += 1

            for row in rows:
                predicate = str(row.get("predicate") or "").upper()
                if predicate not in allowed_predicates:
                    continue

                subject = str(row.get("subject") or "")
                obj = str(row.get("object") or "")
                if not subject or not obj:
                    continue

                if subject == current:
                    neighbor = obj
                    direction = "forward"
                elif obj == current:
                    neighbor = subject
                    direction = "reverse"
                else:
                    continue

                edge = {
                    "from": current,
                    "to": neighbor,
                    "direction": direction,
                    "subject": subject,
                    "predicate": predicate,
                    "object": obj,
                    "file_path": str(row.get("file_path") or ""),
                    "line_start": int(row.get("line_start") or 0),
                    "line_end": int(row.get("line_end") or 0),
                    "confidence": round(float(row.get("confidence") or 0.0), 3),
                    "evidence": str(row.get("evidence") or ""),
                }

                next_path = [*path, edge]
                neighbor_l = neighbor.lower()
                if neighbor_l == goal_l:
                    found_path = next_path
                    queue.clear()
                    break

                next_depth = len(next_path)
                if seen_depth.get(neighbor_l, 9999) <= next_depth:
                    continue
                seen_depth[neighbor_l] = next_depth
                queue.append((neighbor, next_path))

        return {
            "from_symbol": start,
            "to_symbol": goal,
            "max_depth": max_depth,
            "allowed_predicates": sorted(allowed_predicates),
            "explored_symbols": len(seen_depth),
            "expansions": expansions,
            "found": bool(found_path),
            "hops": len(found_path),
            "path": found_path,
            "message": "Dependency path found." if found_path else "No path found within max_depth.",
        }

    def tools(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "rag_index",
                "description": "Build or refresh hybrid RAG index for ERP codebase.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "changed_only": {"type": "boolean"},
                        "diff_base": {"type": "string"},
                        "limit_files": {"type": "integer"},
                        "max_file_bytes": {"type": "integer"},
                        "force": {"type": "boolean"},
                        "prune_missing": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "rag_sync_index",
                "description": "Production-safe index sync (auto no-op/incremental/full) using indexed commit baseline and fallback controls.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "strategy": {
                            "type": "string",
                            "description": "auto | incremental | full (default: auto)",
                        },
                        "diff_base": {"type": "string"},
                        "force": {"type": "boolean"},
                        "fallback_full": {"type": "boolean"},
                        "dry_run": {"type": "boolean"},
                        "limit_files": {"type": "integer"},
                        "max_file_bytes": {"type": "integer"},
                        "prune_missing": {"type": "boolean"},
                        "run_findings": {"type": "boolean"},
                        "findings_top_k": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_query",
                "description": "Retrieve grounded context pack with cite-or-refuse contract.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "top_k": {"type": "integer"},
                        "depth": {"type": "integer"},
                        "include_docs": {"type": "boolean"},
                        "candidate_limit": {"type": "integer"},
                        "min_confirm_score": {"type": "number"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                        "max_per_file": {"type": "integer"},
                        "max_per_module": {"type": "integer"},
                        "min_module_diversity": {"type": "integer"},
                        "min_file_diversity": {"type": "integer"},
                    },
                    "required": ["query"],
                },
            },
            {
                "name": "rag_agent_brief",
                "description": "One-shot agent context pack: sync index, run grounded query, then attach impact/tests/route trace/release signals.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "ensure_fresh": {"type": "boolean"},
                        "sync_strategy": {"type": "string"},
                        "strict": {"type": "boolean"},
                        "top_k": {"type": "integer"},
                        "depth": {"type": "integer"},
                        "candidate_limit": {"type": "integer"},
                        "min_confirm_score": {"type": "number"},
                        "include_docs": {"type": "boolean"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                        "impact_depth": {"type": "integer"},
                        "impact_top_k": {"type": "integer"},
                        "include_tests": {"type": "boolean"},
                        "include_route_trace": {"type": "boolean"},
                        "run_release_gate": {"type": "boolean"},
                        "module": {"type": "string"},
                        "max_per_file": {"type": "integer"},
                        "max_per_module": {"type": "integer"},
                        "min_module_diversity": {"type": "integer"},
                        "min_file_diversity": {"type": "integer"},
                        "min_code_evidence": {"type": "integer"},
                        "min_token_hits": {"type": "integer"},
                        "min_token_coverage": {"type": "number"},
                        "min_high_signal_facts": {"type": "integer"},
                        "min_fact_confidence": {"type": "number"},
                    },
                    "required": ["query"],
                },
            },
            {
                "name": "rag_guarded_query",
                "description": "Strict query wrapper: returns error if evidence cannot be confirmed by cite-or-refuse policy.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "top_k": {"type": "integer"},
                        "depth": {"type": "integer"},
                        "include_docs": {"type": "boolean"},
                        "candidate_limit": {"type": "integer"},
                        "min_confirm_score": {"type": "number"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                        "min_code_evidence": {"type": "integer"},
                        "min_token_hits": {"type": "integer"},
                        "min_token_coverage": {"type": "number"},
                        "min_high_signal_facts": {"type": "integer"},
                        "min_fact_confidence": {"type": "number"},
                        "max_per_file": {"type": "integer"},
                        "max_per_module": {"type": "integer"},
                        "min_module_diversity": {"type": "integer"},
                        "min_file_diversity": {"type": "integer"},
                    },
                    "required": ["query"],
                },
            },
            {
                "name": "rag_verify_claims",
                "description": "Batch-verify behavior claims with cite-or-refuse evidence and per-claim support status.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "claims": {"type": "array", "items": {"type": "string"}},
                        "strict": {"type": "boolean"},
                        "top_k": {"type": "integer"},
                        "depth": {"type": "integer"},
                        "candidate_limit": {"type": "integer"},
                        "include_docs": {"type": "boolean"},
                        "min_confirm_score": {"type": "number"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                        "min_code_evidence": {"type": "integer"},
                        "min_token_hits": {"type": "integer"},
                        "min_token_coverage": {"type": "number"},
                        "min_high_signal_facts": {"type": "integer"},
                        "min_fact_confidence": {"type": "number"},
                        "max_per_file": {"type": "integer"},
                        "max_per_module": {"type": "integer"},
                        "min_module_diversity": {"type": "integer"},
                        "min_file_diversity": {"type": "integer"},
                        "limit_claims": {"type": "integer"},
                    },
                    "required": ["claims"],
                },
            },
            {
                "name": "rag_impact",
                "description": "Cross-module impact traversal for symbol/file.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "depth": {"type": "integer"},
                        "top_k": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_findings",
                "description": "Run silent-failure analyzer and index findings.",
                "inputSchema": {"type": "object", "properties": {"top_k": {"type": "integer"}}},
            },
            {
                "name": "rag_ticket_context",
                "description": "Get compact ticket context (status, slices, assignments, branches, timeline) for agent workflows.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "ticket_id": {"type": "string"},
                        "include_timeline": {"type": "boolean"},
                        "timeline_limit": {"type": "integer"},
                    },
                    "required": ["ticket_id"],
                },
            },
            {
                "name": "rag_agent_slices",
                "description": "List slices assigned to an agent across indexed tickets with status/branch metadata.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "agent": {"type": "string"},
                        "status": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                    "required": ["agent"],
                },
            },
            {
                "name": "rag_get_symbol",
                "description": "Lookup indexed chunks by symbol name.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"symbol": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["symbol"],
                },
            },
            {
                "name": "rag_search_facts",
                "description": "Search graph facts by token/predicate with confidence filtering for precise cross-module debugging.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "token": {"type": "string"},
                        "predicate": {"type": "string"},
                        "subject": {"type": "string"},
                        "object": {"type": "string"},
                        "min_confidence": {"type": "number"},
                        "limit": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_get_callers",
                "description": "Find caller facts for a symbol/method token.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"symbol": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["symbol"],
                },
            },
            {
                "name": "rag_get_callees",
                "description": "Find callee facts for a symbol/method token.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"symbol": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["symbol"],
                },
            },
            {
                "name": "rag_dependency_path",
                "description": "Find a grounded graph path between two symbols for cross-module debugging.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "from_symbol": {"type": "string"},
                        "to_symbol": {"type": "string"},
                        "max_depth": {"type": "integer"},
                        "max_expansions": {"type": "integer"},
                        "predicates": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["from_symbol", "to_symbol"],
                },
            },
            {
                "name": "rag_neighbors",
                "description": "Explore graph neighbors (multi-hop) for a symbol with predicate and confidence filters.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "depth": {"type": "integer"},
                        "limit": {"type": "integer"},
                        "min_confidence": {"type": "number"},
                        "predicates": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["symbol"],
                },
            },
            {
                "name": "rag_get_route_chain",
                "description": "Get route->handler relationships and nearby call chain.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "route": {"type": "string"},
                        "method": {"type": "string"},
                        "handler": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_trace_route",
                "description": "Build a route pipeline: route -> handler -> service calls -> tables/events/security.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"route": {"type": "string"}, "method": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["route"],
                },
            },
            {
                "name": "rag_route_conflicts",
                "description": "Detect route conflicts such as one route mapped to multiple handlers or contracts.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "route": {"type": "string"},
                        "method": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_get_type_usage",
                "description": "Find handlers/routes/methods that accept or return a DTO/type.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"type": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["type"],
                },
            },
            {
                "name": "rag_idempotency_mismatches",
                "description": "Inspect cross-module idempotency key type mismatch findings and supporting contracts.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "module": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_related_tests",
                "description": "Find tests related to a symbol/file/module using TEST_COVERS and test catalog facts.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "module": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_preflight_change",
                "description": "Bundle impact, route trace, and related tests into one MCP preflight payload.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "route": {"type": "string"},
                        "method": {"type": "string"},
                        "module": {"type": "string"},
                        "depth": {"type": "integer"},
                        "top_k": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_dedupe_resolve",
                "description": "Resolve canonical vs legacy duplicate implementations and suggest safest edit location.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "concept": {"type": "string"},
                        "symbol": {"type": "string"},
                        "limit": {"type": "integer"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "rag_patch_guard",
                "description": "Developer-first guardrail before patching: auto-detect scope, gather cross-module context, and return PASS/WARN/FAIL with actions.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "change_summary": {"type": "string"},
                        "changed_files": {"type": "array", "items": {"type": "string"}},
                        "symbols": {"type": "array", "items": {"type": "string"}},
                        "routes": {"type": "array", "items": {"type": "string"}},
                        "claims": {"type": "array", "items": {"type": "string"}},
                        "module": {"type": "string"},
                        "auto_detect_changes": {"type": "boolean"},
                        "ensure_fresh": {"type": "boolean"},
                        "sync_strategy": {"type": "string"},
                        "refresh_findings": {"type": "boolean"},
                        "strict": {"type": "boolean"},
                        "revision": {"type": "string"},
                        "allow_revision_mismatch": {"type": "boolean"},
                        "depth": {"type": "integer"},
                        "top_k": {"type": "integer"},
                        "max_changed_files": {"type": "integer"},
                        "max_symbols": {"type": "integer"},
                        "max_scope_items": {"type": "integer"},
                        "run_release_gate": {"type": "boolean"},
                        "require_tests": {"type": "boolean"},
                        "min_related_tests": {"type": "integer"},
                        "max_critical_findings": {"type": "integer"},
                        "max_high_findings": {"type": "integer"},
                        "max_route_conflicts": {"type": "integer"},
                        "max_config_conflicts": {"type": "integer"},
                        "fail_on_stale_index": {"type": "boolean"},
                        "max_per_file": {"type": "integer"},
                        "max_per_module": {"type": "integer"},
                        "min_module_diversity": {"type": "integer"},
                        "min_file_diversity": {"type": "integer"},
                        "include_full_payloads": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "rag_config_conflicts",
                "description": "Detect config keys defined in multiple places with potentially conflicting values.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "key": {"type": "string"},
                        "limit": {"type": "integer"},
                        "include_low": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "rag_failure_playbook",
                "description": "Build a debugging playbook: findings + config usage + impact + tests (+ optional route trace).",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "route": {"type": "string"},
                        "method": {"type": "string"},
                        "module": {"type": "string"},
                        "scope_files": {"type": "array", "items": {"type": "string"}},
                        "depth": {"type": "integer"},
                        "top_k": {"type": "integer"},
                        "refresh_findings": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "rag_release_gate",
                "description": "Compute release readiness PASS/FAIL using health, findings, conflicts, impact, and test signals.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "symbol": {"type": "string"},
                        "file_path": {"type": "string"},
                        "route": {"type": "string"},
                        "method": {"type": "string"},
                        "module": {"type": "string"},
                        "depth": {"type": "integer"},
                        "top_k": {"type": "integer"},
                        "refresh_findings": {"type": "boolean"},
                        "fail_on_stale_index": {"type": "boolean"},
                        "max_critical_findings": {"type": "integer"},
                        "max_high_findings": {"type": "integer"},
                        "max_route_conflicts": {"type": "integer"},
                        "max_config_conflicts": {"type": "integer"},
                        "require_tests": {"type": "boolean"},
                        "min_related_tests": {"type": "integer"},
                    },
                },
            },
            {
                "name": "rag_get_config_usage",
                "description": "Find where a config/env key is used and defined.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"key": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["key"],
                },
            },
            {
                "name": "rag_get_table_usage",
                "description": "Find reads/writes/definition edges for a table or entity token.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"table": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["table"],
                },
            },
            {
                "name": "rag_get_event_flow",
                "description": "Inspect event emitters and listeners for a given event token.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"event": {"type": "string"}, "limit": {"type": "integer"}},
                    "required": ["event"],
                },
            },
            {
                "name": "rag_get_module_dependencies",
                "description": "Get module dependency edges from docs and code ownership graph.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"module": {"type": "string"}, "limit": {"type": "integer"}},
                },
            },
            {
                "name": "rag_health",
                "description": "Return index health/coverage counters for production monitoring.",
                "inputSchema": {"type": "object", "properties": {}},
            },
            {
                "name": "rag_open_file_lines",
                "description": "Open exact file line range for cited evidence.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "file_path": {"type": "string"},
                        "line_start": {"type": "integer"},
                        "line_end": {"type": "integer"},
                    },
                    "required": ["file_path"],
                },
            },
        ]

    def call_tool(self, name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        if name == "rag_index":
            args: List[str] = ["--repo-root", str(self.repo_root)]
            if arguments.get("changed_only"):
                args.append("--changed-only")
            if arguments.get("diff_base"):
                args.extend(["--diff-base", str(arguments["diff_base"])])
            if arguments.get("limit_files") is not None:
                args.extend(["--limit-files", str(int(arguments["limit_files"]))])
            if arguments.get("max_file_bytes") is not None:
                args.extend(["--max-file-bytes", str(int(arguments["max_file_bytes"]))])
            if arguments.get("force"):
                args.append("--force")
            if arguments.get("prune_missing") is False:
                args.append("--no-prune-missing")
            payload = self._run_script("index.py", args)
            return self._tool_result(payload)

        if name == "rag_sync_index":
            strategy_raw = str(arguments.get("strategy", "auto")).strip().lower() or "auto"
            if strategy_raw not in {"auto", "incremental", "full"}:
                return self._tool_error("strategy must be one of: auto, incremental, full")

            diff_base_input = str(arguments.get("diff_base", "")).strip()
            fallback_full = bool(arguments.get("fallback_full", True))
            force = bool(arguments.get("force", False))
            dry_run = bool(arguments.get("dry_run", False))
            limit_files = arguments.get("limit_files")
            max_file_bytes = arguments.get("max_file_bytes")
            prune_missing = bool(arguments.get("prune_missing", True))
            run_findings = bool(arguments.get("run_findings", False))
            findings_top_k = max(1, min(500, int(arguments.get("findings_top_k", 80))))

            head_commit = self._head_commit()
            indexed_commit = self._indexed_commit()

            def _index_args(mode_name: str, diff_base: str = "") -> List[str]:
                args: List[str] = ["--repo-root", str(self.repo_root)]
                if mode_name == "incremental":
                    args.extend(["--changed-only", "--diff-base", diff_base])
                if limit_files is not None:
                    args.extend(["--limit-files", str(int(limit_files))])
                if max_file_bytes is not None:
                    args.extend(["--max-file-bytes", str(int(max_file_bytes))])
                if force:
                    args.append("--force")
                if not prune_missing:
                    args.append("--no-prune-missing")
                return args

            def _maybe_refresh_findings() -> Dict[str, Any]:
                if not run_findings:
                    return {}
                return self._run_script("silent_failures.py", ["--json", "--top-k", str(findings_top_k)])

            mode = "full"
            reason = ""
            if strategy_raw == "full":
                mode = "full"
                reason = "strategy=full"
            elif strategy_raw == "incremental":
                mode = "incremental"
                reason = "strategy=incremental"
            else:
                if not indexed_commit:
                    mode = "full"
                    reason = "no indexed commit baseline"
                elif head_commit and indexed_commit == head_commit and not force:
                    changed, indexable = self._changed_files_since(indexed_commit)
                    payload = {
                        "action": "noop",
                        "strategy": "auto",
                        "reason": "index already at HEAD commit",
                        "head_commit": head_commit or None,
                        "indexed_commit": indexed_commit or None,
                        "changed_files": len(changed),
                        "indexable_changed_files": len(indexable),
                    }
                    findings_payload = _maybe_refresh_findings()
                    if findings_payload:
                        payload["action"] = "noop_with_findings"
                        payload["findings_refresh"] = findings_payload
                    return self._tool_result(payload)
                else:
                    mode = "incremental"
                    reason = "stale index vs HEAD"

            diff_base_used = diff_base_input
            invalid_diff_base = ""
            changed: List[str] = []
            indexable: List[str] = []
            if mode == "incremental":
                if not diff_base_used:
                    diff_base_used = indexed_commit
                if not diff_base_used:
                    if fallback_full:
                        mode = "full"
                        reason = f"{reason}; missing incremental baseline -> full fallback"
                    else:
                        return self._tool_error("incremental sync requires 'diff_base' or an indexed commit baseline")
                elif not self._commit_exists(diff_base_used):
                    invalid_diff_base = diff_base_used
                    if fallback_full:
                        mode = "full"
                        diff_base_used = ""
                        reason = f"{reason}; invalid diff base -> full fallback"
                    else:
                        return self._tool_error(f"diff base commit not found: {invalid_diff_base}")

            if mode == "incremental":
                changed, indexable = self._changed_files_since(diff_base_used)
                if not force and not indexable:
                    payload = {
                        "action": "noop",
                        "strategy": strategy_raw,
                        "mode": mode,
                        "reason": "no indexable file changes since diff base",
                        "diff_base_used": diff_base_used,
                        "head_commit": head_commit or None,
                        "indexed_commit": indexed_commit or None,
                        "changed_files": len(changed),
                        "indexable_changed_files": 0,
                    }
                    findings_payload = _maybe_refresh_findings()
                    if findings_payload:
                        payload["action"] = "noop_with_findings"
                        payload["findings_refresh"] = findings_payload
                    return self._tool_result(payload)

            run_args = _index_args(mode, diff_base_used)

            planned = {
                "strategy": strategy_raw,
                "mode": mode,
                "reason": reason,
                "head_commit": head_commit or None,
                "indexed_commit": indexed_commit or None,
                "diff_base_used": diff_base_used or None,
                "invalid_diff_base": invalid_diff_base or None,
                "changed_files": len(changed),
                "indexable_changed_files": len(indexable),
                "prune_missing": prune_missing,
                "run_findings": run_findings,
                "findings_top_k": findings_top_k if run_findings else None,
                "command_args": run_args,
            }
            if dry_run:
                return self._tool_result({"action": "plan", "plan": planned})

            primary_result = self._run_script("index.py", run_args)
            attempts = [{"mode": mode, "result": primary_result}]
            final_result = primary_result
            final_mode = mode

            if mode == "incremental" and int(primary_result.get("exit_code", 1)) != 0 and fallback_full:
                full_args = _index_args("full")
                full_result = self._run_script("index.py", full_args)
                attempts.append({"mode": "full_fallback", "result": full_result})
                final_result = full_result
                final_mode = "full_fallback"

            findings_refresh: Dict[str, Any] = {}
            if int(final_result.get("exit_code", 1)) == 0:
                findings_refresh = _maybe_refresh_findings()

            payload = {
                "action": "sync",
                "plan": planned,
                "attempts": attempts,
                "final_mode": final_mode,
                "success": int(final_result.get("exit_code", 1)) == 0,
                "findings_refresh": findings_refresh or None,
            }
            return self._tool_result(payload)

        if name == "rag_query":
            query = str(arguments.get("query", "")).strip()
            if not query:
                return self._tool_error("'query' is required")
            args = ["--query", query, "--json"]
            if arguments.get("top_k") is not None:
                args.extend(["--top-k", str(int(arguments["top_k"]))])
            if arguments.get("depth") is not None:
                args.extend(["--depth", str(int(arguments["depth"]))])
            if arguments.get("candidate_limit") is not None:
                args.extend(["--candidate-limit", str(int(arguments["candidate_limit"]))])
            if arguments.get("include_docs"):
                args.append("--include-docs")
            if arguments.get("min_confirm_score") is not None:
                args.extend(["--min-confirm-score", str(float(arguments["min_confirm_score"]))])
            if arguments.get("revision"):
                args.extend(["--revision", str(arguments["revision"]).strip()])
            if arguments.get("allow_revision_mismatch"):
                args.append("--allow-revision-mismatch")
            if arguments.get("max_per_file") is not None:
                args.extend(["--max-per-file", str(int(arguments["max_per_file"]))])
            if arguments.get("max_per_module") is not None:
                args.extend(["--max-per-module", str(int(arguments["max_per_module"]))])
            if arguments.get("min_module_diversity") is not None:
                args.extend(["--min-module-diversity", str(int(arguments["min_module_diversity"]))])
            if arguments.get("min_file_diversity") is not None:
                args.extend(["--min-file-diversity", str(int(arguments["min_file_diversity"]))])
            payload = self._run_script("query.py", args)
            return self._tool_result(payload)

        if name == "rag_agent_brief":
            query = str(arguments.get("query", "")).strip()
            if not query:
                return self._tool_error("'query' is required")

            ensure_fresh = bool(arguments.get("ensure_fresh", True))
            sync_strategy = str(arguments.get("sync_strategy", "auto")).strip().lower() or "auto"
            if sync_strategy not in {"auto", "incremental", "full"}:
                return self._tool_error("sync_strategy must be one of: auto, incremental, full")

            strict = bool(arguments.get("strict", True))
            top_k = max(3, min(20, int(arguments.get("top_k", 8))))
            depth = max(0, min(4, int(arguments.get("depth", 2))))
            candidate_limit = max(40, min(600, int(arguments.get("candidate_limit", 240))))
            min_confirm_score = float(arguments.get("min_confirm_score", 0.45))
            include_docs = bool(arguments.get("include_docs", False))
            impact_depth = max(1, min(5, int(arguments.get("impact_depth", 2))))
            impact_top_k = max(5, min(120, int(arguments.get("impact_top_k", 30))))
            include_tests = bool(arguments.get("include_tests", True))
            include_route_trace = bool(arguments.get("include_route_trace", True))
            run_release_gate = bool(arguments.get("run_release_gate", False))
            module = str(arguments.get("module") or "").strip().lower()
            revision = self._normalize_revision(arguments.get("revision", ""))
            allow_revision_mismatch = bool(arguments.get("allow_revision_mismatch", False))
            max_per_file = int(arguments.get("max_per_file", 2))
            max_per_module = int(arguments.get("max_per_module", 4))
            min_module_diversity = int(arguments.get("min_module_diversity", 2))
            min_file_diversity = int(arguments.get("min_file_diversity", 3))

            sync_payload: Dict[str, Any] = {}
            if ensure_fresh:
                sync_result = self.call_tool("rag_sync_index", {"strategy": sync_strategy})
                if isinstance(sync_result, dict):
                    if bool(sync_result.get("isError")):
                        return sync_result
                    sync_payload = dict(sync_result.get("structuredContent") or {})

            query_args = {
                "query": query,
                "top_k": top_k,
                "depth": depth,
                "candidate_limit": candidate_limit,
                "include_docs": include_docs,
                "min_confirm_score": min_confirm_score,
                "allow_revision_mismatch": allow_revision_mismatch,
                "max_per_file": max_per_file,
                "max_per_module": max_per_module,
                "min_module_diversity": min_module_diversity,
                "min_file_diversity": min_file_diversity,
            }
            if revision:
                query_args["revision"] = revision
            if strict:
                query_args["min_code_evidence"] = int(arguments.get("min_code_evidence", 2))
                query_args["min_token_hits"] = int(arguments.get("min_token_hits", 1))
                query_args["min_token_coverage"] = float(arguments.get("min_token_coverage", 0.45))
                query_args["min_high_signal_facts"] = int(arguments.get("min_high_signal_facts", 1))
                query_args["min_fact_confidence"] = float(arguments.get("min_fact_confidence", 0.62))

            retrieval_payload: Dict[str, Any] = {}
            guard_error = ""
            query_tool = "rag_guarded_query" if strict else "rag_query"
            query_result = self.call_tool(query_tool, query_args)
            if isinstance(query_result, dict):
                if bool(query_result.get("isError")) and strict:
                    guard_error = str((query_result.get("structuredContent") or {}).get("error") or "")
                    fallback_result = self.call_tool("rag_query", query_args)
                    if isinstance(fallback_result, dict):
                        if bool(fallback_result.get("isError")):
                            return fallback_result
                        retrieval_payload = dict(fallback_result.get("structuredContent") or {})
                elif bool(query_result.get("isError")):
                    return query_result
                else:
                    retrieval_payload = dict(query_result.get("structuredContent") or {})

            evidence = retrieval_payload.get("evidence") if isinstance(retrieval_payload.get("evidence"), list) else []
            facts = retrieval_payload.get("facts") if isinstance(retrieval_payload.get("facts"), list) else []
            retrieval_meta = retrieval_payload.get("retrieval") if isinstance(retrieval_payload.get("retrieval"), dict) else {}

            primary_symbol = ""
            primary_file_path = ""
            primary_route_token = ""
            for item in evidence:
                symbol = str((item or {}).get("symbol") or "").strip()
                file_path = str((item or {}).get("file_path") or "").strip()
                if file_path and not primary_file_path:
                    primary_file_path = file_path
                if symbol.startswith("route:") and not primary_route_token:
                    primary_route_token = symbol[len("route:") :].strip()
                if symbol and not symbol.startswith("route:") and not primary_symbol:
                    primary_symbol = symbol
                if primary_symbol and primary_file_path and primary_route_token:
                    break

            if not primary_route_token:
                for fact in facts:
                    predicate = str((fact or {}).get("predicate") or "")
                    subject = str((fact or {}).get("subject") or "")
                    if predicate in {"ROUTE_TO_HANDLER", "HANDLES_ROUTE", "ROUTE_CALLS"} and subject.startswith("route:"):
                        primary_route_token = subject[len("route:") :].strip()
                        break

            if primary_symbol and not primary_route_token:
                route_rows = self._query_sql(
                    """
                    SELECT subject, confidence
                    FROM facts
                    WHERE predicate = 'ROUTE_TO_HANDLER'
                      AND (object = ? OR lower(object) LIKE lower(?))
                    ORDER BY confidence DESC
                    LIMIT 1
                    """,
                    (primary_symbol, f"%{primary_symbol}%"),
                )
                if route_rows:
                    route_symbol = str(route_rows[0].get("subject") or "")
                    if route_symbol.startswith("route:"):
                        primary_route_token = route_symbol[len("route:") :].strip()

            route_method = ""
            route_path = ""
            if primary_route_token:
                if " " in primary_route_token:
                    route_method, route_path = primary_route_token.split(" ", 1)
                else:
                    route_path = primary_route_token
            if route_path and not route_path.startswith("/"):
                route_path = "/" + route_path

            impact_payload: Dict[str, Any] = {}
            if primary_symbol or primary_file_path:
                impact_args: List[str] = ["--json", "--depth", str(impact_depth), "--top-k", str(impact_top_k)]
                if primary_symbol:
                    impact_args.extend(["--symbol", primary_symbol])
                if primary_file_path:
                    impact_args.extend(["--file-path", primary_file_path])
                impact_payload = self._run_script("impact.py", impact_args)

            effective_module = module
            if not effective_module and impact_payload.get("impacted_modules"):
                impacted_modules = impact_payload.get("impacted_modules")
                if isinstance(impacted_modules, list) and impacted_modules:
                    effective_module = str(impacted_modules[0]).strip().lower()

            related_tests_payload: Dict[str, Any] = {}
            if include_tests and (primary_symbol or primary_file_path or effective_module):
                tests_result = self.call_tool(
                    "rag_related_tests",
                    {
                        "symbol": primary_symbol,
                        "file_path": primary_file_path,
                        "module": effective_module,
                        "limit": impact_top_k,
                    },
                )
                if isinstance(tests_result, dict) and not bool(tests_result.get("isError")):
                    related_tests_payload = dict(tests_result.get("structuredContent") or {})

            route_trace_payload: Dict[str, Any] = {}
            if include_route_trace and route_path:
                trace_result = self.call_tool(
                    "rag_trace_route",
                    {"route": route_path, "method": route_method, "limit": impact_top_k},
                )
                if isinstance(trace_result, dict) and not bool(trace_result.get("isError")):
                    route_trace_payload = dict(trace_result.get("structuredContent") or {})

            release_gate_payload: Dict[str, Any] = {}
            if run_release_gate and (primary_symbol or primary_file_path or route_path or effective_module):
                gate_result = self.call_tool(
                    "rag_release_gate",
                    {
                        "symbol": primary_symbol,
                        "file_path": primary_file_path,
                        "route": route_path,
                        "method": route_method,
                        "module": effective_module,
                        "depth": impact_depth,
                        "top_k": impact_top_k,
                    },
                )
                if isinstance(gate_result, dict) and not bool(gate_result.get("isError")):
                    release_gate_payload = dict(gate_result.get("structuredContent") or {})

            strict_guard_passed: Optional[bool] = None
            if strict:
                strict_guard_passed = not bool(guard_error)

            summary = {
                "can_confirm": bool(retrieval_payload.get("can_confirm")),
                "confidence": float(retrieval_payload.get("confidence") or 0.0),
                "strict_guard_passed": strict_guard_passed,
                "used_fallback_query": bool(guard_error),
                "guard_error": guard_error or None,
                "evidence_count": len(evidence),
                "fact_count": len(facts),
                "module_diversity": int(retrieval_meta.get("module_diversity") or 0),
                "file_diversity": int(retrieval_meta.get("file_diversity") or 0),
                "primary_symbol": primary_symbol or None,
                "primary_file_path": primary_file_path or None,
                "route": route_path or None,
                "method": route_method or None,
                "impact_edges": int(impact_payload.get("edge_count") or 0),
                "related_tests": int(related_tests_payload.get("count") or 0),
                "route_handlers": len(route_trace_payload.get("handlers") or []),
                "release_status": release_gate_payload.get("status"),
            }

            payload = {
                "input": {
                    "query": query,
                    "ensure_fresh": ensure_fresh,
                    "sync_strategy": sync_strategy,
                    "strict": strict,
                    "top_k": top_k,
                    "depth": depth,
                    "candidate_limit": candidate_limit,
                    "min_confirm_score": min_confirm_score,
                    "revision": revision or None,
                    "allow_revision_mismatch": allow_revision_mismatch,
                    "max_per_file": max_per_file,
                    "max_per_module": max_per_module,
                    "min_module_diversity": min_module_diversity,
                    "min_file_diversity": min_file_diversity,
                    "min_code_evidence": int(query_args.get("min_code_evidence", 0)) if strict else None,
                    "min_token_hits": int(query_args.get("min_token_hits", 0)) if strict else None,
                    "min_token_coverage": float(query_args.get("min_token_coverage", 0.0)) if strict else None,
                    "min_high_signal_facts": int(query_args.get("min_high_signal_facts", 0)) if strict else None,
                    "min_fact_confidence": float(query_args.get("min_fact_confidence", 0.0)) if strict else None,
                    "impact_depth": impact_depth,
                    "impact_top_k": impact_top_k,
                    "include_tests": include_tests,
                    "include_route_trace": include_route_trace,
                    "run_release_gate": run_release_gate,
                    "module": effective_module or None,
                },
                "summary": summary,
                "layers": {
                    "layer0_executive_map": {
                        "query": query,
                        "primary_symbol": primary_symbol or None,
                        "primary_file_path": primary_file_path or None,
                        "route": route_path or None,
                        "method": route_method or None,
                        "module": effective_module or None,
                        "strict_guard_passed": strict_guard_passed,
                    },
                    "layer1_flow_trace": {
                        "route_trace_summary": {
                            "handlers": len(route_trace_payload.get("handlers") or []),
                            "handler_calls": len(route_trace_payload.get("handler_calls") or []),
                            "tables": len(route_trace_payload.get("tables") or []),
                            "events": len(route_trace_payload.get("events") or []),
                        }
                        if isinstance(route_trace_payload, dict)
                        else {},
                        "route_trace": route_trace_payload or None,
                    },
                    "layer2_impact_tests": {
                        "impact_summary": {
                            "edge_count": int(impact_payload.get("edge_count") or 0),
                            "impacted_files": len(impact_payload.get("impacted_files") or []),
                            "impacted_modules": len(impact_payload.get("impacted_modules") or []),
                        }
                        if isinstance(impact_payload, dict)
                        else {},
                        "related_tests_summary": {
                            "count": int(related_tests_payload.get("count") or 0),
                        }
                        if isinstance(related_tests_payload, dict)
                        else {},
                        "release_gate_summary": {
                            "status": release_gate_payload.get("status"),
                            "summary": release_gate_payload.get("summary"),
                        }
                        if isinstance(release_gate_payload, dict)
                        else {},
                    },
                    "layer3_evidence_bundle": {
                        "evidence": evidence[: min(len(evidence), 8)],
                        "facts": facts[: min(len(facts), 24)],
                    },
                },
                "sync": sync_payload or None,
                "retrieval": retrieval_payload,
                "impact": impact_payload or None,
                "related_tests": related_tests_payload or None,
                "route_trace": route_trace_payload or None,
                "release_gate": release_gate_payload or None,
            }
            return self._tool_result(payload)

        if name == "rag_guarded_query":
            query = str(arguments.get("query", "")).strip()
            if not query:
                return self._tool_error("'query' is required")
            args = ["--query", query, "--json"]
            if arguments.get("top_k") is not None:
                args.extend(["--top-k", str(int(arguments["top_k"]))])
            if arguments.get("depth") is not None:
                args.extend(["--depth", str(int(arguments["depth"]))])
            if arguments.get("candidate_limit") is not None:
                args.extend(["--candidate-limit", str(int(arguments["candidate_limit"]))])
            if arguments.get("include_docs"):
                args.append("--include-docs")
            if arguments.get("min_confirm_score") is not None:
                args.extend(["--min-confirm-score", str(float(arguments["min_confirm_score"]))])
            if arguments.get("revision"):
                args.extend(["--revision", str(arguments["revision"]).strip()])
            if arguments.get("allow_revision_mismatch"):
                args.append("--allow-revision-mismatch")
            if arguments.get("max_per_file") is not None:
                args.extend(["--max-per-file", str(int(arguments["max_per_file"]))])
            if arguments.get("max_per_module") is not None:
                args.extend(["--max-per-module", str(int(arguments["max_per_module"]))])
            if arguments.get("min_module_diversity") is not None:
                args.extend(["--min-module-diversity", str(int(arguments["min_module_diversity"]))])
            if arguments.get("min_file_diversity") is not None:
                args.extend(["--min-file-diversity", str(int(arguments["min_file_diversity"]))])

            payload = self._run_script("query.py", args)
            min_code_evidence = max(1, int(arguments.get("min_code_evidence", 2)))
            min_token_hits = max(1, int(arguments.get("min_token_hits", 2)))
            min_token_coverage = float(arguments.get("min_token_coverage", 0.45))
            min_token_coverage = max(0.0, min(1.0, min_token_coverage))
            min_high_signal_facts = max(0, int(arguments.get("min_high_signal_facts", 1)))
            min_fact_confidence = float(arguments.get("min_fact_confidence", 0.62))
            min_fact_confidence = max(0.0, min(1.0, min_fact_confidence))
            evidence = payload.get("evidence") if isinstance(payload, dict) else []
            code_evidence_count = 0
            if isinstance(evidence, list):
                for item in evidence:
                    kind = str((item or {}).get("kind") or "")
                    if kind.startswith("java_") or kind in {"sql_statement", "openapi_operation", "py_function", "py_class"}:
                        code_evidence_count += 1

            high_signal_fact_count = 0
            facts = payload.get("facts") if isinstance(payload, dict) else []
            if isinstance(facts, list):
                for fact in facts:
                    predicate = str((fact or {}).get("predicate") or "").upper()
                    confidence = float((fact or {}).get("confidence") or 0.0)
                    if self._is_high_signal_predicate(predicate) and confidence >= min_fact_confidence:
                        high_signal_fact_count += 1
            structural_kinds = {
                "java_route_handler",
                "openapi_operation",
                "workflow_chain",
                "workflow_registry_row",
                "java_repository_method_decl",
                "sql_statement",
            }
            structural_evidence_count = 0
            if isinstance(evidence, list):
                for item in evidence:
                    kind = str((item or {}).get("kind") or "")
                    symbol = str((item or {}).get("symbol") or "")
                    if kind in structural_kinds or symbol.startswith("route:"):
                        structural_evidence_count += 1
            effective_high_signal_count = max(high_signal_fact_count, structural_evidence_count)

            weak_terms = {
                "code",
                "data",
                "module",
                "modules",
                "service",
                "services",
                "endpoint",
                "endpoints",
                "route",
                "routes",
                "api",
                "debug",
                "issue",
                "bug",
                "error",
                "flow",
                "flows",
                "path",
                "return",
                "returns",
            }
            tokens = []
            if isinstance(payload, dict):
                retrieval = payload.get("retrieval")
                if isinstance(retrieval, dict):
                    raw_tokens = retrieval.get("tokens")
                    if isinstance(raw_tokens, list):
                        tokens = [str(t).lower() for t in raw_tokens if str(t).strip()]
            salient_tokens = [token for token in tokens if len(token) >= 4 and token not in weak_terms][:12]
            token_hits = 0
            if salient_tokens and isinstance(evidence, list):
                matched = set()
                for item in evidence[: min(len(evidence), 10)]:
                    text_parts = [
                        str((item or {}).get("file_path") or ""),
                        str((item or {}).get("symbol") or ""),
                        str((item or {}).get("excerpt") or ""),
                    ]
                    haystack = "\n".join(text_parts).lower()
                    for token in salient_tokens:
                        if token in haystack:
                            matched.add(token)
                token_hits = len(matched)
            token_coverage = (token_hits / len(salient_tokens)) if salient_tokens else 1.0
            required_token_hits = min(min_token_hits, len(salient_tokens)) if salient_tokens else 0

            can_confirm = bool(payload.get("can_confirm")) if isinstance(payload, dict) else False
            if (
                (not can_confirm)
                or code_evidence_count < min_code_evidence
                or token_hits < required_token_hits
                or (bool(salient_tokens) and token_coverage < min_token_coverage)
                or effective_high_signal_count < min_high_signal_facts
            ):
                message = "I can't confirm from indexed repo evidence."
                unresolved = payload.get("unresolved") if isinstance(payload, dict) else []
                if isinstance(unresolved, list) and unresolved:
                    message = f"{message} {' | '.join(str(x) for x in unresolved[:3])}"
                else:
                    message = (
                        f"{message} guard_failed(code_evidence={code_evidence_count}, "
                        f"token_hits={token_hits}/{len(salient_tokens)}, coverage={token_coverage:.2f}, "
                        f"high_signal_facts={effective_high_signal_count})"
                    )
                return self._tool_error(message)

            guarded_payload = dict(payload)
            guarded_payload["guard"] = {
                "enforced": True,
                "min_code_evidence": min_code_evidence,
                "code_evidence_count": code_evidence_count,
                "min_token_hits": min_token_hits,
                "required_token_hits": required_token_hits,
                "min_token_coverage": min_token_coverage,
                "token_hits": token_hits,
                "token_coverage": round(token_coverage, 3),
                "salient_tokens": salient_tokens,
                "min_high_signal_facts": min_high_signal_facts,
                "min_fact_confidence": min_fact_confidence,
                "high_signal_fact_count": high_signal_fact_count,
                "structural_evidence_count": structural_evidence_count,
                "effective_high_signal_count": effective_high_signal_count,
                "passed": True,
            }
            return self._tool_result(guarded_payload)

        if name == "rag_verify_claims":
            claims_raw = arguments.get("claims")
            if isinstance(claims_raw, list):
                claims = [str(item).strip() for item in claims_raw if str(item).strip()]
            elif isinstance(claims_raw, str):
                claims = [claims_raw.strip()] if claims_raw.strip() else []
            else:
                claims = []
            if not claims:
                return self._tool_error("'claims' must be a non-empty string array")

            limit_claims = max(1, min(12, int(arguments.get("limit_claims", 8))))
            strict = bool(arguments.get("strict", True))
            claims = claims[:limit_claims]

            base_args: Dict[str, Any] = {}
            passthrough_keys = [
                "top_k",
                "depth",
                "candidate_limit",
                "include_docs",
                "min_confirm_score",
                "revision",
                "allow_revision_mismatch",
                "max_per_file",
                "max_per_module",
                "min_module_diversity",
                "min_file_diversity",
            ]
            for key in passthrough_keys:
                if key in arguments and arguments.get(key) is not None:
                    base_args[key] = arguments.get(key)

            guard_keys = [
                "min_code_evidence",
                "min_token_hits",
                "min_token_coverage",
                "min_high_signal_facts",
                "min_fact_confidence",
            ]
            if strict:
                for key in guard_keys:
                    if key in arguments and arguments.get(key) is not None:
                        base_args[key] = arguments.get(key)

            results: List[Dict[str, Any]] = []
            for claim in claims:
                query_args = {"query": claim, **base_args}
                primary_tool = "rag_guarded_query" if strict else "rag_query"
                primary_result = self.call_tool(primary_tool, query_args)
                guard_error = ""
                fallback_used = False

                claim_payload: Dict[str, Any] = {}
                if isinstance(primary_result, dict) and bool(primary_result.get("isError")):
                    if strict:
                        guard_error = str((primary_result.get("structuredContent") or {}).get("error") or "")
                        fallback_used = True
                        fallback_result = self.call_tool("rag_query", query_args)
                        if isinstance(fallback_result, dict) and not bool(fallback_result.get("isError")):
                            claim_payload = dict(fallback_result.get("structuredContent") or {})
                    else:
                        claim_payload = {}
                elif isinstance(primary_result, dict):
                    claim_payload = dict(primary_result.get("structuredContent") or {})

                can_confirm = bool(claim_payload.get("can_confirm"))
                if strict:
                    if guard_error:
                        status = "needs_review" if can_confirm else "unsupported"
                    else:
                        status = "supported" if can_confirm else "unsupported"
                else:
                    status = "supported" if can_confirm else "unsupported"

                evidence = claim_payload.get("evidence") if isinstance(claim_payload.get("evidence"), list) else []
                facts = claim_payload.get("facts") if isinstance(claim_payload.get("facts"), list) else []
                citations = []
                for item in evidence[:4]:
                    citations.append(
                        {
                            "file_path": str((item or {}).get("file_path") or ""),
                            "line_start": int((item or {}).get("line_start") or 0),
                            "line_end": int((item or {}).get("line_end") or 0),
                            "kind": str((item or {}).get("kind") or ""),
                            "symbol": str((item or {}).get("symbol") or ""),
                            "score": float((item or {}).get("score") or 0.0),
                        }
                    )

                key_facts = []
                for fact in facts:
                    predicate = str((fact or {}).get("predicate") or "").upper()
                    if not self._is_high_signal_predicate(predicate):
                        continue
                    key_facts.append(
                        {
                            "subject": str((fact or {}).get("subject") or ""),
                            "predicate": predicate,
                            "object": str((fact or {}).get("object") or ""),
                            "file_path": str((fact or {}).get("file_path") or ""),
                            "line_start": int((fact or {}).get("line_start") or 0),
                            "line_end": int((fact or {}).get("line_end") or 0),
                            "confidence": float((fact or {}).get("confidence") or 0.0),
                        }
                    )
                    if len(key_facts) >= 8:
                        break

                if not key_facts and citations:
                    seen_fact_keys = set()
                    for citation in citations:
                        symbol = str(citation.get("symbol") or "").strip()
                        if not symbol:
                            continue
                        rows = self._query_sql(
                            """
                            SELECT subject, predicate, object, file_path, line_start, line_end, confidence
                            FROM facts
                            WHERE subject = ?
                              AND predicate IN (
                                'ROUTE_TO_HANDLER',
                                'HANDLES_ROUTE',
                                'ROUTE_CALLS',
                                'HANDLER_CALLS',
                                'ROUTE_CONTRACT',
                                'ROUTE_IDEMPOTENCY_KEY_TYPE',
                                'ACCEPTS_TYPE',
                                'RETURNS_TYPE',
                                'IDEMPOTENCY_KEY_TYPE',
                                'READS_TABLE',
                                'WRITES_TABLE',
                                'SECURED_BY',
                                'EMITS_EVENT',
                                'LISTENS_EVENT',
                                'MODULE_DEPENDS_ON',
                                'WORKFLOW_CHAIN',
                                'TEST_COVERS',
                                'HAS_SLICE',
                                'SLICE_ID',
                                'SLICE_AGENT',
                                'SLICE_STATUS',
                                'SLICE_LANE',
                                'SLICE_BRANCH',
                                'SLICE_WORKTREE',
                                'SLICE_SCOPE_PATH',
                                'SLICE_REVIEWER',
                                'SLICE_OBJECTIVE',
                                'TICKET_STATUS',
                                'TICKET_TITLE',
                                'TICKET_PRIORITY',
                                'TICKET_GOAL',
                                'TICKET_BASE_BRANCH',
                                'TICKET_UPDATED_AT',
                                'TICKET_CREATED_AT',
                                'TICKET_EVENT'
                              )
                            ORDER BY confidence DESC
                            LIMIT 20
                            """,
                            (symbol,),
                        )
                        for row in rows:
                            predicate = str(row.get("predicate") or "").upper()
                            fact_key = (
                                str(row.get("subject") or ""),
                                predicate,
                                str(row.get("object") or ""),
                                str(row.get("file_path") or ""),
                                int(row.get("line_start") or 0),
                                int(row.get("line_end") or 0),
                            )
                            if fact_key in seen_fact_keys:
                                continue
                            seen_fact_keys.add(fact_key)
                            key_facts.append(
                                {
                                    "subject": fact_key[0],
                                    "predicate": predicate,
                                    "object": fact_key[2],
                                    "file_path": fact_key[3],
                                    "line_start": fact_key[4],
                                    "line_end": fact_key[5],
                                    "confidence": float(row.get("confidence") or 0.0),
                                }
                            )
                            if len(key_facts) >= 8:
                                break
                        if len(key_facts) >= 8:
                            break

                claim_symbols: List[str] = []
                claim_files: List[str] = []
                for citation in citations:
                    symbol = str(citation.get("symbol") or "").strip()
                    file_path = str(citation.get("file_path") or "").strip()
                    if symbol and symbol not in claim_symbols:
                        claim_symbols.append(symbol)
                    if file_path and file_path not in claim_files:
                        claim_files.append(file_path)
                for fact in key_facts:
                    subject = str(fact.get("subject") or "").strip()
                    obj = str(fact.get("object") or "").strip()
                    for token in (subject, obj):
                        if not token:
                            continue
                        if "#" in token or token.startswith(("route:", "type:", "workflow:", "module:")):
                            if token not in claim_symbols:
                                claim_symbols.append(token)
                    file_path = str(fact.get("file_path") or "").strip()
                    if file_path and file_path not in claim_files:
                        claim_files.append(file_path)

                scoped_findings = self._query_findings_for_claim_scope(
                    symbols=claim_symbols,
                    file_paths=claim_files,
                    limit=24,
                )
                focus_types = self._claim_focus_finding_types(claim)
                finding_conflicts: List[Dict[str, Any]] = []
                for finding in scoped_findings:
                    finding_type = str(finding.get("finding_type") or "").upper()
                    severity = str(finding.get("severity") or "").lower()
                    if severity not in {"critical", "high", "medium"}:
                        continue
                    if "*" not in focus_types and focus_types and finding_type not in focus_types:
                        continue
                    if "*" in focus_types and severity not in {"critical", "high"}:
                        continue
                    finding_conflicts.append(
                        {
                            "finding_type": finding_type,
                            "severity": severity,
                            "title": str(finding.get("title") or ""),
                            "message": str(finding.get("message") or ""),
                            "file_path": str(finding.get("file_path") or ""),
                            "line_start": int(finding.get("line_start") or 0),
                            "line_end": int(finding.get("line_end") or 0),
                            "symbol": str(finding.get("symbol") or ""),
                            "erp_criticality": finding.get("erp_criticality") if isinstance(finding.get("erp_criticality"), list) else [],
                            "why": str((finding.get("metadata") or {}).get("why") or ""),
                            "suggested_fix_pattern": str((finding.get("metadata") or {}).get("suggested_fix_pattern") or ""),
                        }
                    )
                    if len(finding_conflicts) >= 8:
                        break

                if finding_conflicts and status == "supported":
                    status = "needs_review"

                unresolved = claim_payload.get("unresolved")
                unresolved_list = unresolved[:4] if isinstance(unresolved, list) else []
                message_text = str(claim_payload.get("message") or "")
                if finding_conflicts:
                    message_text = (
                        f"{message_text} | finding_conflicts={len(finding_conflicts)}"
                        if message_text
                        else f"finding_conflicts={len(finding_conflicts)}"
                    )

                results.append(
                    {
                        "claim": claim,
                        "status": status,
                        "supported": status == "supported",
                        "strict_guard_passed": (not guard_error) if strict else None,
                        "used_fallback_query": fallback_used,
                        "guard_error": guard_error or None,
                        "can_confirm": can_confirm,
                        "confidence": float(claim_payload.get("confidence") or 0.0),
                        "message": message_text,
                        "citation_count": len(citations),
                        "citations": citations,
                        "key_fact_count": len(key_facts),
                        "key_facts": key_facts,
                        "finding_conflicts_count": len(finding_conflicts),
                        "finding_conflicts": finding_conflicts,
                        "unresolved": unresolved_list,
                    }
                )

            supported_count = len([item for item in results if item["status"] == "supported"])
            needs_review_count = len([item for item in results if item["status"] == "needs_review"])
            unsupported_count = len([item for item in results if item["status"] == "unsupported"])

            payload = {
                "strict": strict,
                "claims_count": len(results),
                "summary": {
                    "supported": supported_count,
                    "needs_review": needs_review_count,
                    "unsupported": unsupported_count,
                    "fallback_used": len([item for item in results if item["used_fallback_query"]]),
                    "claim_finding_conflicts": len([item for item in results if int(item.get("finding_conflicts_count") or 0) > 0]),
                },
                "results": results,
            }
            return self._tool_result(payload)

        if name == "rag_impact":
            args = ["--json"]
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            if not symbol and not file_path:
                return self._tool_error("provide either 'symbol' or 'file_path'")
            if symbol:
                args.extend(["--symbol", symbol])
            if file_path:
                args.extend(["--file-path", file_path])
            if arguments.get("depth") is not None:
                args.extend(["--depth", str(int(arguments["depth"]))])
            if arguments.get("top_k") is not None:
                args.extend(["--top-k", str(int(arguments["top_k"]))])
            payload = self._run_script("impact.py", args)
            return self._tool_result(payload)

        if name == "rag_findings":
            args = ["--json"]
            if arguments.get("top_k") is not None:
                args.extend(["--top-k", str(int(arguments["top_k"]))])
            payload = self._run_script("silent_failures.py", args)
            return self._tool_result(payload)

        if name == "rag_ticket_context":
            ticket_id_raw = str(arguments.get("ticket_id", "")).strip()
            if not ticket_id_raw:
                return self._tool_error("'ticket_id' is required")
            ticket_id = ticket_id_raw.upper()
            include_timeline = bool(arguments.get("include_timeline", True))
            timeline_limit = max(1, min(120, int(arguments.get("timeline_limit", 30))))

            ticket_symbol = f"ticket:{ticket_id}"
            path_prefix = f"tickets/{ticket_id}/%"

            file_rows = self._query_sql(
                """
                SELECT path, language, module, commit_sha
                FROM files
                WHERE path LIKE ?
                ORDER BY path ASC
                """,
                (path_prefix,),
            )

            ticket_facts = self._query_sql(
                """
                SELECT predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE subject = ?
                  AND predicate IN (
                    'TICKET_STATUS',
                    'TICKET_TITLE',
                    'TICKET_GOAL',
                    'TICKET_PRIORITY',
                    'TICKET_BASE_BRANCH',
                    'TICKET_UPDATED_AT',
                    'TICKET_CREATED_AT',
                    'HAS_SLICE',
                    'TICKET_EVENT'
                  )
                ORDER BY confidence DESC, line_start ASC
                """,
                (ticket_symbol,),
            )

            ticket_summary: Dict[str, Any] = {
                "ticket_id": ticket_id,
                "ticket_symbol": ticket_symbol,
                "status": None,
                "title": None,
                "goal": None,
                "priority": None,
                "base_branch": None,
                "updated_at": None,
                "created_at": None,
            }
            scalar_predicates = {
                "TICKET_STATUS": "status",
                "TICKET_TITLE": "title",
                "TICKET_GOAL": "goal",
                "TICKET_PRIORITY": "priority",
                "TICKET_BASE_BRANCH": "base_branch",
                "TICKET_UPDATED_AT": "updated_at",
                "TICKET_CREATED_AT": "created_at",
            }
            slice_symbols: List[str] = []
            for row in ticket_facts:
                predicate = str(row.get("predicate") or "")
                obj = str(row.get("object") or "")
                field_name = scalar_predicates.get(predicate)
                if field_name and not ticket_summary.get(field_name):
                    ticket_summary[field_name] = obj
                if predicate == "HAS_SLICE" and obj and obj not in slice_symbols:
                    slice_symbols.append(obj)

            slice_fact_rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE subject LIKE ?
                  AND predicate IN (
                    'SLICE_ID',
                    'SLICE_AGENT',
                    'SLICE_STATUS',
                    'SLICE_LANE',
                    'SLICE_BRANCH',
                    'SLICE_WORKTREE',
                    'SLICE_SCOPE_PATH',
                    'SLICE_REVIEWER',
                    'SLICE_OBJECTIVE',
                    'BELONGS_TO'
                  )
                ORDER BY subject ASC, confidence DESC, line_start ASC
                """,
                (f"slice:{ticket_id}:%",),
            )

            slices: Dict[str, Dict[str, Any]] = {}

            def _ensure_slice(slice_symbol: str) -> Dict[str, Any]:
                item = slices.get(slice_symbol)
                if item is not None:
                    return item
                slice_id = slice_symbol.split(":", 2)[2] if slice_symbol.startswith(f"slice:{ticket_id}:") else slice_symbol
                item = {
                    "slice_symbol": slice_symbol,
                    "slice_id": slice_id,
                    "agent": None,
                    "status": None,
                    "lane": None,
                    "branch": None,
                    "worktree": None,
                    "objective": None,
                    "scope_paths": [],
                    "reviewers": [],
                }
                slices[slice_symbol] = item
                return item

            for symbol in slice_symbols:
                _ensure_slice(symbol)

            for row in slice_fact_rows:
                slice_symbol = str(row.get("subject") or "").strip()
                if not slice_symbol:
                    continue
                item = _ensure_slice(slice_symbol)
                predicate = str(row.get("predicate") or "")
                obj = str(row.get("object") or "").strip()
                if not obj:
                    continue
                if predicate == "SLICE_ID":
                    item["slice_id"] = obj
                elif predicate == "SLICE_AGENT":
                    item["agent"] = obj[len("agent:") :] if obj.startswith("agent:") else obj
                elif predicate == "SLICE_STATUS":
                    item["status"] = obj
                elif predicate == "SLICE_LANE":
                    item["lane"] = obj
                elif predicate == "SLICE_BRANCH":
                    item["branch"] = obj
                elif predicate == "SLICE_WORKTREE":
                    item["worktree"] = obj
                elif predicate == "SLICE_OBJECTIVE":
                    if not item.get("objective"):
                        item["objective"] = obj
                elif predicate == "SLICE_SCOPE_PATH":
                    if obj not in item["scope_paths"]:
                        item["scope_paths"].append(obj)
                elif predicate == "SLICE_REVIEWER":
                    reviewer = obj[len("agent:") :] if obj.startswith("agent:") else obj
                    if reviewer and reviewer not in item["reviewers"]:
                        item["reviewers"].append(reviewer)

            timeline_events: List[Dict[str, Any]] = []
            if include_timeline:
                timeline_rows = self._query_sql(
                    """
                    SELECT line_start, line_end, content, metadata_json
                    FROM chunks
                    WHERE file_path = ? AND kind = 'ticket_timeline_event'
                    ORDER BY line_start DESC
                    LIMIT ?
                    """,
                    (f"tickets/{ticket_id}/TIMELINE.md", timeline_limit),
                )
                for row in timeline_rows:
                    metadata = self._parse_metadata_json(row.get("metadata_json"))
                    timeline_events.append(
                        {
                            "line_start": int(row.get("line_start") or 0),
                            "line_end": int(row.get("line_end") or 0),
                            "timestamp": str(metadata.get("timestamp") or ""),
                            "detail": str(row.get("content") or "").strip(),
                        }
                    )

            ordered_slices = sorted(
                slices.values(),
                key=lambda item: (str(item.get("slice_id") or ""), str(item.get("slice_symbol") or "")),
            )

            payload = {
                "ticket": ticket_summary,
                "files_indexed": file_rows,
                "slice_count": len(ordered_slices),
                "slices": ordered_slices,
                "timeline_count": len(timeline_events),
                "timeline": timeline_events,
                "message": (
                    "Ticket context loaded from indexed facts."
                    if file_rows
                    else "Ticket files not indexed yet. Run rag_sync_index first."
                ),
            }
            return self._tool_result(payload)

        if name == "rag_agent_slices":
            agent_raw = str(arguments.get("agent", "")).strip()
            if not agent_raw:
                return self._tool_error("'agent' is required")
            agent_token = agent_raw[len("agent:") :] if agent_raw.startswith("agent:") else agent_raw
            status_filter = str(arguments.get("status", "")).strip().lower()
            limit = max(1, min(200, int(arguments.get("limit", 40))))

            slice_rows = self._query_sql(
                """
                SELECT subject, predicate, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN ('SLICE_AGENT', 'SLICE_REVIEWER')
                  AND (lower(object) = lower(?) OR lower(object) LIKE lower(?))
                ORDER BY confidence DESC, file_path ASC
                LIMIT ?
                """,
                (f"agent:{agent_token}", f"%{agent_token}%", max(limit * 6, 120)),
            )

            slice_symbols: List[str] = []
            assignment_roles: Dict[str, Set[str]] = {}
            for row in slice_rows:
                symbol = str(row.get("subject") or "").strip()
                if symbol and symbol not in slice_symbols:
                    slice_symbols.append(symbol)
                if symbol:
                    predicate = str(row.get("predicate") or "").upper()
                    role = "primary" if predicate == "SLICE_AGENT" else "reviewer"
                    assignment_roles.setdefault(symbol, set()).add(role)

            items: List[Dict[str, Any]] = []
            status_rank = {
                "in_progress": 0,
                "taken": 1,
                "in_review": 2,
                "ready": 3,
                "planned": 4,
                "waiting_for_push": 5,
                "pending_review": 6,
                "checks_failed": 7,
                "blocked": 8,
                "merged": 9,
                "done": 9,
                "completed": 9,
            }

            for slice_symbol in slice_symbols:
                facts_rows = self._query_sql(
                    """
                    SELECT predicate, object, file_path, line_start, line_end, confidence
                    FROM facts
                    WHERE subject = ?
                      AND predicate IN (
                        'SLICE_ID',
                        'SLICE_STATUS',
                        'SLICE_LANE',
                        'SLICE_BRANCH',
                        'SLICE_WORKTREE',
                        'SLICE_OBJECTIVE',
                        'BELONGS_TO'
                      )
                    ORDER BY confidence DESC, line_start ASC
                    """,
                    (slice_symbol,),
                )

                ticket_symbol = ""
                status = ""
                lane = ""
                branch = ""
                worktree = ""
                objective = ""
                slice_id = ""
                for row in facts_rows:
                    predicate = str(row.get("predicate") or "")
                    obj = str(row.get("object") or "").strip()
                    if not obj:
                        continue
                    if predicate == "BELONGS_TO" and not ticket_symbol:
                        ticket_symbol = obj
                    elif predicate == "SLICE_ID" and not slice_id:
                        slice_id = obj
                    elif predicate == "SLICE_STATUS" and not status:
                        status = obj
                    elif predicate == "SLICE_LANE" and not lane:
                        lane = obj
                    elif predicate == "SLICE_BRANCH" and not branch:
                        branch = obj
                    elif predicate == "SLICE_WORKTREE" and not worktree:
                        worktree = obj
                    elif predicate == "SLICE_OBJECTIVE" and not objective:
                        objective = obj

                if not slice_id and slice_symbol.startswith("slice:") and ":" in slice_symbol:
                    parts = slice_symbol.split(":", 2)
                    if len(parts) == 3:
                        slice_id = parts[2]
                if status_filter and status.lower() != status_filter:
                    continue

                ticket_id = ""
                if ticket_symbol.startswith("ticket:"):
                    ticket_id = ticket_symbol[len("ticket:") :]
                elif slice_symbol.startswith("slice:"):
                    parts = slice_symbol.split(":", 2)
                    if len(parts) == 3:
                        ticket_id = parts[1]

                ticket_status = ""
                if ticket_id:
                    ticket_rows = self._query_sql(
                        """
                        SELECT object
                        FROM facts
                        WHERE subject = ? AND predicate = 'TICKET_STATUS'
                        ORDER BY confidence DESC
                        LIMIT 1
                        """,
                        (f"ticket:{ticket_id}",),
                    )
                    if ticket_rows:
                        ticket_status = str(ticket_rows[0].get("object") or "")

                items.append(
                    {
                        "agent": agent_token,
                        "ticket_id": ticket_id or None,
                        "ticket_symbol": ticket_symbol or (f"ticket:{ticket_id}" if ticket_id else None),
                        "ticket_status": ticket_status or None,
                        "slice_symbol": slice_symbol,
                        "slice_id": slice_id or None,
                        "assignment_roles": sorted(assignment_roles.get(slice_symbol, {"reviewer"})),
                        "slice_status": status or None,
                        "lane": lane or None,
                        "branch": branch or None,
                        "worktree": worktree or None,
                        "objective": objective or None,
                    }
                )

            items.sort(
                key=lambda item: (
                    status_rank.get(str(item.get("slice_status") or "").lower(), 99),
                    str(item.get("ticket_id") or ""),
                    str(item.get("slice_id") or ""),
                )
            )

            payload = {
                "agent": agent_token,
                "status_filter": status_filter or None,
                "count": len(items[:limit]),
                "items": items[:limit],
            }
            return self._tool_result(payload)

        if name == "rag_get_symbol":
            symbol = str(arguments.get("symbol", "")).strip()
            if not symbol:
                return self._tool_error("'symbol' is required")
            limit = int(arguments.get("limit", 20))
            rows = self._query_sql(
                """
                SELECT file_path, line_start, line_end, kind, symbol, module, language, substr(content, 1, 1200) AS excerpt
                FROM chunks
                WHERE symbol LIKE ?
                ORDER BY length(symbol) ASC, file_path ASC
                LIMIT ?
                """,
                (f"%{symbol}%", limit),
            )
            return self._tool_result({"count": len(rows), "rows": rows})

        if name == "rag_search_facts":
            token = str(arguments.get("token", "")).strip()
            predicate = str(arguments.get("predicate", "")).strip().upper()
            subject = str(arguments.get("subject", "")).strip()
            obj = str(arguments.get("object", "")).strip()
            min_conf = float(arguments.get("min_confidence", 0.0))
            limit = max(1, min(300, int(arguments.get("limit", 80))))

            filters: List[str] = []
            params: List[Any] = []
            if predicate:
                filters.append("predicate = ?")
                params.append(predicate)
            if subject:
                filters.append("lower(subject) LIKE lower(?)")
                params.append(f"%{subject}%")
            if obj:
                filters.append("lower(object) LIKE lower(?)")
                params.append(f"%{obj}%")
            if token:
                filters.append("(lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?) OR lower(evidence) LIKE lower(?))")
                params.extend([f"%{token}%", f"%{token}%", f"%{token}%"])
            if min_conf > 0:
                filters.append("confidence >= ?")
                params.append(min_conf)

            where = " AND ".join(filters) if filters else "1=1"
            rows = self._query_sql(
                f"""
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE {where}
                ORDER BY confidence DESC, predicate ASC
                LIMIT ?
                """,
                (*params, limit),
            )
            payload = {
                "query": {
                    "token": token or None,
                    "predicate": predicate or None,
                    "subject": subject or None,
                    "object": obj or None,
                    "min_confidence": min_conf,
                    "limit": limit,
                },
                "count": len(rows),
                "rows": rows,
            }
            return self._tool_result(payload)

        if name == "rag_get_callers":
            symbol = str(arguments.get("symbol", "")).strip()
            if not symbol:
                return self._tool_error("'symbol' is required")
            limit = int(arguments.get("limit", 30))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate = 'CALLS' AND lower(object) LIKE lower(?)
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (f"%{symbol}%", limit),
            )
            return self._tool_result({"count": len(rows), "rows": rows})

        if name == "rag_get_callees":
            symbol = str(arguments.get("symbol", "")).strip()
            if not symbol:
                return self._tool_error("'symbol' is required")
            limit = int(arguments.get("limit", 30))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate = 'CALLS' AND lower(subject) LIKE lower(?)
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (f"%{symbol}%", limit),
            )
            return self._tool_result({"count": len(rows), "rows": rows})

        if name == "rag_dependency_path":
            from_symbol = str(arguments.get("from_symbol", "")).strip()
            to_symbol = str(arguments.get("to_symbol", "")).strip()
            if not from_symbol or not to_symbol:
                return self._tool_error("'from_symbol' and 'to_symbol' are required")
            max_depth = max(1, min(8, int(arguments.get("max_depth", 4))))
            max_expansions = max(100, min(4000, int(arguments.get("max_expansions", 1200))))
            allowed_predicates = self._normalize_predicate_filter(arguments.get("predicates"))
            payload = self._find_dependency_path(
                from_symbol=from_symbol,
                to_symbol=to_symbol,
                max_depth=max_depth,
                allowed_predicates=allowed_predicates,
                max_expansions=max_expansions,
            )
            return self._tool_result(payload)

        if name == "rag_neighbors":
            symbol = str(arguments.get("symbol", "")).strip()
            if not symbol:
                return self._tool_error("'symbol' is required")
            depth = max(1, min(4, int(arguments.get("depth", 2))))
            limit = max(1, min(300, int(arguments.get("limit", 80))))
            min_confidence = max(0.0, min(1.0, float(arguments.get("min_confidence", 0.0))))
            allowed_predicates = self._normalize_predicate_filter(arguments.get("predicates"))

            queue: Deque[Tuple[str, int]] = deque([(symbol, 0)])
            seen_hops: Dict[str, int] = {symbol: 0}
            collected_edges: List[Dict[str, Any]] = []

            while queue and len(collected_edges) < max(200, limit * 8):
                current, hop = queue.popleft()
                if hop >= depth:
                    continue
                rows = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE subject = ? OR object = ?
                    LIMIT 320
                    """,
                    (current, current),
                )
                rows = sorted(
                    rows,
                    key=lambda row: (
                        -self._predicate_priority(str(row.get("predicate") or "")),
                        -float(row.get("confidence") or 0.0),
                    ),
                )
                for row in rows:
                    predicate = str(row.get("predicate") or "").upper()
                    if predicate not in allowed_predicates:
                        continue
                    confidence = float(row.get("confidence") or 0.0)
                    if confidence < min_confidence:
                        continue
                    subject = str(row.get("subject") or "")
                    obj = str(row.get("object") or "")
                    if not subject or not obj:
                        continue
                    if subject == current:
                        neighbor = obj
                        direction = "forward"
                    elif obj == current:
                        neighbor = subject
                        direction = "reverse"
                    else:
                        continue
                    edge = {
                        "from": current,
                        "to": neighbor,
                        "direction": direction,
                        "subject": subject,
                        "predicate": predicate,
                        "object": obj,
                        "file_path": str(row.get("file_path") or ""),
                        "line_start": int(row.get("line_start") or 0),
                        "line_end": int(row.get("line_end") or 0),
                        "confidence": round(confidence, 3),
                        "evidence": str(row.get("evidence") or ""),
                        "hop": hop + 1,
                    }
                    collected_edges.append(edge)
                    if neighbor not in seen_hops or seen_hops[neighbor] > hop + 1:
                        seen_hops[neighbor] = hop + 1
                        queue.append((neighbor, hop + 1))
                    if len(collected_edges) >= max(200, limit * 8):
                        break

            dedup_edges: List[Dict[str, Any]] = []
            seen_edge = set()
            for edge in collected_edges:
                key = (
                    edge["subject"],
                    edge["predicate"],
                    edge["object"],
                    edge["file_path"],
                    edge["line_start"],
                    edge["line_end"],
                )
                if key in seen_edge:
                    continue
                seen_edge.add(key)
                dedup_edges.append(edge)
                if len(dedup_edges) >= limit * 3:
                    break

            predicate_counts: Dict[str, int] = {}
            neighbor_rank: List[Tuple[str, float, int]] = []
            neighbor_score: Dict[str, float] = {}
            for edge in dedup_edges:
                pred = edge["predicate"]
                predicate_counts[pred] = predicate_counts.get(pred, 0) + 1
                node = edge["to"] if edge["from"] == symbol else edge["from"]
                score = float(edge["confidence"]) + (0.05 * max(0, depth - edge["hop"]))
                neighbor_score[node] = max(neighbor_score.get(node, 0.0), score)
            for node, score in neighbor_score.items():
                neighbor_rank.append((node, score, seen_hops.get(node, depth)))
            neighbor_rank.sort(key=lambda item: (-item[1], item[2], item[0]))

            payload = {
                "symbol": symbol,
                "depth": depth,
                "min_confidence": min_confidence,
                "edge_count": len(dedup_edges),
                "neighbor_count": len(neighbor_rank),
                "predicate_counts": dict(sorted(predicate_counts.items(), key=lambda item: (-item[1], item[0]))),
                "neighbors": [
                    {"symbol": node, "score": round(score, 3), "hop": hop}
                    for node, score, hop in neighbor_rank[:limit]
                ],
                "edges": dedup_edges[: limit * 2],
            }
            return self._tool_result(payload)

        if name == "rag_get_route_chain":
            route = str(arguments.get("route", "")).strip()
            method = str(arguments.get("method", "")).strip()
            handler = str(arguments.get("handler", "")).strip()
            limit = int(arguments.get("limit", 30))

            if not route and not handler:
                return self._tool_error("provide either 'route' or 'handler'")

            where_clause = ""
            params: List[Any] = []
            if route:
                _, route_pattern, path_pattern = self._route_search_patterns(route, method)
                where_clause = (
                    "((lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?)) "
                    "OR (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?)))"
                )
                params.extend([route_pattern, route_pattern, path_pattern, path_pattern])
            else:
                where_clause = "(lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))"
                params.extend([f"%{handler}%", f"%{handler}%"])

            rows = self._query_sql(
                f"""
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN (
                    'ROUTE_TO_HANDLER',
                    'HANDLES_ROUTE',
                    'ROUTE_CALLS',
                    'HANDLER_CALLS',
                    'CALLS',
                    'ROUTE_CONTRACT',
                    'ROUTE_ACCEPTS_TYPE',
                    'ROUTE_ACCEPTS_CONTAINS_TYPE',
                    'ROUTE_RETURNS_TYPE',
                    'ROUTE_RETURNS_CONTAINS_TYPE',
                    'ROUTE_IDEMPOTENCY_KEY_TYPE',
                    'IDEMPOTENCY_KEY_TYPE'
                ) AND {where_clause}
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (*params, limit),
            )
            return self._tool_result({"count": len(rows), "rows": rows})

        if name == "rag_trace_route":
            route = str(arguments.get("route", "")).strip()
            if not route:
                return self._tool_error("'route' is required")
            method = str(arguments.get("method", "")).strip()
            limit = int(arguments.get("limit", 30))
            route_token, route_pattern, path_pattern = self._route_search_patterns(route, method)

            route_edges = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN ('ROUTE_TO_HANDLER', 'HANDLES_ROUTE', 'ROUTE_CALLS')
                  AND (
                    (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))
                    OR (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))
                  )
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (route_pattern, route_pattern, path_pattern, path_pattern, max(limit, 20)),
            )

            contract_edges = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN (
                    'ROUTE_CONTRACT',
                    'ROUTE_ACCEPTS_TYPE',
                    'ROUTE_RETURNS_TYPE',
                    'ROUTE_ACCEPTS_CONTAINS_TYPE',
                    'ROUTE_RETURNS_CONTAINS_TYPE',
                    'ROUTE_IDEMPOTENCY_KEY_TYPE'
                )
                  AND (
                    (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))
                    OR (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))
                  )
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (route_pattern, route_pattern, path_pattern, path_pattern, max(limit, 20)),
            )

            handlers: List[str] = []
            for edge in route_edges:
                if edge["predicate"] == "ROUTE_TO_HANDLER":
                    handlers.append(str(edge["object"]))
                elif edge["predicate"] == "HANDLES_ROUTE":
                    handlers.append(str(edge["subject"]))

            dedup_handlers: List[str] = []
            seen_handlers = set()
            for handler_symbol in handlers:
                if not handler_symbol or handler_symbol in seen_handlers:
                    continue
                seen_handlers.add(handler_symbol)
                dedup_handlers.append(handler_symbol)

            service_edges: List[Dict[str, Any]] = []
            security_edges: List[Dict[str, Any]] = []
            lifecycle_edges: List[Dict[str, Any]] = []
            contract_type_edges: List[Dict[str, Any]] = []
            table_edges: List[Dict[str, Any]] = []
            event_edges: List[Dict[str, Any]] = []
            call_chain_edges: List[Dict[str, Any]] = []

            for handler_symbol in dedup_handlers[: max(1, limit)]:
                direct = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE subject = ?
                      AND predicate IN (
                        'HANDLER_CALLS',
                        'CALLS',
                        'SECURED_BY',
                        'TRANSACTIONAL',
                        'RETRY_POLICY',
                        'SCHEDULE_TRIGGER',
                        'ACCEPTS_TYPE',
                        'RETURNS_TYPE',
                        'ACCEPTS_CONTAINS_TYPE',
                        'RETURNS_CONTAINS_TYPE',
                        'IDEMPOTENCY_KEY_TYPE'
                      )
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (handler_symbol, max(limit, 20)),
                )
                for edge in direct:
                    predicate = str(edge["predicate"])
                    if predicate in {"HANDLER_CALLS", "CALLS"}:
                        service_edges.append(edge)
                    elif predicate == "SECURED_BY":
                        security_edges.append(edge)
                    elif predicate in {"ACCEPTS_TYPE", "RETURNS_TYPE", "IDEMPOTENCY_KEY_TYPE"}:
                        contract_type_edges.append(edge)
                    else:
                        lifecycle_edges.append(edge)

            callee_symbols = []
            for edge in service_edges:
                obj = str(edge["object"])
                if obj:
                    callee_symbols.append(obj)

            seen_callees = set()
            dedup_callees: List[str] = []
            for symbol in callee_symbols:
                if symbol in seen_callees:
                    continue
                seen_callees.add(symbol)
                dedup_callees.append(symbol)

            for callee in dedup_callees[: max(1, limit)]:
                downstream = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE subject = ?
                      AND predicate IN (
                        'READS_TABLE',
                        'WRITES_TABLE',
                        'TABLE_DEFINED',
                        'EMITS_EVENT',
                        'LISTENS_EVENT',
                        'CALLS',
                        'HANDLER_CALLS',
                        'ACCEPTS_TYPE',
                        'RETURNS_TYPE',
                        'ACCEPTS_CONTAINS_TYPE',
                        'RETURNS_CONTAINS_TYPE',
                        'IDEMPOTENCY_KEY_TYPE'
                      )
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (callee, max(limit, 20)),
                )
                if not downstream and "#" in callee:
                    class_prefix = callee.split("#", 1)[0]
                    downstream = self._query_sql(
                        """
                        SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                        FROM facts
                        WHERE subject LIKE ?
                          AND predicate IN (
                            'READS_TABLE',
                            'WRITES_TABLE',
                            'TABLE_DEFINED',
                            'EMITS_EVENT',
                            'LISTENS_EVENT',
                            'CALLS',
                            'HANDLER_CALLS',
                            'ACCEPTS_TYPE',
                            'RETURNS_TYPE',
                            'ACCEPTS_CONTAINS_TYPE',
                            'RETURNS_CONTAINS_TYPE',
                            'IDEMPOTENCY_KEY_TYPE'
                          )
                        ORDER BY confidence DESC
                        LIMIT ?
                        """,
                        (f"{class_prefix}#%", max(limit, 20)),
                    )
                for edge in downstream:
                    predicate = str(edge["predicate"])
                    if predicate in {"READS_TABLE", "WRITES_TABLE", "TABLE_DEFINED"}:
                        table_edges.append(edge)
                    elif predicate in {"CALLS", "HANDLER_CALLS"}:
                        call_chain_edges.append(edge)
                    elif predicate in {"ACCEPTS_TYPE", "RETURNS_TYPE", "IDEMPOTENCY_KEY_TYPE"}:
                        contract_type_edges.append(edge)
                    else:
                        event_edges.append(edge)

            second_hop_symbols: List[str] = []
            for edge in call_chain_edges:
                obj = str(edge.get("object") or "")
                if "#" in obj:
                    second_hop_symbols.append(obj)
            seen_second = set()
            dedup_second: List[str] = []
            for symbol in second_hop_symbols:
                if symbol in seen_second:
                    continue
                seen_second.add(symbol)
                dedup_second.append(symbol)

            for callee in dedup_second[: max(1, limit)]:
                downstream = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE subject = ?
                      AND predicate IN (
                        'READS_TABLE',
                        'WRITES_TABLE',
                        'TABLE_DEFINED',
                        'EMITS_EVENT',
                        'LISTENS_EVENT',
                        'ACCEPTS_TYPE',
                        'RETURNS_TYPE',
                        'ACCEPTS_CONTAINS_TYPE',
                        'RETURNS_CONTAINS_TYPE',
                        'IDEMPOTENCY_KEY_TYPE'
                      )
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (callee, max(limit, 20)),
                )
                if not downstream and "#" in callee:
                    class_prefix = callee.split("#", 1)[0]
                    downstream = self._query_sql(
                        """
                        SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                        FROM facts
                        WHERE subject LIKE ?
                          AND predicate IN (
                            'READS_TABLE',
                            'WRITES_TABLE',
                            'TABLE_DEFINED',
                            'EMITS_EVENT',
                            'LISTENS_EVENT',
                            'ACCEPTS_TYPE',
                            'RETURNS_TYPE',
                            'ACCEPTS_CONTAINS_TYPE',
                            'RETURNS_CONTAINS_TYPE',
                            'IDEMPOTENCY_KEY_TYPE'
                          )
                        ORDER BY confidence DESC
                        LIMIT ?
                        """,
                        (f"{class_prefix}#%", max(limit, 20)),
                    )
                for edge in downstream:
                    predicate = str(edge["predicate"])
                    if predicate in {"READS_TABLE", "WRITES_TABLE", "TABLE_DEFINED"}:
                        table_edges.append(edge)
                    elif predicate in {"ACCEPTS_TYPE", "RETURNS_TYPE", "IDEMPOTENCY_KEY_TYPE"}:
                        contract_type_edges.append(edge)
                    else:
                        event_edges.append(edge)

            def _dedup_edges(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
                out: List[Dict[str, Any]] = []
                seen = set()
                for row in rows:
                    key = (
                        row.get("subject"),
                        row.get("predicate"),
                        row.get("object"),
                        row.get("file_path"),
                        row.get("line_start"),
                        row.get("line_end"),
                    )
                    if key in seen:
                        continue
                    seen.add(key)
                    out.append(row)
                return out[:limit]

            payload = {
                "route": route,
                "method": method or None,
                "route_token": route_token,
                "handlers": dedup_handlers[:limit],
                "route_edges": _dedup_edges(route_edges),
                "contracts": _dedup_edges(contract_edges),
                "type_contracts": _dedup_edges(contract_type_edges),
                "handler_calls": _dedup_edges(service_edges),
                "call_chain": _dedup_edges(call_chain_edges),
                "security": _dedup_edges(security_edges),
                "lifecycle": _dedup_edges(lifecycle_edges),
                "tables": _dedup_edges(table_edges),
                "events": _dedup_edges(event_edges),
            }
            return self._tool_result(payload)

        if name == "rag_route_conflicts":
            route = str(arguments.get("route", "")).strip()
            method = str(arguments.get("method", "")).strip()
            limit = max(1, min(200, int(arguments.get("limit", 80))))

            where = ""
            params: List[Any] = []
            if route:
                _, route_pattern, path_pattern = self._route_search_patterns(route, method)
                where = (
                    "AND ((lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?)) "
                    "OR (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?)))"
                )
                params.extend([route_pattern, route_pattern, path_pattern, path_pattern])

            route_handler_rows = self._query_sql(
                f"""
                SELECT subject AS route_symbol, object AS handler_symbol, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate = 'ROUTE_TO_HANDLER' {where}
                ORDER BY subject ASC, confidence DESC
                LIMIT ?
                """,
                (*params, limit * 20),
            )
            route_contract_rows = self._query_sql(
                f"""
                SELECT subject AS route_symbol, object AS contract_symbol, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate = 'ROUTE_CONTRACT' {where}
                ORDER BY subject ASC, confidence DESC
                LIMIT ?
                """,
                (*params, limit * 20),
            )

            handlers_by_route: Dict[str, Set[str]] = {}
            contracts_by_route: Dict[str, Set[str]] = {}
            for row in route_handler_rows:
                route_symbol = str(row.get("route_symbol") or "")
                handler_symbol = str(row.get("handler_symbol") or "")
                if not route_symbol or not handler_symbol:
                    continue
                handlers_by_route.setdefault(route_symbol, set()).add(handler_symbol)
            for row in route_contract_rows:
                route_symbol = str(row.get("route_symbol") or "")
                contract_symbol = str(row.get("contract_symbol") or "")
                if not route_symbol or not contract_symbol:
                    continue
                contracts_by_route.setdefault(route_symbol, set()).add(contract_symbol)

            conflicts: List[Dict[str, Any]] = []
            route_keys = sorted(set(handlers_by_route.keys()) | set(contracts_by_route.keys()))
            for route_symbol in route_keys:
                handlers = sorted(handlers_by_route.get(route_symbol, set()))
                contracts = sorted(contracts_by_route.get(route_symbol, set()))
                if len(handlers) <= 1 and len(contracts) <= 1:
                    continue
                conflict_type = []
                if len(handlers) > 1:
                    conflict_type.append("multi_handler")
                if len(contracts) > 1:
                    conflict_type.append("multi_contract")
                conflicts.append(
                    {
                        "route_symbol": route_symbol,
                        "conflict_type": conflict_type,
                        "handler_count": len(handlers),
                        "contract_count": len(contracts),
                        "handlers": handlers,
                        "contracts": contracts,
                    }
                )
                if len(conflicts) >= limit:
                    break

            payload = {
                "route_filter": route or None,
                "method_filter": method or None,
                "conflict_count": len(conflicts),
                "conflicts": conflicts,
            }
            return self._tool_result(payload)

        if name == "rag_get_type_usage":
            type_name = str(arguments.get("type", "")).strip()
            if not type_name:
                return self._tool_error("'type' is required")
            normalized = type_name if type_name.lower().startswith("type:") else f"type:{type_name}"
            token_hint = type_name.split(".")[-1].split(":")[-1]
            limit = int(arguments.get("limit", 80))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN (
                    'ACCEPTS_TYPE',
                    'RETURNS_TYPE',
                    'ROUTE_ACCEPTS_TYPE',
                    'ROUTE_RETURNS_TYPE',
                    'ACCEPTS_CONTAINS_TYPE',
                    'RETURNS_CONTAINS_TYPE',
                    'ROUTE_ACCEPTS_CONTAINS_TYPE',
                    'ROUTE_RETURNS_CONTAINS_TYPE',
                    'IDEMPOTENCY_KEY_TYPE',
                    'ROUTE_IDEMPOTENCY_KEY_TYPE'
                )
                  AND (
                    object = ?
                    OR lower(object) LIKE lower(?)
                    OR lower(object) LIKE lower(?)
                    OR lower(subject) LIKE lower(?)
                  )
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (normalized, f"%{normalized}%", f"%{token_hint}%", f"%{type_name}%", limit),
            )
            return self._tool_result({"type": normalized, "count": len(rows), "rows": rows})

        if name == "rag_idempotency_mismatches":
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            module = str(arguments.get("module", "")).strip().lower()
            limit = max(1, min(200, int(arguments.get("limit", 80))))

            filters = ["finding_type = 'IDEMPOTENCY_KEY_TYPE_MISMATCH'"]
            params: List[Any] = []
            if symbol:
                filters.append("(symbol = ? OR lower(symbol) LIKE lower(?))")
                params.extend([symbol, f"%{symbol}%"])
            if file_path:
                filters.append("file_path = ?")
                params.append(file_path)
            if module:
                filters.append("lower(file_path) LIKE lower(?)")
                params.append(f"%/modules/{module}/%")
            where_clause = " AND ".join(filters)
            findings_rows = self._query_sql(
                f"""
                SELECT finding_type, severity, title, message, file_path, line_start, line_end, symbol, source, metadata_json
                FROM findings
                WHERE {where_clause}
                ORDER BY
                  CASE severity
                    WHEN 'critical' THEN 0
                    WHEN 'high' THEN 1
                    WHEN 'medium' THEN 2
                    ELSE 3
                  END ASC,
                  file_path ASC,
                  line_start ASC
                LIMIT ?
                """,
                (*params, limit),
            )
            findings = [self._normalize_finding_row(row) for row in findings_rows]

            contract_rows: List[Dict[str, Any]] = []
            if symbol:
                symbol_like = f"{symbol.split('#', 1)[0]}#%" if "#" in symbol else f"%{symbol}%"
                contract_rows = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE predicate IN (
                        'IDEMPOTENCY_KEY_TYPE',
                        'IDEMPOTENCY_KEY_PARAM',
                        'ROUTE_IDEMPOTENCY_KEY_TYPE',
                        'CALLS',
                        'HANDLER_CALLS',
                        'ROUTE_CALLS'
                    )
                      AND (
                        subject = ?
                        OR lower(subject) LIKE lower(?)
                        OR lower(object) LIKE lower(?)
                      )
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (symbol, symbol_like, f"%{symbol}%", max(30, min(400, limit * 4))),
                )

            payload = {
                "query": {
                    "symbol": symbol or None,
                    "file_path": file_path or None,
                    "module": module or None,
                    "limit": limit,
                },
                "count": len(findings),
                "findings": findings,
                "contracts": contract_rows[: max(20, min(200, limit * 2))],
            }
            return self._tool_result(payload)

        if name == "rag_related_tests":
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            module = str(arguments.get("module") or "").strip().lower()
            limit = max(1, min(120, int(arguments.get("limit", 30))))
            if not symbol and not file_path and not module:
                return self._tool_error("provide at least one of 'symbol', 'file_path', or 'module'")

            test_symbols: Set[str] = set()

            if symbol:
                symbol_candidates = [symbol]
                if "#" in symbol:
                    symbol_candidates.append(symbol.split("#", 1)[0])
                dedup_candidates = list(dict.fromkeys(candidate for candidate in symbol_candidates if candidate))
                for candidate in dedup_candidates:
                    rows = self._query_sql(
                        """
                        SELECT DISTINCT subject
                        FROM facts
                        WHERE predicate = 'TEST_COVERS'
                          AND (object = ? OR lower(object) LIKE lower(?))
                        LIMIT ?
                        """,
                        (candidate, f"%{candidate}%", limit * 8),
                    )
                    test_symbols.update(str(row["subject"]) for row in rows if row.get("subject"))

            if file_path:
                rows = self._query_sql(
                    """
                    SELECT DISTINCT f.subject
                    FROM facts f
                    JOIN chunks c ON c.symbol = f.object
                    WHERE f.predicate = 'TEST_COVERS'
                      AND c.file_path = ?
                    LIMIT ?
                    """,
                    (file_path, limit * 8),
                )
                test_symbols.update(str(row["subject"]) for row in rows if row.get("subject"))

            if module:
                module_symbol = module if module.startswith("module:") else f"module:{module}"
                rows = self._query_sql(
                    """
                    SELECT DISTINCT subject
                    FROM facts
                    WHERE predicate = 'TEST_MODULE' AND lower(object) = lower(?)
                    LIMIT ?
                    """,
                    (module_symbol, limit * 8),
                )
                test_symbols.update(str(row["subject"]) for row in rows if row.get("subject"))

            ranked_tests = sorted(test_symbols)[: limit * 3]
            tests: List[Dict[str, Any]] = []
            for test_symbol in ranked_tests:
                fact_rows = self._query_sql(
                    """
                    SELECT predicate, object, confidence
                    FROM facts
                    WHERE subject = ?
                      AND predicate IN ('TEST_MODULE', 'TEST_CLASS', 'GATE_MEMBER', 'TAGGED_WITH', 'CROSS_MODULE_CRITICAL')
                    ORDER BY confidence DESC
                    """,
                    (test_symbol,),
                )
                cover_rows = self._query_sql(
                    """
                    SELECT object, confidence, file_path, line_start, line_end
                    FROM facts
                    WHERE subject = ? AND predicate = 'TEST_COVERS'
                    ORDER BY confidence DESC
                    LIMIT 20
                    """,
                    (test_symbol,),
                )
                test_file = test_symbol[len("test:") :] if test_symbol.startswith("test:") else test_symbol
                tests.append(
                    {
                        "test_symbol": test_symbol,
                        "test_file": test_file,
                        "metadata": fact_rows,
                        "covers": cover_rows,
                    }
                )

            tests = tests[:limit]
            payload = {
                "symbol": symbol or None,
                "file_path": file_path or None,
                "module": module or None,
                "count": len(tests),
                "tests": tests,
            }
            return self._tool_result(payload)

        if name == "rag_preflight_change":
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            route = str(arguments.get("route", "")).strip()
            method = str(arguments.get("method", "")).strip()
            module = str(arguments.get("module") or "").strip().lower()
            depth = max(1, min(5, int(arguments.get("depth", 2))))
            top_k = max(5, min(100, int(arguments.get("top_k", 25))))

            if not symbol and not file_path and not route and not module:
                return self._tool_error("provide at least one of symbol/file_path/route/module")

            impact_payload: Dict[str, Any] = {}
            if symbol or file_path:
                impact_args: List[str] = ["--json", "--depth", str(depth), "--top-k", str(top_k)]
                if symbol:
                    impact_args.extend(["--symbol", symbol])
                if file_path:
                    impact_args.extend(["--file-path", file_path])
                impact_payload = self._run_script("impact.py", impact_args)

            route_trace_payload: Dict[str, Any] = {}
            if route:
                trace_result = self.call_tool(
                    "rag_trace_route",
                    {"route": route, "method": method, "limit": top_k},
                )
                if isinstance(trace_result, dict):
                    route_trace_payload = dict(trace_result.get("structuredContent") or {})

            related_module = module
            if not related_module and impact_payload.get("impacted_modules"):
                impacted_modules = impact_payload.get("impacted_modules")
                if isinstance(impacted_modules, list) and impacted_modules:
                    related_module = str(impacted_modules[0]).strip().lower()

            tests_result = self.call_tool(
                "rag_related_tests",
                {
                    "symbol": symbol,
                    "file_path": file_path,
                    "module": related_module,
                    "limit": top_k,
                },
            )
            related_tests_payload = (
                dict(tests_result.get("structuredContent") or {})
                if isinstance(tests_result, dict)
                else {}
            )

            summary = {
                "has_impact": bool(impact_payload),
                "impact_edges": int(impact_payload.get("edge_count") or 0),
                "impacted_modules": len(impact_payload.get("impacted_modules") or []),
                "impacted_files": len(impact_payload.get("impacted_files") or []),
                "route_handlers": len(route_trace_payload.get("handlers") or []),
                "route_call_edges": len(route_trace_payload.get("handler_calls") or []),
                "route_tables": len(route_trace_payload.get("tables") or []),
                "related_tests": int(related_tests_payload.get("count") or 0),
            }

            payload = {
                "input": {
                    "symbol": symbol or None,
                    "file_path": file_path or None,
                    "route": route or None,
                    "method": method or None,
                    "module": module or related_module or None,
                    "depth": depth,
                    "top_k": top_k,
                },
                "summary": summary,
                "impact": impact_payload or None,
                "route_trace": route_trace_payload or None,
                "related_tests": related_tests_payload or None,
            }
            return self._tool_result(payload)

        if name == "rag_dedupe_resolve":
            concept = str(arguments.get("concept") or "").strip()
            symbol_query = str(arguments.get("symbol") or "").strip()
            if not concept and not symbol_query:
                return self._tool_error("provide at least one of 'concept' or 'symbol'")

            limit = max(2, min(40, int(arguments.get("limit", 12))))
            requested_revision = self._normalize_revision(arguments.get("revision", ""))
            allow_revision_mismatch = bool(arguments.get("allow_revision_mismatch", False))
            indexed_commit = self._indexed_commit()
            revision_match = self._revision_matches(indexed_commit, requested_revision)
            if requested_revision and not revision_match and not allow_revision_mismatch:
                return self._tool_error(
                    f"index revision mismatch: indexed={indexed_commit or 'unknown'} requested={requested_revision}"
                )

            def _tokenize(value: str) -> List[str]:
                raw = re.findall(r"[A-Za-z0-9_.#/-]+", value)
                tokens: List[str] = []
                seen: Set[str] = set()
                for token in raw:
                    clean = token.strip()
                    if not clean:
                        continue
                    lowered = clean.lower()
                    if lowered in seen:
                        continue
                    seen.add(lowered)
                    tokens.append(clean)
                return tokens

            query_tokens: List[str] = []
            query_seen: Set[str] = set()
            if symbol_query:
                for token in [symbol_query, symbol_query.split("#", 1)[0], symbol_query.split(".")[-1]]:
                    t = str(token or "").strip()
                    if t and t.lower() not in query_seen:
                        query_seen.add(t.lower())
                        query_tokens.append(t)
            if concept:
                for token in _tokenize(concept):
                    if len(token) < 3:
                        continue
                    lowered = token.lower()
                    if lowered not in query_seen:
                        query_seen.add(lowered)
                        query_tokens.append(token)

            if not query_tokens:
                return self._tool_error("no usable concept/symbol tokens")

            def _match_terms(tokens: Sequence[str]) -> List[str]:
                stopwords = {
                    "com",
                    "org",
                    "net",
                    "io",
                    "www",
                    "erp",
                    "api",
                    "core",
                    "main",
                    "src",
                    "java",
                    "class",
                    "impl",
                    "service",
                    "controller",
                    "module",
                    "modules",
                }
                terms: List[str] = []
                seen_terms: Set[str] = set()
                for token in tokens:
                    for part in re.split(r"[.#/_:\\-]+", token.lower()):
                        clean = part.strip()
                        if not clean:
                            continue
                        if clean in stopwords:
                            continue
                        if len(clean) < 3:
                            continue
                        if clean in seen_terms:
                            continue
                        seen_terms.add(clean)
                        terms.append(clean)
                return terms

            query_match_terms = _match_terms(query_tokens)
            if not query_match_terms:
                query_match_terms = [token.lower() for token in query_tokens if len(token) >= 3][:6]

            token_clauses: List[str] = []
            token_params: List[Any] = []
            for token in query_tokens[:8]:
                token_clauses.append(
                    "(lower(symbol) LIKE lower(?) OR lower(file_path) LIKE lower(?) OR lower(content) LIKE lower(?))"
                )
                like = f"%{token}%"
                token_params.extend([like, like, like])

            rows = self._query_sql(
                f"""
                SELECT
                  symbol,
                  file_path,
                  kind,
                  module,
                  MIN(line_start) AS line_start,
                  MAX(line_end) AS line_end,
                  COUNT(*) AS chunk_count
                FROM chunks
                WHERE symbol IS NOT NULL
                  AND symbol != ''
                  AND kind NOT LIKE 'doc_%'
                  AND kind != 'text_block'
                  AND ({' OR '.join(token_clauses)})
                GROUP BY symbol, file_path, kind, module
                ORDER BY
                  CASE kind
                    WHEN 'java_route_handler' THEN 0
                    WHEN 'openapi_operation' THEN 1
                    WHEN 'java_method' THEN 2
                    WHEN 'java_class' THEN 3
                    WHEN 'java_method_decl' THEN 4
                    WHEN 'java_repository_method_decl' THEN 5
                    ELSE 9
                  END ASC,
                  chunk_count DESC,
                  file_path ASC
                LIMIT ?
                """,
                (*token_params, max(30, limit * 8)),
            )

            if not rows:
                return self._tool_result(
                    {
                        "concept": concept or None,
                        "symbol": symbol_query or None,
                        "revision": {
                            "requested": requested_revision or None,
                            "indexed": indexed_commit or None,
                            "matches_requested": revision_match if requested_revision else None,
                            "enforced": bool(requested_revision and not allow_revision_mismatch),
                        },
                        "count": 0,
                        "canonical": None,
                        "contenders": [],
                        "message": "No duplicate candidates found for concept/symbol.",
                    }
                )

            candidate_symbols: List[str] = []
            for row in rows:
                symbol_value = str(row.get("symbol") or "").strip()
                if symbol_value and symbol_value not in candidate_symbols:
                    candidate_symbols.append(symbol_value)
            sample_text_by_symbol: Dict[str, str] = {}
            if candidate_symbols:
                placeholders = ",".join("?" for _ in candidate_symbols[:500])
                sample_rows = self._query_sql(
                    f"""
                    SELECT symbol, GROUP_CONCAT(substr(content, 1, 420), '\n') AS sample_text
                    FROM chunks
                    WHERE symbol IN ({placeholders})
                    GROUP BY symbol
                    """,
                    tuple(candidate_symbols[:500]),
                )
                for row in sample_rows:
                    symbol_value = str(row.get("symbol") or "").strip()
                    if not symbol_value:
                        continue
                    sample_text_by_symbol[symbol_value] = str(row.get("sample_text") or "")

            logic_fingerprint_by_symbol: Dict[str, str] = {}
            logic_cluster_size: Dict[str, int] = {}
            if candidate_symbols:
                placeholders = ",".join("?" for _ in candidate_symbols[:500])
                logic_rows = self._query_sql(
                    f"""
                    SELECT subject, object AS logic_fingerprint
                    FROM facts
                    WHERE predicate = 'LOGIC_FINGERPRINT'
                      AND subject IN ({placeholders})
                    """,
                    tuple(candidate_symbols[:500]),
                )
                fingerprints: List[str] = []
                for row in logic_rows:
                    subject = str(row.get("subject") or "").strip()
                    fingerprint = str(row.get("logic_fingerprint") or "").strip()
                    if not subject or not fingerprint:
                        continue
                    logic_fingerprint_by_symbol[subject] = fingerprint
                    if fingerprint not in fingerprints:
                        fingerprints.append(fingerprint)
                if fingerprints:
                    placeholders = ",".join("?" for _ in fingerprints[:500])
                    cluster_rows = self._query_sql(
                        f"""
                        SELECT object AS logic_fingerprint, COUNT(DISTINCT subject) AS cluster_size
                        FROM facts
                        WHERE predicate = 'LOGIC_FINGERPRINT'
                          AND object IN ({placeholders})
                        GROUP BY object
                        """,
                        tuple(fingerprints[:500]),
                    )
                    for row in cluster_rows:
                        fingerprint = str(row.get("logic_fingerprint") or "").strip()
                        if not fingerprint:
                            continue
                        logic_cluster_size[fingerprint] = int(row.get("cluster_size") or 0)

            evaluated: List[Dict[str, Any]] = []
            for row in rows:
                symbol = str(row.get("symbol") or "").strip()
                file_path = str(row.get("file_path") or "").strip()
                kind = str(row.get("kind") or "")
                module = str(row.get("module") or "root")
                line_start = int(row.get("line_start") or 0)
                line_end = int(row.get("line_end") or 0)
                chunk_count = int(row.get("chunk_count") or 0)
                if not symbol:
                    continue

                class_prefix = symbol.split("#", 1)[0]
                symbol_like = f"{class_prefix}#%"
                lowered_path = file_path.lower()
                lowered_symbol = symbol.lower()
                legacy_hint = any(tag in lowered_path or tag in lowered_symbol for tag in ("legacy", "deprecated", "archive", "/old/", "_old", "backup"))
                sample_text = sample_text_by_symbol.get(symbol, "")
                query_haystack = f"{symbol}\n{file_path}\n{kind}\n{sample_text}".lower()
                query_match_hits = sum(1 for token in query_match_terms if token in query_haystack)
                query_match_coverage = (
                    float(query_match_hits) / float(len(query_match_terms))
                    if query_match_terms
                    else 0.0
                )
                exact_symbol_match = bool(symbol_query and lowered_symbol == symbol_query.lower())
                if symbol_query and not exact_symbol_match:
                    symbol_root = symbol_query.split("#", 1)[0].strip().lower()
                    exact_symbol_match = bool(symbol_root and lowered_symbol == symbol_root)
                if concept and len(query_match_terms) >= 2 and query_match_hits == 0:
                    continue

                entry_rows = self._query_sql(
                    """
                    SELECT predicate, subject, object, confidence
                    FROM facts
                    WHERE (
                        predicate = 'ROUTE_TO_HANDLER'
                        AND (object = ? OR object LIKE ?)
                    ) OR (
                        predicate = 'HANDLES_ROUTE'
                        AND (subject = ? OR subject LIKE ?)
                    )
                    ORDER BY confidence DESC
                    LIMIT 60
                    """,
                    (symbol, symbol_like, symbol, symbol_like),
                )
                entrypoint_routes: List[str] = []
                for edge in entry_rows:
                    predicate = str(edge.get("predicate") or "")
                    if predicate == "ROUTE_TO_HANDLER":
                        route_symbol = str(edge.get("subject") or "")
                    else:
                        route_symbol = str(edge.get("object") or "")
                    if route_symbol.startswith("route:") and route_symbol not in entrypoint_routes:
                        entrypoint_routes.append(route_symbol)

                reference_rows = self._query_sql(
                    """
                    SELECT COUNT(*) AS c
                    FROM facts
                    WHERE predicate IN ('CALLS', 'HANDLER_CALLS', 'ROUTE_CALLS', 'ROUTE_TO_HANDLER')
                      AND (object = ? OR object LIKE ?)
                    """,
                    (symbol, symbol_like),
                )
                reference_count = int(reference_rows[0].get("c") or 0) if reference_rows else 0

                usage_rows = self._query_sql(
                    """
                    SELECT COUNT(*) AS c
                    FROM facts
                    WHERE predicate IN (
                      'CALLS',
                      'HANDLER_CALLS',
                      'ROUTE_CALLS',
                      'READS_TABLE',
                      'WRITES_TABLE',
                      'EMITS_EVENT',
                      'LISTENS_EVENT',
                      'SECURED_BY',
                      'TRANSACTIONAL'
                    )
                      AND (subject = ? OR subject LIKE ? OR object = ? OR object LIKE ?)
                    """,
                    (symbol, symbol_like, symbol, symbol_like),
                )
                usage_count = int(usage_rows[0].get("c") or 0) if usage_rows else 0

                first_hop_rows = self._query_sql(
                    """
                    SELECT COUNT(*) AS c
                    FROM facts f
                    JOIN facts h
                      ON h.predicate = 'ROUTE_TO_HANDLER'
                     AND f.subject = h.object
                    WHERE f.predicate IN ('HANDLER_CALLS', 'CALLS')
                      AND (f.object = ? OR f.object LIKE ?)
                    """,
                    (symbol, symbol_like),
                )
                first_hop_count = int(first_hop_rows[0].get("c") or 0) if first_hop_rows else 0

                second_hop_rows = self._query_sql(
                    """
                    SELECT COUNT(*) AS c
                    FROM facts h
                    JOIN facts f1
                      ON h.predicate = 'ROUTE_TO_HANDLER'
                     AND f1.subject = h.object
                     AND f1.predicate IN ('HANDLER_CALLS', 'CALLS')
                    JOIN facts f2
                      ON f2.subject = f1.object
                     AND f2.predicate IN ('HANDLER_CALLS', 'CALLS')
                    WHERE (f2.object = ? OR f2.object LIKE ?)
                    """,
                    (symbol, symbol_like),
                )
                second_hop_count = int(second_hop_rows[0].get("c") or 0) if second_hop_rows else 0

                if entrypoint_routes:
                    route_distance = 0
                elif first_hop_count > 0:
                    route_distance = 1
                elif second_hop_count > 0:
                    route_distance = 2
                else:
                    route_distance = 4

                recency_epoch = self._git_file_last_modified_epoch(file_path)
                v2_bonus = 1 if ("/v2/" in lowered_path or ".v2." in lowered_symbol) else 0
                deprecation_penalty = 1 if legacy_hint else 0
                logic_fingerprint = logic_fingerprint_by_symbol.get(symbol, "")
                logic_cluster = int(logic_cluster_size.get(logic_fingerprint, 0)) if logic_fingerprint else 0

                evaluated.append(
                    {
                        "symbol": symbol,
                        "file_path": file_path,
                        "line_start": line_start,
                        "line_end": line_end,
                        "kind": kind,
                        "module": module,
                        "chunk_count": chunk_count,
                        "entrypoint_route_count": len(entrypoint_routes),
                        "entrypoint_routes": entrypoint_routes[:8],
                        "reference_count": reference_count,
                        "usage_count": usage_count,
                        "first_hop_count": first_hop_count,
                        "second_hop_count": second_hop_count,
                        "route_distance": route_distance,
                        "recency_epoch": recency_epoch,
                        "v2_bonus": v2_bonus,
                        "legacy_hint": legacy_hint,
                        "deprecation_penalty": deprecation_penalty,
                        "query_match_hits": query_match_hits,
                        "query_match_coverage": round(query_match_coverage, 4),
                        "exact_symbol_match": exact_symbol_match,
                        "logic_fingerprint": logic_fingerprint,
                        "logic_cluster_size": logic_cluster,
                    }
                )

            if not evaluated:
                return self._tool_result(
                    {
                        "concept": concept or None,
                        "symbol": symbol_query or None,
                        "revision": {
                            "requested": requested_revision or None,
                            "indexed": indexed_commit or None,
                            "matches_requested": revision_match if requested_revision else None,
                            "enforced": bool(requested_revision and not allow_revision_mismatch),
                        },
                        "count": 0,
                        "canonical": None,
                        "contenders": [],
                        "message": "No code candidates resolved after filtering.",
                    }
                )

            recency_values = [int(item.get("recency_epoch") or 0) for item in evaluated if int(item.get("recency_epoch") or 0) > 0]
            min_recency = min(recency_values) if recency_values else 0
            max_recency = max(recency_values) if recency_values else 0

            for item in evaluated:
                recency_epoch = int(item.get("recency_epoch") or 0)
                if max_recency > min_recency and recency_epoch > 0:
                    recency_score = (recency_epoch - min_recency) / (max_recency - min_recency)
                else:
                    recency_score = 0.0

                distance = int(item.get("route_distance") or 4)
                if distance == 0:
                    proximity_score = 1.0
                elif distance == 1:
                    proximity_score = 0.75
                elif distance == 2:
                    proximity_score = 0.45
                else:
                    proximity_score = 0.08

                reference_score = min(1.0, float(item.get("reference_count") or 0) / 40.0)
                usage_score = min(1.0, float(item.get("usage_count") or 0) / 60.0)
                namespace_score = 0.15 if int(item.get("v2_bonus") or 0) > 0 else 0.0
                legacy_penalty = 0.25 if bool(item.get("legacy_hint")) else 0.0
                chunk_score = min(0.25, float(item.get("chunk_count") or 0) / 30.0)
                query_match_score = float(item.get("query_match_coverage") or 0.0)
                exact_symbol_boost = 0.6 if bool(item.get("exact_symbol_match")) else 0.0
                logic_cluster_size = int(item.get("logic_cluster_size") or 0)
                logic_cluster_bonus = (
                    min(0.5, 0.12 * float(max(0, logic_cluster_size - 1)))
                    if logic_cluster_size > 1 and distance <= 2
                    else 0.0
                )

                total_score = (
                    2.2 * proximity_score
                    + 1.2 * reference_score
                    + 0.8 * usage_score
                    + 0.8 * recency_score
                    + 1.6 * query_match_score
                    + namespace_score
                    + chunk_score
                    + exact_symbol_boost
                    + logic_cluster_bonus
                    - legacy_penalty
                )
                item["score"] = round(total_score, 4)
                item["score_breakdown"] = {
                    "proximity": round(proximity_score, 3),
                    "references": round(reference_score, 3),
                    "usage": round(usage_score, 3),
                    "recency": round(recency_score, 3),
                    "query_match": round(query_match_score, 3),
                    "exact_symbol_boost": round(exact_symbol_boost, 3),
                    "logic_cluster_bonus": round(logic_cluster_bonus, 3),
                    "namespace_bonus": round(namespace_score, 3),
                    "chunk_score": round(chunk_score, 3),
                    "legacy_penalty": round(legacy_penalty, 3),
                }

                reasons: List[str] = []
                if bool(item.get("exact_symbol_match")):
                    reasons.append("exact_symbol_match")
                if float(item.get("query_match_coverage") or 0.0) >= 0.6:
                    reasons.append("high_query_match")
                if int(item.get("entrypoint_route_count") or 0) > 0:
                    reasons.append("reachable_from_entrypoints")
                if int(item.get("first_hop_count") or 0) > 0:
                    reasons.append("called_directly_by_handler")
                if int(item.get("second_hop_count") or 0) > 0:
                    reasons.append("reachable_within_two_hops")
                if int(item.get("reference_count") or 0) >= 10:
                    reasons.append("high_reference_count")
                if int(item.get("v2_bonus") or 0) > 0:
                    reasons.append("v2_namespace")
                if int(item.get("logic_cluster_size") or 0) > 1:
                    reasons.append("shared_logic_cluster")
                if bool(item.get("legacy_hint")):
                    reasons.append("legacy_or_deprecated_hint")
                item["reasons"] = reasons

            ranked = sorted(
                evaluated,
                key=lambda item: (
                    float(item.get("score") or 0.0),
                    float(item.get("query_match_coverage") or 0.0),
                    int(item.get("entrypoint_route_count") or 0),
                    int(item.get("reference_count") or 0),
                    int(item.get("recency_epoch") or 0),
                ),
                reverse=True,
            )

            canonical = ranked[0]
            contenders = ranked[:limit]
            canonical_symbol = str(canonical.get("symbol") or "")
            canonical_file_path = str(canonical.get("file_path") or "")

            legacy_reachable = [
                item
                for item in contenders
                if bool(item.get("legacy_hint")) and int(item.get("route_distance") or 4) <= 2
            ]
            non_canonical_reachable = [
                item
                for item in contenders[1:]
                if int(item.get("route_distance") or 4) <= 2
            ]

            safe_edit_location = {
                "symbol": canonical_symbol or None,
                "file_path": canonical_file_path or None,
                "line_start": int(canonical.get("line_start") or 0),
                "line_end": int(canonical.get("line_end") or 0),
            }

            payload = {
                "concept": concept or None,
                "symbol": symbol_query or None,
                "revision": {
                    "requested": requested_revision or None,
                    "indexed": indexed_commit or None,
                    "matches_requested": revision_match if requested_revision else None,
                    "enforced": bool(requested_revision and not allow_revision_mismatch),
                },
                "count": len(contenders),
                "canonical": canonical,
                "safe_edit_location": safe_edit_location,
                "contenders": contenders,
                "reachable_legacy_contenders": legacy_reachable,
                "non_canonical_reachable": non_canonical_reachable,
                "message": (
                    "Canonical implementation resolved from duplicate candidates."
                    if len(contenders) > 1
                    else "Single primary implementation found."
                ),
            }
            return self._tool_result(payload)

        if name == "rag_patch_guard":
            change_summary = str(arguments.get("change_summary", "")).strip()
            module_input = str(arguments.get("module", "")).strip().lower()
            auto_detect_changes = bool(arguments.get("auto_detect_changes", True))
            ensure_fresh = bool(arguments.get("ensure_fresh", True))
            sync_strategy = str(arguments.get("sync_strategy", "auto")).strip().lower() or "auto"
            refresh_findings = bool(arguments.get("refresh_findings", True))
            strict = bool(arguments.get("strict", True))
            revision = self._normalize_revision(arguments.get("revision", ""))
            allow_revision_mismatch = bool(arguments.get("allow_revision_mismatch", False))
            depth = max(1, min(5, int(arguments.get("depth", 2))))
            top_k = max(5, min(160, int(arguments.get("top_k", 30))))
            max_changed_files = max(1, min(200, int(arguments.get("max_changed_files", 30))))
            max_symbols = max(1, min(300, int(arguments.get("max_symbols", 60))))
            max_scope_items = max(1, min(12, int(arguments.get("max_scope_items", 4))))
            run_release_gate = bool(arguments.get("run_release_gate", True))
            include_full_payloads = bool(arguments.get("include_full_payloads", False))
            require_tests = bool(arguments.get("require_tests", True))
            min_related_tests = max(0, int(arguments.get("min_related_tests", 1)))
            fail_on_stale_index = bool(arguments.get("fail_on_stale_index", True))
            max_critical_findings = max(0, int(arguments.get("max_critical_findings", 0)))
            max_high_findings = max(0, int(arguments.get("max_high_findings", 2)))
            max_route_conflicts = max(0, int(arguments.get("max_route_conflicts", 0)))
            max_config_conflicts = max(0, int(arguments.get("max_config_conflicts", 20)))
            max_per_file = max(1, int(arguments.get("max_per_file", 2)))
            max_per_module = max(1, int(arguments.get("max_per_module", 4)))
            min_module_diversity = max(1, int(arguments.get("min_module_diversity", 2)))
            min_file_diversity = max(1, int(arguments.get("min_file_diversity", 3)))
            min_code_evidence = max(1, int(arguments.get("min_code_evidence", 2)))
            min_token_hits = max(1, int(arguments.get("min_token_hits", 1)))
            min_token_coverage = float(arguments.get("min_token_coverage", 0.45))
            min_high_signal_facts = max(0, int(arguments.get("min_high_signal_facts", 1)))
            min_fact_confidence = float(arguments.get("min_fact_confidence", 0.62))

            indexed_commit = self._indexed_commit()
            revision_match = self._revision_matches(indexed_commit, revision)
            if revision and not revision_match and not allow_revision_mismatch:
                return self._tool_error(
                    f"index revision mismatch: indexed={indexed_commit or 'unknown'} requested={revision}"
                )

            changed_files_input = arguments.get("changed_files")
            changed_files: List[str] = []
            if isinstance(changed_files_input, list):
                for raw in changed_files_input:
                    path = str(raw).strip()
                    if not path:
                        continue
                    if path not in changed_files:
                        changed_files.append(path)
            elif isinstance(changed_files_input, str) and changed_files_input.strip():
                changed_files.append(changed_files_input.strip())

            if auto_detect_changes and not changed_files:
                changed_files = self._working_tree_changed_files(max_files=max_changed_files)
            changed_files = changed_files[:max_changed_files]

            def _file_priority(path: str) -> int:
                normalized = str(path or "")
                if normalized.startswith("erp-domain/src/main/java/"):
                    return 0
                if normalized.startswith("erp-domain/src/main/resources/"):
                    return 1
                if normalized.startswith("erp-domain/src/test/java/"):
                    return 2
                if normalized.startswith("erp-domain/src/main/"):
                    return 3
                if normalized.startswith("erp-domain/"):
                    return 4
                if normalized.startswith("scripts/rag/"):
                    return 5
                if normalized.startswith("scripts/"):
                    return 6
                if normalized.startswith("docs/"):
                    return 8
                return 7

            changed_files = sorted(changed_files, key=lambda path: (_file_priority(path), path))
            runtime_code_files = [
                path
                for path in changed_files
                if path.startswith("erp-domain/src/main/java/")
                or path.startswith("erp-domain/src/main/resources/")
                or path.endswith(".sql")
            ]
            symbol_scope_files = runtime_code_files if runtime_code_files else changed_files

            symbols_input = arguments.get("symbols")
            explicit_symbols: List[str] = []
            if isinstance(symbols_input, list):
                for raw in symbols_input:
                    symbol = str(raw).strip()
                    if symbol and symbol not in explicit_symbols:
                        explicit_symbols.append(symbol)
            elif isinstance(symbols_input, str) and symbols_input.strip():
                explicit_symbols.append(symbols_input.strip())

            routes_input = arguments.get("routes")
            explicit_routes: List[str] = []
            if isinstance(routes_input, list):
                for raw in routes_input:
                    route = str(raw).strip()
                    if route and route not in explicit_routes:
                        explicit_routes.append(route)
            elif isinstance(routes_input, str) and routes_input.strip():
                explicit_routes.append(routes_input.strip())

            claims_input = arguments.get("claims")
            claims: List[str] = []
            if isinstance(claims_input, list):
                claims = [str(item).strip() for item in claims_input if str(item).strip()]
            elif isinstance(claims_input, str) and claims_input.strip():
                claims = [claims_input.strip()]
            claims = claims[:12]

            sync_payload: Dict[str, Any] = {}
            refreshed_findings_payload: Dict[str, Any] = {}
            if ensure_fresh:
                sync_result = self.call_tool(
                    "rag_sync_index",
                    {
                        "strategy": sync_strategy,
                        "run_findings": refresh_findings,
                        "findings_top_k": top_k,
                    },
                )
                if isinstance(sync_result, dict):
                    if bool(sync_result.get("isError")):
                        return sync_result
                    sync_payload = dict(sync_result.get("structuredContent") or {})
                    findings_refresh = sync_payload.get("findings_refresh")
                    if isinstance(findings_refresh, dict):
                        refreshed_findings_payload = findings_refresh
            elif refresh_findings:
                refreshed_findings_payload = self._run_script("silent_failures.py", ["--json", "--top-k", str(top_k)])

            modules_in_scope: List[str] = []
            if module_input:
                modules_in_scope.append(module_input)
            for module in self._modules_from_paths(changed_files):
                if module not in modules_in_scope:
                    modules_in_scope.append(module)

            derived_symbols: List[str] = []
            if symbol_scope_files:
                scoped_files = symbol_scope_files[: max_changed_files]
                placeholders = ",".join("?" for _ in scoped_files)
                symbol_rows = self._query_sql(
                    f"""
                    SELECT DISTINCT symbol, kind, file_path, line_start
                    FROM chunks
                    WHERE file_path IN ({placeholders})
                      AND symbol IS NOT NULL
                      AND symbol != ''
                    ORDER BY
                      CASE
                        WHEN file_path LIKE 'erp-domain/src/main/java/%' THEN 0
                        WHEN file_path LIKE 'erp-domain/src/main/resources/%' THEN 1
                        WHEN file_path LIKE 'scripts/rag/%' THEN 2
                        WHEN file_path LIKE 'scripts/%' THEN 3
                        WHEN file_path LIKE 'erp-domain/%' THEN 4
                        WHEN file_path LIKE 'docs/%' THEN 8
                        ELSE 6
                      END ASC,
                      CASE kind
                        WHEN 'java_route_handler' THEN 0
                        WHEN 'openapi_operation' THEN 1
                        WHEN 'java_method' THEN 2
                        WHEN 'java_class' THEN 3
                        ELSE 9
                      END ASC,
                      file_path ASC,
                      line_start ASC
                    LIMIT ?
                    """,
                    (*scoped_files, max_symbols * 8),
                )
                for row in symbol_rows:
                    symbol = str(row.get("symbol") or "").strip()
                    if not symbol or symbol in explicit_symbols or symbol in derived_symbols:
                        continue
                    derived_symbols.append(symbol)
                    if len(derived_symbols) >= max_symbols:
                        break

            scope_symbols: List[str] = []
            for symbol in [*explicit_symbols, *derived_symbols]:
                if not symbol or symbol in scope_symbols:
                    continue
                scope_symbols.append(symbol)
                if len(scope_symbols) >= max_symbols:
                    break

            if not modules_in_scope and scope_symbols:
                placeholders = ",".join("?" for _ in scope_symbols[:300])
                module_rows = self._query_sql(
                    f"""
                    SELECT DISTINCT module
                    FROM chunks
                    WHERE symbol IN ({placeholders}) AND module IS NOT NULL AND module != ''
                    LIMIT 20
                    """,
                    tuple(scope_symbols[:300]),
                )
                for row in module_rows:
                    module = str(row.get("module") or "").strip().lower()
                    if module and module not in modules_in_scope:
                        modules_in_scope.append(module)

            route_tokens: List[str] = []
            for route in explicit_routes:
                token = self._normalize_route_input(route, "")
                if token and token not in route_tokens:
                    route_tokens.append(token)

            if scope_symbols:
                for symbol in scope_symbols[: max(1, min(len(scope_symbols), 30))]:
                    candidates = [symbol]
                    if "#" in symbol:
                        candidates.append(symbol.split("#", 1)[0])
                    for candidate in list(dict.fromkeys(candidates)):
                        route_rows = self._query_sql(
                            """
                            SELECT predicate, subject, object, confidence
                            FROM facts
                            WHERE
                              (predicate = 'ROUTE_TO_HANDLER' AND (object = ? OR lower(object) LIKE lower(?)))
                              OR
                              (predicate = 'HANDLES_ROUTE' AND (subject = ? OR lower(subject) LIKE lower(?)))
                            ORDER BY confidence DESC
                            LIMIT 20
                            """,
                            (candidate, f"%{candidate}%", candidate, f"%{candidate}%"),
                        )
                        for row in route_rows:
                            predicate = str(row.get("predicate") or "")
                            route_symbol = ""
                            if predicate == "ROUTE_TO_HANDLER":
                                route_symbol = str(row.get("subject") or "")
                            elif predicate == "HANDLES_ROUTE":
                                route_symbol = str(row.get("object") or "")
                            if route_symbol.startswith("route:"):
                                token = route_symbol[len("route:") :].strip()
                                if token and token not in route_tokens:
                                    route_tokens.append(token)
                            if len(route_tokens) >= max(2, max_scope_items * 2):
                                break
                        if len(route_tokens) >= max(2, max_scope_items * 2):
                            break
                    if len(route_tokens) >= max(2, max_scope_items * 2):
                        break

            route_method = ""
            route_path = ""
            if route_tokens:
                route_token = route_tokens[0]
                if " " in route_token:
                    route_method, route_path = route_token.split(" ", 1)
                else:
                    route_path = route_token
                route_path = route_path.strip()
                if route_path and not route_path.startswith("/"):
                    route_path = "/" + route_path

            primary_module = modules_in_scope[0] if modules_in_scope else ""
            summary_tokens = [
                token.lower()
                for token in re.findall(r"[A-Za-z0-9_]+", change_summary)
                if len(token) >= 4
            ]

            def _primary_symbol_score(symbol: str) -> Tuple[int, int, int]:
                if "#" in symbol:
                    class_name, member_name = symbol.split("#", 1)
                else:
                    class_name, member_name = symbol, symbol.split(".")[-1]
                class_leaf = class_name.split(".")[-1]
                member_lower = member_name.lower()

                constructor_penalty = -2 if member_name == class_leaf else 0
                accessor_penalty = -1 if member_lower.startswith(("get", "set", "is")) else 0
                summary_hits = sum(1 for token in summary_tokens if token in member_lower or token in symbol.lower())
                method_bonus = 1 if "#" in symbol else 0
                return (summary_hits, method_bonus + constructor_penalty + accessor_penalty, -len(member_name))

            primary_symbol = max(scope_symbols, key=_primary_symbol_score) if scope_symbols else ""
            primary_file_path = changed_files[0] if changed_files else ""

            scope_targets: List[Dict[str, Any]] = []
            for symbol in scope_symbols:
                scope_targets.append({"symbol": symbol, "file_path": ""})
                if len(scope_targets) >= max_scope_items:
                    break
            if not scope_targets:
                for file_path in changed_files:
                    scope_targets.append({"symbol": "", "file_path": file_path})
                    if len(scope_targets) >= max_scope_items:
                        break
            if not scope_targets:
                scope_targets.append({"symbol": "", "file_path": ""})

            scope_cards: List[Dict[str, Any]] = []
            for index, target in enumerate(scope_targets[:max_scope_items]):
                preflight_args: Dict[str, Any] = {
                    "depth": depth,
                    "top_k": top_k,
                }
                if target.get("symbol"):
                    preflight_args["symbol"] = str(target["symbol"])
                if target.get("file_path"):
                    preflight_args["file_path"] = str(target["file_path"])
                if index == 0 and route_path:
                    preflight_args["route"] = route_path
                    if route_method:
                        preflight_args["method"] = route_method
                if primary_module:
                    preflight_args["module"] = primary_module

                if not any(key in preflight_args for key in ("symbol", "file_path", "route", "module")):
                    continue
                preflight_result = self.call_tool("rag_preflight_change", preflight_args)
                if isinstance(preflight_result, dict):
                    if bool(preflight_result.get("isError")):
                        scope_cards.append(
                            {
                                "input": preflight_args,
                                "error": str((preflight_result.get("structuredContent") or {}).get("error") or "failed"),
                            }
                        )
                    else:
                        preflight_payload = dict(preflight_result.get("structuredContent") or {})
                        scope_cards.append(
                            {
                                "input": preflight_args,
                                "summary": preflight_payload.get("summary") or {},
                                "impact_modules": preflight_payload.get("impact", {}).get("impacted_modules", [])
                                if isinstance(preflight_payload.get("impact"), dict)
                                else [],
                                "related_tests_count": int((preflight_payload.get("related_tests") or {}).get("count") or 0)
                                if isinstance(preflight_payload.get("related_tests"), dict)
                                else int((preflight_payload.get("summary") or {}).get("related_tests") or 0),
                                "payload": preflight_payload if include_full_payloads else None,
                            }
                        )

            brief_query = change_summary
            if not brief_query:
                scope_bits = []
                if primary_symbol:
                    scope_bits.append(primary_symbol)
                if route_path:
                    scope_bits.append(f"{route_method} {route_path}".strip())
                if primary_file_path:
                    scope_bits.append(primary_file_path)
                if primary_module:
                    scope_bits.append(f"module {primary_module}")
                joined = ", ".join(scope_bits) if scope_bits else "current patch scope"
                brief_query = f"cross-module context and risks for {joined}"

            brief_tool_args: Dict[str, Any] = {
                "query": brief_query,
                "strict": strict,
                "ensure_fresh": False,
                "allow_revision_mismatch": allow_revision_mismatch,
                "top_k": min(top_k, 12),
                "depth": depth,
                "candidate_limit": max(120, min(600, top_k * 12)),
                "impact_depth": depth,
                "impact_top_k": top_k,
                "include_tests": True,
                "include_route_trace": True,
                "run_release_gate": False,
                "max_per_file": max_per_file,
                "max_per_module": max_per_module,
                "min_module_diversity": min_module_diversity,
                "min_file_diversity": min_file_diversity,
                "min_code_evidence": min_code_evidence,
                "min_token_hits": min_token_hits,
                "min_token_coverage": min_token_coverage,
                "min_high_signal_facts": min_high_signal_facts,
                "min_fact_confidence": min_fact_confidence,
            }
            if revision:
                brief_tool_args["revision"] = revision
            if primary_module:
                brief_tool_args["module"] = primary_module
            agent_brief_result = self.call_tool("rag_agent_brief", brief_tool_args)
            agent_brief_payload = (
                dict(agent_brief_result.get("structuredContent") or {})
                if isinstance(agent_brief_result, dict) and not bool(agent_brief_result.get("isError"))
                else {}
            )

            playbook_payload: Dict[str, Any] = {}
            playbook_result = self.call_tool(
                "rag_failure_playbook",
                {
                    "symbol": primary_symbol,
                    "file_path": primary_file_path,
                    "route": route_path,
                    "method": route_method,
                    "module": primary_module,
                    "depth": depth,
                    "top_k": top_k,
                    "refresh_findings": False,
                },
            )
            if isinstance(playbook_result, dict) and not bool(playbook_result.get("isError")):
                playbook_payload = dict(playbook_result.get("structuredContent") or {})

            playbook_findings_raw = (
                playbook_payload.get("findings")
                if isinstance(playbook_payload.get("findings"), list)
                else []
            )
            playbook_findings = [
                self._normalize_finding_row(item)
                for item in playbook_findings_raw
                if isinstance(item, dict)
            ]
            playbook_findings = self._dedupe_findings(playbook_findings)

            impact_payload = (
                playbook_payload.get("impact")
                if isinstance(playbook_payload.get("impact"), dict)
                else {}
            )
            impacted_files_raw = impact_payload.get("impacted_files") if isinstance(impact_payload.get("impacted_files"), list) else []
            impacted_file_paths: List[str] = []
            for item in impacted_files_raw:
                hop = 1
                if isinstance(item, dict):
                    candidate = str(item.get("file_path") or "").strip()
                    hop = int(item.get("hop") or 99)
                else:
                    candidate = str(item).strip()
                if hop > 1:
                    continue
                if candidate and candidate not in impacted_file_paths:
                    impacted_file_paths.append(candidate)
                if len(impacted_file_paths) >= max(40, top_k * 2):
                    break

            changed_file_set = {path for path in changed_files if path}
            impacted_file_set = {path for path in impacted_file_paths if path and path not in changed_file_set}
            scope_file_paths = [*changed_file_set, *impacted_file_set]

            scoped_findings: List[Dict[str, Any]] = []
            if scope_file_paths:
                placeholders = ",".join("?" for _ in scope_file_paths[:180])
                scoped_rows = self._query_sql(
                    f"""
                    SELECT finding_type, severity, title, message, file_path, line_start, line_end, symbol, source, metadata_json
                    FROM findings
                    WHERE file_path IN ({placeholders})
                    ORDER BY
                      CASE severity
                        WHEN 'critical' THEN 0
                        WHEN 'high' THEN 1
                        WHEN 'medium' THEN 2
                        ELSE 3
                      END ASC,
                      file_path ASC,
                      line_start ASC
                    LIMIT ?
                    """,
                    (*scope_file_paths[:180], max(top_k * 6, 120)),
                )
                scoped_findings = [self._normalize_finding_row(row) for row in scoped_rows]
                scoped_findings = self._dedupe_findings(scoped_findings)

            if not scoped_findings and not scope_file_paths:
                scoped_findings = list(playbook_findings)

            touched_findings = [
                item for item in scoped_findings
                if str(item.get("file_path") or "") in changed_file_set
            ]
            impacted_findings = [
                item for item in scoped_findings
                if str(item.get("file_path") or "") in impacted_file_set
            ]

            if scope_file_paths:
                scoped_effective_findings = self._dedupe_findings([*touched_findings, *impacted_findings])
            else:
                scoped_effective_findings = list(scoped_findings)

            scoped_keys = {
                (
                    str(item.get("finding_type") or ""),
                    str(item.get("file_path") or ""),
                    int(item.get("line_start") or 0),
                    int(item.get("line_end") or 0),
                    str(item.get("symbol") or ""),
                    str(item.get("message") or ""),
                )
                for item in scoped_effective_findings
            }
            baseline_debt_findings = [
                item
                for item in playbook_findings
                if (
                    str(item.get("finding_type") or ""),
                    str(item.get("file_path") or ""),
                    int(item.get("line_start") or 0),
                    int(item.get("line_end") or 0),
                    str(item.get("symbol") or ""),
                    str(item.get("message") or ""),
                )
                not in scoped_keys
            ]
            if not baseline_debt_findings:
                baseline_filters: List[str] = []
                baseline_params: List[Any] = []
                if primary_module:
                    baseline_filters.append("lower(file_path) LIKE lower(?)")
                    baseline_params.append(f"%/modules/{primary_module}/%")
                elif primary_file_path:
                    baseline_filters.append("file_path = ?")
                    baseline_params.append(primary_file_path)
                baseline_where = " AND ".join(baseline_filters) if baseline_filters else "1=1"
                baseline_rows = self._query_sql(
                    f"""
                    SELECT finding_type, severity, title, message, file_path, line_start, line_end, symbol, source, metadata_json
                    FROM findings
                    WHERE {baseline_where}
                    ORDER BY
                      CASE severity
                        WHEN 'critical' THEN 0
                        WHEN 'high' THEN 1
                        WHEN 'medium' THEN 2
                        ELSE 3
                      END ASC,
                      file_path ASC,
                      line_start ASC
                    LIMIT ?
                    """,
                    (*baseline_params, max(top_k * 4, 120)),
                )
                baseline_candidates = [
                    self._normalize_finding_row(row)
                    for row in baseline_rows
                ]
                baseline_debt_findings = [
                    item
                    for item in baseline_candidates
                    if (
                        str(item.get("finding_type") or ""),
                        str(item.get("file_path") or ""),
                        int(item.get("line_start") or 0),
                        int(item.get("line_end") or 0),
                        str(item.get("symbol") or ""),
                        str(item.get("message") or ""),
                    )
                    not in scoped_keys
                ]
                baseline_debt_findings = self._dedupe_findings(baseline_debt_findings)[: max(top_k, 20)]

            release_gate_payload: Dict[str, Any] = {}
            require_tests_for_scope = bool(require_tests and runtime_code_files)
            if run_release_gate:
                release_gate_result = self.call_tool(
                    "rag_release_gate",
                    {
                        "symbol": primary_symbol,
                        "file_path": primary_file_path,
                        "route": route_path,
                        "method": route_method,
                        "module": primary_module,
                        "depth": depth,
                        "top_k": top_k,
                        "refresh_findings": False,
                        "fail_on_stale_index": fail_on_stale_index,
                        "max_critical_findings": max(max_critical_findings, 999),
                        "max_high_findings": max(max_high_findings, 999),
                        "max_route_conflicts": max_route_conflicts,
                        "max_config_conflicts": max_config_conflicts,
                        "require_tests": require_tests_for_scope,
                        "min_related_tests": min_related_tests,
                    },
                )
                if isinstance(release_gate_result, dict) and not bool(release_gate_result.get("isError")):
                    release_gate_payload = dict(release_gate_result.get("structuredContent") or {})

            claim_verification_payload: Dict[str, Any] = {}
            if claims:
                verify_tool_args: Dict[str, Any] = {
                    "claims": claims,
                    "strict": strict,
                    "allow_revision_mismatch": allow_revision_mismatch,
                    "top_k": min(top_k, 12),
                    "depth": depth,
                    "candidate_limit": max(120, min(600, top_k * 12)),
                    "max_per_file": max_per_file,
                    "max_per_module": max_per_module,
                    "min_module_diversity": min_module_diversity,
                    "min_file_diversity": min_file_diversity,
                    "min_code_evidence": min_code_evidence,
                    "min_token_hits": min_token_hits,
                    "min_token_coverage": min_token_coverage,
                    "min_high_signal_facts": min_high_signal_facts,
                    "min_fact_confidence": min_fact_confidence,
                }
                if revision:
                    verify_tool_args["revision"] = revision
                verify_result = self.call_tool("rag_verify_claims", verify_tool_args)
                if isinstance(verify_result, dict) and not bool(verify_result.get("isError")):
                    claim_verification_payload = dict(verify_result.get("structuredContent") or {})

            route_conflicts: List[Dict[str, Any]] = []
            for route_token in route_tokens[:3]:
                method = ""
                route = route_token
                if " " in route_token:
                    method, route = route_token.split(" ", 1)
                route = route.strip()
                if route and not route.startswith("/"):
                    route = "/" + route
                conflict_result = self.call_tool(
                    "rag_route_conflicts",
                    {"route": route, "method": method, "limit": top_k},
                )
                if isinstance(conflict_result, dict) and not bool(conflict_result.get("isError")):
                    conflict_payload = dict(conflict_result.get("structuredContent") or {})
                    if int(conflict_payload.get("conflict_count") or 0) > 0:
                        route_conflicts.append(conflict_payload)

            scoped_config_conflicts: List[Dict[str, Any]] = []
            config_usage_rows = (
                playbook_payload.get("config_usage")
                if isinstance(playbook_payload.get("config_usage"), list)
                else []
            )
            used_config_keys: List[str] = []
            for row in config_usage_rows:
                key = str((row or {}).get("object") or "").strip()
                if key and key not in used_config_keys:
                    used_config_keys.append(key)
            for key_symbol in used_config_keys[:8]:
                normalized_key = key_symbol[len("config:") :] if key_symbol.startswith("config:") else key_symbol
                cfg_result = self.call_tool(
                    "rag_config_conflicts",
                    {"key": normalized_key, "limit": 20, "include_low": False},
                )
                if isinstance(cfg_result, dict) and not bool(cfg_result.get("isError")):
                    cfg_payload = dict(cfg_result.get("structuredContent") or {})
                    if int(cfg_payload.get("conflict_count") or 0) > 0:
                        scoped_config_conflicts.append(cfg_payload)

            failures: List[str] = []
            warnings: List[str] = []

            if not changed_files and not scope_symbols and not route_tokens and not primary_module:
                failures.append(
                    "No patch scope detected. Provide changed_files/symbols/routes or run with modified files in working tree."
                )

            release_status = str(release_gate_payload.get("status") or "")
            if release_status == "FAIL":
                for item in release_gate_payload.get("failures", []) if isinstance(release_gate_payload.get("failures"), list) else []:
                    message = str(item)
                    if "findings" in message.lower():
                        warnings.append(f"Release gate findings threshold note: {message} (scoped by patch guard).")
                        continue
                    failures.append(message)
            elif release_status == "WARN":
                for item in release_gate_payload.get("warnings", []) if isinstance(release_gate_payload.get("warnings"), list) else []:
                    warning_text = str(item)
                    if "findings" in warning_text.lower():
                        continue
                    warnings.append(warning_text)

            critical_findings = len(
                [item for item in scoped_effective_findings if str((item or {}).get("severity") or "").lower() == "critical"]
            )
            high_findings = len(
                [item for item in scoped_effective_findings if str((item or {}).get("severity") or "").lower() == "high"]
            )
            baseline_critical_findings = len(
                [item for item in baseline_debt_findings if str((item or {}).get("severity") or "").lower() == "critical"]
            )
            baseline_high_findings = len(
                [item for item in baseline_debt_findings if str((item or {}).get("severity") or "").lower() == "high"]
            )
            if critical_findings > max_critical_findings:
                failures.append(
                    f"Critical findings in changed/impacted scope: {critical_findings} (allowed {max_critical_findings})."
                )
            if high_findings > max_high_findings:
                warnings.append(f"High findings in changed/impacted scope: {high_findings} (allowed {max_high_findings}).")
            if baseline_critical_findings or baseline_high_findings:
                warnings.append(
                    "Repository baseline debt exists outside touched scope "
                    f"(critical={baseline_critical_findings}, high={baseline_high_findings})."
                )

            related_tests_count = int((playbook_payload.get("summary") or {}).get("related_tests") or 0) if isinstance(playbook_payload.get("summary"), dict) else 0
            if require_tests_for_scope and related_tests_count < min_related_tests:
                failures.append(
                    f"Related tests below threshold: {related_tests_count} < {min_related_tests}."
                )
            elif require_tests and not require_tests_for_scope:
                warnings.append("Test gate relaxed for non-runtime scope (docs/tooling/config-only changes).")

            total_route_conflicts = sum(int(item.get("conflict_count") or 0) for item in route_conflicts)
            if total_route_conflicts > max_route_conflicts:
                failures.append(
                    f"Route conflicts exceed threshold: {total_route_conflicts} > {max_route_conflicts}."
                )
            elif total_route_conflicts > 0:
                warnings.append(f"Route conflicts detected: {total_route_conflicts}.")

            total_config_conflicts = sum(int(item.get("conflict_count") or 0) for item in scoped_config_conflicts)
            if total_config_conflicts > max_config_conflicts:
                failures.append(
                    f"Config conflicts exceed threshold: {total_config_conflicts} > {max_config_conflicts}."
                )
            elif total_config_conflicts > 0:
                warnings.append(f"Config conflicts detected in scoped keys: {total_config_conflicts}.")

            brief_summary = (
                agent_brief_payload.get("summary")
                if isinstance(agent_brief_payload.get("summary"), dict)
                else {}
            )
            if strict and bool(brief_summary) and brief_summary.get("strict_guard_passed") is False:
                warnings.append("Strict retrieval guard did not pass for the patch briefing; review citations carefully.")

            if claim_verification_payload:
                claim_summary = claim_verification_payload.get("summary")
                if isinstance(claim_summary, dict):
                    unsupported = int(claim_summary.get("unsupported") or 0)
                    needs_review = int(claim_summary.get("needs_review") or 0)
                    if unsupported > 0:
                        failures.append(f"{unsupported} claims are unsupported by indexed evidence.")
                    if needs_review > 0:
                        warnings.append(f"{needs_review} claims need human review before patching.")

            dedupe_payload: Dict[str, Any] = {}
            dedupe_error = ""
            dedupe_args: Dict[str, Any] = {
                "limit": max(6, min(20, max_scope_items * 4)),
                "allow_revision_mismatch": allow_revision_mismatch,
            }
            if revision:
                dedupe_args["revision"] = revision
            if primary_symbol:
                dedupe_args["symbol"] = primary_symbol
            elif change_summary:
                dedupe_args["concept"] = change_summary

            if "symbol" in dedupe_args or "concept" in dedupe_args:
                dedupe_result = self.call_tool("rag_dedupe_resolve", dedupe_args)
                if isinstance(dedupe_result, dict) and not bool(dedupe_result.get("isError")):
                    dedupe_payload = dict(dedupe_result.get("structuredContent") or {})
                else:
                    dedupe_error = str((dedupe_result.get("structuredContent") or {}).get("error") or "").strip()
            else:
                dedupe_error = "No symbol or concept available for duplicate resolution."

            if primary_symbol and dedupe_error:
                warnings.append(
                    f"Duplicate resolver could not confirm canonical path for {primary_symbol}: {dedupe_error}"
                )

            canonical_symbol = str((dedupe_payload.get("canonical") or {}).get("symbol") or "")
            if primary_symbol and canonical_symbol and canonical_symbol != primary_symbol:
                warnings.append(
                    f"Primary scope symbol differs from canonical duplicate resolver result: {primary_symbol} -> {canonical_symbol}."
                )
                action_items_hint = (
                    f"Prefer editing canonical symbol {canonical_symbol} at "
                    f"{str((dedupe_payload.get('safe_edit_location') or {}).get('file_path') or 'unknown')}."
                )
            else:
                action_items_hint = ""

            action_items: List[str] = []
            if failures:
                action_items.append("Resolve FAIL blockers before editing or merging.")
            elif warnings:
                action_items.append("Review WARN items and keep citations in final patch rationale.")
            else:
                action_items.append("Guard checks passed; proceed with patch and keep cite-or-refuse in responses.")

            related_tests_payload = (
                playbook_payload.get("related_tests")
                if isinstance(playbook_payload.get("related_tests"), dict)
                else {}
            )
            test_entries = related_tests_payload.get("tests") if isinstance(related_tests_payload.get("tests"), list) else []
            test_files: List[str] = []
            for entry in test_entries[:10]:
                test_file = str((entry or {}).get("test_file") or "").strip()
                if test_file and test_file not in test_files:
                    test_files.append(test_file)
            if test_files:
                action_items.append(f"Run related tests: {', '.join(test_files[:8])}")

            if impacted_file_paths:
                action_items.append(
                    f"Spot-check impacted files: {', '.join(impacted_file_paths[:6])}"
                )

            if total_route_conflicts > 0:
                action_items.append("Review route conflicts for handler/contract ambiguity before patching endpoints.")
            if total_config_conflicts > 0:
                action_items.append("Review scoped config conflicts to avoid env-specific regressions.")
            if action_items_hint:
                action_items.append(action_items_hint)

            dedup_action_items: List[str] = []
            for item in action_items:
                if item not in dedup_action_items:
                    dedup_action_items.append(item)

            status = "PASS"
            if failures:
                status = "FAIL"
            elif warnings:
                status = "WARN"

            payload = {
                "status": status,
                "input": {
                    "change_summary": change_summary or None,
                    "module": module_input or None,
                    "auto_detect_changes": auto_detect_changes,
                    "ensure_fresh": ensure_fresh,
                    "sync_strategy": sync_strategy,
                    "refresh_findings": refresh_findings,
                    "strict": strict,
                    "revision": revision or None,
                    "allow_revision_mismatch": allow_revision_mismatch,
                    "depth": depth,
                    "top_k": top_k,
                    "max_changed_files": max_changed_files,
                    "max_symbols": max_symbols,
                    "max_scope_items": max_scope_items,
                    "run_release_gate": run_release_gate,
                    "require_tests": require_tests,
                    "require_tests_for_scope": require_tests_for_scope,
                    "min_related_tests": min_related_tests,
                    "max_critical_findings": max_critical_findings,
                    "max_high_findings": max_high_findings,
                    "max_route_conflicts": max_route_conflicts,
                    "max_config_conflicts": max_config_conflicts,
                    "fail_on_stale_index": fail_on_stale_index,
                    "max_per_file": max_per_file,
                    "max_per_module": max_per_module,
                    "min_module_diversity": min_module_diversity,
                    "min_file_diversity": min_file_diversity,
                    "indexed_revision": indexed_commit or None,
                    "revision_matches_index": revision_match if revision else None,
                },
                "scope": {
                    "changed_files": changed_files,
                    "explicit_symbols": explicit_symbols,
                    "derived_symbols": derived_symbols[:max_symbols],
                    "scope_symbols": scope_symbols[:max_symbols],
                    "route_tokens": route_tokens,
                    "modules": modules_in_scope,
                    "primary_symbol": primary_symbol or None,
                    "primary_file_path": primary_file_path or None,
                    "primary_route": route_path or None,
                    "primary_method": route_method or None,
                    "primary_module": primary_module or None,
                },
                "summary": {
                    "scope_cards": len(scope_cards),
                    "related_tests": related_tests_count,
                    "scoped_findings": len(scoped_effective_findings),
                    "touched_findings": len(touched_findings),
                    "impacted_findings": len(impacted_findings),
                    "critical_findings": critical_findings,
                    "high_findings": high_findings,
                    "baseline_debt_findings": len(baseline_debt_findings),
                    "baseline_critical_findings": baseline_critical_findings,
                    "baseline_high_findings": baseline_high_findings,
                    "route_conflicts": total_route_conflicts,
                    "config_conflicts": total_config_conflicts,
                    "release_status": release_status or None,
                    "claims_checked": len(claims),
                },
                "failures": failures,
                "warnings": warnings,
                "actions": dedup_action_items,
                "sync": sync_payload or None,
                "refreshed_findings": refreshed_findings_payload or None,
                "agent_brief": (agent_brief_payload if include_full_payloads else {"summary": brief_summary or {}}),
                "scope_cards": scope_cards,
                "playbook": (
                    playbook_payload
                    if include_full_payloads
                    else {
                        "summary": (playbook_payload.get("summary") or {}) if isinstance(playbook_payload, dict) else {},
                        "related_tests_count": related_tests_count,
                    }
                ),
                "release_gate": (
                    release_gate_payload
                    if include_full_payloads
                    else {
                        "status": release_status or None,
                        "summary": (release_gate_payload.get("summary") or {}) if isinstance(release_gate_payload, dict) else {},
                        "failures": (release_gate_payload.get("failures") or []) if isinstance(release_gate_payload, dict) else [],
                        "warnings": (release_gate_payload.get("warnings") or []) if isinstance(release_gate_payload, dict) else [],
                    }
                ),
                "route_conflicts": route_conflicts if include_full_payloads else [{"conflict_count": total_route_conflicts}],
                "config_conflicts": scoped_config_conflicts if include_full_payloads else [{"conflict_count": total_config_conflicts}],
                "claim_verification": (
                    claim_verification_payload
                    if include_full_payloads
                    else {
                        "summary": (claim_verification_payload.get("summary") or {}) if isinstance(claim_verification_payload, dict) else {},
                    }
                ),
                "findings_scope": {
                    "changed_files": sorted(changed_file_set),
                    "impacted_files": sorted(impacted_file_set),
                    "scoped_count": len(scoped_effective_findings),
                    "touched_count": len(touched_findings),
                    "impacted_count": len(impacted_findings),
                    "baseline_debt_count": len(baseline_debt_findings),
                    "scoped_findings": (
                        scoped_effective_findings[: min(len(scoped_effective_findings), 20)]
                        if include_full_payloads
                        else [
                            {
                                "finding_type": str(item.get("finding_type") or ""),
                                "severity": str(item.get("severity") or ""),
                                "file_path": str(item.get("file_path") or ""),
                                "line_start": int(item.get("line_start") or 0),
                                "line_end": int(item.get("line_end") or 0),
                                "symbol": str(item.get("symbol") or ""),
                            }
                            for item in scoped_effective_findings[:6]
                        ]
                    ),
                    "baseline_samples": (
                        baseline_debt_findings[: min(len(baseline_debt_findings), 20)]
                        if include_full_payloads
                        else [
                            {
                                "finding_type": str(item.get("finding_type") or ""),
                                "severity": str(item.get("severity") or ""),
                                "file_path": str(item.get("file_path") or ""),
                                "line_start": int(item.get("line_start") or 0),
                                "line_end": int(item.get("line_end") or 0),
                                "symbol": str(item.get("symbol") or ""),
                            }
                            for item in baseline_debt_findings[:6]
                        ]
                    ),
                },
                "dedupe_resolution": (
                    dedupe_payload
                    if include_full_payloads
                    else {
                        "status": (
                            "resolved"
                            if isinstance(dedupe_payload, dict) and dedupe_payload.get("canonical")
                            else ("error" if dedupe_error else "not_resolved")
                        ),
                        "message": (
                            str(dedupe_payload.get("message") or "")
                            if isinstance(dedupe_payload, dict) and dedupe_payload
                            else (dedupe_error or "Canonical duplicate resolution not available for this scope.")
                        ),
                        "canonical": dedupe_payload.get("canonical") if isinstance(dedupe_payload, dict) else None,
                        "safe_edit_location": dedupe_payload.get("safe_edit_location") if isinstance(dedupe_payload, dict) else None,
                        "count": dedupe_payload.get("count") if isinstance(dedupe_payload, dict) else 0,
                    }
                ),
            }
            return self._tool_result(payload)

        if name == "rag_config_conflicts":
            key = str(arguments.get("key", "")).strip()
            limit = max(1, min(200, int(arguments.get("limit", 60))))
            include_low = bool(arguments.get("include_low", False))

            where = "predicate = 'CONFIG_DEFINED'"
            params: List[Any] = []
            if key:
                normalized_key = key if key.lower().startswith("config:") else f"config:{key}"
                where += " AND (lower(subject) LIKE lower(?) OR lower(subject) LIKE lower(?))"
                params.extend([f"%{normalized_key}%", f"%{key}%"])

            conflict_rows = self._query_sql(
                f"""
                SELECT
                  subject AS key_symbol,
                  COUNT(*) AS occurrences,
                  COUNT(DISTINCT file_path) AS file_count,
                  COUNT(DISTINCT evidence) AS value_variants
                FROM facts
                WHERE {where}
                GROUP BY subject
                HAVING file_count > 1 OR value_variants > 1
                ORDER BY value_variants DESC, file_count DESC, occurrences DESC
                LIMIT ?
                """,
                (*params, limit),
            )

            conflicts: List[Dict[str, Any]] = []

            def _config_file_role(path: str) -> str:
                lowered = path.lower()
                name = Path(lowered).name
                if "/src/test/" in lowered:
                    return "test"
                if name.startswith("application-") and (
                    name.endswith(".yml") or name.endswith(".yaml") or name.endswith(".properties")
                ):
                    return "profile"
                if name in {"application.yml", "application.yaml", "application.properties", ".env", ".env.example"}:
                    return "base"
                return "other"

            for row in conflict_rows:
                key_symbol = str(row.get("key_symbol") or "")
                if not key_symbol:
                    continue
                definitions = self._query_sql(
                    """
                    SELECT file_path, line_start, line_end, evidence
                    FROM facts
                    WHERE predicate = 'CONFIG_DEFINED' AND subject = ?
                    ORDER BY file_path ASC, line_start ASC
                    LIMIT 60
                    """,
                    (key_symbol,),
                )
                roles = sorted(
                    {
                        _config_file_role(str(definition.get("file_path") or ""))
                        for definition in definitions
                        if definition.get("file_path")
                    }
                )
                value_variants = int(row.get("value_variants") or 0)
                file_count = int(row.get("file_count") or 0)
                environment_split = (
                    bool(roles)
                    and set(roles).issubset({"profile", "test"})
                ) or (bool(roles) and set(roles).issubset({"base", "profile", "test"}) and "other" not in roles)

                severity = "low"
                if value_variants > 1 and "other" in roles:
                    severity = "high"
                elif value_variants > 1 and "base" in roles and "other" in roles:
                    severity = "high"
                elif value_variants > 1 and "base" in roles:
                    severity = "medium"
                elif value_variants > 1 and file_count >= 3:
                    severity = "medium"

                if environment_split and severity != "high":
                    severity = "low"
                if not include_low and severity == "low":
                    continue

                conflicts.append(
                    {
                        "key_symbol": key_symbol,
                        "occurrences": int(row.get("occurrences") or 0),
                        "file_count": file_count,
                        "value_variants": value_variants,
                        "severity": severity,
                        "roles": roles,
                        "environment_split": bool(environment_split),
                        "definitions": definitions,
                    }
                )
                if len(conflicts) >= limit:
                    break

            payload = {
                "key_filter": key or None,
                "include_low": include_low,
                "conflict_count": len(conflicts),
                "conflicts": conflicts,
            }
            return self._tool_result(payload)

        if name == "rag_failure_playbook":
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            route = str(arguments.get("route", "")).strip()
            method = str(arguments.get("method", "")).strip()
            module = str(arguments.get("module") or "").strip().lower()
            depth = max(1, min(5, int(arguments.get("depth", 2))))
            top_k = max(5, min(120, int(arguments.get("top_k", 25))))
            refresh_findings = bool(arguments.get("refresh_findings"))
            scope_files_input = arguments.get("scope_files")

            scope_files: List[str] = []
            if isinstance(scope_files_input, list):
                for raw in scope_files_input:
                    normalized = str(raw).strip()
                    if normalized and normalized not in scope_files:
                        scope_files.append(normalized)
            elif isinstance(scope_files_input, str):
                normalized = scope_files_input.strip()
                if normalized:
                    scope_files.append(normalized)

            if not symbol and not file_path and not route and not module:
                return self._tool_error("provide at least one of symbol/file_path/route/module")

            refreshed_findings: Dict[str, Any] = {}
            if refresh_findings:
                refreshed_findings = self._run_script("silent_failures.py", ["--json", "--top-k", str(top_k)])

            route_trace_payload: Dict[str, Any] = {}
            if route:
                trace_result = self.call_tool(
                    "rag_trace_route",
                    {"route": route, "method": method, "limit": top_k},
                )
                if isinstance(trace_result, dict):
                    route_trace_payload = dict(trace_result.get("structuredContent") or {})

            scope_symbols: Set[str] = set()
            if symbol:
                scope_symbols.add(symbol)
                if "#" in symbol:
                    scope_symbols.add(symbol.split("#", 1)[0])
            if file_path:
                file_symbols = self._query_sql(
                    """
                    SELECT DISTINCT symbol
                    FROM chunks
                    WHERE file_path = ? AND symbol IS NOT NULL AND symbol != ''
                    LIMIT 400
                    """,
                    (file_path,),
                )
                scope_symbols.update(str(row.get("symbol") or "") for row in file_symbols)
            for handler_symbol in route_trace_payload.get("handlers", []) if isinstance(route_trace_payload, dict) else []:
                if handler_symbol:
                    scope_symbols.add(str(handler_symbol))
            for edge in route_trace_payload.get("handler_calls", []) if isinstance(route_trace_payload, dict) else []:
                scope_symbols.add(str(edge.get("subject") or ""))
                scope_symbols.add(str(edge.get("object") or ""))
            scope_symbols = {s for s in scope_symbols if s}

            impact_payload: Dict[str, Any] = {}
            if symbol or file_path:
                impact_args: List[str] = ["--json", "--depth", str(depth), "--top-k", str(top_k)]
                if symbol:
                    impact_args.extend(["--symbol", symbol])
                if file_path:
                    impact_args.extend(["--file-path", file_path])
                impact_payload = self._run_script("impact.py", impact_args)

            derived_module = module
            if not derived_module and impact_payload.get("impacted_modules"):
                impacted_modules = impact_payload.get("impacted_modules")
                if isinstance(impacted_modules, list) and impacted_modules:
                    derived_module = str(impacted_modules[0]).strip().lower()

            tests_result = self.call_tool(
                "rag_related_tests",
                {
                    "symbol": symbol,
                    "file_path": file_path,
                    "module": derived_module,
                    "limit": top_k,
                },
            )
            related_tests_payload = (
                dict(tests_result.get("structuredContent") or {})
                if isinstance(tests_result, dict)
                else {}
            )

            findings_where: List[str] = ["1=1"]
            findings_params: List[Any] = []
            if symbol:
                findings_where.append("(symbol = ? OR lower(symbol) LIKE lower(?))")
                findings_params.extend([symbol, f"%{symbol}%"])
            if file_path:
                findings_where.append("file_path = ?")
                findings_params.append(file_path)
            if derived_module:
                findings_where.append("lower(file_path) LIKE lower(?)")
                findings_params.append(f"%/modules/{derived_module}/%")
            if scope_files:
                placeholders = ",".join("?" for _ in scope_files[:80])
                findings_where.append(f"file_path IN ({placeholders})")
                findings_params.extend(scope_files[:80])
            findings_rows = self._query_sql(
                f"""
                SELECT finding_type, severity, title, message, file_path, line_start, line_end, symbol, source, metadata_json
                FROM findings
                WHERE {' AND '.join(findings_where)}
                ORDER BY
                  CASE severity
                    WHEN 'critical' THEN 0
                    WHEN 'high' THEN 1
                    WHEN 'medium' THEN 2
                    ELSE 3
                  END ASC,
                  file_path ASC,
                  line_start ASC
                LIMIT ?
                """,
                (*findings_params, top_k),
            )
            findings_rows = [self._normalize_finding_row(row) for row in findings_rows]

            config_usage_rows: List[Dict[str, Any]] = []
            if scope_symbols:
                placeholders = ",".join("?" for _ in list(scope_symbols)[:300])
                config_usage_rows = self._query_sql(
                    f"""
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE predicate IN ('USES_CONFIG', 'USES_ENV')
                      AND subject IN ({placeholders})
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (*list(scope_symbols)[:300], top_k * 4),
                )

            used_keys = sorted({str(row.get("object") or "") for row in config_usage_rows if row.get("object")})
            config_definitions: List[Dict[str, Any]] = []
            for cfg_key in used_keys[: min(len(used_keys), top_k * 2)]:
                defs = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE predicate = 'CONFIG_DEFINED' AND subject = ?
                    ORDER BY file_path ASC, line_start ASC
                    LIMIT 20
                    """,
                    (cfg_key,),
                )
                config_definitions.extend(defs)

            payload = {
                "input": {
                    "symbol": symbol or None,
                    "file_path": file_path or None,
                    "route": route or None,
                    "method": method or None,
                    "module": module or derived_module or None,
                    "depth": depth,
                    "top_k": top_k,
                    "refresh_findings": refresh_findings,
                },
                "summary": {
                    "scope_symbols": len(scope_symbols),
                    "findings": len(findings_rows),
                    "config_usage": len(config_usage_rows),
                    "config_definitions": len(config_definitions),
                    "related_tests": int(related_tests_payload.get("count") or 0),
                    "impact_edges": int(impact_payload.get("edge_count") or 0),
                    "route_handlers": len(route_trace_payload.get("handlers") or []),
                },
                "impact": impact_payload or None,
                "route_trace": route_trace_payload or None,
                "related_tests": related_tests_payload or None,
                "findings": findings_rows,
                "config_usage": config_usage_rows,
                "config_definitions": config_definitions,
                "refreshed_findings": refreshed_findings or None,
            }
            return self._tool_result(payload)

        if name == "rag_release_gate":
            symbol = str(arguments.get("symbol", "")).strip()
            file_path = str(arguments.get("file_path", "")).strip()
            route = str(arguments.get("route", "")).strip()
            method = str(arguments.get("method", "")).strip()
            module = str(arguments.get("module") or "").strip().lower()
            depth = max(1, min(5, int(arguments.get("depth", 2))))
            top_k = max(5, min(120, int(arguments.get("top_k", 25))))
            refresh_findings = bool(arguments.get("refresh_findings"))

            if not symbol and not file_path and not route and not module:
                return self._tool_error("provide at least one of symbol/file_path/route/module")

            fail_on_stale_index = bool(arguments.get("fail_on_stale_index", True))
            max_critical_findings = max(0, int(arguments.get("max_critical_findings", 0)))
            max_high_findings = max(0, int(arguments.get("max_high_findings", 2)))
            max_route_conflicts = max(0, int(arguments.get("max_route_conflicts", 0)))
            max_config_conflicts = max(0, int(arguments.get("max_config_conflicts", 20)))
            require_tests = bool(arguments.get("require_tests", True))
            min_related_tests = max(0, int(arguments.get("min_related_tests", 1)))

            health_result = self.call_tool("rag_health", {})
            health_payload = (
                dict(health_result.get("structuredContent") or {})
                if isinstance(health_result, dict)
                else {}
            )

            playbook_result = self.call_tool(
                "rag_failure_playbook",
                {
                    "symbol": symbol,
                    "file_path": file_path,
                    "route": route,
                    "method": method,
                    "module": module,
                    "depth": depth,
                    "top_k": top_k,
                    "refresh_findings": refresh_findings,
                },
            )
            playbook_payload = (
                dict(playbook_result.get("structuredContent") or {})
                if isinstance(playbook_result, dict)
                else {}
            )

            route_conflicts_payload: Dict[str, Any] = {"conflict_count": 0, "conflicts": []}
            if route:
                route_conflict_result = self.call_tool(
                    "rag_route_conflicts",
                    {"route": route, "method": method, "limit": top_k},
                )
                if isinstance(route_conflict_result, dict):
                    route_conflicts_payload = dict(route_conflict_result.get("structuredContent") or route_conflicts_payload)

            config_conflicts_result = self.call_tool("rag_config_conflicts", {"limit": top_k})
            config_conflicts_payload = (
                dict(config_conflicts_result.get("structuredContent") or {})
                if isinstance(config_conflicts_result, dict)
                else {"conflict_count": 0, "conflicts": []}
            )

            findings = playbook_payload.get("findings") if isinstance(playbook_payload, dict) else []
            severity_counts = {"critical": 0, "high": 0, "medium": 0, "low": 0}
            if isinstance(findings, list):
                for finding in findings:
                    sev = str((finding or {}).get("severity") or "").lower()
                    if sev in severity_counts:
                        severity_counts[sev] += 1

            related_tests_count = int(
                ((playbook_payload.get("related_tests") or {}).get("count"))
                if isinstance(playbook_payload, dict)
                else 0
            )
            route_conflict_count = int(route_conflicts_payload.get("conflict_count") or 0)
            config_conflict_count = int(config_conflicts_payload.get("conflict_count") or 0)
            is_stale = bool(health_payload.get("is_stale")) if isinstance(health_payload, dict) else False

            failures: List[str] = []
            warnings: List[str] = []

            if fail_on_stale_index and is_stale:
                failures.append("Index is stale versus HEAD commit.")
            elif is_stale:
                warnings.append("Index is stale versus HEAD commit.")

            if severity_counts["critical"] > max_critical_findings:
                failures.append(
                    f"Critical findings {severity_counts['critical']} exceed threshold {max_critical_findings}."
                )
            if severity_counts["high"] > max_high_findings:
                failures.append(f"High findings {severity_counts['high']} exceed threshold {max_high_findings}.")
            elif severity_counts["high"] > 0:
                warnings.append(f"High findings present: {severity_counts['high']}.")

            if route_conflict_count > max_route_conflicts:
                failures.append(f"Route conflicts {route_conflict_count} exceed threshold {max_route_conflicts}.")
            if config_conflict_count > max_config_conflicts:
                failures.append(f"Config conflicts {config_conflict_count} exceed threshold {max_config_conflicts}.")
            elif config_conflict_count > 0:
                warnings.append(f"Config conflicts detected: {config_conflict_count}.")

            if require_tests and related_tests_count < min_related_tests:
                failures.append(
                    f"Related tests {related_tests_count} below required minimum {min_related_tests}."
                )
            elif not require_tests and related_tests_count == 0:
                warnings.append("No related tests discovered.")

            raw_impact_edges = ((playbook_payload.get("impact") or {}).get("edge_count")) if isinstance(playbook_payload, dict) else 0
            impact_edges = int(raw_impact_edges or 0)
            if impact_edges == 0:
                warnings.append("No impact edges found for supplied scope.")

            status = "FAIL" if failures else ("WARN" if warnings else "PASS")
            payload = {
                "status": status,
                "input": {
                    "symbol": symbol or None,
                    "file_path": file_path or None,
                    "route": route or None,
                    "method": method or None,
                    "module": module or None,
                    "depth": depth,
                    "top_k": top_k,
                },
                "thresholds": {
                    "fail_on_stale_index": fail_on_stale_index,
                    "max_critical_findings": max_critical_findings,
                    "max_high_findings": max_high_findings,
                    "max_route_conflicts": max_route_conflicts,
                    "max_config_conflicts": max_config_conflicts,
                    "require_tests": require_tests,
                    "min_related_tests": min_related_tests,
                },
                "summary": {
                    "is_stale_index": is_stale,
                    "critical_findings": severity_counts["critical"],
                    "high_findings": severity_counts["high"],
                    "route_conflicts": route_conflict_count,
                    "config_conflicts": config_conflict_count,
                    "related_tests": related_tests_count,
                    "impact_edges": impact_edges,
                },
                "failures": failures,
                "warnings": warnings,
                "health": health_payload or None,
                "playbook": playbook_payload or None,
                "route_conflicts": route_conflicts_payload or None,
                "config_conflicts": config_conflicts_payload or None,
            }
            return self._tool_result(payload)

        if name == "rag_get_config_usage":
            key = str(arguments.get("key", "")).strip()
            if not key:
                return self._tool_error("'key' is required")
            normalized = key if key.lower().startswith("config:") else f"config:{key}"
            limit = int(arguments.get("limit", 50))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE object = ? OR subject = ?
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (normalized, normalized, limit),
            )
            return self._tool_result({"key": normalized, "count": len(rows), "rows": rows})

        if name == "rag_get_table_usage":
            table = str(arguments.get("table", "")).strip()
            if not table:
                return self._tool_error("'table' is required")
            normalized = table if ":" in table else f"table:{table}"
            entity_form = table if ":" in table else f"entity:{table}"
            limit = int(arguments.get("limit", 60))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN ('READS_TABLE', 'WRITES_TABLE', 'TABLE_DEFINED')
                  AND (object = ? OR object = ? OR lower(object) LIKE lower(?) OR lower(object) LIKE lower(?))
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (normalized, entity_form, f"%{normalized}%", f"%{entity_form}%", limit),
            )
            return self._tool_result({"table": table, "count": len(rows), "rows": rows})

        if name == "rag_get_event_flow":
            event = str(arguments.get("event", "")).strip()
            if not event:
                return self._tool_error("'event' is required")
            normalized = event if event.lower().startswith("event:") else f"event:{event}"
            limit = int(arguments.get("limit", 60))
            rows = self._query_sql(
                """
                SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                FROM facts
                WHERE predicate IN ('EMITS_EVENT', 'LISTENS_EVENT')
                  AND (object = ? OR lower(object) LIKE lower(?) OR lower(subject) LIKE lower(?))
                ORDER BY confidence DESC
                LIMIT ?
                """,
                (normalized, f"%{normalized}%", f"%{event}%", limit),
            )
            return self._tool_result({"event": event, "count": len(rows), "rows": rows})

        if name == "rag_get_module_dependencies":
            module = str(arguments.get("module") or "").strip()
            limit = int(arguments.get("limit", 80))
            if module:
                normalized = module.lower()
                rows = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE predicate IN ('MODULE_DEPENDS_ON', 'IN_MODULE', 'DEPENDS_ON', 'WORKFLOW_OWNER')
                      AND (lower(subject) LIKE lower(?) OR lower(object) LIKE lower(?))
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (f"%module:{normalized}%", f"%module:{normalized}%", limit),
                )
            else:
                rows = self._query_sql(
                    """
                    SELECT subject, predicate, object, file_path, line_start, line_end, confidence, evidence
                    FROM facts
                    WHERE predicate IN ('MODULE_DEPENDS_ON', 'WORKFLOW_OWNER')
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (limit,),
                )
            return self._tool_result({"module": module or None, "count": len(rows), "rows": rows})

        if name == "rag_health":
            conn = self._connect()
            try:
                meta_rows = conn.execute("SELECT key, value FROM repo_meta").fetchall()
                meta = {str(row["key"]): str(row["value"]) for row in meta_rows}
                counts = {
                    "files": int(conn.execute("SELECT COUNT(*) FROM files").fetchone()[0]),
                    "chunks": int(conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]),
                    "facts": int(conn.execute("SELECT COUNT(*) FROM facts").fetchone()[0]),
                    "findings": int(conn.execute("SELECT COUNT(*) FROM findings").fetchone()[0]),
                }
                top_predicates = [
                    {"predicate": str(row["predicate"]), "count": int(row["c"])}
                    for row in conn.execute(
                        "SELECT predicate, COUNT(*) AS c FROM facts GROUP BY predicate ORDER BY c DESC LIMIT 20"
                    ).fetchall()
                ]
                key_predicates = {
                    "ROUTE_TO_HANDLER",
                    "ROUTE_CALLS",
                    "HANDLER_CALLS",
                    "READS_TABLE",
                    "WRITES_TABLE",
                    "SECURED_BY",
                    "EMITS_EVENT",
                    "LISTENS_EVENT",
                    "ACCEPTS_TYPE",
                    "RETURNS_TYPE",
                    "ROUTE_ACCEPTS_TYPE",
                    "ROUTE_RETURNS_TYPE",
                    "IDEMPOTENCY_KEY_TYPE",
                    "ROUTE_IDEMPOTENCY_KEY_TYPE",
                    "EXTENDS",
                    "IMPLEMENTS",
                    "TEST_COVERS",
                }
                coverage = {
                    str(row["predicate"]): int(row["c"])
                    for row in conn.execute(
                        """
                        SELECT predicate, COUNT(*) AS c
                        FROM facts
                        WHERE predicate IN ({})
                        GROUP BY predicate
                        """.format(",".join("?" for _ in key_predicates)),
                        tuple(sorted(key_predicates)),
                    ).fetchall()
                }
            finally:
                conn.close()
            head_commit = self._head_commit()
            indexed_commit = str(meta.get("commit_sha") or "")
            changed_since_indexed: List[str] = []
            indexable_changed_since_indexed: List[str] = []
            if indexed_commit:
                changed_since_indexed, indexable_changed_since_indexed = self._changed_files_since(indexed_commit)
            payload = {
                "meta": meta,
                "counts": counts,
                "top_predicates": top_predicates,
                "coverage": coverage,
                "head_commit": head_commit or None,
                "indexed_commit": indexed_commit or None,
                "is_stale": bool(head_commit and indexed_commit and head_commit != indexed_commit),
                "changed_since_indexed": len(changed_since_indexed),
                "indexable_changed_since_indexed": len(indexable_changed_since_indexed),
            }
            return self._tool_result(payload)

        if name == "rag_open_file_lines":
            file_path = str(arguments.get("file_path", "")).strip()
            if not file_path:
                return self._tool_error("'file_path' is required")
            line_start = int(arguments.get("line_start", 1))
            line_end = int(arguments.get("line_end", line_start + 80))
            line_start = max(1, line_start)
            line_end = max(line_start, line_end)

            abs_path = (self.repo_root / file_path).resolve()
            if self.repo_root not in abs_path.parents and abs_path != self.repo_root:
                return self._tool_error("file_path escapes repository root")
            if not abs_path.exists() or not abs_path.is_file():
                return self._tool_error("file not found")

            lines = abs_path.read_text(encoding="utf-8", errors="replace").splitlines()
            slice_lines = lines[line_start - 1 : line_end]
            rendered = "\n".join(f"{idx}: {line}" for idx, line in enumerate(slice_lines, start=line_start))
            return self._tool_result(
                {
                    "file_path": file_path,
                    "line_start": line_start,
                    "line_end": line_end,
                    "content": rendered,
                }
            )

        return self._tool_error(f"unknown tool: {name}")

    def handle(self, message: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        method = message.get("method")
        req_id = message.get("id")

        if method == "initialize":
            result = {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "erp-rag-mcp", "version": "0.1.0"},
            }
            return _json_result(result, req_id)

        if method == "initialized":
            return None

        if method == "tools/list":
            return _json_result({"tools": self.tools()}, req_id)

        if method == "tools/call":
            params = message.get("params") or {}
            name = str(params.get("name") or "")
            arguments = params.get("arguments") or {}
            if not isinstance(arguments, dict):
                arguments = {}
            result = self.call_tool(name, arguments)
            return _json_result(result, req_id)

        if method == "shutdown":
            self._shutdown_requested = True
            return _json_result(None, req_id)

        if method == "exit":
            self._shutdown_requested = True
            return None

        if req_id is None:
            return None

        return _json_error(-32601, f"Method not found: {method}", req_id)

    def serve(self) -> int:
        while not self._shutdown_requested:
            message = read_message()
            if message is None:
                break
            response = self.handle(message)
            if response is not None:
                write_message(response)
        return 0


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    db_path = Path(args.db_path)
    if not db_path.is_absolute():
        db_path = (repo_root / db_path).resolve()

    server = RagMcpServer(repo_root=repo_root, db_path=db_path, python_bin=args.python)
    return server.serve()


if __name__ == "__main__":
    sys.exit(main())
