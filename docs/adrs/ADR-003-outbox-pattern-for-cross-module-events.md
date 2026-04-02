# ADR-003: Outbox Pattern for Cross-Module Event Publishing

Last reviewed: 2026-04-02

## Status

Accepted

## Context

The orchestrator-erp backend publishes cross-module domain events (inventory movements, accounting integration commands, dispatch triggers, etc.) via its internal orchestrator and RabbitMQ. Without a write-ahead log, events could be lost if the broker is unavailable at the moment a transaction commits, leading to silent data inconsistencies — for example, inventory stock could be deducted without the corresponding accounting journal entry ever being created.

The system already uses RabbitMQ as the message broker, Spring `@Transactional` for database transactions, and ShedLock for distributed scheduler coordination.

## Decision

The orchestrator uses a database-backed outbox pattern for all domain event publishing:

1. **Write to outbox within the business transaction.** When a service emits a `DomainEvent`, the `EventPublisherService.enqueue()` persists an `OutboxEvent` row in the same database transaction as the business operation. This guarantees that the event is not lost if the broker is unreachable.
2. **Scheduled polling publishes to RabbitMQ.** `OutboxPublisherJob` runs on a configurable cron schedule (default: every 30 seconds) and publishes pending events via RabbitMQ.
3. **Claim-and-publish with fencing.** The publisher uses optimistic locking (`version` field) to claim an event for publishing, preventing duplicate publishes from overlapping scheduler runs.
4. **Exponential backoff on failure.** Deterministic retryable failures (connection errors, auth errors, message conversion errors) trigger automatic retry with exponential backoff (30s base, capped at 5 attempts).
5. **Fail-closed ambiguous states.** If the broker publish outcome is uncertain (e.g., timeout after broker acceptance), the event is held in `PUBLISHING` state with an `AMBIGUOUS_PUBLISH:` error marker. These events are not automatically retried; they require manual reconciliation to avoid duplicate side effects.
6. **Dead-letter after max retries.** Events that exhaust all retry attempts are flagged as dead-lettered and preserved for investigation rather than silently dropped.
7. **Health and metrics.** The outbox exposes counts of pending, publishing, stale-publishing, ambiguous, and dead-lettered events through both a health snapshot endpoint and Micrometer gauges.

## Alternatives Rejected

1. **Direct RabbitMQ publish** — risks silent event loss on broker unavailability; unacceptable for financial data integrity.
2. **Transactional outbox with Change Data Capture (Debezium)** — adds operational complexity (Debezium deployment, CDC connector management) disproportionate to the current single-database modular monolith.
3. **Spring ApplicationEvent only** — works for in-process coupling but provides no durable guarantee or cross-service replay.
4. **At-least-once without dedup** — would require every consumer to be fully idempotent, which is not yet the case for all downstream handlers.

## Consequences

- Domain events survive broker outages and application restarts as long as the database is available.
- Financial and operational side effects triggered by events (journal entries, stock movements) have a much lower risk of being silently skipped.
- Ambiguous publish outcomes require manual intervention rather than risking duplicate side effects — this is intentional and conservative.
- The outbox adds latency (up to the cron interval) between business operation completion and event delivery, which is acceptable for the current integration patterns.
- Operators should monitor the `stale_publishing` and `ambiguous_publishing` outbox metrics for events that need reconciliation.

## Cross-references

- [docs/RELIABILITY.md](../RELIABILITY.md) — retry/dead-letter handling and known safety gaps
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — orchestrator module ownership and canonical dependency edges
- ADR-004 — audit layering, which also uses async persistence with retry queues
