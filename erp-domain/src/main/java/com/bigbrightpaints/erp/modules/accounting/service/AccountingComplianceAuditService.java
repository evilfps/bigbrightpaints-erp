package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.AuditCorrelationIdResolver;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.TenantReviewIntelligenceToggleService;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class AccountingComplianceAuditService {

  private static final String MODULE_ACCOUNTING = "ACCOUNTING";
  private static final String ENTITY_JOURNAL = "JOURNAL_ENTRY";
  private static final String ENTITY_PERIOD = "ACCOUNTING_PERIOD";
  private static final String ENTITY_PERIOD_CLOSE_REQUEST = "PERIOD_CLOSE_REQUEST";
  private static final String ENTITY_ACCOUNT = "ACCOUNT";
  private static final String ACTION_REVIEW_INTELLIGENCE_WARNING = "REVIEW_INTELLIGENCE_WARNING";
  private static final String REVIEW_RULE_MANUAL_HIGH_VALUE_JOURNAL = "MANUAL_HIGH_VALUE_JOURNAL";
  private static final String REVIEW_QUEUE_ACCOUNTING_AUDIT_EVENTS = "ACCOUNTING_AUDIT_EVENTS";
  private static final BigDecimal REVIEW_WARNING_THRESHOLD = new BigDecimal("100000.00");
  private static final String METADATA_BEFORE_STATE = "beforeState";
  private static final String METADATA_AFTER_STATE = "afterState";
  private static final String METADATA_SENSITIVE_OPERATION = "sensitiveOperation";

  private final EnterpriseAuditTrailService enterpriseAuditTrailService;
  private final TenantReviewIntelligenceToggleService tenantReviewIntelligenceToggleService;
  private final ObjectMapper objectMapper;

  public AccountingComplianceAuditService(
      EnterpriseAuditTrailService enterpriseAuditTrailService,
      TenantReviewIntelligenceToggleService tenantReviewIntelligenceToggleService,
      ObjectMapper objectMapper) {
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
    this.tenantReviewIntelligenceToggleService = tenantReviewIntelligenceToggleService;
    this.objectMapper = objectMapper;
  }

  public void recordJournalCreation(Company company, JournalEntry entry) {
    recordJournalCreation(company, entry, Map.of());
  }

  public void recordJournalCreation(
      Company company, JournalEntry entry, Map<String, String> additionalMetadata) {
    if (company == null || entry == null) {
      return;
    }
    boolean manualJournal = entry.getJournalType() == JournalEntryType.MANUAL;
    String actionType;
    if (manualJournal) {
      actionType = "MANUAL_JOURNAL_CREATED";
    } else if (isSettlementReference(entry.getReferenceNumber())) {
      actionType = "SETTLEMENT_JOURNAL_CREATED";
    } else {
      actionType = "SYSTEM_JOURNAL_CREATED";
    }

    BigDecimal totalDebit =
        entry.getLines() == null
            ? BigDecimal.ZERO
            : entry.getLines().stream()
                .map(JournalLine::getDebit)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", "NOT_CREATED");

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("journalEntryId", entry.getId());
    afterState.put("referenceNumber", entry.getReferenceNumber());
    afterState.put("status", entry.getStatus());
    afterState.put(
        "journalType", entry.getJournalType() != null ? entry.getJournalType().name() : null);
    afterState.put("entryDate", entry.getEntryDate());
    afterState.put("postedAt", entry.getPostedAt());

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, manualJournal);
    metadata.put("journalSource", manualJournal ? "MANUAL" : "SYSTEM_GENERATED");
    if (StringUtils.hasText(entry.getSourceReference())) {
      metadata.put("sourceReference", entry.getSourceReference().trim());
    }
    if (StringUtils.hasText(entry.getAttachmentReferences())) {
      metadata.put("attachmentReferences", entry.getAttachmentReferences().trim());
    }
    mergeAdditionalMetadata(metadata, additionalMetadata);

    record(
        company,
        actionType,
        ENTITY_JOURNAL,
        stringifyId(entry.getId()),
        entry.getReferenceNumber(),
        totalDebit,
        entry.getCurrency(),
        metadata);
    recordReviewIntelligenceWarningIfEligible(company, entry, totalDebit, manualJournal);
  }

  public void recordJournalReversal(
      Company company, JournalEntry original, JournalEntry reversal, String reason) {
    if (company == null || original == null || reversal == null) {
      return;
    }
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", "POSTED");
    beforeState.put("reversalEntryId", null);

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("status", original.getStatus());
    afterState.put("reversalEntryId", reversal.getId());
    afterState.put("reversalReference", reversal.getReferenceNumber());
    afterState.put("reason", reason);

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, false);
    if (StringUtils.hasText(reason)) {
      metadata.put("reversalReason", reason.trim());
    }

    record(
        company,
        "JOURNAL_REVERSED",
        ENTITY_JOURNAL,
        stringifyId(original.getId()),
        original.getReferenceNumber(),
        null,
        original.getCurrency(),
        metadata);
  }

  public void recordPeriodTransition(
      Company company,
      AccountingPeriod period,
      String actionType,
      String beforeStatus,
      String afterStatus,
      String reason) {
    recordPeriodTransition(company, period, actionType, beforeStatus, afterStatus, reason, false);
  }

  public void recordPeriodTransition(
      Company company,
      AccountingPeriod period,
      String actionType,
      String beforeStatus,
      String afterStatus,
      String reason,
      boolean sensitiveOperation) {
    if (company == null || period == null || !StringUtils.hasText(actionType)) {
      return;
    }
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", beforeStatus);

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("status", afterStatus);
    afterState.put("periodLabel", period.getLabel());
    afterState.put(
        "costingMethod",
        period.getCostingMethod() != null ? period.getCostingMethod().name() : null);

    Map<String, String> metadata =
        baseMetadata(company, beforeState, afterState, sensitiveOperation);
    if (StringUtils.hasText(reason)) {
      metadata.put("reason", reason.trim());
    }

    record(
        company,
        actionType,
        ENTITY_PERIOD,
        stringifyId(period.getId()),
        period.getLabel(),
        null,
        company.getBaseCurrency(),
        metadata);
  }

  public void recordCostingMethodChange(
      Company company,
      AccountingPeriod period,
      CostingMethod beforeMethod,
      CostingMethod afterMethod) {
    if (company == null || period == null) {
      return;
    }

    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("costingMethod", beforeMethod != null ? beforeMethod.name() : null);

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("costingMethod", afterMethod != null ? afterMethod.name() : null);
    afterState.put("periodLabel", period.getLabel());

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, true);
    metadata.put("sensitiveCategory", "COSTING_METHOD_CHANGE");

    record(
        company,
        "COSTING_METHOD_CHANGED",
        ENTITY_PERIOD,
        stringifyId(period.getId()),
        period.getLabel(),
        null,
        company.getBaseCurrency(),
        metadata);
  }

  public void recordPeriodCloseRequestLifecycle(
      Company company,
      PeriodCloseRequest request,
      PeriodCloseRequestStatus beforeStatus,
      PeriodCloseRequestStatus afterStatus,
      String actionType,
      String reasonCode,
      String actor,
      String note,
      boolean sensitiveOperation) {
    if (company == null || request == null || !StringUtils.hasText(actionType)) {
      return;
    }

    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", beforeStatus != null ? beforeStatus.name() : null);

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("status", afterStatus != null ? afterStatus.name() : null);
    afterState.put(
        "periodId",
        request.getAccountingPeriod() != null ? request.getAccountingPeriod().getId() : null);
    afterState.put(
        "periodLabel",
        request.getAccountingPeriod() != null ? request.getAccountingPeriod().getLabel() : null);
    afterState.put("forceRequested", request.isForceRequested());

    Map<String, String> metadata =
        baseMetadata(company, beforeState, afterState, sensitiveOperation);
    if (StringUtils.hasText(reasonCode)) {
      metadata.put("reasonCode", reasonCode.trim());
    }
    if (StringUtils.hasText(actor)) {
      metadata.put("actor", actor.trim());
    }
    if (StringUtils.hasText(note)) {
      metadata.put("note", note.trim());
    }
    if (StringUtils.hasText(request.getRequestedBy())) {
      metadata.put("requestedBy", request.getRequestedBy().trim());
    }
    if (StringUtils.hasText(request.getReviewedBy())) {
      metadata.put("reviewedBy", request.getReviewedBy().trim());
    }
    if (request.getRequestedAt() != null) {
      metadata.put("requestedAt", request.getRequestedAt().toString());
    }
    if (request.getReviewedAt() != null) {
      metadata.put("reviewedAt", request.getReviewedAt().toString());
    }

    record(
        company,
        actionType,
        ENTITY_PERIOD_CLOSE_REQUEST,
        stringifyId(request.getId()),
        periodCloseRequestReference(request),
        null,
        company.getBaseCurrency(),
        metadata);
  }

  public void recordAdminOverrideJournalReversal(
      Company company,
      JournalEntry original,
      JournalEntry reversal,
      String actor,
      String reason,
      boolean overrideRequested,
      boolean overrideAuthorized) {
    if (company == null || original == null || reversal == null || !overrideRequested) {
      return;
    }
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", "POSTED");

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("status", original.getStatus());
    afterState.put("reversalEntryId", reversal.getId());
    afterState.put("reversalReference", reversal.getReferenceNumber());

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, true);
    metadata.put("adminOverrideRequested", Boolean.toString(overrideRequested));
    metadata.put("adminOverrideAuthorized", Boolean.toString(overrideAuthorized));
    if (StringUtils.hasText(actor)) {
      metadata.put("actor", actor.trim());
    }
    if (StringUtils.hasText(reason)) {
      metadata.put("reason", reason.trim());
    }

    record(
        company,
        "JOURNAL_REVERSAL_ADMIN_OVERRIDE",
        ENTITY_JOURNAL,
        stringifyId(original.getId()),
        original.getReferenceNumber(),
        null,
        original.getCurrency(),
        metadata);
  }

  public void recordAccountCreated(Company company, Account account) {
    if (company == null || account == null) {
      return;
    }
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("status", "NOT_CREATED");

    Map<String, Object> afterState = accountState(account);

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, false);
    record(
        company,
        "ACCOUNT_CREATED",
        ENTITY_ACCOUNT,
        stringifyId(account.getId()),
        account.getCode(),
        null,
        company.getBaseCurrency(),
        metadata);
  }

  public void recordAccountDeactivated(Company company, Account account, String reason) {
    if (company == null || account == null) {
      return;
    }
    Map<String, Object> beforeState = accountState(account);
    beforeState.put("active", true);

    Map<String, Object> afterState = accountState(account);
    afterState.put("active", false);

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, true);
    metadata.put("sensitiveCategory", "ACCOUNT_DEACTIVATION");
    if (StringUtils.hasText(reason)) {
      metadata.put("reason", reason.trim());
    }

    record(
        company,
        "ACCOUNT_DEACTIVATED",
        ENTITY_ACCOUNT,
        stringifyId(account.getId()),
        account.getCode(),
        null,
        company.getBaseCurrency(),
        metadata);
  }

  private void record(
      Company company,
      String actionType,
      String entityType,
      String entityId,
      String referenceNumber,
      BigDecimal amount,
      String currency,
      Map<String, String> metadata) {
    HttpServletRequest request = currentRequest();
    Map<String, String> safeMetadata = metadata != null ? metadata : Map.of();
    UUID correlationId =
        AuditCorrelationIdResolver.resolveCorrelationId(
            request,
            referenceNumber,
            safeMetadata.get("sourceReference"),
            safeMetadata.get("journalSource"),
            entityType,
            entityId);
    enterpriseAuditTrailService.recordBusinessEvent(
        new AuditActionEventCommand(
            company,
            AuditActionEventSource.BACKEND,
            MODULE_ACCOUNTING,
            actionType,
            entityType,
            entityId,
            referenceNumber,
            AuditActionEventStatus.SUCCESS,
            null,
            amount,
            currency,
            correlationId,
            header(request, "X-Request-Id"),
            resolveTraceId(request),
            resolveClientIp(request),
            header(request, "User-Agent"),
            null,
            false,
            null,
            safeMetadata,
            CompanyTime.now(company)));
  }

  private Map<String, String> baseMetadata(
      Company company,
      Map<String, Object> beforeState,
      Map<String, Object> afterState,
      boolean sensitiveOperation) {
    Map<String, String> metadata = new LinkedHashMap<>();
    if (company != null && StringUtils.hasText(company.getCode())) {
      metadata.put("companyCode", company.getCode());
    }
    metadata.put(METADATA_BEFORE_STATE, toJson(beforeState));
    metadata.put(METADATA_AFTER_STATE, toJson(afterState));
    metadata.put(METADATA_SENSITIVE_OPERATION, Boolean.toString(sensitiveOperation));
    return metadata;
  }

  private Map<String, Object> accountState(Account account) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("accountId", account.getId());
    state.put("code", account.getCode());
    state.put("name", account.getName());
    state.put("type", account.getType() != null ? account.getType().name() : null);
    state.put("active", account.isActive());
    return state;
  }

  private boolean isSettlementReference(String referenceNumber) {
    if (!StringUtils.hasText(referenceNumber)) {
      return false;
    }
    String normalized = referenceNumber.trim().toUpperCase(Locale.ROOT);
    return normalized.startsWith("SET")
        || normalized.startsWith("RCPT")
        || normalized.startsWith("SUP")
        || normalized.contains("SETTLEMENT");
  }

  private void recordReviewIntelligenceWarningIfEligible(
      Company company, JournalEntry entry, BigDecimal totalDebit, boolean manualJournal) {
    if (!qualifiesForReviewWarning(manualJournal, totalDebit)
        || !tenantReviewIntelligenceToggleService.isEnabledForCompany(
            company == null ? null : company.getId())) {
      return;
    }
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("reviewStatus", "NOT_FLAGGED");

    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("reviewStatus", "FLAGGED");
    afterState.put("reviewRule", REVIEW_RULE_MANUAL_HIGH_VALUE_JOURNAL);
    afterState.put("reviewQueue", REVIEW_QUEUE_ACCOUNTING_AUDIT_EVENTS);
    afterState.put("warnOnly", true);
    afterState.put("thresholdAmount", REVIEW_WARNING_THRESHOLD);
    afterState.put("observedAmount", totalDebit);
    afterState.put("journalEntryId", entry.getId());
    afterState.put("referenceNumber", entry.getReferenceNumber());

    Map<String, String> metadata = baseMetadata(company, beforeState, afterState, false);
    metadata.put("reviewRule", REVIEW_RULE_MANUAL_HIGH_VALUE_JOURNAL);
    metadata.put("reviewQueue", REVIEW_QUEUE_ACCOUNTING_AUDIT_EVENTS);
    metadata.put("reviewSeverity", "WARN");
    metadata.put("reviewStatus", "OPEN");
    metadata.put("warnOnly", "true");
    metadata.put("thresholdAmount", toPlainAmount(REVIEW_WARNING_THRESHOLD));
    metadata.put("observedAmount", toPlainAmount(totalDebit));

    record(
        company,
        ACTION_REVIEW_INTELLIGENCE_WARNING,
        ENTITY_JOURNAL,
        stringifyId(entry.getId()),
        entry.getReferenceNumber(),
        totalDebit,
        entry.getCurrency(),
        metadata);
  }

  private boolean qualifiesForReviewWarning(boolean manualJournal, BigDecimal totalDebit) {
    return manualJournal
        && totalDebit != null
        && totalDebit.compareTo(REVIEW_WARNING_THRESHOLD) >= 0;
  }

  private String toPlainAmount(BigDecimal amount) {
    if (amount == null) {
      return "0";
    }
    return amount.stripTrailingZeros().toPlainString();
  }

  private String toJson(Map<String, Object> state) {
    if (state == null || state.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(state);
    } catch (JsonProcessingException ex) {
      return "{}";
    }
  }

  private String periodCloseRequestReference(PeriodCloseRequest request) {
    if (request == null) {
      return null;
    }
    if (request.getPublicId() != null) {
      return request.getPublicId().toString();
    }
    return stringifyId(request.getId());
  }

  private String stringifyId(Long id) {
    return id != null ? id.toString() : null;
  }

  private void mergeAdditionalMetadata(
      Map<String, String> metadata, Map<String, String> additionalMetadata) {
    if (metadata == null || additionalMetadata == null || additionalMetadata.isEmpty()) {
      return;
    }
    additionalMetadata.forEach(
        (key, value) -> {
          if (!StringUtils.hasText(key) || value == null) {
            return;
          }
          metadata.put(key.trim(), value.trim());
        });
  }

  private HttpServletRequest currentRequest() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes servletRequestAttributes)) {
      return null;
    }
    return servletRequestAttributes.getRequest();
  }

  private String resolveTraceId(HttpServletRequest request) {
    String traceId = header(request, "X-Trace-Id");
    if (!StringUtils.hasText(traceId) && request != null) {
      Object attribute = request.getAttribute("traceId");
      traceId = attribute != null ? attribute.toString() : null;
    }
    return trimToNull(traceId);
  }

  private String resolveClientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwarded = header(request, "X-Forwarded-For");
    if (StringUtils.hasText(forwarded)) {
      int commaIndex = forwarded.indexOf(',');
      return commaIndex > 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
    }
    String realIp = header(request, "X-Real-IP");
    if (StringUtils.hasText(realIp)) {
      return realIp.trim();
    }
    return trimToNull(request.getRemoteAddr());
  }

  private String header(HttpServletRequest request, String name) {
    return request == null ? null : trimToNull(request.getHeader(name));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
