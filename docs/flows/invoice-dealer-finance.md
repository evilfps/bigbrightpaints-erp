# Invoice / Dealer Finance Flow

Last reviewed: 2026-03-30

This packet documents the **invoice and dealer finance flow**: the canonical lifecycle for invoice issuance, settlement, and the distinct host ownership between internal finance views and dealer self-service. It covers invoice creation during dispatch, manual settlement recording, and the self-service isolation boundaries for dealer finance data.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Dealer** | Self-service finance view | `/api/v1/dealer-portal/**` |
| **Admin** | Full invoice and finance access | `ROLE_ADMIN` |
| **Accounting** | Invoice and settlement management | `ROLE_ACCOUNTING` |
| **Sales** | View and email invoices | `ROLE_SALES` |

---

## 2. Entrypoints

### Invoice Management — `InvoiceController` (`/api/v1/invoices/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Invoices | GET | `/api/v1/invoices` | ADMIN, SALES, ACCOUNTING | List all invoices |
| Get Invoice | GET | `/api/v1/invoices/{id}` | ADMIN, SALES, ACCOUNTING | Get invoice detail |
| Invoice PDF | GET | `/api/v1/invoices/{id}/pdf` | ADMIN, SALES, ACCOUNTING | Download PDF |
| Email Invoice | POST | `/api/v1/invoices/{id}/email` | ADMIN, SALES, ACCOUNTING | Email invoice to dealer |

### Internal Finance (Portal Finance) — `PortalFinanceController` (`/api/v1/portal/finance/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Ledger | GET | `/api/v1/portal/finance/ledger?dealerId={id}` | ADMIN, ACCOUNTING | Full ledger for dealer |
| Invoices | GET | `/api/v1/portal/finance/invoices?dealerId={id}` | ADMIN, ACCOUNTING | All invoices for dealer |
| Aging | GET | `/api/v1/portal/finance/aging?dealerId={id}` | ADMIN, ACCOUNTING | Aging buckets for dealer |

### Dealer Self-Service — `DealerPortalController` (`/api/v1/dealer-portal/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dashboard | GET | `/api/v1/dealer-portal/dashboard` | Dealer | Balance, credit, aging |
| Ledger | GET | `/api/v1/dealer-portal/ledger` | Dealer | Transaction history |
| Invoices | GET | `/api/v1/dealer-portal/invoices` | Dealer | Outstanding invoices |
| Invoice PDF | GET | `/api/v1/dealer-portal/invoices/{id}/pdf` | Dealer | Download own invoice |
| Aging | GET | `/api/v1/dealer-portal/aging` | Dealer | Aging buckets |

### Invoice Settlement — Accounting Controllers

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Record Payment | POST | `/api/v1/invoices/{id}/payments` | ADMIN, ACCOUNTING | Record payment against invoice |
| Settlement | POST | `/api/v1/invoices/{id}/settle` | ADMIN, ACCOUNTING | Full settlement |
| Void Invoice | POST | `/api/v1/invoices/{id}/void` | ADMIN, ACCOUNTING | Void invoice |
| Reverse Payment | POST | `/api/v1/invoices/{id}/reverse-payment` | ADMIN, ACCOUNTING | Reverse payment |

---

## 3. Preconditions

### Invoice Creation (via Dispatch) Preconditions

1. **Sales order must be CONFIRMED** — dispatch only on confirmed orders
2. **Packaging slip must exist** — valid slip from factory
3. **Credit check passes** — unless valid override approved
4. **Period open** — accounting period not closed/locked

### Manual Invoice Settlement Preconditions

1. **Invoice exists** — valid invoice ID
2. **Invoice not already PAID** — cannot double-settle
3. **Payment amount positive** — greater than zero
4. **Period open** — for payment journal creation

### Invoice PDF Download (Dealer) Preconditions

1. **Dealer owns invoice** — invoice belongs to authenticated dealer
2. **Invoice exists** — valid ID
3. **Audit logged** — PDF download is audit-logged

---

## 4. Lifecycle

### 4.1 Invoice Creation Lifecycle

```
[Sales Order CONFIRMED] → Dispatch Confirm → 
SalesDispatchReconciliationService → InvoiceService.issueInvoice() → 
[End: Invoice ISSUED]
```

**Key behaviors:**
- Invoice created automatically during dispatch confirmation
- Linked to packaging slip and sales order
- AR/Revenue journal created at issuance time (via AccountingService, not AccountingFacade)
- Status set to ISSUED immediately

### 4.2 Invoice Lifecycle Statuses

| Status | How Entered | Meaning |
| --- | --- | --- |
| `DRAFT` | Initial creation (rare) | Invoice being prepared |
| `ISSUED` | Created during dispatch | Active, payable |
| `PARTIAL` | Payment applied < due | Partial payment recorded |
| `PAID` | Payment applied >= due | Fully paid |
| `VOID` | Manual void | Cancelled/invalid |
| `REVERSED` | Manual reversal | Payment reversed |

### 4.3 Settlement Lifecycle

```
[Start] → Validate invoice not PAID → Record payment → 
Calculate outstanding → Update status → 
[End: PARTIAL or PAID]
```

**Key behaviors:**
- Payment recording via `InvoiceSettlementPolicy.applyPayment()` or `applySettlement()`
- If paid >= due → PAID
- If paid < due → PARTIAL
- Settlement via accounting module (not directly via AccountingFacade)
- **No automatic reconciliation** — manual settlement required

### 4.4 Void/Reversal Lifecycle

```
[Start] → Validate invoice status → Create reversal journal → 
Update status → [End: VOID or REVERSED]
```

**Key behaviors:**
- Void: Invoice cancelled, reversal journal created
- Reverse: Payment reversed, status updated

### 4.5 Dealer Self-Service Lifecycle (Read)

```
[Dealer logs in] → Auto-scoping via JWT → 
[End: Dashboard/Ledger/Invoices/Aging]
```

**Key behaviors:**
- All data auto-scoped to authenticated dealer
- No dealer ID parameter in request path
- Cross-dealer access prevented

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Invoice Created** — Invoice ISSUED during dispatch, linked to order and slip
2. **Invoice Viewed** — Dealer can see invoice list, PDF
3. **Invoice Settled** — Payment recorded, status PAID
4. **Invoice Closed** — Void or reversal if needed

### Current Limitations

1. **No automatic payment reconciliation** — Manual settlement required, no webhook or scheduled matching

2. **Settlement manual** — Admin action required; no automatic matching against bank feeds

3. **No invoice approval workflow** — Invoices auto-issued during dispatch, no separate approval

4. **Limited invoice adjustment** — Credit notes and corrections not fully implemented

5. **Aging simple** — Hardcoded buckets (0-30, 31-60, 61-90, 90+), no custom configuration

6. **No partial invoice payment allocation** — Manual specification of which invoice to pay

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| Auto-issue during dispatch | `SalesDispatchReconciliationService` → `InvoiceService` | Invoice created during dispatch confirmation (dispatch triggers `confirmDispatch()` which creates the invoice via the invoice module); `SalesFulfillmentService` is the higher-level orchestrator that includes this flow |
| `GET /api/v1/portal/finance/ledger?dealerId={id}` | `PortalFinanceController` | Internal finance ledger |
| `GET /api/v1/dealer-portal/ledger` | `DealerPortalController` | Dealer self-service |
| `POST /api/v1/invoices/{id}/payments` | `InvoiceController` | Payment recording |
| `GET /api/v1/invoices/{id}/pdf` | `InvoiceController` | PDF download |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/dealers/{id}/ledger` | Retired (410) | `/api/v1/portal/finance/ledger?dealerId={id}` |
| `GET /api/v1/dealers/{id}/invoices` | Retired (410) | `/api/v1/portal/finance/invoices?dealerId={id}` |
| `GET /api/v1/dealers/{id}/aging` | Retired (410) | `/api/v1/portal/finance/aging?dealerId={id}` |
| `GET /api/v1/reports/aging/dealer/{id}` | Retired | `/api/v1/portal/finance/aging?dealerId={id}` |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `sales` | Dispatch triggers invoice creation via SalesDispatchReconciliationService | Trigger |
| `accounting` | AR/Revenue journal posting, settlement journals, dealer ledger updates | Write |
| `inventory` | Invoice linked to dispatch which references packaging slip | Read |

---

## 8. Security Considerations

- **Host isolation** — `/api/v1/portal/finance/**` vs `/api/v1/dealer-portal/**` strictly separated
- **RBAC** — Admin/Accounting for internal, Dealer for self-service
- **Dealer auto-scoping** — No dealer ID parameter, always from JWT
- **Cross-dealer prevention** — Dealer can only access own invoices
- **PDF audit logging** — Invoice downloads logged
- **Company context mismatch** — Returns 403

---

## 9. Related Documentation

- [docs/modules/invoice.md](../modules/invoice.md) — Invoice module canonical packet
- [docs/modules/sales.md](../modules/sales.md) — Sales module (dispatch triggers invoice)
- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md) — Host ownership and predicates
- [docs/modules/core-security-error.md](../modules/core-security-error.md) — Security filter chain
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory

### Relevant ADRs
- [ADR-002-multi-tenant-auth-scoping.md](../adrs/ADR-002-multi-tenant-auth-scoping.md) — Multi-tenant auth scoping (dealer self-service must be tenant-scoped via JWT)
- [ADR-006-portal-and-host-boundary-separation.md](../adrs/ADR-006-portal-and-host-boundary-separation.md) — Portal/host boundary separation (enforces `/api/v1/dealer-portal/**` vs `/api/v1/portal/finance/**` isolation)

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Automatic payment reconciliation | Not implemented. Manual settlement required. |
| Invoice approval workflow | Not implemented. Invoices are auto-issued during dispatch with no approval flow. |
| Credit notes | Limited. Not fully implemented. |
| Custom aging buckets | Not supported. Hardcoded buckets only. |
