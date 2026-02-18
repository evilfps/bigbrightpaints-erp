# Agent Map (Table of Contents) - orchestrator_erp

Last reviewed: 2026-02-15

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
- Worker agents may own bounded implementation slices in parallel when paths are independent.
- Review agents must run on every commit with severity-tagged findings and file anchors.
- Docs-only exception: for docs-only commits, skip commit-review/subagent and run `bash ci/lint-knowledgebase.sh` only.
- Progressive-disclosure rule: agents must load only the docs needed for the current slice instead of bulk-reading all docs.
- Use lane selection to avoid rule overload:
  - `fast_lane` for low-risk docs/guards/refactors with targeted checks.
  - `strict_lane` for accounting, auth, migrations, orchestrator semantics with full harness ladder.

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

## Agent-First Workflow Contract
- Define acceptance criteria before edits.
- Patch minimally.
- Run harness.
- Diagnose with concrete evidence.
- Self-correct and rerun.
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
