#!/usr/bin/env python3
"""Hybrid RAG indexer for orchestrator_erp.

Builds two retrieval layers:
- chunk index (SQLite FTS5) for lexical grounding
- structured facts graph for cross-module relationship traversal
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

from core import (
    ChunkRecord,
    FactRecord,
    RagStore,
    changed_files,
    commit_exists,
    compact_excerpt,
    compute_file_sha1,
    current_branch,
    current_commit,
    detect_language,
    detect_module,
    list_repo_files,
    read_text_file,
    word_tokens,
)


CONTROL_KEYWORDS = {
    "if",
    "for",
    "while",
    "switch",
    "catch",
    "do",
    "synchronized",
    "try",
    "return",
    "throw",
    "new",
    "assert",
}

METHOD_MODIFIERS = {
    "public",
    "protected",
    "private",
    "static",
    "final",
    "abstract",
    "synchronized",
    "native",
    "strictfp",
    "default",
}

JAVA_TYPE_KEYWORD_NOISE = {
    "extends",
    "super",
    "final",
    "var",
}

JAVA_TYPE_TOKEN_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_$.]*$")

METHOD_NAME_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(")
CLASS_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)")
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;")
IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z0-9_.*]+)\s*;")
MAPPING_RE = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\s*(\((.*?)\))?")
REQUEST_MAPPING_RE = re.compile(r"@RequestMapping\s*(\((.*?)\))?")
CONFIG_PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z0-9_.-]+)(?::[^}]*)?\}")
GET_PROPERTY_RE = re.compile(r"getProperty\(\s*\"([A-Za-z0-9_.-]+)\"\s*\)")
GET_ENV_RE = re.compile(r"getenv\(\s*\"([A-Za-z0-9_.-]+)\"\s*\)")
TABLE_RE = re.compile(r"(?i)\b(?:from|join|into|update|table)\s+([a-zA-Z0-9_.\"]+)")
ANNOTATION_NAME_RE = re.compile(r"@([A-Za-z_][A-Za-z0-9_.]*)")
FIELD_DECL_RE = re.compile(
    r"^(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([A-Za-z0-9_$.<>, ?\[\]]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=.*)?;$"
)
QUALIFIED_CALL_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(")
TEXTBLOCK_RE = re.compile(r'"""(.*?)"""', re.DOTALL)
STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
METHOD_LEVEL_ROUTE_RE = re.compile(r"(?i)\b(GET|POST|PUT|PATCH|DELETE)\s+(/[A-Za-z0-9_/{}/:.-]+)")
PREAUTHORIZE_RE = re.compile(r'@PreAuthorize\(\s*"([^"]+)"\s*\)')
SCHEDULED_KV_RE = re.compile(
    r"(cron|fixedDelay|fixedRate|fixedDelayString|fixedRateString)\s*=\s*\"?([^,\")]+)\"?"
)
RETRY_KV_RE = re.compile(r"(maxAttempts|delay|maxDelay|multiplier)\s*=\s*([A-Za-z0-9_.\"-]+)")

JAVA_PRIMITIVE_TYPES = {
    "byte",
    "short",
    "int",
    "long",
    "float",
    "double",
    "boolean",
    "char",
    "void",
}

JAVA_LANG_SIMPLE_TYPES = {
    "String",
    "Integer",
    "Long",
    "Boolean",
    "Double",
    "Float",
    "Short",
    "Byte",
    "Character",
    "Object",
    "List",
    "Map",
    "Set",
    "Optional",
}

COMPONENT_ROLE_ANNOTATIONS = {
    "RestController": "rest_controller",
    "Controller": "controller",
    "ControllerAdvice": "controller_advice",
    "Service": "service",
    "Repository": "repository",
    "Component": "component",
}

NOISY_CALL_TOKENS = {
    "ifPresent",
    "orElseThrow",
    "map",
    "stream",
    "collect",
    "toList",
    "of",
    "empty",
    "builder",
    "build",
    "ok",
    "status",
    "body",
    "success",
    "failure",
    "now",
    "valueOf",
}

SECURITY_ANNOTATIONS = {"PreAuthorize", "Secured", "RolesAllowed"}

IDEMPOTENCY_PARAM_NAME_RE = re.compile(
    r"(idempot|requestid|eventid|dedup|dedupe|correlationid|messageid|operationid)",
    re.IGNORECASE,
)
JAVA_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
JAVA_LINE_COMMENT_RE = re.compile(r"//.*")
CONTROL_FLOW_TOKEN_RE = re.compile(r"\b(if|for|while|switch|catch|throw|return|try|case)\b", re.IGNORECASE)
METHOD_LOGIC_NOISE = {
    "get",
    "set",
    "put",
    "add",
    "remove",
    "list",
    "find",
    "save",
    "update",
    "delete",
}


@dataclass
class ParseOutcome:
    chunks: List[ChunkRecord]
    facts: List[FactRecord]
    class_symbols: Dict[str, str]
    test_mentions: List[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build hybrid RAG index (chunks + graph facts) for orchestrator_erp",
    )
    parser.add_argument(
        "--repo-root",
        default=".",
        help="Repository root (default: current directory)",
    )
    parser.add_argument(
        "--db-path",
        default=".tmp/rag/context_index.sqlite",
        help="SQLite index path",
    )
    parser.add_argument(
        "--diff-base",
        default="",
        help="Diff base commit for incremental indexing (defaults to last indexed commit)",
    )
    parser.add_argument(
        "--changed-only",
        action="store_true",
        help="Index only files changed since --diff-base",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-index files even if sha1 is unchanged",
    )
    parser.add_argument(
        "--limit-files",
        type=int,
        default=0,
        help="Cap files processed (useful for smoke tests)",
    )
    parser.add_argument(
        "--max-file-bytes",
        type=int,
        default=1_200_000,
        help="Skip oversized files above this byte threshold (default: 1200000). Use 0 to disable.",
    )
    parser.add_argument(
        "--no-prune-missing",
        action="store_true",
        help="Disable pruning indexed files that are no longer part of the full candidate set.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print per-file progress",
    )
    return parser.parse_args()


def normalize_route_path(raw_path: str) -> str:
    value = raw_path.strip().strip('"').strip("'")
    if not value:
        return ""
    if not value.startswith("/"):
        value = "/" + value
    value = re.sub(r"//+", "/", value)
    return value


def extract_annotation_paths(annotation: str) -> List[str]:
    contents_match = re.search(r"\((.*)\)", annotation)
    if not contents_match:
        return [""]
    inside = contents_match.group(1)
    quoted = re.findall(r'"([^"]+)"', inside)
    if quoted:
        return [normalize_route_path(value) for value in quoted]
    value_match = re.search(r"(?:value|path)\s*=\s*\"([^\"]+)\"", inside)
    if value_match:
        return [normalize_route_path(value_match.group(1))]
    return [""]


def extract_mapping_methods(annotation: str) -> List[str]:
    mapping = MAPPING_RE.search(annotation)
    if mapping:
        prefix = mapping.group(1)
        if prefix == "Request":
            method_match = re.findall(r"RequestMethod\.([A-Z]+)", annotation)
            return method_match or ["ANY"]
        return [prefix.upper()]

    request_mapping = REQUEST_MAPPING_RE.search(annotation)
    if request_mapping:
        method_match = re.findall(r"RequestMethod\.([A-Z]+)", annotation)
        return method_match or ["ANY"]

    return []


def join_route(base_path: str, method_path: str) -> str:
    base = normalize_route_path(base_path)
    method = normalize_route_path(method_path)
    if not base and not method:
        return "/"
    if not base:
        return method or "/"
    if not method:
        return base
    if base.endswith("/"):
        base = base[:-1]
    return normalize_route_path(base + "/" + method.lstrip("/"))


def looks_like_method_signature(signature: str) -> bool:
    stripped = " ".join(signature.strip().split())
    if not stripped:
        return False
    if "(" not in stripped or ")" not in stripped or "{" not in stripped:
        return False
    if stripped.endswith(";"):
        return False
    lowered = stripped.lower()
    if lowered.startswith("@"):  # annotation only
        return False
    control_prefixes = tuple(f"{keyword} " for keyword in CONTROL_KEYWORDS)
    if lowered.startswith(control_prefixes):
        return False
    if " class " in lowered or lowered.startswith("class "):
        return False
    return METHOD_NAME_RE.search(stripped) is not None


def extract_method_name(signature: str) -> str:
    normalized = " ".join(signature.strip().split())
    left_paren = normalized.find("(")
    if left_paren > 0:
        prefix = normalized[:left_paren]
        tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", prefix)
        if tokens:
            return tokens[-1]
    matches = METHOD_NAME_RE.findall(signature)
    if not matches:
        return "<unknown_method>"
    return matches[0]


def looks_like_method_declaration(signature: str) -> bool:
    stripped = " ".join(signature.strip().split())
    if not stripped:
        return False
    if "(" not in stripped or ")" not in stripped or ";" not in stripped:
        return False
    if " class " in stripped.lower() or stripped.lower().startswith("class "):
        return False
    if stripped.startswith("@"):
        return False
    lowered = stripped.lower()
    control_prefixes = tuple(f"{keyword} " for keyword in CONTROL_KEYWORDS)
    if lowered.startswith(control_prefixes):
        return False
    return METHOD_NAME_RE.search(stripped) is not None


def collect_annotation_block(lines: Sequence[str], start_idx: int) -> Tuple[str, int]:
    parts = [lines[start_idx].strip()]
    idx = start_idx
    paren_balance = parts[0].count("(") - parts[0].count(")")
    while paren_balance > 0 and idx + 1 < len(lines):
        idx += 1
        nxt = lines[idx].strip()
        parts.append(nxt)
        paren_balance += nxt.count("(") - nxt.count(")")
    return " ".join(part for part in parts if part), idx


def annotation_simple_name(annotation: str) -> str:
    match = ANNOTATION_NAME_RE.search(annotation.strip())
    if not match:
        return ""
    return match.group(1).split(".")[-1]


def split_top_level_commas(text: str) -> List[str]:
    parts: List[str] = []
    buf: List[str] = []
    depth_angle = 0
    depth_paren = 0
    for ch in text:
        if ch == "<":
            depth_angle += 1
        elif ch == ">":
            depth_angle = max(0, depth_angle - 1)
        elif ch == "(":
            depth_paren += 1
        elif ch == ")":
            depth_paren = max(0, depth_paren - 1)
        if ch == "," and depth_angle == 0 and depth_paren == 0:
            part = "".join(buf).strip()
            if part:
                parts.append(part)
            buf = []
            continue
        buf.append(ch)
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts


def strip_java_annotations(value: str) -> str:
    cleaned = value
    while True:
        updated = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", cleaned).strip()
        if updated == cleaned:
            return cleaned
        cleaned = updated


def parse_method_parameter_types(signature: str) -> List[str]:
    return [raw_type for raw_type, _ in parse_method_parameters(signature)]


def parse_method_parameters(signature: str) -> List[Tuple[str, str]]:
    left = signature.find("(")
    right = signature.rfind(")")
    if left == -1 or right == -1 or right <= left:
        return []
    inside = signature[left + 1 : right].strip()
    if not inside:
        return []
    params = split_top_level_commas(inside)
    parsed: List[Tuple[str, str]] = []
    for raw in params:
        candidate = strip_java_annotations(raw)
        candidate = candidate.replace("final ", "").strip()
        if not candidate:
            continue
        tokens = candidate.split()
        if len(tokens) <= 1:
            continue
        param_name = tokens[-1].replace("...", "").strip()
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", param_name):
            continue
        type_token = " ".join(tokens[:-1]).strip()
        if type_token:
            parsed.append((type_token, param_name))
    return parsed


def is_idempotency_parameter_name(name: str) -> bool:
    token = re.sub(r"[^A-Za-z0-9]", "", name or "").strip().lower()
    if not token:
        return False
    return bool(IDEMPOTENCY_PARAM_NAME_RE.search(token))


def idempotency_type_bucket(type_name: str) -> str:
    normalized = str(type_name or "").strip().lower()
    if normalized.startswith("type:"):
        normalized = normalized[len("type:") :]
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


def strip_java_comments(text: str) -> str:
    body = JAVA_BLOCK_COMMENT_RE.sub(" ", text)
    lines = [JAVA_LINE_COMMENT_RE.sub("", line) for line in body.splitlines()]
    return "\n".join(lines)


def method_logic_fingerprint(method_body: str) -> Tuple[str, int]:
    body = strip_java_comments(method_body)
    call_tokens = [
        token.lower()
        for token in extract_method_calls(body)
        if token.lower() not in METHOD_LOGIC_NOISE
    ]
    config_tokens = [key.lower() for key in parse_config_keys_from_text(body)]
    control_tokens = [token.lower() for token in CONTROL_FLOW_TOKEN_RE.findall(body)]
    signal_tokens: List[str] = []
    signal_tokens.extend(call_tokens[:40])
    signal_tokens.extend(sorted(config_tokens)[:20])
    signal_tokens.extend(sorted(control_tokens)[:24])
    non_empty_lines = [line for line in body.splitlines() if line.strip()]
    signal_tokens.append(f"line_bucket:{min(40, max(1, len(non_empty_lines) // 5))}")
    if len(signal_tokens) < 8:
        return "", len(signal_tokens)
    digest_input = "|".join(signal_tokens)
    digest = hashlib.sha1(digest_input.encode("utf-8", "ignore")).hexdigest()[:20]
    return digest, len(signal_tokens)


def strip_leading_generic_declaration(text: str) -> str:
    candidate = text.strip()
    if not candidate.startswith("<"):
        return candidate
    depth = 0
    for idx, ch in enumerate(candidate):
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
            if depth == 0:
                return candidate[idx + 1 :].strip()
    return candidate


def parse_method_return_type(signature: str, method_name: str, class_name: str = "") -> str:
    normalized = " ".join(signature.strip().split())
    if not normalized or "(" not in normalized:
        return ""
    if class_name and method_name == class_name:
        return ""

    marker = f"{method_name}("
    marker_idx = normalized.find(marker)
    if marker_idx == -1:
        marker_idx = normalized.find(method_name)
        if marker_idx <= 0:
            return ""

    prefix = strip_java_annotations(normalized[:marker_idx]).strip()
    if not prefix:
        return ""

    tokens = prefix.split()
    while tokens and tokens[0] in METHOD_MODIFIERS:
        tokens.pop(0)
    if not tokens:
        return ""

    return_type = strip_leading_generic_declaration(" ".join(tokens))
    return return_type.strip()


def extract_java_type_candidates(raw_type: str) -> List[str]:
    cleaned = strip_java_annotations(raw_type)
    cleaned = cleaned.replace("...", "[]")
    tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_$.]*", cleaned)
    out: List[str] = []
    seen: Set[str] = set()
    for token in tokens:
        if token in METHOD_MODIFIERS or token in JAVA_TYPE_KEYWORD_NOISE:
            continue
        if token in JAVA_PRIMITIVE_TYPES:
            continue
        if len(token) == 1 and token.isupper():
            continue
        if "." in token:
            leaf = token.split(".")[-1]
            if not leaf or not leaf[0].isupper():
                continue
        elif not token[0].isupper():
            continue
        if token in seen:
            continue
        seen.add(token)
        out.append(token)
    return out


def collect_declaration_signature(lines: Sequence[str], start_idx: int, max_lines: int = 8) -> str:
    parts: List[str] = []
    idx = start_idx
    while idx < len(lines) and len(parts) < max_lines:
        segment = lines[idx].strip()
        if segment:
            parts.append(segment)
        if "{" in segment:
            break
        idx += 1
    return " ".join(parts)


def extract_class_relationship_types(signature: str, class_kind: str) -> Tuple[List[str], List[str]]:
    normalized = re.sub(r"\s+", " ", signature.strip())
    header = normalized.split("{", 1)[0].strip()
    if not header:
        return [], []

    extends_types: List[str] = []
    implements_types: List[str] = []

    if " extends " in header:
        tail = header.split(" extends ", 1)[1]
        if " implements " in tail:
            extends_part, tail = tail.split(" implements ", 1)
            extends_types.extend(split_top_level_commas(extends_part))
            implements_types.extend(split_top_level_commas(tail))
        else:
            extends_types.extend(split_top_level_commas(tail))
    elif " implements " in header:
        tail = header.split(" implements ", 1)[1]
        implements_types.extend(split_top_level_commas(tail))

    # Interface declarations can have multiple "extends" entries; keep them as hierarchy edges.
    if class_kind == "interface":
        extends_types = [token for token in extends_types if token]

    extends_types = [normalize_java_type(token) for token in extends_types if token.strip()]
    implements_types = [normalize_java_type(token) for token in implements_types if token.strip()]
    return extends_types, implements_types


def strip_java_generics(raw_type: str) -> str:
    out: List[str] = []
    depth = 0
    for ch in raw_type:
        if ch == "<":
            depth += 1
            continue
        if ch == ">":
            depth = max(0, depth - 1)
            continue
        if depth == 0:
            out.append(ch)
    return "".join(out)


def normalize_java_type(raw_type: str) -> str:
    cleaned = strip_java_annotations(raw_type)
    cleaned = cleaned.replace("final ", "").replace("...", "[]").strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    cleaned = strip_java_generics(cleaned)
    cleaned = cleaned.replace("?", "").replace("extends ", "").replace("super ", "")
    cleaned = cleaned.strip()
    if cleaned.endswith("[]"):
        cleaned = cleaned[:-2].strip()
    return cleaned


def build_import_lookups(imports: Sequence[str]) -> Tuple[Dict[str, str], List[str]]:
    direct: Dict[str, str] = {}
    wildcards: List[str] = []
    for imported in imports:
        if imported.endswith(".*"):
            wildcards.append(imported[:-2])
            continue
        simple = imported.rsplit(".", 1)[-1]
        direct[simple] = imported
    return direct, wildcards


def resolve_java_type_symbol(
    raw_type: str,
    package_name: str,
    direct_imports: Dict[str, str],
    wildcard_imports: Sequence[str],
) -> str:
    normalized = normalize_java_type(raw_type)
    if not normalized:
        return ""
    if not JAVA_TYPE_TOKEN_RE.match(normalized):
        return ""
    if normalized in JAVA_PRIMITIVE_TYPES:
        return normalized
    if "." in normalized:
        if normalized.startswith(("java.", "javax.", "jakarta.", "org.", "com.")):
            return normalized
        root = normalized.split(".", 1)[0]
        if root in direct_imports:
            imported_root = direct_imports[root]
            suffix = normalized.split(".", 1)[1]
            return f"{imported_root}.{suffix}"
        if package_name:
            return f"{package_name}.{normalized}"
        return normalized
    if normalized in direct_imports:
        return direct_imports[normalized]
    if normalized in JAVA_LANG_SIMPLE_TYPES:
        return f"java.lang.{normalized}"
    for base in wildcard_imports:
        if base.startswith("com.bigbrightpaints.erp."):
            return f"{base}.{normalized}"
    if package_name:
        return f"{package_name}.{normalized}"
    return normalized


def extract_config_keys_from_annotations(annotations: Sequence[str]) -> List[str]:
    keys: List[str] = []
    for annotation in annotations:
        if annotation_simple_name(annotation) != "Value":
            continue
        keys.extend(CONFIG_PLACEHOLDER_RE.findall(annotation))
    return sorted(set(keys))


def extract_query_strings_from_annotations(annotations: Sequence[str]) -> List[Tuple[str, bool]]:
    queries: List[Tuple[str, bool]] = []
    for annotation in annotations:
        if annotation_simple_name(annotation) != "Query":
            continue
        native_query = "nativequery = true" in annotation.lower().replace(" ", "")
        parts: List[str] = []
        for block in TEXTBLOCK_RE.findall(annotation):
            if block.strip():
                parts.append(block.strip())
        if not parts:
            for literal in STRING_LITERAL_RE.findall(annotation):
                decoded = literal.encode("utf-8").decode("unicode_escape")
                if decoded.strip():
                    parts.append(decoded.strip())
        if parts:
            queries.append(("\n".join(parts), native_query))
    return queries


def query_predicate_for_text(query_text: str) -> str:
    lowered = query_text.lower()
    if any(keyword in lowered for keyword in ("insert into", "update ", "delete from", "merge into", "alter table")):
        return "WRITES_TABLE"
    if any(keyword in lowered for keyword in ("select ", " from ")):
        return "READS_TABLE"
    return "QUERY_EXECUTES"


def extract_query_targets(query_text: str, native_query: bool) -> List[str]:
    targets: List[str] = []
    for token in TABLE_RE.findall(query_text):
        cleaned = token.strip().strip('"').strip("`")
        if not cleaned:
            continue
        if native_query:
            targets.append(f"table:{cleaned}")
        elif cleaned and cleaned[0].isupper():
            targets.append(f"entity:{cleaned}")
        else:
            targets.append(f"table:{cleaned}")
    # JPQL can miss table/entity with TABLE_RE in edge cases, fall back to first FROM token.
    if not targets:
        from_match = re.search(r"(?i)\bfrom\s+([A-Za-z0-9_.]+)", query_text)
        if from_match:
            token = from_match.group(1).strip()
            if token:
                if native_query or token[0].islower():
                    targets.append(f"table:{token}")
                else:
                    targets.append(f"entity:{token}")
    deduped: List[str] = []
    seen = set()
    for target in targets:
        if target in seen:
            continue
        seen.add(target)
        deduped.append(target)
    return deduped


def extract_dependency_calls(method_body: str, class_field_types: Dict[str, str]) -> List[Tuple[str, str, str]]:
    calls: List[Tuple[str, str, str]] = []
    for qualifier, method_name in QUALIFIED_CALL_RE.findall(method_body):
        dependency_symbol = class_field_types.get(qualifier)
        if not dependency_symbol:
            continue
        calls.append((qualifier, method_name, f"{dependency_symbol}#{method_name}"))
    return sorted(set(calls))


def extract_published_event_symbols(method_body: str) -> List[str]:
    symbols: List[str] = []
    for match in re.finditer(r"publishEvent\(\s*(?:new\s+)?([A-Za-z_][A-Za-z0-9_$.]*)", method_body):
        token = match.group(1).strip()
        if not token:
            continue
        last_segment = token.split(".")[-1]
        if not last_segment or not last_segment[0].isupper():
            continue
        symbols.append(token)
    return sorted(set(symbols))


def extract_security_expression(annotation: str) -> str:
    ann_name = annotation_simple_name(annotation)
    preauth = PREAUTHORIZE_RE.search(annotation)
    if preauth:
        return preauth.group(1).strip()
    if ann_name in {"Secured", "RolesAllowed"}:
        values = re.findall(r'"([^"]+)"', annotation)
        if values:
            return f"{ann_name}({', '.join(values)})"
    return ""


def parse_config_keys_from_text(text: str) -> List[str]:
    keys: List[str] = []
    for key in CONFIG_PLACEHOLDER_RE.findall(text):
        keys.append(key)
    for key in GET_PROPERTY_RE.findall(text):
        keys.append(key)
    for key in GET_ENV_RE.findall(text):
        keys.append(key)
    return sorted(set(keys))


def build_fact(
    subject: str,
    predicate: str,
    obj: str,
    file_path: str,
    line_start: int,
    line_end: int,
    confidence: float,
    evidence: str,
    metadata: Optional[Dict[str, Any]] = None,
) -> FactRecord:
    return FactRecord(
        subject=subject,
        predicate=predicate,
        object=obj,
        file_path=file_path,
        line_start=line_start,
        line_end=line_end,
        confidence=confidence,
        evidence=evidence,
        metadata=metadata or {},
    )


def parse_markdown(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    section_start = 1
    section_title = "root"
    section_lines: List[str] = []

    def flush_section(end_line: int) -> None:
        if not section_lines:
            return
        content = "\n".join(section_lines).strip()
        if not content:
            return
        symbol = f"doc:{rel_path}#{section_title}"
        kind = "doc_section"
        source_type = "docs" if rel_path.startswith("docs/") else "code_docs"
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=section_start,
                line_end=end_line,
                kind=kind,
                symbol=symbol,
                language=language,
                module=module,
                content=content,
                metadata={"source_type": source_type, "section": section_title},
            )
        )

    for idx, line in enumerate(lines, start=1):
        heading_match = re.match(r"^(#{1,6})\s+(.+)$", line)
        if heading_match:
            flush_section(idx - 1)
            section_start = idx
            section_title = heading_match.group(2).strip().lower().replace(" ", "-")
            section_lines = [line]
        else:
            section_lines.append(line)

    flush_section(len(lines))

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def ticket_id_from_path(rel_path: str) -> str:
    parts = rel_path.replace("\\", "/").split("/")
    if len(parts) >= 3 and parts[0] == "tickets" and parts[1].startswith("TKT-"):
        return parts[1]
    return ""


def yaml_scalar(value: str) -> str:
    raw = value.strip()
    if not raw:
        return ""
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in {"'", '"'}:
        return raw[1:-1]
    return raw


def parse_ticket_yaml(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    ticket_id = ticket_id_from_path(rel_path)
    ticket_symbol = f"ticket:{ticket_id}" if ticket_id else f"ticket_file:{rel_path}"

    top_level_values: Dict[str, str] = {}
    slices: List[Dict[str, Any]] = []

    in_slices = False
    current_slice: Optional[Dict[str, Any]] = None
    current_list_key = ""

    def flush_slice(end_line: int) -> None:
        nonlocal current_slice
        if not current_slice:
            return
        current_slice["line_end"] = max(int(current_slice.get("line_start", 1)), end_line)
        slices.append(current_slice)
        current_slice = None

    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        if not in_slices and re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*:", stripped):
            key, raw_value = stripped.split(":", 1)
            key = key.strip()
            value = yaml_scalar(raw_value)
            if key == "ticket_id" and value:
                ticket_id = value
                ticket_symbol = f"ticket:{ticket_id}"
            if key == "slices" and raw_value.strip() == "":
                in_slices = True
                continue
            top_level_values[key] = value
            continue

        if in_slices:
            if not line.startswith(" ") and re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*:", stripped):
                flush_slice(idx - 1)
                in_slices = False
                key, raw_value = stripped.split(":", 1)
                top_level_values[key.strip()] = yaml_scalar(raw_value)
                continue

            new_slice = re.match(r"^\s*-\s+id\s*:\s*(.+)$", line)
            if new_slice:
                flush_slice(idx - 1)
                current_slice = {
                    "id": yaml_scalar(new_slice.group(1)),
                    "line_start": idx,
                    "line_end": idx,
                }
                current_list_key = ""
                continue

            if not current_slice:
                continue

            field_match = re.match(r"^\s{2,}([A-Za-z0-9_]+)\s*:\s*(.*)$", line)
            if field_match:
                key = field_match.group(1).strip()
                raw_value = field_match.group(2)
                value = yaml_scalar(raw_value)
                current_slice["line_end"] = idx
                if raw_value.strip() == "":
                    current_slice[key] = []
                    current_list_key = key
                else:
                    current_slice[key] = value
                    current_list_key = ""
                continue

            list_item_match = re.match(r"^\s{2,}-\s*(.+)$", line)
            if list_item_match and current_list_key:
                item = yaml_scalar(list_item_match.group(1))
                existing = current_slice.get(current_list_key)
                if not isinstance(existing, list):
                    existing = []
                    current_slice[current_list_key] = existing
                if item:
                    existing.append(item)
                current_slice["line_end"] = idx

    if in_slices:
        flush_slice(len(lines))

    top_ticket_id = top_level_values.get("ticket_id", "").strip()
    if top_ticket_id:
        ticket_id = top_ticket_id
        ticket_symbol = f"ticket:{ticket_id}"

    chunks.append(
        ChunkRecord(
            file_path=rel_path,
            line_start=1,
            line_end=max(1, len(lines)),
            kind="ticket_record",
            symbol=ticket_symbol,
            language=language,
            module=module,
            content=text.strip(),
            metadata={
                "source_type": "ticket_yaml",
                "ticket_id": ticket_id,
            },
        )
    )

    facts.append(
        build_fact(
            subject=ticket_symbol,
            predicate="DEFINED_IN",
            obj=f"file:{rel_path}",
            file_path=rel_path,
            line_start=1,
            line_end=max(1, len(lines)),
            confidence=0.99,
            evidence=rel_path,
        )
    )

    for key, predicate in (
        ("status", "TICKET_STATUS"),
        ("title", "TICKET_TITLE"),
        ("goal", "TICKET_GOAL"),
        ("priority", "TICKET_PRIORITY"),
        ("base_branch", "TICKET_BASE_BRANCH"),
        ("updated_at", "TICKET_UPDATED_AT"),
        ("created_at", "TICKET_CREATED_AT"),
    ):
        value = top_level_values.get(key, "").strip()
        if not value:
            continue
        facts.append(
            build_fact(
                subject=ticket_symbol,
                predicate=predicate,
                obj=value,
                file_path=rel_path,
                line_start=1,
                line_end=max(1, len(lines)),
                confidence=0.97,
                evidence=f"{key}: {value}",
            )
        )

    for slice_item in slices:
        slice_id = str(slice_item.get("id") or "").strip()
        if not slice_id:
            continue
        slice_symbol = f"slice:{ticket_id}:{slice_id}" if ticket_id else f"slice:{slice_id}"
        line_start = int(slice_item.get("line_start") or 1)
        line_end = int(slice_item.get("line_end") or line_start)

        summary_parts = [f"id={slice_id}"]
        for key in ("primary_agent", "status", "lane", "branch", "worktree_path"):
            value = slice_item.get(key)
            if isinstance(value, str) and value.strip():
                summary_parts.append(f"{key}={value.strip()}")

        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=line_start,
                line_end=max(line_start, line_end),
                kind="ticket_slice",
                symbol=slice_symbol,
                language=language,
                module=module,
                content=" | ".join(summary_parts),
                metadata={
                    "source_type": "ticket_yaml_slice",
                    "ticket_id": ticket_id,
                    "slice_id": slice_id,
                },
            )
        )

        facts.append(
            build_fact(
                subject=ticket_symbol,
                predicate="HAS_SLICE",
                obj=slice_symbol,
                file_path=rel_path,
                line_start=line_start,
                line_end=max(line_start, line_end),
                confidence=0.99,
                evidence=slice_id,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="BELONGS_TO",
                obj=ticket_symbol,
                file_path=rel_path,
                line_start=line_start,
                line_end=max(line_start, line_end),
                confidence=0.99,
                evidence=ticket_id or rel_path,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="SLICE_ID",
                obj=slice_id,
                file_path=rel_path,
                line_start=line_start,
                line_end=max(line_start, line_end),
                confidence=0.98,
                evidence=slice_id,
            )
        )

        agent = str(slice_item.get("primary_agent") or "").strip()
        if agent:
            facts.append(
                build_fact(
                    subject=slice_symbol,
                    predicate="SLICE_AGENT",
                    obj=f"agent:{agent}",
                    file_path=rel_path,
                    line_start=line_start,
                    line_end=max(line_start, line_end),
                    confidence=0.98,
                    evidence=agent,
                )
            )

        for field, predicate in (
            ("status", "SLICE_STATUS"),
            ("lane", "SLICE_LANE"),
            ("branch", "SLICE_BRANCH"),
            ("worktree_path", "SLICE_WORKTREE"),
            ("objective", "SLICE_OBJECTIVE"),
        ):
            value = str(slice_item.get(field) or "").strip()
            if not value:
                continue
            facts.append(
                build_fact(
                    subject=slice_symbol,
                    predicate=predicate,
                    obj=value,
                    file_path=rel_path,
                    line_start=line_start,
                    line_end=max(line_start, line_end),
                    confidence=0.96,
                    evidence=value,
                )
            )

        scope_paths = slice_item.get("scope_paths")
        if isinstance(scope_paths, list):
            for scope_path in scope_paths:
                value = str(scope_path).strip()
                if not value:
                    continue
                facts.append(
                    build_fact(
                        subject=slice_symbol,
                        predicate="SLICE_SCOPE_PATH",
                        obj=value,
                        file_path=rel_path,
                        line_start=line_start,
                        line_end=max(line_start, line_end),
                        confidence=0.95,
                        evidence=value,
                    )
                )

        reviewers = slice_item.get("reviewers")
        if isinstance(reviewers, list):
            for reviewer in reviewers:
                value = str(reviewer).strip()
                if not value:
                    continue
                facts.append(
                    build_fact(
                        subject=slice_symbol,
                        predicate="SLICE_REVIEWER",
                        obj=f"agent:{value}",
                        file_path=rel_path,
                        line_start=line_start,
                        line_end=max(line_start, line_end),
                        confidence=0.94,
                        evidence=value,
                    )
                )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_ticket_summary_md(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    ticket_id = ticket_id_from_path(rel_path)
    ticket_symbol = f"ticket:{ticket_id}" if ticket_id else f"ticket_file:{rel_path}"

    chunks.append(
        ChunkRecord(
            file_path=rel_path,
            line_start=1,
            line_end=max(1, len(lines)),
            kind="ticket_summary",
            symbol=ticket_symbol,
            language=language,
            module=module,
            content=text.strip(),
            metadata={"source_type": "ticket_summary", "ticket_id": ticket_id},
        )
    )

    bullet_re = re.compile(r"^-+\s*([A-Za-z0-9_]+)\s*:\s*(.+)$")
    table_row_re = re.compile(
        r"^\|\s*(SLICE-[A-Za-z0-9_-]+)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*`?([^|`]+)`?\s*\|"
    )

    for idx, line in enumerate(lines, start=1):
        bullet = bullet_re.match(line.strip())
        if bullet:
            key = bullet.group(1).strip().lower()
            value = bullet.group(2).strip()
            predicate_map = {
                "status": "TICKET_STATUS",
                "title": "TICKET_TITLE",
                "goal": "TICKET_GOAL",
                "priority": "TICKET_PRIORITY",
                "base_branch": "TICKET_BASE_BRANCH",
                "updated_at": "TICKET_UPDATED_AT",
                "created_at": "TICKET_CREATED_AT",
            }
            predicate = predicate_map.get(key)
            if predicate:
                facts.append(
                    build_fact(
                        subject=ticket_symbol,
                        predicate=predicate,
                        obj=value,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.92,
                        evidence=line.strip(),
                    )
                )
            continue

        table_row = table_row_re.match(line.strip())
        if not table_row:
            continue
        slice_id = table_row.group(1).strip()
        agent = table_row.group(2).strip()
        lane = table_row.group(3).strip()
        status = table_row.group(4).strip()
        branch = table_row.group(5).strip()
        slice_symbol = f"slice:{ticket_id}:{slice_id}" if ticket_id else f"slice:{slice_id}"

        facts.append(
            build_fact(
                subject=ticket_symbol,
                predicate="HAS_SLICE",
                obj=slice_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.96,
                evidence=line.strip(),
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="BELONGS_TO",
                obj=ticket_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.96,
                evidence=ticket_id or rel_path,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="SLICE_AGENT",
                obj=f"agent:{agent}",
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.94,
                evidence=agent,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="SLICE_STATUS",
                obj=status,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.94,
                evidence=status,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="SLICE_LANE",
                obj=lane,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.92,
                evidence=lane,
            )
        )
        facts.append(
            build_fact(
                subject=slice_symbol,
                predicate="SLICE_BRANCH",
                obj=branch,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.93,
                evidence=branch,
            )
        )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_ticket_timeline_md(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    ticket_id = ticket_id_from_path(rel_path)
    ticket_symbol = f"ticket:{ticket_id}" if ticket_id else f"ticket_file:{rel_path}"

    event_re = re.compile(r"^-+\s*`([^`]+)`\s*(.+)$")
    claim_re = re.compile(
        r"claim recorded:\s*agent=`([^`]+)`\s*slice=`([^`]+)`\s*branch=`([^`]+)`(?:\s*worktree=`([^`]+)`)?(?:\s*status=`([^`]+)`)?",
        re.IGNORECASE,
    )
    move_re = re.compile(r"slice\s+`([^`]+)`\s+moved\s+to\s+`([^`]+)`", re.IGNORECASE)

    for idx, line in enumerate(lines, start=1):
        match = event_re.match(line.strip())
        if not match:
            continue
        ts_value = match.group(1).strip()
        detail = match.group(2).strip()
        event_symbol = f"ticket_event:{ticket_id}:{idx}" if ticket_id else f"ticket_event:{idx}:{rel_path}"

        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                kind="ticket_timeline_event",
                symbol=event_symbol,
                language=language,
                module=module,
                content=detail,
                metadata={"source_type": "ticket_timeline", "timestamp": ts_value, "ticket_id": ticket_id},
            )
        )

        facts.append(
            build_fact(
                subject=ticket_symbol,
                predicate="TICKET_EVENT",
                obj=event_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.95,
                evidence=detail,
                metadata={"timestamp": ts_value},
            )
        )

        claim = claim_re.search(detail)
        if claim:
            agent = claim.group(1).strip()
            slice_id = claim.group(2).strip()
            branch = claim.group(3).strip()
            worktree = (claim.group(4) or "").strip()
            status = (claim.group(5) or "").strip()
            slice_symbol = f"slice:{ticket_id}:{slice_id}" if ticket_id else f"slice:{slice_id}"
            facts.append(
                build_fact(
                    subject=slice_symbol,
                    predicate="SLICE_AGENT",
                    obj=f"agent:{agent}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.93,
                    evidence=detail,
                    metadata={"timestamp": ts_value},
                )
            )
            facts.append(
                build_fact(
                    subject=slice_symbol,
                    predicate="SLICE_BRANCH",
                    obj=branch,
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.92,
                    evidence=detail,
                    metadata={"timestamp": ts_value},
                )
            )
            if worktree:
                facts.append(
                    build_fact(
                        subject=slice_symbol,
                        predicate="SLICE_WORKTREE",
                        obj=worktree,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=detail,
                        metadata={"timestamp": ts_value},
                    )
                )
            if status:
                facts.append(
                    build_fact(
                        subject=slice_symbol,
                        predicate="SLICE_STATUS",
                        obj=status,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.92,
                        evidence=detail,
                        metadata={"timestamp": ts_value},
                    )
                )
            continue

        moved = move_re.search(detail)
        if moved:
            slice_id = moved.group(1).strip()
            status = moved.group(2).strip()
            slice_symbol = f"slice:{ticket_id}:{slice_id}" if ticket_id else f"slice:{slice_id}"
            facts.append(
                build_fact(
                    subject=slice_symbol,
                    predicate="SLICE_STATUS",
                    obj=status,
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.92,
                    evidence=detail,
                    metadata={"timestamp": ts_value},
                )
            )

    if not chunks:
        return parse_markdown(rel_path, text, language, module)

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_properties_or_env(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []
    buffer: List[str] = []
    block_start = 1

    def flush_block(end_line: int) -> None:
        if not buffer:
            return
        content = "\n".join(buffer).strip()
        if not content:
            return
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=block_start,
                line_end=end_line,
                kind="config_block",
                symbol=f"config:{rel_path}:{block_start}-{end_line}",
                language=language,
                module=module,
                content=content,
                metadata={"source_type": "config"},
            )
        )

    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped:
            flush_block(idx - 1)
            buffer = []
            block_start = idx + 1
            continue

        buffer.append(line)

        if stripped.startswith("#"):
            continue

        if "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
        elif ":" in stripped and not stripped.startswith("-"):
            key = stripped.split(":", 1)[0].strip()
        else:
            key = ""

        if key:
            facts.append(
                build_fact(
                    subject=f"config:{key}",
                    predicate="CONFIG_DEFINED",
                    obj=rel_path,
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.95,
                    evidence=line.strip(),
                    metadata={"key": key},
                )
            )

    flush_block(len(lines))
    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_yaml(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    key_stack: List[Tuple[int, str]] = []
    top_indices: List[Tuple[int, str]] = []

    for idx, line in enumerate(lines, start=1):
        if not line.strip() or line.strip().startswith("#"):
            continue
        key_match = re.match(r"^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$", line)
        if not key_match:
            continue
        indent = len(key_match.group(1).replace("\t", "    "))
        key = key_match.group(2).strip()
        while key_stack and indent <= key_stack[-1][0]:
            key_stack.pop()
        key_stack.append((indent, key))
        full_key = ".".join(part for _, part in key_stack)
        if len(key_stack) == 1:
            top_indices.append((idx, full_key))
        facts.append(
            build_fact(
                subject=f"config:{full_key}",
                predicate="CONFIG_DEFINED",
                obj=rel_path,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.93,
                evidence=line.strip(),
                metadata={"key": full_key},
            )
        )

    if not top_indices:
        return parse_properties_or_env(rel_path, text, language, module)

    for i, (start_line, key) in enumerate(top_indices):
        end_line = top_indices[i + 1][0] - 1 if i + 1 < len(top_indices) else len(lines)
        content = "\n".join(lines[start_line - 1 : end_line]).strip()
        if not content:
            continue
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=start_line,
                line_end=end_line,
                kind="config_block",
                symbol=f"config:{rel_path}:{key}",
                language=language,
                module=module,
                content=content,
                metadata={"source_type": "config", "key": key},
            )
        )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_sql(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    statement_lines: List[str] = []
    statement_start = 1
    statement_idx = 0

    def flush_statement(end_line: int) -> None:
        nonlocal statement_lines, statement_start, statement_idx
        body = "\n".join(statement_lines).strip()
        statement_lines = []
        if not body:
            return
        statement_idx += 1
        symbol = f"sql:{rel_path}:stmt:{statement_idx}"
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=statement_start,
                line_end=end_line,
                kind="sql_statement",
                symbol=symbol,
                language=language,
                module=module,
                content=body,
                metadata={"source_type": "sql"},
            )
        )

        lowered = body.lower()
        predicate = "SQL_EXECUTES"
        if "insert into" in lowered or "update " in lowered or "delete from" in lowered or "alter table" in lowered:
            predicate = "WRITES_TABLE"
        elif "select " in lowered:
            predicate = "READS_TABLE"

        for table in TABLE_RE.findall(body):
            normalized = table.strip().strip('"').strip("`")
            facts.append(
                build_fact(
                    subject=symbol,
                    predicate=predicate,
                    obj=f"table:{normalized}",
                    file_path=rel_path,
                    line_start=statement_start,
                    line_end=end_line,
                    confidence=0.8,
                    evidence=compact_excerpt(body, max_lines=4, max_chars=320),
                    metadata={"table": normalized},
                )
            )
            if "create table" in lowered:
                facts.append(
                    build_fact(
                        subject=f"table:{normalized}",
                        predicate="TABLE_DEFINED",
                        obj=rel_path,
                        file_path=rel_path,
                        line_start=statement_start,
                        line_end=end_line,
                        confidence=0.95,
                        evidence=compact_excerpt(body, max_lines=4, max_chars=320),
                        metadata={"table": normalized},
                    )
                )

    for idx, line in enumerate(lines, start=1):
        if not statement_lines:
            statement_start = idx
        statement_lines.append(line)
        if ";" in line:
            flush_statement(idx)

    flush_statement(len(lines))
    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_openapi_json(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])

    paths = parsed.get("paths")
    if not isinstance(paths, dict):
        return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])

    for route_path, route_obj in paths.items():
        if not isinstance(route_obj, dict):
            continue
        for method, operation in route_obj.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete", "options", "head"}:
                continue
            if not isinstance(operation, dict):
                continue

            op_id = str(operation.get("operationId") or f"{method.upper()} {route_path}")
            tags = operation.get("tags") if isinstance(operation.get("tags"), list) else []
            summary = str(operation.get("summary") or operation.get("description") or "")
            content = {
                "route": route_path,
                "method": method.upper(),
                "operationId": op_id,
                "tags": tags,
                "summary": summary,
                "parameters": operation.get("parameters", []),
                "requestBody": operation.get("requestBody", {}),
                "responses": operation.get("responses", {}),
            }
            rendered = json.dumps(content, ensure_ascii=True)

            symbol = f"openapi:{op_id}"
            route_symbol = f"route:{method.upper()} {route_path}"
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=1,
                    line_end=1,
                    kind="openapi_operation",
                    symbol=symbol,
                    language=language,
                    module=module,
                    content=rendered,
                    metadata={"source_type": "openapi", "route": route_path, "method": method.upper()},
                )
            )
            facts.append(
                build_fact(
                    subject=route_symbol,
                    predicate="ROUTE_CONTRACT",
                    obj=symbol,
                    file_path=rel_path,
                    line_start=1,
                    line_end=1,
                    confidence=0.95,
                    evidence=compact_excerpt(rendered, max_lines=3, max_chars=240),
                    metadata={"operationId": op_id},
                )
            )
            for tag in tags:
                facts.append(
                    build_fact(
                        subject=symbol,
                        predicate="TAGGED_WITH",
                        obj=f"tag:{tag}",
                        file_path=rel_path,
                        line_start=1,
                        line_end=1,
                        confidence=0.9,
                        evidence=tag,
                    )
                )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_pom_xml(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    dep_re = re.compile(r"<dependency>(.*?)</dependency>", re.DOTALL)
    group_re = re.compile(r"<groupId>\s*([^<]+)\s*</groupId>")
    artifact_re = re.compile(r"<artifactId>\s*([^<]+)\s*</artifactId>")
    version_re = re.compile(r"<version>\s*([^<]+)\s*</version>")

    project_symbol = f"maven:{rel_path}"
    for match in dep_re.finditer(text):
        block = match.group(1)
        group_match = group_re.search(block)
        artifact_match = artifact_re.search(block)
        if not group_match or not artifact_match:
            continue
        group_id = group_match.group(1).strip()
        artifact_id = artifact_match.group(1).strip()
        version_match = version_re.search(block)
        version = version_match.group(1).strip() if version_match else ""
        dependency_symbol = f"package:{group_id}:{artifact_id}"
        line_start = text[: match.start()].count("\n") + 1
        line_end = text[: match.end()].count("\n") + 1

        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=line_start,
                line_end=line_end,
                kind="maven_dependency",
                symbol=dependency_symbol,
                language=language,
                module=module,
                content=compact_excerpt(block, max_lines=8, max_chars=300),
                metadata={
                    "source_type": "pom",
                    "groupId": group_id,
                    "artifactId": artifact_id,
                    "version": version,
                },
            )
        )
        facts.append(
            build_fact(
                subject=project_symbol,
                predicate="DEPENDS_PACKAGE",
                obj=dependency_symbol,
                file_path=rel_path,
                line_start=line_start,
                line_end=line_end,
                confidence=0.95,
                evidence=f"{group_id}:{artifact_id}",
            )
        )
        if version:
            facts.append(
                build_fact(
                    subject=dependency_symbol,
                    predicate="DEPENDS_VERSION",
                    obj=version,
                    file_path=rel_path,
                    line_start=line_start,
                    line_end=line_end,
                    confidence=0.9,
                    evidence=version,
                )
            )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_endpoint_inventory_tsv(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    for idx, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped:
            continue
        parts = [part.strip() for part in stripped.split("\t")]
        if len(parts) < 4:
            continue
        http_method, route_path, handler_symbol, handler_file = parts[0], parts[1], parts[2], parts[3]
        route_symbol = f"route:{http_method} {route_path}"
        handler_file_path = handler_file
        if handler_file_path.startswith("src/"):
            handler_file_path = f"erp-domain/{handler_file_path}"

        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                kind="endpoint_inventory_row",
                symbol=route_symbol,
                language=language,
                module=module,
                content=stripped,
                metadata={
                    "source_type": "endpoint_inventory",
                    "method": http_method,
                    "route": route_path,
                    "handler": handler_symbol,
                    "handler_file": handler_file_path,
                },
            )
        )
        facts.append(
            build_fact(
                subject=route_symbol,
                predicate="ROUTE_TO_HANDLER",
                obj=handler_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.99,
                evidence=stripped,
                metadata={"source": "endpoint_inventory"},
            )
        )
        facts.append(
            build_fact(
                subject=handler_symbol,
                predicate="HANDLES_ROUTE",
                obj=route_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.99,
                evidence=stripped,
                metadata={"source": "endpoint_inventory"},
            )
        )
        facts.append(
            build_fact(
                subject=handler_symbol,
                predicate="DEFINED_IN",
                obj=f"file:{handler_file_path}",
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.96,
                evidence=handler_file_path,
                metadata={"source": "endpoint_inventory"},
            )
        )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_module_files_map(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []
    module_name = Path(rel_path).parent.name
    doc_symbol = f"module_doc:{module_name}"

    for idx, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if "|" not in stripped:
            continue
        left, right = stripped.split("|", 1)
        file_path = left.strip()
        description = right.strip()
        if not file_path.startswith("erp-domain/"):
            continue

        file_symbol = f"file:{file_path}"
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                kind="module_file_map_row",
                symbol=file_symbol,
                language=language,
                module=module,
                content=stripped,
                metadata={"source_type": "module_files_map", "module_name": module_name},
            )
        )
        facts.append(
            build_fact(
                subject=doc_symbol,
                predicate="LISTS_FILE",
                obj=file_symbol,
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.95,
                evidence=stripped,
                metadata={"description": description},
            )
        )
        facts.append(
            build_fact(
                subject=file_symbol,
                predicate="FILE_ROLE",
                obj=f"role:{description.lower().replace(' ', '_')[:80]}",
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                confidence=0.8,
                evidence=description,
                metadata={"description": description},
            )
        )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_test_catalog_json(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])

    tests = parsed.get("tests")
    if not isinstance(tests, list):
        return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])

    for idx, test_item in enumerate(tests, start=1):
        if not isinstance(test_item, dict):
            continue
        test_path = str(test_item.get("test_path") or "").strip()
        if not test_path:
            continue
        test_symbol = f"test:{test_path}"
        content = json.dumps(test_item, ensure_ascii=True, sort_keys=True)

        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=idx,
                line_end=idx,
                kind="test_catalog_entry",
                symbol=test_symbol,
                language=language,
                module=module,
                content=content,
                metadata={"source_type": "test_catalog"},
            )
        )

        module_name = str(test_item.get("module") or "").strip().lower()
        if module_name:
            facts.append(
                build_fact(
                    subject=test_symbol,
                    predicate="TEST_MODULE",
                    obj=f"module:{module_name}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.9,
                    evidence=module_name,
                )
            )

        test_class = str(test_item.get("class") or "").strip().lower()
        if test_class:
            facts.append(
                build_fact(
                    subject=test_symbol,
                    predicate="TEST_CLASS",
                    obj=f"class:{test_class}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.9,
                    evidence=test_class,
                )
            )

        gate_membership = test_item.get("gate_membership")
        if isinstance(gate_membership, list):
            for gate in gate_membership:
                gate_name = str(gate).strip()
                if not gate_name:
                    continue
                facts.append(
                    build_fact(
                        subject=test_symbol,
                        predicate="GATE_MEMBER",
                        obj=f"gate:{gate_name}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=gate_name,
                    )
                )

        tags = test_item.get("tags")
        if isinstance(tags, list):
            for tag in tags:
                tag_name = str(tag).strip()
                if not tag_name:
                    continue
                facts.append(
                    build_fact(
                        subject=test_symbol,
                        predicate="TAGGED_WITH",
                        obj=f"tag:{tag_name}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=tag_name,
                    )
                )

        if bool(test_item.get("cross_module_critical")):
            facts.append(
                build_fact(
                    subject=test_symbol,
                    predicate="CROSS_MODULE_CRITICAL",
                    obj="true",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.95,
                    evidence="cross_module_critical=true",
                )
            )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def slugify_symbol(value: str) -> str:
    token = value.strip().lower()
    token = re.sub(r"[^a-z0-9]+", "_", token)
    return token.strip("_") or "unknown"


def parse_cross_module_workflows_md(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []
    current_workflow_symbol = "workflow:cross_module_workflows"

    lines = text.splitlines()
    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        heading = re.match(r"^##\s+(.+)$", stripped)
        if heading:
            workflow_name = heading.group(1).strip()
            current_workflow_symbol = f"workflow:{slugify_symbol(workflow_name)}"
            continue

        if stripped.startswith("|") and "|" in stripped[1:]:
            row = [cell.strip() for cell in stripped.strip("|").split("|")]
            if len(row) < 6:
                continue
            if row[0].lower() == "workflow" or set(stripped.replace("|", "").strip()) == {"-"}:
                continue
            workflow, business_event, canonical_path, alternate_path, decision, guard = row[:6]
            row_symbol = f"workflow_row:{slugify_symbol(workflow)}:{idx}"
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    kind="workflow_registry_row",
                    symbol=row_symbol,
                    language=language,
                    module=module,
                    content=stripped,
                    metadata={
                        "source_type": "workflow_registry",
                        "workflow": workflow,
                        "decision": decision,
                    },
                )
            )
            workflow_symbol = f"workflow:{slugify_symbol(workflow)}"
            facts.append(
                build_fact(
                    subject=workflow_symbol,
                    predicate="BUSINESS_EVENT",
                    obj=f"event:{business_event}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.93,
                    evidence=business_event,
                )
            )
            facts.append(
                build_fact(
                    subject=workflow_symbol,
                    predicate="WORKFLOW_DECISION",
                    obj=f"decision:{decision.lower()}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.92,
                    evidence=decision,
                )
            )
            for method, path in METHOD_LEVEL_ROUTE_RE.findall(canonical_path):
                route_symbol = f"route:{method.upper()} {normalize_route_path(path)}"
                facts.append(
                    build_fact(
                        subject=workflow_symbol,
                        predicate="CANONICAL_ROUTE",
                        obj=route_symbol,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.94,
                        evidence=canonical_path,
                    )
                )
            for method, path in METHOD_LEVEL_ROUTE_RE.findall(alternate_path):
                route_symbol = f"route:{method.upper()} {normalize_route_path(path)}"
                facts.append(
                    build_fact(
                        subject=workflow_symbol,
                        predicate="ALTERNATE_ROUTE",
                        obj=route_symbol,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=alternate_path,
                    )
                )
            continue

        owner_match = re.match(r"^-+\s*Owner module/service:\s*(.+)$", stripped, flags=re.IGNORECASE)
        if owner_match:
            owner_text = owner_match.group(1)
            for module_name in re.findall(r"`([^`]+)`", owner_text):
                module_token = slugify_symbol(module_name)
                if not module_token:
                    continue
                facts.append(
                    build_fact(
                        subject=current_workflow_symbol,
                        predicate="WORKFLOW_OWNER",
                        obj=f"module:{module_token}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.88,
                        evidence=module_name,
                    )
                )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_module_boundaries_md(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    lines = text.splitlines()
    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        if "->" not in stripped:
            continue
        quoted_tokens = re.findall(r"`([^`]+)`", stripped)
        if len(quoted_tokens) < 2:
            continue
        left = slugify_symbol(quoted_tokens[0])
        right_tokens = [slugify_symbol(token) for token in quoted_tokens[1:] if token.strip()]
        for right in right_tokens:
            chunk_symbol = f"module_direction:{left}:{right}:{idx}"
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    kind="module_boundary_direction",
                    symbol=chunk_symbol,
                    language=language,
                    module=module,
                    content=stripped,
                    metadata={"source_type": "module_boundaries"},
                )
            )
            facts.append(
                build_fact(
                    subject=f"module:{left}",
                    predicate="MODULE_DEPENDS_ON",
                    obj=f"module:{right}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.9,
                    evidence=stripped,
                )
            )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_cross_module_linkage_md(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []
    current_workflow = "workflow:linkage_matrix"

    lines = text.splitlines()
    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        heading = re.match(r"^##\s+(.+)$", stripped)
        if heading:
            current_workflow = f"workflow:{slugify_symbol(heading.group(1))}"
            continue

        if stripped.lower().startswith("chain:") and "->" in stripped:
            chain_text = stripped.split(":", 1)[1].strip()
            steps = [part.strip().strip(".") for part in chain_text.split("->") if part.strip()]
            if len(steps) < 2:
                continue
            chunk_symbol = f"workflow_chain:{slugify_symbol(current_workflow)}:{idx}"
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    kind="workflow_chain",
                    symbol=chunk_symbol,
                    language=language,
                    module=module,
                    content=chain_text,
                    metadata={"source_type": "linkage_matrix", "workflow": current_workflow},
                )
            )
            for left, right in zip(steps, steps[1:]):
                left_symbol = f"{current_workflow}:step:{slugify_symbol(left)}"
                right_symbol = f"{current_workflow}:step:{slugify_symbol(right)}"
                facts.append(
                    build_fact(
                        subject=left_symbol,
                        predicate="WORKFLOW_CHAIN",
                        obj=right_symbol,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.92,
                        evidence=chain_text,
                    )
                )
            continue

        key_match = re.match(r"-\s*`([^`]+)`\s*->\s*`([^`]+)`", stripped)
        if key_match:
            left_key = key_match.group(1).strip()
            right_key = key_match.group(2).strip()
            facts.append(
                build_fact(
                    subject=f"link_key:{left_key}",
                    predicate="LINKS_TO",
                    obj=f"link_key:{right_key}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.9,
                    evidence=stripped,
                )
            )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_python(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    try:
        tree = ast.parse(text)
    except SyntaxError:
        return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])

    lines = text.splitlines()

    class CallCollector(ast.NodeVisitor):
        def __init__(self) -> None:
            self.calls: List[str] = []

        def visit_Call(self, node: ast.Call) -> Any:
            call_name = ""
            if isinstance(node.func, ast.Name):
                call_name = node.func.id
            elif isinstance(node.func, ast.Attribute):
                call_name = node.func.attr
            if call_name:
                self.calls.append(call_name)
            self.generic_visit(node)

    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        start_line = getattr(node, "lineno", 1)
        end_line = getattr(node, "end_lineno", start_line)
        symbol = f"py:{rel_path}:{node.name}"
        kind = "py_class" if isinstance(node, ast.ClassDef) else "py_function"
        content = "\n".join(lines[start_line - 1 : end_line])
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=start_line,
                line_end=end_line,
                kind=kind,
                symbol=symbol,
                language=language,
                module=module,
                content=content,
                metadata={"source_type": "script"},
            )
        )

        collector = CallCollector()
        collector.visit(node)
        for call_name in sorted(set(collector.calls)):
            facts.append(
                build_fact(
                    subject=symbol,
                    predicate="CALLS",
                    obj=call_name,
                    file_path=rel_path,
                    line_start=start_line,
                    line_end=end_line,
                    confidence=0.65,
                    evidence=f"calls {call_name}",
                )
            )

        for cfg_key in parse_config_keys_from_text(content):
            facts.append(
                build_fact(
                    subject=symbol,
                    predicate="USES_CONFIG",
                    obj=f"config:{cfg_key}",
                    file_path=rel_path,
                    line_start=start_line,
                    line_end=end_line,
                    confidence=0.85,
                    evidence=cfg_key,
                    metadata={"key": cfg_key},
                )
            )

    if rel_path == "scripts/harness_orchestrator.py":
        edge_re = re.compile(r'\("([a-z0-9_-]+)",\s*"([a-z0-9_-]+)",\s*"([^"]+)"\)')
        for idx, line in enumerate(lines, start=1):
            for match in edge_re.finditer(line):
                upstream, downstream, contract = match.group(1), match.group(2), match.group(3)
                edge_symbol = f"agent_edge:{upstream}->{downstream}"
                chunks.append(
                    ChunkRecord(
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        kind="workflow_agent_edge",
                        symbol=edge_symbol,
                        language=language,
                        module=module,
                        content=line.strip(),
                        metadata={"source_type": "workflow_edge", "contract": contract},
                    )
                )
                facts.append(
                    build_fact(
                        subject=f"agent:{upstream}",
                        predicate="WORKFLOW_DOWNSTREAM",
                        obj=f"agent:{downstream}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.95,
                        evidence=contract,
                        metadata={"contract": contract},
                    )
                )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def parse_shell(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []

    func_re = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{")

    i = 0
    while i < len(lines):
        line = lines[i]
        match = func_re.match(line)
        if not match:
            i += 1
            continue

        func_name = match.group(1)
        start = i
        brace_balance = line.count("{") - line.count("}")
        i += 1
        while i < len(lines):
            brace_balance += lines[i].count("{") - lines[i].count("}")
            if brace_balance <= 0:
                break
            i += 1
        end = min(i, len(lines) - 1)

        content = "\n".join(lines[start : end + 1])
        symbol = f"bash:{rel_path}:{func_name}"
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=start + 1,
                line_end=end + 1,
                kind="bash_function",
                symbol=symbol,
                language=language,
                module=module,
                content=content,
                metadata={"source_type": "script"},
            )
        )

        for env_name in sorted(set(re.findall(r"\$\{?([A-Z][A-Z0-9_]+)\}?", content))):
            facts.append(
                build_fact(
                    subject=symbol,
                    predicate="USES_ENV",
                    obj=f"config:{env_name}",
                    file_path=rel_path,
                    line_start=start + 1,
                    line_end=end + 1,
                    confidence=0.85,
                    evidence=env_name,
                    metadata={"key": env_name},
                )
            )

        i += 1

    if not chunks:
        content = "\n".join(lines[: min(len(lines), 180)])
        if content.strip():
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=1,
                    line_end=min(len(lines), 180),
                    kind="bash_file",
                    symbol=f"bash:{rel_path}",
                    language=language,
                    module=module,
                    content=content,
                    metadata={"source_type": "script"},
                )
            )

    return ParseOutcome(chunks=chunks, facts=facts, class_symbols={}, test_mentions=[])


def extract_method_calls(body: str) -> List[str]:
    calls: List[str] = []
    for match in re.finditer(r"([A-Za-z_][A-Za-z0-9_$.]*)\s*\(", body):
        name = match.group(1)
        scan = match.start() - 1
        while scan >= 0 and body[scan].isspace():
            scan -= 1
        if scan >= 0 and body[scan] == "@":
            continue
        token = name.split(".")[-1]
        if token.lower() in CONTROL_KEYWORDS:
            continue
        if token in {"super", "this"}:
            continue
        if token in NOISY_CALL_TOKENS:
            continue
        if len(token) <= 2 and "." not in name:
            continue
        calls.append(name)
    return sorted(set(calls))


def parse_java(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []
    facts: List[FactRecord] = []
    class_symbols: Dict[str, str] = {}
    test_mentions: List[str] = []

    package_name = ""
    imports: List[str] = []
    class_base_route: Dict[str, str] = {}
    class_is_controller: Dict[str, bool] = {}
    class_field_dependencies: Dict[str, Dict[str, str]] = {}
    class_security_rules: Dict[str, List[str]] = {}
    current_class = ""
    current_class_symbol = ""
    pending_annotations: List[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        idx = i + 1
        stripped = line.strip()

        package_match = PACKAGE_RE.match(line)
        if package_match:
            package_name = package_match.group(1)
            i += 1
            continue

        import_match = IMPORT_RE.match(line)
        if import_match:
            imports.append(import_match.group(1))
            i += 1
            continue

        if stripped.startswith("@"):
            annotation_block, end_idx = collect_annotation_block(lines, i)
            pending_annotations.append(annotation_block)
            if len(pending_annotations) > 12:
                pending_annotations = pending_annotations[-12:]
            i = end_idx + 1
            continue

        class_match = CLASS_RE.search(line)
        if class_match:
            class_kind = class_match.group(1)
            class_name = class_match.group(2)
            current_class = class_name
            current_class_symbol = f"{package_name}.{class_name}" if package_name else class_name
            class_symbols[class_name] = current_class_symbol

            direct_imports, wildcard_imports = build_import_lookups(imports)
            declaration_signature = collect_declaration_signature(lines, i)
            extends_types_raw, implements_types_raw = extract_class_relationship_types(
                declaration_signature,
                class_kind,
            )
            is_controller = any("Controller" in annotation_simple_name(ann) for ann in pending_annotations)
            class_is_controller[class_name] = is_controller
            base_paths: List[str] = []
            for ann in pending_annotations:
                if REQUEST_MAPPING_RE.search(ann):
                    base_paths.extend(extract_annotation_paths(ann))
            class_base_route[class_name] = base_paths[0] if base_paths else ""
            class_field_dependencies[current_class_symbol] = {}
            class_security = [
                expression
                for expression in (extract_security_expression(annotation) for annotation in pending_annotations)
                if expression
            ]
            class_security_rules[current_class_symbol] = class_security
            component_roles = [
                COMPONENT_ROLE_ANNOTATIONS.get(annotation_simple_name(annotation))
                for annotation in pending_annotations
            ]
            component_roles = [role for role in component_roles if role]

            class_block_end = min(len(lines), idx + 30)
            class_content = "\n".join(lines[idx - 1 : class_block_end])
            chunks.append(
                ChunkRecord(
                    file_path=rel_path,
                    line_start=idx,
                    line_end=class_block_end,
                    kind="java_class",
                    symbol=current_class_symbol,
                    language=language,
                    module=module,
                    content=class_content,
                    metadata={
                        "source_type": "code",
                        "class_kind": class_kind,
                        "package": package_name,
                        "imports": imports[:120],
                        "is_controller": is_controller,
                        "component_roles": component_roles,
                    },
                )
            )

            facts.append(
                build_fact(
                    subject=current_class_symbol,
                    predicate="IN_MODULE",
                    obj=f"module:{module}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.98,
                    evidence=line.strip(),
                )
            )
            facts.append(
                build_fact(
                    subject=current_class_symbol,
                    predicate="DEFINED_IN",
                    obj=f"file:{rel_path}",
                    file_path=rel_path,
                    line_start=idx,
                    line_end=idx,
                    confidence=0.96,
                    evidence=rel_path,
                )
            )
            for imported in imports:
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="IMPORTS",
                        obj=imported,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.95,
                        evidence=imported,
                    )
                )
                if imported.startswith("com.bigbrightpaints.erp."):
                    facts.append(
                        build_fact(
                            subject=current_class_symbol,
                            predicate="DEPENDS_ON",
                            obj=imported,
                            file_path=rel_path,
                            line_start=idx,
                            line_end=idx,
                            confidence=0.88,
                            evidence=imported,
                        )
                    )

            for raw_type in extends_types_raw:
                resolved_type = resolve_java_type_symbol(raw_type, package_name, direct_imports, wildcard_imports)
                if not resolved_type or resolved_type in JAVA_PRIMITIVE_TYPES:
                    continue
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="EXTENDS",
                        obj=resolved_type,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=raw_type,
                    )
                )
                if resolved_type.startswith("com.bigbrightpaints.erp."):
                    facts.append(
                        build_fact(
                            subject=current_class_symbol,
                            predicate="DEPENDS_ON",
                            obj=resolved_type,
                            file_path=rel_path,
                            line_start=idx,
                            line_end=idx,
                            confidence=0.87,
                            evidence=raw_type,
                        )
                    )

            for raw_type in implements_types_raw:
                resolved_type = resolve_java_type_symbol(raw_type, package_name, direct_imports, wildcard_imports)
                if not resolved_type or resolved_type in JAVA_PRIMITIVE_TYPES:
                    continue
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="IMPLEMENTS",
                        obj=resolved_type,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=raw_type,
                    )
                )
                if resolved_type.startswith("com.bigbrightpaints.erp."):
                    facts.append(
                        build_fact(
                            subject=current_class_symbol,
                            predicate="DEPENDS_ON",
                            obj=resolved_type,
                            file_path=rel_path,
                            line_start=idx,
                            line_end=idx,
                            confidence=0.87,
                            evidence=raw_type,
                        )
                    )

            for role in component_roles:
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="COMPONENT_ROLE",
                        obj=f"role:{role}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.95,
                        evidence=role,
                    )
                )

            for cfg_key in extract_config_keys_from_annotations(pending_annotations):
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="USES_CONFIG",
                        obj=f"config:{cfg_key}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=cfg_key,
                        metadata={"key": cfg_key, "source": "annotation"},
                    )
                )

            for security_expression in class_security:
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="SECURED_BY",
                        obj=security_expression,
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.92,
                        evidence=security_expression,
                        metadata={"source": "class_annotation"},
                    )
                )

            pending_annotations = []
            i += 1
            continue

        if current_class_symbol and stripped.endswith(";") and "(" not in stripped:
            field_match = FIELD_DECL_RE.match(stripped)
            if field_match:
                field_type_raw = field_match.group(1)
                field_name = field_match.group(2)
                direct_imports, wildcard_imports = build_import_lookups(imports)
                resolved_type = resolve_java_type_symbol(
                    field_type_raw,
                    package_name,
                    direct_imports,
                    wildcard_imports,
                )
                if resolved_type and resolved_type not in JAVA_PRIMITIVE_TYPES and not resolved_type.startswith("java.lang."):
                    class_field_dependencies.setdefault(current_class_symbol, {})[field_name] = resolved_type
                    facts.append(
                        build_fact(
                            subject=current_class_symbol,
                            predicate="DEPENDS_ON",
                            obj=resolved_type,
                            file_path=rel_path,
                            line_start=idx,
                            line_end=idx,
                            confidence=0.91,
                            evidence=f"{field_type_raw} {field_name}",
                            metadata={"field": field_name},
                        )
                    )
            for cfg_key in extract_config_keys_from_annotations(pending_annotations):
                facts.append(
                    build_fact(
                        subject=current_class_symbol,
                        predicate="USES_CONFIG",
                        obj=f"config:{cfg_key}",
                        file_path=rel_path,
                        line_start=idx,
                        line_end=idx,
                        confidence=0.9,
                        evidence=cfg_key,
                        metadata={"key": cfg_key, "source": "field_annotation"},
                    )
                )
            pending_annotations = []
            i += 1
            continue

        if "(" in stripped:
            sig_lines = [line]
            sig_end = i
            while sig_end + 1 < len(lines) and (sig_end - i) < 20:
                joined = " ".join(sig_lines)
                if "{" in joined or ";" in joined:
                    break
                sig_end += 1
                sig_lines.append(lines[sig_end])
            signature = " ".join(piece.strip() for piece in sig_lines)
            is_method_impl = looks_like_method_signature(signature)
            is_method_decl = looks_like_method_declaration(signature)

            if is_method_impl or is_method_decl:
                method_name = extract_method_name(signature)
                if method_name.lower() not in CONTROL_KEYWORDS:
                    method_start = idx
                    if is_method_impl:
                        brace_balance = 0
                        seen_open = False
                        k = sig_end
                        while k < len(lines):
                            current = lines[k]
                            opens = current.count("{")
                            closes = current.count("}")
                            if opens > 0:
                                seen_open = True
                            brace_balance += opens
                            brace_balance -= closes
                            if seen_open and brace_balance <= 0:
                                break
                            k += 1
                        if not seen_open:
                            pending_annotations = []
                            i += 1
                            continue
                        method_end = min(k + 1, len(lines))
                        method_body = "\n".join(lines[method_start - 1 : method_end])
                    else:
                        method_end = sig_end + 1
                        method_body = "\n".join([*pending_annotations, signature])

                    if current_class_symbol:
                        method_symbol = f"{current_class_symbol}#{method_name}"
                    elif package_name:
                        method_symbol = f"{package_name}.<file>#{method_name}"
                    else:
                        method_symbol = f"{rel_path}#{method_name}"

                    direct_imports, wildcard_imports = build_import_lookups(imports)
                    parsed_parameters = parse_method_parameters(signature)
                    resolved_parameter_types: List[Tuple[str, str]] = []
                    resolved_parameter_contains: List[Tuple[str, str]] = []
                    idempotency_parameter_types: List[Tuple[str, str, str, str]] = []
                    seen_param_types: Set[str] = set()
                    seen_param_contains_types: Set[str] = set()
                    seen_idempotency_types: Set[Tuple[str, str]] = set()
                    for raw_param_type, param_name in parsed_parameters:
                        resolved_param_type = resolve_java_type_symbol(
                            raw_param_type,
                            package_name,
                            direct_imports,
                            wildcard_imports,
                        )

                        if is_idempotency_parameter_name(param_name):
                            idempotency_type = resolved_param_type or normalize_java_type(raw_param_type)
                            if idempotency_type:
                                id_bucket = idempotency_type_bucket(idempotency_type)
                                id_key = (param_name, idempotency_type)
                                if id_key not in seen_idempotency_types:
                                    seen_idempotency_types.add(id_key)
                                    idempotency_parameter_types.append(
                                        (param_name, raw_param_type, idempotency_type, id_bucket)
                                    )

                        if not resolved_param_type or resolved_param_type in JAVA_PRIMITIVE_TYPES:
                            continue
                        if resolved_param_type in seen_param_types:
                            continue
                        seen_param_types.add(resolved_param_type)
                        resolved_parameter_types.append((raw_param_type, resolved_param_type))

                        for candidate_type in extract_java_type_candidates(raw_param_type):
                            resolved_candidate = resolve_java_type_symbol(
                                candidate_type,
                                package_name,
                                direct_imports,
                                wildcard_imports,
                            )
                            if not resolved_candidate or resolved_candidate in JAVA_PRIMITIVE_TYPES:
                                continue
                            if resolved_candidate in seen_param_contains_types:
                                continue
                            seen_param_contains_types.add(resolved_candidate)
                            resolved_parameter_contains.append((raw_param_type, resolved_candidate))

                    return_type_raw = parse_method_return_type(
                        signature,
                        method_name,
                        class_name=current_class,
                    )
                    resolved_return_type = ""
                    resolved_return_contains: List[str] = []
                    if return_type_raw:
                        candidate_return_type = resolve_java_type_symbol(
                            return_type_raw,
                            package_name,
                            direct_imports,
                            wildcard_imports,
                        )
                        if candidate_return_type and candidate_return_type not in JAVA_PRIMITIVE_TYPES:
                            resolved_return_type = candidate_return_type

                        seen_return_contains: Set[str] = set()
                        for candidate_type in extract_java_type_candidates(return_type_raw):
                            resolved_candidate = resolve_java_type_symbol(
                                candidate_type,
                                package_name,
                                direct_imports,
                                wildcard_imports,
                            )
                            if not resolved_candidate or resolved_candidate in JAVA_PRIMITIVE_TYPES:
                                continue
                            if resolved_candidate in seen_return_contains:
                                continue
                            seen_return_contains.add(resolved_candidate)
                            resolved_return_contains.append(resolved_candidate)

                    route_entries: List[Tuple[str, str]] = []
                    base_path = class_base_route.get(current_class, "")
                    if class_is_controller.get(current_class, False):
                        for ann in pending_annotations:
                            methods = extract_mapping_methods(ann)
                            if not methods:
                                continue
                            paths = extract_annotation_paths(ann)
                            for http_method in methods:
                                for route_path in paths:
                                    route_entries.append((http_method, join_route(base_path, route_path)))

                    logic_fingerprint = ""
                    logic_signal_count = 0
                    if is_method_impl:
                        logic_fingerprint, logic_signal_count = method_logic_fingerprint(method_body)

                    query_specs = extract_query_strings_from_annotations(pending_annotations)
                    method_decl_with_query = is_method_decl and bool(query_specs)
                    chunk_kind = "java_method"
                    if route_entries:
                        chunk_kind = "java_route_handler"
                    elif method_decl_with_query:
                        chunk_kind = "java_repository_method_decl"
                    elif is_method_decl:
                        chunk_kind = "java_method_decl"

                    annotation_names = [annotation_simple_name(ann) for ann in pending_annotations if ann]
                    chunks.append(
                        ChunkRecord(
                            file_path=rel_path,
                            line_start=method_start,
                            line_end=method_end,
                            kind=chunk_kind,
                            symbol=method_symbol,
                            language=language,
                            module=module,
                            content=method_body,
                            metadata={
                                "source_type": "code",
                                "class": current_class_symbol,
                                "declaration_only": is_method_decl and not is_method_impl,
                                "annotations": annotation_names,
                                "routes": [
                                    {"method": method, "path": path}
                                    for method, path in route_entries
                                ],
                                "logic_fingerprint": logic_fingerprint or "",
                                "logic_signal_count": logic_signal_count,
                            },
                        )
                    )

                    facts.append(
                        build_fact(
                            subject=method_symbol,
                            predicate="BELONGS_TO",
                            obj=current_class_symbol or f"file:{rel_path}",
                            file_path=rel_path,
                            line_start=method_start,
                            line_end=method_end,
                            confidence=0.95,
                            evidence=signature,
                        )
                    )
                    facts.append(
                        build_fact(
                            subject=method_symbol,
                            predicate="DEFINED_IN",
                            obj=f"file:{rel_path}",
                            file_path=rel_path,
                            line_start=method_start,
                            line_end=method_end,
                            confidence=0.95,
                            evidence=rel_path,
                        )
                    )

                    if logic_fingerprint:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="LOGIC_FINGERPRINT",
                                obj=f"logic:{logic_fingerprint}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.86,
                                evidence=f"logic fingerprint ({logic_signal_count} signals)",
                                metadata={"signal_count": logic_signal_count},
                            )
                        )

                    for param_name, raw_param_type, idempotency_type, id_bucket in idempotency_parameter_types:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="IDEMPOTENCY_KEY_TYPE",
                                obj=f"type:{idempotency_type}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.9,
                                evidence=f"{param_name}:{raw_param_type}",
                                metadata={
                                    "parameter": param_name,
                                    "raw_type": raw_param_type,
                                    "bucket": id_bucket,
                                },
                            )
                        )
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="IDEMPOTENCY_KEY_PARAM",
                                obj=f"param:{param_name}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.86,
                                evidence=param_name,
                                metadata={"raw_type": raw_param_type, "bucket": id_bucket},
                            )
                        )

                    for raw_param_type, resolved_param_type in resolved_parameter_types:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="ACCEPTS_TYPE",
                                obj=f"type:{resolved_param_type}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.88,
                                evidence=raw_param_type,
                                metadata={"raw_type": raw_param_type},
                            )
                        )

                    for raw_param_type, resolved_contains_type in resolved_parameter_contains:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="ACCEPTS_CONTAINS_TYPE",
                                obj=f"type:{resolved_contains_type}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.82,
                                evidence=raw_param_type,
                                metadata={"raw_type": raw_param_type},
                            )
                        )

                    if resolved_return_type:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="RETURNS_TYPE",
                                obj=f"type:{resolved_return_type}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.86,
                                evidence=return_type_raw or resolved_return_type,
                                metadata={"raw_type": return_type_raw},
                            )
                        )

                    for resolved_contains_type in resolved_return_contains:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="RETURNS_CONTAINS_TYPE",
                                obj=f"type:{resolved_contains_type}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.8,
                                evidence=return_type_raw or resolved_contains_type,
                                metadata={"raw_type": return_type_raw},
                            )
                        )

                    for call_name in extract_method_calls(method_body):
                        confidence = 0.64 if "." in call_name else 0.46
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="CALLS",
                                obj=call_name,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=confidence,
                                evidence=call_name,
                            )
                        )

                    dependency_map = class_field_dependencies.get(current_class_symbol, {})
                    dependency_calls = extract_dependency_calls(method_body, dependency_map)
                    for qualifier, method_called, target_symbol in dependency_calls:
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="CALLS",
                                obj=target_symbol,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.93,
                                evidence=f"{qualifier}.{method_called}",
                                metadata={"qualifier": qualifier},
                            )
                        )
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="HANDLER_CALLS",
                                obj=target_symbol,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.92,
                                evidence=f"{qualifier}.{method_called}",
                            )
                        )

                    for cfg_key in sorted(
                        set(parse_config_keys_from_text(method_body) + extract_config_keys_from_annotations(pending_annotations))
                    ):
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="USES_CONFIG",
                                obj=f"config:{cfg_key}",
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.9,
                                evidence=cfg_key,
                                metadata={"key": cfg_key},
                            )
                        )

                    for route_method, route_path in route_entries:
                        route_symbol = f"route:{route_method} {route_path}"
                        route_idempotency_seen: Set[Tuple[str, str]] = set()
                        facts.append(
                            build_fact(
                                subject=route_symbol,
                                predicate="ROUTE_TO_HANDLER",
                                obj=method_symbol,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.97,
                                evidence=f"{route_method} {route_path}",
                            )
                        )
                        for raw_param_type, resolved_param_type in resolved_parameter_types:
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_ACCEPTS_TYPE",
                                    obj=f"type:{resolved_param_type}",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.85,
                                    evidence=raw_param_type,
                                    metadata={"handler": method_symbol, "raw_type": raw_param_type},
                                )
                            )
                        for param_name, raw_param_type, idempotency_type, id_bucket in idempotency_parameter_types:
                            route_key = (param_name, idempotency_type)
                            if route_key in route_idempotency_seen:
                                continue
                            route_idempotency_seen.add(route_key)
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_IDEMPOTENCY_KEY_TYPE",
                                    obj=f"type:{idempotency_type}",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.88,
                                    evidence=f"{param_name}:{raw_param_type}",
                                    metadata={
                                        "handler": method_symbol,
                                        "parameter": param_name,
                                        "raw_type": raw_param_type,
                                        "bucket": id_bucket,
                                    },
                                )
                            )
                        for raw_param_type, resolved_contains_type in resolved_parameter_contains:
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_ACCEPTS_CONTAINS_TYPE",
                                    obj=f"type:{resolved_contains_type}",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.8,
                                    evidence=raw_param_type,
                                    metadata={"handler": method_symbol, "raw_type": raw_param_type},
                                )
                            )
                        if resolved_return_type:
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_RETURNS_TYPE",
                                    obj=f"type:{resolved_return_type}",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.84,
                                    evidence=return_type_raw or resolved_return_type,
                                    metadata={"handler": method_symbol, "raw_type": return_type_raw},
                                )
                            )
                        for resolved_contains_type in resolved_return_contains:
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_RETURNS_CONTAINS_TYPE",
                                    obj=f"type:{resolved_contains_type}",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.79,
                                    evidence=return_type_raw or resolved_contains_type,
                                    metadata={"handler": method_symbol, "raw_type": return_type_raw},
                                )
                            )
                        for _, method_called, target_symbol in dependency_calls:
                            facts.append(
                                build_fact(
                                    subject=route_symbol,
                                    predicate="ROUTE_CALLS",
                                    obj=target_symbol,
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.92,
                                    evidence=f"{route_method} {route_path} -> {method_called}",
                                )
                            )
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="HANDLES_ROUTE",
                                obj=route_symbol,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.97,
                                evidence=f"{route_method} {route_path}",
                            )
                        )

                    security_expressions: Set[str] = set(class_security_rules.get(current_class_symbol, []))
                    for ann in pending_annotations:
                        ann_name = annotation_simple_name(ann)
                        ann_security = extract_security_expression(ann)
                        if ann_security:
                            security_expressions.add(ann_security)
                        if ann_name == "Transactional":
                            facts.append(
                                build_fact(
                                    subject=method_symbol,
                                    predicate="TRANSACTIONAL",
                                    obj="true",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.92,
                                    evidence=ann,
                                )
                            )
                        elif ann_name == "Retryable":
                            retry_tokens = [f"{key}={value}" for key, value in RETRY_KV_RE.findall(ann)]
                            detail = ", ".join(retry_tokens) if retry_tokens else "retryable"
                            facts.append(
                                build_fact(
                                    subject=method_symbol,
                                    predicate="RETRY_POLICY",
                                    obj=detail,
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.88,
                                    evidence=ann,
                                )
                            )
                        elif ann_name == "Scheduled":
                            parts = [f"{k}={v}" for k, v in SCHEDULED_KV_RE.findall(ann)]
                            schedule_value = ", ".join(parts) if parts else "scheduled"
                            facts.append(
                                build_fact(
                                    subject=method_symbol,
                                    predicate="SCHEDULE_TRIGGER",
                                    obj=schedule_value,
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.9,
                                    evidence=ann,
                                )
                            )
                        elif ann_name in {"EventListener", "TransactionalEventListener"}:
                            if resolved_parameter_types:
                                event_symbol = resolved_parameter_types[0][1]
                                if event_symbol:
                                    facts.append(
                                        build_fact(
                                            subject=method_symbol,
                                            predicate="LISTENS_EVENT",
                                            obj=f"event:{event_symbol}",
                                            file_path=rel_path,
                                            line_start=method_start,
                                            line_end=method_end,
                                            confidence=0.92,
                                            evidence=event_symbol,
                                        )
                                    )
                        elif ann_name == "Async":
                            facts.append(
                                build_fact(
                                    subject=method_symbol,
                                    predicate="EXECUTION_MODE",
                                    obj="async",
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=0.9,
                                    evidence=ann,
                                )
                            )

                    for expression in sorted(security_expressions):
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="SECURED_BY",
                                obj=expression,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.95,
                                evidence=expression,
                            )
                                    )

                    for query_text, is_native in query_specs:
                        predicate = query_predicate_for_text(query_text)
                        for target in extract_query_targets(query_text, is_native):
                            confidence = 0.91 if is_native else 0.78
                            facts.append(
                                build_fact(
                                    subject=method_symbol,
                                    predicate=predicate,
                                    obj=target,
                                    file_path=rel_path,
                                    line_start=method_start,
                                    line_end=method_end,
                                    confidence=confidence,
                                    evidence=compact_excerpt(query_text, max_lines=4, max_chars=320),
                                    metadata={"native_query": bool(is_native)},
                                )
                            )

                    for event_token in extract_published_event_symbols(method_body):
                        resolved_event = resolve_java_type_symbol(
                            event_token,
                            package_name,
                            direct_imports,
                            wildcard_imports,
                        )
                        target = f"event:{resolved_event or event_token}"
                        facts.append(
                            build_fact(
                                subject=method_symbol,
                                predicate="EMITS_EVENT",
                                obj=target,
                                file_path=rel_path,
                                line_start=method_start,
                                line_end=method_end,
                                confidence=0.9,
                                evidence=event_token,
                            )
                        )

                    if rel_path.startswith("erp-domain/src/test/java/"):
                        test_mentions.extend(word_tokens(method_body))

                    pending_annotations = []
                    i = method_end
                    continue

                pending_annotations = []

        if stripped:
            pending_annotations = []
        i += 1

    return ParseOutcome(
        chunks=chunks,
        facts=facts,
        class_symbols=class_symbols,
        test_mentions=test_mentions,
    )


def parse_generic_text(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    lines = text.splitlines()
    chunks: List[ChunkRecord] = []

    block_size = 120
    for start in range(0, len(lines), block_size):
        end = min(start + block_size, len(lines))
        body = "\n".join(lines[start:end]).strip()
        if not body:
            continue
        chunks.append(
            ChunkRecord(
                file_path=rel_path,
                line_start=start + 1,
                line_end=end,
                kind="text_block",
                symbol=f"text:{rel_path}:{start + 1}-{end}",
                language=language,
                module=module,
                content=body,
                metadata={"source_type": "text"},
            )
        )

    return ParseOutcome(chunks=chunks, facts=[], class_symbols={}, test_mentions=[])


def parse_file(rel_path: str, text: str, language: str, module: str) -> ParseOutcome:
    if rel_path.startswith("tickets/") and rel_path.endswith("/ticket.yaml"):
        return parse_ticket_yaml(rel_path, text, language, module)
    if rel_path.startswith("tickets/") and rel_path.endswith("/SUMMARY.md"):
        return parse_ticket_summary_md(rel_path, text, language, module)
    if rel_path.startswith("tickets/") and rel_path.endswith("/TIMELINE.md"):
        return parse_ticket_timeline_md(rel_path, text, language, module)
    if rel_path == "erp-domain/docs/endpoint_inventory.tsv":
        return parse_endpoint_inventory_tsv(rel_path, text, language, module)
    if rel_path.startswith("docs/system-map/modules/") and rel_path.endswith("/FILES.md"):
        return parse_module_files_map(rel_path, text, language, module)
    if rel_path == "docs/CODE-RED/confidence-suite/TEST_CATALOG.json":
        return parse_test_catalog_json(rel_path, text, language, module)
    if rel_path == "docs/system-map/CROSS_MODULE_WORKFLOWS.md":
        return parse_cross_module_workflows_md(rel_path, text, language, module)
    if rel_path == "docs/system-map/MODULE_BOUNDARIES.md":
        return parse_module_boundaries_md(rel_path, text, language, module)
    if rel_path == "erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md":
        return parse_cross_module_linkage_md(rel_path, text, language, module)
    if language == "markdown":
        return parse_markdown(rel_path, text, language, module)
    if language in {"properties", "env"}:
        return parse_properties_or_env(rel_path, text, language, module)
    if language == "yaml":
        return parse_yaml(rel_path, text, language, module)
    if language == "xml" and rel_path.endswith("pom.xml"):
        return parse_pom_xml(rel_path, text, language, module)
    if language == "sql":
        return parse_sql(rel_path, text, language, module)
    if language == "json" and rel_path.endswith("openapi.json"):
        return parse_openapi_json(rel_path, text, language, module)
    if language == "python":
        return parse_python(rel_path, text, language, module)
    if language == "bash":
        return parse_shell(rel_path, text, language, module)
    if language == "java":
        return parse_java(rel_path, text, language, module)
    return parse_generic_text(rel_path, text, language, module)


def add_test_coverage_facts(
    outcomes: List[Tuple[str, ParseOutcome]],
    global_class_symbols: Dict[str, str],
) -> List[FactRecord]:
    coverage_facts: List[FactRecord] = []
    for rel_path, outcome in outcomes:
        if not rel_path.startswith("erp-domain/src/test/java/"):
            continue

        mentions = set(outcome.test_mentions)
        for class_name, class_symbol in global_class_symbols.items():
            if class_name.lower() in {token.lower() for token in mentions}:
                coverage_facts.append(
                    build_fact(
                        subject=f"test:{rel_path}",
                        predicate="TEST_COVERS",
                        obj=class_symbol,
                        file_path=rel_path,
                        line_start=1,
                        line_end=1,
                        confidence=0.6,
                        evidence=class_name,
                    )
                )
    return coverage_facts


def main() -> int:
    args = parse_args()
    start_ts = time.time()

    repo_root = Path(args.repo_root).resolve()
    db_path = (repo_root / args.db_path).resolve() if not Path(args.db_path).is_absolute() else Path(args.db_path)

    store = RagStore(db_path)
    store.init_schema()

    previous_indexed_commit = store.get_meta("commit_sha")
    branch = current_branch(repo_root)
    commit_sha = current_commit(repo_root)

    diff_base_used = ""
    removed_stale_files: List[str] = []

    if args.changed_only:
        diff_base_used = args.diff_base.strip()
        if not diff_base_used:
            diff_base_used = previous_indexed_commit
        if not diff_base_used:
            print(
                "[rag-index] --changed-only requires --diff-base or an existing indexed commit baseline",
                file=sys.stderr,
            )
            return 2
        if not commit_exists(repo_root, diff_base_used):
            print(
                f"[rag-index] diff base commit not found: {diff_base_used}",
                file=sys.stderr,
            )
            return 2
        candidate_files = changed_files(repo_root, diff_base_used)
    else:
        candidate_files = list_repo_files(repo_root, limit_files=args.limit_files)

    if args.limit_files > 0 and args.changed_only:
        candidate_files = candidate_files[: args.limit_files]

    candidate_set = set(candidate_files)
    prune_missing_enabled = (
        (not args.changed_only)
        and (not args.no_prune_missing)
        and args.limit_files <= 0
    )

    if prune_missing_enabled:
        indexed_files = set(store.list_indexed_files())
        removed_stale_files = sorted(path for path in indexed_files if path not in candidate_set)
        for rel_path in removed_stale_files:
            store.remove_file_data(rel_path)

    # Remove deleted files from index when running changed-only.
    if args.changed_only:
        for rel_path in list(candidate_set):
            abs_path = repo_root / rel_path
            if not abs_path.exists():
                store.remove_file_data(rel_path)

    indexed_files = 0
    skipped_unchanged = 0
    skipped_oversized = 0
    parse_failures: List[str] = []
    total_chunks = 0
    total_facts = 0

    parsed_outcomes: List[Tuple[str, ParseOutcome]] = []
    all_class_symbols: Dict[str, str] = {}

    for rel_path in candidate_files:
        abs_path = repo_root / rel_path
        if not abs_path.exists() or not abs_path.is_file():
            continue

        try:
            file_size = abs_path.stat().st_size
            if args.max_file_bytes > 0 and file_size > args.max_file_bytes:
                skipped_oversized += 1
                continue

            sha1 = compute_file_sha1(abs_path)
            if (not args.force) and store.get_indexed_sha1(rel_path) == sha1:
                skipped_unchanged += 1
                continue

            text = read_text_file(abs_path)
            language = detect_language(rel_path)
            module = detect_module(rel_path)

            outcome = parse_file(rel_path, text, language, module)
            parsed_outcomes.append((rel_path, outcome))
            all_class_symbols.update(outcome.class_symbols)

            store.remove_file_data(rel_path)
            store.upsert_file(
                file_path=rel_path,
                language=language,
                module=module,
                sha1=sha1,
                size_bytes=file_size,
                commit_sha=commit_sha,
                metadata={
                    "repo": "orchestrator_erp",
                    "branch": branch,
                    "commit": commit_sha,
                },
            )
            total_chunks += store.insert_chunks(outcome.chunks)
            total_facts += store.insert_facts(outcome.facts)
            indexed_files += 1
            if args.verbose:
                print(
                    f"[rag-index] indexed {rel_path} (chunks={len(outcome.chunks)} facts={len(outcome.facts)})"
                )
        except Exception as exc:  # noqa: BLE001
            parse_failures.append(f"{rel_path}: {exc}")
            if args.verbose:
                print(f"[rag-index] failed {rel_path}: {exc}", file=sys.stderr)

    coverage_facts = add_test_coverage_facts(parsed_outcomes, all_class_symbols)
    total_facts += store.insert_facts(coverage_facts)

    store.set_meta("repo_root", str(repo_root))
    store.set_meta("branch", branch)
    store.set_meta("commit_sha", commit_sha)
    store.set_meta("last_indexed_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))

    store.commit()
    store.close()

    elapsed_ms = int((time.time() - start_ts) * 1000)
    summary = {
        "repo_root": str(repo_root),
        "db_path": str(db_path),
        "branch": branch,
        "commit_sha": commit_sha,
        "previous_indexed_commit": previous_indexed_commit or None,
        "diff_base_used": diff_base_used or None,
        "candidate_files": len(candidate_files),
        "prune_missing_enabled": prune_missing_enabled,
        "indexed_files": indexed_files,
        "skipped_unchanged": skipped_unchanged,
        "skipped_oversized": skipped_oversized,
        "removed_stale_files": len(removed_stale_files),
        "removed_stale_file_paths": removed_stale_files[:30],
        "chunks_inserted": total_chunks,
        "facts_inserted": total_facts,
        "coverage_facts": len(coverage_facts),
        "parse_failures": parse_failures,
        "elapsed_ms": elapsed_ms,
        "passes": len(parse_failures) == 0,
    }

    print("[rag-index] summary:")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
