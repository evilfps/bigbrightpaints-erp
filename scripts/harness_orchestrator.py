#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml


RISK_ORDER = {
    "critical": 0,
    "high": 1,
    "medium": 2,
    "low": 3,
}


# Lightweight domain-edge model used by the orchestrator for cross-workflow planning.
# Edges are directional: upstream -> downstream.
WORKFLOW_AGENT_EDGES: list[tuple[str, str, str]] = [
    ("auth-rbac-company", "sales-domain", "tenant and role boundary contract"),
    ("auth-rbac-company", "inventory-domain", "tenant context and role checks"),
    ("auth-rbac-company", "purchasing-invoice-p2p", "tenant-scoped supplier/AP access rules"),
    ("auth-rbac-company", "factory-production", "tenant-scoped manufacturing operations"),
    ("auth-rbac-company", "reports-admin-portal", "admin/report access boundaries"),
    ("auth-rbac-company", "hr-domain", "payroll/PII access boundaries"),
    ("auth-rbac-company", "accounting-domain", "finance/admin authority boundaries"),
    ("sales-domain", "inventory-domain", "dispatch and stock movement linkage"),
    ("sales-domain", "accounting-domain", "o2c posting and receivable linkage"),
    ("purchasing-invoice-p2p", "inventory-domain", "grn/stock intake coupling"),
    ("purchasing-invoice-p2p", "accounting-domain", "ap/posting and settlement linkage"),
    ("factory-production", "inventory-domain", "production/packing stock transitions"),
    ("factory-production", "accounting-domain", "wip/variance/cogs posting linkage"),
    ("hr-domain", "accounting-domain", "payroll liability/payment posting linkage"),
    ("orchestrator-runtime", "sales-domain", "async orchestration command contract"),
    ("orchestrator-runtime", "inventory-domain", "exactly-once side-effect orchestration"),
    ("orchestrator-runtime", "accounting-domain", "outbox/idempotency posting orchestration"),
    ("data-migration", "release-ops", "migration rehearsal and release gating"),
]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


def run(cmd: list[str], cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=True,
        check=check,
    )


DISALLOWED_CHECK_TOKENS = {"|", "||", "&", "&&", ";", "<", ">", ">>", "1>", "1>>", "2>", "2>>"}
DISALLOWED_SHELL_CHARS = {"|", "&", ";", "<", ">"}
DISALLOWED_CHECK_EXECUTABLES = {"bash", "sh", "zsh"}
DISALLOWED_SHELL_FLAGS = {"-c", "-lc", "--command"}


def parse_required_check_command(command: str) -> list[str]:
    raw = str(command).strip()
    if not raw:
        raise ValueError("required check command cannot be blank")
    if "\n" in raw or "\r" in raw:
        raise ValueError(f"required check command must be single-line: {raw!r}")

    try:
        argv = shlex.split(raw, posix=True)
    except ValueError as exc:
        raise ValueError(f"invalid required check command {raw!r}: {exc}") from exc

    if not argv:
        raise ValueError("required check command cannot be blank")
    if any(token in DISALLOWED_CHECK_TOKENS for token in argv):
        raise ValueError(f"required check command uses unsupported shell syntax: {raw!r}")
    if any(any(char in token for char in DISALLOWED_SHELL_CHARS) for token in argv):
        raise ValueError(f"required check command uses unsupported shell syntax: {raw!r}")
    if any("$(" in token or "`" in token for token in argv):
        raise ValueError(f"required check command uses unsupported shell expansion: {raw!r}")

    executable = Path(argv[0]).name
    if executable in DISALLOWED_CHECK_EXECUTABLES and any(arg in DISALLOWED_SHELL_FLAGS for arg in argv[1:]):
        raise ValueError(f"required check command may not invoke an interactive shell: {raw!r}")

    return argv


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as fh:
        data = yaml.safe_load(fh)
    return data if isinstance(data, dict) else {}


def save_yaml(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        yaml.safe_dump(data, fh, sort_keys=False)


def slugify(value: str) -> str:
    out = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip()).strip("-").lower()
    return out or "slice"


def project_root() -> Path:
    return Path(__file__).resolve().parents[1]


def default_worktree_root(repo_root: Path) -> Path:
    return repo_root.parent / f"{repo_root.name}_worktrees"


def default_tmux_lanes(orchestrator_layer: dict[str, Any]) -> list[str]:
    automation = orchestrator_layer.get("automation", {})
    lanes = automation.get("tmux_lanes")
    if isinstance(lanes, list) and lanes:
        return [str(x) for x in lanes]
    return ["w1", "w2", "w3", "w4"]


def parse_paths(paths: str, path_items: list[str]) -> list[str]:
    values: list[str] = []
    if paths:
        values.extend([p.strip() for p in paths.split(",") if p.strip()])
    values.extend([p.strip() for p in path_items if p.strip()])
    deduped = sorted(set(values))
    if not deduped:
        raise ValueError("no scope paths provided; use --paths or one/more --path")
    return deduped


DOCS_ONLY_EXACT = {"AGENTS.md", "ARCHITECTURE.md", "README.md"}
DOCS_ONLY_PREFIXES = ("docs/", "tickets/")


def is_docs_only_file(path: str) -> bool:
    p = str(path).strip()
    if not p:
        return False
    if p in DOCS_ONLY_EXACT:
        return True
    if p.startswith(DOCS_ONLY_PREFIXES):
        return True
    return p.endswith(".md")


def is_docs_only_scope(scope_paths: list[str]) -> bool:
    if not scope_paths:
        return False
    for path in scope_paths:
        if not is_docs_only_file(path):
            return False
    return True


def load_contracts(repo_root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, dict[str, Any]]]:
    orchestrator_layer = load_yaml(repo_root / "agents" / "orchestrator-layer.yaml")
    catalog = load_yaml(repo_root / "agents" / "catalog.yaml")

    agent_defs: dict[str, dict[str, Any]] = {}
    for agent_file in sorted((repo_root / "agents").glob("*.agent.yaml")):
        data = load_yaml(agent_file)
        agent_id = str(data.get("id", "")).strip()
        if agent_id:
            agent_defs[agent_id] = data

    if not orchestrator_layer:
        raise RuntimeError("missing/invalid agents/orchestrator-layer.yaml")
    if not catalog:
        raise RuntimeError("missing/invalid agents/catalog.yaml")
    return orchestrator_layer, catalog, agent_defs


def path_matches_rule(target: str, rule_path: str) -> bool:
    t = target.rstrip("/")
    r = rule_path.rstrip("/")

    if "*" in r or "?" in r or "[" in r:
        return fnmatch.fnmatch(t, r)

    return t.startswith(r) or r.startswith(t)


def path_in_allowed_scope(path: str, allowed_scope: str) -> bool:
    p = path.rstrip("/")
    s = allowed_scope.rstrip("/")
    if "*" in s or "?" in s or "[" in s:
        return fnmatch.fnmatch(p, s)
    return p.startswith(s)


def resolve_route(orchestrator_layer: dict[str, Any], target_path: str) -> dict[str, Any]:
    best: dict[str, Any] | None = None
    best_score = -1

    for rule in orchestrator_layer.get("routing_rules", []):
        match = rule.get("match", {})
        rule_paths = match.get("paths", [])
        if not isinstance(rule_paths, list):
            continue

        for rule_path in rule_paths:
            rule_path_s = str(rule_path)
            if not path_matches_rule(target_path, rule_path_s):
                continue
            score = len(rule_path_s)
            if score > best_score:
                best = {
                    "primary_agent": str(rule.get("primary_agent", "")).strip(),
                    "reviewers": [str(x).strip() for x in rule.get("reviewers", []) if str(x).strip()],
                    "rule_path": rule_path_s,
                }
                best_score = score

    if best:
        return best

    # Fallback for unmatched paths.
    return {
        "primary_agent": "refactor-techdebt-gc",
        "reviewers": ["qa-reliability"],
        "rule_path": "<fallback>",
    }


def risk_for_agent(catalog: dict[str, Any], agent_id: str) -> str:
    for entry in catalog.get("agents", []):
        if str(entry.get("id", "")).strip() == agent_id:
            return str(entry.get("risk_tier", "medium")).strip().lower()
    return "medium"


def resolve_multi_agent_role_profile(orchestrator_layer: dict[str, Any], agent_id: str) -> dict[str, Any]:
    runtime = orchestrator_layer.get("runtime", {})
    subagents = runtime.get("subagents", {})
    role_policy = subagents.get("role_model_policy", {})
    role_map = subagents.get("agent_role_mapping", {})

    role_entry: Any = None
    if isinstance(role_map, dict):
        role_entry = role_map.get(agent_id)
        if role_entry is None:
            role_entry = role_map.get("default")

    role = ""
    config_file = ""
    if isinstance(role_entry, str):
        role = role_entry.strip()
    elif isinstance(role_entry, dict):
        role = str(role_entry.get("role", "")).strip()
        config_file = str(role_entry.get("config_file", "")).strip()

    policy: dict[str, Any] = {}
    if role and isinstance(role_policy, dict):
        candidate = role_policy.get(role, {})
        if isinstance(candidate, dict):
            policy = candidate

    fallback = policy.get("fallback", [])
    if not isinstance(fallback, list):
        fallback = []

    return {
        "role": role,
        "config_file": config_file,
        "model": str(policy.get("preferred_model", "")).strip(),
        "reasoning": str(policy.get("preferred_reasoning", "")).strip(),
        "fallback": fallback,
    }


def branch_exists(repo_root: Path, branch: str) -> bool:
    proc = run(["git", "show-ref", "--verify", f"refs/heads/{branch}"], cwd=repo_root, check=False)
    return proc.returncode == 0


def remote_branch_exists(repo_root: Path, branch: str) -> bool:
    proc = run(["git", "show-ref", "--verify", f"refs/remotes/origin/{branch}"], cwd=repo_root, check=False)
    return proc.returncode == 0


def resolve_base_ref(repo_root: Path, requested_base: str) -> str:
    base = requested_base.strip()
    if not base:
        raise RuntimeError("base branch cannot be empty")

    if base.startswith("origin/"):
        remote_ref = f"refs/remotes/{base}"
        proc = run(["git", "show-ref", "--verify", remote_ref], cwd=repo_root, check=False)
        if proc.returncode != 0:
            raise RuntimeError(f"base branch not found: {base}")
        return base

    has_local = branch_exists(repo_root, base)
    has_remote = remote_branch_exists(repo_root, base)

    if has_local and has_remote:
        local_sha = run(["git", "rev-parse", f"refs/heads/{base}"], cwd=repo_root).stdout.strip()
        remote_sha = run(["git", "rev-parse", f"refs/remotes/origin/{base}"], cwd=repo_root).stdout.strip()
        if local_sha != remote_sha:
            return f"origin/{base}"
        return base

    if has_remote:
        return f"origin/{base}"
    if has_local:
        return base

    raise RuntimeError(f"base branch not found locally/remotely: {base}")


def ensure_worktree(repo_root: Path, worktree_path: Path, branch: str, base_branch: str) -> None:
    worktree_path.parent.mkdir(parents=True, exist_ok=True)
    if worktree_path.exists() and (worktree_path / ".git").exists():
        return

    if branch_exists(repo_root, branch):
        cmd = ["git", "worktree", "add", str(worktree_path), branch]
    else:
        cmd = ["git", "worktree", "add", "-b", branch, str(worktree_path), base_branch]

    proc = run(cmd, cwd=repo_root, check=False)
    if proc.returncode != 0:
        raise RuntimeError(
            f"failed creating worktree for branch={branch} at {worktree_path}\n{proc.stderr.strip()}"
        )


def ticket_dir(repo_root: Path, ticket_id: str) -> Path:
    return repo_root / "tickets" / ticket_id


def ticket_file(repo_root: Path, ticket_id: str) -> Path:
    return ticket_dir(repo_root, ticket_id) / "ticket.yaml"


def read_ticket(repo_root: Path, ticket_id: str) -> dict[str, Any]:
    path = ticket_file(repo_root, ticket_id)
    if not path.exists():
        raise RuntimeError(f"ticket not found: {path}")
    data = load_yaml(path)
    if not data:
        raise RuntimeError(f"invalid ticket file: {path}")
    return data


def write_ticket(repo_root: Path, ticket: dict[str, Any]) -> None:
    ticket["updated_at"] = utc_now()
    save_yaml(ticket_file(repo_root, str(ticket["ticket_id"])), ticket)


def append_timeline(repo_root: Path, ticket_id: str, line: str) -> None:
    path = ticket_dir(repo_root, ticket_id) / "TIMELINE.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text("# Timeline\n\n", encoding="utf-8")
    with path.open("a", encoding="utf-8") as fh:
        fh.write(f"- `{utc_now()}` {line}\n")


def append_timeline_event(
    repo_root: Path,
    ticket_id: str,
    event: str,
    summary: str,
    payload: dict[str, Any],
    *,
    at_utc: str = "",
) -> None:
    stamp = at_utc.strip() or utc_now()
    path = ticket_dir(repo_root, ticket_id) / "TIMELINE.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text("# Timeline\n\n", encoding="utf-8")
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    line = f"- `{stamp}` [event:{event}] {summary} | payload={encoded}\n"
    with path.open("a", encoding="utf-8") as fh:
        fh.write(line)


def timeline_text(repo_root: Path, ticket_id: str) -> str:
    path = ticket_dir(repo_root, ticket_id) / "TIMELINE.md"
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def parse_transition_pairs(values: list[Any]) -> set[tuple[str, str]]:
    pairs: set[tuple[str, str]] = set()
    for item in values:
        raw = str(item).strip()
        if "->" not in raw:
            continue
        src, dst = raw.split("->", 1)
        src_n = src.strip().lower()
        dst_n = dst.strip().lower()
        if src_n and dst_n:
            pairs.add((src_n, dst_n))
    return pairs


def claim_contract(orchestrator_layer: dict[str, Any]) -> dict[str, Any]:
    dispatch_protocol = orchestrator_layer.get("dispatch_protocol", {})
    policy = dispatch_protocol.get("ticket_claim_enforcement", {})
    required_fields = [str(x).strip() for x in policy.get("required_fields", []) if str(x).strip()]
    if not required_fields:
        required_fields = ["agent_id", "slice_id", "worktree_path", "branch", "claimed_at_utc"]
    return {
        "require_ticket_claim_before_work": bool(dispatch_protocol.get("require_ticket_claim_before_work", False)),
        "claim_status": str(policy.get("claim_status", "taken")).strip().lower() or "taken",
        "allowed_transitions": parse_transition_pairs(policy.get("allowed_transitions", [])),
        "required_fields": required_fields,
        "require_timeline_claim_event": bool(policy.get("require_timeline_claim_event", True)),
        "allow_legacy_timeline_claim_lines": bool(policy.get("allow_legacy_timeline_claim_lines", True)),
        "require_ticket_status_history": bool(policy.get("require_ticket_status_history", True)),
        "reject_unclaimed_agent_results": bool(policy.get("reject_unclaimed_agent_results", True)),
        "prevent_dual_claims_for_same_slice": bool(policy.get("prevent_dual_claims_for_same_slice", True)),
    }


def blocker_id_from_branch(branch: str) -> str:
    match = re.search(r"/(b\d{2})(?:[-/]|$)", branch.lower())
    if not match:
        return ""
    return match.group(1).upper()


def strip_inline_code(value: str) -> str:
    text = str(value).strip()
    if len(text) >= 2 and text.startswith("`") and text.endswith("`"):
        return text[1:-1].strip()
    return text


def blocker_matrix_coordinates(repo_root: Path, ticket_id: str, blocker_id: str) -> tuple[str, str] | None:
    target = blocker_id.strip().upper()
    if not target:
        return None

    matrix_path = ticket_dir(repo_root, ticket_id) / "reports" / "BLOCKER_SLICE_MATRIX.md"
    if not matrix_path.exists():
        return None

    header_cells: list[str] = []
    blocker_idx = -1
    branch_idx = -1
    worktree_idx = -1

    lines = matrix_path.read_text(encoding="utf-8", errors="replace").splitlines()
    for raw in lines:
        line = raw.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if not cells:
            continue
        compact_cells = [c.replace(" ", "") for c in cells]
        if all(re.fullmatch(r":?-{3,}:?", c) for c in compact_cells if c):
            continue

        lowered = [strip_inline_code(c).lower() for c in cells]
        if not header_cells and {"blocker_id", "branch_name", "worktree_path"}.issubset(set(lowered)):
            header_cells = lowered
            blocker_idx = header_cells.index("blocker_id")
            branch_idx = header_cells.index("branch_name")
            worktree_idx = header_cells.index("worktree_path")
            continue

        if not header_cells:
            continue
        if len(cells) <= max(blocker_idx, branch_idx, worktree_idx):
            continue

        row_blocker = strip_inline_code(cells[blocker_idx]).upper()
        if row_blocker != target:
            continue

        branch = strip_inline_code(cells[branch_idx])
        worktree_path = strip_inline_code(cells[worktree_idx])
        if branch and worktree_path:
            return branch, worktree_path
        return None

    return None


def claim_expected_coordinates(
    repo_root: Path,
    ticket_id: str,
    slice_data: dict[str, Any],
    claim: dict[str, Any],
) -> tuple[str, str, str, str]:
    branch = str(slice_data.get("branch", "")).strip()
    worktree_path = str(slice_data.get("worktree_path", "")).strip()
    branch_label = "slice branch"
    worktree_label = "slice worktree_path"

    blocker_id = str(claim.get("blocker_id", "")).strip().upper()
    if blocker_id:
        matrix_coords = blocker_matrix_coordinates(repo_root, ticket_id, blocker_id)
        if matrix_coords:
            branch, worktree_path = matrix_coords
            branch_label = "blocker matrix branch"
            worktree_label = "blocker matrix worktree_path"
    return branch, worktree_path, branch_label, worktree_label


def resolve_slice_execution_coordinates(
    repo_root: Path, ticket_id: str, slice_data: dict[str, Any]
) -> tuple[str, str]:
    branch = str(slice_data.get("branch", "")).strip()
    worktree_path = str(slice_data.get("worktree_path", "")).strip()

    claim = slice_data.get("claim")
    if not isinstance(claim, dict):
        return branch, worktree_path

    blocker_id = str(claim.get("blocker_id", "")).strip().upper()
    if blocker_id:
        matrix_coords = blocker_matrix_coordinates(repo_root, ticket_id, blocker_id)
        if matrix_coords:
            return matrix_coords

    claim_branch = str(claim.get("branch", "")).strip()
    claim_worktree_path = str(claim.get("worktree_path", "")).strip()
    if claim_branch:
        branch = claim_branch
    if claim_worktree_path:
        worktree_path = claim_worktree_path
    return branch, worktree_path


def ensure_history_list(target: dict[str, Any], key: str) -> list[dict[str, Any]]:
    history = target.get(key)
    if isinstance(history, list):
        return [x for x in history if isinstance(x, dict)]
    return []


def apply_slice_status(
    slice_data: dict[str, Any],
    new_status: str,
    *,
    reason: str,
    actor: str,
    at_utc: str,
    allowed_transitions: set[tuple[str, str]] | None = None,
    strict_transition_check: bool = False,
) -> tuple[str, str]:
    prev_status = str(slice_data.get("status", "")).strip().lower()
    next_status = str(new_status).strip().lower()
    if not next_status:
        raise RuntimeError("slice status cannot be empty")
    if prev_status and prev_status != next_status and strict_transition_check and allowed_transitions is not None:
        if (prev_status, next_status) not in allowed_transitions:
            raise RuntimeError(f"invalid status transition: {prev_status} -> {next_status}")

    history = ensure_history_list(slice_data, "status_history")
    if prev_status != next_status:
        history.append(
            {
                "from_status": prev_status,
                "to_status": next_status,
                "at_utc": at_utc,
                "reason": reason,
                "actor": actor,
            }
        )
    slice_data["status_history"] = history
    slice_data["status"] = next_status
    return prev_status, next_status


def apply_ticket_status(
    ticket: dict[str, Any],
    new_status: str,
    *,
    reason: str,
    actor: str,
    at_utc: str,
) -> tuple[str, str]:
    prev_status = str(ticket.get("status", "")).strip().lower()
    next_status = str(new_status).strip().lower()
    if not next_status:
        raise RuntimeError("ticket status cannot be empty")

    history = ensure_history_list(ticket, "status_history")
    if prev_status != next_status:
        history.append(
            {
                "from_status": prev_status,
                "to_status": next_status,
                "at_utc": at_utc,
                "reason": reason,
                "actor": actor,
            }
        )
    ticket["status_history"] = history
    ticket["status"] = next_status
    return prev_status, next_status


def apply_claim_transition_policy(
    slice_data: dict[str, Any],
    *,
    requested_claim_status: str,
    auto_in_progress: bool,
    allowed_transitions: set[tuple[str, str]],
    strict_transition_check: bool,
    actor: str,
    at_utc: str,
) -> list[str]:
    current_status = str(slice_data.get("status", "")).strip().lower() or "ready"
    slice_data["status"] = current_status
    status_chain = [current_status]

    if (
        current_status == "blocked"
        and requested_claim_status == "taken"
        and strict_transition_check
        and ("blocked", "taken") not in allowed_transitions
        and ("blocked", "ready") in allowed_transitions
    ):
        prev_status, next_status = apply_slice_status(
            slice_data,
            "ready",
            reason="claim_recorded",
            actor=actor,
            at_utc=at_utc,
            allowed_transitions=allowed_transitions,
            strict_transition_check=strict_transition_check,
        )
        if prev_status != next_status:
            status_chain.append(next_status)

    prev_status, next_status = apply_slice_status(
        slice_data,
        requested_claim_status,
        reason="claim_recorded",
        actor=actor,
        at_utc=at_utc,
        allowed_transitions=allowed_transitions,
        strict_transition_check=strict_transition_check,
    )
    if prev_status != next_status:
        status_chain.append(next_status)

    if auto_in_progress and str(slice_data.get("status", "")).strip().lower() != "in_progress":
        prev_status, next_status = apply_slice_status(
            slice_data,
            "in_progress",
            reason="claim_recorded",
            actor=actor,
            at_utc=at_utc,
            allowed_transitions=allowed_transitions,
            strict_transition_check=strict_transition_check,
        )
        if prev_status != next_status:
            status_chain.append(next_status)

    return status_chain


def parse_event_payload(line: str) -> dict[str, Any] | None:
    marker = "payload="
    idx = line.find(marker)
    if idx < 0:
        return None
    raw = line[idx + len(marker) :].strip()
    if not raw:
        return None
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def timeline_has_claim_line(
    repo_root: Path,
    ticket_id: str,
    slice_data: dict[str, Any],
    claim: dict[str, Any],
    *,
    allow_legacy: bool = True,
) -> bool:
    sid = str(slice_data.get("id", "")).strip()
    branch = str(claim.get("branch", "")).strip() or str(slice_data.get("branch", "")).strip()
    worktree_path = str(claim.get("worktree_path", "")).strip() or str(slice_data.get("worktree_path", "")).strip()
    agent_id = str(claim.get("agent_id", "")).strip() or str(slice_data.get("primary_agent", "")).strip()
    text = timeline_text(repo_root, ticket_id)
    if not text:
        return False

    for raw in text.splitlines():
        line = raw.strip()
        if "[event:claim_recorded]" in line:
            payload = parse_event_payload(line)
            if not payload:
                continue
            if str(payload.get("slice_id", "")).strip() != sid:
                continue
            if str(payload.get("branch", "")).strip() != branch:
                continue
            if str(payload.get("worktree_path", "")).strip() != worktree_path:
                continue
            if str(payload.get("agent_id", "")).strip() != str(claim.get("agent_id", "")).strip():
                continue
            return True

        if allow_legacy and "claim recorded:" in line and f"`{sid}`" in line:
            if f"`{branch}`" in line and f"`{worktree_path}`" in line and f"`{agent_id}`" in line:
                return True
    return False


def validate_claim_fields(
    slice_data: dict[str, Any],
    required_fields: list[str],
    *,
    expected_branch: str = "",
    expected_worktree_path: str = "",
    expected_branch_label: str = "slice branch",
    expected_worktree_label: str = "slice worktree_path",
) -> tuple[dict[str, Any], list[str]]:
    claim = slice_data.get("claim")
    if not isinstance(claim, dict):
        return {}, ["claim object missing"]

    errors: list[str] = []
    for field in required_fields:
        value = str(claim.get(field, "")).strip()
        if not value:
            errors.append(f"missing claim field: {field}")

    sid = str(slice_data.get("id", "")).strip()
    branch = expected_branch.strip() or str(slice_data.get("branch", "")).strip()
    worktree_path = expected_worktree_path.strip() or str(slice_data.get("worktree_path", "")).strip()
    agent_id = str(slice_data.get("primary_agent", "")).strip()

    if str(claim.get("slice_id", "")).strip() and str(claim.get("slice_id", "")).strip() != sid:
        errors.append("claim slice_id does not match slice id")
    if str(claim.get("branch", "")).strip() and str(claim.get("branch", "")).strip() != branch:
        errors.append(f"claim branch does not match {expected_branch_label}")
    if str(claim.get("worktree_path", "")).strip() and str(claim.get("worktree_path", "")).strip() != worktree_path:
        errors.append(f"claim worktree_path does not match {expected_worktree_label}")
    if str(claim.get("agent_id", "")).strip() and str(claim.get("agent_id", "")).strip() != agent_id:
        errors.append("claim agent_id does not match slice primary_agent")

    return claim, errors


def claim_required_for_slice(slice_data: dict[str, Any], ahead: int) -> bool:
    status = str(slice_data.get("status", "")).strip().lower()
    if ahead > 0:
        return True
    claim_enforced_statuses = {
        "taken",
        "in_progress",
        "in_review",
        "pending_review",
    }
    return status in claim_enforced_statuses


def reviewers_high_risk(orchestrator_layer: dict[str, Any]) -> list[str]:
    values = orchestrator_layer.get("review_pipeline", {}).get("high_risk_additional_reviewers", [])
    if isinstance(values, list):
        return [str(v).strip() for v in values if str(v).strip()]
    return []


def annotate_ticket_workflow(ticket: dict[str, Any]) -> dict[str, Any]:
    slices = [s for s in ticket.get("slices", []) if isinstance(s, dict)]
    by_id: dict[str, dict[str, Any]] = {}
    agent_to_slices: dict[str, list[str]] = {}

    for s in slices:
        sid = str(s.get("id", "")).strip()
        agent = str(s.get("primary_agent", "")).strip()
        if not sid or not agent:
            continue
        by_id[sid] = s
        agent_to_slices.setdefault(agent, []).append(sid)

    upstream_map: dict[str, set[str]] = {sid: set() for sid in by_id}
    downstream_map: dict[str, set[str]] = {sid: set() for sid in by_id}
    ext_upstream_agents: dict[str, set[str]] = {sid: set() for sid in by_id}
    ext_downstream_agents: dict[str, set[str]] = {sid: set() for sid in by_id}
    contract_map: dict[str, set[tuple[str, str, str, str]]] = {sid: set() for sid in by_id}
    in_ticket_edges: set[tuple[str, str, str]] = set()

    for src_agent, dst_agent, contract in WORKFLOW_AGENT_EDGES:
        src_sids = agent_to_slices.get(src_agent, [])
        dst_sids = agent_to_slices.get(dst_agent, [])

        if src_sids and dst_sids:
            for src_sid in src_sids:
                for dst_sid in dst_sids:
                    if src_sid == dst_sid:
                        continue
                    downstream_map[src_sid].add(dst_sid)
                    upstream_map[dst_sid].add(src_sid)
                    in_ticket_edges.add((src_sid, dst_sid, contract))
                    contract_map[src_sid].add(("downstream", dst_agent, dst_sid, contract))
                    contract_map[dst_sid].add(("upstream", src_agent, src_sid, contract))
            continue

        if src_sids and not dst_sids:
            for src_sid in src_sids:
                ext_downstream_agents[src_sid].add(dst_agent)
                contract_map[src_sid].add(("downstream-external", dst_agent, "", contract))
            continue

        if dst_sids and not src_sids:
            for dst_sid in dst_sids:
                ext_upstream_agents[dst_sid].add(src_agent)
                contract_map[dst_sid].add(("upstream-external", src_agent, "", contract))

    for sid, slice_data in by_id.items():
        slice_data["workflow_upstream_slices"] = sorted(upstream_map[sid])
        slice_data["workflow_downstream_slices"] = sorted(downstream_map[sid])
        slice_data["workflow_external_upstream_agents"] = sorted(ext_upstream_agents[sid])
        slice_data["workflow_external_downstream_agents"] = sorted(ext_downstream_agents[sid])
        slice_data["cross_workflow_contracts"] = [
            {
                "relation": relation,
                "agent": agent,
                "slice_id": link_sid,
                "contract": contract,
                "in_ticket": relation in {"upstream", "downstream"},
            }
            for relation, agent, link_sid, contract in sorted(contract_map[sid])
        ]

    ticket["cross_workflow"] = {
        "model": "workflow_agent_edges_v1",
        "generated_at": utc_now(),
        "in_ticket_edges": [
            {"upstream_slice": src_sid, "downstream_slice": dst_sid, "contract": contract}
            for src_sid, dst_sid, contract in sorted(in_ticket_edges)
        ],
    }
    return ticket["cross_workflow"]


def slice_dependency_order(ticket: dict[str, Any]) -> list[str]:
    slices = [s for s in ticket.get("slices", []) if isinstance(s, dict)]
    ids = [str(s.get("id", "")).strip() for s in slices if str(s.get("id", "")).strip()]
    id_set = set(ids)
    indegree: dict[str, int] = {sid: 0 for sid in ids}
    outgoing: dict[str, set[str]] = {sid: set() for sid in ids}

    for s in slices:
        sid = str(s.get("id", "")).strip()
        if sid not in id_set:
            continue
        for upstream_sid in s.get("workflow_upstream_slices", []):
            up = str(upstream_sid).strip()
            if up in id_set and up != sid and sid not in outgoing[up]:
                outgoing[up].add(sid)
                indegree[sid] += 1

    queue = sorted([sid for sid, deg in indegree.items() if deg == 0])
    ordered: list[str] = []
    while queue:
        sid = queue.pop(0)
        ordered.append(sid)
        for nxt in sorted(outgoing[sid]):
            indegree[nxt] -= 1
            if indegree[nxt] == 0:
                queue.append(nxt)
        queue.sort()

    if len(ordered) < len(ids):
        leftovers = [sid for sid in ids if sid not in ordered]
        ordered.extend(sorted(leftovers))
    return ordered


def write_cross_workflow_plan(repo_root: Path, ticket: dict[str, Any]) -> Path:
    tid = str(ticket["ticket_id"])
    path = ticket_dir(repo_root, tid) / "CROSS_WORKFLOW_PLAN.md"
    by_id = {
        str(s.get("id", "")).strip(): s
        for s in ticket.get("slices", [])
        if isinstance(s, dict) and str(s.get("id", "")).strip()
    }
    edges = ticket.get("cross_workflow", {}).get("in_ticket_edges", [])
    merge_order = slice_dependency_order(ticket)

    lines = [
        "# Cross Workflow Plan",
        "",
        f"Ticket: `{tid}`",
        f"Generated: `{utc_now()}`",
        "",
        "## In-Ticket Dependency Edges",
        "",
    ]

    if not edges:
        lines.append("- none")
    else:
        lines.extend(
            [
                "| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |",
                "| --- | --- | --- | --- | --- |",
            ]
        )
        for edge in edges:
            src_sid = str(edge.get("upstream_slice", "")).strip()
            dst_sid = str(edge.get("downstream_slice", "")).strip()
            contract = str(edge.get("contract", "")).strip()
            src_agent = str(by_id.get(src_sid, {}).get("primary_agent", "unknown"))
            dst_agent = str(by_id.get(dst_sid, {}).get("primary_agent", "unknown"))
            lines.append(f"| {src_sid} | {src_agent} | {dst_sid} | {dst_agent} | {contract} |")

    lines.extend(
        [
            "",
            "## Recommended Merge Order",
            "",
        ]
    )
    if not merge_order:
        lines.append("- none")
    else:
        for idx, sid in enumerate(merge_order, start=1):
            agent = str(by_id.get(sid, {}).get("primary_agent", "unknown"))
            lines.append(f"{idx}. `{sid}` ({agent})")

    lines.extend(
        [
            "",
            "## Slice Coordination Notes",
            "",
        ]
    )
    for sid in merge_order:
        s = by_id.get(sid, {})
        upstream = [str(x) for x in s.get("workflow_upstream_slices", []) if str(x).strip()]
        downstream = [str(x) for x in s.get("workflow_downstream_slices", []) if str(x).strip()]
        ext_up = [str(x) for x in s.get("workflow_external_upstream_agents", []) if str(x).strip()]
        ext_down = [str(x) for x in s.get("workflow_external_downstream_agents", []) if str(x).strip()]

        lines.append(f"### {sid} ({s.get('primary_agent')})")
        lines.append(f"- Upstream slices: {', '.join(upstream) if upstream else 'none'}")
        lines.append(f"- Downstream slices: {', '.join(downstream) if downstream else 'none'}")
        lines.append(f"- External upstream agents to watch: {', '.join(ext_up) if ext_up else 'none'}")
        lines.append(f"- External downstream agents to watch: {', '.join(ext_down) if ext_down else 'none'}")
        lines.append("")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def write_packet_files(repo_root: Path, ticket: dict[str, Any], slice_data: dict[str, Any]) -> None:
    tid = str(ticket["ticket_id"])
    sid = str(slice_data["id"])
    slice_dir = ticket_dir(repo_root, tid) / "slices" / sid
    reviews_dir = slice_dir / "reviews"
    slice_dir.mkdir(parents=True, exist_ok=True)
    reviews_dir.mkdir(parents=True, exist_ok=True)

    preferred_role = str(slice_data.get("multi_agent_role", "")).strip()
    preferred_config_file = str(slice_data.get("multi_agent_config_file", "")).strip()
    ticket_title = str(ticket.get("title", "")).strip()
    ticket_goal = str(ticket.get("goal", "")).strip()
    objective = str(slice_data.get("objective", ticket_goal or "Unspecified objective")).strip()
    problem_statement = str(
        slice_data.get("problem_statement", ticket.get("problem_statement", objective))
    ).strip() or objective
    task_summary = str(slice_data.get("task_summary", objective)).strip() or objective
    expected_outcome = str(
        slice_data.get(
            "expected_outcome",
            ticket.get("expected_outcome", "Required checks pass and acceptance criteria are satisfied."),
        )
    ).strip()
    current_failure = str(
        slice_data.get("current_failure", ticket.get("current_failure", problem_statement))
    ).strip() or problem_statement
    expected_behavior = str(
        slice_data.get("expected_behavior", ticket.get("expected_behavior", expected_outcome))
    ).strip() or expected_outcome

    def _normalize_lines(value: Any) -> list[str]:
        if isinstance(value, list):
            return [str(x).strip() for x in value if str(x).strip()]
        text = str(value or "").strip()
        return [text] if text else []

    acceptance_criteria = [
        str(x).strip()
        for x in slice_data.get("acceptance_criteria", ticket.get("acceptance_criteria", []))
        if str(x).strip()
    ]
    non_goals = [
        str(x).strip()
        for x in slice_data.get("non_goals", ticket.get("non_goals", []))
        if str(x).strip()
    ]
    constraints = _normalize_lines(slice_data.get("constraints", ticket.get("constraints", [])))
    if not constraints:
        constraints = [
            "Stay inside assigned slice scope and write boundary.",
            "Do not break existing workflows tied to this module or contract surface.",
            "Do not alter external API/event/schema contracts unless evidence and reviewer approval justify it.",
            "Preserve auditability, authorization boundaries, and accounting invariants where applicable.",
        ]
    safe_assumptions = _normalize_lines(
        slice_data.get("safe_assumptions", ticket.get("safe_assumptions", []))
    )
    if not safe_assumptions:
        safe_assumptions = [
            "Assigned branch/worktree and claim record are the only valid implementation coordinates.",
            "Other slices may progress in parallel; coordinate via declared contracts, not cross-scope edits.",
        ]
    assumptions_to_validate = _normalize_lines(
        slice_data.get("assumptions_to_validate", ticket.get("assumptions_to_validate", []))
    )
    if not assumptions_to_validate:
        assumptions_to_validate = [
            "Whether the root cause is local to this module or originates from an upstream dependency.",
            "Whether upstream/downstream API, event, and schema contracts remain backward compatible.",
            "Whether the fix preserves failure handling, idempotency, and observability under retries.",
        ]
    base_branch_ref = str(ticket.get("base_branch", "")).strip()
    read_only_base_branches = {"harness-engineering-orchestrator", "main", "master"}
    if base_branch_ref:
        read_only_base_branches.add(base_branch_ref)
        if base_branch_ref.startswith("origin/"):
            read_only_base_branches.add(base_branch_ref.split("/", 1)[1])
    read_only_base_branches = sorted(read_only_base_branches)
    read_only_display = ", ".join(f"`{b}`" for b in read_only_base_branches)
    role_sequence = [
        str(x).strip()
        for x in ticket.get("mandatory_spawn_role_sequence", [])
        if str(x).strip()
    ]
    role_sequence_display = (
        " -> ".join(role_sequence)
        if role_sequence
        else "planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops"
    )

    prompt_lines = [
        f"You are `{slice_data['primary_agent']}`.",
        f"Start your first line with: `I am {slice_data['primary_agent']} and I own {sid}.`",
        f"Ticket title: {ticket_title or 'unspecified'}",
        f"Feature objective: {objective}",
        f"Current failure: {current_failure}",
        f"Expected behavior: {expected_behavior}",
        f"Task scope summary: {task_summary}",
        "",
        "Your responsibility:",
        f"- Own all implementation decisions inside this slice boundary: `{sid}`.",
        "- Diagnose root cause inside scope before patching.",
        "- Validate whether failure is local or upstream/downstream.",
        "- If dependency/contract issue is upstream, report evidence before changing contracts.",
        "- Restore system integrity inside this boundary, not just local compilation.",
        "",
        "Constraints:",
    ]
    for item in constraints:
        prompt_lines.append(f"- {item}")
    prompt_lines.extend(
        [
            "",
            "Assumptions that are safe:",
        ]
    )
    for item in safe_assumptions:
        prompt_lines.append(f"- {item}")
    prompt_lines.extend(
        [
            "",
            "Assumptions that must be validated:",
        ]
    )
    for item in assumptions_to_validate:
        prompt_lines.append(f"- {item}")
    prompt_lines.extend(
        [
            "",
            "Execution minimum:",
            f"- validate current branch is `{slice_data['branch']}` and working directory is `{slice_data['worktree_path']}`",
            f"- treat base branches as read-only for implementation: {read_only_display}",
            "- confirm claim evidence exists in ticket.yaml + TIMELINE.md before edits",
            "- diagnose current behavior in the requested focus paths",
            "- perform codebase impact analysis (upstream dependencies, downstream consumers, contracts/events/APIs)",
            "- implement root-cause fix in allowed scope with clear rationale",
            "- add/adjust tests that prove acceptance criteria and no-regression behavior",
            "- run required checks and report evidence",
            "",
            "Required output:",
            "- identity",
            "- root_cause_analysis",
            "- change_summary",
            "- files_changed",
            "- commands_run",
            "- harness_results",
            "- residual_risks",
            "- cross_module_coordination_required",
            "- blockers_or_next_step",
            "- ticket_claim_evidence",
            "- worktree_validation",
            "- codebase_impact_analysis",
            "",
            "You are not here to patch blindly.",
            "You are here to restore system integrity within your boundary.",
        ]
    )
    if preferred_role:
        prompt_lines.insert(
            2,
            (
                f"Use Codex custom multi-agent role `{preferred_role}`"
                + (f" from `{preferred_config_file}`." if preferred_config_file else ".")
            ),
        )

    packet = f"""# Task Packet

Ticket: `{tid}`
Slice: `{sid}`
Primary Agent: `{slice_data['primary_agent']}`
Reviewers: `{', '.join(slice_data.get('reviewers', [])) or 'none'}`
Lane: `{slice_data['lane']}`
Branch: `{slice_data['branch']}`
Worktree: `{slice_data['worktree_path']}`

## Ticket Context
- title: {ticket_title or 'unspecified'}
- goal: {ticket_goal or 'unspecified'}

## Problem Statement
{problem_statement}

## Task To Solve
- {task_summary}
- Expected outcome: {expected_outcome}

## Objective
{objective}

"""
    packet += "## Delegation Context (Invariant-First)\n"
    packet += f"- Feature objective: {objective}\n"
    packet += f"- Current failure: {current_failure}\n"
    packet += f"- Expected behavior: {expected_behavior}\n"
    packet += "- Delegation model: own the module boundary and restore system integrity; avoid file-level micromanagement.\n"
    packet += "- If upstream contract break is detected, provide evidence and coordination request before contract edits.\n"

    packet += "\n## Constraints\n"
    for item in constraints:
        packet += f"- {item}\n"

    packet += "\n## Assumptions\n"
    packet += "- Safe assumptions:\n"
    for item in safe_assumptions:
        packet += f"  - {item}\n"
    packet += "- Assumptions to validate:\n"
    for item in assumptions_to_validate:
        packet += f"  - {item}\n"

    if preferred_role:
        packet += "## Custom Multi-Agent Role (Codex)\n"
        packet += f"- role: `{preferred_role}`\n"
        if preferred_config_file:
            packet += f"- config_file: `{preferred_config_file}`\n"
        packet += "- runtime_profile: `resolved at runtime from role config`\n"
        packet += "\n"

    packet += """## Agent Write Boundary (Enforced)
"""
    for p in slice_data.get("allowed_scope_paths", []):
        packet += f"- `{p}`\n"

    packet += "\n## Requested Focus Paths\n"
    for p in slice_data.get("scope_paths", []):
        packet += f"- `{p}`\n"

    packet += "\n## Ticket-First Gate (Blocking)\n"
    packet += f"- Assigned branch: `{slice_data['branch']}`\n"
    packet += f"- Assigned worktree: `{slice_data['worktree_path']}`\n"
    packet += f"- Base branches are read-only for implementation: {read_only_display}\n"
    packet += "- Claim evidence must be recorded in `ticket.yaml` and `TIMELINE.md` before edits.\n"
    packet += "- If any gate fails, stop and report blocker instead of patching.\n"
    packet += f"- Mandatory orchestrator delegation sequence: `{role_sequence_display}`.\n"

    if acceptance_criteria:
        packet += "\n## Acceptance Criteria\n"
        for item in acceptance_criteria:
            packet += f"- {item}\n"
    else:
        packet += "\n## Acceptance Criteria\n"
        packet += "- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.\n"

    if non_goals:
        packet += "\n## Non-Goals\n"
        for item in non_goals:
            packet += f"- {item}\n"

    upstream_slices = [str(x).strip() for x in slice_data.get("workflow_upstream_slices", []) if str(x).strip()]
    downstream_slices = [str(x).strip() for x in slice_data.get("workflow_downstream_slices", []) if str(x).strip()]
    ext_up_agents = [str(x).strip() for x in slice_data.get("workflow_external_upstream_agents", []) if str(x).strip()]
    ext_down_agents = [str(x).strip() for x in slice_data.get("workflow_external_downstream_agents", []) if str(x).strip()]
    contracts = [c for c in slice_data.get("cross_workflow_contracts", []) if isinstance(c, dict)]

    if upstream_slices or downstream_slices or ext_up_agents or ext_down_agents or contracts:
        packet += "\n## Cross-Workflow Dependencies\n"
        packet += f"- Upstream slices: {', '.join(upstream_slices) if upstream_slices else 'none'}\n"
        packet += f"- Downstream slices: {', '.join(downstream_slices) if downstream_slices else 'none'}\n"
        packet += f"- External upstream agents to watch: {', '.join(ext_up_agents) if ext_up_agents else 'none'}\n"
        packet += f"- External downstream agents to watch: {', '.join(ext_down_agents) if ext_down_agents else 'none'}\n"
        if contracts:
            packet += "- Contract edges:\n"
            for c in contracts:
                relation = str(c.get("relation", "edge"))
                agent = str(c.get("agent", "unknown"))
                contract = str(c.get("contract", ""))
                linked_sid = str(c.get("slice_id", "")).strip()
                suffix = f" (slice {linked_sid})" if linked_sid else ""
                packet += f"  - {relation} -> {agent}{suffix}: {contract}\n"

    packet += "\n## Required Checks Before Done\n"
    checks = slice_data.get("required_checks", [])
    if checks:
        for c in checks:
            packet += f"- `{c}`\n"
    else:
        packet += "- none (recon/doc only)\n"

    packet += "\n## Reviewer Contract\n"
    packet += "- Review-only agents do not commit code.\n"
    packet += "- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.\n"
    packet += "- Mark review status as `approved` only with concrete evidence.\n"
    packet += "\n## Testing Responsibility Split\n"
    packet += "- Implementation agents own targeted tests for changed behavior in-slice.\n"
    packet += "- `merge-specialist` owns integration merge/conflict evidence before code-review phase.\n"
    packet += "- `qa-reliability` owns cross-workflow regression, gate evidence, and release-readiness signal.\n"
    packet += "- `release-ops` owns docs/release evidence sync before final merge.\n"
    packet += "\n## Agent Identity Contract\n"
    packet += f"- First output line must be: `I am {slice_data['primary_agent']} and I own {sid}.`\n"
    packet += "\n## Required Output Contract\n"
    packet += "- root_cause_analysis\n"
    packet += "- change_summary\n"
    packet += "- files_changed\n"
    packet += "- commands_run\n"
    packet += "- harness_results\n"
    packet += "- residual_risks\n"
    packet += "- cross_module_coordination_required\n"
    packet += "- blockers_or_next_step\n"
    packet += "- ticket_claim_evidence\n"
    packet += "- worktree_validation\n"
    packet += "- codebase_impact_analysis:\n"
    packet += "  - upstream dependencies/contracts touched\n"
    packet += "  - downstream modules/portals at risk\n"
    packet += "  - API/event/schema/test surface changed or intentionally unchanged\n"
    packet += "\n## Shipability Bar\n"
    packet += "- The patch must be minimal, deterministic, and test-backed.\n"
    packet += "- Do not change behavior outside explicit scope without evidence and rationale.\n"
    packet += "- If any safety invariant is uncertain, fail closed and document blocker with evidence.\n"

    packet += "\n## Agent Prompt (Copy/Paste)\n```text\n"
    packet += "\n".join(prompt_lines)
    packet += "\n```\n"

    (slice_dir / "TASK_PACKET.md").write_text(packet, encoding="utf-8")

    for reviewer in slice_data.get("reviewers", []):
        review_file = reviews_dir / f"{reviewer}.md"
        if review_file.exists():
            continue
        review_file.write_text(
            f"# Review Evidence\n\n"
            f"ticket: {tid}\n"
            f"slice: {sid}\n"
            f"reviewer: {reviewer}\n"
            f"status: pending\n\n"
            f"## Findings\n"
            f"- pending\n\n"
            f"## Evidence\n"
            f"- commands: pending\n"
            f"- artifacts: pending\n",
            encoding="utf-8",
        )

    # Also mirror packet into each worktree for direct agent access.
    wt = Path(str(slice_data["worktree_path"]))
    harness_dir = wt / ".harness"
    harness_dir.mkdir(parents=True, exist_ok=True)
    (harness_dir / "TASK_PACKET.md").write_text(packet, encoding="utf-8")


def write_ticket_summary(repo_root: Path, ticket: dict[str, Any]) -> None:
    tid = str(ticket["ticket_id"])
    path = ticket_dir(repo_root, tid) / "SUMMARY.md"

    lines = [
        f"# Ticket {tid}",
        "",
        f"- title: {ticket.get('title', '')}",
        f"- goal: {ticket.get('goal', '')}",
        f"- priority: {ticket.get('priority', '')}",
        f"- status: {ticket.get('status', '')}",
        f"- base_branch: {ticket.get('base_branch', '')}",
        f"- created_at: {ticket.get('created_at', '')}",
        f"- updated_at: {ticket.get('updated_at', '')}",
        "",
        "## Slice Board",
        "",
        "| Slice | Agent | Lane | Status | Branch |",
        "| --- | --- | --- | --- | --- |",
    ]

    for s in ticket.get("slices", []):
        lines.append(
            f"| {s.get('id')} | {s.get('primary_agent')} | {s.get('lane')} | {s.get('status')} | `{s.get('branch')}` |"
        )

    lines.extend(
        [
            "",
            "## Operator Commands",
            "",
            "Read cross-workflow dependency plan:",
            f"`cat {ticket_dir(repo_root, tid) / 'CROSS_WORKFLOW_PLAN.md'}`",
            "",
            "Generate tmux launch block:",
            f"`python3 scripts/harness_orchestrator.py dispatch --ticket-id {tid}`",
            "",
            "Verify / readiness pass:",
            f"`python3 scripts/harness_orchestrator.py verify --ticket-id {tid}`",
            "",
            "Verify + merge eligible slices:",
            f"`python3 scripts/harness_orchestrator.py verify --ticket-id {tid} --merge`",
            "",
            "Verify + merge + cleanup worktrees:",
            f"`python3 scripts/harness_orchestrator.py verify --ticket-id {tid} --merge --cleanup-worktrees`",
        ]
    )

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_tmux_commands(repo_root: Path, ticket: dict[str, Any]) -> str:
    tid = str(ticket["ticket_id"])
    command_dir = ticket_dir(repo_root, tid) / "commands"
    command_dir.mkdir(parents=True, exist_ok=True)

    lines = [
        "#!/usr/bin/env bash",
        "set -euo pipefail",
        "",
        f"# Ticket {tid}",
    ]

    human_lines = [
        f"Ticket `{tid}` tmux launch commands:",
        "",
    ]

    for s in ticket.get("slices", []):
        lane = str(s.get("lane"))
        wt = str(s.get("worktree_path"))
        sid = str(s.get("id"))
        agent = str(s.get("primary_agent"))
        hint_cmd = 'printf "\\n# Paste TASK_PACKET prompt into the assigned agent CLI in this lane.\\n"'

        cmds = [
            f"tmux send-keys -t {shlex.quote(lane)} {shlex.quote(f'cd {wt}')} Enter",
            f"tmux send-keys -t {shlex.quote(lane)} {shlex.quote('cat .harness/TASK_PACKET.md')} Enter",
            f"tmux send-keys -t {shlex.quote(lane)} {shlex.quote(hint_cmd)} Enter",
        ]
        lines.extend(cmds)
        lines.append("")

        human_lines.append(f"- Slice `{sid}` ({agent}) on lane `{lane}`")
        human_lines.append(f"  - `cd {wt}`")
        human_lines.append("  - `cat .harness/TASK_PACKET.md`")

    script_path = command_dir / "tmux-launch.sh"
    script_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.chmod(script_path, 0o755)

    attach_path = command_dir / "tmux-attach.sh"
    attach_lines = [
        "#!/usr/bin/env bash",
        "set -euo pipefail",
        "",
        "tmux ls",
    ]
    for s in ticket.get("slices", []):
        lane = str(s.get("lane"))
        attach_lines.append(f"echo 'attach: tmux attach -t {lane}'")
    attach_path.write_text("\n".join(attach_lines) + "\n", encoding="utf-8")
    os.chmod(attach_path, 0o755)

    return "\n".join(human_lines)


def create_ticket(args: argparse.Namespace) -> int:
    repo_root = project_root()
    orchestrator_layer, catalog, agent_defs = load_contracts(repo_root)

    scope_paths = parse_paths(args.paths, args.path)
    requested_base_branch = args.base_branch
    base_branch = resolve_base_ref(repo_root, requested_base_branch)
    worktree_root = Path(args.worktree_root).expanduser() if args.worktree_root else default_worktree_root(repo_root)
    lanes = [x.strip() for x in args.tmux_lanes.split(",") if x.strip()] if args.tmux_lanes else default_tmux_lanes(orchestrator_layer)
    if not lanes:
        raise RuntimeError("no tmux lanes configured")

    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    ticket_id = args.ticket_id or f"TKT-{ts}-{slugify(args.title)[:24]}"
    tdir = ticket_dir(repo_root, ticket_id)
    if tdir.exists():
        raise RuntimeError(f"ticket already exists: {tdir}")

    grouped: dict[str, dict[str, Any]] = {}
    for p in scope_paths:
        route = resolve_route(orchestrator_layer, p)
        primary = route["primary_agent"]
        if not primary:
            primary = "refactor-techdebt-gc"

        g = grouped.setdefault(
            primary,
            {
                "scope_paths": set(),
                "reviewers": set(),
                "matched_rules": set(),
            },
        )
        g["scope_paths"].add(p)
        g["reviewers"].update(route["reviewers"])
        g["matched_rules"].add(route["rule_path"])

    high_risk_reviewers = reviewers_high_risk(orchestrator_layer)
    dispatch_protocol = orchestrator_layer.get("dispatch_protocol", {})
    review_pipeline = orchestrator_layer.get("review_pipeline", {})
    require_qa_non_doc = bool(
        dispatch_protocol.get("require_qa_reliability_for_non_doc_slices", False)
        or review_pipeline.get("require_qa_reliability_for_non_doc_slices", False)
    )
    require_release_ops_docs_sync = bool(
        dispatch_protocol.get("require_release_ops_docs_sync_before_merge", False)
        or review_pipeline.get("require_release_ops_for_non_doc_docs_sync", False)
    )
    require_code_reviewer_non_doc = bool(
        dispatch_protocol.get("require_code_reviewer_for_non_doc_slices", False)
        or review_pipeline.get("require_code_reviewer_for_non_doc_slices", False)
    )
    require_merge_specialist_non_doc = bool(
        dispatch_protocol.get("require_merge_specialist_for_non_doc_slices", False)
        or review_pipeline.get("require_merge_specialist_for_non_doc_slices", False)
    )
    require_merge_before_code_review = bool(
        dispatch_protocol.get("require_merge_specialist_before_code_review", False)
        or review_pipeline.get("require_merge_specialist_before_code_review", False)
    )
    require_reviewer_head_sha_match = bool(
        dispatch_protocol.get("require_reviewer_head_sha_match", False)
        or review_pipeline.get("require_reviewer_head_sha_match", False)
    )
    require_qa_after_code_review_timestamp = bool(
        dispatch_protocol.get("require_qa_after_code_review_timestamp", False)
        or review_pipeline.get("require_qa_after_code_review_timestamp", False)
    )
    enforce_changed_file_docs_only_classification = bool(
        dispatch_protocol.get("enforce_changed_file_docs_only_classification", False)
        or review_pipeline.get("enforce_changed_file_docs_only_classification", False)
    )
    mandatory_role_sequence = [
        str(x).strip()
        for x in dispatch_protocol.get("mandatory_spawn_role_sequence", [])
        if str(x).strip()
    ]

    sorted_agents = sorted(
        grouped.keys(),
        key=lambda a: (
            RISK_ORDER.get(risk_for_agent(catalog, a), 99),
            a,
        ),
    )

    created_stamp = utc_now()
    slices: list[dict[str, Any]] = []
    for idx, agent_id in enumerate(sorted_agents, start=1):
        lane = lanes[(idx - 1) % len(lanes)]
        group = grouped[agent_id]

        reviewers = set(group["reviewers"])
        risk_tier = risk_for_agent(catalog, agent_id)
        if risk_tier in {"high", "critical"}:
            reviewers.update(high_risk_reviewers)
        scope_paths_for_slice = sorted(group["scope_paths"])
        if not scope_paths_for_slice:
            raise RuntimeError(f"planned slice for agent '{agent_id}' has empty scope paths")
        docs_only_slice = is_docs_only_scope(scope_paths_for_slice)
        if require_merge_specialist_non_doc and not docs_only_slice:
            reviewers.add("merge-specialist")
        if require_code_reviewer_non_doc and not docs_only_slice:
            reviewers.add("code_reviewer")
        if require_qa_non_doc and not docs_only_slice:
            reviewers.add("qa-reliability")
        if require_release_ops_docs_sync and not docs_only_slice:
            reviewers.add("release-ops")
        reviewers.discard(agent_id)

        branch = f"tickets/{ticket_id.lower()}/{agent_id}"
        wt = worktree_root / ticket_id / agent_id

        required_checks = []
        agent_def = agent_defs.get(agent_id, {})
        allowed_scope_paths = [str(x) for x in agent_def.get("scope_paths", []) if str(x).strip()]
        role_profile = resolve_multi_agent_role_profile(orchestrator_layer, agent_id)
        for check in agent_def.get("required_checks_before_done", []):
            required_checks.append(str(check))

        scope_text = ", ".join(scope_paths_for_slice)
        task_summary = f"Implement ticket goal within explicit scope paths: {scope_text}"
        acceptance_criteria = [
            f"Changes are limited to declared scope paths for {agent_id}.",
            f"Deliver objective for: {scope_text}.",
            "Targeted tests for changed behavior are added/updated and passing.",
        ]

        slices.append(
            {
                "id": f"SLICE-{idx:02d}",
                "primary_agent": agent_id,
                "reviewers": sorted(reviewers),
                "scope_paths": scope_paths_for_slice,
                "allowed_scope_paths": allowed_scope_paths,
                "matched_rules": sorted(group["matched_rules"]),
                "lane": lane,
                "branch": branch,
                "worktree_path": str(wt),
                "required_checks": required_checks,
                "multi_agent_role": role_profile.get("role", ""),
                "multi_agent_config_file": role_profile.get("config_file", ""),
                "multi_agent_model": role_profile.get("model", ""),
                "multi_agent_reasoning": role_profile.get("reasoning", ""),
                "multi_agent_fallback": role_profile.get("fallback", []),
                "status": "ready",
                "status_history": [
                    {
                        "from_status": "",
                        "to_status": "ready",
                        "at_utc": created_stamp,
                        "reason": "slice_planned",
                        "actor": "orchestrator",
                    }
                ],
                "objective": args.goal,
                "task_summary": task_summary,
                "problem_statement": f"Implement ticket objective for scoped ownership paths: {scope_text}",
                "acceptance_criteria": acceptance_criteria,
            }
        )

    ticket = {
        "ticket_id": ticket_id,
        "title": args.title,
        "goal": args.goal,
        "priority": args.priority,
        "status": "planned",
        "base_branch": base_branch,
        "requested_base_branch": requested_base_branch,
        "worktree_root": str(worktree_root),
        "created_at": created_stamp,
        "updated_at": created_stamp,
        "r3_required": True,
        "orchestrator_authority": {"r1": "orchestrator", "r2": "orchestrator", "r3": "human"},
        "mandatory_spawn_role_sequence": mandatory_role_sequence,
        "status_history": [
            {
                "from_status": "",
                "to_status": "planned",
                "at_utc": created_stamp,
                "reason": "ticket_created",
                "actor": "orchestrator",
            }
        ],
        "review_enforcement": {
            "require_merge_specialist_for_non_doc_slices": require_merge_specialist_non_doc,
            "require_code_reviewer_for_non_doc_slices": require_code_reviewer_non_doc,
            "require_qa_reliability_for_non_doc_slices": require_qa_non_doc,
            "require_release_ops_docs_sync_before_merge": require_release_ops_docs_sync,
            "require_merge_specialist_before_code_review": require_merge_before_code_review,
            "require_reviewer_head_sha_match": require_reviewer_head_sha_match,
            "require_qa_after_code_review_timestamp": require_qa_after_code_review_timestamp,
            "enforce_changed_file_docs_only_classification": enforce_changed_file_docs_only_classification,
        },
        "slices": slices,
    }
    annotate_ticket_workflow(ticket)

    if args.create_worktrees:
        for s in slices:
            ensure_worktree(repo_root, Path(str(s["worktree_path"])), str(s["branch"]), base_branch)

    save_yaml(ticket_file(repo_root, ticket_id), ticket)
    for s in slices:
        write_packet_files(repo_root, ticket, s)

    append_timeline_event(
        repo_root,
        ticket_id,
        "ticket_created",
        "ticket created and slices planned",
        {"ticket_id": ticket_id, "ticket_status": "planned", "slice_count": len(slices)},
        at_utc=created_stamp,
    )
    write_cross_workflow_plan(repo_root, ticket)
    write_ticket_summary(repo_root, ticket)
    block = write_tmux_commands(repo_root, ticket)

    print(f"[harness] created ticket: {ticket_id}")
    print(f"[harness] slices: {len(slices)}")
    print(f"[harness] ticket file: {ticket_file(repo_root, ticket_id)}")
    print(f"[harness] launch script: {ticket_dir(repo_root, ticket_id) / 'commands' / 'tmux-launch.sh'}")
    if base_branch != requested_base_branch:
        print(f"[harness] base branch resolved: requested={requested_base_branch} resolved={base_branch}")
    print()
    print(block)
    return 0


def dispatch_ticket(args: argparse.Namespace) -> int:
    repo_root = project_root()
    ticket = read_ticket(repo_root, args.ticket_id)
    annotate_ticket_workflow(ticket)

    if args.create_missing_worktrees:
        for s in ticket.get("slices", []):
            ensure_worktree(
                repo_root,
                Path(str(s["worktree_path"])),
                str(s["branch"]),
                str(ticket.get("base_branch")),
            )
        write_ticket(repo_root, ticket)

    for s in ticket.get("slices", []):
        write_packet_files(repo_root, ticket, s)
    block = write_tmux_commands(repo_root, ticket)
    write_cross_workflow_plan(repo_root, ticket)
    append_timeline_event(
        repo_root,
        str(ticket["ticket_id"]),
        "dispatch_regenerated",
        "dispatch command block regenerated",
        {"ticket_id": str(ticket["ticket_id"])},
    )
    write_ticket_summary(repo_root, ticket)

    print(block)
    print()
    print(f"Run: bash {ticket_dir(repo_root, str(ticket['ticket_id'])) / 'commands' / 'tmux-launch.sh'}")
    return 0


def set_review_status(args: argparse.Namespace) -> int:
    repo_root = project_root()
    ticket = read_ticket(repo_root, args.ticket_id)

    target_slice = None
    for s in ticket.get("slices", []):
        if str(s.get("id")) == args.slice_id:
            target_slice = s
            break
    if not target_slice:
        raise RuntimeError(f"slice not found: {args.slice_id}")

    review_file = ticket_dir(repo_root, args.ticket_id) / "slices" / args.slice_id / "reviews" / f"{args.reviewer}.md"
    review_file.parent.mkdir(parents=True, exist_ok=True)
    review_head_sha = str(args.head_sha or "").strip() or branch_head_sha(repo_root, str(target_slice.get("branch", "")))
    reviewed_at = utc_now()

    body = [
        "# Review Evidence",
        "",
        f"ticket: {args.ticket_id}",
        f"slice: {args.slice_id}",
        f"reviewer: {args.reviewer}",
        f"status: {args.status}",
        f"reviewed_at_utc: {reviewed_at}",
        f"reviewed_head_sha: {review_head_sha or 'unknown'}",
        "",
        "## Findings",
        f"- {args.findings or 'none'}",
        "",
        "## Evidence",
        f"- commands: {args.commands or 'unspecified'}",
        f"- artifacts: {args.artifacts or 'unspecified'}",
    ]
    review_file.write_text("\n".join(body) + "\n", encoding="utf-8")

    append_timeline_event(
        repo_root,
        args.ticket_id,
        "review_updated",
        f"review updated: {args.slice_id} {args.reviewer} -> {args.status}",
        {
            "ticket_id": args.ticket_id,
            "slice_id": args.slice_id,
            "reviewer": args.reviewer,
            "status": args.status,
            "reviewed_at_utc": reviewed_at,
            "reviewed_head_sha": review_head_sha or "unknown",
        },
        at_utc=reviewed_at,
    )
    print(f"[harness] review saved: {review_file}")
    return 0


def claim_slice(args: argparse.Namespace) -> int:
    repo_root = project_root()
    orchestrator_layer, _, _ = load_contracts(repo_root)
    policy = claim_contract(orchestrator_layer)
    ticket = read_ticket(repo_root, args.ticket_id)

    target_slice = None
    for s in ticket.get("slices", []):
        if str(s.get("id", "")).strip() == args.slice_id:
            target_slice = s
            break
    if not target_slice:
        raise RuntimeError(f"slice not found: {args.slice_id}")

    sid = str(target_slice.get("id", "")).strip()
    primary_agent = str(target_slice.get("primary_agent", "")).strip()
    if not primary_agent:
        raise RuntimeError(f"slice has no primary_agent: {sid}")

    agent_id = str(args.agent_id or "").strip() or primary_agent
    if agent_id != primary_agent:
        raise RuntimeError(
            f"claim agent_id '{agent_id}' does not match slice primary_agent '{primary_agent}' for {sid}"
        )

    existing_claim = target_slice.get("claim")
    if isinstance(existing_claim, dict) and policy["prevent_dual_claims_for_same_slice"]:
        existing_agent = str(existing_claim.get("agent_id", "")).strip()
        if existing_agent and existing_agent != agent_id:
            raise RuntimeError(
                f"slice {sid} already claimed by '{existing_agent}' and dual claims are blocked by policy"
            )

    claimed_at = str(args.claimed_at_utc or "").strip() or utc_now()
    allowed_transitions = policy["allowed_transitions"]
    strict_transitions = bool(allowed_transitions)
    current_status = str(target_slice.get("status", "")).strip().lower() or "ready"
    requested_claim_status = str(args.claim_status or "").strip().lower() or str(policy["claim_status"])
    if current_status == "in_progress" and requested_claim_status == "taken":
        requested_claim_status = "in_progress"

    status_chain = apply_claim_transition_policy(
        target_slice,
        requested_claim_status=requested_claim_status,
        auto_in_progress=bool(args.auto_in_progress),
        allowed_transitions=allowed_transitions,
        strict_transition_check=strict_transitions,
        actor=agent_id,
        at_utc=claimed_at,
    )

    slice_branch = str(target_slice.get("branch", "")).strip()
    slice_worktree_path = str(target_slice.get("worktree_path", "")).strip()
    blocker_id = str(args.blocker_id or "").strip().upper() or blocker_id_from_branch(slice_branch)
    claim_branch = slice_branch
    claim_worktree_path = slice_worktree_path
    expected_branch_label = "slice branch"
    expected_worktree_label = "slice worktree_path"

    if blocker_id:
        matrix_coords = blocker_matrix_coordinates(repo_root, args.ticket_id, blocker_id)
        if matrix_coords:
            claim_branch, claim_worktree_path = matrix_coords
            expected_branch_label = "blocker matrix branch"
            expected_worktree_label = "blocker matrix worktree_path"

    claim: dict[str, Any] = {
        "agent_id": agent_id,
        "slice_id": sid,
        "branch": claim_branch,
        "worktree_path": claim_worktree_path,
        "claimed_at_utc": claimed_at,
    }
    if blocker_id:
        claim["blocker_id"] = blocker_id
    target_slice["claim"] = claim

    _, field_errors = validate_claim_fields(
        target_slice,
        policy["required_fields"],
        expected_branch=claim_branch,
        expected_worktree_path=claim_worktree_path,
        expected_branch_label=expected_branch_label,
        expected_worktree_label=expected_worktree_label,
    )
    if field_errors:
        raise RuntimeError(f"claim artifacts incomplete for {sid}: {', '.join(field_errors)}")

    write_ticket(repo_root, ticket)

    chain_text = " -> ".join(status_chain)
    summary = (
        f"claim recorded: `{agent_id}` took `{sid}` on branch `{claim_branch}` at `{claim_worktree_path}` "
        f"(`{chain_text}`)"
    )
    payload: dict[str, Any] = {
        "ticket_id": args.ticket_id,
        "slice_id": sid,
        "agent_id": agent_id,
        "branch": claim_branch,
        "worktree_path": claim_worktree_path,
        "claimed_at_utc": claimed_at,
        "status_chain": status_chain,
        "slice_status": str(target_slice.get("status", "")).strip(),
    }
    if blocker_id:
        payload["blocker_id"] = blocker_id

    append_timeline_event(
        repo_root,
        args.ticket_id,
        "claim_recorded",
        summary,
        payload,
        at_utc=claimed_at,
    )

    print(f"[harness] claim saved for {sid} ({agent_id})")
    print(f"[harness] slice status: {target_slice.get('status')}")
    if blocker_id:
        print(f"[harness] blocker_id: {blocker_id}")
    return 0


def resolve_branch_ref(repo_root: Path, branch: str) -> str:
    ref = branch.strip()
    if not ref:
        return ref
    if not branch_exists(repo_root, ref) and remote_branch_exists(repo_root, ref):
        return f"origin/{ref}"
    return ref


def branch_head_sha(repo_root: Path, branch: str) -> str:
    ref = resolve_branch_ref(repo_root, branch)
    if not ref:
        return ""
    proc = run(["git", "rev-parse", ref], cwd=repo_root, check=False)
    if proc.returncode != 0:
        return ""
    return proc.stdout.strip()


def count_ahead(repo_root: Path, base_branch: str, branch: str) -> int:
    ref = resolve_branch_ref(repo_root, branch)

    proc = run(["git", "rev-list", "--count", f"{base_branch}..{ref}"], cwd=repo_root, check=False)
    if proc.returncode != 0:
        return 0
    out = proc.stdout.strip()
    return int(out) if out.isdigit() else 0


def changed_files(repo_root: Path, base_branch: str, branch: str) -> list[str]:
    ref = resolve_branch_ref(repo_root, branch)

    # Use merge-base diff so we only attribute files changed by the slice branch.
    # This avoids false scope/overlap violations when base branch moves forward
    # after ticket dispatch (base-only changes should not appear in slice delta).
    proc = run(["git", "diff", "--name-only", f"{base_branch}...{ref}"], cwd=repo_root, check=False)
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def out_of_scope_files(files: list[str], allowed_scopes: list[str]) -> list[str]:
    if not allowed_scopes:
        return files

    violations = []
    for f in files:
        if not any(path_in_allowed_scope(f, scope) for scope in allowed_scopes):
            violations.append(f)
    return sorted(violations)


def cross_slice_overlaps(
    slice_changed: dict[str, set[str]],
    slice_agents: dict[str, str],
) -> dict[str, list[tuple[str, str]]]:
    overlap_map: dict[str, list[tuple[str, str]]] = {sid: [] for sid in slice_changed}
    ids = sorted(slice_changed.keys())
    for i, sid_a in enumerate(ids):
        for sid_b in ids[i + 1 :]:
            if slice_agents.get(sid_a) == slice_agents.get(sid_b):
                continue
            overlap = sorted(slice_changed[sid_a].intersection(slice_changed[sid_b]))
            if not overlap:
                continue
            for f in overlap:
                overlap_map[sid_a].append((sid_b, f))
                overlap_map[sid_b].append((sid_a, f))
    return overlap_map


def parse_review_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    values: dict[str, str] = {}
    text = path.read_text(encoding="utf-8", errors="replace")
    for raw in text.splitlines():
        line = raw.strip()
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        k = key.strip().lower()
        if not k:
            continue
        if k in {"ticket", "slice", "reviewer", "status", "reviewed_at_utc", "reviewed_head_sha"}:
            values[k] = value.strip()
    return values


def parse_reviewed_at(value: str) -> dt.datetime | None:
    if not value:
        return None
    try:
        norm = value.strip()
        if norm.endswith("Z"):
            norm = norm[:-1] + "+00:00"
        parsed = dt.datetime.fromisoformat(norm)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc)
    except ValueError:
        return None


def reviewer_approved(path: Path) -> bool:
    return parse_review_file(path).get("status", "").strip().lower() == "approved"


def ensure_base_checked_out(repo_root: Path, base_branch: str, allow_dirty: bool) -> None:
    if not allow_dirty:
        st = run(["git", "status", "--porcelain"], cwd=repo_root, check=False)
        if st.stdout.strip():
            raise RuntimeError("working tree is dirty; commit/stash before merge or pass --allow-dirty")
    checkout = run(["git", "checkout", base_branch], cwd=repo_root, check=False)
    if checkout.returncode != 0:
        raise RuntimeError(f"cannot checkout base branch {base_branch}: {checkout.stderr.strip()}")


def verify_ticket(args: argparse.Namespace) -> int:
    repo_root = project_root()
    orchestrator_layer, _, _ = load_contracts(repo_root)
    ticket = read_ticket(repo_root, args.ticket_id)
    annotate_ticket_workflow(ticket)
    base_branch = str(ticket.get("base_branch"))
    cleanup_default = bool(orchestrator_layer.get("automation", {}).get("cleanup_worktrees_on_merge", False))
    cleanup_enabled = cleanup_default if args.cleanup_worktrees is None else bool(args.cleanup_worktrees)
    dispatch_protocol = orchestrator_layer.get("dispatch_protocol", {})
    review_pipeline = orchestrator_layer.get("review_pipeline", {})
    ticket_review_enforcement = ticket.get("review_enforcement", {})
    require_merge_specialist_non_doc = bool(
        ticket_review_enforcement.get("require_merge_specialist_for_non_doc_slices", False)
        or dispatch_protocol.get("require_merge_specialist_for_non_doc_slices", False)
        or review_pipeline.get("require_merge_specialist_for_non_doc_slices", False)
    )
    require_code_reviewer_non_doc = bool(
        ticket_review_enforcement.get("require_code_reviewer_for_non_doc_slices", False)
        or dispatch_protocol.get("require_code_reviewer_for_non_doc_slices", False)
        or review_pipeline.get("require_code_reviewer_for_non_doc_slices", False)
    )
    require_qa_non_doc = bool(
        ticket_review_enforcement.get("require_qa_reliability_for_non_doc_slices", False)
        or dispatch_protocol.get("require_qa_reliability_for_non_doc_slices", False)
        or review_pipeline.get("require_qa_reliability_for_non_doc_slices", False)
    )
    require_release_ops_docs_sync = bool(
        ticket_review_enforcement.get("require_release_ops_docs_sync_before_merge", False)
        or dispatch_protocol.get("require_release_ops_docs_sync_before_merge", False)
        or review_pipeline.get("require_release_ops_for_non_doc_docs_sync", False)
    )
    require_merge_before_code_review = bool(
        ticket_review_enforcement.get("require_merge_specialist_before_code_review", False)
        or dispatch_protocol.get("require_merge_specialist_before_code_review", False)
        or review_pipeline.get("require_merge_specialist_before_code_review", False)
    )
    require_reviewer_head_sha_match = bool(
        ticket_review_enforcement.get("require_reviewer_head_sha_match", False)
        or dispatch_protocol.get("require_reviewer_head_sha_match", False)
        or review_pipeline.get("require_reviewer_head_sha_match", False)
    )
    require_qa_after_code_review_timestamp = bool(
        ticket_review_enforcement.get("require_qa_after_code_review_timestamp", False)
        or dispatch_protocol.get("require_qa_after_code_review_timestamp", False)
        or review_pipeline.get("require_qa_after_code_review_timestamp", False)
    )
    enforce_changed_file_docs_only_classification = bool(
        ticket_review_enforcement.get("enforce_changed_file_docs_only_classification", False)
        or dispatch_protocol.get("enforce_changed_file_docs_only_classification", False)
        or review_pipeline.get("enforce_changed_file_docs_only_classification", False)
    )
    claim_policy = claim_contract(orchestrator_layer)
    require_claim_gate = bool(
        claim_policy.get("require_ticket_claim_before_work", False)
        and claim_policy.get("reject_unclaimed_agent_results", False)
    )

    run(["git", "fetch", "origin", "--prune"], cwd=repo_root, check=False)

    if args.merge:
        ensure_base_checked_out(repo_root, base_branch, args.allow_dirty)

    # Senior-orchestrator pre-merge reconnaissance:
    # collect per-slice ahead state and changed files, then detect cross-slice overlaps.
    slice_ahead: dict[str, int] = {}
    slice_changed: dict[str, set[str]] = {}
    slice_agents: dict[str, str] = {}
    slice_branches: dict[str, str] = {}
    slice_worktree_paths: dict[str, str] = {}
    by_id = {
        str(s.get("id", "")).strip(): s
        for s in ticket.get("slices", [])
        if isinstance(s, dict) and str(s.get("id", "")).strip()
    }
    ordered_ids = slice_dependency_order(ticket)
    ordered_slices = [by_id[sid] for sid in ordered_ids if sid in by_id]
    for s in ticket.get("slices", []):
        sid = str(s.get("id", "")).strip()
        if sid not in {str(x.get("id", "")).strip() for x in ordered_slices}:
            ordered_slices.append(s)

    for s in ordered_slices:
        sid = str(s.get("id"))
        branch, worktree_path = resolve_slice_execution_coordinates(repo_root, args.ticket_id, s)
        branch = branch or str(s.get("branch", "")).strip()
        worktree_path = worktree_path or str(s.get("worktree_path", "")).strip()
        agent = str(s.get("primary_agent"))
        slice_branches[sid] = branch
        slice_worktree_paths[sid] = worktree_path
        ahead = count_ahead(repo_root, base_branch, branch)
        slice_ahead[sid] = ahead
        slice_agents[sid] = agent
        if ahead > 0:
            slice_changed[sid] = set(changed_files(repo_root, base_branch, branch))

    overlap_map = cross_slice_overlaps(slice_changed, slice_agents)

    verify_stamp = utc_now()
    initial_ticket_status = str(ticket.get("status", "")).strip().lower()
    if claim_policy.get("require_ticket_status_history", False):
        ticket_history = ensure_history_list(ticket, "status_history")
        if ticket_history:
            last_to = str(ticket_history[-1].get("to_status", "")).strip().lower()
            if last_to and initial_ticket_status and last_to != initial_ticket_status:
                raise RuntimeError(
                    "ticket status history mismatch: last to_status "
                    f"'{last_to}' != current status '{initial_ticket_status}'"
                )
        elif initial_ticket_status:
            ticket_history.append(
                {
                    "from_status": "",
                    "to_status": initial_ticket_status,
                    "at_utc": verify_stamp,
                    "reason": "history_bootstrap",
                    "actor": "orchestrator",
                }
            )
        ticket["status_history"] = ticket_history

    report_lines = [
        f"# Verify Report - {args.ticket_id}",
        "",
        f"- generated_at: {verify_stamp}",
        f"- merge_mode: {'on' if args.merge else 'off'}",
        "",
    ]

    merged_count = 0
    ready_count = 0
    failed_count = 0

    for s in ticket.get("slices", []):
        sid = str(s.get("id"))
        branch = slice_branches.get(sid, str(s.get("branch", "")).strip())
        wt = Path(slice_worktree_paths.get(sid, str(s.get("worktree_path", "")).strip()))
        checks = [str(c) for c in s.get("required_checks", []) if str(c).strip()]
        reviewers = [str(r) for r in s.get("reviewers", []) if str(r).strip()]
        allowed_scope_paths = [str(p) for p in s.get("allowed_scope_paths", []) if str(p).strip()]
        slice_dir = ticket_dir(repo_root, args.ticket_id) / "slices" / sid
        harness_dir = slice_dir / "harness"
        harness_dir.mkdir(parents=True, exist_ok=True)

        ahead = slice_ahead.get(sid, 0)
        changed_now = sorted(slice_changed.get(sid, set()))
        overlaps_now = overlap_map.get(sid, [])
        orchestrator_review = slice_dir / "orchestrator-review.md"

        if require_claim_gate and claim_required_for_slice(s, ahead):
            claim_snapshot = s.get("claim") if isinstance(s.get("claim"), dict) else {}
            expected_branch, expected_worktree_path, expected_branch_label, expected_worktree_label = (
                claim_expected_coordinates(repo_root, args.ticket_id, s, claim_snapshot)
            )
            claim, claim_errors = validate_claim_fields(
                s,
                claim_policy["required_fields"],
                expected_branch=expected_branch,
                expected_worktree_path=expected_worktree_path,
                expected_branch_label=expected_branch_label,
                expected_worktree_label=expected_worktree_label,
            )
            if (
                not claim_errors
                and claim_policy.get("require_timeline_claim_event", True)
                and not timeline_has_claim_line(
                    repo_root,
                    args.ticket_id,
                    s,
                    claim,
                    allow_legacy=bool(claim_policy.get("allow_legacy_timeline_claim_lines", True)),
                )
            ):
                claim_errors.append("missing claim lineage in TIMELINE.md")
            if claim_errors:
                s["status"] = "claim_blocked"
                failed_count += 1
                report_lines.append(f"## {sid} ({s.get('primary_agent')})")
                report_lines.append("- status: claim_blocked")
                for err in claim_errors:
                    report_lines.append(f"- claim_error: {err}")
                report_lines.append("")
                orchestrator_review.write_text(
                    f"# Orchestrator Review\n\n"
                    f"ticket: {args.ticket_id}\n"
                    f"slice: {sid}\n"
                    f"status: claim_blocked\n\n"
                    f"## Notes\n"
                    + "".join([f"- {err}\n" for err in claim_errors]),
                    encoding="utf-8",
                )
                continue

        if ahead <= 0:
            s["status"] = "waiting_for_push"
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: waiting_for_push")
            report_lines.append(f"- branch: `{branch}` has no commits ahead of `{base_branch}`")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: waiting_for_push\n\n"
                f"## Notes\n"
                f"- Branch has no commits ahead of `{base_branch}`.\n",
                encoding="utf-8",
            )
            continue

        if overlaps_now:
            s["status"] = "coordination_required"
            failed_count += 1
            overlap_log = harness_dir / "coordination-overlap.log"
            lines = []
            for other_sid, f in overlaps_now:
                lines.append(f"{sid} overlaps with {other_sid}: {f}")
            overlap_log.write_text("\n".join(lines) + "\n", encoding="utf-8")
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: coordination_required")
            report_lines.append("- reason: cross-slice overlap with another implementation agent")
            report_lines.append(f"- overlap_log: `{overlap_log}`")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: coordination_required\n\n"
                f"## Notes\n"
                f"- Cross-slice overlap detected. Merge blocked pending orchestrator/manual consolidation.\n\n"
                f"## Overlaps\n"
                + "".join([f"- {sid} with {other_sid}: `{f}`\n" for other_sid, f in overlaps_now]),
                encoding="utf-8",
            )
            continue

        violations = out_of_scope_files(changed_now, allowed_scope_paths)
        if violations:
            s["status"] = "scope_violation"
            failed_count += 1
            violation_log = harness_dir / "scope-violation.log"
            violation_log.write_text(
                "Changed files outside allowed scope:\n"
                + "\n".join(violations)
                + "\n\nAllowed scopes:\n"
                + "\n".join(allowed_scope_paths)
                + "\n",
                encoding="utf-8",
            )
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: scope_violation")
            report_lines.append(f"- changed_files_count: {len(changed_now)}")
            report_lines.append(f"- violation_log: `{violation_log}`")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: scope_violation\n\n"
                f"## Notes\n"
                f"- Branch changed files outside agent write boundary.\n"
                f"- See: `{violation_log}`\n",
                encoding="utf-8",
            )
            continue

        if not wt.exists():
            s["status"] = "blocked_missing_worktree"
            failed_count += 1
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: blocked_missing_worktree")
            report_lines.append(f"- missing worktree: `{wt}`")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: blocked_missing_worktree\n\n"
                f"## Notes\n"
                f"- Expected worktree missing: `{wt}`\n",
                encoding="utf-8",
            )
            continue

        check_fail = False
        check_log = []
        for idx, cmd in enumerate(checks, start=1):
            cmd_log = harness_dir / f"check-{idx:02d}.log"
            try:
                argv = parse_required_check_command(cmd)
                proc = run(argv, cwd=wt, check=False)
                command_line = " ".join(shlex.quote(part) for part in argv)
                stdout = proc.stdout
                stderr = proc.stderr
                ok = proc.returncode == 0
            except ValueError as exc:
                command_line = cmd
                stdout = ""
                stderr = f"{exc}\n"
                ok = False
            cmd_log.write_text(
                f"$ {command_line}\n\nSTDOUT:\n{stdout}\n\nSTDERR:\n{stderr}\n",
                encoding="utf-8",
            )
            check_log.append((cmd, ok, cmd_log))
            if not ok:
                check_fail = True
                break

        if check_fail:
            s["status"] = "checks_failed"
            failed_count += 1
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: checks_failed")
            for cmd, ok, path in check_log:
                report_lines.append(f"- check: `{cmd}` => {'PASS' if ok else 'FAIL'} (`{path}`)")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: checks_failed\n\n"
                f"## Notes\n"
                f"- Required checks failed. See harness logs in `{harness_dir}`.\n",
                encoding="utf-8",
            )
            continue

        docs_only_by_scope = is_docs_only_scope([str(p) for p in s.get("scope_paths", []) if str(p).strip()])
        docs_only_by_change = is_docs_only_scope(changed_now)
        docs_only_effective = docs_only_by_change if enforce_changed_file_docs_only_classification else docs_only_by_scope

        if enforce_changed_file_docs_only_classification and docs_only_by_scope and not docs_only_by_change:
            s["status"] = "review_policy_violation"
            failed_count += 1
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: review_policy_violation")
            report_lines.append("- reason: slice declared docs-only scope but branch contains non-doc changed files")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: review_policy_violation\n\n"
                f"## Notes\n"
                f"- Docs-only scope mismatch detected using changed-file classification.\n",
                encoding="utf-8",
            )
            continue

        missing_required_reviewers: list[str] = []
        if not docs_only_effective:
            if require_merge_specialist_non_doc and "merge-specialist" not in reviewers:
                missing_required_reviewers.append("merge-specialist")
            if require_code_reviewer_non_doc and "code_reviewer" not in reviewers:
                missing_required_reviewers.append("code_reviewer")
            if require_qa_non_doc and "qa-reliability" not in reviewers:
                missing_required_reviewers.append("qa-reliability")
            if require_release_ops_docs_sync and "release-ops" not in reviewers:
                missing_required_reviewers.append("release-ops")

        if missing_required_reviewers:
            s["status"] = "review_policy_violation"
            failed_count += 1
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: review_policy_violation")
            report_lines.append(f"- missing_required_reviewers: {', '.join(sorted(set(missing_required_reviewers)))}")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: review_policy_violation\n\n"
                f"## Notes\n"
                f"- Mandatory reviewer roles missing for non-doc slice: {', '.join(sorted(set(missing_required_reviewers)))}\n",
                encoding="utf-8",
            )
            continue

        approved_head_sha = branch_head_sha(repo_root, branch)
        review_meta: dict[str, dict[str, str]] = {}
        pending_reviewers: list[str] = []
        stale_sha_reviewers: list[str] = []
        stale_time_reviewers: list[str] = []

        for reviewer in reviewers:
            rf = slice_dir / "reviews" / f"{reviewer}.md"
            meta = parse_review_file(rf)
            review_meta[reviewer] = meta
            if meta.get("status", "").strip().lower() != "approved":
                pending_reviewers.append(reviewer)
                continue
            if require_reviewer_head_sha_match:
                reviewed_sha = meta.get("reviewed_head_sha", "").strip()
                if not reviewed_sha or not approved_head_sha or reviewed_sha != approved_head_sha:
                    stale_sha_reviewers.append(reviewer)

        qa_reviewer = "qa-reliability"
        merge_reviewer = "merge-specialist"
        non_code_reviewers = {qa_reviewer, merge_reviewer, "release-ops"}
        code_reviewers = [r for r in reviewers if r not in non_code_reviewers]
        pending_code_reviewers = [r for r in pending_reviewers if r in code_reviewers]
        qa_involved = qa_reviewer in reviewers

        if require_merge_before_code_review and merge_reviewer in reviewers and code_reviewers:
            merge_meta = review_meta.get(merge_reviewer, {})
            merge_time = parse_reviewed_at(merge_meta.get("reviewed_at_utc", ""))
            if merge_reviewer not in pending_reviewers and merge_reviewer not in stale_sha_reviewers:
                if not merge_time:
                    stale_time_reviewers.append(merge_reviewer)
                for reviewer in code_reviewers:
                    if reviewer in pending_reviewers or reviewer in stale_sha_reviewers:
                        continue
                    code_time = parse_reviewed_at(review_meta.get(reviewer, {}).get("reviewed_at_utc", ""))
                    if not code_time or (merge_time and code_time < merge_time):
                        stale_time_reviewers.append(reviewer)

        if require_qa_after_code_review_timestamp and qa_involved:
            qa_meta = review_meta.get(qa_reviewer, {})
            qa_time = parse_reviewed_at(qa_meta.get("reviewed_at_utc", ""))
            latest_code_review_time: dt.datetime | None = None
            for reviewer in code_reviewers:
                if reviewer in pending_reviewers or reviewer in stale_sha_reviewers:
                    continue
                ts = parse_reviewed_at(review_meta.get(reviewer, {}).get("reviewed_at_utc", ""))
                if not ts:
                    stale_time_reviewers.append(reviewer)
                    continue
                if latest_code_review_time is None or ts > latest_code_review_time:
                    latest_code_review_time = ts
            if qa_reviewer not in pending_reviewers and qa_reviewer not in stale_sha_reviewers:
                if not qa_time or (latest_code_review_time and qa_time < latest_code_review_time):
                    stale_time_reviewers.append(qa_reviewer)

        all_pending = sorted(set(pending_reviewers + stale_sha_reviewers + stale_time_reviewers))
        pending_code_reviewers = [r for r in all_pending if r in code_reviewers]

        if all_pending:
            s["status"] = "pending_review"
            ready_count += 1
            report_lines.append(f"## {sid} ({s.get('primary_agent')})")
            report_lines.append("- status: pending_review")
            if qa_involved and pending_code_reviewers:
                report_lines.append("- review_sequence_gate: code-review must complete before qa-reliability sign-off")
            if stale_sha_reviewers:
                report_lines.append(f"- stale_head_sha_reviews: {', '.join(sorted(set(stale_sha_reviewers)))}")
            if stale_time_reviewers:
                report_lines.append(f"- stale_sequence_or_timestamp_reviews: {', '.join(sorted(set(stale_time_reviewers)))}")
            report_lines.append(f"- pending_reviewers: {', '.join(all_pending)}")
            report_lines.append("")
            orchestrator_review.write_text(
                f"# Orchestrator Review\n\n"
                f"ticket: {args.ticket_id}\n"
                f"slice: {sid}\n"
                f"status: pending_review\n\n"
                f"## Notes\n"
                + (
                    "- Review sequence gate active: code-review approvals must complete before qa-reliability is final.\n"
                    if qa_involved and pending_code_reviewers
                    else ""
                )
                + (
                    f"- Stale head SHA reviews: {', '.join(sorted(set(stale_sha_reviewers)))}.\n"
                    if stale_sha_reviewers
                    else ""
                )
                + (
                    f"- Sequence/timestamp re-review required: {', '.join(sorted(set(stale_time_reviewers)))}.\n"
                    if stale_time_reviewers
                    else ""
                )
                + f"- Awaiting reviewer approvals: {', '.join(all_pending)}\n",
                encoding="utf-8",
            )
            continue

        s["status"] = "verified"
        ready_count += 1

        if args.merge:
            merge_proc = run(["git", "merge", "--no-ff", "--no-edit", branch], cwd=repo_root, check=False)
            merge_log = harness_dir / "merge.log"
            merge_log.write_text(
                f"$ git merge --no-ff --no-edit {branch}\n\nSTDOUT:\n{merge_proc.stdout}\n\nSTDERR:\n{merge_proc.stderr}\n",
                encoding="utf-8",
            )
            if merge_proc.returncode != 0:
                s["status"] = "merge_failed"
                failed_count += 1
                report_lines.append(f"## {sid} ({s.get('primary_agent')})")
                report_lines.append("- status: merge_failed")
                report_lines.append(f"- merge_log: `{merge_log}`")
                report_lines.append("")
                orchestrator_review.write_text(
                    f"# Orchestrator Review\n\n"
                    f"ticket: {args.ticket_id}\n"
                    f"slice: {sid}\n"
                    f"status: merge_failed\n\n"
                    f"## Notes\n"
                    f"- Merge failed. See `{merge_log}`.\n",
                    encoding="utf-8",
                )
                continue

            s["status"] = "merged"
            merged_count += 1
            if args.push_base:
                push_proc = run(["git", "push", "origin", base_branch], cwd=repo_root, check=False)
                push_log = harness_dir / "push.log"
                push_log.write_text(
                    f"$ git push origin {base_branch}\n\nSTDOUT:\n{push_proc.stdout}\n\nSTDERR:\n{push_proc.stderr}\n",
                    encoding="utf-8",
                )
                if push_proc.returncode != 0:
                    s["status"] = "push_failed"
                    failed_count += 1
                    report_lines.append(f"## {sid} ({s.get('primary_agent')})")
                    report_lines.append("- status: push_failed")
                    report_lines.append(f"- push_log: `{push_log}`")
                    report_lines.append("")
                    orchestrator_review.write_text(
                        f"# Orchestrator Review\n\n"
                        f"ticket: {args.ticket_id}\n"
                        f"slice: {sid}\n"
                        f"status: push_failed\n\n"
                        f"## Notes\n"
                        f"- Push failed. See `{push_log}`.\n",
                        encoding="utf-8",
                    )
                    continue

            if cleanup_enabled:
                cleanup_log = harness_dir / "cleanup-worktree.log"
                if wt.exists():
                    cleanup_proc = run(["git", "worktree", "remove", str(wt)], cwd=repo_root, check=False)
                    cleanup_log.write_text(
                        f"$ git worktree remove {wt}\n\nSTDOUT:\n{cleanup_proc.stdout}\n\nSTDERR:\n{cleanup_proc.stderr}\n",
                        encoding="utf-8",
                    )
                    if cleanup_proc.returncode != 0:
                        s["status"] = "cleanup_failed"
                        failed_count += 1
                        report_lines.append(f"## {sid} ({s.get('primary_agent')})")
                        report_lines.append("- status: cleanup_failed")
                        report_lines.append(f"- cleanup_log: `{cleanup_log}`")
                        report_lines.append("")
                        orchestrator_review.write_text(
                            f"# Orchestrator Review\n\n"
                            f"ticket: {args.ticket_id}\n"
                            f"slice: {sid}\n"
                            f"status: cleanup_failed\n\n"
                            f"## Notes\n"
                            f"- Merge succeeded but worktree cleanup failed. See `{cleanup_log}`.\n",
                            encoding="utf-8",
                        )
                        continue
                else:
                    cleanup_log.write_text(
                        f"worktree already absent: {wt}\n",
                        encoding="utf-8",
                    )

        report_lines.append(f"## {sid} ({s.get('primary_agent')})")
        report_lines.append(f"- status: {s['status']}")
        for cmd, ok, path in check_log:
            report_lines.append(f"- check: `{cmd}` => {'PASS' if ok else 'FAIL'} (`{path}`)")
        report_lines.append("")
        orchestrator_review.write_text(
            f"# Orchestrator Review\n\n"
            f"ticket: {args.ticket_id}\n"
            f"slice: {sid}\n"
            f"status: {s['status']}\n\n"
            f"## Notes\n"
            f"- Scope boundaries respected.\n"
            f"- Required checks passed.\n"
            f"- Reviewer approvals complete.\n",
            encoding="utf-8",
        )

    all_statuses = {str(s.get("status")) for s in ticket.get("slices", [])}
    next_ticket_status = initial_ticket_status or "planned"
    if all(x == "merged" for x in all_statuses):
        next_ticket_status = "done"
    elif any(
        x
        in {
            "checks_failed",
            "merge_failed",
            "push_failed",
            "blocked_missing_worktree",
            "scope_violation",
            "coordination_required",
            "cleanup_failed",
            "claim_blocked",
        }
        for x in all_statuses
    ):
        next_ticket_status = "blocked"
    elif any(x in {"verified", "pending_review", "waiting_for_push"} for x in all_statuses):
        next_ticket_status = "in_progress"

    ticket_from, ticket_to = apply_ticket_status(
        ticket,
        next_ticket_status,
        reason="verify_run",
        actor="orchestrator",
        at_utc=verify_stamp,
    )

    write_ticket(repo_root, ticket)
    write_cross_workflow_plan(repo_root, ticket)
    write_ticket_summary(repo_root, ticket)

    report_dir = ticket_dir(repo_root, args.ticket_id) / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    report_path = report_dir / f"verify-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.md"

    report_lines.insert(4, f"- ticket_status: {ticket.get('status')}")
    report_lines.insert(5, f"- merged_count: {merged_count}")
    report_lines.insert(6, f"- verified_or_pending_review_count: {ready_count}")
    report_lines.insert(7, f"- failed_count: {failed_count}")

    report_path.write_text("\n".join(report_lines) + "\n", encoding="utf-8")
    if ticket_from != ticket_to:
        append_timeline_event(
            repo_root,
            args.ticket_id,
            "ticket_status_transition",
            f"ticket status transitioned: `{ticket_from}` -> `{ticket_to}`",
            {
                "ticket_id": args.ticket_id,
                "from_status": ticket_from,
                "to_status": ticket_to,
                "reason": "verify_run",
            },
            at_utc=verify_stamp,
        )
    append_timeline_event(
        repo_root,
        args.ticket_id,
        "verify_completed",
        f"verify run completed (merge_mode={'on' if args.merge else 'off'})",
        {
            "ticket_id": args.ticket_id,
            "merge_mode": "on" if args.merge else "off",
            "merged_count": merged_count,
            "ready_count": ready_count,
            "failed_count": failed_count,
            "ticket_status": ticket.get("status"),
            "report_path": str(report_path),
        },
        at_utc=verify_stamp,
    )

    print(f"[harness] verify report: {report_path}")
    print(f"[harness] ticket status: {ticket.get('status')}")
    print(f"[harness] merged={merged_count} ready={ready_count} failed={failed_count}")
    return 0 if failed_count == 0 else 1


def status_ticket(args: argparse.Namespace) -> int:
    repo_root = project_root()
    ticket = read_ticket(repo_root, args.ticket_id)
    print(f"ticket_id: {ticket.get('ticket_id')}")
    print(f"title: {ticket.get('title')}")
    print(f"status: {ticket.get('status')}")
    print(f"base_branch: {ticket.get('base_branch')}")
    print("slices:")
    for s in ticket.get("slices", []):
        print(
            f"  - {s.get('id')}: agent={s.get('primary_agent')} lane={s.get('lane')} status={s.get('status')} branch={s.get('branch')}"
        )
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Harness orchestration helper for worktree/ticket automation")
    sub = p.add_subparsers(dest="cmd", required=True)

    create = sub.add_parser("bootstrap", help="Create ticket, plan slices, create worktrees, generate tmux commands")
    create.add_argument("--title", required=True)
    create.add_argument("--goal", required=True)
    create.add_argument("--priority", default="high")
    create.add_argument("--base-branch", default="harness-engineering-orchestrator")
    create.add_argument("--paths", default="", help="Comma-separated target paths")
    create.add_argument("--path", action="append", default=[], help="Repeatable target path")
    create.add_argument("--ticket-id", default="")
    create.add_argument("--worktree-root", default="")
    create.add_argument("--tmux-lanes", default="", help="Comma-separated lanes, e.g. w1,w2,w3,w4")
    create.add_argument("--create-worktrees", action=argparse.BooleanOptionalAction, default=True)
    create.set_defaults(func=create_ticket)

    dispatch = sub.add_parser("dispatch", help="Regenerate and print tmux command block for an existing ticket")
    dispatch.add_argument("--ticket-id", required=True)
    dispatch.add_argument("--create-missing-worktrees", action="store_true")
    dispatch.set_defaults(func=dispatch_ticket)

    review = sub.add_parser("review", help="Set reviewer evidence status for a slice")
    review.add_argument("--ticket-id", required=True)
    review.add_argument("--slice-id", required=True)
    review.add_argument("--reviewer", required=True)
    review.add_argument("--status", choices=["approved", "changes_requested", "blocked", "pending"], required=True)
    review.add_argument("--findings", default="")
    review.add_argument("--commands", default="")
    review.add_argument("--artifacts", default="")
    review.add_argument("--head-sha", default="", help="Optional explicit commit SHA that this review approves")
    review.set_defaults(func=set_review_status)

    claim = sub.add_parser("claim", help="Record claim artifacts for a slice and enforce claim transition policy")
    claim.add_argument("--ticket-id", required=True)
    claim.add_argument("--slice-id", required=True)
    claim.add_argument("--agent-id", default="", help="Defaults to slice primary_agent")
    claim.add_argument("--blocker-id", default="", help="Optional blocker id (for example B07)")
    claim.add_argument("--claim-status", default="", help="Defaults to dispatch_protocol.ticket_claim_enforcement.claim_status")
    claim.add_argument("--claimed-at-utc", default="", help="Optional explicit timestamp")
    claim.add_argument("--auto-in-progress", action=argparse.BooleanOptionalAction, default=True)
    claim.set_defaults(func=claim_slice)

    verify = sub.add_parser("verify", help="Run checks, evaluate reviews, optionally merge")
    verify.add_argument("--ticket-id", required=True)
    verify.add_argument("--merge", action="store_true")
    verify.add_argument("--push-base", action="store_true")
    verify.add_argument("--cleanup-worktrees", action=argparse.BooleanOptionalAction, default=None)
    verify.add_argument("--allow-dirty", action="store_true")
    verify.set_defaults(func=verify_ticket)

    status = sub.add_parser("status", help="Print compact ticket status")
    status.add_argument("--ticket-id", required=True)
    status.set_defaults(func=status_ticket)

    return p


def main() -> int:
    p = parser()
    args = p.parse_args()
    try:
        return int(args.func(args))
    except Exception as exc:  # pylint: disable=broad-except
        print(f"[harness] ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
