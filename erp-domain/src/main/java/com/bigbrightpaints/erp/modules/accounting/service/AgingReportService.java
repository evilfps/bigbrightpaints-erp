package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

/**
 * Service for generating aging reports and DSO calculations.
 *
 * Aging Buckets:
 * - Current (not yet due)
 * - 1-30 days overdue
 * - 31-60 days overdue
 * - 61-90 days overdue
 * - 90+ days overdue
 */
@Service
@Transactional(readOnly = true)
public class AgingReportService {

  private final DealerLedgerRepository dealerLedgerRepository;
  private final DealerRepository dealerRepository;
  private final CompanyContextService companyContextService;
  private final CompanyClock companyClock;

  public AgingReportService(
      DealerLedgerRepository dealerLedgerRepository,
      DealerRepository dealerRepository,
      CompanyContextService companyContextService,
      CompanyClock companyClock) {
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.dealerRepository = dealerRepository;
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
  }

  /**
   * Generate aged receivables report for all dealers
   */
  public AgedReceivablesReport getAgedReceivablesReport() {
    Company company = companyContextService.requireCurrentCompany();
    return getAgedReceivablesReport(companyClock.today(company));
  }

  public AgedReceivablesReport getAgedReceivablesReport(LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
    List<DealerLedgerEntry> unpaidEntries =
        dealerLedgerRepository.findAllUnpaidAsOf(company, effectiveDate);

    Map<Long, List<DealerLedgerEntry>> byDealer =
        unpaidEntries.stream().collect(Collectors.groupingBy(e -> e.getDealer().getId()));

    List<DealerAgingDetail> dealerDetails = new ArrayList<>();
    AgingBuckets totalBuckets = new AgingBuckets();

    for (Map.Entry<Long, List<DealerLedgerEntry>> entry : byDealer.entrySet()) {
      Dealer dealer = entry.getValue().get(0).getDealer();
      AgingBuckets buckets = calculateBuckets(entry.getValue(), effectiveDate);

      dealerDetails.add(
          new DealerAgingDetail(
              dealer.getId(), dealer.getCode(), dealer.getName(), buckets, buckets.total()));

      totalBuckets = totalBuckets.add(buckets);
    }

    // Sort by total outstanding descending
    dealerDetails.sort((a, b) -> b.totalOutstanding().compareTo(a.totalOutstanding()));

    return new AgedReceivablesReport(
        effectiveDate, dealerDetails, totalBuckets, totalBuckets.total());
  }

  /**
   * Get aging detail for a specific dealer
   */
  public DealerAgingDetail getDealerAging(Long dealerId) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Dealer not found"));

    List<DealerLedgerEntry> unpaid = dealerLedgerRepository.findUnpaidByDealer(company, dealer);
    LocalDate today = companyClock.today(company);
    AgingBuckets buckets = calculateBuckets(unpaid, today);

    return new DealerAgingDetail(
        dealer.getId(), dealer.getCode(), dealer.getName(), buckets, buckets.total());
  }

  /**
   * Get detailed aging with individual invoices
   */
  public DealerAgingDetailedReport getDealerAgingDetailed(Long dealerId) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Dealer not found"));

    List<DealerLedgerEntry> unpaid = dealerLedgerRepository.findUnpaidByDealer(company, dealer);
    LocalDate today = companyClock.today(company);

    List<AgingLineItem> lineItems =
        unpaid.stream()
            .map(
                e ->
                    new AgingLineItem(
                        e.getId(),
                        e.getInvoiceNumber(),
                        e.getReferenceNumber(),
                        e.getEntryDate(),
                        e.getDueDate(),
                        e.getOutstandingAmount(),
                        e.getDueDate() != null ? calculateDaysOverdue(e.getDueDate(), today) : 0,
                        getAgingBucket(e.getDueDate(), today)))
            .collect(Collectors.toList());

    AgingBuckets buckets = calculateBuckets(unpaid, today);
    Double avgDSO = dealerLedgerRepository.calculateAverageDSO(company.getId(), dealer.getId());

    return new DealerAgingDetailedReport(
        dealer.getId(),
        dealer.getCode(),
        dealer.getName(),
        lineItems,
        buckets,
        buckets.total(),
        avgDSO != null ? avgDSO : 0.0);
  }

  /**
   * Calculate DSO (Days Sales Outstanding) for a dealer
   */
  public DSOReport getDealerDSO(Long dealerId) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Dealer not found"));

    Double avgDSO = dealerLedgerRepository.calculateAverageDSO(company.getId(), dealer.getId());
    List<DealerLedgerEntry> unpaid = dealerLedgerRepository.findUnpaidByDealer(company, dealer);

    BigDecimal totalOutstanding =
        unpaid.stream()
            .map(DealerLedgerEntry::getOutstandingAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    LocalDate today = companyClock.today(company);
    long overdueCount = unpaid.stream().filter(entry -> entry.isOverdue(today)).count();

    return new DSOReport(
        dealer.getId(),
        dealer.getName(),
        avgDSO != null ? avgDSO : 0.0,
        totalOutstanding,
        unpaid.size(),
        overdueCount);
  }

  private AgingBuckets calculateBuckets(List<DealerLedgerEntry> entries, LocalDate asOfDate) {
    BigDecimal current = BigDecimal.ZERO;
    BigDecimal days1to30 = BigDecimal.ZERO;
    BigDecimal days31to60 = BigDecimal.ZERO;
    BigDecimal days61to90 = BigDecimal.ZERO;
    BigDecimal over90 = BigDecimal.ZERO;

    for (DealerLedgerEntry entry : entries) {
      BigDecimal amount = entry.getOutstandingAmount();
      LocalDate dueDate = entry.getDueDate();

      if (dueDate == null || !asOfDate.isAfter(dueDate)) {
        current = current.add(amount);
      } else {
        long daysOverdue = calculateDaysOverdue(dueDate, asOfDate);
        if (daysOverdue <= 30) {
          days1to30 = days1to30.add(amount);
        } else if (daysOverdue <= 60) {
          days31to60 = days31to60.add(amount);
        } else if (daysOverdue <= 90) {
          days61to90 = days61to90.add(amount);
        } else {
          over90 = over90.add(amount);
        }
      }
    }

    return new AgingBuckets(current, days1to30, days31to60, days61to90, over90);
  }

  private long calculateDaysOverdue(LocalDate dueDate, LocalDate asOfDate) {
    if (dueDate == null || !asOfDate.isAfter(dueDate)) {
      return 0;
    }
    return java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOfDate);
  }

  private String getAgingBucket(LocalDate dueDate, LocalDate asOfDate) {
    if (dueDate == null || !asOfDate.isAfter(dueDate)) {
      return "CURRENT";
    }
    long days = calculateDaysOverdue(dueDate, asOfDate);
    if (days <= 30) return "1-30";
    if (days <= 60) return "31-60";
    if (days <= 90) return "61-90";
    return "90+";
  }

  // DTOs
  public record AgingBuckets(
      BigDecimal current,
      BigDecimal days1to30,
      BigDecimal days31to60,
      BigDecimal days61to90,
      BigDecimal over90) {
    public AgingBuckets() {
      this(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal total() {
      return current.add(days1to30).add(days31to60).add(days61to90).add(over90);
    }

    public AgingBuckets add(AgingBuckets other) {
      return new AgingBuckets(
          current.add(other.current),
          days1to30.add(other.days1to30),
          days31to60.add(other.days31to60),
          days61to90.add(other.days61to90),
          over90.add(other.over90));
    }
  }

  public record DealerAgingDetail(
      Long dealerId,
      String dealerCode,
      String dealerName,
      AgingBuckets buckets,
      BigDecimal totalOutstanding) {}

  public record AgedReceivablesReport(
      LocalDate asOfDate,
      List<DealerAgingDetail> dealers,
      AgingBuckets totalBuckets,
      BigDecimal grandTotal) {}

  public record AgingLineItem(
      Long entryId,
      String invoiceNumber,
      String referenceNumber,
      LocalDate invoiceDate,
      LocalDate dueDate,
      BigDecimal outstandingAmount,
      long daysOverdue,
      String agingBucket) {}

  public record DealerAgingDetailedReport(
      Long dealerId,
      String dealerCode,
      String dealerName,
      List<AgingLineItem> lineItems,
      AgingBuckets buckets,
      BigDecimal totalOutstanding,
      double averageDSO) {}

  public record DSOReport(
      Long dealerId,
      String dealerName,
      double averageDSO,
      BigDecimal totalOutstanding,
      int openInvoices,
      long overdueInvoices) {}
}
