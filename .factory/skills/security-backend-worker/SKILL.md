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
- CI-only router/manifest/contract packets for security-sensitive catching lanes when the feature intentionally changes verification surfaces rather than runtime product code

## Work Procedure

### Step 1: Understand the feature and its blast radius
1. Read the feature description, preconditions, expectedBehavior, and verificationSteps carefully.
2. Read `AGENTS.md`, `.factory/services.yaml`, `.factory/library/auth-hardening.md`, and `.factory/library/frontend-handoff.md` before planning changes.
3. Read the mission `validation-contract.md` and note exactly which assertion IDs the feature must make testable.
4. If the feature description names an exact catching lane, treat that lane as mandatory verification rather than optional follow-up.
5. Read the relevant review evidence in:
   - `docs/code-review/flows/auth-identity.md`
   - `docs/code-review/flows/admin-governance.md`
   - `docs/code-review/risk-register.md`
6. Enumerate all touched endpoints, DTOs, roles, tenant/company-boundary checks, token stores, and adjacent ERP-sensitive flows before coding.
7. Explicitly note whether the feature is expected to preserve the current request/response shape. Default assumption: preserve it.

### Step 2: Write characterization tests first
1. Before implementation, add or update tests that lock in the current contract and reproduce the security or boundary problem.
2. For endpoint or DTO changes, write contract-focused tests first.
3. For boundary changes, write both allowed and denied cases first.
4. For token or session changes, write replay/revocation tests first.
5. If the touched security/control area already has stale policy or regression coverage, realign that suite in the same packet instead of leaving it behind.
6. If the feature removes a stale compatibility path, add regression coverage that proves the stale path is gone or no longer mutates state before implementation is considered complete.
7. For CI-only routing or manifest packets, the characterization path may be router/manifest contract tests plus the exact shard command that reproduces the miss; do not invent unrelated runtime changes just to force a generic red/green flow.
8. Run the targeted suite and confirm it fails before implementation.

### Step 3: Implement the minimal compatible fix
1. Make the smallest change that closes the security gap without unnecessary API churn.
2. Preserve request/response shapes unless the feature explicitly allows a documented change.
3. Remove obsolete authz checks, stale override paths, unused compatibility branches, and dead security-related code in the touched area when they would otherwise leave production confusion behind.
4. When a feature is about canonicalizing a control-plane path, do not leave a second public writer, helper allowlist entry, or OpenAPI contract behind in the touched surface.
5. For CI-only routing packets, keep the diff limited to manifests, router logic, and regression tests that prove the intended catching lane now fires for the feature's file surface.
6. If a shape change is truly unavoidable:
   - update `.factory/library/frontend-handoff.md` in the same session,
   - describe the exact contract delta,
   - include migration notes for the frontend,
   - mention it explicitly in the handoff summary.
7. Keep company/tenant binding fail-closed.
8. Prefer strengthening existing code paths over introducing parallel contracts unless the feature specifically requires a replacement path.

### Step 4: Verify aggressively
1. Run `cd erp-domain && mvn compile -q`.
2. Run the feature-specific targeted tests from `features.json`.
3. Run `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`.
4. If the feature description names an exact catching lane, run that lane command from `.factory/services.yaml` and capture the result.
5. Re-read every touched controller, DTO, migration, manifest, and error path in the diff looking for silent regressions.
6. Explicitly verify adjacent ERP-sensitive flows touched by the change (for example: login/refresh/logout, `/auth/me`, forgot/reset, admin user controls, admin settings authz, tenant binding).
7. If the local app is started and the feature needs runtime evidence, verify the changed flow with `curl` and capture the exact sequence.
8. If the change surfaced stale adjacent security or period-policy tests, update them before handoff or return a tracked issue against a pending feature.

### Step 5: Update shared knowledge
1. Update `.factory/library/frontend-handoff.md` for any touched auth/admin surface. If nothing changed, say so explicitly.
2. Update `.factory/library/frontend-v2.md` for any touched role-surface, blocker, or approval-flow change relevant to frontend-v2 consumers.
3. If you learned a new auth/security constraint or rollout caveat, update `.factory/library/auth-hardening.md`.
4. If runtime setup/verification changed, update `.factory/library/user-testing.md`.
5. If you removed obsolete override or boundary code, append a concise dated note to `.factory/library/remediation-log.md`.

If a higher-priority instruction says to avoid doc updates for the packet and the feature preserves existing auth/admin request-response shapes, you may skip the shared-knowledge doc edits; call that out explicitly in the handoff instead of inventing no-op documentation churn.
If `mission.md`, `AGENTS.md`, or the feature description explicitly requires remediation-log, frontend-v2, frontend-handoff, or README updates for the packet, treat that as an explicit user request and make the scoped documentation updates.

### Step 6: Produce a strict handoff
Your handoff must make shortcuts visible. Include:
- exact endpoints / services / migrations touched
- whether API shapes changed (`yes` or `no`)
- the regression flows you rechecked explicitly
- exact commands run and what passed/failed
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
