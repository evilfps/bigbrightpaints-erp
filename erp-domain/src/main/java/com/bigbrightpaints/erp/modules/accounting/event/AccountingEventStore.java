package com.bigbrightpaints.erp.modules.accounting.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audittrail.AuditCorrelationIdResolver;
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
    UUID correlationId = resolveFlowCorrelationId(entry);
    List<AccountingEvent> events = new ArrayList<>();
    String userId = getCurrentUserId();

    // Journal created event (created and posted are distinct lifecycle markers)
    AccountingEvent createdEvent = new AccountingEvent();
    createdEvent.setCompany(entry.getCompany());
    createdEvent.setEventType(AccountingEventType.JOURNAL_ENTRY_CREATED);
    createdEvent.setAggregateId(entry.getPublicId());
    createdEvent.setAggregateType("JournalEntry");
    createdEvent.setSequenceNumber(eventRepository.getNextSequenceNumber(entry.getPublicId()));
    createdEvent.setEffectiveDate(entry.getEntryDate());
    createdEvent.setJournalEntryId(entry.getId());
    createdEvent.setJournalReference(entry.getReferenceNumber());
    createdEvent.setDescription(entry.getMemo());
    createdEvent.setUserId(userId);
    createdEvent.setCorrelationId(correlationId);
    Map<String, Object> createdPayload = new HashMap<>();
    createdPayload.put("status", entry.getStatus());
    createdPayload.put("journalType", entry.getJournalType());
    createdPayload.put("sourceModule", entry.getSourceModule());
    createdPayload.put("sourceReference", entry.getSourceReference());
    createdEvent.setPayload(serializePayload(createdPayload));
    events.add(saveWithSequenceRetry(createdEvent, createdEvent.getAggregateId()));

    // Journal posted event
    AccountingEvent postedEvent = new AccountingEvent();
    postedEvent.setCompany(entry.getCompany());
    postedEvent.setEventType(AccountingEventType.JOURNAL_ENTRY_POSTED);
    postedEvent.setAggregateId(entry.getPublicId());
    postedEvent.setAggregateType("JournalEntry");
    postedEvent.setSequenceNumber(eventRepository.getNextSequenceNumber(entry.getPublicId()));
    postedEvent.setEffectiveDate(entry.getEntryDate());
    postedEvent.setJournalEntryId(entry.getId());
    postedEvent.setJournalReference(entry.getReferenceNumber());
    postedEvent.setDescription(entry.getMemo());
    postedEvent.setUserId(userId);
    postedEvent.setCorrelationId(correlationId);
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
    postedEvent.setPayload(serializePayload(entryPayload));
    events.add(saveWithSequenceRetry(postedEvent, postedEvent.getAggregateId()));

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
    return recordJournalEntryCorrection(
        original, reversal, reason, AccountingEventType.JOURNAL_ENTRY_REVERSED);
  }

  /**
   * Record a journal entry void operation.
   */
  @Transactional
  public AccountingEvent recordJournalEntryVoided(
      JournalEntry original, JournalEntry voidEntry, String reason) {
    return recordJournalEntryCorrection(
        original, voidEntry, reason, AccountingEventType.JOURNAL_ENTRY_VOIDED);
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

  @Transactional
  public AccountingEvent recordDealerReceiptPosted(
      JournalEntry entry, Long dealerId, BigDecimal amount, String idempotencyKey) {
    return recordPartnerPaymentEvent(
        entry,
        AccountingEventType.DEALER_RECEIPT_POSTED,
        "DEALER",
        dealerId,
        amount,
        idempotencyKey);
  }

  @Transactional
  public AccountingEvent recordSupplierPaymentPosted(
      JournalEntry entry, Long supplierId, BigDecimal amount, String idempotencyKey) {
    return recordPartnerPaymentEvent(
        entry,
        AccountingEventType.SUPPLIER_PAYMENT_POSTED,
        "SUPPLIER",
        supplierId,
        amount,
        idempotencyKey);
  }

  @Transactional
  public AccountingEvent recordSettlementAllocated(
      JournalEntry entry,
      String partnerType,
      Long partnerId,
      BigDecimal amount,
      int allocationCount,
      String idempotencyKey) {
    if (entry == null) {
      throw new IllegalArgumentException("entry is required");
    }
    UUID aggregateId =
        entry.getPublicId() != null
            ? entry.getPublicId()
            : UUID.nameUUIDFromBytes(("JournalEntry-" + entry.getId()).getBytes());
    AccountingEvent event = new AccountingEvent();
    event.setCompany(entry.getCompany());
    event.setEventType(AccountingEventType.SETTLEMENT_ALLOCATED);
    event.setAggregateId(aggregateId);
    event.setAggregateType("JournalEntry");
    event.setSequenceNumber(eventRepository.getNextSequenceNumber(aggregateId));
    event.setEffectiveDate(entry.getEntryDate());
    event.setJournalEntryId(entry.getId());
    event.setJournalReference(entry.getReferenceNumber());
    event.setDescription(entry.getMemo());
    event.setUserId(getCurrentUserId());
    event.setCorrelationId(resolveFlowCorrelationId(entry, idempotencyKey, partnerType));
    Map<String, Object> payload = new HashMap<>();
    payload.put("partnerType", partnerType);
    payload.put("partnerId", partnerId);
    payload.put("amount", amount != null ? amount : BigDecimal.ZERO);
    payload.put("allocationCount", Math.max(allocationCount, 0));
    payload.put("idempotencyKey", idempotencyKey != null ? idempotencyKey : "");
    payload.put("sourceModule", entry.getSourceModule());
    event.setPayload(serializePayload(payload));
    return saveWithSequenceRetry(event, aggregateId);
  }

  private AccountingEvent recordPartnerPaymentEvent(
      JournalEntry entry,
      AccountingEventType eventType,
      String partnerType,
      Long partnerId,
      BigDecimal amount,
      String idempotencyKey) {
    if (entry == null) {
      throw new IllegalArgumentException("entry is required");
    }
    UUID aggregateId =
        entry.getPublicId() != null
            ? entry.getPublicId()
            : UUID.nameUUIDFromBytes(("JournalEntry-" + entry.getId()).getBytes());
    AccountingEvent event = new AccountingEvent();
    event.setCompany(entry.getCompany());
    event.setEventType(eventType);
    event.setAggregateId(aggregateId);
    event.setAggregateType("JournalEntry");
    event.setSequenceNumber(eventRepository.getNextSequenceNumber(aggregateId));
    event.setEffectiveDate(entry.getEntryDate());
    event.setJournalEntryId(entry.getId());
    event.setJournalReference(entry.getReferenceNumber());
    event.setDescription(entry.getMemo());
    event.setUserId(getCurrentUserId());
    event.setCorrelationId(resolveFlowCorrelationId(entry, idempotencyKey, partnerType));
    Map<String, Object> payload = new HashMap<>();
    payload.put("partnerType", partnerType);
    payload.put("partnerId", partnerId);
    payload.put("amount", amount != null ? amount : BigDecimal.ZERO);
    payload.put("idempotencyKey", idempotencyKey != null ? idempotencyKey : "");
    payload.put("sourceModule", entry.getSourceModule());
    event.setPayload(serializePayload(payload));
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

  private AccountingEvent recordJournalEntryCorrection(
      JournalEntry original,
      JournalEntry correctionEntry,
      String reason,
      AccountingEventType eventType) {
    AccountingEvent event = new AccountingEvent();
    event.setCompany(original.getCompany());
    event.setEventType(eventType);
    event.setAggregateId(original.getPublicId());
    event.setAggregateType("JournalEntry");
    event.setSequenceNumber(eventRepository.getNextSequenceNumber(original.getPublicId()));
    event.setEffectiveDate(correctionEntry.getEntryDate());
    event.setJournalEntryId(original.getId());
    event.setJournalReference(original.getReferenceNumber());
    event.setDescription(reason);
    event.setUserId(getCurrentUserId());
    event.setCorrelationId(resolveFlowCorrelationId(correctionEntry, original.getSourceReference()));
    Map<String, Object> payload = new HashMap<>();
    payload.put("correctionEntryId", correctionEntry.getId());
    payload.put("correctionReference", correctionEntry.getReferenceNumber());
    payload.put("reason", reason != null ? reason : "");
    payload.put("correctionType", eventType.name());
    if (eventType == AccountingEventType.JOURNAL_ENTRY_REVERSED) {
      payload.put("reversalEntryId", correctionEntry.getId());
      payload.put("reversalReference", correctionEntry.getReferenceNumber());
    }
    event.setPayload(serializePayload(payload));

    return saveWithSequenceRetry(event, event.getAggregateId());
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

  private UUID resolveFlowCorrelationId(JournalEntry entry, String... additionalKeys) {
    List<String> fallbackKeys = new ArrayList<>();
    if (entry != null) {
      appendCorrelationFallback(fallbackKeys, entry.getSourceReference());
      appendCorrelationFallback(fallbackKeys, entry.getSourceModule());
    }
    if (additionalKeys != null) {
      for (String additionalKey : additionalKeys) {
        appendCorrelationFallback(fallbackKeys, additionalKey);
      }
    }
    return AuditCorrelationIdResolver.resolveCorrelationId(
        AuditCorrelationIdResolver.currentRequest(), fallbackKeys.toArray(String[]::new));
  }

  private void appendCorrelationFallback(List<String> fallbackKeys, String value) {
    if (!StringUtils.hasText(value)) {
      return;
    }
    fallbackKeys.add(value.trim());
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
