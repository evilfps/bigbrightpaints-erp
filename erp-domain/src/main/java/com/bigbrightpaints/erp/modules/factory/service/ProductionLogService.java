package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogMaterial;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogMaterialDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductionLogService {

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String MOVEMENT_TYPE_ISSUE = "ISSUE";
    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final CompanyContextService companyContextService;
    private final ProductionLogRepository logRepository;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final AccountingFacade accountingFacade;
    private final SalesOrderRepository salesOrderRepository;

    public ProductionLogService(CompanyContextService companyContextService,
                                ProductionLogRepository logRepository,
                                ProductionBrandRepository brandRepository,
                                ProductionProductRepository productRepository,
                                RawMaterialRepository rawMaterialRepository,
                                RawMaterialBatchRepository rawMaterialBatchRepository,
                                RawMaterialMovementRepository rawMaterialMovementRepository,
                                AccountingFacade accountingFacade,
                                SalesOrderRepository salesOrderRepository) {
        this.companyContextService = companyContextService;
        this.logRepository = logRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.accountingFacade = accountingFacade;
        this.salesOrderRepository = salesOrderRepository;
    }

    @Transactional
    public ProductionLogDetailDto createLog(ProductionLogRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionBrand brand = brandRepository.findByCompanyAndId(company, request.brandId())
                .orElseThrow(() -> new IllegalArgumentException("Brand not found"));
        ProductionProduct product = productRepository.findByCompanyAndId(company, request.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        if (!product.getBrand().getId().equals(brand.getId())) {
            throw new IllegalArgumentException("Product does not belong to brand");
        }
        BigDecimal batchSize = positive(request.batchSize(), "batchSize");
        BigDecimal mixedQty = positive(request.mixedQuantity(), "mixedQuantity");
        String unitOfMeasure = StringUtils.hasText(request.unitOfMeasure())
                ? request.unitOfMeasure().trim()
                : Optional.ofNullable(product.getUnitOfMeasure()).filter(StringUtils::hasText).orElse("UNIT");
        String productionCode = nextProductionCode(company);

        ProductionLog log = new ProductionLog();
        log.setCompany(company);
        log.setBrand(brand);
        log.setProduct(product);
        log.setProductionCode(productionCode);
        log.setBatchColour(clean(request.batchColour()));
        log.setBatchSize(batchSize);
        log.setUnitOfMeasure(unitOfMeasure);
        log.setMixedQuantity(mixedQty);
        log.setStatus(ProductionLogStatus.READY_TO_PACK);
        log.setTotalPackedQuantity(BigDecimal.ZERO);
        log.setWastageQuantity(mixedQty);
        log.setProducedAt(resolveProducedAt(request.producedAt()));
        log.setNotes(clean(request.notes()));
        log.setCreatedBy(clean(request.createdBy()));
        if (request.salesOrderId() != null) {
            SalesOrder order = salesOrderRepository.findByCompanyAndId(company, request.salesOrderId())
                    .orElseThrow(() -> new IllegalArgumentException("Sales order not found"));
            log.setSalesOrderId(order.getId());
            log.setSalesOrderNumber(order.getOrderNumber());
        }
        BigDecimal laborCost = nonNegative(request.laborCost());
        BigDecimal overheadCost = nonNegative(request.overheadCost());
        log.setLaborCostTotal(laborCost);
        log.setOverheadCostTotal(overheadCost);

        if (request.materials() == null || request.materials().isEmpty()) {
            throw new IllegalArgumentException("Materials are required");
        }

        MaterialIssueSummary issueSummary = issueMaterials(company, log, request.materials());
        log.setMaterialCostTotal(issueSummary.totalCost());
        BigDecimal totalCost = issueSummary.totalCost().add(laborCost).add(overheadCost);
        log.setUnitCost(calculateUnitCost(totalCost, mixedQty));

        ProductionLog saved = logRepository.save(log);

        postMaterialJournal(company, saved, product, issueSummary);

        return toDetailDto(saved);
    }

    public List<ProductionLogDto> recentLogs() {
        Company company = companyContextService.requireCurrentCompany();
        return logRepository.findTop25ByCompanyOrderByProducedAtDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public ProductionLogDetailDto getLog(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = logRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Production log not found"));
        return toDetailDto(log);
    }

    private MaterialIssueSummary issueMaterials(Company company,
                                                ProductionLog log,
                                                List<ProductionLogRequest.MaterialUsageRequest> usages) {
        BigDecimal totalCost = BigDecimal.ZERO;
        Map<Long, BigDecimal> accountTotals = new HashMap<>();
        for (ProductionLogRequest.MaterialUsageRequest usage : usages) {
            MaterialConsumption consumption = consumeMaterial(company, log, usage);
            log.getMaterials().add(consumption.material());
            totalCost = totalCost.add(consumption.totalCost());
            accountTotals.merge(consumption.inventoryAccountId(), consumption.totalCost(), BigDecimal::add);
        }
        return new MaterialIssueSummary(totalCost, Map.copyOf(accountTotals));
    }

    private MaterialConsumption consumeMaterial(Company company,
                                                ProductionLog log,
                                                ProductionLogRequest.MaterialUsageRequest usage) {
        BigDecimal qty = positive(usage.quantity(), "materials.quantity");
        RawMaterial rawMaterial = rawMaterialRepository.lockByCompanyAndId(company, usage.rawMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
        if (rawMaterial.getCurrentStock().compareTo(qty) < 0) {
            throw new IllegalArgumentException("Insufficient stock for " + rawMaterial.getName());
        }
        if (rawMaterial.getInventoryAccountId() == null) {
            throw new IllegalStateException("Raw material " + rawMaterial.getName() + " missing inventory account");
        }

        BigDecimal totalCost = issueFromBatches(rawMaterial, qty, log.getProductionCode());
        rawMaterial.setCurrentStock(rawMaterial.getCurrentStock().subtract(qty));
        rawMaterialRepository.save(rawMaterial);

        ProductionLogMaterial material = new ProductionLogMaterial();
        material.setLog(log);
        material.setRawMaterial(rawMaterial);
        material.setMaterialName(rawMaterial.getName());
        material.setQuantity(qty);
        material.setUnitOfMeasure(StringUtils.hasText(usage.unitOfMeasure())
                ? usage.unitOfMeasure().trim()
                : rawMaterial.getUnitType());
        material.setCostPerUnit(calculateUnitCost(totalCost, qty));
        material.setTotalCost(totalCost);
        return new MaterialConsumption(material, totalCost, rawMaterial.getInventoryAccountId());
    }

    private BigDecimal issueFromBatches(RawMaterial rawMaterial, BigDecimal requiredQty, String referenceId) {
        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(rawMaterial).stream()
                .sorted(Comparator.comparing(RawMaterialBatch::getReceivedAt))
                .toList();
        BigDecimal remaining = requiredQty;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (RawMaterialBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = Optional.ofNullable(batch.getQuantity()).orElse(BigDecimal.ZERO);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = available.min(remaining);
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            batch.setQuantity(available.subtract(take));
            rawMaterialBatchRepository.save(batch);

            RawMaterialMovement movement = new RawMaterialMovement();
            movement.setRawMaterial(rawMaterial);
            movement.setRawMaterialBatch(batch);
            movement.setReferenceType(InventoryReference.PRODUCTION_LOG);
            movement.setReferenceId(referenceId);
            movement.setMovementType(MOVEMENT_TYPE_ISSUE);
            movement.setQuantity(take);
            BigDecimal unitCost = Optional.ofNullable(batch.getCostPerUnit()).orElse(BigDecimal.ZERO);
            movement.setUnitCost(unitCost);
            rawMaterialMovementRepository.save(movement);

            totalCost = totalCost.add(unitCost.multiply(take));
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Insufficient batch availability for " + rawMaterial.getName());
        }
        return totalCost;
    }

    private void postMaterialJournal(Company company,
                                     ProductionLog log,
                                     ProductionProduct product,
                                     MaterialIssueSummary summary) {
        if (summary.totalCost().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long wipAccountId = requireWipAccountId(product);

        // Delegate to AccountingFacade for material consumption journal
        JournalEntryDto entry = accountingFacade.postMaterialConsumption(
                log.getProductionCode(),
                resolveJournalDate(company, log),
                wipAccountId,
                summary.accountTotals(),
                summary.totalCost()
        );

        if (entry != null) {
            linkRawMaterialMovementsToJournal(log.getProductionCode(), entry.id());
        }
    }

    private void linkRawMaterialMovementsToJournal(String referenceId, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        List<RawMaterialMovement> movements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.PRODUCTION_LOG, referenceId);
        if (movements.isEmpty()) {
            return;
        }
        movements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
        rawMaterialMovementRepository.saveAll(movements);
    }

    private LocalDate resolveJournalDate(Company company, ProductionLog log) {
        ZoneId zoneId = Optional.ofNullable(company.getTimezone())
                .filter(StringUtils::hasText)
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);
        return log.getProducedAt().atZone(zoneId).toLocalDate();
    }

    private Long requireWipAccountId(ProductionProduct product) {
        Long accountId = metadataLong(product, "wipAccountId");
        if (accountId == null) {
            throw new IllegalStateException("Product " + product.getProductName() + " missing wipAccountId metadata");
        }
        return accountId;
    }

    private Long metadataLong(ProductionProduct product, String key) {
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

    private BigDecimal calculateUnitCost(BigDecimal total, BigDecimal quantity) {
        if (total == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(quantity, 6, COST_ROUNDING);
    }

    private record MaterialIssueSummary(BigDecimal totalCost, Map<Long, BigDecimal> accountTotals) {}

    private record MaterialConsumption(ProductionLogMaterial material, BigDecimal totalCost, Long inventoryAccountId) {}

    private String nextProductionCode(Company company) {
        String prefix = "PROD-" + CODE_DATE.format(LocalDate.now(ZoneOffset.UTC));
        return logRepository.findTopByCompanyAndProductionCodeStartingWithOrderByProductionCodeDesc(company, prefix)
                .map(ProductionLog::getProductionCode)
                .map(existing -> incrementCode(existing, prefix))
                .orElse(prefix + "-001");
    }

    private String incrementCode(String existing, String prefix) {
        try {
            String[] parts = existing.split("-");
            int seq = Integer.parseInt(parts[parts.length - 1]);
            return prefix + "-" + String.format("%03d", seq + 1);
        } catch (Exception ignored) {
            return prefix + "-001";
        }
    }

    private Instant resolveProducedAt(String producedAt) {
        if (!StringUtils.hasText(producedAt)) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(producedAt).toInstant();
        } catch (Exception ex) {
            try {
                return Instant.parse(producedAt);
            } catch (Exception inner) {
                throw new IllegalArgumentException("Invalid producedAt format");
            }
        }
    }

    private ProductionLogDto toDto(ProductionLog log) {
        return new ProductionLogDto(
                log.getId(),
                log.getPublicId(),
                log.getProductionCode(),
                log.getProducedAt(),
                log.getBrand().getName(),
                log.getProduct().getProductName(),
                log.getProduct().getSkuCode(),
                log.getBatchColour(),
                log.getBatchSize(),
                log.getUnitOfMeasure(),
                log.getMixedQuantity(),
                log.getTotalPackedQuantity(),
                log.getWastageQuantity(),
                log.getStatus().name(),
                log.getCreatedBy(),
                log.getUnitCost(),
                log.getMaterialCostTotal(),
                log.getLaborCostTotal(),
                log.getOverheadCostTotal(),
                log.getSalesOrderId(),
                log.getSalesOrderNumber()
        );
    }

    private ProductionLogDetailDto toDetailDto(ProductionLog log) {
        List<ProductionLogMaterialDto> materials = log.getMaterials().stream()
                .map(material -> new ProductionLogMaterialDto(
                        material.getRawMaterial() != null ? material.getRawMaterial().getId() : null,
                        material.getMaterialName(),
                        material.getQuantity(),
                        material.getUnitOfMeasure(),
                        material.getCostPerUnit(),
                        material.getTotalCost()
                ))
                .toList();
        return new ProductionLogDetailDto(
                log.getId(),
                log.getPublicId(),
                log.getProductionCode(),
                log.getProducedAt(),
                log.getBrand().getName(),
                log.getProduct().getProductName(),
                log.getProduct().getSkuCode(),
                log.getBatchColour(),
                log.getBatchSize(),
                log.getUnitOfMeasure(),
                log.getMixedQuantity(),
                log.getTotalPackedQuantity(),
                log.getWastageQuantity(),
                log.getStatus().name(),
                log.getMaterialCostTotal(),
                log.getLaborCostTotal(),
                log.getOverheadCostTotal(),
                log.getUnitCost(),
                log.getSalesOrderId(),
                log.getSalesOrderNumber(),
                log.getNotes(),
                log.getCreatedBy(),
                materials
        );
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
