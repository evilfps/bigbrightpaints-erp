package com.bigbrightpaints.erp.modules.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.service.CompanyScopedFactoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryPhysicalCountService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class ReportServiceCostBreakdownTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private AccountingPeriodSnapshotRepository snapshotRepository;
  @Mock private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private DealerLedgerRepository dealerLedgerRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyScopedFactoryLookupService factoryLookupService;
  @Mock private CompanyClock companyClock;
  @Mock private InventoryValuationQueryService inventoryValuationService;
  @Mock private TrialBalanceReportQueryService trialBalanceReportQueryService;
  @Mock private ProfitLossReportQueryService profitLossReportQueryService;
  @Mock private BalanceSheetReportQueryService balanceSheetReportQueryService;
  @Mock private AgedDebtorsReportQueryService agedDebtorsReportQueryService;
  @Mock private TaxService taxService;
  @Mock private InventoryPhysicalCountService inventoryPhysicalCountService;
  private ReportService reportService;
  private Company company;

  @BeforeEach
  void setUp() {
    reportService =
        new ReportService(
            companyContextService,
            accountRepository,
            accountingPeriodRepository,
            snapshotRepository,
            snapshotLineRepository,
            dealerRepository,
            dealerLedgerService,
            dealerLedgerRepository,
            journalEntryRepository,
            journalLineRepository,
            productionLogRepository,
            packingRecordRepository,
            inventoryMovementRepository,
            rawMaterialMovementRepository,
            accountingLookupService,
            factoryLookupService,
            companyClock,
            inventoryValuationService,
            trialBalanceReportQueryService,
            profitLossReportQueryService,
            balanceSheetReportQueryService,
            agedDebtorsReportQueryService,
            taxService,
            inventoryPhysicalCountService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 700L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void costBreakdown_sumsMaterialLaborAndOverheadCosts() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 88L);
    log.setProductionCode("PROD-88");
    log.setBatchColour("BLUE");
    log.setMixedQuantity(new BigDecimal("10"));
    log.setMaterialCostTotal(new BigDecimal("120.00"));
    log.setLaborCostTotal(new BigDecimal("30.00"));
    log.setOverheadCostTotal(new BigDecimal("25.00"));
    log.setUnitCost(new BigDecimal("17.50"));
    log.setProducedAt(Instant.parse("2026-02-13T00:00:00Z"));

    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer Blue");
    log.setProduct(product);

    when(factoryLookupService.requireProductionLog(company, 88L)).thenReturn(log);

    CostBreakdownDto dto = reportService.costBreakdown(88L);

    assertThat(dto.productionLogId()).isEqualTo(88L);
    assertThat(dto.productionCode()).isEqualTo("PROD-88");
    assertThat(dto.productName()).isEqualTo("Primer Blue");
    assertThat(dto.materialCostTotal()).isEqualByComparingTo("120.00");
    assertThat(dto.laborCostTotal()).isEqualByComparingTo("30.00");
    assertThat(dto.overheadCostTotal()).isEqualByComparingTo("25.00");
    assertThat(dto.totalCost()).isEqualByComparingTo("175.00");
    assertThat(dto.unitCost()).isEqualByComparingTo("17.50");
  }

  @Test
  void costBreakdown_includesPackedBatchAndRawMaterialTraceability() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 77L);
    log.setCompany(company);
    log.setProductionCode("PROD-77");
    log.setBatchColour("WHITE");
    log.setMixedQuantity(new BigDecimal("10"));
    log.setMaterialCostTotal(new BigDecimal("120.00"));
    log.setLaborCostTotal(new BigDecimal("10.00"));
    log.setOverheadCostTotal(new BigDecimal("5.00"));
    log.setUnitCost(new BigDecimal("13.50"));
    log.setProducedAt(Instant.parse("2026-02-14T00:00:00Z"));

    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer White");
    log.setProduct(product);

    FinishedGood finishedGood = new FinishedGood();
    ReflectionTestUtils.setField(finishedGood, "id", 400L);
    finishedGood.setProductCode("FG-WHITE-1L");
    finishedGood.setName("Primer White 1L");

    FinishedGoodBatch finishedGoodBatch = new FinishedGoodBatch();
    ReflectionTestUtils.setField(finishedGoodBatch, "id", 300L);
    finishedGoodBatch.setBatchCode("FG-BATCH-1");
    ReflectionTestUtils.setField(
        finishedGoodBatch,
        "publicId",
        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
    finishedGoodBatch.setUnitCost(new BigDecimal("14.5000"));

    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 200L);
    sizeVariant.setSizeLabel("1L");

    PackingRecord packingRecord = new PackingRecord();
    ReflectionTestUtils.setField(packingRecord, "id", 100L);
    packingRecord.setCompany(company);
    packingRecord.setProductionLog(log);
    packingRecord.setFinishedGood(finishedGood);
    packingRecord.setFinishedGoodBatch(finishedGoodBatch);
    packingRecord.setSizeVariant(sizeVariant);
    packingRecord.setQuantityPacked(new BigDecimal("5"));
    packingRecord.setPackagingSize("1L");
    packingRecord.setPackagingCost(new BigDecimal("25.00"));

    RawMaterial rawMaterial = new RawMaterial();
    ReflectionTestUtils.setField(rawMaterial, "id", 600L);
    rawMaterial.setSku("RM-BASE");
    rawMaterial.setName("Base");

    RawMaterialBatch rawMaterialBatch = new RawMaterialBatch();
    ReflectionTestUtils.setField(rawMaterialBatch, "id", 700L);
    rawMaterialBatch.setBatchCode("RM-B1");

    RawMaterialMovement packagingMovement = new RawMaterialMovement();
    ReflectionTestUtils.setField(packagingMovement, "id", 800L);
    packagingMovement.setRawMaterial(rawMaterial);
    packagingMovement.setRawMaterialBatch(rawMaterialBatch);
    packagingMovement.setReferenceType(InventoryReference.PACKING_RECORD);
    packagingMovement.setReferenceId("PROD-77-PACK-100");
    packagingMovement.setMovementType("ISSUE");
    packagingMovement.setQuantity(new BigDecimal("5"));
    packagingMovement.setUnitCost(new BigDecimal("5.00"));
    packagingMovement.setJournalEntryId(900L);
    ReflectionTestUtils.setField(
        packagingMovement, "createdAt", Instant.parse("2026-02-14T01:00:00Z"));

    InventoryMovement fgMovement = new InventoryMovement();
    ReflectionTestUtils.setField(fgMovement, "id", 500L);
    fgMovement.setReferenceType(InventoryReference.PACKING_RECORD);
    fgMovement.setReferenceId("PROD-77-PACK-100");
    fgMovement.setMovementType("RECEIPT");
    fgMovement.setQuantity(new BigDecimal("5"));
    fgMovement.setUnitCost(new BigDecimal("14.5000"));
    fgMovement.setJournalEntryId(901L);

    when(factoryLookupService.requireProductionLog(company, 77L)).thenReturn(log);
    when(packingRecordRepository.findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(
            company, log))
        .thenReturn(List.of(packingRecord));
    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PROD-77-PACK-100"))
        .thenReturn(List.of(fgMovement));
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PROD-77-PACK-100"))
        .thenReturn(List.of(packagingMovement));

    CostBreakdownDto dto = reportService.costBreakdown(77L);

    assertThat(dto.totalCost()).isEqualByComparingTo("160.00");
    assertThat(dto.costComponents()).isNotNull();
    assertThat(dto.costComponents().packagingCost()).isEqualByComparingTo("25.00");
    assertThat(dto.costComponents().packedQuantity()).isEqualByComparingTo("5");
    assertThat(dto.costComponents().blendedUnitCost()).isEqualByComparingTo("32.0000");

    assertThat(dto.packedBatches()).hasSize(1);
    var packedBatch = dto.packedBatches().getFirst();
    assertThat(packedBatch.packingRecordId()).isEqualTo(100L);
    assertThat(packedBatch.finishedGoodBatchId()).isEqualTo(300L);
    assertThat(packedBatch.finishedGoodCode()).isEqualTo("FG-WHITE-1L");
    assertThat(packedBatch.sizeLabel()).isEqualTo("1L");
    assertThat(packedBatch.totalValue()).isEqualByComparingTo("72.5000");
    assertThat(packedBatch.journalEntryId()).isEqualTo(901L);

    assertThat(dto.rawMaterialTrace()).hasSize(1);
    var movementTrace = dto.rawMaterialTrace().getFirst();
    assertThat(movementTrace.referenceType()).isEqualTo(InventoryReference.PACKING_RECORD);
    assertThat(movementTrace.referenceId()).isEqualTo("PROD-77-PACK-100");
    assertThat(movementTrace.totalCost()).isEqualByComparingTo("25.00");
    assertThat(movementTrace.journalEntryId()).isEqualTo(900L);
  }

  @Test
  void costBreakdown_handlesNullComponentsAndMissingProductName() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 99L);
    log.setProductionCode("PROD-99");
    log.setBatchColour("WHITE");
    log.setMixedQuantity(new BigDecimal("5"));
    log.setMaterialCostTotal(null);
    log.setLaborCostTotal(new BigDecimal("7.00"));
    log.setOverheadCostTotal(null);
    log.setUnitCost(new BigDecimal("1.40"));
    log.setProducedAt(Instant.parse("2026-02-13T01:00:00Z"));
    log.setProduct(null);

    when(factoryLookupService.requireProductionLog(company, 99L)).thenReturn(log);

    CostBreakdownDto dto = reportService.costBreakdown(99L);

    assertThat(dto.productName()).isEqualTo("Unknown");
    assertThat(dto.materialCostTotal()).isNull();
    assertThat(dto.laborCostTotal()).isEqualByComparingTo("7.00");
    assertThat(dto.overheadCostTotal()).isNull();
    assertThat(dto.totalCost()).isEqualByComparingTo("7.00");
    assertThat(dto.unitCost()).isEqualByComparingTo("1.40");
    assertThat(dto.costComponents()).isNotNull();
    assertThat(dto.packedBatches()).isEmpty();
    assertThat(dto.rawMaterialTrace()).isEmpty();
  }
}
