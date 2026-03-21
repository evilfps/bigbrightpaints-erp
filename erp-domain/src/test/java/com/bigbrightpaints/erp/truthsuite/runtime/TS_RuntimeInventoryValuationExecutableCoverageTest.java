package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.reports.service.InventoryValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeInventoryValuationExecutableCoverageTest {

    @Test
    void fifoFinishedGoodValuation_usesQuantityAvailable_notQuantityTotal() {
        Instant fixedNow = Instant.parse("2026-03-18T00:00:00Z");
        installCompanyTime(fixedNow);
        assertThat(CompanyTime.now()).isEqualTo(fixedNow);
        assertThat(CompanyTime.today()).isEqualTo(LocalDate.of(2026, 3, 18));

        RawMaterialRepository rawMaterialRepository = mock(RawMaterialRepository.class);
        RawMaterialBatchRepository rawMaterialBatchRepository = mock(RawMaterialBatchRepository.class);
        FinishedGoodRepository finishedGoodRepository = mock(FinishedGoodRepository.class);
        FinishedGoodBatchRepository finishedGoodBatchRepository = mock(FinishedGoodBatchRepository.class);
        InventoryMovementRepository inventoryMovementRepository = mock(InventoryMovementRepository.class);
        RawMaterialMovementRepository rawMaterialMovementRepository = mock(RawMaterialMovementRepository.class);
        ProductionProductRepository productionProductRepository = mock(ProductionProductRepository.class);
        AccountingPeriodRepository accountingPeriodRepository = mock(AccountingPeriodRepository.class);

        InventoryValuationService service = new InventoryValuationService(
                rawMaterialRepository,
                rawMaterialBatchRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                rawMaterialMovementRepository,
                productionProductRepository,
                accountingPeriodRepository
        );

        Company company = new Company();
        company.setCode("TRUTH");
        company.setName("Truth Pvt");
        company.setTimezone("Asia/Kolkata");
        ReflectionTestUtils.setField(company, "id", 61L);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-VAL-01");
        finishedGood.setName("FG Valuation");
        finishedGood.setCurrentStock(new BigDecimal("7.00"));
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setCostingMethod("FIFO");
        ReflectionTestUtils.setField(finishedGood, "id", 901L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("FG-BATCH-01");
        batch.setQuantityTotal(new BigDecimal("10.00"));
        batch.setQuantityAvailable(new BigDecimal("3.00"));
        batch.setUnitCost(new BigDecimal("50.00"));
        batch.setManufacturedAt(Instant.parse("2026-02-01T00:00:00Z"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(batch));
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = service.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("150.00");
        assertThat(snapshot.lowStockItems()).isEqualTo(0L);
    }

    private static void installCompanyTime(Instant now) {
        CompanyClock companyClock = mock(CompanyClock.class);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        when(companyClock.now(org.mockito.ArgumentMatchers.any())).thenReturn(now);
        when(companyClock.now(null)).thenReturn(now);
        when(companyClock.today(org.mockito.ArgumentMatchers.any())).thenReturn(today);
        when(companyClock.today(null)).thenReturn(today);
        new CompanyTime(companyClock);
    }
}
