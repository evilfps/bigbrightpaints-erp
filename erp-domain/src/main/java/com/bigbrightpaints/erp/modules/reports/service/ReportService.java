package com.bigbrightpaints.erp.modules.reports.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReportBreakdownDto;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.CostComponentTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackedBatchTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.RawMaterialTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import com.bigbrightpaints.erp.modules.factory.service.CompanyScopedFactoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryPhysicalCountService;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
public class ReportService {

  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final AccountingPeriodRepository accountingPeriodRepository;
  private final AccountingPeriodSnapshotRepository snapshotRepository;
  private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
  private final DealerRepository dealerRepository;
  private final DealerLedgerService dealerLedgerService;
  private final DealerLedgerRepository dealerLedgerRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final ProductionLogRepository productionLogRepository;
  private final PackingRecordRepository packingRecordRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyScopedFactoryLookupService factoryLookupService;
  private final CompanyClock companyClock;
  private final InventoryValuationQueryService inventoryValuationService;
  private final TrialBalanceReportQueryService trialBalanceReportQueryService;
  private final ProfitLossReportQueryService profitLossReportQueryService;
  private final BalanceSheetReportQueryService balanceSheetReportQueryService;
  private final AgedDebtorsReportQueryService agedDebtorsReportQueryService;
  private final TaxService taxService;
  private final InventoryPhysicalCountService inventoryPhysicalCountService;
  private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

  @Autowired(required = false)
  private ObjectProvider<ReconciliationService> reconciliationServiceProvider;

  @Autowired
  public ReportService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingPeriodRepository accountingPeriodRepository,
      AccountingPeriodSnapshotRepository snapshotRepository,
      AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
      DealerRepository dealerRepository,
      DealerLedgerService dealerLedgerService,
      DealerLedgerRepository dealerLedgerRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      ProductionLogRepository productionLogRepository,
      PackingRecordRepository packingRecordRepository,
      InventoryMovementRepository inventoryMovementRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyScopedFactoryLookupService factoryLookupService,
      CompanyClock companyClock,
      InventoryValuationQueryService inventoryValuationService,
      TrialBalanceReportQueryService trialBalanceReportQueryService,
      ProfitLossReportQueryService profitLossReportQueryService,
      BalanceSheetReportQueryService balanceSheetReportQueryService,
      AgedDebtorsReportQueryService agedDebtorsReportQueryService,
      TaxService taxService,
      InventoryPhysicalCountService inventoryPhysicalCountService) {
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.snapshotRepository = snapshotRepository;
    this.snapshotLineRepository = snapshotLineRepository;
    this.dealerRepository = dealerRepository;
    this.dealerLedgerService = dealerLedgerService;
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.productionLogRepository = productionLogRepository;
    this.packingRecordRepository = packingRecordRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    this.accountingLookupService = accountingLookupService;
    this.factoryLookupService = factoryLookupService;
    this.companyClock = companyClock;
    this.inventoryValuationService = inventoryValuationService;
    this.trialBalanceReportQueryService = trialBalanceReportQueryService;
    this.profitLossReportQueryService = profitLossReportQueryService;
    this.balanceSheetReportQueryService = balanceSheetReportQueryService;
    this.agedDebtorsReportQueryService = agedDebtorsReportQueryService;
    this.taxService = taxService;
    this.inventoryPhysicalCountService = inventoryPhysicalCountService;
  }

  @Transactional(readOnly = true)
  public BalanceSheetDto balanceSheet(LocalDate asOfDate) {
    return balanceSheet(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
  }

  @Transactional(readOnly = true)
  public BalanceSheetDto balanceSheet(FinancialReportQueryRequest request) {
    return balanceSheetReportQueryService.generate(requireFinancialReportRequest(request));
  }

  @Transactional(readOnly = true)
  public ProfitLossDto profitLoss(LocalDate asOfDate) {
    return profitLoss(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
  }

  @Transactional(readOnly = true)
  public ProfitLossDto profitLoss(FinancialReportQueryRequest request) {
    return profitLossReportQueryService.generate(requireFinancialReportRequest(request));
  }

  @Transactional(readOnly = true)
  public CashFlowDto cashFlow() {
    ReportContext context = resolveReportContext(null);
    Company company = context.company();
    List<JournalEntry> entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
    BigDecimal operating = BigDecimal.ZERO;
    BigDecimal investing = BigDecimal.ZERO;
    BigDecimal financing = BigDecimal.ZERO;
    for (JournalEntry entry : entries) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      List<JournalLine> lines = entry.getLines();
      if (lines == null || lines.isEmpty()) {
        continue;
      }
      for (JournalLine line : entry.getLines()) {
        if (!isCashAccount(line.getAccount())) {
          continue;
        }
        BigDecimal delta = safe(line.getDebit()).subtract(safe(line.getCredit()));
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
          continue;
        }
        Map<CashFlowSection, BigDecimal> allocations =
            resolveCashFlowAllocations(line, lines, delta);
        operating = operating.add(safe(allocations.get(CashFlowSection.OPERATING)));
        investing = investing.add(safe(allocations.get(CashFlowSection.INVESTING)));
        financing = financing.add(safe(allocations.get(CashFlowSection.FINANCING)));
      }
    }
    BigDecimal net = operating.add(investing).add(financing);
    return new CashFlowDto(operating, investing, financing, net, context.metadata());
  }

  @Transactional(readOnly = true)
  public InventoryValuationDto inventoryValuationAsOf(LocalDate asOfDate) {
    return inventoryValuation(asOfDate);
  }

  @Transactional(readOnly = true)
  public InventoryValuationDto inventoryValuation() {
    return inventoryValuation(null);
  }

  @Transactional(readOnly = true)
  public GstReturnReportDto gstReturn(Long periodId) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period = resolveRequestedPeriod(company, periodId);
    YearMonth periodMonth = YearMonth.of(period.getYear(), period.getMonth());
    GstReportBreakdownDto gstBreakdown = taxService.generateGstReportBreakdown(periodMonth);
    LocalDate startDate =
        gstBreakdown != null && gstBreakdown.getPeriodStart() != null
            ? gstBreakdown.getPeriodStart()
            : period.getStartDate();
    LocalDate endDate =
        gstBreakdown != null && gstBreakdown.getPeriodEnd() != null
            ? gstBreakdown.getPeriodEnd()
            : period.getEndDate();

    GstReturnReportDto.GstComponentSummary outputTax =
        mapComponentSummary(gstBreakdown != null ? gstBreakdown.getCollected() : null);
    GstReturnReportDto.GstComponentSummary inputTaxCredit =
        mapComponentSummary(gstBreakdown != null ? gstBreakdown.getInputTaxCredit() : null);
    GstReturnReportDto.GstComponentSummary netLiability =
        mapComponentSummary(gstBreakdown != null ? gstBreakdown.getNetLiability() : null);

    ReportMetadata metadata =
        new ReportMetadata(
            endDate,
            startDate,
            endDate,
            ReportSource.LIVE,
            period.getId(),
            period.getStatus() != null ? period.getStatus().name() : null,
            null,
            true,
            true,
            null);

    List<GstReturnReportDto.GstRateSummary> rateSummaries =
        mapRateSummaries(gstBreakdown != null ? gstBreakdown.getRateSummaries() : null);
    List<GstReturnReportDto.GstTransactionDetail> transactionDetails =
        mapTransactionDetails(gstBreakdown != null ? gstBreakdown.getTransactionDetails() : null);

    return new GstReturnReportDto(
        period.getId(),
        period.getLabel(),
        startDate,
        endDate,
        outputTax,
        inputTaxCredit,
        netLiability,
        rateSummaries,
        transactionDetails,
        metadata);
  }

  private InventoryValuationDto inventoryValuation(LocalDate asOfDate) {
    ReportContext context = resolveReportContext(asOfDate);
    if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
      AccountingPeriodSnapshot snapshot = context.snapshot();
      InventoryValuationQueryService.InventorySnapshot inventorySnapshot =
          inventoryValuationService.snapshotAsOf(context.company(), context.asOfDate());
      return mapInventorySnapshot(
          inventorySnapshot,
          snapshot.getInventoryTotalValue(),
          snapshot.getInventoryLowStock(),
          context.metadata());
    }
    Company company = context.company();
    if (context.source() == ReportSource.AS_OF) {
      InventoryValuationQueryService.InventorySnapshot snapshot =
          inventoryValuationService.snapshotAsOf(company, context.asOfDate());
      return mapInventorySnapshot(
          snapshot, snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
    }
    InventoryValuationQueryService.InventorySnapshot snapshot =
        inventoryValuationService.currentSnapshot(company);
    return mapInventorySnapshot(
        snapshot, snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
  }

  private InventoryValuationDto mapInventorySnapshot(
      InventoryValuationQueryService.InventorySnapshot snapshot,
      BigDecimal totalValueOverride,
      long lowStockOverride,
      ReportMetadata metadata) {
    InventoryValuationQueryService.InventorySnapshot effective =
        snapshot != null
            ? snapshot
            : new InventoryValuationQueryService.InventorySnapshot(
                BigDecimal.ZERO, 0L, "FIFO", List.of());
    List<InventoryValuationItemDto> items =
        effective.items() == null
            ? List.of()
            : effective.items().stream()
                .filter(Objects::nonNull)
                .map(
                    item ->
                        new InventoryValuationItemDto(
                            item.inventoryItemId(),
                            item.inventoryType() != null ? item.inventoryType().name() : null,
                            item.code(),
                            item.name(),
                            item.category(),
                            item.brand(),
                            roundCurrency(safe(item.quantityOnHand())),
                            roundCurrency(safe(item.reservedQuantity())),
                            roundCurrency(safe(item.availableQuantity())),
                            roundCurrency(safe(item.unitCost())),
                            roundCurrency(safe(item.totalValue())),
                            item.lowStock()))
                .toList();

    List<InventoryValuationGroupDto> groupByCategory = summarizeInventory(items, "CATEGORY", true);
    List<InventoryValuationGroupDto> groupByBrand = summarizeInventory(items, "BRAND", false);

    BigDecimal totalValue =
        roundCurrency(totalValueOverride != null ? totalValueOverride : effective.totalValue());
    String costingMethod = effective.costingMethod() != null ? effective.costingMethod() : "FIFO";

    return new InventoryValuationDto(
        totalValue,
        lowStockOverride,
        costingMethod,
        items,
        groupByCategory,
        groupByBrand,
        metadata);
  }

  private List<InventoryValuationGroupDto> summarizeInventory(
      List<InventoryValuationItemDto> items, String groupType, boolean byCategory) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    Map<String, InventoryGroupingAccumulator> grouped = new LinkedHashMap<>();
    for (InventoryValuationItemDto item : items) {
      if (item == null) {
        continue;
      }
      String key = byCategory ? item.category() : item.brand();
      String normalizedKey =
          key == null || key.isBlank() ? (byCategory ? "UNCATEGORIZED" : "UNBRANDED") : key.trim();
      InventoryGroupingAccumulator accumulator =
          grouped.computeIfAbsent(
              normalizedKey, ignored -> new InventoryGroupingAccumulator(groupType, normalizedKey));
      accumulator.add(safe(item.totalValue()), item.lowStock());
    }
    return grouped.values().stream()
        .map(InventoryGroupingAccumulator::toDto)
        .sorted(
            Comparator.comparing(
                InventoryValuationGroupDto::groupKey, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private AccountingPeriod resolveRequestedPeriod(Company company, Long periodId) {
    if (periodId != null) {
      return accountingPeriodRepository
          .findByCompanyAndId(company, periodId)
          .orElseThrow(
              () ->
                  new ApplicationException(
                      ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                      "Accounting period not found: " + periodId));
    }
    LocalDate today = companyClock.today(company);
    return accountingPeriodRepository
        .findByCompanyAndYearAndMonth(company, today.getYear(), today.getMonthValue())
        .or(() -> accountingPeriodRepository.findFirstByCompanyOrderByStartDateDesc(company))
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                    "Accounting period not found for company"));
  }

  private GstReturnReportDto.GstComponentSummary mapComponentSummary(
      GstReconciliationDto.GstComponentSummary summary) {
    BigDecimal cgst = roundCurrency(safe(summary != null ? summary.getCgst() : null));
    BigDecimal sgst = roundCurrency(safe(summary != null ? summary.getSgst() : null));
    BigDecimal igst = roundCurrency(safe(summary != null ? summary.getIgst() : null));
    BigDecimal total = roundCurrency(cgst.add(sgst).add(igst));
    return new GstReturnReportDto.GstComponentSummary(cgst, sgst, igst, total);
  }

  private List<GstReturnReportDto.GstRateSummary> mapRateSummaries(
      List<GstReportBreakdownDto.GstRateSummary> summaries) {
    if (summaries == null || summaries.isEmpty()) {
      return List.of();
    }
    return summaries.stream()
        .filter(Objects::nonNull)
        .map(
            summary ->
                new GstReturnReportDto.GstRateSummary(
                    roundCurrency(safe(summary.getTaxRate())),
                    roundCurrency(safe(summary.getTaxableAmount())),
                    roundCurrency(safe(summary.getOutputTax())),
                    roundCurrency(safe(summary.getInputTaxCredit())),
                    roundCurrency(safe(summary.getNetTax())),
                    roundCurrency(safe(summary.getOutputCgst())),
                    roundCurrency(safe(summary.getOutputSgst())),
                    roundCurrency(safe(summary.getOutputIgst())),
                    roundCurrency(safe(summary.getInputCgst())),
                    roundCurrency(safe(summary.getInputSgst())),
                    roundCurrency(safe(summary.getInputIgst()))))
        .toList();
  }

  private List<GstReturnReportDto.GstTransactionDetail> mapTransactionDetails(
      List<GstReportBreakdownDto.GstTransactionDetail> details) {
    if (details == null || details.isEmpty()) {
      return List.of();
    }
    return details.stream()
        .filter(Objects::nonNull)
        .map(
            detail ->
                new GstReturnReportDto.GstTransactionDetail(
                    detail.getSourceType(),
                    detail.getSourceId(),
                    detail.getReferenceNumber(),
                    detail.getTransactionDate(),
                    detail.getPartyName(),
                    roundCurrency(safe(detail.getTaxRate())),
                    roundCurrency(safe(detail.getTaxableAmount())),
                    roundCurrency(safe(detail.getCgst())),
                    roundCurrency(safe(detail.getSgst())),
                    roundCurrency(safe(detail.getIgst())),
                    roundCurrency(safe(detail.getTotalTax())),
                    detail.getDirection()))
        .toList();
  }

  @Transactional(readOnly = true)
  public AccountStatementReportDto accountStatement(
      Long accountId, LocalDate fromDate, LocalDate toDate) {
    Company company = companyContextService.requireCurrentCompany();
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "accountId is required");
    }

    LocalDate today = companyClock.today(company);
    LocalDate from = fromDate != null ? fromDate : today.withDayOfMonth(1);
    LocalDate to = toDate != null ? toDate : today;
    if (from.isAfter(to)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_DATE, "from date must be on or before to date")
          .withDetail("from", from.toString())
          .withDetail("to", to.toString());
    }

    Account account = accountingLookupService.requireAccount(company, accountId);
    AccountType accountType = account.getType();
    BigDecimal openingBalance =
        normalizeNetBalance(
            journalLineRepository.netBalanceUpTo(company, accountId, from.minusDays(1)),
            accountType);
    List<JournalLine> lines =
        journalLineRepository.findLinesForAccountBetween(company, accountId, from, to);

    BigDecimal runningBalance = openingBalance;
    List<AccountStatementLineDto> entries = new ArrayList<>();
    for (JournalLine line : lines) {
      JournalEntry entry = line.getJournalEntry();
      BigDecimal debit = safe(line.getDebit());
      BigDecimal credit = safe(line.getCredit());
      BigDecimal normalizedDelta = normalizeNetBalance(debit.subtract(credit), accountType);
      runningBalance = runningBalance.add(normalizedDelta);
      entries.add(
          new AccountStatementLineDto(
              entry != null ? entry.getId() : null,
              entry != null ? entry.getEntryDate() : null,
              resolveEntryTimestamp(entry),
              entry != null ? entry.getReferenceNumber() : null,
              resolveEntryDescription(entry, line),
              debit,
              credit,
              runningBalance));
    }

    BigDecimal closingBalance = entries.isEmpty() ? openingBalance : runningBalance;
    return new AccountStatementReportDto(
        account.getId(),
        account.getCode(),
        account.getName(),
        from,
        to,
        openingBalance,
        entries,
        closingBalance);
  }

  @Transactional(readOnly = true)
  public List<AccountStatementEntryDto> accountStatement() {
    Company company = companyContextService.requireCurrentCompany();
    var dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
    var balances =
        dealerLedgerService.currentBalances(dealers.stream().map(Dealer::getId).toList());
    if (balances == null) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_INTERNAL_ERROR,
          "Dealer balance snapshot unavailable for account statement");
    }
    return dealers.stream()
        .map(
            dealer -> {
              BigDecimal outstanding = balances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
              DealerLedgerEntry latest =
                  dealerLedgerRepository
                      .findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(company, dealer)
                      .orElse(null);
              LocalDate entryDate =
                  latest != null ? latest.getEntryDate() : companyClock.today(company);
              String reference =
                  latest != null && latest.getReferenceNumber() != null
                      ? latest.getReferenceNumber()
                      : "BALANCE";
              BigDecimal debit = latest != null ? safe(latest.getDebit()) : outstanding;
              BigDecimal credit = latest != null ? safe(latest.getCredit()) : BigDecimal.ZERO;
              Long journalEntryId =
                  latest != null && latest.getJournalEntry() != null
                      ? latest.getJournalEntry().getId()
                      : null;
              return new AccountStatementEntryDto(
                  dealer.getName(),
                  entryDate,
                  reference,
                  debit,
                  credit,
                  outstanding,
                  journalEntryId);
            })
        .toList();
  }

  private Instant resolveEntryTimestamp(JournalEntry entry) {
    if (entry == null) {
      return null;
    }
    if (entry.getPostedAt() != null) {
      return entry.getPostedAt();
    }
    return entry.getCreatedAt();
  }

  private String resolveEntryDescription(JournalEntry entry, JournalLine line) {
    if (line != null && line.getDescription() != null && !line.getDescription().isBlank()) {
      return line.getDescription();
    }
    if (entry != null && entry.getMemo() != null && !entry.getMemo().isBlank()) {
      return entry.getMemo();
    }
    return null;
  }

  @Transactional(readOnly = true)
  public List<AgedDebtorDto> agedDebtors(FinancialReportQueryRequest request) {
    return agedDebtorsReportQueryService.generate(requireFinancialReportRequest(request));
  }

  @Transactional(readOnly = true)
  public ReconciliationSummaryDto inventoryReconciliation() {
    Company company = companyContextService.requireCurrentCompany();
    InventoryValuationQueryService.InventorySnapshot totals =
        inventoryValuationService.currentSnapshot(company);
    BigDecimal ledgerBalance = resolveInventoryLedgerBalance(company);
    BigDecimal variance = safe(totals.totalValue()).subtract(ledgerBalance);
    return new ReconciliationSummaryDto(safe(totals.totalValue()), ledgerBalance, variance);
  }

  @Transactional(readOnly = true)
  public InventoryReconciliationReportDto inventoryReconciliationReport() {
    Company company = companyContextService.requireCurrentCompany();
    InventoryValuationQueryService.InventorySnapshot totals =
        inventoryValuationService.currentSnapshot(company);
    List<InventoryValuationQueryService.InventoryItemSnapshot> snapshotItems =
        totals.items() == null ? List.of() : totals.items();
    Map<Long, BigDecimal> latestFinishedGoodCounts =
        inventoryPhysicalCountService.latestFinishedGoodCounts(
            company, inventoryReconciliationItemIds(snapshotItems, true));
    Map<Long, BigDecimal> latestRawMaterialCounts =
        inventoryPhysicalCountService.latestRawMaterialCounts(
            company, inventoryReconciliationItemIds(snapshotItems, false));

    List<InventoryReconciliationItemDto> items =
        snapshotItems.stream()
            .filter(Objects::nonNull)
            .map(
                item -> {
                  BigDecimal systemQty = safe(item.quantityOnHand());
                  BigDecimal physicalQty =
                      resolvePhysicalQuantity(
                          item, systemQty, latestFinishedGoodCounts, latestRawMaterialCounts);
                  BigDecimal variance = physicalQty.subtract(systemQty);
                  return new InventoryReconciliationItemDto(
                      item.inventoryItemId(),
                      item.code(),
                      item.name(),
                      roundCurrency(systemQty),
                      roundCurrency(physicalQty),
                      roundCurrency(variance));
                })
            .toList();

    BigDecimal systemQuantityTotal =
        items.stream()
            .map(InventoryReconciliationItemDto::systemQty)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal physicalQuantityTotal =
        items.stream()
            .map(InventoryReconciliationItemDto::physicalQty)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal quantityVarianceTotal = physicalQuantityTotal.subtract(systemQuantityTotal);

    BigDecimal physicalInventoryValue =
        snapshotItems.stream()
            .filter(Objects::nonNull)
            .map(
                item -> {
                  BigDecimal systemQty = safe(item.quantityOnHand());
                  BigDecimal physicalQty =
                      resolvePhysicalQuantity(
                          item, systemQty, latestFinishedGoodCounts, latestRawMaterialCounts);
                  return physicalQty.multiply(safe(item.unitCost()));
                })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal ledgerBalance = resolveInventoryLedgerBalance(company);
    BigDecimal valueVariance = physicalInventoryValue.subtract(ledgerBalance);
    return new InventoryReconciliationReportDto(
        roundCurrency(systemQuantityTotal),
        roundCurrency(physicalQuantityTotal),
        roundCurrency(quantityVarianceTotal),
        roundCurrency(ledgerBalance),
        roundCurrency(physicalInventoryValue),
        roundCurrency(valueVariance),
        items);
  }

  private List<Long> inventoryReconciliationItemIds(
      List<InventoryValuationQueryService.InventoryItemSnapshot> snapshotItems,
      boolean finishedGoods) {
    InventoryValuationQueryService.InventoryTypeBucket expectedType =
        finishedGoods
            ? InventoryValuationQueryService.InventoryTypeBucket.FINISHED_GOOD
            : InventoryValuationQueryService.InventoryTypeBucket.RAW_MATERIAL;
    return snapshotItems.stream()
        .filter(Objects::nonNull)
        .filter(item -> item.inventoryType() == expectedType)
        .map(InventoryValuationQueryService.InventoryItemSnapshot::inventoryItemId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private BigDecimal resolvePhysicalQuantity(
      InventoryValuationQueryService.InventoryItemSnapshot item,
      BigDecimal systemQty,
      Map<Long, BigDecimal> latestFinishedGoodCounts,
      Map<Long, BigDecimal> latestRawMaterialCounts) {
    if (item == null || item.inventoryItemId() == null) {
      return systemQty;
    }
    BigDecimal physicalQty =
        switch (item.inventoryType()) {
          case FINISHED_GOOD -> latestFinishedGoodCounts.get(item.inventoryItemId());
          case RAW_MATERIAL -> latestRawMaterialCounts.get(item.inventoryItemId());
        };
    return physicalQty == null ? systemQty : safe(physicalQty);
  }

  @Transactional(readOnly = true)
  public List<BalanceWarningDto> balanceWarnings() {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate asOfDate = companyClock.today(company);
    Map<Long, BigDecimal> liveBalances = summarizeBalances(company, asOfDate);
    List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<BalanceWarningDto> warnings = new ArrayList<>();
    for (Account account : accounts) {
      BigDecimal balance = liveBalanceForAccount(account, liveBalances);
      AccountType type = account.getType();
      String reason = null;
      String severity = "INFO";
      String warningType = null;
      BigDecimal threshold = BigDecimal.ZERO;
      if (type == AccountType.ASSET && balance.compareTo(BigDecimal.ZERO) < 0) {
        reason = "Asset account has a credit balance";
        severity = "HIGH";
        warningType = "ASSET_CREDIT_BALANCE";
      } else if (type == AccountType.LIABILITY && balance.compareTo(BigDecimal.ZERO) > 0) {
        reason = "Liability account has a debit balance";
        severity = "HIGH";
        warningType = "LIABILITY_DEBIT_BALANCE";
      } else if (type == AccountType.REVENUE && balance.compareTo(BigDecimal.ZERO) > 0) {
        reason = "Revenue account shows a debit balance";
        severity = "MEDIUM";
        warningType = "REVENUE_DEBIT_BALANCE";
      } else if ((type == AccountType.EXPENSE || type == AccountType.COGS)
          && balance.compareTo(BigDecimal.ZERO) < 0) {
        reason = "Expense account shows a credit balance";
        severity = "MEDIUM";
        warningType = "EXPENSE_CREDIT_BALANCE";
      }
      if (reason != null) {
        warnings.add(
            new BalanceWarningDto(
                account.getId(),
                account.getCode(),
                account.getName(),
                balance,
                warningType,
                threshold,
                severity,
                reason));
      }
    }
    return warnings;
  }

  @Transactional(readOnly = true)
  public ReconciliationDashboardDto reconciliationDashboard(
      Long bankAccountId, BigDecimal statementBalance) {
    Company company = companyContextService.requireCurrentCompany();
    Account bankAccount = resolveBankAccountForDashboard(company, bankAccountId);
    LocalDate asOfDate = companyClock.today(company);
    Map<Long, BigDecimal> liveBalances = summarizeBalances(company, asOfDate);
    InventoryValuationQueryService.InventorySnapshot totals =
        inventoryValuationService.currentSnapshot(company);
    BigDecimal ledgerInventoryValue = resolveInventoryLedgerBalance(company, liveBalances);
    BigDecimal physicalInventoryValue = safe(totals.totalValue());
    BigDecimal inventoryVariance = physicalInventoryValue.subtract(ledgerInventoryValue);

    BigDecimal bankLedgerBalance = liveBalanceForAccount(bankAccount, liveBalances);
    BigDecimal bankStatementBalance =
        statementBalance != null ? statementBalance : bankLedgerBalance;
    BigDecimal bankVariance = bankLedgerBalance.subtract(bankStatementBalance);

    boolean inventoryBalanced = inventoryVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;
    boolean bankBalanced = bankVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;

    BankReconciliationDashboardDto bankSummary =
        new BankReconciliationDashboardDto(
            bankAccount.getId(),
            bankAccount.getCode(),
            bankAccount.getName(),
            roundCurrency(bankLedgerBalance),
            roundCurrency(bankStatementBalance),
            roundCurrency(bankVariance),
            bankBalanced);
    InventoryReconciliationDashboardDto inventorySummary =
        new InventoryReconciliationDashboardDto(
            roundCurrency(ledgerInventoryValue),
            roundCurrency(physicalInventoryValue),
            roundCurrency(inventoryVariance),
            inventoryBalanced);

    SubledgerReconciliationDashboardDto subledgerSummary =
        resolveSubledgerDashboardSummary(
            new SubledgerReconciliationDashboardDto.SubledgerControlSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true),
            new SubledgerReconciliationDashboardDto.SubledgerControlSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true));

    return new ReconciliationDashboardDto(
        bankSummary, subledgerSummary, inventorySummary, balanceWarnings());
  }

  private SubledgerReconciliationDashboardDto resolveSubledgerDashboardSummary(
      SubledgerReconciliationDashboardDto.SubledgerControlSummary defaultReceivables,
      SubledgerReconciliationDashboardDto.SubledgerControlSummary defaultPayables) {
    if (reconciliationServiceProvider == null) {
      return new SubledgerReconciliationDashboardDto(
          defaultReceivables, defaultPayables, BigDecimal.ZERO, true);
    }
    ReconciliationService reconciliationService = reconciliationServiceProvider.getIfAvailable();
    if (reconciliationService == null) {
      return new SubledgerReconciliationDashboardDto(
          defaultReceivables, defaultPayables, BigDecimal.ZERO, true);
    }

    ReconciliationService.ReconciliationResult receivable =
        reconciliationService.reconcileArWithDealerLedger();
    ReconciliationService.SupplierReconciliationResult payable =
        reconciliationService.reconcileApWithSupplierLedger();

    SubledgerReconciliationDashboardDto.SubledgerControlSummary receivableSummary =
        new SubledgerReconciliationDashboardDto.SubledgerControlSummary(
            roundCurrency(safe(receivable.glArBalance())),
            roundCurrency(safe(receivable.dealerLedgerTotal())),
            roundCurrency(safe(receivable.variance())),
            receivable.isReconciled());
    SubledgerReconciliationDashboardDto.SubledgerControlSummary payableSummary =
        new SubledgerReconciliationDashboardDto.SubledgerControlSummary(
            roundCurrency(safe(payable.glApBalance())),
            roundCurrency(safe(payable.supplierLedgerTotal())),
            roundCurrency(safe(payable.variance())),
            payable.isReconciled());

    BigDecimal totalDifference =
        safe(receivable.variance()).add(safe(payable.variance())).setScale(2, RoundingMode.HALF_UP);
    boolean balanced = receivable.isReconciled() && payable.isReconciled();
    return new SubledgerReconciliationDashboardDto(
        receivableSummary, payableSummary, totalDifference, balanced);
  }

  private Account resolveBankAccountForDashboard(Company company, Long bankAccountId) {
    if (bankAccountId != null) {
      return accountingLookupService.requireAccount(company, bankAccountId);
    }
    List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    Optional<Account> preferredBankAccount =
        accounts.stream()
            .filter(account -> account.getType() == AccountType.ASSET)
            .filter(
                account -> {
                  String label =
                      ((account.getCode() != null ? account.getCode() : "")
                              + " "
                              + (account.getName() != null ? account.getName() : ""))
                          .toLowerCase(Locale.ROOT);
                  return label.contains("bank");
                })
            .findFirst();
    if (preferredBankAccount.isPresent()) {
      return preferredBankAccount.get();
    }
    return accounts.stream()
        .filter(account -> account.getType() == AccountType.ASSET)
        .findFirst()
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                    "No asset bank account is available for reconciliation dashboard"));
  }

  @Transactional(readOnly = true)
  public TrialBalanceDto trialBalance(LocalDate asOfDate) {
    return trialBalance(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
  }

  @Transactional(readOnly = true)
  public TrialBalanceDto trialBalance(FinancialReportQueryRequest request) {
    return trialBalanceReportQueryService.generate(requireFinancialReportRequest(request));
  }

  private FinancialReportQueryRequest requireFinancialReportRequest(
      FinancialReportQueryRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Financial report query request is required");
    }
    return request;
  }

  private ReportContext resolveReportContext(LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
    AccountingPeriod period =
        accountingPeriodRepository
            .findByCompanyAndYearAndMonth(
                company, effectiveDate.getYear(), effectiveDate.getMonthValue())
            .orElse(null);
    AccountingPeriodSnapshot snapshot = null;
    ReportSource source;
    if (period != null && period.getStatus() == AccountingPeriodStatus.CLOSED) {
      snapshot =
          snapshotRepository
              .findByCompanyAndPeriod(company, period)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                              "Closed period snapshot is required for reports")
                          .withDetail("companyId", company.getId())
                          .withDetail("periodId", period.getId())
                          .withDetail("asOfDate", effectiveDate));
      source = ReportSource.SNAPSHOT;
    } else if (asOfDate != null) {
      source = ReportSource.AS_OF;
    } else {
      source = ReportSource.LIVE;
    }
    return new ReportContext(company, effectiveDate, period, snapshot, source);
  }

  private List<TrialBalanceLine> resolveTrialBalanceLines(ReportContext context) {
    if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
      return snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(context.snapshot()).stream()
          .map(
              line ->
                  new TrialBalanceLine(
                      line.getAccountId(),
                      line.getAccountCode(),
                      line.getAccountName(),
                      line.getAccountType(),
                      safe(line.getDebit()),
                      safe(line.getCredit())))
          .toList();
    }
    Company company = context.company();
    List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    if (context.source() == ReportSource.AS_OF) {
      Map<Long, BigDecimal> balances = summarizeBalances(company, context.asOfDate());
      return accounts.stream()
          .map(
              account ->
                  toTrialBalanceLine(
                      account, balances.getOrDefault(account.getId(), BigDecimal.ZERO)))
          .toList();
    }
    return accounts.stream()
        .map(account -> toTrialBalanceLine(account, safe(account.getBalance())))
        .toList();
  }

  private Map<Long, BigDecimal> summarizeBalances(Company company, LocalDate asOfDate) {
    Map<Long, BigDecimal> balances = new HashMap<>();
    List<Object[]> rows = journalLineRepository.summarizeByAccountUpTo(company, asOfDate);
    for (Object[] row : rows) {
      if (row == null || row.length < 3 || row[0] == null) {
        continue;
      }
      Long accountId = (Long) row[0];
      BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
      balances.put(accountId, debit.subtract(credit));
    }
    return balances;
  }

  private TrialBalanceLine toTrialBalanceLine(Account account, BigDecimal balance) {
    BigDecimal safeBalance = safe(balance);
    AccountType type = account != null ? account.getType() : null;
    boolean debitNormal = type == null || type.isDebitNormalBalance();
    BigDecimal normalized = debitNormal ? safeBalance : safeBalance.negate();
    BigDecimal debit;
    BigDecimal credit;
    if (normalized.compareTo(BigDecimal.ZERO) >= 0) {
      debit = debitNormal ? normalized : BigDecimal.ZERO;
      credit = debitNormal ? BigDecimal.ZERO : normalized;
    } else {
      BigDecimal amount = normalized.abs();
      debit = debitNormal ? BigDecimal.ZERO : amount;
      credit = debitNormal ? amount : BigDecimal.ZERO;
    }
    return new TrialBalanceLine(
        account != null ? account.getId() : null,
        account != null ? account.getCode() : null,
        account != null ? account.getName() : null,
        type,
        debit,
        credit);
  }

  private record TrialBalanceLine(
      Long accountId,
      String code,
      String name,
      AccountType type,
      BigDecimal debit,
      BigDecimal credit) {}

  private record ReportContext(
      Company company,
      LocalDate asOfDate,
      AccountingPeriod period,
      AccountingPeriodSnapshot snapshot,
      ReportSource source) {
    ReportMetadata metadata() {
      Long periodId = period != null ? period.getId() : null;
      String status =
          period != null && period.getStatus() != null ? period.getStatus().name() : null;
      Long snapshotId = snapshot != null ? snapshot.getId() : null;
      return new ReportMetadata(asOfDate, source, periodId, status, snapshotId);
    }
  }

  private boolean isInventoryAccount(Account account) {
    if (account == null || account.getName() == null) {
      return false;
    }
    return account.getName().toLowerCase(Locale.ROOT).contains("inventory");
  }

  private boolean isCashAccount(Account account) {
    if (account == null || account.getType() != AccountType.ASSET) {
      return false;
    }
    String label = (account.getCode() + " " + account.getName()).toLowerCase(Locale.ROOT);
    return label.contains("cash")
        || label.contains("bank")
        || label.contains("wallet")
        || label.contains("upi");
  }

  private Map<CashFlowSection, BigDecimal> resolveCashFlowAllocations(
      JournalLine cashLine, List<JournalLine> entryLines, BigDecimal cashDelta) {
    Map<CashFlowSection, BigDecimal> allocations = new EnumMap<>(CashFlowSection.class);
    if (cashDelta == null || cashDelta.compareTo(BigDecimal.ZERO) == 0) {
      return allocations;
    }
    Map<CashFlowSection, BigDecimal> weights =
        resolveCashFlowWeights(cashLine, entryLines, cashDelta);
    if (weights.isEmpty()) {
      allocations.put(CashFlowSection.OPERATING, cashDelta);
      return allocations;
    }
    BigDecimal totalWeight =
        weights.values().stream().map(this::safe).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
      allocations.put(CashFlowSection.OPERATING, cashDelta);
      return allocations;
    }

    CashFlowSection dominantSection = dominantCashFlowSection(weights);
    BigDecimal allocated = BigDecimal.ZERO;
    for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
      CashFlowSection section = entry.getKey();
      if (section == dominantSection) {
        continue;
      }
      BigDecimal weight = safe(entry.getValue());
      if (weight.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal sectionAmount =
          roundCurrency(
              cashDelta.multiply(weight).divide(totalWeight, 8, java.math.RoundingMode.HALF_UP));
      if (sectionAmount.compareTo(BigDecimal.ZERO) == 0) {
        continue;
      }
      allocations.merge(section, sectionAmount, BigDecimal::add);
      allocated = allocated.add(sectionAmount);
    }

    BigDecimal dominantAmount = roundCurrency(cashDelta.subtract(allocated));
    if (dominantAmount.compareTo(BigDecimal.ZERO) != 0) {
      allocations.merge(dominantSection, dominantAmount, BigDecimal::add);
    }
    return allocations;
  }

  private Map<CashFlowSection, BigDecimal> resolveCashFlowWeights(
      JournalLine cashLine, List<JournalLine> entryLines, BigDecimal cashDelta) {
    Map<CashFlowSection, BigDecimal> weights = new EnumMap<>(CashFlowSection.class);
    if (cashLine == null || entryLines == null || entryLines.isEmpty() || cashDelta == null) {
      return weights;
    }
    boolean inflow = cashDelta.compareTo(BigDecimal.ZERO) > 0;
    List<JournalLine> candidates =
        entryLines.stream()
            .filter(line -> line != null && line != cashLine)
            .filter(line -> !isCashAccount(line.getAccount()))
            .filter(
                line ->
                    inflow
                        ? safe(line.getCredit()).compareTo(BigDecimal.ZERO) > 0
                        : safe(line.getDebit()).compareTo(BigDecimal.ZERO) > 0)
            .toList();
    if (candidates.isEmpty()) {
      candidates =
          entryLines.stream()
              .filter(line -> line != null && line != cashLine)
              .filter(line -> !isCashAccount(line.getAccount()))
              .toList();
    }
    for (JournalLine candidate : candidates) {
      BigDecimal weight = inflow ? safe(candidate.getCredit()) : safe(candidate.getDebit());
      if (weight.compareTo(BigDecimal.ZERO) <= 0) {
        weight = safe(candidate.getDebit()).add(safe(candidate.getCredit()));
      }
      if (weight.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      CashFlowSection section = classifyCashFlowCounterparty(candidate.getAccount());
      weights.merge(section, weight.abs(), BigDecimal::add);
    }
    return weights;
  }

  private CashFlowSection dominantCashFlowSection(Map<CashFlowSection, BigDecimal> weights) {
    if (weights == null || weights.isEmpty()) {
      return CashFlowSection.OPERATING;
    }
    CashFlowSection resolved = CashFlowSection.OPERATING;
    BigDecimal maxWeight = BigDecimal.ZERO;
    for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
      BigDecimal weight = safe(entry.getValue());
      if (weight.compareTo(maxWeight) > 0) {
        maxWeight = weight;
        resolved = entry.getKey();
      }
    }
    return resolved;
  }

  private BigDecimal roundCurrency(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private enum CashFlowSection {
    OPERATING,
    INVESTING,
    FINANCING
  }

  private final class InventoryGroupingAccumulator {
    private final String groupType;
    private final String groupKey;
    private BigDecimal totalValue = BigDecimal.ZERO;
    private long itemCount = 0;
    private long lowStockItems = 0;

    private InventoryGroupingAccumulator(String groupType, String groupKey) {
      this.groupType = groupType;
      this.groupKey = groupKey;
    }

    private void add(BigDecimal value, boolean lowStock) {
      this.totalValue = this.totalValue.add(value == null ? BigDecimal.ZERO : value);
      this.itemCount++;
      if (lowStock) {
        this.lowStockItems++;
      }
    }

    private InventoryValuationGroupDto toDto() {
      return new InventoryValuationGroupDto(
          groupType, groupKey, roundCurrency(totalValue), itemCount, lowStockItems);
    }
  }

  private CashFlowSection classifyCashFlowCounterparty(Account account) {
    if (account == null) {
      return CashFlowSection.OPERATING;
    }
    AccountType type = account.getType();
    String label =
        ((account.getCode() != null ? account.getCode() : "")
                + " "
                + (account.getName() != null ? account.getName() : ""))
            .toLowerCase(Locale.ROOT);
    if (type == AccountType.EQUITY) {
      return CashFlowSection.FINANCING;
    }
    if (type == AccountType.LIABILITY) {
      if (containsAny(
          label, "loan", "borrow", "debt", "note payable", "capital lease", "long-term")) {
        return CashFlowSection.FINANCING;
      }
      return CashFlowSection.OPERATING;
    }
    if (type == AccountType.ASSET) {
      if (containsAny(
          label,
          "fixed asset",
          "equipment",
          "machinery",
          "vehicle",
          "building",
          "plant",
          "investment")) {
        return CashFlowSection.INVESTING;
      }
      return CashFlowSection.OPERATING;
    }
    return CashFlowSection.OPERATING;
  }

  private boolean containsAny(String value, String... tokens) {
    if (value == null || tokens == null) {
      return false;
    }
    for (String token : tokens) {
      if (token != null && value.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private BigDecimal resolveInventoryLedgerBalance(Company company) {
    LocalDate asOfDate = companyClock.today(company);
    return resolveInventoryLedgerBalance(company, summarizeBalances(company, asOfDate));
  }

  private BigDecimal resolveInventoryLedgerBalance(
      Company company, Map<Long, BigDecimal> liveBalances) {
    Long defaultInventoryAccountId = company.getDefaultInventoryAccountId();
    if (defaultInventoryAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, defaultInventoryAccountId);
      return liveBalanceForAccount(account, liveBalances);
    }
    return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
        .filter(this::isInventoryAccount)
        .map(account -> liveBalanceForAccount(account, liveBalances))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal liveBalanceForAccount(Account account, Map<Long, BigDecimal> liveBalances) {
    if (account == null) {
      return BigDecimal.ZERO;
    }
    if (liveBalances != null && liveBalances.containsKey(account.getId())) {
      return safe(liveBalances.get(account.getId()));
    }
    return safe(account.getBalance());
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal normalizeNetBalance(BigDecimal netBalance, AccountType accountType) {
    BigDecimal normalized = safe(netBalance);
    if (accountType != null && !accountType.isDebitNormalBalance()) {
      return normalized.negate();
    }
    return normalized;
  }

  @Transactional(readOnly = true)
  public ProductCostingReportDto productCosting(Long itemId) {
    Company company = companyContextService.requireCurrentCompany();
    List<ProductionLog> logs =
        productionLogRepository.findByCompanyAndProduct_IdAndStatusOrderByProducedAtDesc(
            company, itemId, ProductionLogStatus.FULLY_PACKED);
    if (logs.isEmpty()) {
      return new ProductCostingReportDto(
          itemId,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
    }

    BigDecimal totalMaterial = BigDecimal.ZERO;
    BigDecimal totalLabour = BigDecimal.ZERO;
    BigDecimal totalOverhead = BigDecimal.ZERO;
    BigDecimal totalPackaging = BigDecimal.ZERO;
    BigDecimal totalPacked = BigDecimal.ZERO;

    for (ProductionLog log : logs) {
      totalMaterial = totalMaterial.add(safe(log.getMaterialCostTotal()));
      totalLabour = totalLabour.add(safe(log.getLaborCostTotal()));
      totalOverhead = totalOverhead.add(safe(log.getOverheadCostTotal()));
      totalPackaging = totalPackaging.add(resolvePackagingCost(company, log));
      totalPacked = totalPacked.add(safe(log.getTotalPackedQuantity()));
    }

    if (totalPacked.compareTo(BigDecimal.ZERO) <= 0) {
      totalPacked =
          logs.stream()
              .map(ProductionLog::getMixedQuantity)
              .map(this::safe)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    if (totalPacked.compareTo(BigDecimal.ZERO) <= 0) {
      return new ProductCostingReportDto(
          itemId,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
    }

    BigDecimal materialPerUnit = totalMaterial.divide(totalPacked, 4, RoundingMode.HALF_UP);
    BigDecimal packagingPerUnit = totalPackaging.divide(totalPacked, 4, RoundingMode.HALF_UP);
    BigDecimal labourPerUnit = totalLabour.divide(totalPacked, 4, RoundingMode.HALF_UP);
    BigDecimal overheadPerUnit = totalOverhead.divide(totalPacked, 4, RoundingMode.HALF_UP);
    BigDecimal totalUnitCost =
        materialPerUnit.add(packagingPerUnit).add(labourPerUnit).add(overheadPerUnit);

    return new ProductCostingReportDto(
        itemId, materialPerUnit, packagingPerUnit, labourPerUnit, overheadPerUnit, totalUnitCost);
  }

  @Transactional(readOnly = true)
  public CostAllocationReportDto costAllocationReport() {
    Company company = companyContextService.requireCurrentCompany();
    List<CostAllocationBatchDto> amountsPerBatch = new ArrayList<>();

    List<JournalEntry> varianceEntries =
        journalEntryRepository.findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
            company, "FACTORY_COST_VARIANCE");
    for (JournalEntry entry : varianceEntries) {
      amountsPerBatch.add(
          new CostAllocationBatchDto(
              resolveBatchCode(entry.getReferenceNumber()),
              resolvePeriodKey(entry.getReferenceNumber()),
              resolveJournalAmount(entry),
              entry.getEntryDate(),
              entry.getId()));
    }

    List<JournalEntry> directAllocationEntries =
        journalEntryRepository.findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
            company, "FACTORY_COST_ALLOCATION");
    for (JournalEntry entry : directAllocationEntries) {
      amountsPerBatch.add(
          new CostAllocationBatchDto(
              resolveBatchCode(entry.getReferenceNumber()),
              resolvePeriodKey(entry.getReferenceNumber()),
              resolveJournalAmount(entry),
              entry.getEntryDate(),
              entry.getId()));
    }

    amountsPerBatch.sort(Comparator.comparing(CostAllocationBatchDto::entryDate).reversed());
    BigDecimal totalAllocated =
        amountsPerBatch.stream()
            .map(CostAllocationBatchDto::allocatedAmount)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new CostAllocationReportDto(
        List.of("PERIOD_CLOSE_VARIANCE_BY_PRODUCTION_VOLUME", "DIRECT_BATCH_ALLOCATION"),
        amountsPerBatch,
        totalAllocated.setScale(2, RoundingMode.HALF_UP));
  }

  @Transactional(readOnly = true)
  public List<MonthlyProductionCostEntryDto> monthlyProductionCosts() {
    Company company = companyContextService.requireCurrentCompany();
    ZoneId zone = companyClock.zoneId(company);
    Map<YearMonth, BigDecimal> totalsByMonth = new LinkedHashMap<>();
    List<ProductionLog> logs =
        productionLogRepository.findByCompanyAndStatusOrderByProducedAtAsc(
            company, ProductionLogStatus.FULLY_PACKED);

    for (ProductionLog log : logs) {
      YearMonth month = YearMonth.from(log.getProducedAt().atZone(zone));
      BigDecimal total =
          safe(log.getMaterialCostTotal())
              .add(safe(log.getLaborCostTotal()))
              .add(safe(log.getOverheadCostTotal()))
              .add(resolvePackagingCost(company, log));
      totalsByMonth.merge(month, total, BigDecimal::add);
    }

    return totalsByMonth.entrySet().stream()
        .sorted(Map.Entry.<YearMonth, BigDecimal>comparingByKey().reversed())
        .map(
            entry ->
                new MonthlyProductionCostEntryDto(
                    entry.getKey().toString(),
                    safe(entry.getValue()).setScale(2, RoundingMode.HALF_UP)))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<WastageReportDto> wastageReport() {
    Company company = companyContextService.requireCurrentCompany();
    List<ProductionLog> logs =
        productionLogRepository.findTop25ByCompanyOrderByProducedAtDesc(company);

    return logs.stream()
        .filter(
            log ->
                log.getWastageQuantity() != null
                    && log.getWastageQuantity().compareTo(BigDecimal.ZERO) > 0)
        .map(
            log -> {
              BigDecimal mixedQty = safe(log.getMixedQuantity());
              BigDecimal wastageQty = safe(log.getWastageQuantity());
              BigDecimal wastagePercentage =
                  mixedQty.compareTo(BigDecimal.ZERO) > 0
                      ? wastageQty
                          .divide(mixedQty, 4, java.math.RoundingMode.HALF_UP)
                          .multiply(new BigDecimal("100"))
                      : BigDecimal.ZERO;

              BigDecimal wastageValue = wastageQty.multiply(safe(log.getUnitCost()));

              return new WastageReportDto(
                  log.getId(),
                  log.getProductionCode(),
                  log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
                  log.getBatchColour(),
                  log.getMixedQuantity(),
                  log.getTotalPackedQuantity(),
                  log.getWastageQuantity(),
                  wastagePercentage,
                  wastageValue,
                  log.getProducedAt());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public CostBreakdownDto costBreakdown(Long productionLogId) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionLog log = factoryLookupService.requireProductionLog(company, productionLogId);

    BigDecimal materialCost = safe(log.getMaterialCostTotal());
    BigDecimal laborCost = safe(log.getLaborCostTotal());
    BigDecimal overheadCost = safe(log.getOverheadCostTotal());

    List<PackingRecord> packingRecords =
        packingRecordRepository.findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(
            company, log);

    List<PackedBatchTraceDto> packedBatches = new ArrayList<>();
    List<RawMaterialTraceDto> rawMaterialTrace = new ArrayList<>();

    BigDecimal packedQuantity = BigDecimal.ZERO;
    BigDecimal packagingCost = BigDecimal.ZERO;

    for (PackingRecord record : packingRecords) {
      String referencePrefix = log.getProductionCode() + "-PACK-" + record.getId();

      BigDecimal quantity = safe(record.getQuantityPacked());
      BigDecimal unitCost =
          Optional.ofNullable(record.getFinishedGoodBatch())
              .map(batch -> safe(batch.getUnitCost()))
              .orElse(BigDecimal.ZERO);
      BigDecimal totalValue = quantity.multiply(unitCost);

      List<InventoryMovement> movements =
          inventoryMovementRepository
              .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                  company, InventoryReference.PACKING_RECORD, referencePrefix);
      Long journalEntryId =
          movements.stream()
              .map(InventoryMovement::getJournalEntryId)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);

      packedBatches.add(
          new PackedBatchTraceDto(
              record.getId(),
              record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getId() : null,
              record.getFinishedGoodBatch() != null
                  ? record.getFinishedGoodBatch().getPublicId()
                  : null,
              record.getFinishedGoodBatch() != null
                  ? record.getFinishedGoodBatch().getBatchCode()
                  : null,
              record.getFinishedGood() != null ? record.getFinishedGood().getProductCode() : null,
              record.getFinishedGood() != null ? record.getFinishedGood().getName() : null,
              record.getSizeVariant() != null
                  ? record.getSizeVariant().getSizeLabel()
                  : record.getPackagingSize(),
              quantity,
              unitCost,
              totalValue,
              referencePrefix,
              journalEntryId));

      packedQuantity = packedQuantity.add(quantity);
      packagingCost = packagingCost.add(safe(record.getPackagingCost()));

      List<RawMaterialMovement> packagingMovements =
          rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
              company, InventoryReference.PACKING_RECORD, referencePrefix);
      for (RawMaterialMovement movement : packagingMovements) {
        BigDecimal movementQuantity = safe(movement.getQuantity());
        BigDecimal movementUnitCost = safe(movement.getUnitCost());
        rawMaterialTrace.add(
            new RawMaterialTraceDto(
                movement.getId(),
                movement.getRawMaterial() != null ? movement.getRawMaterial().getId() : null,
                movement.getRawMaterial() != null ? movement.getRawMaterial().getSku() : null,
                movement.getRawMaterial() != null ? movement.getRawMaterial().getName() : null,
                movement.getRawMaterialBatch() != null
                    ? movement.getRawMaterialBatch().getId()
                    : null,
                movement.getRawMaterialBatch() != null
                    ? movement.getRawMaterialBatch().getBatchCode()
                    : null,
                movementQuantity,
                movementUnitCost,
                movementQuantity.multiply(movementUnitCost),
                movement.getMovementType(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getCreatedAt(),
                movement.getJournalEntryId()));
      }
    }

    log.getMaterials().stream()
        .sorted(
            Comparator.comparing(
                material ->
                    Optional.ofNullable(material.getRawMaterialMovementId())
                        .orElse(Long.MAX_VALUE)))
        .forEach(
            material -> {
              BigDecimal qty = safe(material.getQuantity());
              BigDecimal unit = safe(material.getCostPerUnit());
              rawMaterialTrace.add(
                  new RawMaterialTraceDto(
                      material.getRawMaterialMovementId(),
                      material.getRawMaterial() != null ? material.getRawMaterial().getId() : null,
                      material.getRawMaterial() != null ? material.getRawMaterial().getSku() : null,
                      material.getMaterialName(),
                      material.getRawMaterialBatch() != null
                          ? material.getRawMaterialBatch().getId()
                          : null,
                      material.getRawMaterialBatch() != null
                          ? material.getRawMaterialBatch().getBatchCode()
                          : null,
                      qty,
                      unit,
                      safe(material.getTotalCost()),
                      "ISSUE",
                      InventoryReference.PRODUCTION_LOG,
                      log.getProductionCode(),
                      log.getProducedAt(),
                      null));
            });

    BigDecimal totalCost = materialCost.add(laborCost).add(overheadCost).add(packagingCost);

    CostComponentTraceDto costComponents =
        new CostComponentTraceDto(
            materialCost,
            laborCost,
            overheadCost,
            packagingCost,
            totalCost,
            safe(log.getMixedQuantity()),
            packedQuantity,
            packedQuantity.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(packedQuantity, 4, RoundingMode.HALF_UP)
                : safe(log.getUnitCost()));

    return new CostBreakdownDto(
        log.getId(),
        log.getProductionCode(),
        log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
        log.getBatchColour(),
        log.getMixedQuantity(),
        log.getMaterialCostTotal(),
        log.getLaborCostTotal(),
        log.getOverheadCostTotal(),
        totalCost,
        log.getUnitCost(),
        log.getProducedAt(),
        costComponents,
        packedBatches,
        rawMaterialTrace);
  }

  @Transactional(readOnly = true)
  public MonthlyProductionCostDto monthlyProductionCosts(Integer year, Integer month) {
    Company company = companyContextService.requireCurrentCompany();

    java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
    java.time.LocalDate startDate = yearMonth.atDay(1);
    java.time.LocalDate endDate = yearMonth.atEndOfMonth().plusDays(1);

    ZoneId zone = companyClock.zoneId(company);
    java.time.Instant startInstant = startDate.atStartOfDay(zone).toInstant();
    java.time.Instant endInstant = endDate.atStartOfDay(zone).toInstant();

    List<ProductionLog> logs =
        productionLogRepository.findFullyPackedBatchesByMonth(company, startInstant, endInstant);

    if (logs.isEmpty()) {
      return new MonthlyProductionCostDto(
          year,
          month,
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
    }

    BigDecimal totalLiters = BigDecimal.ZERO;
    BigDecimal totalMaterialCost = BigDecimal.ZERO;
    BigDecimal totalLaborCost = BigDecimal.ZERO;
    BigDecimal totalOverheadCost = BigDecimal.ZERO;
    BigDecimal totalPackagingCost = BigDecimal.ZERO;
    BigDecimal totalWastage = BigDecimal.ZERO;

    for (ProductionLog log : logs) {
      totalLiters = totalLiters.add(safe(log.getMixedQuantity()));
      totalMaterialCost = totalMaterialCost.add(safe(log.getMaterialCostTotal()));
      totalLaborCost = totalLaborCost.add(safe(log.getLaborCostTotal()));
      totalOverheadCost = totalOverheadCost.add(safe(log.getOverheadCostTotal()));
      totalPackagingCost = totalPackagingCost.add(resolvePackagingCost(company, log));
      totalWastage = totalWastage.add(safe(log.getWastageQuantity()));
    }

    BigDecimal totalCost =
        totalMaterialCost.add(totalLaborCost).add(totalOverheadCost).add(totalPackagingCost);
    BigDecimal avgCostPerLiter =
        totalLiters.compareTo(BigDecimal.ZERO) > 0
            ? totalCost.divide(totalLiters, 4, java.math.RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    BigDecimal wastagePercentage =
        totalLiters.compareTo(BigDecimal.ZERO) > 0
            ? totalWastage
                .divide(totalLiters, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
            : BigDecimal.ZERO;

    return new MonthlyProductionCostDto(
        year,
        month,
        logs.size(),
        totalLiters,
        totalMaterialCost,
        totalLaborCost,
        totalOverheadCost,
        totalCost,
        avgCostPerLiter,
        totalWastage,
        wastagePercentage);
  }

  private BigDecimal resolvePackagingCost(Company company, ProductionLog log) {
    return packingRecordRepository
        .findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log)
        .stream()
        .map(PackingRecord::getPackagingCost)
        .map(this::safe)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal resolveJournalAmount(JournalEntry entry) {
    if (entry == null || entry.getLines() == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal debitTotal =
        entry.getLines().stream()
            .map(JournalLine::getDebit)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return debitTotal.setScale(2, RoundingMode.HALF_UP);
  }

  private String resolveBatchCode(String referenceNumber) {
    if (referenceNumber == null || referenceNumber.isBlank()) {
      return "UNKNOWN";
    }
    String normalized = referenceNumber.trim();
    if (normalized.startsWith("CVAR-")) {
      String withoutPrefix = normalized.substring("CVAR-".length());
      int suffixIdx = withoutPrefix.lastIndexOf('-');
      return suffixIdx > 0 ? withoutPrefix.substring(0, suffixIdx) : withoutPrefix;
    }
    if (normalized.startsWith("CAL-")) {
      return normalized.substring("CAL-".length());
    }
    return normalized;
  }

  private String resolvePeriodKey(String referenceNumber) {
    if (referenceNumber == null || !referenceNumber.startsWith("CVAR-")) {
      return null;
    }
    int suffixIdx = referenceNumber.lastIndexOf('-');
    if (suffixIdx < 0 || suffixIdx == referenceNumber.length() - 1) {
      return null;
    }
    return referenceNumber.substring(suffixIdx + 1);
  }
}
