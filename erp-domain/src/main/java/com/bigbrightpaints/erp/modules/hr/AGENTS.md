# HR Module Agent Map

Last reviewed: 2026-02-15
Scope: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr`

## Purpose
Protect payroll correctness, sensitive employee data handling, and HR-to-accounting posting integrity.

## Canonical References
- `erp-domain/docs/HIRE_TO_PAY_STATE_MACHINES.md`
- `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- `docs/SECURITY.md`
- `docs/RELIABILITY.md`

## Hard Invariants
- Payroll calculations and posting links are deterministic and auditable.
- Payroll liabilities clear only through valid posting/payment flows.
- Employee/personnel data handling follows PII redaction rules.

## Required Checks Before Done
- Targeted payroll/HR tests for changed logic.
- Accounting integration tests when posting/payment paths change.
- `bash ci/check-architecture.sh`
- `bash scripts/verify_local.sh` for payroll posting or period-sensitive edits.

## Escalate to Human (R2)
- Payroll calculation rule changes.
- Payroll posting/liability account semantics changes.
- Permission expansions exposing HR/employee data.

## Anti-Patterns
- Silent fallback payroll posting behavior.
- Logging raw employee-sensitive fields in errors or diagnostics.
