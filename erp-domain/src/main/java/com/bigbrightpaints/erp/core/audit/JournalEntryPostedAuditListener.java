package com.bigbrightpaints.erp.core.audit;

import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Restores persisted core audit markers for posted journals based on accounting domain events.
 */
@Component
public class JournalEntryPostedAuditListener {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryPostedAuditListener.class);

    private final AuditService auditService;

    public JournalEntryPostedAuditListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJournalEntryPosted(AccountingEventStore.JournalEntryPostedEvent event) {
        if (event == null || event.entryId() == null) {
            return;
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("journalEntryId", event.entryId().toString());
        if (StringUtils.hasText(event.referenceNumber())) {
            metadata.put("journalReference", event.referenceNumber());
        }
        if (event.correlationId() != null) {
            metadata.put("correlationId", event.correlationId().toString());
        }
        try {
            auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_POSTED, metadata);
        } catch (Exception ex) {
            log.warn("Failed to persist JOURNAL_ENTRY_POSTED audit marker for event {}", event.entryId(), ex);
        }
    }
}
