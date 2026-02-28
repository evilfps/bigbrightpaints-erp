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

## Work Procedure

### Step 1: Understand the Feature
1. Read the feature description, preconditions, expectedBehavior, and verificationSteps carefully.
2. Read `AGENTS.md` for mission boundaries and coding conventions.
3. Read `.factory/services.yaml` for available commands.
4. Read `.factory/library/architecture.md` for codebase patterns.
5. Identify all files that need to change. For refactoring features, trace all callers/importers of the classes being modified.

### Step 2: Write Tests First (TDD)
1. For bug fixes: write a test that reproduces the bug (should fail).
2. For new features: write tests covering happy path, error cases, and edge cases (should fail).
3. For refactoring: ensure existing tests pass first, then write any missing tests for the code being refactored.
4. Run tests to confirm they fail as expected: `cd erp-domain && mvn test -Djacoco.skip=true -pl . -Dtest=YourTestClass`

### Step 3: Implement
1. Make the minimal changes needed to satisfy the feature requirements.
2. Follow coding conventions from AGENTS.md strictly:
   - ApplicationException for all business errors (never IllegalArgumentException/IllegalStateException)
   - Service classes under 500 lines
   - Single responsibility per service
   - Company-scoped queries
   - Event-driven cross-module communication
3. For service decomposition:
   - Create new focused service classes
   - Move methods from the god service to appropriate new services
   - Update the god service to delegate to new services (keep it as a facade initially if needed)
   - Update all controllers and other services that call the moved methods
   - Update all test imports and references
4. For new Flyway migrations: continue from the highest existing version number.

### Step 4: Verify
1. Run the full test suite: `cd erp-domain && mvn test -Djacoco.skip=true -T4`
2. Run compilation check: `cd erp-domain && mvn compile -q`
3. Manually verify key behaviors using the approach appropriate for the feature:
   - For new endpoints: document curl commands that demonstrate the endpoint works
   - For refactoring: confirm all callers are updated and tests pass
   - For bug fixes: confirm the specific bug is fixed via the test you wrote
4. Check for any regressions in related modules.

### Step 5: Document Frontend Handoff
If your feature adds or changes API endpoints, you MUST update `.factory/library/frontend-handoff.md` with:
1. **Endpoint map**: Every new/changed endpoint (method, path, auth, request/response types)
2. **User flows**: Step-by-step API call sequences for each user-facing flow
3. **State machines**: Entity lifecycle states and valid transitions with triggering API calls
4. **Error codes**: All ErrorCodes the module returns with descriptions and suggested frontend behavior
5. **Data contracts**: Request/response record definitions with field types, validation, required/optional
6. **UI hints**: Dropdown sources (endpoint for options), computed fields, field dependencies

This is a mandatory deliverable. A frontend developer should be able to build the UI from this documentation alone.

### Step 6: Update Shared Knowledge
1. If you discovered important patterns, quirks, or conventions, update `.factory/library/architecture.md`.
2. If you changed environment setup, update `.factory/library/environment.md`.
3. If you found issues outside your feature scope, report them in `discoveredIssues`.

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
