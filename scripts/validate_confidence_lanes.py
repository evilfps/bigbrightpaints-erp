#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


REQUIRED_LANES = ("local", "pr", "main", "staging", "canary")
REQUIRED_DEPLOYABLE_PROOF_IDS = (
    "app_boots",
    "migrations_run",
    "auth_works",
    "tenant_isolation_holds",
    "o2c_path_works",
    "p2p_accounting_path_works",
    "health_or_readiness_is_real",
    "rollback_is_possible",
)
REQUIRED_CLASSIFICATIONS = ("product-bug", "bad-test", "infra-coupled")
REQUIRED_QUARANTINE_KEYS = (
    "owner",
    "repro_notes",
    "start_date",
    "expiry",
    "classification",
    "action",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate the repo confidence-lane contract")
    parser.add_argument(
        "--contract",
        default="testing/local/confidence-lanes.json",
        help="Path to the authoritative confidence-lane contract JSON",
    )
    parser.add_argument(
        "--lane",
        default="",
        help="Optional lane to spotlight (local|pr|main|staging|canary)",
    )
    parser.add_argument("--output", default="")
    return parser.parse_args()


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def non_empty_string(value: object) -> bool:
    return isinstance(value, str) and value.strip() != ""


def non_empty_list(value: object) -> bool:
    return isinstance(value, list) and len(value) > 0


def emit_summary(summary: dict, output: str) -> None:
    print("[validate_confidence_lanes] summary:")
    print(json.dumps(summary, indent=2, sort_keys=True))
    if output:
        out_path = Path(output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    contract_path = Path(args.contract)
    errors: list[str] = []

    if not contract_path.exists():
        summary = {
            "contract_path": contract_path.as_posix(),
            "errors": [f"contract missing: {contract_path.as_posix()}"],
            "passes": False,
        }
        emit_summary(summary, args.output)
        return 1

    contract = read_json(contract_path)
    lanes = contract.get("lanes")
    deployable_state = contract.get("deployable_state", {})
    broken_test_policy = contract.get("broken_test_policy", {})
    authoritative_surfaces = contract.get("authoritative_surfaces", {})

    if contract.get("contract_version") != 1:
        errors.append("contract_version must be 1")

    if tuple(contract.get("lane_order", [])) != REQUIRED_LANES:
        errors.append(f"lane_order must be {list(REQUIRED_LANES)}")

    if not isinstance(authoritative_surfaces, dict):
        errors.append("authoritative_surfaces must be an object")
    else:
        for key in ("packet", "library", "quarantine"):
            if not non_empty_string(authoritative_surfaces.get(key)):
                errors.append(f"authoritative_surfaces.{key} must be a non-empty string")

    if not isinstance(lanes, dict):
        errors.append("lanes must be an object")
        lanes = {}

    selected_lane = args.lane.strip()
    if selected_lane and selected_lane not in REQUIRED_LANES:
        errors.append(f"invalid --lane '{selected_lane}' (expected one of {list(REQUIRED_LANES)})")

    for lane_name in REQUIRED_LANES:
        lane = lanes.get(lane_name)
        if not isinstance(lane, dict):
            errors.append(f"missing lane contract: {lane_name}")
            continue
        for field in ("decision_point", "goal"):
            if not non_empty_string(lane.get(field)):
                errors.append(f"lane '{lane_name}' missing non-empty field '{field}'")
        if not non_empty_list(lane.get("prove")):
            errors.append(f"lane '{lane_name}' must define a non-empty prove list")

        primary_commands = lane.get("primary_commands")
        required_signals = lane.get("required_signals")
        if lane_name == "canary":
            if not non_empty_list(required_signals):
                errors.append("lane 'canary' must define non-empty required_signals")
        else:
            if not non_empty_list(primary_commands):
                errors.append(f"lane '{lane_name}' must define non-empty primary_commands")

        if lane_name in {"pr", "main", "staging"} and not non_empty_list(lane.get("required_scripts")):
            errors.append(f"lane '{lane_name}' must define non-empty required_scripts")
        if lane_name == "staging" and lane.get("deployable_state_required") is not True:
            errors.append("lane 'staging' must set deployable_state_required=true")

    if not non_empty_string(deployable_state.get("summary")):
        errors.append("deployable_state.summary must be a non-empty string")
    required_proof = deployable_state.get("required_proof")
    if not isinstance(required_proof, list):
        errors.append("deployable_state.required_proof must be a list")
        required_proof = []
    proof_ids = []
    for entry in required_proof:
        if not isinstance(entry, dict):
            errors.append("deployable_state.required_proof entries must be objects")
            continue
        proof_id = entry.get("id")
        description = entry.get("description")
        if not non_empty_string(proof_id) or not non_empty_string(description):
            errors.append("deployable_state.required_proof entries need non-empty id and description")
            continue
        proof_ids.append(proof_id)
    if tuple(proof_ids) != REQUIRED_DEPLOYABLE_PROOF_IDS:
        errors.append(f"deployable_state.required_proof ids must be {list(REQUIRED_DEPLOYABLE_PROOF_IDS)}")

    if not non_empty_string(broken_test_policy.get("summary")):
        errors.append("broken_test_policy.summary must be a non-empty string")

    classifications = broken_test_policy.get("classifications")
    if not isinstance(classifications, dict):
        errors.append("broken_test_policy.classifications must be an object")
        classifications = {}
    if tuple(classifications.keys()) != REQUIRED_CLASSIFICATIONS:
        errors.append(
            f"broken_test_policy.classifications must contain {list(REQUIRED_CLASSIFICATIONS)} in order"
        )
    for classification_name in REQUIRED_CLASSIFICATIONS:
        entry = classifications.get(classification_name, {})
        if not isinstance(entry, dict):
            errors.append(f"classification '{classification_name}' must be an object")
            continue
        for field in ("meaning", "default_action"):
            if not non_empty_string(entry.get(field)):
                errors.append(
                    f"classification '{classification_name}' missing non-empty field '{field}'"
                )

    allowed_actions = broken_test_policy.get("allowed_actions")
    if not isinstance(allowed_actions, dict):
        errors.append("broken_test_policy.allowed_actions must be an object")
        allowed_actions = {}
    for action in ("fix", "quarantine", "delete", "demote"):
        if not non_empty_string(allowed_actions.get(action)):
            errors.append(f"broken_test_policy.allowed_actions.{action} must be a non-empty string")

    quarantine_metadata = broken_test_policy.get("quarantine_metadata")
    if not isinstance(quarantine_metadata, dict):
        errors.append("broken_test_policy.quarantine_metadata must be an object")
        quarantine_metadata = {}
    if tuple(quarantine_metadata.get("required_keys", [])) != REQUIRED_QUARANTINE_KEYS:
        errors.append(
            f"broken_test_policy.quarantine_metadata.required_keys must be {list(REQUIRED_QUARANTINE_KEYS)}"
        )
    if tuple(quarantine_metadata.get("classification_enum", [])) != REQUIRED_CLASSIFICATIONS:
        errors.append(
            f"broken_test_policy.quarantine_metadata.classification_enum must be {list(REQUIRED_CLASSIFICATIONS)}"
        )
    if quarantine_metadata.get("action_enum") != ["quarantine"]:
        errors.append("broken_test_policy.quarantine_metadata.action_enum must equal ['quarantine']")
    if quarantine_metadata.get("max_expiry_days_from_start") != 14:
        errors.append("broken_test_policy.quarantine_metadata.max_expiry_days_from_start must be 14")
    if not non_empty_string(broken_test_policy.get("fast_lane_rule")):
        errors.append("broken_test_policy.fast_lane_rule must be a non-empty string")

    summary = {
        "contract_path": contract_path.as_posix(),
        "selected_lane": selected_lane or None,
        "lane_order": contract.get("lane_order", []),
        "lanes_present": [lane for lane in REQUIRED_LANES if lane in lanes],
        "deployable_proof_ids": proof_ids,
        "selected_lane_contract": lanes.get(selected_lane) if selected_lane else None,
        "errors": errors,
        "passes": len(errors) == 0,
    }
    emit_summary(summary, args.output)
    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
