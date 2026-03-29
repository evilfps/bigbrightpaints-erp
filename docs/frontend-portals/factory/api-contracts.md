# Factory API Contracts

## Production

- `GET /api/v1/factory/production/logs`
- `POST /api/v1/factory/production/logs`
- production detail endpoints under `/api/v1/factory/production/logs/**`

Rules:

- Production must only start on inventory- and accounting-ready SKUs.
- If backend returns readiness or mapping blockers, keep the user in the
  production flow and show the exact dependency.

## Packing

- `GET /api/v1/factory/packing-records`
- `POST /api/v1/factory/packing-records`
- packing detail endpoints under `/api/v1/factory/packing-records/**`
- packaging reads under `/api/v1/factory/packaging-mappings`

Rules:

- Packing is the bridge between production output and dispatchable goods.
- Packing must not skip required packaging mappings or batch traceability.

## Dispatch

- `GET /api/v1/dispatch/pending`
- `GET /api/v1/dispatch/preview/{slipId}`
- `GET /api/v1/dispatch/slip/{slipId}`
- `GET /api/v1/dispatch/order/{orderId}`
- `POST /api/v1/dispatch/confirm`

Rules:

- `POST /api/v1/dispatch/confirm` is the only dispatch write action frontend
  should call for posting the shipment.
- It is the only canonical O2C posting boundary.
- No alternate dispatch-posting action should exist in sales or accounting UI.
- After confirm succeeds, factory should refresh the slip detail and render the
  returned dispatch confirmation fields:
  - `status`
  - `confirmedAt`
  - `confirmedBy`
  - `deliveryChallanNumber`
  - `deliveryChallanPdfPath`
  - `journalEntryId`
  - `cogsJournalEntryId`
- Invoice visibility after dispatch belongs outside this portal. Sales owns
  order-linked invoice readiness, and dealer-client owns invoice list, detail,
  and download.

## Forbidden From Factory

- `POST /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`
- any COA, default-account, tax-setup, or period-close write action
