#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


LANE_TAGS = {
    "gate-fast": {"critical"},
    "gate-core": {"critical", "concurrency", "reconciliation"},
    "gate-release": {"critical", "concurrency", "reconciliation"},
    "gate-reconciliation": {"reconciliation"},
}

TAG_RE = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')
ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
START_DATE_KEY = "start_date"
CLASSIFICATION_KEY = "classification"
ACTION_KEY = "action"
REQUIRED_METADATA_KEYS = (
    "owner",
    "repro_notes",
    START_DATE_KEY,
    CLASSIFICATION_KEY,
    ACTION_KEY,
)
EXPIRY_METADATA_KEYS = ("expiry", "expires", "expiry_date")
MAX_QUARANTINE_DAYS = 14
ALLOWED_CLASSIFICATIONS = {"product-bug", "bad-test", "infra-coupled"}
ALLOWED_ACTIONS = {"quarantine"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Fail if flaky-tagged tests are lane-eligible")
    p.add_argument("--tests-root", default="erp-domain/src/test/java")
    p.add_argument("--gate", required=True, help="gate-fast|gate-core|gate-release|gate-reconciliation|gate-quality")
    p.add_argument("--quarantine", default="scripts/test_quarantine.txt")
    p.add_argument("--output", default="")
    return p.parse_args()


def parse_iso_date(value: str, field_name: str) -> tuple[date | None, str | None]:
    if not ISO_DATE_RE.fullmatch(value):
        return None, f"invalid {field_name} date '{value}' (expected YYYY-MM-DD)"
    try:
        return datetime.strptime(value, "%Y-%m-%d").date(), None
    except ValueError:
        return None, f"invalid {field_name} date '{value}' (expected YYYY-MM-DD)"


def parse_quarantine_line(raw: str, line_no: int) -> dict | None:
    stripped = raw.strip()
    if not stripped or stripped.startswith("#"):
        return None
    content = raw.split("#", 1)[0].strip()
    if not content:
        return None

    parts = [part.strip() for part in content.split("|") if part.strip()]
    if not parts:
        return None
    path = parts[0].replace("\\", "/")
    metadata: dict[str, str] = {}
    reasons: list[str] = []
    for token in parts[1:]:
        if "=" in token:
            key, value = token.split("=", 1)
            metadata[key.strip().lower()] = value.strip()
        else:
            reasons.append(f"invalid metadata token '{token}' (expected key=value)")

    for key in REQUIRED_METADATA_KEYS:
        if not metadata.get(key):
            reasons.append(f"missing required metadata '{key}'")

    expiry_key = next((k for k in EXPIRY_METADATA_KEYS if k in metadata), None)
    expiry_raw = metadata.get(expiry_key, "").strip() if expiry_key else ""
    if not expiry_key or not expiry_raw:
        reasons.append(
            "missing required metadata 'expiry' (accepted keys: expiry|expires|expiry_date)"
        )

    classification = metadata.get(CLASSIFICATION_KEY, "").strip()
    if classification and classification not in ALLOWED_CLASSIFICATIONS:
        reasons.append(
            f"invalid {CLASSIFICATION_KEY} '{classification}' (expected one of {sorted(ALLOWED_CLASSIFICATIONS)})"
        )

    action = metadata.get(ACTION_KEY, "").strip()
    if action and action not in ALLOWED_ACTIONS:
        reasons.append(
            f"invalid {ACTION_KEY} '{action}' (expected one of {sorted(ALLOWED_ACTIONS)})"
        )

    start_on = None
    expires_on = None
    start_raw = metadata.get(START_DATE_KEY, "").strip()
    if start_raw:
        start_on, date_error = parse_iso_date(start_raw, START_DATE_KEY)
        if date_error:
            reasons.append(date_error)
    if expiry_raw:
        expires_on, date_error = parse_iso_date(expiry_raw, expiry_key or "expiry")
        if date_error:
            reasons.append(date_error)

    if start_on and expires_on:
        latest_allowed = start_on + timedelta(days=MAX_QUARANTINE_DAYS)
        if expires_on < start_on:
            reasons.append(
                f"expiry {expires_on.isoformat()} cannot be before {START_DATE_KEY} {start_on.isoformat()}"
            )
        elif expires_on > latest_allowed:
            reasons.append(
                (
                    f"expiry {expires_on.isoformat()} exceeds max quarantine window "
                    f"({MAX_QUARANTINE_DAYS} days from {START_DATE_KEY} {start_on.isoformat()}; "
                    f"latest allowed {latest_allowed.isoformat()})"
                )
            )

    invalid_reason = "; ".join(reasons) if reasons else None

    return {
        "path": path,
        "line": line_no,
        "metadata": metadata,
        "start_on": start_on,
        "expires_on": expires_on,
        "invalid_reason": invalid_reason,
        "raw": raw.rstrip("\n"),
    }


def load_quarantine_entries(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    entries: list[dict] = []
    with open(path, "r", encoding="utf-8") as fh:
        for idx, raw in enumerate(fh, start=1):
            entry = parse_quarantine_line(raw, idx)
            if entry:
                entries.append(entry)
    return entries


def file_tags(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(TAG_RE.findall(text))


def main() -> int:
    args = parse_args()
    tests_root = Path(args.tests_root)
    quarantine_entries = load_quarantine_entries(args.quarantine)
    today = datetime.now(timezone.utc).date()
    invalid_quarantine_entries = []
    expired_quarantine_entries = []
    quarantine_paths = set()
    for entry in quarantine_entries:
        path = entry.get("path")
        reason = entry.get("invalid_reason")
        start_on = entry.get("start_on")
        expires_on = entry.get("expires_on")
        metadata = entry.get("metadata", {})
        record = {
            "test_path": path,
            "line": entry.get("line"),
            "start_on": start_on.isoformat() if start_on else None,
            "expires_on": expires_on.isoformat() if expires_on else None,
            "metadata": metadata,
            "raw": entry.get("raw"),
            "reason": reason,
        }
        if reason:
            invalid_quarantine_entries.append(record)
            if path:
                quarantine_paths.add(path)
            continue
        if expires_on and expires_on < today:
            record["reason"] = "expired"
            expired_quarantine_entries.append(record)
            continue
        quarantine_paths.add(path)
    lane_tags = LANE_TAGS.get(args.gate, set())

    violations = []
    quarantined_lane_members = []
    for file_path in sorted(tests_root.rglob("*.java")):
        rel = str(file_path).replace("\\", "/")
        tags = file_tags(file_path)
        lane_member = bool(lane_tags.intersection(tags))
        if lane_member and "flaky" in tags:
            violations.append({"test_path": rel, "tags": sorted(tags)})
        if lane_member and rel in quarantine_paths:
            quarantined_lane_members.append({"test_path": rel, "tags": sorted(tags)})

    summary = {
        "gate": args.gate,
        "lane_tags": sorted(lane_tags),
        "quarantine_contract": {
            "required_metadata_keys": [*REQUIRED_METADATA_KEYS, "expiry"],
            "accepted_expiry_keys": list(EXPIRY_METADATA_KEYS),
            "start_key": START_DATE_KEY,
            "classification_key": CLASSIFICATION_KEY,
            "classification_enum": sorted(ALLOWED_CLASSIFICATIONS),
            "action_key": ACTION_KEY,
            "action_enum": sorted(ALLOWED_ACTIONS),
            "date_format": "YYYY-MM-DD",
            "max_expiry_days_from_start": MAX_QUARANTINE_DAYS,
        },
        "flaky_tag_violations": violations,
        "quarantine_lane_violations": quarantined_lane_members,
        "quarantine_entries_total": len(quarantine_entries),
        "quarantine_contract_violations": invalid_quarantine_entries,
        "quarantine_invalid_entries": invalid_quarantine_entries,
        "quarantine_expired_entries": expired_quarantine_entries,
        "passes": (
            len(violations) == 0
            and len(quarantined_lane_members) == 0
            and len(invalid_quarantine_entries) == 0
            and len(expired_quarantine_entries) == 0
        ),
    }

    print("[check_flaky_tags] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
