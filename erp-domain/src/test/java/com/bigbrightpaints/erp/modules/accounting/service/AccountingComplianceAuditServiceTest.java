package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@ExtendWith(MockitoExtension.class)
class AccountingComplianceAuditServiceTest {

  @Mock private EnterpriseAuditTrailService enterpriseAuditTrailService;

  private AccountingComplianceAuditService service;

  @BeforeEach
  void setUp() {
    service = new AccountingComplianceAuditService(enterpriseAuditTrailService, new ObjectMapper());
  }

  @Test
  void recordJournalCreation_manualJournal_flagsSensitiveOperation() {
    Company company = company(77L, "BBP");
    JournalEntry entry = journalEntry(101L, "MANJ-2026-0001", JournalEntryType.MANUAL);

    service.recordJournalCreation(company, entry);

    AuditActionEventCommand command = captureCommand();
    assertThat(command.module()).isEqualTo("ACCOUNTING");
    assertThat(command.action()).isEqualTo("MANUAL_JOURNAL_CREATED");
    assertThat(command.entityType()).isEqualTo("JOURNAL_ENTRY");
    assertThat(command.entityId()).isEqualTo("101");
    assertThat(command.referenceNumber()).isEqualTo("MANJ-2026-0001");
    assertThat(command.metadata()).containsEntry("sensitiveOperation", "true");
    assertThat(command.metadata()).containsEntry("journalSource", "MANUAL");
    assertThat(command.metadata()).containsKey("beforeState");
    assertThat(command.metadata()).containsKey("afterState");
  }

  @Test
  void recordJournalCreation_settlementReference_classifiesAsSettlementAction() {
    Company company = company(78L, "BBP");
    JournalEntry entry = journalEntry(102L, "SET-2026-0003", JournalEntryType.AUTOMATED);

    service.recordJournalCreation(company, entry);

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("SETTLEMENT_JOURNAL_CREATED");
    assertThat(command.metadata()).containsEntry("sensitiveOperation", "false");
    assertThat(command.metadata()).containsEntry("journalSource", "SYSTEM_GENERATED");
  }

  @Test
  void recordJournalCreation_supplierSettlementReference_classifiesAsSettlementAction() {
    Company company = company(83L, "BBP");
    JournalEntry entry = journalEntry(105L, "SUP-SET-2026-0004", JournalEntryType.AUTOMATED);

    service.recordJournalCreation(company, entry);

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("SETTLEMENT_JOURNAL_CREATED");
    assertThat(command.referenceNumber()).isEqualTo("SUP-SET-2026-0004");
    assertThat(command.metadata()).containsEntry("journalSource", "SYSTEM_GENERATED");
  }

  @Test
  void recordJournalCreation_includesSourceReferenceAndAttachmentsWhenPresent() {
    Company company = company(84L, "BBP");
    JournalEntry entry = journalEntry(106L, "JE-ATT-1", JournalEntryType.AUTOMATED);
    entry.setSourceReference("  SRC-ATT-1  ");
    entry.setAttachmentReferences("  att-1,att-2  ");

    service.recordJournalCreation(company, entry);

    AuditActionEventCommand command = captureCommand();
    assertThat(command.metadata()).containsEntry("sourceReference", "SRC-ATT-1");
    assertThat(command.metadata()).containsEntry("attachmentReferences", "att-1,att-2");
  }

  @Test
  void recordJournalReversal_includesBeforeAndAfterState() {
    Company company = company(79L, "BBP");
    JournalEntry original = journalEntry(103L, "INV-2026-0091", JournalEntryType.AUTOMATED);
    original.setStatus("REVERSED");
    JournalEntry reversal = journalEntry(104L, "REV-INV-2026-0091", JournalEntryType.AUTOMATED);

    service.recordJournalReversal(company, original, reversal, "Customer return");

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("JOURNAL_REVERSED");
    assertThat(command.entityId()).isEqualTo("103");
    assertThat(command.metadata().get("beforeState")).contains("POSTED");
    assertThat(command.metadata().get("afterState")).contains("REVERSED");
  }

  @Test
  void recordCostingMethodChange_marksSensitiveOperation() {
    Company company = company(80L, "BBP");
    AccountingPeriod period = period(301L, 2026, 2);

    service.recordCostingMethodChange(
        company, period, CostingMethod.FIFO, CostingMethod.WEIGHTED_AVERAGE);

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("COSTING_METHOD_CHANGED");
    assertThat(command.entityType()).isEqualTo("ACCOUNTING_PERIOD");
    assertThat(command.metadata()).containsEntry("sensitiveOperation", "true");
    assertThat(command.metadata()).containsEntry("sensitiveCategory", "COSTING_METHOD_CHANGE");
  }

  @Test
  void recordPeriodTransition_includesBeforeAndAfterStatus() {
    Company company = company(82L, "BBP");
    AccountingPeriod period = period(302L, 2026, 3);

    service.recordPeriodTransition(
        company, period, "PERIOD_REOPENED", "CLOSED", "OPEN", "Reopen for correction");

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("PERIOD_REOPENED");
    assertThat(command.entityType()).isEqualTo("ACCOUNTING_PERIOD");
    assertThat(command.metadata().get("beforeState")).contains("CLOSED");
    assertThat(command.metadata().get("afterState")).contains("OPEN");
  }

  @Test
  void recordAccountDeactivated_marksSensitiveOperation() {
    Company company = company(81L, "BBP");
    Account account = account(205L, "AR-DEALER", false);

    service.recordAccountDeactivated(company, account, "Dealer deactivated");

    AuditActionEventCommand command = captureCommand();
    assertThat(command.action()).isEqualTo("ACCOUNT_DEACTIVATED");
    assertThat(command.entityType()).isEqualTo("ACCOUNT");
    assertThat(command.entityId()).isEqualTo("205");
    assertThat(command.metadata()).containsEntry("sensitiveOperation", "true");
    assertThat(command.metadata()).containsEntry("sensitiveCategory", "ACCOUNT_DEACTIVATION");
  }

  private AuditActionEventCommand captureCommand() {
    ArgumentCaptor<AuditActionEventCommand> captor =
        ArgumentCaptor.forClass(AuditActionEventCommand.class);
    verify(enterpriseAuditTrailService).recordBusinessEvent(captor.capture());
    return captor.getValue();
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    setField(company, "id", id);
    return company;
  }

  private AccountingPeriod period(Long id, int year, int month) {
    AccountingPeriod period = new AccountingPeriod();
    setField(period, "id", id);
    period.setYear(year);
    period.setMonth(month);
    period.setStartDate(LocalDate.of(year, month, 1));
    period.setEndDate(LocalDate.of(year, month, 1).plusMonths(1).minusDays(1));
    return period;
  }

  private Account account(Long id, String code, boolean active) {
    Account account = new Account();
    setField(account, "id", id);
    account.setCode(code);
    account.setName("Receivable");
    account.setType(AccountType.ASSET);
    account.setActive(active);
    return account;
  }

  private JournalEntry journalEntry(Long id, String reference, JournalEntryType journalType) {
    JournalEntry entry = new JournalEntry();
    setField(entry, "id", id);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(LocalDate.of(2026, 2, 20));
    entry.setStatus("POSTED");
    entry.setJournalType(journalType);
    entry.setCurrency("INR");

    JournalLine debit = new JournalLine();
    debit.setDebit(new BigDecimal("100.00"));
    debit.setCredit(BigDecimal.ZERO);

    JournalLine credit = new JournalLine();
    credit.setDebit(BigDecimal.ZERO);
    credit.setCredit(new BigDecimal("100.00"));

    entry.addLine(debit);
    entry.addLine(credit);
    return entry;
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
