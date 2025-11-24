# Accounting Module - Comprehensive Fix Implementation Plan

## Executive Summary
This document provides a complete implementation plan to fix 14 critical accounting logic issues that could cause financial data corruption, incorrect reporting, and system instability.

---

## Root Cause Analysis & Implementation Plan

### 1. **Journal Entries Can Post to Locked/Closed Periods**

#### Why This Happened:
- **Developer Assumption**: Assumed UI would prevent selection of closed periods
- **Missing Domain Protection**: Relied on presentation layer validation instead of business layer enforcement
- **Incomplete Requirements**: Period locking requirements likely added after initial journal entry implementation

#### What Needs Fixing:
```java
// In AccountingService.createJournalEntry() - Add before line 237
private void validatePeriodOpen(AccountingPeriod period) {
    if (period.getStatus() != AccountingPeriodStatus.OPEN) {
        throw new BusinessRuleException(
            String.format("Cannot post entries to %s period %s. Period must be OPEN.",
                period.getStatus(), period.getName())
        );
    }
}

// Also needed in:
// - updateJournalEntry()
// - Any method that modifies journal entries
```

#### Implementation Steps:
1. Add period status validation in `AccountingService.createJournalEntry()` before saving
2. Add same validation in `updateJournalEntry()` method
3. Create integration test that attempts to post to CLOSED and LOCKED periods
4. Add database constraint: `CHECK (status = 'OPEN' OR journal_entries_count = 0)`

---

### 2. **Race Condition in Partner Ledger Balance Updates**

#### Why This Happened:
- **Concurrency Oversight**: Developer didn't anticipate simultaneous payment processing
- **Missing Transaction Isolation**: No pessimistic locking or optimistic versioning
- **Testing Gap**: Only tested single-threaded operations, not concurrent access

#### What Needs Fixing:
```java
// In AbstractPartnerLedgerService.recordLedgerEntry()
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void recordLedgerEntry(Partner partner, LedgerEntry entry) {
    // Use pessimistic locking
    Partner lockedPartner = partnerRepository.findByIdWithLock(partner.getId());

    // Now safe to calculate and update balance
    BigDecimal newBalance = aggregateBalance(lockedPartner);
    lockedPartner.setOutstandingBalance(newBalance);
    partnerRepository.save(lockedPartner);
}

// Add to Partner entity
@Version
@Column(name = "version")
private Long version; // For optimistic locking as backup
```

#### Implementation Steps:
1. Add `@Version` field to `Dealer` and `Supplier` entities for optimistic locking
2. Implement `findByIdWithLock()` method using `@Lock(LockModeType.PESSIMISTIC_WRITE)`
3. Wrap balance updates in REPEATABLE_READ transaction isolation
4. Add migration: `ALTER TABLE dealers ADD COLUMN version BIGINT DEFAULT 0`
5. Create concurrent update test using `CountDownLatch` to force race condition

---

### 3. **Foreign Currency Gain/Loss Calculation Reversed for Suppliers**

#### Why This Happened:
- **Copy-Paste Error**: Developer copied dealer settlement logic but incorrectly modified FX calculation
- **Lack of Accounting Knowledge**: Didn't understand that FX gain/loss direction is same for both AR and AP
- **Missing Unit Tests**: No tests comparing dealer vs supplier FX handling

#### What Needs Fixing:
```java
// In AccountingService.settleSupplierInvoices() - Line 798
// WRONG: totalApplied.add(totalFxLoss).subtract(totalFxGain)
// CORRECT: Match dealer logic
BigDecimal cashAmount = totalApplied
    .add(totalFxGain)      // FX gains reduce cash needed
    .subtract(totalFxLoss)  // FX losses increase cash needed
    .subtract(totalDiscount)
    .subtract(totalWriteOff);
```

#### Implementation Steps:
1. Fix line 798 in `settleSupplierInvoices()` to match dealer calculation
2. Extract FX calculation to shared method `calculateSettlementCashAmount()`
3. Add unit test comparing dealer and supplier FX calculations
4. Add integration test with multi-currency supplier settlement
5. Review existing supplier settlements for incorrect FX postings

---

### 4. **Foreign Amount Total Incorrectly Calculated**

#### Why This Happened:
- **Conceptual Misunderstanding**: Developer confused foreign amount tracking with balance calculation
- **Wrong Formula**: Added debit AND credit instead of tracking net foreign exposure
- **No Multi-Currency Testing**: Likely never tested with actual foreign currency entries

#### What Needs Fixing:
```java
// In AccountingService.createJournalEntry() - Line 212
// WRONG: foreignTotal = foreignTotal.add(debit).add(credit)
// CORRECT: Track foreign currency amount, not sum
if (line.getForeignCurrencyAmount() != null) {
    // For reporting, we need the actual foreign amount, not sum of debits/credits
    foreignTotal = foreignTotal.add(line.getForeignCurrencyAmount());
}

// Better approach - track by currency
Map<String, BigDecimal> foreignTotals = new HashMap<>();
if (line.getCurrency() != null && !line.getCurrency().equals(company.getBaseCurrency())) {
    foreignTotals.merge(line.getCurrency(),
        line.getForeignCurrencyAmount(),
        BigDecimal::add);
}
```

#### Implementation Steps:
1. Change journal entry to track foreign amounts per currency, not total
2. Add `foreign_amounts` JSON column to store `Map<Currency, Amount>`
3. Fix existing entries via data migration script
4. Add multi-currency journal entry test
5. Update financial reports to use corrected foreign amounts

---

### 5. **Orphaned Ledger Entries When Journal Creation Partially Fails**

#### Why This Happened:
- **Transaction Boundary Error**: Saved journal before confirming ledger entries succeed
- **Incorrect Sequencing**: Should validate everything first, then save atomically
- **Missing Rollback**: No compensation logic if ledger recording fails

#### What Needs Fixing:
```java
@Transactional(rollbackFor = Exception.class)
public JournalEntry createJournalEntry(JournalEntryRequest request) {
    // 1. Validate everything first
    validateRequest(request);
    validatePeriodOpen(period);
    validateAccounts(accounts);

    // 2. Prepare all data
    JournalEntry entry = buildJournalEntry(request);
    List<LedgerRecord> ledgerRecords = prepareLedgerRecords(entry);

    // 3. Save everything in correct order within transaction
    entry = journalEntryRepository.save(entry);

    // 4. Record ledger entries (will rollback journal if this fails)
    for (LedgerRecord record : ledgerRecords) {
        ledgerService.recordEntry(record);
    }

    // 5. Update account balances last
    updateAccountBalances(accounts, deltas);

    return entry;
}
```

#### Implementation Steps:
1. Restructure method to validate everything before any saves
2. Ensure proper `@Transactional` annotation with rollback configuration
3. Move journal save AFTER ledger validation but before ledger recording
4. Add test that simulates ledger service failure
5. Add reconciliation check: GL total = sum of subledger totals

---

### 6. **Settlement Can Create Negative Cash Amount**

#### Why This Happened:
- **Late Validation**: Validates cash after already creating database records
- **Partial Rollback**: Exception doesn't clean up allocation records
- **Business Rule Placement**: Business validation mixed with data persistence

#### What Needs Fixing:
```java
// In AccountingService.settleDealerInvoices()
// Move calculation and validation BEFORE any database operations

// Calculate first (around line 490)
BigDecimal cashAmount = calculateCashAmount(
    totalApplied, totalFxGain, totalFxLoss, totalDiscount, totalWriteOff);

// Validate immediately
if (cashAmount.compareTo(BigDecimal.ZERO) < 0) {
    throw new ValidationException(
        String.format("Settlement would result in negative cash: %s. " +
            "Adjust discounts or write-offs.", cashAmount));
}

// Only THEN create allocations (line 520+)
for (AllocationRequest allocation : request.getAllocations()) {
    // Create allocation records
}
```

#### Implementation Steps:
1. Move cash calculation to line 490 (before creating allocations)
2. Validate cash is non-negative immediately after calculation
3. Only create allocation records if validation passes
4. Add unit test with excessive discounts that would create negative cash
5. Add UI validation to prevent negative cash scenarios

---

### 7. **Duplicate Validation Logic**

#### Why This Happened:
- **Copy-Paste Programming**: Developer duplicated code instead of refactoring
- **Merge Conflict Resolution**: Possibly result of incorrect merge conflict resolution
- **No Code Review**: Code review should have caught obvious duplication

#### What Needs Fixing:
```java
// In AccountingService.settleDealerInvoices()
// DELETE lines 568-579 (duplicate block)
// KEEP only lines 555-570

// Better: Extract to method
private void validateSettlementAmounts(BigDecimal discount, BigDecimal writeOff,
                                      BigDecimal available) {
    if (discount.compareTo(BigDecimal.ZERO) < 0) {
        throw new ValidationException("Discount cannot be negative");
    }
    if (writeOff.compareTo(BigDecimal.ZERO) < 0) {
        throw new ValidationException("Write-off cannot be negative");
    }
    if (discount.add(writeOff).compareTo(available) > 0) {
        throw new ValidationException("Discount + write-off exceeds available amount");
    }
}
```

#### Implementation Steps:
1. Remove duplicate validation block (lines 568-579)
2. Extract validation to private method
3. Call validation method once
4. Add SonarQube or similar tool to detect code duplication
5. Review entire class for other duplications

---

### 8. **Period Closing Not Atomic**

#### Why This Happened:
- **Sequential Thinking**: Developer thought of steps sequentially, not transactionally
- **Missing Failure Scenario**: Didn't consider what happens if next period creation fails
- **State Management**: Period state changes not treated as atomic operation

#### What Needs Fixing:
```java
@Transactional(rollbackFor = Exception.class)
public void closePeriod(Long periodId) {
    AccountingPeriod period = findPeriod(periodId);

    // 1. Create next period FIRST (will rollback if fails)
    AccountingPeriod nextPeriod = createNextPeriod(period);

    // 2. Create closing journal entry
    JournalEntry closingEntry = createClosingJournalEntry(period);

    // 3. Update period status LAST (after everything succeeds)
    period.setStatus(AccountingPeriodStatus.CLOSED);
    period.setClosingJournalEntry(closingEntry);
    period.setNextPeriod(nextPeriod);
    accountingPeriodRepository.save(period);
}
```

#### Implementation Steps:
1. Reorder operations: next period first, status update last
2. Add foreign key: `next_period_id` to track period chain
3. Ensure entire operation is wrapped in transaction
4. Add test that simulates next period creation failure
5. Add invariant check: every CLOSED period must have next period

---

### 9. **Missing Currency Validation in Settlements**

#### Why This Happened:
- **Incomplete Validation**: Focused on ownership validation, forgot currency matching
- **Assumption**: Assumed UI would only show same-currency invoices
- **Multi-Currency Afterthought**: Multi-currency support likely added later

#### What Needs Fixing:
```java
// In AccountingService.settleDealerInvoices() after line 535
for (AllocationRequest allocation : request.getAllocations()) {
    Invoice invoice = validateAndGetInvoice(allocation.getInvoiceId(), dealer);

    // Add currency validation
    if (!invoice.getCurrency().equals(request.getCurrency())) {
        throw new ValidationException(
            String.format("Cannot settle %s invoice with %s payment. " +
                "Invoice %s has different currency.",
                invoice.getCurrency(), request.getCurrency(),
                invoice.getNumber()));
    }
}
```

#### Implementation Steps:
1. Add currency validation for each allocated invoice
2. Add similar validation in supplier settlement
3. Create test with mixed-currency settlement attempt
4. Update API documentation to specify currency requirements
5. Add UI filtering to only show same-currency invoices

---

### 10. **Idempotency Key Optional Allows Duplicates**

#### Why This Happened:
- **Optional Feature**: Idempotency treated as optional instead of required
- **Network Retry Scenarios**: Didn't consider automatic retry scenarios
- **Missing Default**: Should auto-generate key if not provided

#### What Needs Fixing:
```java
// In AccountingService.settleDealerInvoices()
public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
    // Auto-generate idempotency key if not provided
    String idempotencyKey = request.getIdempotencyKey();
    if (idempotencyKey == null) {
        // Generate key from request content
        idempotencyKey = generateIdempotencyKey(request);
        request.setIdempotencyKey(idempotencyKey);
    }

    // Check for duplicate
    Optional<PartnerSettlement> existing = settlementRepo
        .findByCompanyAndIdempotencyKey(company, idempotencyKey);
    if (existing.isPresent()) {
        return buildResponse(existing.get()); // Return existing
    }
}

private String generateIdempotencyKey(SettlementRequest request) {
    String content = String.format("%s-%s-%s-%s",
        request.getDealerId(),
        request.getAmount(),
        request.getSettlementDate(),
        request.getAllocations().hashCode());
    return DigestUtils.sha256Hex(content);
}
```

#### Implementation Steps:
1. Auto-generate idempotency key from request hash if not provided
2. Add unique constraint: `UNIQUE(company_id, idempotency_key)`
3. Return existing settlement if duplicate key found
4. Add test for duplicate settlement prevention
5. Document idempotency behavior in API docs

---

### 11. **Account Balance Updates Not Protected**

#### Why This Happened:
- **Optimistic Concurrency**: Assumed account updates wouldn't conflict
- **Missing Locks**: No locking strategy for account balance updates
- **Race Condition**: Multiple journal entries can update same account simultaneously

#### What Needs Fixing:
```java
// In AccountingService.createJournalEntry()
@Transactional(isolation = Isolation.SERIALIZABLE)
public JournalEntry createJournalEntry(JournalEntryRequest request) {
    // Load accounts with pessimistic lock
    List<Account> accounts = request.getLines().stream()
        .map(line -> accountRepository.findByIdWithLock(line.getAccountId()))
        .distinct()
        .collect(Collectors.toList());

    // Now safe to calculate deltas and update
    Map<Long, BigDecimal> deltas = calculateDeltas(request.getLines());

    // Update balances (protected by lock)
    for (Account account : accounts) {
        BigDecimal delta = deltas.get(account.getId());
        account.setBalance(account.getBalance().add(delta));
    }
}

// In AccountRepository
@Query("SELECT a FROM Account a WHERE a.id = :id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Account findByIdWithLock(@Param("id") Long id);
```

#### Implementation Steps:
1. Add pessimistic locking when loading accounts
2. Use SERIALIZABLE isolation for journal entry creation
3. Add version field to Account entity for optimistic locking fallback
4. Create concurrent journal entry test
5. Monitor for lock timeouts in production

---

### 12. **Period State Transitions Unvalidated**

#### Why This Happened:
- **Missing State Machine**: No formal state machine for period status
- **Assumed Valid Transitions**: Assumed callers would respect state transitions
- **No Invariant Enforcement**: Database doesn't prevent invalid transitions

#### What Needs Fixing:
```java
// In AccountingPeriodService
public void lockPeriod(Long periodId) {
    AccountingPeriod period = findPeriod(periodId);

    // Validate state transition
    if (period.getStatus() != AccountingPeriodStatus.CLOSED) {
        throw new IllegalStateException(
            String.format("Cannot lock period in status %s. Period must be CLOSED first.",
                period.getStatus()));
    }

    period.setStatus(AccountingPeriodStatus.LOCKED);
    save(period);
}

public void reopenPeriod(Long periodId) {
    AccountingPeriod period = findPeriod(periodId);

    // Validate state transition
    if (period.getStatus() != AccountingPeriodStatus.LOCKED) {
        throw new IllegalStateException(
            String.format("Cannot reopen period in status %s. Only LOCKED periods can be reopened.",
                period.getStatus()));
    }

    // Reopen logic...
}

// Add state transition validation
private static final Map<AccountingPeriodStatus, Set<AccountingPeriodStatus>> VALID_TRANSITIONS = Map.of(
    AccountingPeriodStatus.OPEN, Set.of(AccountingPeriodStatus.CLOSED),
    AccountingPeriodStatus.CLOSED, Set.of(AccountingPeriodStatus.LOCKED),
    AccountingPeriodStatus.LOCKED, Set.of(AccountingPeriodStatus.OPEN)
);
```

#### Implementation Steps:
1. Define valid state transitions map
2. Add validation in all status-changing methods
3. Add database check constraint for valid transitions
4. Create state diagram documentation
5. Add tests for invalid transition attempts

---

### 13. **Checklist Completion Has Race Condition**

#### Why This Happened:
- **Time-of-Check-Time-of-Use**: Classic TOCTOU bug
- **Non-Atomic Checks**: Multiple separate queries instead of single atomic check
- **Missing Lock**: No lock between checking and closing

#### What Needs Fixing:
```java
// In AccountingPeriodService.assertChecklistComplete()
@Transactional(isolation = Isolation.SERIALIZABLE)
private void assertChecklistComplete(AccountingPeriod period) {
    // Lock the period for update
    period = periodRepository.findByIdWithLock(period.getId());

    // All checks in single query with lock
    ChecklistStatus status = periodRepository.getChecklistStatus(period.getId());

    if (!status.isBankReconciled()) {
        throw new ValidationException("Bank reconciliation not complete");
    }
    if (!status.isInventoryCounted()) {
        throw new ValidationException("Inventory count not complete");
    }
    if (status.getDraftCount() > 0) {
        throw new ValidationException(
            String.format("%d draft entries still exist", status.getDraftCount()));
    }
}

// Add to repository
@Query("SELECT NEW ChecklistStatus(" +
       "p.bankReconciled, p.inventoryCounted, " +
       "COUNT(je.id)) " +
       "FROM AccountingPeriod p " +
       "LEFT JOIN JournalEntry je ON je.period = p AND je.status = 'DRAFT' " +
       "WHERE p.id = :periodId " +
       "GROUP BY p.id")
ChecklistStatus getChecklistStatus(@Param("periodId") Long periodId);
```

#### Implementation Steps:
1. Create `ChecklistStatus` DTO for atomic status query
2. Use single query to get all checklist items
3. Lock period during checklist validation
4. Use SERIALIZABLE isolation for closing operation
5. Add test with concurrent draft creation during closing

---

### 14. **Missing Null Check Causes NPE Risk**

#### Why This Happened:
- **Assumption**: Assumed all journal entries have dealer or supplier
- **Incomplete Validation**: No null checks before accessing nested properties
- **Missing Test Case**: No test for entries without dealer/supplier

#### What Needs Fixing:
```java
// In AccountingService.reverseJournalEntry()
private JournalLineRequest buildReversalLine(JournalLine original) {
    JournalLineRequest reversal = new JournalLineRequest();

    // Safely handle dealer/supplier
    if (original.getEntry().getDealer() != null) {
        reversal.setDealerId(original.getEntry().getDealer().getId());
    }
    if (original.getEntry().getSupplier() != null) {
        reversal.setSupplierId(original.getEntry().getSupplier().getId());
    }

    // Reverse debits and credits
    reversal.setDebitAmount(original.getCreditAmount());
    reversal.setCreditAmount(original.getDebitAmount());

    return reversal;
}
```

#### Implementation Steps:
1. Add null checks for dealer/supplier before accessing IDs
2. Extract reversal line building to separate method
3. Add unit test for reversing entry without dealer/supplier
4. Review all `.getId()` calls for similar NPE risks
5. Consider using Optional for nullable relationships

---

## Implementation Priority & Timeline

### Phase 1: CRITICAL (Week 1)
**Fix data corruption and calculation errors**
1. Fix #1: Period locking validation (4 hours)
2. Fix #3: FX calculation for suppliers (2 hours)
3. Fix #4: Foreign amount calculation (3 hours)
4. Fix #9: Currency validation (2 hours)

### Phase 2: HIGH (Week 2)
**Fix race conditions and transaction issues**
1. Fix #2: Ledger balance race condition (6 hours)
2. Fix #5: Transaction boundaries (4 hours)
3. Fix #11: Account balance locking (4 hours)

### Phase 3: MEDIUM (Week 3)
**Fix state management and validation**
1. Fix #6: Negative cash validation (2 hours)
2. Fix #8: Atomic period closing (3 hours)
3. Fix #10: Idempotency enforcement (3 hours)
4. Fix #12: State transition validation (3 hours)
5. Fix #13: Checklist race condition (3 hours)

### Phase 4: CLEANUP (Week 4)
**Code quality and minor fixes**
1. Fix #7: Remove duplicate code (1 hour)
2. Fix #14: Add null checks (2 hours)
3. Add comprehensive tests (8 hours)
4. Documentation updates (4 hours)

---

## Testing Strategy

### Unit Tests Required
```java
@Test
public void testCannotPostToLockedPeriod() { }
@Test
public void testConcurrentLedgerUpdates() { }
@Test
public void testSupplierFxCalculation() { }
@Test
public void testForeignAmountTracking() { }
@Test
public void testNegativeCashValidation() { }
@Test
public void testIdempotentSettlement() { }
@Test
public void testPeriodStateTransitions() { }
@Test
public void testCurrencyMismatchValidation() { }
```

### Integration Tests Required
```java
@Test
public void testFullSettlementWorkflow() { }
@Test
public void testPeriodClosingWorkflow() { }
@Test
public void testMultiCurrencyJournalEntry() { }
@Test
public void testConcurrentJournalEntries() { }
@Test
public void testJournalReversalWithNullDealer() { }
```

### Performance Tests Required
- Concurrent settlement processing (100 threads)
- Period closing with 10,000 journal entries
- Balance calculation with 1,000 ledger entries

---

## Database Migrations Required

```sql
-- V52__accounting_fixes.sql

-- 1. Add version columns for optimistic locking
ALTER TABLE dealers ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE suppliers ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE accounts ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- 2. Add unique constraint for idempotency
ALTER TABLE partner_settlements
ADD CONSTRAINT uk_company_idempotency
UNIQUE (company_id, idempotency_key);

-- 3. Add check constraint for period status transitions
ALTER TABLE accounting_periods
ADD CONSTRAINT chk_valid_period_status
CHECK (
    (status = 'OPEN' AND closing_journal_entry_id IS NULL) OR
    (status = 'CLOSED' AND closing_journal_entry_id IS NOT NULL) OR
    (status = 'LOCKED' AND closing_journal_entry_id IS NOT NULL)
);

-- 4. Add foreign key for next period
ALTER TABLE accounting_periods
ADD COLUMN next_period_id BIGINT,
ADD CONSTRAINT fk_next_period
FOREIGN KEY (next_period_id) REFERENCES accounting_periods(id);

-- 5. Create index for concurrent access
CREATE INDEX idx_journal_entries_period_status
ON journal_entries(accounting_period_id, status);

-- 6. Add check constraint to prevent posting to closed periods
ALTER TABLE journal_entries
ADD CONSTRAINT chk_period_open
CHECK (
    NOT EXISTS (
        SELECT 1 FROM accounting_periods ap
        WHERE ap.id = accounting_period_id
        AND ap.status != 'OPEN'
    )
);
```

---

## Monitoring & Alerts

### Metrics to Track
1. **Concurrent Update Failures**: Count of optimistic lock exceptions
2. **Period Violation Attempts**: Count of posts to closed periods
3. **Idempotency Hits**: Count of duplicate requests prevented
4. **FX Calculation Discrepancies**: Monitor FX gain/loss totals
5. **Balance Mismatches**: GL vs Subledger reconciliation

### Alerts to Configure
```yaml
alerts:
  - name: "Locked Period Posting Attempt"
    condition: "count(period_violation) > 0"
    severity: HIGH

  - name: "Concurrent Update Deadlock"
    condition: "count(deadlock_exception) > 5 per minute"
    severity: CRITICAL

  - name: "GL/Subledger Mismatch"
    condition: "abs(gl_total - subledger_total) > 0.01"
    severity: CRITICAL
```

---

## Post-Implementation Verification

### Verification Steps
1. Run full reconciliation of all open periods
2. Verify no journal entries in closed periods
3. Confirm all FX calculations match expected values
4. Test concurrent payment processing (load test)
5. Validate all account balances match ledger totals
6. Review audit logs for any anomalies

### Success Criteria
- Zero posting to closed periods
- Zero concurrent update failures in production
- 100% idempotency key usage in settlements
- All multi-currency calculations accurate to 4 decimal places
- All state transitions follow defined rules
- No NPE errors in reversal operations

---

## Risk Mitigation

### Rollback Plan
1. All migrations include DOWN scripts
2. Feature flags for new validation logic
3. Ability to disable pessimistic locking if performance issues
4. Manual override for period status (admin only)

### Data Recovery
1. Backup before each phase deployment
2. Audit trail for all financial transactions
3. Reconciliation reports before/after changes
4. Script to recalculate all balances from ledger entries

---

## Communication Plan

### Stakeholder Updates
- **Finance Team**: Notify about period locking enforcement
- **Operations**: Warn about potential lock timeouts during transition
- **API Consumers**: Document idempotency requirements
- **Support**: Provide troubleshooting guide for common errors

### Documentation Updates
1. API documentation with idempotency requirements
2. Accounting workflow diagrams with state transitions
3. Troubleshooting guide for lock timeouts
4. Multi-currency handling guide
5. Period closing checklist

---

## Long-term Improvements (Beyond Scope)

While not part of this fix, consider for future:
1. Event sourcing for complete audit trail
2. CQRS for read/write separation
3. Saga pattern for distributed transactions
4. Automated reconciliation jobs
5. Real-time anomaly detection