# Ticket TKT-ERP-STAGE-111

- title: Superadmin frontend password reset and token/company-code alignment
- goal: Implement superadmin forgot/reset-password UX/API flow where entering superadmin email sends reset mail and fix invalid-token behavior caused by tenant/company scoped token mismatch
- priority: high
- status: merged
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-25T11:02:39+00:00
- updated_at: 2026-02-26T01:26:27Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | w1 | merged | `tickets/tkt-erp-stage-111/auth-rbac-company` |
| SLICE-02 | frontend-documentation | w2 | merged | `tickets/tkt-erp-stage-111/frontend-documentation` |
| SLICE-03 | refactor-techdebt-gc | w3 | merged | `tickets/tkt-erp-stage-111/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-111/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-111`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-111`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-111 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-111 --merge --cleanup-worktrees`
