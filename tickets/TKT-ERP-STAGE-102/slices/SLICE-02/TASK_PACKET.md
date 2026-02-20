# Task Packet

Ticket: `TKT-ERP-STAGE-102`
Slice: `SLICE-02`
Primary Agent: `reports-admin-portal`
Reviewers: `qa-reliability, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-102/reports-admin-portal`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-102/reports-admin-portal`

## Objective
Consolidate tenant runtime enforcement and enforce superadmin-only hold block quota updates with fail-closed semantics.

## Custom Multi-Agent Role (Codex)
- role: `cross_module_high`
- config_file: `.codex/agents/cross_module_high.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/demo/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/TenantRuntimePolicyService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementConfig.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementInterceptor.java`

## Cross-Workflow Dependencies
- Upstream slices: SLICE-01
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none
- Contract edges:
  - upstream -> auth-rbac-company (slice SLICE-01): admin/report access boundaries

## Required Checks Before Done
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am reports-admin-portal and I own SLICE-02.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `reports-admin-portal`.
Start your first line with: `I am reports-admin-portal and I own SLICE-02.`
Use Codex custom multi-agent role `cross_module_high` from `.codex/agents/cross_module_high.toml`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
