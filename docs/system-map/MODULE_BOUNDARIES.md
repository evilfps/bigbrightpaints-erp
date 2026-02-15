# Module Boundaries

Last reviewed: 2026-02-15

Boundary ownership:
- `partner` is the canonical cross-module term for external commercial entities; `dealer` and `supplier` are role-specific terms inside sales and purchasing flows.
- `accounting` owns period control, ledger posting, settlement, and ledger/report aggregation.
- `sales` owns order capture, fulfillment triggers, pricing/exposure and dealer-facing sales contracts.
- `inventory` owns stock entities, movements, adjustments, and valuation inputs for accounting.
- `purchasing` owns supplier, PO, GRN, and raw material purchase lifecycle.
- `factory` and `production` own batching, packing, and production logs.
- `hr` owns payroll calculation data and run lifecycle.
- `admin`, `auth`, `company`, `rbac` own governance and guardrails.
- `portal` owns cross-portfolio dashboard and insights composition.
- `reports` owns read models, formatting, and reconciliation visibility.
- `orchestrator` owns cross-module command dispatch, scheduling, and exactly-once semantics.

Boundary direction:
- `orchestrator` -> `sales`, `inventory`, `accounting`, `hr` for coordinated automation.
- `sales` -> `inventory` for reservations and dispatch fulfillment.
- `sales` -> `accounting` for invoice/journal/ledger side effects.
- `purchasing` -> `accounting` for partner posting and settlement (supplier role).
- `factory/production` -> `inventory` for batch and movement outputs; -> `accounting` for cost transfer.
- `portal` reads from `sales`/`inventory`/`accounting` views without mutating source-of-truth modules.

Crossing rules:
- Mutations should be initiated by owning module controllers/services.
- `orchestrator` and API controllers may orchestrate but should preserve canonical write ownership.
- Contract guards enforce portal scope and orchestrator correlation; violations are high-priority debt.

Source references used:
- `docs/ARCHITECTURE.md`, `erp-domain/docs/MODULE_FLOW_MAP.md`, `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`, `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`.
