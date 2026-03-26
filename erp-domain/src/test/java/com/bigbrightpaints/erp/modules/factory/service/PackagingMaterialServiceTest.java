package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PackagingMaterialServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private PackagingSizeMappingRepository mappingRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;

  private PackagingMaterialService packagingMaterialService;
  private Company company;

  @BeforeEach
  void setUp() {
    packagingMaterialService =
        new PackagingMaterialService(
            companyContextService,
            companyEntityLookup,
            mappingRepository,
            rawMaterialRepository,
            rawMaterialBatchRepository,
            rawMaterialMovementRepository);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void createMapping_rejectsNonPackagingMaterial() {
    RawMaterial material = rawMaterial(15L, 900L, new BigDecimal("10"), null);
    material.setMaterialType(MaterialType.PRODUCTION);
    when(companyEntityLookup.requireActiveRawMaterial(company, 15L)).thenReturn(material);

    assertThatThrownBy(
            () ->
                packagingMaterialService.createMapping(
                    new PackagingSizeMappingRequest("1L", 15L, 1, 12, BigDecimal.ONE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(appEx.getMessage()).contains("Raw material must be of type PACKAGING");
            });
  }

  @Test
  void createMapping_rejectsMissingActiveRawMaterial() {
    when(companyEntityLookup.requireActiveRawMaterial(company, 99L))
        .thenThrow(new IllegalArgumentException("inactive"));

    assertThatThrownBy(
            () ->
                packagingMaterialService.createMapping(
                    new PackagingSizeMappingRequest("1L", 99L, 1, 12, BigDecimal.ONE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(appEx.getMessage()).contains("Raw material not found");
            });
  }

  @Test
  void createMapping_rejectsBlankPackagingSize() {
    assertThatThrownBy(
            () ->
                packagingMaterialService.createMapping(
                    new PackagingSizeMappingRequest("   ", 15L, 1, 12, BigDecimal.ONE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(appEx.getMessage()).contains("Packaging size is required");
            });
  }

  @Test
  void createMapping_rejectsDuplicateMapping() {
    RawMaterial material = rawMaterial(15L, 900L, new BigDecimal("10"), null);
    when(companyEntityLookup.requireActiveRawMaterial(company, 15L)).thenReturn(material);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
            company, "1L", material))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                packagingMaterialService.createMapping(
                    new PackagingSizeMappingRequest("1L", 15L, 1, 12, BigDecimal.ONE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ENTITY);
              assertThat(appEx.getMessage())
                  .contains("Packaging size mapping already exists for: 1L and PACK-15");
            });
  }

  @Test
  void createMapping_defaultsOptionalLitersPerUnitFromPackagingSize() {
    RawMaterial material = rawMaterial(21L, 901L, new BigDecimal("10"), null);
    when(companyEntityLookup.requireActiveRawMaterial(company, 21L)).thenReturn(material);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
            company, "500ML", material))
        .thenReturn(false);
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created =
        packagingMaterialService.createMapping(
            new PackagingSizeMappingRequest("500ml", 21L, 2, null, null));

    assertThat(created.packagingSize()).isEqualTo("500ML");
    assertThat(created.rawMaterialId()).isEqualTo(21L);
    assertThat(created.unitsPerPack()).isEqualTo(2);
    assertThat(created.cartonSize()).isNull();
    assertThat(created.litersPerUnit()).isEqualByComparingTo("0.500000");
    assertThat(created.active()).isTrue();
  }

  @Test
  void createMapping_preservesExplicitLitersPerUnit() {
    RawMaterial material = rawMaterial(27L, 907L, new BigDecimal("10"), null);
    when(companyEntityLookup.requireActiveRawMaterial(company, 27L)).thenReturn(material);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
            company, "1L", material))
        .thenReturn(false);
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created =
        packagingMaterialService.createMapping(
            new PackagingSizeMappingRequest("1L", 27L, 1, 12, new BigDecimal("1.250000")));

    assertThat(created.litersPerUnit()).isEqualByComparingTo("1.250000");
  }

  @Test
  void consumePackagingMaterial_throwsWhenPackagingSetupMissing() {
    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of());

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 2, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(appEx.getMessage())
                  .contains("Packaging Setup is required for size 1L")
                  .contains("Packaging Rule");
            });
  }

  @Test
  void consumePackagingMaterial_rejectsPackagingSetupWithoutInventoryAccount() {
    RawMaterial material = rawMaterial(17L, null, new BigDecimal("5"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 17L)).thenReturn(material);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(appEx.getMessage())
                  .contains(
                      "Packaging Setup for size 1L points to packaging material PACK-17"
                          + " without an inventory account")
                  .contains("Update Packaging Rules before packing");
            });
  }

  @Test
  void consumePackagingMaterial_failsClosedWhenPackagingStockIsInsufficient() {
    RawMaterial material = rawMaterial(19L, 930L, new BigDecimal("2"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 19L)).thenReturn(material);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(appEx.getMessage())
                  .contains("Insufficient PACK-19. Need: 3, Available: 2");
            });

    verify(rawMaterialBatchRepository, never()).deductQuantityIfSufficient(any(), any());
    verify(rawMaterialRepository, never()).save(any(RawMaterial.class));
    verify(rawMaterialMovementRepository, never()).save(any(RawMaterialMovement.class));
  }

  @Test
  void consumePackagingMaterial_usesUnitsPerPackAndFifoCosts() {
    RawMaterial material = rawMaterial(11L, 500L, new BigDecimal("10"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 2);

    RawMaterialBatch batchA = batch(101L, new BigDecimal("4"), new BigDecimal("2.00"));
    RawMaterialBatch batchB = batch(102L, new BigDecimal("4"), new BigDecimal("3.00"));

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 11L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
        .thenReturn(List.of(batchA, batchB));
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(101L), any())).thenReturn(1);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(102L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PackagingConsumptionResult result =
        packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF");

    assertThat(result.mappingFound()).isTrue();
    assertThat(result.totalCost()).isEqualByComparingTo("14.00");
    assertThat(result.quantity()).isEqualByComparingTo("6");
    assertThat(result.accountTotalsOrEmpty()).containsEntry(500L, new BigDecimal("14.00"));
    verify(rawMaterialRepository).save(material);
    assertThat(material.getCurrentStock()).isEqualByComparingTo("4");
  }

  @Test
  void consumePackagingMaterial_usesWeightedAverageCostWhenConfigured() {
    RawMaterial material = rawMaterial(12L, 600L, new BigDecimal("10"), "WAC");
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    RawMaterialBatch batchA = batch(201L, new BigDecimal("2"), new BigDecimal("1.00"));
    RawMaterialBatch batchB = batch(202L, new BigDecimal("2"), new BigDecimal("5.00"));

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 12L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
        .thenReturn(List.of(batchA, batchB));
    when(rawMaterialBatchRepository.calculateWeightedAverageCost(material))
        .thenReturn(new BigDecimal("2.50"));
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(201L), any())).thenReturn(1);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(202L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PackagingConsumptionResult result =
        packagingMaterialService.consumePackagingMaterial("1L", 4, "PACK-REF");

    assertThat(result.mappingFound()).isTrue();
    assertThat(result.totalCost()).isEqualByComparingTo("10.00");
    assertThat(result.quantity()).isEqualByComparingTo("4");
    assertThat(result.accountTotalsOrEmpty()).containsEntry(600L, new BigDecimal("10.00"));
    verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);
  }

  @Test
  void consumePackagingMaterial_wacNullAverageFallsBackToBatchCostDeterministically() {
    RawMaterial material = rawMaterial(13L, 700L, new BigDecimal("10"), "WAC");
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    RawMaterialBatch batchA = batch(301L, new BigDecimal("2"), new BigDecimal("1.00"));
    RawMaterialBatch batchB = batch(302L, new BigDecimal("2"), new BigDecimal("5.00"));

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 13L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
        .thenReturn(List.of(batchA, batchB));
    when(rawMaterialBatchRepository.calculateWeightedAverageCost(material)).thenReturn(null);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(301L), any())).thenReturn(1);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(302L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PackagingConsumptionResult result =
        packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF");

    assertThat(result.mappingFound()).isTrue();
    assertThat(result.totalCost()).isEqualByComparingTo("7.00");
    assertThat(result.quantity()).isEqualByComparingTo("3");
    assertThat(result.accountTotalsOrEmpty()).containsEntry(700L, new BigDecimal("7.00"));
    verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(rawMaterialMovementRepository, times(2)).save(movementCaptor.capture());
    List<RawMaterialMovement> movements = movementCaptor.getAllValues();
    assertThat(movements)
        .anySatisfy(
            movement -> {
              assertThat(movement.getUnitCost()).isEqualByComparingTo("1.00");
              assertThat(movement.getQuantity()).isEqualByComparingTo("2");
            });
    assertThat(movements)
        .anySatisfy(
            movement -> {
              assertThat(movement.getUnitCost()).isEqualByComparingTo("5.00");
              assertThat(movement.getQuantity()).isEqualByComparingTo("1");
            });
  }

  @Test
  void consumePackagingMaterial_wacNullAverageRejectsZeroCostWhenPackagingRequired() {
    RawMaterial material = rawMaterial(14L, 800L, new BigDecimal("5"), "WAC");
    PackagingSizeMapping mapping = packagingMapping(material, 1);
    RawMaterialBatch batchA = batch(401L, new BigDecimal("2"), null);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 14L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material)).thenReturn(List.of(batchA));
    when(rawMaterialBatchRepository.calculateWeightedAverageCost(material)).thenReturn(null);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(401L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(appEx.getMessage())
                  .contains("Packaging Setup for size 1L resolved to zero packaging cost")
                  .contains("Update Packaging Rules before packing");
            });
  }

  @Test
  void consumePackagingMaterial_rejectsInactivePackagingMaterial() {
    RawMaterial material = rawMaterial(16L, 900L, new BigDecimal("5"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 16L))
        .thenThrow(new IllegalArgumentException("inactive"));

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(appEx.getMessage())
                  .contains(
                      "Packaging Setup for size 1L points to an inactive or missing packaging"
                          + " material")
                  .contains("Update Packaging Rules before packing");
            });
  }

  @Test
  void consumePackagingMaterial_rejectsPackagingSetupPointingToNonPackagingMaterial() {
    RawMaterial material = rawMaterial(18L, 910L, new BigDecimal("5"), null);
    material.setMaterialType(MaterialType.PRODUCTION);
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 18L)).thenReturn(material);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(appEx.getMessage())
                  .contains(
                      "Packaging Setup for size 1L points to raw material PACK-18"
                          + " that is not marked as PACKAGING")
                  .contains("Update Packaging Rules before packing");
            });
  }

  @Test
  void updateMapping_updatesPackagingSizeMaterialAndDerivedLiters() {
    RawMaterial originalMaterial = rawMaterial(31L, 920L, new BigDecimal("5"), null);
    RawMaterial updatedMaterial = rawMaterial(32L, 921L, new BigDecimal("6"), null);
    PackagingSizeMapping mapping = packagingMapping(originalMaterial, 1);
    ReflectionTestUtils.setField(mapping, "id", 41L);

    when(mappingRepository.findByCompanyAndId(company, 41L))
        .thenReturn(java.util.Optional.of(mapping));
    when(companyEntityLookup.requireActiveRawMaterial(company, 32L)).thenReturn(updatedMaterial);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterialAndIdNot(
            company, "500ML", updatedMaterial, 41L))
        .thenReturn(false);
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var updated =
        packagingMaterialService.updateMapping(
            41L, new PackagingSizeMappingRequest("500ml", 32L, 3, 24, null));

    assertThat(updated.packagingSize()).isEqualTo("500ML");
    assertThat(updated.rawMaterialId()).isEqualTo(32L);
    assertThat(updated.unitsPerPack()).isEqualTo(3);
    assertThat(updated.cartonSize()).isEqualTo(24);
    assertThat(updated.litersPerUnit()).isEqualByComparingTo("0.500000");
  }

  @Test
  void updateMapping_rejectsDuplicateMappingAfterSizeOrMaterialChange() {
    RawMaterial originalMaterial = rawMaterial(33L, 922L, new BigDecimal("5"), null);
    RawMaterial updatedMaterial = rawMaterial(34L, 923L, new BigDecimal("5"), null);
    PackagingSizeMapping mapping = packagingMapping(originalMaterial, 1);
    ReflectionTestUtils.setField(mapping, "id", 42L);

    when(mappingRepository.findByCompanyAndId(company, 42L))
        .thenReturn(java.util.Optional.of(mapping));
    when(companyEntityLookup.requireActiveRawMaterial(company, 34L)).thenReturn(updatedMaterial);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterialAndIdNot(
            company, "5L", updatedMaterial, 42L))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                packagingMaterialService.updateMapping(
                    42L, new PackagingSizeMappingRequest("5L", 34L, 2, 12, BigDecimal.valueOf(5))))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ENTITY);
              assertThat(appEx.getMessage())
                  .contains("Packaging size mapping already exists for: 5L and PACK-34");
            });
  }

  @Test
  void updateMapping_rejectsMissingMapping() {
    when(mappingRepository.findByCompanyAndId(company, 404L))
        .thenReturn(java.util.Optional.empty());

    assertThatThrownBy(
            () ->
                packagingMaterialService.updateMapping(
                    404L, new PackagingSizeMappingRequest("1L", 15L, 1, 12, BigDecimal.ONE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_ENTITY_NOT_FOUND))
        .hasMessageContaining("Mapping not found");
  }

  @Test
  void listMappingsAndListActiveMappings_keepFullAndActiveViewsSeparate() {
    PackagingSizeMapping active =
        packagingMapping(rawMaterial(35L, 924L, new BigDecimal("5"), null), 1);
    PackagingSizeMapping inactive =
        packagingMapping(rawMaterial(36L, 925L, new BigDecimal("5"), null), 1);
    inactive.setActive(false);

    when(mappingRepository.findByCompanyOrderByPackagingSizeAsc(company))
        .thenReturn(List.of(active, inactive));
    when(mappingRepository.findByCompanyAndActiveOrderByPackagingSizeAsc(company, true))
        .thenReturn(List.of(active));

    var fullList = packagingMaterialService.listMappings();
    var activeList = packagingMaterialService.listActiveMappings();

    assertThat(fullList).hasSize(2);
    assertThat(fullList).extracting(dto -> dto.active()).containsExactly(true, false);
    assertThat(activeList).hasSize(1);
    assertThat(activeList).allSatisfy(dto -> assertThat(dto.active()).isTrue());
  }

  @Test
  void deactivateMapping_marksMappingInactive() {
    PackagingSizeMapping mapping =
        packagingMapping(rawMaterial(37L, 926L, new BigDecimal("5"), null), 1);
    ReflectionTestUtils.setField(mapping, "id", 43L);

    when(mappingRepository.findByCompanyAndId(company, 43L))
        .thenReturn(java.util.Optional.of(mapping));
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    packagingMaterialService.deactivateMapping(43L);

    assertThat(mapping.isActive()).isFalse();
    verify(mappingRepository).save(mapping);
  }

  @Test
  void deactivateMapping_rejectsMissingMapping() {
    when(mappingRepository.findByCompanyAndId(company, 405L))
        .thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> packagingMaterialService.deactivateMapping(405L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_ENTITY_NOT_FOUND))
        .hasMessageContaining("Mapping not found");
  }

  @Test
  void createMapping_defaultsLitersPerUnitToOneWhenSizeCannotBeParsed() {
    RawMaterial material = rawMaterial(22L, 902L, new BigDecimal("10"), null);
    when(companyEntityLookup.requireActiveRawMaterial(company, 22L)).thenReturn(material);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
            company, "CUSTOM", material))
        .thenReturn(false);
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created =
        packagingMaterialService.createMapping(
            new PackagingSizeMappingRequest("custom", 22L, 1, null, null));

    assertThat(created.litersPerUnit()).isEqualByComparingTo("1");
  }

  @Test
  void createMapping_defaultsLitersPerUnitToOneWhenParsedSizeIsNotPositive() {
    RawMaterial material = rawMaterial(29L, 909L, new BigDecimal("10"), null);
    when(companyEntityLookup.requireActiveRawMaterial(company, 29L)).thenReturn(material);
    when(mappingRepository.existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(
            company, "0L", material))
        .thenReturn(false);
    when(mappingRepository.save(any(PackagingSizeMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created =
        packagingMaterialService.createMapping(
            new PackagingSizeMappingRequest("0L", 29L, 1, null, null));

    assertThat(created.litersPerUnit()).isEqualByComparingTo("1");
  }

  @Test
  void consumePackagingMaterial_assignsPackingRecordMaterialAndSizeVariant() {
    RawMaterial material = rawMaterial(23L, 903L, new BigDecimal("5"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);
    RawMaterialBatch batch = batch(501L, new BigDecimal("5"), new BigDecimal("2.00"));
    PackingRecord packingRecord = new PackingRecord();
    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 61L);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 23L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material)).thenReturn(List.of(batch));
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(501L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PackagingConsumptionResult result =
        packagingMaterialService.consumePackagingMaterial(
            "1L", 1, "PACK-REF", sizeVariant, packingRecord);

    assertThat(result.totalCost()).isEqualByComparingTo("2.00");
    assertThat(packingRecord.getPackagingMaterial()).isEqualTo(material);
    assertThat(packingRecord.getSizeVariant()).isEqualTo(sizeVariant);
  }

  @Test
  void consumePackagingMaterial_skipsEmptyBatchBeforeUsingAvailableBatch() {
    RawMaterial material = rawMaterial(24L, 904L, new BigDecimal("3"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);
    RawMaterialBatch emptyBatch = batch(601L, BigDecimal.ZERO, new BigDecimal("1.00"));
    RawMaterialBatch usableBatch = batch(602L, new BigDecimal("3"), new BigDecimal("3.00"));

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 24L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
        .thenReturn(List.of(emptyBatch, usableBatch));
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(602L), any())).thenReturn(1);
    when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PackagingConsumptionResult result =
        packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF");

    assertThat(result.totalCost()).isEqualByComparingTo("3.00");
    verify(rawMaterialBatchRepository, never()).deductQuantityIfSufficient(eq(601L), any());
  }

  @Test
  void consumePackagingMaterial_rejectsConcurrentBatchModification() {
    RawMaterial material = rawMaterial(25L, 905L, new BigDecimal("3"), null);
    PackagingSizeMapping mapping = packagingMapping(material, 1);
    RawMaterialBatch batch = batch(701L, new BigDecimal("3"), new BigDecimal("3.00"));

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 25L)).thenReturn(material);
    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material)).thenReturn(List.of(batch));
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(701L), any())).thenReturn(0);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE))
        .hasMessageContaining("Concurrent modification detected for packaging batch");
  }

  @Test
  void consumePackagingMaterial_rejectsMappingWithoutRawMaterialReference() {
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize("1L");
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(BigDecimal.ONE);
    mapping.setActive(true);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE))
        .hasMessageContaining("does not reference a packaging material");
  }

  @Test
  void consumePackagingMaterial_rejectsUnnamedNonPackagingSetupById() {
    RawMaterial material = rawMaterial(26L, 906L, new BigDecimal("5"), null);
    material.setSku("   ");
    material.setMaterialType(MaterialType.PRODUCTION);
    PackagingSizeMapping mapping = packagingMapping(material, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 26L)).thenReturn(material);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("#26")
        .hasMessageContaining("not marked as PACKAGING");
  }

  @Test
  void consumePackagingMaterial_rejectsNullPackagingMaterial() {
    RawMaterial mappingMaterial = rawMaterial(30L, 910L, new BigDecimal("5"), null);
    PackagingSizeMapping mapping = packagingMapping(mappingMaterial, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 30L)).thenReturn(null);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("unknown")
        .hasMessageContaining("not marked as PACKAGING");
  }

  @Test
  void consumePackagingMaterial_rejectsNullPackagingMaterialReferenceWithUnknownLabel() {
    RawMaterial mappingMaterial = rawMaterial(28L, 908L, new BigDecimal("5"), null);
    RawMaterial unresolvedMaterial = new RawMaterial();
    unresolvedMaterial.setCompany(company);
    unresolvedMaterial.setMaterialType(MaterialType.PRODUCTION);
    unresolvedMaterial.setSku("   ");
    PackagingSizeMapping mapping = packagingMapping(mappingMaterial, 1);

    when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
        .thenReturn(List.of(mapping));
    when(companyEntityLookup.lockActiveRawMaterial(company, 28L)).thenReturn(unresolvedMaterial);

    assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("unknown")
        .hasMessageContaining("not marked as PACKAGING");
  }

  private RawMaterial rawMaterial(Long id, Long accountId, BigDecimal stock, String costingMethod) {
    RawMaterial material = new RawMaterial();
    ReflectionTestUtils.setField(material, "id", id);
    material.setCompany(company);
    material.setInventoryAccountId(accountId);
    material.setCurrentStock(stock);
    material.setCostingMethod(costingMethod);
    material.setMaterialType(MaterialType.PACKAGING);
    material.setSku("PACK-" + id);
    return material;
  }

  private PackagingSizeMapping packagingMapping(RawMaterial material, int unitsPerPack) {
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize("1L");
    mapping.setRawMaterial(material);
    mapping.setUnitsPerPack(unitsPerPack);
    mapping.setLitersPerUnit(BigDecimal.ONE);
    mapping.setActive(true);
    return mapping;
  }

  private RawMaterialBatch batch(Long id, BigDecimal quantity, BigDecimal unitCost) {
    RawMaterialBatch batch = new RawMaterialBatch();
    ReflectionTestUtils.setField(batch, "id", id);
    batch.setQuantity(quantity);
    batch.setCostPerUnit(unitCost);
    return batch;
  }
}
