# Rollback Runbook

Last reviewed: 2026-02-15
Owner: Release & Ops Agent

## Purpose
Provide deterministic rollback steps for application and database failures.

## Preconditions
- Release artifact/version is known.
- Prior deploy artifact is available.
- Data backup/snapshot status is confirmed.
- Incident owner and approval authority assigned.

## Trigger Conditions
- Critical functional regression in accounting/auth/payroll.
- SLO breach persisting beyond agreed mitigation window.
- Migration side effects causing service instability or data corruption risk.

## Application Rollback Steps
1. Freeze new deployments.
2. Identify last known good release artifact/tag.
3. Revert deployment to that artifact/tag.
4. Run health + critical invariant smoke checks.
5. Confirm service stabilization and error-rate drop.
6. Publish incident update with rollback timestamp and impact.

## Database Rollback Strategy
- Preferred: forward-fix migration when rollback is unsafe.
- If rollback is required:
  1. Stop write traffic or enter maintenance mode.
  2. Restore from validated backup/snapshot.
  3. Reconcile post-restore ledger and critical domain counts.
  4. Re-enable traffic after validation checks pass.

## Validation Checklist
- [ ] auth login and token refresh smoke checks
- [ ] accounting posting smoke check
- [ ] reconciliation dashboard sanity checks
- [ ] payroll critical endpoint smoke check
- [ ] outbox/scheduler health checks

## Evidence to Capture
- rollback initiator
- approved by
- exact commands/manifests used
- timestamps
- verification outputs
- residual risks

## Unknowns and TODOs
- Production deployment platform-specific rollback command is unspecified.
  - TODO: add exact platform commands once deployment target is documented.

## Enterprise R2 Linkage
- For high-risk changes, set rollback owner and approval expiry in `docs/approvals/R2-CHECKPOINT.md` before release go/no-go.
