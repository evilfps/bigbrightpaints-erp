# Audit Read Hard-Cut

This note records the canonical audit read contract after the audit unification hard-cut.

## What Was Done

- tenant accounting reads, tenant-admin review reads, and platform review reads were split onto explicit controllers instead of sharing the older mixed audit surfaces
- canonical `/events` feeds now read through `AuditAccessService`, which merges:
  - `audit_logs`
  - enterprise business audit events
- accounting event-trail failures logged as `INTEGRATION_FAILURE` with `eventTrailOperation` metadata are now treated as accounting audit evidence
- merged feed paging is fail-closed at a 5,000-row window and extreme page values are overflow-safe
- accounting-only and business-only module filtering now stay semantically aligned with the module value returned in the DTO

## Canonical API Calls

### 1. Tenant accounting audit feed

Use this for accounting-facing event review inside a tenant.

```http
GET /api/v1/accounting/audit/events?from=2026-03-01&to=2026-03-31&module=ACCOUNTING&status=SUCCESS&page=0&size=50
```

Optional query params:

- `from`
- `to`
- `module`
- `action`
- `status`
- `actor`
- `entityType`
- `reference`
- `page`
- `size`

Notes:

- this surface is tenant-admin/accounting only
- omit `module` to default to `ACCOUNTING`, or pass `module=ACCOUNTING`
- non-accounting module values (including `INVENTORY`, `SALES`, `EXPORT`, `AUTH`, `ADMIN`, `SUPERADMIN`, `CHANGELOG`, and `COMPANIES`) are rejected by the accounting-visibility policy and return an empty result

### 2. Tenant-admin review feed

Use this when a tenant admin needs the broader tenant review feed.

```http
GET /api/v1/admin/audit/events?from=2026-03-01&to=2026-03-31&module=BUSINESS&page=0&size=50
```

Optional query params:

- `from`
- `to`
- `module`
- `action`
- `status`
- `actor`
- `entityType`
- `reference`
- `page`
- `size`

Notes:

- this surface is tenant-admin only and queries the full tenant-wide merged feed
- use `module=ACCOUNTING` for accounting-labelled rows
- use `module=BUSINESS` for non-accounting business rows such as reference/order/dispatch activity
- this is the only canonical surface that exposes non-accounting module rows; the accounting audit feed (`/api/v1/accounting/audit/events`) restricts visibility to accounting modules only

### 3. Accounting transaction audit feed

Use this for journal-centric accounting provenance.

```http
GET /api/v1/accounting/audit/transactions?from=2026-03-01&to=2026-03-31&status=POSTED&reference=JE-2026-0001&page=0&size=50
GET /api/v1/accounting/audit/transactions/{journalEntryId}
```

### 4. Platform audit review

Use this only for superadmin platform review.

```http
GET /api/v1/superadmin/audit/platform-events?from=2026-03-01&to=2026-03-31&status=FAILURE&page=0&size=50
```

Optional query params:

- `from`
- `to`
- `action`
- `status`
- `actor`
- `entityType`
- `reference`
- `page`
- `size`

## Removed Surfaces

The following older accounting audit surfaces were hard-removed and should not be called by frontend code, scripts, or integrations:

- `GET /api/v1/accounting/audit-trail`
- `GET /api/v1/accounting/audit/digest`
- `GET /api/v1/accounting/audit/digest.csv`

The following backend-only legacy pieces were also removed with that hard-cut:

- `AccountingAuditTrailController`
- `AuditTrailQueryService`
- `AuditDigestScheduler`
- `AccountingAuditTrailEntryDto`
- `AuditDigestResponse`

## Practical Migration Rule

If the caller needs:

- tenant accounting events -> call `GET /api/v1/accounting/audit/events`
- tenant admin review -> call `GET /api/v1/admin/audit/events`
- journal-centric accounting provenance -> call `GET /api/v1/accounting/audit/transactions` and `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
- platform/superadmin review -> call `GET /api/v1/superadmin/audit/platform-events`

Do not route accounting portal reads back to `/api/v1/accounting/audit-trail` or the removed digest endpoints.
