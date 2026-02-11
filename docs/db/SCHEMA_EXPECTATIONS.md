# Schema Expectations (Flyway v2)

These expectations are hard requirements for the v2 migration chain and are derived from current entity/repository/service behavior.

## Column and Type Rules

- IDs: `bigint` PKs with sequences/defaults.
- Public IDs: `uuid` (`public_id`) for externally exposed entities where present in code.
- Versioning: every `VersionedEntity` table has `version bigint not null default 0`.
- Money/amounts: `numeric(18,2)` unless code requires higher precision (e.g., FX/tax/event fields).
- Timestamps:
  - `Instant` -> `timestamp with time zone`.
  - `LocalDate` -> `date`.
  - `LocalDateTime` -> `timestamp without time zone` (audit timestamp field usage).
- JSON: `production_products.metadata` stays `jsonb`.
- Enum storage: `varchar` (`@Enumerated(EnumType.STRING)`) with check constraints where stable.

## Nullability and Defaults

- `@Column(nullable = false)` must map to `NOT NULL` in DDL.
- Creation timestamps default to `now()` where entities expect auto-population.
- Numeric counters/quantities with invariant semantics default to zero.
- Optional partner/account links remain nullable where business flow allows deferred linking.

## Multi-Tenancy and Keys

- `company_id` required for operational tables and used in primary query indexes.
- Required per-company unique keys include:
  - `accounts(company_id, code)`
  - `journal_entries(company_id, reference_number)`
  - `accounting_periods(company_id, year, month)`
  - `sales_orders(company_id, order_number)`
  - `invoices(company_id, invoice_number)`
  - `purchase_orders(company_id, order_number)`
  - `raw_material_purchases(company_id, invoice_number)`
  - `goods_receipts(company_id, receipt_number)`
  - `suppliers(company_id, code)`
  - `dealers(company_id, code)`
  - `finished_goods(company_id, product_code)`
  - `raw_materials(company_id, sku)`

## Idempotency Constraints

Unique or strongly indexed idempotency keys must exist for:
- `orchestrator_commands`
- `journal_reference_mappings`
- `catalog_imports`
- `sales_orders` (idempotency markers)
- `inventory_adjustments`
- `opening_stock_imports`
- `raw_material_intake_requests`
- `packing_request_records`
- `payroll_runs` (company + run identity + idempotency key)

## FK and Integrity Expectations

- FK constraints enforced for explicit `@ManyToOne`/join references unless flow requires deferred linkage.
- Accounting integrity: journal lines always reference existing journals/accounts.
- Batch integrity: `finished_good_batches(finished_good_id, batch_code)` and material batch uniqueness are enforced.
- Append-only audit/event surfaces are never updated for business-state mutation.

## Query/Index Expectations

- Composite indexes must match repository list/order patterns (`company_id`, status/date, and stable tie-breakers).
- Outbox polling requires `orchestrator_outbox(status, next_attempt_at)`.
- Reconciliation tables require period/account/date lookup indexes.
- Partner ledgers need `(company_id, partner_id, entry_date)` access paths.

## Compatibility Expectations

- Existing `db/migration` chain remains untouched.
- v2 chain lives in `db/migration_v2` and uses dedicated Flyway history table.
- v2 dev profile uses separate dev database (`erp_domain_v2`) to avoid touching v1 state.
