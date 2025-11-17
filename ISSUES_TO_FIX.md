# Issues That Need Fixing

## Summary
**Current Status:** 36/72 tests passing (50%)
**Target:** 58/72 tests passing (80%)
**Tests to fix:** 22 tests

---

## **PRIORITY 1: Backend 500 Errors (10 tests) - BLOCKING**

These indicate actual backend issues that need investigation:

### Issue 1A: Journal Entry Creation - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `CriticalPathSmokeTest.createJournalEntrySuccess`
- `JournalEntryE2ETest.trialBalance_AfterManyTransactions_Balances` (NPE)
- `JournalEntryE2ETest.journalReversal_CreatesOffsettingEntry_LinkedAudit` (NPE)
- `JournalEntryE2ETest.financialReports_ProfitLoss_BalanceSheet_Accurate` (NPE)
- `BusinessLogicRegressionTest.doubleEntry_NeverUnbalanced_AllScenarios` (NPE)

**Current request format (looks correct):**
```java
Map.of(
    "referenceNumber", "TEST-JE-" + System.currentTimeMillis(),
    "entryDate", LocalDate.now(),
    "memo", "Critical Path Test Entry",
    "lines", List.of(
        Map.of("accountId", cashAccount.getId(), "debit", 1000.00, "credit", 0, "description", "Test debit"),
        Map.of("accountId", revenueAccount.getId(), "debit", 0, "credit", 1000.00, "description", "Test credit")
    )
)
```

**Likely root causes:**
1. ❓ AccountingPeriodService may be rejecting entries for current period
2. ❓ Company may need accounting configuration initialized
3. ❓ Missing fiscal year setup for the company

**How to diagnose:**
```bash
# Run test with debug logging to see the actual error
mvn test -Dtest=CriticalPathSmokeTest#createJournalEntrySuccess -X 2>&1 | grep -A 20 "500"
```

**Possible fixes:**
- Check if company needs fiscal year configuration
- Check if accounting periods are being created correctly
- Add accounting setup to TestDataSeeder

---

### Issue 1B: Production Log Creation - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `CriticalPathSmokeTest.logProductionSuccess`
- `CompleteProductionCycleTest.completePacking_CreatesFinishedGoodBatches_FIFO` (NPE)
- `CompleteProductionCycleTest.packingWithWastage_AutoCalculationAndAccounting` (NPE)
- `CompleteProductionCycleTest.partialPacking_MultiplePackingSessions` (NPE)

**Current request format:**
```java
Map.of(
    "brandId", brand.getId(),
    "productId", product.getId(),
    "batchSize", new BigDecimal("100.00"),
    "mixedQuantity", new BigDecimal("100.00"),
    "materials", List.of(
        Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("10.00"))
    )
)
```

**Likely root causes:**
1. ❓ Raw material stock deduction failing
2. ❓ GL posting for production failing (missing accounts)
3. ❓ Production log service expecting additional fields

**How to diagnose:**
```bash
# Check actual backend error
mvn test -Dtest=CriticalPathSmokeTest#logProductionSuccess -X 2>&1 | grep -B 5 -A 20 "Exception"
```

**Possible fixes:**
- Ensure raw materials have accounting integration setup
- Check if production logs need company manufacturing accounts configured
- Verify raw material has sufficient stock (test creates 500 units, uses 10)

---

### Issue 1C: Cost Allocation - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `CriticalPathSmokeTest.allocateCostsSuccess`

**Current request format:**
```java
Map.of(
    "year", 2025,
    "month", 1,
    "laborCost", new BigDecimal("100000.00"),
    "overheadCost", new BigDecimal("50000.00")
)
```

**Likely root cause:**
- Cost allocation expects production logs to exist for the period
- Test is calling cost allocation WITHOUT creating production logs first

**How to fix:**
1. Create production logs BEFORE testing cost allocation
2. OR modify test to expect empty result (200 OK with empty data)

---

### Issue 1D: Packing Records - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `CriticalPathSmokeTest.recordPackingSuccess`

**Endpoint:** `GET /api/v1/factory/unpacked-batches`

**Likely root cause:**
- Backend exception when querying unpacked batches
- May be expecting specific data structure that doesn't exist

**How to diagnose:**
```bash
mvn test -Dtest=CriticalPathSmokeTest#recordPackingSuccess -X 2>&1 | grep -A 20 "500"
```

---

### Issue 1E: MFA Enrollment - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `MfaControllerIT.enrollment_and_activation_require_totp_for_login`

**Likely root causes:**
1. ❓ TOTP library issue in test environment
2. ❓ MFA service initialization problem
3. ❓ Missing crypto/encryption setup for tests

---

### Issue 1F: Payroll Run - 500 INTERNAL_SERVER_ERROR
**Tests failing:**
- `OrchestratorControllerIT.payroll_run_creates_payroll_entry`

**Likely root causes:**
1. ❓ Company payroll account defaults not configured
2. ❓ Missing employee data
3. ❓ Payroll GL posting failing

---

## **PRIORITY 2: Sales Order 400 Errors (7 tests) - HIGH IMPACT**

### Issue 2: Sales Order Creation - 400 BAD_REQUEST
**Tests failing:**
- `CriticalPathSmokeTest.createSalesOrderSuccess`
- `OrderFulfillmentE2ETest.createOrder_AutoApproval_WithinCreditLimit`
- `OrderFulfillmentE2ETest.createOrder_RequiresManualApproval_ExceedsCreditLimit`
- `OrderFulfillmentE2ETest.orderFulfillment_ReservesInventory_FIFO`
- `OrderFulfillmentE2ETest.orderWithGST_CalculatesCorrectTaxes`
- `OrderFulfillmentE2ETest.orderWithPromotion_AppliesDiscount_CorrectPricing`
- `OrderFulfillmentE2ETest.dispatch_CreatesPackingSlip_Invoice_PostsCOGS` (NPE)
- `OrderFulfillmentE2ETest.multipleOrders_SameDealer_UpdatesLedgerBalance` (NPE)
- `BusinessLogicRegressionTest.dealerBalance_AfterMultipleTransactions_AlwaysAccurate`

**Request format looks CORRECT:**
```java
// SalesOrderRequest expects: dealerId, totalAmount, currency, items, gstTreatment
// Test is sending: dealerId, totalAmount, currency, items, gstTreatment ✓

// SalesOrderItemRequest expects: productCode, description, quantity, unitPrice, gstRate
// Test is sending: productCode, description, quantity, unitPrice, gstRate ✓
```

**Possible causes:**
1. ❓ Finished good doesn't exist in database
2. ❓ Dealer doesn't have required fields (address, GST number, etc.)
3. ❓ Product pricing validation failing
4. ❓ Business validation rule (credit limit, inventory availability)

**How to diagnose:**
```bash
# Check the actual validation error message
mvn test -Dtest=CriticalPathSmokeTest#createSalesOrderSuccess -X 2>&1 | grep -B 10 "400 BAD_REQUEST"
```

**Likely fix:**
- Check backend logs for actual validation error
- May need to add more fields to dealer or finished good setup
- Verify finished good has correct revenue account configured

---

## **PRIORITY 3: 404 Errors (3 tests) - QUICK FIX**

### Issue 3A: Dispatch Endpoint - 404 NOT_FOUND
**Tests failing:**
- `CriticalPathSmokeTest.dispatchOrderEndpointAvailable`

**Current endpoint:** `POST /api/v1/orchestrator/dispatch`

**How to fix:**
1. Check actual endpoint in OrchestratorController
2. Update test with correct path

---

### Issue 3B: Production with Multiple Materials - 404 NOT_FOUND
**Tests failing:**
- `CompleteProductionCycleTest.productionWithMultipleRawMaterials_InventoryDeduction`
- `CompleteProductionCycleTest.productionLog_WithoutSufficientRawMaterial_ThrowsError` (expects 400/422, gets 404)

**How to fix:**
1. Verify production log endpoint path
2. May be same as Issue 1B (production logs)

---

## **PRIORITY 4: Data Setup Issues (2 tests)**

### Issue 4A: Portal Insights Setup - InvalidDataAccessApiUsage
**Tests failing:**
- `PortalInsightsControllerIT.setUp` (blocks all 3 tests in suite)

**Error:** `TransientPropertyValueException: PackagingSlip.salesOrder -> SalesOrder`

**Root cause:**
- Test is trying to save a PackagingSlip with an unsaved SalesOrder
- Need to save SalesOrder BEFORE creating PackagingSlip

**How to fix:**
```java
// WRONG:
SalesOrder order = new SalesOrder();
PackagingSlip slip = new PackagingSlip();
slip.setSalesOrder(order); // order is transient!
slipRepository.save(slip); // ❌ FAILS

// RIGHT:
SalesOrder order = new SalesOrder();
order = orderRepository.save(order); // ✅ Save first
PackagingSlip slip = new PackagingSlip();
slip.setSalesOrder(order);
slipRepository.save(slip); // ✅ Works
```

---

### Issue 4B: MFA Recovery Code Test Logic
**Tests failing:**
- `MfaControllerIT.recovery_code_is_consumed_after_login`

**Issue:** Test expects 401 UNAUTHORIZED, but gets 200 OK

**Root cause:** Test logic issue - recovery code is working correctly, test assertion is wrong

---

## **PRIORITY 5: Inventory Movements (1 test)**

### Issue 5: Inventory GL Matching
**Tests failing:**
- `BusinessLogicRegressionTest.dataIntegrity_InventoryMovements_MatchJournalGL`

**Getting:** 400 BAD_REQUEST

**Likely cause:**
- Test creating inventory movement with missing required fields
- OR inventory movement validation failing

---

## Quick Diagnosis Commands

### 1. See all 500 errors with details:
```bash
cd erp-domain
mvn test -X 2>&1 | grep -B 10 -A 20 "500 INTERNAL_SERVER_ERROR" > 500_errors.txt
cat 500_errors.txt
```

### 2. See all 400 errors with details:
```bash
mvn test -X 2>&1 | grep -B 10 -A 20 "400 BAD_REQUEST" > 400_errors.txt
cat 400_errors.txt
```

### 3. Run just the failing tests:
```bash
# Journal entries
mvn test -Dtest=CriticalPathSmokeTest#createJournalEntrySuccess

# Production logs
mvn test -Dtest=CriticalPathSmokeTest#logProductionSuccess

# Sales orders
mvn test -Dtest=CriticalPathSmokeTest#createSalesOrderSuccess

# All Critical Path
mvn test -Dtest=CriticalPathSmokeTest
```

---

## Recommended Fix Order

1. **Start here:** Run tests with `-X` flag to capture actual error messages
2. **Fix:** Portal insights data setup (easy fix, unblocks 3 tests)
3. **Fix:** Journal entry 500 error (will fix 5 tests)
4. **Fix:** Production log 500 error (will fix 4 tests)
5. **Fix:** Sales order 400 error (will fix 7 tests)
6. **Fix:** 404 endpoint issues (quick, will fix 3 tests)
7. **Fix:** Cost allocation (needs production logs first)
8. **Fix:** Remaining edge cases

**Expected result after fixes:** 58-62 tests passing (80-85%)

---

## What to Do Next

### Option A: Deep Dive (Recommended)
Run the failing tests with debug output to see the ACTUAL backend errors:

```bash
cd erp-domain
mvn test -Dtest=CriticalPathSmokeTest -X > test_output.txt 2>&1
cat test_output.txt | grep -A 30 "Exception\|Error creating\|Failed to"
```

This will show you the exact backend exception messages, which will tell you:
- What's missing in the database
- What validation is failing
- What configuration is needed

### Option B: Manual Testing
Start the application and test the endpoints manually via Swagger:

```bash
cd erp-domain
mvn spring-boot:run
```

Then open: `http://localhost:8080/swagger-ui`

Try creating:
1. A journal entry
2. A production log
3. A sales order

See what validation errors you get, then fix the tests accordingly.

### Option C: Fix Low-Hanging Fruit First
1. Fix PortalInsightsControllerIT data setup (10 minutes)
2. Fix 404 endpoint paths (10 minutes)
3. Fix MFA recovery code test assertion (5 minutes)

This will quickly get you to **39-40 tests passing (54%)** with minimal effort.

---

## Most Likely Root Cause

Based on the pattern of failures, **the most likely issue is:**

**Missing company accounting/manufacturing setup in test data.**

The backend expects companies to have:
- ✅ Fiscal year configuration
- ✅ Accounting periods defined
- ✅ Default accounts configured (for COGS, revenue, inventory, etc.)
- ✅ Manufacturing accounts configured (for production)
- ✅ Payroll accounts configured (for payroll)

**The tests create companies, but may not be initializing these configurations.**

**Quick test:** Check if `TestDataSeeder.ensureUser()` also initializes company accounting defaults. If not, add that setup.
