# Procure / to / pay

## Scope and evidence

This review covers supplier master-data onboarding and lifecycle changes, purchase-order creation and approval, goods receipt posting, raw-material purchase invoicing, purchase returns, supplier settlement/auto-settlement, supplier statement and aging views, and the accounting/inventory handoffs that turn physical receipts into AP truth.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/controller/{SupplierController,PurchasingWorkflowController,RawMaterialPurchaseController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/{SupplierService,PurchaseOrderService,GoodsReceiptService,PurchaseInvoiceEngine,PurchaseReturnService,PurchaseReturnAllocationService,SupplierApprovalDecision,SupplierApprovalPolicy}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/{AccountingCoreEngineCore,AccountingFacadeCore}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/{SupplierLedgerService,StatementService,SettlementService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/{Supplier,PurchaseOrder,PurchaseOrderStatus,PurchaseOrderStatusHistory,GoodsReceipt,GoodsReceiptStatus,RawMaterialPurchase,RawMaterialPurchaseRepository,SupplierStatus}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/{PartnerSettlementAllocation,SupplierLedgerEntry}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V2__accounting_core.sql,V5__purchasing_hr.sql,V26__journal_entry_type_and_source_columns.sql,V29__supplier_management_improvements.sql,V32__gst_component_tracking_fields.sql,V34__hr_p2p_factory_schema_parity.sql}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/controller/{PurchasingWorkflowControllerTest,PurchasingWorkflowControllerSecurityContractTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/service/{SupplierServiceTest,PurchaseOrderServiceTransitionMatrixTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/p2p/{TS_P2PApprovalRuntimeTest,TS_P2PSupplierApprovalCoverageTest,TS_P2PGoodsReceiptIdempotencyTest,TS_P2PPurchaseJournalLinkageTest,TS_P2PPurchaseSettlementBoundaryTest,TS_P2PSettlementRuntimeTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/PurchaseReturnIdempotencyRegressionIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/{CR_PurchasingToApAccountingTest,CR_PurchasingGrnPeriodCloseTest,CR_InventoryGlAutomationProdOffIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/ProcureToPayE2ETest.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeAccountingControllerExportCoverageTest.java`

Supporting runtime evidence was degraded in this session: `curl -i -s http://localhost:8081/actuator/health` failed with exit code `7`, so this review relies on static inspection plus existing tests. Baseline suite `mvn test -Pgate-fast -Djacoco.skip=true` passed before drafting.

## Executable remediation handoff

This review feeds:

- [Lane 03 exec spec](../executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md)
- [Lane 04 exec spec](../executable-specs/04-lane-commercial-workflows/EXEC-SPEC.md)

Planning notes:

- Lane 03 Packet 0 is the prove-first boundary note in [`../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md`](../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md); it keeps `GoodsReceiptService` as the physical stock boundary, `PurchaseInvoiceEngine` as the candidate canonical AP truth boundary, and the inventory-accounting listener/manual inventory journals explicitly noncanonical until Packet 2 narrows them.
- Downstream P2P/finance consumers that must inherit that note are `AccountingCoreEngineCore.{recordSupplierPayment,settleSupplierInvoices,autoSettleSupplier}(...)`, `SupplierLedgerService`, `StatementService`, `ReconciliationServiceCore`, and `AccountingPeriodServiceCore`.
- `P2P-01` stays in Lane 03 because purchase invoice remains the canonical AP boundary and goods receipt must not become a second posting path.
- Keep GRN idempotency and settlement-parity hardening in Lane 04, separate from posting-boundary redesign.

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Supplier master data | `GET/POST /api/v1/suppliers`, `GET/PUT /api/v1/suppliers/{id}` | `SupplierController` | Supplier onboarding and maintenance; list/get is readable by admin/accounting/factory, mutations are admin/accounting only. |
| Supplier lifecycle | `POST /api/v1/suppliers/{id}/{approve|activate|suspend}` | `SupplierController` | Explicit state transitions rather than free-form status edits. |
| Purchase-order workflow | `GET/POST /api/v1/purchasing/purchase-orders`, `GET /api/v1/purchasing/purchase-orders/{id}`, `POST /api/v1/purchasing/purchase-orders/{id}/{approve|void|close}`, `GET /api/v1/purchasing/purchase-orders/{id}/timeline` | `PurchasingWorkflowController` | Canonical PO state machine plus persisted timeline history. |
| Goods receipts | `GET/POST /api/v1/purchasing/goods-receipts`, `GET /api/v1/purchasing/goods-receipts/{id}` | `PurchasingWorkflowController` | Receipt creation is idempotent and period-locked. |
| Purchase invoice and return | `GET/POST /api/v1/purchasing/raw-material-purchases`, `GET /api/v1/purchasing/raw-material-purchases/{id}`, `POST /api/v1/purchasing/raw-material-purchases/returns` | `RawMaterialPurchaseController` | Purchase invoice posting and purchase-return reversal surface. |
| Supplier settlement | `POST /api/v1/accounting/settlements/suppliers`, `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle` | `AccountingController` | Canonical supplier money flow is settlement; auto-settlement builds and posts canonical allocations. |
| Supplier statement and aging | `GET /api/v1/accounting/statements/suppliers/{supplierId}`, `GET /api/v1/accounting/aging/suppliers/{supplierId}`, admin-only PDF variants | `AccountingController` | Read models over supplier ledger entries with export audit logging. |

The local `openapi.json` snapshot publishes at least the supplier CRUD/lifecycle routes and the major purchasing/accounting P2P surfaces above.

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `suppliers`, `accounts` | `Supplier`, `SupplierService`, `V5__purchasing_hr.sql`, `V26__journal_entry_type_and_source_columns.sql`, `V29__supplier_management_improvements.sql` | Supplier identity, GST/state/payment terms, encrypted bank fields, payable-account linkage, credit limit, lifecycle state. |
| `purchase_orders`, `purchase_order_items`, `purchase_order_status_history` | `PurchaseOrder`, `PurchaseOrderStatusHistory`, `PurchaseOrderService`, `V5__purchasing_hr.sql`, `V34__hr_p2p_factory_schema_parity.sql` | PO commercial terms, canonical state machine, timeline/audit trail. |
| `goods_receipts`, raw-material batches/movements | `GoodsReceipt`, `GoodsReceiptService`, `RawMaterialService`, `V5__purchasing_hr.sql` | Physical receipt truth, idempotency anchor, stock increase, journal-link replay anchors. |
| `raw_material_purchases`, `raw_material_purchase_items` | `RawMaterialPurchase`, `PurchaseInvoiceEngine`, `PurchaseReturnAllocationService`, `V5__purchasing_hr.sql`, `V32__gst_component_tracking_fields.sql` | Purchase invoice identity, outstanding amount, purchase journal link, goods-receipt linkage, returned quantity, GST component storage. |
| `supplier_ledger_entries`, `partner_settlement_allocations` | `SupplierLedgerService`, `StatementService`, `AccountingCoreEngineCore`, `PartnerSettlementAllocation`, `V2__accounting_core.sql` | Supplier sub-ledger, statements, aging, settlement traceability, idempotent payment/settlement replay. |
| Status-normalization migrations | `V29__supplier_management_improvements.sql` | Legacy value backfill (`NEW` -> `PENDING`, `OPEN` -> `APPROVED`, `PARTIAL` -> `PARTIALLY_RECEIVED`, etc.) and workflow constraints/indexes. |
| API contract snapshot | `openapi.json` | Confirms that supplier and core P2P surfaces are client-visible and contract-reviewed. |

## Service chain

### 1. Supplier onboarding, maintenance, and lifecycle

`SupplierController` is thin; `SupplierService` owns nearly all business behavior.

- Supplier create normalizes code, GST number, state code, GST registration type, payment terms, and optional contact fields before persisting.
- New suppliers are explicitly created as `PENDING` in service code, even though the base `V5__purchasing_hr.sql` table default was historically `ACTIVE`.
- Creation also provisions a supplier-specific payable account. If the company already has an `AP` control account, the new supplier payable account is attached beneath it (`SupplierServiceTest` proves this parent linkage).
- Bank account name/number/IFSC/branch are encrypted before persistence and decrypted again in `SupplierResponse` for authorized readers.
- Supplier list/detail is already financially enriched: `SupplierLedgerService.currentBalance(...)` is invoked so the master-data surface carries current payable posture.
- Lifecycle transitions are strict: `PENDING -> APPROVED`, `APPROVED|SUSPENDED -> ACTIVE`, and `ACTIVE -> SUSPENDED`. Any other transition is rejected.

There is also a fail-closed `SupplierApprovalDecision` / `SupplierApprovalPolicy` helper that requires maker/checker separation plus immutable audit metadata (`ticket` + source alias), but the public supplier approve endpoint itself still performs a direct `PENDING -> APPROVED` state change rather than collecting that approval payload.

### 2. Purchase-order creation, approval, and timeline

`PurchasingWorkflowController` delegates PO work into `PurchaseOrderService`.

Important implementation rules:

1. **Order number uniqueness, not caller-visible idempotency, is the duplicate guard.** `createPurchaseOrder(...)` pessimistically checks `order_number` before insert; there is no `Idempotency-Key` equivalent on PO create.
2. **Only active suppliers can receive new POs.** `SupplierStatus.ACTIVE` is a hard precondition for creation.
3. **POs start in `DRAFT`.** The service explicitly overrides the legacy schema default and records an initial `PURCHASE_ORDER_CREATED` history row.
4. **The canonical transition matrix is narrow:** `DRAFT -> APPROVED|VOID`, `APPROVED -> PARTIALLY_RECEIVED|FULLY_RECEIVED|VOID`, `PARTIALLY_RECEIVED -> FULLY_RECEIVED`, `FULLY_RECEIVED -> INVOICED`, `INVOICED -> CLOSED`, and `CLOSED`/`VOID` are terminal.
5. **Timeline is first-class.** Every transition writes `purchase_order_status_history`, and `/timeline` reads that table back.

`PurchaseOrderServiceTransitionMatrixTest` proves both the happy path and several illegal skips/reopens.

### 3. Goods receipt is the stock boundary

Goods receipts are posted through `PurchasingWorkflowController.createGoodsReceipt(...)` into `GoodsReceiptService`.

- The controller is deliberately strict about the protocol: `Idempotency-Key` is accepted, `X-Idempotency-Key` is explicitly rejected, and header/body mismatches fail closed (`PurchasingWorkflowControllerTest`).
- `GoodsReceiptService` normalizes the idempotency key, hashes the request signature, and replays the existing receipt only when the payload matches. `TS_P2PGoodsReceiptIdempotencyTest` and `CR_PurchasingToApAccountingTest` cover retry safety.
- The receipt date must land in an open accounting period. `CR_PurchasingGrnPeriodCloseTest` proves closed periods reject new GRNs.
- The service locks the PO, validates quantities against ordered quantities, rejects over-receipt, and updates both GRN status (`PARTIAL`/`RECEIVED`) and PO status (`PARTIALLY_RECEIVED`/`FULLY_RECEIVED`).
- `RawMaterialService.recordReceipt(...)` is the stock-entry boundary that creates/updates raw-material batches and inventory movements.
- After persistence, the service publishes `InventoryMovementEvent` with supplier payable as source and raw-material inventory as destination when both accounts are known.

Operationally, a goods receipt is not just a document row: it creates the inventory movement chain that later purchase invoicing expects to link back to a journal entry.

### 4. Purchase invoice posting is the AP truth boundary

Raw-material purchase invoice creation flows through `RawMaterialPurchaseController` into `PurchaseInvoiceEngine`.

Observed chain:

1. Lock and validate the supplier and target goods receipt.
2. Reject already-invoiced GRNs and reject any GRN already linked to another purchase invoice.
3. Assert that the GRN inventory movements are still unlinked before attempting journal linkage.
4. Require invoice lines to match GRN lines exactly on material coverage, quantity, unit, and unit cost.
5. Resolve a single purchase tax mode for the invoice; mixing GST and non-GST materials is rejected.
6. **Post the purchase journal first.** `TS_P2PPurchaseJournalLinkageTest` proves the intended order: journal before purchase persistence, specifically to avoid orphan purchase rows if journal posting fails.
7. Persist `RawMaterialPurchase` with supplier, PO, GRN, line items, total/tax/outstanding amounts, and journal reference.
8. Link raw-material movements and GRN movements to the posted journal.
9. Mark the goods receipt `INVOICED`; move the PO to `INVOICED` and then `CLOSED` once every receipt for that PO has been invoiced.

`CR_PurchasingToApAccountingTest` and `ProcureToPayE2ETest` prove the resulting journal is balanced, receipt movements are linked exactly once, GRN status becomes `INVOICED`, PO closes, and AP reconciles back to the supplier ledger.

### 5. Purchase returns reverse stock and payable together

Purchase returns are posted through `PurchaseReturnService`.

- The request must name the supplier, purchase, material, quantity, unit cost, and reference number; supplier/purchase/material mismatches fail closed.
- The return reference doubles as the replay key. If prior `PURCHASE_RETURN` inventory movements already exist for that reference, the service validates that material, quantity, and unit cost match before reusing the accounting result.
- Remaining returnable quantity is calculated from `RawMaterialPurchaseLine.returnedQuantity`; attempts to exceed that quantity are rejected.
- The service computes line net + GST reversal, posts the purchase-return journal through `AccountingFacade.postPurchaseReturn(...)`, deducts on-hand stock, and issues FIFO out of raw-material batches.
- `PurchaseReturnAllocationService` then updates returned quantity and reduces purchase outstanding; status becomes `PARTIAL`, `PAID`, or `VOID`, with `VOID` reserved for fully returned/fully cleared purchases.

`PurchaseReturnIdempotencyRegressionIT` proves replay does not duplicate movements and that partial-then-final returns only mark the purchase `VOID` after the full quantity is returned.

### 6. Supplier settlement and auto-settlement

Accounting owns the cash-application side of P2P.

#### Supplier settlements

`AccountingCoreEngineCore.settleSupplierInvoices(...)` is the canonical public open-item clearing path.

- Allocations still cannot target invoices.
- Purchase allocations may include discount/write-off/FX adjustments, but on-account lines (`purchaseId == null`) may not include those adjustment components.
- Before any purchase is cleared, `enforceSupplierSettlementPostingParity(...)` checks that the purchase journal exists, belongs to the same supplier, credits the supplier payable account for the full purchase total, and matches GST input posting to `purchase.taxAmount`.
- Over-clearing a purchase is rejected against the current outstanding amount.
- Replay safety again depends on idempotency mappings plus `PartnerSettlementAllocation` persistence.

#### Auto-settlement

`autoSettleSupplier(...)` builds FIFO allocations and delegates to the canonical settlement posting flow.

- `RawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(...)` orders open purchases by `invoiceDate`, then `id`, under pessimistic lock.
- The requested amount must be fully allocatable; otherwise the call fails closed rather than partially guessing.

`TS_P2PPurchaseSettlementBoundaryTest`, `CR_PurchasingToApAccountingTest`, and `ProcureToPayE2ETest` cover the settlement-only endpoint surface, idempotent replay, and outstanding-balance updates.

### 7. Supplier statements, aging, and sub-ledger read models

Supplier read models are ledger-centric.

- `SupplierLedgerService.currentBalance(...)` is used by supplier master-data responses, so list/detail views expose current payable balance rather than just static master data.
- `StatementService.supplierStatement(...)` defaults to a six-month window, calculates an opening balance before the range, and then walks `supplier_ledger_entries` in date/id order to build a running balance.
- `StatementService.supplierAging(...)` derives aging buckets from ledger entries as of a reference date; default buckets are `0-30`, `31-60`, `61-90`, and `91+`, but callers can provide a custom bucket string.
- PDF exports for supplier statement and aging are admin-only and emit `DATA_EXPORT` audit metadata (`TS_RuntimeAccountingControllerExportCoverageTest`).

The key dependency is ledger sync quality: if purchase journals or settlement allocations drift, supplier list balance, statement, and aging all drift together.

## State machine and idempotency assumptions

### State assumptions

| Object | State assumptions | Evidence |
| --- | --- | --- |
| Supplier | Canonical lifecycle is `PENDING -> APPROVED -> ACTIVE -> SUSPENDED`, with re-activation allowed only from `APPROVED` or `SUSPENDED`. | `SupplierStatus`, `SupplierService`, `V29__supplier_management_improvements.sql` |
| Purchase order | Canonical workflow is `DRAFT -> APPROVED -> PARTIALLY_RECEIVED -> FULLY_RECEIVED -> INVOICED -> CLOSED`, with `VOID` only from early stages. | `PurchaseOrderStatus`, `PurchaseOrderService`, `PurchaseOrderServiceTransitionMatrixTest` |
| Goods receipt | Receipt status is `PARTIAL`, `RECEIVED`, or `INVOICED`; uninvoiced GRNs are period-close blockers. | `GoodsReceiptStatus`, `GoodsReceiptService`, `CR_PurchasingGrnPeriodCloseTest` |
| Raw material purchase | Invoice posting starts purchases effectively in `POSTED`; settlements move them to `PARTIAL`/`PAID`, and full return can force `VOID`. | `PurchaseInvoiceEngine`, `AccountingCoreEngineCore.updatePurchaseStatus(...)`, `PurchaseReturnAllocationService` |
| Settlement allocation | Supplier payments are purchase-only, while supplier settlements allow purchase allocations plus limited on-account lines. | `AccountingCoreEngineCore.validatePaymentAllocations(...)`, `validateSupplierSettlementAllocations(...)` |

### Idempotency assumptions

- **Supplier CRUD and PO create:** no public idempotency key; uniqueness and state-machine checks are the main duplicate protection.
- **Goods receipt:** explicit caller-supplied `Idempotency-Key` plus payload hashing and retry replay.
- **Purchase invoice:** duplicate protection is composite: unique invoice number, one purchase per goods receipt, and movement journal-link guards.
- **Purchase return:** reference number replay via existing `PURCHASE_RETURN` movements.
- **Supplier settlement:** idempotency key or reference number plus durable `PartnerSettlementAllocation` rows and journal-reference mappings.
- **Auto-settlement:** inherits supplier-settlement idempotency once FIFO allocations are built.

The result is a mixed model: GRNs/payments/settlements are explicitly replay-safe, while supplier/PO create flows rely more on business keys and transaction isolation.

## Side effects, integrations, and recovery behavior

- Supplier creation provisions a payable account and immediately couples master data to the chart of accounts.
- Goods receipts mutate inventory batches/movements and publish `InventoryMovementEvent` after commit.
- Purchase invoice posting creates the AP journal, links inventory movements to that journal, marks the GRN invoiced, and can close the PO.
- Purchase returns create reversal journals, decrement stock, write return movements, and reduce purchase outstanding.
- Supplier payments/settlements create journals plus `PartnerSettlementAllocation` rows, then update purchase outstanding/status.
- Period close fails closed when uninvoiced GRNs exist in the target month (`CR_PurchasingGrnPeriodCloseTest`).
- Month-end checklist logic treats `PARTIAL`/`PAID` purchases as posted-ish, but still flags purchases missing journal links (`CR_PurchasingToApAccountingTest`).
- Supplier statement/aging PDF exports emit audit metadata with resource type, resource id, operation, and format.

Recovery is strongest where explicit replay anchors exist. GRNs, purchase returns, and supplier settlements can all deterministically replay. PO creation is weaker because it depends on client-generated order numbers rather than a durable idempotency contract.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| critical | accounting boundary / configuration assumption | The AP truth boundary is purchase invoicing, not goods receipt, yet GRN posting emits `InventoryMovementEvent` with payable and inventory accounts populated. `CR_InventoryGlAutomationProdOffIT` proves production safety currently depends on `erp.inventory.accounting.events.enabled=false`. | `GoodsReceiptService`, `InventoryAccountingEventListener`, `CR_InventoryGlAutomationProdOffIT` | Re-enabling inventory auto-posting in production without redesign would likely create a second inventory/AP journal at receipt time in addition to the purchase invoice journal. |
| high | schema / workflow drift | Base schema defaults still reflect legacy behavior (`suppliers.status` default `ACTIVE`, `purchase_orders.status` default `OPEN`), while service code assumes `PENDING` and `DRAFT`; `V29` backfills data and adds constraints but does not realign those defaults. | `V5__purchasing_hr.sql`, `V29__supplier_management_improvements.sql`, `SupplierService`, `PurchaseOrderService` | Any import, seed, or direct SQL path that bypasses service-layer assignment can accidentally create active suppliers or pre-approved purchase orders. |
| high | settlement integrity | Supplier settlement refuses to clear a purchase unless supplier linkage, purchase journal linkage, AP credit amount, and GST input posting all match the original purchase. | `AccountingCoreEngineCore.enforceSupplierSettlementPostingParity(...)` | This is the right fail-closed behavior, but it also means small journal-link or tax-posting drift becomes a cash-application blocker. |
| high | protocol asymmetry | GRN creation has a strict public idempotency contract and rejects legacy header names, while PO creation has no equivalent idempotency key and relies only on order-number uniqueness. | `PurchasingWorkflowControllerTest`, `GoodsReceiptService`, `PurchaseOrderService` | Client retry logic has to treat adjacent P2P mutations differently, which increases duplicate/ordering mistakes in integrators. |
| medium | governance gap | The repo contains fail-closed supplier approval helpers with maker/checker separation and immutable audit metadata, but the public supplier approve endpoint still only flips state. | `SupplierApprovalDecision`, `SupplierApprovalPolicy`, `TS_P2PApprovalRuntimeTest`, `TS_P2PSupplierApprovalCoverageTest`, `SupplierController`, `SupplierService` | Governance intent exists in code/tests, but runtime supplier-approval traceability is weaker than the helper model suggests. |
| medium | privacy / data handling | Supplier bank details are encrypted at rest but decrypted into `SupplierResponse` on read. | `SupplierService.applyBankDetails(...)`, `SupplierService.toResponse(...)` | Sensitive data exposure is controlled entirely by role checks at the controller boundary; any authorization drift leaks full bank details, not masked values. |

## Security, privacy, protocol, performance, and observability notes

### Strengths

- Supplier, purchasing, and settlement mutations are role-gated to admin/accounting surfaces; supplier list/detail expands read access to factory users without opening write paths.
- GRN protocol handling is intentionally fail-closed around missing or mismatched idempotency keys.
- Pessimistic locking is used on purchase orders, goods receipts, purchases, suppliers, and settlement targets where concurrency would corrupt financial truth.
- Supplier bank details are encrypted before persistence.
- Supplier statement/aging PDF exports are audit-logged.
- Period close and month-end checklist logic explicitly look for uninvoiced GRNs and missing purchase journal links.

### Hotspots

- Purchase-order and supplier workflow correctness still depends on service-layer status assignment because legacy schema defaults remain reachable beneath it.
- The after-commit inventory-accounting listener is powerful and loosely coupled; production correctness currently depends on it staying disabled for the GRN path.
- Sensitive supplier bank details are returned decrypted, not masked.
- Runtime verification was degraded in this session, so confidence comes from static analysis plus existing test evidence rather than live mutation against the local backend.

## Evidence notes

- `TS_P2PGoodsReceiptIdempotencyTest` proves GRNs require idempotency, open periods, and duplicate-safe retry behavior.
- `PurchasingWorkflowControllerTest` and `PurchasingWorkflowControllerSecurityContractTest` prove canonical `Idempotency-Key` handling and mutation-role protections.
- `PurchaseOrderServiceTransitionMatrixTest` proves the canonical PO transition matrix and timeline persistence behavior.
- `TS_P2PPurchaseJournalLinkageTest` proves purchase invoice posting is journal-first, movement-linking, and single-tax-mode constrained.
- `CR_PurchasingToApAccountingTest` proves balanced journals, movement linkage, AP/sub-ledger reconciliation, concurrent invoice safety, and supplier settlement idempotency.
- `ProcureToPayE2ETest` proves happy-path purchase -> receipt -> purchase invoice -> supplier settlement, plus quantity/tax edge cases.
- `PurchaseReturnIdempotencyRegressionIT` proves purchase-return replay safety and quantity/outstanding guards.
- `TS_P2PPurchaseSettlementBoundaryTest` proves purchasing endpoints stay isolated from supplier-settlement endpoints and that purchase posting does not directly invoke settlement paths.
- `CR_PurchasingGrnPeriodCloseTest` proves uninvoiced GRNs block period close and closed periods reject downstream posting.
- `TS_RuntimeAccountingControllerExportCoverageTest` proves supplier statement/aging PDFs emit export audit metadata.
