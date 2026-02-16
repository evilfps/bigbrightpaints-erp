# Orchestrator Layer Contract

Last reviewed: 2026-02-16
Owner: Orchestrator Agent

This defines how the orchestrator controls all agents in long-running async loops.

## Objective
- Real orchestrator behavior: route work, enforce contracts, require evidence, and gate completion.
- Keep autonomy high: orchestrator closes R1/R2 when proof exists.
- Keep humans focused on irreversible production actions only.

## Control Plane Inputs
- `asyncloop` (active slice queue and evidence ledger)
- `tickets/` (local ticket ledger, task packets, review evidence, verify reports)
- `agents/catalog.yaml` (agent inventory, risk, scopes)
- `agents/orchestrator-layer.yaml` (routing, reviews, completion rules)
- `docs/agents/PERMISSIONS.md` and `docs/agents/WORKFLOW.md` (policy)
- `scripts/harness_orchestrator.py` (worktree assignment, tmux dispatch commands, verify/merge controller)

## Dispatch Model
1. Orchestrator reads next `in_progress` or top `ready` slice from `asyncloop`.
2. It maps changed paths and risk to a primary agent via `agents/orchestrator-layer.yaml`.
3. It assigns at least one reviewer agent.
4. For high-risk slices, it adds `security-governance` and `qa-reliability` reviewers.
5. It runs required guard checks before marking done.
6. It blocks merge when branch edits violate the assigned agent's `scope_paths`.

## Autonomous Throughput During Active Runs
- While workers are running, orchestrator keeps monitoring logs/evidence and plans upcoming work in parallel.
- Throughput target is never below 2 preplanned ready tickets; preferred buffer is 3.
- Next tickets must be selected from `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md` and prepared before active slices finish.
- Ticket/task-packet isolation still applies; planning ahead does not permit cross-slice packet leakage.

## Codex Exec Flag Canon
- Canonical full-access orchestrator execution flag: `--dangerously-bypass-approvals-and-sandbox`.
- `codex exec --help` is the authority for current flag surface and does not always display compatibility aliases.
- `--yolo` remains accepted as compatibility alias for open-session usage, not as canonical orchestration contract.

## Review Model (Mandatory)
- Every slice requires:
  - one reviewer agent minimum
  - orchestrator pre-merge review
  - codex review guideline checks
  - architecture/doc/enterprise policy guard checks
- Evidence must be appended to `asyncloop` for traceability.
- Orchestrator verifies cross-slice overlap/conflict risk before merge and blocks merge when overlap is unresolved.

## Commit Ownership Model
- Primary implementation agent commits slice code.
- Review-only agents provide findings/evidence and do not commit code.
- Orchestrator commits orchestration/policy/docs artifacts unless it explicitly takes slice ownership.
- A commit is valid only after review evidence is attached.
- Merge is valid only when scope-boundary checks pass for the primary implementation agent.
- Merge is blocked if two different implementation slices overlap on the same files unless consolidated in a single coordinated slice.
- Post-merge, orchestrator may remove merged slice worktrees according to `agents/orchestrator-layer.yaml` automation policy.

## Cross-Module Contract
- The orchestrator enforces order: contracts -> producer -> consumers -> orchestrator.
- New dependency edges require allowlist + ADR evidence.

## Proof-First Decisions
- Decision quality is evidence-backed (tests/guards/traces), never assumption-backed.
- If evidence is missing, slice status remains blocked.

## Human Escalation Boundary
- Human escalation is required only for irreversible production actions (R3).
- R1/R2 decisions are owned by orchestrator when proof pack is complete.

## Unknowns and TODOs
- Auto-trigger mechanism for subagent spawning from CI events is unspecified.
  - TODO: define event adapter if orchestrator will run outside interactive Codex sessions.
