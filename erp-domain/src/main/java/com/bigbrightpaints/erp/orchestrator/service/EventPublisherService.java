package com.bigbrightpaints.erp.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEvent;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

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
  private static final long DEFAULT_PUBLISHING_LEASE_SECONDS = 120;
  private static final long DEFAULT_AMBIGUOUS_RECHECK_DELAY_SECONDS = 300;
  private static final String DEFAULT_OUTBOX_LOCK_AT_MOST_FOR = "PT5M";
  private static final String AMBIGUOUS_PUBLISH_ERROR_PREFIX = "AMBIGUOUS_PUBLISH:";
  private static final String FINALIZE_FAILURE_ERROR_PREFIX = "FINALIZE_FAILURE:";
  private static final String STALE_LEASE_UNCERTAIN_ERROR_PREFIX = "STALE_LEASE_UNCERTAIN:";
  private static final PlatformTransactionManager NOOP_TRANSACTION_MANAGER =
      new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
          return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
          // no-op fallback for direct unit construction
        }

        @Override
        public void rollback(TransactionStatus status) {
          // no-op fallback for direct unit construction
        }
      };

  // Mutex to prevent concurrent publishing in single-instance mode
  private final AtomicBoolean publishingInProgress = new AtomicBoolean(false);

  private final OutboxEventRepository outboxEventRepository;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final CompanyContextService companyContextService;
  private final TransactionTemplate newTxTemplate;
  private final long publishingLeaseSeconds;
  private final long ambiguousRecheckDelaySeconds;
  private final Duration outboxLockAtMostFor;

  public EventPublisherService(
      OutboxEventRepository outboxEventRepository,
      RabbitTemplate rabbitTemplate,
      CompanyContextService companyContextService,
      ObjectMapper objectMapper,
      @Nullable MeterRegistry meterRegistry) {
    this(
        outboxEventRepository,
        rabbitTemplate,
        companyContextService,
        objectMapper,
        NOOP_TRANSACTION_MANAGER,
        DEFAULT_PUBLISHING_LEASE_SECONDS,
        DEFAULT_AMBIGUOUS_RECHECK_DELAY_SECONDS,
        DEFAULT_OUTBOX_LOCK_AT_MOST_FOR,
        meterRegistry);
  }

  @Autowired
  public EventPublisherService(
      OutboxEventRepository outboxEventRepository,
      RabbitTemplate rabbitTemplate,
      CompanyContextService companyContextService,
      ObjectMapper objectMapper,
      PlatformTransactionManager txManager,
      @Value("${orchestrator.outbox.publish-lease-seconds:120}") long publishingLeaseSeconds,
      @Value("${orchestrator.outbox.ambiguous-recheck-seconds:300}")
          long ambiguousRecheckDelaySeconds,
      @Value("${orchestrator.outbox.lock-at-most-for:PT5M}") String lockAtMostFor,
      @Nullable MeterRegistry meterRegistry) {
    this.outboxEventRepository = outboxEventRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.companyContextService = companyContextService;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.publishingLeaseSeconds = Math.max(1L, publishingLeaseSeconds);
    this.ambiguousRecheckDelaySeconds = Math.max(1L, ambiguousRecheckDelaySeconds);
    this.outboxLockAtMostFor = parseDuration(lockAtMostFor, DEFAULT_OUTBOX_LOCK_AT_MOST_FOR);
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.newTxTemplate = template;
    assertLockCoversPublishLease();
    registerMetrics();
  }

  @Transactional
  public void enqueue(DomainEvent event) {
    try {
      String payload = objectMapper.writeValueAsString(event);
      Long companyId = companyContextService.requireCurrentCompany().getId();
      String sanitizedTraceId =
          CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(event.traceId());
      String sanitizedRequestId =
          CorrelationIdentifierSanitizer.sanitizeOptionalRequestId(event.requestId());
      String sanitizedIdempotencyKey =
          CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(event.idempotencyKey());
      OutboxEvent outboxEvent =
          new OutboxEvent(
              event.entity(),
              event.entityId(),
              event.eventType(),
              payload,
              companyId,
              sanitizedTraceId,
              sanitizedRequestId,
              sanitizedIdempotencyKey);
      outboxEventRepository.save(outboxEvent);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize event", e);
    }
  }

  @SchedulerLock(
      name = "outbox-publish-pending",
      lockAtMostFor = "${orchestrator.outbox.lock-at-most-for:PT5M}")
  public void publishPendingEvents() {
    // Mutex guard to prevent overlapping runs in single-instance mode
    if (!publishingInProgress.compareAndSet(false, true)) {
      log.debug("Outbox publishing already in progress, skipping");
      return;
    }
    try {
      Instant now = CompanyTime.now();
      reclaimStalePublishingEvents(now);
      List<OutboxEvent> pendingEvents = findReadyEvents(OutboxEvent.Status.PENDING, now);
      for (OutboxEvent pendingEvent : pendingEvents) {
        try {
          UUID eventId = pendingEvent.getId();
          if (eventId == null) {
            publishDetachedFallback(pendingEvent);
            continue;
          }
          publishClaimedEvent(eventId);
        } catch (RuntimeException ex) {
          log.error("Outbox publish cycle failed for event {}", pendingEvent.getId(), ex);
        }
      }
    } finally {
      publishingInProgress.set(false);
    }
  }

  private List<OutboxEvent> findReadyEvents(OutboxEvent.Status status, Instant now) {
    List<OutboxEvent> readyEvents =
        outboxEventRepository
            .findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                status, now);
    return readyEvents == null ? List.of() : readyEvents;
  }

  private void reclaimStalePublishingEvents(Instant now) {
    List<OutboxEvent> stalePublishingSnapshots =
        findReadyEvents(OutboxEvent.Status.PUBLISHING, now);
    for (OutboxEvent staleSnapshot : stalePublishingSnapshots) {
      UUID eventId = staleSnapshot.getId();
      if (eventId == null) {
        log.warn("Skipping stale PUBLISHING outbox row without id");
        continue;
      }
      Long observedFence = staleSnapshot.getVersion();
      try {
        reclaimStalePublishing(eventId, observedFence, now);
      } catch (RuntimeException ex) {
        log.error("Failed to reclaim stale PUBLISHING event {}", eventId, ex);
      }
    }
  }

  private void publishDetachedFallback(OutboxEvent event) {
    try {
      rabbitTemplate.convertAndSend(
          "bbp.orchestrator.events", event.getEventType(), event.getPayload());
      event.markPublished();
    } catch (RuntimeException ex) {
      log.error("Failed to publish detached outbox event fallback", ex);
      long delaySeconds = computeBackoffDelay(event.getRetryCount());
      event.scheduleRetry(resolveFailureMessage(ex), MAX_RETRY_ATTEMPTS, delaySeconds);
    }
  }

  private void publishClaimedEvent(UUID eventId) {
    ClaimedOutboxEvent claimed = claimForPublish(eventId);
    if (claimed == null) {
      return;
    }
    try {
      rabbitTemplate.convertAndSend(
          "bbp.orchestrator.events", claimed.eventType(), claimed.payload());
    } catch (RuntimeException ex) {
      if (isDeterministicRetryableFailure(ex)) {
        log.warn(
            "Publish attempt for event {} failed before broker acceptance; scheduling retry with"
                + " backoff",
            claimed.id(),
            ex);
        scheduleRetry(claimed.id(), claimed.fenceToken(), ex);
        return;
      }
      log.error(
          "Publish outcome for event {} is ambiguous; automatic retry disabled until"
              + " reconciliation",
          claimed.id(),
          ex);
      markAmbiguousPublishingState(claimed.id(), claimed.fenceToken(), ex);
      return;
    }
    try {
      markPublished(claimed.id(), claimed.fenceToken());
    } catch (RuntimeException ex) {
      log.error(
          "Broker publish for event {} succeeded but finalize failed; event held in PUBLISHING for"
              + " reconciliation",
          claimed.id(),
          ex);
      markFinalizeFailure(claimed.id(), claimed.fenceToken(), ex);
    }
  }

  private ClaimedOutboxEvent claimForPublish(UUID eventId) {
    return newTxTemplate.execute(
        status ->
            outboxEventRepository
                .findByIdForUpdate(eventId)
                .filter(this::isPublishable)
                .map(
                    event -> {
                      event.markPublishingUntil(
                          CompanyTime.now().plusSeconds(publishingLeaseSeconds));
                      OutboxEvent claimedEvent = outboxEventRepository.saveAndFlush(event);
                      return new ClaimedOutboxEvent(
                          claimedEvent.getId(),
                          claimedEvent.getEventType(),
                          claimedEvent.getPayload(),
                          normalizeFenceToken(claimedEvent.getVersion()));
                    })
                .orElse(null));
  }

  private boolean isPublishable(OutboxEvent event) {
    if (event.getStatus() != OutboxEvent.Status.PENDING || event.isDeadLetter()) {
      return false;
    }
    Instant nextAttemptAt = event.getNextAttemptAt();
    return nextAttemptAt == null || !nextAttemptAt.isAfter(CompanyTime.now());
  }

  private void markPublished(UUID eventId, long expectedFenceToken) {
    newTxTemplate.executeWithoutResult(
        status ->
            outboxEventRepository
                .findByIdForUpdate(eventId)
                .ifPresent(
                    event -> {
                      if (event.getStatus() != OutboxEvent.Status.PUBLISHING) {
                        return;
                      }
                      if (!fenceMatches(event, expectedFenceToken)) {
                        log.warn(
                            "Skip finalize for outbox event {} because claim fence {} is stale"
                                + " (current={})",
                            eventId,
                            expectedFenceToken,
                            event.getVersion());
                        return;
                      }
                      event.markPublished();
                      outboxEventRepository.saveAndFlush(event);
                    }));
  }

  private void reclaimStalePublishing(
      UUID eventId, @Nullable Long observedFenceToken, Instant now) {
    newTxTemplate.executeWithoutResult(
        status ->
            outboxEventRepository
                .findByIdForUpdate(eventId)
                .ifPresent(
                    event -> {
                      if (event.getStatus() != OutboxEvent.Status.PUBLISHING
                          || event.isDeadLetter()) {
                        return;
                      }
                      if (!isLeaseDue(event, now)) {
                        return;
                      }
                      if (!fenceMatches(event, normalizeFenceToken(observedFenceToken))) {
                        log.warn(
                            "Skip stale-lease reclaim for outbox event {} because snapshot fence {}"
                                + " no longer matches current {}",
                            eventId,
                            observedFenceToken,
                            event.getVersion());
                        return;
                      }
                      String marker = staleLeaseMarkerForReconciliation(event);
                      event.deferPublishing(marker, ambiguousRecheckDelaySeconds);
                      outboxEventRepository.saveAndFlush(event);
                      log.error(
                          "Outbox event {} lease expired in PUBLISHING (fence={}); held fail-closed"
                              + " for reconciliation",
                          eventId,
                          event.getVersion());
                    }));
  }

  private void markAmbiguousPublishingState(
      UUID eventId, long expectedFenceToken, RuntimeException ex) {
    String errorMessage = AMBIGUOUS_PUBLISH_ERROR_PREFIX + resolveFailureMessage(ex);
    holdInPublishingForReconciliation(eventId, expectedFenceToken, errorMessage);
  }

  private void markFinalizeFailure(UUID eventId, long expectedFenceToken, RuntimeException ex) {
    String errorMessage = FINALIZE_FAILURE_ERROR_PREFIX + resolveFailureMessage(ex);
    holdInPublishingForReconciliation(eventId, expectedFenceToken, errorMessage);
  }

  private void holdInPublishingForReconciliation(
      UUID eventId, long expectedFenceToken, String errorMessage) {
    newTxTemplate.executeWithoutResult(
        status ->
            outboxEventRepository
                .findByIdForUpdate(eventId)
                .ifPresent(
                    event -> {
                      if (event.getStatus() != OutboxEvent.Status.PUBLISHING
                          || event.isDeadLetter()) {
                        return;
                      }
                      if (!fenceMatches(event, expectedFenceToken)) {
                        log.warn(
                            "Skip reconciliation hold for outbox event {} because claim fence {} is"
                                + " stale (current={})",
                            eventId,
                            expectedFenceToken,
                            event.getVersion());
                        return;
                      }
                      event.deferPublishing(errorMessage, ambiguousRecheckDelaySeconds);
                      outboxEventRepository.saveAndFlush(event);
                    }));
  }

  private void scheduleRetry(UUID eventId, long expectedFenceToken, RuntimeException ex) {
    String errorMessage = resolveFailureMessage(ex);
    newTxTemplate.executeWithoutResult(
        status ->
            outboxEventRepository
                .findByIdForUpdate(eventId)
                .ifPresent(
                    event -> {
                      if (event.getStatus() != OutboxEvent.Status.PUBLISHING
                          || event.isDeadLetter()) {
                        return;
                      }
                      if (!fenceMatches(event, expectedFenceToken)) {
                        log.warn(
                            "Skip retry scheduling for outbox event {} because claim fence {} is"
                                + " stale (current={})",
                            eventId,
                            expectedFenceToken,
                            event.getVersion());
                        return;
                      }
                      long delaySeconds = computeBackoffDelay(event.getRetryCount());
                      event.scheduleRetry(errorMessage, MAX_RETRY_ATTEMPTS, delaySeconds);
                      outboxEventRepository.saveAndFlush(event);
                    }));
  }

  private boolean isLeaseDue(OutboxEvent event, Instant now) {
    Instant nextAttemptAt = event.getNextAttemptAt();
    return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
  }

  private boolean isDeterministicRetryableFailure(RuntimeException ex) {
    return ex instanceof MessageConversionException
        || ex instanceof AmqpConnectException
        || ex instanceof AmqpAuthenticationException;
  }

  private boolean isAmbiguousPublishingState(OutboxEvent event) {
    String marker = event.getLastError();
    if (marker == null) {
      return false;
    }
    return marker.startsWith(AMBIGUOUS_PUBLISH_ERROR_PREFIX)
        || marker.startsWith(FINALIZE_FAILURE_ERROR_PREFIX)
        || marker.startsWith(STALE_LEASE_UNCERTAIN_ERROR_PREFIX);
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
    Instant now = CompanyTime.now();
    Map<String, Object> health = new HashMap<>();
    health.put(
        "pendingEvents",
        outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING));
    health.put(
        "retryingEvents",
        outboxEventRepository.countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(
            OutboxEvent.Status.PENDING, 0));
    health.put(
        "publishingEvents",
        outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PUBLISHING));
    health.put(
        "stalePublishingEvents",
        outboxEventRepository.countByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqual(
            OutboxEvent.Status.PUBLISHING, now));
    health.put("ambiguousPublishingEvents", countAmbiguousPublishingEvents());
    health.put(
        "deadLetters",
        outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED));
    return health;
  }

  private void registerMetrics() {
    if (meterRegistry == null) {
      return;
    }
    Gauge.builder(
            "outbox.events.pending",
            () -> outboxEventRepository.countByStatusAndDeadLetterFalse(OutboxEvent.Status.PENDING))
        .description("Outbox events pending publish")
        .register(meterRegistry);
    Gauge.builder(
            "outbox.events.retrying",
            () ->
                outboxEventRepository.countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(
                    OutboxEvent.Status.PENDING, 0))
        .description("Outbox events waiting to retry after failures")
        .register(meterRegistry);
    Gauge.builder(
            "outbox.events.publishing",
            () ->
                outboxEventRepository.countByStatusAndDeadLetterFalse(
                    OutboxEvent.Status.PUBLISHING))
        .description("Outbox events currently in publishing state")
        .register(meterRegistry);
    Gauge.builder(
            "outbox.events.stale_publishing",
            () ->
                outboxEventRepository.countByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqual(
                    OutboxEvent.Status.PUBLISHING, CompanyTime.now()))
        .description("Outbox events stuck in publishing state beyond lease")
        .register(meterRegistry);
    Gauge.builder("outbox.events.ambiguous_publishing", this::countAmbiguousPublishingEvents)
        .description("Outbox events held for manual reconciliation due ambiguous publish outcomes")
        .register(meterRegistry);
    Gauge.builder(
            "outbox.events.deadletters",
            () -> outboxEventRepository.countByStatusAndDeadLetterTrue(OutboxEvent.Status.FAILED))
        .description("Outbox events dead-lettered after retries")
        .register(meterRegistry);
  }

  private long countAmbiguousPublishingEvents() {
    return outboxEventRepository.countAmbiguousPublishingEvents(
        OutboxEvent.Status.PUBLISHING,
        AMBIGUOUS_PUBLISH_ERROR_PREFIX,
        FINALIZE_FAILURE_ERROR_PREFIX,
        STALE_LEASE_UNCERTAIN_ERROR_PREFIX);
  }

  private boolean fenceMatches(OutboxEvent event, long expectedFenceToken) {
    return normalizeFenceToken(event.getVersion()) == expectedFenceToken;
  }

  private String staleLeaseMarkerForReconciliation(OutboxEvent event) {
    if (isAmbiguousPublishingState(event)) {
      return event.getLastError();
    }
    return STALE_LEASE_UNCERTAIN_ERROR_PREFIX + normalizeFenceToken(event.getVersion());
  }

  private long normalizeFenceToken(@Nullable Long fenceToken) {
    return fenceToken == null ? -1L : fenceToken;
  }

  private Duration parseDuration(String rawDuration, String fallbackDuration) {
    String source = (rawDuration == null || rawDuration.isBlank()) ? fallbackDuration : rawDuration;
    try {
      return Duration.parse(source);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "Invalid orchestrator.outbox.lock-at-most-for duration: " + source, ex);
    }
  }

  private void assertLockCoversPublishLease() {
    Duration leaseWindow = Duration.ofSeconds(publishingLeaseSeconds);
    if (!outboxLockAtMostFor.minus(leaseWindow).isPositive()) {
      throw new IllegalStateException(
          "orchestrator.outbox.lock-at-most-for must be greater than"
              + " orchestrator.outbox.publish-lease-seconds");
    }
  }

  private record ClaimedOutboxEvent(UUID id, String eventType, String payload, long fenceToken) {}
}
