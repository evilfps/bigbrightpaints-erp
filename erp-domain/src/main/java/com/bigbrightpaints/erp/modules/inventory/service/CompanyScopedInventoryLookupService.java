package com.bigbrightpaints.erp.modules.inventory.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@Service
public class CompanyScopedInventoryLookupService {

  private final RawMaterialRepository rawMaterialRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final ProductionProductRepository productionProductRepository;

  public CompanyScopedInventoryLookupService(
      RawMaterialRepository rawMaterialRepository,
      FinishedGoodRepository finishedGoodRepository,
      ProductionProductRepository productionProductRepository) {
    this.rawMaterialRepository = rawMaterialRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.productionProductRepository = productionProductRepository;
  }

  public RawMaterial requireRawMaterial(Company company, Long rawMaterialId) {
    return rawMaterialRepository
        .findByCompanyAndId(company, rawMaterialId)
        .orElseThrow(
            () -> new IllegalArgumentException("Raw material not found: id=" + rawMaterialId));
  }

  public RawMaterial requireActiveRawMaterial(Company company, Long rawMaterialId) {
    RawMaterial rawMaterial = requireRawMaterial(company, rawMaterialId);
    assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
    return rawMaterial;
  }

  public RawMaterial lockActiveRawMaterial(Company company, Long rawMaterialId) {
    RawMaterial rawMaterial =
        rawMaterialRepository
            .lockByCompanyAndId(company, rawMaterialId)
            .orElseThrow(
                () -> new IllegalArgumentException("Raw material not found: id=" + rawMaterialId));
    assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
    return rawMaterial;
  }

  public FinishedGood requireActiveFinishedGood(Company company, Long finishedGoodId) {
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () -> new IllegalArgumentException("Finished good not found: id=" + finishedGoodId));
    assertLinkedProductActive(
        company, finishedGood.getProductCode(), "finished good", finishedGoodId);
    return finishedGood;
  }

  public FinishedGood lockActiveFinishedGood(Company company, Long finishedGoodId) {
    FinishedGood finishedGood =
        finishedGoodRepository
            .lockByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () -> new IllegalArgumentException("Finished good not found: id=" + finishedGoodId));
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
