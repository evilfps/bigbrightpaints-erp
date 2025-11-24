package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceSettlementPolicyTest {

    private InvoiceSettlementPolicy policy;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        policy = new InvoiceSettlementPolicy();
        invoice = new Invoice();
        invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.DRAFT.name());
        invoice.setTotalAmount(BigDecimal.valueOf(100));
        invoice.setOutstandingAmount(BigDecimal.valueOf(100));
        invoice.setDueDate(LocalDate.now().minusDays(1));
    }

    @Test
    void partialPaymentIsTrackedAndIdempotent() {
        policy.ensureIssuable(invoice);
        policy.applyPayment(invoice, BigDecimal.valueOf(40), "PAY-1");

        assertEquals(BigDecimal.valueOf(60), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());

        // Duplicate callback should be ignored to prevent double payment
        policy.applyPayment(invoice, BigDecimal.valueOf(40), "PAY-1");
        assertEquals(BigDecimal.valueOf(60), invoice.getOutstandingAmount());
    }

    @Test
    void fullSettlementAfterPartialPayment() {
        policy.ensureIssuable(invoice);
        policy.applyPayment(invoice, BigDecimal.valueOf(40), "PAY-1");
        policy.applyPayment(invoice, BigDecimal.valueOf(60), "PAY-2");

        assertEquals(BigDecimal.ZERO, invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PAID.name(), invoice.getStatus());
    }

    @Test
    void voidingStopsPaymentsAndZerosBalance() {
        policy.ensureIssuable(invoice);
        policy.voidInvoice(invoice);

        assertEquals(BigDecimal.ZERO, invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.VOID.name(), invoice.getStatus());
        assertThrows(IllegalStateException.class, () ->
                policy.applyPayment(invoice, BigDecimal.TEN, "PAY-VOID"));
    }

    @Test
    void reversalRestoresOutstandingButCannotExceedTotal() {
        policy.ensureIssuable(invoice);
        policy.applyPayment(invoice, BigDecimal.valueOf(50), "PAY-1");
        policy.reversePayment(invoice, BigDecimal.valueOf(20), "PAY-1");

        assertEquals(BigDecimal.valueOf(70), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
        assertThrows(IllegalArgumentException.class, () ->
                policy.reversePayment(invoice, BigDecimal.valueOf(80), "PAY-1"));
    }

    @Test
    void detectsPastDueInvoices() {
        policy.ensureIssuable(invoice);
        assertTrue(policy.isPastDue(invoice, LocalDate.now()));
    }
}
