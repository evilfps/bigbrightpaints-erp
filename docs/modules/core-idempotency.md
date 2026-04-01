# Core Platform Contracts: Shared-Versus-Module-Local Idempotency Behavior

Last reviewed: 2026-03-30

This packet documents the **shared idempotency infrastructure** and the **module-local idempotency implementations** that together govern safe write-path replay behavior across the BigBright ERP backend. It is the integrating slice of the core platform contracts packet: it explains shared-vs-module-local ownership, reconciles the full platform contract, and surfaces known pre-existing contract inconsistencies as explicit caveats rather than silently normalizing them away.

> **Scope note:** This is the third and integrating slice of the core platform contracts packet. The first slice covers the [security filter chain and exception/error contract](core-security-error.md). The second slice covers [audit-surface ownership, runtime-gating, and settings risk](core-audit-runtime-settings.md). This slice adds idempotency and reconciles all three into one coherent canonical reference. Readers who need the complete picture should start with the [reconciled contract table in §5](#5-reconciled-core-platform-contract) and then read individual slices for detail.

---

## Ownership Summary

| Area | Package | Role |
| --- | --- | --- |
| Shared idempotency infrastructure | `core/idempotency/` | Key normalization, reservation pattern, payload hashing, signature building, data-integrity-violation detection |
| Shared header resolution | `core/util/IdempotencyHeaderUtils` | Header/body key resolution, mismatch detection |
| Sales order idempotency | `modules/sales/service/SalesIdempotencyService` + `SalesCoreEngine` | Order-key reservation, payload signature, replay resolution |
| Goods receipt idempotency | `modules/purchasing/service/GoodsReceiptService` | GRN-key normalization, payload hash, lock-based race reconciliation |
| Packing idempotency | `modules/factory/service/PackingIdempotencyService` | Pack-record reservation, payload hash, replay resolution |
| Payroll run idempotency | `modules/hr/service/PayrollRunService` | Period+type keyed, signature checks, metadata repair |
| Inventory adjustment idempotency | `modules/inventory/service/InventoryAdjustmentService` + `RawMaterialService` | Adjustment-key normalization, payload hash, reservation + race reconciliation |
| Opening stock import idempotency | `modules/inventory/service/OpeningStockImportService` | Batch-key + idempotency-key dual-key replay protection |
| Accounting idempotency | `modules/accounting/service/AccountingIdempotencyService` | Extends `AccountingCoreEngine`; facade-aware journal/receipt/settlement delegation |
| Orchestrator command idempotency | `orchestrator/service/OrchestratorIdempotencyService` | Lease-based exactly-once command scope with rollback-aware status tracking |

---

## 1. Shared Idempotency Infrastructure

The platform provides a small set of shared utilities that every module-local idempotency implementation uses as building blocks. The shared infrastructure does **not** own replay decisions or signature semantics — it provides key normalization, reservation primitives, hashing, and data-integrity-violation detection so that modules can implement their own replay logic consistently.

### 1.1 IdempotencyReservationService

**Source:** [`core/idempotency/IdempotencyReservationService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/idempotency/IdempotencyReservationService.java)

**Scope:** Request-scoped bean that provides reusable reservation and assertion primitives.

| Method | Purpose |
| --- | --- |
| `normalizeKey(raw)` | Delegates to `IdempotencyUtils.normalizeKey()` — trims whitespace, returns `null` for empty |
| `requireKey(raw, label)` | Normalizes and validates the key; throws `VALIDATION_MISSING_REQUIRED_FIELD` if absent or `VALIDATION_INVALID_INPUT` if > 128 characters |
| `isDataIntegrityViolation(error)` | Inspects the exception chain for `DataIntegrityViolationException` — used to detect concurrent-insert races |
| `payloadMismatch(key)` | Creates a `CONCURRENCY_CONFLICT` exception with the conflicting key in details |
| `assertAndRepairSignature(...)` | Compares stored vs expected signature on an entity; if stored is empty, writes the expected signature. Rejects on mismatch |
| `reserve(existingLookup, reservationCreator)` | Generic reserve-first pattern: look up existing, create if absent, catch `DataIntegrityViolationException` and re-lookup for concurrent race |

**Key invariant:** The reservation service never makes domain-level replay decisions. It provides the *mechanics* of idempotency (normalize, reserve, detect races, assert signatures). Modules decide *what to do* when a reservation returns a non-leader result.

### 1.2 IdempotencyUtils

**Source:** [`core/idempotency/IdempotencyUtils.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/idempotency/IdempotencyUtils.java)

| Method | Purpose |
| --- | --- |
| `normalizeKey(raw)` | Trims whitespace, returns `null` for empty/blank input |
| `normalizeToken(value)` | Null-safe trim; returns empty string for null |
| `normalizeUpperToken(value)` | Null-safe trim + uppercase; for case-insensitive comparison tokens |
| `normalizeAmount(BigDecimal)` | Strips trailing zeros for stable decimal representation |
| `normalizeDecimal(BigDecimal)` | Strips trailing zeros; returns `"0"` for null |
| `sha256Hex(value)` | SHA-256 hex digest of a string |
| `sha256Hex(byte[])` | SHA-256 hex digest of a byte array |
| `sha256Hex(value, length)` | Truncated SHA-256 hex digest |
| `isDataIntegrityViolation(error)` | Walks the cause chain looking for Spring's `DataIntegrityViolationException` |

**Key invariant:** All hashing is deterministic and based on `DigestUtils.sha256Hex`. The utility class is stateless and safe for concurrent use.

### 1.3 IdempotencySignatureBuilder

**Source:** [`core/idempotency/IdempotencySignatureBuilder.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/idempotency/IdempotencySignatureBuilder.java)

A fluent builder that constructs normalized payload strings and their SHA-256 hashes for signature comparison:

```java
String signature = IdempotencySignatureBuilder.create()
    .addUpperToken(orderNumber)
    .addAmount(totalAmount)
    .addToken(dealerCode)
    .buildHash();
```

Segments are joined with `|` delimiters. Each segment is normalized via the corresponding `IdempotencyUtils` method before joining, ensuring that equivalent payloads produce identical hashes regardless of whitespace, case, or trailing-zero differences.

### 1.4 IdempotencyHeaderUtils

**Source:** [`core/util/IdempotencyHeaderUtils.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/util/IdempotencyHeaderUtils.java)

| Method | Purpose |
| --- | --- |
| `resolveHeaderKey(idempotencyKeyHeader, legacyIdempotencyKeyHeader)` | Resolves the effective key from `Idempotency-Key` and `X-Idempotency-Key` headers. Rejects if both are present with different values |
| `resolveBodyOrHeaderKey(bodyKey, idempotencyKeyHeader, legacyIdempotencyKeyHeader)` | Resolves from body field first, then headers. Rejects if body and header disagree |

**Header precedence:** `Idempotency-Key` is the canonical header. `X-Idempotency-Key` is a legacy header that some endpoints still accept for backward compatibility. When both are present with different values, the platform rejects the request with `VALIDATION_INVALID_INPUT`.

---

## 2. Module-Local Idempotency Implementations

Every module that protects write paths with idempotency layers its own domain-specific logic on top of the shared infrastructure. The following sections describe each module's implementation, its signature semantics, and how it differs from the shared pattern.

### 2.1 Sales Order Creation

**Services:** `SalesIdempotencyService` → `SalesCoreEngine` → `SalesOrderRepository`

**Entity fields:** `SalesOrder.idempotencyKey`, `SalesOrder.idempotencyHash`

**Unique constraint:** `(company_id, idempotency_key)`

**Pattern:**

1. Controller resolves the idempotency key from `Idempotency-Key`, `X-Idempotency-Key`, or the request body field, rejecting mismatches.
2. `SalesCoreEngine` normalizes the key via `IdempotencyUtils.normalizeKey()`.
3. Looks up existing order by `(company, idempotencyKey)`.
4. If found: compares stored `idempotencyHash` against the computed request signature. Returns the existing order on match; throws `CONCURRENCY_CONFLICT` on mismatch.
5. If not found: creates the order, catches `DataIntegrityViolationException`, re-looks up, and asserts signature match.
6. On fresh creates where the hash is not yet persisted, `assertAndRepairSignature` writes the hash.

**Legacy support:** The controller accepts both `Idempotency-Key` and `X-Idempotency-Key` headers. The order request DTO also carries an `idempotencyKey` body field. If both header and body provide a key, they must match.

**Test evidence:** `SalesControllerIdempotencyHeaderTest` proves header parity, legacy support, and mismatch rejection.

### 2.2 Goods Receipt (GRN) Creation

**Service:** `GoodsReceiptService` → `GoodsReceiptRepository`

**Entity fields:** `GoodsReceipt.idempotencyKey`, `GoodsReceipt.idempotencyHash`

**Unique constraint:** `(company_id, idempotency_key)`

**Pattern:**

1. Controller rejects `X-Idempotency-Key` explicitly, then resolves the key from `Idempotency-Key` header or the request body's `idempotencyKey` field via `IdempotencyHeaderUtils.resolveBodyOrHeaderKey()`. A key from either source is required — this is stricter than sales order creation, which also accepts `X-Idempotency-Key`.
2. Service normalizes the key, computes a request signature hash.
3. Looks up existing GRN by `(company, idempotencyKey)` with eager line loading.
4. If found: asserts stored signature matches the incoming signature. Returns existing on match.
5. If not found: attempts insert, catches `DataIntegrityViolationException`, re-looks up, and asserts match.
6. `IdempotencyReservationService` is used for race reconciliation.

**Key difference from sales:** GRN explicitly rejects `X-Idempotency-Key` (unlike sales, which accepts it as legacy). The idempotency key is resolved from the `Idempotency-Key` header or the request body's `idempotencyKey` field via `IdempotencyHeaderUtils.resolveBodyOrHeaderKey()`, and a key from either source is required.

**Test evidence:** `TS_P2PGoodsReceiptIdempotencyTest`, `PurchasingWorkflowControllerTest`.

### 2.3 Packing Request

**Service:** `PackingIdempotencyService` → `PackingRequestRecordRepository`

**Entity:** `PackingRequestRecord` (separate from the main packing entity)

**Unique constraint:** `(company_id, idempotency_key)`

**Pattern:**

1. Controller requires `Idempotency-Key` header. `X-Idempotency-Key` is **explicitly rejected** (same strict stance as GRN).
2. `PackingIdempotencyService` computes a domain-specific payload hash including log ID, packed date, packed-by, close-residual-wastage flag, and all line details (size, child SKU, batch count, liters, pieces, boxes, pieces-per-box).
3. Reserves a `PackingRequestRecord` with `(company, idempotencyKey, idempotencyHash, productionLogId)`.
4. If existing record found: validates production log ID match and hash match. Returns a `replayResult` with the current production log detail.
5. On successful pack completion: `markCompleted(reservation, packingRecordId)` links the reservation to the actual packing record.

**Key difference:** Packing uses a separate reservation entity (`PackingRequestRecord`) rather than embedding idempotency fields on the primary business entity. This allows the idempotency reservation to exist before the business entity is created.

**Test evidence:** `TS_PackingIdempotencyAndFacadeBoundaryTest`, `TS_BulkPackDeterministicReferenceTest`.

### 2.4 Payroll Run Creation

**Service:** `PayrollRunService` → `PayrollRunRepository`

**Entity fields:** `PayrollRun.idempotencyKey`, `PayrollRun.idempotencyHash`

**Unique constraint:** `(company_id, idempotency_key)`

**Pattern:**

1. The idempotency key is **server-derived** from the request payload using `buildIdempotencyKey(runType, periodStart, periodEnd)` — the caller does **not** supply an `Idempotency-Key` header or body field. The key format is `PAYROLL:{runType}:{periodStart}:{periodEnd}`.
2. The service computes a payload signature via `IdempotencySignatureBuilder` using `runType`, `periodStart`, `periodEnd`, and `remarks`.
3. Looks up existing run by `(company, idempotencyKey)`.
4. If found: asserts stored signature matches using `assertRunSignatureMatches()`. If the stored hash is empty, derives the signature from the persisted run fields and compares that instead (repair-on-read).
5. If not found by idempotency key: falls back to legacy lookup by `(company, runType, periodStart, periodEnd)` to handle runs created before the idempotency field was added.
6. If not found by either lookup: creates the run with the derived key and computed hash.
7. `ensureIdempotencyMetadata()` writes the key and hash to runs that were created before the idempotency fields were added (repair-on-read).

**Key difference:** Payroll is the only module where the idempotency key is **entirely server-derived** from the request payload rather than client-supplied. It also uses a dual-lookup strategy (idempotency key first, then legacy fields) to handle pre-idempotency runs, and a repair-on-read pattern to backfill missing idempotency metadata.

**Test evidence:** `PayrollRunServiceTest`, payroll-related integration tests.

### 2.5 Inventory Adjustments and Raw Material Operations

**Services:** `InventoryAdjustmentService`, `RawMaterialService` → `InventoryAdjustmentRepository`, `RawMaterialIntakeRecordRepository`

**Entity fields:** `InventoryAdjustment.idempotencyKey`, `RawMaterialIntakeRecord.idempotencyKey`

**Unique constraints:** `(company_id, idempotency_key)` on both entities

**Pattern:**

1. Controllers accept `Idempotency-Key` header. Some also accept `X-Idempotency-Key` as legacy.
2. Services require the key via `IdempotencyReservationService.requireKey()`.
3. Reserve-first with race reconciliation using the shared `reserve()` pattern.
4. On match: return existing adjustment. On mismatch: throw `CONCURRENCY_CONFLICT`.

**Key difference:** Inventory operations use `IdempotencyReservationService` directly for the reserve-then-race-reconcile pattern, rather than implementing their own reservation logic.

### 2.6 Opening Stock Import

**Service:** `OpeningStockImportService` → `OpeningStockImportRepository`

**Unique constraint:** Dual-key model — `(company_id, idempotency_key)` on the import record plus a separate `openingStockBatchKey` query parameter.

**Pattern:**

1. Endpoint requires both `Idempotency-Key` header and `openingStockBatchKey` query parameter.
2. The `Idempotency-Key` is the request-replay key (replay the same request = same result).
3. The `openingStockBatchKey` is the batch-identity key (same batch = same import).
4. Cross-key validation: a fresh `Idempotency-Key` cannot reuse an already-processed `openingStockBatchKey`, and the same `Idempotency-Key` cannot be used with a different `openingStockBatchKey`.

**Key difference:** Opening stock is the only write path that uses a **dual-key model** with cross-key consistency checks. This prevents both accidental batch re-application and key-reuse attacks.

**Test evidence:** `CR_OpeningStockImportIdempotencyIT`.

### 2.7 Accounting Journal and Settlement Operations

**Service:** `AccountingIdempotencyService` (extends `AccountingCoreEngine`)

**Pattern:**

`AccountingIdempotencyService` is a thin layer that extends `AccountingCoreEngine` and adds facade-aware delegation for manual journal entries. The actual idempotency enforcement for accounting operations lives in `AccountingCoreEngine` and `AccountingFacade`, which use the shared `IdempotencyReservationService` for key normalization and signature checks on journal entries, settlements, receipts, and payments.

Accounting operations do not use a separate idempotency reservation entity. Instead, idempotency keys and signatures are stored directly on the `JournalEntry` entity, and replay resolution occurs at the engine level.

### 2.8 Orchestrator Command Idempotency

**Service:** `OrchestratorIdempotencyService` → `OrchestratorCommandRepository`

**Entity:** `OrchestratorCommand` with `status`, `requestHash`, `traceId`, `idempotencyKey`

**Unique constraint:** `(company_id, command_name, idempotency_key)` via `reserveScope()`

**Pattern:**

1. `start(commandName, idempotencyKey, requestPayload, traceIdSupplier)` opens a `REQUIRES_NEW` transaction.
2. Attempts to `reserveScope()` — an insert-where-not-exists database operation.
3. Locks the existing row if reservation fails.
4. If newly reserved: `shouldExecute = true`. Command will run.
5. If existing and hash matches: `shouldExecute = false` (already completed). Return existing result.
6. If existing and hash differs: throws `CONCURRENCY_CONFLICT`.
7. If existing with `FAILED` status: `markRetry()` sets `shouldExecute = true` for retry.
8. `markSuccess()` registers an `afterCommit` synchronization that persists success in a separate transaction. If the outer transaction rolls back, the command is marked `FAILED` instead.
9. `markFailed()` persists failure in a `REQUIRES_NEW` transaction so the failure record survives even if the caller's transaction rolls back.

**Key difference:** The orchestrator uses a **lease-based model** with `REQUIRES_NEW` transactions, pessimistic database locks (`lockByScope`), and rollback-aware status tracking. This is the most sophisticated idempotency implementation in the system — it is designed for cross-module coordination commands that must never execute twice even under transaction rollback.

**Test evidence:** `OrchestratorIdempotencyServiceTest`, `TS_RuntimeOrchestratorIdempotencyExecutableCoverageTest`.

---

## 3. Shared vs Module-Local: Summary Comparison

| Dimension | Shared Infrastructure | Module-Local Implementation |
| --- | --- | --- |
| **Key normalization** | `IdempotencyReservationService.normalizeKey()` / `IdempotencyUtils.normalizeKey()` | All modules delegate to shared |
| **Key validation** | `IdempotencyReservationService.requireKey()` | Most modules delegate; some validate inline |
| **Header resolution** | `IdempotencyHeaderUtils` | Controllers call shared; some reject legacy headers |
| **Payload hashing** | `IdempotencySignatureBuilder` + `IdempotencyUtils.sha256Hex()` | Modules build their own signatures using the shared builder |
| **Reservation pattern** | `IdempotencyReservationService.reserve()` | Some modules use shared `reserve()`, others implement their own |
| **Race reconciliation** | `IdempotencyReservationService.isDataIntegrityViolation()` | All modules use shared for `DataIntegrityViolationException` detection |
| **Replay decision** | **Not owned by shared** | Each module decides what to return on replay |
| **Persistence** | **Not owned by shared** | Each module owns its idempotency entity/fields |
| **Error reporting** | `IdempotencyReservationService.payloadMismatch()` | Some modules use shared, others create their own `CONCURRENCY_CONFLICT` |

---

## 4. Known Pre-Existing Contract Inconsistencies

The following inconsistencies exist in the current idempotency contract. They are documented here explicitly so that readers are not surprised by them, and so that future normalization work has a clear starting point.

### 4.1 Inconsistent Legacy Header Support

| Module | `Idempotency-Key` | `X-Idempotency-Key` | Body Field | Behavior |
| --- | --- | --- | --- | --- |
| Sales order creation | ✅ Accepted | ✅ Accepted (legacy) | ✅ Accepted | Mismatch between any two sources → rejection |
| Goods receipt creation | ✅ Required | ❌ Explicitly rejected | ✅ Accepted | `X-Idempotency-Key` → immediate 400 |
| Packing | ✅ Required | ❌ Explicitly rejected | Not used | `X-Idempotency-Key` → immediate 400 (same strict stance as GRN) |
| Inventory adjustments | ✅ Accepted | ✅ Accepted (legacy) | ✅ Accepted | Same as sales |
| Raw material intake | ✅ Required | ✅ Accepted (legacy) | Not used | Both headers accepted |
| Raw material adjustments | ✅ Required | ✅ Accepted (legacy) | ✅ Accepted | Both headers + body accepted |
| Payroll run | Not used | Not used | Server-derived | Key built from `runType + periodStart + periodEnd`; no client-supplied key |
| Orchestrator commands | ✅ Accepted | Not used | Not used | Key derived from `Idempotency-Key`, `X-Request-Id`, or trace-ID via `CorrelationIdentifierSanitizer` |
| Opening stock import | ✅ Required | Not used | Not used | Plus `openingStockBatchKey` query param |
| Catalog import | ✅ Accepted | ✅ Accepted (legacy) | Not used | `resolveHeaderKey()` resolves between both headers; mismatch → rejection |

**Impact:** Frontend clients must know which header/body combination each endpoint expects. There is no single universal rule. A caller who assumes `X-Idempotency-Key` works everywhere will get 400 errors on GRN creation.

### 4.2 Inconsistent Key-Requirement Enforcement

| Module | Key Required? | Enforcement |
| --- | --- | --- |
| Sales order creation | Optional | Key resolved from header/body; missing key → order created without idempotency protection |
| Goods receipt creation | Required | `IdempotencyReservationService.requireKey()` → 400 if absent |
| Packing | Required | Missing key → 400 |
| Inventory adjustments | Required | `IdempotencyReservationService.requireKey()` → 400 if absent |
| Raw material intake | Required | `IdempotencyReservationService.requireKey()` → 400 if absent |
| Payroll run | Server-derived | Key built from `runType + periodStart + periodEnd` — no client-supplied key required |
| Orchestrator commands | Required | `CorrelationIdentifierSanitizer.sanitizeRequiredIdempotencyKey()` → rejection if absent |
| Opening stock import | Required | Missing key → 400 |
| Catalog import | Optional | Key resolved from headers; missing key → import proceeds without idempotency protection |

**Impact:** Sales order creation is the only major write path where the idempotency key is optional. Payroll is unique in that the key is always present (server-derived from request fields) rather than client-supplied. All other write paths require a client-supplied key.

### 4.3 Inconsistent Signature Storage

| Module | Signature Field | Stored On | Repair-on-Read |
| --- | --- | --- | --- |
| Sales order | `SalesOrder.idempotencyHash` | Business entity | Yes (`assertAndRepairSignature`) |
| Goods receipt | Entity-level hash | Business entity | Via shared reservation |
| Packing | `PackingRequestRecord.idempotencyHash` | Separate reservation entity | No (hash written at reservation time) |
| Payroll run | `PayrollRun` entity fields | Business entity | Yes (`ensureIdempotencyMetadata`) |
| Inventory adjustment | `InventoryAdjustment` entity | Business entity | Via shared reservation |
| Orchestrator command | `OrchestratorCommand.requestHash` | Command entity | No (hash written at reservation time) |

**Impact:** Some modules write the payload hash at reservation time (packing, orchestrator), while others write it after the business entity is created and may need repair-on-read (sales, payroll). This difference means that the replay-safety window varies: modules that write hashes at reservation time can reject mismatched replays immediately, while modules that use repair-on-read may accept a replay before the hash is persisted.

### 4.4 SalesIdempotencyService Is a Pass-Through

`SalesIdempotencyService` delegates entirely to `SalesCoreEngine`:

```java
public SalesOrderDto createOrderWithIdempotency(SalesOrderRequest request) {
    return salesCoreEngine.createOrder(request);
}
```

The actual idempotency logic lives in `SalesCoreEngine`, not in the nominal idempotency service. This creates a naming inconsistency: the service named "IdempotencyService" does not own idempotency enforcement.

### 4.5 AccountingIdempotencyService Inverts the Delegation Pattern

`AccountingIdempotencyService` extends `AccountingCoreEngine` rather than delegating to it. When an `AccountingFacade` bean is available, it delegates to the facade; otherwise it falls back to the parent engine. This means the "idempotency service" is actually a routing layer for the accounting engine, not a dedicated idempotency service in the same sense as `PackingIdempotencyService` or `OrchestratorIdempotencyService`.

### 4.6 Purchase Order Creation Has No Idempotency Key

`createPurchaseOrder(...)` uses order-number uniqueness as its duplicate guard, not a caller-supplied `Idempotency-Key`. This means PO creation cannot be safely retried by the client with a stable key — the caller must generate a unique order number each time or handle the `DATA_001` duplicate error.

### 4.7 Mixed bodyKey/headerKey Resolution Across Endpoints

Some endpoints accept an idempotency key in the request body alongside the header, while others accept only the header. When both are present and disagree, the behavior is to reject — but the set of endpoints that accept body keys is not uniform. `IdempotencyHeaderUtils.resolveBodyOrHeaderKey()` provides the shared resolution logic, but not all controllers use it.

### 4.8 Payroll Run Uses a Server-Derived Key

`PayrollRunService` is the only write path where the idempotency key is **not client-supplied** at all. The service derives the key from `runType + periodStart + periodEnd` via `buildIdempotencyKey()`, meaning the caller has no way to control or override it. This is a different model from every other idempotency-protected endpoint in the system. While it eliminates the risk of missing keys, it also means the caller cannot supply a stable key across retry attempts if the period parameters change between calls.

### 4.9 Catalog Import Idempotency Is Optional

The catalog import endpoint (`POST /api/v1/catalog/import`) accepts `Idempotency-Key` and `X-Idempotency-Key` headers via `resolveHeaderKey()`, but the key is **not required**. If no key is provided, the import proceeds without idempotency protection. This is the same optional-key stance as sales order creation.

---

## 5. Reconciled Core Platform Contract

The core platform contracts packet now covers five areas, each documented in its own section:

| Area | Document | What It Explains |
| --- | --- | --- |
| **Security filter chain** | [core-security-error.md](core-security-error.md) §1 | JWT authentication, company-context resolution, must-change-password corridor, filter ordering |
| **Exception/error contract** | [core-security-error.md](core-security-error.md) §2–5 | `ApplicationException` + `ErrorCode`, global and fallback exception handlers, response envelope, production detail sanitization |
| **Audit-surface ownership** | [core-audit-runtime-settings.md](core-audit-runtime-settings.md) §1 | Platform audit, enterprise audit trail, accounting event store — owners, write semantics, failure modes |
| **Runtime-gating and settings risk** | [core-audit-runtime-settings.md](core-audit-runtime-settings.md) §2–4 | Three-layer runtime enforcement, dual enforcement services, global-vs-tenant settings risk |
| **Idempotency behavior** | This document §1–4 | Shared infrastructure, module-local implementations, contract inconsistencies |

### 5.1 How the Slices Fit Together

```
Incoming Request
  │
  ├─ Security filter chain                    [core-security-error.md §1]
  │     JwtAuthenticationFilter → CompanyContextFilter → MustChangePasswordCorridorFilter
  │
  ├─ Runtime gating                          [core-audit-runtime-settings.md §2]
  │     TenantRuntimeRequestAdmissionService → TenantRuntimeEnforcementService
  │     (TenantRuntimeAccessService for portal/reports/demo)
  │
  ├─ Controller resolves idempotency key     [This document §1]
  │     IdempotencyHeaderUtils / IdempotencyReservationService
  │
  ├─ Module service applies business logic   [This document §2]
  │     Module-local idempotency service → shared reservation + signature primitives
  │
  ├─ Accounting facade posts journal         [core-audit-runtime-settings.md §1]
  │     AccountingEventStore (synchronous) → JournalEntryPostedAuditListener (async after commit)
  │
  ├─ Exception handling                      [core-security-error.md §2]
  │     GlobalExceptionHandler / CoreFallbackExceptionHandler
  │
  └─ Audit routing                           [core-audit-runtime-settings.md §4]
        AuditExceptionRoutingService → AuditService / EnterpriseAuditTrailService
```

### 5.2 Cross-References Between Slices

| From | To | Relationship |
| --- | --- | --- |
| Security filter chain | Runtime gating | `CompanyContextFilter` calls `TenantRuntimeRequestAdmissionService` |
| Security filter chain | Error contract | Filters throw `AuthSecurityContractException` handled by fallback handler |
| Security filter chain | Fail-open/fail-closed model | Authentication validation is fail-open (Spring Security rejects unauthenticated requests to protected endpoints); tenant isolation is fail-closed (any ambiguity → 403). See [core-security-error.md §3](core-security-error.md#3-fail-open-vs-fail-closed-summary) |
| Error contract | Audit routing | `GlobalExceptionHandler` routes settlement failures to `AuditService` |
| Audit surfaces | Idempotency | `AccountingEventStore` events are the structured truth that idempotent replay returns |
| Runtime gating | Settings risk | Enforcement services read policies from `system_settings` with key-based scoping |
| Idempotency | Error contract | Payload mismatches throw `CONCURRENCY_CONFLICT` (`ApplicationException`) |
| Idempotency | Reliability | [docs/RELIABILITY.md](../RELIABILITY.md) §1 summarizes idempotency patterns at the platform level |

---

## 6. Recommendations for Future Normalization

These recommendations are **not part of the current documentation scope** but are recorded here so that future work has a clear starting point. No code changes are implied by this section.

1. **Standardize header policy:** Decide whether `X-Idempotency-Key` should be accepted, rejected, or ignored across all endpoints. Current inconsistency (accepted on some, rejected on GRN) makes frontend integration harder.

2. **Require idempotency keys on all write paths:** Sales order creation is the only major write path where the client-supplied key is optional. Payroll derives its key server-side from request fields, so it does not need a client key. Making sales order creation require a client-supplied key would close a replay-safety gap.

3. **Align signature storage timing:** Some modules write hashes at reservation time (packing, orchestrator), others repair-on-read (sales, payroll). Standardizing to reservation-time hashes would close the window where a concurrent replay could be accepted before the hash is persisted.

4. **Rename or restructure `SalesIdempotencyService`:** Currently a pass-through to `SalesCoreEngine`. Either move the idempotency logic into the service or remove the indirection layer.

5. **Document body-field key acceptance consistently:** The set of endpoints that accept an `idempotencyKey` body field in addition to the header is currently discoverable only by reading each controller. A uniform policy would reduce frontend confusion.

6. **Add idempotency key to purchase order creation:** PO creation relies on order-number uniqueness rather than a caller-supplied key, making client-side retry unsafe.

---

## 7. Cross-References

| Document | Relationship |
| --- | --- |
| [core-security-error.md](core-security-error.md) | First slice: security filter chain and exception/error contract |
| [core-audit-runtime-settings.md](core-audit-runtime-settings.md) | Second slice: audit ownership, runtime gating, settings risk |
| [docs/RELIABILITY.md](../RELIABILITY.md) | Platform-level reliability: idempotency patterns, retry, dead-letter |
| [docs/adrs/ADR-003-outbox-pattern-for-cross-module-events.md](../adrs/ADR-003-outbox-pattern-for-cross-module-events.md) | ADR: outbox pattern and idempotency strategy |
| [docs/ARCHITECTURE.md](../ARCHITECTURE.md) | Architecture overview: module map, cross-module boundaries |
| [docs/INDEX.md](../INDEX.md) | Canonical documentation index |
