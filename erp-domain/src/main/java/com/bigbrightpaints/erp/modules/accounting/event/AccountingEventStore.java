package com.bigbrightpaints.erp.modules.accounting.event;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Event store for accounting domain events.
 * Provides append-only event storage with replay capability.
 */
@Service
public class AccountingEventStore {

    private static final Logger log = LoggerFactory.getLogger(AccountingEventStore.class);

    private final AccountingEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final CompanyClock companyClock;

    public AccountingEventStore(AccountingEventRepository eventRepository,
                                ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                CompanyClock companyClock) {
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.companyClock = companyClock;
    }

    /**
     * Record a journal entry posted event with all line items
     */
    @Transactional
    public List<AccountingEvent> recordJournalEntryPosted(JournalEntry entry, Map<Long, BigDecimal> balancesBefore) {
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
        BigDecimal totalDebit = entry.getLines().stream()
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entry.getLines().stream()
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        entryEvent.setPayload(serializePayload(Map.of(
                "status", entry.getStatus(),
                "totalDebit", totalDebit,
                "totalCredit", totalCredit
        )));
        events.add(eventRepository.save(entryEvent));

        // Individual line events (for balance tracking)
        for (JournalLine line : entry.getLines()) {
            Account account = line.getAccount();
            BigDecimal balanceBefore = balancesBefore.getOrDefault(account.getId(), account.getBalance());
            BigDecimal balanceAfter = calculateBalanceAfter(account, line, balanceBefore);

            AccountingEvent lineEvent = new AccountingEvent();
            lineEvent.setCompany(entry.getCompany());
            lineEvent.setEventType(line.getDebit().compareTo(BigDecimal.ZERO) > 0 
                    ? AccountingEventType.ACCOUNT_DEBIT_POSTED 
                    : AccountingEventType.ACCOUNT_CREDIT_POSTED);
            lineEvent.setAggregateId(UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes()));
            lineEvent.setAggregateType("Account");
            lineEvent.setSequenceNumber(eventRepository.getNextSequenceNumber(
                    UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes())));
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
            events.add(eventRepository.save(lineEvent));
        }

        // Publish for any listeners (cache invalidation, etc.)
        eventPublisher.publishEvent(new JournalEntryPostedEvent(
                entry.getId(),
                entry.getPublicId(),
                entry.getReferenceNumber(),
                entry.getEntryDate(),
                correlationId
        ));

        log.debug("Recorded {} events for journal entry {}", events.size(), entry.getReferenceNumber());
        return events;
    }

    /**
     * Record a journal entry reversal
     */
    @Transactional
    public AccountingEvent recordJournalEntryReversed(JournalEntry original, JournalEntry reversal, String reason) {
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
        event.setPayload(serializePayload(Map.of(
                "reversalEntryId", reversal.getId(),
                "reversalReference", reversal.getReferenceNumber(),
                "reason", reason != null ? reason : ""
        )));

        return eventRepository.save(event);
    }

    /**
     * Record account balance adjustment (for corrections)
     */
    @Transactional
    public AccountingEvent recordBalanceAdjustment(Account account, BigDecimal oldBalance, 
                                                    BigDecimal newBalance, String reason) {
        AccountingEvent event = new AccountingEvent();
        event.setCompany(account.getCompany());
        event.setEventType(AccountingEventType.BALANCE_CORRECTION);
        event.setAggregateId(UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes()));
        event.setAggregateType("Account");
        event.setSequenceNumber(eventRepository.getNextSequenceNumber(
                UUID.nameUUIDFromBytes(("Account-" + account.getId()).getBytes())));
        event.setEffectiveDate(companyClock.today(account.getCompany()));
        event.setAccountId(account.getId());
        event.setAccountCode(account.getCode());
        event.setBalanceBefore(oldBalance);
        event.setBalanceAfter(newBalance);
        event.setDescription(reason);
        event.setUserId(getCurrentUserId());

        return eventRepository.save(event);
    }

    /**
     * Replay events to reconstruct account balance at a point in time
     */
    public BigDecimal replayBalanceAsOf(Company company, Long accountId, Instant asOf) {
        return eventRepository.findLastEventForAccountAsOf(company, accountId, asOf)
                .map(AccountingEvent::getBalanceAfter)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Replay events to reconstruct account balance as of a date
     */
    public BigDecimal replayBalanceAsOfDate(Company company, Long accountId, LocalDate asOfDate) {
        return eventRepository.findLastEventForAccountAsOfDate(company, accountId, asOfDate)
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

    private BigDecimal calculateBalanceAfter(Account account, JournalLine line, BigDecimal balanceBefore) {
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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ApplicationException(ErrorCode.SYSTEM_INTERNAL_ERROR,
                    "Failed to serialize accounting event payload", e);
        }
    }

    // Domain event record for Spring event publishing
    public record JournalEntryPostedEvent(
            Long entryId,
            UUID publicId,
            String referenceNumber,
            LocalDate entryDate,
            UUID correlationId
    ) {}
}
