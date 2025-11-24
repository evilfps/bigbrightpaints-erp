package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

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

    public void applyPayment(Invoice invoice, BigDecimal amount, String reference) {
        validatePositive(amount, "payment amount");
        validateReference(reference);
        if (isVoid(invoice)) {
            throw new IllegalStateException("Cannot pay a void invoice");
        }
        if (invoice.getPaymentReferences().contains(reference)) {
            return; // idempotent reprocessing
        }
        if (amount.compareTo(invoice.getOutstandingAmount()) > 0) {
            throw new IllegalArgumentException("Payment exceeds outstanding amount");
        }
        invoice.getPaymentReferences().add(reference);
        BigDecimal newOutstanding = invoice.getOutstandingAmount().subtract(amount);
        invoice.setOutstandingAmount(newOutstanding);
        if (newOutstanding.compareTo(BigDecimal.ZERO) == 0) {
            invoice.setStatus(InvoiceStatus.PAID.name());
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
