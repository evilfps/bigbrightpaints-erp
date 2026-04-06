package com.bigbrightpaints.erp.modules.purchasing.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
public class CompanyScopedPurchasingLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final SupplierRepository supplierRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  @Autowired
  public CompanyScopedPurchasingLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      SupplierRepository supplierRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.supplierRepository = supplierRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
  }

  public CompanyScopedPurchasingLookupService(
      SupplierRepository supplierRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this(new CompanyScopedLookupService(), supplierRepository, rawMaterialPurchaseRepository);
  }

  private CompanyScopedPurchasingLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.supplierRepository = null;
    this.rawMaterialPurchaseRepository = null;
  }

  public static CompanyScopedPurchasingLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedPurchasingLookupService(legacyLookup);
  }

  public Supplier requireSupplier(Company company, Long supplierId) {
    if (legacyLookup != null) {
      return legacyLookup.requireSupplier(company, supplierId);
    }
    return companyScopedLookupService.require(
        company, supplierId, supplierRepository::findByCompanyAndId, "Supplier");
  }

  public RawMaterialPurchase requireRawMaterialPurchase(Company company, Long purchaseId) {
    if (legacyLookup != null) {
      return legacyLookup.requireRawMaterialPurchase(company, purchaseId);
    }
    return companyScopedLookupService.require(
        company,
        purchaseId,
        rawMaterialPurchaseRepository::findByCompanyAndId,
        "Raw material purchase");
  }
}
