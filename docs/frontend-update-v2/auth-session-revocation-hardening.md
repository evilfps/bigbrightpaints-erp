# Auth session revocation hardening

- Feature: `auth-session-revocation-hardening`
- Frontend impact: medium, session UX follow-up

## Notes

- Login, refresh-token, logout, password-change, password-reset, disablement, lockout, and support hard-reset success payloads stay the same.
- Previously issued access and refresh tokens are now rejected consistently after logout, password change, password reset, disablement, lockout, and support hard-reset.
- Frontend review should confirm stale-token rejection already clears stored credentials and routes the user back to login consistently across normal logout, forced logout, and post-password-change/reset flows.
