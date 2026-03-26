package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;

@Service
public class PackagingMaterialService {

  private final CompanyContextService companyContextService;
  private final CompanyEntityLookup companyEntityLookup;
  private final PackagingSizeMappingRepository mappingRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;

  public PackagingMaterialService(
      CompanyContextService companyContextService,
      CompanyEntityLookup companyEntityLookup,
      PackagingSizeMappingRepository mappingRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository) {
    this.companyContextService = companyContextService;
    this.companyEntityLookup = companyEntityLookup;
    this.mappingRepository = mappingRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
  }

  public List<PackagingSizeMappingDto> listMappings() {
    Company company = companyContextService.requireCurrentCompany();
    return mappingRepository.findByCompanyOrderByPackagingSizeAsc(company).stream()
        .map(this::toDto)
        .toList();
  }

  public List<PackagingSizeMappingDto> listActiveMappings() {
    Company company = companyContextService.requireCurrentCompany();
    return mappingRepository.findByCompanyAndActiveOrderByPackagingSizeAsc(company, true).stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public PackagingSizeMappingDto createMapping(PackagingSizeMappingRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String normalizedSize = normalizePackagingSize(request.packagingSize());

    RawMaterial rawMaterial =
        requireActivePackagingRawMaterial(company, request.rawMaterialId(), false);
    requirePackagingMaterial(rawMaterial);

    if (mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
        company, normalizedSize, rawMaterial)) {
      throw duplicateMapping(normalizedSize, rawMaterial);
    }

    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(normalizedSize);
    mapping.setRawMaterial(rawMaterial);
    mapping.setUnitsPerPack(request.unitsPerPack());
    mapping.setCartonSize(request.cartonSize());
    mapping.setLitersPerUnit(resolveLitersPerUnit(request.litersPerUnit(), normalizedSize));
    mapping.setActive(true);

    return toDto(mappingRepository.save(mapping));
  }

  @Transactional
  public PackagingSizeMappingDto updateMapping(Long id, PackagingSizeMappingRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    PackagingSizeMapping mapping =
        mappingRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Mapping not found"));

    String normalizedSize = normalizePackagingSize(request.packagingSize());
    RawMaterial rawMaterial =
        requireActivePackagingRawMaterial(company, request.rawMaterialId(), false);
    requirePackagingMaterial(rawMaterial);

    if (mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterialAndIdNot(
        company, normalizedSize, rawMaterial, mapping.getId())) {
      throw duplicateMapping(normalizedSize, rawMaterial);
    }

    mapping.setPackagingSize(normalizedSize);
    mapping.setRawMaterial(rawMaterial);
    mapping.setUnitsPerPack(request.unitsPerPack());
    mapping.setCartonSize(request.cartonSize());
    mapping.setLitersPerUnit(resolveLitersPerUnit(request.litersPerUnit(), normalizedSize));

    return toDto(mappingRepository.save(mapping));
  }

  @Transactional
  public void deactivateMapping(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PackagingSizeMapping mapping =
        mappingRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Mapping not found"));
    mapping.setActive(false);
    mappingRepository.save(mapping);
  }

  /**
   * Consume packaging material (buckets) for a packing operation.
   *
   * @param packagingSize The packaging size (e.g., "1L", "5L", "10L")
   * @param piecesCount Number of pieces/buckets to consume
   * @param referenceId Reference ID for the movement (e.g., production code)
   * @return Consumption result with cost and material details
   */
  @Transactional
  public PackagingConsumptionResult consumePackagingMaterial(
      String packagingSize, int piecesCount, String referenceId) {
    return consumePackagingMaterial(packagingSize, piecesCount, referenceId, null, null);
  }

  @Transactional
  public PackagingConsumptionResult consumePackagingMaterial(
      String packagingSize,
      int piecesCount,
      String referenceId,
      SizeVariant sizeVariant,
      PackingRecord packingRecord) {

    Company company = companyContextService.requireCurrentCompany();
    String normalizedSize = normalizePackagingSize(packagingSize);

    List<PackagingSizeMapping> mappings =
        mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, normalizedSize);

    if (mappings.isEmpty()) {
      throw missingPackagingSetup(normalizedSize);
    }

    BigDecimal totalCost = BigDecimal.ZERO;
    BigDecimal totalQuantity = BigDecimal.ZERO;
    Map<Long, BigDecimal> accountTotals = new HashMap<>();
    List<RawMaterial> consumedMaterials = new ArrayList<>();

    for (PackagingSizeMapping mapping : mappings) {
      RawMaterial material = requirePackagingSetupRawMaterial(company, mapping, normalizedSize);
      requirePackagingSetupMaterial(normalizedSize, material);

      if (material.getInventoryAccountId() == null) {
        throw invalidPackagingSetupReference(
            normalizedSize,
            "points to packaging material "
                + describeRawMaterial(material)
                + " without an inventory account");
      }

      int unitsPerPack = mapping.getUnitsPerPack() != null ? mapping.getUnitsPerPack() : 1;
      BigDecimal requiredQty =
          BigDecimal.valueOf(piecesCount).multiply(BigDecimal.valueOf(unitsPerPack));

      if (material.getCurrentStock().compareTo(requiredQty) < 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            String.format(
                "Insufficient %s. Need: %s, Available: %s",
                material.getSku(), requiredQty, material.getCurrentStock()));
      }

      BigDecimal consumedCost = issueFromBatches(material, requiredQty, referenceId, packingRecord);
      material.setCurrentStock(material.getCurrentStock().subtract(requiredQty));
      rawMaterialRepository.save(material);

      totalCost = totalCost.add(consumedCost);
      totalQuantity = totalQuantity.add(requiredQty);
      accountTotals.merge(material.getInventoryAccountId(), consumedCost, BigDecimal::add);
      consumedMaterials.add(material);
    }

    if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
      throw invalidPackagingSetupInput(normalizedSize, "resolved to zero packaging cost");
    }

    if (packingRecord != null && !consumedMaterials.isEmpty()) {
      packingRecord.setPackagingMaterial(consumedMaterials.getFirst());
    }
    if (sizeVariant != null && packingRecord != null) {
      packingRecord.setSizeVariant(sizeVariant);
    }

    return new PackagingConsumptionResult(true, totalCost, totalQuantity, accountTotals, null);
  }

  private BigDecimal issueFromBatches(
      RawMaterial rawMaterial,
      BigDecimal requiredQty,
      String referenceId,
      PackingRecord packingRecord) {
    List<RawMaterialBatch> batches =
        rawMaterialBatchRepository.findAvailableBatchesFIFO(rawMaterial);
    BigDecimal weightedAverageCost =
        CostingMethodUtils.selectWeightedAverageValue(
            rawMaterial.getCostingMethod(),
            () -> rawMaterialBatchRepository.calculateWeightedAverageCost(rawMaterial),
            () -> null);
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
      BigDecimal unitCost =
          weightedAverageCost != null
              ? weightedAverageCost
              : Optional.ofNullable(batch.getCostPerUnit()).orElse(BigDecimal.ZERO);
      BigDecimal movementCost = unitCost.multiply(take);

      // Atomic deduction
      int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), take);
      if (updated == 0) {
        throw new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Concurrent modification detected for packaging batch " + batch.getBatchCode());
      }

      // Record movement
      RawMaterialMovement movement = new RawMaterialMovement();
      movement.setRawMaterial(rawMaterial);
      movement.setRawMaterialBatch(batch);
      movement.setReferenceType(InventoryReference.PACKING_RECORD);
      movement.setReferenceId(referenceId);
      movement.setMovementType("ISSUE");
      movement.setQuantity(take);
      movement.setUnitCost(unitCost);
      movement.setPackingRecord(packingRecord);
      rawMaterialMovementRepository.save(movement);

      totalCost = totalCost.add(movementCost);
      remaining = remaining.subtract(take);
    }

    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Insufficient batch availability for packaging material " + rawMaterial.getSku());
    }

    return totalCost.setScale(2, RoundingMode.HALF_UP);
  }

  private String normalizePackagingSize(String size) {
    if (size == null || size.isBlank()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Packaging size is required");
    }
    return size.trim().toUpperCase();
  }

  private void requirePackagingMaterial(RawMaterial material) {
    if (material == null || material.getMaterialType() != MaterialType.PACKAGING) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material must be of type PACKAGING");
    }
  }

  private RawMaterial requirePackagingSetupRawMaterial(
      Company company, PackagingSizeMapping mapping, String normalizedSize) {
    Long rawMaterialId = mapping.getRawMaterial() != null ? mapping.getRawMaterial().getId() : null;
    if (rawMaterialId == null) {
      throw invalidPackagingSetupReference(
          normalizedSize, "does not reference a packaging material");
    }
    try {
      return companyEntityLookup.lockActiveRawMaterial(company, rawMaterialId);
    } catch (IllegalArgumentException ex) {
      throw invalidPackagingSetupReference(
          normalizedSize, "points to an inactive or missing packaging material");
    }
  }

  private void requirePackagingSetupMaterial(String normalizedSize, RawMaterial material) {
    if (material == null) {
      throw invalidPackagingSetupReference(
          normalizedSize,
          "points to raw material "
              + describeRawMaterial(null)
              + " that is not marked as PACKAGING");
    }
    if (material.getMaterialType() != MaterialType.PACKAGING) {
      throw invalidPackagingSetupReference(
          normalizedSize,
          "points to raw material "
              + describeRawMaterial(material)
              + " that is not marked as PACKAGING");
    }
  }

  private RawMaterial requireActivePackagingRawMaterial(
      Company company, Long rawMaterialId, boolean lock) {
    try {
      return lock
          ? companyEntityLookup.lockActiveRawMaterial(company, rawMaterialId)
          : companyEntityLookup.requireActiveRawMaterial(company, rawMaterialId);
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material not found", ex);
    }
  }

  private ApplicationException missingPackagingSetup(String normalizedSize) {
    return new ApplicationException(
        ErrorCode.VALIDATION_INVALID_INPUT,
        "Packaging Setup is required for size "
            + normalizedSize
            + ". Create or reactivate a Packaging Rule before packing.");
  }

  private ApplicationException invalidPackagingSetupReference(
      String normalizedSize, String detail) {
    return new ApplicationException(
        ErrorCode.VALIDATION_INVALID_REFERENCE,
        "Packaging Setup for size "
            + normalizedSize
            + ' '
            + detail
            + ". Update Packaging Rules before packing.");
  }

  private ApplicationException invalidPackagingSetupInput(String normalizedSize, String detail) {
    return new ApplicationException(
        ErrorCode.VALIDATION_INVALID_INPUT,
        "Packaging Setup for size "
            + normalizedSize
            + ' '
            + detail
            + ". Update Packaging Rules before packing.");
  }

  private String describeRawMaterial(RawMaterial material) {
    if (material == null) {
      return "unknown";
    }
    if (StringUtils.hasText(material.getSku())) {
      return material.getSku().trim();
    }
    return material.getId() != null ? "#" + material.getId() : "unknown";
  }

  private BigDecimal resolveLitersPerUnit(BigDecimal litersPerUnit, String normalizedSize) {
    if (litersPerUnit != null) {
      return litersPerUnit;
    }
    BigDecimal parsed = PackagingSizeParser.parseSizeInLitersAllowBareNumber(normalizedSize);
    if (parsed == null) {
      return BigDecimal.ONE;
    }
    if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ONE;
    }
    return parsed;
  }

  private ApplicationException duplicateMapping(String normalizedSize, RawMaterial rawMaterial) {
    return new ApplicationException(
        ErrorCode.DUPLICATE_ENTITY,
        "Packaging size mapping already exists for: "
            + normalizedSize
            + " and "
            + rawMaterial.getSku());
  }

  private PackagingSizeMappingDto toDto(PackagingSizeMapping mapping) {
    RawMaterial rm = mapping.getRawMaterial();
    return new PackagingSizeMappingDto(
        mapping.getId(),
        mapping.getPublicId(),
        mapping.getPackagingSize(),
        rm.getId(),
        rm.getSku(),
        rm.getName(),
        mapping.getUnitsPerPack(),
        mapping.getCartonSize(),
        mapping.getLitersPerUnit(),
        mapping.isActive());
  }
}
