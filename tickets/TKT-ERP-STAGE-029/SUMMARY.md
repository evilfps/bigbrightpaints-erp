# Ticket TKT-ERP-STAGE-029

- title: ERP Staging Batch 29
- goal: Sync Stage-028 evidence into slice branches for merge eligibility
- priority: high
- status: blocked
- base_branch: async-loop-predeploy-audit
- created_at: 2026-02-17T06:00:50+00:00
- updated_at: 2026-02-17T06:37:53+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | orchestrator | w1 | pending_review | `tickets/tkt-erp-stage-029/orchestrator-v2` |
| SLICE-02 | refactor-techdebt-gc | w2 | checks_failed | `tickets/tkt-erp-stage-029/refactor-techdebt-gc-v2` |
| SLICE-03 | repo-cartographer | w3 | pending_review | `tickets/tkt-erp-stage-029/repo-cartographer-v2` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-029/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-029`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-029`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-029 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-029 --merge --cleanup-worktrees`
