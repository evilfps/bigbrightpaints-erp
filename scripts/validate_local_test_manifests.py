#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
import re


TAG_RE = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate local testing ladder manifests")
    parser.add_argument(
        "--tests-root",
        default="erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite",
        help="Authoritative truthsuite root",
    )
    parser.add_argument(
        "--dev-smoke-manifest",
        default="testing/local/manifests/dev-smoke.txt",
        help="Curated fast local manifest",
    )
    parser.add_argument(
        "--local-guard-manifest",
        default="testing/local/manifests/local-guard.txt",
        help="Pre-push manifest that must mirror all critical non-flaky truthsuite tests",
    )
    parser.add_argument("--output", default="")
    return parser.parse_args()


def normalize_manifest_entry(raw: str) -> str:
    return raw.replace("\\", "/").removeprefix("./")


def read_tags(path: Path) -> set[str]:
    return set(TAG_RE.findall(path.read_text(encoding="utf-8")))


def read_manifest(path: Path, tests_root: Path) -> tuple[list[str], list[str]]:
    entries: list[str] = []
    errors: list[str] = []
    seen: dict[str, int] = {}

    if not path.exists():
        return entries, [f"manifest missing: {path.as_posix()}"]

    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        content = raw.split("#", 1)[0].strip()
        if not content:
            continue
        entry = normalize_manifest_entry(content)
        if not entry.endswith(".java"):
            errors.append(
                f"{path.as_posix()}:{line_no} must reference a .java truthsuite file: {entry}"
            )
            continue
        if entry in seen:
            errors.append(
                f"duplicate manifest entry in {path.as_posix()}: {entry} (lines {seen[entry]} and {line_no})"
            )
            continue
        seen[entry] = line_no
        candidate = (tests_root / entry).resolve()
        if not candidate.exists():
            errors.append(f"manifest entry missing on disk: {entry}")
            continue
        try:
            candidate.relative_to(tests_root.resolve())
        except ValueError:
            errors.append(f"manifest entry escapes tests root: {entry}")
            continue
        entries.append(entry)

    if not entries:
        errors.append(f"manifest is empty: {path.as_posix()}")
    return entries, errors


def discover_critical_non_flaky_tests(tests_root: Path) -> list[str]:
    discovered: list[str] = []
    for java_file in sorted(tests_root.rglob("*.java")):
        tags = read_tags(java_file)
        if "critical" not in tags or "flaky" in tags:
            continue
        rel = java_file.relative_to(tests_root).as_posix()
        discovered.append(rel)
    return discovered


def dump_summary(summary: dict, output: str) -> None:
    print("[validate_local_test_manifests] summary:")
    print(json.dumps(summary, indent=2, sort_keys=True))
    if output:
        out_path = Path(output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    tests_root = Path(args.tests_root)
    dev_smoke_manifest = Path(args.dev_smoke_manifest)
    local_guard_manifest = Path(args.local_guard_manifest)

    errors: list[str] = []
    dev_smoke_entries, dev_smoke_errors = read_manifest(dev_smoke_manifest, tests_root)
    local_guard_entries, local_guard_errors = read_manifest(local_guard_manifest, tests_root)
    errors.extend(dev_smoke_errors)
    errors.extend(local_guard_errors)

    expected_local_guard = discover_critical_non_flaky_tests(tests_root)
    expected_local_guard_set = set(expected_local_guard)
    local_guard_set = set(local_guard_entries)
    dev_smoke_set = set(dev_smoke_entries)

    missing_from_local_guard = sorted(expected_local_guard_set - local_guard_set)
    extra_in_local_guard = sorted(local_guard_set - expected_local_guard_set)
    if missing_from_local_guard:
        errors.append(
            f"local-guard manifest is missing {len(missing_from_local_guard)} critical non-flaky truthsuite tests"
        )
    if extra_in_local_guard:
        errors.append(
            f"local-guard manifest contains {len(extra_in_local_guard)} entries outside the critical non-flaky truthsuite set"
        )

    dev_smoke_not_in_local_guard = sorted(dev_smoke_set - local_guard_set)
    if dev_smoke_not_in_local_guard:
        errors.append(
            f"dev-smoke manifest contains {len(dev_smoke_not_in_local_guard)} tests that are not in local-guard"
        )

    summary = {
        "tests_root": tests_root.as_posix(),
        "dev_smoke_manifest": dev_smoke_manifest.as_posix(),
        "dev_smoke_count": len(dev_smoke_entries),
        "local_guard_manifest": local_guard_manifest.as_posix(),
        "local_guard_count": len(local_guard_entries),
        "expected_local_guard_count": len(expected_local_guard),
        "dev_smoke_not_in_local_guard": dev_smoke_not_in_local_guard,
        "local_guard_missing": missing_from_local_guard,
        "local_guard_extra": extra_in_local_guard,
        "errors": errors,
        "passes": len(errors) == 0,
    }
    dump_summary(summary, args.output)
    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
