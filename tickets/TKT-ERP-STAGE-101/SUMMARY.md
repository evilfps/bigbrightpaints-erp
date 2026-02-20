# Ticket TKT-ERP-STAGE-101

- title: Accounting Safety Truth Coverage Period Close Audit Tax
- goal: Add truth-suite coverage for accounting period close, audit trail, and tax paths to protect safety and gate_fast thresholds.
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T12:01:45+00:00
- updated_at: 2026-02-20T12:01:45+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-101/accounting-domain` |
| SLICE-02 | hr-domain | w2 | ready | `tickets/tkt-erp-stage-101/hr-domain` |
| SLICE-03 | purchasing-invoice-p2p | w3 | ready | `tickets/tkt-erp-stage-101/purchasing-invoice-p2p` |
| SLICE-04 | refactor-techdebt-gc | w4 | ready | `tickets/tkt-erp-stage-101/refactor-techdebt-gc` |
| SLICE-05 | repo-cartographer | w1 | ready | `tickets/tkt-erp-stage-101/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-101/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-101`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-101`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-101 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-101 --merge --cleanup-worktrees`
