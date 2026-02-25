# Orchestrator Layer Contract

Last reviewed: 2026-02-24
Owner: Orchestrator Agent

This defines how the orchestrator controls all agents in long-running async loops.

## Objective
- Real orchestrator behavior: route work, enforce contracts, require evidence, and gate completion.
- Keep autonomy high: orchestrator closes R1/R2 when proof exists.
- Keep humans focused on irreversible production actions only.

## Control Plane Inputs
- `asyncloop` (active slice queue and evidence ledger)
- `agents/catalog.yaml` (agent inventory, risk, scopes)
- `agents/orchestrator-layer.yaml` (routing, reviews, completion rules)
- `docs/agents/PERMISSIONS.md` and `docs/agents/WORKFLOW.md` (policy)

## Dispatch Model
1. Orchestrator reads next `in_progress` or top `ready` slice from `asyncloop`.
2. It maps changed paths and risk to a primary agent via `agents/orchestrator-layer.yaml`.
3. It enforces ticket claim: `ready -> taken -> in_progress` with agent identity + worktree + branch recorded.
4. It validates ticket-first gate before coding: assigned branch, assigned worktree, and base-branch read-only policy.
5. It requires codebase impact analysis in each implementation handoff before review.
6. It assigns at least one reviewer agent.
7. For high-risk slices, it adds `security-governance` and `qa-reliability` reviewers.
8. It runs required guard checks before marking done.
9. For non-doc slices, it enforces role order: planning -> implementation -> merge-specialist -> code review -> qa-reliability -> release-ops sync.

## Agent Claim and Isolation Contract
- Agents must not start edits before claim is recorded in ticket artifacts.
- One slice can have only one active implementation claim at a time.
- Worker reads only its own `docs/agents/templates/TASK_PACKET.md`-derived packet; cross-slice packet reads are blocked.
- Reviewer agents are review-only and cannot claim implementation ownership.
- Unclaimed submissions are rejected in orchestrator pre-merge review.
- Base branches (`harness-engineering-orchestrator`, `main`, `master`) are read-only for implementation edits.

## Required Slice Output Contract
- `files_changed`
- `commands_run`
- `harness_results`
- `residual_risks`
- `blockers_or_next_step`
- `ticket_claim_evidence`
- `worktree_validation`
- `codebase_impact_analysis`

## QA Reliability Role
- `qa-reliability` is the cross-workflow testing owner, not a superficial reviewer.
- It validates integrated behavior after code-review approvals and before final merge.
- It publishes regression/gate evidence used by orchestrator merge readiness.

## Merge Specialist Role
- `merge-specialist` owns integration merge/conflict handling between implementation and review phases.
- It provides merge-evidence artifacts and flags semantic conflict risk before code-review and QA stages.
- It does not replace code review or QA.

## Subagent Role Routing
Orchestrator selects runtime role by scope and risk:
- `orchestrator`
- `planning`
- `planning_architecture`
- `backend_arch`
- `product_analyst`
- `data_migration`
- `cross_module`
- `cross_module_high`
- `security_auditor`
- `merge_specialist`
- `code_reviewer`
- `qa_reliability`
- `performance`
- `frontend_arch` / `frontend_documentation`
- legacy fallback buckets: `reviewer`, `implementation_high_risk`, `implementation_standard`, `exploration`

Existing YAML agent IDs map into these custom roles through `runtime.subagents.agent_role_mapping` in `agents/orchestrator-layer.yaml` and project-level role config in `.codex/config.toml`.

Fallback role/profile decision must be logged in ticket timeline.

## Review Model (Mandatory)
- Every slice requires:
  - one reviewer agent minimum
  - codex review guideline checks
  - architecture/doc/enterprise policy guard checks
- Lane-qualified docs-only exception:
  - `fast_lane` docs-only slices may skip reviewer-agent and commit-review steps.
  - `strict_lane` control-plane docs changes (`docs/agents/`, `docs/ASYNC_LOOP_OPERATIONS.md`, `docs/system-map/REVIEW_QUEUE_POLICY.md`, `agents/orchestrator-layer.yaml`, `asyncloop`, `scripts/harness_orchestrator.py`, `ci/`) may skip reviewer-agent and commit-review only when no runtime/config/schema/test files changed, and still require:
    - `bash ci/lint-knowledgebase.sh`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
- runtime/config/schema/test changes never qualify for docs-only review skip.
- resolve lane/check mapping with `scripts/harness_orchestrator.py`.
- approvals must target the current branch head SHA; stale approvals are treated as pending.
- QA timestamp must be after code-review approvals for non-doc slices.
- Evidence must be appended to `asyncloop` for traceability.

## Strict-Lane Runbook Alignment
- Strict-lane docs-only operations must keep policy text aligned across:
  - `docs/agents/WORKFLOW.md`
  - `docs/ASYNC_LOOP_OPERATIONS.md`
  - `docs/system-map/REVIEW_QUEUE_POLICY.md`
  - `asyncloop`
- Review-only agents provide findings/evidence and do not commit code.

## Commit Ownership Model
- Primary implementation agent commits slice code.
- Review-only agents provide findings/evidence and do not commit code.
- Orchestrator commits orchestration/policy/docs artifacts unless it explicitly takes slice ownership.
- A commit is valid only after review evidence is attached.

## Cross-Module Contract
- The orchestrator enforces order: contracts -> producer -> consumers -> orchestrator.
- New dependency edges require allowlist + ADR evidence.

## Proof-First Decisions
- Decision quality is evidence-backed (tests/guards/traces), never assumption-backed.
- If evidence is missing, slice status remains blocked.

## Human Escalation Boundary
- Human escalation is required only for irreversible production actions (R3).
- R1/R2 decisions are owned by orchestrator when proof pack is complete.
- Main orchestrator CLI session remains active until human sends exact `STOP`.

## Unknowns and TODOs
- Auto-trigger mechanism for subagent spawning from CI events is unspecified.
  - TODO: define event adapter if orchestrator will run outside interactive Codex sessions.
