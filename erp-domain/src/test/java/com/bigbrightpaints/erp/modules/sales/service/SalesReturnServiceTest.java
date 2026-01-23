package com.bigbrightpaints.erp.modules.sales.service;

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
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;

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
                companyEntityLookup,
                companyAccountingSettingsService
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyAccountingSettingsService.requireTaxAccounts())
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

        when(companyEntityLookup.requireInvoice(company, 10L)).thenReturn(invoice);
        when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.lockByCompanyAndId(company, 21L)).thenReturn(Optional.of(fg));
        when(finishedGoodRepository.findById(21L)).thenReturn(Optional.of(fg));
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
        when(inventoryMovementRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER,
                "99")
        ).thenReturn(List.of(dispatchMovement));

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
