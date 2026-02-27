package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
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

    @Mock
    private CompanyRepository companyRepository;

    private EventPublisherService service() {
        return new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                companyRepository,
                objectMapper,
                null);
    }

    @Test
    void enqueue_serializesAndPersistsOutboxEvent() throws Exception {
        EventPublisherService service = service();

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setCode("COMP");
        when(companyRepository.findByCodeIgnoreCase("COMP")).thenReturn(Optional.of(company));

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
    void publishPendingEvents_continuesWhenLoopIterationThrowsRuntimeException() {
        EventPublisherService service = service();
        UUID failingId = UUID.randomUUID();
        UUID successfulId = UUID.randomUUID();
        OutboxEvent failingSnapshot = pendingEvent(failingId);
        OutboxEvent successfulSnapshot = pendingEvent(successfulId);
        OutboxEvent successfulClaimedEntity = pendingEvent(successfulId);
        OutboxEvent successfulFinalizeEntity = pendingEvent(successfulId);
        successfulFinalizeEntity.markPublishing();
        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(failingSnapshot, successfulSnapshot));
        when(outboxEventRepository.findByIdForUpdate(failingId))
                .thenThrow(new RuntimeException("claim failure"));
        when(outboxEventRepository.findByIdForUpdate(successfulId))
                .thenReturn(Optional.of(successfulClaimedEntity), Optional.of(successfulFinalizeEntity));
        when(outboxEventRepository.saveAndFlush(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.publishPendingEvents();

        verify(rabbitTemplate, times(1))
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");
        assertThat(successfulFinalizeEntity.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
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
    void publishPendingEvents_usesEmptyListWhenPendingQueryReturnsNull() {
        EventPublisherService service = service();
        stubNoStalePublishingRows();
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(null);

        service.publishPendingEvents();

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void publishPendingEvents_skipsNullStaleIdsAndHandlesReclaimExceptions() {
        EventPublisherService service = service();
        OutboxEvent staleWithoutId = new OutboxEvent("SalesOrder", "42", "OrderApprovedEvent", "{\"ok\":true}");
        staleWithoutId.markPublishingUntil(Instant.now().minusSeconds(1));
        UUID staleId = UUID.randomUUID();
        OutboxEvent staleWithId = pendingEvent(staleId);
        staleWithId.markPublishingUntil(Instant.now().minusSeconds(1));

        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(List.of(staleWithoutId, staleWithId));
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of());
        when(outboxEventRepository.findByIdForUpdate(staleId))
                .thenThrow(new RuntimeException("reclaim failed"));

        service.publishPendingEvents();

        verify(outboxEventRepository).findByIdForUpdate(staleId);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void reclaimStalePublishing_returnsWhenRowIsNotPublishing() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingEntity = pendingEvent(eventId);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(pendingEntity));

        ReflectionTestUtils.invokeMethod(service, "reclaimStalePublishing", eventId, pendingEntity.getVersion(), Instant.now());

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void reclaimStalePublishing_returnsWhenLeaseIsNotDue() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent publishingEntity = pendingEvent(eventId);
        publishingEntity.markPublishingUntil(Instant.now().plusSeconds(120));
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(publishingEntity));

        ReflectionTestUtils.invokeMethod(service, "reclaimStalePublishing", eventId, publishingEntity.getVersion(), Instant.now());

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void reclaimStalePublishing_returnsWhenFenceDoesNotMatchSnapshot() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent publishingEntity = pendingEvent(eventId);
        publishingEntity.setVersion(7L);
        publishingEntity.markPublishingUntil(Instant.now().minusSeconds(5));
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(publishingEntity));

        ReflectionTestUtils.invokeMethod(service, "reclaimStalePublishing", eventId, null, Instant.now());

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
        assertThat(publishingEntity.getLastError()).isNull();
    }

    @Test
    void markPublished_returnsWhenCurrentStateIsNotPublishing() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingEntity = pendingEvent(eventId);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(pendingEntity));

        ReflectionTestUtils.invokeMethod(service, "markPublished", eventId, pendingEntity.getVersion());

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
        assertThat(pendingEntity.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
    }

    @Test
    void holdInPublishingForReconciliation_returnsWhenCurrentStateIsNotPublishing() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingEntity = pendingEvent(eventId);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(pendingEntity));

        ReflectionTestUtils.invokeMethod(
                service,
                "holdInPublishingForReconciliation",
                eventId,
                pendingEntity.getVersion(),
                "FINALIZE_FAILURE:noop");

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void holdInPublishingForReconciliation_returnsWhenFenceTokenDoesNotMatch() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent publishingEntity = pendingEvent(eventId);
        publishingEntity.setVersion(4L);
        publishingEntity.markPublishing();
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(publishingEntity));

        ReflectionTestUtils.invokeMethod(
                service,
                "holdInPublishingForReconciliation",
                eventId,
                999L,
                "FINALIZE_FAILURE:stale-fence");

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
        assertThat(publishingEntity.getLastError()).isNull();
    }

    @Test
    void scheduleRetry_returnsWhenCurrentStateIsNotPublishing() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent pendingEntity = pendingEvent(eventId);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(pendingEntity));

        ReflectionTestUtils.invokeMethod(service, "scheduleRetry", eventId, pendingEntity.getVersion(), new RuntimeException("down"));

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
        assertThat(pendingEntity.getRetryCount()).isZero();
    }

    @Test
    void scheduleRetry_returnsWhenFenceTokenDoesNotMatch() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent publishingEntity = pendingEvent(eventId);
        publishingEntity.setVersion(2L);
        publishingEntity.markPublishing();
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(publishingEntity));

        ReflectionTestUtils.invokeMethod(service, "scheduleRetry", eventId, 3L, new RuntimeException("still down"));

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
        assertThat(publishingEntity.getRetryCount()).isZero();
    }

    @Test
    void staleLeaseMarker_reusesFinalizeAndStaleLeaseMarkers() {
        EventPublisherService service = service();
        OutboxEvent finalizeFailure = pendingEvent(UUID.randomUUID());
        finalizeFailure.deferPublishing("FINALIZE_FAILURE:broker-ack-lost", 30);
        OutboxEvent staleLease = pendingEvent(UUID.randomUUID());
        staleLease.deferPublishing("STALE_LEASE_UNCERTAIN:7", 30);

        String finalizeMarker =
                ReflectionTestUtils.invokeMethod(service, "staleLeaseMarkerForReconciliation", finalizeFailure);
        String staleLeaseMarker =
                ReflectionTestUtils.invokeMethod(service, "staleLeaseMarkerForReconciliation", staleLease);

        assertThat(finalizeMarker).isEqualTo("FINALIZE_FAILURE:broker-ack-lost");
        assertThat(staleLeaseMarker).isEqualTo("STALE_LEASE_UNCERTAIN:7");
    }

    @Test
    void isDeterministicRetryableFailure_classifiesConversionConnectAndAuthExceptions() {
        EventPublisherService service = service();

        Boolean conversionFailure = ReflectionTestUtils.invokeMethod(
                service,
                "isDeterministicRetryableFailure",
                new MessageConversionException("invalid payload"));
        Boolean connectFailure = ReflectionTestUtils.invokeMethod(
                service,
                "isDeterministicRetryableFailure",
                new AmqpConnectException(new RuntimeException("broker down")));
        Boolean authFailure = ReflectionTestUtils.invokeMethod(
                service,
                "isDeterministicRetryableFailure",
                new AmqpAuthenticationException(new RuntimeException("access denied")));
        Boolean unknownFailure = ReflectionTestUtils.invokeMethod(
                service,
                "isDeterministicRetryableFailure",
                new RuntimeException("unknown"));

        assertThat(conversionFailure).isTrue();
        assertThat(connectFailure).isTrue();
        assertThat(authFailure).isTrue();
        assertThat(unknownFailure).isFalse();
    }

    @Test
    void parseDuration_usesFallbackWhenRawValueIsBlank() {
        EventPublisherService service = service();

        Duration parsedDuration = ReflectionTestUtils.invokeMethod(service, "parseDuration", " ", "PT7M");

        assertThat(parsedDuration).isEqualTo(Duration.parse("PT7M"));
    }

    @Test
    void parseDuration_usesFallbackWhenRawValueIsNull() {
        EventPublisherService service = service();

        Duration parsedDuration = ReflectionTestUtils.invokeMethod(service, "parseDuration", (Object) null, "PT9M");

        assertThat(parsedDuration).isEqualTo(Duration.parse("PT9M"));
    }

    @Test
    void isPublishable_respectsDeadLetterAndNextAttemptTimingBranches() {
        EventPublisherService service = service();
        OutboxEvent deadLetterPending = pendingEvent(UUID.randomUUID());
        ReflectionTestUtils.setField(deadLetterPending, "deadLetter", true);
        OutboxEvent nullNextAttemptPending = pendingEvent(UUID.randomUUID());
        ReflectionTestUtils.setField(nullNextAttemptPending, "nextAttemptAt", null);
        OutboxEvent futureAttemptPending = pendingEvent(UUID.randomUUID());
        ReflectionTestUtils.setField(futureAttemptPending, "nextAttemptAt", Instant.now().plusSeconds(60));

        Boolean deadLetterPublishable =
                ReflectionTestUtils.invokeMethod(service, "isPublishable", deadLetterPending);
        Boolean nullNextAttemptPublishable =
                ReflectionTestUtils.invokeMethod(service, "isPublishable", nullNextAttemptPending);
        Boolean futureAttemptPublishable =
                ReflectionTestUtils.invokeMethod(service, "isPublishable", futureAttemptPending);

        assertThat(deadLetterPublishable).isFalse();
        assertThat(nullNextAttemptPublishable).isTrue();
        assertThat(futureAttemptPublishable).isFalse();
    }

    @Test
    void reclaimStalePublishing_returnsWhenRowIsDeadLetter() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent deadLetterPublishingEntity = pendingEvent(eventId);
        deadLetterPublishingEntity.markPublishingUntil(Instant.now().minusSeconds(5));
        ReflectionTestUtils.setField(deadLetterPublishingEntity, "deadLetter", true);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(deadLetterPublishingEntity));

        ReflectionTestUtils.invokeMethod(
                service,
                "reclaimStalePublishing",
                eventId,
                deadLetterPublishingEntity.getVersion(),
                Instant.now());

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void holdInPublishingForReconciliation_returnsWhenRowIsDeadLetter() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent deadLetterPublishingEntity = pendingEvent(eventId);
        deadLetterPublishingEntity.markPublishing();
        ReflectionTestUtils.setField(deadLetterPublishingEntity, "deadLetter", true);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(deadLetterPublishingEntity));

        ReflectionTestUtils.invokeMethod(
                service,
                "holdInPublishingForReconciliation",
                eventId,
                deadLetterPublishingEntity.getVersion(),
                "FINALIZE_FAILURE:dead-lettered");

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void scheduleRetry_returnsWhenRowIsDeadLetter() {
        EventPublisherService service = service();
        UUID eventId = UUID.randomUUID();
        OutboxEvent deadLetterPublishingEntity = pendingEvent(eventId);
        deadLetterPublishingEntity.markPublishing();
        ReflectionTestUtils.setField(deadLetterPublishingEntity, "deadLetter", true);
        when(outboxEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(deadLetterPublishingEntity));

        ReflectionTestUtils.invokeMethod(
                service,
                "scheduleRetry",
                eventId,
                deadLetterPublishingEntity.getVersion(),
                new RuntimeException("dead-lettered"));

        verify(outboxEventRepository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void isLeaseDue_returnsTrueWhenNextAttemptIsNull() {
        EventPublisherService service = service();
        OutboxEvent event = pendingEvent(UUID.randomUUID());
        ReflectionTestUtils.setField(event, "nextAttemptAt", null);

        Boolean leaseDue = ReflectionTestUtils.invokeMethod(service, "isLeaseDue", event, Instant.now());

        assertThat(leaseDue).isTrue();
    }

    @Test
    void isAmbiguousPublishingState_returnsFalseForUnknownMarker() {
        EventPublisherService service = service();
        OutboxEvent event = pendingEvent(UUID.randomUUID());
        ReflectionTestUtils.setField(event, "lastError", "NON_RECONCILIATION_MARKER");

        Boolean ambiguous = ReflectionTestUtils.invokeMethod(service, "isAmbiguousPublishingState", event);

        assertThat(ambiguous).isFalse();
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
    void constructor_failsWhenLockDurationIsInvalid() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new EventPublisherService(
                        outboxEventRepository,
                        rabbitTemplate,
                        companyContextService,
                        objectMapper,
                        txManager,
                        120,
                        300,
                        "not-a-duration",
                        null));
        assertThat(thrown).hasMessageContaining("Invalid orchestrator.outbox.lock-at-most-for duration");
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
    void constructor_registersAndEvaluatesOutboxGaugesWhenMeterRegistryProvided() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING)).thenReturn(11L);
        when(outboxEventRepository
                .countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(OutboxEvent.Status.PENDING, 0))
                .thenReturn(3L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PUBLISHING)).thenReturn(5L);
        when(outboxEventRepository
                .countByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqual(eq(OutboxEvent.Status.PUBLISHING), any(Instant.class)))
                .thenReturn(2L);
        when(outboxEventRepository
                .countByStatusAndDeadLetterFalseAndLastErrorStartingWith(OutboxEvent.Status.PUBLISHING, "AMBIGUOUS_PUBLISH:"))
                .thenReturn(4L);
        when(outboxEventRepository
                .countByStatusAndDeadLetterFalseAndLastErrorStartingWith(OutboxEvent.Status.PUBLISHING, "FINALIZE_FAILURE:"))
                .thenReturn(1L);
        when(outboxEventRepository
                .countByStatusAndDeadLetterFalseAndLastErrorStartingWith(OutboxEvent.Status.PUBLISHING, "STALE_LEASE_UNCERTAIN:"))
                .thenReturn(2L);
        when(outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED)).thenReturn(6L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper,
                txManager,
                120,
                300,
                "PT5M",
                meterRegistry);

        assertThat(meterRegistry.get("outbox.events.pending").gauge().value()).isEqualTo(11.0d);
        assertThat(meterRegistry.get("outbox.events.retrying").gauge().value()).isEqualTo(3.0d);
        assertThat(meterRegistry.get("outbox.events.publishing").gauge().value()).isEqualTo(5.0d);
        assertThat(meterRegistry.get("outbox.events.stale_publishing").gauge().value()).isEqualTo(2.0d);
        assertThat(meterRegistry.get("outbox.events.ambiguous_publishing").gauge().value()).isEqualTo(7.0d);
        assertThat(meterRegistry.get("outbox.events.deadletters").gauge().value()).isEqualTo(6.0d);
        meterRegistry.close();
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
