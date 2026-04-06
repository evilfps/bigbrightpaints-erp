package com.bigbrightpaints.erp.modules.portal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.DashboardWindow;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.portal.dto.EnterpriseDashboardSnapshot;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@Service
@Transactional(readOnly = true)
public class EnterpriseDashboardService {

  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final Set<String> REVENUE_STATUSES = Set.of("ISSUED", "PAID", "PARTIAL");
  private static final Set<String> CLOSED_ORDER_STATUSES =
      Set.of("SHIPPED", "FULFILLED", "CLOSED", "CANCELLED", "REJECTED");
  private static final Set<String> DISPATCH_BACKLOG_STATUSES = Set.of("PENDING", "BACKORDER");
  private static final int TOP_LIMIT = 5;

  private final CompanyContextService companyContextService;
  private final InvoiceRepository invoiceRepository;
  private final SalesOrderRepository salesOrderRepository;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountRepository accountRepository;
  private final ProductionLogRepository productionLogRepository;
  private final PackingRecordRepository packingRecordRepository;
  private final PackagingSlipRepository packagingSlipRepository;
  private final ReportService reportService;

  public EnterpriseDashboardService(
      CompanyContextService companyContextService,
      InvoiceRepository invoiceRepository,
      SalesOrderRepository salesOrderRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      JournalEntryRepository journalEntryRepository,
      AccountRepository accountRepository,
      ProductionLogRepository productionLogRepository,
      PackingRecordRepository packingRecordRepository,
      PackagingSlipRepository packagingSlipRepository,
      ReportService reportService) {
    this.companyContextService = companyContextService;
    this.invoiceRepository = invoiceRepository;
    this.salesOrderRepository = salesOrderRepository;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.accountRepository = accountRepository;
    this.productionLogRepository = productionLogRepository;
    this.packingRecordRepository = packingRecordRepository;
    this.packagingSlipRepository = packagingSlipRepository;
    this.reportService = reportService;
  }

  public EnterpriseDashboardSnapshot snapshot(String window, String compare, String timezone) {
    Company company = companyContextService.requireCurrentCompany();
    DashboardWindow range =
        DashboardWindow.resolve(window, compare, timezone, company.getTimezone());

    List<Invoice> invoicesInWindow =
        invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            company, range.start(), range.end());
    List<Invoice> allInvoices = invoiceRepository.findByCompanyOrderByIssueDateDesc(company);

    BigDecimal netRevenue = sumInvoices(invoicesInWindow, Invoice::getSubtotal);
    BigDecimal taxRevenue = sumInvoices(invoicesInWindow, Invoice::getTaxTotal);
    BigDecimal grossRevenue = sumInvoices(invoicesInWindow, Invoice::getTotalAmount);

    BigDecimal arOutstanding = ZERO;
    BigDecimal overdueOutstanding = ZERO;
    EnterpriseDashboardSnapshot.ArAging aging = computeAging(allInvoices, range.end());
    arOutstanding = aging.total();
    overdueOutstanding = aging.total().subtract(aging.current());

    List<JournalEntry> journalEntries =
        journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, range.start(), range.end());
    List<JournalEntry> entriesAfterWindow =
        journalEntryRepository.findByCompanyAndEntryDateAfterOrderByEntryDateAsc(
            company, range.end());
    BigDecimal cogs = computeCogs(journalEntries);

    List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<Account> cashAccounts = accounts.stream().filter(this::isCashAccount).toList();
    BigDecimal cashBalance =
        cashAccounts.isEmpty()
            ? null
            : cashAccounts.stream().map(Account::getBalance).reduce(ZERO, BigDecimal::add);

    InventoryValuationDto inventoryValuation = reportService.inventoryValuation();
    BigDecimal inventoryValue = inventoryValuation.totalValue();

    BigDecimal inventoryTurns =
        computeInventoryTurns(
            accounts,
            journalEntries,
            entriesAfterWindow,
            cogs,
            company.getDefaultInventoryAccountId());

    List<SalesOrder> orders = salesOrderRepository.findByCompanyOrderByCreatedAtDesc(company);
    BigDecimal bookedBacklog =
        orders.stream()
            .filter(order -> !isClosedOrder(order.getStatus()))
            .map(order -> safe(order.getTotalAmount()))
            .reduce(ZERO, BigDecimal::add);
    long openOrders = orders.stream().filter(order -> !isClosedOrder(order.getStatus())).count();
    BigDecimal bookedOrderValue =
        orders.stream()
            .filter(order -> isWithin(order.getCreatedAt(), range, range.zone()))
            .map(order -> safe(order.getTotalAmount()))
            .reduce(ZERO, BigDecimal::add);
    long bookedOrderCount =
        orders.stream()
            .filter(order -> isWithin(order.getCreatedAt(), range, range.zone()))
            .count();

    Double orderToCashDays = computeOrderToCash(company, range);

    ProductionTotals productionTotals = computeProductionTotals(company, range);

    Double grossMarginPct = percentage(netRevenue.subtract(cogs), netRevenue);
    Double overduePct = percentage(overdueOutstanding, arOutstanding);

    Double onTimeDispatchPct = null;

    List<EnterpriseDashboardSnapshot.Alert> alerts =
        buildAlerts(
            cashBalance,
            overduePct,
            inventoryValuation.lowStockItems(),
            company,
            range,
            onTimeDispatchPct);

    Map<LocalDate, BigDecimal> revenueByDay = aggregateInvoiceByDate(invoicesInWindow);
    Map<LocalDate, BigDecimal> cogsByDay = aggregateCogsByDate(journalEntries);
    Map<LocalDate, BigDecimal> cashMovements =
        aggregateCashMovementsByDate(journalEntries, cashAccounts);
    List<EnterpriseDashboardSnapshot.SeriesPoint> revenueTrend = buildSeries(revenueByDay, range);
    List<EnterpriseDashboardSnapshot.SeriesPoint> cogsTrend = buildSeries(cogsByDay, range);
    List<EnterpriseDashboardSnapshot.SeriesPoint> cashTrend =
        buildCashSeries(cashBalance, cashMovements, range);
    List<EnterpriseDashboardSnapshot.SeriesPoint> arOverdueTrend =
        buildOverdueSeries(allInvoices, range);

    EnterpriseDashboardSnapshot.Trends trends =
        new EnterpriseDashboardSnapshot.Trends(revenueTrend, cogsTrend, cashTrend, arOverdueTrend);

    EnterpriseDashboardSnapshot.Breakdown breakdowns =
        new EnterpriseDashboardSnapshot.Breakdown(
            topDealers(invoicesInWindow),
            topSkus(invoicesInWindow),
            topOverdueInvoices(allInvoices, range.end()));

    EnterpriseDashboardSnapshot.Window windowDto =
        new EnterpriseDashboardSnapshot.Window(
            range.start(),
            range.end(),
            range.compareStart(),
            range.compareEnd(),
            range.zone().getId(),
            range.bucket());

    EnterpriseDashboardSnapshot.Financial financial =
        new EnterpriseDashboardSnapshot.Financial(
            company.getBaseCurrency(),
            netRevenue,
            taxRevenue,
            grossRevenue,
            cogs,
            netRevenue.subtract(cogs),
            cashBalance,
            arOutstanding,
            overdueOutstanding,
            aging);

    EnterpriseDashboardSnapshot.Sales sales =
        new EnterpriseDashboardSnapshot.Sales(
            bookedBacklog, openOrders, bookedOrderValue, bookedOrderCount, orderToCashDays);

    EnterpriseDashboardSnapshot.Operations operations =
        new EnterpriseDashboardSnapshot.Operations(
            inventoryValue,
            inventoryTurns != null ? inventoryTurns.doubleValue() : null,
            productionTotals.producedQty(),
            productionTotals.packedQty(),
            productionTotals.dispatchedQty(),
            productionTotals.yieldPct(),
            productionTotals.wastagePct());

    EnterpriseDashboardSnapshot.Ratios ratios =
        new EnterpriseDashboardSnapshot.Ratios(
            grossMarginPct,
            overduePct,
            inventoryTurns != null ? inventoryTurns.doubleValue() : null,
            onTimeDispatchPct);

    return new EnterpriseDashboardSnapshot(
        windowDto, financial, sales, operations, ratios, trends, alerts, breakdowns);
  }

  private EnterpriseDashboardSnapshot.ArAging computeAging(List<Invoice> invoices, LocalDate asOf) {
    BigDecimal current = ZERO;
    BigDecimal days1to30 = ZERO;
    BigDecimal days31to60 = ZERO;
    BigDecimal days61to90 = ZERO;
    BigDecimal over90 = ZERO;
    for (Invoice invoice : invoices) {
      if (!isOutstanding(invoice)) {
        continue;
      }
      LocalDate dueDate = invoice.getDueDate();
      BigDecimal outstanding = safe(invoice.getOutstandingAmount());
      if (dueDate == null || !asOf.isAfter(dueDate)) {
        current = current.add(outstanding);
        continue;
      }
      long daysPastDue = ChronoUnit.DAYS.between(dueDate, asOf);
      if (daysPastDue <= 30) {
        days1to30 = days1to30.add(outstanding);
      } else if (daysPastDue <= 60) {
        days31to60 = days31to60.add(outstanding);
      } else if (daysPastDue <= 90) {
        days61to90 = days61to90.add(outstanding);
      } else {
        over90 = over90.add(outstanding);
      }
    }
    BigDecimal total = current.add(days1to30).add(days31to60).add(days61to90).add(over90);
    return new EnterpriseDashboardSnapshot.ArAging(
        current, days1to30, days31to60, days61to90, over90, total);
  }

  private BigDecimal computeCogs(List<JournalEntry> entries) {
    BigDecimal total = ZERO;
    for (JournalEntry entry : entries) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      for (JournalLine line : entry.getLines()) {
        Account account = line.getAccount();
        if (account != null && AccountType.COGS.equals(account.getType())) {
          total = total.add(safe(line.getDebit()).subtract(safe(line.getCredit())));
        }
      }
    }
    return total;
  }

  private BigDecimal computeInventoryTurns(
      List<Account> accounts,
      List<JournalEntry> entries,
      List<JournalEntry> entriesAfterWindow,
      BigDecimal cogs,
      Long defaultInventoryAccountId) {
    List<Account> inventoryAccounts =
        accounts.stream()
            .filter(account -> isInventoryAccount(account, defaultInventoryAccountId))
            .toList();
    if (inventoryAccounts.isEmpty()) {
      return null;
    }
    Map<Long, Account> inventoryById =
        inventoryAccounts.stream()
            .filter(account -> account.getId() != null)
            .collect(Collectors.toMap(Account::getId, account -> account));
    BigDecimal currentBalance =
        inventoryById.values().stream().map(Account::getBalance).reduce(ZERO, BigDecimal::add);
    BigDecimal netChangeWithinWindow = ZERO;
    for (JournalEntry entry : entries) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      for (JournalLine line : entry.getLines()) {
        Account account = line.getAccount();
        if (account != null
            && account.getId() != null
            && inventoryById.containsKey(account.getId())) {
          netChangeWithinWindow =
              netChangeWithinWindow.add(safe(line.getDebit()).subtract(safe(line.getCredit())));
        }
      }
    }
    BigDecimal netChangeAfterWindow = ZERO;
    for (JournalEntry entry : entriesAfterWindow) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      for (JournalLine line : entry.getLines()) {
        Account account = line.getAccount();
        if (account != null
            && account.getId() != null
            && inventoryById.containsKey(account.getId())) {
          netChangeAfterWindow =
              netChangeAfterWindow.add(safe(line.getDebit()).subtract(safe(line.getCredit())));
        }
      }
    }
    BigDecimal endingAtWindow = currentBalance.subtract(netChangeAfterWindow);
    BigDecimal beginning = endingAtWindow.subtract(netChangeWithinWindow);
    BigDecimal average =
        beginning.add(endingAtWindow).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    if (average.compareTo(ZERO) <= 0) {
      return null;
    }
    return cogs.divide(average, 4, RoundingMode.HALF_UP);
  }

  private ProductionTotals computeProductionTotals(Company company, DashboardWindow range) {
    Instant start = range.startInstant();
    Instant end = range.endExclusiveInstant();
    List<ProductionLog> logs =
        productionLogRepository.findByCompanyAndProducedAtBetween(company, start, end);
    BigDecimal produced = ZERO;
    BigDecimal wastage = ZERO;
    for (ProductionLog log : logs) {
      produced = produced.add(safe(log.getMixedQuantity()));
      wastage = wastage.add(safe(log.getWastageQuantity()));
    }
    List<PackingRecord> packingRecords =
        packingRecordRepository.findByCompanyAndPackedDateBetween(
            company, range.start(), range.end());
    BigDecimal packed =
        packingRecords.stream().map(PackingRecord::getQuantityPacked).reduce(ZERO, BigDecimal::add);

    List<PackagingSlip> dispatched =
        packagingSlipRepository.findByCompanyAndDispatchedAtBetween(company, start, end);
    BigDecimal dispatchedQty = ZERO;
    for (PackagingSlip slip : dispatched) {
      for (PackagingSlipLine line : slip.getLines()) {
        BigDecimal qty =
            line.getShippedQuantity() != null ? line.getShippedQuantity() : line.getQuantity();
        dispatchedQty = dispatchedQty.add(safe(qty));
      }
    }
    Double yieldPct =
        produced.compareTo(ZERO) > 0
            ? packed.divide(produced, 4, RoundingMode.HALF_UP).doubleValue() * 100d
            : null;
    Double wastagePct =
        produced.compareTo(ZERO) > 0
            ? wastage.divide(produced, 4, RoundingMode.HALF_UP).doubleValue() * 100d
            : null;
    return new ProductionTotals(produced, packed, dispatchedQty, yieldPct, wastagePct);
  }

  private Double computeOrderToCash(Company company, DashboardWindow range) {
    List<PartnerSettlementAllocation> allocations =
        settlementAllocationRepository.findByCompanyAndPartnerTypeAndSettlementDateBetween(
            company, PartnerType.DEALER, range.start(), range.end());
    Map<Long, List<PartnerSettlementAllocation>> byInvoice =
        allocations.stream()
            .filter(a -> a.getInvoice() != null)
            .collect(Collectors.groupingBy(a -> a.getInvoice().getId()));
    if (byInvoice.isEmpty()) {
      return null;
    }
    long count = 0;
    long totalDays = 0;
    for (Map.Entry<Long, List<PartnerSettlementAllocation>> entry : byInvoice.entrySet()) {
      Invoice invoice = entry.getValue().get(0).getInvoice();
      if (invoice == null || invoice.getOutstandingAmount() == null) {
        continue;
      }
      if (invoice.getOutstandingAmount().compareTo(ZERO) > 0) {
        continue;
      }
      LocalDate settlementDate =
          entry.getValue().stream()
              .map(PartnerSettlementAllocation::getSettlementDate)
              .max(Comparator.naturalOrder())
              .orElse(null);
      if (settlementDate == null) {
        continue;
      }
      LocalDate orderDate =
          invoice.getSalesOrder() != null && invoice.getSalesOrder().getCreatedAt() != null
              ? LocalDate.ofInstant(invoice.getSalesOrder().getCreatedAt(), range.zone())
              : invoice.getIssueDate();
      if (orderDate == null) {
        continue;
      }
      long days = ChronoUnit.DAYS.between(orderDate, settlementDate);
      totalDays += Math.max(days, 0);
      count++;
    }
    if (count == 0) {
      return null;
    }
    return totalDays / (double) count;
  }

  private List<EnterpriseDashboardSnapshot.Alert> buildAlerts(
      BigDecimal cashBalance,
      Double overduePct,
      long lowStockItems,
      Company company,
      DashboardWindow range,
      Double onTimeDispatchPct) {
    List<EnterpriseDashboardSnapshot.Alert> alerts = new ArrayList<>();
    if (cashBalance != null && cashBalance.compareTo(ZERO) <= 0) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "HIGH",
              "LOW_CASH",
              "Cash balance is at or below zero.",
              Map.of("cashBalance", cashBalance)));
    }
    if (overduePct != null && overduePct >= 25d) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "MEDIUM",
              "OVERDUE_AR",
              "Overdue receivables exceed 25% of total AR.",
              Map.of("overduePct", overduePct)));
    }
    if (lowStockItems > 0) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "MEDIUM",
              "LOW_INVENTORY",
              "Low stock items detected.",
              Map.of("lowStockItems", lowStockItems)));
    }
    Instant cutoff = range.endExclusiveInstant().minus(7, ChronoUnit.DAYS);
    long pendingDispatch =
        packagingSlipRepository.countByCompanyAndStatusInAndCreatedAtBefore(
            company, DISPATCH_BACKLOG_STATUSES, cutoff);
    if (pendingDispatch > 0) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "MEDIUM",
              "DISPATCH_BACKLOG",
              "Pending dispatch slips older than 7 days.",
              Map.of("pendingSlips", pendingDispatch)));
    }
    if (onTimeDispatchPct == null) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "INFO",
              "MISSING_PROMISED_DATE",
              "On-time dispatch requires promised dates on sales orders.",
              Map.of()));
    }
    if (cashBalance == null) {
      alerts.add(
          new EnterpriseDashboardSnapshot.Alert(
              "INFO",
              "CASH_ACCOUNT_UNMAPPED",
              "Cash trend requires cash/bank accounts to be identifiable.",
              Map.of()));
    }
    return alerts;
  }

  private Map<LocalDate, BigDecimal> aggregateInvoiceByDate(List<Invoice> invoices) {
    Map<LocalDate, BigDecimal> totals = new HashMap<>();
    for (Invoice invoice : invoices) {
      if (!isRevenueInvoice(invoice) || invoice.getIssueDate() == null) {
        continue;
      }
      totals.merge(invoice.getIssueDate(), safe(invoice.getSubtotal()), BigDecimal::add);
    }
    return totals;
  }

  private Map<LocalDate, BigDecimal> aggregateCogsByDate(List<JournalEntry> entries) {
    Map<LocalDate, BigDecimal> totals = new HashMap<>();
    for (JournalEntry entry : entries) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      LocalDate date = entry.getEntryDate();
      if (date == null) {
        continue;
      }
      BigDecimal total = totals.getOrDefault(date, ZERO);
      for (JournalLine line : entry.getLines()) {
        Account account = line.getAccount();
        if (account != null && AccountType.COGS.equals(account.getType())) {
          total = total.add(safe(line.getDebit()).subtract(safe(line.getCredit())));
        }
      }
      totals.put(date, total);
    }
    return totals;
  }

  private Map<LocalDate, BigDecimal> aggregateCashMovementsByDate(
      List<JournalEntry> entries, List<Account> cashAccounts) {
    if (cashAccounts.isEmpty()) {
      return Map.of();
    }
    Set<Long> cashIds = cashAccounts.stream().map(Account::getId).collect(Collectors.toSet());
    Map<LocalDate, BigDecimal> movements = new HashMap<>();
    for (JournalEntry entry : entries) {
      if (entry.getStatus() != JournalEntryStatus.POSTED) {
        continue;
      }
      LocalDate date = entry.getEntryDate();
      if (date == null) {
        continue;
      }
      BigDecimal delta = ZERO;
      for (JournalLine line : entry.getLines()) {
        Account account = line.getAccount();
        if (account != null && cashIds.contains(account.getId())) {
          delta = delta.add(safe(line.getDebit()).subtract(safe(line.getCredit())));
        }
      }
      if (delta.compareTo(ZERO) != 0) {
        movements.merge(date, delta, BigDecimal::add);
      }
    }
    return movements;
  }

  private List<EnterpriseDashboardSnapshot.SeriesPoint> buildSeries(
      Map<LocalDate, BigDecimal> dailyValues, DashboardWindow range) {
    List<EnterpriseDashboardSnapshot.SeriesPoint> series = new ArrayList<>();
    for (LocalDate bucketStart : range.bucketStarts()) {
      LocalDate bucketEnd = bucketStart.plusDays(range.bucketDays() - 1L);
      if (bucketEnd.isAfter(range.end())) {
        bucketEnd = range.end();
      }
      BigDecimal sum = ZERO;
      LocalDate cursor = bucketStart;
      while (!cursor.isAfter(bucketEnd)) {
        sum = sum.add(dailyValues.getOrDefault(cursor, ZERO));
        cursor = cursor.plusDays(1);
      }
      series.add(new EnterpriseDashboardSnapshot.SeriesPoint(bucketStart.toString(), sum));
    }
    return series;
  }

  private List<EnterpriseDashboardSnapshot.SeriesPoint> buildCashSeries(
      BigDecimal cashBalance, Map<LocalDate, BigDecimal> movements, DashboardWindow range) {
    List<EnterpriseDashboardSnapshot.SeriesPoint> series = new ArrayList<>();
    if (cashBalance == null) {
      return series;
    }
    BigDecimal netMovement = movements.values().stream().reduce(ZERO, BigDecimal::add);
    BigDecimal startingBalance = cashBalance.subtract(netMovement);
    Map<LocalDate, BigDecimal> balances = new HashMap<>();
    BigDecimal running = startingBalance;
    LocalDate cursor = range.start();
    while (!cursor.isAfter(range.end())) {
      running = running.add(movements.getOrDefault(cursor, ZERO));
      balances.put(cursor, running);
      cursor = cursor.plusDays(1);
    }
    for (LocalDate bucketStart : range.bucketStarts()) {
      LocalDate bucketEnd = bucketStart.plusDays(range.bucketDays() - 1L);
      if (bucketEnd.isAfter(range.end())) {
        bucketEnd = range.end();
      }
      series.add(
          new EnterpriseDashboardSnapshot.SeriesPoint(
              bucketStart.toString(), balances.get(bucketEnd)));
    }
    return series;
  }

  private List<EnterpriseDashboardSnapshot.SeriesPoint> buildOverdueSeries(
      List<Invoice> invoices, DashboardWindow range) {
    List<EnterpriseDashboardSnapshot.SeriesPoint> series = new ArrayList<>();
    for (LocalDate bucketStart : range.bucketStarts()) {
      LocalDate bucketEnd = bucketStart.plusDays(range.bucketDays() - 1L);
      if (bucketEnd.isAfter(range.end())) {
        bucketEnd = range.end();
      }
      BigDecimal overdue = overdueAsOf(invoices, bucketEnd);
      series.add(new EnterpriseDashboardSnapshot.SeriesPoint(bucketStart.toString(), overdue));
    }
    return series;
  }

  private BigDecimal overdueAsOf(List<Invoice> invoices, LocalDate asOf) {
    BigDecimal total = ZERO;
    for (Invoice invoice : invoices) {
      if (!isOutstanding(invoice)) {
        continue;
      }
      LocalDate dueDate = invoice.getDueDate();
      if (dueDate != null && !asOf.isBefore(dueDate)) {
        total = total.add(safe(invoice.getOutstandingAmount()));
      }
    }
    return total;
  }

  private List<EnterpriseDashboardSnapshot.TopDealer> topDealers(List<Invoice> invoices) {
    Map<String, BigDecimal> totals = new HashMap<>();
    BigDecimal totalRevenue = ZERO;
    for (Invoice invoice : invoices) {
      if (!isRevenueInvoice(invoice) || invoice.getDealer() == null) {
        continue;
      }
      String name = invoice.getDealer().getName();
      BigDecimal value = safe(invoice.getSubtotal());
      totalRevenue = totalRevenue.add(value);
      totals.merge(name, value, BigDecimal::add);
    }
    final BigDecimal denominator = totalRevenue;
    return totals.entrySet().stream()
        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
        .limit(TOP_LIMIT)
        .map(
            entry ->
                new EnterpriseDashboardSnapshot.TopDealer(
                    entry.getKey(), entry.getValue(), percentage(entry.getValue(), denominator)))
        .toList();
  }

  private List<EnterpriseDashboardSnapshot.TopSku> topSkus(List<Invoice> invoices) {
    Map<String, BigDecimal> revenue = new HashMap<>();
    Map<String, BigDecimal> quantity = new HashMap<>();
    for (Invoice invoice : invoices) {
      if (!isRevenueInvoice(invoice)) {
        continue;
      }
      for (InvoiceLine line : invoice.getLines()) {
        String code = line.getProductCode() != null ? line.getProductCode() : "UNKNOWN";
        revenue.merge(code, safe(line.getLineTotal()), BigDecimal::add);
        quantity.merge(code, safe(line.getQuantity()), BigDecimal::add);
      }
    }
    return revenue.entrySet().stream()
        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
        .limit(TOP_LIMIT)
        .map(
            entry ->
                new EnterpriseDashboardSnapshot.TopSku(
                    entry.getKey(), entry.getValue(), quantity.getOrDefault(entry.getKey(), ZERO)))
        .toList();
  }

  private List<EnterpriseDashboardSnapshot.OverdueInvoice> topOverdueInvoices(
      List<Invoice> invoices, LocalDate asOf) {
    return invoices.stream()
        .filter(this::isOutstanding)
        .filter(invoice -> invoice.getDueDate() != null && asOf.isAfter(invoice.getDueDate()))
        .sorted(
            Comparator.comparingLong(
                    (Invoice invoice) -> ChronoUnit.DAYS.between(invoice.getDueDate(), asOf))
                .reversed()
                .thenComparing(
                    invoice -> safe(invoice.getOutstandingAmount()), Comparator.reverseOrder()))
        .limit(TOP_LIMIT)
        .map(
            invoice ->
                new EnterpriseDashboardSnapshot.OverdueInvoice(
                    invoice.getInvoiceNumber(),
                    invoice.getDealer() != null ? invoice.getDealer().getName() : "Unknown",
                    invoice.getDueDate(),
                    ChronoUnit.DAYS.between(invoice.getDueDate(), asOf),
                    safe(invoice.getOutstandingAmount())))
        .toList();
  }

  private boolean isRevenueInvoice(Invoice invoice) {
    if (invoice == null || invoice.getStatus() == null) {
      return false;
    }
    return REVENUE_STATUSES.contains(invoice.getStatus().toUpperCase(Locale.ROOT));
  }

  private boolean isOutstanding(Invoice invoice) {
    if (invoice == null || invoice.getOutstandingAmount() == null) {
      return false;
    }
    if (invoice.getOutstandingAmount().compareTo(ZERO) <= 0) {
      return false;
    }
    String status = invoice.getStatus();
    if (status == null) {
      return false;
    }
    return !"VOID".equalsIgnoreCase(status)
        && !"REVERSED".equalsIgnoreCase(status)
        && !"DRAFT".equalsIgnoreCase(status);
  }

  private BigDecimal sumInvoices(
      List<Invoice> invoices, java.util.function.Function<Invoice, BigDecimal> extractor) {
    return invoices.stream()
        .filter(this::isRevenueInvoice)
        .map(extractor)
        .map(EnterpriseDashboardService::safe)
        .reduce(ZERO, BigDecimal::add);
  }

  private boolean isClosedOrder(String status) {
    if (status == null) {
      return false;
    }
    return CLOSED_ORDER_STATUSES.contains(status.toUpperCase(Locale.ROOT));
  }

  private boolean isWithin(Instant timestamp, DashboardWindow range, ZoneId zone) {
    if (timestamp == null) {
      return false;
    }
    LocalDate date = LocalDate.ofInstant(timestamp, zone);
    return !date.isBefore(range.start()) && !date.isAfter(range.end());
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

  private boolean isInventoryAccount(Account account, Long defaultInventoryAccountId) {
    if (account == null) {
      return false;
    }
    if (defaultInventoryAccountId != null && defaultInventoryAccountId.equals(account.getId())) {
      return true;
    }
    String label = (account.getCode() + " " + account.getName()).toLowerCase(Locale.ROOT);
    return label.contains("inventory");
  }

  private static BigDecimal safe(BigDecimal value) {
    return value != null ? value : ZERO;
  }

  private static Double percentage(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(ZERO) <= 0) {
      return null;
    }
    return numerator
        .multiply(BigDecimal.valueOf(100))
        .divide(denominator, 4, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private record ProductionTotals(
      BigDecimal producedQty,
      BigDecimal packedQty,
      BigDecimal dispatchedQty,
      Double yieldPct,
      Double wastagePct) {}
}
