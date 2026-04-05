#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DTO_FILE="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/dto/DispatchConfirmationResponse.java"
OPENAPI_FILE="$ROOT_DIR/openapi.json"
HANDOFF_FILE="$ROOT_DIR/.factory/library/frontend-handoff.md"
REMEDIATION_COMMAND="refresh the dispatch response bullets in .factory/library/frontend-handoff.md and keep them aligned with DispatchConfirmationResponse + openapi.json"

fail() {
  echo "[guard_dispatch_frontend_handoff_contract] FAIL: $1" >&2
  echo "[guard_dispatch_frontend_handoff_contract] remediation: $REMEDIATION_COMMAND" >&2
  exit 1
}

for path in "$DTO_FILE" "$OPENAPI_FILE" "$HANDOFF_FILE"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

python3 - "$DTO_FILE" "$OPENAPI_FILE" "$HANDOFF_FILE" <<'PY' || fail "dispatch frontend handoff parity check failed"
import json
import re
import sys
from pathlib import Path

dto_path, openapi_path, handoff_path = map(Path, sys.argv[1:4])

dto_text = dto_path.read_text(encoding="utf-8")
handoff_text = handoff_path.read_text(encoding="utf-8")
spec = json.loads(openapi_path.read_text(encoding="utf-8"))


def fail(message: str) -> None:
    print(f"[guard_dispatch_frontend_handoff_contract] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def extract_record_fields(text: str, record_name: str) -> list[str]:
    match = re.search(rf"record\s+{re.escape(record_name)}\s*\((.*?)\)\s*\{{", text, re.S)
    if not match:
        fail(f"missing DTO record declaration for {record_name}")
    fields: list[str] = []
    for raw in match.group(1).split(","):
        token = raw.strip()
        if not token:
            continue
        fields.append(token.split()[-1])
    return fields


def extract_schema_fields(schema_name: str) -> list[str]:
    schemas = spec.get("components", {}).get("schemas", {})
    schema = schemas.get(schema_name)
    if not isinstance(schema, dict):
        fail(f"missing OpenAPI schema {schema_name}")
    properties = schema.get("properties")
    if not isinstance(properties, dict):
        fail(f"OpenAPI schema {schema_name} is missing properties")
    return sorted(properties.keys())


def extract_doc_fields(record_name: str) -> list[str]:
    pattern = rf"^- `{re.escape(record_name)}`: .*$"
    match = re.search(pattern, handoff_text, re.MULTILINE)
    if not match:
        fail(f"missing frontend handoff bullet for {record_name}")
    line = match.group(0).split(". Pure factory operational views", 1)[0]
    tokens = re.findall(r"`([^`]+)`", line)
    if not tokens or tokens[0] != record_name:
        fail(f"frontend handoff bullet for {record_name} is malformed")
    fields = [
        token.replace("[]", "").strip()
        for token in tokens[1:]
        if token.strip() != "null"
    ]
    return sorted(fields)


dto_dispatch_fields = sorted(extract_record_fields(dto_text, "DispatchConfirmationResponse"))
dto_line_fields = sorted(extract_record_fields(dto_text, "LineResult"))
openapi_dispatch_fields = extract_schema_fields("DispatchConfirmationResponse")
openapi_line_fields = extract_schema_fields("LineResult")
doc_dispatch_fields = extract_doc_fields("DispatchConfirmationResponse")
doc_line_fields = extract_doc_fields("DispatchConfirmationResponse.LineResult")

if dto_dispatch_fields != openapi_dispatch_fields:
    fail(
        "DispatchConfirmationResponse DTO/OpenAPI mismatch: "
        f"dto={dto_dispatch_fields}, openapi={openapi_dispatch_fields}"
    )

if dto_line_fields != openapi_line_fields:
    fail(
        "DispatchConfirmationResponse.LineResult DTO/OpenAPI mismatch: "
        f"dto={dto_line_fields}, openapi={openapi_line_fields}"
    )

if doc_dispatch_fields != dto_dispatch_fields:
    fail(
        "frontend handoff DispatchConfirmationResponse fields drifted: "
        f"doc={doc_dispatch_fields}, dto={dto_dispatch_fields}"
    )

if doc_line_fields != dto_line_fields:
    fail(
        "frontend handoff DispatchConfirmationResponse.LineResult fields drifted: "
        f"doc={doc_line_fields}, dto={dto_line_fields}"
    )

for forbidden_field in ("salesOrderId", "finalInvoiceId", "arJournalEntryId"):
    if forbidden_field in doc_dispatch_fields or forbidden_field in doc_line_fields:
        fail(f"frontend handoff must not advertise retired dispatch response field {forbidden_field}")

expected_shadow_note = (
    "Do not expect `salesOrderId`, `finalInvoiceId`, `arJournalEntryId`, or other shadow invoice-link fields on this response"
)
if expected_shadow_note not in handoff_text:
    fail("frontend handoff must keep the explicit no-shadow-fields note for dispatch confirmation")

print("[guard_dispatch_frontend_handoff_contract] verified dispatch DTO/OpenAPI/frontend-handoff parity")
PY

echo "[guard_dispatch_frontend_handoff_contract] OK"
