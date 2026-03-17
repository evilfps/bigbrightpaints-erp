# R2 Checkpoint

## Scope
- Feature: `auth-merge-gate-hardening`
- Branch: `packet/lane02-auth-merge-gate-hardening`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/PasswordResetToken.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/PasswordResetTokenRepository.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java`, `erp-domain/src/main/resources/db/migration_v2/V162__password_reset_token_delivery_tracking.sql`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthPasswordResetPublicContractIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimePasswordResetServiceExecutableCoverageTest.java`, `docs/runbooks/migrations.md`, and this approval record.
- Why this is R2: this packet changes a live auth recovery surface, adds delivery-state tracking to password-reset tokens, and hardens the after-commit rollback so only a previously delivered token can be restored after dispatch or delivery-marker failures.

## Risk Trigger
- Triggered by auth-sensitive public forgot-password behavior changes, new token-delivery state in auth persistence, and the guard that only restores a prior reset token after the fallback path proves the newly issued token was deleted.
- Contract surfaces affected: public forgot-password masked-success behavior, delivered-only prior-token restore ordering, after-commit delivery-marker durability, hard-cut reset-token invalidation during migration, and the auth regression coverage that proves undelivered prior tokens are never resurrected.
- Failure mode if wrong: an undelivered newer prior token could be restored instead of the last delivered token, delivery-marker failures could leave the system with no valid reset token or a still-valid newly emailed token after rollback, or the migration could leave ambiguous legacy reset-token state in production.

## Approval Authority
- Mode: orchestrator
- Approver: ERP auth merge-gate hardening mission orchestration
- Basis: the packet hardens existing auth behavior without widening tenant boundaries or expanding privileges; the migration only invalidates ephemeral reset tokens and keeps a single canonical delivery-state model.

## Escalation Decision
- Human escalation required: no
- Reason: the change preserves current-state auth contracts, tightens rollback durability, and the schema cutover only deletes reset tokens whose delivery state is unknowable under the old model.

## Rollback Owner
- Owner: ERP-8 PR 116 remediation worker
- Rollback method: revert the remediation commit set on `packet/lane02-auth-merge-gate-hardening`, deploy the previous backend build, run `DELETE FROM public.password_reset_tokens; ALTER TABLE public.password_reset_tokens DROP COLUMN IF EXISTS delivered_at;`, then rerun `cd erp-domain && mvn -Dtest=PasswordResetServiceTest test`, `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`, `bash ci/check-architecture.sh`, `bash ci/check-enterprise-policy.sh`, and `bash ci/check-orchestrator-layer.sh`.

## Expiry
- Valid until: 2026-03-24
- Re-evaluate if: public forgot-password masking behavior changes again, delivery-marker semantics change again, the migration pair changes again, or the validation evidence below is superseded before merge.

## Verification Evidence
- Commands run:
  - `cd erp-domain && mvn compile -q`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=PasswordResetServiceTest test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=AuthPasswordResetPublicContractIT,TS_RuntimePasswordResetServiceExecutableCoverageTest test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp test -Pgate-fast -Djacoco.skip=true`
  - `bash ci/check-architecture.sh`
  - `bash ci/check-enterprise-policy.sh`
  - `bash ci/check-orchestrator-layer.sh`
  - `bash ci/check-codex-review-guidelines.sh`
- Result summary:
  - `mvn compile -q`: pending rerun after the delivered-token migration/docs update.
  - `MIGRATION_SET=v2 mvn -B -ntp -Dtest=PasswordResetServiceTest test`: previously passed on 2026-03-17 with `62` tests, `0` failures, `0` errors, `0` skipped`; rerunning after the docs update as part of the current packet validation.
  - `MIGRATION_SET=v2 mvn -B -ntp -Dtest=AuthPasswordResetPublicContractIT,TS_RuntimePasswordResetServiceExecutableCoverageTest test`: previously passed on 2026-03-17 with `23` tests, `0` failures, `0` errors, `0` skipped`; rerunning after the docs update as part of the current packet validation. That pass proved the public forgot-password endpoint keeps the generic masked-success response while rollback only restores the last delivered token and invalidates a newly issued token when delivery-marker persistence fails.
  - `MIGRATION_SET=v2 mvn -B -ntp test -Pgate-fast -Djacoco.skip=true`: pending rerun after the delivered-token migration/docs update.
  - `bash ci/check-architecture.sh`: pending rerun after the docs update.
  - `bash ci/check-enterprise-policy.sh`: previously failed because `docs/runbooks/migrations.md` was missing the migration entry; rerunning after that fix.
  - `bash ci/check-orchestrator-layer.sh`: pending rerun after the docs update.
  - `bash ci/check-codex-review-guidelines.sh`: pending rerun after the docs update.
- Artifacts/links:
  - Current validation is executed from the `erp-domain` module on `packet/lane02-auth-merge-gate-hardening` while fixing GitHub review threads `discussion_r2945806922` and `discussion_r2945959164`, which flagged stale-token resurrection and delivered-only restore ordering in the password-reset fallback flow.
