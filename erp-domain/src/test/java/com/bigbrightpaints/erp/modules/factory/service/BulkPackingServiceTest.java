package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class BulkPackingServiceTest {

  @Mock private CompanyEntityLookup companyEntityLookup;

  @Mock
  private com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository
      finishedGoodRepository;

  @Mock private FinishedGoodBatchRegistrar finishedGoodBatchRegistrar;
  @Mock private PackingJournalBuilder packingJournalBuilder;

  @Test
  void parseSizeInLitersSupportsMlAndLtr() {
    assertThat(BulkPackingOrchestrator.parseSizeInLiters("500ML"))
        .isEqualByComparingTo(new BigDecimal("0.500000"));
    assertThat(BulkPackingOrchestrator.parseSizeInLiters("1LTR"))
        .isEqualByComparingTo(new BigDecimal("1"));
    assertThat(BulkPackingOrchestrator.parseSizeInLiters("0.5L"))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void parseSizeInLitersReturnsNullForInvalid() {
    assertThat(BulkPackingOrchestrator.parseSizeInLiters("SIZE")).isNull();
  }

  @Test
  void buildPackReference_isStableForEquivalentRequests() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    RawMaterialBatch bulkBatch = new RawMaterialBatch();
    ReflectionTestUtils.setField(bulkBatch, "id", 42L);
    bulkBatch.setBatchCode("bulk-42");

    BulkPackRequest firstRequest =
        new BulkPackRequest(
            42L,
            List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
            LocalDate.of(2026, 3, 23),
            null,
            null,
            null);
    BulkPackRequest secondRequest =
        new BulkPackRequest(
            42L,
            List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
            LocalDate.of(2026, 3, 23),
            null,
            null,
            null);

    String firstReference =
        ReflectionTestUtils.invokeMethod(service, "buildPackReference", bulkBatch, firstRequest);
    String secondReference =
        ReflectionTestUtils.invokeMethod(service, "buildPackReference", bulkBatch, secondRequest);

    assertThat(firstReference).isEqualTo(secondReference);
  }

  @Test
  void buildPackReference_includesExplicitIdempotencyKeyFingerprint() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    RawMaterialBatch bulkBatch = new RawMaterialBatch();
    ReflectionTestUtils.setField(bulkBatch, "id", 42L);
    bulkBatch.setBatchCode("bulk-42");

    BulkPackRequest requestWithFirstKey =
        new BulkPackRequest(
            42L,
            List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
            LocalDate.of(2026, 3, 23),
            null,
            null,
            "KEY-1");
    BulkPackRequest requestWithSecondKey =
        new BulkPackRequest(
            42L,
            List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
            LocalDate.of(2026, 3, 23),
            null,
            null,
            "KEY-2");

    String firstReference =
        ReflectionTestUtils.invokeMethod(
            service, "buildPackReference", bulkBatch, requestWithFirstKey);
    String secondReference =
        ReflectionTestUtils.invokeMethod(
            service, "buildPackReference", bulkBatch, requestWithSecondKey);

    assertThat(firstReference).isNotEqualTo(secondReference);
  }

  @Test
  void buildPackReference_nullRequestStillExercisesDerivedFingerprintGuard() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    RawMaterialBatch bulkBatch = new RawMaterialBatch();
    ReflectionTestUtils.setField(bulkBatch, "id", 42L);
    bulkBatch.setBatchCode("bulk-42");

    assertThatThrownBy(
            () -> ReflectionTestUtils.invokeMethod(service, "buildPackReference", bulkBatch, null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void trimReference_returnsBaseWhenWithinLimit() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    String reference =
        ReflectionTestUtils.invokeMethod(service, "trimReference", "PACK-", "BULK-1", "abc123", 64);

    assertThat(reference).isEqualTo("PACK-BULK-1-abc123");
  }

  @Test
  void trimReference_returnsPrefixAndHashWhenBatchSpaceIsNonPositive() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    String reference =
        ReflectionTestUtils.invokeMethod(service, "trimReference", "PACK-", "BULK-1", "1234567890", 8);

    assertThat(reference).isEqualTo("PACK-1234567890");
  }

  @Test
  void trimReference_trimsBatchWhenReferenceExceedsLimit() {
    BulkPackingService service =
        new BulkPackingService(null, null, null, null, null, null, null, null, null);

    String reference =
        ReflectionTestUtils.invokeMethod(
            service, "trimReference", "PACK-", "BULK-BATCH-CODE-LONG", "abcd", 16);

    assertThat(reference).isEqualTo("PACK-BULK-B-abcd");
  }

  @Test
  void createChildBatch_requiresActiveFinishedGood() {
    BulkPackingOrchestrator orchestrator =
        new BulkPackingOrchestrator(
            companyEntityLookup,
            finishedGoodRepository,
            finishedGoodBatchRegistrar,
            packingJournalBuilder);
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 9L);

    FinishedGood childFinishedGood = new FinishedGood();
    ReflectionTestUtils.setField(childFinishedGood, "id", 77L);
    childFinishedGood.setCompany(company);
    childFinishedGood.setProductCode("FG-CHILD-1L");
    childFinishedGood.setName("Primer 1L");

    RawMaterialBatch parentBatch = new RawMaterialBatch();
    parentBatch.setManufacturedAt(Instant.parse("2026-03-20T10:15:30Z"));

    FinishedGoodBatch childBatch = new FinishedGoodBatch();
    childBatch.setFinishedGood(childFinishedGood);
    when(companyEntityLookup.lockActiveFinishedGood(company, 77L)).thenReturn(childFinishedGood);
    when(finishedGoodBatchRegistrar.registerReceipt(any()))
        .thenReturn(new FinishedGoodBatchRegistrar.ReceiptRegistrationResult(childBatch, null));

    FinishedGoodBatch result =
        orchestrator.createChildBatch(
            company,
            parentBatch,
            new BulkPackRequest.PackLine(77L, new BigDecimal("6"), "1L", "L"),
            new BigDecimal("80.00"),
            new BigDecimal("2.50"),
            LocalDate.of(2026, 3, 23),
            "PACK-42-REF");

    assertThat(result).isSameAs(childBatch);
    verify(companyEntityLookup).lockActiveFinishedGood(company, 77L);
  }

  @Test
  void createChildBatch_translatesUnknownChildSku() {
    BulkPackingOrchestrator orchestrator =
        new BulkPackingOrchestrator(
            companyEntityLookup,
            finishedGoodRepository,
            finishedGoodBatchRegistrar,
            packingJournalBuilder);
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 9L);

    RawMaterialBatch parentBatch = new RawMaterialBatch();

    when(companyEntityLookup.lockActiveFinishedGood(company, 77L))
        .thenThrow(new IllegalArgumentException("inactive"));

    assertThatThrownBy(
            () ->
                orchestrator.createChildBatch(
                    company,
                    parentBatch,
                    new BulkPackRequest.PackLine(77L, new BigDecimal("6"), "1L", "L"),
                    new BigDecimal("80.00"),
                    new BigDecimal("2.50"),
                    LocalDate.of(2026, 3, 23),
                    "PACK-42-REF"))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Child SKU not found: 77");
  }

  @Test
  void resolveTargetFinishedGood_requiresActiveChildSku() {
    PackingProductSupport support =
        new PackingProductSupport(companyEntityLookup, finishedGoodRepository);
    Company company = new Company();

    FinishedGood activeChild = new FinishedGood();
    activeChild.setProductCode("FG-PARENT-1L");
    when(companyEntityLookup.lockActiveFinishedGood(company, 88L)).thenReturn(activeChild);

    ProductionLog log = new ProductionLog();
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("FG-PARENT");
    log.setProduct(product);

    FinishedGood resolved =
        support.resolveTargetFinishedGood(
            company,
            log,
            new PackingLineRequest(88L, 1, "1L", new BigDecimal("1.0"), 1, 1, 1),
            null);

    assertThat(resolved).isSameAs(activeChild);
    verify(companyEntityLookup).lockActiveFinishedGood(company, 88L);
  }

  @Test
  void resolveTargetFinishedGood_rejectsInactiveChildSku() {
    PackingProductSupport support =
        new PackingProductSupport(companyEntityLookup, finishedGoodRepository);
    Company company = new Company();

    ProductionLog log = new ProductionLog();
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode("FG-PARENT");
    log.setProduct(product);

    when(companyEntityLookup.lockActiveFinishedGood(company, 88L))
        .thenThrow(new IllegalArgumentException("inactive"));

    assertThatThrownBy(
            () ->
                support.resolveTargetFinishedGood(
                    company,
                    log,
                    new PackingLineRequest(88L, 1, "1L", new BigDecimal("1.0"), 1, 1, 1),
                    null))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Child finished good not found: 88");
  }

  @Test
  void buildBulkToSizeJournalLines_requiresBulkInventoryAccount() {
    BulkPackingOrchestrator orchestrator =
        new BulkPackingOrchestrator(
            companyEntityLookup,
            finishedGoodRepository,
            finishedGoodBatchRegistrar,
            packingJournalBuilder);

    assertThatThrownBy(
            () ->
                orchestrator.buildBulkToSizeJournalLines(
                    null,
                    "BULK-1",
                    new BigDecimal("4"),
                    List.of(),
                    BigDecimal.ONE,
                    BulkPackCostSummary.empty()))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Bulk material account is missing");
  }

  @Test
  void buildBulkToSizeJournalLines_buildsJournalFromProvidedBulkValues() {
    BulkPackingOrchestrator orchestrator =
        new BulkPackingOrchestrator(
            companyEntityLookup,
            finishedGoodRepository,
            finishedGoodBatchRegistrar,
            packingJournalBuilder);

    FinishedGood fg = new FinishedGood();
    fg.setProductCode("FG-200-1L");
    fg.setValuationAccountId(230L);

    FinishedGoodBatch childBatch = new FinishedGoodBatch();
    childBatch.setFinishedGood(fg);
    childBatch.setSizeLabel("1L");
    childBatch.setQuantityTotal(new BigDecimal("3"));
    childBatch.setUnitCost(new BigDecimal("5"));

    JournalEntryRequest.JournalLineRequest expected =
        new JournalEntryRequest.JournalLineRequest(230L, "child", new BigDecimal("15"), BigDecimal.ZERO);
    when(packingJournalBuilder.buildBulkToSizePackingLines(
            eq(110L),
            eq(new BigDecimal("10.00")),
            eq(Map.of(510L, new BigDecimal("2.00"))),
            any()))
        .thenReturn(List.of(expected));

    List<JournalEntryRequest.JournalLineRequest> lines =
        orchestrator.buildBulkToSizeJournalLines(
            110L,
            "BULK-200",
            new BigDecimal("4"),
            List.of(childBatch),
            new BigDecimal("2.5"),
            new BulkPackCostSummary(
                new BigDecimal("2.00"), Map.of(510L, new BigDecimal("2.00")), Map.of()));

    assertThat(lines).containsExactly(expected);
  }

  @Test
  void extractSizeInLiters_fallsBackToUnitWhenLabelInvalid() {
    BulkPackingOrchestrator orchestrator =
        new BulkPackingOrchestrator(
            companyEntityLookup,
            finishedGoodRepository,
            finishedGoodBatchRegistrar,
            packingJournalBuilder);

    assertThat(orchestrator.extractSizeInLiters("UNKNOWN", "500ML"))
        .isEqualByComparingTo(new BigDecimal("0.500000"));
  }

  @Test
  void pack_returnsIdempotentResponseWithoutMutatingInventory() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            55L,
            List.of(new BulkPackRequest.PackLine(7L, BigDecimal.ONE, "1L", "L")),
            LocalDate.of(2026, 3, 24),
            null,
            null,
            "IDEMPOTENT-55");

    Company company = fixture.company("PACK-IDEMP");
    RawMaterialBatch bulkBatch = fixture.bulkBatch(company, "FG-55-BULK", "BULK-55", "9");
    BulkPackResponse cached =
        new BulkPackResponse(
            55L,
            "BULK-55",
            new BigDecimal("1"),
            new BigDecimal("8"),
            BigDecimal.ZERO,
            List.of(),
            901L,
            Instant.parse("2026-03-24T00:00:00Z"));

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 55L))
        .thenReturn(java.util.Optional.of(bulkBatch));
    when(fixture.bulkPackingReadService.resolveIdempotentPack(
            any(), any(), anyString()))
        .thenReturn(cached);

    BulkPackResponse response = fixture.service.pack(request);

    assertThat(response).isEqualTo(cached);
    verify(fixture.bulkPackingInventoryService, never()).consumeBulkInventory(any(), any(), anyString());
    verify(fixture.accountingFacade, never())
        .postPackingJournal(anyString(), any(), anyString(), any());
  }

  @Test
  void pack_rejectsBulkBatchFromAnotherCompany() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            56L,
            List.of(new BulkPackRequest.PackLine(7L, BigDecimal.ONE, "1L", "L")),
            LocalDate.of(2026, 3, 24),
            null,
            null,
            null);

    Company requester = fixture.company("PACK-OWN-A");
    Company owner = fixture.company("PACK-OWN-B");
    ReflectionTestUtils.setField(requester, "id", 1L);
    ReflectionTestUtils.setField(owner, "id", 2L);
    RawMaterialBatch foreignBatch = fixture.bulkBatch(owner, "FG-56-BULK", "BULK-56", "9");

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(requester);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(requester, 56L))
        .thenReturn(java.util.Optional.of(foreignBatch));

    assertThatThrownBy(() -> fixture.service.pack(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Batch does not belong to this company");
  }

  @Test
  void pack_rejectsNonBulkRawMaterialSku() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            57L,
            List.of(new BulkPackRequest.PackLine(7L, BigDecimal.ONE, "1L", "L")),
            LocalDate.of(2026, 3, 24),
            null,
            null,
            null);

    Company company = fixture.company("PACK-SKU");
    RawMaterialBatch nonBulkBatch = fixture.bulkBatch(company, "FG-57", "BULK-57", "9");

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 57L))
        .thenReturn(java.util.Optional.of(nonBulkBatch));

    assertThatThrownBy(() -> fixture.service.pack(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("is not a semi-finished bulk batch");
  }

  @Test
  void pack_rejectsNullRawMaterialSku() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            157L,
            List.of(new BulkPackRequest.PackLine(7L, BigDecimal.ONE, "1L", "L")),
            LocalDate.of(2026, 3, 24),
            null,
            null,
            null);

    Company company = fixture.company("PACK-SKU-NULL");
    RawMaterialBatch batch = fixture.bulkBatch(company, "FG-157-BULK", "BULK-157", "9");
    batch.getRawMaterial().setSku(null);

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 157L))
        .thenReturn(java.util.Optional.of(batch));

    assertThatThrownBy(() -> fixture.service.pack(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("is not a semi-finished bulk batch");
  }

  @Test
  void pack_rejectsWhenRequestedVolumeExceedsAvailableBulkQuantity() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            58L,
            List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("2"), "1L", "L")),
            LocalDate.of(2026, 3, 24),
            null,
            null,
            null);

    Company company = fixture.company("PACK-STOCK");
    RawMaterialBatch batch = fixture.bulkBatch(company, "FG-58-BULK", "BULK-58", "1");

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 58L))
        .thenReturn(java.util.Optional.of(batch));
    when(fixture.bulkPackingReadService.resolveIdempotentPack(any(), any(), anyString()))
        .thenReturn(null);
    when(fixture.bulkPackingOrchestrator.calculateTotalVolume(any()))
        .thenReturn(new BigDecimal("2"));

    assertThatThrownBy(() -> fixture.service.pack(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Insufficient bulk stock");
  }

  @Test
  void pack_successPathPostsJournalAndLinksMovements() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            59L,
            List.of(new BulkPackRequest.PackLine(77L, new BigDecimal("2"), "1L", "L")),
            LocalDate.of(2026, 3, 25),
            null,
            "ok",
            "PACK-59");

    Company company = fixture.company("PACK-HAPPY");
    RawMaterialBatch batch = fixture.bulkBatch(company, "FG-59-BULK", "BULK-59", "8");
    batch.setCostPerUnit(new BigDecimal("4"));
    batch.getRawMaterial().setInventoryAccountId(410L);

    FinishedGood fg = new FinishedGood();
    fg.setValuationAccountId(510L);
    fg.setProductCode("FG-59-1L");
    FinishedGoodBatch childBatch = new FinishedGoodBatch();
    childBatch.setFinishedGood(fg);
    childBatch.setSizeLabel("1L");
    childBatch.setQuantityTotal(new BigDecimal("2"));
    childBatch.setUnitCost(new BigDecimal("5"));

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 59L))
        .thenReturn(java.util.Optional.of(batch));
    when(fixture.bulkPackingReadService.resolveIdempotentPack(any(), any(), anyString()))
        .thenReturn(null);
    when(fixture.bulkPackingOrchestrator.calculateTotalVolume(any()))
        .thenReturn(new BigDecimal("2"));
    when(fixture.bulkPackingCostService.consumePackagingIfRequired(any(), any(), anyString()))
        .thenReturn(BulkPackCostSummary.empty());
    when(fixture.bulkPackingOrchestrator.resolveTotalPacks(any())).thenReturn(2);
    when(fixture.bulkPackingCostService.createCostingContext(any(), any(), anyInt()))
        .thenReturn(new BulkPackCostingContext(new BigDecimal("4"), BigDecimal.ZERO, Map.of(), false));
    when(fixture.bulkPackingCostService.resolveLinePackagingCostPerUnit(any(), any(), anyInt()))
        .thenReturn(BigDecimal.ZERO);
    when(fixture.bulkPackingOrchestrator.createChildBatch(any(), any(), any(), any(), any(), any(), anyString()))
        .thenReturn(childBatch);
    when(fixture.bulkPackingOrchestrator.buildBulkToSizeJournalLines(
            any(), anyString(), any(), any(), any(), any()))
        .thenReturn(
            List.of(new JournalEntryRequest.JournalLineRequest(410L, "line", new BigDecimal("8"), BigDecimal.ZERO)));
    when(fixture.accountingFacade.postPackingJournal(anyString(), any(), anyString(), any()))
        .thenReturn(
            new JournalEntryDto(
                700L,
                null,
                "REF-59",
                LocalDate.of(2026, 3, 25),
                "posted",
                "POSTED",
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
                null));

    BulkPackResponse response = fixture.service.pack(request);

    assertThat(response.journalEntryId()).isEqualTo(700L);
    verify(fixture.bulkPackingInventoryService).consumeBulkInventory(any(), any(), anyString());
    verify(fixture.packingJournalLinkHelper)
        .linkPackagingMovementsToJournal(any(), anyString(), eq(700L));
  }

  @Test
  void pack_defaultsNullBulkUnitCostToZeroInCostingAndJournal() {
    PackServiceFixture fixture = new PackServiceFixture();
    BulkPackRequest request =
        new BulkPackRequest(
            160L,
            List.of(new BulkPackRequest.PackLine(77L, new BigDecimal("2"), "1L", "L")),
            LocalDate.of(2026, 3, 25),
            null,
            "ok",
            "PACK-160");

    Company company = fixture.company("PACK-NULL-COST");
    RawMaterialBatch batch = fixture.bulkBatch(company, "FG-160-BULK", "BULK-160", "8");
    batch.setCostPerUnit(null);
    batch.getRawMaterial().setInventoryAccountId(410L);

    FinishedGood fg = new FinishedGood();
    fg.setValuationAccountId(510L);
    fg.setProductCode("FG-160-1L");
    FinishedGoodBatch childBatch = new FinishedGoodBatch();
    childBatch.setFinishedGood(fg);
    childBatch.setSizeLabel("1L");
    childBatch.setQuantityTotal(new BigDecimal("2"));
    childBatch.setUnitCost(new BigDecimal("5"));

    when(fixture.companyContextService.requireCurrentCompany()).thenReturn(company);
    when(fixture.rawMaterialBatchRepository.lockByRawMaterialCompanyAndId(company, 160L))
        .thenReturn(java.util.Optional.of(batch));
    when(fixture.bulkPackingReadService.resolveIdempotentPack(any(), any(), anyString()))
        .thenReturn(null);
    when(fixture.bulkPackingOrchestrator.calculateTotalVolume(any()))
        .thenReturn(new BigDecimal("2"));
    when(fixture.bulkPackingCostService.consumePackagingIfRequired(any(), any(), anyString()))
        .thenReturn(BulkPackCostSummary.empty());
    when(fixture.bulkPackingOrchestrator.resolveTotalPacks(any())).thenReturn(2);
    when(fixture.bulkPackingCostService.createCostingContext(any(), any(), anyInt()))
        .thenReturn(new BulkPackCostingContext(BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), false));
    when(fixture.bulkPackingCostService.resolveLinePackagingCostPerUnit(any(), any(), anyInt()))
        .thenReturn(BigDecimal.ZERO);
    when(fixture.bulkPackingOrchestrator.createChildBatch(
            any(), any(), any(), any(), any(), any(), anyString()))
        .thenReturn(childBatch);
    when(fixture.bulkPackingOrchestrator.buildBulkToSizeJournalLines(
            any(), anyString(), any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    410L, "line", new BigDecimal("8"), BigDecimal.ZERO)));
    when(fixture.accountingFacade.postPackingJournal(anyString(), any(), anyString(), any()))
        .thenReturn(
            new JournalEntryDto(
                701L,
                null,
                "REF-160",
                LocalDate.of(2026, 3, 25),
                "posted",
                "POSTED",
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
                null));

    fixture.service.pack(request);

    verify(fixture.bulkPackingCostService)
        .createCostingContext(eq(BigDecimal.ZERO), any(), eq(2));
    verify(fixture.bulkPackingOrchestrator)
        .buildBulkToSizeJournalLines(
            eq(410L), eq("BULK-160"), eq(BigDecimal.ZERO), any(), any(), any());
  }

  private static final class PackServiceFixture {
    private final CompanyContextService companyContextService = mock(CompanyContextService.class);
    private final RawMaterialBatchRepository rawMaterialBatchRepository = mock(RawMaterialBatchRepository.class);
    private final AccountingFacade accountingFacade = mock(AccountingFacade.class);
    private final CompanyClock companyClock = mock(CompanyClock.class);
    private final BulkPackingOrchestrator bulkPackingOrchestrator = mock(BulkPackingOrchestrator.class);
    private final BulkPackingCostService bulkPackingCostService = mock(BulkPackingCostService.class);
    private final BulkPackingInventoryService bulkPackingInventoryService = mock(BulkPackingInventoryService.class);
    private final BulkPackingReadService bulkPackingReadService = mock(BulkPackingReadService.class);
    private final PackingJournalLinkHelper packingJournalLinkHelper = mock(PackingJournalLinkHelper.class);

    private final BulkPackingService service =
        new BulkPackingService(
            companyContextService,
            rawMaterialBatchRepository,
            accountingFacade,
            companyClock,
            bulkPackingOrchestrator,
            bulkPackingCostService,
            bulkPackingInventoryService,
            bulkPackingReadService,
            packingJournalLinkHelper);

    private Company company(String code) {
      Company company = new Company();
      company.setCode(code);
      ReflectionTestUtils.setField(company, "id", 1L);
      return company;
    }

    private RawMaterialBatch bulkBatch(
        Company company, String sku, String batchCode, String quantity) {
      RawMaterial material = new RawMaterial();
      material.setCompany(company);
      material.setSku(sku);
      material.setCurrentStock(new BigDecimal(quantity));
      material.setInventoryAccountId(410L);

      RawMaterialBatch batch = new RawMaterialBatch();
      ReflectionTestUtils.setField(batch, "id", Long.valueOf(batchCode.replaceAll("\\D", "")));
      batch.setRawMaterial(material);
      batch.setBatchCode(batchCode);
      batch.setQuantity(new BigDecimal(quantity));
      batch.setCostPerUnit(new BigDecimal("4"));
      return batch;
    }
  }
}
