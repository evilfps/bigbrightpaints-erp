package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchaseInvoiceEngineLifecycleTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private RawMaterialPurchaseRepository purchaseRepository;
  @Mock private PurchaseOrderRepository purchaseOrderRepository;
  @Mock private GoodsReceiptRepository goodsReceiptRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialService rawMaterialService;
  @Mock private RawMaterialMovementRepository movementRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private com.bigbrightpaints.erp.core.util.CompanyClock companyClock;
  @Mock private GstService gstService;
  @Mock private PurchaseOrderService purchaseOrderService;

  private PurchaseInvoiceEngine purchaseInvoiceEngine;
  private Company company;
  private Supplier supplier;
  private RawMaterial rawMaterial;
  private PurchaseOrder purchaseOrder;
  private GoodsReceipt goodsReceipt;

  @BeforeEach
  void setUp() {
    purchaseInvoiceEngine =
        new PurchaseInvoiceEngine(
            companyContextService,
            purchaseRepository,
            purchaseOrderRepository,
            goodsReceiptRepository,
            rawMaterialRepository,
            rawMaterialBatchRepository,
            rawMaterialService,
            movementRepository,
            accountingFacade,
            purchasingLookupService,
            inventoryLookupService,
            accountingLookupService,
            referenceNumberService,
            companyClock,
            gstService,
            new PurchaseResponseMapper(),
            new PurchaseTaxPolicy());
    purchaseInvoiceEngine.setPurchaseOrderService(purchaseOrderService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setStateCode("KA");

    Account payableAccount = new Account();
    ReflectionTestUtils.setField(payableAccount, "id", 800L);

    supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 10L);
    supplier.setCompany(company);
    supplier.setCode("SUP-10");
    supplier.setName("Supplier 10");
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setStateCode("KA");
    supplier.setPayableAccount(payableAccount);

    rawMaterial = new RawMaterial();
    ReflectionTestUtils.setField(rawMaterial, "id", 20L);
    rawMaterial.setCompany(company);
    rawMaterial.setName("Resin");
    rawMaterial.setSku("RM-20");
    rawMaterial.setUnitType("KG");
    rawMaterial.setInventoryAccountId(200L);
    rawMaterial.setInventoryType(InventoryType.STANDARD);
    rawMaterial.setCurrentStock(BigDecimal.valueOf(100));

    purchaseOrder = new PurchaseOrder();
    ReflectionTestUtils.setField(purchaseOrder, "id", 30L);
    purchaseOrder.setCompany(company);
    purchaseOrder.setSupplier(supplier);
    purchaseOrder.setOrderNumber("PO-30");
    purchaseOrder.setOrderDate(LocalDate.of(2026, 3, 1));
    purchaseOrder.setStatus(PurchaseOrderStatus.FULLY_RECEIVED);

    PurchaseOrderLine orderLine = new PurchaseOrderLine();
    orderLine.setPurchaseOrder(purchaseOrder);
    orderLine.setRawMaterial(rawMaterial);
    orderLine.setQuantity(new BigDecimal("10.0000"));
    orderLine.setUnit("KG");
    orderLine.setCostPerUnit(new BigDecimal("12.50"));
    orderLine.setLineTotal(new BigDecimal("125.00"));
    purchaseOrder.getLines().add(orderLine);

    goodsReceipt = new GoodsReceipt();
    ReflectionTestUtils.setField(goodsReceipt, "id", 40L);
    goodsReceipt.setCompany(company);
    goodsReceipt.setSupplier(supplier);
    goodsReceipt.setPurchaseOrder(purchaseOrder);
    goodsReceipt.setReceiptNumber("GRN-40");
    goodsReceipt.setReceiptDate(LocalDate.of(2026, 3, 2));
    goodsReceipt.setStatus(GoodsReceiptStatus.RECEIVED);

    GoodsReceiptLine receiptLine = new GoodsReceiptLine();
    receiptLine.setGoodsReceipt(goodsReceipt);
    receiptLine.setRawMaterial(rawMaterial);
    receiptLine.setQuantity(new BigDecimal("10.0000"));
    receiptLine.setUnit("KG");
    receiptLine.setCostPerUnit(new BigDecimal("12.50"));
    receiptLine.setLineTotal(new BigDecimal("125.00"));
    RawMaterialBatch batch = new RawMaterialBatch();
    ReflectionTestUtils.setField(batch, "id", 900L);
    batch.setRawMaterial(rawMaterial);
    batch.setBatchCode("RM-20-LOT-1");
    batch.setQuantity(new BigDecimal("10.0000"));
    batch.setUnit("KG");
    batch.setCostPerUnit(new BigDecimal("12.50"));
    receiptLine.setRawMaterialBatch(batch);
    goodsReceipt.getLines().add(receiptLine);

    RawMaterialMovement movement = new RawMaterialMovement();
    ReflectionTestUtils.setField(movement, "id", 500L);
    movement.setRawMaterial(rawMaterial);
    movement.setReferenceType("GOODS_RECEIPT");
    movement.setReferenceId("GRN-40");
    movement.setMovementType("RECEIPT");
    movement.setQuantity(new BigDecimal("10.0000"));
    movement.setUnitCost(new BigDecimal("12.50"));

    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(purchasingLookupService.requireSupplier(company, 10L)).thenReturn(supplier);
    lenient()
        .when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-40"))
        .thenReturn(Optional.empty());
    lenient()
        .when(goodsReceiptRepository.lockByCompanyAndId(company, 40L))
        .thenReturn(Optional.of(goodsReceipt));
    lenient()
        .when(purchaseRepository.findByCompanyAndGoodsReceipt(company, goodsReceipt))
        .thenReturn(Optional.empty());
    lenient()
        .when(inventoryLookupService.lockActiveRawMaterial(company, 20L))
        .thenReturn(rawMaterial);
    lenient()
        .when(referenceNumberService.purchaseReference(company, supplier, "INV-40"))
        .thenReturn("RMP-SUP10-INV40");
    lenient()
        .when(gstService.splitTaxAmount(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new GstService.GstBreakdown(
                    invocation.getArgument(0),
                    BigDecimal.ZERO,
                    invocation.getArgument(1),
                    BigDecimal.ZERO,
                    GstService.TaxType.INTRA_STATE));
    lenient()
        .when(gstService.normalizeStateCode(any()))
        .thenAnswer(
            invocation -> {
              String raw = invocation.getArgument(0);
              return raw == null ? null : raw.trim().toUpperCase(java.util.Locale.ROOT);
            });

    lenient()
        .when(
            movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "GOODS_RECEIPT", "GRN-40"))
        .thenReturn(List.of(movement));

    JournalEntryDto journalEntryDto =
        new JournalEntryDto(
            700L,
            null,
            "RMP-SUP10-INV40",
            LocalDate.of(2026, 3, 2),
            "memo",
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
            Instant.now(),
            Instant.now(),
            Instant.now(),
            "tester",
            "tester",
            "tester");
    lenient()
        .when(
            accountingFacade.postPurchaseJournal(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(journalEntryDto);

    JournalEntry linkedEntry = new JournalEntry();
    ReflectionTestUtils.setField(linkedEntry, "id", 700L);
    linkedEntry.setStatus("POSTED");
    linkedEntry.setReferenceNumber("RMP-SUP10-INV40");
    lenient()
        .when(accountingLookupService.requireJournalEntry(company, 700L))
        .thenReturn(linkedEntry);

    lenient()
        .when(purchaseRepository.save(any(RawMaterialPurchase.class)))
        .thenAnswer(
            invocation -> {
              RawMaterialPurchase purchase = invocation.getArgument(0);
              ReflectionTestUtils.setField(purchase, "id", 600L);
              ReflectionTestUtils.setField(
                  purchase, "createdAt", Instant.parse("2026-03-02T00:00:00Z"));
              return purchase;
            });
    lenient().when(goodsReceiptRepository.save(goodsReceipt)).thenReturn(goodsReceipt);
    lenient().when(purchaseOrderRepository.save(purchaseOrder)).thenReturn(purchaseOrder);
  }

  @Test
  @DisplayName("createPurchase replays existing purchase for duplicate canonical Idempotency-Key")
  void createPurchase_replaysExistingPurchaseForDuplicateIdempotencyKey() {
    RawMaterialPurchase existing = new RawMaterialPurchase();
    ReflectionTestUtils.setField(existing, "id", 611L);
    existing.setCompany(company);
    existing.setSupplier(supplier);
    existing.setInvoiceNumber("INV-40");
    existing.setInvoiceDate(LocalDate.of(2026, 3, 2));
    existing.setMemo("invoice");
    existing.setTotalAmount(new BigDecimal("125.00"));
    existing.setTaxAmount(BigDecimal.ZERO);
    existing.setOutstandingAmount(new BigDecimal("125.00"));
    existing.setStatus("POSTED");
    existing.setIdempotencyKey("purchase-idem-40");
    RawMaterialPurchaseLine existingLine = new RawMaterialPurchaseLine();
    existingLine.setPurchase(existing);
    existingLine.setRawMaterial(rawMaterial);
    existingLine.setQuantity(new BigDecimal("10.0000"));
    existingLine.setUnit("KG");
    existingLine.setCostPerUnit(new BigDecimal("12.50"));
    existingLine.setLineTotal(new BigDecimal("125.00"));
    existing.getLines().add(existingLine);
    when(purchaseRepository.findWithLinesByCompanyAndIdempotencyKey(company, "purchase-idem-40"))
        .thenReturn(Optional.of(existing));
    when(purchaseRepository.save(existing)).thenReturn(existing);

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    RawMaterialPurchaseResponse replay =
        purchaseInvoiceEngine.createPurchase(request, " purchase-idem-40 ");

    assertThat(replay.id()).isEqualTo(existing.getId());
    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(purchaseRepository, never()).lockByCompanyAndInvoiceNumberIgnoreCase(any(), any());
  }

  @Test
  @DisplayName("createPurchase fails closed when canonical Idempotency-Key payload drifts")
  void createPurchase_rejectsIdempotencyPayloadDrift() {
    RawMaterialPurchase existing = new RawMaterialPurchase();
    existing.setCompany(company);
    existing.setInvoiceNumber("INV-40");
    existing.setIdempotencyKey("purchase-idem-drift");
    existing.setIdempotencyHash("stored-signature");
    when(purchaseRepository.findWithLinesByCompanyAndIdempotencyKey(company, "purchase-idem-drift"))
        .thenReturn(Optional.of(existing));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request, "purchase-idem-drift"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            throwable -> {
              ApplicationException ex = (ApplicationException) throwable;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
            })
        .hasMessageContaining("Idempotency key already used with different payload");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "createPurchase transitions purchase order FULLY_RECEIVED -> INVOICED -> CLOSED when all GRNs"
          + " invoiced")
  void createPurchase_transitionsToInvoicedThenClosed() {
    when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
        .thenReturn(List.of(goodsReceipt));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            null,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    RawMaterialPurchaseResponse response = purchaseInvoiceEngine.createPurchase(request);

    assertThat(response.id()).isEqualTo(600L);
    assertThat(goodsReceipt.getStatusEnum()).isEqualTo(GoodsReceiptStatus.INVOICED);

    ArgumentCaptor<PurchaseOrderStatus> statusCaptor =
        ArgumentCaptor.forClass(PurchaseOrderStatus.class);
    verify(purchaseOrderService, times(2))
        .transitionStatus(eq(purchaseOrder), statusCaptor.capture(), any(), any());
    assertThat(statusCaptor.getAllValues())
        .containsExactly(PurchaseOrderStatus.INVOICED, PurchaseOrderStatus.CLOSED);
  }

  @Test
  void createPurchase_rejectsUnknownRawMaterial() {
    when(inventoryLookupService.lockActiveRawMaterial(company, 20L))
        .thenThrow(new IllegalArgumentException("Raw material not found: id=20"));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-41",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Raw material not found");
  }

  @Test
  @DisplayName("createPurchase transitions purchase order to INVOICED when more GRNs remain")
  void createPurchase_transitionsToInvoicedWhenNotAllGrnsInvoiced() {
    GoodsReceipt pending = new GoodsReceipt();
    pending.setStatus(GoodsReceiptStatus.RECEIVED);
    when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
        .thenReturn(List.of(goodsReceipt, pending));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    purchaseInvoiceEngine.createPurchase(request);

    verify(purchaseOrderService)
        .transitionStatus(eq(purchaseOrder), eq(PurchaseOrderStatus.INVOICED), any(), any());
    verify(purchaseOrderService, times(1)).transitionStatus(any(), any(), any(), any());
  }

  @Test
  void listPurchases_usesBatchMapperPathWithoutSupplierFilter() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    ReflectionTestUtils.setField(purchase, "id", 601L);
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber("PINV-601");
    purchase.setStatus("POSTED");
    RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
    line.setPurchase(purchase);
    line.setRawMaterial(rawMaterial);
    line.setQuantity(new BigDecimal("10.0000"));
    line.setUnit("KG");
    line.setCostPerUnit(new BigDecimal("12.50"));
    line.setLineTotal(new BigDecimal("125.00"));
    purchase.getLines().add(line);

    when(purchaseRepository.findByCompanyWithLinesOrderByInvoiceDateDesc(company))
        .thenReturn(List.of(purchase));

    List<RawMaterialPurchaseResponse> responses = purchaseInvoiceEngine.listPurchases();

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).invoiceNumber()).isEqualTo("PINV-601");
    verify(purchaseRepository).findByCompanyWithLinesOrderByInvoiceDateDesc(company);
    verifyNoMoreInteractions(purchaseRepository);
  }

  @Test
  @DisplayName("createPurchase rejects suppliers that are no longer active")
  void createPurchase_rejectsSuspendedSupplierWithExplicitReason() {
    supplier.setStatus(SupplierStatus.SUSPENDED);

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("suspended")
        .hasMessageContaining("reference only");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "createPurchase links AP journal to receipt movement without replaying stock receipt")
  void createPurchase_linksJournalToReceiptMovementWithoutRestocking() {
    when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
        .thenReturn(List.of(goodsReceipt));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    RawMaterialPurchaseResponse response = purchaseInvoiceEngine.createPurchase(request);

    assertThat(response.id()).isEqualTo(600L);
    assertThat(response.lifecycle().workflowStatus()).isEqualTo("POSTED");
    assertThat(response.lifecycle().accountingStatus()).isEqualTo("POSTED");
    assertThat(response.linkedReferences())
        .extracting(
            LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
        .contains(
            org.assertj.core.groups.Tuple.tuple("PURCHASE_ORDER", "PURCHASE_ORDER"),
            org.assertj.core.groups.Tuple.tuple("GOODS_RECEIPT", "GOODS_RECEIPT"),
            org.assertj.core.groups.Tuple.tuple("ACCOUNTING_ENTRY", "JOURNAL_ENTRY"));
    verify(movementRepository)
        .saveAll(
            org.mockito.ArgumentMatchers.argThat(
                movements -> {
                  java.util.Iterator<RawMaterialMovement> iterator = movements.iterator();
                  if (!iterator.hasNext()) {
                    return false;
                  }
                  RawMaterialMovement movement = iterator.next();
                  return !iterator.hasNext()
                      && movement.getId().equals(500L)
                      && movement.getJournalEntryId() != null
                      && movement.getJournalEntryId().equals(700L);
                }));
    verify(goodsReceiptRepository).save(goodsReceipt);
    verify(rawMaterialBatchRepository, never()).save(any(RawMaterialBatch.class));
    verifyNoInteractions(rawMaterialService);
  }

  @Test
  @DisplayName("createPurchase still records AP truth when journal facade returns no linked entry")
  void createPurchase_allowsMissingJournalEntryLink() {
    when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
        .thenReturn(List.of(goodsReceipt));
    when(accountingFacade.postPurchaseJournal(
            any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(null);

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    RawMaterialPurchaseResponse response = purchaseInvoiceEngine.createPurchase(request);

    assertThat(response.id()).isEqualTo(600L);
    assertThat(goodsReceipt.getStatusEnum()).isEqualTo(GoodsReceiptStatus.INVOICED);
    verify(movementRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("createPurchase rejects missing company state metadata for GST decisioning")
  void createPurchase_rejectsMissingCompanyStateMetadataForGstDecisioning() {
    company.setStateCode(null);
    supplier.setStateCode(null);
    supplier.setGstNumber("27ABCDE1234F1Z5");
    when(gstService.splitTaxAmount(any(), any(), eq((String) null), eq("27")))
        .thenThrow(
            new ApplicationException(
                ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                "State codes are required for GST decisioning"));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
              assertThat(ex).hasMessageContaining("State codes are required for GST decisioning");
            });

    verify(gstService).splitTaxAmount(any(), any(), eq((String) null), eq("27"));
    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("splitTaxAmountSafe falls back to IGST when splitter returns null for inter-state")
  void splitTaxAmountSafe_fallsBackToInterStateIgstWhenSplitterReturnsNull() {
    when(gstService.splitTaxAmount(new BigDecimal("100.00"), new BigDecimal("18.00"), "KA", "MH"))
        .thenReturn(null);
    when(gstService.resolveTaxType("KA", "MH", false)).thenReturn(GstService.TaxType.INTER_STATE);

    GstService.GstBreakdown breakdown =
        ReflectionTestUtils.invokeMethod(
            purchaseInvoiceEngine,
            "splitTaxAmountSafe",
            new BigDecimal("100.00"),
            new BigDecimal("18.00"),
            "KA",
            "MH");

    assertThat(breakdown).isNotNull();
    assertThat(breakdown.taxType()).isEqualTo(GstService.TaxType.INTER_STATE);
    assertThat(breakdown.cgst()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(breakdown.sgst()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(breakdown.igst()).isEqualByComparingTo("18.00");
  }

  @Test
  @DisplayName("splitTaxAmountSafe falls back to CGST SGST split when splitter returns null")
  void splitTaxAmountSafe_fallsBackToIntraStateSplitWhenSplitterReturnsNull() {
    when(gstService.splitTaxAmount(new BigDecimal("100.00"), new BigDecimal("18.00"), "KA", "KA"))
        .thenReturn(null);
    when(gstService.resolveTaxType("KA", "KA", false)).thenReturn(GstService.TaxType.INTRA_STATE);

    GstService.GstBreakdown breakdown =
        ReflectionTestUtils.invokeMethod(
            purchaseInvoiceEngine,
            "splitTaxAmountSafe",
            new BigDecimal("100.00"),
            new BigDecimal("18.00"),
            "KA",
            "KA");

    assertThat(breakdown).isNotNull();
    assertThat(breakdown.taxType()).isEqualTo(GstService.TaxType.INTRA_STATE);
    assertThat(breakdown.cgst()).isEqualByComparingTo("9.00");
    assertThat(breakdown.sgst()).isEqualByComparingTo("9.00");
    assertThat(breakdown.igst()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("createPurchase rejects goods receipt drift when stock movement is missing")
  void createPurchase_rejectsGoodsReceiptWithoutStockMovement() {
    when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, "GOODS_RECEIPT", "GRN-40"))
        .thenReturn(List.of());

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("has no stock movement");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(purchaseRepository, never()).save(any(RawMaterialPurchase.class));
  }

  @Test
  @DisplayName("createPurchase rejects goods receipts missing persisted receipt number linkage")
  void createPurchase_rejectsGoodsReceiptWithoutReceiptNumber() {
    goodsReceipt.setReceiptNumber("   ");

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("linkage is incomplete");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(purchaseRepository, never()).save(any(RawMaterialPurchase.class));
  }

  @Test
  @DisplayName(
      "createPurchase rejects goods receipt lines missing GRN batch linkage before posting AP")
  void createPurchase_rejectsGoodsReceiptLineMissingBatchBeforePostingAp() {
    goodsReceipt.getLines().getFirst().setRawMaterialBatch(null);

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("missing batch linkage");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(purchaseRepository, never()).save(any(RawMaterialPurchase.class));
  }

  @Test
  @DisplayName(
      "createPurchase rejects goods receipt linkage drift when stock movement materials do not"
          + " match GRN lines")
  void createPurchase_rejectsGoodsReceiptMovementMaterialDrift() {
    RawMaterial unexpectedMaterial = new RawMaterial();
    ReflectionTestUtils.setField(unexpectedMaterial, "id", 21L);
    unexpectedMaterial.setCompany(company);
    unexpectedMaterial.setName("Pigment");
    unexpectedMaterial.setSku("RM-21");
    unexpectedMaterial.setUnitType("KG");

    RawMaterialMovement driftedMovement = new RawMaterialMovement();
    ReflectionTestUtils.setField(driftedMovement, "id", 501L);
    driftedMovement.setRawMaterial(unexpectedMaterial);
    driftedMovement.setReferenceType("GOODS_RECEIPT");
    driftedMovement.setReferenceId("GRN-40");
    driftedMovement.setMovementType("RECEIPT");
    driftedMovement.setQuantity(new BigDecimal("10.0000"));
    driftedMovement.setUnitCost(new BigDecimal("12.50"));

    when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, "GOODS_RECEIPT", "GRN-40"))
        .thenReturn(List.of(driftedMovement));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("linkage drift detected");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("createPurchase rejects goods receipt movements missing raw material identity")
  void createPurchase_rejectsGoodsReceiptMovementWithoutMaterialIdentity() {
    RawMaterialMovement nullMaterialMovement = new RawMaterialMovement();
    ReflectionTestUtils.setField(nullMaterialMovement, "id", 501L);
    nullMaterialMovement.setReferenceType("GOODS_RECEIPT");
    nullMaterialMovement.setReferenceId("GRN-40");
    nullMaterialMovement.setMovementType("RECEIPT");
    nullMaterialMovement.setQuantity(new BigDecimal("10.0000"));
    nullMaterialMovement.setUnitCost(new BigDecimal("12.50"));

    RawMaterialMovement nullMaterialIdMovement = new RawMaterialMovement();
    ReflectionTestUtils.setField(nullMaterialIdMovement, "id", 502L);
    nullMaterialIdMovement.setRawMaterial(new RawMaterial());
    nullMaterialIdMovement.setReferenceType("GOODS_RECEIPT");
    nullMaterialIdMovement.setReferenceId("GRN-40");
    nullMaterialIdMovement.setMovementType("RECEIPT");
    nullMaterialIdMovement.setQuantity(new BigDecimal("10.0000"));
    nullMaterialIdMovement.setUnitCost(new BigDecimal("12.50"));

    when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, "GOODS_RECEIPT", "GRN-40"))
        .thenReturn(List.of(nullMaterialMovement, nullMaterialIdMovement));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("linkage drift detected");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("createPurchase rejects goods receipts that have no stock lines to anchor AP truth")
  void createPurchase_rejectsGoodsReceiptWithoutStockLines() {
    goodsReceipt.getLines().clear();

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("has no stock lines");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(purchaseRepository, never()).save(any(RawMaterialPurchase.class));
  }

  @Test
  @DisplayName("createPurchase rejects goods receipt movements already linked to another journal")
  void createPurchase_rejectsGoodsReceiptMovementAlreadyLinkedToJournal() {
    RawMaterialMovement linkedMovement =
        movementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "GOODS_RECEIPT", "GRN-40")
            .getFirst();
    linkedMovement.setJournalEntryId(701L);

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("already linked to journal 701");

    verify(accountingFacade, never())
        .postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("createPurchase re-reads current goods receipt movements before linking AP journal")
  void createPurchase_reReadsCurrentGoodsReceiptMovementsBeforeLinkingJournal() {
    RawMaterialMovement initialMovement =
        movementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "GOODS_RECEIPT", "GRN-40")
            .getFirst();
    RawMaterialMovement concurrentlyLinkedMovement = new RawMaterialMovement();
    ReflectionTestUtils.setField(concurrentlyLinkedMovement, "id", 500L);
    concurrentlyLinkedMovement.setRawMaterial(rawMaterial);
    concurrentlyLinkedMovement.setReferenceType("GOODS_RECEIPT");
    concurrentlyLinkedMovement.setReferenceId("GRN-40");
    concurrentlyLinkedMovement.setMovementType("RECEIPT");
    concurrentlyLinkedMovement.setQuantity(new BigDecimal("10.0000"));
    concurrentlyLinkedMovement.setUnitCost(new BigDecimal("12.50"));
    concurrentlyLinkedMovement.setJournalEntryId(701L);
    when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, "GOODS_RECEIPT", "GRN-40"))
        .thenReturn(List.of(initialMovement), List.of(concurrentlyLinkedMovement));

    RawMaterialPurchaseRequest request =
        new RawMaterialPurchaseRequest(
            10L,
            "INV-40",
            LocalDate.of(2026, 3, 2),
            "invoice",
            30L,
            40L,
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    20L,
                    null,
                    new BigDecimal("10.0000"),
                    "KG",
                    new BigDecimal("12.50"),
                    null,
                    null,
                    "line")));

    assertThatThrownBy(() -> purchaseInvoiceEngine.createPurchase(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already linked to journal 701");

    verify(movementRepository, never()).saveAll(any());
  }
}
