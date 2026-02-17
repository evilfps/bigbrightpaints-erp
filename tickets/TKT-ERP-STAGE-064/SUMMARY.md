# Ticket TKT-ERP-STAGE-064

- title: Migration Rollback Rehearsal Parity On Release Candidate SHA
- goal: Produce deterministic migration+rollback rehearsal evidence on a single release candidate SHA for staging
- priority: high
- status: done
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T18:42:41+00:00
- updated_at: 2026-02-17T19:48:37+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-064/release-ops` |
| SLICE-02 | repo-cartographer | w2 | merged | `tickets/tkt-erp-stage-064/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-064/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-064`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-064`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-064 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-064 --merge --cleanup-worktrees`
