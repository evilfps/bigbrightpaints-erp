package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingFacadeTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountingService accountingService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private ReferenceNumberService referenceNumberService;
    @Mock private DealerRepository dealerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CompanyClock companyClock;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock private JournalReferenceResolver journalReferenceResolver;
    @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;

    private AccountingFacade accountingFacade;
    private Company company;

    @BeforeEach
    void setup() {
        accountingFacade = new AccountingFacade(
                companyContextService,
                accountRepository,
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                dealerRepository,
                supplierRepository,
                companyClock,
                companyEntityLookup,
                companyAccountingSettingsService,
                journalReferenceResolver,
                journalReferenceMappingRepository
        );
        company = new Company();
        company.setBaseCurrency("INR");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2024, 4, 9));
    }

    @Test
    void postSalesReturn_usesHashReferenceWhenBaseExists() {
        Dealer dealer = new Dealer();
        Account receivable = new Account();
        receivable.setCode("AR");
        dealer.setReceivableAccount(receivable);
        Long dealerId = 10L;
        ReflectionTestUtils.setField(dealer, "id", dealerId);

        when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

        String invoiceNumber = "INV-100";
        Map<Long, BigDecimal> returnLines = Map.of(101L, new BigDecimal("100.00"));
        BigDecimal total = new BigDecimal("100.00");
        String reason = "Damaged";
        String baseReference = "CRN-" + invoiceNumber;
        String hashReference = buildExpectedHash(baseReference, dealerId, returnLines, total, reason);

        JournalEntry existing = new JournalEntry();
        existing.setReferenceNumber(baseReference);
        when(journalReferenceResolver.findExistingEntry(any(), eq(hashReference))).thenReturn(Optional.empty());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq(baseReference)))
                .thenReturn(Optional.of(existing));
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq(hashReference)))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
                .thenReturn(Optional.empty());

        JournalEntryDto stub = new JournalEntryDto(
                50L,
                null,
                hashReference,
                LocalDate.of(2024, 4, 9),
                null,
                "POSTED",
                dealerId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(stub).when(accountingService).createJournalEntry(requestCaptor.capture());

        accountingFacade.postSalesReturn(
                dealerId,
                invoiceNumber,
                returnLines,
                total,
                reason
        );

        assertThat(requestCaptor.getValue().referenceNumber()).isEqualTo(hashReference);
    }

    @Test
    void postPayrollRun_delegatesToAccountingServiceCanonicalMethod() {
        JournalEntryDto expected = new JournalEntryDto(
                61L,
                null,
                "PAYROLL-PR-W-202602",
                LocalDate.of(2026, 2, 1),
                null,
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        List<JournalEntryRequest.JournalLineRequest> lines = List.of(
                new JournalEntryRequest.JournalLineRequest(10L, "Payroll expense", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(11L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("1000.00"))
        );
        when(accountingService.postPayrollRun("PR-W-202602", 77L, LocalDate.of(2026, 2, 1), "Payroll - PR-W-202602", lines))
                .thenReturn(expected);

        JournalEntryDto actual = accountingFacade.postPayrollRun(
                "PR-W-202602",
                77L,
                LocalDate.of(2026, 2, 1),
                "Payroll - PR-W-202602",
                lines
        );

        assertThat(actual).isSameAs(expected);
        verify(accountingService).postPayrollRun("PR-W-202602", 77L, LocalDate.of(2026, 2, 1), "Payroll - PR-W-202602", lines);
        verify(accountingService, never()).createJournalEntry(any());
    }

    @Test
    void postPurchaseJournal_legacyPrefixFallback_ignoresReversalSuffix() {
        Long supplierId = 88L;
        Supplier supplier = new Supplier();
        Account payable = new Account();
        payable.setCode("AP");
        payable.setName("Accounts Payable");
        ReflectionTestUtils.setField(payable, "id", 301L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.requireSupplier(eq(company), eq(supplierId))).thenReturn(supplier);

        Long inventoryAccountId = 201L;
        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", inventoryAccountId);
        when(companyEntityLookup.requireAccount(eq(company), eq(inventoryAccountId))).thenReturn(inventory);

        String baseReference = "RMP-ACME-SUP-INV100";
        when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-100")))
                .thenReturn(baseReference);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference))).thenReturn(Optional.empty());

        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 501L);
        reversal.setReferenceNumber(baseReference + "-0001-REV");
        reversal.setEntryDate(LocalDate.of(2026, 1, 12));

        JournalEntry canonical = new JournalEntry();
        ReflectionTestUtils.setField(canonical, "id", 500L);
        canonical.setReferenceNumber(baseReference + "-0001");
        canonical.setEntryDate(LocalDate.of(2026, 1, 13));

        JournalEntry laterSequenceButOlderDate = new JournalEntry();
        ReflectionTestUtils.setField(laterSequenceButOlderDate, "id", 499L);
        laterSequenceButOlderDate.setReferenceNumber(baseReference + "-0002");
        laterSequenceButOlderDate.setEntryDate(LocalDate.of(2026, 1, 10));

        when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(eq(company), eq(baseReference + "-")))
                .thenReturn(List.of(reversal, laterSequenceButOlderDate, canonical));
        when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq(baseReference)))
                .thenReturn(Optional.empty());

        JournalEntryDto dto = accountingFacade.postPurchaseJournal(
                supplierId,
                "INV-100",
                LocalDate.of(2026, 1, 10),
                "legacy replay",
                Map.of(inventoryAccountId, new BigDecimal("100.00")),
                null,
                new BigDecimal("100.00"),
                null
        );

        assertThat(dto.referenceNumber()).isEqualTo(baseReference + "-0001");
        verify(accountingService, never()).createJournalEntry(any());
    }

    @Test
    void postSalesJournal_idempotentHitDelegatesToAccountingServiceForDuplicateValidation() {
        Long dealerId = 77L;
        Dealer dealer = new Dealer();
        Account receivable = new Account();
        receivable.setCode("AR");
        receivable.setName("Accounts Receivable");
        ReflectionTestUtils.setField(receivable, "id", 701L);
        dealer.setReceivableAccount(receivable);
        when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

        String orderNumber = "SO-1001";
        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        JournalEntry existing = new JournalEntry();
        ReflectionTestUtils.setField(existing, "id", 777L);
        existing.setReferenceNumber(canonicalReference);
        existing.setEntryDate(LocalDate.of(2026, 1, 5));
        existing.setStatus("POSTED");
        existing.setDealer(dealer);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
                .thenReturn(Optional.of(existing));

        JournalEntryDto replay = new JournalEntryDto(
                777L,
                null,
                canonicalReference,
                LocalDate.of(2026, 1, 5),
                null,
                "POSTED",
                dealerId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        when(accountingService.createJournalEntry(requestCaptor.capture())).thenReturn(replay);
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(777L))).thenReturn(existing);

        JournalEntryDto dto = accountingFacade.postSalesJournal(
                dealerId,
                orderNumber,
                null,
                "sales replay",
                Map.of(9001L, new BigDecimal("120.00")),
                Map.of(9002L, new BigDecimal("21.60")),
                new BigDecimal("141.60"),
                null
        );

        assertThat(dto.id()).isEqualTo(777L);
        assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
        assertThat(requestCaptor.getValue().referenceNumber()).isEqualTo(canonicalReference);
        verify(accountingService).createJournalEntry(any());
    }

    @Test
    void postSalesJournal_idempotentReplayWithoutId_keepsExistingEntryForReferenceMapping() {
        Long dealerId = 88L;
        Dealer dealer = new Dealer();
        Account receivable = new Account();
        receivable.setCode("AR");
        receivable.setName("Accounts Receivable");
        ReflectionTestUtils.setField(receivable, "id", 702L);
        dealer.setReceivableAccount(receivable);
        when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

        String orderNumber = "SO-1002";
        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        JournalEntry existing = new JournalEntry();
        ReflectionTestUtils.setField(existing, "id", 888L);
        existing.setReferenceNumber(canonicalReference);
        existing.setEntryDate(LocalDate.of(2026, 1, 6));
        existing.setStatus("POSTED");
        existing.setDealer(dealer);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
                .thenReturn(Optional.of(existing));

        JournalEntryDto replayWithoutId = new JournalEntryDto(
                null,
                null,
                canonicalReference,
                LocalDate.of(2026, 1, 6),
                null,
                "POSTED",
                dealerId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingService.createJournalEntry(any(JournalEntryRequest.class))).thenReturn(replayWithoutId);

        JournalEntryDto dto = accountingFacade.postSalesJournal(
                dealerId,
                orderNumber,
                null,
                "sales replay without id",
                Map.of(9001L, new BigDecimal("100.00")),
                Map.of(9002L, new BigDecimal("18.00")),
                new BigDecimal("118.00"),
                null
        );

        assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
        verify(accountingService).createJournalEntry(any());
        verify(companyEntityLookup, never()).requireJournalEntry(eq(company), any(Long.class));
    }

    @Test
    void postSalesJournal_idempotentReplayNull_returnsNullAndSkipsEntryLookup() {
        Long dealerId = 99L;
        Dealer dealer = new Dealer();
        Account receivable = new Account();
        receivable.setCode("AR");
        receivable.setName("Accounts Receivable");
        ReflectionTestUtils.setField(receivable, "id", 703L);
        dealer.setReceivableAccount(receivable);
        when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

        String orderNumber = "SO-1003";
        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        JournalEntry existing = new JournalEntry();
        ReflectionTestUtils.setField(existing, "id", 889L);
        existing.setReferenceNumber(canonicalReference);
        existing.setEntryDate(LocalDate.of(2026, 1, 7));
        existing.setStatus("POSTED");
        existing.setDealer(dealer);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
                .thenReturn(Optional.of(existing));
        when(accountingService.createJournalEntry(any(JournalEntryRequest.class))).thenReturn(null);

        JournalEntryDto replay = accountingFacade.postSalesJournal(
                dealerId,
                orderNumber,
                null,
                "sales replay null",
                Map.of(9001L, new BigDecimal("100.00")),
                Map.of(9002L, new BigDecimal("18.00")),
                new BigDecimal("118.00"),
                null
        );

        assertThat(replay).isNull();
        verify(accountingService).createJournalEntry(any());
        verify(companyEntityLookup, never()).requireJournalEntry(eq(company), any(Long.class));
    }

    @Test
    void recordPayrollPayment_delegatesToAccountingService() {
        PayrollPaymentRequest request = new PayrollPaymentRequest(9L, 2L, 1L, new BigDecimal("800.00"), "PAYROLL-PAY-9", "Payroll clear");
        JournalEntryDto expected = new JournalEntryDto(
                88L,
                null,
                "PAYROLL-PAY-9",
                LocalDate.of(2026, 2, 9),
                null,
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingService.recordPayrollPayment(request)).thenReturn(expected);

        JournalEntryDto actual = accountingFacade.recordPayrollPayment(request);

        assertThat(actual).isSameAs(expected);
        verify(accountingService).recordPayrollPayment(request);
    }

    private String buildExpectedHash(String base,
                                     Long dealerId,
                                     Map<Long, BigDecimal> returnLines,
                                     BigDecimal totalAmount,
                                     String reason) {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(base)
                .append("|dealer=").append(dealerId != null ? dealerId : "NA")
                .append("|total=").append(normalizeDecimal(totalAmount))
                .append("|reason=").append(reason != null ? reason.trim() : "");
        returnLines.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fingerprint
                        .append("|acc=").append(entry.getKey())
                        .append(":").append(normalizeDecimal(entry.getValue())));
        String hash = DigestUtils.sha256Hex(fingerprint.toString());
        return base + "-H" + hash.substring(0, 12);
    }

    private String normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
