#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


LANE_TAGS = {
    "gate-fast": {"critical"},
    "gate-core": {"critical", "concurrency", "reconciliation"},
    "gate-release": {"critical", "concurrency", "reconciliation"},
    "gate-reconciliation": {"reconciliation"},
}

TAG_RE = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Fail if flaky-tagged tests are lane-eligible")
    p.add_argument("--tests-root", default="erp-domain/src/test/java")
    p.add_argument("--gate", required=True, help="gate-fast|gate-core|gate-release|gate-reconciliation|gate-quality")
    p.add_argument("--quarantine", default="scripts/test_quarantine.txt")
    p.add_argument("--output", default="")
    return p.parse_args()


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
    for token in parts[1:]:
        if "=" in token:
            key, value = token.split("=", 1)
            metadata[key.strip().lower()] = value.strip()
        else:
            metadata[token.strip().lower()] = ""

    expiry_key = next((k for k in ("expiry", "expires", "expiry_date") if k in metadata), None)
    expiry_raw = metadata.get(expiry_key, "") if expiry_key else ""
    invalid_reason = None
    expires_on = None
    if expiry_raw:
        try:
            expires_on = datetime.fromisoformat(expiry_raw).date()
        except ValueError:
            invalid_reason = f"invalid expiry date '{expiry_raw}'"
    else:
        invalid_reason = "missing expiry metadata (expiry=YYYY-MM-DD)"

    return {
        "path": path,
        "line": line_no,
        "metadata": metadata,
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
        expires_on = entry.get("expires_on")
        metadata = entry.get("metadata", {})
        record = {
            "test_path": path,
            "line": entry.get("line"),
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
        "flaky_tag_violations": violations,
        "quarantine_lane_violations": quarantined_lane_members,
        "quarantine_entries_total": len(quarantine_entries),
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
