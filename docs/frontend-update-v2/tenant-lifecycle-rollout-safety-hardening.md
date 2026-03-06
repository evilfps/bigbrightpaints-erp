# Tenant lifecycle rollout safety hardening

- Feature: `tenant-lifecycle-rollout-safety-hardening`
- Frontend impact: none

## Notes

- No auth/admin/lifecycle request or response shapes changed.
- Superadmin lifecycle endpoints still use the existing `ACTIVE` / `SUSPENDED` / `DEACTIVATED` API vocabulary.
- Backend persistence remains aligned to the supported Flyway v2 schema path by storing `ACTIVE` / `HOLD` / `BLOCKED` in the database during suspend, activate, and deactivate flows.
- Corrupted or unrecognized stored lifecycle values now fail closed instead of reopening tenant access, so frontend code does not need to change.
