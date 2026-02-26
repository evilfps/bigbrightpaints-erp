# Ticket TKT-ERP-STAGE-113

- title: Production blocker remediation wave from TKT-ERP-STAGE-112
- goal: Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence
- priority: high
- status: in_progress
- base_branch: origin/harness-engineering-orchestrator
- created_at: 2026-02-26T10:34:09+00:00
- updated_at: 2026-02-26T10:34:09+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | ready | `tickets/tkt-erp-stage-113/accounting-domain` |
| SLICE-02 | auth-rbac-company | w2 | ready | `tickets/tkt-erp-stage-113/auth-rbac-company` |
| SLICE-03 | hr-domain | w3 | ready | `tickets/tkt-erp-stage-113/hr-domain` |
| SLICE-04 | orchestrator-runtime | w4 | ready | `tickets/tkt-erp-stage-113/orchestrator-runtime` |
| SLICE-05 | inventory-domain | w1 | ready | `tickets/tkt-erp-stage-113/inventory-domain` |
| SLICE-06 | orchestrator | w2 | ready | `tickets/tkt-erp-stage-113/orchestrator` |
| SLICE-07 | purchasing-invoice-p2p | w3 | ready | `tickets/tkt-erp-stage-113/purchasing-invoice-p2p` |
| SLICE-08 | reports-admin-portal | w4 | ready | `tickets/tkt-erp-stage-113/reports-admin-portal` |
| SLICE-09 | sales-domain | w1 | ready | `tickets/tkt-erp-stage-113/sales-domain` |
| SLICE-10 | frontend-documentation | w2 | ready | `tickets/tkt-erp-stage-113/frontend-documentation` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator/tickets/TKT-ERP-STAGE-113/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-113`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-113`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-113 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-113 --merge --cleanup-worktrees`
