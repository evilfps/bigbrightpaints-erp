#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Summarize Surefire XML reports")
    p.add_argument("--reports-dir", default="erp-domain/target/surefire-reports")
    p.add_argument("--summary-out", required=True)
    p.add_argument("--mismatch-out", required=True)
    p.add_argument("--gate", default="")
    return p.parse_args()


def testcase_id(node: ET.Element) -> str:
    return f"{node.get('classname', '')}#{node.get('name', '')}"


def main() -> int:
    args = parse_args()
    report_dir = pathlib.Path(args.reports_dir)
    files = sorted(report_dir.glob("TEST-*.xml"))
    if not files:
        print(f"[surefire_report_summary] no surefire XML found in {report_dir}", file=sys.stderr)
        return 2

    total_tests = 0
    total_failures = 0
    total_errors = 0
    total_skipped = 0
    total_time = 0.0
    failing_cases: list[dict[str, str]] = []

    for file_path in files:
        root = ET.parse(file_path).getroot()
        total_tests += int(root.get("tests", "0"))
        total_failures += int(root.get("failures", "0"))
        total_errors += int(root.get("errors", "0"))
        total_skipped += int(root.get("skipped", "0"))
        total_time += float(root.get("time", "0") or "0")

        for case in root.findall("testcase"):
            failure = case.find("failure")
            error = case.find("error")
            if failure is None and error is None:
                continue
            node = failure if failure is not None else error
            message = (node.get("message") or "").strip()
            text = (node.text or "").strip().splitlines()
            snippet = text[0].strip() if text else ""
            failing_cases.append(
                {
                    "test": testcase_id(case),
                    "type": node.tag,
                    "message": message or snippet,
                }
            )

    summary = {
        "gate": args.gate,
        "reports_dir": str(report_dir),
        "report_files": len(files),
        "tests": total_tests,
        "failures": total_failures,
        "errors": total_errors,
        "skipped": total_skipped,
        "time_seconds": round(total_time, 3),
        "failing_cases": failing_cases,
        "passes": (total_failures == 0 and total_errors == 0),
    }

    os.makedirs(os.path.dirname(args.summary_out), exist_ok=True)
    with open(args.summary_out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    os.makedirs(os.path.dirname(args.mismatch_out), exist_ok=True)
    with open(args.mismatch_out, "w", encoding="utf-8") as fh:
        if failing_cases:
            for row in failing_cases:
                fh.write(f"{row['type']} {row['test']} {row['message']}\n")
        else:
            fh.write("")

    print("[surefire_report_summary] summary:")
    print(json.dumps(summary, indent=2))

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
