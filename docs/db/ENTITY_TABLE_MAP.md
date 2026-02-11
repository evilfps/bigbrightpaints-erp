# Entity ↔ Table Map (Code-Derived)

This inventory is generated from `@Entity` + `@Table` annotations under `erp-domain/src/main/java/com/bigbrightpaints/erp`.

- Entity count: **76**
- Includes explicit join / collection tables used by JPA mappings.

## Entities

| Module | Entity | Table | Source |
|---|---|---|---|
| accounting | `Account` | `accounts` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/Account.java` |
| accounting | `AccountingEvent` | `accounting_events` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/AccountingEvent.java` |
| accounting | `AccountingPeriod` | `accounting_periods` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/AccountingPeriod.java` |
| accounting | `AccountingPeriodSnapshot` | `accounting_period_snapshots` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/AccountingPeriodSnapshot.java` |
| accounting | `AccountingPeriodTrialBalanceLine` | `accounting_period_trial_balance_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/AccountingPeriodTrialBalanceLine.java` |
| accounting | `DealerLedgerEntry` | `dealer_ledger_entries` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/DealerLedgerEntry.java` |
| accounting | `JournalEntry` | `journal_entries` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalEntry.java` |
| accounting | `JournalLine` | `journal_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalLine.java` |
| accounting | `JournalReferenceMapping` | `journal_reference_mappings` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalReferenceMapping.java` |
| accounting | `PartnerSettlementAllocation` | `partner_settlement_allocations` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocation.java` |
| accounting | `SupplierLedgerEntry` | `supplier_ledger_entries` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/SupplierLedgerEntry.java` |
| auth | `BlacklistedToken` | `blacklisted_tokens` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/BlacklistedToken.java` |
| auth | `MfaRecoveryCode` | `mfa_recovery_codes` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/MfaRecoveryCode.java` |
| auth | `PasswordResetToken` | `password_reset_tokens` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/PasswordResetToken.java` |
| auth | `RefreshToken` | `refresh_tokens` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/RefreshToken.java` |
| auth | `UserAccount` | `app_users` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserAccount.java` |
| auth | `UserPasswordHistory` | `user_password_history` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserPasswordHistory.java` |
| auth | `UserTokenRevocation` | `user_token_revocations` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserTokenRevocation.java` |
| company | `Company` | `companies` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/domain/Company.java` |
| core | `AuditLog` | `audit_logs` | `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/AuditLog.java` |
| core | `NumberSequence` | `number_sequences` | `erp-domain/src/main/java/com/bigbrightpaints/erp/core/domain/NumberSequence.java` |
| core | `SystemSetting` | `system_settings` | `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/SystemSetting.java` |
| factory | `FactoryTask` | `factory_tasks` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/FactoryTask.java` |
| factory | `PackagingSizeMapping` | `packaging_size_mappings` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackagingSizeMapping.java` |
| factory | `PackingRecord` | `packing_records` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackingRecord.java` |
| factory | `PackingRequestRecord` | `packing_request_records` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackingRequestRecord.java` |
| factory | `ProductionBatch` | `production_batches` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionBatch.java` |
| factory | `ProductionLog` | `production_logs` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionLog.java` |
| factory | `ProductionLogMaterial` | `production_log_materials` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionLogMaterial.java` |
| factory | `ProductionPlan` | `production_plans` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionPlan.java` |
| hr | `Attendance` | `attendance` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/Attendance.java` |
| hr | `Employee` | `employees` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/Employee.java` |
| hr | `LeaveRequest` | `leave_requests` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/LeaveRequest.java` |
| hr | `PayrollRun` | `payroll_runs` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/PayrollRun.java` |
| hr | `PayrollRunLine` | `payroll_run_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/PayrollRunLine.java` |
| inventory | `FinishedGood` | `finished_goods` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/FinishedGood.java` |
| inventory | `FinishedGoodBatch` | `finished_good_batches` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/FinishedGoodBatch.java` |
| inventory | `InventoryAdjustment` | `inventory_adjustments` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryAdjustment.java` |
| inventory | `InventoryAdjustmentLine` | `inventory_adjustment_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryAdjustmentLine.java` |
| inventory | `InventoryMovement` | `inventory_movements` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovement.java` |
| inventory | `InventoryReservation` | `inventory_reservations` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryReservation.java` |
| inventory | `OpeningStockImport` | `opening_stock_imports` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/OpeningStockImport.java` |
| inventory | `PackagingSlip` | `packaging_slips` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/PackagingSlip.java` |
| inventory | `PackagingSlipLine` | `packaging_slip_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/PackagingSlipLine.java` |
| inventory | `RawMaterial` | `raw_materials` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterial.java` |
| inventory | `RawMaterialBatch` | `raw_material_batches` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialBatch.java` |
| inventory | `RawMaterialIntakeRecord` | `raw_material_intake_requests` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialIntakeRecord.java` |
| inventory | `RawMaterialMovement` | `raw_material_movements` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialMovement.java` |
| invoice | `Invoice` | `invoices` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java` |
| invoice | `InvoiceLine` | `invoice_lines` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/InvoiceLine.java` |
| invoice | `InvoiceSequence` | `invoice_sequences` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/InvoiceSequence.java` |
| orchestrator | `AuditRecord` | `orchestrator_audit` | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/AuditRecord.java` |
| orchestrator | `OrchestratorCommand` | `orchestrator_commands` | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommand.java` |
| orchestrator | `OrderAutoApprovalState` | `order_auto_approval_state` | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrderAutoApprovalState.java` |
| orchestrator | `OutboxEvent` | `orchestrator_outbox` | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OutboxEvent.java` |
| orchestrator | `ScheduledJobDefinition` | `scheduled_jobs` | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/ScheduledJobDefinition.java` |
| production | `CatalogImport` | `catalog_imports` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/domain/CatalogImport.java` |
| production | `ProductionBrand` | `production_brands` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/domain/ProductionBrand.java` |
| production | `ProductionProduct` | `production_products` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/domain/ProductionProduct.java` |
| purchasing | `GoodsReceipt` | `goods_receipts` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/GoodsReceipt.java` |
| purchasing | `GoodsReceiptLine` | `goods_receipt_items` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/GoodsReceiptLine.java` |
| purchasing | `PurchaseOrder` | `purchase_orders` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/PurchaseOrder.java` |
| purchasing | `PurchaseOrderLine` | `purchase_order_items` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/PurchaseOrderLine.java` |
| purchasing | `RawMaterialPurchase` | `raw_material_purchases` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/RawMaterialPurchase.java` |
| purchasing | `RawMaterialPurchaseLine` | `raw_material_purchase_items` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/RawMaterialPurchaseLine.java` |
| purchasing | `Supplier` | `suppliers` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/Supplier.java` |
| rbac | `Permission` | `permissions` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/Permission.java` |
| rbac | `Role` | `roles` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/Role.java` |
| sales | `CreditLimitOverrideRequest` | `credit_limit_override_requests` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/CreditLimitOverrideRequest.java` |
| sales | `CreditRequest` | `credit_requests` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/CreditRequest.java` |
| sales | `Dealer` | `dealers` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/Dealer.java` |
| sales | `OrderSequence` | `order_sequences` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/OrderSequence.java` |
| sales | `Promotion` | `promotions` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/Promotion.java` |
| sales | `SalesOrder` | `sales_orders` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/SalesOrder.java` |
| sales | `SalesOrderItem` | `sales_order_items` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/SalesOrderItem.java` |
| sales | `SalesTarget` | `sales_targets` | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/SalesTarget.java` |

## Join / Collection Tables

| Table | Mapping Source | Purpose |
|---|---|---|
| `user_roles` | `modules/auth/domain/UserAccount.java` | Many-to-many user ↔ role membership |
| `user_companies` | `modules/auth/domain/UserAccount.java` | Many-to-many user ↔ company scope |
| `role_permissions` | `modules/rbac/domain/Role.java` | Many-to-many role ↔ permission grants |
| `audit_log_metadata` | `core/audit/AuditLog.java` | Key/value metadata for append-only audit logs |
| `invoice_payment_refs` | `modules/invoice/domain/Invoice.java` | Payment reference strings per invoice |

## Notes

- Most entities extend `VersionedEntity` and require a non-null `version` column with default 0.
- Tables with `(implicit)` are not expected here; verify any future additions enforce explicit `@Table` names.
