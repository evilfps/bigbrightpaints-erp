package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

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
            throw new IllegalStateException("Only draft invoices can be issued");
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
            throw new IllegalStateException("Cannot pay a void invoice");
        }
        if (invoice.getPaymentReferences().contains(reference)) {
            return; // idempotent reprocessing
        }
        BigDecimal currentOutstanding = invoice.getOutstandingAmount() != null 
                ? invoice.getOutstandingAmount() : BigDecimal.ZERO;
        if (amount.compareTo(currentOutstanding) > 0) {
            throw new IllegalArgumentException("Payment exceeds outstanding amount");
        }
        invoice.getPaymentReferences().add(reference);
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
            throw new IllegalStateException("Cannot settle a void invoice");
        }
        if (reference != null && !reference.isBlank() && invoice.getPaymentReferences().contains(reference)) {
            return; // idempotent reprocessing
        }
        BigDecimal currentOutstanding = invoice.getOutstandingAmount() != null 
                ? invoice.getOutstandingAmount() : BigDecimal.ZERO;
        if (clearedAmount.compareTo(currentOutstanding) > 0) {
            throw new IllegalArgumentException("Settlement exceeds outstanding amount");
        }
        BigDecimal newOutstanding = currentOutstanding.subtract(clearedAmount);
        invoice.setOutstandingAmount(newOutstanding);
        if (reference != null && !reference.isBlank()) {
            invoice.getPaymentReferences().add(reference);
        }
        updateStatusFromOutstanding(invoice, newOutstanding);
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
        } else if (outstanding.compareTo(invoice.getTotalAmount()) < 0) {
            invoice.setStatus(InvoiceStatus.PARTIAL.name());
        }
        // Keep ISSUED status if no payment applied yet
    }

    public void applyCredit(Invoice invoice, BigDecimal amount, String reference) {
        applyPayment(invoice, amount, reference);
    }

    public void reversePayment(Invoice invoice, BigDecimal amount, String reference) {
        validatePositive(amount, "reversal amount");
        validateReference(reference);
        if (!invoice.getPaymentReferences().contains(reference)) {
            throw new IllegalArgumentException("No payment found to reverse for reference: " + reference);
        }
        BigDecimal restored = invoice.getOutstandingAmount().add(amount);
        if (restored.compareTo(invoice.getTotalAmount()) > 0) {
            throw new IllegalArgumentException("Reversal exceeds original total");
        }
        invoice.setOutstandingAmount(restored);
        invoice.getPaymentReferences().remove(reference);
        invoice.setStatus(restored.compareTo(BigDecimal.ZERO) == 0
                ? InvoiceStatus.PAID.name()
                : InvoiceStatus.PARTIAL.name());
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
        return InvoiceStatus.VOID.name().equalsIgnoreCase(invoice.getStatus());
    }

    private void validatePositive(BigDecimal amount, String label) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private void validateReference(String reference) {
        if (!Objects.requireNonNull(reference, "reference").trim().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Payment reference is required");
    }
}
