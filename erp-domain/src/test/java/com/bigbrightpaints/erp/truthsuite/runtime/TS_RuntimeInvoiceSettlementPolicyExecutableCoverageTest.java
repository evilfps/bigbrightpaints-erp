package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;

@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeInvoiceSettlementPolicyExecutableCoverageTest {

  @Test
  void ensure_issuable_requires_draft_status() {
    InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();

    Invoice draft = invoice("DRAFT", "100.00", "100.00");
    policy.ensureIssuable(draft);
    assertThat(draft.getStatus()).isEqualTo("ISSUED");

    Invoice alreadyIssued = invoice("ISSUED", "100.00", "100.00");
    assertThatThrownBy(() -> policy.ensureIssuable(alreadyIssued))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Only draft invoices can be issued");
  }

  @Test
  void apply_payment_and_settlement_are_idempotent_and_mismatch_safe() {
    InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();
    Invoice invoice = invoice("ISSUED", "100.00", "100.00");

    policy.applyPayment(invoice, new BigDecimal("40.00"), "PAY-001");
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("60.00");
    assertThat(invoice.getStatus()).isEqualTo("PARTIAL");

    policy.applyPayment(invoice, new BigDecimal("40.00"), "PAY-001");
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("60.00");

    policy.applySettlement(invoice, new BigDecimal("10.00"), "SET-001");
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("50.00");

    policy.applySettlement(invoice, new BigDecimal("10.00"), "SET-001");
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("50.00");

    policy.applySettlement(invoice, BigDecimal.ZERO, "SET-IGNORE");
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("50.00");

    assertThatThrownBy(() -> policy.applyPayment(invoice, new BigDecimal("60.00"), "PAY-OVER"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payment exceeds outstanding amount");

    assertThatThrownBy(() -> policy.applySettlement(invoice, new BigDecimal("60.00"), "SET-OVER"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Settlement exceeds outstanding amount");
  }

  @Test
  void status_and_reversal_paths_cover_paid_issued_partial_void_and_validation_guards() {
    InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();

    Invoice partial = invoice("ISSUED", "100.00", "60.00");
    policy.updateStatusFromOutstanding(partial, new BigDecimal("0.00"));
    assertThat(partial.getStatus()).isEqualTo("PAID");

    Invoice issued = invoice("PARTIAL", "100.00", "100.00");
    policy.updateStatusFromOutstanding(issued, new BigDecimal("100.00"));
    assertThat(issued.getStatus()).isEqualTo("ISSUED");

    Invoice stillDraft = invoice("DRAFT", "100.00", "100.00");
    policy.updateStatusFromOutstanding(stillDraft, new BigDecimal("100.00"));
    assertThat(stillDraft.getStatus()).isEqualTo("DRAFT");

    Invoice voidInvoice = invoice("VOID", "100.00", "30.00");
    policy.updateStatusFromOutstanding(voidInvoice, new BigDecimal("10.00"));
    assertThat(voidInvoice.getStatus()).isEqualTo("VOID");

    Invoice reversible = invoice("PARTIAL", "100.00", "60.00");
    reversible.addPaymentReference("REF-1");
    policy.reversePayment(reversible, new BigDecimal("10.00"), "REF-1");
    assertThat(reversible.getOutstandingAmount()).isEqualByComparingTo("70.00");
    assertThat(reversible.getPaymentReferences()).doesNotContain("REF-1");

    assertThatThrownBy(() -> policy.reversePayment(reversible, new BigDecimal("10.00"), "UNKNOWN"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("No payment found");

    Invoice overflow = invoice("PARTIAL", "100.00", "95.00");
    overflow.addPaymentReference("REF-2");
    assertThatThrownBy(() -> policy.reversePayment(overflow, new BigDecimal("10.00"), "REF-2"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Reversal exceeds original total");

    Invoice payable = invoice("ISSUED", "10.00", "10.00");
    assertThatThrownBy(() -> policy.applyPayment(payable, BigDecimal.ZERO, "X"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invalid payment amount");
    assertThatThrownBy(() -> policy.applyPayment(payable, new BigDecimal("1.00"), " "))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payment reference is required");

    policy.voidInvoice(payable);
    assertThat(payable.getStatus()).isEqualTo("VOID");
    assertThat(payable.getOutstandingAmount()).isEqualByComparingTo("0.00");

    assertThatThrownBy(() -> policy.applySettlement(payable, new BigDecimal("1.00"), "SET-VOID"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot settle a void or written-off invoice");
    assertThatThrownBy(() -> policy.applyPayment(payable, new BigDecimal("1.00"), "PAY-VOID"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot pay a void or written-off invoice");
  }

  @Test
  void due_date_detection_handles_null_and_past_due_dates() {
    InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();

    Invoice noDueDate = invoice("ISSUED", "10.00", "10.00");
    noDueDate.setDueDate(null);
    assertThat(policy.isPastDue(noDueDate, LocalDate.of(2026, 2, 1))).isFalse();

    Invoice due = invoice("ISSUED", "10.00", "10.00");
    due.setDueDate(LocalDate.of(2026, 1, 31));
    assertThat(policy.isPastDue(due, LocalDate.of(2026, 2, 1))).isTrue();
  }

  @Test
  void settlement_and_void_branches_cover_reversed_status_null_outstanding_and_reference_guards() {
    InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();

    Invoice nullOutstanding = invoice("ISSUED", "100.00", "100.00");
    nullOutstanding.setOutstandingAmount(null);
    assertThatThrownBy(
            () -> policy.applySettlement(nullOutstanding, new BigDecimal("5.00"), "SET-NULL"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Settlement exceeds outstanding amount");

    Invoice nonTrackedReference = invoice("ISSUED", "100.00", "100.00");
    policy.applySettlement(nonTrackedReference, new BigDecimal("10.00"), "   ");
    assertThat(nonTrackedReference.getPaymentReferences()).isEmpty();
    assertThat(nonTrackedReference.getOutstandingAmount()).isEqualByComparingTo("90.00");

    Invoice zeroSettlement = invoice("ISSUED", "100.00", "100.00");
    policy.applySettlement(zeroSettlement, null, "SET-ZERO");
    policy.applySettlement(zeroSettlement, BigDecimal.ZERO, "SET-ZERO");
    assertThat(zeroSettlement.getOutstandingAmount()).isEqualByComparingTo("100.00");

    Invoice reversed = invoice("REVERSED", "100.00", "100.00");
    assertThatThrownBy(() -> policy.applyPayment(reversed, new BigDecimal("1.00"), "PAY-REV"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot pay a void or written-off invoice");
    assertThatThrownBy(() -> policy.applySettlement(reversed, new BigDecimal("1.00"), "SET-REV"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot settle a void or written-off invoice");

    Invoice writtenOff = invoice("WRITTEN_OFF", "100.00", "0.00");
    assertThatThrownBy(() -> policy.applyPayment(writtenOff, new BigDecimal("1.00"), "PAY-WR"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot pay a void or written-off invoice");
    assertThatThrownBy(() -> policy.applySettlement(writtenOff, new BigDecimal("1.00"), "SET-WR"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Cannot settle a void or written-off invoice");

    Invoice nullTotal = invoice("PARTIAL", "100.00", "40.00");
    nullTotal.setTotalAmount(null);
    policy.updateStatusFromOutstanding(nullTotal, new BigDecimal("40.00"));
    assertThat(nullTotal.getStatus()).isEqualTo("ISSUED");

    Invoice draftTotalNull = invoice("DRAFT", "100.00", "20.00");
    draftTotalNull.setTotalAmount(null);
    policy.updateStatusFromOutstanding(draftTotalNull, new BigDecimal("20.00"));
    assertThat(draftTotalNull.getStatus()).isEqualTo("DRAFT");

    Invoice creditTarget = invoice("ISSUED", "100.00", "100.00");
    policy.applyCredit(creditTarget, new BigDecimal("10.00"), "CREDIT-1");
    assertThat(creditTarget.getOutstandingAmount()).isEqualByComparingTo("90.00");

    assertThatThrownBy(() -> policy.applyPayment(creditTarget, new BigDecimal("1.00"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reference");
    assertThatThrownBy(() -> policy.reversePayment(creditTarget, null, "CREDIT-1"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invalid reversal amount");
  }

  private Invoice invoice(String status, String total, String outstanding) {
    Invoice invoice = new Invoice();
    invoice.setStatus(status);
    invoice.setTotalAmount(new BigDecimal(total));
    invoice.setOutstandingAmount(new BigDecimal(outstanding));
    invoice.setDueDate(LocalDate.of(2026, 2, 1));
    return invoice;
  }
}
