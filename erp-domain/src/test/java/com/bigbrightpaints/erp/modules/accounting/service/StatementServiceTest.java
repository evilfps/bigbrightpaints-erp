package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.OverdueInvoiceDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class StatementServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private DealerLedgerRepository dealerLedgerRepository;
  @Mock private SupplierLedgerRepository supplierLedgerRepository;
  @Mock private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Mock private CompanyClock companyClock;

  private StatementService statementService;
  private Company company;

  @BeforeEach
  void setUp() {
    statementService =
        new StatementService(
            companyContextService,
            dealerRepository,
            supplierRepository,
            dealerLedgerRepository,
            supplierLedgerRepository,
            settlementAllocationRepository,
            companyClock);
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 88L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void dealerStatement_rejectsFromAfterTo() {
    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 5L);
    when(dealerRepository.findByCompanyAndId(company, 5L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 12));

    assertThatThrownBy(
            () ->
                statementService.dealerStatement(
                    5L, LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 10)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("from date cannot be after to date");
    verify(dealerLedgerRepository, never())
        .findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void supplierStatement_rejectsFromAfterTo() {
    Supplier supplier = new Supplier();
    ReflectionFieldAccess.setField(supplier, "id", 9L);
    when(supplierRepository.findByCompanyAndId(company, 9L)).thenReturn(Optional.of(supplier));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 12));

    assertThatThrownBy(
            () ->
                statementService.supplierStatement(
                    9L, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 18)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("from date cannot be after to date");
    verify(supplierLedgerRepository, never())
        .findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAscIdAsc(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dealerAging_rejectsMalformedBuckets() {
    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 12L);
    when(dealerRepository.findByCompanyAndId(company, 12L)).thenReturn(Optional.of(dealer));

    assertThatThrownBy(
            () -> statementService.dealerAging(12L, LocalDate.of(2026, 2, 12), "0-30,abc"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invalid aging bucket format");
  }

  @Test
  void supplierAging_rejectsTrueOverlappingBuckets() {
    Supplier supplier = new Supplier();
    ReflectionFieldAccess.setField(supplier, "id", 13L);
    when(supplierRepository.findByCompanyAndId(company, 13L)).thenReturn(Optional.of(supplier));

    assertThatThrownBy(
            () -> statementService.supplierAging(13L, LocalDate.of(2026, 2, 12), "0-30,29-60,61"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invalid aging bucket format");
  }

  @Test
  void supplierAging_allowsLegacyTouchingBoundaryBuckets() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    ReflectionFieldAccess.setField(supplier, "id", 14L);
    when(supplierRepository.findByCompanyAndId(company, 14L)).thenReturn(Optional.of(supplier));
    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of());

    var response = statementService.supplierAging(14L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

    assertThat(response.totalOutstanding()).isZero();
    assertThat(response.buckets()).hasSize(3);
    assertThat(response.buckets().get(2).toDays()).isNull();
  }

  @Test
  void dealerAging_acceptsStrictlyOrderedOpenEndedFinalBucket() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionFieldAccess.setField(dealer, "id", 21L);
    when(dealerRepository.findByCompanyAndId(company, 21L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of());

    var response = statementService.dealerAging(21L, LocalDate.of(2026, 2, 12), "0-15,16-30,31");

    assertThat(response.totalOutstanding()).isZero();
    assertThat(response.buckets()).hasSize(3);
    assertThat(response.buckets().get(2).toDays()).isNull();
  }

  @Test
  void dealerAging_overloadUsesProvidedDealerWithoutRepositoryReload() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Direct");
    ReflectionFieldAccess.setField(dealer, "id", 211L);
    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of());

    var response = statementService.dealerAging(dealer, LocalDate.of(2026, 2, 12), "0-15,16-30,31");

    assertThat(response.partnerId()).isEqualTo(211L);
    verify(dealerRepository, never()).findByCompanyAndId(company, 211L);
  }

  @Test
  void dealerAgingWithinEntryWindow_appliesOutOfWindowCreditsBeforeAsOfDate() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Window Aging");
    ReflectionFieldAccess.setField(dealer, "id", 212L);

    LocalDate asOf = LocalDate.of(2026, 3, 15);
    LocalDate startDate = LocalDate.of(2026, 3, 1);
    LocalDate endDate = LocalDate.of(2026, 3, 15);

    DealerLedgerEntry preWindowCredit = new DealerLedgerEntry();
    preWindowCredit.setEntryDate(LocalDate.of(2026, 2, 28));
    preWindowCredit.setReferenceNumber("RCPT-WINDOW-001");
    preWindowCredit.setDebit(BigDecimal.ZERO);
    preWindowCredit.setCredit(new BigDecimal("80.00"));

    DealerLedgerEntry inWindowInvoice = new DealerLedgerEntry();
    inWindowInvoice.setEntryDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setDueDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setReferenceNumber("INV-WINDOW-001");
    inWindowInvoice.setInvoiceNumber("INV-WINDOW-001");
    inWindowInvoice.setDebit(new BigDecimal("100.00"));
    inWindowInvoice.setCredit(BigDecimal.ZERO);

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(preWindowCredit, inWindowInvoice));

    var response =
        statementService.dealerAgingWithinEntryWindow(
            dealer, asOf, "0-0,1-30,31-60,61-90,91", startDate, endDate);

    assertThat(response.totalOutstanding()).isEqualByComparingTo("20.00");
    assertThat(response.buckets()).hasSize(5);
    assertThat(response.buckets().get(0).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(1).amount()).isEqualByComparingTo("20.00");
    assertThat(response.buckets().get(2).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(3).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(4).amount()).isEqualByComparingTo("0.00");
  }

  @Test
  void dealerAgingWithinEntryWindow_ignoresMutableOutstandingStateFromPostAsOfPayments() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Historical Window Aging");
    ReflectionFieldAccess.setField(dealer, "id", 213L);

    LocalDate asOf = LocalDate.of(2026, 3, 15);
    LocalDate startDate = LocalDate.of(2026, 3, 1);
    LocalDate endDate = LocalDate.of(2026, 3, 31);

    DealerLedgerEntry inWindowInvoice = new DealerLedgerEntry();
    inWindowInvoice.setEntryDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setDueDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setReferenceNumber("INV-HIST-001");
    inWindowInvoice.setInvoiceNumber("INV-HIST-001");
    inWindowInvoice.setDebit(new BigDecimal("100.00"));
    inWindowInvoice.setCredit(BigDecimal.ZERO);
    inWindowInvoice.setAmountPaid(new BigDecimal("100.00"));
    inWindowInvoice.setPaidDate(LocalDate.of(2026, 3, 20));
    inWindowInvoice.setPaymentStatus("PAID");

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(inWindowInvoice));

    var response =
        statementService.dealerAgingWithinEntryWindow(
            dealer, asOf, "0-0,1-30,31-60,61-90,91", startDate, endDate);

    assertThat(response.totalOutstanding()).isEqualByComparingTo("100.00");
    assertThat(response.buckets()).hasSize(5);
    assertThat(response.buckets().get(0).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(1).amount()).isEqualByComparingTo("100.00");
    assertThat(response.buckets().get(2).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(3).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(4).amount()).isEqualByComparingTo("0.00");
  }

  @Test
  void dealerStatement_usesAggregateOpeningWithoutLoadingAllPriorRows() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Aggregate");
    ReflectionFieldAccess.setField(dealer, "id", 51L);
    when(dealerRepository.findByCompanyAndId(company, 51L)).thenReturn(Optional.of(dealer));

    LocalDate from = LocalDate.of(2026, 2, 1);
    LocalDate to = LocalDate.of(2026, 2, 28);
    when(dealerLedgerRepository.aggregateBalanceBefore(company, dealer, from))
        .thenReturn(Optional.of(new DealerBalanceView(51L, new BigDecimal("1250.50"))));
    when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(
            company, dealer, from, to))
        .thenReturn(List.of());

    var response = statementService.dealerStatement(51L, from, to);

    assertThat(response.openingBalance()).isEqualByComparingTo("1250.50");
    assertThat(response.closingBalance()).isEqualByComparingTo("1250.50");
    verify(dealerLedgerRepository, never())
        .findByCompanyAndDealerAndEntryDateBeforeOrderByEntryDateAsc(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void supplierStatement_usesAggregateOpeningWithoutLoadingAllPriorRows() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Aggregate");
    ReflectionFieldAccess.setField(supplier, "id", 61L);
    when(supplierRepository.findByCompanyAndId(company, 61L)).thenReturn(Optional.of(supplier));

    LocalDate from = LocalDate.of(2026, 2, 1);
    LocalDate to = LocalDate.of(2026, 2, 28);
    when(supplierLedgerRepository.aggregateBalanceBefore(company, supplier, from))
        .thenReturn(Optional.of(new SupplierBalanceView(61L, new BigDecimal("980.25"))));
    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                company, supplier, from, to))
        .thenReturn(List.of());

    var response = statementService.supplierStatement(61L, from, to);

    assertThat(response.openingBalance()).isEqualByComparingTo("980.25");
    assertThat(response.closingBalance()).isEqualByComparingTo("980.25");
    verify(supplierLedgerRepository, never())
        .findByCompanyAndSupplierAndEntryDateBeforeOrderByEntryDateAsc(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dealerAging_prefiltersByAsOfInRepositoryQuery() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Aging");
    ReflectionFieldAccess.setField(dealer, "id", 71L);
    when(dealerRepository.findByCompanyAndId(company, 71L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of());

    var response = statementService.dealerAging(71L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

    assertThat(response.totalOutstanding()).isZero();
    verify(dealerLedgerRepository, never())
        .findByCompanyAndDealerOrderByEntryDateAsc(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dealerAging_ignoresFutureDatedEntriesIfRepositoryLeaksThem() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Future Guard");
    ReflectionFieldAccess.setField(dealer, "id", 72L);
    when(dealerRepository.findByCompanyAndId(company, 72L)).thenReturn(Optional.of(dealer));

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    DealerLedgerEntry inRange = new DealerLedgerEntry();
    inRange.setEntryDate(asOf.minusDays(2));
    inRange.setDebit(new BigDecimal("120.00"));
    inRange.setCredit(BigDecimal.ZERO);
    inRange.setDueDate(asOf.minusDays(2));

    DealerLedgerEntry future = new DealerLedgerEntry();
    future.setEntryDate(asOf.plusDays(3));
    future.setDebit(new BigDecimal("999.00"));
    future.setCredit(BigDecimal.ZERO);
    future.setDueDate(asOf.plusDays(3));

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(inRange, future));

    var response = statementService.dealerAging(72L, asOf, "0-30,30-60,61");

    assertThat(response.totalOutstanding()).isEqualByComparingTo("120.00");
  }

  @Test
  void supplierAging_ignoresFutureDatedEntriesIfRepositoryLeaksThem() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Future Guard");
    ReflectionFieldAccess.setField(supplier, "id", 73L);
    when(supplierRepository.findByCompanyAndId(company, 73L)).thenReturn(Optional.of(supplier));

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    SupplierLedgerEntry inRange = new SupplierLedgerEntry();
    inRange.setEntryDate(asOf.minusDays(1));
    inRange.setDebit(BigDecimal.ZERO);
    inRange.setCredit(new BigDecimal("75.00"));

    SupplierLedgerEntry future = new SupplierLedgerEntry();
    future.setEntryDate(asOf.plusDays(5));
    future.setDebit(BigDecimal.ZERO);
    future.setCredit(new BigDecimal("400.00"));

    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, asOf))
        .thenReturn(List.of(inRange, future));

    var response = statementService.supplierAging(73L, asOf, "0-30,30-60,61");

    assertThat(response.totalOutstanding()).isEqualByComparingTo("75.00");
  }

  @Test
  void dealerAging_overcreditDoesNotCreateNegativeBucketAmount() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Overcredit Clamp");
    ReflectionFieldAccess.setField(dealer, "id", 74L);
    when(dealerRepository.findByCompanyAndId(company, 74L)).thenReturn(Optional.of(dealer));

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    DealerLedgerEntry invoice = new DealerLedgerEntry();
    invoice.setEntryDate(asOf.minusDays(10));
    invoice.setDueDate(asOf.minusDays(10));
    invoice.setDebit(new BigDecimal("100.00"));
    invoice.setCredit(BigDecimal.ZERO);

    DealerLedgerEntry payment = new DealerLedgerEntry();
    payment.setEntryDate(asOf.minusDays(2));
    payment.setDebit(BigDecimal.ZERO);
    payment.setCredit(new BigDecimal("250.00"));

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(invoice, payment));

    var response = statementService.dealerAging(74L, asOf, "0-30,31");

    assertThat(response.totalOutstanding()).isEqualByComparingTo("-150.00");
    assertThat(response.buckets()).hasSize(3);
    assertThat(response.buckets().get(0).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(1).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(2).label()).isEqualTo("Credit Balance");
    assertThat(response.buckets().get(2).amount()).isEqualByComparingTo("-150.00");
    BigDecimal bucketTotal =
        response.buckets().stream()
            .map(AgingBucketDto::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(bucketTotal).isEqualByComparingTo(response.totalOutstanding());
  }

  @Test
  void dealerOverdueInvoices_returnsOnlyPositiveOverdueLedgerInvoices() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Overdue");
    ReflectionFieldAccess.setField(dealer, "id", 76L);

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    DealerLedgerEntry overdue = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(overdue, "id", 1L);
    overdue.setEntryDate(asOf.minusDays(20));
    overdue.setDueDate(asOf.minusDays(5));
    overdue.setInvoiceNumber("INV-001");
    overdue.setDebit(new BigDecimal("500.00"));
    overdue.setCredit(BigDecimal.ZERO);
    overdue.setAmountPaid(new BigDecimal("100.00"));
    overdue.setPaymentStatus("PARTIAL");

    DealerLedgerEntry current = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(current, "id", 2L);
    current.setEntryDate(asOf.minusDays(3));
    current.setDueDate(asOf);
    current.setInvoiceNumber("INV-002");
    current.setDebit(new BigDecimal("300.00"));
    current.setCredit(BigDecimal.ZERO);
    current.setAmountPaid(BigDecimal.ZERO);
    current.setPaymentStatus("UNPAID");

    DealerLedgerEntry paid = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(paid, "id", 3L);
    paid.setEntryDate(asOf.minusDays(30));
    paid.setDueDate(asOf.minusDays(15));
    paid.setInvoiceNumber("INV-003");
    paid.setDebit(new BigDecimal("200.00"));
    paid.setCredit(BigDecimal.ZERO);
    paid.setAmountPaid(new BigDecimal("200.00"));
    paid.setPaymentStatus("PAID");

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(current, overdue, paid));

    List<OverdueInvoiceDto> overdueInvoices = statementService.dealerOverdueInvoices(dealer, asOf);

    assertThat(overdueInvoices)
        .containsExactly(
            new OverdueInvoiceDto(
                "INV-001", asOf.minusDays(20), asOf.minusDays(5), 5L, new BigDecimal("400.00")));
  }

  @Test
  void dealerOverdueInvoices_netsUnappliedCreditsAgainstOldestInvoiceRows() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Credit Netting");
    ReflectionFieldAccess.setField(dealer, "id", 77L);

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    DealerLedgerEntry overdue = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(overdue, "id", 1L);
    overdue.setEntryDate(asOf.minusDays(20));
    overdue.setDueDate(asOf.minusDays(5));
    overdue.setInvoiceNumber("INV-010");
    overdue.setDebit(new BigDecimal("500.00"));
    overdue.setCredit(BigDecimal.ZERO);
    overdue.setAmountPaid(new BigDecimal("100.00"));
    overdue.setPaymentStatus("PARTIAL");

    DealerLedgerEntry current = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(current, "id", 2L);
    current.setEntryDate(asOf.minusDays(3));
    current.setDueDate(asOf.plusDays(7));
    current.setInvoiceNumber("INV-011");
    current.setDebit(new BigDecimal("300.00"));
    current.setCredit(BigDecimal.ZERO);
    current.setAmountPaid(BigDecimal.ZERO);
    current.setPaymentStatus("UNPAID");

    DealerLedgerEntry unappliedCredit = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(unappliedCredit, "id", 3L);
    unappliedCredit.setEntryDate(asOf.minusDays(1));
    unappliedCredit.setDebit(BigDecimal.ZERO);
    unappliedCredit.setCredit(new BigDecimal("250.00"));

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(overdue, current, unappliedCredit));

    List<OverdueInvoiceDto> overdueInvoices = statementService.dealerOverdueInvoices(dealer, asOf);

    assertThat(overdueInvoices)
        .containsExactly(
            new OverdueInvoiceDto(
                "INV-010", asOf.minusDays(20), asOf.minusDays(5), 5L, new BigDecimal("150.00")));
  }

  @Test
  void dealerInvoiceHelpers_doNotDoubleNetAllocatedSettlementCredits() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Settled Invoice");
    ReflectionFieldAccess.setField(dealer, "id", 78L);

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    JournalEntry receiptJournal = new JournalEntry();
    ReflectionFieldAccess.setField(receiptJournal, "id", 901L);

    DealerLedgerEntry overdue = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(overdue, "id", 1L);
    overdue.setEntryDate(asOf.minusDays(20));
    overdue.setDueDate(asOf.minusDays(5));
    overdue.setInvoiceNumber("INV-020");
    overdue.setDebit(new BigDecimal("500.00"));
    overdue.setCredit(BigDecimal.ZERO);
    overdue.setAmountPaid(new BigDecimal("250.00"));
    overdue.setPaymentStatus("PARTIAL");

    DealerLedgerEntry allocatedReceipt = new DealerLedgerEntry();
    ReflectionFieldAccess.setField(allocatedReceipt, "id", 2L);
    allocatedReceipt.setEntryDate(asOf.minusDays(2));
    allocatedReceipt.setDebit(BigDecimal.ZERO);
    allocatedReceipt.setCredit(new BigDecimal("250.00"));
    allocatedReceipt.setJournalEntry(receiptJournal);

    PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
    allocation.setJournalEntry(receiptJournal);
    allocation.setAllocationAmount(new BigDecimal("250.00"));
    allocation.setCompany(company);
    allocation.setInvoice(new Invoice());

    when(dealerLedgerRepository
            .findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf))
        .thenReturn(List.of(overdue, allocatedReceipt));
    when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(company, List.of(901L)))
        .thenReturn(List.of(allocation));

    List<OverdueInvoiceDto> overdueInvoices = statementService.dealerOverdueInvoices(dealer, asOf);
    long openInvoiceCount = statementService.dealerOpenInvoiceCount(dealer, asOf);

    assertThat(overdueInvoices)
        .containsExactly(
            new OverdueInvoiceDto(
                "INV-020", asOf.minusDays(20), asOf.minusDays(5), 5L, new BigDecimal("250.00")));
    assertThat(openInvoiceCount).isEqualTo(1);
  }

  @Test
  void supplierAging_overcreditDoesNotCreateNegativeBucketAmount() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Overcredit Clamp");
    ReflectionFieldAccess.setField(supplier, "id", 75L);
    when(supplierRepository.findByCompanyAndId(company, 75L)).thenReturn(Optional.of(supplier));

    LocalDate asOf = LocalDate.of(2026, 2, 12);
    SupplierLedgerEntry invoice = new SupplierLedgerEntry();
    invoice.setEntryDate(asOf.minusDays(9));
    invoice.setDebit(BigDecimal.ZERO);
    invoice.setCredit(new BigDecimal("80.00"));

    SupplierLedgerEntry payment = new SupplierLedgerEntry();
    payment.setEntryDate(asOf.minusDays(1));
    payment.setDebit(new BigDecimal("200.00"));
    payment.setCredit(BigDecimal.ZERO);

    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, asOf))
        .thenReturn(List.of(invoice, payment));

    var response = statementService.supplierAging(75L, asOf, "0-30,31");

    assertThat(response.totalOutstanding()).isEqualByComparingTo("-120.00");
    assertThat(response.buckets()).hasSize(3);
    assertThat(response.buckets().get(0).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(1).amount()).isEqualByComparingTo("0.00");
    assertThat(response.buckets().get(2).label()).isEqualTo("Credit Balance");
    assertThat(response.buckets().get(2).amount()).isEqualByComparingTo("-120.00");
    BigDecimal bucketTotal =
        response.buckets().stream()
            .map(AgingBucketDto::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(bucketTotal).isEqualByComparingTo(response.totalOutstanding());
  }

  @Test
  void dealerStatementPdf_returnsRealPdfBytes() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer PDF");
    ReflectionFieldAccess.setField(dealer, "id", 31L);
    when(dealerRepository.findByCompanyAndId(company, 31L)).thenReturn(Optional.of(dealer));

    LocalDate from = LocalDate.of(2026, 2, 1);
    LocalDate to = LocalDate.of(2026, 2, 28);
    when(dealerLedgerRepository.aggregateBalanceBefore(company, dealer, from))
        .thenReturn(Optional.empty());
    when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(
            company, dealer, from, to))
        .thenReturn(List.of());

    byte[] pdf = statementService.dealerStatementPdf(31L, from, to);

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
  }

  @Test
  void supplierAgingPdf_returnsRealPdfBytes() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier PDF");
    ReflectionFieldAccess.setField(supplier, "id", 41L);
    when(supplierRepository.findByCompanyAndId(company, 41L)).thenReturn(Optional.of(supplier));
    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of());

    byte[] pdf = statementService.supplierAgingPdf(41L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
  }

  @Test
  void dealerStatementPdf_containsStatementPayloadText() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Ledger Text");
    ReflectionFieldAccess.setField(dealer, "id", 91L);
    when(dealerRepository.findByCompanyAndId(company, 91L)).thenReturn(Optional.of(dealer));

    LocalDate from = LocalDate.of(2026, 2, 1);
    LocalDate to = LocalDate.of(2026, 2, 28);
    when(dealerLedgerRepository.aggregateBalanceBefore(company, dealer, from))
        .thenReturn(Optional.of(new DealerBalanceView(91L, new BigDecimal("25.00"))));

    DealerLedgerEntry row = new DealerLedgerEntry();
    row.setEntryDate(LocalDate.of(2026, 2, 10));
    row.setReferenceNumber("INV-TEXT-1");
    row.setMemo("Dispatch text row");
    row.setDebit(new BigDecimal("75.00"));
    row.setCredit(BigDecimal.ZERO);
    when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(
            company, dealer, from, to))
        .thenReturn(List.of(row));

    var expected = statementService.dealerStatement(91L, from, to);
    byte[] pdf = statementService.dealerStatementPdf(91L, from, to);
    String text = canonicalizeForMatch(extractPdfText(pdf));

    assertThat(text).contains("Dealer Statement");
    assertThat(text).contains(canonicalizeForMatch(expected.partnerName()));
    assertPatternPresent(
        text, "Opening\\s+Balance:\\s+" + numericPattern(expected.openingBalance()));
    assertPatternPresent(
        text, "Closing\\s+Balance:\\s+" + numericPattern(expected.closingBalance()));
    for (var tx : expected.transactions()) {
      assertThat(text).contains(canonicalizeForMatch(tx.referenceNumber()));
      assertPatternPresent(text, transactionRowPattern(tx));
    }
  }

  @Test
  void supplierAgingPdf_containsAgingPayloadText() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Aging Text");
    ReflectionFieldAccess.setField(supplier, "id", 92L);
    when(supplierRepository.findByCompanyAndId(company, 92L)).thenReturn(Optional.of(supplier));

    SupplierLedgerEntry row = new SupplierLedgerEntry();
    row.setEntryDate(LocalDate.of(2026, 2, 1));
    row.setDebit(BigDecimal.ZERO);
    row.setCredit(new BigDecimal("90.00"));
    when(supplierLedgerRepository
            .findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12)))
        .thenReturn(List.of(row));

    var expected = statementService.supplierAging(92L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");
    byte[] pdf = statementService.supplierAgingPdf(92L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");
    String text = canonicalizeForMatch(extractPdfText(pdf));

    assertThat(text).contains("Supplier Aging");
    assertThat(text).contains(canonicalizeForMatch(expected.partnerName()));
    assertPatternPresent(text, "Total\\s+" + numericPattern(expected.totalOutstanding()));
    for (var bucket : expected.buckets()) {
      assertThat(text).contains(canonicalizeForMatch(bucket.label()));
      assertPatternPresent(
          text,
          Pattern.quote(canonicalizeForMatch(bucket.label()))
              + "\\s+"
              + numericPattern(bucket.amount()));
    }
  }

  private String extractPdfText(byte[] pdf) {
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
      return new PDFTextStripper().getText(document);
    } catch (IOException ex) {
      throw new RuntimeException("Unable to extract text from generated PDF", ex);
    }
  }

  private String canonicalizeForMatch(String text) {
    if (text == null) {
      return "";
    }
    return text.replace('\u00A0', ' ')
        .replace(",", "")
        .replaceAll("\\((\\d+(?:\\.\\d+)?)\\)", "-$1")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String numericPattern(BigDecimal value) {
    BigDecimal abs = value.abs().stripTrailingZeros();
    String plain = abs.toPlainString();
    String signedCore = value.signum() < 0 ? "-" + plain : plain;
    if (!signedCore.contains(".")) {
      return Pattern.quote(signedCore) + "(?:\\.0+)?";
    }
    return Pattern.quote(signedCore) + "0*";
  }

  private String transactionRowPattern(
      com.bigbrightpaints.erp.modules.accounting.dto.StatementTransactionDto tx) {
    String reference = Pattern.quote(canonicalizeForMatch(tx.referenceNumber()));
    String memo = canonicalizeForMatch(tx.memo());
    StringBuilder regex = new StringBuilder(reference).append("\\s+");
    if (!memo.isBlank()) {
      regex.append(Pattern.quote(memo)).append("\\s+");
    }
    regex
        .append(numericPattern(tx.debit()))
        .append("\\s+")
        .append(numericPattern(tx.credit()))
        .append("\\s+")
        .append(numericPattern(tx.runningBalance()));
    return regex.toString();
  }

  private void assertPatternPresent(String text, String regex) {
    assertThat(Pattern.compile(regex).matcher(text).find())
        .withFailMessage("Expected pattern %s in extracted PDF text: %s", regex, text)
        .isTrue();
  }
}
