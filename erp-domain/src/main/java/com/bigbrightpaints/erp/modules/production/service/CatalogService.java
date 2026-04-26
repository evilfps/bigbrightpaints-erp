package com.bigbrightpaints.erp.modules.production.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemCreateCommand;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemStockDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemUpdateCommand;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.persistence.criteria.Predicate;

@Service
public class CatalogService {

  private static final String ITEM_CLASS_FINISHED_GOOD = "FINISHED_GOOD";
  private static final String ITEM_CLASS_RAW_MATERIAL = "RAW_MATERIAL";
  private static final String ITEM_CLASS_PACKAGING_RAW_MATERIAL = "PACKAGING_RAW_MATERIAL";
  private static final int MAX_PAGE_SIZE = 100;
  private static final String ACCOUNTING_METADATA_KEY_SUFFIX = "AccountId";
  private static final String FG_COGS_ACCOUNT_ID_KEY = "fgCogsAccountId";
  private static final String FG_REVENUE_ACCOUNT_ID_KEY = "fgRevenueAccountId";
  private static final String FG_VALUATION_ACCOUNT_ID_KEY = "fgValuationAccountId";
  private static final List<String> RAW_MATERIAL_CATEGORIES =
      List.of("RAW_MATERIAL", "RAW MATERIAL", "RAW-MATERIAL");
  private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");

  private final CompanyContextService companyContextService;
  private final CompanyScopedProductionLookupService productionLookupService;
  private final ProductionBrandRepository brandRepository;
  private final ProductionProductRepository productRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final SkuReadinessService skuReadinessService;
  private final ProductionCatalogService productionCatalogService;

  @Autowired
  public CatalogService(
      CompanyContextService companyContextService,
      CompanyScopedProductionLookupService productionLookupService,
      ProductionBrandRepository brandRepository,
      ProductionProductRepository productRepository,
      FinishedGoodRepository finishedGoodRepository,
      RawMaterialRepository rawMaterialRepository,
      SkuReadinessService skuReadinessService,
      ProductionCatalogService productionCatalogService) {
    this.companyContextService = companyContextService;
    this.productionLookupService = productionLookupService;
    this.brandRepository = brandRepository;
    this.productRepository = productRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.skuReadinessService = skuReadinessService;
    this.productionCatalogService = productionCatalogService;
  }

  @Transactional
  public CatalogBrandDto createBrand(CatalogBrandRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String name = normalizeRequiredText(request.name(), "Brand name is required");
    assertBrandNameUnique(company, name, null);

    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setName(name);
    brand.setCode(generateBrandCode(company, name));
    brand.setLogoUrl(normalizeOptionalText(request.logoUrl()));
    brand.setDescription(normalizeOptionalText(request.description()));
    brand.setActive(request.active() == null || request.active());
    return toBrandDto(brandRepository.save(brand));
  }

  @Transactional(readOnly = true)
  public List<CatalogBrandDto> listBrands(Boolean active) {
    Company company = companyContextService.requireCurrentCompany();
    List<ProductionBrand> brands =
        active == null
            ? brandRepository.findByCompanyOrderByNameAsc(company)
            : brandRepository.findByCompanyAndActiveOrderByNameAsc(company, active);
    return brands.stream().map(this::toBrandDto).toList();
  }

  @Transactional(readOnly = true)
  public CatalogBrandDto getBrand(Long brandId) {
    Company company = companyContextService.requireCurrentCompany();
    return toBrandDto(requireBrand(company, brandId));
  }

  @Transactional
  public CatalogBrandDto updateBrand(Long brandId, CatalogBrandRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionBrand brand = requireBrand(company, brandId);
    String name = normalizeRequiredText(request.name(), "Brand name is required");
    assertBrandNameUnique(company, name, brand.getId());

    brand.setName(name);
    brand.setLogoUrl(normalizeOptionalText(request.logoUrl()));
    brand.setDescription(normalizeOptionalText(request.description()));
    if (request.active() != null) {
      brand.setActive(request.active());
    }
    return toBrandDto(brandRepository.save(brand));
  }

  @Transactional
  public CatalogBrandDto deactivateBrand(Long brandId) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionBrand brand = requireBrand(company, brandId);
    brand.setActive(false);
    return toBrandDto(brandRepository.save(brand));
  }

  @Transactional
  public CatalogItemDto createItem(CatalogItemRequest request) {
    var created = productionCatalogService.createCatalogItem(toCreateCommand(request));
    return getItem(created.id(), true, true, true);
  }

  @Transactional(readOnly = true)
  public CatalogItemDto getItem(
      Long itemId,
      boolean includeStock,
      boolean includeReadiness,
      boolean includeAccountingMetadata) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionProduct product = requireProduct(company, itemId);
    RawMaterial rawMaterial = rawMaterialForProduct(product);
    FinishedGood finishedGood = finishedGoodForProduct(product);
    com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness =
        includeReadiness ? skuReadinessService.forProduct(company, product) : null;
    return toItemDto(
        product, includeAccountingMetadata, includeStock, readiness, rawMaterial, finishedGood);
  }

  @Transactional
  public CatalogItemDto updateItem(Long itemId, CatalogItemRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionProduct product = requireProduct(company, itemId);
    if (!Objects.equals(product.getBrand().getId(), request.brandId())) {
      throw ValidationUtils.invalidInput(
          "brandId is immutable for existing items; create a new item instead");
    }
    productionCatalogService.updateCatalogItem(itemId, toUpdateCommand(request));
    return getItem(itemId, true, true, true);
  }

  @Transactional
  public CatalogItemDto deactivateItem(Long itemId) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionProduct product = requireProduct(company, itemId);
    product.setActive(false);
    ProductionProduct saved = productRepository.save(product);
    RawMaterial rawMaterial = rawMaterialForProduct(saved);
    FinishedGood finishedGood = finishedGoodForProduct(saved);
    return toItemDto(
        saved,
        false,
        true,
        skuReadinessService.forProduct(company, saved),
        rawMaterial,
        finishedGood);
  }

  @Transactional(readOnly = true)
  public PageResponse<CatalogItemDto> searchItems(
      String q,
      String itemClass,
      boolean includeStock,
      boolean includeReadiness,
      int page,
      int pageSize,
      boolean includeAccountingMetadata) {
    Company company = companyContextService.requireCurrentCompany();
    int sanitizedPage = Math.max(page, 0);
    int sanitizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    String normalizedItemClass =
        StringUtils.hasText(itemClass) ? normalizeItemClass(itemClass) : null;
    Pageable pageable =
        PageRequest.of(
            sanitizedPage, sanitizedPageSize, Sort.by(Sort.Direction.ASC, "productName"));
    Page<ProductionProduct> result =
        productRepository.findAll(
            buildItemSpecification(company, normalizeOptionalText(q), normalizedItemClass),
            pageable);
    List<ProductionProduct> pageContent = result.getContent();
    Map<String, RawMaterial> rawMaterialsBySku = rawMaterialsBySku(company, pageContent);
    Map<String, FinishedGood> finishedGoodsBySku = finishedGoodsBySku(company, pageContent);
    Map<Long, com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto> readinessByProductId =
        includeReadiness ? skuReadinessService.forProducts(company, pageContent) : Map.of();

    List<CatalogItemDto> content =
        pageContent.stream()
            .map(
                product -> {
                  String skuKey = normalizeSkuLookupKey(product.getSkuCode());
                  RawMaterial rawMaterial = skuKey != null ? rawMaterialsBySku.get(skuKey) : null;
                  FinishedGood finishedGood =
                      skuKey != null ? finishedGoodsBySku.get(skuKey) : null;
                  return toItemDto(
                      product,
                      includeAccountingMetadata,
                      includeStock,
                      readinessByProductId.get(product.getId()),
                      rawMaterial,
                      finishedGood);
                })
            .toList();
    return PageResponse.of(content, result.getTotalElements(), sanitizedPage, sanitizedPageSize);
  }

  private Specification<ProductionProduct> buildItemSpecification(
      Company company, String q, String itemClass) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("company"), company));
      if (StringUtils.hasText(q)) {
        String token = "%" + q.toLowerCase(Locale.ROOT) + "%";
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("productName")), token),
                cb.like(cb.lower(root.get("skuCode")), token),
                cb.like(cb.lower(root.get("brand").get("name")), token)));
      }
      if (StringUtils.hasText(itemClass)) {
        String normalizedItemClass = normalizeItemClass(itemClass);
        switch (normalizedItemClass) {
          case ITEM_CLASS_FINISHED_GOOD ->
              predicates.add(cb.notEqual(cb.upper(root.get("category")), ITEM_CLASS_RAW_MATERIAL));
          case ITEM_CLASS_RAW_MATERIAL, ITEM_CLASS_PACKAGING_RAW_MATERIAL -> {
            predicates.add(cb.equal(cb.upper(root.get("category")), ITEM_CLASS_RAW_MATERIAL));
            var rawMaterialMatch = query.subquery(Long.class);
            var rawMaterialRoot = rawMaterialMatch.from(RawMaterial.class);
            MaterialType expectedType =
                ITEM_CLASS_PACKAGING_RAW_MATERIAL.equals(normalizedItemClass)
                    ? MaterialType.PACKAGING
                    : MaterialType.PRODUCTION;
            rawMaterialMatch.select(rawMaterialRoot.get("id"));
            rawMaterialMatch.where(
                cb.equal(rawMaterialRoot.get("company"), company),
                cb.equal(cb.lower(rawMaterialRoot.get("sku")), cb.lower(root.get("skuCode"))),
                cb.equal(rawMaterialRoot.get("materialType"), expectedType));
            predicates.add(cb.exists(rawMaterialMatch));
          }
          default -> {}
        }
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  private CatalogItemCreateCommand toCreateCommand(CatalogItemRequest request) {
    Map<String, Object> metadata = requestMetadataWithFinishedGoodAccountOverrides(request);
    return new CatalogItemCreateCommand(
        request.brandId(),
        null,
        null,
        request.name(),
        null,
        request.itemClass(),
        request.color(),
        request.size(),
        request.unitOfMeasure(),
        request.hsnCode(),
        null,
        request.basePrice(),
        request.gstRate(),
        request.minDiscountPercent(),
        request.minSellingPrice(),
        metadata,
        request.active());
  }

  private CatalogItemUpdateCommand toUpdateCommand(CatalogItemRequest request) {
    Map<String, Object> metadata = requestMetadataWithFinishedGoodAccountOverrides(request);
    return new CatalogItemUpdateCommand(
        request.name(),
        null,
        request.itemClass(),
        request.color(),
        request.size(),
        request.unitOfMeasure(),
        request.hsnCode(),
        request.basePrice(),
        request.gstRate(),
        request.minDiscountPercent(),
        request.minSellingPrice(),
        metadata,
        request.active());
  }

  private Map<String, Object> requestMetadataWithFinishedGoodAccountOverrides(
      CatalogItemRequest request) {
    if (request == null || !hasExplicitFinishedGoodAccountOverrides(request)) {
      return request != null ? request.metadata() : null;
    }
    if (!ITEM_CLASS_FINISHED_GOOD.equals(normalizeItemClass(request.itemClass()))) {
      return request.metadata();
    }
    LinkedHashMap<String, Object> metadata =
        request.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.metadata());
    putFinishedGoodAccountOverride(
        metadata, "cogsAccountId", FG_COGS_ACCOUNT_ID_KEY, request.cogsAccountId());
    putFinishedGoodAccountOverride(
        metadata, "revenueAccountId", FG_REVENUE_ACCOUNT_ID_KEY, request.revenueAccountId());
    putFinishedGoodAccountOverride(
        metadata, "inventoryAccountId", FG_VALUATION_ACCOUNT_ID_KEY, request.inventoryAccountId());
    return metadata;
  }

  private boolean hasExplicitFinishedGoodAccountOverrides(CatalogItemRequest request) {
    return request.cogsAccountId() != null
        || request.revenueAccountId() != null
        || request.inventoryAccountId() != null;
  }

  private void putFinishedGoodAccountOverride(
      Map<String, Object> metadata, String requestFieldName, String metadataKey, Long accountId) {
    if (accountId == null) {
      return;
    }
    if (accountId <= 0) {
      throw ValidationUtils.invalidInput(requestFieldName + " must be a positive account id");
    }
    metadata.put(metadataKey, accountId);
  }

  private String normalizeItemClass(String itemClass) {
    if (!StringUtils.hasText(itemClass)) {
      throw ValidationUtils.invalidInput(
          "itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
    }
    String normalized =
        itemClass.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case ITEM_CLASS_RAW_MATERIAL, "PRODUCTION", "PRODUCTION_RAW_MATERIAL" ->
          ITEM_CLASS_RAW_MATERIAL;
      case ITEM_CLASS_PACKAGING_RAW_MATERIAL, "PACKAGING" -> ITEM_CLASS_PACKAGING_RAW_MATERIAL;
      case ITEM_CLASS_FINISHED_GOOD -> ITEM_CLASS_FINISHED_GOOD;
      default ->
          throw ValidationUtils.invalidInput(
              "itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
    };
  }

  private String normalizeSkuKey(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeSkuLookupKey(String value) {
    String normalized = normalizeSkuKey(value);
    return StringUtils.hasText(normalized) ? normalized.toLowerCase(Locale.ROOT) : null;
  }

  private Map<String, RawMaterial> rawMaterialsBySku(
      Company company, List<ProductionProduct> products) {
    if (company == null || products == null || products.isEmpty()) {
      return Map.of();
    }
    List<String> skus =
        products.stream()
            .map(ProductionProduct::getSkuCode)
            .map(this::normalizeSkuLookupKey)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (skus.isEmpty()) {
      return Map.of();
    }
    return rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(company, skus).stream()
        .filter(rawMaterial -> StringUtils.hasText(rawMaterial.getSku()))
        .collect(
            Collectors.toMap(
                rawMaterial -> normalizeSkuLookupKey(rawMaterial.getSku()),
                rawMaterial -> rawMaterial,
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private RawMaterial rawMaterialForProduct(ProductionProduct product) {
    if (product == null
        || !isRawMaterialCategory(product.getCategory())
        || !StringUtils.hasText(product.getSkuCode())) {
      return null;
    }
    return rawMaterialRepository
        .findByCompanyAndSkuIgnoreCase(product.getCompany(), product.getSkuCode())
        .orElse(null);
  }

  private boolean isRawMaterialCategory(String category) {
    if (!StringUtils.hasText(category)) {
      return false;
    }
    String normalized = category.replace('-', '_').toUpperCase(Locale.ROOT);
    return RAW_MATERIAL_CATEGORIES.stream().anyMatch(normalized::equalsIgnoreCase);
  }

  private ProductionBrand requireBrand(Company company, Long brandId) {
    return productionLookupService.requireProductionBrand(company, brandId);
  }

  private ProductionBrand requireActiveBrand(Company company, Long brandId) {
    ProductionBrand brand = requireBrand(company, brandId);
    if (!brand.isActive()) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Brand '" + brand.getName() + "' is inactive");
    }
    return brand;
  }

  private ProductionProduct requireProduct(Company company, Long productId) {
    return productionLookupService.requireProductionProduct(company, productId);
  }

  private void assertBrandNameUnique(Company company, String name, Long currentBrandId) {
    Optional<ProductionBrand> existing =
        brandRepository.findByCompanyAndNameIgnoreCase(company, name);
    if (existing.isPresent()
        && (currentBrandId == null || !existing.get().getId().equals(currentBrandId))) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_DUPLICATE_ENTRY, "Brand with name '" + name + "' already exists");
    }
  }

  private String generateBrandCode(Company company, String brandName) {
    String base = sanitizeCode(brandName);
    String candidate = base;
    int sequence = 1;
    while (brandRepository.findByCompanyAndCodeIgnoreCase(company, candidate).isPresent()) {
      candidate = base + String.format(Locale.ROOT, "%02d", sequence++);
    }
    return candidate;
  }

  private String sanitizeCode(String source) {
    String cleaned =
        NON_ALPHANUM
            .matcher(
                normalizeRequiredText(source, "Unable to derive code from blank source")
                    .toUpperCase(Locale.ROOT))
            .replaceAll("");
    if (!StringUtils.hasText(cleaned)) {
      return "CAT";
    }
    return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
  }

  private String normalizeRequiredText(String value, String message) {
    String normalized = normalizeOptionalText(value);
    if (!StringUtils.hasText(normalized)) {
      throw ValidationUtils.invalidInput(message);
    }
    return normalized;
  }

  private String normalizeOptionalText(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private CatalogBrandDto toBrandDto(ProductionBrand brand) {
    return new CatalogBrandDto(
        brand.getId(),
        brand.getPublicId(),
        brand.getName(),
        brand.getCode(),
        brand.getLogoUrl(),
        brand.getDescription(),
        brand.isActive());
  }

  private Map<String, FinishedGood> finishedGoodsBySku(
      Company company, List<ProductionProduct> products) {
    if (company == null || products == null || products.isEmpty()) {
      return Map.of();
    }
    List<String> lookupKeys =
        products.stream()
            .map(ProductionProduct::getSkuCode)
            .map(this::normalizeSkuLookupKey)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (lookupKeys.isEmpty()) {
      return Map.of();
    }
    return finishedGoodRepository
        .findByCompanyAndProductCodeInIgnoreCase(company, lookupKeys)
        .stream()
        .filter(finishedGood -> StringUtils.hasText(finishedGood.getProductCode()))
        .collect(
            Collectors.toMap(
                finishedGood -> normalizeSkuLookupKey(finishedGood.getProductCode()),
                finishedGood -> finishedGood,
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private FinishedGood finishedGoodForProduct(ProductionProduct product) {
    if (product == null
        || product.getCompany() == null
        || !StringUtils.hasText(product.getSkuCode())) {
      return null;
    }
    return finishedGoodRepository
        .findByCompanyAndProductCodeIgnoreCase(product.getCompany(), product.getSkuCode())
        .orElse(null);
  }

  private CatalogItemDto toItemDto(
      ProductionProduct product,
      boolean includeAccountingMetadata,
      boolean includeStock,
      com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readinessSnapshot,
      RawMaterial rawMaterial,
      FinishedGood finishedGood) {
    Map<String, Object> metadata =
        snapshotMetadata(product.getMetadata(), includeAccountingMetadata);
    com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness =
        readinessSnapshot == null
            ? null
            : skuReadinessService.sanitizeForCatalogViewer(
                readinessSnapshot, includeAccountingMetadata);
    String itemClass = itemClassForProduct(product, rawMaterial);
    RawMaterial resolvedRawMaterial =
        ITEM_CLASS_FINISHED_GOOD.equals(itemClass) ? null : rawMaterial;
    CatalogItemStockDto stock =
        includeStock ? toItemStock(product, resolvedRawMaterial, finishedGood) : null;
    return new CatalogItemDto(
        product.getId(),
        product.getPublicId(),
        resolvedRawMaterial != null ? resolvedRawMaterial.getId() : null,
        product.getBrand().getId(),
        product.getBrand().getName(),
        product.getBrand().getCode(),
        product.getVariantGroupId(),
        product.getProductFamilyName(),
        product.getProductName(),
        product.getSkuCode(),
        itemClass,
        product.getDefaultColour(),
        product.getSizeLabel(),
        product.getUnitOfMeasure(),
        product.getHsnCode(),
        product.getBasePrice(),
        product.getGstRate(),
        product.getMinDiscountPercent(),
        product.getMinSellingPrice(),
        metadata,
        product.isActive(),
        stock,
        readiness);
  }

  private CatalogItemStockDto toItemStock(
      ProductionProduct product, RawMaterial rawMaterial, FinishedGood finishedGood) {
    String itemClass = itemClassForProduct(product, rawMaterial);
    if (ITEM_CLASS_FINISHED_GOOD.equals(itemClass) && finishedGood != null) {
      BigDecimal onHand =
          Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
      BigDecimal reserved =
          Optional.ofNullable(finishedGood.getReservedStock()).orElse(BigDecimal.ZERO);
      BigDecimal available = onHand.subtract(reserved);
      if (available.compareTo(BigDecimal.ZERO) < 0) {
        available = BigDecimal.ZERO;
      }
      return new CatalogItemStockDto(onHand, reserved, available, finishedGood.getUnit());
    }
    BigDecimal onHand =
        rawMaterial != null
            ? Optional.ofNullable(rawMaterial.getCurrentStock()).orElse(BigDecimal.ZERO)
            : BigDecimal.ZERO;
    return new CatalogItemStockDto(onHand, BigDecimal.ZERO, onHand, product.getUnitOfMeasure());
  }

  private String itemClassForProduct(ProductionProduct product, RawMaterial rawMaterial) {
    if (product == null || !isRawMaterialCategory(product.getCategory())) {
      return ITEM_CLASS_FINISHED_GOOD;
    }
    if (rawMaterial != null && rawMaterial.getMaterialType() != null) {
      return rawMaterial.getMaterialType() == MaterialType.PACKAGING
          ? ITEM_CLASS_PACKAGING_RAW_MATERIAL
          : ITEM_CLASS_RAW_MATERIAL;
    }
    return ITEM_CLASS_RAW_MATERIAL;
  }

  private Map<String, Object> snapshotMetadata(
      Map<String, Object> metadata, boolean includeAccountingMetadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
    metadata.forEach(
        (key, value) -> {
          if (includeAccountingMetadata
              || key == null
              || !key.endsWith(ACCOUNTING_METADATA_KEY_SUFFIX)) {
            snapshot.put(key, value);
          }
        });
    return snapshot.isEmpty() ? Map.of() : Collections.unmodifiableMap(snapshot);
  }
}
