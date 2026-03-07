#!/usr/bin/env python3
import argparse
import subprocess
import sys
from pathlib import Path


ACCESS_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/config/CorsConfig.java",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/config/SecurityConfig.java",
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
)

WORKFLOW_PATTERNS = (
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/",
    "erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/",
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
        "--github-output",
        default="",
        help="Optional path to the GitHub Actions output file; when omitted, prints KEY=VALUE pairs",
    )
    return parser.parse_args()


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True).strip()


def changed_files(base: str, head: str) -> list[str]:
    output = run(["git", "diff", "--name-only", f"{base}...{head}"])
    return [line.strip() for line in output.splitlines() if line.strip()]


def matches_prefix(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def matches_keyword(path: str, keywords: tuple[str, ...]) -> bool:
    return any(keyword in path for keyword in keywords)


def compute_flags(paths: list[str]) -> dict[str, str]:
    run_codered_access = any(matches_prefix(path, ACCESS_PATTERNS) for path in paths)
    run_codered_finance = any(matches_prefix(path, FINANCE_PATTERNS) for path in paths)
    run_codered_workflow = any(matches_prefix(path, WORKFLOW_PATTERNS) for path in paths)
    run_persistence_smoke = any(
        matches_prefix(path, PERSISTENCE_PATTERNS) or matches_keyword(path, PERSISTENCE_KEYWORDS)
        for path in paths
    )

    return {
        "run_auth_tenant": "true",
        "run_accounting": "true",
        "run_idempotency_outbox": "true",
        "run_business_slice": "true",
        "run_persistence_smoke": "true" if run_persistence_smoke else "false",
        "run_codered_access": "true" if run_codered_access else "false",
        "run_codered_finance": "true" if (run_codered_finance or run_codered_workflow) else "false",
        "changed_files_count": str(len(paths)),
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
    paths = changed_files(args.base, args.head)
    flags = compute_flags(paths)
    emit_outputs(flags, args.github_output)
    print("[ci_risk_router] changed files:", file=sys.stderr)
    for path in paths:
        print(f"  - {path}", file=sys.stderr)
    print("[ci_risk_router] outputs:", file=sys.stderr)
    for key, value in flags.items():
        print(f"  - {key}={value}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
