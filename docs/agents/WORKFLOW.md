# Workflow Governance

Last reviewed: 2026-04-04

## Canonical Workflow Paths

### Review and merge workflow

1. **Feature branch creation** — work happens on feature branches off `main`.
2. **Pre-commit validation** — Spotless format, Checkstyle, compile, and lint hooks run locally.
3. **Push and CI** — `gate-fast` is the PR/push merge-confidence lane, `gate-core` is the main-branch integration lane, and `gate-release` plus `gate-reconciliation` run on tag/manual release validation. The separate `Release` workflow only generates GitHub release notes and is not deploy proof.
4. **Packet review** — orchestrator assigns reviewers based on change class.
5. **R2 escalation** — high-risk changes (auth, company, RBAC, HR, accounting, orchestrator, migration_v2) require updated `docs/approvals/R2-CHECKPOINT.md`.
6. **Merge** — only after CI green + reviewer approval.

### Docs-only workflow

- Docs-only packets are limited to the canonical docs/governance lane — `README.md`, `AGENTS.md`, `ARCHITECTURE.md`, `CHANGELOG.md`, `docs/INDEX.md`, `docs/ARCHITECTURE.md`, `docs/CONVENTIONS.md`, `docs/SECURITY.md`, `docs/RELIABILITY.md`, `docs/BACKEND-FEATURE-CATALOG.md`, `docs/RECOMMENDATIONS.md`, `docs/adrs/**`, `docs/agents/**`, `docs/approvals/**`, `docs/deprecated/**`, `docs/modules/**`, `docs/flows/**`, `docs/frontend-api/**`, `docs/frontend-portals/**` — or the internal worker-guidance lane `.factory/library/**`.
- Markdown outside that lane — including `docs/platform/**`, `docs/runbooks/**`, `docs/design/**`, `docs/code-review/**`, `docs/developer/**`, `docs/frontend-update-v2/**`, root worklogs/reports, or mixed markdown-plus-code/config/test/script/OpenAPI changes — is not docs-only.
- Docs-only packets run `bash ci/lint-knowledgebase.sh` only.
- Skip Codex review/subagent review for docs-only packets.
- Must not change backend runtime behavior.

### High-risk change workflow

- Must pass `bash ci/check-codex-review-guidelines.sh`.
- Must pass `bash ci/check-enterprise-policy.sh`.
- Must update `docs/approvals/R2-CHECKPOINT.md` with scope-specific evidence.
- Must update `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md` when touching `migration_v2`.

## Workflow Validators

| Validator | Purpose |
| --- | --- |
| `bash ci/lint-knowledgebase.sh` | Docs/governance file presence, freshness markers, link integrity |
| `bash ci/check-enterprise-policy.sh` | R2 escalation for high-risk paths |
| `bash ci/check-codex-review-guidelines.sh` | Review readiness for runtime/config/schema changes |
| `bash ci/check-architecture.sh` | Architecture invariant checks |
| `bash ci/check-orchestrator-layer.sh` | Orchestrator layer boundary checks |
| `bash scripts/guard_openapi_contract_drift.sh` | API contract drift detection |

## Required Governance Surfaces

- `AGENTS.md` — repository agent governance
- `docs/SECURITY.md` — security review policy
- `docs/agents/PERMISSIONS.md` — agent permission boundaries
- `docs/agents/CATALOG.md` — agent review catalog
- `docs/agents/WORKFLOW.md` — this document
- `docs/agents/ENTERPRISE_MODE.md` — enterprise mode controls
- `docs/agents/ORCHESTRATION_LAYER.md` — orchestration layer governance
- `docs/approvals/R2-CHECKPOINT.md` — active R2 evidence

## Cross-references

- [docs/agents/CATALOG.md](CATALOG.md) — agent catalog
- [docs/agents/PERMISSIONS.md](PERMISSIONS.md) — agent permissions
- [docs/agents/ENTERPRISE_MODE.md](ENTERPRISE_MODE.md) — enterprise mode
- [docs/agents/ORCHESTRATION_LAYER.md](ORCHESTRATION_LAYER.md) — orchestration layer
- [docs/SECURITY.md](../SECURITY.md) — security review policy
- [docs/approvals/R2-CHECKPOINT.md](../approvals/R2-CHECKPOINT.md) — R2 checkpoint
