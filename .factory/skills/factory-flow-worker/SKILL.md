---
name: factory-flow-worker
description: Hard-cut worker for ERP-38 canonical factory execution flow cleanup, route consolidation, and cross-module proof.
---

# Factory Flow Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for ERP-38 implementation features that change factory, inventory, sales, orchestrator, tests, OpenAPI, or docs in order to hard-cut the public execution flow to:

- `POST /api/v1/factory/production/logs`
- `POST /api/v1/factory/packing-records`
- `POST /api/v1/dispatch/confirm`

This worker owns route removal, dead-code cleanup, stale test replacement/deletion, OpenAPI/endpoint-inventory cleanup, workflow-doc cleanup, and cross-module correctness proof for the ERP-38 packet.

## Required Skills

None.

## Work Procedure

### Step 1: Reconstruct the exact before/after contract
1. Read `mission.md`, mission `AGENTS.md`, `.factory/services.yaml`, `.factory/library/factory-canonical-flow.md`, `.factory/library/architecture.md`, `.factory/library/environment.md`, and `.factory/library/user-testing.md`.
2. List the surviving route/term/DTO/test/doc surfaces for the feature and the retired ones that must disappear.
3. Trace all internal callers before touching code. For ERP-38 this includes controller mappings, services, DTOs, orchestrator callers, `openapi.json`, endpoint inventories, workflow docs, frontend handoff, and any stale compatibility tests.
4. If the feature moves a default permission between system roles, also trace startup/backfill synchronizers, persisted role-permission rows, and upgraded-tenant authority surfaces. Fresh-role tests alone are not enough.
5. If the feature would require preserving both canonical and retired operator paths, stop and return to orchestrator.

### Step 2: Write failing tests first
1. Add or update tests before implementation.
2. For route removals, write negative coverage proving the retired surface is absent or fail-closed exactly as the feature requires.
3. For contract hard-cuts, rewrite stale tests that currently encode legacy behavior; do not leave them behind for a later feature if this feature is what changes the contract.
4. For cross-module behavior, add or update the narrowest high-signal regression/integration test that proves the intended side effect.
5. Run the smallest targeted failing command first from `erp-domain/` with `MIGRATION_SET=v2`.

### Step 3: Implement the hard-cut
1. Make the smallest set of changes that leaves only the canonical path for the owned operator action.
2. Delete dead routes, DTOs, service seams, helper branches, and internal callers that become obsolete.
3. Do not add compatibility bridges.
4. Do not keep `X-Idempotency-Key`, `X-Request-Id`, payload-derived fallback, or auto-generated replay keys on the surviving pack route.
5. Keep factory as execution truth only. Do not turn factory into a second inventory-admin or accounting-admin workspace.
6. Preserve the downstream accounting/inventory path already chosen by the mission; do not invent a new posting owner.
7. If a `migration_v2` change is truly required, continue from the highest v2 version only and mention the exact dependency in the handoff.

### Step 4: Clean the published contract in the same feature
When the feature changes a public route, DTO, error contract, or operator term, update the affected published surfaces in the same feature:
- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- relevant `docs/workflows/*.md`
- relevant `docs/code-review/flows/*.md`
- `.factory/library/frontend-handoff.md`
- `.factory/library/frontend-v2.md`
- `README.md` when the feature changes the top-level public flow or final run/test guidance
- tracked `.factory/validation/**` evidence when the feature promises no stale supported-behavior proof remains

If the feature does not change a public contract, say so explicitly in the handoff.

### Step 5: Verify thoroughly
1. First classify whether the feature is docs-only. Docs-only means it changes only markdown/text/shared-state surfaces (including workflow docs, review docs, `.factory/library/*`, `README.md`, or `erp-domain/docs/endpoint_inventory.tsv`) and does not change runtime code, tests, config, or generated OpenAPI truth.
2. For docs-only features:
   - Do not run `commands.compile`, targeted JVM suites, `commands.gate-fast`, or extra scrutiny-style review loops.
   - Run the highest-signal docs checks instead: `bash ci/lint-knowledgebase.sh`, any relevant contract/workflow guards, targeted repo scans/grep for retired strings or routes, and `git diff --check`.
   - Explain in the handoff why docs-focused verification was sufficient.
3. For non-docs-only features, run `commands.compile`.
4. For non-docs-only features, run the targeted ERP-38 command(s) for the feature from `.factory/services.yaml`.
5. If a non-docs-only feature changes a public route or cross-module posting behavior, run `commands.gate-fast` unless the orchestrator explicitly scoped you to a narrower pre-validation loop.
6. If a feature changes endpoint inventories, workflow docs, or OpenAPI as part of a runtime contract change, run `commands.erp38-contract-guards`.
7. Use runtime probes only when the assertion needs live API evidence. Prefer the reset harness documented in `.factory/library/user-testing.md`.
8. Run `git diff --check` before ending the feature.

### Step 6: Produce a high-signal handoff
1. Summarize the surviving and removed surfaces.
2. List every source/test/doc/OpenAPI file changed.
3. Record the exact verification commands and the observations that prove the hard-cut.
4. Call out any ERP-37 boundary you deliberately avoided.
5. If anything remains intentionally deferred, record it as `discoveredIssues` or incomplete work; do not silently leave drift behind.

## Example Handoff

```json
{
  "salientSummary": "Removed `/api/v1/factory/production-batches` and the deprecated `FactoryService.logBatch(...)` seam, kept production logs as the only batch-create route, and cleaned the public contract surfaces that still advertised the legacy path.",
  "whatWasImplemented": "Deleted the public `GET/POST /api/v1/factory/production-batches` mappings, removed `FactoryService.logBatch(...)` plus its feature-flag gate and internal caller wiring, rewrote the legacy regression coverage to assert route absence, and updated `openapi.json`, `docs/endpoint-inventory.md`, `erp-domain/docs/endpoint_inventory.tsv`, and `.factory/library/frontend-handoff.md` so only `POST /api/v1/factory/production/logs` remains as the batch-create contract.",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      {
        "command": "ROOT=$(git rev-parse --show-toplevel) && cd \"$ROOT/erp-domain\" && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='ProductionLogWipPostingRegressionIT,ProductionLogListDetailLazyLoadRegressionIT,CR_FactoryLegacyBatchProdGatingIT' test",
        "exitCode": 0,
        "observation": "Production-log create path and legacy-route absence regressions passed."
      },
      {
        "command": "ROOT=$(git rev-parse --show-toplevel) && cd \"$ROOT\" && bash scripts/guard_openapi_contract_drift.sh",
        "exitCode": 0,
        "observation": "OpenAPI and endpoint inventory stayed aligned after the route removal."
      },
      {
        "command": "git diff --check",
        "exitCode": 0,
        "observation": "No whitespace or merge-marker issues remained in the packet diff."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Probe the retired `/api/v1/factory/production-batches` route against the reset runtime and compare it with a successful `POST /api/v1/factory/production/logs` request.",
        "observed": "The canonical production-log route remains live; the retired route no longer behaves as a supported operator path."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionLogCreateContractRegressionIT.java",
        "cases": [
          {
            "name": "createProductionLog_returnsReadyToPackCanonicalContract",
            "verifies": "Canonical create response shape and READY_TO_PACK state."
          },
          {
            "name": "legacyProductionBatchesRoute_isAbsent",
            "verifies": "Retired public route no longer survives as an operator path."
          }
        ]
      }
    ]
  },
  "discoveredIssues": []
}
```

## When to Return to Orchestrator

- A file or behavior is clearly ERP-37-owned and the dependency is not already called out in the feature
- The change would require preserving both canonical and retired public surfaces
- A route hard-cut would require a new public route to compensate
- A required runtime/test/doc surface cannot be updated inside the current feature without creating cross-feature drift
- A `migration_v2` change is required but another worker already introduced a conflicting version
- The feature cannot be verified on Flyway v2 only
