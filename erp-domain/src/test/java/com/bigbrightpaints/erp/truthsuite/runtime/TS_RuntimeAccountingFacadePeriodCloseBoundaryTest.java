package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
    @Tag("reconciliation")
class TS_RuntimeAccountingFacadePeriodCloseBoundaryTest {

    @Test
    void reverseClosingEntryForPeriodReopen_delegatesToAccountingService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingFacade facade = new AccountingFacade(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                accountingService,
                mock(JournalEntryRepository.class),
                mock(ReferenceNumberService.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyAccountingSettingsService.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class)
        );

        JournalEntry entry = new JournalEntry();
        AccountingPeriod period = new AccountingPeriod();
        facade.reverseClosingEntryForPeriodReopen(entry, period, "reopen for correction");

        boolean delegated = mockingDetails(accountingService).getInvocations().stream()
                .anyMatch(invocation ->
                        "reverseClosingEntryForPeriodReopen".equals(invocation.getMethod().getName())
                                && invocation.getArguments().length == 3
                                && invocation.getArguments()[0] == entry
                                && invocation.getArguments()[1] == period
                                && "reopen for correction".equals(invocation.getArguments()[2]));

        assertThat(delegated).isTrue();
    }
}
