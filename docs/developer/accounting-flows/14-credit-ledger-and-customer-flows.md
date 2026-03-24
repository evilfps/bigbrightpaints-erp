# Credit, Ledger, and Customer-Facing Accounting Flows

## Folder Map

- `modules/sales/controller`
  Purpose for this slice: dealer portal, dealer admin views, credit requests, and credit override routes.
- `modules/sales/service`
  Purpose for this slice: dealer ledger views, credit approvals, dispatch override checks, and dealer self-service reads.
- `modules/accounting/controller`
  Purpose for this slice: statement, aging, settlement, and supplier/dealer accounting actions.
- `modules/accounting/service`
  Purpose for this slice: ledger-backed statements, settlements, and temporal balance helpers.
- `modules/invoice/controller`
  Purpose for this slice: invoice read, export, and email surfaces over receivable truth.

## Canonical Workflow Graph

```mermaid
flowchart LR
    SCR["SalesController credit requests"] --> SDC["SalesDealerCrudService"]
    SDC --> SCE["SalesCoreEngine"]
    SCE --> DEAL["DealerRepository"]

    OVC["CreditLimitOverrideController"] --> OVS["CreditLimitOverrideService"]
    OVS --> DLS["DealerLedgerService"]
    OVS --> PSR["PackagingSlipRepository / SalesOrderRepository"]

    DPC["DealerPortalController"] --> DPS["DealerPortalService"]
    DPS --> DS["DealerService.ledgerView"]
    DPS --> IR["InvoiceRepository"]

    ACC["AccountingController"] --> STS["StatementService"]
    ACC --> SET["SettlementService"]
    ACC --> TBS["TemporalBalanceService"]
    INV["InvoiceController"] --> IVS["InvoiceService / InvoicePdfService"]

    STS --> LED["DealerLedgerRepository / SupplierLedgerRepository"]
    SET --> LED
    DS --> LED
    IVS --> LED
```

## Major Workflows

### Credit Request Pipeline

- entry:
  - `SalesController.creditRequests`
  - `SalesController.createCreditRequest`
  - `SalesController.approveCreditRequest`
  - `SalesController.rejectCreditRequest`
- canonical path:
  - `SalesDealerCrudService`
  - `SalesCoreEngine`
  - `CreditRequestRepository`
  - on approval, mutate the dealer credit limit directly
- key functions:
  - `listCreditRequests`
  - `createCreditRequest`
  - `approveCreditRequest`
  - `rejectCreditRequest`
- important semantic:
  - this is a durable credit-limit mutation path, not a temporary exception path

### Credit Override Pipeline

- entry:
  - `CreditLimitOverrideController.createRequest`
  - `listRequests`
  - `approveRequest`
  - `rejectRequest`
- canonical path:
  - `CreditLimitOverrideService`
  - current dealer exposure via `DealerLedgerService`
  - packaging slip or sales order context
  - maker-checker approval metadata
- important semantic:
  - this authorizes temporary dispatch headroom over current credit exposure

### Dealer Portal Ledger / Aging / Invoices

- entry:
  - `DealerPortalController.getDashboard`
  - `getMyLedger`
  - `getMyInvoices`
  - `getMyAging`
  - `getMyInvoicePdf`
- canonical path:
  - `DealerPortalService`
  - `DealerService.ledgerView`
  - invoice repository reads
  - PDF rendering for invoice export
- important semantic:
  - dealer portal is read-only for credit requests and explicit about that

### Accounting Statement / Aging / Settlement

- entry:
  - `AccountingController.dealerStatement`
  - `supplierStatement`
  - `dealerAging`
  - `supplierAging`
  - `settleDealerInvoices`
  - `settleSupplierInvoices`
  - `autoSettleDealer`
  - `autoSettleSupplier`
- canonical path:
  - `StatementService`
  - `SettlementService`
  - `DealerLedgerRepository` / `SupplierLedgerRepository`
- important semantic:
  - these are ledger-backed accounting helpers, not the canonical public report namespace

## What Works

- credit requests and override requests are explicit separate workflows rather than one overloaded endpoint
- dealer ledger truth is reused across dealer portal, dealer admin, and accounting views
- dealer portal credit creation fails closed instead of silently creating shadow credit requests
- invoice export and email routes do not invent their own receivable truth

## Duplicates and Bad Paths

- there are two approval pipelines around dealer credit:
  - `CreditRequest`
  - `CreditLimitOverrideRequest`
- dealer-facing ledger and aging truth is surfaced through multiple hosts:
  - `DealerPortalController`
  - `DealerController`
  - `AccountingController`
  - `ReportController`
- `ReportService.accountStatement` is not the same thing as `StatementService.dealerStatement`, even though the names sound similar
- `DealerPortalController.POST /credit-requests` is intentionally dead and returns `403`, which is correct but still a visible stale seam

## Review Hotspots

- `SalesCoreEngine.createCreditRequest`
- `SalesCoreEngine.approveCreditRequest`
- `CreditLimitOverrideService.createRequest`
- `CreditLimitOverrideService.approveRequest`
- `DealerPortalService.buildAgingView`
- `StatementService.dealerStatement`
- `StatementService.supplierStatement`
- `SettlementService`
