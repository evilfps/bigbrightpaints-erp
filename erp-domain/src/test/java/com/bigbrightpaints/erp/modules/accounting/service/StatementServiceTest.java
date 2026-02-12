package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        verify(dealerLedgerRepository, never()).findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(
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
        verify(supplierLedgerRepository, never()).findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAscIdAsc(
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
        when(supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12))).thenReturn(List.of());

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
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, LocalDate.of(2026, 2, 12))).thenReturn(List.of());

        var response = statementService.dealerAging(21L, LocalDate.of(2026, 2, 12), "0-15,16-30,31");

        assertThat(response.totalOutstanding()).isZero();
        assertThat(response.buckets()).hasSize(3);
        assertThat(response.buckets().get(2).toDays()).isNull();
    }

    @Test
    void dealerStatement_usesAggregateOpeningWithoutLoadingAllPriorRows() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer Aggregate");
        ReflectionTestUtils.setField(dealer, "id", 51L);
        when(dealerRepository.findByCompanyAndId(company, 51L)).thenReturn(Optional.of(dealer));

        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);
        when(dealerLedgerRepository.aggregateBalanceBefore(company, dealer, from))
                .thenReturn(Optional.of(new DealerBalanceView(51L, new BigDecimal("1250.50"))));
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(company, dealer, from, to))
                .thenReturn(List.of());

        var response = statementService.dealerStatement(51L, from, to);

        assertThat(response.openingBalance()).isEqualByComparingTo("1250.50");
        assertThat(response.closingBalance()).isEqualByComparingTo("1250.50");
        verify(dealerLedgerRepository, never()).findByCompanyAndDealerAndEntryDateBeforeOrderByEntryDateAsc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void supplierStatement_usesAggregateOpeningWithoutLoadingAllPriorRows() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier Aggregate");
        ReflectionTestUtils.setField(supplier, "id", 61L);
        when(supplierRepository.findByCompanyAndId(company, 61L)).thenReturn(Optional.of(supplier));

        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);
        when(supplierLedgerRepository.aggregateBalanceBefore(company, supplier, from))
                .thenReturn(Optional.of(new SupplierBalanceView(61L, new BigDecimal("980.25"))));
        when(supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAscIdAsc(company, supplier, from, to))
                .thenReturn(List.of());

        var response = statementService.supplierStatement(61L, from, to);

        assertThat(response.openingBalance()).isEqualByComparingTo("980.25");
        assertThat(response.closingBalance()).isEqualByComparingTo("980.25");
        verify(supplierLedgerRepository, never()).findByCompanyAndSupplierAndEntryDateBeforeOrderByEntryDateAsc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dealerAging_prefiltersByAsOfInRepositoryQuery() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer Aging");
        ReflectionTestUtils.setField(dealer, "id", 71L);
        when(dealerRepository.findByCompanyAndId(company, 71L)).thenReturn(Optional.of(dealer));
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, LocalDate.of(2026, 2, 12))).thenReturn(List.of());

        var response = statementService.dealerAging(71L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

        assertThat(response.totalOutstanding()).isZero();
        verify(dealerLedgerRepository, never()).findByCompanyAndDealerOrderByEntryDateAsc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dealerAging_ignoresFutureDatedEntriesIfRepositoryLeaksThem() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer Future Guard");
        ReflectionTestUtils.setField(dealer, "id", 72L);
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

        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, dealer, asOf)).thenReturn(List.of(inRange, future));

        var response = statementService.dealerAging(72L, asOf, "0-30,30-60,61");

        assertThat(response.totalOutstanding()).isEqualByComparingTo("120.00");
    }

    @Test
    void supplierAging_ignoresFutureDatedEntriesIfRepositoryLeaksThem() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier Future Guard");
        ReflectionTestUtils.setField(supplier, "id", 73L);
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

        when(supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, asOf)).thenReturn(List.of(inRange, future));

        var response = statementService.supplierAging(73L, asOf, "0-30,30-60,61");

        assertThat(response.totalOutstanding()).isEqualByComparingTo("75.00");
    }

    @Test
    void dealerStatementPdf_returnsRealPdfBytes() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer PDF");
        ReflectionTestUtils.setField(dealer, "id", 31L);
        when(dealerRepository.findByCompanyAndId(company, 31L)).thenReturn(Optional.of(dealer));

        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);
        when(dealerLedgerRepository.aggregateBalanceBefore(company, dealer, from)).thenReturn(Optional.empty());
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(company, dealer, from, to))
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
        when(supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12))).thenReturn(List.of());

        byte[] pdf = statementService.supplierAgingPdf(41L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void dealerStatementPdf_containsStatementPayloadText() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer Ledger Text");
        ReflectionTestUtils.setField(dealer, "id", 91L);
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
        when(dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(company, dealer, from, to))
                .thenReturn(List.of(row));

        var expected = statementService.dealerStatement(91L, from, to);
        byte[] pdf = statementService.dealerStatementPdf(91L, from, to);
        String text = extractPdfText(pdf);

        assertThat(text).contains("Dealer Statement");
        assertThat(text).contains(expected.partnerName());
        assertNumericTokenPresent(text, expected.openingBalance());
        assertNumericTokenPresent(text, expected.closingBalance());
        for (int i = 0; i < expected.transactions().size(); i++) {
            var tx = expected.transactions().get(i);
            String nextReference = i + 1 < expected.transactions().size()
                    ? expected.transactions().get(i + 1).referenceNumber()
                    : null;
            String txSection = extractSection(text, tx.referenceNumber(), nextReference);
            assertThat(text).contains(tx.referenceNumber());
            assertThat(txSection).contains(tx.memo());
            assertNumericTokenPresent(txSection, tx.debit());
            assertNumericTokenPresent(txSection, tx.credit());
            assertNumericTokenPresent(txSection, tx.runningBalance());
        }
    }

    @Test
    void supplierAgingPdf_containsAgingPayloadText() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier Aging Text");
        ReflectionTestUtils.setField(supplier, "id", 92L);
        when(supplierRepository.findByCompanyAndId(company, 92L)).thenReturn(Optional.of(supplier));

        SupplierLedgerEntry row = new SupplierLedgerEntry();
        row.setEntryDate(LocalDate.of(2026, 2, 1));
        row.setDebit(BigDecimal.ZERO);
        row.setCredit(new BigDecimal("90.00"));
        when(supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(
                company, supplier, LocalDate.of(2026, 2, 12))).thenReturn(List.of(row));

        var expected = statementService.supplierAging(92L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");
        byte[] pdf = statementService.supplierAgingPdf(92L, LocalDate.of(2026, 2, 12), "0-30,30-60,61");
        String text = extractPdfText(pdf);

        assertThat(text).contains("Supplier Aging");
        assertThat(text).contains(expected.partnerName());
        assertNumericTokenPresent(extractSection(text, "Total", null), expected.totalOutstanding());
        for (int i = 0; i < expected.buckets().size(); i++) {
            var bucket = expected.buckets().get(i);
            String nextLabel = i + 1 < expected.buckets().size()
                    ? expected.buckets().get(i + 1).label()
                    : "Total";
            String bucketSection = extractSection(text, bucket.label(), nextLabel);
            assertThat(text).contains(bucket.label());
            assertNumericTokenPresent(bucketSection, bucket.amount());
        }
    }

    private String extractPdfText(byte[] pdf) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to extract text from generated PDF", ex);
        }
    }

    private List<BigDecimal> extractNumericTokens(String text) {
        List<BigDecimal> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\(?-?\\d+(?:,\\d{3})*(?:\\.\\d+)?\\)?").matcher(text);
        while (matcher.find()) {
            String token = matcher.group().replace(",", "").trim();
            if (token.startsWith("(") && token.endsWith(")")) {
                token = "-" + token.substring(1, token.length() - 1);
            }
            try {
                values.add(new BigDecimal(token));
            } catch (NumberFormatException ignored) {
                // Skip non-numeric artifacts from text extraction.
            }
        }
        return values;
    }

    private void assertNumericTokenPresent(String text, BigDecimal expected) {
        List<BigDecimal> numericTokens = extractNumericTokens(text);
        assertThat(numericTokens.stream().anyMatch(value -> value.compareTo(expected) == 0))
                .withFailMessage("Expected numeric value %s in extracted PDF text", expected)
                .isTrue();
    }

    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertThat(start)
                .withFailMessage("Expected marker '%s' in extracted PDF text", startMarker)
                .isGreaterThanOrEqualTo(0);
        int searchFrom = start + startMarker.length();
        if (endMarker == null) {
            return text.substring(start);
        }
        int end = text.indexOf(endMarker, searchFrom);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end);
    }
}
