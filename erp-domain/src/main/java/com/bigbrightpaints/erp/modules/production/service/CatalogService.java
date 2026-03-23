package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
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
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemStockDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkItemResult;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogService {

    private static final String DEFAULT_PRODUCT_CATEGORY = "FINISHED_GOOD";
    private static final String ITEM_CLASS_FINISHED_GOOD = "FINISHED_GOOD";
    private static final String ITEM_CLASS_RAW_MATERIAL = "RAW_MATERIAL";
    private static final String ITEM_CLASS_PACKAGING_RAW_MATERIAL = "PACKAGING_RAW_MATERIAL";
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ACCOUNTING_METADATA_KEY_SUFFIX = "AccountId";
    private static final List<String> RAW_MATERIAL_CATEGORIES = List.of("RAW_MATERIAL", "RAW MATERIAL", "RAW-MATERIAL");
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern SKU_SEQUENCE_PATTERN = Pattern.compile("-(\\d{3})$");
    private static final Pattern SIZE_WITH_UNIT_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(ML|L|LTR|LITRE|LITER)?$");
    private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");
    private static final List<String> FINISHED_GOOD_ACCOUNT_KEYS = List.of(
            "fgValuationAccountId",
            "fgCogsAccountId",
            "fgRevenueAccountId",
            "fgDiscountAccountId",
            "fgTaxAccountId");
    private static final Validator BULK_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final CompanyContextService companyContextService;
    private final CompanyEntityLookup companyEntityLookup;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final SizeVariantRepository sizeVariantRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final SkuReadinessService skuReadinessService;
    private final ProductionCatalogService productionCatalogService;

    public CatalogService(CompanyContextService companyContextService,
                          CompanyEntityLookup companyEntityLookup,
                          ProductionBrandRepository brandRepository,
                          ProductionProductRepository productRepository,
                          SizeVariantRepository sizeVariantRepository,
                          FinishedGoodRepository finishedGoodRepository,
                          RawMaterialRepository rawMaterialRepository,
                          SkuReadinessService skuReadinessService,
                          ProductionCatalogService productionCatalogService) {
        this.companyContextService = companyContextService;
        this.companyEntityLookup = companyEntityLookup;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.sizeVariantRepository = sizeVariantRepository;
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
        List<ProductionBrand> brands = active == null
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
        var created = productionCatalogService.createProduct(toCreateRequest(request));
        return getItem(created.id(), true, true, true);
    }

    @Transactional(readOnly = true)
    public CatalogItemDto getItem(Long itemId,
                                  boolean includeStock,
                                  boolean includeReadiness,
                                  boolean includeAccountingMetadata) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, itemId);
        RawMaterial rawMaterial = rawMaterialForProduct(product);
        FinishedGood finishedGood = finishedGoodForProduct(product);
        com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness = includeReadiness
                ? skuReadinessService.forProduct(company, product)
                : null;
        return toItemDto(product, includeAccountingMetadata, includeStock, readiness, rawMaterial, finishedGood);
    }

    @Transactional
    public CatalogItemDto updateItem(Long itemId, CatalogItemRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, itemId);
        if (!Objects.equals(product.getBrand().getId(), request.brandId())) {
            throw ValidationUtils.invalidInput("brandId is immutable for existing items; create a new item instead");
        }
        productionCatalogService.updateProduct(itemId, toUpdateRequest(request));
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
        return toItemDto(saved, false, true, skuReadinessService.forProduct(company, saved), rawMaterial, finishedGood);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogItemDto> searchItems(String q,
                                                    String itemClass,
                                                    boolean includeStock,
                                                    boolean includeReadiness,
                                                    int page,
                                                    int pageSize,
                                                    boolean includeAccountingMetadata) {
        Company company = companyContextService.requireCurrentCompany();
        int sanitizedPage = Math.max(page, 0);
        int sanitizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String normalizedItemClass = StringUtils.hasText(itemClass) ? normalizeItemClass(itemClass) : null;
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedPageSize, Sort.by(Sort.Direction.ASC, "productName"));
        Page<ProductionProduct> result = productRepository.findAll(
                buildItemSpecification(company, normalizeOptionalText(q), normalizedItemClass),
                pageable);
        List<ProductionProduct> pageContent = result.getContent();
        Map<String, RawMaterial> rawMaterialsBySku = rawMaterialsBySku(company, pageContent);
        Map<String, FinishedGood> finishedGoodsBySku = finishedGoodsBySku(company, pageContent);
        Map<Long, com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto> readinessByProductId = includeReadiness
                ? skuReadinessService.forProducts(company, pageContent)
                : Map.of();

        List<CatalogItemDto> content = pageContent.stream()
                .map(product -> {
                    String skuKey = normalizeSkuLookupKey(product.getSkuCode());
                    RawMaterial rawMaterial = skuKey != null ? rawMaterialsBySku.get(skuKey) : null;
                    FinishedGood finishedGood = skuKey != null ? finishedGoodsBySku.get(skuKey) : null;
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

    @Transactional
    public CatalogProductDto createProduct(CatalogProductRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionBrand brand = requireActiveBrand(company, request.brandId());
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(generateSku(company, brand, request.name()));
        String itemClass = applyProductPayload(product, brand, request, true, null, null);
        ProductionProduct saved = productRepository.save(product);
        syncInventoryTruth(company, saved, itemClass);
        syncSizeVariants(company, saved);
        return toProductDto(saved);
    }

    @Transactional(readOnly = true)
    public CatalogProductDto getProduct(Long productId) {
        return getProduct(productId, false);
    }

    @Transactional(readOnly = true)
    public CatalogProductDto getProduct(Long productId, boolean includeAccountingMetadata) {
        Company company = companyContextService.requireCurrentCompany();
        return toProductDto(requireProduct(company, productId), includeAccountingMetadata);
    }

    @Transactional
    public CatalogProductDto updateProduct(Long productId, CatalogProductRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, productId);
        ProductionBrand brand = requireActiveBrand(company, request.brandId());
        String previousProductName = product.getProductName();
        String previousProductFamilyName = product.getProductFamilyName();
        product.setBrand(brand);
        String itemClass = applyProductPayload(product, brand, request, false, previousProductName, previousProductFamilyName);
        validateInventorySyncMetadata(company, product);
        ProductionProduct saved = productRepository.save(product);
        syncInventoryTruth(company, saved, itemClass);
        syncSizeVariants(company, saved);
        return toProductDto(saved);
    }

    @Transactional
    public CatalogProductDto deactivateProduct(Long productId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, productId);
        product.setActive(false);
        return toPublicProductDto(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogProductDto> searchProducts(Long brandId,
                                                          String color,
                                                          String size,
                                                          Boolean active,
                                                          int page,
                                                          int pageSize) {
        return searchProducts(brandId, color, size, active, page, pageSize, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogProductDto> searchProducts(Long brandId,
                                                          String color,
                                                          String size,
                                                          Boolean active,
                                                          int page,
                                                          int pageSize,
                                                          boolean includeAccountingMetadata) {
        Company company = companyContextService.requireCurrentCompany();
        int sanitizedPage = Math.max(page, 0);
        int sanitizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedPageSize, Sort.by(Sort.Direction.ASC, "productName"));
        Specification<ProductionProduct> specification = buildProductSpecification(
                company,
                brandId,
                normalizeOptionalText(color),
                normalizeOptionalText(size),
                active);
        Page<ProductionProduct> result = productRepository.findAll(specification, pageable);
        Map<Long, com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto> readinessByProductId =
                skuReadinessService.forProducts(company, result.getContent());
        Map<String, RawMaterial> rawMaterialsBySku = rawMaterialsBySku(company, result.getContent());
        List<CatalogProductDto> content = result.getContent().stream()
                .map(product -> toProductDto(
                        product,
                        includeAccountingMetadata,
                        readinessByProductId.get(product.getId()),
                        rawMaterialsBySku.get(normalizeSkuLookupKey(product.getSkuCode()))))
                .toList();
        return PageResponse.of(content, result.getTotalElements(), sanitizedPage, sanitizedPageSize);
    }

    public CatalogProductBulkResponse bulkUpsertProducts(List<CatalogProductBulkItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw ValidationUtils.invalidInput("Bulk request must contain at least one product payload");
        }
        Company company = companyContextService.requireCurrentCompany();
        List<CatalogProductBulkItemResult> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;

        for (int index = 0; index < requests.size(); index++) {
            CatalogProductBulkItemRequest item = requests.get(index);
            try {
                validateBulkItem(item);

                ProductMutationOutcome outcome = upsertBulkItem(company, item);
                results.add(new CatalogProductBulkItemResult(
                        index,
                        true,
                        outcome.action(),
                        outcome.product().id(),
                        outcome.product().sku(),
                        "OK",
                        outcome.product()));
                succeeded++;
            } catch (ApplicationException ex) {
                results.add(new CatalogProductBulkItemResult(
                        index,
                        false,
                        "FAILED",
                        null,
                        normalizeOptionalText(item != null ? item.sku() : null),
                        ex.getMessage(),
                        null));
                failed++;
            } catch (Exception ex) {
                String message = resolveBulkFailureMessage(ex);
                results.add(new CatalogProductBulkItemResult(
                        index,
                        false,
                        "FAILED",
                        null,
                        normalizeOptionalText(item != null ? item.sku() : null),
                        message,
                        null));
                failed++;
            }
        }

        return new CatalogProductBulkResponse(requests.size(), succeeded, failed, results);
    }

    private void validateBulkItem(CatalogProductBulkItemRequest item) {
        if (item == null || item.product() == null) {
            throw ValidationUtils.invalidInput("product: payload is required");
        }
        Set<ConstraintViolation<CatalogProductRequest>> violations = BULK_VALIDATOR.validate(item.product());
        if (!violations.isEmpty()) {
            throw ValidationUtils.invalidInput(formatConstraintViolations(violations));
        }
    }

    private String resolveBulkFailureMessage(Exception ex) {
        String validationMessage = extractValidationMessage(ex);
        if (validationMessage != null) {
            return validationMessage;
        }
        return "Unexpected error: " + ex.getMessage();
    }

    private String extractValidationMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve
                    && cve.getConstraintViolations() != null
                    && !cve.getConstraintViolations().isEmpty()) {
                return formatConstraintViolations(cve.getConstraintViolations());
            }
            current = current.getCause();
        }
        return null;
    }

    private String formatConstraintViolations(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
    }

    private ProductMutationOutcome upsertBulkItem(Company company, CatalogProductBulkItemRequest item) {
        ProductionProduct target = resolveBulkTarget(company, item.id(), item.sku());
        if (target == null) {
            CatalogProductDto created = createProduct(item.product());
            return new ProductMutationOutcome("CREATED", created);
        }
        CatalogProductDto updated = updateProduct(target.getId(), item.product());
        return new ProductMutationOutcome("UPDATED", updated);
    }

    private ProductionProduct resolveBulkTarget(Company company, Long id, String sku) {
        String normalizedSku = normalizeOptionalText(sku);
        ProductionProduct byId = null;
        if (id != null) {
            byId = requireProduct(company, id);
        }
        ProductionProduct bySku = null;
        if (StringUtils.hasText(normalizedSku)) {
            bySku = productRepository.findByCompanyAndSkuCode(company, normalizedSku)
                    .orElseThrow(() -> new ApplicationException(
                            ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                            "Product not found for SKU " + normalizedSku));
        }

        if (byId != null && bySku != null && !byId.getId().equals(bySku.getId())) {
            throw ValidationUtils.invalidInput("Bulk item id and sku refer to different products");
        }
        return byId != null ? byId : bySku;
    }

    private Specification<ProductionProduct> buildProductSpecification(Company company,
                                                                       Long brandId,
                                                                       String color,
                                                                       String size,
                                                                       Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company"), company));

            if (brandId != null) {
                predicates.add(cb.equal(root.get("brand").get("id"), brandId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (StringUtils.hasText(color)) {
                String normalizedColor = color.toLowerCase(Locale.ROOT);
                Join<ProductionProduct, String> colors = root.joinSet("colors", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(cb.lower(root.get("defaultColour")), normalizedColor),
                        cb.equal(cb.lower(colors), normalizedColor)));
                query.distinct(true);
            }
            if (StringUtils.hasText(size)) {
                String normalizedSize = size.toLowerCase(Locale.ROOT);
                Join<ProductionProduct, String> sizes = root.joinSet("sizes", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(cb.lower(root.get("sizeLabel")), normalizedSize),
                        cb.equal(cb.lower(sizes), normalizedSize)));
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Specification<ProductionProduct> buildItemSpecification(Company company, String q, String itemClass) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company"), company));
            if (StringUtils.hasText(q)) {
                String token = "%" + q.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("productName")), token),
                        cb.like(cb.lower(root.get("skuCode")), token),
                        cb.like(cb.lower(root.get("brand").get("name")), token)));
            }
            if (StringUtils.hasText(itemClass)) {
                String normalizedItemClass = normalizeItemClass(itemClass);
                switch (normalizedItemClass) {
                    case ITEM_CLASS_FINISHED_GOOD -> predicates.add(cb.notEqual(cb.upper(root.get("category")), ITEM_CLASS_RAW_MATERIAL));
                    case ITEM_CLASS_RAW_MATERIAL, ITEM_CLASS_PACKAGING_RAW_MATERIAL -> {
                        predicates.add(cb.equal(cb.upper(root.get("category")), ITEM_CLASS_RAW_MATERIAL));
                        var rawMaterialMatch = query.subquery(Long.class);
                        var rawMaterialRoot = rawMaterialMatch.from(RawMaterial.class);
                        MaterialType expectedType = ITEM_CLASS_PACKAGING_RAW_MATERIAL.equals(normalizedItemClass)
                                ? MaterialType.PACKAGING
                                : MaterialType.PRODUCTION;
                        rawMaterialMatch.select(rawMaterialRoot.get("id"));
                        rawMaterialMatch.where(
                                cb.equal(rawMaterialRoot.get("company"), company),
                                cb.equal(cb.lower(rawMaterialRoot.get("sku")), cb.lower(root.get("skuCode"))),
                                cb.equal(rawMaterialRoot.get("materialType"), expectedType));
                        predicates.add(cb.exists(rawMaterialMatch));
                    }
                    default -> {
                    }
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private ProductCreateRequest toCreateRequest(CatalogItemRequest request) {
        return new ProductCreateRequest(
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
                request.metadata(),
                request.active());
    }

    private ProductUpdateRequest toUpdateRequest(CatalogItemRequest request) {
        return new ProductUpdateRequest(
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
                request.metadata(),
                request.active());
    }

    private String applyProductPayload(ProductionProduct product,
                                       ProductionBrand brand,
                                       CatalogProductRequest request,
                                       boolean creating,
                                       String previousProductName,
                                       String previousProductFamilyName) {
        String name = normalizeRequiredText(request.name(), "Product name is required");
        String itemClass = resolveRequestedItemClass(product, request.itemClass(), creating);
        Set<String> colors = normalizeOptions(request.colors(), "colors");
        Set<String> sizes = normalizeOptions(request.sizes(), "sizes");
        Map<String, Integer> cartonSizes = normalizeCartonSizes(request.cartonSizes(), sizes);
        assertUniqueProductName(brand, name, creating ? null : product.getId());

        product.setProductName(name);
        product.setCategory(categoryForItemClass(itemClass));
        product.setDefaultColour(colors.stream().findFirst().orElse(null));
        product.setSizeLabel(sizes.stream().findFirst().orElse(null));
        product.setColors(colors);
        product.setSizes(sizes);
        product.setCartonSizes(cartonSizes);
        product.setUnitOfMeasure(normalizeRequiredText(request.unitOfMeasure(), "Unit of measure is required"));
        product.setHsnCode(normalizeRequiredText(request.hsnCode(), "HSN code is required"));
        if (request.basePrice() != null || creating) {
            product.setBasePrice(normalizeMoney(request.basePrice()));
        }
        product.setGstRate(normalizeRate(request.gstRate()));
        if (request.minDiscountPercent() != null || creating) {
            product.setMinDiscountPercent(normalizeOptionalRate(request.minDiscountPercent()));
        }
        if (request.minSellingPrice() != null || creating) {
            product.setMinSellingPrice(normalizeMoney(request.minSellingPrice()));
        }
        if (request.metadata() != null) {
            product.setMetadata(creating
                    ? normalizeMetadata(request.metadata())
                    : mergeMetadata(product.getMetadata(), request.metadata()));
        } else if (creating) {
            product.setMetadata(new LinkedHashMap<>());
        }
        if (creating) {
            product.setActive(request.active() == null || request.active());
        } else if (request.active() != null) {
            product.setActive(request.active());
        }
        refreshCanonicalFamilyLinkage(product, brand, previousProductName, previousProductFamilyName, itemClass);
        return itemClass;
    }

    private void syncInventoryTruth(Company company, ProductionProduct product, String itemClass) {
        if (company == null || product == null || product.getId() == null) {
            return;
        }
        if (isRawMaterialCategory(product.getCategory())) {
            syncRawMaterial(company, product, itemClass);
            deleteFinishedGoodMirror(company, product);
            return;
        }
        syncFinishedGood(company, product);
        deleteRawMaterialMirror(company, product);
    }

    private void refreshCanonicalFamilyLinkage(ProductionProduct product,
                                               ProductionBrand brand,
                                               String previousProductName,
                                               String previousProductFamilyName,
                                               String itemClass) {
        if (product.getVariantGroupId() == null && !StringUtils.hasText(product.getProductFamilyName())) {
            return;
        }
        String productFamilyName = resolveCanonicalProductFamilyName(
                product.getProductName(),
                previousProductName,
                previousProductFamilyName);
        product.setProductFamilyName(productFamilyName);
        product.setVariantGroupId(buildVariantGroupId(
                product.getCompany(),
                brand,
                productFamilyName,
                itemClass,
                product.getUnitOfMeasure(),
                product.getHsnCode()));
    }

    private String resolveCanonicalProductFamilyName(String currentProductName,
                                                     String previousProductName,
                                                     String previousProductFamilyName) {
        String normalizedCurrentProductName = normalizeRequiredText(currentProductName, "Product name is required");
        String normalizedPreviousProductName = normalizeOptionalText(previousProductName);
        String normalizedPreviousProductFamilyName = normalizeOptionalText(previousProductFamilyName);
        if (!StringUtils.hasText(normalizedPreviousProductFamilyName)) {
            return normalizedCurrentProductName;
        }
        if (Objects.equals(normalizedCurrentProductName, normalizedPreviousProductName)) {
            return normalizedPreviousProductFamilyName;
        }
        String canonicalMemberSuffix = extractCanonicalMemberSuffix(
                normalizedPreviousProductName,
                normalizedPreviousProductFamilyName);
        if (StringUtils.hasText(canonicalMemberSuffix) && normalizedCurrentProductName.endsWith(canonicalMemberSuffix)) {
            String derivedFamilyName = normalizeOptionalText(
                    normalizedCurrentProductName.substring(
                            0,
                            normalizedCurrentProductName.length() - canonicalMemberSuffix.length()));
            if (StringUtils.hasText(derivedFamilyName)) {
                return derivedFamilyName;
            }
        }
        return normalizedCurrentProductName;
    }

    private String extractCanonicalMemberSuffix(String productName, String productFamilyName) {
        if (!StringUtils.hasText(productName) || !StringUtils.hasText(productFamilyName)) {
            return null;
        }
        if (!productName.startsWith(productFamilyName)) {
            return null;
        }
        return normalizeOptionalText(productName.substring(productFamilyName.length()));
    }

    private UUID buildVariantGroupId(Company company,
                                     ProductionBrand brand,
                                     String productFamilyName,
                                     String itemClass,
                                     String unitOfMeasure,
                                     String hsnCode) {
        String fingerprint = String.join("|",
                String.valueOf(company != null ? company.getId() : null),
                String.valueOf(brand != null ? brand.getId() : null),
                sanitizeSegment(productFamilyName),
                sanitizeSegment(itemClass),
                sanitizeSegment(unitOfMeasure),
                sanitizeSegment(hsnCode));
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return NON_ALPHANUM.matcher(upper).replaceAll("");
    }

    private void syncRawMaterial(Company company, ProductionProduct product, String itemClassHint) {
        String sku = normalizeRequiredText(product.getSkuCode(), "Product SKU is required");
        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, sku)
                .orElseGet(() -> {
                    RawMaterial created = new RawMaterial();
                    created.setCompany(company);
                    created.setSku(sku);
                    created.setCurrentStock(BigDecimal.ZERO);
                    return created;
                });
        material.setName(normalizeRequiredText(product.getProductName(), "Product name is required"));
        material.setUnitType(resolveUnit(product.getUnitOfMeasure()));
        Long inventoryAccountId = rawMaterialInventoryAccountIdFromMetadata(product);
        if (inventoryAccountId != null) {
            material.setInventoryAccountId(inventoryAccountId);
        } else if (material.getInventoryAccountId() == null && company.getDefaultInventoryAccountId() != null) {
            material.setInventoryAccountId(company.getDefaultInventoryAccountId());
        }
        if (product.getGstRate() != null) {
            material.setGstRate(product.getGstRate());
        }
        material.setMaterialType(resolveRawMaterialMaterialType(product, material, itemClassHint));
        rawMaterialRepository.save(material);
    }

    private void syncFinishedGood(Company company, ProductionProduct product) {
        String sku = normalizeRequiredText(product.getSkuCode(), "Product SKU is required");
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseGet(() -> {
                    FinishedGood created = new FinishedGood();
                    created.setCompany(company);
                    created.setProductCode(sku);
                    created.setCurrentStock(BigDecimal.ZERO);
                    created.setReservedStock(BigDecimal.ZERO);
                    return created;
                });
        finishedGood.setName(normalizeRequiredText(product.getProductName(), "Product name is required"));
        finishedGood.setUnit(resolveUnit(product.getUnitOfMeasure()));
        finishedGood.setValuationAccountId(metadataLong(product.getMetadata(), "fgValuationAccountId"));
        finishedGood.setCogsAccountId(metadataLong(product.getMetadata(), "fgCogsAccountId"));
        finishedGood.setRevenueAccountId(metadataLong(product.getMetadata(), "fgRevenueAccountId"));
        finishedGood.setDiscountAccountId(metadataLong(product.getMetadata(), "fgDiscountAccountId"));
        finishedGood.setTaxAccountId(metadataLong(product.getMetadata(), "fgTaxAccountId"));
        finishedGoodRepository.save(finishedGood);
    }

    private void deleteRawMaterialMirror(Company company, ProductionProduct product) {
        String sku = product != null ? normalizeOptionalText(product.getSkuCode()) : null;
        if (!StringUtils.hasText(sku)) {
            return;
        }
        rawMaterialRepository.findByCompanyAndSku(company, sku).ifPresent(rawMaterialRepository::delete);
    }

    private void deleteFinishedGoodMirror(Company company, ProductionProduct product) {
        String sku = product != null ? normalizeOptionalText(product.getSkuCode()) : null;
        if (!StringUtils.hasText(sku)) {
            return;
        }
        finishedGoodRepository.findByCompanyAndProductCodeIgnoreCase(company, sku).ifPresent(finishedGoodRepository::delete);
    }

    private Set<String> normalizeOptions(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw ValidationUtils.invalidInput("Product " + fieldName + " must contain at least one value");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = normalizeOptionalText(value);
            if (!StringUtils.hasText(cleaned)) {
                continue;
            }
            normalized.add(cleaned);
        }
        if (normalized.isEmpty()) {
            throw ValidationUtils.invalidInput("Product " + fieldName + " must contain at least one value");
        }
        return normalized;
    }

    private Map<String, Integer> normalizeCartonSizes(List<CatalogProductCartonSizeRequest> cartonSizes,
                                                      Set<String> sizes) {
        if (cartonSizes == null || cartonSizes.isEmpty()) {
            throw ValidationUtils.invalidInput("cartonSizes mapping is required");
        }

        Set<String> normalizedSizes = new LinkedHashSet<>();
        for (String size : sizes) {
            normalizedSizes.add(size.toLowerCase(Locale.ROOT));
        }

        Map<String, Integer> mappings = new LinkedHashMap<>();
        for (CatalogProductCartonSizeRequest mapping : cartonSizes) {
            if (mapping == null) {
                continue;
            }
            String normalizedSize = normalizeRequiredText(mapping.size(), "Carton size label is required");
            if (!normalizedSizes.contains(normalizedSize.toLowerCase(Locale.ROOT))) {
                throw ValidationUtils.invalidInput("cartonSizes contains size '" + normalizedSize + "' that is not present in sizes");
            }
            if (mapping.piecesPerCarton() == null || mapping.piecesPerCarton() <= 0) {
                throw ValidationUtils.invalidInput("Pieces per carton must be greater than zero");
            }
            if (mappings.containsKey(normalizedSize)) {
                throw ValidationUtils.invalidInput("Duplicate carton size mapping for '" + normalizedSize + "'");
            }
            mappings.put(normalizedSize, mapping.piecesPerCarton());
        }

        for (String size : sizes) {
            if (!mappings.containsKey(size)) {
                throw ValidationUtils.invalidInput("Missing carton size mapping for size '" + size + "'");
            }
        }
        return mappings;
    }

    private void assertUniqueProductName(ProductionBrand brand, String name, Long currentProductId) {
        Optional<ProductionProduct> existing = productRepository.findByBrandAndProductNameIgnoreCase(brand, name);
        if (existing.isPresent() && (currentProductId == null || !existing.get().getId().equals(currentProductId))) {
            throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Product with name '" + name + "' already exists for brand " + brand.getName());
        }
    }

    private void syncSizeVariants(Company company, ProductionProduct product) {
        if (company == null || product == null || product.getId() == null) {
            return;
        }
        Map<String, Integer> cartonBySize = product.getCartonSizes() == null
                ? Map.of()
                : product.getCartonSizes();
        Set<String> activeSizeKeys = new HashSet<>();

        for (Map.Entry<String, Integer> entry : cartonBySize.entrySet()) {
            String sizeLabel = normalizeRequiredText(entry.getKey(), "Carton size label is required");
            Integer cartonQuantity = entry.getValue();
            if (cartonQuantity == null || cartonQuantity <= 0) {
                throw ValidationUtils.invalidInput("Pieces per carton must be greater than zero");
            }
            BigDecimal litersPerUnit = parseSizeToLiters(sizeLabel);
            String key = sizeLabel.toLowerCase(Locale.ROOT);
            activeSizeKeys.add(key);

            SizeVariant variant = sizeVariantRepository
                    .findByCompanyAndProductAndSizeLabelIgnoreCase(company, product, sizeLabel)
                    .orElseGet(() -> {
                        SizeVariant created = new SizeVariant();
                        created.setCompany(company);
                        created.setProduct(product);
                        created.setSizeLabel(sizeLabel);
                        return created;
                    });
            variant.setSizeLabel(sizeLabel);
            variant.setCartonQuantity(cartonQuantity);
            variant.setLitersPerUnit(litersPerUnit);
            variant.setActive(true);
            sizeVariantRepository.save(variant);
        }

        List<SizeVariant> existing = sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, product);
        for (SizeVariant variant : existing) {
            if (variant.getSizeLabel() == null) {
                continue;
            }
            String key = variant.getSizeLabel().toLowerCase(Locale.ROOT);
            if (!activeSizeKeys.contains(key) && variant.isActive()) {
                variant.setActive(false);
                sizeVariantRepository.save(variant);
            }
        }
    }

    private BigDecimal parseSizeToLiters(String sizeLabel) {
        Matcher matcher = SIZE_WITH_UNIT_PATTERN.matcher(sizeLabel.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return BigDecimal.ONE;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ex) {
            return BigDecimal.ONE;
        }
        String unit = matcher.group(2);
        if ("ML".equals(unit)) {
            return value.divide(ONE_THOUSAND, 4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String resolveUnit(String unit) {
        return StringUtils.hasText(unit) ? unit.trim() : "UNIT";
    }

    private Long rawMaterialInventoryAccountIdFromMetadata(ProductionProduct product) {
        if (product == null) {
            return null;
        }
        return rawMaterialInventoryAccountIdFromMetadata(product.getMetadata());
    }

    private Long rawMaterialInventoryAccountIdFromMetadata(Map<String, Object> metadata) {
        Long accountId = metadataLong(metadata, "inventoryAccountId");
        if (accountId == null) {
            accountId = metadataLong(metadata, "rawMaterialInventoryAccountId");
        }
        return accountId;
    }

    private void validateInventorySyncMetadata(Company company, ProductionProduct product) {
        if (company == null || product == null) {
            return;
        }
        product.setMetadata(validateInventorySyncMetadata(
                company,
                product.getCategory(),
                product.getSkuCode(),
                product.getMetadata()));
    }

    private Map<String, Object> validateInventorySyncMetadata(Company company,
                                                              String category,
                                                              String sku,
                                                              Map<String, Object> metadata) {
        Map<String, Object> working = normalizeMetadata(metadata);
        if (isRawMaterialCategory(category)) {
            Long inventoryAccountId = rawMaterialInventoryAccountIdFromMetadata(working);
            if (inventoryAccountId != null) {
                Long validatedAccountId = requireRawMaterialInventoryAccount(company, inventoryAccountId, sku);
                if (working.containsKey("inventoryAccountId")) {
                    working.put("inventoryAccountId", validatedAccountId);
                }
                if (working.containsKey("rawMaterialInventoryAccountId")) {
                    working.put("rawMaterialInventoryAccountId", validatedAccountId);
                }
            }
            return working;
        }
        return ensureFinishedGoodAccounts(company, sku, working);
    }

    private Long requireRawMaterialInventoryAccount(Company company, Long accountId, String sku) {
        if (accountId == null || accountId <= 0) {
            return null;
        }
        try {
            return companyEntityLookup.requireAccount(company, accountId).getId();
        } catch (IllegalArgumentException ex) {
            throw ValidationUtils.invalidInput(
                    "Raw material SKU " + sku + " references an invalid inventory account id " + accountId);
        }
    }

    private Map<String, Object> ensureFinishedGoodAccounts(Company company,
                                                           String sku,
                                                           Map<String, Object> metadata) {
        Map<String, Object> working = normalizeMetadata(metadata);
        Map<String, Long> defaults = new LinkedHashMap<>();
        defaults.put("fgValuationAccountId", company.getDefaultInventoryAccountId());
        defaults.put("fgCogsAccountId", company.getDefaultCogsAccountId());
        defaults.put("fgRevenueAccountId", company.getDefaultRevenueAccountId());
        defaults.put("fgDiscountAccountId", company.getDefaultDiscountAccountId());
        defaults.put("fgTaxAccountId", company.getDefaultTaxAccountId());

        for (String key : FINISHED_GOOD_ACCOUNT_KEYS) {
            if (!hasLongValue(working.get(key)) && defaults.get(key) != null) {
                working.put(key, defaults.get(key));
            }
        }

        for (String key : List.of("fgValuationAccountId", "fgCogsAccountId", "fgRevenueAccountId", "fgTaxAccountId")) {
            if (!hasLongValue(working.get(key))) {
                throw ValidationUtils.invalidState(
                        "Default " + key + " is not configured for company " + company.getCode()
                                + ". Configure company default accounts to enable product posting.");
            }
        }

        for (String key : FINISHED_GOOD_ACCOUNT_KEYS) {
            Long accountId = metadataLong(working, key);
            if (accountId == null) {
                continue;
            }
            Long validatedAccountId = requireFinishedGoodAccount(company, accountId, sku, key);
            if (!Objects.equals(accountId, validatedAccountId)) {
                working.put(key, validatedAccountId);
            }
        }
        return working;
    }

    private Long requireFinishedGoodAccount(Company company,
                                            Long accountId,
                                            String sku,
                                            String key) {
        if (accountId == null || accountId <= 0) {
            return null;
        }
        try {
            return companyEntityLookup.requireAccount(company, accountId).getId();
        } catch (IllegalArgumentException ex) {
            throw ValidationUtils.invalidInput(
                    "Finished good SKU " + sku + " references an invalid account id " + accountId + " for " + key);
        }
    }

    private boolean hasLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue() > 0;
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Long.parseLong(stringValue.trim()) > 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private Long metadataLong(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object candidate = metadata.get(key);
        if (candidate instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (candidate instanceof String text && StringUtils.hasText(text)) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveRequestedItemClass(ProductionProduct product, String requestedItemClass, boolean creating) {
        if (StringUtils.hasText(requestedItemClass)) {
            return normalizeItemClass(requestedItemClass);
        }
        if (!creating && product != null) {
            return itemClassForProduct(product);
        }
        throw ValidationUtils.invalidInput(
                "itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
    }

    private String normalizeItemClass(String itemClass) {
        if (!StringUtils.hasText(itemClass)) {
            throw ValidationUtils.invalidInput(
                    "itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
        }
        String normalized = itemClass.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ITEM_CLASS_RAW_MATERIAL, "PRODUCTION", "PRODUCTION_RAW_MATERIAL" -> ITEM_CLASS_RAW_MATERIAL;
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL, "PACKAGING" -> ITEM_CLASS_PACKAGING_RAW_MATERIAL;
            case ITEM_CLASS_FINISHED_GOOD -> ITEM_CLASS_FINISHED_GOOD;
            default -> throw ValidationUtils.invalidInput(
                    "itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
        };
    }

    private String categoryForItemClass(String itemClass) {
        return switch (normalizeItemClass(itemClass)) {
            case ITEM_CLASS_RAW_MATERIAL, ITEM_CLASS_PACKAGING_RAW_MATERIAL -> ITEM_CLASS_RAW_MATERIAL;
            default -> DEFAULT_PRODUCT_CATEGORY;
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

    private Map<String, RawMaterial> rawMaterialsBySku(Company company, List<ProductionProduct> products) {
        if (company == null || products == null || products.isEmpty()) {
            return Map.of();
        }
        List<String> skus = products.stream()
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
                .collect(Collectors.toMap(
                        rawMaterial -> normalizeSkuLookupKey(rawMaterial.getSku()),
                        rawMaterial -> rawMaterial,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private RawMaterial rawMaterialForProduct(ProductionProduct product) {
        if (product == null || !isRawMaterialCategory(product.getCategory()) || !StringUtils.hasText(product.getSkuCode())) {
            return null;
        }
        return rawMaterialRepository.findByCompanyAndSkuIgnoreCase(product.getCompany(), product.getSkuCode()).orElse(null);
    }

    private com.bigbrightpaints.erp.modules.inventory.domain.MaterialType resolveRawMaterialMaterialType(ProductionProduct product,
                                                                                                         RawMaterial material,
                                                                                                         String itemClassHint) {
        if (StringUtils.hasText(itemClassHint)) {
            return ITEM_CLASS_PACKAGING_RAW_MATERIAL.equals(normalizeItemClass(itemClassHint))
                    ? MaterialType.PACKAGING
                    : MaterialType.PRODUCTION;
        }
        if (material != null && material.getMaterialType() != null) {
            return material.getMaterialType();
        }
        return MaterialType.PRODUCTION;
    }

    private boolean isRawMaterialCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return false;
        }
        String normalized = category.replace('-', '_').toUpperCase(Locale.ROOT);
        return RAW_MATERIAL_CATEGORIES.stream().anyMatch(normalized::equalsIgnoreCase);
    }

    private ProductionBrand requireBrand(Company company, Long brandId) {
        return brandRepository.findByCompanyAndId(company, brandId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Brand not found for id " + brandId));
    }

    private ProductionBrand requireActiveBrand(Company company, Long brandId) {
        ProductionBrand brand = requireBrand(company, brandId);
        if (!brand.isActive()) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Brand '" + brand.getName() + "' is inactive");
        }
        return brand;
    }

    private ProductionProduct requireProduct(Company company, Long productId) {
        return productRepository.findByCompanyAndId(company, productId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Product not found for id " + productId));
    }

    private void assertBrandNameUnique(Company company, String name, Long currentBrandId) {
        Optional<ProductionBrand> existing = brandRepository.findByCompanyAndNameIgnoreCase(company, name);
        if (existing.isPresent() && (currentBrandId == null || !existing.get().getId().equals(currentBrandId))) {
            throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Brand with name '" + name + "' already exists");
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

    private String generateSku(Company company, ProductionBrand brand, String productName) {
        String nameToken = sanitizeCode(productName);
        String prefix = brand.getCode() + "-" + nameToken;
        int next = 1;
        ProductionProduct latest = productRepository.findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(company, prefix)
                .orElse(null);
        if (latest != null) {
            Matcher matcher = SKU_SEQUENCE_PATTERN.matcher(latest.getSkuCode());
            if (matcher.find()) {
                next = Integer.parseInt(matcher.group(1)) + 1;
            }
        }
        String candidate = prefix + "-" + String.format(Locale.ROOT, "%03d", next++);
        while (productRepository.findByCompanyAndSkuCode(company, candidate).isPresent()) {
            candidate = prefix + "-" + String.format(Locale.ROOT, "%03d", next++);
        }
        return candidate;
    }

    private String sanitizeCode(String source) {
        String cleaned = NON_ALPHANUM.matcher(
                normalizeRequiredText(source, "Unable to derive code from blank source")
                        .toUpperCase(Locale.ROOT))
                .replaceAll("");
        if (!StringUtils.hasText(cleaned)) {
            return "CAT";
        }
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }

    private BigDecimal normalizeRate(BigDecimal value) {
        if (value == null) {
            throw ValidationUtils.invalidInput("GST rate is required");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw ValidationUtils.invalidInput("GST rate must be between 0 and 100");
        }
        return value;
    }

    private BigDecimal normalizeOptionalRate(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw ValidationUtils.invalidInput("Minimum discount percent must be between 0 and 100");
        }
        return value;
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw ValidationUtils.invalidInput("Money values cannot be negative");
        }
        return value;
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(metadata);
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> existingMetadata, Map<String, Object> requestMetadata) {
        Map<String, Object> merged = normalizeMetadata(existingMetadata);
        merged.putAll(normalizeMetadata(requestMetadata));
        return merged;
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

    private Map<String, FinishedGood> finishedGoodsBySku(Company company, List<ProductionProduct> products) {
        if (company == null || products == null || products.isEmpty()) {
            return Map.of();
        }
        List<String> lookupKeys = products.stream()
                .map(ProductionProduct::getSkuCode)
                .map(this::normalizeSkuLookupKey)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (lookupKeys.isEmpty()) {
            return Map.of();
        }
        return finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(company, lookupKeys).stream()
                .filter(finishedGood -> StringUtils.hasText(finishedGood.getProductCode()))
                .collect(Collectors.toMap(
                        finishedGood -> normalizeSkuLookupKey(finishedGood.getProductCode()),
                        finishedGood -> finishedGood,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private FinishedGood finishedGoodForProduct(ProductionProduct product) {
        if (product == null || product.getCompany() == null || !StringUtils.hasText(product.getSkuCode())) {
            return null;
        }
        return finishedGoodRepository.findByCompanyAndProductCodeIgnoreCase(product.getCompany(), product.getSkuCode())
                .orElse(null);
    }

    private CatalogItemDto toItemDto(ProductionProduct product,
                                     boolean includeAccountingMetadata,
                                     boolean includeStock,
                                     com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readinessSnapshot,
                                     RawMaterial rawMaterial,
                                     FinishedGood finishedGood) {
        Map<String, Object> metadata = snapshotMetadata(product.getMetadata(), includeAccountingMetadata);
        com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness = readinessSnapshot == null
                ? null
                : skuReadinessService.sanitizeForCatalogViewer(readinessSnapshot, includeAccountingMetadata);
        String itemClass = itemClassForProduct(product, rawMaterial);
        RawMaterial resolvedRawMaterial = ITEM_CLASS_FINISHED_GOOD.equals(itemClass) ? null : rawMaterial;
        CatalogItemStockDto stock = includeStock ? toItemStock(product, resolvedRawMaterial, finishedGood) : null;
        return new CatalogItemDto(
                product.getId(),
                product.getPublicId(),
                resolvedRawMaterial != null ? resolvedRawMaterial.getId() : null,
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getBrand().getCode(),
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

    private CatalogItemStockDto toItemStock(ProductionProduct product,
                                            RawMaterial rawMaterial,
                                            FinishedGood finishedGood) {
        String itemClass = itemClassForProduct(product, rawMaterial);
        if (ITEM_CLASS_FINISHED_GOOD.equals(itemClass) && finishedGood != null) {
            BigDecimal onHand = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
            BigDecimal reserved = Optional.ofNullable(finishedGood.getReservedStock()).orElse(BigDecimal.ZERO);
            BigDecimal available = onHand.subtract(reserved);
            if (available.compareTo(BigDecimal.ZERO) < 0) {
                available = BigDecimal.ZERO;
            }
            return new CatalogItemStockDto(onHand, reserved, available, finishedGood.getUnit());
        }
        BigDecimal onHand = rawMaterial != null
                ? Optional.ofNullable(rawMaterial.getCurrentStock()).orElse(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        return new CatalogItemStockDto(onHand, BigDecimal.ZERO, onHand, product.getUnitOfMeasure());
    }

    private CatalogProductDto toProductDto(ProductionProduct product) {
        return toProductDto(
                product,
                true,
                skuReadinessService.forProduct(product.getCompany(), product),
                rawMaterialForProduct(product));
    }

    private CatalogProductDto toPublicProductDto(ProductionProduct product) {
        return toProductDto(
                product,
                false,
                skuReadinessService.forProduct(product.getCompany(), product),
                rawMaterialForProduct(product));
    }

    private CatalogProductDto toProductDto(ProductionProduct product, boolean includeAccountingMetadata) {
        return toProductDto(
                product,
                includeAccountingMetadata,
                skuReadinessService.forProduct(product.getCompany(), product),
                rawMaterialForProduct(product));
    }

    private CatalogProductDto toProductDto(ProductionProduct product,
                                           boolean includeAccountingMetadata,
                                           com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readinessSnapshot,
                                           RawMaterial rawMaterial) {
        List<String> colors = toVariantList(product.getColors(), product.getDefaultColour());
        List<String> sizes = toVariantList(product.getSizes(), product.getSizeLabel());
        List<CatalogProductCartonSizeDto> cartonSizeDtos = product.getCartonSizes() == null
                ? List.of()
                : product.getCartonSizes().entrySet().stream()
                .map(entry -> new CatalogProductCartonSizeDto(entry.getKey(), entry.getValue()))
                .toList();
        Map<String, Object> metadata = snapshotMetadata(product.getMetadata(), includeAccountingMetadata);
        com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness =
                skuReadinessService.sanitizeForCatalogViewer(readinessSnapshot, includeAccountingMetadata);
        String itemClass = itemClassForProduct(product, rawMaterial);
        RawMaterial resolvedRawMaterial = ITEM_CLASS_FINISHED_GOOD.equals(itemClass) ? null : rawMaterial;

        return new CatalogProductDto(
                product.getId(),
                product.getPublicId(),
                resolvedRawMaterial != null ? resolvedRawMaterial.getId() : null,
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getBrand().getCode(),
                product.getProductName(),
                product.getSkuCode(),
                product.getCategory(),
                itemClass,
                product.getVariantGroupId(),
                product.getProductFamilyName(),
                colors,
                sizes,
                cartonSizeDtos,
                product.getUnitOfMeasure(),
                product.getHsnCode(),
                product.getBasePrice(),
                product.getGstRate(),
                product.getMinDiscountPercent(),
                product.getMinSellingPrice(),
                metadata,
                product.isActive(),
                readiness);
    }

    private String itemClassForProduct(ProductionProduct product) {
        return itemClassForProduct(product, rawMaterialForProduct(product));
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

    private Map<String, Object> snapshotMetadata(Map<String, Object> metadata, boolean includeAccountingMetadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (includeAccountingMetadata || key == null || !key.endsWith(ACCOUNTING_METADATA_KEY_SUFFIX)) {
                snapshot.put(key, value);
            }
        });
        return snapshot.isEmpty() ? Map.of() : Collections.unmodifiableMap(snapshot);
    }

    private List<String> toVariantList(Set<String> values, String fallback) {
        if (values != null && !values.isEmpty()) {
            return List.copyOf(values);
        }
        String normalizedFallback = normalizeOptionalText(fallback);
        return normalizedFallback == null ? List.of() : List.of(normalizedFallback);
    }

    private record ProductMutationOutcome(String action, CatalogProductDto product) {
    }
}
