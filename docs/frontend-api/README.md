# Frontend API Contract Pack

This folder holds cross-portal API rules that do not belong to a single portal
folder.

Canonical rules:

- `GET /api/v1/auth/me` is the only frontend identity bootstrap call.
- `companyCode` is the only tenant scope identifier frontend should persist.
- Superadmin control-plane flows may still use a numeric `tenantId` path param
  from `/api/v1/superadmin/tenants/**` responses, but that id must never be
  reused as tenant-shell auth scope.
- `POST /api/v1/accounting/journal-entries` is the only public manual journal
  create route.
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse` is the only
  public reversal route.
- Period close is maker-checker only:
  `request-close -> GET /api/v1/admin/approvals -> approve-close|reject-close`.
- Retired surfaces such as `/api/v1/auth/profile`,
  `/api/v1/accounting/journals/manual`,
  `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse`, and
  `/api/v1/accounting/periods/{periodId}/close` must not be wired into UI code.
- Export approval lives in tenant-admin; report consumption lives in accounting.
- Dispatch confirmation lives in factory even when it causes invoice or journal
  side effects.

Reading order:

1. `auth-and-company-scope.md`
2. `idempotency-and-errors.md`
3. `pagination-and-filters.md`
4. `exports-and-approvals.md`
5. `accounting-reference-chains.md`
6. `dto-examples.md`

Use `accounting-reference-chains.md` for the frontend sequence from superadmin
COA bootstrap to company default accounts, product-account readiness, GST or
tax safety, opening stock, and downstream accounting references.

Use this pack as the frontend truth for shared API behavior. Do not recover
retired routes in portal-specific docs, feature code, client SDKs, or
Playwright fixtures.
