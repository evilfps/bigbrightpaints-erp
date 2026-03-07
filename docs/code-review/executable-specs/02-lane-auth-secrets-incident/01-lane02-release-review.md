# Lane 02 auth/secrets/incident release review

This packet closes the Lane 02 governance gap after the auth/secrets implementation slices landed locally on `Factory-droid`. It ties the Lane 02 packet back to the sealed preflight and Lane 01 packets, records rollback and consumer-handoff evidence, and stops short of merge handling so the orchestrator can perform the required base-branch review.

## 1. Header
- lane: `lane-02-auth-secrets-incident`
- slice name: `lane02-release-gate-and-cross-area-audit`
- finding IDs: `VAL-AUTH-007`, supporting `VAL-CROSS-001`, `VAL-CROSS-002`, `VAL-AUTH-001`, `VAL-AUTH-002`, `VAL-AUTH-003`, `VAL-AUTH-004`, `VAL-AUTH-005`, `VAL-AUTH-006`
- implementer: `Anas Ibn Anwar` (`1159ef54 fix(auth): harden stored reset and refresh secrets`, `7deb909d fix(auth): reject stale sessions after security events`, `aae06aed fix(auth): retire stale reset alias and harden delivery guarantees`, `51dfda9f fix(auth): enforce must-change-password corridor`, `e51926d3 fix(company): stop exposing onboarding temp credentials`, `2baa1175 fix(auth): verify token digest rollout compatibility`, `2941da83 fix(auth): lock recovery contract parity`)
- reviewer: `Factory-droid orchestrator base-branch reviewer`
- QA owner: `Factory-droid Lane 02 auth regression pack owner`
- release approver: `Factory-droid release gate approver`
- branch: `Factory-droid`
- target environment: local `MIGRATION_SET=v2` validation plus the approved compose-backed `prod,flyway-v2` runtime on `8081/9090` when available

## 2. Lane Start Gate
- the preflight and merge-gate chain is sealed in [`../00-preflight-review-merged-auth-company-admin-hardening.md`](../00-preflight-review-merged-auth-company-admin-hardening.md), [`../00-current-auth-merge-gate.md`](../00-current-auth-merge-gate.md), and [`../00-current-auth-merge-gate-release-gate.md`](../00-current-auth-merge-gate-release-gate.md); Lane 02 therefore starts from a `Factory-droid`-reviewed auth baseline instead of reopening `AUTH-09`, token-revocation precision, or the already-closed `ADMIN-14` guardrail.
- the prerequisite Lane 01 packet now has proof, rollback notes, and release-gate evidence recorded in [`../01-lane-control-plane-runtime/01-lane01-release-review.md`](../01-lane-control-plane-runtime/01-lane01-release-review.md) and [`../01-lane-control-plane-runtime/01-lane01-release-gate.md`](../01-lane-control-plane-runtime/01-lane01-release-gate.md); Lane 02 may reference Lane 01 as review-ready input, but not as already-merged truth.
- the active lane still matches [`EXEC-SPEC.md`](./EXEC-SPEC.md): the packet stays on auth/secrets/onboarding/incident-response boundaries and does not reopen tenant-lifecycle/global-settings design or accounting scope.
- workers must stop at release-gate assembly and cross-area audit; final base-branch review, branch refresh, PR handling, and merge recommendation belong to the orchestrator.

## 3. Why This Slice Exists
- the Lane 02 implementation commits already prove the auth/secrets fixes, but the lane still lacked one packet-shaped release review that names the exact `Factory-droid` lineage, rollback order, and consumer handoff before anyone recommends it for merge.
- Lane 02 touches frontend/support-relevant auth and admin surfaces (onboarding credential delivery, forced-password-change corridor, token storage compatibility, session revocation behavior, and recovery routes), so the release packet must explicitly link the earlier handoff notes instead of expecting reviewers to reconstruct them from scattered commits.
- the final cross-area audit is also the control that prevents Lane 02 from silently consuming Lane 01 without proof: Lane 01 now has the required release-gate evidence, but merge handling still must return to the orchestrator.

## 4. Scope
- record the merged Lane 02 auth foundations already present on `Factory-droid`: digest-backed reset/refresh storage (`1159ef54`), stale-session revocation (`7deb909d`), recovery-alias retirement and delivery guarantees (`aae06aed`), and the must-change-password corridor (`51dfda9f`)
- record the local Lane 02 follow-up commits stacked after Lane 01: onboarding temp-credential redaction (`e51926d3`), token-digest compatibility proof (`2baa1175`), and recovery-contract parity lock (`2941da83`)
- attach packet proof that `openapi.json`, `.factory/library/frontend-handoff.md`, and `docs/frontend-update-v2/**` reflect the surviving auth/admin contract, including explicit no-op release-governance notes
- prove that Lane 02 only starts consuming Lane 01 after Lane 01 has its own proof/rollback/release-gate bundle
- explicitly leave later non-Lane-02 packets out of scope: the PR #90 review-follow-up commits (`a00b0f83`, `adf29360`) and the later remote-review governance-only packet (`3eae3d81` plus validation syntheses) are separate packet-chain work and must not be folded into any Lane 02 merge recommendation
- do not widen into new MFA persistence design, tenant lifecycle/governance redesign, or post-release cleanup beyond the documented compatibility window

## 5. Caller Map
- `TenantOnboardingService` and `TenantOnboardingResponse`
- `PasswordResetService`, `RefreshTokenService`, and the digest-storage/backfill helpers behind reset/refresh persistence
- `AuthService`, `TokenBlacklistService`, and the revocation path reached from login/refresh/logout/password-change/reset/disablement/lockout flows
- `AuthController` recovery endpoints, including the deprecated `POST /api/v1/auth/password/forgot/superadmin` compatibility alias and the supported `POST /api/v1/auth/password/forgot` canonical path
- company support recovery via `POST /api/v1/companies/{id}/support/admin-password-reset`
- frontend/operator review surfaces: `.factory/library/frontend-handoff.md`, `docs/frontend-update-v2/README.md`, `docs/frontend-update-v2/lane02-temp-credential-and-corridor-hardening.md`, `docs/frontend-update-v2/lane02-recovery-contract-parity.md`, and `docs/frontend-update-v2/lane02-release-gate-and-cross-area-audit.md`

## 6. Invariant Pack
- onboarding responses must not reveal a usable temporary password in normal API payloads
- `mustChangePassword=true` bearer sessions stay confined to the documented password-change corridor until the password is changed successfully
- refresh-token and password-reset persistence stay digest-backed with schema work limited to `db/migration_v2`; legacy `db/migration/**` remains untouched
- stale sessions issued before logout, password change, password reset, disablement, or lockout remain rejected; any later same-millisecond fresh-session usability fix is tracked in the separate PR #90 review-follow-up packet and is not counted as Lane 02 scope
- the deprecated super-admin forgot-password alias remains an explicit `410 Gone` migration pointer while the canonical public forgot-password path and the root-only support reset path stay the supported live recovery surfaces
- Lane 02 may cite Lane 01 as a prerequisite packet only because Lane 01 now has proof, rollback notes, and release-gate evidence attached; degraded runtime evidence is recorded as a note and never as a waiver

## 7. Implemented Slice
1. The preflight and merge-gate packets established the `Factory-droid` baseline and removed the narrow `AUTH-09` / token-revocation precision blockers before broader Lane 02 work proceeded.
2. Lane 02's auth foundations on `Factory-droid` then hardened reset/refresh secret storage (`1159ef54`), stale-session revocation (`7deb909d`), recovery-alias and delivery guarantees (`aae06aed`), and the must-change-password corridor (`51dfda9f`).
3. The local Lane 02 follow-up commits removed `adminTemporaryPassword` from tenant onboarding responses (`e51926d3`), tightened digest-rollout compatibility proof (`2baa1175`), and locked the recovery contract against OpenAPI/frontend drift (`2941da83`).
4. This governance packet adds the final release review, rollback note, and explicit cross-packet audit so reviewers can see that preflight, Lane 01, and Lane 02 all remain attributable to `Factory-droid` and that Lane 02 is not consuming Lane 01 before its release gate exists.
5. The packet also records the current stop boundary: later review-follow-up and remote-review governance commits exist on the local stack but remain separate packets that require orchestrator branch handling before any final merge recommendation.

## 8. Proof Pack
- targeted Lane 02 regression pack:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AuthControllerIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,AuthTenantAuthorityIT,PasswordResetServiceTest,PasswordServiceTest,RefreshTokenServiceTest,TokenBlacklistServiceTest,AdminUserSecurityIT,AdminUserServiceTest,TenantAdminProvisioningServiceTest,OpenApiSnapshotIT'`
- full regression confidence:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- repo guards / governance lint:
  - `bash /home/realnigga/Desktop/Mission-control/ci/lint-knowledgebase.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-architecture.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-orchestrator-layer.sh && python3 /home/realnigga/Desktop/Mission-control/scripts/check_flaky_tags.py --tests-root /home/realnigga/Desktop/Mission-control/erp-domain/src/test/java --gate gate-fast && bash /home/realnigga/Desktop/Mission-control/scripts/guard_openapi_contract_drift.sh`
- packet-lineage / packet-scope evidence:
  - `git show --stat --name-only --format=fuller 51dfda9f 1159ef54 7deb909d aae06aed e51926d3 2baa1175 2941da83`
  - `git show --name-only --format='' e51926d3 2baa1175 2941da83 | sort -u`
  - `git status --short --branch`
  - `git diff --stat`
  - `git merge-base HEAD origin/Factory-droid`
  - `git diff --name-only origin/Factory-droid...HEAD`
- runtime evidence note:
  - `status1=$(curl -s -o /tmp/factory-health.out -w '%{http_code}' http://localhost:9090/actuator/health || true) && status2=$(curl -s -o /tmp/factory-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true) && echo "$status1;$status2" && { [ "$status2" = "200" ] || [ "$status2" = "401" ] || [ "$status2" = "403" ]; }`
- release-gate artifact:
  - [`01-lane02-release-gate.md`](./01-lane02-release-gate.md)

## 9. Validation-First Evidence
- not a new validation-first packet; the lane inherits its packet-entry proof from the sealed preflight / merge-gate review and the completed Lane 01 release gate
- no unresolved validation-first drift is being used as implementation truth here: the earlier merge-gate blockers were closed before Lane 02, and the Lane 01 prerequisite now carries explicit proof / rollback evidence instead of an implied dependency
- reviewer sign-off for final promotion is still deferred to orchestrator base-branch review because workers do not push or merge

## 10. Rollback Pack
- revert the newest Lane 02 follow-ups first when the issue is localized: `2941da83` for recovery-contract parity drift, then `e51926d3` if onboarding consumers cannot absorb temp-credential redaction without operational fallback; `2baa1175` is test-only compatibility proof and can be reverted independently if it proves incorrect
- for older Lane 02 auth foundations, revert in reverse dependency order only after targeted proof shows the broader rollback is needed: `51dfda9f` (corridor), `aae06aed` (recovery alias / delivery guarantee), `7deb909d` (session revocation), and `1159ef54` (digest-backed storage) last
- `V158__auth_token_digest_storage.sql` is forward-only and remains in place during rollback; rollback means reverting the write/read-path code while leaving the schema dormant, not hand-editing production token rows
- keep the published recovery migration pointer and support reset surface stable during rollback; only the specific behavior under regression should move
- rollback trigger threshold: any regression that reintroduces onboarding credential leakage, breaks digest lookup compatibility, leaves stale sessions authenticated after a security event, or makes the published recovery / support-reset contract diverge again
- rollback rehearsal evidence: rerun the targeted Lane 02 regression pack after reverting the implicated commit(s) before re-promoting the lane
- expected RTO: under 2 hours for code-only reverts plus targeted auth regression reruns
- expected RPO: none

## 11. Stop Rule
- split immediately if follow-up work starts redesigning MFA recovery-code persistence, tenant lifecycle/global-settings control-plane semantics, or the later PR #90 / remote-review follow-up packets instead of staying on Lane 02 release-governance evidence

## 12. Exit Gate
- the Lane 02 auth regression pack is green and `gate-fast` remains green on the current branch
- the surviving consumer-facing auth/admin contract is explicitly recorded in `.factory/library/frontend-handoff.md` and `docs/frontend-update-v2/**`, including the release-review no-op note in `lane02-release-gate-and-cross-area-audit.md`
- the packet links back to the sealed preflight, merge-gate, and Lane 01 packets so reviewers can see `Factory-droid` lineage across the chain without treating those earlier packets as implicit or undocumented input
- Lane 02 only cites Lane 01 as review-ready prerequisite evidence because Lane 01 now has proof, rollback notes, and release-gate evidence; Lane 02 still must not treat Lane 01 as already-merged truth before orchestrator review
- final merge recommendation remains outside worker scope because `origin/Factory-droid...HEAD` still includes later review-follow-up / remote-review packets that the orchestrator must keep separate when refreshing branches or reviewing the final stack

## 13. Handoff
- next lane: orchestrator base-branch review and final packet-chain handling; if the stack is refreshed for remote review, keep Lane 02 limited to the auth/secrets packet commits and this governance packet only
- remaining transitional paths: digest-storage legacy-row compatibility remains active until a later cleanup packet retires it, and the deprecated super-admin forgot-password alias remains a `410 Gone` migration pointer rather than a restored live route
- operator or frontend note: no additional request/response contract change was introduced by the release packet itself; the actionable Lane 02 consumer notes remain onboarding temp-credential redaction plus the retired recovery alias migration pointer, and those are already captured in the frontend handoff trackers
- compatibility window and wrapper duration: keep legacy-token read compatibility and the deprecated recovery alias pointer in place until orchestrator-approved cleanup / removal work is scheduled separately
- consumer sign-off needed before cutover: orchestrator base-branch review plus backend/security review, with frontend/support acknowledgement of the onboarding and recovery notes already tracked in `docs/frontend-update-v2/**`
- deprecation or removal cutoff: none is changed by this governance packet
