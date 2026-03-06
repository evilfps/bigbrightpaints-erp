# Auth token secret storage hardening

- Feature: `auth-token-secret-storage-hardening`
- Frontend impact: none

## Notes

- Login, refresh-token, logout, forgot-password, and reset-password request/response shapes stay the same.
- The change is backend-only: refresh-token and password-reset secrets are now stored as digests with compatibility backfill and legacy-row fallback.
- No frontend code change required.
