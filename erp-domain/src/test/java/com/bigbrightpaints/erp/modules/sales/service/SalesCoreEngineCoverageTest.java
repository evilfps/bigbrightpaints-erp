package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingComplianceAuditService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.DealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderSearchFilters;

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
  @Mock private CompanyScopedSalesLookupService salesLookupService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private PackagingSlipRepository packagingSlipRepository;
  @Mock private FinishedGoodsService finishedGoodsService;
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
  @Mock private EnterpriseAuditTrailService enterpriseAuditTrailService;

  private SalesCoreEngine engine;

  @BeforeEach
  void setUp() {
    engine =
        new SalesCoreEngine(
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
            salesLookupService,
            accountingLookupService,
            packagingSlipRepository,
            finishedGoodsService,
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
    ReflectionTestUtils.setField(
        engine, "enterpriseAuditTrailService", enterpriseAuditTrailService);
  }

  @Test
  void recordOrderStatusHistory_emitsSalesBusinessAuditEvent() {
    Company company = new Company();
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setStatus("DRAFT");
    order.setOrderNumber("SO-100");
    ReflectionTestUtils.setField(order, "id", 100L);
    when(companyClock.now(company)).thenReturn(Instant.parse("2026-04-07T16:30:00Z"));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        engine,
        "recordOrderStatusHistory",
        order,
        "DRAFT",
        "CONFIRMED",
        "ORDER_CONFIRMED",
        "Order confirmed",
        "alice@example.com");

    ArgumentCaptor<AuditActionEventCommand> commandCaptor =
        ArgumentCaptor.forClass(AuditActionEventCommand.class);
    verify(enterpriseAuditTrailService).recordBusinessEvent(commandCaptor.capture());
    AuditActionEventCommand command = commandCaptor.getValue();
    assertThat(command.module()).isEqualTo("SALES");
    assertThat(command.action()).isEqualTo("ORDER_CONFIRMED");
    assertThat(command.entityType()).isEqualTo("SALES_ORDER");
    assertThat(command.entityId()).isEqualTo("100");
    assertThat(command.referenceNumber()).isEqualTo("SO-100");
  }

  @Test
  @SuppressWarnings("unchecked")
  void normalizePendingDispatchLines_returnsProvidedLinesOrKeepsExplicitSlipRequestsUntouched()
      throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "normalizePendingDispatchLines",
            List.class,
            PackagingSlip.class,
            Long.class,
            FinishedGoodsService.InventoryReservationResult.class,
            Long.class);
    method.setAccessible(true);

    List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine> provided =
        List.of(
            new com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine(
                11L, 21L, BigDecimal.ONE, null, null, null, null, null));

    Object providedResult = method.invoke(engine, provided, null, null, null, 1L);
    Object explicitSlipResult =
        method.invoke(
            engine,
            null,
            null,
            55L,
            new FinishedGoodsService.InventoryReservationResult(null, List.of()),
            1L);

    assertThat(
            (List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine>)
                providedResult)
        .isEqualTo(provided);
    assertThat(explicitSlipResult).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void normalizePendingDispatchLines_synthesizesFullCoverageForAutoReservedSlip() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "normalizePendingDispatchLines",
            List.class,
            PackagingSlip.class,
            Long.class,
            FinishedGoodsService.InventoryReservationResult.class,
            Long.class);
    method.setAccessible(true);

    PackagingSlip slip = new PackagingSlip();
    ReflectionTestUtils.setField(slip, "id", 77L);
    PackagingSlipLine slipLine = new PackagingSlipLine();
    ReflectionTestUtils.setField(slipLine, "id", 701L);
    slipLine.setPackagingSlip(slip);
    slipLine.setQuantity(BigDecimal.TEN);
    slip.getLines().add(slipLine);

    List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine>
        synthesized =
            (List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine>)
                method.invoke(
                    engine,
                    null,
                    slip,
                    null,
                    new FinishedGoodsService.InventoryReservationResult(
                        new PackagingSlipDto(
                            77L,
                            null,
                            1L,
                            "SO-1",
                            null,
                            "PS-77",
                            "RESERVED",
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
                            null),
                        List.of()),
                    1L);

    assertThat(synthesized).hasSize(1);
    assertThat(synthesized.get(0).lineId()).isEqualTo(701L);
    assertThat(synthesized.get(0).shipQty()).isEqualByComparingTo("10");
  }

  @Test
  @SuppressWarnings("unchecked")
  void buildFullDispatchLines_andResolveDispatchLineQuantity_coverFallbackBranches()
      throws Exception {
    Method buildMethod =
        SalesCoreEngine.class.getDeclaredMethod("buildFullDispatchLines", PackagingSlip.class);
    buildMethod.setAccessible(true);
    Method qtyMethod =
        SalesCoreEngine.class.getDeclaredMethod(
            "resolveDispatchLineQuantity", PackagingSlipLine.class);
    qtyMethod.setAccessible(true);

    PackagingSlip slip = new PackagingSlip();

    PackagingSlipLine qtyLine = new PackagingSlipLine();
    ReflectionTestUtils.setField(qtyLine, "id", 801L);
    qtyLine.setPackagingSlip(slip);
    qtyLine.setQuantity(new BigDecimal("2"));
    FinishedGoodBatch qtyBatch = new FinishedGoodBatch();
    ReflectionTestUtils.setField(qtyBatch, "id", 901L);
    qtyLine.setFinishedGoodBatch(qtyBatch);
    slip.getLines().add(qtyLine);

    PackagingSlipLine orderedLine = new PackagingSlipLine();
    ReflectionTestUtils.setField(orderedLine, "id", 802L);
    orderedLine.setPackagingSlip(slip);
    orderedLine.setOrderedQuantity(new BigDecimal("3"));
    slip.getLines().add(orderedLine);

    PackagingSlipLine zeroLine = new PackagingSlipLine();
    ReflectionTestUtils.setField(zeroLine, "id", 803L);
    zeroLine.setPackagingSlip(slip);
    slip.getLines().add(zeroLine);

    PackagingSlipLine filteredLine = new PackagingSlipLine();
    filteredLine.setPackagingSlip(slip);
    filteredLine.setQuantity(new BigDecimal("4"));
    slip.getLines().add(filteredLine);
    slip.getLines().add(null);

    List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine> lines =
        (List<com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.DispatchLine>)
            buildMethod.invoke(engine, slip);

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0).batchId()).isEqualTo(901L);
    assertThat(lines.get(0).shipQty()).isEqualByComparingTo("2");
    assertThat(lines.get(1).shipQty()).isEqualByComparingTo("3");
    assertThat(lines.get(2).shipQty()).isEqualByComparingTo("0");
    assertThat((BigDecimal) qtyMethod.invoke(engine, new Object[] {null}))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void syncFactoryDispatchReadiness_reassessesAvailabilityAfterReleasingExistingReservations()
      throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
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
        new SalesProformaBoundaryService.CommercialAssessment(
            "PENDING_PRODUCTION", List.of(shortage));
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

    assertThat(method.invoke(engine, company, order, shortageAssessment))
        .isEqualTo(reservedAssessment);
    verify(finishedGoodsService).releaseReservationsForOrder(41L);
    verify(finishedGoodsService).reserveForOrder(order);
  }

  @Test
  void syncFactoryDispatchReadiness_releasesReservationsWhenRebuiltReservationStillShowsShortage()
      throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
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
        new SalesProformaBoundaryService.CommercialAssessment(
            "PENDING_PRODUCTION", List.of(shortage));
    SalesProformaBoundaryService.CommercialAssessment reservedAssessment =
        new SalesProformaBoundaryService.CommercialAssessment("RESERVED", List.of());

    when(finishedGoodsService.reserveForOrder(order))
        .thenReturn(
            new FinishedGoodsService.InventoryReservationResult(
                new PackagingSlipDto(
                    1L, null, 41L, "SO-41", null, "PS-41", "PENDING", null, null, null, null, null,
                    null, null, List.of(), null, null, null, null, null, null),
                List.of(shortage)));
    when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-1"))
        .thenReturn(java.util.Optional.empty());
    when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 41L)).thenReturn(List.of());
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 9));

    assertThat(method.invoke(engine, company, order, reservedAssessment))
        .isEqualTo(shortageAssessment);
    verify(finishedGoodsService).reserveForOrder(order);
    verify(finishedGoodsService).releaseReservationsForOrder(41L);
  }

  @Test
  void
      syncFactoryDispatchReadiness_returnsExistingAssessmentWhenReservationSucceedsOrInputsMissing()
          throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
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

    assertThat(method.invoke(engine, company, order, reservedAssessment))
        .isEqualTo(reservedAssessment);
    assertThat(method.invoke(engine, company, null, reservedAssessment))
        .isEqualTo(reservedAssessment);
    assertThat(method.invoke(engine, company, order, null)).isNull();
  }

  @Test
  void resolveExistingOrder_backfillsMissingPaymentModeWhenSignatureMatches() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
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
  void buildSalesOrderSignature_usesOrderPaymentModeWhenRequestPaymentModeIsMissing()
      throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "buildSalesOrderSignature", SalesOrder.class, String.class, boolean.class);
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
  void resolveLegacySplitReplayRequestSignature_returnsNullWhenCanonicalAlreadyUsesLegacyShape()
      throws Exception {
    Method legacySignatureMethod =
        SalesCoreEngine.class.getDeclaredMethod(
            "resolveLegacySplitReplayRequestSignature",
            com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest.class,
            String.class);
    legacySignatureMethod.setAccessible(true);
    Method buildSignatureMethod =
        SalesCoreEngine.class.getDeclaredMethod(
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
            List.of(
                new com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest(
                    "SKU-1", "Primer", BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO)),
            "NONE",
            BigDecimal.ZERO,
            Boolean.FALSE,
            null,
            "SPLIT");

    String canonicalLegacyShape =
        (String) buildSignatureMethod.invoke(engine, request, false, true);

    assertThat(legacySignatureMethod.invoke(engine, request, canonicalLegacyShape)).isNull();
  }

  @Test
  void acceptedRequestSignatures_ignoresBlankEntries_and_deduplicates() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod("acceptedRequestSignatures", String[].class);
    method.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> accepted =
        (List<String>)
            method.invoke(engine, (Object) new String[] {"sig-1", " ", "sig-1", null, "sig-2"});

    assertThat(accepted).containsExactly("sig-1", "sig-2");
  }

  @Test
  void signaturePaymentModeToken_defaultsBlank_and_preservesLegacySplitAlias() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "signaturePaymentModeToken", String.class, boolean.class);
    method.setAccessible(true);

    assertThat(method.invoke(engine, "   ", false)).isEqualTo("CREDIT");
    assertThat(method.invoke(engine, "split", true)).isEqualTo("SPLIT");
  }

  @Test
  void resolveLegacySplitReplayIdempotencyKey_returnsDistinctReplayKeyOnly() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "resolveLegacySplitReplayIdempotencyKey", SalesOrderRequest.class, String.class);
    method.setAccessible(true);

    SalesOrderRequest request = salesOrderRequest("SPLIT", null);
    String canonicalKey = request.resolveIdempotencyKey();

    assertThat(method.invoke(engine, request, canonicalKey))
        .isEqualTo(request.resolveLegacySplitReplayIdempotencyKey());
    assertThat(method.invoke(engine, request, request.resolveLegacySplitReplayIdempotencyKey()))
        .isNull();
  }

  @Test
  void createOrder_rejectsRetiredLegacyHeaderAtExecutionPath() {
    MockHttpServletRequest servletRequest =
        new MockHttpServletRequest("POST", "/api/v1/sales/orders");
    servletRequest.addHeader("X-Idempotency-Key", "legacy-order-key");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

    try {
      SalesOrderRequest request = salesOrderRequest("CREDIT", null);

      assertThatThrownBy(() -> engine.createOrder(request))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("X-Idempotency-Key is not supported for sales orders");
      verifyNoInteractions(companyContextService, salesOrderRepository);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  void
      resolveLegacySplitReplayRequestSignature_returnsDistinctReplaySignature_forLegacySplitRequest()
          throws Exception {
    Method replaySignatureMethod =
        SalesCoreEngine.class.getDeclaredMethod(
            "resolveLegacySplitReplayRequestSignature", SalesOrderRequest.class, String.class);
    replaySignatureMethod.setAccessible(true);
    Method buildSignatureMethod =
        SalesCoreEngine.class.getDeclaredMethod(
            "buildSalesOrderSignature", SalesOrderRequest.class, boolean.class, boolean.class);
    buildSignatureMethod.setAccessible(true);

    SalesOrderRequest request = salesOrderRequest("SPLIT", null);
    String canonicalSignature = (String) buildSignatureMethod.invoke(engine, request, false, false);
    String replaySignature =
        (String) replaySignatureMethod.invoke(engine, request, canonicalSignature);

    assertThat(replaySignature).isNotNull().isNotEqualTo(canonicalSignature);
    assertThat(
            replaySignatureMethod.invoke(
                engine, salesOrderRequest("CREDIT", null), canonicalSignature))
        .isNull();
  }

  @Test
  void syncFactoryDispatchReadiness_keepsDraftShortageAssessment_withoutReleasingReservations()
      throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "syncFactoryDispatchReadiness",
            Company.class,
            SalesOrder.class,
            SalesProformaBoundaryService.CommercialAssessment.class);
    method.setAccessible(true);

    Company company = new Company();
    SalesOrder order = new SalesOrder();
    ReflectionTestUtils.setField(order, "id", 77L);
    order.setStatus("DRAFT");
    FinishedGoodsService.InventoryShortage shortage =
        new FinishedGoodsService.InventoryShortage("FG-DRAFT", BigDecimal.ONE, "Primer");
    SalesProformaBoundaryService.CommercialAssessment shortageAssessment =
        new SalesProformaBoundaryService.CommercialAssessment(
            "PENDING_PRODUCTION", List.of(shortage));

    assertThat(method.invoke(engine, company, order, shortageAssessment))
        .isEqualTo(shortageAssessment);
    verify(finishedGoodsService, never()).releaseReservationsForOrder(77L);
    verify(finishedGoodsService, never()).reserveForOrder(order);
  }

  @Test
  void assertDispatchAutoReservationAllowed_enforcesStatusAndAccountingMarkers() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "assertDispatchAutoReservationAllowed", SalesOrder.class);
    method.setAccessible(true);

    SalesOrder blockedByStatus = new SalesOrder();
    ReflectionTestUtils.setField(blockedByStatus, "id", 88L);
    blockedByStatus.setStatus("DISPATCHED");

    assertThatThrownBy(() -> method.invoke(engine, blockedByStatus))
        .hasRootCauseInstanceOf(RuntimeException.class)
        .hasRootCauseMessage(
            "Cannot auto-create packing slip for dispatch from order status DISPATCHED");

    SalesOrder blockedByAccounting = new SalesOrder();
    ReflectionTestUtils.setField(blockedByAccounting, "id", 89L);
    blockedByAccounting.setStatus("CONFIRMED");
    blockedByAccounting.setFulfillmentInvoiceId(501L);

    assertThatThrownBy(() -> method.invoke(engine, blockedByAccounting))
        .hasRootCauseInstanceOf(RuntimeException.class)
        .hasRootCauseMessage(
            "Cannot auto-create packing slip for dispatch when order already has accounting"
                + " markers");

    SalesOrder allowed = new SalesOrder();
    ReflectionTestUtils.setField(allowed, "id", 90L);
    allowed.setStatus("READY_TO_SHIP");
    assertThat(method.invoke(engine, allowed)).isNull();
  }

  @Test
  void createDealer_createsReceivableAccountWhenMissing() {
    Company company = new Company();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(company, "DLR1"))
        .thenReturn(java.util.Optional.empty());
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-DLR1"))
        .thenReturn(java.util.Optional.empty());
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR"))
        .thenReturn(java.util.Optional.empty());
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(dealerRepository.save(org.mockito.ArgumentMatchers.any(Dealer.class)))
        .thenAnswer(
            invocation -> {
              Dealer dealer = invocation.getArgument(0);
              ReflectionTestUtils.setField(dealer, "id", 201L);
              return dealer;
            });
    when(dealerLedgerService.currentBalance(201L)).thenReturn(BigDecimal.ZERO);

    DealerDto dealer =
        engine.createDealer(
            new DealerRequest(
                "Dealer One",
                "DLR1",
                "dealer1@example.com",
                "9999999999",
                new BigDecimal("5000.00")));

    assertThat(dealer.id()).isEqualTo(201L);
    assertThat(dealer.code()).isEqualTo("DLR1");
    verify(accountRepository).save(org.mockito.ArgumentMatchers.any(Account.class));
  }

  @Test
  void createDealer_reactivatesInactiveReceivableAccount() {
    Company company = new Company();
    Dealer existing = new Dealer();
    existing.setCompany(company);
    existing.setName("Dealer Two");
    existing.setCode("DLR2");
    Account account = new Account();
    account.setActive(false);
    existing.setReceivableAccount(account);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(company, "DLR2"))
        .thenReturn(java.util.Optional.of(existing));
    when(accountRepository.save(account)).thenReturn(account);
    when(dealerRepository.save(existing)).thenReturn(existing);

    DealerDto dealer =
        engine.createDealer(
            new DealerRequest("Dealer Two", "DLR2", null, null, new BigDecimal("7000.00")));

    assertThat(account.isActive()).isTrue();
    assertThat(dealer.code()).isEqualTo("DLR2");
    verify(accountRepository).save(account);
  }

  @Test
  void updateDealer_syncsReceivableAccountCodeAndName_whenCodeChangesWithoutConflict() {
    Company company = new Company();
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setName("Old Dealer");
    dealer.setCode("OLD");
    Account account = new Account();
    account.setCode("AR-OLD-1");
    account.setName("Old Dealer Receivable");
    dealer.setReceivableAccount(account);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(salesLookupService.requireDealer(company, 301L)).thenReturn(dealer);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-NEW-1"))
        .thenReturn(java.util.Optional.empty());

    DealerDto updated =
        engine.updateDealer(
            301L, new DealerRequest("New Dealer", "NEW", null, null, new BigDecimal("9000.00")));

    assertThat(updated.name()).isEqualTo("New Dealer");
    assertThat(account.getCode()).isEqualTo("AR-NEW-1");
    assertThat(account.getName()).isEqualTo("New Dealer Receivable");
    verify(accountRepository).save(account);
  }

  @Test
  void deleteDealer_recordsComplianceAudit_forPreviouslyActiveReceivableAccount() {
    Company company = new Company();
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    Account account = new Account();
    account.setActive(true);
    dealer.setReceivableAccount(account);
    AccountingComplianceAuditService complianceAuditService =
        org.mockito.Mockito.mock(AccountingComplianceAuditService.class);
    ReflectionTestUtils.setField(
        engine, "accountingComplianceAuditService", complianceAuditService);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(salesLookupService.requireDealer(company, 302L)).thenReturn(dealer);
    when(accountRepository.save(account)).thenReturn(account);

    engine.deleteDealer(302L);

    assertThat(account.isActive()).isFalse();
    verify(complianceAuditService).recordAccountDeactivated(company, account, "Dealer deactivated");
    verify(dealerRepository).save(dealer);
  }

  @Test
  void normalizeGstAndStateCode_coverBlankValidAndInvalidBranches() throws Exception {
    Method gstMethod = SalesCoreEngine.class.getDeclaredMethod("normalizeGstNumber", String.class);
    gstMethod.setAccessible(true);
    Method stateMethod =
        SalesCoreEngine.class.getDeclaredMethod("normalizeStateCode", String.class);
    stateMethod.setAccessible(true);

    assertThat(gstMethod.invoke(engine, " ")).isNull();
    assertThat(gstMethod.invoke(engine, "29ABCDE1234F1Z5")).isEqualTo("29ABCDE1234F1Z5");
    assertThatThrownBy(() -> gstMethod.invoke(engine, "BADGST"))
        .hasRootCauseMessage("GST number must be a valid 15-character GSTIN");

    assertThat(stateMethod.invoke(engine, " ")).isNull();
    assertThat(stateMethod.invoke(engine, "ka")).isEqualTo("KA");
    assertThatThrownBy(() -> stateMethod.invoke(engine, "KAR"))
        .hasRootCauseMessage("State code must be exactly 2 characters");
  }

  @Test
  void listOrders_routesThroughPagedAndUnpagedDealerBranches() {
    Company company = new Company();
    Dealer dealer = new Dealer();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(salesLookupService.requireDealer(company, 401L)).thenReturn(dealer);
    when(salesOrderRepository.findIdsByCompanyOrderByCreatedAtDescIdDesc(
            company, PageRequest.of(0, 25)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
    when(salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer))
        .thenReturn(List.of());
    when(salesOrderRepository.findByCompanyAndDealerAndStatusOrderByCreatedAtDesc(
            company, dealer, "CONFIRMED"))
        .thenReturn(List.of());

    assertThat(engine.listOrders(null, 0, 25)).isEmpty();
    assertThat(engine.listOrders("", 401L)).isEmpty();
    assertThat(engine.listOrders("confirmed", 401L)).isEmpty();

    verify(salesOrderRepository)
        .findIdsByCompanyOrderByCreatedAtDescIdDesc(company, PageRequest.of(0, 25));
    verify(salesOrderRepository).findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer);
    verify(salesOrderRepository)
        .findByCompanyAndDealerAndStatusOrderByCreatedAtDesc(company, dealer, "CONFIRMED");
  }

  @Test
  void searchOrders_returnsEmptyPage_whenNoOrderIdsMatch() {
    Company company = new Company();
    Dealer dealer = new Dealer();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(salesLookupService.requireDealer(company, 402L)).thenReturn(dealer);
    when(salesOrderRepository.searchIdsByCompany(
            company, "CONFIRMED", dealer, "SO-1", null, null, PageRequest.of(0, 20)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    var page =
        engine.searchOrders(
            new SalesOrderSearchFilters("confirmed", 402L, "SO-1", null, null, 0, 20));

    assertThat(page.content()).isEmpty();
    assertThat(page.totalElements()).isZero();

    when(salesOrderRepository.searchIdsByCompany(
            company, null, null, null, null, null, PageRequest.of(1, 5)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

    var dealerlessPage =
        engine.searchOrders(new SalesOrderSearchFilters(null, null, null, null, null, 1, 5));

    assertThat(dealerlessPage.content()).isEmpty();
    assertThat(dealerlessPage.totalElements()).isZero();
  }

  @Test
  void resolveGstTreatment_acceptsLegacyAliasesAndRejectsUnknownValue() throws Exception {
    Method method = SalesCoreEngine.class.getDeclaredMethod("resolveGstTreatment", String.class);
    method.setAccessible(true);

    assertThat(method.invoke(engine, "EXCLUSIVE").toString()).isEqualTo("NONE");
    assertThat(method.invoke(engine, "inclusive").toString()).isEqualTo("ORDER_TOTAL");
    assertThatThrownBy(() -> method.invoke(engine, "mystery"))
        .hasRootCauseInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasRootCauseMessage("Unknown GST treatment mystery");
  }

  @Test
  void computeDispatchLineAmounts_handlesInclusiveAndExclusiveTaxPaths() throws Exception {
    Method method =
        SalesCoreEngine.class.getDeclaredMethod(
            "computeDispatchLineAmounts",
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            boolean.class);
    method.setAccessible(true);

    Object inclusive =
        method.invoke(engine, new BigDecimal("118"), BigDecimal.ZERO, new BigDecimal("18"), true);
    Object exclusive =
        method.invoke(
            engine, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("18"), false);

    assertThat(
            (BigDecimal)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    inclusive, "net"))
        .isEqualTo(new BigDecimal("100.00"));
    assertThat(
            (BigDecimal)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    inclusive, "tax"))
        .isEqualTo(new BigDecimal("18.00"));
    assertThat(
            (BigDecimal)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    exclusive, "net"))
        .isEqualTo(new BigDecimal("90.00"));
    assertThat(
            (BigDecimal)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    exclusive, "tax"))
        .isEqualTo(new BigDecimal("16.20"));
    assertThat(
            (BigDecimal)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    exclusive, "total"))
        .isEqualTo(new BigDecimal("106.20"));
  }

  private SalesOrderRequest salesOrderRequest(String paymentMode, String explicitIdempotencyKey) {
    return new SalesOrderRequest(
        101L,
        new BigDecimal("100.00"),
        "INR",
        "notes",
        List.of(
            new SalesOrderItemRequest(
                "SKU-1", "Primer", BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO)),
        "NONE",
        BigDecimal.ZERO,
        Boolean.FALSE,
        explicitIdempotencyKey,
        paymentMode);
  }
}
