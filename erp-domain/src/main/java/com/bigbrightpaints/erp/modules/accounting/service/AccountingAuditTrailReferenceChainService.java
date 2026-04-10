package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.bigbrightpaints.erp.core.util.BusinessDocumentTruths;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

final class AccountingAuditTrailReferenceChainService {

  private final InvoiceRepository invoiceRepository;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final PackagingSlipRepository packagingSlipRepository;

  AccountingAuditTrailReferenceChainService(
      InvoiceRepository invoiceRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      PackagingSlipRepository packagingSlipRepository) {
    this.invoiceRepository = invoiceRepository;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.packagingSlipRepository = packagingSlipRepository;
  }

  LinkedBusinessReferenceDto resolveDrivingDocument(
      Invoice invoice,
      RawMaterialPurchase purchase,
      List<PartnerSettlementAllocation> allocations) {
    if (invoice != null) {
      return BusinessDocumentTruths.reference(
          "DRIVING_DOCUMENT",
          "INVOICE",
          invoice.getId(),
          invoice.getInvoiceNumber(),
          BusinessDocumentTruths.invoiceLifecycle(invoice.getStatus(), invoice.getJournalEntry()),
          invoice.getJournalEntry() != null ? invoice.getJournalEntry().getId() : null);
    }
    if (purchase != null) {
      return BusinessDocumentTruths.reference(
          "DRIVING_DOCUMENT",
          "PURCHASE_INVOICE",
          purchase.getId(),
          purchase.getInvoiceNumber(),
          BusinessDocumentTruths.purchaseLifecycle(purchase),
          purchase.getJournalEntry() != null ? purchase.getJournalEntry().getId() : null);
    }
    if (allocations != null && !allocations.isEmpty()) {
      PartnerSettlementAllocation allocation = allocations.getFirst();
      if (allocation.getInvoice() != null) {
        Invoice settledInvoice = allocation.getInvoice();
        return BusinessDocumentTruths.reference(
            "DRIVING_DOCUMENT",
            "INVOICE",
            settledInvoice.getId(),
            settledInvoice.getInvoiceNumber(),
            BusinessDocumentTruths.invoiceLifecycle(
                settledInvoice.getStatus(), settledInvoice.getJournalEntry()),
            settledInvoice.getJournalEntry() != null
                ? settledInvoice.getJournalEntry().getId()
                : null);
      }
      if (allocation.getPurchase() != null) {
        RawMaterialPurchase settledPurchase = allocation.getPurchase();
        return BusinessDocumentTruths.reference(
            "DRIVING_DOCUMENT",
            "PURCHASE_INVOICE",
            settledPurchase.getId(),
            settledPurchase.getInvoiceNumber(),
            BusinessDocumentTruths.purchaseLifecycle(settledPurchase),
            settledPurchase.getJournalEntry() != null
                ? settledPurchase.getJournalEntry().getId()
                : null);
      }
    }
    return null;
  }

  List<LinkedBusinessReferenceDto> buildReferenceChain(
      JournalEntry entry,
      Invoice invoice,
      RawMaterialPurchase purchase,
      List<PartnerSettlementAllocation> allocations,
      LinkedBusinessReferenceDto drivingDocument) {
    List<LinkedBusinessReferenceDto> chain = new ArrayList<>();
    if (drivingDocument != null) {
      chain.add(drivingDocument);
    }
    if (invoice != null) {
      if (invoice.getSalesOrder() != null) {
        List<PackagingSlip> slips =
            packagingSlipRepository.findAllByCompanyAndSalesOrderId(
                invoice.getCompany(), invoice.getSalesOrder().getId());
        chain.add(
            BusinessDocumentTruths.reference(
                "SOURCE_ORDER",
                "SALES_ORDER",
                invoice.getSalesOrder().getId(),
                invoice.getSalesOrder().getOrderNumber(),
                BusinessDocumentTruths.salesOrderLifecycle(invoice.getSalesOrder()),
                invoice.getSalesOrder().getSalesJournalEntryId()));
        for (PackagingSlip slip : slips) {
          if (!isSlipLinkedToInvoice(slip, invoice)) {
            continue;
          }
          chain.add(
              BusinessDocumentTruths.reference(
                  "DISPATCH",
                  "PACKAGING_SLIP",
                  slip.getId(),
                  slip.getSlipNumber(),
                  BusinessDocumentTruths.packagingSlipLifecycle(slip),
                  slip.getCogsJournalEntryId() != null
                      ? slip.getCogsJournalEntryId()
                      : slip.getJournalEntryId()));
        }
      }
      appendSettlementReferences(chain, invoice.getCompany(), invoice, null);
    }
    if (purchase != null) {
      PurchaseOrder purchaseOrder = purchase.getPurchaseOrder();
      if (purchaseOrder != null) {
        chain.add(
            BusinessDocumentTruths.reference(
                "PURCHASE_ORDER",
                "PURCHASE_ORDER",
                purchaseOrder.getId(),
                purchaseOrder.getOrderNumber(),
                new DocumentLifecycleDto(purchaseOrder.getStatusValue(), "NOT_ELIGIBLE"),
                null));
      }
      GoodsReceipt goodsReceipt = purchase.getGoodsReceipt();
      if (goodsReceipt != null) {
        chain.add(
            BusinessDocumentTruths.reference(
                "GOODS_RECEIPT",
                "GOODS_RECEIPT",
                goodsReceipt.getId(),
                goodsReceipt.getReceiptNumber(),
                BusinessDocumentTruths.goodsReceiptLifecycle(goodsReceipt, purchase),
                purchase.getJournalEntry() != null ? purchase.getJournalEntry().getId() : null));
      }
      appendSettlementReferences(chain, purchase.getCompany(), null, purchase);
    }
    for (PartnerSettlementAllocation allocation : allocations) {
      chain.add(settlementReference(allocation));
    }
    chain.add(
        BusinessDocumentTruths.reference(
            "ACCOUNTING_ENTRY",
            "JOURNAL_ENTRY",
            entry.getId(),
            entry.getReferenceNumber(),
            BusinessDocumentTruths.journalLifecycle(entry),
            entry.getId()));
    return chain.stream().filter(reference -> reference.documentId() != null).distinct().toList();
  }

  void appendSettlementReferences(
      List<LinkedBusinessReferenceDto> chain,
      Company company,
      Invoice invoice,
      RawMaterialPurchase purchase) {
    if (company == null) {
      return;
    }
    List<PartnerSettlementAllocation> allocations =
        invoice != null
            ? settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
                company, invoice)
            : settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(
                company, purchase);
    if (allocations == null) {
      return;
    }
    for (PartnerSettlementAllocation allocation : allocations) {
      chain.add(settlementReference(allocation));
    }
  }

  private LinkedBusinessReferenceDto settlementReference(PartnerSettlementAllocation allocation) {
    JournalEntry settlementEntry = allocation != null ? allocation.getJournalEntry() : null;
    return BusinessDocumentTruths.reference(
        "SETTLEMENT",
        "SETTLEMENT_ALLOCATION",
        allocation != null ? allocation.getId() : null,
        settlementEntry != null ? settlementEntry.getReferenceNumber() : null,
        BusinessDocumentTruths.settlementLifecycle(settlementEntry),
        settlementEntry != null ? settlementEntry.getId() : null);
  }

  private boolean isSlipLinkedToInvoice(PackagingSlip slip, Invoice invoice) {
    return slip != null
        && invoice != null
        && slip.getInvoiceId() != null
        && invoice.getId() != null
        && slip.getInvoiceId().equals(invoice.getId());
  }

  int resolveCurrentSalesOrderInvoiceCount(Invoice invoice) {
    if (invoice == null
        || invoice.getCompany() == null
        || invoice.getSalesOrder() == null
        || invoice.getSalesOrder().getId() == null) {
      return 0;
    }
    List<Invoice> orderInvoices =
        invoiceRepository.findAllByCompanyAndSalesOrderId(
            invoice.getCompany(), invoice.getSalesOrder().getId());
    int knownCount = countCurrentInvoices(orderInvoices);
    if (knownCount > 0) {
      return knownCount;
    }
    return isCurrentInvoiceStatus(invoice.getStatus()) ? 1 : 0;
  }

  private static boolean hasExplicitInvoiceLinks(List<PackagingSlip> slips) {
    return slips != null
        && slips.stream().anyMatch(slip -> slip != null && slip.getInvoiceId() != null);
  }

  private static int countCurrentInvoices(List<Invoice> invoices) {
    if (invoices == null) {
      return 0;
    }
    return (int)
        invoices.stream()
            .filter(Objects::nonNull)
            .filter(invoice -> isCurrentInvoiceStatus(invoice.getStatus()))
            .count();
  }

  private static boolean isCurrentInvoiceStatus(String status) {
    if (status == null) {
      return true;
    }
    String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
    return !normalized.equals("DRAFT")
        && !normalized.equals("VOID")
        && !normalized.equals("REVERSED")
        && !normalized.equals("WRITTEN_OFF");
  }
}
