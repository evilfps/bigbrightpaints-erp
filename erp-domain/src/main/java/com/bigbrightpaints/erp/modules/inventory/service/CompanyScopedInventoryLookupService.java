package com.bigbrightpaints.erp.modules.inventory.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@Service
public class CompanyScopedInventoryLookupService {

  private final CompanyScopedLookupService companyScopedLookupService;
  private final RawMaterialRepository rawMaterialRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final ProductionProductRepository productionProductRepository;

  @Autowired
  public CompanyScopedInventoryLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      RawMaterialRepository rawMaterialRepository,
      FinishedGoodRepository finishedGoodRepository,
      ProductionProductRepository productionProductRepository) {
    this.companyScopedLookupService = companyScopedLookupService;
    this.rawMaterialRepository = rawMaterialRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.productionProductRepository = productionProductRepository;
  }

  public CompanyScopedInventoryLookupService(
      RawMaterialRepository rawMaterialRepository,
      FinishedGoodRepository finishedGoodRepository,
      ProductionProductRepository productionProductRepository) {
    this(
        new CompanyScopedLookupService(),
        rawMaterialRepository,
        finishedGoodRepository,
        productionProductRepository);
  }

  public RawMaterial requireRawMaterial(Company company, Long rawMaterialId) {
    return companyScopedLookupService.require(
        company, rawMaterialId, rawMaterialRepository::findByCompanyAndId, "Raw material");
  }

  public RawMaterial requireActiveRawMaterial(Company company, Long rawMaterialId) {
    RawMaterial rawMaterial = requireRawMaterial(company, rawMaterialId);
    assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
    return rawMaterial;
  }

  public RawMaterial lockActiveRawMaterial(Company company, Long rawMaterialId) {
    RawMaterial rawMaterial =
        companyScopedLookupService.require(
            company, rawMaterialId, rawMaterialRepository::lockByCompanyAndId, "Raw material");
    assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
    return rawMaterial;
  }

  public FinishedGood requireActiveFinishedGood(Company company, Long finishedGoodId) {
    FinishedGood finishedGood =
        companyScopedLookupService.require(
            company, finishedGoodId, finishedGoodRepository::findByCompanyAndId, "Finished good");
    assertLinkedProductActive(
        company, finishedGood.getProductCode(), "finished good", finishedGoodId);
    return finishedGood;
  }

  public FinishedGood lockActiveFinishedGood(Company company, Long finishedGoodId) {
    FinishedGood finishedGood =
        companyScopedLookupService.require(
            company, finishedGoodId, finishedGoodRepository::lockByCompanyAndId, "Finished good");
    assertLinkedProductActive(
        company, finishedGood.getProductCode(), "finished good", finishedGoodId);
    return finishedGood;
  }

  private void assertLinkedProductActive(
      Company company, String sku, String entityType, Long entityId) {
    if (company == null || !StringUtils.hasText(sku)) {
      return;
    }
    ProductionProduct linkedProduct =
        productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, sku).orElse(null);
    if (linkedProduct == null || linkedProduct.isActive()) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.BUSINESS_INVALID_STATE,
            "Catalog item is inactive for " + entityType + " " + sku)
        .withDetail("sku", sku)
        .withDetail(entityType.replace(' ', '_') + "Id", entityId)
        .withDetail("productId", linkedProduct.getId());
  }
}
