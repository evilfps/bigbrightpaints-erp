---
name: catalog-surface-worker
description: Worker for the narrow catalog/product/SKU consolidation packet in the BigBright ERP.
---

# Catalog Surface Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for features in the Catalog Surface Consolidation packet that touch:
- catalog/product/SKU controllers, DTOs, services, entities, and Flyway v2 migrations
- downstream-ready product creation for finished goods and raw materials
- canonical browse/search behavior needed by sales and factory selection
- stale route retirement under `/api/v1/accounting/catalog/**`, `/api/v1/production/**`, and duplicate `/api/v1/catalog/**` write paths
- packet-scoped tests, OpenAPI, route inventories/helpers, handoff docs, and README alignment tied to this flow

## Required Skills

None.

## Work Procedure

### Step 1: Lock the packet contract before editing
1. Read `mission.md`, mission `AGENTS.md`, and `validation-contract.md` in full.
2. Read `docs/developer/catalog-consolidation/01-current-state-flow.md`, `02-target-accounting-product-entry-flow.md`, `03-definition-of-done-and-parallel-scope.md`, and `04-update-hygiene.md`.
3. Read `.factory/services.yaml`, `.factory/library/architecture.md`, `.factory/library/environment.md`, `.factory/library/user-testing.md`, and `.factory/library/catalog-surface-consolidation.md` if present.
4. Trace every touched public route, DTO, service, entity, migration, test, OpenAPI surface, and route inventory/helper before changing code.
5. Treat these rules as non-negotiable:
   - keep only `/api/v1/catalog/**` as the public catalog host
   - `POST /api/v1/catalog/brands` creates brands; `POST /api/v1/catalog/products` consumes a pre-resolved active `brandId`
   - no inline brand-create fallback in the product contract
   - no compatibility bridges, no wrapper delegations, no dual-route behavior
   - no separate public bulk product-create route
   - preserve reserved SKU semantics such as `-BULK`
   - stay inside catalog/product/SKU flow plus downstream inventory/sales/factory readiness and aligned docs/tests/OpenAPI
6. Proof-only work is valid for this skill when the assigned feature is about tightening regression evidence or contract-surface alignment for behavior that already exists; you still must follow the same verification bar.

### Step 2: Write or replace failing tests first
1. Encode the assigned validation assertions in tests before implementation.
2. Prefer targeted contract/regression coverage close to the touched surface:
   - controller or integration tests for surviving and retired routes
   - service/integration tests for create, preview, conflict, readiness, and variant-group persistence
   - OpenAPI snapshot or route-inventory checks when the public surface changes
3. If stale tests assert the old split surface, delete or rewrite them in the same red step; do not preserve obsolete expectations.
4. Run the smallest targeted command needed to prove the tests fail for the intended reason.

### Step 3: Implement the narrow root-cause fix
1. Make only the changes required for the assigned feature and its claimed assertions.
2. Consolidate root causes, not wrappers:
   - remove stale controller mappings instead of forwarding them
   - remove stale DTO fields instead of ignoring them
   - remove duplicate write paths instead of keeping aliases
3. If the feature needs schema changes, add the next Flyway v2 migration only under `erp-domain/src/main/resources/db/migration_v2`.
4. Keep the canonical contract strict:
   - arrays only for `sizes` / `colors`
   - explicit persisted product-family / variant-group linkage
   - all-or-nothing conflict handling
   - preview on the canonical host only
5. Preserve or improve downstream readiness in the same write path for finished goods, raw materials, sales order resolution, and factory selection.
6. Delete dead code, stale helpers, retired route inventories, and obsolete OpenAPI/docs references made irrelevant by the feature.

### Step 4: Update contract surfaces in the same feature
1. When a feature changes a public catalog contract, update in the same session:
   - repo-root `openapi.json`
   - any touched route inventories/helpers (for example `erp-domain/docs/endpoint_inventory.tsv`)
   - `.factory/library/frontend-handoff.md` when it exposes affected catalog routes/contracts
   - README / packet docs assigned by the feature
2. Follow `04-update-hygiene.md` exactly. Do not leave split truth behind.
3. If the mission feature list assigns a later dedicated cleanup/proof feature, keep the current feature's contract-surface updates limited to what is necessary for its changed runtime truth, and leave broader stale-surface cleanup to the dedicated cleanup feature.
4. If a referenced doc is missing, either create the required replacement or remove the stale reference in the responsible feature; do not hand-wave the gap.

### Step 5: Verify thoroughly
1. Run the targeted packet suite or narrower targeted command for the changed surface.
2. Run repo-root OpenAPI verification when public routes or schemas changed.
3. Run `bash scripts/gate_fast.sh` only when the feature explicitly requires broad-signal proof. If mission `AGENTS.md` documents unrelated pre-existing broad-gate failures, do not widen scope to repair them during an in-scope catalog feature; record them exactly if they are the only remaining broad-gate reds.
4. Run `git diff --check` before handoff.
5. Perform API-level verification for the claimed assertions, including negative checks for retired routes and strict contract failures.
6. For retired routes, prove unmapped/not-supported behavior with an authenticated caller; do not treat `401/403` as sufficient evidence of removal.
7. If this feature is a rerun/fix specifically assigned to remove an in-scope stale test, stale diff, or contract mismatch that currently breaks the targeted baseline, it is acceptable for the first validation attempt to fail on that exact known issue; fix it first, then rerun the full targeted validation set before handoff.

### Step 6: Produce a high-signal handoff
Your handoff must make shortcuts obvious. Include:
1. exact routes/contracts changed or removed
2. exact tests added/rewritten/deleted
3. exact docs/OpenAPI/helper files updated
4. runtime/API checks performed, including negative retired-route probes
5. any in-scope drift you found but could not finish

## Example Handoff

```json
{
  "salientSummary": "Collapsed product creation onto the canonical `/api/v1/catalog/products` flow, removed the public `/api/v1/catalog/products/bulk` and `/api/v1/accounting/catalog/**` write surfaces, and added explicit variant-group persistence with downstream finished-good/raw-material readiness. Updated the OpenAPI snapshot, route inventory, and packet docs so the surviving public contract is `/api/v1/catalog/**` only.",
  "whatWasImplemented": "Added a Flyway v2 migration and persistence changes for explicit variant-group linkage, rewired `CatalogController` product create/preview/search to the downstream-ready write path, removed legacy inline brand fallback fields from the canonical product DTO, and retired the stale accounting/production catalog public mappings. Replaced stale route-anchored tests with canonical-host tests, updated repo-root `openapi.json`, refreshed `erp-domain/docs/endpoint_inventory.tsv`, and rewrote the catalog-consolidation docs and `.factory/library/frontend-handoff.md` to match the surviving contract.",
  "whatWasLeftUndone": "`docs/code-review/flows/manufacturing-inventory.md` still references the retired accounting bulk-variants route and should be cleaned in the next contract-alignment feature if it is not already covered there.",
  "verification": {
    "commandsRun": [
      {
        "command": "cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CatalogServiceProductCrudTest,ProductionCatalogServiceBulkVariantRaceTest,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,OpenApiSnapshotIT' test",
        "exitCode": 0,
        "observation": "Targeted catalog/readiness/OpenAPI packet suite passed after replacing stale route expectations."
      },
      {
        "command": "cd /home/realnigga/Desktop/Mission-control && bash scripts/gate_fast.sh",
        "exitCode": 0,
        "observation": "Repo fast gate passed with the catalog packet changes."
      },
      {
        "command": "cd /home/realnigga/Desktop/Mission-control && git diff --check",
        "exitCode": 0,
        "observation": "No whitespace or conflict-marker issues remain in the packet diff."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Posted a new brand to `/api/v1/catalog/brands`, used the returned `brandId` in `POST /api/v1/catalog/products`, and confirmed the created SKU family was discoverable on `/api/v1/catalog/products?brandId=...`.",
        "observed": "Canonical brand-then-product flow succeeded without touching retired hosts."
      },
      {
        "action": "Sent authenticated negative requests to `/api/v1/accounting/catalog/products`, `/api/v1/production/brands`, and `/api/v1/catalog/products/bulk`.",
        "observed": "All retired routes returned unmapped/not-supported outcomes instead of legacy handler responses."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "src/test/java/com/bigbrightpaints/erp/modules/production/controller/CatalogControllerCanonicalProductIT.java",
        "cases": [
          {
            "name": "createProduct_requiresResolvedActiveBrandId_andRejectsInlineBrandFallback",
            "verifies": "Canonical product create uses only a resolved active brandId and rejects legacy inline brand fields."
          },
          {
            "name": "previewAndCommit_shareCandidateSet_andPersistVariantGroupLinkage",
            "verifies": "Preview is non-mutating, commit matches preview planning, and multi-member create persists explicit group linkage."
          }
        ]
      }
    ]
  },
  "discoveredIssues": [
    {
      "severity": "medium",
      "description": "A stale review document under `docs/code-review/**` still names the retired accounting catalog route as current truth.",
      "suggestedFix": "Clean the remaining route reference in the contract-alignment feature so docs and route inventories are fully consistent."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature cannot be completed without expanding into reports, settlement/ledger, payroll/HR, period-close, runtime-control-plane, or broad pricing/inventory-valuation redesign.
- The surviving canonical contract is ambiguous in a way not resolved by `mission.md`, `AGENTS.md`, or `validation-contract.md`.
- The change would require preserving a compatibility bridge, alias, or second public route to keep existing behavior working.
- A required migration collides with another worker’s Flyway v2 version.
- Verification reveals unrelated pre-existing failures that prevent proving the assigned assertions and cannot be isolated within the packet.
