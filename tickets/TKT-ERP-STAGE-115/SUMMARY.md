# Ticket TKT-ERP-STAGE-115

- title: Superadmin control-plane completion and hard-boundary cleanup
- goal: Deliver a production-safe superadmin portal backend contract with tenant dashboard metrics, tenant limit governance, warning-before-hold support controls, and explicit role boundaries that prevent tenant-admin escalation into superadmin scope.
- priority: high
- status: in_progress
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-27T00:00:00Z
- updated_at: 2026-02-27T00:00:00Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | in_progress | `tickets/tkt-erp-stage-115/auth-rbac-company` |
| SLICE-02 | frontend-documentation | w2 | in_progress | `tickets/tkt-erp-stage-115/frontend-documentation` |
| SLICE-03 | qa-reliability | w3 | ready | `tickets/tkt-erp-stage-115/qa-reliability` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-115/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-115`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-115`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-115 --merge`
