# Ticket TKT-ERP-STAGE-108

- title: Bulk Variants Fail-Closed and DryRun Preview
- goal: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
- priority: high
- status: in_progress
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-24T09:55:24+00:00
- updated_at: 2026-02-24T09:56:42Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-108/accounting-domain` |
| SLICE-02 | factory-production | w2 | ready | `tickets/tkt-erp-stage-108/factory-production` |
| SLICE-03 | inventory-domain | w3 | ready | `tickets/tkt-erp-stage-108/inventory-domain` |
| SLICE-04 | frontend-documentation | w4 | ready | `tickets/tkt-erp-stage-108/frontend-documentation` |
| SLICE-05 | refactor-techdebt-gc | w1 | ready | `tickets/tkt-erp-stage-108/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-108/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-108`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-108`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-108 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-108 --merge --cleanup-worktrees`
