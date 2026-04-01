# ADR-004: Layered Audit Surfaces

Last reviewed: 2026-03-29

## Status

Accepted

## Context

BigBright ERP has three distinct audit needs that serve different audiences and carry different integrity and retention requirements:

1. **Platform audit** — operational audit trail for security events, authentication successes/failures, data access, and administrative actions. Consumed by tenant admins and security reviewers. Stored in the `audit_log` table via `AuditService`.
2. **Enterprise audit trail** — signed, tamper-evident business audit trail for regulatory and compliance purposes. Records financial and operational business events (journal postings, dispatch confirmations, payroll runs) with HMAC-signed actor identity. Stored in the `audit_action_event` table via `EnterpriseAuditTrailService`.
3. **Accounting event store** — domain-specific journal and settlement event records that support the accounting module's own posting, reconciliation, and period-close requirements. Managed within the accounting module's service layer.

These surfaces capture overlapping but distinct views of system activity. A single journal posting may generate records in all three: a platform audit log entry (who did it), an enterprise audit trail event (signed proof with business context), and an accounting event record (financial details for period close).

## Decision

The three audit surfaces coexist with clear ownership boundaries:

### Platform audit (`AuditService`)

- **Owner:** `core/audit/` package.
- **Writes:** asynchronously in a `REQUIRES_NEW` transaction to avoid impacting the caller's transaction.
- **Content:** authentication events, data CRUD operations, security alerts, session/IP metadata.
- **Scoping:** company-scoped via `CompanyContextHolder`, with auth-specific overrides for login/logout events where the company context may not yet be established.
- **Failure mode:** audit logging failures are caught and logged but do not propagate to the caller.

### Enterprise audit trail (`EnterpriseAuditTrailService`)

- **Owner:** `core/audittrail/` package.
- **Writes:** asynchronously in a `REQUIRES_NEW` transaction with a persistent retry queue (`audit_action_event_retry` table) and an in-memory overflow queue.
- **Content:** business events with module, action, entity references, amounts, currencies, and correlation IDs. Actor identity is HMAC-signed using `erp.security.audit.private-key`.
- **Scoping:** company-scoped via `CompanyContextService`, enforced per event.
- **Failure mode:** retry up to `erp.audit.business.retry.max-attempts` (default: 4) with exponential backoff. Events that exhaust retries are dropped with an error log. The retry queue has a configurable max size (`erp.audit.business.retry.max-queue-size`, default: 500).
- **ML interactions:** a separate ingestion path (`MlInteractionEvent`) captures frontend interaction events for analytics with actor anonymization based on consent.

### Accounting event store

- **Owner:** `modules/accounting/` package.
- **Writes:** synchronous within the accounting transaction to guarantee that journal and settlement records are always consistent with their financial side effects.
- **Content:** journal entries, settlements, corrections/notes, period-close records.
- **Failure mode:** accounting event persistence failures propagate to the caller and roll back the entire business transaction.

### Failure routing

`AuditExceptionRoutingService` and `IntegrationFailureAlertRoutingPolicy` handle exception-to-audit-event routing, ensuring that integration failures and unexpected errors are captured by the appropriate audit surface based on their severity and domain.

## Alternatives Rejected

1. **Single unified audit table** — would force a one-size-fits-all schema that cannot serve both fast operational queries and signed compliance records without compromising either.
2. **Audit as a separate microservice** — adds network hops, deployment complexity, and transactional boundary issues that the current modular monolith does not need.
3. **Synchronous-only audit** — would add unacceptable latency to every business write, especially for the enterprise audit trail's HMAC signing and retry logic.
4. **No separate accounting event store** — would conflate financial truth with operational audit, making period-close and reconciliation harder to reason about.

## Consequences

- Each audit surface is optimized for its primary consumer without compromising the others.
- Platform audit is fast and non-blocking but may lose events under extreme failure (caught exceptions, no persistent retry).
- Enterprise audit trail is durable (persistent retry queue) and tamper-evident (HMAC signing) but adds async latency.
- Accounting events are always consistent with their financial side effects because they share the same transaction.
- Developers adding new business events must decide which audit surface(s) to populate. For financial events, all three surfaces are typically appropriate.

## Cross-references

- [docs/RELIABILITY.md](../RELIABILITY.md) — retry and dead-letter handling
- [docs/SECURITY.md](../SECURITY.md) — security review policy
- ADR-003 — outbox pattern for event publishing, which also uses async persistence with retry semantics
