package com.bigbrightpaints.erp.core.audittrail;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audittrail.web.AuditEventIngestItemRequest;
import com.bigbrightpaints.erp.core.audittrail.web.MlAuditIngestResponse;
import com.bigbrightpaints.erp.core.audittrail.web.MlInteractionEventResponse;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class EnterpriseAuditTrailService {

  private static final Logger log = LoggerFactory.getLogger(EnterpriseAuditTrailService.class);
  private static final int MAX_BATCH_EVENTS = 200;
  private static final int MAX_METADATA_ENTRIES = 40;
  private static final int MAX_METADATA_VALUE_LENGTH = 2000;
  private static final int MAX_PAYLOAD_LENGTH = 16_000;
  private static final int MAX_RETRY_ERROR_LENGTH = 4_000;
  private static final int MAX_IDENTIFIER_LENGTH = 255;

  private final AuditActionEventRepository auditActionEventRepository;
  private final AuditActionEventRetryRepository auditActionEventRetryRepository;
  private final MlInteractionEventRepository mlInteractionEventRepository;
  private final CompanyContextService companyContextService;
  private final ObjectMapper objectMapper;
  private final String auditPrivateKey;

  @Autowired @Lazy private EnterpriseAuditTrailService self;

  @Value("${erp.audit.business.async-enabled:true}")
  private boolean businessEventAsyncEnabled = true;

  @Value("${erp.audit.business.retry.max-attempts:4}")
  private int businessEventRetryMaxAttempts = 4;

  @Value("${erp.audit.business.retry.max-queue-size:500}")
  private int businessEventRetryMaxQueueSize = 500;

  @Value("${erp.audit.business.retry.batch-size:50}")
  private int businessEventRetryBatchSize = 50;

  @Value("${erp.audit.business.retry.backoff-ms:30000}")
  private long businessEventRetryBackoffMs = 30_000L;

  private final Deque<PendingBusinessEventRetry> businessEventRetryQueue =
      new ConcurrentLinkedDeque<>();
  private final AtomicInteger businessEventRetryQueueSize = new AtomicInteger();

  public EnterpriseAuditTrailService(
      AuditActionEventRepository auditActionEventRepository,
      AuditActionEventRetryRepository auditActionEventRetryRepository,
      MlInteractionEventRepository mlInteractionEventRepository,
      CompanyContextService companyContextService,
      ObjectMapper objectMapper,
      @Value("${erp.security.audit.private-key}") String auditPrivateKey) {
    this.auditActionEventRepository = auditActionEventRepository;
    this.auditActionEventRetryRepository = auditActionEventRetryRepository;
    this.mlInteractionEventRepository = mlInteractionEventRepository;
    this.companyContextService = companyContextService;
    this.objectMapper = objectMapper;
    String normalizedAuditPrivateKey = auditPrivateKey == null ? null : auditPrivateKey.strip();
    if (!StringUtils.hasText(normalizedAuditPrivateKey)) {
      throw new IllegalStateException("erp.security.audit.private-key must be configured");
    }
    this.auditPrivateKey = normalizedAuditPrivateKey;
  }

  public void recordBusinessEvent(AuditActionEventCommand command) {
    UserAccount actorSnapshot =
        command != null
            ? (command.actorUserOverride() != null
                ? command.actorUserOverride()
                : resolveCurrentActor().orElse(null))
            : null;
    EnterpriseAuditTrailService dispatcher = self != null ? self : this;
    if (businessEventAsyncEnabled) {
      dispatcher.recordBusinessEventAsync(command, actorSnapshot);
      return;
    }
    dispatcher.recordBusinessEventSync(command, actorSnapshot);
  }

  @Async
  public void recordBusinessEventAsync(AuditActionEventCommand command, UserAccount actorSnapshot) {
    persistBusinessEventWithRetry(command, actorSnapshot);
  }

  public void recordBusinessEventSync(AuditActionEventCommand command, UserAccount actorSnapshot) {
    if (command == null || command.company() == null) {
      return;
    }
    EnterpriseAuditTrailService dispatcher = self != null ? self : this;
    try {
      dispatcher.recordBusinessEventSyncTransactional(command, actorSnapshot);
    } catch (Exception ex) {
      enqueueBusinessEventRetry(command, actorSnapshot, 1, ex);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordBusinessEventSyncTransactional(
      AuditActionEventCommand command, UserAccount actorSnapshot) {
    if (command == null || command.company() == null) {
      return;
    }
    persistBusinessEvent(command, actorSnapshot);
  }

  private void persistBusinessEventWithRetry(
      AuditActionEventCommand command, UserAccount actorSnapshot) {
    if (command == null || command.company() == null) {
      return;
    }
    try {
      persistBusinessEvent(command, actorSnapshot);
    } catch (Exception ex) {
      enqueueBusinessEventRetry(command, actorSnapshot, 1, ex);
    }
  }

  @Scheduled(fixedDelayString = "${erp.audit.business.retry.fixed-delay-ms:30000}")
  void retryQueuedBusinessEvents() {
    int batchLimit = Math.max(1, businessEventRetryBatchSize);
    int processed = 0;
    while (processed < batchLimit) {
      PendingBusinessEventRetry pending = pollBusinessEventRetry();
      if (pending == null) {
        break;
      }
      int failedAttemptCount = pending.failedAttemptCount() + 1;
      try {
        persistBusinessEvent(pending.command(), pending.actorSnapshot());
      } catch (Exception ex) {
        enqueueBusinessEventRetry(
            pending.command(), pending.actorSnapshot(), failedAttemptCount, ex);
      }
      processed++;
    }
    if (processed < batchLimit) {
      EnterpriseAuditTrailService dispatcher = self != null ? self : this;
      dispatcher.retryPersistedBusinessEventsTransactional(batchLimit - processed);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void retryPersistedBusinessEventsTransactional(int batchLimit) {
    retryPersistedBusinessEvents(batchLimit);
  }

  @Transactional
  public MlAuditIngestResponse ingestMlInteractions(
      UserAccount actor, List<AuditEventIngestItemRequest> items, HttpServletRequest request) {
    if (items == null || items.isEmpty()) {
      return new MlAuditIngestResponse(0, 0);
    }
    if (items.size() > MAX_BATCH_EVENTS) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Too many events in one request; max " + MAX_BATCH_EVENTS);
    }
    Company company = companyContextService.requireCurrentCompany();
    IdentitySnapshot identity = resolveMlIdentity(actor, company.getId());

    List<MlInteractionEvent> accepted = new ArrayList<>();
    int dropped = 0;
    for (AuditEventIngestItemRequest item : items) {
      try {
        MlInteractionEvent event = new MlInteractionEvent();
        event.setCompanyId(company.getId());
        event.setOccurredAt(item.occurredAt() != null ? item.occurredAt() : CompanyTime.now());
        event.setModule(trim(item.module(), 64, "ui"));
        event.setAction(trim(item.action(), 128, "interaction"));
        event.setInteractionType(
            trim(
                firstNonBlank(item.interactionType(), inferInteractionType(item.action())),
                32,
                null));
        event.setScreen(trim(item.screen(), 128, null));
        event.setTargetId(trim(item.targetId(), 256, null));
        event.setStatus(item.status() != null ? item.status() : AuditActionEventStatus.INFO);
        event.setFailureReason(trim(item.failureReason(), 512, null));
        event.setCorrelationId(item.correlationId());
        event.setRequestId(
            trim(
                firstNonBlank(item.requestId(), requestHeader(request, "X-Request-Id")),
                128,
                null));
        event.setTraceId(
            trim(firstNonBlank(item.traceId(), requestHeader(request, "X-Trace-Id")), 128, null));
        event.setIpAddress(trim(getClientIpAddress(request), 64, null));
        event.setUserAgent(
            trim(
                request != null ? request.getHeader("User-Agent") : null,
                MAX_METADATA_VALUE_LENGTH,
                null));
        event.setActorUserId(identity.actorUserId());
        event.setActorIdentifier(
            trim(identity.actorIdentifier(), MAX_IDENTIFIER_LENGTH, "anonymous"));
        event.setActorAnonymized(identity.actorAnonymized());
        event.setConsentOptIn(identity.consentOptIn());
        event.setTrainingSubjectKey(identity.trainingSubjectKey());
        event.setPayload(serializePayload(item.trainingPayload()));
        Map<String, String> metadata = sanitizeMetadata(item.metadata());
        if (StringUtils.hasText(item.referenceNumber())) {
          metadata.put("referenceNumber", trim(item.referenceNumber(), 128, null));
        }
        event.setMetadata(metadata);
        accepted.add(event);
      } catch (Exception parseFailure) {
        dropped++;
      }
    }

    if (!accepted.isEmpty()) {
      mlInteractionEventRepository.saveAll(accepted);
    }
    return new MlAuditIngestResponse(accepted.size(), dropped);
  }

  @Transactional(readOnly = true)
  public PageResponse<MlInteractionEventResponse> queryMlEvents(
      LocalDate fromDate,
      LocalDate toDate,
      String module,
      String action,
      AuditActionEventStatus status,
      Long actorUserId,
      String actorIdentifier,
      int page,
      int size) {
    Company company = companyContextService.requireCurrentCompany();
    int safeSize = Math.max(1, Math.min(size, 200));
    int safePage = Math.max(page, 0);

    Specification<MlInteractionEvent> spec =
        Specification.where(byCompanyMl(company.getId()))
            .and(byOccurredRangeMl(fromDate, toDate))
            .and(byEqualsMl("module", module))
            .and(byEqualsMl("action", action))
            .and(byStatusMl(status))
            .and(byActorMl(actorUserId))
            .and(byEqualsMl("actorIdentifier", actorIdentifier));

    Page<MlInteractionEvent> data =
        mlInteractionEventRepository.findAll(
            spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt")));

    List<MlInteractionEventResponse> content =
        data.getContent().stream()
            .map(
                event ->
                    new MlInteractionEventResponse(
                        event.getId(),
                        event.getOccurredAt(),
                        event.getModule(),
                        event.getAction(),
                        event.getInteractionType(),
                        event.getScreen(),
                        event.getTargetId(),
                        event.getStatus(),
                        event.getFailureReason(),
                        event.getCorrelationId(),
                        event.getRequestId(),
                        event.getTraceId(),
                        event.getActorUserId(),
                        event.getActorIdentifier(),
                        event.isActorAnonymized(),
                        event.isConsentOptIn(),
                        event.getTrainingSubjectKey(),
                        event.getPayload(),
                        event.getMetadata()))
            .toList();
    return PageResponse.of(content, data.getTotalElements(), safePage, safeSize);
  }

  private Optional<UserAccount> resolveCurrentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserPrincipal userPrincipal) {
      return Optional.ofNullable(userPrincipal.getUser());
    }
    return Optional.empty();
  }

  private void persistBusinessEvent(AuditActionEventCommand command, UserAccount actorSnapshot) {
    Company company = command.company();
    UserAccount actor = actorSnapshot != null ? actorSnapshot : command.actorUserOverride();

    AuditActionEvent event = new AuditActionEvent();
    event.setCompanyId(company.getId());
    event.setOccurredAt(command.occurredAt() != null ? command.occurredAt() : CompanyTime.now());
    event.setSource(command.source() != null ? command.source() : AuditActionEventSource.BACKEND);
    event.setModule(trim(command.module(), 64, "general"));
    event.setAction(trim(command.action(), 128, "unspecified"));
    event.setEntityType(trim(command.entityType(), 128, null));
    event.setEntityId(trim(command.entityId(), 128, null));
    event.setReferenceNumber(trim(command.referenceNumber(), 128, null));
    event.setStatus(command.status() != null ? command.status() : AuditActionEventStatus.INFO);
    event.setFailureReason(trim(command.failureReason(), 512, null));
    event.setAmount(command.amount());
    event.setCurrency(trim(command.currency(), 16, null));
    event.setCorrelationId(
        command.correlationId() != null ? command.correlationId() : java.util.UUID.randomUUID());
    event.setRequestId(trim(command.requestId(), 128, null));
    event.setTraceId(trim(command.traceId(), 128, null));
    event.setIpAddress(trim(command.ipAddress(), 64, null));
    event.setUserAgent(trim(command.userAgent(), MAX_METADATA_VALUE_LENGTH, null));
    event.setActorUserId(actor != null ? actor.getId() : null);
    event.setActorIdentifier(resolveActorIdentifier(actor));
    event.setActorAnonymized(false);
    event.setMlEligible(Boolean.TRUE.equals(command.mlEligible()));
    if (event.isMlEligible()) {
      event.setTrainingSubjectKey(
          actor != null && actor.getId() != null ? "u:" + actor.getId() : "system");
      event.setTrainingPayload(trim(command.trainingPayload(), MAX_PAYLOAD_LENGTH, null));
    }
    event.setMetadata(sanitizeMetadata(command.metadata()));
    auditActionEventRepository.save(event);
  }

  private void persistBusinessEvent(BusinessEventRetryPayload payload) {
    AuditActionEvent event = new AuditActionEvent();
    event.setCompanyId(payload.companyId());
    event.setOccurredAt(
        payload.occurredAtEpochMillis() != null
            ? Instant.ofEpochMilli(payload.occurredAtEpochMillis())
            : CompanyTime.now());
    event.setSource(payload.source() != null ? payload.source() : AuditActionEventSource.BACKEND);
    event.setModule(trim(payload.module(), 64, "general"));
    event.setAction(trim(payload.action(), 128, "unspecified"));
    event.setEntityType(trim(payload.entityType(), 128, null));
    event.setEntityId(trim(payload.entityId(), 128, null));
    event.setReferenceNumber(trim(payload.referenceNumber(), 128, null));
    event.setStatus(payload.status() != null ? payload.status() : AuditActionEventStatus.INFO);
    event.setFailureReason(trim(payload.failureReason(), 512, null));
    event.setAmount(payload.amount());
    event.setCurrency(trim(payload.currency(), 16, null));
    event.setCorrelationId(
        payload.correlationId() != null ? payload.correlationId() : java.util.UUID.randomUUID());
    event.setRequestId(trim(payload.requestId(), 128, null));
    event.setTraceId(trim(payload.traceId(), 128, null));
    event.setIpAddress(trim(payload.ipAddress(), 64, null));
    event.setUserAgent(trim(payload.userAgent(), MAX_METADATA_VALUE_LENGTH, null));
    event.setActorUserId(payload.actorUserId());
    event.setActorIdentifier(trim(payload.actorIdentifier(), MAX_IDENTIFIER_LENGTH, "system"));
    event.setActorAnonymized(false);
    event.setMlEligible(Boolean.TRUE.equals(payload.mlEligible()));
    if (event.isMlEligible()) {
      event.setTrainingSubjectKey(
          payload.actorUserId() != null ? "u:" + payload.actorUserId() : "system");
      event.setTrainingPayload(trim(payload.trainingPayload(), MAX_PAYLOAD_LENGTH, null));
    }
    event.setMetadata(sanitizeMetadata(payload.metadata()));
    auditActionEventRepository.save(event);
  }

  private int retryPersistedBusinessEvents(int batchLimit) {
    if (batchLimit <= 0) {
      return 0;
    }
    List<AuditActionEventRetry> dueRetries =
        auditActionEventRetryRepository.lockDueRetries(CompanyTime.now(), batchLimit);
    int processed = 0;
    for (AuditActionEventRetry retry : dueRetries) {
      try {
        BusinessEventRetryPayload payload =
            objectMapper.readValue(retry.getPayload(), BusinessEventRetryPayload.class);
        persistBusinessEvent(payload);
        auditActionEventRetryRepository.delete(retry);
      } catch (Exception ex) {
        int nextFailedAttemptCount = retry.getFailedAttemptCount() + 1;
        if (nextFailedAttemptCount >= Math.max(1, businessEventRetryMaxAttempts)) {
          log.error(
              "Dropping persistent business audit retry after {} failed attempts (id={},"
                  + " companyId={}, nextAttemptAt={})",
              nextFailedAttemptCount,
              retry.getId(),
              retry.getCompanyId(),
              retry.getNextAttemptAt(),
              ex);
          auditActionEventRetryRepository.delete(retry);
        } else {
          retry.setFailedAttemptCount(nextFailedAttemptCount);
          retry.setNextAttemptAt(
              CompanyTime.now().plusMillis(Math.max(1L, businessEventRetryBackoffMs)));
          retry.setLastError(
              trim(ex.getMessage(), MAX_RETRY_ERROR_LENGTH, ex.getClass().getSimpleName()));
          auditActionEventRetryRepository.save(retry);
          log.warn(
              "Rescheduled persistent business audit retry {}/{} (id={}, companyId={},"
                  + " nextAttemptAt={})",
              nextFailedAttemptCount + 1,
              Math.max(1, businessEventRetryMaxAttempts),
              retry.getId(),
              retry.getCompanyId(),
              retry.getNextAttemptAt(),
              ex);
        }
      }
      processed++;
    }
    return processed;
  }

  private void enqueueBusinessEventRetry(
      AuditActionEventCommand command,
      UserAccount actorSnapshot,
      int failedAttemptCount,
      Exception exception) {
    int maxAttempts = Math.max(1, businessEventRetryMaxAttempts);
    if (failedAttemptCount >= maxAttempts) {
      log.error(
          "Dropping business audit event after {} failed attempts", failedAttemptCount, exception);
      return;
    }

    if (persistBusinessEventRetry(command, actorSnapshot, failedAttemptCount, exception)) {
      return;
    }

    PendingBusinessEventRetry pending =
        new PendingBusinessEventRetry(command, actorSnapshot, failedAttemptCount);
    if (!offerBusinessEventRetry(pending)) {
      log.error(
          "Business audit retry queue full (size={}, max={}); dropping event",
          businessEventRetryQueueSize.get(),
          Math.max(1, businessEventRetryMaxQueueSize),
          exception);
      return;
    }

    log.warn(
        "Queued business audit event retry {}/{}", failedAttemptCount + 1, maxAttempts, exception);
  }

  private boolean persistBusinessEventRetry(
      AuditActionEventCommand command,
      UserAccount actorSnapshot,
      int failedAttemptCount,
      Exception exception) {
    try {
      BusinessEventRetryPayload payload = BusinessEventRetryPayload.from(command, actorSnapshot);
      AuditActionEventRetry retry = new AuditActionEventRetry();
      retry.setCompanyId(payload.companyId());
      retry.setPayload(objectMapper.writeValueAsString(payload));
      retry.setFailedAttemptCount(failedAttemptCount);
      retry.setNextAttemptAt(
          CompanyTime.now().plusMillis(Math.max(1L, businessEventRetryBackoffMs)));
      retry.setLastError(
          trim(
              exception.getMessage(),
              MAX_RETRY_ERROR_LENGTH,
              exception.getClass().getSimpleName()));
      auditActionEventRetryRepository.save(retry);
      log.warn(
          "Queued persistent business audit retry {}/{} (id={}, companyId={})",
          failedAttemptCount + 1,
          Math.max(1, businessEventRetryMaxAttempts),
          retry.getId(),
          retry.getCompanyId(),
          exception);
      return true;
    } catch (Exception persistenceEx) {
      log.error(
          "Failed to persist business audit retry; falling back to in-memory queue", persistenceEx);
      return false;
    }
  }

  private boolean offerBusinessEventRetry(PendingBusinessEventRetry pending) {
    int maxQueueSize = Math.max(1, businessEventRetryMaxQueueSize);
    int current = businessEventRetryQueueSize.incrementAndGet();
    if (current > maxQueueSize) {
      businessEventRetryQueueSize.decrementAndGet();
      return false;
    }
    boolean offered = businessEventRetryQueue.offerLast(pending);
    if (!offered) {
      businessEventRetryQueueSize.decrementAndGet();
    }
    return offered;
  }

  private PendingBusinessEventRetry pollBusinessEventRetry() {
    PendingBusinessEventRetry pending = businessEventRetryQueue.pollFirst();
    if (pending != null) {
      businessEventRetryQueueSize.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }
    return pending;
  }

  int pendingBusinessEventRetryQueueSize() {
    return businessEventRetryQueueSize.get();
  }

  private String resolveActorIdentifier(UserAccount actor) {
    if (actor == null) {
      return "system";
    }
    if (StringUtils.hasText(actor.getEmail())) {
      return trim(actor.getEmail().toLowerCase(), MAX_IDENTIFIER_LENGTH, "system");
    }
    if (actor.getId() != null) {
      return "user:" + actor.getId();
    }
    return "system";
  }

  private IdentitySnapshot resolveMlIdentity(UserAccount actor, Long companyId) {
    if (actor == null || actor.getId() == null) {
      return new IdentitySnapshot(
          null, "anonymous", true, false, "anon:" + anonymize(companyId, "anonymous"));
    }
    boolean consent = resolveAiPersonalizationOptIn(actor);
    if (consent) {
      String identifier =
          StringUtils.hasText(actor.getEmail())
              ? actor.getEmail().toLowerCase()
              : "user:" + actor.getId();
      return new IdentitySnapshot(actor.getId(), identifier, false, true, "u:" + actor.getId());
    }
    String anon = anonymize(companyId, actor.getId().toString());
    return new IdentitySnapshot(null, "anon:" + anon, true, false, "anon:" + anon);
  }

  private boolean resolveAiPersonalizationOptIn(UserAccount actor) {
    if (actor == null) {
      return false;
    }
    for (String methodName : List.of("isAiPersonalizationOptIn", "getAiPersonalizationOptIn")) {
      var method = ReflectionUtils.findMethod(UserAccount.class, methodName);
      if (method == null) {
        continue;
      }
      Object result = ReflectionUtils.invokeMethod(method, actor);
      if (result instanceof Boolean booleanResult) {
        return booleanResult;
      }
    }
    return false;
  }

  private String anonymize(Long companyId, String raw) {
    String normalized =
        (companyId != null ? companyId : 0L) + "|" + (raw != null ? raw : "unknown");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(auditPrivateKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 24);
    } catch (Exception ex) {
      return Integer.toHexString(normalized.hashCode());
    }
  }

  private String serializePayload(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    try {
      String json = objectMapper.writeValueAsString(payload);
      return trim(json, MAX_PAYLOAD_LENGTH, null);
    } catch (JsonProcessingException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Invalid training payload");
    }
  }

  private Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return new HashMap<>();
    }
    Map<String, String> sanitized = new HashMap<>();
    int count = 0;
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      if (!StringUtils.hasText(entry.getKey())) {
        continue;
      }
      sanitized.put(
          trim(entry.getKey(), 128, "key"), trim(entry.getValue(), MAX_METADATA_VALUE_LENGTH, ""));
      count++;
      if (count >= MAX_METADATA_ENTRIES) {
        break;
      }
    }
    return sanitized;
  }

  private String inferInteractionType(String action) {
    String normalized = action != null ? action.trim().toLowerCase() : "";
    if (normalized.contains("click")) {
      return "CLICK";
    }
    if (normalized.contains("view") || normalized.contains("open")) {
      return "VIEW";
    }
    if (normalized.contains("submit") || normalized.contains("save")) {
      return "SUBMIT";
    }
    if (normalized.contains("input") || normalized.contains("type")) {
      return "INPUT";
    }
    return "INTERACTION";
  }

  private String requestHeader(HttpServletRequest request, String name) {
    return request == null ? null : request.getHeader(name);
  }

  private String firstNonBlank(String first, String fallback) {
    return StringUtils.hasText(first) ? first : fallback;
  }

  private String getClientIpAddress(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String[] headers = {"X-Forwarded-For", "X-Real-IP"};
    for (String header : headers) {
      String ip = request.getHeader(header);
      if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
        int commaIndex = ip.indexOf(',');
        return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip;
      }
    }
    return request.getRemoteAddr();
  }

  private String trim(String value, int max, String fallback) {
    if (!StringUtils.hasText(value)) {
      return fallback;
    }
    String trimmed = value.trim();
    if (trimmed.length() <= max) {
      return trimmed;
    }
    return trimmed.substring(0, max);
  }

  private Specification<AuditActionEvent> byCompany(Long companyId) {
    return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
  }

  private Specification<AuditActionEvent> byOccurredRange(LocalDate from, LocalDate to) {
    return (root, query, cb) -> {
      if (from == null && to == null) {
        return cb.conjunction();
      }
      Instant start = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
      Instant end = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
      if (start != null && end != null) {
        return cb.and(
            cb.greaterThanOrEqualTo(root.get("occurredAt"), start),
            cb.lessThan(root.get("occurredAt"), end));
      }
      if (start != null) {
        return cb.greaterThanOrEqualTo(root.get("occurredAt"), start);
      }
      return cb.lessThan(root.get("occurredAt"), end);
    };
  }

  private Specification<AuditActionEvent> byEquals(String field, String value) {
    return (root, query, cb) ->
        StringUtils.hasText(value) ? cb.equal(root.get(field), value.trim()) : cb.conjunction();
  }

  private Specification<AuditActionEvent> byStatus(AuditActionEventStatus status) {
    return (root, query, cb) ->
        status != null ? cb.equal(root.get("status"), status) : cb.conjunction();
  }

  private Specification<AuditActionEvent> byActor(Long actorUserId) {
    return (root, query, cb) ->
        actorUserId != null ? cb.equal(root.get("actorUserId"), actorUserId) : cb.conjunction();
  }

  private Specification<MlInteractionEvent> byCompanyMl(Long companyId) {
    return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
  }

  private Specification<MlInteractionEvent> byOccurredRangeMl(LocalDate from, LocalDate to) {
    return (root, query, cb) -> {
      if (from == null && to == null) {
        return cb.conjunction();
      }
      Instant start = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
      Instant end = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
      if (start != null && end != null) {
        return cb.and(
            cb.greaterThanOrEqualTo(root.get("occurredAt"), start),
            cb.lessThan(root.get("occurredAt"), end));
      }
      if (start != null) {
        return cb.greaterThanOrEqualTo(root.get("occurredAt"), start);
      }
      return cb.lessThan(root.get("occurredAt"), end);
    };
  }

  private Specification<MlInteractionEvent> byEqualsMl(String field, String value) {
    return (root, query, cb) ->
        StringUtils.hasText(value) ? cb.equal(root.get(field), value.trim()) : cb.conjunction();
  }

  private Specification<MlInteractionEvent> byStatusMl(AuditActionEventStatus status) {
    return (root, query, cb) ->
        status != null ? cb.equal(root.get("status"), status) : cb.conjunction();
  }

  private Specification<MlInteractionEvent> byActorMl(Long actorUserId) {
    return (root, query, cb) ->
        actorUserId != null ? cb.equal(root.get("actorUserId"), actorUserId) : cb.conjunction();
  }

  private record BusinessEventRetryPayload(
      Long companyId,
      AuditActionEventSource source,
      String module,
      String action,
      String entityType,
      String entityId,
      String referenceNumber,
      AuditActionEventStatus status,
      String failureReason,
      java.math.BigDecimal amount,
      String currency,
      java.util.UUID correlationId,
      String requestId,
      String traceId,
      String ipAddress,
      String userAgent,
      Long actorUserId,
      String actorIdentifier,
      Boolean mlEligible,
      String trainingPayload,
      Map<String, String> metadata,
      Long occurredAtEpochMillis) {

    static BusinessEventRetryPayload from(
        AuditActionEventCommand command, UserAccount actorSnapshot) {
      UserAccount actor = actorSnapshot != null ? actorSnapshot : command.actorUserOverride();
      Long actorUserId = actor != null ? actor.getId() : null;
      String actorIdentifier;
      if (actor == null) {
        actorIdentifier = "system";
      } else if (StringUtils.hasText(actor.getEmail())) {
        actorIdentifier = actor.getEmail().toLowerCase();
      } else if (actor.getId() != null) {
        actorIdentifier = "user:" + actor.getId();
      } else {
        actorIdentifier = "system";
      }
      return new BusinessEventRetryPayload(
          command.company() != null ? command.company().getId() : null,
          command.source(),
          command.module(),
          command.action(),
          command.entityType(),
          command.entityId(),
          command.referenceNumber(),
          command.status(),
          command.failureReason(),
          command.amount(),
          command.currency(),
          command.correlationId(),
          command.requestId(),
          command.traceId(),
          command.ipAddress(),
          command.userAgent(),
          actorUserId,
          actorIdentifier,
          command.mlEligible(),
          command.trainingPayload(),
          command.metadata(),
          command.occurredAt() != null ? command.occurredAt().toEpochMilli() : null);
    }
  }

  private record IdentitySnapshot(
      Long actorUserId,
      String actorIdentifier,
      boolean actorAnonymized,
      boolean consentOptIn,
      String trainingSubjectKey) {}

  private record PendingBusinessEventRetry(
      AuditActionEventCommand command, UserAccount actorSnapshot, int failedAttemptCount) {}
}
