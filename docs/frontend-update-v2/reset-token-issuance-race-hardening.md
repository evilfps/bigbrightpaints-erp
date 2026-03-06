# Reset token issuance race hardening

- Feature: `reset-token-issuance-race-hardening`
- Frontend impact: none

## Notes

- Public forgot-password and admin-triggered reset issuance still use the same request and response shapes.
- Duplicate or overlapping reset issuance now deterministically leaves only the latest reset link usable instead of cross-deleting every valid token.
- No frontend code change required.
