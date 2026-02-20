# Task Packet

Ticket: `TKT-ERP-STAGE-101`
Slice: `SLICE-01`
Primary Agent: `accounting-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-101/accounting-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-101/accounting-domain`

## Objective
Add truth-suite coverage for accounting period close, audit trail, and tax paths to protect safety and gate_fast thresholds.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java`

## Cross-Workflow Dependencies
- Upstream slices: SLICE-02, SLICE-03
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, orchestrator-runtime, sales-domain
- External downstream agents to watch: none
- Contract edges:
  - upstream -> hr-domain (slice SLICE-02): payroll liability/payment posting linkage
  - upstream -> purchasing-invoice-p2p (slice SLICE-03): ap/posting and settlement linkage
  - upstream-external -> auth-rbac-company: finance/admin authority boundaries
  - upstream-external -> factory-production: wip/variance/cogs posting linkage
  - upstream-external -> orchestrator-runtime: outbox/idempotency posting orchestration
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
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
