# BigBright Paints ERP — Purchasing Domain Deep Investigation

---

## 1. Module Structure

```
erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/
├── controller/
│   ├── PurchasingWorkflowController.java      # PO + GRN endpoints
│   ├── RawMaterialPurchaseController.java     # Purchase invoice + returns endpoints
│   └── SupplierController.java                # Supplier CRUD + lifecycle
├── domain/
│   ├── PurchaseOrder.java                     # Aggregate root: PO header
│   ├── PurchaseOrderLine.java                 # PO line items
│   ├── PurchaseOrderStatus.java               # Enum: PO state machine states
│   ├── PurchaseOrderStatusHistory.java        # Audit trail for PO transitions
│   ├── PurchaseOrderRepository.java           # JPA repo with pessimistic locks
│   ├── PurchaseOrderStatusHistoryRepository.java
│   ├── GoodsReceipt.java                      # Aggregate root: GRN header
│   ├── GoodsReceiptLine.java                  # GRN line items (batch-linked)
│   ├── GoodsReceiptStatus.java                # Enum: GRN states
│   ├── GoodsReceiptRepository.java            # JPA repo with idempotency support
│   ├── RawMaterialPurchase.java               # Aggregate root: Purchase invoice
│   ├── RawMaterialPurchaseLine.java           # Invoice lines with tax breakdown
│   ├── RawMaterialPurchaseRepository.java     # JPA repo with settlement queries
│   ├── Supplier.java                          # Aggregate root: Vendor master
│   ├── SupplierStatus.java                    # Enum: Supplier lifecycle states
│   ├── SupplierPaymentTerms.java              # Enum: NET_30/60/90
│   └── SupplierRepository.java
├── dto/
│   ├── PurchaseOrderRequest.java
│   ├── PurchaseOrderResponse.java
│   ├── PurchaseOrderLineRequest.java
│   ├── PurchaseOrderLineResponse.java
│   ├── PurchaseOrderVoidRequest.java
│   ├── PurchaseOrderStatusHistoryResponse.java
│   ├── GoodsReceiptRequest.java
│   ├── GoodsReceiptResponse.java
│   ├── GoodsReceiptLineRequest.java
│   ├── GoodsReceiptLineResponse.java
│   ├── RawMaterialPurchaseRequest.java
│   ├── RawMaterialPurchaseResponse.java
│   ├── RawMaterialPurchaseLineRequest.java
│   ├── RawMaterialPurchaseLineResponse.java
│   ├── PurchaseReturnRequest.java
│   ├── PurchaseReturnPreviewDto.java
│   ├── SupplierRequest.java
│   └── SupplierResponse.java
└── service/
    ├── PurchasingService.java                 # Facade orchestrating sub-services
    ├── PurchaseOrderService.java              # PO CRUD + state transitions
    ├── GoodsReceiptService.java               # GRN creation with idempotency
    ├── PurchaseInvoiceService.java            # Thin wrapper around engine
    ├── PurchaseInvoiceEngine.java             # Core invoice posting logic
    ├── PurchaseReturnService.java             # Purchase return + stock reversal
    ├── PurchaseReturnAllocationService.java   # Return qty allocation per line
    ├── PurchaseTaxPolicy.java                 # GST/non-GST tax mode enforcement
    ├── PurchaseResponseMapper.java            # Entity → DTO mapping with settlement links
    ├── SupplierService.java                   # Supplier CRUD + lifecycle
    ├── SupplierApprovalPolicy.java            # Maker-checker approval policy
    ├── SupplierApprovalDecision.java          # Approval decision record
    └── SupplierApprovalReasonCode.java        # SUPPLIER_EXCEPTION, SETTLEMENT_OVERRIDE
```

### Related modules the purchasing domain integrates with:
- **`inventory`** — `RawMaterialService`, `RawMaterialRepository`, `RawMaterialBatchRepository`, `RawMaterialMovementRepository`
- **`accounting`** — `AccountingFacade`, `AccountingPeriodService`, `GstService`, `ReferenceNumberService`, `SupplierLedgerService`, `SettlementService`
- **`company`** — `CompanyContextService`, `CompanyEntityLookup`

---

## 2. Entities & Models

### 2.1 Supplier

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK, auto-generated |
| publicId | UUID | External-safe identifier |
| company | Company | FK, multi-tenant |
| code | String | Unique per company, auto-generated from name if blank |
| name | String | Required |
| status | SupplierStatus | PENDING → APPROVED → ACTIVE → SUSPENDED |
| email | String | Optional |
| phone | String | Optional |
| address | String | Optional |
| gstNumber | String | Validated 15-char GSTIN pattern `^[0-9]{2}[A-Z0-9]{13}$` |
| stateCode | String | 2-char Indian state code |
| gstRegistrationType | GstRegistrationType | UNREGISTERED, REGULAR, COMPOSITION, etc. |
| paymentTerms | SupplierPaymentTerms | NET_30, NET_60, NET_90 (default NET_30) |
| bankAccountNameEncrypted | String | AES encrypted via CryptoService |
| bankAccountNumberEncrypted | String | AES encrypted |
| bankIfscEncrypted | String | AES encrypted |
| bankBranchEncrypted | String | AES encrypted |
| creditLimit | BigDecimal | Default ZERO, validated @PositiveOrZero |
| payableAccount | Account | FK to accounting.Account, auto-created as "AP-{code}" |
| createdAt | Instant | Auto-set via CompanyTime |

**Key behaviours:**
- `requireTransactionalUsage(action)` — Blocks PO/GRN/invoice creation unless status is ACTIVE
- `getResolvedStatus()` — Null-safe resolution to PENDING
- Aliases: "INACTIVE"/"DISABLED" → SUSPENDED, "NEW" → PENDING
- On creation: auto-creates a liability account `AP-{code}` under the `AP` control account

**Relationships:**
- `Supplier 1:N PurchaseOrder`
- `Supplier 1:N GoodsReceipt`
- `Supplier 1:N RawMaterialPurchase`
- `Supplier N:1 Account` (payableAccount)

### 2.2 PurchaseOrder

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| publicId | UUID | External-safe |
| company | Company | FK, multi-tenant |
| supplier | Supplier | FK, required |
| orderNumber | String | Unique per company (case-insensitive) |
| orderDate | LocalDate | Required |
| status | PurchaseOrderStatus | State machine (see §4) |
| memo | String | Optional |
| createdAt / updatedAt | Instant | Auto-managed |
| lines | List\<PurchaseOrderLine\> | Cascade ALL, orphan removal |

**Status aliases for backward compat:** OPEN→APPROVED, PARTIAL→PARTIALLY_RECEIVED, RECEIVED→FULLY_RECEIVED, CANCELLED/CANCELED→VOID

### 2.3 PurchaseOrderLine

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| purchaseOrder | PurchaseOrder | FK |
| rawMaterial | RawMaterial | FK, required |
| quantity | BigDecimal | Must be > 0 |
| unit | String | Falls back to rawMaterial.unitType |
| costPerUnit | BigDecimal | Must be > 0 |
| lineTotal | BigDecimal | = quantity × costPerUnit (currency rounded) |
| notes | String | Optional |

### 2.4 PurchaseOrderStatusHistory

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| company | Company | FK |
| purchaseOrder | PurchaseOrder | FK |
| fromStatus | String | Nullable (for initial creation) |
| toStatus | String | Required |
| reasonCode | String | E.g., PURCHASE_ORDER_CREATED, PURCHASE_ORDER_APPROVED, PURCHASE_ORDER_CLOSED |
| reason | String | Free-text |
| changedBy | String | Resolved via SecurityActorResolver |
| changedAt | Instant | Auto-set via CompanyTime |

### 2.5 GoodsReceipt

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| publicId | UUID | External-safe |
| company | Company | FK |
| supplier | Supplier | FK |
| purchaseOrder | PurchaseOrder | FK, required |
| receiptNumber | String | Unique per company |
| receiptDate | LocalDate | Required, validated against open accounting period |
| idempotencyKey | String(128) | Required for dedup |
| idempotencyHash | String(64) | SHA-256 signature of request payload |
| status | GoodsReceiptStatus | PARTIAL, RECEIVED, INVOICED |
| memo | String | Optional |
| createdAt / updatedAt | Instant | Auto-managed |
| lines | List\<GoodsReceiptLine\> | Cascade ALL, orphan removal |

### 2.6 GoodsReceiptLine

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| goodsReceipt | GoodsReceipt | FK |
| rawMaterial | RawMaterial | FK, required |
| rawMaterialBatch | RawMaterialBatch | FK, set after batch creation |
| batchCode | String | Required, defaults to `{sku}-{receiptNumber}` |
| quantity | BigDecimal | Must be > 0 |
| unit | String | Must match PO unit |
| costPerUnit | BigDecimal | Must be > 0 |
| lineTotal | BigDecimal | = quantity × costPerUnit |
| notes | String | Optional |

### 2.7 RawMaterialPurchase (Purchase Invoice)

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| publicId | UUID | External-safe |
| company | Company | FK |
| supplier | Supplier | FK |
| journalEntry | JournalEntry | FK, linked after posting |
| purchaseOrder | PurchaseOrder | FK |
| goodsReceipt | GoodsReceipt | FK, 1:1 (validated unique) |
| invoiceNumber | String | Unique per company |
| invoiceDate | LocalDate | Required |
| totalAmount | BigDecimal | = sum(lineNet) + taxAmount |
| taxAmount | BigDecimal | Total GST |
| outstandingAmount | BigDecimal | Decreases with payments/returns |
| status | String | POSTED, PARTIAL, PAID, VOID, REVERSED |
| memo | String | Optional |
| createdAt / updatedAt | Instant | Auto-managed |
| lines | List\<RawMaterialPurchaseLine\> | Cascade ALL, orphan removal |

### 2.8 RawMaterialPurchaseLine

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| purchase | RawMaterialPurchase | FK |
| rawMaterial | RawMaterial | FK |
| rawMaterialBatch | RawMaterialBatch | FK |
| batchCode | String | Required |
| quantity | BigDecimal | Must match GRN quantity |
| returnedQuantity | BigDecimal | Accumulated returns, default ZERO |
| unit | String | Must match GRN unit |
| costPerUnit | BigDecimal | Must match GRN cost within 0.01 tolerance |
| lineTotal | BigDecimal | Net amount (excl. tax or incl. tax after backing out) |
| taxRate | BigDecimal | GST % (null if top-level tax provided) |
| taxAmount | BigDecimal | Line-level tax |
| cgstAmount | BigDecimal | Central GST component |
| sgstAmount | BigDecimal | State GST component |
| igstAmount | BigDecimal | Integrated GST component |
| notes | String | Optional |

### 2.9 Enums

**PurchaseOrderStatus:** DRAFT → APPROVED → PARTIALLY_RECEIVED → FULLY_RECEIVED → INVOICED → CLOSED, plus VOID (terminal)

**GoodsReceiptStatus:** PARTIAL, RECEIVED, INVOICED

**SupplierStatus:** PENDING → APPROVED → ACTIVE → SUSPENDED

**SupplierPaymentTerms:** NET_30(30), NET_60(60), NET_90(90)

---

## 3. API Endpoints

### 3.1 Supplier Management (`/api/v1/suppliers`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/suppliers` | ADMIN, ACCOUNTING, FACTORY | List all suppliers (with payable account + ledger balance) |
| GET | `/api/v1/suppliers/{id}` | ADMIN, ACCOUNTING, FACTORY | Get supplier detail + balance |
| POST | `/api/v1/suppliers` | ADMIN, ACCOUNTING | Create supplier (status=PENDING, auto-creates AP account) |
| PUT | `/api/v1/suppliers/{id}` | ADMIN, ACCOUNTING | Update supplier details |
| POST | `/api/v1/suppliers/{id}/approve` | ADMIN, ACCOUNTING | PENDING → APPROVED |
| POST | `/api/v1/suppliers/{id}/activate` | ADMIN, ACCOUNTING | APPROVED/SUSPENDED → ACTIVE |
| POST | `/api/v1/suppliers/{id}/suspend` | ADMIN, ACCOUNTING | ACTIVE → SUSPENDED |

### 3.2 Purchase Order Workflow (`/api/v1/purchasing`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/purchasing/purchase-orders` | ADMIN, ACCOUNTING | List POs (optional `?supplierId=`) |
| GET | `/api/v1/purchasing/purchase-orders/{id}` | ADMIN, ACCOUNTING | Get PO detail |
| POST | `/api/v1/purchasing/purchase-orders` | ADMIN, ACCOUNTING | Create PO (status=DRAFT) |
| POST | `/api/v1/purchasing/purchase-orders/{id}/approve` | ADMIN, ACCOUNTING | DRAFT → APPROVED |
| POST | `/api/v1/purchasing/purchase-orders/{id}/void` | ADMIN, ACCOUNTING | → VOID (requires reasonCode) |
| POST | `/api/v1/purchasing/purchase-orders/{id}/close` | ADMIN, ACCOUNTING | → CLOSED |
| GET | `/api/v1/purchasing/purchase-orders/{id}/timeline` | ADMIN, ACCOUNTING | Get status history timeline |

### 3.3 Goods Receipts (`/api/v1/purchasing/goods-receipts`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/purchasing/goods-receipts` | ADMIN, ACCOUNTING | List GRNs (optional `?supplierId=`) |
| GET | `/api/v1/purchasing/goods-receipts/{id}` | ADMIN, ACCOUNTING | Get GRN detail |
| POST | `/api/v1/purchasing/goods-receipts` | ADMIN, ACCOUNTING | Create GRN (requires `Idempotency-Key` header) |

### 3.4 Purchase Invoices (`/api/v1/purchasing/raw-material-purchases`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/purchasing/raw-material-purchases` | ADMIN, ACCOUNTING | List invoices (optional `?supplierId=`) |
| GET | `/api/v1/purchasing/raw-material-purchases/{id}` | ADMIN, ACCOUNTING | Get invoice detail |
| POST | `/api/v1/purchasing/raw-material-purchases` | ADMIN, ACCOUNTING | Create invoice (posts journal entry) |

### 3.5 Purchase Returns (`/api/v1/purchasing/raw-material-purchases/returns`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/purchasing/raw-material-purchases/returns/preview` | ADMIN, ACCOUNTING | Preview return (dry run) |
| POST | `/api/v1/purchasing/raw-material-purchases/returns` | ADMIN, ACCOUNTING | Execute return |

### 3.6 Vendor Payments (via Accounting module, `/api/v1/accounting`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/accounting/settlements/suppliers` | — | Settle supplier invoices (payment allocation) |
| POST | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | — | Auto-settle supplier by FIFO |
| GET | `/api/v1/accounting/statements/suppliers/{supplierId}` | — | Supplier statement |
| GET | `/api/v1/accounting/aging/suppliers/{supplierId}` | — | Supplier aging report |
| GET | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | — | Statement PDF |
| GET | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | — | Aging PDF |

---

## 4. State Machines

### 4.1 Purchase Order State Machine

```
DRAFT ──approve──▸ APPROVED ──receive(partial)──▸ PARTIALLY_RECEIVED
  │                                      │                    │
  │void                                  │void                │receive(all)
  ▼                                      ▼                    ▼
 VOID                                  VOID             FULLY_RECEIVED
                                                              │
                                                         invoice
                                                              │
                                                              ▼
                                                          INVOICED
                                                              │
                                                           close
                                                              │
                                                              ▼
                                                           CLOSED (terminal)

VOID ── (terminal)
CLOSED ── (terminal)
```

**Valid transitions (from `isValidTransition`):**
- DRAFT → APPROVED, VOID
- APPROVED → PARTIALLY_RECEIVED, FULLY_RECEIVED, VOID
- PARTIALLY_RECEIVED → FULLY_RECEIVED
- FULLY_RECEIVED → INVOICED
- INVOICED → CLOSED
- CLOSED → *(none, terminal)*
- VOID → *(none, terminal)*

**Status history tracking:** Every transition is recorded in `purchase_order_status_history` with fromStatus, toStatus, reasonCode, reason, changedBy, changedAt.

**Reason codes used:**
- `PURCHASE_ORDER_CREATED`
- `PURCHASE_ORDER_APPROVED`
- `PURCHASE_ORDER_VOID` (user-provided)
- `GOODS_RECEIPT_PARTIAL`
- `GOODS_RECEIPT_COMPLETED`
- `PURCHASE_ORDER_INVOICED`
- `PURCHASE_ORDER_CLOSED`

### 4.2 Goods Receipt Status

```
PARTIAL ──▸ RECEIVED ──▸ INVOICED
```

- Initial status: RECEIVED (if all PO lines fulfilled) or PARTIAL (if partial)
- INVOICED set after purchase invoice is posted against this GRN

### 4.3 Supplier Lifecycle

```
PENDING ──approve──▸ APPROVED ──activate──▸ ACTIVE ◄──reactivate──┐
                                               │                   │
                                            suspend                │
                                               │                   │
                                               ▼───────────────────┘
                                            SUSPENDED
```

- Only ACTIVE suppliers can be used in PO/GRN/invoice creation
- `requireTransactionalUsage()` throws if status ≠ ACTIVE with descriptive message per status

### 4.4 Purchase Invoice Status (RawMaterialPurchase)

Status is a String field (not enum):
- `POSTED` — After journal entry creation
- `PARTIAL` — After partial payment/settlement
- `PAID` — When outstanding drops to zero via payments
- `VOID` — When all quantities fully returned OR manually voided
- `REVERSED` — Not directly set in purchasing, likely set by accounting

---

## 5. Business Rules & Validations

### 5.1 Purchase Order Creation
- Supplier must be ACTIVE (`requireTransactionalUsage`)
- Order number must be unique per company (case-insensitive)
- Raw materials must be active (locked via `lockActiveRawMaterial`)
- No duplicate raw material lines in same PO
- All quantities and costPerUnit must be > 0
- Unit defaults to rawMaterial.unitType if not provided

### 5.2 Purchase Order Status Transitions
- DRAFT can only be approved or voided
- APPROVED can be partially received, fully received, or voided
- No self-transition allowed (throws `BUSINESS_INVALID_STATE`)
- Invalid transitions throw `BUSINESS_INVALID_STATE` with from/to details
- Void requires `reasonCode` (non-blank)
- Terminal states (CLOSED, VOID) cannot transition

### 5.3 Goods Receipt Creation
- **Idempotency required**: `Idempotency-Key` header mandatory; body `idempotencyKey` takes precedence
- Legacy `X-Idempotency-Key` header explicitly rejected with error
- Request payload hashed (SHA-256) for dedup verification
- Accounting period must be open for receipt date
- Purchase order must be in APPROVED or PARTIALLY_RECEIVED status
- Supplier must be ACTIVE
- Receipt number must be unique per company
- Each receipt line must correspond to a PO line (raw material must be in PO)
- Receipt quantity per material cannot exceed remaining PO quantity
- Receipt unit must match PO unit
- No duplicate raw material lines in same receipt
- If all PO lines fulfilled → status=RECEIVED, PO→FULLY_RECEIVED
- If partial → status=PARTIAL, PO→PARTIALLY_RECEIVED
- Cannot create GRN if PO already fully received (all quantities met)

### 5.4 Purchase Invoice Creation (PurchaseInvoiceEngine)
- Supplier must be ACTIVE with payable account
- Invoice number must be unique per company
- Goods receipt must exist, belong to supplier, not already invoiced
- Goods receipt must have a linked purchase order
- 1:1 relationship: each GRN can only have one purchase invoice
- Invoice must include ALL goods receipt lines (no partial invoicing)
- Invoice quantities must exactly match GRN quantities
- Unit costs must match GRN costs within 0.01 tolerance
- Units must match GRN units
- Raw material must have inventory account mapping
- Goods receipt must have stock movements already recorded
- All GRN lines must have batch linkages
- Cannot mix taxAmount (top-level) with line-level taxRate/taxInclusive
- GST and non-GST materials cannot be mixed in same invoice
- Tax-inclusive lines require positive GST rate
- GST rate max: 28%
- After posting: GRN status → INVOICED, PO status → INVOICED → CLOSED (if all GRNs invoiced)

### 5.5 Purchase Return
- Supplier must be ACTIVE
- Purchase must be POSTED (not DRAFT/VOID/REVERSED, must have journal entry)
- Material must be in the original purchase
- Material must have inventory account mapping
- Quantity must be > 0 and ≤ remaining returnable quantity
- Return reduces raw material stock (`deductStockIfSufficient`)
- Return reverses from batches FIFO
- Outstanding amount is reduced by return total (cannot go negative)
- If all quantities returned AND outstanding = 0 → status = VOID
- Journal correction type set to REVERSAL, correction reason = PURCHASE_RETURN
- Return memo auto-generated: "{reason} - {material} to {supplier}"
- Reference number auto-generated if not provided
- Existing movements for same reference are checked for idempotency

### 5.6 Supplier Validations
- GST number: 15-char pattern `^[0-9]{2}[A-Z0-9]{13}$`
- State code: exactly 2 characters
- Email: validated via `@Email`
- Name: required, max 64 chars
- Code: auto-generated from name (normalized, ASCII, uppercase) if blank
- Bank details: encrypted at rest via CryptoService
- Credit limit: must be ≥ 0
- Payable account: auto-created as `AP-{code}` under `AP` control account

### 5.7 Approval Decision Rules
- Maker and checker must be different users
- `approvalId`, `makerUserId`, `checkerUserId` required
- `reasonCode` required (SUPPLIER_EXCEPTION or SETTLEMENT_OVERRIDE)
- `auditMetadata` must contain `ticket` and an approval source key
- Supplier exception approval must use SUPPLIER_EXCEPTION reason code
- Settlement override approval must use SETTLEMENT_OVERRIDE reason code

---

## 6. Inventory Effects

### 6.1 Goods Receipt (Stock Inward)
When a Goods Receipt is created:
1. For each line, `RawMaterialService.recordReceipt()` is called
2. A `RawMaterialBatch` is created with: batchCode, quantity, unit, costPerUnit, supplier, manufacturing/expiry dates
3. `RawMaterial.currentStock` is incremented by the received quantity
4. A `RawMaterialMovement` is recorded (type=RECEIPT) with referenceType=GOODS_RECEIPT and referenceId=receiptNumber
5. The movement's `journalEntryId` may be set at this stage if journal posting occurs

**Note:** The goods receipt step calls `recordReceipt` with `postJournal=false`, so the inventory movement is recorded but no journal entry is posted at this stage. Journal posting happens at invoice time.

### 6.2 Purchase Invoice (Movement → Journal Linkage)
When a purchase invoice is created:
1. The engine verifies all GRN stock movements exist and are ready for invoicing
2. It verifies all GRN lines have batch linkages
3. It verifies movement material IDs match GRN material IDs exactly
4. After journal entry posting, all GRN movements are linked to the journal entry ID

### 6.3 Purchase Return (Stock Reversal)
1. `rawMaterialRepository.deductStockIfSufficient(materialId, quantity)` — reduces currentStock
2. Batch quantities reversed using FIFO from available batches
3. `rawMaterialBatchRepository.deductQuantityIfSufficient(batchId, take)` — per-batch deduction
4. `RawMaterialMovement` created with type=RETURN, referenceType=PURCHASE_RETURN
5. Movement linked to the return journal entry

---

## 7. Accounting Effects

### 7.1 Purchase Invoice Posting (via AccountingFacade)
When a purchase invoice is created, `AccountingFacade.postPurchaseJournal()` is called:

**Journal Entry Structure:**
```
Dr  Inventory Account (per raw material)   — sum of line net amounts
Dr  Input Tax Account (if GST applicable)  — taxAmount
    Cr  Supplier Payable Account           — totalAmount (net + tax)
```

- The journal entry is created with `REPEATABLE_READ` isolation and optimistic locking retry (3 attempts)
- Reference number auto-generated via `ReferenceNumberService.purchaseReference()`
- Entry date = invoice date (or company clock today if null)

### 7.2 GST Breakdown in Journal
The journal records GST components separately:

**Intra-state (same state):**
```
Dr  Input CGST Account   — CGST amount
Dr  Input SGST Account   — SGST amount
```

**Inter-state (different states):**
```
Dr  Input IGST Account   — IGST amount
```

Tax type resolution based on company state code vs supplier state code.

### 7.3 Purchase Return Posting
**Journal Entry Structure (reversal):**
```
Dr  Supplier Payable Account    — totalAmount (net + tax)
    Cr  Inventory Account       — net amount
    Cr  Input Tax Account       — tax amount (if applicable)
```

- Journal marked as `correctionType = REVERSAL`
- `correctionReason = "PURCHASE_RETURN"`
- `sourceModule = "PURCHASING_RETURN"`
- `sourceReference = purchaseInvoiceNumber`
- GST reversal follows same intra/inter-state split

### 7.4 Vendor Payment Settlement (via SettlementService)
`POST /api/v1/accounting/settlements/suppliers`:

```
Dr  Supplier Payable Account    — settlement amount
    Cr  Cash/Bank Account       — settlement amount
    Cr  Discount Account        — (if early payment discount)
```

- Supports manual and auto-settlement
- Settlement allocations tracked in `PartnerSettlementAllocation`
- Open purchases locked for settlement in FIFO order (`lockOpenPurchasesForSettlement`)
- Updates `RawMaterialPurchase.outstandingAmount`

---

## 8. Tax Effects

### 8.1 Tax Modes
The `PurchaseTaxPolicy` enforces two modes per invoice:
- **GST mode** — All materials in invoice have `isGstApplicable=true`
- **NON_GST mode** — All materials have `isGstApplicable=false`

**Cannot mix GST and non-GST materials** in the same invoice.

### 8.2 Tax Calculation Methods
**Top-level tax (legacy):** `taxAmount` provided at invoice level
- Must not combine with line-level `taxRate` or `taxInclusive`
- Allocated proportionally across lines based on net amounts (last line gets remainder)
- If provided as zero with taxable lines → rejected

**Line-level tax (preferred):**
- `taxRate` per line: resolved from line request → raw material GST rate → company default GST rate
- `taxInclusive=true`: Backs tax out from gross: `net = gross / (1 + rate/100)`
- `taxInclusive=false` (default): Calculates tax on net: `tax = net × rate / 100`
- GST rate validated: 0–28%, rounded to 4 decimal places

### 8.3 GST Component Tracking
Each invoice line stores:
- `cgstAmount` — Central GST
- `sgstAmount` — State GST
- `igstAmount` — Integrated GST
- `taxRate` — Applicable rate
- `taxAmount` — Total tax for line

Split logic:
- **Same state** (company.stateCode == supplier.stateCode): CGST + SGST (each = tax/2)
- **Different state**: IGST (= full tax)

### 8.4 Return Tax Computation
- If purchase has line-level tax data: `taxPerUnit = lineTax / lineQty`, returnTax = taxPerUnit × returnQty
- Otherwise: allocated proportionally from total purchase tax based on material's share of invoice total

---

## 9. Events Published

**No direct domain events** are published from the purchasing module itself. The purchasing module does not use Spring's `ApplicationEventPublisher` or a custom event store.

However, the **accounting module** publishes events when purchasing-related journal entries are created:
- `JOURNAL_ENTRY_CREATED` — When purchase invoice journal is posted
- `JOURNAL_ENTRY_POSTED` — When journal is finalized
- `JOURNAL_ENTRY_REVERSED` — When purchase return reversal is posted
- `SUPPLIER_PAYMENT_POSTED` — When vendor payment is recorded
- `SETTLEMENT_ALLOCATED` — When payment is allocated to an invoice

These events are stored in `AccountingEventStore` and `AccountingEventRepository`.

---

## 10. Approval Workflows

### 10.1 Supplier Approval (3-stage lifecycle)
1. **Create** → status=PENDING (visible for reference, not usable)
2. **Approve** → status=APPROVED (requires ADMIN/ACCOUNTING role; must be from PENDING)
3. **Activate** → status=ACTIVE (from APPROVED or SUSPENDED; now usable in transactions)

The supplier can be suspended from ACTIVE and later re-activated.

### 10.2 Purchase Order Approval
- Created in DRAFT status
- Requires explicit approve action: `POST /purchase-orders/{id}/approve`
- Only DRAFT → APPROVED is valid for approval
- Approval requires ADMIN or ACCOUNTING role

### 10.3 Supplier Exception Approval (Maker-Checker)
The `SupplierApprovalPolicy` enforces a maker-checker pattern for two scenarios:
- **Supplier Exception** (`SUPPLIER_EXCEPTION` reason code) — For overriding supplier-related constraints
- **Settlement Override** (`SETTLEMENT_OVERRIDE` reason code) — For overriding settlement rules

Both require:
- Different maker and checker users
- Full audit metadata with ticket number and approval source
- Immutable audit trail

### 10.4 Void Approval
- Voiding a PO requires a `reasonCode` (validated as non-blank)
- The reason code and optional free-text reason are recorded in status history

---

## 11. Integration Points

### 11.1 Purchasing → Inventory
| Trigger | Integration |
|---------|-------------|
| Goods Receipt creation | `RawMaterialService.recordReceipt()` → creates batch, increments stock, records movement |
| Purchase Return | `RawMaterialRepository.deductStockIfSufficient()` + FIFO batch reversal |

### 11.2 Purchasing → Accounting
| Trigger | Integration |
|---------|-------------|
| Purchase Invoice creation | `AccountingFacade.postPurchaseJournal()` → Dr Inventory+Tax / Cr Payable |
| Purchase Return | `AccountingFacade.postPurchaseReturn()` → Dr Payable / Cr Inventory+Tax |
| Accounting period check | `AccountingPeriodService.requireOpenPeriod()` at GRN creation |
| Reference number generation | `ReferenceNumberService.purchaseReference()` |
| Supplier ledger balance | `SupplierLedgerService.currentBalance()` |
| GST split | `GstService.splitTaxAmount()` |

### 11.3 Accounting → Purchasing (reverse)
| Trigger | Integration |
|---------|-------------|
| Vendor payment settlement | Updates `RawMaterialPurchase.outstandingAmount`, status to PAID/PARTIAL |
| Supplier statement/aging | Reads purchasing data for reporting |

### 11.4 Purchasing → Inventory (Raw Material reference)
| Trigger | Integration |
|---------|-------------|
| PO creation | Validates raw materials exist and are active |
| GRN creation | Links to raw materials, creates batches |
| Invoice creation | Reads raw material inventory account for journal |

### 11.5 Cross-Module Data Flows
- **Company** (multi-tenant): All entities are company-scoped
- **RawMaterial** (inventory): Referenced by PO lines, GRN lines, invoice lines
- **Account** (accounting): Supplier payable account, raw material inventory accounts
- **JournalEntry** (accounting): Linked to purchase invoices and returns
- **RawMaterialBatch** (inventory): Created at GRN, linked to invoice lines
- **RawMaterialMovement** (inventory): Created at GRN and return, linked to journal entries

---

## 12. Gaps & Ambiguities

### 12.1 No Domain Events from Purchasing
The purchasing module does not publish any domain events. It relies entirely on synchronous service calls. There is no event-driven decoupling. This means:
- No async processing of PO approvals
- No event-based notification when goods are received
- No loose coupling between purchasing and other modules

### 12.2 No Purchase Requisition Flow
There is no purchase requisition or request-for-quotation (RFQ) workflow. The flow starts directly at PO creation (DRAFT). For a paint ERP, this may be acceptable if purchasing decisions are centralized.

### 12.3 No Partial Invoicing
The invoice engine requires ALL goods receipt lines to be included. Partial invoicing (e.g., invoicing 3 of 5 materials) is explicitly rejected. This is a deliberate constraint but may be limiting if suppliers send partial invoices.

### 12.4 No PO Amendment Workflow
Once a PO is created, there is no edit or amendment endpoint. The only options are approve, void, or close. If a buyer needs to change quantities or materials, they must void and recreate.

### 12.5 No Goods Receipt Reversal / Rejection
There is no endpoint to reverse or reject a goods receipt. Once received, the only path forward is invoicing. Quality rejection workflows are not implemented.

### 12.6 Tax Amount Not Stored on Goods Receipt
Goods receipt lines store costPerUnit and lineTotal but no tax fields. Tax is only computed at invoice time. This means GRN values are always pre-tax.

### 12.7 RawMaterialPurchase Status is String (Not Enum)
Unlike PO and GRN which use proper enums, the purchase invoice status is a plain String. This could lead to inconsistency. Known values: POSTED, PARTIAL, PAID, VOID, REVERSED.

### 12.8 No Reverse Charge Mechanism (RCM)
While the codebase has `GstRegistrationType` (which includes UNREGISTERED), there is no explicit reverse charge mechanism for purchases from unregistered dealers. Unregistered suppliers would have zero GST by default, but the formal RCM accounting (self-invoicing) is not implemented.

### 12.9 Credit Limit Not Enforced
The Supplier entity has a `creditLimit` field, but no service validates outstanding payable against this limit before creating new POs or invoices.

### 12.10 No Purchase Order – Delivery Schedule
There is no expected delivery date or delivery scheduling on POs. Only `orderDate` is tracked.

### 12.11 Invoice to GRN is 1:1 Strict
Each GRN must map to exactly one purchase invoice. Splitting a GRN across multiple invoices (e.g., partial invoice for partial delivery) is not supported.

### 12.12 No Multi-Currency
All amounts are in a single currency. No exchange rate or foreign currency purchasing support.

### 12.13 Idempotency Only on GRN
Goods receipt creation has robust idempotency (key + hash + optimistic concurrency). Purchase order and invoice creation rely only on unique constraint violations (order number / invoice number) but lack explicit idempotency key support.

---

## Summary: End-to-End Procure-to-Pay Flow

```
1. Supplier Created (PENDING)
       │
2. Supplier Approved → APPROVED
       │
3. Supplier Activated → ACTIVE
       │
4. Purchase Order Created → DRAFT
       │
5. PO Approved → APPROVED
       │
6. Goods Receipt Created → RECEIVED/PARTIAL
   ├── Stock incremented per material
   ├── Batches created
   ├── Movements recorded
   └── PO transitions to PARTIALLY_RECEIVED or FULLY_RECEIVED
       │
7. Purchase Invoice Created → POSTED
   ├── Journal entry posted (Dr Inventory+Tax / Cr Payable)
   ├── Movements linked to journal
   ├── GRN transitions to INVOICED
   └── PO transitions to INVOICED → CLOSED
       │
8a. Vendor Payment Settlement
    ├── Journal posted (Dr Payable / Cr Cash)
    └── Outstanding amount reduced
       │
8b. Purchase Return (alternative path)
    ├── Stock reversed (FIFO from batches)
    ├── Return journal posted (Dr Payable / Cr Inventory+Tax)
    ├── Returned quantity tracked per line
    ├── Outstanding amount reduced
    └── Status may transition to VOID if fully returned
```
