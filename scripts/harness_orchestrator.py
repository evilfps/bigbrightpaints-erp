#!/usr/bin/env python3
"""Resolve lane and required checks for orchestrator slice validation."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys

STRICT_RUNTIME_PREFIXES = (
    "erp-domain/src/main/resources/db/migration_v2/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/",
)

DOCS_ONLY_PREFIXES = ("docs/", "agents/", "skills/", "ci/", "tickets/")
DOCS_ONLY_EXACT = {"asyncloop", "scripts/harness_orchestrator.py"}

STRICT_DOC_PREFIXES = ("docs/agents/", "ci/")
STRICT_DOC_EXACT = {
    "docs/ASYNC_LOOP_OPERATIONS.md",
    "docs/system-map/REVIEW_QUEUE_POLICY.md",
    "agents/orchestrator-layer.yaml",
    "asyncloop",
    "scripts/harness_orchestrator.py",
}

FAST_LANE_CHECKS = ["bash ci/lint-knowledgebase.sh"]
STRICT_LANE_CHECKS = [
    "bash ci/lint-knowledgebase.sh",
    "bash ci/check-architecture.sh",
    "bash ci/check-enterprise-policy.sh",
]

IGNORED_PREFIXES = (".harness/", ".codex/")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Classify orchestrator lane and print required review/check policy."
    )
    parser.add_argument(
        "--changed-file",
        action="append",
        default=[],
        help="Explicit changed path (repeatable). If omitted, read from git.",
    )
    parser.add_argument(
        "--diff-base",
        default="",
        help="Optional diff base for range changes (used with --changed-file omitted).",
    )
    parser.add_argument(
        "--checks-only",
        action="store_true",
        help="Print required checks only (one command per line).",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit policy as JSON.",
    )
    return parser.parse_args()


def run_git(args: list[str]) -> list[str]:
    try:
        output = subprocess.check_output(["git", *args], text=True).strip()
    except subprocess.CalledProcessError:
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]


def gather_changed_files(diff_base: str) -> list[str]:
    changed = set()
    if diff_base:
        changed.update(run_git(["diff", "--name-only", f"{diff_base}...HEAD"]))
    changed.update(run_git(["diff", "--name-only"]))
    changed.update(run_git(["ls-files", "--others", "--exclude-standard"]))
    return [path for path in sorted(changed) if not matches_prefix(path, IGNORED_PREFIXES)]


def matches_prefix(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def matches_prefix_or_exact(path: str, prefixes: tuple[str, ...], exact: set[str]) -> bool:
    return path in exact or matches_prefix(path, prefixes)


def is_docs_only(paths: list[str]) -> bool:
    if not paths:
        return True
    return all(matches_prefix_or_exact(path, DOCS_ONLY_PREFIXES, DOCS_ONLY_EXACT) for path in paths)


def classify(paths: list[str]) -> dict[str, object]:
    if any(matches_prefix(path, STRICT_RUNTIME_PREFIXES) for path in paths):
        return {
            "lane": "strict_lane",
            "classification": "runtime_or_schema_strict",
            "docs_only_review_skip": False,
            "required_checks": STRICT_LANE_CHECKS,
            "required_review": "commit_review_and_review_agent_required",
        }

    if is_docs_only(paths):
        if any(matches_prefix_or_exact(path, STRICT_DOC_PREFIXES, STRICT_DOC_EXACT) for path in paths):
            return {
                "lane": "strict_lane",
                "classification": "docs_only_control_plane_strict",
                "docs_only_review_skip": True,
                "required_checks": STRICT_LANE_CHECKS,
                "required_review": "docs_only_review_skip_allowed",
            }
        return {
            "lane": "fast_lane",
            "classification": "docs_only_fast",
            "docs_only_review_skip": True,
            "required_checks": FAST_LANE_CHECKS,
            "required_review": "docs_only_review_skip_allowed",
        }

    return {
        "lane": "fast_lane",
        "classification": "mixed_non_strict",
        "docs_only_review_skip": False,
        "required_checks": STRICT_LANE_CHECKS,
        "required_review": "commit_review_and_review_agent_required",
    }


def build_policy(paths: list[str]) -> dict[str, object]:
    resolved = classify(paths)
    resolved["changed_files"] = paths
    resolved["review_only_agents_commit_code"] = False
    resolved["runbook_alignment_targets"] = [
        "docs/agents/WORKFLOW.md",
        "docs/ASYNC_LOOP_OPERATIONS.md",
        "docs/system-map/REVIEW_QUEUE_POLICY.md",
        "asyncloop",
    ]
    return resolved


def main() -> int:
    args = parse_args()
    if args.changed_file:
        changed_files = [
            path
            for path in sorted(set(args.changed_file))
            if not matches_prefix(path, IGNORED_PREFIXES)
        ]
    else:
        changed_files = gather_changed_files(args.diff_base)
    policy = build_policy(changed_files)

    required_checks = policy["required_checks"]
    if args.checks_only:
        for cmd in required_checks:
            print(cmd)
        return 0

    if args.json:
        print(json.dumps(policy, indent=2))
        return 0

    print(f"lane: {policy['lane']}")
    print(f"classification: {policy['classification']}")
    print(f"docs_only_review_skip: {policy['docs_only_review_skip']}")
    print(f"required_review: {policy['required_review']}")
    print("review_only_agents_commit_code: false")
    print("required_checks:")
    for cmd in required_checks:
        print(f"- {cmd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
