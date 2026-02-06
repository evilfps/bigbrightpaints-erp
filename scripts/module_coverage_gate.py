#!/usr/bin/env python3
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Enforce module/package coverage from JaCoCo XML")
    p.add_argument("--jacoco", required=True, help="Path to jacoco.xml")
    p.add_argument("--packages", required=True, help="Comma-separated package prefixes (dot notation)")
    p.add_argument("--line-threshold", type=float, default=0.92)
    p.add_argument("--branch-threshold", type=float, default=0.85)
    p.add_argument("--output", default="")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    package_prefixes = [p.strip().replace(".", "/") for p in args.packages.split(",") if p.strip()]
    tree = ET.parse(args.jacoco)
    root = tree.getroot()

    covered_line = 0
    missed_line = 0
    covered_branch = 0
    missed_branch = 0
    matched_packages: list[str] = []

    for pkg in root.findall(".//package"):
        name = pkg.get("name", "")
        if not any(name.startswith(prefix) for prefix in package_prefixes):
            continue
        matched_packages.append(name.replace("/", "."))
        for counter in pkg.findall("counter"):
            ctype = counter.get("type")
            covered = int(counter.get("covered", "0"))
            missed = int(counter.get("missed", "0"))
            if ctype == "LINE":
                covered_line += covered
                missed_line += missed
            elif ctype == "BRANCH":
                covered_branch += covered
                missed_branch += missed

    line_total = covered_line + missed_line
    branch_total = covered_branch + missed_branch
    line_ratio = (covered_line / line_total) if line_total else 1.0
    branch_ratio = (covered_branch / branch_total) if branch_total else 1.0
    passes = line_ratio >= args.line_threshold and branch_ratio >= args.branch_threshold

    summary = {
        "matched_packages": sorted(set(matched_packages)),
        "line_covered": covered_line,
        "line_total": line_total,
        "line_ratio": line_ratio,
        "line_threshold": args.line_threshold,
        "branch_covered": covered_branch,
        "branch_total": branch_total,
        "branch_ratio": branch_ratio,
        "branch_threshold": args.branch_threshold,
        "passes": passes,
    }

    print("[module_coverage_gate] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)

    return 0 if passes else 1


if __name__ == "__main__":
    sys.exit(main())
