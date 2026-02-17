# Ticket TKT-ERP-STAGE-041

- title: Ticket Status Drift Guard
- goal: Fail fast when ticket metadata status drifts from closure evidence
- priority: high
- status: completed
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-17T10:57:45+00:00
- updated_at: 2026-02-17T11:01:41Z

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-041/release-ops` |
| SLICE-02 | repo-cartographer | w2 | merged | `tickets/tkt-erp-stage-041/repo-cartographer` |

## Closure Evidence

- Runtime guard delivered:
  - Added `scripts/check_ticket_status_parity.py` to fail when `ticket.yaml`, `SUMMARY.md`, and `TIMELINE.md` status evidence drifts.
  - Integrated guard into `bash ci/lint-knowledgebase.sh`.
  - Updated runbook/plan closure protocol to require ticket status parity before marking completion.
- Required checks passed on integration state:
  - `bash ci/lint-knowledgebase.sh` -> PASS
  - `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_reconciliation.sh` -> PASS
  - `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_release.sh` -> PASS

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate/tickets/TKT-ERP-STAGE-041/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-041`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-041`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-041 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-041 --merge --cleanup-worktrees`
