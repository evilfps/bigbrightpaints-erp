# Runtime policy cache invalidation regression fix

- Feature: `runtime-policy-cache-invalidation-regression-fix`
- Frontend impact: none

## Notes

- `PUT /api/v1/companies/{id}/tenant-runtime/policy` keeps the same request body and success response shape.
- The canonical company-scoped runtime-policy path now refreshes live enforcement on the handling node immediately after a successful update instead of waiting for cache TTL expiry.
- No frontend code or payload migration is required.
