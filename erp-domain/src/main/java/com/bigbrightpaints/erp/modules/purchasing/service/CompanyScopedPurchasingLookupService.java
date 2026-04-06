package com.bigbrightpaints.erp.modules.purchasing.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
public class CompanyScopedPurchasingLookupService {

  private final CompanyScopedLookupService companyScopedLookupService;
  private final SupplierRepository supplierRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  @Autowired
  public CompanyScopedPurchasingLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      SupplierRepository supplierRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this.companyScopedLookupService = companyScopedLookupService;
    this.supplierRepository = supplierRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
  }

  public CompanyScopedPurchasingLookupService(
      SupplierRepository supplierRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this(new CompanyScopedLookupService(), supplierRepository, rawMaterialPurchaseRepository);
  }

  public Supplier requireSupplier(Company company, Long supplierId) {
    return companyScopedLookupService.require(
        company, supplierId, supplierRepository::findByCompanyAndId, "Supplier");
  }

  public RawMaterialPurchase requireRawMaterialPurchase(Company company, Long purchaseId) {
    return companyScopedLookupService.require(
        company,
        purchaseId,
        rawMaterialPurchaseRepository::findByCompanyAndId,
        "Raw material purchase");
  }
}
