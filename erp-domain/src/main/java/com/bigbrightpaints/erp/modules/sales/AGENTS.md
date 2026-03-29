# Sales Module

Last reviewed: 2026-03-29

## Overview

The sales module owns dealer/customer flows, order lifecycle, credit controls, and dispatch ownership. It is the commercial lifecycle truth boundary for order-to-cash.

## What This Module Owns

- **Dealer/customer management** — dealer setup, credit limits, dealer ledger entries.
- **Order lifecycle** — create, update, confirm, and track sales orders.
- **Credit controls** — credit limit checks, credit limit requests (durable), dealer portal credit requests.
- **Dispatch boundary** — dispatch confirmation and coordination with inventory.
- **Dealer portal** — self-service dealer endpoints for orders, statements, and support.

## Primary Controllers

- `SalesController` — order CRUD and dispatch.
- `DealerPortalController` — dealer self-service portal.
- `DealerPortalSupportTicketController` — dealer support tickets.

## Key Services

- `SalesCoreEngine` — order creation, update, and dispatch with idempotency.
- `DealerService` — dealer management and credit controls.
- `DealerPortalService` — dealer self-service operations.
- `CreditLimitRequestService` — durable credit limit request handling.
- `InvoiceService` — invoice issuance and linkage (lives in the invoice module but is closely coupled to sales).

## DTO Families

- `SalesOrder` — order representation.
- `PackagingSlip` — dispatch slip representation.
- `DealerDto` — dealer representation.
- `CreditLimitRequestCreateRequest` / `CreditLimitRequestDecisionRequest` — credit request payloads.

## Cross-Module Boundaries

- **Sales → Inventory:** reservation, dispatch, stock execution.
- **Sales → Invoice:** invoice issuance and linkage.
- **Sales → Accounting:** dispatch-linked journals, AR settlements, credit controls.
- **Sales → Admin:** support ticket access and dealer portal security.

## Canonical Documentation

For the full architecture reference, see:
- [docs/ARCHITECTURE.md](../../../../../../../docs/ARCHITECTURE.md)
- [docs/INDEX.md](../../../../../../../docs/INDEX.md)

## Known Limitations

- Dispatch is a two-layer seam: sales coordinates but inventory executes stock changes.
- Credit limit requests have a requester-identity field that is nullable for historical rows.
- Some legacy dealer endpoints may exist as compatibility aliases.
