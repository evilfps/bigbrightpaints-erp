package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchasingServiceGoodsReceiptTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private RawMaterialPurchaseRepository purchaseRepository;
    @Mock
    private PartnerSettlementAllocationRepository settlementAllocationRepository;
    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialService rawMaterialService;
    @Mock
    private RawMaterialMovementRepository movementRepository;
    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private ReferenceNumberService referenceNumberService;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private GstService gstService;
    @Mock
    private PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository;

    private PurchasingService purchasingService;
    private Company company;
    private Supplier supplier;
    private RawMaterial rawMaterial;
    private PurchaseOrder purchaseOrder;

    @BeforeEach
    void setUp() {
        purchasingService = new PurchasingService(
                companyContextService,
                purchaseRepository,
                purchaseOrderRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialService,
                movementRepository,
                goodsReceiptRepository,
                accountingFacade,
                journalEntryRepository,
                companyEntityLookup,
                referenceNumberService,
                companyClock,
                accountingPeriodService,
                gstService,
                purchaseOrderStatusHistoryRepository,
                new ResourcelessTransactionManager(),
                settlementAllocationRepository
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 10L);
        supplier.setCompany(company);
        supplier.setCode("SUP-10");
        supplier.setName("Supplier 10");

        rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 20L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-20");
        rawMaterial.setName("Resin");
        rawMaterial.setUnitType("KG");

        purchaseOrder = new PurchaseOrder();
        ReflectionTestUtils.setField(purchaseOrder, "id", 30L);
        purchaseOrder.setCompany(company);
        purchaseOrder.setSupplier(supplier);
        purchaseOrder.setOrderNumber("PO-30");
        purchaseOrder.setOrderDate(LocalDate.of(2026, 2, 10));
        purchaseOrder.setStatus(PurchaseOrderStatus.APPROVED);

        PurchaseOrderLine orderLine = new PurchaseOrderLine();
        orderLine.setPurchaseOrder(purchaseOrder);
        orderLine.setRawMaterial(rawMaterial);
        orderLine.setQuantity(new BigDecimal("10.0000"));
        orderLine.setUnit("KG");
        orderLine.setCostPerUnit(new BigDecimal("5.00"));
        orderLine.setLineTotal(new BigDecimal("50.00"));
        purchaseOrder.getLines().add(orderLine);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    @DisplayName("createGoodsReceipt rejects missing idempotency key")
    void createGoodsReceipt_rejectsMissingIdempotencyKey() {
        GoodsReceiptRequest request = request(
                null,
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, null, new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );

        assertThatThrownBy(() -> purchasingService.createGoodsReceipt(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
                    assertThat(ex).hasMessage("Idempotency key is required for goods receipts");
                });

        verify(goodsReceiptRepository, never()).findWithLinesByCompanyAndIdempotencyKey(any(), any());
        verifyNoInteractions(accountingPeriodService, purchaseOrderRepository, rawMaterialRepository, rawMaterialService);
    }

    @Test
    @DisplayName("createGoodsReceipt rejects missing receipt date")
    void createGoodsReceipt_rejectsMissingReceiptDate() {
        GoodsReceiptRequest request = request(
                "idem-missing-date",
                null,
                List.of(new GoodsReceiptLineRequest(20L, null, new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );
        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-missing-date"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchasingService.createGoodsReceipt(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
                    assertThat(ex).hasMessage("Receipt date is required");
                });

        verify(goodsReceiptRepository).findWithLinesByCompanyAndIdempotencyKey(company, "idem-missing-date");
        verifyNoInteractions(accountingPeriodService, purchaseOrderRepository, rawMaterialRepository, rawMaterialService);
    }

    @Test
    @DisplayName("createGoodsReceipt idempotency replay returns existing receipt")
    void createGoodsReceipt_idempotencyReplayReturnsExisting() {
        GoodsReceiptRequest request = request(
                "idem-replay-ok",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH", new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );
        GoodsReceipt existing = existingReceipt(request);
        existing.setIdempotencyHash(signatureFor(request));

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-replay-ok"))
                .thenReturn(Optional.of(existing));

        GoodsReceiptResponse response = purchasingService.createGoodsReceipt(request);

        assertThat(response.id()).isEqualTo(901L);
        assertThat(response.receiptNumber()).isEqualTo("GRN-30-01");
        assertThat(response.status()).isEqualTo("RECEIVED");
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).batchCode()).isEqualTo("REQ-BATCH");
        assertThat(response.totalAmount()).isEqualByComparingTo("20.00");

        verify(goodsReceiptRepository).findWithLinesByCompanyAndIdempotencyKey(company, "idem-replay-ok");
        verify(goodsReceiptRepository, never()).save(any(GoodsReceipt.class));
        verifyNoInteractions(accountingPeriodService, purchaseOrderRepository, rawMaterialRepository, rawMaterialService);
    }

    @Test
    @DisplayName("listGoodsReceipts batches linked purchase lookup for lifecycle mapping")
    void listGoodsReceipts_batchesLinkedPurchaseLookup() {
        GoodsReceipt firstReceipt = existingReceipt(request(
                "idem-list-1",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH-1", new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        ));
        ReflectionTestUtils.setField(firstReceipt, "id", 901L);

        GoodsReceipt secondReceipt = existingReceipt(request(
                "idem-list-2",
                LocalDate.of(2026, 2, 21),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH-2", new BigDecimal("3.0000"), "KG", new BigDecimal("5.00"), "line note"))
        ));
        ReflectionTestUtils.setField(secondReceipt, "id", 902L);

        RawMaterialPurchase linkedPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(linkedPurchase, "id", 990L);
        linkedPurchase.setCompany(company);
        linkedPurchase.setSupplier(supplier);
        linkedPurchase.setInvoiceNumber("PINV-990");
        linkedPurchase.setStatus("POSTED");
        linkedPurchase.setGoodsReceipt(firstReceipt);

        when(goodsReceiptRepository.findByCompanyWithLinesOrderByReceiptDateDesc(company))
                .thenReturn(List.of(firstReceipt, secondReceipt));
        when(purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, List.of(901L, 902L)))
                .thenReturn(List.of(linkedPurchase));

        List<GoodsReceiptResponse> responses = purchasingService.listGoodsReceipts();

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("PURCHASE_ORDER", "PURCHASE_INVOICE", "SELF");
        assertThat(responses.get(1).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactlyInAnyOrder("PURCHASE_ORDER", "SELF");

        verify(purchaseRepository).findByCompanyAndGoodsReceipt_IdIn(company, List.of(901L, 902L));
        verify(purchaseRepository, never()).findByCompanyAndGoodsReceipt(any(), any());
    }

    @Test
    @DisplayName("createGoodsReceipt idempotency replay mismatch throws conflict")
    void createGoodsReceipt_idempotencyReplayMismatchThrows() {
        GoodsReceiptRequest request = request(
                "idem-replay-mismatch",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH", new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );
        GoodsReceipt existing = existingReceipt(request);
        existing.setIdempotencyHash("different-hash");

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-replay-mismatch"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> purchasingService.createGoodsReceipt(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex).hasMessage("Idempotency key already used with different payload");
                    assertThat(ex.getDetails()).containsEntry("idempotencyKey", "idem-replay-mismatch");
                    assertThat(ex.getDetails()).containsEntry("receiptNumber", "GRN-30-01");
                });

        verify(goodsReceiptRepository).findWithLinesByCompanyAndIdempotencyKey(company, "idem-replay-mismatch");
        verifyNoInteractions(accountingPeriodService, purchaseOrderRepository, rawMaterialRepository, rawMaterialService);
    }

    @Test
    @DisplayName("createGoodsReceipt handles data-integrity race by reloading idempotent receipt")
    void createGoodsReceipt_dataIntegrityRace_reloadsExistingReceipt() {
        GoodsReceiptRequest request = request(
                "idem-race-reload",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH", new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );
        GoodsReceipt existing = existingReceipt(request);
        existing.setIdempotencyHash(signatureFor(request));

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-race-reload"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
                .thenReturn(Optional.of(purchaseOrder));
        when(goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, "GRN-30-01"))
                .thenReturn(Optional.empty());
        when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
                .thenReturn(List.of());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L))
                .thenReturn(Optional.of(rawMaterial));

        RawMaterialBatch recordedBatch = new RawMaterialBatch();
        ReflectionTestUtils.setField(recordedBatch, "id", 702L);
        recordedBatch.setBatchCode("RM-20-LOT-RACE");
        recordedBatch.setRawMaterial(rawMaterial);
        recordedBatch.setQuantity(new BigDecimal("4.0000"));
        recordedBatch.setUnit("KG");
        recordedBatch.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialService.recordReceipt(eq(20L), any(RawMaterialBatchRequest.class), any(RawMaterialService.ReceiptContext.class)))
                .thenReturn(new RawMaterialService.ReceiptResult(recordedBatch, null, null));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        GoodsReceiptResponse response = purchasingService.createGoodsReceipt(request);

        assertThat(response.id()).isEqualTo(901L);
        assertThat(response.receiptNumber()).isEqualTo("GRN-30-01");
        assertThat(response.status()).isEqualTo("RECEIVED");
        assertThat(response.totalAmount()).isEqualByComparingTo("20.00");

        verify(goodsReceiptRepository, times(2)).findWithLinesByCompanyAndIdempotencyKey(company, "idem-race-reload");
        verify(goodsReceiptRepository).saveAndFlush(any(GoodsReceipt.class));
        verify(accountingPeriodService).requireOpenPeriod(company, LocalDate.of(2026, 2, 20));
    }

    @Test
    @DisplayName("createGoodsReceipt legacy replay backfills missing idempotency hash")
    void createGoodsReceipt_legacyReplayBackfillsHash() {
        GoodsReceiptRequest request = request(
                "idem-legacy-backfill",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(20L, "REQ-BATCH", new BigDecimal("4.0000"), "KG", new BigDecimal("5.00"), "line note"))
        );
        GoodsReceipt existing = existingReceipt(request);
        existing.setIdempotencyHash(null);

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-legacy-backfill"))
                .thenReturn(Optional.of(existing));
        when(goodsReceiptRepository.save(existing)).thenReturn(existing);

        GoodsReceiptResponse response = purchasingService.createGoodsReceipt(request);

        assertThat(existing.getIdempotencyHash()).isEqualTo(signatureFor(request));
        assertThat(response.id()).isEqualTo(901L);
        assertThat(response.receiptNumber()).isEqualTo("GRN-30-01");
        assertThat(response.status()).isEqualTo("RECEIVED");
        verify(goodsReceiptRepository).save(existing);
        verifyNoInteractions(accountingPeriodService, purchaseOrderRepository, rawMaterialRepository, rawMaterialService);
    }

    @Test
    @DisplayName("createGoodsReceipt partial receipt sets statuses and records batches")
    void createGoodsReceipt_partialReceipt_setsStatusesAndRecordsBatches() {
        LocalDate manufacturingDate = LocalDate.of(2026, 2, 15);
        LocalDate expiryDate = LocalDate.of(2026, 12, 31);
        GoodsReceiptRequest request = request(
                "idem-partial",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(
                        20L,
                        null,
                        new BigDecimal("4.0000"),
                        "KG",
                        new BigDecimal("5.00"),
                        manufacturingDate,
                        expiryDate,
                        "line note"))
        );

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-partial"))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
                .thenReturn(Optional.of(purchaseOrder));
        when(goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, "GRN-30-01"))
                .thenReturn(Optional.empty());
        when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
                .thenReturn(List.of());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L))
                .thenReturn(Optional.of(rawMaterial));

        RawMaterialBatch recordedBatch = new RawMaterialBatch();
        ReflectionTestUtils.setField(recordedBatch, "id", 701L);
        recordedBatch.setBatchCode("RM-20-LOT-001");
        recordedBatch.setRawMaterial(rawMaterial);
        recordedBatch.setQuantity(new BigDecimal("4.0000"));
        recordedBatch.setUnit("KG");
        recordedBatch.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialService.recordReceipt(eq(20L), any(RawMaterialBatchRequest.class), any(RawMaterialService.ReceiptContext.class)))
                .thenReturn(new RawMaterialService.ReceiptResult(recordedBatch, null, null));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class)))
                .thenAnswer(invocation -> {
                    GoodsReceipt receipt = invocation.getArgument(0);
                    ReflectionTestUtils.setField(receipt, "id", 950L);
                    ReflectionTestUtils.setField(receipt, "createdAt", Instant.parse("2026-02-20T00:00:00Z"));
                    return receipt;
                });
        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GoodsReceiptResponse response = purchasingService.createGoodsReceipt(request);

        ArgumentCaptor<GoodsReceipt> receiptCaptor = ArgumentCaptor.forClass(GoodsReceipt.class);
        verify(goodsReceiptRepository).saveAndFlush(receiptCaptor.capture());
        GoodsReceipt savedReceipt = receiptCaptor.getValue();
        assertThat(savedReceipt.getStatus()).isEqualTo("PARTIAL");
        assertThat(savedReceipt.getLines()).hasSize(1);
        assertThat(savedReceipt.getLines().get(0).getRawMaterialBatch()).isEqualTo(recordedBatch);
        assertThat(savedReceipt.getLines().get(0).getBatchCode()).isEqualTo("RM-20-LOT-001");
        assertThat(savedReceipt.getLines().get(0).getLineTotal()).isEqualByComparingTo("20.00");
        assertThat(purchaseOrder.getStatus()).isEqualTo("PARTIALLY_RECEIVED");

        ArgumentCaptor<RawMaterialBatchRequest> batchRequestCaptor = ArgumentCaptor.forClass(RawMaterialBatchRequest.class);
        verify(rawMaterialService).recordReceipt(eq(20L), batchRequestCaptor.capture(), any(RawMaterialService.ReceiptContext.class));
        RawMaterialBatchRequest batchRequest = batchRequestCaptor.getValue();
        assertThat(batchRequest.batchCode()).isEqualTo("RM-20-GRN-30-01");
        assertThat(batchRequest.quantity()).isEqualByComparingTo("4.0000");
        assertThat(batchRequest.costPerUnit()).isEqualByComparingTo("5.00");
        assertThat(batchRequest.supplierId()).isEqualTo(10L);
        assertThat(batchRequest.manufacturingDate()).isEqualTo(manufacturingDate);
        assertThat(batchRequest.expiryDate()).isEqualTo(expiryDate);

        verify(accountingPeriodService).requireOpenPeriod(company, LocalDate.of(2026, 2, 20));
        verify(purchaseOrderRepository).save(purchaseOrder);

        assertThat(response.id()).isEqualTo(950L);
        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.lifecycle().workflowStatus()).isEqualTo("PARTIAL");
        assertThat(response.lifecycle().accountingStatus()).isEqualTo("PENDING");
        assertThat(response.receiptNumber()).isEqualTo("GRN-30-01");
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).batchCode()).isEqualTo("RM-20-LOT-001");
        assertThat(response.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(response.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
                .contains(org.assertj.core.groups.Tuple.tuple("PURCHASE_ORDER", "PURCHASE_ORDER"));
    }

    @Test
    @DisplayName("createGoodsReceipt keeps AP truth out of receipt posting")
    void createGoodsReceipt_doesNotPostApJournalOnSuccessfulReceipt() {
        GoodsReceiptRequest request = request(
                "idem-stock-only",
                LocalDate.of(2026, 2, 20),
                List.of(new GoodsReceiptLineRequest(
                        20L,
                        null,
                        new BigDecimal("4.0000"),
                        "KG",
                        new BigDecimal("5.00"),
                        "line note"))
        );

        when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-stock-only"))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
                .thenReturn(Optional.of(purchaseOrder));
        when(goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, "GRN-30-01"))
                .thenReturn(Optional.empty());
        when(goodsReceiptRepository.findByPurchaseOrder(purchaseOrder))
                .thenReturn(List.of());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L))
                .thenReturn(Optional.of(rawMaterial));

        RawMaterialBatch recordedBatch = new RawMaterialBatch();
        ReflectionTestUtils.setField(recordedBatch, "id", 702L);
        recordedBatch.setBatchCode("RM-20-LOT-002");
        recordedBatch.setRawMaterial(rawMaterial);
        recordedBatch.setQuantity(new BigDecimal("4.0000"));
        recordedBatch.setUnit("KG");
        recordedBatch.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialService.recordReceipt(eq(20L), any(RawMaterialBatchRequest.class), any(RawMaterialService.ReceiptContext.class)))
                .thenReturn(new RawMaterialService.ReceiptResult(recordedBatch, null, null));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class)))
                .thenAnswer(invocation -> {
                    GoodsReceipt receipt = invocation.getArgument(0);
                    ReflectionTestUtils.setField(receipt, "id", 951L);
                    ReflectionTestUtils.setField(receipt, "createdAt", Instant.parse("2026-02-20T00:00:00Z"));
                    return receipt;
                });
        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GoodsReceiptResponse response = purchasingService.createGoodsReceipt(request);

        assertThat(response.id()).isEqualTo(951L);
        assertThat(response.status()).isEqualTo("PARTIAL");
        verifyNoInteractions(accountingFacade, journalEntryRepository);
    }

    private GoodsReceiptRequest request(String idempotencyKey, LocalDate receiptDate, List<GoodsReceiptLineRequest> lines) {
        return new GoodsReceiptRequest(
                30L,
                "GRN-30-01",
                receiptDate,
                "Goods receipt memo",
                idempotencyKey,
                lines
        );
    }

    private GoodsReceipt existingReceipt(GoodsReceiptRequest request) {
        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", 901L);
        ReflectionTestUtils.setField(receipt, "createdAt", Instant.parse("2026-02-20T00:00:00Z"));
        receipt.setCompany(company);
        receipt.setSupplier(supplier);
        receipt.setPurchaseOrder(purchaseOrder);
        receipt.setReceiptNumber(request.receiptNumber());
        receipt.setReceiptDate(request.receiptDate());
        receipt.setMemo(request.memo());
        receipt.setStatus("RECEIVED");
        receipt.setIdempotencyKey(request.idempotencyKey());

        GoodsReceiptLine requestLine = new GoodsReceiptLine();
        requestLine.setGoodsReceipt(receipt);
        requestLine.setRawMaterial(rawMaterial);
        requestLine.setBatchCode(request.lines().get(0).batchCode());
        requestLine.setQuantity(request.lines().get(0).quantity());
        requestLine.setUnit(request.lines().get(0).unit());
        requestLine.setCostPerUnit(request.lines().get(0).costPerUnit());
        requestLine.setLineTotal(new BigDecimal("20.00"));
        requestLine.setNotes(request.lines().get(0).notes());
        receipt.getLines().add(requestLine);
        return receipt;
    }

    private String signatureFor(GoodsReceiptRequest request) {
        StringBuilder signature = new StringBuilder();
        signature.append(request.purchaseOrderId() != null ? request.purchaseOrderId() : "")
                .append('|').append(normalizeToken(request.receiptNumber()))
                .append('|').append(request.receiptDate() != null ? request.receiptDate() : "")
                .append('|').append(normalizeToken(request.memo()));
        request.lines().stream()
                .sorted(Comparator.comparing(GoodsReceiptLineRequest::rawMaterialId))
                .forEach(line -> signature.append('|')
                        .append(line.rawMaterialId() != null ? line.rawMaterialId() : "")
                        .append(':').append(normalizeToken(line.batchCode()))
                        .append(':').append(normalizeAmount(line.quantity()))
                        .append(':').append(normalizeToken(line.unit()))
                        .append(':').append(normalizeAmount(line.costPerUnit()))
                        .append(':').append(line.manufacturingDate() != null ? line.manufacturingDate() : "")
                        .append(':').append(line.expiryDate() != null ? line.expiryDate() : "")
                        .append(':').append(normalizeToken(line.notes())));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String normalizeToken(String value) {
        return value != null ? value.trim() : "";
    }

    private String normalizeAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
