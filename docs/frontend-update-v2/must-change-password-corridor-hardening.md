# Must-change-password corridor hardening

- Feature: `must-change-password-corridor-hardening`
- Frontend impact: medium, workflow confirmation required

## Notes

- Login, refresh-token, `/auth/me`, `GET /auth/profile`, password-change, and logout success payloads stay the same.
- While `mustChangePassword=true`, only the password-change corridor endpoints remain usable; normal protected work is denied with `403` `ApiResponse` carrying `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`.
- Frontend follow-up is to ensure users who log in with `mustChangePassword=true` are routed directly into password change, normal protected API calls are suppressed until the change succeeds, and corridor `403` responses are treated as enforced workflow state rather than generic auth drift.
