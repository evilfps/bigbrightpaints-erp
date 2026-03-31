package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * Request for reversing a journal entry with support for:
 * - Full or partial reversals (percentage-based)
 * - Cascade reversal of related entries (COGS, tax, payments)
 * - Audit trail with reason codes
 */
public record JournalEntryReversalRequest(
    LocalDate reversalDate,
    boolean voidOnly,
    String reason,
    String memo,
    Boolean adminOverride,

    // Partial reversal support (null or 100 = full reversal)
    @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal reversalPercentage,

    // Cascade options for related entries
    boolean cascadeRelatedEntries,
    List<Long> relatedEntryIds,

    // Audit trail enhancements
    ReversalReasonCode reasonCode,
    String approvedBy,
    String supportingDocumentRef) {
  // Convenience constructor for simple reversals
  public JournalEntryReversalRequest(
      LocalDate reversalDate, boolean voidOnly, String reason, String memo, Boolean adminOverride) {
    this(reversalDate, voidOnly, reason, memo, adminOverride, null, false, null, null, null, null);
  }

  public JournalEntryReversalRequest withoutCascadeReplay() {
    return copyWith(reason, memo, false, null);
  }

  public JournalEntryReversalRequest forCascadeChild(String cascadeReason, String cascadeMemo) {
    return copyWith(cascadeReason, cascadeMemo, false, null);
  }

  private JournalEntryReversalRequest copyWith(
      String nextReason, String nextMemo, boolean nextCascadeRelatedEntries, List<Long> nextRelatedIds) {
    return new JournalEntryReversalRequest(
        reversalDate,
        voidOnly,
        nextReason,
        nextMemo,
        adminOverride,
        reversalPercentage,
        nextCascadeRelatedEntries,
        nextRelatedIds,
        reasonCode,
        approvedBy,
        supportingDocumentRef);
  }

  public BigDecimal getEffectivePercentage() {
    return reversalPercentage != null ? reversalPercentage : new BigDecimal("100.00");
  }

  public boolean isPartialReversal() {
    return reversalPercentage != null && reversalPercentage.compareTo(new BigDecimal("100.00")) < 0;
  }

  /**
   * Standard reason codes for audit compliance
   */
  public enum ReversalReasonCode {
    CUSTOMER_RETURN, // Goods returned by customer
    VENDOR_CREDIT, // Credit received from vendor
    PRICING_ERROR, // Incorrect price/amount
    DUPLICATE_ENTRY, // Entry was posted twice
    WRONG_ACCOUNT, // Posted to wrong account
    WRONG_PERIOD, // Posted to wrong period
    FRAUD_CORRECTION, // Fraudulent entry correction
    SYSTEM_ERROR, // System/integration error
    AUDIT_ADJUSTMENT, // Auditor-required adjustment
    OTHER // Other (requires explanation in reason field)
  }
}
