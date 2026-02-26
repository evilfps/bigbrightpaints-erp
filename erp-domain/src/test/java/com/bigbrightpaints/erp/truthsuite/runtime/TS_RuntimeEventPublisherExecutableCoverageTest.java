package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.bigbrightpaints.erp.orchestrator.service.EventPublisherService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("concurrency")
@Tag("reconciliation")
@Tag("critical")
class TS_RuntimeEventPublisherExecutableCoverageTest {

    @Test
    void enqueue_persists_serialized_event_with_company_scope_and_audit_identifiers() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 99L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        EventPublisherService service = new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper(),
                null
        );

        DomainEvent event = DomainEvent.of(
                "ORDER_APPROVED",
                "C1",
                "ops@bbp.com",
                "sales_order",
                "SO-1001",
                Map.of("items", 2, "total", "250.00"),
                "trace-order-1",
                "req-order-1",
                "idem-order-1"
        );

        service.enqueue(event);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent persisted = eventCaptor.getValue();

        assertThat(persisted.getCompanyId()).isEqualTo(99L);
        assertThat(persisted.getEventType()).isEqualTo("ORDER_APPROVED");
        assertThat(persisted.getAggregateType()).isEqualTo("sales_order");
        assertThat(persisted.getAggregateId()).isEqualTo("SO-1001");
        assertThat(persisted.getTraceId()).isEqualTo("trace-order-1");
        assertThat(persisted.getRequestId()).isEqualTo("req-order-1");
        assertThat(persisted.getIdempotencyKey()).isEqualTo("idem-order-1");
        assertThat(persisted.getPayload()).contains("\"items\":2");
    }

    @Test
    void publishPendingEvents_marks_success_retries_and_dead_letters_deterministically() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EventPublisherService service = new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper(),
                null
        );

        OutboxEvent success = new OutboxEvent("order", "SO-1", "OK", "{\"ok\":true}", 10L);
        OutboxEvent retry = new OutboxEvent("order", "SO-2", "RETRY", "{\"ok\":false}", 10L);
        OutboxEvent deadLetter = new OutboxEvent("order", "SO-3", "DLQ", "{\"ok\":false}", 10L);
        ReflectionTestUtils.setField(deadLetter, "retryCount", 4);

        when(outboxEventRepository.findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(OutboxEvent.Status.PENDING), any())).thenReturn(List.of(success, retry, deadLetter));

        doAnswer(invocation -> {
            String routingKey = invocation.getArgument(1);
            if ("RETRY".equals(routingKey) || "DLQ".equals(routingKey)) {
                throw new RuntimeException("broker down");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("bbp.orchestrator.events"), anyString(), anyString());

        service.publishPendingEvents();

        assertThat(success.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
        assertThat(success.isDeadLetter()).isFalse();

        assertThat(retry.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(retry.getRetryCount()).isEqualTo(1);
        assertThat(retry.isDeadLetter()).isFalse();
        assertThat(retry.getLastError()).isEqualTo("broker down");

        assertThat(deadLetter.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(deadLetter.getRetryCount()).isEqualTo(5);
        assertThat(deadLetter.isDeadLetter()).isTrue();
        assertThat(deadLetter.getLastError()).isEqualTo("broker down");
    }

    @Test
    void healthSnapshot_reads_pending_retrying_and_publishing_counts() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EventPublisherService service = new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper(),
                null
        );

        when(outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING)).thenReturn(7L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(
                OutboxEvent.Status.PENDING, 0)).thenReturn(2L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PUBLISHING)).thenReturn(4L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqual(
                eq(OutboxEvent.Status.PUBLISHING), any())).thenReturn(3L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalseAndLastErrorStartingWith(
                OutboxEvent.Status.PUBLISHING, "AMBIGUOUS_PUBLISH:")).thenReturn(2L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalseAndLastErrorStartingWith(
                OutboxEvent.Status.PUBLISHING, "FINALIZE_FAILURE:")).thenReturn(1L);
        when(outboxEventRepository.countByStatusAndDeadLetterFalseAndLastErrorStartingWith(
                OutboxEvent.Status.PUBLISHING, "STALE_LEASE_UNCERTAIN:")).thenReturn(1L);
        when(outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED)).thenReturn(1L);

        Map<String, Object> snapshot = service.healthSnapshot();
        assertThat(snapshot)
                .containsEntry("pendingEvents", 7L)
                .containsEntry("retryingEvents", 2L)
                .containsEntry("publishingEvents", 4L)
                .containsEntry("stalePublishingEvents", 3L)
                .containsEntry("ambiguousPublishingEvents", 4L)
                .containsEntry("deadLetters", 1L);
    }

    @Test
    void enqueue_fails_closed_when_event_payload_cannot_be_serialized() throws Exception {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 12L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        doThrow(new JsonProcessingException("broken payload") {
        }).when(objectMapper).writeValueAsString(any());

        EventPublisherService service = new EventPublisherService(
                outboxEventRepository,
                rabbitTemplate,
                companyContextService,
                objectMapper,
                null
        );

        DomainEvent event = DomainEvent.of(
                "BROKEN",
                "C1",
                "ops@bbp.com",
                "sales_order",
                "SO-404",
                Map.of("k", "v"),
                "trace-broken",
                "req-broken",
                "idem-broken"
        );

        assertThatThrownBy(() -> service.enqueue(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize event")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void helper_branches_cover_failure_message_resolution_and_backoff_bounds() {
        EventPublisherService service = new EventPublisherService(
                mock(OutboxEventRepository.class),
                mock(RabbitTemplate.class),
                mock(CompanyContextService.class),
                objectMapper(),
                null
        );

        assertThat((Long) ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", -3)).isEqualTo(30L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 0)).isEqualTo(30L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 1)).isEqualTo(60L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(service, "computeBackoffDelay", 11)).isEqualTo(30720L);

        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "resolveFailureMessage",
                (Object) null)).isEqualTo("unknown publish failure");
        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "resolveFailureMessage",
                new RuntimeException("broker down"))).isEqualTo("broker down");
        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "resolveFailureMessage",
                new RuntimeException((String) null))).isEqualTo(RuntimeException.class.getName());
        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "resolveFailureMessage",
                new RuntimeException("   "))).isEqualTo(RuntimeException.class.getName());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
