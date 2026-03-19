# Auth compatibility regression handoff

- Feature: `auth-compatibility-regression-handoff`
- Frontend impact: targeted contract review

## Notes

- This was the compatibility and documentation verification pass for the mission's auth/admin changes.
- Login, refresh-token, logout, password-change, forgot/reset, admin user-control, and admin settings payloads were rechecked and remained frontend-safe.
- `GET /api/v1/auth/me` remains a stable wrapped `ApiResponse<MeResponse>` contract. The public payload now returns `companyCode` as the only company identifier, the deprecated `companyId` alias is removed, and no raw DTO fields are exposed beyond the documented `MeResponse` shape.
- Public forgot/reset stays email-only on the shared user identity. `companyCode` is still required for login, but it selects workspace scope for that one account rather than selecting a separate tenant-specific password record.
- `GET /api/v1/admin/roles` and `GET /api/v1/admin/roles/{roleKey}` remain available as read-only role-catalog endpoints, but `POST /api/v1/admin/roles` is removed from the admin contract.
- The admin-facing role catalog is fixed to the persisted platform roles used on the client side. `ROLE_SUPER_ADMIN` is platform-owner only, is not returned by the admin role catalog, is not echoed in admin user DTO role lists, and cannot be assigned from admin/client-facing surfaces.
- Admin user edit semantics now treat `roles=null` as "leave role bindings unchanged" and `roles=[]` as "clear all persisted roles". That explicit empty-list path is how the admin surface scrubs hidden legacy authorities without re-exposing raw authority names in `UserDto.roles`.
- The published OpenAPI contract was refreshed so logout and admin user-control no-content endpoints now match the live `204 No Content` behavior.
- Frontend follow-up is limited to removing any `/auth/me.data.companyId` reads, treating the admin role surface as read-only, and assuming the role picker/catalog never offers `ROLE_SUPER_ADMIN`.
