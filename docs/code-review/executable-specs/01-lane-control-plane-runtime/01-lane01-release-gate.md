# Lane 01 release gate

- packet: [`01-lane01-release-review.md`](./01-lane01-release-review.md)
- base branch: `Factory-droid`
- packet code commits: `ddf28d7b` (`fix(company): align lane 01 runtime timestamps with OpenAPI`), `13cfee89` (`fix(company): align suspended lifecycle auth reads with runtime truth`)
- implementer: `Anas Ibn Anwar`
- reviewer: `Factory-droid orchestrator base-branch reviewer`
- QA owner: `Factory-droid Lane 01 regression pack owner`
- release approver: `Factory-droid release gate approver`
- status: review-ready, return to the orchestrator before Lane 02 consumes this lane

## Must-pass checks

- exit gate from `01-lane01-release-review.md` is satisfied by the attached prove-first bundle, the targeted Lane 01 regression proof, and the existing green `gate-fast` rerun on `Factory-droid`
- changed-files proof for the Lane 01 code commits is limited to:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/dto/TenantRuntimeMetricsDto.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/shared/dto/ApiResponse.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/CompanyControllerIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/PortalInsightsControllerIT.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantRuntimeEnforcementTest.java`
  - governance/handoff docs only for this packet review
- `openapi.json` stays unchanged in this governance packet because the code was brought back into parity with the already-published contract
- frontend/operator parity is explicitly recorded in:
  - `.factory/library/frontend-handoff.md`
  - `docs/frontend-update-v2/README.md`
  - `docs/frontend-update-v2/global-security-settings-authorization.md`
  - `docs/frontend-update-v2/lane01-align-lifecycle-and-runtime-truth.md`
  - `docs/frontend-update-v2/lane01-release-gate-and-handoff.md`
- Lane 02 remains blocked by process, not missing evidence: workers must return to the orchestrator for base-branch review instead of merging or pushing

## Commands / evidence

1. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
   - observed in this governance session before packet edits: `BUILD SUCCESS`, `Tests run: 395, Failures: 0, Errors: 0, Skipped: 0`.
2. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AdminSettingsControllerTenantRuntimeContractTest,SystemSettingsServiceCorsTest,CompanyControllerIT,SuperAdminControllerIT,CompanyLifecycleStateConverterTest,TenantLifecycleServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest'`
   - observed in this governance session after packet edits: `BUILD SUCCESS`, `Tests run: 147, Failures: 0, Errors: 0, Skipped: 0`.
3. `bash /home/realnigga/Desktop/Mission-control/ci/lint-knowledgebase.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-architecture.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-orchestrator-layer.sh && python3 /home/realnigga/Desktop/Mission-control/scripts/check_flaky_tags.py --tests-root /home/realnigga/Desktop/Mission-control/erp-domain/src/test/java --gate gate-fast && bash /home/realnigga/Desktop/Mission-control/scripts/guard_openapi_contract_drift.sh`
   - observed in this governance session after packet edits: all validators exited successfully; knowledgebase, architecture, enterprise-policy, orchestrator-layer, flaky-tag, and OpenAPI-drift guards reported compatibility-mode warnings only for optional legacy docs.
4. `git show --stat --name-only --format=fuller ddf28d7b 13cfee89`
   - observed in this governance session: the two Lane 01 code commits stay narrow to the timestamp/OpenAPI parity slice and the suspended-read/runtime-truth slice.
5. `git status --short --branch && git diff --stat && git diff -- openapi.json .factory/library/frontend-handoff.md docs/frontend-update-v2 docs/code-review/executable-specs .factory/library/packet-governance.md`
   - used to verify the packet/release-gate/front-end parity evidence assembled by this governance packet; `openapi.json` remained unchanged and the working tree showed only the expected governance/handoff artifacts.
6. `status1=$(curl -s -o /tmp/factory-health.out -w '%{http_code}' http://localhost:9090/actuator/health || true) && status2=$(curl -s -o /tmp/factory-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true) && echo "$status1;$status2" && { [ "$status2" = "200" ] || [ "$status2" = "401" ] || [ "$status2" = "403" ]; }`
   - observed in this governance session: `000;000` with a non-zero exit because the approved compose-backed runtime was not running locally.

## Data and migration controls

- no schema or migration files changed in the Lane 01 code commits or in this governance packet
- no forward-only data change exists here; rollback is code-only
- rollback order: revert `13cfee89` first if suspended-read semantics regress, then revert `ddf28d7b` if timestamp/date-time parity causes issues
- rollback rehearsal evidence: rerun the targeted Lane 01 proof pack after reverting the implicated commit(s)
- expected RTO: under 1 hour
- expected RPO: none

## Runtime evidence

- approved compose-backed runtime probe in this session returned `000;000`, confirming the runtime was unavailable locally while the release gate was assembled
- interpretation: runtime evidence is degraded/unavailable for this governance packet and is recorded only as a confidence note; targeted tests and packet proof remain the actual correctness evidence
- if `9090` reports `503 DOWN` while `8081` auth routes still return an expected `401` / `403` in a follow-up review, treat the route probe as the primary runtime evidence and record the degraded-health note explicitly

## Frontend and operator controls

- no new request-body or success-response shape change is introduced by the release-governance packet itself
- the surviving frontend/operator consequences are already explicit: tenant-admin global-settings mutation stays disabled, tenant-runtime metrics remain the read surface, and suspended tenants keep the published protected-read contract while writes still fail closed
- no route removal, wrapper cutoff, or frontend cutover is required before base-branch review
- consumer sign-off required before Lane 02 or merge recommendation: orchestrator base-branch review plus backend/security review

## No-go check

- no-go condition is procedural, not evidentiary: Lane 02 and any merge recommendation stay blocked until the orchestrator performs the required base-branch review on this packet
