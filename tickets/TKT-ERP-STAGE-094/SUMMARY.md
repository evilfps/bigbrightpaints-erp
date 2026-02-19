# Ticket TKT-ERP-STAGE-094

- title: Gate and Lint Portability Hardening
- goal: Fix gate closure fail-open and Bash/Python/macOS portability blockers: enforce immutable anchor validation, robust BASH_ENV compatibility, and deterministic knowledgebase lint behavior.
- priority: high
- status: completed
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-19T19:14:56+00:00
- updated_at: 2026-02-19T19:57:30Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | done | `tickets/tkt-erp-stage-094/release-ops` |

## Operator Commands

Read cross-workflow dependency plan:
`cat /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/tickets/TKT-ERP-STAGE-094/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-094`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-094`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-094 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-094 --merge --cleanup-worktrees`
