# R3 Go/No-Go Checkpoint Template

Last reviewed: 2026-02-23
Owner: Release & Ops Agent

Use this file for final human release approval on a frozen release-candidate SHA.

## Candidate Identity
- Release candidate SHA:
- Release anchor SHA:
- Decision timestamp (UTC):

## Decision Authority (Human Only)
- Approver name:
- Approver role:
- Decision: `GO` | `NO-GO`

## Required Evidence (Attach Exact Paths)
- Completion gate board: `docs/system-map/COMPLETION_GATES_STATUS.md`
- Live execution board: `docs/system-map/LIVE_EXECUTION_PLAN.md`
- Gate hash ledger:
- Sign-off report:
- Go/No-go checklist: `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`
- Rollback runbook: `docs/runbooks/rollback.md`

## Gate Confirmation (Candidate SHA)
- `gate_fast`: pass | fail
- `gate_core`: pass | fail
- `gate_reconciliation`: pass | fail
- `gate_release`: pass | fail
- full regression (`mvn test`): pass | fail

## Risk Statement
- Open high/critical findings:
- Accepted residual risks (owner + due date):

## Rollback Readiness
- Rollback owner:
- Rollback communication channel:
- Rollback SLA:

## Final Notes
- Conditions attached to approval:
- Post-release validation requirements:
