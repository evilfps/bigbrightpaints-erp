package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancy;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyResolution;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyListResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyResolveRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.DealerDiscrepancy;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.InterCompanyReconciliationItem;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.InterCompanyReconciliationReport;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.PeriodReconciliationResult;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.ReconciliationResult;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.SubledgerReconciliationReport;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.SupplierDiscrepancy;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService.SupplierReconciliationResult;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

/**
 * Internal reconciliation operations backing {@link ReconciliationService}.
 * Used to detect discrepancies between AR/AP accounts and dealer/supplier ledgers.
 */
final class ReconciliationOperations {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationOperations.class);
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  private final CompanyContextService companyContextService;
  private final CompanyRepository companyRepository;
  private final AccountRepository accountRepository;
  private final DealerRepository dealerRepository;
  private final DealerLedgerRepository dealerLedgerRepository;
  private final SupplierRepository supplierRepository;
  private final SupplierLedgerRepository supplierLedgerRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final TemporalBalanceService temporalBalanceService;
  private final ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
  private final AccountingPeriodRepository accountingPeriodRepository;
  private final TaxService taxService;
  private final ReportService reportService;
  private final ObjectProvider<JournalEntryService> journalEntryServiceProvider;

  ReconciliationOperations(
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      AccountRepository accountRepository,
      DealerRepository dealerRepository,
      DealerLedgerRepository dealerLedgerRepository,
      SupplierRepository supplierRepository,
      SupplierLedgerRepository supplierLedgerRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      TemporalBalanceService temporalBalanceService,
      ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
      AccountingPeriodRepository accountingPeriodRepository,
      TaxService taxService,
      ReportService reportService,
      ObjectProvider<JournalEntryService> journalEntryServiceProvider) {
    this.companyContextService = companyContextService;
    this.companyRepository = companyRepository;
    this.accountRepository = accountRepository;
    this.dealerRepository = dealerRepository;
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.supplierRepository = supplierRepository;
    this.supplierLedgerRepository = supplierLedgerRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.temporalBalanceService = temporalBalanceService;
    this.reconciliationDiscrepancyRepository = reconciliationDiscrepancyRepository;
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.taxService = taxService;
    this.reportService = reportService;
    this.journalEntryServiceProvider = journalEntryServiceProvider;
  }

  /**
   * Reconcile AR GL account balance with sum of dealer ledger balances.
   * Returns discrepancies if any.
   */
  ReconciliationResult reconcileArWithDealerLedger() {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
    List<Account> arAccounts = resolveReceivableAccounts(allAccounts, dealers);

    BigDecimal totalArBalance =
        arAccounts.stream()
            .map(Account::getBalance)
            .filter(b -> b != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Get all dealers with their ledger balances
    List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();

    Map<Long, BigDecimal> dealerBalances =
        dealerLedgerRepository.aggregateBalances(company, dealerIds).stream()
            .collect(Collectors.toMap(DealerBalanceView::dealerId, DealerBalanceView::balance));

    BigDecimal totalDealerLedgerBalance =
        dealerBalances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal variance = totalArBalance.subtract(totalDealerLedgerBalance);
    boolean isReconciled = variance.abs().compareTo(TOLERANCE) <= 0;

    List<DealerDiscrepancy> discrepancies = new ArrayList<>();

    // Check individual dealers
    for (Dealer dealer : dealers) {
      BigDecimal ledgerBalance = dealerBalances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
      BigDecimal outstandingBalance =
          dealer.getOutstandingBalance() != null ? dealer.getOutstandingBalance() : BigDecimal.ZERO;

      BigDecimal dealerVariance = outstandingBalance.subtract(ledgerBalance);
      if (dealerVariance.abs().compareTo(TOLERANCE) > 0) {
        discrepancies.add(
            new DealerDiscrepancy(
                dealer.getId(),
                dealer.getCode(),
                dealer.getName(),
                outstandingBalance,
                ledgerBalance,
                dealerVariance));
      }
    }

    log.info(
        "AR Reconciliation: GL={}, DealerLedger={}, Variance={}, Reconciled={}",
        totalArBalance,
        totalDealerLedgerBalance,
        variance,
        isReconciled);

    return new ReconciliationResult(
        totalArBalance,
        totalDealerLedgerBalance,
        variance,
        isReconciled,
        discrepancies,
        arAccounts.size(),
        dealers.size());
  }

  /**
   * Reconcile AP GL account balance with sum of supplier ledger balances.
   */
  SupplierReconciliationResult reconcileApWithSupplierLedger() {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<Supplier> suppliers =
        supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company);
    List<Account> apAccounts = resolvePayableAccounts(allAccounts, suppliers);

    BigDecimal totalApBalance =
        apAccounts.stream()
            .map(Account::getBalance)
            .filter(b -> b != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    // Account.balance uses a debit-positive convention (debit - credit). For AP we reconcile
    // against the
    // supplier sub-ledger which is aggregated as (credit - debit), so normalize the GL balance
    // here.
    BigDecimal glApBalance = totalApBalance.negate();

    List<Long> supplierIds = suppliers.stream().map(Supplier::getId).toList();

    Map<Long, BigDecimal> supplierBalances =
        supplierLedgerRepository.aggregateBalances(company, supplierIds).stream()
            .collect(
                Collectors.toMap(SupplierBalanceView::supplierId, SupplierBalanceView::balance));

    BigDecimal totalSupplierLedgerBalance =
        supplierBalances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal variance = glApBalance.subtract(totalSupplierLedgerBalance);
    boolean isReconciled = variance.abs().compareTo(TOLERANCE) <= 0;

    List<SupplierDiscrepancy> discrepancies = new ArrayList<>();

    for (Supplier supplier : suppliers) {
      BigDecimal ledgerBalance = supplierBalances.getOrDefault(supplier.getId(), BigDecimal.ZERO);
      Account payableAccount = supplier.getPayableAccount();
      BigDecimal supplierPayableAccountBalance =
          payableAccount != null && payableAccount.getBalance() != null
              ? payableAccount.getBalance().negate()
              : BigDecimal.ZERO;

      BigDecimal supplierVariance = supplierPayableAccountBalance.subtract(ledgerBalance);
      if (supplierVariance.abs().compareTo(TOLERANCE) > 0) {
        discrepancies.add(
            new SupplierDiscrepancy(
                supplier.getId(),
                supplier.getCode(),
                supplier.getName(),
                supplierPayableAccountBalance,
                ledgerBalance,
                supplierVariance));
      }
    }

    log.info(
        "AP Reconciliation: GL={}, SupplierLedger={}, Variance={}, Reconciled={}",
        glApBalance,
        totalSupplierLedgerBalance,
        variance,
        isReconciled);

    return new SupplierReconciliationResult(
        glApBalance,
        totalSupplierLedgerBalance,
        variance,
        isReconciled,
        discrepancies,
        apAccounts.size(),
        suppliers.size());
  }

  BankReconciliationSummaryDto reconcileBankAccount(
      Long bankAccountId,
      LocalDate statementDate,
      BigDecimal statementEndingBalanceInput,
      LocalDate startDate,
      LocalDate endDate,
      Set<Long> clearedJournalLineIds,
      Set<String> clearedReferenceNumbers) {
    if (bankAccountId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "bankAccountId is required");
    }
    if (statementDate == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "statementDate is required");
    }
    if (statementEndingBalanceInput == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "statementEndingBalance is required");
    }

    Company company = companyContextService.requireCurrentCompany();
    Account account =
        accountRepository
            .findByCompanyAndId(company, bankAccountId)
            .orElseThrow(
                () ->
                    new com.bigbrightpaints.erp.core.exception.ApplicationException(
                        com.bigbrightpaints.erp.core.exception.ErrorCode
                            .VALIDATION_INVALID_REFERENCE,
                        "Bank account not found"));

    if (account.getType() != null && account.getType() != AccountType.ASSET) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Bank reconciliation account must be an ASSET account");
    }

    LocalDate start = startDate != null ? startDate : statementDate.withDayOfMonth(1);
    LocalDate end = endDate != null ? endDate : statementDate;
    if (start.isAfter(end)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "startDate must be on or before endDate");
    }

    Set<Long> clearedLineIds =
        clearedJournalLineIds == null
            ? Collections.emptySet()
            : clearedJournalLineIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    Set<String> clearedReferences =
        clearedReferenceNumbers == null
            ? Collections.emptySet()
            : clearedReferenceNumbers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());

    List<JournalLine> lines =
        journalLineRepository.findLinesForAccountBetween(company, account.getId(), start, end);
    List<BankReconciliationSummaryDto.BankReconciliationItemDto> unclearedDeposits =
        new ArrayList<>();
    List<BankReconciliationSummaryDto.BankReconciliationItemDto> unclearedChecks =
        new ArrayList<>();

    BigDecimal outstandingDeposits = BigDecimal.ZERO;
    BigDecimal outstandingChecks = BigDecimal.ZERO;

    for (JournalLine line : lines) {
      JournalEntry entry = line.getJournalEntry();
      String reference = entry != null ? entry.getReferenceNumber() : null;
      if (isJournalLineCleared(line, reference, clearedLineIds, clearedReferences)) {
        continue;
      }

      BigDecimal debit = safe(line.getDebit());
      BigDecimal credit = safe(line.getCredit());
      BigDecimal net = debit.subtract(credit);

      BankReconciliationSummaryDto.BankReconciliationItemDto item =
          new BankReconciliationSummaryDto.BankReconciliationItemDto(
              entry != null ? entry.getId() : null,
              reference,
              entry != null ? entry.getEntryDate() : null,
              entry != null ? entry.getMemo() : null,
              debit,
              credit,
              net);

      if (net.compareTo(BigDecimal.ZERO) > 0) {
        outstandingDeposits = outstandingDeposits.add(net);
        unclearedDeposits.add(item);
      } else if (net.compareTo(BigDecimal.ZERO) < 0) {
        BigDecimal checkAmount = net.abs();
        outstandingChecks = outstandingChecks.add(checkAmount);
        unclearedChecks.add(item);
      }
    }

    BigDecimal ledgerBalance =
        safe(temporalBalanceService.getBalanceAsOfDate(account.getId(), end));
    BigDecimal statementEndingBalance = safe(statementEndingBalanceInput);
    BigDecimal adjustedStatementBalance =
        statementEndingBalance.add(outstandingDeposits).subtract(outstandingChecks);
    BigDecimal difference = ledgerBalance.subtract(adjustedStatementBalance);
    boolean balanced = difference.abs().compareTo(TOLERANCE) <= 0;

    return new BankReconciliationSummaryDto(
        account.getId(),
        account.getCode(),
        account.getName(),
        statementDate,
        ledgerBalance,
        statementEndingBalance,
        outstandingDeposits,
        outstandingChecks,
        difference,
        balanced,
        unclearedDeposits,
        unclearedChecks);
  }

  SubledgerReconciliationReport reconcileSubledgerBalances() {
    Company company = companyContextService.requireCurrentCompany();
    ReconciliationResult dealerReconciliation = reconcileArWithDealerLedger();
    SupplierReconciliationResult supplierReconciliation = reconcileApWithSupplierLedger();
    BigDecimal combinedVariance =
        safe(dealerReconciliation.variance()).add(safe(supplierReconciliation.variance()));
    boolean reconciled =
        dealerReconciliation.isReconciled() && supplierReconciliation.isReconciled();

    syncCurrentOpenPeriodDiscrepancies(company);

    return new SubledgerReconciliationReport(
        dealerReconciliation, supplierReconciliation, combinedVariance, reconciled);
  }

  GstReconciliationDto generateGstReconciliation(YearMonth period) {
    return taxService.generateGstReconciliation(period);
  }

  InterCompanyReconciliationReport interCompanyReconcile(Long companyAId, Long companyBId) {
    Company activeCompany = companyContextService.requireCurrentCompany();
    Long activeCompanyId = activeCompany != null ? activeCompany.getId() : null;
    if (activeCompanyId == null) {
      throw ValidationUtils.invalidState(
          "Active company must be persisted for inter-company reconciliation");
    }

    if (companyAId == null && companyBId == null) {
      throw ValidationUtils.invalidInput(
          "companyA or companyB is required for inter-company reconciliation");
    } else {
      if (companyAId == null) {
        companyAId = activeCompanyId;
      }
      if (companyBId == null) {
        companyBId = activeCompanyId;
      }
    }

    if (Objects.equals(companyAId, companyBId)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "companyA and companyB must be different companies");
    }

    if (!Objects.equals(companyAId, activeCompanyId)
        && !Objects.equals(companyBId, activeCompanyId)) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Inter-company reconciliation must include the active company")
          .withDetail("activeCompanyId", activeCompanyId)
          .withDetail("companyA", companyAId)
          .withDetail("companyB", companyBId);
    }
    final Long resolvedCompanyAId = companyAId;
    final Long resolvedCompanyBId = companyBId;

    Company companyA =
        Objects.equals(resolvedCompanyAId, activeCompanyId)
            ? activeCompany
            : companyRepository
                .findById(resolvedCompanyAId)
                .orElseThrow(
                    () ->
                        com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                            "Company not found: " + resolvedCompanyAId));
    Company companyB =
        Objects.equals(resolvedCompanyBId, activeCompanyId)
            ? activeCompany
            : companyRepository
                .findById(resolvedCompanyBId)
                .orElseThrow(
                    () ->
                        com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                            "Company not found: " + resolvedCompanyBId));

    InterCompanyReconciliationItem aReceivableVsBPayable =
        reconcileInterCompanyDirection(companyA, companyB);
    InterCompanyReconciliationItem bReceivableVsAPayable =
        reconcileInterCompanyDirection(companyB, companyA);

    List<InterCompanyReconciliationItem> allItems =
        List.of(aReceivableVsBPayable, bReceivableVsAPayable);

    List<InterCompanyReconciliationItem> matchedItems =
        allItems.stream().filter(InterCompanyReconciliationItem::matched).toList();
    List<InterCompanyReconciliationItem> unmatchedItems =
        allItems.stream().filter(item -> !item.matched()).toList();

    BigDecimal totalDiscrepancyAmount =
        allItems.stream()
            .map(item -> safe(item.discrepancyAmount()).abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new InterCompanyReconciliationReport(
        companyA.getId(),
        companyA.getCode(),
        companyB.getId(),
        companyB.getCode(),
        matchedItems,
        unmatchedItems,
        totalDiscrepancyAmount,
        unmatchedItems.isEmpty());
  }

  PeriodReconciliationResult reconcileSubledgersForPeriod(
      java.time.LocalDate start, java.time.LocalDate end) {
    Company company = companyContextService.requireCurrentCompany();
    if (start == null || end == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "start and end dates are required");
    }
    if (start.isAfter(end)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "start date must be on or before end date");
    }
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
    List<Account> arAccounts = resolveReceivableAccounts(allAccounts, dealers);
    List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();
    BigDecimal dealerLedgerNet =
        dealerIds.isEmpty()
            ? BigDecimal.ZERO
            : dealerLedgerRepository
                .aggregateBalancesBetween(company, dealerIds, start, end)
                .stream()
                .map(DealerBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<Supplier> suppliers =
        supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company);
    List<Account> apAccounts = resolvePayableAccounts(allAccounts, suppliers);
    List<Long> supplierIds = suppliers.stream().map(Supplier::getId).toList();
    BigDecimal supplierLedgerNet =
        supplierIds.isEmpty()
            ? BigDecimal.ZERO
            : supplierLedgerRepository
                .aggregateBalancesBetween(company, supplierIds, start, end)
                .stream()
                .map(SupplierBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal glArNet = sumAccountNet(company, arAccounts, start, end, true);
    BigDecimal glApNet = sumAccountNet(company, apAccounts, start, end, false);

    BigDecimal arVariance = glArNet.subtract(dealerLedgerNet);
    BigDecimal apVariance = glApNet.subtract(supplierLedgerNet);
    boolean arReconciled = arVariance.abs().compareTo(TOLERANCE) <= 0;
    boolean apReconciled = apVariance.abs().compareTo(TOLERANCE) <= 0;

    return new PeriodReconciliationResult(
        start,
        end,
        glArNet,
        dealerLedgerNet,
        arVariance,
        arReconciled,
        glApNet,
        supplierLedgerNet,
        apVariance,
        apReconciled);
  }

  private BigDecimal sumAccountNet(
      Company company,
      List<Account> accounts,
      java.time.LocalDate start,
      java.time.LocalDate end,
      boolean receivable) {
    if (accounts == null || accounts.isEmpty()) {
      return BigDecimal.ZERO;
    }
    List<Long> accountIds = accounts.stream().map(Account::getId).filter(Objects::nonNull).toList();
    if (accountIds.isEmpty()) {
      return BigDecimal.ZERO;
    }

    Collection<JournalLineRepository.AccountLineTotals> totalsByAccount =
        journalLineRepository.summarizeTotalsByCompanyAndAccountIdsWithin(
            company, accountIds, start, end, JournalEntryStatus.POSTED);
    if (totalsByAccount == null || totalsByAccount.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal net =
        totalsByAccount.stream()
            .map(
                total -> {
                  BigDecimal debit = safe(total.getTotalDebit());
                  BigDecimal credit = safe(total.getTotalCredit());
                  return receivable ? debit.subtract(credit) : credit.subtract(debit);
                })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return safe(net);
  }

  private InterCompanyReconciliationItem reconcileInterCompanyDirection(
      Company receivableCompany, Company payableCompany) {
    String payableCompanyCode = normalizeCode(payableCompany.getCode());
    String receivableCompanyCode = normalizeCode(receivableCompany.getCode());

    Optional<Dealer> receivableDealer =
        payableCompanyCode == null
            ? Optional.empty()
            : dealerRepository.findByCompanyAndCodeIgnoreCase(
                receivableCompany, payableCompanyCode);
    Optional<Supplier> payableSupplier =
        receivableCompanyCode == null
            ? Optional.empty()
            : supplierRepository.findByCompanyAndCodeIgnoreCase(
                payableCompany, receivableCompanyCode);

    BigDecimal receivableAmount =
        receivableDealer.map(Dealer::getOutstandingBalance).map(this::safe).orElse(BigDecimal.ZERO);
    BigDecimal payableAmount =
        payableSupplier
            .map(supplier -> resolveSupplierLedgerBalance(payableCompany, supplier))
            .map(this::safe)
            .orElse(BigDecimal.ZERO);

    BigDecimal discrepancyAmount = receivableAmount.subtract(payableAmount);
    boolean counterpartyMissing = receivableDealer.isEmpty() || payableSupplier.isEmpty();
    boolean matched = !counterpartyMissing && discrepancyAmount.abs().compareTo(TOLERANCE) <= 0;

    return new InterCompanyReconciliationItem(
        receivableCompany.getId(),
        normalizeCode(receivableCompany.getCode()),
        payableCompany.getId(),
        normalizeCode(payableCompany.getCode()),
        receivableDealer.map(Dealer::getId).orElse(null),
        payableSupplier.map(Supplier::getId).orElse(null),
        receivableAmount,
        payableAmount,
        discrepancyAmount,
        matched,
        counterpartyMissing);
  }

  private String normalizeCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    return code.trim();
  }

  private BigDecimal resolveSupplierLedgerBalance(Company company, Supplier supplier) {
    return supplierLedgerRepository
        .aggregateBalance(company, supplier)
        .map(SupplierBalanceView::balance)
        .orElse(BigDecimal.ZERO);
  }

  private List<Account> resolveReceivableAccounts(List<Account> accounts, List<Dealer> dealers) {
    if (accounts == null || accounts.isEmpty()) {
      return List.of();
    }
    Set<Long> receivableIds =
        dealers == null
            ? Set.of()
            : dealers.stream()
                .map(Dealer::getReceivableAccount)
                .filter(Objects::nonNull)
                .map(Account::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    if (!receivableIds.isEmpty()) {
      return accounts.stream()
          .filter(account -> account.getId() != null && receivableIds.contains(account.getId()))
          .toList();
    }
    return accounts.stream()
        .filter(account -> account.getType() == AccountType.ASSET)
        .filter(this::isReceivableCandidate)
        .toList();
  }

  private List<Account> resolvePayableAccounts(List<Account> accounts, List<Supplier> suppliers) {
    if (accounts == null || accounts.isEmpty()) {
      return List.of();
    }
    Set<Long> payableIds =
        suppliers == null
            ? Set.of()
            : suppliers.stream()
                .map(Supplier::getPayableAccount)
                .filter(Objects::nonNull)
                .map(Account::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    if (!payableIds.isEmpty()) {
      return accounts.stream()
          .filter(account -> account.getId() != null && payableIds.contains(account.getId()))
          .toList();
    }
    return accounts.stream()
        .filter(account -> account.getType() == AccountType.LIABILITY)
        .filter(this::isPayableCandidate)
        .toList();
  }

  private boolean isReceivableCandidate(Account account) {
    if (account == null || account.getCode() == null) {
      return false;
    }
    String code = account.getCode().toUpperCase();
    String name = account.getName() == null ? "" : account.getName().toUpperCase();
    return code.contains("AR") || name.contains("ACCOUNTS RECEIVABLE");
  }

  private boolean isPayableCandidate(Account account) {
    if (account == null || account.getCode() == null) {
      return false;
    }
    String code = account.getCode().toUpperCase();
    String name = account.getName() == null ? "" : account.getName().toUpperCase();
    return code.contains("AP") || name.contains("ACCOUNTS PAYABLE");
  }

  private Set<String> normalizeReferences(List<String> references) {
    if (references == null || references.isEmpty()) {
      return Collections.emptySet();
    }
    return references.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toUpperCase(java.util.Locale.ROOT))
        .collect(Collectors.toSet());
  }

  private boolean isReferenceCleared(String reference, Set<String> clearedReferences) {
    if (clearedReferences == null || clearedReferences.isEmpty() || reference == null) {
      return false;
    }
    return clearedReferences.contains(reference.trim().toUpperCase(java.util.Locale.ROOT));
  }

  private boolean isJournalLineCleared(
      JournalLine line, String reference, Set<Long> clearedLineIds, Set<String> clearedReferences) {
    if (line != null
        && line.getId() != null
        && clearedLineIds != null
        && clearedLineIds.contains(line.getId())) {
      return true;
    }
    return isReferenceCleared(reference, clearedReferences);
  }

  ReconciliationDiscrepancyListResponse listDiscrepancies(
      ReconciliationDiscrepancyStatus status, ReconciliationDiscrepancyType type) {
    Company company = companyContextService.requireCurrentCompany();
    List<ReconciliationDiscrepancy> items =
        reconciliationDiscrepancyRepository.findFiltered(company, status, type);
    long openCount =
        items.stream()
            .filter(item -> item.getStatus() == ReconciliationDiscrepancyStatus.OPEN)
            .count();
    long resolvedCount = items.stream().filter(this::isResolvedOrAcknowledged).count();
    return new ReconciliationDiscrepancyListResponse(
        items.stream().map(this::toDto).toList(), openCount, resolvedCount);
  }

  ReconciliationDiscrepancyDto resolveDiscrepancy(
      Long discrepancyId, ReconciliationDiscrepancyResolveRequest request) {
    ValidationUtils.requireNotNull(discrepancyId, "discrepancyId");
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.resolution(), "resolution");

    Company company = companyContextService.requireCurrentCompany();
    ReconciliationDiscrepancy discrepancy =
        reconciliationDiscrepancyRepository
            .findByCompanyAndId(company, discrepancyId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Reconciliation discrepancy not found: " + discrepancyId));

    if (discrepancy.getStatus() != ReconciliationDiscrepancyStatus.OPEN) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Only OPEN discrepancies can be resolved");
    }

    String note = normalizeNote(request.note());
    String actor = resolveCurrentActor();
    Instant resolvedAt = CompanyTime.now(company);

    switch (request.resolution()) {
      case ACKNOWLEDGED ->
          applyAcknowledgedResolution(
              discrepancy,
              ReconciliationDiscrepancyResolution.ACKNOWLEDGED,
              ReconciliationDiscrepancyStatus.ACKNOWLEDGED,
              note,
              actor,
              resolvedAt);
      case CORRECTION ->
          applyAcknowledgedResolution(
              discrepancy,
              ReconciliationDiscrepancyResolution.CORRECTION,
              ReconciliationDiscrepancyStatus.RESOLVED,
              note,
              actor,
              resolvedAt);
      case ADJUSTMENT_JOURNAL, ADJUSTMENT ->
          applyAdjustmentResolution(company, discrepancy, request, note, actor, resolvedAt);
      case WRITE_OFF ->
          applyWriteOffResolution(company, discrepancy, request, note, actor, resolvedAt);
      default ->
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Unsupported discrepancy resolution: " + request.resolution());
    }

    ReconciliationDiscrepancy saved = reconciliationDiscrepancyRepository.save(discrepancy);
    return toDto(saved);
  }

  private void applyAcknowledgedResolution(
      ReconciliationDiscrepancy discrepancy,
      ReconciliationDiscrepancyResolution resolution,
      ReconciliationDiscrepancyStatus status,
      String note,
      String actor,
      Instant resolvedAt) {
    discrepancy.setResolution(resolution);
    discrepancy.setStatus(status);
    discrepancy.setResolutionNote(note);
    discrepancy.setResolvedBy(actor);
    discrepancy.setResolvedAt(resolvedAt);
    discrepancy.setResolutionJournal(null);
  }

  private void applyAdjustmentResolution(
      Company company,
      ReconciliationDiscrepancy discrepancy,
      ReconciliationDiscrepancyResolveRequest request,
      String note,
      String actor,
      Instant resolvedAt) {
    JournalEntry resolutionJournal =
        resolveOrCreateResolutionJournal(
            company,
            discrepancy,
            request,
            "RECON_DISCREPANCY_ADJUSTMENT",
            request.resolution(),
            note);

    discrepancy.setResolution(
        request.resolution() == ReconciliationDiscrepancyResolution.ADJUSTMENT
            ? ReconciliationDiscrepancyResolution.ADJUSTMENT
            : ReconciliationDiscrepancyResolution.ADJUSTMENT_JOURNAL);
    discrepancy.setStatus(ReconciliationDiscrepancyStatus.RESOLVED);
    discrepancy.setResolutionNote(note);
    discrepancy.setResolutionJournal(resolutionJournal);
    discrepancy.setResolvedBy(actor);
    discrepancy.setResolvedAt(resolvedAt);
  }

  private void applyWriteOffResolution(
      Company company,
      ReconciliationDiscrepancy discrepancy,
      ReconciliationDiscrepancyResolveRequest request,
      String note,
      String actor,
      Instant resolvedAt) {
    JournalEntry resolutionJournal =
        resolveOrCreateResolutionJournal(
            company,
            discrepancy,
            request,
            "RECON_DISCREPANCY_WRITE_OFF",
            request.resolution(),
            note);

    discrepancy.setResolution(ReconciliationDiscrepancyResolution.WRITE_OFF);
    discrepancy.setStatus(ReconciliationDiscrepancyStatus.RESOLVED);
    discrepancy.setResolutionNote(note);
    discrepancy.setResolutionJournal(resolutionJournal);
    discrepancy.setResolvedBy(actor);
    discrepancy.setResolvedAt(resolvedAt);
  }

  private JournalEntry resolveOrCreateResolutionJournal(
      Company company,
      ReconciliationDiscrepancy discrepancy,
      ReconciliationDiscrepancyResolveRequest request,
      String sourceModule,
      ReconciliationDiscrepancyResolution resolution,
      String note) {
    if (request.journalEntryId() != null) {
      return requireResolutionJournal(company, request.journalEntryId());
    }
    Account adjustmentAccount =
        requireResolutionAccount(company, request.adjustmentAccountId(), request.resolution());
    return createResolutionJournal(
        company, discrepancy, adjustmentAccount, sourceModule, resolution, note);
  }

  private JournalEntry createResolutionJournal(
      Company company,
      ReconciliationDiscrepancy discrepancy,
      Account adjustmentAccount,
      String sourceModule,
      ReconciliationDiscrepancyResolution resolution,
      String note) {
    BigDecimal varianceAmount = safe(discrepancy.getVariance()).abs();
    if (varianceAmount.compareTo(TOLERANCE) <= 0) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
          "Discrepancy variance must be above tolerance for journal resolutions");
    }

    Long controlAccountId = resolveControlAccountId(company, discrepancy.getType());
    String reference = buildResolutionReference(discrepancy, resolution);
    String narration = buildResolutionNarration(discrepancy, note, resolution);

    JournalCreationRequest request =
        new JournalCreationRequest(
            varianceAmount,
            controlAccountId,
            adjustmentAccount.getId(),
            narration,
            sourceModule,
            reference,
            null,
            null,
            CompanyTime.today(company),
            discrepancy.getPartnerType() == PartnerType.DEALER ? discrepancy.getPartnerId() : null,
            discrepancy.getPartnerType() == PartnerType.SUPPLIER
                ? discrepancy.getPartnerId()
                : null,
            Boolean.FALSE);

    JournalEntryDto created = journalEntryServiceProvider.getObject().createStandardJournal(request);
    if (created == null || created.id() == null) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_INTERNAL_ERROR, "Failed to create discrepancy resolution journal");
    }
    return journalEntryRepository
        .findByCompanyAndId(company, created.id())
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.SYSTEM_INTERNAL_ERROR,
                    "Resolution journal was not found after creation"));
  }

  private Account requireResolutionAccount(
      Company company, Long accountId, ReconciliationDiscrepancyResolution resolution) {
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "adjustmentAccountId is required for " + resolution.name());
    }
    return accountRepository
        .findByCompanyAndId(company, accountId)
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Adjustment account not found: " + accountId));
  }

  private JournalEntry requireResolutionJournal(Company company, Long journalEntryId) {
    return journalEntryRepository
        .findByCompanyAndId(company, journalEntryId)
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Resolution journal not found: " + journalEntryId));
  }

  private Long resolveControlAccountId(Company company, ReconciliationDiscrepancyType type) {
    return switch (type) {
      case AR ->
          accountRepository
              .findByCompanyAndCodeIgnoreCase(company, "AR")
              .map(Account::getId)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                          ErrorCode.VALIDATION_INVALID_REFERENCE,
                          "AR control account not configured"));
      case AP ->
          accountRepository
              .findByCompanyAndCodeIgnoreCase(company, "AP")
              .map(Account::getId)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                          ErrorCode.VALIDATION_INVALID_REFERENCE,
                          "AP control account not configured"));
      case INVENTORY ->
          accountRepository
              .findByCompanyAndCodeIgnoreCase(company, "INV")
              .map(Account::getId)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                          ErrorCode.VALIDATION_INVALID_REFERENCE,
                          "Inventory control account not configured"));
      case GST -> {
        Long gstPayableAccountId = company.getGstPayableAccountId();
        if (gstPayableAccountId == null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "GST payable account not configured");
        }
        yield accountRepository
            .findByCompanyAndId(company, gstPayableAccountId)
            .map(Account::getId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "GST payable account not configured"));
      }
    };
  }

  private String buildResolutionReference(
      ReconciliationDiscrepancy discrepancy, ReconciliationDiscrepancyResolution resolution) {
    long id = discrepancy.getId() == null ? 0L : discrepancy.getId();
    return "RECON-" + resolution.name() + "-" + id;
  }

  private String buildResolutionNarration(
      ReconciliationDiscrepancy discrepancy,
      String note,
      ReconciliationDiscrepancyResolution resolution) {
    String prefix =
        switch (resolution) {
          case ADJUSTMENT_JOURNAL, ADJUSTMENT -> "Reconciliation adjustment";
          case WRITE_OFF -> "Reconciliation write-off";
          case ACKNOWLEDGED, CORRECTION -> "Reconciliation acknowledgement";
        };
    String base = prefix + " for " + discrepancy.getType();
    if (!StringUtils.hasText(note)) {
      return base;
    }
    return base + " - " + note;
  }

  void syncPeriodDiscrepancies(
      Company company,
      AccountingPeriod period,
      PeriodReconciliationResult periodReconciliation,
      ReconciliationSummaryDto inventorySummary,
      GstReconciliationDto gstSummary) {
    if (company == null || period == null || periodReconciliation == null) {
      return;
    }

    upsertArAndApDiscrepancies(company, periodReconciliation, period);
    upsertInventoryDiscrepancies(company, period, inventorySummary);
    upsertGstDiscrepancies(company, period, gstSummary);
  }

  private void upsertArAndApDiscrepancies(
      Company company, PeriodReconciliationResult periodReconciliation, AccountingPeriod period) {
    if (period == null) {
      return;
    }

    if (periodReconciliation.arVariance().abs().compareTo(TOLERANCE) > 0) {
      saveOrReplaceOpenDiscrepancy(
          company,
          period,
          ReconciliationDiscrepancyType.AR,
          periodReconciliation.glArNet(),
          periodReconciliation.dealerLedgerNet(),
          periodReconciliation.arVariance(),
          PartnerType.DEALER,
          null,
          null,
          null);
    } else {
      reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
          company, period, ReconciliationDiscrepancyType.AR, ReconciliationDiscrepancyStatus.OPEN);
    }

    if (periodReconciliation.apVariance().abs().compareTo(TOLERANCE) > 0) {
      saveOrReplaceOpenDiscrepancy(
          company,
          period,
          ReconciliationDiscrepancyType.AP,
          periodReconciliation.glApNet(),
          periodReconciliation.supplierLedgerNet(),
          periodReconciliation.apVariance(),
          PartnerType.SUPPLIER,
          null,
          null,
          null);
    } else {
      reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
          company, period, ReconciliationDiscrepancyType.AP, ReconciliationDiscrepancyStatus.OPEN);
    }
  }

  private void upsertInventoryDiscrepancies(
      Company company, AccountingPeriod period, ReconciliationSummaryDto inventorySummary) {
    if (inventorySummary == null
        || inventorySummary.variance() == null
        || inventorySummary.variance().abs().compareTo(TOLERANCE) <= 0) {
      reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
          company,
          period,
          ReconciliationDiscrepancyType.INVENTORY,
          ReconciliationDiscrepancyStatus.OPEN);
      return;
    }

    saveOrReplaceOpenDiscrepancy(
        company,
        period,
        ReconciliationDiscrepancyType.INVENTORY,
        safe(inventorySummary.ledgerInventoryBalance()),
        safe(inventorySummary.physicalInventoryValue()),
        safe(inventorySummary.variance()),
        null,
        null,
        null,
        null);
  }

  private void upsertGstDiscrepancies(
      Company company, AccountingPeriod period, GstReconciliationDto gstSummary) {
    if (gstSummary == null
        || gstSummary.getNetLiability() == null
        || gstSummary.getNetLiability().getTotal() == null
        || gstSummary.getNetLiability().getTotal().abs().compareTo(TOLERANCE) <= 0) {
      reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
          company, period, ReconciliationDiscrepancyType.GST, ReconciliationDiscrepancyStatus.OPEN);
      return;
    }

    BigDecimal expected =
        safe(gstSummary.getCollected() != null ? gstSummary.getCollected().getTotal() : null);
    BigDecimal actual =
        safe(
            gstSummary.getInputTaxCredit() != null
                ? gstSummary.getInputTaxCredit().getTotal()
                : null);
    BigDecimal variance = safe(gstSummary.getNetLiability().getTotal());
    saveOrReplaceOpenDiscrepancy(
        company,
        period,
        ReconciliationDiscrepancyType.GST,
        expected,
        actual,
        variance,
        null,
        null,
        null,
        null);
  }

  private void saveOrReplaceOpenDiscrepancy(
      Company company,
      AccountingPeriod period,
      ReconciliationDiscrepancyType type,
      BigDecimal expected,
      BigDecimal actual,
      BigDecimal variance,
      PartnerType partnerType,
      Long partnerId,
      String partnerCode,
      String partnerName) {
    if (variance == null || variance.abs().compareTo(TOLERANCE) <= 0) {
      return;
    }

    reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
        company, period, type, ReconciliationDiscrepancyStatus.OPEN);

    ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
    discrepancy.setCompany(company);
    discrepancy.setAccountingPeriod(period);
    discrepancy.setPeriodStart(period.getStartDate());
    discrepancy.setPeriodEnd(period.getEndDate());
    discrepancy.setType(type);
    discrepancy.setPartnerType(partnerType);
    discrepancy.setPartnerId(partnerId);
    discrepancy.setPartnerCode(partnerCode);
    discrepancy.setPartnerName(partnerName);
    discrepancy.setExpectedAmount(safe(expected));
    discrepancy.setActualAmount(safe(actual));
    discrepancy.setVariance(safe(variance));
    discrepancy.setStatus(ReconciliationDiscrepancyStatus.OPEN);
    reconciliationDiscrepancyRepository.save(discrepancy);
  }

  private void syncCurrentOpenPeriodDiscrepancies(Company company) {
    Optional<AccountingPeriod> maybeOpenPeriod =
        accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(
            company, AccountingPeriodStatus.OPEN);
    if (maybeOpenPeriod.isEmpty()) {
      log.debug(
          "Skipping reconciliation discrepancy sync because no open period exists for company {}",
          company != null ? company.getCode() : "UNKNOWN");
      return;
    }

    AccountingPeriod period = maybeOpenPeriod.get();
    PeriodReconciliationResult base =
        reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate());
    ReconciliationSummaryDto inventorySummary = null;
    GstReconciliationDto gstSummary = null;
    try {
      inventorySummary = buildInventorySummary(company, period);
    } catch (Exception ex) {
      log.warn("Inventory reconciliation summary unavailable during discrepancy sync", ex);
    }
    try {
      gstSummary = taxService.generateGstReconciliation(YearMonth.from(period.getStartDate()));
    } catch (Exception ex) {
      log.warn("GST reconciliation summary unavailable during discrepancy sync", ex);
    }

    syncPeriodDiscrepancies(company, period, base, inventorySummary, gstSummary);
  }

  private ReconciliationSummaryDto buildInventorySummary(Company company, AccountingPeriod period) {
    InventoryValuationDto valuation = reportService.inventoryValuationAsOf(period.getEndDate());
    BigDecimal physicalValue = safe(valuation != null ? valuation.totalValue() : null);

    BigDecimal ledgerValue = BigDecimal.ZERO;
    Long inventoryAccountId = company.getDefaultInventoryAccountId();
    if (inventoryAccountId != null) {
      ledgerValue =
          accountRepository
              .findByCompanyAndId(company, inventoryAccountId)
              .map(Account::getBalance)
              .map(this::safe)
              .orElse(BigDecimal.ZERO);
    }

    BigDecimal variance = physicalValue.subtract(ledgerValue);
    return new ReconciliationSummaryDto(physicalValue, ledgerValue, variance);
  }

  private String normalizeNote(String note) {
    if (!StringUtils.hasText(note)) {
      return null;
    }
    return note.trim();
  }

  private String resolveCurrentActor() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  private boolean isResolvedOrAcknowledged(ReconciliationDiscrepancy discrepancy) {
    if (discrepancy == null || discrepancy.getStatus() == null) {
      return false;
    }
    return EnumSet.of(
            ReconciliationDiscrepancyStatus.ACKNOWLEDGED,
            ReconciliationDiscrepancyStatus.ADJUSTED,
            ReconciliationDiscrepancyStatus.RESOLVED)
        .contains(discrepancy.getStatus());
  }

  private ReconciliationDiscrepancyDto toDto(ReconciliationDiscrepancy discrepancy) {
    return new ReconciliationDiscrepancyDto(
        discrepancy.getId(),
        discrepancy.getAccountingPeriod() != null
            ? discrepancy.getAccountingPeriod().getId()
            : null,
        discrepancy.getPeriodStart(),
        discrepancy.getPeriodEnd(),
        discrepancy.getType() != null ? discrepancy.getType().name() : null,
        discrepancy.getPartnerType() != null ? discrepancy.getPartnerType().name() : null,
        discrepancy.getPartnerId(),
        discrepancy.getPartnerCode(),
        discrepancy.getPartnerName(),
        discrepancy.getExpectedAmount(),
        discrepancy.getActualAmount(),
        discrepancy.getVariance(),
        discrepancy.getStatus() != null ? discrepancy.getStatus().name() : null,
        discrepancy.getResolution() != null ? discrepancy.getResolution().name() : null,
        discrepancy.getResolutionNote(),
        discrepancy.getResolutionJournal() != null
            ? discrepancy.getResolutionJournal().getId()
            : null,
        discrepancy.getResolvedBy(),
        discrepancy.getResolvedAt(),
        discrepancy.getCreatedAt(),
        discrepancy.getUpdatedAt());
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
