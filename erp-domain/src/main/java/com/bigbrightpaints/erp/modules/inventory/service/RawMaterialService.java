package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustmentLine;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustmentRepository;
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
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;

import jakarta.transaction.Transactional;

@Service
public class RawMaterialService {

  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository batchRepository;
  private final RawMaterialMovementRepository movementRepository;
  private final RawMaterialAdjustmentRepository rawMaterialAdjustmentRepository;
  private final RawMaterialIntakeRepository rawMaterialIntakeRepository;
  private final CompanyContextService companyContextService;
  private final ProductionProductRepository productionProductRepository;
  private final ProductionBrandRepository productionBrandRepository;
  private final AccountingFacade accountingFacade;
  private final BatchNumberService batchNumberService;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyClock companyClock;
  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final AuditService auditService;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();
  private final Environment environment;
  private final TransactionTemplate transactionTemplate;
  private final boolean rawMaterialIntakeEnabled;

  @Autowired(required = false)
  private InventoryPhysicalCountService inventoryPhysicalCountService;

  public RawMaterialService(
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository batchRepository,
      RawMaterialMovementRepository movementRepository,
      RawMaterialAdjustmentRepository rawMaterialAdjustmentRepository,
      RawMaterialIntakeRepository rawMaterialIntakeRepository,
      CompanyContextService companyContextService,
      ProductionProductRepository productionProductRepository,
      ProductionBrandRepository productionBrandRepository,
      AccountingFacade accountingFacade,
      BatchNumberService batchNumberService,
      ReferenceNumberService referenceNumberService,
      CompanyClock companyClock,
      CompanyScopedInventoryLookupService inventoryLookupService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      AuditService auditService,
      Environment environment,
      PlatformTransactionManager transactionManager,
      @Value("${erp.raw-material.intake.enabled:false}") boolean rawMaterialIntakeEnabled) {
    this.rawMaterialRepository = rawMaterialRepository;
    this.batchRepository = batchRepository;
    this.movementRepository = movementRepository;
    this.rawMaterialAdjustmentRepository = rawMaterialAdjustmentRepository;
    this.rawMaterialIntakeRepository = rawMaterialIntakeRepository;
    this.companyContextService = companyContextService;
    this.productionProductRepository = productionProductRepository;
    this.productionBrandRepository = productionBrandRepository;
    this.accountingFacade = accountingFacade;
    this.batchNumberService = batchNumberService;
    this.referenceNumberService = referenceNumberService;
    this.companyClock = companyClock;
    this.inventoryLookupService = inventoryLookupService;
    this.purchasingLookupService = purchasingLookupService;
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
    material.setMaterialType(resolveMaterialType(request.materialType()));
    material.setUnitType(request.unitType());
    material.setCurrentStock(BigDecimal.ZERO);
    material.setReorderLevel(
        request.reorderLevel() != null ? request.reorderLevel() : BigDecimal.ZERO);
    material.setMinStock(request.minStock() != null ? request.minStock() : BigDecimal.ZERO);
    material.setMaxStock(request.maxStock() != null ? request.maxStock() : BigDecimal.ZERO);
    material.setInventoryAccountId(request.inventoryAccountId());
    material.setCostingMethod(
        CostingMethodUtils.normalizeRawMaterialMethodOrDefault(request.costingMethod()));
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
    RawMaterial material =
        rawMaterialRepository
            .lockByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Raw material not found"));
    material.setName(request.name());
    material.setSku(request.sku());
    material.setMaterialType(resolveMaterialType(request.materialType()));
    material.setUnitType(request.unitType());
    material.setReorderLevel(
        request.reorderLevel() != null ? request.reorderLevel() : BigDecimal.ZERO);
    material.setMinStock(request.minStock() != null ? request.minStock() : BigDecimal.ZERO);
    material.setMaxStock(request.maxStock() != null ? request.maxStock() : BigDecimal.ZERO);
    material.setInventoryAccountId(request.inventoryAccountId());
    material.setCostingMethod(
        CostingMethodUtils.normalizeRawMaterialMethodOrDefault(request.costingMethod()));
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
    RawMaterial material =
        rawMaterialRepository
            .lockByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Raw material not found"));
    rawMaterialRepository.delete(material);
  }

  public StockSummaryDto summarizeStock() {
    Company company = companyContextService.requireCurrentCompany();
    List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
    long total = materials.size();
    long lowStock = materials.stream().filter(this::isLowStock).count();
    long criticalStock = materials.stream().filter(this::isCriticalStock).count();
    List<Long> materialIds =
        materials.stream().map(RawMaterial::getId).filter(java.util.Objects::nonNull).toList();
    Map<Long, Long> batchCountByMaterialId =
        materialIds.isEmpty()
            ? Map.of()
            : batchRepository.countBatchesGroupedByRawMaterialIds(materialIds).stream()
                .filter(
                    row ->
                        row != null
                            && row.length >= 2
                            && row[0] instanceof Number
                            && row[1] instanceof Number)
                .collect(
                    java.util.stream.Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue(),
                        Long::sum,
                        HashMap::new));
    long batches =
        materials.stream()
            .map(RawMaterial::getId)
            .mapToLong(id -> id == null ? 0L : batchCountByMaterialId.getOrDefault(id, 0L))
            .sum();
    return new StockSummaryDto(
        null, null, null, null, null, null, null, null, total, lowStock, criticalStock, batches);
  }

  public List<RawMaterialStockEntryDto> listStockEntries() {
    Company company = companyContextService.requireCurrentCompany();
    return rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
        .map(
            material ->
                new RawMaterialStockEntryDto(
                    material.getId(),
                    material.getSku(),
                    material.getName(),
                    safeQuantity(material.getCurrentStock())))
        .toList();
  }

  public List<InventoryStockSnapshot> listInventory() {
    Company company = companyContextService.requireCurrentCompany();
    return rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
        .map(this::toSnapshot)
        .toList();
  }

  public List<InventoryStockSnapshot> listLowStock() {
    return listInventory().stream()
        .filter(
            snapshot ->
                "LOW_STOCK".equals(snapshot.status()) || "CRITICAL".equals(snapshot.status()))
        .toList();
  }

  public List<RawMaterialBatchDto> listBatches(Long rawMaterialId) {
    // NOTE: This is a read-only operation. Avoid taking a PESSIMISTIC_WRITE lock here
    // (which requires an active transaction) to prevent TransactionRequiredException.
    Company company = companyContextService.requireCurrentCompany();
    RawMaterial material =
        rawMaterialRepository
            .findByCompanyAndId(company, rawMaterialId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Raw material not found"));
    return batchRepository.findByRawMaterial(material).stream()
        .sorted(Comparator.comparing(RawMaterialBatch::getReceivedAt).reversed())
        .map(this::toBatchDto)
        .toList();
  }

  public RawMaterialBatchDto createBatch(
      Long rawMaterialId, RawMaterialBatchRequest request, String idempotencyKey) {
    if (!rawMaterialIntakeEnabled) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Manual raw material batch creation is disabled; use raw material purchases for"
                  + " supplier receipts.")
          .withDetail("endpoint", "/api/v1/purchasing/raw-material-purchases")
          .withDetail("canonicalPath", "/api/v1/purchasing/raw-material-purchases")
          .withDetail("setting", "erp.raw-material.intake.enabled");
    }
    if (request == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Raw material batch request is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    String normalizedKey =
        requireIdempotencyKey(idempotencyKey, "manual raw material batch creation");
    String signature = buildManualIntakeSignature(rawMaterialId, request);

    RawMaterialIntakeRecord existing =
        rawMaterialIntakeRepository
            .findByCompanyAndIdempotencyKey(company, normalizedKey)
            .orElse(null);
    if (existing != null) {
      assertIdempotencyMatch(existing, signature, normalizedKey);
      return resolveExistingBatch(existing, normalizedKey);
    }
    try {
      RawMaterialBatchDto response =
          transactionTemplate.execute(
              status ->
                  createManualIntakeInternal(
                      company, rawMaterialId, request, normalizedKey, signature));
      if (response == null) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
            "Manual raw material batch creation failed to return a batch");
      }
      return response;
    } catch (RuntimeException ex) {
      if (!idempotencyReservationService.isDataIntegrityViolation(ex)) {
        throw ex;
      }
      RawMaterialIntakeRecord concurrent =
          rawMaterialIntakeRepository
              .findByCompanyAndIdempotencyKey(company, normalizedKey)
              .orElseThrow(() -> ex);
      assertIdempotencyMatch(concurrent, signature, normalizedKey);
      return resolveExistingBatch(concurrent, normalizedKey);
    }
  }

  @Transactional
  public ReceiptResult recordReceipt(
      Long rawMaterialId, RawMaterialBatchRequest request, ReceiptContext context) {
    RawMaterial material = requireMaterial(rawMaterialId);
    BigDecimal quantity = ValidationUtils.requirePositive(request.quantity(), "quantity");
    BigDecimal costPerUnit = ValidationUtils.requirePositive(request.costPerUnit(), "costPerUnit");
    boolean postingRequired = context == null || context.postJournal();
    Supplier supplier = requireSupplier(material.getCompany(), request.supplierId());
    ensureReceiptAccounts(material, supplier, postingRequired);
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
    batch.setManufacturedAt(
        resolveManufacturedAt(material.getCompany(), request.manufacturingDate()));
    batch.setExpiryDate(request.expiryDate());
    BigDecimal currentStock =
        material.getCurrentStock() == null ? BigDecimal.ZERO : material.getCurrentStock();
    material.setCurrentStock(currentStock.add(quantity));
    rawMaterialRepository.save(material);
    ReceiptContext effectiveContext =
        context != null ? context : ReceiptContext.forBatch(batchCode);
    batch.setSource(resolveBatchSource(effectiveContext.referenceType()));
    RawMaterialBatch savedBatch = batchRepository.save(batch);
    RawMaterialMovement receiptMovement =
        recordReceiptMovement(material, savedBatch, quantity, costPerUnit, effectiveContext);
    Long journalEntryId =
        effectiveContext.postJournal()
            ? postInventoryReceipt(
                material, supplier, savedBatch, quantity, costPerUnit, effectiveContext)
            : null;
    if (journalEntryId != null && receiptMovement != null) {
      receiptMovement.setJournalEntryId(journalEntryId);
      movementRepository.save(receiptMovement);
    }
    logGoodsReceiptAuditEvent(
        material, supplier, receiptMovement, effectiveContext, journalEntryId);
    return new ReceiptResult(savedBatch, receiptMovement, journalEntryId);
  }

  public RawMaterialAdjustmentDto adjustStock(RawMaterialAdjustmentRequest request) {
    if (request == null || request.lines() == null || request.lines().isEmpty()) {
      throw ValidationUtils.invalidInput("Raw material adjustment lines are required");
    }
    Company company = companyContextService.requireCurrentCompany();
    String idempotencyKey =
        requireIdempotencyKey(request.idempotencyKey(), "raw material adjustment");
    LocalDate adjustmentDate =
        request.adjustmentDate() != null ? request.adjustmentDate() : companyClock.today(company);
    List<RawMaterialAdjustmentRequest.LineRequest> sortedLines =
        request.lines().stream()
            .sorted(Comparator.comparing(RawMaterialAdjustmentRequest.LineRequest::rawMaterialId))
            .toList();

    String signature = buildRawMaterialAdjustmentSignature(request, sortedLines, adjustmentDate);
    RawMaterialAdjustment existing =
        rawMaterialAdjustmentRepository
            .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      idempotencyReservationService.assertAndRepairSignature(
          existing,
          idempotencyKey,
          signature,
          RawMaterialAdjustment::getIdempotencyHash,
          RawMaterialAdjustment::setIdempotencyHash,
          rawMaterialAdjustmentRepository::save);
      return toAdjustmentDto(existing);
    }

    try {
      RawMaterialAdjustmentDto created =
          transactionTemplate.execute(
              status ->
                  createRawMaterialAdjustmentInternal(
                      company, sortedLines, request, adjustmentDate, idempotencyKey, signature));
      if (created == null) {
        throw ValidationUtils.invalidState("Raw material adjustment failed to persist");
      }
      return created;
    } catch (RuntimeException ex) {
      if (!idempotencyReservationService.isDataIntegrityViolation(ex)) {
        throw ex;
      }
      RawMaterialAdjustment concurrent =
          rawMaterialAdjustmentRepository
              .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
              .orElseThrow(() -> ex);
      idempotencyReservationService.assertAndRepairSignature(
          concurrent,
          idempotencyKey,
          signature,
          RawMaterialAdjustment::getIdempotencyHash,
          RawMaterialAdjustment::setIdempotencyHash,
          rawMaterialAdjustmentRepository::save);
      return toAdjustmentDto(concurrent);
    }
  }

  private RawMaterialAdjustmentDto createRawMaterialAdjustmentInternal(
      Company company,
      List<RawMaterialAdjustmentRequest.LineRequest> sortedLines,
      RawMaterialAdjustmentRequest request,
      LocalDate adjustmentDate,
      String idempotencyKey,
      String signature) {
    List<Long> materialIds =
        sortedLines.stream().map(RawMaterialAdjustmentRequest.LineRequest::rawMaterialId).toList();
    if (materialIds.stream().anyMatch(id -> id == null)) {
      throw ValidationUtils.invalidInput("Raw material not found");
    }
    List<Long> uniqueMaterialIds = materialIds.stream().distinct().toList();
    List<RawMaterial> lockedMaterials =
        uniqueMaterialIds.stream()
            .map(
                id -> {
                  try {
                    return inventoryLookupService.lockActiveRawMaterial(company, id);
                  } catch (IllegalArgumentException ex) {
                    throw ValidationUtils.invalidInput("Raw material not found");
                  }
                })
            .toList();
    Map<Long, RawMaterial> materialsById = new HashMap<>();
    lockedMaterials.forEach(material -> materialsById.put(material.getId(), material));

    if (request.direction() == null) {
      throw ValidationUtils.invalidInput("Adjustment direction is required");
    }
    boolean increaseInventory =
        request.direction() == RawMaterialAdjustmentRequest.AdjustmentDirection.INCREASE;

    RawMaterialAdjustment adjustment = new RawMaterialAdjustment();
    adjustment.setCompany(company);
    adjustment.setReferenceNumber(referenceNumberService.rawMaterialAdjustmentReference(company));
    adjustment.setAdjustmentDate(adjustmentDate);
    adjustment.setReason(request.reason());
    adjustment.setCreatedBy(SecurityActorResolver.resolveActorWithSystemProcessFallback());
    adjustment.setIdempotencyKey(idempotencyKey);
    adjustment.setIdempotencyHash(signature);

    Map<Long, BigDecimal> inventoryLines = new HashMap<>();
    List<RawMaterialMovement> movements = new java.util.ArrayList<>();
    BigDecimal totalAmount = BigDecimal.ZERO;

    for (RawMaterialAdjustmentRequest.LineRequest lineRequest : sortedLines) {
      RawMaterial material = materialsById.get(lineRequest.rawMaterialId());
      if (material == null) {
        throw ValidationUtils.invalidInput("Raw material not found");
      }

      BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "quantity");
      BigDecimal unitCost = ValidationUtils.requirePositive(lineRequest.unitCost(), "unitCost");
      BigDecimal currentStock =
          material.getCurrentStock() == null ? BigDecimal.ZERO : material.getCurrentStock();
      if (!increaseInventory && currentStock.compareTo(quantity) < 0) {
        throw ValidationUtils.invalidInput(
            "Insufficient stock for raw material " + material.getSku());
      }

      BigDecimal delta = increaseInventory ? quantity : quantity.negate();
      BigDecimal amount = quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
      BigDecimal physicalQuantity = currentStock.add(delta);
      material.setCurrentStock(physicalQuantity);
      rawMaterialRepository.save(material);
      recordRawMaterialPhysicalCount(
          company,
          material,
          physicalQuantity,
          adjustmentDate,
          adjustment.getReferenceNumber(),
          lineRequest.note());

      RawMaterialAdjustmentLine line = new RawMaterialAdjustmentLine();
      line.setAdjustment(adjustment);
      line.setRawMaterial(material);
      line.setQuantity(delta);
      line.setUnitCost(unitCost);
      line.setAmount(amount);
      line.setNote(lineRequest.note());
      adjustment.addLine(line);

      Long inventoryAccountId = material.getInventoryAccountId();
      if (inventoryAccountId == null && company.getDefaultInventoryAccountId() != null) {
        inventoryAccountId = company.getDefaultInventoryAccountId();
        material.setInventoryAccountId(inventoryAccountId);
      }
      if (inventoryAccountId == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_REFERENCE,
            "Raw material " + material.getSku() + " is missing an inventory account");
      }
      inventoryLines.merge(inventoryAccountId, amount, BigDecimal::add);
      totalAmount = totalAmount.add(amount);

      if (delta.compareTo(BigDecimal.ZERO) > 0) {
        RawMaterialMovement movement = new RawMaterialMovement();
        movement.setRawMaterial(material);
        movement.setReferenceType(InventoryReference.RAW_MATERIAL_ADJUSTMENT);
        movement.setReferenceId(adjustment.getReferenceNumber());
        movement.setMovementType("ADJUSTMENT_IN");
        movement.setQuantity(delta.abs());
        movement.setUnitCost(unitCost);
        movements.add(movement);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        batch.setBatchCode(nextUniqueBatchCode(material));
        batch.setQuantity(quantity);
        batch.setUnit(material.getUnitType() != null ? material.getUnitType() : "UNIT");
        batch.setCostPerUnit(unitCost);
        batch.setSource(InventoryBatchSource.ADJUSTMENT);
        batch.setNotes("Stock recount positive adjustment " + adjustment.getReferenceNumber());
        batch = batchRepository.saveAndFlush(batch);
        movement.setRawMaterialBatch(batch);
      } else {
        issueFromRawMaterialBatches(
            material, quantity, unitCost, adjustment.getReferenceNumber(), movements);
      }
    }

    if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw ValidationUtils.invalidInput("Adjustment amount must be greater than zero");
    }

    adjustment.setTotalAmount(totalAmount);
    RawMaterialAdjustment saved = rawMaterialAdjustmentRepository.saveAndFlush(adjustment);

    JournalEntryDto journalEntry =
        accountingFacade.postInventoryAdjustment(
            "RAW_MATERIAL_ADJUSTMENT",
            saved.getReferenceNumber(),
            request.adjustmentAccountId(),
            Map.copyOf(inventoryLines),
            increaseInventory,
            Boolean.TRUE.equals(request.adminOverride()),
            memoForRawMaterialAdjustment(saved),
            saved.getAdjustmentDate());

    if (journalEntry == null) {
      throw ValidationUtils.invalidState("Raw material adjustment journal was not created");
    }

    saved.setJournalEntryId(journalEntry.id());
    saved.setStatus("POSTED");
    movements.forEach(movement -> movement.setJournalEntryId(journalEntry.id()));
    movementRepository.saveAll(movements);
    RawMaterialAdjustment posted = rawMaterialAdjustmentRepository.save(saved);
    logInventoryAdjustmentAuditEvent(posted, increaseInventory);

    return toAdjustmentDto(posted);
  }

  private void issueFromRawMaterialBatches(
      RawMaterial material,
      BigDecimal quantity,
      BigDecimal unitCost,
      String referenceNumber,
      List<RawMaterialMovement> movements) {
    BigDecimal remaining = quantity;
    List<RawMaterialBatch> batches = batchRepository.findAvailableBatchesFIFO(material);
    for (RawMaterialBatch batch : batches) {
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      BigDecimal available = batch.getQuantity() != null ? batch.getQuantity() : BigDecimal.ZERO;
      if (available.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal take = available.min(remaining);
      if (take.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      int updated = batchRepository.deductQuantityIfSufficient(batch.getId(), take);
      if (updated == 0) {
        throw ValidationUtils.invalidInput(
            "Concurrent modification detected or insufficient quantity for batch "
                + batch.getBatchCode());
      }

      RawMaterialMovement movement = new RawMaterialMovement();
      movement.setRawMaterial(material);
      movement.setRawMaterialBatch(batchRepository.findById(batch.getId()).orElse(batch));
      movement.setReferenceType(InventoryReference.RAW_MATERIAL_ADJUSTMENT);
      movement.setReferenceId(referenceNumber);
      movement.setMovementType("ADJUSTMENT_OUT");
      movement.setQuantity(take);
      movement.setUnitCost(unitCost);
      movements.add(movement);
      remaining = remaining.subtract(take);
    }

    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      throw ValidationUtils.invalidInput(
          "Insufficient batch availability for " + material.getSku());
    }
  }

  private RawMaterialAdjustmentDto toAdjustmentDto(RawMaterialAdjustment adjustment) {
    List<RawMaterialAdjustmentLineDto> lines =
        adjustment.getLines().stream()
            .map(
                line ->
                    new RawMaterialAdjustmentLineDto(
                        line.getRawMaterial().getId(),
                        line.getRawMaterial().getName(),
                        line.getQuantity(),
                        line.getUnitCost(),
                        line.getAmount(),
                        line.getNote()))
            .toList();

    return new RawMaterialAdjustmentDto(
        adjustment.getId(),
        adjustment.getPublicId(),
        adjustment.getReferenceNumber(),
        adjustment.getAdjustmentDate(),
        adjustment.getStatus(),
        adjustment.getReason(),
        adjustment.getTotalAmount(),
        adjustment.getJournalEntryId(),
        lines);
  }

  private String buildRawMaterialAdjustmentSignature(
      RawMaterialAdjustmentRequest request,
      List<RawMaterialAdjustmentRequest.LineRequest> sortedLines,
      LocalDate adjustmentDate) {
    IdempotencySignatureBuilder builder =
        IdempotencySignatureBuilder.create()
            .add(adjustmentDate != null ? adjustmentDate : "")
            .add(request.direction() != null ? request.direction().name() : "")
            .add(request.adjustmentAccountId() != null ? request.adjustmentAccountId() : "")
            .addToken(request.reason())
            .add(Boolean.TRUE.equals(request.adminOverride()));

    for (RawMaterialAdjustmentRequest.LineRequest line : sortedLines) {
      builder.add(
          (line.rawMaterialId() != null ? line.rawMaterialId() : "")
              + ":"
              + com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.normalizeAmount(
                  line.quantity())
              + ":"
              + com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.normalizeAmount(
                  line.unitCost())
              + ":"
              + com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.normalizeToken(
                  line.note()));
    }
    return builder.buildHash();
  }

  private String memoForRawMaterialAdjustment(RawMaterialAdjustment adjustment) {
    String suffix =
        StringUtils.hasText(adjustment.getReason())
            ? adjustment.getReason().trim()
            : adjustment.getReferenceNumber();
    return "Raw material adjustment - " + suffix;
  }

  private RawMaterial requireMaterial(Long rawMaterialId) {
    // This method is used by write flows (receipts/intake/adjustments). Keep locking semantics.
    Company company = companyContextService.requireCurrentCompany();
    try {
      return inventoryLookupService.lockActiveRawMaterial(company, rawMaterialId);
    } catch (IllegalArgumentException ex) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Raw material not found");
    }
  }

  private RawMaterialDto toDto(RawMaterial material) {
    return new RawMaterialDto(
        material.getId(),
        material.getPublicId(),
        material.getName(),
        material.getSku(),
        material.getUnitType(),
        material.getReorderLevel(),
        material.getCurrentStock(),
        material.getMinStock(),
        material.getMaxStock(),
        stockStatus(material),
        material.getInventoryAccountId(),
        material.getCostingMethod(),
        material.getMaterialType() != null ? material.getMaterialType().name() : null);
  }

  private MaterialType resolveMaterialType(String rawMaterialType) {
    if (!StringUtils.hasText(rawMaterialType)) {
      return MaterialType.PRODUCTION;
    }
    String normalized =
        rawMaterialType.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PACKAGING", "PACKAGING_RAW_MATERIAL", "PKG" -> MaterialType.PACKAGING;
      case "PRODUCTION", "RAW_MATERIAL", "RM" -> MaterialType.PRODUCTION;
      default -> throw ValidationUtils.invalidInput("Unsupported materialType: " + rawMaterialType);
    };
  }

  private RawMaterialBatchDto toBatchDto(RawMaterialBatch batch) {
    Supplier supplier = batch.getSupplier();
    return new RawMaterialBatchDto(
        batch.getId(),
        batch.getPublicId(),
        batch.getBatchCode(),
        batch.getQuantity(),
        batch.getUnit(),
        batch.getCostPerUnit(),
        supplier != null ? supplier.getId() : null,
        batch.getSupplierName(),
        batch.getReceivedAt(),
        batch.getNotes());
  }

  private InventoryStockSnapshot toSnapshot(RawMaterial material) {
    return new InventoryStockSnapshot(
        material.getId(),
        material.getName(),
        material.getSku(),
        safeQuantity(material.getCurrentStock()),
        safeQuantity(material.getReorderLevel()),
        safeQuantity(material.getMinStock()),
        safeQuantity(material.getMaxStock()),
        stockStatus(material));
  }

  private void recordRawMaterialPhysicalCount(
      Company company,
      RawMaterial material,
      BigDecimal physicalQuantity,
      LocalDate countDate,
      String sourceReference,
      String note) {
    if (inventoryPhysicalCountService == null
        || company == null
        || material == null
        || material.getId() == null) {
      return;
    }
    inventoryPhysicalCountService.recordRawMaterialCount(
        company,
        material.getId(),
        safeQuantity(physicalQuantity),
        countDate,
        sourceReference,
        note);
  }

  private BigDecimal safeQuantity(BigDecimal quantity) {
    return quantity == null ? BigDecimal.ZERO : quantity;
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

  private RawMaterialMovement recordReceiptMovement(
      RawMaterial material,
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
    String referenceType =
        context != null && StringUtils.hasText(context.referenceType())
            ? context.referenceType()
            : InventoryReference.RAW_MATERIAL_PURCHASE;
    String referenceId =
        context != null && StringUtils.hasText(context.referenceId())
            ? context.referenceId()
            : batch.getBatchCode();
    movement.setReferenceType(referenceType);
    movement.setReferenceId(referenceId);
    movement.setMovementType("RECEIPT");
    movement.setQuantity(normalizedQty);
    movement.setUnitCost(costPerUnit == null ? BigDecimal.ZERO : costPerUnit);
    return movementRepository.save(movement);
  }

  private Long postInventoryReceipt(
      RawMaterial material,
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
    String memo =
        context != null && StringUtils.hasText(context.memo())
            ? context.memo()
            : "Raw material batch " + batch.getBatchCode();
    JournalEntryDto entry =
        accountingFacade.postPurchaseJournal(
            supplier.getId(),
            batch.getBatchCode(),
            companyClock.today(material.getCompany()),
            memo,
            Map.of(inventoryAccountId, totalCost),
            totalCost);
    return entry != null ? entry.id() : null;
  }

  private LocalDate currentDate(Company company) {
    return companyClock.today(company);
  }

  private void syncProductFromMaterial(Company company, RawMaterial material) {
    if (!StringUtils.hasText(material.getSku())) {
      return;
    }
    ProductionProduct product =
        productionProductRepository
            .findByCompanyAndSkuCode(company, material.getSku())
            .orElseGet(
                () -> {
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
    Map<String, Object> metadata =
        product.getMetadata() == null ? new HashMap<>() : new HashMap<>(product.getMetadata());
    metadata.put("linkedRawMaterialId", material.getId());
    metadata.put("linkedRawMaterialSku", material.getSku());
    product.setMetadata(metadata);
    productionProductRepository.save(product);
  }

  private ProductionBrand resolveRawMaterialBrand(Company company) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, "RAW-MATERIALS")
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode("RAW-MATERIALS");
              brand.setName("Raw Materials");
              return productionBrandRepository.save(brand);
            });
  }

  private Supplier requireSupplier(Company company, Long supplierId) {
    return purchasingLookupService.requireSupplier(company, supplierId);
  }

  private void ensureReceiptAccounts(
      RawMaterial material, Supplier supplier, boolean postingRequired) {
    if (material.getInventoryAccountId() == null && material.getCompany() != null) {
      // Try company default inventory account before failing
      material.setInventoryAccountId(material.getCompany().getDefaultInventoryAccountId());
    }
    if (material.getInventoryAccountId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Raw material " + material.getName() + " is missing an inventory account");
    }
    if (postingRequired && supplier.getPayableAccount() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier " + supplier.getName() + " is missing a payable account");
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
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
            "Unable to allocate unique batch code for raw material " + describeMaterial(material));
      }
      candidate = batchNumberService.nextRawMaterialBatchCode(material);
    }
    return candidate;
  }

  private void ensureBatchCodeUnique(RawMaterial material, String batchCode) {
    if (batchRepository.existsByRawMaterialAndBatchCode(material, batchCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Batch code already exists for raw material "
              + describeMaterial(material)
              + ": "
              + batchCode);
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

  private RawMaterialBatchDto createManualIntakeInternal(
      Company company,
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

  private RawMaterialBatchDto resolveExistingBatch(
      RawMaterialIntakeRecord record, String idempotencyKey) {
    Long batchId = record.getRawMaterialBatchId();
    if (batchId == null) {
      throw new ApplicationException(
              ErrorCode.CONCURRENCY_CONFLICT,
              "Idempotency key already used but batch record is missing")
          .withDetail("idempotencyKey", idempotencyKey);
    }
    RawMaterialBatch batch =
        batchRepository
            .findById(batchId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                        "Raw material batch not found for idempotency key"));
    return toBatchDto(batch);
  }

  private void assertIdempotencyMatch(
      RawMaterialIntakeRecord record, String expectedSignature, String idempotencyKey) {
    idempotencyReservationService.assertAndRepairSignature(
        record,
        idempotencyKey,
        expectedSignature,
        RawMaterialIntakeRecord::getIdempotencyHash,
        RawMaterialIntakeRecord::setIdempotencyHash,
        rawMaterialIntakeRepository::save);
  }

  private String requireIdempotencyKey(String idempotencyKey, String label) {
    return idempotencyReservationService.requireKey(idempotencyKey, label);
  }

  private String buildManualIntakeSignature(Long rawMaterialId, RawMaterialBatchRequest request) {
    IdempotencySignatureBuilder signature =
        IdempotencySignatureBuilder.create().add(rawMaterialId != null ? rawMaterialId : "");
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
    String timezone =
        company != null && StringUtils.hasText(company.getTimezone())
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
              InventoryReference.MANUFACTURING_ORDER ->
          InventoryBatchSource.PRODUCTION;
      default -> InventoryBatchSource.PURCHASE;
    };
  }

  private boolean isProdProfile() {
    return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
  }

  private void logInventoryAdjustmentAuditEvent(
      RawMaterialAdjustment adjustment, boolean increaseInventory) {
    if (adjustment == null
        || adjustment.getCompany() == null
        || adjustment.getCompany().getId() == null) {
      return;
    }
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "INVENTORY");
    metadata.put("referenceType", InventoryReference.RAW_MATERIAL_ADJUSTMENT);
    metadata.put("adjustmentDirection", increaseInventory ? "INCREASE" : "DECREASE");
    if (adjustment.getReferenceNumber() != null) {
      metadata.put("referenceNumber", adjustment.getReferenceNumber());
    }
    if (adjustment.getId() != null) {
      metadata.put("adjustmentId", adjustment.getId().toString());
    }
    if (adjustment.getJournalEntryId() != null) {
      metadata.put("journalEntryId", adjustment.getJournalEntryId().toString());
    }
    if (adjustment.getTotalAmount() != null) {
      metadata.put("totalAmount", adjustment.getTotalAmount().toPlainString());
    }
    if (adjustment.getLines() != null) {
      metadata.put("lineCount", Integer.toString(adjustment.getLines().size()));
    }
    auditService.logSuccess(AuditEvent.INVENTORY_ADJUSTMENT, metadata);
  }

  private void logGoodsReceiptAuditEvent(
      RawMaterial material,
      Supplier supplier,
      RawMaterialMovement movement,
      ReceiptContext context,
      Long journalEntryId) {
    if (material == null
        || material.getCompany() == null
        || material.getCompany().getId() == null
        || context == null
        || !InventoryReference.GOODS_RECEIPT.equalsIgnoreCase(context.referenceType())) {
      return;
    }
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "INVENTORY");
    metadata.put("referenceType", InventoryReference.GOODS_RECEIPT);
    if (context.referenceId() != null) {
      metadata.put("referenceNumber", context.referenceId());
      metadata.put("goodsReceiptNumber", context.referenceId());
    }
    if (material.getId() != null) {
      metadata.put("rawMaterialId", material.getId().toString());
    }
    if (supplier != null && supplier.getId() != null) {
      metadata.put("supplierId", supplier.getId().toString());
    }
    if (movement != null && movement.getId() != null) {
      metadata.put("rawMaterialMovementId", movement.getId().toString());
    }
    if (journalEntryId != null) {
      metadata.put("journalEntryId", journalEntryId.toString());
    }
    auditService.logSuccess(AuditEvent.GOODS_RECEIPT, metadata);
  }

  public record ReceiptContext(
      String referenceType, String referenceId, String memo, boolean postJournal) {
    public static ReceiptContext forBatch(String batchCode) {
      return new ReceiptContext(
          InventoryReference.RAW_MATERIAL_PURCHASE,
          batchCode,
          "Raw material batch " + batchCode,
          true);
    }
  }

  public record ReceiptResult(
      RawMaterialBatch batch, RawMaterialMovement movement, Long journalEntryId) {}
}
