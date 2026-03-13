package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchaseResponseMapperTest {

    @Mock
    private RawMaterialPurchaseRepository purchaseRepository;
    @Mock
    private PartnerSettlementAllocationRepository settlementAllocationRepository;

    private PurchaseResponseMapper mapper;
    private Company company;
    private Supplier supplier;
    private PurchaseOrder purchaseOrder;
    private RawMaterial rawMaterial;

    @BeforeEach
    void setUp() {
        mapper = new PurchaseResponseMapper(purchaseRepository, settlementAllocationRepository);

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 11L);
        supplier.setCompany(company);
        supplier.setCode("SUP-11");
        supplier.setName("Supplier 11");

        purchaseOrder = new PurchaseOrder();
        ReflectionTestUtils.setField(purchaseOrder, "id", 21L);
        purchaseOrder.setCompany(company);
        purchaseOrder.setOrderNumber("PO-21");
        purchaseOrder.setStatus("APPROVED");

        rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 31L);
        rawMaterial.setName("Resin");
    }

    @Test
    void toPurchaseResponse_includesTruthRailsLinkedReferences() {
        GoodsReceipt receipt = goodsReceipt(41L, "GRN-41");
        JournalEntry purchaseJournal = journalEntry(51L, "RMP-51", "POSTED");

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 61L);
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setPurchaseOrder(purchaseOrder);
        purchase.setGoodsReceipt(receipt);
        purchase.setJournalEntry(purchaseJournal);
        purchase.setInvoiceNumber("PINV-61");
        purchase.setInvoiceDate(LocalDate.of(2026, 2, 20));
        purchase.setStatus("POSTED");
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setTaxAmount(new BigDecimal("10.00"));
        purchase.setOutstandingAmount(new BigDecimal("40.00"));
        purchase.getLines().add(purchaseLine(purchase));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(allocation, "id", 71L);
        allocation.setCompany(company);
        allocation.setPurchase(purchase);
        allocation.setJournalEntry(journalEntry(72L, "SET-72", "POSTED"));
        allocation.setIdempotencyKey("settlement-72");

        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(61L)))
                .thenReturn(List.of(allocation));

        RawMaterialPurchaseResponse response = mapper.toPurchaseResponse(purchase);

        assertThat(response.lifecycle().workflowStatus()).isEqualTo("POSTED");
        assertThat(response.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("PURCHASE_ORDER", "GOODS_RECEIPT", "ACCOUNTING_ENTRY", "SETTLEMENT", "SELF");
    }

    @Test
    void toGoodsReceiptResponses_batchesLinkedPurchaseLookup() {
        GoodsReceipt firstReceipt = goodsReceipt(401L, "GRN-401");
        GoodsReceipt secondReceipt = goodsReceipt(402L, "GRN-402");

        RawMaterialPurchase linkedPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(linkedPurchase, "id", 501L);
        linkedPurchase.setCompany(company);
        linkedPurchase.setSupplier(supplier);
        linkedPurchase.setPurchaseOrder(purchaseOrder);
        linkedPurchase.setGoodsReceipt(firstReceipt);
        linkedPurchase.setJournalEntry(journalEntry(601L, "RMP-601", "POSTED"));
        linkedPurchase.setInvoiceNumber("PINV-501");
        linkedPurchase.setStatus("POSTED");

        when(purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, List.of(401L, 402L)))
                .thenReturn(List.of(linkedPurchase));

        List<GoodsReceiptResponse> responses = mapper.toGoodsReceiptResponses(List.of(firstReceipt, secondReceipt));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("PURCHASE_ORDER", "PURCHASE_INVOICE", "ACCOUNTING_ENTRY", "SELF");
        assertThat(responses.get(1).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactlyInAnyOrder("PURCHASE_ORDER", "SELF");
        verify(purchaseRepository).findByCompanyAndGoodsReceipt_IdIn(company, List.of(401L, 402L));
        verify(purchaseRepository, never()).findByCompanyAndGoodsReceipt(any(), any());
    }

    @Test
    void toPurchaseResponses_batchesSettlementLookup() {
        RawMaterialPurchase firstPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(firstPurchase, "id", 511L);
        firstPurchase.setCompany(company);
        firstPurchase.setSupplier(supplier);
        firstPurchase.setInvoiceNumber("PINV-511");
        firstPurchase.setStatus("POSTED");
        firstPurchase.getLines().add(purchaseLine(firstPurchase));

        RawMaterialPurchase secondPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(secondPurchase, "id", 512L);
        secondPurchase.setCompany(company);
        secondPurchase.setSupplier(supplier);
        secondPurchase.setInvoiceNumber("PINV-512");
        secondPurchase.setStatus("POSTED");
        secondPurchase.getLines().add(purchaseLine(secondPurchase));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(allocation, "id", 513L);
        allocation.setCompany(company);
        allocation.setPurchase(firstPurchase);
        allocation.setIdempotencyKey("settlement-513");

        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(511L, 512L)))
                .thenReturn(List.of(allocation));

        List<RawMaterialPurchaseResponse> responses = mapper.toPurchaseResponses(List.of(firstPurchase, secondPurchase));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("SETTLEMENT", "SELF");
        assertThat(responses.get(1).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("SELF");
        verify(settlementAllocationRepository).findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(511L, 512L));
        verify(settlementAllocationRepository, never()).findByCompanyAndPurchaseOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void toPurchaseResponses_returnsEmptyForNullOrEmptyInput() {
        assertThat(mapper.toPurchaseResponses(null)).isEmpty();
        assertThat(mapper.toPurchaseResponses(List.of())).isEmpty();
    }

    @Test
    void toGoodsReceiptResponses_returnsEmptyForNullOrEmptyInput() {
        assertThat(mapper.toGoodsReceiptResponses(null)).isEmpty();
        assertThat(mapper.toGoodsReceiptResponses(List.of())).isEmpty();
    }

    @Test
    void toGoodsReceiptResponses_batchesOnlyReceiptsWithResolvableCompanyAndIds() {
        GoodsReceipt receiptWithoutCompany = new GoodsReceipt();
        ReflectionTestUtils.setField(receiptWithoutCompany, "id", 801L);
        receiptWithoutCompany.setReceiptNumber("GRN-801");

        GoodsReceipt receiptWithoutId = goodsReceipt(null, "GRN-NULL");

        when(purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, List.of(801L))).thenReturn(List.of());

        assertThat(mapper.toGoodsReceiptResponses(List.of(receiptWithoutCompany, receiptWithoutId))).hasSize(2);
        verify(purchaseRepository).findByCompanyAndGoodsReceipt_IdIn(company, List.of(801L));
    }

    @Test
    void toPurchaseOrderResponse_mapsSupplierAndLineTotals() {
        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 101L);
        order.setCompany(company);
        order.setSupplier(supplier);
        order.setOrderNumber("PO-101");
        order.setStatus("APPROVED");

        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrder(order);
        line.setRawMaterial(rawMaterial);
        line.setQuantity(new BigDecimal("5.00"));
        line.setUnit("KG");
        line.setCostPerUnit(new BigDecimal("12.00"));
        line.setLineTotal(new BigDecimal("60.00"));
        order.getLines().add(line);

        assertThat(mapper.toPurchaseOrderResponse(order))
                .satisfies(response -> {
                    assertThat(response.supplierId()).isEqualTo(11L);
                    assertThat(response.totalAmount()).isEqualByComparingTo("60.00");
                    assertThat(response.lines()).singleElement().satisfies(mappedLine -> {
                        assertThat(mappedLine.rawMaterialId()).isEqualTo(31L);
                        assertThat(mappedLine.rawMaterialName()).isEqualTo("Resin");
                    });
                });
    }

    @Test
    void toGoodsReceiptResponse_usesSingleReceiptLookupAndAddsLinkedPurchaseReferences() {
        GoodsReceipt receipt = goodsReceipt(611L, "GRN-611");
        RawMaterialPurchase linkedPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(linkedPurchase, "id", 612L);
        linkedPurchase.setCompany(company);
        linkedPurchase.setSupplier(supplier);
        linkedPurchase.setPurchaseOrder(purchaseOrder);
        linkedPurchase.setGoodsReceipt(receipt);
        linkedPurchase.setJournalEntry(journalEntry(613L, "RMP-613", "POSTED"));
        linkedPurchase.setInvoiceNumber("PINV-612");
        linkedPurchase.setStatus("POSTED");

        when(purchaseRepository.findByCompanyAndGoodsReceipt(company, receipt)).thenReturn(java.util.Optional.of(linkedPurchase));

        GoodsReceiptResponse response = mapper.toGoodsReceiptResponse(receipt);

        assertThat(response.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("PURCHASE_ORDER", "PURCHASE_INVOICE", "ACCOUNTING_ENTRY", "SELF");
        verify(purchaseRepository).findByCompanyAndGoodsReceipt(company, receipt);
    }

    @Test
    void nullSafeConstructors_doNotRequireRepositoriesForSingleMappings() {
        PurchaseResponseMapper nullSafeMapper = new PurchaseResponseMapper();
        GoodsReceipt receipt = goodsReceipt(901L, "GRN-901");

        assertThat(nullSafeMapper.toGoodsReceiptResponse(receipt).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactlyInAnyOrder("PURCHASE_ORDER", "SELF");
    }

    @Test
    void toGoodsReceiptResponses_fallsBackToSelfReferenceWhenNoLinkedPurchaseIsResolved() {
        GoodsReceipt unlinkedReceipt = goodsReceipt(701L, "GRN-701");

        when(purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, List.of(701L))).thenReturn(List.of());

        assertThat(mapper.toGoodsReceiptResponses(List.of(unlinkedReceipt))).singleElement()
                .satisfies(response -> assertThat(response.linkedReferences())
                        .extracting(LinkedBusinessReferenceDto::relationType)
                        .containsExactly("PURCHASE_ORDER", "SELF"));
        verify(purchaseRepository).findByCompanyAndGoodsReceipt_IdIn(company, List.of(701L));
    }

    @Test
    void toPurchaseResponse_filtersNullLinkedReferencesAndHandlesMissingJournal() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 81L);
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber("PINV-81");
        purchase.setStatus("DRAFT");
        purchase.getLines().add(purchaseLine(purchase));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(allocation, "id", 91L);
        allocation.setCompany(company);
        allocation.setPurchase(purchase);
        allocation.setIdempotencyKey("settlement-91");

        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(81L)))
                .thenReturn(List.of(allocation));

        RawMaterialPurchaseResponse response = mapper.toPurchaseResponse(purchase);

        assertThat(response.lifecycle().accountingStatus()).isEqualTo("PENDING");
        assertThat(response.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("SETTLEMENT", "SELF");
        assertThat(response.linkedReferences())
                .filteredOn(reference -> "SETTLEMENT".equals(reference.relationType()))
                .first()
                .satisfies(reference -> assertThat(reference.journalEntryId()).isNull());
    }

    @Test
    void toPurchaseResponse_skipsSettlementLookupWhenCompanyIsMissing() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 82L);
        purchase.setInvoiceNumber("PINV-82");
        purchase.setStatus("POSTED");
        purchase.getLines().add(purchaseLine(purchase));

        RawMaterialPurchaseResponse response = mapper.toPurchaseResponse(purchase);

        assertThat(response.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("SELF");
        verify(settlementAllocationRepository, never()).findByCompanyAndPurchaseOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void toPurchaseResponse_skipsSettlementLookupWhenRepositoryIsMissingButCompanyExists() {
        PurchaseResponseMapper noSettlementMapper = new PurchaseResponseMapper();

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 83L);
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber("PINV-83");
        purchase.setStatus("POSTED");
        purchase.getLines().add(purchaseLine(purchase));

        assertThat(noSettlementMapper.toPurchaseResponse(purchase).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("SELF");
    }

    @Test
    void helperMethods_coverLinkedPurchaseFallbackBranches() {
        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", 910L);

        assertThat((Object) ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchase", receipt)).isNull();

        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> emptyLookup = ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchases", List.of(receipt));
        assertThat(emptyLookup).isEmpty();

        PurchaseResponseMapper noSettlementMapper = new PurchaseResponseMapper(purchaseRepository);
        GoodsReceipt linkedOnlyReceipt = goodsReceipt(920L, "GRN-920");
        assertThat(noSettlementMapper.toGoodsReceiptResponse(linkedOnlyReceipt, null).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactlyInAnyOrder("PURCHASE_ORDER", "SELF");
    }

    @Test
    void helperMethods_coverNullReceiptAndRepositoryBranches() {
        assertThat((Object) ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchase", new Object[]{null})).isNull();

        GoodsReceipt noCompanyReceipt = new GoodsReceipt();
        ReflectionTestUtils.setField(noCompanyReceipt, "id", 925L);
        assertThat((Object) ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchase", noCompanyReceipt)).isNull();

        PurchaseResponseMapper noRepoMapper = new PurchaseResponseMapper();
        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> noRepoLookup = ReflectionTestUtils.invokeMethod(noRepoMapper, "resolveLinkedPurchases", List.of(goodsReceipt(926L, "GRN-926")));
        assertThat(noRepoLookup).isEmpty();
        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> nullInputLookup = ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchases", new Object[]{null});
        assertThat(nullInputLookup).isEmpty();
    }

    @Test
    void helperMethods_coverResolveLinkedPurchaseSuccessAndMissingReceiptIds() {
        GoodsReceipt receipt = goodsReceipt(930L, "GRN-930");
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 931L);
        purchase.setCompany(company);
        purchase.setInvoiceNumber("PINV-931");
        purchase.setGoodsReceipt(receipt);
        when(purchaseRepository.findByCompanyAndGoodsReceipt(company, receipt)).thenReturn(Optional.of(purchase));

        assertThat((Object) ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchase", receipt)).isSameAs(purchase);

        GoodsReceipt noIdReceipt = goodsReceipt(null, "GRN-NO-ID");
        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> emptyLookup = ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchases", List.of(noIdReceipt));
        assertThat(emptyLookup).isEmpty();
    }

    @Test
    void helperMethods_coverResolveLinkedPurchasesDuplicateAndNullAllocationBranches() {
        GoodsReceipt receipt = goodsReceipt(944L, "GRN-944");

        RawMaterialPurchase firstPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(firstPurchase, "id", 945L);
        firstPurchase.setCompany(company);
        firstPurchase.setInvoiceNumber("PINV-945");
        firstPurchase.setGoodsReceipt(receipt);

        RawMaterialPurchase secondPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(secondPurchase, "id", 946L);
        secondPurchase.setCompany(company);
        secondPurchase.setInvoiceNumber("PINV-946");
        secondPurchase.setGoodsReceipt(receipt);

        when(purchaseRepository.findByCompanyAndGoodsReceipt_IdIn(company, List.of(944L)))
                .thenReturn(List.of(firstPurchase, secondPurchase));

        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> linkedPurchases =
                ReflectionTestUtils.invokeMethod(mapper, "resolveLinkedPurchases", List.of(receipt));
        assertThat(linkedPurchases).containsEntry(944L, firstPurchase);

        RawMaterialPurchase companyTwoPurchase = new RawMaterialPurchase();
        Company companyTwo = new Company();
        ReflectionTestUtils.setField(companyTwo, "id", 947L);
        ReflectionTestUtils.setField(companyTwoPurchase, "id", 948L);
        companyTwoPurchase.setCompany(companyTwo);

        PartnerSettlementAllocation mappedAllocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(mappedAllocation, "id", 949L);
        mappedAllocation.setCompany(company);
        mappedAllocation.setPurchase(firstPurchase);

        PartnerSettlementAllocation nullPurchaseAllocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(nullPurchaseAllocation, "id", 950L);
        nullPurchaseAllocation.setCompany(company);

        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(945L)))
                .thenReturn(List.of(mappedAllocation, nullPurchaseAllocation));
        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(companyTwo, List.of(948L)))
                .thenReturn(null);

        @SuppressWarnings("unchecked")
        Map<Long, List<PartnerSettlementAllocation>> allocationsByPurchaseId =
                ReflectionTestUtils.invokeMethod(mapper, "resolveSettlementAllocations", List.of(firstPurchase, companyTwoPurchase));
        assertThat(allocationsByPurchaseId).containsKey(945L);
        assertThat(allocationsByPurchaseId.get(945L)).containsExactly(mappedAllocation);
    }

    @Test
    void helperMethods_coverNullAllocationsAndLinkedPurchaseWithoutJournal() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 941L);
        purchase.setCompany(company);
        purchase.setInvoiceNumber("PINV-941");
        purchase.setStatus("POSTED");
        purchase.getLines().add(purchaseLine(purchase));

        when(settlementAllocationRepository.findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(company, List.of(941L))).thenReturn(null);

        assertThat(mapper.toPurchaseResponse(purchase).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("SELF");

        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", 942L);
        receipt.setReceiptNumber("GRN-942");
        receipt.getLines().add(new GoodsReceiptLine());

        RawMaterialPurchase linkedPurchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(linkedPurchase, "id", 943L);
        linkedPurchase.setInvoiceNumber("PINV-943");
        linkedPurchase.setStatus("POSTED");

        assertThat(mapper.toGoodsReceiptResponse(receipt, linkedPurchase).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactlyInAnyOrder("PURCHASE_INVOICE", "SELF");
    }

    @Test
    void mappingHelpers_coverNullMetadataBranches() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 951L);
        purchase.setInvoiceNumber("PINV-951");
        purchase.setStatus("DRAFT");
        purchase.getLines().add(new RawMaterialPurchaseLine());

        RawMaterialPurchaseResponse purchaseResponse = mapper.toPurchaseResponse(purchase);
        assertThat(purchaseResponse.supplierId()).isNull();
        assertThat(purchaseResponse.purchaseOrderId()).isNull();
        assertThat(purchaseResponse.goodsReceiptId()).isNull();
        assertThat(purchaseResponse.journalEntryId()).isNull();

        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 952L);
        order.setOrderNumber("PO-952");
        order.setStatus("DRAFT");
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrder(order);
        line.setLineTotal(new BigDecimal("12.00"));
        order.getLines().add(line);

        assertThat(mapper.toPurchaseOrderResponse(order).supplierId()).isNull();
        assertThat(mapper.toPurchaseOrderLineResponse(line).rawMaterialId()).isNull();
    }

    private GoodsReceipt goodsReceipt(Long id, String receiptNumber) {
        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", id);
        receipt.setCompany(company);
        receipt.setSupplier(supplier);
        receipt.setPurchaseOrder(purchaseOrder);
        receipt.setReceiptNumber(receiptNumber);
        receipt.setReceiptDate(LocalDate.of(2026, 2, 19));
        receipt.setStatus("INVOICED");
        receipt.getLines().add(goodsReceiptLine(receipt));
        return receipt;
    }

    private GoodsReceiptLine goodsReceiptLine(GoodsReceipt receipt) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setGoodsReceipt(receipt);
        line.setRawMaterial(rawMaterial);
        line.setBatchCode("LOT-31");
        line.setQuantity(new BigDecimal("4.00"));
        line.setUnit("KG");
        line.setCostPerUnit(new BigDecimal("25.00"));
        line.setLineTotal(new BigDecimal("100.00"));
        return line;
    }

    private RawMaterialPurchaseLine purchaseLine(RawMaterialPurchase purchase) {
        RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
        line.setPurchase(purchase);
        line.setRawMaterial(rawMaterial);
        line.setBatchCode("LOT-31");
        line.setQuantity(new BigDecimal("4.00"));
        line.setUnit("KG");
        line.setCostPerUnit(new BigDecimal("25.00"));
        line.setLineTotal(new BigDecimal("100.00"));
        return line;
    }

    private JournalEntry journalEntry(Long id, String referenceNumber, String status) {
        JournalEntry entry = new JournalEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setReferenceNumber(referenceNumber);
        entry.setStatus(status);
        return entry;
    }
}
