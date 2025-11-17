# Journal Entry Traceability - Complete Guide

**How Journal Entries are Linked to Business Transactions**

---

## Quick Answer

**YES**, journal entries are linked to their source transactions for **full traceability**. You can always trace back:
- Which sales order created this revenue journal?
- Which purchase created this payable journal?
- Which production batch created this material consumption journal?

---

## Part 1: Direct Links (Entity → Journal Entry)

### 1. Purchase Orders → Journal Entry

**Table**: `raw_material_purchases`

**Link**:
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "journal_entry_id")
private JournalEntry journalEntry;
```

**Database Column**: `journal_entry_id` (nullable)

**When Set**: When purchase is created and accounting journal is posted

**Example**:
```sql
SELECT
    p.id,
    p.invoice_number,
    p.total_amount,
    p.journal_entry_id,
    j.reference_number,
    j.status
FROM raw_material_purchases p
LEFT JOIN journal_entries j ON j.id = p.journal_entry_id
WHERE p.id = 123;

Result:
id  | invoice_number | total_amount | journal_entry_id | reference_number | status
123 | INV-2025-001   | 5000.00      | 2001             | RMP-SUP99-001    | POSTED
```

**Traceability**:
```
Purchase #123
├─ Invoice: INV-2025-001
├─ Supplier: XYZ Corp
├─ Amount: $5,000
└─ Journal Entry: #2001
    ├─ Reference: RMP-SUP99-001
    ├─ Dr: Raw Material Inventory $5,000
    └─ Cr: Accounts Payable $5,000
```

---

### 2. Sales Orders → Journal Entry (Indirect via Invoice)

**Note**: Sales orders don't directly store journal_entry_id in the entity, but they're linked via:
1. **Invoice** → Has journal entry
2. **Order reference number** → Used as journal reference

**Traceability via Reference Number**:
```java
// Journal reference format for sales: "SO-{orderNumber}"
String reference = "SO-" + salesOrder.getOrderNumber();

// Find journal by reference
JournalEntry journal = journalRepository
    .findByCompanyAndReferenceNumber(company, reference);
```

**Example**:
```sql
-- Find journal for sales order SO-2025-001
SELECT
    j.id,
    j.reference_number,
    j.entry_date,
    j.memo,
    j.dealer_id
FROM journal_entries j
WHERE j.reference_number = 'SO-2025-001';

Result:
id   | reference_number | entry_date  | memo                    | dealer_id
1001 | SO-2025-001      | 2025-01-17  | Sales order SO-2025-001 | 123
```

**Traceability**:
```
Sales Order #789
├─ Order Number: SO-2025-001
├─ Customer: ABC Corp
├─ Amount: $10,000
└─ Journal Entry: Found via reference "SO-2025-001"
    ├─ Dr: Accounts Receivable $10,000
    ├─ Cr: Sales Revenue $8,475
    └─ Cr: GST Payable $1,525
```

---

## Part 2: Indirect Links (Movement → Journal Entry)

### 3. Raw Material Movements → Journal Entry

**Table**: `raw_material_movements`

**Link**:
```java
@Column(name = "journal_entry_id")
private Long journalEntryId;  // Not a foreign key, just ID
```

**Database Column**: `journal_entry_id` (nullable, set after journal is created)

**When Set**: After material consumption journal is posted

**How It Works**:
```java
// Step 1: Issue materials (creates movements)
issueFromBatches(rawMaterial, quantity, "PROD-B-001");
// Creates RawMaterialMovement records with:
// - referenceType: "PRODUCTION_LOG"
// - referenceId: "PROD-B-001"
// - journalEntryId: NULL (not set yet)

// Step 2: Post journal
JournalEntryDto entry = accountingFacade.postMaterialConsumption(...);
// Returns journal ID: 3001

// Step 3: Link movements to journal
linkRawMaterialMovementsToJournal("PROD-B-001", 3001);
// Updates all movements where referenceId = "PROD-B-001"
// Sets journalEntryId = 3001
```

**Query Example**:
```sql
-- Find all movements for production batch and their journal
SELECT
    m.id,
    m.reference_id,
    m.raw_material_id,
    m.quantity,
    m.unit_cost,
    m.journal_entry_id,
    j.reference_number
FROM raw_material_movements m
LEFT JOIN journal_entries j ON j.id = m.journal_entry_id
WHERE m.reference_type = 'PRODUCTION_LOG'
  AND m.reference_id = 'PROD-B-001';

Result:
id   | reference_id | raw_material_id | quantity | unit_cost | journal_entry_id | reference_number
5001 | PROD-B-001   | 55              | 10.00    | 150.00    | 3001             | PROD-B-001-RM
5002 | PROD-B-001   | 56              | 2.00     | 50.00     | 3001             | PROD-B-001-RM
```

**Traceability**:
```
Production Batch: PROD-B-001
├─ Movement #5001: Paint Base (10L @ $150/L) = $1,500
├─ Movement #5002: Hardener (2L @ $50/L) = $100
├─ Total Cost: $1,600
└─ Journal Entry #3001
    ├─ Reference: PROD-B-001-RM
    ├─ Dr: Work In Progress $1,600
    ├─ Cr: Paint Base Inventory $1,500
    └─ Cr: Hardener Inventory $100
```

---

## Part 3: Complete Traceability Examples

### Example 1: Purchase Order Trace

**Question**: "Where did this journal entry come from?"

**Given**: Journal Entry #2001

**Query**:
```sql
-- Trace journal back to purchase
SELECT
    j.id AS journal_id,
    j.reference_number,
    j.entry_date,
    j.memo,
    p.id AS purchase_id,
    p.invoice_number,
    p.total_amount,
    s.name AS supplier_name
FROM journal_entries j
LEFT JOIN raw_material_purchases p ON p.journal_entry_id = j.id
LEFT JOIN suppliers s ON s.id = p.supplier_id
WHERE j.id = 2001;

Result:
journal_id | reference_number | entry_date  | memo           | purchase_id | invoice_number | total_amount | supplier_name
2001       | RMP-SUP99-001    | 2025-01-17  | Purchase INV.. | 123         | INV-2025-001   | 5000.00      | XYZ Corp
```

**Answer**: This journal was created by Purchase Order #123 for supplier XYZ Corp

---

### Example 2: Production Batch Trace

**Question**: "What materials were used in this production batch?"

**Given**: Production Code "PROD-B-001"

**Query**:
```sql
-- Trace production to movements to journal
SELECT
    m.id AS movement_id,
    rm.name AS material_name,
    m.quantity,
    m.unit_cost,
    m.quantity * m.unit_cost AS line_cost,
    j.id AS journal_id,
    j.reference_number,
    jl.debit,
    jl.credit,
    a.code AS account_code,
    a.name AS account_name
FROM raw_material_movements m
JOIN raw_materials rm ON rm.id = m.raw_material_id
LEFT JOIN journal_entries j ON j.id = m.journal_entry_id
LEFT JOIN journal_lines jl ON jl.journal_entry_id = j.id
LEFT JOIN accounts a ON a.id = jl.account_id
WHERE m.reference_type = 'PRODUCTION_LOG'
  AND m.reference_id = 'PROD-B-001'
ORDER BY m.id, jl.id;

Result:
movement_id | material_name | quantity | unit_cost | line_cost | journal_id | reference_number | debit   | credit  | account_code | account_name
5001        | Paint Base    | 10.00    | 150.00    | 1500.00   | 3001       | PROD-B-001-RM    | 1600.00 | 0.00    | WIP          | Work In Progress
5001        | Paint Base    | 10.00    | 150.00    | 1500.00   | 3001       | PROD-B-001-RM    | 0.00    | 1500.00 | RM-PAINT     | Paint Base Inventory
5002        | Hardener      | 2.00     | 50.00     | 100.00    | 3001       | PROD-B-001-RM    | 0.00    | 100.00  | RM-HARD      | Hardener Inventory
```

**Answer**:
- Used $1,500 of Paint Base + $100 of Hardener = $1,600 total
- Journal #3001 recorded: Dr WIP $1,600 / Cr Inventory $1,600

---

### Example 3: Sales Order to Revenue & COGS Trace

**Question**: "Show me all accounting for sales order SO-2025-001"

**Query**:
```sql
-- Find all journals related to sales order
SELECT
    j.id AS journal_id,
    j.reference_number,
    j.entry_date,
    j.memo,
    j.status,
    jl.account_id,
    a.code AS account_code,
    a.name AS account_name,
    jl.debit,
    jl.credit
FROM journal_entries j
JOIN journal_lines jl ON jl.journal_entry_id = j.id
JOIN accounts a ON a.id = jl.account_id
WHERE j.reference_number LIKE 'SO-2025-001%'
   OR j.reference_number LIKE 'COGS-SO-2025-001%'
ORDER BY j.id, jl.id;

Result:
journal_id | reference_number    | entry_date  | memo              | account_code | account_name         | debit    | credit
1001       | SO-2025-001         | 2025-01-17  | Sales order...    | AR           | Accounts Receivable  | 10000.00 | 0.00
1001       | SO-2025-001         | 2025-01-17  | Sales order...    | REV          | Sales Revenue        | 0.00     | 8475.00
1001       | SO-2025-001         | 2025-01-17  | Sales order...    | GST          | GST Payable          | 0.00     | 1525.00
1002       | COGS-SO-2025-001    | 2025-01-18  | COGS for order... | COGS         | Cost of Goods Sold   | 3500.00  | 0.00
1002       | COGS-SO-2025-001    | 2025-18-17  | COGS for order... | FG-INV       | Finished Goods Inv   | 0.00     | 3500.00
```

**Answer**:
- Revenue journal #1001: Recorded $10,000 sale
- COGS journal #1002: Recorded $3,500 cost
- Profit on sale: $10,000 - $3,500 = $6,500

---

## Part 4: Traceability Summary Table

| Business Entity | Link Type | Link Field | Trace Method |
|----------------|-----------|------------|--------------|
| **Purchase Order** | Direct | `journal_entry_id` | Purchase → Journal (FK) |
| **Sales Order** | Indirect | `reference_number` | Journal reference = "SO-{orderNumber}" |
| **Production Batch** | Indirect | Multiple movements | Movements → Journal via `journal_entry_id` |
| **Raw Material Movement** | Direct | `journal_entry_id` | Movement → Journal (ID only) |
| **Payroll Run** | Direct | `journal_entry_id` | PayrollRun → Journal (FK) |
| **Invoice** | Direct | `journal_entry_id` | Invoice → Journal (FK) |
| **Inventory Adjustment** | Indirect | `reference_number` | Journal reference = adjustment code |

---

## Part 5: Frontend Traceability Queries

### API Endpoint Ideas for Frontend

#### 1. Get Journal by Business Transaction
```typescript
GET /api/accounting/journals/by-reference/{referenceType}/{referenceId}

Examples:
GET /api/accounting/journals/by-reference/SALES_ORDER/SO-2025-001
GET /api/accounting/journals/by-reference/PURCHASE/123
GET /api/accounting/journals/by-reference/PRODUCTION/PROD-B-001

Response:
{
    "journals": [
        {
            "id": 1001,
            "referenceNumber": "SO-2025-001",
            "entryDate": "2025-01-17",
            "type": "SALES_REVENUE",
            "totalDebit": 10000.00,
            "totalCredit": 10000.00,
            "status": "POSTED"
        },
        {
            "id": 1002,
            "referenceNumber": "COGS-SO-2025-001",
            "entryDate": "2025-01-18",
            "type": "COGS",
            "totalDebit": 3500.00,
            "totalCredit": 3500.00,
            "status": "POSTED"
        }
    ]
}
```

#### 2. Get Transaction by Journal
```typescript
GET /api/accounting/journals/{journalId}/source

Example:
GET /api/accounting/journals/2001/source

Response:
{
    "journalId": 2001,
    "referenceNumber": "RMP-SUP99-001",
    "sourceType": "PURCHASE_ORDER",
    "sourceEntity": {
        "id": 123,
        "type": "raw_material_purchase",
        "invoiceNumber": "INV-2025-001",
        "supplierName": "XYZ Corp",
        "totalAmount": 5000.00,
        "url": "/purchasing/purchases/123"
    }
}
```

#### 3. Get Material Movements with Journal
```typescript
GET /api/production/logs/{productionCode}/movements

Example:
GET /api/production/logs/PROD-B-001/movements

Response:
{
    "productionCode": "PROD-B-001",
    "movements": [
        {
            "id": 5001,
            "materialName": "Paint Base",
            "quantity": 10.00,
            "unitCost": 150.00,
            "lineCost": 1500.00,
            "journalEntryId": 3001,
            "journalReference": "PROD-B-001-RM"
        },
        {
            "id": 5002,
            "materialName": "Hardener",
            "quantity": 2.00,
            "unitCost": 50.00,
            "lineCost": 100.00,
            "journalEntryId": 3001,
            "journalReference": "PROD-B-001-RM"
        }
    ],
    "totalCost": 1600.00,
    "journalEntry": {
        "id": 3001,
        "referenceNumber": "PROD-B-001-RM",
        "status": "POSTED"
    }
}
```

---

## Part 6: Audit Trail Benefits

### Why Traceability Matters

#### 1. **Compliance & Auditing**
- External auditors can trace every journal entry back to source
- "Show me the invoice for this purchase journal" ✅
- "Prove this revenue came from an actual sale" ✅

#### 2. **Error Investigation**
- "Why is this cost wrong?" → Trace to production batch → Check material quantities
- "Who created this journal?" → Check created_by field + source transaction
- "When was this posted?" → Check posted_at timestamp

#### 3. **Financial Reporting**
- Month-end reports: "Show all sales journals for January"
- Cost analysis: "What materials were used in Q1 production?"
- Profit analysis: "Match revenue journals to COGS journals by order"

#### 4. **Business Intelligence**
- "Which products are most profitable?" → Link sales revenue to COGS
- "Which suppliers cost the most?" → Link purchase journals to suppliers
- "Production efficiency?" → Link material cost to output quantity

---

## Part 7: Visual Traceability Flow

### Complete Purchase → Production → Sale Flow

```
1. PURCHASE RAW MATERIALS
   │
   ├─ RawMaterialPurchase #123
   │  ├─ invoice_number: INV-2025-001
   │  ├─ journal_entry_id: 2001 ← DIRECT LINK
   │  └─ total_amount: $5,000
   │
   └─ JournalEntry #2001
      ├─ reference_number: RMP-SUP99-001
      ├─ Dr: Raw Material Inventory $5,000
      └─ Cr: Accounts Payable $5,000

2. USE IN PRODUCTION
   │
   ├─ ProductionLog (production_code: PROD-B-001)
   │  └─ Issues materials:
   │     ├─ Movement #5001: Paint Base 10L
   │     │  ├─ reference_id: PROD-B-001
   │     │  └─ journal_entry_id: 3001 ← SET AFTER JOURNAL
   │     └─ Movement #5002: Hardener 2L
   │        ├─ reference_id: PROD-B-001
   │        └─ journal_entry_id: 3001 ← SET AFTER JOURNAL
   │
   └─ JournalEntry #3001
      ├─ reference_number: PROD-B-001-RM
      ├─ Dr: Work In Progress $1,600
      └─ Cr: Raw Material Inventory $1,600

3. SELL FINISHED PRODUCTS
   │
   ├─ SalesOrder #789
   │  ├─ order_number: SO-2025-001
   │  └─ Links via reference_number (not FK)
   │
   ├─ JournalEntry #1001 (Revenue)
   │  ├─ reference_number: SO-2025-001 ← MATCHES ORDER
   │  ├─ Dr: Accounts Receivable $10,000
   │  └─ Cr: Sales Revenue $10,000
   │
   └─ JournalEntry #1002 (COGS)
      ├─ reference_number: COGS-SO-2025-001 ← MATCHES ORDER
      ├─ Dr: Cost of Goods Sold $3,500
      └─ Cr: Finished Goods Inventory $3,500

FULL TRACE:
Purchase #123 → Journal #2001 → Materials in inventory
                                 ↓
Production PROD-B-001 → Movements #5001/#5002 → Journal #3001 → WIP
                                                                   ↓
Sales Order #789 → Journal #1001 (Revenue) + Journal #1002 (COGS)
```

---

## Summary: Yes, Everything is Traceable!

### Three Types of Links:

1. **Direct Foreign Key** (Purchases, Payroll)
   - Entity has `journal_entry_id` column
   - Direct database relationship
   - Example: `raw_material_purchases.journal_entry_id → journal_entries.id`

2. **Reference Number Match** (Sales, Inventory Adjustments)
   - Journal reference matches business entity code
   - Query by reference_number pattern
   - Example: Journal reference "SO-2025-001" → Sales Order "SO-2025-001"

3. **Movement Linking** (Production)
   - Movements track materials used
   - Movements linked to journal after posting
   - Example: RawMaterialMovement.journalEntryId → journal_entries.id

### Key Points:

✅ **Every journal can be traced to its source transaction**
✅ **Every transaction can find its accounting journals**
✅ **Full audit trail from business event → accounting entry**
✅ **Multiple journals per transaction supported** (revenue + COGS for sales)
✅ **Timestamps and user tracking on all entities**

**You have complete end-to-end traceability from business operations to financial reporting!**

---

*Need to implement any of these traceability queries in your frontend? Use the API endpoint patterns shown above!*
