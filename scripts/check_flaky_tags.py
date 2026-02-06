#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
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


def load_quarantine(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    values: set[str] = set()
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.split("#", 1)[0].strip()
            if line:
                values.add(line.replace("\\", "/"))
    return values


def file_tags(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(TAG_RE.findall(text))


def main() -> int:
    args = parse_args()
    tests_root = Path(args.tests_root)
    quarantine = load_quarantine(args.quarantine)
    lane_tags = LANE_TAGS.get(args.gate, set())

    violations = []
    quarantined_lane_members = []
    for file_path in sorted(tests_root.rglob("*.java")):
        rel = str(file_path).replace("\\", "/")
        tags = file_tags(file_path)
        lane_member = bool(lane_tags.intersection(tags))
        if lane_member and "flaky" in tags:
            violations.append({"test_path": rel, "tags": sorted(tags)})
        if lane_member and rel in quarantine:
            quarantined_lane_members.append({"test_path": rel, "tags": sorted(tags)})

    summary = {
        "gate": args.gate,
        "lane_tags": sorted(lane_tags),
        "flaky_tag_violations": violations,
        "quarantine_lane_violations": quarantined_lane_members,
        "passes": (len(violations) == 0 and len(quarantined_lane_members) == 0),
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
