package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.CreditLimitExceededException;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.*;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private SalesTargetRepository salesTargetRepository;
    @Mock
    private CreditRequestRepository creditRequestRepository;
    @Mock
    private OrderNumberService orderNumberService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProductionProductRepository productionProductRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private AccountingService accountingService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository journalEntryRepository;
    @Mock
    private InvoiceNumberService invoiceNumberService;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private FactoryTaskRepository factoryTaskRepository;
    @Mock
    private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock
    private CreditLimitOverrideService creditLimitOverrideService;
    @Mock
    private AuditService auditService;
    @Mock
    private CompanyClock companyClock;
    private final PlatformTransactionManager transactionManager = new NoopTransactionManager();

    private SalesService salesService;
    private Company company;

    @BeforeEach
    void setUp() {
        salesService = new SalesService(
                companyContextService,
                dealerRepository,
                salesOrderRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                orderNumberService,
                eventPublisher,
                productionProductRepository,
                dealerLedgerService,
                finishedGoodRepository,
                accountRepository,
                companyEntityLookup,
                packagingSlipRepository,
                finishedGoodsService,
                accountingService,
                accountingFacade,
                journalEntryRepository,
                invoiceNumberService,
                invoiceRepository,
                factoryTaskRepository,
                companyDefaultAccountsService,
                companyAccountingSettingsService,
                creditLimitOverrideService,
                auditService,
                companyClock,
                transactionManager);

        when(finishedGoodsService.reserveForOrder(any()))
                .thenReturn(new InventoryReservationResult(null, List.of()));
        when(companyDefaultAccountsService.requireDefaults())
                .thenReturn(new CompanyDefaultAccountsService.DefaultAccounts(1L, 2L, 3L, 4L, 5L));
        when(companyDefaultAccountsService.getDefaults())
                .thenReturn(new CompanyDefaultAccountsService.DefaultAccounts(1L, 2L, 3L, 4L, 5L));
        company = new Company();
        company.setCode("COMP");
        company.setTimezone("UTC");
        company.setDefaultInventoryAccountId(1L);
        company.setDefaultCogsAccountId(2L);
        company.setDefaultRevenueAccountId(3L);
        company.setDefaultDiscountAccountId(4L);
        company.setDefaultTaxAccountId(5L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(any())).thenReturn(java.time.LocalDate.of(2026, 1, 27));
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(eq(company), anyLong())).thenReturn(List.of());
    }

    @Test
    void createOrderNeedsRevenueAccount() {
        setupProduct("SKU1", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU1");
        finishedGood.setRevenueAccountId(null);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU1"))
                .thenReturn(Optional.of(finishedGood));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU1", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.ZERO)),
                "NONE",
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderRequiresTaxAccountWhenGstPerItem() {
        setupProduct("SKU2", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU2");
        finishedGood.setRevenueAccountId(1L);
        finishedGood.setTaxAccountId(null);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU2"))
                .thenReturn(Optional.of(finishedGood));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(110),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU2", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.TEN)),
                "PER_ITEM",
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void confirmDispatchBlocksCancelledOrder() {
        SalesOrder cancelled = new SalesOrder();
        cancelled.setStatus("CANCELLED");
        setField(cancelled, "id", 10L);

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(cancelled);
        slip.setStatus("PENDING");
        setField(slip, "id", 55L);

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip));
        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(cancelled);

        DispatchConfirmRequest request = new DispatchConfirmRequest(null, 10L, List.of(), null, null, false, null, null);

        assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
    }

    @Test
    void confirmDispatchBlocksWhenCreditLimitExceededWithoutOverride() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(100));
        Account receivable = new Account();
        receivable.setName("AR");
        setField(receivable, "id", 900L);
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-10");
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(BigDecimal.valueOf(200));

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-D");
        item.setDescription("Desc");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(200));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setCurrentStock(BigDecimal.ONE);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(BigDecimal.ZERO);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("PENDING");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(BigDecimal.ZERO);
        slip.getLines().add(slipLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(dealerLedgerService.currentBalance(dealer.getId())).thenReturn(BigDecimal.ZERO);

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.FALSE, null, null);

        assertThrows(CreditLimitExceededException.class, () -> salesService.confirmDispatch(request));
    }

    @Test
    void confirmDispatchAllowsAdminOverrideCreditLimit() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(100));
        Account receivable = new Account();
        receivable.setName("AR");
        setField(receivable, "id", 900L);
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-10");
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(BigDecimal.valueOf(200));

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-D");
        item.setDescription("Desc");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(200));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setCurrentStock(BigDecimal.ONE);
        finishedGood.setRevenueAccountId(3L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(BigDecimal.ZERO);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("PENDING");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(BigDecimal.ZERO);
        slip.getLines().add(slipLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(invoiceNumberService.nextInvoiceNumber(company)).thenReturn("INV-55");
        when(invoiceRepository.save(ArgumentMatchers.any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            setField(invoice, "id", 777L);
            return invoice;
        });
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.save(ArgumentMatchers.any(PackagingSlip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findById(ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.TRUE, null, null);
        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(55L, response.packingSlipId());
        assertEquals(10L, response.salesOrderId());
        assertEquals(777L, response.finalInvoiceId());
        verifyNoInteractions(creditLimitOverrideService);
    }

    @Test
    void confirmDispatchPostsDiscountLines() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        Account receivable = new Account();
        receivable.setName("AR");
        setField(receivable, "id", 900L);
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-10");
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(BigDecimal.valueOf(90));

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-D");
        item.setDescription("Desc");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(100));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setCurrentStock(BigDecimal.ONE);
        finishedGood.setRevenueAccountId(3L);
        finishedGood.setDiscountAccountId(4L);
        finishedGood.setValuationAccountId(11L);
        finishedGood.setCogsAccountId(12L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(BigDecimal.ZERO);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("PENDING");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(BigDecimal.ZERO);
        slip.getLines().add(slipLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(invoiceNumberService.nextInvoiceNumber(company)).thenReturn("INV-55");
        when(invoiceRepository.save(ArgumentMatchers.any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            setField(invoice, "id", 777L);
            return invoice;
        });
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.save(ArgumentMatchers.any(PackagingSlip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findById(ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-D")).thenReturn(Optional.of(finishedGood));

        JournalEntryDto journalEntryDto = new JournalEntryDto(
                501L,
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingFacade.postSalesJournal(
                anyLong(),
                anyString(),
                any(),
                anyString(),
                anyMap(),
                anyMap(),
                anyMap(),
                any(),
                anyString()
        )).thenReturn(journalEntryDto);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Discount override for test",
                null);
        salesService.confirmDispatch(request);

        ArgumentCaptor<Map<Long, BigDecimal>> revenueCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> taxCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> discountCaptor = ArgumentCaptor.forClass(Map.class);
        verify(accountingFacade).postSalesJournal(
                eq(dealer.getId()),
                eq(order.getOrderNumber()),
                any(),
                anyString(),
                revenueCaptor.capture(),
                taxCaptor.capture(),
                discountCaptor.capture(),
                eq(new BigDecimal("90.00")),
                eq("INV-55")
        );

        assertEquals(new BigDecimal("100.00"), revenueCaptor.getValue().get(3L));
        assertEquals(new BigDecimal("10.00"), discountCaptor.getValue().get(4L));
        assertEquals(0, taxCaptor.getValue().size());
    }

    @Test
    void confirmDispatchRequiresOverrideReasonWhenOverridesProvided() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("READY_TO_SHIP");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("PENDING");

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                null,
                null);

        assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
    }

    @Test
    void confirmDispatchSkipsArPostingWhenOrderAlreadyHasJournal() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        Account receivable = new Account();
        receivable.setName("AR");
        setField(receivable, "id", 900L);
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-10");
        order.setStatus("READY_TO_SHIP");
        order.setSalesJournalEntryId(222L);

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-D");
        item.setDescription("Desc");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(200));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setCurrentStock(BigDecimal.ONE);
        finishedGood.setRevenueAccountId(3L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(BigDecimal.ZERO);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("PENDING");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(BigDecimal.ZERO);
        slip.getLines().add(slipLine);

        JournalEntry existingEntry = new JournalEntry();
        setField(existingEntry, "id", 222L);
        JournalLine line = new JournalLine();
        line.setAccount(receivable);
        line.setDebit(new BigDecimal("200"));
        line.setCredit(BigDecimal.ZERO);
        existingEntry.getLines().add(line);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(companyEntityLookup.requireJournalEntry(company, 222L)).thenReturn(existingEntry);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(invoiceNumberService.nextInvoiceNumber(company)).thenReturn("INV-55");
        when(invoiceRepository.save(ArgumentMatchers.any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            setField(invoice, "id", 777L);
            return invoice;
        });
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.save(ArgumentMatchers.any(PackagingSlip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findById(ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.TRUE, null, null);
        salesService.confirmDispatch(request);

        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
    }

    @Test
    void createOrderRespectsDealerCreditLimit() {
        setupProduct("SKU3", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        when(companyEntityLookup.requireDealer(company, 42L)).thenReturn(dealer);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-42");
        when(dealerLedgerService.currentBalance(42L)).thenReturn(BigDecimal.valueOf(950));

        SalesOrderRequest request = new SalesOrderRequest(
                42L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderUsesCompanyDefaultGstForOrderTotal() {
        company.setDefaultGstRate(BigDecimal.valueOf(15));
        setupProduct("SKU4", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU4");
        finishedGood.setRevenueAccountId(6L);
        finishedGood.setTaxAccountId(7L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU4"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-100");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 501L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(115),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU4", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "ORDER_TOTAL",
                null,
                null,
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository, times(2)).save(orderCaptor.capture());
        SalesOrder finalSaved = orderCaptor.getAllValues().get(orderCaptor.getAllValues().size() - 1);
        assertEquals(new BigDecimal("15.0000"), finalSaved.getGstRate());
    }

    @Test
    void createOrderUsesCompanyDefaultGstForPerItemWhenMissing() {
        company.setDefaultGstRate(BigDecimal.valueOf(12));
        setupProduct("SKU5", BigDecimal.valueOf(50), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU5");
        finishedGood.setRevenueAccountId(8L);
        finishedGood.setTaxAccountId(9L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU5"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-200");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 601L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(56),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU5", "Desc", BigDecimal.ONE, BigDecimal.valueOf(50), null)),
                "PER_ITEM",
                null,
                null,
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository, times(2)).save(orderCaptor.capture());
        SalesOrder finalSaved = orderCaptor.getAllValues().get(orderCaptor.getAllValues().size() - 1);
        assertEquals(new BigDecimal("12.0000"), finalSaved.getItems().get(0).getGstRate());
    }

    @Test
    void createOrderCalculatesMixedPerItemGst() {
        setupProduct("SKU6", BigDecimal.valueOf(100), BigDecimal.valueOf(18));
        setupProduct("SKU7", BigDecimal.valueOf(50), BigDecimal.valueOf(5));

        FinishedGood taxable = buildFinishedGood("SKU6");
        taxable.setRevenueAccountId(10L);
        taxable.setTaxAccountId(11L);
        FinishedGood reduced = buildFinishedGood("SKU7");
        reduced.setRevenueAccountId(12L);
        reduced.setTaxAccountId(13L);

        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU6"))
                .thenReturn(Optional.of(taxable));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU7"))
                .thenReturn(Optional.of(reduced));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-300");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 701L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                new BigDecimal("288.50"),
                "INR",
                null,
                List.of(
                        new SalesOrderItemRequest("SKU6", "High", new BigDecimal("2"), BigDecimal.valueOf(100), BigDecimal.valueOf(18)),
                        new SalesOrderItemRequest("SKU7", "Reduced", BigDecimal.ONE, BigDecimal.valueOf(50), BigDecimal.valueOf(5))
                ),
                "PER_ITEM",
                null,
                false,
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository, times(2)).save(orderCaptor.capture());
        SalesOrder saved = orderCaptor.getAllValues().get(orderCaptor.getAllValues().size() - 1);
        assertEquals(new BigDecimal("250.00"), saved.getSubtotalAmount());
        assertEquals(new BigDecimal("38.50"), saved.getGstTotal());
        assertEquals(new BigDecimal("288.50"), saved.getTotalAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), saved.getGstRoundingAdjustment());
        assertEquals(new BigDecimal("36.00"), saved.getItems().get(0).getGstAmount());
        assertEquals(new BigDecimal("2.50"), saved.getItems().get(1).getGstAmount());
    }

    @Test
    void createOrderHandlesExemptPerItemGst() {
        setupProduct("SKU8", BigDecimal.valueOf(100), BigDecimal.valueOf(12));
        setupProduct("SKU9", BigDecimal.valueOf(50), BigDecimal.ZERO);

        FinishedGood taxable = buildFinishedGood("SKU8");
        taxable.setRevenueAccountId(14L);
        taxable.setTaxAccountId(15L);
        FinishedGood exempt = buildFinishedGood("SKU9");
        exempt.setRevenueAccountId(16L);
        exempt.setTaxAccountId(17L);

        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU8"))
                .thenReturn(Optional.of(taxable));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU9"))
                .thenReturn(Optional.of(exempt));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-301");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 801L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                new BigDecimal("162.00"),
                "INR",
                null,
                List.of(
                        new SalesOrderItemRequest("SKU8", "Taxed", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.valueOf(12)),
                        new SalesOrderItemRequest("SKU9", "Exempt", BigDecimal.ONE, BigDecimal.valueOf(50), BigDecimal.ZERO)
                ),
                "PER_ITEM",
                null,
                false,
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository, times(2)).save(orderCaptor.capture());
        SalesOrder saved = orderCaptor.getAllValues().get(orderCaptor.getAllValues().size() - 1);
        assertEquals(new BigDecimal("150.00"), saved.getSubtotalAmount());
        assertEquals(new BigDecimal("12.00"), saved.getGstTotal());
        assertEquals(new BigDecimal("162.00"), saved.getTotalAmount());
        assertEquals(new BigDecimal("12.00"), saved.getItems().get(0).getGstAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), saved.getItems().get(1).getGstAmount());
    }

    @Test
    void createOrderSetsPendingProductionWhenShortage() {
        company.setDefaultGstRate(BigDecimal.valueOf(12));
        setupProduct("SKU10", BigDecimal.valueOf(50), BigDecimal.ZERO);
        setupProduct("SKU11", BigDecimal.valueOf(75), BigDecimal.ZERO);
        FinishedGood fg1 = buildFinishedGood("SKU10");
        fg1.setRevenueAccountId(20L);
        fg1.setTaxAccountId(21L);
        FinishedGood fg2 = buildFinishedGood("SKU11");
        fg2.setRevenueAccountId(22L);
        fg2.setTaxAccountId(23L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU10")).thenReturn(Optional.of(fg1));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU11")).thenReturn(Optional.of(fg2));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-400");
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 901L);
            return entity;
        });

        InventoryShortage shortage = new InventoryShortage("SKU10", BigDecimal.ONE, "Prod 10");
        when(finishedGoodsService.reserveForOrder(any())).thenReturn(new InventoryReservationResult(null, List.of(shortage)));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                new BigDecimal("125.00"),
                "INR",
                null,
                List.of(
                        new SalesOrderItemRequest("SKU10", "Q1", BigDecimal.ONE, BigDecimal.valueOf(50), null),
                        new SalesOrderItemRequest("SKU11", "Q2", BigDecimal.ONE, BigDecimal.valueOf(75), null)
                ),
                "NONE",
                null,
                false,
                "IDEMP-SHORTAGE");

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals("PENDING_PRODUCTION", dto.status());
        verify(finishedGoodsService).reserveForOrder(any());
    }

    @Test
    void cancelOrderReleasesReservations() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("RESERVED");
        setField(order, "id", 42L);
        when(companyEntityLookup.requireSalesOrder(company, 42L)).thenReturn(order);

        SalesOrderDto dto = salesService.cancelOrder(42L, "Customer cancelled");

        assertEquals("CANCELLED", dto.status());
        verify(finishedGoodsService).releaseReservationsForOrder(42L);
    }

    @Test
    void cancelOrderSkipsReleaseWhenAlreadyDispatched() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("DISPATCHED");
        setField(order, "id", 43L);
        when(companyEntityLookup.requireSalesOrder(company, 43L)).thenReturn(order);

        SalesOrderDto dto = salesService.cancelOrder(43L, "Too late");

        assertEquals("CANCELLED", dto.status());
        verify(finishedGoodsService, never()).releaseReservationsForOrder(anyLong());
    }

    @Test
    void createOrderThrowsWhenCreditLimitExceeded() {
        setupProduct("SKU-CL", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood fg = buildFinishedGood("SKU-CL");
        fg.setRevenueAccountId(5L);
        fg.setTaxAccountId(6L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-CL"))
                .thenReturn(Optional.of(fg));

        Dealer dealer = dealerWithCreditLimit(99L, BigDecimal.valueOf(100));
        dealer.setReceivableAccount(new Account());
        when(dealerRepository.lockByCompanyAndId(company, 99L)).thenReturn(Optional.of(dealer));
        when(dealerLedgerService.currentBalance(99L)).thenReturn(BigDecimal.valueOf(90));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-CL-1");

        SalesOrderRequest request = new SalesOrderRequest(
                99L,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU-CL", "Item", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.ZERO)),
                "NONE",
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    private void setupProduct(String sku, BigDecimal price, BigDecimal gstRate) {
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode(sku);
        product.setBasePrice(price);
        product.setGstRate(gstRate);
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setCode("BR");
        brand.setName("Brand");
        product.setBrand(brand);
        product.setCompany(company);
        when(productionProductRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.of(product));
    }

    private FinishedGood buildFinishedGood(String sku) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(sku);
        finishedGood.setName("Name");
        finishedGood.setCurrentStock(BigDecimal.ZERO);
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setValuationAccountId(10L);
        finishedGood.setCogsAccountId(11L);
        return finishedGood;
    }

    private Dealer dealerWithCreditLimit(long id, BigDecimal limit) {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer");
        dealer.setCreditLimit(limit);
        setField(dealer, "id", id);
        return dealer;
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

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
