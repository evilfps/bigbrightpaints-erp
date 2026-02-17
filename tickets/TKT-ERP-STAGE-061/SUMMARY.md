# Ticket TKT-ERP-STAGE-061

- title: Accounting Deploy Safety Finalization
- goal: Close remaining accounting/data safety risks for v1 staging go-no-go
- priority: high
- status: planned
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T17:52:14+00:00
- updated_at: 2026-02-17T17:52:14+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-061/accounting-domain` |
| SLICE-02 | release-ops | w2 | ready | `tickets/tkt-erp-stage-061/release-ops` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-061/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-061/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-061`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-061`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-061 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-061 --merge --cleanup-worktrees`
