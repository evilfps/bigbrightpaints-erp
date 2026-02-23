# Ticket TKT-ERP-STAGE-103

- title: Orchestrator Idempotency and Cross-Module Workflow Guards
- goal: Enforce deterministic idempotency and trace correlation across orchestrator-driven sales inventory production and accounting flows.
- priority: high
- status: done
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T12:01:46+00:00
- updated_at: 2026-02-23T14:42:08Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | orchestrator-runtime | w1 | merged | `tickets/tkt-erp-stage-103/orchestrator-runtime` |
| SLICE-02 | factory-production | w2 | merged | `tickets/tkt-erp-stage-103/factory-production` |
| SLICE-03 | inventory-domain | w3 | merged | `tickets/tkt-erp-stage-103/inventory-domain` |
| SLICE-04 | sales-domain | w4 | merged | `tickets/tkt-erp-stage-103/sales-domain` |
| SLICE-05 | refactor-techdebt-gc | w1 | merged | `tickets/tkt-erp-stage-103/refactor-techdebt-gc` |
| SLICE-06 | repo-cartographer | w2 | merged | `tickets/tkt-erp-stage-103/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-103/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-103`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-103`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-103 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-103 --merge --cleanup-worktrees`
