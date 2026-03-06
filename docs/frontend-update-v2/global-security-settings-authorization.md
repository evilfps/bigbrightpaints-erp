# Global security settings authorization

- Feature: `global-security-settings-authorization`
- Frontend impact: medium, RBAC/UI follow-up

## Notes

- Admin settings request and response payload shapes stay the same.
- `PUT /api/v1/admin/settings` now requires `ROLE_SUPER_ADMIN` because it mutates platform-wide CORS, mail, export, and related security/runtime settings.
- Tenant admins still retain `GET /api/v1/admin/tenant-runtime/metrics`, while `PUT /api/v1/admin/tenant-runtime/policy` remains a super-admin control-plane path.
- Frontend follow-up is to hide or disable global-settings mutation controls for tenant-admin users and keep tenant runtime metrics surfaced through the still-supported read path.
