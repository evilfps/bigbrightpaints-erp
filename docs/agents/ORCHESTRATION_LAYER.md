# Orchestrator Layer Contract

Last reviewed: 2026-02-27
Owner: Orchestrator Agent

This defines how the orchestrator controls all agents in long-running async loops.

## Objective
- Route work, enforce contracts, require evidence, and gate completion.
- Preserve architectural integrity and cross-module coherence, not just merge throughput.
- Keep humans focused on irreversible production actions only.

## System Posture
- The orchestrator is an engineering control system, not a blind task executor.
- Every role keeps system-level awareness proportional to responsibility.
- Narrow scope does not permit narrow thinking on integration and failure modes.

## Control Plane Inputs
- `asyncloop` (active slice queue and evidence ledger)
- `agents/catalog.yaml` (agent inventory, risk, scopes)
- `agents/orchestrator-layer.yaml` (routing, reviews, completion rules)
- `docs/agents/PERMISSIONS.md` and `docs/agents/WORKFLOW.md` (policy)

## Dispatch Model
1. Planner publishes architecture intent packet (scope boundaries, constraints, contracts, success criteria).
2. Orchestrator maps paths/risk to scoped implementation agents via `agents/orchestrator-layer.yaml`.
3. Each implementation slice runs in isolated worktree/branch with ticket claim recorded.
4. Code-reviewer performs deep module-level review.
5. Merge-specialist performs integration-integrity review and merge gate decision.
6. QA-reliability performs cross-workflow exploratory validation and regression checks.
7. Orchestrator runs required guard checks before marking done.

## Agent Claim and Isolation Contract
- Agents must not start edits before claim is recorded in ticket artifacts.
- One slice can have only one active implementation claim at a time.
- Worker reads only its own `docs/agents/templates/TASK_PACKET.md`-derived packet; cross-slice packet reads are blocked.
- Reviewer agents are review-only and cannot claim implementation ownership.
- Unclaimed submissions are rejected in orchestrator pre-merge review.

## Role Responsibility Contract
- Planner:
  - Defines architectural intent, module boundaries, and system constraints.
  - Does not micromanage implementation details.
- Implementation agents:
  - Own design decisions within scope.
  - Must account for cross-module impact and maintainability.
- Code-reviewer:
  - Performs deep correctness/security/performance/test review at module level.
- Merge-specialist:
  - Validates semantic merge correctness, interface compatibility, coupling risk, and observability impact.
  - Is not a blind conflict resolver and cannot be forced to approve.
- QA-reliability:
  - Performs exploratory validation, edge-case checks, and end-to-end workflow verification.

## Subagent Role Routing
Orchestrator selects runtime role by scope and risk:
- `orchestrator`
- `planning`
- `planning_architecture`
- `backend_arch`
- `product_analyst`
- `cross_module`
- `cross_module_high`
- `security_auditor`
- `code_reviewer`
- `merge_specialist`
- `performance`
- `frontend_arch` / `frontend_documentation`
- legacy fallback buckets: `reviewer`, `implementation_high_risk`, `implementation_standard`, `exploration`

Existing YAML agent IDs map into these custom roles through `runtime.subagents.agent_role_mapping` in `agents/orchestrator-layer.yaml` and project-level role config in `.codex/config.toml`.

Fallback role/profile decision must be logged in ticket timeline.

## Review Model (Mandatory)
- Every slice requires:
  - code-reviewer module review
  - merge-specialist integration review
  - qa-reliability system validation
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
- Merge-specialist decides merge readiness based on integration integrity evidence.
- A commit/merge is valid only after required review evidence is attached.

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
