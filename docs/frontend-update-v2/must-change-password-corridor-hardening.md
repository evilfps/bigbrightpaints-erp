# Must-change-password corridor hardening

- Feature: `must-change-password-corridor-hardening`
- Frontend impact: medium, workflow confirmation required

## Notes

- Login, refresh-token, `/auth/me`, password-change, and logout success payloads stay the same. `/api/v1/auth/profile` stays retired, is not part of the corridor, and should fall through as `404` rather than a corridor success or workflow alias.
- While `mustChangePassword=true`, only the password-change corridor endpoints remain usable; normal protected work is denied with `403` `ApiResponse` carrying `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`.
- Frontend follow-up is to ensure users who log in with `mustChangePassword=true` are routed directly into password change, normal protected API calls are suppressed until the change succeeds, and corridor `403` responses are treated as enforced workflow state rather than generic auth drift.
