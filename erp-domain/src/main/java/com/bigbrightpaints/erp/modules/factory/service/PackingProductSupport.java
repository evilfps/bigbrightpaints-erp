package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Component
public class PackingProductSupport {

  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final FinishedGoodRepository finishedGoodRepository;

  public PackingProductSupport(
      CompanyScopedInventoryLookupService inventoryLookupService,
      FinishedGoodRepository finishedGoodRepository) {
    this.inventoryLookupService = inventoryLookupService;
    this.finishedGoodRepository = finishedGoodRepository;
  }

  public FinishedGood ensureFinishedGood(Company company, ProductionLog log) {
    ProductionProduct product = log.getProduct();
    return finishedGoodRepository
        .lockByCompanyAndProductCode(company, product.getSkuCode())
        .orElseGet(() -> initializeFinishedGood(company, product));
  }

  public FinishedGood resolveTargetFinishedGood(
      Company company,
      ProductionLog log,
      PackingLineRequest line,
      FinishedGood defaultFinishedGood) {
    if (line.childFinishedGoodId() == null) {
      return defaultFinishedGood;
    }
    FinishedGood target;
    try {
      target = inventoryLookupService.lockActiveFinishedGood(company, line.childFinishedGoodId());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Child finished good not found: " + line.childFinishedGoodId(),
          ex);
    }

    String productSku =
        Optional.ofNullable(log.getProduct()).map(ProductionProduct::getSkuCode).orElse(null);
    if (!StringUtils.hasText(productSku)
        || !isMatchingChildSku(target.getProductCode(), productSku)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Child finished good must belong to product SKU family " + productSku);
    }
    return target;
  }

  public boolean isMatchingChildSku(String candidateSku, String parentSku) {
    if (!StringUtils.hasText(candidateSku) || !StringUtils.hasText(parentSku)) {
      return false;
    }
    String normalizedCandidate = candidateSku.trim().toUpperCase();
    String normalizedParent = parentSku.trim().toUpperCase();
    return normalizedCandidate.equals(normalizedParent)
        || normalizedCandidate.startsWith(normalizedParent + "-");
  }

  public Long requireWipAccountId(ProductionProduct product) {
    Long accountId = metadataLong(product, "wipAccountId");
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing wipAccountId metadata");
    }
    return accountId;
  }

  public Long requireSemiFinishedAccountId(ProductionProduct product) {
    Long accountId =
        Optional.ofNullable(metadataLong(product, "semiFinishedAccountId"))
            .orElse(metadataLong(product, "fgValuationAccountId"));
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing semiFinishedAccountId metadata");
    }
    return accountId;
  }

  public String semiFinishedSku(ProductionProduct product) {
    return product.getSkuCode() + "-BULK";
  }

  public Long metadataLong(ProductionProduct product, String key) {
    if (product.getMetadata() == null) {
      return null;
    }
    Object candidate = product.getMetadata().get(key);
    if (candidate instanceof Number number) {
      return number.longValue();
    }
    if (candidate instanceof String str && StringUtils.hasText(str)) {
      try {
        return Long.parseLong(str.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private FinishedGood initializeFinishedGood(Company company, ProductionProduct product) {
    Long valuationAccountId = metadataLong(product, "fgValuationAccountId");
    Long cogsAccountId = metadataLong(product, "fgCogsAccountId");
    Long revenueAccountId = metadataLong(product, "fgRevenueAccountId");
    Long discountAccountId = metadataLong(product, "fgDiscountAccountId");
    Long taxAccountId = metadataLong(product, "fgTaxAccountId");
    if (valuationAccountId == null
        || cogsAccountId == null
        || revenueAccountId == null
        || discountAccountId == null
        || taxAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing finished good account metadata");
    }
    FinishedGood created = new FinishedGood();
    created.setCompany(company);
    created.setProductCode(product.getSkuCode());
    created.setName(product.getProductName());
    created.setUnit(Optional.ofNullable(product.getUnitOfMeasure()).orElse("UNIT"));
    created.setCostingMethod("FIFO");
    created.setValuationAccountId(valuationAccountId);
    created.setCogsAccountId(cogsAccountId);
    created.setRevenueAccountId(revenueAccountId);
    created.setDiscountAccountId(discountAccountId);
    created.setTaxAccountId(taxAccountId);
    created.setCurrentStock(BigDecimal.ZERO);
    created.setReservedStock(BigDecimal.ZERO);
    return finishedGoodRepository.save(created);
  }
}
