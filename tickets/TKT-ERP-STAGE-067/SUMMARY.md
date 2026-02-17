# Ticket TKT-ERP-STAGE-067

- title: Gate-Fast Release Anchor Normalization
- goal: Make Stage 14.3 release-mode gate_fast non-vacuous and deterministic on integration head
- priority: high
- status: planned
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T20:24:29+00:00
- updated_at: 2026-02-17T20:24:29+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | ready | `tickets/tkt-erp-stage-067/release-ops` |
| SLICE-02 | repo-cartographer | w2 | ready | `tickets/tkt-erp-stage-067/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-067/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-067`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-067`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-067 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-067 --merge --cleanup-worktrees`
