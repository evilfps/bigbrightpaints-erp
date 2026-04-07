package com.bigbrightpaints.erp.modules.accounting.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class AccountingEventStoreMetricsTest {

  @Mock private AccountingEventRepository eventRepository;

  @Mock private ApplicationEventPublisher eventPublisher;

  @Mock private CompanyClock companyClock;

  @Test
  void recordJournalEntryPosted_incrementsBusinessJournalMetrics() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AccountingEventStore store =
        new AccountingEventStore(
            eventRepository, eventPublisher, new ObjectMapper(), companyClock, meterRegistry);

    JournalEntry entry = buildJournalEntry();

    when(eventRepository.getNextSequenceNumber(any(UUID.class))).thenReturn(1L, 2L, 1L, 1L);
    when(eventRepository.save(any(AccountingEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.recordJournalEntryPosted(
        entry, Map.of(101L, new BigDecimal("1000.00"), 202L, new BigDecimal("250.00")));

    assertThat(meterRegistry.get("erp.business.journals.created").counter().count())
        .isEqualTo(1.0d);
    assertThat(
            meterRegistry
                .get("erp.business.journals.created.by_company")
                .tag("company", "ACME")
                .counter()
                .count())
        .isEqualTo(1.0d);

    ArgumentCaptor<AccountingEventStore.JournalEntryPostedEvent> eventCaptor =
        ArgumentCaptor.forClass(AccountingEventStore.JournalEntryPostedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().entryId()).isEqualTo(77L);

    ArgumentCaptor<AccountingEvent> accountingEventCaptor =
        ArgumentCaptor.forClass(AccountingEvent.class);
    verify(eventRepository, times(4)).save(accountingEventCaptor.capture());
    assertThat(
            accountingEventCaptor.getAllValues().stream()
                .map(AccountingEvent::getEventType)
                .collect(Collectors.toSet()))
        .contains(
            AccountingEventType.JOURNAL_ENTRY_CREATED,
            AccountingEventType.JOURNAL_ENTRY_POSTED,
            AccountingEventType.ACCOUNT_DEBIT_POSTED,
            AccountingEventType.ACCOUNT_CREDIT_POSTED);
  }

  @Test
  void recordDealerReceiptPosted_persistsDealerReceiptEventType() {
    AccountingEventStore store =
        new AccountingEventStore(
            eventRepository, eventPublisher, new ObjectMapper(), companyClock, null);
    JournalEntry entry = buildJournalEntry();
    when(eventRepository.getNextSequenceNumber(any(UUID.class))).thenReturn(3L);
    when(eventRepository.save(any(AccountingEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.recordDealerReceiptPosted(
        entry, 501L, new BigDecimal("150.00"), "dealer-idempotency-key");

    ArgumentCaptor<AccountingEvent> eventCaptor = ArgumentCaptor.forClass(AccountingEvent.class);
    verify(eventRepository).save(eventCaptor.capture());
    AccountingEvent persisted = eventCaptor.getValue();
    assertThat(persisted.getEventType()).isEqualTo(AccountingEventType.DEALER_RECEIPT_POSTED);
    assertThat(persisted.getJournalEntryId()).isEqualTo(entry.getId());
    assertThat(persisted.getPayload())
        .contains("\"partnerType\":\"DEALER\"")
        .contains("\"partnerId\":501")
        .contains("\"idempotencyKey\":\"dealer-idempotency-key\"");
  }

  @Test
  void recordSupplierPaymentPosted_persistsSupplierPaymentEventType() {
    AccountingEventStore store =
        new AccountingEventStore(
            eventRepository, eventPublisher, new ObjectMapper(), companyClock, null);
    JournalEntry entry = buildJournalEntry();
    when(eventRepository.getNextSequenceNumber(any(UUID.class))).thenReturn(4L);
    when(eventRepository.save(any(AccountingEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.recordSupplierPaymentPosted(
        entry, 902L, new BigDecimal("80.00"), "supplier-idempotency-key");

    ArgumentCaptor<AccountingEvent> eventCaptor = ArgumentCaptor.forClass(AccountingEvent.class);
    verify(eventRepository).save(eventCaptor.capture());
    AccountingEvent persisted = eventCaptor.getValue();
    assertThat(persisted.getEventType()).isEqualTo(AccountingEventType.SUPPLIER_PAYMENT_POSTED);
    assertThat(persisted.getJournalEntryId()).isEqualTo(entry.getId());
    assertThat(persisted.getPayload())
        .contains("\"partnerType\":\"SUPPLIER\"")
        .contains("\"partnerId\":902")
        .contains("\"idempotencyKey\":\"supplier-idempotency-key\"");
  }

  private JournalEntry buildJournalEntry() {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 10L);
    company.setCode("ACME");

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 101L);
    debitAccount.setCompany(company);
    debitAccount.setCode("1000");
    debitAccount.setName("Cash");
    debitAccount.setType(AccountType.ASSET);
    debitAccount.setBalance(new BigDecimal("1000.00"));

    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 202L);
    creditAccount.setCompany(company);
    creditAccount.setCode("2000");
    creditAccount.setName("Revenue");
    creditAccount.setType(AccountType.REVENUE);
    creditAccount.setBalance(new BigDecimal("-250.00"));

    JournalLine debitLine = new JournalLine();
    debitLine.setAccount(debitAccount);
    debitLine.setDebit(new BigDecimal("150.00"));
    debitLine.setCredit(BigDecimal.ZERO);
    debitLine.setDescription("Debit line");

    JournalLine creditLine = new JournalLine();
    creditLine.setAccount(creditAccount);
    creditLine.setDebit(BigDecimal.ZERO);
    creditLine.setCredit(new BigDecimal("150.00"));
    creditLine.setDescription("Credit line");

    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", 77L);
    ReflectionTestUtils.setField(
        entry, "publicId", UUID.fromString("11111111-1111-1111-1111-111111111111"));
    ReflectionTestUtils.setField(entry, "lines", List.of(debitLine, creditLine));
    entry.setCompany(company);
    entry.setReferenceNumber("JRN-77");
    entry.setEntryDate(LocalDate.of(2026, 3, 3));
    entry.setMemo("Metrics test entry");
    entry.setStatus("POSTED");
    debitLine.setJournalEntry(entry);
    creditLine.setJournalEntry(entry);
    return entry;
  }
}
