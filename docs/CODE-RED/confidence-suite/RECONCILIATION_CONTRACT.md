# Reconciliation Contract

Mandatory invariants for `gate-reconciliation`.

Authoritative execution scope:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/**`
- `scripts/db_predeploy_scans.sql` rows 9-19 are blocking NO-GO checks.

## Invariant Classes

1. Double-entry integrity
- Every journal entry balances (`sum(debit) == sum(credit)`).
- Flow-level aggregate balancing across O2C, P2P, payroll, manufacturing, adjustments.

2. Idempotency + replay safety
- Same key + same material payload returns same financial effect.
- Same key + different material payload fails with conflict (mismatch-safe replay).

3. Subledger == GL control
- AR control-account balance equals receivables subledger within tolerance.
- AP control-account balance equals payables subledger within tolerance.
- Drift is blocked by scans:
  - `-- 16) AR subledger vs AR control-account mismatch (NO-GO)`
  - `-- 17) AP subledger vs AP control-account mismatch (NO-GO)`

4. Inventory/COGS linkage
- Dispatch and manufacturing inventory movements link to corresponding journals.
- COGS effect matches operational quantity/value movement.
- Drift is blocked by scans:
  - `-- 18) Dispatched slips without dispatch inventory movements (NO-GO)`
  - `-- 19) Dispatch inventory movements not linked to slip COGS journal (NO-GO)`

5. Tax/GST and rounding determinism
- Same input yields identical tax and rounded outcomes across retries.
- Boundary values (fractional rates, half-up edges, zero-tax cases) are deterministic.

6. Period close truth
- Closed periods reject late posting unless explicit controlled override path exists.
- Closed-period reporting reads snapshots; late postings do not drift closed-period output.

7. Chain linkage integrity
- O2C: order -> slip -> invoice -> journal -> ledger linkage complete.
- P2P: GRN -> purchase invoice -> payment/settlement -> journal linkage complete.
- Payroll: run -> post -> payment -> paid state linkage complete.

## Tolerance Policy

- Money tolerance for reconciliation deltas: `<= 0.01`.
- Linkage tolerance: exact (`0` missing links, `0` duplicates).
- Closed-period drift tolerance: exact (`0`).

## Evidence Outputs

`gate-reconciliation` emits:
- `reconciliation-summary.json`
  - total tests, passed/failed, per-module status.
- `mismatch-report.txt`
  - must be empty on pass.

## Failure Triage

1. Identify first failing invariant class from summary.
2. Map failure to matrix row in `FLOW_EVIDENCE_MATRIX.md`.
3. Confirm whether failure is:
- logic regression,
- date/period locking drift,
- idempotency mismatch regression,
- data/setup defect.
4. Fix only canonical boundary path; no side-path bypass patches.
