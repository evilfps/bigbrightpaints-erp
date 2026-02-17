# Ticket TKT-ERP-STAGE-038

- title: Flake Quarantine Contract Tightening
- goal: Enforce expiring quarantine policy and keep release decisions based on invariant signal quality
- priority: high
- status: planned
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-17T09:55:06+00:00
- updated_at: 2026-02-17T09:55:06+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | ready | `tickets/tkt-erp-stage-038/release-ops` |
| SLICE-02 | repo-cartographer | w2 | ready | `tickets/tkt-erp-stage-038/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate/tickets/TKT-ERP-STAGE-038/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-038`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-038`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-038 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-038 --merge --cleanup-worktrees`
