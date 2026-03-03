package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkItemResult;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogService {

    private static final String DEFAULT_PRODUCT_CATEGORY = "FINISHED_GOOD";
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern SKU_SEQUENCE_PATTERN = Pattern.compile("-(\\d{3})$");
    private static final Pattern SIZE_WITH_UNIT_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(ML|L|LTR|LITRE|LITER)?$");
    private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");
    private static final Validator BULK_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final CompanyContextService companyContextService;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final SizeVariantRepository sizeVariantRepository;

    public CatalogService(CompanyContextService companyContextService,
                          ProductionBrandRepository brandRepository,
                          ProductionProductRepository productRepository,
                          SizeVariantRepository sizeVariantRepository) {
        this.companyContextService = companyContextService;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.sizeVariantRepository = sizeVariantRepository;
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
    public CatalogProductDto createProduct(CatalogProductRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionBrand brand = requireActiveBrand(company, request.brandId());
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(generateSku(company, brand, request.name()));
        applyProductPayload(product, brand, request, true);
        ProductionProduct saved = productRepository.save(product);
        syncSizeVariants(company, saved);
        return toProductDto(saved);
    }

    @Transactional(readOnly = true)
    public CatalogProductDto getProduct(Long productId) {
        Company company = companyContextService.requireCurrentCompany();
        return toProductDto(requireProduct(company, productId));
    }

    @Transactional
    public CatalogProductDto updateProduct(Long productId, CatalogProductRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, productId);
        ProductionBrand brand = requireActiveBrand(company, request.brandId());
        product.setBrand(brand);
        applyProductPayload(product, brand, request, false);
        ProductionProduct saved = productRepository.save(product);
        syncSizeVariants(company, saved);
        return toProductDto(saved);
    }

    @Transactional
    public CatalogProductDto deactivateProduct(Long productId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = requireProduct(company, productId);
        product.setActive(false);
        return toProductDto(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogProductDto> searchProducts(Long brandId,
                                                          String color,
                                                          String size,
                                                          Boolean active,
                                                          int page,
                                                          int pageSize) {
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
        List<CatalogProductDto> content = result.getContent().stream().map(this::toProductDto).toList();
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
                Join<ProductionProduct, String> colors = root.joinSet("colors", JoinType.LEFT);
                predicates.add(cb.equal(cb.lower(colors), color.toLowerCase(Locale.ROOT)));
                query.distinct(true);
            }
            if (StringUtils.hasText(size)) {
                Join<ProductionProduct, String> sizes = root.joinSet("sizes", JoinType.LEFT);
                predicates.add(cb.equal(cb.lower(sizes), size.toLowerCase(Locale.ROOT)));
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void applyProductPayload(ProductionProduct product,
                                     ProductionBrand brand,
                                     CatalogProductRequest request,
                                     boolean creating) {
        String name = normalizeRequiredText(request.name(), "Product name is required");
        Set<String> colors = normalizeOptions(request.colors(), "colors");
        Set<String> sizes = normalizeOptions(request.sizes(), "sizes");
        Map<String, Integer> cartonSizes = normalizeCartonSizes(request.cartonSizes(), sizes);
        assertUniqueProductName(brand, name, creating ? null : product.getId());

        product.setProductName(name);
        product.setCategory(DEFAULT_PRODUCT_CATEGORY);
        product.setDefaultColour(colors.stream().findFirst().orElse(null));
        product.setSizeLabel(sizes.stream().findFirst().orElse(null));
        product.setColors(colors);
        product.setSizes(sizes);
        product.setCartonSizes(cartonSizes);
        product.setUnitOfMeasure(normalizeRequiredText(request.unitOfMeasure(), "Unit of measure is required"));
        product.setHsnCode(normalizeRequiredText(request.hsnCode(), "HSN code is required"));
        product.setGstRate(normalizeRate(request.gstRate()));
        if (creating) {
            product.setActive(request.active() == null || request.active());
        } else if (request.active() != null) {
            product.setActive(request.active());
        }
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
        String candidate;
        do {
            candidate = prefix + "-" + String.format(Locale.ROOT, "%03d", next++);
        } while (productRepository.findByCompanyAndSkuCode(company, candidate).isPresent());
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

    private CatalogProductDto toProductDto(ProductionProduct product) {
        List<String> colors = product.getColors() == null ? List.of() : List.copyOf(product.getColors());
        List<String> sizes = product.getSizes() == null ? List.of() : List.copyOf(product.getSizes());
        List<CatalogProductCartonSizeDto> cartonSizeDtos = product.getCartonSizes() == null
                ? List.of()
                : product.getCartonSizes().entrySet().stream()
                .map(entry -> new CatalogProductCartonSizeDto(entry.getKey(), entry.getValue()))
                .toList();

        return new CatalogProductDto(
                product.getId(),
                product.getPublicId(),
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getBrand().getCode(),
                product.getProductName(),
                product.getSkuCode(),
                colors,
                sizes,
                cartonSizeDtos,
                product.getUnitOfMeasure(),
                product.getHsnCode(),
                product.getGstRate(),
                product.isActive());
    }

    private record ProductMutationOutcome(String action, CatalogProductDto product) {
    }
}
