# ERP-21 Portal Split Hard Cut

Use this file for ERP-21 host ownership truth, retirement targets, and proof expectations.

## Canonical Host Model

- Dealer-facing finance only under `/api/v1/dealer-portal/**`
- Internal/admin finance only under `/api/v1/portal/finance/**`
- Admin/ops support only under `/api/v1/portal/support/tickets/**`
- Dealer support only under `/api/v1/dealer-portal/support/tickets/**`
- No shared `/api/v1/support/**`

## Exact Internal Finance Paths

- `GET /api/v1/portal/finance/ledger`
- `GET /api/v1/portal/finance/invoices`
- `GET /api/v1/portal/finance/aging`

## Exact Dealer Portal Finance Paths

- `GET /api/v1/dealer-portal/dashboard`
- `GET /api/v1/dealer-portal/ledger`
- `GET /api/v1/dealer-portal/invoices`
- `GET /api/v1/dealer-portal/aging`
- `GET /api/v1/dealer-portal/orders`
- `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf`
- `POST /api/v1/dealer-portal/credit-limit-requests`

## Exact Support Paths

- `/api/v1/portal/support/tickets/**`
- `/api/v1/dealer-portal/support/tickets/**`

## Retire-First Route Set

- `/api/v1/dealers/{dealerId}/ledger`
- `/api/v1/dealers/{dealerId}/invoices`
- `/api/v1/dealers/{dealerId}/aging`
- `/api/v1/dealers/{dealerId}/credit-utilization`
- `/api/v1/invoices/dealers/{dealerId}`
- `/api/v1/accounting/aging/dealers/{dealerId}`
- `/api/v1/accounting/aging/dealers/{dealerId}/pdf`
- `/api/v1/accounting/statements/dealers/{dealerId}`
- `/api/v1/accounting/statements/dealers/{dealerId}/pdf`
- `/api/v1/reports/aging/dealer/{dealerId}`
- `/api/v1/reports/aging/dealer/{dealerId}/detailed`
- `/api/v1/reports/dso/dealer/{dealerId}`
- `/api/v1/support/**`

Probe retired routes with the strongest actor that used to be allowed on them. A dealer-only denial is not sufficient proof of retirement.

## Packet Scope Rule

- Aggressive deletion is correct inside ERP-21 lane ownership.
- If cleanup crosses into ERP-22/ERP-23 or an unrelated module refactor, return to orchestrator instead of widening the packet.
- If the known unrelated O2C dispatch provenance baseline blocker is still red during ERP-21 development, classify it with proof, do not absorb the fix into ERP-21, and continue the packet.
- Before merge or final PR handoff, rerun the required gates and make sure the final PR is green.

## Contract Surfaces That Must Stay In Sync

- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- `docs/frontend-api/README.md`
- `docs/frontend-portals/accounting/README.md`
- `.factory/library/frontend-handoff.md`
- touched workflow/code-review docs

## Required Proof Themes

- canonical dealer-portal finance host proof
- canonical portal finance host proof
- shared-support retirement proof
- split support host proof
- retired duplicate-finance route proof
- OpenAPI/doc/inventory parity proof
- finance parity proof between dealer and portal hosts for the same seeded dealer
