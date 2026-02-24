# Task Packet

Ticket: `TKT-ERP-STAGE-108`
Slice: `SLICE-01`
Primary Agent: `accounting-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-108/accounting-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-108/accounting-domain`

## Ticket Context
- title: Bulk Variants Fail-Closed and DryRun Preview
- goal: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Problem Statement
Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Task To Solve
- Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingCatalogController.java`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Cross-Workflow Dependencies
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, hr-domain, orchestrator-runtime, purchasing-invoice-p2p, sales-domain
- External downstream agents to watch: none
- Contract edges:
  - upstream -> factory-production (slice SLICE-02): wip/variance/cogs posting linkage
  - upstream-external -> auth-rbac-company: finance/admin authority boundaries
  - upstream-external -> hr-domain: payroll liability/payment posting linkage
  - upstream-external -> orchestrator-runtime: outbox/idempotency posting orchestration
  - upstream-external -> purchasing-invoice-p2p: ap/posting and settlement linkage
  - upstream-external -> sales-domain: o2c posting and receivable linkage

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
- `bash scripts/verify_local.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am accounting-domain and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `accounting-domain`.
Start your first line with: `I am accounting-domain and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module` from `.codex/agents/cross_module.toml`.
Ticket title: Bulk Variants Fail-Closed and DryRun Preview
Problem statement: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
Task to solve: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- diagnose current behavior in the requested focus paths
- implement the root-cause fix in allowed scope
- add/adjust tests that prove acceptance criteria
- run required checks and report evidence

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
