# Ticket TKT-ERP-STAGE-059

- title: Tenant Quota Runtime Enforcement
- goal: Enforce tenant quota limits fail-closed at runtime with superadmin-governed boundaries
- priority: high
- status: canceled
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T17:39:34+00:00
- updated_at: 2026-02-18T08:12:04+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | canceled | `tickets/tkt-erp-stage-059/auth-rbac-company` |
| SLICE-02 | refactor-techdebt-gc | w2 | canceled | `tickets/tkt-erp-stage-059/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-059/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-059`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-059`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-059 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-059 --merge --cleanup-worktrees`
