# Remediation backlog

This backlog is the implementation-facing handoff for the next mission. It consumes the stable finding IDs from [risk-register.md](./risk-register.md) and points back to the originating review docs so the follow-up work can start from already-collected evidence instead of re-running the audit.

Rows **1-8** are the **immediate-fix wave**: they either carry critical/high exploitability, can corrupt ERP truth, expose cross-tenant/global control surfaces, or are prerequisites for safely changing later flows. Rows **9-11** are **later cleanup / ratchet work** that should follow once the immediate safety rails and canonical contracts are in place.

## Executable-spec package

Use the backlog for priority and lane ownership. Use the executable-spec package for the broader mission order and the narrow packets inside each lane:

- [executable-specs/README.md](./executable-specs/README.md)
- [executable-specs/00-program-map.md](./executable-specs/00-program-map.md)
- [executable-specs/PACKET-TEMPLATE.md](./executable-specs/PACKET-TEMPLATE.md)
- [executable-specs/VALIDATION-FIRST-BUNDLE.md](./executable-specs/VALIDATION-FIRST-BUNDLE.md)
- [executable-specs/RELEASE-GATE.md](./executable-specs/RELEASE-GATE.md)
- row 1 -> [executable-specs/01-lane-control-plane-runtime/EXEC-SPEC.md](./executable-specs/01-lane-control-plane-runtime/EXEC-SPEC.md)
- row 2 -> [executable-specs/02-lane-auth-secrets-incident/EXEC-SPEC.md](./executable-specs/02-lane-auth-secrets-incident/EXEC-SPEC.md)
- row 3 -> [executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md](./executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md)
- row 4 -> [executable-specs/04-lane-commercial-workflows/EXEC-SPEC.md](./executable-specs/04-lane-commercial-workflows/EXEC-SPEC.md)
- row 5 -> [executable-specs/05-lane-catalog-manufacturing/EXEC-SPEC.md](./executable-specs/05-lane-catalog-manufacturing/EXEC-SPEC.md)
- row 6 -> [executable-specs/06-lane-governance-finance/EXEC-SPEC.md](./executable-specs/06-lane-governance-finance/EXEC-SPEC.md)
- row 7 -> [executable-specs/07-lane-orchestrator-ops/EXEC-SPEC.md](./executable-specs/07-lane-orchestrator-ops/EXEC-SPEC.md)
- row 8 -> [executable-specs/08-lane-quality-governance/EXEC-SPEC.md](./executable-specs/08-lane-quality-governance/EXEC-SPEC.md)

Related companion plan:
- row 5 also consumes the catalog/material authority-migration package at `/home/realnigga/Desktop/mission-control-refactor-specs/catalog-materials-refactor/README.md`

Execution rule:
- every slice should start from [PACKET-TEMPLATE.md](./executable-specs/PACKET-TEMPLATE.md) and should not close until [RELEASE-GATE.md](./executable-specs/RELEASE-GATE.md) is satisfied for that lane
- every validation-first finding should carry a completed [VALIDATION-FIRST-BUNDLE.md](./executable-specs/VALIDATION-FIRST-BUNDLE.md) before it is promoted into backend implementation scope

## Prioritization method

The backlog order uses these factors together rather than severity alone:

1. **Severity** — `critical` and `high` findings rise first.
2. **Exploitability** — direct tenant-admin abuse, bearer-secret replay, and external-data exfiltration outrank internal polish work.
3. **ERP integrity impact** — anything that can double-post GL, drift inventory/AR/AP truth, or bypass maker-checker controls is promoted.
4. **Operational risk** — health/recovery blind spots and mission-environment failures move earlier when they would make the next remediation wave unsafe or hard to verify.
5. **Implementation sequencing** — shared contracts (tenant lifecycle, auth secret handling, accounting truth boundaries, CI gates) come before surface cleanup.
6. **Cleanup vs immediate-fix split** — drift, dead-route cleanup, topology modernization, and style/hotspot reduction are intentionally deferred until the core control and integrity fixes land.

### Priority tiers

| Tier | Meaning |
| --- | --- |
| **P0** | Start first. Cross-tenant/global blast radius, direct credential or secret exposure, or critical ERP truth-boundary risk. |
| **P1** | Next wave. High/medium issues that still threaten business correctness, governance, observability, or safe rollout if left behind. |
| **P2** | Later cleanup after P0-P1 contracts are stable. Mostly drift, dead-surface removal, or deployment-shape improvements. |
| **P3** | Background ratchet work after baseline/gates exist. Focus on hotspot decomposition and style-noise reduction. |

## Immediate-fix workstreams for the next mission

All IDs below are stable references into [risk-register.md](./risk-register.md).

| Seq | Priority | Workstream | Why now / sequencing rationale | Included finding IDs | Primary source docs |
| ---: | --- | --- | --- | --- | --- |
| 1 | P0 | Lock down the global control plane and unify tenant lifecycle/runtime policy contracts | `ADMIN-01` is the single highest governance defect in the review set, and it combines badly with the tenant-policy drift documented in the control-plane reviews. Fix the global/tenant scoping model and lifecycle contract first so later auth, export, and ops remediations land on one canonical control plane. | `TEN-01`, `TEN-02`, `TEN-04`, `TEN-05`, `TEN-06`, `TEN-07`, `ADMIN-01`, `ADMIN-09`, `OPS-06` | [flows/company-tenant-control-plane.md](./flows/company-tenant-control-plane.md), [flows/admin-governance.md](./flows/admin-governance.md), [ops-deployment-runtime.md](./ops-deployment-runtime.md) |
| 2 | P0 | Harden credentials, reset flows, bearer-secret storage, and incident-response auth controls | These findings create the clearest direct security exposure: leaked onboarding credentials, plaintext reset/refresh secrets, weak forced-password-change enforcement, and inconsistent cross-tenant incident-response authority. Do this before any user-management or support-flow expansion. | `TEN-03`, `AUTH-01`, `AUTH-02`, `AUTH-03`, `AUTH-04`, `AUTH-05`, `AUTH-06`, `AUTH-07`, `ADMIN-08`, `OPS-02` | [flows/company-tenant-control-plane.md](./flows/company-tenant-control-plane.md), [flows/auth-identity.md](./flows/auth-identity.md), [flows/admin-governance.md](./flows/admin-governance.md), [ops-deployment-runtime.md](./ops-deployment-runtime.md) |
| 3 | P0 | Redesign the canonical accounting truth boundaries before enabling or expanding automation | This is the most important ERP-integrity design item in the audit. The lane must open prove-first with [`03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md`](./executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md): sales pain drives the opening scope, but Packet 0 must first lock the dispatch vs order, purchase-invoice vs goods-receipt, listener/manual-inventory, and period-close/reconciliation truth boundaries, together with the exact proof/tests, rollback owner/rule, stop rule, and downstream guarantees for Lanes 04-06. Only after that note is accepted may later packets change listeners, retries, settlement behavior, or reconciliation handling. | `O2C-01`, `P2P-01`, `MFG-01`, `FIN-01` | [flows/order-to-cash.md](./flows/order-to-cash.md), [flows/procure-to-pay.md](./flows/procure-to-pay.md), [flows/manufacturing-inventory.md](./flows/manufacturing-inventory.md), [flows/finance-reporting-audit.md](./flows/finance-reporting-audit.md) |
| 4 | P1 | Stabilize commercial workflow invariants, approvals, and settlement parity | Once the canonical posting boundary is agreed, tighten the sales and purchasing workflows that feed it: idempotency asymmetry, legacy status aliases, approval overreach, settlement-link parity, and supplier approval/runtime privacy gaps. This is the main commercial-integrity follow-up after row 3. | `O2C-02`, `O2C-03`, `O2C-04`, `O2C-05`, `O2C-06`, `O2C-07`, `O2C-08`, `P2P-02`, `P2P-03`, `P2P-04`, `P2P-05`, `P2P-06` | [flows/order-to-cash.md](./flows/order-to-cash.md), [flows/procure-to-pay.md](./flows/procure-to-pay.md) |
| 5 | P1 | Repair catalog-to-inventory linkages, manufacturing idempotency, and costing guardrails | The manufacturing review shows several ways to create inventory-visible but operationally invalid product state. After row 3, fix the SKU/product bootstrap split, production-log replay gap, opening-stock drift, optional packaging-cost bypass, and historical valuation fragility before broadening manufacturing throughput. | `MFG-02`, `MFG-03`, `MFG-04`, `MFG-05`, `MFG-06`, `MFG-07`, `MFG-08` | [flows/manufacturing-inventory.md](./flows/manufacturing-inventory.md) |
| 6 | P1 | Close governance, exfiltration, and finance-control gaps | This cluster contains the remaining high-value governance work after the global control plane is fixed: export approvals that can be bypassed, changelog/public-feed tampering, GitHub ticket exfiltration, missing export audit, payroll shortcuts, report-window correctness, and tenant-visible outbox counters. Run it after rows 1-2 so ownership and auth boundaries are stable. | `ADMIN-02`, `ADMIN-03`, `ADMIN-04`, `ADMIN-05`, `ADMIN-06`, `ADMIN-10`, `ADMIN-11`, `FIN-02`, `FIN-03`, `FIN-04`, `FIN-05`, `FIN-06`, `ORCH-02` | [flows/admin-governance.md](./flows/admin-governance.md), [flows/finance-reporting-audit.md](./flows/finance-reporting-audit.md), [flows/orchestrator-background-integration.md](./flows/orchestrator-background-integration.md) |
| 7 | P1 | Restore orchestrator recovery, health-truth, and next-mission execution enablers | The next remediation wave will be harder to validate unless recovery surfaces, health truth, and mission tooling are stabilized. This row combines missing replay/manual-recovery tooling, misleading health/integration surfaces, actuator-port drift, weak app recovery hooks, and the recorded mission-environment constraints that reduced evidence confidence. | `ORCH-01`, `ORCH-03`, `ORCH-04`, `ORCH-05`, `ORCH-06`, `ORCH-07`, `FIN-07`, `OPS-01`, `OPS-04`, `OPS-05`, `OPS-08`, `SA-06`, `ENV-01`, `ENV-02`, `ENV-03`, `ENV-04`, `ENV-05` | [flows/orchestrator-background-integration.md](./flows/orchestrator-background-integration.md), [ops-deployment-runtime.md](./ops-deployment-runtime.md), [static-analysis-triage.md](./static-analysis-triage.md), [README.md](./README.md) |
| 8 | P1 | Turn quality governance from advisory to enforceable using a baseline + new-violations-only gate | The remediation mission should not ship without better guardrails than the current advisory-only posture. Activate the baseline capture, Qodana execution, changed-files/static-analysis enforcement, and hard CI lanes for CODE-RED, invariant, and smoke coverage in parallel with the implementation wave so new fixes do not add fresh debt. | `QA-01`, `QA-02`, `QA-03`, `QA-04`, `QA-05`, `QA-06`, `QA-07`, `QA-08`, `QA-09`, `QA-10`, `SA-01`, `SA-03`, `SA-04` | [test-ci-governance.md](./test-ci-governance.md), [static-analysis-triage.md](./static-analysis-triage.md) |

## Post-review execution notes

Additional live-backend findings should be folded into the lanes below rather than treated as ad hoc one-off fixes:

- **Row 1** also carries `TEN-09`, but treat it as validation-first: re-prove current tenant-runtime and portal payload drift against code, tests, and `openapi.json` before opening backend work.
- **Row 4** also carries `O2C-09`, and the lane should explicitly preserve the reservation prerequisite before `POST /api/v1/sales/orders/{id}/confirm` succeeds.
- **Row 5** also carries `MFG-09`; stock-bearing create readiness in the `MOCK` tenant is a concrete blocker, and raw-material receiving must stay on the purchasing receipt flow rather than any noncanonical escape hatch.
- **Row 6** also carries `FIN-08`, plus `ADMIN-07` and `ADMIN-13` as validation-first contract checks rather than automatic backend endpoint work.
- **Row 7** also carries `ORCH-10` as a validation-first operator-surface check rather than an automatic new backend route build.

## Current auth PR regression-closure gate

Before starting the broader next mission, the open auth-hardening PR should close these newly reviewed regressions on the current branch:

- `TEN-10` — canonical company runtime-policy updates must keep immediate policy-cache invalidation
- `AUTH-09` — public forgot-password should keep delivery masking but not swallow token-persistence/storage failures
- `ADMIN-14` — masked tenant-admin foreign-user lookups must not acquire global write locks before scope checks

This is a **merge gate**, not a later cleanup item. The broader next mission should start only after the current auth PR is regression-clean.
See [executable-specs/00-current-auth-merge-gate.md](./executable-specs/00-current-auth-merge-gate.md) and [executable-specs/02-lane-auth-secrets-incident/EXEC-SPEC.md](./executable-specs/02-lane-auth-secrets-incident/EXEC-SPEC.md).

## Later cleanup / ratchet work

These items should follow the immediate wave rather than lead it.

| Seq | Priority | Workstream | Why later | Included finding IDs | Primary source docs |
| ---: | --- | --- | --- | --- | --- |
| 9 | P2 | Remove dead or drifted API surfaces and align published contracts with live behavior | These are real defects, but they are safer to clean up after the canonical lifecycle/auth/governance/orchestrator behavior is settled. Otherwise the team risks documenting or deleting the wrong contract while deeper fixes are still moving. | `TEN-08`, `AUTH-08`, `ADMIN-07`, `ADMIN-12`, `ORCH-08`, `ORCH-09` | [flows/company-tenant-control-plane.md](./flows/company-tenant-control-plane.md), [flows/auth-identity.md](./flows/auth-identity.md), [flows/admin-governance.md](./flows/admin-governance.md), [flows/orchestrator-background-integration.md](./flows/orchestrator-background-integration.md) |
| 10 | P2 | Modernize deployment topology and secret-distribution posture | Compose blast radius and placeholder-secret discipline matter, but they are better handled after the control-plane, auth, and health-truth fixes clarify the steady-state runtime contract. Treat this as the deployment hardening follow-up once the product-side fixes stop moving. | `OPS-03`, `OPS-07` | [ops-deployment-runtime.md](./ops-deployment-runtime.md) |
| 11 | P3 | Burn down hotspot files and style-noise backlog after the baseline exists | The hotspot/static-analysis findings are important, but they should be attacked with the new baseline and gating strategy already live. Otherwise the next mission will spend effort on style-noise without protecting the higher-risk logic paths first. | `SA-02`, `SA-05` | [static-analysis-triage.md](./static-analysis-triage.md) |

## Coverage by completed review area

This map makes the carry-forward explicit: every completed review area and mission-level constraint set is assigned to one or more backlog rows above.

| Source review / synthesis area | Backlog rows carrying its findings |
| --- | --- |
| [Company, tenant, and control-plane review](./flows/company-tenant-control-plane.md) | 1, 2, 9 |
| [Auth and identity review](./flows/auth-identity.md) | 2, 9 |
| [Admin and governance review](./flows/admin-governance.md) | 1, 2, 6, 9 |
| [Order-to-cash review](./flows/order-to-cash.md) | 3, 4 |
| [Procure-to-pay review](./flows/procure-to-pay.md) | 3, 4 |
| [Manufacturing and inventory review](./flows/manufacturing-inventory.md) | 3, 5 |
| [Finance, reporting, and audit review](./flows/finance-reporting-audit.md) | 3, 6, 7 |
| [Orchestrator, background, and integration review](./flows/orchestrator-background-integration.md) | 6, 7, 9 |
| [Ops, deployment, and runtime review](./ops-deployment-runtime.md) | 1, 2, 7, 10 |
| [Test, CI, and quality-governance review](./test-ci-governance.md) | 8 |
| [Static-analysis triage](./static-analysis-triage.md) | 7, 8, 11 |
| [Mission-level evidence constraints from synthesis](./risk-register.md) | 7 |

## Recommended next-mission framing

If the follow-up implementation mission cannot take all eight immediate rows at once, split it in this order:

1. rows **1-3** as the non-negotiable control/integrity foundation,
2. rows **7-8** as the validation and rollout safety net,
3. rows **4-6** as domain/governance hardening on top of the new contracts,
4. rows **9-11** as cleanup and ratchet work once the immediate fixes are stable.

Use [executable-specs/00-program-map.md](./executable-specs/00-program-map.md) for the concrete lane order and packet shape inside that framing.
