package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.controller.DealerController;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.controller.OrchestratorController;
import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.OrderFulfillmentRequest;
import com.bigbrightpaints.erp.orchestrator.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.orchestrator.exception.OrchestratorFeatureDisabledException;
import com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.service.CommandDispatcher;
import com.bigbrightpaints.erp.orchestrator.service.EventPublisherService;
import com.bigbrightpaints.erp.orchestrator.service.IntegrationCoordinator;
import com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService;
import com.bigbrightpaints.erp.orchestrator.service.TraceService;
import com.bigbrightpaints.erp.orchestrator.workflow.WorkflowService;

@Tag("concurrency")
@Tag("reconciliation")
@Tag("critical")
class TS_RuntimeOrchestratorExecutableCoverageTest {

  @Test
  void policyEnforcer_rejects_missing_user_or_company_context() {
    PolicyEnforcer enforcer = new PolicyEnforcer();

    assertThatThrownBy(() -> enforcer.checkOrderApprovalPermissions(null, "C1"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkOrderApprovalPermissions("u1", null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkDispatchPermissions("u1", null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkDispatchPermissions(null, "C1"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkPayrollPermissions(null, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkPayrollPermissions("u1", null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");
    assertThatThrownBy(() -> enforcer.checkPayrollPermissions(null, "C1"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing user or company context");

    enforcer.checkOrderApprovalPermissions("u1", "C1");
    enforcer.checkDispatchPermissions("u1", "C1");
    enforcer.checkPayrollPermissions("u1", "C1");
  }

  @Test
  void commandDispatcher_covers_success_replay_and_feature_gated_paths() {
    WorkflowService workflowService = mock(WorkflowService.class);
    IntegrationCoordinator integrationCoordinator = mock(IntegrationCoordinator.class);
    EventPublisherService eventPublisherService = mock(EventPublisherService.class);
    TraceService traceService = mock(TraceService.class);
    PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    OrchestratorIdempotencyService idempotencyService = mock(OrchestratorIdempotencyService.class);
    OrchestratorFeatureFlags featureFlags = mock(OrchestratorFeatureFlags.class);

    CommandDispatcher dispatcher =
        new CommandDispatcher(
            workflowService,
            integrationCoordinator,
            eventPublisherService,
            traceService,
            policyEnforcer,
            idempotencyService,
            featureFlags);

    OrchestratorCommand orderCommand =
        new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-order", "hash", "trace-order");
    OrchestratorIdempotencyService.CommandLease orderLease =
        new OrchestratorIdempotencyService.CommandLease("trace-order", orderCommand, true);

    when(idempotencyService.start(eq("ORCH.ORDER.APPROVE"), eq("idem-order"), any(), any()))
        .thenReturn(orderLease);
    when(integrationCoordinator.reserveInventory("42", "C1", "trace-order", "idem-order"))
        .thenReturn(new InventoryReservationResult(null, List.of()));

    String trace =
        dispatcher.approveOrder(
            new ApproveOrderRequest("42", "ops@bbp.com", new BigDecimal("100.00")),
            "idem-order",
            "req-order",
            "C1",
            "ops@bbp.com");

    assertThat(trace).isEqualTo("trace-order");
    verify(eventPublisherService, atLeastOnce()).enqueue(any());
    verify(traceService, atLeastOnce())
        .record(eq("trace-order"), any(), eq("C1"), any(), eq("req-order"), eq("idem-order"));
    verify(idempotencyService).markSuccess(orderCommand);

    OrchestratorCommand replayCommand =
        new OrchestratorCommand(
            1L, "ORCH.ORDER.APPROVE", "idem-order-replay", "hash", "trace-replay");
    OrchestratorIdempotencyService.CommandLease replayLease =
        new OrchestratorIdempotencyService.CommandLease("trace-replay", replayCommand, false);
    when(idempotencyService.start(eq("ORCH.ORDER.APPROVE"), eq("idem-order-replay"), any(), any()))
        .thenReturn(replayLease);

    String replayTrace =
        dispatcher.approveOrder(
            new ApproveOrderRequest("42", "ops@bbp.com", new BigDecimal("100.00")),
            "idem-order-replay",
            null,
            "C1",
            "ops@bbp.com");
    assertThat(replayTrace).isEqualTo("trace-replay");

    assertThatThrownBy(
            () ->
                dispatcher.dispatchBatch(
                    new DispatchRequest("BATCH-1", "ops@bbp.com", new BigDecimal("50.00")),
                    "idem-dispatch",
                    "req-dispatch",
                    "C1",
                    "ops@bbp.com"))
        .isInstanceOf(OrchestratorFeatureDisabledException.class)
        .hasMessageContaining("/api/v1/dispatch/confirm");

    OrchestratorCommand payrollCommand =
        new OrchestratorCommand(1L, "ORCH.PAYROLL.RUN", "idem-payroll", "hash", "trace-payroll");
    OrchestratorIdempotencyService.CommandLease payrollLease =
        new OrchestratorIdempotencyService.CommandLease("trace-payroll", payrollCommand, true);
    when(idempotencyService.start(eq("ORCH.PAYROLL.RUN"), eq("idem-payroll"), any(), any()))
        .thenReturn(payrollLease);
    when(featureFlags.isPayrollEnabled()).thenReturn(false);

    assertThatThrownBy(
            () ->
                dispatcher.runPayroll(
                    new PayrollRunRequest(
                        LocalDate.of(2026, 2, 1),
                        "ops@bbp.com",
                        11L,
                        12L,
                        new BigDecimal("500.00")),
                    "idem-payroll",
                    "req-payroll",
                    "C1",
                    "ops@bbp.com"))
        .isInstanceOf(OrchestratorFeatureDisabledException.class)
        .hasMessageContaining("disabled (CODE-RED)");

    verify(idempotencyService).markFailed(eq(payrollCommand), any(RuntimeException.class));
    assertThat(dispatcher.generateTraceId()).isNotBlank();
  }

  @Test
  void orchestratorController_idempotency_payload_selection_branches_are_deterministic() {
    String rawPayload = " raw ";
    String normalizedPayload = "raw";

    String explicit =
        (String)
            ReflectionTestUtils.invokeMethod(
                OrchestratorController.class,
                "selectPayloadForIdempotency",
                " idem-1 ",
                rawPayload,
                normalizedPayload);
    String derived =
        (String)
            ReflectionTestUtils.invokeMethod(
                OrchestratorController.class,
                "selectPayloadForIdempotency",
                null,
                rawPayload,
                normalizedPayload);

    assertThat(explicit).isEqualTo(rawPayload);
    assertThat(derived).isEqualTo(normalizedPayload);
  }

  @Test
  void orchestratorController_paths_execute_idempotency_payload_selection_call_sites() {
    CommandDispatcher dispatcher = mock(CommandDispatcher.class);
    TraceService traceService = mock(TraceService.class);
    OrchestratorController controller = new OrchestratorController(dispatcher, traceService);
    Principal principal = () -> "ops@bbp.com";
    CompanyContextHolder.setCompanyCode("C1");
    try {
      when(dispatcher.approveOrder(any(), any(), any(), any(), any())).thenReturn("trace-approve");
      when(dispatcher.updateOrderFulfillment(any(), any(), any(), any(), any(), any()))
          .thenReturn("trace-fulfill");
      when(dispatcher.dispatchBatch(any(), any(), any(), any(), any()))
          .thenReturn("trace-dispatch");
      when(dispatcher.runPayroll(any(), any(), any(), any(), any())).thenReturn("trace-payroll");

      controller.approveOrder(
          "SO-1",
          new ApproveOrderRequest("SO-1", " ops@bbp.com ", new BigDecimal("100.00")),
          "idem-approve",
          "req-approve",
          principal);
      controller.fulfillOrder(
          "SO-1",
          new OrderFulfillmentRequest("processing", " note "),
          null,
          "req-fulfill",
          principal);
      controller.dispatch(
          "B-1",
          new DispatchRequest("B-1", " ops@bbp.com ", new BigDecimal("55.00")),
          null,
          "req-dispatch",
          principal);
      controller.runPayroll(
          new PayrollRunRequest(
              LocalDate.of(2026, 2, 1), " ops@bbp.com ", 11L, 12L, new BigDecimal("500.00")),
          null,
          "req-payroll",
          principal);
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void dealer_portal_and_controller_ledger_path_use_scoped_lookup_consistently() {
    DealerRepository dealerRepository = mock(DealerRepository.class);
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    DealerLedgerService dealerLedgerService = mock(DealerLedgerService.class);
    StatementService statementService = mock(StatementService.class);
    InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    InvoicePdfService invoicePdfService = mock(InvoicePdfService.class);
    DealerService dealerService = mock(DealerService.class);
    SalesOrderRepository salesOrderRepository = mock(SalesOrderRepository.class);
    CompanyClock companyClock = mock(CompanyClock.class);

    DealerPortalService portalService =
        new DealerPortalService(
            dealerRepository,
            companyContextService,
            dealerLedgerService,
            invoiceRepository,
            invoicePdfService,
            dealerService,
            salesOrderRepository,
            companyClock,
            statementService);

    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 22L);
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    ReflectionTestUtils.setField(dealer, "id", 77L);
    Map<String, Object> ledgerPayload = Map.of("dealerId", 77L, "status", "ok");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(dealerRepository.findByCompanyAndId(company, 77L)).thenReturn(Optional.of(dealer));
    when(dealerService.ledgerView(77L)).thenReturn(ledgerPayload);
    when(invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer))
        .thenReturn(List.of());
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 16));
    when(statementService.dealerAging(dealer, LocalDate.of(2026, 2, 16), "0-0,1-30,31-60,61-90,91"))
        .thenReturn(
            new com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse(
                77L, "Dealer", BigDecimal.ZERO, List.of()));
    when(statementService.dealerOverdueInvoices(dealer, LocalDate.of(2026, 2, 16)))
        .thenReturn(List.of());

    assertThat(portalService.getLedgerForDealer(77L)).isEqualTo(ledgerPayload);
    assertThat(portalService.getInvoicesForDealer(77L)).containsEntry("dealerId", 77L);
    assertThat(portalService.getAgingForDealer(77L)).containsEntry("dealerId", 77L);

    DealerPortalService portalDelegate = mock(DealerPortalService.class);
    when(portalDelegate.getLedgerForDealer(77L)).thenReturn(ledgerPayload);
    DealerController controller =
        new DealerController(mock(DealerService.class), mock(DunningService.class), portalDelegate);

    var response = controller.dealerLedger(77L);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(ledgerPayload);
    verify(portalDelegate).getLedgerForDealer(77L);
  }

  @Test
  void commandDispatcher_failure_and_validation_paths_cover_helper_branches() {
    WorkflowService workflowService = mock(WorkflowService.class);
    IntegrationCoordinator integrationCoordinator = mock(IntegrationCoordinator.class);
    EventPublisherService eventPublisherService = mock(EventPublisherService.class);
    TraceService traceService = mock(TraceService.class);
    PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    OrchestratorIdempotencyService idempotencyService = mock(OrchestratorIdempotencyService.class);
    OrchestratorFeatureFlags featureFlags = mock(OrchestratorFeatureFlags.class);
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            workflowService,
            integrationCoordinator,
            eventPublisherService,
            traceService,
            policyEnforcer,
            idempotencyService,
            featureFlags);

    OrchestratorCommand failingApprove =
        new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "   ", "hash", "trace-failing");
    when(idempotencyService.start(eq("ORCH.ORDER.APPROVE"), eq("  idem-fallback  "), any(), any()))
        .thenReturn(
            new OrchestratorIdempotencyService.CommandLease("trace-failing", failingApprove, true));
    when(integrationCoordinator.reserveInventory("99", "C1", "trace-failing", "idem-fallback"))
        .thenThrow(new RuntimeException("reserve failed"));

    assertThatThrownBy(
            () ->
                dispatcher.approveOrder(
                    new ApproveOrderRequest("99", "ops@bbp.com", new BigDecimal("10.00")),
                    "  idem-fallback  ",
                    "req-approve-fail",
                    "C1",
                    "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("reserve failed");
    verify(idempotencyService).markFailed(eq(failingApprove), any(RuntimeException.class));

    assertThatThrownBy(
            () ->
                dispatcher.dispatchBatch(
                    new DispatchRequest("B-9", "ops@bbp.com", BigDecimal.ZERO),
                    "idem-dispatch-zero",
                    "req-dispatch-zero",
                    "C1",
                    "ops@bbp.com"))
        .isInstanceOf(OrchestratorFeatureDisabledException.class)
        .hasMessageContaining("/api/v1/dispatch/confirm");
    verifyNoInteractions(eventPublisherService, traceService);
  }

  @Test
  void commandDispatcher_update_fulfillment_path_covers_lease_canonical_idempotency_payload() {
    WorkflowService workflowService = mock(WorkflowService.class);
    IntegrationCoordinator integrationCoordinator = mock(IntegrationCoordinator.class);
    EventPublisherService eventPublisherService = mock(EventPublisherService.class);
    TraceService traceService = mock(TraceService.class);
    PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    OrchestratorIdempotencyService idempotencyService = mock(OrchestratorIdempotencyService.class);
    OrchestratorFeatureFlags featureFlags = mock(OrchestratorFeatureFlags.class);

    CommandDispatcher dispatcher =
        new CommandDispatcher(
            workflowService,
            integrationCoordinator,
            eventPublisherService,
            traceService,
            policyEnforcer,
            idempotencyService,
            featureFlags);

    OrchestratorCommand fulfillmentCommand =
        new OrchestratorCommand(
            91L,
            "ORCH.ORDER.FULFILLMENT.UPDATE",
            "persisted-fulfillment-key",
            "hash",
            "trace-fulfillment");
    OrchestratorIdempotencyService.CommandLease lease =
        new OrchestratorIdempotencyService.CommandLease(
            "trace-fulfillment", fulfillmentCommand, true);
    when(idempotencyService.start(
            eq("ORCH.ORDER.FULFILLMENT.UPDATE"), eq("idem-fulfillment"), any(), any()))
        .thenReturn(lease);
    when(integrationCoordinator.updateFulfillment(
            "SO-900", "processing", "C1", "trace-fulfillment", "persisted-fulfillment-key"))
        .thenReturn(new IntegrationCoordinator.AutoApprovalResult("processing", true));

    String trace =
        dispatcher.updateOrderFulfillment(
            "SO-900",
            new OrderFulfillmentRequest("processing", "sync"),
            "idem-fulfillment",
            "req-fulfillment",
            "C1",
            "ops@bbp.com");

    assertThat(trace).isEqualTo("trace-fulfillment");
    verify(eventPublisherService, atLeastOnce()).enqueue(any());
    verify(traceService)
        .record(
            eq("trace-fulfillment"),
            any(),
            eq("C1"),
            any(),
            eq("req-fulfillment"),
            eq("persisted-fulfillment-key"));
    verify(idempotencyService).markSuccess(fulfillmentCommand);
  }

  @Test
  void commandDispatcher_auto_approve_path_uses_shared_lease_bootstrap_and_canonical_key() {
    WorkflowService workflowService = mock(WorkflowService.class);
    IntegrationCoordinator integrationCoordinator = mock(IntegrationCoordinator.class);
    EventPublisherService eventPublisherService = mock(EventPublisherService.class);
    TraceService traceService = mock(TraceService.class);
    PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    OrchestratorIdempotencyService idempotencyService = mock(OrchestratorIdempotencyService.class);
    OrchestratorFeatureFlags featureFlags = mock(OrchestratorFeatureFlags.class);

    CommandDispatcher dispatcher =
        new CommandDispatcher(
            workflowService,
            integrationCoordinator,
            eventPublisherService,
            traceService,
            policyEnforcer,
            idempotencyService,
            featureFlags);

    OrchestratorCommand autoApproveCommand =
        new OrchestratorCommand(
            92L, "ORCH.ORDER.AUTO_APPROVE", "persisted-auto-key", "hash", "trace-auto");
    OrchestratorIdempotencyService.CommandLease lease =
        new OrchestratorIdempotencyService.CommandLease("trace-auto", autoApproveCommand, true);
    when(idempotencyService.start(eq("ORCH.ORDER.AUTO_APPROVE"), eq("idem-auto"), any(), any()))
        .thenReturn(lease);
    when(integrationCoordinator.autoApproveOrder(
            "SO-901", new BigDecimal("250.00"), "C1", "trace-auto", "persisted-auto-key"))
        .thenReturn(new IntegrationCoordinator.AutoApprovalResult("READY_TO_SHIP", false));

    String trace =
        dispatcher.autoApproveOrder(
            "SO-901", new BigDecimal("250.00"), "C1", "idem-auto", "req-auto");

    assertThat(trace).isEqualTo("trace-auto");
    verify(eventPublisherService, atLeastOnce()).enqueue(any());
    verify(traceService)
        .record(
            eq("trace-auto"),
            eq("ORDER_AUTO_APPROVED"),
            eq("C1"),
            any(),
            eq("req-auto"),
            eq("persisted-auto-key"));
    verify(idempotencyService).markSuccess(autoApproveCommand);
  }

  @Test
  void commandDispatcher_dispatch_fails_closed_while_payroll_replay_still_short_circuits() {
    WorkflowService workflowService = mock(WorkflowService.class);
    IntegrationCoordinator integrationCoordinator = mock(IntegrationCoordinator.class);
    EventPublisherService eventPublisherService = mock(EventPublisherService.class);
    TraceService traceService = mock(TraceService.class);
    PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    OrchestratorIdempotencyService idempotencyService = mock(OrchestratorIdempotencyService.class);
    OrchestratorFeatureFlags featureFlags = mock(OrchestratorFeatureFlags.class);

    CommandDispatcher dispatcher =
        new CommandDispatcher(
            workflowService,
            integrationCoordinator,
            eventPublisherService,
            traceService,
            policyEnforcer,
            idempotencyService,
            featureFlags);

    assertThatThrownBy(
            () ->
                dispatcher.dispatchBatch(
                    new DispatchRequest("B-R1", "ops@bbp.com", BigDecimal.ZERO),
                    "idem-dispatch-replay",
                    "req-dispatch-replay",
                    "C1",
                    "ops@bbp.com"))
        .isInstanceOf(OrchestratorFeatureDisabledException.class)
        .hasMessageContaining("/api/v1/dispatch/confirm");
    verify(featureFlags, never()).isFactoryDispatchEnabled();
    verifyNoInteractions(idempotencyService);

    OrchestratorCommand replayPayroll =
        new OrchestratorCommand(
            202L, "ORCH.PAYROLL.RUN", "idem-payroll-replay", "hash", "trace-payroll-replay");
    when(idempotencyService.start(eq("ORCH.PAYROLL.RUN"), eq("idem-payroll-replay"), any(), any()))
        .thenReturn(
            new OrchestratorIdempotencyService.CommandLease(
                "trace-payroll-replay", replayPayroll, false));

    String payrollTrace =
        dispatcher.runPayroll(
            new PayrollRunRequest(
                LocalDate.of(2026, 2, 1), "ops@bbp.com", 11L, 12L, BigDecimal.ZERO),
            "idem-payroll-replay",
            "req-payroll-replay",
            "C1",
            "ops@bbp.com");

    assertThat(payrollTrace).isEqualTo("trace-payroll-replay");
    verify(featureFlags, never()).isPayrollEnabled();
    verify(idempotencyService, never()).markFailed(eq(replayPayroll), any(RuntimeException.class));
  }

  @Test
  void commandDispatcher_private_idempotency_and_requestid_helpers_cover_all_branches() {
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            mock(WorkflowService.class),
            mock(IntegrationCoordinator.class),
            mock(EventPublisherService.class),
            mock(TraceService.class),
            new PolicyEnforcer(),
            mock(OrchestratorIdempotencyService.class),
            mock(OrchestratorFeatureFlags.class));

    String hashInput = "X".repeat(160);
    String normalizedHashed =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher, "normalizeRequestId", hashInput, "idem-fallback");
    assertThat(normalizedHashed).startsWith("RIDH|");

    String normalizedFromRequest =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher, "normalizeRequestId", "  req-1  ", "idem-fallback");
    assertThat(normalizedFromRequest).isEqualTo("req-1");

    String normalizedFromFallback =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher, "normalizeRequestId", "   ", "  idem-fallback  ");
    assertThat(normalizedFromFallback).isEqualTo("idem-fallback");

    String normalizedNull =
        (String) ReflectionTestUtils.invokeMethod(dispatcher, "normalizeRequestId", null, "   ");
    assertThat(normalizedNull).isNull();

    OrchestratorCommand commandWithIdempotency =
        new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "persisted-key", "hash", "trace-1");
    OrchestratorIdempotencyService.CommandLease leaseWithCommandKey =
        new OrchestratorIdempotencyService.CommandLease("trace-1", commandWithIdempotency, true);
    String canonicalFromLease =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher, "canonicalIdempotencyKey", leaseWithCommandKey, "fallback-key");
    assertThat(canonicalFromLease).isEqualTo("persisted-key");

    OrchestratorCommand commandWithoutIdempotency =
        new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "   ", "hash", "trace-2");
    OrchestratorIdempotencyService.CommandLease leaseWithoutCommandKey =
        new OrchestratorIdempotencyService.CommandLease("trace-2", commandWithoutIdempotency, true);
    String canonicalFromFallback =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher, "canonicalIdempotencyKey", leaseWithoutCommandKey, "  fallback-key  ");
    assertThat(canonicalFromFallback).isEqualTo("fallback-key");

    OrchestratorIdempotencyService.CommandLease leaseWithNullCommand =
        new OrchestratorIdempotencyService.CommandLease("trace-3", null, true);
    String canonicalFromNullCommand =
        (String)
            ReflectionTestUtils.invokeMethod(
                dispatcher,
                "canonicalIdempotencyKey",
                leaseWithNullCommand,
                "  fallback-from-null-command  ");
    assertThat(canonicalFromNullCommand).isEqualTo("fallback-from-null-command");

    String canonicalNull =
        (String)
            ReflectionTestUtils.invokeMethod(dispatcher, "canonicalIdempotencyKey", null, null);
    assertThat(canonicalNull).isNull();
  }
}
