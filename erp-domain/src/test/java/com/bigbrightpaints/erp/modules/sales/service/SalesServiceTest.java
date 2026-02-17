package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.AuditEvent;
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
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestRequest;
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
import com.bigbrightpaints.erp.modules.sales.dto.DispatchMarkerReconciliationResponse;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        lenient().when(packagingSlipRepository.findByIdAndCompany(anyLong(), eq(company)))
                .thenAnswer(invocation -> {
                    Long slipId = invocation.getArgument(0);
                    Optional<PackagingSlip> lockedSlip = packagingSlipRepository.findAndLockByIdAndCompany(slipId, company);
                    return lockedSlip != null ? lockedSlip : Optional.empty();
                });
    }

    @Test
    void createCreditRequestNormalizesPendingStatusForAdminQueue() {
        when(creditRequestRepository.save(any(CreditRequest.class))).thenAnswer(invocation -> {
            CreditRequest entity = invocation.getArgument(0);
            setField(entity, "id", 901L);
            return entity;
        });

        CreditRequestDto dto = salesService.createCreditRequest(new CreditRequestRequest(
                null,
                new BigDecimal("1500"),
                "Need temporary headroom",
                " pending "
        ));

        assertEquals("PENDING", dto.status());
        ArgumentCaptor<CreditRequest> requestCaptor = ArgumentCaptor.forClass(CreditRequest.class);
        verify(creditRequestRepository).save(requestCaptor.capture());
        assertEquals("PENDING", requestCaptor.getValue().getStatus());
    }

    @Test
    void createCreditRequestRejectsNonPendingInitialStatus() {
        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.createCreditRequest(
                new CreditRequestRequest(
                        null,
                        new BigDecimal("1200"),
                        "Attempted pre-approval",
                        "APPROVED"
                )));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        verify(creditRequestRepository, never()).save(any(CreditRequest.class));
    }

    @Test
    void updateCreditRequestRejectsStatusTransitionToApproved() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("800"));
        existing.setStatus("PENDING");
        setField(existing, "id", 902L);

        when(companyEntityLookup.requireCreditRequest(company, 902L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.updateCreditRequest(
                902L,
                new CreditRequestRequest(
                        null,
                        new BigDecimal("800"),
                        "Approved by admin",
                        " approved "
                )));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
    }

    @Test
    void updateCreditRequestRejectsEditsWhenRequestAlreadyFinalized() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("810"));
        existing.setReason("Already finalized");
        existing.setStatus("APPROVED");
        setField(existing, "id", 905L);

        when(companyEntityLookup.requireCreditRequest(company, 905L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.updateCreditRequest(
                905L,
                new CreditRequestRequest(
                        null,
                        new BigDecimal("920"),
                        "Edited after approval",
                        null
                )));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals(new BigDecimal("810"), existing.getAmountRequested());
        assertEquals("APPROVED", existing.getStatus());
    }

    @Test
    void updateCreditRequestAllowsExplicitSameStatus() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("810"));
        existing.setStatus("PENDING");
        setField(existing, "id", 904L);

        when(companyEntityLookup.requireCreditRequest(company, 904L)).thenReturn(existing);

        CreditRequestDto dto = salesService.updateCreditRequest(904L, new CreditRequestRequest(
                null,
                new BigDecimal("810"),
                "Minor note update",
                " pending "
        ));

        assertEquals("PENDING", dto.status());
        assertEquals("PENDING", existing.getStatus());
    }

    @Test
    void updateCreditRequestRejectsUnsupportedStatusValue() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setStatus("PENDING");
        setField(existing, "id", 903L);

        when(companyEntityLookup.requireCreditRequest(company, 903L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.updateCreditRequest(
                903L,
                new CreditRequestRequest(
                        null,
                        new BigDecimal("600"),
                        "Status drift attempt",
                        "in_review"
                )
        ));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
    }

    @Test
    void approveCreditRequestUpdatesStatusAndAuditsDecisionReason() {
        Dealer dealer = dealerWithCreditLimit(77L, new BigDecimal("2500"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setReason("Temporary headroom needed");
        existing.setStatus("PENDING");
        setField(existing, "id", 910L);

        when(companyEntityLookup.requireCreditRequest(company, 910L)).thenReturn(existing);

        CreditRequestDto dto = salesService.approveCreditRequest(910L, "  Exposure validated by accounting review  ");

        assertEquals("APPROVED", dto.status());
        assertEquals(new BigDecimal("600"), existing.getAmountRequested());
        assertEquals("Temporary headroom needed", existing.getReason());
        assertEquals(dealer.getId(), existing.getDealer().getId());

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_APPROVED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("APPROVED", metadata.get("decisionStatus"));
        assertEquals("Exposure validated by accounting review", metadata.get("decisionReason"));
        assertEquals("Exposure validated by accounting review", metadata.get("reason"));
        assertEquals("Temporary headroom needed", metadata.get("requestReason"));
    }

    @Test
    void approveCreditRequestRejectsNonPendingStatus() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setStatus("APPROVED");
        setField(existing, "id", 912L);

        when(companyEntityLookup.requireCreditRequest(company, 912L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(912L, "Already reviewed"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("APPROVED", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveCreditRequestRequiresDecisionReason() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setStatus("PENDING");
        setField(existing, "id", 914L);

        when(companyEntityLookup.requireCreditRequest(company, 914L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(914L, "   "));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void rejectCreditRequestUpdatesStatusAndAuditsDecisionReason() {
        Dealer dealer = dealerWithCreditLimit(78L, new BigDecimal("3000"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("725"));
        existing.setReason("Dealer requested temporary overrun");
        existing.setStatus("PENDING");
        setField(existing, "id", 911L);

        when(companyEntityLookup.requireCreditRequest(company, 911L)).thenReturn(existing);

        CreditRequestDto dto = salesService.rejectCreditRequest(911L, " Insufficient collateral documentation ");

        assertEquals("REJECTED", dto.status());
        assertEquals(new BigDecimal("725"), existing.getAmountRequested());
        assertEquals("Dealer requested temporary overrun", existing.getReason());
        assertEquals(dealer.getId(), existing.getDealer().getId());

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_REJECTED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("REJECTED", metadata.get("decisionStatus"));
        assertEquals("Insufficient collateral documentation", metadata.get("decisionReason"));
        assertEquals("Insufficient collateral documentation", metadata.get("reason"));
        assertEquals("Dealer requested temporary overrun", metadata.get("requestReason"));
    }

    @Test
    void rejectCreditRequestRejectsNonPendingStatus() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("725"));
        existing.setStatus("REJECTED");
        setField(existing, "id", 913L);

        when(companyEntityLookup.requireCreditRequest(company, 913L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.rejectCreditRequest(913L, "Already final"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("REJECTED", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void rejectCreditRequestRequiresDecisionReason() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("725"));
        existing.setStatus("PENDING");
        setField(existing, "id", 915L);

        when(companyEntityLookup.requireCreditRequest(company, 915L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.rejectCreditRequest(915L, " "));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verifyNoInteractions(auditService);
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
    void confirmDispatchRequiresPackingSlipIdWhenMultipleSlipsExist() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);

        PackagingSlip slipA = new PackagingSlip();
        slipA.setCompany(company);
        slipA.setSalesOrder(order);
        slipA.setStatus("PENDING");
        setField(slipA, "id", 55L);

        PackagingSlip slipB = new PackagingSlip();
        slipB.setCompany(company);
        slipB.setSalesOrder(order);
        slipB.setStatus("BACKORDER");
        setField(slipB, "id", 56L);

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slipA, slipB));

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
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
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
    void confirmDispatchCashSignaturePostsArAndCogsWithoutImplicitReceipt() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        Account receivable = new Account();
        receivable.setName("AR");
        setField(receivable, "id", 900L);
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-CASH-10");
        order.setStatus("READY_TO_SHIP");
        order.setIdempotencyHash(DigestUtils.sha256Hex("42|200|INR|NONE|false|0||CASH|SKU-CASH:1:200:0"));

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-CASH");
        item.setDescription("Cash dispatch order");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("200.00"));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-CASH");
        finishedGood.setCurrentStock(BigDecimal.ONE);
        finishedGood.setRevenueAccountId(3L);
        finishedGood.setValuationAccountId(11L);
        finishedGood.setCogsAccountId(12L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-CASH-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(new BigDecimal("120.00"));

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
        slipLine.setUnitCost(new BigDecimal("120.00"));
        slip.getLines().add(slipLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(invoiceNumberService.nextInvoiceNumber(company)).thenReturn("INV-CASH-55");
        when(invoiceRepository.save(ArgumentMatchers.any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            setField(invoice, "id", 777L);
            return invoice;
        });
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.save(ArgumentMatchers.any(PackagingSlip.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JournalEntryDto arEntry = new JournalEntryDto(
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
        JournalEntryDto cogsEntry = new JournalEntryDto(
                601L,
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
                ArgumentMatchers.nullable(Map.class),
                any(),
                anyString()
        )).thenReturn(arEntry);
        when(accountingFacade.postCogsJournal(
                anyString(),
                anyLong(),
                any(),
                anyString(),
                ArgumentMatchers.anyList()
        )).thenReturn(cogsEntry);

        DispatchConfirmResponse response = salesService.confirmDispatch(
                new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.TRUE, null, null));

        assertEquals(501L, response.arJournalEntryId());
        assertEquals(777L, response.finalInvoiceId());
        assertEquals(1, response.cogsPostings().size());
        assertEquals(11L, response.cogsPostings().get(0).inventoryAccountId());
        assertEquals(12L, response.cogsPostings().get(0).cogsAccountId());
        assertEquals(0, response.cogsPostings().get(0).cost().compareTo(new BigDecimal("120.00")));
        assertTrue(response.arPostings().stream().anyMatch(posting ->
                Long.valueOf(900L).equals(posting.accountId())
                        && posting.debit() != null
                        && posting.debit().compareTo(new BigDecimal("200.00")) == 0));

        verify(accountingFacade, times(1)).postSalesJournal(
                eq(dealer.getId()),
                eq(order.getOrderNumber()),
                any(),
                anyString(),
                anyMap(),
                anyMap(),
                ArgumentMatchers.nullable(Map.class),
                eq(new BigDecimal("200.00")),
                eq("INV-CASH-55")
        );
        verify(accountingFacade, times(1)).postCogsJournal(
                eq("PS-55"),
                eq(dealer.getId()),
                any(),
                anyString(),
                ArgumentMatchers.anyList()
        );
        verifyNoInteractions(accountingService);
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
    void confirmDispatchRejectsOverridesForAlreadyDispatchedSlip() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Attempted replay override",
                null);

        assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
        verifyNoInteractions(dealerRepository);
        verifyNoInteractions(finishedGoodsService);
        verifyNoInteractions(accountingFacade);
    }

    @Test
    void confirmDispatchRejectsReplayOverridesWhenOnlyOrderJournalAnchorOnMultiSlipOrder() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");
        order.setSalesJournalEntryId(222L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");

        PackagingSlip otherSlip = new PackagingSlip();
        setField(otherSlip, "id", 56L);
        otherSlip.setCompany(company);
        otherSlip.setSalesOrder(order);
        otherSlip.setStatus("DISPATCHED");

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, otherSlip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Attempted replay override",
                null);

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
        assertTrue(ex.getMessage().contains("without existing financial markers"));
        verifyNoInteractions(dealerRepository);
        verifyNoInteractions(finishedGoodsService);
        verifyNoInteractions(accountingFacade);
    }

    @Test
    void confirmDispatchTreatsOrderJournalAnchorAsReplayAnchorWhenOnlyOtherSlipCancelled() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");
        order.setSalesJournalEntryId(222L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");

        PackagingSlip cancelledSlip = new PackagingSlip();
        setField(cancelledSlip, "id", 56L);
        cancelledSlip.setCompany(company);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setStatus("CANCELLED");

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, cancelledSlip));
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

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
        assertTrue(ex.getMessage().contains("overrideReason is required when replaying overrides"));
        verifyNoInteractions(dealerRepository);
        verifyNoInteractions(finishedGoodsService);
        verifyNoInteractions(accountingFacade);
    }

    @Test
    void confirmDispatchRequiresOverrideReasonWhenReplayOverrideAnchored() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");
        slip.setJournalEntryId(222L);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
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

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
        assertTrue(ex.getMessage().contains("overrideReason is required when replaying overrides"));
        verifyNoInteractions(dealerRepository);
        verifyNoInteractions(finishedGoodsService);
        verifyNoInteractions(accountingFacade);
    }

    @Test
    void confirmDispatchAllowsReplayOverridesWhenAlreadyDispatchedHasJournalAnchor() {
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
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setRevenueAccountId(3L);
        finishedGood.setDiscountAccountId(4L);
        finishedGood.setValuationAccountId(11L);
        finishedGood.setCogsAccountId(12L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(new BigDecimal("25.00"));

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setJournalEntryId(222L);

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setShippedQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(new BigDecimal("25.00"));
        slip.getLines().add(slipLine);

        JournalEntry existingEntry = new JournalEntry();
        setField(existingEntry, "id", 222L);
        JournalLine line = new JournalLine();
        line.setAccount(receivable);
        line.setDebit(new BigDecimal("90.00"));
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

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10.00"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay with approved prior override",
                null);

        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(222L, response.arJournalEntryId());
        assertEquals(777L, response.finalInvoiceId());
        assertEquals(1, response.cogsPostings().size());
        assertEquals(11L, response.cogsPostings().get(0).inventoryAccountId());
        assertEquals(12L, response.cogsPostings().get(0).cogsAccountId());
        assertEquals(0, response.cogsPostings().get(0).cost().compareTo(new BigDecimal("25.00")));
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
        verify(accountingFacade, times(1)).postCogsJournal(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyList());
    }

    @Test
    void confirmDispatchRejectsReplayOverrideWhenExistingJournalTotalMismatches() {
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        Account receivable = new Account();
        setField(receivable, "id", 900L);
        receivable.setName("AR");
        dealer.setReceivableAccount(receivable);

        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-10");
        order.setStatus("SHIPPED");

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-D");
        item.setDescription("Desc");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setGstRate(BigDecimal.ZERO);
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-D");
        finishedGood.setRevenueAccountId(3L);
        finishedGood.setDiscountAccountId(4L);
        finishedGood.setValuationAccountId(11L);
        finishedGood.setCogsAccountId(12L);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("B-1");
        batch.setQuantityTotal(BigDecimal.ONE);
        batch.setQuantityAvailable(BigDecimal.ONE);
        batch.setUnitCost(new BigDecimal("25.00"));

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setJournalEntryId(222L);

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setShippedQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(new BigDecimal("25.00"));
        slip.getLines().add(slipLine);

        JournalEntry existingEntry = new JournalEntry();
        setField(existingEntry, "id", 222L);
        JournalLine receivableLine = new JournalLine();
        receivableLine.setAccount(receivable);
        receivableLine.setDebit(new BigDecimal("100.00"));
        receivableLine.setCredit(BigDecimal.ZERO);
        existingEntry.getLines().add(receivableLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(companyEntityLookup.requireJournalEntry(company, 222L)).thenReturn(existingEntry);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10.00"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay with approved prior override",
                null);

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmDispatch(request));
        assertTrue(ex.getMessage().contains("Existing AR journal total does not match dispatch total"));
        verify(invoiceRepository, never()).save(any(Invoice.class));
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
        verify(accountingFacade, never()).postCogsJournal(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyList());
    }

    @Test
    void confirmDispatchReplayFastPathLogsOverrideAuditMetadata() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(777L);
        slip.setJournalEntryId(222L);
        slip.setCogsJournalEntryId(333L);

        Invoice existingInvoice = new Invoice();
        setField(existingInvoice, "id", 777L);
        existingInvoice.setTotalAmount(new BigDecimal("90.00"));

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(invoiceRepository.findByCompanyAndId(company, 777L)).thenReturn(Optional.of(existingInvoice));

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override in fast path",
                null);

        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(777L, response.finalInvoiceId());
        assertEquals(222L, response.arJournalEntryId());
        assertTrue(response.cogsPostings().isEmpty());
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
        verify(accountingFacade, never()).postCogsJournal(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyList());
        verifyNoInteractions(accountingService);

        ArgumentCaptor<Map<String, String>> auditMetadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DISPATCH_CONFIRMED), auditMetadataCaptor.capture());
        Map<String, String> metadata = auditMetadataCaptor.getValue();
        assertEquals("true", metadata.get("alreadyDispatched"));
        assertEquals("true", metadata.get("dispatchOverridesApplied"));
        assertEquals("Replay override in fast path", metadata.get("dispatchOverrideReason"));
    }

    @Test
    void confirmDispatchReplayFastPathSkipsDuplicateCogsWhenJournalExistsByReference() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(777L);
        slip.setJournalEntryId(222L);
        slip.setCogsJournalEntryId(null);

        Invoice existingInvoice = new Invoice();
        setField(existingInvoice, "id", 777L);
        existingInvoice.setTotalAmount(new BigDecimal("90.00"));

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(slip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(invoiceRepository.findByCompanyAndId(company, 777L)).thenReturn(Optional.of(existingInvoice));
        when(accountingFacade.hasCogsJournalFor("PS-55")).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with pre-existing COGS reference",
                null);

        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(777L, response.finalInvoiceId());
        assertEquals(222L, response.arJournalEntryId());
        assertTrue(response.cogsPostings().isEmpty());
        verify(accountingFacade, atLeastOnce()).hasCogsJournalFor("PS-55");
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
        verify(accountingFacade, never()).postCogsJournal(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyList());
        verifyNoInteractions(accountingService);
    }

    @Test
    void confirmDispatchReplayFastPathDoesNotSetOrderJournalForMultiSlipOrder() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");
        order.setSalesJournalEntryId(901L);
        order.setCogsJournalEntryId(902L);
        order.setFulfillmentInvoiceId(903L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(777L);
        slip.setJournalEntryId(222L);
        slip.setCogsJournalEntryId(333L);

        PackagingSlip otherSlip = new PackagingSlip();
        setField(otherSlip, "id", 56L);
        otherSlip.setCompany(company);
        otherSlip.setSalesOrder(order);
        otherSlip.setSlipNumber("PS-56");
        otherSlip.setStatus("DISPATCHED");

        Invoice existingInvoice = new Invoice();
        setField(existingInvoice, "id", 777L);
        existingInvoice.setTotalAmount(new BigDecimal("90.00"));

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, otherSlip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(invoiceRepository.findByCompanyAndId(company, 777L)).thenReturn(Optional.of(existingInvoice));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with multi-slip order",
                null);

        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(777L, response.finalInvoiceId());
        assertEquals(222L, response.arJournalEntryId());
        assertNull(order.getSalesJournalEntryId());
        assertNull(order.getCogsJournalEntryId());
        assertNull(order.getFulfillmentInvoiceId());
        verify(salesOrderRepository, times(1)).save(order);
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
    }

    @Test
    void confirmDispatchReplayFastPathRetainsOrderMarkersWhenOnlyOtherSlipCancelled() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("SHIPPED");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-55");
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(777L);
        slip.setJournalEntryId(222L);
        slip.setCogsJournalEntryId(333L);

        PackagingSlip cancelledSlip = new PackagingSlip();
        setField(cancelledSlip, "id", 56L);
        cancelledSlip.setCompany(company);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setSlipNumber("PS-56");
        cancelledSlip.setStatus("CANCELLED");

        Invoice existingInvoice = new Invoice();
        setField(existingInvoice, "id", 777L);
        existingInvoice.setTotalAmount(new BigDecimal("90.00"));

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, cancelledSlip));
        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(invoiceRepository.findByCompanyAndId(company, 777L)).thenReturn(Optional.of(existingInvoice));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with cancelled secondary slip",
                null);

        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(777L, response.finalInvoiceId());
        assertEquals(222L, response.arJournalEntryId());
        assertEquals(222L, order.getSalesJournalEntryId());
        assertEquals(333L, order.getCogsJournalEntryId());
        assertEquals(777L, order.getFulfillmentInvoiceId());
    }

    @Test
    void reconcileStaleOrderLevelMarkersClearsMarkersForDriftedMultiSlipOrders() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setSalesJournalEntryId(901L);
        order.setCogsJournalEntryId(902L);
        order.setFulfillmentInvoiceId(903L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");

        PackagingSlip otherSlip = new PackagingSlip();
        setField(otherSlip, "id", 56L);
        otherSlip.setCompany(company);
        otherSlip.setSalesOrder(order);
        otherSlip.setStatus("PENDING");

        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(eq(company), any()))
                .thenReturn(new PageImpl<>(List.of(10L), PageRequest.of(0, 200), 1));
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 10L))
                .thenReturn(Optional.of(order));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, otherSlip));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DispatchMarkerReconciliationResponse response = salesService.reconcileStaleOrderLevelMarkers(200);

        assertEquals(1, response.scannedOrders());
        assertEquals(1, response.reconciledOrders());
        assertEquals(List.of(10L), response.reconciledOrderIds());
        assertNull(order.getSalesJournalEntryId());
        assertNull(order.getCogsJournalEntryId());
        assertNull(order.getFulfillmentInvoiceId());
        verify(salesOrderRepository).save(order);
    }

    @Test
    void reconcileStaleOrderLevelMarkersRetainsMarkersWhenOnlyOtherSlipCancelled() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setSalesJournalEntryId(901L);
        order.setCogsJournalEntryId(902L);
        order.setFulfillmentInvoiceId(903L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");

        PackagingSlip cancelledSlip = new PackagingSlip();
        setField(cancelledSlip, "id", 56L);
        cancelledSlip.setCompany(company);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setStatus("CANCELLED");

        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(eq(company), any()))
                .thenReturn(new PageImpl<>(List.of(10L), PageRequest.of(0, 200), 1));
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 10L))
                .thenReturn(Optional.of(order));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, cancelledSlip));

        DispatchMarkerReconciliationResponse response = salesService.reconcileStaleOrderLevelMarkers(200);

        assertEquals(1, response.scannedOrders());
        assertEquals(0, response.reconciledOrders());
        assertTrue(response.reconciledOrderIds().isEmpty());
        assertEquals(901L, order.getSalesJournalEntryId());
        assertEquals(902L, order.getCogsJournalEntryId());
        assertEquals(903L, order.getFulfillmentInvoiceId());
        verify(salesOrderRepository, never()).save(any(SalesOrder.class));
    }

    @Test
    void reconcileStaleOrderLevelMarkersRepairsSingleActiveSlipMarkerDrift() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setSalesJournalEntryId(901L);
        order.setCogsJournalEntryId(902L);
        order.setFulfillmentInvoiceId(903L);

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 55L);
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(777L);
        slip.setJournalEntryId(222L);
        slip.setCogsJournalEntryId(333L);

        PackagingSlip cancelledSlip = new PackagingSlip();
        setField(cancelledSlip, "id", 56L);
        cancelledSlip.setCompany(company);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setStatus("CANCELLED");

        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(eq(company), any()))
                .thenReturn(new PageImpl<>(List.of(10L), PageRequest.of(0, 200), 1));
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 10L))
                .thenReturn(Optional.of(order));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, cancelledSlip));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DispatchMarkerReconciliationResponse response = salesService.reconcileStaleOrderLevelMarkers(200);

        assertEquals(1, response.scannedOrders());
        assertEquals(1, response.reconciledOrders());
        assertEquals(List.of(10L), response.reconciledOrderIds());
        assertEquals(222L, order.getSalesJournalEntryId());
        assertEquals(333L, order.getCogsJournalEntryId());
        assertEquals(777L, order.getFulfillmentInvoiceId());
        verify(salesOrderRepository).save(order);
    }

    @Test
    void reconcileStaleOrderLevelMarkersProcessesAllReturnedCandidates() {
        SalesOrder singleSlipOrder = new SalesOrder();
        setField(singleSlipOrder, "id", 10L);
        singleSlipOrder.setCompany(company);
        singleSlipOrder.setSalesJournalEntryId(901L);
        singleSlipOrder.setCogsJournalEntryId(902L);
        singleSlipOrder.setFulfillmentInvoiceId(903L);

        SalesOrder multiSlipOrder = new SalesOrder();
        setField(multiSlipOrder, "id", 20L);
        multiSlipOrder.setCompany(company);
        multiSlipOrder.setSalesJournalEntryId(801L);
        multiSlipOrder.setCogsJournalEntryId(802L);
        multiSlipOrder.setFulfillmentInvoiceId(803L);

        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(
                company, PageRequest.of(0, 200)))
                .thenReturn(new PageImpl<>(List.of(10L, 20L), PageRequest.of(0, 200), 2));

        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 10L))
                .thenReturn(Optional.of(singleSlipOrder));
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 20L))
                .thenReturn(Optional.of(multiSlipOrder));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagingSlip singleSlip = new PackagingSlip();
        setField(singleSlip, "id", 55L);
        singleSlip.setCompany(company);
        singleSlip.setSalesOrder(singleSlipOrder);
        singleSlip.setStatus("DISPATCHED");

        PackagingSlip cancelledSingle = new PackagingSlip();
        setField(cancelledSingle, "id", 56L);
        cancelledSingle.setCompany(company);
        cancelledSingle.setSalesOrder(singleSlipOrder);
        cancelledSingle.setStatus("CANCELLED");

        PackagingSlip multiSlipA = new PackagingSlip();
        setField(multiSlipA, "id", 65L);
        multiSlipA.setCompany(company);
        multiSlipA.setSalesOrder(multiSlipOrder);
        multiSlipA.setStatus("DISPATCHED");

        PackagingSlip multiSlipB = new PackagingSlip();
        setField(multiSlipB, "id", 66L);
        multiSlipB.setCompany(company);
        multiSlipB.setSalesOrder(multiSlipOrder);
        multiSlipB.setStatus("PENDING");

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(singleSlip, cancelledSingle));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 20L))
                .thenReturn(List.of(multiSlipA, multiSlipB));

        DispatchMarkerReconciliationResponse response = salesService.reconcileStaleOrderLevelMarkers(200);

        assertEquals(2, response.scannedOrders());
        assertEquals(1, response.reconciledOrders());
        assertEquals(List.of(20L), response.reconciledOrderIds());
        assertEquals(901L, singleSlipOrder.getSalesJournalEntryId());
        assertNull(multiSlipOrder.getSalesJournalEntryId());
        assertNull(multiSlipOrder.getCogsJournalEntryId());
        assertNull(multiSlipOrder.getFulfillmentInvoiceId());
    }

    @Test
    void reconcileStaleOrderLevelMarkersPaginatesUntilReconcileLimitReached() {
        SalesOrder healthyOrder = new SalesOrder();
        setField(healthyOrder, "id", 10L);
        healthyOrder.setCompany(company);

        SalesOrder driftedOrder = new SalesOrder();
        setField(driftedOrder, "id", 20L);
        driftedOrder.setCompany(company);
        driftedOrder.setSalesJournalEntryId(801L);
        driftedOrder.setCogsJournalEntryId(802L);
        driftedOrder.setFulfillmentInvoiceId(803L);

        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(
                company, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(10L), PageRequest.of(0, 1), 2));
        when(salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(
                company, PageRequest.of(1, 1)))
                .thenReturn(new PageImpl<>(List.of(20L), PageRequest.of(1, 1), 2));

        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 10L))
                .thenReturn(Optional.of(healthyOrder));
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, 20L))
                .thenReturn(Optional.of(driftedOrder));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagingSlip healthySlip = new PackagingSlip();
        setField(healthySlip, "id", 55L);
        healthySlip.setCompany(company);
        healthySlip.setSalesOrder(healthyOrder);
        healthySlip.setStatus("DISPATCHED");

        PackagingSlip driftedSlipA = new PackagingSlip();
        setField(driftedSlipA, "id", 65L);
        driftedSlipA.setCompany(company);
        driftedSlipA.setSalesOrder(driftedOrder);
        driftedSlipA.setStatus("DISPATCHED");

        PackagingSlip driftedSlipB = new PackagingSlip();
        setField(driftedSlipB, "id", 66L);
        driftedSlipB.setCompany(company);
        driftedSlipB.setSalesOrder(driftedOrder);
        driftedSlipB.setStatus("PENDING");

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(healthySlip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 20L))
                .thenReturn(List.of(driftedSlipA, driftedSlipB));

        DispatchMarkerReconciliationResponse response = salesService.reconcileStaleOrderLevelMarkers(1);

        assertEquals(2, response.scannedOrders());
        assertEquals(1, response.reconciledOrders());
        assertEquals(List.of(20L), response.reconciledOrderIds());
        assertNull(driftedOrder.getSalesJournalEntryId());
        assertNull(driftedOrder.getCogsJournalEntryId());
        assertNull(driftedOrder.getFulfillmentInvoiceId());
    }

    @Test
    void confirmDispatchReusesOrderJournalWhenOnlyOtherSlipCancelled() {
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
        order.setStatus("SHIPPED");
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
        slip.setStatus("DISPATCHED");

        PackagingSlip cancelledSlip = new PackagingSlip();
        setField(cancelledSlip, "id", 56L);
        cancelledSlip.setCompany(company);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setSlipNumber("PS-56");
        cancelledSlip.setStatus("CANCELLED");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setShippedQuantity(BigDecimal.ONE);
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
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip, cancelledSlip));
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

        DispatchConfirmResponse response = salesService.confirmDispatch(
                new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.TRUE, null, null));

        assertEquals(222L, response.arJournalEntryId());
        verify(accountingFacade, never()).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
    }

    @Test
    void confirmDispatchPostsArWhenOrderJournalExistsButSlipNotDispatched() {
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

        verify(accountingFacade, times(1)).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString());
    }

    @Test
    void confirmDispatchPostsArWhenOrderJournalExistsButOrderBecomesMultiSlip() {
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

        PackagingSlip otherSlip = new PackagingSlip();
        setField(otherSlip, "id", 56L);
        otherSlip.setCompany(company);
        otherSlip.setSalesOrder(order);
        otherSlip.setSlipNumber("PS-56");
        otherSlip.setStatus("PENDING");

        PackagingSlipLine slipLine = new PackagingSlipLine();
        setField(slipLine, "id", 99L);
        slipLine.setPackagingSlip(slip);
        slipLine.setFinishedGoodBatch(batch);
        slipLine.setOrderedQuantity(BigDecimal.ONE);
        slipLine.setQuantity(BigDecimal.ONE);
        slipLine.setUnitCost(BigDecimal.ZERO);
        slip.getLines().add(slipLine);

        when(packagingSlipRepository.findAndLockByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L))
                .thenReturn(List.of(slip), List.of(slip, otherSlip), List.of(slip, otherSlip));
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

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.TRUE, null, null);
        salesService.confirmDispatch(request);

        verify(accountingFacade, times(1)).postSalesJournal(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.nullable(Map.class),
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

        assertThrows(CreditLimitExceededException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderCreditLimitIgnoresPendingOrderExposureProjection() {
        setupProduct("SKU3-EXPOSURE", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-EXPOSURE");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-EXPOSURE"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(422L, BigDecimal.valueOf(1000));
        when(companyEntityLookup.requireDealer(company, 422L)).thenReturn(dealer);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-EXPOSURE-42");
        when(dealerLedgerService.currentBalance(422L)).thenReturn(BigDecimal.valueOf(400));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                setField(entity, "id", 4220L);
            }
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                422L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-EXPOSURE", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null);

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals("RESERVED", dto.status());
        verify(salesOrderRepository, never()).sumPendingCreditExposureByCompanyAndDealer(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anySet(),
                ArgumentMatchers.any());
    }

    @Test
    void createOrderCreditLimitFailureReturnsExistingIdempotentReplay() {
        setupProduct("SKU3-RACE", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-RACE");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-RACE"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(423L, BigDecimal.valueOf(1000));
        when(companyEntityLookup.requireDealer(company, 423L)).thenReturn(dealer);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-RACE-42");
        when(dealerLedgerService.currentBalance(423L)).thenReturn(BigDecimal.valueOf(950));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 4230L);
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setOrderNumber("SO-RACE-42");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(200));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(200));
        existing.setIdempotencyHash(DigestUtils.sha256Hex("423|200|INR|NONE|false|0||SKU3-RACE:1:200:0"));
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 4231L);
        existingItem.setSalesOrder(existing);
        existingItem.setProductCode("SKU3-RACE");
        existingItem.setDescription("Desc");
        existingItem.setQuantity(BigDecimal.ONE);
        existingItem.setUnitPrice(BigDecimal.valueOf(200));
        existingItem.setLineSubtotal(BigDecimal.valueOf(200));
        existingItem.setGstRate(BigDecimal.ZERO);
        existingItem.setGstAmount(BigDecimal.ZERO);
        existingItem.setLineTotal(BigDecimal.valueOf(200));
        existing.getItems().add(existingItem);

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, "SO-RACE-KEY"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        SalesOrderRequest request = new SalesOrderRequest(
                423L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-RACE", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                "SO-RACE-KEY",
                "CREDIT");

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        assertEquals("SO-RACE-42", dto.orderNumber());
        verify(salesOrderRepository, never()).save(any(SalesOrder.class));
    }

    @Test
    void createOrderCashPaymentModeStillEnforcesDealerCreditLimit() {
        setupProduct("SKU3-CASH", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-CASH");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-CASH"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(420L, BigDecimal.valueOf(1000));
        when(companyEntityLookup.requireDealer(company, 420L)).thenReturn(dealer);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-CASH-42");
        when(dealerLedgerService.currentBalance(420L)).thenReturn(BigDecimal.valueOf(950));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                setField(entity, "id", 520L);
            }
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                420L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-CASH", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null,
                " cash ");

        assertThrows(CreditLimitExceededException.class, () -> salesService.createOrder(request));
        verify(dealerLedgerService).currentBalance(420L);
    }

    @Test
    void updateOrderCashPaymentModeStillEnforcesDealerCreditLimit() {
        setupProduct("SKU3-UPD-CASH", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-UPD-CASH");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-UPD-CASH"))
                .thenReturn(Optional.of(finishedGood));

        Dealer dealer = dealerWithCreditLimit(430L, BigDecimal.valueOf(1000));
        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 4300L);
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setStatus("BOOKED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(200));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(200));

        when(companyEntityLookup.requireSalesOrder(company, 4300L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(dealerLedgerService.currentBalance(430L)).thenReturn(BigDecimal.valueOf(950));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 4300L)).thenReturn(List.of());

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-UPD-CASH", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null,
                "CASH");

        assertThrows(CreditLimitExceededException.class, () -> salesService.updateOrder(4300L, request));
        verify(dealerLedgerService).currentBalance(430L);
    }

    @Test
    void createOrderSplitPaymentModeStillEnforcesDealerCreditLimit() {
        setupProduct("SKU3-SPLIT", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-SPLIT");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-SPLIT"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(421L, BigDecimal.valueOf(1000));
        when(companyEntityLookup.requireDealer(company, 421L)).thenReturn(dealer);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-SPLIT-42");
        when(dealerLedgerService.currentBalance(421L)).thenReturn(BigDecimal.valueOf(950));

        SalesOrderRequest request = new SalesOrderRequest(
                421L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-SPLIT", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null,
                "SPLIT");

        assertThrows(CreditLimitExceededException.class, () -> salesService.createOrder(request));
        verify(dealerLedgerService).currentBalance(421L);
    }

    @Test
    void createOrderRejectsUnsupportedPaymentMode() {
        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU-BAD-MODE", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "NONE",
                null,
                null,
                null,
                "WIRE_TRANSFER");

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.createOrder(request));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        verifyNoInteractions(dealerLedgerService);
    }

    @Test
    void createOrderIdempotentRetry_acceptsStoredSignatureWithDefaultPaymentModeSegment() {
        setupProduct("SKU3-IDEMP", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-IDEMP");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-IDEMP"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-IDEMP-1");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 901L);
        existing.setCompany(company);
        existing.setOrderNumber("SO-IDEMP-1");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));
        existing.setIdempotencyHash(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||CREDIT|SKU3-IDEMP:1:100:0"));
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 911L);
        existingItem.setSalesOrder(existing);
        existingItem.setProductCode("SKU3-IDEMP");
        existingItem.setDescription("Desc");
        existingItem.setQuantity(BigDecimal.ONE);
        existingItem.setUnitPrice(BigDecimal.valueOf(100));
        existingItem.setLineSubtotal(BigDecimal.valueOf(100));
        existingItem.setGstRate(BigDecimal.ZERO);
        existingItem.setGstAmount(BigDecimal.ZERO);
        existingItem.setLineTotal(BigDecimal.valueOf(100));
        existing.getItems().add(existingItem);

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, "SO-IDEMP-KEY"))
                .thenReturn(Optional.of(existing));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-IDEMP", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "NONE",
                null,
                null,
                "SO-IDEMP-KEY",
                "CREDIT");

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        assertEquals("SO-IDEMP-1", dto.orderNumber());
        verify(salesOrderRepository).save(existing);
        assertEquals(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||SKU3-IDEMP:1:100:0"), existing.getIdempotencyHash());
    }

    @Test
    void createOrderAutoIdempotencyRetry_acceptsLegacyDefaultCreditKeyShape() {
        SalesOrderItemRequest requestItem = new SalesOrderItemRequest("SKU3-IDEMP", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null);
        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(requestItem),
                "NONE",
                null,
                null,
                null,
                "CREDIT");
        String canonicalKey = request.resolveIdempotencyKey();
        String legacyDefaultKey = request.resolveIdempotencyKeyIncludingDefaultPaymentMode();
        assertTrue(!canonicalKey.equals(legacyDefaultKey));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 902L);
        existing.setCompany(company);
        existing.setOrderNumber("SO-IDEMP-AUTO");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));
        existing.setIdempotencyHash(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||CREDIT|SKU3-IDEMP:1:100:0"));
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 912L);
        existingItem.setSalesOrder(existing);
        existingItem.setProductCode("SKU3-IDEMP");
        existingItem.setDescription("Desc");
        existingItem.setQuantity(BigDecimal.ONE);
        existingItem.setUnitPrice(BigDecimal.valueOf(100));
        existingItem.setLineSubtotal(BigDecimal.valueOf(100));
        existingItem.setGstRate(BigDecimal.ZERO);
        existingItem.setGstAmount(BigDecimal.ZERO);
        existingItem.setLineTotal(BigDecimal.valueOf(100));
        existing.getItems().add(existingItem);

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, legacyDefaultKey))
                .thenReturn(Optional.of(existing));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        verify(salesOrderRepository, never()).findByCompanyAndIdempotencyKey(company, canonicalKey);
        assertEquals(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||SKU3-IDEMP:1:100:0"), existing.getIdempotencyHash());
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

        assertThrows(CreditLimitExceededException.class, () -> salesService.createOrder(request));
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
