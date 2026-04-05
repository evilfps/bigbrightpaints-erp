package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialIntakeRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class RawMaterialServiceReceiptContextTest {

  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private RawMaterialBatchRepository batchRepository;
  @Mock private RawMaterialMovementRepository movementRepository;
  @Mock private RawMaterialAdjustmentRepository rawMaterialAdjustmentRepository;
  @Mock private RawMaterialIntakeRepository rawMaterialIntakeRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionProductRepository productionProductRepository;
  @Mock private ProductionBrandRepository productionBrandRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private BatchNumberService batchNumberService;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private AuditService auditService;
  @Mock private Environment environment;

  private RawMaterialService rawMaterialService;
  private Company company;
  private RawMaterial material;
  private Supplier supplier;

  @BeforeEach
  void setUp() {
    rawMaterialService =
        new RawMaterialService(
            rawMaterialRepository,
            batchRepository,
            movementRepository,
            rawMaterialAdjustmentRepository,
            rawMaterialIntakeRepository,
            companyContextService,
            productionProductRepository,
            productionBrandRepository,
            accountingFacade,
            batchNumberService,
            referenceNumberService,
            companyClock,
            inventoryLookupService,
            purchasingLookupService,
            auditService,
            environment,
            new ResourcelessTransactionManager(),
            false);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("ACME");
    company.setTimezone("UTC");
    company.setDefaultInventoryAccountId(99L);

    material = new RawMaterial();
    ReflectionTestUtils.setField(material, "id", 20L);
    material.setCompany(company);
    material.setName("Resin");
    material.setSku("RM-20");
    material.setUnitType("KG");
    material.setCurrentStock(BigDecimal.ZERO);
    material.setInventoryAccountId(99L);

    supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 10L);
    supplier.setCompany(company);
    supplier.setCode("SUP-10");
    supplier.setName("Supplier 10");

    ProductionBrand rawMaterialBrand = new ProductionBrand();
    rawMaterialBrand.setCompany(company);
    rawMaterialBrand.setCode("RAW-MATERIALS");
    rawMaterialBrand.setName("Raw Materials");

    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(inventoryLookupService.lockActiveRawMaterial(company, 20L)).thenReturn(material);
    lenient()
        .when(rawMaterialRepository.lockByCompanyAndId(company, 20L))
        .thenReturn(Optional.of(material));
    lenient().when(purchasingLookupService.requireSupplier(company, 10L)).thenReturn(supplier);
    lenient()
        .when(rawMaterialRepository.save(any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(
            productionProductRepository.findByCompanyAndSkuCode(
                any(Company.class), any(String.class)))
        .thenReturn(Optional.empty());
    lenient()
        .when(productionProductRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "RAW-MATERIALS"))
        .thenReturn(Optional.of(rawMaterialBrand));
    lenient()
        .when(productionBrandRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(batchRepository.existsByRawMaterialAndBatchCode(material, "BATCH-1"))
        .thenReturn(false);
    lenient()
        .when(batchRepository.save(any(RawMaterialBatch.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(movementRepository.save(any(RawMaterialMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(companyClock.now(company)).thenReturn(Instant.parse("2026-03-08T00:00:00Z"));
    lenient().when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
  }

  @Test
  @DisplayName("recordReceipt keeps GRN stock-only contexts free of AP posting requirements")
  void recordReceipt_grnContextSkipsPayableAndJournalPosting() {
    RawMaterialBatchRequest request =
        new RawMaterialBatchRequest(
            "BATCH-1",
            new BigDecimal("4.0000"),
            "KG",
            new BigDecimal("5.00"),
            10L,
            null,
            null,
            "GRN receipt");

    RawMaterialService.ReceiptResult result =
        rawMaterialService.recordReceipt(
            20L,
            request,
            new RawMaterialService.ReceiptContext(
                InventoryReference.GOODS_RECEIPT, "GRN-001", "Goods receipt GRN-001", false));

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(movementRepository).save(movementCaptor.capture());
    RawMaterialMovement savedMovement = movementCaptor.getValue();
    assertThat(savedMovement.getReferenceType()).isEqualTo(InventoryReference.GOODS_RECEIPT);
    assertThat(savedMovement.getReferenceId()).isEqualTo("GRN-001");
    assertThat(savedMovement.getMovementType()).isEqualTo("RECEIPT");
    assertThat(savedMovement.getJournalEntryId()).isNull();
    assertThat(result.journalEntryId()).isNull();
    assertThat(result.batch()).isNotNull();
    verifyNoInteractions(accountingFacade);
  }

  @Test
  @DisplayName(
      "recordReceipt keeps purchase-posting contexts fail-closed when payable account is missing")
  void recordReceipt_defaultPurchaseContextRequiresPayableAccount() {
    RawMaterialBatchRequest request =
        new RawMaterialBatchRequest(
            "BATCH-1",
            new BigDecimal("4.0000"),
            "KG",
            new BigDecimal("5.00"),
            10L,
            null,
            null,
            "Purchase receipt");

    assertThatThrownBy(() -> rawMaterialService.recordReceipt(20L, request, null))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
              assertThat(ex).hasMessage("Supplier Supplier 10 is missing a payable account");
            });

    verify(rawMaterialRepository, never()).save(any(RawMaterial.class));
    verify(batchRepository, never()).save(any(RawMaterialBatch.class));
    verify(movementRepository, never()).save(any(RawMaterialMovement.class));
    verifyNoInteractions(accountingFacade);
  }

  @Test
  @DisplayName(
      "recordReceipt defaults purchase contexts to batch-linked AP posting when payable account"
          + " exists")
  void recordReceipt_defaultPurchaseContextPostsJournalAndUsesBatchReference() {
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 301L);
    supplier.setPayableAccount(payable);
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 8));

    JournalEntryDto entry =
        new JournalEntryDto(
            501L,
            null,
            "BATCH-1",
            LocalDate.of(2026, 3, 8),
            "Raw material batch BATCH-1",
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
            null);
    when(accountingFacade.postPurchaseJournal(
            eq(10L),
            eq("BATCH-1"),
            eq(LocalDate.of(2026, 3, 8)),
            eq("Raw material batch BATCH-1"),
            any(),
            org.mockito.ArgumentMatchers.argThat(
                total -> total != null && total.compareTo(new BigDecimal("20.00")) == 0)))
        .thenReturn(entry);

    RawMaterialBatchRequest request =
        new RawMaterialBatchRequest(
            "BATCH-1",
            new BigDecimal("4.0000"),
            "KG",
            new BigDecimal("5.00"),
            10L,
            null,
            null,
            "Purchase receipt");

    RawMaterialService.ReceiptResult result = rawMaterialService.recordReceipt(20L, request, null);

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(movementRepository, org.mockito.Mockito.times(2)).save(movementCaptor.capture());
    assertThat(movementCaptor.getAllValues().getFirst().getReferenceType())
        .isEqualTo(InventoryReference.RAW_MATERIAL_PURCHASE);
    assertThat(movementCaptor.getAllValues().getFirst().getReferenceId()).isEqualTo("BATCH-1");
    assertThat(movementCaptor.getAllValues().getLast().getJournalEntryId()).isEqualTo(501L);
    assertThat(result.batch().getBatchCode()).isEqualTo("BATCH-1");
    assertThat(result.journalEntryId()).isEqualTo(501L);
  }

  @Test
  void createRawMaterial_mapsPackagingAliasesAndDefaultsInventoryAccount() {
    RawMaterialRequest request =
        new RawMaterialRequest(
            "Bottle",
            "PKG-001",
            "pkg",
            "PCS",
            new BigDecimal("2"),
            new BigDecimal("1"),
            new BigDecimal("5"),
            null,
            null);

    rawMaterialService.createRawMaterial(request);

    ArgumentCaptor<RawMaterial> materialCaptor = ArgumentCaptor.forClass(RawMaterial.class);
    verify(rawMaterialRepository).save(materialCaptor.capture());
    assertThat(materialCaptor.getValue().getMaterialType()).isEqualTo(MaterialType.PACKAGING);
    assertThat(materialCaptor.getValue().getInventoryAccountId()).isEqualTo(99L);
  }

  @Test
  void updateRawMaterial_defaultsBlankMaterialTypeToProduction() {
    material.setMaterialType(MaterialType.PACKAGING);

    rawMaterialService.updateRawMaterial(
        20L,
        new RawMaterialRequest(
            "Resin",
            "RM-20",
            null,
            "KG",
            new BigDecimal("5"),
            new BigDecimal("2"),
            new BigDecimal("8"),
            77L,
            null));

    ArgumentCaptor<RawMaterial> materialCaptor = ArgumentCaptor.forClass(RawMaterial.class);
    verify(rawMaterialRepository).save(materialCaptor.capture());
    assertThat(materialCaptor.getValue().getMaterialType()).isEqualTo(MaterialType.PRODUCTION);
    assertThat(materialCaptor.getValue().getInventoryAccountId()).isEqualTo(77L);
  }

  @Test
  void updateRawMaterial_mapsRawMaterialAliasToProductionType() {
    rawMaterialService.updateRawMaterial(
        20L,
        new RawMaterialRequest(
            "Resin",
            "RM-20",
            "RM",
            "KG",
            new BigDecimal("5"),
            new BigDecimal("2"),
            new BigDecimal("8"),
            77L,
            null));

    ArgumentCaptor<RawMaterial> materialCaptor = ArgumentCaptor.forClass(RawMaterial.class);
    verify(rawMaterialRepository).save(materialCaptor.capture());
    assertThat(materialCaptor.getValue().getMaterialType()).isEqualTo(MaterialType.PRODUCTION);
  }

  @Test
  void createRawMaterial_rejectsUnsupportedMaterialType() {
    assertThatThrownBy(
            () ->
                rawMaterialService.createRawMaterial(
                    new RawMaterialRequest(
                        "Mystery",
                        "RM-ERR",
                        "unknown-type",
                        "KG",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.TEN,
                        99L,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Unsupported materialType");
  }

  @Test
  @DisplayName(
      "recordReceipt honors explicit posting contexts and restores inventory account from company"
          + " defaults")
  void recordReceipt_explicitPostingContextUsesReferenceAndDefaultInventoryAccount() {
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 301L);
    supplier.setPayableAccount(payable);
    material.setCurrentStock(null);
    material.setInventoryAccountId(null);
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 8));

    JournalEntryDto entry =
        new JournalEntryDto(
            502L,
            null,
            "BATCH-1",
            LocalDate.of(2026, 3, 8),
            "Purchase receipt PO-77",
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
            null);
    when(accountingFacade.postPurchaseJournal(
            eq(10L),
            eq("BATCH-1"),
            eq(LocalDate.of(2026, 3, 8)),
            eq("Purchase receipt PO-77"),
            any(),
            org.mockito.ArgumentMatchers.argThat(
                total -> total != null && total.compareTo(new BigDecimal("20.00")) == 0)))
        .thenReturn(entry);

    RawMaterialBatchRequest request =
        new RawMaterialBatchRequest(
            "BATCH-1",
            new BigDecimal("4.0000"),
            "KG",
            new BigDecimal("5.00"),
            10L,
            null,
            null,
            "Purchase receipt");

    RawMaterialService.ReceiptResult result =
        rawMaterialService.recordReceipt(
            20L,
            request,
            new RawMaterialService.ReceiptContext(
                InventoryReference.RAW_MATERIAL_PURCHASE, "PO-77", "Purchase receipt PO-77", true));

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(movementRepository, org.mockito.Mockito.times(2)).save(movementCaptor.capture());
    assertThat(movementCaptor.getAllValues().getFirst().getReferenceId()).isEqualTo("PO-77");
    assertThat(movementCaptor.getAllValues().getLast().getJournalEntryId()).isEqualTo(502L);
    assertThat(material.getInventoryAccountId()).isEqualTo(99L);
    assertThat(material.getCurrentStock()).isEqualByComparingTo("4.0000");
    assertThat(result.journalEntryId()).isEqualTo(502L);
  }

  @Test
  @DisplayName("recordReceipt fails closed when no inventory account can be resolved")
  void recordReceipt_requiresInventoryAccountEvenForStockOnlyContext() {
    material.setInventoryAccountId(null);
    company.setDefaultInventoryAccountId(null);

    RawMaterialBatchRequest request =
        new RawMaterialBatchRequest(
            "BATCH-1",
            new BigDecimal("4.0000"),
            "KG",
            new BigDecimal("5.00"),
            10L,
            null,
            null,
            "GRN receipt");

    assertThatThrownBy(
            () ->
                rawMaterialService.recordReceipt(
                    20L,
                    request,
                    new RawMaterialService.ReceiptContext(
                        InventoryReference.GOODS_RECEIPT,
                        "GRN-001",
                        "Goods receipt GRN-001",
                        false)))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
              assertThat(ex).hasMessage("Raw material Resin is missing an inventory account");
            });

    verify(rawMaterialRepository, never()).save(any(RawMaterial.class));
    verify(batchRepository, never()).save(any(RawMaterialBatch.class));
    verify(movementRepository, never()).save(any(RawMaterialMovement.class));
    verifyNoInteractions(accountingFacade);
  }

  @Test
  @DisplayName(
      "ensureReceiptAccounts fails closed when a material has no company default to recover from")
  void ensureReceiptAccounts_requiresInventoryAccountWithoutCompanyFallback() {
    RawMaterial orphanMaterial = new RawMaterial();
    orphanMaterial.setName("Orphan Resin");
    orphanMaterial.setInventoryAccountId(null);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    rawMaterialService, "ensureReceiptAccounts", orphanMaterial, supplier, false))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
              assertThat(ex)
                  .hasMessage("Raw material Orphan Resin is missing an inventory account");
            });
  }
}
