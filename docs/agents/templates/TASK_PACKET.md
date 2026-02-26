# Task Packet Template

Ticket: `<TKT-ID>`
Slice: `<SLICE-ID>`
Primary Agent: `<agent-id>`
Reviewers: `<reviewer1>, <reviewer2>`
Lane: `<w1|w2|w3|w4>`
Branch: `<branch>`
Worktree: `<path>`

## Objective
- `<concrete, testable outcome>`

## Delegation Context (Invariant-First)
- Feature objective: `<business/system objective>`
- Current failure: `<current breakage, error signal, observed behavior>`
- Expected behavior: `<what must be true after fix>`
- Delegation model: own module boundary and restore system integrity; no blind patching.
- If upstream dependency/contract is root cause, report evidence and request coordination before changing contracts.

## Constraints
- `<what must not break>`
- `<compatibility/performance/security constraint>`

## Assumptions
- Safe assumptions:
  - `<assumption considered safe>`
- Assumptions to validate:
  - `<assumption that requires evidence>`

## Custom Multi-Agent Role (Codex)
- role: `<custom-role-id>`
- config_file: `<.codex/agents/<role>.toml>`
- runtime_profile: `<resolved at runtime from role config>`

## Agent Write Boundary (Enforced)
- `<allowed scope path 1>`
- `<allowed scope path 2>`

## Requested Focus Paths
- `<requested path 1>`
- `<requested path 2>`

## Ticket-First Gate (Blocking)
- Work only on assigned branch `<branch>` and assigned worktree `<path>`.
- Base branches are read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`.
- Claim evidence must be present in `tickets/<TKT-ID>/ticket.yaml` and `tickets/<TKT-ID>/TIMELINE.md` before edits.
- If branch/worktree/claim validation fails, stop and report blocker instead of patching.
- Mandatory role sequence: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Required Checks Before Done
- `<command 1>`
- `<command 2>`

## Reviewer Contract
- Review-only agents do not commit code.
- Attach findings with evidence to `tickets/<id>/slices/<slice>/reviews/<reviewer>.md`.

## Testing Responsibility Split
- Implementation agents write targeted tests for changed behavior.
- `merge-specialist` owns integration/conflict-resolution evidence before code review.
- `qa-reliability` owns cross-workflow regression verification after code-review approvals.
- `release-ops` ensures docs/release-readiness sync before final merge.

## Shipability Bar
- Minimal patch, deterministic behavior, evidence-backed checks.
- Fail closed when safety/permission/accounting invariants are uncertain.
- Include residual risk + blocker if unresolved.

## Required Output
- Begin response with identity line:
  - `I am <agent-id> and I own <SLICE-ID>.`
- root_cause_analysis
- change_summary
- files_changed
- commands_run
- harness_results
- residual_risks
- cross_module_coordination_required
- blockers_or_next_step
- ticket_claim_evidence
- worktree_validation
- codebase_impact_analysis:
  - upstream dependencies/contracts touched
  - downstream modules/portals at risk
  - API/event/schema/test surface changed or intentionally unchanged
