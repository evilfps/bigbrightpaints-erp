#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "artifacts" / "pr-ci-parity"
LOG_TAIL_LINES = 40
JACOCO_SOURCE = REPO_ROOT / "erp-domain" / "target" / "site" / "jacoco" / "jacoco.xml"
MAVEN_ENV = {"MIGRATION_SET": "v2"}

BASELINE_JOBS = (
    "ci-config-check",
    "knowledgebase-lint",
    "architecture-check",
    "high-risk-change-control",
    "secrets-scan",
)

ROUTED_SHARDS = (
    {
        "job": "pr-auth-tenant",
        "flag": "run_auth_tenant",
        "profile": "pr-fast",
        "label": "auth-tenant",
        "manifest": "ci/pr_manifests/pr_auth_tenant.txt",
        "artifact": "pr-jacoco-auth-tenant",
        "maven_args": ["-Dtest.groups="],
    },
    {
        "job": "pr-accounting",
        "flag": "run_accounting",
        "profile": "pr-fast",
        "label": "accounting",
        "manifest": "ci/pr_manifests/pr_accounting.txt",
        "artifact": "pr-jacoco-accounting",
        "maven_args": ["-Dtest.groups="],
    },
    {
        "job": "pr-idempotency-outbox",
        "flag": "run_idempotency_outbox",
        "profile": "pr-fast",
        "label": "idempotency-outbox",
        "manifest": "ci/pr_manifests/pr_idempotency_outbox.txt",
        "artifact": "pr-jacoco-idempotency-outbox",
    },
    {
        "job": "pr-business-slice",
        "flag": "run_business_slice",
        "profile": "pr-fast",
        "label": "business-slice",
        "manifest": "ci/pr_manifests/pr_business_slice.txt",
        "artifact": "pr-jacoco-business-slice",
        "maven_args": ["-Dtest.groups="],
    },
    {
        "job": "pr-persistence-smoke",
        "flag": "run_persistence_smoke",
        "profile": "pr-fast",
        "label": "persistence-smoke",
        "manifest": "ci/pr_manifests/pr_persistence_smoke.txt",
        "artifact": "pr-jacoco-persistence-smoke",
        "maven_args": ["-Dtest.groups="],
    },
    {
        "job": "pr-codered-access",
        "flag": "run_codered_access",
        "profile": "codered",
        "label": "codered-access",
        "manifest": "ci/pr_manifests/pr_codered_access.txt",
        "artifact": "pr-jacoco-codered-access",
    },
    {
        "job": "pr-codered-finance",
        "flag": "run_codered_finance",
        "profile": "codered",
        "label": "codered-finance",
        "manifest": "ci/pr_manifests/pr_codered_finance.txt",
        "artifact": "pr-jacoco-codered-finance",
    },
)

MERGE_GATE_NEEDS = [
    *BASELINE_JOBS,
    "pr-risk-router",
    "pr-build",
    *(spec["job"] for spec in ROUTED_SHARDS),
    "pr-changed-coverage",
]

JOB_LABELS = {
    "ci-config-check": ("ci-config", "CI Config Check"),
    "knowledgebase-lint": ("docs", "Docs Lint"),
    "architecture-check": ("module-boundary", "Module Boundary Check"),
    "high-risk-change-control": ("high-risk-control", "High-Risk Change Control"),
    "secrets-scan": ("secrets", "Secrets Scan"),
    "pr-risk-router": ("ci-routing", "Change Impact Router"),
    "pr-build": ("compile", "Compile Check"),
    "pr-auth-tenant": ("product-tests", "Access And Tenant Tests"),
    "pr-accounting": ("product-tests", "Finance And Accounting Tests"),
    "pr-idempotency-outbox": ("product-tests", "Idempotency And Outbox Tests"),
    "pr-business-slice": ("product-tests", "Workflow Integration Tests"),
    "pr-persistence-smoke": ("product-tests", "Persistence Smoke"),
    "pr-codered-access": ("security-tests", "CODE-RED Access Tests"),
    "pr-codered-finance": ("finance-tests", "CODE-RED Finance Tests"),
    "pr-changed-coverage": ("changed-code-coverage", "Changed-Code Coverage"),
}


def load_module(module_name: str, file_path: Path):
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


ci_risk_router = load_module("ci_risk_router_runtime", REPO_ROOT / "scripts" / "ci_risk_router.py")


@dataclass
class JobResult:
    name: str
    result: str
    command: str
    log_file: str | None = None
    duration_seconds: float = 0.0
    reason: str = ""
    jacoco_artifact: str | None = None
    changed_coverage_summary: str | None = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run repo-local parity for the pull_request jobs in .github/workflows/ci.yml")
    parser.add_argument("--base", required=True, help="PR base commit/ref used for routing and changed-files coverage")
    parser.add_argument("--head", default="HEAD", help="Head commit/ref to compare against the base (default: HEAD)")
    parser.add_argument(
        "--artifacts-dir",
        default=str(DEFAULT_ARTIFACTS_DIR),
        help=f"Directory for logs and summaries (default: {DEFAULT_ARTIFACTS_DIR})",
    )
    return parser.parse_args()


def run_git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=REPO_ROOT, text=True).strip()


def resolve_commit(ref: str) -> str:
    return run_git("rev-parse", "--verify", f"{ref}^{{commit}}")


def relpath(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def ensure_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def create_changed_coverage_skip_summary(
    diff_base: str,
    changed_files_count: int,
    changed_runtime_source_count: int,
) -> dict[str, object]:
    return {
        "diff_base": diff_base,
        "skipped": True,
        "reason": "no_runtime_source_changes",
        "changed_files_count": changed_files_count,
        "changed_runtime_source_count": changed_runtime_source_count,
        "passes": True,
    }


def changed_coverage_failure_is_compatible(summary: dict[str, object] | None) -> tuple[bool, str]:
    if not summary:
        return False, "missing-summary"
    if bool(summary.get("passes")):
        return False, "already-passing"
    if bool(summary.get("skipped")):
        return False, "explicitly-skipped"

    missing_coverage = bool(summary.get("missing_coverage"))
    vacuous = bool(summary.get("vacuous"))
    skipped_files = summary.get("coverage_skipped_files") or []
    unmapped_files = summary.get("files_with_unmapped_lines") or []

    if missing_coverage:
        return False, "missing-coverage"
    if vacuous:
        return False, "vacuous-coverage"
    if skipped_files:
        return False, "coverage-skipped-files"
    if unmapped_files:
        return False, "unmapped-changed-lines"
    return True, "threshold-only-compatibility"


def load_json(path: Path) -> dict[str, object] | None:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None


def build_routed_job_plan(flags: dict[str, str]) -> dict[str, bool]:
    return {spec["job"]: flags.get(spec["flag"], "false") == "true" for spec in ROUTED_SHARDS}


def evaluate_merge_gate(results: dict[str, str]) -> dict[str, str]:
    return {
        name: results[name]
        for name in MERGE_GATE_NEEDS
        if results.get(name, "missing") not in {"success", "skipped"}
    }


def tail_text(path: Path, lines: int = LOG_TAIL_LINES) -> str:
    if not path.exists():
        return ""
    content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(content[-lines:])


def write_json(path: Path, payload: dict[str, object]) -> None:
    ensure_dir(path.parent)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def copy_jacoco_artifact(artifact_name: str, artifacts_dir: Path) -> Path | None:
    if not JACOCO_SOURCE.exists():
        return None
    destination = ensure_dir(artifacts_dir / "pr-jacoco" / artifact_name) / "jacoco.xml"
    shutil.copy2(JACOCO_SOURCE, destination)
    return destination


def command_display(command: list[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def execute_job(
    name: str,
    command: list[str],
    log_path: Path,
    *,
    cwd: Path = REPO_ROOT,
    env_overrides: dict[str, str] | None = None,
    jacoco_artifact_name: str | None = None,
    artifacts_dir: Path,
) -> JobResult:
    env = os.environ.copy()
    env.update(MAVEN_ENV)
    if env_overrides:
        env.update(env_overrides)

    ensure_dir(log_path.parent)
    start = time.time()
    print(f"[{name}] START {command_display(command)}")
    with log_path.open("w", encoding="utf-8") as log_file:
        log_file.write(f"cwd={cwd}\n")
        log_file.write(f"command={command_display(command)}\n")
        if env_overrides:
            log_file.write(f"env_overrides={json.dumps(env_overrides, sort_keys=True)}\n")
        log_file.write("--- output ---\n")
        completed = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )

    duration = round(time.time() - start, 2)
    jacoco_artifact = None
    if jacoco_artifact_name:
        copied = copy_jacoco_artifact(jacoco_artifact_name, artifacts_dir)
        if copied is not None:
            jacoco_artifact = relpath(copied)

    if completed.returncode == 0:
        print(f"[{name}] SUCCESS in {duration:.2f}s (log: {relpath(log_path)})")
        return JobResult(
            name=name,
            result="success",
            command=command_display(command),
            log_file=relpath(log_path),
            duration_seconds=duration,
            jacoco_artifact=jacoco_artifact,
        )

    print(f"[{name}] FAILURE in {duration:.2f}s (log: {relpath(log_path)})")
    tail = tail_text(log_path)
    if tail:
        print(f"[{name}] log tail:\n{tail}")
    return JobResult(
        name=name,
        result="failure",
        command=command_display(command),
        log_file=relpath(log_path),
        duration_seconds=duration,
        reason=f"exit_code={completed.returncode}",
        jacoco_artifact=jacoco_artifact,
    )


def skipped_job(name: str, reason: str) -> JobResult:
    print(f"[{name}] SKIPPED ({reason})")
    return JobResult(name=name, result="skipped", command="", reason=reason)


def failed_job(name: str, reason: str) -> JobResult:
    print(f"[{name}] FAILURE ({reason})")
    return JobResult(name=name, result="failure", command="", reason=reason)


def main() -> int:
    args = parse_args()
    artifacts_dir = Path(args.artifacts_dir).resolve()
    logs_dir = ensure_dir(artifacts_dir / "logs")
    ensure_dir(artifacts_dir / "pr-jacoco")

    try:
        coverage_baseline_ref = os.environ.get(
            "PR_CHANGED_COVERAGE_BASELINE_SHA",
            getattr(ci_risk_router, "DEFAULT_CHANGED_COVERAGE_BASELINE_SHA", ""),
        )
        (
            requested_changed_files,
            coverage_changed_files,
            base_sha,
            head_sha,
            effective_diff_base,
            coverage_baseline_sha,
            coverage_baseline_applied,
        ) = ci_risk_router.resolve_diff_scopes(args.base, args.head, coverage_baseline_ref)
    except (subprocess.CalledProcessError, AttributeError) as exc:
        print(f"[pr-ci-parity] FAIL: unable to resolve base/head refs: {exc}", file=sys.stderr)
        return 2

    router_flags = ci_risk_router.compute_flags(requested_changed_files, coverage_paths=coverage_changed_files)
    routed_plan = build_routed_job_plan(router_flags)
    act_available = shutil.which("act") is not None

    router_summary = {
        "base": base_sha,
        "head": head_sha,
        "effective_diff_base": effective_diff_base,
        "coverage_baseline_sha": coverage_baseline_sha or None,
        "coverage_baseline_applied": coverage_baseline_applied,
        "changed_files": requested_changed_files,
        "coverage_changed_files": coverage_changed_files,
        "router_flags": router_flags,
        "routed_plan": routed_plan,
        "act_available": act_available,
        "act_note": "Optional helper only; local parity does not depend on act.",
    }
    write_json(artifacts_dir / "pr-risk-router.json", router_summary)
    print("[pr-risk-router] outputs:")
    print(json.dumps(router_summary, indent=2))

    results: dict[str, JobResult] = {}

    baseline_commands = {
        "knowledgebase-lint": (["bash", str(REPO_ROOT / "ci" / "lint-knowledgebase.sh")], {}),
        "architecture-check": (
            ["bash", str(REPO_ROOT / "ci" / "check-architecture.sh")],
            {"ARCH_DIFF_BASE": base_sha},
        ),
        "high-risk-change-control": (
            ["bash", str(REPO_ROOT / "ci" / "check-high-risk-changes.sh")],
            {"HIGH_RISK_DIFF_BASE": base_sha},
        ),
    }

    for job_name in BASELINE_JOBS:
        if job_name == "ci-config-check":
            if shutil.which("actionlint") is None or shutil.which("shellcheck") is None:
                results[job_name] = failed_job(
                    job_name,
                    "missing_actionlint_or_shellcheck",
                )
                continue
            results[job_name] = execute_job(
                job_name,
                ["bash", str(REPO_ROOT / "ci" / "check-ci-config.sh")],
                logs_dir / f"{job_name}.log",
                env_overrides={"CI_CONFIG_DIFF_BASE": base_sha},
                artifacts_dir=artifacts_dir,
            )
            continue

        if job_name == "secrets-scan":
            report_path = artifacts_dir / "gitleaks-report.json"
            if shutil.which("gitleaks") is None:
                results[job_name] = failed_job(
                    job_name,
                    "missing_gitleaks",
                )
                continue
            results[job_name] = execute_job(
                job_name,
                [
                    "gitleaks",
                    "git",
                    ".",
                    "--config",
                    ".gitleaks.toml",
                    "--redact",
                    "--report-format",
                    "json",
                    "--report-path",
                    str(report_path),
                    "--log-opts",
                    f"{base_sha}..{head_sha}",
                ],
                logs_dir / f"{job_name}.log",
                artifacts_dir=artifacts_dir,
            )
            continue

        command, env_overrides = baseline_commands[job_name]
        results[job_name] = execute_job(
            job_name,
            command,
            logs_dir / f"{job_name}.log",
            env_overrides=env_overrides,
            artifacts_dir=artifacts_dir,
        )

    results["pr-risk-router"] = JobResult(
        name="pr-risk-router",
        result="success",
        command=f"python3 scripts/ci_risk_router.py --base {base_sha} --head {head_sha}",
        log_file=relpath(artifacts_dir / "pr-risk-router.json"),
        reason="computed locally from git diff",
    )

    if results["pr-risk-router"].result != "success":
        results["pr-build"] = skipped_job("pr-build", "pr-risk-router_failed")
    else:
        results["pr-build"] = execute_job(
            "pr-build",
            ["mvn", "-B", "-ntp", "-Ppr-fast", "-DskipTests", "package"],
            logs_dir / "pr-build.log",
            cwd=REPO_ROOT / "erp-domain",
            artifacts_dir=artifacts_dir,
        )

    for spec in ROUTED_SHARDS:
        if results["pr-risk-router"].result != "success":
            results[spec["job"]] = skipped_job(spec["job"], "pr-risk-router_failed")
            continue
        if not routed_plan[spec["job"]]:
            results[spec["job"]] = skipped_job(spec["job"], f"{spec['flag']}=false")
            continue

        command = [
            "bash",
            str(REPO_ROOT / "scripts" / "run_test_manifest.sh"),
            "--profile",
            spec["profile"],
            "--label",
            spec["label"],
        ]
        for extra_arg in spec.get("maven_args", []):
            command.extend(["--maven-arg", extra_arg])
        command.extend(["--manifest", str(REPO_ROOT / spec["manifest"])])
        results[spec["job"]] = execute_job(
            spec["job"],
            command,
            logs_dir / f"{spec['job']}.log",
            jacoco_artifact_name=spec["artifact"],
            artifacts_dir=artifacts_dir,
        )

    changed_coverage_output = artifacts_dir / "pr-jacoco" / "changed-coverage.json"
    if results["pr-risk-router"].result != "success":
        results["pr-changed-coverage"] = JobResult(
            name="pr-changed-coverage",
            result="failure",
            command="",
            reason="pr-risk-router_failed",
        )
    elif router_flags["run_changed_coverage"] != "true":
        summary = create_changed_coverage_skip_summary(
            diff_base=effective_diff_base,
            changed_files_count=int(router_flags["changed_files_count"]),
            changed_runtime_source_count=int(router_flags["changed_runtime_source_count"]),
        )
        write_json(changed_coverage_output, summary)
        print("[pr-changed-coverage] no runtime source changes; recorded explicit skip summary")
        print(json.dumps(summary, indent=2))
        results["pr-changed-coverage"] = JobResult(
            name="pr-changed-coverage",
            result="success",
            command="python3 scripts/changed_files_coverage.py ...",
            changed_coverage_summary=relpath(changed_coverage_output),
        )
    else:
        jacoco_files = sorted((artifacts_dir / "pr-jacoco").rglob("jacoco.xml"))
        if not jacoco_files:
            print("[pr-changed-coverage] FAIL: no shard jacoco.xml files were downloaded", file=sys.stderr)
            results["pr-changed-coverage"] = JobResult(
                name="pr-changed-coverage",
                result="failure",
                command="python3 scripts/changed_files_coverage.py ...",
                reason="no shard jacoco.xml files were downloaded",
            )
        else:
            coverage_command = [sys.executable, str(REPO_ROOT / "scripts" / "changed_files_coverage.py")]
            for jacoco_file in jacoco_files:
                coverage_command.extend(["--jacoco", str(jacoco_file)])
            coverage_command.extend(
                [
                    "--diff-base",
                    effective_diff_base,
                    "--src-root",
                    "erp-domain/src/main/java",
                    "--threshold-line",
                    "0.95",
                    "--threshold-branch",
                    "0.90",
                    "--fail-on-vacuous",
                    "--output",
                    str(changed_coverage_output),
                ]
            )
            results["pr-changed-coverage"] = execute_job(
                "pr-changed-coverage",
                coverage_command,
                logs_dir / "pr-changed-coverage.log",
                artifacts_dir=artifacts_dir,
            )
            if changed_coverage_output.exists():
                results["pr-changed-coverage"].changed_coverage_summary = relpath(changed_coverage_output)
                coverage_summary = load_json(changed_coverage_output)
                compatible_failure, compatibility_reason = changed_coverage_failure_is_compatible(coverage_summary)
                if (
                    results["pr-changed-coverage"].result == "failure"
                    and coverage_baseline_applied
                    and compatible_failure
                ):
                    print(
                        "[pr-changed-coverage] WARN: thresholds were not met but coverage mapping is complete; "
                        "recording compatibility-mode success for long-lived diff parity."
                    )
                    results["pr-changed-coverage"].result = "success"
                    results["pr-changed-coverage"].reason = compatibility_reason

    merge_gate_statuses = {name: result.result for name, result in results.items()}
    blocking = evaluate_merge_gate(merge_gate_statuses)
    merge_gate_summary = {
        "base": base_sha,
        "head": head_sha,
        "effective_diff_base": effective_diff_base,
        "blocking": blocking,
        "needs": {name: results[name].result for name in MERGE_GATE_NEEDS},
        "passes": not blocking,
    }
    write_json(artifacts_dir / "pr-merge-gate.json", merge_gate_summary)
    if blocking:
        print("[pr-ship-gate] FAIL: blocking jobs detected")
        for name, status in sorted(blocking.items()):
            blocker_type, label = JOB_LABELS.get(name, ("unknown", name))
            print(f"  - {label}: {status} [{blocker_type}]")
        results["pr-merge-gate"] = JobResult(
            name="pr-merge-gate",
            result="failure",
            command="python3 - <<'PY' # evaluate merge gate",
            log_file=relpath(artifacts_dir / "pr-merge-gate.json"),
            reason=f"blocking jobs: {', '.join(sorted(blocking))}",
        )
    else:
        print("[pr-ship-gate] OK: all required jobs succeeded or were skipped")
        results["pr-merge-gate"] = JobResult(
            name="pr-merge-gate",
            result="success",
            command="python3 - <<'PY' # evaluate merge gate",
            log_file=relpath(artifacts_dir / "pr-merge-gate.json"),
        )

    final_summary = {
        "base": base_sha,
        "head": head_sha,
        "effective_diff_base": effective_diff_base,
        "coverage_baseline_sha": coverage_baseline_sha or None,
        "coverage_baseline_applied": coverage_baseline_applied,
        "act_available": act_available,
        "router_flags": router_flags,
        "routed_plan": routed_plan,
        "jobs": {name: result.__dict__ for name, result in results.items()},
        "merge_gate_passed": results["pr-merge-gate"].result == "success",
        "artifacts_dir": relpath(artifacts_dir),
    }
    write_json(artifacts_dir / "summary.json", final_summary)
    print("[pr-ci-parity] summary:")
    print(json.dumps(final_summary, indent=2))

    return 0 if final_summary["merge_gate_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
