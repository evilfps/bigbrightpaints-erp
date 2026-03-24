---
name: docs-worker
description: Mission documentation worker for definition-of-done, remediation logs, frontend-v2 handoff notes, and README alignment.
---

# Docs Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for documentation-only mission features that update:
- `.factory/library/erp-definition-of-done.md`
- `.factory/library/remediation-log.md`
- `.factory/library/frontend-v2.md`
- `.factory/library/frontend-handoff.md`
- repository `README.md` files when explicitly assigned by a mission feature or end-of-mission gate

This worker does not change application code.

## Work Procedure

### Step 1: Lock scope and source truth
1. Read `mission.md`, mission `AGENTS.md`, and the assigned feature carefully.
2. Read `.factory/library/architecture.md`, `.factory/library/environment.md`, `.factory/library/user-testing.md`, and any existing target docs before editing.
3. Treat the approved mission scope and current implementation state as source truth; do not invent capabilities that the code does not have.

### Step 2: Gather evidence
1. Read the relevant code paths, tests, mission artifacts, and validation reports needed to document the assigned area accurately.
2. For frontend-facing notes, map endpoints, roles, blockers, artifacts, and contract deltas directly from code/tests.
3. If a behavior is still planned rather than implemented, label it clearly as pending or future work instead of documenting it as done.

### Step 3: Write high-signal docs only
1. Keep documentation focused on what future workers, reviewers, and frontend-v2 consumers need.
2. For `erp-definition-of-done.md`, keep the approved scope, truth boundaries, canonical document chain, costing choice, v2-only rule, and portal model explicit.
3. For `remediation-log.md`, append dated entries capturing what was cleaned, why it was risky, and what dead or duplicate-truth code was removed.
4. For `frontend-v2.md` and `frontend-handoff.md`, record only backend-facing contract details the frontend team needs: endpoint changes, role-surface changes, blocker semantics, generated artifacts, and migration notes.
5. When updating README files, keep setup, run, test, and environment guidance aligned with the current repo state.

### Step 4: Verify accuracy
1. Re-read every edited doc against the feature description and code evidence.
2. Docs-only features do **not** require repo-wide compile, test, or code-review passes unless the feature explicitly says otherwise.
3. Run lightweight verification as needed (for example, JSON/OpenAPI spot checks, knowledgebase lint, or grep-based existence checks) but do not start services unless the feature explicitly requires runtime evidence.
4. Confirm that docs do not mention Flyway v1; this mission is v2-only.
5. If worker-base baseline validation fails on a clearly unrelated pre-existing issue, record it briefly but continue and complete the docs packet as long as the scoped documentation checks pass.

### Step 5: Produce a precise handoff
Include:
- exact docs edited
- what evidence was used
- any areas intentionally left pending because implementation is not complete yet
- any inconsistencies discovered between docs and runtime/code

## Example Handoff

```json
{
  "salientSummary": "Updated the mission documentation set so workers and frontend-v2 consumers can follow the approved ERP truth model. Added the definition-of-done reference, initialized the remediation log, and captured the current O2C/P2P contract notes without overstating unfinished behavior.",
  "whatWasImplemented": "Created .factory/library/erp-definition-of-done.md with the approved workflow-centric ERP scope, canonical portal roles, v2-only migration rule, and phase-one costing decision. Updated .factory/library/frontend-v2.md and .factory/library/frontend-handoff.md with the current backend-facing notes for dispatch truth, supplier lifecycle blocking, role surfaces, and generated challan expectations. Added a dated entry to .factory/library/remediation-log.md describing which duplicate-truth paths were removed and why.",
  "whatWasLeftUndone": "Deferred final README wording for the portal milestone because the role matrix implementation is not merged yet.",
  "verification": {
    "commandsRun": [
      {"command": "python3 - <<'PY'\nfrom pathlib import Path\nfor p in [Path('.factory/library/erp-definition-of-done.md'), Path('.factory/library/remediation-log.md'), Path('.factory/library/frontend-v2.md')]:\n    assert p.exists(), p\nprint('ok')\nPY", "exitCode": 0, "observation": "Expected mission docs exist."}
    ],
    "interactiveChecks": [
      {"action": "Compared edited docs against mission.md and touched tests/services.", "observed": "The docs reflect approved scope and current implementation state without referencing Flyway v1."}
    ]
  },
  "tests": {
    "added": []
  },
  "discoveredIssues": [
    {"severity": "medium", "description": "Frontend-handoff notes still reference an older portal access assumption that should be updated once the portal-boundary milestone lands.", "suggestedFix": "Refresh the portal section during the portal-boundaries milestone handoff feature."}
  ]
}
```

## When to Return to Orchestrator

- The requested documentation would require inventing behavior that the implementation has not delivered yet.
- The feature actually needs application-code changes rather than documentation.
- Source-of-truth evidence is contradictory and needs an orchestration decision.
