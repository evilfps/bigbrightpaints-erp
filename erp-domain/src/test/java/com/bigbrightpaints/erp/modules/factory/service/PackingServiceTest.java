package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private ProductionLogRepository productionLogRepository;
    @Mock
    private PackingRecordRepository packingRecordRepository;
    @Mock
    private PackingRequestRecordRepository packingRequestRecordRepository;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private ProductionLogService productionLogService;
    @Mock
    private BatchNumberService batchNumberService;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private PackagingMaterialService packagingMaterialService;
    @Mock
    private FinishedGoodsService finishedGoodsService;

    private PackingService packingService;
    private Company company;

    @BeforeEach
    void setup() {
        packingService = new PackingService(
                companyContextService,
                productionLogRepository,
                packingRecordRepository,
                packingRequestRecordRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                rawMaterialMovementRepository,
                accountingFacade,
                productionLogService,
                batchNumberService,
                companyClock,
                companyEntityLookup,
                packagingMaterialService,
                finishedGoodsService
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void completePacking_postsOnlyWastageJournal() {
        // FG receipt journals are now posted per packing session in recordPacking
        // completePacking only posts wastage journal
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode("SKU-1");
        product.setProductName("Primer");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", 900L);
        metadata.put("wastageAccountId", 901L);
        product.setMetadata(metadata);

        ProductionLog log = new ProductionLog();
        ReflectionTestUtils.setField(log, "id", 1L);
        log.setCompany(company);
        log.setProduct(product);
        log.setProductionCode("PROD-001");
        log.setMixedQuantity(new BigDecimal("100"));
        log.setTotalPackedQuantity(new BigDecimal("80"));
        log.setMaterialCostTotal(new BigDecimal("1000"));
        log.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
        log.setStatus(ProductionLogStatus.PARTIAL_PACKED);

        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", 5L);
        finishedGood.setCompany(company);
        finishedGood.setProductCode(product.getSkuCode());
        finishedGood.setValuationAccountId(500L);
        finishedGood.setCurrentStock(BigDecimal.ZERO);

        FinishedGood semiFinished = new FinishedGood();
        ReflectionTestUtils.setField(semiFinished, "id", 6L);
        semiFinished.setCompany(company);
        semiFinished.setProductCode(product.getSkuCode() + "-BULK");
        semiFinished.setValuationAccountId(700L);
        semiFinished.setCurrentStock(new BigDecimal("100"));

        FinishedGoodBatch semiBatch = new FinishedGoodBatch();
        semiBatch.setFinishedGood(semiFinished);
        semiBatch.setBatchCode(log.getProductionCode());
        semiBatch.setQuantityAvailable(new BigDecimal("100"));
        semiBatch.setQuantityTotal(new BigDecimal("100"));
        semiBatch.setUnitCost(new BigDecimal("10"));

        // Use lockProductionLog instead of requireProductionLog for pessimistic locking
        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1")).thenReturn(Optional.of(finishedGood));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1-BULK"))
                .thenReturn(Optional.of(semiFinished));
        when(finishedGoodBatchRepository.lockByFinishedGoodAndBatchCode(semiFinished, "PROD-001"))
                .thenReturn(Optional.of(semiBatch));
        when(finishedGoodBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finishedGoodRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JournalEntryDto wasteEntry = stubEntry(11L);
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class))).thenReturn(wasteEntry);

        ProductionLogDetailDto detailDto = new ProductionLogDetailDto(
                log.getId(),
                null,
                log.getProductionCode(),
                log.getProducedAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                log.getTotalPackedQuantity(),
                log.getWastageQuantity(),
                log.getStatus().name(),
                log.getMaterialCostTotal(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()
        );
        when(productionLogService.getLog(1L)).thenReturn(detailDto);

        packingService.completePacking(1L);

        // Only wastage journal should be posted in completePacking
        ArgumentCaptor<JournalCreationRequest> requestCaptor = ArgumentCaptor.forClass(JournalCreationRequest.class);
        verify(accountingFacade, times(1)).createStandardJournal(requestCaptor.capture());
        JournalCreationRequest request = requestCaptor.getValue();
        assertThat(request.sourceReference()).isEqualTo("PROD-001-WASTE");
        assertThat(request.entryDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(request.narration()).isEqualTo("Manufacturing wastage for PROD-001");
        assertThat(request.debitAccount()).isEqualTo(901L);
        assertThat(request.creditAccount()).isEqualTo(900L);
        assertThat(request.amount()).isEqualByComparingTo("200.00");
        assertThat(request.adminOverride()).isFalse();
    }

    @Test
    void recordPacking_postsViaAccountingFacade() {
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode("SKU-1");
        product.setProductName("Primer");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", 900L);
        metadata.put("semiFinishedAccountId", 700L);
        product.setMetadata(metadata);

        ProductionLog log = new ProductionLog();
        ReflectionTestUtils.setField(log, "id", 1L);
        log.setCompany(company);
        log.setProduct(product);
        log.setProductionCode("PROD-001");
        log.setMixedQuantity(new BigDecimal("10"));
        log.setTotalPackedQuantity(new BigDecimal("0"));
        log.setUnitCost(new BigDecimal("5.00"));
        log.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
        log.setStatus(ProductionLogStatus.READY_TO_PACK);

        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", 5L);
        finishedGood.setCompany(company);
        finishedGood.setProductCode(product.getSkuCode());
        finishedGood.setValuationAccountId(500L);
        finishedGood.setCurrentStock(BigDecimal.ZERO);

        FinishedGood semiFinished = new FinishedGood();
        ReflectionTestUtils.setField(semiFinished, "id", 6L);
        semiFinished.setCompany(company);
        semiFinished.setProductCode(product.getSkuCode() + "-BULK");
        semiFinished.setValuationAccountId(700L);
        semiFinished.setCurrentStock(new BigDecimal("10"));

        FinishedGoodBatch semiBatch = new FinishedGoodBatch();
        semiBatch.setFinishedGood(semiFinished);
        semiBatch.setBatchCode(log.getProductionCode());
        semiBatch.setQuantityAvailable(new BigDecimal("10"));
        semiBatch.setQuantityTotal(new BigDecimal("10"));
        semiBatch.setUnitCost(new BigDecimal("5.00"));

        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
        when(companyEntityLookup.requireProductionLog(company, 1L)).thenReturn(log);
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1"))
                .thenReturn(Optional.of(finishedGood));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1-BULK"))
                .thenReturn(Optional.of(semiFinished));
        when(finishedGoodBatchRepository.lockByFinishedGoodAndBatchCode(semiFinished, "PROD-001"))
                .thenReturn(Optional.of(semiBatch));
        when(packingRecordRepository.save(any())).thenAnswer(invocation -> {
            var record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 88L);
            return record;
        });
        when(finishedGoodBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finishedGoodRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryMovementRepository.save(any())).thenAnswer(invocation -> {
            InventoryMovement movement = invocation.getArgument(0);
            ReflectionTestUtils.setField(movement, "id", 77L);
            return movement;
        });
        when(packagingMaterialService.consumePackagingMaterial(anyString(), anyInt(), anyString()))
                .thenReturn(new PackagingConsumptionResult(false, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null));
        when(productionLogRepository.incrementPackedQuantityAtomic(eq(1L), any())).thenReturn(1);
        when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packingRequestRecordRepository.findByCompanyAndIdempotencyKey(company, "pack-key-1"))
                .thenReturn(Optional.empty());
        when(packingRequestRecordRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packingRequestRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountingFacade.postPackingJournal(anyString(), any(LocalDate.class), anyString(), any()))
                .thenReturn(stubEntry(11L));

        ProductionLogDetailDto detailDto = new ProductionLogDetailDto(
                log.getId(),
                null,
                log.getProductionCode(),
                log.getProducedAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                log.getMixedQuantity(),
                log.getTotalPackedQuantity(),
                log.getWastageQuantity(),
                log.getStatus().name(),
                log.getMaterialCostTotal(),
                null,
                null,
                log.getUnitCost(),
                null,
                null,
                null,
                null,
                List.of()
        );
        when(productionLogService.getLog(1L)).thenReturn(detailDto);

        PackingRequest request = new PackingRequest(
                log.getId(),
                LocalDate.of(2024, 1, 1),
                "packer",
                "pack-key-1",
                List.of(new PackingLineRequest("500ML", null, 2, null, null))
        );

        packingService.recordPacking(request);

        ArgumentCaptor<BigDecimal> packedQuantity = ArgumentCaptor.forClass(BigDecimal.class);
        verify(productionLogRepository).incrementPackedQuantityAtomic(eq(1L), packedQuantity.capture());
        assertThat(packedQuantity.getValue()).isEqualByComparingTo("1.0");

        verify(accountingFacade, times(1)).postPackingJournal(
                eq("PROD-001-PACK-77"),
                eq(LocalDate.of(2024, 1, 1)),
                anyString(),
                any()
        );
    }

    @Test
    void recordPacking_rejectsPackagingMovementJournalRelinkDrift() {
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode("SKU-1");
        product.setProductName("Primer");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", 900L);
        product.setMetadata(metadata);

        ProductionLog log = new ProductionLog();
        ReflectionTestUtils.setField(log, "id", 1L);
        log.setCompany(company);
        log.setProduct(product);
        log.setProductionCode("PROD-001");
        log.setMixedQuantity(new BigDecimal("10"));
        log.setTotalPackedQuantity(BigDecimal.ZERO);
        log.setStatus(ProductionLogStatus.READY_TO_PACK);

        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", 5L);
        finishedGood.setCompany(company);
        finishedGood.setProductCode(product.getSkuCode());
        finishedGood.setValuationAccountId(500L);
        finishedGood.setCurrentStock(BigDecimal.ZERO);

        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1"))
                .thenReturn(Optional.of(finishedGood));
        when(packingRecordRepository.save(any())).thenAnswer(invocation -> {
            var record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 88L);
            return record;
        });
        when(packagingMaterialService.consumePackagingMaterial("500ML", 2, "PROD-001-PACK-88"))
                .thenReturn(new PackagingConsumptionResult(
                        true,
                        new BigDecimal("20.00"),
                        new BigDecimal("2"),
                        Map.of(811L, new BigDecimal("20.00")),
                        null
                ));
        when(accountingFacade.postPackingJournal(anyString(), any(LocalDate.class), anyString(), any()))
                .thenReturn(stubEntry(55L));

        RawMaterialMovement existingMovement = new RawMaterialMovement();
        ReflectionTestUtils.setField(existingMovement, "id", 300L);
        existingMovement.setJournalEntryId(999L);
        when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.PACKING_RECORD,
                "PROD-001-PACK-88"))
                .thenReturn(List.of(existingMovement));

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2024, 1, 1),
                "packer",
                null,
                List.of(new PackingLineRequest("500ML", null, 2, null, null))
        );

        assertThatThrownBy(() -> packingService.recordPacking(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("already linked to journal")
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION));

        assertThat(existingMovement.getJournalEntryId()).isEqualTo(999L);
        verify(rawMaterialMovementRepository, never()).saveAll(any());
        verify(productionLogRepository, never()).incrementPackedQuantityAtomic(anyLong(), any());
    }

    @Test
    void recordPacking_idempotentReplayDoesNotConsumeOrPostAgain() {
        PackingRequestRecord existing = new PackingRequestRecord();
        existing.setCompany(company);
        existing.setIdempotencyKey("pack-replay");
        existing.setIdempotencyHash(null);
        existing.setProductionLogId(1L);
        existing.setPackingRecordId(99L);

        when(packingRequestRecordRepository.findByCompanyAndIdempotencyKey(company, "pack-replay"))
                .thenReturn(Optional.of(existing));
        ProductionLogDetailDto detailDto = new ProductionLogDetailDto(
                1L, null, "PROD-001", Instant.parse("2024-01-01T00:00:00Z"), null, null, null, null,
                null, null, null, BigDecimal.ONE, null, "READY_TO_PACK", null, null, null,
                null, null, null, null, null, List.of()
        );
        when(productionLogService.getLog(1L)).thenReturn(detailDto);
        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(new ProductionLog());

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2024, 1, 1),
                "packer",
                "pack-replay",
                List.of(new PackingLineRequest("1L", new BigDecimal("1"), 1, null, null))
        );

        ProductionLogDetailDto result = packingService.recordPacking(request);

        assertThat(result.id()).isEqualTo(1L);
        verify(packagingMaterialService, never()).consumePackagingMaterial(anyString(), anyInt(), anyString());
        verify(accountingFacade, never()).postPackingJournal(anyString(), any(LocalDate.class), anyString(), any());
        verify(packingRecordRepository, never()).save(any());
    }

    @Test
    void recordPacking_idempotencyMismatchConflicts() {
        PackingRequestRecord existing = new PackingRequestRecord();
        existing.setCompany(company);
        existing.setIdempotencyKey("pack-mismatch");
        existing.setIdempotencyHash("aaaaaaaa");
        existing.setProductionLogId(1L);

        when(packingRequestRecordRepository.findByCompanyAndIdempotencyKey(company, "pack-mismatch"))
                .thenReturn(Optional.of(existing));
        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(new ProductionLog());

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2024, 1, 1),
                "packer",
                "pack-mismatch",
                List.of(new PackingLineRequest("1L", new BigDecimal("1"), 1, null, null))
        );

        assertThatThrownBy(() -> packingService.recordPacking(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency payload mismatch");
    }

    private JournalEntryDto stubEntry(long id) {
        return new JournalEntryDto(
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
