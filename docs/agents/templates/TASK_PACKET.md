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

## Agent Write Boundary (Enforced)
- `<allowed scope path 1>`
- `<allowed scope path 2>`

## Requested Focus Paths
- `<requested path 1>`
- `<requested path 2>`

## Required Checks Before Done
- `<command 1>`
- `<command 2>`

## Reviewer Contract
- Review-only agents do not commit code.
- Attach findings with evidence to `tickets/<id>/slices/<slice>/reviews/<reviewer>.md`.

## Shipability Bar
- Minimal patch, deterministic behavior, evidence-backed checks.
- Fail closed when safety/permission/accounting invariants are uncertain.
- Include residual risk + blocker if unresolved.

## Required Output
- Begin response with identity line:
  - `I am <agent-id> and I own <SLICE-ID>.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
