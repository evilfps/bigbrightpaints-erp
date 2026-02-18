# Audit Trail Ownership and De-dup Contract

This document is the ownership contract for accounting audit-trail event emission and de-dup enforcement.

## Canonical Audit Surfaces

- AccountingEventStore is the canonical source for accounting journal and settlement lifecycle events.
- AuditService remains the canonical source for platform security/access/system audit events.
- Query/report consumers must not assume duplicate success emissions across both surfaces.

## De-dup Policy

- Accounting journal/reversal/settlement summary events are captured by AccountingEventStore.
- Legacy summary success writes for these events in `AuditService` are fully decommissioned (not toggle-controlled).
- No profile may re-enable legacy summary success writes.
- Any change that could reintroduce dual-write behavior is fail-closed and must be rejected in review.

## Ownership

- Primary owner: accounting-domain maintainers.
- Review owner: security-governance reviewers for audit boundary conformance.
- Runtime ownership for cross-module alerts remains with orchestrator-layer governance.

## Change-Control Rule

- Changes to accounting audit emission behavior require:
  - explicit contract update in this file,
  - regression tests for de-dup expectations,
  - and orchestrator review sign-off before merge.
