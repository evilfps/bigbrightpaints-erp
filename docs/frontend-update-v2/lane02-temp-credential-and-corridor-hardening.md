# Temporary-credential redaction and corridor hardening

- Feature: `lane02-temp-credential-and-corridor-hardening`
- Frontend impact: medium, onboarding follow-up required

## Notes

- `POST /api/v1/superadmin/tenants/onboard` no longer returns `adminTemporaryPassword` in the success payload. The response still includes tenant/accounting metadata plus `adminEmail` and `credentialsEmailSent`, but successful onboarding now requires credential email delivery to be enabled, so `credentialsEmailSent=false` is no longer a supported success outcome.
- Superadmin onboarding UX must stop expecting an inline temporary-password reveal/copy flow. If credential delivery is disabled, the backend now rejects onboarding in a controlled way; operators should surface that failure, restore delivery, and then retry or use the supported support reset path after a successful onboarding.
- The forced-password-change corridor contract itself is unchanged: while `mustChangePassword=true`, only login/refresh, `/auth/me`, `GET /auth/profile`, password change, and logout remain allowed, and denied out-of-corridor requests still return `403` `ApiResponse` with `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`.
