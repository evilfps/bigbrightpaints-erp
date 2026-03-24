package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ProductionCatalogService {

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern NON_SKU_CHAR = Pattern.compile("[^A-Z0-9-]");
    private static final Pattern MULTI_VALUE_DELIMITER = Pattern.compile("[,;\\n]");
    private static final Pattern PACKED_MULTI_VALUE_TOKEN = Pattern.compile("[/,;\\r\\n]");
    private static final Pattern SEQUENCE_PATTERN = Pattern.compile(".*-(\\d{3})$");
    private static final String SEMI_FINISHED_SUFFIX = "-BULK";
    private static final String ITEM_CLASS_FINISHED_GOOD = "FINISHED_GOOD";
    private static final String ITEM_CLASS_RAW_MATERIAL = "RAW_MATERIAL";
    private static final String ITEM_CLASS_PACKAGING_RAW_MATERIAL = "PACKAGING_RAW_MATERIAL";
    private static final int MAX_CATALOG_FIELD_LENGTH = 2048;
    private static final int MAX_PRODUCT_SKU_LENGTH = 128;
    private static final int MAX_PRODUCT_NAME_LENGTH = 255;
    private static final int MAX_PRODUCT_FAMILY_NAME_LENGTH = 255;
    private static final int MAX_PRODUCT_UNIT_OF_MEASURE_LENGTH = 64;
    private static final int MAX_PRODUCT_HSN_CODE_LENGTH = 32;
    private static final Set<String> CATALOG_IMPORT_ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/csv",
            "application/vnd.ms-excel"
    );
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
    private static final String VARIANT_REASON_GENERATED = "GENERATED";
    private static final String VARIANT_REASON_WOULD_CREATE = "WOULD_CREATE";
    private static final String VARIANT_REASON_CREATED = "CREATED";
    private static final String VARIANT_REASON_SKU_ALREADY_EXISTS = "SKU_ALREADY_EXISTS";
    private static final String VARIANT_REASON_PRODUCT_NAME_ALREADY_EXISTS = "PRODUCT_NAME_ALREADY_EXISTS";
    private static final String VARIANT_REASON_DUPLICATE_IN_REQUEST = "DUPLICATE_IN_REQUEST";
    private static final String VARIANT_REASON_CONCURRENT_CONFLICT = "CONCURRENT_SKU_CONFLICT";
    private static final String CATALOG_ENTRY_OPERATION = "catalog-product-entry";
    private static final int BULK_VARIANT_AUDIT_SAMPLE_LIMIT = 12;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CompanyContextService companyContextService;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CatalogImportRepository catalogImportRepository;
    private final AuditService auditService;
    private final SkuReadinessService skuReadinessService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate rowTransactionTemplate;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();

    public ProductionCatalogService(CompanyContextService companyContextService,
                                    ProductionBrandRepository brandRepository,
                                    ProductionProductRepository productRepository,
                                    FinishedGoodRepository finishedGoodRepository,
                                    RawMaterialRepository rawMaterialRepository,
                                    CompanyEntityLookup companyEntityLookup,
                                    CompanyDefaultAccountsService companyDefaultAccountsService,
                                    CatalogImportRepository catalogImportRepository,
                                    AuditService auditService,
                                    SkuReadinessService skuReadinessService,
                                    PlatformTransactionManager transactionManager) {
        this.companyContextService = companyContextService;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.catalogImportRepository = catalogImportRepository;
        this.auditService = auditService;
        this.skuReadinessService = skuReadinessService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.rowTransactionTemplate = new TransactionTemplate(transactionManager);
        this.rowTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CatalogImportResponse importCatalog(MultipartFile file) {
        return importCatalog(file, null);
    }

    public CatalogImportResponse importCatalog(MultipartFile file, String idempotencyKey) {
        Company company = companyContextService.requireCurrentCompany();
        if (file == null || file.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("CSV file is required");
        }
        if (!isSupportedCatalogContentType(file)) {
            throw new ApplicationException(ErrorCode.FILE_INVALID_TYPE,
                    "Catalog import accepts CSV files only");
        }
        String fileHash = resolveFileHash(file);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey, fileHash);

        CatalogImport existing = catalogImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, fileHash, normalizedKey);
            return toResponse(existing);
        }

        try {
            CatalogImportResponse response = transactionTemplate.execute(status ->
                    importCatalogInternal(company, file, normalizedKey, fileHash));
            if (response == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Catalog import failed to return a response");
            }
            return response;
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            CatalogImport concurrent = catalogImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, fileHash, normalizedKey);
            return toResponse(concurrent);
        }
    }

    private CatalogImportResponse importCatalogInternal(Company company,
                                                        MultipartFile file,
                                                        String idempotencyKey,
                                                        String fileHash) {
        CatalogImport record = new CatalogImport();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(fileHash);
        record.setFileHash(fileHash);
        record.setFileName(file.getOriginalFilename());
        record = catalogImportRepository.saveAndFlush(record);

        CatalogImportResponse response = processCatalogImport(company, file);
        record.setRowsProcessed(response.rowsProcessed());
        record.setBrandsCreated(response.brandsCreated());
        record.setProductsCreated(response.productsCreated());
        record.setProductsUpdated(response.productsUpdated());
        record.setRawMaterialsSeeded(response.rawMaterialsSeeded());
        record.setErrorsJson(serializeErrors(response.errors()));
        catalogImportRepository.save(record);

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("operation", "catalog-import");
        auditMetadata.put("idempotencyKey", idempotencyKey);
        auditMetadata.put("fileHash", fileHash);
        auditMetadata.put("rowsProcessed", Integer.toString(response.rowsProcessed()));
        auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);

        return response;
    }

    private CatalogImportResponse processCatalogImport(Company company, MultipartFile file) {
        AtomicInteger rows = new AtomicInteger();
        AtomicInteger brandsCreated = new AtomicInteger();
        AtomicInteger productsCreated = new AtomicInteger();
        AtomicInteger productsUpdated = new AtomicInteger();
        AtomicInteger rawMaterialsSeeded = new AtomicInteger();
        List<CatalogImportResponse.ImportError> errors = new ArrayList<>();
        List<ImportRow> importRows = new ArrayList<>();

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
                    importRows.add(ImportRow.from(record, row));
                } catch (IllegalArgumentException ex) {
                    errors.add(new CatalogImportResponse.ImportError(record.getRecordNumber(), ex.getMessage()));
                }
            }

            ImportContext context = buildImportContext(company, importRows);
            for (ImportRow importRow : importRows) {
                try {
                    ProcessOutcome outcome = processCatalogRowWithRetry(company, importRow, context);
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
                    errors.add(new CatalogImportResponse.ImportError(importRow.recordNumber(), ex.getMessage()));
                } catch (Exception ex) {
                    errors.add(new CatalogImportResponse.ImportError(importRow.recordNumber(), "Unexpected error: " + ex.getMessage()));
                }
            }

            return new CatalogImportResponse(rows.get(), brandsCreated.get(), productsCreated.get(),
                    productsUpdated.get(), rawMaterialsSeeded.get(), errors);
        } catch (IOException ex) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Failed to read CSV file", ex);
        }
    }

    private ProcessOutcome processCatalogRowWithRetry(Company company, ImportRow importRow, ImportContext context) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ProcessOutcome outcome = rowTransactionTemplate.execute(status ->
                        upsertProduct(company, importRow, context));
                if (outcome == null) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Catalog import row did not return an outcome");
                }
                return outcome;
            } catch (RuntimeException ex) {
                lastError = ex;
                evictRowCache(company, importRow, context);
                if (!isRetryableImportFailure(ex) || attempt == 2) {
                    throw ex;
                }
            }
        }
        throw lastError;
    }

    private void evictRowCache(Company company, ImportRow importRow, ImportContext context) {
        Long importRowBrandId = null;
        if (importRow.brandKey() != null) {
            ProductionBrand cachedBrand = context.brandsByName().get(importRow.brandKey());
            if (cachedBrand != null) {
                importRowBrandId = cachedBrand.getId();
            }
            if (importRowBrandId == null && company != null) {
                importRowBrandId = brandRepository.findByCompanyAndNameIgnoreCase(company, importRow.brandKey())
                        .map(ProductionBrand::getId)
                        .orElse(null);
            }
        }
        if (importRow.brandKey() != null) {
            context.brandsByName().remove(importRow.brandKey());
        }
        if (StringUtils.hasText(importRow.sanitizedSku())) {
            context.productsBySku().remove(normalizeSkuKey(importRow.sanitizedSku()));
        }
        if (importRow.productKey() != null) {
            if (importRow.brandKey() != null && importRowBrandId == null) {
                pruneDriftedProductNameCacheEntries(company, importRow.productKey(), context);
                return;
            }
            for (var iterator = context.productsByBrandName().entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<ProductKey, ProductionProduct> entry = iterator.next();
                boolean matchesName = importRow.productKey().equals(entry.getKey().productNameKey());
                boolean matchesBrand = importRowBrandId == null || importRowBrandId.equals(entry.getKey().brandId());
                if (matchesName && matchesBrand) {
                    iterator.remove();
                }
            }
        }
    }

    private void pruneDriftedProductNameCacheEntries(Company company, String productKey, ImportContext context) {
        if (company == null || productKey == null) {
            return;
        }
        Map<Long, Boolean> productExistsById = new HashMap<>();
        for (var iterator = context.productsByBrandName().entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<ProductKey, ProductionProduct> entry = iterator.next();
            if (!productKey.equals(entry.getKey().productNameKey())) {
                continue;
            }
            if (isDriftedCachedProduct(company, entry.getValue(), productExistsById)) {
                iterator.remove();
            }
        }
    }

    private boolean isDriftedCachedProduct(Company company,
                                           ProductionProduct cachedProduct,
                                           Map<Long, Boolean> productExistsById) {
        if (cachedProduct == null || cachedProduct.getId() == null) {
            return true;
        }
        Long productId = cachedProduct.getId();
        Boolean exists = productExistsById.get(productId);
        if (exists == null) {
            exists = productRepository.findByCompanyAndId(company, productId).isPresent();
            productExistsById.put(productId, exists);
        }
        return !exists;
    }

    @Transactional
    public ProductionProductDto createProduct(ProductCreateRequest request) {
        return createProduct(request, null, null);
    }

    @Transactional
    public CatalogProductEntryResponse createOrPreviewCatalogProducts(CatalogProductEntryRequest request, boolean preview) {
        Company company = companyContextService.requireCurrentCompany();
        CatalogProductEntryPlan plan = prepareCatalogProductEntryPlan(company, request, preview);
        CatalogProductEntryResponse previewResponse = toCatalogProductEntryResponse(plan, List.of(), preview);

        if (preview) {
            return previewResponse;
        }

        if (!plan.conflicts().isEmpty()) {
            throw catalogProductEntryConflict(previewResponse,
                    "Canonical product request has SKU conflicts. Resolve conflicts and retry.");
        }

        List<CatalogProductEntryResponse.Member> created = new ArrayList<>();
        for (CatalogProductCandidate candidate : plan.candidatesToCreate()) {
            try {
                ProductionProductDto product = createProduct(
                        candidate.createRequest(),
                        plan.variantGroupId(),
                        plan.productFamilyName());
                created.add(candidate.toMember(
                        product.id(),
                        product.publicId(),
                        rawMaterialIdForSku(company, product.skuCode()),
                        skuReadinessService.forSku(company, product.skuCode(), expectedStockType(plan.itemClass()))));
            } catch (RuntimeException ex) {
                if (isVariantDuplicateConflict(ex, company, candidate.sku())) {
                    CatalogProductEntryResponse conflictResponse = toCatalogProductEntryResponse(
                            plan,
                            List.of(candidate.toConflict(VARIANT_REASON_CONCURRENT_CONFLICT)),
                            false);
                    throw catalogProductEntryConflict(conflictResponse,
                            "Canonical product write conflict for SKU " + candidate.sku()
                                    + ". Re-run with preview=true and resubmit.");
                }
                throw ex;
            }
        }

        return new CatalogProductEntryResponse(
                false,
                plan.variantGroupId(),
                plan.productFamilyName(),
                plan.brand().getId(),
                plan.brand().getName(),
                plan.brand().getCode(),
                plan.category(),
                plan.itemClass(),
                plan.unitOfMeasure(),
                plan.hsnCode(),
                plan.basePrice(),
                plan.gstRate(),
                plan.minDiscountPercent(),
                plan.minSellingPrice(),
                plan.metadata(),
                plan.generatedMembers().size(),
                plan.downstreamEffects(),
                List.copyOf(created),
                List.of());
    }

    private ProductionProductDto createProduct(ProductCreateRequest request,
                                               UUID variantGroupId,
                                               String productFamilyName) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.brandId() == null && !StringUtils.hasText(request.brandName())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Either brandId or brandName must be provided");
        }

        String normalizedItemClass = normalizeItemClass(request.itemClass());
        String normalizedCategory = categoryForItemClass(normalizedItemClass);
        BrandResolution resolution = resolveBrand(company, request.brandId(), request.brandName(), request.brandCode());
        ProductionBrand brand = resolution.brand();
        String baseName = request.productName().trim();
        if (!StringUtils.hasText(baseName)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Product name is required");
        }

        String detail = cleanValue(request.defaultColour());
        String sizeLabel = cleanValue(request.sizeLabel());
        validateSingleVariantField("defaultColour", detail);
        validateSingleVariantField("sizeLabel", sizeLabel);
        String displayName = composeItemDisplayName(normalizedItemClass, baseName, detail, sizeLabel);
        String sku = StringUtils.hasText(request.customSkuCode())
                ? sanitizeSku(request.customSkuCode())
                : buildCanonicalItemCode(normalizedItemClass, brand, baseName, detail, sizeLabel, request.unitOfMeasure());
        if (productRepository.findByCompanyAndSkuCode(company, sku).isPresent()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("SKU " + sku + " already exists");
        }
        assertNotReservedSemiFinishedSku(sku);

        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName(displayName);
        product.setCategory(normalizedCategory);
        product.setDefaultColour(detail);
        product.setSizeLabel(sizeLabel);
        product.setColors(singleVariantSet(product.getDefaultColour()));
        product.setSizes(singleVariantSet(product.getSizeLabel()));
        product.setCartonSizes(defaultCartonSizes(product.getSizeLabel()));
        product.setUnitOfMeasure(cleanValue(request.unitOfMeasure()));
        product.setHsnCode(cleanValue(request.hsnCode()));
        product.setSkuCode(sku);
        product.setVariantGroupId(variantGroupId);
        product.setProductFamilyName(cleanValue(productFamilyName != null ? productFamilyName : baseName));
        product.setActive(request.active() == null || request.active());
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
        ensureCatalogFinishedGood(company, saved);
        syncRawMaterial(company, saved, normalizedItemClass);
        return toProductDto(saved);
    }

    private Set<String> singleVariantSet(String value) {
        String cleaned = cleanValue(value);
        return StringUtils.hasText(cleaned)
                ? new LinkedHashSet<>(List.of(cleaned))
                : new LinkedHashSet<>();
    }

    private Map<String, Integer> defaultCartonSizes(String sizeLabel) {
        String cleaned = cleanValue(sizeLabel);
        return StringUtils.hasText(cleaned)
                ? new LinkedHashMap<>(Map.of(cleaned, 1))
                : new LinkedHashMap<>();
    }

    private CatalogProductEntryPlan prepareCatalogProductEntryPlan(Company company,
                                                                  CatalogProductEntryRequest request,
                                                                  boolean preview) {
        if (request == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Canonical product request is required");
        }
        if (!request.getUnknownFields().isEmpty()) {
            String unsupported = request.getUnknownFields().keySet().stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Unsupported fields in canonical product request: " + unsupported);
        }

        ProductionBrand brand = requireActiveBrand(company, request.getBrandId());
        String productFamilyName = requireCanonicalToken(request.getBaseProductName(), "baseProductName");
        validateCanonicalPersistedTextLength(
                productFamilyName,
                "productFamilyName",
                MAX_PRODUCT_FAMILY_NAME_LENGTH,
                "shorten baseProductName");
        String normalizedItemClass = resolveEntryItemClass(request);
        String normalizedCategory = categoryForItemClass(normalizedItemClass);
        String unitOfMeasure = requireCanonicalToken(request.getUnitOfMeasure(), "unitOfMeasure");
        validateCanonicalPersistedTextLength(
                unitOfMeasure,
                "unitOfMeasure",
                MAX_PRODUCT_UNIT_OF_MEASURE_LENGTH,
                "shorten unitOfMeasure");
        String hsnCode = requireCanonicalToken(request.getHsnCode(), "hsnCode");
        validateCanonicalPersistedTextLength(
                hsnCode,
                "hsnCode",
                MAX_PRODUCT_HSN_CODE_LENGTH,
                "shorten hsnCode");
        BigDecimal basePrice = validateCanonicalMoney(request.getBasePrice(), "basePrice");
        BigDecimal gstRate = validateCanonicalPercent(request.getGstRate(), "gstRate", true);
        BigDecimal minDiscountPercent = validateCanonicalPercent(request.getMinDiscountPercent(), "minDiscountPercent", false);
        BigDecimal minSellingPrice = validateCanonicalMoney(request.getMinSellingPrice(), "minSellingPrice");
        Map<String, Object> metadata = normalizeMetadata(request.getMetadata());

        List<String> colors = normalizeCanonicalTokens(request.getColors(), "colors");
        List<String> sizes = normalizeCanonicalTokens(request.getSizes(), "sizes");
        UUID variantGroupId = buildVariantGroupId(company, brand, productFamilyName, normalizedItemClass, unitOfMeasure, hsnCode, colors, sizes);

        String brandCode = sanitizeCode(brand.getCode());
        String productFamilyCode = requireCanonicalSkuFragment("baseProductName", productFamilyName, Integer.MAX_VALUE);
        String previewSku = buildDeterministicSku(normalizedItemClass, brandCode, productFamilyCode, colors.getFirst(), sizes.getFirst());
        metadata = validateCanonicalEntryMetadata(company, normalizedCategory, previewSku, metadata, !preview);

        List<CatalogProductCandidate> generatedCandidates = new ArrayList<>();
        for (String color : colors) {
            for (String size : sizes) {
                String sku = buildDeterministicSku(normalizedItemClass, brandCode, productFamilyCode, color, size);
                String productName = productFamilyName + " " + color + " " + size;
                validateCanonicalPersistedTextLength(
                        productName,
                        "productName",
                        MAX_PRODUCT_NAME_LENGTH,
                        "shorten baseProductName, color, or size");
                ProductCreateRequest createRequest = new ProductCreateRequest(
                        brand.getId(),
                        null,
                        null,
                        productName,
                        normalizedCategory,
                        normalizedItemClass,
                        color,
                        size,
                        unitOfMeasure,
                        hsnCode,
                        sku,
                        basePrice,
                        gstRate,
                        minDiscountPercent,
                        minSellingPrice,
                        metadata);
                generatedCandidates.add(new CatalogProductCandidate(sku, color, size, productName, createRequest));
            }
        }

        Set<String> duplicateSkuKeys = generatedCandidates.stream()
                .collect(Collectors.groupingBy(candidate -> normalizeSkuKey(candidate.sku()), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        Set<String> generatedSkuKeys = generatedCandidates.stream()
                .map(CatalogProductCandidate::sku)
                .map(ProductionCatalogService::normalizeSkuKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> existingSkuKeys = productRepository.findByCompanyAndSkuCodeIn(company, generatedSkuKeys).stream()
                .map(ProductionProduct::getSkuCode)
                .map(ProductionCatalogService::normalizeSkuKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, ProductionProduct> existingProductsByName = new LinkedHashMap<>();
        for (CatalogProductCandidate candidate : generatedCandidates) {
            String productNameKey = normalizeKey(candidate.productName());
            if (!StringUtils.hasText(productNameKey) || existingProductsByName.containsKey(productNameKey)) {
                continue;
            }
            existingProductsByName.put(
                    productNameKey,
                    productRepository.findByBrandAndProductNameIgnoreCase(brand, candidate.productName()).orElse(null));
        }

        List<CatalogProductEntryResponse.Member> generatedMembers = new ArrayList<>();
        List<CatalogProductEntryResponse.Conflict> conflicts = new ArrayList<>();
        List<CatalogProductCandidate> candidatesToCreate = new ArrayList<>();
        for (CatalogProductCandidate candidate : generatedCandidates) {
                generatedMembers.add(candidate.toMember(null, null, null, previewMemberReadiness(company, normalizedItemClass, normalizedCategory, candidate)));
            String skuKey = normalizeSkuKey(candidate.sku());
            String productNameKey = normalizeKey(candidate.productName());
            if (duplicateSkuKeys.contains(skuKey)) {
                conflicts.add(candidate.toConflict(VARIANT_REASON_DUPLICATE_IN_REQUEST));
                continue;
            }
            if (existingSkuKeys.contains(skuKey)) {
                conflicts.add(candidate.toConflict(VARIANT_REASON_SKU_ALREADY_EXISTS));
                continue;
            }
            ProductionProduct existingProductByName = StringUtils.hasText(productNameKey)
                    ? existingProductsByName.get(productNameKey)
                    : null;
            if (existingProductByName != null
                    && !Objects.equals(normalizeSkuKey(existingProductByName.getSkuCode()), skuKey)) {
                conflicts.add(candidate.toConflict(VARIANT_REASON_PRODUCT_NAME_ALREADY_EXISTS));
                continue;
            }
            candidatesToCreate.add(candidate);
        }

        CatalogProductEntryResponse.DownstreamEffects downstreamEffects = new CatalogProductEntryResponse.DownstreamEffects(
                isRawMaterialCategory(normalizedCategory) ? 0 : generatedMembers.size(),
                isRawMaterialCategory(normalizedCategory) ? generatedMembers.size() : 0);

        return new CatalogProductEntryPlan(
                brand,
                variantGroupId,
                productFamilyName,
                normalizedCategory,
                normalizedItemClass,
                unitOfMeasure,
                hsnCode,
                basePrice,
                gstRate,
                minDiscountPercent,
                minSellingPrice,
                metadata,
                List.copyOf(candidatesToCreate),
                List.copyOf(generatedMembers),
                List.copyOf(conflicts),
                downstreamEffects);
    }

    private CatalogProductEntryPlan prepareCatalogProductEntryPlan(Company company, CatalogProductEntryRequest request) {
        return prepareCatalogProductEntryPlan(company, request, false);
    }

    private CatalogProductEntryResponse toCatalogProductEntryResponse(CatalogProductEntryPlan plan,
                                                                     List<CatalogProductEntryResponse.Conflict> conflicts,
                                                                     boolean preview) {
        List<CatalogProductEntryResponse.Conflict> effectiveConflicts = conflicts == null || conflicts.isEmpty()
                ? plan.conflicts()
                : List.copyOf(conflicts);
        return new CatalogProductEntryResponse(
                preview,
                plan.variantGroupId(),
                plan.productFamilyName(),
                plan.brand().getId(),
                plan.brand().getName(),
                plan.brand().getCode(),
                plan.category(),
                plan.itemClass(),
                plan.unitOfMeasure(),
                plan.hsnCode(),
                plan.basePrice(),
                plan.gstRate(),
                plan.minDiscountPercent(),
                plan.minSellingPrice(),
                plan.metadata(),
                plan.generatedMembers().size(),
                plan.downstreamEffects(),
                plan.generatedMembers(),
                effectiveConflicts);
    }

    private SkuReadinessDto previewMemberReadiness(Company company,
                                                   String itemClass,
                                                   String category,
                                                   CatalogProductCandidate candidate) {
        ProductionProduct draftProduct = new ProductionProduct();
        draftProduct.setCompany(company);
        draftProduct.setSkuCode(candidate.sku());
        draftProduct.setProductName(candidate.productName());
        draftProduct.setCategory(category);
        draftProduct.setDefaultColour(candidate.color());
        draftProduct.setSizeLabel(candidate.size());
        draftProduct.setUnitOfMeasure(cleanValue(candidate.createRequest().unitOfMeasure()));
        draftProduct.setGstRate(candidate.createRequest().gstRate());
        draftProduct.setMetadata(normalizeMetadata(candidate.createRequest().metadata()));
        draftProduct.setActive(true);

        if (isRawMaterialCategory(category)) {
            RawMaterial projectedRawMaterial = new RawMaterial();
            projectedRawMaterial.setCompany(company);
            projectedRawMaterial.setSku(candidate.sku());
            projectedRawMaterial.setName(candidate.productName());
            projectedRawMaterial.setUnitType(resolveUnit(draftProduct.getUnitOfMeasure()));
            projectedRawMaterial.setInventoryAccountId(resolveRawMaterialInventoryAccountId(company, draftProduct));
            if (ITEM_CLASS_PACKAGING_RAW_MATERIAL.equals(itemClass)) {
                projectedRawMaterial.setMaterialType(com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PACKAGING);
            }
            return skuReadinessService.forPlannedProduct(
                    draftProduct,
                    expectedStockType(itemClass),
                    null,
                    projectedRawMaterial);
        }

        FinishedGood projectedFinishedGood = new FinishedGood();
        projectedFinishedGood.setCompany(company);
        projectedFinishedGood.setProductCode(candidate.sku());
        projectedFinishedGood.setName(candidate.productName());
        projectedFinishedGood.setUnit(resolveUnit(draftProduct.getUnitOfMeasure()));
        projectedFinishedGood.setValuationAccountId(metadataLong(draftProduct, "fgValuationAccountId"));
        projectedFinishedGood.setCogsAccountId(metadataLong(draftProduct, "fgCogsAccountId"));
        projectedFinishedGood.setRevenueAccountId(metadataLong(draftProduct, "fgRevenueAccountId"));
        projectedFinishedGood.setTaxAccountId(metadataLong(draftProduct, "fgTaxAccountId"));
        projectedFinishedGood.setDiscountAccountId(metadataLong(draftProduct, "fgDiscountAccountId"));
        return skuReadinessService.forPlannedProduct(
                draftProduct,
                expectedStockType(itemClass),
                projectedFinishedGood,
                null);
    }

    private SkuReadinessService.ExpectedStockType expectedStockType(String itemClass) {
        return switch (normalizeItemClass(itemClass)) {
            case ITEM_CLASS_RAW_MATERIAL -> SkuReadinessService.ExpectedStockType.RAW_MATERIAL;
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL -> SkuReadinessService.ExpectedStockType.PACKAGING_RAW_MATERIAL;
            default -> SkuReadinessService.ExpectedStockType.FINISHED_GOOD;
        };
    }

    private ApplicationException catalogProductEntryConflict(CatalogProductEntryResponse response, String message) {
        return new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, message)
                .withDetail("operation", CATALOG_ENTRY_OPERATION)
                .withDetail("generated", response.members())
                .withDetail("conflicts", response.conflicts())
                .withDetail("wouldCreate", response.members().stream()
                        .filter(member -> response.conflicts().stream()
                                .noneMatch(conflict -> normalizeSkuKey(conflict.sku()).equals(normalizeSkuKey(member.sku()))))
                        .toList())
                .withDetail("created", List.of());
    }

    private ProductionBrand requireActiveBrand(Company company, Long brandId) {
        if (brandId == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("brandId is required");
        }
        ProductionBrand brand = brandRepository.findByCompanyAndId(company, brandId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Brand not found for id " + brandId));
        if (!brand.isActive()) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Brand '" + brand.getName() + "' is inactive");
        }
        return brand;
    }

    private List<String> normalizeCanonicalTokens(List<String> rawValues, String fieldName) {
        if (rawValues == null || rawValues.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(fieldName + " must contain at least one value");
        }
        List<String> normalized = new ArrayList<>();
        for (String rawValue : rawValues) {
            String cleaned = requireCanonicalToken(rawValue, fieldName);
            if (PACKED_MULTI_VALUE_TOKEN.matcher(cleaned).find()) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        fieldName + " entries must be single canonical values; packed multi-value tokens are not supported");
            }
            normalized.add(cleaned);
        }
        return List.copyOf(normalized);
    }

    private String requireCanonicalToken(String value, String fieldName) {
        String cleaned = cleanValue(value);
        if (!StringUtils.hasText(cleaned)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(fieldName + " is required");
        }
        return cleaned;
    }

    private UUID buildVariantGroupId(Company company,
                                     ProductionBrand brand,
                                     String productFamilyName,
                                     String itemClass,
                                     String unitOfMeasure,
                                     String hsnCode,
                                     List<String> colors,
                                     List<String> sizes) {
        String fingerprint = String.join("|",
                String.valueOf(company != null ? company.getId() : null),
                String.valueOf(brand != null ? brand.getId() : null),
                sanitizeSegment(productFamilyName),
                sanitizeSegment(itemClass),
                sanitizeSegment(unitOfMeasure),
                sanitizeSegment(hsnCode));
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private String buildDeterministicSku(String itemClass,
                                     String brandCode,
                                     String productFamilyCode,
                                     String color,
                                     String size) {
        String stockPrefix = itemClassSkuPrefix(itemClass);
        String normalizedBrandCode = requireCanonicalSkuFragment("brandCode", brandCode, 12);
        String colorCode = requireCanonicalSkuFragment("colors", color, 16);
        String sizeCode = requireCanonicalSkuFragment("sizes", size, 16);
        String sku = String.join("-", List.of(stockPrefix, normalizedBrandCode, productFamilyCode, colorCode, sizeCode))
                .replaceAll("-{2,}", "-");
        if (sku.length() > MAX_PRODUCT_SKU_LENGTH) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Canonical product SKU exceeds 128 characters; shorten brand, baseProductName, color, or size");
        }
        assertNotReservedSemiFinishedSku(sku);
        return sku;
    }

    private String buildCanonicalItemCode(String itemClass,
                                          ProductionBrand brand,
                                          String baseName,
                                          String detail,
                                          String sizeLabel,
                                          String unitOfMeasure) {
        String normalizedItemClass = normalizeItemClass(itemClass);
        return switch (normalizedItemClass) {
            case ITEM_CLASS_FINISHED_GOOD -> String.join("-", List.of(
                    "FG",
                    requireCanonicalSkuFragment("brandCode", brand != null ? brand.getCode() : null, 12),
                    requireCanonicalSkuFragment("name", baseName, 40),
                    requireCanonicalSkuFragment("color", detail, 24),
                    requireCanonicalSkuFragment("size", sizeLabel, 24)));
            case ITEM_CLASS_RAW_MATERIAL -> String.join("-", List.of(
                    "RM",
                    requireCanonicalSkuFragment("brandCode", brand != null ? brand.getCode() : null, 12),
                    requireCanonicalSkuFragment("name", baseName, 48),
                    requireCanonicalSkuFragment("spec", detail, 24),
                    requireCanonicalSkuFragment("unitOfMeasure", unitOfMeasure, 16)));
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL -> String.join("-", List.of(
                    "PKG",
                    requireCanonicalSkuFragment("brandCode", brand != null ? brand.getCode() : null, 12),
                    requireCanonicalSkuFragment("packType", baseName, 32),
                    requireCanonicalSkuFragment("size", sizeLabel, 24),
                    requireCanonicalSkuFragment("unitOfMeasure", unitOfMeasure, 16)));
            default -> throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "itemClass is required (FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL)");
        };
    }

    private String composeItemDisplayName(String itemClass,
                                          String baseName,
                                          String detail,
                                          String sizeLabel) {
        String normalizedBaseName = requireCanonicalToken(baseName, "name");
        String normalizedItemClass = normalizeItemClass(itemClass);
        return switch (normalizedItemClass) {
            case ITEM_CLASS_FINISHED_GOOD -> normalizedBaseName
                    + " " + requireCanonicalToken(detail, "color")
                    + " " + requireCanonicalToken(sizeLabel, "size");
            case ITEM_CLASS_RAW_MATERIAL -> StringUtils.hasText(detail)
                    ? normalizedBaseName + " " + requireCanonicalToken(detail, "spec")
                    : normalizedBaseName;
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL -> normalizedBaseName
                    + " " + requireCanonicalToken(sizeLabel, "size");
            default -> normalizedBaseName;
        };
    }

    private void assertImmutableIdentityField(String fieldName, String existingValue, String requestedValue) {
        if (Objects.equals(existingValue, requestedValue)) {
            return;
        }
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                fieldName + " is immutable after item creation; create a new item instead");
    }

    private String requireCanonicalSkuFragment(String fieldName, String rawValue, int maxLength) {
        String sanitized = sanitizeSkuFragment(rawValue);
        sanitized = maxLength > 0 ? truncate(sanitized, maxLength) : sanitized;
        if (!StringUtils.hasText(sanitized)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Canonical product " + fieldName + " must contain at least one alphanumeric SKU character");
        }
        return sanitized;
    }

    private void validateCanonicalPersistedTextLength(String value,
                                                      String persistedFieldName,
                                                      int maxLength,
                                                      String remedy) {
        if (StringUtils.hasText(value) && value.length() > maxLength) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Canonical product " + persistedFieldName + " exceeds " + maxLength + " characters; " + remedy);
        }
    }

    private BigDecimal validateCanonicalMoney(BigDecimal value, String fieldName) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(fieldName + " cannot be negative");
        }
        return money(value);
    }

    private BigDecimal validateCanonicalPercent(BigDecimal value, String fieldName, boolean required) {
        if (value == null) {
            if (required) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(fieldName + " is required");
            }
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(fieldName + " cannot be negative");
        }
        if (value.compareTo(new BigDecimal("100")) > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    fieldName + " cannot be greater than 100");
        }
        return percent(value);
    }

    /**
     * Bulk variant creation: generates SKUs for each (color x size) and creates products if missing.
     */
    @Transactional
    public BulkVariantResponse createVariants(BulkVariantRequest request) {
        return createVariants(request, false);
    }

    @Transactional
    public BulkVariantResponse createVariants(BulkVariantRequest request, boolean dryRun) {
        Company company = companyContextService.requireCurrentCompany();
        VariantExecutionPlan plan = prepareVariantExecutionPlan(company, request);
        BulkVariantResponse dryRunResponse = toVariantResponse(plan, plan.conflicts(), List.of());

        if (dryRun) {
            auditBulkVariantOutcome(company, plan, dryRunResponse, true, true, null);
            return dryRunResponse;
        }

        if (!plan.conflicts().isEmpty()) {
            auditBulkVariantOutcome(company, plan, dryRunResponse, false, false, "pre-validation-conflicts");
            throw bulkVariantConflictException(dryRunResponse,
                    "Bulk variant request has SKU conflicts. Resolve conflicts and retry.");
        }

        List<BulkVariantResponse.VariantItem> created = new ArrayList<>();
        for (VariantCandidate candidate : plan.candidatesToCreate()) {
            try {
                createProduct(candidate.createRequest());
                created.add(candidate.toItem(VARIANT_REASON_CREATED));
            } catch (RuntimeException ex) {
                if (isVariantDuplicateConflict(ex, company, candidate.sku())) {
                    BulkVariantResponse raceConflictResponse = toVariantResponse(
                            plan,
                            List.of(candidate.toItem(VARIANT_REASON_CONCURRENT_CONFLICT)),
                            List.of());
                    auditBulkVariantOutcome(company, plan, raceConflictResponse, false, false, "write-time-conflict");
                    throw bulkVariantConflictException(
                            raceConflictResponse,
                            "Bulk variant write conflict for SKU " + candidate.sku()
                                    + ". Re-run with dryRun=true and resubmit.");
                }
                throw ex;
            }
        }

        BulkVariantResponse committedResponse = toVariantResponse(plan, List.of(), created);
        auditBulkVariantOutcome(company, plan, committedResponse, false, true, null);
        return committedResponse;
    }

    private VariantExecutionPlan prepareVariantExecutionPlan(Company company, BulkVariantRequest request) {
        Map<String, String> colorsByKey = new LinkedHashMap<>();
        for (String color : expandTokens(request.colors())) {
            colorsByKey.putIfAbsent(IdempotencyUtils.normalizeUpperToken(color), color);
        }
        Map<String, ColorSizeSpec> colorSizeMatrix = expandColorSizeMatrix(request.colorSizeMatrix());
        for (ColorSizeSpec matrixEntry : colorSizeMatrix.values()) {
            colorsByKey.putIfAbsent(IdempotencyUtils.normalizeUpperToken(matrixEntry.color()), matrixEntry.color());
        }
        List<String> globalSizes = expandTokens(request.sizes());

        if (colorsByKey.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("At least one color is required");
        }
        if (globalSizes.isEmpty() && colorSizeMatrix.values().stream().allMatch(entry -> entry.sizes().isEmpty())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("At least one size is required");
        }

        String normalizedCategory = normalizeCategory(request.category());
        VariantBrandPlan brandPlan = resolveBrandPlanForVariantPlanning(
                company,
                request.brandId(),
                request.brandName(),
                request.brandCode());
        String baseName = request.baseProductName().trim();
        String unit = StringUtils.hasText(request.unitOfMeasure()) ? request.unitOfMeasure().trim() : "UNIT";
        String normalizedItemClass = normalizeVariantItemClass(normalizedCategory);
        String normalizedBrandCode = sanitizeCode(brandPlan.brandCode());
        String baseSkuFragment = requireVariantSkuFragment("baseProductName", baseName, Integer.MAX_VALUE);

        List<VariantCandidate> generatedCandidates = new ArrayList<>();
        for (Map.Entry<String, String> colorEntry : colorsByKey.entrySet()) {
            String color = colorEntry.getValue();
            ColorSizeSpec matrixEntry = colorSizeMatrix.get(colorEntry.getKey());
            List<String> sizes = matrixEntry != null && !matrixEntry.sizes().isEmpty()
                    ? matrixEntry.sizes()
                    : globalSizes;
            if (sizes.isEmpty()) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("At least one size is required for color '" + color + "'");
            }
            String colorCode = requireVariantSkuFragment("color", color, 8);
            for (String size : sizes) {
                String sizeCode = requireVariantSkuFragment("size", size, 8);
                String sku = String.join("-",
                        List.of(itemClassSkuPrefix(normalizedItemClass), normalizedBrandCode, baseSkuFragment, colorCode, sizeCode))
                        .replaceAll("-+", "-");
                ProductCreateRequest createRequest = new ProductCreateRequest(
                        brandPlan.brandId(),
                        brandPlan.brandId() == null ? brandPlan.brandName() : null,
                        brandPlan.brandId() == null ? brandPlan.brandCode() : null,
                        baseName + " " + color + " " + size,
                        normalizedCategory,
                        normalizedItemClass,
                        color,
                        size,
                        unit,
                        null,
                        sku,
                        request.basePrice(), request.gstRate(),
                        request.minDiscountPercent(), request.minSellingPrice(),
                        request.metadata()
                );
                generatedCandidates.add(new VariantCandidate(sku, color, size, createRequest.productName(), createRequest));
            }
        }

        Set<String> duplicateSkuKeys = generatedCandidates.stream()
                .collect(Collectors.groupingBy(candidate -> normalizeSkuKey(candidate.sku()),
                        LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        Set<String> generatedSkuKeys = generatedCandidates.stream()
                .map(candidate -> normalizeSkuKey(candidate.sku()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> existingSkuKeys = generatedSkuKeys.isEmpty()
                ? Set.of()
                : productRepository.findByCompanyAndSkuCodeIn(company, generatedSkuKeys).stream()
                .map(ProductionProduct::getSkuCode)
                .map(ProductionCatalogService::normalizeSkuKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<BulkVariantResponse.VariantItem> generated = new ArrayList<>();
        List<BulkVariantResponse.VariantItem> conflicts = new ArrayList<>();
        List<BulkVariantResponse.VariantItem> wouldCreate = new ArrayList<>();
        List<VariantCandidate> candidatesToCreate = new ArrayList<>();

        for (VariantCandidate candidate : generatedCandidates) {
            generated.add(candidate.toItem(VARIANT_REASON_GENERATED));
            String skuKey = normalizeSkuKey(candidate.sku());
            if (duplicateSkuKeys.contains(skuKey)) {
                conflicts.add(candidate.toItem(VARIANT_REASON_DUPLICATE_IN_REQUEST));
                continue;
            }
            if (existingSkuKeys.contains(skuKey)) {
                conflicts.add(candidate.toItem(VARIANT_REASON_SKU_ALREADY_EXISTS));
                continue;
            }
            wouldCreate.add(candidate.toItem(VARIANT_REASON_WOULD_CREATE));
            candidatesToCreate.add(candidate);
        }

        return new VariantExecutionPlan(
                brandPlan,
                baseName,
                normalizedCategory,
                List.copyOf(candidatesToCreate),
                List.copyOf(generated),
                List.copyOf(conflicts),
                List.copyOf(wouldCreate));
    }

    private VariantBrandPlan resolveBrandPlanForVariantPlanning(Company company,
                                                                Long brandId,
                                                                String brandName,
                                                                String providedCode) {
        if (brandId != null) {
            ProductionBrand brand = companyEntityLookup.requireProductionBrand(company, brandId);
            return new VariantBrandPlan(brand.getId(), brand.getName(), brand.getCode());
        }
        if (!StringUtils.hasText(brandName)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Brand is required");
        }
        String effectiveName = brandName.trim();
        if (StringUtils.hasText(providedCode)) {
            Optional<ProductionBrand> byCode = brandRepository.findByCompanyAndCodeIgnoreCase(company, sanitizeCode(providedCode));
            if (byCode.isPresent()) {
                ProductionBrand brand = byCode.get();
                return new VariantBrandPlan(brand.getId(), brand.getName(), brand.getCode());
            }
        }
        Optional<ProductionBrand> byName = brandRepository.findByCompanyAndNameIgnoreCase(company, effectiveName);
        if (byName.isPresent()) {
            ProductionBrand brand = byName.get();
            return new VariantBrandPlan(brand.getId(), brand.getName(), brand.getCode());
        }
        String plannedCode = nextBrandCode(company, providedCode != null ? providedCode : effectiveName);
        return new VariantBrandPlan(null, effectiveName, plannedCode);
    }

    private BulkVariantResponse toVariantResponse(VariantExecutionPlan plan,
                                                  List<BulkVariantResponse.VariantItem> conflicts,
                                                  List<BulkVariantResponse.VariantItem> created) {
        Set<String> conflictSkuKeys = conflicts == null
                ? Set.of()
                : conflicts.stream()
                .map(BulkVariantResponse.VariantItem::sku)
                .map(ProductionCatalogService::normalizeSkuKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<BulkVariantResponse.VariantItem> filteredWouldCreate = plan.wouldCreate().stream()
                .filter(item -> !conflictSkuKeys.contains(normalizeSkuKey(item.sku())))
                .toList();
        return new BulkVariantResponse(
                plan.generated(),
                conflicts == null ? List.of() : List.copyOf(conflicts),
                filteredWouldCreate,
                created == null ? List.of() : List.copyOf(created));
    }

    private ApplicationException bulkVariantConflictException(BulkVariantResponse response, String message) {
        return new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, message)
                .withDetail("operation", "catalog-bulk-variants")
                .withDetail("generated", response.generated())
                .withDetail("conflicts", response.conflicts())
                .withDetail("wouldCreate", response.wouldCreate())
                .withDetail("created", response.created());
    }

    private void auditBulkVariantOutcome(Company company,
                                         VariantExecutionPlan plan,
                                         BulkVariantResponse response,
                                         boolean dryRun,
                                         boolean success,
                                         String failureReason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("operation", "catalog-bulk-variants");
        metadata.put("mode", dryRun ? "dry-run" : "commit");
        metadata.put("companyId", company != null && company.getId() != null ? company.getId().toString() : "");
        metadata.put("brandId",
                plan.brandPlan() != null && plan.brandPlan().brandId() != null
                        ? plan.brandPlan().brandId().toString()
                        : "");
        metadata.put("brandCode",
                safeAuditValue(plan.brandPlan() != null ? plan.brandPlan().brandCode() : null));
        metadata.put("brandName",
                safeAuditValue(plan.brandPlan() != null ? plan.brandPlan().brandName() : null));
        metadata.put("baseProductName", safeAuditValue(plan.baseProductName()));
        metadata.put("category", safeAuditValue(plan.category()));
        metadata.put("generatedCount", Integer.toString(response.generated().size()));
        metadata.put("conflictCount", Integer.toString(response.conflicts().size()));
        metadata.put("wouldCreateCount", Integer.toString(response.wouldCreate().size()));
        metadata.put("createdCount", Integer.toString(response.created().size()));
        metadata.put("conflictSkus", summarizeAuditSkus(response.conflicts()));
        metadata.put("wouldCreateSkus", summarizeAuditSkus(response.wouldCreate()));
        if (!success && StringUtils.hasText(failureReason)) {
            metadata.put("reason", failureReason);
        }
        if (success) {
            auditService.logSuccess(dryRun ? AuditEvent.DATA_READ : AuditEvent.DATA_CREATE, metadata);
            return;
        }
        auditService.logFailure(AuditEvent.DATA_CREATE, metadata);
    }

    private String summarizeAuditSkus(List<BulkVariantResponse.VariantItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream()
                .map(BulkVariantResponse.VariantItem::sku)
                .filter(StringUtils::hasText)
                .limit(BULK_VARIANT_AUDIT_SAMPLE_LIMIT)
                .collect(Collectors.joining(","));
    }

    private String safeAuditValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String resolveVariantPrefix(String requestedPrefix, String brandCode) {
        String source = StringUtils.hasText(requestedPrefix) ? requestedPrefix : brandCode;
        String prefix = sanitizeSkuFragment(source);
        if (!StringUtils.hasText(prefix)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Bulk variant skuPrefix/brandCode must contain at least one alphanumeric SKU character");
        }
        return prefix;
    }

    private String requireVariantSkuFragment(String fieldName, String rawValue, int maxLength) {
        String sanitized = sanitizeSkuFragment(rawValue);
        sanitized = maxLength > 0 ? truncate(sanitized, maxLength) : sanitized;
        if (!StringUtils.hasText(sanitized)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Bulk variant " + fieldName + " must contain at least one alphanumeric SKU character");
        }
        return sanitized;
    }

    private List<String> expandTokens(List<String> items) {
        if (items == null) return List.of();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String raw : items) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String[] chunks = raw.split("[,;\\n]+");
            for (String chunk : chunks) {
                if (!StringUtils.hasText(chunk)) {
                    continue;
                }
                String token = chunk.trim();
                tokens.putIfAbsent(IdempotencyUtils.normalizeUpperToken(token), token);
            }
        }
        return List.copyOf(tokens.values());
    }

    private Map<String, ColorSizeSpec> expandColorSizeMatrix(List<BulkVariantRequest.ColorSizeMatrixEntry> entries) {
        if (entries == null) {
            return Map.of();
        }
        Map<String, ColorSizeSpec> matrix = new LinkedHashMap<>();
        for (BulkVariantRequest.ColorSizeMatrixEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.color())) {
                continue;
            }
            List<String> colors = expandTokens(List.of(entry.color()));
            if (colors.isEmpty()) {
                continue;
            }
            List<String> sizes = expandTokens(entry.sizes());
            for (String color : colors) {
                String colorKey = IdempotencyUtils.normalizeUpperToken(color);
                ColorSizeSpec existing = matrix.get(colorKey);
                if (existing == null) {
                    matrix.put(colorKey, new ColorSizeSpec(color, sizes));
                    continue;
                }
                matrix.put(colorKey, new ColorSizeSpec(existing.color(), mergeTokens(existing.sizes(), sizes)));
            }
        }
        return matrix;
    }

    private List<String> mergeTokens(List<String> first, List<String> second) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (first != null) {
            for (String token : first) {
                if (StringUtils.hasText(token)) {
                    merged.putIfAbsent(IdempotencyUtils.normalizeUpperToken(token), token.trim());
                }
            }
        }
        if (second != null) {
            for (String token : second) {
                if (StringUtils.hasText(token)) {
                    merged.putIfAbsent(IdempotencyUtils.normalizeUpperToken(token), token.trim());
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private String resolveEffectiveSizeLabel(String sizeLabel, String unitOfMeasure) {
        if (StringUtils.hasText(sizeLabel)) {
            return sizeLabel.trim();
        }
        return StringUtils.hasText(unitOfMeasure) ? unitOfMeasure.trim() : unitOfMeasure;
    }

    private void validateSingleVariantField(String fieldName, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (MULTI_VALUE_DELIMITER.matcher(value).find()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Multiple values in '" + fieldName + "' are not supported for single-product create/update. "
                            + "Use POST /api/v1/catalog/items with one item per request.");
        }
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
        String currentItemClass = itemClassForProduct(product);
        if (StringUtils.hasText(request.productName())) {
            product.setProductName(composeItemDisplayName(
                    currentItemClass,
                    request.productName().trim(),
                    product.getDefaultColour(),
                    product.getSizeLabel()));
        }
        if (StringUtils.hasText(request.itemClass())) {
            String requestedItemClass = normalizeItemClass(request.itemClass());
            if (!Objects.equals(requestedItemClass, currentItemClass)) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "itemClass is immutable for existing items; create a new item instead");
            }
        } else if (StringUtils.hasText(request.category())) {
            String requestedCategory = normalizeCategory(request.category());
            if (!requestedCategory.equalsIgnoreCase(product.getCategory())) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "category is derived from itemClass and cannot be changed directly");
            }
        }
        if (request.defaultColour() != null) {
            validateSingleVariantField("defaultColour", request.defaultColour());
            assertImmutableIdentityField("color/spec", cleanValue(product.getDefaultColour()), cleanValue(request.defaultColour()));
        }
        if (request.sizeLabel() != null) {
            validateSingleVariantField("sizeLabel", request.sizeLabel());
            assertImmutableIdentityField("size", cleanValue(product.getSizeLabel()), cleanValue(request.sizeLabel()));
        }
        if (request.unitOfMeasure() != null) {
            assertImmutableIdentityField("unitOfMeasure", cleanValue(product.getUnitOfMeasure()), cleanValue(request.unitOfMeasure()));
        }
        if (request.hsnCode() != null) {
            product.setHsnCode(cleanValue(request.hsnCode()));
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
        if (request.active() != null) {
            product.setActive(request.active());
        }
        ProductionProduct saved = productRepository.save(product);
        ensureCatalogFinishedGood(company, saved);
        syncRawMaterial(company, saved, currentItemClass);
        return toProductDto(saved);
    }

    private ProcessOutcome upsertProduct(Company company, ImportRow importRow, ImportContext context) {
        CatalogRow row = importRow.row();
        BrandResolution resolution = resolveBrandForImport(company, row.brand(), context);
        ProductionBrand brand = resolution.brand();
        String category = normalizeCategory(row.category());
        if (!StringUtils.hasText(row.productName())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Product name is required");
        }
        String productName = row.productName().trim();
        String sizeLabel = StringUtils.hasText(row.sizeLabel()) ? row.sizeLabel() : row.unitOfMeasure();
        String sanitizedSku = importRow.sanitizedSku();
        ProductionProduct existing = null;
        if (StringUtils.hasText(sanitizedSku)) {
            existing = context.productsBySku().get(normalizeSkuKey(sanitizedSku));
        } else if (importRow.productKey() != null) {
            existing = context.productsByBrandName().get(new ProductKey(brand.getId(), importRow.productKey()));
        }
        if (existing != null) {
            ProductionProduct refreshed = refreshCachedProductFromCurrentTransaction(company, existing);
            if (refreshed == null) {
                evictRowCache(company, importRow, context);
                existing = null;
            } else {
                existing = refreshed;
                cacheProduct(context, existing);
            }
        }
        if (existing == null) {
            existing = findExistingProduct(company, brand, productName, sanitizedSku);
            if (existing != null) {
                cacheProduct(context, existing);
            }
        }
        boolean created = existing == null;
        ProductionProduct product = created ? new ProductionProduct() : existing;
        if (created) {
            String sku = StringUtils.hasText(sanitizedSku)
                    ? sanitizedSku
                    : determineSku(company, brand, category, row.defaultColour(), sizeLabel, null);
            assertNotReservedSemiFinishedSku(sku);
            product.setCompany(company);
            product.setBrand(brand);
            product.setSkuCode(sku);
            product.setActive(true);
        } else if (StringUtils.hasText(sanitizedSku) && !sanitizedSku.equalsIgnoreCase(product.getSkuCode())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "SKU cannot be changed for an existing product. Existing SKU: "
                            + product.getSkuCode() + ", provided: " + sanitizedSku);
        } else if (product.getBrand() != null && !product.getBrand().getId().equals(brand.getId())) {
            // Avoid accidental brand switching when reusing SKU
            brand = product.getBrand();
        }
        applyRowToProduct(
                product,
                company,
                brand,
                row,
                productName,
                category,
                sizeLabel,
                created,
                context.validatedRawMaterialInventoryAccounts());
        ProductionProduct saved = productRepository.save(product);
        ensureCatalogFinishedGood(company, saved);
        cacheProduct(context, saved);
        boolean seeded = syncRawMaterial(
                company,
                saved,
                context.validatedRawMaterialInventoryAccounts(),
                hasExplicitRawMaterialInventoryAccount(row));
        return new ProcessOutcome(resolution.created(), created, seeded);
    }

    private BrandResolution resolveBrandForImport(Company company, String brandName, ImportContext context) {
        if (!StringUtils.hasText(brandName)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Brand is required");
        }
        String normalizedName = normalizeKey(brandName);
        if (normalizedName != null) {
            ProductionBrand cached = context.brandsByName().get(normalizedName);
            if (cached != null) {
                return new BrandResolution(cached, false);
            }
        }
        String effectiveName = brandName.trim();
        ProductionBrand existing = brandRepository.findByCompanyAndNameIgnoreCase(company, effectiveName).orElse(null);
        if (existing != null) {
            cacheBrand(context, existing);
            return new BrandResolution(existing, false);
        }
        String uniqueCode = nextBrandCode(company, effectiveName);
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setName(effectiveName);
        brand.setCode(uniqueCode);
        boolean created = true;
        brand = brandRepository.save(brand);
        cacheBrand(context, brand);
        return new BrandResolution(brand, created);
    }

    private ImportContext buildImportContext(Company company, List<ImportRow> rows) {
        Set<String> brandKeys = new HashSet<>();
        Set<String> skuKeys = new HashSet<>();
        Set<String> productKeys = new HashSet<>();
        for (ImportRow row : rows) {
            if (row.brandKey() != null) {
                brandKeys.add(row.brandKey());
            }
            if (StringUtils.hasText(row.sanitizedSku())) {
                skuKeys.add(normalizeSkuKey(row.sanitizedSku()));
            } else if (row.productKey() != null) {
                productKeys.add(row.productKey());
            }
        }

        Map<String, ProductionBrand> brandsByName = new HashMap<>();
        if (!brandKeys.isEmpty()) {
            List<ProductionBrand> brands = brandRepository.findByCompanyAndNameInIgnoreCase(company, brandKeys);
            for (ProductionBrand brand : brands) {
                String key = normalizeKey(brand.getName());
                if (key != null) {
                    brandsByName.put(key, brand);
                }
            }
        }

        Map<String, ProductionProduct> productsBySku = new HashMap<>();
        if (!skuKeys.isEmpty()) {
            List<ProductionProduct> products = productRepository.findByCompanyAndSkuCodeIn(company, skuKeys);
            for (ProductionProduct product : products) {
                String key = normalizeSkuKey(product.getSkuCode());
                if (key != null) {
                    productsBySku.put(key, product);
                }
            }
        }

        Map<ProductKey, ProductionProduct> productsByBrandName = new HashMap<>();
        if (!productKeys.isEmpty() && !brandsByName.isEmpty()) {
            Collection<ProductionBrand> brands = brandsByName.values();
            List<ProductionProduct> products = productRepository.findByBrandInAndProductNameInIgnoreCase(brands, productKeys);
            for (ProductionProduct product : products) {
                String key = normalizeKey(product.getProductName());
                if (key != null && product.getBrand() != null) {
                    productsByBrandName.put(new ProductKey(product.getBrand().getId(), key), product);
                }
            }
        }

        return new ImportContext(brandsByName, productsBySku, productsByBrandName, new HashMap<>());
    }

    private void cacheBrand(ImportContext context, ProductionBrand brand) {
        if (brand == null) {
            return;
        }
        String key = normalizeKey(brand.getName());
        if (key != null) {
            context.brandsByName().put(key, brand);
        }
    }

    private void cacheProduct(ImportContext context, ProductionProduct product) {
        if (product == null) {
            return;
        }
        String skuKey = normalizeSkuKey(product.getSkuCode());
        if (skuKey != null) {
            context.productsBySku().put(skuKey, product);
        }
        if (product.getBrand() != null) {
            String nameKey = normalizeKey(product.getProductName());
            if (nameKey != null) {
                context.productsByBrandName().put(new ProductKey(product.getBrand().getId(), nameKey), product);
            }
        }
    }

    private ProductionProduct findExistingProduct(Company company,
                                                  ProductionBrand brand,
                                                  String productName,
                                                  String sanitizedSku) {
        if (StringUtils.hasText(sanitizedSku)) {
            ProductionProduct bySku = productRepository.findByCompanyAndSkuCode(company, sanitizedSku).orElse(null);
            if (bySku != null) {
                return bySku;
            }
            ProductionProduct byName = productRepository.findByBrandAndProductNameIgnoreCase(brand, productName).orElse(null);
            if (byName != null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "SKU cannot be changed for existing product " + productName
                                + ". Existing SKU: " + byName.getSkuCode()
                                + ", provided: " + sanitizedSku);
            }
            return null;
        }
        return productRepository.findByBrandAndProductNameIgnoreCase(brand, productName).orElse(null);
    }

    private ProductionProduct refreshCachedProductFromCurrentTransaction(Company company, ProductionProduct cachedProduct) {
        if (cachedProduct == null || cachedProduct.getId() == null) {
            return cachedProduct;
        }
        return productRepository.findByCompanyAndId(company, cachedProduct.getId()).orElse(null);
    }

    private void applyRowToProduct(ProductionProduct product,
                                   Company company,
                                   ProductionBrand brand,
                                   CatalogRow row,
                                   String productName,
                                   String category,
                                   String sizeLabel,
                                   boolean created,
                                   Map<Long, Long> validatedFinishedGoodAccounts) {
        if (created) {
            product.setCompany(company);
            product.setBrand(brand);
        } else if (product.getBrand() == null) {
            product.setBrand(brand);
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
            metadata = ensureFinishedGoodAccounts(
                    company,
                    product.getSkuCode(),
                    metadata,
                    validatedFinishedGoodAccounts);
        }
        product.setMetadata(metadata);
    }

    private BrandResolution resolveBrand(Company company, Long brandId, String brandName, String providedCode) {
        if (brandId != null) {
            ProductionBrand brand = companyEntityLookup.requireProductionBrand(company, brandId);
            if (StringUtils.hasText(brandName) && !brandName.equalsIgnoreCase(brand.getName())) {
                brand.setName(brandName.trim());
            }
            return new BrandResolution(brand, false);
        }
        if (!StringUtils.hasText(brandName)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Brand is required");
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
        return syncRawMaterial(company, product, null, null, rawMaterialInventoryAccountIdFromMetadata(product) != null);
    }

    private boolean syncRawMaterial(Company company,
                                    ProductionProduct product,
                                    String itemClassHint) {
        return syncRawMaterial(
                company,
                product,
                itemClassHint,
                null,
                rawMaterialInventoryAccountIdFromMetadata(product) != null);
    }

    private boolean syncRawMaterial(Company company,
                                    ProductionProduct product,
                                    Map<Long, Long> validatedRawMaterialInventoryAccounts) {
        return syncRawMaterial(
                company,
                product,
                null,
                validatedRawMaterialInventoryAccounts,
                rawMaterialInventoryAccountIdFromMetadata(product) != null);
    }

    private boolean syncRawMaterial(Company company,
                                    ProductionProduct product,
                                    Map<Long, Long> validatedRawMaterialInventoryAccounts,
                                    boolean hasExplicitInventoryAccountMapping) {
        return syncRawMaterial(
                company,
                product,
                null,
                validatedRawMaterialInventoryAccounts,
                hasExplicitInventoryAccountMapping);
    }

    private boolean syncRawMaterial(Company company,
                                    ProductionProduct product,
                                    String itemClassHint,
                                    Map<Long, Long> validatedRawMaterialInventoryAccounts,
                                    boolean hasExplicitInventoryAccountMapping) {
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
        Long resolvedInventoryAccountId = resolveRawMaterialInventoryAccountId(company, product);
        boolean shouldApplyInventoryAccount = resolvedInventoryAccountId != null
                && (hasExplicitInventoryAccountMapping || material.getInventoryAccountId() == null);
        if (shouldApplyInventoryAccount) {
            Long validatedInventoryAccountId = requireRawMaterialInventoryAccount(
                    company,
                    resolvedInventoryAccountId,
                    sku,
                    validatedRawMaterialInventoryAccounts);
            if (!Objects.equals(material.getInventoryAccountId(), validatedInventoryAccountId)) {
                material.setInventoryAccountId(validatedInventoryAccountId);
            }
        }
        if (product.getGstRate() != null) {
            material.setGstRate(percent(product.getGstRate()));
        }
        com.bigbrightpaints.erp.modules.inventory.domain.MaterialType materialType = resolveRawMaterialMaterialType(
                product,
                material,
                itemClassHint);
        if (materialType != null && material.getMaterialType() != materialType) {
            material.setMaterialType(materialType);
        }
        String canonicalCostingMethod = CostingMethodUtils.canonicalizeRawMaterialMethodForSync(material.getCostingMethod());
        if (!Objects.equals(material.getCostingMethod(), canonicalCostingMethod)) {
            material.setCostingMethod(canonicalCostingMethod);
        }
        rawMaterialRepository.save(material);
        return isNew;
    }

    private boolean hasExplicitRawMaterialInventoryAccount(CatalogRow row) {
        return row != null && rawMaterialInventoryAccountIdFromMetadata(row.metadata()) != null;
    }

    private Long rawMaterialInventoryAccountIdFromMetadata(ProductionProduct product) {
        return rawMaterialInventoryAccountIdFromMetadata(product != null ? product.getMetadata() : null);
    }

    private Long rawMaterialInventoryAccountIdFromMetadata(Map<String, Object> metadata) {
        Long metadataAccountId = metadataLong(metadata, "inventoryAccountId");
        if (metadataAccountId == null) {
            metadataAccountId = metadataLong(metadata, "rawMaterialInventoryAccountId");
        }
        return metadataAccountId;
    }

    private Long resolveRawMaterialInventoryAccountId(Company company, ProductionProduct product) {
        Long metadataAccountId = rawMaterialInventoryAccountIdFromMetadata(product);
        if (metadataAccountId != null && metadataAccountId > 0) {
            return metadataAccountId;
        }
        Long defaultInventoryAccountId = company != null ? company.getDefaultInventoryAccountId() : null;
        return defaultInventoryAccountId != null && defaultInventoryAccountId > 0 ? defaultInventoryAccountId : null;
    }

    private Long requireRawMaterialInventoryAccount(Company company, Long accountId, String sku) {
        return requireRawMaterialInventoryAccount(company, accountId, sku, null);
    }

    private Long requireRawMaterialInventoryAccount(Company company,
                                                    Long accountId,
                                                    String sku,
                                                    Map<Long, Long> validatedRawMaterialInventoryAccounts) {
        if (accountId == null || accountId <= 0) {
            return null;
        }
        if (validatedRawMaterialInventoryAccounts != null) {
            Long cachedAccountId = validatedRawMaterialInventoryAccounts.get(accountId);
            if (cachedAccountId != null) {
                return cachedAccountId;
            }
        }
        try {
            Long validatedAccountId = companyEntityLookup.requireAccount(company, accountId).getId();
            if (validatedRawMaterialInventoryAccounts != null) {
                validatedRawMaterialInventoryAccounts.put(accountId, validatedAccountId);
            }
            return validatedAccountId;
        } catch (IllegalArgumentException ex) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Raw material SKU " + sku + " references an invalid inventory account id " + accountId);
        }
    }

    private Long requireFinishedGoodAccount(Company company,
                                            Long accountId,
                                            String sku,
                                            String key,
                                            Map<Long, Long> validatedFinishedGoodAccounts) {
        if (accountId == null || accountId <= 0) {
            return null;
        }
        if (validatedFinishedGoodAccounts != null) {
            Long cachedAccountId = validatedFinishedGoodAccounts.get(accountId);
            if (cachedAccountId != null) {
                return cachedAccountId;
            }
        }
        try {
            Long validatedAccountId = companyEntityLookup.requireAccount(company, accountId).getId();
            if (validatedFinishedGoodAccounts != null) {
                validatedFinishedGoodAccounts.put(accountId, validatedAccountId);
            }
            return validatedAccountId;
        } catch (IllegalArgumentException ex) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Finished good SKU " + sku + " references an invalid account id " + accountId + " for " + key);
        }
    }

    private void ensureCatalogFinishedGood(Company company, ProductionProduct product) {
        if (product == null || isRawMaterialCategory(product.getCategory())) {
            return;
        }
        String sku = product.getSkuCode();
        if (!StringUtils.hasText(sku)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Production product SKU is required to map finished goods");
        }
        Long valuationAccountId = requiredMetadataLong(product, "fgValuationAccountId");
        Long cogsAccountId = requiredMetadataLong(product, "fgCogsAccountId");
        Long revenueAccountId = requiredMetadataLong(product, "fgRevenueAccountId");
        Long taxAccountId = requiredMetadataLong(product, "fgTaxAccountId");
        Long discountAccountId = metadataLong(product, "fgDiscountAccountId");

        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseGet(() -> {
                    FinishedGood created = new FinishedGood();
                    created.setCompany(company);
                    created.setProductCode(sku);
                    created.setCurrentStock(BigDecimal.ZERO);
                    created.setReservedStock(BigDecimal.ZERO);
                    return created;
                });

        boolean dirty = false;
        String productName = product.getProductName();
        if (StringUtils.hasText(productName) && !Objects.equals(finishedGood.getName(), productName)) {
            finishedGood.setName(productName);
            dirty = true;
        }
        String unit = resolveUnit(product.getUnitOfMeasure());
        if (!Objects.equals(finishedGood.getUnit(), unit)) {
            finishedGood.setUnit(unit);
            dirty = true;
        }
        if (!Objects.equals(finishedGood.getValuationAccountId(), valuationAccountId)) {
            finishedGood.setValuationAccountId(valuationAccountId);
            dirty = true;
        }
        if (!Objects.equals(finishedGood.getCogsAccountId(), cogsAccountId)) {
            finishedGood.setCogsAccountId(cogsAccountId);
            dirty = true;
        }
        if (!Objects.equals(finishedGood.getRevenueAccountId(), revenueAccountId)) {
            finishedGood.setRevenueAccountId(revenueAccountId);
            dirty = true;
        }
        if (!Objects.equals(finishedGood.getTaxAccountId(), taxAccountId)) {
            finishedGood.setTaxAccountId(taxAccountId);
            dirty = true;
        }
        if (!Objects.equals(finishedGood.getDiscountAccountId(), discountAccountId)) {
            finishedGood.setDiscountAccountId(discountAccountId);
            dirty = true;
        }
        String canonicalCostingMethod = CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(finishedGood.getCostingMethod());
        if (!Objects.equals(finishedGood.getCostingMethod(), canonicalCostingMethod)) {
            finishedGood.setCostingMethod(canonicalCostingMethod);
            dirty = true;
        }
        if (dirty) {
            try {
                finishedGoodRepository.save(finishedGood);
            } catch (DataIntegrityViolationException ex) {
                // Concurrent create on the same (company, product_code) should converge to existing row.
                FinishedGood existing = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                        .orElseThrow(() -> ex);
                boolean needsSync = false;
                if (StringUtils.hasText(productName) && !Objects.equals(existing.getName(), productName)) {
                    existing.setName(productName);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getUnit(), unit)) {
                    existing.setUnit(unit);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getValuationAccountId(), valuationAccountId)) {
                    existing.setValuationAccountId(valuationAccountId);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getCogsAccountId(), cogsAccountId)) {
                    existing.setCogsAccountId(cogsAccountId);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getRevenueAccountId(), revenueAccountId)) {
                    existing.setRevenueAccountId(revenueAccountId);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getTaxAccountId(), taxAccountId)) {
                    existing.setTaxAccountId(taxAccountId);
                    needsSync = true;
                }
                if (!Objects.equals(existing.getDiscountAccountId(), discountAccountId)) {
                    existing.setDiscountAccountId(discountAccountId);
                    needsSync = true;
                }
                String existingCanonicalCostingMethod = CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(existing.getCostingMethod());
                if (!Objects.equals(existing.getCostingMethod(), existingCanonicalCostingMethod)) {
                    existing.setCostingMethod(existingCanonicalCostingMethod);
                    needsSync = true;
                }
                if (needsSync) {
                    finishedGoodRepository.save(existing);
                }
            }
        }
    }

    private String resolveUnit(String unit) {
        if (StringUtils.hasText(unit)) {
            return unit.trim();
        }
        return "UNIT";
    }

    private com.bigbrightpaints.erp.modules.inventory.domain.MaterialType resolveRawMaterialMaterialType(
            ProductionProduct product,
            RawMaterial material,
            String itemClassHint) {
        if (StringUtils.hasText(itemClassHint)) {
            return ITEM_CLASS_PACKAGING_RAW_MATERIAL.equals(normalizeItemClass(itemClassHint))
                    ? com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PACKAGING
                    : com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PRODUCTION;
        }
        if (material != null && material.getMaterialType() != null) {
            return material.getMaterialType();
        }
        return com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PRODUCTION;
    }

    private Long requiredMetadataLong(ProductionProduct product, String key) {
        Long value = metadataLong(product, key);
        if (value == null || value <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "Product " + product.getSkuCode() + " is missing required metadata: " + key);
        }
        return value;
    }

    private Long metadataLong(ProductionProduct product, String key) {
        if (product == null) {
            return null;
        }
        return metadataLong(product.getMetadata(), key);
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

    private void assertNotReservedSemiFinishedSku(String sku) {
        if (!StringUtils.hasText(sku)) {
            return;
        }
        if (sku.trim().toUpperCase(Locale.ROOT).endsWith(SEMI_FINISHED_SUFFIX)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "SKU suffix '" + SEMI_FINISHED_SUFFIX
                            + "' is reserved for internally generated semi-finished inventory");
        }
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
                product.getHsnCode(),
                product.getSkuCode(),
                product.getVariantGroupId(),
                product.getProductFamilyName(),
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
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("SKU cannot be empty");
        }
        String upper = sku.trim().toUpperCase();
        upper = NON_SKU_CHAR.matcher(upper).replaceAll("");
        upper = upper.replaceAll("-{2,}", "-");
        if (upper.isBlank()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("SKU cannot be empty");
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

    private String resolveEntryItemClass(CatalogProductEntryRequest request) {
        if (request == null) {
            return normalizeItemClass(null);
        }
        return normalizeItemClass(request.getItemClass());
    }

    private String normalizeItemClass(String itemClass) {
        String normalized = normalizeCategory(itemClass);
        return switch (normalized) {
            case ITEM_CLASS_FINISHED_GOOD, ITEM_CLASS_RAW_MATERIAL, ITEM_CLASS_PACKAGING_RAW_MATERIAL -> normalized;
            case "PACKAGING" -> ITEM_CLASS_PACKAGING_RAW_MATERIAL;
            default -> throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "itemClass is required (FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL)");
        };
    }

    private String normalizeVariantItemClass(String category) {
        return switch (normalizeCategory(category)) {
            case ITEM_CLASS_RAW_MATERIAL -> ITEM_CLASS_RAW_MATERIAL;
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL, "PACKAGING" -> ITEM_CLASS_PACKAGING_RAW_MATERIAL;
            default -> ITEM_CLASS_FINISHED_GOOD;
        };
    }

    private String itemClassSkuPrefix(String itemClass) {
        return switch (normalizeItemClass(itemClass)) {
            case ITEM_CLASS_RAW_MATERIAL -> "RM";
            case ITEM_CLASS_PACKAGING_RAW_MATERIAL -> "PKG";
            default -> "FG";
        };
    }

    private String categoryForItemClass(String itemClass) {
        return switch (normalizeItemClass(itemClass)) {
            case ITEM_CLASS_RAW_MATERIAL, ITEM_CLASS_PACKAGING_RAW_MATERIAL -> ITEM_CLASS_RAW_MATERIAL;
            default -> ITEM_CLASS_FINISHED_GOOD;
        };
    }

    private Long rawMaterialIdForSku(Company company, String sku) {
        if (company == null || !StringUtils.hasText(sku)) {
            return null;
        }
        return rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, sku)
                .map(RawMaterial::getId)
                .orElse(null);
    }

    private String itemClassForProduct(ProductionProduct product) {
        if (product == null) {
            return ITEM_CLASS_FINISHED_GOOD;
        }
        if (!isRawMaterialCategory(product.getCategory())) {
            return ITEM_CLASS_FINISHED_GOOD;
        }
        RawMaterial material = StringUtils.hasText(product.getSkuCode())
                ? rawMaterialRepository.findByCompanyAndSkuIgnoreCase(product.getCompany(), product.getSkuCode()).orElse(null)
                : null;
        if (material != null && material.getMaterialType() == com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PACKAGING) {
            return ITEM_CLASS_PACKAGING_RAW_MATERIAL;
        }
        return ITEM_CLASS_RAW_MATERIAL;
    }

    private static String normalizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSkuKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(metadata);
    }

    private Map<String, Object> validateCanonicalEntryMetadata(Company company,
                                                               String category,
                                                               String sku,
                                                               Map<String, Object> metadata,
                                                               boolean requireFinishedGoodDefaults) {
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
        return ensureFinishedGoodAccounts(company, sku, working, null, requireFinishedGoodDefaults);
    }

    private Map<String, Object> ensureFinishedGoodAccounts(Company company, String sku, Map<String, Object> metadata) {
        return ensureFinishedGoodAccounts(company, sku, metadata, null, true);
    }

    private Map<String, Object> ensureFinishedGoodAccounts(Company company,
                                                           String sku,
                                                           Map<String, Object> metadata,
                                                           Map<Long, Long> validatedFinishedGoodAccounts) {
        return ensureFinishedGoodAccounts(company, sku, metadata, validatedFinishedGoodAccounts, true);
    }

    private Map<String, Object> ensureFinishedGoodAccounts(Company company,
                                                           String sku,
                                                           Map<String, Object> metadata,
                                                           Map<Long, Long> validatedFinishedGoodAccounts,
                                                           boolean requireConfiguredDefaults) {
        Map<String, Object> working = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        var defaults = requireConfiguredDefaults
                ? companyDefaultAccountsService.requireDefaults()
                : Optional.ofNullable(companyDefaultAccountsService.getDefaults())
                .orElse(new CompanyDefaultAccountsService.DefaultAccounts(null, null, null, null, null));

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
        if (requireConfiguredDefaults) {
            for (String key : List.of("fgValuationAccountId", "fgCogsAccountId", "fgRevenueAccountId", "fgTaxAccountId")) {
                if (!hasLongValue(working.get(key))) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Default " + key + " is not configured for company " + company.getCode() +
                            ". Configure company default accounts to enable product posting.");
                }
            }
        }
        for (String key : FINISHED_GOOD_ACCOUNT_KEYS) {
            Long accountId = metadataLong(working, key);
            if (accountId == null) {
                continue;
            }
            Long validatedAccountId = requireFinishedGoodAccount(
                    company,
                    accountId,
                    sku,
                    key,
                    validatedFinishedGoodAccounts);
            if (!Objects.equals(accountId, validatedAccountId)) {
                working.put(key, validatedAccountId);
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

    private String resolveFileHash(MultipartFile file) {
        try {
            return IdempotencyUtils.sha256Hex(file.getBytes());
        } catch (Exception ex) {
            return Integer.toHexString(file.getOriginalFilename() != null ? file.getOriginalFilename().hashCode() : 0);
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey, String fileHash) {
        String resolved = StringUtils.hasText(idempotencyKey)
                ? IdempotencyUtils.normalizeKey(idempotencyKey)
                : fileHash;
        return idempotencyReservationService.requireKey(resolved, "catalog imports");
    }

    private void assertIdempotencyMatch(CatalogImport record, String expectedHash, String idempotencyKey) {
        idempotencyReservationService.assertAndRepairSignature(
                record,
                idempotencyKey,
                expectedHash,
                persisted -> StringUtils.hasText(persisted.getIdempotencyHash())
                        ? persisted.getIdempotencyHash()
                        : persisted.getFileHash(),
                CatalogImport::setIdempotencyHash,
                catalogImportRepository::save,
                () -> idempotencyReservationService.payloadMismatch(idempotencyKey));
    }

    private CatalogImportResponse toResponse(CatalogImport record) {
        List<CatalogImportResponse.ImportError> errors = deserializeErrors(record.getErrorsJson());
        return new CatalogImportResponse(
                record.getRowsProcessed(),
                record.getBrandsCreated(),
                record.getProductsCreated(),
                record.getProductsUpdated(),
                record.getRawMaterialsSeeded(),
                errors
        );
    }

    private String serializeErrors(List<CatalogImportResponse.ImportError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(errors);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<CatalogImportResponse.ImportError> deserializeErrors(String errorsJson) {
        if (!StringUtils.hasText(errorsJson)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(errorsJson, new TypeReference<List<CatalogImportResponse.ImportError>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        return idempotencyReservationService.isDataIntegrityViolation(error);
    }

    private boolean isVariantDuplicateConflict(Throwable error, Company company, String sku) {
        if (!StringUtils.hasText(sku)) {
            return false;
        }
        if (!(isDataIntegrityViolation(error) || isDuplicateSkuValidation(error))) {
            return false;
        }
        return productRepository.findByCompanyAndSkuCode(company, sku).isPresent();
    }

    private boolean isDuplicateSkuValidation(Throwable error) {
        if (!(error instanceof IllegalArgumentException ex) || !StringUtils.hasText(ex.getMessage())) {
            return false;
        }
        String message = ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("sku") && message.contains("already exists");
    }

    private boolean isRetryableImportFailure(Throwable error) {
        if (isDataIntegrityViolation(error)) {
            return true;
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof UnexpectedRollbackException
                    || cursor instanceof OptimisticLockingFailureException
                    || cursor instanceof OptimisticLockException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private boolean isSupportedCatalogContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)) {
            String normalized = contentType.trim().toLowerCase(Locale.ROOT);
            int parametersIndex = normalized.indexOf(';');
            String parameterSection = null;
            if (parametersIndex >= 0) {
                parameterSection = normalized.substring(parametersIndex + 1);
                normalized = normalized.substring(0, parametersIndex).trim();
            }
            if (!CATALOG_IMPORT_ALLOWED_CONTENT_TYPES.contains(normalized)) {
                return false;
            }
            return isValidMimeParameterSection(parameterSection);
        }
        String fileName = file.getOriginalFilename();
        return StringUtils.hasText(fileName) && fileName.trim().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private boolean isValidMimeParameterSection(String parameterSection) {
        if (parameterSection == null) {
            return true;
        }
        String[] parameters = parameterSection.split(";", -1);
        for (String parameter : parameters) {
            String token = parameter.trim();
            if (!StringUtils.hasText(token)) {
                return false;
            }
            int equalsIndex = token.indexOf('=');
            if (equalsIndex <= 0 || equalsIndex == token.length() - 1) {
                return false;
            }
            String key = token.substring(0, equalsIndex).trim();
            String value = token.substring(equalsIndex + 1).trim();
            if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                return false;
            }
        }
        return true;
    }

    private record VariantExecutionPlan(VariantBrandPlan brandPlan,
                                        String baseProductName,
                                        String category,
                                        List<VariantCandidate> candidatesToCreate,
                                        List<BulkVariantResponse.VariantItem> generated,
                                        List<BulkVariantResponse.VariantItem> conflicts,
                                        List<BulkVariantResponse.VariantItem> wouldCreate) {
    }

    private record CatalogProductEntryPlan(ProductionBrand brand,
                                           UUID variantGroupId,
                                           String productFamilyName,
                                           String category,
                                           String itemClass,
                                           String unitOfMeasure,
                                           String hsnCode,
                                           BigDecimal basePrice,
                                           BigDecimal gstRate,
                                           BigDecimal minDiscountPercent,
                                           BigDecimal minSellingPrice,
                                           Map<String, Object> metadata,
                                           List<CatalogProductCandidate> candidatesToCreate,
                                           List<CatalogProductEntryResponse.Member> generatedMembers,
                                           List<CatalogProductEntryResponse.Conflict> conflicts,
                                           CatalogProductEntryResponse.DownstreamEffects downstreamEffects) {
    }

    private record VariantBrandPlan(Long brandId,
                                    String brandName,
                                    String brandCode) {
    }

    private record VariantCandidate(String sku,
                                    String color,
                                    String size,
                                    String productName,
                                    ProductCreateRequest createRequest) {
        private BulkVariantResponse.VariantItem toItem(String reason) {
            return new BulkVariantResponse.VariantItem(sku, reason, productName, color, size);
        }
    }

    private record CatalogProductCandidate(String sku,
                                           String color,
                                           String size,
                                           String productName,
                                           ProductCreateRequest createRequest) {
        private CatalogProductEntryResponse.Member toMember(Long id,
                                                            UUID publicId,
                                                            Long rawMaterialId,
                                                            com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto readiness) {
            return new CatalogProductEntryResponse.Member(id, publicId, rawMaterialId, sku, productName, createRequest.itemClass(), color, size, readiness);
        }

        private CatalogProductEntryResponse.Conflict toConflict(String reason) {
            return new CatalogProductEntryResponse.Conflict(sku, reason, productName, createRequest.itemClass(), color, size);
        }
    }

    private record ColorSizeSpec(String color, List<String> sizes) {
        private ColorSizeSpec {
            color = color == null ? "" : color;
            sizes = sizes == null ? List.of() : List.copyOf(sizes);
        }
    }

    private record BrandResolution(ProductionBrand brand, boolean created) {}

    private record ProcessOutcome(boolean brandCreated, boolean productCreated, boolean rawMaterialSeeded) {}

    private record ProductKey(Long brandId, String productNameKey) {
        private ProductKey {
            Objects.requireNonNull(brandId, "brandId");
            Objects.requireNonNull(productNameKey, "productNameKey");
        }
    }

    private record ImportContext(Map<String, ProductionBrand> brandsByName,
                                 Map<String, ProductionProduct> productsBySku,
                                 Map<ProductKey, ProductionProduct> productsByBrandName,
                                 Map<Long, Long> validatedRawMaterialInventoryAccounts) {
    }

    private record ImportRow(long recordNumber,
                             CatalogRow row,
                             String sanitizedSku,
                             String brandKey,
                             String productKey) {
        static ImportRow from(CSVRecord record, CatalogRow row) {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(row, "row");
            String sanitizedSku = null;
            if (StringUtils.hasText(row.skuCode())) {
                sanitizedSku = sanitizeSku(row.skuCode());
            }
            String brandKey = normalizeKey(row.brand());
            String productKey = normalizeKey(row.productName());
            return new ImportRow(record.getRecordNumber(), row, sanitizedSku, brandKey, productKey);
        }
    }

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
                    if (!StringUtils.hasText(value)) {
                        return null;
                    }
                    String trimmed = value.trim();
                    if (trimmed.length() > MAX_CATALOG_FIELD_LENGTH) {
                        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Column '" + column + "' exceeds max length of "
                                + MAX_CATALOG_FIELD_LENGTH + " characters");
                    }
                    return trimmed;
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
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid numeric value in column '" + column + "': " + value);
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
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid numeric value in column '" + column + "': " + value);
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
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid JSON in column '" + column + "': " + ex.getOriginalMessage());
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
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid gst_slabs entry '" + token + "'. Expected format STATE:RATE");
                }
                String state = pair[0].trim();
                String value = pair[1].trim();
                if (!StringUtils.hasText(state) || !StringUtils.hasText(value)) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid gst_slabs entry '" + token + "'. Expected format STATE:RATE");
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
            Long rawMaterialInventoryAccountId = wholeNumber(record, "rm_inventory_account_id");
            if (rawMaterialInventoryAccountId == null) {
                rawMaterialInventoryAccountId = wholeNumber(record, "inventory_account_id");
            }
            maybePut(metadata, "inventoryAccountId", rawMaterialInventoryAccountId);
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
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Missing numeric value for " + column);
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
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid numeric value '" + stringValue + "' in column '" + column + "'");
                }
            }
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid numeric value '" + value + "' in column '" + column + "'");
        }
    }
}
