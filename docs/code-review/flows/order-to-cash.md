# Order / to / cash

## Scope and evidence

This review covers dealer master data, dealer self-service, credit requests, credit-limit overrides, sales-order lifecycle, dispatch confirmation, invoice generation, dealer cash receipt and settlement, aging/statement views, and the accounting handoff that turns physical dispatch into receivables truth.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/{DealerController,DealerPortalController,SalesController,CreditLimitOverrideController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/controller/InvoiceController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/{SalesService,SalesCoreEngine,SalesDealerCrudService,SalesOrderLifecycleService,SalesDispatchReconciliationService,DealerService,DealerPortalService,CreditLimitOverrideService,SalesJournalService,DunningService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/{InvoiceService,InvoiceSettlementPolicy}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/{AccountingCoreEngineCore,AccountingFacadeCore}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/{DealerLedgerService,StatementService,AgingReportService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/{Dealer,DealerRepository,SalesOrder,SalesOrderRepository,CreditRequest,CreditLimitOverrideRequest,SalesOrderStatusHistory}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/{Invoice,InvoiceRepository}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/PackagingSlip.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/{DealerLedgerEntry,DealerLedgerRepository,PartnerSettlementAllocation}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V2__accounting_core.sql,V3__sales_invoice.sql,V10__accounting_replay_hotspot_indexes.sql,V12__invoice_pending_exposure_status_norm_indexes.sql,V17__accounting_audit_read_model_hotspot_indexes_invoices.sql}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/controller/SalesControllerIdempotencyHeaderTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/DealerPortalServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchControllerTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/InvoiceControllerSecurityContractTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/{TS_truthsuite_o2c_Approval_RuntimeTest,TS_truthsuite_o2c_Override_RuntimeTest,TS_O2CDispatchCanonicalPostingTest,TS_O2CApprovalDecisionCoverageTest}.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/{TS_RuntimeDealerPortalControllerExportCoverageTest,TS_RuntimeInvoiceControllerExportCoverageTest}.java`

Supporting runtime evidence was limited in this session: `curl -i -s http://localhost:8081/actuator/health` failed with exit code `7`, so this review relies on static inspection plus existing integration/truth-suite coverage.

## Executable remediation handoff

This review feeds:

- [Lane 03 exec spec](../executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md)
- [Lane 04 exec spec](../executable-specs/04-lane-commercial-workflows/EXEC-SPEC.md)

Planning notes:

- Lane 03 Packet 0 is now the prove-first boundary note in [`../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md`](../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md); it makes `SalesCoreEngine.confirmDispatch(...)` / dispatch confirmation the authoritative candidate for AR, revenue, tax, and COGS truth and records the exact proof pack that later runtime slices must preserve.
- Downstream O2C consumers that must inherit that note rather than redefine truth are `InvoiceService.issueInvoiceForOrder(...)`, `DealerLedgerService.syncInvoiceLedger(...)`, `StatementService`, `AgingReportService`, `DunningService`, `DealerPortalService`, and `EnterpriseDashboardService`.
- `O2C-09` should be treated as an early runtime repair packet inside Lane 04, not as a reason to redesign the sales workflow.
- Keep dispatch as the canonical posting boundary from Lane 03, and keep the reservation prerequisite explicit before `POST /api/v1/sales/orders/{id}/confirm` succeeds.

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Dealer master data | `POST/GET /api/v1/dealers`, `GET /api/v1/dealers/search`, `PUT /api/v1/dealers/{dealerId}` | `DealerController` | Admin/sales/accounting surface for dealer onboarding, lookup, and maintenance. |
| Dealer financial views | `GET /api/v1/dealers/{dealerId}/{ledger|invoices|credit-utilization|aging}`, `POST /api/v1/dealers/{dealerId}/dunning/hold` | `DealerController` | Internal receivables, exposure, and hold-evaluation surface. |
| Dealer self-service | `GET /api/v1/dealer-portal/{dashboard|ledger|invoices|aging|orders}`, `POST /api/v1/dealer-portal/credit-requests`, `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf` | `DealerPortalController` | Dealer-scoped read model plus credit-extension request entrypoint and PDF export. |
| Sales order lifecycle | `GET/POST/PUT/DELETE /api/v1/sales/orders`, `GET /api/v1/sales/orders/search`, `POST /api/v1/sales/orders/{id}/{confirm|cancel}`, `PATCH /api/v1/sales/orders/{id}/status`, `GET /api/v1/sales/orders/{id}/timeline` | `SalesController` | Core quote/order CRUD, state transitions, and timeline history. |
| Credit requests | `GET/POST/PUT /api/v1/sales/credit-requests`, `POST /api/v1/sales/credit-requests/{id}/{approve|reject}` | `SalesController` | Workflow for permanent credit-limit increase requests. |
| Dispatch exceptions | `POST/GET /api/v1/credit/override-requests`, `POST /api/v1/credit/override-requests/{id}/{approve|reject}` | `CreditLimitOverrideController` | Maker-checker approvals for dispatch-time credit/price/discount/tax exceptions. |
| Dispatch confirmation | `POST /api/v1/sales/dispatch/confirm`, `POST /api/v1/sales/dispatch/reconcile-order-markers`, `GET /api/v1/dispatch/{pending,preview/{slipId},slip/{slipId},order/{orderId}}`, `POST /api/v1/dispatch/confirm` | `SalesController`, `DispatchController` | Factory/admin path that converts a packing slip into inventory, invoice, and journal truth. |
| Invoice access | `GET /api/v1/invoices`, `GET /api/v1/invoices/{id}`, `GET /api/v1/invoices/{id}/pdf`, `GET /api/v1/invoices/dealers/{dealerId}`, `POST /api/v1/invoices/{id}/email` | `InvoiceController` | Internal invoice browse/export/email surface. |
| Cash receipt and settlement | `POST /api/v1/accounting/receipts/dealer`, `POST /api/v1/accounting/receipts/dealer/hybrid`, `POST /api/v1/accounting/settlements/dealers`, `POST /api/v1/accounting/dealers/{dealerId}/auto-settle` | `AccountingController` | Receivable clearing, settlement allocation, and auto-allocation. |
| Statements and aging | `GET /api/v1/accounting/statements/dealers/{dealerId}`, `GET /api/v1/accounting/aging/dealers/{dealerId}`, and matching PDF exports | `AccountingController` | Dealer statement, aging, and PDF evidence surfaces. |

`openapi.json` includes the major order-to-cash routes above, including dealer portal, order lifecycle, dispatch, override, invoice, settlement, statement, and aging paths.

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `dealers`, `accounts` | `Dealer`, `DealerRepository`, `DealerService`, dealer receivable-account wiring in `SalesCoreEngine` | Dealer onboarding, portal binding, credit-limit storage, receivable-account linkage, dunning hold state. |
| `sales_orders`, `sales_order_status_history` | `SalesOrder`, `SalesOrderRepository`, `SalesOrderStatusHistory` | Order identity, idempotency keys/hashes, state machine, timeline, accounting markers. |
| `credit_requests` | `CreditRequest`, `SalesCoreEngine` credit-request methods | Permanent dealer credit-limit extension workflow. |
| `credit_limit_override_requests` | `CreditLimitOverrideRequest`, `CreditLimitOverrideService` | Dispatch-time exception approval with requester/reviewer metadata, headroom math, and expiry. |
| `packaging_slips` | `PackagingSlip`, `DispatchController`, `SalesCoreEngine.confirmDispatch(...)` | Physical dispatch truth, AR/COGS/invoice replay anchors, dispatch notes, confirmation metadata. |
| `invoices`, `invoice_payment_refs` | `Invoice`, `InvoiceRepository`, `InvoiceSettlementPolicy`, `V3__sales_invoice.sql` | Invoice issuance, outstanding balance, due date, payment-reference idempotency. |
| `dealer_ledger_entries`, `partner_settlement_allocations` | `DealerLedgerEntry`, `DealerLedgerRepository`, `PartnerSettlementAllocation`, `V2__accounting_core.sql` | Sub-ledger balance, aging, statements, settlement allocation traceability, DSO. |
| Replay/exposure indexes | `V10__accounting_replay_hotspot_indexes.sql`, `V12__invoice_pending_exposure_status_norm_indexes.sql`, `V17__accounting_audit_read_model_hotspot_indexes_invoices.sql` | Dispatch replay, pending exposure queries, invoice/read-model hot paths. |
| `openapi.json` | repo snapshot | Confirms that the major O2C API surface is published for clients and contract review. |

## Service chain

### 1. Dealer onboarding, maintenance, and search

`DealerController` is thin; `DealerService` and `SalesCoreEngine` do the real work.

- Dealer create/update stores commercial identity (`code`, `name`, GST, region/state, payment terms) plus credit posture (`creditLimit`, `status`) on `Dealer`.
- Dealer create/update also maintains the linked receivable account. If a dealer has no receivable account, the service creates one; if the linked account exists but is inactive, it is reactivated.
- Dealer list/search is not just master-data lookup. `DealerService` enriches dealers with current receivable balances from `DealerLedgerService`, so the list is already partially financial.
- `DealerRepository.searchFiltered(...)` filters by normalized status, region, and fuzzy code/name matching.
- Dunning is part of the same surface: `DunningService.evaluateDealerHold(...)` computes overdue exposure from `StatementService.dealerAging(...)` and can flip `Dealer.status` to `ON_HOLD` plus send reminder email.

This means “dealer onboarding” is already coupled to accounting and collections state; it is not a pure CRM/master-data workflow.

### 2. Dealer portal read model

`DealerPortalController` is dealer-only (`ROLE_DEALER`), but the security boundary lives in `DealerPortalService`.

Binding logic is intentionally fail-closed:

1. Resolve the authenticated principal.
2. Try `DealerRepository.findAllByCompanyAndPortalUserId(...)`.
3. If the principal has no persisted user id, fall back to `findAllByCompanyAndPortalUserEmailIgnoreCase(...)`.
4. Reject missing mappings.
5. Reject ambiguous mappings.

`DealerPortalServiceTest` proves both missing and duplicate matches are denied rather than guessed.

The portal dashboard then composes:

- current balance from `DealerLedgerService.currentBalance(...)`
- pending order exposure and count from `SalesOrderRepository.sum/countPendingCreditExposureByCompanyAndDealer(...)`
- recent invoices from `InvoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(...)`
- aging derived from accounting statement/aging services

`TS_RuntimeDealerPortalControllerExportCoverageTest` proves dealer invoice PDF exports log `DATA_EXPORT` audit metadata with resource type, resource id, format, and filename.

### 3. Sales order creation, search, and lifecycle

`SalesController` splits responsibility between CRUD, lifecycle, and dashboard facades, but `SalesCoreEngine` owns the state machine.

Important behaviors:

1. **Order creation is explicitly idempotent.** The controller accepts `Idempotency-Key` and legacy `X-Idempotency-Key`; tests prove header parity and mismatch rejection. `SalesOrder` stores both `idempotencyKey` and `idempotencyHash`, and the engine replays prior orders only when the signature matches.
2. **Order credit exposure is checked on entry.** `SalesCoreEngine.enforceCreditLimit(...)` locks the dealer row, reads the current dealer-ledger balance, adds pending exposure, and raises a `CreditLimitExceededException` with structured details when the projected total breaches the limit.
3. **Status history is first-class.** `transitionOrderStatus(...)` persists `SalesOrderStatusHistory`, and the public `/timeline` endpoint reads it back.
4. **The status vocabulary is normalized rather than singular.** Canonical statuses include `DRAFT`, `CONFIRMED`, `PROCESSING`, `DISPATCHED`, `INVOICED`, `SETTLED`, `CLOSED`, `CANCELLED`, `ON_HOLD`, `REJECTED`, `PENDING_INVENTORY`, `RESERVED`, `PENDING_PRODUCTION`, and `READY_TO_SHIP`, but searches and timeline rendering also canonicalize legacy aliases such as `BOOKED`, `SHIPPED`, `FULFILLED`, and `COMPLETED`.
5. **Manual transitions are constrained.** `updateStatus(...)` rejects workflow-only states and only allows direct mutation for a limited manual subset; `CLOSED` requires the canonical status to already be `SETTLED`.

Operationally, the order row already carries downstream financial anchors: `salesJournalEntryId`, `cogsJournalEntryId`, and `fulfillmentInvoiceId`. Those are not merely reports; they are replay markers used later during dispatch reconciliation.

### 4. Credit requests (permanent credit extension)

Credit requests are the softer commercial approval lane.

- Sales/admin users can create and update requests.
- Admin/accounting users can approve or reject them.
- `TS_O2CApprovalDecisionCoverageTest` and controller coverage prove that decision metadata is required and that approve/reject are dedicated actions rather than generic status edits.

The critical implementation detail is in `SalesCoreEngine.approveCreditRequest(...)`:

1. Load the pending credit request.
2. Lock the linked dealer row.
3. Read the requested increment.
4. Add that increment directly to `Dealer.creditLimit`.
5. Mark the request `APPROVED` and audit the old/new limit.

So a credit request is not just an approval record; approval mutates dealer master data in-place.

### 5. Credit-limit overrides (dispatch-time exception workflow)

`CreditLimitOverrideService` is a separate maker-checker path for dispatch-time exceptions.

On request creation it captures:

- `dealer`
- `salesOrder` and/or `packagingSlip`
- `dispatchAmount`
- `currentExposure`
- `creditLimit`
- `requiredHeadroom`
- `requestedBy`
- optional `expiresAt`

Approval semantics are stronger than the credit-request path:

- only `PENDING` requests can be approved or rejected
- requester and reviewer cannot be the same actor
- `reviewedBy`/`reviewedAt` are immutable once set
- approvals can expire; if no expiry is supplied, approval defaults to roughly one day
- reason codes are embedded into the persisted reason string and audit metadata

`TS_truthsuite_o2c_Override_RuntimeTest` and `CreditLimitOverrideService` show that this approval object is reused for multiple dispatch exceptions, not only raw credit breaches. Dispatch can require approval for price override, discount override, tax override, and explicit admin credit override, which makes override semantics broader than the controller name suggests.

### 6. Dispatch confirmation is the accounting truth boundary

The densest O2C path is `SalesCoreEngine.confirmDispatch(...)`.

Observed chain:

1. Run in `SERIALIZABLE` isolation.
2. Require either `packingSlipId` or `orderId`.
3. Lock the referenced packing slip, order, and dealer rows.
4. Validate dealer receivable-account presence.
5. Recompute ship quantities, discounts, tax treatment, and override implications from slip/order data.
6. If the slip is already dispatched, attempt replay recovery by reusing existing invoice id, AR journal id, and COGS journal id from slip markers, order markers, or accounting lookups.
7. If the slip is not yet financially complete, confirm the physical dispatch through `FinishedGoodsService.confirmDispatch(...)`.
8. Post COGS/inventory through `AccountingFacade.postCogsJournal(...)`.
9. Post AR/revenue/tax through `AccountingFacade.postSalesJournal(...)`.
10. Create or reuse the invoice, store markers on both slip and order, sync the dealer ledger, and transition order status.
11. Audit `DISPATCH_CONFIRMED` metadata and return a dispatch response.

Two architectural rules make this path especially important:

- `SalesJournalService.postSalesJournal(...)` is intentionally disabled with `"Order-truth sales journal posting is disabled (CODE-RED). Use dispatch confirmation."`
- `SalesFulfillmentService` also forces invoice/dispatch ownership of AR and disables order-level sales/COGS posting.

In other words: **commercial order confirmation is not the accounting boundary; physical dispatch is.** If dispatch truth is skipped, half-written, or replayed incorrectly, revenue, tax, inventory, invoice, and dealer-ledger state can diverge.

There is also a repair lane: `/api/v1/sales/dispatch/reconcile-order-markers` scans candidate orders and backfills order-level invoice/journal markers from slip-level truth. The presence of a dedicated reconciliation endpoint is evidence that marker drift is an expected failure mode.

### 7. Invoice issuance, export, and status transitions

Invoice creation is downstream of dispatch rather than upstream of order approval.

- `InvoiceService.issueInvoiceForOrder(...)` routes issuance through dispatch confirmation when a packing slip exists.
- `Invoice` stores `salesOrder`, `journalEntry`, `outstandingAmount`, `issueDate`, `dueDate`, and `paymentReferences`.
- `InvoiceController` exposes list/detail/PDF/email endpoints for internal users, while dealer-facing PDF access is separately routed through `DealerPortalController`.
- `TS_RuntimeInvoiceControllerExportCoverageTest` proves invoice PDF downloads emit audit metadata; `InvoiceControllerSecurityContractTest` covers controller security expectations.

`InvoiceSettlementPolicy` centralizes invoice state transitions:

- `DRAFT` -> `ISSUED` on issuance
- `ISSUED` -> `PARTIAL` or `PAID` as outstanding amount decreases
- `VOID`/`REVERSED` are terminal for payment application
- duplicate payment/settlement references are ignored, making payment application idempotent by reference

This is the canonical invoice state machine; receipt and settlement code is expected to go through this policy rather than hand-editing `Invoice.status`.

### 8. Cash receipt, settlement, and auto-settlement

Dealer cash application lives in accounting, but it closes the O2C loop.

`AccountingController` routes dealer receipt and settlement endpoints into `AccountingCoreEngineCore`, which:

1. Creates accounting entries for the receipt/settlement event.
2. Locks the affected invoices.
3. Persists `PartnerSettlementAllocation` rows that explain how money was allocated.
4. Calls `InvoiceSettlementPolicy.applySettlement(...)` or equivalent payment logic.
5. Calls `DealerLedgerService.syncInvoiceLedger(...)` so sub-ledger state reflects the new invoice status/outstanding amount.

Auto-settlement uses `InvoiceRepository.lockOpenInvoicesForSettlement(...)`, which orders open invoices by due date and issue date before allocation. That gives the system a deterministic settlement order, but it also means accounting owns the final “which invoice got paid first” truth, not sales.

### 9. Aging, statements, ledger, and collections

Receivables observability is ledger-centric.

- `DealerLedgerRepository` aggregates balances, running statements, unpaid invoices, overdue balances, and DSO support queries from `dealer_ledger_entries`.
- `StatementService` builds dealer statements and statement PDFs from those ledger rows.
- `AgingReportService` and adjacent aged-debtors queries derive bucketed receivables and DSO from the same sub-ledger.
- `DealerController` and `AccountingController` both expose aging/statement-style views, while the dealer portal exposes read-only slices of the same commercial debt position.
- `DunningService` periodically reviews aging buckets and can place dealers on hold when overdue exposure crosses threshold.

The key pattern is that all downstream views eventually depend on synchronized dealer-ledger entries. If invoice status or settlement allocation drifts without a ledger sync, every aging, statement, dashboard, and dunning surface becomes suspect.

## State machine and idempotency assumptions

### State assumptions

| Object | State assumptions | Evidence |
| --- | --- | --- |
| Dealer | Dealer status is commercially active unless collections logic places it `ON_HOLD`; hold state blocks new order creation. | `Dealer.status`, `SalesCoreEngine.createDealer(...)`, `SalesCoreEngine.createOrder(...)`, `DunningService` |
| Sales order | Canonical order lifecycle is normalized through `canonicalOrderStatus(...)`; aliases such as `BOOKED`, `SHIPPED`, `FULFILLED`, and `COMPLETED` are translated into canonical states for search/history. | `SalesCoreEngine`, `SalesOrderRepository.searchIdsByCompany(...)` |
| Credit request | Pending requests must be decided through dedicated approve/reject actions, not free-form status edits. | `SalesCoreEngine.updateCreditRequest(...)`, approval/rejection methods, approval tests |
| Override request | Override requests begin `PENDING`, move to `APPROVED` or `REJECTED`, and retain immutable review metadata plus optional expiry. | `CreditLimitOverrideRequest`, `CreditLimitOverrideService` |
| Packaging slip | Dispatch replay logic assumes slip status plus `invoiceId`, `journalEntryId`, and `cogsJournalEntryId` represent the durable fulfillment/accounting anchors. | `PackagingSlip`, `SalesCoreEngine.confirmDispatch(...)` |
| Invoice | Invoice state must flow through `InvoiceSettlementPolicy`; outstanding amount drives `ISSUED`/`PARTIAL`/`PAID`, while `VOID` and `REVERSED` are terminal. | `InvoiceSettlementPolicy` |

### Idempotency assumptions

- **Order create:** explicit, caller-visible idempotency via header/body key parity plus payload hashing.
- **Dispatch confirm:** implicit idempotency via replay anchors on packing slips, orders, invoices, and journal lookups; there is no equivalent public dispatch idempotency key.
- **Invoice settlement/payment:** idempotent by settlement/payment reference stored in `invoice_payment_refs`.
- **Receipt allocation:** durable replay support depends on persisted `PartnerSettlementAllocation` rows and subsequent invoice-ledger resync.

The system therefore mixes explicit idempotency (orders, invoice payments) with inferred idempotency (dispatch replay). The inferred side is more fragile because it depends on partially written financial markers being present and consistent.

## Side effects, integrations, and recovery behavior

- Dealer onboarding and maintenance can create or reactivate receivable accounts.
- Dealer portal PDF export and internal invoice PDF export both emit `DATA_EXPORT` audit events.
- Dispatch confirmation mutates inventory, invoices, AR, tax, COGS, status history, and dealer sub-ledger state in one workflow.
- Dealer receipts and settlements create journals plus settlement-allocation rows, then update invoice and dealer-ledger state.
- Dunning can send reminder email and place a dealer on hold based on aging thresholds.
- The dedicated dispatch marker reconciliation endpoint exists specifically to recover from partially linked order/slip financial markers.

Recovery is strongest where explicit replay anchors exist. Orders and invoice settlements can reject duplicates deterministically. Dispatch recovery is more operational: it tries to infer prior success from slip/order/invoice/journal markers and backfill missing fields.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| critical | accounting boundary / integrity | Dispatch confirmation, not order confirmation, is the true AR/revenue/COGS boundary. Order-truth journal posting is intentionally disabled, and a separate marker-reconciliation endpoint exists to heal drift. | `SalesJournalService.postSalesJournal(...)`, `SalesFulfillmentService`, `SalesCoreEngine.confirmDispatch(...)`, `/sales/dispatch/reconcile-order-markers` | Any bypass, partial failure, or replay bug in dispatch can orphan revenue, tax, invoice, or inventory truth while the order itself still appears commercially valid. |
| high | state-machine drift | Order status handling accepts legacy aliases (`BOOKED`, `SHIPPED`, `FULFILLED`, `COMPLETED`) alongside canonical states and normalizes them in search/history. | `SalesCoreEngine.canonicalOrderStatus(...)`, `SalesOrderRepository.searchIdsByCompany(...)` | Multiple vocabularies increase the chance that reports, APIs, and business rules disagree on whether an order is still open, dispatched, or settled. |
| high | master-data mutation | Credit-request approval directly increments `Dealer.creditLimit` on the dealer row. | `SalesCoreEngine.approveCreditRequest(...)` | A workflow approval permanently rewrites dealer master data, so a mistaken approval is not merely a bad transaction outcome; it changes future credit enforcement for every order. |
| high | approval semantics | Credit-limit override approvals are reused for price, discount, tax, and admin credit exceptions, not just pure credit breaches. | `SalesCoreEngine.confirmDispatch(...)`, `CreditLimitOverrideService` | One approval object is carrying several exception classes; if the approval context is misunderstood, reviewers can unintentionally authorize broader financial deviations than the name implies. |
| high | replay / resilience | Dispatch idempotency is inferred from slip/order/invoice/journal markers rather than a caller-supplied idempotency key. | `SalesCoreEngine.confirmDispatch(...)`, `PackagingSlip`, `SalesOrder` marker fields | Retry safety depends on prior writes completing far enough to leave reliable anchors. Mid-transaction failures or manual data repair can make replay decisions ambiguous. |
| high | query/runtime break | `GET /api/v1/sales/orders/search` is currently broken on PostgreSQL with `lower(bytea) does not exist`. | live backend probe on `GET /api/v1/sales/orders/search`, `SalesOrderRepository.searchIdsByCompany(...)` | A primary order-discovery surface is unusable in the current backend, which blocks sales/admin lookup workflows and signals query/type drift against the supported PostgreSQL runtime. |
| high | accounting coupling | Dealer receipt/settlement processing spans journal posting, allocation persistence, invoice settlement policy, and dealer-ledger synchronization. | `AccountingCoreEngineCore`, `PartnerSettlementAllocation`, `InvoiceSettlementPolicy`, `DealerLedgerService.syncInvoiceLedger(...)` | If one leg drifts, every downstream statement, aging report, portal dashboard, and collection decision can be wrong even when the journal entry itself exists. |
| medium | reporting consistency | Dealer portal dashboard, dealer aging endpoints, accounting statements, and dunning all derive receivable posture through related but not identical query paths over the same sub-ledger. | `DealerPortalService`, `DealerLedgerRepository`, `StatementService`, `AgingReportService`, `DunningService` | Small differences in filters or stale ledger sync can produce contradictory customer-balance stories across operational surfaces. |
| medium | collections coupling | Dealer hold state is driven by aging thresholds and directly blocks order creation. | `DunningService`, `SalesCoreEngine.createOrder(...)` | Collections policy is directly embedded into order-entry viability; stale aging or accidental holds become revenue-blocking events. |

## Security, privacy, protocol, performance, and observability notes

### Strengths

- Dealer portal identity binding is fail-closed on missing or ambiguous dealer mappings.
- Dispatch confirmation requires both role authority and the explicit `dispatch.confirm` permission.
- Pessimistic locking is used on dealers, orders, and settlement invoices where concurrency would otherwise corrupt credit or settlement state.
- PDF exports for invoices and dealer invoices are audited with resource metadata.
- `openapi.json` covers the main O2C surfaces, so client-visible drift is lower here than in some other modules.

### Hotspots

- The most sensitive workflow is also the busiest one: dispatch confirmation mixes inventory, tax, revenue, invoice, and sub-ledger logic in a single method.
- Pending exposure is query-driven and excludes orders already covered by active invoices. That is correct in principle, but it means exposure quality depends on invoice-state normalization staying exact.
- The system uses sub-ledger balance (`DealerLedgerService.currentBalance(...)`) plus pending order exposure to judge credit. If ledger sync lags invoice settlement, credit checks can become too strict or too loose.
- Runtime probing also confirmed `POST /api/v1/sales/orders/{id}/confirm` fails with `BUS_001` until finished-goods stock has already been reserved, so reservation is a hard workflow prerequisite that the frontend/integration path must model explicitly.
- Runtime verification was degraded in this session, so operational confidence here comes mainly from static analysis plus existing tests rather than live end-to-end mutation.

## Evidence notes

- `SalesControllerIdempotencyHeaderTest` proves order creation honors `Idempotency-Key`, supports legacy `X-Idempotency-Key`, and rejects mismatches.
- `DealerPortalServiceTest` proves dealer-portal binding fails closed on ambiguous or missing mappings and computes pending exposure as part of the dashboard.
- `DispatchControllerTest` and `TS_O2CDispatchCanonicalPostingTest` cover dispatch-controller delegation and canonical dispatch posting expectations.
- `TS_truthsuite_o2c_Approval_RuntimeTest`, `TS_truthsuite_o2c_Override_RuntimeTest`, and `TS_O2CApprovalDecisionCoverageTest` cover approval/override workflow expectations and decision metadata rules.
- `TS_RuntimeDealerPortalControllerExportCoverageTest` and `TS_RuntimeInvoiceControllerExportCoverageTest` prove PDF exports emit audit metadata.
- The local OpenAPI snapshot includes dealer-portal, sales-order, credit-request, dispatch-confirm, override-request, invoice, dealer-settlement, statement, and aging paths.
