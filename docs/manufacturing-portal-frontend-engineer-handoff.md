# Manufacturing Portal Frontend Engineer Handoff (Deep)

Source: `erp-domain/openapi.json` parsed via `.claude/skills/openapi-frontend-endpoint-map/scripts/map_openapi_frontend.py`, then verified against backend controller RBAC.

This handoff mirrors the Admin + Accounting deliverable pattern with 3 tasks:
1. Manufacturing endpoint expectations (deep)
2. API inventory grouped by domain with cache/debounce/idempotency and inconsistencies
3. Enterprise manufacturing route map with required APIs, states, schema-driven tables/forms, and exact permission gates

## Assumptions (Explicit)

1. Primary manufacturing personas are `ROLE_FACTORY` and `ROLE_ADMIN`; `ROLE_SALES` and `ROLE_ACCOUNTING` have partial access to dispatch/trace/catalog surfaces.
2. OpenAPI security sections are often unspecified; route guards must follow backend `@PreAuthorize`, not spec-only assumptions.
3. Company context is mandatory on orchestrator/dashboard surfaces (company context filter + context holder path).
4. High-risk actions (dispatch confirmation, batch dispatch, cost allocation, packing completion) require audit-friendly UX: confirmation modal + immutable success logs.
5. Manufacturing portal should ship with role-aware navigation: hide routes not backend-accessible for the current role to avoid noisy 403s.

## Verified Backend RBAC Baseline (Exact, Code-Verified)

- Method security is active via `@EnableMethodSecurity` in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`.
- Authorities include both role names and permission codes via `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserPrincipal.java`.
- Default role permissions in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/SystemRole.java`:
  - `ROLE_FACTORY`: `portal:factory`, `dispatch.confirm`, `factory.dispatch`
  - `ROLE_ADMIN`: all portal + dispatch/factory/payroll permissions
  - `ROLE_ACCOUNTING`: no default `dispatch.confirm` or `factory.dispatch`

Verified role behavior for manufacturing scope:
- `FactoryController` and `ProductionLogController` are `ROLE_ADMIN|ROLE_FACTORY` only.
- `PackagingMappingController` is mixed: read for `ROLE_ADMIN|ROLE_FACTORY`, write/delete for `ROLE_ADMIN` only.
- `DispatchController` is mixed by action:
  - read queue/slip/order: `ROLE_ADMIN|ROLE_FACTORY|ROLE_SALES`
  - preview/cancel/status update: `ROLE_ADMIN|ROLE_FACTORY`
  - confirm dispatch: `ROLE_ADMIN|ROLE_FACTORY` + `dispatch.confirm`
- `SalesController` has canonical dispatch confirmation endpoint `POST /api/v1/sales/dispatch/confirm` gated by `ROLE_SALES|ROLE_ACCOUNTING|ROLE_ADMIN` + `dispatch.confirm`.
- `OrchestratorController` factory dispatch requires `ROLE_ADMIN` OR (`ROLE_FACTORY` + `factory.dispatch`).
- `PackingController` has a critical split:
  - `/api/v1/factory/pack` + bulk-batch reads are role-gated.
  - `/api/v1/factory/packing-records*` and unpacked/packing-history endpoints have no explicit `@PreAuthorize` (authenticated access only).
- `ReportController` is `ROLE_ADMIN|ROLE_ACCOUNTING` only, so factory users cannot directly access manufacturing report APIs under current backend policy.

## Shared Foundation APIs (Used Across All Manufacturing Routes)

| Function | Method | Path | Purpose |
|---|---|---|---|
| `authGetMe` | GET | `/api/v1/auth/me` | Session + permissions for role-gated manufacturing navigation |
| `profileGet` | GET | `/api/v1/auth/profile` | Load profile page/modal |
| `profileUpdate` | PUT | `/api/v1/auth/profile` | Save profile updates |
| `authChangePassword` | POST | `/api/v1/auth/password/change` | Password update |
| `companiesList` | GET | `/api/v1/companies` | Company switch list (note: factory role lacks this endpoint by default) |
| `companiesSwitch` | POST | `/api/v1/multi-company/companies/switch` | Switch company context |
| `authLogout` | POST | `/api/v1/auth/logout` | Sign out |

## Task 1: Endpoint Expectations

- Full endpoint expectation map: `/home/realnigga/Desktop/orchestrator_erp/docs/manufacturing-portal-endpoint-map.md`
- Scoped endpoint count: **69**
- Production Planning & Execution: **16** endpoints
- Costing & Allocation: **1** endpoints
- Dispatch & Packing Workflow: **20** endpoints
- Manufacturing Inventory & Masters: **23** endpoints
- Operations Dashboard & Tracing: **4** endpoints
- Manufacturing Reports & Analytics: **5** endpoints

## Task 2: Frontend API Inventory (Grouped by Domain)

### Production Planning & Execution

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_batches_1` | GET | `/api/v1/factory/production-batches` | - | - | Yes | No | Yes |
| `mfg_create` | POST | `/api/v1/factory/production/logs` | batchSize (body), brandId (body), materials (body), materials[].quantity (body), materials[].rawMaterialId (body), mixedQuantity (body), productId (body) | addToFinishedGoods (body), batchColour (body), createdBy (body), laborCost (body), materials[].unitOfMeasure (body), notes (body), overheadCost (body), producedAt (body), salesOrderId (body), unitOfMeasure (body) | No | No | No |
| `mfg_createPlan` | POST | `/api/v1/factory/production-plans` | planNumber (body), plannedDate (body), productName (body), quantity (body) | notes (body) | No | No | No |
| `mfg_createTask` | POST | `/api/v1/factory/tasks` | title (body) | assignee (body), description (body), dueDate (body), packagingSlipId (body), salesOrderId (body), status (body) | No | No | No |
| `mfg_dashboard_1` | GET | `/api/v1/factory/dashboard` | - | - | Yes | No | Yes |
| `mfg_deletePlan` | DELETE | `/api/v1/factory/production-plans/{id}` | id (path) | - | No | No | Yes |
| `mfg_detail` | GET | `/api/v1/factory/production/logs/{id}` | id (path) | - | Yes | No | Yes |
| `mfg_list` | GET | `/api/v1/factory/production/logs` | - | - | Yes | No | Yes |
| `mfg_listBrandProducts` | GET | `/api/v1/production/brands/{brandId}/products` | brandId (path) | - | Yes | No | Yes |
| `mfg_listBrands` | GET | `/api/v1/production/brands` | - | - | Yes | No | Yes |
| `mfg_logBatch` | POST | `/api/v1/factory/production-batches` | batchNumber (body), quantityProduced (body) | loggedBy (body), notes (body), planId (query) | No | No | No |
| `mfg_plans` | GET | `/api/v1/factory/production-plans` | - | - | Yes | No | Yes |
| `mfg_tasks` | GET | `/api/v1/factory/tasks` | - | - | Yes | No | Yes |
| `mfg_updatePlan` | PUT | `/api/v1/factory/production-plans/{id}` | id (path), planNumber (body), plannedDate (body), productName (body), quantity (body) | notes (body) | No | No | Yes |
| `mfg_updatePlanStatus` | PATCH | `/api/v1/factory/production-plans/{id}/status` | id (path) | status (body) | No | No | Conditional |
| `mfg_updateTask` | PUT | `/api/v1/factory/tasks/{id}` | id (path), title (body) | assignee (body), description (body), dueDate (body), packagingSlipId (body), salesOrderId (body), status (body) | No | No | Yes |

### Costing & Allocation

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_allocateCosts` | POST | `/api/v1/factory/cost-allocation` | finishedGoodsAccountId (body), laborCost (body), laborExpenseAccountId (body), month (body), overheadCost (body), overheadExpenseAccountId (body), year (body) | notes (body) | No | No | No |

### Dispatch & Packing Workflow

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_cancelBackorder` | POST | `/api/v1/dispatch/backorder/{slipId}/cancel` | slipId (path) | reason (query) | No | No | No |
| `mfg_completePacking` | POST | `/api/v1/factory/packing-records/{productionLogId}/complete` | productionLogId (path) | - | No | No | No |
| `mfg_confirmDispatch` | POST | `/api/v1/sales/dispatch/confirm` | lines[].shipQty (body) | adminOverrideCreditLimit (body), confirmedBy (body), dispatchNotes (body), lines (body), lines[].batchId (body), lines[].discount (body), lines[].lineId (body), lines[].notes (body), lines[].priceOverride (body), lines[].taxInclusive (body), lines[].taxRate (body), orderId (body), overrideRequestId (body), packingSlipId (body) | No | No | No |
| `mfg_confirmDispatch_1` | POST | `/api/v1/dispatch/confirm` | lines (body), lines[].lineId (body), lines[].shippedQuantity (body), packagingSlipId (body) | confirmedBy (body), lines[].notes (body), notes (body), overrideRequestId (body) | No | No | No |
| `mfg_dispatch` | POST | `/api/v1/orchestrator/factory/dispatch/{batchId}` | X-Company-Id (header), batchId (body), batchId (path), postingAmount (body), requestedBy (body) | - | No | No | No |
| `mfg_dispatchOrder` | POST | `/api/v1/orchestrator/dispatch` | X-Company-Id (header) | - | No | No | No |
| `mfg_dispatchOrderAlias` | POST | `/api/v1/orchestrator/dispatch/{orderId}` | X-Company-Id (header), orderId (path) | - | No | No | No |
| `mfg_fulfillOrder` | POST | `/api/v1/orchestrator/orders/{orderId}/fulfillment` | X-Company-Id (header), orderId (path), status (body) | notes (body) | No | No | No |
| `mfg_getDispatchPreview` | GET | `/api/v1/dispatch/preview/{slipId}` | slipId (path) | - | Yes | No | Yes |
| `mfg_getPackagingSlip` | GET | `/api/v1/dispatch/slip/{slipId}` | slipId (path) | - | Yes | No | Yes |
| `mfg_getPackagingSlipByOrder` | GET | `/api/v1/dispatch/order/{orderId}` | orderId (path) | - | Yes | No | Yes |
| `mfg_getPendingSlips` | GET | `/api/v1/dispatch/pending` | - | - | Yes | Conditional | Yes |
| `mfg_listBulkBatches` | GET | `/api/v1/factory/bulk-batches/{finishedGoodId}` | finishedGoodId (path) | - | Yes | No | Yes |
| `mfg_listChildBatches` | GET | `/api/v1/factory/bulk-batches/{parentBatchId}/children` | parentBatchId (path) | - | Yes | No | Yes |
| `mfg_listUnpackedBatches` | GET | `/api/v1/factory/unpacked-batches` | - | - | Yes | No | Yes |
| `mfg_orders` | GET | `/api/v1/sales/orders` | - | dealerId (query), page (query), size (query), status (query) | Yes | Conditional | Yes |
| `mfg_packBulkToSizes` | POST | `/api/v1/factory/pack` | bulkBatchId (body), packagingMaterials[].materialId (body), packagingMaterials[].quantity (body), packs (body), packs[].childSkuId (body), packs[].quantity (body) | notes (body), packDate (body), packagingMaterials (body), packagingMaterials[].unit (body), packedBy (body), packs[].sizeLabel (body), packs[].unit (body) | No | No | No |
| `mfg_packingHistory` | GET | `/api/v1/factory/production-logs/{productionLogId}/packing-history` | productionLogId (path) | - | Yes | No | Yes |
| `mfg_recordPacking` | POST | `/api/v1/factory/packing-records` | lines (body), lines[].packagingSize (body), productionLogId (body) | lines[].boxesCount (body), lines[].piecesCount (body), lines[].piecesPerBox (body), lines[].quantityLiters (body), packedBy (body), packedDate (body) | No | No | No |
| `mfg_updateSlipStatus` | PATCH | `/api/v1/dispatch/slip/{slipId}/status` | slipId (path), status (query) | - | No | No | Conditional |

### Manufacturing Inventory & Masters

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_batches` | GET | `/api/v1/raw-material-batches/{rawMaterialId}` | rawMaterialId (path) | - | Yes | No | Yes |
| `mfg_createBatch` | POST | `/api/v1/raw-material-batches/{rawMaterialId}` | costPerUnit (body), quantity (body), rawMaterialId (path), supplierId (body), unit (body) | batchCode (body), notes (body) | No | No | No |
| `mfg_createFinishedGood` | POST | `/api/v1/finished-goods` | name (body), productCode (body) | cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body) | No | No | No |
| `mfg_createMapping` | POST | `/api/v1/factory/packaging-mappings` | - | cartonSize (body), litersPerUnit (body), packagingSize (body), rawMaterialId (body), unitsPerPack (body) | No | No | No |
| `mfg_createRawMaterial` | POST | `/api/v1/accounting/raw-materials` | maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body) | inventoryAccountId (body), sku (body) | No | No | No |
| `mfg_deactivateMapping` | DELETE | `/api/v1/factory/packaging-mappings/{id}` | id (path) | - | No | No | Yes |
| `mfg_deleteRawMaterial` | DELETE | `/api/v1/accounting/raw-materials/{id}` | id (path) | - | No | No | Yes |
| `mfg_getFinishedGood` | GET | `/api/v1/finished-goods/{id}` | id (path) | - | Yes | No | Yes |
| `mfg_getLowStockItems` | GET | `/api/v1/finished-goods/low-stock` | - | threshold (query) | Yes | No | Yes |
| `mfg_getStockSummary` | GET | `/api/v1/finished-goods/stock-summary` | - | - | Yes | No | Yes |
| `mfg_intake` | POST | `/api/v1/raw-materials/intake` | costPerUnit (body), quantity (body), rawMaterialId (body), supplierId (body), unit (body) | batchCode (body), notes (body) | No | No | No |
| `mfg_inventory` | GET | `/api/v1/raw-materials/stock/inventory` | - | - | Yes | No | Yes |
| `mfg_listActiveMappings` | GET | `/api/v1/factory/packaging-mappings/active` | - | - | Yes | No | Yes |
| `mfg_listBatches` | GET | `/api/v1/finished-goods/{id}/batches` | id (path) | - | Yes | No | Yes |
| `mfg_listFinishedGoods` | GET | `/api/v1/finished-goods` | - | - | Yes | No | Yes |
| `mfg_listMappings` | GET | `/api/v1/factory/packaging-mappings` | - | - | Yes | No | Yes |
| `mfg_listRawMaterials` | GET | `/api/v1/accounting/raw-materials` | - | - | Yes | No | Yes |
| `mfg_lowStock` | GET | `/api/v1/raw-materials/stock/low-stock` | - | - | Yes | No | Yes |
| `mfg_registerBatch` | POST | `/api/v1/finished-goods/{id}/batches` | finishedGoodId (body), id (path), quantity (body), unitCost (body) | batchCode (body), expiryDate (body), manufacturedAt (body) | No | No | No |
| `mfg_stockSummary` | GET | `/api/v1/raw-materials/stock` | - | - | Yes | No | Yes |
| `mfg_updateFinishedGood` | PUT | `/api/v1/finished-goods/{id}` | id (path), name (body), productCode (body) | cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body) | No | No | Yes |
| `mfg_updateMapping` | PUT | `/api/v1/factory/packaging-mappings/{id}` | id (path) | cartonSize (body), litersPerUnit (body), packagingSize (body), rawMaterialId (body), unitsPerPack (body) | No | No | Yes |
| `mfg_updateRawMaterial` | PUT | `/api/v1/accounting/raw-materials/{id}` | id (path), maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body) | inventoryAccountId (body), sku (body) | No | No | Yes |

### Operations Dashboard & Tracing

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_adminDashboard` | GET | `/api/v1/orchestrator/dashboard/admin` | X-Company-Id (header) | - | Yes | No | Yes |
| `mfg_factoryDashboard` | GET | `/api/v1/orchestrator/dashboard/factory` | X-Company-Id (header) | - | Yes | No | Yes |
| `mfg_financeDashboard` | GET | `/api/v1/orchestrator/dashboard/finance` | X-Company-Id (header) | - | Yes | No | Yes |
| `mfg_trace` | GET | `/api/v1/orchestrator/traces/{traceId}` | traceId (path) | - | Yes | No | Yes |

### Manufacturing Reports & Analytics

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfg_costBreakdown` | GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | id (path) | - | Yes | No | Yes |
| `mfg_inventoryReconciliation` | GET | `/api/v1/reports/inventory-reconciliation` | - | - | Yes | No | Yes |
| `mfg_inventoryValuation` | GET | `/api/v1/reports/inventory-valuation` | - | - | Yes | No | Yes |
| `mfg_monthlyProductionCosts` | GET | `/api/v1/reports/monthly-production-costs` | month (query), year (query) | - | Yes | No | Yes |
| `mfg_wastageReport` | GET | `/api/v1/reports/wastage` | - | - | Yes | No | Yes |

### Endpoints That Look Unsafe or Inconsistent

- `POST /api/v1/orchestrator/dispatch` and `POST /api/v1/orchestrator/dispatch/{orderId}` are exposed in OpenAPI, but backend implementation returns `410 GONE` and points to `/api/v1/sales/dispatch/confirm`.
- `POST /api/v1/dispatch/confirm` and `POST /api/v1/sales/dispatch/confirm` overlap in purpose but differ in role gates and request shape; frontend should choose one canonical command path.
- `POST /api/v1/factory/packing-records` and `POST /api/v1/factory/packing-records/{productionLogId}/complete` have no explicit method-level role gate in controller (authenticated-only behavior via global security).
- `POST /api/v1/factory/packing-records` requires idempotency key at runtime (header or body) via controller logic, but OpenAPI does not mark this as required.
- Orchestrator command endpoints mark `Idempotency-Key` header optional in signature but enforce it in code (`requireIdempotencyKey`), creating spec/runtime mismatch.
- `DELETE /api/v1/factory/production-plans/{id}` returns `204 No Content` in controller, while OpenAPI map reports only `200` success semantics.
- Most scoped endpoints define no explicit error responses in OpenAPI (only `200`), including mutating commands.
- Generated operation IDs with suffixes (`dashboard_1`, `batches_1`, `confirmDispatch_1`) are unstable for client SDK naming.

## Task 3: Enterprise Manufacturing Route Map

### Universal Header Controls (Manufacturing Portal)

| UI control | Target | APIs | Loading/Error expectations | Gate |
|---|---|---|---|---|
| `My Profile` | `/manufacturing/profile` | `profileGet`, `profileUpdate` | Form skeleton; inline validation + toast | `isAuthenticated()` |
| `Change Password` | `/manufacturing/profile?tab=password` | `authChangePassword` | Submit spinner; inline validation | `isAuthenticated()` |
| `Switch Company` | Company switch modal | `companiesList`, `companiesSwitch` | Modal loader; hard-refresh manufacturing caches after switch | `isAuthenticated()` + company membership. Note: `companiesList` excludes pure `ROLE_FACTORY` users by default |
| `Sign Out` | Redirect `/auth/login` | `authLogout` | Immediate spinner; fallback local clear on failure | `isAuthenticated()` |

Button naming standard for universal profile controls (use exactly):
- `My Profile`
- `Change Password`
- `Switch Company`
- `Sign Out`

| Route | Purpose | Backend-enforced gate (exact) |
|---|---|---|
| `/manufacturing/dashboard` | Factory KPIs, open tasks, active plans/batches, dispatch backlog. | `ROLE_ADMIN or ROLE_FACTORY` (`GET /api/v1/orchestrator/dashboard/factory`) |
| `/manufacturing/production/plans` | Create/update/cancel production plans and status transitions. | `ROLE_ADMIN or ROLE_FACTORY` |
| `/manufacturing/production/logs` | Log production runs, track material consumption and output records. | `ROLE_ADMIN or ROLE_FACTORY` |
| `/manufacturing/production/tasks` | Track factory tasks linked to sales order / packaging slip references. | `ROLE_ADMIN or ROLE_FACTORY` |
| `/manufacturing/packing/operations` | Packing records, bulk-to-size conversion, unpacked queue, packing history. | Mixed: `/factory/pack*` allows `ROLE_FACTORY|ROLE_ACCOUNTING|ROLE_ADMIN`; `/factory/packing-records*` has no explicit role gate |
| `/manufacturing/dispatch/queue` | Pending slips, preview, status management, backorder cancellation. | Mixed: list includes sales role; preview/status/cancel are `ROLE_ADMIN|ROLE_FACTORY` |
| `/manufacturing/dispatch/confirm` | Final shipment confirmation and posting trigger. | Option A (`/dispatch/confirm`): `ROLE_ADMIN|ROLE_FACTORY` + `dispatch.confirm`; Option B (`/sales/dispatch/confirm`): `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` + `dispatch.confirm` |
| `/manufacturing/inventory/finished-goods` | FG masters, stock summary, low-stock, batch registration. | Mixed FG gate: reads broad; writes are `ROLE_ADMIN|ROLE_FACTORY`; low-stock excludes accounting |
| `/manufacturing/inventory/raw-materials` | RM masters, stock snapshots, batches, intake. | Base `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_FACTORY`; intake is `ROLE_ADMIN|ROLE_ACCOUNTING` only |
| `/manufacturing/config/packaging-mappings` | Configure size-to-material mappings used by packing. | Read: `ROLE_ADMIN|ROLE_FACTORY`; Write/Delete: `ROLE_ADMIN` |
| `/manufacturing/reports/production-costs` | Monthly production cost and per-log cost breakdown. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/manufacturing/reports/wastage` | Wastage and inventory reconciliation insights. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/manufacturing/ops/traces/:traceId` | Orchestrator trace drilldown for fulfillment/dispatch command lifecycle. | `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES|ROLE_FACTORY` |

### `/manufacturing/dashboard`
- Required API calls: `mfg_factoryDashboard`, `mfg_dashboard_1`, `mfg_tasks`, `mfg_getPendingSlips`
- Loading state: KPI skeleton cards + table skeleton rows.
- Empty state: “No active plans/tasks/slips” with CTA links.
- Error state: widget-level retry and page banner fallback.
- Suggested table columns: planNumber, batchNumber, status, dueDate, pendingSlips, activeTasks.
- Suggested form fields: date window, assignee filter, plan status filter.
- Role gate: `ROLE_ADMIN or ROLE_FACTORY`.

### `/manufacturing/production/plans`
- Required API calls: `mfg_plans`, `mfg_createPlan`, `mfg_updatePlan`, `mfg_updatePlanStatus`, `mfg_deletePlan`, `mfg_listBrands`, `mfg_listBrandProducts`
- Loading state: table load + form submit spinner.
- Empty state: no plans yet, “Create first production plan”.
- Error state: inline form validation + toast + optimistic rollback for status change.
- Suggested table columns: planNumber, productName, plannedDate, quantity, status, notes.
- Suggested form fields: planNumber, productName/productId, plannedDate, quantity, notes.
- Role gate: `ROLE_ADMIN or ROLE_FACTORY`.

### `/manufacturing/production/logs`
- Required API calls: `mfg_list`, `mfg_create`, `mfg_detail`
- Loading state: list skeleton + detail panel skeleton.
- Empty state: no production logs found for current filters.
- Error state: list retry + detail fallback card.
- Suggested table columns: id, producedAt, productId, batchSize, mixedQuantity, addToFinishedGoods, createdBy.
- Suggested form fields: productId, brandId, batchSize, mixedQuantity, materials[], laborCost, overheadCost, producedAt, notes.
- Role gate: `ROLE_ADMIN or ROLE_FACTORY`.

### `/manufacturing/production/tasks`
- Required API calls: `mfg_tasks`, `mfg_createTask`, `mfg_updateTask`
- Loading state: board/list loader with action-level spinners.
- Empty state: no tasks; create task CTA.
- Error state: inline error on row/action.
- Suggested table columns: id, title, status, assignee, dueDate, salesOrderId, packagingSlipId.
- Suggested form fields: title, description, assignee, dueDate, status, salesOrderId, packagingSlipId.
- Role gate: `ROLE_ADMIN or ROLE_FACTORY`.

### `/manufacturing/packing/operations`
- Required API calls: `mfg_listUnpackedBatches`, `mfg_recordPacking`, `mfg_completePacking`, `mfg_packingHistory`, `mfg_listBulkBatches`, `mfg_listChildBatches`, `mfg_packBulkToSizes`
- Loading state: queue loader, modal loader for batch details, action spinners for pack/complete.
- Empty state: no unpacked or bulk batches available.
- Error state: hard validation errors for pack lines + toast.
- Suggested table columns: productionLogId, packedDate, packedBy, packagingSize, quantityLiters, boxesCount, piecesCount.
- Suggested form fields:
  - Packing record: productionLogId, packedDate, packedBy, lines[].packagingSize, lines[].quantityLiters, lines[].boxesCount.
  - Bulk-to-size: bulkBatchId, packs[].childSkuId, packs[].quantity, packagingMaterials[].materialId, packagingMaterials[].quantity.
- Role gate: mixed (see route table); explicitly test non-factory roles due missing method-level gate on packing-record endpoints.

### `/manufacturing/dispatch/queue`
- Required API calls: `mfg_getPendingSlips`, `mfg_getDispatchPreview`, `mfg_getPackagingSlip`, `mfg_getPackagingSlipByOrder`, `mfg_updateSlipStatus`, `mfg_cancelBackorder`
- Loading state: list polling loader + per-slip modal loader.
- Empty state: no pending dispatch slips.
- Error state: retry with preserved filters.
- Suggested table columns: slipId, orderId, dealerName, status, createdAt, totalLines, reservedQty.
- Suggested form fields: status update, cancel reason.
- Role gate: mixed read/write dispatch roles.

### `/manufacturing/dispatch/confirm`
- Required API calls: `mfg_confirmDispatch_1` and/or `mfg_confirmDispatch`, plus `mfg_getDispatchPreview`.
- Loading state: confirmation preview modal + submit lock.
- Empty state: no dispatchable lines.
- Error state: line-level validation + posting failure banner.
- Suggested table columns: lineId, sku, orderedQty, shippedQty, variance, notes.
- Suggested form fields: packagingSlipId/orderId, lines[].shippedQuantity or shipQty, notes, overrideRequestId, confirmedBy.
- Role gate: choose one backend path and gate accordingly; do not expose both to same user without clear policy.

### `/manufacturing/inventory/finished-goods`
- Required API calls: `mfg_listFinishedGoods`, `mfg_getFinishedGood`, `mfg_getStockSummary`, `mfg_getLowStockItems`, `mfg_listBatches`, `mfg_createFinishedGood`, `mfg_updateFinishedGood`, `mfg_registerBatch`
- Loading state: table + details split-view loader.
- Empty state: no FG masters/batches.
- Error state: save/action toasts + row-level retry.
- Suggested table columns: productCode, name, unit, quantityOnHand, valuation, lowStockFlag.
- Suggested form fields: productCode, name, unit, costingMethod, valuationAccountId, revenueAccountId, taxAccountId.
- Role gate: mixed FG read/write policy from controller.

### `/manufacturing/inventory/raw-materials`
- Required API calls: `mfg_listRawMaterials`, `mfg_createRawMaterial`, `mfg_updateRawMaterial`, `mfg_deleteRawMaterial`, `mfg_stockSummary`, `mfg_inventory`, `mfg_lowStock`, `mfg_batches`, `mfg_createBatch`, `mfg_intake`
- Loading state: stock dashboard loaders + modal form loaders.
- Empty state: no raw materials/batches.
- Error state: validation + rollback toast.
- Suggested table columns: sku, name, unitType, reorderLevel, minStock, maxStock, availableQty.
- Suggested form fields: sku, name, unitType, reorderLevel, minStock, maxStock, inventoryAccountId; intake fields for quantity/cost/supplier/unit.
- Role gate: intake action excludes factory role.

### `/manufacturing/config/packaging-mappings`
- Required API calls: `mfg_listMappings`, `mfg_listActiveMappings`, `mfg_createMapping`, `mfg_updateMapping`, `mfg_deactivateMapping`
- Loading state: list skeleton + inline action spinner.
- Empty state: no mappings configured warning (packing risk).
- Error state: form validation + save toast.
- Suggested table columns: id, packagingSize, rawMaterialId, unitsPerPack, litersPerUnit, active.
- Suggested form fields: packagingSize, rawMaterialId, unitsPerPack, litersPerUnit, cartonSize.
- Role gate: write/delete admin-only.

### `/manufacturing/reports/production-costs`
- Required API calls: `mfg_monthlyProductionCosts`, `mfg_costBreakdown`, `mfg_inventoryValuation`, `mfg_inventoryReconciliation`
- Loading state: chart/table loaders with skeleton placeholders.
- Empty state: no cost data for selected month.
- Error state: query retry + export-disabled state.
- Suggested table columns: month, year, totalCost, laborCost, overheadCost, materialCost, variance.
- Suggested form fields: month, year, productionLogId drilldown.
- Role gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (factory users blocked by backend currently).

### `/manufacturing/reports/wastage`
- Required API calls: `mfg_wastageReport`
- Loading state: report skeleton + filters loading.
- Empty state: no wastage events.
- Error state: retry and fallback summary.
- Suggested table columns: productionLogId, rawMaterialId, expectedQty, actualQty, varianceQty, variancePercent.
- Suggested form fields: date range, product/brand filters.
- Role gate: `ROLE_ADMIN or ROLE_ACCOUNTING`.

### `/manufacturing/ops/traces/:traceId`
- Required API calls: `mfg_trace`
- Loading state: trace timeline skeleton.
- Empty state: no events for traceId.
- Error state: not-found/unauthorized separation.
- Suggested table columns: timestamp, eventType, module, status, referenceId, payload summary.
- Suggested form fields: traceId search input.
- Role gate: `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES|ROLE_FACTORY`.

## Senior Developer Verification Notes (What To Enforce Before FE Freeze)

1. Pick one dispatch confirmation command path (recommended: `/api/v1/sales/dispatch/confirm`) and feature-flag/deprecate the other in UI.
2. Confirm with backend whether packing-record endpoints should be factory-only; if yes, add method-level `@PreAuthorize` before release.
3. Align OpenAPI with runtime idempotency requirements for orchestrator and packing commands.
4. Decide whether factory users should access manufacturing reports; current backend blocks them.
5. Add contract tests for role matrix (`factory`, `admin`, `sales`, `accounting`) on critical routes: packing, dispatch confirm, raw-material intake, packaging mapping writes.

## Delta Update (2026-02-13): Costing Coverage + Audit-Trail + Approval Handshake

### Costing Surfaces That Must Be Visible In FE

Keep all costing-critical surfaces first-class in manufacturing UI:
- `POST /api/v1/factory/cost-allocation`
- `GET /api/v1/reports/monthly-production-costs`
- `GET /api/v1/reports/production-logs/{id}/cost-breakdown`
- `GET /api/v1/reports/inventory-valuation`
- `GET /api/v1/reports/inventory-reconciliation`

UX requirement:
- Every production cost row/detail should expose a reference/journal drill-through path for accounting reconciliation.

### Accounting Audit Trail Handoff For Manufacturing Costing

When finance/admin users review manufacturing postings, FE must support handoff to accounting audit APIs:
- `GET /api/v1/accounting/audit/transactions`
- `GET /api/v1/accounting/audit/transactions/{journalEntryId}`

Recommended route-level integration:
- Add “Open in Accounting Audit Trail” action from:
  - production cost breakdown detail,
  - cost allocation success confirmation,
  - inventory valuation/reconciliation exception rows.

### Approval Flow Linkage (Factory-Origin Overrides)

Dispatch credit override requests created from factory context appear in admin approvals queue with explicit source metadata:
- Queue endpoint: `GET /api/v1/admin/approvals`
- Expected row semantics:
  - `type=CREDIT_LIMIT_OVERRIDE_REQUEST`
  - `sourcePortal=FACTORY_PORTAL` (when tied to packaging-slip path)
  - `actionType=APPROVE_DISPATCH_CREDIT_OVERRIDE`
  - action endpoints from payload (`approveEndpoint`, `rejectEndpoint`)

Frontend rule:
- Manufacturing portal requests overrides; admin/accounting portals approve/reject.
- Do not duplicate approve/reject actions in factory UI.
