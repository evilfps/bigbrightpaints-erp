package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialIntakeRecord;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialIntakeRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RawMaterialService {

    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository batchRepository;
    private final RawMaterialMovementRepository movementRepository;
    private final RawMaterialIntakeRepository rawMaterialIntakeRepository;
    private final CompanyContextService companyContextService;
    private final ProductionProductRepository productionProductRepository;
    private final ProductionBrandRepository productionBrandRepository;
    private final AccountingFacade accountingFacade;
    private final BatchNumberService batchNumberService;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;
    private final CompanyEntityLookup companyEntityLookup;
    private final AuditService auditService;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();
    private final Environment environment;
    private final TransactionTemplate transactionTemplate;
    private final boolean rawMaterialIntakeEnabled;

    public RawMaterialService(RawMaterialRepository rawMaterialRepository,
                              RawMaterialBatchRepository batchRepository,
                              RawMaterialMovementRepository movementRepository,
                              RawMaterialIntakeRepository rawMaterialIntakeRepository,
                              CompanyContextService companyContextService,
                              ProductionProductRepository productionProductRepository,
                              ProductionBrandRepository productionBrandRepository,
                              AccountingFacade accountingFacade,
                              BatchNumberService batchNumberService,
                              ReferenceNumberService referenceNumberService,
                              CompanyClock companyClock,
                              CompanyEntityLookup companyEntityLookup,
                              AuditService auditService,
                              Environment environment,
                              PlatformTransactionManager transactionManager,
                              @Value("${erp.raw-material.intake.enabled:false}") boolean rawMaterialIntakeEnabled) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.batchRepository = batchRepository;
        this.movementRepository = movementRepository;
        this.rawMaterialIntakeRepository = rawMaterialIntakeRepository;
        this.companyContextService = companyContextService;
        this.productionProductRepository = productionProductRepository;
        this.productionBrandRepository = productionBrandRepository;
        this.accountingFacade = accountingFacade;
        this.batchNumberService = batchNumberService;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
        this.companyEntityLookup = companyEntityLookup;
        this.auditService = auditService;
        this.environment = environment;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.rawMaterialIntakeEnabled = rawMaterialIntakeEnabled;
    }

    public List<RawMaterialDto> listRawMaterials() {
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public RawMaterialDto createRawMaterial(RawMaterialRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName(request.name());
        material.setSku(request.sku());
        material.setUnitType(request.unitType());
        material.setCurrentStock(BigDecimal.ZERO);
        material.setReorderLevel(request.reorderLevel() != null ? request.reorderLevel() : BigDecimal.ZERO);
        material.setMinStock(request.minStock() != null ? request.minStock() : BigDecimal.ZERO);
        material.setMaxStock(request.maxStock() != null ? request.maxStock() : BigDecimal.ZERO);
        material.setInventoryAccountId(request.inventoryAccountId());
        material.setCostingMethod(CostingMethodUtils.normalizeRawMaterialMethodOrDefault(request.costingMethod()));
        if (material.getInventoryAccountId() == null) {
            material.setInventoryAccountId(company.getDefaultInventoryAccountId());
        }
        RawMaterial saved = rawMaterialRepository.save(material);
        syncProductFromMaterial(company, saved);
        return toDto(saved);
    }

    @Transactional
    public RawMaterialDto updateRawMaterial(Long id, RawMaterialRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterial material = rawMaterialRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material not found"));
        material.setName(request.name());
        material.setSku(request.sku());
        material.setUnitType(request.unitType());
        material.setReorderLevel(request.reorderLevel() != null ? request.reorderLevel() : BigDecimal.ZERO);
        material.setMinStock(request.minStock() != null ? request.minStock() : BigDecimal.ZERO);
        material.setMaxStock(request.maxStock() != null ? request.maxStock() : BigDecimal.ZERO);
        material.setInventoryAccountId(request.inventoryAccountId());
        material.setCostingMethod(CostingMethodUtils.normalizeRawMaterialMethodOrDefault(request.costingMethod()));
        if (material.getInventoryAccountId() == null) {
            material.setInventoryAccountId(company.getDefaultInventoryAccountId());
        }
        RawMaterial saved = rawMaterialRepository.save(material);
        syncProductFromMaterial(company, saved);
        return toDto(saved);
    }

    @Transactional
    public void deleteRawMaterial(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterial material = rawMaterialRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material not found"));
        rawMaterialRepository.delete(material);
    }

    public StockSummaryDto summarizeStock() {
        Company company = companyContextService.requireCurrentCompany();
        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        long total = materials.size();
        long lowStock = materials.stream().filter(this::isLowStock).count();
        long criticalStock = materials.stream().filter(this::isCriticalStock).count();
        List<Long> materialIds = materials.stream()
                .map(RawMaterial::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, Long> batchCountByMaterialId = materialIds.isEmpty()
                ? Map.of()
                : batchRepository.countBatchesGroupedByRawMaterialIds(materialIds).stream()
                .filter(row -> row != null && row.length >= 2 && row[0] instanceof Number && row[1] instanceof Number)
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue(),
                        Long::sum,
                        HashMap::new
                ));
        long batches = materials.stream()
                .map(RawMaterial::getId)
                .mapToLong(id -> id == null ? 0L : batchCountByMaterialId.getOrDefault(id, 0L))
                .sum();
        return new StockSummaryDto(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                total,
                lowStock,
                criticalStock,
                batches);
    }

    public List<InventoryStockSnapshot> listInventory() {
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(this::toSnapshot)
                .toList();
    }

    public List<InventoryStockSnapshot> listLowStock() {
        return listInventory().stream()
                .filter(snapshot -> "LOW_STOCK".equals(snapshot.status()) || "CRITICAL".equals(snapshot.status()))
                .toList();
    }

    public List<RawMaterialBatchDto> listBatches(Long rawMaterialId) {
        // NOTE: This is a read-only operation. Avoid taking a PESSIMISTIC_WRITE lock here
        // (which requires an active transaction) to prevent TransactionRequiredException.
        Company company = companyContextService.requireCurrentCompany();
        RawMaterial material = rawMaterialRepository.findByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material not found"));
        return batchRepository.findByRawMaterial(material).stream()
                .sorted(Comparator.comparing(RawMaterialBatch::getReceivedAt).reversed())
                .map(this::toBatchDto)
                .toList();
    }

    public RawMaterialBatchDto createBatch(Long rawMaterialId, RawMaterialBatchRequest request, String idempotencyKey) {
        if (!rawMaterialIntakeEnabled) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Manual raw material batch creation is disabled; use raw material purchases for supplier receipts.")
                    .withDetail("endpoint", "/api/v1/purchasing/raw-material-purchases")
                    .withDetail("canonicalPath", "/api/v1/purchasing/raw-material-purchases")
                    .withDetail("setting", "erp.raw-material.intake.enabled");
        }
        if (request == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material batch request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        String normalizedKey = requireIdempotencyKey(idempotencyKey, "manual raw material batch creation");
        String signature = buildManualIntakeSignature(rawMaterialId, request);

        RawMaterialIntakeRecord existing = rawMaterialIntakeRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, signature, normalizedKey);
            return resolveExistingBatch(existing, normalizedKey);
        }
        try {
            RawMaterialBatchDto response = transactionTemplate.execute(status ->
                    createManualIntakeInternal(company, rawMaterialId, request, normalizedKey, signature));
            if (response == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Manual raw material intake failed to return a batch");
            }
            return response;
        } catch (RuntimeException ex) {
            if (!idempotencyReservationService.isDataIntegrityViolation(ex)) {
                throw ex;
            }
            RawMaterialIntakeRecord concurrent = rawMaterialIntakeRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, signature, normalizedKey);
            return resolveExistingBatch(concurrent, normalizedKey);
        }
    }

    @Transactional
    public ReceiptResult recordReceipt(Long rawMaterialId,
                                       RawMaterialBatchRequest request,
                                       ReceiptContext context) {
        RawMaterial material = requireMaterial(rawMaterialId);
        BigDecimal quantity = ValidationUtils.requirePositive(request.quantity(), "quantity");
        BigDecimal costPerUnit = ValidationUtils.requirePositive(request.costPerUnit(), "costPerUnit");
        Supplier supplier = requireSupplier(material.getCompany(), request.supplierId());
        ensurePostingAccounts(material, supplier);
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        String batchCode = resolveBatchCode(material, request.batchCode());
        batch.setBatchCode(batchCode);
        batch.setQuantity(quantity);
        batch.setUnit(request.unit());
        batch.setCostPerUnit(costPerUnit);
        batch.setSupplierName(supplier.getName());
        batch.setSupplier(supplier);
        batch.setNotes(request.notes());
        batch.setManufacturedAt(resolveManufacturedAt(material.getCompany(), request.manufacturingDate()));
        batch.setExpiryDate(request.expiryDate());
        BigDecimal currentStock = material.getCurrentStock() == null ? BigDecimal.ZERO : material.getCurrentStock();
        material.setCurrentStock(currentStock.add(quantity));
        rawMaterialRepository.save(material);
        ReceiptContext effectiveContext = context != null ? context : ReceiptContext.forBatch(batch.getBatchCode());
        batch.setSource(resolveBatchSource(effectiveContext.referenceType()));
        RawMaterialBatch savedBatch = batchRepository.save(batch);
        RawMaterialMovement receiptMovement = recordReceiptMovement(material, savedBatch, quantity, costPerUnit, effectiveContext);
        Long journalEntryId = effectiveContext.postJournal()
                ? postInventoryReceipt(material, supplier, savedBatch, quantity, costPerUnit, effectiveContext)
                : null;
        if (journalEntryId != null && receiptMovement != null) {
            receiptMovement.setJournalEntryId(journalEntryId);
            movementRepository.save(receiptMovement);
        }
        return new ReceiptResult(savedBatch, receiptMovement, journalEntryId);
    }

    public RawMaterialBatchDto intake(RawMaterialIntakeRequest request, String idempotencyKey) {
        if (!rawMaterialIntakeEnabled) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Raw material intake is disabled; use raw material purchases for supplier invoices.")
                    .withDetail("endpoint", "/api/v1/purchasing/raw-material-purchases")
                    .withDetail("canonicalPath", "/api/v1/purchasing/raw-material-purchases")
                    .withDetail("setting", "erp.raw-material.intake.enabled");
        }
        if (request == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material intake request is required");
        }
        RawMaterialBatchRequest batchRequest = new RawMaterialBatchRequest(
                request.batchCode(),
                request.quantity(),
                request.unit(),
                request.costPerUnit(),
                request.supplierId(),
                request.manufacturingDate(),
                request.expiryDate(),
                request.notes()
        );
        return createBatch(request.rawMaterialId(), batchRequest, idempotencyKey);
    }

    private RawMaterial requireMaterial(Long rawMaterialId) {
        // This method is used by write flows (receipts/intake/adjustments). Keep locking semantics.
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material not found"));
    }

    private RawMaterialDto toDto(RawMaterial material) {
        return new RawMaterialDto(material.getId(), material.getPublicId(), material.getName(), material.getSku(),
                material.getUnitType(), material.getReorderLevel(), material.getCurrentStock(),
                material.getMinStock(), material.getMaxStock(), stockStatus(material), material.getInventoryAccountId(),
                material.getCostingMethod(),
                material.getMaterialType() != null ? material.getMaterialType().name() : null);
    }

    private RawMaterialBatchDto toBatchDto(RawMaterialBatch batch) {
        Supplier supplier = batch.getSupplier();
        return new RawMaterialBatchDto(batch.getId(), batch.getPublicId(), batch.getBatchCode(), batch.getQuantity(),
                batch.getUnit(), batch.getCostPerUnit(),
                supplier != null ? supplier.getId() : null,
                batch.getSupplierName(),
                batch.getReceivedAt(), batch.getNotes());
    }

    private InventoryStockSnapshot toSnapshot(RawMaterial material) {
        return new InventoryStockSnapshot(material.getName(), material.getSku(), material.getCurrentStock(),
                material.getReorderLevel(), stockStatus(material));
    }

    private String stockStatus(RawMaterial material) {
        if (material.getCurrentStock().compareTo(material.getMinStock()) <= 0) {
            return "CRITICAL";
        }
        if (isLowStock(material)) {
            return "LOW_STOCK";
        }
        return "IN_STOCK";
    }

    private boolean isLowStock(RawMaterial material) {
        return material.getCurrentStock().compareTo(material.getReorderLevel()) < 0;
    }

    private boolean isCriticalStock(RawMaterial material) {
        return material.getCurrentStock().compareTo(material.getMinStock()) <= 0;
    }

    private RawMaterialMovement recordReceiptMovement(RawMaterial material,
                                                      RawMaterialBatch batch,
                                                      BigDecimal quantity,
                                                      BigDecimal costPerUnit,
                                                      ReceiptContext context) {
        BigDecimal normalizedQty = quantity == null ? BigDecimal.ZERO : quantity;
        if (normalizedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        RawMaterialMovement movement = new RawMaterialMovement();
        movement.setRawMaterial(material);
        movement.setRawMaterialBatch(batch);
        String referenceType = context != null && StringUtils.hasText(context.referenceType())
                ? context.referenceType()
                : InventoryReference.RAW_MATERIAL_PURCHASE;
        String referenceId = context != null && StringUtils.hasText(context.referenceId())
                ? context.referenceId()
                : batch.getBatchCode();
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(normalizedQty);
        movement.setUnitCost(costPerUnit == null ? BigDecimal.ZERO : costPerUnit);
        return movementRepository.save(movement);
    }

    private Long postInventoryReceipt(RawMaterial material,
                                      Supplier supplier,
                                      RawMaterialBatch batch,
                                      BigDecimal quantity,
                                      BigDecimal costPerUnit,
                                      ReceiptContext context) {
        Long inventoryAccountId = material.getInventoryAccountId();
        if (inventoryAccountId == null || supplier.getPayableAccount() == null) {
            return null;
        }
        BigDecimal totalCost = MoneyUtils.safeMultiply(quantity, costPerUnit);
        if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String memo = context != null && StringUtils.hasText(context.memo())
                ? context.memo()
                : "Raw material batch " + batch.getBatchCode();
        JournalEntryDto entry = accountingFacade.postPurchaseJournal(
                supplier.getId(),
                batch.getBatchCode(),
                companyClock.today(material.getCompany()),
                memo,
                Map.of(inventoryAccountId, totalCost),
                totalCost
        );
        return entry != null ? entry.id() : null;
    }

    private LocalDate currentDate(Company company) {
        return companyClock.today(company);
    }

    private void syncProductFromMaterial(Company company, RawMaterial material) {
        if (!StringUtils.hasText(material.getSku())) {
            return;
        }
        ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, material.getSku())
                .orElseGet(() -> {
                    ProductionProduct created = new ProductionProduct();
                    created.setCompany(company);
                    created.setBrand(resolveRawMaterialBrand(company));
                    created.setProductName(material.getName());
                    created.setCategory("RAW_MATERIAL");
                    created.setUnitOfMeasure(material.getUnitType());
                    created.setSkuCode(material.getSku());
                    created.setBasePrice(BigDecimal.ZERO);
                    created.setGstRate(BigDecimal.ZERO);
                    created.setMinDiscountPercent(BigDecimal.ZERO);
                    created.setMinSellingPrice(BigDecimal.ZERO);
                    created.setMetadata(new HashMap<>());
                    return created;
                });
        product.setProductName(material.getName());
        product.setCategory("RAW_MATERIAL");
        product.setUnitOfMeasure(material.getUnitType());
        if (product.getBrand() == null) {
            product.setBrand(resolveRawMaterialBrand(company));
        }
        Map<String, Object> metadata = product.getMetadata() == null ? new HashMap<>() : new HashMap<>(product.getMetadata());
        metadata.put("linkedRawMaterialId", material.getId());
        metadata.put("linkedRawMaterialSku", material.getSku());
        product.setMetadata(metadata);
        productionProductRepository.save(product);
    }

    private ProductionBrand resolveRawMaterialBrand(Company company) {
        return productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "RAW-MATERIALS")
                .orElseGet(() -> {
                    ProductionBrand brand = new ProductionBrand();
                    brand.setCompany(company);
                    brand.setCode("RAW-MATERIALS");
                    brand.setName("Raw Materials");
                    return productionBrandRepository.save(brand);
                });
    }

    private Supplier requireSupplier(Company company, Long supplierId) {
        return companyEntityLookup.requireSupplier(company, supplierId);
    }

    private void ensurePostingAccounts(RawMaterial material, Supplier supplier) {
        if (material.getInventoryAccountId() == null && material.getCompany() != null) {
            // Try company default inventory account before failing
            material.setInventoryAccountId(material.getCompany().getDefaultInventoryAccountId());
        }
        if (material.getInventoryAccountId() == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Raw material " + material.getName() + " is missing an inventory account");
        }
        if (supplier.getPayableAccount() == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Supplier " + supplier.getName() + " is missing a payable account");
        }
    }

    private String resolveBatchCode(RawMaterial material, String requested) {
        if (StringUtils.hasText(requested)) {
            String trimmed = requested.trim();
            ensureBatchCodeUnique(material, trimmed);
            return trimmed;
        }
        return nextUniqueBatchCode(material);
    }

    private String nextUniqueBatchCode(RawMaterial material) {
        String candidate = batchNumberService.nextRawMaterialBatchCode(material);
        int attempts = 0;
        while (batchRepository.existsByRawMaterialAndBatchCode(material, candidate)) {
            if (attempts++ > 10) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Unable to allocate unique batch code for raw material "
                        + describeMaterial(material));
            }
            candidate = batchNumberService.nextRawMaterialBatchCode(material);
        }
        return candidate;
    }

    private void ensureBatchCodeUnique(RawMaterial material, String batchCode) {
        if (batchRepository.existsByRawMaterialAndBatchCode(material, batchCode)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Batch code already exists for raw material "
                    + describeMaterial(material) + ": " + batchCode);
        }
    }

    private String describeMaterial(RawMaterial material) {
        if (StringUtils.hasText(material.getSku())) {
            return material.getSku();
        }
        if (StringUtils.hasText(material.getName())) {
            return material.getName();
        }
        return material.getId() != null ? material.getId().toString() : "unknown";
    }

    private String resolveReferenceNumber(RawMaterial material, ReceiptContext context, RawMaterialBatch batch) {
        if (context != null && StringUtils.hasText(context.referenceId())) {
            String prefix = StringUtils.hasText(context.referenceType()) ? context.referenceType() : "RM";
            return prefix + "-" + context.referenceId();
        }
        return referenceNumberService.rawMaterialReceiptReference(material.getCompany(), batch.getBatchCode());
    }

    private RawMaterialBatchDto createManualIntakeInternal(Company company,
                                                           Long rawMaterialId,
                                                           RawMaterialBatchRequest request,
                                                           String idempotencyKey,
                                                           String requestSignature) {
        RawMaterialIntakeRecord record = new RawMaterialIntakeRecord();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(requestSignature);
        record.setRawMaterialId(rawMaterialId);
        record = rawMaterialIntakeRepository.saveAndFlush(record);

        ReceiptResult receipt = recordReceipt(rawMaterialId, request, null);
        RawMaterialBatch batch = receipt.batch();
        record.setRawMaterialBatchId(batch != null ? batch.getId() : null);
        record.setRawMaterialMovementId(receipt.movement() != null ? receipt.movement().getId() : null);
        record.setJournalEntryId(receipt.journalEntryId());
        rawMaterialIntakeRepository.save(record);

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("operation", "manual-raw-material-intake");
        auditMetadata.put("idempotencyKey", idempotencyKey);
        if (rawMaterialId != null) {
            auditMetadata.put("rawMaterialId", rawMaterialId.toString());
        }
        if (batch != null && batch.getId() != null) {
            auditMetadata.put("batchId", batch.getId().toString());
        }
        if (receipt.journalEntryId() != null) {
            auditMetadata.put("journalEntryId", receipt.journalEntryId().toString());
        }
        auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);

        return toBatchDto(batch);
    }

    private RawMaterialBatchDto resolveExistingBatch(RawMaterialIntakeRecord record, String idempotencyKey) {
        Long batchId = record.getRawMaterialBatchId();
        if (batchId == null) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used but batch record is missing")
                    .withDetail("idempotencyKey", idempotencyKey);
        }
        RawMaterialBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Raw material batch not found for idempotency key"));
        return toBatchDto(batch);
    }

    private void assertIdempotencyMatch(RawMaterialIntakeRecord record, String expectedSignature, String idempotencyKey) {
        idempotencyReservationService.assertAndRepairSignature(
                record,
                idempotencyKey,
                expectedSignature,
                RawMaterialIntakeRecord::getIdempotencyHash,
                RawMaterialIntakeRecord::setIdempotencyHash,
                rawMaterialIntakeRepository::save
        );
    }

    private String requireIdempotencyKey(String idempotencyKey, String label) {
        return idempotencyReservationService.requireKey(idempotencyKey, label);
    }

    private String buildManualIntakeSignature(Long rawMaterialId, RawMaterialBatchRequest request) {
        IdempotencySignatureBuilder signature = IdempotencySignatureBuilder.create()
                .add(rawMaterialId != null ? rawMaterialId : "");
        if (request == null) {
            return signature.buildHash();
        }
        return signature
                .addToken(request.batchCode())
                .addAmount(request.quantity())
                .addToken(request.unit())
                .addAmount(request.costPerUnit())
                .add(request.supplierId() != null ? request.supplierId() : "")
                .add(request.manufacturingDate() != null ? request.manufacturingDate() : "")
                .add(request.expiryDate() != null ? request.expiryDate() : "")
                .addToken(request.notes())
                .buildHash();
    }

    private java.time.Instant resolveManufacturedAt(Company company, LocalDate manufacturingDate) {
        if (manufacturingDate == null) {
            return companyClock.now(company);
        }
        return manufacturingDate.atStartOfDay(resolveZone(company)).toInstant();
    }

    private ZoneId resolveZone(Company company) {
        String timezone = company != null && StringUtils.hasText(company.getTimezone())
                ? company.getTimezone()
                : "UTC";
        return ZoneId.of(timezone);
    }

    private InventoryBatchSource resolveBatchSource(String referenceType) {
        if (!StringUtils.hasText(referenceType)) {
            return InventoryBatchSource.PURCHASE;
        }
        String normalized = referenceType.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case InventoryReference.OPENING_STOCK, "ADJUSTMENT", InventoryReference.PURCHASE_RETURN ->
                    InventoryBatchSource.ADJUSTMENT;
            case InventoryReference.PRODUCTION_LOG,
                    InventoryReference.PACKING_RECORD,
                    InventoryReference.MANUFACTURING_ORDER -> InventoryBatchSource.PRODUCTION;
            default -> InventoryBatchSource.PURCHASE;
        };
    }

    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    public record ReceiptContext(String referenceType, String referenceId, String memo, boolean postJournal) {
        public static ReceiptContext forBatch(String batchCode) {
            return new ReceiptContext(InventoryReference.RAW_MATERIAL_PURCHASE, batchCode, "Raw material batch " + batchCode, true);
        }
    }

    public record ReceiptResult(RawMaterialBatch batch, RawMaterialMovement movement, Long journalEntryId) {}
}
