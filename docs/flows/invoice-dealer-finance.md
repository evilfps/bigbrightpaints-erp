# Invoice / Dealer Finance Flow

Last reviewed: 2026-04-26

This packet documents the **invoice and dealer finance flow**: invoice issuance after dispatch, dealer and internal finance reads, and accounting-owned receipt/settlement behavior. It keeps invoice issuance, finance disclosure, and settlement truth connected instead of describing old invoice-hosted payment aliases as current behavior.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Dealer** | Self-service finance view | `ROLE_DEALER` on `/api/v1/dealer-portal/**` |
| **Admin** | Full invoice and finance access | `ROLE_ADMIN` |
| **Accounting** | Invoice finance and settlement management | `ROLE_ACCOUNTING` |
| **Sales** | Invoice list/detail/email workflow where exposed | `ROLE_SALES` |

Sensitive finance disclosure is split by host: internal finance uses `/api/v1/portal/finance/**` for admin/accounting users, while dealers use self-scoped `/api/v1/dealer-portal/**` routes.

---

## 2. Entrypoints

### Invoice Management — `InvoiceController` (`/api/v1/invoices/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Invoices | GET | `/api/v1/invoices` | ADMIN, SALES, ACCOUNTING | List invoices; supports exposed filters such as `orderId`. |
| Get Invoice | GET | `/api/v1/invoices/{id}` | ADMIN, SALES, ACCOUNTING | Get invoice detail. |
| Invoice PDF | GET | `/api/v1/invoices/{id}/pdf` | ADMIN, ACCOUNTING | Download invoice PDF. |
| Email Invoice | POST | `/api/v1/invoices/{id}/email` | ADMIN, SALES, ACCOUNTING | Email invoice to dealer. |

### Internal Dealer Finance — `PortalFinanceController` (`/api/v1/portal/finance/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Ledger | GET | `/api/v1/portal/finance/ledger?dealerId={dealerId}` | ADMIN, ACCOUNTING | Full internal finance ledger for a dealer. |
| Invoices | GET | `/api/v1/portal/finance/invoices?dealerId={dealerId}` | ADMIN, ACCOUNTING | Dealer invoice finance read. |
| Aging | GET | `/api/v1/portal/finance/aging?dealerId={dealerId}` | ADMIN, ACCOUNTING | Dealer aging buckets. |

### Dealer Self-Service — `DealerPortalController` (`/api/v1/dealer-portal/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dashboard | GET | `/api/v1/dealer-portal/dashboard` | Dealer | Balance, credit, and aging summary. |
| Ledger | GET | `/api/v1/dealer-portal/ledger` | Dealer | Own transaction history. |
| Invoices | GET | `/api/v1/dealer-portal/invoices` | Dealer | Own invoices. |
| Invoice PDF | GET | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | Dealer | Download own invoice PDF. |
| Aging | GET | `/api/v1/dealer-portal/aging` | Dealer | Own aging buckets. |

### Accounting Receipt and Settlement — `SettlementController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dealer Receipt | POST | `/api/v1/accounting/receipts/dealer` | ADMIN, ACCOUNTING | Record party-first dealer receipt. |
| Dealer Hybrid Receipt | POST | `/api/v1/accounting/receipts/dealer/hybrid` | ADMIN, ACCOUNTING | Record receipt split across incoming payment lines. |
| Dealer Settlement | POST | `/api/v1/accounting/settlements/dealers` | ADMIN, ACCOUNTING | Allocate settlement to dealer invoices or supported unapplied remainder. |
| Dealer Auto-Settle | POST | `/api/v1/accounting/dealers/{dealerId}/auto-settle` | ADMIN, ACCOUNTING | Apply amount to open dealer invoices in deterministic order. |
| Supplier Settlement | POST | `/api/v1/accounting/settlements/suppliers` | ADMIN, ACCOUNTING | Settle supplier purchases. |
| Supplier Auto-Settle | POST | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | ADMIN, ACCOUNTING | Apply amount to open supplier purchases in deterministic order. |

The canonical idempotency header for receipt/settlement writes is `Idempotency-Key`. The legacy `X-Idempotency-Key` header is rejected.

---

## 3. Preconditions

### Invoice Creation via Dispatch

1. **Sales order is executable** — the order has moved through confirmation/dispatch readiness.
2. **Dispatch confirmation succeeds** — shipped quantities and cost markers are recorded through `/api/v1/dispatch/confirm`.
3. **Accounting configuration is ready** — valuation, COGS, revenue, tax, and related account wiring must be available where required.
4. **No invoice approval gate** — invoice issuance after dispatch is not approval-gated.

### Receipt and Settlement

1. **Party exists and is tenant-scoped** — dealer or supplier belongs to the current company.
2. **Amount is positive** — zero/negative payments are rejected.
3. **Allocation rules are valid** — allocations must target the same party and cannot exceed supported outstanding amounts.
4. **Override controls** — discount/write-off/FX adjustments require admin override and a reason where implemented.
5. **Idempotency is canonical** — use `Idempotency-Key`; do not use `X-Idempotency-Key`.

### Dealer Finance Reads

1. **Internal reads require admin/accounting** — `/api/v1/portal/finance/**` always uses explicit `dealerId` and finance role gates.
2. **Dealer reads are self-scoped** — `/api/v1/dealer-portal/**` derives the dealer from the authenticated user.
3. **Non-active dealer finance visibility is preserved** — finance read-only access remains for preserved dealer history where the backend allows it.

---

## 4. Lifecycle

### 4.1 Invoice Issuance Lifecycle

```
Sales order confirmed → Dispatch confirmed → Invoice issued →
AR/revenue/COGS/accounting markers linked to dispatch and order
```

Invoice issuance is part of the order-to-cash chain. It happens after dispatch confirmation and does not require a separate approval workflow.

### 4.2 Dealer Collection Lifecycle

```
Dealer payment input → payment truth created → allocation rows resolved →
journal derived → dealer invoice/ledger/aging reads update
```

The important accounting model is party-first, payment-first, allocation-next, journal-derived. Payment and allocation truth are not reconstructed only from journal lines.

### 4.3 Dealer Finance Read Lifecycle

```
Internal finance user selects dealer → portal finance reads explicit dealer truth
Dealer logs in → dealer portal reads self-scoped finance truth
```

Internal and dealer-facing hosts are deliberately separate. Internal finance users pass `dealerId`; dealers never choose another dealer id.

### 4.4 Supplier Settlement Linkage

Supplier settlements use the same accounting boundary principles: payment truth and allocations are explicit, journals are derived, and supplier statement/aging reads reflect the AP outcome.

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Invoice Created** — invoice is issued from dispatch and linked to the order/slip context.
2. **Invoice Disclosed Safely** — internal finance and dealer self-service reads return only authorized tenant/dealer data.
3. **Receipt or Settlement Recorded** — accounting records payment, allocation, and journal evidence.
4. **Balances Reconcile** — dealer ledger, invoice status/outstanding, aging, and portal finance views read the same surviving truth.

### Current Limitations

1. **No invoice approval workflow** — dispatch-linked invoice issuance is intentionally not approval-gated.
2. **No invoice-hosted payment aliases** — settlement writes live under `/api/v1/accounting/**`.
3. **No automatic bank-feed matching** — collections/settlements are backend API workflows, not a bank webhook reconciliation feature.
4. **Dealer statement PDFs under accounting are not exposed** — use portal finance/dealer portal reads for dealer finance disclosure.

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| Auto-issue during dispatch | Dispatch/invoice/accounting chain | Invoice created from dispatch confirmation; no approval gate. |
| `GET /api/v1/invoices` | `InvoiceController` | Invoice list with exposed filters. |
| `GET /api/v1/invoices/{id}` | `InvoiceController` | Invoice detail. |
| `GET /api/v1/invoices/{id}/pdf` | `InvoiceController` | Admin/accounting invoice PDF. |
| `GET /api/v1/portal/finance/ledger?dealerId={dealerId}` | `PortalFinanceController` | Internal dealer finance ledger. |
| `GET /api/v1/portal/finance/invoices?dealerId={dealerId}` | `PortalFinanceController` | Internal dealer invoice finance read. |
| `GET /api/v1/portal/finance/aging?dealerId={dealerId}` | `PortalFinanceController` | Internal dealer aging. |
| `GET /api/v1/dealer-portal/ledger` | `DealerPortalController` | Dealer self-service ledger. |
| `GET /api/v1/dealer-portal/invoices` | `DealerPortalController` | Dealer self-service invoices. |
| `GET /api/v1/dealer-portal/aging` | `DealerPortalController` | Dealer self-service aging. |
| `POST /api/v1/accounting/receipts/dealer` | `SettlementController` | Dealer receipt. |
| `POST /api/v1/accounting/settlements/dealers` | `SettlementController` | Dealer settlement. |
| `POST /api/v1/accounting/dealers/{dealerId}/auto-settle` | `SettlementController` | Dealer auto-settle. |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| Invoice-hosted payment alias | Not current settlement surface | `POST /api/v1/accounting/receipts/dealer` or `/settlements/dealers` |
| Invoice-hosted settle alias | Not current settlement surface | `POST /api/v1/accounting/settlements/dealers` |
| Invoice-hosted void alias | Not current public finance workflow here | Use accounting correction/credit-note paths where supported. |
| Invoice-hosted reverse-payment alias | Not current settlement surface | Use accounting correction/reversal paths where supported. |
| `GET /api/v1/dealers/{id}/ledger` | Retired (410) | `/api/v1/portal/finance/ledger?dealerId={dealerId}` |
| `GET /api/v1/dealers/{id}/invoices` | Retired (410) | `/api/v1/portal/finance/invoices?dealerId={dealerId}` |
| `GET /api/v1/dealers/{id}/aging` | Retired (410) | `/api/v1/portal/finance/aging?dealerId={dealerId}` |
| `GET /api/v1/reports/aging/dealer/{id}` | Retired | `/api/v1/portal/finance/aging?dealerId={dealerId}` |
| `/api/v1/accounting/statements/dealers/**` or `/api/v1/accounting/aging/dealers/**` | Not exposed | Portal finance and dealer portal finance reads. |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `sales` | Dispatch triggers invoice creation and dealer balance changes | Trigger |
| `invoice` | Invoice list/detail/PDF/email surfaces | Read/write |
| `accounting` | AR journals, payment events, allocation rows, settlement journals, dealer ledger updates | Write/read |
| `portal` | Internal and dealer self-service finance reads | Read |
| `inventory` | Dispatch and slip context for invoice issuance | Read/trigger |

---

## 8. Security Considerations

- **Host isolation** — internal finance is `/api/v1/portal/finance/**`; dealer self-service is `/api/v1/dealer-portal/**`.
- **RBAC** — admin/accounting for internal finance and settlement; dealer only for self-scoped finance reads.
- **Dealer auto-scoping** — dealer portal reads derive the dealer from authentication context.
- **Cross-dealer prevention** — dealer users cannot pass arbitrary dealer ids.
- **Sensitive PDFs** — invoice PDF and supplier/dealer finance disclosures follow the current role gates.
- **Company context mismatch** — tenant mismatch fails closed.

---

## 9. Related Documentation

- [Accounting Workflow Architecture](accounting-workflow-architecture.md) — connected accounting architecture for client sharing
- [Order-to-Cash Flow](order-to-cash.md) — sales order, dispatch, invoice chain
- [Reporting / Export Flow](reporting-export.md) — reporting and export approval behavior
- [docs/modules/invoice.md](../modules/invoice.md) — invoice module packet
- [docs/modules/sales.md](../modules/sales.md) — sales module packet
- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md) — host ownership and predicates

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Automatic bank matching | Not implemented; settlement is an API/operator workflow. |
| Invoice approval workflow | Not implemented by design; invoices are issued after dispatch without an approval gate. |
| Invoice-hosted settlement aliases | Not exposed as current public settlement truth. |
| Dealer statement PDFs under accounting aliases | Not exposed; use portal finance/dealer portal reads. |
