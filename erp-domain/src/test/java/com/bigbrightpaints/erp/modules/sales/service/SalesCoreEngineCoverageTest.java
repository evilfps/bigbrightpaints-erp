package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.sales.service.OrderNumberService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class SalesCoreEngineCoverageTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private DealerRepository dealerRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderStatusHistoryRepository salesOrderStatusHistoryRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private SalesTargetRepository salesTargetRepository;
    @Mock private CreditRequestRepository creditRequestRepository;
    @Mock private OrderNumberService orderNumberService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductionProductRepository productionProductRepository;
    @Mock private DealerLedgerService dealerLedgerService;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private PackagingSlipRepository packagingSlipRepository;
    @Mock private FinishedGoodsService finishedGoodsService;
    @Mock private AccountingService accountingService;
    @Mock private AccountingFacade accountingFacade;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private InvoiceNumberService invoiceNumberService;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private FactoryTaskRepository factoryTaskRepository;
    @Mock private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock private GstService gstService;
    @Mock private CreditLimitOverrideService creditLimitOverrideService;
    @Mock private AuditService auditService;
    @Mock private CompanyClock companyClock;
    @Mock private PlatformTransactionManager transactionManager;

    private SalesCoreEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SalesCoreEngine(
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
                null);
    }

    @Test
    void syncFactoryDispatchReadiness_reassessesAvailabilityAfterReleasingExistingReservations() throws Exception {
        Method method = SalesCoreEngine.class.getDeclaredMethod(
                "syncFactoryDispatchReadiness",
                Company.class,
                SalesOrder.class,
                SalesProformaBoundaryService.CommercialAssessment.class);
        method.setAccessible(true);

        Company company = new Company();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        ReflectionTestUtils.setField(order, "id", 41L);
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(order);
        item.setProductCode("FG-1");
        item.setDescription("Primer");
        item.setQuantity(BigDecimal.ONE);
        order.getItems().add(item);

        FinishedGoodsService.InventoryShortage shortage =
                new FinishedGoodsService.InventoryShortage("FG-1", BigDecimal.ONE, "Primer");
        SalesProformaBoundaryService.CommercialAssessment shortageAssessment =
                new SalesProformaBoundaryService.CommercialAssessment("PENDING_PRODUCTION", List.of(shortage));
        SalesProformaBoundaryService.CommercialAssessment reservedAssessment =
                new SalesProformaBoundaryService.CommercialAssessment("RESERVED", List.of());

        com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood finishedGood =
                new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood();
        finishedGood.setProductCode("FG-1");
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-1"))
                .thenReturn(java.util.Optional.of(finishedGood));
        com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch batch =
                new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch();
        batch.setQuantityAvailable(BigDecimal.ONE);
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(batch));
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 41L)).thenReturn(List.of());
        when(finishedGoodsService.reserveForOrder(order))
                .thenReturn(new FinishedGoodsService.InventoryReservationResult(null, List.of()));

        assertThat(method.invoke(engine, company, order, shortageAssessment)).isEqualTo(reservedAssessment);
        verify(finishedGoodsService).releaseReservationsForOrder(41L);
        verify(finishedGoodsService).reserveForOrder(order);
    }

    @Test
    void syncFactoryDispatchReadiness_releasesReservationsWhenRebuiltReservationStillShowsShortage() throws Exception {
        Method method = SalesCoreEngine.class.getDeclaredMethod(
                "syncFactoryDispatchReadiness",
                Company.class,
                SalesOrder.class,
                SalesProformaBoundaryService.CommercialAssessment.class);
        method.setAccessible(true);

        Company company = new Company();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        ReflectionTestUtils.setField(order, "id", 41L);
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(order);
        item.setProductCode("FG-1");
        item.setDescription("Primer");
        item.setQuantity(BigDecimal.ONE);
        order.getItems().add(item);

        FinishedGoodsService.InventoryShortage shortage =
                new FinishedGoodsService.InventoryShortage("FG-1", BigDecimal.ONE, "Primer");
        SalesProformaBoundaryService.CommercialAssessment shortageAssessment =
                new SalesProformaBoundaryService.CommercialAssessment("PENDING_PRODUCTION", List.of(shortage));
        SalesProformaBoundaryService.CommercialAssessment reservedAssessment =
                new SalesProformaBoundaryService.CommercialAssessment("RESERVED", List.of());

        when(finishedGoodsService.reserveForOrder(order))
                .thenReturn(new FinishedGoodsService.InventoryReservationResult(
                        new PackagingSlipDto(
                                1L, null, 41L, "SO-41", null, "PS-41", "PENDING", null, null, null, null,
                                null, null, null, List.of(), null, null, null, null, null, null),
                        List.of(shortage)));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-1")).thenReturn(java.util.Optional.empty());
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 41L)).thenReturn(List.of());
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 9));

        assertThat(method.invoke(engine, company, order, reservedAssessment)).isEqualTo(shortageAssessment);
        verify(finishedGoodsService).reserveForOrder(order);
        verify(finishedGoodsService).releaseReservationsForOrder(41L);
    }

    @Test
    void syncFactoryDispatchReadiness_returnsExistingAssessmentWhenReservationSucceedsOrInputsMissing() throws Exception {
        Method method = SalesCoreEngine.class.getDeclaredMethod(
                "syncFactoryDispatchReadiness",
                Company.class,
                SalesOrder.class,
                SalesProformaBoundaryService.CommercialAssessment.class);
        method.setAccessible(true);

        Company company = new Company();
        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 42L);
        SalesProformaBoundaryService.CommercialAssessment reservedAssessment =
                new SalesProformaBoundaryService.CommercialAssessment("RESERVED", List.of());

        when(finishedGoodsService.reserveForOrder(order))
                .thenReturn(new FinishedGoodsService.InventoryReservationResult(null, List.of()));

        assertThat(method.invoke(engine, company, order, reservedAssessment)).isEqualTo(reservedAssessment);
        assertThat(method.invoke(engine, company, null, reservedAssessment)).isEqualTo(reservedAssessment);
        assertThat(method.invoke(engine, company, order, null)).isNull();
    }

    @Test
    void resolveExistingOrder_backfillsMissingPaymentModeWhenSignatureMatches() throws Exception {
        Method method = SalesCoreEngine.class.getDeclaredMethod(
                "resolveExistingOrder",
                SalesOrder.class,
                String.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 55L);
        order.setStatus("DRAFT");
        order.setOrderNumber("SO-55");
        order.setIdempotencyHash("sig-55");
        ReflectionTestUtils.setField(order, "paymentMode", null);
        order.setCurrency("INR");
        order.setGstTreatment("NONE");
        order.setGstInclusive(false);
        order.setNotes("notes");
        order.setTotalAmount(new BigDecimal("10.00"));

        Object dto = method.invoke(engine, order, "idem-55", "sig-55", "legacy-55", " cash ");

        assertThat(order.getPaymentMode()).isEqualTo("CASH");
        assertThat(dto).isNotNull();
        verify(salesOrderRepository).save(order);
    }

    @Test
    void buildSalesOrderSignature_usesOrderPaymentModeWhenRequestPaymentModeIsMissing() throws Exception {
        Method method = SalesCoreEngine.class.getDeclaredMethod(
                "buildSalesOrderSignature",
                SalesOrder.class,
                String.class,
                boolean.class);
        method.setAccessible(true);

        SalesOrder order = new SalesOrder();
        order.setPaymentMode("HYBRID");
        order.setCurrency("INR");
        order.setGstTreatment("NONE");
        order.setGstInclusive(false);
        order.setNotes("notes");
        order.setTotalAmount(new BigDecimal("12.00"));

        String fromOrderPaymentMode = (String) method.invoke(engine, order, null, false);
        String explicitHybrid = (String) method.invoke(engine, order, "HYBRID", false);

        assertThat(fromOrderPaymentMode).isEqualTo(explicitHybrid);
    }

    @Test
    void resolveLegacySplitReplayRequestSignature_returnsNullWhenCanonicalAlreadyUsesLegacyShape() throws Exception {
        Method legacySignatureMethod = SalesCoreEngine.class.getDeclaredMethod(
                "resolveLegacySplitReplayRequestSignature",
                com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest.class,
                String.class);
        legacySignatureMethod.setAccessible(true);
        Method buildSignatureMethod = SalesCoreEngine.class.getDeclaredMethod(
                "buildSalesOrderSignature",
                com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest.class,
                boolean.class,
                boolean.class);
        buildSignatureMethod.setAccessible(true);

        com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest request =
                new com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest(
                        101L,
                        new BigDecimal("100.00"),
                        "INR",
                        "notes",
                        List.of(new com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest(
                                "SKU-1",
                                "Primer",
                                BigDecimal.ONE,
                                new BigDecimal("100.00"),
                                BigDecimal.ZERO
                        )),
                        "NONE",
                        BigDecimal.ZERO,
                        Boolean.FALSE,
                        null,
                        "SPLIT"
                );

        String canonicalLegacyShape = (String) buildSignatureMethod.invoke(engine, request, false, true);

        assertThat(legacySignatureMethod.invoke(engine, request, canonicalLegacyShape)).isNull();
    }
}
