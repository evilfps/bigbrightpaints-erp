# Code-review synthesis index

> ⚠️ **NON-CANONICAL**: This directory contains legacy review artifacts from a past production-backend audit. The canonical documentation is now maintained in the new docs tree under [docs/modules/](modules/), [docs/flows/](flows/), and [docs/INDEX.md](../INDEX.md).

This directory now holds the full review set for the production-backend audit. The index below links every completed review document, points to the milestone validation evidence under `.factory/validation/`, and records the environment limits that affected how much runtime corroboration the mission could gather.

The final review set carries **90 review findings** from the completed area reviews plus **5 mission-level evidence constraints** into the synthesis outputs, and it now includes the implementation-facing [remediation-backlog.md](./remediation-backlog.md) handoff for the follow-up fix mission.

## Review artifact map

### Architecture foundation

| Artifact | Purpose |
| --- | --- |
| [architecture-overview.md](./architecture-overview.md) | Platform shape, module boundaries, shared infrastructure, and entrypoint inventory. |
| [dependency-map.md](./dependency-map.md) | Cross-module dependencies, ownership seams, and coupling hotspots. |

### Foundation flow reviews

| Artifact | Purpose |
| --- | --- |
| [flows/company-tenant-control-plane.md](./flows/company-tenant-control-plane.md) | Tenant onboarding, company CRUD/lifecycle, module gating, runtime policy, and usage metrics. |
| [flows/auth-identity.md](./flows/auth-identity.md) | Login/session lifecycle, reset flows, MFA, temporary credentials, and auth protocol/security findings. |
| [flows/admin-governance.md](./flows/admin-governance.md) | Admin user management, changelog governance, support-ticket GitHub sync, system settings, and export approvals. |

### Commercial flow reviews

| Artifact | Purpose |
| --- | --- |
| [flows/order-to-cash.md](./flows/order-to-cash.md) | Dealer onboarding, credit controls, sales lifecycle, dispatch, invoicing, settlement, and AR truth boundaries. |
| [flows/procure-to-pay.md](./flows/procure-to-pay.md) | Supplier governance, purchase orders, goods receipt, returns, and AP linkage/invariants. |

### Operations flow reviews

| Artifact | Purpose |
| --- | --- |
| [flows/manufacturing-inventory.md](./flows/manufacturing-inventory.md) | Catalog/product-to-inventory linkage, stock movement flows, production, packing, valuation, and costing risks. |
| [flows/finance-reporting-audit.md](./flows/finance-reporting-audit.md) | Accounting controls, period close, reporting, payroll accounting, export governance, and audit trail behavior. |
| [flows/orchestrator-background-integration.md](./flows/orchestrator-background-integration.md) | Outbox publication, listeners, schedulers, dashboards, retries, traces, and recovery gaps. |
| [ops-deployment-runtime.md](./ops-deployment-runtime.md) | Compose/runtime topology, health/management surfaces, observability, resilience, and secrets posture. |

### Governance and synthesis artifacts

| Artifact | Purpose |
| --- | --- |
| [test-ci-governance.md](./test-ci-governance.md) | Test-layer inventory, CI gate classification, signal-vs-noise assessment, and missing hard controls. |
| [static-analysis-triage.md](./static-analysis-triage.md) | Legacy backlog triage model, hotspot concentration, and baseline/new-violations-only gate strategy. |
| [coverage-matrix.md](./coverage-matrix.md) | Cross-area mandatory-angle map showing where each required review angle is covered. |
| [risk-register.md](./risk-register.md) | Central register of 90 carried-forward review findings plus 5 mission-level evidence constraints. |
| [remediation-backlog.md](./remediation-backlog.md) | Prioritized next-mission fix backlog grouped into immediate workstreams and later cleanup/ratchet work. |

## Milestone validation evidence

| Milestone | Scrutiny synthesis | User-testing synthesis | Notes |
| --- | --- | --- | --- |
| Foundation review | [`../../.factory/validation/foundation-review/scrutiny/synthesis.json`](../../.factory/validation/foundation-review/scrutiny/synthesis.json) | [`../../.factory/validation/foundation-review/user-testing/synthesis.json`](../../.factory/validation/foundation-review/user-testing/synthesis.json) | Docs-first validation completed after helper-worker delegation failed and runtime probes degraded. |
| Commercial review | [`../../.factory/validation/commercial-review/scrutiny/synthesis.json`](../../.factory/validation/commercial-review/scrutiny/synthesis.json) | [`../../.factory/validation/commercial-review/user-testing/synthesis.json`](../../.factory/validation/commercial-review/user-testing/synthesis.json) | Commercial docs were validated through direct inspection because `8081`/`5433` probes were unreliable. |
| Operations review | [`../../.factory/validation/operations-review/scrutiny/synthesis.json`](../../.factory/validation/operations-review/scrutiny/synthesis.json) | [`../../.factory/validation/operations-review/user-testing/synthesis.json`](../../.factory/validation/operations-review/user-testing/synthesis.json) | Operations validation captured the strongest degraded-runtime evidence, including `9090` returning `503/DOWN` in one round. |
| Governance review | [`../../.factory/validation/governance-review/scrutiny/synthesis.json`](../../.factory/validation/governance-review/scrutiny/synthesis.json) | Pending | Scrutiny round 1 captured the README/remediation-backlog index drift on the prior head; this sync fix brings the review index back in line with the completed governance-review artifacts. |

## Mandatory-angle coverage

Every mandatory review angle from the mission plan is covered somewhere in the produced artifacts. Use [coverage-matrix.md](./coverage-matrix.md) for the exact cross-reference map. The covered angles are:

- cyber security
- data privacy
- protocol/protection handling
- bad-pattern detection
- state machines and invariants
- DB schema and migrations
- external integrations and side effects
- API/OpenAPI drift
- performance and scalability risk
- access logging and observability
- business-rule correctness and ERP data integrity
- incident recovery and resilience

## Mission-level evidence constraints

| Constraint | Evidence | Effect on this review set |
| --- | --- | --- |
| Runtime probes were inconsistent across review rounds: `8081/actuator/health` alternated between curl exit `7` and HTTP `404`, while `9090/actuator/health` is the intended management surface but also returned HTTP `503` with `{"status":"DOWN"}` in operations-review validation. | [ops-deployment-runtime.md](./ops-deployment-runtime.md), [`../../.factory/validation/operations-review/user-testing/synthesis.json`](../../.factory/validation/operations-review/user-testing/synthesis.json), [`../../.factory/validation/operations-review/user-testing/flows/operations-review-docs.json`](../../.factory/validation/operations-review/user-testing/flows/operations-review-docs.json), [`../../.factory/library/user-testing.md`](../../.factory/library/user-testing.md) | Runtime evidence is best-effort support only; docs/code-review remained the primary trustworthy validation surface for this mission. |
| The validator/benchmark Postgres surface on `5433` alternated between connection refusals and successful TCP connects across milestones. | [ops-deployment-runtime.md](./ops-deployment-runtime.md), [`../../.factory/validation/foundation-review/user-testing/synthesis.json`](../../.factory/validation/foundation-review/user-testing/synthesis.json), [`../../.factory/validation/commercial-review/user-testing/synthesis.json`](../../.factory/validation/commercial-review/user-testing/synthesis.json), [`../../.factory/validation/operations-review/user-testing/synthesis.json`](../../.factory/validation/operations-review/user-testing/synthesis.json) | Fresh validator/runtime corroboration could not be treated as a stable prerequisite for documentation-quality assertions. |
| Delegated helper-worker launches repeatedly failed with `Invalid model: custom:CLIProxyAPI-5.4-xhigh`, forcing validators to fall back to direct in-session inspection and manual synthesis artifacts. | [`../../.factory/validation/foundation-review/scrutiny/synthesis.json`](../../.factory/validation/foundation-review/scrutiny/synthesis.json), [`../../.factory/validation/commercial-review/scrutiny/synthesis.json`](../../.factory/validation/commercial-review/scrutiny/synthesis.json), [`../../.factory/validation/operations-review/scrutiny/synthesis.json`](../../.factory/validation/operations-review/scrutiny/synthesis.json), [`../../.factory/library/user-testing.md`](../../.factory/library/user-testing.md) | The mission remained achievable, but validation economics and evidence depth were worse than planned because automated review fan-out was unavailable. |
| Disk-space pressure prevented a fresh local Checkstyle reproduction during governance review (`No space left on device`). | [static-analysis-triage.md](./static-analysis-triage.md), [`../../.factory/library/user-testing.md`](../../.factory/library/user-testing.md) | Static-analysis synthesis had to reconstruct backlog shape from configuration and prior evidence instead of attaching a newly generated whole-repo report. |
| The worker session started with unrelated modified files outside the docs-only write surface (`.env.example`, `.env.prod.template`, `.factory/**`, `docker-compose.yml`, and several `erp-domain/**` files). | `git status --porcelain` at worker startup (and current session status), plus mission `AGENTS.md` dirty-worktree guidance | Review synthesis changes had to be isolated carefully, and the pre-existing dirty tree reduced clean-room confidence for any repo-wide post-edit checks. |

## Current verification state

- Baseline repo validation for this session succeeded with `mvn test -Pgate-fast -Djacoco.skip=true` (`394` tests, `0` failures, `0` errors).
- The review docs remain the primary validation surface because runtime probes and delegated validator surfaces were not reliable enough to serve as a hard prerequisite.
- The completed [remediation-backlog.md](./remediation-backlog.md) now consumes [risk-register.md](./risk-register.md) and the area reviews as the implementation-facing handoff for the next mission.
- Governance-review scrutiny evidence has been published, while governance-review user-testing synthesis is still pending publication at the time of this index refresh.
