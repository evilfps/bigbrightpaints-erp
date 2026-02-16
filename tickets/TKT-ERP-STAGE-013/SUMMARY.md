# Ticket TKT-ERP-STAGE-013

- title: ERP Staging Batch 13 - Settlement Replay Race Matrix
- goal: M18-S5B: harden settlement idempotency replay/race diagnostics and deterministic conflict handling
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-16T21:40:12+00:00
- updated_at: 2026-02-16T21:40:12+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-013/accounting-domain` |
| SLICE-02 | refactor-techdebt-gc | w2 | ready | `tickets/tkt-erp-stage-013/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-013/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-013`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-013`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-013 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-013 --merge --cleanup-worktrees`
