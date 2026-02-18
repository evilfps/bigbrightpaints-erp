# Ticket TKT-ERP-STAGE-060

- title: Tenant Quota Contract And Docs Parity
- goal: Align tenant quota API contract, OpenAPI artifacts, and portal documentation with runtime enforcement
- priority: high
- status: canceled
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T17:39:39+00:00
- updated_at: 2026-02-18T08:12:04+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | canceled | `tickets/tkt-erp-stage-060/auth-rbac-company` |
| SLICE-02 | release-ops | w2 | canceled | `tickets/tkt-erp-stage-060/release-ops` |
| SLICE-03 | frontend-documentation | w3 | canceled | `tickets/tkt-erp-stage-060/frontend-documentation` |
| SLICE-04 | refactor-techdebt-gc | w4 | canceled | `tickets/tkt-erp-stage-060/refactor-techdebt-gc` |
| SLICE-05 | repo-cartographer | w1 | canceled | `tickets/tkt-erp-stage-060/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-060/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-060`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-060`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-060 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-060 --merge --cleanup-worktrees`
