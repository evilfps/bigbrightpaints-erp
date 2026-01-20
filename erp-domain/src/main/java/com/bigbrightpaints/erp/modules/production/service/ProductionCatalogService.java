package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ProductionCatalogService {

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern NON_SKU_CHAR = Pattern.compile("[^A-Z0-9-]");
    private static final Pattern SEQUENCE_PATTERN = Pattern.compile(".*-(\\d{3})$");
    private static final List<String> RAW_MATERIAL_CATEGORIES = List.of("RAW_MATERIAL", "RAW MATERIAL", "RAW-MATERIAL");
    private static final List<String> FINISHED_GOOD_ACCOUNT_KEYS = List.of(
            "fgValuationAccountId",
            "fgCogsAccountId",
            "fgRevenueAccountId",
            "fgDiscountAccountId",
            "fgTaxAccountId"
    );
    private static final Map<String, String> ACCOUNT_COLUMN_HINTS = Map.of(
            "fgValuationAccountId", "fg_valuation_account_id",
            "fgCogsAccountId", "fg_cogs_account_id",
            "fgRevenueAccountId", "fg_revenue_account_id",
            "fgDiscountAccountId", "fg_discount_account_id",
            "fgTaxAccountId", "fg_tax_account_id"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CompanyContextService companyContextService;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;

    public ProductionCatalogService(CompanyContextService companyContextService,
                                    ProductionBrandRepository brandRepository,
                                    ProductionProductRepository productRepository,
                                    RawMaterialRepository rawMaterialRepository,
                                    CompanyEntityLookup companyEntityLookup,
                                    CompanyDefaultAccountsService companyDefaultAccountsService) {
        this.companyContextService = companyContextService;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
    }

    @Transactional
    public CatalogImportResponse importCatalog(MultipartFile file) {
        Company company = companyContextService.requireCurrentCompany();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("CSV file is required");
        }

        AtomicInteger rows = new AtomicInteger();
        AtomicInteger brandsCreated = new AtomicInteger();
        AtomicInteger productsCreated = new AtomicInteger();
        AtomicInteger productsUpdated = new AtomicInteger();
        AtomicInteger rawMaterialsSeeded = new AtomicInteger();
        List<CatalogImportResponse.ImportError> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                CatalogRow row;
                try {
                    row = CatalogRow.from(record);
                } catch (IllegalArgumentException ex) {
                    errors.add(new CatalogImportResponse.ImportError(record.getRecordNumber(), ex.getMessage()));
                    continue;
                }
                if (row == null) {
                    continue;
                }
                try {
                    ProcessOutcome outcome = upsertProduct(company, row);
                    rows.incrementAndGet();
                    if (outcome.brandCreated()) {
                        brandsCreated.incrementAndGet();
                    }
                    if (outcome.productCreated()) {
                        productsCreated.incrementAndGet();
                    } else {
                        productsUpdated.incrementAndGet();
                    }
                    if (outcome.rawMaterialSeeded()) {
                        rawMaterialsSeeded.incrementAndGet();
                    }
                } catch (IllegalArgumentException ex) {
                    errors.add(new CatalogImportResponse.ImportError(record.getRecordNumber(), ex.getMessage()));
                } catch (Exception ex) {
                    errors.add(new CatalogImportResponse.ImportError(record.getRecordNumber(), "Unexpected error: " + ex.getMessage()));
                }
            }

            return new CatalogImportResponse(rows.get(), brandsCreated.get(), productsCreated.get(),
                    productsUpdated.get(), rawMaterialsSeeded.get(), errors);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read CSV file", ex);
        }
    }

    @Transactional
    public ProductionProductDto createProduct(ProductCreateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.brandId() == null && !StringUtils.hasText(request.brandName())) {
            throw new IllegalArgumentException("Either brandId or brandName must be provided");
        }

        String normalizedCategory = normalizeCategory(request.category());
        BrandResolution resolution = resolveBrand(company, request.brandId(), request.brandName(), request.brandCode());
        ProductionBrand brand = resolution.brand();
        String productName = request.productName().trim();
        if (!StringUtils.hasText(productName)) {
            throw new IllegalArgumentException("Product name is required");
        }

        String sizeLabel = StringUtils.hasText(request.sizeLabel()) ? request.sizeLabel().trim() : request.unitOfMeasure();
        String sku = determineSku(company, brand, normalizedCategory, request.defaultColour(), sizeLabel, request.customSkuCode());
        if (productRepository.findByCompanyAndSkuCode(company, sku).isPresent()) {
            throw new IllegalArgumentException("SKU " + sku + " already exists");
        }

        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName(productName);
        product.setCategory(normalizedCategory);
        product.setDefaultColour(cleanValue(request.defaultColour()));
        product.setSizeLabel(cleanValue(sizeLabel));
        product.setUnitOfMeasure(cleanValue(request.unitOfMeasure()));
        product.setSkuCode(sku);
        product.setActive(true);
        product.setBasePrice(money(request.basePrice()));
        product.setGstRate(percent(request.gstRate()));
        product.setMinDiscountPercent(percent(request.minDiscountPercent()));
        product.setMinSellingPrice(money(request.minSellingPrice()));
        Map<String, Object> metadata = normalizeMetadata(request.metadata());
        if (!isRawMaterialCategory(normalizedCategory)) {
            metadata = ensureFinishedGoodAccounts(company, sku, metadata);
        }
        product.setMetadata(metadata);
        ProductionProduct saved = productRepository.save(product);
        syncRawMaterial(company, saved);
        return toProductDto(saved);
    }

    /**
     * Bulk variant creation: generates SKUs for each (color x size) and creates products if missing.
     */
    @Transactional
    public BulkVariantResponse createVariants(BulkVariantRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        List<String> colors = expandTokens(request.colors());
        List<String> sizes = expandTokens(request.sizes());
        if (colors.isEmpty()) {
            throw new IllegalArgumentException("At least one color is required");
        }
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("At least one size is required");
        }
        String normalizedCategory = normalizeCategory(request.category());
        BrandResolution resolution = resolveBrand(company, request.brandId(), request.brandName(), request.brandCode());
        ProductionBrand brand = resolution.brand();
        String baseName = request.baseProductName().trim();
        String unit = StringUtils.hasText(request.unitOfMeasure()) ? request.unitOfMeasure().trim() : "UNIT";
        String prefix = StringUtils.hasText(request.skuPrefix())
                ? sanitizeSkuFragment(request.skuPrefix())
                : sanitizeSkuFragment(brand.getCode());
        List<ProductionProductDto> variants = new ArrayList<>();
        int created = 0;
        int skipped = 0;
        for (String color : colors) {
            String colorCode = truncate(sanitizeSkuFragment(color), 8);
            for (String size : sizes) {
                String sizeCode = truncate(sanitizeSkuFragment(size), 8);
                String sku = String.join("-",
                        List.of(prefix, sanitizeSkuFragment(baseName), colorCode, sizeCode))
                        .replaceAll("-+", "-");
                if (productRepository.findByCompanyAndSkuCode(company, sku).isPresent()) {
                    skipped++;
                    continue;
                }
                ProductCreateRequest create = new ProductCreateRequest(
                        brand.getId(), null, null,
                        baseName + " " + color + " " + size,
                        normalizedCategory, color, size, unit, sku,
                        request.basePrice(), request.gstRate(),
                        request.minDiscountPercent(), request.minSellingPrice(),
                        request.metadata()
                );
                ProductionProductDto dto = createProduct(create);
                variants.add(dto);
                created++;
            }
        }
        return new BulkVariantResponse(created, skipped, variants);
    }

    private List<String> expandTokens(List<String> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private String sanitizeSkuFragment(String value) {
        if (!StringUtils.hasText(value)) return "";
        return NON_SKU_CHAR.matcher(value.trim().toUpperCase()).replaceAll("");
    }

    @Transactional
    public List<ProductionBrandDto> listBrands() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionBrand> brands = brandRepository.findByCompanyOrderByNameAsc(company);
        Map<Long, Long> productCounts = productRepository.findByCompanyOrderByProductNameAsc(company).stream()
                .collect(Collectors.groupingBy(product -> product.getBrand().getId(), Collectors.counting()));
        return brands.stream()
                .map(brand -> new ProductionBrandDto(
                        brand.getId(),
                        brand.getPublicId(),
                        brand.getName(),
                        brand.getCode(),
                        productCounts.getOrDefault(brand.getId(), 0L)))
                .toList();
    }

    @Transactional
    public List<ProductionProductDto> listBrandProducts(Long brandId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionBrand brand = companyEntityLookup.requireProductionBrand(company, brandId);
        return productRepository.findByBrandOrderByProductNameAsc(brand).stream()
                .map(this::toProductDto)
                .toList();
    }

    public List<ProductionProductDto> listProducts() {
        Company company = companyContextService.requireCurrentCompany();
        return productRepository.findByCompanyOrderByProductNameAsc(company).stream()
                .map(this::toProductDto)
                .toList();
    }

    @Transactional
    public ProductionProductDto updateProduct(Long productId, ProductUpdateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionProduct product = companyEntityLookup.requireProductionProduct(company, productId);
        if (StringUtils.hasText(request.productName())) {
            product.setProductName(request.productName().trim());
        }
        if (StringUtils.hasText(request.category())) {
            product.setCategory(normalizeCategory(request.category()));
        }
        if (request.defaultColour() != null) {
            product.setDefaultColour(cleanValue(request.defaultColour()));
        }
        if (request.sizeLabel() != null) {
            product.setSizeLabel(cleanValue(request.sizeLabel()));
        }
        if (request.unitOfMeasure() != null) {
            product.setUnitOfMeasure(cleanValue(request.unitOfMeasure()));
        }
        if (request.basePrice() != null) {
            product.setBasePrice(money(request.basePrice()));
        }
        if (request.gstRate() != null) {
            product.setGstRate(percent(request.gstRate()));
        }
        if (request.minDiscountPercent() != null) {
            product.setMinDiscountPercent(percent(request.minDiscountPercent()));
        }
        if (request.minSellingPrice() != null) {
            product.setMinSellingPrice(money(request.minSellingPrice()));
        }
        String effectiveCategory = product.getCategory();
        if (request.metadata() != null) {
            Map<String, Object> metadata = normalizeMetadata(request.metadata());
            if (!isRawMaterialCategory(effectiveCategory)) {
                metadata = ensureFinishedGoodAccounts(company, product.getSkuCode(), metadata);
            }
            product.setMetadata(metadata);
        } else if (!isRawMaterialCategory(effectiveCategory)) {
            product.setMetadata(ensureFinishedGoodAccounts(company, product.getSkuCode(),
                    new HashMap<>(Optional.ofNullable(product.getMetadata()).orElseGet(HashMap::new))));
        }
        ProductionProduct saved = productRepository.save(product);
        return toProductDto(saved);
    }

    private ProcessOutcome upsertProduct(Company company, CatalogRow row) {
        BrandResolution resolution = resolveBrand(company, null, row.brand(), null);
        ProductionBrand brand = resolution.brand();
        String category = normalizeCategory(row.category());
        if (!StringUtils.hasText(row.productName())) {
            throw new IllegalArgumentException("Product name is required");
        }
        String productName = row.productName().trim();
        String sizeLabel = StringUtils.hasText(row.sizeLabel()) ? row.sizeLabel() : row.unitOfMeasure();
        String sku = determineSku(company, brand, category, row.defaultColour(), sizeLabel, row.skuCode());

        Optional<ProductionProduct> existing = productRepository.findByCompanyAndSkuCode(company, sku);
        boolean created = existing.isEmpty();
        ProductionProduct product = existing.orElseGet(ProductionProduct::new);
        if (created) {
            product.setCompany(company);
            product.setBrand(brand);
            product.setSkuCode(sku);
            product.setActive(true);
        } else if (!product.getBrand().getId().equals(brand.getId())) {
            // Avoid accidental brand switching when reusing SKU
            brand = product.getBrand();
        }
        product.setProductName(productName);
        product.setCategory(category);
        product.setDefaultColour(cleanValue(row.defaultColour()));
        product.setSizeLabel(cleanValue(sizeLabel));
        product.setUnitOfMeasure(cleanValue(row.unitOfMeasure()));
        if (row.basePrice() != null) {
            product.setBasePrice(money(row.basePrice()));
        }
        if (row.gstRate() != null) {
            product.setGstRate(percent(row.gstRate()));
        }
        if (row.minDiscountPercent() != null) {
            product.setMinDiscountPercent(percent(row.minDiscountPercent()));
        }
        if (row.minSellingPrice() != null) {
            product.setMinSellingPrice(money(row.minSellingPrice()));
        }
        Map<String, Object> metadata;
        if (created) {
            metadata = normalizeMetadata(row.metadata());
        } else {
            metadata = new HashMap<>(Optional.ofNullable(product.getMetadata()).orElseGet(HashMap::new));
            if (row.metadata() != null && !row.metadata().isEmpty()) {
                metadata.putAll(normalizeMetadata(row.metadata()));
            }
        }
        if (!isRawMaterialCategory(category)) {
            metadata = ensureFinishedGoodAccounts(company, sku, metadata);
        }
        product.setMetadata(metadata);
        ProductionProduct saved = productRepository.save(product);
        boolean seeded = syncRawMaterial(company, saved);
        return new ProcessOutcome(resolution.created(), created, seeded);
    }

    private BrandResolution resolveBrand(Company company, Long brandId, String brandName, String providedCode) {
        if (brandId != null) {
            ProductionBrand brand = brandRepository.findById(brandId)
                    .filter(existing -> existing.getCompany().getId().equals(company.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found"));
            if (StringUtils.hasText(brandName) && !brandName.equalsIgnoreCase(brand.getName())) {
                brand.setName(brandName.trim());
            }
            return new BrandResolution(brand, false);
        }
        if (!StringUtils.hasText(brandName)) {
            throw new IllegalArgumentException("Brand is required");
        }
        String effectiveName = brandName.trim();
        if (StringUtils.hasText(providedCode)) {
            Optional<ProductionBrand> byCode = brandRepository.findByCompanyAndCodeIgnoreCase(company, sanitizeCode(providedCode));
            if (byCode.isPresent()) {
                return new BrandResolution(byCode.get(), false);
            }
        }
        Optional<ProductionBrand> byName = brandRepository.findByCompanyAndNameIgnoreCase(company, effectiveName);
        if (byName.isPresent()) {
            return new BrandResolution(byName.get(), false);
        }
        String uniqueCode = nextBrandCode(company, providedCode != null ? providedCode : effectiveName);
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setName(effectiveName);
        brand.setCode(uniqueCode);
        ProductionBrand saved = brandRepository.save(brand);
        return new BrandResolution(saved, true);
    }

    private String nextBrandCode(Company company, String source) {
        String base = sanitizeCode(source);
        String candidate = base;
        int counter = 1;
        while (brandRepository.findByCompanyAndCodeIgnoreCase(company, candidate).isPresent()) {
            String suffix = String.valueOf(counter);
            int maxPrefix = Math.max(4, 12 - suffix.length());
            String prefix = base.length() > maxPrefix ? base.substring(0, maxPrefix) : base;
            candidate = prefix + suffix;
            counter++;
        }
        return candidate;
    }

    private String determineSku(Company company,
                                ProductionBrand brand,
                                String category,
                                String colour,
                                String sizeLabel,
                                String providedSku) {
        if (StringUtils.hasText(providedSku)) {
            return sanitizeSku(providedSku);
        }
        List<String> segments = new ArrayList<>();
        segments.add(brand.getCode());
        if (StringUtils.hasText(category)) {
            segments.add(sanitizeSegment(category));
        }
        if (StringUtils.hasText(colour)) {
            segments.add(sanitizeSegment(colour));
        }
        if (StringUtils.hasText(sizeLabel)) {
            segments.add(sanitizeSegment(sizeLabel));
        }
        String prefix = segments.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("-"));
        if (!StringUtils.hasText(prefix)) {
            prefix = brand.getCode();
        }
        prefix = prefix.replaceAll("-{2,}", "-");
        if (!prefix.contains("-")) {
            prefix = prefix + "-SKU";
        }
        String normalizedPrefix = prefix.toUpperCase();
        Optional<ProductionProduct> last = productRepository.findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(
                company, normalizedPrefix + "-");
        int nextSeq = 1;
        if (last.isPresent()) {
            Matcher matcher = SEQUENCE_PATTERN.matcher(last.get().getSkuCode());
            if (matcher.matches()) {
                nextSeq = Integer.parseInt(matcher.group(1)) + 1;
            }
        }
        return normalizedPrefix + "-" + String.format("%03d", nextSeq);
    }

    private boolean syncRawMaterial(Company company, ProductionProduct product) {
        if (!isRawMaterialCategory(product.getCategory())) {
            return false;
        }
        String sku = product.getSkuCode();
        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, sku)
                .orElseGet(() -> {
                    RawMaterial created = new RawMaterial();
                    created.setCompany(company);
                    created.setSku(sku);
                    created.setName(product.getProductName());
                    created.setUnitType(resolveUnit(product.getUnitOfMeasure()));
                    return created;
                });
        boolean isNew = material.getId() == null;
        material.setName(product.getProductName());
        material.setUnitType(resolveUnit(product.getUnitOfMeasure()));
        rawMaterialRepository.save(material);
        return isNew;
    }

    private String resolveUnit(String unit) {
        if (StringUtils.hasText(unit)) {
            return unit.trim();
        }
        return "UNIT";
    }

    private boolean isRawMaterialCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return false;
        }
        String normalized = category.replace('-', '_').toUpperCase();
        return RAW_MATERIAL_CATEGORIES.stream().anyMatch(normalized::equalsIgnoreCase);
    }

    private ProductionProductDto toProductDto(ProductionProduct product) {
        return new ProductionProductDto(
                product.getId(),
                product.getPublicId(),
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getBrand().getCode(),
                product.getProductName(),
                product.getCategory(),
                product.getDefaultColour(),
                product.getSizeLabel(),
                product.getUnitOfMeasure(),
                product.getSkuCode(),
                product.isActive(),
                product.getBasePrice(),
                product.getGstRate(),
                product.getMinDiscountPercent(),
                product.getMinSellingPrice(),
                product.getMetadata()
        );
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }

    private BigDecimal percent(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value.compareTo(new BigDecimal("100")) > 0 ? new BigDecimal("100") : value;
    }

    private static String sanitizeSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String upper = value.trim().toUpperCase();
        return NON_ALPHANUM.matcher(upper).replaceAll("");
    }

    private static String sanitizeSku(String sku) {
        if (!StringUtils.hasText(sku)) {
            throw new IllegalArgumentException("SKU cannot be empty");
        }
        String upper = sku.trim().toUpperCase();
        upper = NON_SKU_CHAR.matcher(upper).replaceAll("");
        upper = upper.replaceAll("-{2,}", "-");
        if (upper.isBlank()) {
            throw new IllegalArgumentException("SKU cannot be empty");
        }
        return upper;
    }

    private static String sanitizeCode(String code) {
        String sanitized = sanitizeSegment(code);
        if (sanitized.length() > 12) {
            sanitized = sanitized.substring(0, 12);
        }
        if (sanitized.isBlank()) {
            sanitized = "BRAND";
        }
        return sanitized;
    }

    private static String cleanValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().replace(' ', '_').toUpperCase() : "GENERAL";
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(metadata);
    }

    private Map<String, Object> ensureFinishedGoodAccounts(Company company, String sku, Map<String, Object> metadata) {
        Map<String, Object> working = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        var defaults = companyDefaultAccountsService.requireDefaults();

        Map<String, Long> defaultsMap = new HashMap<>();
        defaultsMap.put("fgValuationAccountId", defaults.inventoryAccountId());
        defaultsMap.put("fgCogsAccountId", defaults.cogsAccountId());
        defaultsMap.put("fgRevenueAccountId", defaults.revenueAccountId());
        defaultsMap.put("fgDiscountAccountId", defaults.discountAccountId());
        defaultsMap.put("fgTaxAccountId", defaults.taxAccountId());

        for (String key : FINISHED_GOOD_ACCOUNT_KEYS) {
            Object candidate = working.get(key);
            if (!hasLongValue(candidate) && defaultsMap.get(key) != null) {
                working.put(key, defaultsMap.get(key));
            }
        }

        // Final validation: ensure required defaults are present so postings don't mis-map
        for (String key : List.of("fgValuationAccountId", "fgCogsAccountId", "fgRevenueAccountId", "fgTaxAccountId")) {
            if (!hasLongValue(working.get(key))) {
                throw new IllegalStateException("Default " + key + " is not configured for company " + company.getCode() +
                        ". Configure company default accounts to enable product posting.");
            }
        }
        return working;
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

    private record BrandResolution(ProductionBrand brand, boolean created) {}

    private record ProcessOutcome(boolean brandCreated, boolean productCreated, boolean rawMaterialSeeded) {}

    private record CatalogRow(String brand,
                              String productName,
                              String skuCode,
                              String category,
                              String defaultColour,
                              String unitOfMeasure,
                              String sizeLabel,
                              BigDecimal basePrice,
                              BigDecimal gstRate,
                              BigDecimal minDiscountPercent,
                              BigDecimal minSellingPrice,
                              Map<String, Object> metadata) {

        static CatalogRow from(CSVRecord record) {
            String brand = trim(record, "brand");
            String productName = trim(record, "product_name");
            if (!StringUtils.hasText(brand) && !StringUtils.hasText(productName)) {
                return null;
            }
            return new CatalogRow(
                    brand,
                    productName,
                    trim(record, "sku_code"),
                    trim(record, "category"),
                    trim(record, "default_colour"),
                    trim(record, "unit_of_measure"),
                    trim(record, "size"),
                    decimal(record, "base_price"),
                    decimal(record, "gst_rate"),
                    decimal(record, "min_discount_percent"),
                    decimal(record, "min_selling_price"),
                    metadata(record)
            );
        }

        private static String trim(CSVRecord record, String column) {
            Map<String, String> map = record.toMap();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                    String value = entry.getValue();
                    return StringUtils.hasText(value) ? value.trim() : null;
                }
            }
            return null;
        }

        private static BigDecimal decimal(CSVRecord record, String column) {
            String value = trim(record, column);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid numeric value in column '" + column + "': " + value);
            }
        }

        private static Long wholeNumber(CSVRecord record, String column) {
            String value = trim(record, column);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid numeric value in column '" + column + "': " + value);
            }
        }

        private static Map<String, Object> metadata(CSVRecord record) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            Map<String, Object> provided = parseJsonMap(trim(record, "metadata"), "metadata");
            if (provided != null && !provided.isEmpty()) {
                metadata.putAll(provided);
            }
            Map<String, BigDecimal> gstSlabs = parseGstSlabs(trim(record, "gst_slabs"));
            if (!gstSlabs.isEmpty()) {
                metadata.put("gstSlabs", gstSlabs);
            }
            appendAccountMetadata(metadata, record);
            return metadata.isEmpty() ? null : metadata;
        }

        private static Map<String, Object> parseJsonMap(String raw, String column) {
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
                return parsed == null || parsed.isEmpty() ? null : parsed;
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid JSON in column '" + column + "': " + ex.getOriginalMessage());
            }
        }

        private static Map<String, BigDecimal> parseGstSlabs(String raw) {
            if (!StringUtils.hasText(raw)) {
                return Map.of();
            }
            String text = raw.trim();
            Map<String, BigDecimal> slabs = new LinkedHashMap<>();
            if (text.startsWith("{") && text.endsWith("}")) {
                Map<String, Object> parsed = parseJsonMap(text, "gst_slabs");
                if (parsed != null) {
                    for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                        if (!StringUtils.hasText(entry.getKey())) {
                            continue;
                        }
                        slabs.put(entry.getKey().trim().toUpperCase(), coerceDecimal(entry.getValue(), "gst_slabs." + entry.getKey()));
                    }
                }
                return slabs;
            }
            String[] tokens = text.split("[|;,]");
            for (String token : tokens) {
                if (!StringUtils.hasText(token)) {
                    continue;
                }
                String[] pair = token.split(":");
                if (pair.length != 2) {
                    throw new IllegalArgumentException("Invalid gst_slabs entry '" + token + "'. Expected format STATE:RATE");
                }
                String state = pair[0].trim();
                String value = pair[1].trim();
                if (!StringUtils.hasText(state) || !StringUtils.hasText(value)) {
                    throw new IllegalArgumentException("Invalid gst_slabs entry '" + token + "'. Expected format STATE:RATE");
                }
                slabs.put(state.toUpperCase(), coerceDecimal(value, "gst_slabs." + state));
            }
            return slabs;
        }

        private static void appendAccountMetadata(Map<String, Object> metadata, CSVRecord record) {
            maybePut(metadata, "fgValuationAccountId", wholeNumber(record, "fg_valuation_account_id"));
            maybePut(metadata, "fgCogsAccountId", wholeNumber(record, "fg_cogs_account_id"));
            maybePut(metadata, "fgRevenueAccountId", wholeNumber(record, "fg_revenue_account_id"));
            maybePut(metadata, "fgDiscountAccountId", wholeNumber(record, "fg_discount_account_id"));
            maybePut(metadata, "fgTaxAccountId", wholeNumber(record, "fg_tax_account_id"));
            maybePut(metadata, "wipAccountId", wholeNumber(record, "wip_account_id"));
            maybePut(metadata, "semiFinishedAccountId", wholeNumber(record, "semi_finished_account_id"));
        }

        private static void maybePut(Map<String, Object> metadata, String key, Long value) {
            if (value != null) {
                metadata.put(key, value);
            }
        }

        private static BigDecimal coerceDecimal(Object value, String column) {
            if (value == null) {
                throw new IllegalArgumentException("Missing numeric value for " + column);
            }
            if (value instanceof BigDecimal bigDecimal) {
                return bigDecimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
                try {
                    return new BigDecimal(stringValue.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid numeric value '" + stringValue + "' in column '" + column + "'");
                }
            }
            throw new IllegalArgumentException("Invalid numeric value '" + value + "' in column '" + column + "'");
        }
    }
}
