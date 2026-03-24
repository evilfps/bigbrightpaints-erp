package com.bigbrightpaints.erp.orchestrator.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestDto;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class IntegrationCoordinatorTest {

    private static final String COMPANY_ID = "COMP";
    private static final Long ORDER_ID = 42L;

    @Mock
    private SalesService salesService;
    @Mock
    private FactoryService factoryService;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private AccountingService accountingService;
    @Mock
    private HrService hrService;
    @Mock
    private ReportService reportService;
    @Mock
    private OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyClock companyClock;

    private IntegrationCoordinator integrationCoordinator;
    private SalesOrder order;
    private Company company;
    private OrderAutoApprovalState state;

    @BeforeEach
    void setUp() {
        integrationCoordinator = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                accountingService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyRepository,
                companyClock,
                new OrchestratorFeatureFlags(true, true),
                new NoOpTransactionManager());

        company = new Company();
        company.setCode(COMPANY_ID);
        company.setTimezone("UTC");
        lenient().when(companyRepository.findByCodeIgnoreCase(COMPANY_ID)).thenReturn(Optional.of(company));
        lenient().when(companyClock.today(any())).thenReturn(LocalDate.of(2024, 1, 1));
        order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(new Dealer());

        state = new OrderAutoApprovalState(COMPANY_ID, ORDER_ID);
        lenient().when(orderAutoApprovalStateRepository.findByCompanyCodeAndOrderId(COMPANY_ID, ORDER_ID))
                .thenReturn(Optional.of(state));
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void autoApproveOrderMarksReadyToShipWhenInventoryAvailable() {
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of());
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(reservation);

        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), new BigDecimal("1500"), COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(result.awaitingProduction()).isFalse();
        assertThat(state.isInventoryReserved()).isTrue();
        assertThat(state.isDispatchFinalized()).isFalse();
        assertThat(state.isInvoiceIssued()).isFalse();
        assertThat(state.isOrderStatusUpdated()).isTrue();
        assertThat(state.isCompleted()).isTrue();

        verify(salesService).updateOrchestratorWorkflowStatus(ORDER_ID, "RESERVED");
        verify(salesService).updateOrchestratorWorkflowStatus(ORDER_ID, "READY_TO_SHIP");
        verify(salesService, never()).updateOrchestratorWorkflowStatus(ORDER_ID, "SHIPPED");
        verify(salesService, never()).confirmDispatch(any());
    }

    @Test
    void autoApproveOrderAttachesTraceWithinProvidedCompanyContext() {
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of());
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(reservation);

        List<String> contextsDuringAttach = new ArrayList<>();
        doAnswer(invocation -> {
            contextsDuringAttach.add(CompanyContextHolder.getCompanyCode());
            return null;
        }).when(salesService).attachTraceId(ORDER_ID, "trace-auto-42");

        CompanyContextHolder.setCompanyCode("AMBIENT-COMPANY");

        integrationCoordinator.autoApproveOrder(
                String.valueOf(ORDER_ID),
                new BigDecimal("1500"),
                "  " + COMPANY_ID + "  ",
                "trace-auto-42",
                null);

        assertThat(contextsDuringAttach).isNotEmpty();
        assertThat(contextsDuringAttach).allMatch(COMPANY_ID::equals);
        assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("AMBIENT-COMPANY");
    }

    @Test
    void updateFulfillmentCancelledMarksStateFailed() {
        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "cancelled", COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("CANCELLED");
        assertThat(result.awaitingProduction()).isFalse();
        assertThat(state.getStatus()).isEqualToIgnoringCase("FAILED");
        assertThat(state.getLastError()).isEqualTo("Cancelled");
        verify(salesService).cancelOrder(ORDER_ID, "Cancelled");
    }

    @Test
    void updateFulfillmentDispatchFailsClosed() {
        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "DISPATCHED", COMPANY_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(ex.getDetails())
                .containsEntry("canonicalPath", "/api/v1/sales/dispatch/confirm")
                .containsEntry("requestedStatus", "DISPATCHED");
        verify(salesService, never()).hasDispatchConfirmation(anyLong());
        verify(salesService, never()).getOrderWithItems(anyLong());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(eq(ORDER_ID), anyString());
    }

    @Test
    void updateFulfillmentDispatchStillFailsClosedWhenDispatchAlreadyConfirmed() {
        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "DISPATCHED", COMPANY_ID));

        assertThat(ex.getDetails()).containsEntry("canonicalPath", "/api/v1/sales/dispatch/confirm");
        verify(salesService, never()).hasDispatchConfirmation(anyLong());
        verify(salesService, never()).getOrderWithItems(anyLong());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(eq(ORDER_ID), anyString());
    }

    @Test
    void updateFulfillmentProcessingAllowedWhenNoDispatchTruth() {
        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "PROCESSING", COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("PROCESSING");
        assertThat(result.awaitingProduction()).isFalse();
        verify(salesService).updateOrchestratorWorkflowStatus(ORDER_ID, "PROCESSING");
    }

    @Test
    void fetchFinanceDashboard_usesExplicitEmptyReportQueryRequest() {
        when(reportService.cashFlow()).thenReturn(new CashFlowDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null));
        when(reportService.agedDebtors(ReportQueryRequestBuilder.empty())).thenReturn(List.of());
        when(reportService.inventoryReconciliation()).thenReturn(
                new ReconciliationSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(accountingService.listAccounts()).thenReturn(List.of(
                new AccountDto(1L, null, "CASH", "Cash", null, BigDecimal.ZERO)));

        Map<String, Object> dashboard = integrationCoordinator.fetchFinanceDashboard(COMPANY_ID);

        assertThat(dashboard).containsKeys("cashflow", "agedDebtors", "ledger", "reconciliation");
        verify(reportService).cashFlow();
        verify(reportService).agedDebtors(ReportQueryRequestBuilder.empty());
        verify(accountingService).listAccounts();
        verify(reportService).inventoryReconciliation();
    }

    @Test
    void reserveInventoryRejectsMalformedOrderIdBeforeSideEffects() {
        ApplicationException ex = assertThrows(ApplicationException.class, () ->
                integrationCoordinator.reserveInventory("abc", COMPANY_ID, "trace-order", "idem-order"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
        verify(salesService, never()).attachTraceId(anyLong(), anyString());
        verify(salesService, never()).getOrderWithItems(anyLong());
        verify(finishedGoodsService, never()).reserveForOrder(any());
        verify(factoryService, never()).createPlan(any());
        verify(factoryService, never()).createTask(any());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(anyLong(), anyString());
    }

    @Test
    void updateFulfillmentRejectsMalformedOrderIdBeforeSideEffects() {
        ApplicationException ex = assertThrows(ApplicationException.class, () ->
                integrationCoordinator.updateFulfillment("abc", "PROCESSING", COMPANY_ID, "trace-fulfillment", "idem-fulfillment"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
        verify(salesService, never()).attachTraceId(anyLong(), anyString());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(anyLong(), anyString());
        verify(salesService, never()).cancelOrder(anyLong(), anyString());
        verify(salesService, never()).hasDispatchConfirmation(anyLong());
        verify(salesService, never()).getOrderWithItems(anyLong());
    }

    @Test
    void parseNumericIdLogsSanitizedIdentifierOnly() {
        Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(IntegrationCoordinator.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        coordinatorLogger.addAppender(listAppender);

        try {
            assertThrows(ApplicationException.class, () ->
                    integrationCoordinator.updateFulfillment("abc\ninjected", "PROCESSING", COMPANY_ID));

            String parseFailureLog = listAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("Rejected non-numeric identifier"))
                    .findFirst()
                    .orElse("");

            assertThat(parseFailureLog).contains("Rejected non-numeric identifier");
            assertThat(parseFailureLog).contains("invalid#");
            assertThat(parseFailureLog).doesNotContain("abc");
            assertThat(parseFailureLog).doesNotContain("injected");
            assertThat(parseFailureLog).doesNotContain("\n");
        } finally {
            coordinatorLogger.detachAppender(listAppender);
        }
    }

    @Test
    void autoApproveOrderRetriesWithoutReplayingReservation() {
        state.markInventoryReserved();
        state.markOrderStatusUpdated();

        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), null, COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(result.awaitingProduction()).isFalse();
        assertThat(state.isInventoryReserved()).isTrue();
        assertThat(state.isDispatchFinalized()).isFalse();
        assertThat(state.isSalesJournalPosted()).isFalse();
        assertThat(state.isInvoiceIssued()).isFalse();
        assertThat(state.isCompleted()).isTrue();

        verify(finishedGoodsService, never()).reserveForOrder(any());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(eq(ORDER_ID), anyString());
        verify(salesService, never()).confirmDispatch(any());
    }

    @Test
    void autoApproveOrderSkipsWorkWhenAlreadyCompleted() {
        state.markInventoryReserved();
        state.markOrderStatusUpdated();
        state.markCompleted();

        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), null, COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(result.awaitingProduction()).isFalse();
        verify(finishedGoodsService, never()).reserveForOrder(any());
        verify(salesService, never()).updateOrchestratorWorkflowStatus(eq(ORDER_ID), anyString());
    }

    @Test
    void autoApproveOrderRetrySkipsReservationAfterPartialProgress() {
        state.markInventoryReserved();
        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), null, COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(result.awaitingProduction()).isFalse();
        verify(finishedGoodsService, never()).reserveForOrder(any());
        verify(salesService, times(1)).updateOrchestratorWorkflowStatus(ORDER_ID, "READY_TO_SHIP");
    }

    @Test
    void autoApproveOrderDoesNotDispatchAutomatically() {
        order.setOrderNumber("SO-42");
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of());
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(reservation);

        integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), new BigDecimal("1500"), COMPANY_ID);

        verify(salesService, never()).confirmDispatch(any());
    }

    @Test
    void autoApproveOrderDoesNotFinalizeShipmentOnRetry() {
        order.setOrderNumber("SO-42");
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of());
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(reservation);

        integrationCoordinator.autoApproveOrder(String.valueOf(ORDER_ID), new BigDecimal("1500"), COMPANY_ID);

        verify(salesService, never()).confirmDispatch(any());
    }

    @Test
    void health_omitsEmployeeCountWhenHrPayrollPaused() {
        company.setEnabledModules(Set.of("PORTAL"));
        CompanyContextHolder.setCompanyCode(COMPANY_ID);
        when(salesService.listOrders(null)).thenReturn(List.of());
        when(factoryService.listPlans()).thenReturn(List.of());
        when(accountingService.listAccounts()).thenReturn(List.of());

        Map<String, Object> health = integrationCoordinator.health();

        assertThat(health).doesNotContainKey("employees");
        verify(hrService, never()).listEmployees();
    }

    @Test
    void health_includesEmployeeCountWhenHrPayrollEnabled() {
        company.setEnabledModules(Set.of("PORTAL", "HR_PAYROLL"));
        CompanyContextHolder.setCompanyCode(COMPANY_ID);
        when(salesService.listOrders(null)).thenReturn(List.of());
        when(factoryService.listPlans()).thenReturn(List.of());
        when(accountingService.listAccounts()).thenReturn(List.of());
        when(hrService.listEmployees()).thenReturn(List.of(
                employee("ACTIVE"),
                employee("INACTIVE")));

        Map<String, Object> health = integrationCoordinator.health();

        assertThat(health).containsEntry("employees", 2);
        verify(hrService).listEmployees();
    }

    @Test
    void fetchAdminDashboard_omitsHrSnapshotWhenHrPayrollPaused() {
        company.setEnabledModules(Set.of("PORTAL"));
        when(salesService.listOrders(null)).thenReturn(List.of());
        when(salesService.listDealers()).thenReturn(List.of());
        when(accountingService.listAccounts()).thenReturn(List.of());

        Map<String, Object> snapshot = integrationCoordinator.fetchAdminDashboard(COMPANY_ID);

        assertThat(snapshot).doesNotContainKey("hr");
        verify(hrService, never()).listEmployees();
        verify(hrService, never()).listLeaveRequests();
    }

    @Test
    void fetchAdminDashboard_includesHrSnapshotWhenHrPayrollEnabled() {
        company.setEnabledModules(Set.of("PORTAL", "HR_PAYROLL"));
        when(salesService.listOrders(null)).thenReturn(List.of());
        when(salesService.listDealers()).thenReturn(List.of());
        when(accountingService.listAccounts()).thenReturn(List.of());
        when(hrService.listEmployees()).thenReturn(List.of(
                employee("ACTIVE"),
                employee("INACTIVE")));
        when(hrService.listLeaveRequests()).thenReturn(List.of(
                leaveRequest("APPROVED"),
                leaveRequest("PENDING")));

        Map<String, Object> snapshot = integrationCoordinator.fetchAdminDashboard(COMPANY_ID);

        assertThat(snapshot).containsKey("hr");
        assertThat((Map<String, Object>) snapshot.get("hr"))
                .containsEntry("activeEmployees", 1L)
                .containsEntry("onLeave", 1L);
        verify(hrService).listEmployees();
        verify(hrService).listLeaveRequests();
    }

    @Test
    void isHrPayrollEnabled_requiresCompanyAndEnabledModules() {
        Company modulesMissing = new Company();
        modulesMissing.setEnabledModules(null);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(integrationCoordinator, "isHrPayrollEnabled", (Company) null))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(integrationCoordinator, "isHrPayrollEnabled", modulesMissing))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(integrationCoordinator, "isHrPayrollEnabled", company))
                .isFalse();

        company.setEnabledModules(Set.of("HR_PAYROLL"));
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(integrationCoordinator, "isHrPayrollEnabled", company))
                .isTrue();
    }

    @Test
    void updateProductionStatusFailsClosedWhenFactoryDispatchDisabled() {
        IntegrationCoordinator disabled = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                accountingService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyRepository,
                companyClock,
                new OrchestratorFeatureFlags(true, false),
                new NoOpTransactionManager());

        assertThrows(ApplicationException.class,
                () -> disabled.updateProductionStatus("101", COMPANY_ID));
        verify(factoryService, never()).updatePlanStatus(any(), anyString());
    }

    @Test
    void queueProductionBuildsNextDayPlanUsingTenantCompanyTimezone() {
        integrationCoordinator.queueProduction("101", COMPANY_ID);

        verify(companyRepository).findByCodeIgnoreCase(COMPANY_ID);
        verify(companyClock).today(company);
        verify(factoryService).createPlan(argThat((ProductionPlanRequest request) ->
                request != null
                        && request.planNumber().equals("PLAN-101")
                        && request.productName().equals("Order 101")
                        && request.quantity() == 1.0
                        && request.plannedDate().equals(LocalDate.of(2024, 1, 2))
                        && request.notes().equals("Auto-generated from orchestrator")));
    }

    @Test
    void queueProductionBuildsNextDayPlanUsingNumericCompanyIdLookup() {
        company.setCode("ACME");
        when(companyRepository.findById(99L)).thenReturn(Optional.of(company));
        when(companyRepository.findByCodeIgnoreCase("99")).thenReturn(Optional.empty());
        List<String> contextsDuringCreatePlan = new ArrayList<>();
        doAnswer(invocation -> {
            contextsDuringCreatePlan.add(CompanyContextHolder.getCompanyCode());
            return null;
        }).when(factoryService).createPlan(any());

        integrationCoordinator.queueProduction("101", "99");

        verify(companyRepository).findByCodeIgnoreCase("99");
        verify(companyRepository).findById(99L);
        verify(companyClock).today(company);
        assertThat(contextsDuringCreatePlan).containsExactly("ACME");
        verify(factoryService).createPlan(argThat((ProductionPlanRequest request) ->
                request != null
                        && request.planNumber().equals("PLAN-101")
                        && request.productName().equals("Order 101")
                        && request.quantity() == 1.0
                        && request.plannedDate().equals(LocalDate.of(2024, 1, 2))
                        && request.notes().equals("Auto-generated from orchestrator")));
    }

    @Test
    void queueProductionPrefersCompanyCodeBeforeNumericIdFallback() {
        Company codeCompany = new Company();
        codeCompany.setCode("99");
        codeCompany.setTimezone("Asia/Kolkata");
        Company idCompany = new Company();
        idCompany.setCode("OTHER");
        idCompany.setTimezone("UTC");
        when(companyRepository.findByCodeIgnoreCase("99")).thenReturn(Optional.of(codeCompany));
        when(companyClock.today(codeCompany)).thenReturn(LocalDate.of(2024, 2, 10));

        integrationCoordinator.queueProduction("101", "99");

        verify(companyRepository).findByCodeIgnoreCase("99");
        verify(companyRepository, never()).findById(99L);
        verify(companyClock).today(codeCompany);
        verify(companyClock, never()).today(idCompany);
        verify(factoryService).createPlan(argThat((ProductionPlanRequest request) ->
                request != null
                        && request.planNumber().equals("PLAN-101")
                        && request.plannedDate().equals(LocalDate.of(2024, 2, 11))));
    }

    @Test
    void queueProductionRejectsBlankCompanyIdBeforeSideEffects() {
        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> integrationCoordinator.queueProduction("101", "   "));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
        assertThat(ex.getDetails())
                .containsEntry("field", "companyId")
                .containsEntry("operation", "queueProduction");
        verify(companyRepository, never()).findById(anyLong());
        verify(companyRepository, never()).findByCodeIgnoreCase(anyString());
        verify(companyClock, never()).today(any());
        verify(factoryService, never()).createPlan(any());
    }

    @Test
    void queueProductionRejectsUnknownCompanyIdWithSafeIdentifier() {
        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> integrationCoordinator.queueProduction("101", "UNKNOWN-COMPANY"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
        assertThat(ex.getDetails())
                .containsEntry("field", "companyId")
                .containsEntry("operation", "queueProduction")
                .containsEntry("safeIdentifier",
                        CorrelationIdentifierSanitizer.safeIdentifierForLog("UNKNOWN-COMPANY"));
        verify(companyRepository).findByCodeIgnoreCase("UNKNOWN-COMPANY");
        verify(companyClock, never()).today(any());
        verify(factoryService, never()).createPlan(any());
    }

    @Test
    void generatePayrollFailsClosedWhenPayrollDisabled() {
        IntegrationCoordinator disabled = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                accountingService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyRepository,
                companyClock,
                new OrchestratorFeatureFlags(false, true),
                new NoOpTransactionManager());

        assertThrows(ApplicationException.class,
                () -> disabled.generatePayroll(LocalDate.now(), new BigDecimal("1000"), COMPANY_ID));
        verify(hrService, never()).createPayrollRun(any());
    }

    @Test
    void recordPayrollPaymentFailsClosedWhenPayrollDisabled() {
        IntegrationCoordinator disabled = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                accountingService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyRepository,
                companyClock,
                new OrchestratorFeatureFlags(false, true),
                new NoOpTransactionManager());

        assertThrows(ApplicationException.class,
                () -> disabled.recordPayrollPayment(1L, new BigDecimal("1000"), 1L, 2L, COMPANY_ID));
        verify(accountingFacade, never()).recordPayrollPayment(any());
    }

    void reserveInventoryCorrelationAnnotatesProductionArtifactsAndAttachesTrace() {
        order.setOrderNumber("SO-42");
        InventoryShortage shortage = new InventoryShortage("SKU-1", new BigDecimal("3"), "Red Paint");
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of(shortage));
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(reservation);

        integrationCoordinator.reserveInventory(
                String.valueOf(ORDER_ID),
                COMPANY_ID,
                "trace-order-42",
                "idem-order-42");

        verify(salesService).attachTraceId(ORDER_ID, "trace-order-42");
        verify(factoryService).createPlan(argThat(plan ->
                plan != null
                        && plan.notes() != null
                        && plan.notes().contains("[trace=trace-order-42]")
                        && plan.notes().contains("[idem=idem-order-42]")));
        verify(factoryService).createTask(argThat(task ->
                task != null
                        && task.description() != null
                        && task.description().contains("[trace=trace-order-42]")
                        && task.description().contains("[idem=idem-order-42]")));
    }

    @Test
    void releaseInventoryPropagatesTraceAndIdempotencyInBatchNotes() {
        integrationCoordinator.releaseInventory(
                "B-901",
                COMPANY_ID,
                "trace-release-901",
                "idem-release-901");

        verify(factoryService).logBatch(eq(null), argThat((ProductionBatchRequest request) ->
                request != null
                        && request.notes() != null
                        && request.notes().contains("Auto release for dispatch B-901")
                        && request.notes().contains("[trace=trace-release-901]")
                        && request.notes().contains("[idem=idem-release-901]")));
    }

    @Test
    void recordPayrollPaymentPropagatesTraceAndIdempotencyInMemo() {
        integrationCoordinator.recordPayrollPayment(
                901L,
                new BigDecimal("500.00"),
                11L,
                22L,
                COMPANY_ID,
                "trace-payroll-901",
                "idem-payroll-901");

        verify(accountingFacade).recordPayrollPayment(argThat((PayrollPaymentRequest request) ->
                request != null
                        && request.payrollRunId().equals(901L)
                        && request.amount().compareTo(new BigDecimal("500.00")) == 0
                        && request.memo() != null
                        && request.memo().contains("Orchestrator payroll payment for run 901")
                        && request.memo().contains("[trace=trace-payroll-901]")
                        && request.memo().contains("[idem=idem-payroll-901]")));
    }

    @Test
    void recordPayrollPaymentRejectsMalformedIdempotencyKey() {
        assertThrows(ApplicationException.class, () -> integrationCoordinator.recordPayrollPayment(
                901L,
                new BigDecimal("500.00"),
                11L,
                22L,
                COMPANY_ID,
                "trace-payroll-901",
                "idem malformed"));

        verify(accountingFacade, never()).recordPayrollPayment(any());
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op
        }
    }

    private EmployeeDto employee(String status) {
        return new EmployeeDto(
                1L,
                null,
                "Harper",
                "Singh",
                "harper@example.com",
                null,
                "Operator",
                status,
                LocalDate.of(2024, 1, 1),
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
                null);
    }

    private LeaveRequestDto leaveRequest(String status) {
        return new LeaveRequestDto(
                1L,
                null,
                1L,
                "Harper Singh",
                "Vacation",
                LocalDate.of(2024, 1, 5),
                LocalDate.of(2024, 1, 7),
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
