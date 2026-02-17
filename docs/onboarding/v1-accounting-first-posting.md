# V1 Accounting First Posting Onboarding

Owner: tenant admin + accounting lead  
Goal: first live posting is deterministic, balanced, and reversible.

## Readiness Checklist
1. Chart of accounts defaults are present and mapped.
2. Tax mode (GST/non-GST) is configured and validated.
3. Period is `OPEN` and not locked/closed.
4. Counterparty master data is valid (dealer/supplier mappings).
5. Idempotency/reference settings are active.

## Guided First Posting Flow
1. Draft transaction and generate pre-post preview.
2. Confirm double-entry balance and control-account impact.
3. Validate reason codes for any override/exception path.
4. Post with idempotency key and capture reference.
5. Verify resulting journal linkage and subledger consistency.
6. Run targeted reconciliation check (document vs GL linkage).

## Must-Pass Evidence
- Targeted accounting/p2p/o2c tests green for touched path.
- No unlinked journal or duplicate allocation sentinel findings.
- Audit metadata includes actor, tenant scope, and reason where required.

## Fail-Closed Rules
- If period is locked/closed: reject write.
- If idempotency key conflict payload differs: return conflict, no side effects.
- If reconciliation link missing after post: treat as release blocker.
