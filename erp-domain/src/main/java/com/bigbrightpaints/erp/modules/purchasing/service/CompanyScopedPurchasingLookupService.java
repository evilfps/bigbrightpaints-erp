package com.bigbrightpaints.erp.modules.purchasing.service;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
public class CompanyScopedPurchasingLookupService {

  private final SupplierRepository supplierRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  public CompanyScopedPurchasingLookupService(
      SupplierRepository supplierRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this.supplierRepository = supplierRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
  }

  public Supplier requireSupplier(Company company, Long supplierId) {
    return supplierRepository
        .findByCompanyAndId(company, supplierId)
        .orElseThrow(() -> new IllegalArgumentException("Supplier not found: id=" + supplierId));
  }

  public RawMaterialPurchase requireRawMaterialPurchase(Company company, Long purchaseId) {
    return rawMaterialPurchaseRepository
        .findByCompanyAndId(company, purchaseId)
        .orElseThrow(
            () -> new IllegalArgumentException("Raw material purchase not found: id=" + purchaseId));
  }
}
