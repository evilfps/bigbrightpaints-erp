# Lane 01 align lifecycle and runtime truth

- Feature: `lane01-align-lifecycle-and-runtime-truth`
- Frontend impact: none

## Notes

- No auth/admin/lifecycle request-body or success-response payload shapes changed.
- Lifecycle `HOLD` / stored `SUSPENDED` tenants now consistently follow the published read-only contract on protected authenticated reads.
- `GET /api/v1/auth/me` and comparable authenticated read surfaces remain available while the tenant is suspended, but mutating protected requests still fail closed until the tenant returns to `ACTIVE`.
- `DEACTIVATED` / stored `BLOCKED` tenants still deny all protected access.
- No frontend code or payload migration is required.
