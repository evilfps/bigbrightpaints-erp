---
name: dod-flow-documenter
description: Documents a single ERP flow at full depth by reading source code and producing the 20-section DoD spec with canonical path identification and cross-module coupling analysis.
---

# DoD Flow Documenter

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

This skill handles features that document individual ERP flows in the DoD Bible. Each feature covers 1-2 flows. The output is appended to `docs/ERP-DOD-BIBLE.md`.

## Required Skills

None. This is a read-and-write documentation task. No code execution needed.

## Work Procedure

### Step 1: Understand the Feature

Read the feature description from features.json to understand which flow(s) you are documenting. Note the specific modules, services, entities, and controllers mentioned.

### Step 2: Read Source Code

For the assigned flow, systematically read:

1. **Entity files** — Find the domain entities in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/{module}/domain/`. Read ALL entity files to understand fields, relationships, status enums, and constraints.

2. **Controller files** — Find controllers in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/{module}/controller/`. Map ALL endpoints (method, path, @PreAuthorize annotations, request/response types).

3. **Service files** — Find services in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/{module}/service/`. Read the core engine/service to understand:
   - State transitions and guards
   - Validation rules
   - Side effects (what records are created/updated)
   - Integration points with other modules
   - Idempotency patterns

4. **Enum files** — Find ALL enums (status, type, correction type, etc.) and document exact values.

5. **Existing documentation** — Read the corresponding workflow doc in `docs/workflows/` and any relevant `.factory/library/` files.

6. **Domain maps** — Read the relevant domain map from `artifacts/` for additional context.

### Step 3: Identify Canonical Path vs Alternatives

For the assigned flow, trace the PRIMARY business path through the code:

1. Identify the ONE canonical entry point (e.g., `SalesCoreEngine.confirmDispatch()` for dispatch)
2. Trace the canonical path through services to its ultimate effects (inventory, accounting, tax, events)
3. Search for ANY alternative code paths that achieve the same or similar effect:
   - Other controllers/services that trigger the same flow
   - Legacy endpoints that still exist
   - Direct repository access that bypasses the canonical service
   - Duplicate validation logic in multiple places
4. Search for dead/unreachable code:
   - Entity fields that are written but never read
   - Status values that are defined but never transitioned to
   - Endpoints that exist but have no realistic caller
   - Services/methods that are never invoked

Mark findings:
- `[CANONICAL]` for the primary path
- `[NON-CANONICAL]` for alternative/duplicate paths
- `[DEAD-CODE]` for unreachable entities/endpoints/statuses
- `[UNCLEAR-OWNERSHIP]` where responsibility is ambiguous
- `[DECISION-REQUIRED]` where founder must decide the intended behavior

### Step 4: Map Cross-Module Coupling

For the assigned flow:

1. List every module the flow touches (with specific class/service names)
2. Draw call direction arrows (e.g., `sales → accounting via AccountingFacade.postJournal()`)
3. Identify TIGHT coupling:
   - Does the flow read another module's repository directly? (violation)
   - Does the flow mutate another module's entity directly? (violation)
   - Are there shared database tables accessed from multiple modules?
4. Identify HIDDEN dependencies:
   - Does the flow depend on state in another module without an explicit call?
   - Does it read configuration from another module's tables?
5. Identify SHARED ENTITY risks:
   - Which entities are mutated by more than one module?
   - Are there race conditions possible?

### Step 5: Write the Flow Documentation

Write the complete flow documentation to `docs/ERP-DOD-BIBLE.md`. If the file doesn't exist, create it with a title and introduction. If it exists, append your flow section.

Each flow MUST have ALL 20 sections from the workbook template:

```
## Flow N: [Flow Name]

### A. Flow Identity
### B. Business Objective
### C. Actors
### D. Preconditions
### E. Trigger
### F. Input Contract
### G. Step-by-Step Lifecycle
### H. State Machine
### I. Validation Rules
### J. Side Effects
### K. Inventory Effect
### L. Accounting Effect
### M. Tax Effect
### N. Failure Handling
### O. Auditability
### P. Reporting Impact
### Q. Completion Criteria
### R. Edge Cases
### S. Non-Negotiables

### Current Implementation Map
### Canonical Path Analysis
### Cross-Module Coupling Map
### Current Maturity Grade
### Done Checklist
### Open Decisions ([DECISION-REQUIRED] items)
```

### Step 6: Verify Against Source

After writing, verify your documentation by:
1. Cross-checking every status value against the enum source
2. Cross-checking every API path against controller annotations
3. Cross-checking every role against `SystemRole` enum
4. Cross-checking every accounting effect against `AccountingCoreEngine` or relevant facade
5. Ensuring no section is empty or contains only placeholder text

### Step 7: Commit

Stage and commit the updated `docs/ERP-DOD-BIBLE.md` file with a descriptive commit message:
```
docs(dod): add Flow N - [flow name] DoD specification
```

## Example Handoff

```json
{
  "salientSummary": "Documented Flow 5 (Sales Order Creation and Approval) with all 20 sections. Identified SalesCoreEngine.createOrderInternal() as canonical path. Found 3 alternative paths: legacy PATCH /status endpoint, direct status update in SalesOrderCrudService, and SalesProformaBoundaryService proforma flow. Flagged Promotion entity as [DEAD-CODE] and hardcoded 15-day invoice due date as [DECISION-REQUIRED].",
  "whatWasImplemented": "Full 20-section DoD spec for Sales Order flow appended to docs/ERP-DOD-BIBLE.md (approximately 2500 words). Includes state machine with all 14 statuses and 40+ transitions, 16 API endpoints, accounting effects (AR journal + COGS journal), inventory effects (reservation + release), GST handling (3 treatment modes), 12 edge cases, and 5 [DECISION-REQUIRED] items.",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      { "command": "grep -c '### ' docs/ERP-DOD-BIBLE.md", "exitCode": 0, "observation": "Confirmed 20+ section headers present for Flow 5" },
      { "command": "grep 'DECISION-REQUIRED' docs/ERP-DOD-BIBLE.md | head -20", "exitCode": 0, "observation": "Found 5 decision-required flags in Flow 5 section" }
    ],
    "interactiveChecks": []
  },
  "tests": { "added": [] },
  "discoveredIssues": [
    { "severity": "medium", "description": "Promotion entity has CRUD endpoints but no application logic - flagged as [DEAD-CODE]", "suggestedFix": "Founder to decide: implement promotion application logic or remove Promotion entity" },
    { "severity": "high", "description": "Invoice due date hardcoded to dispatch+15 days, ignoring dealer payment terms (NET_30/60/90)", "suggestedFix": "[DECISION-REQUIRED] Founder to decide: use dealer payment terms or keep hardcoded" }
  ]
}
```

## When to Return to Orchestrator

- Cannot find the expected source files for the assigned flow (wrong module path)
- Source code contradicts the feature description significantly
- Cannot determine canonical path because multiple equally-valid paths exist (needs founder decision)
- The output file is corrupted or cannot be written
