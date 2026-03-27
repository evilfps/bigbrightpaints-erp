package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryBatchMovementDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryBatchTraceabilityDto;

@Service
public class InventoryBatchTraceabilityService {

  private final CompanyContextService companyContextService;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;

  public InventoryBatchTraceabilityService(
      CompanyContextService companyContextService,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      InventoryMovementRepository inventoryMovementRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository) {
    this.companyContextService = companyContextService;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
  }

  public InventoryBatchTraceabilityDto getBatchMovementHistory(Long batchId, String batchType) {
    if (batchId == null || batchId <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Batch id must be positive");
    }
    Company company = companyContextService.requireCurrentCompany();
    RequestedBatchType requestedType = RequestedBatchType.parse(batchType);

    Optional<FinishedGoodBatch> finished =
        requestedType != RequestedBatchType.RAW_MATERIAL
            ? finishedGoodBatchRepository.findByFinishedGood_CompanyAndId(company, batchId)
            : Optional.empty();
    Optional<RawMaterialBatch> raw =
        requestedType != RequestedBatchType.FINISHED_GOOD
            ? rawMaterialBatchRepository.findByRawMaterial_CompanyAndId(company, batchId)
            : Optional.empty();

    if (requestedType == RequestedBatchType.AUTO && finished.isPresent() && raw.isPresent()) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Batch id is ambiguous across raw material and finished good batches; specify"
                  + " batchType")
          .withDetail("batchId", batchId)
          .withDetail("supportedBatchTypes", "RAW_MATERIAL,FINISHED_GOOD");
    }

    if (finished.isPresent()) {
      return toFinishedBatchDto(finished.get());
    }
    if (raw.isPresent()) {
      return toRawMaterialBatchDto(raw.get());
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "Batch not found: " + batchId);
  }

  private InventoryBatchTraceabilityDto toFinishedBatchDto(FinishedGoodBatch batch) {
    List<InventoryBatchMovementDto> movements =
        inventoryMovementRepository.findByFinishedGoodBatchOrderByCreatedAtAsc(batch).stream()
            .map(
                movement ->
                    toMovementDto(
                        movement.getId(),
                        movement.getMovementType(),
                        movement.getQuantity(),
                        movement.getUnitCost(),
                        movement.getCreatedAt(),
                        resolveMovementSource(
                            movement.getReferenceType(),
                            movement.getMovementType(),
                            batch.getSource()),
                        movement.getReferenceType(),
                        movement.getReferenceId(),
                        movement.getJournalEntryId(),
                        movement.getPackingSlipId()))
            .toList();

    return new InventoryBatchTraceabilityDto(
        batch.getId(),
        batch.getPublicId(),
        "FINISHED_GOOD",
        batch.getFinishedGood().getProductCode(),
        batch.getFinishedGood().getName(),
        batch.getBatchCode(),
        batch.getManufacturedAt(),
        batch.getExpiryDate(),
        safe(batch.getQuantityTotal()),
        safe(batch.getQuantityAvailable()),
        safe(batch.getUnitCost()),
        normalizeSource(batch.getSource()),
        movements);
  }

  private InventoryBatchTraceabilityDto toRawMaterialBatchDto(RawMaterialBatch batch) {
    List<InventoryBatchMovementDto> movements =
        rawMaterialMovementRepository.findByRawMaterialBatchOrderByCreatedAtAsc(batch).stream()
            .map(
                movement ->
                    toMovementDto(
                        movement.getId(),
                        movement.getMovementType(),
                        movement.getQuantity(),
                        movement.getUnitCost(),
                        movement.getCreatedAt(),
                        resolveMovementSource(
                            movement.getReferenceType(),
                            movement.getMovementType(),
                            batch.getSource()),
                        movement.getReferenceType(),
                        movement.getReferenceId(),
                        movement.getJournalEntryId(),
                        null))
            .toList();

    return new InventoryBatchTraceabilityDto(
        batch.getId(),
        batch.getPublicId(),
        "RAW_MATERIAL",
        batch.getRawMaterial().getSku(),
        batch.getRawMaterial().getName(),
        batch.getBatchCode(),
        batch.getManufacturedAt(),
        batch.getExpiryDate(),
        safe(batch.getQuantity()),
        safe(batch.getQuantity()),
        safe(batch.getCostPerUnit()),
        normalizeSource(batch.getSource()),
        movements);
  }

  private InventoryBatchMovementDto toMovementDto(
      Long id,
      String movementType,
      BigDecimal quantity,
      BigDecimal unitCost,
      java.time.Instant createdAt,
      String source,
      String referenceType,
      String referenceId,
      Long journalEntryId,
      Long packingSlipId) {
    BigDecimal safeQuantity = safe(quantity);
    BigDecimal safeCost = safe(unitCost);
    return new InventoryBatchMovementDto(
        id,
        movementType,
        safeQuantity,
        safeCost,
        safeQuantity.multiply(safeCost),
        createdAt,
        source,
        referenceType,
        referenceId,
        journalEntryId,
        packingSlipId);
  }

  private String resolveMovementSource(
      String referenceType, String movementType, InventoryBatchSource fallbackSource) {
    if (StringUtils.hasText(referenceType)) {
      String normalized = referenceType.trim().toUpperCase(Locale.ROOT);
      return switch (normalized) {
        case InventoryReference.PRODUCTION_LOG,
                InventoryReference.MANUFACTURING_ORDER,
                InventoryReference.PACKING_RECORD ->
            "production";
        case InventoryReference.RAW_MATERIAL_PURCHASE, InventoryReference.GOODS_RECEIPT ->
            "purchase";
        case InventoryReference.OPENING_STOCK,
                InventoryReference.PURCHASE_RETURN,
                "ADJUSTMENT",
                "SALES_RETURN" ->
            "adjustment";
        default -> resolveFromMovementType(movementType, fallbackSource);
      };
    }
    return resolveFromMovementType(movementType, fallbackSource);
  }

  private String resolveFromMovementType(String movementType, InventoryBatchSource fallbackSource) {
    if (StringUtils.hasText(movementType)) {
      String normalizedMovementType = movementType.trim().toUpperCase(Locale.ROOT);
      if (normalizedMovementType.contains("ADJUST")
          || normalizedMovementType.contains("RETURN")
          || normalizedMovementType.contains("WASTAGE")) {
        return "adjustment";
      }
    }
    return normalizeSource(fallbackSource);
  }

  private String normalizeSource(InventoryBatchSource source) {
    InventoryBatchSource resolved = source == null ? InventoryBatchSource.ADJUSTMENT : source;
    return resolved.name().toLowerCase(Locale.ROOT);
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private enum RequestedBatchType {
    AUTO,
    RAW_MATERIAL,
    FINISHED_GOOD;

    private static RequestedBatchType parse(String raw) {
      if (!StringUtils.hasText(raw)) {
        return AUTO;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      return switch (normalized) {
        case "RAW_MATERIAL", "RAW", "RM" -> RAW_MATERIAL;
        case "FINISHED_GOOD", "FINISHED", "FG" -> FINISHED_GOOD;
        default ->
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                "Unsupported batchType: " + raw + " (expected RAW_MATERIAL or FINISHED_GOOD)");
      };
    }
  }
}
