package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Component
public class AuditLogReadAdapter {

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
    int fetchLimit = filter.fetchLimit();
    Specification<AuditLog> spec =
        Specification.where(auditVisibilityPolicy.tenantCompanyVisibility(company.getId()))
            .and(byOccurredRange(filter.from(), filter.to()))
            .and(byActor(filter.normalizedActor()))
            .and(byStatus(filter.normalizedStatus()))
            .and(byAction(filter.normalizedAction()))
            .and(byEntityType(filter.normalizedEntityType()))
            .and(byReference(filter.normalizedReference()));
    Page<AuditLog> page = auditLogRepository.findAll(spec, firstPage(fetchLimit));
    return new AuditFeedSlice(
        page.getContent().stream().map(log -> toDto(log, company.getCode())).toList(),
        page.getTotalElements());
  }

  @Transactional(readOnly = true)
  public AuditFeedSlice queryPlatformFeed(AuditFeedFilter filter) {
    int safePage = filter.safePage();
    int safeSize = filter.safeSize();
    Specification<AuditLog> spec =
        Specification.where(auditVisibilityPolicy.platformVisibility())
            .and(byOccurredRange(filter.from(), filter.to()))
            .and(byActor(filter.normalizedActor()))
            .and(byStatus(filter.normalizedStatus()))
            .and(byAction(filter.normalizedAction()))
            .and(byEntityType(filter.normalizedEntityType()))
            .and(byReference(filter.normalizedReference()));
    Page<AuditLog> page = auditLogRepository.findAll(spec, PageRequest.of(safePage, safeSize, sort()));
    return new AuditFeedSlice(page.getContent().stream().map(log -> toDto(log, null)).toList(), page.getTotalElements());
  }

  private AuditFeedItemDto toDto(AuditLog log, String currentCompanyCode) {
    Map<String, String> metadata = log.getMetadata() == null ? Map.of() : Map.copyOf(log.getMetadata());
    String companyCode =
        currentCompanyCode != null
            ? currentCompanyCode
            : firstNonBlank(
                metadata.get("targetCompanyCode"),
                auditVisibilityPolicy.resolveCompanyCode(log.getCompanyId()));
    String entityType = firstNonBlank(log.getResourceType(), metadata.get("entityType"));
    String entityId = firstNonBlank(log.getResourceId(), metadata.get("entityId"));
    return new AuditFeedItemDto(
        log.getId(),
        "AUDIT_LOG",
        auditEventClassifier.categoryFor(log),
        log.getTimestamp() != null ? log.getTimestamp().atZone(java.time.ZoneOffset.UTC).toInstant() : null,
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
        firstNonBlank(metadata.get("referenceNumber"), entityId),
        log.getRequestMethod(),
        log.getRequestPath(),
        log.getTraceId(),
        metadata);
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
        cb.equal(cb.lower(root.get("resourceType").as(String.class)), normalizedEntityType);
  }

  private Specification<AuditLog> byReference(String reference) {
    if (!StringUtils.hasText(reference)) {
      return null;
    }
    String normalizedReference = reference.trim().toLowerCase(java.util.Locale.ROOT);
    return (root, query, cb) ->
        cb.or(
            cb.equal(cb.lower(root.get("resourceId").as(String.class)), normalizedReference),
            cb.equal(cb.lower(root.get("traceId").as(String.class)), normalizedReference));
  }

  private String firstNonBlank(String primary, String fallback) {
    if (StringUtils.hasText(primary)) {
      return primary.trim();
    }
    return StringUtils.hasText(fallback) ? fallback.trim() : null;
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
