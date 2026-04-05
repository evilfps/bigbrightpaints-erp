package com.bigbrightpaints.erp.modules.invoice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;

/**
 * Centralized policy for invoice status transitions and payment handling.
 * All invoice payment/settlement operations should go through this policy
 * to ensure consistent status management.
 */
@Component
public class InvoiceSettlementPolicy {

  public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PARTIAL,
    PAID,
    VOID,
    REVERSED
  }

  public void ensureIssuable(Invoice invoice) {
    if (!InvoiceStatus.DRAFT.name().equals(invoice.getStatus())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Only draft invoices can be issued");
    }
    invoice.setStatus(InvoiceStatus.ISSUED.name());
  }

  /**
   * Apply a payment/settlement to an invoice.
   * Updates outstanding amount and transitions status appropriately.
   * Idempotent - duplicate references are silently ignored.
   *
   * @param invoice The invoice to apply payment to (must be locked by caller)
   * @param amount The payment amount (must be positive)
   * @param reference Unique payment reference for idempotency
   */
  public void applyPayment(Invoice invoice, BigDecimal amount, String reference) {
    validatePositive(amount, "payment amount");
    validateReference(reference);
    if (isVoid(invoice)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Cannot pay a void invoice");
    }
    if (invoice.getPaymentReferences().contains(reference)) {
      return; // idempotent reprocessing
    }
    BigDecimal currentOutstanding =
        invoice.getOutstandingAmount() != null ? invoice.getOutstandingAmount() : BigDecimal.ZERO;
    if (amount.compareTo(currentOutstanding) > 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Payment exceeds outstanding amount");
    }
    invoice.addPaymentReference(reference);
    BigDecimal newOutstanding = currentOutstanding.subtract(amount);
    invoice.setOutstandingAmount(newOutstanding);
    updateStatusFromOutstanding(invoice, newOutstanding);
  }

  /**
   * Apply a settlement clearing (payment + discount + write-off + fx adjustment).
   * Used by AccountingService for dealer/supplier settlements.
   *
   * @param invoice The invoice to settle (must be locked by caller)
   * @param clearedAmount Total cleared amount (applied + discount + write-off + fx)
   * @param reference Settlement reference for tracking
   */
  public void applySettlement(Invoice invoice, BigDecimal clearedAmount, String reference) {
    if (clearedAmount == null || clearedAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (isVoid(invoice)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Cannot settle a void invoice");
    }
    if (reference != null
        && !reference.isBlank()
        && invoice.getPaymentReferences().contains(reference)) {
      return; // idempotent reprocessing
    }
    BigDecimal currentOutstanding =
        invoice.getOutstandingAmount() != null ? invoice.getOutstandingAmount() : BigDecimal.ZERO;
    if (clearedAmount.compareTo(currentOutstanding) > 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Settlement exceeds outstanding amount");
    }
    BigDecimal newOutstanding = currentOutstanding.subtract(clearedAmount);
    invoice.setOutstandingAmount(newOutstanding);
    if (reference != null && !reference.isBlank()) {
      invoice.addPaymentReference(reference);
    }
    updateStatusFromOutstanding(invoice, newOutstanding);
  }

  /**
   * Applies settlement with explicit override components.
   * Non-zero discount/write-off/FX adjustments require maker-checker approval.
   */
  public void applySettlementWithOverride(
      Invoice invoice,
      BigDecimal appliedAmount,
      BigDecimal discountAmount,
      BigDecimal writeOffAmount,
      BigDecimal fxAdjustmentAmount,
      String reference,
      SettlementApprovalDecision approval) {
    BigDecimal applied = appliedAmount != null ? appliedAmount : BigDecimal.ZERO;
    BigDecimal discount = requireNonNegative(discountAmount, "discount amount");
    BigDecimal writeOff = requireNonNegative(writeOffAmount, "write-off amount");
    BigDecimal fxAdjustment = fxAdjustmentAmount != null ? fxAdjustmentAmount : BigDecimal.ZERO;
    validateReference(reference);

    if (discount.compareTo(BigDecimal.ZERO) > 0
        || writeOff.compareTo(BigDecimal.ZERO) > 0
        || fxAdjustment.compareTo(BigDecimal.ZERO) != 0) {
      SettlementApprovalDecision resolved = requireSettlementOverrideApproval(approval);
      if (resolved.immutableAuditMetadata().isEmpty()) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
            "Settlement override approval audit metadata is required");
      }
    }

    BigDecimal clearedAmount = applied.add(discount).add(writeOff).add(fxAdjustment);
    applySettlement(invoice, clearedAmount, reference);
  }

  public SettlementApprovalDecision requireSupplierExceptionApproval(
      SettlementApprovalDecision approval) {
    SettlementApprovalDecision resolved =
        SettlementApprovalDecision.requireApproved(approval, "Supplier exception");
    if (resolved.reasonCode() != SettlementApprovalReasonCode.SUPPLIER_EXCEPTION) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Supplier exception approval must use SUPPLIER_EXCEPTION reason code");
    }
    return resolved;
  }

  private SettlementApprovalDecision requireSettlementOverrideApproval(
      SettlementApprovalDecision approval) {
    SettlementApprovalDecision resolved =
        SettlementApprovalDecision.requireApproved(approval, "Settlement override");
    if (resolved.reasonCode() != SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Settlement override approval must use SETTLEMENT_OVERRIDE reason code");
    }
    return resolved;
  }

  /**
   * Update invoice status based on outstanding amount.
   * Call this after any modification to outstanding amount.
   */
  public void updateStatusFromOutstanding(Invoice invoice, BigDecimal outstanding) {
    if (isVoid(invoice)) {
      return; // Don't change void status
    }
    if (outstanding == null || outstanding.compareTo(BigDecimal.ZERO) <= 0) {
      invoice.setStatus(InvoiceStatus.PAID.name());
      return;
    }
    BigDecimal total =
        invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
    if (outstanding.compareTo(total) >= 0) {
      if (!InvoiceStatus.DRAFT.name().equalsIgnoreCase(invoice.getStatus())) {
        invoice.setStatus(InvoiceStatus.ISSUED.name());
      }
    } else {
      invoice.setStatus(InvoiceStatus.PARTIAL.name());
    }
  }

  public void applyCredit(Invoice invoice, BigDecimal amount, String reference) {
    applyPayment(invoice, amount, reference);
  }

  public void reversePayment(Invoice invoice, BigDecimal amount, String reference) {
    validatePositive(amount, "reversal amount");
    validateReference(reference);
    if (!invoice.getPaymentReferences().contains(reference)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "No payment found to reverse for reference: " + reference);
    }
    BigDecimal restored = invoice.getOutstandingAmount().add(amount);
    if (restored.compareTo(invoice.getTotalAmount()) > 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Reversal exceeds original total");
    }
    invoice.setOutstandingAmount(restored);
    invoice.removePaymentReference(reference);
    updateStatusFromOutstanding(invoice, restored);
  }

  public void voidInvoice(Invoice invoice) {
    invoice.setOutstandingAmount(BigDecimal.ZERO);
    invoice.setStatus(InvoiceStatus.VOID.name());
  }

  public boolean isPastDue(Invoice invoice, LocalDate asOf) {
    LocalDate dueDate = invoice.getDueDate();
    return dueDate != null && asOf.isAfter(dueDate);
  }

  private boolean isVoid(Invoice invoice) {
    String status = invoice.getStatus();
    return InvoiceStatus.VOID.name().equalsIgnoreCase(status)
        || InvoiceStatus.REVERSED.name().equalsIgnoreCase(status);
  }

  private void validatePositive(BigDecimal amount, String label) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid " + label);
    }
  }

  private void validateReference(String reference) {
    if (!Objects.requireNonNull(reference, "reference").trim().isEmpty()) {
      return;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "Payment reference is required");
  }

  private BigDecimal requireNonNegative(BigDecimal amount, String label) {
    BigDecimal resolved = amount != null ? amount : BigDecimal.ZERO;
    if (resolved.compareTo(BigDecimal.ZERO) < 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid " + label);
    }
    return resolved;
  }
}
