# Factory Portal

The factory portal is the operational workspace for production execution,
packing, packaging validation, dispatch preparation, and canonical dispatch
confirmation. It owns the operational path that turns demand into shipped
product.

## Portal Ownership

- production-log creation and correction within backend policy
- packing-record creation and review
- packaging-mapping visibility needed for packing execution
- finished-good batch lineage and dispatch-slip preparation
- pending-dispatch queue and dispatch confirmation

## Explicit Non-Ownership

- chart of accounts, default accounts, tax setup, journals, reversals
- tenant onboarding, tenant-user management, or export approval
- dealer self-service and customer support ticket ownership

## Canonical Operational Rule

The only supported operational chain is:

`production -> packing -> dispatch confirm`

Frontend must preserve that order in navigation, CTAs, validations, and status
language.

## Posting Boundary Rule

- `POST /api/v1/dispatch/confirm` is the only O2C posting boundary.
- Factory owns that action.
- Sales may observe dispatch state, but factory performs the final confirm.
- If dispatch confirmation fails, frontend must keep the user in the factory
  recovery flow and surface the backend blocker directly.
