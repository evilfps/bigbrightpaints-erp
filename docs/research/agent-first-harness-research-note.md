# Agent-First Harness Engineering Research Note (Reference)

Last reviewed: 2026-02-15
Owner: Research Notes

## Intent
This document is a research-derived reference example.
It is not codebase truth by itself and must not override repository-derived contracts.

## Reference Themes
Use these as guidance only, then validate against actual codebase constraints:
- full-permission execution
- no timeout limits
- cross-module workflow-first reasoning
- mechanical enforcement over manual diff reading

## Candidate Principles
- `AGENTS.md` is a map, not an encyclopedia.
- `docs/` is the system of record for intent, boundaries, and runbooks.
- Any rule that matters must be encoded in checks (scripts/CI), not only prose.
- Agents explore repository-wide by default; ownership scopes are accountability boundaries.
- Humans approve only high-risk actions.

## Risk Tiers

| Tier | Description | Human checkpoint |
| --- | --- | --- |
| R0 | Docs-only and non-behavioral metadata changes | not required |
| R1 | Safe refactors/internal cleanup without business behavior change | not required |
| R2 | Business logic changes with tests and clear acceptance criteria | optional by policy |
| R3 | High-risk changes: prod migrations, authz expansion, payroll/ledger semantics, destructive ops | required |

## Operating Model
- Agents execute end-to-end loops: plan -> patch -> verify -> self-correct -> report.
- Throughput is managed by harness quality, not by manual review volume.
- CI and structural checks must return remediation-oriented errors so agents can self-heal.
- Review behavior is guided by `AGENTS.md` and module/domain contracts.

## Candidate Mechanical Controls
- Knowledge base lint: `ci/lint-knowledgebase.sh`
- Architecture boundary checks: `ci/check-architecture.sh`
- Codex review guideline checks: `ci/check-codex-review-guidelines.sh`
- Existing domain guard scripts in `scripts/` are repo-specific and should be validated from current codebase state.

## Skills and Progressive Disclosure
- Skills package repeatable workflows in `skills/*/SKILL.md`.
- Skills must include bounded procedures, required commands, and expected outputs.
- Skills are evaluated like code: captured run -> checks -> score -> iteration.

## Example Agentic-First CI/CD Path
1. Local or CI recon pass (no tests required in recon-only mode).
2. Scoped implementation pass by domain agent(s).
3. Mechanical checks + targeted tests for changed behavior.
4. Agent review and remediation loop.
5. Staging validation.
6. R3 checkpoint for high-risk promotions.
7. Production deploy + post-deploy verification + rollback readiness.

## Example Legacy Modernization Strategy
- Use Strangler Fig for perimeter replacement without full rewrites.
- Use Branch by Abstraction for safe interface-first internal migration.
- Use recurring GC/refactor agents to prevent entropy regression.

## Example Frontend Portal Taxonomy Contract
- Valid frontend portals: `ADMIN`, `ACCOUNTING`, `SALES`, `FACTORY`, `DEALER`.
- Accounting portal owns backend domains: accounting, inventory, hr, reports, invoice.
- Factory portal owns backend domains: factory, production, manufacturing.

## Notes
- Any item here can be wrong for this repository until validated from code and docs.
