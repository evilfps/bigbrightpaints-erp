# Ticket TKT-ERP-STAGE-028

- title: ERP Staging Batch 28
- goal: M18-S10C refresh anchored release gate evidence on current head
- priority: high
- status: in_progress
- base_branch: async-loop-predeploy-audit
- created_at: 2026-02-17T05:37:59+00:00
- updated_at: 2026-02-17T05:57:52+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | waiting_for_push | `tickets/tkt-erp-stage-028/release-ops` |
| SLICE-02 | refactor-techdebt-gc | w2 | waiting_for_push | `tickets/tkt-erp-stage-028/refactor-techdebt-gc` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-028/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-028`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-028`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-028 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-028 --merge --cleanup-worktrees`
