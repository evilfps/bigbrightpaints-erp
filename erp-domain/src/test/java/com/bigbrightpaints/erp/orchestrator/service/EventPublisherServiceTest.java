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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Test
    void enqueue_serializesAndPersistsOutboxEvent() throws Exception {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

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
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

        DomainEvent event = DomainEvent.of("X", "COMP", "user-1", "Entity", "1", Map.of(),
                "trace-2", "req-2", "idem-2");
        when(objectMapper.writeValueAsString(event)).thenThrow(new JsonProcessingException("boom") {});

        assertThrows(IllegalStateException.class, () -> service.enqueue(event));
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void publishPendingEvents_marksPublishedOnSuccess() {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

        OutboxEvent pending = new OutboxEvent("SalesOrder", "42", "OrderApprovedEvent", "{\"ok\":true}");
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pending));

        service.publishPendingEvents();

        verify(rabbitTemplate).convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");
        assertThat(pending.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    void publishPendingEvents_schedulesRetryOnFailureAndReleasesMutex() {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

        OutboxEvent pending = new OutboxEvent("SalesOrder", "42", "OrderApprovedEvent", "{\"ok\":true}");
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pending));
        doThrow(new RuntimeException("rabbit down"))
                .when(rabbitTemplate)
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");

        Instant before = Instant.now();
        service.publishPendingEvents();

        assertThat(pending.getRetryCount()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt()).isAfter(before);

        // Ensure mutex is released even when publish fails
        AtomicBoolean mutex = (AtomicBoolean) ReflectionTestUtils.getField(service, "publishingInProgress");
        assertThat(mutex).isNotNull();
        assertThat(mutex.get()).isFalse();
    }

    @Test
    void publishPendingEvents_recordsExceptionClassWhenFailureMessageMissing() {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

        OutboxEvent pending = new OutboxEvent("SalesOrder", "42", "OrderApprovedEvent", "{\"ok\":true}");
        when(outboxEventRepository
                .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq(OutboxEvent.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pending));
        doThrow(new RuntimeException())
                .when(rabbitTemplate)
                .convertAndSend("bbp.orchestrator.events", "OrderApprovedEvent", "{\"ok\":true}");

        service.publishPendingEvents();

        assertThat(pending.getRetryCount()).isEqualTo(1);
        assertThat(pending.getLastError()).isEqualTo(RuntimeException.class.getName());
    }

    @Test
    void computeBackoffDelay_usesIntegerDoublingAndCapsExponent() {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);

        Long firstRetryDelay = ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 0);
        Long cappedDelay = ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 20);

        assertThat(firstRetryDelay).isEqualTo(30L);
        assertThat(cappedDelay).isEqualTo(30L * 1024L);
    }

    @Test
    void publishPendingEvents_returnsEarlyWhenAlreadyPublishing() {
        EventPublisherService service = new EventPublisherService(outboxEventRepository, rabbitTemplate, companyContextService, objectMapper, null);
        AtomicBoolean mutex = (AtomicBoolean) ReflectionTestUtils.getField(service, "publishingInProgress");
        assertThat(mutex).isNotNull();
        mutex.set(true);

        service.publishPendingEvents();

        verifyNoInteractions(outboxEventRepository, rabbitTemplate);
    }
}
