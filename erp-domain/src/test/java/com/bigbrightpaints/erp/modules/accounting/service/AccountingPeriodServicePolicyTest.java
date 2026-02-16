package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingPeriodServicePolicyTest {

    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private CompanyContextService companyContextService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CompanyClock companyClock;
    @Mock private ReportService reportService;
    @Mock private ReconciliationService reconciliationService;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private PeriodCloseHook periodCloseHook;
    @Mock private AccountingPeriodSnapshotService snapshotService;

    private AccountingPeriodService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AccountingFacade> accountingFacadeProvider = mock(ObjectProvider.class);
        service = new AccountingPeriodService(
                accountingPeriodRepository,
                companyContextService,
                journalEntryRepository,
                companyEntityLookup,
                journalLineRepository,
                accountRepository,
                companyClock,
                reportService,
                reconciliationService,
                invoiceRepository,
                goodsReceiptRepository,
                rawMaterialPurchaseRepository,
                payrollRunRepository,
                accountingFacadeProvider,
                periodCloseHook,
                snapshotService
        );
    }

    @Test
    void lockPeriod_requiresReasonForOpenPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.lockPeriod(10L, new AccountingPeriodLockRequest("   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Lock reason is required");
    }

    @Test
    void closePeriod_requiresReasonForOpenPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Close reason is required");
    }

    private Company company(Long id, String code) {
        Company company = new Company();
        company.setCode(code);
        company.setName(code + " Pvt");
        company.setTimezone("Asia/Kolkata");
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    private AccountingPeriod openPeriod(Company company, int year, int month) {
        AccountingPeriod period = new AccountingPeriod();
        period.setCompany(company);
        period.setYear(year);
        period.setMonth(month);
        period.setStatus(AccountingPeriodStatus.OPEN);
        return period;
    }
}
