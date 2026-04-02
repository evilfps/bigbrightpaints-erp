# orchestrator-erp Data Migration Guide (Retired Reference)

> ⚠️ **RETIRED / REFERENCE ONLY**: This guide is retained as an archival snapshot of older onboarding migration notes. Current canonical migration rollout and rollback truth lives in [docs/runbooks/migrations.md](runbooks/migrations.md).
>
> There is **no maintained canonical replacement** for the legacy CSV/Tally appendix below. If you reuse any detail from this file, verify it directly against current source, `openapi.json`, and the runbook before treating it as current truth.

Last reviewed: 2026-04-02

This archival guide covers end-to-end onboarding migration for a new company in the older BigBright ERP framing:

1. Prepare and import opening accounting balances from CSV
2. Prepare and import opening inventory stock from CSV
3. Export and import opening data from Tally Prime XML
4. Resolve import errors quickly
5. Follow the go-live onboarding workflow

---

## 1) CSV Template Formats (with Example Rows)

## 1.1 Opening Balance CSV (`POST /api/v1/accounting/opening-balances`)

**Auth:** `ROLE_ADMIN`
**Upload type:** `multipart/form-data` with part name `file`
**Idempotency:** file content hash (same file bytes replay prior result)

### Required header row

```csv
account_code,account_name,account_type,debit_amount,credit_amount,narration
```

### Field rules

| Column | Required | Rules |
|---|---|---|
| `account_code` | Yes | Must be non-blank. Existing accounts are matched by code (case-insensitive). |
| `account_name` | Conditionally | Required when `account_code` does not already exist (new account auto-create). |
| `account_type` | Yes | One of: `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`. |
| `debit_amount` | Yes | Numeric, >= 0. |
| `credit_amount` | Yes | Numeric, >= 0. |
| `narration` | No | If blank, backend uses `Opening balance import <account_code>`. |

**Row validation:**
- Exactly one side must be > 0 (`debit_amount` XOR `credit_amount`)
- Both cannot be zero
- Both cannot be non-zero

**File-level validation:**
- Sum of all debits must equal sum of all credits, otherwise journal posting is skipped and an unbalanced error is returned.

### Example CSV (balanced)

```csv
account_code,account_name,account_type,debit_amount,credit_amount,narration
BANK-CURRENT,Main Bank Account,ASSET,250000.00,0,Opening bank balance
AR-DEALERS,Trade Debtors,ASSET,180000.00,0,Opening receivables
AP-SUPPLIERS,Trade Creditors,LIABILITY,0,95000.00,Opening payables
OWNER-CAPITAL,Owner Capital,EQUITY,0,335000.00,Opening capital introduced
```

---

## 1.2 Opening Stock CSV (`POST /api/v1/inventory/opening-stock`)

**Auth:** `ROLE_ADMIN` / `ROLE_ACCOUNTING` / `ROLE_FACTORY`
**Upload type:** `multipart/form-data` with part name `file`
**Required replay inputs:** `Idempotency-Key` header plus `openingStockBatchKey` query parameter
**Replay contract:** `Idempotency-Key` is the request replay key, and `openingStockBatchKey` is the opening-stock batch identity. Reuse the original `Idempotency-Key` to replay the same batch result. Use a new `openingStockBatchKey` only for a materially distinct follow-up import, or reverse the prior opening stock before rerunning the same batch from scratch.

> In `prod` profile, opening stock import can be blocked unless `erp.inventory.opening-stock.enabled=true`.

### Recommended header row

```csv
type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at,expiry_date
```

### Accepted aliases

- `type` or `item_type`
- `sku` or `product_code` or `sku_code`
- `name` or `product_name`
- `unit` or `unit_of_measure`
- `batch_code` or `batch`
- `quantity` or `qty`
- `unit_cost` or `cost_per_unit` or `cost`
- `manufactured_at` or `manufacturing_date` or `manufacturingDate` or `batch_date`
- `expiry_date` or `expiryDate` or `expiry`

### Field rules

| Column | Required | Rules |
|---|---|---|
| `type` | Yes | `RAW_MATERIAL` / `FINISHED_GOOD` (also accepts `RM`, `FG`). |
| `sku` | Yes for `FINISHED_GOOD`; optional for `RAW_MATERIAL` | Duplicate SKU in the same CSV is rejected row-wise. |
| `name` | Required when creating new item without SKU lookup | For new raw material without existing SKU, name is mandatory. |
| `unit` / `unit_type` | Required for raw material creation | `unit_type` preferred for raw materials. |
| `batch_code` | No | If omitted, backend auto-generates unique batch code. |
| `quantity` | Yes | Numeric, > 0. |
| `unit_cost` | Yes | Numeric, > 0. |
| `material_type` | No | For raw material: `PRODUCTION` or `PACKAGING`. |
| `manufactured_at` | No | Date format `YYYY-MM-DD`. |
| `expiry_date` | No | Date format `YYYY-MM-DD`. |

### Example CSV (raw material + finished good)

```csv
type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at,expiry_date
RAW_MATERIAL,RM-RESIN-001,Alkyd Resin,KG,KG,RM-OPEN-001,1200,155.50,PRODUCTION,2026-03-01,
RAW_MATERIAL,PKG-CAN-001,1L Tin Can,PCS,PCS,PK-OPEN-001,5000,12.25,PACKAGING,2026-03-01,
FINISHED_GOOD,FG-PAINT-1L,Premium Enamel 1L,L,L,FG-OPEN-001,900,242.80,,2026-03-01,2027-03-01
```

---

## 2) Tally Prime Export Instructions (for XML import)

BigBright Tally endpoint: `POST /api/v1/migration/tally-import` (admin only).

The importer expects Tally XML with:
- `LEDGER` masters (`NAME`, `PARENT/GROUP`)
- Opening voucher ledger entries (`VOUCHER` with opening context and `ALLLEDGERENTRIES.LIST` + `AMOUNT`)

## 2.1 Prepare company period in Tally Prime

1. Open the target company in Tally Prime.
2. Set the migration period using **Alt+F2** (Period), so opening balances are visible for export.
3. Ensure ledger masters are cleaned up (especially group assignments), because group mapping drives ERP account type mapping.

## 2.2 Export ledger masters as XML

1. Go to the ledger/master listing screen in Tally Prime.
2. Press **Alt+E (Export)**.
3. Set **Format** = `XML (Data Interchange)`.
4. Select masters/ledgers export scope (all ledgers recommended for first-time migration).
5. Save/export the XML file.

## 2.3 Export opening vouchers as XML

1. Open voucher export for opening entries (or opening balance voucher report).
2. Press **Alt+E (Export)**.
3. Set **Format** = `XML`.
4. Include opening voucher entries in the selected period.
5. Save/export the XML file.

## 2.4 Upload into BigBright ERP

```bash
curl -X POST "<BASE_URL>/api/v1/migration/tally-import" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -F "file=@tally-opening.xml"
```

### Tally import response highlights

- `ledgersProcessed`
- `mappedLedgers`
- `accountsCreated`
- `openingVoucherEntriesProcessed`
- `openingBalanceRowsProcessed`
- `unmappedGroups[]`
- `unmappedItems[]`
- `errors[]`

Re-uploading the **exact same XML bytes** is replay-safe (idempotent by SHA-256 file hash).

---

## 3) Tally Group → ERP Account Type Mapping Reference

The mapping below is implemented in the Tally import adapter.

| Tally Group | ERP `AccountType` |
|---|---|
| `SUNDRY DEBTORS` | `ASSET` |
| `SUNDRY CREDITORS` | `LIABILITY` |
| `BANK ACCOUNTS` | `ASSET` |
| `BANK OD A/C` | `LIABILITY` |
| `BANK OCC A/C` | `LIABILITY` |
| `CASH-IN-HAND` | `ASSET` |
| `CAPITAL ACCOUNT` | `EQUITY` |
| `RESERVES & SURPLUS` | `EQUITY` |
| `CURRENT ASSETS` | `ASSET` |
| `CURRENT LIABILITIES` | `LIABILITY` |
| `FIXED ASSETS` | `ASSET` |
| `LOANS (LIABILITY)` | `LIABILITY` |
| `LOANS & ADVANCES (ASSET)` | `ASSET` |
| `DUTIES & TAXES` | `LIABILITY` |
| `SALES ACCOUNTS` | `REVENUE` |
| `PURCHASE ACCOUNTS` | `EXPENSE` |
| `INDIRECT INCOMES` | `REVENUE` |
| `INDIRECT EXPENSES` | `EXPENSE` |
| `DIRECT INCOMES` | `REVENUE` |
| `DIRECT EXPENSES` | `EXPENSE` |
| `BRANCH / DIVISIONS` | `ASSET` |
| `SUSPENSE A/C` | `ASSET` |
| `PROVISIONS` | `LIABILITY` |
| `SECURED LOANS` | `LIABILITY` |
| `UNSECURED LOANS` | `LIABILITY` |

If a group is not listed, it is returned in `unmappedGroups[]` and needs manual mapping cleanup before retry.

---

## 4) Error Handling Guide (What errors mean and how to fix)

## 4.1 Opening balance CSV errors

| Error pattern | Meaning | Fix |
|---|---|---|
| `CSV file is required` | Empty or missing file upload | Attach a non-empty CSV file in `file` part |
| `CSV is missing required headers` | Header mismatch | Use exact required headers |
| `Invalid account_type` | Unsupported value | Use only `ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE` |
| `Invalid numeric value for debit_amount/credit_amount` | Non-numeric amount | Use plain decimal values (e.g., `1250.00`) |
| `... cannot be negative` | Negative amounts in debit/credit | Use positive values; choose side with debit/credit columns |
| `Either debit_amount or credit_amount must be greater than zero` | Both are 0 | Put value on one side |
| `Both debit_amount and credit_amount cannot be non-zero` | Both sides filled | Keep one side only |
| `account_name is required for new account code` | New code with blank name | Fill `account_name` |
| `Account mapping mismatch for code ...` | Existing account type differs from CSV type | Correct CSV type or account master |
| `Import totals are unbalanced` | Total debit != total credit | Correct CSV totals before re-upload |

## 4.2 Opening stock CSV errors

| Error pattern | Meaning | Fix |
|---|---|---|
| `Opening stock import is disabled...` | Migration disabled in prod | Enable `erp.inventory.opening-stock.enabled=true` for migration window |
| `openingStockBatchKey is required...` | Missing explicit batch identity | Send a non-empty `openingStockBatchKey` query parameter |
| `Idempotency key exceeds 128 characters` | Header key too long | Use shorter key |
| `Opening stock batch key already exists...` | A fresh `Idempotency-Key` tried to reuse an already-processed batch | Reuse the original `Idempotency-Key` for replay, or reverse the old opening stock before rerunning that batch |
| `Idempotency key already used with different openingStockBatchKey` | Same request replay key was reused for a different batch identity | Keep the original `openingStockBatchKey` when retrying that request, or use a new `Idempotency-Key` for a distinct batch |
| `Duplicate SKU in import file` | Same SKU repeated in one CSV | Keep each SKU once per file |
| `Unknown type` | Invalid `type` value | Use `RAW_MATERIAL` / `FINISHED_GOOD` (or `RM`/`FG`) |
| `Unknown material_type` | Invalid material type | Use `PRODUCTION` or `PACKAGING` |
| `Invalid numeric value` | Bad `quantity`/`unit_cost` | Use numeric values only |
| `Invalid date value ... expected YYYY-MM-DD` | Bad date format | Use ISO format (e.g., `2026-03-01`) |
| `... missing an inventory account` | Item/account setup incomplete | Configure valuation/inventory account and retry |
| `Batch code already exists...` | Duplicate batch code for item | Change batch code or omit to auto-generate |

## 4.3 Tally XML import errors

| Response field / error | Meaning | Fix |
|---|---|---|
| `Invalid Tally XML format` | XML parse failed | Re-export XML from Tally, verify file integrity |
| `Invalid amount in Tally XML` | Non-numeric amount token | Clean source amount values and re-export |
| `unmappedGroups[]` | Ledger groups not mapped to ERP account type | Reclassify those ledgers to supported groups (or add mapping in backend) |
| `unmappedItems[]` | Voucher ledger lines could not resolve to account | Fix ledger master/group or create matching accounts, then re-import |
| `errors[].context=ledger:*` | Ledger creation/type mismatch issues | Correct group/type consistency for that ledger |
| `errors[].context=opening-row:*` | Opening row could not map/post | Fix ledger/group and opening amount data |

## 4.4 Partial success behavior

All three imports are designed for migration resilience:
- Row-level failures are reported in `errors[]`
- Valid rows continue processing
- Replaying the same opening-stock batch requires the original `Idempotency-Key`
- Reusing an existing `openingStockBatchKey` under a fresh `Idempotency-Key` is rejected before duplicate stock or journal posting

---

## 5) Step-by-Step Onboarding Workflow (Create company → migrate → verify → go live)

## Step 1: Create tenant/company with CoA template

1. (Optional) List templates:
   - `GET /api/v1/superadmin/tenants/coa-templates`
2. Create company and seed chart of accounts:
   - `POST /api/v1/superadmin/tenants/onboard`

Example request:

```json
{
  "name": "Acme Paints Pvt Ltd",
  "code": "ACME",
  "timezone": "Asia/Kolkata",
  "defaultGstRate": 18.0,
  "maxActiveUsers": 100,
  "maxApiRequests": 100000,
  "maxStorageBytes": 10737418240,
  "maxConcurrentUsers": 30,
  "softLimitEnabled": true,
  "hardLimitEnabled": true,
  "firstAdminEmail": "admin@acmepaints.in",
  "firstAdminDisplayName": "Acme Admin",
  "coaTemplateCode": "MANUFACTURING"
}
```

Supported template codes:
- `INDIAN_STANDARD`
- `MANUFACTURING`
- `GENERIC`

---

## Step 2: Import opening balances (CoA + opening numbers)

1. Prepare opening-balance CSV with the template from section 1.1.
2. Upload:

```bash
curl -X POST "<BASE_URL>/api/v1/accounting/opening-balances" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -F "file=@opening-balances.csv"
```

3. Resolve `errors[]` if present and re-upload corrected file.

---

## Step 3: Import opening stock

1. Prepare opening-stock CSV using section 1.2 format.
2. Upload:

```bash
curl -X POST "<BASE_URL>/api/v1/inventory/opening-stock?openingStockBatchKey=open-stock-batch-2026-03-01" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Idempotency-Key: open-stock-2026-03-01" \
  -F "file=@opening-stock.csv"
```

3. Check import history/audit summary:
   - `GET /api/v1/inventory/opening-stock?page=0&size=20`

---

## Step 4: (Optional) Import from Tally XML

If migrating from Tally Prime, follow section 2 export steps and upload XML to:
- `POST /api/v1/migration/tally-import`

Then resolve `unmappedGroups[]`, `unmappedItems[]`, and `errors[]` until clean.

---

## Step 5: Verify trial balance

Run trial balance after imports to confirm accounting integrity.

- Primary report endpoint:
  - `GET /api/v1/reports/trial-balance?periodId=<ID>`
  - or `GET /api/v1/reports/trial-balance?date=YYYY-MM-DD`
- Accounting as-of snapshot:
  - `GET /api/v1/accounting/trial-balance/as-of?date=YYYY-MM-DD`

Expected verification:
- `totalDebit == totalCredit`
- `balanced = true`
- Opening balances and inventory capitalization entries are visible

---

## Step 6: Go-live checklist

Before first live transaction:

- [ ] Company onboarding completed with correct template
- [ ] Opening balance import completed with no unresolved `errors[]`
- [ ] Opening stock import completed; history reviewed
- [ ] (If applicable) Tally import completed with no unresolved unmapped groups/items
- [ ] Trial balance verified (`balanced=true`)
- [ ] Admin credentials tested and business users activated
- [ ] First sale/purchase dry run completed successfully

When all checks pass, proceed to live operations.
