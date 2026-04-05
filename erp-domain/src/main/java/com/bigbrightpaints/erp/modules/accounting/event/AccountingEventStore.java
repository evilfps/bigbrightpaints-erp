package com.bigbrightpaints.erp.modules.accounting.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Event store for accounting domain events.
 * Append-only audit log; closed-period truth relies on journals + snapshots, not replay.
 */
@Service
public class AccountingEventStore {

  private static final Logger log = LoggerFactory.getLogger(AccountingEventStore.class);
  private static final int MAX_SEQUENCE_RETRIES = 5;

  private final AccountingEventRepository eventRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final CompanyClock companyClock;
  private final MeterRegistry meterRegistry;
  private final Counter journalsCreatedCounter;

  public AccountingEventStore(
      AccountingEventRepository eventRepository,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      CompanyClock companyClock,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this.eventRepository = eventRepository;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.companyClock = companyClock;
    this.meterRegistry = meterRegistry;
    this.journalsCreatedCounter =
        meterRegistry == null
            ? null
            : Counter.builder("erp.business.journals.created")
                .description("Number of accounting journals posted")
                .register(meterRegistry);
  }

  /**
   * Record a journal entry posted event with all line items
   */
  @Transactional
  public List<AccountingEvent> recordJournalEntryPosted(
      JournalEntry entry, Map<Long, BigDecimal> balancesBefore) {
    UUID correlationId = UUID.randomUUID();
    List<AccountingEvent> events = new ArrayList<>();
    String userId = getCurrentUserId();

    // Main journal entry event
    AccountingEvent entryEvent = new AccountingEvent();
    entryEvent.setCompany(entry.getCompany());
    entryEvent.setEventType(AccountingEventType.JOURNAL_ENTRY_POSTED);
    entryEvent.setAggregateId(entry.getPublicId());
    entryEvent.setAggregateType("JournalEntry");
    entryEvent.setSequenceNumber(eventRepository.getNextSequenceNumber(entry.getPublicId()));
    entryEvent.setEffectiveDate(entry.getEntryDate());
    entryEvent.setJournalEntryId(entry.getId());
    entryEvent.setJournalReference(entry.getReferenceNumber());
    entryEvent.setDescription(entry.getMemo());
    entryEvent.setUserId(userId);
    entryEvent.setCorrelationId(correlationId);
    BigDecimal totalDebit =
        entry.getLines().stream()
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredit =
        entry.getLines().stream()
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<String, Object> entryPayload = new HashMap<>();
    entryPayload.put("status", entry.getStatus());
    entryPayload.put("totalDebit", totalDebit);
    entryPayload.put("totalCredit", totalCredit);
    entryEvent.setPayload(serializePayload(entryPayload));
    events.add(saveWithSequenceRetry(entryEvent, entryEvent.getAggregateId()));

    // Individual line events (for balance tracking)
    for (JournalLine line : entry.getLines()) {
      Account account = line.getAccount();
      BigDecimal balanceBefore = balancesBefore.getOrDefault(account.getId(), account.getBalance());
      BigDecimal balanceAfter = calculateBalanceAfter(account, line, balanceBefore);

      AccountingEvent lineEvent = new AccountingEvent();
      lineEvent.setCompany(entry.getCompany());
      lineEvent.setEventType(
          line.getDebit().compareTo(BigDecimal.ZERO) > 0
              ? AccountingEventType.ACCOUNT_DEBIT_POSTED
              : AccountingEventType.ACCOUNT_CREDIT_POSTED);
      lineEvent.setAggregateId(UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes()));
      lineEvent.setAggregateType("Account");
      UUID lineAggregateId = UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes());
      lineEvent.setSequenceNumber(eventRepository.getNextSequenceNumber(lineAggregateId));
      lineEvent.setEffectiveDate(entry.getEntryDate());
      lineEvent.setAccountId(account.getId());
      lineEvent.setAccountCode(account.getCode());
      lineEvent.setJournalEntryId(entry.getId());
      lineEvent.setJournalReference(entry.getReferenceNumber());
      lineEvent.setDebitAmount(line.getDebit());
      lineEvent.setCreditAmount(line.getCredit());
      lineEvent.setBalanceBefore(balanceBefore);
      lineEvent.setBalanceAfter(balanceAfter);
      lineEvent.setDescription(line.getDescription());
      lineEvent.setUserId(userId);
      lineEvent.setCorrelationId(correlationId);
      events.add(saveWithSequenceRetry(lineEvent, lineAggregateId));
    }

    // Publish for any listeners (cache invalidation, etc.)
    eventPublisher.publishEvent(
        new JournalEntryPostedEvent(
            entry.getId(),
            entry.getPublicId(),
            entry.getReferenceNumber(),
            entry.getEntryDate(),
            correlationId));

    incrementJournalsCreatedMetric(entry.getCompany());
    log.debug("Recorded {} events for journal entry", events.size());
    return events;
  }

  /**
   * Record a journal entry reversal
   */
  @Transactional
  public AccountingEvent recordJournalEntryReversed(
      JournalEntry original, JournalEntry reversal, String reason) {
    AccountingEvent event = new AccountingEvent();
    event.setCompany(original.getCompany());
    event.setEventType(AccountingEventType.JOURNAL_ENTRY_REVERSED);
    event.setAggregateId(original.getPublicId());
    event.setAggregateType("JournalEntry");
    event.setSequenceNumber(eventRepository.getNextSequenceNumber(original.getPublicId()));
    event.setEffectiveDate(reversal.getEntryDate());
    event.setJournalEntryId(original.getId());
    event.setJournalReference(original.getReferenceNumber());
    event.setDescription(reason);
    event.setUserId(getCurrentUserId());
    Map<String, Object> payload = new HashMap<>();
    payload.put("reversalEntryId", reversal.getId());
    payload.put("reversalReference", reversal.getReferenceNumber());
    payload.put("reason", reason != null ? reason : "");
    event.setPayload(serializePayload(payload));

    return saveWithSequenceRetry(event, event.getAggregateId());
  }

  /**
   * Record account balance adjustment (for corrections)
   */
  @Transactional
  public AccountingEvent recordBalanceAdjustment(
      Account account, BigDecimal oldBalance, BigDecimal newBalance, String reason) {
    AccountingEvent event = new AccountingEvent();
    event.setCompany(account.getCompany());
    event.setEventType(AccountingEventType.BALANCE_CORRECTION);
    event.setAggregateId(UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes()));
    event.setAggregateType("Account");
    UUID aggregateId = UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes());
    event.setSequenceNumber(eventRepository.getNextSequenceNumber(aggregateId));
    event.setEffectiveDate(companyClock.today(account.getCompany()));
    event.setAccountId(account.getId());
    event.setAccountCode(account.getCode());
    event.setBalanceBefore(oldBalance);
    event.setBalanceAfter(newBalance);
    event.setDescription(reason);
    event.setUserId(getCurrentUserId());

    return saveWithSequenceRetry(event, aggregateId);
  }

  private AccountingEvent saveWithSequenceRetry(AccountingEvent event, UUID aggregateId) {
    DataIntegrityViolationException lastError = null;
    for (int attempt = 1; attempt <= MAX_SEQUENCE_RETRIES; attempt++) {
      try {
        if (attempt > 1) {
          Long next = eventRepository.getNextSequenceNumber(aggregateId);
          event.setSequenceNumber(next);
        }
        return eventRepository.save(event);
      } catch (DataIntegrityViolationException ex) {
        lastError = ex;
        log.warn(
            "Sequence contention for aggregate {} on attempt {}/{}",
            aggregateId,
            attempt,
            MAX_SEQUENCE_RETRIES);
      }
    }
    if (lastError != null) {
      throw lastError;
    }
    throw new IllegalStateException(
        "Failed to persist accounting event for aggregate " + aggregateId);
  }

  /**
   * Replay events to reconstruct account balance at a point in time
   */
  public BigDecimal replayBalanceAsOf(Company company, Long accountId, Instant asOf) {
    return eventRepository
        .findFirstByCompanyAndAccountIdAndEventTimestampLessThanEqualOrderByEventTimestampDescSequenceNumberDesc(
            company, accountId, asOf)
        .map(AccountingEvent::getBalanceAfter)
        .orElse(BigDecimal.ZERO);
  }

  /**
   * Replay events to reconstruct account balance as of a date
   */
  public BigDecimal replayBalanceAsOfDate(Company company, Long accountId, LocalDate asOfDate) {
    return eventRepository
        .findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
            company, accountId, asOfDate)
        .map(AccountingEvent::getBalanceAfter)
        .orElse(BigDecimal.ZERO);
  }

  /**
   * Get full event history for an account
   */
  public List<AccountingEvent> getAccountHistory(Company company, Long accountId) {
    return eventRepository.findAccountMovements(company, accountId);
  }

  /**
   * Get audit trail for a journal entry
   */
  public List<AccountingEvent> getJournalEntryAuditTrail(Long journalEntryId) {
    return eventRepository.findByJournalEntryIdOrderByEventTimestampAsc(journalEntryId);
  }

  /**
   * Get all events from a correlated transaction
   */
  public List<AccountingEvent> getCorrelatedEvents(UUID correlationId) {
    return eventRepository.findByCorrelationIdOrderBySequenceNumberAsc(correlationId);
  }

  private BigDecimal calculateBalanceAfter(
      Account account, JournalLine line, BigDecimal balanceBefore) {
    // For debit-normal accounts (ASSET, EXPENSE): debits increase, credits decrease
    // For credit-normal accounts (LIABILITY, EQUITY, REVENUE): credits increase, debits decrease
    boolean debitNormal = account.getType().isDebitNormalBalance();
    if (debitNormal) {
      return balanceBefore.add(line.getDebit()).subtract(line.getCredit());
    } else {
      return balanceBefore.add(line.getCredit()).subtract(line.getDebit());
    }
  }

  private String getCurrentUserId() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  private void incrementJournalsCreatedMetric(Company company) {
    if (journalsCreatedCounter == null || meterRegistry == null) {
      return;
    }
    String companyTag =
        company != null && company.getCode() != null && !company.getCode().isBlank()
            ? company.getCode().trim().toUpperCase(Locale.ROOT)
            : "UNKNOWN";
    journalsCreatedCounter.increment();
    meterRegistry
        .counter("erp.business.journals.created.by_company", "company", companyTag)
        .increment();
  }

  private String serializePayload(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize event payload", e);
      return "{}";
    }
  }

  // Domain event record for Spring event publishing
  public record JournalEntryPostedEvent(
      Long entryId,
      UUID publicId,
      String referenceNumber,
      LocalDate entryDate,
      UUID correlationId) {}
}
