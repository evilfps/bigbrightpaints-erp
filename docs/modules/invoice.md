# Invoice Module / Dealer Finance and Portal Finance Surfaces

Last reviewed: 2026-03-30

This packet documents the **invoice module** and the **finance host ownership** between internal finance views and dealer self-service. It covers invoice lifecycle, settlement behavior, host ownership boundaries, self-service isolation, and the ledger/aging/statement surfaces that both internal and dealer-facing consumers interact with.

---

## Ownership Summary

| Surface | Owning Module | Primary Responsibility |
| --- | --- | --- |
| Invoice lifecycle (create, issue, PDF, status) | `invoice` | `InvoiceService`, `InvoiceController`, `InvoicePdfService` |
| Invoice number sequencing | `invoice` | `InvoiceNumberService` |
| Invoice settlement | `invoice` | `InvoiceSettlementPolicy` (includes `SettlementApprovalDecision`, `SettlementApprovalReasonCode` value types) |
| Dealer self-service finance views | `sales` | `DealerPortalController`, `DealerPortalService` |
| Internal admin/accounting finance views | `portal` | `PortalFinanceController` |
| Accounting AR/journal posting for invoices | `accounting` | Invoice creation triggers accounting via `InvoiceService` → `AccountingService` (not directly via AccountingFacade) |

---

## Host Ownership Map

The ERP exposes finance data through two distinct host families with **strict isolation** between them:

| Host Prefix | Owning Module | Primary Actors | Access Predicate | Data Scope |
| --- | --- | --- | --- | --- |
| `/api/v1/invoices/*` | `invoice` | Admin, Sales, Accounting | `ADMIN_SALES_ACCOUNTING` | Tenant-scoped, any dealer |
| `/api/v1/portal/finance/*` | `portal` | Admin, Accounting | `ADMIN_OR_ACCOUNTING` | By dealerId query param |
| `/api/v1/dealer-portal/*` | `sales` | Dealer only | `DEALER_ONLY` | Auto-scoped to authenticated dealer |

### Internal Finance (Portal Finance)

**Route family**: `/api/v1/portal/finance/**`

Accessible by `ROLE_ADMIN` and `ROLE_ACCOUNTING` via `ADMIN_OR_ACCOUNTING` predicate.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/portal/finance/ledger?dealerId={id}` | Full ledger entries for a specific dealer |
| GET | `/api/v1/portal/finance/invoices?dealerId={id}` | All invoices for a specific dealer |
| GET | `/api/v1/portal/finance/aging?dealerId={id}` | Aging buckets for a specific dealer |

**Actor rules**:
- Sales and Dealer roles are explicitly blocked (403 `SUPER_ADMIN_PLATFORM_ONLY`)
- Company header mismatch fails closed (403 `COMPANY_CONTEXT_MISMATCH`)
- Cross-tenant dealer lookups fail closed (404 `Dealer not found`)

### Dealer Self-Service Finance

**Route family**: `/api/v1/dealer-portal/**`

Accessible by `ROLE_DEALER` only via `DEALER_ONLY` predicate. All data is **auto-scoped** to the authenticated dealer — no query parameters required.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/dealer-portal/dashboard` | Dealer dashboard: balance, credit limit, available credit, aging summary |
| GET | `/api/v1/dealer-portal/ledger` | Full ledger with running balance (own dealer only) |
| GET | `/api/v1/dealer-portal/invoices` | All invoices (own dealer only) |
| GET | `/api/v1/dealer-portal/invoices/{id}/pdf` | Download invoice PDF (own invoice only, audit-logged) |
| GET | `/api/v1/dealer-portal/aging` | Outstanding balance with aging buckets (own dealer only) |
| GET | `/api/v1/dealer-portal/orders` | Own orders |
| POST | `/api/v1/dealer-portal/credit-limit-requests` | Submit credit limit increase request |

**Actor rules**:
- Admin, Accounting, Sales, Factory roles are blocked (403 `Access denied`)
- Company header mismatch fails closed (403 `COMPANY_CONTEXT_MISMATCH`)
- Cross-dealer data access is prevented by auto-scoping

### Self-Service Isolation Boundaries

The dealer self-service surfaces enforce **hard isolation** between dealers:

1. **No dealer ID in request path** — All `/api/v1/dealer-portal/*` endpoints derive the dealer ID from the authenticated JWT token. There is no `?dealerId=` parameter.

2. **Cross-dealer invoice PDF denial** — Attempting to access `/api/v1/dealer-portal/invoices/{id}/pdf` for an invoice belonging to another dealer returns 404 `Invoice not found`.

3. **Tenant boundary enforcement** — A dealer authenticated in company MOCK cannot access RIVAL company data even if they guess invoice IDs.

4. **Read-only except one action** — The only write action available to dealers is `POST /api/v1/dealer-portal/credit-limit-requests`. All other dealer finance endpoints are GET-only.

---

## Invoice Lifecycle

Invoices are created automatically during the **sales fulfillment/dispatch** process. The canonical path is:

```
Sales Order → Confirm → Dispatch → Fulfillment (issueInvoice=true) → Invoice Created
```

### Lifecycle States

| Status | How Entered | Meaning |
| --- | --- | --- |
| `DRAFT` | Initial creation (rare, usually skipped) | Invoice is being prepared |
| `ISSUED` | Created during `SalesFulfillmentService.fulfill()` with `issueInvoice=true` | Invoice is active and payable |
| `PARTIAL` | When payment is applied but outstanding amount remains | Partial payment recorded |
| `PAID` | When outstanding amount reaches zero via `InvoiceSettlementPolicy.applyPayment()` / `applySettlement()` | Invoice fully paid |
| `VOID` | Manual void via `InvoiceSettlementPolicy.voidInvoice()` | Invoice cancelled/invalid |
| `REVERSED` | Manual reversal (via reversePayment) | Payment reversed |

**Notes**:
- The invoice module does **not** currently support automatic payment reconciliation. Settlements are recorded manually via admin operations.
- Invoice status transitions are not enforced by a state machine — status is a mutable field.
- The accounting module receives AR/revenue journals at invoice issuance time via internal `AccountingService` (not directly via AccountingFacade).

### Key Services

| Service | Responsibility |
| --- | --- |
| `InvoiceService` | Core CRUD, invoice issuance |
| `InvoiceNumberService` | Generates sequential invoice numbers |
| `InvoicePdfService` | Generates PDF for invoice download |
| `InvoiceSettlementPolicy` | Settlement logic including `applyPayment()`, `applySettlement()`, `applySettlementWithOverride()`; contains value types `SettlementApprovalDecision` and `SettlementApprovalReasonCode` |

---

## Settlement Behavior

Settlement in the invoice module operates on the following model:

1. **Payment recording** — An admin or accounting user records a payment against an invoice via `InvoiceSettlementPolicy.applyPayment()` or `applySettlement()`.

2. **Settlement evaluation** — The `InvoiceSettlementPolicy` evaluates whether the paid amount satisfies the invoice:
   - If paid amount >= due amount → status transitions to `PAID`
   - If paid amount < due amount → status transitions to `PARTIAL` with partial payment tracked

3. **Accounting impact** — Settlement is handled by the accounting module via internal services (`SettlementService`, `AccountingService`). The invoice module does not directly invoke `AccountingFacade` for settlement.

4. **No automatic reconciliation** — The system does not currently match payments to invoices automatically. Manual settlement is required.

5. **Dunning interaction** — The `DunningService` in sales evaluates overdue invoices and applies dunning holds. Dunning is independent of settlement status but operates on the same outstanding balance concept.

---

## Ledger, Aging, and Statement Surfaces

Both internal finance and dealer self-service expose ledger and aging views. The data model is shared, but the access patterns differ.

### Ledger

- **Internal**: `GET /api/v1/portal/finance/ledger?dealerId={id}` — Admin/Accounting can query any dealer's ledger
- **Dealer self-service**: `GET /api/v1/dealer-portal/ledger` — Dealer sees only their own ledger

The ledger contains:
- Transaction date
- Transaction type (INVOICE, PAYMENT, CREDIT_NOTE, ADJUSTMENT)
- Reference (invoice number, payment reference)
- Debit amount
- Credit amount
- Running balance

### Aging

- **Internal**: `GET /api/v1/portal/finance/aging?dealerId={id}` — Admin/Accounting can query any dealer's aging
- **Dealer self-service**: `GET /api/v1/dealer-portal/aging` — Dealer sees only their own aging

Aging buckets are typically:
- `CURRENT` — Not yet due
- `30_DAYS` — 1-30 days overdue
- `60_DAYS` — 31-60 days overdue
- `90_DAYS` — 61-90 days overdue
- `OVER_90_DAYS` — More than 90 days overdue

### Invoice List

- **Internal**: `GET /api/v1/portal/finance/invoices?dealerId={id}` — Admin/Accounting can query any dealer's invoices
- **Dealer self-service**: `GET /api/v1/dealer-portal/invoices` — Dealer sees only their own invoices

Invoice list fields include:
- Invoice number, date, due date
- Status (DRAFT, ISSUED, PARTIAL, PAID, VOID, REVERSED)
- Total amount, outstanding amount
- PDF download link (for own invoices only)

---

## Deprecated / Non-Canonical Paths

The following paths have been **retired** and return 404:

| Retired Path | Former Actor | Replacement |
| --- | --- | :--- |
| `GET /api/v1/dealers/{id}/ledger` | Admin, Sales, Accounting | `GET /api/v1/portal/finance/ledger?dealerId={id}` |
| `GET /api/v1/dealers/{id}/invoices` | Admin, Sales, Accounting | `GET /api/v1/portal/finance/invoices?dealerId={id}` |
| `GET /api/v1/dealers/{id}/aging` | Admin, Sales, Accounting | `GET /api/v1/portal/finance/aging?dealerId={id}` |
| `GET /api/v1/invoices/dealers/{id}` | Admin | `GET /api/v1/portal/finance/invoices?dealerId={id}` |
| `GET /api/v1/accounting/aging/dealers/{id}` | Accounting | `GET /api/v1/portal/finance/aging?dealerId={id}` |
| `GET /api/v1/accounting/statements/dealers/{id}` | Accounting | Use portal finance endpoints |
| `GET /api/v1/reports/aging/dealer/{id}` | Accounting | `GET /api/v1/portal/finance/aging?dealerId={id}` |
| `GET /api/v1/reports/dso/dealer/{id}` | Accounting | Use portal finance aging |

All legacy dealer finance aliases have been removed from `openapi.json` and the endpoint inventories. The canonical paths are `/api/v1/portal/finance/*` for internal and `/api/v1/dealer-portal/*` for dealer self-service.

---

## Frontend Contract Boundaries

### For Internal Finance (Admin/Accounting)

| Host | Payload Family | Read/Write | RBAC |
| --- | --- | :---: | --- |
| `/api/v1/portal/finance/ledger` | `LedgerEntryDto[]` | Read | `ADMIN_OR_ACCOUNTING` |
| `/api/v1/portal/finance/invoices` | `InvoiceDto[]` | Read | `ADMIN_OR_ACCOUNTING` |
| `/api/v1/portal/finance/aging` | `AgingDto` | Read | `ADMIN_OR_ACCOUNTING` |
| `/api/v1/invoices` | `InvoiceDto[]`, `InvoiceDto` | Read | `ADMIN_SALES_ACCOUNTING` |
| `/api/v1/invoices/{id}/pdf` | `application/pdf` | Read | `ADMIN_SALES_ACCOUNTING` |
| `/api/v1/invoices/{id}/email` | — | Write (action) | `ADMIN_SALES_ACCOUNTING` |

### For Dealer Self-Service

| Host | Payload Family | Read/Write | RBAC |
| --- | --- | :---: | --- |
| `/api/v1/dealer-portal/dashboard` | `DealerDashboardDto` | Read | `DEALER_ONLY` |
| `/api/v1/dealer-portal/ledger` | `LedgerEntryDto[]` | Read | `DEALER_ONLY` |
| `/api/v1/dealer-portal/invoices` | `InvoiceDto[]` | Read | `DEALER_ONLY` |
| `/api/v1/dealer-portal/invoices/{id}/pdf` | `application/pdf` | Read | `DEALER_ONLY` |
| `/api/v1/dealer-portal/aging` | `AgingDto` | Read | `DEALER_ONLY` |
| `/api/v1/dealer-portal/credit-limit-requests` | `CreditLimitRequestDto` | Write (create) | `DEALER_ONLY` |

**Key notes**:
- Dealer endpoints auto-scope to the authenticated dealer — no dealer ID parameter needed
- PDF download for dealer's own invoices is audit-logged
- The only write action available to dealers is credit limit request submission

---

## Cross-Module Seams

| Seam | Modules Involved | Boundary Behavior |
| --- | --- | --- |
| Invoice creation | Sales → Invoice | `SalesFulfillmentService.fulfill(issueInvoice=true)` triggers invoice creation via `InvoiceService.issueInvoiceForOrder()` |
| AR posting | Invoice → Accounting | Invoice issuance triggers accounting via internal `AccountingService` (not directly via AccountingFacade) |
| Sales journal posting | Sales → Accounting | `SalesFulfillmentService` has `postSalesJournal` option; when `issueInvoice=true`, postSalesJournal is automatically disabled because Invoice owns AR/Revenue/Tax posting |
| Settlement posting | Invoice → Accounting | Settlement via `InvoiceSettlementPolicy` triggers accounting via internal settlement services |
| Credit limit request | Dealer Portal → Sales | `DealerPortalController` delegates to `CreditLimitRequestService` |
| Dunning evaluation | Invoice → Sales | `DunningService` reads invoice outstanding balances |

---

## Known Limitations

1. **No automatic payment reconciliation** — Payments must be recorded manually against invoices.
2. **Settlement is manual** — No scheduled job or webhook triggers settlement; admin action required.
3. **No invoice approval workflow** — Invoices are issued automatically during dispatch; no separate approval step.
4. **Limited invoice adjustment** — Credit notes and invoice corrections are not fully implemented.
5. **Aging is simple** — Aging buckets are calculated from due date; custom aging configurations are not supported.

---

## Cross-references

- [sales.md](sales.md) — Sales module, order lifecycle, dispatch coordination, dealer management
- [admin-portal-rbac.md](admin-portal-rbac.md) — Host ownership map, PortalRoleActionMatrix predicates
- [purchasing.md](purchasing.md) — Purchase invoices (AP side)
- [inventory.md](inventory.md) — Stock and dispatch execution
- [core-security-error.md](core-security-error.md) — Security filter chain, access denial messages
- [core-idempotency.md](core-idempotency.md) — Idempotency helpers
- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/flows/invoice-dealer-finance.md](../flows/invoice-dealer-finance.md) — canonical invoice/dealer finance flow (behavioral entrypoint)
