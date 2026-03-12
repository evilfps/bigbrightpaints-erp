package com.bigbrightpaints.erp.shared.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceLineDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class TruthRailsSharedDtoContractTest {

    @Test
    void sharedReferenceDtosExposeLifecycleAndLinkedChainFields() {
        DocumentLifecycleDto lifecycle = new DocumentLifecycleDto("ISSUED", "POSTED");
        LinkedBusinessReferenceDto linkedReference = new LinkedBusinessReferenceDto(
                "SOURCE_ORDER",
                "SALES_ORDER",
                51L,
                "SO-51",
                lifecycle,
                91L
        );

        assertThat(lifecycle.workflowStatus()).isEqualTo("ISSUED");
        assertThat(lifecycle.accountingStatus()).isEqualTo("POSTED");
        assertThat(linkedReference.relationType()).isEqualTo("SOURCE_ORDER");
        assertThat(linkedReference.lifecycle()).isEqualTo(lifecycle);
        assertThat(linkedReference.journalEntryId()).isEqualTo(91L);
    }

    @Test
    void canonicalConstructorsExposeLifecycleAndLinkedReferenceFields() {
        InvoiceDto invoiceDto = new InvoiceDto(
                11L,
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                "INV-11",
                "ISSUED",
                new BigDecimal("90.00"),
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                new BigDecimal("40.00"),
                "INR",
                LocalDate.of(2026, 2, 20),
                LocalDate.of(2026, 3, 1),
                7L,
                "Dealer 7",
                71L,
                81L,
                Instant.parse("2026-02-20T00:00:00Z"),
                List.of(new InvoiceLineDto(1L, "FG-1", "Paint", new BigDecimal("1.00"), new BigDecimal("90.00"), new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("5.00"), new BigDecimal("5.00"), BigDecimal.ZERO)),
                null,
                List.of()
        );
        GoodsReceiptResponse goodsReceiptResponse = new GoodsReceiptResponse(
                12L,
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                "GRN-12",
                LocalDate.of(2026, 2, 21),
                new BigDecimal("75.00"),
                "PARTIAL",
                "memo",
                21L,
                "SUP-21",
                "Supplier 21",
                121L,
                "PO-121",
                Instant.parse("2026-02-21T00:00:00Z"),
                List.of(new GoodsReceiptLineResponse(31L, "Resin", "BATCH-31", new BigDecimal("3.00"), "KG", new BigDecimal("25.00"), new BigDecimal("75.00"), "note")),
                null,
                List.of()
        );
        RawMaterialPurchaseResponse purchaseResponse = new RawMaterialPurchaseResponse(
                13L,
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                "PINV-13",
                LocalDate.of(2026, 2, 22),
                new BigDecimal("80.00"),
                new BigDecimal("8.00"),
                new BigDecimal("20.00"),
                "POSTED",
                "memo",
                22L,
                "SUP-22",
                "Supplier 22",
                122L,
                "PO-122",
                222L,
                "GRN-222",
                322L,
                Instant.parse("2026-02-22T00:00:00Z"),
                List.of(new RawMaterialPurchaseLineResponse(41L, "Resin", 51L, "LOT-51", new BigDecimal("4.00"), "KG", new BigDecimal("20.00"), new BigDecimal("80.00"), new BigDecimal("10.00"), new BigDecimal("8.00"), "note", new BigDecimal("4.00"), new BigDecimal("4.00"), BigDecimal.ZERO)),
                null,
                List.of()
        );

        assertThat(invoiceDto.lifecycle()).isNull();
        assertThat(invoiceDto.linkedReferences()).isEmpty();
        assertThat(goodsReceiptResponse.lifecycle()).isNull();
        assertThat(goodsReceiptResponse.linkedReferences()).isEmpty();
        assertThat(purchaseResponse.lifecycle()).isNull();
        assertThat(purchaseResponse.linkedReferences()).isEmpty();
    }

    @Test
    void accountingAuditDetailCarriesDrivingDocumentAndLinkedReferenceChain() {
        LinkedBusinessReferenceDto drivingDocument = new LinkedBusinessReferenceDto(
                "DRIVING_DOCUMENT",
                "PURCHASE_INVOICE",
                61L,
                "PINV-61",
                new DocumentLifecycleDto("POSTED", "POSTED"),
                161L
        );
        AccountingTransactionAuditDetailDto detail = new AccountingTransactionAuditDetailDto(
                71L,
                UUID.fromString("00000000-0000-0000-0000-000000000071"),
                "RMP-71",
                LocalDate.of(2026, 2, 23),
                "POSTED",
                "PURCHASING",
                "PURCHASE_INVOICE",
                "memo",
                null,
                null,
                81L,
                "Supplier 81",
                91L,
                "FY26-P02",
                "OPEN",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("80.00"),
                "OK",
                List.of(),
                List.of(new JournalLineDto(11L, "RM", "Inventory", new BigDecimal("80.00"), BigDecimal.ZERO)),
                List.of(new AccountingTransactionAuditDetailDto.LinkedDocument("PURCHASE", 61L, "PINV-61", "POSTED", new BigDecimal("80.00"), new BigDecimal("20.00"))),
                List.of(new AccountingTransactionAuditDetailDto.SettlementAllocation(101L, "SUPPLIER", null, 81L, null, 61L, new BigDecimal("60.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "memo", LocalDate.of(2026, 2, 24), "idem-101")),
                List.of(new AccountingTransactionAuditDetailDto.EventTrailItem(111L, "JOURNAL_ENTRY_POSTED", "JournalEntry", UUID.fromString("00000000-0000-0000-0000-000000000111"), 1L, Instant.parse("2026-02-23T00:00:00Z"), LocalDate.of(2026, 2, 23), 11L, "RM", new BigDecimal("80.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("80.00"), "posted", "user-1", UUID.fromString("00000000-0000-0000-0000-000000000211"))),
                drivingDocument,
                List.of(drivingDocument),
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z"),
                "maker",
                "poster",
                "checker"
        );

        assertThat(detail.drivingDocument()).isEqualTo(drivingDocument);
        assertThat(detail.linkedReferenceChain()).containsExactly(drivingDocument);
        assertThat(detail.linkedDocuments()).singleElement().extracting(AccountingTransactionAuditDetailDto.LinkedDocument::documentType)
                .isEqualTo("PURCHASE");
    }
}
