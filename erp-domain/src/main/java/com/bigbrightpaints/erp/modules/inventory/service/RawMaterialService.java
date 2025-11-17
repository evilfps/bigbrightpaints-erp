package com.bigbrightpaints.erp.modules.inventory.service;

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
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
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
    private final CompanyContextService companyContextService;
    private final ProductionProductRepository productionProductRepository;
    private final ProductionBrandRepository productionBrandRepository;
    private final AccountingFacade accountingFacade;
    private final SupplierRepository supplierRepository;
    private final BatchNumberService batchNumberService;
    private final ReferenceNumberService referenceNumberService;

    public RawMaterialService(RawMaterialRepository rawMaterialRepository,
                              RawMaterialBatchRepository batchRepository,
                              RawMaterialMovementRepository movementRepository,
                              CompanyContextService companyContextService,
                              ProductionProductRepository productionProductRepository,
                              ProductionBrandRepository productionBrandRepository,
                              AccountingFacade accountingFacade,
                              SupplierRepository supplierRepository,
                              BatchNumberService batchNumberService,
                              ReferenceNumberService referenceNumberService) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.batchRepository = batchRepository;
        this.movementRepository = movementRepository;
        this.companyContextService = companyContextService;
        this.productionProductRepository = productionProductRepository;
        this.productionBrandRepository = productionBrandRepository;
        this.accountingFacade = accountingFacade;
        this.supplierRepository = supplierRepository;
        this.batchNumberService = batchNumberService;
        this.referenceNumberService = referenceNumberService;
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
        material.setReorderLevel(request.reorderLevel());
        material.setMinStock(request.minStock());
        material.setMaxStock(request.maxStock());
        material.setInventoryAccountId(request.inventoryAccountId());
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
        material.setReorderLevel(request.reorderLevel());
        material.setMinStock(request.minStock());
        material.setMaxStock(request.maxStock());
        material.setInventoryAccountId(request.inventoryAccountId());
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
        return new StockSummaryDto(total, lowStock, criticalStock, batches);
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
        Supplier supplier = requireSupplier(material.getCompany(), request.supplierId());
        ensurePostingAccounts(material, supplier);
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        String batchCode = resolveBatchCode(material, request.batchCode());
        batch.setBatchCode(batchCode);
        batch.setQuantity(request.quantity());
        batch.setUnit(request.unit());
        batch.setCostPerUnit(request.costPerUnit());
        batch.setSupplierName(supplier.getName());
        batch.setSupplier(supplier);
        batch.setNotes(request.notes());
        material.setCurrentStock(material.getCurrentStock().add(request.quantity()));
        rawMaterialRepository.save(material);
        RawMaterialBatch savedBatch = batchRepository.save(batch);
        ReceiptContext effectiveContext = context != null ? context : ReceiptContext.forBatch(batch.getBatchCode());
        RawMaterialMovement receiptMovement = recordReceiptMovement(material, savedBatch, request.quantity(), request.costPerUnit(), effectiveContext);
        Long journalEntryId = effectiveContext.postJournal()
                ? postInventoryReceipt(material, supplier, savedBatch, request.quantity(), request.costPerUnit(), effectiveContext)
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

    private RawMaterial requireMaterial(Long rawMaterialId) {
        Company company = companyContextService.requireCurrentCompany();
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
    }

    private RawMaterialDto toDto(RawMaterial material) {
        return new RawMaterialDto(material.getId(), material.getPublicId(), material.getName(), material.getSku(),
                material.getUnitType(), material.getReorderLevel(), material.getCurrentStock(),
                material.getMinStock(), material.getMaxStock(), stockStatus(material), material.getInventoryAccountId());
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
        BigDecimal totalCost = safeMultiply(quantity, costPerUnit);
        if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String memo = context != null && StringUtils.hasText(context.memo())
                ? context.memo()
                : "Raw material batch " + batch.getBatchCode();
        String referenceNumber = resolveReferenceNumber(material, context, batch);
        JournalEntryDto entry = accountingFacade.postPurchaseJournal(
                supplier.getId(),
                batch.getBatchCode(),
                currentDate(material.getCompany()),
                memo,
                Map.of(inventoryAccountId, totalCost),
                totalCost,
                referenceNumber
        );
        return entry != null ? entry.id() : null;
    }

    private LocalDate currentDate(Company company) {
        String timezone = company.getTimezone() == null ? "UTC" : company.getTimezone();
        return LocalDate.now(ZoneId.of(timezone));
    }

    private BigDecimal safeMultiply(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return BigDecimal.ZERO;
        }
        return left.multiply(right);
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
        return supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
    }

    private void ensurePostingAccounts(RawMaterial material, Supplier supplier) {
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
