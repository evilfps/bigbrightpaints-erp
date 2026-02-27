# Agent Map (Table of Contents) - orchestrator_erp

Last reviewed: 2026-02-27

This file is intentionally short.
It is a map, not an encyclopedia.
Depth belongs in `docs/` and `skills/`.
Human-friendly alias: `AGENTMAP.md`.

## Mission
- Run agent-first with measurable harness checks.
- Keep loops long-running and low-touch.
- Escalate only when risk is high or intent is ambiguous.
- Allow full-repository exploration for analysis/recon before scoped edits.
- Operate with enterprise-autonomous write policy and no timeout limits by default.
- Require proof-backed decisions (tests/guards/traces), never assumption-backed decisions.
- Prioritize cross-module workflow correctness over single-module local optimization.

## Operating Identity
- The orchestrator is a high-legitimacy engineering control system, not a blind task executor.
- Operate with architectural intent, authority, and system context; do not optimize for local task closure at the cost of system coherence.

## Canonical Sources (Progressive Disclosure)
1. `docs/INDEX.md` (repo snapshot + doc map)
2. `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md` (final stability-first staging plan)
3. `docs/system-map/Goal/ENTERPRISE_BACKEND_STABILIZATION_PLAN.md` (execution control-plane)
4. `docs/system-map/Goal/ERP_ENTERPRISE_DEPLOYMENT_DEEP_SPEC.md` (domain + deployment blueprint)
5. `docs/ARCHITECTURE.md` (boundaries + dependency rules)
6. `docs/agents/CATALOG.md` (who does what)
7. `docs/agents/PERMISSIONS.md` (who can do what)
8. `docs/agents/WORKFLOW.md` (lifecycle, CI/CD, rollback)
9. `docs/agents/ENTERPRISE_MODE.md` (near-deployment policy profiles)
10. `docs/agents/ORCHESTRATION_LAYER.md` (real orchestrator control plane)
11. Domain contracts: `docs/INDEX.md`
12. Async loop runbook: `docs/ASYNC_LOOP_OPERATIONS.md` + `asyncloop`

## Autonomous Throughput Policy
- This repository is not single-agent-only. Multi-agent execution is the default for large slices.
- Primary orchestrator agent owns intent, safety, and final merge quality.
- Planner agent defines scope boundaries, module impact, constraints, and success criteria; planner does not micromanage implementation details.
- Implementation agents own bounded module slices and have reasoning authority within scope; they must account for cross-module effects.
- Parallel execution is expected; each implementation agent works in its own isolated worktree/branch.
- Code-reviewer role performs deep module-level review (correctness, security, performance, test quality).
- Merge-specialist role is an integration integrity gate, not a conflict button; it validates contracts, hidden coupling, observability, and merge risk.
- QA-reliability role performs exploratory and workflow-level validation across modules.
- Review agents must run on every commit with severity-tagged findings and file anchors.
- Docs-only exception: for docs-only commits, skip commit-review/subagent and run `bash ci/lint-knowledgebase.sh` only.
- Progressive-disclosure rule: agents must load only the docs needed for the current slice instead of bulk-reading all docs.
- Use lane selection to avoid rule overload:
  - `fast_lane` for low-risk docs/guards/refactors with targeted checks.
  - `strict_lane` for accounting, auth, migrations, orchestrator semantics with full harness ladder.
- Delivery objective is parallel velocity with architectural stability, not speed-only task completion.

## Build / Run / Test (Detected)
- Build: `cd erp-domain && mvn -B -ntp -DskipTests package`
- Run local stack: `docker compose up --build`
- Run tests: `cd erp-domain && mvn -B -ntp test`
- Harness: `bash scripts/verify_local.sh`
- Gate tiers:
  - `bash scripts/gate_fast.sh`
  - `bash scripts/gate_core.sh`
  - `bash scripts/gate_release.sh`
  - `bash scripts/gate_reconciliation.sh`

## Non-Negotiable Safety
- No history rewrite, branch deletion, or force push without explicit approval.
- Never discard unknown local changes.
- If unexpected diffs or missing files appear, stop and report.
- Never bypass accounting/migration/reconciliation guards just to get green CI.

## Review Guidelines (Required)
- PII: enforce redaction and avoid sensitive payload logging (`docs/SECURITY.md`).
- AuthZ/RBAC/company isolation: verify fail-closed semantics and tenant scope checks.
- Migrations: use Flyway v2 policy and run drift/overlap guards.
- Posting rules: preserve double-entry, period locks, idempotency, and reconciliation links.
- Frontend docs taxonomy: Accounting Portal owns HR/Inventory/Accounting/Reports/Invoice; Factory Portal owns Production/Manufacturing/Factory.
- For risky edits, add or update tests before marking done.

## Decision Checkpoints
- `R1` Orchestrator intent checkpoint when requirements conflict.
- `R2` Orchestrator risk checkpoint for high-risk semantics with proof pack.
- `R3` Human checkpoint only for irreversible production actions and final production go/no-go.

## Harness Ladder (Cheapest -> Broadest)
1. Targeted checks tied to changed files.
2. `bash ci/lint-knowledgebase.sh`
3. `bash ci/check-architecture.sh`
4. `bash ci/check-enterprise-policy.sh`
5. `bash ci/check-orchestrator-layer.sh`
6. `bash scripts/verify_local.sh`
7. CI parity gates as needed.

## RAG MCP Playbook
Use the RAG sidecar as a verification helper, never as the source of truth.

### Non-Negotiable Rules
- Always work revision-locked on the current branch with the latest pulled code.
- Cite-or-refuse: if there is no file/line evidence, explicitly state `can't confirm`.
- Never edit before running `rag_guarded_query` and `rag_dedupe_resolve`.
- Run `rag_patch_guard` before every PR/update.
- If canonical/duplicate resolution is ambiguous, mark `needs_review` and block risky edits until evidence is strengthened.

### Daily Workflow (All Agents)
1. Sync branch and refresh index:
   - `BASE="$(git merge-base HEAD origin/harness-engineering-orchestrator)"`
   - `bash scripts/rag_index.sh --changed-only --diff-base "$BASE"`
   - `bash scripts/rag_silent_failures.sh --json`
2. Understand task context:
   - `rag_ticket_context`
   - `rag_agent_slices`
3. Find exact implementation path:
   - `rag_guarded_query` (where to edit, call-chain)
   - `rag_dedupe_resolve` (canonical vs duplicate)
4. Before coding:
   - `rag_impact` / `rag_patch_guard` (blast radius + risk)
5. After coding:
   - `rag_patch_guard` again
   - `rag_verify_claims` for PR summary truthfulness

### Prompt Format For Agents
- `Use MCP only with cite-or-refuse.`
- Return exactly: files to edit, why canonical, affected modules, risks, required tests.
- If evidence is weak, return `needs_review` and do not guess.

### Token-Efficient Defaults
- `rag_guarded_query`: `top_k=6..8`, `depth=2`, `candidate_limit<=180`.
- Keep payload compact by default; expand only for deep incident debugging.

### Management Checklist
Require each update to include:
1. Proposed edit files.
2. Call-chain evidence.
3. Cross-module impact list.
4. Silent-failure findings in touched flow.
5. Patch-guard result (`PASS`/`WARN`/`FAIL`).

Reject updates with no citations.
Reject edits to non-canonical duplicates unless evidence proves why.

### Why Verification Is Mandatory
- Static retrieval can produce false positives in complex ERP code.
- Duplicate flows can appear valid while being non-canonical.
- `rag_patch_guard` and `rag_verify_claims` reduce silent regressions and overconfident output.

## Agent-First Workflow Contract
- Planner defines architecture intent and boundaries first.
- Implementation proceeds in isolated parallel slices when dependencies allow.
- Deep module review happens before integration merge decisions.
- Merge specialist performs semantic integration integrity review before merge.
- QA performs cross-workflow exploratory validation after integration.
- Release is promoted only after required gates and evidence are green.
- Keep artifacts/logs linked in task output.
- For cross-module changes, follow contract-first order:
  - contracts/events first
  - producer second
  - consumers third
  - orchestrator last

## Where To Add Detail
- New long-lived process docs: `docs/` + link in `docs/INDEX.md`.
- Agent definitions: `agents/*.agent.yaml` and `agents/catalog.yaml`.
- Repeatable workflows: `skills/` (`skills/*/SKILL.md` per skill, plus optional `scripts/` templates).
- Risk/governance updates: `docs/SECURITY.md`, `docs/RELIABILITY.md`, `docs/runbooks/`.

## Module Overrides (Closest Equivalent Paths)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/AGENTS.md`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/AGENTS.md`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/AGENTS.md`
- All remaining module ownership and `orchestrator` ownership are defined in `docs/agents/CATALOG.md` and `agents/catalog.yaml`.

## Exploration Policy
- Agents may read the full repository for discovery (`erp-domain`, `scripts`, `docs`, `.github/workflows`, `testing`).
- Write scope remains agent-specific (see `agents/*.agent.yaml`).
- Recon-only passes can skip tests; code-change passes must run appropriate harness checks.

## Output Contract (Every Task)
Return:
- files changed
- commands run
- harness results
- residual risks
- exact blocker + missing evidence if unresolved

## Dependency Change Proof Rule
- Any allowlist dependency edge update must include ADR evidence with:
  - why needed
  - alternatives rejected
  - boundary preserved
