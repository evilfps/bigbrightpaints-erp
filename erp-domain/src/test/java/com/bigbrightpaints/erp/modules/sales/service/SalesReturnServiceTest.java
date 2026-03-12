package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
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
                invoiceRepository,
                companyAccountingSettingsService,
                finishedGoodsService
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(900L, 800L, null));
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
        setField(invoice, "id", 10L);

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setProductCode("FG-1");
        line.setQuantity(new BigDecimal("1"));
        setField(line, "id", 55L);
        invoice.getLines().add(line);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
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
}
