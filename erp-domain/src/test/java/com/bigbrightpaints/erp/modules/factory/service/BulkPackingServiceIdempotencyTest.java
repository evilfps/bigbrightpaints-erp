package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkPackingServiceIdempotencyTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private BatchNumberService batchNumberService;
    @Mock
    private PackagingMaterialService packagingMaterialService;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private CompanyClock companyClock;

    private BulkPackingService bulkPackingService;
    private Company company;

    @BeforeEach
    void setUp() {
        bulkPackingService = new BulkPackingService(
                companyContextService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                inventoryMovementRepository,
                rawMaterialMovementRepository,
                journalEntryRepository,
                accountingFacade,
                batchNumberService,
                packagingMaterialService,
                finishedGoodsService,
                companyClock
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void pack_idempotentReplayWithoutJournalFailsClosed() {
        FinishedGood bulkFg = new FinishedGood();
        ReflectionTestUtils.setField(bulkFg, "id", 100L);
        bulkFg.setCompany(company);

        FinishedGoodBatch bulkBatch = new FinishedGoodBatch();
        ReflectionTestUtils.setField(bulkBatch, "id", 10L);
        bulkBatch.setFinishedGood(bulkFg);
        bulkBatch.setBatchCode("BULK-10");
        bulkBatch.setBulk(true);
        bulkBatch.setQuantityAvailable(new BigDecimal("100"));
        bulkBatch.setQuantityTotal(new BigDecimal("100"));

        InventoryMovement issueMovement = new InventoryMovement();
        issueMovement.setMovementType("ISSUE");
        issueMovement.setQuantity(new BigDecimal("5"));

        when(finishedGoodBatchRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(bulkBatch));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq(InventoryReference.PACKING_RECORD), anyString()))
                .thenReturn(List.of(issueMovement));
        when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                eq(company), eq(InventoryReference.PACKING_RECORD), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), anyString()))
                .thenReturn(Optional.empty());

        BulkPackRequest request = new BulkPackRequest(
                10L,
                List.of(new BulkPackRequest.PackLine(200L, new BigDecimal("5"), "1L", "L")),
                null,
                true,
                LocalDate.of(2026, 2, 12),
                "factory-user",
                "idempotent replay",
                "bulk-idem-1"
        );

        assertThatThrownBy(() -> bulkPackingService.pack(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Partial bulk pack detected")
                .hasMessageContaining("inventory movements exist without journal");

        verify(accountingFacade, never()).postPackingJournal(anyString(), any(), anyString(), any());
    }

    @Test
    void pack_rejectsDuplicateChildSkuLinesBeforeMutation() {
        BulkPackRequest request = new BulkPackRequest(
                10L,
                List.of(
                        new BulkPackRequest.PackLine(200L, new BigDecimal("2"), "1L", "L"),
                        new BulkPackRequest.PackLine(200L, new BigDecimal("3"), "1L", "L")
                ),
                null,
                true,
                LocalDate.of(2026, 2, 12),
                "factory-user",
                "duplicate child line",
                "bulk-idem-dup"
        );

        assertThatThrownBy(() -> bulkPackingService.pack(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Duplicate child SKU line is not allowed");

        verify(finishedGoodBatchRepository, never()).lockByCompanyAndId(any(), any());
    }

    @Test
    void pack_rejectsZeroPackQuantityBeforeMutation() {
        BulkPackRequest request = new BulkPackRequest(
                10L,
                List.of(new BulkPackRequest.PackLine(200L, BigDecimal.ZERO, "1L", "L")),
                null,
                true,
                LocalDate.of(2026, 2, 12),
                "factory-user",
                "zero quantity",
                "bulk-idem-zero"
        );

        assertThatThrownBy(() -> bulkPackingService.pack(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Pack quantity must be greater than zero");

        verify(finishedGoodBatchRepository, never()).lockByCompanyAndId(any(), any());
    }

    @Test
    void consumePackagingMaterials_wacNullAverageFallsBackToBatchUnitCosts() {
        RawMaterial packaging = new RawMaterial();
        ReflectionTestUtils.setField(packaging, "id", 901L);
        packaging.setCompany(company);
        packaging.setSku("PKG-901");
        packaging.setCurrentStock(new BigDecimal("10"));
        packaging.setInventoryAccountId(700L);
        packaging.setCostingMethod("WAC");

        RawMaterialBatch batchA = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchA, "id", 501L);
        batchA.setBatchCode("PKG-A");
        batchA.setQuantity(new BigDecimal("2"));
        batchA.setCostPerUnit(new BigDecimal("1.00"));

        RawMaterialBatch batchB = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchB, "id", 502L);
        batchB.setBatchCode("PKG-B");
        batchB.setQuantity(new BigDecimal("2"));
        batchB.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialRepository.lockByCompanyAndId(company, 901L)).thenReturn(Optional.of(packaging));
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(packaging)).thenReturn(List.of(batchA, batchB));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(packaging)).thenReturn(null);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(501L),
                argThat(qty -> qty.compareTo(new BigDecimal("2")) == 0))).thenReturn(1);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(502L),
                argThat(qty -> qty.compareTo(BigDecimal.ONE) == 0))).thenReturn(1);
        when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Object summary = ReflectionTestUtils.invokeMethod(
                bulkPackingService,
                "consumePackagingMaterials",
                company,
                List.of(new BulkPackRequest.MaterialConsumption(901L, new BigDecimal("3"), "UNIT")),
                "PACK-REF");

        BigDecimal totalCost = (BigDecimal) ReflectionTestUtils.invokeMethod(summary, "totalCost");
        assertThat(totalCost).isEqualByComparingTo("7.00");
        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> accountTotals = (Map<Long, BigDecimal>) ReflectionTestUtils.invokeMethod(summary, "accountTotals");
        assertThat(accountTotals).containsEntry(700L, new BigDecimal("7.00"));

        ArgumentCaptor<RawMaterialMovement> movementCaptor = ArgumentCaptor.forClass(RawMaterialMovement.class);
        verify(rawMaterialMovementRepository, times(2)).save(movementCaptor.capture());
        List<RawMaterialMovement> movements = movementCaptor.getAllValues();
        assertThat(movements.get(0).getUnitCost()).isEqualByComparingTo("1.00");
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(movements.get(1).getUnitCost()).isEqualByComparingTo("5.00");
        assertThat(movements.get(1).getQuantity()).isEqualByComparingTo("1");
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(packaging);
    }
}
