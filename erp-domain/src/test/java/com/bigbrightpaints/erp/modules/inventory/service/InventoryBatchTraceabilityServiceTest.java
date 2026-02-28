package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryBatchTraceabilityDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryBatchTraceabilityServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;

    private InventoryBatchTraceabilityService inventoryBatchTraceabilityService;
    private Company company;

    @BeforeEach
    void setUp() {
        inventoryBatchTraceabilityService = new InventoryBatchTraceabilityService(
                companyContextService,
                finishedGoodBatchRepository,
                rawMaterialBatchRepository,
                inventoryMovementRepository,
                rawMaterialMovementRepository);

        company = new Company();
        company.setCode("BTRACE");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void finishedGoodBatchMovementHistoryReturnsTraceability() {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-BTRACE");
        finishedGood.setName("FG BTrace");

        FinishedGoodBatch batch = new FinishedGoodBatch();
        ReflectionTestUtils.setField(batch, "id", 11L);
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("FG-B1");
        batch.setQuantityTotal(new BigDecimal("20"));
        batch.setQuantityAvailable(new BigDecimal("16"));
        batch.setUnitCost(new BigDecimal("12"));
        batch.setManufacturedAt(Instant.parse("2025-01-01T00:00:00Z"));
        batch.setExpiryDate(LocalDate.parse("2026-01-01"));
        batch.setSource(InventoryBatchSource.PRODUCTION);

        InventoryMovement receipt = new InventoryMovement();
        ReflectionTestUtils.setField(receipt, "id", 101L);
        receipt.setFinishedGood(finishedGood);
        receipt.setFinishedGoodBatch(batch);
        receipt.setReferenceType(InventoryReference.OPENING_STOCK);
        receipt.setReferenceId("OPEN-1");
        receipt.setMovementType("RECEIPT");
        receipt.setQuantity(new BigDecimal("20"));
        receipt.setUnitCost(new BigDecimal("12"));
        ReflectionTestUtils.setField(receipt, "createdAt", Instant.parse("2025-01-01T01:00:00Z"));

        InventoryMovement dispatch = new InventoryMovement();
        ReflectionTestUtils.setField(dispatch, "id", 102L);
        dispatch.setFinishedGood(finishedGood);
        dispatch.setFinishedGoodBatch(batch);
        dispatch.setReferenceType(InventoryReference.SALES_ORDER);
        dispatch.setReferenceId("SO-123");
        dispatch.setMovementType("DISPATCH");
        dispatch.setQuantity(new BigDecimal("4"));
        dispatch.setUnitCost(new BigDecimal("12"));
        ReflectionTestUtils.setField(dispatch, "createdAt", Instant.parse("2025-01-02T01:00:00Z"));

        when(finishedGoodBatchRepository.findByFinishedGood_CompanyAndId(company, 11L))
                .thenReturn(Optional.of(batch));
        when(inventoryMovementRepository.findByFinishedGoodBatchOrderByCreatedAtAsc(batch))
                .thenReturn(List.of(receipt, dispatch));

        InventoryBatchTraceabilityDto trace = inventoryBatchTraceabilityService
                .getBatchMovementHistory(11L, "FINISHED_GOOD");

        assertThat(trace.batchType()).isEqualTo("FINISHED_GOOD");
        assertThat(trace.itemCode()).isEqualTo("FG-BTRACE");
        assertThat(trace.batchNumber()).isEqualTo("FG-B1");
        assertThat(trace.source()).isEqualTo("production");
        assertThat(trace.movements()).hasSize(2);
        assertThat(trace.movements()).extracting(m -> m.referenceType())
                .containsExactly(InventoryReference.OPENING_STOCK, InventoryReference.SALES_ORDER);
        assertThat(trace.movements()).extracting(m -> m.source())
                .containsExactly("adjustment", "production");
    }

    @Test
    void rawMaterialBatchMovementHistoryReturnsTraceability() {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku("RM-BTRACE");
        material.setName("RM BTrace");
        material.setUnitType("KG");

        RawMaterialBatch batch = new RawMaterialBatch();
        ReflectionTestUtils.setField(batch, "id", 22L);
        batch.setRawMaterial(material);
        batch.setBatchCode("RM-B1");
        batch.setQuantity(new BigDecimal("30"));
        batch.setUnit("KG");
        batch.setCostPerUnit(new BigDecimal("3"));
        batch.setManufacturedAt(Instant.parse("2025-01-01T00:00:00Z"));
        batch.setExpiryDate(LocalDate.parse("2025-12-01"));
        batch.setSource(InventoryBatchSource.PURCHASE);

        RawMaterialMovement receipt = new RawMaterialMovement();
        ReflectionTestUtils.setField(receipt, "id", 201L);
        receipt.setRawMaterial(material);
        receipt.setRawMaterialBatch(batch);
        receipt.setReferenceType(InventoryReference.RAW_MATERIAL_PURCHASE);
        receipt.setReferenceId("GRN-1");
        receipt.setMovementType("RECEIPT");
        receipt.setQuantity(new BigDecimal("30"));
        receipt.setUnitCost(new BigDecimal("3"));
        ReflectionTestUtils.setField(receipt, "createdAt", Instant.parse("2025-01-01T01:00:00Z"));

        RawMaterialMovement issue = new RawMaterialMovement();
        ReflectionTestUtils.setField(issue, "id", 202L);
        issue.setRawMaterial(material);
        issue.setRawMaterialBatch(batch);
        issue.setReferenceType(InventoryReference.PRODUCTION_LOG);
        issue.setReferenceId("PROD-1");
        issue.setMovementType("ISSUE");
        issue.setQuantity(new BigDecimal("5"));
        issue.setUnitCost(new BigDecimal("3"));
        ReflectionTestUtils.setField(issue, "createdAt", Instant.parse("2025-01-02T01:00:00Z"));

        when(rawMaterialBatchRepository.findByRawMaterial_CompanyAndId(company, 22L))
                .thenReturn(Optional.of(batch));
        when(rawMaterialMovementRepository.findByRawMaterialBatchOrderByCreatedAtAsc(batch))
                .thenReturn(List.of(receipt, issue));

        InventoryBatchTraceabilityDto trace = inventoryBatchTraceabilityService
                .getBatchMovementHistory(22L, "RAW_MATERIAL");

        assertThat(trace.batchType()).isEqualTo("RAW_MATERIAL");
        assertThat(trace.itemCode()).isEqualTo("RM-BTRACE");
        assertThat(trace.batchNumber()).isEqualTo("RM-B1");
        assertThat(trace.source()).isEqualTo("purchase");
        assertThat(trace.movements()).hasSize(2);
        assertThat(trace.movements()).extracting(m -> m.source())
                .containsExactly("purchase", "production");
    }
}
