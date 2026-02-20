# Task Packet

Ticket: `TKT-ERP-STAGE-102`
Slice: `SLICE-01`
Primary Agent: `auth-rbac-company`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-102/auth-rbac-company`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-102/auth-rbac-company`

## Objective
Consolidate tenant runtime enforcement and enforce superadmin-only hold block quota updates with fail-closed semantics.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/AuthService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/CompanyController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-02
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain, factory-production, hr-domain, inventory-domain, purchasing-invoice-p2p, sales-domain
- Contract edges:
  - downstream -> reports-admin-portal (slice SLICE-02): admin/report access boundaries
  - downstream-external -> accounting-domain: finance/admin authority boundaries
  - downstream-external -> factory-production: tenant-scoped manufacturing operations
  - downstream-external -> hr-domain: payroll/PII access boundaries
  - downstream-external -> inventory-domain: tenant context and role checks
  - downstream-external -> purchasing-invoice-p2p: tenant-scoped supplier/AP access rules
  - downstream-external -> sales-domain: tenant and role boundary contract

## Required Checks Before Done
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am auth-rbac-company and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `auth-rbac-company`.
Start your first line with: `I am auth-rbac-company and I own SLICE-01.`
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
