# Task Packet

Ticket: `TKT-ERP-STAGE-101`
Slice: `SLICE-02`
Primary Agent: `hr-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-101/hr-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-101/hr-domain`

## Objective
Add truth-suite coverage for accounting period close, audit trail, and tax paths to protect safety and gate_fast thresholds.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/hr/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/payroll/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: none
- Contract edges:
  - downstream -> accounting-domain (slice SLICE-01): payroll liability/payment posting linkage
  - upstream-external -> auth-rbac-company: payroll/PII access boundaries

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Payroll*' test`
- `bash scripts/verify_local.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am hr-domain and I own SLICE-02.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `hr-domain`.
Start your first line with: `I am hr-domain and I own SLICE-02.`
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
