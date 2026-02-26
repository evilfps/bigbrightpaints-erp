package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CompanyContextService companyContextService;

    private EventPublisherService service() {
        return new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper,
                null);
    }

    @Test
    void enqueue_serializesAndPersistsOutboxEvent() throws Exception {
        EventPublisherService service = service();

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        DomainEvent event = DomainEvent.of(
                "OrderApprovedEvent",
                "COMP",
                "user-1",
                "SalesOrder",
                "42",
                Map.of("ok", true),
                "trace-1",
                "req-1",
                "idem-1"
        );
        when(objectMapper.writeValueAsString(event)).thenReturn("{\"ok\":true}");

        service.enqueue(event);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent saved = outboxCaptor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo("SalesOrder");
        assertThat(saved.getAggregateId()).isEqualTo("42");
        assertThat(saved.getEventType()).isEqualTo("OrderApprovedEvent");
        assertThat(saved.getPayload()).isEqualTo("{\"ok\":true}");
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getRequestId()).isEqualTo("req-1");
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
    }

    @Test
    void enqueue_failsClosedWhenSerializationFails() throws Exception {
        EventPublisherService service = service();

        DomainEvent event = DomainEvent.of("X", "COMP", "user-1", "Entity", "1", Map.of(),
                "trace-2", "req-2", "idem-2");
        when(objectMapper.writeValueAsString(event)).thenThrow(new JsonProcessingException("boom") {});

        assertThrows(IllegalStateException.class, () -> service.enqueue(event));
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void publishPendingEvents_marksPublishedOnSuccess() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingSnapshot = pendingEvent(eventId);
        OutboxEvent claimedEntity = pendingEvent(eventId);
        OutboxEvent publishFinalizeEntity = pendingEvent(eventId);
        publishFinalizeEntity.markPublishing();
        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pendingSnapshot));
        when(outboxEventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(claimedEntity), Optional.of(publishFinalizeEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.publishPendingEvents();

        verify(rabbitTemplate).convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");
        assertThat(claimedEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(publishFinalizeEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    void publishPendingEvents_schedulesRetryOnDeterministicFailureAndReleasesMutex() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingSnapshot = pendingEvent(eventId);
        OutboxEvent claimedEntity = pendingEvent(eventId);
        OutboxEvent retryEntity = pendingEvent(eventId);
        retryEntity.markPublishing();
        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pendingSnapshot));
        when(outboxEventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(claimedEntity), Optional.of(retryEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new AmqpConnectException(new RuntimeException("rabbit down")))
                .when(rabbitTemplate)
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");

        Instant before = Instant.now();
        service.publishPendingEvents();

        assertThat(retryEntity.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(retryEntity.getRetryCount()).isEqualTo(1);
        assertThat(retryEntity.getNextAttemptAt()).isAfter(before);

        // Ensure mutex is released even when publish fails
        AtomicBoolean mutex = (AtomicBoolean) ReflectionTestUtils.getField(service, "publishingInProgress");
        assertThat(mutex).isNotNull();
        assertThat(mutex.get()).isFalse();
    }

    @Test
    void publishPendingEvents_holdsAmbiguousPublishFailuresForReconciliation() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingSnapshot = pendingEvent(eventId);
        OutboxEvent claimedEntity = pendingEvent(eventId);
        OutboxEvent publishingEntity = pendingEvent(eventId);
        publishingEntity.markPublishing();
        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pendingSnapshot));
        when(outboxEventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(claimedEntity), Optional.of(publishingEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException())
                .when(rabbitTemplate)
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");

        Instant before = Instant.now();
        service.publishPendingEvents();

        assertThat(publishingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(publishingEntity.getRetryCount()).isZero();
        assertThat(publishingEntity.getLastError()).startsWith("AMBIGUOUS_PUBLISH:");
        assertThat(publishingEntity.getLastError()).endsWith(RuntimeException.class.getName());
        assertThat(publishingEntity.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void publishPendingEvents_doesNotRepublishWhenFinalizeRollsBack() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingSnapshot = pendingEvent(eventId);
        OutboxEvent claimedEntity = pendingEvent(eventId);
        OutboxEvent publishFinalizeEntity = pendingEvent(eventId);
        publishFinalizeEntity.markPublishing();
        OutboxEvent stalePublishingEntity = pendingEvent(eventId);
        stalePublishingEntity.markPublishing();

        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pendingSnapshot), List.of(pendingSnapshot));
        when(outboxEventRepository.findByIdForUpdate(eventId))
                .thenReturn(
                        Optional.of(claimedEntity),
                        Optional.of(publishFinalizeEntity),
                        Optional.of(stalePublishingEntity));
        doAnswer(invocation -> {
            OutboxEvent candidate = invocation.getArgument(0);
            if (candidate == publishFinalizeEntity && candidate.getStatus() == OutboxEvent.Status.PUBLISHED) {
                throw new RuntimeException("finalize rollback");
            }
            return candidate;
        }).when(outboxEventRepository).saveAndFlush(any(OutboxEvent.class));

        service.publishPendingEvents();
        service.publishPendingEvents();

        verify(rabbitTemplate, times(1))
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");
        assertThat(stalePublishingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(stalePublishingEntity.getLastError()).startsWith("FINALIZE_FAILURE:");
    }

    @Test
    void publishPendingEvents_holdsCrashWindowPublishingRowsFailClosedUntilReconciled() {
        EventPublisherService service = service();
        UUID staleId = UUID.randomUUID();
        OutboxEvent stalePublishingSnapshot = pendingEvent(staleId);
        stalePublishingSnapshot.markPublishingUntil(Instant.now().minusSeconds(5));
        OutboxEvent stalePublishingEntity = pendingEvent(staleId);
        stalePublishingEntity.markPublishingUntil(Instant.now().minusSeconds(5));

        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(List.of(stalePublishingSnapshot));
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of());
        when(outboxEventRepository.findByIdForUpdate(staleId))
                .thenReturn(Optional.of(stalePublishingEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        service.publishPendingEvents();

        assertThat(stalePublishingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(stalePublishingEntity.getRetryCount()).isZero();
        assertThat(stalePublishingEntity.getLastError()).startsWith("STALE_LEASE_UNCERTAIN:");
        assertThat(stalePublishingEntity.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void publishPendingEvents_multiInstanceLeaseOverlap_doesNotDuplicateBrokerPublish() {
        EventPublisherService publisherA = service();
        EventPublisherService publisherB = service();
        UUID eventId = UUID.randomUUID();

        OutboxEvent pendingSnapshot = pendingEvent(eventId);
        OutboxEvent claimedEntity = pendingEvent(eventId);
        OutboxEvent stalePublishingSnapshot = pendingEvent(eventId);
        OutboxEvent sharedPublishingEntity = pendingEvent(eventId);

        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(List.of(), List.of(stalePublishingSnapshot));
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pendingSnapshot), List.of());
        when(outboxEventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(claimedEntity), Optional.of(sharedPublishingEntity), Optional.of(sharedPublishingEntity));
        doAnswer(invocation -> {
            OutboxEvent candidate = invocation.getArgument(0);
            candidate.setVersion(candidate.getVersion() + 1);
            if (candidate == claimedEntity) {
                sharedPublishingEntity.markPublishingUntil(candidate.getNextAttemptAt());
                sharedPublishingEntity.setVersion(candidate.getVersion());
            }
            return candidate;
        }).when(outboxEventRepository).saveAndFlush(any(OutboxEvent.class));
        doAnswer(invocation -> {
            stalePublishingSnapshot.markPublishingUntil(Instant.now().minusSeconds(1));
            stalePublishingSnapshot.setVersion(sharedPublishingEntity.getVersion());
            sharedPublishingEntity.markPublishingUntil(Instant.now().minusSeconds(1));
            publisherB.publishPendingEvents();
            return null;
        }).when(rabbitTemplate).convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");

        publisherA.publishPendingEvents();

        verify(rabbitTemplate, times(1))
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");
        assertThat(sharedPublishingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(sharedPublishingEntity.getLastError()).startsWith("STALE_LEASE_UNCERTAIN:");
        assertThat(sharedPublishingEntity.getRetryCount()).isZero();
    }

    @Test
    void publishPendingEvents_keepsAmbiguousStalePublishingRowsFailClosed() {
        EventPublisherService service = service();
        UUID staleId = UUID.randomUUID();
        OutboxEvent stalePublishingSnapshot = pendingEvent(staleId);
        stalePublishingSnapshot.deferPublishing("AMBIGUOUS_PUBLISH:ack lost", 1);
        ReflectionTestUtils.setField(stalePublishingSnapshot, "nextAttemptAt", Instant.now().minusSeconds(1));
        OutboxEvent stalePublishingEntity = pendingEvent(staleId);
        stalePublishingEntity.deferPublishing("AMBIGUOUS_PUBLISH:ack lost", 1);
        ReflectionTestUtils.setField(stalePublishingEntity, "nextAttemptAt", Instant.now().minusSeconds(1));

        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(List.of(stalePublishingSnapshot));
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of());
        when(outboxEventRepository.findByIdForUpdate(staleId))
                .thenReturn(Optional.of(stalePublishingEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.publishPendingEvents();

        assertThat(stalePublishingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHING);
        assertThat(stalePublishingEntity.getRetryCount()).isZero();
        assertThat(stalePublishingEntity.getLastError()).isEqualTo("AMBIGUOUS_PUBLISH:ack lost");
    }

    @Test
    void computeBackoffDelay_usesIntegerDoublingAndCapsExponent() {
        EventPublisherService service = service();

        Long firstRetryDelay = ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 0);
        Long cappedDelay = ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 20);

        assertThat(firstRetryDelay).isEqualTo(30L);
        assertThat(cappedDelay).isEqualTo(30L * 1024L);
    }

    @Test
    void constructor_failsWhenLockWindowIsNotGreaterThanLease() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        assertThrows(
                IllegalStateException.class,
                () -> new EventPublisherService(
                        outboxEventRepository,
                        rabbitTemplate,
                        companyContextService,
                        objectMapper,
                        txManager,
                        120,
                        300,
                        "PT120S",
                        null));
    }

    @Test
    void publishPendingEvents_returnsEarlyWhenAlreadyPublishing() {
        EventPublisherService service = service();
        AtomicBoolean mutex = (AtomicBoolean) ReflectionTestUtils.getField(service, "publishingInProgress");
        assertThat(mutex).isNotNull();
        mutex.set(true);

        service.publishPendingEvents();

        verifyNoInteractions(outboxEventRepository, rabbitTemplate);
    }

    private OutboxEvent pendingEvent(UUID eventId) {
        OutboxEvent event = new OutboxEvent("SalesOrder", "42", "OrderApprovedEvent", "{\"ok\":true}");
        ReflectionTestUtils.setField(event, "id", eventId);
        return event;
    }

    private void stubNoStalePublishingRows() {
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(List.of());
    }
}
