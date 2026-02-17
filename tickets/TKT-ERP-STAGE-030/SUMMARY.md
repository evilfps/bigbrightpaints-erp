# Ticket TKT-ERP-STAGE-030

- title: Async Base Parity Reconciliation
- goal: Resolve Stage-029 full-suite regressions on async-loop-predeploy-audit parity lane
- priority: high
- status: planned
- base_branch: async-loop-predeploy-audit
- created_at: 2026-02-17T06:39:57+00:00
- updated_at: 2026-02-17T06:39:57+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-030/accounting-domain` |
| SLICE-02 | purchasing-invoice-p2p | w2 | ready | `tickets/tkt-erp-stage-030/purchasing-invoice-p2p` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-030/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-030/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-030`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-030`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-030 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-030 --merge --cleanup-worktrees`
