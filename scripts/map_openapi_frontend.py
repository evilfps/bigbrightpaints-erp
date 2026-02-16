#!/usr/bin/env python3
"""
Generate frontend-oriented endpoint maps from an OpenAPI specification.

Outputs:
- JSON contract map for tooling/automation
- Markdown report for designers and frontend engineers
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import yaml

HTTP_METHOD_ORDER = ["get", "post", "put", "patch", "delete", "options", "head"]
HTTP_METHOD_SET = set(HTTP_METHOD_ORDER)
PREFERRED_CONTENT_TYPES = [
    "application/json",
    "multipart/form-data",
    "application/x-www-form-urlencoded",
    "application/octet-stream",
    "text/plain",
]


def load_openapi(path: Path) -> Dict[str, Any]:
    raw = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".yml", ".yaml"}:
        parsed = yaml.safe_load(raw)
    else:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = yaml.safe_load(raw)
    if not isinstance(parsed, dict):
        raise ValueError("OpenAPI root must be an object")
    if "paths" not in parsed or not isinstance(parsed.get("paths"), dict):
        raise ValueError("OpenAPI spec must contain a 'paths' object")
    return parsed


def unescape_pointer_token(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


def resolve_pointer(root: Dict[str, Any], ref: str) -> Any:
    if not ref.startswith("#/"):
        raise ValueError(f"Only local refs are supported, got: {ref}")
    current: Any = root
    for token in ref[2:].split("/"):
        key = unescape_pointer_token(token)
        if isinstance(current, dict) and key in current:
            current = current[key]
            continue
        raise KeyError(f"Unable to resolve ref path segment '{key}' in {ref}")
    return current


def deep_resolve(value: Any, root: Dict[str, Any], seen: Optional[set[str]] = None) -> Any:
    if seen is None:
        seen = set()
    if isinstance(value, list):
        return [deep_resolve(v, root, seen) for v in value]
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            if not isinstance(ref, str):
                return value
            if ref in seen:
                return {"$ref": ref, "_ref_cycle": True}
            target = deep_resolve(resolve_pointer(root, ref), root, seen | {ref})
            siblings = {k: v for k, v in value.items() if k != "$ref"}
            if not siblings:
                return target
            resolved_siblings = deep_resolve(siblings, root, seen | {ref})
            if isinstance(target, dict) and isinstance(resolved_siblings, dict):
                merged = dict(target)
                merged.update(resolved_siblings)
                return merged
            return resolved_siblings
        return {k: deep_resolve(v, root, seen) for k, v in value.items()}
    return value


def infer_schema_type(schema: Dict[str, Any]) -> str:
    schema_type = schema.get("type")
    if isinstance(schema_type, list):
        return "|".join(str(t) for t in schema_type)
    if isinstance(schema_type, str):
        return schema_type
    if "properties" in schema:
        return "object"
    if "items" in schema:
        return "array"
    if "allOf" in schema:
        return "allOf"
    if "oneOf" in schema:
        return "oneOf"
    if "anyOf" in schema:
        return "anyOf"
    return "unknown"


def iter_schema_fields(
    schema: Dict[str, Any],
    *,
    prefix: str = "",
    parent_required: Optional[set[str]] = None,
    depth: int = 0,
    max_depth: int = 2,
    max_fields: int = 40,
) -> List[Dict[str, Any]]:
    fields: List[Dict[str, Any]] = []
    if len(fields) >= max_fields:
        return fields

    schema_type = infer_schema_type(schema)
    if schema_type != "object":
        return fields

    properties = schema.get("properties") or {}
    required = set(schema.get("required") or [])
    if parent_required is not None and not required:
        required = parent_required

    for name, sub in properties.items():
        if not isinstance(sub, dict):
            continue
        field_name = f"{prefix}.{name}" if prefix else name
        field_type = infer_schema_type(sub)
        fields.append(
            {
                "name": field_name,
                "required": name in required,
                "type": field_type,
                "format": sub.get("format"),
                "enum": sub.get("enum"),
                "description": sub.get("description"),
            }
        )
        if len(fields) >= max_fields:
            break
        if depth < max_depth:
            if field_type == "object":
                fields.extend(
                    iter_schema_fields(
                        sub,
                        prefix=field_name,
                        depth=depth + 1,
                        max_depth=max_depth,
                        max_fields=max_fields - len(fields),
                    )
                )
            elif field_type == "array":
                items = sub.get("items")
                if isinstance(items, dict) and infer_schema_type(items) == "object":
                    fields.extend(
                        iter_schema_fields(
                            items,
                            prefix=f"{field_name}[]",
                            depth=depth + 1,
                            max_depth=max_depth,
                            max_fields=max_fields - len(fields),
                        )
                    )
        if len(fields) >= max_fields:
            break
    return fields


def schema_summary(schema: Optional[Dict[str, Any]], *, max_fields: int = 30) -> Dict[str, Any]:
    if not isinstance(schema, dict):
        return {"type": "none", "fields": []}
    summary: Dict[str, Any] = {
        "type": infer_schema_type(schema),
        "description": schema.get("description"),
        "required": schema.get("required") or [],
        "fields": iter_schema_fields(schema, max_fields=max_fields),
    }
    if summary["type"] == "array":
        items = schema.get("items")
        if isinstance(items, dict):
            summary["items_type"] = infer_schema_type(items)
            summary["items_fields"] = iter_schema_fields(items, max_fields=max_fields)
    if schema.get("enum"):
        summary["enum"] = schema["enum"]
    if schema.get("format"):
        summary["format"] = schema["format"]
    if schema.get("allOf"):
        summary["allOf_count"] = len(schema["allOf"])
    if schema.get("oneOf"):
        summary["oneOf_count"] = len(schema["oneOf"])
    if schema.get("anyOf"):
        summary["anyOf_count"] = len(schema["anyOf"])
    return summary


def normalize_parameter(param: Dict[str, Any]) -> Dict[str, Any]:
    schema = param.get("schema") if isinstance(param.get("schema"), dict) else {}
    return {
        "name": param.get("name"),
        "in": param.get("in"),
        "required": bool(param.get("required")),
        "type": infer_schema_type(schema),
        "format": schema.get("format"),
        "enum": schema.get("enum"),
        "description": param.get("description"),
        "example": param.get("example"),
    }


def merge_parameters(path_item: Dict[str, Any], operation: Dict[str, Any]) -> List[Dict[str, Any]]:
    merged: List[Dict[str, Any]] = []
    seen: set[Tuple[Optional[str], Optional[str]]] = set()
    for source in (path_item.get("parameters"), operation.get("parameters")):
        if not isinstance(source, list):
            continue
        for raw in source:
            if not isinstance(raw, dict):
                continue
            key = (raw.get("name"), raw.get("in"))
            if key in seen:
                continue
            seen.add(key)
            merged.append(normalize_parameter(raw))
    return merged


def sort_content_types(content: Dict[str, Any]) -> List[str]:
    keys = list(content.keys())
    rank = {c: i for i, c in enumerate(PREFERRED_CONTENT_TYPES)}
    return sorted(keys, key=lambda c: (rank.get(c, 999), c))


def parse_request_body(operation: Dict[str, Any], *, max_fields: int) -> Dict[str, Any]:
    request = operation.get("requestBody")
    if not isinstance(request, dict):
        return {
            "required": False,
            "present": False,
            "content_types": [],
            "content": {},
            "upload_hint": False,
        }

    content = request.get("content")
    if not isinstance(content, dict):
        content = {}

    parsed_content: Dict[str, Any] = {}
    upload_hint = False
    for content_type in sort_content_types(content):
        meta = content.get(content_type)
        if not isinstance(meta, dict):
            continue
        schema = meta.get("schema")
        summary = schema_summary(schema if isinstance(schema, dict) else None, max_fields=max_fields)
        parsed_content[content_type] = summary
        if (
            "multipart/form-data" in content_type
            or "application/octet-stream" in content_type
            or summary.get("format") == "binary"
        ):
            upload_hint = True

    return {
        "required": bool(request.get("required")),
        "present": True,
        "description": request.get("description"),
        "content_types": list(parsed_content.keys()),
        "content": parsed_content,
        "upload_hint": upload_hint,
    }


def parse_responses(operation: Dict[str, Any], *, max_fields: int) -> Dict[str, Any]:
    responses = operation.get("responses")
    if not isinstance(responses, dict):
        return {"responses": {}, "success_statuses": [], "error_statuses": [], "download_hint": False}

    parsed: Dict[str, Any] = {}
    success_statuses: List[str] = []
    error_statuses: List[str] = []
    download_hint = False

    for status in sorted(responses.keys(), key=lambda s: (len(s), s)):
        response = responses.get(status)
        if not isinstance(response, dict):
            continue
        content = response.get("content")
        if not isinstance(content, dict):
            content = {}
        parsed_content: Dict[str, Any] = {}
        for content_type in sort_content_types(content):
            meta = content.get(content_type)
            if not isinstance(meta, dict):
                continue
            schema = meta.get("schema")
            summary = schema_summary(schema if isinstance(schema, dict) else None, max_fields=max_fields)
            parsed_content[content_type] = summary
            if (
                "application/octet-stream" in content_type
                or "application/pdf" in content_type
                or content_type.startswith("image/")
                or summary.get("format") == "binary"
            ):
                download_hint = True

        parsed[status] = {
            "description": response.get("description"),
            "content_types": list(parsed_content.keys()),
            "content": parsed_content,
        }
        if status.startswith("2"):
            success_statuses.append(status)
        elif status != "default":
            error_statuses.append(status)

    return {
        "responses": parsed,
        "success_statuses": success_statuses,
        "error_statuses": error_statuses,
        "download_hint": download_hint,
    }


def describe_security(
    operation: Dict[str, Any],
    path_item: Dict[str, Any],
    spec: Dict[str, Any],
) -> Dict[str, Any]:
    security = operation.get("security")
    if security is None:
        security = path_item.get("security")
    if security is None:
        security = spec.get("security")

    schemes = (
        spec.get("components", {}).get("securitySchemes", {})
        if isinstance(spec.get("components"), dict)
        else {}
    )

    if security is None:
        return {"mode": "unspecified", "requirements": [], "notes": ["No explicit security section."]}
    if security == []:
        return {"mode": "public", "requirements": [], "notes": ["Operation explicitly allows anonymous access."]}

    requirements = []
    for option in security:
        if not isinstance(option, dict):
            continue
        option_parts = []
        for scheme_name, scopes in option.items():
            scheme_meta = schemes.get(scheme_name) if isinstance(schemes, dict) else None
            scheme_type = scheme_meta.get("type") if isinstance(scheme_meta, dict) else None
            bearer = scheme_meta.get("scheme") if isinstance(scheme_meta, dict) else None
            option_parts.append(
                {
                    "scheme": scheme_name,
                    "type": scheme_type,
                    "transport": bearer,
                    "scopes": scopes if isinstance(scopes, list) else [],
                }
            )
        if option_parts:
            requirements.append(option_parts)
    return {"mode": "secured", "requirements": requirements, "notes": []}


def classify_pagination(params: List[Dict[str, Any]]) -> Optional[str]:
    names = {str(p.get("name", "")).lower() for p in params if p.get("in") == "query"}
    if {"page", "size"}.issubset(names):
        return "page-size"
    if {"limit", "offset"}.issubset(names):
        return "limit-offset"
    if {"cursor", "limit"}.issubset(names):
        return "cursor-limit"
    if "cursor" in names:
        return "cursor"
    return None


def suggest_screens(method: str, path: str) -> List[str]:
    has_id = "{" in path and "}" in path
    if method == "get":
        return ["detail-view"] if has_id else ["list-view"]
    if method == "post":
        return ["create-form"]
    if method in {"put", "patch"}:
        return ["edit-form"]
    if method == "delete":
        return ["delete-action"]
    return ["custom-action"]


def pick_primary_success_schema(responses: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    for status in sorted(responses.keys(), key=lambda s: (s != "200", s)):
        if not status.startswith("2"):
            continue
        meta = responses.get(status) or {}
        content = meta.get("content") or {}
        if not isinstance(content, dict):
            continue
        for content_type in sort_content_types(content):
            cmeta = content.get(content_type)
            if isinstance(cmeta, dict) and isinstance(cmeta.get("schema"), dict):
                return cmeta["schema"]
    return None


def choose_table_columns_from_schema(schema: Optional[Dict[str, Any]]) -> List[str]:
    if not isinstance(schema, dict):
        return []
    t = infer_schema_type(schema)
    if t == "object":
        props = schema.get("properties") or {}
        if isinstance(props, dict):
            return list(props.keys())[:8]
    if t == "array":
        items = schema.get("items")
        if isinstance(items, dict) and infer_schema_type(items) == "object":
            props = items.get("properties") or {}
            if isinstance(props, dict):
                return list(props.keys())[:8]
    return []


def frontend_expectations(
    method: str,
    path: str,
    params: List[Dict[str, Any]],
    request_body: Dict[str, Any],
    operation: Dict[str, Any],
) -> Dict[str, Any]:
    query_params = [p for p in params if p.get("in") == "query"]
    request_fields: List[str] = []
    for content in (request_body.get("content") or {}).values():
        if not isinstance(content, dict):
            continue
        for f in content.get("fields") or []:
            if isinstance(f, dict) and f.get("name"):
                request_fields.append(f["name"])
    request_fields = sorted(set(request_fields))[:20]

    responses = operation.get("responses") if isinstance(operation.get("responses"), dict) else {}
    primary_schema = pick_primary_success_schema(responses)
    table_columns = choose_table_columns_from_schema(primary_schema)

    pagination = classify_pagination(params)
    filter_controls = [p["name"] for p in query_params if p.get("name")]
    sort_detected = any((p.get("name") or "").lower() in {"sort", "sortby", "order", "direction"} for p in query_params)
    search_detected = any((p.get("name") or "").lower() in {"q", "query", "search", "keyword"} for p in query_params)

    states = ["loading", "error", "success"]
    if method == "get":
        states.append("empty")

    ux_notes = []
    if pagination:
        ux_notes.append(f"Implement {pagination} pagination controls.")
    if sort_detected:
        ux_notes.append("Expose sort controls based on query params.")
    if search_detected:
        ux_notes.append("Expose search input tied to query params.")
    if request_body.get("upload_hint"):
        ux_notes.append("Use file picker and multipart upload UX.")

    return {
        "screens": suggest_screens(method, path),
        "form_fields": request_fields,
        "filter_controls": filter_controls[:20],
        "table_columns": table_columns,
        "states": states,
        "ux_notes": ux_notes,
    }


def operation_contract(
    spec: Dict[str, Any],
    path: str,
    method: str,
    path_item: Dict[str, Any],
    operation: Dict[str, Any],
    *,
    max_fields: int,
) -> Dict[str, Any]:
    tags = operation.get("tags") if isinstance(operation.get("tags"), list) else []
    module = tags[0] if tags else "untagged"

    params = merge_parameters(path_item, operation)
    request_body = parse_request_body(operation, max_fields=max_fields)
    responses = parse_responses(operation, max_fields=max_fields)
    security = describe_security(operation, path_item, spec)

    by_in = defaultdict(list)
    for p in params:
        location = str(p.get("in") or "unknown")
        by_in[location].append(p)

    frontend_map = frontend_expectations(method, path, params, request_body, operation)
    backend_expectations = {
        "auth": security,
        "path_params": by_in.get("path", []),
        "query_params": by_in.get("query", []),
        "header_params": by_in.get("header", []),
        "cookie_params": by_in.get("cookie", []),
        "request_body": request_body,
    }
    backend_returns = {
        "success_statuses": responses["success_statuses"],
        "error_statuses": responses["error_statuses"],
        "responses": responses["responses"],
        "download_hint": responses["download_hint"],
    }

    return {
        "module": module,
        "tags": tags,
        "method": method.upper(),
        "path": path,
        "operation_id": operation.get("operationId"),
        "summary": operation.get("summary"),
        "description": operation.get("description"),
        "frontend_ui_map": frontend_map,
        "backend_expectations": backend_expectations,
        "backend_returns": backend_returns,
    }


def summarize_modules(contracts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for c in contracts:
        grouped[c["module"]].append(c)
    rows = []
    for module in sorted(grouped.keys()):
        items = grouped[module]
        methods = Counter(item["method"] for item in items)
        rows.append(
            {
                "module": module,
                "endpoints": len(items),
                "methods": dict(sorted(methods.items())),
                "sample_paths": sorted({item["path"] for item in items})[:5],
            }
        )
    return rows


def render_markdown(payload: Dict[str, Any]) -> str:
    lines: List[str] = []
    meta = payload["meta"]
    lines.append(f"# Frontend Endpoint Map: {meta.get('title') or 'OpenAPI Spec'}")
    lines.append("")
    lines.append(f"- Source: `{meta['source']}`")
    lines.append(f"- OpenAPI: `{meta.get('openapi')}`")
    lines.append(f"- Version: `{meta.get('version')}`")
    lines.append(f"- Endpoints: `{payload['high_level']['total_endpoints']}`")
    lines.append("")

    lines.append("## High-Level Module Map")
    lines.append("")
    lines.append("| Module | Endpoints | Methods |")
    lines.append("|---|---:|---|")
    for module in payload["high_level"]["modules"]:
        methods = ", ".join(f"{k}:{v}" for k, v in module["methods"].items())
        lines.append(f"| `{module['module']}` | {module['endpoints']} | {methods} |")
    lines.append("")

    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for endpoint in payload["endpoints"]:
        grouped[endpoint["module"]].append(endpoint)

    lines.append("## Frontend vs Backend Contract Map")
    lines.append("")
    for module in sorted(grouped.keys()):
        lines.append(f"### {module}")
        lines.append("")
        for ep in sorted(grouped[module], key=lambda e: (e["path"], e["method"])):
            op = ep["operation_id"] or "-"
            lines.append(f"#### `{ep['method']} {ep['path']}`")
            lines.append("")
            lines.append(f"- Operation ID: `{op}`")
            if ep.get("summary"):
                lines.append(f"- Summary: {ep['summary']}")
            lines.append("- Frontend should put:")
            screens = ", ".join(ep["frontend_ui_map"]["screens"]) or "none"
            lines.append(f"  - Screens: {screens}")
            form_fields = ", ".join(ep["frontend_ui_map"]["form_fields"][:12]) or "none"
            lines.append(f"  - Form fields: {form_fields}")
            filters = ", ".join(ep["frontend_ui_map"]["filter_controls"][:12]) or "none"
            lines.append(f"  - Filters/search controls: {filters}")
            columns = ", ".join(ep["frontend_ui_map"]["table_columns"][:12]) or "none"
            lines.append(f"  - Candidate table columns: {columns}")
            states = ", ".join(ep["frontend_ui_map"]["states"])
            lines.append(f"  - UI states: {states}")
            if ep["frontend_ui_map"]["ux_notes"]:
                lines.append(f"  - UX notes: {' | '.join(ep['frontend_ui_map']['ux_notes'])}")

            lines.append("- Backend expects:")
            auth_mode = ep["backend_expectations"]["auth"]["mode"]
            lines.append(f"  - Auth mode: `{auth_mode}`")
            path_params = ep["backend_expectations"]["path_params"]
            query_params = ep["backend_expectations"]["query_params"]
            header_params = ep["backend_expectations"]["header_params"]
            body = ep["backend_expectations"]["request_body"]
            lines.append(f"  - Path params: {len(path_params)}")
            lines.append(f"  - Query params: {len(query_params)}")
            lines.append(f"  - Header params: {len(header_params)}")
            lines.append(f"  - Request body required: `{body['required']}`")
            lines.append(f"  - Request content types: {', '.join(body['content_types']) or 'none'}")

            lines.append("- Backend returns:")
            success = ", ".join(ep["backend_returns"]["success_statuses"]) or "none"
            errors = ", ".join(ep["backend_returns"]["error_statuses"]) or "none"
            lines.append(f"  - Success statuses: {success}")
            lines.append(f"  - Error statuses: {errors}")
            if ep["backend_returns"]["download_hint"]:
                lines.append("  - Download behavior: likely binary/file response")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def analyze_spec(spec: Dict[str, Any], source: str, *, max_fields: int) -> Dict[str, Any]:
    endpoints: List[Dict[str, Any]] = []
    paths = spec.get("paths") if isinstance(spec.get("paths"), dict) else {}
    for path in sorted(paths.keys()):
        path_item_raw = paths[path]
        if not isinstance(path_item_raw, dict):
            continue
        path_item = deep_resolve(path_item_raw, spec)
        for method in HTTP_METHOD_ORDER:
            operation_raw = path_item.get(method)
            if not isinstance(operation_raw, dict):
                continue
            operation = deep_resolve(operation_raw, spec)
            endpoints.append(
                operation_contract(
                    spec,
                    path,
                    method,
                    path_item,
                    operation,
                    max_fields=max_fields,
                )
            )

    payload = {
        "meta": {
            "source": source,
            "title": ((spec.get("info") or {}).get("title") if isinstance(spec.get("info"), dict) else None),
            "version": ((spec.get("info") or {}).get("version") if isinstance(spec.get("info"), dict) else None),
            "openapi": spec.get("openapi"),
        },
        "high_level": {
            "total_endpoints": len(endpoints),
            "modules": summarize_modules(endpoints),
        },
        "endpoints": endpoints,
    }
    return payload


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Map OpenAPI endpoints to frontend/backend contracts.")
    parser.add_argument("--input", required=True, help="Path to OpenAPI JSON/YAML file")
    parser.add_argument("--json-out", help="Path to write JSON output")
    parser.add_argument("--md-out", help="Path to write Markdown output")
    parser.add_argument(
        "--max-fields",
        type=int,
        default=30,
        help="Maximum fields to extract per schema summary",
    )
    args = parser.parse_args()

    input_path = Path(args.input).resolve()
    spec = load_openapi(input_path)
    payload = analyze_spec(spec, source=str(input_path), max_fields=max(1, args.max_fields))

    json_text = json.dumps(payload, indent=2, ensure_ascii=True) + "\n"
    md_text = render_markdown(payload)

    if args.json_out:
        out = Path(args.json_out).resolve()
        ensure_parent(out)
        out.write_text(json_text, encoding="utf-8")
        print(f"[OK] Wrote JSON map: {out}")
    else:
        print(json_text)

    if args.md_out:
        out = Path(args.md_out).resolve()
        ensure_parent(out)
        out.write_text(md_text, encoding="utf-8")
        print(f"[OK] Wrote Markdown map: {out}")
    else:
        print(md_text)


if __name__ == "__main__":
    main()
