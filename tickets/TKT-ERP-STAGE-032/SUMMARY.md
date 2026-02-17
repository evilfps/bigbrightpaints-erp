# Ticket TKT-ERP-STAGE-032

- title: CI Gate Strategy Refactor
- goal: High-signal deterministic staging gates and reduced flaky/full-suite overhead per ERP_STAGING_MASTER_PLAN section 14
- priority: high
- status: completed
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-17T08:37:01+00:00
- updated_at: 2026-02-17T09:10:58Z

## Slice Board

| Slice | Agent | Lane | Status | Branch | Source Commit | Integrated Commit |
| --- | --- | --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-032/release-ops` | `11647da0` | `dac7119c` |
| SLICE-02 | refactor-techdebt-gc | w2 | merged | `tickets/tkt-erp-stage-032/refactor-techdebt-gc` | `0ab28887` | `5be17817` |

## Closure Evidence

- `bash ci/check-architecture.sh` -> PASS
- `bash ci/check-enterprise-policy.sh` -> PASS
- `bash scripts/verify_local.sh` -> PASS
- Maven verify summary from `verify_local`: `Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate/tickets/TKT-ERP-STAGE-032/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-032`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-032`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-032 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-032 --merge --cleanup-worktrees`
