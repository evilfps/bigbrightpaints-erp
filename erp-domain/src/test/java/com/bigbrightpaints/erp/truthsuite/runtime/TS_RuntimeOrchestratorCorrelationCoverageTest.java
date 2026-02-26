package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.controller.OrchestratorController;
import com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRecord;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRepository;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommandRepository;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;
import com.bigbrightpaints.erp.orchestrator.service.CommandDispatcher;
import com.bigbrightpaints.erp.orchestrator.service.CorrelationIdentifierSanitizer;
import com.bigbrightpaints.erp.orchestrator.service.EventPublisherService;
import com.bigbrightpaints.erp.orchestrator.service.IntegrationCoordinator;
import com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService;
import com.bigbrightpaints.erp.orchestrator.service.TraceService;
import com.bigbrightpaints.erp.orchestrator.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeOrchestratorCorrelationCoverageTest {

    @Test
    void orchestratorController_trace_endpoint_sanitizes_and_delegates() {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        TraceService traceService = mock(TraceService.class);
        List<AuditRecord> events = List.of();
        when(traceService.getTrace("trace-42")).thenReturn(events);

        OrchestratorController controller = new OrchestratorController(dispatcher, traceService);
        Map<String, Object> payload = controller.trace(" trace-42 ").getBody();

        assertThat(payload).containsEntry("traceId", "trace-42");
        assertThat(payload).containsEntry("events", events);
        verify(traceService).getTrace("trace-42");
    }

    @Test
    void orchestratorController_resolve_idempotency_key_hashes_oversized_request_scope() {
        OrchestratorController controller = new OrchestratorController(mock(CommandDispatcher.class), mock(TraceService.class));

        String direct = ReflectionTestUtils.invokeMethod(
                controller,
                "resolveIdempotencyKey",
                null,
                "req-10",
                "ORCH.ORDER.FULFILLMENT.UPDATE",
                "COMP",
                "payload");
        String hashed = ReflectionTestUtils.invokeMethod(
                controller,
                "resolveIdempotencyKey",
                null,
                "r".repeat(512),
                "ORCH.ORDER.FULFILLMENT.UPDATE",
                "COMP",
                "payload");

        assertThat(direct).isEqualTo("REQ|ORCH.ORDER.FULFILLMENT.UPDATE|req-10");
        assertThat(hashed).startsWith("REQH|ORCH.ORDER.FULFILLMENT.UPDATE|");
        assertThat(hashed.length()).isLessThanOrEqualTo(CorrelationIdentifierSanitizer.MAX_IDEMPOTENCY_KEY_LENGTH);
    }

    @Test
    void commandDispatcher_trace_summary_sanitizes_trace_and_fetches_events() {
        TraceService traceService = mock(TraceService.class);
        List<AuditRecord> events = List.of();
        when(traceService.getTrace("trace-summary")).thenReturn(events);

        CommandDispatcher dispatcher = new CommandDispatcher(
                mock(WorkflowService.class),
                mock(IntegrationCoordinator.class),
                mock(EventPublisherService.class),
                traceService,
                new PolicyEnforcer(),
                mock(OrchestratorIdempotencyService.class),
                new OrchestratorFeatureFlags(true, true));

        Map<String, Object> summary = dispatcher.traceSummary(" trace-summary ");

        assertThat(summary).containsEntry("traceId", "trace-summary");
        assertThat(summary).containsEntry("events", events);
        verify(traceService).getTrace("trace-summary");
    }

    @Test
    void correlationIdentifierSanitizer_covers_request_and_trace_fallback_paths() {
        String normalizedFromLongIdempotency = CorrelationIdentifierSanitizer.normalizeRequestId(
                null,
                "idem-" + "x".repeat(160));

        assertThat(normalizedFromLongIdempotency).startsWith("RIDH|");
        assertThat(normalizedFromLongIdempotency.length())
                .isLessThanOrEqualTo(CorrelationIdentifierSanitizer.MAX_REQUEST_ID_LENGTH);

        assertThatThrownBy(() -> CorrelationIdentifierSanitizer.sanitizeTraceIdOrFallback("bad\ntrace", null))
                .isInstanceOf(ApplicationException.class);

        String fallback = CorrelationIdentifierSanitizer.sanitizeTraceIdOrFallback(
                "bad\ntrace",
                () -> " trace-safe ");
        assertThat(fallback).isEqualTo("trace-safe");
    }

    @Test
    void correlationIdentifierSanitizer_covers_safe_log_and_control_character_branches() {
        assertThat(CorrelationIdentifierSanitizer.safeTraceForLog(null)).isNull();
        assertThat(CorrelationIdentifierSanitizer.safeTraceForLog("   ")).isNull();
        assertThat(CorrelationIdentifierSanitizer.safeTraceForLog("trace-ok")).startsWith("trace-ok#");
        assertThat(CorrelationIdentifierSanitizer.safeIdempotencyForLog("idem-bad\nvalue")).startsWith("invalid#");
        assertThat(CorrelationIdentifierSanitizer.sanitizeOptionalTraceId("   ")).isNull();

        assertThatThrownBy(() -> CorrelationIdentifierSanitizer.sanitizeOptionalTraceId("trace\nid"))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> CorrelationIdentifierSanitizer.sanitizeOptionalTraceId("trace\rid"))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> CorrelationIdentifierSanitizer.sanitizeOptionalTraceId("trace\u0007id"))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void correlationIdentifierSanitizer_invalid_identifier_handles_null_value_path() {
        ApplicationException ex = ReflectionTestUtils.invokeMethod(
                CorrelationIdentifierSanitizer.class,
                "invalidIdentifier",
                "traceId",
                null,
                CorrelationIdentifierSanitizer.MAX_TRACE_ID_LENGTH,
                "unit_test");

        assertThat(ex).isNotNull();
        assertThat(ex.getDetails())
                .containsEntry("actualLength", 0)
                .containsEntry("fingerprint", "000000000000");
    }

    @Test
    void integrationCoordinator_generatePayroll_includes_optional_details_only_when_present() {
        IntegrationCoordinator coordinator = coordinator(mock(SalesService.class), new OrchestratorFeatureFlags(true, true));

        assertThatThrownBy(() -> coordinator.generatePayroll(
                LocalDate.of(2026, 2, 1),
                new BigDecimal("1000"),
                "COMP",
                " trace-100 ",
                " idem-100 "))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getDetails())
                            .containsEntry("canonicalPath", "/api/v1/payroll/runs")
                            .containsEntry("traceId", "trace-100")
                            .containsEntry("idempotencyKey", "idem-100");
                });

        assertThatThrownBy(() -> coordinator.generatePayroll(
                LocalDate.of(2026, 2, 1),
                new BigDecimal("1000"),
                "COMP",
                "   ",
                null))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getDetails())
                            .containsEntry("canonicalPath", "/api/v1/payroll/runs")
                            .doesNotContainKeys("traceId", "idempotencyKey");
                });
    }

    @Test
    void integrationCoordinator_correlation_helpers_cover_append_and_skip_branches() {
        IntegrationCoordinator coordinator = coordinator(mock(SalesService.class), new OrchestratorFeatureFlags(true, true));

        String memoWithCorrelation = ReflectionTestUtils.invokeMethod(
                coordinator,
                "correlationMemo",
                "dispatch memo",
                " trace-200 ",
                " idem-200 ");
        String memoWithoutCorrelation = ReflectionTestUtils.invokeMethod(
                coordinator,
                "correlationMemo",
                "dispatch memo",
                "   ",
                null);
        String suffixWithCorrelation = ReflectionTestUtils.invokeMethod(
                coordinator,
                "correlationSuffix",
                " trace-201 ",
                " idem-201 ");
        String suffixWithoutCorrelation = ReflectionTestUtils.invokeMethod(
                coordinator,
                "correlationSuffix",
                null,
                "   ");

        assertThat(memoWithCorrelation).contains("[trace=trace-200]").contains("[idem=idem-200]");
        assertThat(memoWithoutCorrelation).isEqualTo("dispatch memo");
        assertThat(suffixWithCorrelation).contains("[trace=trace-201").contains("[idem=idem-201");
        assertThat(suffixWithoutCorrelation).isEmpty();
    }

    @Test
    void integrationCoordinator_attachOrderTrace_skips_invalid_inputs_and_sanitizes_valid_trace() {
        SalesService salesService = mock(SalesService.class);
        IntegrationCoordinator coordinator = coordinator(salesService, new OrchestratorFeatureFlags(true, true));

        ReflectionTestUtils.invokeMethod(coordinator, "attachOrderTrace", (Long) null, "trace-null-order");
        ReflectionTestUtils.invokeMethod(coordinator, "attachOrderTrace", 77L, "   ");
        ReflectionTestUtils.invokeMethod(coordinator, "attachOrderTrace", 78L, " trace-78 ");

        verify(salesService, never()).attachTraceId(eq(77L), any());
        verify(salesService).attachTraceId(78L, "trace-78");
    }

    @Test
    void traceService_getTrace_sanitizes_and_scopes_by_company() {
        AuditRepository auditRepository = mock(AuditRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TraceService traceService = new TraceService(
                auditRepository,
                mock(CompanyRepository.class),
                companyContextService,
                new ObjectMapper());

        Company company = company(77L);
        List<AuditRecord> expected = List.of();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(auditRepository.findByTraceIdAndCompanyIdOrderByTimestampAsc("trace-77", 77L))
                .thenReturn(expected);

        List<AuditRecord> actual = traceService.getTrace(" trace-77 ");

        assertThat(actual).isSameAs(expected);
        verify(auditRepository).findByTraceIdAndCompanyIdOrderByTimestampAsc("trace-77", 77L);
    }

    @Test
    void orchestratorIdempotencyService_start_uses_generated_trace_when_supplier_is_missing() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(901L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager());

        Map<String, Object> payload = Map.of("orderId", "SO-901", "total", "100.00");
        String requestHash = ReflectionTestUtils.invokeMethod(
                service,
                "hashRequest",
                901L,
                "ORCH.ORDER.APPROVE",
                payload);

        when(commandRepository.reserveScope(
                eq(901L),
                eq("ORCH.ORDER.APPROVE"),
                eq("idem-auto-trace"),
                eq(requestHash),
                any()))
                .thenReturn(1);
        OrchestratorCommand existing = new OrchestratorCommand(
                901L,
                "ORCH.ORDER.APPROVE",
                "idem-auto-trace",
                requestHash,
                "trace-existing");
        when(commandRepository.lockByScope(901L, "ORCH.ORDER.APPROVE", "idem-auto-trace"))
                .thenReturn(Optional.of(existing));

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                "idem-auto-trace",
                payload,
                null);

        assertThat(lease.shouldExecute()).isTrue();
        assertThat(lease.traceId()).isEqualTo("trace-existing");

        ArgumentCaptor<String> traceCaptor = ArgumentCaptor.forClass(String.class);
        verify(commandRepository).reserveScope(
                eq(901L),
                eq("ORCH.ORDER.APPROVE"),
                eq("idem-auto-trace"),
                eq(requestHash),
                traceCaptor.capture());
        assertThat(traceCaptor.getValue()).isNotBlank();
    }

    private IntegrationCoordinator coordinator(SalesService salesService, OrchestratorFeatureFlags featureFlags) {
        return new IntegrationCoordinator(
                salesService,
                mock(FactoryService.class),
                mock(FinishedGoodsService.class),
                mock(InvoiceService.class),
                mock(AccountingService.class),
                mock(SalesJournalService.class),
                mock(HrService.class),
                mock(ReportService.class),
                mock(OrderAutoApprovalStateRepository.class),
                mock(AccountingFacade.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyDefaultAccountsService.class),
                mock(CompanyContextService.class),
                mock(CompanyClock.class),
                featureFlags,
                new ResourcelessTransactionManager(),
                10L,
                20L);
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setCode("COMP-" + id);
        company.setName("Company " + id);
        company.setTimezone("UTC");
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }
}
