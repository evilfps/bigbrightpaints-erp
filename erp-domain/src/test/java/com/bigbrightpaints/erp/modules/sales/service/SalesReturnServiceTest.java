package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesReturnServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private BatchNumberService batchNumberService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private InvoiceRepository invoiceRepository;

    private SalesReturnService salesReturnService;
    private Company company;

    @BeforeEach
    void setup() {
        salesReturnService = new SalesReturnService(
                companyContextService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                batchNumberService,
                accountingFacade,
                journalEntryRepository,
                invoiceRepository,
                companyAccountingSettingsService,
                finishedGoodsService
        );
        company = new Company();
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(900L, 800L, null));
        lenient().when(journalEntryRepository.findByCompanyAndId(any(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void processReturn_postsSalesAndCogsJournals() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 99L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-1");
        attachPostedJournal(invoice, 901L);
        setField(invoice, "id", 10L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-1");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setDiscountAmount(new BigDecimal("20"));
        line.setTaxableAmount(new BigDecimal("180"));
        line.setTaxAmount(new BigDecimal("18"));
        line.setLineTotal(new BigDecimal("198")); // net + tax
        setField(line, "id", 55L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setValuationAccountId(500L);
        fg.setCogsAccountId(600L);
        fg.setRevenueAccountId(710L);
        fg.setDiscountAccountId(700L);
        fg.setTaxAccountId(800L);
        setField(fg, "id", 21L);

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.lockByCompanyAndId(company, 21L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.findByCompanyAndId(company, 21L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.save(any(FinishedGood.class))).thenAnswer(inv -> inv.getArgument(0));
        when(finishedGoodBatchRepository.save(any(FinishedGoodBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchNumberService.nextFinishedGoodBatchCode(any(), any())).thenReturn("RET-BATCH");
        when(inventoryMovementRepository.save(any(InventoryMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("99");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("1"));
        dispatchMovement.setUnitCost(new BigDecimal("50"));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("99"))
        ).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1:")
        )).thenReturn(List.of());

        JournalEntryDto salesReturnEntry = stubEntry(100L);
        when(accountingFacade.postSalesReturn(
                anyLong(),
                anyString(),
                anyMap(),
                any(BigDecimal.class),
                anyString())
        ).thenReturn(salesReturnEntry);
        when(accountingFacade.postInventoryAdjustment(
                anyString(),
                anyString(),
                anyLong(),
                anyMap(),
                anyBoolean(),
                anyBoolean(),
                anyString())
        ).thenReturn(stubEntry(101L));

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Damaged goods",
                List.of(new SalesReturnRequest.ReturnLine(55L, new BigDecimal("1")))
        );

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(100L);

        ArgumentCaptor<Map<Long, BigDecimal>> returnLinesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(accountingFacade).postSalesReturn(
                eq(dealer.getId()),
                eq("INV-1"),
                returnLinesCaptor.capture(),
                argThat(total -> total.compareTo(new BigDecimal("99")) == 0),
                eq("Damaged goods")
        );
        Map<Long, BigDecimal> capturedReturnLines = returnLinesCaptor.getValue();
        assertThat(capturedReturnLines.get(710L)).isEqualByComparingTo("100");
        assertThat(capturedReturnLines.get(700L)).isEqualByComparingTo("-10");
        assertThat(capturedReturnLines.get(800L)).isEqualByComparingTo("9");

        verify(accountingFacade).postInventoryAdjustment(
                eq("SALES_RETURN_COGS"),
                eq("CRN-INV-1-COGS-0"),
                eq(600L),
                argThat(lines -> lines.containsKey(500L)
                        && new BigDecimal("50").compareTo(lines.get(500L)) == 0),
                eq(true),
                eq(false),
                contains("COGS reversal")
        );
        verify(inventoryMovementRepository).save(argThat(movement -> movement.getJournalEntryId() != null
                && movement.getJournalEntryId().equals(100L)));
    }

    @Test
    void previewReturn_reportsLinkedImpactBeforePosting() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 199L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-PREVIEW-1");
        attachPostedJournal(invoice, 908L);
        setField(invoice, "id", 110L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-PREVIEW");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("200"));
        line.setTaxAmount(new BigDecimal("20"));
        line.setLineTotal(new BigDecimal("220"));
        setField(line, "id", 111L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-PREVIEW");
        setField(fg, "id", 211L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("199");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("55"));

        when(invoiceRepository.lockByCompanyAndId(company, 110L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-PREVIEW")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("199")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-PREVIEW-1")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-PREVIEW-1:")
        )).thenReturn(List.of());

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                110L,
                "Preview only",
                List.of(new SalesReturnRequest.ReturnLine(111L, BigDecimal.ONE))
        ));

        assertThat(preview.invoiceId()).isEqualTo(110L);
        assertThat(preview.totalReturnAmount()).isEqualByComparingTo("110.00");
        assertThat(preview.totalInventoryValue()).isEqualByComparingTo("55.00");
        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("1.00");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("55.0000");
        });
    }

    @Test
    void processReturn_rejectsDraftInvoiceMutationPath() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-DRAFT-1");
        invoice.setStatus("DRAFT");
        setField(invoice, "id", 120L);

        when(invoiceRepository.lockByCompanyAndId(company, 120L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                120L,
                "Draft correction",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void previewReturn_rejectsInvoiceWithoutPostedJournal() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-NO-JOURNAL");
        invoice.setStatus("POSTED");
        setField(invoice, "id", 121L);

        when(invoiceRepository.lockByCompanyAndId(company, 121L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                121L,
                "Preview guard",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void previewReturn_rejectsMissingInvoice() {
        when(invoiceRepository.lockByCompanyAndId(company, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                999L,
                "Missing invoice",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice not found: id=999");
    }

    @Test
    void previewReturn_rejectsVoidedInvoiceMutationPath() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-VOID-1");
        attachPostedJournal(invoice, 910L);
        invoice.setStatus("VOID");
        setField(invoice, "id", 122L);

        when(invoiceRepository.lockByCompanyAndId(company, 122L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                122L,
                "Preview void",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void ensurePostedInvoice_allowsNullInvoice() {
        invokeEnsurePostedInvoice(null);
    }

    @Test
    void ensurePostedInvoice_rejectsReversedAndBlankStatus() {
        Invoice reversed = new Invoice();
        reversed.setCompany(company);
        reversed.setInvoiceNumber("INV-REVERSED-1");
        attachPostedJournal(reversed, 911L);
        reversed.setStatus("REVERSED");

        assertThatThrownBy(() -> invokeEnsurePostedInvoice(reversed))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        Invoice blankStatus = new Invoice();
        blankStatus.setCompany(company);
        blankStatus.setInvoiceNumber("INV-BLANK-1");
        attachPostedJournal(blankStatus, 912L);
        blankStatus.setStatus("   ");

        assertThatThrownBy(() -> invokeEnsurePostedInvoice(blankStatus))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        Invoice draftStatus = new Invoice();
        draftStatus.setCompany(company);
        draftStatus.setInvoiceNumber("INV-DRAFT-1");
        attachPostedJournal(draftStatus, 913L);
        draftStatus.setStatus("DRAFT");

        assertThatThrownBy(() -> invokeEnsurePostedInvoice(draftStatus))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        Invoice journalWithoutId = new Invoice();
        journalWithoutId.setCompany(company);
        journalWithoutId.setInvoiceNumber("INV-JE-NO-ID");
        journalWithoutId.setJournalEntry(new JournalEntry());
        journalWithoutId.setStatus("POSTED");

        assertThatThrownBy(() -> invokeEnsurePostedInvoice(journalWithoutId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void previewReturn_withoutSalesOrderFailsFastWithoutDispatchLookup() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 280L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NO-SO");
        attachPostedJournal(invoice, 9300L);
        setField(invoice, "id", 2800L);

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-NO-SO");
        requestedLine.setQuantity(new BigDecimal("2"));
        requestedLine.setUnitPrice(new BigDecimal("40"));
        requestedLine.setTaxableAmount(new BigDecimal("80"));
        requestedLine.setTaxAmount(new BigDecimal("8"));
        requestedLine.setLineTotal(new BigDecimal("88"));
        setField(requestedLine, "id", 2801L);
        invoice.getLines().add(requestedLine);

        InvoiceLine siblingWithoutId = new InvoiceLine();
        siblingWithoutId.setInvoice(invoice);
        siblingWithoutId.setProductCode("FG-NO-SO");
        siblingWithoutId.setQuantity(BigDecimal.ONE);
        invoice.getLines().add(siblingWithoutId);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-NO-SO");
        setField(fg, "id", 3801L);

        when(invoiceRepository.lockByCompanyAndId(company, 2800L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-NO-SO")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq("SALES_RETURN"), eq("INV-NO-SO"))).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company), eq("SALES_RETURN"), eq("INV-NO-SO:"))).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                2800L,
                "No sales order",
                List.of(new SalesReturnRequest.ReturnLine(2801L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("dispatch cost layers");
        verify(inventoryMovementRepository, never()).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq(InventoryReference.SALES_ORDER), anyString());
    }

    @Test
    void validateReturnQuantities_rejectsUnknownInvoiceLine() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-DIRECT-UNKNOWN");

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Direct validation",
                        List.of(new SalesReturnRequest.ReturnLine(999L, BigDecimal.ONE))
                ),
                Map.of(),
                new java.util.HashMap<>()
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice line not found: 999");
    }

    @Test
    void validateReturnQuantities_rejectsQuantityBeyondInvoicedAmount() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-DIRECT-QTY");

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-DIRECT");
        line.setQuantity(BigDecimal.ONE);
        setField(line, "id", 301L);
        invoice.getLines().add(line);

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Direct quantity",
                        List.of(new SalesReturnRequest.ReturnLine(301L, new BigDecimal("2")))
                ),
                Map.of(301L, line),
                new java.util.HashMap<>()
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return quantity exceeds invoiced amount for FG-DIRECT");
    }

    @Test
    void validateReturnQuantities_rejectsRemainingAmountAfterLegacyAllocationSkipsStaleMappings() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-DIRECT-LEGACY");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-DIRECT");
        requestedLine.setQuantity(new BigDecimal("3"));
        setField(requestedLine, "id", 401L);
        invoice.getLines().add(requestedLine);

        InvoiceLine staleInvoiceLine = new InvoiceLine();
        staleInvoiceLine.setInvoice(invoice);
        staleInvoiceLine.setProductCode("FG-DIRECT");
        staleInvoiceLine.setQuantity(BigDecimal.ONE);
        setField(staleInvoiceLine, "id", 402L);
        invoice.getLines().add(staleInvoiceLine);

        InvoiceLine ghostLine = new InvoiceLine();
        ghostLine.setInvoice(invoice);
        ghostLine.setProductCode("FG-GHOST");
        ghostLine.setQuantity(BigDecimal.ONE);
        setField(ghostLine, "id", 450L);

        FinishedGood directFg = new FinishedGood();
        directFg.setCompany(company);
        directFg.setProductCode("FG-DIRECT");
        setField(directFg, "id", 501L);

        FinishedGood ghostFg = new FinishedGood();
        ghostFg.setCompany(company);
        ghostFg.setProductCode("FG-GHOST");
        setField(ghostFg, "id", 502L);

        InventoryMovement legacyAlloc = new InventoryMovement();
        legacyAlloc.setFinishedGood(directFg);
        legacyAlloc.setReferenceType("SALES_RETURN");
        legacyAlloc.setReferenceId("INV-DIRECT-LEGACY");
        legacyAlloc.setQuantity(new BigDecimal("2"));

        InventoryMovement fullyReturnedLine = new InventoryMovement();
        fullyReturnedLine.setFinishedGood(directFg);
        fullyReturnedLine.setReferenceType("SALES_RETURN");
        fullyReturnedLine.setReferenceId("INV-DIRECT-LEGACY:401");
        fullyReturnedLine.setQuantity(new BigDecimal("3"));

        InventoryMovement ghostReturnedLine = new InventoryMovement();
        ghostReturnedLine.setFinishedGood(ghostFg);
        ghostReturnedLine.setReferenceType("SALES_RETURN");
        ghostReturnedLine.setReferenceId("INV-DIRECT-LEGACY:450");
        ghostReturnedLine.setQuantity(BigDecimal.ONE);

        InventoryMovement unknownReturnedLine = new InventoryMovement();
        unknownReturnedLine.setFinishedGood(ghostFg);
        unknownReturnedLine.setReferenceType("SALES_RETURN");
        unknownReturnedLine.setReferenceId("INV-DIRECT-LEGACY:999");
        unknownReturnedLine.setQuantity(BigDecimal.ONE);

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-DIRECT")).thenReturn(Optional.of(directFg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-DIRECT-LEGACY")
        )).thenReturn(List.of(legacyAlloc));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-DIRECT-LEGACY:")
        )).thenReturn(List.of(fullyReturnedLine, ghostReturnedLine, unknownReturnedLine));

        java.util.Map<Long, InvoiceLine> invoiceLines = new java.util.LinkedHashMap<>();
        invoiceLines.put(401L, requestedLine);
        invoiceLines.put(450L, ghostLine);

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Legacy validation",
                        List.of(new SalesReturnRequest.ReturnLine(401L, BigDecimal.ONE))
                ),
                invoiceLines,
                new java.util.HashMap<>()
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount for FG-DIRECT");
    }

    @Test
    void processReturn_rejectsWhenPriorReturnsExceedInvoiceQuantity() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-1");
        attachPostedJournal(invoice, 902L);
        setField(invoice, "id", 10L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-1");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setLineTotal(new BigDecimal("200"));
        setField(line, "id", 55L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setValuationAccountId(500L);
        fg.setCogsAccountId(600L);
        fg.setRevenueAccountId(710L);
        fg.setDiscountAccountId(700L);
        fg.setTaxAccountId(800L);
        setField(fg, "id", 21L);

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setQuantity(new BigDecimal("1.5"));
        priorReturn.setReferenceType("SALES_RETURN");
        priorReturn.setReferenceId("INV-1");

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of(priorReturn));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1:")
        )).thenReturn(List.of());

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Damaged goods",
                List.of(new SalesReturnRequest.ReturnLine(55L, new BigDecimal("1")))
        );

        assertThatThrownBy(() -> salesReturnService.processReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount");
    }

    @Test
    void processReturn_rejectsDuplicateProductLineOverReturn() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-1");
        attachPostedJournal(invoice, 903L);
        setField(invoice, "id", 10L);

        InvoiceLine firstLine = new InvoiceLine();
        firstLine.setInvoice(invoice);
        firstLine.setProductCode("FG-1");
        firstLine.setQuantity(new BigDecimal("1"));
        setField(firstLine, "id", 55L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-1");
        secondLine.setQuantity(new BigDecimal("1"));
        setField(secondLine, "id", 56L);
        invoice.getLines().add(secondLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setRevenueAccountId(710L);
        setField(fg, "id", 21L);

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setQuantity(new BigDecimal("1"));
        priorReturn.setReferenceType("SALES_RETURN");
        priorReturn.setReferenceId("INV-1:55");

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1:")
        )).thenReturn(List.of(priorReturn));

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Duplicate line return",
                List.of(new SalesReturnRequest.ReturnLine(55L, new BigDecimal("1")))
        );

        assertThatThrownBy(() -> salesReturnService.processReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount");
    }

    @Test
    void processReturn_rejectsLegacyReturnOverLine() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-1");
        attachPostedJournal(invoice, 904L);
        setField(invoice, "id", 10L);

        InvoiceLine firstLine = new InvoiceLine();
        firstLine.setInvoice(invoice);
        firstLine.setProductCode("FG-1");
        firstLine.setQuantity(new BigDecimal("1"));
        setField(firstLine, "id", 55L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-1");
        secondLine.setQuantity(new BigDecimal("1"));
        setField(secondLine, "id", 56L);
        invoice.getLines().add(secondLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setRevenueAccountId(710L);
        setField(fg, "id", 21L);

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setQuantity(new BigDecimal("1"));
        priorReturn.setReferenceType("SALES_RETURN");
        priorReturn.setReferenceId("INV-1");

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of(priorReturn));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1:")
        )).thenReturn(List.of());

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Legacy return",
                List.of(new SalesReturnRequest.ReturnLine(55L, new BigDecimal("1")))
        );

        assertThatThrownBy(() -> salesReturnService.processReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount");
    }

    @Test
    void previewReturn_allocatesLegacyReturnToEarliestLineBeforeRemainingValidation() {
        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 501L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-LEGACY-ALLOC");
        attachPostedJournal(invoice, 911L);
        setField(invoice, "id", 123L);

        InvoiceLine firstLine = new InvoiceLine();
        firstLine.setInvoice(invoice);
        firstLine.setProductCode("FG-ALLOC");
        firstLine.setQuantity(BigDecimal.ONE);
        firstLine.setUnitPrice(new BigDecimal("100"));
        firstLine.setTaxableAmount(new BigDecimal("100"));
        firstLine.setTaxAmount(BigDecimal.ZERO);
        firstLine.setLineTotal(new BigDecimal("100"));
        setField(firstLine, "id", 201L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-ALLOC");
        secondLine.setQuantity(BigDecimal.ONE);
        secondLine.setUnitPrice(new BigDecimal("100"));
        secondLine.setTaxableAmount(new BigDecimal("100"));
        secondLine.setTaxAmount(BigDecimal.ZERO);
        secondLine.setLineTotal(new BigDecimal("100"));
        setField(secondLine, "id", 202L);
        invoice.getLines().add(secondLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-ALLOC");
        setField(fg, "id", 301L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("501");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("50"));

        InventoryMovement legacyReturn = new InventoryMovement();
        legacyReturn.setFinishedGood(fg);
        legacyReturn.setReferenceType("SALES_RETURN");
        legacyReturn.setReferenceId("INV-LEGACY-ALLOC");
        legacyReturn.setQuantity(BigDecimal.ONE);

        when(invoiceRepository.lockByCompanyAndId(company, 123L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-ALLOC")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("501")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-ALLOC")
        )).thenReturn(List.of(legacyReturn));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-ALLOC:")
        )).thenReturn(List.of());

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                123L,
                "Legacy allocation",
                List.of(new SalesReturnRequest.ReturnLine(202L, BigDecimal.ONE))
        ));

        assertThat(preview.totalReturnAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.totalInventoryValue()).isEqualByComparingTo("50.00");
        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.invoiceLineId()).isEqualTo(202L);
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("0.00");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("50.0000");
        });
    }

    @Test
    void previewReturn_allocatesLegacyBalanceAfterSkippingFullyReturnedAndUnrequestedLines() {
        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 502L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-LEGACY-MIX");
        attachPostedJournal(invoice, 912L);
        setField(invoice, "id", 124L);

        InvoiceLine firstLine = new InvoiceLine();
        firstLine.setInvoice(invoice);
        firstLine.setProductCode("FG-ALLOC");
        firstLine.setQuantity(BigDecimal.ONE);
        firstLine.setUnitPrice(new BigDecimal("100"));
        firstLine.setTaxableAmount(new BigDecimal("100"));
        firstLine.setTaxAmount(BigDecimal.ZERO);
        firstLine.setLineTotal(new BigDecimal("100"));
        setField(firstLine, "id", 211L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-ALLOC");
        secondLine.setQuantity(new BigDecimal("3"));
        secondLine.setUnitPrice(new BigDecimal("100"));
        secondLine.setTaxableAmount(new BigDecimal("300"));
        secondLine.setTaxAmount(BigDecimal.ZERO);
        secondLine.setLineTotal(new BigDecimal("300"));
        setField(secondLine, "id", 212L);
        invoice.getLines().add(secondLine);

        InvoiceLine unrelatedLine = new InvoiceLine();
        unrelatedLine.setInvoice(invoice);
        unrelatedLine.setProductCode("FG-OTHER");
        unrelatedLine.setQuantity(BigDecimal.ONE);
        unrelatedLine.setUnitPrice(new BigDecimal("25"));
        unrelatedLine.setTaxableAmount(new BigDecimal("25"));
        unrelatedLine.setTaxAmount(BigDecimal.ZERO);
        unrelatedLine.setLineTotal(new BigDecimal("25"));
        setField(unrelatedLine, "id", 213L);
        invoice.getLines().add(unrelatedLine);

        FinishedGood allocFg = new FinishedGood();
        allocFg.setCompany(company);
        allocFg.setProductCode("FG-ALLOC");
        setField(allocFg, "id", 311L);

        FinishedGood otherFg = new FinishedGood();
        otherFg.setCompany(company);
        otherFg.setProductCode("FG-OTHER");
        setField(otherFg, "id", 312L);

        InventoryMovement allocDispatch = new InventoryMovement();
        allocDispatch.setFinishedGood(allocFg);
        allocDispatch.setReferenceType(InventoryReference.SALES_ORDER);
        allocDispatch.setReferenceId("502");
        allocDispatch.setMovementType("DISPATCH");
        allocDispatch.setQuantity(new BigDecimal("4"));
        allocDispatch.setUnitCost(new BigDecimal("50"));

        InventoryMovement legacyAlloc = new InventoryMovement();
        legacyAlloc.setFinishedGood(allocFg);
        legacyAlloc.setReferenceType("SALES_RETURN");
        legacyAlloc.setReferenceId("INV-LEGACY-MIX");
        legacyAlloc.setQuantity(new BigDecimal("2"));

        InventoryMovement lineReturned = new InventoryMovement();
        lineReturned.setFinishedGood(allocFg);
        lineReturned.setReferenceType("SALES_RETURN");
        lineReturned.setReferenceId("INV-LEGACY-MIX:211");
        lineReturned.setQuantity(BigDecimal.ONE);

        InventoryMovement unrelatedProductLineReturned = new InventoryMovement();
        unrelatedProductLineReturned.setFinishedGood(otherFg);
        unrelatedProductLineReturned.setReferenceType("SALES_RETURN");
        unrelatedProductLineReturned.setReferenceId("INV-LEGACY-MIX:213");
        unrelatedProductLineReturned.setQuantity(BigDecimal.ONE);

        InventoryMovement unknownLineReturned = new InventoryMovement();
        unknownLineReturned.setFinishedGood(otherFg);
        unknownLineReturned.setReferenceType("SALES_RETURN");
        unknownLineReturned.setReferenceId("INV-LEGACY-MIX:999");
        unknownLineReturned.setQuantity(BigDecimal.ONE);

        when(invoiceRepository.lockByCompanyAndId(company, 124L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-ALLOC")).thenReturn(Optional.of(allocFg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("502")
        )).thenReturn(List.of(allocDispatch));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-MIX")
        )).thenReturn(List.of(legacyAlloc));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-MIX:")
        )).thenReturn(List.of(lineReturned, unrelatedProductLineReturned, unknownLineReturned));

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                124L,
                "Legacy allocation mix",
                List.of(new SalesReturnRequest.ReturnLine(212L, BigDecimal.ONE))
        ));

        assertThat(preview.totalReturnAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.totalInventoryValue()).isEqualByComparingTo("50.00");
        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.invoiceLineId()).isEqualTo(212L);
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("2.00");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("50.0000");
        });
    }

    @Test
    void processReturn_ignoresUnrelatedInvoicePrefixMovements() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 70L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 7L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-1");
        attachPostedJournal(invoice, 905L);
        setField(invoice, "id", 10L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-1");
        line.setQuantity(new BigDecimal("1"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 55L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setRevenueAccountId(710L);
        setField(fg, "id", 21L);

        InventoryMovement unrelatedReturn = new InventoryMovement();
        unrelatedReturn.setFinishedGood(fg);
        unrelatedReturn.setQuantity(new BigDecimal("1"));
        unrelatedReturn.setReferenceType("SALES_RETURN");
        unrelatedReturn.setReferenceId("INV-10:999");

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1:")
        )).thenReturn(List.of(unrelatedReturn));

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Prefix safety",
                List.of(new SalesReturnRequest.ReturnLine(55L, new BigDecimal("1")))
        );

        assertThatThrownBy(() -> salesReturnService.processReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("dispatch cost layers");
    }

    @Test
    void processReturn_replaySkipsRestockAndReusesAccountingReplay() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 72L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 9L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-REPLAY-1");
        attachPostedJournal(invoice, 906L);
        setField(invoice, "id", 30L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-REPLAY");
        line.setQuantity(new BigDecimal("1"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 77L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-REPLAY");
        fg.setRevenueAccountId(712L);
        setField(fg, "id", 23L);

        when(invoiceRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-REPLAY")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-1"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay return")
        )).thenReturn(stubEntry(120L));

        SalesReturnRequest request = new SalesReturnRequest(
                30L,
                "Replay return",
                List.of(new SalesReturnRequest.ReturnLine(77L, new BigDecimal("1")))
        );

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(120L);
        verify(accountingFacade).postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-1"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay return")
        );
        verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
        verify(finishedGoodBatchRepository, never()).save(any(FinishedGoodBatch.class));
        verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        verify(accountingFacade, never()).postInventoryAdjustment(anyString(), anyString(), anyLong(), anyMap(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void processReturn_replayWithNullAccountingReplayIdSkipsMetadataAndRelink() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 84L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 14L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-REPLAY-NULL");
        JournalEntry sourceJournal = new JournalEntry();
        setField(sourceJournal, "id", 9063L);
        sourceJournal.setStatus("POSTED");
        invoice.setJournalEntry(sourceJournal);
        invoice.setStatus("POSTED");
        setField(invoice, "id", 132L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-REPLAY-NULL");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 179L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-REPLAY-NULL");
        fg.setRevenueAccountId(715L);
        setField(fg, "id", 225L);

        when(invoiceRepository.lockByCompanyAndId(company, 132L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-REPLAY-NULL")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-NULL"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay null")
        )).thenReturn(journalEntryDto(null, null, null, null));

        JournalEntryDto result = salesReturnService.processReturn(new SalesReturnRequest(
                132L,
                "Replay null",
                List.of(new SalesReturnRequest.ReturnLine(179L, BigDecimal.ONE))
        ));

        assertThat(result.id()).isNull();
        verify(journalEntryRepository, never()).findByCompanyAndId(any(), anyLong());
        verify(inventoryMovementRepository, never()).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                any(),
                anyString(),
                anyString()
        );
    }

    @Test
    void processReturn_replayWithNullAccountingReplaySkipsMetadataAndRelink() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 184L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 24L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-REPLAY-NIL");
        attachPostedJournal(invoice, 9164L);
        setField(invoice, "id", 182L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-REPLAY-NIL");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 279L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-REPLAY-NIL");
        fg.setRevenueAccountId(725L);
        setField(fg, "id", 325L);

        when(invoiceRepository.lockByCompanyAndId(company, 182L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-REPLAY-NIL")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-NIL"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay nil")
        )).thenReturn(null);

        JournalEntryDto result = salesReturnService.processReturn(new SalesReturnRequest(
                182L,
                "Replay nil",
                List.of(new SalesReturnRequest.ReturnLine(279L, BigDecimal.ONE))
        ));

        assertThat(result).isNull();
        verify(journalEntryRepository, never()).findByCompanyAndId(any(), anyLong());
        verify(inventoryMovementRepository, never()).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                any(),
                anyString(),
                anyString()
        );
    }

    @Test
    void processReturn_withoutSalesOrderAndNullAccountingEntryFailsBeforeCogsPosting() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Direct partner");
        Account receivable = new Account();
        setField(receivable, "id", 185L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 25L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NO-SO-POST");
        attachPostedJournal(invoice, 9165L);
        setField(invoice, "id", 183L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-NO-SO-POST");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("50"));
        line.setTaxableAmount(new BigDecimal("50"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("50"));
        setField(line, "id", 280L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-NO-SO-POST");
        fg.setValuationAccountId(901L);
        fg.setCogsAccountId(902L);
        fg.setRevenueAccountId(903L);
        setField(fg, "id", 326L);

        when(invoiceRepository.lockByCompanyAndId(company, 183L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-NO-SO-POST")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq("SALES_RETURN"), eq("INV-NO-SO-POST"))).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company), eq("SALES_RETURN"), eq("INV-NO-SO-POST:"))).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                183L,
                "No SO",
                List.of(new SalesReturnRequest.ReturnLine(280L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("dispatch cost layers");
        verify(inventoryMovementRepository, never()).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq(InventoryReference.SALES_ORDER), anyString());
        verify(accountingFacade, never()).postInventoryAdjustment(anyString(), anyString(), anyLong(), anyMap(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void processReturn_replayWithAlignedMetadataSkipsPersistence() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 83L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 13L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-REPLAY-CLEAN");
        JournalEntry sourceJournal = new JournalEntry();
        setField(sourceJournal, "id", 9062L);
        sourceJournal.setStatus("POSTED");
        invoice.setJournalEntry(sourceJournal);
        invoice.setStatus("POSTED");
        setField(invoice, "id", 131L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-REPLAY-CLEAN");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 178L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-REPLAY-CLEAN");
        fg.setRevenueAccountId(714L);
        setField(fg, "id", 224L);

        JournalEntry replayJournal = new JournalEntry();
        setField(replayJournal, "id", 1202L);
        replayJournal.setStatus("POSTED");
        replayJournal.setCorrectionType(JournalCorrectionType.REVERSAL);
        replayJournal.setCorrectionReason("SALES_RETURN");
        replayJournal.setSourceModule("SALES_RETURN");
        replayJournal.setSourceReference("INV-REPLAY-CLEAN");

        SalesReturnRequest request = new SalesReturnRequest(
                131L,
                "Replay clean",
                List.of(new SalesReturnRequest.ReturnLine(178L, BigDecimal.ONE))
        );
        String returnKey = invokeBuildReturnIdempotencyKey(invoice, request);

        InventoryMovement matchingMovement = new InventoryMovement();
        matchingMovement.setReferenceType("SALES_RETURN");
        matchingMovement.setReferenceId("INV-REPLAY-CLEAN:178:RET-" + returnKey);
        matchingMovement.setJournalEntryId(1202L);

        when(invoiceRepository.lockByCompanyAndId(company, 131L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-REPLAY-CLEAN")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-CLEAN"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay clean")
        )).thenReturn(stubEntry(1202L));
        when(journalEntryRepository.findByCompanyAndId(company, 1202L)).thenReturn(Optional.of(replayJournal));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-REPLAY-CLEAN:")
        )).thenReturn(List.of(matchingMovement));

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(1202L);
        verify(journalEntryRepository, never()).save(replayJournal);
        verify(inventoryMovementRepository, never()).saveAll(any());
        verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
        verify(finishedGoodBatchRepository, never()).save(any(FinishedGoodBatch.class));
        verify(accountingFacade, never()).postInventoryAdjustment(anyString(), anyString(), anyLong(), anyMap(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void processReturn_replayRelinksMatchingReturnMovementsAndCorrectionMetadata() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 82L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 12L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-REPLAY-LINK");
        JournalEntry sourceJournal = new JournalEntry();
        setField(sourceJournal, "id", 9061L);
        sourceJournal.setStatus("POSTED");
        invoice.setJournalEntry(sourceJournal);
        invoice.setStatus("POSTED");
        setField(invoice, "id", 130L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-RELINK");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 177L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-RELINK");
        fg.setRevenueAccountId(713L);
        setField(fg, "id", 223L);

        JournalEntry replayJournal = new JournalEntry();
        setField(replayJournal, "id", 1201L);
        replayJournal.setStatus("POSTED");

        SalesReturnRequest request = new SalesReturnRequest(
                130L,
                "Replay relink",
                List.of(new SalesReturnRequest.ReturnLine(177L, BigDecimal.ONE))
        );
        String returnKey = invokeBuildReturnIdempotencyKey(invoice, request);

        InventoryMovement matchingMovement = new InventoryMovement();
        matchingMovement.setReferenceType("SALES_RETURN");
        matchingMovement.setReferenceId("INV-REPLAY-LINK:177:RET-" + returnKey);
        matchingMovement.setJournalEntryId(null);

        InventoryMovement unrelatedMovement = new InventoryMovement();
        unrelatedMovement.setReferenceType("SALES_RETURN");
        unrelatedMovement.setReferenceId("INV-REPLAY-LINK:999:RET-" + returnKey);
        unrelatedMovement.setJournalEntryId(44L);

        when(invoiceRepository.lockByCompanyAndId(company, 130L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-RELINK")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-REPLAY-LINK"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay relink")
        )).thenReturn(stubEntry(1201L));
        when(journalEntryRepository.findByCompanyAndId(company, 1201L)).thenReturn(Optional.of(replayJournal));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-REPLAY-LINK:")
        )).thenReturn(List.of(matchingMovement, unrelatedMovement));

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(1201L);
        assertThat(replayJournal.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
        assertThat(replayJournal.getCorrectionReason()).isEqualTo("SALES_RETURN");
        assertThat(replayJournal.getSourceModule()).isEqualTo("SALES_RETURN");
        assertThat(replayJournal.getSourceReference()).isEqualTo("INV-REPLAY-LINK");
        verify(journalEntryRepository).save(replayJournal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryMovement>> movementCaptor = ArgumentCaptor.forClass(List.class);
        verify(inventoryMovementRepository).saveAll(movementCaptor.capture());
        List<InventoryMovement> savedMovements = movementCaptor.getValue();
        assertThat(savedMovements).containsExactly(matchingMovement, unrelatedMovement);
        assertThat(matchingMovement.getJournalEntryId()).isEqualTo(1201L);
        assertThat(unrelatedMovement.getJournalEntryId()).isEqualTo(44L);
        verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
    }

    void previewReturn_rejectsMissingLines() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 84L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NO-LINES");
        attachPostedJournal(invoice, 910L);
        setField(invoice, "id", 141L);

        when(invoiceRepository.lockByCompanyAndId(company, 141L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                141L,
                "No lines",
                List.of()
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void previewReturn_rejectsNullLines() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 184L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NULL-LINES");
        attachPostedJournal(invoice, 9101L);
        setField(invoice, "id", 1411L);

        when(invoiceRepository.lockByCompanyAndId(company, 1411L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                1411L,
                "Null lines",
                null
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void previewReturn_rejectsUnknownInvoiceLine() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 85L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-MISSING-LINE");
        attachPostedJournal(invoice, 911L);
        setField(invoice, "id", 142L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-MISSING");
        line.setQuantity(BigDecimal.ONE);
        setField(line, "id", 212L);
        invoice.getLines().add(line);

        when(invoiceRepository.lockByCompanyAndId(company, 142L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                142L,
                "Unknown line",
                List.of(new SalesReturnRequest.ReturnLine(999L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice line not found: 999");
    }

    @Test
    void previewReturn_ignoresMalformedLineReturnMovementsInHistory() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 186L);
        dealer.setReceivableAccount(receivable);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 399L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-MALFORMED-HISTORY");
        attachPostedJournal(invoice, 9103L);
        setField(invoice, "id", 143L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-MALFORMED");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("60"));
        line.setTaxableAmount(new BigDecimal("120"));
        line.setTaxAmount(new BigDecimal("12"));
        line.setLineTotal(new BigDecimal("132"));
        setField(line, "id", 222L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-MALFORMED");
        setField(fg, "id", 322L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("399");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("30"));

        InventoryMovement malformedReturn = new InventoryMovement();
        malformedReturn.setFinishedGood(fg);
        malformedReturn.setReferenceType("SALES_RETURN");
        malformedReturn.setReferenceId("INV-MALFORMED-HISTORY:not-a-line");
        malformedReturn.setQuantity(new BigDecimal("0.75"));

        when(invoiceRepository.lockByCompanyAndId(company, 143L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-MALFORMED")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("399")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-MALFORMED-HISTORY")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-MALFORMED-HISTORY:")
        )).thenReturn(List.of(malformedReturn));

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                143L,
                "Malformed history",
                List.of(new SalesReturnRequest.ReturnLine(222L, BigDecimal.ONE))
        ));

        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.alreadyReturnedQuantity()).isEqualByComparingTo("0.00");
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("1.00");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("30.0000");
        });
    }

    @Test
    void previewReturn_capsRemainingQuantityAtZeroWhenRequestConsumesBalance() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 84L);
        dealer.setReceivableAccount(receivable);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 299L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-PREVIEW-2");
        attachPostedJournal(invoice, 909L);
        setField(invoice, "id", 150L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-PREVIEW-2");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("50"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(new BigDecimal("10"));
        line.setLineTotal(new BigDecimal("110"));
        setField(line, "id", 211L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-PREVIEW-2");
        setField(fg, "id", 311L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("299");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("20"));

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setReferenceType("SALES_RETURN");
        priorReturn.setReferenceId("INV-PREVIEW-2:211");
        priorReturn.setQuantity(new BigDecimal("1.5"));

        when(invoiceRepository.lockByCompanyAndId(company, 150L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-PREVIEW-2")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("299")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-PREVIEW-2")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-PREVIEW-2:")
        )).thenReturn(List.of(priorReturn));

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                150L,
                "Preview exact remainder",
                List.of(new SalesReturnRequest.ReturnLine(211L, new BigDecimal("0.5")))
        ));

        assertThat(preview.totalReturnAmount()).isEqualByComparingTo("27.50");
        assertThat(preview.totalInventoryValue()).isEqualByComparingTo("10.00");
        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.alreadyReturnedQuantity()).isEqualByComparingTo("1.50");
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("0.00");
            assertThat(linePreview.lineAmount()).isEqualByComparingTo("27.50");
            assertThat(linePreview.taxAmount()).isEqualByComparingTo("2.50");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("20.0000");
            assertThat(linePreview.inventoryValue()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void previewReturn_rejectsWhenDispatchQuantityIsInsufficient() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 187L);
        dealer.setReceivableAccount(receivable);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 499L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-SHORT-DISPATCH");
        attachPostedJournal(invoice, 9104L);
        setField(invoice, "id", 144L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-SHORT");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("40"));
        line.setTaxableAmount(new BigDecimal("80"));
        line.setTaxAmount(new BigDecimal("8"));
        line.setLineTotal(new BigDecimal("88"));
        setField(line, "id", 223L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-SHORT");
        setField(fg, "id", 323L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("499");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("0.50"));
        dispatchMovement.setUnitCost(new BigDecimal("25"));

        when(invoiceRepository.lockByCompanyAndId(company, 144L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-SHORT")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("499")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-SHORT-DISPATCH")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-SHORT-DISPATCH:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                144L,
                "Short dispatch",
                List.of(new SalesReturnRequest.ReturnLine(223L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return quantity exceeds dispatched quantity for FG-SHORT");
    }

    @Test
    void processReturn_rejectsInvoiceWithoutDealerReceivableContext() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-NO-DEALER");
        attachPostedJournal(invoice, 912L);
        setField(invoice, "id", 160L);

        when(invoiceRepository.lockByCompanyAndId(company, 160L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                160L,
                "Dealer context missing",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("missing dealer receivable context");
    }

    @Test
    void processReturn_rejectsNullLines() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 188L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 18L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-PROCESS-NULL");
        attachPostedJournal(invoice, 9121L);
        setField(invoice, "id", 1601L);

        when(invoiceRepository.lockByCompanyAndId(company, 1601L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                1601L,
                "Null lines",
                null
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void processReturn_rejectsZeroValuedReturnAmount() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 86L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 10L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-ZERO");
        attachPostedJournal(invoice, 913L);
        setField(invoice, "id", 161L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-ZERO");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.ZERO);
        line.setTaxableAmount(BigDecimal.ZERO);
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(BigDecimal.ZERO);
        setField(line, "id", 213L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-ZERO");
        fg.setRevenueAccountId(714L);
        setField(fg, "id", 214L);

        when(invoiceRepository.lockByCompanyAndId(company, 161L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-ZERO")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-ZERO")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-ZERO:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                161L,
                "Zero value",
                List.of(new SalesReturnRequest.ReturnLine(213L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return amount must be greater than zero");
    }

    @Test
    void processReturn_rejectsMissingDiscountAccountWhenReturnIncludesDiscount() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 87L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 11L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-DISCOUNT");
        attachPostedJournal(invoice, 914L);
        setField(invoice, "id", 162L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-DISCOUNT");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setDiscountAmount(new BigDecimal("10"));
        line.setTaxableAmount(new BigDecimal("90"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("90"));
        setField(line, "id", 214L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-DISCOUNT");
        fg.setRevenueAccountId(715L);
        fg.setDiscountAccountId(null);
        setField(fg, "id", 215L);

        when(invoiceRepository.lockByCompanyAndId(company, 162L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-DISCOUNT")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-DISCOUNT")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-DISCOUNT:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                162L,
                "Discount correction",
                List.of(new SalesReturnRequest.ReturnLine(214L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Discount account is required");
    }

    @Test
    void processReturn_rejectsTaxAccountMismatchAgainstConfiguredOutputTax() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 88L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 12L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-TAX-MISMATCH");
        attachPostedJournal(invoice, 915L);
        setField(invoice, "id", 163L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-TAX");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(new BigDecimal("18"));
        line.setLineTotal(new BigDecimal("118"));
        setField(line, "id", 215L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-TAX");
        fg.setRevenueAccountId(716L);
        fg.setTaxAccountId(999L);
        setField(fg, "id", 216L);

        when(invoiceRepository.lockByCompanyAndId(company, 163L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-TAX")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-TAX-MISMATCH")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-TAX-MISMATCH:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                163L,
                "Tax mismatch",
                List.of(new SalesReturnRequest.ReturnLine(215L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("tax account must match GST output account");
    }

    @Test
    void processReturn_normalizesGstInclusiveDiscounts() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Retail Partner");
        Account receivable = new Account();
        setField(receivable, "id", 71L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 8L);

        SalesOrder salesOrder = new SalesOrder();
        salesOrder.setGstInclusive(true);
        setField(salesOrder, "id", 101L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-2");
        attachPostedJournal(invoice, 907L);
        setField(invoice, "id", 20L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-2");
        line.setQuantity(new BigDecimal("1"));
        line.setUnitPrice(new BigDecimal("110"));
        line.setTaxRate(new BigDecimal("10"));
        line.setDiscountAmount(new BigDecimal("11"));
        line.setTaxableAmount(new BigDecimal("90"));
        line.setTaxAmount(new BigDecimal("9"));
        line.setLineTotal(new BigDecimal("99"));
        setField(line, "id", 66L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-2");
        fg.setValuationAccountId(501L);
        fg.setCogsAccountId(601L);
        fg.setRevenueAccountId(711L);
        fg.setDiscountAccountId(701L);
        fg.setTaxAccountId(800L);
        setField(fg, "id", 22L);

        when(invoiceRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-2")).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.findByCompanyAndId(company, 22L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.save(any(FinishedGood.class))).thenAnswer(inv -> inv.getArgument(0));
        when(finishedGoodBatchRepository.save(any(FinishedGoodBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchNumberService.nextFinishedGoodBatchCode(any(), any())).thenReturn("RET-BATCH-2");
        when(inventoryMovementRepository.save(any(InventoryMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("101");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("1"));
        dispatchMovement.setUnitCost(new BigDecimal("50"));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("101"))
        ).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-2")
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-2:")
        )).thenReturn(List.of());

        when(accountingFacade.postSalesReturn(
                anyLong(),
                anyString(),
                anyMap(),
                any(BigDecimal.class),
                anyString())
        ).thenReturn(stubEntry(110L));
        SalesReturnRequest request = new SalesReturnRequest(
                20L,
                "Inclusive return",
                List.of(new SalesReturnRequest.ReturnLine(66L, new BigDecimal("1")))
        );

        JournalEntryDto result = salesReturnService.processReturn(request);
        assertThat(result.id()).isEqualTo(110L);

        ArgumentCaptor<Map<Long, BigDecimal>> returnLinesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(accountingFacade).postSalesReturn(
                eq(dealer.getId()),
                eq("INV-2"),
                returnLinesCaptor.capture(),
                argThat(total -> total.compareTo(new BigDecimal("99")) == 0),
                eq("Inclusive return")
        );
        Map<Long, BigDecimal> capturedReturnLines = returnLinesCaptor.getValue();
        assertThat(capturedReturnLines.get(711L)).isEqualByComparingTo("100");
        assertThat(capturedReturnLines.get(701L)).isEqualByComparingTo("-10");
        assertThat(capturedReturnLines.get(800L)).isEqualByComparingTo("9");
    }

    @Test
    void ensureLinkedCorrectionJournal_noopsWithoutReplayOrSourceIdentity() {
        invokeEnsureLinkedCorrectionJournal(company, null, new JournalEntry(), "INV-NOOP");
        invokeEnsureLinkedCorrectionJournal(company, journalEntryDto(null, "JE-NOOP", LocalDate.now(), null), new JournalEntry(), "INV-NOOP");

        JournalEntry sourceWithoutId = new JournalEntry();
        JournalEntryDto replay = journalEntryDto(220L, "JE-220", LocalDate.now(), null);
        invokeEnsureLinkedCorrectionJournal(company, replay, sourceWithoutId, "INV-NOOP");

        verify(journalEntryRepository, never()).findByCompanyAndId(any(), anyLong());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void ensureLinkedCorrectionJournal_andRelinkHelpers_skipPersistenceWhenAlreadyAligned() {
        JournalEntry source = new JournalEntry();
        setField(source, "id", 320L);

        JournalEntry aligned = new JournalEntry();
        setField(aligned, "id", 321L);
        aligned.setCorrectionType(JournalCorrectionType.REVERSAL);
        aligned.setCorrectionReason("SALES_RETURN");
        aligned.setSourceModule("SALES_RETURN");
        aligned.setSourceReference("INV-ALIGNED");

        when(journalEntryRepository.findByCompanyAndId(company, 321L)).thenReturn(Optional.of(aligned));

        invokeEnsureLinkedCorrectionJournal(
                company,
                journalEntryDto(321L, "JE-321", LocalDate.now(), null),
                source,
                "INV-ALIGNED"
        );

        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Aligned replay",
                List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))
        );
        InventoryMovement alreadyLinked = new InventoryMovement();
        alreadyLinked.setReferenceType("SALES_RETURN");
        alreadyLinked.setReferenceId("INV-ALIGNED:55:RET-KEY");
        alreadyLinked.setJournalEntryId(321L);
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-ALIGNED:"
        )).thenReturn(List.of(alreadyLinked));

        invokeRelinkExistingReturnMovements(company, "INV-ALIGNED", request, 321L, "KEY");

        verify(journalEntryRepository, never()).save(aligned);
        verify(inventoryMovementRepository, never()).saveAll(any());
    }

    @Test
    void validateReturnQuantities_skipsNullProductAndMissingFinishedGoodIdentityInLegacyHistory() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-LEGACY-SKIP");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-LEGACY");
        requestedLine.setQuantity(new BigDecimal("2"));
        setField(requestedLine, "id", 701L);
        invoice.getLines().add(requestedLine);

        InvoiceLine nullProductLine = new InvoiceLine();
        nullProductLine.setInvoice(invoice);
        nullProductLine.setProductCode(null);
        nullProductLine.setQuantity(BigDecimal.ONE);
        setField(nullProductLine, "id", 702L);
        invoice.getLines().add(nullProductLine);

        FinishedGood requestedFg = new FinishedGood();
        requestedFg.setCompany(company);
        requestedFg.setProductCode("FG-LEGACY");

        FinishedGood historyFg = new FinishedGood();
        historyFg.setCompany(company);
        historyFg.setProductCode("FG-LEGACY-HISTORY");
        setField(historyFg, "id", 801L);

        InventoryMovement legacyHeader = new InventoryMovement();
        legacyHeader.setFinishedGood(historyFg);
        legacyHeader.setReferenceType("SALES_RETURN");
        legacyHeader.setReferenceId("INV-LEGACY-SKIP");
        legacyHeader.setQuantity(BigDecimal.ZERO);

        InventoryMovement requestedLineHistory = new InventoryMovement();
        requestedLineHistory.setFinishedGood(historyFg);
        requestedLineHistory.setReferenceType("SALES_RETURN");
        requestedLineHistory.setReferenceId("INV-LEGACY-SKIP:701");
        requestedLineHistory.setQuantity(new BigDecimal("0.5"));

        InventoryMovement nullProductHistory = new InventoryMovement();
        nullProductHistory.setFinishedGood(historyFg);
        nullProductHistory.setReferenceType("SALES_RETURN");
        nullProductHistory.setReferenceId("INV-LEGACY-SKIP:702");
        nullProductHistory.setQuantity(new BigDecimal("0.25"));

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LEGACY")).thenReturn(Optional.of(requestedFg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-SKIP")
        )).thenReturn(List.of(legacyHeader));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-SKIP:")
        )).thenReturn(List.of(requestedLineHistory, nullProductHistory));

        java.util.Map<Long, InvoiceLine> invoiceLines = new java.util.LinkedHashMap<>();
        invoiceLines.put(701L, requestedLine);
        invoiceLines.put(702L, nullProductLine);

        invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Legacy skips",
                        List.of(new SalesReturnRequest.ReturnLine(701L, BigDecimal.ONE))
                ),
                invoiceLines,
                new java.util.HashMap<>()
        );
    }

    @Test
    void validateReturnQuantities_skipsLateMissingInvoiceLineAfterLegacyLookup() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-LATE-MISSING");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-LATE");
        requestedLine.setQuantity(new BigDecimal("3.00"));
        setField(requestedLine, "id", 711L);
        invoice.getLines().add(requestedLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-LATE");
        setField(fg, "id", 811L);

        InventoryMovement legacyHeader = new InventoryMovement();
        legacyHeader.setFinishedGood(fg);
        legacyHeader.setReferenceType("SALES_RETURN");
        legacyHeader.setReferenceId("INV-LATE-MISSING");
        legacyHeader.setQuantity(new BigDecimal("2.00"));

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LATE")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LATE-MISSING")
        )).thenReturn(List.of(legacyHeader));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LATE-MISSING:")
        )).thenReturn(List.of());

        java.util.Map<Long, InvoiceLine> invoiceLines = new java.util.LinkedHashMap<>() {
            private int requestedLineLookups = 0;

            {
                put(711L, requestedLine);
            }

            @Override
            public InvoiceLine get(Object key) {
                if (Long.valueOf(711L).equals(key)) {
                    requestedLineLookups++;
                    return requestedLineLookups == 1 ? requestedLine : null;
                }
                return super.get(key);
            }
        };

        invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Late missing line",
                        List.of(new SalesReturnRequest.ReturnLine(711L, BigDecimal.ONE))
                ),
                invoiceLines,
                new java.util.HashMap<>()
        );
    }

    @Test
    void validateReturnQuantities_rejectsLegacyOverageAfterPartialAllocationRemainder() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-LEGACY-OVERAGE");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-OVERAGE");
        requestedLine.setQuantity(BigDecimal.ONE);
        setField(requestedLine, "id", 712L);
        invoice.getLines().add(requestedLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-OVERAGE");
        setField(fg, "id", 812L);

        InventoryMovement legacyHeader = new InventoryMovement();
        legacyHeader.setFinishedGood(fg);
        legacyHeader.setReferenceType("SALES_RETURN");
        legacyHeader.setReferenceId("INV-LEGACY-OVERAGE");
        legacyHeader.setQuantity(new BigDecimal("2.00"));

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-OVERAGE")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-OVERAGE")
        )).thenReturn(List.of(legacyHeader));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-OVERAGE:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "Legacy overage",
                        List.of(new SalesReturnRequest.ReturnLine(712L, BigDecimal.ONE))
                ),
                Map.of(712L, requestedLine),
                new java.util.HashMap<>()
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount for FG-OVERAGE");
    }

    @Test
    void validateReturnQuantities_reportsFinishedGoodCodeForFinishedGoodLevelOverage() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-FG-OVERAGE-CODE");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-CODE");
        requestedLine.setQuantity(BigDecimal.ONE);
        setField(requestedLine, "id", 713L);
        invoice.getLines().add(requestedLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-CODE");
        setField(fg, "id", 813L);

        InventoryMovement legacyHeader = new InventoryMovement();
        legacyHeader.setFinishedGood(fg);
        legacyHeader.setReferenceType("SALES_RETURN");
        legacyHeader.setReferenceId("INV-FG-OVERAGE-CODE");
        legacyHeader.setQuantity(new BigDecimal("1.50"));

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-CODE")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-FG-OVERAGE-CODE")
        )).thenReturn(List.of(legacyHeader));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-FG-OVERAGE-CODE:")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "FG overage code",
                        List.of(new SalesReturnRequest.ReturnLine(713L, new BigDecimal("0.75")))
                ),
                Map.of(713L, requestedLine),
                new java.util.HashMap<>()
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount for FG-CODE");
    }

    @Test
    void validateReturnQuantities_fallsBackToFinishedGoodIdWhenFinishedGoodIdentityMissing() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-FG-OVERAGE-ID");

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-ID");
        requestedLine.setQuantity(BigDecimal.ONE);
        setField(requestedLine, "id", 714L);
        invoice.getLines().add(requestedLine);

        FinishedGood fg = mock(FinishedGood.class);
        when(fg.getId()).thenReturn(814L, 815L, 814L, 815L, 814L, 815L);

        InventoryMovement legacyHeader = new InventoryMovement();
        legacyHeader.setFinishedGood(fg);
        legacyHeader.setReferenceType("SALES_RETURN");
        legacyHeader.setReferenceId("INV-FG-OVERAGE-ID");
        legacyHeader.setQuantity(new BigDecimal("1.50"));

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-FG-OVERAGE-ID")
        )).thenReturn(List.of(legacyHeader));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-FG-OVERAGE-ID:")
        )).thenReturn(List.of());

        java.util.Map<String, FinishedGood> finishedGoodsByCode = new java.util.HashMap<>();
        finishedGoodsByCode.put("FG-ID", fg);

        assertThatThrownBy(() -> invokeValidateReturnQuantities(
                company,
                invoice,
                new SalesReturnRequest(
                        null,
                        "FG overage id",
                        List.of(new SalesReturnRequest.ReturnLine(714L, new BigDecimal("0.75")))
                ),
                Map.of(714L, requestedLine),
                finishedGoodsByCode
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining invoiced amount for 815");
    }

    @Test
    void relinkExistingReturnMovements_noopsForMissingRequestStateOrEmptyHistory() {
        invokeRelinkExistingReturnMovements(company, "INV-NO-RELINK", null, 991L, "RETKEY");

        invokeRelinkExistingReturnMovements(
                company,
                "INV-NO-RELINK",
                new SalesReturnRequest(10L, "No lines", List.of()),
                991L,
                "RETKEY"
        );

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NO-RELINK:"
        )).thenReturn(List.of());

        invokeRelinkExistingReturnMovements(
                company,
                "INV-NO-RELINK",
                new SalesReturnRequest(
                        10L,
                        "Empty history",
                        List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))
                ),
                991L,
                "RETKEY"
        );

        verify(inventoryMovementRepository).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NO-RELINK:"
        );
        verify(inventoryMovementRepository, never()).saveAll(any());
    }

    @Test
    void relinkExistingReturnMovements_noopsForNullJournalAndNullHistory() {
        invokeRelinkExistingReturnMovements(
                company,
                "INV-NULL-HISTORY",
                new SalesReturnRequest(
                        10L,
                        "Null journal",
                        List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))
                ),
                null,
                "RETKEY"
        );

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NULL-HISTORY:"
        )).thenReturn(null);

        invokeRelinkExistingReturnMovements(
                company,
                "INV-NULL-HISTORY",
                new SalesReturnRequest(
                        10L,
                        "Null history",
                        List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))
                ),
                992L,
                "RETKEY"
        );

        verify(inventoryMovementRepository).findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NULL-HISTORY:"
        );
        verify(inventoryMovementRepository, never()).saveAll(any());
    }

    @Test
    void quantityValue_returnsZeroForNull() {
        assertThat(invokeQuantityValue(null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invokeQuantityValue(new BigDecimal("2.5"))).isEqualByComparingTo("2.5");
    }

    @Test
    void buildReturnIdempotencyKey_returnsNullForMissingInputs() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-KEY-1");
        setField(invoice, "id", 501L);

        assertThat(invokeBuildReturnIdempotencyKey(null, new SalesReturnRequest(
                501L,
                "missing invoice",
                List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))
        ))).isNull();
        assertThat(invokeBuildReturnIdempotencyKey(invoice, null)).isNull();
        assertThat(invokeBuildReturnIdempotencyKey(invoice, new SalesReturnRequest(
                501L,
                "null lines",
                null
        ))).isNull();
        assertThat(invokeBuildReturnIdempotencyKey(invoice, new SalesReturnRequest(
                501L,
                "empty lines",
                List.of()
        ))).isNull();
    }

    @Test
    void buildReturnReferenceAndIdempotencyKey_coverFallbackIdentityBranches() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(" INV-KEY-2 ");

        String idempotencyKey = invokeBuildReturnIdempotencyKey(invoice, new SalesReturnRequest(
                502L,
                "fallback identity",
                List.of(new SalesReturnRequest.ReturnLine(88L, BigDecimal.ONE))
        ));

        assertThat(idempotencyKey).isNotBlank();
        assertThat(invokeBuildReturnIdempotencyKey(invoice, new SalesReturnRequest(
                503L,
                "null line id",
                List.of(new SalesReturnRequest.ReturnLine(null, BigDecimal.ONE))
        ))).isNotBlank();
        assertThat(invokeBuildReturnReference(null, 88L, null)).isNull();
        assertThat(invokeBuildReturnReference(" INV-KEY-2 ", null, null)).isEqualTo("INV-KEY-2");
        assertThat(invokeBuildReturnReference(" INV-KEY-2 ", 88L, null)).isEqualTo("INV-KEY-2:88");
    }

    @Test
    void loadDispatchMovements_filtersDispatchOnlyAndNonNullFinishedGood() {
        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-DISPATCH-FILTER");
        setField(fg, "id", 611L);

        InventoryMovement validDispatch = new InventoryMovement();
        validDispatch.setFinishedGood(fg);
        validDispatch.setMovementType("DISPATCH");
        validDispatch.setQuantity(BigDecimal.ONE);

        InventoryMovement returnMovement = new InventoryMovement();
        returnMovement.setFinishedGood(fg);
        returnMovement.setMovementType("RETURN");
        returnMovement.setQuantity(BigDecimal.ONE);

        InventoryMovement nullFinishedGood = new InventoryMovement();
        nullFinishedGood.setMovementType("DISPATCH");
        nullFinishedGood.setQuantity(BigDecimal.ONE);

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                InventoryReference.SALES_ORDER,
                "701"
        )).thenReturn(List.of(validDispatch, returnMovement, nullFinishedGood));

        Map<Long, List<InventoryMovement>> grouped = invokeLoadDispatchMovements(company, 701L);

        assertThat(grouped).hasSize(1);
        assertThat(grouped.get(611L)).containsExactly(validDispatch);
    }

    @Test
    void loadReturnMovements_returnsEmptyForBlankInvoiceAndSkipsMalformedReferences() {
        Object blankSummary = invokeLoadReturnMovements(company, "   ");
        assertThat((Map<?, ?>) invokeRecordAccessor(blankSummary, "byInvoiceLineId")).isEmpty();
        assertThat((Map<?, ?>) invokeRecordAccessor(blankSummary, "byFinishedGoodId")).isEmpty();

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-RET-HISTORY");
        setField(fg, "id", 612L);

        InventoryMovement exactLegacy = new InventoryMovement();
        exactLegacy.setFinishedGood(fg);
        exactLegacy.setReferenceType("SALES_RETURN");
        exactLegacy.setReferenceId("INV-HISTORY-1");
        exactLegacy.setQuantity(new BigDecimal("1.00"));

        InventoryMovement mismatchedReference = new InventoryMovement();
        mismatchedReference.setFinishedGood(fg);
        mismatchedReference.setReferenceType("SALES_RETURN");
        mismatchedReference.setReferenceId("INV-OTHER-1:55");
        mismatchedReference.setQuantity(new BigDecimal("9.00"));

        InventoryMovement blankReference = new InventoryMovement();
        blankReference.setFinishedGood(fg);
        blankReference.setReferenceType("SALES_RETURN");
        blankReference.setReferenceId("   ");
        blankReference.setQuantity(new BigDecimal("2.00"));

        InventoryMovement validLine = new InventoryMovement();
        validLine.setFinishedGood(fg);
        validLine.setReferenceType("SALES_RETURN");
        validLine.setReferenceId("INV-HISTORY-1:55");
        validLine.setQuantity(new BigDecimal("0.50"));

        InventoryMovement validLineWithSuffix = new InventoryMovement();
        validLineWithSuffix.setFinishedGood(fg);
        validLineWithSuffix.setReferenceType("SALES_RETURN");
        validLineWithSuffix.setReferenceId("INV-HISTORY-1:55:RETKEY");
        validLineWithSuffix.setQuantity(new BigDecimal("0.25"));

        InventoryMovement malformedLine = new InventoryMovement();
        malformedLine.setFinishedGood(fg);
        malformedLine.setReferenceType("SALES_RETURN");
        malformedLine.setReferenceId("INV-HISTORY-1:abc");
        malformedLine.setQuantity(new BigDecimal("0.75"));

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-1"
        )).thenReturn(List.of(exactLegacy, mismatchedReference, blankReference));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-1:"
        )).thenReturn(List.of(validLine, validLineWithSuffix, malformedLine));

        Object summary = invokeLoadReturnMovements(company, "INV-HISTORY-1");

        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byLine = (Map<Long, BigDecimal>) invokeRecordAccessor(summary, "byInvoiceLineId");
        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byFinishedGood = (Map<Long, BigDecimal>) invokeRecordAccessor(summary, "byFinishedGoodId");

        assertThat(byLine).containsOnlyKeys(55L);
        assertThat(byLine.get(55L)).isEqualByComparingTo("0.75");
        assertThat(byFinishedGood).containsOnlyKeys(612L);
        assertThat(byFinishedGood.get(612L)).isEqualByComparingTo("2.50");
    }

    @Test
    void loadReturnMovements_skipsNullMetadataAndEmptyLineSuffixes() {
        FinishedGood validFg = new FinishedGood();
        validFg.setCompany(company);
        validFg.setProductCode("FG-RET-HISTORY-2");
        setField(validFg, "id", 613L);

        FinishedGood idlessFg = new FinishedGood();
        idlessFg.setCompany(company);
        idlessFg.setProductCode("FG-IDLESS");

        InventoryMovement nullFinishedGood = new InventoryMovement();
        nullFinishedGood.setReferenceType("SALES_RETURN");
        nullFinishedGood.setReferenceId("INV-HISTORY-2");
        nullFinishedGood.setQuantity(BigDecimal.ONE);

        InventoryMovement idlessFinishedGood = new InventoryMovement();
        idlessFinishedGood.setFinishedGood(idlessFg);
        idlessFinishedGood.setReferenceType("SALES_RETURN");
        idlessFinishedGood.setReferenceId("INV-HISTORY-2");
        idlessFinishedGood.setQuantity(BigDecimal.ONE);

        InventoryMovement nullReference = new InventoryMovement();
        nullReference.setFinishedGood(validFg);
        nullReference.setReferenceType("SALES_RETURN");
        nullReference.setReferenceId(null);
        nullReference.setQuantity(BigDecimal.ONE);

        InventoryMovement blankReference = new InventoryMovement();
        blankReference.setFinishedGood(validFg);
        blankReference.setReferenceType("SALES_RETURN");
        blankReference.setReferenceId("   ");
        blankReference.setQuantity(BigDecimal.ONE);

        InventoryMovement blankLineSuffix = new InventoryMovement();
        blankLineSuffix.setFinishedGood(validFg);
        blankLineSuffix.setReferenceType("SALES_RETURN");
        blankLineSuffix.setReferenceId("INV-HISTORY-2:   ");
        blankLineSuffix.setQuantity(new BigDecimal("2.00"));

        InventoryMovement nullQuantityLine = new InventoryMovement();
        nullQuantityLine.setFinishedGood(validFg);
        nullQuantityLine.setReferenceType("SALES_RETURN");
        nullQuantityLine.setReferenceId("INV-HISTORY-2:77");
        nullQuantityLine.setQuantity(null);

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-2"
        )).thenReturn(null);
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-2:"
        )).thenReturn(List.of(
                nullFinishedGood,
                idlessFinishedGood,
                nullReference,
                blankReference,
                blankLineSuffix,
                nullQuantityLine
        ));

        Object summary = invokeLoadReturnMovements(company, " INV-HISTORY-2 ");

        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byLine = (Map<Long, BigDecimal>) invokeRecordAccessor(summary, "byInvoiceLineId");
        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byFinishedGood = (Map<Long, BigDecimal>) invokeRecordAccessor(summary, "byFinishedGoodId");

        assertThat(byLine).containsOnlyKeys(77L);
        assertThat(byLine.get(77L)).isEqualByComparingTo("0");
        assertThat(byFinishedGood).containsOnlyKeys(613L);
        assertThat(byFinishedGood.get(613L)).isEqualByComparingTo("2.00");
    }

    @Test
    void loadReturnMovements_returnsEmptyForNullInvoiceAndNullLineMovementBatch() {
        Object nullSummary = invokeLoadReturnMovements(company, null);
        assertThat((Map<?, ?>) invokeRecordAccessor(nullSummary, "byInvoiceLineId")).isEmpty();
        assertThat((Map<?, ?>) invokeRecordAccessor(nullSummary, "byFinishedGoodId")).isEmpty();

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-NULL"
        )).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-HISTORY-NULL:"
        )).thenReturn(null);

        Object summary = invokeLoadReturnMovements(company, "INV-HISTORY-NULL");
        assertThat((Map<?, ?>) invokeRecordAccessor(summary, "byInvoiceLineId")).isEmpty();
        assertThat((Map<?, ?>) invokeRecordAccessor(summary, "byFinishedGoodId")).isEmpty();
    }

    @Test
    void resolveReturnUnitCost_allocatesAcrossMultipleLayersAndSkipsInvalidQuantities() {
        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-LAYERED");
        setField(fg, "id", 613L);

        InventoryMovement zeroQuantity = new InventoryMovement();
        zeroQuantity.setQuantity(BigDecimal.ZERO);
        zeroQuantity.setUnitCost(new BigDecimal("10.00"));

        InventoryMovement firstLayer = new InventoryMovement();
        firstLayer.setQuantity(new BigDecimal("1.00"));
        firstLayer.setUnitCost(new BigDecimal("12.00"));

        InventoryMovement secondLayer = new InventoryMovement();
        secondLayer.setQuantity(new BigDecimal("5.00"));
        secondLayer.setUnitCost(new BigDecimal("20.00"));

        Map<Long, List<InventoryMovement>> dispatchMovements = Map.of(
                613L,
                List.of(zeroQuantity, firstLayer, secondLayer)
        );

        InvoiceLine invoiceLine = new InvoiceLine();
        invoiceLine.setProductCode("FG-LAYERED");

        assertThat(invokeResolveReturnUnitCost(
                fg,
                new BigDecimal("3.00"),
                dispatchMovements,
                invoiceLine
        )).isEqualByComparingTo("17.3333");

        assertThatThrownBy(() -> invokeResolveReturnUnitCost(
                fg,
                new BigDecimal("7.00"),
                dispatchMovements,
                invoiceLine
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return quantity exceeds dispatched quantity");
    }

    @Test
    void postCogsReversal_skipsWhenFinishedGoodMissingOrAccountsMissing() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-COGS-SKIP");

        InventoryMovement missingFgMovement = new InventoryMovement();
        missingFgMovement.setQuantity(new BigDecimal("2.00"));
        missingFgMovement.setUnitCost(new BigDecimal("15.00"));

        InventoryMovement accountlessMovement = new InventoryMovement();
        accountlessMovement.setQuantity(new BigDecimal("1.00"));
        accountlessMovement.setUnitCost(new BigDecimal("18.00"));

        FinishedGood missingAccounts = new FinishedGood();
        missingAccounts.setCompany(company);
        missingAccounts.setProductCode("FG-COGS-SKIP");
        setField(missingAccounts, "id", 615L);
        missingAccounts.setValuationAccountId(null);
        missingAccounts.setCogsAccountId(900L);

        when(finishedGoodRepository.findByCompanyAndId(company, 614L)).thenReturn(Optional.empty());
        when(finishedGoodRepository.findByCompanyAndId(company, 615L)).thenReturn(Optional.of(missingAccounts));

        invokePostCogsReversal(
                invoice,
                Map.of(614L, new BigDecimal("2.00"), 615L, BigDecimal.ONE),
                Map.of(
                        614L, List.of(missingFgMovement),
                        615L, List.of(accountlessMovement)
                )
        );

        verify(accountingFacade, never()).postInventoryAdjustment(
                anyString(),
                anyString(),
                anyLong(),
                anyMap(),
                anyBoolean(),
                anyBoolean(),
                anyString()
        );
    }

    @Test
    void postCogsReversal_handlesZeroRemainingNullQuantitiesAndNullUnitCost() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-COGS-NULLS");

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-COGS-NULLS");
        fg.setValuationAccountId(901L);
        fg.setCogsAccountId(902L);
        setField(fg, "id", 616L);

        InventoryMovement nullQuantity = new InventoryMovement();
        nullQuantity.setQuantity(null);
        nullQuantity.setUnitCost(new BigDecimal("9.00"));

        InventoryMovement zeroQuantity = new InventoryMovement();
        zeroQuantity.setQuantity(BigDecimal.ZERO);
        zeroQuantity.setUnitCost(new BigDecimal("9.00"));

        InventoryMovement nullCost = new InventoryMovement();
        nullCost.setQuantity(BigDecimal.ONE);
        nullCost.setUnitCost(null);

        InventoryMovement valuedLayer = new InventoryMovement();
        valuedLayer.setQuantity(BigDecimal.ONE);
        valuedLayer.setUnitCost(new BigDecimal("7.00"));

        when(finishedGoodRepository.findByCompanyAndId(company, 616L)).thenReturn(Optional.of(fg));

        invokePostCogsReversal(
                invoice,
                Map.of(615L, BigDecimal.ZERO, 616L, new BigDecimal("2.00")),
                Map.of(
                        616L,
                        List.of(nullQuantity, zeroQuantity, nullCost, valuedLayer)
                )
        );

        verify(accountingFacade).postInventoryAdjustment(
                eq("SALES_RETURN_COGS"),
                eq("CRN-INV-COGS-NULLS-COGS-0"),
                eq(902L),
                argThat(lines -> lines.containsKey(901L)
                        && new BigDecimal("7.00").compareTo(lines.get(901L)) == 0),
                eq(true),
                eq(false),
                contains("COGS reversal")
        );
    }

    @Test
    void pricingHelpers_coverTaxBaseDiscountAndDiscountTaxInclusiveBranches() {
        InvoiceLine zeroQuantity = new InvoiceLine();
        zeroQuantity.setQuantity(BigDecimal.ZERO);
        assertThat(invokePerUnitTax(zeroQuantity)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invokePerUnitBase(zeroQuantity)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invokePerUnitDiscount(zeroQuantity, true)).isEqualByComparingTo(BigDecimal.ZERO);

        InvoiceLine fallbackLine = new InvoiceLine();
        fallbackLine.setQuantity(new BigDecimal("2.00"));
        fallbackLine.setUnitPrice(new BigDecimal("100.00"));
        fallbackLine.setDiscountAmount(new BigDecimal("20.00"));
        fallbackLine.setLineTotal(new BigDecimal("198.00"));
        fallbackLine.setTaxRate(new BigDecimal("18.00"));

        assertThat(invokePerUnitTax(fallbackLine)).isEqualByComparingTo("9.0000");
        assertThat(invokePerUnitBase(fallbackLine)).isEqualByComparingTo("90.0000");
        assertThat(invokePerUnitDiscount(fallbackLine, true)).isEqualByComparingTo("8.4746");

        fallbackLine.setTaxRate(BigDecimal.ZERO);
        assertThat(invokePerUnitDiscount(fallbackLine, true)).isEqualByComparingTo("10.0000");

        assertThat(invokeIsDiscountTaxInclusive(null, true)).isTrue();
        assertThat(invokeIsDiscountTaxInclusive(fallbackLine, false)).isFalse();

        InvoiceLine inclusiveFallback = new InvoiceLine();
        inclusiveFallback.setQuantity(new BigDecimal("2.00"));
        inclusiveFallback.setUnitPrice(new BigDecimal("100.00"));
        inclusiveFallback.setDiscountAmount(new BigDecimal("20.00"));
        inclusiveFallback.setTaxableAmount(new BigDecimal("180.00"));
        inclusiveFallback.setTaxAmount(BigDecimal.ZERO);
        assertThat(invokeIsDiscountTaxInclusive(inclusiveFallback, false)).isTrue();

        InvoiceLine missingPrice = new InvoiceLine();
        missingPrice.setDiscountAmount(new BigDecimal("5.00"));
        assertThat(invokeIsDiscountTaxInclusive(missingPrice, true)).isTrue();
    }

    @Test
    void pricingHelpers_coverNullQuantityNullPriceAndZeroGrossFallbacks() {
        InvoiceLine nullQuantity = new InvoiceLine();
        nullQuantity.setQuantity(null);
        nullQuantity.setDiscountAmount(new BigDecimal("5.00"));
        assertThat(invokePerUnitTax(nullQuantity)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invokePerUnitBase(nullQuantity)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invokePerUnitDiscount(nullQuantity, true)).isEqualByComparingTo(BigDecimal.ZERO);

        InvoiceLine nullUnitPrice = new InvoiceLine();
        nullUnitPrice.setQuantity(BigDecimal.ONE);
        nullUnitPrice.setDiscountAmount(new BigDecimal("5.00"));
        assertThat(invokeIsDiscountTaxInclusive(nullUnitPrice, false)).isFalse();

        InvoiceLine zeroGross = new InvoiceLine();
        zeroGross.setQuantity(BigDecimal.ONE);
        zeroGross.setUnitPrice(BigDecimal.ZERO);
        zeroGross.setDiscountAmount(new BigDecimal("2.00"));
        assertThat(invokeIsDiscountTaxInclusive(zeroGross, true)).isTrue();

        InvoiceLine nullTaxRateDiscount = new InvoiceLine();
        nullTaxRateDiscount.setQuantity(BigDecimal.ONE);
        nullTaxRateDiscount.setDiscountAmount(new BigDecimal("4.00"));
        nullTaxRateDiscount.setTaxRate(null);
        assertThat(invokePerUnitDiscount(nullTaxRateDiscount, true)).isEqualByComparingTo("4.0000");
    }

    @Test
    void ensureLinkedCorrectionJournal_noopsWhenSourceJournalIsNull() {
        invokeEnsureLinkedCorrectionJournal(
                company,
                journalEntryDto(420L, "JE-420", LocalDate.now(), null),
                null,
                "INV-NO-SOURCE"
        );

        verify(journalEntryRepository, never()).findByCompanyAndId(any(), eq(420L));
    }

    @Test
    void relinkExistingReturnMovements_noopsForNullRequestLines() {
        invokeRelinkExistingReturnMovements(
                company,
                "INV-NULL-LINES",
                new SalesReturnRequest(10L, "Null lines", null),
                993L,
                "RETKEY"
        );

        verify(inventoryMovementRepository, never())
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                        eq(company),
                        eq("SALES_RETURN"),
                        eq("INV-NULL-LINES:")
                );
    }

    private JournalEntryDto stubEntry(long id) {
        return journalEntryDto(id, null, LocalDate.now(), null);
    }

    private JournalEntryDto journalEntryDto(Long id, String reference, LocalDate entryDate, String memo) {
        return new JournalEntryDto(
                id,
                null,
                reference,
                entryDate,
                memo,
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
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String invokeBuildReturnIdempotencyKey(Invoice invoice, SalesReturnRequest request) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "buildReturnIdempotencyKey",
                    Invoice.class,
                    SalesReturnRequest.class
            );
            method.setAccessible(true);
            return (String) method.invoke(salesReturnService, invoice, request);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String invokeBuildReturnReference(String invoiceNumber, Long invoiceLineId, String returnKey) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "buildReturnReference",
                    String.class,
                    Long.class,
                    String.class
            );
            method.setAccessible(true);
            return (String) method.invoke(salesReturnService, invoiceNumber, invoiceLineId, returnKey);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void invokeEnsurePostedInvoice(Invoice invoice) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod("ensurePostedInvoice", Invoice.class);
            method.setAccessible(true);
            method.invoke(salesReturnService, invoice);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void invokeValidateReturnQuantities(Company company,
                                                Invoice invoice,
                                                SalesReturnRequest request,
                                                Map<Long, InvoiceLine> invoiceLines,
                                                Map<String, FinishedGood> finishedGoodsByCode) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "validateReturnQuantities",
                    Company.class,
                    Invoice.class,
                    SalesReturnRequest.class,
                    Map.class,
                    Map.class
            );
            method.setAccessible(true);
            method.invoke(salesReturnService, company, invoice, request, invoiceLines, finishedGoodsByCode);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void invokeEnsureLinkedCorrectionJournal(Company company,
                                                     JournalEntryDto replayEntry,
                                                     JournalEntry sourceJournal,
                                                     String invoiceNumber) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "ensureLinkedCorrectionJournal",
                    Company.class,
                    JournalEntryDto.class,
                    JournalEntry.class,
                    String.class
            );
            method.setAccessible(true);
            method.invoke(salesReturnService, company, replayEntry, sourceJournal, invoiceNumber);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void invokeRelinkExistingReturnMovements(Company company,
                                                     String invoiceNumber,
                                                     SalesReturnRequest request,
                                                     Long journalEntryId,
                                                     String returnKey) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "relinkExistingReturnMovements",
                    Company.class,
                    String.class,
                    SalesReturnRequest.class,
                    Long.class,
                    String.class
            );
            method.setAccessible(true);
            method.invoke(salesReturnService, company, invoiceNumber, request, journalEntryId, returnKey);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BigDecimal invokeQuantityValue(BigDecimal value) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod("quantityValue", BigDecimal.class);
            method.setAccessible(true);
            return (BigDecimal) method.invoke(salesReturnService, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Map<Long, List<InventoryMovement>> invokeLoadDispatchMovements(Company company, Long salesOrderId) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "loadDispatchMovements",
                    Company.class,
                    Long.class
            );
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, List<InventoryMovement>> result =
                    (Map<Long, List<InventoryMovement>>) method.invoke(salesReturnService, company, salesOrderId);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Object invokeLoadReturnMovements(Company company, String invoiceNumber) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "loadReturnMovements",
                    Company.class,
                    String.class
            );
            method.setAccessible(true);
            return method.invoke(salesReturnService, company, invoiceNumber);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Object invokeRecordAccessor(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BigDecimal invokeResolveReturnUnitCost(FinishedGood finishedGood,
                                                   BigDecimal quantity,
                                                   Map<Long, List<InventoryMovement>> dispatchMovements,
                                                   InvoiceLine invoiceLine) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "resolveReturnUnitCost",
                    FinishedGood.class,
                    BigDecimal.class,
                    Map.class,
                    InvoiceLine.class
            );
            method.setAccessible(true);
            return (BigDecimal) method.invoke(salesReturnService, finishedGood, quantity, dispatchMovements, invoiceLine);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void invokePostCogsReversal(Invoice invoice,
                                        Map<Long, BigDecimal> returnQuantitiesByFinishedGood,
                                        Map<Long, List<InventoryMovement>> dispatchMovements) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "postCogsReversal",
                    Invoice.class,
                    Map.class,
                    Map.class
            );
            method.setAccessible(true);
            method.invoke(salesReturnService, invoice, returnQuantitiesByFinishedGood, dispatchMovements);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BigDecimal invokePerUnitTax(InvoiceLine line) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod("perUnitTax", InvoiceLine.class);
            method.setAccessible(true);
            return (BigDecimal) method.invoke(salesReturnService, line);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BigDecimal invokePerUnitBase(InvoiceLine line) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod("perUnitBase", InvoiceLine.class);
            method.setAccessible(true);
            return (BigDecimal) method.invoke(salesReturnService, line);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BigDecimal invokePerUnitDiscount(InvoiceLine line, boolean discountTaxInclusive) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "perUnitDiscount",
                    InvoiceLine.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (BigDecimal) method.invoke(salesReturnService, line, discountTaxInclusive);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private boolean invokeIsDiscountTaxInclusive(InvoiceLine line, boolean orderGstInclusive) {
        try {
            var method = SalesReturnService.class.getDeclaredMethod(
                    "isDiscountTaxInclusive",
                    InvoiceLine.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (Boolean) method.invoke(salesReturnService, line, orderGstInclusive);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void attachPostedJournal(Invoice invoice, long journalId) {
        JournalEntry journalEntry = new JournalEntry();
        setField(journalEntry, "id", journalId);
        journalEntry.setStatus("POSTED");
        invoice.setJournalEntry(journalEntry);
        invoice.setStatus("POSTED");
    }
}
