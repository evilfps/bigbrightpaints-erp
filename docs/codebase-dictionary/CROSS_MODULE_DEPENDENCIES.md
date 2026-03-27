# Cross-Module Dependencies

> **Generated:** 2026-03-27  
> **Purpose:** Comprehensive dependency graph showing how changes in one module affect others. Essential for impact analysis before refactoring.

---

## Overview

BigBrightPaints ERP follows a modular architecture with 15 business modules. This document maps all cross-module dependencies to help developers understand the impact of changes.

**Module Count:** 15 business modules + core infrastructure + shared DTOs

---

## Dependency Graph

### Visual Representation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CORE INFRASTRUCTURE                                │
│  (audit, audittrail, config, exception, idempotency, security, util, etc.)  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            SHARED DTOS                                       │
│              ApiResponse, PageResponse, ErrorResponse, etc.                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
        ▼                           ▼                           ▼
┌───────────────┐         ┌───────────────┐         ┌───────────────┐
│    COMPANY    │◄────────│   ACCOUNTING  │◄────────│   PURCHASING  │
└───────────────┘         └───────────────┘         └───────────────┘
        │                           │                           │
        │                           │                           │
        ▼                           ▼                           │
┌───────────────┐         ┌───────────────┐                    │
│     AUTH      │         │    INVOICE    │                    │
└───────────────┘         └───────────────┘                    │
        │                           │                           │
        │                           ▼                           │
        │                 ┌───────────────┐                    │
        │                 │     SALES     │◄───────────────────┘
        │                 └───────────────┘
        │                           │
        │                           ▼
        │                 ┌───────────────┐
        │                 │   INVENTORY   │
        │                 └───────────────┘
        │                           │
        │            ┌──────────────┼──────────────┐
        │            │              │              │
        │            ▼              ▼              ▼
        │   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
        │   │   FACTORY   │ │  PRODUCTION │ │   REPORTS   │
        │   └─────────────┘ └─────────────┘ └─────────────┘
        │            │              │              │
        │            └──────────────┼──────────────┘
        │                           │
        ▼                           ▼
┌───────────────┐         ┌───────────────┐
│      HR       │         │    PORTAL     │
└───────────────┘         └───────────────┘
        │
        ▼
┌───────────────┐
│     RBAC      │
└───────────────┘
```

### Dependency Matrix

| Source Module | Depends On |
|---------------|------------|
| **accounting** | company, sales (Dealer), purchasing (Supplier), inventory, hr (PayrollRun), invoice, reports |
| **auth** | company, rbac (Role) |
| **admin** | company, auth, rbac |
| **company** | accounting (Account, CoA), auth (UserAccount), rbac (Role) |
| **factory** | company, inventory, production, accounting (AccountingFacade), purchasing |
| **hr** | company, accounting (Payroll posting) |
| **inventory** | company, accounting (Inventory movements), sales (Dispatch), purchasing (GoodsReceipt) |
| **invoice** | company, accounting (JournalEntry), sales (Dealer, SalesOrder), inventory (PackagingSlip) |
| **portal** | company, all modules (for dashboard aggregation) |
| **production** | company, inventory (FinishedGood, RawMaterial), factory (SizeVariant), purchasing, sales |
| **purchasing** | company, accounting (Supplier ledger), inventory (Raw material intake) |
| **rbac** | company |
| **reports** | accounting, company, inventory, factory, sales, purchasing, invoice |
| **sales** | company, accounting (Dealer ledger, GST), inventory (Dispatch), portal |

---

## Detailed Dependency Analysis

### 1. Accounting Module - **CRITICAL HUB**

**Consumed By (5 modules depend on it):**
- `factory` - Uses `AccountingFacade` for WIP/costing journals
- `hr` - Uses accounting for payroll posting
- `inventory` - Uses accounting for inventory valuation adjustments
- `invoice` - Links invoices to `JournalEntry`
- `purchasing` - Uses accounting for AP/posting
- `sales` - Uses accounting for AR/dealer ledgers, GST
- `reports` - Heavy dependency for all financial reports

**Imports From Accounting:**
```java
// Factory imports
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;

// HR imports  
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;

// Inventory imports
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;

// Invoice imports
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;

// Purchasing imports
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;

// Sales imports
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;

// Reports imports (extensive)
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.service.*;
```

---

### 2. Company Module - **FOUNDATION MODULE**

**Consumed By (ALL modules depend on it):**
- Every module uses `Company` entity for multi-tenancy
- `CompanyContextService` is used everywhere
- `CompanyContextHolder` in core security

**Key Shared Artifacts:**
```java
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
```

**Special Dependencies:**
- `accounting` - Company has `Account` for default accounts
- `auth` - Company provisions `UserAccount`
- `rbac` - Company has `Role` assignments

---

### 3. Inventory Module - **OPERATIONAL HUB**

**Consumed By:**
- `factory` - Heavy dependency for `FinishedGood`, `FinishedGoodBatch`, `RawMaterial`, inventory movements
- `sales` - Dispatch operations, stock availability
- `purchasing` - Goods receipt, raw material intake
- `reports` - Inventory valuation reports
- `production` - Product catalog linkage

**Key Shared Artifacts:**
```java
// Factory imports (extensive)
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;

// Sales imports
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsReservationEngine;

// Purchasing imports
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialIntakeRecord;
```

---

### 4. Sales Module - **REVENUE MODULE**

**Consumed By:**
- `accounting` - Dealer ledger entries
- `invoice` - `SalesOrder`, `Dealer` entities
- `inventory` - Dispatch creation
- `portal` - Dealer portal features
- `reports` - Sales reports

**Key Shared Artifacts:**
```java
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
```

---

### 5. Factory Module - **MANUFACTURING HUB**

**Consumed By:**
- `reports` - Production cost reports, wastage reports
- `production` - Production logs, packing records

**Key Shared Artifacts:**
```java
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackedBatchTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
```

---

## Impact Analysis Matrix

| If you change... | These modules break | Test suites affected | Risk Level |
|------------------|---------------------|---------------------|------------|
| `AccountingFacade` | factory, hr, purchasing, sales, reports | accounting-it, factory-it, hr-it, purchasing-it, sales-it, reports-it | 🔴 **CRITICAL** |
| `Company` entity | ALL modules | ALL test suites | 🔴 **CRITICAL** |
| `CompanyContextService` | ALL modules | ALL test suites | 🔴 **CRITICAL** |
| `Dealer` / `DealerRepository` | accounting, invoice, sales, reports | sales-it, accounting-it, invoice-it, reports-it | 🟠 **HIGH** |
| `FinishedGood` / `FinishedGoodBatch` | factory, inventory, production, reports, sales | inventory-it, factory-it, production-it, reports-it | 🟠 **HIGH** |
| `InventoryMovement` | factory, inventory, reports, accounting (valuation) | inventory-it, factory-it, accounting-it | 🟠 **HIGH** |
| `JournalEntry` / `JournalLine` | accounting, invoice, reports, hr (payroll) | accounting-it, invoice-it, hr-it, reports-it | 🟠 **HIGH** |
| `SalesOrder` | sales, invoice, inventory, reports | sales-it, invoice-it, inventory-it | 🟠 **HIGH** |
| `Supplier` | purchasing, accounting, reports | purchasing-it, accounting-it, reports-it | 🟡 **MEDIUM** |
| `RawMaterial` / `RawMaterialBatch` | inventory, factory, purchasing, production | inventory-it, factory-it, purchasing-it | 🟡 **MEDIUM** |
| `ProductionLog` | factory, reports | factory-it, reports-it | 🟡 **MEDIUM** |
| `UserAccount` | auth, company, admin, rbac | auth-it, company-it, admin-it | 🟡 **MEDIUM** |
| `Role` | rbac, auth, company, admin | rbac-it, auth-it, company-it, admin-it | 🟡 **MEDIUM** |
| `PackagingSlip` | inventory, invoice, factory | inventory-it, invoice-it, factory-it | 🟡 **MEDIUM** |
| `PayrollRun` | hr, accounting | hr-it, accounting-it | 🟢 **LOW** |
| `SizeVariant` | factory, production | factory-it, production-it | 🟢 **LOW** |

### Risk Level Legend
- 🔴 **CRITICAL** - Changes cascade to 5+ modules, requires extensive coordination
- 🟠 **HIGH** - Changes affect 3-5 modules, needs careful planning
- 🟡 **MEDIUM** - Changes affect 2-3 modules, moderate coordination needed
- 🟢 **LOW** - Changes isolated to 1-2 modules, lower risk

---

## Critical Paths (High Risk)

### 1. AccountingFacade - Used by 6 modules
**Location:** `modules/accounting/service/AccountingFacade.java`

**Callers:**
- `factory/PackingBatchService` - WIP journals
- `factory/PackingService` - Production journals entries
- `factory/CostAllocationService` - Cost allocation journals
- `factory/BulkPackingOrchestrator` - Bulk packing journals
- `hr/PayrollPostingService` - Payroll liability journals
- `purchasing/PurchaseInvoiceService` - AP journals
- `sales/SalesCoreEngine` - AR journals

**Breaking Change Impact:**
- Any signature change requires updating all callers
- Tests in: accounting-it, factory-it, hr-it, purchasing-it, sales-it, reports-it

**Recommendation:** Never change method signatures. Add new overloaded methods.

---

### 2. CompanyContextService - Used by ALL modules
**Location:** `modules/company/service/CompanyContextService.java`

**Callers:** Every module that needs tenant context

**Breaking Change Impact:**
- Changes cascade to every service, controller, and repository
- ALL test suites affected

**Recommendation:** This is infrastructure. Changes should be rare and well-coordinated.

---

### 3. Company Entity - Used by ALL modules
**Location:** `modules/company/domain/Company.java`

**Impact:**
- Domain entity shared across all modules
- Foreign key relationships everywhere
- Tenant isolation depends on it

**Recommendation:** Add new fields only. Never remove or rename existing fields.

---

### 4. AuditService - Used by ALL write operations
**Location:** `core/audit/AuditService.java`

**Callers:**
- All modules with write operations
- Event listeners for audit trail

**Impact:**
- Changes affect all mutations
- Integration tests across modules

---

### 5. InventoryMovement - Core inventory tracking
**Location:** `modules/inventory/domain/InventoryMovement.java`

**Consumed By:**
- `factory` - Production movements
- `inventory` - All inventory operations
- `reports` - Valuation reports
- `accounting` - Inventory valuation

**Impact:**
- Changes affect inventory tracking and financial reporting

---

## Circular Dependency Check

**No Direct Circular Dependencies Detected**

The module structure follows a layered architecture that prevents circular dependencies:

```
Layer 1 (Foundation): company, auth, rbac
Layer 2 (Operations): inventory, purchasing, sales
Layer 3 (Manufacturing): factory, production
Layer 4 (Finance): accounting, invoice
Layer 5 (Reporting): reports, portal
Layer 6 (Admin): admin, hr
```

**Note:** Some bidirectional references exist (e.g., accounting ↔ inventory for valuation events) but these are handled through event-driven architecture, not direct imports.

---

## Shared Artifacts

### DTOs shared across modules:

| DTO | Location | Used By |
|-----|----------|---------|
| `ApiResponse<T>` | `shared/dto/ApiResponse.java` | ALL controllers |
| `PageResponse<T>` | `shared/dto/PageResponse.java` | ALL controllers with pagination |
| `ErrorResponse` | `shared/dto/ErrorResponse.java` | Global exception handler |
| `DocumentLifecycleDto` | `shared/dto/DocumentLifecycleDto.java` | invoice, purchasing, accounting |
| `LinkedBusinessReferenceDto` | `shared/dto/LinkedBusinessReferenceDto.java` | invoice, purchasing, accounting |

### Events shared across modules:

| Event | Publisher | Consumers |
|-------|-----------|-----------|
| `AccountingEvent` | accounting, inventory, sales, purchasing | AccountingEventStore, InventoryAccountingEventListener |
| `InventoryMovementEvent` | inventory, sales, factory | Accounting module for valuation |
| `InventoryValuationChangedEvent` | inventory | Accounting module |
| `PackagingSlipEvent` | inventory | FactorySlipEventListener |
| `SalesOrderCreatedEvent` | sales | Orchestrator, accounting |
| `AuditActionEvent` | All modules | EnterpriseAuditTrailService |

### Utilities used everywhere:

| Utility | Location | Purpose |
|---------|----------|---------|
| `MoneyUtils` | `core/util/MoneyUtils.java` | ALL financial operations |
| `CompanyClock` | `core/util/CompanyClock.java` | ALL time-sensitive operations |
| `CompanyTime` | `core/util/CompanyTime.java` | ALL timestamp generation |
| `IdempotencyHeaderUtils` | `core/util/IdempotencyHeaderUtils.java` | ALL write operations |
| `CompanyEntityLookup` | `core/util/CompanyEntityLookup.java` | Entity validation across modules |
| `CostingMethodUtils` | `core/util/CostingMethodUtils.java` | Inventory valuation |
| `ValidationUtils` | `core/validation/ValidationUtils.java` | Input validation |
| `BusinessDocumentTruths` | `core/util/BusinessDocumentTruths.java` | Document reference matching |

---

## Safe Changes (Low Risk)

1. **Adding new controller endpoints** - No cross-module impact
2. **Adding new DTOs** - No impact unless shared
3. **Adding new services** - No impact until called
4. **Adding new domain entities** - No impact unless referenced
5. **Adding new repository methods** - No impact unless used elsewhere
6. **Internal service refactoring** - Safe if public API unchanged

---

## CI Cascade Effects

When these tests fail, check these other test suites:

### Primary Cascade Paths

```
accounting-it fails → Check sales-it, inventory-it, factory-it, purchasing-it, hr-it, reports-it
company-it fails → Check ALL test suites (tenant isolation)
auth-it fails → Check company-it, admin-it, rbac-it (user/role bindings)
inventory-it fails → Check factory-it, sales-it, purchasing-it, reports-it
```

### Integration Test Dependencies

| Test Suite | Cross-Module Dependencies |
|------------|---------------------------|
| `accounting-it` | Requires: company, sales (dealers), purchasing (suppliers), inventory |
| `factory-it` | Requires: company, inventory, production, accounting |
| `hr-it` | Requires: company, accounting (payroll posting) |
| `inventory-it` | Requires: company, purchasing (goods receipt), sales (dispatch) |
| `invoice-it` | Requires: company, sales, accounting, inventory |
| `purchasing-it` | Requires: company, accounting, inventory |
| `reports-it` | Requires: ALL modules (aggregation) |
| `sales-it` | Requires: company, accounting, inventory |

### Truth Suite Cross-Module Tests

The `truthsuite/crossmodule/` directory contains tests specifically for cross-module contracts:

- `TS_CrossModuleLinkageContractTest.java` - Validates entity linkages
- `TS_CostingHelperCentralizationContractTest.java` - Validates costing consistency

---

## Test Fixtures Shared Across Modules

| Fixture | Location | Used In |
|---------|----------|---------|
| `CanonicalErpDataset` | `test/support/CanonicalErpDataset.java` | E2E tests, integration tests |
| `CanonicalErpDatasetBuilder` | `test/support/CanonicalErpDatasetBuilder.java` | Test data construction |
| `TestDateUtils` | `test/support/TestDateUtils.java` | Date handling in tests |
| `AccountingInvariantAssertions` | `truthsuite/support/AccountingInvariantAssertions.java` | Accounting validation |

---

## Recommendations for Developers

### Before Modifying Shared Code:

1. **Check this document** for impact analysis
2. **Run dependent module tests** not just your module
3. **Consider adding new methods** instead of changing signatures
4. **Use deprecation** for old methods with migration path

### When Adding Cross-Module Dependencies:

1. **Document the dependency** in this file
2. **Use interfaces** to reduce coupling
3. **Consider events** for loose coupling
4. **Add integration tests** for the cross-module behavior

### Module Boundary Rules:

1. **Never** import internal packages (`internal/`) from other modules
2. **Prefer** using public service facades (e.g., `AccountingFacade`)
3. **Use events** for async cross-module communication
4. **Keep DTOs** in shared package when truly shared

---

## Appendix: Full Import Analysis

### Modules Importing from Accounting (7 modules)
```
factory → accounting (AccountingFacade, JournalEntryDto, JournalEntryRequest)
hr → accounting (AccountingFacade, JournalEntry)
inventory → accounting (AccountingPeriod, CostingMethod, AccountingEvent)
invoice → accounting (JournalEntry, PartnerSettlementAllocation)
purchasing → accounting (SupplierLedgerEntry, AccountingFacade)
reports → accounting (Account, JournalLine, DealerLedgerEntry, etc.)
sales → accounting (DealerLedgerEntry, GstRegistrationType, DealerLedgerService)
```

### Modules Importing from Company (ALL modules)
```
All modules → company (Company, CompanyRepository, CompanyContextService)
auth → company (CompanyContextFilter, tenant binding)
accounting → company (Company has default Account references)
```

### Modules Importing from Inventory (6 modules)
```
factory → inventory (FinishedGood, FinishedGoodBatch, RawMaterial, InventoryMovement)
production → inventory (FinishedGood, RawMaterial, InventoryMovement)
sales → inventory (PackagingSlip, FinishedGood for dispatch)
purchasing → inventory (RawMaterialIntakeRecord)
reports → inventory (InventoryMovement, RawMaterial, FinishedGood)
accounting → inventory (InventoryAccountingEventListener)
```

### Modules Importing from Sales (4 modules)
```
accounting → sales (Dealer, DealerRepository for ledger)
invoice → sales (Dealer, SalesOrder, SalesOrderRepository)
inventory → sales (Dispatch operations)
reports → sales (Dealer for aged debtors)
```

---

*Last Updated: 2026-03-27*
*Generated from codebase analysis of BigBrightPaints ERP*
