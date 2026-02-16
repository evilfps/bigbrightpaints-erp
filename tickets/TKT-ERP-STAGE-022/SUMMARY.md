# Ticket TKT-ERP-STAGE-022

- title: ERP Staging Batch 22 - GST Reconciliation Signal
- goal: M18-S6D strengthen GST reconciliation signal integrity for liability-vs-claimability visibility
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-16T22:32:08+00:00
- updated_at: 2026-02-16T22:32:08+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-022/accounting-domain` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-022/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-022`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-022`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-022 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-022 --merge --cleanup-worktrees`
