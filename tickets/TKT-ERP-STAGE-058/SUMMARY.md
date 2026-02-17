# Ticket TKT-ERP-STAGE-058

- title: Tenant Quota Controls Foundation
- goal: Implement superadmin-governed tenant quota fields and fail-closed update/read contract baseline
- priority: high
- status: planned
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T17:16:18+00:00
- updated_at: 2026-02-17T17:16:18+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | ready | `tickets/tkt-erp-stage-058/auth-rbac-company` |
| SLICE-02 | data-migration | w2 | ready | `tickets/tkt-erp-stage-058/data-migration` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-058/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-058/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-058`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-058`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-058 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-058 --merge --cleanup-worktrees`
