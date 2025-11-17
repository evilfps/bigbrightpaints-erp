# Orchestrator, COGS, Material Consumption & Frontend API Guide

**Simple English Explanation for Frontend Developers**

---

## Part 1: What is the Orchestrator?

### Simple Answer
The **Orchestrator** is like an **automated workflow manager** that completes multi-step business processes automatically.

### Real-World Analogy
Think of ordering food online:
- **Without Orchestrator**: You place order → waiter takes it → chef cooks → waiter serves → you pay (5 manual handoffs)
- **With Orchestrator**: You click "Order" → Everything happens automatically → Food arrives!

### In Your ERP System

**Example: Sales Order Flow**

**User Action**: Creates sales order for $10,000

**Orchestrator Automatically**:
1. ✅ Checks if customer has credit available
2. ✅ Reserves inventory (if available)
3. ✅ Updates order status to "READY_TO_SHIP" or "PENDING_PRODUCTION"
4. ✅ Creates revenue journal entry (via `accountingFacade.postSalesJournal()`)
5. ✅ Records COGS when shipped (via `accountingFacade.postCOGS()`)
6. ✅ Updates customer ledger balance
7. ✅ Generates invoice

**Result**: User clicks ONE button → 7 steps complete automatically!

---

## Part 2: COGS vs Material Consumption - What's the Difference?

### Confusion Alert!
These are **TWO DIFFERENT accounting operations** that happen at **DIFFERENT times**:

### 1. Material Consumption (`postMaterialConsumption`)

**When**: Factory **MAKES** products (production phase)
**What**: Records raw materials used in production
**Called By**: `ProductionLogService` when production batch is logged
**Accounting Entry**:
```
Dr: Work In Progress (WIP) - $2,000
Cr: Raw Material Inventory   - $2,000
```

**Real Example**:
```
Factory makes Batch B-001:
- Used 100 liters of paint base: $1,500
- Used 20 liters of hardener: $500
→ Total material cost: $2,000

accountingFacade.postMaterialConsumption(
    productionCode: "B-001",
    wipAccountId: 4001,
    materialLines: {
        accountId_3001: $1,500,  // Paint base inventory
        accountId_3002: $500     // Hardener inventory
    },
    totalCost: $2,000
);
```

**What This Means**:
- You spent $2,000 worth of raw materials
- Those materials are now "in production" (WIP account)
- Your raw material inventory decreased by $2,000

---

### 2. COGS - Cost of Goods Sold (`postCOGS`)

**When**: You **SELL** products (sales/dispatch phase)
**What**: Records the cost of products you sold
**Called By**: `IntegrationCoordinator` when order is dispatched
**Accounting Entry**:
```
Dr: Cost of Goods Sold        - $3,500
Cr: Finished Goods Inventory  - $3,500
```

**Real Example**:
```
Customer orders 50 cans of paint:
- Revenue (what you sell for): $10,000
- Cost (what it cost to make): $3,500
- Profit: $6,500

When order ships:
accountingFacade.postCOGS(
    referenceId: "SO-2025-001",
    cogsAccountId: 5001,
    inventoryAccountId: 1003,
    cost: $3,500,
    memo: "COGS for order SO-2025-001"
);
```

**What This Means**:
- You sold products that cost $3,500 to make
- Your profit on this sale is $6,500 ($10,000 - $3,500)
- Your finished goods inventory decreased by $3,500

---

### Timeline: From Raw Materials → Finished Goods → Sale

```
Step 1: BUY RAW MATERIALS
├─ Accounting: postPurchaseJournal()
├─ Dr: Raw Material Inventory $2,000
└─ Cr: Accounts Payable $2,000

Step 2: MAKE PRODUCTS (Factory)
├─ Accounting: postMaterialConsumption()
├─ Dr: Work In Progress $2,000
└─ Cr: Raw Material Inventory $2,000

Step 3: FINISH PRODUCTION
├─ Accounting: postCostAllocation() [adds labor + overhead]
├─ Dr: Finished Goods Inventory $3,500
├─ Cr: Labor Expense $1,000
└─ Cr: Overhead Expense $500

Step 4: SELL PRODUCTS
├─ Accounting: postSalesJournal() [revenue]
├─ Dr: Accounts Receivable $10,000
├─ Cr: Sales Revenue $10,000
└─ Customer now owes you $10,000

Step 5: SHIP PRODUCTS
├─ Accounting: postCOGS() [cost]
├─ Dr: Cost of Goods Sold $3,500
└─ Cr: Finished Goods Inventory $3,500

PROFIT CALCULATION:
Revenue: $10,000
COGS:    -$3,500
Profit:   $6,500 ✓
```

---

## Part 3: Orchestrator's Key Workflows

### Workflow 1: Auto-Approval of Sales Orders

**Endpoint**: `POST /api/orchestrator/auto-approve/{orderId}`

**What It Does**:
1. Locks order to prevent concurrent processing
2. Checks if already completed (idempotency)
3. Reserves inventory from finished goods
4. If inventory available → Status: "READY_TO_SHIP"
5. If inventory missing → Status: "PENDING_PRODUCTION"
6. Records state in database (OrderAutoApprovalState)

**Code**:
```java
// orchestrator calls:
reserveInventory(orderId)
   → finishedGoodsService.reserveInventory()

updateOrderStatus(orderId, "READY_TO_SHIP")
   → salesService.updateStatus()
```

---

### Workflow 2: Order Dispatch & COGS Recording

**Endpoint**: `POST /api/orchestrator/dispatch/{orderId}`

**What It Does**:
1. Creates accounting entry for revenue (if not already done)
2. Calls `postSalesJournal()` → Records AR & Revenue
3. Gets dispatch postings from `finishedGoodsService`
4. For each product dispatched:
   - Calls `accountingFacade.postCOGS()`
   - Records cost of goods sold
5. Marks order as dispatched

**Code Flow**:
```java
finalizeShipment(orderId, companyId) {
    // 1. Record revenue
    if (!state.isSalesJournalPosted()) {
        createAccountingEntry(orderId);  // calls postSalesJournal
        state.markSalesJournalPosted();
    }

    // 2. Mark goods as dispatched
    if (!state.isDispatchFinalized()) {
        List<DispatchPosting> postings =
            finishedGoodsService.markSlipDispatched(orderId);

        // 3. Record COGS for each product line
        for (DispatchPosting posting : postings) {
            accountingFacade.postCOGS(
                referenceKey,
                posting.cogsAccountId(),
                posting.inventoryAccountId(),
                posting.cost(),
                "COGS for order " + orderId
            );
        }
    }
}
```

---

### Workflow 3: Payroll Payment Processing

**Endpoint**: `POST /api/orchestrator/payroll/payment`

**What It Does**:
1. Receives payroll run details
2. Calls `accountingFacade.recordPayrollPayment()`
3. Creates journal:
   - Dr: Salary Expense
   - Cr: Cash/Bank

**Code**:
```java
recordPayrollPayment(payrollRunId, amount, expenseAccountId, cashAccountId) {
    return accountingFacade.recordPayrollPayment(
        new PayrollPaymentRequest(
            payrollRunId,
            cashAccountId,
            expenseAccountId,
            amount,
            null,  // memo
            null   // reference
        )
    );
}
```

---

### Workflow 4: Dashboard Data Aggregation

**Endpoints**:
- `GET /api/orchestrator/dashboard/admin`
- `GET /api/orchestrator/dashboard/factory`

**What It Does**:
Fetches data from multiple services and combines into one response:

**Admin Dashboard**:
```java
{
    "orders": {
        "pending": 15,
        "readyToShip": 8,
        "dispatched": 42
    },
    "dealers": {
        "totalBalance": $125,000,
        "overdueAmount": $5,000
    },
    "accounting": {
        "cashBalance": $45,000,
        "revenueThisMonth": $150,000
    },
    "hr": {
        "activeEmployees": 25,
        "pendingLeaveRequests": 3
    }
}
```

**Factory Dashboard**:
```java
{
    "production": {
        "efficiency": 87.5%,
        "completed": 120,
        "batchesLogged": 145
    },
    "tasks": 18,
    "inventory": {
        "finishedGoods": $85,000,
        "rawMaterials": $32,000
    }
}
```

---

## Part 4: Frontend API Endpoints You Need

### 1. Sales Module APIs

#### Create Sales Order
```typescript
POST /api/sales/orders
Request:
{
    "dealerId": 123,
    "items": [
        {
            "productId": 456,
            "quantity": 10,
            "unitPrice": 100.00
        }
    ],
    "notes": "Urgent order"
}
Response:
{
    "id": 789,
    "orderNumber": "SO-2025-001",
    "status": "DRAFT",
    "totalAmount": 1180.00  // includes tax
}
```

#### Auto-Approve Order (Orchestrator)
```typescript
POST /api/orchestrator/auto-approve/{orderId}
Response:
{
    "status": "READY_TO_SHIP",  // or "PENDING_PRODUCTION"
    "inventoryReserved": true,
    "awaitingProduction": false
}
```

#### Dispatch Order (Orchestrator)
```typescript
POST /api/orchestrator/dispatch/{orderId}
Response:
{
    "status": "DISPATCHED",
    "salesJournalId": 1001,
    "cogsJournalIds": [1002, 1003],
    "invoiceGenerated": true
}
```

---

### 2. Purchasing Module APIs

#### Create Purchase Order
```typescript
POST /api/purchasing/purchases
Request:
{
    "supplierId": 99,
    "invoiceNumber": "INV-2025-001",
    "invoiceDate": "2025-01-17",
    "lines": [
        {
            "rawMaterialId": 55,
            "quantity": 100,
            "costPerUnit": 50.00,
            "batchCode": "BATCH-001"
        }
    ]
}
Response:
{
    "id": 888,
    "invoiceNumber": "INV-2025-001",
    "totalAmount": 5000.00,
    "journalEntryId": 2001  // accounting journal created
}
```

#### Record Purchase Return
```typescript
POST /api/purchasing/returns
Request:
{
    "supplierId": 99,
    "rawMaterialId": 55,
    "quantity": 10,
    "unitCost": 50.00,
    "reason": "Damaged goods"
}
Response:
{
    "id": 999,
    "referenceNumber": "PRN-SUP99-ABC123",
    "totalAmount": 500.00,
    "journalEntryId": 2002
}
```

---

### 3. Production Module APIs

#### Log Production Batch
```typescript
POST /api/production/logs
Request:
{
    "productionBatchId": 333,
    "productionDate": "2025-01-17",
    "quantityProduced": 50,
    "materials": [
        {
            "rawMaterialId": 55,
            "quantityUsed": 10,
            "batchCode": "BATCH-001"
        }
    ]
}
Response:
{
    "id": 444,
    "batchCode": "PROD-B-001",
    "status": "COMPLETED",
    "materialConsumptionJournalId": 3001  // calls postMaterialConsumption
}
```

#### Allocate Production Costs
```typescript
POST /api/production/cost-allocation
Request:
{
    "batchCode": "PROD-B-001",
    "laborCost": 1000.00,
    "overheadCost": 500.00
}
Response:
{
    "batchCode": "PROD-B-001",
    "totalCostAllocated": 1500.00,
    "journalEntryId": 3002  // calls postCostAllocation
}
```

---

### 4. Accounting Module APIs

#### List Journal Entries
```typescript
GET /api/accounting/journals?dealerId=123
Response:
{
    "journals": [
        {
            "id": 1001,
            "referenceNumber": "SO-2025-001",
            "entryDate": "2025-01-17",
            "status": "POSTED",
            "memo": "Sales order SO-2025-001",
            "dealerName": "ABC Corp",
            "lines": [
                {
                    "accountCode": "AR",
                    "description": "Accounts Receivable",
                    "debit": 1180.00,
                    "credit": 0
                },
                {
                    "accountCode": "REV",
                    "description": "Sales Revenue",
                    "debit": 0,
                    "credit": 1000.00
                },
                {
                    "accountCode": "GST",
                    "description": "GST Payable",
                    "debit": 0,
                    "credit": 180.00
                }
            ]
        }
    ]
}
```

#### Create Manual Journal Entry
```typescript
POST /api/accounting/journals/simple
Request:
{
    "reference": "MANUAL-2025-001",
    "entryDate": "2025-01-17",
    "memo": "Manual adjustment",
    "debitAccountId": 1001,
    "creditAccountId": 2002,
    "amount": 500.00
}
Response:
{
    "id": 5001,
    "referenceNumber": "MANUAL-2025-001",
    "status": "POSTED"
}
```

---

### 5. Dashboard APIs (Orchestrator)

#### Admin Dashboard
```typescript
GET /api/orchestrator/dashboard/admin
Response:
{
    "orders": {
        "pending": 15,
        "readyToShip": 8,
        "dispatched": 42,
        "totalValue": 250000.00
    },
    "dealers": {
        "totalBalance": 125000.00,
        "overdueAmount": 5000.00,
        "activeCount": 45
    },
    "accounting": {
        "cashBalance": 45000.00,
        "revenueThisMonth": 150000.00,
        "expensesThisMonth": 85000.00
    },
    "hr": {
        "activeEmployees": 25,
        "pendingLeaveRequests": 3
    }
}
```

#### Factory Dashboard
```typescript
GET /api/orchestrator/dashboard/factory
Response:
{
    "production": {
        "efficiency": 87.5,
        "completedPlans": 120,
        "batchesLogged": 145
    },
    "tasks": 18,
    "inventory": {
        "finishedGoodsValue": 85000.00,
        "rawMaterialsValue": 32000.00
    }
}
```

---

### 6. Inventory Module APIs

#### Reserve Inventory (Auto-called by Orchestrator)
```typescript
POST /api/inventory/reserve/{orderId}
Response:
{
    "orderId": 789,
    "reservations": [
        {
            "productId": 456,
            "quantityReserved": 10,
            "batchCode": "FG-B-001"
        }
    ],
    "shortages": []  // empty if all available
}
```

#### Adjust Inventory
```typescript
POST /api/inventory/adjustments
Request:
{
    "type": "DAMAGE",
    "referenceNumber": "ADJ-2025-001",
    "adjustmentAccountId": 6001,  // loss account
    "lines": [
        {
            "inventoryAccountId": 1003,
            "amount": 500.00,
            "increase": false  // decrease inventory
        }
    ],
    "reason": "Damaged during handling"
}
Response:
{
    "id": 777,
    "referenceNumber": "ADJ-2025-001",
    "journalEntryId": 4001  // calls postInventoryAdjustment
}
```

---

## Part 5: Frontend Page Structure

### Recommended Pages & Their API Calls

#### 1. Sales Order Page
**Route**: `/sales/orders`

**APIs Needed**:
- `GET /api/sales/orders` - List all orders
- `POST /api/sales/orders` - Create new order
- `POST /api/orchestrator/auto-approve/{orderId}` - Auto-approve
- `POST /api/orchestrator/dispatch/{orderId}` - Dispatch order

**UI Flow**:
```
1. User fills order form
2. Click "Create Order"
   → Calls POST /api/sales/orders
3. Click "Approve"
   → Calls POST /api/orchestrator/auto-approve
   → Shows "READY_TO_SHIP" or "PENDING_PRODUCTION"
4. Click "Dispatch"
   → Calls POST /api/orchestrator/dispatch
   → Shows success message
   → Revenue & COGS journals created automatically
```

---

#### 2. Purchase Order Page
**Route**: `/purchasing/orders`

**APIs Needed**:
- `GET /api/purchasing/purchases` - List purchases
- `POST /api/purchasing/purchases` - Create purchase
- `POST /api/purchasing/returns` - Record return

**UI Flow**:
```
1. User fills purchase form
2. Click "Create Purchase"
   → Calls POST /api/purchasing/purchases
   → Journal entry created automatically
3. If damaged goods received:
   → Click "Record Return"
   → Calls POST /api/purchasing/returns
   → Return journal created automatically
```

---

#### 3. Production Page
**Route**: `/production/batches`

**APIs Needed**:
- `GET /api/production/logs` - List production batches
- `POST /api/production/logs` - Log production
- `POST /api/production/cost-allocation` - Allocate costs

**UI Flow**:
```
1. User logs production batch
   → Calls POST /api/production/logs
   → Material consumption journal created
2. User allocates costs
   → Calls POST /api/production/cost-allocation
   → Cost allocation journal created
```

---

#### 4. Accounting Journal Page
**Route**: `/accounting/journals`

**APIs Needed**:
- `GET /api/accounting/journals` - List all journals
- `GET /api/accounting/journals?dealerId=123` - Filter by dealer
- `POST /api/accounting/journals/simple` - Manual entry

**UI Flow**:
```
1. View all accounting entries
2. Filter by dealer/supplier/date
3. Click "Manual Entry"
   → Fill form (debit account, credit account, amount)
   → Calls POST /api/accounting/journals/simple
   → Manual journal created
```

---

#### 5. Dashboard Page
**Route**: `/dashboard`

**APIs Needed**:
- `GET /api/orchestrator/dashboard/admin` - Admin view
- `GET /api/orchestrator/dashboard/factory` - Factory view

**UI Components**:
```
Cards showing:
- Total orders pending
- Revenue this month
- Cash balance
- Production efficiency
- Active employees
```

---

## Part 6: Key Points for Frontend Developers

### 1. Don't Call Accounting APIs Directly
❌ **Wrong**:
```typescript
// DON'T do this from sales page:
await axios.post('/api/accounting/journals', {...});
```

✅ **Correct**:
```typescript
// DO this from sales page:
await axios.post('/api/orchestrator/dispatch/{orderId}');
// Orchestrator handles accounting automatically
```

**Why**: The orchestrator ensures all related accounting entries are created in the correct order with proper validation.

---

### 2. Use Orchestrator for Multi-Step Operations

**Operations that use Orchestrator**:
- ✅ Approving sales orders
- ✅ Dispatching orders (creates revenue + COGS journals)
- ✅ Recording payroll payments
- ✅ Fetching dashboard data

**Operations that call services directly**:
- ✅ Creating purchase orders (single-step)
- ✅ Logging production batches (single-step)
- ✅ Adjusting inventory (single-step)

---

### 3. Handle Status Updates in UI

After calling orchestrator APIs, update UI based on response:

```typescript
// Auto-approve order
const result = await axios.post(`/api/orchestrator/auto-approve/${orderId}`);

if (result.status === "READY_TO_SHIP") {
    // Show "Dispatch" button
    showDispatchButton();
} else if (result.status === "PENDING_PRODUCTION") {
    // Show "Waiting for production" message
    showProductionPendingMessage();
}
```

---

### 4. Error Handling

All accounting operations have validation:

```typescript
try {
    await axios.post('/api/orchestrator/dispatch/' + orderId);
    showSuccess("Order dispatched successfully");
} catch (error) {
    if (error.response.status === 400) {
        // Validation error
        showError(error.response.data.message);
        // Example: "Insufficient inventory for dispatch"
    } else if (error.response.status === 409) {
        // Already processed (idempotency)
        showInfo("Order already dispatched");
    }
}
```

---

## Summary: What You Need to Know

### For Sales Flow:
1. User creates order → `POST /api/sales/orders`
2. User approves → `POST /api/orchestrator/auto-approve/{orderId}`
3. User dispatches → `POST /api/orchestrator/dispatch/{orderId}`
   - Revenue journal created automatically (`postSalesJournal`)
   - COGS journal created automatically (`postCOGS`)

### For Purchase Flow:
1. User creates purchase → `POST /api/purchasing/purchases`
   - Purchase journal created automatically (`postPurchaseJournal`)
2. User records return → `POST /api/purchasing/returns`
   - Return journal created automatically (`postPurchaseReturn`)

### For Production Flow:
1. User logs production → `POST /api/production/logs`
   - Material consumption journal created (`postMaterialConsumption`)
2. User allocates costs → `POST /api/production/cost-allocation`
   - Cost allocation journal created (`postCostAllocation`)

### Key Principle:
**Business services call AccountingFacade → Accounting journals created automatically → No manual accounting entries needed!**

---

*Now you understand the complete flow from UI → Orchestrator → Business Services → AccountingFacade → Database!*
