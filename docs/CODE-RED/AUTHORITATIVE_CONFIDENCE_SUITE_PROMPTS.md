# CODE-RED Prompt Pack: Authoritative Confidence Suite

Last updated: 2026-02-05

Purpose: deliver a five-lane confidence suite where release is blocked unless the
same immutable commit (SHA) passes all mandatory gates.

Core intent: build one authoritative "truth suite" from code-understanding first,
then wire that suite across five CI/release lanes.

This prompt pack is tailored to this repository:
- CI entrypoint: `.github/workflows/ci.yml`
- Local gate: `scripts/verify_local.sh`
- Predeploy DB scans: `scripts/db_predeploy_scans.sql`
- Release controls: `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`, `docs/CODE-RED/RELEASE_RUNBOOK.md`
- Debug workflow: `docs/codex-cloud-ci-debugging-plan.md`

## Truth-source policy (mandatory for all prompts)

Treat this task as if you are a new senior engineer onboarding from zero context.

Authoritative sources:
- Production code and schema in this repository (primary truth).
- CODE-RED docs under `docs/CODE-RED/**` (policy and release constraints).

Non-authoritative sources:
- Any non-CODE-RED documentation.
- Any existing tests (they may be useful hints, but are not truth).

Forbidden for this initiative:
- Reusing old test classes as gate evidence.
- Retagging old tests to satisfy gate membership.
- Claiming coverage from legacy test files.

Required behavior for every prompt:
- Explore repository code paths first and infer actual runtime flows.
- Produce a code-derived flow/evidence matrix for critical workflows:
  O2C, P2P, Payroll, Inventory, Manufacturing, Period Close, Reconciliation.
- Build or rewrite tests to reflect the discovered code truth and deploy policy.
- Prefer high-signal, deterministic truth tests over broad low-signal coverage.
- Keep release decision based on this truth suite plus strict safety scans.

## One authoritative truth suite (shared foundation)

All five gates must consume a single curated truth suite that defines deploy
readiness for this ERP. The suite should prove, at minimum:
- idempotency replay safety and mismatch conflict safety on critical writes
- posting boundary/canonical service enforcement
- tenant/company isolation on mutating and read-sensitive paths
- period-close lock behavior and closed-period snapshot immutability
- operational truth equals financial truth for core business flows

This suite does not need to be perfect. It must be strong enough to support an
enterprise "deploy-ready" decision without waivers.

### New-test requirement (non-optional)

Retagging/refactoring existing tests is not sufficient.

Using old tests is not allowed for truth-gate evidence.

Every implementation run must add **new** truth-suite assets:
- at least 12 new test classes across critical flows
- at least 3 new suite/aggregator entry points (fast/core/reconciliation views)
- at least 1 new shared assertion helper for accounting/cross-module invariants

No completion credit if these are missing, even if CI scripts are updated.

### Mandatory accounting and cross-module invariant depth

The truth suite must explicitly validate accounting math and cross-module
integrity at "release confidence" depth, including:
- double-entry balance invariants (debits == credits per journal and in aggregate)
- idempotent posting invariants (no duplicate financial effect on retries)
- mismatch-safe replay behavior (material payload mismatch fails closed)
- AR/AP subledger to GL control-account reconciliation
- inventory valuation and COGS linkage consistency with journal outcomes
- tax/GST and rounding determinism (including edge values and decimal handling)
- period-close invariants (locked-period posting block + snapshot immutability)
- end-to-end linkage invariants:
  order/slip/invoice/journal/ledger and GRN/purchase/invoice/payment/journal

Testing approach should match strong engineering practice used in mature teams:
- deterministic integration tests for core business flows
- invariant/property-oriented tests for accounting calculations
- concurrency/retry tests for exactly-once boundaries
- mutation/flakiness pressure on critical modules to verify test quality

## Enterprise-grade behavior (target operating model)

For this ERP, "working" is not "tests are green once." It is:
- Canonical mutation boundaries only (no bypass posting paths).
- Idempotency is replay-safe and mismatch-safe on all high-risk write paths.
- Closed periods are immutable for reporting (snapshot-truth for closed periods).
- Operational truth and accounting truth reconcile continuously.
- Release decisions are SHA-based, reproducible, and waiver-free for safety gates.

Current baseline is strong (CODE-RED scans + `mvn verify` + runbooks), but the
pipeline is still a single-lane verify model. The goal is to split confidence
into explicit lanes with independent failure signals and promotion rules, all
backed by the same truth suite.

## Definition of "App Is Working"

Only when one fixed SHA passes all mandatory lanes:
- `gate-fast` (PR)
- `gate-core` (main)
- `gate-release` (release SHA)
- `gate-reconciliation` (release SHA)
- plus staging smoke/health/soak checks and no open P0 blockers.

No safety-gate waivers for release.

## Prompt 1 (2-4h): Build `gate-fast` for PRs (critical invariants + changed-file coverage)

Use this prompt with an implementation agent:

```text
Implement a new CI lane named gate-fast for pull requests in this repository.
Design it as a <=15 minute "hard fail" lane focused on safety invariants only.

Context:
- Existing CI: .github/workflows/ci.yml
- Existing local gate: scripts/verify_local.sh
- Existing release docs: docs/CODE-RED/GO_NO_GO_CHECKLIST.md, docs/CODE-RED/RELEASE_RUNBOOK.md
- Existing tests may be misleading; do not trust them as-is.

Objective:
0) Discover first, then implement:
   - read code to map actual critical mutation flows and invariants
   - derive truth tests from code behavior and CODE-RED policy
1) Run only critical invariant tests:
   - idempotency replay safety
   - idempotency mismatch conflict behavior (409/explicit conflict path)
   - accounting posting boundary/canonical posting path checks
   - closed-period posting block checks
   - tenant/company isolation checks
2) Enforce changed-files coverage thresholds:
   - line >= 95%
   - branch >= 90%
3) Enforce "zero flaky tests allowed" in this lane.

Required implementation:
- Add a discovery artifact checked into repo (or CI artifact) that maps:
  endpoint -> service -> repository/table -> accounting/inventory side effects.
- Build a dedicated truth-test set from discovered flows using only new files.
- Create new tests (minimum for this prompt):
  - >= 4 new critical tests
  - >= 1 new suite/aggregator class for gate-fast selection
- Add a durable test tagging convention in Java tests:
  - @Tag("critical"), @Tag("concurrency"), @Tag("reconciliation"), @Tag("flaky")
  - Tag the new truth-suite tests first.
- Configure gate-fast to run only the new truth-suite package/classes.
- Update Maven/Surefire configuration (or profile) so gate-fast can select only
  critical tests deterministically.
- Add changed-file coverage computation:
  - use JaCoCo XML + git diff against PR base
  - fail gate if changed lines/branches fall below threshold
  - publish a machine-readable summary artifact
- Add flake guard:
  - if any selected test carries @Tag("flaky"), gate-fast fails
  - add a quarantine list file if needed, but gate-fast must exclude quarantined tests entirely.
- Update .github/workflows/ci.yml with a dedicated gate-fast job for pull_request.
- Keep runtime budget tight; parallelize where safe.

Docs updates (required):
- Add/extend docs/CODE-RED section documenting gate-fast contract, runtime budget,
  included tags, and failure conditions.
- Add "how to run locally" command examples (exact commands).

Acceptance criteria:
- PR CI shows a distinct gate-fast status check.
- gate-fast fails on:
  - conflict/mismatch regression
  - changed-file coverage below threshold
  - presence of flaky-tagged tests in lane
- gate-fast remains <=15 minutes on normal PRs.

Deliverables:
- CI changes, Maven/test-tag plumbing, any helper scripts, and docs updates.
- New test files and suite files listed explicitly in evidence.
- A short evidence note with command outputs and one intentional failure demo.
```

## Prompt 2 (2-4h): Build `gate-core` on main (full module integration + concurrency)

Use this prompt with an implementation agent:

```text
Implement a gate-core lane for pushes to main that runs a broad, integration-heavy
confidence suite in 30-60 minutes, with explicit concurrency coverage.

Context:
- Main modules to cover: O2C, P2P, Payroll, Inventory, Manufacturing.
- Existing tests are not automatically trusted; validate against code first.

Objective:
0) Expand the same code-derived truth suite from gate-fast.
1) Define and run gate-core as a curated integration suite:
   - module E2E/integration flows for O2C, P2P, Payroll, Inventory, Manufacturing
   - concurrency/retry tests on high-risk endpoints (idempotent writes, dispatch,
     settlements, payroll runs/payments, packing/production critical paths)
2) Enforce critical-module coverage:
   - line >= 92%
   - branch >= 85%
3) Enforce core accounting and cross-module invariant suite in this lane:
   - calculation invariants
   - reconciliation invariants
   - linkage invariants

Required implementation:
- Extend truth-suite coverage by reading production code paths module-by-module.
- Create new tests (minimum for this prompt):
  - >= 5 new tests focused on cross-module invariants and concurrency boundaries
  - >= 1 new suite/aggregator class for gate-core selection
- Create/extend a test-selection profile for gate-core using tags and/or suite includes.
- Configure gate-core to run only new truth-suite tests (no legacy suite classes).
- Include @Tag("concurrency") tests explicitly in this lane.
- Include @Tag("critical") tests that verify accounting calculations and
  cross-module invariants, not only endpoint success/failure status.
- Ensure module-critical package coverage checks are applied to:
  - accounting, inventory, invoice, orchestrator policy/service/workflow
  - any additional high-risk packages discovered in this codebase.
- Add a gate-core job to .github/workflows/ci.yml for push to main.
- Publish artifacts:
  - surefire reports
  - JaCoCo report
  - module-level coverage summary table
- Add timeout and retry policy only for infrastructure steps; never auto-retry failing tests.

Docs updates (required):
- Document gate-core composition and module mapping in CODE-RED docs.
- Add a "how to extend gate-core safely" section (new tests must be tagged and mapped).

Acceptance criteria:
- gate-core is a separate required check on main.
- Concurrency-tagged tests are present and executed.
- Coverage thresholds are enforced and visible in logs/artifacts.
- Runtime stays in the 30-60 minute budget on standard runner capacity.

Deliverables:
- Workflow changes, test selection rules, coverage guardrails, and docs.
- New test files and suite files listed explicitly in evidence.
- Evidence with one passing and one threshold-failing run snapshot.
```

## Prompt 3 (2-4h): Build strict `gate-release` (release SHA only, migration matrix + scans)

Use this prompt with an implementation agent:

```text
Implement gate-release as a strict release-SHA gate. Release cannot proceed unless
this lane passes end-to-end.

Context:
- Existing strict command: FAIL_ON_FINDINGS=true bash scripts/verify_local.sh
- Existing predeploy SQL: scripts/db_predeploy_scans.sql
- Existing runbook/checklist: docs/CODE-RED/RELEASE_RUNBOOK.md, docs/CODE-RED/GO_NO_GO_CHECKLIST.md
- Release decision must rely on code-derived truth tests, not legacy suite assumptions.

Objective:
1) gate-release runs only for release candidates/tags/manual release workflow.
2) It must execute:
   - FAIL_ON_FINDINGS=true bash scripts/verify_local.sh
   - fresh database migration + full suite
   - upgrade migration path (N-1 snapshot -> current) + full suite
   - scripts/db_predeploy_scans.sql with zero-row enforcement
3) It must be impossible to mark release green if any of these fail.

Required implementation:
- Ensure gate-release executes the same authoritative truth suite (strict mode),
  not a loosely inherited legacy mix.
- Ensure gate-release runs only new truth-suite tests.
- Add at least 1 new release-only invariant test covering migration/period-close/
  predeploy-scan consistency assertions.
- Add a dedicated release workflow/job matrix in .github/workflows/ci.yml (or a new
  release workflow) with at least:
  - fresh DB path
  - upgrade DB path
- Add deterministic DB bootstrap fixtures for both paths.
- Add SQL result parser/wrapper so db_predeploy_scans.sql "any rows" => hard fail.
- Persist release evidence artifacts:
  - migration count/max version
  - scan outputs
  - test summaries
  - SHA manifest
- Wire branch protection/release policy so gate-release is required for release SHA promotion.

Docs updates (required):
- Update CODE-RED release docs with exact job names, commands, and failure semantics.
- Add an evidence checklist section with artifact names/locations.

Acceptance criteria:
- Release workflow blocks if strict verify fails.
- Release workflow blocks if fresh/upgrade migration path fails.
- Release workflow blocks if db_predeploy scans return any rows.
- Evidence artifacts are attached and reproducible by SHA.

Deliverables:
- Release workflow implementation, new release invariant tests, helper scripts,
  policy docs, and evidence template.
```

## Prompt 4 (2-4h): Build mandatory `gate-reconciliation` (operational truth == financial truth)

Use this prompt with an implementation agent:

```text
Implement gate-reconciliation as a mandatory release lane that proves cross-module
business truth reconciles with accounting truth.

Context:
- Existing reconciliation/close guidance: docs/codex-cloud-ci-debugging-plan.md
- Existing close and drift controls: scripts/db_predeploy_scans.sql
- Existing tests are hints only; assertions must be verified against code truth.

Objective:
1) Add a dedicated reconciliation lane for release SHA that runs E2E business flows
   and asserts accounting reconciliation invariants:
   - operational truth == financial truth
   - no unlinked slips/invoices/journals
   - closed-period reports come from snapshots only
2) Make this lane mandatory for release promotion.
3) Treat accounting calculations as first-class reconciliation assertions, not
   secondary checks.

Required implementation:
- Curate or build @Tag("reconciliation") tests that cover:
  - O2C dispatch/invoice/journal/ledger chain
  - P2P GRN/invoice/AP settlement linkage
  - payroll run/payment/journal linkage
  - manufacturing/packing inventory + accounting linkage
- Add deterministic assertions for:
  - missing links == zero
  - duplicate or contradictory postings == zero
  - closed-period as-of values remain stable after later postings
- Add deterministic accounting assertions for:
  - debit/credit equality at journal and flow level
  - AR/AP control balances equal subledger totals within defined tolerance
  - inventory/COGS financial effect matches operational quantity/value movement
  - tax and rounding outputs are stable for replay and boundary values
- Assert using code-traced linkage rules (controller/service/repository flow), not
  only prior test expectations.
- Reuse db_predeploy scan logic where appropriate, but keep test-level assertions
  first-class (not SQL-only checks).
- Add reconciliation artifacts:
  - per-module assertion summary
  - mismatch report (empty on pass)
- Create new tests (minimum for this prompt):
  - >= 3 new reconciliation tests (not previously existing files)
  - >= 1 new suite/aggregator class for gate-reconciliation selection
- Configure gate-reconciliation to run only new truth-suite reconciliation tests.

Docs updates (required):
- Add a reconciliation contract doc section in CODE-RED docs defining each invariant,
  data source, and tolerance policy.
- Document triage steps for reconciliation failures.

Acceptance criteria:
- gate-reconciliation is required for release.
- Lane fails on any mismatch between operational and financial truth.
- Lane fails when closed-period snapshot rules are violated.
- Lane fails on accounting math or reconciliation invariant violations.
- Outputs include a concise reconciliation evidence artifact.

Deliverables:
- New reconciliation tests/suite, workflow job, reports/artifacts, and docs updates.
```

## Prompt 5 (2-4h): Build `gate-quality` nightly (mutation + rolling flake control + test portfolio governance)

Use this prompt with an implementation agent:

```text
Implement gate-quality as a nightly lane focused on confidence durability:
mutation resistance, flake detection, and disciplined test portfolio management.

Context:
- Current suite has broad coverage but no first-class flake or mutation gates.
- Release intent requires classifying tests: critical, useful, low-signal, flaky.
- Classification must be performed against the new truth suite first.

Objective:
1) Run nightly mutation testing for critical modules, enforce >=80% mutation score.
2) Run rolling flake detection (20-run window), enforce flake rate <1%.
3) Formalize test classification and quarantine workflow:
   - critical/useful included in release gates
   - low-signal tests rewritten/removed only when replaced by stronger invariant tests
   - flaky tests quarantined until fixed, never silently ignored.

Required implementation:
- Add mutation tooling (PIT or equivalent) scoped to critical modules:
  accounting, inventory, invoicing, orchestrator safety boundaries.
- Add nightly workflow that:
  - executes mutation lane
  - executes flake-repeat lane (repeat critical/useful suite with randomized order)
  - computes rolling 20-run flake rate and fails above threshold
- Add a test-catalog manifest in repo (YAML/JSON/CSV) with fields:
  - test id/path
  - owner
  - class (critical/useful/low-signal/flaky)
  - gate membership
  - last failure date
- Require every truth-suite test to be cataloged and owned.
- Require explicit catalog flag for "accounting-math-critical" and
  "cross-module-critical" tests to prevent accidental exclusion.
- Add scripts to validate manifest completeness against actual test files.
- Add quality guard script that fails if the run added zero new tests/suites for
  the truth-suite initiative branch.
- Add quarantine policy enforcement:
  - quarantined tests cannot be in gate-fast/core/release/reconciliation lanes.

Docs updates (required):
- Document mutation and flake policy in CODE-RED docs.
- Add operating procedure for moving tests between classes.
- Add SLO dashboard definition for suite health trends.

Acceptance criteria:
- Nightly gate-quality fails when mutation score <80% in critical modules.
- Nightly gate-quality fails when rolling flake rate >=1%.
- Test catalog is enforced and auditable.
- Clear quarantine workflow exists and is documented.

Deliverables:
- Nightly workflow, scripts, manifest, policy docs, and sample reports.

## Hard acceptance checks for "new tests were written"

Any implementation claiming completion must provide:
- A list of **newly added** test files (git-added paths) and suite files.
- Evidence count:
  - `new_test_files >= 12`
  - `new_suite_files >= 3`
- A command-based proof (example):
  - `git diff --name-status <base>...HEAD | rg '^A\\s+.*src/test/java/.*(Test|IT|Suite).*\\.java$'`
- A command-based proof that no pre-existing test file is used for gate selection.
  Example checks:
  - gate includes point to new truth-suite package only
  - no modified legacy test path is part of gate membership.

If counts are below thresholds, mark the work incomplete.
```

## Execution order

Run prompts in this order:
1. Prompt 1 (`gate-fast`)
2. Prompt 2 (`gate-core`)
3. Prompt 3 (`gate-release`)
4. Prompt 4 (`gate-reconciliation`)
5. Prompt 5 (`gate-quality`)

Reason: this sequence creates immediate PR safety first, then mainline confidence,
then release strictness, then reconciliation authority, then long-horizon quality.
