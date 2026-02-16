# Ticket TKT-ERP-STAGE-009

- title: ERP Staging Batch 9 - M18-S10A Rollback Rehearsal Evidence
- goal: M18-S10A smallest shippable closure: standardize staging rollback rehearsal evidence and release gate traceability
- priority: high
- status: planned
- base_branch: async-loop-predeploy-audit
- created_at: 2026-02-16T20:33:10+00:00
- updated_at: 2026-02-16T20:33:10+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | ready | `tickets/tkt-erp-stage-009/release-ops` |
| SLICE-02 | repo-cartographer | w2 | ready | `tickets/tkt-erp-stage-009/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-009/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-009`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-009`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-009 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-009 --merge --cleanup-worktrees`
