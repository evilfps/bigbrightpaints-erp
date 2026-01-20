# LF Tracker (derived from commit messages + evidence folders)

This file is a lightweight index of LF work based on commit messages and
evidence artifacts. It does not guarantee a fix unless a validating test or
explicit completion note exists.

## Summary
- Confirmed complete: LF-19, LF-001, LF-007, LF-008, LF-009 (see `HYDRATION.md`).
- Fixed in this worktree: LF-011, LF-012, LF-013, LF-014, LF-015, LF-016, LF-017, LF-018, LF-020, LF-021, LF-022, LF-023.

## Tracker

| LF | Evidence folder | Commit signals | Status |
| --- | --- | --- | --- |
| LF-001 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-001` | Audit log company resolution + auth login audit events; `AuthAuditIT` | Fixed |
| LF-007 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007` | Payroll idempotency scoped per company; `PayrollRunIdempotencyIT` | Fixed |
| LF-008 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008` | Orchestrator audit company scoping; `TraceServiceIT` | Fixed |
| LF-009 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009` | Settlement idempotency scope fix; `SettlementE2ETest` | Fixed |
| LF-011 | none | GST config health/validation restored; `GstConfigurationRegressionIT` | Fixed |
| LF-012 | none | WIP debit/credit match at log creation; `ProductionLogWipPostingRegressionIT` | Fixed |
| LF-013 | none | Packing status refresh on packed quantity update; `ProductionLogPackingStatusRegressionIT` | Fixed |
| LF-014 | none | Null discount default handled in catalog create; `ProductionCatalogDiscountDefaultRegressionIT` | Fixed |
| LF-015 | none | Production log list/detail lazy load guarded; `ProductionLogListDetailLazyLoadRegressionIT` | Fixed |
| LF-016 | none | Bulk pack rejects manual packaging materials; `BulkPackingManualPackagingRegressionIT` | Fixed |
| LF-017 | none | Bulk pack skip packaging consumption; `BulkPackingSkipPackagingConsumptionRegressionIT` | Fixed |
| LF-018 | none | `PackingService.listUnpackedBatches` transactional to avoid lazy-load 500 | Fixed |
| LF-019 | none | `HYDRATION.md` marks complete on `pr-coverage-lf-clean` | Confirmed complete |
| LF-020 | none | `V103__raw_material_batch_code_unique.sql` + batch code validation in services; `AuditFixesIntegrationTest#rawMaterialBatchCodesMustBeUniquePerMaterial` | Fixed |
| LF-021 | none | Opening stock import GL posting; `OpeningStockPostingRegressionIT` | Fixed |
| LF-022 | none | Purchase return idempotency; `PurchaseReturnIdempotencyRegressionIT` | Fixed |
| LF-023 | none | Idempotency conflict handling; `IdempotencyConflictRegressionIT` | Fixed |

## Notes
- Evidence-only means SQL/curl outputs exist but no fix commit was found by message scan.
- "Likely fixed" should be validated by tests or updated completion notes if needed.
