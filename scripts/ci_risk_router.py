#!/usr/bin/env python3
import argparse
import os
import subprocess
import sys
from pathlib import Path


JAVA_SOURCE_ROOT = "erp-domain/src/main/java/"
DEFAULT_CHANGED_COVERAGE_BASELINE_SHA = "9d467c0543d1e728fab4b4ab3049a92399f5db69"
LOCAL_SEED_RUNTIME_EXCLUSIONS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/MockDataInitializer.java",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/SeedCompanyAdminSupport.java",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/ValidationSeedDataInitializer.java",
)

CI_INFRA_PATTERNS = (
    ".github/workflows/ci.yml",
    ".factory/services.yaml",
    "erp-domain/pom.xml",
    "scripts/ci_risk_router.py",
    "scripts/changed_files_coverage.py",
    "scripts/manifest_to_dtest.py",
    "scripts/pr_ci_parity.py",
    "scripts/run_test_manifest.sh",
    "ci/pr_manifests/",
)


ACCESS_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/config/CorsConfig.java",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/config/SecurityConfig.java",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/",
)

AUTH_TENANT_PROOF_PATTERNS = (
    "openapi.json",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/OpenApiSnapshotIT.java",
)

FINANCE_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/factory/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/",
)

WORKFLOW_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/crossmodule/",
)

IDEMPOTENCY_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/idempotency/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/outbox/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/core/idempotency/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/orchestrator/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/p2p/",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/manufacturing/",
)

PERSISTENCE_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/",
    "erp-domain/src/main/resources/db/",
    "erp-domain/src/main/resources/application",
    "erp-domain/src/test/java/com/bigbrightpaints/erp/test/",
    "docker-compose.yml",
    "erp-domain/Dockerfile",
)

PERSISTENCE_KEYWORDS = (
    "/domain/",
    "/repository/",
    "AbstractIntegrationTest",
    "Flyway",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Route PR CI shards by changed-file risk classes")
    parser.add_argument("--base", required=True, help="Base commit SHA")
    parser.add_argument("--head", default="HEAD", help="Head commit/ref (default: HEAD)")
    parser.add_argument(
        "--coverage-baseline",
        default=os.environ.get("PR_CHANGED_COVERAGE_BASELINE_SHA", DEFAULT_CHANGED_COVERAGE_BASELINE_SHA),
        help=(
            "Optional changed-files coverage baseline commit/ref. "
            "When the requested base predates this baseline and the baseline is an ancestor of head, "
            "routing/coverage scope is compacted to this baseline."
        ),
    )
    parser.add_argument(
        "--github-output",
        default="",
        help="Optional path to the GitHub Actions output file; when omitted, prints KEY=VALUE pairs",
    )
    return parser.parse_args()


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True).strip()


def resolve_commit(ref: str) -> str:
    return run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"])


def is_ancestor(ancestor_ref: str, descendant_ref: str) -> bool:
    completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor_ref, descendant_ref],
        check=False,
        capture_output=True,
        text=True,
    )
    return completed.returncode == 0


def select_effective_diff_base(
    base_sha: str,
    head_sha: str,
    coverage_baseline_sha: str,
    ancestry_checker,
) -> tuple[str, bool]:
    if not coverage_baseline_sha:
        return base_sha, False

    baseline_is_newer_than_base = ancestry_checker(base_sha, coverage_baseline_sha)
    baseline_is_on_head_lineage = ancestry_checker(coverage_baseline_sha, head_sha)
    if baseline_is_newer_than_base and baseline_is_on_head_lineage:
        return coverage_baseline_sha, True
    return base_sha, False


def resolve_changed_files(
    base: str,
    head: str,
    coverage_baseline: str,
) -> tuple[list[str], str, str, str, str, bool]:
    (
        _requested_paths,
        coverage_paths,
        base_sha,
        head_sha,
        effective_base,
        baseline_sha,
        baseline_applied,
    ) = resolve_diff_scopes(base, head, coverage_baseline)
    return coverage_paths, base_sha, head_sha, effective_base, baseline_sha, baseline_applied


def resolve_diff_scopes(
    base: str,
    head: str,
    coverage_baseline: str,
) -> tuple[list[str], list[str], str, str, str, str, bool]:
    base_sha = resolve_commit(base)
    head_sha = resolve_commit(head)
    baseline_sha = ""
    if coverage_baseline.strip():
        try:
            baseline_sha = resolve_commit(coverage_baseline.strip())
        except subprocess.CalledProcessError:
            baseline_sha = ""
    effective_base, baseline_applied = select_effective_diff_base(
        base_sha,
        head_sha,
        baseline_sha,
        is_ancestor,
    )
    requested_paths = changed_files(base_sha, head_sha)
    coverage_paths = changed_files(effective_base, head_sha)
    return requested_paths, coverage_paths, base_sha, head_sha, effective_base, baseline_sha, baseline_applied


def changed_files(base: str, head: str) -> list[str]:
    output = run(["git", "diff", "--name-only", f"{base}...{head}"])
    return [line.strip() for line in output.splitlines() if line.strip()]


def matches_prefix(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def matches_keyword(path: str, keywords: tuple[str, ...]) -> bool:
    return any(keyword in path for keyword in keywords)


def compute_flags(paths: list[str], coverage_paths: list[str] | None = None) -> dict[str, str]:
    routing_paths = paths
    coverage_scope_paths = coverage_paths if coverage_paths is not None else paths

    run_ci_infra_validation = any(matches_prefix(path, CI_INFRA_PATTERNS) for path in routing_paths)
    changed_runtime_source_count = sum(
        1
        for path in coverage_scope_paths
        if path.startswith(JAVA_SOURCE_ROOT) and path not in LOCAL_SEED_RUNTIME_EXCLUSIONS
    )

    run_auth_tenant = run_ci_infra_validation or any(
        matches_prefix(path, ACCESS_PATTERNS) or matches_prefix(path, AUTH_TENANT_PROOF_PATTERNS)
        for path in routing_paths
    )
    run_accounting = run_ci_infra_validation or any(matches_prefix(path, FINANCE_PATTERNS) for path in routing_paths)
    run_idempotency_outbox = run_ci_infra_validation or any(
        matches_prefix(path, IDEMPOTENCY_PATTERNS) for path in routing_paths
    )
    run_business_slice = run_ci_infra_validation or any(matches_prefix(path, WORKFLOW_PATTERNS) for path in routing_paths)
    run_codered_access = any(matches_prefix(path, ACCESS_PATTERNS) for path in routing_paths)
    run_codered_finance = any(matches_prefix(path, FINANCE_PATTERNS) for path in routing_paths)
    run_codered_workflow = any(matches_prefix(path, WORKFLOW_PATTERNS) for path in routing_paths)
    run_persistence_smoke = run_ci_infra_validation or any(
        matches_prefix(path, PERSISTENCE_PATTERNS) or matches_keyword(path, PERSISTENCE_KEYWORDS) for path in routing_paths
    )

    return {
        "run_auth_tenant": "true" if run_auth_tenant else "false",
        "run_accounting": "true" if run_accounting else "false",
        "run_idempotency_outbox": "true" if run_idempotency_outbox else "false",
        "run_business_slice": "true" if run_business_slice else "false",
        "run_persistence_smoke": "true" if run_persistence_smoke else "false",
        "run_codered_access": "true" if run_codered_access else "false",
        "run_codered_finance": "true" if (run_codered_finance or run_codered_workflow) else "false",
        "run_changed_coverage": "true" if changed_runtime_source_count > 0 else "false",
        "changed_files_count": str(len(coverage_scope_paths)),
        "changed_runtime_source_count": str(changed_runtime_source_count),
    }


def emit_outputs(flags: dict[str, str], github_output: str) -> None:
    lines = [f"{key}={value}" for key, value in flags.items()]
    if github_output:
        output_path = Path(github_output)
        with output_path.open("a", encoding="utf-8") as fh:
            for line in lines:
                fh.write(line)
                fh.write("\n")
    else:
        print("\n".join(lines))


def main() -> int:
    args = parse_args()
    (
        requested_paths,
        coverage_paths,
        base_sha,
        head_sha,
        effective_diff_base,
        coverage_baseline_sha,
        coverage_baseline_applied,
    ) = resolve_diff_scopes(args.base, args.head, args.coverage_baseline)
    flags = compute_flags(requested_paths, coverage_paths=coverage_paths)
    flags.update(
        {
            "requested_diff_base": base_sha,
            "effective_diff_base": effective_diff_base,
            "coverage_baseline_sha": coverage_baseline_sha,
            "coverage_baseline_applied": "true" if coverage_baseline_applied else "false",
        }
    )
    emit_outputs(flags, args.github_output)
    print("[ci_risk_router] changed files (requested diff):", file=sys.stderr)
    for path in requested_paths:
        print(f"  - {path}", file=sys.stderr)
    if effective_diff_base != base_sha:
        print("[ci_risk_router] changed files (coverage diff):", file=sys.stderr)
        for path in coverage_paths:
            print(f"  - {path}", file=sys.stderr)
    print(
        (
            f"[ci_risk_router] diff scope: requested_base={base_sha} "
            f"effective_base={effective_diff_base} head={head_sha} "
            f"coverage_baseline={coverage_baseline_sha or 'none'} "
            f"coverage_baseline_applied={coverage_baseline_applied}"
        ),
        file=sys.stderr,
    )
    print("[ci_risk_router] outputs:", file=sys.stderr)
    for key, value in flags.items():
        print(f"  - {key}={value}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
