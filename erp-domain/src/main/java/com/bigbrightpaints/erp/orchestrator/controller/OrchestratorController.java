package com.bigbrightpaints.erp.orchestrator.controller;

import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.OrderFulfillmentRequest;
import com.bigbrightpaints.erp.orchestrator.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.orchestrator.service.CommandDispatcher;
import com.bigbrightpaints.erp.orchestrator.service.TraceService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orchestrator")
public class OrchestratorController {

    private final CommandDispatcher commandDispatcher;
    private final TraceService traceService;
    private final boolean orderDispatchEnabled;
    private final boolean payrollEnabled;
    private final boolean factoryDispatchEnabled;

    public OrchestratorController(CommandDispatcher commandDispatcher,
                                  TraceService traceService,
                                  @Value("${orchestrator.order-dispatch.enabled:false}") boolean orderDispatchEnabled,
                                  @Value("${orchestrator.payroll.enabled:false}") boolean payrollEnabled,
                                  @Value("${orchestrator.factory-dispatch.enabled:false}") boolean factoryDispatchEnabled) {
        this.commandDispatcher = commandDispatcher;
        this.traceService = traceService;
        this.orderDispatchEnabled = orderDispatchEnabled;
        this.payrollEnabled = payrollEnabled;
        this.factoryDispatchEnabled = factoryDispatchEnabled;
    }

    @PostMapping("/orders/{orderId}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> approveOrder(@PathVariable String orderId,
                                                             @Valid @RequestBody ApproveOrderRequest request,
                                                             @RequestHeader("X-Company-Id") String companyId,
                                                             Principal principal) {
        ApproveOrderRequest normalized = new ApproveOrderRequest(orderId, request.approvedBy(), request.totalAmount());
        String traceId = commandDispatcher.approveOrder(normalized, companyId, principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    @PostMapping("/orders/{orderId}/fulfillment")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<Map<String, Object>> fulfillOrder(@PathVariable String orderId,
                                                             @Valid @RequestBody OrderFulfillmentRequest request,
                                                             @RequestHeader("X-Company-Id") String companyId,
                                                             Principal principal) {
        String traceId = commandDispatcher.updateOrderFulfillment(orderId, request, companyId, principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    @PostMapping("/factory/dispatch/{batchId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (hasAuthority('ROLE_FACTORY') and hasAuthority('factory.dispatch'))")
    public ResponseEntity<Map<String, Object>> dispatch(@PathVariable String batchId,
                                                         @Valid @RequestBody DispatchRequest request,
                                                         @RequestHeader("X-Company-Id") String companyId,
                                                         Principal principal) {
        if (!factoryDispatchEnabled) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of(
                            "message", "Orchestrator factory dispatch is disabled (CODE-RED).",
                            "canonicalPath", "/api/v1/factory"));
        }
        DispatchRequest normalized = new DispatchRequest(batchId,
                request.requestedBy(),
                request.postingAmount());
        String traceId = commandDispatcher.dispatchBatch(normalized, companyId, principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    /**
     * Lightweight dispatch endpoint used by smoke/E2E tests to trigger fulfillment without batch context.
     */
    @PostMapping("/dispatch")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> dispatchOrder(@RequestBody Map<String, Object> request,
                                                              @RequestHeader("X-Company-Id") String companyId,
                                                              Principal principal) {
        Object orderId = request.get("orderId");
        return handleDispatchOrder(orderId != null ? orderId.toString() : null, companyId, principal);
    }

    @PostMapping("/dispatch/{orderId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> dispatchOrderAlias(@PathVariable String orderId,
                                                                  @RequestHeader("X-Company-Id") String companyId,
                                                                  Principal principal) {
        return handleDispatchOrder(orderId, companyId, principal);
    }

    @PostMapping("/payroll/run")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and hasAuthority('payroll.run')")
    public ResponseEntity<Map<String, Object>> runPayroll(@Valid @RequestBody PayrollRunRequest request,
                                                           @RequestHeader("X-Company-Id") String companyId,
                                                           Principal principal) {
        if (!payrollEnabled) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of(
                            "message", "Orchestrator payroll run is disabled (CODE-RED).",
                            "canonicalPath", "/api/v1/hr/payroll-runs"));
        }
        String traceId = commandDispatcher.runPayroll(request, companyId, principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String traceId) {
        return ResponseEntity.ok(Map.of("traceId", traceId, "events", traceService.getTrace(traceId)));
    }

    @GetMapping("/health/integrations")
    public ResponseEntity<Map<String, Object>> integrationsHealth() {
        return ResponseEntity.ok(commandDispatcher.integrationHealth());
    }

    @GetMapping("/health/events")
    public ResponseEntity<Map<String, Object>> eventHealth() {
        return ResponseEntity.ok(commandDispatcher.eventHealth());
    }

    private ResponseEntity<Map<String, Object>> handleDispatchOrder(String orderId,
                                                                     String companyId,
                                                                     Principal principal) {
        if (!orderDispatchEnabled) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of(
                            "message", "Orchestrator dispatch is deprecated; use /api/v1/sales/dispatch/confirm",
                            "canonicalPath", "/api/v1/sales/dispatch/confirm"));
        }
        if (orderId == null || orderId.isBlank()) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("message", "orderId is required"));
        }
        try {
            OrderFulfillmentRequest fulfillmentRequest = new OrderFulfillmentRequest("DISPATCHED", "auto-dispatch");
            String traceId = commandDispatcher.updateOrderFulfillment(orderId, fulfillmentRequest,
                    companyId, principal.getName());
            return ResponseEntity.accepted().body(Map.of("traceId", traceId));
        } catch (Exception ex) {
            String traceId = commandDispatcher.generateTraceId();
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = ex.getClass().getSimpleName();
            }
            return ResponseEntity.badRequest()
                    .body(new HashMap<>(Map.of("traceId", traceId, "message", message)));
        }
    }
}
