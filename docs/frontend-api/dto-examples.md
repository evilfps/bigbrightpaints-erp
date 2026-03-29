# DTO Examples

These are frontend-shape reminders, not full schema dumps.

## `/api/v1/auth/me`

```json
{
  "success": true,
  "data": {
    "email": "controller@acme.test",
    "displayName": "Tenant Controller",
    "companyCode": "ACME",
    "roles": ["ROLE_ADMIN"],
    "permissions": ["accounting.periods.requestClose", "reports.export.request"],
    "mfaEnabled": true,
    "mustChangePassword": false
  }
}
```

Do not expect `companyId`.

## Tenant onboarding success

```json
{
  "success": true,
  "data": {
    "companyCode": "ACME",
    "adminEmail": "controller@acme.test",
    "seededChartOfAccounts": true,
    "defaultAccountingPeriodCreated": true,
    "tenantAdminProvisioned": true
  }
}
```

Treat the three bootstrap flags as required success truth, not optional
metadata.

## Company default accounts

```json
{
  "success": true,
  "data": {
    "inventoryAccountId": 1101,
    "cogsAccountId": 5101,
    "revenueAccountId": 4101,
    "discountAccountId": 4102,
    "taxAccountId": 2105
  }
}
```

Finished goods may also rely on explicit item metadata fields:

- `fgValuationAccountId`
- `fgCogsAccountId`
- `fgRevenueAccountId`
- `fgDiscountAccountId`
- `fgTaxAccountId`

## Journal create request

```json
{
  "entryDate": "2026-03-28",
  "referenceNumber": "JRNL-UI-2026-00042",
  "memo": "Write-off adjustment",
  "lines": [
    {
      "accountId": 6102,
      "debit": 500.00,
      "credit": 0
    },
    {
      "accountId": 1101,
      "debit": 0,
      "credit": 500.00
    }
  ]
}
```

Public route:

- `POST /api/v1/accounting/journal-entries`

## Journal reversal request

```json
{
  "reversalDate": "2026-03-29",
  "reason": "Wrong inventory adjustment source document",
  "memo": "Reverse and relink to corrected batch",
  "adminOverride": false,
  "relatedEntryIds": [9002, 9003],
  "cascadeRelatedEntries": true
}
```

Public route:

- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`

## Export request create

```json
{
  "reportType": "TRIAL_BALANCE",
  "parameters": "periodId=3&startDate=2026-03-01&endDate=2026-03-31&exportFormat=CSV"
}
```

Public route:

- `POST /api/v1/exports/request`

## Approval inbox row

```json
{
  "id": "approval-114",
  "originType": "PERIOD_CLOSE_REQUEST",
  "ownerType": "ACCOUNTING",
  "status": "PENDING",
  "requestedBy": "controller@acme.test",
  "createdAt": "2026-03-28T10:22:11Z"
}
```

Use this for maker-checker close and export approvals. The inbox list itself is
always `GET /api/v1/admin/approvals`.

## Paginated list response

```json
{
  "success": true,
  "data": {
    "content": [],
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0
  }
}
```
