# Auth Audit Attribution Policy

This policy defines how authentication audit events must carry actor/company attribution.

## Scope
- `AuditService.logAuthSuccess(...)`
- `AuditService.logAuthFailure(...)`
- Auth entrypoints that call these wrappers (currently `AuthService.login(...)`).

## Rules
1. Callers should pass nonblank `username` and `companyCode` values.
2. Wrappers normalize incoming values:
   - nonblank values are trimmed and used as overrides
   - blank values are treated as unresolved
3. Unresolved values do **not** fall back to ambient thread context:
   - username resolves to `UNKNOWN_AUTH_ACTOR`
   - company override uses unresolved sentinel (no ambient company lookup)
4. Wrapper metadata includes explicit unresolved markers when needed:
   - `authActorResolution=UNRESOLVED`
   - `authCompanyResolution=UNRESOLVED`

## Why
- Prevents silent misattribution when thread-local security/company context is stale, missing, or mutated.
- Produces deterministic audit semantics for incident review and compliance reporting.

## Test Coverage
- `AuditServiceTest` verifies wrapper behavior in unit-level contexts.
- `AuditServiceAsyncIT` verifies proxied `@Async` behavior and context-mutation resilience.
- `AuthServiceAuditAttributionTest` verifies auth service failure path passes normalized audit identifiers.
