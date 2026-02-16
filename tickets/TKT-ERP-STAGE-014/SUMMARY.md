# Ticket TKT-ERP-STAGE-014

- title: ERP Staging Batch 14 - Workflow Decision Log Refresh
- goal: M18-S3B docs closure: refresh canonical workflow/duplicate-path decisions from latest merged slices
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-16T21:40:12+00:00
- updated_at: 2026-02-16T21:40:12+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | repo-cartographer | w1 | ready | `tickets/tkt-erp-stage-014/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-014/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-014`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-014`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-014 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-014 --merge --cleanup-worktrees`
