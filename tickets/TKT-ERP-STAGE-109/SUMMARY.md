# Ticket TKT-ERP-STAGE-109

- title: Superadmin Tenant Onboarding Credential Flow
- goal: Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.
- priority: high
- status: in_review
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-24T10:23:24+00:00
- updated_at: 2026-02-25T09:00:04Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | refactor-techdebt-gc | w1 | in_review | `tickets/tkt-erp-stage-109/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-108/refactor-techdebt-gc-review/tickets/TKT-ERP-STAGE-109/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-109`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-109`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-109 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-109 --merge --cleanup-worktrees`
