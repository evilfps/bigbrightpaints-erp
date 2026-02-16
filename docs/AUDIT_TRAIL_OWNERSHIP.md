# Audit Trail Ownership and De-dup Contract

## Goal
Keep audit coverage complete without running duplicate audit-processing paths for the same accounting workflow event.

## Canonical Audit Surfaces
- `audit_logs` via `core.audit.AuditService`
  - Scope: authentication, authorization, security, admin/compliance, integration-failure markers.
- `audit_action_events` and `ml_interaction_events` via `core.audittrail.EnterpriseAuditTrailService`
  - Scope: enterprise business-action timeline and ML-training/interaction telemetry.
- `accounting_events` via `modules.accounting.event.AccountingEventStore`
  - Scope: immutable accounting event trail (journal and account movement lineage).
- `AccountingAuditTrailService`
  - Scope: read model only; it aggregates accounting journals/events/doc links and is not a writer.

## De-dup Policy
- Accounting journal/reversal/settlement summary events are captured by AccountingEventStore as the structured source of truth.
- Legacy summary success writes for these events in `AuditService` are fully decommissioned (not toggle-controlled).
- No profile may re-enable legacy summary success writes for `JOURNAL_ENTRY_POSTED`, `JOURNAL_ENTRY_REVERSED`, or `SETTLEMENT_RECORDED`.
- `AuditService` remains active for failure and security/admin signal paths.

## Change-Control Rule
- Any change to this ownership split must update, in the same commit:
  - this file,
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`,
  - runtime/test config contract in `application*.yml` (legacy summary toggle block must remain absent),
  - guard script `scripts/guard_audit_trail_ownership_contract.sh`.
