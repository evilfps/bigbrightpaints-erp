# Ticket TKT-ERP-STAGE-049

- title: Tenant Control Plane Controller Fail-Fast Parity
- goal: M18-S2A: enforce superadmin-only controller prefilters for tenant bootstrap, lifecycle-state, and tenant-metrics endpoints
- priority: high
- status: blocked
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T13:31:19+00:00
- updated_at: 2026-02-17T14:02:32+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | scope_violation | `tickets/tkt-erp-stage-049/auth-rbac-company` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-049/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-049`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-049`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-049 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-049 --merge --cleanup-worktrees`
