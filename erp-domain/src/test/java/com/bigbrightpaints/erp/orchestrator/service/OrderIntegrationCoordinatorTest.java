package com.bigbrightpaints.erp.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class OrderIntegrationCoordinatorTest {

  @Mock private SalesService salesService;
  @Mock private FactoryService factoryService;
  @Mock private FinishedGoodsService finishedGoodsService;
  @Mock private OrderSupportCoordinator orderSupportCoordinator;
  @Mock private CompanyRepository companyRepository;

  private OrderIntegrationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator =
        new OrderIntegrationCoordinator(
            salesService,
            factoryService,
            finishedGoodsService,
            orderSupportCoordinator,
            new IntegrationCoordinatorSupportService(companyRepository));
  }

  @Test
  void autoApproveOrder_returnsPendingProductionWhenCompanyContextMissing() {
    IntegrationCoordinator.AutoApprovalResult result = coordinator.autoApproveOrder("42", "   ");

    assertThat(result.orderStatus()).isEqualTo("PENDING_PRODUCTION");
    assertThat(result.awaitingProduction()).isTrue();
    verifyNoInteractions(
        orderSupportCoordinator, salesService, factoryService, finishedGoodsService);
  }

  @Test
  void autoApproveOrder_returnsReadyToShipWhenStateAlreadyCompleted() {
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    state.markCompleted();
    when(orderSupportCoordinator.requireNumericOrderId("42", "autoApproveOrder")).thenReturn(42L);
    doNothing().when(orderSupportCoordinator).attachOrderTrace(42L, null);
    when(orderSupportCoordinator.lockAutoApprovalState("COMP", 42L)).thenReturn(state);

    IntegrationCoordinator.AutoApprovalResult result = coordinator.autoApproveOrder("42", "COMP");

    assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
    assertThat(result.awaitingProduction()).isFalse();
  }

  @Test
  void autoApproveOrder_marksCompletedWhenInventoryWasAlreadyReserved() {
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    state.markInventoryReserved();
    when(orderSupportCoordinator.requireNumericOrderId("42", "autoApproveOrder")).thenReturn(42L);
    doNothing().when(orderSupportCoordinator).attachOrderTrace(42L, null);
    when(orderSupportCoordinator.lockAutoApprovalState("COMP", 42L)).thenReturn(state);

    IntegrationCoordinator.AutoApprovalResult result = coordinator.autoApproveOrder("42", "COMP");

    assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
    assertThat(result.awaitingProduction()).isFalse();
    assertThat(state.isCompleted()).isTrue();
    verify(salesService).updateOrchestratorWorkflowStatus(42L, "READY_TO_SHIP");
  }

  @Test
  void autoApproveOrder_marksStateFailedWhenStatusUpdateThrows() {
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    state.markInventoryReserved();
    when(orderSupportCoordinator.requireNumericOrderId("42", "autoApproveOrder")).thenReturn(42L);
    doNothing().when(orderSupportCoordinator).attachOrderTrace(42L, null);
    when(orderSupportCoordinator.lockAutoApprovalState("COMP", 42L)).thenReturn(state);
    doThrow(new RuntimeException("status update failed"))
        .when(salesService)
        .updateOrchestratorWorkflowStatus(42L, "READY_TO_SHIP");

    assertThatThrownBy(() -> coordinator.autoApproveOrder("42", "COMP"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("status update failed");
    assertThat(state.getStatus()).isEqualTo("FAILED");
    assertThat(state.getLastError()).contains("status update failed");
  }

  @Test
  void updateProductionStatus_resumesAutoApprovalAfterPlanCompletion() {
    ProductionPlanDto plan = productionPlan(55L, "PLAN-42");
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    state.markInventoryReserved();
    when(orderSupportCoordinator.parseNumericId("55")).thenReturn(55L);
    when(factoryService.updatePlanStatus(55L, "COMPLETED")).thenReturn(plan);
    when(orderSupportCoordinator.extractOrderIdFromPlan(plan)).thenReturn(Optional.of(42L));
    when(orderSupportCoordinator.requireNumericOrderId("42", "autoApproveOrder")).thenReturn(42L);
    doNothing().when(orderSupportCoordinator).attachOrderTrace(42L, "trace-1");
    when(orderSupportCoordinator.lockAutoApprovalState("COMP", 42L)).thenReturn(state);

    coordinator.updateProductionStatus("55", "COMP", "trace-1", "idem-1");

    verify(salesService).updateOrchestratorWorkflowStatus(42L, "READY_TO_SHIP");
    assertThat(state.isCompleted()).isTrue();
  }

  @Test
  void updateFulfillment_readyToShipDelegatesToAutoApprove() {
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    state.markInventoryReserved();
    when(orderSupportCoordinator.requireNumericOrderId("42", "updateFulfillment")).thenReturn(42L);
    when(orderSupportCoordinator.requireNumericOrderId("42", "autoApproveOrder")).thenReturn(42L);
    doNothing().when(orderSupportCoordinator).attachOrderTrace(42L, "trace-2");
    when(orderSupportCoordinator.lockAutoApprovalState("COMP", 42L)).thenReturn(state);

    IntegrationCoordinator.AutoApprovalResult result =
        coordinator.updateFulfillment("42", "READY_TO_SHIP", "COMP", "trace-2", "idem-2");

    assertThat(result.orderStatus()).isEqualTo("READY_TO_SHIP");
    assertThat(result.awaitingProduction()).isFalse();
    verify(salesService).updateOrchestratorWorkflowStatus(42L, "READY_TO_SHIP");
    assertThat(state.isCompleted()).isTrue();
  }

  private ProductionPlanDto productionPlan(Long id, String planNumber) {
    return new ProductionPlanDto(
        id,
        UUID.randomUUID(),
        planNumber,
        "Product",
        1.0,
        java.time.LocalDate.of(2026, 4, 10),
        "COMPLETED",
        "notes");
  }
}
