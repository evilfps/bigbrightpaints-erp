package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationRequest;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class CostAllocationServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyClock companyClock;

  private CostAllocationService costAllocationService;
  private Company company;

  @BeforeEach
  void setUp() {
    costAllocationService =
        new CostAllocationService(
            companyContextService,
            productionLogRepository,
            finishedGoodBatchRepository,
            accountingFacade,
            accountingLookupService,
            companyClock);
    company = new Company();
    company.setTimezone("UTC");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyClock.zoneId(company)).thenReturn(ZoneId.of("UTC"));
  }

  @Test
  void allocateCosts_allocatesEntireRemainingVarianceAcrossUnallocatedBatches() {
    ProductionLog alreadyAllocated = productionLog(1L, "PROD-1", "10", "10", "40", "20");
    ProductionLog allocatable = productionLog(2L, "PROD-2", "10", "10", "0", "0");

    when(productionLogRepository.findFullyPackedBatchesByMonth(eq(company), any(), any()))
        .thenReturn(List.of(alreadyAllocated, allocatable));
    when(accountingFacade.findExistingCostVarianceReference("PROD-1", "202604"))
        .thenReturn(Optional.of("CVAR-PROD-1-202604"));
    when(accountingFacade.findExistingCostVarianceReference("PROD-2", "202604"))
        .thenReturn(Optional.empty());
    when(accountingLookupService.requireAccount(company, 11L))
        .thenReturn(account(11L, "FG", AccountType.ASSET));
    when(accountingLookupService.requireAccount(company, 12L))
        .thenReturn(account(12L, "LAB", AccountType.EXPENSE));
    when(accountingLookupService.requireAccount(company, 13L))
        .thenReturn(account(13L, "OVH", AccountType.EXPENSE));
    when(accountingFacade.postCostVarianceAllocation(
            eq("PROD-2"),
            eq("202604"),
            any(),
            eq(11L),
            eq(12L),
            eq(13L),
            eq(new BigDecimal("60.00")),
            eq(new BigDecimal("30.00")),
            eq("Variance allocation")))
        .thenReturn(null);

    CostAllocationResponse response =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                2026,
                4,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                11L,
                12L,
                13L,
                "Variance allocation"));

    assertThat(response.totalLaborAllocated()).isEqualByComparingTo("60.00");
    assertThat(response.totalOverheadAllocated()).isEqualByComparingTo("30.00");
    assertThat(response.avgCostPerLiter()).isEqualByComparingTo("9.0000");
    assertThat(response.summary()).contains("skipped 1");
    assertThat(allocatable.getLaborCostTotal()).isEqualByComparingTo("60.0000");
    assertThat(allocatable.getOverheadCostTotal()).isEqualByComparingTo("30.0000");
    verify(productionLogRepository).save(allocatable);
  }

  @Test
  void allocateCosts_splitsRemainingVarianceAcrossMultipleAllocatableBatches() {
    ProductionLog first = productionLog(3L, "PROD-3", "3", "3", "0", "0");
    ProductionLog second = productionLog(4L, "PROD-4", "7", "7", "0", "0");

    when(productionLogRepository.findFullyPackedBatchesByMonth(eq(company), any(), any()))
        .thenReturn(List.of(first, second));
    when(accountingFacade.findExistingCostVarianceReference("PROD-3", "202604"))
        .thenReturn(Optional.empty());
    when(accountingFacade.findExistingCostVarianceReference("PROD-4", "202604"))
        .thenReturn(Optional.empty());
    when(accountingLookupService.requireAccount(company, 11L))
        .thenReturn(account(11L, "FG", AccountType.ASSET));
    when(accountingLookupService.requireAccount(company, 12L))
        .thenReturn(account(12L, "LAB", AccountType.EXPENSE));
    when(accountingLookupService.requireAccount(company, 13L))
        .thenReturn(account(13L, "OVH", AccountType.EXPENSE));
    when(accountingFacade.postCostVarianceAllocation(
            any(),
            eq("202604"),
            any(),
            eq(11L),
            eq(12L),
            eq(13L),
            any(),
            any(),
            eq("Variance allocation")))
        .thenReturn(null);

    CostAllocationResponse response =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                2026,
                4,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                11L,
                12L,
                13L,
                "Variance allocation"));

    assertThat(response.totalLaborAllocated()).isEqualByComparingTo("100.00");
    assertThat(response.totalOverheadAllocated()).isEqualByComparingTo("50.00");
    assertThat(first.getLaborCostTotal()).isEqualByComparingTo("30.0000");
    assertThat(first.getOverheadCostTotal()).isEqualByComparingTo("15.0000");
    assertThat(second.getLaborCostTotal()).isEqualByComparingTo("70.0000");
    assertThat(second.getOverheadCostTotal()).isEqualByComparingTo("35.0000");
    verify(productionLogRepository).save(first);
    verify(productionLogRepository).save(second);
  }

  private ProductionLog productionLog(
      Long id,
      String productionCode,
      String mixedQuantity,
      String packedQuantity,
      String laborCostTotal,
      String overheadCostTotal) {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", id);
    log.setProductionCode(productionCode);
    log.setMixedQuantity(new BigDecimal(mixedQuantity));
    log.setTotalPackedQuantity(new BigDecimal(packedQuantity));
    log.setLaborCostTotal(new BigDecimal(laborCostTotal).setScale(4));
    log.setOverheadCostTotal(new BigDecimal(overheadCostTotal).setScale(4));
    log.setMaterialCostTotal(BigDecimal.ZERO.setScale(4));
    return log;
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCode(code);
    account.setType(type);
    return account;
  }
}
