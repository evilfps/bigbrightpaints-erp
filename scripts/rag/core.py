#!/usr/bin/env python3
"""Shared core utilities for hybrid RAG indexing and retrieval."""

from __future__ import annotations

import dataclasses
import hashlib
import json
import os
import re
import sqlite3
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Tuple


SCHEMA_SQL = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS repo_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS files (
  path TEXT PRIMARY KEY,
  language TEXT NOT NULL,
  module TEXT,
  sha1 TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  indexed_at TEXT NOT NULL,
  commit_sha TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS chunks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chunk_uid TEXT UNIQUE NOT NULL,
  file_path TEXT NOT NULL REFERENCES files(path) ON DELETE CASCADE,
  line_start INTEGER NOT NULL,
  line_end INTEGER NOT NULL,
  kind TEXT NOT NULL,
  symbol TEXT,
  language TEXT NOT NULL,
  module TEXT,
  content TEXT NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_path);
CREATE INDEX IF NOT EXISTS idx_chunks_symbol ON chunks(symbol);
CREATE INDEX IF NOT EXISTS idx_chunks_module ON chunks(module);
CREATE INDEX IF NOT EXISTS idx_chunks_kind ON chunks(kind);

CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
  content,
  symbol,
  file_path,
  module,
  kind,
  tokenize='unicode61'
);

CREATE TABLE IF NOT EXISTS facts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  fact_uid TEXT UNIQUE NOT NULL,
  subject TEXT NOT NULL,
  predicate TEXT NOT NULL,
  object TEXT NOT NULL,
  file_path TEXT,
  line_start INTEGER,
  line_end INTEGER,
  confidence REAL NOT NULL DEFAULT 0.5,
  evidence TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_facts_subject ON facts(subject);
CREATE INDEX IF NOT EXISTS idx_facts_predicate ON facts(predicate);
CREATE INDEX IF NOT EXISTS idx_facts_object ON facts(object);
CREATE INDEX IF NOT EXISTS idx_facts_file ON facts(file_path);
CREATE INDEX IF NOT EXISTS idx_facts_pred_obj ON facts(predicate, object);
CREATE INDEX IF NOT EXISTS idx_facts_pred_subj ON facts(predicate, subject);
CREATE INDEX IF NOT EXISTS idx_facts_subj_pred ON facts(subject, predicate);
CREATE INDEX IF NOT EXISTS idx_facts_obj_pred ON facts(object, predicate);
CREATE INDEX IF NOT EXISTS idx_facts_pred_conf ON facts(predicate, confidence DESC);
CREATE INDEX IF NOT EXISTS idx_facts_obj_pred_conf ON facts(object, predicate, confidence DESC);

CREATE TABLE IF NOT EXISTS findings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  finding_uid TEXT UNIQUE NOT NULL,
  finding_type TEXT NOT NULL,
  severity TEXT NOT NULL,
  title TEXT NOT NULL,
  message TEXT NOT NULL,
  file_path TEXT,
  line_start INTEGER,
  line_end INTEGER,
  symbol TEXT,
  source TEXT NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_findings_type ON findings(finding_type);
CREATE INDEX IF NOT EXISTS idx_findings_file ON findings(file_path);
"""


SUPPORTED_EXTENSIONS = {
    ".java",
    ".kt",
    ".kts",
    ".md",
    ".txt",
    ".yaml",
    ".yml",
    ".json",
    ".sql",
    ".properties",
    ".sh",
    ".py",
    ".xml",
    ".tsv",
    ".env",
}

EXCLUDE_DIR_NAMES = {
    ".git",
    "target",
    "node_modules",
    "artifacts",
    "__pycache__",
    ".tmp",
    ".security-baseline-20260211_160652",
    ".security-baseline-20260211_160856",
    ".security-baseline-20260212_012924",
    ".security-baseline-20260212_015027",
    ".security-baseline-20260212_023226",
    ".security-baseline-20260212_032329",
    ".security-baseline-20260212_063704",
    ".security-baseline-20260212_072958",
    ".security-baseline-20260212_075413",
    ".security-baseline-review-21c381ee",
}

INCLUDE_PREFIXES = (
    "erp-domain/src/main/java",
    "erp-domain/src/test/java",
    "erp-domain/src/main/resources",
    "erp-domain/src/test/resources",
    "erp-domain/docs",
    "docs",
    "scripts",
    "tickets",
)

INCLUDE_FILES = {
    "openapi.json",
    ".env.example",
    "erp-domain/pom.xml",
    "docker-compose.yml",
}


@dataclasses.dataclass(frozen=True)
class ChunkRecord:
    file_path: str
    line_start: int
    line_end: int
    kind: str
    symbol: str
    language: str
    module: str
    content: str
    metadata: Dict[str, Any]


@dataclasses.dataclass(frozen=True)
class FactRecord:
    subject: str
    predicate: str
    object: str
    file_path: str
    line_start: int
    line_end: int
    confidence: float
    evidence: str
    metadata: Dict[str, Any]


@dataclasses.dataclass(frozen=True)
class FindingRecord:
    finding_type: str
    severity: str
    title: str
    message: str
    file_path: str
    line_start: int
    line_end: int
    symbol: str
    source: str
    metadata: Dict[str, Any]


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def json_dumps(data: Dict[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=True, sort_keys=True)


def stable_sha1(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8", "ignore")).hexdigest()


def make_uid(*parts: str) -> str:
    return stable_sha1("||".join(parts))


def compute_file_sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def detect_language(path: str) -> str:
    lowered = path.lower()
    if lowered.endswith(".java"):
        return "java"
    if lowered.endswith(".kt") or lowered.endswith(".kts"):
        return "kotlin"
    if lowered.endswith(".py"):
        return "python"
    if lowered.endswith(".sh"):
        return "bash"
    if lowered.endswith(".sql"):
        return "sql"
    if lowered.endswith(".md"):
        return "markdown"
    if lowered.endswith(".yaml") or lowered.endswith(".yml"):
        return "yaml"
    if lowered.endswith(".properties"):
        return "properties"
    if lowered.endswith(".json"):
        return "json"
    if lowered.endswith(".xml"):
        return "xml"
    if lowered.endswith(".tsv"):
        return "tsv"
    if lowered.endswith(".env") or lowered.endswith(".env.example"):
        return "env"
    return "text"


def detect_module(path: str) -> str:
    normalized = path.replace("\\", "/")
    module_match = re.search(r"/modules/([^/]+)/", f"/{normalized}")
    if module_match:
        return module_match.group(1)
    if "/orchestrator/" in f"/{normalized}":
        return "orchestrator"
    if normalized.startswith("docs/"):
        return "docs"
    if normalized.startswith("scripts/"):
        return "scripts"
    if normalized.startswith("tickets/"):
        return "tickets"
    if normalized.startswith("erp-domain/docs/"):
        return "erp-domain-docs"
    return "root"


def should_index_path(rel_path: str) -> bool:
    rel_path = rel_path.replace("\\", "/")
    if rel_path.startswith("docs/ops_and_debug/LOGS/"):
        return False
    if rel_path.startswith("artifacts/"):
        return False
    if rel_path.startswith("tickets/"):
        name = Path(rel_path).name
        return name in {"ticket.yaml", "SUMMARY.md", "TIMELINE.md"}
    if rel_path in INCLUDE_FILES:
        return True
    if any(rel_path.startswith(prefix + "/") for prefix in INCLUDE_PREFIXES):
        suffix = Path(rel_path).suffix.lower()
        return suffix in SUPPORTED_EXTENSIONS or rel_path.endswith(".env.example")
    return False


def repo_relative_path(repo_root: Path, absolute_path: Path) -> str:
    return absolute_path.relative_to(repo_root).as_posix()


def list_repo_files(repo_root: Path, limit_files: int = 0) -> List[str]:
    selected: List[str] = []
    for root, dirs, files in os.walk(repo_root):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIR_NAMES]
        root_path = Path(root)
        for filename in files:
            abs_path = root_path / filename
            rel_path = repo_relative_path(repo_root, abs_path)
            if should_index_path(rel_path):
                selected.append(rel_path)
    selected.sort()
    if limit_files > 0:
        return selected[:limit_files]
    return selected


def read_text_file(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="latin-1", errors="replace")


def run_git_command(repo_root: Path, *args: str) -> str:
    cmd = ["git", "-C", str(repo_root), *args]
    proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        return ""
    return proc.stdout.strip()


def current_branch(repo_root: Path) -> str:
    branch = run_git_command(repo_root, "rev-parse", "--abbrev-ref", "HEAD")
    return branch or "unknown"


def current_commit(repo_root: Path) -> str:
    commit = run_git_command(repo_root, "rev-parse", "HEAD")
    return commit or "unknown"


def commit_exists(repo_root: Path, commit_ref: str) -> bool:
    if not commit_ref:
        return False
    output = run_git_command(repo_root, "rev-parse", "--verify", commit_ref)
    return bool(output)


def changed_files(repo_root: Path, diff_base: str) -> List[str]:
    output = run_git_command(repo_root, "diff", "--name-only", f"{diff_base}...HEAD")
    if not output:
        return []
    all_paths = [line.strip() for line in output.splitlines() if line.strip()]
    return [path for path in all_paths if should_index_path(path)]


class RagStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(db_path))
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys=ON;")

    def close(self) -> None:
        self.conn.close()

    def init_schema(self) -> None:
        self.conn.executescript(SCHEMA_SQL)
        self.conn.commit()

    def set_meta(self, key: str, value: str) -> None:
        self.conn.execute(
            "INSERT INTO repo_meta(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )

    def get_meta(self, key: str) -> str:
        row = self.conn.execute("SELECT value FROM repo_meta WHERE key = ?", (key,)).fetchone()
        return str(row["value"]) if row else ""

    def all_meta(self) -> Dict[str, str]:
        rows = self.conn.execute("SELECT key, value FROM repo_meta").fetchall()
        return {str(row["key"]): str(row["value"]) for row in rows}

    def list_indexed_files(self) -> List[str]:
        rows = self.conn.execute("SELECT path FROM files").fetchall()
        return [str(row["path"]) for row in rows]

    def get_indexed_sha1(self, file_path: str) -> str:
        row = self.conn.execute(
            "SELECT sha1 FROM files WHERE path = ?",
            (file_path,),
        ).fetchone()
        return str(row["sha1"]) if row else ""

    def remove_file_data(self, file_path: str) -> None:
        chunk_ids = [
            int(row["id"])
            for row in self.conn.execute(
                "SELECT id FROM chunks WHERE file_path = ?",
                (file_path,),
            ).fetchall()
        ]
        if chunk_ids:
            placeholders = ",".join("?" for _ in chunk_ids)
            self.conn.execute(
                f"DELETE FROM chunks_fts WHERE rowid IN ({placeholders})",
                chunk_ids,
            )
        self.conn.execute("DELETE FROM chunks WHERE file_path = ?", (file_path,))
        self.conn.execute("DELETE FROM facts WHERE file_path = ?", (file_path,))
        self.conn.execute("DELETE FROM findings WHERE file_path = ?", (file_path,))
        self.conn.execute("DELETE FROM files WHERE path = ?", (file_path,))

    def upsert_file(
        self,
        file_path: str,
        language: str,
        module: str,
        sha1: str,
        size_bytes: int,
        commit_sha: str,
        metadata: Dict[str, Any],
    ) -> None:
        self.conn.execute(
            """
            INSERT INTO files(path, language, module, sha1, size_bytes, indexed_at, commit_sha, metadata_json)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
              language=excluded.language,
              module=excluded.module,
              sha1=excluded.sha1,
              size_bytes=excluded.size_bytes,
              indexed_at=excluded.indexed_at,
              commit_sha=excluded.commit_sha,
              metadata_json=excluded.metadata_json
            """,
            (
                file_path,
                language,
                module,
                sha1,
                size_bytes,
                utc_now_iso(),
                commit_sha,
                json_dumps(metadata),
            ),
        )

    def insert_chunks(self, chunks: Sequence[ChunkRecord]) -> int:
        inserted = 0
        for chunk in chunks:
            chunk_uid = make_uid(
                chunk.file_path,
                str(chunk.line_start),
                str(chunk.line_end),
                chunk.kind,
                chunk.symbol,
                stable_sha1(chunk.content),
            )
            cursor = self.conn.execute(
                """
                INSERT OR IGNORE INTO chunks(
                  chunk_uid, file_path, line_start, line_end, kind, symbol, language, module, content, metadata_json
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    chunk_uid,
                    chunk.file_path,
                    chunk.line_start,
                    chunk.line_end,
                    chunk.kind,
                    chunk.symbol,
                    chunk.language,
                    chunk.module,
                    chunk.content,
                    json_dumps(chunk.metadata),
                ),
            )
            if cursor.rowcount <= 0:
                continue
            chunk_id = int(cursor.lastrowid)
            self.conn.execute(
                "INSERT OR REPLACE INTO chunks_fts(rowid, content, symbol, file_path, module, kind) VALUES(?, ?, ?, ?, ?, ?)",
                (
                    chunk_id,
                    chunk.content,
                    chunk.symbol,
                    chunk.file_path,
                    chunk.module,
                    chunk.kind,
                ),
            )
            inserted += 1
        return inserted

    def insert_facts(self, facts: Sequence[FactRecord]) -> int:
        inserted = 0
        for fact in facts:
            fact_uid = make_uid(
                fact.subject,
                fact.predicate,
                fact.object,
                fact.file_path,
                str(fact.line_start),
                str(fact.line_end),
            )
            cursor = self.conn.execute(
                """
                INSERT OR IGNORE INTO facts(
                  fact_uid, subject, predicate, object, file_path, line_start, line_end, confidence, evidence, metadata_json
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    fact_uid,
                    fact.subject,
                    fact.predicate,
                    fact.object,
                    fact.file_path,
                    fact.line_start,
                    fact.line_end,
                    fact.confidence,
                    fact.evidence,
                    json_dumps(fact.metadata),
                ),
            )
            inserted += 1 if cursor.rowcount > 0 else 0
        return inserted

    def replace_findings(self, source: str, findings: Sequence[FindingRecord]) -> int:
        self.conn.execute("DELETE FROM findings WHERE source = ?", (source,))
        inserted = 0
        for finding in findings:
            finding_uid = make_uid(
                finding.source,
                finding.finding_type,
                finding.file_path,
                str(finding.line_start),
                str(finding.line_end),
                finding.title,
                finding.message,
            )
            cursor = self.conn.execute(
                """
                INSERT OR IGNORE INTO findings(
                  finding_uid, finding_type, severity, title, message, file_path, line_start,
                  line_end, symbol, source, metadata_json, created_at
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    finding_uid,
                    finding.finding_type,
                    finding.severity,
                    finding.title,
                    finding.message,
                    finding.file_path,
                    finding.line_start,
                    finding.line_end,
                    finding.symbol,
                    finding.source,
                    json_dumps(finding.metadata),
                    utc_now_iso(),
                ),
            )
            inserted += 1 if cursor.rowcount > 0 else 0
        return inserted

    def commit(self) -> None:
        self.conn.commit()


def compact_excerpt(text: str, max_lines: int = 18, max_chars: int = 1800) -> str:
    lines = text.splitlines()
    excerpt = "\n".join(lines[:max_lines])
    if len(excerpt) > max_chars:
        excerpt = excerpt[: max_chars - 3] + "..."
    return excerpt


def word_tokens(query: str) -> List[str]:
    tokens = re.findall(r"[A-Za-z0-9_.:/-]+", query.lower())
    stopwords = {
        "a",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "by",
        "for",
        "from",
        "how",
        "i",
        "in",
        "is",
        "it",
        "of",
        "on",
        "or",
        "the",
        "to",
        "was",
        "what",
        "where",
        "which",
        "why",
        "with",
    }
    return [token for token in tokens if len(token) >= 2 and token not in stopwords]
