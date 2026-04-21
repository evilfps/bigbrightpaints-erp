package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodSnapshotService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.PeriodCloseHook;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingPeriodPolicyExecutableCoverageTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private CompanyClock companyClock;
  @Mock private ReportService reportService;
  @Mock private ReconciliationService reconciliationService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private GoodsReceiptRepository goodsReceiptRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
  @Mock private PeriodCloseRequestRepository periodCloseRequestRepository;
  @Mock private ObjectProvider<JournalEntryService> journalEntryServiceProvider;
  @Mock private PeriodCloseHook periodCloseHook;
  @Mock private AccountingPeriodSnapshotService snapshotService;

  @Test
  void reopenPeriod_failsClosedWhenReasonIsBlankForClosedPeriod() {
    AccountingPeriodService service =
        new AccountingPeriodService(
            accountingPeriodRepository,
            companyContextService,
            journalEntryRepository,
            accountingLookupService,
            journalLineRepository,
            accountRepository,
            companyClock,
            reportService,
            reconciliationService,
            invoiceRepository,
            goodsReceiptRepository,
            rawMaterialPurchaseRepository,
            payrollRunRepository,
            reconciliationDiscrepancyRepository,
            periodCloseRequestRepository,
            journalEntryServiceProvider,
            periodCloseHook,
            snapshotService);

    Company company = new Company();
    company.setCode("POLICY");
    ReflectionTestUtils.setField(company, "id", 7L);
    AccountingPeriod period = new AccountingPeriod();
    period.setCompany(company);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 12L))
        .thenReturn(Optional.of(period));
    authenticate("super.admin", "ROLE_SUPER_ADMIN");

    assertThatThrownBy(() -> service.reopenPeriod(12L, new AccountingPeriodReopenRequest("   ")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Reopen reason is required");
  }

  private void authenticate(String username, String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
  }
}
