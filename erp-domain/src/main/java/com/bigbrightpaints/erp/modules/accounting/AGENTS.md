# Accounting Module Agent Map

Last reviewed: 2026-02-15
Scope: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting`

## Purpose
Protect ledger correctness, period boundaries, posting idempotency, and reconciliation safety.

## Canonical References
- `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- `erp-domain/docs/RECONCILIATION_CONTRACTS.md`
- `docs/SECURITY.md`
- `docs/RELIABILITY.md`

## Hard Invariants
- Double-entry must balance.
- Period lock/close rules must fail closed.
- Journal references/idempotency mappings stay deterministic.
- Settlement and reversal flows remain replay-safe.

## Required Checks Before Done
- Targeted accounting tests for changed behavior.
- Relevant truthsuite/reconciliation tests when posting logic is touched.
- `bash ci/check-architecture.sh`
- `bash scripts/verify_local.sh` for high-risk accounting edits.

## Escalate to Human (R2)
- Ledger posting semantic changes.
- Close/reopen period policy changes.
- Manual journal authorization rule changes.
- Any migration that mutates accounting tables.

## Anti-Patterns
- Bypassing period checks to unblock tests.
- Silent fallback behavior for posting failures.
- Using docs as proof without executable tests.
