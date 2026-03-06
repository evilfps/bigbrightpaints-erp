# Masked admin target lookup hardening

- Feature: `masked-admin-target-lookup-hardening`
- Frontend impact: low, contract note only

## Notes

- No request-body or success-response payload shapes changed.
- For tenant-admin initiated privileged user-control actions (`force-reset-password`, status enable/disable, suspend, unsuspend, MFA disable, delete), a foreign-tenant user id now returns the same `400 User not found` validation envelope as a truly missing id.
- This is intentional masking to keep foreign users indistinguishable from nonexistent targets on auth-sensitive admin endpoints.
- `POST /api/v1/admin/roles` remains a super-admin-only mutation path; tenant admins are now rejected at the controller boundary before role mutation logic proceeds.
- No frontend code change is required if the UI already treats missing and foreign targets as the same generic missing-user case; review should not rely on distinguishing those cases.
