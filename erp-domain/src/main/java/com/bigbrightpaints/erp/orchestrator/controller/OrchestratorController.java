package com.bigbrightpaints.erp.orchestrator.controller;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.OrderFulfillmentRequest;
import com.bigbrightpaints.erp.orchestrator.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.orchestrator.exception.OrchestratorFeatureDisabledException;
import com.bigbrightpaints.erp.orchestrator.service.CommandDispatcher;
import com.bigbrightpaints.erp.orchestrator.service.TraceService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/orchestrator")
public class OrchestratorController {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final String SALES_DISPATCH_CANONICAL_PATH = "/api/v1/sales/dispatch/confirm";
    private static final String PAYROLL_RUNS_CANONICAL_PATH = "/api/v1/payroll/runs";

    private final CommandDispatcher commandDispatcher;
    private final TraceService traceService;

    public OrchestratorController(CommandDispatcher commandDispatcher,
                                  TraceService traceService) {
        this.commandDispatcher = commandDispatcher;
        this.traceService = traceService;
    }

    @PostMapping("/orders/{orderId}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> approveOrder(@PathVariable String orderId,
                                                             @Valid @RequestBody ApproveOrderRequest request,
                                                             @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                             Principal principal) {
        String companyCode = requireCompanyCode();
        ApproveOrderRequest raw = new ApproveOrderRequest(
                orderId,
                request.approvedBy(),
                request.totalAmount());
        ApproveOrderRequest normalized = new ApproveOrderRequest(
                orderId,
                canonicalText(request.approvedBy()),
                normalizeAmount(request.totalAmount()));
        String traceId = commandDispatcher.approveOrder(
                selectPayloadForIdempotency(idempotencyKey, raw, normalized),
                resolveIdempotencyKey(
                        idempotencyKey,
                        requestId,
                        "ORCH.ORDER.APPROVE",
                        companyCode,
                        normalized.orderId() + "|" + canonicalText(normalized.approvedBy()) + "|" + canonicalAmount(normalized.totalAmount())
                ),
                requestId,
                companyCode,
                principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    @PostMapping("/orders/{orderId}/fulfillment")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<Map<String, Object>> fulfillOrder(@PathVariable String orderId,
                                                             @Valid @RequestBody OrderFulfillmentRequest request,
                                                             @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                             Principal principal) {
        String companyCode = requireCompanyCode();
        OrderFulfillmentRequest normalized = new OrderFulfillmentRequest(
                normalizeFulfillmentStatus(request.status()),
                canonicalText(request.notes()));
        String traceId = commandDispatcher.updateOrderFulfillment(
                orderId,
                selectPayloadForIdempotency(idempotencyKey, request, normalized),
                resolveIdempotencyKey(
                        idempotencyKey,
                        requestId,
                        "ORCH.ORDER.FULFILLMENT.UPDATE",
                        companyCode,
                        orderId + "|" + canonicalText(normalized.status()) + "|" + canonicalText(normalized.notes())
                ),
                requestId,
                companyCode,
                principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    @PostMapping("/factory/dispatch/{batchId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (hasAuthority('ROLE_FACTORY') and hasAuthority('factory.dispatch'))")
    public ResponseEntity<Map<String, Object>> dispatch(@PathVariable String batchId,
                                                         @Valid @RequestBody DispatchRequest request,
                                                         @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                         @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                         Principal principal) {
        String companyCode = requireCompanyCode();
        DispatchRequest raw = new DispatchRequest(batchId,
                request.requestedBy(),
                request.postingAmount());
        DispatchRequest normalized = new DispatchRequest(batchId,
                canonicalText(request.requestedBy()),
                normalizeAmount(request.postingAmount()));
        String traceId = commandDispatcher.dispatchBatch(
                selectPayloadForIdempotency(idempotencyKey, raw, normalized),
                resolveIdempotencyKey(
                        idempotencyKey,
                        requestId,
                        "ORCH.FACTORY.BATCH.DISPATCH",
                        companyCode,
                        normalized.batchId() + "|" + canonicalText(normalized.requestedBy()) + "|" + canonicalAmount(normalized.postingAmount())
                ),
                requestId,
                companyCode,
                principal.getName());
        return ResponseEntity.accepted().body(Map.of("traceId", traceId));
    }

    /**
     * Lightweight dispatch endpoint used by smoke/E2E tests to trigger fulfillment without batch context.
     */
    @PostMapping("/dispatch")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> dispatchOrder() {
        return goneToCanonical(
                "Orchestrator dispatch is deprecated; use " + SALES_DISPATCH_CANONICAL_PATH,
                SALES_DISPATCH_CANONICAL_PATH);
    }

    @PostMapping("/dispatch/{orderId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<Map<String, Object>> dispatchOrderAlias(@PathVariable String orderId) {
        return goneToCanonical(
                "Orchestrator dispatch is deprecated; use " + SALES_DISPATCH_CANONICAL_PATH,
                SALES_DISPATCH_CANONICAL_PATH);
    }

    @PostMapping("/payroll/run")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and hasAuthority('payroll.run')")
    public ResponseEntity<Map<String, Object>> runPayroll(@RequestBody(required = false) PayrollRunRequest request,
                                                          @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                          Principal principal) {
        return goneToCanonical(
                "Orchestrator payroll run is deprecated; use " + PAYROLL_RUNS_CANONICAL_PATH,
                PAYROLL_RUNS_CANONICAL_PATH);
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES','ROLE_FACTORY')")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String traceId) {
        return ResponseEntity.ok(Map.of("traceId", traceId, "events", traceService.getTrace(traceId)));
    }

    @GetMapping("/health/integrations")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> integrationsHealth() {
        return ResponseEntity.ok(commandDispatcher.integrationHealth());
    }

    @GetMapping("/health/events")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> eventHealth() {
        return ResponseEntity.ok(commandDispatcher.eventHealth());
    }

    private ResponseEntity<Map<String, Object>> goneToCanonical(String message,
                                                                String canonicalPath) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of(
                        "message", message,
                        "canonicalPath", canonicalPath));
    }

    private String requireCompanyCode() {
        String companyCode = CompanyContextHolder.getCompanyCode();
        if (!StringUtils.hasText(companyCode)) {
            throw new IllegalStateException("Company context is required");
        }
        return companyCode.trim();
    }

    private String resolveIdempotencyKey(String idempotencyKey,
                                         String requestId,
                                         String commandName,
                                         String companyCode,
                                         String payloadSignature) {
        if (StringUtils.hasText(idempotencyKey)) {
            return idempotencyKey.trim();
        }
        if (StringUtils.hasText(requestId)) {
            String requestScoped = "REQ|" + commandName + "|" + requestId.trim();
            if (requestScoped.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
                return requestScoped;
            }
            return "REQH|" + commandName + "|" + sha256Hex(requestScoped);
        }
        String source = commandName + "|" + canonicalText(companyCode) + "|" + canonicalText(payloadSignature);
        return "AUTO|" + commandName + "|" + sha256Hex(source);
    }

    private static String normalizeFulfillmentStatus(String status) {
        return canonicalText(status).toUpperCase(Locale.ROOT);
    }

    private static String canonicalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> T selectPayloadForIdempotency(String idempotencyKey,
                                                     T rawPayload,
                                                     T normalizedPayload) {
        return StringUtils.hasText(idempotencyKey) ? rawPayload : normalizedPayload;
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros();
    }

    private static String canonicalAmount(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @ExceptionHandler(OrchestratorFeatureDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureDisabled(OrchestratorFeatureDisabledException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of(
                        "message", ex.getMessage(),
                        "canonicalPath", ex.getCanonicalPath()));
    }
}
