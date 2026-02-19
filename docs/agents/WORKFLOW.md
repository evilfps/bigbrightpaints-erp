# Agent Workflow and Lifecycle

Last reviewed: 2026-02-18
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
