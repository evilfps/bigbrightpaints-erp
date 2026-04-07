package com.bigbrightpaints.erp.modules.purchasing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.bigbrightpaints.erp.core.util.BusinessDocumentTruths;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

@Component
public class PurchaseResponseMapper {

  private final RawMaterialPurchaseRepository purchaseRepository;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;

  @Autowired
  public PurchaseResponseMapper(
      RawMaterialPurchaseRepository purchaseRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository) {
    this.purchaseRepository = purchaseRepository;
    this.settlementAllocationRepository = settlementAllocationRepository;
  }

  PurchaseResponseMapper() {
    this(null, null);
  }

  PurchaseResponseMapper(RawMaterialPurchaseRepository purchaseRepository) {
    this(purchaseRepository, null);
  }

  public List<GoodsReceiptResponse> toGoodsReceiptResponses(List<GoodsReceipt> receipts) {
    if (receipts == null || receipts.isEmpty()) {
      return List.of();
    }
    Map<Long, RawMaterialPurchase> linkedPurchasesByReceiptId = resolveLinkedPurchases(receipts);
    return receipts.stream()
        .map(
            receipt ->
                toGoodsReceiptResponse(receipt, linkedPurchasesByReceiptId.get(receipt.getId())))
        .toList();
  }

  public List<RawMaterialPurchaseResponse> toPurchaseResponses(
      List<RawMaterialPurchase> purchases) {
    if (purchases == null || purchases.isEmpty()) {
      return List.of();
    }
    Map<Long, List<PartnerSettlementAllocation>> settlementAllocationsByPurchaseId =
        resolveSettlementAllocations(purchases);
    return purchases.stream()
        .map(
            purchase ->
                toPurchaseResponse(
                    purchase,
                    settlementAllocationsByPurchaseId.getOrDefault(purchase.getId(), List.of())))
        .toList();
  }

  public RawMaterialPurchaseResponse toPurchaseResponse(RawMaterialPurchase purchase) {
    return toPurchaseResponse(
        purchase,
        resolveSettlementAllocations(List.of(purchase)).getOrDefault(purchase.getId(), List.of()));
  }

  private RawMaterialPurchaseResponse toPurchaseResponse(
      RawMaterialPurchase purchase, List<PartnerSettlementAllocation> settlementAllocations) {
    JournalEntry journalEntry = purchase.getJournalEntry();
    Supplier supplier = purchase.getSupplier();
    PurchaseOrder purchaseOrder = purchase.getPurchaseOrder();
    GoodsReceipt goodsReceipt = purchase.getGoodsReceipt();
    List<RawMaterialPurchaseLineResponse> lines =
        purchase.getLines().stream().map(this::toPurchaseLineResponse).toList();
    DocumentLifecycleDto lifecycle = BusinessDocumentTruths.purchaseLifecycle(purchase);
    return new RawMaterialPurchaseResponse(
        purchase.getId(),
        purchase.getPublicId(),
        purchase.getInvoiceNumber(),
        purchase.getInvoiceDate(),
        purchase.getTotalAmount(),
        purchase.getTaxAmount(),
        purchase.getOutstandingAmount(),
        purchase.getStatus(),
        purchase.getMemo(),
        supplier != null ? supplier.getId() : null,
        supplier != null ? supplier.getCode() : null,
        supplier != null ? supplier.getName() : null,
        purchaseOrder != null ? purchaseOrder.getId() : null,
        purchaseOrder != null ? purchaseOrder.getOrderNumber() : null,
        goodsReceipt != null ? goodsReceipt.getId() : null,
        goodsReceipt != null ? goodsReceipt.getReceiptNumber() : null,
        journalEntry != null ? journalEntry.getId() : null,
        purchase.getCreatedAt(),
        lines,
        lifecycle,
        purchaseLinkedReferences(purchase, lifecycle, settlementAllocations));
  }

  public RawMaterialPurchaseLineResponse toPurchaseLineResponse(RawMaterialPurchaseLine line) {
    RawMaterial material = line.getRawMaterial();
    return new RawMaterialPurchaseLineResponse(
        material != null ? material.getId() : null,
        material != null ? material.getName() : null,
        line.getRawMaterialBatch() != null ? line.getRawMaterialBatch().getId() : null,
        line.getBatchCode(),
        line.getQuantity(),
        line.getUnit(),
        line.getCostPerUnit(),
        line.getLineTotal(),
        line.getTaxRate(),
        line.getTaxAmount(),
        line.getNotes(),
        line.getCgstAmount(),
        line.getSgstAmount(),
        line.getIgstAmount());
  }

  public PurchaseOrderResponse toPurchaseOrderResponse(PurchaseOrder order) {
    Supplier supplier = order.getSupplier();
    List<PurchaseOrderLineResponse> lines =
        order.getLines().stream().map(this::toPurchaseOrderLineResponse).toList();
    BigDecimal totalAmount =
        lines.stream()
            .map(PurchaseOrderLineResponse::lineTotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new PurchaseOrderResponse(
        order.getId(),
        order.getPublicId(),
        order.getOrderNumber(),
        order.getOrderDate(),
        totalAmount,
        canonicalPurchaseOrderStatus(order.getStatusValue()),
        order.getMemo(),
        supplier != null ? supplier.getId() : null,
        supplier != null ? supplier.getCode() : null,
        supplier != null ? supplier.getName() : null,
        order.getCreatedAt(),
        lines);
  }

  private String canonicalPurchaseOrderStatus(String status) {
    if ("VOID".equalsIgnoreCase(status)) {
      return "VOIDED";
    }
    return status;
  }

  public PurchaseOrderLineResponse toPurchaseOrderLineResponse(PurchaseOrderLine line) {
    RawMaterial material = line.getRawMaterial();
    return new PurchaseOrderLineResponse(
        material != null ? material.getId() : null,
        material != null ? material.getName() : null,
        line.getQuantity(),
        line.getUnit(),
        line.getCostPerUnit(),
        line.getLineTotal(),
        line.getNotes());
  }

  public GoodsReceiptResponse toGoodsReceiptResponse(GoodsReceipt receipt) {
    return toGoodsReceiptResponse(receipt, resolveLinkedPurchase(receipt));
  }

  GoodsReceiptResponse toGoodsReceiptResponse(
      GoodsReceipt receipt, RawMaterialPurchase linkedPurchase) {
    Supplier supplier = receipt.getSupplier();
    PurchaseOrder purchaseOrder = receipt.getPurchaseOrder();
    List<GoodsReceiptLineResponse> lines =
        receipt.getLines().stream().map(this::toGoodsReceiptLineResponse).toList();
    BigDecimal totalAmount =
        lines.stream()
            .map(GoodsReceiptLineResponse::lineTotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    DocumentLifecycleDto lifecycle =
        BusinessDocumentTruths.goodsReceiptLifecycle(receipt, linkedPurchase);
    return new GoodsReceiptResponse(
        receipt.getId(),
        receipt.getPublicId(),
        receipt.getReceiptNumber(),
        receipt.getReceiptDate(),
        totalAmount,
        receipt.getStatusValue(),
        receipt.getMemo(),
        supplier != null ? supplier.getId() : null,
        supplier != null ? supplier.getCode() : null,
        supplier != null ? supplier.getName() : null,
        purchaseOrder != null ? purchaseOrder.getId() : null,
        purchaseOrder != null ? purchaseOrder.getOrderNumber() : null,
        receipt.getCreatedAt(),
        lines,
        lifecycle,
        goodsReceiptLinkedReferences(receipt, linkedPurchase, lifecycle));
  }

  public GoodsReceiptLineResponse toGoodsReceiptLineResponse(GoodsReceiptLine line) {
    RawMaterial material = line.getRawMaterial();
    return new GoodsReceiptLineResponse(
        material != null ? material.getId() : null,
        material != null ? material.getName() : null,
        line.getBatchCode(),
        line.getQuantity(),
        line.getUnit(),
        line.getCostPerUnit(),
        line.getLineTotal(),
        line.getNotes());
  }

  private List<LinkedBusinessReferenceDto> purchaseLinkedReferences(
      RawMaterialPurchase purchase,
      DocumentLifecycleDto lifecycle,
      List<PartnerSettlementAllocation> settlementAllocations) {
    List<LinkedBusinessReferenceDto> linkedReferences = new ArrayList<>();
    PurchaseOrder purchaseOrder = purchase.getPurchaseOrder();
    if (purchaseOrder != null) {
      linkedReferences.add(
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
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "GOODS_RECEIPT",
              "GOODS_RECEIPT",
              goodsReceipt.getId(),
              goodsReceipt.getReceiptNumber(),
              BusinessDocumentTruths.goodsReceiptLifecycle(goodsReceipt, purchase),
              purchase.getJournalEntry() != null ? purchase.getJournalEntry().getId() : null));
    }
    if (purchase.getJournalEntry() != null) {
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "ACCOUNTING_ENTRY",
              "JOURNAL_ENTRY",
              purchase.getJournalEntry().getId(),
              purchase.getJournalEntry().getReferenceNumber(),
              BusinessDocumentTruths.journalLifecycle(purchase.getJournalEntry()),
              purchase.getJournalEntry().getId()));
    }
    if (settlementAllocations != null) {
      for (PartnerSettlementAllocation allocation : settlementAllocations) {
        linkedReferences.add(
            BusinessDocumentTruths.reference(
                "SETTLEMENT",
                "SETTLEMENT_ALLOCATION",
                allocation.getId(),
                allocation.getIdempotencyKey(),
                BusinessDocumentTruths.settlementLifecycle(allocation.getJournalEntry()),
                allocation.getJournalEntry() != null
                    ? allocation.getJournalEntry().getId()
                    : null));
      }
    }
    linkedReferences.add(
        BusinessDocumentTruths.reference(
            "SELF",
            "PURCHASE_INVOICE",
            purchase.getId(),
            purchase.getInvoiceNumber(),
            lifecycle,
            purchase.getJournalEntry() != null ? purchase.getJournalEntry().getId() : null));
    return linkedReferences.stream()
        .filter(reference -> reference.documentId() != null)
        .distinct()
        .toList();
  }

  private List<LinkedBusinessReferenceDto> goodsReceiptLinkedReferences(
      GoodsReceipt receipt, RawMaterialPurchase linkedPurchase, DocumentLifecycleDto lifecycle) {
    List<LinkedBusinessReferenceDto> linkedReferences = new ArrayList<>();
    PurchaseOrder purchaseOrder = receipt.getPurchaseOrder();
    if (purchaseOrder != null) {
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "PURCHASE_ORDER",
              "PURCHASE_ORDER",
              purchaseOrder.getId(),
              purchaseOrder.getOrderNumber(),
              new DocumentLifecycleDto(purchaseOrder.getStatusValue(), "NOT_ELIGIBLE"),
              null));
    }
    if (linkedPurchase != null) {
      DocumentLifecycleDto purchaseLifecycle =
          BusinessDocumentTruths.purchaseLifecycle(linkedPurchase);
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "PURCHASE_INVOICE",
              "PURCHASE_INVOICE",
              linkedPurchase.getId(),
              linkedPurchase.getInvoiceNumber(),
              purchaseLifecycle,
              linkedPurchase.getJournalEntry() != null
                  ? linkedPurchase.getJournalEntry().getId()
                  : null));
      if (linkedPurchase.getJournalEntry() != null) {
        linkedReferences.add(
            BusinessDocumentTruths.reference(
                "ACCOUNTING_ENTRY",
                "JOURNAL_ENTRY",
                linkedPurchase.getJournalEntry().getId(),
                linkedPurchase.getJournalEntry().getReferenceNumber(),
                BusinessDocumentTruths.journalLifecycle(linkedPurchase.getJournalEntry()),
                linkedPurchase.getJournalEntry().getId()));
      }
    }
    linkedReferences.add(
        BusinessDocumentTruths.reference(
            "SELF",
            "GOODS_RECEIPT",
            receipt.getId(),
            receipt.getReceiptNumber(),
            lifecycle,
            linkedPurchase != null && linkedPurchase.getJournalEntry() != null
                ? linkedPurchase.getJournalEntry().getId()
                : null));
    return linkedReferences.stream()
        .filter(reference -> reference.documentId() != null)
        .distinct()
        .toList();
  }

  private RawMaterialPurchase resolveLinkedPurchase(GoodsReceipt receipt) {
    if (purchaseRepository == null || receipt == null || receipt.getCompany() == null) {
      return null;
    }
    return purchaseRepository
        .findByCompanyAndGoodsReceipt(receipt.getCompany(), receipt)
        .orElse(null);
  }

  private Map<Long, RawMaterialPurchase> resolveLinkedPurchases(List<GoodsReceipt> receipts) {
    if (purchaseRepository == null || receipts == null || receipts.isEmpty()) {
      return Map.of();
    }
    com.bigbrightpaints.erp.modules.company.domain.Company company =
        receipts.stream()
            .map(GoodsReceipt::getCompany)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (company == null) {
      return Map.of();
    }
    List<Long> receiptIds =
        receipts.stream().map(GoodsReceipt::getId).filter(Objects::nonNull).toList();
    if (receiptIds.isEmpty()) {
      return Map.of();
    }
    return purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, receiptIds).stream()
        .filter(
            purchase ->
                purchase.getGoodsReceipt() != null && purchase.getGoodsReceipt().getId() != null)
        .collect(
            Collectors.toMap(
                purchase -> purchase.getGoodsReceipt().getId(),
                purchase -> purchase,
                (left, right) -> left));
  }

  private Map<Long, List<PartnerSettlementAllocation>> resolveSettlementAllocations(
      List<RawMaterialPurchase> purchases) {
    if (settlementAllocationRepository == null || purchases == null || purchases.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<PartnerSettlementAllocation>> allocationsByPurchaseId = new HashMap<>();
    Map<com.bigbrightpaints.erp.modules.company.domain.Company, List<Long>> purchaseIdsByCompany =
        purchases.stream()
            .filter(Objects::nonNull)
            .filter(purchase -> purchase.getCompany() != null && purchase.getId() != null)
            .collect(
                Collectors.groupingBy(
                    RawMaterialPurchase::getCompany,
                    Collectors.mapping(
                        RawMaterialPurchase::getId,
                        Collectors.collectingAndThen(
                            Collectors.toList(), ids -> ids.stream().distinct().toList()))));
    for (Map.Entry<com.bigbrightpaints.erp.modules.company.domain.Company, List<Long>> entry :
        purchaseIdsByCompany.entrySet()) {
      List<PartnerSettlementAllocation> allocations =
          settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(
              entry.getKey(), entry.getValue());
      if (allocations == null) {
        continue;
      }
      allocations.stream()
          .filter(
              allocation ->
                  allocation.getPurchase() != null && allocation.getPurchase().getId() != null)
          .forEach(
              allocation ->
                  allocationsByPurchaseId
                      .computeIfAbsent(
                          allocation.getPurchase().getId(), ignored -> new ArrayList<>())
                      .add(allocation));
    }
    return allocationsByPurchaseId;
  }
}
