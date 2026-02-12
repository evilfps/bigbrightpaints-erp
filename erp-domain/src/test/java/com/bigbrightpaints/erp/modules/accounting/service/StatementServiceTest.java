package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private DealerLedgerRepository dealerLedgerRepository;
    @Mock
    private SupplierLedgerRepository supplierLedgerRepository;
    @Mock
    private CompanyClock companyClock;

    private StatementService statementService;
    private Company company;

    @BeforeEach
    void setUp() {
        statementService = new StatementService(
                companyContextService,
                dealerRepository,
                supplierRepository,
                dealerLedgerRepository,
                supplierLedgerRepository,
                companyClock
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 88L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void dealerStatement_rejectsFromAfterTo() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 5L);
        when(dealerRepository.findByCompanyAndId(company, 5L)).thenReturn(Optional.of(dealer));
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 12));

        assertThatThrownBy(() -> statementService.dealerStatement(
                5L,
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 2, 10)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("from date cannot be after to date");
        verify(dealerLedgerRepository, never()).findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAsc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void supplierStatement_rejectsFromAfterTo() {
        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 9L);
        when(supplierRepository.findByCompanyAndId(company, 9L)).thenReturn(Optional.of(supplier));
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 12));

        assertThatThrownBy(() -> statementService.supplierStatement(
                9L,
                LocalDate.of(2026, 2, 20),
                LocalDate.of(2026, 2, 18)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("from date cannot be after to date");
        verify(supplierLedgerRepository, never()).findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAsc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dealerAging_rejectsMalformedBuckets() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 12L);
        when(dealerRepository.findByCompanyAndId(company, 12L)).thenReturn(Optional.of(dealer));

        assertThatThrownBy(() -> statementService.dealerAging(
                12L,
                LocalDate.of(2026, 2, 12),
                "0-30,abc"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invalid aging bucket format");
    }

    @Test
    void supplierAging_rejectsTrueOverlappingBuckets() {
        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 13L);
        when(supplierRepository.findByCompanyAndId(company, 13L)).thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> statementService.supplierAging(
                13L,
                LocalDate.of(2026, 2, 12),
                "0-30,29-60,61"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invalid aging bucket format");
    }

    @Test
    void supplierAging_allowsLegacyTouchingBoundaryBuckets() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 14L);
        when(supplierRepository.findByCompanyAndId(company, 14L)).thenReturn(Optional.of(supplier));
        when(supplierLedgerRepository.findByCompanyAndSupplierOrderByEntryDateAsc(company, supplier)).thenReturn(List.of());

        var response = statementService.supplierAging(14L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

        assertThat(response.totalOutstanding()).isZero();
        assertThat(response.buckets()).hasSize(3);
        assertThat(response.buckets().get(2).toDays()).isNull();
    }

    @Test
    void dealerAging_acceptsStrictlyOrderedOpenEndedFinalBucket() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        ReflectionTestUtils.setField(dealer, "id", 21L);
        when(dealerRepository.findByCompanyAndId(company, 21L)).thenReturn(Optional.of(dealer));
        when(dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, dealer)).thenReturn(List.of());

        var response = statementService.dealerAging(21L, LocalDate.of(2026, 2, 12), "0-15,16-30,31");

        assertThat(response.totalOutstanding()).isZero();
        assertThat(response.buckets()).hasSize(3);
        assertThat(response.buckets().get(2).toDays()).isNull();
    }

    @Test
    void dealerStatementPdf_returnsRealPdfBytes() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer PDF");
        ReflectionTestUtils.setField(dealer, "id", 31L);
        when(dealerRepository.findByCompanyAndId(company, 31L)).thenReturn(Optional.of(dealer));

        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBeforeOrderByEntryDateAsc(company, dealer, from))
                .thenReturn(List.of());
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAsc(company, dealer, from, to))
                .thenReturn(List.of());

        byte[] pdf = statementService.dealerStatementPdf(31L, from, to);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void supplierAgingPdf_returnsRealPdfBytes() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier PDF");
        ReflectionTestUtils.setField(supplier, "id", 41L);
        when(supplierRepository.findByCompanyAndId(company, 41L)).thenReturn(Optional.of(supplier));
        when(supplierLedgerRepository.findByCompanyAndSupplierOrderByEntryDateAsc(company, supplier)).thenReturn(List.of());

        byte[] pdf = statementService.supplierAgingPdf(41L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
