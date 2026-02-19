# Ticket TKT-ERP-STAGE-092

- title: Tenant Quota Controls Foundation
- goal: Implement superadmin-governed tenant quota fields and fail-closed update/read contract baseline on canonical branch
- priority: high
- status: in_progress
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-19T13:10:22+00:00
- updated_at: 2026-02-19T17:25:33Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | in_review | `tickets/tkt-erp-stage-092/auth-rbac-company` |
| SLICE-02 | data-migration | w2 | ready | `tickets/tkt-erp-stage-092/data-migration` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-092/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_stabilize/tickets/TKT-ERP-STAGE-092/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-092`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-092`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-092 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-092 --merge --cleanup-worktrees`
