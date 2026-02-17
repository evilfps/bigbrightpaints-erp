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


def git_ref_exists(ref: str) -> bool:
    try:
        run(["git", "rev-parse", "--verify", "--quiet", ref])
        return True
    except subprocess.CalledProcessError:
        return False


def resolve_diff_base(explicit_base: str) -> str:
    base = explicit_base.strip()
    if base:
        return base

    for ref in ("origin/main", "main", "origin/master"):
        if git_ref_exists(ref):
            return run(["git", "merge-base", ref, "HEAD"])

    return "HEAD~1"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Enforce changed-file JaCoCo coverage")
    p.add_argument("--jacoco", required=True, help="Path to jacoco.xml")
    p.add_argument(
        "--diff-base",
        default="",
        help="Git base ref/sha for diff (default: merge-base origin/main HEAD, then main/master, else HEAD~1)",
    )
    p.add_argument("--src-root", default="erp-domain/src/main/java", help="Source root to evaluate")
    p.add_argument("--threshold-line", type=float, default=0.95, help="Minimum changed-line coverage ratio")
    p.add_argument("--threshold-branch", type=float, default=0.90, help="Minimum changed-branch coverage ratio")
    p.add_argument(
        "--fail-on-vacuous",
        action="store_true",
        help="Fail when changed-file coverage is vacuous (no files considered or no executable changed lines)",
    )
    p.add_argument("--output", default="", help="Optional JSON summary output")
    return p.parse_args()


INTERFACE_DECL_RE = re.compile(r"^\s*(?:public\s+)?(?:sealed\s+|non-sealed\s+)?(?:abstract\s+)?interface\s+\w+")


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


def load_source_info(path: str, cache: dict[str, tuple[list[str], bool]]) -> tuple[list[str], bool]:
    info = cache.get(path)
    if info is not None:
        return info
    lines: list[str] = []
    is_interface = False
    try:
        with open(path, "r", encoding="utf-8") as fh:
            lines = fh.readlines()
    except FileNotFoundError:
        cache[path] = (lines, is_interface)
        return lines, is_interface

    for line in lines:
        if INTERFACE_DECL_RE.match(line):
            is_interface = True
            break
    cache[path] = (lines, is_interface)
    return lines, is_interface


def is_structural_source_line(text: str, is_interface_file: bool) -> bool:
    stripped = text.strip()
    if not stripped:
        return True
    if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or stripped == "*/":
        return True
    if stripped.startswith("package ") or stripped.startswith("import "):
        return True
    if stripped.startswith("@"):
        return True
    if stripped in {"{", "}", ";", "};"}:
        return True
    if is_interface_file and ";" in stripped and "{" not in stripped:
        return True
    return False


def main() -> int:
    args = parse_args()
    base = resolve_diff_base(args.diff_base)
    try:
        run(["git", "rev-parse", "--verify", f"{base}^{{commit}}"])
    except subprocess.CalledProcessError:
        print(f"[changed_files_coverage] invalid --diff-base commit: {base}", file=sys.stderr)
        return 2

    diff_cmd = ["git", "diff", "--unified=0", f"{base}...HEAD", "--", args.src_root]
    try:
        diff_text = run(diff_cmd)
    except subprocess.CalledProcessError as exc:
        print(f"[changed_files_coverage] failed to diff against base={base}: {exc}", file=sys.stderr)
        return 2
    changed = parse_changed_lines(diff_text)

    jacoco = build_jacoco_line_map(args.jacoco, args.src_root)

    line_cov = 0
    line_total = 0
    branch_cov = 0
    branch_total = 0
    files_considered = 0
    per_file: dict[str, dict[str, float | int]] = {}
    structural_files: list[str] = []
    files_with_unmapped_lines: list[str] = []
    skipped_files: list[str] = []

    source_cache: dict[str, tuple[list[str], bool]] = {}

    for file_path, changed_lines in changed.items():
        if not changed_lines:
            continue
        line_map = jacoco.get(file_path)
        if line_map is None:
            skipped_files.append(file_path)
            continue
        lines, is_interface_file = load_source_info(file_path, source_cache)
        files_considered += 1
        f_line_cov = 0
        f_line_total = 0
        f_branch_cov = 0
        f_branch_total = 0
        f_structural_lines = 0
        f_unmapped_lines = 0
        for ln in sorted(changed_lines):
            stats = line_map.get(ln)
            if stats is None:
                if 1 <= ln <= len(lines) and is_structural_source_line(lines[ln - 1], is_interface_file):
                    f_structural_lines += 1
                else:
                    f_unmapped_lines += 1
                continue
            mi, ci, mb, cb = stats
            if mi + ci > 0:
                f_line_total += 1
                if ci > 0:
                    f_line_cov += 1
            if mb + cb > 0:
                f_branch_total += mb + cb
                f_branch_cov += cb
            if (mi + ci) == 0 and (mb + cb) == 0:
                f_structural_lines += 1
        line_cov += f_line_cov
        line_total += f_line_total
        branch_cov += f_branch_cov
        branch_total += f_branch_total
        if f_line_total == 0 and f_branch_total == 0 and f_structural_lines > 0 and f_unmapped_lines == 0:
            structural_files.append(file_path)
        if f_unmapped_lines > 0:
            files_with_unmapped_lines.append(file_path)
        per_file[file_path] = {
            "changed_lines": len(changed_lines),
            "line_covered": f_line_cov,
            "line_total": f_line_total,
            "branch_covered": f_branch_cov,
            "branch_total": f_branch_total,
            "line_ratio": (f_line_cov / f_line_total) if f_line_total else 1.0,
            "branch_ratio": (f_branch_cov / f_branch_total) if f_branch_total else 1.0,
            "structural_lines": f_structural_lines,
            "unmapped_lines": f_unmapped_lines,
        }

    line_ratio = (line_cov / line_total) if line_total else 1.0
    branch_ratio = (branch_cov / branch_total) if branch_total else 1.0
    structural_only = (
        files_considered > 0
        and line_total == 0
        and branch_total == 0
        and len(structural_files) == files_considered
        and len(structural_files) > 0
        and not files_with_unmapped_lines
        and not skipped_files
    )
    vacuous = files_considered == 0 or (line_total == 0 and not structural_only)
    vacuous_reason = ""
    if files_considered == 0:
        vacuous_reason = "no_files_considered"
    elif line_total == 0 and not structural_only:
        vacuous_reason = "no_instrumented_lines"

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
        "vacuous": vacuous,
        "vacuous_reason": vacuous_reason,
        "structural_only": structural_only,
        "structural_files": sorted(structural_files),
        "coverage_skipped_files": sorted(skipped_files),
        "files_with_unmapped_lines": sorted(files_with_unmapped_lines),
        "passes": (line_ratio >= args.threshold_line
                   and branch_ratio >= args.threshold_branch
                   and not (args.fail_on_vacuous and vacuous)),
        "per_file": per_file,
    }

    print("[changed_files_coverage] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)

    if args.fail_on_vacuous and vacuous:
        print(
            "[changed_files_coverage] FAIL: vacuous changed-files coverage "
            f"(files_considered={files_considered}, line_total={line_total}, reason={vacuous_reason})",
            file=sys.stderr,
        )
        return 1

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
