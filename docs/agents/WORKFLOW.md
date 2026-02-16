# Agent Workflow and Lifecycle

Last reviewed: 2026-02-16
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
- Ticket orchestration and tmux dispatch are driven by `scripts/harness_orchestrator.py`.

## Ticketed Worktree Flow (Harness Engineering)
1. Create ticket and slices:
- `python3 scripts/harness_orchestrator.py bootstrap --title "<title>" --goal "<goal>" --paths "<path1,path2>"`
2. Dispatch tmux command block:
- `python3 scripts/harness_orchestrator.py dispatch --ticket-id <TKT-ID>`
3. Run workers in isolated worktrees:
- each lane opens its assigned worktree and executes the generated task packet in the worktree harness folder.
4. Record reviewer outcomes:
- `python3 scripts/harness_orchestrator.py review --ticket-id <TKT-ID> --slice-id <SLICE-ID> --reviewer <agent-id> --status approved|changes_requested|blocked`
5. Verify and merge loop:
- verify only: `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID>`
- verify + merge: `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID> --merge`
- merge-time worktree cleanup (default from orchestrator-layer): `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID> --merge --cleanup-worktrees`
- keep worktrees when needed: `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID> --merge --no-cleanup-worktrees`
- orchestrator review artifact per slice: `tickets/<TKT-ID>/slices/<SLICE-ID>/orchestrator-review.md`

## Codex Exec Command Standard
- Canonical non-interactive full-access execution command:
  - `codex exec -m gpt-5.3-codex -c reasoning_effort="<xhigh|high|medium>" --dangerously-bypass-approvals-and-sandbox`
- `codex exec --help` documents the canonical full-access flag and may not list `--yolo`.
- Treat `--yolo` as compatibility alias only; keep the canonical full-access flag in orchestrator command templates.
- Always set model and reasoning effort explicitly for orchestrated execution agents.

## Throughput While Workers Are Active
- Orchestrator does not wait for idle periods to plan follow-up work.
- While workers execute slices, orchestrator must keep at least 2-3 next tickets preplanned and ready.
- If ready backlog drops below 2, orchestrator immediately prepares the next goal-aligned ticket from `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md`.
- Permission expansions for blocked slices remain task-bound and must follow `docs/agents/PERMISSIONS.md` evidence and monitoring rules.

## Scope Boundary Enforcement
- Module agents may read broadly for context, but merge eligibility is blocked if a slice branch edits files outside that agent's `scope_paths`.
- Scope compliance is checked during ticket verify runs and logged per slice.
- Reviewer agents remain review-only and must not commit implementation code.
- Cross-slice overlap detection is enforced for different implementation agents. If two slices touch the same files, status becomes `coordination_required` until orchestrator consolidates/replans.

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
- enforce at least one reviewer agent per slice (orchestrator layer rule)
- docs-only exception: reviewer-agent and commit-review steps can be skipped when no runtime/config/schema/test files changed; require `bash ci/lint-knowledgebase.sh` pass evidence.

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
