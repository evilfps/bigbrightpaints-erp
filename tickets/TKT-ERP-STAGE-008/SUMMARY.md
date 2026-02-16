# Ticket TKT-ERP-STAGE-008

- title: ERP Staging Batch 8 - M18-S9A OpenAPI Drift + Portal Parity
- goal: M18-S9A smallest shippable closure: tighten OpenAPI drift enforcement and portal endpoint-map parity
- priority: high
- status: merged
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-16T19:15:38+00:00
- updated_at: 2026-02-17T02:01:00+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-008/release-ops` |
| SLICE-02 | frontend-documentation | w2 | merged | `tickets/tkt-erp-stage-008/frontend-documentation` |
| SLICE-03 | repo-cartographer | w3 | merged | `tickets/tkt-erp-stage-008/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo/tickets/TKT-ERP-STAGE-008/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-008`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-008`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-008 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-008 --merge --cleanup-worktrees`
