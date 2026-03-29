# Factory Routes

Every route below belongs to the factory shell and should stay out of sales,
accounting, tenant-admin, and dealer-client.

| UI route | Purpose | Backend contract family |
| --- | --- | --- |
| `/factory/dashboard` | Operational backlog, production queue, packing blockers, dispatch queue counts. | factory dashboard reads |
| `/factory/production/logs` | Production log list, filters, and daily execution queue. | `/api/v1/factory/production/logs` |
| `/factory/production/logs/new` | Create production log for ready SKU and batch context. | `/api/v1/factory/production/logs` |
| `/factory/production/logs/:logId` | Production detail, consumption summary, and correction visibility. | `/api/v1/factory/production/logs/**` |
| `/factory/packing-records` | Packing queue, packing history, and dispatch readiness view. | `/api/v1/factory/packing-records` |
| `/factory/packing-records/new` | Create packing record from produced stock and packaging rules. | `/api/v1/factory/packing-records` |
| `/factory/packing-records/:recordId` | Packing detail, package composition, and readiness blockers. | `/api/v1/factory/packing-records/**` |
| `/factory/packaging-mappings` | Packaging references used by factory execution. | `/api/v1/factory/packaging-mappings` |
| `/factory/dispatch/pending` | Pending dispatch queue and slip selection. | `GET /api/v1/dispatch/pending` |
| `/factory/dispatch/:slipId` | Dispatch detail, preview, challan metadata, and canonical confirm action. | `GET /api/v1/dispatch/preview/{slipId}`, `GET /api/v1/dispatch/slip/{slipId}`, `POST /api/v1/dispatch/confirm` |

Route rules:

- Factory routes may show accounting-readiness blockers but must not embed
  accounting setup editors.
- Dispatch confirm lives only here.
- Factory owns dispatch detail, preview, challan, and post-confirm success
  receipt. It does not own invoice browsing after dispatch.
- Dealer-facing shipment tracking belongs in the dealer-client portal, not here.
