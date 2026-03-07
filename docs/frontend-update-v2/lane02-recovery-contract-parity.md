# Lane 02 recovery contract parity

- Feature: `lane02-recovery-contract-parity`
- Frontend impact: none

## Notes

- No new auth/admin request or response shape changes were introduced in this packet.
- The final published recovery contract remains: `POST /api/v1/auth/password/forgot/superadmin` is a deprecated compatibility alias that returns `410 Gone` with `canonicalPath=/api/v1/auth/password/forgot` and `supportResetPath=/api/v1/companies/{id}/support/admin-password-reset`.
- The supported live recovery surfaces are still the canonical public forgot-password flow for self-service recovery and the root-only support reset flow for operator recovery.
- Frontend/support follow-up is only to keep treating the retired alias as a migration pointer; no additional UI cutover is required if clients already use the supported routes.
