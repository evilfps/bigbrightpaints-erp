# Global security settings authorization

- Feature: `global-security-settings-authorization`
- Frontend impact: medium, RBAC/UI follow-up

## Notes

- Admin settings request and response payload shapes stay the same.
- `PUT /api/v1/admin/settings` now requires `ROLE_SUPER_ADMIN` because it mutates platform-wide CORS, mail, export, and related security/runtime settings.
- The current public contract does not publish `GET /api/v1/admin/tenant-runtime/metrics` or `PUT /api/v1/admin/tenant-runtime/policy`.
- Superadmin tenant quota/runtime controls now live on `GET /api/v1/superadmin/tenants/{id}` and `PUT /api/v1/superadmin/tenants/{id}/limits`.
- The backend does not currently expose dedicated admin-settings fields for `sessionTimeoutMinutes`, `passwordMinLength`, `maxLoginAttempts`, or `mfaRequired`.
- Frontend follow-up is to hide or disable tenant-admin mutation controls for global settings and avoid rendering unsupported runtime-policy form fields as if they are wired.
