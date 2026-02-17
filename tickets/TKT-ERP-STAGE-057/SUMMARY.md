# Ticket TKT-ERP-STAGE-057

- title: Tenant Metrics Control Plane Coverage Expansion
- goal: Extend superadmin tenant metrics toward mandatory control-plane coverage with deterministic, test-backed fields
- priority: high
- status: done
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T16:47:24+00:00
- updated_at: 2026-02-17T17:12:49+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-02 | refactor-techdebt-gc | w2 | merged | `tickets/tkt-erp-stage-057/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-057/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-057`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-057`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-057 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-057 --merge --cleanup-worktrees`
