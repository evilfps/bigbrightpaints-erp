# Canonical Vocabulary (System-Map)

Last reviewed: 2026-02-16

Use this file as the naming source of truth for cross-module docs and execution ledgers.

## External entity terms
- `partner`: canonical cross-module term for external commercial entities.
- `dealer`: sales/O2C role specialization of `partner`.
- `supplier`: purchasing/P2P role specialization of `partner`.

## Idempotency and replay terms
- `idempotencyKey`: canonical field name for replay identity in headers, conflict details, and metadata.
- `replay conflict`: canonical result for same `idempotencyKey` with payload/ownership mismatch.
- Avoid introducing alternate field names for the same concept (`requestKey`, `idemKey`, `retryKey`).

## Replay conflict partner wording contract
- Conflict `reason` text keeps domain role wording:
  - dealer path: `Idempotency key already used for another dealer`
  - supplier path: `Idempotency key already used for another supplier`
  - fallback/unknown partner type: `Idempotency key already used for another partner type`
- Conflict `details` keys remain canonical and role-agnostic across all paths:
  - `idempotencyKey`
  - `partnerType`
  - `partnerId`
- `partnerType` detail value is uppercase role label (`DEALER`, `SUPPLIER`), or `"null"` when partner type is not provided.

## Ledger-gate execution terms
- `RELEASE_ANCHOR_SHA`: fixed commit SHA before the active hardening train.
- `DIFF_BASE`: gate-fast diff baseline, set to `RELEASE_ANCHOR_SHA` for strict final ledger runs.
- `final ledger gates`: `gate_fast` (strict anchored), `gate_core`, `gate_reconciliation`, `gate_release`.

## Workflow names
- `O2C`: dealer order-to-cash chain.
- `P2P`: supplier procure-to-pay chain.
- `Production-to-Pack`: production/factory to inventory/accounting movement chain.
- `Period Close`: accounting close/reopen and reconciliation boundary controls.
