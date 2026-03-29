# Runtime policy cache invalidation regression fix

- Feature: `runtime-policy-cache-invalidation-regression-fix`
- Frontend impact: none

## Notes

- Runtime/quota control now lives on `PUT /api/v1/superadmin/tenants/{id}/limits`.
- The request body uses the canonical quota fields, including `quotaMaxConcurrentRequests`.
- Tenant detail and current limit values are read back from `GET /api/v1/superadmin/tenants/{id}`.
- Successful updates refresh live enforcement on the handling node immediately instead of waiting for cache TTL expiry.
- The retired `/api/v1/companies/{id}/tenant-runtime/policy` and `/api/v1/admin/tenant-runtime/*` surfaces are no longer part of the published contract.
