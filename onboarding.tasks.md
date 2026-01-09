You are in the ERP backend repo.

Run in careful, non-rushed mode. Goal is to add onboarding capabilities for “starting fresh from Tally/manual migration” while keeping ERP accounting correctness intact.

Scope

Implement Admin-only onboarding flows that are visible only in the Accounting portal UI context, but enforced on the backend as:

Admin role required (and optionally an explicit permission like onboarding.manage)

Company-scoped (must not leak across companies)

Idempotent enough to safely retry (avoid duplicates)

This is NOT a “new features” epic in the product sense: treat it as operational onboarding scaffolding required to use the ERP, with minimal surface area, maximum traceability, and reconciliation safety.

Hydration (no code changes)

Read SCOPE.md and AGENTS.md fully.

Read erp-domain/docs/DEPLOY_CHECKLIST.md, erp-domain/docs/STABILIZATION_LOG.md (latest entries), and the reconciliation docs (Epic 08 outputs if present).

Inspect existing endpoints for:

Suppliers creation/list/update

Products/catalog creation/list/update

Raw materials creation/list/update

Stock/Inventory opening balance or adjustments

Tax config defaults (GST output etc.)

Default accounts mapping endpoints (/api/v1/accounting/default-accounts or equivalent)

Confirm where OpenAPI snapshot is generated and where openapi.json lives and how it should be updated.

Deliverables

Create a new backend feature set called Onboarding (Accounting Admin):

A) Onboarding API (minimal, strict)

Add endpoints under a clear namespace, e.g.
/api/v1/accounting/onboarding/* (or consistent existing prefix).

Must include:

Master Data Bootstrap

Create/update/list:

Brands

Product categories

Product variants (size/color) or equivalent existing model

Finished goods products

Raw materials (with unit + category)

If these resources already exist, do NOT duplicate models—use existing entities/endpoints, and only add missing operations.

Trading Partners

Verify supplier endpoints exist and are correct; if not, add minimal supplier CRUD required for onboarding.

Verify dealer endpoints exist (they do) but ensure onboarding flow can create them safely.

Opening Balances / Starting Point
Provide a safe way to set starting inventory + opening balances:

Opening stock for raw materials and finished goods

Optional: opening AR/AP per partner (if your accounting model supports it safely)

Must generate proper inventory movements with reference type like OPENING_STOCK

Must reconcile against inventory control accounts (no silent drift)

Auto-mapping defaults
When creating a new product:

Auto-assign default revenue account, inventory/control account, and output tax account based on company defaults.

If defaults aren’t configured, API should fail with a clear validation message (and surface via readiness/requiredConfig if appropriate).

B) Strict visibility rule (Accounting portal, Admin-only)

Backend enforcement:

All onboarding endpoints require admin role (and/or explicit permission).

Add/verify RBAC guards with the same style as existing secured controllers.

Ensure these endpoints are not accessible to dealer portal roles.

C) Sync & correctness rules

Every onboarding action must produce traceable records:

createdBy, createdAt, companyId

reference numbers where relevant

Make operations idempotent where safe (e.g., “upsert brand by name”, “upsert category by code”), but never silently overwrite financial amounts.

D) Verification

For each chunk of work:

compile: mvn -f erp-domain/pom.xml -DskipTests compile

checkstyle baseline tolerated: mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check

full tests: mvn -f erp-domain/pom.xml test

add or extend invariant tests to prove onboarding doesn’t violate:

no negative stock

journal balance zero tolerance

inventory movements link to reference

default account mapping required for postings

ensure OpenAPI snapshot updated in the canonical openapi.json location.

E) Documentation

Create docs that a human can follow:

erp-domain/docs/ONBOARDING_GUIDE.md

step-by-step: configure accounts → add suppliers/dealers → add products/raw materials → set opening stock → verify reconciliation

Update erp-domain/docs/STABILIZATION_LOG.md with what was done + commands run.

Execution constraints (non-negotiable)

Do not invent new domain concepts unless unavoidable; prefer existing entities and patterns.

Do not add UI code; only backend endpoints + invariants + docs.

If something required doesn’t exist (brands/sizes/colors model), implement the minimal backend representation consistent with current product model.

If blocked by semantic ambiguity, STOP and output a BLOCKER REPORT with the smallest decision needed.

Output format at end

Print an ONBOARDING COMPLETION REPORT:

endpoints added/modified

files changed

migrations added (if any)

tests run + results

reconciliation evidence queries (inventory vs GL)

known limitations (e.g., AR/AP opening balances not implemented yet)

Extra note (important)

Before adding anything, explicitly confirm supplier endpoints:

If supplier CRUD already exists, reuse it.

If partially exists, patch it (do not create parallel supplier models).