---
name: portal-hardcut-worker
description: ERP-21 worker for portal/dealer-portal host hard-cuts, duplicate-route retirement, and contract-proof cleanup.
---

# Portal Hard-Cut Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for ERP-21 features that:
- move finance/support surfaces onto the canonical portal or dealer-portal hosts
- retire duplicate dealer-finance or shared-support routes
- harden role and tenant boundaries around the portal split
- rewrite stale tests, OpenAPI, endpoint inventories, and frontend-handoff docs that preserve the old mixed host model

## Required Skills

None.

## Work Procedure

### Step 1: Lock the approved host model before changing code
1. Read `mission.md`, mission `AGENTS.md`, `validation-contract.md`, and the feature carefully.
2. Read `.factory/services.yaml`, `.factory/library/architecture.md`, `.factory/library/environment.md`, `.factory/library/user-testing.md`, and `.factory/library/portal-split-hard-cut.md`.
3. Write down the canonical end state for your feature before editing:
   - dealer-facing finance only under `/api/v1/dealer-portal/**`
   - internal finance only under `/api/v1/portal/finance/**`
   - admin support only under `/api/v1/portal/support/tickets/**`
   - dealer support only under `/api/v1/dealer-portal/support/tickets/**`
   - no shared `/api/v1/support/**`
4. Enumerate every touched controller, service, DTO, test, OpenAPI/doc artifact, and every retired path your feature must remove or prove absent.
5. If finishing the feature would require widening into ERP-22/ERP-23 or an unrelated refactor, stop and return to orchestrator.

### Step 2: Write characterization and retirement tests first
1. Add or rewrite tests before implementation.
2. For canonical-host features, add positive tests for the new host and negative tests for the retired host.
3. Probe retired routes with the strongest actor that used to be authorized on them (`admin`, `accounting`, or `sales` as applicable). Do not accept a dealer-only `403` as retirement proof.
4. If the touched area already has stale tests that preserve the old host model, replace or delete them in the same feature instead of leaving them behind.
5. Run the targeted suite and confirm it fails before implementation.

### Step 3: Implement the hard cut
1. Build the canonical route/controller/service path needed for the feature.
2. Remove stale controller methods, DTOs, helper branches, and route mappings that would leave duplicate portal truth alive.
3. Keep company binding fail-closed.
4. Prefer reusing existing read-model services instead of cloning logic into another host.
5. If you touch `modules/accounting` or `db/migration_v2`, update `docs/approvals/R2-CHECKPOINT.md` in the same feature.

### Step 4: Clean every touched contract surface in the same feature
1. Update `openapi.json` when routes or verbs change.
2. Update all touched runtime-facing docs and handoff artifacts in the same feature:
   - `docs/endpoint-inventory.md`
   - `erp-domain/docs/endpoint_inventory.tsv`
   - `.factory/library/frontend-handoff.md`
   - `docs/frontend-api/README.md`
   - `docs/frontend-portals/accounting/README.md`
   - touched workflow/code-review docs
3. If a touched doc would still describe a retired host or route after your code change, fix it now; do not defer it.
4. Append a concise dated note to `.factory/library/remediation-log.md` when your feature removes duplicate-truth or dead code.

### Step 5: Verify aggressively
1. Run the feature-specific targeted tests from `features.json`.
2. Run `MIGRATION_SET=v2 mvn compile -q` from `erp-domain/`.
3. Run `MIGRATION_SET=v2 mvn test -Pgate-fast -Djacoco.skip=true` unless the orchestrator has explicitly scoped the feature to a smaller temporary check during iteration.
4. If `gate-fast` is already red in the known unrelated O2C dispatch provenance lane before or during ERP-21 development:
   - capture proof of the unrelated failure,
   - do not absorb that fix into ERP-21,
   - continue ERP-21 implementation and scoped verification anyway.
5. Before final handoff for merge/PR readiness, rerun the full required gates and ensure the final PR state is green.
6. Run the relevant contract guards from `.factory/services.yaml`.
7. If your feature changes runtime routes, reset the runtime and capture exact `curl` probes showing:
   - canonical host success
   - retired host absence
   - denied role or tenant mismatch behavior
8. Re-read your diff looking for stale route names, unused helpers, or doc drift you missed.

### Step 6: Produce a strict handoff
Your handoff must include:
- exact canonical routes added/kept
- exact retired routes removed or proven absent
- exact docs/OpenAPI artifacts updated
- exact `curl` probes and test commands run
- whether API shapes changed (`yes` or `no`)
- any escalated out-of-scope cleanup you intentionally did not absorb

## Example Handoff

```json
{
  "salientSummary": "Hard-cut internal finance onto `/api/v1/portal/finance/**` and retired the dealer/invoice/report duplicates that previously acted like alternate portal finance views. Rewrote the stale security/OpenAPI/docs coverage so the published contract now shows one internal host and one dealer host only.",
  "whatWasImplemented": "Added `PortalFinanceController` with `/api/v1/portal/finance/ledger`, `/api/v1/portal/finance/invoices`, and `/api/v1/portal/finance/aging`, wired it to the existing dealer finance read models, and removed the duplicate dealer-finance routes from `DealerController`, `InvoiceController`, `AccountingController`, and `ReportController`. Replaced stale route-contract/security tests with canonical-host coverage, refreshed `openapi.json`, updated the endpoint inventories plus frontend handoff docs, and logged the duplicate-route retirement in `.factory/library/remediation-log.md`.",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      {
        "command": "cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerPortalControllerSecurityIT,DealerControllerSecurityIT,InvoiceControllerSecurityContractTest,ReportControllerRouteContractIT,OpenApiSnapshotIT' test",
        "exitCode": 0,
        "observation": "Canonical-host and retired-route coverage passed after replacing the stale duplicate-route expectations."
      },
      {
        "command": "cd erp-domain && MIGRATION_SET=v2 mvn compile -q",
        "exitCode": 0,
        "observation": "Compilation clean."
      },
      {
        "command": "cd erp-domain && MIGRATION_SET=v2 mvn test -Pgate-fast -Djacoco.skip=true",
        "exitCode": 0,
        "observation": "Gate-fast passed."
      },
      {
        "command": "bash scripts/guard_openapi_contract_drift.sh && bash scripts/guard_accounting_portal_scope_contract.sh",
        "exitCode": 0,
        "observation": "OpenAPI and accounting-portal scope guards passed."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Reset runtime and curled `/api/v1/portal/finance/ledger` as seeded admin plus the retired `/api/v1/dealers/{dealerId}/ledger` route as seeded admin.",
        "observed": "The canonical route returned 200 with tenant-bound finance data; the retired legacy route returned 404."
      },
      {
        "action": "Curled `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` for own invoice and then with a cross-dealer invoice id.",
        "observed": "Own invoice PDF succeeded; cross-dealer probe returned 404."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/PortalFinanceControllerIT.java",
        "cases": [
          {
            "name": "admin_can_read_portal_finance_hosts",
            "verifies": "The canonical `/api/v1/portal/finance/**` subtree exists and is admin/accounting scoped."
          },
          {
            "name": "retired_duplicate_finance_routes_return_not_found",
            "verifies": "Legacy finance clones are absent even for formerly authorized actors."
          }
        ]
      }
    ]
  },
  "discoveredIssues": [
    {
      "severity": "medium",
      "description": "A report-export doc outside the touched ERP-21 host split still references a retired dealer-aging path in a separate review packet.",
      "suggestedFix": "Escalate to orchestrator only if the doc is inside the touched ERP-21 contract surface; otherwise keep ERP-21 scoped."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature cannot finish without widening into ERP-22/ERP-23 or an unrelated module refactor.
- A required canonical-host decision is missing from the mission artifacts.
- R2-governed accounting or migration work becomes broader than the feature description allows.
- The final rerun of required gates for PR readiness is still red, even after classifying any unrelated baseline blocker separately from ERP-21.
