# Lane 02 release gate

- packet: [`01-lane02-release-review.md`](./01-lane02-release-review.md)
- base branch: `Factory-droid`
- packet code commits: `1159ef54` (`fix(auth): harden stored reset and refresh secrets`), `7deb909d` (`fix(auth): reject stale sessions after security events`), `aae06aed` (`fix(auth): retire stale reset alias and harden delivery guarantees`), `51dfda9f` (`fix(auth): enforce must-change-password corridor`), `e51926d3` (`fix(company): stop exposing onboarding temp credentials`), `2baa1175` (`fix(auth): verify token digest rollout compatibility`), `2941da83` (`fix(auth): lock recovery contract parity`)
- implementer: `Anas Ibn Anwar`
- reviewer: `Factory-droid orchestrator base-branch reviewer`
- QA owner: `Factory-droid Lane 02 auth regression pack owner`
- release approver: `Factory-droid release gate approver`
- status: review-ready locally, return to the orchestrator before any final merge recommendation

## Must-pass checks

- the exit gate from [`01-lane02-release-review.md`](./01-lane02-release-review.md) is satisfied by the targeted Lane 02 regression proof, the green `gate-fast` rerun, and the packet-lineage audit commands below
- changed-files proof for the local Lane 02 follow-up commits after Lane 01 is limited to:
  - `.factory/library/auth-hardening.md`
  - `.factory/library/frontend-handoff.md`
  - `docs/frontend-update-v2/README.md`
  - `docs/frontend-update-v2/lane02-recovery-contract-parity.md`
  - `docs/frontend-update-v2/lane02-temp-credential-and-corridor-hardening.md`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/dto/TenantOnboardingResponse.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantOnboardingService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/OpenApiSnapshotIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthControllerIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/RefreshTokenServiceIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/TenantOnboardingControllerTest.java`
  - `openapi.json`
- the earlier merged Lane 02 auth foundations (`1159ef54`, `7deb909d`, `aae06aed`, `51dfda9f`) are already part of `Factory-droid` lineage and are re-attested here through `git show` rather than reopened as unrelated carry-over
- frontend/support parity is explicitly recorded in:
  - `.factory/library/frontend-handoff.md`
  - `docs/frontend-update-v2/README.md`
  - `docs/frontend-update-v2/lane02-temp-credential-and-corridor-hardening.md`
  - `docs/frontend-update-v2/lane02-recovery-contract-parity.md`
  - `docs/frontend-update-v2/lane02-release-gate-and-cross-area-audit.md`
- Lane 01 is now consumed only as release-gated prerequisite evidence: [`../01-lane-control-plane-runtime/01-lane01-release-review.md`](../01-lane-control-plane-runtime/01-lane01-release-review.md) and [`../01-lane-control-plane-runtime/01-lane01-release-gate.md`](../01-lane-control-plane-runtime/01-lane01-release-gate.md) supply the required proof / rollback / release-gate bundle before Lane 02 references that lane

## Commands / evidence

1. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AuthControllerIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,AuthTenantAuthorityIT,PasswordResetServiceTest,PasswordServiceTest,RefreshTokenServiceTest,TokenBlacklistServiceTest,AdminUserSecurityIT,AdminUserServiceTest,TenantAdminProvisioningServiceTest,OpenApiSnapshotIT'`
   - observed in this governance session after packet edits: `BUILD SUCCESS`; the targeted auth/admin/OpenAPI pack finished green with `Tests run: 138, Failures: 0, Errors: 0, Skipped: 0`.
2. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
   - observed in this governance session both as baseline and after packet edits: `BUILD SUCCESS`, `Tests run: 395, Failures: 0, Errors: 0, Skipped: 0`.
3. `bash /home/realnigga/Desktop/Mission-control/ci/lint-knowledgebase.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-architecture.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-orchestrator-layer.sh && python3 /home/realnigga/Desktop/Mission-control/scripts/check_flaky_tags.py --tests-root /home/realnigga/Desktop/Mission-control/erp-domain/src/test/java --gate gate-fast && bash /home/realnigga/Desktop/Mission-control/scripts/guard_openapi_contract_drift.sh`
   - observed in this governance session after packet edits: all validators exited successfully; knowledgebase / architecture / enterprise-policy / orchestrator-layer / flaky-tag / OpenAPI drift guards reported success with only compatibility-mode informational warnings for optional legacy docs.
4. `git show --stat --name-only --format=fuller 51dfda9f 1159ef54 7deb909d aae06aed e51926d3 2baa1175 2941da83`
   - observed in this governance session: the Lane 02 packet remains attributable to the expected auth/onboarding/OpenAPI/frontend-handoff surfaces and does not reopen tenant-lifecycle or accounting code paths.
5. `git show --name-only --format='' e51926d3 2baa1175 2941da83 | sort -u`
   - observed in this governance session: the local post-Lane-01 Lane 02 follow-up scope is limited to the temp-credential, digest-compatibility, recovery-parity, and handoff files listed in the changed-files proof above.
6. `git status --short --branch && git diff --stat && git merge-base HEAD origin/Factory-droid && git diff --name-only origin/Factory-droid...HEAD`
   - used to verify the current stack boundary. The common ancestor stayed `b93c91aa6d703d75708df66a5bb6f805f7e47154` (`origin/Factory-droid`), and the raw stack diff still includes later review-follow-up / remote-review packets, so final merge recommendation must return to the orchestrator instead of treating all local commits as one Lane 02 publication.
7. `status1=$(curl -s -o /tmp/factory-health.out -w '%{http_code}' http://localhost:9090/actuator/health || true) && status2=$(curl -s -o /tmp/factory-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true) && echo "$status1;$status2" && { [ "$status2" = "200" ] || [ "$status2" = "401" ] || [ "$status2" = "403" ]; }`
   - observed in this governance session: `000;000` with a non-zero exit because the approved compose-backed runtime was not running locally.

## Data and migration controls

- the only Lane 02 schema work remains `erp-domain/src/main/resources/db/migration_v2/V158__auth_token_digest_storage.sql`; legacy `db/migration/**` stayed untouched
- `V158__auth_token_digest_storage.sql` is forward-only, so rollback is application-code-first: preserve the schema, revert the read/write path if needed, and rely on compatibility reads instead of hand-editing token rows
- rollback order for localized follow-ups: `2941da83`, then `e51926d3`, with `2baa1175` independently revertible because it is proof-only
- rollback order for broader Lane 02 auth foundations, only if required by targeted proof: `51dfda9f`, `aae06aed`, `7deb909d`, then `1159ef54`
- rollback rehearsal evidence: rerun the targeted Lane 02 regression pack after reverting the implicated commit(s)
- expected RTO: under 2 hours
- expected RPO: none

## Runtime evidence

- management probe attempted during this governance session: `000`
- user-facing `/api/v1/auth/me` probe attempted during this governance session: `000`
- interpretation: the approved compose-backed runtime was unavailable locally when the release gate was assembled, so runtime evidence is degraded / unavailable in-session
- waiver rule applied correctly: the degraded runtime probe is recorded only as a confidence note and does **not** waive the targeted Lane 02 regression pack, `gate-fast`, or the packet-lineage audit

## Frontend and operator controls

- no new request-body or success-response shape change was introduced by the Lane 02 release-governance packet itself
- the only surviving Lane 02 consumer-visible changes remain the already-tracked ones: onboarding no longer reveals `adminTemporaryPassword`, and the deprecated super-admin forgot-password alias remains a `410 Gone` migration pointer to the canonical public forgot path plus the support reset path
- no new route removal or frontend cutover is introduced by this packet; the existing compatibility window remains the digest-storage legacy-row reads plus the retired-alias pointer
- consumer sign-off required before final merge recommendation: orchestrator base-branch review plus backend/security review, with frontend/support acknowledgement of the onboarding and recovery notes already captured in `docs/frontend-update-v2/**`

## No-go check

- no-go conditions are procedural, not evidentiary: the Lane 02 packet itself is locally release-ready, but the raw `origin/Factory-droid...HEAD` stack still contains later review-follow-up and remote-review packets that must stay separate from any Lane 02 publication
- therefore workers must return to the orchestrator for final base-branch review, branch refresh, or merge handling rather than improvising a merge recommendation from the full local stack
