---
name: backend-worker
description: General-purpose Java/Spring backend worker for the BigBright ERP. Handles bug fixes, refactoring, new features, and service decomposition.
---

# Backend Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for any backend Java/Spring feature in the BigBright ERP, including:
- Bug fixes in existing services/controllers
- Service decomposition (breaking god services into focused services)
- New feature implementation (endpoints, services, entities, migrations)
- Shared infrastructure extraction (utilities, frameworks)
- Performance optimization (query fixes, batch operations)
- Backend-adjacent governance, CI, manifest, or enforcement-script cleanup when the feature changes deployability or contract guards without touching application code

## Required Skills

None.

## Work Procedure

### Step 1: Understand the Feature
0. If you are resuming after context compaction or continuing a previously-started feature lineage, re-invoke the required skills before any tool use, cite the prior lineage/baseline you are inheriting, and only skip repeated startup work when you explicitly verify that earlier baseline still applies.
1. Read the feature description, preconditions, expectedBehavior, and verificationSteps carefully.
2. Read `AGENTS.md` for mission boundaries and coding conventions.
3. Read `.factory/services.yaml` for available commands.
4. Read `.factory/library/architecture.md` for codebase patterns.
5. If verification uses live runtime/API proof, also read `.factory/library/user-testing.md` before planning setup so you inherit the current validation-runtime constraints and workarounds.
6. Identify all files that need to change. For refactoring features, trace all callers/importers of the classes being modified.

### Step 2: Freeze Behavior First, Then Add the Right Tests
1. Choose the safety pattern that fits the feature instead of forcing one workflow on every packet:
   - **Messy legacy refactor / decomposition / behavior-preserving cleanup**: start with characterization coverage. Identify the narrowest existing regression proof for the touched surface, run it before structural edits, and add/extend characterization tests anywhere behavior is unclear or insufficiently locked down.
   - **New behavior / API redesign / clear bug fix / newly extracted seam**: write failing tests first (red), then implement to make them pass (green).
   - **Script / manifest / governance enforcement**: update or add the narrowest failing guard/probe first using the authoritative script or contract surface for that feature.
2. If the feature changes behavior in an already-covered area, update or replace the stale policy/contract/regression tests in that area as part of the same packet; do not leave drift behind.
3. Run the focused proof that establishes the baseline or expected failure before major edits:
   - Java characterization/TDD: `cd erp-domain && mvn test -Djacoco.skip=true -pl . -Dtest=YourTestClass`
   - Existing hotspot proof: the smallest targeted suite already covering the touched behavior
   - Script/governance features: the guard/probe command named in the feature verification steps or `.factory/services.yaml`
4. Record in the handoff which mode you used (characterization-first, TDD red/green, or guard-first) and why it matched the feature.

### Step 3: Implement
1. Make the minimal changes needed to satisfy the feature requirements. Prefer incremental, understandable slices over big-bang rewrites.
2. Follow coding conventions from AGENTS.md strictly:
   - ApplicationException for all business errors (never IllegalArgumentException/IllegalStateException)
   - Service classes under 500 lines
   - Single responsibility per service
   - Company-scoped queries
   - Event-driven cross-module communication
   - If you retire a legacy header/parameter/alias, enforce fail-closed runtime behavior; never just stop binding it in one controller while downstream code still accepts a null canonical value
   - No fallbacks or duplicate helper paths unless the feature explicitly says otherwise
3. While touching O2C/P2P hotspots, remove dead code, unused branches, stale helpers, retired duplicate-truth paths, and obsolete tests that are made obsolete by your feature. Do not leave known dead logic or dead regression files behind in the touched area.
4. If the feature touches accounting, preserve dependent-module behavior across sales, inventory, purchasing, invoice, and reporting. Do not relocate duplicate accounting logic into new classes.
5. For service decomposition:
   - Create new focused service classes
   - Move methods from the god service to appropriate new services
   - Make the extracted service the canonical owner of the migrated behavior; deleting wrapper shells is not enough if real logic still lives behind `super` calls or dependency hops back into the god class
   - If a surviving public service only forwards into the new owner, delete that delegating wrapper method and move tests onto the collaborator/public behavior; do not keep reflection-based private-method invocation coverage (`ReflectionTestUtils.invokeMethod(...)`, `ReflectionFieldAccess.invokeMethod(...)`, or equivalent helpers) for retired wrappers
   - Delete the retired service/helper/test files once the new owner is live; a renamed monolith or compatibility shim still in the production write path does not count as a completed hard cut
   - Update the god service to delegate to the new services only as a thin facade/shim if needed; do not leave business logic ownership in the god service
   - Update all controllers and other services that call the moved methods
   - Update all test imports and references
6. For new Flyway migrations: continue from the highest existing version number in `migration_v2` only.

### Step 4: Verify
1. Run the feature's required proof commands from `verificationSteps` and `.factory/services.yaml`.
2. If an initial validator fails solely because of the exact in-scope contract drift or parity artifact your feature explicitly owns (for example stale `openapi.json` / `docs/endpoint-inventory.md` metadata for the contract you are updating), remediate that owned drift and then rerun the full validator stack instead of treating the baseline failure as an automatic stop condition.
3. If Java code changed, run compilation check: `cd erp-domain && mvn compile -q`
4. If Java code changed or the feature explicitly requires it, run the baseline repo PR lane: `cd /home/realnigga/Desktop/Mission-control && bash scripts/gate_fast.sh`
5. When you also need a narrower targeted Maven proof, name it explicitly as the Maven `-Pgate-fast` profile (for example `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`) rather than calling it the full gate-fast lane.
6. For broader validation when useful (optional, takes ~2.5min): `cd erp-domain && mvn test -Djacoco.skip=true '-Dtest=!*IT,!*ITCase,!*codered*' -pl .`
7. Manually verify key behaviors using the approach appropriate for the feature:
   - For new endpoints: document curl commands that demonstrate the endpoint works
   - For refactoring: confirm all callers are updated, tests pass, and extracted services own the migrated behavior instead of delegating back through `super` or monolithic helpers
   - For bug fixes: confirm the specific bug is fixed via the test you wrote
   - For script/governance features: document the exact guard/policy probes that now enforce the intended lane
   - If the feature changes `scripts/ci_risk_router.py`, parity routing, or diff-compaction logic, prove shard routing still reflects the real requested PR diff and that any compaction stays scoped to the intended changed-files coverage behavior
   - If runtime proof depends on seeded/authenticated actors, verify those actors actually exist before claiming the runtime is blocked; if the reset/runtime script left `app_users` empty, provision the required actors first and say so in the handoff
   - If the feature claims audit/event emission, include at least one executable proof of persisted/readable events (database row, audit API/read surface, or equivalent live evidence). Static source-inspection tests are supportive but do not satisfy the audit proof on their own
   - If the feature claims audit metadata or correlation behavior, prove the metadata survives persistence on the live path and that one flow-stable correlationId spans the related business events; non-null ids alone are insufficient
8. Check for any regressions in related modules.
9. If the feature touches accounting or another shared seam, run the dependent proof packs from `.factory/services.yaml` that cover downstream consumers.
10. If the packet changes a public endpoint contract (controller annotations, request/response DTO fields, response status codes, timeline/list fields, validation envelopes, or canonical examples), run `openapi-refresh` plus the matching contract-proof pack from `.factory/services.yaml` and update the matching canonical docs. This applies to inventory, purchasing, supplier, catalog, and other non-accounting public surfaces too—not just accounting endpoints. Hard stop: the feature is incomplete until the refreshed artifacts are actually in the working tree and the handoff cites the exact `openapi-refresh`/drift-guard proof plus the canonical doc files updated. For this repo, cite the concrete `OpenApiSnapshotIT` refresh command (or the manifest wrapper that runs it); the drift guard alone is not sufficient evidence. After refreshing, directly inspect the touched path objects in `openapi.json` and confirm the non-200 responses and created-status metadata match runtime truth. Do not claim full procedure compliance or `followedProcedure=true` unless you cite that proof or explicitly explain, with file-level evidence, why no public contract artifact changed.
11. If your change exposed stale adjacent tests in the touched control surface, either fix them in the same feature or return a clearly tracked discovered issue tied to a pending feature.
12. If the working tree is dirty only because of pre-existing `.factory/validation/**` or other orchestrator-owned mission artifacts that AGENTS says must remain uncommitted, do not treat that dirtiness as a feature failure. Leave it alone, or if you must temporarily stash it to get a clean feature commit, say so explicitly in the handoff and identify the stash.
13. If optional dependent-proof packs expose clearly pre-existing failures outside your feature while the required validators and `gate_fast` pass, record them as discovered issues with the most relevant pending feature/owner instead of failing an otherwise-correct packet.

### Step 5: Update Canonical Docs When the Changed Truth Requires It
Public contract changes are not complete until the canonical contract artifacts are refreshed. That means `openapi.json`, `docs/endpoint-inventory.md` when the guard expects it, and the matching canonical docs (`docs/frontend-api/*`, `docs/frontend-portals/*`, module/flow/ADR surfaces as applicable) whenever request/response DTOs, validation/error envelopes, example payloads, or route semantics exposed by live endpoints change. The handoff must name those refreshed files and the proof commands, or explicitly explain why no canonical contract artifact changed.

If the packet changes implemented truth, update the matching canonical docs in the same packet:

- API / DTO / auth / error changes → `docs/frontend-api/*`
- Portal route / role / UX-state / workflow changes → `docs/frontend-portals/<portal>/*`
- Module ownership / services / invariants / business rules → `docs/modules/<module>.md`
- Lifecycle / business flow / process changes → `docs/flows/*.md` and/or `docs/workflows/*.md`
- Architecture / boundaries / canonical ownership changes → `docs/ARCHITECTURE.md`, root `ARCHITECTURE.md`, and relevant `docs/adrs/*.md`
- Known limitation / accepted decision / bug-status changes → `docs/RECOMMENDATIONS.md` and any module/flow doc that defers to it
- Deprecation / replacement changes → `docs/deprecated/INDEX.md` plus replacement links

Use code, tests, controller annotations, DTOs, and `openapi.json` as source of truth. Update canonical docs, not stale duplicates, and never present planned behavior as implemented.
The canonical portal contract lane is `docs/frontend-portals/<portal>/`. If an affected portal lane has multiple canonical files carrying the same contract (for example `api-contracts.md` and `frontend-engineer-handoff.md`), refresh all of them or explicitly justify why one surface is unaffected. Portal-local duplicates outside that lane are reference-only unless the packet explicitly proves they are authoritative.
If a higher-priority instruction appears to forbid the required canonical doc refresh for a public-contract packet, stop and return to the orchestrator for clarification rather than silently skipping the docs or still claiming `followedProcedure=true`.

If `docs/frontend-api/*` changes, verify the examples against the live DTO/controller contract and exception envelope. Do not leave impossible enum values or invented top-level `ApiResponse` fields in canonical examples.
For this mission, canonical docs are the default frontend contract artifact. Do not update `.factory/library/frontend-handoff.md` or `.factory/library/frontend-v2.md` unless the feature, mission artifacts, or the user explicitly requires those internal handoff files.

If the packet is docs-only, use the docs-only lane: run `bash ci/lint-knowledgebase.sh` and skip runtime/scrutiny validators unless the packet also changes code, config, schema, scripts, OpenAPI, or test behavior. If you intentionally run broader validation anyway, explain why in the handoff and do not claim docs-only procedure compliance by default.

If docs, approvals, or R2 checkpoint files cite PR parity evidence, reference only artifact paths that actually exist under `artifacts/pr-ci-parity/**` (or other verified outputs on disk). Verify every cited evidence path exists before committing docs; do not invent placeholder files like `pr-merge-gate.json`.

If your feature adds or changes frontend-facing API endpoints or contracts and the mission still expects an internal handoff artifact, update `.factory/library/frontend-handoff.md` with:
1. **Endpoint map**: Every new/changed endpoint (method, path, auth, request/response types)
2. **User flows**: Step-by-step API call sequences for each user-facing flow
3. **State machines**: Entity lifecycle states and valid transitions with triggering API calls
4. **Error codes**: All ErrorCodes the module returns with descriptions and suggested frontend behavior
5. **Data contracts**: Request/response record definitions with field types, validation, required/optional
6. **UI hints**: Dropdown sources (endpoint for options), computed fields, field dependencies

This supplements canonical docs when the mission explicitly wants the internal handoff artifact. A frontend developer should be able to build the UI from this documentation alone.

Also update `.factory/library/frontend-v2.md` when the feature changes role surfaces, blocker semantics, generated artifacts, or backend-facing frontend assumptions.

If a higher-priority instruction says to avoid doc updates for the packet and the feature preserves the existing frontend-facing contract, do not force a docs edit; instead, state explicitly in the handoff that no frontend-handoff/frontend-v2 update was required.
If `mission.md`, `AGENTS.md`, or the feature description explicitly requires remediation-log, frontend-v2, frontend-handoff, or README updates, that counts as an explicit user request for those scoped docs and you should make them.

### Step 6: Update Shared Knowledge
1. If you discovered important patterns, quirks, or conventions, update `.factory/library/architecture.md`.
2. If you changed environment setup, update `.factory/library/environment.md`.
3. If you removed duplicate-truth or dead code paths and the packet explicitly allows docs/library updates, append a concise dated note to `.factory/library/remediation-log.md`; otherwise state in the handoff that the packet intentionally skipped remediation-log updates because docs/library edits were out of scope.
4. If you found issues outside your feature scope, report them in `discoveredIssues`.

## Example Handoff

```json
{
  "salientSummary": "Decomposed AccountingService (5728 lines) into 6 focused services: JournalEntryService, DealerReceiptService, SettlementService, CreditDebitNoteService, AccountingAuditService, AccountingIdempotencyService. All 369 tests pass. AccountingService now delegates to sub-services (facade pattern) keeping backward compatibility.",
  "whatWasImplemented": "Created 6 new service classes extracted from AccountingService. Moved journal entry creation/reversal to JournalEntryService (412 lines), dealer receipt recording to DealerReceiptService (380 lines), settlement logic to SettlementService (450 lines), credit/debit note posting to CreditDebitNoteService (290 lines), audit event handling to AccountingAuditService (180 lines), and idempotency reservation/replay logic to AccountingIdempotencyService (350 lines). Updated AccountingService to delegate to these services. Updated AccountingController and all 14 callers across modules. All existing tests pass without modification.",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      {"command": "cd erp-domain && mvn compile -q", "exitCode": 0, "observation": "Clean compilation"},
      {"command": "cd erp-domain && mvn test -Djacoco.skip=true -T4", "exitCode": 0, "observation": "369 tests, 1 pre-existing failure (TS_RuntimeTenantPolicyControl), all others pass"},
      {"command": "grep -r 'AccountingService' --include='*.java' erp-domain/src/main/java/ | wc -l", "exitCode": 0, "observation": "28 references remaining (facade delegations + imports)"}
    ],
    "interactiveChecks": []
  },
  "tests": {
    "added": [
      {"file": "src/test/java/.../service/JournalEntryServiceTest.java", "cases": [
        {"name": "createJournalEntry_validRequest_createsEntry", "verifies": "Journal entry creation in new service"},
        {"name": "createJournalEntry_duplicateIdempotencyKey_returnsExisting", "verifies": "Idempotency replay in extracted service"}
      ]}
    ]
  },
  "discoveredIssues": [
    {"severity": "medium", "description": "AccountingFacade still has 200 lines of inline utility methods that could be extracted to a shared AccountingUtils class", "suggestedFix": "Create AccountingUtils and move normalizeAmount, sameCurrency, sameFxRate, etc."}
  ],
  "frontendHandoff": "Updated .factory/library/frontend-handoff.md Accounting section with: 18 endpoints (journal CRUD, settlements, credit/debit notes, reconciliation), 4 user flows (journal entry, dealer settlement, period close, bank reconciliation), 2 state machines (journal lifecycle, period lifecycle), 12 error codes, all request/response DTOs with field descriptions."
}
```

## When to Return to Orchestrator

- Feature depends on a service or entity that doesn't exist yet (precondition not met)
- Decomposition would break more than 50 callers and needs orchestrator to decide on approach
- New Flyway migration conflicts with another worker's migration (version number collision)
- Test suite has more than 3 new failures unrelated to your changes
- Requirements are ambiguous about whether to preserve backward compatibility
- You discover a security vulnerability that needs immediate attention
