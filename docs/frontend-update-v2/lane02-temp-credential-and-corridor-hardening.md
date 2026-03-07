# Temporary-credential redaction and corridor hardening

- Feature: `lane02-temp-credential-and-corridor-hardening`
- Frontend impact: medium, onboarding follow-up required

## Notes

- `POST /api/v1/superadmin/tenants/onboard` no longer returns `adminTemporaryPassword` in the success payload. The response still includes tenant/accounting metadata plus `adminEmail` and `credentialsEmailSent`.
- Superadmin onboarding UX must stop expecting an inline temporary-password reveal/copy flow. If `credentialsEmailSent=false`, operators should surface a delivery warning and use the supported support reset path instead of relying on a leaked API credential.
- The forced-password-change corridor contract itself is unchanged: while `mustChangePassword=true`, only login/refresh, `/auth/me`, `GET /auth/profile`, password change, and logout remain allowed, and denied out-of-corridor requests still return `403` `ApiResponse` with `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`.
