package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("critical")
class TS_RuntimeAccountingPayrollPostingExecutableCoverageTest {

    @Test
    void postPayrollRun_rejectsMissingRunIdentity() {
        AccountingService service = newAccountingService(mock(CompanyContextService.class), mock(CompanyClock.class));

        assertThatThrownBy(() -> service.postPayrollRun("   ", null, LocalDate.of(2026, 2, 19), "memo", List.of()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Payroll run number or id is required for posting");
    }

    @Test
    void postPayrollRun_usesTrimmedRunNumberAndDefaults() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        Company company = company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 19));

        AccountingService service = spy(newAccountingService(companyContextService, companyClock));
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(null).when(service).createJournalEntry(requestCaptor.capture());

        List<JournalEntryRequest.JournalLineRequest> lines = List.of(
                new JournalEntryRequest.JournalLineRequest(11L, "Payroll expense", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(12L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("1000.00"))
        );

        service.postPayrollRun(" RUN-42 ", null, null, null, lines);

        JournalEntryRequest posted = requestCaptor.getValue();
        assertThat(posted.referenceNumber()).isEqualTo("PAYROLL-RUN-42");
        assertThat(posted.entryDate()).isEqualTo(LocalDate.of(2026, 2, 19));
        assertThat(posted.memo()).isEqualTo("Payroll - RUN-42");
        assertThat(posted.lines()).isEqualTo(lines);
    }

    @Test
    void postPayrollRun_usesLegacyRunTokenAndExplicitValues() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        Company company = company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        AccountingService service = spy(newAccountingService(companyContextService, companyClock));
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(null).when(service).createJournalEntry(requestCaptor.capture());

        LocalDate postingDate = LocalDate.of(2026, 2, 20);
        String memo = "Payroll explicit memo";
        List<JournalEntryRequest.JournalLineRequest> lines = List.of(
                new JournalEntryRequest.JournalLineRequest(11L, "Payroll expense", new BigDecimal("900.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(12L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("900.00"))
        );

        service.postPayrollRun("   ", 77L, postingDate, memo, lines);

        JournalEntryRequest posted = requestCaptor.getValue();
        assertThat(posted.referenceNumber()).isEqualTo("PAYROLL-LEGACY-77");
        assertThat(posted.entryDate()).isEqualTo(postingDate);
        assertThat(posted.memo()).isEqualTo(memo);
        assertThat(posted.lines()).isEqualTo(lines);
    }

    @Test
    void facadePostPayrollRun_delegatesToAccountingServicePostPayrollRun() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingFacade facade = newAccountingFacade(accountingService);

        List<JournalEntryRequest.JournalLineRequest> lines = List.of(
                new JournalEntryRequest.JournalLineRequest(11L, "Payroll expense", new BigDecimal("750.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(12L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("750.00"))
        );

        facade.postPayrollRun("PR-W-202602", 9L, LocalDate.of(2026, 2, 20), "Payroll - PR-W-202602", lines);

        verify(accountingService).postPayrollRun("PR-W-202602", 9L, LocalDate.of(2026, 2, 20), "Payroll - PR-W-202602", lines);
        verify(accountingService, never()).createJournalEntry(any());
    }

    private AccountingService newAccountingService(CompanyContextService companyContextService, CompanyClock companyClock) {
        return new AccountingService(
                companyContextService,
                mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class),
                mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                companyClock,
                mock(com.bigbrightpaints.erp.core.util.CompanyEntityLookup.class),
                mock(com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository.class),
                mock(com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository.class),
                mock(com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository.class),
                mock(com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository.class),
                mock(com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository.class),
                mock(com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository.class),
                mock(com.bigbrightpaints.erp.modules.sales.domain.DealerRepository.class),
                mock(com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository.class),
                mock(com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver.class),
                mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository.class),
                mock(jakarta.persistence.EntityManager.class),
                mock(com.bigbrightpaints.erp.core.config.SystemSettingsService.class),
                mock(com.bigbrightpaints.erp.core.audit.AuditService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class)
        );
    }

    private AccountingFacade newAccountingFacade(AccountingService accountingService) {
        return new AccountingFacade(
                mock(com.bigbrightpaints.erp.modules.company.service.CompanyContextService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class),
                accountingService,
                mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService.class),
                mock(com.bigbrightpaints.erp.modules.sales.domain.DealerRepository.class),
                mock(com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository.class),
                mock(com.bigbrightpaints.erp.core.util.CompanyClock.class),
                mock(com.bigbrightpaints.erp.core.util.CompanyEntityLookup.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver.class),
                mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository.class)
        );
    }

    private Company company() {
        Company company = new Company();
        company.setBaseCurrency("INR");
        return company;
    }
}
