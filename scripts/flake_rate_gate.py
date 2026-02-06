#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Compute rolling flake rate from repeated surefire runs")
    p.add_argument("--runs-dir", required=True, help="Root folder containing run*/surefire XML directories")
    p.add_argument("--threshold", type=float, default=0.01)
    p.add_argument("--window", type=int, default=20)
    p.add_argument("--allow-short-window", action="store_true")
    p.add_argument("--output", required=True)
    return p.parse_args()


def natural_run_key(path: pathlib.Path) -> tuple[int, str]:
    m = re.search(r"(\d+)", path.name)
    return (int(m.group(1)) if m else 0, path.name)


def collect_run_status(run_dir: pathlib.Path) -> dict[str, bool]:
    statuses: dict[str, bool] = {}
    files = sorted(run_dir.rglob("TEST-*.xml"))
    for file_path in files:
        root = ET.parse(file_path).getroot()
        for case in root.findall("testcase"):
            test_id = f"{case.get('classname', '')}#{case.get('name', '')}"
            failed = case.find("failure") is not None or case.find("error") is not None
            existing = statuses.get(test_id)
            if existing is None:
                statuses[test_id] = not failed
            else:
                statuses[test_id] = existing and (not failed)
    return statuses


def main() -> int:
    args = parse_args()
    runs_root = pathlib.Path(args.runs_dir)
    if not runs_root.exists():
        print(f"[flake_rate_gate] runs dir not found: {runs_root}", file=sys.stderr)
        return 2

    run_dirs = sorted([p for p in runs_root.iterdir() if p.is_dir()], key=natural_run_key)
    if not run_dirs:
        print(f"[flake_rate_gate] no run folders found in {runs_root}", file=sys.stderr)
        return 2

    if len(run_dirs) > args.window:
        run_dirs = run_dirs[-args.window:]

    test_history: dict[str, list[bool]] = {}
    for run_dir in run_dirs:
        run_status = collect_run_status(run_dir)
        for test_id, passed in run_status.items():
            test_history.setdefault(test_id, []).append(passed)

    flaky_tests = []
    always_fail = []
    always_pass = []

    for test_id, history in sorted(test_history.items()):
        has_pass = any(history)
        has_fail = any(not v for v in history)
        if has_pass and has_fail:
            flaky_tests.append(test_id)
        elif has_fail and not has_pass:
            always_fail.append(test_id)
        else:
            always_pass.append(test_id)

    total_tests = len(test_history)
    flake_rate = 0.0 if total_tests == 0 else len(flaky_tests) / total_tests
    has_window = len(run_dirs) >= args.window

    passes = (
        flake_rate < args.threshold
        and len(always_fail) == 0
        and (has_window or args.allow_short_window)
    )

    summary = {
        "runs_root": str(runs_root),
        "runs_evaluated": len(run_dirs),
        "window": args.window,
        "has_full_window": has_window,
        "total_tests": total_tests,
        "flaky_tests": flaky_tests,
        "always_fail_tests": always_fail,
        "always_pass_tests": len(always_pass),
        "flake_rate": round(flake_rate, 6),
        "threshold": args.threshold,
        "allow_short_window": args.allow_short_window,
        "passes": passes,
    }

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    print("[flake_rate_gate] summary:")
    print(json.dumps(summary, indent=2))

    return 0 if passes else 1


if __name__ == "__main__":
    sys.exit(main())
