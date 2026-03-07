# Lane 01 control-plane runtime release review

This packet closes the Lane 01 governance gap after the prove-first bundle and the two implementation slices landed on `Factory-droid`. Lane 02 must stay blocked on this lane until the release gate and handoff evidence below are accepted during orchestrator base-branch review.

## 1. Header
- lane: `lane-01-control-plane-runtime`
- slice name: `lane01-release-gate-and-handoff`
- finding IDs: `VAL-CTRL-005`, supporting `VAL-CTRL-001`, `VAL-CTRL-002`, `VAL-CTRL-003`, `VAL-CTRL-004`
- implementer: `Anas Ibn Anwar` (`ddf28d7b fix(company): align lane 01 runtime timestamps with OpenAPI`, `13cfee89 fix(company): align suspended lifecycle auth reads with runtime truth`)
- reviewer: `Factory-droid orchestrator base-branch reviewer`
- QA owner: `Factory-droid Lane 01 regression pack owner`
- release approver: `Factory-droid release gate approver`
- branch: `Factory-droid`
- target environment: local `MIGRATION_SET=v2` validation plus approved compose-backed `prod,flyway-v2` runtime on `8081/9090` when available

## 2. Lane Start Gate
- packet 0 is sealed in [`00-ten-09-validation-first-bundle.md`](./00-ten-09-validation-first-bundle.md) and classifies `TEN-09` as a narrow backend parity defect, not a missing-surface backlog item
- the Lane 01 implementation commits on `Factory-droid` are limited to `ddf28d7b` and `13cfee89`; the earlier runtime-policy cache invalidation guardrail remains tracked separately and is not reopened here
- the active lane still matches [`EXEC-SPEC.md`](./EXEC-SPEC.md): no auth-secret migration, accounting-boundary redesign, or Lane 02 work was mixed into this packet
- workers must stop at release-gate assembly and return to the orchestrator instead of merging or pushing

## 3. Why This Slice Exists
- Lane 01 already has implementation proof, but it still lacked the filled packet template, rollback note, and operator/frontend handoff needed before anyone can treat the control-plane contract as review-ready
- the lane fixes touched shared operator/frontend surfaces (`/api/v1/admin/settings`, `/api/v1/admin/tenant-runtime/metrics`, protected auth reads for suspended tenants), so the surviving contract and no-op frontend posture must be made explicit
- closing this governance slice is the required boundary before Lane 02 is allowed to consume Lane 01 as stable input

## 4. Scope
- record the exact Lane 01 code packet owned by commits `ddf28d7b` and `13cfee89`
- attach proof that runtime/OpenAPI timestamp parity and suspended-tenant read semantics are green on the current branch
- attach rollback notes for those two code commits without reopening earlier merge-gate or later Lane 02 scope
- publish operator/frontend handoff evidence in `.factory/library/frontend-handoff.md` and `docs/frontend-update-v2/**`
- do not widen into new company/admin endpoint design, tenant metrics redesign, auth-secret hardening, or schema work

## 5. Caller Map
- `AdminSettingsController.tenantRuntimeMetrics()`
- `PortalInsightsController.{dashboard,operations,workforce}()`
- `ApiResponse` wrapper serialization and `TenantRuntimeMetricsDto.policyUpdatedAt`
- `CompanyContextFilter` protected auth request enforcement for suspended / deactivated tenants
- protected auth read surfaces such as `GET /api/v1/auth/me`
- frontend/operator review surfaces: `.factory/library/frontend-handoff.md`, `docs/frontend-update-v2/README.md`, `docs/frontend-update-v2/global-security-settings-authorization.md`, `docs/frontend-update-v2/lane01-align-lifecycle-and-runtime-truth.md`, and `docs/frontend-update-v2/lane01-release-gate-and-handoff.md`

## 6. Invariant Pack
- tenant admins must not regain platform-wide mutation through `PUT /api/v1/admin/settings`
- tenant-admin/runtime read surfaces keep the published wrapper and date-time semantics aligned with `openapi.json`
- suspended (`HOLD` / stored `SUSPENDED`) tenants keep read-only access on protected auth `GET` surfaces while mutating requests still fail closed
- deactivated (`BLOCKED`) tenants still lose protected access entirely
- the authoritative runtime-policy write path and immediate same-node enforcement remain unchanged from the already-proved Lane 01 guardrail packet
- no new routes, aliases, schema changes, or frontend payload migrations are introduced by this governance closure

## 7. Implemented Slice
1. Packet 0 (`TEN-09`) proved the surviving tenant-runtime and portal routes exist, documented the timestamp serialization defect, and blocked wider lane work until parity was fixed.
2. Commit `ddf28d7b` aligned `ApiResponse.timestamp` and `TenantRuntimeMetricsDto.policyUpdatedAt` runtime serialization with the already-published OpenAPI contract and refreshed the targeted tests that cover those surfaces.
3. Commit `13cfee89` aligned suspended-tenant protected auth reads with the published read-only contract while keeping writes fail-closed and deactivated tenants blocked.
4. This governance packet adds the final release review, rollback note, and explicit operator/frontend no-new-shape-change handoff so Lane 02 can remain blocked on orchestrator review instead of guessing at Lane 01 state.

## 8. Proof Pack
- full regression confidence:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- targeted Lane 01 proof pack:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AdminSettingsControllerTenantRuntimeContractTest,SystemSettingsServiceCorsTest,CompanyControllerIT,SuperAdminControllerIT,CompanyLifecycleStateConverterTest,TenantLifecycleServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest'`
- packet/handoff verification:
  - `git status --short --branch`
  - `git diff --stat`
  - `git diff -- openapi.json .factory/library/frontend-handoff.md docs/frontend-update-v2 docs/code-review/executable-specs .factory/library/packet-governance.md`
  - `git show --stat --name-only --format=fuller ddf28d7b 13cfee89`
- release-gate artifact:
  - [`01-lane01-release-gate.md`](./01-lane01-release-gate.md)

## 9. Validation-First Evidence
- `TEN-09` remains linked to [`00-ten-09-validation-first-bundle.md`](./00-ten-09-validation-first-bundle.md)
- validation-first verdict: `confirmed backend defect`, now closed by the timestamp-parity implementation in `ddf28d7b`
- reviewer sign-off for the validation-first verdict remains the recorded packet-governance sign-off in the prove-first bundle; this packet only records that the follow-up landed and is now gated for base-branch review

## 10. Rollback Pack
- revert `13cfee89` first if suspended-tenant protected-read behavior regresses or reopens write access unexpectedly
- revert `ddf28d7b` second if timestamp/date-time parity introduces unexpected client/runtime fallout; no schema or data rollback is required
- keep the canonical route family intact during rollback: `GET /api/v1/admin/tenant-runtime/metrics` remains the surviving tenant-admin read surface, `PUT /api/v1/admin/settings` and `PUT /api/v1/admin/tenant-runtime/policy` stay privileged mutation paths
- rollback trigger threshold: any regression that reintroduces numeric timestamp drift, allows suspended tenants to mutate protected surfaces, or blocks the documented suspended-tenant read-only auth corridor
- rollback rehearsal evidence: rerun the targeted Lane 01 proof pack after reverting the implicated commit(s) before re-promoting the lane
- expected RTO: under 1 hour for code-only revert plus targeted proof rerun
- expected RPO: none

## 11. Stop Rule
- split immediately if any follow-up starts redesigning tenant metrics dashboards, lifecycle vocabulary beyond the published contract, auth-secret storage, or other Lane 02 scope instead of staying on Lane 01 release-governance evidence

## 12. Exit Gate
- `TEN-09` proof-first bundle is attached and no longer unresolved drift
- runtime/OpenAPI timestamp parity for the surviving tenant-runtime and portal surfaces is proven on the current branch
- suspended-tenant protected reads match the published read-only contract while write denial stays fail-closed
- rollback notes, named release roles, and operator/frontend handoff references are present in this packet and [`01-lane01-release-gate.md`](./01-lane01-release-gate.md)
- Lane 02 is still explicitly blocked on orchestrator base-branch review and must not consume Lane 01 as merged truth yet

## 13. Handoff
- next lane: orchestrator base-branch review, then Lane 02 auth/secrets/incident packets only after Lane 01 is accepted as review-ready
- remaining transitional paths: no additional Lane 01 contract migration is required beyond the already-tracked tenant-admin global-settings RBAC follow-up; later auth/session/storage work stays in Lane 02
- operator or frontend note: the authoritative operator story is now explicit — tenant admins may read `GET /api/v1/admin/tenant-runtime/metrics` but may not mutate global settings, and suspended tenants keep protected auth reads while writes remain denied. No new request-body or success-response shape change was introduced by this governance packet beyond the previously tracked Lane 01 contract clarifications.
- compatibility window and wrapper duration: existing canonical control-plane routes remain the supported surfaces; no extra wrapper or deprecation window is opened by this packet
- consumer sign-off needed before cutover: orchestrator base-branch review plus backend/security review before Lane 02 or any merge recommendation treats Lane 01 as stable
- deprecation or removal cutoff: none in this packet
