package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
    private AccountingService accountingService;
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

    private PackingService packingService;
    private Company company;

    @BeforeEach
    void setup() {
        packingService = new PackingService(
                companyContextService,
                productionLogRepository,
                packingRecordRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                rawMaterialMovementRepository,
                accountingFacade,
                accountingService,
                productionLogService,
                batchNumberService,
                companyClock,
                companyEntityLookup,
                packagingMaterialService
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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

        // Use lockProductionLog instead of requireProductionLog for pessimistic locking
        when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "SKU-1")).thenReturn(Optional.of(finishedGood));

        JournalEntryDto wasteEntry = stubEntry(11L);
        when(accountingFacade.postSimpleJournal(
                anyString(),
                any(LocalDate.class),
                anyString(),
                anyLong(),
                anyLong(),
                any(BigDecimal.class),
                anyBoolean())
        ).thenReturn(wasteEntry);

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
        ArgumentCaptor<BigDecimal> wasteAmount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingFacade, times(1)).postSimpleJournal(
                eq("PROD-001-WASTE"),
                eq(LocalDate.of(2024, 1, 1)),
                eq("Manufacturing wastage for PROD-001"),
                eq(901L),
                eq(900L),
                wasteAmount.capture(),
                eq(false)
        );
        assertThat(wasteAmount.getValue()).isEqualByComparingTo("200.00");
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
