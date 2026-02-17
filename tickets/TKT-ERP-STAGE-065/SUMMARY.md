# Ticket TKT-ERP-STAGE-065

- title: One-SHA Gate Closure Across Fast/Core/Reconciliation/Release
- goal: Enforce and prove same-SHA gate closure for gate_fast, gate_core, gate_reconciliation, gate_release on integration head
- priority: high
- status: done
- base_branch: tmp/orch-exec-20260217
- created_at: 2026-02-17T19:51:15+00:00
- updated_at: 2026-02-17T20:08:53+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-065/release-ops` |
| SLICE-02 | repo-cartographer | w2 | merged | `tickets/tkt-erp-stage-065/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec/tickets/TKT-ERP-STAGE-065/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-065`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-065`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-065 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-065 --merge --cleanup-worktrees`
