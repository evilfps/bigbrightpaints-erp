# Tenant lifecycle rollout safety hardening

- Feature: `tenant-lifecycle-rollout-safety-hardening`
- Frontend impact: none

## Notes

- Lifecycle control is now canonicalized on `PUT /api/v1/superadmin/tenants/{id}/lifecycle`.
- The supported request and response vocabulary is `ACTIVE`, `SUSPENDED`, `DEACTIVATED`.
- Backend persistence now uses that same vocabulary end-to-end; legacy `HOLD` and `BLOCKED` values are rewritten by `migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql`.
- `DEACTIVATED -> ACTIVE` reactivation is now supported through the same canonical lifecycle endpoint.
- Corrupted or unrecognized stored lifecycle values still fail closed instead of reopening tenant access.
