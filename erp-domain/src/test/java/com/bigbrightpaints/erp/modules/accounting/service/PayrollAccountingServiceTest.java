package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PayrollAccountingServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private PayrollRunLineRepository payrollRunLineRepository;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Mock private EntityManager entityManager;
  @Mock private SystemSettingsService systemSettingsService;
  @Mock private AuditService auditService;
  @Mock private AccountingEventStore accountingEventStore;
  @Mock private JournalEntryService journalEntryService;

  private PayrollAccountingService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new PayrollAccountingService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            org.mockito.Mockito.mock(DealerLedgerService.class),
            org.mockito.Mockito.mock(SupplierLedgerService.class),
            payrollRunRepository,
            payrollRunLineRepository,
            accountingPeriodService,
            referenceNumberService,
            eventPublisher,
            companyClock,
            companyEntityLookup,
            org.mockito.Mockito.mock(PartnerSettlementAllocationRepository.class),
            org.mockito.Mockito.mock(RawMaterialPurchaseRepository.class),
            org.mockito.Mockito.mock(InvoiceRepository.class),
            org.mockito.Mockito.mock(RawMaterialMovementRepository.class),
            org.mockito.Mockito.mock(RawMaterialBatchRepository.class),
            org.mockito.Mockito.mock(FinishedGoodBatchRepository.class),
            org.mockito.Mockito.mock(DealerRepository.class),
            org.mockito.Mockito.mock(SupplierRepository.class),
            org.mockito.Mockito.mock(InvoiceSettlementPolicy.class),
            org.mockito.Mockito.mock(JournalReferenceResolver.class),
            journalReferenceMappingRepository,
            entityManager,
            systemSettingsService,
            auditService,
            accountingEventStore,
            journalEntryService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 44L);
    company.setCode("BBP");
    company.setBaseCurrency("INR");
    company.setTimezone("UTC");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2024, 4, 15));
    lenient()
        .when(payrollRunRepository.save(any(PayrollRun.class)))
        .thenAnswer(
            invocation -> {
              PayrollRun run = invocation.getArgument(0, PayrollRun.class);
              if (run.getId() == null) {
                ReflectionTestUtils.setField(run, "id", 701L);
              }
              return run;
            });
    lenient()
        .when(payrollRunLineRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void postPayrollRun_handlesNullLinesAndDelegatesToStandardJournal() {
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    when(journalEntryService.createStandardJournal(requestCaptor.capture()))
        .thenReturn(journalEntryDto(801L, "PAYROLL-POST-1"));

    JournalEntryDto result = service.postPayrollRun(" RUN-42 ", null, null, null, null);

    assertThat(result.id()).isEqualTo(801L);
    JournalCreationRequest request = requestCaptor.getValue();
    assertThat(request.sourceReference()).isEqualTo("PAYROLL-RUN-42");
    assertThat(request.narration()).isEqualTo("Payroll - RUN-42");
    assertThat(request.entryDate()).isEqualTo(LocalDate.of(2024, 4, 15));
    assertThat(request.amount()).isEqualByComparingTo("0.00");
    assertThat(request.resolvedLines()).hasSize(2);
    assertThat(request.resolvedLines().get(0).debit()).isEqualByComparingTo("0.00");
    assertThat(request.resolvedLines().get(1).credit()).isEqualByComparingTo("0.00");
  }

  @Test
  void processPayrollBatchPayment_createsPayrollAndEmployerContributionJournals() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account expense = account(21L, "PAYROLL-EXP", AccountType.EXPENSE);
    Account taxPayable = account(31L, "TAX-PAYABLE", AccountType.LIABILITY);
    Account pfPayable = account(41L, "PF-PAYABLE", AccountType.LIABILITY);
    Account employerTaxExpense = account(51L, "EMP-TAX-EXP", AccountType.EXPENSE);
    Account employerPfExpense = account(61L, "EMP-PF-EXP", AccountType.EXPENSE);
    stubAccounts(cash, expense, taxPayable, pfPayable, employerTaxExpense, employerPfExpense);
    when(referenceNumberService.payrollPaymentReference(company)).thenReturn("PAY-9001");
    when(journalEntryService.createJournalEntry(any(JournalEntryRequest.class)))
        .thenReturn(journalEntryDto(2001L, "PAY-9001"), journalEntryDto(2002L, "PAY-9001-EMP"));
    when(companyEntityLookup.requireJournalEntry(company, 2001L))
        .thenReturn(journalEntry(2001L, "PAY-9001"));

    PayrollBatchPaymentResponse response =
        service.processPayrollBatchPayment(
            new PayrollBatchPaymentRequest(
                LocalDate.of(2024, 4, 30),
                cash.getId(),
                expense.getId(),
                taxPayable.getId(),
                pfPayable.getId(),
                employerTaxExpense.getId(),
                employerPfExpense.getId(),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.10"),
                new BigDecimal("0.12"),
                null,
                "   ",
                List.of(
                    new PayrollBatchPaymentRequest.PayrollLine(
                        "Alice",
                        10,
                        new BigDecimal("100.00"),
                        new BigDecimal("20.00"),
                        new BigDecimal("15.00"),
                        null,
                        "Line 1"),
                    new PayrollBatchPaymentRequest.PayrollLine(
                        "Bob",
                        5,
                        new BigDecimal("200.00"),
                        BigDecimal.ZERO,
                        null,
                        new BigDecimal("8.00"),
                        null))));

    assertThat(response.payrollRunId()).isEqualTo(701L);
    assertThat(response.grossAmount()).isEqualByComparingTo("2000.00");
    assertThat(response.totalTaxWithholding()).isEqualByComparingTo("115.00");
    assertThat(response.totalPfWithholding()).isEqualByComparingTo("58.00");
    assertThat(response.totalAdvances()).isEqualByComparingTo("20.00");
    assertThat(response.netPayAmount()).isEqualByComparingTo("1807.00");
    assertThat(response.employerTaxAmount()).isEqualByComparingTo("200.00");
    assertThat(response.employerPfAmount()).isEqualByComparingTo("240.00");
    assertThat(response.totalEmployerCost()).isEqualByComparingTo("2440.00");
    assertThat(response.payrollJournalId()).isEqualTo(2001L);
    assertThat(response.employerContribJournalId()).isEqualTo(2002L);
    assertThat(response.lines()).hasSize(2);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService, org.mockito.Mockito.times(2)).createJournalEntry(requestCaptor.capture());
    List<JournalEntryRequest> requests = requestCaptor.getAllValues();
    JournalEntryRequest payrollRequest = requests.get(0);
    JournalEntryRequest employerRequest = requests.get(1);

    assertThat(payrollRequest.referenceNumber()).isEqualTo("PAY-9001");
    assertThat(payrollRequest.memo()).isEqualTo("Payroll batch for 2024-04-30");
    assertThat(payrollRequest.lines()).hasSize(4);
    assertThat(payrollRequest.lines().get(0).accountId()).isEqualTo(expense.getId());
    assertThat(payrollRequest.lines().get(0).debit()).isEqualByComparingTo("1980.00");
    assertThat(payrollRequest.lines().get(1).accountId()).isEqualTo(cash.getId());
    assertThat(payrollRequest.lines().get(1).credit()).isEqualByComparingTo("1807.00");
    assertThat(payrollRequest.lines().get(2).accountId()).isEqualTo(taxPayable.getId());
    assertThat(payrollRequest.lines().get(2).credit()).isEqualByComparingTo("115.00");
    assertThat(payrollRequest.lines().get(3).accountId()).isEqualTo(pfPayable.getId());
    assertThat(payrollRequest.lines().get(3).credit()).isEqualByComparingTo("58.00");

    assertThat(employerRequest.referenceNumber()).isEqualTo("PAY-9001-EMP");
    assertThat(employerRequest.memo()).isEqualTo("Employer contributions for Payroll batch for 2024-04-30");
    assertThat(employerRequest.lines()).hasSize(4);
  }

  @Test
  void processPayrollBatchPayment_skipsEmployerContributionJournalWhenNoEmployerAccounts() {
    Account cash = account(12L, "CASH-2", AccountType.ASSET);
    Account expense = account(22L, "PAYROLL-EXP-2", AccountType.EXPENSE);
    stubAccounts(cash, expense);
    when(journalEntryService.createJournalEntry(any(JournalEntryRequest.class)))
        .thenReturn(journalEntryDto(2101L, "PAY-CUSTOM"));
    when(companyEntityLookup.requireJournalEntry(company, 2101L))
        .thenReturn(journalEntry(2101L, "PAY-CUSTOM"));

    PayrollBatchPaymentResponse response =
        service.processPayrollBatchPayment(
            new PayrollBatchPaymentRequest(
                LocalDate.of(2024, 5, 1),
                cash.getId(),
                expense.getId(),
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "PAY-CUSTOM",
                "Weekly payout",
                List.of(
                    new PayrollBatchPaymentRequest.PayrollLine(
                        "Cara",
                        6,
                        new BigDecimal("150.00"),
                        BigDecimal.ZERO,
                        null,
                        null,
                        "Weekly"))));

    assertThat(response.employerContribJournalId()).isNull();
    verify(referenceNumberService, never()).payrollPaymentReference(company);
    verify(journalEntryService).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void processPayrollBatchPayment_rejectsEmptyLines() {
    assertThatThrownBy(
            () ->
                service.processPayrollBatchPayment(
                    new PayrollBatchPaymentRequest(
                        LocalDate.of(2024, 4, 30),
                        11L,
                        21L,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        null,
                        List.of())))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("At least one payroll line is required");
  }

  @Test
  void processPayrollBatchPayment_rejectsZeroDays() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account expense = account(21L, "PAYROLL-EXP", AccountType.EXPENSE);
    stubAccounts(cash, expense);

    assertThatThrownBy(
            () ->
                service.processPayrollBatchPayment(
                    new PayrollBatchPaymentRequest(
                        LocalDate.of(2024, 4, 30),
                        cash.getId(),
                        expense.getId(),
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        null,
                        List.of(
                            new PayrollBatchPaymentRequest.PayrollLine(
                                "Alice",
                                0,
                                new BigDecimal("100.00"),
                                BigDecimal.ZERO,
                                null,
                                null,
                                null)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Days must be greater than zero");
  }

  @Test
  void processPayrollBatchPayment_rejectsZeroDailyWage() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account expense = account(21L, "PAYROLL-EXP", AccountType.EXPENSE);
    stubAccounts(cash, expense);

    assertThatThrownBy(
            () ->
                service.processPayrollBatchPayment(
                    new PayrollBatchPaymentRequest(
                        LocalDate.of(2024, 4, 30),
                        cash.getId(),
                        expense.getId(),
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        null,
                        List.of(
                            new PayrollBatchPaymentRequest.PayrollLine(
                                "Alice",
                                10,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null,
                                null,
                                null)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Daily wage must be greater than zero");
  }

  @Test
  void processPayrollBatchPayment_rejectsNegativeAdvances() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account expense = account(21L, "PAYROLL-EXP", AccountType.EXPENSE);
    stubAccounts(cash, expense);

    assertThatThrownBy(
            () ->
                service.processPayrollBatchPayment(
                    new PayrollBatchPaymentRequest(
                        LocalDate.of(2024, 4, 30),
                        cash.getId(),
                        expense.getId(),
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        null,
                        List.of(
                            new PayrollBatchPaymentRequest.PayrollLine(
                                "Alice",
                                10,
                                new BigDecimal("100.00"),
                                new BigDecimal("-1.00"),
                                null,
                                null,
                                null)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Advances cannot be negative");
  }

  @Test
  void processPayrollBatchPayment_rejectsWhenDeductionsExceedGrossPay() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account expense = account(21L, "PAYROLL-EXP", AccountType.EXPENSE);
    stubAccounts(cash, expense);

    assertThatThrownBy(
            () ->
                service.processPayrollBatchPayment(
                    new PayrollBatchPaymentRequest(
                        LocalDate.of(2024, 4, 30),
                        cash.getId(),
                        expense.getId(),
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        null,
                        List.of(
                            new PayrollBatchPaymentRequest.PayrollLine(
                                "Alice",
                                1,
                                new BigDecimal("10.00"),
                                new BigDecimal("5.00"),
                                new BigDecimal("4.00"),
                                new BigDecimal("3.00"),
                                null)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Net pay cannot be negative");
  }

  @Test
  void recordPayrollPayment_rejectsPaidRunWithoutPaymentJournalReference() {
    PayrollRun run = payrollRun(801L, PayrollRun.PayrollStatus.PAID, 9001L, "PR-2024-04-801");
    when(companyEntityLookup.lockPayrollRun(company, 801L)).thenReturn(run);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        801L,
                        11L,
                        21L,
                        new BigDecimal("100.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("payment journal reference is missing");
  }

  @Test
  void recordPayrollPayment_rejectsRunsThatAreNotPostedOrPaid() {
    PayrollRun run = payrollRun(800L, PayrollRun.PayrollStatus.DRAFT, 9000L, "PR-2024-04-800");
    when(companyEntityLookup.lockPayrollRun(company, 800L)).thenReturn(run);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        800L,
                        11L,
                        21L,
                        new BigDecimal("100.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be posted to accounting before recording payment");
  }

  @Test
  void recordPayrollPayment_rejectsWhenPostingJournalReferenceIsMissing() {
    PayrollRun run = payrollRun(802L, PayrollRun.PayrollStatus.POSTED, null, "PR-2024-04-802");
    when(companyEntityLookup.lockPayrollRun(company, 802L)).thenReturn(run);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        802L,
                        11L,
                        21L,
                        new BigDecimal("100.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be posted to accounting before recording payment");
  }

  @Test
  void recordPayrollPayment_handlesNullPostingJournalLinesAsMissingPayableAmount() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(8021L, PayrollRun.PayrollStatus.POSTED, 9100L, "PR-2024-04-8021");
    JournalEntry postingJournal =
        new JournalEntry() {
          @Override
          public List<JournalLine> getLines() {
            return null;
          }
        };
    ReflectionTestUtils.setField(postingJournal, "id", 9100L);
    postingJournal.setCompany(company);
    postingJournal.setReferenceNumber("PAYROLL-POSTED-8021");
    postingJournal.setEntryDate(LocalDate.of(2024, 4, 30));
    when(companyEntityLookup.lockPayrollRun(company, 8021L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 9100L)).thenReturn(postingJournal);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        8021L,
                        cash.getId(),
                        21L,
                        new BigDecimal("100.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("does not contain a payable amount for SALARY-PAYABLE");
  }

  @Test
  void recordPayrollPayment_rejectsWhenPostedJournalHasNoSalaryPayableAmount() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(803L, PayrollRun.PayrollStatus.POSTED, 9101L, "PR-2024-04-803");
    JournalEntry postingJournal = journalEntry(9101L, "PAYROLL-POSTED-803");
    postingJournal.addLine(journalLine(cash, new BigDecimal("100.00"), BigDecimal.ZERO));
    when(companyEntityLookup.lockPayrollRun(company, 803L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 9101L)).thenReturn(postingJournal);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        803L,
                        cash.getId(),
                        21L,
                        new BigDecimal("100.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("does not contain a payable amount for SALARY-PAYABLE");
  }

  @Test
  void recordPayrollPayment_rejectsWhenAmountDoesNotMatchSalaryPayable() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(804L, PayrollRun.PayrollStatus.POSTED, 9102L, "PR-2024-04-804");
    JournalEntry postingJournal = journalEntry(9102L, "PAYROLL-POSTED-804");
    postingJournal.addLine(journalLine(salaryPayable, BigDecimal.ZERO, new BigDecimal("500.00")));
    when(companyEntityLookup.lockPayrollRun(company, 804L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 9102L)).thenReturn(postingJournal);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        804L,
                        cash.getId(),
                        21L,
                        new BigDecimal("499.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("does not match salary payable");
  }

  @Test
  void recordPayrollPayment_returnsExistingPaymentJournalForIdempotentReplay() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(805L, PayrollRun.PayrollStatus.PAID, 9103L, "PR-2024-04-805");
    run.setPaymentJournalEntryId(9203L);
    JournalEntry postingJournal = journalEntry(9103L, "PAYROLL-POSTED-805");
    postingJournal.addLine(journalLine(salaryPayable, BigDecimal.ZERO, new BigDecimal("500.00")));
    JournalEntry existingPaymentJournal = journalEntry(9203L, "PAYROLL-PAY-PR-2024-04-805");
    existingPaymentJournal.addLine(
        journalLine(salaryPayable, new BigDecimal("500.00"), BigDecimal.ZERO));
    existingPaymentJournal.addLine(journalLine(cash, BigDecimal.ZERO, new BigDecimal("500.00")));
    when(companyEntityLookup.lockPayrollRun(company, 805L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 9103L)).thenReturn(postingJournal);
    when(companyEntityLookup.requireJournalEntry(company, 9203L)).thenReturn(existingPaymentJournal);

    JournalEntryDto result =
        service.recordPayrollPayment(
            new PayrollPaymentRequest(805L, cash.getId(), 21L, new BigDecimal("500.00"), null, null));

    assertThat(result.id()).isEqualTo(9203L);
    assertThat(result.referenceNumber()).isEqualTo("PAYROLL-PAY-PR-2024-04-805");
    verify(journalEntryService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void recordPayrollPayment_rejectsIdempotentReplayWithDifferentExistingDetails() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(8051L, PayrollRun.PayrollStatus.PAID, 91031L, "PR-2024-04-8051");
    run.setPaymentJournalEntryId(92031L);
    JournalEntry postingJournal = journalEntry(91031L, "PAYROLL-POSTED-8051");
    postingJournal.addLine(journalLine(salaryPayable, BigDecimal.ZERO, new BigDecimal("500.00")));
    JournalEntry existingPaymentJournal = journalEntry(92031L, "PAYROLL-PAY-PR-2024-04-8051");
    existingPaymentJournal.addLine(
        journalLine(salaryPayable, new BigDecimal("400.00"), BigDecimal.ZERO));
    existingPaymentJournal.addLine(journalLine(cash, BigDecimal.ZERO, new BigDecimal("400.00")));
    when(companyEntityLookup.lockPayrollRun(company, 8051L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 91031L)).thenReturn(postingJournal);
    when(companyEntityLookup.requireJournalEntry(company, 92031L)).thenReturn(existingPaymentJournal);

    assertThatThrownBy(
            () ->
                service.recordPayrollPayment(
                    new PayrollPaymentRequest(
                        8051L,
                        cash.getId(),
                        21L,
                        new BigDecimal("500.00"),
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already recorded with different details");
  }

  @Test
  void recordPayrollPayment_createsPaymentJournalForPostedRun() {
    Account cash = account(11L, "CASH", AccountType.ASSET);
    Account salaryPayable = account(31L, "SALARY-PAYABLE", AccountType.LIABILITY);
    stubAccounts(cash);
    PayrollRun run = payrollRun(806L, PayrollRun.PayrollStatus.POSTED, 9104L, "PR-2024-04-806");
    JournalEntry postingJournal = journalEntry(9104L, "PAYROLL-POSTED-806");
    postingJournal.addLine(journalLine(salaryPayable, BigDecimal.ZERO, new BigDecimal("500.00")));
    JournalEntry paymentJournal = journalEntry(9204L, "PAYROLL-PAY-PR-2024-04-806");
    when(companyEntityLookup.lockPayrollRun(company, 806L)).thenReturn(run);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayable));
    when(companyEntityLookup.requireJournalEntry(company, 9104L)).thenReturn(postingJournal);
    when(journalEntryService.createJournalEntry(any(JournalEntryRequest.class)))
        .thenReturn(journalEntryDto(9204L, "PAYROLL-PAY-PR-2024-04-806"));
    when(companyEntityLookup.requireJournalEntry(company, 9204L)).thenReturn(paymentJournal);

    JournalEntryDto result =
        service.recordPayrollPayment(
            new PayrollPaymentRequest(806L, cash.getId(), 21L, new BigDecimal("500.00"), null, null));

    assertThat(result.id()).isEqualTo(9204L);
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService).createJournalEntry(requestCaptor.capture());
    JournalEntryRequest request = requestCaptor.getValue();
    assertThat(request.referenceNumber()).isEqualTo("PAYROLL-PAY-PR-2024-04-806");
    assertThat(request.entryDate()).isEqualTo(LocalDate.of(2024, 4, 15));
    assertThat(request.lines()).hasSize(2);
    assertThat(request.lines().get(0).accountId()).isEqualTo(salaryPayable.getId());
    assertThat(request.lines().get(0).debit()).isEqualByComparingTo("500.00");
    assertThat(request.lines().get(1).accountId()).isEqualTo(cash.getId());
    assertThat(request.lines().get(1).credit()).isEqualByComparingTo("500.00");
    assertThat(run.getPaymentJournalEntryId()).isEqualTo(9204L);
  }

  private void stubAccounts(Account... accounts) {
    for (Account account : accounts) {
      when(companyEntityLookup.requireAccount(eq(company), eq(account.getId()))).thenReturn(account);
    }
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setType(type);
    account.setActive(true);
    return account;
  }

  private PayrollRun payrollRun(
      Long id, PayrollRun.PayrollStatus status, Long journalEntryId, String runNumber) {
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", id);
    run.setCompany(company);
    run.setStatus(status);
    run.setJournalEntryId(journalEntryId);
    run.setRunNumber(runNumber);
    run.setRunDate(LocalDate.of(2024, 4, 30));
    return run;
  }

  private JournalEntry journalEntry(Long id, String referenceNumber) {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(referenceNumber);
    entry.setEntryDate(LocalDate.of(2024, 4, 30));
    return entry;
  }

  private JournalLine journalLine(Account account, BigDecimal debit, BigDecimal credit) {
    JournalLine line = new JournalLine();
    line.setAccount(account);
    line.setDescription("line");
    line.setDebit(debit);
    line.setCredit(credit);
    return line;
  }

  private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2024, 4, 30),
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
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
