#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
from pathlib import Path


TAG_RE = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')
ALLOWED_CLASS = {"critical", "useful", "low-signal", "flaky"}
ALLOWED_GATES = {"gate-fast", "gate-core", "gate-release", "gate-reconciliation", "gate-quality"}
TAGGED_TRUTH = {"critical", "concurrency", "reconciliation", "flaky"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Validate confidence-suite test catalog")
    p.add_argument("--catalog", default="docs/CODE-RED/confidence-suite/TEST_CATALOG.json")
    p.add_argument("--quarantine", default="scripts/test_quarantine.txt")
    p.add_argument(
        "--tests-root",
        default="erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite",
        help="Root folder for authoritative truth-suite tests",
    )
    p.add_argument("--gate", default="", help="Optional lane to validate membership constraints")
    p.add_argument("--output", default="")
    return p.parse_args()


def read_json(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def file_tags(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    return set(TAG_RE.findall(Path(path).read_text(encoding="utf-8")))


def load_quarantine(path: str) -> set[str]:
    out = set()
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.split("#", 1)[0].strip()
            if line:
                out.add(line.replace("\\", "/"))
    return out


def is_under_root(path: str, root: str) -> bool:
    absolute_path = Path(path).resolve()
    absolute_root = Path(root).resolve()
    try:
        absolute_path.relative_to(absolute_root)
        return True
    except ValueError:
        return False


def discover_tagged_truth_tests(tests_root: str) -> list[str]:
    root = Path(tests_root)
    if not root.exists():
        return []
    discovered: list[str] = []
    cwd = Path.cwd().resolve()
    for java_file in sorted(root.rglob("*.java")):
        tags = set(TAG_RE.findall(java_file.read_text(encoding="utf-8")))
        if not tags.intersection(TAGGED_TRUTH):
            continue
        try:
            rel = str(java_file.resolve().relative_to(cwd)).replace("\\", "/")
        except ValueError:
            rel = str(java_file).replace("\\", "/")
        discovered.append(rel)
    return discovered


def main() -> int:
    args = parse_args()
    if not os.path.exists(args.catalog):
        discovered = discover_tagged_truth_tests(args.tests_root)
        summary = {
            "catalog_path": args.catalog,
            "tests_root": args.tests_root,
            "tests_cataloged": 0,
            "catalog_missing": True,
            "discovered_tagged_tests": len(discovered),
            "discovered_test_paths": discovered,
            "errors": [],
            "passes": True,
        }
        print("[validate_test_catalog] WARN: catalog file missing; running in compatibility mode")
        print("[validate_test_catalog] summary:")
        print(json.dumps(summary, indent=2))
        if args.output:
            os.makedirs(os.path.dirname(args.output), exist_ok=True)
            with open(args.output, "w", encoding="utf-8") as fh:
                json.dump(summary, fh, indent=2)
        return 0

    catalog = read_json(args.catalog)
    tests = catalog.get("tests", [])
    quarantine = load_quarantine(args.quarantine)
    errors: list[str] = []

    by_path = {}
    for entry in tests:
        path = entry.get("test_path")
        if not path:
            errors.append(f"missing test_path in entry: {entry.get('id')}")
            continue
        if path in by_path:
            errors.append(f"duplicate test_path in catalog: {path}")
        by_path[path] = entry

        if not os.path.exists(path):
            errors.append(f"catalog test file does not exist: {path}")
        elif not is_under_root(path, args.tests_root):
            errors.append(f"catalog test outside authoritative tests-root: {path}")
        klass = entry.get("class")
        if klass not in ALLOWED_CLASS:
            errors.append(f"invalid class for {path}: {klass}")
        gates = set(entry.get("gate_membership", []))
        if not gates:
            errors.append(f"empty gate_membership: {path}")
        if gates - ALLOWED_GATES:
            errors.append(f"invalid gate_membership for {path}: {sorted(gates - ALLOWED_GATES)}")
        if not entry.get("owner"):
            errors.append(f"missing owner for {path}")
        for flag in ("accounting_math_critical", "cross_module_critical"):
            if not isinstance(entry.get(flag), bool):
                errors.append(f"missing/invalid boolean {flag} for {path}")

        tags = file_tags(path)
        declared_tags = set(entry.get("tags", []))
        if "critical" in declared_tags and "critical" not in tags:
            errors.append(f"catalog declares critical but file lacks @Tag(\"critical\"): {path}")
        if "concurrency" in declared_tags and "concurrency" not in tags:
            errors.append(f"catalog declares concurrency but file lacks @Tag(\"concurrency\"): {path}")
        if "reconciliation" in declared_tags and "reconciliation" not in tags:
            errors.append(f"catalog declares reconciliation but file lacks @Tag(\"reconciliation\"): {path}")
        if "flaky" in declared_tags and "flaky" not in tags:
            errors.append(f"catalog declares flaky but file lacks @Tag(\"flaky\"): {path}")

        if path in quarantine:
            forbidden = {"gate-fast", "gate-core", "gate-release", "gate-reconciliation"}
            if forbidden.intersection(gates):
                errors.append(
                    f"quarantined test cannot belong to mandatory release lanes: {path} -> {sorted(gates)}"
                )

    # Every tagged truth test must be cataloged.
    cwd = Path.cwd().resolve()
    for java_file in sorted(Path(args.tests_root).rglob("*.java")):
        try:
            rel = str(java_file.resolve().relative_to(cwd)).replace("\\", "/")
        except ValueError:
            rel = str(java_file).replace("\\", "/")
        tags = set(TAG_RE.findall(java_file.read_text(encoding="utf-8")))
        if tags.intersection(TAGGED_TRUTH) and rel not in by_path:
            errors.append(f"tagged truth test missing from catalog: {rel} tags={sorted(tags)}")

    # Optional gate-specific check: every lane test in catalog must still exist.
    if args.gate:
        for path, entry in by_path.items():
            gates = set(entry.get("gate_membership", []))
            if args.gate in gates and not os.path.exists(path):
                errors.append(f"gate member missing on disk ({args.gate}): {path}")

    summary = {
        "catalog_path": args.catalog,
        "tests_root": args.tests_root,
        "tests_cataloged": len(tests),
        "errors": errors,
        "passes": len(errors) == 0,
    }

    print("[validate_test_catalog] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())
