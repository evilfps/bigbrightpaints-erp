package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private InvoiceService invoiceService;
    @Mock
    private AccountingService accountingService;
    @Mock
    private SalesJournalService salesJournalService;
    @Mock
    private HrService hrService;
    @Mock
    private ReportService reportService;
    @Mock
    private OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock
    private CompanyContextService companyContextService;
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
                invoiceService,
                accountingService,
                salesJournalService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyEntityLookup,
                companyDefaultAccountsService,
                companyContextService,
                companyClock,
                new OrchestratorFeatureFlags(true, true),
                new NoOpTransactionManager(),
                10L,
                20L);

        company = new Company();
        company.setCode(COMPANY_ID);
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
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
        assertThrows(ApplicationException.class,
                () -> integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "DISPATCHED", COMPANY_ID));
        verify(salesService).hasDispatchConfirmation(ORDER_ID);
        verify(salesService, never()).updateOrchestratorWorkflowStatus(eq(ORDER_ID), anyString());
    }

    @Test
    void updateFulfillmentDispatchAcknowledgesWhenDispatchConfirmed() {
        order.setStatus("SHIPPED");
        when(salesService.hasDispatchConfirmation(ORDER_ID)).thenReturn(true);
        when(salesService.getOrderWithItems(ORDER_ID)).thenReturn(order);

        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.updateFulfillment(String.valueOf(ORDER_ID), "DISPATCHED", COMPANY_ID);

        assertThat(result.orderStatus()).isEqualTo("SHIPPED");
        assertThat(result.awaitingProduction()).isFalse();
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
    void createAccountingEntryFailsClosedInCodeRed() {
        assertThrows(IllegalStateException.class, () ->
                integrationCoordinator.createAccountingEntry(String.valueOf(ORDER_ID), COMPANY_ID));
    }

    @Test
    void updateProductionStatusFailsClosedWhenFactoryDispatchDisabled() {
        IntegrationCoordinator disabled = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                invoiceService,
                accountingService,
                salesJournalService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyEntityLookup,
                companyDefaultAccountsService,
                companyContextService,
                companyClock,
                new OrchestratorFeatureFlags(true, false),
                new NoOpTransactionManager(),
                10L,
                20L);

        assertThrows(ApplicationException.class,
                () -> disabled.updateProductionStatus("101", COMPANY_ID));
        verify(factoryService, never()).updatePlanStatus(any(), anyString());
    }

    @Test
    void generatePayrollFailsClosedWhenPayrollDisabled() {
        IntegrationCoordinator disabled = new IntegrationCoordinator(
                salesService,
                factoryService,
                finishedGoodsService,
                invoiceService,
                accountingService,
                salesJournalService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyEntityLookup,
                companyDefaultAccountsService,
                companyContextService,
                companyClock,
                new OrchestratorFeatureFlags(false, true),
                new NoOpTransactionManager(),
                10L,
                20L);

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
                invoiceService,
                accountingService,
                salesJournalService,
                hrService,
                reportService,
                orderAutoApprovalStateRepository,
                accountingFacade,
                companyEntityLookup,
                companyDefaultAccountsService,
                companyContextService,
                companyClock,
                new OrchestratorFeatureFlags(false, true),
                new NoOpTransactionManager(),
                10L,
                20L);

        assertThrows(ApplicationException.class,
                () -> disabled.recordPayrollPayment(1L, new BigDecimal("1000"), 1L, 2L, COMPANY_ID));
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
}
