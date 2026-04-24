package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class AccountingPeriodServiceTest {

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
  @Mock private ClosedPeriodPostingExceptionService closedPeriodPostingExceptionService;
  @Mock private AccountingComplianceAuditService accountingComplianceAuditService;

  private AccountingPeriodService service;

  private static final String SYSTEM_PROCESS_ACTOR = "SYSTEM_PROCESS";

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                SYSTEM_PROCESS_ACTOR, "N/A", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM"))));
    service =
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
    ReflectionFieldAccess.setField(
        service, "closedPeriodPostingExceptionService", closedPeriodPostingExceptionService);
  }

  @Test
  void lockPeriod_whenAlreadyLocked_isIdempotentWithoutSave() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.LOCKED);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 10L))
        .thenReturn(Optional.of(period));

    assertThat(service.lockPeriod(10L, null).status()).isEqualTo("LOCKED");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
  }

  @Test
  void lockPeriod_whenAlreadyClosed_isIdempotentWithoutSave() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 11L))
        .thenReturn(Optional.of(period));

    assertThat(service.lockPeriod(11L, null).status()).isEqualTo("CLOSED");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
  }

  @Test
  void lockPeriod_requiresLockActionWhenRequestMissing() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 12L))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(() -> service.lockPeriod(12L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Period status action must be LOCK");
  }

  @Test
  void lockPeriod_requiresReasonWhenLockActionProvided() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 12L))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(
            () ->
                service.lockPeriod(
                    12L,
                    new PeriodStatusChangeRequest(
                        PeriodStatusChangeRequest.PeriodStatusAction.LOCK, null, "   ")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Lock reason is required");
  }

  @Test
  void lockPeriod_trimsReasonBeforePersisting() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 13L))
        .thenReturn(Optional.of(period));
    when(accountingPeriodRepository.save(period)).thenReturn(period);

    assertThat(
            service.lockPeriod(13L, new PeriodStatusChangeRequest("  lock for audit  ")).status())
        .isEqualTo("LOCKED");
    assertThat(period.getLockReason()).isEqualTo("lock for audit");
    assertThat(period.getLockedBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
  }

  @Test
  void reopenPeriod_requiresSuperAdminRole() {
    authenticate("accounting.user", "ROLE_ACCOUNTING");

    assertThatThrownBy(() -> service.reopenPeriod(20L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("SUPER_ADMIN authority required");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    verify(snapshotService, never()).deleteSnapshotForPeriod(any(), any());
  }

  @Test
  void reopenPeriod_requiresAuthenticatedSuperAdmin() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(() -> service.reopenPeriod(20L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("SUPER_ADMIN authority required");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    verify(snapshotService, never()).deleteSnapshotForPeriod(any(), any());
  }

  @Test
  void reopenPeriod_rejectsExplicitlyUnauthenticatedSuperAdminPrincipal() {
    SecurityContextHolder.clearContext();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("super.admin", "N/A"));

    assertThatThrownBy(() -> service.reopenPeriod(20L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("SUPER_ADMIN authority required");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    verify(snapshotService, never()).deleteSnapshotForPeriod(any(), any());
  }

  @Test
  void reopenPeriod_requiresReasonWhenRequestMissing() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 21L))
        .thenReturn(Optional.of(period));
    authenticate("super.admin", "ROLE_SUPER_ADMIN");

    assertThatThrownBy(() -> service.reopenPeriod(21L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Reopen reason is required");
  }

  @Test
  void reopenPeriod_clearsClosingJournalIdAndDeletesSnapshotWhenJournalLookupMisses() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    period.setClosingJournalEntryId(901L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 22L))
        .thenReturn(Optional.of(period));
    when(journalEntryRepository.findByCompanyAndId(company, 901L)).thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    authenticate("super.admin", "ROLE_SUPER_ADMIN");

    assertThat(
            service
                .reopenPeriod(22L, new AccountingPeriodReopenRequest("  reopen correction  "))
                .status())
        .isEqualTo("OPEN");
    assertThat(period.getReopenReason()).isEqualTo("reopen correction");
    assertThat(period.getClosingJournalEntryId()).isNull();
    verify(journalEntryServiceProvider, never()).getObject();
    verify(snapshotService).deleteSnapshotForPeriod(company, period);
  }

  @Test
  void reopenPeriod_skipsReversalWhenClosingJournalAlreadyReversed() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    period.setClosingJournalEntryId(902L);
    JournalEntry closing = new JournalEntry();
    closing.setStatus("REVERSED");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 23L))
        .thenReturn(Optional.of(period));
    when(journalEntryRepository.findByCompanyAndId(company, 902L)).thenReturn(Optional.of(closing));
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    authenticate("super.admin", "ROLE_SUPER_ADMIN");

    assertThat(
            service.reopenPeriod(23L, new AccountingPeriodReopenRequest("monthly reopen")).status())
        .isEqualTo("OPEN");
    verify(journalEntryServiceProvider, never()).getObject();
    verify(snapshotService).deleteSnapshotForPeriod(company, period);
  }

  @Test
  void closePeriod_requiresApprovedMakerCheckerRequest() {
    assertThatThrownBy(() -> service.closePeriod(30L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("submit /request-close and approve");
  }

  @Test
  void approvePeriodClose_requiresAdminRole() {
    authenticate("accounting.user", "ROLE_ACCOUNTING");

    assertThatThrownBy(
            () ->
                service.approvePeriodClose(31L, new PeriodCloseRequestActionRequest("close", true)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("ROLE_ADMIN authority required");
  }

  @Test
  void approvePeriodClose_rejectsExplicitlyUnauthenticatedAdminPrincipal() {
    SecurityContextHolder.clearContext();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("admin.user", "N/A"));

    assertThatThrownBy(
            () ->
                service.approvePeriodClose(31L, new PeriodCloseRequestActionRequest("close", true)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("ROLE_ADMIN authority required");
  }

  @Test
  void approvePeriodClose_requiresAuthenticatedAdmin() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(
            () ->
                service.approvePeriodClose(31L, new PeriodCloseRequestActionRequest("close", true)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("ROLE_ADMIN authority required");
  }

  @Test
  void approvePeriodClose_closesAndCapturesSnapshotWhenNetIncomeZero() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 31L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 501L, "maker.user");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 31L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
    authenticate("checker.user", "ROLE_ADMIN");

    assertThat(
            service
                .approvePeriodClose(
                    31L, new PeriodCloseRequestActionRequest("  month close  ", true))
                .status())
        .isEqualTo("CLOSED");
    assertThat(period.getChecklistNotes()).isEqualTo("month close");
    assertThat(period.getLockReason()).isEqualTo("month close");
    assertThat(period.getClosingJournalEntryId()).isNull();
    assertThat(pending.getStatus()).isEqualTo(PeriodCloseRequestStatus.APPROVED);
    assertThat(pending.getReviewedBy()).isEqualTo("checker.user");
    verify(periodCloseHook).onPeriodCloseLocked(company, period);
    verify(snapshotService).captureSnapshot(company, period, "checker.user");
    verify(journalEntryRepository, never()).findByCompanyAndReferenceNumber(any(), anyString());
  }

  @Test
  void approvePeriodClose_createsNextPeriodWithOpenedAuditEvent() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 311L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 511L, "maker.user");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 311L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.empty());
    ReflectionFieldAccess.setField(
        service, "accountingComplianceAuditService", accountingComplianceAuditService);
    authenticate("checker.user", "ROLE_ADMIN");

    assertThat(
            service
                .approvePeriodClose(
                    311L, new PeriodCloseRequestActionRequest("close and open next", true))
                .status())
        .isEqualTo("CLOSED");
    verify(accountingComplianceAuditService)
        .recordPeriodTransition(
            eq(company),
            any(AccountingPeriod.class),
            eq("PERIOD_OPENED"),
            eq(null),
            eq("OPEN"),
            contains("Period opened"));
  }

  @Test
  void approvePeriodClose_allowsSuperAdminAuthority() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 310L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 510L, "maker.user");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 310L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
    authenticate("super.admin", "ROLE_SUPER_ADMIN");

    assertThat(
            service
                .approvePeriodClose(
                    310L, new PeriodCloseRequestActionRequest("  super close  ", true))
                .status())
        .isEqualTo("CLOSED");
    assertThat(pending.getReviewedBy()).isEqualTo("super.admin");
    verify(snapshotService).captureSnapshot(company, period, "super.admin");
  }

  @Test
  void approvePeriodClose_setsClosingJournalEntryIdWhenNetIncomeNonZero() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 32L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 502L, "maker.user");
    JournalEntry closingEntry = journalEntryWithId(444L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 32L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {AccountType.REVENUE, BigDecimal.ZERO, new BigDecimal("125.00")}));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PERIOD-CLOSE-202602"))
        .thenReturn(Optional.of(closingEntry));
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
    authenticate("checker.user", "ROLE_ADMIN");

    assertThat(
            service
                .approvePeriodClose(
                    32L, new PeriodCloseRequestActionRequest("close for month", true))
                .status())
        .isEqualTo("CLOSED");
    assertThat(period.getClosingJournalEntryId()).isEqualTo(444L);
    assertThat(pending.getStatus()).isEqualTo(PeriodCloseRequestStatus.APPROVED);
    verify(snapshotService).captureSnapshot(company, period, "checker.user");
  }

  @Test
  void approvePeriodClose_usesPendingForceWhenRequestForceFlagIsMissing() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 321L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 521L, "maker.user");
    pending.setForceRequested(true);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 321L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
    authenticate("checker.user", "ROLE_ADMIN");

    assertThat(
            service
                .approvePeriodClose(
                    321L, new PeriodCloseRequestActionRequest("close with inherited force", null))
                .status())
        .isEqualTo("CLOSED");
    assertThat(pending.isForceRequested()).isTrue();
  }

  @Test
  void approvePeriodClose_uninvoicedReceiptsPreventCloseAndSnapshotCapture() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 33L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 503L, "maker.user");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 33L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(3L);
    authenticate("checker.user", "ROLE_ADMIN");

    assertThatThrownBy(
            () ->
                service.approvePeriodClose(33L, new PeriodCloseRequestActionRequest("close", true)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Un-invoiced goods receipts exist in this period (3)");
    verify(periodCloseHook).onPeriodCloseLocked(company, period);
    verify(snapshotService, never()).captureSnapshot(any(), any(), anyString());
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
  }

  @Test
  void approvePeriodClose_failsWhenTrialBalanceIsNotBalanced() {
    Company company = company(1L, "ACME");
    company.setEnabledModules(new LinkedHashSet<>(List.of(CompanyModule.HR_PAYROLL.name())));
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 34L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 504L, "maker.user");
    period.setBankReconciled(true);
    period.setInventoryCounted(true);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 34L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(gstReconciliation(BigDecimal.ZERO));
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new TrialBalanceDto(
                List.of(), new BigDecimal("150.00"), new BigDecimal("100.00"), false, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID)))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED)))
        .thenReturn(0L);
    authenticate("checker.user", "ROLE_ADMIN");

    assertThatThrownBy(
            () ->
                service.approvePeriodClose(
                    34L, new PeriodCloseRequestActionRequest("month close", false)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Trial balance is not balanced");
  }

  @Test
  void rejectPeriodClose_requiresAdminRole() {
    authenticate("accounting.user", "ROLE_ACCOUNTING");

    assertThatThrownBy(
            () ->
                service.rejectPeriodClose(
                    35L, new PeriodCloseRequestActionRequest("not ready", false)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("ROLE_ADMIN authority required");
  }

  @Test
  void rejectPeriodClose_marksPendingRequestRejectedAndClearsApprovalNote() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 35L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 505L, "maker.user");
    pending.setApprovalNote("stale approval");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 35L))
        .thenReturn(Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    authenticate("checker.user", "ROLE_ADMIN");

    PeriodCloseRequestDto result =
        service.rejectPeriodClose(
            35L, new PeriodCloseRequestActionRequest("  not ready yet  ", false));

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.reviewNote()).isEqualTo("not ready yet");
    assertThat(result.approvalNote()).isNull();
    assertThat(pending.getReviewedBy()).isEqualTo("checker.user");
    assertThat(pending.getStatus()).isEqualTo(PeriodCloseRequestStatus.REJECTED);
  }

  @Test
  void rejectPeriodClose_requiresNoteWhenRequestPayloadIsMissing() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    ReflectionFieldAccess.setField(period, "id", 351L);
    PeriodCloseRequest pending = pendingCloseRequest(company, period, 551L, "maker.user");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 351L))
        .thenReturn(Optional.of(period));
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    authenticate("checker.user", "ROLE_ADMIN");

    assertThatThrownBy(() -> service.rejectPeriodClose(351L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Rejection note is required");
  }

  @Test
  void requirePostablePeriod_rejectsClosedPeriodWithoutOverride() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(
            () ->
                service.requirePostablePeriod(
                    company,
                    LocalDate.of(2026, 2, 12),
                    "JOURNAL_ENTRY",
                    "JE-12",
                    "closed period post",
                    false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("admin one-hour posting exception is required");
  }

  @Test
  void requirePostablePeriod_authorizesClosedPeriodOverrideWhenWorkflowPresent() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.LOCKED);
    ClosedPeriodPostingExceptionService exceptionService =
        org.mockito.Mockito.mock(ClosedPeriodPostingExceptionService.class);
    ReflectionFieldAccess.setField(
        service, "closedPeriodPostingExceptionService", exceptionService);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    AccountingPeriod result =
        service.requirePostablePeriod(
            company, LocalDate.of(2026, 2, 12), "JOURNAL_ENTRY", "JE-14", "override post", true);

    assertThat(result).isSameAs(period);
    verify(exceptionService).authorize(company, period, "JOURNAL_ENTRY", "JE-14", "override post");
  }

  @Test
  void requirePostablePeriod_returnsOpenPeriodWithoutOverrideWorkflow() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);

    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    AccountingPeriod result =
        service.requirePostablePeriod(
            company,
            LocalDate.of(2026, 2, 12),
            "JOURNAL_ENTRY",
            "JE-OPEN-1",
            "standard post",
            false);

    assertThat(result).isSameAs(period);
    verify(closedPeriodPostingExceptionService, never())
        .authorize(any(), any(), anyString(), anyString(), anyString());
  }

  @Test
  void getMonthEndChecklist_includesTrialBalancePassFailItem() {
    Company company = company(1L, "ACME");
    company.setEnabledModules(new LinkedHashSet<>(List.of(CompanyModule.HR_PAYROLL.name())));
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setBankReconciled(true);
    period.setInventoryCounted(true);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 99L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(gstReconciliation(BigDecimal.ZERO));
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new TrialBalanceDto(
                List.of(), new BigDecimal("200.00"), new BigDecimal("120.00"), false, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID)))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED)))
        .thenReturn(0L);

    var checklist = service.getMonthEndChecklist(99L);
    var trialBalanceItem =
        checklist.items().stream()
            .filter(item -> "trialBalanceBalanced".equals(item.key()))
            .findFirst()
            .orElseThrow();
    assertThat(trialBalanceItem.completed()).isFalse();
    assertThat(trialBalanceItem.detail()).contains("differ by");
  }

  @Test
  void getMonthEndChecklist_ignoresPayrollDiagnosticsWhenHrPayrollModulePaused() {
    Company company = company(1L, "ACME");
    company.setEnabledModules(new LinkedHashSet<>(List.of(CompanyModule.PORTAL.name())));
    AccountingPeriod period = openPeriod(company, 2026, 2);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 100L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(gstReconciliation(BigDecimal.ZERO));
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(new TrialBalanceDto(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);

    var checklist = service.getMonthEndChecklist(100L);

    assertThat(checklist.items())
        .filteredOn(
            item ->
                "unlinkedDocuments".equals(item.key()) || "unpostedDocuments".equals(item.key()))
        .allMatch(item -> item.completed() && item.detail().startsWith("All"));
    verify(payrollRunRepository, never())
        .countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(any(), any(), any(), any());
    verify(payrollRunRepository, never())
        .countByCompanyAndPeriodBetweenAndStatusIn(any(), any(), any(), any());
  }

  @Test
  void requirePostablePeriod_rejectsLockedPeriodWithoutOverride() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.LOCKED);

    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(
            () ->
                service.requirePostablePeriod(
                    company,
                    LocalDate.of(2026, 2, 12),
                    "JOURNAL_ENTRY",
                    "JE-LOCKED-1",
                    "late adjustment",
                    false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("admin one-hour posting exception is required");
    verify(closedPeriodPostingExceptionService, never())
        .authorize(any(), any(), anyString(), anyString(), anyString());
  }

  @Test
  void requirePostablePeriod_rejectsMissingPeriodWithoutAutoCreation() {
    Company company = company(1L, "ACME");
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.requirePostablePeriod(
                    company,
                    LocalDate.of(2026, 2, 12),
                    "JOURNAL_ENTRY",
                    "JE-MISSING-PERIOD",
                    "requires explicit period setup",
                    false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not found")
        .hasMessageContaining("create it first");
    verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    verify(closedPeriodPostingExceptionService, never())
        .authorize(any(), any(), anyString(), anyString(), anyString());
  }

  @Test
  void requirePostablePeriod_rejectsOverrideWhenWorkflowNotConfigured() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    ReflectionFieldAccess.setField(service, "closedPeriodPostingExceptionService", null);

    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(
            () ->
                service.requirePostablePeriod(
                    company,
                    LocalDate.of(2026, 2, 12),
                    "JOURNAL_ENTRY",
                    "JE-CLOSED-1",
                    "year-end correction",
                    true))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("workflow is not configured");
  }

  @Test
  void requirePostablePeriod_authorizesOverrideForLockedPeriod() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.of(period));

    assertThat(
            service.requirePostablePeriod(
                company,
                LocalDate.of(2026, 2, 12),
                "JOURNAL_ENTRY",
                "JE-CLOSED-2",
                "approved exception",
                true))
        .isSameAs(period);
    verify(closedPeriodPostingExceptionService)
        .authorize(company, period, "JOURNAL_ENTRY", "JE-CLOSED-2", "approved exception");
  }

  @Test
  void getMonthEndChecklist_countsCorrectionLinkageGapsAsUnlinkedDocuments() {
    Company company = company(1L, "ACME");
    company.setEnabledModules(new LinkedHashSet<>(List.of(CompanyModule.HR_PAYROLL.name())));
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setBankReconciled(true);
    period.setInventoryCounted(true);

    JournalEntry correctionEntry = new JournalEntry();
    correctionEntry.setReferenceNumber("  crn-2001 ");
    correctionEntry.setCorrectionReason(" ");
    correctionEntry.setSourceModule(null);
    correctionEntry.setSourceReference(null);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 199L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(gstReconciliation(BigDecimal.ZERO));
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new TrialBalanceDto(
                List.of(), new BigDecimal("120.00"), new BigDecimal("120.00"), true, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of(correctionEntry));
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID)))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED)))
        .thenReturn(0L);
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);

    var checklist = service.getMonthEndChecklist(199L);
    var unlinkedItem =
        checklist.items().stream()
            .filter(item -> "unlinkedDocuments".equals(item.key()))
            .findFirst()
            .orElseThrow();

    assertThat(unlinkedItem.completed()).isFalse();
    assertThat(unlinkedItem.detail()).contains("1 missing journal links");
  }

  @Test
  void correctionLinkageHelpers_coverEmptyBlankAndCnReferencePaths() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    AccountingPeriodCorrectionJournalClassifier classifier =
        new AccountingPeriodCorrectionJournalClassifier();
    AccountingPeriodChecklistDiagnosticsService diagnosticsService =
        checklistDiagnosticsService(classifier);

    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());

    assertThat(diagnosticsService.countCorrectionLinkageGaps(company, period)).isZero();

    JournalEntry blankReference = new JournalEntry();
    blankReference.setReferenceNumber("   ");
    assertThat(classifier.isCorrectionJournal(blankReference)).isFalse();

    JournalEntry cnReference = new JournalEntry();
    cnReference.setReferenceNumber(" cn-2201 ");
    assertThat(classifier.isCorrectionJournal(cnReference)).isTrue();

    JournalEntry missingReason = new JournalEntry();
    missingReason.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    missingReason.setCorrectionReason(" ");
    missingReason.setSourceModule("SALES_RETURN");
    missingReason.setSourceReference("SR-2201");
    assertThat(classifier.isMissingCorrectionLinkage(missingReason)).isTrue();
  }

  @Test
  void correctionLinkageHelpers_coverNullEntriesReversalFlagsAndCompleteLinkage() {
    AccountingPeriodCorrectionJournalClassifier classifier =
        new AccountingPeriodCorrectionJournalClassifier();

    assertThat(classifier.isCorrectionJournal(null)).isFalse();

    JournalEntry reversalLinked = new JournalEntry();
    reversalLinked.setReversalOf(new JournalEntry());
    assertThat(classifier.isCorrectionJournal(reversalLinked)).isTrue();

    JournalEntry debitNote = new JournalEntry();
    debitNote.setReferenceNumber(" dn-3301 ");
    assertThat(classifier.isCorrectionJournal(debitNote)).isTrue();

    JournalEntry purchaseReturnNote = new JournalEntry();
    purchaseReturnNote.setReferenceNumber(" prn-3302 ");
    assertThat(classifier.isCorrectionJournal(purchaseReturnNote)).isTrue();

    JournalEntry complete = new JournalEntry();
    complete.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    complete.setCorrectionReason("SALES_RETURN");
    complete.setSourceModule("SALES_RETURN");
    complete.setSourceReference("SR-3301");
    assertThat(classifier.isMissingCorrectionLinkage(complete)).isFalse();

    JournalEntry missingSourceModule = new JournalEntry();
    missingSourceModule.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    missingSourceModule.setCorrectionReason("SALES_RETURN");
    missingSourceModule.setSourceModule(" ");
    missingSourceModule.setSourceReference("SR-3302");
    assertThat(classifier.isMissingCorrectionLinkage(missingSourceModule)).isTrue();

    JournalEntry missingSourceReference = new JournalEntry();
    missingSourceReference.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    missingSourceReference.setCorrectionReason("SALES_RETURN");
    missingSourceReference.setSourceModule("SALES_RETURN");
    missingSourceReference.setSourceReference(" ");
    assertThat(classifier.isMissingCorrectionLinkage(missingSourceReference)).isTrue();
  }

  @Test
  void getMonthEndChecklist_ignoresCorrectionEntriesWithCompleteLinkage() {
    Company company = company(1L, "ACME");
    company.setEnabledModules(new LinkedHashSet<>(List.of(CompanyModule.HR_PAYROLL.name())));
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setBankReconciled(true);
    period.setInventoryCounted(true);

    JournalEntry correctionEntry = new JournalEntry();
    correctionEntry.setReferenceNumber("CRN-2002");
    correctionEntry.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    correctionEntry.setCorrectionReason("approved credit correction");
    correctionEntry.setSourceModule("SALES_RETURN");
    correctionEntry.setSourceReference("SR-2002");

    JournalEntry regularEntry = new JournalEntry();
    regularEntry.setReferenceNumber("GEN-2003");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 200L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(gstReconciliation(BigDecimal.ZERO));
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new TrialBalanceDto(
                List.of(), new BigDecimal("120.00"), new BigDecimal("120.00"), true, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of(correctionEntry, regularEntry));
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID)))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED)))
        .thenReturn(0L);
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);

    var checklist = service.getMonthEndChecklist(200L);
    var unlinkedItem =
        checklist.items().stream()
            .filter(item -> "unlinkedDocuments".equals(item.key()))
            .findFirst()
            .orElseThrow();

    assertThat(unlinkedItem.completed()).isTrue();
    assertThat(unlinkedItem.detail()).isEqualTo("All documents linked");
  }

  @Test
  void confirmBankReconciliation_trimsChecklistNoteOnOpenPeriod() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 40L)).thenReturn(period);
    when(accountingPeriodRepository.save(period)).thenReturn(period);

    assertThat(service.confirmBankReconciliation(40L, null, "  bank done  ").bankReconciled())
        .isTrue();
    assertThat(period.getChecklistNotes()).isEqualTo("bank done");
    assertThat(period.getBankReconciledBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
  }

  @Test
  void confirmInventoryCount_trimsChecklistNoteOnOpenPeriod() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 41L)).thenReturn(period);
    when(accountingPeriodRepository.save(period)).thenReturn(period);

    assertThat(service.confirmInventoryCount(41L, null, "  counted  ").inventoryCounted()).isTrue();
    assertThat(period.getChecklistNotes()).isEqualTo("counted");
    assertThat(period.getInventoryCountedBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
  }

  @Test
  void updateMonthEndChecklist_updatesRequestedFlagsAndTrimsNote() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 42L)).thenReturn(period);
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());

    assertThat(
            service
                .updateMonthEndChecklist(
                    42L, new MonthEndChecklistUpdateRequest(true, null, "  month checklist note  "))
                .period()
                .checklistNotes())
        .isEqualTo("month checklist note");
    assertThat(period.isBankReconciled()).isTrue();
    assertThat(period.isInventoryCounted()).isFalse();
  }

  @Test
  void ensurePeriod_defaultsToFifoWhenCreatingMissingPeriod() {
    Company company = company(1L, "ACME");
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AccountingPeriod created = service.ensurePeriod(company, LocalDate.of(2026, 2, 9));

    assertThat(created.getCostingMethod()).isEqualTo(CostingMethod.FIFO);
  }

  @Test
  void ensurePeriod_recordsOpenedAuditWhenAutoCreatingMissingPeriod() {
    Company company = company(1L, "ACME");
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 2))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ReflectionFieldAccess.setField(
        service, "accountingComplianceAuditService", accountingComplianceAuditService);

    AccountingPeriod created = service.ensurePeriod(company, LocalDate.of(2026, 2, 9));

    assertThat(created.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
    verify(accountingComplianceAuditService)
        .recordPeriodTransition(
            eq(company),
            any(AccountingPeriod.class),
            eq("PERIOD_OPENED"),
            eq(null),
            eq("OPEN"),
            contains("Period opened"));
  }

  @Test
  void listPeriods_recordsOpenedAuditForEachAutoCreatedSurroundingPeriod() {
    Company company = company(1L, "ACME");
    LocalDate today = LocalDate.of(2026, 4, 9);
    List<AccountingPeriod> persisted = new ArrayList<>();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyClock.today(company)).thenReturn(today);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 4))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 5))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(
            invocation -> {
              AccountingPeriod period = invocation.getArgument(0);
              persisted.add(period);
              return period;
            });
    when(accountingPeriodRepository.findByCompanyOrderByYearDescMonthDesc(company))
        .thenAnswer(invocation -> persisted);
    ReflectionFieldAccess.setField(
        service, "accountingComplianceAuditService", accountingComplianceAuditService);

    assertThat(service.listPeriods()).hasSize(3);
    verify(accountingComplianceAuditService, org.mockito.Mockito.times(3))
        .recordPeriodTransition(
            eq(company),
            any(AccountingPeriod.class),
            eq("PERIOD_OPENED"),
            eq(null),
            eq("OPEN"),
            contains("Period opened"));
  }

  @Test
  void createOrUpdatePeriod_createsOpenPeriodWithRequestedDatesAndCostingMethod() {
    Company company = company(1L, "ACME");
    LocalDate startDate = LocalDate.of(2026, 4, 3);
    LocalDate endDate = LocalDate.of(2026, 4, 29);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 4))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dto =
        service.createOrUpdatePeriod(
            new AccountingPeriodRequest(2026, 4, startDate, endDate, CostingMethod.LIFO));

    assertThat(dto.year()).isEqualTo(2026);
    assertThat(dto.month()).isEqualTo(4);
    assertThat(dto.startDate()).isEqualTo(startDate);
    assertThat(dto.endDate()).isEqualTo(endDate);
    assertThat(dto.status()).isEqualTo("OPEN");
    assertThat(dto.costingMethod()).isEqualTo("LIFO");
  }

  @Test
  void createOrUpdatePeriod_recordsOpenedAuditWhenCreatingNewPeriod() {
    Company company = company(1L, "ACME");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 7))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ReflectionFieldAccess.setField(
        service, "accountingComplianceAuditService", accountingComplianceAuditService);

    var dto =
        service.createOrUpdatePeriod(
            new AccountingPeriodRequest(
                2026, 7, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), CostingMethod.FIFO));

    assertThat(dto.status()).isEqualTo("OPEN");
    verify(accountingComplianceAuditService)
        .recordPeriodTransition(
            eq(company),
            any(AccountingPeriod.class),
            eq("PERIOD_OPENED"),
            eq(null),
            eq("OPEN"),
            contains("Period opened"));
  }

  @Test
  void createOrUpdatePeriod_rejectsNullRequest() {
    assertThatThrownBy(() -> service.createOrUpdatePeriod(null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Accounting period request is required");
  }

  @Test
  void createOrUpdatePeriod_rejectsInvalidMonth() {
    assertThatThrownBy(
            () ->
                service.createOrUpdatePeriod(
                    new AccountingPeriodRequest(
                        2026,
                        13,
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 31),
                        CostingMethod.FIFO)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("month must be between 1 and 12");
  }

  @Test
  void createOrUpdatePeriod_rejectsMissingStartDate() {
    assertThatThrownBy(
            () ->
                service.createOrUpdatePeriod(
                    new AccountingPeriodRequest(2026, 6, null, LocalDate.of(2026, 6, 30), null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("startDate is required");
  }

  @Test
  void createOrUpdatePeriod_rejectsInvalidDateRange() {
    assertThatThrownBy(
            () ->
                service.createOrUpdatePeriod(
                    new AccountingPeriodRequest(
                        2026, 6, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1), null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("startDate must be on or before endDate");
  }

  @Test
  void createOrUpdatePeriod_rejectsDuplicateCompanyYearMonth() {
    Company company = company(1L, "ACME");
    AccountingPeriod existing = openPeriod(company, 2026, 6);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 6))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.createOrUpdatePeriod(
                    new AccountingPeriodRequest(
                        2026,
                        6,
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 30),
                        CostingMethod.FIFO)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY));
  }

  @Test
  void createOrUpdatePeriod_defaultsToFifoWhenRequestMethodMissing() {
    Company company = company(1L, "ACME");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 6))
        .thenReturn(Optional.empty());
    when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dto =
        service.createOrUpdatePeriod(
            new AccountingPeriodRequest(
                2026, 6, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null));

    assertThat(dto.costingMethod()).isEqualTo("FIFO");
  }

  @Test
  void updatePeriod_updatesDatesAndCostingMethodWhenOpen() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 5);
    period.setCostingMethod(CostingMethod.FIFO);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 77L))
        .thenReturn(Optional.of(period));
    when(accountingPeriodRepository.save(period)).thenReturn(period);

    var dto =
        service.updatePeriod(
            77L,
            new AccountingPeriodRequest(
                2026,
                5,
                LocalDate.of(2026, 5, 2),
                LocalDate.of(2026, 5, 30),
                CostingMethod.WEIGHTED_AVERAGE));

    assertThat(dto.costingMethod()).isEqualTo("WEIGHTED_AVERAGE");
    assertThat(period.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    assertThat(period.getEndDate()).isEqualTo(LocalDate.of(2026, 5, 30));
  }

  @Test
  void updatePeriod_rejectsNullRequest() {
    assertThatThrownBy(() -> service.updatePeriod(77L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Accounting period request is required");
  }

  @Test
  void updatePeriod_rejectsMissingStartDate() {
    assertThatThrownBy(
            () ->
                service.updatePeriod(
                    77L, new AccountingPeriodRequest(null, null, null, LocalDate.now(), null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("startDate is required");
  }

  @Test
  void updatePeriod_rejectsDateUpdatesWhenPeriodIsNotOpen() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 5);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 77L))
        .thenReturn(Optional.of(period));

    assertThatThrownBy(
            () ->
                service.updatePeriod(
                    77L,
                    new AccountingPeriodRequest(
                        2026,
                        5,
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 31),
                        CostingMethod.FIFO)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_STATE));
  }

  @Test
  void updatePeriod_rejectsMissingPeriod() {
    Company company = company(1L, "ACME");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 77L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.updatePeriod(
                    77L,
                    new AccountingPeriodRequest(
                        2026,
                        5,
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 31),
                        CostingMethod.FIFO)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Accounting period not found");
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Pvt");
    company.setTimezone("Asia/Kolkata");
    ReflectionFieldAccess.setField(company, "id", id);
    return company;
  }

  private AccountingPeriod openPeriod(Company company, int year, int month) {
    AccountingPeriod period = new AccountingPeriod();
    period.setCompany(company);
    period.setYear(year);
    period.setMonth(month);
    LocalDate startDate = LocalDate.of(year, month, 1);
    period.setStartDate(startDate);
    period.setEndDate(startDate.plusMonths(1).minusDays(1));
    period.setStatus(AccountingPeriodStatus.OPEN);
    period.setCostingMethod(CostingMethod.WEIGHTED_AVERAGE);
    return period;
  }

  private JournalEntry journalEntryWithId(Long id) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", id);
    return entry;
  }

  private Dealer dealer(Company company, Long id, String code) {
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(code + " Dealer");
    ReflectionFieldAccess.setField(dealer, "id", id);
    return dealer;
  }

  private Supplier supplier(Company company, Long id, String code) {
    Supplier supplier = new Supplier();
    supplier.setCompany(company);
    supplier.setCode(code);
    supplier.setName(code + " Supplier");
    ReflectionFieldAccess.setField(supplier, "id", id);
    return supplier;
  }

  private PeriodCloseRequest pendingCloseRequest(
      Company company, AccountingPeriod period, Long requestId, String requestedBy) {
    PeriodCloseRequest request = new PeriodCloseRequest();
    ReflectionFieldAccess.setField(request, "id", requestId);
    request.setCompany(company);
    request.setAccountingPeriod(period);
    request.setStatus(PeriodCloseRequestStatus.PENDING);
    request.setRequestedBy(requestedBy);
    request.setRequestNote("pending close");
    request.setForceRequested(true);
    request.setRequestedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    return request;
  }

  private void authenticate(String username, String... roles) {
    List<SimpleGrantedAuthority> authorities =
        java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(username, "N/A", authorities));
  }

  @Test
  void confirmBankReconciliation_rejectsChecklistMutationOnLockedPeriod() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    period.setStatus(AccountingPeriodStatus.LOCKED);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 43L)).thenReturn(period);

    assertThatThrownBy(() -> service.confirmBankReconciliation(43L, null, "locked mutation"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("locked or closed period");
  }

  @Test
  void countCorrectionLinkageGaps_ignoresLegacyReturnJournalsWhenDealerOrSupplierLinkExists() {
    Company company = company(1L, "ACME");
    AccountingPeriod period = openPeriod(company, 2026, 3);
    AccountingPeriodChecklistDiagnosticsService diagnosticsService =
        checklistDiagnosticsService(new AccountingPeriodCorrectionJournalClassifier());

    JournalEntry salesReturn = new JournalEntry();
    salesReturn.setCompany(company);
    salesReturn.setReferenceNumber("CRN-INV-REF-ONLY");
    salesReturn.setEntryDate(period.getStartDate().plusDays(1));
    salesReturn.setStatus("POSTED");
    salesReturn.setDealer(dealer(company, 10L, "DLR-10"));

    JournalEntry purchaseReturn = new JournalEntry();
    purchaseReturn.setCompany(company);
    purchaseReturn.setReferenceNumber("PRN-RMP-REF-ONLY");
    purchaseReturn.setEntryDate(period.getStartDate().plusDays(2));
    purchaseReturn.setStatus("POSTED");
    purchaseReturn.setSupplier(supplier(company, 20L, "SUP-20"));

    JournalEntry malformedCorrection = new JournalEntry();
    malformedCorrection.setCompany(company);
    malformedCorrection.setReferenceNumber("DN-2001");
    malformedCorrection.setEntryDate(period.getStartDate().plusDays(3));
    malformedCorrection.setStatus("POSTED");
    malformedCorrection.setCorrectionType(
        com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType.REVERSAL);
    malformedCorrection.setCorrectionReason("PRICE_ADJUSTMENT");
    malformedCorrection.setSourceModule("PURCHASING");

    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of(salesReturn, purchaseReturn, malformedCorrection));

    long gaps = diagnosticsService.countCorrectionLinkageGaps(company, period);

    assertThat(gaps).isEqualTo(1L);
  }

  @Test
  void isMissingCorrectionLinkage_flagsLegacyReturnJournalsWithoutDealerOrSupplierAssociation() {
    AccountingPeriodCorrectionJournalClassifier classifier =
        new AccountingPeriodCorrectionJournalClassifier();

    JournalEntry orphanedSalesReturn = new JournalEntry();
    orphanedSalesReturn.setReferenceNumber("CRN-ORPHAN-1");

    JournalEntry orphanedPurchaseReturn = new JournalEntry();
    orphanedPurchaseReturn.setReferenceNumber("PRN-ORPHAN-1");

    assertThat(classifier.isMissingCorrectionLinkage(orphanedSalesReturn)).isTrue();
    assertThat(classifier.isMissingCorrectionLinkage(orphanedPurchaseReturn)).isTrue();
  }

  private AccountingPeriodChecklistDiagnosticsService checklistDiagnosticsService(
      AccountingPeriodCorrectionJournalClassifier classifier) {
    return new AccountingPeriodChecklistDiagnosticsService(
        journalEntryRepository,
        reportService,
        reconciliationService,
        invoiceRepository,
        goodsReceiptRepository,
        rawMaterialPurchaseRepository,
        payrollRunRepository,
        reconciliationDiscrepancyRepository,
        classifier);
  }

  private com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto gstReconciliation(
      BigDecimal netTotal) {
    com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto dto =
        new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto();
    com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto.GstComponentSummary
        summary =
            new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto
                .GstComponentSummary();
    summary.setTotal(netTotal);
    dto.setNetLiability(summary);
    return dto;
  }
}
