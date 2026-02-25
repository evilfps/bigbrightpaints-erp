# Agent Workflow and Lifecycle

Last reviewed: 2026-02-24
Owner: Orchestrator Agent

## Lifecycle
1. Creation
- Define objective, ownership scope, risk tier, done checks, escalation triggers.
- Register in `agents/catalog.yaml` and `agents/*.agent.yaml`.
- Ensure routing and review mapping exists in `agents/orchestrator-layer.yaml`.

2. Skills
- Use skills for repeated tasks to keep context compact.
- Keep skills procedural, testable, and reversible.

3. Recon and Planning
- Start in `read_only_recon` when scope/risk is unclear.
- Build slice plan with acceptance criteria and risk tags.

4. Implementation
- Run in `enterprise_autonomous` (or migration/release profile when needed).
- Keep slices small; preserve cross-module contract order.

5. Validation
- Run cheapest checks first, then broader harness.
- Failures must include remediation-oriented messages.

6. Release Promotion
- CI -> staging -> production.
- Production actions remain checkpointed.

7. Monitoring and Feedback
- Track retries, blocker rate, incident escapes, and drift.
- Convert repeated failures into skills/checks/docs updates.

## Runtime Model for Long-Running Flows
- No timeout limits by default.
- Full repository exploration allowed.
- Write operations are risk-aware and mechanically guarded.
- Async continuity is maintained in `asyncloop` and async runbooks.
- Orchestrator dispatch and review sequencing are defined in `agents/orchestrator-layer.yaml`.
- Main orchestrator loop continues until human sends exact `STOP`.

## Ticket Claim Protocol (Mandatory)
Before any implementation edits, worker agents must claim the slice:
1. Set slice status to `taken` in `tickets/<TKT-ID>/ticket.yaml`.
2. Append claim line in `tickets/<TKT-ID>/TIMELINE.md` with:
- agent id
- slice id
- branch
- worktree
- UTC timestamp
3. Move to `in_progress` only after claim is recorded.
4. Release claim by moving to `in_review`, `done`, or `blocked` with evidence note.

Claim collisions are merge-blocking. Unclaimed implementation submissions are rejected.

## Clone and Worktree Baseline
- Canonical base branch: `harness-engineering-orchestrator` (unless ticket overrides).
- Always refresh base before new worktree:
  - `git fetch origin`
  - `git checkout harness-engineering-orchestrator`
  - `git pull --ff-only origin harness-engineering-orchestrator`
- Create one worktree per agent per slice under `../orchestrator_erp_worktrees/<TKT-ID>/`.
- Never branch new slice worktrees from older slice branches.

## Base Branch Protection (Mandatory)
- Do not implement directly on `harness-engineering-orchestrator`, `main`, or `master`.
- Implementation is allowed only in claimed ticket branches (`tickets/<tkt-id>/<agent-id>`) and assigned worktrees.
- If current branch/worktree does not match the task packet assignment, stop and mark the slice blocked.
- Base branches are integration-only: merge/push after required checks and review evidence.

## Codebase Impact Analysis (Mandatory)
Every implementation submission must include `codebase_impact_analysis` with:
- upstream dependencies and contracts consumed
- downstream modules/services/portals potentially affected
- API/event/schema/test surface touched or intentionally unchanged
- why non-goal modules are unaffected (or what follow-up is required)

## Subagent Role Policy
Use Codex multi-agent role configuration for role/risk selection:
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

If a preferred runtime profile is unavailable, orchestrator must document the fallback role/profile choice in ticket timeline.

## Required Execution Order (Non-Doc Slices)
For non-doc implementation tickets, orchestrator must run this sequence:
1. `planning` (deep analysis + stepwise implementation plan)
2. implementation slice agents in parallel worktrees
3. `merge-specialist` integration/conflict-resolution evidence on ticket branches
4. `code_reviewer` review evidence on each implementation slice
5. `qa-reliability` cross-workflow regression and gate evidence
6. `release-ops` docs/release-readiness sync before final merge

Order is merge-blocking for release readiness.

## Agent-ID to Role Mapping
- Existing YAML agent IDs are mapped to custom multi-agent roles in:
  - `agents/orchestrator-layer.yaml` -> `runtime.subagents.agent_role_mapping`
  - `.codex/config.toml` -> `[agents.<role-or-id>]` tables
- `scripts/harness_orchestrator.py` injects role/config metadata into each generated `tickets/<TKT-ID>/slices/<SLICE-ID>/TASK_PACKET.md`.

## Cross-Module Contract-First Protocol
When touching multiple domains, use this order:
1. contracts/events/interfaces
2. producer module
3. consumer module(s)
4. orchestrator integration
5. architecture and policy checks

Do not reorder unless an ADR explains why.

## Enterprise Policy Gates (Near Deployment)
- `bash ci/check-enterprise-policy.sh`
- `bash ci/check-architecture.sh`
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-orchestrator-layer.sh`
- `bash scripts/verify_local.sh` (when code changes require broader validation)

High-risk deltas (auth/payroll/ledger/migrations/permissions/destructive ops) require:
- `docs/approvals/R2-CHECKPOINT.md`
- explicit rollback ownership
- proof-first verification evidence (tests/guards/traces), not assumption

## CI/CD Integration Points
- `.github/workflows/ci.yml`
- `.github/workflows/doc-lint.yml`
- `.github/workflows/codex-review.yml`
- `.github/workflows/codex-autofix.yml`

## Automated Review Contract
- classify severity
- map finding to file/invariant
- suggest minimal remediation
- attach evidence (test/guard output)
- enforce at least one reviewer agent per slice (orchestrator layer rule), except lane-qualified docs-only slices.
- lane-qualified docs-only review skip policy:
  - `fast_lane` docs-only slices (non-strict docs changes only): skip reviewer-agent + commit-review; require `bash ci/lint-knowledgebase.sh`.
  - `strict_lane` control-plane docs slices (`docs/agents/`, `docs/ASYNC_LOOP_OPERATIONS.md`, `docs/system-map/REVIEW_QUEUE_POLICY.md`, `agents/orchestrator-layer.yaml`, `asyncloop`, `scripts/harness_orchestrator.py`, `ci/`): reviewer-agent + commit-review may be skipped only when no runtime/config/schema/test files changed, and the strict guard trio must pass:
    - `bash ci/lint-knowledgebase.sh`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
- runtime/config/schema/test changes never qualify for docs-only review skip.
- evaluate lane and required checks with `scripts/harness_orchestrator.py`.
- implementation submissions are incomplete without `ticket_claim_evidence`, `worktree_validation`, and `codebase_impact_analysis`.
- `qa-reliability` is mandatory on non-doc slices and must validate cross-workflow behavior after code-review approvals.
- reviewer approvals are valid only for the current branch head SHA (stale approvals are rejected).
- QA approval timestamp must be after code-review approval timestamps on non-doc slices.

Frontend doc changes must preserve portal ownership taxonomy:
- Accounting Portal: accounting + inventory + hr + reports + invoice
- Factory Portal: factory + production + manufacturing

## Rollback and Migration Controls
Application rollback:
- keep previous artifact/tag
- revert deployment target
- run smoke/invariant checks

Database rollback:
- prefer forward-fix where destructive rollback is unsafe
- run migration drill before production
- maintain `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md`

## Deployment Constraints
Detected local/runtime baseline: Docker Compose (`docker-compose.yml`).
Kubernetes target: no specific constraint detected.

## Decision Checkpoints
- R1: ambiguous/conflicting intent (orchestrator decides when evidence is sufficient).
- R2: high-risk semantic change (orchestrator approves when proof pack is complete).
- R3: irreversible production actions require human go/no-go.

## Unknowns and TODOs
- Authoritative production orchestrator/pipeline is unspecified.
  - TODO: link definitive pipeline docs and replace templates.
- Pager severity matrix is unspecified.
  - TODO: define in `docs/RELIABILITY.md` and on-call runbooks.
