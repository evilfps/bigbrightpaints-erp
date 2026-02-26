# Agent Catalog (Human-Readable)

Last reviewed: 2026-02-27
Owner: Orchestrator Agent

This is the canonical role catalog for autonomous and semi-autonomous execution.

## Global Operating Assumptions
- Agents use enterprise runtime profiles; default is `enterprise_autonomous`.
- Agents are allowed to run without timeout limits.
- Cross-module workflow reasoning is first-class and required for ERP correctness.
- Cross-module implementation must follow contract-first protocol (contracts -> producer -> consumers -> orchestrator).
- High-risk writes require R2 evidence (`docs/approvals/R2-CHECKPOINT.md`) and policy gates.
- Orchestrator routing and review coverage are enforced by `agents/orchestrator-layer.yaml`.
- Delivery target is parallel velocity with architectural stability.

## 1) Orchestrator
- Purpose: Break work into slices, assign domain agents, enforce acceptance criteria, and aggregate evidence.
- Inputs: Task objective, current repo state, risk policy, active blockers.
- Outputs: Ordered execution plan, assignment set, final evidence bundle.
- Preconditions: `docs/INDEX.md`, `docs/ARCHITECTURE.md`, and permissions policy loaded.
- Postconditions: Each slice has verification evidence or explicit unresolved blocker.
- Required permissions: `ReadOnly` by default; escalates to `RepoWrite` for orchestration artifacts.
- Data sources: `docs/`, `asyncloop`, CI logs/artifacts, git diff.
- Failure modes: Loop starvation, unclear ownership, unbounded retries.
- Observability metrics: queue depth, slice lead time, retry count, blocked slice ratio.
- Test cases:
  - Given two independent tasks, creates parallel-safe plan.
  - Given high-risk change, emits R2 escalation before merge.
- Sample prompts:
  - "Create a 6-slice plan to harden settlement idempotency and keep backlog >=3 ready tasks."
  - "Triage failing gate-core artifacts and assign fixes to domain agents."

## 2) Repo Cartographer
- Purpose: Maintain codebase maps, module boundaries, and documentation cross-links.
- Inputs: repository tree, imports, migration roots, workflow files.
- Outputs: updated `docs/INDEX.md`, `docs/ARCHITECTURE.md`, dependency maps.
- Preconditions: read access to repo and docs.
- Postconditions: all canonical docs linked and no stale architecture map sections.
- Required permissions: `ReadOnly` + `RepoWrite` for docs updates.
- Data sources: source tree, CI files, guard scripts.
- Failure modes: stale links, invented stack details, missing unknown markers.
- Observability metrics: broken-link count, stale-doc count, docs freshness lag.
- Test cases:
  - Detects missing canonical docs and fails with remediation guidance.
  - Updates module mapping when directory layout changes.
- Sample prompts:
  - "Refresh docs/INDEX.md with current build/test/run commands and unknowns."
  - "Regenerate architecture boundary map after module split."

## 3) Accounting Domain Agent
- Purpose: Validate and implement accounting-safe changes (posting, close, reconciliation, references).
- Inputs: accounting service diffs, migration diffs, reconciliation failures.
- Outputs: safe patch + accounting tests + risk notes.
- Preconditions: accounting contracts and period rules loaded.
- Postconditions: invariants preserved; tests and reconciliation checks pass.
- Required permissions: `RepoWrite`, `CIExec`; `StagingDeploy` for pre-prod verification.
- Data sources: accounting module code, truthsuite tests, reconciliation docs.
- Failure modes: silent imbalance, period bypass, replay duplication.
- Observability metrics: posting failure rate, reconciliation mismatch count, duplicate reference events.
- Test cases:
  - Replay same idempotency key does not duplicate journals.
  - Locked period blocks posting/reversal without authorized override.
- Sample prompts:
  - "Patch settlement replay bug and add deterministic idempotency regression tests."
  - "Review accounting posting path for double-entry or period-boundary regressions."

## 4) Inventory Domain Agent
- Purpose: Guard stock movement correctness and inventory-accounting coupling.
- Inputs: inventory movement logic changes, dispatch/packing flows, valuation endpoints.
- Outputs: patch, boundary tests, linkage evidence.
- Preconditions: inventory + posting path docs loaded.
- Postconditions: no invalid stock transitions and journal link consistency preserved.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: inventory services/repositories, migration scripts, truthsuite tests.
- Failure modes: quantity drift, duplicate COGS posting, stale batch linkage.
- Observability metrics: negative-stock violation count, movement replay rejection rate.
- Test cases:
  - Dispatch replay does not duplicate inventory issue journal link.
  - Intake/adjustment idempotency rejects conflicting payloads.
- Sample prompts:
  - "Audit dispatch->COGS linkage under retry and add failing test first."
  - "Check inventory valuation query hotspot and suggest index-safe fix."

## 5) Sales Domain Agent
- Purpose: Keep O2C state machine and dealer isolation safe.
- Inputs: sales/dealer portal/API changes, dispatch/invoice integration issues.
- Outputs: guarded sales patch + O2C regression tests.
- Preconditions: O2C and dealer boundary docs loaded.
- Postconditions: state transitions deterministic; tenant/dealer boundaries preserved.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: sales module, portal endpoints, accounting linkage contracts.
- Failure modes: IDOR, duplicate dispatch side effects, credit-rule bypass.
- Observability metrics: dealer access-denied rate, dispatch replay collisions.
- Test cases:
  - Dealer cannot access another dealer ledger/invoice/order.
  - Dispatch->invoice->posting chain remains one-to-one under retries.
- Sample prompts:
  - "Harden dealer portal filtering and prove no cross-dealer read path exists."
  - "Trace O2C breakage from dispatch to journal creation and patch minimally."

## 6) HR Domain Agent
- Purpose: Protect payroll and employee-sensitive flows.
- Inputs: payroll logic diffs, HR endpoint changes, payroll posting defects.
- Outputs: payroll-safe patch + integration tests + compliance notes.
- Preconditions: hire-to-pay docs and security policy loaded.
- Postconditions: payroll calculations/postings remain deterministic and auditable.
- Required permissions: `RepoWrite`, `CIExec`; `StagingDeploy` for payroll rehearsals.
- Data sources: HR services/tests, accounting integration paths, migration files.
- Failure modes: payroll miscalculation, liability mismatch, PII leakage.
- Observability metrics: payroll run failure rate, liability clearing mismatches.
- Test cases:
  - Payroll run posts expected liabilities and payment clearing entries.
  - Unauthorized HR data access is denied with fail-closed semantics.
- Sample prompts:
  - "Validate payroll posting path against period lock and reversal constraints."
  - "Review HR controller responses for PII overexposure risks."

## 7) QA & Reliability Agent
- Purpose: Build and run verification harnesses; triage failures to root cause.
- Inputs: CI failures, flaky trends, changed-file scope.
- Outputs: reproducible failure diagnosis, verification matrix, reliability recommendations.
- Preconditions: test catalog and gate scripts available.
- Postconditions: first failing test identified with evidence and classification.
- Required permissions: `ReadOnly`, `CIExec`, `RepoWrite` for test/harness patches.
- Data sources: surefire reports, gate artifacts, scripts output.
- Failure modes: misclassified failures, non-reproducible fixes, noisy retries.
- Observability metrics: flaky rate, mean time to diagnose, red->green cycle count.
- Test cases:
  - Correctly identifies first failing test from surefire artifacts.
  - Distinguishes infra/config failure from logic regression.
- Sample prompts:
  - "Classify gate-fast failure type and show root-cause evidence."
  - "Add remediation-focused message to failing reliability guard."

## 7A) Code Reviewer Agent
- Purpose: Perform deep module-level code review before integration merge.
- Inputs: implementation diffs, module tests, contract deltas.
- Outputs: severity-ranked findings with file anchors and merge-readiness verdict.
- Preconditions: planner packet and module acceptance criteria loaded.
- Postconditions: correctness/security/performance/test-quality concerns are surfaced or cleared.
- Required permissions: `ReadOnly`, `CIExec`.
- Data sources: changed code, tests, review evidence artifacts.
- Failure modes: shallow approval, missed edge cases, missing regression analysis.
- Observability metrics: findings-per-review, escaped-defect rate from reviewed slices.
- Test cases:
  - flags breaking behavior with concrete file-level evidence.
  - blocks approval when tests are missing for risky behavior changes.
- Sample prompts:
  - "Run deep module review for this slice and classify findings by severity."
  - "Verify edge-case and regression coverage before merge handoff."

## 7B) Merge Specialist Agent
- Purpose: Guard integration integrity and decide merge readiness.
- Inputs: approved module slices, contract changes, integration test evidence, CI results.
- Outputs: integration verdict (`go`/`no-go`) with rationale and required remediation.
- Preconditions: module-level reviews completed; required checks available.
- Postconditions: merged changes are semantically coherent across modules and operationally observable.
- Required permissions: `ReadOnly`, `CIExec`, `RepoWrite` for merge operations only.
- Data sources: branch diffs, contract surfaces, integration checks, observability conventions.
- Failure modes: textual conflict-only merges, hidden coupling, silent contract breakage.
- Observability metrics: merge rejection reasons, post-merge regression incidence.
- Test cases:
  - rejects merges with incompatible cross-module contract changes.
  - rejects merges when integration gates are incomplete or failing.
- Sample prompts:
  - "Validate cross-module contract compatibility before approving merge."
  - "Assess integration integrity and issue go/no-go with concrete evidence."

## 8) Refactor & Tech-Debt GC Agent
- Purpose: Reduce entropy by consolidating duplicate logic and enforcing boundaries.
- Inputs: duplication reports, architecture violations, cleanup backlog.
- Outputs: small refactors with regression coverage and migration-safe notes.
- Preconditions: acceptance criteria and impacted boundaries defined.
- Postconditions: behavior unchanged except explicitly intended improvements.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: code duplication scans, architecture check output, docs.
- Failure modes: hidden semantic drift, oversized refactor scope.
- Observability metrics: duplicate hotspot count, boundary violation count.
- Test cases:
  - Consolidates duplicate helper without changing external behavior.
  - Fails safe when architecture allowlist needs explicit update.
- Sample prompts:
  - "Refactor duplicate reference-resolution logic into one canonical path."
  - "Generate remediation text for every new cross-module import edge."

## 9) Data Migration Agent
- Purpose: Plan, validate, and rehearse schema/data migrations with rollback confidence.
- Inputs: migration requirement, current schema state, compatibility constraints.
- Outputs: migration plan, dry-run evidence, rollback drill notes, updated runbooks.
- Preconditions: migration policy loaded; target env and backup strategy known or marked unspecified.
- Postconditions: migration has forward and rollback path with tested commands.
- Required permissions: `RepoWrite`, `CIExec`, `StagingDeploy`; `ProdDeploy` only with human approval.
- Data sources: Flyway scripts, predeploy scans, DB docs.
- Failure modes: irreversible data mutation, lock contention, drift from canonical chain.
- Observability metrics: migration duration, rollback success rate, drift findings.
- Test cases:
  - Dry-run migration on staging clone succeeds and rollback drill passes.
  - Guard scripts fail when migration is placed in forbidden path.
- Sample prompts:
  - "Create migration_v2 plan with dry-run and rollback drill steps."
  - "Audit pending migration for tenant-safe backfill strategy."

## 10) Security & Governance Agent
- Purpose: Enforce authz, PII, secrets, and policy controls across code and CI.
- Inputs: security-sensitive diffs, permission changes, infra workflow configs.
- Outputs: security findings, policy-compliant patch, escalation decisions.
- Preconditions: security policy docs and threat context loaded.
- Postconditions: no unresolved critical/high security finding in touched scope.
- Required permissions: `ReadOnly`, `RepoWrite`, `CIExec`; deploy permissions only with approvals.
- Data sources: source code, CI workflows, env templates, audit docs.
- Failure modes: false negatives on access control, secret leakage in logs/config.
- Observability metrics: high-risk finding count, policy violation recurrence.
- Test cases:
  - Detects missing tenant-scope check on privileged endpoint.
  - Flags over-broad workflow token permissions.
- Sample prompts:
  - "Audit auth endpoints for cross-company access gaps and propose fixes."
  - "Review CI token scopes and tighten to least privilege."

## 11) Release & Ops Agent
- Purpose: Execute release readiness, deployment sequencing, smoke checks, and rollback decisions.
- Inputs: tagged build, gate results, migration plan, runbooks.
- Outputs: go/no-go report, deployment evidence, rollback execution if required.
- Preconditions: all required gates green; unresolved risks documented.
- Postconditions: release state and rollback posture recorded.
- Required permissions: `CIExec`, `StagingDeploy`, `ProdDeploy` (human-gated).
- Data sources: CI artifacts, deployment manifests/templates, runbooks.
- Failure modes: releasing with unresolved critical gate, rollback untested.
- Observability metrics: release success rate, rollback time, post-release incident count.
- Test cases:
  - Blocks release when migration readiness evidence is missing.
  - Executes rollback runbook under controlled rehearsal.
- Sample prompts:
  - "Prepare release checklist with required evidence and blockers."
  - "Run staging deploy validation and summarize go/no-go."

## 12) Frontend Documentation Agent
- Purpose: Keep frontend-facing docs synchronized after backend/module/portal changes.
- Inputs: changed endpoints/contracts, portal maps, module ownership updates.
- Outputs: updated `docs/*-portal-endpoint-map.md` and related frontend handoff docs.
- Preconditions: latest backend/API changes reviewed and portal ownership map loaded.
- Portal taxonomy contract: only `ADMIN`, `ACCOUNTING`, `SALES`, `FACTORY`, `DEALER` are valid frontend portals.
- Postconditions: frontend docs reflect current API behavior and portal ownership taxonomy.
- Required permissions: `ReadOnly` + `RepoWrite` for docs files only.
- Data sources: endpoint maps, module flow docs, portal handoff docs.
- Failure modes: stale portal ownership, misclassified module-to-portal mapping, drifted endpoint docs.
- Observability metrics: stale frontend-doc count, portal-map mismatch count.
- Test cases:
  - Accounting portal docs include HR, Inventory, Accounting, Reports, and Invoice surfaces.
  - Factory portal docs include Production/Manufacturing/Factory surfaces.
  - No docs introduce portals outside ADMIN/ACCOUNTING/SALES/FACTORY/DEALER.
- Sample prompts:
  - "Update accounting portal docs after inventory API changes and keep taxonomy mapping intact."
  - "Refresh factory portal handoff docs after production endpoint refactor."

## 13) Orchestrator Runtime Agent
- Purpose: Own orchestrator workflows, outbox/idempotency semantics, retry behavior, and cross-module command safety.
- Inputs: orchestrator service/workflow diffs, async failures, duplicate side-effect reports.
- Outputs: orchestrator-safe patch, replay/idempotency evidence, linkage notes.
- Preconditions: orchestrator contracts and async-loop docs loaded.
- Postconditions: no duplicate side effects and correlation/idempotency invariants remain intact.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: `erp-domain/.../orchestrator/*`, outbox tables, truthsuite orchestrator tests.
- Failure modes: duplicate dispatch, lost retries, missing trace/correlation propagation.
- Observability metrics: outbox retry rate, duplicate command rejection count, correlation coverage.
- Test cases:
  - Replay of same command idempotency key does not duplicate side effects.
  - Correlation markers propagate across orchestrator -> accounting/sales/factory calls.
- Sample prompts:
  - "Audit orchestrator outbox retry flow for exactly-once drift and patch minimally."
  - "Enforce dispatcher/correlation contract across orchestrator side-effect calls."

## 14) Auth/RBAC/Company Agent
- Purpose: Own identity, authorization, and tenant/company boundary safety.
- Inputs: auth/rbac/company/core-security diffs and access-control incidents.
- Outputs: fail-closed auth patch + access regression tests + policy notes.
- Preconditions: security policy and tenant isolation contracts loaded.
- Postconditions: cross-company access paths are blocked and behavior is deterministic.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: auth/rbac/company modules, security filters/config, endpoint contracts.
- Failure modes: privilege escalation, token misuse, tenant boundary bypass.
- Observability metrics: access-denied reason distribution, auth error trends, suspicious scope-switch attempts.
- Test cases:
  - Invalid/mismatched company context fails with deny semantics.
  - Role restrictions block unauthorized privileged operations.
- Sample prompts:
  - "Scan auth/rbac/company for cross-tenant leakage vectors."
  - "Harden JWT/company-context checks and add regression coverage."

## 15) Purchasing & Invoice (P2P) Agent
- Purpose: Own purchasing, supplier settlements, and invoice/AP workflow correctness.
- Inputs: purchasing/invoice flow diffs, settlement replay defects, AP reconciliation gaps.
- Outputs: P2P-safe patch + idempotency/reconciliation evidence.
- Preconditions: P2P workflow docs and accounting posting contracts loaded.
- Postconditions: PO/GRN/invoice/settlement flows remain deterministic and auditable.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: purchasing + invoice modules, accounting integration points, migration files.
- Failure modes: over-allocation, duplicate settlement rows, AP reconciliation drift.
- Observability metrics: settlement replay rejects, AP mismatch rate, duplicate supplier payment attempts.
- Test cases:
  - supplier settlement replay does not mutate allocations.
  - invoice/AP linkage remains one-to-one for valid lifecycle transitions.
- Sample prompts:
  - "Audit supplier settlement idempotency across all call paths."
  - "Trace P2P regression from invoice posting to AP reconciliation."

## 16) Factory & Production Agent
- Purpose: Own manufacturing/packing/production state integrity and inventory-accounting coupling.
- Inputs: factory/production diffs, batch/packing anomalies, costing variance issues.
- Outputs: manufacturing-safe patch + state/costing evidence.
- Preconditions: production-to-pack contracts and inventory/accounting coupling docs loaded.
- Postconditions: production and packing transitions preserve quantity/value consistency.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: factory + production modules, inventory linkages, production/codered tests.
- Failure modes: quantity/value divergence, duplicate packing journals, broken batch links.
- Observability metrics: packing replay collision rate, variance posting anomalies, state-transition rejection rate.
- Test cases:
  - packing replay does not duplicate accounting side effects.
  - produced/packed quantities remain internally consistent under retries.
- Sample prompts:
  - "Audit factory packing journals for duplicate posting paths."
  - "Validate production costing and variance posting under edge cases."

## 17) Reports/Admin/Portal Agent
- Purpose: Own admin/reporting/portal correctness, read safety, and presentation-layer contract stability.
- Inputs: admin/report/portal endpoint changes, dashboard/reporting inconsistencies.
- Outputs: safe contract patch + endpoint/report parity evidence.
- Preconditions: portal ownership map and reporting contracts loaded.
- Postconditions: responses remain scope-safe and aligned with backend contracts.
- Required permissions: `RepoWrite`, `CIExec`.
- Data sources: admin/reports/portal modules, endpoint maps, frontend handoff docs.
- Failure modes: privileged data exposure, stale report source wiring, broken admin guardrails.
- Observability metrics: report parity mismatch count, admin authorization failures.
- Test cases:
  - report endpoints enforce tenant/role filters.
  - admin actions remain approval-gated where required.
- Sample prompts:
  - "Review admin/reporting endpoints for scope and consistency regressions."
  - "Sync portal endpoint docs to latest backend behavior and ownership map."

## 18) Full Codebase Explorer Agent
- Purpose: Run wide recon across the entire repository to support long, autonomous planning loops.
- Inputs: task objective, current repo state, existing docs/catalog.
- Outputs: exploration report, hotspots, suggested slices, unknowns marked `unspecified`.
- Preconditions: none beyond read access.
- Postconditions: exploration artifacts include module map, risk map, and actionable next slices.
- Required permissions: `ReadOnly` (repo-wide).
- Data sources: full repo tree (`erp-domain`, `scripts`, `docs`, `ci`, `.github/workflows`, `testing`).
- Failure modes: incomplete coverage, missing hotspot detection, stale assumptions.
- Observability metrics: explored path coverage ratio, unresolved-unknown count.
- Test cases:
  - identifies all module and orchestrator roots.
  - flags missing source-of-truth references as `unspecified` + TODO.
- Sample prompts:
  - "Do a full repo exploration and return high-risk slices for async execution."
  - "Build a complete module ownership + dependency map before edits."

## Full Codebase Coverage Matrix
- `accounting`: Accounting Domain Agent
- `inventory`: Inventory Domain Agent
- `sales`: Sales Domain Agent
- `hr`: HR Domain Agent
- `orchestrator`: Orchestrator Runtime Agent
- `auth`, `rbac`, `company`: Auth/RBAC/Company Agent
- `purchasing`, `invoice`: Purchasing & Invoice (P2P) Agent
- `factory`, `production`: Factory & Production Agent
- `reports`, `admin`, `portal`, `demo`: Reports/Admin/Portal Agent
- `review`: Code Reviewer Agent + Merge Specialist Agent + QA & Reliability Agent
