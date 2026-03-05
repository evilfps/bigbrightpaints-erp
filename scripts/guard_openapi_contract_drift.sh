#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${OPENAPI_CONTRACT_DRIFT_MODE:-verify}"
OPENAPI_SPEC="${OPENAPI_CONTRACT_DRIFT_OPENAPI_SPEC:-$ROOT_DIR/openapi.json}"
ENDPOINT_INVENTORY_DOC="${OPENAPI_CONTRACT_DRIFT_ENDPOINT_INVENTORY_DOC:-$ROOT_DIR/docs/endpoint-inventory.md}"
REMEDIATION_COMMAND="${OPENAPI_CONTRACT_DRIFT_REMEDIATION_COMMAND:-OPENAPI_CONTRACT_DRIFT_MODE=report bash scripts/guard_openapi_contract_drift.sh}"

fail() {
  echo "[guard_openapi_contract_drift] FAIL: $1" >&2
  echo "[guard_openapi_contract_drift] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

[[ -f "$OPENAPI_SPEC" ]] || fail "missing required file: $OPENAPI_SPEC"
if [[ ! -f "$ENDPOINT_INVENTORY_DOC" ]]; then
  echo "[guard_openapi_contract_drift] WARN: missing optional endpoint inventory doc: $ENDPOINT_INVENTORY_DOC"
  echo "[guard_openapi_contract_drift] WARN: continuing with fail-open compatibility mode"
  exit 0
fi

case "$MODE" in
  verify|report)
    ;;
  *)
    fail "invalid OPENAPI_CONTRACT_DRIFT_MODE='$MODE' (expected verify or report)"
    ;;
esac

python3 - "$MODE" "$OPENAPI_SPEC" "$ENDPOINT_INVENTORY_DOC" "$REMEDIATION_COMMAND" <<'PY'
import collections
import hashlib
import json
import re
import sys
from pathlib import Path

mode, openapi_path, inventory_path, remediation_command = sys.argv[1:5]
http_methods = {"get", "post", "put", "patch", "delete", "options", "head", "trace"}

openapi_raw = Path(openapi_path).read_bytes()
openapi_sha256 = hashlib.sha256(openapi_raw).hexdigest()
spec = json.loads(openapi_raw.decode("utf-8"))

paths = spec.get("paths")
if not isinstance(paths, dict):
    print("[guard_openapi_contract_drift] FAIL: OpenAPI spec missing object 'paths'", file=sys.stderr)
    print(f"[guard_openapi_contract_drift] REMEDIATION: run '{remediation_command}'", file=sys.stderr)
    raise SystemExit(1)

openapi_entries = set()
for path, path_item in paths.items():
    if not isinstance(path_item, dict):
        continue
    for method in path_item:
        if method.lower() in http_methods:
            openapi_entries.add((method.upper(), path))

openapi_total_paths = len(paths)
openapi_total_operations = len(openapi_entries)

inventory_text = Path(inventory_path).read_text(encoding="utf-8")
inventory_line_re = re.compile(r"^- `([A-Z]+(?:, [A-Z]+)*)` `(/api(?:/v1)?/[^`]+)`\r?$")

inventory_entries = []
for raw_line in inventory_text.splitlines():
    match = inventory_line_re.match(raw_line)
    if not match:
        continue
    methods = [value.strip() for value in match.group(1).split(",")]
    endpoint_path = match.group(2)
    for method in methods:
        inventory_entries.append((method, endpoint_path))

inventory_counter = collections.Counter(inventory_entries)
inventory_duplicates = sorted((entry, count) for entry, count in inventory_counter.items() if count > 1)
inventory_entry_set = set(inventory_entries)
inventory_total_operations = len(inventory_entry_set)
inventory_total_paths = len({path for _, path in inventory_entry_set})

declared_sha_match = re.search(
    r"OpenAPI snapshot:\s*`openapi\.json`\s*\(sha256\s*`([0-9a-f]{64})`\)",
    inventory_text,
)
declared_paths_match = re.search(r"OpenAPI total paths:\s*`([0-9]+)`", inventory_text)
declared_operations_match = re.search(r"OpenAPI total operations:\s*`([0-9]+)`", inventory_text)

declared_sha = declared_sha_match.group(1) if declared_sha_match else None
declared_paths = int(declared_paths_match.group(1)) if declared_paths_match else None
declared_operations = int(declared_operations_match.group(1)) if declared_operations_match else None

missing_from_inventory = sorted(openapi_entries - inventory_entry_set)
extra_in_inventory = sorted(inventory_entry_set - openapi_entries)

if mode == "report":
    print("[guard_openapi_contract_drift] REPORT")
    print(f"[guard_openapi_contract_drift] openapi_sha256={openapi_sha256}")
    print(f"[guard_openapi_contract_drift] openapi_total_paths={openapi_total_paths}")
    print(f"[guard_openapi_contract_drift] openapi_total_operations={openapi_total_operations}")
    print(f"[guard_openapi_contract_drift] inventory_total_paths={inventory_total_paths}")
    print(f"[guard_openapi_contract_drift] inventory_total_operations={inventory_total_operations}")
    print(
        "[guard_openapi_contract_drift] expected_inventory_snapshot_line="
        f"OpenAPI snapshot: `openapi.json` (sha256 `{openapi_sha256}`)"
    )
    print(
        "[guard_openapi_contract_drift] expected_inventory_paths_line="
        f"OpenAPI total paths: `{openapi_total_paths}`"
    )
    print(
        "[guard_openapi_contract_drift] expected_inventory_operations_line="
        f"OpenAPI total operations: `{openapi_total_operations}`"
    )
    if inventory_duplicates:
        preview = ", ".join(f"{method} {path} (x{count})" for (method, path), count in inventory_duplicates[:5])
        print(f"[guard_openapi_contract_drift] inventory_duplicates={preview}")
    if missing_from_inventory:
        preview = ", ".join(f"{method} {path}" for method, path in missing_from_inventory[:5])
        print(f"[guard_openapi_contract_drift] missing_from_inventory={preview}")
    if extra_in_inventory:
        preview = ", ".join(f"{method} {path}" for method, path in extra_in_inventory[:5])
        print(f"[guard_openapi_contract_drift] extra_in_inventory={preview}")
    raise SystemExit(0)

errors = []

if declared_sha is None:
    errors.append("docs/endpoint-inventory.md is missing the OpenAPI snapshot sha256 line")
elif declared_sha != openapi_sha256:
    errors.append(
        "OpenAPI snapshot sha256 mismatch "
        f"(declared={declared_sha}, actual={openapi_sha256})"
    )

if declared_paths is None:
    errors.append("docs/endpoint-inventory.md is missing the OpenAPI total paths line")
elif declared_paths != openapi_total_paths:
    errors.append(
        "OpenAPI total paths mismatch "
        f"(declared={declared_paths}, actual={openapi_total_paths})"
    )

if declared_operations is None:
    errors.append("docs/endpoint-inventory.md is missing the OpenAPI total operations line")
elif declared_operations != openapi_total_operations:
    errors.append(
        "OpenAPI total operations mismatch "
        f"(declared={declared_operations}, actual={openapi_total_operations})"
    )

if inventory_duplicates:
    preview = ", ".join(f"{method} {path} (x{count})" for (method, path), count in inventory_duplicates[:5])
    errors.append(f"duplicate method/path entries found in docs/endpoint-inventory.md ({preview})")

if inventory_total_operations != openapi_total_operations:
    errors.append(
        "docs/endpoint-inventory.md method/path inventory size mismatch "
        f"(inventory={inventory_total_operations}, openapi={openapi_total_operations})"
    )

if inventory_total_paths != openapi_total_paths:
    errors.append(
        "docs/endpoint-inventory.md unique path count mismatch "
        f"(inventory={inventory_total_paths}, openapi={openapi_total_paths})"
    )

if missing_from_inventory:
    preview = ", ".join(f"{method} {path}" for method, path in missing_from_inventory[:5])
    errors.append(f"OpenAPI endpoints missing from docs/endpoint-inventory.md ({preview})")

if extra_in_inventory:
    preview = ", ".join(f"{method} {path}" for method, path in extra_in_inventory[:5])
    errors.append(f"docs/endpoint-inventory.md contains endpoints absent from OpenAPI ({preview})")

if errors:
    print(
        "[guard_openapi_contract_drift] FAIL: OpenAPI contract drift detected in verification mode",
        file=sys.stderr,
    )
    for issue in errors:
        print(f"[guard_openapi_contract_drift] FAIL: {issue}", file=sys.stderr)
    print(f"[guard_openapi_contract_drift] REMEDIATION: run '{remediation_command}'", file=sys.stderr)
    raise SystemExit(1)

print("[guard_openapi_contract_drift] OK")
PY
