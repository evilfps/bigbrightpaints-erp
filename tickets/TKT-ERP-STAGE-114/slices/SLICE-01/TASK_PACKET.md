# Task Packet

Ticket: `TKT-ERP-STAGE-114`
Slice: `SLICE-01`
Primary Agent: `release-ops`
Reviewers: `code-reviewer, code_reviewer, merge-specialist, qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-114/release-ops`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/TKT-ERP-STAGE-114/release-ops`

## Ticket Context
- title: RAG MCP Context Engine rollout
- goal: Deploy RAG MCP context engine for agent-safe ERP development with local dev tooling and CI sidecar; include usage docs and early regression guards for cross-module flows/idempotency mismatches.

## Problem Statement
Implement ticket objective for scoped ownership paths: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh

## Task To Solve
- Implement ticket goal within explicit scope paths: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Deploy RAG MCP context engine for agent-safe ERP development with local dev tooling and CI sidecar; include usage docs and early regression guards for cross-module flows/idempotency mismatches.

## Delegation Context (Invariant-First)
- Feature objective: Deploy RAG MCP context engine for agent-safe ERP development with local dev tooling and CI sidecar; include usage docs and early regression guards for cross-module flows/idempotency mismatches.
- Current failure: Implement ticket objective for scoped ownership paths: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh
- Expected behavior: Required checks pass and acceptance criteria are satisfied.
- Delegation model: own the module boundary and restore system integrity; avoid file-level micromanagement.
- If upstream contract break is detected, provide evidence and coordination request before contract edits.

## Constraints
- Stay inside assigned slice scope and write boundary.
- Do not break existing workflows tied to this module or contract surface.
- Do not alter external API/event/schema contracts unless evidence and reviewer approval justify it.
- Preserve auditability, authorization boundaries, and accounting invariants where applicable.

## Assumptions
- Safe assumptions:
  - Assigned branch/worktree and claim record are the only valid implementation coordinates.
  - Other slices may progress in parallel; coordinate via declared contracts, not cross-scope edits.
- Assumptions to validate:
  - Whether the root cause is local to this module or originates from an upstream dependency.
  - Whether upstream/downstream API, event, and schema contracts remain backward compatible.
  - Whether the fix preserves failure handling, idempotency, and observability under retries.
## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `ci/`
- `.github/workflows/`
- `scripts/`
- `docs/runbooks/`
- `docker-compose.yml`
- `erp-domain/Dockerfile`

## Requested Focus Paths
- `.github/workflows`
- `scripts/rag`
- `scripts/rag_impact.sh`
- `scripts/rag_index.sh`
- `scripts/rag_mcp_server.sh`
- `scripts/rag_query.sh`
- `scripts/rag_silent_failures.sh`

## Ticket-First Gate (Blocking)
- Assigned branch: `tickets/tkt-erp-stage-114/release-ops`
- Assigned worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/TKT-ERP-STAGE-114/release-ops`
- Base branches are read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`
- Claim evidence must be recorded in `ticket.yaml` and `TIMELINE.md` before edits.
- If any gate fails, stop and report blocker instead of patching.
- Mandatory orchestrator delegation sequence: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Acceptance Criteria
- Changes are limited to declared scope paths for release-ops.
- Deliver objective for: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh.
- Targeted tests for changed behavior are added/updated and passing.

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: data-migration
- External downstream agents to watch: none
- Contract edges:
  - upstream-external -> data-migration: migration rehearsal and release gating

## Required Checks Before Done
- `bash scripts/gate_release.sh`
- `bash scripts/gate_reconciliation.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Testing Responsibility Split
- Implementation agents own targeted tests for changed behavior in-slice.
- `merge-specialist` owns integration merge/conflict evidence before code-review phase.
- `qa-reliability` owns cross-workflow regression, gate evidence, and release-readiness signal.
- `release-ops` owns docs/release evidence sync before final merge.

## Agent Identity Contract
- First output line must be: `I am release-ops and I own SLICE-01.`

## Required Output Contract
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

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `release-ops`.
Start your first line with: `I am release-ops and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module` from `.codex/agents/cross_module.toml`.
Ticket title: RAG MCP Context Engine rollout
Feature objective: Deploy RAG MCP context engine for agent-safe ERP development with local dev tooling and CI sidecar; include usage docs and early regression guards for cross-module flows/idempotency mismatches.
Current failure: Implement ticket objective for scoped ownership paths: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh
Expected behavior: Required checks pass and acceptance criteria are satisfied.
Task scope summary: Implement ticket goal within explicit scope paths: .github/workflows, scripts/rag, scripts/rag_impact.sh, scripts/rag_index.sh, scripts/rag_mcp_server.sh, scripts/rag_query.sh, scripts/rag_silent_failures.sh

Your responsibility:
- Own all implementation decisions inside this slice boundary: `SLICE-01`.
- Diagnose root cause inside scope before patching.
- Validate whether failure is local or upstream/downstream.
- If dependency/contract issue is upstream, report evidence before changing contracts.
- Restore system integrity inside this boundary, not just local compilation.

Constraints:
- Stay inside assigned slice scope and write boundary.
- Do not break existing workflows tied to this module or contract surface.
- Do not alter external API/event/schema contracts unless evidence and reviewer approval justify it.
- Preserve auditability, authorization boundaries, and accounting invariants where applicable.

Assumptions that are safe:
- Assigned branch/worktree and claim record are the only valid implementation coordinates.
- Other slices may progress in parallel; coordinate via declared contracts, not cross-scope edits.

Assumptions that must be validated:
- Whether the root cause is local to this module or originates from an upstream dependency.
- Whether upstream/downstream API, event, and schema contracts remain backward compatible.
- Whether the fix preserves failure handling, idempotency, and observability under retries.

Execution minimum:
- validate current branch is `tickets/tkt-erp-stage-114/release-ops` and working directory is `/home/realnigga/Desktop/orchestrator_erp_worktrees/TKT-ERP-STAGE-114/release-ops`
- treat base branches as read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`
- confirm claim evidence exists in ticket.yaml + TIMELINE.md before edits
- diagnose current behavior in the requested focus paths
- perform codebase impact analysis (upstream dependencies, downstream consumers, contracts/events/APIs)
- implement root-cause fix in allowed scope with clear rationale
- add/adjust tests that prove acceptance criteria and no-regression behavior
- run required checks and report evidence

Required output:
- identity
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
- codebase_impact_analysis

You are not here to patch blindly.
You are here to restore system integrity within your boundary.
```
