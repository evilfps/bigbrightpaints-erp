# Ticket TKT-ERP-STAGE-114

- title: RAG MCP Context Engine rollout
- goal: Deploy RAG MCP context engine for agent-safe ERP development with local dev tooling and CI sidecar; include usage docs and early regression guards for cross-module flows/idempotency mismatches.
- priority: high
- status: in_progress
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-26T22:52:00+00:00
- updated_at: 2026-02-26T22:52:00+00:00

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | ready | `tickets/tkt-erp-stage-114/release-ops` |
| SLICE-02 | repo-cartographer | w2 | ready | `tickets/tkt-erp-stage-114/repo-cartographer` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp/tickets/TKT-ERP-STAGE-114/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-114`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-114`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-114 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-114 --merge --cleanup-worktrees`
