# Accounting Portal Scope Guardrail

> ⚠️ **NON-CANONICAL / REFERENCE ONLY**
>
> This retained accounting-portal scope lock is outside the canonical docs spine.
> Use [docs/frontend-portals/accounting/README.md](frontend-portals/accounting/README.md) and [docs/frontend-api/README.md](frontend-api/README.md) for current portal ownership and shared API boundary truth.
>
> Status: reference only — retained accounting-portal scope lock. If this file conflicts with the canonical portal/API docs, the canonical docs win.

Status: mandatory, do not remove.

## Invariant

HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

This invariant protects route ownership, API contract coverage, QA scope, and release sign-off.

## Change-Control Rule

Any scope change must be applied in one atomic change set with explicit evidence:

1. Updated canonical portal and frontend API docs for every affected portal.
2. Updated `docs/endpoint-inventory.md` module mapping and examples.
3. Added packet evidence covering rationale, impact, and verification plan.

## Required References

- `docs/frontend-portals/accounting/README.md`
- `docs/frontend-api/README.md`
- `docs/endpoint-inventory.md`

## Fail-Closed Policy

If any required artifact is missing or out-of-sync, release gates must fail closed until reconciled.
