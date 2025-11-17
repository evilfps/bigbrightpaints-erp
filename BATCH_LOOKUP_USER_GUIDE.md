# Batch Lookup User Guide - What You See When You Search

**For Business Users: Understanding Your Complete Production History**

---

## What This Guide Shows You

When you look up a production batch ID in your system, this guide explains **exactly what information you'll see** and **how everything connects**.

---

## Example Scenario

**You search for**: Production Batch `PROD-B-001`

**Date Made**: January 17, 2025

**Product**: Premium Paint Red

---

## What You'll See in the System

### 1. Production Summary (Main Card)

```
┌─────────────────────────────────────────────────┐
│  Production Batch: PROD-B-001                   │
│  Status: ✅ COMPLETED & PACKED                  │
├─────────────────────────────────────────────────┤
│  Product: Premium Paint Red                     │
│  Produced Date: January 17, 2025                │
│  Produced By: john.smith                        │
│                                                  │
│  Quantities:                                    │
│  • Mixed: 50.00 liters                         │
│  • Packed: 50.00 liters                        │
│  • Wastage: 0.00 liters                        │
│  • Efficiency: 100%                             │
└─────────────────────────────────────────────────┘
```

---

### 2. Raw Materials Used (What Went Into Making It)

```
┌─────────────────────────────────────────────────┐
│  Raw Materials Consumed                         │
├─────────────────────────────────────────────────┤
│  Paint Base                                     │
│  • Quantity: 10.00 liters                      │
│  • Cost per liter: $150.00                     │
│  • Total cost: $1,500.00                       │
│  • Batch source: BATCH-PB-2025-001             │
│                                                  │
│  Hardener                                       │
│  • Quantity: 2.00 liters                       │
│  • Cost per liter: $50.00                      │
│  • Total cost: $100.00                         │
│  • Batch source: BATCH-HD-2025-003             │
│                                                  │
│  TOTAL MATERIAL COST: $1,600.00                │
└─────────────────────────────────────────────────┘
```

**What This Tells You**:
- Exactly which materials were used
- How much of each material
- What it cost (for costing/pricing decisions)
- Which supplier batches they came from (for quality tracking)

---

### 3. Cost Breakdown (Complete Production Costs)

```
┌─────────────────────────────────────────────────┐
│  Production Costs                               │
├─────────────────────────────────────────────────┤
│  Materials:     $1,600.00                       │
│  Labor:         $   0.00  (Not allocated yet)   │
│  Overhead:      $   0.00  (Not allocated yet)   │
│  ────────────────────────────                   │
│  TOTAL COST:    $1,600.00                       │
│                                                  │
│  Cost per liter: $32.00                         │
└─────────────────────────────────────────────────┘
```

**What This Tells You**:
- Complete cost to make this batch
- Cost per unit (for pricing decisions)
- Whether labor/overhead has been allocated yet

---

### 4. Packing Details (How Products Were Packaged)

```
┌─────────────────────────────────────────────────┐
│  Packing Records                                │
├─────────────────────────────────────────────────┤
│  1 Liter Cans                                   │
│  • Quantity packed: 30.00 liters               │
│  • Number of cans: 30 pieces                   │
│  • Boxes: 6 boxes (5 cans per box)            │
│  • Finished batch: PROD-B-001-1L               │
│                                                  │
│  5 Liter Cans                                   │
│  • Quantity packed: 20.00 liters               │
│  • Number of cans: 4 pieces                    │
│  • Boxes: 1 box (4 cans per box)              │
│  • Finished batch: PROD-B-001-5L               │
│                                                  │
│  TOTAL PACKED: 50.00 liters (100%)             │
└─────────────────────────────────────────────────┘
```

**What This Tells You**:
- How the batch was split into different package sizes
- How many cans/boxes were created
- Which finished good batches are available to sell

---

### 5. Finished Good Batches (What's Available to Sell)

```
┌─────────────────────────────────────────────────┐
│  Finished Goods Inventory                       │
├─────────────────────────────────────────────────┤
│  Batch: PROD-B-001-1L                           │
│  • Size: 1 Liter cans                          │
│  • Total produced: 30 cans (30.00 liters)     │
│  • Available now: 25 cans (25.00 liters)      │
│  • Already sold: 5 cans (5.00 liters)         │
│  • Cost per can: $32.00                        │
│  • Expiry date: July 17, 2025                  │
│                                                  │
│  Batch: PROD-B-001-5L                           │
│  • Size: 5 Liter cans                          │
│  • Total produced: 4 cans (20.00 liters)      │
│  • Available now: 4 cans (20.00 liters)       │
│  • Already sold: 0 cans                        │
│  • Cost per can: $160.00                       │
│  • Expiry date: July 17, 2025                  │
└─────────────────────────────────────────────────┘
```

**What This Tells You**:
- Which finished batches are available to sell
- Current stock levels
- How much has already been sold
- Expiry dates for quality control

---

### 6. Accounting Journal (Financial Record)

```
┌─────────────────────────────────────────────────┐
│  Accounting Entry                               │
├─────────────────────────────────────────────────┤
│  Journal ID: #3001                              │
│  Reference: PROD-B-001-RM                       │
│  Date: January 17, 2025                         │
│  Status: ✅ POSTED                              │
│                                                  │
│  Debit Entries:                                 │
│  • Work In Progress: $1,600.00                 │
│                                                  │
│  Credit Entries:                                │
│  • Paint Base Inventory: $1,500.00             │
│  • Hardener Inventory: $100.00                 │
│                                                  │
│  Posted by: system                              │
│  Posted at: January 17, 2025 10:35 AM          │
└─────────────────────────────────────────────────┘
```

**What This Tells You**:
- Financial record of material consumption
- When and by whom it was recorded
- Audit trail for accounting compliance

---

## Real-World Use Cases

### Use Case 1: Quality Issue Investigation

**Situation**: Customer complains that paint from batch PROD-B-001-1L has quality issues.

**What You Do**:
1. Look up batch `PROD-B-001-1L`
2. See it came from production batch `PROD-B-001`
3. Check raw materials used:
   - Paint Base from `BATCH-PB-2025-001`
   - Hardener from `BATCH-HD-2025-003`
4. Check if other batches using same materials have issues
5. Contact supplier about specific material batches

**Result**: You identified the exact material batches used, can recall specific products, and prevent future issues.

---

### Use Case 2: Cost Analysis

**Situation**: Management wants to know why Premium Paint Red costs $32/liter.

**What You Do**:
1. Look up recent production batch (e.g., `PROD-B-001`)
2. See cost breakdown:
   - Paint Base: $15.00/liter (10L used for 50L output = $15/L)
   - Hardener: $5.00/liter (2L used for 50L output = $5/L)
   - Material cost: $20/liter
   - Labor & overhead: $12/liter (allocated later)
   - Total: $32/liter
3. Identify that raw material costs are 62.5% of total cost

**Result**: You can explain pricing and identify opportunities to reduce costs.

---

### Use Case 3: Inventory Verification

**Situation**: Monthly inventory count shows discrepancies in 1L cans.

**What You Do**:
1. List all finished batches for Premium Paint Red 1L
2. For each batch (e.g., `PROD-B-001-1L`):
   - Produced: 30 cans
   - Available: 25 cans
   - Sold: 5 cans (verify against sales orders)
3. Trace back to production logs to verify production quantities
4. Check packing records to confirm packaging was recorded correctly

**Result**: You reconciled inventory and found any recording errors.

---

### Use Case 4: Sales Order Fulfillment

**Situation**: Customer orders 20 cans of Premium Paint Red 1L. You need to know what's available.

**What You Do**:
1. Check finished goods inventory for Premium Paint Red 1L
2. See available batches:
   - `PROD-B-001-1L`: 25 cans available (expiry: Jul 2025)
   - `PROD-B-002-1L`: 40 cans available (expiry: Aug 2025)
3. Use FIFO (first expiry first): Take 20 cans from PROD-B-001-1L
4. System automatically records:
   - Batch PROD-B-001-1L: Available reduced to 5 cans
   - Sales order links to specific batch for traceability

**Result**: Customer gets products with longest shelf life, full traceability maintained.

---

## How to Use the Lookup Features

### Frontend Search Options

**Option 1: Search by Production Batch Code**
```
Search: PROD-B-001
→ Shows complete production details
→ All materials, costs, packing, journals
```

**Option 2: Search by Finished Good Batch Code**
```
Search: PROD-B-001-1L
→ Shows finished batch details
→ Traces back to source production batch
→ Shows all materials used in production
```

**Option 3: Search by Date**
```
Search: January 17, 2025
→ Lists all batches produced on that date
→ Click any batch to see full details
```

**Option 4: Search by Product**
```
Search: Premium Paint Red
→ Lists all production batches for this product
→ Shows total quantities produced
→ Current inventory levels by batch
```

---

## API Endpoints for Frontend (Technical Reference)

### Get Complete Batch Information
```typescript
GET /api/production/logs/PROD-B-001/trace

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
    "wastage": 0.00
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
      "materialName": "Paint Base",
      "quantity": 10.00,
      "unitCost": 150.00,
      "totalCost": 1500.00,
      "sourceBatch": "BATCH-PB-2025-001"
    },
    {
      "materialName": "Hardener",
      "quantity": 2.00,
      "unitCost": 50.00,
      "totalCost": 100.00,
      "sourceBatch": "BATCH-HD-2025-003"
    }
  ],

  "packingRecords": [
    {
      "packagingSize": "1L",
      "quantityPacked": 30.00,
      "piecesCount": 30,
      "finishedBatchCode": "PROD-B-001-1L"
    },
    {
      "packagingSize": "5L",
      "quantityPacked": 20.00,
      "piecesCount": 4,
      "finishedBatchCode": "PROD-B-001-5L"
    }
  ],

  "finishedBatches": [
    {
      "batchCode": "PROD-B-001-1L",
      "quantityTotal": 30.00,
      "quantityAvailable": 25.00,
      "unitCost": 32.00,
      "expiryDate": "2025-07-17"
    },
    {
      "batchCode": "PROD-B-001-5L",
      "quantityTotal": 20.00,
      "quantityAvailable": 20.00,
      "unitCost": 32.00,
      "expiryDate": "2025-07-17"
    }
  ],

  "journalEntry": {
    "id": 3001,
    "referenceNumber": "PROD-B-001-RM",
    "status": "POSTED",
    "entryDate": "2025-01-17"
  }
}
```

### Trace Finished Batch to Source
```typescript
GET /api/inventory/batches/PROD-B-001-1L/trace

Response:
{
  "finishedBatchCode": "PROD-B-001-1L",
  "productName": "Premium Paint Red",
  "quantityAvailable": 25.00,
  "unitCost": 32.00,

  "sourceProduction": {
    "productionCode": "PROD-B-001",
    "producedAt": "2025-01-17T10:30:00Z",
    "createdBy": "john.smith"
  },

  "rawMaterialsUsed": [
    {
      "materialName": "Paint Base",
      "quantity": 10.00,
      "totalCost": 1500.00
    },
    {
      "materialName": "Hardener",
      "quantity": 2.00,
      "totalCost": 100.00
    }
  ],

  "costBreakdown": {
    "materials": 1600.00,
    "total": 1600.00,
    "costPerLiter": 32.00
  }
}
```

---

## Summary: What You Get from Batch Lookup

✅ **Complete Production History**
- Who made it, when, and how much

✅ **All Materials Used**
- Exact quantities and costs
- Source batches for quality tracking

✅ **Cost Transparency**
- Material, labor, overhead costs
- Per-unit costs for pricing

✅ **Packing Details**
- How products were packaged
- Which sizes are available

✅ **Current Inventory**
- What's available to sell
- What's already been sold
- Expiry dates

✅ **Accounting Records**
- Financial journal entries
- Audit trail for compliance

✅ **Full Traceability**
- From raw materials to finished goods
- Forward: What finished batches were created
- Backward: What materials were used

---

## Key Principle: Everything is Connected

```
Customer complains about product
       ↓
Look up finished batch code (PROD-B-001-1L)
       ↓
Trace to production batch (PROD-B-001)
       ↓
See raw materials used (Paint Base BATCH-PB-2025-001)
       ↓
Check if other products from same material have issues
       ↓
Contact supplier about specific material batch
       ↓
PROBLEM SOLVED! ✓
```

**You have complete traceability from customer complaint all the way back to supplier!**

---

*This is the power of your batch traceability system - every product can be traced to its source, and every source can be traced to its products.*
