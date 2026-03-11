package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
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
    void previewReturn_includesLegacyInvoiceScopedReturnsInLineBalances() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 73L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 10L);

        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 99L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber("INV-LEGACY-PREVIEW");
        attachPostedJournal(invoice, 909L);
        setField(invoice, "id", 112L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-LEGACY");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 113L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-LEGACY");
        setField(fg, "id", 211L);

        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("99");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("50"));

        InventoryMovement legacyMovement = new InventoryMovement();
        legacyMovement.setFinishedGood(fg);
        legacyMovement.setReferenceType("SALES_RETURN");
        legacyMovement.setReferenceId("INV-LEGACY-PREVIEW");
        legacyMovement.setQuantity(BigDecimal.ONE);

        when(invoiceRepository.lockByCompanyAndId(company, 112L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LEGACY")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("99")
        )).thenReturn(List.of(dispatchMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-PREVIEW")
        )).thenReturn(List.of(legacyMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-PREVIEW:")
        )).thenReturn(List.of());

        SalesReturnPreviewDto preview = salesReturnService.previewReturn(new SalesReturnRequest(
                112L,
                "Legacy preview",
                List.of(new SalesReturnRequest.ReturnLine(113L, BigDecimal.ONE))
        ));

        assertThat(preview.totalReturnAmount()).isEqualByComparingTo("50.00");
        assertThat(preview.totalInventoryValue()).isEqualByComparingTo("50.00");
        assertThat(preview.lines()).singleElement().satisfies(linePreview -> {
            assertThat(linePreview.alreadyReturnedQuantity()).isEqualByComparingTo("1.00");
            assertThat(linePreview.remainingQuantityAfterReturn()).isEqualByComparingTo("0.00");
            assertThat(linePreview.inventoryUnitCost()).isEqualByComparingTo("50.0000");
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
    void ensurePostedInvoice_rejectsMissingJournalVoidAndReversedStatuses() {
        Invoice missingJournal = new Invoice();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                missingJournal))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        Invoice invalidStatus = new Invoice();
        attachPostedJournal(invalidStatus, 901L);
        invalidStatus.setStatus("VOID");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                invalidStatus))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        invalidStatus.setStatus("REVERSED");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                invalidStatus))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
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
        priorReturn.setReferenceId("INV-1:55:RET-prior");

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
        firstLine.setUnitPrice(new BigDecimal("100"));
        setField(firstLine, "id", 55L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-1");
        secondLine.setQuantity(new BigDecimal("1"));
        secondLine.setUnitPrice(new BigDecimal("100"));
        setField(secondLine, "id", 56L);
        invoice.getLines().add(secondLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setRevenueAccountId(710L);
        setField(fg, "id", 21L);

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setQuantity(new BigDecimal("2"));
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
        firstLine.setUnitPrice(new BigDecimal("100"));
        setField(firstLine, "id", 55L);
        invoice.getLines().add(firstLine);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-1");
        secondLine.setQuantity(new BigDecimal("1"));
        secondLine.setUnitPrice(new BigDecimal("100"));
        setField(secondLine, "id", 56L);
        invoice.getLines().add(secondLine);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setRevenueAccountId(710L);
        setField(fg, "id", 21L);

        InventoryMovement priorReturn = new InventoryMovement();
        priorReturn.setFinishedGood(fg);
        priorReturn.setQuantity(new BigDecimal("2"));
        priorReturn.setReferenceType("SALES_RETURN");
        priorReturn.setReferenceId("INV-1");

        when(invoiceRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-1")
        )).thenReturn(List.of(priorReturn));

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
    void processReturn_replayAfterInvoiceBecomesVoidReturnsOriginalEntry() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 73L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 10L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-VOID-REPLAY");
        attachPostedJournal(invoice, 907L);
        invoice.setStatus("VOID");
        setField(invoice, "id", 31L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-VOID");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 78L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-VOID");
        fg.setRevenueAccountId(713L);
        setField(fg, "id", 24L);

        JournalEntry replayEntry = new JournalEntry();
        setField(replayEntry, "id", 121L);

        when(invoiceRepository.lockByCompanyAndId(company, 31L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-VOID")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-VOID-REPLAY"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay return")
        )).thenReturn(stubEntry(121L));
        when(journalEntryRepository.findByCompanyAndId(company, 121L)).thenReturn(Optional.of(replayEntry));

        SalesReturnRequest request = new SalesReturnRequest(
                31L,
                "Replay return",
                List.of(new SalesReturnRequest.ReturnLine(78L, BigDecimal.ONE))
        );

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(121L);
        verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
        verify(finishedGoodBatchRepository, never()).save(any(FinishedGoodBatch.class));
        verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        verify(accountingFacade, never()).postInventoryAdjustment(anyString(), anyString(), anyLong(), anyMap(), anyBoolean(), anyBoolean(), anyString());
        verify(journalEntryRepository).save(argThat(entry -> Objects.equals(entry.getId(), 121L)
                && entry.getReversalOf() != null
                && Objects.equals(entry.getReversalOf().getId(), 907L)
                && Objects.equals(entry.getSourceReference(), "INV-VOID-REPLAY")
                && Objects.equals(entry.getCorrectionReason(), "SALES_RETURN")));
    }

    @Test
    void processReturn_legacyInvoiceScopedMovementsRemainUsableAndScoped() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Legacy Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 73L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 10L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-LEGACY-1");
        attachPostedJournal(invoice, 907L);
        setField(invoice, "id", 31L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-LEGACY");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 78L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-LEGACY");
        fg.setValuationAccountId(513L);
        fg.setCogsAccountId(613L);
        fg.setRevenueAccountId(713L);
        setField(fg, "id", 24L);

        InventoryMovement legacyMovement = new InventoryMovement();
        legacyMovement.setFinishedGood(fg);
        legacyMovement.setQuantity(BigDecimal.ONE);
        legacyMovement.setReferenceType("SALES_RETURN");
        legacyMovement.setReferenceId("INV-LEGACY-1");

        when(invoiceRepository.lockByCompanyAndId(company, 31L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LEGACY")).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.lockByCompanyAndId(company, 24L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.findByCompanyAndId(company, 24L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.save(any(FinishedGood.class))).thenAnswer(inv -> inv.getArgument(0));
        when(finishedGoodBatchRepository.save(any(FinishedGoodBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchNumberService.nextFinishedGoodBatchCode(any(), any())).thenReturn("RET-LEGACY");
        when(inventoryMovementRepository.save(any(InventoryMovement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(false);
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-1")
        )).thenReturn(List.of(legacyMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-LEGACY-1:")
        )).thenReturn(List.of());
        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(fg);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId("99");
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(new BigDecimal("2"));
        dispatchMovement.setUnitCost(new BigDecimal("50"));
        SalesOrder salesOrder = new SalesOrder();
        setField(salesOrder, "id", 99L);
        invoice.setSalesOrder(salesOrder);
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq(InventoryReference.SALES_ORDER),
                eq("99")
        )).thenReturn(List.of(dispatchMovement));
        when(accountingFacade.postSalesReturn(
                anyLong(),
                anyString(),
                anyMap(),
                any(BigDecimal.class),
                anyString()
        )).thenReturn(stubEntry(123L));
        when(accountingFacade.postInventoryAdjustment(
                anyString(),
                anyString(),
                anyLong(),
                anyMap(),
                anyBoolean(),
                anyBoolean(),
                anyString())
        ).thenReturn(stubEntry(124L));

        SalesReturnRequest request = new SalesReturnRequest(
                31L,
                "Legacy replay",
                List.of(new SalesReturnRequest.ReturnLine(78L, BigDecimal.ONE))
        );

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(123L);
        verify(accountingFacade).postSalesReturn(
                eq(dealer.getId()),
                eq("INV-LEGACY-1"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("50")) == 0),
                eq("Legacy replay")
        );
        verify(inventoryMovementRepository).save(argThat(movement -> Objects.equals(movement.getJournalEntryId(), 123L)
                && movement.getReferenceId() != null
                && movement.getReferenceId().startsWith("INV-LEGACY-1:78:RET-")));
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
    void previewReturn_requiresAtLeastOneLine() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-NO-LINES");
        attachPostedJournal(invoice, 920L);
        setField(invoice, "id", 90L);
        when(invoiceRepository.lockByCompanyAndId(company, 90L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(90L, "preview", List.of())))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void previewReturn_requiresReturnLinesWhenRequestLinesAreNull() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-NULL-PREVIEW");
        attachPostedJournal(invoice, 921L);
        setField(invoice, "id", 91L);
        when(invoiceRepository.lockByCompanyAndId(company, 91L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(91L, "preview", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void previewReturn_rejectsMissingInvoice() {
        when(invoiceRepository.lockByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                404L,
                "Missing",
                List.of(new SalesReturnRequest.ReturnLine(1L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice not found: id=404");
    }

    @Test
    void processReturn_replayRelinksExistingReturnMovementsAndCorrectionJournal() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Replay Partner");
        Account receivable = new Account();
        setField(receivable, "id", 74L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 11L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-RELINK-1");
        attachPostedJournal(invoice, 909L);
        setField(invoice, "id", 32L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-RELINK");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100"));
        line.setTaxableAmount(new BigDecimal("100"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100"));
        setField(line, "id", 79L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-RELINK");
        fg.setRevenueAccountId(714L);
        setField(fg, "id", 25L);

        SalesReturnRequest request = new SalesReturnRequest(
                32L,
                "Replay relink",
                List.of(new SalesReturnRequest.ReturnLine(79L, BigDecimal.ONE))
        );
        String returnKey = ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "buildReturnIdempotencyKey",
                invoice,
                request);

        InventoryMovement invoiceScopedMovement = new InventoryMovement();
        invoiceScopedMovement.setFinishedGood(fg);
        invoiceScopedMovement.setReferenceType("SALES_RETURN");
        invoiceScopedMovement.setReferenceId("INV-RELINK-1");
        invoiceScopedMovement.setJournalEntryId(911L);

        InventoryMovement lineScopedMovement = new InventoryMovement();
        lineScopedMovement.setFinishedGood(fg);
        lineScopedMovement.setReferenceType("SALES_RETURN");
        lineScopedMovement.setReferenceId("INV-RELINK-1:79:RET-" + returnKey);
        lineScopedMovement.setJournalEntryId(912L);

        JournalEntry replayEntry = new JournalEntry();
        setField(replayEntry, "id", 122L);

        when(invoiceRepository.lockByCompanyAndId(company, 32L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-RELINK")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                eq(company),
                eq("SALES_RETURN"),
                anyString()
        )).thenReturn(true);
        when(accountingFacade.postSalesReturn(
                eq(dealer.getId()),
                eq("INV-RELINK-1"),
                anyMap(),
                argThat(total -> total.compareTo(new BigDecimal("100")) == 0),
                eq("Replay relink")
        )).thenReturn(stubEntry(122L));
        when(journalEntryRepository.findByCompanyAndId(company, 122L)).thenReturn(Optional.of(replayEntry));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-RELINK-1")
        )).thenReturn(List.of(invoiceScopedMovement));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-RELINK-1:")
        )).thenReturn(List.of(lineScopedMovement));

        JournalEntryDto result = salesReturnService.processReturn(request);

        assertThat(result.id()).isEqualTo(122L);
        verify(journalEntryRepository).save(argThat(entry -> Objects.equals(entry.getId(), 122L)
                && entry.getReversalOf() != null
                && Objects.equals(entry.getReversalOf().getId(), 909L)
                && Objects.equals(entry.getSourceReference(), "INV-RELINK-1")
                && Objects.equals(entry.getCorrectionReason(), "SALES_RETURN")));
        verify(inventoryMovementRepository).saveAll(argThat(movements -> {
            List<InventoryMovement> saved = (List<InventoryMovement>) movements;
            return saved.size() == 2
                    && saved.stream().anyMatch(movement -> ("INV-RELINK-1:79:RET-" + returnKey).equals(movement.getReferenceId())
                    && Objects.equals(movement.getJournalEntryId(), 122L))
                    && saved.stream().anyMatch(movement -> "INV-RELINK-1".equals(movement.getReferenceId())
                    && Objects.equals(movement.getJournalEntryId(), 911L));
        }));
        assertThat(invoiceScopedMovement.getJournalEntryId()).isEqualTo(911L);
        assertThat(lineScopedMovement.getJournalEntryId()).isEqualTo(122L);
        verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
        verify(finishedGoodBatchRepository, never()).save(any(FinishedGoodBatch.class));
        verify(accountingFacade, never()).postInventoryAdjustment(anyString(), anyString(), anyLong(), anyMap(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void previewReturn_rejectsMissingInvoiceLineBeforePreviewing() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-MISSING-LINE");
        attachPostedJournal(invoice, 930L);
        setField(invoice, "id", 130L);

        when(invoiceRepository.lockByCompanyAndId(company, 130L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                130L,
                "Preview",
                List.of(new SalesReturnRequest.ReturnLine(999L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice line not found: 999");
    }

    @Test
    void processReturn_requiresReturnLinesWhenRequestLinesAreNull() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 77L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NULL-LINES");
        attachPostedJournal(invoice, 935L);
        setField(invoice, "id", 132L);

        when(invoiceRepository.lockByCompanyAndId(company, 132L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(132L, "Null lines", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void processReturn_requiresReturnLinesWhenRequestLinesAreEmpty() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 79L);
        dealer.setReceivableAccount(receivable);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-EMPTY-LINES");
        attachPostedJournal(invoice, 937L);
        setField(invoice, "id", 134L);

        when(invoiceRepository.lockByCompanyAndId(company, 134L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(134L, "Empty lines", List.of())))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return lines are required");
    }

    @Test
    void processReturn_rejectsZeroReturnAmountBeforePosting() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        Account receivable = new Account();
        setField(receivable, "id", 78L);
        dealer.setReceivableAccount(receivable);
        setField(dealer, "id", 12L);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-ZERO");
        attachPostedJournal(invoice, 936L);
        setField(invoice, "id", 133L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-ZERO");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.ZERO);
        line.setTaxableAmount(BigDecimal.ZERO);
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(BigDecimal.ZERO);
        setField(line, "id", 92L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-ZERO");
        setField(fg, "id", 602L);

        when(invoiceRepository.lockByCompanyAndId(company, 133L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-ZERO")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-ZERO")).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-ZERO:")).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.processReturn(new SalesReturnRequest(
                133L,
                "Zero",
                List.of(new SalesReturnRequest.ReturnLine(92L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return amount must be greater than zero");
    }

    @Test
    void previewReturn_withoutSalesOrderFailsOnMissingDispatchCostLayers() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-NO-SO");
        attachPostedJournal(invoice, 934L);
        setField(invoice, "id", 131L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-NO-SO");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setTaxableAmount(new BigDecimal("100.00"));
        line.setTaxAmount(BigDecimal.ZERO);
        line.setLineTotal(new BigDecimal("100.00"));
        setField(line, "id", 88L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-NO-SO");
        setField(fg, "id", 501L);

        when(invoiceRepository.lockByCompanyAndId(company, 131L)).thenReturn(Optional.of(invoice));
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-NO-SO")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NO-SO")).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-NO-SO:")).thenReturn(List.of());

        assertThatThrownBy(() -> salesReturnService.previewReturn(new SalesReturnRequest(
                131L,
                "Preview",
                List.of(new SalesReturnRequest.ReturnLine(88L, BigDecimal.ONE))
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("dispatch cost layers")
                .hasMessageContaining("FG-NO-SO");
    }

    @Test
    void ensurePostedInvoice_rejectsMissingJournalAndBlankStatus() {
        Invoice missingJournal = new Invoice();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                missingJournal))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");

        Invoice blankStatus = new Invoice();
        attachPostedJournal(blankStatus, 931L);
        blankStatus.setStatus("   ");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                blankStatus))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void ensurePostedInvoice_rejectsVoidAndReversedStatus() {
        Invoice voidInvoice = new Invoice();
        attachPostedJournal(voidInvoice, 932L);
        voidInvoice.setStatus("VOID");

        Invoice reversedInvoice = new Invoice();
        attachPostedJournal(reversedInvoice, 933L);
        reversedInvoice.setStatus("REVERSED");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                voidInvoice))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensurePostedInvoice",
                reversedInvoice))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted invoices can be corrected through sales return");
    }

    @Test
    void ensurePostedInvoice_allowsNullInvoice() {
        ReflectionTestUtils.invokeMethod(salesReturnService, "ensurePostedInvoice", new Object[]{null});
    }

    @Test
    void loadReturnMovements_aggregatesCanonicalKeyedReferencesAndIgnoresNoise() {
        FinishedGood fg = new FinishedGood();
        fg.setProductCode("FG-CANON");
        setField(fg, "id", 31L);

        InventoryMovement keyed = new InventoryMovement();
        keyed.setFinishedGood(fg);
        keyed.setReferenceId(" INV-CANON:55:KEY-1 ");
        keyed.setQuantity(new BigDecimal("2.00"));

        InventoryMovement direct = new InventoryMovement();
        direct.setFinishedGood(fg);
        direct.setReferenceId("INV-CANON:55");
        direct.setQuantity(BigDecimal.ONE);

        InventoryMovement invalidLine = new InventoryMovement();
        invalidLine.setFinishedGood(fg);
        invalidLine.setReferenceId("INV-CANON:ABC");
        invalidLine.setQuantity(new BigDecimal("4.00"));

        InventoryMovement blankLine = new InventoryMovement();
        blankLine.setFinishedGood(fg);
        blankLine.setReferenceId("INV-CANON:");
        blankLine.setQuantity(new BigDecimal("5.00"));

        InventoryMovement unrelated = new InventoryMovement();
        unrelated.setFinishedGood(fg);
        unrelated.setReferenceId("INV-CANON-OTHER:55");
        unrelated.setQuantity(new BigDecimal("9.00"));

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-CANON")).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-CANON:")).thenReturn(List.of(keyed, direct, invalidLine, blankLine, unrelated));

        Object summary = ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "loadReturnMovements",
                company,
                "INV-CANON");

        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byLine = (Map<Long, BigDecimal>) ReflectionTestUtils.invokeMethod(summary, "byInvoiceLineId");
        @SuppressWarnings("unchecked")
        Map<Long, BigDecimal> byFinishedGood = (Map<Long, BigDecimal>) ReflectionTestUtils.invokeMethod(summary, "byFinishedGoodId");

        assertThat(byLine).containsEntry(55L, new BigDecimal("3.00"));
        assertThat(byFinishedGood).containsEntry(31L, new BigDecimal("12.00"));
    }

    @Test
    void validateReturnQuantities_rejectsMissingInvoiceLinesAndOverInvoicedQuantities() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-VALIDATE");

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-VAL");
        line.setQuantity(new BigDecimal("2.00"));
        setField(line, "id", 90L);
        invoice.getLines().add(line);

        Map<Long, InvoiceLine> invoiceLines = Map.of(90L, line);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "validateReturnQuantities",
                company,
                invoice,
                new SalesReturnRequest(90L, "bad", List.of(new SalesReturnRequest.ReturnLine(999L, BigDecimal.ONE))),
                invoiceLines,
                new HashMap<String, FinishedGood>()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice line not found: 999");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "validateReturnQuantities",
                company,
                invoice,
                new SalesReturnRequest(90L, "bad", List.of(new SalesReturnRequest.ReturnLine(90L, new BigDecimal("3.00")))),
                invoiceLines,
                new HashMap<String, FinishedGood>()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return quantity exceeds invoiced amount for FG-VAL");
    }

    @Test
    void validateReturnQuantities_allocatesLegacyInvoiceScopedReturnsAcrossKnownInvoiceLines() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-LEGACY");

        InvoiceLine firstLine = new InvoiceLine();
        firstLine.setInvoice(invoice);
        firstLine.setProductCode("FG-1");
        firstLine.setQuantity(new BigDecimal("5.00"));
        setField(firstLine, "id", 81L);

        InvoiceLine secondLine = new InvoiceLine();
        secondLine.setInvoice(invoice);
        secondLine.setProductCode("FG-1");
        secondLine.setQuantity(new BigDecimal("5.00"));
        setField(secondLine, "id", 82L);

        InvoiceLine unrelatedLine = new InvoiceLine();
        unrelatedLine.setInvoice(invoice);
        unrelatedLine.setProductCode("FG-2");
        unrelatedLine.setQuantity(new BigDecimal("4.00"));
        setField(unrelatedLine, "id", 83L);

        invoice.getLines().add(firstLine);
        invoice.getLines().add(secondLine);
        invoice.getLines().add(unrelatedLine);

        FinishedGood fg1 = new FinishedGood();
        fg1.setCompany(company);
        fg1.setProductCode("FG-1");
        setField(fg1, "id", 401L);

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg1));
        InventoryMovement invoiceScopedLegacy = new InventoryMovement();
        invoiceScopedLegacy.setFinishedGood(fg1);
        invoiceScopedLegacy.setReferenceId("INV-LEGACY");
        invoiceScopedLegacy.setQuantity(new BigDecimal("2.00"));

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-LEGACY")).thenReturn(List.of(invoiceScopedLegacy));

        InventoryMovement lineScoped = new InventoryMovement();
        lineScoped.setFinishedGood(fg1);
        lineScoped.setReferenceId("INV-LEGACY:81");
        lineScoped.setQuantity(new BigDecimal("2.00"));

        FinishedGood otherFinishedGood = new FinishedGood();
        otherFinishedGood.setProductCode("FG-2");
        setField(otherFinishedGood, "id", 402L);

        InventoryMovement otherProduct = new InventoryMovement();
        otherProduct.setFinishedGood(otherFinishedGood);
        otherProduct.setReferenceId("INV-LEGACY:83");
        otherProduct.setQuantity(BigDecimal.ONE);

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-LEGACY:")).thenReturn(List.of(lineScoped, otherProduct));

        SalesReturnRequest request = new SalesReturnRequest(
                81L,
                "Legacy allocation",
                List.of(new SalesReturnRequest.ReturnLine(82L, new BigDecimal("3.00"))));

        Map<Long, InvoiceLine> invoiceLines = Map.of(
                81L, firstLine,
                82L, secondLine,
                83L, unrelatedLine
        );

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "validateReturnQuantities",
                company,
                invoice,
                request,
                invoiceLines,
                new HashMap<String, FinishedGood>());

        verify(finishedGoodRepository).lockByCompanyAndProductCode(company, "FG-1");
    }

    @Test
    void validateReturnQuantities_rejectsFinishedGoodLevelOverReturnFromInvalidLegacyMarkers() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-FG-OVER");

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-OVER");
        line.setQuantity(new BigDecimal("5.00"));
        setField(line, "id", 91L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-OVER");
        setField(fg, "id", 601L);

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-OVER")).thenReturn(Optional.of(fg));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-FG-OVER")).thenReturn(List.of());

        InventoryMovement invalidLegacy = new InventoryMovement();
        invalidLegacy.setFinishedGood(fg);
        invalidLegacy.setReferenceId("INV-FG-OVER:BAD-LINE");
        invalidLegacy.setQuantity(new BigDecimal("6.00"));

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-FG-OVER:")).thenReturn(List.of(invalidLegacy));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "validateReturnQuantities",
                company,
                invoice,
                new SalesReturnRequest(91L, "FG over", List.of(new SalesReturnRequest.ReturnLine(91L, BigDecimal.ONE))),
                Map.of(91L, line),
                new HashMap<String, FinishedGood>()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Return quantity exceeds remaining invoiced amount for FG-OVER");
    }

    @Test
    void validateReturnQuantities_skipsLegacyRowsWithMissingProductCodeAndFinishedGoodIds() {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-LEGACY-SKIP");

        InvoiceLine nullProduct = new InvoiceLine();
        nullProduct.setInvoice(invoice);
        nullProduct.setQuantity(new BigDecimal("2.00"));
        setField(nullProduct, "id", 81L);

        InvoiceLine requestedLine = new InvoiceLine();
        requestedLine.setInvoice(invoice);
        requestedLine.setProductCode("FG-LEG");
        requestedLine.setQuantity(new BigDecimal("2.00"));
        setField(requestedLine, "id", 82L);

        invoice.getLines().add(nullProduct);
        invoice.getLines().add(requestedLine);

        FinishedGood requestedFinishedGood = new FinishedGood();
        requestedFinishedGood.setCompany(company);
        requestedFinishedGood.setProductCode("FG-LEG");

        FinishedGood legacyFinishedGood = new FinishedGood();
        legacyFinishedGood.setCompany(company);
        legacyFinishedGood.setProductCode("FG-OTHER");
        setField(legacyFinishedGood, "id", 501L);

        FinishedGood trackedFinishedGood = new FinishedGood();
        trackedFinishedGood.setCompany(company);
        trackedFinishedGood.setProductCode("FG-LEG");
        setField(trackedFinishedGood, "id", 502L);

        InventoryMovement missingProductMovement = new InventoryMovement();
        missingProductMovement.setFinishedGood(legacyFinishedGood);
        missingProductMovement.setReferenceId("INV-LEGACY-SKIP:81");
        missingProductMovement.setQuantity(BigDecimal.ONE);

        InventoryMovement zeroQuantityRequestedLine = new InventoryMovement();
        zeroQuantityRequestedLine.setFinishedGood(trackedFinishedGood);
        zeroQuantityRequestedLine.setReferenceId("INV-LEGACY-SKIP:82");
        zeroQuantityRequestedLine.setQuantity(BigDecimal.ZERO);

        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LEG")).thenReturn(Optional.of(requestedFinishedGood));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-LEGACY-SKIP")).thenReturn(List.of());
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-LEGACY-SKIP:")).thenReturn(List.of(missingProductMovement, zeroQuantityRequestedLine));

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "validateReturnQuantities",
                company,
                invoice,
                new SalesReturnRequest(82L, "skip legacy", List.of(new SalesReturnRequest.ReturnLine(82L, BigDecimal.ONE))),
                Map.of(81L, nullProduct, 82L, requestedLine),
                new HashMap<String, FinishedGood>());

        verify(finishedGoodRepository).lockByCompanyAndProductCode(company, "FG-LEG");
    }

    @Test
    void ensureLinkedCorrectionJournal_repairsMismatchedReversalMetadata() {
        JournalEntry sourceJournal = new JournalEntry();
        setField(sourceJournal, "id", 940L);

        JournalEntry replayEntry = new JournalEntry();
        setField(replayEntry, "id", 941L);
        replayEntry.setCorrectionType(JournalCorrectionType.REVERSAL);
        replayEntry.setCorrectionReason("SALES_RETURN");
        replayEntry.setSourceModule("SALES_RETURN");
        replayEntry.setSourceReference("OLD-REF");
        JournalEntry wrongSource = new JournalEntry();
        setField(wrongSource, "id", 1L);
        replayEntry.setReversalOf(wrongSource);

        when(journalEntryRepository.findByCompanyAndId(company, 941L)).thenReturn(Optional.of(replayEntry));

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                stubEntry(941L),
                sourceJournal,
                "INV-CORR-1");

        verify(journalEntryRepository).save(argThat(entry -> Objects.equals(entry.getId(), 941L)
                && entry.getReversalOf() == sourceJournal
                && Objects.equals(entry.getSourceReference(), "INV-CORR-1")));
    }

    @Test
    void ensureLinkedCorrectionJournal_skipsSaveWhenReplayEntryAlreadyMatches() {
        JournalEntry sourceJournal = new JournalEntry();
        setField(sourceJournal, "id", 942L);

        JournalEntry aligned = new JournalEntry();
        setField(aligned, "id", 943L);
        aligned.setCorrectionType(JournalCorrectionType.REVERSAL);
        aligned.setCorrectionReason("SALES_RETURN");
        aligned.setSourceModule("SALES_RETURN");
        aligned.setSourceReference("INV-CORR-2");
        aligned.setReversalOf(sourceJournal);

        when(journalEntryRepository.findByCompanyAndId(company, 943L)).thenReturn(Optional.of(aligned));

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                stubEntry(943L),
                sourceJournal,
                "INV-CORR-2");

        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    void ensureLinkedCorrectionJournal_returnsWhenDtoOrSourceMissing() {
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                null,
                new JournalEntry(),
                "INV-CORR-NULL");
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                stubEntry(944L),
                null,
                "INV-CORR-NULL");

        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    void relinkExistingReturnMovements_skipsWhenJournalEntryMissingOrReferencesDoNotMatch() {
        InventoryMovement unrelated = new InventoryMovement();
        unrelated.setReferenceId("INV-RELINK-X:55:OTHER");
        unrelated.setJournalEntryId(41L);

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-RELINK-X")
        )).thenReturn(List.of(unrelated));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                eq(company),
                eq("SALES_RETURN"),
                eq("INV-RELINK-X:")
        )).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-X",
                new SalesReturnRequest(1L, "No-op", List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))),
                null,
                "KEY-1");
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-X",
                new SalesReturnRequest(1L, "No-op", List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))),
                99L,
                "KEY-1");

        verify(inventoryMovementRepository, never()).saveAll(any());
    }

    @Test
    void relinkExistingReturnMovements_skipsNullAndEmptyRequestsBeforeLoadingMovements() {
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-EMPTY",
                null,
                44L,
                "KEY-1");
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-EMPTY",
                new SalesReturnRequest(1L, "No-op", List.of()),
                44L,
                "KEY-1");

        verify(inventoryMovementRepository, never())
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(any(), any(), any());
        verify(inventoryMovementRepository, never())
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void relinkExistingReturnMovements_skipsWhenRequestLinesAreNull() {
        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-NULL-LINES",
                new SalesReturnRequest(1L, "No-op", null),
                44L,
                "KEY-1");

        verify(inventoryMovementRepository, never())
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void relinkExistingReturnMovements_skipsWhenRepositoriesReturnNoMovements() {
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-RELINK-NONE")).thenReturn(null);
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-RELINK-NONE:")).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-NONE",
                new SalesReturnRequest(1L, "No-op", List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))),
                99L,
                "KEY-1");

        verify(inventoryMovementRepository, never()).saveAll(any());
    }

    @Test
    void relinkExistingReturnMovements_updatesOnlyKeyedLineScopedReferencesForRequestedReturnKey() {
        InventoryMovement invoiceScoped = new InventoryMovement();
        invoiceScoped.setReferenceId("INV-RELINK-OK");
        invoiceScoped.setJournalEntryId(11L);

        InventoryMovement directLineScoped = new InventoryMovement();
        directLineScoped.setReferenceId("INV-RELINK-OK:55");
        directLineScoped.setJournalEntryId(14L);

        InventoryMovement lineScoped = new InventoryMovement();
        lineScoped.setReferenceId("INV-RELINK-OK:55:RET-KEY-9");
        lineScoped.setJournalEntryId(12L);

        InventoryMovement unrelated = new InventoryMovement();
        unrelated.setReferenceId("INV-RELINK-OK:77:OTHER");
        unrelated.setJournalEntryId(13L);

        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-RELINK-OK")).thenReturn(List.of(invoiceScoped));
        when(inventoryMovementRepository.findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company,
                "SALES_RETURN",
                "INV-RELINK-OK:")).thenReturn(List.of(directLineScoped, lineScoped, unrelated));

        ReflectionTestUtils.invokeMethod(
                salesReturnService,
                "relinkExistingReturnMovements",
                company,
                "INV-RELINK-OK",
                new SalesReturnRequest(1L, "Replay", List.of(new SalesReturnRequest.ReturnLine(55L, BigDecimal.ONE))),
                99L,
                "KEY-9");

        assertThat(invoiceScoped.getJournalEntryId()).isEqualTo(11L);
        assertThat(directLineScoped.getJournalEntryId()).isEqualTo(14L);
        assertThat(lineScoped.getJournalEntryId()).isEqualTo(99L);
        assertThat(unrelated.getJournalEntryId()).isEqualTo(13L);
        verify(inventoryMovementRepository).saveAll(argThat(movements -> {
            List<InventoryMovement> saved = (List<InventoryMovement>) movements;
            return saved.size() == 4
                    && saved.stream().anyMatch(movement -> "INV-RELINK-OK".equals(movement.getReferenceId())
                    && Objects.equals(movement.getJournalEntryId(), 11L))
                    && saved.stream().anyMatch(movement -> "INV-RELINK-OK:55".equals(movement.getReferenceId())
                    && Objects.equals(movement.getJournalEntryId(), 14L))
                    && saved.stream().anyMatch(movement -> "INV-RELINK-OK:55:RET-KEY-9".equals(movement.getReferenceId())
                    && Objects.equals(movement.getJournalEntryId(), 99L));
        }));
    }

    @Test
    void salesReturnReferences_handleMissingInvoiceAndMissingReturnKey() throws Exception {
        Class<?> helper = Class.forName("com.bigbrightpaints.erp.modules.sales.service.SalesReturnService$StreamReferenceHelper");
        var method = helper.getDeclaredMethod("salesReturnReferences", String.class, Long.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> empty = (List<String>) method.invoke(null, "   ", null, "KEY");
        @SuppressWarnings("unchecked")
        List<String> single = (List<String>) method.invoke(null, "INV-1", 55L, "   ");
        @SuppressWarnings("unchecked")
        List<String> keyed = (List<String>) method.invoke(null, "INV-1", 55L, "KEY");
        @SuppressWarnings("unchecked")
        List<String> invoiceOnly = (List<String>) method.invoke(null, "INV-ONLY", null, "KEY");

        assertThat(empty).isEmpty();
        assertThat(single).containsExactly("INV-1:55");
        assertThat(keyed).containsExactly("INV-1:55:RET-KEY");
        assertThat(invoiceOnly).containsExactly("INV-ONLY", "INV-ONLY:RET-KEY");
    }

    @Test
    void quantityValue_defaultsNullToZero() {
        assertThat((BigDecimal) ReflectionTestUtils.invokeMethod(salesReturnService, "quantityValue", new Object[]{null}))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) ReflectionTestUtils.invokeMethod(salesReturnService, "quantityValue", new BigDecimal("2.50")))
                .isEqualByComparingTo("2.50");
    }

    private JournalEntryDto stubEntry(long id) {
        return new JournalEntryDto(
                id,
                null,
                null,
                LocalDate.now(),
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

    private void attachPostedJournal(Invoice invoice, long journalId) {
        JournalEntry journalEntry = new JournalEntry();
        setField(journalEntry, "id", journalId);
        journalEntry.setStatus("POSTED");
        invoice.setJournalEntry(journalEntry);
        invoice.setStatus("POSTED");
    }
}
