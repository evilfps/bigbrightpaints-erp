# Enterprise Backend Stabilization Plan (Canonical)

Last reviewed: 2026-02-15
Owner: Orchestrator Agent
Status: Active

This is the canonical control-plane entrypoint for making the backend enterprise-stable and deployable under AI-heavy development.
It does not replace detailed technical docs. It defines execution order, hard gates, and completion evidence.

## Relationship to Existing Plans
- `docs/ERP_STAGING_MASTER_PLAN.md` is the final stability-first staging plan and priority source for scope decisions.
- This file is the orchestration layer for planning and execution priority.
- `docs/CODE-RED/stabilization-plan.md` and `docs/CODE-RED/plan-v2.md` remain the detailed technical backlog and implementation history.
- If conflicts appear:
  - release/safety gates come from `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`
  - blocker status comes from `docs/CODE-RED/P0_DEPLOY_BLOCKERS.md`
  - live execution order comes from `asyncloop`

## Scope
- Backend correctness and stability only.
- Accounting trust, workflow invariants, security boundaries, and deployment safety.
- Duplicate code/process/doc elimination for critical paths.
- Frontend contract stability through OpenAPI-backed API contracts.

## Baseline (Repo-Evidenced)
- Test source files: `259`.
- Test annotations detected: `1170` (`@Test` and peers).
- Truthsuite/codered test files: `72`.
- CI gate workflows detected in `.github/workflows/ci.yml`:
  - knowledgebase lint
  - architecture check
  - enterprise policy check
  - orchestrator layer check
  - `gate-fast`, `gate-core`, `gate-release`, `gate-reconciliation`, `gate-quality`
- Flyway v2 is canonical:
  - migrations: `erp-domain/src/main/resources/db/migration_v2`
  - table: `flyway_schema_history_v2`

## Non-Negotiable Rules
- New schema changes only in Flyway v2 path.
- No controller/service write-path duplication for the same business event without an explicit alias policy.
- Every risk-bearing change requires proof pack:
  - targeted tests
  - relevant guard scripts
  - `asyncloop` evidence entry
- No deploy claim without zero unresolved P0 blockers in `docs/CODE-RED/P0_DEPLOY_BLOCKERS.md`.

## Canonical Sources
- Live execution ledger: `asyncloop`
- Final scope plan: `docs/ERP_STAGING_MASTER_PLAN.md`
- Deep implementation blueprint: `docs/ERP_ENTERPRISE_DEPLOYMENT_DEEP_SPEC.md`
- Release gate and blocker truth:
  - `docs/CODE-RED/P0_DEPLOY_BLOCKERS.md`
  - `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`
  - `docs/CODE-RED/DEDUPLICATION_BACKLOG.md`
- Migration policy and runbooks:
  - `docs/db/MIGRATION_V2_WORKFLOW.md`
  - `docs/runbooks/migrations.md`
  - `docs/runbooks/rollback.md`

## AI Agent Operating Model (Required)
1. Pick one slice from `asyncloop` (`in_progress` first, then top `ready`).
2. Define invariant and abuse case before editing.
3. Patch minimally in contract-first order:
   - contracts/events
   - producer
   - consumers
   - orchestrator
4. Run smallest proving harness first, then broaden only as needed.
5. For large slices, split bounded implementation work across worker agents in parallel; keep one orchestrator owner.
6. Commit.
7. For runtime/config/schema/test logic commits: run commit review and one review subagent (minimum).
8. For docs-only commits: skip commit-review/subagent and run `bash ci/lint-knowledgebase.sh`.
9. Fix review findings immediately (for reviewed commits).
10. Append evidence + queue rotation in `asyncloop`.

## Program Phases

### Phase 0: Control-Plane Deduplication
Objective: one source of truth for plan, blockers, and release gates.

Deliverables:
- This document is canonical and linked from `docs/INDEX.md`.
- `asyncloop` queue references this phase set.
- Prevent duplicate planning drift by requiring new long-lived plans to link from this file.

Exit criteria:
- Agents can discover plan path from `AGENTS.md` -> `docs/INDEX.md` -> this document.
- No conflicting active plan identifiers for the same risk area.

### Phase 1: API Contract Canonicalization
Objective: stop frontend/backend drift and duplicate endpoint semantics.

Deliverables:
- OpenAPI contract is generated/validated in CI for release lanes.
- Canonical endpoint map per portal with alias policy (canonical vs compatibility endpoint).
- Contract tests for high-risk write endpoints (dispatch, settlement, payroll, period close).

Exit criteria:
- Contract diff check in CI blocks undocumented breaking changes.
- Frontend handoff docs derive from one scriptable source (`openapi.json` + policy overlays).

### Phase 2: Duplicate Write-Path Elimination
Objective: exactly one canonical write path per business event.

Deliverables:
- Update and execute `docs/CODE-RED/DEDUPLICATION_BACKLOG.md` as migration slices.
- For each duplicate path: canonical path, alias behavior, and production gating documented and tested.
- Add guards for forbidden direct write shortcuts where drift risk is high.

Exit criteria:
- No reachable alternate path can bypass accounting/period/idempotency invariants.
- Alias parity tests prove same outcomes or fail-closed semantics.

### Phase 3: Test Suite Rationalization
Objective: replace noisy breadth with deterministic confidence lanes.

Deliverables:
- Test lane taxonomy:
  - fast PR lane (high-signal contract/invariant tests)
  - core integration lane
  - release lane
  - reconciliation lane
  - nightly flake/soak lane
- Flake budget policy and quarantine workflow with expiry.
- Coverage of deploy-blocking invariants mapped to named tests.

Exit criteria:
- Release decision based on invariant coverage and flake budget, not raw test count.
- Known flaky tests either fixed or quarantined with owner+expiry.

### Phase 4: CI and Release Gate Hardening
Objective: make CI the mechanical enforcement of enterprise policy.

Deliverables:
- Required checks for merge and release are explicit and immutable in workflow policy.
- Gate artifacts include migration matrix, predeploy scan output, and invariant summaries.
- Guard scripts fail fast with actionable diagnostics.

Exit criteria:
- Same SHA can be proven across local verify, CI release gate, and staging rehearsal.
- Missing artifacts or mismatched SHA blocks promotion.

### Phase 5: Data and Migration Safety (Flyway v2)
Objective: eliminate schema drift and migration ambiguity.

Deliverables:
- Drift and overlap scans are mandatory in release lanes:
  - `bash scripts/schema_drift_scan.sh --migration-set v2`
  - `bash scripts/flyway_overlap_scan.sh --migration-set v2`
- Migration matrix rehearsal with fresh and upgrade paths.
- Checksum transition automation for known transient variants.

Exit criteria:
- v2 drift/overlap findings are zero in release rehearsal.
- Migration runbook has executable commands for repair/recovery with v2-scoped table/location.

### Phase 6: Deployment Readiness and Operational Evidence
Objective: no-go cannot be bypassed, rollback is practiced.

Deliverables:
- Predeploy checklist evidence attached to release SHA.
- Smoke and health checks with auth posture validated.
- Rollback drill evidence for application and migration incidents.

Exit criteria:
- `docs/CODE-RED/GO_NO_GO_CHECKLIST.md` is green with evidence links.
- Residual risks are explicitly accepted with owner and due date.

## Completion Definition (Enterprise-Ready Backend)
All conditions must be true:
- P0 blockers closed or safely prod-gated with tests.
- Canonical write-path ownership enforced for accounting-sensitive flows.
- Flyway v2 scans and migration matrix are clean.
- CI required lanes are green for release SHA.
- Staging rehearsal evidence includes predeploy scans, smoke checks, and rollback drill output.

## Immediate Next Slices (Execution Queue Seed)
- `M17-S1`: Canonical API contract gate (`openapi.json` drift check + portal map parity guard).
- `M17-S2`: Top 5 duplicate write-path closures from `docs/CODE-RED/DEDUPLICATION_BACKLOG.md`.
- `M17-S3`: Test lane signal cleanup (flake quarantine policy + release invariant map).
- `M17-S4`: CI artifact contract hardening (release SHA evidence bundle).
- `M17-S5`: Flyway v2 checksum transition automation in release pipeline.
- `M17-S6`: Staging deploy rehearsal runbook execution with evidence capture template.
- `M17-S7`: Virtual-accountant readiness contract (intent schema + simulation/approval chain, no autonomous posting).
