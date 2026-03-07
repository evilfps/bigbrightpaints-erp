---
name: security-backend-worker
description: Security/auth-focused Java/Spring backend worker for compatibility-preserving hardening in the BigBright ERP.
---

# Security Backend Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for backend features that change:
- authentication or session flows
- password reset, recovery, MFA, or token lifecycle behavior
- tenant-admin / super-admin / cross-tenant authorization boundaries
- security-sensitive admin settings or security error contracts
- compatibility-preserving hardening around frontend-sensitive auth/admin endpoints

## Work Procedure

### Step 1: Understand the feature and its blast radius
1. Read the feature description, preconditions, expectedBehavior, and verificationSteps carefully.
2. Read `AGENTS.md`, `.factory/services.yaml`, `.factory/library/auth-hardening.md`, `.factory/library/frontend-handoff.md`, and `.factory/library/packet-governance.md` before planning changes.
3. Read the relevant review evidence in:
   - `docs/code-review/flows/auth-identity.md`
   - `docs/code-review/flows/admin-governance.md`
   - `docs/code-review/risk-register.md`
4. Read the packet controls for the active lane in:
   - `docs/code-review/executable-specs/PACKET-TEMPLATE.md`
   - `docs/code-review/executable-specs/RELEASE-GATE.md`
   - the lane-specific `EXEC-SPEC.md`
5. Enumerate all touched endpoints, DTOs, roles, tenant/company-boundary checks, token stores, migrations, and adjacent ERP-sensitive flows before coding.
6. Explicitly note whether the feature is expected to preserve the current request/response shape. Default assumption: preserve it.
7. Reconfirm the packet stays inside its assigned lane: Lane 01 work must not absorb auth-secret migration, and Lane 02 work must not reopen control-plane lifecycle/runtime redesign.

### Step 2: Write characterization tests first
1. Before implementation, add or update tests that lock in the current contract and reproduce the security or boundary problem.
2. For endpoint or DTO changes, write contract-focused tests first.
3. For boundary changes, write both allowed and denied cases first.
4. For token or session changes, write replay/revocation tests first.
5. Run the targeted suite and confirm it fails before implementation.

### Step 3: Implement the minimal compatible fix
1. Make the smallest change that closes the security gap without unnecessary API churn.
2. Preserve request/response shapes unless the feature explicitly allows a documented change.
3. If the packet requires schema work, use Flyway `v2` only (`db/migration_v2`, `flyway_schema_history_v2`) and do not touch legacy `db/migration/**`.
4. If a shape change is truly unavoidable:
   - update `.factory/library/frontend-handoff.md` in the same session,
   - update the relevant `docs/frontend-update-v2/**` tracker entry in the same session,
   - describe the exact contract delta,
   - include migration notes for the frontend,
   - mention it explicitly in the handoff summary.
5. Keep company/tenant binding fail-closed.
6. Prefer strengthening existing code paths over introducing parallel contracts unless the feature specifically requires a replacement path.

### Step 4: Verify aggressively
1. Run `cd erp-domain && mvn compile -q`.
2. Run the feature-specific targeted tests from `features.json`.
3. Run `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`.
4. Re-read every touched controller, DTO, migration, and error path in the diff looking for silent regressions.
5. Explicitly verify adjacent ERP-sensitive flows touched by the change (for example: login/refresh/logout, `/auth/me`, forgot/reset, admin user controls, admin settings authz, tenant binding).
6. If the local app is started and the feature needs runtime evidence, verify the changed flow with `curl` and capture the exact sequence.
7. Before closing the packet, record whether release-gate evidence and rollback notes are complete; if branch review or merge action is required, return to the orchestrator instead of guessing.

### Step 5: Update shared knowledge
1. Update `.factory/library/frontend-handoff.md` for any touched auth/admin surface. If nothing changed, say so explicitly.
2. Update the relevant `docs/frontend-update-v2/**` entry for any frontend-relevant auth/admin/control-plane outcome, including explicit no-op notes where applicable.
3. When a packet restores live runtime behavior to an already-published contract on a frontend-relevant surface, still record that parity/no-op conclusion in the handoff docs instead of silently skipping the update.
4. If you learned a new auth/security constraint or rollout caveat, update `.factory/library/auth-hardening.md`.
5. If runtime setup/verification changed, update `.factory/library/user-testing.md`.

### Step 6: Produce a strict handoff
Your handoff must make shortcuts visible. Include:
- exact endpoints / services / migrations touched
- whether API shapes changed (`yes` or `no`)
- the regression flows you rechecked explicitly
- exact commands run and what passed/failed
- whether the packet touched Flyway `v2` schema work and what compatibility window remains
- any remaining risk or rollout caveat

## Example Handoff

```json
{
  "salientSummary": "Hardened password reset and session revocation while preserving the existing login/refresh/logout payloads. Added contract tests for public reset plus admin recovery, updated the token-revocation path so password change invalidates prior sessions, and documented that no auth/admin request or response shapes changed.",
  "whatWasImplemented": "Reworked refresh-token and password-reset persistence to use digest-backed lookup, updated PasswordService and reset flows so password change/reset/disable now revoke prior access and refresh tokens consistently, and normalized the broken super-admin recovery alias into a single supported path. Added characterization tests first, then implementation, then updated frontend-handoff.md with an explicit 'no shape changes required' note for auth/admin endpoints.",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      {"command": "cd erp-domain && mvn test -Djacoco.skip=true -pl . -Dtest=AuthControllerIT,AuthPasswordResetPublicContractIT,PasswordResetServiceTest,PasswordServiceTest", "exitCode": 0, "observation": "Targeted auth/reset contract and service tests passed after initially failing during red phase."},
      {"command": "cd erp-domain && mvn compile -q", "exitCode": 0, "observation": "Compilation clean after auth hardening changes."},
      {"command": "cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true", "exitCode": 0, "observation": "Gate-fast passed with no regressions."},
      {"command": "curl -i -sS -X POST http://localhost:8081/api/v1/auth/login ...", "exitCode": 0, "observation": "Login payload shape remained compatible; follow-up old-token request was rejected after password change."}
    ],
    "interactiveChecks": [
      {"action": "Rechecked adjacent ERP-sensitive auth flows: login, refresh, logout, /auth/me, public forgot/reset, admin force-reset, and tenant binding on admin settings.", "observed": "All rechecked flows matched the expected contract; no silent payload drift found."}
    ]
  },
  "tests": {
    "added": [
      {
        "file": "erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthHardeningIT.java",
        "cases": [
          {"name": "passwordChange_revokesExistingTokens", "verifies": "Old sessions are invalid after password change."},
          {"name": "mustChangePassword_userBlockedFromProtectedWork", "verifies": "Temporary credentials only allow the password-change corridor."}
        ]
      }
    ]
  },
  "discoveredIssues": [
    {
      "severity": "medium",
      "description": "Admin settings authz still exposes a non-auth security boundary that should be reviewed if future features broaden the mission.",
      "suggestedFix": "Keep the current mission scoped, or create a follow-up feature if that surface must change in the same milestone."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature requires a breaking auth/admin API shape change larger than a narrow, documented compatibility adjustment.
- A migration or token-storage transition plan looks unsafe or ambiguous.
- A boundary fix expands into unrelated domain refactoring outside the agreed security/auth scope.
- Targeted regression coverage reveals an adjacent ERP-breaking flow that is not safely fixable inside the current feature.
- Runtime verification is required but the needed infrastructure or credentials are unavailable.
- The packet is technically complete but now needs base-branch review, merge handling, or release-gate judgment from the orchestrator.
