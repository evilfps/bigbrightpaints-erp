# Ticket TKT-ERP-STAGE-037

- title: Gate-Fast Vacuous Coverage Policy Hardening
- goal: Fail closed on real coverage gaps while avoiding false vacuous failures for interface-only diffs
- priority: high
- status: completed
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-17T09:54:49+00:00
- updated_at: 2026-02-17T10:18:34Z

## Slice Board

| Slice | Agent | Lane | Status | Branch | Source Commit | Integrated Commit |
| --- | --- | --- | --- | --- | --- | --- |
| SLICE-01 | release-ops | w1 | merged | `tickets/tkt-erp-stage-037/release-ops` | `32f0af63` | `0b271bfe` |
| SLICE-02 | repo-cartographer | w2 | merged | `tickets/tkt-erp-stage-037/repo-cartographer` | `4c52d593` | `85b98247` |

## Closure Evidence

- Regression fix integrated after initial over-tightening:
  - `a1e9259e` (`scripts/gate_fast.sh`) limits release-mode blocking to `coverage_skipped_files` while keeping unmapped-line visibility as warning.
- Final gate + harness proof on integration branch:
  - `DIFF_BASE=07cc472ea5e087ada11caefa25ef68dab3b86005 GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh` -> PASS
  - `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_reconciliation.sh` -> PASS
  - `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_release.sh` -> PASS
  - `bash scripts/verify_local.sh` -> PASS (`Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`)
- Logs:
  - `/tmp/tkt037_integrated_gate_fast_rerun.log`
  - `/tmp/tkt037_final_gate_reconciliation.log`
  - `/tmp/tkt037_final_gate_release.log`
  - `/tmp/tkt037_verify_local.log`

## Operator Commands

Read cross-workflow dependency plan:
`cat /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate/tickets/TKT-ERP-STAGE-037/CROSS_WORKFLOW_PLAN.md`

Generate tmux launch block:
`python3 scripts/harness_orchestrator.py dispatch --ticket-id TKT-ERP-STAGE-037`

Verify / readiness pass:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-037`

Verify + merge eligible slices:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-037 --merge`

Verify + merge + cleanup worktrees:
`python3 scripts/harness_orchestrator.py verify --ticket-id TKT-ERP-STAGE-037 --merge --cleanup-worktrees`
