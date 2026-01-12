# Task 02 — Endpoint + Portal Matrix (Surface Area, Roles/Permissions, Deprecations)

## Purpose
**Accountant-level:** ensure every financially significant operation is reachable only from the correct portal/role, and that no legacy/alias endpoint can silently bypass intended controls.

**System-level:** create and maintain a single authoritative mapping of **endpoint → portal → auth (role/permission) → notes/deprecation**, and use it to drive tests and safe cleanup.

## Scope guard (explicitly NOT allowed)
- Do not add new business workflows.
- Do not add a new portal or UI.
- Do not delete endpoints unless proven unused and a canonical replacement exists (document the proof).
- No broad controller refactors; keep changes localized and pattern-consistent.

## Milestones

### M1 — Reconcile the API inventory (OpenAPI vs code)
Deliverables:
- Confirm the endpoint inventory is complete and stable:
  - `erp-domain/docs/endpoint_inventory.tsv` (source-of-truth from code scan)
  - `openapi.json` + `/v3/api-docs` output (runtime contract)
- Record any drift:
  - endpoints present in code but missing in OpenAPI
  - endpoints present in OpenAPI but no longer present in code
  - duplicate/alias endpoints

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test`

Evidence to capture:
- A short drift report (paths + controller anchors) appended to `docs/ops_and_debug/EVIDENCE.md`.

Stop conditions + smallest decision needed:
- If OpenAPI snapshot generation dirties the worktree unexpectedly: smallest decision is whether to accept the drift (update `openapi.json`) or treat it as a regression requiring investigation before proceeding.

### M2 — Build the portal matrix (endpoint → portal → auth)
Deliverables:
- Create/update `docs/API_PORTAL_MATRIX.md` with:
  - portal definition (Admin / Accounting / Sales / Manufacturing / Dealer)
  - mapping for **every** endpoint in `erp-domain/docs/endpoint_inventory.tsv`
  - explicit auth:
    - `permitAll` (e.g., login/health) vs
    - `authenticated()` only vs
    - `@PreAuthorize(...)` roles/authorities
  - notes for financial significance and required evidence chain (link to Task 03 contracts)
- Flag any endpoint with **no method-level guard** as a “security review required” item (default stance: fail‑closed).

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (security smoke): `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AdminUserSecurityIT test`

Evidence to capture:
- The completed `docs/API_PORTAL_MATRIX.md`.
- A list of endpoints with “authenticated-only” access that should likely be restricted (with safest recommendation).

Stop conditions + smallest decision needed:
- If the correct portal for an endpoint is ambiguous (shared use): choose the safest mapping of “Admin + least privilege”, and document the reasoning in `docs/API_PORTAL_MATRIX.md`.

### M3 — Deprecation + cleanup mapping (no deletions yet)
Deliverables:
- For every alias/deprecated endpoint:
  - define canonical replacement endpoint
  - define removal criteria (“proof of unused”)
  - define tests that must pass before removal (OpenAPI snapshot + module suite)
- Create a “deprecated endpoints ledger” section inside `docs/API_PORTAL_MATRIX.md`:
  - endpoint
  - status: active / deprecated / candidate-remove
  - replacement
  - proof required

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test`

Evidence to capture:
- A “candidate removals” list with the exact proof plan (logs/grep/usage traces).

Stop conditions + smallest decision needed:
- If an alias endpoint is used by unknown callers: smallest decision is to mark it deprecated-only (documented) and postpone deletion until production traffic confirms replacement adoption.

