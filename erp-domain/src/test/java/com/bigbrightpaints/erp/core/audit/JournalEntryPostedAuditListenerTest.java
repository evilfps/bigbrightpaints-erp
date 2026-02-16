package com.bigbrightpaints.erp.core.audit;

import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JournalEntryPostedAuditListenerTest {

    @Mock
    private AuditService auditService;

    @InjectMocks
    private JournalEntryPostedAuditListener listener;

    @Test
    void writesJournalPostedAuditMetadata() {
        UUID publicId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        AccountingEventStore.JournalEntryPostedEvent event = new AccountingEventStore.JournalEntryPostedEvent(
                42L,
                publicId,
                "JE-2026-00042",
                LocalDate.of(2026, 2, 16),
                correlationId
        );

        listener.onJournalEntryPosted(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logEvent(eq(AuditEvent.JOURNAL_ENTRY_POSTED), eq(AuditStatus.SUCCESS), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata).containsEntry("journalEntryId", "42");
        assertThat(metadata).containsEntry("journalReference", "JE-2026-00042");
        assertThat(metadata).containsEntry("correlationId", correlationId.toString());
    }

    @Test
    void ignoresEventsWithoutJournalId() {
        AccountingEventStore.JournalEntryPostedEvent event = new AccountingEventStore.JournalEntryPostedEvent(
                null,
                UUID.randomUUID(),
                "JE-2026-00099",
                LocalDate.of(2026, 2, 16),
                UUID.randomUUID()
        );

        listener.onJournalEntryPosted(event);

        verify(auditService, never()).logEvent(eq(AuditEvent.JOURNAL_ENTRY_POSTED), eq(AuditStatus.SUCCESS), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void swallowsAuditServiceFailures() {
        AccountingEventStore.JournalEntryPostedEvent event = new AccountingEventStore.JournalEntryPostedEvent(
                77L,
                UUID.randomUUID(),
                "JE-2026-00077",
                LocalDate.of(2026, 2, 16),
                UUID.randomUUID()
        );
        doThrow(new IllegalStateException("audit-log-down"))
                .when(auditService)
                .logEvent(eq(AuditEvent.JOURNAL_ENTRY_POSTED), eq(AuditStatus.SUCCESS), org.mockito.ArgumentMatchers.anyMap());

        assertThatCode(() -> listener.onJournalEntryPosted(event)).doesNotThrowAnyException();
    }
}
