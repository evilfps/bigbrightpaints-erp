package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AccountingPeriodService {

    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.01");
    private static final String UNRESOLVED_CONTROLS_PREFIX = "Checklist controls unresolved for this period: ";
    private static final List<String> RECONCILIATION_CONTROL_ORDER = List.of(
            "inventoryReconciled",
            "arReconciled",
            "apReconciled");

    private final AccountingPeriodRepository accountingPeriodRepository;
    private final CompanyContextService companyContextService;
    private final JournalEntryRepository journalEntryRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final JournalLineRepository journalLineRepository;
    private final AccountRepository accountRepository;
    private final CompanyClock companyClock;
    private final ReportService reportService;
    private final ReconciliationService reconciliationService;
    private final InvoiceRepository invoiceRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
    private final PeriodCloseHook periodCloseHook;
    private final AccountingPeriodSnapshotService snapshotService;

    public AccountingPeriodService(AccountingPeriodRepository accountingPeriodRepository,
                                   CompanyContextService companyContextService,
                                   JournalEntryRepository journalEntryRepository,
                                   CompanyEntityLookup companyEntityLookup,
                                   JournalLineRepository journalLineRepository,
                                   AccountRepository accountRepository,
                                   CompanyClock companyClock,
                                   ReportService reportService,
                                   ReconciliationService reconciliationService,
                                   InvoiceRepository invoiceRepository,
                                   GoodsReceiptRepository goodsReceiptRepository,
                                   RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                                   PayrollRunRepository payrollRunRepository,
                                   ObjectProvider<AccountingFacade> accountingFacadeProvider,
                                   PeriodCloseHook periodCloseHook,
                                   AccountingPeriodSnapshotService snapshotService) {
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.companyContextService = companyContextService;
        this.journalEntryRepository = journalEntryRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.journalLineRepository = journalLineRepository;
        this.accountRepository = accountRepository;
        this.companyClock = companyClock;
        this.reportService = reportService;
        this.reconciliationService = reconciliationService;
        this.invoiceRepository = invoiceRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.accountingFacadeProvider = accountingFacadeProvider;
        this.periodCloseHook = periodCloseHook;
        this.snapshotService = snapshotService;
    }

    public List<AccountingPeriodDto> listPeriods() {
        Company company = companyContextService.requireCurrentCompany();
        ensureSurroundingPeriods(company);
        return accountingPeriodRepository.findByCompanyOrderByYearDescMonthDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public AccountingPeriodDto getPeriod(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        return toDto(period);
    }

    @Transactional
    public AccountingPeriodDto closePeriod(Long periodId, AccountingPeriodCloseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            snapshotService.captureSnapshot(company, period, resolveCurrentUsername());
            return toDto(period);
        }
        if (period.getStatus() == AccountingPeriodStatus.LOCKED) {
            return toDto(period);
        }
        String note = request != null && StringUtils.hasText(request.note()) ? request.note().trim() : null;
        if (!StringUtils.hasText(note)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Close reason is required");
        }
        periodCloseHook.onPeriodCloseLocked(company, period);
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        assertNoUninvoicedReceipts(company, period);
        if (!force) {
            assertChecklistComplete(company, period);
        }
        if (note != null) {
            period.setChecklistNotes(note);
        }
        BigDecimal netIncome = computeNetIncome(company, period);
        Long closingJournalId = null;
        if (netIncome.compareTo(BigDecimal.ZERO) != 0) {
            JournalEntry closingJe = postClosingJournal(company, period, netIncome, note);
            closingJournalId = closingJe.getId();
        }
        String user = resolveCurrentUsername();
        snapshotService.captureSnapshot(company, period, user);
        Instant now = CompanyTime.now(company);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(now);
        period.setClosedBy(user);
        period.setLockedAt(now);
        period.setLockedBy(user);
        period.setLockReason(note);
        period.setClosingJournalEntryId(closingJournalId);
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        ensurePeriod(company, period.getEndDate().plusDays(1));
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto confirmBankReconciliation(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        period.setBankReconciled(true);
        period.setBankReconciledAt(CompanyTime.now(company));
        period.setBankReconciledBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto confirmInventoryCount(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        period.setInventoryCounted(true);
        period.setInventoryCountedAt(CompanyTime.now(company));
        period.setInventoryCountedBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto lockPeriod(Long periodId, AccountingPeriodLockRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.LOCKED || period.getStatus() == AccountingPeriodStatus.CLOSED) {
            return toDto(period);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Lock reason is required");
        }
        period.setStatus(AccountingPeriodStatus.LOCKED);
        period.setLockedAt(CompanyTime.now(company));
        period.setLockedBy(resolveCurrentUsername());
        period.setLockReason(request.reason().trim());
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.OPEN) {
            return toDto(period);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Reopen reason is required");
        }
        Instant now = CompanyTime.now(company);
        period.setStatus(AccountingPeriodStatus.OPEN);
        period.setReopenedAt(now);
        period.setReopenedBy(resolveCurrentUsername());
        period.setReopenReason(request != null ? request.reason() : null);
        // Auto-reverse closing journal if present
        if (period.getClosingJournalEntryId() != null) {
            journalEntryRepository.findByCompanyAndId(company, period.getClosingJournalEntryId())
                    .ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, request.reason()));
            period.setClosingJournalEntryId(null);
        }
        snapshotService.deleteSnapshotForPeriod(company, period);
        return toDto(accountingPeriodRepository.save(period));
    }

    public MonthEndChecklistDto getMonthEndChecklist(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        return buildChecklist(company, period);
    }

    @Transactional
    public MonthEndChecklistDto updateMonthEndChecklist(Long periodId, MonthEndChecklistUpdateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        if (request != null) {
            if (request.bankReconciled() != null) {
                period.setBankReconciled(request.bankReconciled());
            }
            if (request.inventoryCounted() != null) {
                period.setInventoryCounted(request.inventoryCounted());
            }
            if (StringUtils.hasText(request.note())) {
                period.setChecklistNotes(request.note().trim());
            }
        }
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        return buildChecklist(company, saved);
    }

    public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
        AccountingPeriod period = lockOrCreatePeriod(company, referenceDate);
        if (period.getStatus() != AccountingPeriodStatus.OPEN) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Accounting period " + period.getLabel() + " is locked/closed");
        }
        return period;
    }

    private AccountingPeriod lockOrCreatePeriod(Company company, LocalDate referenceDate) {
        LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        LocalDate safeDate = baseDate.withDayOfMonth(1);
        int year = safeDate.getYear();
        int month = safeDate.getMonthValue();
        Optional<AccountingPeriod> locked = accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, year, month);
        if (locked.isPresent()) {
            return locked.get();
        }
        return ensurePeriod(company, safeDate);
    }

    /**
     * Computes net income for a period using double-entry accounting principles.
     * 
     * Net Income = (Revenue + Other Income) - COGS - Operating Expenses - Other Expenses
     * 
     * For each account type:
     * - REVENUE: Normal credit balance. Net = Credits - Debits (positive = income)
     *   Contra accounts (returns, discounts) have debit balances, reducing revenue.
     * - OTHER_INCOME: Normal credit balance (interest income, gains on sales)
     * - EXPENSE: Normal debit balance. Net = Debits - Credits (positive = expense)
     * - OTHER_EXPENSE: Normal debit balance (interest expense, losses)
     * - COGS: Normal debit balance. Net = Debits - Credits (positive = cost)
     * 
     * The query includes all journal entries within the period date range,
     * regardless of posting date (accrual basis).
     * 
     * Prior period adjustments should be posted to EQUITY (Retained Earnings)
     * and will not affect current period net income.
     */
    private BigDecimal computeNetIncome(Company company, AccountingPeriod period) {
        List<Object[]> aggregates = journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate());
        
        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal otherExpenses = BigDecimal.ZERO;
        
        for (Object[] row : aggregates) {
            if (row == null || row.length < 3) {
                continue;
            }
            AccountType type = (AccountType) row[0];
            BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            
            switch (type) {
                case REVENUE -> {
                    // Revenue has credit balance; contra-revenue (returns) reduces it
                    grossRevenue = grossRevenue.add(credit.subtract(debit));
                }
                case OTHER_INCOME -> {
                    // Other income has credit balance (interest, gains)
                    otherIncome = otherIncome.add(credit.subtract(debit));
                }
                case COGS -> {
                    // COGS has debit balance
                    totalCogs = totalCogs.add(debit.subtract(credit));
                }
                case EXPENSE -> {
                    // Operating expenses have debit balance
                    totalExpenses = totalExpenses.add(debit.subtract(credit));
                }
                case OTHER_EXPENSE -> {
                    // Other expenses have debit balance (interest expense, losses)
                    otherExpenses = otherExpenses.add(debit.subtract(credit));
                }
                default -> {
                    // ASSET, LIABILITY, EQUITY don't affect net income
                    // Prior period adjustments go to EQUITY (Retained Earnings)
                }
            }
        }
        
        // Net Income = (Gross Revenue + Other Income) - COGS - Operating Expenses - Other Expenses
        BigDecimal totalIncome = grossRevenue.add(otherIncome);
        BigDecimal totalCosts = totalCogs.add(totalExpenses).add(otherExpenses);
        BigDecimal netIncome = totalIncome.subtract(totalCosts);
        return netIncome.setScale(2, RoundingMode.HALF_UP);
    }

    private JournalEntry postClosingJournal(Company company,
                                            AccountingPeriod period,
                                            BigDecimal netIncome,
                                            String note) {
        String reference = "PERIOD-CLOSE-" + period.getYear() + String.format("%02d", period.getMonth());
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseGet(() -> createSystemJournal(company, period, reference, note, netIncome));
    }

    private JournalEntry createSystemJournal(Company company,
                                             AccountingPeriod period,
                                             String reference,
                                             String note,
                                             BigDecimal netIncome) {
        Account retained = ensureEquityAccount(company, "RETAINED_EARNINGS", "Retained Earnings");
        Account periodResult = ensureEquityAccount(company, "PERIOD_RESULT", "Period Result");
        BigDecimal amount = netIncome.abs();
        String memo = note != null ? note : "Period close " + period.getLabel();
        boolean profit = netIncome.compareTo(BigDecimal.ZERO) > 0;
        Long debitAccountId = profit ? periodResult.getId() : retained.getId();
        Long creditAccountId = profit ? retained.getId() : periodResult.getId();
        LocalDate entryDate = period.getEndDate();
        LocalDate today = companyClock.today(company);
        if (entryDate.isAfter(today)) {
            entryDate = today;
        }
        AccountingFacade accountingFacade = accountingFacadeProvider.getObject();
        JournalEntryDto posted = accountingFacade.postSimpleJournal(
                reference,
                entryDate,
                memo,
                debitAccountId,
                creditAccountId,
                amount,
                true);
        return journalEntryRepository.findByCompanyAndId(company, posted.id())
                .orElseThrow(() -> new ApplicationException(ErrorCode.SYSTEM_INTERNAL_ERROR,
                        "Closing journal entry not found after posting"));
    }

    private Account ensureEquityAccount(Company company, String code, String name) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account acct = new Account();
                    acct.setCompany(company);
                    acct.setCode(code);
                    acct.setName(name);
                    acct.setType(AccountType.EQUITY);
                    return accountRepository.save(acct);
                });
    }

    private void reverseClosingJournalIfNeeded(JournalEntry closing,
                                               AccountingPeriod period,
                                               String reason) {
        if (closing == null) {
            return;
        }
        String status = closing.getStatus() != null ? closing.getStatus().toUpperCase() : null;
        if ("REVERSED".equals(status) || "VOIDED".equals(status) || closing.getReversalEntry() != null) {
            return;
        }
        AccountingFacade accountingFacade = accountingFacadeProvider.getObject();
        accountingFacade.reverseClosingEntryForPeriodReopen(closing, period, reason);
    }

    @Transactional
    public AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
        LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        LocalDate safeDate = baseDate.withDayOfMonth(1);
        int year = safeDate.getYear();
        int month = safeDate.getMonthValue();
        return accountingPeriodRepository.findByCompanyAndYearAndMonth(company, year, month)
                .orElseGet(() -> {
                    AccountingPeriod period = new AccountingPeriod();
                    period.setCompany(company);
                    period.setYear(year);
                    period.setMonth(month);
                    period.setStartDate(safeDate);
                    period.setEndDate(safeDate.plusMonths(1).minusDays(1));
                    period.setStatus(AccountingPeriodStatus.OPEN);
                    return accountingPeriodRepository.save(period);
                });
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId, LocalDate referenceDate) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        LocalDate effectiveDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        return ensurePeriod(company, effectiveDate);
    }

    private void ensureSurroundingPeriods(Company company) {
        LocalDate today = resolveCurrentDate(company);
        ensurePeriod(company, today);
        ensurePeriod(company, today.minusMonths(1));
        ensurePeriod(company, today.plusMonths(1));
    }

    private void assertChecklistComplete(Company company, AccountingPeriod period) {
        if (!period.isBankReconciled()) {
            throw new IllegalStateException("Bank reconciliation has not been confirmed for this period");
        }
        if (!period.isInventoryCounted()) {
            throw new IllegalStateException("Inventory count has not been confirmed for this period");
        }
        long drafts = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        if (drafts > 0) {
            throw new IllegalStateException("There are " + drafts + " draft entries in this period");
        }
        ChecklistDiagnostics diagnostics = evaluateChecklistDiagnostics(company, period);
        List<String> unresolvedControls = diagnostics.unresolvedControlsInPolicyOrder();
        if (!unresolvedControls.isEmpty()) {
            throw new IllegalStateException(UNRESOLVED_CONTROLS_PREFIX + String.join(", ", unresolvedControls));
        }
        if (!diagnostics.inventoryReconciled()) {
            throw new IllegalStateException("Inventory reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.inventoryVariance()) + ")");
        }
        if (!diagnostics.arReconciled()) {
            throw new IllegalStateException("AR reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.arVariance()) + ")");
        }
        if (!diagnostics.apReconciled()) {
            throw new IllegalStateException("AP reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.apVariance()) + ")");
        }
        if (diagnostics.unbalancedJournals() > 0) {
            throw new IllegalStateException("Unbalanced journals present in this period (" +
                    diagnostics.unbalancedJournals() + ")");
        }
        if (diagnostics.unlinkedDocuments() > 0) {
            throw new IllegalStateException("Documents missing journal links in this period (" +
                    diagnostics.unlinkedDocuments() + ")");
        }
        if (diagnostics.unpostedDocuments() > 0) {
            throw new IllegalStateException("Unposted documents exist in this period (" +
                    diagnostics.unpostedDocuments() + ")");
        }
    }

    private void assertNoUninvoicedReceipts(Company company, AccountingPeriod period) {
        long uninvoicedReceipts = countUninvoicedReceipts(company, period);
        if (uninvoicedReceipts > 0) {
            throw new IllegalStateException("Un-invoiced goods receipts exist in this period (" +
                    uninvoicedReceipts + ")");
        }
    }

    private AccountingPeriodDto toDto(AccountingPeriod period) {
        return new AccountingPeriodDto(
                period.getId(),
                period.getYear(),
                period.getMonth(),
                period.getStartDate(),
                period.getEndDate(),
                period.getLabel(),
                period.getStatus().name(),
                period.isBankReconciled(),
                period.getBankReconciledAt(),
                period.getBankReconciledBy(),
                period.isInventoryCounted(),
                period.getInventoryCountedAt(),
                period.getInventoryCountedBy(),
                period.getClosedAt(),
                period.getClosedBy(),
                period.getChecklistNotes(),
                period.getLockedAt(),
                period.getLockedBy(),
                period.getLockReason(),
                period.getReopenedAt(),
                period.getReopenedBy(),
                period.getReopenReason(),
                period.getClosingJournalEntryId(),
                period.getChecklistNotes()
        );
    }

    private LocalDate resolveCurrentDate(Company company) {
        return companyClock.today(company);
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        return accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN)
                .orElseGet(() -> ensurePeriod(company, resolveCurrentDate(company)));
    }

    private MonthEndChecklistDto buildChecklist(Company company, AccountingPeriod period) {
        long draftEntries = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        boolean draftsCleared = draftEntries == 0;
        ChecklistDiagnostics diagnostics = evaluateChecklistDiagnostics(company, period);
        boolean inventoryControlResolved = diagnostics.inventoryControlResolved();
        boolean arControlResolved = diagnostics.arControlResolved();
        boolean apControlResolved = diagnostics.apControlResolved();
        boolean inventoryReconciled = diagnostics.inventoryReconciled();
        boolean arReconciled = diagnostics.arReconciled();
        boolean apReconciled = diagnostics.apReconciled();
        boolean unbalancedCleared = diagnostics.unbalancedJournals() == 0;
        boolean unlinkedCleared = diagnostics.unlinkedDocuments() == 0;
        boolean unpostedCleared = diagnostics.unpostedDocuments() == 0;
        boolean receiptsCleared = diagnostics.uninvoicedReceipts() == 0;
        String inventoryDetail = !inventoryControlResolved
                ? "Control unresolved: inventory reconciliation result unavailable"
                : (inventoryReconciled
                ? "Variance " + formatVariance(diagnostics.inventoryVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.inventoryVariance()) + " exceeds tolerance");
        String arDetail = !arControlResolved
                ? "Control unresolved: AR reconciliation result unavailable"
                : (arReconciled
                ? "Variance " + formatVariance(diagnostics.arVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.arVariance()) + " exceeds tolerance");
        String apDetail = !apControlResolved
                ? "Control unresolved: AP reconciliation result unavailable"
                : (apReconciled
                ? "Variance " + formatVariance(diagnostics.apVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.apVariance()) + " exceeds tolerance");
        List<MonthEndChecklistItemDto> items = List.of(
                new MonthEndChecklistItemDto(
                        "bankReconciled",
                        "Bank accounts reconciled",
                        period.isBankReconciled(),
                        period.isBankReconciled() ? "Confirmed" : "Pending review"),
                new MonthEndChecklistItemDto(
                        "inventoryCounted",
                        "Inventory counted",
                        period.isInventoryCounted(),
                        period.isInventoryCounted() ? "Counts logged" : "Awaiting stock count"),
                new MonthEndChecklistItemDto(
                        "draftEntries",
                        "Draft entries cleared",
                        draftsCleared,
                        draftsCleared ? "All entries posted" : draftEntries + " draft entries remaining"),
                new MonthEndChecklistItemDto(
                        "inventoryReconciled",
                        "Inventory reconciled to GL",
                        inventoryReconciled,
                        inventoryDetail),
                new MonthEndChecklistItemDto(
                        "arReconciled",
                        "AR reconciled to dealer ledger",
                        arReconciled,
                        arDetail),
                new MonthEndChecklistItemDto(
                        "apReconciled",
                        "AP reconciled to supplier ledger",
                        apReconciled,
                        apDetail),
                new MonthEndChecklistItemDto(
                        "unbalancedJournals",
                        "Unbalanced journals cleared",
                        unbalancedCleared,
                        unbalancedCleared ? "All journals balanced" : diagnostics.unbalancedJournals() + " unbalanced journals"),
                new MonthEndChecklistItemDto(
                        "unlinkedDocuments",
                        "Documents linked to journals",
                        unlinkedCleared,
                        unlinkedCleared ? "All documents linked" : diagnostics.unlinkedDocuments() + " missing journal links"),
                new MonthEndChecklistItemDto(
                        "uninvoicedReceipts",
                        "Goods receipts invoiced",
                        receiptsCleared,
                        receiptsCleared ? "All receipts invoiced" : diagnostics.uninvoicedReceipts() + " receipts awaiting invoice"),
                new MonthEndChecklistItemDto(
                        "unpostedDocuments",
                        "Unposted documents cleared",
                        unpostedCleared,
                        unpostedCleared ? "All documents posted" : diagnostics.unpostedDocuments() + " unposted documents")
        );
        boolean ready = period.isBankReconciled()
                && period.isInventoryCounted()
                && draftsCleared
                && inventoryReconciled
                && arReconciled
                && apReconciled
                && unbalancedCleared
                && unlinkedCleared
                && receiptsCleared
                && unpostedCleared;
        return new MonthEndChecklistDto(toDto(period), items, ready);
    }

    private ChecklistDiagnostics evaluateChecklistDiagnostics(Company company, AccountingPeriod period) {
        ReconciliationSummaryDto inventory = reportService.inventoryReconciliation();
        ReconciliationService.PeriodReconciliationResult periodReconciliation = reconciliationService
                .reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate());
        long unbalancedJournals = countUnbalancedJournals(company, period);
        long unlinkedDocuments = countUnlinkedDocuments(company, period);
        long unpostedDocuments = countUnpostedDocuments(company, period);
        long uninvoicedReceipts = countUninvoicedReceipts(company, period);
        return new ChecklistDiagnostics(inventory, periodReconciliation, unpostedDocuments, unlinkedDocuments, unbalancedJournals, uninvoicedReceipts);
    }

    private long countUnbalancedJournals(Company company, AccountingPeriod period) {
        return journalEntryRepository
                .findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(company, period.getStartDate(), period.getEndDate())
                .stream()
                .filter(this::isUnbalanced)
                .count();
    }

    private boolean isUnbalanced(JournalEntry entry) {
        BigDecimal debits = entry.getLines().stream()
                .map(JournalLine::getDebit)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entry.getLines().stream()
                .map(JournalLine::getCredit)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return debits.subtract(credits).abs().compareTo(RECONCILIATION_TOLERANCE) > 0;
    }

    private long countUnpostedDocuments(Company company, AccountingPeriod period) {
        long invoiceDrafts = invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT"));
        long purchaseUnposted = rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID"));
        long payrollUnposted = payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.DRAFT,
                        PayrollRun.PayrollStatus.CALCULATED,
                        PayrollRun.PayrollStatus.APPROVED));
        return invoiceDrafts + purchaseUnposted + payrollUnposted;
    }

    private long countUninvoicedReceipts(Company company, AccountingPeriod period) {
        return goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company,
                period.getStartDate(),
                period.getEndDate(),
                "INVOICED");
    }

    private long countUnlinkedDocuments(Company company, AccountingPeriod period) {
        long invoiceUnlinked = invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                "DRAFT");
        long purchaseUnlinked = rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID"));
        long payrollUnlinked = payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID));
        return invoiceUnlinked + purchaseUnlinked + payrollUnlinked;
    }

    private String formatVariance(BigDecimal variance) {
        return safe(variance).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ChecklistDiagnostics(
            ReconciliationSummaryDto inventory,
            ReconciliationService.PeriodReconciliationResult periodReconciliation,
            long unpostedDocuments,
            long unlinkedDocuments,
            long unbalancedJournals,
            long uninvoicedReceipts
    ) {
        List<String> unresolvedControlsInPolicyOrder() {
            List<String> unresolved = new ArrayList<>(RECONCILIATION_CONTROL_ORDER.size());
            if (!inventoryControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(0));
            }
            if (!arControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(1));
            }
            if (!apControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(2));
            }
            return List.copyOf(unresolved);
        }

        boolean inventoryControlResolved() {
            return inventory != null && inventory.variance() != null;
        }

        boolean inventoryReconciled() {
            return inventoryControlResolved() && varianceWithinTolerance(inventoryVariance());
        }

        BigDecimal inventoryVariance() {
            return inventory != null ? inventory.variance() : BigDecimal.ZERO;
        }

        boolean arControlResolved() {
            return periodReconciliation != null && periodReconciliation.arVariance() != null;
        }

        boolean arReconciled() {
            return arControlResolved() && periodReconciliation.arReconciled();
        }

        BigDecimal arVariance() {
            return periodReconciliation != null ? periodReconciliation.arVariance() : BigDecimal.ZERO;
        }

        boolean apControlResolved() {
            return periodReconciliation != null && periodReconciliation.apVariance() != null;
        }

        boolean apReconciled() {
            return apControlResolved() && periodReconciliation.apReconciled();
        }

        BigDecimal apVariance() {
            return periodReconciliation != null ? periodReconciliation.apVariance() : BigDecimal.ZERO;
        }

        private boolean varianceWithinTolerance(BigDecimal variance) {
            return variance != null && variance.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
        }
    }

    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }
}
