# Exports and Approvals

Last reviewed: 2026-04-26

## Ownership split

- Export request creation belongs to accountant-facing report screens
  (`ROLE_ADMIN` or `ROLE_ACCOUNTING`).
- Export approval decisions belong to `tenant-admin`.
- Report viewing, drill-down, and export intent begins in `accounting`.
- Download eligibility is resolved through the canonical download contract, not
  through a separate pending-export alias.

## Canonical flow

1. Accounting user configures the report and requests export.
2. Backend creates an export request.
3. Tenant-admin reviews the request in `GET /api/v1/admin/approvals`.
4. Tenant-admin approves or rejects the approval item through the canonical decision route.
5. Requester calls `GET /api/v1/exports/{requestId}/download`.
6. Frontend enables the actual file retrieval only after that backend call succeeds; pending, rejected, expired, or not-owned requests return controlled denial responses instead of a file.

## API surfaces

- `POST /api/v1/exports/request`
- `GET /api/v1/admin/approvals`
- `POST /api/v1/admin/approvals/{originType}/{id}/decisions`
- `GET /api/v1/exports/{requestId}/download`

## Canonical request body

`POST /api/v1/exports/request` accepts:

```json
{
  "reportType": "TRIAL_BALANCE",
  "format": "CSV",
  "parameters": "periodId=3&startDate=2026-03-01&endDate=2026-03-31&exportFormat=CSV"
}
```

Notes:

- `reportType` is required.
- `format` is optional.
- `parameters` is a serialized filter string. Frontend should generate it from
  the exact active report filters, not from hidden client-only state.
- There is no separate export-history list endpoint in the current backend.

## Contract rules

- Do not expose direct file-download actions while the export is still pending, rejected, expired, not owned by the caller, or otherwise blocked by backend policy.
- Rejections should surface reviewer reason and a path back to the originating
  report screen.
- Export approval actions do not belong in accounting page chrome.
- Approval inbox rows should be keyed from `GET /api/v1/admin/approvals`, not
  from a retired export-specific pending list.
- The download route returns the file bytes when allowed. It does not expose a separate status endpoint or `downloadUrl`; keep status messaging on the originating report screen from the create response, the approval inbox row, and any controlled denial returned by the download call.
- When export approval is disabled at system-settings level, the backend policy may allow download without an approved decision. Treat that as an explicit backend bypass, not as a frontend-created exception path.

## Statuses frontend must render

- `PENDING`: request submitted, waiting for tenant-admin decision
- `APPROVED`: request can proceed through the download contract
- `REJECTED`: show reviewer reason and a path back to the originating report
- `EXPIRED`: stale request; user must submit a fresh export request

Example approval row:

```json
{
  "id": 901,
  "publicId": "0b410db6-2cbf-41a5-9077-6f6a479f0d36",
  "originType": "EXPORT_REQUEST",
  "ownerType": "REPORTS",
  "status": "PENDING",
  "reportType": "GST_SUMMARY",
  "createdAt": "2026-03-28T10:22:11Z"
}
```

Example decision request:

```json
{
  "decision": "APPROVE",
  "reason": "Approved for finance review"
}
```

## Create Response and Download Check

`POST /api/v1/exports/request` creates the export request record. There is no separate status endpoint in the current contract; frontend should keep the returned request id, refresh the tenant-admin approval inbox where needed, and call `GET /api/v1/exports/{requestId}/download` only when the UI is ready to attempt file retrieval.
