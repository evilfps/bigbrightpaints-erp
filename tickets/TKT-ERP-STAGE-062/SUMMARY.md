# Ticket TKT-ERP-STAGE-062

- title: Workflow UX Simplification Hardening
- goal: Simplify messy backend workflows with deterministic reason-coded fail-closed behavior
- priority: high
- status: done
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T17:52:15+00:00
- updated_at: 2026-02-17T19:35:13+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | merged | `tickets/tkt-erp-stage-062/accounting-domain` |
| SLICE-02 | purchasing-invoice-p2p | w2 | merged | `tickets/tkt-erp-stage-062/purchasing-invoice-p2p` |
| SLICE-03 | sales-domain | w3 | merged | `tickets/tkt-erp-stage-062/sales-domain` |
| SLICE-04 | refactor-techdebt-gc | w4 | merged | `tickets/tkt-erp-stage-062/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-062/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-062`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-062`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-062 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-062 --merge --cleanup-worktrees`
