# Repository Agent Governance

Last reviewed: 2026-04-04

## Code Style & Formatting

- **Formatter**: Spotless Maven plugin enforces Google Java Format across all source files
- **Run check**: `cd erp-domain && MIGRATION_SET=v2 mvn spotless:check`
- **Auto-format**: `cd erp-domain && MIGRATION_SET=v2 mvn spotless:apply`
- **CI integration**: Formatting check runs automatically during `verify` phase
- **Style**: Google Java Format with import ordering (java, javax, org, com, com.bigbrightpaints)

## Pre-commit Hooks

- **Setup**: `pip install pre-commit && pre-commit install`
- **Run manually**: `pre-commit run --all-files`
- **Hooks configured**:
  - Spotless format check (Java)
  - Checkstyle (Java)
  - Maven compile (syntax validation)
  - Trailing whitespace, file endings
  - YAML/JSON syntax checks
  - Markdown linting
  - Shell script linting (scripts/, ci/)

## Review Guidelines (Required)

- Use `Factory-droid` as the integration base for remediation packet review unless a packet explicitly states a narrower stacked-review base.
- Treat a packet as docs-only only when every changed file stays inside the canonical docs/governance lane — repo-root `README.md`, `AGENTS.md`, `ARCHITECTURE.md`, `CHANGELOG.md`; canonical docs spine files `docs/INDEX.md`, `docs/ARCHITECTURE.md`, `docs/CONVENTIONS.md`, `docs/SECURITY.md`, `docs/RELIABILITY.md`, `docs/BACKEND-FEATURE-CATALOG.md`, `docs/RECOMMENDATIONS.md`; canonical directories `docs/adrs/**`, `docs/agents/**`, `docs/approvals/**`, `docs/deprecated/**`, `docs/modules/**`, `docs/flows/**`, `docs/frontend-api/**`, `docs/frontend-portals/**`; or the internal worker-guidance lane `.factory/library/**`. In those docs-only lanes, run `bash ci/lint-knowledgebase.sh` only and skip Codex review/subagent/runtime validators.
- Markdown elsewhere — including `docs/platform/**`, `docs/runbooks/**`, `docs/design/**`, `docs/code-review/**`, `docs/developer/**`, `docs/frontend-update-v2/**`, root worklogs/reports, or mixed markdown-plus-code/config/test/script/OpenAPI changes — is not docs-only.
- Any runtime, config, schema, or test-impacting packet must pass `bash ci/check-codex-review-guidelines.sh` before it is considered review-ready.
- High-risk auth, company, RBAC, HR, accounting, orchestrator, or `erp-domain/src/main/resources/db/migration_v2/` changes must update `docs/approvals/R2-CHECKPOINT.md` in the same packet with scope-specific evidence.
- Review workers may prepare packet/release-gate evidence and commit docs-only governance fixes, but they must never push, merge, or rewrite history.

## R2 Escalation Checkpoint

- Trigger R2 whenever the packet touches high-risk paths enforced by `ci/check-enterprise-policy.sh`.
- Record the exact scope, approval mode, escalation decision, rollback owner, expiry, and verification evidence in `docs/approvals/R2-CHECKPOINT.md`.
- Use orchestrator approval for compatibility-preserving high-risk remediation packets; escalate to human approval if the packet widens privileges, changes tenant boundaries, or introduces destructive migration risk.
- Do not treat degraded runtime evidence as a waiver for product-correctness proof.

## Governance References

- Security posture: `docs/SECURITY.md`
- Agent permission boundaries: `docs/agents/PERMISSIONS.md`
- Review/governance role catalog: `docs/agents/CATALOG.md`
- Active R2 evidence: `docs/approvals/R2-CHECKPOINT.md`
