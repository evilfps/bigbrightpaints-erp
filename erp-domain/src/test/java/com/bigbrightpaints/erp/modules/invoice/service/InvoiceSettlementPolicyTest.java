package com.bigbrightpaints.erp.modules.invoice.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;

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
    assertThrows(
        ApplicationException.class, () -> policy.applyPayment(invoice, BigDecimal.TEN, "PAY-VOID"));
  }

  @Test
  void reversalRestoresOutstandingButCannotExceedTotal() {
    policy.ensureIssuable(invoice);
    policy.applyPayment(invoice, BigDecimal.valueOf(50), "PAY-1");
    policy.reversePayment(invoice, BigDecimal.valueOf(20), "PAY-1");

    assertEquals(BigDecimal.valueOf(70), invoice.getOutstandingAmount());
    assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
    assertThrows(
        ApplicationException.class,
        () -> policy.reversePayment(invoice, BigDecimal.valueOf(80), "PAY-1"));
  }

  @Test
  void detectsPastDueInvoices() {
    policy.ensureIssuable(invoice);
    assertTrue(policy.isPastDue(invoice, LocalDate.now()));
  }

  @Test
  void settlementOverrideFailsClosedWhenApprovalIsMissing() {
    policy.ensureIssuable(invoice);

    assertThrows(
        ApplicationException.class,
        () ->
            policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(40),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "SETTLE-OVERRIDE-1",
                null));
  }

  @Test
  void settlementOverrideRequiresOverrideReasonCode() {
    policy.ensureIssuable(invoice);

    SettlementApprovalDecision wrongReasonApproval =
        new SettlementApprovalDecision(
            "APP-1",
            "maker-user",
            "checker-user",
            SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
            Instant.parse("2026-02-20T00:00:00Z"),
            Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

    assertThrows(
        ApplicationException.class,
        () ->
            policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(40),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "SETTLE-OVERRIDE-2",
                wrongReasonApproval));
  }

  @Test
  void settlementOverrideAppliesWhenApproved() {
    policy.ensureIssuable(invoice);

    SettlementApprovalDecision approval =
        new SettlementApprovalDecision(
            "APP-2",
            "maker-user",
            "checker-user",
            SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
            Instant.parse("2026-02-20T00:00:00Z"),
            Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

    policy.applySettlementWithOverride(
        invoice,
        BigDecimal.valueOf(40),
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "SETTLE-OVERRIDE-3",
        approval);

    assertEquals(BigDecimal.valueOf(50), invoice.getOutstandingAmount());
    assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
  }

  @Test
  void supplierExceptionApprovalRequiresReasonCodeAndImmutableMetadata() {
    SettlementApprovalDecision approval =
        new SettlementApprovalDecision(
            "APP-3",
            "maker-user",
            "checker-user",
            SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
            Instant.parse("2026-02-20T00:00:00Z"),
            Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

    SettlementApprovalDecision resolved = policy.requireSupplierExceptionApproval(approval);
    assertThrows(
        UnsupportedOperationException.class,
        () -> resolved.immutableAuditMetadata().put("newKey", "newValue"));
  }

  @Test
  void settlementOverrideFailsClosedWhenReferenceMissing() {
    policy.ensureIssuable(invoice);
    SettlementApprovalDecision approval =
        new SettlementApprovalDecision(
            "APP-4",
            "maker-user",
            "checker-user",
            SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
            Instant.parse("2026-02-20T00:00:00Z"),
            Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

    assertThrows(
        ApplicationException.class,
        () ->
            policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(40),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                " ",
                approval));
  }

  @Test
  void settlementApprovalMetadataRequiresSourceKey() {
    assertThrows(
        ApplicationException.class,
        () ->
            new SettlementApprovalDecision(
                "APP-5",
                "maker-user",
                "checker-user",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095")));
  }

  @Test
  void writtenOffInvoicesStayDistinctAndRejectFurtherSettlement() {
    policy.ensureIssuable(invoice);
    policy.markWrittenOff(invoice);

    assertEquals(InvoiceSettlementPolicy.InvoiceStatus.WRITTEN_OFF.name(), invoice.getStatus());
    assertThrows(
        ApplicationException.class,
        () -> policy.applyPayment(invoice, BigDecimal.ONE, "PAY-WRITTEN-OFF"));
    assertThrows(
        ApplicationException.class,
        () -> policy.applySettlement(invoice, BigDecimal.ONE, "SET-WRITTEN-OFF"));

    policy.updateStatusFromOutstanding(invoice, BigDecimal.ZERO);
    assertEquals(InvoiceSettlementPolicy.InvoiceStatus.WRITTEN_OFF.name(), invoice.getStatus());
  }
}
