#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import sys
from typing import Optional
import xml.etree.ElementTree as ET


KILLED_STATUSES = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}
SURVIVED_STATUSES = {"SURVIVED"}
EXCLUDED_STATUSES = {"NO_COVERAGE", "NON_VIABLE"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Summarize PIT mutation reports")
    p.add_argument("--pit-reports", default="erp-domain/target/pit-reports")
    p.add_argument("--threshold", type=float, default=80.0)
    p.add_argument("--min-scored-total", type=int, default=50,
                   help="Minimum actionable mutations (total minus excluded) required")
    p.add_argument("--max-excluded-ratio", type=float, default=0.80,
                   help="Maximum allowed excluded mutation ratio before failing quality signal")
    p.add_argument("--output", required=True)
    return p.parse_args()


def latest_mutations_xml(report_root: pathlib.Path) -> Optional[pathlib.Path]:
    if not report_root.exists():
        return None
    direct = report_root / "mutations.xml"
    if direct.exists():
        return direct
    dirs = [d for d in report_root.iterdir() if d.is_dir()]
    if not dirs:
        return None
    latest = sorted(dirs, key=lambda p: p.name)[-1]
    xml_path = latest / "mutations.xml"
    return xml_path if xml_path.exists() else None


def main() -> int:
    args = parse_args()
    report_root = pathlib.Path(args.pit_reports)
    mutations_file = latest_mutations_xml(report_root)
    if mutations_file is None:
        summary = {
            "pit_report": str(report_root),
            "error": "mutations.xml not found",
            "total_mutations": 0,
            "killed_or_detected": 0,
            "survived": 0,
            "excluded": 0,
            "scored_total": 0,
            "mutation_score": 0.0,
            "threshold": args.threshold,
            "min_scored_total": args.min_scored_total,
            "max_excluded_ratio": args.max_excluded_ratio,
            "passes": False,
        }
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)
        print(f"[pit_mutation_summary] mutations.xml not found under {report_root}", file=sys.stderr)
        print(json.dumps(summary, indent=2))
        return 2

    root = ET.parse(mutations_file).getroot()
    total = 0
    killed = 0
    survived = 0
    excluded = 0

    for mutation in root.findall("mutation"):
        total += 1
        status = (mutation.get("status") or "").upper()
        if status in KILLED_STATUSES:
            killed += 1
        elif status in SURVIVED_STATUSES:
            survived += 1
        elif status in EXCLUDED_STATUSES:
            excluded += 1

    scored_total = total - excluded
    score = 100.0 if scored_total <= 0 else (killed / scored_total) * 100.0
    excluded_ratio = 0.0 if total <= 0 else (excluded / total)
    passes = (
            score >= args.threshold
            and scored_total >= args.min_scored_total
            and excluded_ratio <= args.max_excluded_ratio
    )

    summary = {
        "pit_report": str(mutations_file),
        "total_mutations": total,
        "killed_or_detected": killed,
        "survived": survived,
        "excluded": excluded,
        "excluded_ratio": round(excluded_ratio, 5),
        "scored_total": scored_total,
        "mutation_score": round(score, 3),
        "threshold": args.threshold,
        "min_scored_total": args.min_scored_total,
        "max_excluded_ratio": args.max_excluded_ratio,
        "passes": passes,
    }

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    print("[pit_mutation_summary] summary:")
    print(json.dumps(summary, indent=2))

    return 0 if passes else 1


if __name__ == "__main__":
    sys.exit(main())
