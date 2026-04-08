package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImport;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImportRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class OpeningBalanceImportServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private OpeningBalanceImportRepository openingBalanceImportRepository;
  @Mock private AuditService auditService;
  @Mock private com.bigbrightpaints.erp.core.util.CompanyClock companyClock;

  private OpeningBalanceImportService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new OpeningBalanceImportService(
            companyContextService,
            accountRepository,
            accountingFacade,
            journalEntryRepository,
            openingBalanceImportRepository,
            auditService,
            new ObjectMapper(),
            companyClock,
            new ResourcelessTransactionManager());
    company = new Company();
    company.setCode("ACME");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 1));
  }

  @Test
  void importOpeningBalances_balancedCsvCreatesJournalAndAudit() {
    Account cash = existingAccount(11L, "BANK-001", "Main Bank", AccountType.ASSET);
    Account equity = existingAccount(12L, "CAP-001", "Capital", AccountType.EQUITY);
    stubIdempotencyDefaults();
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "BANK-001"))
        .thenReturn(Optional.of(cash));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "CAP-001"))
        .thenReturn(Optional.of(equity));

    when(openingBalanceImportRepository.saveAndFlush(any(OpeningBalanceImport.class)))
        .thenAnswer(
            invocation -> {
              OpeningBalanceImport importRecord = invocation.getArgument(0);
              ReflectionFieldAccess.setField(importRecord, "id", 101L);
              return importRecord;
            });
    when(openingBalanceImportRepository.save(any(OpeningBalanceImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(
            new JournalEntryDto(
                77L,
                null,
                "OPEN-BAL-ACME-XYZ",
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
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null));

    OpeningBalanceImportResponse response =
        service.importOpeningBalances(
            csvFile(
                "BANK-001,Main Bank,ASSET,100.00,0,Opening cash\n"
                    + "CAP-001,Capital,EQUITY,0,100.00,Owner capital\n"));

    assertThat(response.successCount()).isEqualTo(2);
    assertThat(response.failureCount()).isZero();
    assertThat(response.rowsProcessed()).isEqualTo(2);
    assertThat(response.accountsCreated()).isZero();
    assertThat(response.errors()).isEmpty();

    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    verify(accountingFacade).createStandardJournal(requestCaptor.capture());
    JournalCreationRequest request = requestCaptor.getValue();
    assertThat(request.sourceModule()).isEqualTo("OPENING_BALANCE");
    assertThat(request.sourceReference()).startsWith("OPEN-BAL-ACME-");
    assertThat(request.amount()).isEqualByComparingTo("100.00");
    assertThat(request.lines()).hasSize(2);
    assertThat(request.lines().get(0).accountId()).isEqualTo(11L);
    assertThat(request.lines().get(0).debit()).isEqualByComparingTo("100.00");
    assertThat(request.lines().get(1).accountId()).isEqualTo(12L);
    assertThat(request.lines().get(1).credit()).isEqualByComparingTo("100.00");

    verify(auditService).logSuccess(eq(AuditEvent.DATA_CREATE), any(Map.class));
  }

  @Test
  void importOpeningBalances_unbalancedTotalsRecordedPerRejectedRowWithoutJournalPosting() {
    Account cash = existingAccount(11L, "BANK-001", "Main Bank", AccountType.ASSET);
    Account debtors = existingAccount(12L, "AR-001", "Debtors", AccountType.ASSET);
    stubIdempotencyDefaults();
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "BANK-001"))
        .thenReturn(Optional.of(cash));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-001"))
        .thenReturn(Optional.of(debtors));

    when(openingBalanceImportRepository.saveAndFlush(any(OpeningBalanceImport.class)))
        .thenAnswer(
            invocation -> {
              OpeningBalanceImport importRecord = invocation.getArgument(0);
              ReflectionFieldAccess.setField(importRecord, "id", 102L);
              return importRecord;
            });
    when(openingBalanceImportRepository.save(any(OpeningBalanceImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OpeningBalanceImportResponse response =
        service.importOpeningBalances(
            csvFile(
                "BANK-001,Main Bank,ASSET,200.00,0,Opening cash\n"
                    + "AR-001,Debtors,ASSET,0,100.00,Wrong side\n"));

    assertThat(response.successCount()).isZero();
    assertThat(response.failureCount()).isEqualTo(2);
    assertThat(response.rowsProcessed()).isZero();
    assertThat(response.errors()).hasSize(2);
    assertThat(response.errors())
        .extracting(OpeningBalanceImportResponse.ImportError::rowNumber)
        .containsExactlyInAnyOrder(1L, 2L);
    assertThat(response.errors())
        .extracting(OpeningBalanceImportResponse.ImportError::message)
        .anyMatch(message -> message.contains("Import totals are unbalanced"));
    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void importOpeningBalances_accountResolutionImbalanceRecordsResolvedRowsByNumber() {
    Account cash = existingAccount(11L, "BANK-001", "Main Bank", AccountType.ASSET);
    stubIdempotencyDefaults();
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "BANK-001"))
        .thenReturn(Optional.of(cash));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "NEW-EQ-01"))
        .thenReturn(Optional.empty());
    when(accountRepository.save(any(Account.class)))
        .thenThrow(new RuntimeException("account create failed"));

    when(openingBalanceImportRepository.saveAndFlush(any(OpeningBalanceImport.class)))
        .thenAnswer(
            invocation -> {
              OpeningBalanceImport importRecord = invocation.getArgument(0);
              ReflectionFieldAccess.setField(importRecord, "id", 1021L);
              return importRecord;
            });
    when(openingBalanceImportRepository.save(any(OpeningBalanceImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OpeningBalanceImportResponse response =
        service.importOpeningBalances(
            csvFile(
                "BANK-001,Main Bank,ASSET,200.00,0,Opening cash\n"
                    + "NEW-EQ-01,Opening Capital,EQUITY,0,200.00,Opening capital\n"));

    assertThat(response.successCount()).isZero();
    assertThat(response.failureCount()).isEqualTo(2);
    assertThat(response.rowsProcessed()).isZero();
    assertThat(response.errors()).hasSize(2);
    assertThat(response.errors())
        .extracting(OpeningBalanceImportResponse.ImportError::rowNumber)
        .containsExactlyInAnyOrder(1L, 2L);
    assertThat(response.errors())
        .extracting(OpeningBalanceImportResponse.ImportError::message)
        .anyMatch(message -> message.contains("account create failed"))
        .anyMatch(message -> message.contains("became unbalanced during account resolution"));
    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void importOpeningBalances_rowValidationErrorsDoNotAbortImportAndAccountsAutoCreate() {
    Account bank = existingAccount(11L, "BANK-001", "Main Bank", AccountType.ASSET);
    stubIdempotencyDefaults();
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "BANK-001"))
        .thenReturn(Optional.of(bank));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "NEW-EQ-01"))
        .thenReturn(Optional.empty());

    when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account created = invocation.getArgument(0);
              ReflectionFieldAccess.setField(created, "id", 44L);
              return created;
            });

    when(openingBalanceImportRepository.saveAndFlush(any(OpeningBalanceImport.class)))
        .thenAnswer(
            invocation -> {
              OpeningBalanceImport importRecord = invocation.getArgument(0);
              ReflectionFieldAccess.setField(importRecord, "id", 103L);
              return importRecord;
            });
    when(openingBalanceImportRepository.save(any(OpeningBalanceImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(
            new JournalEntryDto(
                88L,
                null,
                "OPEN-BAL-ACME-ROW",
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
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null));

    OpeningBalanceImportResponse response =
        service.importOpeningBalances(
            csvFile(
                "BANK-001,Main Bank,ASSET,100.00,0,Valid debit\n"
                    + "BROKEN,,ASSET,0,0,Invalid zero row\n"
                    + "NEW-EQ-01,Opening Capital,EQUITY,0,100.00,Auto-create equity\n"));

    assertThat(response.successCount()).isEqualTo(2);
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.rowsProcessed()).isEqualTo(2);
    assertThat(response.accountsCreated()).isEqualTo(1);
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().getFirst().rowNumber()).isEqualTo(2L);
    verify(accountRepository).save(any(Account.class));
    verify(accountingFacade).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void importOpeningBalances_replaysPersistedImportByFileHash() throws Exception {
    String fileHash = hashOf(csvFile("BANK-001,Main Bank,ASSET,100.00,0,Opening\n"));
    OpeningBalanceImport existing = new OpeningBalanceImport();
    existing.setCompany(company);
    existing.setIdempotencyKey(fileHash);
    existing.setIdempotencyHash(fileHash);
    existing.setRowsProcessed(7);
    existing.setAccountsCreated(2);
    existing.setErrorsJson("[{\"rowNumber\":5,\"message\":\"Invalid account\"}]");

    when(openingBalanceImportRepository.findByCompanyAndIdempotencyKey(eq(company), eq(fileHash)))
        .thenReturn(Optional.of(existing));

    OpeningBalanceImportResponse replay =
        service.importOpeningBalances(csvFile("BANK-001,Main Bank,ASSET,100.00,0,Opening\n"));

    assertThat(replay.successCount()).isEqualTo(7);
    assertThat(replay.failureCount()).isEqualTo(1);
    assertThat(replay.rowsProcessed()).isEqualTo(7);
    assertThat(replay.accountsCreated()).isEqualTo(2);
    assertThat(replay.errors()).hasSize(1);
    assertThat(replay.errors().getFirst().message()).contains("Invalid account");
    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void importOpeningBalances_rejectsAccountTypeMismatchForExistingAccount() {
    Account existing = existingAccount(11L, "BANK-001", "Main Bank", AccountType.LIABILITY);
    stubIdempotencyDefaults();
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "BANK-001"))
        .thenReturn(Optional.of(existing));

    when(openingBalanceImportRepository.saveAndFlush(any(OpeningBalanceImport.class)))
        .thenAnswer(
            invocation -> {
              OpeningBalanceImport importRecord = invocation.getArgument(0);
              ReflectionFieldAccess.setField(importRecord, "id", 104L);
              return importRecord;
            });
    when(openingBalanceImportRepository.save(any(OpeningBalanceImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OpeningBalanceImportResponse response =
        service.importOpeningBalances(csvFile("BANK-001,Main Bank,ASSET,100.00,0,Type mismatch\n"));

    assertThat(response.successCount()).isZero();
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.rowsProcessed()).isZero();
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().getFirst().message()).contains("Account mapping mismatch");
    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void importOpeningBalances_rejectsEmptyFile() {
    assertThatThrownBy(
            () ->
                service.importOpeningBalances(
                    new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0])))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("CSV file is required");
  }

  private void stubIdempotencyDefaults() {
    when(openingBalanceImportRepository.findByCompanyAndIdempotencyKey(eq(company), any()))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
  }

  private Account existingAccount(Long id, String code, String name, AccountType type) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    account.setCompany(company);
    return account;
  }

  private MockMultipartFile csvFile(String rows) {
    String csv =
        "account_code,account_name,account_type,debit_amount,credit_amount,narration\n" + rows;
    return new MockMultipartFile(
        "file", "opening-balances.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  private String hashOf(MockMultipartFile file) throws Exception {
    return com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());
  }
}
