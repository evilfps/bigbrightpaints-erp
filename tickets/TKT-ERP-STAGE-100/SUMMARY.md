# Ticket TKT-ERP-STAGE-100

- title: Gate-Fast Coverage Tranche for Sales Purchasing Approvals
- goal: Add truth-suite coverage for changed sales and purchasing approval paths so gate_fast reflects deterministic coverage.
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T12:01:45+00:00
- updated_at: 2026-02-20T12:01:45+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | purchasing-invoice-p2p | w1 | ready | `tickets/tkt-erp-stage-100/purchasing-invoice-p2p` |
| SLICE-02 | sales-domain | w2 | ready | `tickets/tkt-erp-stage-100/sales-domain` |
| SLICE-03 | refactor-techdebt-gc | w3 | ready | `tickets/tkt-erp-stage-100/refactor-techdebt-gc` |
| SLICE-04 | repo-cartographer | w4 | ready | `tickets/tkt-erp-stage-100/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-100/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-100`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-100`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-100 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-100 --merge --cleanup-worktrees`
