package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SkuReadinessService {

    public enum ExpectedStockType {
        FINISHED_GOOD,
        RAW_MATERIAL
    }

    private static final List<String> RAW_MATERIAL_CATEGORIES = List.of(
            "RAW_MATERIAL",
            "RAW MATERIAL",
            "RAW-MATERIAL"
    );
    private static final String ACCOUNTING_CONFIGURATION_REQUIRED = "ACCOUNTING_CONFIGURATION_REQUIRED";

    private final ProductionProductRepository productRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final RawMaterialRepository rawMaterialRepository;

    public SkuReadinessService(ProductionProductRepository productRepository,
                               FinishedGoodRepository finishedGoodRepository,
                               FinishedGoodBatchRepository finishedGoodBatchRepository,
                               RawMaterialRepository rawMaterialRepository) {
        this.productRepository = productRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.rawMaterialRepository = rawMaterialRepository;
    }

    public SkuReadinessDto forProduct(Company company, ProductionProduct product) {
        if (product == null) {
            throw new IllegalArgumentException("product is required");
        }
        return buildSnapshot(
                company,
                normalizeSkuKey(product.getSkuCode()),
                sanitizeSku(product.getSkuCode()),
                product,
                null);
    }

    public SkuReadinessDto forPlannedProduct(ProductionProduct product,
                                             ExpectedStockType expectedStockType,
                                             FinishedGood finishedGood,
                                             RawMaterial rawMaterial) {
        if (product == null) {
            throw new IllegalArgumentException("product is required");
        }
        return buildSnapshot(
                resolveCompany(product, finishedGood, rawMaterial),
                normalizeSkuKey(product.getSkuCode()),
                product,
                finishedGood,
                rawMaterial,
                expectedStockType,
                false);
    }

    public SkuReadinessDto forSku(Company company, String sku, ExpectedStockType expectedStockType) {
        String requestedSku = sanitizeSku(sku);
        ProductionProduct product = StringUtils.hasText(requestedSku)
                ? productRepository.findByCompanyAndSkuCodeIgnoreCase(company, requestedSku).orElse(null)
                : null;
        String lookupSku = product != null ? sanitizeSku(product.getSkuCode()) : requestedSku;
        String snapshotSku = product != null ? normalizeSkuKey(product.getSkuCode()) : normalizeSkuKey(requestedSku);
        return buildSnapshot(company, snapshotSku, lookupSku, product, expectedStockType);
    }

    public Map<Long, SkuReadinessDto> forProducts(Company company, Collection<ProductionProduct> products) {
        if (products == null || products.isEmpty()) {
            return Map.of();
        }
        List<ProductionProduct> candidates = products.stream()
                .filter(Objects::nonNull)
                .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }

        Map<String, ProductionProduct> productsBySku = candidates.stream()
                .filter(product -> StringUtils.hasText(normalizeSkuKey(product.getSkuCode())))
                .collect(Collectors.toMap(
                        product -> normalizeSkuKey(product.getSkuCode()),
                        product -> product,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<String> lookupSkuKeys = productsBySku.keySet().stream()
                .map(this::normalizeSkuLookupKey)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        Map<String, FinishedGood> finishedGoodsBySku = lookupSkuKeys.isEmpty()
                ? Map.of()
                : finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(company, lookupSkuKeys).stream()
                .filter(finishedGood -> StringUtils.hasText(finishedGood.getProductCode()))
                .collect(Collectors.toMap(
                        finishedGood -> normalizeSkuKey(finishedGood.getProductCode()),
                        finishedGood -> finishedGood,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, RawMaterial> rawMaterialsBySku = lookupSkuKeys.isEmpty()
                ? Map.of()
                : rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(company, lookupSkuKeys).stream()
                .filter(rawMaterial -> StringUtils.hasText(rawMaterial.getSku()))
                .collect(Collectors.toMap(
                        rawMaterial -> normalizeSkuKey(rawMaterial.getSku()),
                        rawMaterial -> rawMaterial,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<Long, Boolean> saleReadyBatchByFinishedGoodId = finishedGoodsBySku.isEmpty()
                ? Map.of()
                : finishedGoodBatchRepository.findByFinishedGoodIn(finishedGoodsBySku.values()).stream()
                .filter(batch -> batch.getFinishedGood() != null && batch.getFinishedGood().getId() != null)
                .collect(Collectors.toMap(
                        batch -> batch.getFinishedGood().getId(),
                        this::hasPositiveAvailableQuantity,
                        Boolean::logicalOr,
                        LinkedHashMap::new));

        Map<Long, SkuReadinessDto> readinessByProductId = new LinkedHashMap<>();
        for (ProductionProduct product : candidates) {
            Long productId = product.getId();
            if (productId == null) {
                continue;
            }
            String normalizedSku = normalizeSkuKey(product.getSkuCode());
            FinishedGood finishedGood = StringUtils.hasText(normalizedSku)
                    ? finishedGoodsBySku.get(normalizedSku)
                    : null;
            RawMaterial rawMaterial = StringUtils.hasText(normalizedSku)
                    ? rawMaterialsBySku.get(normalizedSku)
                    : null;
            boolean hasSaleReadyBatch = finishedGood != null
                    && Boolean.TRUE.equals(saleReadyBatchByFinishedGoodId.get(finishedGood.getId()));
            readinessByProductId.put(productId, buildSnapshot(
                    company,
                    normalizedSku,
                    product,
                    finishedGood,
                    rawMaterial,
                    null,
                    hasSaleReadyBatch));
        }
        return readinessByProductId;
    }

    public SkuReadinessDto sanitizeForCatalogViewer(SkuReadinessDto readiness, boolean includeAccountingDetail) {
        if (includeAccountingDetail || readiness == null) {
            return readiness;
        }
        return new SkuReadinessDto(
                readiness.sku(),
                sanitizeStage(readiness.catalog()),
                sanitizeStage(readiness.inventory()),
                sanitizeStage(readiness.production()),
                sanitizeStage(readiness.sales())
        );
    }

    private SkuReadinessDto buildSnapshot(Company company,
                                          String sku,
                                          String lookupSku,
                                          ProductionProduct product,
                                          ExpectedStockType expectedStockType) {
        String resolvedSku = StringUtils.hasText(sku)
                ? sku
                : Optional.ofNullable(product).map(ProductionProduct::getSkuCode).map(this::normalizeSkuKey).orElse(null);
        String resolvedLookupSku = StringUtils.hasText(lookupSku)
                ? sanitizeSku(lookupSku)
                : Optional.ofNullable(product).map(ProductionProduct::getSkuCode).map(this::sanitizeSku).orElse(null);
        FinishedGood finishedGood = StringUtils.hasText(resolvedLookupSku)
                ? finishedGoodRepository.findByCompanyAndProductCodeIgnoreCase(company, resolvedLookupSku).orElse(null)
                : null;
        RawMaterial rawMaterial = StringUtils.hasText(resolvedLookupSku)
                ? rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, resolvedLookupSku).orElse(null)
                : null;
        return buildSnapshot(
                company,
                resolvedSku,
                product,
                finishedGood,
                rawMaterial,
                expectedStockType,
                hasSaleReadyBatch(finishedGood)
        );
    }

    private SkuReadinessDto buildSnapshot(Company company,
                                          String sku,
                                          ProductionProduct product,
                                          FinishedGood finishedGood,
                                          RawMaterial rawMaterial,
                                          ExpectedStockType expectedStockType,
                                          boolean hasSaleReadyBatch) {
        ExpectedStockType effectiveStockType = resolveExpectedStockType(product, rawMaterial, expectedStockType);

        List<String> catalogBlockers = new ArrayList<>();
        if (product == null) {
            catalogBlockers.add("PRODUCT_MASTER_MISSING");
        } else if (!product.isActive()) {
            catalogBlockers.add("PRODUCT_INACTIVE");
        }

        List<String> inventoryBlockers = new ArrayList<>();
        if (effectiveStockType == ExpectedStockType.RAW_MATERIAL) {
            if (product != null && !isRawMaterialCategory(product.getCategory())) {
                inventoryBlockers.add("RAW_MATERIAL_CATEGORY_REQUIRED");
            }
            if (rawMaterial == null) {
                inventoryBlockers.add("RAW_MATERIAL_MIRROR_MISSING");
            } else if (rawMaterial.getInventoryAccountId() == null) {
                inventoryBlockers.add("RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING");
            }
        } else {
            if (product != null && isRawMaterialCategory(product.getCategory())) {
                inventoryBlockers.add("FINISHED_GOOD_CATEGORY_REQUIRED");
            }
            if (finishedGood == null) {
                inventoryBlockers.add("FINISHED_GOOD_MIRROR_MISSING");
            } else {
                if (finishedGood.getValuationAccountId() == null) {
                    inventoryBlockers.add("FINISHED_GOOD_VALUATION_ACCOUNT_MISSING");
                }
                if (finishedGood.getCogsAccountId() == null) {
                    inventoryBlockers.add("FINISHED_GOOD_COGS_ACCOUNT_MISSING");
                }
                if (finishedGood.getRevenueAccountId() == null) {
                    inventoryBlockers.add("FINISHED_GOOD_REVENUE_ACCOUNT_MISSING");
                }
                if (finishedGood.getTaxAccountId() == null) {
                    inventoryBlockers.add("FINISHED_GOOD_TAX_ACCOUNT_MISSING");
                }
            }
        }

        List<String> productionBlockers = new ArrayList<>();
        productionBlockers.addAll(catalogBlockers);
        productionBlockers.addAll(inventoryBlockers);
        if (effectiveStockType == ExpectedStockType.FINISHED_GOOD) {
            Long wipAccountId = metadataLong(product, "wipAccountId");
            if (wipAccountId == null) {
                productionBlockers.add("WIP_ACCOUNT_MISSING");
            } else {
                if (metadataLong(product, "laborAppliedAccountId") == null) {
                    productionBlockers.add("LABOR_APPLIED_ACCOUNT_MISSING");
                }
                if (metadataLong(product, "overheadAppliedAccountId") == null) {
                    productionBlockers.add("OVERHEAD_APPLIED_ACCOUNT_MISSING");
                }
            }
        }

        List<String> salesBlockers = new ArrayList<>();
        if (effectiveStockType == ExpectedStockType.RAW_MATERIAL) {
            salesBlockers.add("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE");
        } else {
            salesBlockers.addAll(catalogBlockers);
            salesBlockers.addAll(inventoryBlockers);
            if (!hasSaleReadyBatch) {
                salesBlockers.add("NO_FINISHED_GOOD_BATCH_STOCK");
            }
            if (finishedGood != null && finishedGood.getDiscountAccountId() == null
                    && (company == null || company.getDefaultDiscountAccountId() == null)) {
                salesBlockers.add("DISCOUNT_ACCOUNT_MISSING");
            }
            if (isTaxableFinishedGood(product)) {
                Long outputTaxAccountId = company != null ? company.getGstOutputTaxAccountId() : null;
                if (outputTaxAccountId == null) {
                    salesBlockers.add("GST_OUTPUT_ACCOUNT_MISSING");
                } else if (finishedGood != null
                        && finishedGood.getTaxAccountId() != null
                        && !outputTaxAccountId.equals(finishedGood.getTaxAccountId())) {
                    salesBlockers.add("FINISHED_GOOD_GST_OUTPUT_ACCOUNT_MISMATCH");
                }
            }
        }

        return new SkuReadinessDto(
                sku,
                stage(catalogBlockers),
                stage(inventoryBlockers),
                stage(productionBlockers),
                stage(salesBlockers));
    }

    private ExpectedStockType resolveExpectedStockType(ProductionProduct product,
                                                       RawMaterial rawMaterial,
                                                       ExpectedStockType expectedStockType) {
        if (expectedStockType != null) {
            return expectedStockType;
        }
        if (product != null) {
            return isRawMaterialCategory(product.getCategory())
                    ? ExpectedStockType.RAW_MATERIAL
                    : ExpectedStockType.FINISHED_GOOD;
        }
        if (rawMaterial != null) {
            return ExpectedStockType.RAW_MATERIAL;
        }
        return ExpectedStockType.FINISHED_GOOD;
    }

    private Company resolveCompany(ProductionProduct product,
                                   FinishedGood finishedGood,
                                   RawMaterial rawMaterial) {
        if (product != null && product.getCompany() != null) {
            return product.getCompany();
        }
        if (finishedGood != null && finishedGood.getCompany() != null) {
            return finishedGood.getCompany();
        }
        if (rawMaterial != null) {
            return rawMaterial.getCompany();
        }
        return null;
    }

    private boolean hasSaleReadyBatch(FinishedGood finishedGood) {
        if (finishedGood == null) {
            return false;
        }
        List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood);
        return batches.stream()
                .map(FinishedGoodBatch::getQuantityAvailable)
                .map(quantity -> quantity == null ? BigDecimal.ZERO : quantity)
                .anyMatch(quantity -> quantity.compareTo(BigDecimal.ZERO) > 0);
    }

    private boolean hasPositiveAvailableQuantity(FinishedGoodBatch batch) {
        BigDecimal quantity = batch.getQuantityAvailable();
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
    }

    private SkuReadinessDto.Stage stage(List<String> blockers) {
        List<String> effectiveBlockers = blockers == null ? List.of() : List.copyOf(blockers);
        return new SkuReadinessDto.Stage(effectiveBlockers.isEmpty(), effectiveBlockers);
    }

    private SkuReadinessDto.Stage sanitizeStage(SkuReadinessDto.Stage stage) {
        if (stage == null || stage.blockers() == null || stage.blockers().isEmpty()) {
            return stage;
        }
        List<String> visibleBlockers = new ArrayList<>();
        boolean hiddenAccountingBlocker = false;
        for (String blocker : stage.blockers()) {
            if (isAccountingBlocker(blocker)) {
                hiddenAccountingBlocker = true;
                continue;
            }
            visibleBlockers.add(blocker);
        }
        if (hiddenAccountingBlocker && !visibleBlockers.contains(ACCOUNTING_CONFIGURATION_REQUIRED)) {
            visibleBlockers.add(ACCOUNTING_CONFIGURATION_REQUIRED);
        }
        return new SkuReadinessDto.Stage(visibleBlockers.isEmpty(), List.copyOf(visibleBlockers));
    }

    private boolean isAccountingBlocker(String blocker) {
        return StringUtils.hasText(blocker)
                && (blocker.endsWith("_ACCOUNT_MISSING") || blocker.endsWith("_ACCOUNT_MISMATCH"));
    }

    private boolean isRawMaterialCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return false;
        }
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        return RAW_MATERIAL_CATEGORIES.contains(normalized);
    }

    private Long metadataLong(ProductionProduct product, String key) {
        if (product == null || product.getMetadata() == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object candidate = product.getMetadata().get(key);
        if (candidate instanceof Number number) {
            return number.longValue();
        }
        if (candidate instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Long.parseLong(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isTaxableFinishedGood(ProductionProduct product) {
        return product != null
                && product.getGstRate() != null
                && product.getGstRate().compareTo(BigDecimal.ZERO) > 0;
    }

    private String sanitizeSku(String sku) {
        return StringUtils.hasText(sku) ? sku.trim() : null;
    }

    private String normalizeSkuKey(String sku) {
        String sanitized = sanitizeSku(sku);
        return StringUtils.hasText(sanitized) ? sanitized.toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeSkuLookupKey(String sku) {
        String normalized = normalizeSkuKey(sku);
        return StringUtils.hasText(normalized) ? normalized.toLowerCase(Locale.ROOT) : null;
    }
}
