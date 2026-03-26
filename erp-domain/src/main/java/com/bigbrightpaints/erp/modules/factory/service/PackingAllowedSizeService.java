package com.bigbrightpaints.erp.modules.factory.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.AllowedSellableSizeDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@Service
public class PackingAllowedSizeService {

  private final ProductionProductRepository productionProductRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final SizeVariantRepository sizeVariantRepository;

  public PackingAllowedSizeService(
      ProductionProductRepository productionProductRepository,
      FinishedGoodRepository finishedGoodRepository,
      SizeVariantRepository sizeVariantRepository) {
    this.productionProductRepository = productionProductRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.sizeVariantRepository = sizeVariantRepository;
  }

  public List<AllowedSellableSizeDto> listAllowedSellableSizes(Company company, ProductionLog log) {
    return resolveAllowedSellableSizeTargets(company, log).stream().map(this::toDto).toList();
  }

  public List<AllowedSellableSizeTarget> resolveAllowedSellableSizeTargets(
      Company company, ProductionLog log) {
    return loadAllowedTargets(company, log);
  }

  public AllowedSellableSizeTarget requireAllowedSellableSize(
      Company company,
      ProductionLog log,
      Long childFinishedGoodId,
      String packagingSize,
      int lineNumber) {
    return requireAllowedSellableSize(
        resolveAllowedSellableSizeTargets(company, log),
        log,
        childFinishedGoodId,
        packagingSize,
        lineNumber);
  }

  public AllowedSellableSizeTarget requireAllowedSellableSize(
      List<AllowedSellableSizeTarget> allowedTargets,
      ProductionLog log,
      Long childFinishedGoodId,
      String packagingSize,
      int lineNumber) {
    if (childFinishedGoodId == null) {
      throw ValidationUtils.invalidInput("Sellable size target is required for line " + lineNumber);
    }

    AllowedSellableSizeTarget target =
        allowedTargets.stream()
            .filter(candidate -> childFinishedGoodId.equals(candidate.finishedGood().getId()))
            .findFirst()
            .orElseThrow(() -> invalidAllowedSellableSizeTarget(log, childFinishedGoodId));

    String normalizedSize = normalizeRequiredSize(packagingSize, lineNumber);
    String targetSize = normalizeSize(target.sizeVariant().getSizeLabel());
    if (!normalizedSize.equals(targetSize)) {
      throw ValidationUtils.invalidInput(
          "Packaging size '"
              + normalizedSize
              + "' does not match sellable size target '"
              + target.sizeVariant().getSizeLabel()
              + "' on line "
              + lineNumber);
    }
    return target;
  }

  private List<AllowedSellableSizeTarget> loadAllowedTargets(Company company, ProductionLog log) {
    ProductionProduct baseProduct = log != null ? log.getProduct() : null;
    if (company == null || baseProduct == null) {
      return List.of();
    }

    List<ProductionProduct> familyProducts = resolveFamilyProducts(company, baseProduct);
    if (familyProducts.isEmpty()) {
      return List.of();
    }

    Map<String, FinishedGood> finishedGoodsBySku =
        loadFinishedGoodsBySku(
            company, familyProducts.stream().map(ProductionProduct::getSkuCode).toList());

    String productFamilyName = resolveProductFamilyName(baseProduct);
    List<AllowedSellableSizeTarget> allowedTargets = new ArrayList<>();
    for (ProductionProduct product : familyProducts) {
      if (product == null || !product.isActive()) {
        continue;
      }
      if (!StringUtils.hasText(product.getSkuCode())) {
        continue;
      }
      FinishedGood finishedGood = finishedGoodsBySku.get(normalizeSku(product.getSkuCode()));
      if (finishedGood == null || finishedGood.getId() == null) {
        continue;
      }
      SizeVariant sizeVariant = resolveActiveSizeVariant(company, product);
      if (sizeVariant == null || sizeVariant.getId() == null) {
        continue;
      }
      AllowedSellableSizeTarget target =
          allowedTarget(product, finishedGood, sizeVariant, productFamilyName);
      allowedTargets.add(target);
    }

    return allowedTargets.stream()
        .sorted(
            Comparator.comparing(
                    AllowedSellableSizeTarget::sizeVariant,
                    Comparator.comparing(
                            SizeVariant::getLitersPerUnit,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                            SizeVariant::getSizeLabel,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .thenComparing(
                    target -> target.finishedGood().getProductCode(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
        .toList();
  }

  private List<ProductionProduct> resolveFamilyProducts(
      Company company, ProductionProduct baseProduct) {
    UUID variantGroupId = baseProduct.getVariantGroupId();
    if (variantGroupId == null) {
      return List.of(baseProduct);
    }
    List<ProductionProduct> familyProducts =
        productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, variantGroupId);
    if (familyProducts == null || familyProducts.isEmpty()) {
      return List.of(baseProduct);
    }
    return familyProducts;
  }

  private Map<String, FinishedGood> loadFinishedGoodsBySku(
      Company company, List<String> productCodes) {
    List<String> normalizedCodes =
        productCodes.stream()
            .filter(StringUtils::hasText)
            .map(this::normalizeSku)
            .distinct()
            .toList();
    if (normalizedCodes.isEmpty()) {
      return Map.of();
    }
    Map<String, FinishedGood> finishedGoodsBySku = new LinkedHashMap<>();
    List<FinishedGood> finishedGoods =
        finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(company, normalizedCodes);
    for (FinishedGood finishedGood : finishedGoods) {
      if (finishedGood == null || !StringUtils.hasText(finishedGood.getProductCode())) {
        continue;
      }
      finishedGoodsBySku.put(normalizeSku(finishedGood.getProductCode()), finishedGood);
    }
    return finishedGoodsBySku;
  }

  private SizeVariant resolveActiveSizeVariant(Company company, ProductionProduct product) {
    List<SizeVariant> activeVariants =
        sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, product).stream()
            .filter(SizeVariant::isActive)
            .toList();
    if (activeVariants.isEmpty()) {
      return null;
    }

    if (StringUtils.hasText(product.getSizeLabel())) {
      String normalizedProductSize = normalizeSize(product.getSizeLabel());
      return activeVariants.stream()
          .filter(variant -> normalizedProductSize.equals(normalizeSize(variant.getSizeLabel())))
          .findFirst()
          .orElse(null);
    }

    return activeVariants.size() == 1 ? activeVariants.getFirst() : null;
  }

  private AllowedSellableSizeDto toDto(AllowedSellableSizeTarget target) {
    return new AllowedSellableSizeDto(
        target.finishedGood().getId(),
        target.finishedGood().getProductCode(),
        target.finishedGood().getName(),
        target.sizeVariant().getId(),
        target.sizeVariant().getSizeLabel(),
        target.sizeVariant().getCartonQuantity(),
        target.sizeVariant().getLitersPerUnit(),
        target.productFamilyName());
  }

  private AllowedSellableSizeTarget allowedTarget(
      ProductionProduct product,
      FinishedGood finishedGood,
      SizeVariant sizeVariant,
      String productFamilyName) {
    return new AllowedSellableSizeTarget(product, finishedGood, sizeVariant, productFamilyName);
  }

  private ApplicationException invalidAllowedSellableSizeTarget(
      ProductionLog log, Long childFinishedGoodId) {
    return new ApplicationException(
            ErrorCode.VALIDATION_INVALID_REFERENCE,
            "Sellable size target is not allowed for production batch "
                + log.getProductionCode()
                + ": "
                + childFinishedGoodId)
        .withDetail("productionLogId", log.getId())
        .withDetail("childFinishedGoodId", childFinishedGoodId);
  }

  private String resolveProductFamilyName(ProductionProduct product) {
    if (product == null) {
      return null;
    }
    if (StringUtils.hasText(product.getProductFamilyName())) {
      return product.getProductFamilyName().trim();
    }
    if (StringUtils.hasText(product.getProductName())) {
      return product.getProductName().trim();
    }
    return product.getSkuCode();
  }

  private String normalizeSku(String productCode) {
    return productCode.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeRequiredSize(String packagingSize, int lineNumber) {
    if (!StringUtils.hasText(packagingSize)) {
      throw ValidationUtils.invalidInput("Packaging size is required for line " + lineNumber);
    }
    return normalizeSize(packagingSize);
  }

  private String normalizeSize(String packagingSize) {
    return packagingSize == null ? "" : packagingSize.trim().toUpperCase(Locale.ROOT);
  }

  public record AllowedSellableSizeTarget(
      ProductionProduct product,
      FinishedGood finishedGood,
      SizeVariant sizeVariant,
      String productFamilyName) {}
}
