# Ticket TKT-ERP-STAGE-099

- title: Gate-Fast Alignment for Tenant Runtime Admin Portal Company Auth
- goal: Add truth-suite coverage for tenant runtime enforcement and admin/portal policy paths so gate_fast coverage rises on changed files.
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T12:01:45+00:00
- updated_at: 2026-02-20T12:01:45+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | ready | `tickets/tkt-erp-stage-099/auth-rbac-company` |
| SLICE-02 | reports-admin-portal | w2 | ready | `tickets/tkt-erp-stage-099/reports-admin-portal` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-099/refactor-techdebt-gc` |
| SLICE-04 | repo-cartographer | w4 | ready | `tickets/tkt-erp-stage-099/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-099/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-099`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-099`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-099 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-099 --merge --cleanup-worktrees`
