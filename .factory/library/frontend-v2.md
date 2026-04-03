# Frontend V2 Notes

Final backend-facing handoff for frontend-v2 consumers after the merged `truth-rails`, `o2c-truth`, `p2p-truth`, `corrections-and-control`, and `portal-boundaries` packets.

## Status

- Updated: `2026-03-15`
- Detailed API/reference source: `.factory/library/frontend-handoff.md`
- Mission scope source: `.factory/library/erp-definition-of-done.md`
- Cleanup/provenance source: `.factory/library/remediation-log.md`

> This repository remains backend-first. There is no separate frontend app in-repo, so these notes describe the backend contract the frontend should now treat as delivered.

## Delivered Backend Contract Snapshot

### O2C truth

- Dealer onboarding now provisions the dealer record, receivable account, and portal identity together.
- Commercial ordering stays separate from stock/accounting truth until canonical dispatch/invoice boundaries execute.
- The factory operational dispatch surface is the read-only prepared-slip namespace `GET /api/v1/dispatch/{pending,preview/{slipId},slip/{slipId},order/{orderId}}`.
- The canonical public dispatch posting surface is `POST /api/v1/dispatch/confirm`; pure factory callers receive the operationally redacted response, while admin/elevated callers keep the permitted finance-linked fields.
- The finance repair surface is `POST /api/v1/sales/dispatch/reconcile-order-markers`; it is not a second public dispatch writer.
- The legacy orchestrator batch-dispatch surface `POST /api/v1/orchestrator/factory/dispatch/{batchId}` is no longer a posting path; it now fails closed with `410 Gone` and `canonicalPath=/api/v1/dispatch/confirm`.
- The orchestrator fulfillment endpoint must not be used to force `SHIPPED`/`DISPATCHED`/`FULFILLED`/`COMPLETED`; those requests now fail closed with `BUS_001` and direct callers back to `/api/v1/dispatch/confirm`.
- Factory-facing dispatch reads must be treated as operational-only: logistics metadata and challan access are exposed, while pricing/accounting fields are intentionally redacted.
- Delivery challan output is available from the dispatch flow and replay stays idempotent.

### P2P truth

- `POST /api/v1/suppliers` now returns suppliers with linked payable-account identity already provisioned.
- `PENDING`, `APPROVED`, and `SUSPENDED` suppliers remain visible for lookup/reference, but transactional progression and posting paths fail closed with business-language blockers.
- GRN is the stock truth boundary only; purchase invoice is the AP truth boundary only.

### Corrections and control

- Dealer and supplier settlement guidance is header-level only for this mission phase.
- Manual journals are controlled accounting adjustments, not workflow glue.
- Admin-only, per-document approvals/overrides are explicit backend contract concepts.
- Closed-period posting requires a time-bounded admin exception rather than silent mutation.
- Posted business documents are corrected through linked returns, notes, reversals, or reissue flows instead of in-place edits.

### Portal boundaries

- Dealer portal is dealer-scoped and limited to the authenticated dealer's own dashboard, ledger, invoices, aging, orders, invoice PDF export, and permanent credit-limit request submission.
- Expose only the durable credit-limit request CTA in the dealer portal. Do not expose dispatch overrides or other tenant-internal workflow CTAs there.
- Dealer pending-order exposure and admin aging views now share the same linkage/parity rules.
- Factory owns the dispatch-confirm UX, while accounting stays on finance trail and repair surfaces after dispatch posts.
- Super Admin is platform-only and must not be routed into tenant business workflows or tenant portal dashboards.
- Cross-tenant/foreign-record attempts fail closed without exposing the foreign document body.

## Role-Surface Assumptions Frontend Must Follow

| Role | Frontend should expose | Frontend must not expose |
|---|---|---|
| `ROLE_ADMIN` | tenant operational + finance views, approvals, dispatch workspace, canonical `POST /api/v1/dispatch/confirm`, accounting repair surfaces | platform-only super-admin control plane actions |
| `ROLE_ACCOUNTING` | settlements, approval review, finance trail, dispatch marker reconciliation | `POST /api/v1/dispatch/confirm` or factory-only shipment workspace actions |
| `ROLE_SALES` | dealer onboarding/search, commercial order flows, order-linked dispatch status reads | dispatch-confirm CTAs or accounting-only approval-review actions |
| `ROLE_FACTORY` | operational dispatch preview/confirm, challan handling, shipment metadata capture | pricing/accounting detail reserved for elevated callers, finance repair controls |
| `ROLE_DEALER` | own dashboard/ledger/invoices/aging/orders/PDF export and permanent credit-limit request submission | dispatch overrides, foreign-record access, broader tenant workflow actions |
| `ROLE_SUPER_ADMIN` | platform control-plane flows only | tenant portal, tenant dispatch, tenant settlement, tenant sales execution |

## Contract Notes That Commonly Affect UI

- Surface backend blocker text verbatim for dispatch/portal denials; the mission normalized these messages into business-language guidance.
- Treat factory dispatch previews and slip reads as redacted operational data, not a finance summary.
- Use `POST /api/v1/dispatch/confirm` responses for invoice/journal linkage; pure factory sessions stay redacted while admin/elevated sessions keep the permitted finance-linked fields.
- Tenant runtime lifecycle/quota changes are superadmin-only control-plane actions on `PUT /api/v1/superadmin/tenants/{id}/lifecycle` and `PUT /api/v1/superadmin/tenants/{id}/limits`; keep tenant-admin tooling off retired admin/company runtime-policy aliases.
- Remove any frontend CTA or retry logic that targets `/api/v1/orchestrator/factory/dispatch/{batchId}`; route stale shipment-posting callers to `POST /api/v1/dispatch/confirm` and keep factory UX on the canonical dispatch workspace. Orchestrator fulfillment status bumps for shipment completion should also be removed in favor of the canonical dispatch surfaces.
- Keep non-active suppliers visible but disable mutation CTAs when the current supplier state is already known client-side.
- Keep dealer portal scoped and narrow: allow permanent credit-limit request submission, but keep dispatch overrides and other internal workflow actions out of dealer UX.

## Delivered vs Deferred

### Delivered in merged backend code

- O2C/P2P/control/portal truth-boundary changes summarized above.
- Role-boundary normalization for dealer, sales, factory, accounting, admin, and super-admin surfaces.
- Flyway v2-only guidance across mission docs and local run/test instructions.

### Still deferred or separate from this handoff

- The `final-hardening` milestone still owns the end-of-mission real-user/adversarial validation pass.
- Any separate frontend-app implementation work remains outside this backend repository.
- Do not document future UI behavior as shipped unless it is backed by merged code/tests and added to `.factory/library/frontend-handoff.md`.

## Update Rule

- Add new notes here only when merged backend behavior changes frontend expectations.
