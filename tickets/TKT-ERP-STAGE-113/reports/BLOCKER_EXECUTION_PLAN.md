# TKT-ERP-STAGE-113 Blocker Execution Plan

Date: 2026-02-26  
Ticket: `TKT-ERP-STAGE-113`  
Planning mode: `planning-only` (no implementation changes in this artifact)

## Problem Framing And Assumptions

The Stage-112 production readiness synthesis is `NO-GO` with 14 unresolved critical/high blockers that cut across auth, orchestrator runtime, sales, inventory, HR, P2P, factory, reports, and governance (`production-readiness-debug-master.md`). This ticket must convert those blockers into isolated execution slices so remediation can be merged safely one blocker at a time with full review and QA lineage.

Assumptions used in this plan:
- Canonical blocker inventory is the 14-item severity matrix in `tickets/TKT-ERP-STAGE-112/reports/production-readiness-debug-master.md` (dated 2026-02-26).
- Because `tickets/TKT-ERP-STAGE-112/...` is not present in this Stage-113 worktree, blocker evidence was read from the canonical Stage-112 audit snapshot at:
  - `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-112/deep-debug-audit/tickets/TKT-ERP-STAGE-112/reports/...`
- All non-doc blocker slices must follow the mandatory role sequence from `AGENTS.md` and `agents/orchestrator-layer.yaml`:
  - `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`
- `one blocker = one branch = one worktree`; no blocker shares a worktree.

## Scope Boundaries And Non-Goals

In scope:
- Convert all Stage-112 critical/high blockers into blocker-level execution slices.
- Assign implementation owner, merge/review/QA/release roles per blocker.
- Define per-blocker acceptance criteria, verification strategy, and merge gates.
- Define dependency-aware wave order and cleanup policy.

Out of scope:
- Implementing fixes.
- Merging any branch.
- Final code review disposition or QA sign-off.
- Reclassifying blocker severities from Stage-112.

## Canonical Blocker Set For Stage-113

Critical:
- `B01` Tenant isolation breach via tenant-admin mutation of global role permissions.
- `B02` Orchestrator outbox publish path not commit-atomic.
- `B03` Sales lifecycle fail-open (`confirmOrder`) and return continuity break.
- `B04` Inventory dispatchability mismatch allows canceled slips into execution.
- `B05` Payroll correction/reversal lifecycle missing after posting.
- `B06` P2P full return/void does not reopen GRN/PO lifecycle continuity.
- `B07` Governance traceability gaps across slice worktrees.

High:
- `B08` Hardcoded/predictable credentials or static secret exposure paths.
- `B09` Correlation/idempotency sanitization gaps in logs and memos.
- `B10` Auth recovery continuity gaps (MFA step-up, reset throttling, switch-token handoff).
- `B11` Factory lifecycle lacks cancel/rework/reopen and recoverable plan history.
- `B12` Reports/admin export recovery and denial-envelope inconsistency.
- `B13` Inventory correction/audit parity gaps (no reversal chain).
- `B14` Local verification portability blocker (`verify_local.sh` fails on Bash 3.2 `mapfile`).

## Execution Model

Isolation and ownership rules:
- Every blocker runs on a dedicated blocker branch and dedicated blocker worktree.
- Every blocker has one implementation owner (primary role) and fixed handoff owners:
  - Merge: `merge-specialist`
  - Review: `code_reviewer` (+ `security-governance` for high-risk semantics)
  - QA: `qa-reliability`
  - Release gate and docs sync: `release-ops`
- No two blockers are merged together; merge order follows dependency order and `branch_execution_policy` (one slice branch at a time, rerun checks on merged base after each merge).

Required gate stack before blocker can move to merge:
- `bash ci/check-architecture.sh`
- `bash ci/check-enterprise-policy.sh`
- `bash ci/check-orchestrator-layer.sh`
- `DIFF_BASE=origin/harness-engineering-orchestrator bash scripts/gate_fast.sh`
- `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator` (mandatory when runtime Java sources change)
- Blocker-targeted Maven suites listed in matrix

## Wave-by-Wave Slice Execution Order

### Wave 0 (Platform Safety + Deterministic Verification)

Order and dependencies:
1. `B07` governance traceability baseline (no dependencies)  
2. `B01` tenant isolation fail-closed (`depends_on: B07`)  
3. `B08` credential/secret hardening (`depends_on: B01`)  
4. `B02` outbox commit atomicity (`depends_on: B07`)  
5. `B09` correlation/idempotency sanitization (`depends_on: B02`)  
6. `B14` `verify_local` portability unblock (`depends_on: B07`)

Wave 0 exit:
- Ticket claim artifacts are complete for all active blocker branches.
- Auth and runtime fail-closed guardrails are merged independently.
- Local verification chain is runnable on supported macOS Bash baseline or documented parity shell.

### Wave 1 (Cross-Domain Reversal/Correction Continuity)

Order and dependencies:
1. `B03` sales fail-closed lifecycle (`depends_on: B01, B02`)  
2. `B04` inventory dispatch eligibility parity (`depends_on: B03`)  
3. `B13` inventory adjustment reversal chain (`depends_on: B04`)  
4. `B05` payroll correction/reversal lifecycle (`depends_on: B01`)  
5. `B06` P2P return/void lifecycle reopen (`depends_on: B01, B04`)

Wave 1 exit:
- O2C/P2P/Inventory/HR correction and reversal contracts pass targeted and cross-module tests.
- No open critical blockers remain.

### Wave 2 (Recovery + Operator Continuity)

Order and dependencies:
1. `B10` auth recovery continuity (`depends_on: B01, B08`)  
2. `B11` factory cancel/rework/reopen continuity (`depends_on: B01, B04`)  
3. `B12` reports export recovery + denial envelope normalization (`depends_on: B01, B09`)

Wave 2 exit:
- Auth recovery and factory/reports operator continuity blockers are closed with evidence.
- No open high blockers remain.

### Wave 3 (Integrated Release Readiness Checkpoint)

Dependencies: all blockers `B01-B14` merged in sequence.

Execution owners:
- `qa-reliability` executes integrated regression and cross-workflow proof pack.
- `release-ops` publishes immutable release evidence bundle.
- Human `R3` approver records final GO/NO-GO decision.

Wave 3 exit:
- Integrated checks green on same base SHA.
- No unresolved critical/high blockers.
- `R3` decision captured in ticket artifacts.

## Per-Blocker Role Assignment Contract

Applies to every blocker slice in matrix:
- Implementation role: blocker owner listed in matrix row.
- Merge role: `merge-specialist` (must run after implementation evidence is attached).
- Review role: `code_reviewer` with severity-tagged findings and file anchors; add `security-governance` review for auth/security/orchestrator/high-risk blockers.
- QA role: `qa-reliability` after code-review timestamp on same head SHA.
- Release role: `release-ops` for docs sync, release artifact refresh, and merge authorization gate.

## Acceptance Criteria And Verification Strategy Per Slice

Per-slice criteria and checks are execution-bound in the matrix file:
- See `tickets/TKT-ERP-STAGE-113/reports/BLOCKER_SLICE_MATRIX.md`.
- Each blocker row includes:
  - explicit acceptance criteria,
  - blocker-targeted tests,
  - mandatory gate commands,
  - evidence artifacts to attach,
  - gate handoff conditions from merge-specialist through release-ops.

## OpenAPI-Informed API Change Strategy Per Blocker

Reference analysis:
- `tickets/TKT-ERP-STAGE-113/reports/OPENAPI_DUPLICATE_ROUTE_ANALYSIS.md`
- Route source: `/openapi.json` (cross-checked with `/erp-domain/openapi.json`).

Strategy distribution (preference honored):
- `extend_existing`: 8 blockers
- `deprecate_alias`: 5 blockers
- `new_endpoint_required`: 1 blocker (`B06` only)

| blocker_id | api_change_strategy | duplicate/similar route handling note |
| --- | --- | --- |
| B01 | `extend_existing` | No duplicate role mutation API found; keep `/api/v1/admin/roles*` canonical and harden tenant/global permission mutation guards. |
| B02 | `deprecate_alias` | Consolidate orchestrator dispatch onto canonical `/api/v1/orchestrator/dispatch/{orderId}`; keep body alias temporarily with explicit deprecation. |
| B03 | `extend_existing` | Keep sales confirm and orchestrator approve/fulfillment routes, but enforce one shared fail-closed lifecycle/state-machine service. |
| B04 | `deprecate_alias` | Treat `/api/v1/sales/dispatch/confirm` as canonical; transition `/api/v1/dispatch/confirm` to compatibility alias then retire. |
| B05 | `deprecate_alias` | Canonicalize payroll run lifecycle under `/api/v1/payroll/runs*`; migrate `/api/v1/hr/payroll-runs` to alias then remove. |
| B06 | `new_endpoint_required` | Reuse existing purchasing routes first; add only missing reopen/void transition endpoints where PO/GRN lifecycle cannot be represented today. |
| B07 | `extend_existing` | Non-API governance blocker; no OpenAPI duplication surface, process artifacts stay in existing ticket/timeline contracts. |
| B08 | `extend_existing` | Reuse existing auth/admin credential and reset routes; enforce shared secret policy and fail-closed behavior without new root APIs. |
| B09 | `deprecate_alias` | Remove duplicate dispatch ingress patterns so correlation/idempotency sanitation is applied through one canonical route path. |
| B10 | `extend_existing` | Keep recovery endpoints but unify `/auth/me` vs `/auth/profile` read behavior and MFA disable policy service across admin/self routes. |
| B11 | `extend_existing` | Use `/api/v1/factory/production-plans/{id}/status` as lifecycle source of truth; constrain PUT and phase out destructive delete semantics. |
| B12 | `deprecate_alias` | Consolidate dealer/reporting read contracts to canonical accounting/invoice surfaces; keep portal facades during migration period. |
| B13 | `extend_existing` | Centralize correction semantics in `/api/v1/inventory/adjustments`; accounting revaluation/WIP routes become strict adapters. |
| B14 | `extend_existing` | Non-API shell portability blocker; no OpenAPI route changes required. |

## Cross-Module Impact Map

| Blocker | Upstream contracts | Downstream consumers | Migration/Auth/Accounting risk |
| --- | --- | --- | --- |
| B01 | Auth+RBAC tenant context and role mutation API | All module authorization gates | **Auth critical**: cross-tenant privilege escalation if fail-open |
| B02 | Orchestrator command + outbox transaction contract | Accounting, inventory, sales side effects | **Accounting critical**: duplicate postings under commit/publish race |
| B03 | Sales order state machine + return/reversal API | Inventory dispatch, accounting receivables | **Accounting high**: invalid O2C reversals and receivable drift |
| B04 | Inventory dispatch eligibility interface with sales | Dispatch execution, stock ledger, accounting adjustments | **Accounting high**: stock-to-ledger mismatch risk |
| B05 | Payroll posting lifecycle + correction API | Accounting payroll liability and disbursement reconciliation | **Accounting critical**: payroll close divergence |
| B06 | P2P return/void lifecycle contract (PO/GRN/AP) | Inventory intake and AP settlement | **Accounting critical**: AP reconciliation dead-end |
| B07 | Ticket claim/timeline governance contract | Merge lineage, incident forensics, QA audit trail | **Governance critical**: cannot prove ownership chain |
| B08 | Secret/credential bootstrap and rotation interfaces | Auth login/recovery and admin provisioning | **Auth high**: credential compromise blast radius |
| B09 | Correlation/idempotency header sanitation contract | Observability, audit logs, replay safety | **Auth+Accounting high**: audit poisoning / replay ambiguity |
| B10 | MFA/password reset/switch-token continuity API | Support operations, tenant admins, security monitoring | **Auth high**: user lockout/recovery nondeterminism |
| B11 | Factory production/factory task lifecycle interfaces | Inventory movements, manufacturing truthsuite | **Accounting high**: production-to-stock continuity breaks |
| B12 | Reports export lifecycle + denial envelope schema | Admin portal clients and support runbooks | **Auth high**: inconsistent denial contract + support failure |
| B13 | Inventory adjustment and reversal chain contract | Accounting inventory valuation and audit trail | **Accounting high**: non-reversible correction path |
| B14 | Local verification shell compatibility interface | Developer preflight and release reproducibility | **Migration/Release high**: cannot complete full local gate chain |

## Risk Register And Rollback Strategy

| Risk ID | Trigger | Impact | Mitigation owner | Rollback strategy |
| --- | --- | --- | --- | --- |
| R-01 | Cross-blocker scope overlap in same files | Regressions and blocked merges | Orchestrator + merge-specialist | Revert only blocker branch merge commit; keep other blocker branches untouched |
| R-02 | Contract drift between auth and downstream modules | Runtime authorization failures | Auth owner + code reviewer | Roll back blocker merge and restore previous contract payload while preserving audit trail |
| R-03 | Outbox/idempotency changes cause duplicate or lost events | Financial/inventory inconsistency | Orchestrator-runtime + QA | Roll back B02/B09 merge commits and replay from known good outbox state via runbook |
| R-04 | Reversal workflow change breaks period-close behavior | Accounting close failure | Accounting-domain + QA | Roll back affected blocker (B03/B04/B05/B06/B13) and freeze close jobs until patched |
| R-05 | `verify_local` portability fix is shell-specific only | False confidence in preflight gates | Orchestrator + release-ops | Revert B14 and enforce documented alternate shell gate until portable fix lands |
| R-06 | Missing claim/timeline artifacts on blocker branches | Audit/release governance violation | Orchestrator + release-ops | Block merge; no rollback needed, complete artifacts before retry |

Rollback policy notes:
- Roll back per blocker merge commit only; never use destructive history rewrite.
- Preserve ticket timeline evidence for failed attempt, then respawn blocker worktree from latest base.

## Handoff Gates Per Blocker (Mandatory)

1. Implementation handoff gate (owner role)
- Acceptance criteria in matrix row are met.
- Required tests and gates pass and logs are attached.
- Ticket claim fields (`agent_id`, `slice_id/blocker_id`, `worktree_path`, `branch`, timestamp) are present.

2. Merge-specialist gate
- Validate no scope violation and no cross-slice overlap.
- Validate blocker branch is merge-ready against latest base.
- Record explicit `GO/BLOCK` rationale per blocker.

3. Code-review gate
- Severity-tagged findings with file anchors.
- High-risk blockers require `security-governance` co-review.
- All blocking findings resolved on same head SHA.

4. QA-reliability gate
- Re-run blocker-targeted suites and required check stack on head SHA.
- Validate cross-module behavior for declared contract participants.
- Record pass/fail evidence paths.

5. Release-ops gate
- Ensure docs/evidence sync in ticket reports/timeline.
- Confirm mandatory checks and gate evidence are complete.
- Authorize merge sequencing and post-merge cleanup.

## Post-Merge Cleanup Plan

Per blocker, after merge confirmation:
- Remove local blocker worktree:
  - `git worktree remove <blocker-worktree-path>`
- Delete blocker branch only if it was orchestrator-created and merged:
  - `git branch -d <blocker-branch>`
  - if remote branch exists and policy allows: `git push origin --delete <blocker-branch>`
- Update ticket timeline with:
  - merged SHA,
  - gate command evidence summary,
  - cleanup completion evidence.

Cleanup policy constraints from orchestrator-layer:
- `cleanup_worktrees_on_merge: true`
- `cleanup_owned_branches_on_merge: true`
- `cleanup_branch_scope: orchestrator_created_only`
- merge one blocker branch at a time; rerun checks on base after each merge.
