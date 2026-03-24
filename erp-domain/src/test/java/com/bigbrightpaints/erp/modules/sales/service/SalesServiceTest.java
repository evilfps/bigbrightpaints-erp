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
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
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
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestRequest;
import com.bigbrightpaints.erp.modules.sales.dto.PromotionDto;
import com.bigbrightpaints.erp.modules.sales.dto.PromotionRequest;
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
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderSearchFilters;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderStatusHistoryDto;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchMarkerReconciliationResponse;
import com.bigbrightpaints.erp.modules.sales.dto.SalesDashboardDto;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("critical")
class SalesServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private SalesOrderStatusHistoryRepository salesOrderStatusHistoryRepository;
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
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
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
    private GstService gstService;
    @Mock
    private CreditLimitOverrideService creditLimitOverrideService;
    @Mock
    private AuditService auditService;
    @Mock
    private CompanyClock companyClock;
    private final PlatformTransactionManager transactionManager = new NoopTransactionManager();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SalesService salesService;
    private Company company;

    @BeforeEach
    void setUp() {
        salesService = new SalesService(
                companyContextService,
                dealerRepository,
                salesOrderRepository,
                salesOrderStatusHistoryRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                orderNumberService,
                eventPublisher,
                productionProductRepository,
                dealerLedgerService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
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
                gstService,
                creditLimitOverrideService,
                auditService,
                companyClock,
                transactionManager,
                meterRegistry);

        when(finishedGoodsService.reserveForOrder(any()))
                .thenReturn(new InventoryReservationResult(null, List.of()));
        lenient().when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(factoryTaskRepository.findByCompanyAndSalesOrderId(any(), anyLong())).thenReturn(List.of());
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
        lenient().when(gstService.calculateGst(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(0);
            BigDecimal rate = invocation.getArgument(3);
            BigDecimal taxable = amount == null ? BigDecimal.ZERO : amount;
            BigDecimal resolvedRate = rate == null ? BigDecimal.ZERO : rate;
            BigDecimal igst = taxable.multiply(resolvedRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            return new GstService.GstBreakdown(taxable, BigDecimal.ZERO, BigDecimal.ZERO, igst, GstService.TaxType.INTER_STATE);
        });
        lenient().when(gstService.splitTaxAmount(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal taxable = invocation.getArgument(0);
            BigDecimal tax = invocation.getArgument(1);
            return new GstService.GstBreakdown(
                    taxable == null ? BigDecimal.ZERO : taxable,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    tax == null ? BigDecimal.ZERO : tax,
                    GstService.TaxType.INTER_STATE);
        });
        lenient().when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
        lenient().when(packagingSlipRepository.findByIdAndCompany(anyLong(), eq(company)))
                .thenAnswer(invocation -> {
                    Long slipId = invocation.getArgument(0);
                    Optional<PackagingSlip> lockedSlip = packagingSlipRepository.findAndLockByIdAndCompany(slipId, company);
                    return lockedSlip != null ? lockedSlip : Optional.empty();
                });
    }

    @Test
    void getDashboardReturnsDealerOrderAndPendingCreditRequestCounts() {
        when(dealerRepository.countByCompanyAndStatusIgnoreCase(eq(company), anyString())).thenReturn(4L);
        when(creditRequestRepository.countByCompanyAndStatusIgnoreCase(company, "PENDING")).thenReturn(2L);
        when(salesOrderRepository.countByCompanyGroupedByNormalizedStatus(company)).thenReturn(List.of(
                new Object[] {"BOOKED", 3L},
                new Object[] {"pending_production", 5L},
                new Object[] {"SHIPPED", 2L},
                new Object[] {"COMPLETED", 1L},
                new Object[] {"CANCELLED", 4L},
                new Object[] {"UNKNOWN", 6L}
        ));

        SalesDashboardDto dashboard = salesService.getDashboard();

        assertEquals(4L, dashboard.activeDealers());
        assertEquals(21L, dashboard.totalOrders());
        assertEquals(2L, dashboard.pendingCreditRequests());
        assertEquals(3L, dashboard.orderStatusBuckets().get("open"));
        assertEquals(5L, dashboard.orderStatusBuckets().get("in_progress"));
        assertEquals(2L, dashboard.orderStatusBuckets().get("dispatched"));
        assertEquals(1L, dashboard.orderStatusBuckets().get("completed"));
        assertEquals(4L, dashboard.orderStatusBuckets().get("cancelled"));
        assertEquals(6L, dashboard.orderStatusBuckets().get("other"));
    }

    @Test
    void getDashboardSkipsMalformedAggregateRowsAndKeepsDefaultBuckets() {
        when(dealerRepository.countByCompanyAndStatusIgnoreCase(eq(company), anyString())).thenReturn(0L);
        when(creditRequestRepository.countByCompanyAndStatusIgnoreCase(company, "PENDING")).thenReturn(0L);
        when(salesOrderRepository.countByCompanyGroupedByNormalizedStatus(company)).thenReturn(
                java.util.Arrays.asList(
                        null,
                        new Object[] {"BOOKED"},
                        new Object[] {"BOOKED", "not-a-number"},
                        new Object[] {null, 2L}
                ));

        SalesDashboardDto dashboard = salesService.getDashboard();

        assertEquals(2L, dashboard.totalOrders());
        assertEquals(0L, dashboard.orderStatusBuckets().get("open"));
        assertEquals(0L, dashboard.orderStatusBuckets().get("in_progress"));
        assertEquals(0L, dashboard.orderStatusBuckets().get("dispatched"));
        assertEquals(0L, dashboard.orderStatusBuckets().get("completed"));
        assertEquals(0L, dashboard.orderStatusBuckets().get("cancelled"));
        assertEquals(2L, dashboard.orderStatusBuckets().get("other"));
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
    void listCreditRequestsUsesDealerFetchPathForStableDtoMapping() {
        Dealer dealer = new Dealer();
        dealer.setName("Prime Dealer");
        CreditRequest request = new CreditRequest();
        request.setCompany(company);
        request.setDealer(dealer);
        request.setAmountRequested(new BigDecimal("900"));
        request.setStatus("PENDING");
        request.setReason("Temporary extension");
        setField(request, "id", 9011L);
        setField(request, "publicId", UUID.randomUUID());

        when(creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company))
                .thenReturn(List.of(request));

        List<CreditRequestDto> response = salesService.listCreditRequests();

        assertEquals(1, response.size());
        assertEquals("Prime Dealer", response.getFirst().dealerName());
        verify(creditRequestRepository).findByCompanyWithDealerOrderByCreatedAtDesc(company);
        verify(creditRequestRepository, never()).findByCompanyOrderByCreatedAtDesc(any());
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
    void approveCreditRequestIncrementsDealerCreditLimitAndAuditsMutationMetadata() {
        Dealer dealer = dealerWithCreditLimit(77L, new BigDecimal("2500"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setReason("Temporary headroom needed");
        existing.setStatus("PENDING");
        setField(existing, "id", 910L);

        when(companyEntityLookup.requireCreditRequest(company, 910L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 77L)).thenReturn(Optional.of(dealer));

        CreditRequestDto dto = salesService.approveCreditRequest(910L, "  Exposure validated by accounting review  ");

        assertEquals("APPROVED", dto.status());
        assertEquals(new BigDecimal("600"), existing.getAmountRequested());
        assertEquals("Temporary headroom needed", existing.getReason());
        assertEquals(dealer.getId(), existing.getDealer().getId());
        assertEquals(new BigDecimal("3100"), dealer.getCreditLimit());
        verify(dealerRepository).lockByCompanyAndId(company, 77L);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_APPROVED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("APPROVED", metadata.get("decisionStatus"));
        assertEquals("Exposure validated by accounting review", metadata.get("decisionReason"));
        assertEquals("Exposure validated by accounting review", metadata.get("reason"));
        assertEquals("Temporary headroom needed", metadata.get("requestReason"));
        assertEquals("2500", metadata.get("oldLimit"));
        assertEquals("3100", metadata.get("newLimit"));
        assertEquals("600", metadata.get("increment"));
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
    void approveCreditRequestFailsClosedWhenDealerIsMissing() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setStatus("PENDING");
        setField(existing, "id", 916L);

        when(companyEntityLookup.requireCreditRequest(company, 916L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(916L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verify(auditService, never()).logSuccess(any(), any());
        verify(dealerRepository, never()).lockByCompanyAndId(any(), anyLong());
    }

    @Test
    void approveCreditRequestFailsClosedWhenDealerCannotBeLocked() {
        Dealer dealer = dealerWithCreditLimit(81L, new BigDecimal("2000"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("600"));
        existing.setStatus("PENDING");
        setField(existing, "id", 917L);

        when(companyEntityLookup.requireCreditRequest(company, 917L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 81L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(917L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_REFERENCE, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        assertEquals(new BigDecimal("2000"), dealer.getCreditLimit());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveCreditRequestFailsClosedWhenAmountRequestedIsInvalid() {
        Dealer dealer = dealerWithCreditLimit(82L, new BigDecimal("2000"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(BigDecimal.ZERO);
        existing.setStatus("PENDING");
        setField(existing, "id", 918L);

        when(companyEntityLookup.requireCreditRequest(company, 918L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 82L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(918L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        assertEquals(new BigDecimal("2000"), dealer.getCreditLimit());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveCreditRequestFailsClosedWhenAmountRequestedIsMissing() {
        Dealer dealer = dealerWithCreditLimit(83L, new BigDecimal("2000"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(null);
        existing.setStatus("PENDING");
        setField(existing, "id", 919L);

        when(companyEntityLookup.requireCreditRequest(company, 919L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 83L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(919L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveCreditRequestFailsClosedWhenDealerIdIsMissing() {
        Dealer dealer = dealerWithCreditLimit(84L, new BigDecimal("2100"));
        setField(dealer, "id", null);
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("100"));
        existing.setStatus("PENDING");
        setField(existing, "id", 920L);

        when(companyEntityLookup.requireCreditRequest(company, 920L)).thenReturn(existing);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(920L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(dealerRepository, never()).lockByCompanyAndId(any(), anyLong());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveCreditRequestFailsClosedWhenDealerLimitIsMissing() {
        Dealer dealer = dealerWithCreditLimit(85L, new BigDecimal("2100"));
        dealer.setCreditLimit(null);
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("100"));
        existing.setStatus("PENDING");
        setField(existing, "id", 921L);

        when(companyEntityLookup.requireCreditRequest(company, 921L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 85L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(921L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveCreditRequestFailsClosedWhenDealerLimitIsNegative() {
        Dealer dealer = dealerWithCreditLimit(86L, new BigDecimal("2100"));
        dealer.setCreditLimit(new BigDecimal("-1"));
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setAmountRequested(new BigDecimal("100"));
        existing.setStatus("PENDING");
        setField(existing, "id", 922L);

        when(companyEntityLookup.requireCreditRequest(company, 922L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, 86L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.approveCreditRequest(922L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(auditService, never()).logSuccess(any(), any());
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
    void updateStatusRejectsUnknownStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 921L);

        when(companyEntityLookup.requireSalesOrder(company, 921L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateStatus(921L, "archived"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Unknown order status"));
        assertEquals("BOOKED", order.getStatus());
    }

    @Test
    void updateStatusRejectsBlankStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 920L);

        when(companyEntityLookup.requireSalesOrder(company, 920L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateStatus(920L, " "));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Status is required"));
    }

    @Test
    void updateStatusRejectsWorkflowOnlyStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 922L);

        when(companyEntityLookup.requireSalesOrder(company, 922L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateStatus(922L, "confirmed"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("workflow endpoints"));
        assertEquals("BOOKED", order.getStatus());
    }

    @Test
    void updateStatusAllowsManualStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 923L);

        when(companyEntityLookup.requireSalesOrder(company, 923L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 923L)).thenReturn(List.of());

        SalesOrderDto dto = salesService.updateStatus(923L, " on_hold ");

        assertEquals("ON_HOLD", dto.status());
        assertEquals("ON_HOLD", order.getStatus());
    }

    @Test
    void updateOrchestratorWorkflowStatusRejectsInvalidStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 924L);

        when(companyEntityLookup.requireSalesOrder(company, 924L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateOrchestratorWorkflowStatus(924L, "ON_HOLD"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("cannot be set by orchestrator"));
        assertEquals("BOOKED", order.getStatus());
    }

    @Test
    void updateOrchestratorWorkflowStatusRejectsBlankStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 926L);

        when(companyEntityLookup.requireSalesOrder(company, 926L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateOrchestratorWorkflowStatus(926L, " "));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Status is required"));
    }

    @Test
    void updateOrchestratorWorkflowStatusAcceptsAllowedStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 925L);

        when(companyEntityLookup.requireSalesOrder(company, 925L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 925L)).thenReturn(List.of());

        salesService.updateOrchestratorWorkflowStatus(925L, " ready_to_ship ");

        assertEquals("READY_TO_SHIP", order.getStatus());
    }

    @Test
    void updateOrchestratorWorkflowStatusRejectsTerminalManualCurrentStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("REJECTED");
        setField(order, "id", 927L);

        when(companyEntityLookup.requireSalesOrder(company, 927L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.updateOrchestratorWorkflowStatus(927L, " ready_to_ship "));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("REJECTED"));
        assertEquals("REJECTED", order.getStatus());
        verifyNoInteractions(packagingSlipRepository);
    }

    @Test
    void updateOrchestratorWorkflowStatusAllowsOnHoldCurrentStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("ON_HOLD");
        setField(order, "id", 928L);

        when(companyEntityLookup.requireSalesOrder(company, 928L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 928L)).thenReturn(List.of());

        salesService.updateOrchestratorWorkflowStatus(928L, " ready_to_ship ");

        assertEquals("READY_TO_SHIP", order.getStatus());
    }

    @Test
    void updateOrchestratorWorkflowStatusAllowsBlankCurrentStatus() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("   ");
        setField(order, "id", 929L);

        when(companyEntityLookup.requireSalesOrder(company, 929L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 929L)).thenReturn(List.of());

        salesService.updateOrchestratorWorkflowStatus(929L, " ready_to_ship ");

        assertEquals("READY_TO_SHIP", order.getStatus());
    }

    @Test
    void attachTraceIdSetsFirstTraceAndDoesNotOverwriteDifferingTrace() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("BOOKED");
        setField(order, "id", 933L);

        when(companyEntityLookup.requireSalesOrder(company, 933L)).thenReturn(order);

        salesService.attachTraceId(933L, "   ");
        assertNull(order.getTraceId());

        salesService.attachTraceId(933L, " trace-101 ");
        assertEquals("trace-101", order.getTraceId());

        salesService.attachTraceId(933L, "   ");
        assertEquals("trace-101", order.getTraceId());

        salesService.attachTraceId(933L, "trace-202");
        assertEquals("trace-101", order.getTraceId());

        salesService.attachTraceId(933L, " trace-101 ");
        assertEquals("trace-101", order.getTraceId());
    }

    @Test
    void hasDispatchConfirmationReturnsFalseWhenNoSlipsFound() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 930L);

        when(companyEntityLookup.requireSalesOrder(company, 930L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 930L)).thenReturn(List.of());

        assertFalse(salesService.hasDispatchConfirmation(930L));
    }

    @Test
    void hasDispatchConfirmationReturnsFalseWhenDispatchMarkersAreIncomplete() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 931L);

        PackagingSlip slip = new PackagingSlip();
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(11L);

        when(companyEntityLookup.requireSalesOrder(company, 931L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 931L)).thenReturn(List.of(slip));

        assertFalse(salesService.hasDispatchConfirmation(931L));
    }

    @Test
    void hasDispatchConfirmationReturnsTrueWhenDispatchTruthExists() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 932L);

        PackagingSlip slip = new PackagingSlip();
        slip.setStatus("DISPATCHED");
        slip.setSlipNumber("PS-932");
        slip.setInvoiceId(12L);
        slip.setJournalEntryId(13L);

        when(companyEntityLookup.requireSalesOrder(company, 932L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 932L)).thenReturn(List.of(slip));
        when(accountingFacade.hasCogsJournalFor("PS-932")).thenReturn(true);

        assertTrue(salesService.hasDispatchConfirmation(932L));
    }

    @Test
    void listOrdersPagedUsesCompanyPathWhenStatusBlankAndDealerMissing() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 941L);
        order.setStatus("BOOKED");
        order.setOrderNumber("SO-941");

        when(salesOrderRepository.findIdsByCompanyOrderByCreatedAtDescIdDesc(eq(company), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(941L)));
        when(salesOrderRepository.findByCompanyAndIdInOrderByCreatedAtDescIdDesc(company, List.of(941L)))
                .thenReturn(List.of(order));

        List<SalesOrderDto> results = salesService.listOrders(" ", -3, 400);

        assertEquals(1, results.size());
        assertEquals(941L, results.get(0).id());
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(salesOrderRepository).findIdsByCompanyOrderByCreatedAtDescIdDesc(eq(company), pageCaptor.capture());
        assertEquals(0, pageCaptor.getValue().getPageNumber());
        assertEquals(200, pageCaptor.getValue().getPageSize());
        verify(salesOrderRepository, never())
                .findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(any(), any(), anyString(), any());
    }

    @Test
    void listOrdersPagedUsesDealerAndStatusPath() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer A");
        setField(dealer, "id", 501L);

        SalesOrder order = new SalesOrder();
        order.setDealer(dealer);
        order.setStatus("CONFIRMED");
        order.setOrderNumber("SO-942");
        setField(order, "id", 942L);

        when(companyEntityLookup.requireDealer(company, 501L)).thenReturn(dealer);
        when(salesOrderRepository.findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(
                eq(company), eq(dealer), eq("CONFIRMED"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(942L)));
        when(salesOrderRepository.findByCompanyAndIdInOrderByCreatedAtDescIdDesc(company, List.of(942L)))
                .thenReturn(List.of(order));

        List<SalesOrderDto> results = salesService.listOrders("CONFIRMED", 501L, 2, 25);

        assertEquals(1, results.size());
        assertEquals(942L, results.get(0).id());
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(salesOrderRepository).findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(
                eq(company), eq(dealer), eq("CONFIRMED"), pageCaptor.capture());
        assertEquals(2, pageCaptor.getValue().getPageNumber());
        assertEquals(25, pageCaptor.getValue().getPageSize());
    }

    @Test
    void listOrdersPagedReturnsEmptyWhenNoIdsFound() {
        when(salesOrderRepository.findIdsByCompanyAndStatusOrderByCreatedAtDescIdDesc(eq(company), eq("ON_HOLD"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        List<SalesOrderDto> results = salesService.listOrders("ON_HOLD", 0, 10);

        assertTrue(results.isEmpty());
        verify(salesOrderRepository, never()).findByCompanyAndIdInOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void listOrdersNonPagedUsesDealerPathWhenStatusBlank() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer B");
        setField(dealer, "id", 502L);

        SalesOrder order = new SalesOrder();
        order.setDealer(dealer);
        order.setStatus("BOOKED");
        order.setOrderNumber("SO-943");
        setField(order, "id", 943L);

        when(companyEntityLookup.requireDealer(company, 502L)).thenReturn(dealer);
        when(salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer)).thenReturn(List.of(order));

        List<SalesOrderDto> results = salesService.listOrders("   ", 502L);

        assertEquals(1, results.size());
        assertEquals(943L, results.get(0).id());
        verify(salesOrderRepository).findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer);
    }

    @Test
    void listOrdersNonPagedUsesStatusPathWhenDealerMissing() {
        SalesOrder order = new SalesOrder();
        order.setStatus("ON_HOLD");
        order.setOrderNumber("SO-944");
        setField(order, "id", 944L);

        when(salesOrderRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, "ON_HOLD"))
                .thenReturn(List.of(order));

        List<SalesOrderDto> results = salesService.listOrders("ON_HOLD");

        assertEquals(1, results.size());
        assertEquals(944L, results.get(0).id());
        verify(salesOrderRepository).findByCompanyAndStatusOrderByCreatedAtDesc(company, "ON_HOLD");
        verify(salesOrderRepository, never()).findByCompanyOrderByCreatedAtDesc(company);
    }

    @Test
    void createDealerCreatesUniqueReceivableAccountAndLinksControlAccount() {
        Account existingReceivable = new Account();
        existingReceivable.setCompany(company);
        existingReceivable.setCode("AR-DLR");
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-DLR")).thenReturn(Optional.of(existingReceivable));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-DLR-1")).thenReturn(Optional.empty());

        Account arControl = new Account();
        arControl.setCompany(company);
        arControl.setCode("AR");
        arControl.setType(com.bigbrightpaints.erp.modules.accounting.domain.AccountType.ASSET);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR")).thenReturn(Optional.of(arControl));

        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            setField(saved, "id", 811L);
            return saved;
        });
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> {
            Dealer saved = invocation.getArgument(0);
            setField(saved, "id", 812L);
            return saved;
        });
        when(dealerLedgerService.currentBalance(812L)).thenReturn(BigDecimal.ZERO);

        DealerDto dto = salesService.createDealer(new com.bigbrightpaints.erp.modules.sales.dto.DealerRequest(
                "Dealer Link",
                "DLR",
                "dealer@example.com",
                "555-1010",
                new BigDecimal("5000")
        ));

        assertEquals(812L, dto.id());
        assertEquals("DLR", dto.code());
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertEquals("AR-DLR-1", accountCaptor.getValue().getCode());
        assertEquals("Dealer Link Receivable", accountCaptor.getValue().getName());
        assertEquals(arControl, accountCaptor.getValue().getParent());
    }

    @Test
    void updateDealerSyncsReceivableAccountCodeAndName() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Old Dealer");
        dealer.setCode("OLD");
        setField(dealer, "id", 813L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-OLD-1");
        receivable.setName("Old Dealer Receivable");
        dealer.setReceivableAccount(receivable);

        when(companyEntityLookup.requireDealer(company, 813L)).thenReturn(dealer);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-NEW-1")).thenReturn(Optional.empty());
        when(dealerLedgerService.currentBalance(813L)).thenReturn(BigDecimal.ZERO);

        DealerDto dto = salesService.updateDealer(813L, new com.bigbrightpaints.erp.modules.sales.dto.DealerRequest(
                "New Dealer",
                "NEW",
                "new@example.com",
                "555-2020",
                new BigDecimal("7200")
        ));

        assertEquals("NEW", dto.code());
        assertEquals("AR-NEW-1", receivable.getCode());
        assertEquals("New Dealer Receivable", receivable.getName());
        verify(accountRepository).save(receivable);
    }

    @Test
    void updateDealerSkipsReceivableCodeUpdateWhenNewCodeAlreadyExists() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Same Name");
        dealer.setCode("OLD");
        setField(dealer, "id", 814L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-OLD-2");
        receivable.setName("Same Name Receivable");
        dealer.setReceivableAccount(receivable);

        when(companyEntityLookup.requireDealer(company, 814L)).thenReturn(dealer);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-NEW-2")).thenReturn(Optional.of(new Account()));
        when(dealerLedgerService.currentBalance(814L)).thenReturn(BigDecimal.ZERO);

        DealerDto dto = salesService.updateDealer(814L, new com.bigbrightpaints.erp.modules.sales.dto.DealerRequest(
                "Same Name",
                "NEW",
                "same@example.com",
                "555-3030",
                new BigDecimal("8100")
        ));

        assertEquals("NEW", dto.code());
        assertEquals("AR-OLD-2", receivable.getCode());
        assertEquals("Same Name Receivable", receivable.getName());
        verify(accountRepository, never()).save(receivable);
    }

    @Test
    void updateDealerSkipsReceivableSyncWhenNoLinkedAccount() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("No Account");
        dealer.setCode("NACC");
        setField(dealer, "id", 815L);
        dealer.setReceivableAccount(null);

        when(companyEntityLookup.requireDealer(company, 815L)).thenReturn(dealer);
        when(dealerLedgerService.currentBalance(815L)).thenReturn(BigDecimal.ZERO);

        DealerDto dto = salesService.updateDealer(815L, new com.bigbrightpaints.erp.modules.sales.dto.DealerRequest(
                "No Account Updated",
                "NACC2",
                "none@example.com",
                "555-4040",
                new BigDecimal("6100")
        ));

        assertEquals("NACC2", dto.code());
        verifyNoInteractions(accountRepository);
    }

    @Test
    void deleteDealerMarksDealerInactiveAndDeactivatesReceivableAccount() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        setField(dealer, "id", 816L);
        dealer.setStatus("ACTIVE");

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-DEL");
        receivable.setActive(true);
        dealer.setReceivableAccount(receivable);

        when(companyEntityLookup.requireDealer(company, 816L)).thenReturn(dealer);

        salesService.deleteDealer(816L);

        assertEquals("INACTIVE", dealer.getStatus());
        assertFalse(receivable.isActive());
        verify(accountRepository).save(receivable);
        verify(dealerRepository).save(dealer);
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

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.createOrder(request));
        assertEquals(ErrorCode.VALIDATION_INVALID_STATE, ex.getErrorCode());
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

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.createOrder(request));
        assertEquals(ErrorCode.VALIDATION_INVALID_STATE, ex.getErrorCode());
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
    void confirmDispatchIncludesShortagesWhenReservationStillProducesNoSlip() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(new BigDecimal("100.00"));

        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of(), List.of());
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(new InventoryReservationResult(
                null,
                List.of(new InventoryShortage("FG-1", BigDecimal.ONE, "Primer"))));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(null, 10L, List.of(), null, "admin", Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.VALIDATION_INVALID_REFERENCE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Packing slip not found for order 10"));
        assertTrue(ex.getDetails().containsKey("shortages"));
    }

    @Test
    void confirmDispatchRejectsNoSlipFallbackForDispatchedOrder() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 10L);
        order.setCompany(company);
        order.setStatus("DISPATCHED");

        when(companyEntityLookup.requireSalesOrder(company, 10L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 10L)).thenReturn(List.of());

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(null, 10L, List.of(), null, "admin", Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("DISPATCHED", ex.getDetails().get("currentStatus"));
        verify(finishedGoodsService, never()).reserveForOrder(any(SalesOrder.class));
        verifyNoInteractions(dealerRepository);
    }

    @Test
    void confirmDispatchRejectsNoSlipFallbackForDraftOrder() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 11L);
        order.setCompany(company);
        order.setStatus("DRAFT");

        when(companyEntityLookup.requireSalesOrder(company, 11L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 11L)).thenReturn(List.of());

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(null, 11L, List.of(), null, "admin", Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("DRAFT", ex.getDetails().get("currentStatus"));
        verify(finishedGoodsService, never()).reserveForOrder(any(SalesOrder.class));
        verifyNoInteractions(dealerRepository);
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
    void confirmDispatchAllowsAdminOverrideCreditLimitWithApprovedRequest() {
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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(801L),
                eq(company),
                eq(dealer),
                eq(slip),
                eq(order),
                any()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(),
                null,
                "admin",
                Boolean.TRUE,
                "Approved credit exception",
                801L);
        DispatchConfirmResponse response = salesService.confirmDispatch(request);

        assertEquals(55L, response.packingSlipId());
        assertEquals(10L, response.salesOrderId());
        assertEquals(777L, response.finalInvoiceId());
        assertTrue(response.gstBreakdown().taxableAmount().compareTo(new BigDecimal("200.00")) == 0);
        assertTrue(response.gstBreakdown().cgst().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(response.gstBreakdown().sgst().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(response.gstBreakdown().igst().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(response.gstBreakdown().totalTax().compareTo(BigDecimal.ZERO) == 0);
        verify(companyAccountingSettingsService, never()).requireTaxAccounts();
        verify(creditLimitOverrideService).isOverrideApproved(eq(801L), eq(company), eq(dealer), eq(slip), eq(order), any());
    }

    @Test
    void confirmDispatchFailsWhenExceptionPresentWithoutOverrideRequestId() {
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

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(
                        55L,
                        null,
                        List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                        null,
                        "admin",
                        Boolean.FALSE,
                        "Discount exception requires approval",
                        null)));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("overrideRequestId"));
    }

    @Test
    void confirmDispatchFailsWhenOverrideRequestIsNotApproved() {
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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(900L),
                eq(company),
                eq(dealer),
                eq(slip),
                eq(order),
                any()
        )).thenReturn(false);

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(
                        55L,
                        null,
                        List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                        null,
                        "admin",
                        Boolean.FALSE,
                        "Discount exception requires approval",
                        900L)));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("approved maker-checker override request"));
    }

    @Test
    void confirmDispatchFailsWhenAdminCreditOverrideMissingReason() {
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

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(
                        55L,
                        null,
                        List.of(),
                        null,
                        "admin",
                        Boolean.TRUE,
                        null,
                        901L)));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("adminOverrideCreditLimit"));
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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(802L),
                eq(company),
                eq(dealer),
                eq(slip),
                eq(order),
                any()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.FALSE,
                "Discount override for test",
                802L);
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
    void confirmDispatchFailsClosedWhenTaxableLineMissingTaxAccount() {
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

        SalesOrderItem item = new SalesOrderItem();
        setField(item, "id", 1L);
        item.setSalesOrder(order);
        item.setProductCode("SKU-TAX");
        item.setDescription("Taxed item");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setGstRate(new BigDecimal("18.0000"));
        order.getItems().add(item);

        FinishedGood finishedGood = buildFinishedGood("SKU-TAX");
        finishedGood.setCurrentStock(BigDecimal.ONE);
        finishedGood.setRevenueAccountId(3L);
        finishedGood.setTaxAccountId(null);

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

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> salesService.confirmDispatch(new DispatchConfirmRequest(
                        55L, null, List.of(), null, "admin", Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.VALIDATION_INVALID_REFERENCE, ex.getErrorCode());
        verify(companyAccountingSettingsService, never()).requireTaxAccounts();
        verify(accountingFacade, never()).postSalesJournal(
                anyLong(),
                anyString(),
                any(),
                anyString(),
                anyMap(),
                anyMap(),
                anyMap(),
                any(),
                anyString());
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
        order.setTraceId("TRACE-CASH-10");
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
                new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.FALSE, null, null));

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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(803L),
                eq(company),
                eq(dealer),
                eq(slip),
                eq(order),
                any()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10.00"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay with approved prior override",
                803L);

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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(804L),
                eq(company),
                eq(dealer),
                eq(slip),
                eq(order),
                any()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10.00"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay with approved prior override",
                804L);

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
        order.setTraceId("TRACE-FASTPATH-805");

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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(805L),
                eq(company),
                isNull(),
                eq(slip),
                eq(order),
                isNull()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override in fast path",
                805L);

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
        assertEquals("DISCOUNT_OVERRIDE", metadata.get("dispatchOverrideReasonCode"));
        assertEquals("805", metadata.get("overrideRequestId"));
        assertEquals("TRACE-FASTPATH-805", metadata.get("traceId"));
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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(806L),
                eq(company),
                isNull(),
                eq(slip),
                eq(order),
                isNull()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with pre-existing COGS reference",
                806L);

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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(807L),
                eq(company),
                isNull(),
                eq(slip),
                eq(order),
                isNull()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with multi-slip order",
                807L);

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
        when(creditLimitOverrideService.isOverrideApproved(
                eq(808L),
                eq(company),
                isNull(),
                eq(slip),
                eq(order),
                isNull()
        )).thenReturn(true);

        DispatchConfirmRequest request = new DispatchConfirmRequest(
                55L,
                null,
                List.of(new DispatchConfirmRequest.DispatchLine(99L, null, BigDecimal.ONE, null, new BigDecimal("10"), null, null, null)),
                null,
                "admin",
                Boolean.TRUE,
                "Replay override with cancelled secondary slip",
                808L);

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
                new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.FALSE, null, null));

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

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.FALSE, null, null);
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

        DispatchConfirmRequest request = new DispatchConfirmRequest(55L, null, List.of(), null, "admin", Boolean.FALSE, null, null);
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
    void createOrderCreditLimitIncludesPendingOrderExposureProjection() {
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
        when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(eq(company), eq(dealer), any(), eq(null)))
                .thenReturn(BigDecimal.valueOf(450));

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

        CreditLimitExceededException ex = assertThrows(CreditLimitExceededException.class,
                () -> salesService.createOrder(request));

        assertEquals(BigDecimal.valueOf(450), ex.getDetails().get("pendingOrderExposure"));
        assertEquals(BigDecimal.valueOf(850), ex.getDetails().get("currentExposure"));
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
    void createOrderCashPaymentModeSkipsDealerCreditLimit() {
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

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals("RESERVED", dto.status());
        assertEquals("CASH", dto.paymentMode());
        verify(dealerLedgerService, never()).currentBalance(420L);
        verify(finishedGoodsService, never()).reserveForOrder(any(SalesOrder.class));
    }

    @Test
    void updateOrderCashPaymentModeSkipsDealerCreditLimit() {
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

        SalesOrderDto dto = salesService.updateOrder(4300L, request);

        assertEquals("CASH", dto.paymentMode());
        verify(dealerLedgerService, never()).currentBalance(430L);
    }

    @Test
    void updateOrderPreservesExistingPaymentModeWhenRequestOmitsIt() {
        setupProduct("SKU3-UPD-KEEP", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-UPD-KEEP");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-UPD-KEEP"))
                .thenReturn(Optional.of(finishedGood));

        Dealer dealer = dealerWithCreditLimit(431L, BigDecimal.valueOf(1000));
        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 4303L);
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setStatus("BOOKED");
        existing.setPaymentMode("CASH");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(200));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(200));

        when(companyEntityLookup.requireSalesOrder(company, 4303L)).thenReturn(existing);
        when(dealerRepository.lockByCompanyAndId(company, dealer.getId())).thenReturn(Optional.of(dealer));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 4303L)).thenReturn(List.of());

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3-UPD-KEEP", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null,
                null,
                null,
                null);

        SalesOrderDto dto = salesService.updateOrder(4303L, request);

        assertEquals("CASH", dto.paymentMode());
        verify(dealerLedgerService, never()).currentBalance(431L);
    }

    @Test
    void updateOrderMovesReservedOrdersBackToPendingProductionWhenShortageAppears() {
        setupProduct("SKU-UPD-SHORT", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU-UPD-SHORT");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-UPD-SHORT"))
                .thenReturn(Optional.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood)).thenReturn(List.of());

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 4301L);
        existing.setCompany(company);
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));

        when(companyEntityLookup.requireSalesOrder(company, 4301L)).thenReturn(existing);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 4301L)).thenReturn(List.of());
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 4301L)).thenReturn(List.of());
        when(companyClock.today(company)).thenReturn(java.time.LocalDate.of(2026, 3, 9));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU-UPD-SHORT", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "NONE",
                null,
                null,
                null,
                "CASH");

        SalesOrderDto dto = salesService.updateOrder(4301L, request);

        assertEquals("PENDING_PRODUCTION", dto.status());
        verify(finishedGoodsService, never()).releaseReservationsForOrder(4301L);
        verify(finishedGoodsService, never()).reserveForOrder(existing);
        verify(factoryTaskRepository).saveAll(any());
    }

    @Test
    void updateOrderReturnsPendingProductionOrdersToReservedWhenShortageClears() {
        setupProduct("SKU-UPD-CLEAR", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU-UPD-CLEAR");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-UPD-CLEAR"))
                .thenReturn(Optional.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(batch(finishedGood, BigDecimal.ONE)));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 4302L);
        existing.setCompany(company);
        existing.setStatus("PENDING_PRODUCTION");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));

        when(companyEntityLookup.requireSalesOrder(company, 4302L)).thenReturn(existing);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 4302L)).thenReturn(List.of());
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 4302L)).thenReturn(List.of());

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU-UPD-CLEAR", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "NONE",
                null,
                null,
                null,
                "CASH");

        SalesOrderDto dto = salesService.updateOrder(4302L, request);

        assertEquals("RESERVED", dto.status());
        verify(finishedGoodsService, never()).reserveForOrder(existing);
        verify(finishedGoodsService, never()).releaseReservationsForOrder(4302L);
    }

    @Test
    void createOrderHybridPaymentModeStillEnforcesDealerCreditLimit() {
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
                "HYBRID");

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
    void createOrderIdempotentRetry_missingStoredSignatureUsesRequestPaymentMode() {
        setupProduct("SKU3-IDEMP", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-IDEMP");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-IDEMP"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-IDEMP-CASH");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 9901L);
        existing.setCompany(company);
        existing.setOrderNumber("SO-IDEMP-CASH");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));
        existing.setIdempotencyHash(null);
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 9902L);
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

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, "SO-IDEMP-CASH-KEY"))
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
                "SO-IDEMP-CASH-KEY",
                "CASH");

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        verify(salesOrderRepository).save(existing);
        assertEquals(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||CASH|SKU3-IDEMP:1:100:0"),
                existing.getIdempotencyHash());
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
    void createOrderIdempotentRetry_acceptsStoredLegacySplitSignature() {
        setupProduct("SKU3-IDEMP", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3-IDEMP");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3-IDEMP"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-IDEMP-SPLIT");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 903L);
        existing.setCompany(company);
        existing.setOrderNumber("SO-IDEMP-SPLIT");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));
        existing.setPaymentMode("HYBRID");
        existing.setIdempotencyHash(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||SPLIT|SKU3-IDEMP:1:100:0"));
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 913L);
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

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, "SO-IDEMP-SPLIT-KEY"))
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
                "SO-IDEMP-SPLIT-KEY",
                "SPLIT");

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        verify(salesOrderRepository).save(existing);
        assertEquals(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||HYBRID|SKU3-IDEMP:1:100:0"), existing.getIdempotencyHash());
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
    void createOrderAutoIdempotencyRetry_acceptsLegacySplitKeyShape() {
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
                "SPLIT");
        String canonicalKey = request.resolveIdempotencyKey();
        String legacySplitKey = request.resolveLegacySplitReplayIdempotencyKey();
        assertTrue(!canonicalKey.equals(legacySplitKey));

        SalesOrder existing = new SalesOrder();
        setField(existing, "id", 904L);
        existing.setCompany(company);
        existing.setOrderNumber("SO-IDEMP-AUTO-SPLIT");
        existing.setStatus("RESERVED");
        existing.setCurrency("INR");
        existing.setGstTreatment("NONE");
        existing.setGstInclusive(false);
        existing.setGstRate(BigDecimal.ZERO);
        existing.setSubtotalAmount(BigDecimal.valueOf(100));
        existing.setGstTotal(BigDecimal.ZERO);
        existing.setGstRoundingAdjustment(BigDecimal.ZERO);
        existing.setTotalAmount(BigDecimal.valueOf(100));
        existing.setPaymentMode("HYBRID");
        existing.setIdempotencyHash(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||SPLIT|SKU3-IDEMP:1:100:0"));
        SalesOrderItem existingItem = new SalesOrderItem();
        setField(existingItem, "id", 914L);
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

        when(salesOrderRepository.findByCompanyAndIdempotencyKey(company, legacySplitKey))
                .thenReturn(Optional.of(existing));
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrderDto dto = salesService.createOrder(request);

        assertEquals(existing.getId(), dto.id());
        verify(salesOrderRepository, never()).findByCompanyAndIdempotencyKey(company, canonicalKey);
        assertEquals(DigestUtils.sha256Hex("null|100|INR|NONE|false|0||HYBRID|SKU3-IDEMP:1:100:0"), existing.getIdempotencyHash());
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
    void promotionRequestLegacyConstructorDefaultsImageUrlToNull() {
        PromotionRequest request = new PromotionRequest(
                "Campaign",
                "Description",
                "PERCENTAGE",
                new BigDecimal("10.00"),
                java.time.LocalDate.of(2026, 5, 1),
                java.time.LocalDate.of(2026, 5, 31),
                "ACTIVE"
        );

        assertEquals("Campaign", request.name());
        assertNull(request.imageUrl());
    }

    @Test
    void promotionDtoLegacyConstructorDefaultsImageUrlToNull() {
        PromotionDto dto = new PromotionDto(
                901L,
                UUID.randomUUID(),
                "Campaign",
                "Description",
                "PERCENTAGE",
                new BigDecimal("10.00"),
                java.time.LocalDate.of(2026, 5, 1),
                java.time.LocalDate.of(2026, 5, 31),
                "ACTIVE"
        );

        assertEquals("Campaign", dto.name());
        assertNull(dto.imageUrl());
    }

    @Test
    void createPromotionMapsImageUrlFromRequestIntoEntityAndDto() {
        UUID publicId = UUID.randomUUID();
        when(promotionRepository.save(any(Promotion.class))).thenAnswer(invocation -> {
            Promotion entity = invocation.getArgument(0);
            setField(entity, "id", 411L);
            setField(entity, "publicId", publicId);
            return entity;
        });

        PromotionRequest request = new PromotionRequest(
                "Summer Launch",
                "Doorstep campaign",
                "https://cdn.example.com/promotions/summer-launch.png",
                "PERCENTAGE",
                new BigDecimal("15.00"),
                java.time.LocalDate.of(2026, 3, 1),
                java.time.LocalDate.of(2026, 3, 31),
                "ACTIVE"
        );

        PromotionDto dto = salesService.createPromotion(request);

        ArgumentCaptor<Promotion> promotionCaptor = ArgumentCaptor.forClass(Promotion.class);
        verify(promotionRepository).save(promotionCaptor.capture());
        Promotion saved = promotionCaptor.getValue();

        assertEquals(company, saved.getCompany());
        assertEquals(request.name(), saved.getName());
        assertEquals(request.description(), saved.getDescription());
        assertEquals(request.imageUrl(), saved.getImageUrl());
        assertEquals(request.discountType(), saved.getDiscountType());
        assertEquals(request.discountValue(), saved.getDiscountValue());
        assertEquals(request.startDate(), saved.getStartDate());
        assertEquals(request.endDate(), saved.getEndDate());
        assertEquals(request.status(), saved.getStatus());

        assertEquals(411L, dto.id());
        assertEquals(publicId, dto.publicId());
        assertEquals(request.name(), dto.name());
        assertEquals(request.description(), dto.description());
        assertEquals(request.imageUrl(), dto.imageUrl());
        assertEquals(request.discountType(), dto.discountType());
        assertEquals(request.discountValue(), dto.discountValue());
        assertEquals(request.startDate(), dto.startDate());
        assertEquals(request.endDate(), dto.endDate());
        assertEquals(request.status(), dto.status());
    }

    @Test
    void updatePromotionSupportsNullImageUrlThroughEntityAndDto() {
        Promotion existing = new Promotion();
        existing.setCompany(company);
        existing.setName("Old Campaign");
        existing.setDescription("Old description");
        existing.setImageUrl("https://cdn.example.com/promotions/legacy.png");
        existing.setDiscountType("PERCENTAGE");
        existing.setDiscountValue(new BigDecimal("5.00"));
        existing.setStartDate(java.time.LocalDate.of(2026, 1, 1));
        existing.setEndDate(java.time.LocalDate.of(2026, 1, 31));
        existing.setStatus("DRAFT");
        UUID publicId = UUID.randomUUID();
        setField(existing, "id", 412L);
        setField(existing, "publicId", publicId);

        when(companyEntityLookup.requirePromotion(company, 412L)).thenReturn(existing);

        PromotionRequest request = new PromotionRequest(
                "Updated Campaign",
                "Updated description",
                null,
                "FLAT",
                new BigDecimal("100.00"),
                java.time.LocalDate.of(2026, 4, 1),
                java.time.LocalDate.of(2026, 4, 30),
                null
        );

        PromotionDto dto = salesService.updatePromotion(412L, request);

        assertEquals(request.name(), existing.getName());
        assertEquals(request.description(), existing.getDescription());
        assertNull(existing.getImageUrl());
        assertEquals(request.discountType(), existing.getDiscountType());
        assertEquals(request.discountValue(), existing.getDiscountValue());
        assertEquals(request.startDate(), existing.getStartDate());
        assertEquals(request.endDate(), existing.getEndDate());
        assertEquals("DRAFT", existing.getStatus());

        assertEquals(412L, dto.id());
        assertEquals(publicId, dto.publicId());
        assertEquals(request.name(), dto.name());
        assertEquals(request.description(), dto.description());
        assertNull(dto.imageUrl());
        assertEquals(request.discountType(), dto.discountType());
        assertEquals(request.discountValue(), dto.discountValue());
        assertEquals(request.startDate(), dto.startDate());
        assertEquals(request.endDate(), dto.endDate());
        assertEquals("DRAFT", dto.status());
        verify(promotionRepository, never()).save(any(Promotion.class));
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
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg1)).thenReturn(List.of());
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg2))
                .thenReturn(List.of(batch(fg2, BigDecimal.ONE)));

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
        verify(finishedGoodsService, never()).reserveForOrder(any());
        verify(factoryTaskRepository).saveAll(any());
    }

    @Test
    void createOrderTreatsMissingFinishedGoodAsProductionShortage() {
        SalesProformaBoundaryService boundaryService = new SalesProformaBoundaryService(
                dealerRepository,
                dealerLedgerService,
                salesOrderRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                factoryTaskRepository,
                companyClock);

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("SO-401");
        setField(order, "id", 902L);

        SalesOrderItem item = new SalesOrderItem();
        item.setProductCode("SKU-MISSING");
        item.setDescription("Missing SKU");
        item.setQuantity(BigDecimal.ONE);
        item.setSalesOrder(order);
        order.getItems().add(item);

        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-MISSING")).thenReturn(Optional.empty());
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 902L)).thenReturn(List.of());
        when(companyClock.today(company)).thenReturn(java.time.LocalDate.of(2026, 3, 9));

        SalesProformaBoundaryService.CommercialAssessment assessment = boundaryService.assessCommercialAvailability(company, order);

        assertEquals("PENDING_PRODUCTION", assessment.commercialStatus());
        assertEquals(1, assessment.shortages().size());
        assertEquals("SKU-MISSING", assessment.shortages().getFirst().productCode());
        assertEquals(BigDecimal.ONE, assessment.shortages().getFirst().shortageQuantity());
        ArgumentCaptor<List<FactoryTask>> tasksCaptor = ArgumentCaptor.forClass(List.class);
        verify(factoryTaskRepository).saveAll(tasksCaptor.capture());
        assertEquals(1, tasksCaptor.getValue().size());
        assertEquals("Production requirement: SKU-MISSING", tasksCaptor.getValue().getFirst().getTitle());
        assertTrue(tasksCaptor.getValue().getFirst().getDescription().contains("Missing SKU"));
    }

    @Test
    void confirmOrderRejectsWhenProductionRequirementsRemainWithoutReservedSlip() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setPaymentMode("CASH");
        setField(order, "id", 904L);

        FactoryTask requirement = new FactoryTask();
        requirement.setCompany(company);
        requirement.setTitle("Production requirement: SKU-OPEN");
        requirement.setStatus("PENDING");
        requirement.setSalesOrderId(904L);

        when(companyEntityLookup.requireSalesOrder(company, 904L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 904L)).thenReturn(List.of());
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 904L)).thenReturn(List.of(requirement));

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmOrder(904L));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("production is still required"));
        assertEquals(List.of("SKU-OPEN"), ex.getDetails().get("productionRequirementSkus"));
    }

    @Test
    void confirmOrderRejectsWhenSlipLinesHaveNoReservedQuantity() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("READY_TO_SHIP");
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setPaymentMode("CASH");
        setField(order, "id", 905L);

        FinishedGood finishedGood = buildFinishedGood("SKU-UNRESERVED");
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("BATCH-UNRESERVED");

        PackagingSlipLine line = new PackagingSlipLine();
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(new BigDecimal("5.00"));
        line.setBackorderQuantity(new BigDecimal("5.00"));
        line.setShippedQuantity(BigDecimal.ZERO);
        line.setQuantity(new BigDecimal("5.00"));

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setStatus("RESERVED");
        slip.getLines().add(line);

        when(companyEntityLookup.requireSalesOrder(company, 905L)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 905L)).thenReturn(List.of(slip));

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.confirmOrder(905L));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("no stock is reserved"));
        assertEquals(List.of("SKU-UNRESERVED"), ex.getDetails().get("unreservedSkus"));
    }

    @Test
    void cancelOrderReleasesReservations() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("CONFIRMED");
        setField(order, "id", 42L);
        when(companyEntityLookup.requireSalesOrder(company, 42L)).thenReturn(order);

        SalesOrderDto dto = salesService.cancelOrder(42L, "CUSTOMER_REQUEST|Customer cancelled");

        assertEquals("CANCELLED", dto.status());
        verify(finishedGoodsService).releaseReservationsForOrder(42L);
    }

    @Test
    void cancelOrderRejectsWhenAlreadyDispatched() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("DISPATCHED");
        setField(order, "id", 43L);
        when(companyEntityLookup.requireSalesOrder(company, 43L)).thenReturn(order);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> salesService.cancelOrder(43L, "CUSTOMER_REQUEST|Too late"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(finishedGoodsService, never()).releaseReservationsForOrder(anyLong());
    }

    @Test
    void searchOrdersAppliesFiltersAndReturnsPaginatedResult() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer Search");
        setField(dealer, "id", 777L);

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-SEARCH-1");
        order.setStatus("BOOKED");
        setField(order, "id", 778L);

        SalesOrderSearchFilters filters = new SalesOrderSearchFilters(
                "draft",
                777L,
                "SO-SEARCH",
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-31T23:59:59Z"),
                1,
                10);

        when(companyEntityLookup.requireDealer(company, 777L)).thenReturn(dealer);
        when(salesOrderRepository.searchIdsByCompany(
                eq(company),
                eq("DRAFT"),
                eq(dealer),
                eq("SO-SEARCH"),
                eq(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                eq(java.time.Instant.parse("2026-01-31T23:59:59Z")),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(778L), PageRequest.of(1, 10), 1));
        when(salesOrderRepository.findByCompanyAndIdInOrderByCreatedAtDescIdDesc(company, List.of(778L)))
                .thenReturn(List.of(order));

        PageResponse<SalesOrderDto> response = salesService.searchOrders(filters);

        assertEquals(1, response.content().size());
        assertEquals("DRAFT", response.content().getFirst().status());
        assertEquals(1, response.page());
        assertEquals(10, response.size());
        assertEquals(2, response.totalPages());
        assertEquals(11L, response.totalElements());
    }

    @Test
    void orderTimelineReturnsChronologicalHistory() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setStatus("CONFIRMED");
        setField(order, "id", 911L);

        SalesOrderStatusHistory created = new SalesOrderStatusHistory();
        created.setCompany(company);
        created.setSalesOrder(order);
        created.setFromStatus(null);
        created.setToStatus("DRAFT");
        created.setReasonCode("ORDER_CREATED");
        created.setReason("Order created");
        created.setChangedBy("alice");
        created.setChangedAt(java.time.Instant.parse("2026-01-01T10:00:00Z"));
        setField(created, "id", 1L);

        SalesOrderStatusHistory confirmed = new SalesOrderStatusHistory();
        confirmed.setCompany(company);
        confirmed.setSalesOrder(order);
        confirmed.setFromStatus("DRAFT");
        confirmed.setToStatus("CONFIRMED");
        confirmed.setReasonCode("ORDER_CONFIRMED");
        confirmed.setReason("Order confirmed");
        confirmed.setChangedBy("bob");
        confirmed.setChangedAt(java.time.Instant.parse("2026-01-01T10:05:00Z"));
        setField(confirmed, "id", 2L);

        when(companyEntityLookup.requireSalesOrder(company, 911L)).thenReturn(order);
        when(salesOrderStatusHistoryRepository.findTimeline(company, order)).thenReturn(List.of(created, confirmed));

        List<SalesOrderStatusHistoryDto> timeline = salesService.orderTimeline(911L);

        assertEquals(2, timeline.size());
        assertEquals("DRAFT", timeline.get(0).toStatus());
        assertEquals("CONFIRMED", timeline.get(1).toStatus());
        assertEquals("ORDER_CONFIRMED", timeline.get(1).reasonCode());
        assertEquals("bob", timeline.get(1).changedBy());
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
        lenient().when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(batch(finishedGood, new BigDecimal("1000"))));
        return finishedGood;
    }

    private Dealer dealerWithCreditLimit(long id, BigDecimal limit) {
        Dealer dealer = new Dealer();
        Account receivableAccount = new Account();
        receivableAccount.setActive(true);
        dealer.setCompany(company);
        dealer.setName("Dealer");
        dealer.setCreditLimit(limit);
        dealer.setReceivableAccount(receivableAccount);
        setField(dealer, "id", id);
        return dealer;
    }

    private FinishedGoodBatch batch(FinishedGood finishedGood, BigDecimal quantityAvailable) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setQuantityAvailable(quantityAvailable);
        batch.setQuantityTotal(quantityAvailable);
        batch.setUnitCost(BigDecimal.ONE);
        return batch;
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
