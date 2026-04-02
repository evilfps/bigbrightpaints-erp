---
name: docs-worker
description: Documentation worker for the backend truth library, flow packets, ADRs, frontend handoff packets, deprecated registries, and docs-governance surfaces.
---

# Docs Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for documentation-only mission features that create or update:
- canonical docs tree packets under `docs/`
- ADR index and ADR files
- module packets, flow packets, frontend handoff packets, and deprecated/incomplete registries
- mainline catch-up docs under `docs/frontend-portals/` and `docs/frontend-api/`
- lint-required governance docs such as `docs/INDEX.md`, `docs/agents/*`, `docs/approvals/*`, root `ARCHITECTURE.md`, and module `AGENTS.md`
- `.factory/library/*` guidance files that support the docs mission
- repository `README.md` files only when a feature explicitly asks for them

This worker does not change application code.

## Required Skills

None.

## Work Procedure

### Step 1: Lock scope and source truth
1. Read `mission.md`, mission `AGENTS.md`, and the assigned feature carefully.
2. Read `.factory/library/architecture.md`, `.factory/library/environment.md`, `.factory/library/user-testing.md`, `.factory/library/documentation-ia.md`, `.factory/library/docs-validation.md`, and any existing target docs before editing.
3. Treat the approved mission scope and current implementation state as source truth; do not invent capabilities that the code does not have.

### Step 2: Gather evidence
1. Read the relevant code paths, DTOs, controllers, services, tests, mission artifacts, OpenAPI snapshot, and existing docs needed to document the assigned area accurately.
2. For module packets, identify the owning controllers, services/facades/engines, DTO families, entities, helpers, events, and cross-module seams.
3. For flow packets, identify actors, entrypoints, preconditions, canonical lifecycle, completion boundary/current definition of done, non-canonical paths, access-control gates, event/listener seams, relevant ADRs, and known limitations.
4. For frontend handoff packets, map hosts, payload families, RBAC assumptions, read/write boundaries, and canonical links back to module/flow packets.
5. For mainline catch-up portal/API packets, treat `docs/frontend-portals/` and `docs/frontend-api/` as the target canonical frontend-facing docs structure, and explicitly retire or mark older branch-era handoff docs when they would otherwise compete.
6. If a behavior is still planned rather than implemented, label it clearly as pending, incomplete, deprecated, or future work instead of documenting it as done.

### Step 3: Write the assigned packet type correctly
1. **Module packets** must explain what the module owns, the primary entrypoints, major DTO families, important helpers/events, and the module's boundaries with adjacent modules.
2. **Flow packets** must be behavior-first: who triggers the flow, what enters it, what must already be true, what canonical lifecycle occurs, how the flow completes today, what is incomplete, where canonical/non-canonical seams diverge, which event/listener boundaries materially affect the flow, and which ADRs explain key decisions behind that flow.
3. **Frontend handoff packets** must be consumer-first: canonical hosts, payload groups, RBAC assumptions, read/write boundaries, and explicit links back to module and flow truth.
4. **Mainline portal/API packets** must preserve the current frontend shell split: per-portal ownership docs under `docs/frontend-portals/` and shared cross-portal rules under `docs/frontend-api/`.
5. **ADR packets** must explain an actual backend decision already embodied by the codebase; they are not speculative design notes.
6. **Deprecated/incomplete registry entries** must point to a canonical replacement or explicitly say there is no replacement.
7. Update root/governance docs only as much as needed to keep the docs tree navigable and truthful.

### Step 4: Verify accuracy
1. Re-read every edited doc against the feature description and code evidence.
2. Docs-only features do **not** require repo-wide compile or runtime tests unless the feature explicitly says otherwise.
3. If the mission `AGENTS.md` or feature description says docs-only packets skip validators, follow that instruction and record the skip explicitly in the handoff instead of running lint/check scripts.
4. Otherwise run the docs validators relevant to the packet:
   - `bash ci/lint-knowledgebase.sh`
   - `bash ci/check-enterprise-policy.sh`
   - `bash ci/check-architecture.sh`
   - `bash ci/check-orchestrator-layer.sh`
   Use the narrowest set that proves the packet, but run the packet's required commands from the feature verification steps.
5. Do not start services or mutate runtime state for this mission unless a feature explicitly requires non-doc evidence.
6. Verify links, route/host names, role names, DTO names, and deprecated markers against source and `openapi.json`.
7. When documenting endpoint paths, confirm each path string against controller annotations and `openapi.json` before writing it into a packet; do not rely on memory or inferred naming.
8. If a validator fails on a clearly unrelated pre-existing issue, record it precisely but still complete the packet if the scoped docs checks are otherwise valid.

### Step 5: Produce a precise handoff
Include:
- exact docs edited
- what source evidence was used (controllers/services/tests/OpenAPI/older docs)
- which validator commands were run and what they proved
- any areas intentionally left pending because implementation is not complete yet
- any contradictions, deprecated seams, or open decisions discovered while documenting

## Example Handoff

```json
{
  "salientSummary": "Built the platform docs packet for auth/company runtime and linked it into the new backend docs tree. The packet now explains the current login/refresh/MFA/password-reset corridor, company-scoping/runtime admission, and token/session revocation model without overstating fail-open behavior.",
  "whatWasImplemented": "Created docs/modules/auth.md and docs/modules/company.md plus supporting links from docs/INDEX.md and docs/flows/README.md. The auth packet covers login, refresh, logout, MFA, password reset, must-change-password behavior, token blacklisting, and licensing/runtime caveats. The company packet covers tenant lifecycle, runtime admission, module gating, and company-scoping assumptions, with explicit notes for deprecated or partial behavior.",
  "whatWasLeftUndone": "Did not document the frontend handoff view for these surfaces because that is assigned to a later handoff feature.",
  "verification": {
    "commandsRun": [
      {"command": "bash ci/lint-knowledgebase.sh", "exitCode": 0, "observation": "Docs tree and canonical governance links remain valid."},
      {"command": "bash ci/check-enterprise-policy.sh", "exitCode": 0, "observation": "No policy regressions introduced by docs-only changes."}
    ],
    "interactiveChecks": [
      {"action": "Compared the new auth/company packet against controller routes, service behavior, and mission scope.", "observed": "The packet reflects implemented behavior and explicitly labels caveats instead of presenting them as guarantees."}
    ]
  },
  "tests": {
    "added": []
  },
  "discoveredIssues": [
    {"severity": "medium", "description": "The runtime-admission story is split across multiple services/interceptors, which the docs now call out as a coupling seam.", "suggestedFix": "Keep the platform packet and ADR set aligned if the runtime-gating surfaces are consolidated later."}
  ]
}
```

## When to Return to Orchestrator

- The requested documentation would require inventing behavior that the implementation has not delivered yet.
- The feature actually needs application-code changes rather than documentation.
- Source-of-truth evidence is contradictory and needs an orchestration decision.
