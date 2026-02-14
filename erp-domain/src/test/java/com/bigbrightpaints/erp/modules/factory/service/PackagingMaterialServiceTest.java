package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PackagingMaterialServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private PackagingSizeMappingRepository mappingRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;

    private PackagingMaterialService packagingMaterialService;
    private Company company;

    @BeforeEach
    void setUp() {
        packagingMaterialService = new PackagingMaterialService(
                companyContextService,
                mappingRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void consumePackagingMaterial_returnsNoMappingWhenOptional() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", false);
        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of());

        PackagingConsumptionResult result = packagingMaterialService.consumePackagingMaterial("1L", 2, "PACK-REF");

        assertThat(result.mappingFound()).isFalse();
        assertThat(result.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.accountTotalsOrEmpty()).isEmpty();
    }

    @Test
    void consumePackagingMaterial_throwsWhenMappingRequiredAndMissing() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", true);
        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 2, "PACK-REF"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(appEx.getMessage()).contains("Packaging BOM is required");
                });
    }

    @Test
    void consumePackagingMaterial_usesUnitsPerPackAndFifoCosts() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", false);
        RawMaterial material = rawMaterial(11L, 500L, new BigDecimal("10"), null);
        PackagingSizeMapping mapping = packagingMapping(material, 2);

        RawMaterialBatch batchA = batch(101L, new BigDecimal("4"), new BigDecimal("2.00"));
        RawMaterialBatch batchB = batch(102L, new BigDecimal("4"), new BigDecimal("3.00"));

        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of(mapping));
        when(rawMaterialRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(material));
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
                .thenReturn(List.of(batchA, batchB));
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(101L), any())).thenReturn(1);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(102L), any())).thenReturn(1);
        when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagingConsumptionResult result = packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF");

        assertThat(result.mappingFound()).isTrue();
        assertThat(result.totalCost()).isEqualByComparingTo("14.00");
        assertThat(result.quantity()).isEqualByComparingTo("6");
        assertThat(result.accountTotalsOrEmpty()).containsEntry(500L, new BigDecimal("14.00"));
        verify(rawMaterialRepository).save(material);
        assertThat(material.getCurrentStock()).isEqualByComparingTo("4");
    }

    @Test
    void consumePackagingMaterial_usesWeightedAverageCostWhenConfigured() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", false);
        RawMaterial material = rawMaterial(12L, 600L, new BigDecimal("10"), "WAC");
        PackagingSizeMapping mapping = packagingMapping(material, 1);

        RawMaterialBatch batchA = batch(201L, new BigDecimal("2"), new BigDecimal("1.00"));
        RawMaterialBatch batchB = batch(202L, new BigDecimal("2"), new BigDecimal("5.00"));

        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of(mapping));
        when(rawMaterialRepository.lockByCompanyAndId(company, 12L)).thenReturn(Optional.of(material));
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

        PackagingConsumptionResult result = packagingMaterialService.consumePackagingMaterial("1L", 4, "PACK-REF");

        assertThat(result.mappingFound()).isTrue();
        assertThat(result.totalCost()).isEqualByComparingTo("10.00");
        assertThat(result.quantity()).isEqualByComparingTo("4");
        assertThat(result.accountTotalsOrEmpty()).containsEntry(600L, new BigDecimal("10.00"));
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);
    }

    @Test
    void consumePackagingMaterial_wacNullAverageFallsBackToBatchCostDeterministically() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", false);
        RawMaterial material = rawMaterial(13L, 700L, new BigDecimal("10"), "WAC");
        PackagingSizeMapping mapping = packagingMapping(material, 1);

        RawMaterialBatch batchA = batch(301L, new BigDecimal("2"), new BigDecimal("1.00"));
        RawMaterialBatch batchB = batch(302L, new BigDecimal("2"), new BigDecimal("5.00"));

        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of(mapping));
        when(rawMaterialRepository.lockByCompanyAndId(company, 13L)).thenReturn(Optional.of(material));
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
                .thenReturn(List.of(batchA, batchB));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(material))
                .thenReturn(null);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(301L), any())).thenReturn(1);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(302L), any())).thenReturn(1);
        when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagingConsumptionResult result = packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF");

        assertThat(result.mappingFound()).isTrue();
        assertThat(result.totalCost()).isEqualByComparingTo("7.00");
        assertThat(result.quantity()).isEqualByComparingTo("3");
        assertThat(result.accountTotalsOrEmpty()).containsEntry(700L, new BigDecimal("7.00"));
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);

        ArgumentCaptor<RawMaterialMovement> movementCaptor = ArgumentCaptor.forClass(RawMaterialMovement.class);
        verify(rawMaterialMovementRepository, times(2)).save(movementCaptor.capture());
        List<RawMaterialMovement> movements = movementCaptor.getAllValues();
        assertThat(movements.get(0).getUnitCost()).isEqualByComparingTo("1.00");
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(movements.get(1).getUnitCost()).isEqualByComparingTo("5.00");
        assertThat(movements.get(1).getQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void consumePackagingMaterial_wacNullAverageRejectsZeroCostWhenPackagingRequired() {
        ReflectionTestUtils.setField(packagingMaterialService, "requirePackaging", true);
        RawMaterial material = rawMaterial(14L, 800L, new BigDecimal("5"), "WAC");
        PackagingSizeMapping mapping = packagingMapping(material, 1);
        RawMaterialBatch batchA = batch(401L, new BigDecimal("2"), null);

        when(mappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, "1L"))
                .thenReturn(List.of(mapping));
        when(rawMaterialRepository.lockByCompanyAndId(company, 14L)).thenReturn(Optional.of(material));
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(material))
                .thenReturn(List.of(batchA));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(material))
                .thenReturn(null);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(401L), any())).thenReturn(1);
        when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> packagingMaterialService.consumePackagingMaterial("1L", 1, "PACK-REF"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(appEx.getMessage()).contains("produced zero cost");
                });
    }

    private RawMaterial rawMaterial(Long id, Long accountId, BigDecimal stock, String costingMethod) {
        RawMaterial material = new RawMaterial();
        ReflectionTestUtils.setField(material, "id", id);
        material.setCompany(company);
        material.setInventoryAccountId(accountId);
        material.setCurrentStock(stock);
        material.setCostingMethod(costingMethod);
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
