# V1 Period Close Rehearsal Onboarding

Owner: accounting lead + release ops  
Goal: prove period close can be executed and audited without data drift.

## Rehearsal Flow
1. Validate close prerequisites:
   - no unresolved checklist controls
   - no unposted critical documents
   - reconciliation deltas resolved
2. Run dry-run checklist and capture unresolved items (if any).
3. Execute close with mandatory reason.
4. Verify close snapshot and closing journal linkage.
5. Attempt prohibited post-close mutations to confirm fail-closed behavior.
6. Reopen rehearsal (if policy allows) and verify reversal/audit chain.

## Must-Pass Evidence
- Period lifecycle policy tests green (`LOCKED -> CLOSED` and reopen invariants).
- Reconciliation gate green on same SHA.
- Close/reopen audit metadata contains actor, reason, and tenant scope.

## Deployment Guard
- If close rehearsal fails, staging deploy is blocked.
- Any manual bypass without audit reason is a release stop condition.
