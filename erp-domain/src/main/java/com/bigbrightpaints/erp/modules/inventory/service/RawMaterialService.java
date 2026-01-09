package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RawMaterialService {

    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository batchRepository;
    private final RawMaterialMovementRepository movementRepository;
    private final CompanyContextService companyContextService;
    private final ProductionProductRepository productionProductRepository;
    private final ProductionBrandRepository productionBrandRepository;
    private final AccountingFacade accountingFacade;
    private final BatchNumberService batchNumberService;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;
    private final CompanyEntityLookup companyEntityLookup;

    public RawMaterialService(RawMaterialRepository rawMaterialRepository,
                              RawMaterialBatchRepository batchRepository,
                              RawMaterialMovementRepository movementRepository,
                              CompanyContextService companyContextService,
                              ProductionProductRepository productionProductRepository,
                              ProductionBrandRepository productionBrandRepository,
                              AccountingFacade accountingFacade,
                              BatchNumberService batchNumberService,
                              ReferenceNumberService referenceNumberService,
                              CompanyClock companyClock,
                              CompanyEntityLookup companyEntityLookup) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.batchRepository = batchRepository;
        this.movementRepository = movementRepository;
        this.companyContextService = companyContextService;
        this.productionProductRepository = productionProductRepository;
        this.productionBrandRepository = productionBrandRepository;
        this.accountingFacade = accountingFacade;
        this.batchNumberService = batchNumberService;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
        this.companyEntityLookup = companyEntityLookup;
    }

    public List<RawMaterialDto> listRawMaterials() {
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public RawMaterialDto getRawMaterial(Long rawMaterialId) {
        RawMaterial material = requireMaterial(rawMaterialId);
        return toDto(material);
    }

    public RawMaterialDto getRawMaterialBySku(String sku) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, sku)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found for SKU " + sku));
        return toDto(material);
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
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
        material.setName(request.name());
        material.setSku(request.sku());
        material.setUnitType(request.unitType());
        material.setReorderLevel(request.reorderLevel() != null ? request.reorderLevel() : BigDecimal.ZERO);
        material.setMinStock(request.minStock() != null ? request.minStock() : BigDecimal.ZERO);
        material.setMaxStock(request.maxStock() != null ? request.maxStock() : BigDecimal.ZERO);
        material.setInventoryAccountId(request.inventoryAccountId());
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
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
        rawMaterialRepository.delete(material);
    }

    public StockSummaryDto summarizeStock() {
        Company company = companyContextService.requireCurrentCompany();
        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        long total = materials.size();
        long lowStock = materials.stream().filter(this::isLowStock).count();
        long criticalStock = materials.stream().filter(this::isCriticalStock).count();
        long batches = materials.stream()
                .map(rawMaterial -> batchRepository.findByRawMaterial(rawMaterial).size())
                .mapToLong(Integer::longValue)
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
        RawMaterial material = requireMaterial(rawMaterialId);
        return batchRepository.findByRawMaterial(material).stream()
                .sorted(Comparator.comparing(RawMaterialBatch::getReceivedAt).reversed())
                .map(this::toBatchDto)
                .toList();
    }

    @Transactional
    public RawMaterialBatchDto createBatch(Long rawMaterialId, RawMaterialBatchRequest request) {
        ReceiptResult receipt = recordReceipt(rawMaterialId, request, null);
        return toBatchDto(receipt.batch());
    }

    @Transactional
    public ReceiptResult recordReceipt(Long rawMaterialId,
                                       RawMaterialBatchRequest request,
                                       ReceiptContext context) {
        RawMaterial material = requireMaterial(rawMaterialId);
        BigDecimal quantity = requirePositive(request.quantity(), "quantity");
        BigDecimal costPerUnit = requirePositive(request.costPerUnit(), "costPerUnit");
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
        BigDecimal currentStock = material.getCurrentStock() == null ? BigDecimal.ZERO : material.getCurrentStock();
        material.setCurrentStock(currentStock.add(quantity));
        rawMaterialRepository.save(material);
        RawMaterialBatch savedBatch = batchRepository.save(batch);
        ReceiptContext effectiveContext = context != null ? context : ReceiptContext.forBatch(batch.getBatchCode());
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

    @Transactional
    public RawMaterialBatchDto intake(RawMaterialIntakeRequest request) {
        return createBatch(request.rawMaterialId(), new RawMaterialBatchRequest(
                request.batchCode(),
                request.quantity(),
                request.unit(),
                request.costPerUnit(),
                request.supplierId(),
                request.notes()
        ));
    }

    private BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private RawMaterial requireMaterial(Long rawMaterialId) {
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
    }

    private RawMaterialDto toDto(RawMaterial material) {
        return new RawMaterialDto(material.getId(), material.getPublicId(), material.getName(), material.getSku(),
                material.getUnitType(), material.getReorderLevel(), material.getCurrentStock(),
                material.getMinStock(), material.getMaxStock(), stockStatus(material), material.getInventoryAccountId(),
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
            throw new IllegalStateException("Raw material " + material.getName() + " is missing an inventory account");
        }
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
    }

    private String resolveBatchCode(RawMaterial material, String requested) {
        if (StringUtils.hasText(requested)) {
            return requested.trim();
        }
        return batchNumberService.nextRawMaterialBatchCode(material);
    }

    private String resolveReferenceNumber(RawMaterial material, ReceiptContext context, RawMaterialBatch batch) {
        if (context != null && StringUtils.hasText(context.referenceId())) {
            String prefix = StringUtils.hasText(context.referenceType()) ? context.referenceType() : "RM";
            return prefix + "-" + context.referenceId();
        }
        return referenceNumberService.rawMaterialReceiptReference(material.getCompany(), batch.getBatchCode());
    }

    public record ReceiptContext(String referenceType, String referenceId, String memo, boolean postJournal) {
        public static ReceiptContext forBatch(String batchCode) {
            return new ReceiptContext(InventoryReference.RAW_MATERIAL_PURCHASE, batchCode, "Raw material batch " + batchCode, true);
        }
    }

    public record ReceiptResult(RawMaterialBatch batch, RawMaterialMovement movement, Long journalEntryId) {}
}
