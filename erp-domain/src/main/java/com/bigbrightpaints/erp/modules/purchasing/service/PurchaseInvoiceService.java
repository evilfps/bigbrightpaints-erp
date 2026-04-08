package com.bigbrightpaints.erp.modules.purchasing.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;

@Service
public class PurchaseInvoiceService {

  private final PurchaseInvoiceEngine purchaseInvoiceEngine;

  public PurchaseInvoiceService(
      PurchaseInvoiceEngine purchaseInvoiceEngine, PurchaseOrderService purchaseOrderService) {
    this.purchaseInvoiceEngine = purchaseInvoiceEngine;
    this.purchaseInvoiceEngine.setPurchaseOrderService(purchaseOrderService);
  }

  public List<RawMaterialPurchaseResponse> listPurchases() {
    return purchaseInvoiceEngine.listPurchases();
  }

  public List<RawMaterialPurchaseResponse> listPurchases(Long supplierId) {
    return purchaseInvoiceEngine.listPurchases(supplierId);
  }

  public RawMaterialPurchaseResponse getPurchase(Long id) {
    return purchaseInvoiceEngine.getPurchase(id);
  }

  public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
    return createPurchase(request, null);
  }

  public RawMaterialPurchaseResponse createPurchase(
      RawMaterialPurchaseRequest request, String idempotencyKey) {
    return purchaseInvoiceEngine.createPurchase(request, idempotencyKey);
  }
}
