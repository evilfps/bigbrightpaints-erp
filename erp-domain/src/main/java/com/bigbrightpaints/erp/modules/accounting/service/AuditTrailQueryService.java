package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingAuditTrailEntryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class AuditTrailQueryService {

  private static final String ACCOUNTING_MODULE = "ACCOUNTING";
  private static final String BEFORE_STATE_KEY = "beforeState";
  private static final String AFTER_STATE_KEY = "afterState";
  private static final String SENSITIVE_OPERATION_KEY = "sensitiveOperation";

  private final CompanyContextService companyContextService;
  private final AuditActionEventRepository auditActionEventRepository;

  public AuditTrailQueryService(
      CompanyContextService companyContextService,
      AuditActionEventRepository auditActionEventRepository) {
    this.companyContextService = companyContextService;
    this.auditActionEventRepository = auditActionEventRepository;
  }

  public PageResponse<AccountingAuditTrailEntryDto> queryAuditTrail(
      LocalDate from,
      LocalDate to,
      String user,
      String actionType,
      String entityType,
      int page,
      int size) {
    Company company = companyContextService.requireCurrentCompany();
    int safePage = Math.max(page, 0);
    int safeSize = Math.max(1, Math.min(size, 200));
    Specification<AuditActionEvent> spec =
        Specification.where(byCompany(company.getId()))
            .and(byOccurredRange(from, to))
            .and(byExactIgnoreCase("module", ACCOUNTING_MODULE))
            .and(byActor(user))
            .and(byExactIgnoreCase("action", actionType))
            .and(byExactIgnoreCase("entityType", entityType));
    PageRequest pageRequest =
        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
    Page<AuditActionEvent> resultPage = auditActionEventRepository.findAll(spec, pageRequest);
    List<AccountingAuditTrailEntryDto> content =
        resultPage.getContent().stream()
            .map(event -> toDto(event, company.getCode()))
            .toList();

    return PageResponse.of(content, resultPage.getTotalElements(), safePage, safeSize);
  }

  private AccountingAuditTrailEntryDto toDto(AuditActionEvent event, String companyCode) {
    Map<String, String> metadata =
        event.getMetadata() == null ? Map.of() : Map.copyOf(event.getMetadata());
    return new AccountingAuditTrailEntryDto(
        event.getId(),
        event.getOccurredAt(),
        event.getCompanyId(),
        companyCode,
        event.getActorUserId(),
        event.getActorIdentifier(),
        event.getAction(),
        event.getEntityType(),
        event.getEntityId(),
        event.getReferenceNumber(),
        event.getTraceId(),
        event.getIpAddress(),
        metadata.get(BEFORE_STATE_KEY),
        metadata.get(AFTER_STATE_KEY),
        Boolean.parseBoolean(metadata.getOrDefault(SENSITIVE_OPERATION_KEY, "false")),
        metadata);
  }

  private Specification<AuditActionEvent> byExactIgnoreCase(String fieldName, String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
    return (root, query, cb) ->
        cb.equal(cb.lower(root.get(fieldName).as(String.class)), normalizedValue);
  }

  private Specification<AuditActionEvent> byActor(String user) {
    if (!StringUtils.hasText(user)) {
      return null;
    }
    String normalizedUser = user.trim().toLowerCase(Locale.ROOT);
    Long actorUserId = parseLongFilter(normalizedUser);
    return (root, query, cb) -> {
      if (actorUserId == null) {
        return cb.equal(cb.lower(root.get("actorIdentifier").as(String.class)), normalizedUser);
      }
      return cb.or(
          cb.equal(cb.lower(root.get("actorIdentifier").as(String.class)), normalizedUser),
          cb.equal(root.get("actorUserId"), actorUserId));
    };
  }

  private Long parseLongFilter(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException ex) {
      return null;
    }
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
}
