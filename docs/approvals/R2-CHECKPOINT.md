# R2 Checkpoint

## Scope
- Feature: `PR-128 catalog surface consolidation`
- PR: `#128`
- Review candidate SHA: `aad306dd502ce2e37e03dcf455523252d15916f0`
- PR branch: `pr/main-catalog-surface-consolidation-11e3cef1`
- Rebuild branch: `pr/main-catalog-surface-consolidation-11e3cef1`
- Why this is R2: this packet changes the canonical catalog write and import surface, retires the public accounting catalog controller, changes stock-bearing product creation authorization, and ships `migration_v2/V163__catalog_variant_group_linkage.sql` to support canonical variant-group data on privileged inventory/accounting-linked products.

## Risk Trigger
- Triggered by runtime-bearing changes under `modules/accounting` plus `migration_v2` schema changes that feed canonical catalog product creation and CSV import.
- Contract surfaces affected: `POST /api/v1/catalog/products`, `POST /api/v1/catalog/import`, `AccountingCatalogController`, `CatalogController`, and the retired public accounting host `/api/v1/accounting/catalog/**`.
- Failure mode if wrong: non-accounting roles regain write access to stock-bearing catalog creation, catalog bootstrap CSV imports return 404 on the canonical host, variant-group/product-family data drifts from the new schema, or downstream inventory/accounting product setup runs against an incomplete public contract.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: the bounded PR-128 proof is green on review candidate `aad306dd502ce2e37e03dcf455523252d15916f0`, all current review threads are resolved on GitHub, and the remaining gate is the live PR check set plus human merge approval.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes privileged catalog/accounting-linked runtime behavior and schema, so merge remains gated on explicit human approval after CI settles on PR `#128`.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the PR-128 packet commit(s), redeploy the previous backend build, drop the V163 variant-group schema additions, refresh `openapi.json`, and rerun the focused catalog proof before republishing the pre-PR-128 head.
- Rollback trigger:
  - any caller still depends on the retired public accounting catalog host instead of `/api/v1/catalog/**`
  - `ROLE_SALES` or `ROLE_FACTORY` can create canonical products
  - canonical CSV import on `/api/v1/catalog/import` regresses or returns 404
  - variant-group or product-family data written by the new flow cannot be cleanly served or rolled back

## Expiry
- Valid until: `2026-03-28`
- Re-evaluate if: any additional runtime-bearing catalog/accounting/schema change lands above review candidate `aad306dd502ce2e37e03dcf455523252d15916f0`, CI is rerun against a different candidate SHA, or the packet grows beyond catalog surface consolidation and import/authorization repair.

## Residual Follow-up
- Explicit follow-up ticket: `PR-128 follow-up`
- Follow-up scope: any post-merge cleanup that is not required for the canonical `/api/v1/catalog/**` contract, including stale packet artifacts and any future catalog product workflow simplification outside the bounded consolidation scope.
- Why excluded here: this packet is intentionally bounded to the canonical catalog host, import/product write parity, authorization correction, schema support, and matching doc/OpenAPI/test cleanup.

## Verification Evidence
- Merged-branch targeted proof on review candidate `aad306dd502ce2e37e03dcf455523252d15916f0`:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest=OpenApiSnapshotIT,CatalogServiceProductCrudTest,CatalogControllerCanonicalProductIT,AccountingCatalogControllerSecurityIT test`
  - result: `BUILD SUCCESS`
  - tests: `15 run, 0 failures, 0 errors, 0 skipped`
- OpenAPI refresh proof on the merged branch:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - result: `BUILD SUCCESS`
  - tests: `4 run, 0 failures, 0 errors, 0 skipped`
- Hygiene proof:
  - `git diff --check`
  - result: clean
- Review-surface proof:
  - GitHub review threads for the canonical product authorization fix, canonical import restoration, metadata null safety, services command restoration, doc wording repair, and valuation test drift are resolved on PR `#128`.

## Reviewer Notes
- Review should block any attempt to re-open public catalog import/product writes on the retired `/api/v1/accounting/catalog/**` host or to widen canonical product creation beyond `ROLE_ADMIN|ROLE_ACCOUNTING`.
- The canonical import route on `/api/v1/catalog/import` is intentional and must stay on the consolidated catalog host with multipart/form-data plus idempotency handling.
- `migration_v2/V163__catalog_variant_group_linkage.sql` is part of the packet contract; do not merge without the matching migration and rollback runbook updates.
