# R2 Checkpoint

## Scope
- Feature: `ERP-34 hard-cut stock setup readiness`
- PR: `#133`
- PR branch: `anas/erp-36-hard-cut-stock-setup`
- Review candidate: current ERP-34 umbrella packet on PR `#133`
- Why this is R2: this packet hard-cuts stock setup onto canonical item classes, removes stale raw-material write paths, and tightens opening-stock replay protection with migration-backed persistence plus DB-level uniqueness.

## Risk Trigger
- Triggered by inventory/accounting runtime behavior changes plus `migration_v2/V46__opening_stock_import_results_json.sql` updates.
- Contract surfaces affected: `POST|GET /api/v1/catalog/products`, `POST|GET /api/v1/inventory/opening-stock`, and catalog readiness/read-model behavior for stock-bearing SKUs.
- Failure mode if wrong: catalog create/update can drift item classes or mirror seeding, packaging raw materials can collapse back into heuristic classification, or opening-stock replay protection can admit duplicate payloads under concurrency.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Canary owner: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: focused compile proof, replay-protection regression proof, canonical catalog regression proof, and `git diff --check` are green locally for PR `#133`.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes migration-backed replay protection and canonical inventory/accounting behavior, so merge remains gated on explicit human approval after CI settles on PR `#133`.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the ERP-34 follow-up commit(s), redeploy the previous backend build, and rerun the focused catalog/opening-stock proof before reopening operator traffic.
- Rollback trigger:
  - canonical catalog writes stop seeding the correct stock mirror or account linkage
  - packaging raw materials lose explicit `PACKAGING_RAW_MATERIAL` classification
  - opening-stock imports allow duplicate payloads under fresh idempotency keys or concurrent writes

## Expiry
- Valid until: `2026-03-29`
- Re-evaluate if: scope grows beyond ERP-34 stock setup/opening-stock hard-cut behavior or CI reruns against a different candidate than PR `#133`.

## Verification Evidence
- Compile gate:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-34-hard-cut-readiness/erp-domain" && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -DskipTests test-compile`
  - result: `BUILD SUCCESS`
- Focused regression suite:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-34-hard-cut-readiness/erp-domain" && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='RawMaterialControllerTest,OpeningStockImportServiceTest,ProductionCatalogServiceCanonicalEntryTest,CR_CatalogImportDeterminismIT,ProductionCatalogDiscountDefaultRegressionIT,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT' test`
  - result: `BUILD SUCCESS`
  - tests: `79 run, 0 failures, 0 errors, 0 skipped`
- Contract/hygiene guard:
  - `git diff --check`
  - result: clean
