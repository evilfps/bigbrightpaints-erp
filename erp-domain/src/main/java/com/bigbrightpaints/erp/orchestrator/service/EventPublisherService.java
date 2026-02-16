package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for publishing domain events via outbox pattern.
 * Uses AtomicBoolean mutex to prevent concurrent publishing in single-instance mode.
 * For multi-instance deployments, consider adding ShedLock for distributed locking.
 */
@Service
public class EventPublisherService {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherService.class);

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_BASE_DELAY_SECONDS = 30;
    
    // Mutex to prevent concurrent publishing in single-instance mode
    private final AtomicBoolean publishingInProgress = new AtomicBoolean(false);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final CompanyContextService companyContextService;

    public EventPublisherService(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
                                 CompanyContextService companyContextService,
                                 ObjectMapper objectMapper,
                                 @Nullable MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.companyContextService = companyContextService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        registerMetrics();
    }

    @Transactional
    public void enqueue(DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            Long companyId = companyContextService.requireCurrentCompany().getId();
            OutboxEvent outboxEvent = new OutboxEvent(
                    event.entity(),
                    event.entityId(),
                    event.eventType(),
                    payload,
                    companyId,
                    event.traceId(),
                    event.requestId(),
                    event.idempotencyKey());
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }

    @Transactional
    @SchedulerLock(name = "outbox-publish-pending")
    public void publishPendingEvents() {
        // Mutex guard to prevent overlapping runs in single-instance mode
        if (!publishingInProgress.compareAndSet(false, true)) {
            log.debug("Outbox publishing already in progress, skipping");
            return;
        }
        try {
            List<OutboxEvent> pending = outboxEventRepository
                    .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            OutboxEvent.Status.PENDING, CompanyTime.now());
            for (OutboxEvent event : pending) {
                try {
                    rabbitTemplate.convertAndSend("bbp.orchestrator.events", event.getEventType(), event.getPayload());
                    event.markPublished();
                } catch (Exception ex) {
                    log.error("Failed to publish event {}", event.getId(), ex);
                    long delaySeconds = computeBackoffDelay(event.getRetryCount());
                    event.scheduleRetry(resolveFailureMessage(ex), MAX_RETRY_ATTEMPTS, delaySeconds);
                }
            }
        } finally {
            publishingInProgress.set(false);
        }
    }

    private long computeBackoffDelay(int retryCount) {
        int exponent = Math.max(0, Math.min(retryCount, 10));
        return RETRY_BASE_DELAY_SECONDS * (1L << exponent);
    }

    private String resolveFailureMessage(Exception ex) {
        if (ex == null) {
            return "unknown publish failure";
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return ex.getClass().getName();
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> health = new HashMap<>();
        health.put("pendingEvents", outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING));
        health.put("retryingEvents", outboxEventRepository
                .countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(OutboxEvent.Status.PENDING, 0));
        health.put("deadLetters", outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED));
        return health;
    }

    private void registerMetrics() {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("outbox.events.pending",
                () -> outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING))
                .description("Outbox events pending publish")
                .register(meterRegistry);
        Gauge.builder("outbox.events.retrying",
                () -> outboxEventRepository.countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(
                        OutboxEvent.Status.PENDING, 0))
                .description("Outbox events waiting to retry after failures")
                .register(meterRegistry);
        Gauge.builder("outbox.events.deadletters",
                () -> outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED))
                .description("Outbox events dead-lettered after retries")
                .register(meterRegistry);
    }
}
