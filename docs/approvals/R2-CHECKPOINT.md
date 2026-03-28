# R2 Checkpoint

## Scope
- Feature: `ERP-48 canonical hard-cut deployability packet`
- Branch: `packet/erp-48-canonical-hardcut-d2df29ee`
- Baseline head: `d2df29eeb58c6d74b932a7be2d76b90eb310b419`
- Review candidate:
  - hard-cut auth identity onto `GET /api/v1/auth/me` and retire `/api/v1/auth/profile`
  - hard-cut accounting journal and reversal surfaces onto one canonical public route each
  - hard-cut tenant-admin approval/export ownership away from superadmin
  - hard-cut product/account default validation, GST health, and inventory-accounting default-off behavior
  - retire MCP sidecar and dead task scripts from CI/runtime surface
  - add `V173__company_lifecycle_constraint_hard_cut.sql` and release-matrix/guard fixes needed for deployable Flyway v2 proof
  - publish the six-portal frontend contract plus the API handoff pack
- Why this is R2: the packet changes live auth, tenant control-plane, accounting, inventory/manufacturing, release-guard, and migration surfaces. A wrong cut here can break tenant isolation, accounting posting, or rollout safety even when local tests are green.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
  - `erp-domain/src/main/resources/db/migration_v2/V173__company_lifecycle_constraint_hard_cut.sql`
  - `scripts/gate_release.sh`
  - `scripts/verify_local.sh`
  - `scripts/release_migration_matrix.sh`
- Contract surfaces affected:
  - tenant-scoped identity and must-change-password corridor
  - superadmin isolation from tenant-admin approval/export flows
  - manual journal and reversal public API contract
  - finished-good/default-account/GST fail-closed behavior
  - release-grade Flyway v2 migration proof and rollback rehearsal
- Failure mode if wrong:
  - tenant users could authenticate with the wrong company identity surface
  - superadmin could regain tenant-admin approval/export authority
  - accounting could double-post or reverse through the wrong public surface
  - deploy-time migration matrix could pass local gates but fail on fresh or upgrade paths
  - frontend could keep implementing against retired routes or ambiguous portal ownership

## Approval Authority
- Mode: human
- Approver: `ERP-48 owner`
- Canary owner: `ERP-48 owner`
- Approval status: `pending human deploy approval; local release proof green`
- Basis: this packet is a destructive hard-cut across auth, control-plane, accounting, and migration surfaces and therefore stays inside the R2 approval lane.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet combines runtime hard-cuts and a new `migration_v2` file; automatic approval is not sufficient for a safe deploy decision.

## Rollback Owner
- Owner: `ERP-48 owner`
- Rollback method:
  - before merge: abandon the packet branch/worktree and do not promote the artifact
  - after merge but before deploy: revert the packet commits together; do not partially keep the API/doc changes while reverting the runtime or migration changes
  - after deploy: keep the ERP-48-compatible backend live unless the database is first restored to a pre-`V173` snapshot/PITR state
  - do not hand-edit the database back toward mixed legacy/current lifecycle constraint state; treat `V173` as a coordinated app-and-schema cut
- Rollback trigger:
  - auth identity or tenant isolation deviates from `companyCode`-scoped `/api/v1/auth/me`
  - approval/export actions become visible to superadmin again
  - manual journal/reversal paths fail canonical route expectations
  - fresh-path or upgrade-path Flyway v2 rollout diverges from the verified release matrix
  - canary smoke shows O2C, P2P, or accounting/export regression after deploy

## Expiry
- Valid until: `2026-04-04`
- Re-evaluate if: scope widens beyond this packet, another `migration_v2` change is added, or canary ownership/rollback ownership changes.

## Verification Evidence
- Commands run:
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_fast.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_core.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_reconciliation.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_release.sh`
- Result summary:
  - `gate_fast` passed
  - `gate_core` passed
  - `gate_reconciliation` passed with `Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`
  - `gate_release` passed with canonical base verification against `origin/main` at `d2df29eeb58c6d74b932a7be2d76b90eb310b419`
  - release migration matrix passed on fresh path, upgrade seed path, and upgrade-to-head path
- Artifacts/links:
  - repo checkout: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee`
  - release artifact pack: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/artifacts/gate-release`
  - worker execution log: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/erp-48-parallel-worker-worklog.md`
