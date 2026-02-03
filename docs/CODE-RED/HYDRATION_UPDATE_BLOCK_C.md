# Hydration Update (Block C)

## 2026-02-03
- Scope: Block C (P0) Orchestrator Strong Arm defense-in-depth + auditability.
- Changes:
  - Service-layer feature flag gating enforced in orchestrator services; denied commands are audited.
  - Fulfillment updates route through SalesService guards; terminal status updates require dispatch truth.
  - Idempotency + audit envelope: outbox/audit store requestId/traceId/idempotencyKey; auto-approve idempotent;
    mismatch-safe idempotency returns 409; migration `V121__orchestrator_audit_outbox_identifiers.sql`.
- Tests:
  - `mvn -B -ntp -Dtest=CommandDispatcherTest,OrderAutoApprovalListenerTest,EventPublisherServiceTest,DomainEventTest,OrchestratorControllerIT test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed.
