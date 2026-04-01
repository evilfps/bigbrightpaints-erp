# BigBright Paints ERP — Database Schema Map

> Generated from Flyway v2 migrations (`erp-domain/src/main/resources/db/migration_v2/`)
> and JPA `@Entity` classes across all modules.
> Latest migration: **V172** (as of 2026-03-28)

---

## Overview

- **Database**: PostgreSQL (uses `pgcrypto`, `jsonb`, `timestamptz`, `bigserial`)
- **Multi-tenant**: All business tables have `company_id FK → companies(id)`
- **Idempotency**: Many tables use `(company_id, idempotency_key)` unique constraints
- **Optimistic locking**: All tables have `version BIGINT DEFAULT 0`
- **Public IDs**: Most entities expose `public_id UUID` for external APIs
- **Auth model**: Scoped accounts — each `app_users` row belongs to exactly one company (Auth V2, V168–V169)

**Migration count**: 62 files (V1–V172, some numbers skipped)

---

## Table Inventory by Domain

### 1. Auth & RBAC

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `app_users` | `id PK`, `public_id UUID`, `email`, `password_hash`, `display_name`, `auth_scope_code VARCHAR(64) NOT NULL`, `company_id FK`, `enabled`, `mfa_enabled`, `failed_login_attempts`, `locked_until` | `company_id → companies(id)` | Auth V2: unique on `(email, auth_scope_code)`. `reset_token`/`reset_expiry` dropped (V157). `user_companies` table dropped (V169). |
| `roles` | `id PK`, `name UNIQUE` | — | `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`, etc. |
| `permissions` | `id PK`, `code UNIQUE` | — | Fine-grained permission codes |
| `user_roles` | `user_id FK`, `role_id FK` | `user_id → app_users(id)`, `role_id → roles(id)` | Composite PK |
| `role_permissions` | `role_id FK`, `permission_id FK` | `role_id → roles(id)`, `permission_id → permissions(id)` | Composite PK |
| `refresh_tokens` | `id PK`, `token_digest VARCHAR(64) UNIQUE`, `user_public_id UUID NOT NULL`, `auth_scope_code VARCHAR(64) NOT NULL` | — | Token stored as digest; `user_email` column dropped (V168) |
| `password_reset_tokens` | `id PK`, `user_id FK`, `token_digest VARCHAR(64) UNIQUE`, `delivered_at TIMESTAMPTZ` | `user_id → app_users(id)` | Token stored as digest (V158). `delivered_at` added (V162) |
| `blacklisted_tokens` | `id PK`, `token_id UNIQUE`, `expires_at` | — | JWT blacklist |
| `user_token_revocations` | `id PK`, `user_id UNIQUE` | — | Bulk token revocation |
| `user_password_history` | `id PK`, `user_id FK`, `password_hash` | `user_id → app_users(id)` | Password rotation audit |
| `mfa_recovery_codes` | `id PK`, `user_id FK`, `code_hash`, `used_at` | `user_id → app_users(id)` | Unique `(user_id, code_hash) WHERE used_at IS NULL` |

---

### 2. Company & Tenant Management

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `companies` | `id PK`, `public_id UUID`, `name`, `code UNIQUE`, `timezone`, `base_currency DEFAULT 'INR'`, `lifecycle_state ENUM('ACTIVE','SUSPENDED','DEACTIVATED')`, `enabled_modules JSONB`, `default_gst_rate`, `state_code VARCHAR(2)` | `payroll_expense_account_id → accounts(id)`, `payroll_cash_account_id → accounts(id)`, `gst_input/output/payable_account_id → accounts(id)`, `main_admin_user_id → app_users(id)`, `onboarding_admin_user_id → app_users(id)` | Many default account FKs. `quota_max_concurrent_requests`, `quota_max_active_users`, `quota_max_api_requests`, `quota_max_storage_bytes`, `onboarding_coa_template_code`, `support_notes`, `support_tags JSONB` |
| `system_settings` | `setting_key PK`, `setting_value` | — | Key-value config (`auth.platform.code`, etc.) |
| `number_sequences` | `id PK`, `company_id FK`, `sequence_key`, `next_value` | `company_id → companies(id)` | Unique `(company_id, sequence_key)` |
| `coa_templates` | Entity exists — Chart of Accounts templates for onboarding | — | Referenced via `companies.onboarding_coa_template_code` |
| `tenant_support_warnings` | `id PK`, `company_id FK`, `warning_category`, `message`, `requested_lifecycle_state`, `grace_period_hours`, `issued_by` | `company_id → companies(id) CASCADE` | Created in V167 |
| `tenant_admin_email_change_requests` | `id PK`, `company_id FK`, `admin_user_id FK`, `current_email`, `requested_email`, `verification_token`, `consumed` | `company_id → companies(id)`, `admin_user_id → app_users(id)` | Created in V167 |

---

### 3. Accounting

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `accounts` | `id PK`, `public_id UUID`, `company_id FK`, `code UNIQUE per company`, `name`, `type ENUM`, `balance`, `active`, `parent_id FK`, `hierarchy_level` | `company_id → companies(id)`, `parent_id → accounts(id)` self-ref | Types: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE |
| `accounting_periods` | `id PK`, `public_id UUID`, `company_id FK`, `year`, `month`, `start_date`, `end_date`, `status DEFAULT 'OPEN'`, `costing_method ENUM('FIFO','LIFO','WEIGHTED_AVERAGE')`, `closing_journal_entry_id FK` | `company_id → companies(id)`, `closing_journal_entry_id → journal_entries(id)` | Unique `(company_id, year, month)`. Locked/reopened tracking. |
| `journal_entries` | `id PK`, `public_id UUID`, `company_id FK`, `reference_number UNIQUE per company`, `status DEFAULT 'DRAFT'`, `entry_date`, `journal_type DEFAULT 'AUTOMATED'`, `source_module`, `source_reference`, `dealer_id FK`, `supplier_id FK`, `accounting_period_id FK`, `reversal_of_id FK`, `currency DEFAULT 'INR'`, `foreign_amount_total`, `fx_rate`, `attachment_references TEXT` | `company_id → companies(id)`, `accounting_period_id → accounting_periods(id)`, `dealer_id → dealers(id)`, `supplier_id → suppliers(id)`, `reversal_of_id → journal_entries(id)` self-ref | Double-entry bookkeeping. Status flow: DRAFT → POSTED. Void/reversal support. |
| `journal_lines` | `id PK`, `journal_entry_id FK`, `account_id FK`, `debit`, `credit` | `journal_entry_id → journal_entries(id)`, `account_id → accounts(id) RESTRICT` | Core double-entry lines |
| `accounting_events` | `id PK`, `event_id UUID UNIQUE`, `company_id FK`, `event_type`, `aggregate_id`, `aggregate_type`, `sequence_number`, `effective_date`, `debit_amount`, `credit_amount`, `balance_before`, `balance_after` | `company_id → companies(id)` | Event-sourced audit trail. Unique `(aggregate_id, sequence_number)` |
| `accounting_period_snapshots` | `id PK`, `company_id FK`, `accounting_period_id FK`, `as_of_date`, `trial_balance_total_debit/credit`, `inventory_total_value`, `ar/ap_subledger_total` | `company_id → companies(id)`, `accounting_period_id → accounting_periods(id)` | Unique `(company_id, accounting_period_id)` |
| `accounting_period_trial_balance_lines` | `id PK`, `snapshot_id FK`, `account_id`, `account_code`, `account_type`, `debit`, `credit` | `snapshot_id → accounting_period_snapshots(id) CASCADE` | Trial balance detail per snapshot |
| `dealer_ledger_entries` | `id PK`, `company_id FK`, `dealer_id FK`, `journal_entry_id FK`, `entry_date`, `reference_number`, `debit`, `credit`, `payment_status DEFAULT 'UNPAID'`, `due_date`, `invoice_number` | `company_id → companies(id)`, `dealer_id → dealers(id)`, `journal_entry_id → journal_entries(id)` | AR subledger |
| `supplier_ledger_entries` | `id PK`, `company_id FK`, `supplier_id FK`, `journal_entry_id FK`, `entry_date`, `reference_number`, `debit`, `credit`, `payment_status DEFAULT 'UNPAID'` | `company_id → companies(id)`, `supplier_id → suppliers(id)`, `journal_entry_id → journal_entries(id)` | AP subledger |
| `partner_settlement_allocations` | `id PK`, `company_id FK`, `partner_type ENUM('DEALER','SUPPLIER')`, `dealer_id FK`, `supplier_id FK`, `invoice_id FK`, `purchase_id FK`, `journal_entry_id FK`, `settlement_date`, `allocation_amount`, `discount_amount`, `write_off_amount`, `idempotency_key` | `company_id → companies(id)`, `dealer_id → dealers(id)`, `supplier_id → suppliers(id)`, `invoice_id → invoices(id)`, `purchase_id → raw_material_purchases(id)`, `journal_entry_id → journal_entries(id)` | Partner payment settlement matching. Check: DEALER → dealer_id NOT NULL, SUPPLIER → supplier_id NOT NULL |
| `journal_reference_mappings` | `id PK`, `company_id FK`, `legacy_reference UNIQUE per company`, `canonical_reference`, `entity_type`, `entity_id` | `company_id → companies(id)` | Legacy reference normalization |
| `opening_balance_imports` | `id PK`, `company_id FK`, `idempotency_key UNIQUE per company`, `journal_entry_id FK`, `opening_stock_batch_key NOT NULL`, `results_json TEXT` | `company_id → companies(id)` | Unique `(company_id, opening_stock_batch_key)` |
| `tally_imports` | `id PK`, `company_id FK`, `idempotency_key` | `company_id → companies(id)` | Tally ERP import tracking |
| `bank_reconciliation_sessions` | `id PK BIGSERIAL`, `company_id FK`, `bank_account_id FK → accounts(id)`, `accounting_period_id FK`, `reference_number UNIQUE per company`, `statement_date`, `statement_ending_balance`, `status ENUM('DRAFT','COMPLETED')` | `company_id → companies(id)`, `bank_account_id → accounts(id) RESTRICT`, `accounting_period_id → accounting_periods(id)` | Bank reconciliation |
| `bank_reconciliation_items` | `id PK BIGSERIAL`, `company_id FK`, `session_id FK`, `journal_line_id FK`, `amount`, `cleared_at`, `cleared_by` | `session_id → bank_reconciliation_sessions(id)`, `journal_line_id → journal_lines(id) RESTRICT` | Unique `(session_id, journal_line_id)` |
| `reconciliation_discrepancies` | `id PK BIGSERIAL`, `company_id FK`, `accounting_period_id FK`, `discrepancy_type ENUM('AR','AP','INVENTORY','GST')`, `partner_type ENUM('DEALER','SUPPLIER')`, `expected_amount`, `actual_amount`, `variance`, `status ENUM('OPEN','ACKNOWLEDGED','ADJUSTED','RESOLVED')`, `resolution ENUM('ACKNOWLEDGED','ADJUSTMENT_JOURNAL','WRITE_OFF')` | `company_id → companies(id)`, `resolution_journal_id → journal_entries(id)` | Period-end discrepancy tracking |
| `period_close_requests` | `id PK BIGSERIAL`, `public_id UUID`, `company_id FK`, `accounting_period_id FK`, `status ENUM('PENDING','APPROVED','REJECTED')`, `requested_by`, `force_requested` | `company_id → companies(id)`, `accounting_period_id → accounting_periods(id)` | Only one PENDING per `(company_id, accounting_period_id)` |
| `closed_period_posting_exceptions` | `id PK BIGSERIAL`, `public_id UUID`, `company_id FK`, `accounting_period_id FK`, `document_type`, `document_reference`, `reason`, `approved_by`, `expires_at`, `used_by`, `journal_entry_id FK` | `company_id → companies(id)`, `accounting_period_id → accounting_periods(id)`, `journal_entry_id → journal_entries(id)` | Exception-based posting to closed periods |

---

### 4. Sales & Dealers

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `dealers` | `id PK`, `public_id UUID`, `company_id FK`, `name`, `code UNIQUE per company`, `email`, `phone`, `status DEFAULT 'ACTIVE'`, `credit_limit`, `outstanding_balance`, `receivable_account_id FK`, `portal_user_id FK UNIQUE per company`, `gst_number`, `gst_registration_type DEFAULT 'UNREGISTERED'`, `payment_terms ENUM('NET_30','NET_60','NET_90')`, `region` | `company_id → companies(id)`, `portal_user_id → app_users(id)`, `receivable_account_id → accounts(id)` | Customer/distributor entity |
| `sales_orders` | `id PK`, `public_id UUID`, `company_id FK`, `dealer_id FK`, `order_number UNIQUE per company`, `status`, `total_amount`, `subtotal_amount`, `gst_total`, `gst_treatment`, `gst_inclusive`, `idempotency_key UNIQUE per company`, `sales_journal_entry_id FK`, `cogs_journal_entry_id FK`, `fulfillment_invoice_id FK`, `payment_mode VARCHAR(32) DEFAULT 'CREDIT' NOT NULL` | `company_id → companies(id)`, `dealer_id → dealers(id)`, `sales_journal_entry_id → journal_entries(id)`, `cogs_journal_entry_id → journal_entries(id)`, `fulfillment_invoice_id → invoices(id)` | Core sales order. Auto-approval pipeline. |
| `sales_order_items` | `id PK`, `sales_order_id FK`, `product_code`, `quantity`, `unit_price`, `line_subtotal`, `line_total`, `gst_rate`, `gst_amount` | `sales_order_id → sales_orders(id)` | Line items |
| `sales_order_status_histories` | Entity exists — tracks order status transitions | — | JPA entity |
| `invoices` | `id PK`, `public_id UUID`, `company_id FK`, `dealer_id FK`, `sales_order_id FK`, `invoice_number UNIQUE per company`, `status DEFAULT 'DRAFT'`, `subtotal`, `tax_total`, `total_amount`, `outstanding_amount`, `issue_date`, `due_date`, `journal_entry_id FK` | `company_id → companies(id)`, `dealer_id → dealers(id)`, `sales_order_id → sales_orders(id)`, `journal_entry_id → journal_entries(id)` | GST invoices |
| `invoice_lines` | `id PK`, `invoice_id FK`, `product_code`, `quantity`, `unit_price`, `tax_rate`, `line_total`, `taxable_amount`, `tax_amount`, `discount_amount` | `invoice_id → invoices(id)` | Invoice line items |
| `invoice_payment_refs` | `invoice_id FK`, `payment_reference` | `invoice_id → invoices(id)` | Composite PK |
| `invoice_sequences` | `id PK`, `company_id FK`, `fiscal_year UNIQUE per company` | `company_id → companies(id)` | Auto-numbering |
| `order_sequences` | `id PK`, `company_id FK`, `fiscal_year UNIQUE per company` | `company_id → companies(id)` | Auto-numbering |
| `packaging_slips` | `id PK`, `public_id UUID`, `company_id FK`, `sales_order_id FK`, `slip_number UNIQUE per company`, `status DEFAULT 'PENDING'`, `dispatched_at`, `journal_entry_id FK`, `cogs_journal_entry_id FK`, `invoice_id FK`, `is_backorder`, `transporter_name`, `driver_name`, `vehicle_number`, `challan_reference` | `company_id → companies(id)`, `sales_order_id → sales_orders(id)`, `journal_entry_id → journal_entries(id)`, `invoice_id → invoices(id)` | Dispatch/delivery notes with logistics info |
| `packaging_slip_lines` | `id PK`, `packaging_slip_id FK`, `finished_good_batch_id FK`, `quantity`, `ordered_quantity`, `shipped_quantity`, `backorder_quantity` | `packaging_slip_id → packaging_slips(id)` | Dispatch line items |
| `promotions` | `id PK`, `public_id UUID`, `company_id FK`, `name`, `discount_type`, `discount_value`, `start_date`, `end_date`, `status DEFAULT 'DRAFT'`, `image_url` | `company_id → companies(id)` | Sales promotions |
| `sales_targets` | `id PK`, `public_id UUID`, `company_id FK`, `name`, `period_start`, `period_end`, `target_amount`, `achieved_amount` | `company_id → companies(id)` | Sales targets |
| `credit_requests` | `id PK`, `public_id UUID`, `company_id FK`, `dealer_id FK`, `amount_requested`, `status`, `requester_user_id BIGINT`, `requester_email VARCHAR(255)` | `company_id → companies(id)`, `dealer_id → dealers(id)` | Credit increase requests |
| `credit_limit_override_requests` | `id PK`, `public_id UUID`, `company_id FK`, `dealer_id FK`, `packaging_slip_id FK`, `sales_order_id FK`, `dispatch_amount`, `credit_limit`, `required_headroom`, `status`, `reason` | `company_id → companies(id)`, `dealer_id → dealers(id)`, `packaging_slip_id → packaging_slips(id)`, `sales_order_id → sales_orders(id)` | One-time credit overrides |

---

### 5. Inventory

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `raw_materials` | `id PK`, `public_id UUID`, `company_id FK`, `name`, `sku UNIQUE per company`, `unit_type`, `current_stock`, `private_stock`, `reorder_level`, `min_stock`, `max_stock`, `gst_rate`, `material_type DEFAULT 'PRODUCTION'`, `costing_method DEFAULT 'FIFO'`, `inventory_type DEFAULT 'STANDARD'` | `company_id → companies(id)`, `inventory_account_id → accounts(id)` | Raw material catalog |
| `raw_material_batches` | `id PK`, `public_id UUID`, `raw_material_id FK`, `batch_code UNIQUE per material`, `quantity`, `cost_per_unit`, `supplier_id FK`, `inventory_type` | `raw_material_id → raw_materials(id)`, `supplier_id → suppliers(id)` | Batch-tracked raw material stock |
| `raw_material_movements` | `id PK`, `raw_material_id FK`, `raw_material_batch_id FK`, `reference_type`, `reference_id`, `movement_type`, `quantity`, `unit_cost`, `journal_entry_id FK`, `packing_record_id FK` | `raw_material_id → raw_materials(id)` | Inventory movement audit trail |
| `raw_material_intake_requests` | `id PK`, `company_id FK`, `idempotency_key UNIQUE per company`, `raw_material_id FK`, `raw_material_batch_id FK`, `journal_entry_id FK` | `company_id → companies(id)` | Idempotent intake recording |
| `raw_material_adjustments` | `id PK BIGSERIAL`, `public_id UUID`, `company_id FK`, `reference_number UNIQUE per company`, `adjustment_date`, `status DEFAULT 'DRAFT'`, `journal_entry_id FK`, `total_amount`, `idempotency_key` | `company_id → companies(id)`, `journal_entry_id → journal_entries(id)` | Raw material stock adjustments |
| `raw_material_adjustment_lines` | `id PK BIGSERIAL`, `adjustment_id FK`, `raw_material_id FK`, `quantity`, `unit_cost`, `amount` | `adjustment_id → raw_material_adjustments(id)`, `raw_material_id → raw_materials(id)` | Adjustment line items |
| `finished_goods` | `id PK`, `public_id UUID`, `company_id FK`, `product_code UNIQUE per company`, `name`, `unit`, `current_stock`, `reserved_stock`, `costing_method DEFAULT 'FIFO'`, `inventory_type DEFAULT 'STANDARD'`, `valuation/cogs/revenue/discount/tax_account_id` | `company_id → companies(id)`, various `→ accounts(id)` | Finished goods catalog. Constraint: `chk_finished_goods_no_bulk_suffix` (V172) |
| `finished_good_batches` | `id PK`, `public_id UUID`, `finished_good_id FK`, `batch_code UNIQUE per FG`, `quantity_total`, `quantity_available`, `unit_cost`, `expiry_date`, `parent_batch_id FK`, `size_label`, `inventory_type` | `finished_good_id → finished_goods(id)`, `parent_batch_id → finished_good_batches(id)` | Batch-tracked finished goods. `is_bulk` column dropped (V171). |
| `inventory_movements` | `id PK`, `finished_good_id FK`, `finished_good_batch_id FK`, `reference_type`, `reference_id`, `movement_type`, `quantity`, `unit_cost`, `journal_entry_id FK`, `packing_slip_id FK` | `finished_good_id → finished_goods(id)` | FG movement audit trail |
| `inventory_adjustments` | `id PK`, `public_id UUID`, `company_id FK`, `reference_number`, `adjustment_date`, `adjustment_type`, `status DEFAULT 'DRAFT'`, `journal_entry_id FK`, `total_amount`, `idempotency_key` | `company_id → companies(id)`, `journal_entry_id → journal_entries(id)` | FG stock adjustments |
| `inventory_adjustment_lines` | `id PK`, `adjustment_id FK`, `finished_good_id FK`, `quantity`, `unit_cost`, `amount` | `adjustment_id → inventory_adjustments(id)`, `finished_good_id → finished_goods(id)` | Adjustment line items |
| `inventory_reservations` | `id PK`, `raw_material_id FK`, `finished_good_id FK`, `finished_good_batch_id FK`, `reference_type`, `reference_id`, `quantity`, `reserved_quantity`, `fulfilled_quantity`, `status` | — | Stock reservation for orders/production |
| `opening_stock_imports` | `id PK`, `company_id FK`, `idempotency_key`, `opening_stock_batch_key NOT NULL`, `results_json TEXT`, `journal_entry_id FK` | `company_id → companies(id)` | Initial stock import. Unique `(company_id, opening_stock_batch_key)`. |
| `packaging_size_mappings` | `id PK`, `public_id UUID`, `company_id FK`, `packaging_size`, `raw_material_id FK`, `units_per_pack`, `carton_size`, `liters_per_unit`, `active` | `company_id → companies(id)`, `raw_material_id → raw_materials(id)` | Unique `(company_id, packaging_size, raw_material_id)` |

---

### 6. Production & Factory

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `production_brands` | `id PK`, `public_id UUID`, `company_id FK`, `name UNIQUE per company`, `code UNIQUE per company` | `company_id → companies(id)` | Paint brand catalog |
| `production_products` | `id PK`, `public_id UUID`, `company_id FK`, `brand_id FK`, `product_name`, `category`, `sku_code UNIQUE per company`, `base_price`, `gst_rate`, `min_discount_percent`, `min_selling_price`, `variant_group_id UUID`, `product_family_name VARCHAR(255)` | `company_id → companies(id)`, `brand_id → production_brands(id)` | Product catalog (paints). `variant_group_id` for variant grouping (V163). |
| `size_variants` | `id PK BIGSERIAL`, `public_id UUID`, `company_id FK`, `product_id FK`, `size_label`, `carton_quantity`, `liters_per_unit`, `active` | `company_id → companies(id)`, `product_id → production_products(id)` | Unique `(company_id, product_id, size_label)` |
| `production_plans` | `id PK`, `public_id UUID`, `company_id FK`, `plan_number UNIQUE per company`, `product_name`, `quantity`, `planned_date`, `status DEFAULT 'PLANNED'` | `company_id → companies(id)` | Production planning |
| `production_batches` | `id PK`, `public_id UUID`, `company_id FK`, `plan_id FK`, `batch_number UNIQUE per company`, `quantity_produced` | `company_id → companies(id)`, `plan_id → production_plans(id)` | Actual production runs |
| `production_logs` | `id PK`, `public_id UUID`, `company_id FK`, `brand_id FK`, `product_id FK`, `production_code UNIQUE per company`, `batch_colour`, `batch_size`, `status DEFAULT 'MIXED'`, `mixed_quantity`, `total_packed_quantity`, `wastage_quantity`, `material_cost_total`, `labor_cost_total`, `overhead_cost_total`, `unit_cost`, `sales_order_id FK` | `company_id → companies(id)`, `brand_id → production_brands(id)`, `product_id → production_products(id)` | Core production log. Status: MIXED → PACKED |
| `production_log_materials` | `id PK`, `log_id FK`, `raw_material_id FK`, `material_name`, `quantity`, `unit_of_measure`, `cost_per_unit`, `total_cost` | `log_id → production_logs(id)`, `raw_material_id → raw_materials(id)` | Materials consumed in production |
| `packing_records` | `id PK`, `public_id UUID`, `company_id FK`, `production_log_id FK`, `finished_good_id FK`, `finished_good_batch_id FK`, `packaging_size`, `quantity_packed`, `pieces_count`, `boxes_count`, `size_variant_id FK`, `packaging_material_id FK`, `packaging_cost` | `production_log_id → production_logs(id)`, `finished_good_id → finished_goods(id)`, `size_variant_id → size_variants(id)` | Packing output from production |
| `packing_request_records` | `id PK`, `company_id FK`, `idempotency_key UNIQUE per company`, `production_log_id FK`, `packing_record_id FK` | `company_id → companies(id)` | Idempotent packing requests |
| `factory_tasks` | `id PK`, `public_id UUID`, `company_id FK`, `title`, `assignee`, `status DEFAULT 'PENDING'`, `sales_order_id FK`, `packaging_slip_id FK` | `company_id → companies(id)` | Factory floor task management |
| `catalog_imports` | `id PK`, `company_id FK`, `idempotency_key UNIQUE per company`, `file_name`, `rows_processed`, `errors_json` | `company_id → companies(id)` | Bulk product catalog import |

---

### 7. Purchasing & Suppliers

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `suppliers` | `id PK`, `public_id UUID`, `company_id FK`, `code UNIQUE per company`, `name`, `status DEFAULT 'ACTIVE'`, `credit_limit`, `payable_account_id FK`, `gst_number`, `state_code`, `gst_registration_type` | `company_id → companies(id)`, `payable_account_id → accounts(id)` | Vendor master. `outstanding_balance` dropped (V170). |
| `purchase_orders` | `id PK`, `public_id UUID`, `company_id FK`, `supplier_id FK`, `order_number UNIQUE per company`, `order_date`, `status DEFAULT 'OPEN'`, `memo` | `company_id → companies(id)`, `supplier_id → suppliers(id)` | Purchase order header |
| `purchase_order_items` | `id PK`, `purchase_order_id FK`, `raw_material_id FK`, `quantity`, `unit`, `cost_per_unit`, `line_total` | `purchase_order_id → purchase_orders(id)`, `raw_material_id → raw_materials(id)` | PO line items |
| `purchase_order_status_histories` | Entity exists — tracks PO status transitions | — | JPA entity |
| `goods_receipts` | `id PK`, `public_id UUID`, `company_id FK`, `supplier_id FK`, `purchase_order_id FK`, `receipt_number UNIQUE per company`, `receipt_date`, `status DEFAULT 'RECEIVED'`, `idempotency_key` | `company_id → companies(id)`, `supplier_id → suppliers(id)`, `purchase_order_id → purchase_orders(id)` | GRN (Goods Receipt Note) |
| `goods_receipt_items` | `id PK`, `goods_receipt_id FK`, `raw_material_id FK`, `raw_material_batch_id FK`, `batch_code`, `quantity`, `cost_per_unit`, `line_total` | `goods_receipt_id → goods_receipts(id)`, `raw_material_id → raw_materials(id)`, `raw_material_batch_id → raw_material_batches(id)` | GRN line items |
| `raw_material_purchases` | `id PK`, `public_id UUID`, `company_id FK`, `supplier_id FK`, `invoice_number UNIQUE per company`, `invoice_date`, `total_amount`, `tax_amount`, `outstanding_amount`, `status DEFAULT 'POSTED'`, `journal_entry_id FK`, `purchase_order_id FK`, `goods_receipt_id FK UNIQUE` | `company_id → companies(id)`, `supplier_id → suppliers(id)`, `journal_entry_id → journal_entries(id)`, `purchase_order_id → purchase_orders(id)`, `goods_receipt_id → goods_receipts(id)` | Purchase invoices (accounts payable) |
| `raw_material_purchase_items` | `id PK`, `purchase_id FK`, `raw_material_id FK`, `raw_material_batch_id FK`, `batch_code`, `quantity`, `cost_per_unit`, `line_total`, `tax_rate`, `tax_amount`, `returned_quantity` | `purchase_id → raw_material_purchases(id)`, `raw_material_id → raw_materials(id)`, `raw_material_batch_id → raw_material_batches(id)` | Purchase invoice line items |

---

### 8. HR & Payroll

> **Note**: HR_PAYROLL module paused (V165). Module removed from `enabled_modules` defaults.

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `employees` | `id PK`, `public_id UUID`, `company_id FK`, `first_name`, `last_name`, `email UNIQUE per company`, `status DEFAULT 'ACTIVE'`, `employee_type ENUM('STAFF',...)`, `monthly_salary`, `daily_wage`, `payment_schedule DEFAULT 'MONTHLY'`, `department`, `designation`, `date_of_joining`, `employment_type DEFAULT 'FULL_TIME'`, `pf_number`, `esi_number`, `pan_number`, `tax_regime DEFAULT 'NEW'`, `salary_structure_template_id FK`, `bank_account_number_encrypted`, etc. | `company_id → companies(id)`, `salary_structure_template_id → salary_structure_templates(id)` | Employee master with encrypted bank details |
| `salary_structure_templates` | `id PK BIGSERIAL`, `public_id UUID`, `company_id FK`, `code UNIQUE per company`, `name`, `basic_pay`, `hra`, `da`, `special_allowance`, `employee_pf_rate DEFAULT 12`, `employee_esi_rate DEFAULT 0.75`, `active` | `company_id → companies(id)` | Salary component templates |
| `attendance` | `id PK`, `company_id FK`, `employee_id FK`, `attendance_date`, `status DEFAULT 'ABSENT'`, `check_in_time`, `check_out_time`, `regular_hours`, `overtime_hours`, `double_overtime_hours`, `base_pay`, `overtime_pay`, `total_pay`, `payroll_run_id FK` | `company_id → companies(id)`, `employee_id → employees(id)`, `payroll_run_id → payroll_runs(id)` | Unique `(company_id, employee_id, attendance_date)` |
| `leave_type_policies` | `id PK BIGSERIAL`, `company_id FK`, `leave_type UNIQUE per company`, `display_name`, `annual_entitlement`, `carry_forward_limit`, `active` | `company_id → companies(id)` | Types: CASUAL, SICK, EARNED, MATERNITY |
| `leave_balances` | `id PK BIGSERIAL`, `company_id FK`, `employee_id FK`, `leave_type`, `balance_year`, `opening_balance`, `accrued`, `used`, `remaining`, `carry_forward_applied` | `company_id → companies(id)`, `employee_id → employees(id)` | Unique `(company_id, employee_id, leave_type, balance_year)` |
| `leave_requests` | `id PK`, `public_id UUID`, `company_id FK`, `employee_id FK`, `leave_type`, `start_date`, `end_date`, `total_days`, `status DEFAULT 'PENDING'`, `approved_by`, `rejected_by`, `decision_reason` | `company_id → companies(id)`, `employee_id → employees(id)` | Leave management |
| `payroll_runs` | `id PK`, `public_id UUID`, `company_id FK`, `run_number`, `run_type DEFAULT 'MONTHLY'`, `period_start`, `period_end`, `status DEFAULT 'DRAFT'`, `total_net_pay`, `journal_entry_id FK`, `payment_journal_entry_id FK`, `idempotency_key UNIQUE per company`, `approved_by`, `posted_by` | `company_id → companies(id)`, `journal_entry_id → journal_entries(id)`, `payment_journal_entry_id → journal_entries(id)` | Unique `(company_id, run_type, period_start, period_end)` |
| `payroll_run_lines` | `id PK`, `payroll_run_id FK`, `employee_id FK`, `present_days`, `absent_days`, `leave_days`, `regular_hours`, `overtime_hours`, `base_pay`, `overtime_pay`, `gross_pay`, `advance_deduction`, `pf_deduction`, `tax_deduction`, `total_deductions`, `net_pay`, `payment_status DEFAULT 'PENDING'`, `payment_reference` | `payroll_run_id → payroll_runs(id)`, `employee_id → employees(id)` | Per-employee payroll breakdown |

---

### 9. Orchestrator & Scheduler

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `orchestrator_commands` | `id UUID PK`, `company_id FK`, `command_name`, `idempotency_key`, `request_hash`, `trace_id`, `status DEFAULT 'IN_PROGRESS'` | `company_id → companies(id)` | Unique `(company_id, command_name, idempotency_key)` |
| `orchestrator_outbox` | `id UUID PK`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `status DEFAULT 'PENDING'`, `retry_count`, `dead_letter`, `trace_id` | `company_id → companies(id)` | Outbox pattern for async processing |
| `orchestrator_audit` | `id UUID PK`, `trace_id`, `event_type`, `details`, `company_id FK`, `idempotency_key` | `company_id → companies(id)` | Orchestrator audit trail |
| `order_auto_approval_state` | `id PK`, `company_code`, `order_id FK`, `status DEFAULT 'PENDING'`, `inventory_reserved`, `sales_journal_posted`, `dispatch_finalized`, `invoice_issued`, `order_status_updated`, `last_error` | — | Unique `(company_code, order_id)`. Tracks auto-approval pipeline steps. |
| `scheduled_jobs` | `job_id PK`, `cron_expression`, `active`, `last_run_at`, `next_run_at` | — | Job scheduling config |
| `shedlock` | `name PK`, `lock_until`, `locked_at`, `locked_by` | — | Distributed lock (ShedLock) |

---

### 10. Audit & ML Events

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `audit_logs` | `id PK`, `event_type`, `timestamp`, `user_id`, `company_id FK`, `ip_address`, `request_method`, `request_path`, `resource_type`, `resource_id`, `status DEFAULT 'SUCCESS'`, `trace_id`, `session_id`, `duration_ms` | `company_id → companies(id)` | Legacy audit log |
| `audit_log_metadata` | `audit_log_id PK FK`, `metadata_key PK`, `metadata_value` | `audit_log_id → audit_logs(id) CASCADE` | Key-value metadata for audit events |
| `audit_action_events` | `id PK`, `company_id FK`, `occurred_at`, `source`, `module`, `action`, `entity_type`, `entity_id`, `reference_number`, `status`, `amount`, `correlation_id UUID`, `trace_id`, `actor_user_id FK`, `actor_identifier`, `ml_eligible`, `training_subject_key` | `company_id → companies(id)`, `actor_user_id → app_users(id)` | New audit system with ML training signals |
| `audit_action_event_metadata` | `event_id PK FK`, `metadata_key PK`, `metadata_value` | `event_id → audit_action_events(id)` | Metadata for action events |
| `audit_action_event_retries` | `id PK`, `company_id`, `payload TEXT`, `failed_attempt_count`, `next_attempt_at`, `last_error` | — | Retry queue for failed audit events |
| `ml_interaction_events` | `id PK`, `company_id FK`, `occurred_at`, `module`, `action`, `interaction_type`, `screen`, `target_id`, `status`, `actor_user_id FK`, `consent_opt_in`, `training_subject_key` | `company_id → companies(id)`, `actor_user_id → app_users(id)` | ML training data collection |
| `ml_interaction_event_metadata` | `event_id PK FK`, `metadata_key PK`, `metadata_value` | `event_id → ml_interaction_events(id)` | Metadata for ML events |

---

### 11. Admin & Support

| Table | Key Columns | Relationships | Notes |
|---|---|---|---|
| `export_requests` | `id PK`, `company_id FK`, `user_id FK`, `report_type`, `parameters TEXT`, `status ENUM('PENDING','APPROVED','REJECTED','EXPIRED')`, `approved_by` | `company_id → companies(id)`, `user_id → app_users(id)` | Data export approval gate |
| `support_tickets` | `id PK`, `public_id UUID`, `company_id FK`, `user_id FK`, `category ENUM('BUG','FEATURE_REQUEST','SUPPORT')`, `subject`, `description`, `status ENUM('OPEN','IN_PROGRESS','RESOLVED','CLOSED')`, `github_issue_number`, `github_issue_url`, `github_issue_state` | `company_id → companies(id)`, `user_id → app_users(id)` | GitHub-integrated support tickets |
| `changelog_entries` | `id PK`, `version_label`, `title`, `body TEXT`, `published_at`, `highlighted`, `deleted` | — | Product changelog |

---

## Key Enum / Status Values

| Context | Enum Values |
|---|---|
| `companies.lifecycle_state` | `ACTIVE`, `SUSPENDED`, `DEACTIVATED` |
| `companies.enabled_modules` | JSONB array: `MANUFACTURING`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED` (HR_PAYROLL removed) |
| `accounting_periods.status` | `OPEN`, `CLOSED`, `LOCKED` |
| `accounting_periods.costing_method` | `FIFO`, `LIFO`, `WEIGHTED_AVERAGE` |
| `journal_entries.status` | `DRAFT`, `POSTED` |
| `journal_entries.journal_type` | `AUTOMATED`, `MANUAL` |
| `sales_orders.status` | `PENDING`, `APPROVED`, `DISPATCHED`, `INVOICED`, `CANCELLED`, etc. |
| `sales_orders.payment_mode` | `CREDIT` (default), `CASH`, etc. |
| `invoices.status` | `DRAFT`, `ISSUED`, `PAID`, `CANCELLED` |
| `packaging_slips.status` | `PENDING`, `RESERVED`, `PENDING_PRODUCTION`, `PENDING_STOCK`, `CONFIRMED`, `DISPATCHED`, `BACKORDER` |
| `dealers.status` | `ACTIVE` |
| `dealers.payment_terms` | `NET_30`, `NET_60`, `NET_90` |
| `dealers/suppliers.gst_registration_type` | `UNREGISTERED`, `REGISTERED`, etc. |
| `purchase_orders.status` | `OPEN`, `RECEIVED`, `CLOSED`, `CANCELLED` |
| `goods_receipts.status` | `RECEIVED` |
| `raw_material_purchases.status` | `POSTED` |
| `production_logs.status` | `MIXED`, `PACKED` |
| `production_plans.status` | `PLANNED`, `IN_PROGRESS`, `COMPLETED` |
| `inventory_adjustments.status` | `DRAFT`, `POSTED` |
| `payroll_runs.status` | `DRAFT`, `APPROVED`, `POSTED`, `CANCELLED` |
| `payroll_runs.run_type` | `MONTHLY` |
| `payroll_run_lines.payment_status` | `PENDING`, `PAID` |
| `attendance.status` | `ABSENT`, `PRESENT`, `HALF_DAY`, `HOLIDAY`, `LEAVE` |
| `leave_requests.status` | `PENDING`, `APPROVED`, `REJECTED` |
| `leave_type_policies.leave_type` | `CASUAL`, `SICK`, `EARNED`, `MATERNITY` |
| `reconciliation_discrepancies.type` | `AR`, `AP`, `INVENTORY`, `GST` |
| `reconciliation_discrepancies.status` | `OPEN`, `ACKNOWLEDGED`, `ADJUSTED`, `RESOLVED` |
| `bank_reconciliation_sessions.status` | `DRAFT`, `COMPLETED` |
| `period_close_requests.status` | `PENDING`, `APPROVED`, `REJECTED` |
| `export_requests.status` | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED` |
| `support_tickets.status` | `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `support_tickets.category` | `BUG`, `FEATURE_REQUEST`, `SUPPORT` |
| `partner_settlement_allocations.partner_type` | `DEALER`, `SUPPLIER` |
| `payment_status (ledger entries)` | `UNPAID`, `PAID` |
| `employees.employment_type` | `FULL_TIME` |
| `employees.tax_regime` | `NEW`, `OLD` |

---

## Cross-Domain Relationship Map

```
companies ──────────────────────────────────────────────────────┐
  │                                                              │
  ├── app_users (company_id, Auth V2 scoped)                     │
  │     ├── user_roles → roles → role_permissions → permissions  │
  │     ├── refresh_tokens (user_public_id, auth_scope_code)     │
  │     ├── password_reset_tokens                                │
  │     └── mfa_recovery_codes                                   │
  │                                                              │
  ├── accounts (chart of accounts)                               │
  │     └── self-ref: parent_id → accounts(id)                   │
  │                                                              │
  ├── accounting_periods                                         │
  │     ├── journal_entries                                      │
  │     │     ├── journal_lines → accounts                       │
  │     │     ├── dealer_ledger_entries                          │
  │     │     └── supplier_ledger_entries                        │
  │     └── period_close_requests                                │
  │                                                              │
  ├── dealers ─────────────────┐                                 │
  │     ├── sales_orders        │                                 │
  │     │     ├── sales_order_items                              │
  │     │     ├── packaging_slips                                │
  │     │     │     ├── packaging_slip_lines → finished_good_batches
  │     │     │     └── credit_limit_override_requests           │
  │     │     ├── invoices (also via packaging_slips)             │
  │     │     │     └── invoice_lines                            │
  │     │     └── factory_tasks                                  │
  │     ├── credit_requests                                      │
  │     └── dealer_ledger_entries                                │
  │                                                              │
  ├── suppliers ────────────────┐                                 │
  │     ├── purchase_orders     │                                 │
  │     │     └── purchase_order_items → raw_materials            │
  │     ├── goods_receipts                                        │
  │     │     └── goods_receipt_items → raw_material_batches     │
  │     ├── raw_material_purchases                               │
  │     │     └── raw_material_purchase_items                    │
  │     └── supplier_ledger_entries                              │
  │                                                              │
  ├── raw_materials                                              │
  │     ├── raw_material_batches                                 │
  │     ├── raw_material_movements                               │
  │     ├── raw_material_adjustments → raw_material_adjustment_lines
  │     └── packaging_size_mappings                              │
  │                                                              │
  ├── production_brands                                         │
  │     └── production_products                                  │
  │           └── size_variants                                   │
  │                                                              │
  ├── production_logs                                            │
  │     ├── production_log_materials → raw_materials             │
  │     └── packing_records → finished_goods / size_variants     │
  │                                                              │
  ├── finished_goods                                            │
  │     ├── finished_good_batches                                │
  │     ├── inventory_movements                                   │
  │     └── inventory_adjustments → inventory_adjustment_lines   │
  │                                                              │
  ├── employees                                                  │
  │     ├── attendance → payroll_runs                            │
  │     ├── leave_requests → leave_type_policies                 │
  │     └── leave_balances                                       │
  │                                                              │
  ├── payroll_runs                                               │
  │     └── payroll_run_lines → employees                        │
  │                                                              │
  ├── bank_reconciliation_sessions → bank_reconciliation_items   │
  ├── reconciliation_discrepancies                               │
  ├── closed_period_posting_exceptions                           │
  │                                                              │
  ├── export_requests                                            │
  ├── support_tickets                                            │
  └── catalog_imports                                            │
                                                                 │
  orchestrator_commands / outbox / audit ────────────────────────┘
  order_auto_approval_state
  scheduled_jobs / shedlock

  audit_action_events ──→ companies, app_users
  ml_interaction_events ──→ companies, app_users
  partner_settlement_allocations ──→ dealers, suppliers, invoices, raw_material_purchases, journal_entries
```

---

## Key Design Patterns

1. **Multi-tenancy**: Every business table has `company_id FK → companies(id)`. No cross-tenant data sharing.
2. **Idempotency**: Operations use `(company_id, idempotency_key)` unique constraints to prevent duplicate submissions.
3. **Event Sourcing** (partial): `accounting_events` provides full audit trail with `(aggregate_id, sequence_number)` uniqueness.
4. **Outbox Pattern**: `orchestrator_outbox` ensures reliable async event delivery.
5. **Auto-Approval Pipeline**: `order_auto_approval_state` tracks multi-step order processing (inventory → journal → dispatch → invoice).
6. **Double-Entry Accounting**: All financial flows go through `journal_entries` + `journal_lines` with strict debit=credit balance.
7. **Subledgers**: `dealer_ledger_entries` (AR) and `supplier_ledger_entries` (AP) mirror journal entries for partner-level tracking.
8. **Auth V2 Scoped Accounts**: Each user belongs to exactly one company (or is platform-scoped). `user_companies` table dropped.
9. **Token Security**: `refresh_tokens` and `password_reset_tokens` use `token_digest` instead of raw tokens.
10. **Batch Traceability**: Both raw materials and finished goods have batch-level tracking with movements.
11. **Optimistic Locking**: Every table has `version BIGINT DEFAULT 0` for concurrent update detection.
12. **Encrypted PII**: Employee bank details stored encrypted (`bank_account_number_encrypted`, etc.).
