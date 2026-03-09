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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
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

        when(settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(company, purchase))
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

        when(settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(company, purchase))
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
