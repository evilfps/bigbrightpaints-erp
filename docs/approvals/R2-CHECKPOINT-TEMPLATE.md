# R2 Checkpoint Template

Last reviewed: 2026-03-29

## Scope
- Feature: `<feature identifier>`
- Branch: `<branch name>`
- PR: `<PR URL or "pending">`
- Review candidate:
  - `<summary of what this change does>`
- Why this is R2: `<explain why this change is classified as high-risk>`

## Risk Trigger
- Triggered by:
  - `<list of high-risk paths changed>`
- Contract surfaces affected:
  - `<list of API/config/schema surfaces affected>`
- Failure mode if wrong:
  - `<what goes wrong if this change is incorrect>`

## Approval Authority
- Mode: `<orchestrator|human>`
- Approver: `<approver identity>`
- Canary owner: `<canary owner identity>`
- Approval status: `<pending|approved>`
- Basis: `<why this approval mode was chosen>`

## Escalation Decision
- Human escalation required: `<yes|no>`
- Reason: `<why human escalation is or is not required>`

## Rollback Owner
- Owner: `<rollback owner identity>`
- Rollback method:
  - `<rollback steps>`
- Rollback trigger:
  - `<conditions that should trigger rollback>`

## Expiry
- Valid until: `<YYYY-MM-DD>`
- Re-evaluate if: `<conditions that should trigger re-evaluation>`

## Verification Evidence
- Commands run:
  - `<list of validation commands executed>`
- Result summary:
  - `<summary of command outcomes>`
- Artifacts/links:
  - `<relevant artifact paths or links>`
