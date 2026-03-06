---
name: review-worker
description: Review-and-documentation worker for production audits, flow mapping, and risk analysis in the BigBright ERP.
---

# Review Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for review-only features that:
- map controllers, services, entities, and cross-module flows
- create or update `docs/code-review/**`
- analyze security, privacy, integrity, performance, observability, resilience, CI, or architecture risks
- synthesize findings into coverage matrices, risk registers, and remediation backlogs

This worker does **not** fix application code in this mission.

## Work Procedure

### Step 1: Lock the scope before touching files
1. Read `mission.md`, mission `AGENTS.md`, and the feature description carefully.
2. Read `.factory/services.yaml` for the approved runtime boundaries and allowed commands.
3. Read `.factory/library/architecture.md`, `.factory/library/environment.md`, `.factory/library/user-testing.md`, and `.factory/library/review-playbook.md`.
4. Identify exactly which review artifacts this feature owns.
5. Confirm that the write surface is limited to `docs/code-review/**`. If you think a source-code change is needed, stop and return to orchestrator.

### Step 2: Gather evidence
1. Trace the assigned flow or area from controller entrypoints through service chains, entity/data paths, and side effects.
2. Use targeted repo inspection to gather evidence: controllers, services, entities, config, workflows, scripts, tests, migrations, and runtime config.
3. When runtime evidence is needed, prefer passive probes only:
   - `curl -sf http://localhost:8081/actuator/health`
   - read-only Swagger/OpenAPI surfaces if available
4. Do not mutate shared state unless the feature explicitly requires it and the mission boundaries allow it.
5. If delegated helper tooling or Task-style subagents are unavailable because of model-resolution issues, continue with direct in-session analysis whenever the feature can still be completed to the required evidence standard. Record the limitation explicitly in the handoff instead of returning solely because helper delegation failed.

### Step 3: Write the review artifact
1. Create or update only the files owned by the feature under `docs/code-review/**`.
2. For every flow file, write a **narrative walkthrough** that includes:
   - business purpose
   - entrypoints/controllers
   - service/class chain
   - entities/data path
   - DB schema/migration touchpoints where relevant
   - invariants and state-machine assumptions
   - integrations and side effects
   - failure points and recovery behavior
   - security, privacy, and protocol/protection notes
   - performance, observability, resilience, and bad-pattern notes
   - concrete evidence with file/class/method/script references
3. For synthesis files, ensure links and references are internally consistent.

### Step 4: Verify the artifact
1. Re-read the produced doc and confirm it covers every required section for the feature.
2. Run the smallest useful verification commands to support the artifact:
   - repo inspection commands
   - passive runtime probes if needed
   - compile/test commands only when the feature depends on them for evidence
3. If the repository started dirty outside your scope, leave unrelated changes untouched and isolate your owned changes clearly. Confirm that you did not modify files outside `docs/code-review/**` during the session, and call out any pre-existing unrelated dirty files in the handoff instead of trying to clean them.

### Step 5: Produce a high-signal handoff
1. Summarize what review artifact was produced.
2. List the evidence commands and what they proved.
3. Record discovered issues with severity, why they matter, and suggested next-step remediation.
4. If the feature was blocked by missing surfaces, invalid runtime state, or worker/tool issues, make that explicit.

## Example Handoff

```json
{
  "salientSummary": "Produced the order-to-cash review narrative and documented dealer onboarding, sales order lifecycle, dispatch, invoicing, settlement, and accounting linkage risks. Verified the doc against controller surfaces and identified three high-risk integrity gaps around status transitions and idempotency assumptions.",
  "whatWasImplemented": "Created docs/code-review/flows/order-to-cash.md as a full-stack narrative walkthrough. The document maps DealerController, DealerPortalController, SalesController, InvoiceController, and linked accounting services to their downstream entities and side effects. It includes sections for state-machine assumptions, accounting coupling, privacy/security observations, performance considerations, and bad-pattern hotspots, all backed by code-path references.",
  "whatWasLeftUndone": "Did not create the cross-area risk register because that belongs to the later synthesis feature.",
  "verification": {
    "commandsRun": [
      {
        "command": "curl -sf http://localhost:8081/actuator/health",
        "exitCode": 0,
        "observation": "Existing review surface is reachable; passive runtime evidence available."
      },
      {
        "command": "cd /home/realnigga/Desktop/Mission-control && python3 scripts/surefire_report_summary.py --help",
        "exitCode": 0,
        "observation": "Test-governance helper script is available for later CI review evidence."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Read docs/code-review/flows/order-to-cash.md after authoring and compare headings against the required flow-doc sections in AGENTS.md and review-playbook.md.",
        "observed": "The document contains narrative walkthrough, entrypoints, service chain, data path, invariants, side effects, failure points, security/privacy/protocol notes, performance/observability notes, and evidence references."
      }
    ]
  },
  "tests": {
    "added": []
  },
  "discoveredIssues": [
    {
      "severity": "high",
      "description": "Sales order status transitions appear to rely on distributed checks across multiple services instead of a single explicit state model, increasing the risk of inconsistent dispatch/invoice behavior under retries or partial failures.",
      "suggestedFix": "Introduce a centralized state-transition contract and follow-up review of idempotent transition boundaries in the later remediation mission."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature would require changing source code, tests, CI, Docker files, or runtime config to complete
- Required evidence depends on a destructive or state-changing runtime action
- The runtime surface on `8081` is unavailable and the doc cannot be completed from static analysis alone
- The owned review artifact depends on another unfinished review artifact for cross-linking or synthesis
- Tooling or worker execution issues prevent the feature from collecting the promised evidence
