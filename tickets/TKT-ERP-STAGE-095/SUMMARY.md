# Ticket TKT-ERP-STAGE-095

- title: ERP Staging Batch 95 - Approval/Override Policy Matrix Completion
- goal: M18-S4 approval governance: enforce approval/override matrix with maker-checker, reason codes, and immutable audit metadata across sales, purchasing, and accounting exception flows
- priority: high
- status: in_progress
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T07:45:13+00:00
- updated_at: 2026-02-20T09:26:01Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | merged | `tickets/tkt-erp-stage-095/accounting-domain` |
| SLICE-02 | purchasing-invoice-p2p | w2 | merged | `tickets/tkt-erp-stage-095/purchasing-invoice-p2p` |
| SLICE-03 | sales-domain | w3 | merged | `tickets/tkt-erp-stage-095/sales-domain` |
| SLICE-04 | refactor-techdebt-gc | w4 | ready | `tickets/tkt-erp-stage-095/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-095/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-095`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-095`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-095 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-095 --merge --cleanup-worktrees`
