package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class PackingServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private PackagingMaterialService packagingMaterialService;
  @Mock private ProductionLogService productionLogService;
  @Mock private PackingProductSupport packingProductSupport;
  @Mock private PackingAllowedSizeService packingAllowedSizeService;
  @Mock private PackingLineResolver packingLineResolver;
  @Mock private PackingIdempotencyService packingIdempotencyService;
  @Mock private PackingInventoryService packingInventoryService;
  @Mock private PackingBatchService packingBatchService;
  @Mock private PackingJournalBuilder packingJournalBuilder;
  @Mock private PackingJournalLinkHelper packingJournalLinkHelper;
  @Mock private PackingReadService packingReadService;

  private PackingService packingService;
  private Company company;

  @BeforeEach
  void setup() {
    packingService =
        new PackingService(
            companyContextService,
            productionLogRepository,
            packingRecordRepository,
            productionLogService,
            companyClock,
            accountingFacade,
            companyEntityLookup,
            packagingMaterialService,
            packingProductSupport,
            packingAllowedSizeService,
            packingLineResolver,
            packingIdempotencyService,
            packingInventoryService,
            packingBatchService,
            packingJournalBuilder,
            packingJournalLinkHelper,
            packingReadService);

    company = new Company();
    company.setTimezone("UTC");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void recordPacking_postsViaAccountingFacade() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-001");
    log.setMixedQuantity(new BigDecimal("10"));
    log.setTotalPackedQuantity(BigDecimal.ZERO);
    log.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
    log.setStatus(ProductionLogStatus.READY_TO_PACK);

    FinishedGood targetFinishedGood = new FinishedGood();
    ReflectionTestUtils.setField(targetFinishedGood, "id", 501L);
    targetFinishedGood.setProductCode("SKU-1");

    PackingRecord savedRecord = new PackingRecord();
    ReflectionTestUtils.setField(savedRecord, "id", 88L);
    savedRecord.setCompany(company);
    savedRecord.setProductionLog(log);

    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 41L);
    sizeVariant.setSizeLabel("500ML");

    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
    when(companyEntityLookup.requireProductionLog(company, 1L)).thenReturn(log);
    when(packingLineResolver.normalizePackagingSize("500ML", 1)).thenReturn("500ML");
    when(packingAllowedSizeService.resolveAllowedSellableSizeTargets(company, log))
        .thenReturn(List.of(allowedTarget(product, targetFinishedGood, sizeVariant)));
    when(packingAllowedSizeService.requireAllowedSellableSize(
            anyList(), eq(log), eq(501L), eq("500ML"), eq(1)))
        .thenReturn(allowedTarget(product, targetFinishedGood, sizeVariant));
    when(packingLineResolver.resolvePiecesPerBox(any(), eq(sizeVariant))).thenReturn(1);
    when(packingLineResolver.resolvePiecesCountForLine(any(), eq(1), eq(1))).thenReturn(2);
    when(packingLineResolver.resolveQuantity(any(), eq(sizeVariant), eq("500ML"), eq(2), eq(1)))
        .thenReturn(new BigDecimal("1.0"));
    when(packingLineResolver.resolveChildBatchCount(any(), eq(2))).thenReturn(2);
    when(packingRecordRepository.save(any())).thenReturn(savedRecord);
    when(packagingMaterialService.consumePackagingMaterial(
            eq("500ML"), eq(2), eq("PROD-001-PACK-88"), eq(sizeVariant), eq(savedRecord)))
        .thenReturn(
            new PackagingConsumptionResult(
                false, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null));

    PackingInventoryService.SemiFinishedConsumption semiFinishedConsumption =
        new PackingInventoryService.SemiFinishedConsumption(
            targetFinishedGood, null, new InventoryMovement(), new BigDecimal("5.00"));
    when(packingInventoryService.consumeSemiFinishedInventory(log, new BigDecimal("1.0"), 88L))
        .thenReturn(semiFinishedConsumption);

    when(packingBatchService.registerFinishedGoodBatch(
            eq(log),
            eq(targetFinishedGood),
            eq(savedRecord),
            eq(new BigDecimal("1.0")),
            eq(LocalDate.of(2024, 1, 1)),
            any(PackagingConsumptionResult.class),
            eq(semiFinishedConsumption),
            eq(sizeVariant)))
        .thenAnswer(
            invocation -> {
              var batch = new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch();
              ReflectionTestUtils.setField(batch, "id", 777L);
              batch.setBatchCode("FG-777");
              return batch;
            });

    when(productionLogRepository.incrementPackedQuantityAtomic(1L, new BigDecimal("1.0")))
        .thenReturn(1);
    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(packingIdempotencyService.reserveIdempotencyRecord(
            eq(company), eq(1L), eq("pack-key-1"), anyString()))
        .thenReturn(new PackingIdempotencyService.IdempotencyReservation(null, null));
    when(packingIdempotencyService.packingRequestHash(any(), eq(LocalDate.of(2024, 1, 1))))
        .thenReturn("hash-123");

    ProductionLogDetailDto detailDto =
        new ProductionLogDetailDto(
            1L,
            null,
            "PROD-001",
            log.getProducedAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            log.getMixedQuantity(),
            "PROD-001",
            log.getMixedQuantity(),
            log.getTotalPackedQuantity(),
            log.getWastageQuantity(),
            null,
            log.getStatus().name(),
            log.getMaterialCostTotal(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    when(productionLogService.getLog(1L)).thenReturn(detailDto);

    PackingRequest request =
        new PackingRequest(
            1L,
            LocalDate.of(2024, 1, 1),
            "packer",
            "pack-key-1",
            List.of(requestLine(501L, "500ML", 2)));

    packingService.recordPacking(request);

    verify(packingBatchService)
        .registerFinishedGoodBatch(
            eq(log),
            eq(targetFinishedGood),
            eq(savedRecord),
            eq(new BigDecimal("1.0")),
            eq(LocalDate.of(2024, 1, 1)),
            any(PackagingConsumptionResult.class),
            eq(semiFinishedConsumption),
            eq(sizeVariant));

    verify(productionLogRepository).incrementPackedQuantityAtomic(1L, new BigDecimal("1.0"));
    verify(packingIdempotencyService).markCompleted(any(), eq(88L));
  }

  @Test
  void recordPacking_marksLogFullyPackedWhenFinalQuantityIsRecorded() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog lockedLog = new ProductionLog();
    ReflectionTestUtils.setField(lockedLog, "id", 1L);
    lockedLog.setCompany(company);
    lockedLog.setProduct(product);
    lockedLog.setProductionCode("PROD-001");
    lockedLog.setMixedQuantity(new BigDecimal("1.0"));
    lockedLog.setTotalPackedQuantity(BigDecimal.ZERO);
    lockedLog.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
    lockedLog.setStatus(ProductionLogStatus.READY_TO_PACK);

    ProductionLog refreshedLog = new ProductionLog();
    ReflectionTestUtils.setField(refreshedLog, "id", 1L);
    refreshedLog.setCompany(company);
    refreshedLog.setProduct(product);
    refreshedLog.setProductionCode("PROD-001");
    refreshedLog.setMixedQuantity(new BigDecimal("1.0"));
    refreshedLog.setTotalPackedQuantity(new BigDecimal("1.0"));
    refreshedLog.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
    refreshedLog.setStatus(ProductionLogStatus.READY_TO_PACK);

    FinishedGood targetFinishedGood = new FinishedGood();
    ReflectionTestUtils.setField(targetFinishedGood, "id", 501L);
    targetFinishedGood.setProductCode("SKU-1");

    PackingRecord savedRecord = new PackingRecord();
    ReflectionTestUtils.setField(savedRecord, "id", 88L);
    savedRecord.setCompany(company);
    savedRecord.setProductionLog(lockedLog);

    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 41L);
    sizeVariant.setSizeLabel("500ML");

    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(lockedLog);
    when(companyEntityLookup.requireProductionLog(company, 1L)).thenReturn(refreshedLog);
    when(packingLineResolver.normalizePackagingSize("500ML", 1)).thenReturn("500ML");
    when(packingAllowedSizeService.resolveAllowedSellableSizeTargets(company, lockedLog))
        .thenReturn(List.of(allowedTarget(product, targetFinishedGood, sizeVariant)));
    when(packingAllowedSizeService.requireAllowedSellableSize(
            anyList(), eq(lockedLog), eq(501L), eq("500ML"), eq(1)))
        .thenReturn(allowedTarget(product, targetFinishedGood, sizeVariant));
    when(packingLineResolver.resolvePiecesPerBox(any(), eq(sizeVariant))).thenReturn(1);
    when(packingLineResolver.resolvePiecesCountForLine(any(), eq(1), eq(1))).thenReturn(1);
    when(packingLineResolver.resolveQuantity(any(), eq(sizeVariant), eq("500ML"), eq(1), eq(1)))
        .thenReturn(new BigDecimal("1.0"));
    when(packingLineResolver.resolveChildBatchCount(any(), eq(1))).thenReturn(1);
    when(packingRecordRepository.save(any())).thenReturn(savedRecord);
    when(packagingMaterialService.consumePackagingMaterial(
            eq("500ML"), eq(1), eq("PROD-001-PACK-88"), eq(sizeVariant), eq(savedRecord)))
        .thenReturn(
            new PackagingConsumptionResult(
                false, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null));

    PackingInventoryService.SemiFinishedConsumption semiFinishedConsumption =
        new PackingInventoryService.SemiFinishedConsumption(
            targetFinishedGood, null, new InventoryMovement(), new BigDecimal("5.00"));
    when(packingInventoryService.consumeSemiFinishedInventory(
            lockedLog, new BigDecimal("1.0"), 88L))
        .thenReturn(semiFinishedConsumption);
    when(packingBatchService.registerFinishedGoodBatch(
            eq(lockedLog),
            eq(targetFinishedGood),
            eq(savedRecord),
            eq(new BigDecimal("1.0")),
            eq(LocalDate.of(2024, 1, 1)),
            any(PackagingConsumptionResult.class),
            eq(semiFinishedConsumption),
            eq(sizeVariant)))
        .thenAnswer(
            invocation -> {
              var batch = new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch();
              ReflectionTestUtils.setField(batch, "id", 777L);
              batch.setBatchCode("FG-777");
              return batch;
            });

    when(productionLogRepository.incrementPackedQuantityAtomic(1L, new BigDecimal("1.0")))
        .thenReturn(1);
    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ProductionLogDetailDto detailDto =
        new ProductionLogDetailDto(
            1L,
            null,
            "PROD-001",
            lockedLog.getProducedAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            lockedLog.getMixedQuantity(),
            "PROD-001",
            lockedLog.getMixedQuantity(),
            new BigDecimal("1.0"),
            BigDecimal.ZERO,
            null,
            "FULLY_PACKED",
            lockedLog.getMaterialCostTotal(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    when(productionLogService.getLog(1L)).thenReturn(detailDto);

    ProductionLogDetailDto result =
        packingService.recordPacking(
            new PackingRequest(
                1L,
                LocalDate.of(2024, 1, 1),
                "packer",
                null,
                List.of(requestLine(501L, "500ML", 1))));

    assertThat(result.status()).isEqualTo("FULLY_PACKED");
    assertThat(refreshedLog.getStatus()).isEqualTo(ProductionLogStatus.FULLY_PACKED);
    verify(productionLogRepository).save(refreshedLog);
  }

  @Test
  void recordPacking_closeResidualWastage_consumesRemainingInventoryAndPostsJournal() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");
    product.setMetadata(Map.of("wastageAccountId", 902L));

    ProductionLog lockedLog = new ProductionLog();
    ReflectionTestUtils.setField(lockedLog, "id", 1L);
    lockedLog.setCompany(company);
    lockedLog.setProduct(product);
    lockedLog.setProductionCode("PROD-001");
    lockedLog.setMixedQuantity(new BigDecimal("10.0"));
    lockedLog.setTotalPackedQuantity(new BigDecimal("6.0"));
    lockedLog.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
    lockedLog.setStatus(ProductionLogStatus.PARTIAL_PACKED);
    lockedLog.setUnitCost(new BigDecimal("12.50"));

    ProductionLog refreshedLog = new ProductionLog();
    ReflectionTestUtils.setField(refreshedLog, "id", 1L);
    refreshedLog.setCompany(company);
    refreshedLog.setProduct(product);
    refreshedLog.setProductionCode("PROD-001");
    refreshedLog.setMixedQuantity(new BigDecimal("10.0"));
    refreshedLog.setTotalPackedQuantity(new BigDecimal("6.0"));
    refreshedLog.setProducedAt(Instant.parse("2024-01-01T00:00:00Z"));
    refreshedLog.setStatus(ProductionLogStatus.PARTIAL_PACKED);
    refreshedLog.setUnitCost(new BigDecimal("12.50"));

    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(lockedLog);
    when(companyEntityLookup.requireProductionLog(company, 1L)).thenReturn(refreshedLog);
    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(stubEntry(91L));
    when(packingProductSupport.requireWipAccountId(product)).thenReturn(811L);

    ProductionLogDetailDto detailDto =
        new ProductionLogDetailDto(
            1L,
            null,
            "PROD-001",
            refreshedLog.getProducedAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            refreshedLog.getMixedQuantity(),
            "PROD-001",
            refreshedLog.getMixedQuantity(),
            refreshedLog.getTotalPackedQuantity(),
            new BigDecimal("4.0"),
            "PROCESS_LOSS",
            "FULLY_PACKED",
            refreshedLog.getMaterialCostTotal(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    when(productionLogService.getLog(1L)).thenReturn(detailDto);

    PackingRequest request =
        new PackingRequest(1L, LocalDate.of(2024, 1, 2), "packer", "close-loss-1", List.of(), true);
    when(packingIdempotencyService.reserveIdempotencyRecord(
            eq(company), eq(1L), eq("close-loss-1"), anyString()))
        .thenReturn(new PackingIdempotencyService.IdempotencyReservation(null, null));
    when(packingIdempotencyService.packingRequestHash(any(), eq(LocalDate.of(2024, 1, 2))))
        .thenReturn("hash-close-loss");

    ProductionLogDetailDto result = packingService.recordPacking(request);

    assertThat(result.status()).isEqualTo("FULLY_PACKED");
    assertThat(refreshedLog.getStatus()).isEqualTo(ProductionLogStatus.FULLY_PACKED);
    assertThat(refreshedLog.getWastageQuantity()).isEqualByComparingTo(new BigDecimal("4.0"));
    assertThat(refreshedLog.getWastageReasonCode()).isEqualTo("PROCESS_LOSS");
    verify(packingInventoryService).consumeSemiFinishedWastage(refreshedLog, new BigDecimal("4.0"));
    verify(accountingFacade).createStandardJournal(any(JournalCreationRequest.class));
    verify(productionLogRepository, never()).incrementPackedQuantityAtomic(anyLong(), any());
  }

  @Test
  void closeResidualWastage_capsNegativeResidualAtZeroAndSkipsJournal() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-NEG");
    log.setMixedQuantity(new BigDecimal("5.0"));
    log.setTotalPackedQuantity(new BigDecimal("7.0"));
    log.setStatus(ProductionLogStatus.PARTIAL_PACKED);

    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ReflectionTestUtils.invokeMethod(
        packingService, "closeResidualWastage", log, LocalDate.of(2024, 1, 3));

    verify(packingInventoryService).consumeSemiFinishedWastage(log, BigDecimal.ZERO);
    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
    assertThat(log.getStatus()).isEqualTo(ProductionLogStatus.FULLY_PACKED);
    assertThat(log.getWastageQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(log.getWastageReasonCode()).isEqualTo("NONE");
  }

  @Test
  void closeResidualWastage_usesMaterialCostFallbackWhenUnitCostMissing() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");
    product.setMetadata(Map.of("wastageAccountId", 902L));

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-FALLBACK");
    log.setMixedQuantity(new BigDecimal("10.0"));
    log.setTotalPackedQuantity(new BigDecimal("6.0"));
    log.setMaterialCostTotal(new BigDecimal("25.0"));
    log.setUnitCost(BigDecimal.ZERO);
    log.setStatus(ProductionLogStatus.PARTIAL_PACKED);

    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(packingProductSupport.requireWipAccountId(product)).thenReturn(811L);
    when(packingProductSupport.metadataLong(product, "wastageAccountId")).thenReturn(902L);

    ReflectionTestUtils.invokeMethod(
        packingService, "closeResidualWastage", log, LocalDate.of(2024, 1, 4));

    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    verify(accountingFacade).createStandardJournal(requestCaptor.capture());
    assertThat(requestCaptor.getValue().amount()).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(requestCaptor.getValue().debitAccount()).isEqualTo(902L);
    assertThat(requestCaptor.getValue().creditAccount()).isEqualTo(811L);
  }

  @Test
  void closeResidualWastage_rejectsMissingWastageAccountMetadata() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-NO-WASTE-ACCOUNT");
    log.setMixedQuantity(new BigDecimal("10.0"));
    log.setTotalPackedQuantity(new BigDecimal("6.0"));
    log.setUnitCost(new BigDecimal("5.0"));
    log.setStatus(ProductionLogStatus.PARTIAL_PACKED);
    when(packingProductSupport.metadataLong(product, "wastageAccountId")).thenReturn(null);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    packingService, "closeResidualWastage", log, LocalDate.of(2024, 1, 5)))
        .isInstanceOf(ApplicationException.class)
        .hasMessage("Product Primer missing wastageAccountId metadata");
  }

  @Test
  void applyPackedQuantity_rejectsOverflow() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setProductionCode("PROD-OVERFLOW");

    when(productionLogRepository.incrementPackedQuantityAtomic(1L, new BigDecimal("2.0")))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    packingService, "applyPackedQuantity", log, new BigDecimal("2.0")))
        .isInstanceOf(ApplicationException.class)
        .hasMessage("Packed quantity would exceed mixed quantity for PROD-OVERFLOW");
  }

  @Test
  void updateStatus_setsReadyToPackWhenNothingPacked() {
    ProductionLog log = new ProductionLog();
    log.setMixedQuantity(new BigDecimal("10.0"));
    log.setStatus(ProductionLogStatus.PARTIAL_PACKED);

    ReflectionTestUtils.invokeMethod(packingService, "updateStatus", log, BigDecimal.ZERO);

    assertThat(log.getStatus()).isEqualTo(ProductionLogStatus.READY_TO_PACK);
  }

  @Test
  void postResidualWastageJournal_skipsWhenCalculatedValueIsZero() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");
    product.setMetadata(Map.of("wastageAccountId", 902L));

    ProductionLog log = new ProductionLog();
    log.setProduct(product);
    log.setProductionCode("PROD-ZERO-VALUE");
    log.setMixedQuantity(BigDecimal.ZERO);
    log.setMaterialCostTotal(new BigDecimal("25.0"));
    log.setUnitCost(BigDecimal.ZERO);

    ReflectionTestUtils.invokeMethod(
        packingService,
        "postResidualWastageJournal",
        log,
        LocalDate.of(2024, 1, 6),
        new BigDecimal("4.0"));

    verify(accountingFacade, never()).createStandardJournal(any(JournalCreationRequest.class));
  }

  @Test
  void safeQuantity_returnsZeroForNull() {
    BigDecimal safeQuantity =
        ReflectionTestUtils.invokeMethod(packingService, "safeQuantity", (Object) null);

    assertThat(safeQuantity).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void calculateUnitCost_returnsZeroWhenInputsAreMissing() {
    BigDecimal missingTotal =
        ReflectionTestUtils.invokeMethod(
            packingService, "calculateUnitCost", null, new BigDecimal("10.0"));
    BigDecimal missingQuantity =
        ReflectionTestUtils.invokeMethod(
            packingService, "calculateUnitCost", new BigDecimal("10.0"), null);

    assertThat(missingTotal).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(missingQuantity).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void recordPacking_rejectsEmptyRequestWhenResidualCloseNotRequested() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProductionCode("PROD-001");
    log.setStatus(ProductionLogStatus.READY_TO_PACK);
    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);

    PackingRequest request =
        new PackingRequest(1L, LocalDate.of(2024, 1, 2), "packer", "empty-1", List.of(), false);

    assertThatThrownBy(() -> packingService.recordPacking(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Packing lines are required unless closeResidualWastage is true");

    verifyNoInteractions(packagingMaterialService);
    verify(productionLogRepository, never()).incrementPackedQuantityAtomic(anyLong(), any());
  }

  @Test
  void recordPacking_rejectsPackagingMovementJournalRelinkDrift() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-001");
    log.setMixedQuantity(new BigDecimal("10"));
    log.setTotalPackedQuantity(BigDecimal.ZERO);
    log.setStatus(ProductionLogStatus.READY_TO_PACK);

    FinishedGood targetFinishedGood = new FinishedGood();
    ReflectionTestUtils.setField(targetFinishedGood, "id", 501L);
    targetFinishedGood.setProductCode("SKU-1");

    PackingRecord savedRecord = new PackingRecord();
    ReflectionTestUtils.setField(savedRecord, "id", 88L);
    savedRecord.setCompany(company);
    savedRecord.setProductionLog(log);

    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 41L);
    sizeVariant.setSizeLabel("500ML");

    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
    when(packingLineResolver.normalizePackagingSize("500ML", 1)).thenReturn("500ML");
    when(packingAllowedSizeService.resolveAllowedSellableSizeTargets(company, log))
        .thenReturn(List.of(allowedTarget(product, targetFinishedGood, sizeVariant)));
    when(packingAllowedSizeService.requireAllowedSellableSize(
            anyList(), eq(log), eq(501L), eq("500ML"), eq(1)))
        .thenReturn(allowedTarget(product, targetFinishedGood, sizeVariant));
    when(packingLineResolver.resolvePiecesPerBox(any(), eq(sizeVariant))).thenReturn(1);
    when(packingLineResolver.resolvePiecesCountForLine(any(), eq(1), eq(1))).thenReturn(2);
    when(packingLineResolver.resolveQuantity(any(), eq(sizeVariant), eq("500ML"), eq(2), eq(1)))
        .thenReturn(new BigDecimal("1.0"));
    when(packingLineResolver.resolveChildBatchCount(any(), eq(2))).thenReturn(2);
    when(packingRecordRepository.save(any())).thenReturn(savedRecord);

    PackagingConsumptionResult packagingResult =
        new PackagingConsumptionResult(
            true,
            new BigDecimal("20.00"),
            new BigDecimal("2"),
            Map.of(811L, new BigDecimal("20.00")),
            null);
    when(packagingMaterialService.consumePackagingMaterial(
            eq("500ML"), eq(2), eq("PROD-001-PACK-88"), eq(sizeVariant), eq(savedRecord)))
        .thenReturn(packagingResult);

    when(packingJournalBuilder.buildWipPackagingConsumptionLines(
            anyLong(), anyString(), any(), any()))
        .thenCallRealMethod();
    when(accountingFacade.postPackingJournal(anyString(), any(LocalDate.class), anyString(), any()))
        .thenReturn(stubEntry(55L));

    doThrow(
            new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Packing reference PROD-001-PACK-88 already linked to journal 999"))
        .when(packingJournalLinkHelper)
        .linkPackagingMovementsToJournal(company, "PROD-001-PACK-88", 55L);

    PackingRequest request =
        new PackingRequest(
            1L, LocalDate.of(2024, 1, 1), "packer", null, List.of(requestLine(501L, "500ML", 2)));

    assertThatThrownBy(() -> packingService.recordPacking(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already linked to journal")
        .satisfies(
            error ->
                assertThat(((ApplicationException) error).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION));

    verify(productionLogRepository, never()).incrementPackedQuantityAtomic(anyLong(), any());
  }

  @Test
  void recordPacking_idempotentReplayDoesNotConsumeOrPostAgain() {
    ProductionLog log = new ProductionLog();
    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);

    ProductionLogDetailDto detailDto =
        new ProductionLogDetailDto(
            1L,
            null,
            "PROD-001",
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ONE,
            "PROD-001",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "READY_TO_PACK",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    when(packingIdempotencyService.reserveIdempotencyRecord(
            eq(company), eq(1L), eq("pack-replay"), anyString()))
        .thenReturn(new PackingIdempotencyService.IdempotencyReservation(null, detailDto));
    when(packingIdempotencyService.packingRequestHash(any(), eq(LocalDate.of(2024, 1, 1))))
        .thenReturn("hash-replay");

    PackingRequest request =
        new PackingRequest(
            1L,
            LocalDate.of(2024, 1, 1),
            "packer",
            "pack-replay",
            List.of(requestLine(11L, "1L", 1)));

    ProductionLogDetailDto result = packingService.recordPacking(request);

    assertThat(result.id()).isEqualTo(1L);
    verify(packagingMaterialService, never())
        .consumePackagingMaterial(anyString(), anyInt(), anyString(), any(), any());
    verify(accountingFacade, never())
        .postPackingJournal(anyString(), any(LocalDate.class), anyString(), any());
    verify(packingRecordRepository, never()).save(any());
  }

  @Test
  void recordPacking_idempotencyMismatchConflicts() {
    ProductionLog log = new ProductionLog();
    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);

    when(packingIdempotencyService.reserveIdempotencyRecord(
            eq(company), eq(1L), eq("pack-mismatch"), anyString()))
        .thenThrow(
            new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Idempotency payload mismatch for packing request"));
    when(packingIdempotencyService.packingRequestHash(any(), eq(LocalDate.of(2024, 1, 1))))
        .thenReturn("hash-mismatch");

    PackingRequest request =
        new PackingRequest(
            1L,
            LocalDate.of(2024, 1, 1),
            "packer",
            "pack-mismatch",
            List.of(requestLine(11L, "1L", 1)));

    assertThatThrownBy(() -> packingService.recordPacking(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency payload mismatch");
  }

  @Test
  void recordPacking_reusesAllowedTargetsAcrossMultipleLines() {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("SKU-1");
    product.setProductName("Primer");

    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 1L);
    log.setCompany(company);
    log.setProduct(product);
    log.setProductionCode("PROD-001");
    log.setMixedQuantity(new BigDecimal("10"));
    log.setTotalPackedQuantity(BigDecimal.ZERO);
    log.setStatus(ProductionLogStatus.READY_TO_PACK);

    FinishedGood targetFinishedGood = new FinishedGood();
    ReflectionTestUtils.setField(targetFinishedGood, "id", 501L);
    targetFinishedGood.setProductCode("SKU-1");

    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", 41L);
    sizeVariant.setSizeLabel("500ML");

    PackingAllowedSizeService.AllowedSellableSizeTarget allowedTarget =
        allowedTarget(product, targetFinishedGood, sizeVariant);
    List<PackingAllowedSizeService.AllowedSellableSizeTarget> allowedTargets =
        List.of(allowedTarget);

    PackingRecord firstRecord = new PackingRecord();
    ReflectionTestUtils.setField(firstRecord, "id", 88L);
    firstRecord.setCompany(company);
    firstRecord.setProductionLog(log);

    PackingRecord secondRecord = new PackingRecord();
    ReflectionTestUtils.setField(secondRecord, "id", 89L);
    secondRecord.setCompany(company);
    secondRecord.setProductionLog(log);

    when(companyEntityLookup.lockProductionLog(company, 1L)).thenReturn(log);
    when(companyEntityLookup.requireProductionLog(company, 1L)).thenReturn(log);
    when(packingAllowedSizeService.resolveAllowedSellableSizeTargets(company, log))
        .thenReturn(allowedTargets);
    when(packingLineResolver.normalizePackagingSize("500ML", 1)).thenReturn("500ML");
    when(packingLineResolver.normalizePackagingSize("500ML", 2)).thenReturn("500ML");
    when(packingAllowedSizeService.requireAllowedSellableSize(
            sameList(allowedTargets), eq(log), eq(501L), eq("500ML"), eq(1)))
        .thenReturn(allowedTarget);
    when(packingAllowedSizeService.requireAllowedSellableSize(
            sameList(allowedTargets), eq(log), eq(501L), eq("500ML"), eq(2)))
        .thenReturn(allowedTarget);
    when(packingLineResolver.resolvePiecesPerBox(any(), eq(sizeVariant))).thenReturn(1);
    when(packingLineResolver.resolvePiecesCountForLine(any(), eq(1), eq(1))).thenReturn(1);
    when(packingLineResolver.resolvePiecesCountForLine(any(), eq(1), eq(2))).thenReturn(1);
    when(packingLineResolver.resolveQuantity(any(), eq(sizeVariant), eq("500ML"), eq(1), eq(1)))
        .thenReturn(new BigDecimal("1.0"));
    when(packingLineResolver.resolveQuantity(any(), eq(sizeVariant), eq("500ML"), eq(1), eq(2)))
        .thenReturn(new BigDecimal("1.0"));
    when(packingLineResolver.resolveChildBatchCount(any(), eq(1))).thenReturn(1);
    when(packingRecordRepository.save(any()))
        .thenReturn(firstRecord, secondRecord, secondRecord, secondRecord);
    when(packagingMaterialService.consumePackagingMaterial(
            anyString(), anyInt(), anyString(), eq(sizeVariant), any()))
        .thenReturn(
            new PackagingConsumptionResult(
                false, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null));

    PackingInventoryService.SemiFinishedConsumption semiFinishedConsumption =
        new PackingInventoryService.SemiFinishedConsumption(
            targetFinishedGood, null, new InventoryMovement(), new BigDecimal("5.00"));
    when(packingInventoryService.consumeSemiFinishedInventory(eq(log), any(), anyLong()))
        .thenReturn(semiFinishedConsumption);
    when(packingBatchService.registerFinishedGoodBatch(
            eq(log),
            eq(targetFinishedGood),
            any(PackingRecord.class),
            any(),
            eq(LocalDate.of(2024, 1, 1)),
            any(PackagingConsumptionResult.class),
            eq(semiFinishedConsumption),
            eq(sizeVariant)))
        .thenAnswer(
            invocation -> {
              var batch = new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch();
              ReflectionTestUtils.setField(batch, "id", 777L);
              batch.setBatchCode("FG-777");
              return batch;
            });
    when(productionLogRepository.incrementPackedQuantityAtomic(1L, new BigDecimal("2.0")))
        .thenReturn(1);
    when(productionLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ProductionLogDetailDto detailDto =
        new ProductionLogDetailDto(
            1L,
            null,
            "PROD-001",
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("10"),
            "PROD-001",
            new BigDecimal("10"),
            new BigDecimal("2.0"),
            BigDecimal.ZERO,
            null,
            ProductionLogStatus.PARTIAL_PACKED.name(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    when(productionLogService.getLog(1L)).thenReturn(detailDto);

    PackingRequest request =
        new PackingRequest(
            1L,
            LocalDate.of(2024, 1, 1),
            "packer",
            null,
            List.of(requestLine(501L, "500ML", 1), requestLine(501L, "500ML", 1)));

    ProductionLogDetailDto result = packingService.recordPacking(request);

    assertThat(result.status()).isEqualTo(ProductionLogStatus.PARTIAL_PACKED.name());
    verify(packingAllowedSizeService).resolveAllowedSellableSizeTargets(company, log);
    verify(packingAllowedSizeService, times(2))
        .requireAllowedSellableSize(
            sameList(allowedTargets), eq(log), eq(501L), eq("500ML"), anyInt());
  }

  private PackingLineRequest requestLine(Long childFinishedGoodId, String size, int piecesCount) {
    return new PackingLineRequest(childFinishedGoodId, null, size, null, piecesCount, null, null);
  }

  private PackingAllowedSizeService.AllowedSellableSizeTarget allowedTarget(
      ProductionProduct product, FinishedGood finishedGood, SizeVariant sizeVariant) {
    return new PackingAllowedSizeService.AllowedSellableSizeTarget(
        product, finishedGood, sizeVariant, product.getProductName());
  }

  @SuppressWarnings("unchecked")
  private List<PackingAllowedSizeService.AllowedSellableSizeTarget> sameList(
      List<PackingAllowedSizeService.AllowedSellableSizeTarget> allowedTargets) {
    return eq(allowedTargets);
  }

  private JournalEntryDto stubEntry(long id) {
    return new JournalEntryDto(
        id, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, List.of(), null, null, null, null, null, null);
  }
}
