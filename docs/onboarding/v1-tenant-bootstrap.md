# V1 Tenant Bootstrap Onboarding

Owner: superadmin + platform ops  
Goal: create a tenant that is safe to transact on day 1 without accounting/data risk.

## Flow
1. Create tenant with canonical code, timezone, and tax mode.
2. Assign tenant admin user and verify tenant-only scope.
3. Set tenant lifecycle state `ACTIVE` with reason metadata.
4. Configure quota baseline (users, API, storage, sessions) and enforcement flags.
5. Verify tenant metrics endpoint and audit trail events are present.
6. Run tenant boundary smoke checks:
   - admin cannot perform superadmin actions
   - cross-tenant requests fail closed
   - unauthenticated + company-header requests fail closed

## Must-Pass Evidence
- Audit events for bootstrap, admin assignment, lifecycle, and quota changes.
- `AuthTenantAuthorityIT` and company authority tests green on same SHA.
- `check-architecture` and `check-enterprise-policy` green.

## Operator Notes
- Never leave lifecycle reason empty for hold/block transitions.
- Do not unlock tenant traffic before quota and role checks are confirmed.
