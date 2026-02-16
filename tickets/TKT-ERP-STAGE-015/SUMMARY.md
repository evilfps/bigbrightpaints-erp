# Ticket TKT-ERP-STAGE-015

- title: ERP Staging Batch 15 - Variant Duplicate Race Guard
- goal: M18-S7A smallest shippable hardening for bulk variant duplicate-race handling
- priority: high
- status: done
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-16T21:59:50+00:00
- updated_at: 2026-02-16T22:04:48+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | factory-production | w1 | merged | `tickets/tkt-erp-stage-015/factory-production` |
| SLICE-02 | refactor-techdebt-gc | w2 | merged | `tickets/tkt-erp-stage-015/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-015/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-015`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-015`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-015 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-015 --merge --cleanup-worktrees`
