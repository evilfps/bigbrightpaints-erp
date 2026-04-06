package com.bigbrightpaints.erp.modules.production.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@Service
public class CompanyScopedProductionLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final ProductionBrandRepository productionBrandRepository;
  private final ProductionProductRepository productionProductRepository;

  @Autowired
  public CompanyScopedProductionLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      ProductionBrandRepository productionBrandRepository,
      ProductionProductRepository productionProductRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.productionBrandRepository = productionBrandRepository;
    this.productionProductRepository = productionProductRepository;
  }

  private CompanyScopedProductionLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.productionBrandRepository = null;
    this.productionProductRepository = null;
  }

  public static CompanyScopedProductionLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedProductionLookupService(legacyLookup);
  }

  public ProductionBrand requireProductionBrand(Company company, Long brandId) {
    if (legacyLookup != null) {
      return legacyLookup.requireProductionBrand(company, brandId);
    }
    return companyScopedLookupService.require(
        company, brandId, productionBrandRepository::findByCompanyAndId, "Production brand");
  }

  public ProductionProduct requireProductionProduct(Company company, Long productId) {
    if (legacyLookup != null) {
      return legacyLookup.requireProductionProduct(company, productId);
    }
    return companyScopedLookupService.require(
        company, productId, productionProductRepository::findByCompanyAndId, "Production product");
  }
}
