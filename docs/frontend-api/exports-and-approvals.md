# Exports And Approvals

## Ownership split

- Export request and approval submission UX belongs to `tenant-admin`.
- Report viewing, drill-down, and export intent begins in `accounting`.
- Download eligibility is resolved through the canonical download contract, not
  through a separate pending-export alias.

## Canonical flow

1. Accounting user configures the report and requests export.
2. Backend creates an export request.
3. Tenant-admin reviews the request in `GET /api/v1/admin/approvals`.
4. Tenant-admin approves or rejects the request.
5. Requester checks `GET /api/v1/exports/{requestId}/download`.
6. Frontend enables the actual file retrieval or download action only when the
   returned contract says the request is approved, or when the backend
   explicitly reports that the approval gate is disabled.

## API surfaces

- `POST /api/v1/exports/request`
- `GET /api/v1/admin/approvals`
- `PUT /api/v1/admin/exports/{requestId}/approve`
- `PUT /api/v1/admin/exports/{requestId}/reject`
- `GET /api/v1/exports/{requestId}/download`

## Canonical request body

`POST /api/v1/exports/request` accepts:

```json
{
  "reportType": "TRIAL_BALANCE",
  "parameters": "periodId=3&startDate=2026-03-01&endDate=2026-03-31&exportFormat=CSV"
}
```

Notes:

- `reportType` is required.
- `parameters` is a serialized filter string. Frontend should generate it from
  the exact active report filters, not from hidden client-only state.
- There is no separate export-history list endpoint in the current backend.

## Contract rules

- Do not expose direct file-download actions while the download contract is
  still blocked.
- Rejections should surface reviewer reason and a path back to the originating
  report screen.
- Export approval actions do not belong in accounting page chrome.
- Approval inbox rows should be keyed from `GET /api/v1/admin/approvals`, not
  from a retired export-specific pending list.
- The download contract does not return `fileName` or `downloadUrl`. Frontend
  must derive its next action from `requestId`, `status`, `reportType`,
  `parameters`, and `message`.
- When export approval is disabled at system-settings level, the download
  contract may return a non-approved status with an allow message. Treat that
  as an explicit backend bypass, not as a frontend-created exception path.

## Statuses frontend must render

- `PENDING`: request submitted, waiting for tenant-admin decision
- `APPROVED`: request can proceed through the download contract
- `REJECTED`: show reviewer reason and a path back to the originating report
- `EXPIRED`: stale request; user must submit a fresh export request

Example approval row:

```json
{
  "id": "exp-901",
  "originType": "EXPORT_REQUEST",
  "ownerType": "REPORTS",
  "status": "PENDING",
  "reportType": "GST_SUMMARY",
  "createdAt": "2026-03-28T10:22:11Z"
}
```

Example download contract:

```json
{
  "success": true,
  "data": {
    "requestId": 901,
    "status": "APPROVED",
    "reportType": "GST_SUMMARY",
    "parameters": "periodId=3",
    "message": "Export request approved for download"
  }
}
```
