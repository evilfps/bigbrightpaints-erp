package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Component
public class AuditLogReadAdapter {

  private static final List<String> ENTITY_ID_METADATA_KEYS =
      List.of(
          "resourceId",
          "entityId",
          "journalEntryId",
          "journalReference",
          "reference",
          "orderNumber");
  private static final List<String> REFERENCE_METADATA_KEYS =
      List.of("referenceNumber", "journalReference", "reference", "orderNumber");
  private static final List<String> REFERENCE_FILTER_METADATA_KEYS =
      List.of(
          "resourceId",
          "entityId",
          "referenceNumber",
          "journalEntryId",
          "journalReference",
          "reference",
          "orderNumber");
  private static final String ACCOUNTING_EVENT_TRAIL_OPERATION_KEY = "eventTrailOperation";
  private static final String NO_MODULE_MATCH = "__NO_MATCH__";

  private final AuditLogRepository auditLogRepository;
  private final AuditEventClassifier auditEventClassifier;
  private final AuditVisibilityPolicy auditVisibilityPolicy;

  public AuditLogReadAdapter(
      AuditLogRepository auditLogRepository,
      AuditEventClassifier auditEventClassifier,
      AuditVisibilityPolicy auditVisibilityPolicy) {
    this.auditLogRepository = auditLogRepository;
    this.auditEventClassifier = auditEventClassifier;
    this.auditVisibilityPolicy = auditVisibilityPolicy;
  }

  @Transactional(readOnly = true)
  public AuditFeedSlice queryTenantCompanyFeed(Company company, AuditFeedFilter filter) {
    return queryCompanyFeed(company, filter, filter.normalizedModule());
  }

  @Transactional(readOnly = true)
  public AuditFeedSlice queryAccountingFeed(Company company, AuditFeedFilter filter) {
    return queryCompanyFeed(company, filter, enforcedAccountingModule(filter.normalizedModule()));
  }

  private AuditFeedSlice queryCompanyFeed(Company company, AuditFeedFilter filter, String module) {
    int fetchLimit = filter.fetchLimit();
    Specification<AuditLog> spec =
        Specification.where(auditVisibilityPolicy.tenantCompanyVisibility(company.getId()))
            .and(byOccurredRange(filter.from(), filter.to()))
            .and(byModule(module))
            .and(byActor(filter.normalizedActor()))
            .and(byStatus(filter.normalizedStatus()))
            .and(byAction(filter.normalizedAction()))
            .and(byEntityType(filter.normalizedEntityType()))
            .and(byReference(filter.normalizedReference()));
    Page<AuditLog> page = auditLogRepository.findAll(spec, firstPage(fetchLimit));
    return new AuditFeedSlice(
        page.getContent().stream().map(log -> toDto(log, company.getCode(), Map.of())).toList(),
        page.getTotalElements());
  }

  @Transactional(readOnly = true)
  public AuditFeedSlice queryPlatformFeed(AuditFeedFilter filter) {
    int safePage = filter.safePage();
    int safeSize = filter.safeSize();
    Specification<AuditLog> spec =
        Specification.where(auditVisibilityPolicy.platformVisibility())
            .and(byOccurredRange(filter.from(), filter.to()))
            .and(byModule(filter.normalizedModule()))
            .and(byActor(filter.normalizedActor()))
            .and(byStatus(filter.normalizedStatus()))
            .and(byAction(filter.normalizedAction()))
            .and(byEntityType(filter.normalizedEntityType()))
            .and(byReference(filter.normalizedReference()));
    Page<AuditLog> page =
        auditLogRepository.findAll(spec, PageRequest.of(safePage, safeSize, sort()));
    List<AuditLog> logs = page.getContent();
    Map<Long, String> companyCodes = resolveFallbackCompanyCodes(logs);
    return new AuditFeedSlice(
        logs.stream().map(log -> toDto(log, null, companyCodes)).toList(), page.getTotalElements());
  }

  private AuditFeedItemDto toDto(
      AuditLog log, String currentCompanyCode, Map<Long, String> fallbackCompanyCodes) {
    Map<String, String> metadata = metadata(log);
    String fallbackCompanyCode =
        log.getCompanyId() == null ? null : fallbackCompanyCodes.get(log.getCompanyId());
    String companyCode =
        currentCompanyCode != null
            ? currentCompanyCode
            : firstNonBlank(metadata.get("targetCompanyCode"), fallbackCompanyCode);
    String entityType = entityTypeFor(log, metadata);
    String entityId = entityIdFor(log, metadata);
    return new AuditFeedItemDto(
        log.getId(),
        "AUDIT_LOG",
        auditEventClassifier.categoryFor(log),
        log.getTimestamp() != null
            ? log.getTimestamp().atZone(java.time.ZoneOffset.UTC).toInstant()
            : null,
        log.getCompanyId(),
        companyCode,
        auditEventClassifier.moduleFor(log),
        log.getEventType() != null ? log.getEventType().name() : null,
        log.getStatus() != null ? log.getStatus().name() : null,
        parseLong(firstNonBlank(metadata.get("actorUserId"), log.getUserId())),
        firstNonBlank(log.getUsername(), log.getUserId()),
        auditEventClassifier.subjectUserId(metadata),
        auditEventClassifier.subjectIdentifier(metadata),
        entityType,
        entityId,
        referenceNumberFor(entityId, metadata),
        log.getRequestMethod(),
        log.getRequestPath(),
        log.getTraceId(),
        metadata);
  }

  String entityTypeFor(AuditLog log) {
    return entityTypeFor(log, metadata(log));
  }

  String entityIdFor(AuditLog log) {
    return entityIdFor(log, metadata(log));
  }

  String referenceNumberFor(AuditLog log) {
    Map<String, String> metadata = metadata(log);
    return referenceNumberFor(entityIdFor(log, metadata), metadata);
  }

  private String entityTypeFor(AuditLog log, Map<String, String> metadata) {
    return firstNonBlank(
        log.getResourceType(), metadata.get("resourceType"), metadata.get("entityType"));
  }

  private String entityIdFor(AuditLog log, Map<String, String> metadata) {
    return firstNonBlank(
        log.getResourceId(), firstMetadataValue(metadata, ENTITY_ID_METADATA_KEYS));
  }

  private String referenceNumberFor(String entityId, Map<String, String> metadata) {
    return firstNonBlank(firstMetadataValue(metadata, REFERENCE_METADATA_KEYS), entityId);
  }

  private Map<Long, String> resolveFallbackCompanyCodes(List<AuditLog> logs) {
    Set<Long> companyIds =
        logs.stream()
            .filter(log -> !StringUtils.hasText(metadata(log).get("targetCompanyCode")))
            .map(AuditLog::getCompanyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (companyIds.isEmpty()) {
      return Map.of();
    }
    return auditVisibilityPolicy.resolveCompanyCodes(companyIds);
  }

  private PageRequest firstPage(int fetchLimit) {
    return PageRequest.of(0, fetchLimit, sort());
  }

  private Sort sort() {
    return Sort.by(Sort.Direction.DESC, "timestamp", "id");
  }

  private Specification<AuditLog> byOccurredRange(LocalDate from, LocalDate to) {
    return (root, query, cb) -> {
      if (from == null && to == null) {
        return cb.conjunction();
      }
      LocalDateTime start = from != null ? from.atStartOfDay() : null;
      LocalDateTime end = to != null ? to.plusDays(1).atStartOfDay() : null;
      if (start != null && end != null) {
        return cb.and(
            cb.greaterThanOrEqualTo(root.get("timestamp"), start),
            cb.lessThan(root.get("timestamp"), end));
      }
      if (start != null) {
        return cb.greaterThanOrEqualTo(root.get("timestamp"), start);
      }
      return cb.lessThan(root.get("timestamp"), end);
    };
  }

  private Specification<AuditLog> byModule(String module) {
    if (!StringUtils.hasText(module)) {
      return null;
    }
    String normalizedModule = module.trim().toUpperCase(Locale.ROOT);
    return (root, query, cb) -> {
      Predicate knownPath = knownModulePathPredicate(root.get("requestPath"), cb);
      Predicate pathMatch = modulePathPredicate(root.get("requestPath"), cb, normalizedModule);
      Predicate resourceTypePresent =
          cb.or(
              hasText(root.get("resourceType"), cb),
              metadataValueHasText(root, query, cb, "resourceType"));
      Predicate resourceTypeMatch =
          cb.or(
              equalsIgnoreCase(root.get("resourceType"), normalizedModule, cb),
              metadataValueEquals(root, query, cb, "resourceType", normalizedModule));
      Predicate categoryMatch = categoryPredicate(root.get("eventType"), cb, normalizedModule);
      Predicate accountingFailureMatch = accountingFailurePredicate(root, query, cb);

      ArrayList<Predicate> matches = new ArrayList<>();
      if (pathMatch != null) {
        matches.add(pathMatch);
      }
      matches.add(cb.and(cb.not(knownPath), resourceTypeMatch));
      if ("ACCOUNTING".equals(normalizedModule)) {
        Predicate accountingFallbackMatch = accountingFailureMatch;
        if (categoryMatch != null) {
          accountingFallbackMatch = cb.or(categoryMatch, accountingFailureMatch);
        }
        matches.add(
            cb.and(cb.not(knownPath), cb.not(resourceTypePresent), accountingFallbackMatch));
      } else if (categoryMatch != null) {
        Predicate fallbackCategoryMatch = categoryMatch;
        if ("SYSTEM".equals(normalizedModule)) {
          fallbackCategoryMatch = cb.and(categoryMatch, cb.not(accountingFailureMatch));
        }
        matches.add(cb.and(cb.not(knownPath), cb.not(resourceTypePresent), fallbackCategoryMatch));
      }
      return cb.or(matches.toArray(Predicate[]::new));
    };
  }

  private Specification<AuditLog> byActor(String actor) {
    if (!StringUtils.hasText(actor)) {
      return null;
    }
    String normalizedActor = actor.trim().toLowerCase(java.util.Locale.ROOT);
    return (root, query, cb) ->
        cb.or(
            cb.equal(cb.lower(root.get("username").as(String.class)), normalizedActor),
            cb.equal(cb.lower(root.get("userId").as(String.class)), normalizedActor));
  }

  private Specification<AuditLog> byStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    String normalizedStatus = status.trim().toUpperCase(java.util.Locale.ROOT);
    return (root, query, cb) -> cb.equal(root.get("status").as(String.class), normalizedStatus);
  }

  private Specification<AuditLog> byAction(String action) {
    if (!StringUtils.hasText(action)) {
      return null;
    }
    String normalizedAction = action.trim().toUpperCase(java.util.Locale.ROOT);
    return (root, query, cb) -> cb.equal(root.get("eventType").as(String.class), normalizedAction);
  }

  private Specification<AuditLog> byEntityType(String entityType) {
    if (!StringUtils.hasText(entityType)) {
      return null;
    }
    String normalizedEntityType = entityType.trim().toLowerCase(java.util.Locale.ROOT);
    return (root, query, cb) ->
        cb.or(
            equalsIgnoreCase(root.get("resourceType"), normalizedEntityType, cb),
            metadataValueEquals(root, query, cb, "resourceType", normalizedEntityType),
            metadataValueEquals(root, query, cb, "entityType", normalizedEntityType));
  }

  private Specification<AuditLog> byReference(String reference) {
    if (!StringUtils.hasText(reference)) {
      return null;
    }
    String normalizedReference = reference.trim().toLowerCase(java.util.Locale.ROOT);
    return (root, query, cb) -> {
      ArrayList<Predicate> matches = new ArrayList<>();
      matches.add(equalsIgnoreCase(root.get("resourceId"), normalizedReference, cb));
      matches.add(equalsIgnoreCase(root.get("traceId"), normalizedReference, cb));
      for (String metadataKey : REFERENCE_FILTER_METADATA_KEYS) {
        matches.add(metadataValueEquals(root, query, cb, metadataKey, normalizedReference));
      }
      return cb.or(matches.toArray(Predicate[]::new));
    };
  }

  private Predicate equalsIgnoreCase(Path<String> path, String value, CriteriaBuilder cb) {
    return cb.equal(cb.lower(path.as(String.class)), value.toLowerCase(Locale.ROOT));
  }

  private Predicate hasText(Path<String> path, CriteriaBuilder cb) {
    return cb.and(cb.isNotNull(path), cb.notEqual(cb.trim(path), ""));
  }

  private Predicate modulePathPredicate(
      Path<String> path, CriteriaBuilder cb, String normalizedModule) {
    return switch (normalizedModule) {
      case "SUPERADMIN" -> pathEqualsOrStartsWith(path, cb, "/api/v1/superadmin");
      case "ADMIN" -> pathEqualsOrStartsWith(path, cb, "/api/v1/admin");
      case "ACCOUNTING" -> pathEqualsOrStartsWith(path, cb, "/api/v1/accounting");
      case "AUTH" -> pathEqualsOrStartsWith(path, cb, "/api/v1/auth");
      case "CHANGELOG" -> pathEqualsOrStartsWith(path, cb, "/api/v1/changelog");
      case "COMPANIES" -> pathEqualsOrStartsWith(path, cb, "/api/v1/companies");
      default -> null;
    };
  }

  private Predicate knownModulePathPredicate(Path<String> path, CriteriaBuilder cb) {
    return cb.or(
        pathEqualsOrStartsWith(path, cb, "/api/v1/superadmin"),
        pathEqualsOrStartsWith(path, cb, "/api/v1/admin"),
        pathEqualsOrStartsWith(path, cb, "/api/v1/accounting"),
        pathEqualsOrStartsWith(path, cb, "/api/v1/auth"),
        pathEqualsOrStartsWith(path, cb, "/api/v1/changelog"),
        pathEqualsOrStartsWith(path, cb, "/api/v1/companies"));
  }

  private Predicate categoryPredicate(
      Path<AuditEvent> eventTypePath, CriteriaBuilder cb, String normalizedModule) {
    Set<AuditEvent> categoryEvents = categoryEvents(normalizedModule);
    if (categoryEvents == null || categoryEvents.isEmpty()) {
      return null;
    }
    return eventTypePath.in(categoryEvents);
  }

  private Set<AuditEvent> categoryEvents(String normalizedModule) {
    return switch (normalizedModule) {
      case "AUTH" ->
          EnumSet.of(
              AuditEvent.LOGIN_SUCCESS,
              AuditEvent.LOGIN_FAILURE,
              AuditEvent.LOGOUT,
              AuditEvent.TOKEN_REFRESH,
              AuditEvent.TOKEN_REVOKED,
              AuditEvent.PASSWORD_CHANGED,
              AuditEvent.PASSWORD_RESET_REQUESTED,
              AuditEvent.PASSWORD_RESET_COMPLETED,
              AuditEvent.MFA_ENROLLED,
              AuditEvent.MFA_ACTIVATED,
              AuditEvent.MFA_DISABLED,
              AuditEvent.MFA_SUCCESS,
              AuditEvent.MFA_FAILURE,
              AuditEvent.MFA_RECOVERY_CODE_USED);

      case "SECURITY" ->
          EnumSet.of(
              AuditEvent.ACCESS_GRANTED, AuditEvent.ACCESS_DENIED, AuditEvent.SECURITY_ALERT);

      case "ADMIN" ->
          EnumSet.of(
              AuditEvent.USER_CREATED,
              AuditEvent.USER_UPDATED,
              AuditEvent.USER_DELETED,
              AuditEvent.USER_ACTIVATED,
              AuditEvent.USER_DEACTIVATED,
              AuditEvent.USER_LOCKED,
              AuditEvent.USER_UNLOCKED,
              AuditEvent.PERMISSION_CHANGED,
              AuditEvent.ROLE_ASSIGNED,
              AuditEvent.ROLE_REMOVED,
              AuditEvent.CONFIGURATION_CHANGED);

      case "ACCOUNTING" -> auditEventClassifier.accountingEventTypes();

      case "DATA" ->
          EnumSet.of(
              AuditEvent.DATA_CREATE,
              AuditEvent.DATA_READ,
              AuditEvent.DATA_UPDATE,
              AuditEvent.DATA_DELETE,
              AuditEvent.DATA_EXPORT,
              AuditEvent.SENSITIVE_DATA_ACCESSED);

      case "COMPLIANCE" ->
          EnumSet.of(
              AuditEvent.AUDIT_LOG_ACCESSED,
              AuditEvent.AUDIT_LOG_EXPORTED,
              AuditEvent.COMPLIANCE_CHECK,
              AuditEvent.DATA_RETENTION_ACTION);

      case "SYSTEM" ->
          EnumSet.of(
              AuditEvent.SYSTEM_STARTUP,
              AuditEvent.SYSTEM_SHUTDOWN,
              AuditEvent.INTEGRATION_SUCCESS,
              AuditEvent.INTEGRATION_FAILURE);

      case "BUSINESS" ->
          EnumSet.of(
              AuditEvent.REFERENCE_GENERATED,
              AuditEvent.ORDER_NUMBER_GENERATED,
              AuditEvent.DISPATCH_CONFIRMED);
      default -> null;
    };
  }

  private Predicate accountingFailurePredicate(
      Root<AuditLog> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
    return cb.and(
        cb.equal(root.get("eventType"), AuditEvent.INTEGRATION_FAILURE),
        metadataValueHasText(root, query, cb, ACCOUNTING_EVENT_TRAIL_OPERATION_KEY));
  }

  private Predicate metadataValueEquals(
      Root<AuditLog> root, CriteriaQuery<?> query, CriteriaBuilder cb, String key, String value) {
    var subquery = query.subquery(Integer.class);
    Root<AuditLog> correlatedRoot = subquery.correlate(root);
    MapJoin<AuditLog, String, String> metadata = correlatedRoot.joinMap("metadata");
    subquery
        .select(cb.literal(1))
        .where(
            cb.equal(metadata.key(), key),
            cb.equal(cb.lower(metadata.value()), value.toLowerCase(Locale.ROOT)));
    return cb.exists(subquery);
  }

  private Predicate metadataValueHasText(
      Root<AuditLog> root, CriteriaQuery<?> query, CriteriaBuilder cb, String key) {
    var subquery = query.subquery(Integer.class);
    Root<AuditLog> correlatedRoot = subquery.correlate(root);
    MapJoin<AuditLog, String, String> metadata = correlatedRoot.joinMap("metadata");
    subquery
        .select(cb.literal(1))
        .where(
            cb.equal(metadata.key(), key),
            cb.isNotNull(metadata.value()),
            cb.notEqual(cb.trim(metadata.value()), ""));
    return cb.exists(subquery);
  }

  private Predicate pathEqualsOrStartsWith(Path<String> path, CriteriaBuilder cb, String prefix) {
    return cb.or(cb.equal(path, prefix), cb.like(path, prefix + "/%"));
  }

  private Map<String, String> metadata(AuditLog log) {
    return log.getMetadata() == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(log.getMetadata()));
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String firstMetadataValue(Map<String, String> metadata, List<String> keys) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    for (String key : keys) {
      String value = metadata.get(key);
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String enforcedAccountingModule(String requestedModule) {
    if (requestedModule == null) {
      return "ACCOUNTING";
    }
    return auditVisibilityPolicy.isAccountingModule(requestedModule)
        ? requestedModule
        : NO_MODULE_MATCH;
  }

  private Long parseLong(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
