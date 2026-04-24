package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
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

  private static final String REPORT_AGING_BUCKETS = "0-0,1-30,31-60,61-90,91";

  private final DealerLedgerRepository dealerLedgerRepository;
  private final DealerRepository dealerRepository;
  private final CompanyContextService companyContextService;
  private final CompanyClock companyClock;
  private final StatementService statementService;

  public AgingReportService(
      DealerLedgerRepository dealerLedgerRepository,
      DealerRepository dealerRepository,
      CompanyContextService companyContextService,
      CompanyClock companyClock,
      StatementService statementService) {
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.dealerRepository = dealerRepository;
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
    this.statementService = statementService;
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
    List<DealerAgingDetail> dealerDetails = getDealerAgingRows(effectiveDate);
    AgingBuckets totalBuckets =
        dealerDetails.stream()
            .map(DealerAgingDetail::buckets)
            .reduce(new AgingBuckets(), AgingBuckets::add);
    return new AgedReceivablesReport(
        effectiveDate, dealerDetails, totalBuckets, totalBuckets.total());
  }

  public List<DealerAgingDetail> getDealerAgingRows(LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
    return buildDealerAgingRows(company, effectiveDate, null, null, false);
  }

  public List<DealerAgingDetail> getDealerAgingRows(
      LocalDate asOfDate, LocalDate startDate, LocalDate endDate) {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
    return buildDealerAgingRows(company, effectiveDate, startDate, endDate, true);
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
    LocalDate today = companyClock.today(company);
    AgingSummaryResponse summary =
        statementService.dealerAging(dealer, today, REPORT_AGING_BUCKETS);
    AgingBuckets buckets = toAgingBuckets(summary);
    BigDecimal totalOutstanding = safe(summary != null ? summary.totalOutstanding() : null);

    return new DealerAgingDetail(
        dealer.getId(), dealer.getCode(), dealer.getName(), buckets, totalOutstanding);
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

  private List<DealerAgingDetail> buildDealerAgingRows(
      Company company,
      LocalDate effectiveDate,
      LocalDate startDate,
      LocalDate endDate,
      boolean filterByEntryWindow) {
    List<DealerAgingDetail> dealerDetails = new ArrayList<>();
    for (Dealer dealer : dealerRepository.findByCompanyOrderByNameAsc(company)) {
      AgingSummaryResponse summary =
          resolveCanonicalDealerAging(
              dealer, effectiveDate, startDate, endDate, filterByEntryWindow);
      BigDecimal totalOutstanding = safe(summary != null ? summary.totalOutstanding() : null);
      if (totalOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      dealerDetails.add(
          new DealerAgingDetail(
              dealer.getId(),
              dealer.getCode(),
              dealer.getName(),
              toAgingBuckets(summary),
              totalOutstanding));
    }
    dealerDetails.sort((a, b) -> b.totalOutstanding().compareTo(a.totalOutstanding()));
    return dealerDetails;
  }

  private AgingSummaryResponse resolveCanonicalDealerAging(
      Dealer dealer,
      LocalDate asOfDate,
      LocalDate startDate,
      LocalDate endDate,
      boolean filterByEntryWindow) {
    if (filterByEntryWindow) {
      return statementService.dealerAgingWithinEntryWindow(
          dealer, asOfDate, REPORT_AGING_BUCKETS, startDate, endDate);
    }
    return statementService.dealerAging(dealer, asOfDate, REPORT_AGING_BUCKETS);
  }

  private AgingBuckets toAgingBuckets(AgingSummaryResponse summary) {
    if (summary == null || summary.buckets() == null) {
      return new AgingBuckets();
    }

    BigDecimal current = BigDecimal.ZERO;
    BigDecimal days1to30 = BigDecimal.ZERO;
    BigDecimal days31to60 = BigDecimal.ZERO;
    BigDecimal days61to90 = BigDecimal.ZERO;
    BigDecimal over90 = BigDecimal.ZERO;

    for (AgingBucketDto bucket : summary.buckets()) {
      if (bucket == null || bucket.amount() == null) {
        continue;
      }
      if (bucket.fromDays() == 0 && Integer.valueOf(0).equals(bucket.toDays())) {
        current = current.add(bucket.amount());
      } else if (bucket.fromDays() == 1 && Integer.valueOf(30).equals(bucket.toDays())) {
        days1to30 = days1to30.add(bucket.amount());
      } else if (bucket.fromDays() == 31 && Integer.valueOf(60).equals(bucket.toDays())) {
        days31to60 = days31to60.add(bucket.amount());
      } else if (bucket.fromDays() == 61 && Integer.valueOf(90).equals(bucket.toDays())) {
        days61to90 = days61to90.add(bucket.amount());
      } else if (bucket.fromDays() == 91 && bucket.toDays() == null) {
        over90 = over90.add(bucket.amount());
      }
    }
    return new AgingBuckets(current, days1to30, days31to60, days61to90, over90);
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

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
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
