package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;

@Service
public class AccountingAuditService {

  private static final Logger log = LoggerFactory.getLogger(AccountingAuditService.class);
  private static final int ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH = 100;
  private static final int ACCOUNTING_EVENT_ACCOUNT_CODE_MAX_LENGTH = 50;
  private static final int ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH = 500;

  private final ApplicationEventPublisher eventPublisher;
  private final AuditService auditService;
  private final AccountingEventStore accountingEventStore;

  @Value("${erp.accounting.event-trail.strict:true}")
  private boolean strictAccountingEventTrail = true;

  public AccountingAuditService(
      ApplicationEventPublisher eventPublisher,
      AuditService auditService,
      AccountingEventStore accountingEventStore) {
    this.eventPublisher = eventPublisher;
    this.auditService = auditService;
    this.accountingEventStore = accountingEventStore;
  }

  void logAuditSuccessAfterCommit(AuditEvent event, Map<String, String> metadata) {
    if (event == null || !shouldEmitAuditServiceSuccessEvent(event)) {
      return;
    }
    Map<String, String> capturedMetadata = metadata != null ? new HashMap<>(metadata) : null;
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              auditService.logSuccess(event, capturedMetadata);
            }
          });
      return;
    }
    auditService.logSuccess(event, capturedMetadata);
  }

  boolean recordJournalEntryPostedEventSafe(
      JournalEntry journalEntry, Map<Long, BigDecimal> balancesBefore) {
    if (journalEntry == null) {
      return true;
    }
    try {
      validatePostedEventPayloadCompatibility(journalEntry);
      Map<Long, BigDecimal> snapshot =
          balancesBefore != null ? new HashMap<>(balancesBefore) : Map.of();
      accountingEventStore.recordJournalEntryPosted(journalEntry, snapshot);
      return true;
    } catch (Exception ex) {
      handleAccountingEventTrailFailure(
          "JOURNAL_ENTRY_POSTED", journalEntry.getReferenceNumber(), ex);
      return false;
    }
  }

  void recordJournalEntryReversedEventSafe(
      JournalEntry original, JournalEntry reversal, String reason) {
    if (original == null || reversal == null) {
      return;
    }
    validateReversalEventPayloadCompatibility(original, reason);
    try {
      accountingEventStore.recordJournalEntryReversed(original, reversal, reason);
    } catch (Exception ex) {
      handleAccountingEventTrailFailure(
          "JOURNAL_ENTRY_REVERSED", original.getReferenceNumber(), ex);
    }
  }

  void recordJournalEntryVoidedEventSafe(
      JournalEntry original, JournalEntry voidEntry, String reason) {
    if (original == null || voidEntry == null) {
      return;
    }
    validateReversalEventPayloadCompatibility(original, reason);
    try {
      accountingEventStore.recordJournalEntryVoided(original, voidEntry, reason);
    } catch (Exception ex) {
      handleAccountingEventTrailFailure("JOURNAL_ENTRY_VOIDED", original.getReferenceNumber(), ex);
    }
  }

  void logSettlementAuditSuccess(
      PartnerType partnerType,
      Long partnerId,
      JournalEntryDto journalEntryDto,
      LocalDate settlementDate,
      String idempotencyKey,
      int allocationCount,
      BigDecimal totalApplied,
      BigDecimal cashAmount,
      BigDecimal totalDiscount,
      BigDecimal totalWriteOff,
      BigDecimal totalFxGain,
      BigDecimal totalFxLoss,
      boolean settlementOverrideRequested,
      String settlementOverrideReason,
      String settlementOverrideActor) {
    Map<String, String> auditMetadata =
        buildSettlementAuditSuccessMetadata(
            partnerType,
            partnerId,
            journalEntryDto,
            settlementDate,
            idempotencyKey,
            allocationCount,
            totalApplied,
            cashAmount,
            totalDiscount,
            totalWriteOff,
            totalFxGain,
            totalFxLoss,
            settlementOverrideRequested,
            settlementOverrideReason,
            settlementOverrideActor);
    logAuditSuccessAfterCommit(AuditEvent.SETTLEMENT_RECORDED, auditMetadata);
  }

  void recordDealerReceiptPostedEventSafe(
      JournalEntry journalEntry, Long dealerId, BigDecimal amount, String idempotencyKey) {
    if (journalEntry == null) {
      return;
    }
    try {
      validateSettlementEventPayloadCompatibility(journalEntry, "DEALER_RECEIPT_POSTED");
      accountingEventStore.recordDealerReceiptPosted(
          journalEntry, dealerId, amount, normalizeAuditValue(idempotencyKey));
    } catch (Exception ex) {
      handleAccountingEventTrailFailure(
          "DEALER_RECEIPT_POSTED", journalEntry.getReferenceNumber(), ex);
    }
  }

  void recordSupplierPaymentPostedEventSafe(
      JournalEntry journalEntry, Long supplierId, BigDecimal amount, String idempotencyKey) {
    if (journalEntry == null) {
      return;
    }
    try {
      validateSettlementEventPayloadCompatibility(journalEntry, "SUPPLIER_PAYMENT_POSTED");
      accountingEventStore.recordSupplierPaymentPosted(
          journalEntry, supplierId, amount, normalizeAuditValue(idempotencyKey));
    } catch (Exception ex) {
      handleAccountingEventTrailFailure(
          "SUPPLIER_PAYMENT_POSTED", journalEntry.getReferenceNumber(), ex);
    }
  }

  void recordSettlementAllocatedEventSafe(
      JournalEntry journalEntry,
      PartnerType partnerType,
      Long partnerId,
      BigDecimal amount,
      int allocationCount,
      String idempotencyKey) {
    if (journalEntry == null || partnerType == null) {
      return;
    }
    try {
      validateSettlementEventPayloadCompatibility(journalEntry, "SETTLEMENT_ALLOCATED");
      accountingEventStore.recordSettlementAllocated(
          journalEntry,
          partnerType.name(),
          partnerId,
          amount,
          allocationCount,
          normalizeAuditValue(idempotencyKey));
    } catch (Exception ex) {
      handleAccountingEventTrailFailure(
          "SETTLEMENT_ALLOCATED", journalEntry.getReferenceNumber(), ex);
    }
  }

  String resolveCurrentUsername() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  void publishAccountCacheInvalidated(Long companyId) {
    if (companyId == null) {
      return;
    }
    eventPublisher.publishEvent(new AccountCacheInvalidatedEvent(companyId));
  }

  private Map<String, String> buildSettlementAuditSuccessMetadata(
      PartnerType partnerType,
      Long partnerId,
      JournalEntryDto journalEntryDto,
      LocalDate settlementDate,
      String idempotencyKey,
      int allocationCount,
      BigDecimal totalApplied,
      BigDecimal cashAmount,
      BigDecimal totalDiscount,
      BigDecimal totalWriteOff,
      BigDecimal totalFxGain,
      BigDecimal totalFxLoss,
      boolean settlementOverrideRequested,
      String settlementOverrideReason,
      String settlementOverrideActor) {
    Map<String, String> auditMetadata = new HashMap<>();
    auditMetadata.put(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType.name());
    if (partnerId != null) {
      auditMetadata.put(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId.toString());
    }
    if (journalEntryDto != null && journalEntryDto.id() != null) {
      auditMetadata.put(
          IntegrationFailureMetadataSchema.KEY_JOURNAL_ENTRY_ID, journalEntryDto.id().toString());
    }
    if (settlementDate != null) {
      auditMetadata.put(
          IntegrationFailureMetadataSchema.KEY_SETTLEMENT_DATE, settlementDate.toString());
    }
    if (StringUtils.hasText(idempotencyKey)) {
      auditMetadata.put(
          IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey.trim());
    }
    auditMetadata.put(
        IntegrationFailureMetadataSchema.KEY_ALLOCATION_COUNT, Integer.toString(allocationCount));
    auditMetadata.put("totalApplied", totalApplied.toPlainString());
    auditMetadata.put("cashAmount", cashAmount.toPlainString());
    auditMetadata.put("totalDiscount", totalDiscount.toPlainString());
    auditMetadata.put("totalWriteOff", totalWriteOff.toPlainString());
    auditMetadata.put("totalFxGain", totalFxGain.toPlainString());
    auditMetadata.put("totalFxLoss", totalFxLoss.toPlainString());
    auditMetadata.put("settlementOverrideRequested", Boolean.toString(settlementOverrideRequested));
    if (StringUtils.hasText(settlementOverrideReason)) {
      auditMetadata.put("settlementOverrideReason", settlementOverrideReason.trim());
    }
    if (StringUtils.hasText(settlementOverrideActor)) {
      auditMetadata.put("settlementOverrideActor", settlementOverrideActor.trim());
    }
    return auditMetadata;
  }

  private boolean shouldEmitAuditServiceSuccessEvent(AuditEvent event) {
    return event != AuditEvent.JOURNAL_ENTRY_POSTED
        && event != AuditEvent.JOURNAL_ENTRY_REVERSED
        && event != AuditEvent.SETTLEMENT_RECORDED;
  }

  private void validatePostedEventPayloadCompatibility(JournalEntry journalEntry) {
    ensureEventFieldWithinLimit(
        "journalReference",
        journalEntry.getReferenceNumber(),
        ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH,
        "JOURNAL_ENTRY_POSTED");
    ensureEventFieldWithinLimit(
        "journalMemo",
        journalEntry.getMemo(),
        ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
        "JOURNAL_ENTRY_POSTED");
    if (journalEntry.getLines() == null || journalEntry.getLines().isEmpty()) {
      return;
    }
    for (JournalLine line : journalEntry.getLines()) {
      if (line == null) {
        continue;
      }
      Account account = line.getAccount();
      if (account != null) {
        ensureEventFieldWithinLimit(
            "accountCode",
            account.getCode(),
            ACCOUNTING_EVENT_ACCOUNT_CODE_MAX_LENGTH,
            "JOURNAL_ENTRY_POSTED");
      }
      ensureEventFieldWithinLimit(
          "journalLineDescription",
          line.getDescription(),
          ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
          "JOURNAL_ENTRY_POSTED");
    }
  }

  private void validateReversalEventPayloadCompatibility(JournalEntry original, String reason) {
    ensureEventFieldWithinLimit(
        "journalReference",
        original.getReferenceNumber(),
        ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH,
        "JOURNAL_ENTRY_REVERSED");
    ensureEventFieldWithinLimit(
        "reversalReason",
        reason,
        ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
        "JOURNAL_ENTRY_REVERSED");
  }

  private void validateSettlementEventPayloadCompatibility(
      JournalEntry journalEntry, String operation) {
    ensureEventFieldWithinLimit(
        "journalReference",
        journalEntry.getReferenceNumber(),
        ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH,
        operation);
    ensureEventFieldWithinLimit(
        "journalMemo", journalEntry.getMemo(), ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH, operation);
  }

  private String normalizeAuditValue(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private void ensureEventFieldWithinLimit(
      String field, String value, int maxLength, String operation) {
    if (!StringUtils.hasText(value)) {
      return;
    }
    String normalized = value.trim();
    if (normalized.length() <= maxLength) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Accounting event-trail field exceeds allowed length")
        .withDetail("eventTrailOperation", operation)
        .withDetail("field", field)
        .withDetail("maxLength", maxLength)
        .withDetail("actualLength", normalized.length());
  }

  private void handleAccountingEventTrailFailure(
      String operation, String journalReference, Exception ex) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("eventTrailOperation", operation);
    String policy = strictAccountingEventTrail ? "STRICT" : "BEST_EFFORT";
    metadata.put("policy", policy);
    if (StringUtils.hasText(journalReference)) {
      metadata.put("journalReference", journalReference);
    }
    String failureCode = AccountingEventTrailAlertRoutingPolicy.ACCOUNTING_EVENT_TRAIL_FAILURE_CODE;
    String errorCategory = classifyEventTrailFailure(ex);
    IntegrationFailureMetadataSchema.applyRequiredFields(
        metadata,
        failureCode,
        errorCategory,
        AccountingEventTrailAlertRoutingPolicy.ROUTING_VERSION,
        AccountingEventTrailAlertRoutingPolicy.resolveRoute(failureCode, errorCategory, policy));
    metadata.put("errorType", ex.getClass().getSimpleName());
    try {
      auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    } catch (Exception auditEx) {
      log.warn("Failed to write integration-failure audit marker", auditEx);
    }
    if (strictAccountingEventTrail) {
      throw new ApplicationException(
              ErrorCode.SYSTEM_DATABASE_ERROR, "Accounting event trail persistence failed", ex)
          .withDetail("eventTrailOperation", operation)
          .withDetail("journalReference", journalReference);
    }
    log.warn("Accounting event trail persistence failed (best-effort policy)", ex);
  }

  private String classifyEventTrailFailure(Exception ex) {
    if (ex instanceof ApplicationException appEx) {
      return classifyApplicationEventTrailFailure(appEx.getErrorCode());
    }
    if (ex instanceof IllegalArgumentException) {
      return "VALIDATION";
    }
    if (ex instanceof DataIntegrityViolationException) {
      return "DATA_INTEGRITY";
    }
    return "PERSISTENCE";
  }

  private String classifyApplicationEventTrailFailure(ErrorCode errorCode) {
    if (errorCode == null) {
      return "PERSISTENCE";
    }
    if (isValidationError(errorCode)) {
      return "VALIDATION";
    }
    if (isDataIntegrityError(errorCode)) {
      return "DATA_INTEGRITY";
    }
    return "PERSISTENCE";
  }

  private boolean isValidationError(ErrorCode errorCode) {
    return errorCode.name().startsWith("VALIDATION_");
  }

  private boolean isDataIntegrityError(ErrorCode errorCode) {
    return switch (errorCode) {
      case CONCURRENCY_CONFLICT,
              CONCURRENCY_LOCK_TIMEOUT,
              INTERNAL_CONCURRENCY_FAILURE,
              DUPLICATE_ENTITY ->
          true;
      default -> false;
    };
  }
}
