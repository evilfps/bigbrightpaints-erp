#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True).strip()


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Enforce changed-file JaCoCo coverage")
    p.add_argument("--jacoco", required=True, help="Path to jacoco.xml")
    p.add_argument("--diff-base", default="", help="Git base ref/sha for diff (default HEAD~1)")
    p.add_argument("--src-root", default="erp-domain/src/main/java", help="Source root to evaluate")
    p.add_argument("--threshold-line", type=float, default=0.95, help="Minimum changed-line coverage ratio")
    p.add_argument("--threshold-branch", type=float, default=0.90, help="Minimum changed-branch coverage ratio")
    p.add_argument("--output", default="", help="Optional JSON summary output")
    return p.parse_args()


def parse_changed_lines(diff_text: str) -> dict[str, set[int]]:
    changed: dict[str, set[int]] = {}
    cur_file = None
    hunk_re = re.compile(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            cur_file = line[len("+++ b/"):].strip()
            changed.setdefault(cur_file, set())
            continue
        m = hunk_re.match(line)
        if not m or cur_file is None:
            continue
        start = int(m.group(1))
        count = int(m.group(2) or "1")
        if count == 0:
            continue
        for n in range(start, start + count):
            changed[cur_file].add(n)
    return changed


def build_jacoco_line_map(jacoco_xml: str, src_root: str) -> dict[str, dict[int, tuple[int, int, int, int]]]:
    tree = ET.parse(jacoco_xml)
    root = tree.getroot()
    mapped: dict[str, dict[int, tuple[int, int, int, int]]] = {}
    for pkg in root.findall(".//package"):
        pkg_name = pkg.get("name", "")
        for sf in pkg.findall("sourcefile"):
            sf_name = sf.get("name", "")
            rel_path = os.path.join(src_root, pkg_name, sf_name).replace("\\", "/")
            lines: dict[int, tuple[int, int, int, int]] = {}
            for line in sf.findall("line"):
                nr = int(line.get("nr", "0"))
                mi = int(line.get("mi", "0"))
                ci = int(line.get("ci", "0"))
                mb = int(line.get("mb", "0"))
                cb = int(line.get("cb", "0"))
                lines[nr] = (mi, ci, mb, cb)
            mapped[rel_path] = lines
    return mapped


def main() -> int:
    args = parse_args()
    base = args.diff_base.strip() or "HEAD~1"
    diff_cmd = ["git", "diff", "--unified=0", f"{base}...HEAD", "--", args.src_root]
    diff_text = run(diff_cmd)
    changed = parse_changed_lines(diff_text)

    jacoco = build_jacoco_line_map(args.jacoco, args.src_root)

    line_cov = 0
    line_total = 0
    branch_cov = 0
    branch_total = 0
    files_considered = 0
    per_file: dict[str, dict[str, float | int]] = {}

    for file_path, changed_lines in changed.items():
        if not changed_lines:
            continue
        line_map = jacoco.get(file_path)
        if line_map is None:
            continue
        files_considered += 1
        f_line_cov = 0
        f_line_total = 0
        f_branch_cov = 0
        f_branch_total = 0
        for ln in sorted(changed_lines):
            stats = line_map.get(ln)
            if stats is None:
                continue
            mi, ci, mb, cb = stats
            if mi + ci > 0:
                f_line_total += 1
                if ci > 0:
                    f_line_cov += 1
            if mb + cb > 0:
                f_branch_total += mb + cb
                f_branch_cov += cb
        line_cov += f_line_cov
        line_total += f_line_total
        branch_cov += f_branch_cov
        branch_total += f_branch_total
        per_file[file_path] = {
            "line_covered": f_line_cov,
            "line_total": f_line_total,
            "branch_covered": f_branch_cov,
            "branch_total": f_branch_total,
            "line_ratio": (f_line_cov / f_line_total) if f_line_total else 1.0,
            "branch_ratio": (f_branch_cov / f_branch_total) if f_branch_total else 1.0,
        }

    line_ratio = (line_cov / line_total) if line_total else 1.0
    branch_ratio = (branch_cov / branch_total) if branch_total else 1.0

    summary = {
        "diff_base": base,
        "files_considered": files_considered,
        "line_covered": line_cov,
        "line_total": line_total,
        "line_ratio": line_ratio,
        "line_threshold": args.threshold_line,
        "branch_covered": branch_cov,
        "branch_total": branch_total,
        "branch_ratio": branch_ratio,
        "branch_threshold": args.threshold_branch,
        "passes": (line_ratio >= args.threshold_line and branch_ratio >= args.threshold_branch),
        "per_file": per_file,
    }

    print("[changed_files_coverage] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
