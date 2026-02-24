# Ticket TKT-ERP-STAGE-107

- title: Dealer linkage integrity and journal invoice reference visibility
- goal: Prevent dealer-history orphaning on admin/sales dealer changes and surface invoice references in journal views without breaking accounting invariants.
- priority: high
- status: done
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-23T19:10:40+00:00
- updated_at: 2026-02-24T08:50:00Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | done | `tickets/tkt-erp-stage-107/accounting-domain` |
| SLICE-02 | sales-domain | w2 | done | `tickets/tkt-erp-stage-107/sales-domain` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-107/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-107`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-107`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-107 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-107 --merge --cleanup-worktrees`
