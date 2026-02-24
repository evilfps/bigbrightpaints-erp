# Task Packet

Ticket: `TKT-ERP-STAGE-109`
Slice: `SLICE-01`
Primary Agent: `refactor-techdebt-gc`
Reviewers: `qa-reliability`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-109/refactor-techdebt-gc`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-109/refactor-techdebt-gc`

## Ticket Context
- title: Superadmin Tenant Onboarding Credential Flow
- goal: Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.

## Problem Statement
Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.

## Task To Solve
- Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.

## Custom Multi-Agent Role (Codex)
- role: `cross_module_high`
- config_file: `.codex/agents/cross_module_high.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/`
- `erp-domain/src/test/java/`
- `docs/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `cd erp-domain && mvn -B -ntp test`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am refactor-techdebt-gc and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `refactor-techdebt-gc`.
Start your first line with: `I am refactor-techdebt-gc and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module_high` from `.codex/agents/cross_module_high.toml`.
Ticket title: Superadmin Tenant Onboarding Credential Flow
Problem statement: Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.
Task to solve: Implement superadmin-controlled company onboarding with optional GST defaults, unique company code enforcement, first admin credential issuance via email, support password reset controls, and seeded superadmin bootstrap credentials for current rollout.
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- diagnose current behavior in the requested focus paths
- implement the root-cause fix in allowed scope
- add/adjust tests that prove acceptance criteria
- run required checks and report evidence

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
