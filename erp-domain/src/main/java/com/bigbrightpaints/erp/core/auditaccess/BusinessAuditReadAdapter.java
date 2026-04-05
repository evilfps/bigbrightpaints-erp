package com.bigbrightpaints.erp.core.auditaccess;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Component
public class BusinessAuditReadAdapter {

  private static final String NO_MODULE_MATCH = "__NO_MATCH__";

  private final AuditActionEventRepository auditActionEventRepository;
  private final AuditEventClassifier auditEventClassifier;
  private final AuditVisibilityPolicy auditVisibilityPolicy;

  public BusinessAuditReadAdapter(
      AuditActionEventRepository auditActionEventRepository,
      AuditEventClassifier auditEventClassifier,
      AuditVisibilityPolicy auditVisibilityPolicy) {
    this.auditActionEventRepository = auditActionEventRepository;
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
    Page<AuditActionEvent> page =
        auditActionEventRepository.findAll(
            buildSpecification(company.getId(), filter, module),
            PageRequest.of(0, filter.fetchLimit(), sort()));
    return new AuditFeedSlice(
        page.getContent().stream().map(event -> toDto(event, company.getCode())).toList(),
        page.getTotalElements());
  }

  private Specification<AuditActionEvent> buildSpecification(
      Long companyId, AuditFeedFilter filter, String module) {
    return Specification.where(byCompany(companyId))
        .and(byOccurredRange(filter.from(), filter.to()))
        .and(byExactIgnoreCase("module", module))
        .and(byExactIgnoreCase("action", filter.normalizedAction()))
        .and(byExactIgnoreCase("entityType", filter.normalizedEntityType()))
        .and(byExactIgnoreCase("referenceNumber", filter.normalizedReference()))
        .and(byStatus(filter.normalizedStatus()))
        .and(byActor(filter.normalizedActor()));
  }

  private AuditFeedItemDto toDto(AuditActionEvent event, String companyCode) {
    Map<String, String> metadata =
        event.getMetadata() == null ? Map.of() : Map.copyOf(event.getMetadata());
    return new AuditFeedItemDto(
        event.getId(),
        "BUSINESS_EVENT",
        auditEventClassifier.categoryFor(event),
        event.getOccurredAt(),
        event.getCompanyId(),
        companyCode,
        auditEventClassifier.moduleFor(event),
        event.getAction(),
        event.getStatus() != null ? event.getStatus().name() : null,
        event.getActorUserId(),
        event.getActorIdentifier(),
        auditEventClassifier.subjectUserId(metadata),
        auditEventClassifier.subjectIdentifier(metadata),
        event.getEntityType(),
        event.getEntityId(),
        event.getReferenceNumber(),
        null,
        null,
        event.getTraceId(),
        metadata);
  }

  private Sort sort() {
    return Sort.by(Sort.Direction.DESC, "occurredAt", "id");
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

  private Specification<AuditActionEvent> byStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    String normalizedStatus = status.trim().toUpperCase(java.util.Locale.ROOT);
    return (root, query, cb) -> cb.equal(root.get("status").as(String.class), normalizedStatus);
  }

  private Specification<AuditActionEvent> byActor(String actor) {
    if (!StringUtils.hasText(actor)) {
      return null;
    }
    String normalizedActor = actor.trim().toLowerCase(java.util.Locale.ROOT);
    Long actorUserId = parseLong(normalizedActor);
    return (root, query, cb) -> {
      if (actorUserId == null) {
        return cb.equal(cb.lower(root.get("actorIdentifier").as(String.class)), normalizedActor);
      }
      return cb.or(
          cb.equal(cb.lower(root.get("actorIdentifier").as(String.class)), normalizedActor),
          cb.equal(root.get("actorUserId"), actorUserId));
    };
  }

  private Specification<AuditActionEvent> byExactIgnoreCase(String fieldName, String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalizedValue = value.trim().toLowerCase(java.util.Locale.ROOT);
    return (root, query, cb) ->
        cb.equal(cb.lower(root.get(fieldName).as(String.class)), normalizedValue);
  }

  private Long parseLong(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String enforcedAccountingModule(String requestedModule) {
    if (requestedModule == null) {
      return "ACCOUNTING";
    }
    return auditVisibilityPolicy.isAccountingModule(requestedModule)
        ? requestedModule
        : NO_MODULE_MATCH;
  }
}
