# Auth reset recovery contract hardening

- Feature: `auth-reset-recovery-contract-hardening`
- Frontend impact: medium, conditional path migration

## Notes

- Supported public forgot/reset, admin force-reset, and support admin-password-reset request/response shapes stay the same.
- The deprecated compatibility alias `POST /api/v1/auth/password/forgot/superadmin` now returns `410 Gone` with `canonicalPath=/api/v1/auth/password/forgot` and `supportResetPath=/api/v1/companies/{id}/support/admin-password-reset`.
- Frontend follow-up is only to confirm that no shipped client still calls the retired alias. If a client still uses it, migrate to the canonical self-service or support-reset route.
- If the frontend already uses the canonical routes, no further code change is required.
