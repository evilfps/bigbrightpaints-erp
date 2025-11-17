# Complete Batch Traceability - From Raw Materials to Finished Goods

**How Everything Links Together Through Production Batch Codes**

---

## Executive Summary

**YES! Everything is fully traceable through the batch system.**

When you look up a **Production Batch Code** (e.g., "PROD-B-001"), you can see:
- ✅ What raw materials were used (quantities & costs)
- ✅ Which journal entries recorded the accounting
- ✅ How much was produced and packed
- ✅ Which finished good batches were created
- ✅ When products were made and by whom
- ✅ Complete cost breakdown (materials + labor + overhead)

---

## Part 1: The Complete Traceability Chain

### The Master Key: `production_code` (Production Batch ID)

Every entity in the production → packing → inventory flow links back to the **production_code**.

**Example**: Production Batch Code = `"PROD-B-001"`

---

## Part 2: Entity Relationship Map

### 1. Production Log (Master Record)

**Table**: `production_logs`
**Key**: `production_code` (unique per company)

```java
@Table(name = "production_logs")
public class ProductionLog {
    @Id
    private Long id;

    @Column(name = "production_code", nullable = false)
    private String productionCode;  // e.g., "PROD-B-001"

    // Tracks totals
    private BigDecimal mixedQuantity;         // Total produced
    private BigDecimal totalPackedQuantity;   // Total packed
    private BigDecimal wastageQuantity;       // Wastage

    // Cost tracking
    private BigDecimal materialCostTotal;     // Raw material costs
    private BigDecimal laborCostTotal;        // Labor costs
    private BigDecimal overheadCostTotal;     // Overhead costs
    private BigDecimal unitCost;              // Cost per unit

    // Metadata
    private Instant producedAt;
    private String createdBy;
}
```

**What it tracks**:
- Production batch identity
- Quantities (mixed, packed, wasted)
- Complete cost breakdown
- Status (MIXED, PACKED, COMPLETED)

---

### 2. Raw Material Movements (What Was Used)

**Table**: `raw_material_movements`
**Link**: `reference_id = production_code`

```java
@Table(name = "raw_material_movements")
public class RawMaterialMovement {
    @Id
    private Long id;

    @ManyToOne
    private RawMaterial rawMaterial;          // Which material

    @ManyToOne
    private RawMaterialBatch rawMaterialBatch; // Which batch

    @Column(name = "reference_type")
    private String referenceType;              // "PRODUCTION_LOG"

    @Column(name = "reference_id")
    private String referenceId;                // "PROD-B-001" ← LINK!

    private String movementType;               // "ISSUE"
    private BigDecimal quantity;               // How much used
    private BigDecimal unitCost;               // Cost per unit

    @Column(name = "journal_entry_id")
    private Long journalEntryId;               // Links to accounting
}
```

**What it tracks**:
- Which raw materials were used
- How much of each material
- Cost of each material
- Which batches they came from (FIFO tracking)
- Link to accounting journal

---

### 3. Journal Entry (Accounting Record)

**Table**: `journal_entries`
**Link**: `reference_number = "{production_code}-RM"`

```java
@Table(name = "journal_entries")
public class JournalEntry {
    @Id
    private Long id;

    @Column(name = "reference_number")
    private String referenceNumber;            // "PROD-B-001-RM" ← LINK!

    private LocalDate entryDate;
    private String memo;
    private String status;                     // "POSTED"

    @OneToMany(mappedBy = "journalEntry")
    private List<JournalLine> lines;           // Dr WIP / Cr Materials

    private Instant postedAt;
    private String postedBy;
}
```

**What it records**:
- Material consumption accounting
- Dr: Work In Progress
- Cr: Raw Material Inventory
- Links back to movements via `journal_entry_id`

---

### 4. Packing Records (How Products Were Packed)

**Table**: `packing_records`
**Link**: Foreign key `production_log_id`

```java
@Table(name = "packing_records")
public class PackingRecord {
    @Id
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "production_log_id")
    private ProductionLog productionLog;       // ← DIRECT LINK!

    @ManyToOne
    private FinishedGood finishedGood;         // Which product

    @ManyToOne
    private FinishedGoodBatch finishedGoodBatch; // Which batch created

    private String packagingSize;              // "1L", "5L", "20L"
    private BigDecimal quantityPacked;         // How much packed
    private Integer piecesCount;               // Number of pieces
    private Integer boxesCount;                // Number of boxes

    private LocalDate packedDate;
    private String packedBy;
}
```

**What it tracks**:
- What finished products were created
- How they were packaged (sizes)
- Which finished good batches were created
- Quantity packed per size

---

### 5. Finished Good Batches (Final Products)

**Table**: `finished_good_batches`
**Link**: `batch_code` contains production reference

```java
@Table(name = "finished_good_batches")
public class FinishedGoodBatch {
    @Id
    private Long id;

    @ManyToOne
    private FinishedGood finishedGood;         // Which product

    @Column(name = "batch_code")
    private String batchCode;                  // "PROD-B-001-1L" ← LINK!

    private BigDecimal quantityTotal;          // Total produced
    private BigDecimal quantityAvailable;      // Available to sell
    private BigDecimal unitCost;               // Cost per unit

    private Instant manufacturedAt;
    private LocalDate expiryDate;
}
```

**What it tracks**:
- Final product batches
- How much is available to sell
- Unit cost (from production log)
- Expiry dates

---

## Part 3: Complete Traceability Flow

### Step-by-Step: Production → Packing → Finished Goods

```
┌─────────────────────────────────────────────────────────────┐
│         1. PRODUCTION LOG CREATED                           │
│         production_code: "PROD-B-001"                       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         2. RAW MATERIALS ISSUED                             │
│                                                              │
│  Movement #5001:                                            │
│  ├─ raw_material: Paint Base (ID: 55)                      │
│  ├─ reference_type: PRODUCTION_LOG                         │
│  ├─ reference_id: "PROD-B-001" ← LINK TO PRODUCTION       │
│  ├─ quantity: 10.00 liters                                 │
│  ├─ unit_cost: $150.00                                     │
│  └─ journal_entry_id: 3001 (set after journal posted)     │
│                                                              │
│  Movement #5002:                                            │
│  ├─ raw_material: Hardener (ID: 56)                       │
│  ├─ reference_id: "PROD-B-001" ← LINK TO PRODUCTION       │
│  ├─ quantity: 2.00 liters                                  │
│  ├─ unit_cost: $50.00                                      │
│  └─ journal_entry_id: 3001                                 │
│                                                              │
│  TOTAL MATERIAL COST: $1,600                               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         3. JOURNAL ENTRY POSTED                             │
│         journal_entry_id: 3001                              │
│                                                              │
│  Journal Entry #3001:                                       │
│  ├─ reference_number: "PROD-B-001-RM" ← LINK TO PRODUCTION│
│  ├─ entry_date: 2025-01-17                                 │
│  ├─ status: POSTED                                         │
│  └─ lines:                                                  │
│      ├─ Dr: Work In Progress (4001) - $1,600              │
│      ├─ Cr: Paint Base Inventory (3001) - $1,500          │
│      └─ Cr: Hardener Inventory (3002) - $100              │
│                                                              │
│  Movements #5001 & #5002 updated:                          │
│  └─ journal_entry_id = 3001 ← REVERSE LINK                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         4. PRODUCTION LOG UPDATED                           │
│         production_code: "PROD-B-001"                       │
│                                                              │
│  ProductionLog updated:                                     │
│  ├─ material_cost_total: $1,600                            │
│  ├─ mixed_quantity: 50.00 liters                           │
│  └─ status: MIXED                                           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         5. PACKING RECORDS CREATED                          │
│                                                              │
│  PackingRecord #7001:                                       │
│  ├─ production_log_id: (points to PROD-B-001) ← FK LINK   │
│  ├─ finished_good: Premium Paint Red (ID: 100)            │
│  ├─ packaging_size: "1L"                                   │
│  ├─ quantity_packed: 30.00 liters                          │
│  ├─ pieces_count: 30 cans                                  │
│  └─ finished_good_batch_id: 8001                           │
│                                                              │
│  PackingRecord #7002:                                       │
│  ├─ production_log_id: (points to PROD-B-001) ← FK LINK   │
│  ├─ finished_good: Premium Paint Red (ID: 100)            │
│  ├─ packaging_size: "5L"                                   │
│  ├─ quantity_packed: 20.00 liters                          │
│  ├─ pieces_count: 4 cans                                   │
│  └─ finished_good_batch_id: 8002                           │
│                                                              │
│  TOTAL PACKED: 50.00 liters                                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         6. FINISHED GOOD BATCHES CREATED                    │
│                                                              │
│  FinishedGoodBatch #8001:                                   │
│  ├─ batch_code: "PROD-B-001-1L" ← CONTAINS PRODUCTION REF │
│  ├─ finished_good: Premium Paint Red                       │
│  ├─ quantity_total: 30.00 liters (30 × 1L cans)          │
│  ├─ quantity_available: 30.00                              │
│  ├─ unit_cost: $32.00 per liter                           │
│  └─ manufactured_at: 2025-01-17T10:30:00Z                  │
│                                                              │
│  FinishedGoodBatch #8002:                                   │
│  ├─ batch_code: "PROD-B-001-5L" ← CONTAINS PRODUCTION REF │
│  ├─ finished_good: Premium Paint Red                       │
│  ├─ quantity_total: 20.00 liters (4 × 5L cans)           │
│  ├─ quantity_available: 20.00                              │
│  ├─ unit_cost: $32.00 per liter                           │
│  └─ manufactured_at: 2025-01-17T10:30:00Z                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         7. PRODUCTION LOG FINALIZED                         │
│         production_code: "PROD-B-001"                       │
│                                                              │
│  ProductionLog final state:                                 │
│  ├─ mixed_quantity: 50.00 liters                           │
│  ├─ total_packed_quantity: 50.00 liters                    │
│  ├─ wastage_quantity: 0.00                                 │
│  ├─ material_cost_total: $1,600                            │
│  ├─ labor_cost_total: $0 (to be allocated later)          │
│  ├─ overhead_cost_total: $0 (to be allocated later)       │
│  ├─ unit_cost: $32.00 per liter                            │
│  └─ status: PACKED                                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Part 4: Traceability Query Examples

### Query 1: "Show me everything for production batch PROD-B-001"

```sql
-- Master production record
SELECT * FROM production_logs
WHERE production_code = 'PROD-B-001';

-- What materials were used
SELECT
    m.id,
    rm.name AS material_name,
    m.quantity,
    m.unit_cost,
    m.quantity * m.unit_cost AS total_cost,
    m.journal_entry_id
FROM raw_material_movements m
JOIN raw_materials rm ON rm.id = m.raw_material_id
WHERE m.reference_type = 'PRODUCTION_LOG'
  AND m.reference_id = 'PROD-B-001';

-- Accounting journal entry
SELECT j.*, jl.account_id, jl.debit, jl.credit, a.name
FROM journal_entries j
JOIN journal_lines jl ON jl.journal_entry_id = j.id
JOIN accounts a ON a.id = jl.account_id
WHERE j.reference_number = 'PROD-B-001-RM';

-- Packing records
SELECT
    pr.id,
    fg.name AS product_name,
    pr.packaging_size,
    pr.quantity_packed,
    pr.pieces_count,
    fgb.batch_code AS finished_batch_code
FROM packing_records pr
JOIN production_logs pl ON pl.id = pr.production_log_id
JOIN finished_goods fg ON fg.id = pr.finished_good_id
LEFT JOIN finished_good_batches fgb ON fgb.id = pr.finished_good_batch_id
WHERE pl.production_code = 'PROD-B-001';

-- Finished good batches created
SELECT *
FROM finished_good_batches
WHERE batch_code LIKE 'PROD-B-001%';
```

**Result Summary**:
```
Production Batch: PROD-B-001
├─ Materials Used:
│  ├─ Paint Base: 10L × $150 = $1,500
│  └─ Hardener: 2L × $50 = $100
├─ Total Material Cost: $1,600
├─ Journal Entry: #3001 (PROD-B-001-RM)
├─ Quantity Produced: 50L
├─ Packing:
│  ├─ 30L in 1L cans (30 pieces) → Batch PROD-B-001-1L
│  └─ 20L in 5L cans (4 pieces) → Batch PROD-B-001-5L
└─ Unit Cost: $32.00/L
```

---

### Query 2: "Show me which batch a finished good came from"

```sql
-- Given finished good batch code
SELECT
    fgb.batch_code AS finished_batch,
    fg.name AS product_name,
    fgb.quantity_available,
    fgb.unit_cost,
    fgb.manufactured_at,
    -- Trace back to production log
    pr.id AS packing_record_id,
    pl.production_code,
    pl.material_cost_total,
    pl.produced_at,
    pl.created_by
FROM finished_good_batches fgb
JOIN finished_goods fg ON fg.id = fgb.finished_good_id
LEFT JOIN packing_records pr ON pr.finished_good_batch_id = fgb.id
LEFT JOIN production_logs pl ON pl.id = pr.production_log_id
WHERE fgb.batch_code = 'PROD-B-001-1L';
```

**Result**:
```
Finished Good Batch: PROD-B-001-1L
├─ Product: Premium Paint Red
├─ Quantity Available: 30L (30 cans)
├─ Unit Cost: $32.00/L
├─ Manufactured: 2025-01-17 10:30 AM
└─ Source Production Batch: PROD-B-001
   ├─ Material Cost: $1,600
   ├─ Produced: 2025-01-17
   └─ Created By: john.smith
```

---

### Query 3: "Show me raw materials used for a specific finished good batch"

```sql
-- Trace finished batch → production log → raw materials
SELECT
    fgb.batch_code AS finished_batch,
    pl.production_code,
    rm.name AS raw_material_name,
    m.quantity AS quantity_used,
    m.unit_cost,
    m.quantity * m.unit_cost AS cost,
    j.reference_number AS journal_ref
FROM finished_good_batches fgb
JOIN packing_records pr ON pr.finished_good_batch_id = fgb.id
JOIN production_logs pl ON pl.id = pr.production_log_id
JOIN raw_material_movements m ON m.reference_id = pl.production_code
JOIN raw_materials rm ON rm.id = m.raw_material_id
LEFT JOIN journal_entries j ON j.id = m.journal_entry_id
WHERE fgb.batch_code = 'PROD-B-001-5L'
  AND m.reference_type = 'PRODUCTION_LOG';
```

**Result**:
```
Finished Batch: PROD-B-001-5L
└─ Raw Materials Used:
   ├─ Paint Base: 10L × $150 = $1,500
   └─ Hardener: 2L × $50 = $100
   Total: $1,600
   Journal: PROD-B-001-RM
```

---

## Part 5: Frontend API Endpoints

### Endpoint 1: Get Complete Production Batch Details

```typescript
GET /api/production/logs/{productionCode}/trace

Example: GET /api/production/logs/PROD-B-001/trace

Response:
{
    "productionCode": "PROD-B-001",
    "product": "Premium Paint Red",
    "status": "PACKED",
    "producedAt": "2025-01-17T10:30:00Z",
    "createdBy": "john.smith",

    "quantities": {
        "mixed": 50.00,
        "packed": 50.00,
        "wastage": 0.00,
        "unit": "liters"
    },

    "costs": {
        "materials": 1600.00,
        "labor": 0.00,
        "overhead": 0.00,
        "total": 1600.00,
        "unitCost": 32.00
    },

    "materialsUsed": [
        {
            "id": 5001,
            "materialName": "Paint Base",
            "quantity": 10.00,
            "unitCost": 150.00,
            "totalCost": 1500.00,
            "journalEntryId": 3001
        },
        {
            "id": 5002,
            "materialName": "Hardener",
            "quantity": 2.00,
            "unitCost": 50.00,
            "totalCost": 100.00,
            "journalEntryId": 3001
        }
    ],

    "journalEntry": {
        "id": 3001,
        "referenceNumber": "PROD-B-001-RM",
        "entryDate": "2025-01-17",
        "status": "POSTED",
        "totalDebit": 1600.00,
        "totalCredit": 1600.00
    },

    "packingRecords": [
        {
            "id": 7001,
            "packagingSize": "1L",
            "quantityPacked": 30.00,
            "piecesCount": 30,
            "finishedBatchCode": "PROD-B-001-1L",
            "finishedBatchId": 8001
        },
        {
            "id": 7002,
            "packagingSize": "5L",
            "quantityPacked": 20.00,
            "piecesCount": 4,
            "finishedBatchCode": "PROD-B-001-5L",
            "finishedBatchId": 8002
        }
    ],

    "finishedBatches": [
        {
            "id": 8001,
            "batchCode": "PROD-B-001-1L",
            "quantityTotal": 30.00,
            "quantityAvailable": 30.00,
            "unitCost": 32.00
        },
        {
            "id": 8002,
            "batchCode": "PROD-B-001-5L",
            "quantityTotal": 20.00,
            "quantityAvailable": 20.00,
            "unitCost": 32.00
        }
    ]
}
```

---

### Endpoint 2: Trace Finished Good Batch Back to Source

```typescript
GET /api/inventory/batches/{batchCode}/trace

Example: GET /api/inventory/batches/PROD-B-001-1L/trace

Response:
{
    "finishedBatchCode": "PROD-B-001-1L",
    "productName": "Premium Paint Red",
    "quantityAvailable": 30.00,
    "unitCost": 32.00,
    "manufacturedAt": "2025-01-17T10:30:00Z",

    "sourceProduction": {
        "productionCode": "PROD-B-001",
        "mixedQuantity": 50.00,
        "producedAt": "2025-01-17T10:30:00Z",
        "createdBy": "john.smith"
    },

    "rawMaterialsUsed": [
        {
            "materialName": "Paint Base",
            "quantity": 10.00,
            "unitCost": 150.00,
            "totalCost": 1500.00
        },
        {
            "materialName": "Hardener",
            "quantity": 2.00,
            "unitCost": 50.00,
            "totalCost": 100.00
        }
    ],

    "costBreakdown": {
        "materials": 1600.00,
        "labor": 0.00,
        "overhead": 0.00,
        "total": 1600.00,
        "costPerLiter": 32.00
    },

    "accountingReference": "PROD-B-001-RM"
}
```

---

## Part 6: Complete Traceability Summary

### What You Can Trace:

#### Starting from Production Code (PROD-B-001):
1. ✅ **Raw Materials Used**
   - Which materials (Paint Base, Hardener)
   - Quantities (10L, 2L)
   - Costs ($150/L, $50/L)
   - Source batches (FIFO tracking)

2. ✅ **Accounting Journal**
   - Journal ID (3001)
   - Reference (PROD-B-001-RM)
   - All debit/credit lines
   - Posted date and by whom

3. ✅ **Production Details**
   - Quantity mixed (50L)
   - Quantity packed (50L)
   - Wastage (0L)
   - Unit cost ($32/L)

4. ✅ **Packing Information**
   - Package sizes (1L, 5L)
   - Quantities per size (30L, 20L)
   - Number of pieces (30 cans, 4 cans)

5. ✅ **Finished Good Batches**
   - Batch codes (PROD-B-001-1L, PROD-B-001-5L)
   - Quantities available
   - Current inventory levels

#### Starting from Finished Batch Code (PROD-B-001-1L):
1. ✅ **Trace back to production** (PROD-B-001)
2. ✅ **Find all raw materials used**
3. ✅ **See complete cost breakdown**
4. ✅ **View accounting journal**
5. ✅ **Check who made it and when**

---

## Part 7: Visual Relationship Diagram

```
                    PRODUCTION_CODE: "PROD-B-001"
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌──────────────┐    ┌──────────────────┐   ┌──────────────┐
│ PRODUCTION   │    │   RAW MATERIAL   │   │   JOURNAL    │
│     LOG      │    │    MOVEMENTS     │   │    ENTRY     │
│              │    │                  │   │              │
│ production_  │◄───┤ reference_id:    │───┤ reference:   │
│ code         │    │ "PROD-B-001"     │   │ "PROD-B-001  │
│              │    │                  │   │      -RM"    │
│ material_    │    │ journal_entry_   │◄──┤              │
│ cost: $1600  │    │ id: 3001         │   │ id: 3001     │
└──────┬───────┘    └──────────────────┘   └──────────────┘
       │
       │ FK: production_log_id
       │
       ▼
┌──────────────────┐
│   PACKING        │
│   RECORDS        │
│                  │
│ production_log_  │
│ id: (FK)         │
│                  │
│ finished_good_   │
│ batch_id: 8001   │───┐
└──────────────────┘   │
                       │ FK
                       ▼
              ┌──────────────────┐
              │  FINISHED GOOD   │
              │    BATCHES       │
              │                  │
              │ batch_code:      │
              │ "PROD-B-001-1L"  │
              │                  │
              │ quantity: 30L    │
              │ unit_cost: $32   │
              └──────────────────┘
```

---

## Summary: YES, Full Traceability! 🎯

### When you lookup production batch "PROD-B-001", you get:

✅ **Raw Materials**: Paint Base 10L + Hardener 2L = $1,600
✅ **Journal Entry**: #3001 (PROD-B-001-RM) → Dr WIP / Cr Inventory
✅ **Production**: Mixed 50L, Packed 50L, Waste 0L
✅ **Finished Batches**:
   - PROD-B-001-1L: 30 cans × 1L = 30L
   - PROD-B-001-5L: 4 cans × 5L = 20L
✅ **Cost**: $32/L ($1,600 ÷ 50L)
✅ **Who & When**: john.smith @ 2025-01-17 10:30 AM

### When you lookup finished batch "PROD-B-001-1L", you get:
✅ Traces back to production PROD-B-001
✅ Shows all raw materials used
✅ Shows journal entry for accounting
✅ Shows complete cost breakdown

**Everything is connected. Everything is traceable. Perfect audit trail!** ✨

