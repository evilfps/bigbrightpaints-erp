# Controlled auth error contracts

- Feature: `controlled-auth-error-contracts`
- Frontend impact: low, contract note

## Notes

- Supported auth/admin success payloads stay the same.
- Previously raw framework or servlet failures now return controlled `ApiResponse` contracts: lockout returns `401` with `AUTH_005`; authenticated tenant-binding mismatches return `403` with `AUTH_004` plus `reason` and `reasonDetail`; tenant runtime denials now return explicit runtime denial codes such as `TENANT_ON_HOLD`, `TENANT_BLOCKED`, and `TENANT_REQUEST_RATE_EXCEEDED`.
- No frontend code change is required if the client already handles generic `ApiResponse` auth errors, but product review may want to map the explicit new codes and reasons to more specific UX messaging.
