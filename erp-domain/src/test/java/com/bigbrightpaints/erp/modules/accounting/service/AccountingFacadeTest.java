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
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
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
