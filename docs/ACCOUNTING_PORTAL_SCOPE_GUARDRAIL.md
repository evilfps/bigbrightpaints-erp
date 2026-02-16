# Accounting Portal Scope Guardrail

Status: mandatory, do not remove.

## Invariant

HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

This invariant protects route ownership, API contract coverage, QA scope, and release sign-off.

## Change-Control Rule

Any scope change must be applied in one atomic change set with explicit evidence:

1. Updated portal endpoint map and frontend handoff docs for every affected portal.
2. Updated `docs/endpoint-inventory.md` module mapping and examples.
3. Added `asyncloop` evidence covering rationale, impact, and verification plan.

## Required References

- `docs/accounting-portal-endpoint-map.md`
- `docs/accounting-portal-frontend-engineer-handoff.md`
- `docs/endpoint-inventory.md`

## Fail-Closed Policy

If any required artifact is missing or out-of-sync, release gates must fail closed until reconciled.
