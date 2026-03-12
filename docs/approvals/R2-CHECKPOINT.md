# R2 Checkpoint

## Scope
- Feature: `recovery-review.truth-rails-governance-and-feedback`
- Branch: `recovery/02-truth-rails`
- High-risk paths touched: accounting provenance services, accounting period control wiring, invoice truth surfacing, and purchasing truth mappers/tests under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`.
- Why this is R2: the packet changes high-risk accounting and control paths while recovering PR #96, so the branch needs explicit evidence that provenance links, canonical posting boundaries, coverage additions, and push-safety hardening remain compatibility-preserving.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/` plus the paired regression tests and governance evidence for PR #96.
- Contract surfaces affected: accounting audit transaction detail provenance, invoice linked-reference expansion, GRN linked purchase expansion, period-close diagnostics wiring, and CODE-RED prod-hardening test defaults.
- Main risks being controlled: ambiguous settlement provenance back-links, N+1 query regressions on invoice or GRN list surfaces, enterprise-policy rejection for missing R2 evidence, changed-files coverage misses on touched runtime files, and Droid Shield rejection from realistic-looking test-only secret literals.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving review-remediation on the active recovery branch with no privilege widening, tenant-boundary change, or destructive migration.

## Escalation Decision
- Human escalation required: no
- Reason: the packet resolves review and governance blockers on already-shipped truth-rails behavior, preserves the current business contract, and does not widen access or alter data boundaries.

## Rollback Owner
- Owner: recovery-review truth-rails worker
- Rollback method: revert the recovery-review commit, then rerun `bash ci/check-enterprise-policy.sh`, `bash ci/check-codex-review-guidelines.sh`, and the targeted truth-rails Maven suite before reopening PR #96 for review.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: the packet grows beyond review-remediation into new accounting workflows, tenant-boundary changes, additional CODE-RED prod-profile defaults, or any `db/migration_v2` edits.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-codex-review-guidelines.sh`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='InvoiceServiceTest,PurchaseResponseMapperTest,PurchasingServiceGoodsReceiptTest,AccountingAuditTrailServiceTest,InventoryAccountingEventListenerIT,BusinessDocumentTruthsTest,TruthRailsSharedDtoContractTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='InvoiceServiceTest,PurchasingServiceGoodsReceiptTest,InventoryAccountingEventListenerIT,BusinessDocumentTruthsTest' test`; `python3 - <<'PY2' ... PY2`; `python3 scripts/changed_files_coverage.py ...`
- Result summary: baseline compile/test health was re-established for the branch, PR #96 review comments were covered by code and focused regression tests, the R2/governance packet now travels with the high-risk accounting diff, changed-files coverage was raised with mapper/service/DTO regression tests, and the prod-hardening packet now uses Droid-Shield-safe JWT placeholders instead of realistic-looking literals.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingAuditTrailServiceCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseResponseMapper.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseResponseMapperTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/shared/dto/TruthRailsSharedDtoContractTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_HealthEndpointProdHardeningIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_SwaggerProdHardeningIT.java`
