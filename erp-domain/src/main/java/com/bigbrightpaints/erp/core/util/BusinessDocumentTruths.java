package com.bigbrightpaints.erp.core.util;

import java.util.Locale;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

public final class BusinessDocumentTruths {
  private BusinessDocumentTruths() {}

  public static DocumentLifecycleDto salesOrderLifecycle(SalesOrder order) {
    String workflowStatus = normalizeWorkflow(order != null ? order.getStatus() : null, "DRAFT");
    String accountingStatus = "NOT_ELIGIBLE";
    if (order != null && order.getSalesJournalEntryId() != null) {
      accountingStatus = "POSTED";
    } else if (order != null && order.getFulfillmentInvoiceId() != null) {
      accountingStatus = "PENDING";
    }
    return new DocumentLifecycleDto(workflowStatus, accountingStatus);
  }

  public static DocumentLifecycleDto packagingSlipLifecycle(PackagingSlip slip) {
    String workflowStatus = normalizeWorkflow(slip != null ? slip.getStatus() : null, "PENDING");
    String accountingStatus = "PENDING";
    if (slip != null
        && (slip.getInvoiceId() != null
            || slip.getJournalEntryId() != null
            || slip.getCogsJournalEntryId() != null)) {
      accountingStatus = "POSTED";
    }
    return new DocumentLifecycleDto(workflowStatus, accountingStatus);
  }

  public static DocumentLifecycleDto invoiceLifecycle(
      String workflowStatus, JournalEntry journalEntry) {
    String normalizedWorkflow = normalizeWorkflow(workflowStatus, "DRAFT");
    return new DocumentLifecycleDto(
        normalizedWorkflow,
        deriveAccountingStatus(
            normalizedWorkflow, journalEntry, !"DRAFT".equals(normalizedWorkflow)));
  }

  public static DocumentLifecycleDto goodsReceiptLifecycle(
      GoodsReceipt goodsReceipt, RawMaterialPurchase linkedPurchase) {
    String workflowStatus =
        normalizeWorkflow(goodsReceipt != null ? goodsReceipt.getStatus() : null, "RECEIVED");
    return new DocumentLifecycleDto(
        workflowStatus,
        linkedPurchase == null ? "PENDING" : purchaseLifecycle(linkedPurchase).accountingStatus());
  }

  public static DocumentLifecycleDto purchaseLifecycle(RawMaterialPurchase purchase) {
    String workflowStatus =
        normalizeWorkflow(purchase != null ? purchase.getStatus() : null, "POSTED");
    return new DocumentLifecycleDto(
        workflowStatus,
        deriveAccountingStatus(
            workflowStatus, purchase != null ? purchase.getJournalEntry() : null, true));
  }

  public static DocumentLifecycleDto settlementLifecycle(JournalEntry journalEntry) {
    return new DocumentLifecycleDto("ALLOCATED", deriveAccountingStatus(null, journalEntry, true));
  }

  public static DocumentLifecycleDto journalLifecycle(JournalEntry journalEntry) {
    String workflowStatus =
        normalizeWorkflow(journalEntry != null ? journalStatus(journalEntry) : null, "DRAFT");
    return new DocumentLifecycleDto(
        workflowStatus, deriveAccountingStatus(workflowStatus, journalEntry, true));
  }

  public static LinkedBusinessReferenceDto reference(
      String relationType,
      String documentType,
      Long documentId,
      String documentNumber,
      DocumentLifecycleDto lifecycle,
      Long journalEntryId) {
    return new LinkedBusinessReferenceDto(
        relationType, documentType, documentId, documentNumber, lifecycle, journalEntryId);
  }

  private static String deriveAccountingStatus(
      String workflowStatus, JournalEntry journalEntry, boolean accountingEligible) {
    String normalizedWorkflow = normalizeWorkflow(workflowStatus, null);
    if ("BLOCKED".equals(normalizedWorkflow)) {
      return "BLOCKED";
    }
    if (isReversedWorkflow(normalizedWorkflow)) {
      return "REVERSED";
    }
    if (journalEntry == null) {
      return accountingEligible ? "PENDING" : "NOT_ELIGIBLE";
    }
    JournalEntryStatus status = journalEntry.getStatus();
    if (status == null) {
      return accountingEligible ? "PENDING" : "NOT_ELIGIBLE";
    }
    return switch (status) {
      case POSTED, PAID, SETTLED, CLOSED -> "POSTED";
      case REVERSED, VOID, VOIDED, CANCELLED -> "REVERSED";
      case BLOCKED, FAILED -> "BLOCKED";
      case DRAFT -> accountingEligible ? "PENDING" : "NOT_ELIGIBLE";
    };
  }

  private static boolean isReversedWorkflow(String workflowStatus) {
    return "REVERSED".equals(workflowStatus)
        || "VOID".equals(workflowStatus)
        || "VOIDED".equals(workflowStatus)
        || "CANCELLED".equals(workflowStatus);
  }

  private static String normalizeWorkflow(String value, String defaultValue) {
    if (!StringUtils.hasText(value)) {
      return defaultValue;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static String journalStatus(JournalEntry journalEntry) {
    return journalEntry.getStatus() == null ? null : journalEntry.getStatus().name();
  }
}
